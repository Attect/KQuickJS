package com.mquickjs

import com.mquickjs.JSMTags.JS_MTAG_FLOAT64
import com.mquickjs.JSMTags.JS_MTAG_STRING
import com.mquickjs.memory.getMTag

const val JS_SHORTINT_MIN = -(1 shl 30)
const val JS_SHORTINT_MAX = (1 shl 30) - 1

fun JS_NewShortInt(v: Int): JSValue {
    return (v.toLong() shl 1)
}

fun JS_NewInt32(ctx: JSContext, v: Int): JSValue {
    if (v in JS_SHORTINT_MIN..JS_SHORTINT_MAX) {
        return JS_NewShortInt(v)
    }
    return JS_NewFloat64(ctx, v.toDouble())
}

fun JS_NewUint32(ctx: JSContext, v: Long): JSValue {
    if (v <= JS_SHORTINT_MAX.toLong()) {
        return JS_NewShortInt(v.toInt())
    }
    return JS_NewFloat64(ctx, v.toDouble())
}

fun JS_NewInt64(ctx: JSContext, v: Long): JSValue {
    if (v in JS_SHORTINT_MIN.toLong()..JS_SHORTINT_MAX.toLong()) {
        return JS_NewShortInt(v.toInt())
    }
    return JS_NewFloat64(ctx, v.toDouble())
}

fun JS_NewFloat64(ctx: JSContext, d: Double): JSValue {
    val bits = d.toBits()
    if (bits == (-0.0).toBits()) {
        return ctx.minusZero
    }
    
    if (tryToShortFloat(d)) {
        return jsToShortFloat(d)
    }
    
    return allocFloat64(ctx, d)
}

fun tryToShortFloat(d: Double): Boolean {
    if (d.isNaN() || d.isInfinite()) return false
    val absD = kotlin.math.abs(d)
    val minVal = Math.pow(2.0, -127.0)
    val maxVal = Math.pow(2.0, 128.0)
    return absD >= minVal && absD <= maxVal
}

fun jsToShortFloat(d: Double): JSValue {
    val bits = d.toBits()
    val addend = ((1023 - 127 - (5 shl 8)).toLong() shl 52)
    return rotl64(bits - addend, 4)
}

fun jsGetShortFloat(v: JSValue): Double {
    val addend = ((1023 - 127 - (5 shl 8)).toLong() shl 52)
    return Double.fromBits(rotl64(v, 60) + addend)
}

fun rotl64(a: Long, n: Int): Long {
    return (a shl n) or (a ushr (64 - n))
}

fun allocFloat64(ctx: JSContext, d: Double): JSValue {
    val ptr = ctx.malloc(16, JS_MTAG_FLOAT64)
    if (ptr == 0) return JS_EXCEPTION
    ctx.memory.putFloat64(ptr + 8, d)
    return JS_VALUE_FROM_PTR(ptr)
}

fun JS_IsNumber(ctx: JSContext, v: JSValue): Boolean {
    if (JS_IsInt(v)) return true
    if (JS_IsShortFloat(v)) return true
    if (JS_IsPtr(v)) {
        val ptr = JS_VALUE_TO_PTR(v)
        return ctx.memory.getMTag(ptr) == JS_MTAG_FLOAT64
    }
    return false
}

fun JS_ToNumber(ctx: JSContext, v: JSValue): Double {
    if (JS_IsInt(v)) {
        return JS_VALUE_GET_INT(v).toDouble()
    }
    if (JS_IsShortFloat(v)) {
        return jsGetShortFloat(v)
    }
    if (JS_IsPtr(v)) {
        val ptr = JS_VALUE_TO_PTR(v)
        val mtag = ctx.memory.getMTag(ptr)
        when (mtag) {
            JS_MTAG_FLOAT64 -> return ctx.memory.getFloat64(ptr + 8)
            JS_MTAG_STRING -> {
                val str = jsGetString(ctx, v)
                return str.toDoubleOrNull() ?: Double.NaN
            }
        }
    }
    when (v) {
        JS_NULL -> return 0.0
        JS_UNDEFINED -> return Double.NaN
        JS_TRUE -> return 1.0
        JS_FALSE -> return 0.0
    }
    return Double.NaN
}

fun JS_ToInt32(ctx: JSContext, v: JSValue): Int {
    val d = JS_ToNumber(ctx, v)
    if (d.isNaN() || d.isInfinite()) return 0
    return d.toInt()
}

fun JS_ToUint32(ctx: JSContext, v: JSValue): Long {
    val d = JS_ToNumber(ctx, v)
    if (d.isNaN() || d.isInfinite()) return 0
    return d.toLong() and 0xFFFFFFFFL
}

fun jsGetString(ctx: JSContext, v: JSValue): String {
    if (!JS_IsPtr(v)) return ""
    val ptr = JS_VALUE_TO_PTR(v)
    if (ctx.memory.getMTag(ptr) != JS_MTAG_STRING) return ""
    
    val len = ctx.memory.getI32(ptr + 4)
    val buf = ctx.memory.getBytes(ptr + 8, len)
    return String(buf, Charsets.UTF_8)
}

fun JS_NewStringLen(ctx: JSContext, buf: ByteArray, bufLen: Int): JSValue {
    val ptr = ctx.malloc(8 + 8 + ((bufLen + JSW) and (JSW - 1).inv()), JS_MTAG_STRING)
    if (ptr == 0) return JS_EXCEPTION
    
    ctx.memory.putU8(ptr, (JS_MTAG_STRING shl 1))
    ctx.memory.putU8(ptr + 1, 0)
    ctx.memory.putU8(ptr + 2, 0)
    ctx.memory.putU8(ptr + 3, 0)
    ctx.memory.putI32(ptr + 4, bufLen)
    ctx.memory.putBytes(ptr + 8, buf, 0, bufLen)
    
    return JS_VALUE_FROM_PTR(ptr)
}

fun JS_NewString(ctx: JSContext, str: String): JSValue {
    val bytes = str.toByteArray(Charsets.UTF_8)
    return JS_NewStringLen(ctx, bytes, bytes.size)
}
