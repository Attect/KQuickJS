package app.muka.project.kquickjs

import app.muka.project.kquickjs.memory.getMTag
import app.muka.project.kquickjs.parser.JSParseState
import app.muka.project.kquickjs.parser.JSParser
import app.muka.project.kquickjs.runtime.JSRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/* GC 回归测试：
 * - 验证死对象被回收（压缩后堆收缩）
 * - 验证用户 finalizer 在对象释放时被调用（同步上游 #71）
 * - 验证用户对象私有 JSValue 经 GC 搬移后仍有效（同步上游 #80）
 * - 验证宿主 opaque 引用随压缩保持可读 */
class GCTest {
    
    /* JS_CLASS_USER = 28，这里额外注册 1 个用户类（classId = 28） */
    private val userClassId = JSObjectClassEnum.JS_CLASS_USER.value
    
    private fun createContext(
        finalizers: Array<((ctx: JSContext, opaque: Any?) -> Unit)?> = emptyArray()
    ): JSContext {
        val memSize = 65536
        val mem = ByteArray(memSize)
        val stdlib = JSSTDLibraryDef(
            stdlibTable = LongArray(0),
            cFunctionTable = emptyArray(),
            cFinalizerTable = finalizers,
            stdlibTableLen = 0,
            stdlibTableAlign = 8,
            sortedAtomsOffset = 0,
            globalObjectOffset = 0,
            classCount = userClassId + 1
        )
        return JS_NewContext(mem, memSize, stdlib)
    }
    
    @Test
    fun testGcReclaimsDeadObjects() {
        val ctx = createContext()
        
        /* 先分配一批无引用的死对象（数组 extraSize = 8） */
        repeat(8) { JS_NewArray(ctx, 4) }
        val heapBefore = ctx.heapFree
        
        /* 分配一个存活对象并压入执行栈作为 GC root */
        val live = JS_NewArray(ctx, 4)
        ctx.pushValue(live)
        
        ctx.gc()
        
        /* 死对象应被回收：堆占用明显收缩 */
        val heapAfter = ctx.heapFree
        assertTrue(heapAfter < heapBefore, "heap should shrink after gc: before=$heapBefore after=$heapAfter")
        
        /* 存活对象内容应完好 */
        val livePtr = JS_VALUE_TO_PTR(live)
        assertEquals(JSMTags.JS_MTAG_OBJECT, ctx.memory.getMTag(livePtr))
        val classId = ctx.memory.getU8(livePtr + 2)
        assertEquals(JSObjectClassEnum.JS_CLASS_ARRAY.value, classId)
        assertEquals(4, ctx.memory.getI32(livePtr + 32))
        
        ctx.popValue()
    }
    
    @Test
    fun testFinalizerCalledOnGc() {
        val finalized = mutableListOf<Any?>()
        val fin: ((ctx: JSContext, opaque: Any?) -> Unit)? = { _, opaque -> finalized.add(opaque) }
        val ctx = createContext(finalizers = arrayOf(fin))
        
        /* 创建用户对象并绑定宿主 opaque 资源 */
        val obj = JS_NewObjectClassUser(ctx, userClassId, 0)
        assertTrue(!JS_IsException(obj))
        JS_SetOpaque(ctx, obj, "resource-A")
        
        /* 保活期间 GC：finalizer 不应被调用 */
        ctx.pushValue(obj)
        ctx.gc()
        assertEquals(0, finalized.size, "finalizer must not run while object is alive")
        
        /* 丢弃引用后 GC：finalizer 应收到 opaque 并清理侧表条目 */
        ctx.popValue()
        ctx.gc()
        assertEquals(1, finalized.size, "finalizer should be called once after object is freed")
        assertEquals("resource-A", finalized[0])
        assertTrue(ctx.userOpaqueTable.isEmpty(), "opaque table entry should be removed after free")
    }
    
    @Test
    fun testUserValueSurvivesGc() {
        val ctx = createContext()
        
        /* 用户对象持有 2 个私有 JSValue，其中槽 0 引用一个数组
         * （该数组仅通过 user value 可达，必须依赖 GC 的 user values 标记分支存活） */
        var userObj = JS_NewObjectClassUser(ctx, userClassId, 2)
        assertTrue(!JS_IsException(userObj))
        /* 先压栈保活：后续分配可能触发 GC */
        ctx.pushValue(userObj)
        
        val inner = JS_NewArray(ctx, 4)
        JS_SetUserValue(ctx, userObj, 0, inner)
        JS_SetUserValue(ctx, userObj, 1, JS_NewShortInt(42))
        /* inner 同时压栈：GC 后与 user value 中的地址对照 */
        ctx.pushValue(inner)
        
        ctx.gc()
        
        /* GC 压缩会搬移对象：从栈上读回最新地址（值可能移动，局部值会过期） */
        userObj = ctx.memory.getJSValue(ctx.sp + JSW)
        val innerAfterGc = ctx.memory.getJSValue(ctx.sp)
        
        /* inner 应随搬移保持有效且数据完好 */
        val kept = JS_GetUserValue(ctx, userObj, 0)
        assertTrue(JS_IsPtr(kept), "user value should remain a valid pointer after gc")
        /* user value 与栈引用应指向同一搬移后的对象 */
        assertEquals(JS_VALUE_TO_PTR(innerAfterGc), JS_VALUE_TO_PTR(kept))
        
        val innerPtr = JS_VALUE_TO_PTR(kept)
        assertEquals(JSObjectClassEnum.JS_CLASS_ARRAY.value, ctx.memory.getU8(innerPtr + 2))
        assertEquals(4, ctx.memory.getI32(innerPtr + 32))
        
        /* 非指针值应原样保留 */
        assertEquals(JS_NewShortInt(42), JS_GetUserValue(ctx, userObj, 1))
        
        ctx.popValue()  /* inner */
        ctx.popValue()  /* userObj */
    }
    
    @Test
    fun testOpaqueKeptAcrossGcCompaction() {
        val finalized = mutableListOf<Any?>()
        val fin: ((ctx: JSContext, opaque: Any?) -> Unit)? = { _, opaque -> finalized.add(opaque) }
        val ctx = createContext(finalizers = arrayOf(fin))
        
        /* 制造一批死对象迫使压缩搬移存活块 */
        repeat(8) { JS_NewArray(ctx, 4) }
        
        val obj = JS_NewObjectClassUser(ctx, userClassId, 1)
        /* 先压栈保活：后续分配可能触发 GC */
        ctx.pushValue(obj)
        JS_SetOpaque(ctx, obj, "keep-me")
        JS_SetUserValue(ctx, obj, 0, JS_NewArray(ctx, 2))
        
        val opaqueBeforeGc = JS_GetOpaque(ctx, obj)
        assertEquals("keep-me", opaqueBeforeGc)
        
        ctx.gc()
        
        /* GC 压缩会搬移对象：从栈上读回最新地址 */
        val objAfterGc = ctx.memory.getJSValue(ctx.sp)
        assertEquals(JS_VALUE_TO_PTR(objAfterGc), JS_VALUE_TO_PTR(objAfterGc))
        
        /* 压缩后 opaque 应仍可读（侧表 key 随块搬移更新） */
        assertEquals("keep-me", JS_GetOpaque(ctx, objAfterGc), "opaque should survive gc compaction")
        assertEquals(0, finalized.size)
        
        /* 搬移后 user value 仍有效 */
        val v = JS_GetUserValue(ctx, objAfterGc, 0)
        assertTrue(JS_IsPtr(v))
        
        ctx.popValue()
        ctx.gc()
        assertEquals(listOf<Any?>("keep-me"), finalized)
    }
    
    @Test
    fun testPublicApis() {
        val ctx = createContext()
        
        /* JS_IsArray（同步上游公共 API） */
        val arr = JS_NewArray(ctx, 2)
        assertTrue(JS_IsArray(ctx, arr))
        assertTrue(!JS_IsArray(ctx, JS_NewObject(ctx)))
        assertTrue(!JS_IsArray(ctx, JS_NewShortInt(1)))
        assertTrue(!JS_IsArray(ctx, JS_NULL))
        assertTrue(!JS_IsArray(ctx, JS_UNDEFINED))
        
        /* JS_SetContextOpaque / JS_GetContextOpaque（同步上游公共 API） */
        JS_SetContextOpaque(ctx, "host-data")
        assertEquals("host-data", JS_GetContextOpaque(ctx))
        JS_SetContextOpaque(ctx, null)
        assertEquals(null, JS_GetContextOpaque(ctx))
    }
    
    @Test
    fun testDateSupport() {
        val ctx = createContext()
        
        /* JS_NewDate + valueOf（同步上游 new Date() 支持） */
        val d = JS_NewDate(ctx, 1717488000000.0)
        assertTrue(!JS_IsException(d), "JS_NewDate should succeed")
        assertEquals(JSObjectClassEnum.JS_CLASS_DATE.value, JS_GetClassID(ctx, d))
        
        val v = jsDateValueOf(ctx, d)
        assertTrue(JS_IsNumber(ctx, v), "valueOf should return a number")
        assertEquals(1717488000000.0, JS_ToNumber(ctx, v), 1e-6)
        
        /* 非 Date 对象调用 valueOf 应失败 */
        assertTrue(JS_IsException(jsDateValueOf(ctx, JS_NewObject(ctx))))
        assertTrue(JS_IsException(jsDateValueOf(ctx, JS_NewShortInt(1))))
        
        /* Date 对象经 GC 搬移后数据完好 */
        ctx.pushValue(d)
        repeat(4) { JS_NewArray(ctx, 4) }
        ctx.gc()
        val dAfterGc = ctx.memory.getJSValue(ctx.sp)
        assertEquals(1717488000000.0, JS_ToNumber(ctx, jsDateValueOf(ctx, dAfterGc)), 1e-6)
        ctx.popValue()
    }
    
    @Test
    fun testHostDateConstructor() {
        val ctx = createContext()
        
        /* 无参 → 当前时间（与宿主时钟误差 < 5 秒） */
        val now = MQuickJS.mqjsDateConstructor(ctx, 0, LongArray(0))
        assertTrue(!JS_IsException(now), "Date() should succeed")
        val nowMs = JS_ToNumber(ctx, jsDateValueOf(ctx, now))
        assertTrue(Math.abs(nowMs - System.currentTimeMillis()) < 5000,
                   "Date() should be close to now: $nowMs")
        
        /* 单数值参数 → 该 epoch 毫秒值 */
        val epoch = JS_NewFloat64(ctx, 1717488000000.0)
        val at = MQuickJS.mqjsDateConstructor(ctx, 1, longArrayOf(epoch))
        assertTrue(!JS_IsException(at))
        assertEquals(1717488000000.0, JS_ToNumber(ctx, jsDateValueOf(ctx, at)), 1e-6)
        
        /* 非数值参数 → 异常 */
        assertTrue(JS_IsException(MQuickJS.mqjsDateConstructor(ctx, 1, longArrayOf(JS_NewString(ctx, "x")))))
        
        /* 构造器调用标志位（FRAME_CF_CTOR）应被剥离 */
        val ctorFlagged = MQuickJS.mqjsDateConstructor(ctx, 0 or FRAME_CF_CTOR, LongArray(0))
        assertTrue(!JS_IsException(ctorFlagged), "constructor call flag should be stripped from argc")
    }
    
    @Test
    fun testGcKeepsClosuresRunnable() {
        /* 闭包脚本（内层函数捕获外层 var）在「不 GC」与「解析后 GC 压缩再执行」
         * 两种情况下行为必须一致，验证字节码/闭包变量经 GC 搬移后仍正确 */
        val code = "var r = 0; var f = function() { var x = 1; var g = function() { return x + 1 }; r = g(); }; f(); r"
        
        val ctxA = createContext()
        val stateA = JSParseState(ctxA, code.toByteArray(), "a.js", JS_EVAL_RETVAL)
        val resultA = JSParser(stateA).parse()
        assertTrue(!JS_IsException(resultA), "parse should succeed")
        val ra = JSRuntime(ctxA).callFunction(resultA, emptyList())
        
        val ctxB = createContext()
        val stateB = JSParseState(ctxB, code.toByteArray(), "b.js", JS_EVAL_RETVAL)
        val resultB = JSParser(stateB).parse()
        assertTrue(!JS_IsException(resultB), "parse should succeed")
        /* 保活函数字节码块，GC 压缩后从栈上读回最新地址（值可能移动） */
        ctxB.pushValue(resultB)
        ctxB.gc()
        val resultAfterGc = ctxB.memory.getJSValue(ctxB.sp)
        val rb = JSRuntime(ctxB).callFunction(resultAfterGc, emptyList())
        
        assertTrue(!JS_IsException(ra), "call without gc should succeed")
        assertTrue(!JS_IsException(rb), "call after gc should succeed")
        assertEquals(ra, rb, "gc compaction must not change program behavior")
    }
}
