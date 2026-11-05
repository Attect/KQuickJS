package app.muka.project.kquickjs

import app.muka.project.kquickjs.memory.getMTag
import app.muka.project.kquickjs.runtime.JSRuntime

/**
 * 最小标准库引导（对照 C 版 mqjs_stdlib.c）。
 *
 * 该移植版此前完全没有内建对象，只有 CLI 注册的 Date。这里以 Kotlin 原生函数
 * 的形式注册一组核心内建：全局值、Error 家族、Object/String/Array/Number、
 * Math、JSON、parseInt/parseFloat/isNaN 等。
 *
 * 说明：
 * - 与 C 版不同，函数表在 JS_NewContext 之后可扩展，原生函数通过
 *   ctx.extFunctionTable 动态注册；
 * - 只有在堆足够大（>= minMemSize）时才初始化，避免 64KB 的单元测试上下文
 *   OOM（这些测试不需要标准库）。
 */
object JSStdlib {

    const val MIN_MEM_SIZE = 160 * 1024

    private fun native(ctx: JSContext, length: Int,
                       fn: (JSContext, JSValue, List<JSValue>) -> JSValue): JSValue {
        val base = ctx.cFunctionTable?.size ?: 0
        val idx = base + ctx.extFunctionTable.size
        ctx.extFunctionTable.add(
            JSCFunctionDef(
                func = { c, thisVal, _: Int, argv: LongArray -> fn(c, thisVal, argv.toList()) },
                defType = JSCFunctionDefEnum.JS_CFUNC_generic.value,
                argCount = length
            )
        )
        return JS_NewCFunctionParams(ctx, idx, JS_NULL)
    }

    private fun defGlobal(ctx: JSContext, rt: JSRuntime, name: String, value: JSValue) {
        rt.setProperty(ctx.globalObj, JS_NewString(ctx, name), value)
    }

    private fun defMethod(ctx: JSContext, rt: JSRuntime, obj: JSValue, name: String, length: Int,
                          fn: (JSContext, JSValue, List<JSValue>) -> JSValue) {
        rt.setProperty(obj, JS_NewString(ctx, name), native(ctx, length, fn))
    }

    private fun defValue(ctx: JSContext, rt: JSRuntime, obj: JSValue, name: String, value: JSValue) {
        rt.setProperty(obj, JS_NewString(ctx, name), value)
    }

    private fun arg(args: List<JSValue>, i: Int): JSValue = args.getOrElse(i) { JS_UNDEFINED }

    /* ---------------- 值转换辅助 ---------------- */

    fun toStr(ctx: JSContext, rt: JSRuntime, v: JSValue): String = rt.toStringValue(v)

    private fun toNum(ctx: JSContext, rt: JSRuntime, v: JSValue): Double = rt.toNumber(v)

    private fun toInt(ctx: JSContext, rt: JSRuntime, v: JSValue): Int {
        val d = toNum(ctx, rt, v)
        return if (d.isNaN() || d.isInfinite()) 0 else d.toInt()
    }

    private fun throwTypeError(ctx: JSContext, msg: String): JSValue = JS_ThrowTypeError(ctx, msg)

    private fun throwRangeError(ctx: JSContext, msg: String): JSValue = JS_ThrowRangeError(ctx, msg)

    /* ---------------- 入口 ---------------- */

    fun init(ctx: JSContext, rt: JSRuntime) {
        if (ctx.memory.size < MIN_MEM_SIZE) return
        try {
            /* 先建立各类原型对象：原生函数/闭包/数组在创建时都会引用它们 */
            val funcProto = JS_NewObject(ctx)
            ctx.classProto[JSObjectClassEnum.JS_CLASS_CLOSURE.value] = funcProto
            val stringProto = JS_NewObject(ctx)
            ctx.classProto[JSObjectClassEnum.JS_CLASS_STRING.value] = stringProto
            val numberProto = JS_NewObject(ctx)
            ctx.classProto[JSObjectClassEnum.JS_CLASS_NUMBER.value] = numberProto
            val booleanProto = JS_NewObject(ctx)
            ctx.classProto[JSObjectClassEnum.JS_CLASS_BOOLEAN.value] = booleanProto
            val arrayProto = JS_NewObject(ctx)
            ctx.classProto[JSObjectClassEnum.JS_CLASS_ARRAY.value] = arrayProto

            initPrimitives(ctx, rt)
            initErrors(ctx, rt)
            initObject(ctx, rt)
            initFunction(ctx, rt, funcProto)
            initArray(ctx, rt)
            initString(ctx, rt)
            initNumber(ctx, rt)
            initRegExp(ctx, rt)
            initMath(ctx, rt)
            initJson(ctx, rt)
        } catch (e: Exception) {
            /* 初始化失败不阻塞引擎创建（记录到 stderr 便于诊断） */
            System.err.println("JSStdlib init failed: ${e::class.simpleName}: ${e.message}")
        }
    }
    
    private fun initFunction(ctx: JSContext, rt: JSRuntime, proto: JSValue) {
        defMethod(ctx, rt, proto, "call", 1) { c, thisVal, args ->
            if (args.isEmpty()) rt.callFunction(thisVal, emptyList(), JS_UNDEFINED)
            else rt.callFunction(thisVal, args.drop(1), args[0])
        }
        defMethod(ctx, rt, proto, "apply", 2) { c, thisVal, args ->
            val thisArg = arg(args, 0)
            val arr = arg(args, 1)
            val list = ArrayList<JSValue>()
            if (JS_IsPtr(arr) && c.memory.getMTag(JS_VALUE_TO_PTR(arr)) == JSMTags.JS_MTAG_OBJECT) {
                val len = c.memory.getI32(JS_VALUE_TO_PTR(arr) + 32)
                for (i in 0 until len) list.add(rt.getElement(arr, JS_NewShortInt(i)))
            }
            rt.callFunction(thisVal, list, thisArg)
        }
        defMethod(ctx, rt, proto, "toString", 0) { c, _, _ ->
            JS_NewString(c, "function () { [native code] }")
        }
    }

    private fun initPrimitives(ctx: JSContext, rt: JSRuntime) {
        defGlobal(ctx, rt, "undefined", JS_UNDEFINED)
        defGlobal(ctx, rt, "NaN", JS_NewFloat64(ctx, Double.NaN))
        defGlobal(ctx, rt, "Infinity", JS_NewFloat64(ctx, Double.POSITIVE_INFINITY))

        defGlobal(ctx, rt, "isNaN", native(ctx, 1) { c, _, args ->
            JS_NewBool(toNum(c, rt, arg(args, 0)).isNaN())
        })
        defGlobal(ctx, rt, "isFinite", native(ctx, 1) { c, _, args ->
            val d = toNum(c, rt, arg(args, 0))
            JS_NewBool(!d.isNaN() && !d.isInfinite())
        })
        defGlobal(ctx, rt, "parseInt", native(ctx, 2) { c, _, args ->
            parseIntImpl(c, arg(args, 0), arg(args, 1))
        })
        defGlobal(ctx, rt, "parseFloat", native(ctx, 1) { c, _, args ->
            parseFloatImpl(c, toStr(c, rt, arg(args, 0)))
        })
        defGlobal(ctx, rt, "print", native(ctx, 1) { c, _, args ->
            val sb = StringBuilder()
            for (i in args.indices) {
                if (i > 0) sb.append(' ')
                sb.append(toStr(c, rt, args[i]))
            }
            println(sb.toString())
            JS_UNDEFINED
        })
        
        /* console.log（对照 C 宿主注册的 console 对象） */
        val consoleObj = JS_NewObject(ctx)
        val logFn = native(ctx, 1) { c, _, args ->
            val sb = StringBuilder()
            for (i in args.indices) {
                if (i > 0) sb.append(' ')
                sb.append(toStr(c, rt, args[i]))
            }
            println(sb.toString())
            JS_UNDEFINED
        }
        defValue(ctx, rt, consoleObj, "log", logFn)
        defValue(ctx, rt, consoleObj, "info", logFn)
        defValue(ctx, rt, consoleObj, "warn", logFn)
        defValue(ctx, rt, consoleObj, "error", logFn)
        defGlobal(ctx, rt, "console", consoleObj)
    }

    private fun parseIntImpl(ctx: JSContext, v: JSValue, radix: JSValue): JSValue {
        val s = if (JS_IsUndefined(v) || JS_IsNull(v)) "" else jsGetString(ctx, v)
        var str = s.trim()
        var sign = 1
        if (str.startsWith("-")) { sign = -1; str = str.substring(1) }
        else if (str.startsWith("+")) str = str.substring(1)
        var base = 10
        if (!JS_IsUndefined(radix)) {
            base = JS_ToInt32(ctx, radix)
        } else if (str.startsWith("0x") || str.startsWith("0X")) {
            base = 16; str = str.substring(2)
        }
        if (base !in 2..36) return JS_NewFloat64(ctx, Double.NaN)
        var end = 0
        while (end < str.length && Character.digit(str[end], base) >= 0) end++
        if (end == 0) return JS_NewFloat64(ctx, Double.NaN)
        val value = str.substring(0, end).toLongOrNull(base) ?: return JS_NewFloat64(ctx, Double.NaN)
        return JS_NewFloat64(ctx, sign.toDouble() * value.toDouble())
    }

    private fun parseFloatImpl(ctx: JSContext, s: String): JSValue {
        val str = s.trim()
        var end = 0
        var seenDot = false
        var seenExp = false
        while (end < str.length) {
            val c = str[end]
            if (c.isDigit() || c == '+' || c == '-') {
                end++
            } else if (c == '.' && !seenDot && !seenExp) {
                seenDot = true; end++
            } else if ((c == 'e' || c == 'E') && !seenExp && end > 0) {
                seenExp = true; end++
            } else {
                break
            }
        }
        if (end == 0) return JS_NewFloat64(ctx, Double.NaN)
        return JS_NewFloat64(ctx, str.substring(0, end).toDoubleOrNull() ?: Double.NaN)
    }

    /* ---------------- Error 家族 ---------------- */

    private fun makeErrorProto(ctx: JSContext, rt: JSRuntime, proto: JSValue): JSValue {
        val p = JS_NewObjectProtoClass(ctx, proto, JSObjectClassEnum.JS_CLASS_OBJECT.value, 0)
        return p
    }

    private fun initErrors(ctx: JSContext, rt: JSRuntime) {
        /* Error.prototype 作为所有错误实例的最终原型，并写入 classProto[ERROR] */
        val errorProto = JS_NewObject(ctx)
        ctx.classProto[JSObjectClassEnum.JS_CLASS_ERROR.value] = errorProto

        fun errorGlobal(name: String, classEnum: JSObjectClassEnum): JSValue {
            val proto = makeErrorProto(ctx, rt, errorProto)
            ctx.classProto[classEnum.value] = proto
            val ctor = native(ctx, 1) { c, _, args ->
                val obj = JS_NewObjectProtoClass(c, proto, JSObjectClassEnum.JS_CLASS_ERROR.value, 2)
                if (JS_IsException(obj)) {
                    JS_EXCEPTION
                } else {
                    val msg = if (args.isEmpty() || JS_IsUndefined(args[0])) JS_NewString(c, "")
                              else JS_NewString(c, rt.toStringValue(args[0]))
                    if (JS_IsException(msg)) {
                        JS_EXCEPTION
                    } else {
                        val ptr = JS_VALUE_TO_PTR(obj)
                        c.memory.putJSValue(ptr + 24, msg)
                        c.memory.putJSValue(ptr + 32, JS_NULL)
                        obj
                    }
                }
            }
            rt.setProperty(ctor, JS_NewString(ctx, "prototype"), proto)
            rt.setProperty(proto, JS_NewString(ctx, "constructor"), ctor)
            defValue(ctx, rt, proto, "name", JS_NewString(ctx, name))
            defValue(ctx, rt, proto, "message", JS_NewString(ctx, ""))
            defGlobal(ctx, rt, name, ctor)
            return ctor
        }

        defMethod(ctx, rt, errorProto, "toString", 0) { c, thisVal, _ ->
            /* Error 实例的 message 存在对象 extra 槽（不是普通属性） */
            val msg: JSValue = if (JS_IsError(c, thisVal)) {
                c.memory.getJSValue(JS_VALUE_TO_PTR(thisVal) + 24)
            } else {
                getStr(c, rt, thisVal, "message", "")
            }
            val nameVal = getStr(c, rt, thisVal, "name", "Error")
            val n = rt.toStringValue(nameVal)
            val m = rt.toStringValue(msg)
            JS_NewString(c, if (m.isEmpty()) n else "$n: $m")
        }

        errorGlobal("Error", JSObjectClassEnum.JS_CLASS_ERROR)
        errorGlobal("EvalError", JSObjectClassEnum.JS_CLASS_EVAL_ERROR)
        errorGlobal("RangeError", JSObjectClassEnum.JS_CLASS_RANGE_ERROR)
        errorGlobal("ReferenceError", JSObjectClassEnum.JS_CLASS_REFERENCE_ERROR)
        errorGlobal("SyntaxError", JSObjectClassEnum.JS_CLASS_SYNTAX_ERROR)
        errorGlobal("TypeError", JSObjectClassEnum.JS_CLASS_TYPE_ERROR)
        errorGlobal("URIError", JSObjectClassEnum.JS_CLASS_URI_ERROR)
        errorGlobal("InternalError", JSObjectClassEnum.JS_CLASS_INTERNAL_ERROR)
    }

    private fun getStr(ctx: JSContext, rt: JSRuntime, obj: JSValue, key: String, dflt: String): JSValue {
        val v = rt.getProperty(obj, JS_NewString(ctx, key))
        return if (JS_IsUndefined(v) || JS_IsNull(v)) JS_NewString(ctx, dflt) else v
    }

    /* ---------------- Object ---------------- */

    private fun initObject(ctx: JSContext, rt: JSRuntime) {
        val proto = ctx.classProto[JSObjectClassEnum.JS_CLASS_OBJECT.value]
        val objCtor = native(ctx, 1) { c, _, args ->
            if (args.isNotEmpty() && JS_IsPtr(args[0]) &&
                c.memory.getMTag(JS_VALUE_TO_PTR(args[0])) == JSMTags.JS_MTAG_OBJECT) {
                args[0]
            } else {
                JS_NewObject(c)
            }
        }
        rt.setProperty(objCtor, JS_NewString(ctx, "prototype"), proto)
        rt.setProperty(proto, JS_NewString(ctx, "constructor"), objCtor)
        defGlobal(ctx, rt, "Object", objCtor)

        defMethod(ctx, rt, objCtor, "getPrototypeOf", 1) { c, _, args ->
            val o = arg(args, 0)
            if (!JS_IsPtr(o) || c.memory.getMTag(JS_VALUE_TO_PTR(o)) != JSMTags.JS_MTAG_OBJECT) {
                throwTypeError(c, "not an object")
            } else {
                c.memory.getJSValue(JS_VALUE_TO_PTR(o) + 8)
            }
        }
        defMethod(ctx, rt, objCtor, "setPrototypeOf", 2) { c, _, args ->
            val o = arg(args, 0)
            val p = arg(args, 1)
            if (JS_IsPtr(o) && c.memory.getMTag(JS_VALUE_TO_PTR(o)) == JSMTags.JS_MTAG_OBJECT) {
                val np = if (JS_IsNull(p) || (JS_IsPtr(p) &&
                    c.memory.getMTag(JS_VALUE_TO_PTR(p)) == JSMTags.JS_MTAG_OBJECT)) p else JS_NULL
                c.memory.putJSValue(JS_VALUE_TO_PTR(o) + 8, np)
            }
            o
        }
        defMethod(ctx, rt, objCtor, "create", 2) { c, _, args ->
            val p = arg(args, 0)
            val np = if (JS_IsNull(p)) JS_NULL else p
            JS_NewObjectProtoClass(c, np, JSObjectClassEnum.JS_CLASS_OBJECT.value, 0)
        }
        defMethod(ctx, rt, objCtor, "keys", 1) { c, _, args ->
            rt.jsObjectKeys(arg(args, 0))
        }
        defMethod(ctx, rt, objCtor, "defineProperty", 3) { c, _, args ->
            val o = arg(args, 0)
            val k = arg(args, 1)
            val d = arg(args, 2)
            definePropertyImpl(c, rt, o, k, d)
            o
        }
        defMethod(ctx, rt, objCtor, "getOwnPropertyNames", 1) { c, _, args ->
            rt.jsObjectKeys(arg(args, 0))
        }

        defMethod(ctx, rt, proto, "hasOwnProperty", 1) { c, thisVal, args ->
            JS_NewBool(hasOwn(c, rt, thisVal, arg(args, 0)))
        }
        defMethod(ctx, rt, proto, "toString", 0) { c, thisVal, _ ->
            /* this 由 call/apply 传入；普通调用时 this 是对象本身 */
            JS_NewString(c, objectTag(c, rt, thisVal))
        }
        defMethod(ctx, rt, proto, "valueOf", 0) { _, thisVal, _ -> thisVal }
    }
    
    /* Object.prototype.toString 的标签（按 this 的实际类型） */
    fun objectTag(ctx: JSContext, rt: JSRuntime, v: JSValue): String {
        if (JS_IsUndefined(v)) return "[object Undefined]"
        if (JS_IsNull(v)) return "[object Null]"
        if (JS_IsBool(v)) return "[object Boolean]"
        if (JS_IsInt(v) || JS_IsShortFloat(v)) return "[object Number]"
        if (JS_IsString(ctx, v)) return "[object String]"
        if (JS_IsPtr(v)) {
            val p = JS_VALUE_TO_PTR(v)
            when (ctx.memory.getMTag(p)) {
                JSMTags.JS_MTAG_FLOAT64 -> return "[object Number]"
                JSMTags.JS_MTAG_STRING -> return "[object String]"
                JSMTags.JS_MTAG_FUNCTION_BYTECODE -> return "[object Function]"
                JSMTags.JS_MTAG_OBJECT -> {
                    return when (ctx.memory.getU8(p + 2)) {
                        JSObjectClassEnum.JS_CLASS_ARRAY.value -> "[object Array]"
                        JSObjectClassEnum.JS_CLASS_CLOSURE.value,
                        JSObjectClassEnum.JS_CLASS_C_FUNCTION.value -> "[object Function]"
                        JSObjectClassEnum.JS_CLASS_ERROR.value -> "[object Error]"
                        JSObjectClassEnum.JS_CLASS_DATE.value -> "[object Date]"
                        JSObjectClassEnum.JS_CLASS_REGEXP.value -> "[object RegExp]"
                        else -> "[object Object]"
                    }
                }
            }
        }
        return "[object Object]"
    }

    private fun definePropertyImpl(ctx: JSContext, rt: JSRuntime,
                                   obj: JSValue, key: JSValue, desc: JSValue) {
        if (!JS_IsPtr(desc) || ctx.memory.getMTag(JS_VALUE_TO_PTR(desc)) != JSMTags.JS_MTAG_OBJECT) return
        val getter = rt.getProperty(desc, JS_NewString(ctx, "get"))
        val setter = rt.getProperty(desc, JS_NewString(ctx, "set"))
        if (JS_IsFunction(ctx, getter) || JS_IsFunction(ctx, setter)) {
            if (JS_IsFunction(ctx, getter)) rt.defineAccessor(obj, key, getter, true)
            if (JS_IsFunction(ctx, setter)) rt.defineAccessor(obj, key, setter, false)
            return
        }
        val valueKey = JS_NewString(ctx, "value")
        if (hasOwn(ctx, rt, desc, valueKey)) {
            rt.defineProperty(obj, key, rt.getProperty(desc, valueKey))
        } else {
            /* 空描述符：属性已存在则保持原值，否则创建 undefined 属性 */
            if (!hasOwn(ctx, rt, obj, key)) {
                rt.defineProperty(obj, key, JS_UNDEFINED)
            }
        }
    }

    private fun hasOwn(ctx: JSContext, rt: JSRuntime, obj: JSValue, key: JSValue): Boolean {
        if (!JS_IsPtr(obj)) return false
        val p = JS_VALUE_TO_PTR(obj)
        if (ctx.memory.getMTag(p) != JSMTags.JS_MTAG_OBJECT) return false
        /* 数组的索引与 length 也是自有属性 */
        if (ctx.memory.getU8(p + 2) == JSObjectClassEnum.JS_CLASS_ARRAY.value) {
            if (JS_IsString(ctx, key) && jsGetString(ctx, key) == "length") return true
            if (JS_IsInt(key)) {
                val i = JS_VALUE_GET_INT(key)
                if (i >= 0 && i < ctx.memory.getI32(p + 32)) return true
            }
        }
        val props = ctx.memory.getJSValue(p + 16)
        if (props == JS_NULL || props == ctx.emptyProps) return false
        val pp = JS_VALUE_TO_PTR(props)
        val hashMask = JS_VALUE_GET_INT(ctx.memory.getJSValue(pp + 16))
        val h = rt.hashProp(key, hashMask)
        var idx = JS_VALUE_GET_INT(ctx.memory.getJSValue(pp + 24 + h * 8)) shr 1
        while (idx != 0) {
            val propPtr = pp + 8 + idx * 8
            val k = ctx.memory.getJSValue(propPtr)
            if (k == key || rt.jsValueEquals(k, key)) return true
            idx = JS_VALUE_GET_INT(ctx.memory.getJSValue(propPtr + 16)) shr 1
        }
        return false
    }

    /* ---------------- Array ---------------- */

    private fun arrayLen(ctx: JSContext, arr: JSValue): Int {
        if (!JS_IsPtr(arr)) return 0
        val p = JS_VALUE_TO_PTR(arr)
        if (ctx.memory.getMTag(p) != JSMTags.JS_MTAG_OBJECT) return 0
        return ctx.memory.getI32(p + 32)
    }

    private fun setArrayLen(ctx: JSContext, arr: JSValue, len: Int) {
        if (!JS_IsPtr(arr)) return
        val p = JS_VALUE_TO_PTR(arr)
        if (ctx.memory.getMTag(p) != JSMTags.JS_MTAG_OBJECT) return
        ctx.memory.putI32(p + 32, len)
        /* 确保元素存储存在 */
        var tab = ctx.memory.getJSValue(p + 24)
        if (tab == JS_NULL && len > 0) {
            tab = runtimeAllocArray(ctx, len)
            if (!JS_IsNull(tab)) ctx.memory.putJSValue(p + 24, tab)
        } else if (tab != JS_NULL) {
            val tabPtr = JS_VALUE_TO_PTR(tab)
            val cap = ctx.memory.getI32(tabPtr + 4)
            if (cap < len) {
                val nt = runtimeResizeArray(ctx, tab, len)
                if (!JS_IsNull(nt)) ctx.memory.putJSValue(p + 24, nt)
            }
        }
    }

    private fun runtimeAllocArray(ctx: JSContext, n: Int): JSValue {
        val ptr = ctx.malloc(8 + n * 8, JSMTags.JS_MTAG_VALUE_ARRAY)
        if (ptr == 0) return JS_NULL
        ctx.memory.putI32(ptr + 4, n)
        for (i in 0 until n) ctx.memory.putJSValue(ptr + 8 + i * 8, JS_UNDEFINED)
        return JS_VALUE_FROM_PTR(ptr)
    }

    private fun runtimeResizeArray(ctx: JSContext, val1: JSValue, n: Int): JSValue {
        ctx.gcKeepAlive.add(val1)
        try {
            val oldPtr = JS_VALUE_TO_PTR(ctx.gcKeepAlive[ctx.gcKeepAlive.size - 1])
            val oldN = ctx.memory.getI32(oldPtr + 4)
            val ptr = ctx.malloc(8 + n * 8, JSMTags.JS_MTAG_VALUE_ARRAY)
            if (ptr == 0) return JS_NULL
            ctx.memory.putI32(ptr + 4, n)
            val src = JS_VALUE_TO_PTR(ctx.gcKeepAlive[ctx.gcKeepAlive.size - 1])
            for (i in 0 until n) {
                ctx.memory.putJSValue(ptr + 8 + i * 8,
                    if (i < oldN) ctx.memory.getJSValue(src + 8 + i * 8) else JS_UNDEFINED)
            }
            return JS_VALUE_FROM_PTR(ptr)
        } finally {
            ctx.gcKeepAlive.removeAt(ctx.gcKeepAlive.size - 1)
        }
    }

    private fun initArray(ctx: JSContext, rt: JSRuntime) {
        val proto = ctx.classProto[JSObjectClassEnum.JS_CLASS_ARRAY.value]
        val arrayCtor = native(ctx, 1) { c, _, args ->
            val n = if (args.size == 1 && JS_IsNumber(c, args[0])) {
                JS_ToInt32(c, args[0]).coerceAtLeast(0)
            } else args.size
            val arr = JS_NewArray(c, n)
            if (!JS_IsException(arr) && args.size != 1) {
                for (i in args.indices) rt.setElement(arr, JS_NewShortInt(i), args[i])
                c.memory.putI32(JS_VALUE_TO_PTR(arr) + 32, args.size)
            }
            arr
        }
        rt.setProperty(arrayCtor, JS_NewString(ctx, "prototype"), proto)
        rt.setProperty(proto, JS_NewString(ctx, "constructor"), arrayCtor)
        defGlobal(ctx, rt, "Array", arrayCtor)
        defMethod(ctx, rt, arrayCtor, "isArray", 1) { c, _, args ->
            val v = arg(args, 0)
            JS_NewBool(JS_IsPtr(v) &&
                c.memory.getMTag(JS_VALUE_TO_PTR(v)) == JSMTags.JS_MTAG_OBJECT &&
                c.memory.getU8(JS_VALUE_TO_PTR(v) + 2) == JSObjectClassEnum.JS_CLASS_ARRAY.value)
        }

        defMethod(ctx, rt, proto, "push", 1) { c, thisVal, args ->
            var len = arrayLen(c, thisVal)
            for (a in args) {
                setArrayLen(c, thisVal, len + 1)
                rt.setElement(thisVal, JS_NewShortInt(len), a)
                len++
            }
            setArrayLen(c, thisVal, len)
            JS_NewShortInt(len)
        }
        defMethod(ctx, rt, proto, "pop", 0) { c, thisVal, _ ->
            val len = arrayLen(c, thisVal)
            if (len == 0) {
                JS_UNDEFINED
            } else {
                val v = rt.getElement(thisVal, JS_NewShortInt(len - 1))
                rt.setElement(thisVal, JS_NewShortInt(len - 1), JS_UNDEFINED)
                c.memory.putI32(JS_VALUE_TO_PTR(thisVal) + 32, len - 1)
                v
            }
        }
        defMethod(ctx, rt, proto, "shift", 0) { c, thisVal, _ ->
            val len = arrayLen(c, thisVal)
            if (len == 0) {
                JS_UNDEFINED
            } else {
                val v = rt.getElement(thisVal, JS_NewShortInt(0))
                for (i in 1 until len) {
                    rt.setElement(thisVal, JS_NewShortInt(i - 1), rt.getElement(thisVal, JS_NewShortInt(i)))
                }
                rt.setElement(thisVal, JS_NewShortInt(len - 1), JS_UNDEFINED)
                c.memory.putI32(JS_VALUE_TO_PTR(thisVal) + 32, len - 1)
                v
            }
        }
        defMethod(ctx, rt, proto, "unshift", 1) { c, thisVal, args ->
            val len = arrayLen(c, thisVal)
            val n = args.size
            setArrayLen(c, thisVal, len + n)
            for (i in len - 1 downTo 0) {
                rt.setElement(thisVal, JS_NewShortInt(i + n), rt.getElement(thisVal, JS_NewShortInt(i)))
            }
            for (i in 0 until n) rt.setElement(thisVal, JS_NewShortInt(i), args[i])
            c.memory.putI32(JS_VALUE_TO_PTR(thisVal) + 32, len + n)
            JS_NewShortInt(len + n)
        }
        defMethod(ctx, rt, proto, "join", 1) { c, thisVal, args ->
            val sep = if (args.isEmpty() || JS_IsUndefined(args[0])) "," else rt.toStringValue(args[0])
            val len = arrayLen(c, thisVal)
            val sb = StringBuilder()
            for (i in 0 until len) {
                if (i > 0) sb.append(sep)
                val v = rt.getElement(thisVal, JS_NewShortInt(i))
                if (!JS_IsUndefined(v) && !JS_IsNull(v)) sb.append(rt.toStringValue(v))
            }
            JS_NewString(c, sb.toString())
        }
        defMethod(ctx, rt, proto, "toString", 0) { c, thisVal, _ ->
            val len = arrayLen(c, thisVal)
            val sb = StringBuilder()
            for (i in 0 until len) {
                if (i > 0) sb.append(',')
                val v = rt.getElement(thisVal, JS_NewShortInt(i))
                if (!JS_IsUndefined(v) && !JS_IsNull(v)) sb.append(rt.toStringValue(v))
            }
            JS_NewString(c, sb.toString())
        }
        defMethod(ctx, rt, proto, "indexOf", 1) { c, thisVal, args ->
            val target = arg(args, 0)
            val start = if (args.size > 1) toInt(c, rt, args[1]).coerceAtLeast(0) else 0
            var res = -1
            val len = arrayLen(c, thisVal)
            for (i in start until len) {
                if (strictEq(c, rt, rt.getElement(thisVal, JS_NewShortInt(i)), target)) {
                    res = i; break
                }
            }
            JS_NewShortInt(res)
        }
        defMethod(ctx, rt, proto, "lastIndexOf", 1) { c, thisVal, args ->
            val target = arg(args, 0)
            val len = arrayLen(c, thisVal)
            var start = if (args.size > 1) toInt(c, rt, args[1]) else len - 1
            if (start >= len) start = len - 1
            var res = -1
            for (i in start downTo 0) {
                if (strictEq(c, rt, rt.getElement(thisVal, JS_NewShortInt(i)), target)) {
                    res = i; break
                }
            }
            JS_NewShortInt(res)
        }
        defMethod(ctx, rt, proto, "slice", 2) { c, thisVal, args ->
            val len = arrayLen(c, thisVal)
            var s = if (args.isNotEmpty()) toInt(c, rt, args[0]) else 0
            var e = if (args.size > 1 && !JS_IsUndefined(args[1])) toInt(c, rt, args[1]) else len
            if (s < 0) s = (len + s).coerceAtLeast(0)
            if (e < 0) e = (len + e).coerceAtLeast(0)
            if (s > len) s = len
            if (e > len) e = len
            val n = (e - s).coerceAtLeast(0)
            val out = JS_NewArray(c, n)
            for (i in 0 until n) {
                rt.setElement(out, JS_NewShortInt(i), rt.getElement(thisVal, JS_NewShortInt(s + i)))
            }
            c.memory.putI32(JS_VALUE_TO_PTR(out) + 32, n)
            out
        }
        defMethod(ctx, rt, proto, "concat", 1) { c, thisVal, args ->
            val out = JS_NewArray(c, 0)
            var n = 0
            fun append(v: JSValue) {
                if (JS_IsPtr(v) && c.memory.getMTag(JS_VALUE_TO_PTR(v)) == JSMTags.JS_MTAG_OBJECT &&
                    c.memory.getU8(JS_VALUE_TO_PTR(v) + 2) == JSObjectClassEnum.JS_CLASS_ARRAY.value) {
                    val l = arrayLen(c, v)
                    for (i in 0 until l) {
                        rt.setElement(out, JS_NewShortInt(n++), rt.getElement(v, JS_NewShortInt(i)))
                    }
                } else {
                    rt.setElement(out, JS_NewShortInt(n++), v)
                }
            }
            append(thisVal)
            for (a in args) append(a)
            c.memory.putI32(JS_VALUE_TO_PTR(out) + 32, n)
            out
        }
        defMethod(ctx, rt, proto, "reverse", 0) { c, thisVal, _ ->
            val len = arrayLen(c, thisVal)
            for (i in 0 until len / 2) {
                val a = rt.getElement(thisVal, JS_NewShortInt(i))
                val b = rt.getElement(thisVal, JS_NewShortInt(len - 1 - i))
                rt.setElement(thisVal, JS_NewShortInt(i), b)
                rt.setElement(thisVal, JS_NewShortInt(len - 1 - i), a)
            }
            thisVal
        }
        defMethod(ctx, rt, proto, "sort", 1) { c, thisVal, args ->
            val cmp = arg(args, 0)
            val len = arrayLen(c, thisVal)
            val items = ArrayList<JSValue>(len)
            for (i in 0 until len) items.add(rt.getElement(thisVal, JS_NewShortInt(i)))
            val list = items.toMutableList()
            val comparator = java.util.Comparator<JSValue> { a, b ->
                if (JS_IsFunction(c, cmp)) {
                    val r = rt.callFunction(cmp, listOf(a, b), JS_UNDEFINED)
                    toNum(c, rt, r).toInt()
                } else {
                    rt.toStringValue(a).compareTo(rt.toStringValue(b))
                }
            }
            list.sortWith(comparator)
            for (i in 0 until len) rt.setElement(thisVal, JS_NewShortInt(i), list[i])
            thisVal
        }
    }

    private fun strictEq(ctx: JSContext, rt: JSRuntime, a: JSValue, b: JSValue): Boolean = rt.strictEquals(a, b)

    /* ---------------- String ---------------- */

    private fun initString(ctx: JSContext, rt: JSRuntime) {
        val proto = ctx.classProto[JSObjectClassEnum.JS_CLASS_STRING.value]
        val stringCtor = native(ctx, 1) { c, _, args ->
            if (args.isEmpty()) JS_NewString(c, "")
            else JS_NewString(c, rt.toStringValue(args[0]))
        }
        rt.setProperty(stringCtor, JS_NewString(ctx, "prototype"), proto)
        rt.setProperty(proto, JS_NewString(ctx, "constructor"), stringCtor)
        defGlobal(ctx, rt, "String", stringCtor)
        defMethod(ctx, rt, stringCtor, "fromCharCode", 1) { c, _, args ->
            val sb = StringBuilder()
            for (a in args) sb.append(toInt(c, rt, a).toChar())
            JS_NewString(c, sb.toString())
        }

        defMethod(ctx, rt, proto, "charAt", 1) { c, thisVal, args ->
            val s = rt.toStringValue(thisVal)
            val i = if (args.isEmpty()) 0 else toInt(c, rt, args[0])
            JS_NewString(c, if (i in s.indices) s[i].toString() else "")
        }
        defMethod(ctx, rt, proto, "charCodeAt", 1) { c, thisVal, args ->
            val s = rt.toStringValue(thisVal)
            val i = if (args.isEmpty()) 0 else toInt(c, rt, args[0])
            if (i in s.indices) JS_NewShortInt(s[i].code) else JS_NewFloat64(c, Double.NaN)
        }
        defMethod(ctx, rt, proto, "indexOf", 1) { c, thisVal, args ->
            val s = rt.toStringValue(thisVal)
            val sub = rt.toStringValue(arg(args, 0))
            val from = if (args.size > 1) toInt(c, rt, args[1]) else 0
            JS_NewShortInt(s.indexOf(sub, from.coerceAtLeast(0)))
        }
        defMethod(ctx, rt, proto, "lastIndexOf", 1) { c, thisVal, args ->
            val s = rt.toStringValue(thisVal)
            val sub = rt.toStringValue(arg(args, 0))
            JS_NewShortInt(s.lastIndexOf(sub))
        }
        defMethod(ctx, rt, proto, "substring", 2) { c, thisVal, args ->
            val s = rt.toStringValue(thisVal)
            var a = if (args.isNotEmpty()) toInt(c, rt, args[0]).coerceIn(0, s.length) else 0
            var b = if (args.size > 1 && !JS_IsUndefined(args[1])) toInt(c, rt, args[1]).coerceIn(0, s.length) else s.length
            if (a > b) { val t = a; a = b; b = t }
            JS_NewString(c, s.substring(a, b))
        }
        defMethod(ctx, rt, proto, "substr", 2) { c, thisVal, args ->
            val s = rt.toStringValue(thisVal)
            var a = if (args.isNotEmpty()) toInt(c, rt, args[0]) else 0
            if (a < 0) a = (s.length + a).coerceAtLeast(0)
            var n = if (args.size > 1 && !JS_IsUndefined(args[1])) toInt(c, rt, args[1]) else s.length
            if (n < 0) n = 0
            val e = (a + n).coerceAtMost(s.length)
            val st = a.coerceAtMost(s.length)
            JS_NewString(c, s.substring(st, e))
        }
        defMethod(ctx, rt, proto, "slice", 2) { c, thisVal, args ->
            val s = rt.toStringValue(thisVal)
            var a = if (args.isNotEmpty()) toInt(c, rt, args[0]) else 0
            var b = if (args.size > 1 && !JS_IsUndefined(args[1])) toInt(c, rt, args[1]) else s.length
            if (a < 0) a = (s.length + a).coerceAtLeast(0)
            if (b < 0) b = (s.length + b).coerceAtLeast(0)
            a = a.coerceIn(0, s.length); b = b.coerceIn(0, s.length)
            if (a > b) JS_NewString(c, "") else JS_NewString(c, s.substring(a, b))
        }
        defMethod(ctx, rt, proto, "concat", 1) { c, thisVal, args ->
            val sb = StringBuilder(rt.toStringValue(thisVal))
            for (a in args) sb.append(rt.toStringValue(a))
            JS_NewString(c, sb.toString())
        }
        defMethod(ctx, rt, proto, "split", 2) { c, thisVal, args ->
            val s = rt.toStringValue(thisVal)
            val sep = if (args.isEmpty() || JS_IsUndefined(args[0])) null else rt.toStringValue(args[0])
            val parts: List<String> = if (sep == null) {
                if (s.isEmpty()) emptyList() else s.map { it.toString() }
            } else if (sep.isEmpty()) {
                s.map { it.toString() }
            } else {
                s.split(sep)
            }
            val out = JS_NewArray(c, parts.size)
            for (i in parts.indices) rt.setElement(out, JS_NewShortInt(i), JS_NewString(c, parts[i]))
            c.memory.putI32(JS_VALUE_TO_PTR(out) + 32, parts.size)
            out
        }
        defMethod(ctx, rt, proto, "toLowerCase", 0) { c, thisVal, _ ->
            JS_NewString(c, rt.toStringValue(thisVal).lowercase())
        }
        defMethod(ctx, rt, proto, "toUpperCase", 0) { c, thisVal, _ ->
            JS_NewString(c, rt.toStringValue(thisVal).uppercase())
        }
        defMethod(ctx, rt, proto, "trim", 0) { c, thisVal, _ ->
            JS_NewString(c, rt.toStringValue(thisVal).trim())
        }
        defMethod(ctx, rt, proto, "repeat", 1) { c, thisVal, args ->
            val n = toInt(c, rt, arg(args, 0))
            if (n < 0) JS_ThrowRangeError(c, "invalid count value")
            else JS_NewString(c, rt.toStringValue(thisVal).repeat(n))
        }
        defMethod(ctx, rt, proto, "replace", 2) { c, thisVal, args ->
            val s = rt.toStringValue(thisVal)
            if (isRegExp(c, arg(args, 0))) {
                regexpReplace(c, rt, arg(args, 0), s, arg(args, 1))
            } else {
                val pat = rt.toStringValue(arg(args, 0))
                val rep = arg(args, 1)
                val idx = s.indexOf(pat)
                if (idx < 0) {
                    JS_NewString(c, s)
                } else {
                    val repl = if (JS_IsFunction(c, rep)) {
                        rt.toStringValue(rt.callFunction(rep, listOf(JS_NewString(c, pat)), JS_UNDEFINED))
                    } else rt.toStringValue(rep)
                    JS_NewString(c, s.substring(0, idx) + repl + s.substring(idx + pat.length))
                }
            }
        }
        defMethod(ctx, rt, proto, "match", 1) { c, thisVal, args ->
            val s = rt.toStringValue(thisVal)
            if (isRegExp(c, arg(args, 0))) {
                regexpMatch(c, rt, arg(args, 0), s)
            } else {
                val pat = rt.toStringValue(arg(args, 0))
                val re = newRegExp(c, pat, "")
                regexpMatch(c, rt, re, s)
            }
        }
        defMethod(ctx, rt, proto, "search", 1) { c, thisVal, args ->
            val s = rt.toStringValue(thisVal)
            val re = arg(args, 0)
            val pat = if (isRegExp(c, re)) JS_GetOpaque(c, re) as? java.util.regex.Pattern
                      else compileRegexp(rt.toStringValue(re), "")
            if (pat == null) JS_NewShortInt(-1)
            else {
                val m = pat.matcher(s)
                JS_NewShortInt(if (m.find()) m.start() else -1)
            }
        }
        defMethod(ctx, rt, proto, "toString", 0) { c, thisVal, _ ->
            JS_NewString(c, rt.toStringValue(thisVal))
        }
        defMethod(ctx, rt, proto, "valueOf", 0) { c, thisVal, _ ->
            JS_NewString(c, rt.toStringValue(thisVal))
        }
    }

    /* ---------------- Number ---------------- */

    private fun initNumber(ctx: JSContext, rt: JSRuntime) {
        val proto = ctx.classProto[JSObjectClassEnum.JS_CLASS_NUMBER.value]
        val numberCtor = native(ctx, 1) { c, _, args ->
            if (args.isEmpty()) JS_NewShortInt(0) else JS_NewFloat64(c, toNum(c, rt, args[0]))
        }
        rt.setProperty(numberCtor, JS_NewString(ctx, "prototype"), proto)
        rt.setProperty(proto, JS_NewString(ctx, "constructor"), numberCtor)
        defValue(ctx, rt, numberCtor, "MAX_VALUE", JS_NewFloat64(ctx, Double.MAX_VALUE))
        defValue(ctx, rt, numberCtor, "MIN_VALUE", JS_NewFloat64(ctx, Double.MIN_VALUE))
        defValue(ctx, rt, numberCtor, "NaN", JS_NewFloat64(ctx, Double.NaN))
        defValue(ctx, rt, numberCtor, "POSITIVE_INFINITY", JS_NewFloat64(ctx, Double.POSITIVE_INFINITY))
        defValue(ctx, rt, numberCtor, "NEGATIVE_INFINITY", JS_NewFloat64(ctx, Double.NEGATIVE_INFINITY))
        defGlobal(ctx, rt, "Number", numberCtor)

        defMethod(ctx, rt, proto, "toString", 1) { c, thisVal, args ->
            val d = toNum(c, rt, thisVal)
            val radix = if (args.isNotEmpty()) toInt(c, rt, args[0]) else 10
            if (radix == 10) JS_NewString(c, jsNumberToString(d))
            else JS_NewString(c, java.lang.Long.toString(d.toLong(), radix))
        }
        defMethod(ctx, rt, proto, "toFixed", 1) { c, thisVal, args ->
            val d = toNum(c, rt, thisVal)
            val n = if (args.isEmpty()) 0 else toInt(c, rt, args[0])
            JS_NewString(c, String.format(java.util.Locale.ROOT, "%.${n}f", d))
        }
        defMethod(ctx, rt, proto, "toExponential", 1) { c, thisVal, args ->
            val d = toNum(c, rt, thisVal)
            val n = if (args.isEmpty() || JS_IsUndefined(args[0])) 6 else toInt(c, rt, args[0])
            val s = String.format(java.util.Locale.ROOT, "%.${n}e", d)
            JS_NewString(c, s.replace("e+", "e+").replace("e-0", "e-"))
        }
        defMethod(ctx, rt, proto, "valueOf", 0) { c, thisVal, _ ->
            JS_NewFloat64(c, toNum(c, rt, thisVal))
        }
    }

    /* ---------------- Math ---------------- */

    private fun initMath(ctx: JSContext, rt: JSRuntime) {
        val math = JS_NewObject(ctx)
        defGlobal(ctx, rt, "Math", math)
        defValue(ctx, rt, math, "PI", JS_NewFloat64(ctx, Math.PI))
        defValue(ctx, rt, math, "E", JS_NewFloat64(ctx, Math.E))
        defValue(ctx, rt, math, "LN2", JS_NewFloat64(ctx, Math.log(2.0)))
        defValue(ctx, rt, math, "LN10", JS_NewFloat64(ctx, Math.log(10.0)))
        defValue(ctx, rt, math, "SQRT2", JS_NewFloat64(ctx, Math.sqrt(2.0)))

        fun unary(name: String, f: (Double) -> Double) {
            defMethod(ctx, rt, math, name, 1) { c, _, args ->
                JS_NewFloat64(c, f(toNum(c, rt, arg(args, 0))))
            }
        }
        unary("abs") { Math.abs(it) }
        unary("floor") { Math.floor(it) }
        unary("ceil") { Math.ceil(it) }
        unary("sqrt") { Math.sqrt(it) }
        unary("exp") { Math.exp(it) }
        unary("log") { Math.log(it) }
        unary("sin") { Math.sin(it) }
        unary("cos") { Math.cos(it) }
        unary("tan") { Math.tan(it) }
        unary("atan") { Math.atan(it) }
        defMethod(ctx, rt, math, "round", 1) { c, _, args ->
            JS_NewFloat64(c, Math.floor(toNum(c, rt, arg(args, 0)) + 0.5))
        }
        defMethod(ctx, rt, math, "pow", 2) { c, _, args ->
            JS_NewFloat64(c, Math.pow(toNum(c, rt, arg(args, 0)), toNum(c, rt, arg(args, 1))))
        }
        defMethod(ctx, rt, math, "atan2", 2) { c, _, args ->
            JS_NewFloat64(c, Math.atan2(toNum(c, rt, arg(args, 0)), toNum(c, rt, arg(args, 1))))
        }
        defMethod(ctx, rt, math, "min", 2) { c, _, args ->
            if (args.isEmpty()) JS_NewFloat64(c, Double.POSITIVE_INFINITY)
            else JS_NewFloat64(c, args.minOf { toNum(c, rt, it) })
        }
        defMethod(ctx, rt, math, "max", 2) { c, _, args ->
            if (args.isEmpty()) JS_NewFloat64(c, Double.NEGATIVE_INFINITY)
            else JS_NewFloat64(c, args.maxOf { toNum(c, rt, it) })
        }
        defMethod(ctx, rt, math, "random", 0) { c, _, _ ->
            JS_NewFloat64(c, Math.random())
        }
    }

    /* ---------------- RegExp ---------------- */

    fun isRegExp(ctx: JSContext, v: JSValue): Boolean {
        if (!JS_IsPtr(v)) return false
        val p = JS_VALUE_TO_PTR(v)
        return ctx.memory.getMTag(p) == JSMTags.JS_MTAG_OBJECT &&
               ctx.memory.getU8(p + 2) == JSObjectClassEnum.JS_CLASS_REGEXP.value
    }

    /* JS 正则 → Java 正则：处理身份转义（Annex B）与控制字符转义 */
    private fun sanitizePattern(p: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < p.length) {
            val c = p[i]
            if (c != '\\' || i + 1 >= p.length) {
                sb.append(c); i++; continue
            }
            val n = p[i + 1]
            when {
                n == 'c' && i + 2 < p.length -> {
                    sb.append("\\x%02x".format(java.util.Locale.ROOT, p[i + 2].code % 32))
                    i += 3
                }
                n in "dDsSwWbBfnrtv0xupPkK".toSet() ||
                    n.isDigit() -> {
                    sb.append('\\').append(n); i += 2
                }
                n == '/' -> { sb.append('/'); i += 2 }
                else -> { sb.append(n); i += 2 }   /* 身份转义：去掉反斜杠 */
            }
        }
        return sb.toString()
    }

    private fun compileRegexp(pattern: String, flags: String): java.util.regex.Pattern? {
        var f = 0
        if (flags.contains('i')) f = f or java.util.regex.Pattern.CASE_INSENSITIVE or
                java.util.regex.Pattern.UNICODE_CASE
        if (flags.contains('m')) f = f or java.util.regex.Pattern.MULTILINE
        if (flags.contains('s')) f = f or java.util.regex.Pattern.DOTALL
        if (flags.contains('u')) f = f or java.util.regex.Pattern.UNICODE_CASE
        return try {
            java.util.regex.Pattern.compile(sanitizePattern(pattern), f)
        } catch (e: Exception) {
            null
        }
    }

    fun newRegExp(ctx: JSContext, pattern: String, flags: String): JSValue {
        val rt = JSRuntime(ctx)
        val obj = JS_NewObjectProtoClass(ctx, ctx.classProto[JSObjectClassEnum.JS_CLASS_REGEXP.value],
                                          JSObjectClassEnum.JS_CLASS_REGEXP.value, 0)
        if (JS_IsException(obj)) return obj
        rt.setProperty(obj, JS_NewString(ctx, "source"), JS_NewString(ctx, pattern))
        rt.setProperty(obj, JS_NewString(ctx, "flags"), JS_NewString(ctx, flags))
        rt.setProperty(obj, JS_NewString(ctx, "global"), JS_NewBool(flags.contains('g')))
        rt.setProperty(obj, JS_NewString(ctx, "lastIndex"), JS_NewShortInt(0))
        JS_SetOpaque(ctx, obj, compileRegexp(pattern, flags))
        return obj
    }

    private fun reFlags(ctx: JSContext, rt: JSRuntime, re: JSValue): String {
        val v = rt.getProperty(re, JS_NewString(ctx, "flags"))
        return if (JS_IsString(ctx, v)) jsGetString(ctx, v) else ""
    }

    fun regexpExec(ctx: JSContext, rt: JSRuntime, re: JSValue, str: String): JSValue {
        val pat = JS_GetOpaque(ctx, re) as? java.util.regex.Pattern
        if (pat == null) return JS_NULL
        val global = reFlags(ctx, rt, re).contains('g')
        val m = pat.matcher(str)
        var start = 0
        if (global) {
            val li = rt.getProperty(re, JS_NewString(ctx, "lastIndex"))
            start = if (JS_IsInt(li)) JS_VALUE_GET_INT(li) else 0
            if (start < 0 || start > str.length) {
                rt.setProperty(re, JS_NewString(ctx, "lastIndex"), JS_NewShortInt(0))
                return JS_NULL
            }
        }
        val found = if (global) m.find(start) else m.find()
        if (!found) {
            if (global) rt.setProperty(re, JS_NewString(ctx, "lastIndex"), JS_NewShortInt(0))
            return JS_NULL
        }
        val n = m.groupCount() + 1
        val arr = JS_NewArray(ctx, n)
        if (JS_IsException(arr)) return JS_NULL
        for (g in 0 until n) {
            val gv = m.group(g)
            rt.setElement(arr, JS_NewShortInt(g),
                if (gv == null) JS_UNDEFINED else JS_NewString(ctx, gv))
        }
        ctx.memory.putI32(JS_VALUE_TO_PTR(arr) + 32, n)
        rt.setProperty(arr, JS_NewString(ctx, "index"), JS_NewShortInt(m.start()))
        rt.setProperty(arr, JS_NewString(ctx, "input"), JS_NewString(ctx, str))
        if (global) rt.setProperty(re, JS_NewString(ctx, "lastIndex"), JS_NewShortInt(m.end()))
        return arr
    }

    private fun expandReplacement(rep: String, s: String, m: java.util.regex.Matcher): String {
        val sb = StringBuilder()
        var i = 0
        while (i < rep.length) {
            val c = rep[i]
            if (c == '$' && i + 1 < rep.length) {
                when (val n = rep[i + 1]) {
                    '$' -> { sb.append('$'); i += 2 }
                    '&' -> { sb.append(m.group()); i += 2 }
                    '`' -> { sb.append(s, 0, m.start()); i += 2 }
                    '\'' -> { sb.append(s, m.end(), s.length); i += 2 }
                    else -> {
                        if (n.isDigit()) {
                            var j = i + 1
                            var num = 0
                            var cnt = 0
                            while (j < rep.length && cnt < 2 && rep[j].isDigit()) {
                                num = num * 10 + (rep[j] - '0'); j++; cnt++
                            }
                            if (num in 0..m.groupCount()) {
                                sb.append(m.group(num) ?: ""); i = j
                            } else {
                                sb.append(c); i++
                            }
                        } else {
                            sb.append(c); i++
                        }
                    }
                }
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }

    private fun regexpReplace(ctx: JSContext, rt: JSRuntime, re: JSValue, s: String,
                              rep: JSValue): JSValue {
        val pat = JS_GetOpaque(ctx, re) as? java.util.regex.Pattern ?: return JS_NewString(ctx, s)
        val global = reFlags(ctx, rt, re).contains('g')
        val m = pat.matcher(s)
        val sb = StringBuilder()
        var last = 0
        while (m.find()) {
            sb.append(s, last, m.start())
            if (JS_IsFunction(ctx, rep)) {
                val argv = ArrayList<JSValue>()
                for (g in 0..m.groupCount()) {
                    val gv = m.group(g)
                    argv.add(if (gv == null) JS_UNDEFINED else JS_NewString(ctx, gv))
                }
                argv.add(JS_NewShortInt(m.start()))
                argv.add(JS_NewString(ctx, s))
                val r = rt.callFunction(rep, argv, JS_UNDEFINED)
                if (JS_IsException(r)) return r
                sb.append(rt.toStringValue(r))
            } else {
                sb.append(expandReplacement(rt.toStringValue(rep), s, m))
            }
            last = m.end()
            if (!global) break
        }
        sb.append(s, last, s.length)
        return JS_NewString(ctx, sb.toString())
    }

    private fun regexpMatch(ctx: JSContext, rt: JSRuntime, re: JSValue, s: String): JSValue {
        val pat = JS_GetOpaque(ctx, re) as? java.util.regex.Pattern ?: return JS_NULL
        val global = reFlags(ctx, rt, re).contains('g')
        if (!global) return regexpExec(ctx, rt, re, s)
        val m = pat.matcher(s)
        val out = JS_NewArray(ctx, 0)
        var n = 0
        while (m.find()) {
            rt.setElement(out, JS_NewShortInt(n++), JS_NewString(ctx, m.group()))
        }
        if (n == 0) return JS_NULL
        ctx.memory.putI32(JS_VALUE_TO_PTR(out) + 32, n)
        return out
    }

    private fun initRegExp(ctx: JSContext, rt: JSRuntime) {
        val proto = JS_NewObject(ctx)
        ctx.classProto[JSObjectClassEnum.JS_CLASS_REGEXP.value] = proto
        val ctor = native(ctx, 2) { c, _, args ->
            val p = if (args.isEmpty()) "" else rt.toStringValue(args[0])
            val f = if (args.size < 2 || JS_IsUndefined(args[1])) "" else rt.toStringValue(args[1])
            newRegExp(c, p, f)
        }
        rt.setProperty(ctor, JS_NewString(ctx, "prototype"), proto)
        rt.setProperty(proto, JS_NewString(ctx, "constructor"), ctor)
        defGlobal(ctx, rt, "RegExp", ctor)

        defMethod(ctx, rt, proto, "exec", 1) { c, thisVal, args ->
            regexpExec(c, rt, thisVal, rt.toStringValue(arg(args, 0)))
        }
        defMethod(ctx, rt, proto, "test", 1) { c, thisVal, args ->
            val r = regexpExec(c, rt, thisVal, rt.toStringValue(arg(args, 0)))
            JS_NewBool(!JS_IsNull(r))
        }
        defMethod(ctx, rt, proto, "toString", 0) { c, thisVal, _ ->
            val src = rt.getProperty(thisVal, JS_NewString(c, "source"))
            val fl = rt.getProperty(thisVal, JS_NewString(c, "flags"))
            JS_NewString(c, "/" + rt.toStringValue(src) + "/" + rt.toStringValue(fl))
        }
    }

    private fun initJson(ctx: JSContext, rt: JSRuntime) {
        val json = JS_NewObject(ctx)
        defGlobal(ctx, rt, "JSON", json)
        defMethod(ctx, rt, json, "stringify", 3) { c, _, args ->
            val sb = StringBuilder()
            if (jsonStringify(c, rt, arg(args, 0), sb, HashSet())) JS_NewString(c, sb.toString())
            else JS_UNDEFINED
        }
        defMethod(ctx, rt, json, "parse", 2) { c, _, args ->
            val s = rt.toStringValue(arg(args, 0))
            val pos = intArrayOf(0)
            jsonParse(c, rt, s, pos)
        }
    }

    private fun jsonStringify(ctx: JSContext, rt: JSRuntime, v: JSValue,
                              sb: StringBuilder, seen: MutableSet<Int>): Boolean {
        if (JS_IsUndefined(v) || JS_IsNull(v)) {
            sb.append("null"); return true
        }
        if (JS_IsBool(v)) {
            sb.append(if (JS_VALUE_GET_SPECIAL_VALUE(v) != 0) "true" else "false"); return true
        }
        if (JS_IsInt(v) || JS_IsShortFloat(v)) {
            sb.append(jsNumberToString(rt.toNumber(v))); return true
        }
        if (JS_IsPtr(v)) {
            val p = JS_VALUE_TO_PTR(v)
            when (ctx.memory.getMTag(p)) {
                JSMTags.JS_MTAG_FLOAT64 -> {
                    val d = ctx.memory.getFloat64(p + 8)
                    if (d.isNaN() || d.isInfinite()) sb.append("null") else sb.append(jsNumberToString(d))
                    return true
                }
                JSMTags.JS_MTAG_STRING -> {
                    sb.append(jsonQuote(rt.jsGetString(ctx, v))); return true
                }
                JSMTags.JS_MTAG_OBJECT -> {
                    if (!seen.add(p)) return false
                    val classId = ctx.memory.getU8(p + 2)
                    if (classId == JSObjectClassEnum.JS_CLASS_ARRAY.value) {
                        val len = ctx.memory.getI32(p + 32)
                        sb.append('[')
                        for (i in 0 until len) {
                            if (i > 0) sb.append(',')
                            jsonStringify(ctx, rt, rt.getElement(v, JS_NewShortInt(i)), sb, seen)
                        }
                        sb.append(']')
                    } else {
                        sb.append('{')
                        val keys = rt.jsObjectKeys(v)
                        val n = if (JS_IsPtr(keys)) ctx.memory.getI32(JS_VALUE_TO_PTR(keys) + 32) else 0
                        for (i in 0 until n) {
                            if (i > 0) sb.append(',')
                            val k = rt.getElement(keys, JS_NewShortInt(i))
                            sb.append(jsonQuote(rt.toStringValue(k)))
                            sb.append(':')
                            jsonStringify(ctx, rt, rt.getProperty(v, k), sb, seen)
                        }
                        sb.append('}')
                    }
                    seen.remove(p)
                    return true
                }
            }
        }
        sb.append("null")
        return true
    }

    private fun jsonQuote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun jsonParse(ctx: JSContext, rt: JSRuntime, s: String, pos: IntArray): JSValue {
        skipWs(s, pos)
        if (pos[0] >= s.length) return JS_UNDEFINED
        return when (val c = s[pos[0]]) {
            '{' -> {
                val obj = JS_NewObject(ctx)
                pos[0]++
                skipWs(s, pos)
                if (pos[0] < s.length && s[pos[0]] == '}') { pos[0]++; return obj }
                while (pos[0] < s.length) {
                    skipWs(s, pos)
                    val key = jsonParseString(s, pos)
                    skipWs(s, pos)
                    if (pos[0] >= s.length || s[pos[0]] != ':') return JS_UNDEFINED
                    pos[0]++
                    val v = jsonParse(ctx, rt, s, pos)
                    rt.setProperty(obj, JS_NewString(ctx, key), v)
                    skipWs(s, pos)
                    if (pos[0] < s.length && s[pos[0]] == ',') { pos[0]++; continue }
                    if (pos[0] < s.length && s[pos[0]] == '}') pos[0]++
                    break
                }
                obj
            }
            '[' -> {
                val arr = JS_NewArray(ctx, 0)
                pos[0]++
                var n = 0
                skipWs(s, pos)
                if (pos[0] < s.length && s[pos[0]] == ']') { pos[0]++; return arr }
                while (pos[0] < s.length) {
                    val v = jsonParse(ctx, rt, s, pos)
                    rt.setElement(arr, JS_NewShortInt(n++), v)
                    skipWs(s, pos)
                    if (pos[0] < s.length && s[pos[0]] == ',') { pos[0]++; continue }
                    if (pos[0] < s.length && s[pos[0]] == ']') pos[0]++
                    break
                }
                ctx.memory.putI32(JS_VALUE_TO_PTR(arr) + 32, n)
                arr
            }
            '"' -> JS_NewString(ctx, jsonParseString(s, pos))
            't' -> { pos[0] += 4; JS_TRUE }
            'f' -> { pos[0] += 5; JS_FALSE }
            'n' -> { pos[0] += 4; JS_NULL }
            else -> {
                val start = pos[0]
                while (pos[0] < s.length && (s[pos[0]].isDigit() || s[pos[0]] in "+-.eE")) pos[0]++
                JS_NewFloat64(ctx, s.substring(start, pos[0]).toDoubleOrNull() ?: Double.NaN)
            }
        }
    }

    private fun skipWs(s: String, pos: IntArray) {
        while (pos[0] < s.length && s[pos[0]].isWhitespace()) pos[0]++
    }

    private fun jsonParseString(s: String, pos: IntArray): String {
        if (pos[0] >= s.length || s[pos[0]] != '"') return ""
        pos[0]++
        val sb = StringBuilder()
        while (pos[0] < s.length) {
            val c = s[pos[0]++]
            if (c == '"') break
            if (c == '\\' && pos[0] < s.length) {
                when (val e = s[pos[0]++]) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'u' -> {
                        if (pos[0] + 4 <= s.length) {
                            sb.append(s.substring(pos[0], pos[0] + 4).toInt(16).toChar())
                            pos[0] += 4
                        }
                    }
                    else -> sb.append(e)
                }
            } else sb.append(c)
        }
        return sb.toString()
    }
}

/* 数值 → JS 字符串（整数去掉小数点，避免 Kotlin 的 "1.0"） */
fun jsNumberToString(d: Double): String {
    if (d.isNaN()) return "NaN"
    if (d == Double.POSITIVE_INFINITY) return "Infinity"
    if (d == Double.NEGATIVE_INFINITY) return "-Infinity"
    if (d == 0.0) return "0"
    if (d == Math.floor(d) && Math.abs(d) < 1e21) {
        return d.toLong().toString()
    }
    return d.toString()
}