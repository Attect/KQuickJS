package com.mquickjs

typealias JSWord = Long
typealias JSValue = Long

const val JSW: Int = 8

object JSTags {
    const val JS_TAG_INT: Int = 0
    const val JS_TAG_PTR: Int = 1
    const val JS_TAG_SPECIAL: Int = 3
    const val JS_TAG_BOOL: Int = JS_TAG_SPECIAL or (0 shl 2)
    const val JS_TAG_NULL: Int = JS_TAG_SPECIAL or (1 shl 2)
    const val JS_TAG_UNDEFINED: Int = JS_TAG_SPECIAL or (2 shl 2)
    const val JS_TAG_EXCEPTION: Int = JS_TAG_SPECIAL or (3 shl 2)
    const val JS_TAG_SHORT_FUNC: Int = JS_TAG_SPECIAL or (4 shl 2)
    const val JS_TAG_UNINITIALIZED: Int = JS_TAG_SPECIAL or (5 shl 2)
    const val JS_TAG_STRING_CHAR: Int = JS_TAG_SPECIAL or (6 shl 2)
    const val JS_TAG_CATCH_OFFSET: Int = JS_TAG_SPECIAL or (7 shl 2)
    const val JS_TAG_SHORT_FLOAT: Int = 5
    
    const val JS_TAG_SPECIAL_BITS: Int = 5
    
    const val JS_EX_NORMAL: Int = 0
    const val JS_EX_CALL: Int = 1
}

fun JS_VALUE_GET_INT(v: JSValue): Int = (v shr 1).toInt()
fun JS_VALUE_GET_SPECIAL_VALUE(v: JSValue): Int = (v shr JSTags.JS_TAG_SPECIAL_BITS).toInt()
fun JS_VALUE_GET_SPECIAL_TAG(v: JSValue): Int = (v and ((1L shl JSTags.JS_TAG_SPECIAL_BITS) - 1)).toInt()
fun JS_VALUE_MAKE_SPECIAL(tag: Int, v: Int): JSValue = tag.toLong() or (v.toLong() shl JSTags.JS_TAG_SPECIAL_BITS)

val JS_NULL: JSValue = JS_VALUE_MAKE_SPECIAL(JSTags.JS_TAG_NULL, 0)
val JS_UNDEFINED: JSValue = JS_VALUE_MAKE_SPECIAL(JSTags.JS_TAG_UNDEFINED, 0)
val JS_UNINITIALIZED: JSValue = JS_VALUE_MAKE_SPECIAL(JSTags.JS_TAG_UNINITIALIZED, 0)
val JS_FALSE: JSValue = JS_VALUE_MAKE_SPECIAL(JSTags.JS_TAG_BOOL, 0)
val JS_TRUE: JSValue = JS_VALUE_MAKE_SPECIAL(JSTags.JS_TAG_BOOL, 1)
val JS_EXCEPTION: JSValue = JS_VALUE_MAKE_SPECIAL(JSTags.JS_TAG_EXCEPTION, JSTags.JS_EX_NORMAL)

fun JS_IsInt(v: JSValue): Boolean = (v and 1L) == JSTags.JS_TAG_INT.toLong()
fun JS_IsPtr(v: JSValue): Boolean = (v and (JSW - 1).toLong()) == JSTags.JS_TAG_PTR.toLong()
fun JS_IsShortFloat(v: JSValue): Boolean = (v and (JSW - 1).toLong()) == JSTags.JS_TAG_SHORT_FLOAT.toLong()
fun JS_IsBool(v: JSValue): Boolean = JS_VALUE_GET_SPECIAL_TAG(v) == JSTags.JS_TAG_BOOL
fun JS_IsNull(v: JSValue): Boolean = v == JS_NULL
fun JS_IsUndefined(v: JSValue): Boolean = v == JS_UNDEFINED
fun JS_IsUninitialized(v: JSValue): Boolean = v == JS_UNINITIALIZED
fun JS_IsException(v: JSValue): Boolean = v == JS_EXCEPTION

fun JS_NewBool(val1: Boolean): JSValue = JS_VALUE_MAKE_SPECIAL(JSTags.JS_TAG_BOOL, if (val1) 1 else 0)

fun JS_VALUE_TO_PTR(v: JSValue): Int = (v - 1).toInt()
fun JS_VALUE_FROM_PTR(ptr: Int): JSValue = ptr.toLong() + 1
