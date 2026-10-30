package app.muka.project.kquickjs

import app.muka.project.kquickjs.bridge.JSBridge
import app.muka.project.kquickjs.bridge.JSBridgeCall
import app.muka.project.kquickjs.bridge.JSBridgeException
import app.muka.project.kquickjs.bridge.JSBridgeScriptException
import app.muka.project.kquickjs.bridge.JSBridgeConvert
import app.muka.project.kquickjs.bridge.JSErrorKind
import app.muka.project.kquickjs.bridge.JSRawValue
import app.muka.project.kquickjs.bridge.JSPlatformExample
import app.muka.project.kquickjs.runtime.JSRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * 宿主桥接层测试：覆盖任务 1-5 项
 * 1. 类型转换层（双向自动转换 + 可定位类型错误）
 * 2. 便捷注册（嵌套命名空间 + 普通函数/构造器两种变体 + 可扩函数表）
 * 3. 属性绑定（数据属性 + getXxx/setXxx 近似辅助）
 * 4. 异常桥接（宿主异常 → JS Error；JS 错误 → 可捕获 Kotlin 异常）
 * 5. 宿主对象生命周期（opaque + finalizer + GC 搬移后引用正确）
 * 7. platform 命名空间完整示例
 */
class JSBridgeTest {

    /* ==================== 测试基建 ==================== */

    /** 创建带桥接层的测试上下文（256KB 堆足够注册与转换测试） */
    private fun newBridgeContext(memSize: Int = 256 * 1024): Pair<JSContext, JSBridge> {
        val mem = ByteArray(memSize)
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
        val ctx = JS_NewContext(mem, memSize, stdlib)
        val bridge = JSBridge(ctx)
        /* 诊断辅助：本引擎错误对象的 message 存于 extra 槽而非普通属性，
         * JS 代码无法直接 e.message 读取，注册 sys.errmsg 供测试捕获消息 */
        bridge.function("sys.errmsg", listOf("e")) { c, args ->
            JSBridge(c).errorMessage(args.jsValue(0))
        }
        return ctx to bridge
    }

    /** 演示用宿主数据类 */
    data class PointData(val x: Int, val y: Int)

    /** 演示用可关闭资源 */
    class FakeResource(val id: Int) {
        var closed: Boolean = false
    }

    /* ==================== 1. 类型转换层 ==================== */

    @Test
    fun testJsToKotlinPrimitives() {
        val (ctx, bridge) = newBridgeContext()
        assertEquals(null, bridge.eval("null"))
        /* 引擎将 undefined 词法处理为标识符（全局未定义即 ReferenceError），
         * 通过无初始化 var 声明获取 undefined 值 */
        assertEquals(null, bridge.eval("var u; u"))
        assertEquals(true, bridge.eval("true"))
        assertEquals(false, bridge.eval("false"))
        assertEquals(42, bridge.eval("42"))
        assertEquals(-7, bridge.eval("0 - 7"))
        assertEquals(3.5, bridge.eval("3.5"))
        assertEquals("你好,KQuickJS", bridge.eval("'你好,KQuickJS'"))
        /* 顶层 var 声明与表达式（严格模式要求 var） */
        assertEquals(12, bridge.eval("var a = 5; a + 7"))
        ctx.hashCode()
    }

    @Test
    fun testKotlinToJsAndBack() {
        val ctx = newBridgeContext().first
        val cases: List<Any?> = listOf(
            null, true, false, 0, 1, -1, 100, Int.MAX_VALUE, Long.MAX_VALUE,
            2.5, "文本", listOf(1, "two", null, true),
            linkedMapOf("a" to 1, "b" to listOf("x", "y"))
        )
        for (v in cases) {
            val js = JSBridgeConvert.fromKotlin(ctx, v)
            val back = JSBridgeConvert.toKotlin(ctx, js)
            assertEquals(v, back, "往返失败: $v")
        }
        /* ByteArray → number 数组 → ByteArray */
        val bytes = byteArrayOf(0, 1, 127, 128.toByte(), 255.toByte())
        val jsBytes = JSBridgeConvert.fromKotlin(ctx, bytes)
        val backBytes = JSBridgeConvert.toJsBytes(ctx, jsBytes, "测试参数")
        assertTrue(bytes.contentEquals(backBytes), "ByteArray 往返失败")
    }

    @Test
    fun testNestedCollectionsThroughJs() {
        val (ctx, bridge) = newBridgeContext()
        /* 宿主 Map 返回值 → JS 对象 → JS 属性读取 → Kotlin */
        bridge.function("data.config") { _ ->
            linkedMapOf(
                "name" to "agent",
                "retries" to 3,
                "ratio" to 0.5,
                "tags" to listOf("a", "b")
            )
        }
        assertEquals("agent", bridge.eval("data.config().name"))
        assertEquals(3, bridge.eval("data.config().retries"))
        assertEquals(2, bridge.eval("data.config().tags.length"))
        /* JS 对象字面量 → Kotlin Map（递归转换） */
        val obj = bridge.eval("var o = { x: 1, y: { z: [true, null] } }; o")
        val map = assertNotNull(obj as? Map<*, *>, "对象应转换为 Map")
        assertEquals(1, map["x"])
        val inner = assertNotNull(map["y"] as? Map<*, *>)
        val list = assertNotNull(inner["z"] as? List<*>)
        assertEquals(true, list[0])
        assertEquals(null, list[1])
        ctx.hashCode()
    }

    @Test
    fun testStrictTypeErrorIsLocatable() {
        val (ctx, bridge) = newBridgeContext()
        bridge.namespace("conv").function("need", "url", "timeoutMs") { _, _, _ -> "ok" }
        /* string 参数收到 number：错误消息须含函数路径、参数位置、参数名、实际类型 */
        val msg = bridge.eval(
            "try { conv.need(123, 1) } catch (e) { sys.errmsg(e) }"
        ) as? String ?: fail("应捕获到 TypeError 消息")
        assertTrue(msg.contains("conv.need"), "消息应含函数路径: $msg")
        assertTrue(msg.contains("'url'"), "消息应含参数名: $msg")
        assertTrue(msg.contains("number(123)"), "消息应含实际类型描述: $msg")
        /* int 参数收到非整数值 */
        val msg2 = bridge.eval(
            "try { conv.need('u', 1.5) } catch (e) { sys.errmsg(e) }"
        ) as String
        assertTrue(msg2.contains("'timeoutMs'") && msg2.contains("非整数"), "消息应定位非整数问题: $msg2")
        /* boolean 严格策略：number 不自动转 boolean */
        bridge.namespace("conv").function("needBool", listOf("flag")) { _, args -> args.boolean("flag") }
        val msg3 = bridge.eval(
            "try { conv.needBool(1) } catch (e) { sys.errmsg(e) }"
        ) as String
        assertTrue(msg3.contains("boolean"), "消息应声明期望 boolean: $msg3")
        ctx.hashCode()
    }

    @Test
    fun testMissingArgcAndExtraArgc() {
        val (ctx, bridge) = newBridgeContext()
        bridge.namespace("argc").function("two", "a", "b") { _, _, _ -> 2 }
        val msg = bridge.eval("try { argc.two(1) } catch (e) { sys.errmsg(e) }") as String
        assertTrue(msg.contains("期望 2 个参数") && msg.contains("实际收到 1 个"), "消息应说明参数个数: $msg")
        val msg2 = bridge.eval("try { argc.two(1, 2, 3) } catch (e) { sys.errmsg(e) }") as String
        assertTrue(msg2.contains("实际收到 3 个"), "多余参数同样拒绝: $msg2")
        ctx.hashCode()
    }

    @Test
    fun testRawValueRoundTrip() {
        val (ctx, bridge) = newBridgeContext()
        /* 非数据值（函数）经通用转换以 JSRawValue 包装，再转换时原样透传 */
        val funcVal = bridge.eval("var fn = function () { return 1; }; fn")
        val raw = assertNotNull(funcVal as? JSRawValue, "函数应包装为 JSRawValue")
        val back = JSBridgeConvert.fromKotlin(ctx, raw)
        assertEquals(raw.value, back, "JSRawValue 应原样透传")
        ctx.hashCode()
    }

    /* ==================== 2. 便捷注册 ==================== */

    @Test
    fun testNestedNamespaceCreation() {
        val (ctx, bridge) = newBridgeContext()
        bridge.namespace("platform").apply {
            namespace("log").function("info") { _ -> "logged" }
            function("now") { _ -> 1L }
        }
        /* 注意：引擎未实现 typeof（当前解释器直接返回操作数），改用功能断言 */
        assertEquals("logged", bridge.eval("platform.log.info()"))
        assertEquals(1, bridge.eval("platform.now()"))
        /* 重复解析同一命名空间：复用既有对象而非重建 */
        bridge.namespace("platform.log").function("warn") { _ -> "warned" }
        assertEquals("logged", bridge.eval("platform.log.info()"))
        assertEquals("warned", bridge.eval("platform.log.warn()"))
        ctx.hashCode()
    }

    @Test
    fun testNamespaceConflictRejected() {
        val (ctx, bridge) = newBridgeContext()
        bridge.function("dup.me") { _ -> 1 }
        val ex = assertFailsWith<JSBridgeException> { bridge.namespace("dup.me.sub") }
        assertEquals(JSErrorKind.TYPE_ERROR, ex.kind, "在函数上创建子命名空间应报 TypeError")
        ctx.hashCode()
    }

    @Test
    fun testTypedSignatureOverloads() {
        val (ctx, bridge) = newBridgeContext()
        /* 0 参 */
        bridge.function("sig.zero") { _ -> "zero" }
        /* 1 参（string） */
        bridge.function("sig.echo", "msg") { _, msg -> "echo:$msg" }
        /* 2 参（string, int）——任务示例签名形态 */
        bridge.function("sig.get", "url", "timeoutMs") { _, url, timeoutMs ->
            "$url#$timeoutMs"
        }
        assertEquals("zero", bridge.eval("sig.zero()"))
        assertEquals("echo:你好", bridge.eval("sig.echo('你好')"))
        assertEquals("http://x#500", bridge.eval("sig.get('http://x', 500)"))
        /* JS number 非整数传给 int 参数：可定位 TypeError */
        assertTrue(
            (bridge.eval("try { sig.get('u', 0.5) } catch (e) { sys.errmsg(e) }") as String)
                .contains("'timeoutMs'"),
            "int 参数应拒绝非整数"
        )
        ctx.hashCode()
    }

    @Test
    fun testArgsFormRegistration() {
        val (ctx, bridge) = newBridgeContext()
        /* 通用形态：参数按名读取 + 缺省参数（undefined → null）；
         * 单 eval 完成调用与读取（顶层 var 不跨 eval 持久，属引擎既有语义） */
        bridge.namespace("args").function("call", listOf("name", "value", "flag")) { _, args ->
            linkedMapOf(
                "name" to args.string("name"),
                "value" to args.intOrNull("value"),
                "flag" to args.booleanOrNull("flag"),
                "any" to args.any(3)
            )
        }
        assertEquals("n", bridge.eval("args.call('n', 7, true).name"))
        assertEquals(7, bridge.eval("args.call('n', 7, true).value"))
        assertEquals(true, bridge.eval("args.call('n', 7, true).flag"))
        assertEquals(null, bridge.eval("args.call('n', 7, true).any"))
        /* 缺参：数量校验错误 */
        val msg = bridge.eval("try { args.call('n') } catch (e) { sys.errmsg(e) }") as String
        assertTrue(msg.contains("期望 3 个参数") && msg.contains("实际收到 1 个"), "缺参应报个数错误: $msg")
        ctx.hashCode()
    }

    @Test
    fun testRawFunctionVariant() {
        val (ctx, bridge) = newBridgeContext()
        /* 原生形态：可变参数 + JSValue 直接处理（此路径宿主自担 GC 约定） */
        bridge.functionRaw("raw.sum", 0, -1) { c, _, args ->
            var acc = 0
            for (i in 0 until args.size) {
                acc += JSBridgeConvert.toJsInt(c, args.jsValue(i), "raw.sum 的参数 $i")
            }
            JS_NewInt32(c, acc)
        }
        assertEquals(6, bridge.eval("raw.sum(1, 2, 3)"))
        assertEquals(0, bridge.eval("raw.sum()"))
        ctx.hashCode()
    }

    @Test
    fun testExtFunctionTableGrowth() {
        val (ctx, bridge) = newBridgeContext()
        /* 突破"必须预建定长函数表"限制：运行期注册 40 个函数并可全部调用 */
        val count = 40
        /* newBridgeContext 已注册 sys.errmsg 诊断辅助，以其为基线计量 */
        val baseExt = ctx.extFunctionTable.size
        for (i in 0 until count) {
            /* 返回值是 JSValue 时必须用 JSRawValue 包装（JSValue 是 Long 的
             * typealias，裸返回会被 fromKotlin 误当 Kotlin 数字再转换） */
            bridge.function("many.f$i") { _ -> JSRawValue(JS_NewInt32(ctx, i)) }
        }
        assertEquals(0, ctx.cFunctionTable?.size ?: 0, "未预建基础函数表")
        assertEquals(baseExt + count, ctx.extFunctionTable.size, "扩展函数表应持续增长")
        for (i in 0 until count) {
            assertEquals(i, bridge.eval("many.f$i()"), "扩展表函数 f$i 应可调用")
        }
    }

    @Test
    fun testConstructorVariant() {
        val (ctx, bridge) = newBridgeContext()
        /* 构造器便捷变体：返回的 Kotlin 实例自动包装为宿主对象 */
        bridge.constructor("Point", listOf("x", "y")) { _, args ->
            PointData(args.int("x"), args.int("y"))
        }
        /* 通过宿主捕获函数取回 new 的结果（顶层 var 不跨 eval 持久） */
        var captured: JSValue? = null
        bridge.function("sys.capture", listOf("v")) { _, args ->
            captured = args.jsValue(0); null
        }
        bridge.eval("sys.capture(new Point(3, 4))")
        val data = bridge.getHostObject(assertNotNull(captured), PointData::class.java)
        assertEquals(PointData(3, 4), data, "new 出的对象应携带宿主实例")
        /* 构造器参数个数/类型校验同样生效 */
        val msg = bridge.eval("try { sys.capture(new Point(1)) } catch (e) { sys.errmsg(e) }") as String
        assertTrue(msg.contains("Point") && msg.contains("期望 2 个参数"), "构造器参数校验: $msg")
        /* 非宿主对象查询返回 null（经宿主函数捕获 JS 对象原值） */
        var plainVal: JSValue? = null
        bridge.function("sys.capturePlain", listOf("v")) { _, args ->
            plainVal = args.jsValue(0); null
        }
        bridge.eval("sys.capturePlain({ k: 1 })")
        assertNull(bridge.getHostObject(assertNotNull(plainVal)), "普通对象不是宿主对象")
        ctx.hashCode()
    }

    /* ==================== 3. 属性绑定（近似） ==================== */

    @Test
    fun testPropertyDataAttribute() {
        val (ctx, bridge) = newBridgeContext()
        bridge.namespace("cfg").apply {
            property("name", "test-agent")
            property("limits.max", 100)
        }
        assertEquals("test-agent", bridge.eval("cfg.name"))
        assertEquals(100, bridge.eval("cfg.limits.max"))
        ctx.hashCode()
    }

    @Test
    fun testGetterSetterApproximation() {
        val (ctx, bridge) = newBridgeContext()
        var port = 8080
        bridge.namespace("cfg").getterSetter(
            "port",
            { _ -> port },
            { _, args -> port = args.int("port"); null }
        )
        /* 引擎无宿主级 get/set 访问器：以 get<Name>/set<Name> 普通函数近似 */
        assertEquals(8080, bridge.eval("cfg.getPort()"))
        bridge.eval("cfg.setPort(9090)")
        assertEquals(9090, bridge.eval("cfg.getPort()"))
        assertEquals(9090, port, "setter 应写回宿主侧变量")
        /* 单独 getter：get<Name> 命名约定 */
        bridge.namespace("cfg2").getter("status") { _ -> "ready" }
        assertEquals("ready", bridge.eval("cfg2.getStatus()"))
        ctx.hashCode()
    }

    /* ==================== 4. 异常桥接 ==================== */

    @Test
    fun testHostExceptionBecomesJsError() {
        val (ctx, bridge) = newBridgeContext()
        bridge.namespace("boom").apply {
            function("runtime") { _ -> throw RuntimeException("宿主崩溃") }
            function("range", listOf("i")) { _, args ->
                throw IndexOutOfBoundsException("索引 ${args.int("i")} 越界")
            }
            function("type") { _ -> throw IllegalArgumentException("参数非法") }
        }
        /* RuntimeException → Error，消息含函数路径与原因 */
        val m1 = bridge.eval("try { boom.runtime() } catch (e) { sys.errmsg(e) }") as String
        assertTrue(m1.contains("boom.runtime"), "消息应含函数路径: $m1")
        assertTrue(m1.contains("RuntimeException") && m1.contains("宿主崩溃"), "消息应含异常类名与原因: $m1")
        /* IndexOutOfBoundsException → RangeError（经 jsErrorName 验证映射） */
        val ex2 = assertFailsWith<JSBridgeScriptException> { bridge.eval("boom.range(9)") }
        assertEquals("RangeError", ex2.jsErrorName, "IndexOutOfBoundsException 应映射 RangeError")
        assertTrue(ex2.jsMessage.contains("boom.range"), "RangeError 消息应含函数路径: ${ex2.jsMessage}")
        /* IllegalArgumentException → TypeError */
        val ex3 = assertFailsWith<JSBridgeScriptException> { bridge.eval("boom.type()") }
        assertEquals("TypeError", ex3.jsErrorName, "IllegalArgumentException 应映射 TypeError")
        /* 异常不炸穿解释器：抛出后引擎仍可继续执行 */
        assertEquals("still-alive", bridge.eval("'still' + '-alive'"))
        ctx.hashCode()
    }

    @Test
    fun testJsErrorWrappedForHost() {
        val (ctx, bridge) = newBridgeContext()
        bridge.function("boom.js") { _ -> throw JSBridgeException(JSErrorKind.TYPE_ERROR, "桥接层显式异常") }
        /* JS 侧 throw 字符串 */
        val ex1 = assertFailsWith<JSBridgeScriptException> { bridge.eval("throw 'boom-js'") }
        assertNull(ex1.jsErrorName)
        assertEquals("boom-js", ex1.jsMessage)
        /* JS 侧收到宿主函数抛出的错误对象（未捕获）→ 反向包装 */
        val ex2 = assertFailsWith<JSBridgeScriptException> { bridge.eval("boom.js()") }
        assertEquals("TypeError", ex2.jsErrorName)
        assertTrue(ex2.jsMessage.contains("桥接层显式异常"), "应携带 JS 错误 message: ${ex2.jsMessage}")
        /* 宿主可捕获（携带 message） */
        try {
            bridge.eval("boom.js()")
            fail("应抛出 JSBridgeScriptException")
        } catch (e: JSBridgeScriptException) {
            assertTrue(e.message!!.contains("TypeError"), "异常 message 应含错误类名: ${e.message}")
        }
        ctx.hashCode()
    }

    /* ==================== 5. 宿主对象生命周期 ==================== */

    @Test
    fun testHostObjectOpaqueAccess() {
        val (ctx, bridge) = newBridgeContext()
        val res = FakeResource(7)
        val obj = bridge.newHostObject(res)
        assertTrue(bridge.isHostObject(obj), "应为宿主对象")
        assertEquals(res, bridge.getHostObject(obj))
        assertEquals(res, bridge.getHostObject(obj, FakeResource::class.java))
        assertNull(bridge.getHostObject(obj, PointData::class.java), "类型不符返回 null")
        ctx.hashCode()
    }

    @Test
    fun testHostObjectPassesThroughJs() {
        val (ctx, bridge) = newBridgeContext()
        val res = FakeResource(9)
        bridge.namespace("res").apply {
            hostObject("current", res)
            /* 宿主函数接收 JS 对象参数并取回 opaque（jsValue 原样读取） */
            function("idOf", listOf("r")) { c, args ->
                val host = JSBridge(c).getHostObject(args.jsValue(0))
                (host as? FakeResource)?.id ?: -1
            }
        }
        assertEquals(9, bridge.eval("res.idOf(res.current)"), "宿主函数应能从 JS 对象取回宿主实例")
        ctx.hashCode()
    }

    @Test
    fun testHostObjectOpaqueSurvivesGCMove() {
        val (ctx, bridge) = newBridgeContext()
        val res = FakeResource(42)
        bridge.namespace("gc").hostObject("res", res)
        /* 制造大量垃圾并强制 GC 压缩搬移 */
        bridge.eval(
            "var junk = null; " +
                "for (var i = 0; i < 30; i++) { junk = [junk, { a: i, b: 'x' + i }]; }"
        )
        ctx.gc()
        /* opaque 引用表按对象块地址维护、GC 搬移时随块更新：读取仍正确
         * （res 挂在 gc 命名空间对象上，需两跳解析） */
        val runtime = JSRuntime(ctx)
        val gcObj = runtime.getProperty(ctx.globalObj, JS_NewString(ctx, "gc"))
        val obj = runtime.getProperty(gcObj, JS_NewString(ctx, "res"))
        assertTrue(bridge.isHostObject(obj), "GC 搬移后仍应为宿主对象")
        assertEquals(res, bridge.getHostObject(obj), "GC 搬移后宿主引用应保持正确")
    }

    @Test
    fun testHostObjectFinalizerOnGC() {
        val (ctx, bridge) = newBridgeContext()
        var finalized = 0
        /* 创建后不落地到任何 GC 根：强制回收时 finalizer 应被调用 */
        bridge.newHostObject(FakeResource(1), onFinalize = { _ -> finalized += 1 })
        bridge.newHostObject(FakeResource(2), onFinalize = { r -> finalized += (r as FakeResource).id })
        assertEquals(0, finalized, "回收前不应触发 finalizer")
        ctx.gc()
        assertEquals(1 + 2, finalized, "GC 回收时应触发宿主 finalizer")
        ctx.hashCode()
    }

    /* ==================== 7. platform 命名空间示例 ==================== */

    @Test
    fun testPlatformExampleFullChain() {
        val (ctx, bridge) = newBridgeContext()
        val logLines = mutableListOf<String>()
        JSPlatformExample.install(bridge, logSink = { line -> logLines.add(line) })

        /* platform.now：Long 返回值自动转 JS number */
        val now = bridge.eval("platform.now()")
        assertTrue((now is Long || now is Int) && (now as Number).toLong() > 0, "now 应为正数: $now")

        /* platform.log.info：日志进入宿主 sink，返回 undefined */
        assertEquals(null, bridge.eval("platform.log.info('你好桥接')"))
        assertEquals(listOf("[info] 你好桥接"), logLines)

        /* platform.http.get：模拟响应对象（嵌套 Map → JS 对象） */
        assertEquals(200, bridge.eval("platform.http.get('https://example.test/api', 100).status"))
        assertEquals(
            "application/json",
            bridge.eval("platform.http.get('https://example.test/api', 100).headers['content-type']")
        )
        assertTrue(
            (bridge.eval("platform.http.get('u', 50).body") as String).contains("\"ok\":true"),
            "body 应为模拟 JSON"
        )

        /* 阻塞调用约定示例：timeout 参数校验（真实实现中到期返回超时标记值） */
        val msg = bridge.eval(
            "try { platform.http.get('u', 0) } catch (e) { sys.errmsg(e) }"
        ) as String
        assertTrue(msg.contains("'timeoutMs'"), "timeout 校验错误应可定位: $msg")

        /* property 常量 */
        assertEquals("kotlin-bridge-demo-1.0", bridge.eval("platform.version"))

        /* JS 侧组合调用：方法链与算术（引擎与桥接层协同） */
        val double = bridge.eval("platform.http.get('x', 10).status * 2")
        assertEquals(400, double)
        ctx.hashCode()
    }

    @Test
    fun testBlockingConventionTimeoutMarker() {
        val (ctx, bridge) = newBridgeContext()
        /* 推荐模式演示：阻塞等待类宿主函数自带 timeout 参数，
         * 超时返回 {"timeout": true} 标记值（而非抛异常或永久阻塞） */
        bridge.namespace("wait").function("mock", "id", "timeoutMs") { _, _, timeoutMs ->
            if (timeoutMs >= 100) {
                linkedMapOf("timeout" to true)
            } else {
                linkedMapOf("timeout" to false, "value" to "done")
            }
        }
        assertEquals(true, bridge.eval("wait.mock('a', 100).timeout"), "超时应返回标记值")
        assertEquals(false, bridge.eval("wait.mock('b', 50).timeout"))
        assertEquals("done", bridge.eval("wait.mock('b', 50).value"))
        ctx.hashCode()
    }

    @Test
    fun testBridgeCallHelpers() {
        val (ctx, bridge) = newBridgeContext()
        /* JSBridgeCall 的按位读取与 jsValue 透传 */
        bridge.namespace("h").function("probe", listOf("a", "b")) { c, args: JSBridgeCall ->
            assertEquals(2, args.size)
            assertTrue(args.isProvided(0))
            assertEquals("x", args.string(0))
            assertEquals(5, args.int(1))
            assertTrue(args.isUndefined(2), "越界位置视为 undefined")
            assertEquals(JS_UNDEFINED, args.jsValue(2))
            JSBridgeConvert.toKotlin(c, args.jsValue(0))
        }
        assertEquals("x", bridge.eval("h.probe('x', 5)"))
        ctx.hashCode()
    }
}
