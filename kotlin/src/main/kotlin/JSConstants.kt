package com.mquickjs

object JSMTags {
    const val JS_MTAG_FREE: Int = 0
    const val JS_MTAG_OBJECT: Int = 1
    const val JS_MTAG_FLOAT64: Int = 2
    const val JS_MTAG_STRING: Int = 3
    const val JS_MTAG_FUNCTION_BYTECODE: Int = 4
    const val JS_MTAG_VALUE_ARRAY: Int = 5
    const val JS_MTAG_BYTE_ARRAY: Int = 6
    const val JS_MTAG_VARREF: Int = 7
    const val JS_MTAG_COUNT: Int = 8
    
    const val JS_MTAG_BITS: Int = 4
    
    val MTAG_NAMES = arrayOf(
        "free",
        "object",
        "float64",
        "string",
        "func_bytecode",
        "value_array",
        "byte_array",
        "varref"
    )
    
    fun getMTagName(mtag: Int): String {
        return if (mtag in MTAG_NAMES.indices) MTAG_NAMES[mtag] else "?"
    }
}

const val JS_STACK_SLACK: Int = 16
const val JS_MIN_FREE_SIZE: Int = 512
const val JS_MAX_LOCAL_VARS: Int = 65535
const val JS_MAX_FUNC_STACK_SIZE: Int = 65535
const val JS_MAX_ARGC: Int = 65535
const val JS_MAX_CALL_RECURSE: Int = 8
const val JS_INTERRUPT_COUNTER_INIT: Int = 10000

const val JS_STRING_POS_CACHE_SIZE: Int = 2
const val JS_STRING_POS_CACHE_MIN_LEN: Int = 16

const val JS_BYTECODE_MAGIC: Int = 0xacfb

const val FRAME_CF_ARGC_MASK: Int = 0xffff
const val FRAME_CF_CTOR: Int = (1 shl 16)
const val FRAME_CF_POP_RET: Int = (1 shl 17)
const val FRAME_CF_PC_ADD1: Int = (1 shl 18)

const val JS_EVAL_RETVAL: Int = (1 shl 0)
const val JS_EVAL_REPL: Int = (1 shl 1)
const val JS_EVAL_STRIP_COL: Int = (1 shl 2)
const val JS_EVAL_JSON: Int = (1 shl 3)
const val JS_EVAL_REGEXP: Int = (1 shl 4)
const val JS_EVAL_REGEXP_FLAGS_SHIFT: Int = 8

const val JS_DUMP_LONG: Int = (1 shl 0)
const val JS_DUMP_NOQUOTE: Int = (1 shl 1)
const val JS_DUMP_RAW: Int = (1 shl 2)
