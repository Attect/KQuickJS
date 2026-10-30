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

/* 创建用户对象：extra 区含 1 个 opaque 占位槽 + nValues 个私有 JSValue 槽
 * （同步上游 #80：JS_NewObjectClassUser 增加 n_values 参数） */
fun JS_NewObjectClassUser(ctx: JSContext, classId: Int, nValues: Int): JSValue {
    if (classId < JSObjectClassEnum.JS_CLASS_USER.value || classId >= ctx.classCount) {
        return JS_EXCEPTION
    }
    if (nValues < 0 || nValues > 254) {
        /* 对照 C：n_values 受 extra_size 的 uint8 位宽限制 */
        return JS_EXCEPTION
    }
    val obj = JS_NewObjectProtoClass(ctx, ctx.classProto[classId], classId, 1 + nValues)
    if (JS_IsException(obj)) return obj
    val ptr = JS_VALUE_TO_PTR(obj)
    /* 私有 JSValue 槽（槽 1 起）初始化为 UNDEFINED；槽 0 为 opaque 占位，不存 JSValue */
    for (i in 0 until nValues) {
        ctx.memory.putJSValue(ptr + 24 + (1 + i) * 8, JS_UNDEFINED)
    }
    return obj
}

fun JS_GetClassID(ctx: JSContext, val1: JSValue): Int {
    if (!JS_IsPtr(val1)) return -1
    val ptr = JS_VALUE_TO_PTR(val1)
    if (ctx.memory.getMTag(ptr) != JS_MTAG_OBJECT) return -1
    return ctx.memory.getU8(ptr + 2)
}

/* opaque 存放在按对象地址索引的宿主引用表中（对照 C 的 p->u.user.opaque），
 * GC 搬移对象时由压缩器同步更新表 key，对象释放时由 GC 清理条目 */
fun JS_SetOpaque(ctx: JSContext, val1: JSValue, opaque: Any?) {
    if (!JS_IsPtr(val1)) return
    val ptr = JS_VALUE_TO_PTR(val1)
    if (ctx.memory.getMTag(ptr) != JS_MTAG_OBJECT) return
    val classId = ctx.memory.getU8(ptr + 2)
    if (classId >= JSObjectClassEnum.JS_CLASS_USER.value) {
        ctx.userOpaqueTable[ptr] = opaque
    }
}

fun JS_GetOpaque(ctx: JSContext, val1: JSValue): Any? {
    if (!JS_IsPtr(val1)) return null
    val ptr = JS_VALUE_TO_PTR(val1)
    if (ctx.memory.getMTag(ptr) != JS_MTAG_OBJECT) return null
    val classId = ctx.memory.getU8(ptr + 2)
    if (classId >= JSObjectClassEnum.JS_CLASS_USER.value) {
        return ctx.userOpaqueTable[ptr]
    }
    return null
}

/* 设置/读取用户对象的私有 JSValue（idx 从 0 开始，位于 opaque 占位槽之后）
 * （同步上游 #80：JS_SetUserValue/JS_GetUserValue） */
fun JS_SetUserValue(ctx: JSContext, obj: JSValue, idx: Int, value: JSValue) {
    if (!JS_IsPtr(obj)) return
    val ptr = JS_VALUE_TO_PTR(obj)
    val extraSize = ctx.memory.getU8(ptr + 3)
    require(idx + 1 < extraSize) { "user value index out of range" }
    ctx.memory.putJSValue(ptr + 24 + (1 + idx) * 8, value)
}

fun JS_GetUserValue(ctx: JSContext, obj: JSValue, idx: Int): JSValue {
    val ptr = JS_VALUE_TO_PTR(obj)
    val extraSize = ctx.memory.getU8(ptr + 3)
    require(idx + 1 < extraSize) { "user value index out of range" }
    return ctx.memory.getJSValue(ptr + 24 + (1 + idx) * 8)
}

/* 判断值是否为数组对象（同步上游 JS_IsArray 公共 API） */
fun JS_IsArray(ctx: JSContext, obj: JSValue): Boolean {
    if (!JS_IsPtr(obj)) return false
    val ptr = JS_VALUE_TO_PTR(obj)
    if (ctx.memory.getMTag(ptr) != JS_MTAG_OBJECT) return false
    return ctx.memory.getU8(ptr + 2) == JSObjectClassEnum.JS_CLASS_ARRAY.value
}

/* 设置/获取上下文宿主 opaque 引用（同步上游 JS_GetContextOpaque；setter 原先缺失，一并补齐） */
fun JS_SetContextOpaque(ctx: JSContext, opaque: Any?) {
    ctx.opaque = opaque
}

fun JS_GetContextOpaque(ctx: JSContext): Any? = ctx.opaque

/* 创建 Date 对象：extra 槽 0 存储 epoch 毫秒值（对照 C JSDate.dval，同步上游 new Date() 支持） */
fun JS_NewDate(ctx: JSContext, epochMs: Double): JSValue {
    val obj = JS_NewObjectProtoClass(ctx, ctx.classProto[JSObjectClassEnum.JS_CLASS_DATE.value],
                                     JSObjectClassEnum.JS_CLASS_DATE.value, 1)
    if (JS_IsException(obj)) return obj
    val ptr = JS_VALUE_TO_PTR(obj)
    ctx.memory.putFloat64(ptr + 24, epochMs)
    return obj
}

/* Date.prototype.valueOf：从 Date 对象 extra 槽 0 读取 epoch 毫秒
 * （对照 C js_date_valueOf；非 Date 对象应抛 TypeError，待错误对象机制完成后接入） */
fun jsDateValueOf(ctx: JSContext, thisVal: JSValue): JSValue {
    if (!JS_IsPtr(thisVal)) return JS_EXCEPTION
    val ptr = JS_VALUE_TO_PTR(thisVal)
    if (ctx.memory.getMTag(ptr) != JS_MTAG_OBJECT ||
        ctx.memory.getU8(ptr + 2) != JSObjectClassEnum.JS_CLASS_DATE.value) {
        return JS_EXCEPTION
    }
    return JS_NewFloat64(ctx, ctx.memory.getFloat64(ptr + 24))
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

/* 检查是否存在待处理的异常（同步上游 JS_HasException：JS_UNINITIALIZED 表示无异常） */
fun JS_HasException(ctx: JSContext): Boolean {
    return !JS_IsUninitialized(ctx.currentException)
}

fun JS_GetException(ctx: JSContext): JSValue {
    val ex = ctx.currentException
    ctx.currentException = JS_UNINITIALIZED
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
