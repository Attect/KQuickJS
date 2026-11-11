package app.muka.project.kquickjs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * GC 保活回归：标准库在“先创建结果容器、再循环写入”的路径中必须保活目标值。
 * 小堆下循环内分配会触发 GC 压缩，未保活会产生悬垂指针并静默返回错误结果
 * （历史问题：split 非正则路径、全局 match、JSON.parse 对象/数组分支）。
 */
class GCRegressionTest {

    private fun newCtx(memSize: Int): JSContext {
        val mem = ByteArray(memSize)
        val def = JSSTDLibraryDef(
            stdlibTable = LongArray(0),
            cFunctionTable = emptyArray(),
            cFinalizerTable = emptyArray(),
            stdlibTableLen = 0,
            stdlibTableAlign = 8,
            sortedAtomsOffset = 0,
            globalObjectOffset = 0,
            classCount = JSObjectClassEnum.JS_CLASS_USER.value
        )
        return JS_NewContext(mem, memSize, def)
    }

    private fun evalInt(ctx: JSContext, code: String, file: String): Int {
        val r = JS_Eval(ctx, code, file, JS_EVAL_RETVAL)
        assertTrue(!JS_IsException(r), "执行不应异常：$file")
        return JS_ToInt32(ctx, r)
    }

    /** 16MB 堆（默认值）下反复 split 1 万元素字符串：早期实现会得到 NaN。 */
    @Test
    fun splitSurvivesGcDuringBuild() {
        val ctx = newCtx(16 * 1024 * 1024)
        val n = evalInt(ctx, """
            (function () {
                var parts = [];
                for (var i = 0; i < 10000; i++) parts.push("value-" + i);
                var s = parts.join(",");
                var n = 0;
                for (var i = 0; i < 200; i++) n += s.split(",").length;
                return n;
            })()
        """.trimIndent(), "<gc-split>")
        assertEquals(2000000, n, "split 结果应为 2000000（NaN 说明 GC 搬移后写入悬垂指针）")
    }

    /** 正则分隔符路径（历史实现已保活，作为对照）。 */
    @Test
    fun regexpSplitSurvivesGcDuringBuild() {
        val ctx = newCtx(16 * 1024 * 1024)
        val n = evalInt(ctx, """
            (function () {
                var parts = [];
                for (var i = 0; i < 5000; i++) parts.push("v" + i + "x");
                var s = parts.join(";");
                var n = 0;
                for (var i = 0; i < 200; i++) n += s.split(/;/).length;
                return n;
            })()
        """.trimIndent(), "<gc-split-re>")
        assertEquals(1000000, n)
    }

    /** 全局 String.match：循环内分配结果字符串，结果数组必须保活。 */
    @Test
    fun stringMatchGlobalSurvivesGc() {
        val ctx = newCtx(16 * 1024 * 1024)
        val n = evalInt(ctx, """
            (function () {
                var parts = [];
                for (var i = 0; i < 10000; i++) parts.push("value-" + i);
                var s = parts.join(" ");
                var re = /value-\d+/g;
                var n = 0;
                for (var i = 0; i < 20; i++) n += s.match(re).length;
                return n;
            })()
        """.trimIndent(), "<gc-match>")
        assertEquals(200000, n)
    }

    /** JSON.parse 大对象：递归解析期间分配会触发 GC，结果对象必须保活。 */
    @Test
    fun jsonParseObjectSurvivesGcDuringBuild() {
        val ctx = newCtx(16 * 1024 * 1024)
        val n = evalInt(ctx, """
            (function () {
                var parts = [];
                for (var i = 0; i < 5000; i++) parts.push('"k' + i + '":' + i);
                var o = JSON.parse("{" + parts.join(",") + "}");
                return Object.keys(o).length;
            })()
        """.trimIndent(), "<gc-json-object>")
        assertEquals(5000, n, "JSON.parse 对象属性数应为 5000（0 说明结果对象未被保活）")
    }

    /** JSON.parse 大数组：同上。 */
    @Test
    fun jsonParseArraySurvivesGcDuringBuild() {
        val ctx = newCtx(16 * 1024 * 1024)
        val n = evalInt(ctx, """
            (function () {
                var parts = [];
                for (var i = 0; i < 5000; i++) parts.push("[" + i + "," + i + "]");
                var s = "[" + parts.join(",") + "]";
                var n = 0;
                for (var i = 0; i < 100; i++) n += JSON.parse(s).length;
                return n;
            })()
        """.trimIndent(), "<gc-json-array>")
        assertEquals(500000, n)
    }
}