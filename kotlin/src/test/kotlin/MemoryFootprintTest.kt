package app.muka.project.kquickjs

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 标准库内存足迹回归：
 * - 引导后引擎堆占用保持紧凑（当前约 28 KB，上限 48 KB）；
 * - 最小可用堆 128 KB 下可启用标准库，并能实际执行依赖标准库的脚本。
 * 对齐 C 版的紧凑内存占用目标。
 */
class MemoryFootprintTest {

    private fun newCtx(size: Int): JSContext {
        val mem = ByteArray(size)
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
        return JS_NewContext(mem, size, def)
    }

    @Test
    fun stdlibFitsSmallHeap() {
        val ctx = newCtx(128 * 1024)
        val used = ctx.heapFree - ctx.heapBase
        assertTrue("128KB 堆应启用标准库", ctx.memory.size >= JSStdlib.MIN_MEM_SIZE)
        assertTrue("标准库引导占用应低于 48KB，实际 $used", used < 48 * 1024)

        /* 在小堆上下文中实际执行一段依赖惰性命名空间（JSON）的脚本 */
        val r = JS_Eval(ctx, "JSON.stringify({a:[1,2,3], b:\"x\"})", "<mem-test>", JS_EVAL_RETVAL)
        assertTrue("小堆标准库执行不应异常", !JS_IsException(r))
        assertTrue(JS_IsString(ctx, r))
    }

    /** 惰性命名空间（Set/TypedArray/Math/JSON）不应在 Context 创建时占用堆。 */
    @Test
    fun lazyNamespacesDoNotInflateBootstrap() {
        val ctx = newCtx(256 * 1024)
        val used = ctx.heapFree - ctx.heapBase
        assertTrue("引导后堆占用应低于 32KB，实际 $used", used < 32 * 1024)

        val r = JS_Eval(ctx, "new Set([1,2,2]).size + new Uint8Array(2).length + Math.max(1,2) + JSON.stringify([1]).length",
                        "<lazy-test>", JS_EVAL_RETVAL)
        assertTrue("惰性命名空间应可按需初始化", !JS_IsException(r))
        assertTrue(JS_ToInt32(ctx, r) > 0)
    }
}