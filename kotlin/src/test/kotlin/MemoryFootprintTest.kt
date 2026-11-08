package app.muka.project.kquickjs

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 标准库内存足迹回归：标准库引导在 160KB 堆上必须成功，且占用保持较低，
 * 以保证极小堆下仍能运行含标准库的脚本（对齐 C 版的紧凑内存占用）。
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
        val ctx = newCtx(160 * 1024)
        val used = ctx.heapFree - ctx.heapBase
        assertTrue("160KB 堆应启用标准库", ctx.memory.size >= JSStdlib.MIN_MEM_SIZE)
        assertTrue("标准库占用应低于 96KB，实际 $used", used < 96 * 1024)

        /* 在小堆上下文中实际执行一段依赖标准库的脚本 */
        val r = JS_Eval(ctx, "JSON.stringify({a:[1,2,3], b:\"x\"})", "<mem-test>", JS_EVAL_RETVAL)
        assertTrue("小堆标准库执行不应异常", !JS_IsException(r))
        assertTrue(JS_IsString(ctx, r))
    }
}