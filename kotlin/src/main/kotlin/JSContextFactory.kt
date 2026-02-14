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
