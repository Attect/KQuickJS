package app.muka.project.kquickjs

data class JSCFunctionDef(
    val func: ((ctx: JSContext, thisVal: JSValue, argc: Int, argv: LongArray) -> JSValue)? = null,
    val funcMagic: ((ctx: JSContext, thisVal: JSValue, argc: Int, argv: LongArray, magic: Int) -> JSValue)? = null,
    val funcParams: ((ctx: JSContext, thisVal: JSValue, argc: Int, argv: LongArray, params: JSValue) -> JSValue)? = null,
    val funcFF: ((f: Double) -> Double)? = null,
    val name: JSValue = JS_NULL,
    val defType: Int = JSCFunctionDefEnum.JS_CFUNC_generic.value,
    val argCount: Int = 0,
    val magic: Int = 0
)
