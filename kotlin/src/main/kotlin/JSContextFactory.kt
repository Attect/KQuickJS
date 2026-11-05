package app.muka.project.kquickjs

import app.muka.project.kquickjs.memory.Memory
import app.muka.project.kquickjs.memory.getMTag
import app.muka.project.kquickjs.parser.JSParseState
import app.muka.project.kquickjs.parser.JSParser
import app.muka.project.kquickjs.runtime.JSRuntime

data class JSSTDLibraryDef(
    val stdlibTable: LongArray,
    val cFunctionTable: Array<JSCFunctionDef>,
    val cFinalizerTable: Array<((ctx: JSContext, opaque: Any?) -> Unit)?>,
    val stdlibTableLen: Int,
    val stdlibTableAlign: Int,
    val sortedAtomsOffset: Int,
    val globalObjectOffset: Int,
    val classCount: Int
)

fun JS_NewContext(mem: ByteArray, memSize: Int, stdlib: JSSTDLibraryDef): JSContext {
    val memory = Memory(memSize)
    val atomTable = LongArray(1024)
    
    val ctx = JSContext(
        memory = memory,
        classCount = stdlib.classCount,
        atomTable = atomTable,
        cFunctionTable = stdlib.cFunctionTable,
        cFinalizerTable = stdlib.cFinalizerTable
    )
    
    // 设置堆的起始位置，跳过一些空间用于上下文结构
    ctx.heapBase = 1024
    ctx.heapFree = 1024
    
    // 初始化类原型
    for (i in 0 until ctx.classCount) {
        ctx.classProto[i] = JS_NULL
        ctx.classObj[i] = JS_NULL
    }
    
    // 初始化 empty_props (空属性数组)
    val emptyPropsPtr = ctx.malloc(8 + 3 * 8, JSMTags.JS_MTAG_VALUE_ARRAY)
    if (emptyPropsPtr != 0) {
        ctx.memory.putI32(emptyPropsPtr + 4, 3)
        ctx.memory.putJSValue(emptyPropsPtr + 8, JS_NewShortInt(0))  // prop_count
        ctx.memory.putJSValue(emptyPropsPtr + 16, JS_NewShortInt(0)) // hash_mask
        ctx.memory.putJSValue(emptyPropsPtr + 24, JS_NewShortInt(0)) // hash_table[0]
        ctx.emptyProps = JS_VALUE_FROM_PTR(emptyPropsPtr)
    }
    
    // 初始化 Object.prototype
    ctx.classProto[JSObjectClassEnum.JS_CLASS_OBJECT.value] = JS_NewObject(ctx)
    
    // 初始化全局对象
    ctx.globalObj = JS_NewObject(ctx)
    
    // -0 的唯一表示（JS_NewFloat64 依赖；延迟创建会与 GC 保活交叉，提前分配）
    ctx.minusZero = allocFloat64(ctx, -0.0)
    
    /* 长期 JVM 根列表（bind 等）始终参与 GC 标记与搬移 */
    ctx.jvmRoots.add(ctx.persistentRoots)
    
    // 标准库引导（对照 C 的 mqjs_stdlib.c；堆过小时跳过）
    JSStdlib.init(ctx, JSRuntime(ctx))
    
    return ctx
}

fun JS_Eval(ctx: JSContext, code: String, filename: String, flags: Int): JSValue {
    /* 深嵌套源码（如 1000 层括号）会让递归下降解析器耗尽 JVM 栈：
     * 解析阶段栈溢出时改用大栈线程重试（执行阶段不重试，避免副作用重复） */
    var phase = 0
    fun evalImpl(): JSValue = evalBody(ctx, code, filename, flags) { phase = 1 }
    return try {
        evalImpl()
    } catch (e: StackOverflowError) {
        if (phase != 0) throw e
        var out: JSValue = JS_EXCEPTION
        val th = Thread(null, { out = evalImpl() }, "kqjs-eval-big-stack", 64L * 1024 * 1024)
        th.start()
        th.join()
        out
    }
}

private fun evalBody(ctx: JSContext, code: String, filename: String, flags: Int,
                     onExecute: () -> Unit): JSValue {
    val bytes = code.toByteArray()
    val state = JSParseState(ctx, bytes, filename, flags)
    val parser = JSParser(state)
    val result = parser.parse()

    if (JS_IsException(result)) {
        /* 语法错误：构造 SyntaxError 并附带 eval 位置的回溯行 */
        ctx.throwError(JSObjectClassEnum.JS_CLASS_SYNTAX_ERROR,
                        state.errorMsg ?: "syntax error")
        val err = ctx.currentException
        if (!JS_IsException(err)) {
            val keep = ctx.gcKeepAlive.size
            ctx.gcKeepAlive.add(err)
            val stack = JS_NewString(ctx,
                "    at " + filename + ":" + state.errorLine + ":" + state.errorCol)
            if (!JS_IsException(stack)) {
                ctx.memory.putJSValue(JS_VALUE_TO_PTR(ctx.gcKeepAlive[keep]) + 32, stack)
            }
            ctx.currentException = ctx.gcKeepAlive[keep]
            while (ctx.gcKeepAlive.size > keep) ctx.gcKeepAlive.removeAt(ctx.gcKeepAlive.size - 1)
        }
        return JS_EXCEPTION
    }

    onExecute()
    val runtime = JSRuntime(ctx)
    return runtime.callFunction(result, emptyList())
}