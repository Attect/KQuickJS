package app.muka.project.kquickjs

import app.muka.project.kquickjs.JSMTags.JS_MTAG_OBJECT
import app.muka.project.kquickjs.JSMTags.JS_MTAG_STRING
import app.muka.project.kquickjs.JSMTags.JS_MTAG_VALUE_ARRAY
import app.muka.project.kquickjs.memory.getMTag

fun JS_NewObjectProtoClass(ctx: JSContext, proto: JSValue, classId: Int, extraSize: Int): JSValue {
    val size = 24 + extraSize * JSW
    val ptr = ctx.mallocz(size, JS_MTAG_OBJECT)
    if (ptr == 0) return JS_EXCEPTION
    
    ctx.memory.putU8(ptr, (JS_MTAG_OBJECT shl 1))
    ctx.memory.putU8(ptr + 1, 0)
    ctx.memory.putU8(ptr + 2, classId)
    ctx.memory.putU8(ptr + 3, extraSize)
    
    ctx.memory.putJSValue(ptr + 8, proto)
    ctx.memory.putJSValue(ptr + 16, ctx.emptyProps)
    
    return JS_VALUE_FROM_PTR(ptr)
}

fun JS_NewObject(ctx: JSContext): JSValue {
    return JS_NewObjectProtoClass(ctx, ctx.classProto[JSObjectClassEnum.JS_CLASS_OBJECT.value], 
                                   JSObjectClassEnum.JS_CLASS_OBJECT.value, 0)
}

fun JS_NewArray(ctx: JSContext, initialLen: Int): JSValue {
    val obj = JS_NewObjectProtoClass(ctx, ctx.classProto[JSObjectClassEnum.JS_CLASS_ARRAY.value],
                                      JSObjectClassEnum.JS_CLASS_ARRAY.value, 8)
    if (JS_IsException(obj)) return obj
    
    val ptr = JS_VALUE_TO_PTR(obj)
    ctx.memory.putJSValue(ptr + 24, JS_NULL)
    ctx.memory.putI32(ptr + 32, initialLen)
    
    return obj
}

fun JS_NewObjectClassUser(ctx: JSContext, classId: Int): JSValue {
    if (classId < JSObjectClassEnum.JS_CLASS_USER.value || classId >= ctx.classCount) {
        return JS_EXCEPTION
    }
    return JS_NewObjectProtoClass(ctx, ctx.classProto[classId], classId, 8)
}

fun JS_GetClassID(ctx: JSContext, val1: JSValue): Int {
    if (!JS_IsPtr(val1)) return -1
    val ptr = JS_VALUE_TO_PTR(val1)
    if (ctx.memory.getMTag(ptr) != JS_MTAG_OBJECT) return -1
    return ctx.memory.getU8(ptr + 2)
}

fun JS_SetOpaque(ctx: JSContext, val1: JSValue, opaque: Any?) {
    if (!JS_IsPtr(val1)) return
    val ptr = JS_VALUE_TO_PTR(val1)
    if (ctx.memory.getMTag(ptr) != JS_MTAG_OBJECT) return
    val classId = ctx.memory.getU8(ptr + 2)
    if (classId >= JSObjectClassEnum.JS_CLASS_USER.value) {
        ctx.opaque = opaque
    }
}

fun JS_GetOpaque(ctx: JSContext, val1: JSValue): Any? {
    if (!JS_IsPtr(val1)) return null
    val ptr = JS_VALUE_TO_PTR(val1)
    if (ctx.memory.getMTag(ptr) != JS_MTAG_OBJECT) return null
    val classId = ctx.memory.getU8(ptr + 2)
    if (classId >= JSObjectClassEnum.JS_CLASS_USER.value) {
        return ctx.opaque
    }
    return null
}

fun JS_IsString(ctx: JSContext, val1: JSValue): Boolean {
    if (!JS_IsPtr(val1)) return false
    val ptr = JS_VALUE_TO_PTR(val1)
    return ctx.memory.getMTag(ptr) == JS_MTAG_STRING
}

fun JS_IsFunction(ctx: JSContext, val1: JSValue): Boolean {
    if (!JS_IsPtr(val1)) return false
    val ptr = JS_VALUE_TO_PTR(val1)
    if (ctx.memory.getMTag(ptr) != JS_MTAG_OBJECT) return false
    val classId = ctx.memory.getU8(ptr + 2)
    return classId == JSObjectClassEnum.JS_CLASS_CLOSURE.value ||
           classId == JSObjectClassEnum.JS_CLASS_C_FUNCTION.value
}

fun JS_IsError(ctx: JSContext, val1: JSValue): Boolean {
    if (!JS_IsPtr(val1)) return false
    val ptr = JS_VALUE_TO_PTR(val1)
    if (ctx.memory.getMTag(ptr) != JS_MTAG_OBJECT) return false
    val classId = ctx.memory.getU8(ptr + 2)
    return classId >= JSObjectClassEnum.JS_CLASS_ERROR.value &&
           classId <= JSObjectClassEnum.JS_CLASS_INTERNAL_ERROR.value
}

fun JS_GetGlobalObject(ctx: JSContext): JSValue = ctx.globalObj

fun JS_GetException(ctx: JSContext): JSValue {
    val ex = ctx.currentException
    ctx.currentException = JS_UNDEFINED
    return ex
}

fun JS_Throw(ctx: JSContext, obj: JSValue): JSValue {
    ctx.currentException = obj
    ctx.currentExceptionIsUncatchable = false
    return JS_EXCEPTION
}

fun jsAllocValueArray(ctx: JSContext, size: Int): Int {
    val totalSize = 8 + size * JSW
    val ptr = ctx.malloc(totalSize, JS_MTAG_VALUE_ARRAY)
    if (ptr == 0) return 0
    
    ctx.memory.putU8(ptr, (JS_MTAG_VALUE_ARRAY shl 1))
    ctx.memory.putU8(ptr + 1, 0)
    ctx.memory.putI32(ptr + 4, size)
    
    return ptr
}

fun JS_NewCFunctionParams(ctx: JSContext, funcIdx: Int, params: JSValue): JSValue {
    val obj = JS_NewObjectProtoClass(ctx, ctx.classProto[JSObjectClassEnum.JS_CLASS_CLOSURE.value],
                                      JSObjectClassEnum.JS_CLASS_C_FUNCTION.value, 8)
    if (JS_IsException(obj)) return obj
    
    val ptr = JS_VALUE_TO_PTR(obj)
    ctx.memory.putI32(ptr + 24, funcIdx)
    ctx.memory.putJSValue(ptr + 28, params)
    
    return obj
}
