package app.muka.project.kquickjs

import app.muka.project.kquickjs.parser.JSParseState
import app.muka.project.kquickjs.parser.JSParser
import app.muka.project.kquickjs.runtime.JSRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 长脚本相关回归测试。
 *
 * 覆盖此前会导致 `IndexOutOfBoundsException` 的缺陷：
 * 1. 注释未被正确跳过（任何带注释的脚本都无法解析）；
 * 2. 常量池计数跨嵌套函数泄漏，外层常量池被越界读取；
 * 3. 字节码数组容量与长度不一致导致 GC 堆遍历错位；
 * 4. 解析期/执行期 GC 搬移后解析器与解释器持有的旧地址失效。
 *
 * 这些用例都在较小的堆内存下运行，以便确定性地触发多次 GC 压缩搬移。
 */
class MQuickJSParserRegressionTest {

    private class RunResult(val ctx: JSContext, val value: JSValue)

    private fun runJS(code: String, memSize: Int): RunResult {
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

        val bytes = code.toByteArray()
        val state = JSParseState(ctx, bytes, "test.js", JS_EVAL_RETVAL)
        val parser = JSParser(state)
        val result = parser.parse()
        if (JS_IsException(result)) {
            throw RuntimeException("Parse error: ${state.errorMsg}")
        }

        val runtimeResult = JSRuntime(ctx).callFunction(result, emptyList())
        if (JS_IsException(runtimeResult)) {
            throw RuntimeException("Runtime exception")
        }
        return RunResult(ctx, runtimeResult)
    }

    /* 规范要求：块注释、行注释、尾随注释都必须被正确跳过 */
    @Test
    fun testCommentsAreSkipped() {
        val code = """
            /* 块注释
               跨行 */
            // 行注释
            var a = 1; // 尾随注释
            a += 2;
            a
        """.trimIndent()
        val r = runJS(code, 128 * 1024)
        assertEquals(3, JS_VALUE_GET_INT(r.value))
    }

    /* 嵌套函数的常量池长度必须独立计数：
     * 旧实现中内层函数的大量常量会把 s.cpoolLen 撑大，回到外层后
     * addConstPool 越界读取外层常量池，把堆内数据当作 JSValue 解引用 */
    @Test
    fun testNestedFunctionConstantPoolLength() {
        val js = StringBuilder()
        js.append("function outer() {\n")
        js.append("  var a = \"outerA\";\n")
        js.append("  function inner() {\n")
        for (i in 0 until 80) {
            js.append("    var x$i = \"inner_const_$i\";\n")
        }
        js.append("    return x79;\n")
        js.append("  }\n")
        js.append("  var b = \"outerB\";\n")
        js.append("  return inner() + b;\n")
        js.append("}\n")
        js.append("outer()\n")

        val r = runJS(js.toString(), 256 * 1024)
        assertTrue(JS_IsString(r.ctx, r.value), "结果应为字符串")
        assertEquals("inner_const_79outerB", jsGetString(r.ctx, r.value))
    }

    /* 长脚本（大量函数 + 字符串常量）在 256KB 堆上会触发多次解析期与
     * 执行期 GC：验证字节码数组收缩与 JVM 侧 GC 根修复后不再崩溃，
     * 且函数声明/调用仍然正确 */
    @Test
    fun testLongScriptWithManyFunctionsSurvivesGc() {
        val count = 400
        val js = StringBuilder()
        for (i in 0 until count) {
            js.append("function f$i() { return \"s$i\"; }\n")
        }
        js.append("f0() + f${count - 1}()\n")

        val r = runJS(js.toString(), 256 * 1024)
        assertTrue(JS_IsString(r.ctx, r.value), "结果应为字符串")
        assertEquals("s0s399", jsGetString(r.ctx, r.value))
    }
}