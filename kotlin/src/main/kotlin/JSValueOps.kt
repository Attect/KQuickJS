package app.muka.project.kquickjs

import app.muka.project.kquickjs.JSMTags.JS_MTAG_FLOAT64
import app.muka.project.kquickjs.JSMTags.JS_MTAG_STRING
import app.muka.project.kquickjs.JSMTags.JS_MTAG_VARREF
import app.muka.project.kquickjs.memory.getMTag

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
        /* -0 必须用唯一的 float64 对象表示（延迟创建） */
        if (ctx.minusZero == JS_NULL || ctx.minusZero == 0L) {
            ctx.minusZero = allocFloat64(ctx, -0.0)
        }
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
    return jsDecodeString(ctx.memory.getBytes(ptr + 8, len))
}

/* ---------------- BigInt ----------------
 * JVM 移植版用一个 detach 的 VARREF 块承载 BigInt（块头 mtag 只有 3 位，
 * 不能再新增 mtag）：+2 的 detached 标志置 1，+4 写入魔数区分于普通 varref；
 * BigInteger 本体存放于 userOpaqueTable（GC 释放时清理、搬移时更新 key）。 */
const val JS_BIGINT_MAGIC: Int = 0x42494749  /* "BIGI" */

fun JS_IsBigInt(ctx: JSContext, v: JSValue): Boolean {
    if (!JS_IsPtr(v)) return false
    val p = JS_VALUE_TO_PTR(v)
    if (p < ctx.heapBase || p >= ctx.heapFree) return false
    if (ctx.memory.getMTag(p) != JS_MTAG_VARREF) return false
    return (ctx.memory.getU8(p + 2) and 1) != 0 && ctx.memory.getI32(p + 4) == JS_BIGINT_MAGIC
}

fun JS_GetBigInt(ctx: JSContext, v: JSValue): java.math.BigInteger? {
    if (!JS_IsBigInt(ctx, v)) return null
    return ctx.userOpaqueTable[JS_VALUE_TO_PTR(v)] as? java.math.BigInteger
}

fun JS_NewBigInt(ctx: JSContext, value: java.math.BigInteger): JSValue {
    val ptr = ctx.malloc(16, JS_MTAG_VARREF)
    if (ptr == 0) return JS_EXCEPTION
    ctx.memory.putU8(ptr + 2, 1)              /* detached：GC 不遍历 +16 */
    ctx.memory.putI32(ptr + 4, JS_BIGINT_MAGIC)
    ctx.userOpaqueTable[ptr] = value
    return JS_VALUE_FROM_PTR(ptr)
}

fun JS_NewBigInt(ctx: JSContext, value: Long): JSValue =
    JS_NewBigInt(ctx, java.math.BigInteger.valueOf(value))
/* JS 字符串使用 WTF-8 编码存储：允许孤立代理项（\udbc4 等）原样往返，
 * 普通 UTF-8 无法表示孤立代理项（会被替换成 '?'），会破坏字符串拼接/切片语义 */
fun jsEncodeString(str: String): ByteArray {
    val out = java.io.ByteArrayOutputStream(str.length + 8)
    var i = 0
    while (i < str.length) {
        val c = str[i].code
        if (c < 0x80) {
            out.write(c)
        } else if (c < 0x800) {
            out.write(0xC0 or (c shr 6))
            out.write(0x80 or (c and 0x3F))
        } else if (Character.isHighSurrogate(str[i]) && i + 1 < str.length &&
                   Character.isLowSurrogate(str[i + 1])) {
            val cp = Character.toCodePoint(str[i], str[i + 1])
            out.write(0xF0 or (cp shr 18))
            out.write(0x80 or ((cp shr 12) and 0x3F))
            out.write(0x80 or ((cp shr 6) and 0x3F))
            out.write(0x80 or (cp and 0x3F))
            i++
        } else {
            out.write(0xE0 or (c shr 12))
            out.write(0x80 or ((c shr 6) and 0x3F))
            out.write(0x80 or (c and 0x3F))
        }
        i++
    }
    return out.toByteArray()
}

fun jsDecodeString(b: ByteArray): String {
    val sb = StringBuilder(b.size)
    var i = 0
    while (i < b.size) {
        val c = b[i].toInt() and 0xFF
        when {
            c < 0x80 -> {
                sb.append(c.toChar()); i++
            }
            (c and 0xE0) == 0xC0 && i + 1 < b.size -> {
                val cp = ((c and 0x1F) shl 6) or (b[i + 1].toInt() and 0x3F)
                sb.append(cp.toChar()); i += 2
            }
            (c and 0xF0) == 0xE0 && i + 2 < b.size -> {
                val cp = ((c and 0x0F) shl 12) or
                         ((b[i + 1].toInt() and 0x3F) shl 6) or
                         (b[i + 2].toInt() and 0x3F)
                /* 代理项（0xD800..0xDFFF）也按单个 UTF-16 码元保留 */
                sb.append(cp.toChar()); i += 3
            }
            (c and 0xF8) == 0xF0 && i + 3 < b.size -> {
                val cp = ((c and 0x07) shl 18) or
                         ((b[i + 1].toInt() and 0x3F) shl 12) or
                         ((b[i + 2].toInt() and 0x3F) shl 6) or
                         (b[i + 3].toInt() and 0x3F)
                sb.append(String(Character.toChars(cp))); i += 4
            }
            else -> {
                sb.append('\uFFFD'); i++
            }
        }
    }
    return sb.toString()
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
    val bytes = jsEncodeString(str)
    return JS_NewStringLen(ctx, bytes, bytes.size)
}
