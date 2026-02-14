package com.mquickjs

import com.mquickjs.memory.Memory
import com.mquickjs.memory.getMTag
import com.mquickjs.parser.JSParseState
import com.mquickjs.parser.JSParser
import com.mquickjs.runtime.JSRuntime

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
    
    return ctx
}

fun JS_Eval(ctx: JSContext, code: String, filename: String, flags: Int): JSValue {
    val bytes = code.toByteArray()
    val state = JSParseState(ctx, bytes, filename, flags)
    val parser = JSParser(state)
    val result = parser.parse()
    
    if (JS_IsException(result)) {
        return result
    }
    
    val runtime = JSRuntime(ctx)
    return runtime.callFunction(result, emptyList())
}
