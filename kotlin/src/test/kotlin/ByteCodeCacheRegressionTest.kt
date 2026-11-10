package app.muka.project.kquickjs

import app.muka.project.kquickjs.bridge.JSBridge
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 字节码内容缓存回归测试。
 *
 * 缺陷史：runBody 曾按「函数块指针地址 → 字节码 ByteArray 副本」缓存字节码内容。
 * 该缓存会在两种场景命中陈旧副本，造成「字节码（N 槽布局）× 调用帧（M 槽）」错配，
 * 以 OP_add_loc_loc 等超级指令访问 vars[262] 之类远超帧容量的索引，抛
 * IndexOutOfBoundsException：
 * 1. GC 压缩搬移后地址复用：新函数对象落在与旧条目 key 相同的地址上；
 * 2. 同名函数重新 eval：函数块槽 16 的字节码对象被替换，而缓存副本仍是旧字节码，
 *    且函数块地址未变（GC 重映射不触发），缓存永不失效。
 *
 * 修复：废除内容缓存，每次调用从堆复制（与 57c9c86「指针每次重取」同哲学）。
 * 调用入口用 JSBridge.call/eval（宿主 callContractFunction 的同款链路），
 * 并注册 __stset/__stget 宿主函数模拟执行机适配器的 store 依赖。
 */
class ByteCodeCacheRegressionTest {

    private fun newBridgeContext(): Pair<JSContext, JSBridge> {
        val mem = ByteArray(1024 * 1024)
        val stdlib = JSSTDLibraryDef(
            stdlibTable = LongArray(0),
            cFunctionTable = emptyArray(),
            cFinalizerTable = emptyArray(),
            stdlibTableLen = 0,
            stdlibTableAlign = 8,
            sortedAtomsOffset = 0,
            globalObjectOffset = 0,
            classCount = JSObjectClassEnum.JS_CLASS_USER.value
        )
        val ctx = JS_NewContext(mem, 1024 * 1024, stdlib)
        val bridge = JSBridge(ctx)
        return ctx to bridge
    }

    /** 宿主 store（模拟执行机适配器的 __stset/__stget 注入；宽容取值兼容数字/字符串） */
    private fun registerStore(bridge: JSBridge): MutableMap<String, String> {
        val store = HashMap<String, String>()
        bridge.function("__stset", listOf("k", "v")) { _, args ->
            val k = args.stringOrNull(0) ?: return@function null
            store[k] = args.stringOrNull(1) ?: args.longOrNull(1)?.toString() ?: args.doubleOrNull(1)?.toString() ?: ""
            null
        }
        bridge.function("__stget", listOf("k")) { _, args ->
            store[args.stringOrNull(0) ?: ""]
        }
        return store
    }

    /** 场景2直接复现：同名函数重新 eval（2槽小版→300槽大版）后，call 必须执行新字节码。 */
    @Test
    fun redefinedFunctionMustRunFreshBytecode() {
        val (ctx, bridge) = newBridgeContext()
        val store = registerStore(bridge)

        bridge.eval("function f() { var a = '1'; var b = '2'; __stset('a', a); return __stget('a'); }")
        assertEquals("1", bridge.eval("f()"))
        assertEquals("1", store["a"])

        /* 同名重新 eval：帧容量要求远超旧版（300 局部槽），返回 7 */
        bridge.eval(buildString {
            append("function f() {")
            for (i in 0 until 300) append("var v$i = $i; ")
            append("var sum = 0; ")
            for (i in 0 until 300) append("sum = sum + v$i; ")
            append("return 7; }")
        })
        assertEquals(7, bridge.eval("f()"))
    }

    /** 场景1近似复现：多函数多轮调用＋调用间大分配（诱发 GC 搬移/地址复用），结果全部正确。 */
    @Test
    fun multiFunctionMultiRoundWithAllocPressure() {
        val (ctx, bridge) = newBridgeContext()
        registerStore(bridge)
        /* 8 个不同帧布局的函数（局部槽数量各异） */
        for (n in 1..8) {
            bridge.eval(buildString {
                append("function g$n() {")
                for (i in 0 until n * 7) append("var t$i = $i; ")
                append("return $n; }")
            })
        }
        /* 30 轮 × 8 函数，每 3 轮制造一次堆压力（诱发 GC），断言每轮结果正确 */
        for (round in 1..30) {
            for (n in 1..8) {
                if (round % 3 == 0) {
                    /* 纯数字循环堆压力（引擎未实现 Array.prototype.fill，避免依赖） */
                    bridge.eval("var sink$n$round = 0; for (var i = 0; i < 20000; i++) { sink$n$round += i; }")
                }
                assertEquals(n, bridge.eval("g$n()"))
            }
        }
    }
}
