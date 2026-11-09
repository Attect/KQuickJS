package app.muka.project.kquickjs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 可配置栈/调用深度与 Set/BigInt 行为测试。 */
class JsOptionsTest {

    private fun newCtx(size: Int = 1024 * 1024): JSContext {
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
        return JS_NewContext(ByteArray(size), size, def)
    }

    @Test
    fun maxCallDepthIsConfigurable() {
        val old = KQuickJSOptions.maxCallDepth
        try {
            KQuickJSOptions.maxCallDepth = 64
            val ctx = newCtx()
            val r = JS_Eval(ctx,
                "function f(n) { return n <= 0 ? 0 : f(n - 1) + 1 } f(500)",
                "<depth>", JS_EVAL_RETVAL)
            assertTrue("超出配置深度应抛异常", JS_IsException(r))
        } finally {
            KQuickJSOptions.maxCallDepth = old
        }
    }

    @Test
    fun evalStackSizeIsAConfigurablePositiveValue() {
        assertTrue(KQuickJSOptions.evalStackSizeBytes >= 256L * 1024)
    }

    @Test
    fun setSupportsIterationAndBigIntEdges() {
        val ctx = newCtx()
        val r = JS_Eval(ctx, """
            var s = new Set([1, 2, 2, 3]);
            var arr = [];
            for (var v of s) arr.push(v);
            var ok = arr.join(",") === "1,2,3" && new Set(s).size === 3 &&
                     new Set("aba").size === 2 &&
                     Object.prototype.toString.call(s) === "[object Set]";
            var e1 = false, e2 = false, e3 = false;
            try { JSON.stringify({a: 1n}) } catch (e) { e1 = e instanceof TypeError }
            try { Number(1n) } catch (e) { e2 = e instanceof TypeError }
            try { 1n >>> 1n } catch (e) { e3 = e instanceof TypeError }
            ok && e1 && e2 && e3 && (1n + 2n) === 3n && (255n).toString(16) === "ff"
        """.trimIndent(), "<setbig>", JS_EVAL_RETVAL)
        assertTrue(!JS_IsException(r))
        assertTrue("Set/BigInt 语义检查失败", jsToBoolean(ctx, r))
    }

    @Test
    fun setHashingSurvivesGcAndDeletion() {
        val ctx = newCtx()
        val r = JS_Eval(ctx, """
            var s = new Set();
            for (var i = 0; i < 200; i++) s.add("k" + i);
            for (var round = 0; round < 10; round++)
                for (var j = 0; j < 2000; j++) { var t = "tmp" + j; }
            var ok = s.size === 200;
            for (var i = 0; i < 200; i++) if (!s.has("k" + i)) ok = false;
            if (!s.delete("k5") || s.has("k5") || s.size !== 199) ok = false;
            s.add("k5");
            var obj = {};
            s.add(obj); s.add(obj);
            if (s.size !== 201 || !s.has(obj)) ok = false;
            ok
        """.trimIndent(), "<sethash>", JS_EVAL_RETVAL)
        assertTrue("哈希 Set 语义/GC 检查失败", !JS_IsException(r) && jsToBoolean(ctx, r))
    }

    @Test
    fun bigIntLongPathAndBigIntTypedArrays() {
        val ctx = newCtx()
        val r = JS_Eval(ctx, """
            var a = new BigInt64Array(3);
            a[0] = 1n; a[1] = -2n; a[2] = 9223372036854775807n;
            var b = new BigUint64Array([18446744073709551615n, 9223372036854775808n]);
            var e1 = false;
            try { a[0] = 1 } catch (e) { e1 = e instanceof TypeError }
            var overflow = (9223372036854775807n + 1n) === 9223372036854775808n;
            a[0] === 1n && a[1] === -2n && a[2] === 9223372036854775807n &&
            b[0] === 18446744073709551615n && b[1] === 9223372036854775808n &&
            BigInt64Array.BYTES_PER_ELEMENT === 8 && e1 && overflow
        """.trimIndent(), "<bigtyped>", JS_EVAL_RETVAL)
        assertTrue("BigInt Long 路径/TypedArray 检查失败", !JS_IsException(r) && jsToBoolean(ctx, r))
    }

    private fun jsToBoolean(ctx: JSContext, v: JSValue): Boolean {
        if (JS_IsBool(v)) return JS_VALUE_GET_SPECIAL_VALUE(v) != 0
        return false
    }
}