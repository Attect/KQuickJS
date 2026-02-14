package com.mquickjs

enum class JSObjectClassEnum(val value: Int) {
    JS_CLASS_OBJECT(0),
    JS_CLASS_ARRAY(1),
    JS_CLASS_C_FUNCTION(2),
    JS_CLASS_CLOSURE(3),
    JS_CLASS_NUMBER(4),
    JS_CLASS_BOOLEAN(5),
    JS_CLASS_STRING(6),
    JS_CLASS_DATE(7),
    JS_CLASS_REGEXP(8),
    
    JS_CLASS_ERROR(9),
    JS_CLASS_EVAL_ERROR(10),
    JS_CLASS_RANGE_ERROR(11),
    JS_CLASS_REFERENCE_ERROR(12),
    JS_CLASS_SYNTAX_ERROR(13),
    JS_CLASS_TYPE_ERROR(14),
    JS_CLASS_URI_ERROR(15),
    JS_CLASS_INTERNAL_ERROR(16),
    
    JS_CLASS_ARRAY_BUFFER(17),
    JS_CLASS_TYPED_ARRAY(18),
    
    JS_CLASS_UINT8C_ARRAY(19),
    JS_CLASS_INT8_ARRAY(20),
    JS_CLASS_UINT8_ARRAY(21),
    JS_CLASS_INT16_ARRAY(22),
    JS_CLASS_UINT16_ARRAY(23),
    JS_CLASS_INT32_ARRAY(24),
    JS_CLASS_UINT32_ARRAY(25),
    JS_CLASS_FLOAT32_ARRAY(26),
    JS_CLASS_FLOAT64_ARRAY(27),
    
    JS_CLASS_USER(28);
    
    companion object {
        fun fromValue(value: Int): JSObjectClassEnum? = entries.find { it.value == value }
    }
}

enum class JSCFunctionEnum(val value: Int) {
    JS_CFUNCTION_bound(0),
    JS_CFUNCTION_USER(1);
}

enum class JSCFunctionDefEnum(val value: Int) {
    JS_CFUNC_generic(0),
    JS_CFUNC_generic_magic(1),
    JS_CFUNC_constructor(2),
    JS_CFUNC_constructor_magic(3),
    JS_CFUNC_generic_params(4),
    JS_CFUNC_f_f(5);
}

enum class JSPropTypeEnum(val value: Int) {
    JS_PROP_NORMAL(0),
    JS_PROP_GETSET(1),
    JS_PROP_VARREF(2),
    JS_PROP_SPECIAL(3);
}

enum class JSVarRefKindEnum(val value: Int) {
    JS_VARREF_KIND_ARG(0),
    JS_VARREF_KIND_VAR(1),
    JS_VARREF_KIND_VAR_REF(2),
    JS_VARREF_KIND_GLOBAL(3);
}
