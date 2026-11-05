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

    const val MIN_MEM_SIZE = 512 * 1024

    private fun native(ctx: JSContext, length: Int,
                       fn: (JSContext, JSValue, List<JSValue>) -> JSValue): JSValue {
        val base = ctx.cFunctionTable?.size ?: 0
        val idx = base + ctx.extFunctionTable.size
        ctx.extFunctionTable.add(
            JSCFunctionDef(
                func = { c, thisVal, argc: Int, argv: LongArray ->
                    val base = c.cArgBase
                    val args: List<JSValue> = if (base >= 0) {
                        object : AbstractList<JSValue>() {
                            override val size: Int get() = argv.size
                            override fun get(index: Int): JSValue = c.gcKeepAlive[base + 1 + index]
                        }
                    } else {
                        argv.toList()
                    }
                    fn(c, thisVal, args)
                },
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
            initTypedArrays(ctx, rt)
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
        defMethod(ctx, rt, proto, "bind", 1) { c, thisVal, args ->
            val keep = c.persistentRoots.size
            c.persistentRoots.add(thisVal)
            c.persistentRoots.add(arg(args, 0))
            for (i in 1 until args.size) c.persistentRoots.add(args[i])
            val info = intArrayOf(keep, keep + 1, keep + 2, args.size - 1)
            c.boundFns.add(info)
            val magic = c.boundFns.size - 1
            val baseIdx = c.cFunctionTable?.size ?: 0
            val fnIdx = baseIdx + c.extFunctionTable.size
            c.extFunctionTable.add(JSCFunctionDef(
                funcMagic = { cc, _, _, argv, m ->
                    val bi = cc.boundFns[m]
                    val target = cc.persistentRoots[bi[0]]
                    val thisArg = cc.persistentRoots[bi[1]]
                    val list = ArrayList<JSValue>()
                    for (i in 0 until bi[3]) list.add(cc.persistentRoots[bi[2] + i])
                    list.addAll(argv.toList())
                    rt.callFunction(target, list, thisArg)
                },
                defType = JSCFunctionDefEnum.JS_CFUNC_generic_magic.value,
                argCount = 0,
                magic = magic
            ))
            JS_NewCFunctionParams(c, fnIdx, JS_NULL)
        }
        defMethod(ctx, rt, proto, "toString", 0) { c, _, _ ->
            JS_NewString(c, "function () { [native code] }")
        }
        
        /* Function 构造器：把参数名/函数体拼成源码后走 JS_Eval 编译 */
        val funcCtor = native(ctx, 1) { c, _, args ->
            if (args.isEmpty()) {
                JS_NewString(c, "")
            } else {
                val names = StringBuilder()
                for (i in 0 until args.size - 1) {
                    if (i > 0) names.append(',')
                    names.append(rt.toStringValue(args[i]))
                }
                val body = rt.toStringValue(args[args.size - 1])
                val src = "(function(" + names + "){" + body + "\n})"
                val fn = JS_Eval(c, src, "<Function>", JS_EVAL_RETVAL)
                if (JS_IsException(fn)) JS_EXCEPTION else fn
            }
        }
        rt.setProperty(funcCtor, JS_NewString(ctx, "prototype"), proto)
        rt.setProperty(proto, JS_NewString(ctx, "constructor"), funcCtor)
        defGlobal(ctx, rt, "Function", funcCtor)
        
        /* 全局 eval（仅间接/全局语义） */
        defGlobal(ctx, rt, "eval", native(ctx, 1) { c, _, args ->
            if (args.isEmpty() || !JS_IsString(c, args[0])) {
                if (args.isEmpty()) JS_UNDEFINED else args[0]
            } else {
                JS_Eval(c, jsGetString(c, args[0]), "<eval>", JS_EVAL_RETVAL)
            }
        })
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
        
        /* 宿主脚本参数（命令行测试脚本用；无参数时为空数组） */
        defGlobal(ctx, rt, "scriptArgs", JS_NewArray(ctx, 0))
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
        if (str == "Infinity" || str == "+Infinity") return JS_NewFloat64(ctx, Double.POSITIVE_INFINITY)
        if (str == "-Infinity") return JS_NewFloat64(ctx, Double.NEGATIVE_INFINITY)
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
            /* Error.prototype 本身要作为所有错误原型链的根，不能再包一层 */
            val proto = if (classEnum == JSObjectClassEnum.JS_CLASS_ERROR) errorProto
                        else makeErrorProto(ctx, rt, errorProto)
            ctx.classProto[classEnum.value] = proto
            val ctor = native(ctx, 1) { c, _, args ->
                /* 原型从 classProto 现取：闭包捕获的 JSValue 不会随 GC 搬移更新 */
                val obj = JS_NewObjectProtoClass(c, c.classProto[classEnum.value],
                                                 JSObjectClassEnum.JS_CLASS_ERROR.value, 2)
                if (JS_IsException(obj)) {
                    JS_EXCEPTION
                } else {
                    val msg = if (args.isEmpty() || JS_IsUndefined(args[0])) JS_NewString(c, "")
                              else JS_NewString(c, rt.toStringValue(args[0]))
                    if (JS_IsException(msg)) {
                        JS_EXCEPTION
                    } else {
                        val keep = c.gcKeepAlive.size
                        c.gcKeepAlive.add(obj)
                        c.gcKeepAlive.add(msg)
                        c.memory.putJSValue(JS_VALUE_TO_PTR(c.gcKeepAlive[keep]) + 24,
                                            c.gcKeepAlive[keep + 1])
                        /* 构造 Error 时记录回溯（对照 C 的 build_backtrace） */
                        val stackVal = JS_NewString(c, c.buildBacktrace())
                        val eptr = JS_VALUE_TO_PTR(c.gcKeepAlive[keep])
                        c.memory.putJSValue(eptr + 32,
                            if (JS_IsException(stackVal)) JS_NULL else stackVal)
                        c.gcKeepAlive[keep]
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
            if (k == key || rt.jsPropKeyEquals(k, key)) return true
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
                val keep = c.gcKeepAlive.size
                c.gcKeepAlive.add(arr)
                for (i in args.indices) rt.setElement(c.gcKeepAlive[keep], JS_NewShortInt(i), args[i])
                c.memory.putI32(JS_VALUE_TO_PTR(c.gcKeepAlive[keep]) + 32, args.size)
                return@native c.gcKeepAlive[keep]
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
            val keep = c.gcKeepAlive.size
            c.gcKeepAlive.add(thisVal)
            for (i in args.indices) {
                setArrayLen(c, c.gcKeepAlive[keep], len + 1)
                rt.setElement(c.gcKeepAlive[keep], JS_NewShortInt(len), args[i])
                len++
            }
            setArrayLen(c, c.gcKeepAlive[keep], len)
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
            val keep = c.gcKeepAlive.size
            c.gcKeepAlive.add(thisVal)
            for (i in args.indices) c.gcKeepAlive.add(args[i])
            setArrayLen(c, c.gcKeepAlive[keep], len + n)
            for (i in len - 1 downTo 0) {
                rt.setElement(c.gcKeepAlive[keep], JS_NewShortInt(i + n),
                              rt.getElement(c.gcKeepAlive[keep], JS_NewShortInt(i)))
            }
            for (i in 0 until n) rt.setElement(c.gcKeepAlive[keep], JS_NewShortInt(i), c.gcKeepAlive[keep + 1 + i])
            c.memory.putI32(JS_VALUE_TO_PTR(c.gcKeepAlive[keep]) + 32, len + n)
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
            /* 源数组与结果数组都保活：setElement 可能分配并触发 GC 搬移 */
            val srcKeep = c.gcKeepAlive.size
            c.gcKeepAlive.add(thisVal)
            val outKeep = c.gcKeepAlive.size
            c.gcKeepAlive.add(out)
            for (i in 0 until n) {
                rt.setElement(c.gcKeepAlive[outKeep], JS_NewShortInt(i),
                              rt.getElement(c.gcKeepAlive[srcKeep], JS_NewShortInt(s + i)))
            }
            c.memory.putI32(JS_VALUE_TO_PTR(c.gcKeepAlive[outKeep]) + 32, n)
            c.gcKeepAlive[outKeep]
        }
        defMethod(ctx, rt, proto, "concat", 1) { c, thisVal, args ->
            val keep = c.gcKeepAlive.size
            c.gcKeepAlive.add(thisVal)
            val out = JS_NewArray(c, 0)
            val outSlot = c.gcKeepAlive.size
            c.gcKeepAlive.add(out)
            for (i in args.indices) c.gcKeepAlive.add(args[i])
            val thisSlot = keep
            var n = 0
            fun appendFrom(slot: Int) {
                val v0 = c.gcKeepAlive[slot]
                if (JS_IsPtr(v0) && c.memory.getMTag(JS_VALUE_TO_PTR(v0)) == JSMTags.JS_MTAG_OBJECT &&
                    c.memory.getU8(JS_VALUE_TO_PTR(v0) + 2) == JSObjectClassEnum.JS_CLASS_ARRAY.value) {
                    val l = arrayLen(c, v0)
                    for (i in 0 until l) {
                        rt.setElement(c.gcKeepAlive[outSlot], JS_NewShortInt(n++),
                                      rt.getElement(c.gcKeepAlive[slot], JS_NewShortInt(i)))
                    }
                } else {
                    rt.setElement(c.gcKeepAlive[outSlot], JS_NewShortInt(n++), c.gcKeepAlive[slot])
                }
            }
            appendFrom(thisSlot)
            for (i in args.indices) appendFrom(thisSlot + 2 + i)
            c.memory.putI32(JS_VALUE_TO_PTR(c.gcKeepAlive[outSlot]) + 32, n)
            c.gcKeepAlive[outSlot]
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
            /* 快速路径：无比较函数且元素均为原始值（数值/字符串/undefined）时，
             * 直接按预先计算的字符串键排序，避免每次比较走 JSValue 槽与 GC 检查 */
            if (!JS_IsFunction(c, cmp) && JS_IsPtr(thisVal)) {
                val arrP = JS_VALUE_TO_PTR(thisVal)
                val dataV = c.memory.getJSValue(arrP + 24)
                val cap = if (dataV != JS_NULL) c.memory.getI32(JS_VALUE_TO_PTR(dataV) + 4) else 0
                val dp = if (dataV != JS_NULL) JS_VALUE_TO_PTR(dataV) else 0
                var allPrimitive = cap >= len
                var failIdx = -1
                if (allPrimitive) {
                    for (i in 0 until len) {
                        val v = c.memory.getJSValue(dp + 8 + i * 8)
                        if (!(JS_IsUndefined(v) || JS_IsInt(v) || JS_IsShortFloat(v) ||
                              (JS_IsPtr(v) && c.memory.getMTag(JS_VALUE_TO_PTR(v)) == JSMTags.JS_MTAG_STRING) ||
                              (JS_IsPtr(v) && c.memory.getMTag(JS_VALUE_TO_PTR(v)) == JSMTags.JS_MTAG_FLOAT64))) {
                            allPrimitive = false; failIdx = i; break
                        }
                    }
                }

                if (allPrimitive) {

                    var m = 0
                    val vals = LongArray(len)
                    val keys = arrayOfNulls<String>(len)
                    for (i in 0 until len) {
                        val v = c.memory.getJSValue(JS_VALUE_TO_PTR(dataV) + 8 + i * 8)
                        if (JS_IsUndefined(v)) continue
                        vals[m] = v
                        keys[m] = rt.toStringValue(v)
                        m++
                    }
                    val order = (0 until m).toMutableList()
                    order.sortWith { ia, ib -> keys[ia]!!.compareTo(keys[ib]!!) }
                    for (i in 0 until m) {
                        c.memory.putJSValue(dp + 8 + i * 8, vals[order[i]])
                    }
                    for (i in m until len) {
                        c.memory.putJSValue(dp + 8 + i * 8, JS_UNDEFINED)
                    }
                    c.memory.putI32(arrP + 32, len)
                    return@defMethod thisVal
                }
            }
            val keep = c.gcKeepAlive.size
            c.gcKeepAlive.add(thisVal)          /* 槽 keep */
            c.gcKeepAlive.add(cmp)              /* 槽 keep+1 */
            val slots = IntArray(len)
            var undefCount = 0
            var m = 0
            for (i in 0 until len) {
                val v = rt.getElement(c.gcKeepAlive[keep], JS_NewShortInt(i))
                if (JS_IsUndefined(v)) {
                    undefCount++
                } else {
                    slots[m] = c.gcKeepAlive.size
                    c.gcKeepAlive.add(v)
                    m++
                }
            }
            val order = (0 until m).toMutableList()
            val comparator = java.util.Comparator<Int> { ia, ib ->
                if (JS_IsFunction(c, c.gcKeepAlive[keep + 1])) {
                    val r = rt.callFunction(c.gcKeepAlive[keep + 1],
                        listOf(c.gcKeepAlive[slots[ia]], c.gcKeepAlive[slots[ib]]), JS_UNDEFINED)
                    toNum(c, rt, r).toInt()
                } else {
                    rt.toStringValue(c.gcKeepAlive[slots[ia]])
                        .compareTo(rt.toStringValue(c.gcKeepAlive[slots[ib]]))
                }
            }
            order.sortWith(comparator)
            for (i in 0 until m) {
                rt.setElement(c.gcKeepAlive[keep], JS_NewShortInt(i), c.gcKeepAlive[slots[order[i]]])
            }
            for (i in m until len) {
                rt.setElement(c.gcKeepAlive[keep], JS_NewShortInt(i), JS_UNDEFINED)
            }
            c.memory.putI32(JS_VALUE_TO_PTR(c.gcKeepAlive[keep]) + 32, len)
            c.gcKeepAlive[keep]
        }
        
        /* 数组迭代类方法：this/回调放入保活列表，回调期间的 GC 搬移后仍可用 */
        fun arrayIterate(name: String, mode: Int) {
            defMethod(ctx, rt, proto, name, 1) { c, thisVal, args ->
                val cb = arg(args, 0)
                if (!JS_IsFunction(c, cb)) {
                    JS_ThrowTypeError(c, "not a function")
                } else {
                    val keep = c.gcKeepAlive.size
                    c.gcKeepAlive.add(thisVal)
                    c.gcKeepAlive.add(cb)
                    try {
                        val len = arrayLen(c, c.gcKeepAlive[keep])
                        val hasOut = mode == 1 || mode == 2
                        val out = if (hasOut) JS_NewArray(c, 0) else JS_NULL
                        val outSlot = c.gcKeepAlive.size
                        if (hasOut) c.gcKeepAlive.add(out)
                        var outN = 0
                        for (i in 0 until len) {
                            val arr = c.gcKeepAlive[keep]
                            val v = rt.getElement(arr, JS_NewShortInt(i))
                            val r = rt.callFunction(c.gcKeepAlive[keep + 1],
                                listOf(v, JS_NewShortInt(i), arr), JS_UNDEFINED)
                            if (JS_IsException(r)) return@defMethod r
                            when (mode) {
                                0 -> {}                       /* forEach */
                                1 -> rt.setElement(c.gcKeepAlive[outSlot], JS_NewShortInt(outN++), r)   /* map */
                                2 -> if (rt.toBoolean(r)) rt.setElement(c.gcKeepAlive[outSlot], JS_NewShortInt(outN++),
                                    rt.getElement(c.gcKeepAlive[keep], JS_NewShortInt(i))) /* filter */
                                3 -> if (rt.toBoolean(r)) return@defMethod JS_TRUE    /* some */
                                4 -> if (!rt.toBoolean(r)) return@defMethod JS_FALSE  /* every */
                            }
                        }
                        when (mode) {
                            1, 2 -> {
                                c.memory.putI32(JS_VALUE_TO_PTR(c.gcKeepAlive[outSlot]) + 32, outN)
                                c.gcKeepAlive[outSlot]
                            }
                            3 -> JS_FALSE
                            4 -> JS_TRUE
                            else -> JS_UNDEFINED
                        }
                    } finally {
                        while (c.gcKeepAlive.size > keep) c.gcKeepAlive.removeAt(c.gcKeepAlive.size - 1)
                    }
                }
            }
        }
        arrayIterate("forEach", 0)
        arrayIterate("map", 1)
        arrayIterate("filter", 2)
        arrayIterate("some", 3)
        arrayIterate("every", 4)
        
        defMethod(ctx, rt, proto, "reduce", 1) { c, thisVal, args ->
            reduceImpl(c, rt, thisVal, args, right = false)
        }
        defMethod(ctx, rt, proto, "reduceRight", 1) { c, thisVal, args ->
            reduceImpl(c, rt, thisVal, args, right = true)
        }
        defMethod(ctx, rt, proto, "splice", 2) { c, thisVal, args ->
            spliceImpl(c, rt, thisVal, args)
        }
    }

    private fun reduceImpl(ctx: JSContext, rt: JSRuntime, thisVal: JSValue,
                           args: List<JSValue>, right: Boolean): JSValue {
        val cb = arg(args, 0)
        if (!JS_IsFunction(ctx, cb)) return JS_ThrowTypeError(ctx, "not a function")
        val keep = ctx.gcKeepAlive.size
        ctx.gcKeepAlive.add(thisVal)
        ctx.gcKeepAlive.add(cb)
        val hasInit = args.size > 1
        if (hasInit) ctx.gcKeepAlive.add(args[1])
        try {
            val len = arrayLen(ctx, ctx.gcKeepAlive[keep])
            var acc: JSValue
            var i: Int
            val end: Int
            val step: Int
            if (right) { i = len - 1; end = -1; step = -1 } else { i = 0; end = len; step = 1 }
            if (hasInit) {
                acc = ctx.gcKeepAlive[keep + 2]
            } else {
                if (len == 0) return JS_ThrowTypeError(ctx, "reduce of empty array with no initial value")
                acc = rt.getElement(ctx.gcKeepAlive[keep], JS_NewShortInt(i))
                i += step
            }
            while (i != end) {
                val arr = ctx.gcKeepAlive[keep]
                val v = rt.getElement(arr, JS_NewShortInt(i))
                val r = rt.callFunction(ctx.gcKeepAlive[keep + 1],
                    listOf(acc, v, JS_NewShortInt(i), arr), JS_UNDEFINED)
                if (JS_IsException(r)) return r
                acc = r
                i += step
            }
            return acc
        } finally {
            while (ctx.gcKeepAlive.size > keep) ctx.gcKeepAlive.removeAt(ctx.gcKeepAlive.size - 1)
        }
    }
    
    private fun spliceImpl(ctx: JSContext, rt: JSRuntime, thisVal: JSValue,
                           args: List<JSValue>): JSValue {
        val keep = ctx.gcKeepAlive.size
        ctx.gcKeepAlive.add(thisVal)
        try {
            val len = arrayLen(ctx, ctx.gcKeepAlive[keep])
            var start = if (args.isNotEmpty()) toInt(ctx, rt, args[0]) else 0
            if (start < 0) start = (len + start).coerceAtLeast(0)
            if (start > len) start = len
            val del = if (args.size > 1) toInt(ctx, rt, args[1]).coerceIn(0, len - start) else len - start
            val items = args.drop(2)
            
            /* 旧元素与插入项统一放入保活槽，避免后续分配触发搬移 */
            val oldIdx = IntArray(len)
            for (i in 0 until len) {
                oldIdx[i] = ctx.gcKeepAlive.size
                ctx.gcKeepAlive.add(rt.getElement(ctx.gcKeepAlive[keep], JS_NewShortInt(i)))
            }
            val newIdx = IntArray(items.size)
            for (i in items.indices) {
                newIdx[i] = ctx.gcKeepAlive.size
                ctx.gcKeepAlive.add(items[i])
            }
            
            /* 被删除的元素组成返回数组 */
            val removed = JS_NewArray(ctx, del)
            val remKeep = ctx.gcKeepAlive.size
            ctx.gcKeepAlive.add(removed)
            for (i in 0 until del) {
                rt.setElement(ctx.gcKeepAlive[remKeep], JS_NewShortInt(i),
                    ctx.gcKeepAlive[oldIdx[start + i]])
            }
            ctx.memory.putI32(JS_VALUE_TO_PTR(ctx.gcKeepAlive[remKeep]) + 32, del)
            
            /* 按 “前缀 + 插入项 + 后缀” 重写数组 */
            val newLen = len - del + items.size
            var w = 0
            for (i in 0 until start) {
                rt.setElement(ctx.gcKeepAlive[keep], JS_NewShortInt(w++), ctx.gcKeepAlive[oldIdx[i]])
            }
            for (i in items.indices) {
                rt.setElement(ctx.gcKeepAlive[keep], JS_NewShortInt(w++), ctx.gcKeepAlive[newIdx[i]])
            }
            for (i in start + del until len) {
                rt.setElement(ctx.gcKeepAlive[keep], JS_NewShortInt(w++), ctx.gcKeepAlive[oldIdx[i]])
            }
            for (i in newLen until len) {
                rt.setElement(ctx.gcKeepAlive[keep], JS_NewShortInt(i), JS_UNDEFINED)
            }
            ctx.memory.putI32(JS_VALUE_TO_PTR(ctx.gcKeepAlive[keep]) + 32, newLen)
            return ctx.gcKeepAlive[remKeep]
        } finally {
            while (ctx.gcKeepAlive.size > keep) ctx.gcKeepAlive.removeAt(ctx.gcKeepAlive.size - 1)
        }
    }
    
    private fun strictEq(ctx: JSContext, rt: JSRuntime, a: JSValue, b: JSValue): Boolean = rt.strictEquals(a, b)

    /* ---------------- String ---------------- */

    /* 字符串模式替换中的 $ 展开（$$、$&、$`、$'） */
    private fun expandStringReplacement(rep: String, matched: String, pos: Int, s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < rep.length) {
            val c = rep[i]
            if (c == '$' && i + 1 < rep.length) {
                when (rep[i + 1]) {
                    '$' -> { sb.append('$'); i += 2 }
                    '&' -> { sb.append(matched); i += 2 }
                    '`' -> { sb.append(s, 0, pos); i += 2 }
                    '\'' -> { sb.append(s, pos + matched.length, s.length); i += 2 }
                    else -> { sb.append(c); i++ }
                }
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }
    
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
        defMethod(ctx, rt, stringCtor, "fromCodePoint", 1) { c, _, args ->
            val sb = StringBuilder()
            for (a in args) {
                val cp = toInt(c, rt, a)
                if (cp in 0..0x10FFFF && !(cp in 0xD800..0xDFFF)) {
                    sb.append(String(Character.toChars(cp)))
                }
            }
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
        defMethod(ctx, rt, proto, "codePointAt", 1) { c, thisVal, args ->
            val s = rt.toStringValue(thisVal)
            val i = if (args.isEmpty()) 0 else toInt(c, rt, args[0])
            if (i in s.indices) JS_NewShortInt(Character.codePointAt(s, i))
            else JS_UNDEFINED
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
            val limit = if (args.size > 1 && !JS_IsUndefined(args[1])) {
                toInt(c, rt, args[1]).coerceAtLeast(0)
            } else Int.MAX_VALUE
            if (limit == 0) return@defMethod JS_NewArray(c, 0)
            /* 正则分隔符：按匹配切分，捕获组按规范插入结果 */
            if (isRegExp(c, arg(args, 0))) {
                val info = JS_GetOpaque(c, arg(args, 0)) as? RegexpInfo
                if (info?.pat == null) return@defMethod JS_NewArray(c, 0)
                val m = info.pat.matcher(s)
                val parts = ArrayList<JSValue>()
                var last = 0
                var lastMatchNonZero = false
                while (m.find()) {
                    val st = m.start(); val en = m.end()
                    if (st == last && en == last) {
                        if (en >= s.length) break
                        continue
                    }
                    parts.add(JS_NewString(c, s.substring(last, st)))
                    for (g in 1..m.groupCount()) {
                        val gv = if (en == st && st == last) null else m.group(g)
                        parts.add(if (gv == null) JS_UNDEFINED else JS_NewString(c, gv))
                    }
                    last = en
                    lastMatchNonZero = en > st
                    if (en == st && en >= s.length) break
                }
                if (last < s.length || lastMatchNonZero) {
                    parts.add(JS_NewString(c, s.substring(last)))
                }
                val n = minOf(parts.size, limit)
                val out = JS_NewArray(c, n)
                val keep = c.gcKeepAlive.size
                c.gcKeepAlive.add(out)
                for (i in 0 until n) rt.setElement(c.gcKeepAlive[keep], JS_NewShortInt(i), parts[i])
                c.memory.putI32(JS_VALUE_TO_PTR(c.gcKeepAlive[keep]) + 32, n)
                return@defMethod c.gcKeepAlive[keep]
            }
            val sep = if (args.isEmpty() || JS_IsUndefined(args[0])) null else rt.toStringValue(args[0])
            var parts: List<String> = if (sep == null) {
                if (s.isEmpty()) emptyList() else listOf(s)
            } else if (sep.isEmpty()) {
                s.map { it.toString() }
            } else {
                s.split(sep)
            }
            if (parts.size > limit) parts = parts.subList(0, limit)
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
        defMethod(ctx, rt, proto, "trimStart", 0) { c, thisVal, _ ->
            JS_NewString(c, rt.toStringValue(thisVal).trimStart())
        }
        defMethod(ctx, rt, proto, "trimEnd", 0) { c, thisVal, _ ->
            JS_NewString(c, rt.toStringValue(thisVal).trimEnd())
        }
        defMethod(ctx, rt, proto, "replaceAll", 2) { c, thisVal, args ->
            val s = rt.toStringValue(thisVal)
            if (isRegExp(c, arg(args, 0))) {
                regexpReplace(c, rt, arg(args, 0), s, arg(args, 1))
            } else {
                val pat = rt.toStringValue(arg(args, 0))
                val rep = arg(args, 1)
                if (pat.isEmpty()) {
                    JS_NewString(c, s)
                } else {
                    val sb = StringBuilder()
                    var idx = 0
                    while (true) {
                        val p = s.indexOf(pat, idx)
                        if (p < 0) { sb.append(s, idx, s.length); break }
                        sb.append(s, idx, p)
                        if (JS_IsFunction(c, rep)) {
                            val r = rt.callFunction(rep, listOf(JS_NewString(c, pat),
                                JS_NewShortInt(p), JS_NewString(c, s)), JS_UNDEFINED)
                            if (JS_IsException(r)) return@defMethod r
                            sb.append(rt.toStringValue(r))
                        } else {
                            sb.append(expandStringReplacement(rt.toStringValue(rep), pat, p, s))
                        }
                        idx = p + pat.length
                    }
                    JS_NewString(c, sb.toString())
                }
            }
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
                        rt.toStringValue(rt.callFunction(rep, listOf(JS_NewString(c, pat),
                            JS_NewShortInt(idx), JS_NewString(c, s)), JS_UNDEFINED))
                    } else expandStringReplacement(rt.toStringValue(rep), pat, idx, s)
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
            val pat = if (isRegExp(c, re)) (JS_GetOpaque(c, re) as? RegexpInfo)?.pat
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
            val digits = if (args.isEmpty() || JS_IsUndefined(args[0])) null
                         else toInt(c, rt, args[0]).coerceIn(0, 100)
            JS_NewString(c, jsToExponential(d, digits))
        }
        defMethod(ctx, rt, proto, "toPrecision", 1) { c, thisVal, args ->
            val d = toNum(c, rt, thisVal)
            if (args.isEmpty() || JS_IsUndefined(args[0])) {
                JS_NewString(c, jsNumberToString(d))
            } else {
                val p = toInt(c, rt, args[0]).coerceIn(1, 100)
                JS_NewString(c, jsToPrecision(d, p))
            }
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
        defMethod(ctx, rt, math, "imul", 2) { c, _, args ->
            val a = rt.toInt32(arg(args, 0))
            val b = rt.toInt32(arg(args, 1))
            JS_NewShortInt(a * b)
        }
        defMethod(ctx, rt, math, "fround", 1) { c, _, args ->
            JS_NewFloat64(c, toNum(c, rt, arg(args, 0)).toFloat().toDouble())
        }
        defMethod(ctx, rt, math, "trunc", 1) { c, _, args ->
            val d = toNum(c, rt, arg(args, 0))
            JS_NewFloat64(c, if (d >= 0) Math.floor(d) else Math.ceil(d))
        }
        defMethod(ctx, rt, math, "sign", 1) { c, _, args ->
            val d = toNum(c, rt, arg(args, 0))
            JS_NewFloat64(c, if (d > 0) 1.0 else if (d < 0) -1.0 else d)
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
        var inClass = false
        while (i < p.length) {
            val c = p[i]
            if (c == '\\' && i + 1 < p.length) {
                val n = p[i + 1]
                when {
                    n == 'c' && i + 2 < p.length && p[i + 2].isLetter() -> {
                        sb.append("\\x%02x".format(java.util.Locale.ROOT, p[i + 2].code % 32))
                        i += 3
                    }
                    n == 'c' -> { sb.append("\\\\"); i += 1 }   /* \c 后非字母：字面反斜杠 */
                    /* Java 字符类中的 \b/\B 非法：JS 字符类里 \b 是退格，\B 是字面 B */
                    n == 'b' && inClass -> { sb.append("\\x08"); i += 2 }
                    n == 'B' && inClass -> { sb.append('B'); i += 2 }
                    n in "dDsSwWbBfnrtv0xupP".toSet() || n.isDigit() -> {
                        sb.append('\\').append(n); i += 2
                    }
                    n == '\\' -> { sb.append("\\\\"); i += 2 }
                    n.isLetter() -> { sb.append(n); i += 2 }   /* 身份转义：去掉反斜杠 */
                    else -> { sb.append('\\').append(n); i += 2 } /* 标点转义保留（Java 有效） */
                }
                continue
            }
            if (c == '[') inClass = true
            else if (c == ']') inClass = false
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    /* JS 正则允许空分支优先（如 (?:|[\w])+），Java 会因此提前停止；
     * 把“以空分支开头”的分组改写为“空分支结尾”，保持贪婪匹配行为 */
    private fun fixEmptyAlternation(p: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < p.length) {
            if (p[i] == '\\' && i + 1 < p.length) {
                sb.append(p[i]).append(p[i + 1]); i += 2; continue
            }
            var prefixLen = -1
            if (p.startsWith("(?:|", i)) prefixLen = 4
            else if (p.startsWith("(|", i)) prefixLen = 2
            if (prefixLen >= 0) {
                var depth = 0
                var j = i
                var close = -1
                while (j < p.length) {
                    val c = p[j]
                    if (c == '\\') { j += 2; continue }
                    if (c == '[') {
                        j++
                        while (j < p.length && p[j] != ']') { if (p[j] == '\\') j++; j++ }
                    } else if (c == '(') depth++
                    else if (c == ')') {
                        depth--
                        if (depth == 0) { close = j; break }
                    }
                    j++
                }
                if (close > i) {
                    val headEnd = i + prefixLen
                    val inner = p.substring(headEnd, close)
                    val head = if (prefixLen == 4) "(?:" else "("
                    sb.append(head).append(inner).append("|)")
                    i = close + 1
                    continue
                }
            }
            sb.append(p[i]); i++
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
        /* u 模式下未匹配的 ']' 是语法错误（Java 会当作字面量接受） */
        if (flags.contains('u') && hasStrayBracket(pattern)) return null
        val src = fixEmptyAlternation(sanitizePattern(pattern))
        fun tryCompile(s: String): java.util.regex.Pattern? = try {
            java.util.regex.Pattern.compile(s, f)
        } catch (e: Exception) {
            null
        } catch (e: StackOverflowError) {
            null
        }
        return tryCompile(src) ?: stripRedundantNesting(src)?.let { tryCompile(it) }
    }

    /* 检查字符类之外的孤立 ']'（仅 u 模式报错） */
    private fun hasStrayBracket(p: String): Boolean {
        var i = 0
        var inClass = false
        while (i < p.length) {
            val c = p[i]
            if (c == '\\') { i += 2; continue }
            if (!inClass && c == '[') { inClass = true }
            else if (inClass && c == ']') { inClass = false }
            else if (!inClass && c == ']') return true
            i++
        }
        return false
    }
    
    /* 剥离形如 (?: (?: ... ) ) 的冗余嵌套外壳（Java 深层嵌套会栈溢出） */
    private fun stripRedundantNesting(p: String): String? {
        var k = 0
        while (p.startsWith("(?:", k * 3)) k++
        if (k == 0 || p.length <= 2 * k) return null
        for (i in 0 until k) {
            if (p[p.length - 1 - i] != ')') return null
        }
        val inner = p.substring(3 * k, p.length - k)
        var depth = 0
        var i = 0
        while (i < inner.length) {
            val c = inner[i]
            if (c == '\\') { i += 2; continue }
            if (c == '[') {
                i++
                while (i < inner.length && inner[i] != ']') { if (inner[i] == '\\') i++; i++ }
            } else if (c == '(') depth++
            else if (c == ')') { depth--; if (depth < 0) return null }
            i++
        }
        return if (depth == 0) inner else null
    }

    fun newRegExp(ctx: JSContext, pattern: String, flags: String): JSValue {
        val rt = JSRuntime(ctx)
        val obj = JS_NewObjectProtoClass(ctx, ctx.classProto[JSObjectClassEnum.JS_CLASS_REGEXP.value],
                                          JSObjectClassEnum.JS_CLASS_REGEXP.value, 0)
        if (JS_IsException(obj)) return obj
        val keep = ctx.gcKeepAlive.size
        ctx.gcKeepAlive.add(obj)
        try {
            val o = ctx.gcKeepAlive[keep]
            rt.setProperty(o, JS_NewString(ctx, "source"), JS_NewString(ctx, pattern))
            rt.setProperty(o, JS_NewString(ctx, "flags"), JS_NewString(ctx, flags))
            rt.setProperty(o, JS_NewString(ctx, "global"), JS_NewBool(flags.contains('g')))
            rt.setProperty(o, JS_NewString(ctx, "lastIndex"), JS_NewShortInt(0))
            JS_SetOpaque(ctx, ctx.gcKeepAlive[keep],
                         RegexpInfo(compileRegexp(pattern, flags), pattern,
                                    quantifiedCaptureAncestors(pattern)))
        if (compileRegexp(pattern, flags) == null) {
            JS_ThrowSyntaxError(ctx, "invalid regular expression")
            return JS_EXCEPTION
        }
            return ctx.gcKeepAlive[keep]
        } finally {
            while (ctx.gcKeepAlive.size > keep) ctx.gcKeepAlive.removeAt(ctx.gcKeepAlive.size - 1)
        }
    }

    private fun reFlags(ctx: JSContext, rt: JSRuntime, re: JSValue): String {
        val v = rt.getProperty(re, JS_NewString(ctx, "flags"))
        return if (JS_IsString(ctx, v)) jsGetString(ctx, v) else ""
    }

    class RegexpInfo(val pat: java.util.regex.Pattern?, val source: String,
                     val quantifiedAncestors: IntArray)

    /* 解析正则结构：返回每个捕获组“最近的被 * / + / {n,} 量词化的捕获祖先”索引。
     * JS 规范要求量词内未在最后一次迭代参与的捕获组为 undefined，而 Java 会保留
     * 更早迭代的值；据此在 exec 结果里把过期捕获重置为 undefined。 */
    private fun quantifiedCaptureAncestors(pattern: String): IntArray {
        val parents = ArrayList<Int>()
        val captureIdx = ArrayList<Int>()
        val quantified = ArrayList<Boolean>()
        val isAssertion = ArrayList<Boolean>()
        val quantifiedZero = ArrayList<Boolean>()
        val stack = ArrayList<Int>()
        var capCount = 0
        var i = 0
        fun newGroup(cap: Boolean, assertion: Boolean = false): Int {
            val id = parents.size
            parents.add(stack.lastOrNull() ?: -1)
            captureIdx.add(if (cap) ++capCount else -1)
            quantified.add(false)
            isAssertion.add(assertion)
            quantifiedZero.add(false)
            stack.add(id)
            return id
        }
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' -> i += 2
                c == '[' -> {
                    i++
                    while (i < pattern.length && pattern[i] != ']') {
                        if (pattern[i] == '\\') i++
                        i++
                    }
                    i++
                }
                c == '(' -> {
                    if (i + 1 < pattern.length && pattern[i + 1] == '?') {
                        val c2 = if (i + 2 < pattern.length) pattern[i + 2] else ' '
                        if (c2 == '<' && i + 3 < pattern.length &&
                            (pattern[i + 3] == '=' || pattern[i + 3] == '!')) {
                            newGroup(false, assertion = true); i += 4
                        } else if (c2 == '=' || c2 == '!') {
                            newGroup(false, assertion = true); i += 3
                        } else if (c2 == '<' || c2 == 'P') {
                            newGroup(true)
                            i += if (c2 == 'P') 4 else 3
                            while (i < pattern.length && pattern[i] != '>') i++
                            i++
                        } else {
                            newGroup(false); i += 3
                        }
                    } else {
                        newGroup(true); i++
                    }
                }
                c == ')' -> {
                    val id = stack.removeAt(stack.size - 1)
                    var j = i + 1
                    var q = false
                    var qZero = false
                    if (j < pattern.length) {
                        when (pattern[j]) {
                            '*' -> { q = true; qZero = true; j++ }
                            '+' -> { q = true; j++ }
                            '?' -> { q = true; qZero = true; j++ }
                            '{' -> {
                                val close = pattern.indexOf('}', j)
                                if (close > j && pattern.substring(j + 1, close).matches(Regex("\\d+(,\\d*)?"))) {
                                    q = true
                                    val body = pattern.substring(j + 1, close)
                                    qZero = body.startsWith("0") && (body.length == 1 || body[1] == ',')
                                    j = close + 1
                                }
                            }
                        }
                        if (q && j < pattern.length && pattern[j] == '?') j++ /* 懒惰量词 */
                    }
                    quantified[id] = q
                    quantifiedZero[id] = qZero
                    i = j
                }
                else -> i++
            }
        }
        val anc = IntArray(capCount + 1) { -1 }
        for (id in parents.indices) {
            val ci = captureIdx[id]
            if (ci < 0) continue
            var p = parents[id]
            var sawAssertion = false
            while (p >= 0) {
                if (isAssertion[p]) sawAssertion = true
                if (sawAssertion && quantifiedZero[p]) {
                    anc[ci] = -2   /* 可空量词内的先行/后行断言：捕获一律重置 */
                    break
                }
                if (quantified[p] && captureIdx[p] >= 0) {
                    anc[ci] = captureIdx[p]; break
                }
                p = parents[p]
            }
        }
        return anc
    }

    fun regexpExec(ctx: JSContext, rt: JSRuntime, re: JSValue, str: String): JSValue {
        val info = JS_GetOpaque(ctx, re) as? RegexpInfo
        val pat = info?.pat
        if (pat == null) return JS_NULL
        val reKeep = ctx.gcKeepAlive.size
        ctx.gcKeepAlive.add(re)
        val flags = reFlags(ctx, rt, ctx.gcKeepAlive[reKeep])
        val global = flags.contains('g')
        val unicode = flags.contains('u')
        val m = pat.matcher(str)
        var start = 0
        if (global) {
            val li = rt.getProperty(ctx.gcKeepAlive[reKeep], JS_NewString(ctx, "lastIndex"))
            start = if (JS_IsInt(li)) JS_VALUE_GET_INT(li) else 0
            /* unicode 模式下 lastIndex 落在代理对中间时视为匹配失败并清零 */
            if (unicode && start > 0 && start < str.length &&
                Character.isLowSurrogate(str[start]) && Character.isHighSurrogate(str[start - 1])) {
                rt.setProperty(ctx.gcKeepAlive[reKeep], JS_NewString(ctx, "lastIndex"), JS_NewShortInt(0))
                return JS_NULL
            }
            if (start < 0 || start > str.length) {
                rt.setProperty(ctx.gcKeepAlive[reKeep], JS_NewString(ctx, "lastIndex"), JS_NewShortInt(0))
                return JS_NULL
            }
        }
        val found = if (global) m.find(start) else m.find()
        if (!found) {
            if (global) rt.setProperty(ctx.gcKeepAlive[reKeep], JS_NewString(ctx, "lastIndex"), JS_NewShortInt(0))
            return JS_NULL
        }
        val n = m.groupCount() + 1
        val arr = JS_NewArray(ctx, n)
        if (JS_IsException(arr)) return JS_NULL
        val keep = ctx.gcKeepAlive.size
        ctx.gcKeepAlive.add(ctx.gcKeepAlive[reKeep])
        ctx.gcKeepAlive.add(arr)
        for (g in 0 until n) {
            var gv = m.group(g)
            /* 量词内、且不属于最后一次迭代的捕获应重置为 undefined */
            if (gv != null && info != null && g < info.quantifiedAncestors.size) {
                val a = info.quantifiedAncestors[g]
                if (a == -2) {
                    gv = null
                } else if (a >= 0 && m.start(g) >= 0 && m.start(a) >= 0 && m.start(g) < m.start(a)) {
                    gv = null
                }
            }
            rt.setElement(ctx.gcKeepAlive[keep + 1], JS_NewShortInt(g),
                if (gv == null) JS_UNDEFINED else JS_NewString(ctx, gv))
        }
        ctx.memory.putI32(JS_VALUE_TO_PTR(ctx.gcKeepAlive[keep + 1]) + 32, n)
        rt.setProperty(ctx.gcKeepAlive[keep + 1], JS_NewString(ctx, "index"), JS_NewShortInt(m.start()))
        rt.setProperty(ctx.gcKeepAlive[keep + 1], JS_NewString(ctx, "input"), JS_NewString(ctx, str))
        if (global) rt.setProperty(ctx.gcKeepAlive[keep], JS_NewString(ctx, "lastIndex"), JS_NewShortInt(m.end()))
        return ctx.gcKeepAlive[keep + 1]
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
        val pat = (JS_GetOpaque(ctx, re) as? RegexpInfo)?.pat ?: return JS_NewString(ctx, s)
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
        val pat = (JS_GetOpaque(ctx, re) as? RegexpInfo)?.pat ?: return JS_NULL
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

    /* ---------------- TypedArray / ArrayBuffer ---------------- */

    class ArrayBufferInfo(val bytes: ByteArray)

    class TypedArrayInfo(val bytes: ByteArray, var offset: Int, var length: Int, val kind: Int)

    private fun readTypedI16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun writeTypedI16(b: ByteArray, o: Int, v: Long) {
        b[o] = (v and 0xFF).toByte()
        b[o + 1] = ((v shr 8) and 0xFF).toByte()
    }
    private fun readTypedI32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
        ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
    private fun writeTypedI32(b: ByteArray, o: Int, v: Long) {
        for (i in 0 until 4) b[o + i] = ((v shr (8 * i)) and 0xFF).toByte()
    }
    private fun readTypedF32(b: ByteArray, o: Int): Float = Float.fromBits(readTypedI32(b, o))
    private fun writeTypedF32(b: ByteArray, o: Int, f: Float) {
        writeTypedI32(b, o, f.toBits().toLong())
    }
    private fun readTypedF64(b: ByteArray, o: Int): Double {
        var bits = 0L
        for (i in 0 until 8) bits = bits or ((b[o + i].toLong() and 0xFF) shl (8 * i))
        return Double.fromBits(bits)
    }
    private fun writeTypedF64(b: ByteArray, o: Int, d: Double) {
        val bits = d.toBits()
        for (i in 0 until 8) b[o + i] = ((bits shr (8 * i)) and 0xFF).toByte()
    }

    fun readTyped(ctx: JSContext, ti: TypedArrayInfo, idx: Int): JSValue {
        val o = ti.offset + idx * typedBpe(ti.kind)
        return when (ti.kind) {
            0 -> JS_NewShortInt(ti.bytes[o].toInt() and 0xFF)
            1 -> JS_NewShortInt(ti.bytes[o].toInt())
            2 -> JS_NewShortInt(readTypedI16(ti.bytes, o) and 0xFFFF)
            3 -> JS_NewShortInt(readTypedI16(ti.bytes, o).toShort().toInt())
            4 -> JS_NewFloat64(ctx, (readTypedI32(ti.bytes, o).toLong() and 0xFFFFFFFFL).toDouble())
            5 -> JS_NewShortInt(readTypedI32(ti.bytes, o))
            6 -> JS_NewFloat64(ctx, readTypedF32(ti.bytes, o).toDouble())
            7 -> JS_NewFloat64(ctx, readTypedF64(ti.bytes, o))
            else -> JS_NewShortInt(ti.bytes[o].toInt() and 0xFF)
        }
    }

    fun writeTyped(ctx: JSContext, rt: JSRuntime, ti: TypedArrayInfo, idx: Int, v: JSValue) {
        val o = ti.offset + idx * typedBpe(ti.kind)
        when (ti.kind) {
            0, 1 -> ti.bytes[o] = (rt.toInt32(v) and 0xFF).toByte()
            2, 3 -> writeTypedI16(ti.bytes, o, rt.toInt32(v).toLong() and 0xFFFF)
            4 -> writeTypedI32(ti.bytes, o, rt.toUInt32(v))
            5 -> writeTypedI32(ti.bytes, o, rt.toInt32(v).toLong())
            6 -> writeTypedF32(ti.bytes, o, rt.toNumber(v).toFloat())
            7 -> writeTypedF64(ti.bytes, o, rt.toNumber(v))
            else -> {
                val d = rt.toNumber(v)
                val clamped = when {
                    d.isNaN() || d <= 0.0 -> 0
                    d >= 255.0 -> 255
                    else -> {
                        val fl = Math.floor(d)
                        val diff = d - fl
                        when {
                            diff < 0.5 -> fl.toInt()
                            diff > 0.5 -> (fl + 1).toInt()
                            (fl.toLong() % 2L) == 0L -> fl.toInt()
                            else -> (fl + 1).toInt()
                        }
                    }
                }
                ti.bytes[o] = clamped.toByte()
            }
        }
    }

    private fun typedBpe(kind: Int): Int = when (kind) {
        0, 1, 8 -> 1
        2, 3 -> 2
        4, 5, 6 -> 4
        else -> 8
    }

    private fun makeArrayBuffer(ctx: JSContext, rt: JSRuntime, len: Int): JSValue {
        val obj = JS_NewObjectProtoClass(ctx, ctx.classProto[JSObjectClassEnum.JS_CLASS_ARRAY_BUFFER.value],
                                          JSObjectClassEnum.JS_CLASS_ARRAY_BUFFER.value, 0)
        if (JS_IsException(obj)) return obj
        val keep = ctx.gcKeepAlive.size
        ctx.gcKeepAlive.add(obj)
        try {
            rt.setProperty(ctx.gcKeepAlive[keep], JS_NewString(ctx, "byteLength"), JS_NewShortInt(len))
            JS_SetOpaque(ctx, ctx.gcKeepAlive[keep], ArrayBufferInfo(ByteArray(len.coerceAtLeast(0))))
            return ctx.gcKeepAlive[keep]
        } finally {
            while (ctx.gcKeepAlive.size > keep) ctx.gcKeepAlive.removeAt(ctx.gcKeepAlive.size - 1)
        }
    }

    private fun makeTypedArray(ctx: JSContext, rt: JSRuntime, kind: Int, bpe: Int,
                               bufferObj: JSValue, bufInfo: ArrayBufferInfo,
                               offset: Int, length: Int): JSValue {
        val obj = JS_NewArray(ctx, length)
        if (JS_IsException(obj)) return obj
        val keep = ctx.gcKeepAlive.size
        ctx.gcKeepAlive.add(obj)
        ctx.gcKeepAlive.add(bufferObj)
        try {
            val o = ctx.gcKeepAlive[keep]
            ctx.memory.putI32(JS_VALUE_TO_PTR(o) + 32, length)
            rt.setProperty(o, JS_NewString(ctx, "BYTES_PER_ELEMENT"), JS_NewShortInt(bpe))
            rt.setProperty(o, JS_NewString(ctx, "byteOffset"), JS_NewShortInt(offset))
            rt.setProperty(o, JS_NewString(ctx, "byteLength"), JS_NewShortInt(length * bpe))
            rt.setProperty(ctx.gcKeepAlive[keep], JS_NewString(ctx, "buffer"), ctx.gcKeepAlive[keep + 1])
            rt.setProperty(ctx.gcKeepAlive[keep], JS_NewString(ctx, "length"), JS_NewShortInt(length))
            JS_SetOpaque(ctx, ctx.gcKeepAlive[keep], TypedArrayInfo(bufInfo.bytes, offset, length, kind))
            return ctx.gcKeepAlive[keep]
        } finally {
            while (ctx.gcKeepAlive.size > keep) ctx.gcKeepAlive.removeAt(ctx.gcKeepAlive.size - 1)
        }
    }

    private fun initTypedArrays(ctx: JSContext, rt: JSRuntime) {
        val abProto = JS_NewObject(ctx)
        ctx.classProto[JSObjectClassEnum.JS_CLASS_ARRAY_BUFFER.value] = abProto
        val abCtor = native(ctx, 1) { c, _, args ->
            makeArrayBuffer(c, rt, toInt(c, rt, arg(args, 0)).coerceAtLeast(0))
        }
        rt.setProperty(abCtor, JS_NewString(ctx, "prototype"), abProto)
        rt.setProperty(abProto, JS_NewString(ctx, "constructor"), abCtor)
        defGlobal(ctx, rt, "ArrayBuffer", abCtor)

        val defs = listOf(
            Triple("Uint8Array", 1, 0), Triple("Int8Array", 1, 1),
            Triple("Uint16Array", 2, 2), Triple("Int16Array", 2, 3),
            Triple("Uint32Array", 4, 4), Triple("Int32Array", 4, 5),
            Triple("Float32Array", 4, 6), Triple("Float64Array", 8, 7),
            Triple("Uint8ClampedArray", 1, 8)
        )
        for ((name, bpe, kind) in defs) {
            val proto = JS_NewObject(ctx)
            val ctor = native(ctx, 1) { c, _, args ->
                val a0 = arg(args, 0)
                if (JS_IsPtr(a0) && JS_GetOpaque(c, a0) is ArrayBufferInfo) {
                    val info = JS_GetOpaque(c, a0) as ArrayBufferInfo
                    val off = if (args.size > 1) toInt(c, rt, args[1]).coerceAtLeast(0) else 0
                    val len = if (args.size > 2 && !JS_IsUndefined(args[2]))
                        toInt(c, rt, args[2]).coerceAtLeast(0)
                    else ((info.bytes.size - off) / bpe).coerceAtLeast(0)
                    makeTypedArray(c, rt, kind, bpe, a0, info, off, len)
                } else if (JS_IsPtr(a0) && c.memory.getMTag(JS_VALUE_TO_PTR(a0)) == JSMTags.JS_MTAG_OBJECT &&
                           c.memory.getU8(JS_VALUE_TO_PTR(a0) + 2) == JSObjectClassEnum.JS_CLASS_ARRAY.value) {
                    val len = c.memory.getI32(JS_VALUE_TO_PTR(a0) + 32)
                    val buf = makeArrayBuffer(c, rt, len * bpe)
                    val info = JS_GetOpaque(c, buf) as ArrayBufferInfo
                    val ta = makeTypedArray(c, rt, kind, bpe, buf, info, 0, len)
                    val ti = JS_GetOpaque(c, ta) as TypedArrayInfo
                    for (i in 0 until len) {
                        writeTyped(c, rt, ti, i, rt.getElement(a0, JS_NewShortInt(i)))
                    }
                    ta
                } else {
                    val len = toInt(c, rt, a0).coerceAtLeast(0)
                    val buf = makeArrayBuffer(c, rt, len * bpe)
                    val info = JS_GetOpaque(c, buf) as ArrayBufferInfo
                    makeTypedArray(c, rt, kind, bpe, buf, info, 0, len)
                }
            }
            rt.setProperty(ctor, JS_NewString(ctx, "prototype"), proto)
            rt.setProperty(proto, JS_NewString(ctx, "constructor"), ctor)
            defValue(ctx, rt, ctor, "BYTES_PER_ELEMENT", JS_NewShortInt(bpe))
            defGlobal(ctx, rt, name, ctor)
            defMethod(ctx, rt, proto, "set", 2) { c, thisVal, args ->
                val ti = JS_GetOpaque(c, thisVal) as? TypedArrayInfo
                val src = arg(args, 0)
                val off = if (args.size > 1) toInt(c, rt, args[1]).coerceAtLeast(0) else 0
                if (ti != null && JS_IsPtr(src)) {
                    val n = c.memory.getI32(JS_VALUE_TO_PTR(src) + 32)
                    for (i in 0 until n) {
                        if (off + i >= ti.length) break
                        writeTyped(c, rt, ti, off + i, rt.getElement(src, JS_NewShortInt(i)))
                    }
                }
                JS_UNDEFINED
            }
            defMethod(ctx, rt, proto, "subarray", 2) { c, thisVal, args ->
                val ti = JS_GetOpaque(c, thisVal) as? TypedArrayInfo
                if (ti == null) JS_UNDEFINED
                else {
                    var s = if (args.isNotEmpty()) toInt(c, rt, args[0]) else 0
                    var e = if (args.size > 1 && !JS_IsUndefined(args[1])) toInt(c, rt, args[1]) else ti.length
                    if (s < 0) s = (ti.length + s).coerceAtLeast(0)
                    if (e < 0) e = (ti.length + e).coerceAtLeast(0)
                    s = s.coerceIn(0, ti.length); e = e.coerceIn(0, ti.length)
                    if (e < s) e = s
                    val buf = rt.getProperty(thisVal, JS_NewString(c, "buffer"))
                    val info = JS_GetOpaque(c, buf) as? ArrayBufferInfo
                    if (info == null) JS_UNDEFINED
                    else makeTypedArray(c, rt, ti.kind, typedBpe(ti.kind), buf, info,
                                        ti.offset + s * typedBpe(ti.kind), e - s)
                }
            }
        }
    /* 类型化数组实例的原型是 Array.prototype（便于 toString 等），
         * 因此 set/subarray 也注册在 Array.prototype 上（按 opaque 判定实例类型） */
        val arrProto = ctx.classProto[JSObjectClassEnum.JS_CLASS_ARRAY.value]
        defMethod(ctx, rt, arrProto, "set", 2) { c, thisVal, args ->
            val ti = JS_GetOpaque(c, thisVal) as? TypedArrayInfo
            val src = arg(args, 0)
            val off = if (args.size > 1) toInt(c, rt, args[1]).coerceAtLeast(0) else 0
            if (ti != null && JS_IsPtr(src)) {
                val n = c.memory.getI32(JS_VALUE_TO_PTR(src) + 32)
                for (i in 0 until n) {
                    if (off + i >= ti.length) break
                    writeTyped(c, rt, ti, off + i, rt.getElement(src, JS_NewShortInt(i)))
                }
            }
            JS_UNDEFINED
        }
        defMethod(ctx, rt, arrProto, "subarray", 2) { c, thisVal, args ->
            val ti = JS_GetOpaque(c, thisVal) as? TypedArrayInfo
            if (ti == null) JS_UNDEFINED
            else {
                var s = if (args.isNotEmpty()) toInt(c, rt, args[0]) else 0
                var e = if (args.size > 1 && !JS_IsUndefined(args[1])) toInt(c, rt, args[1]) else ti.length
                if (s < 0) s = (ti.length + s).coerceAtLeast(0)
                if (e < 0) e = (ti.length + e).coerceAtLeast(0)
                s = s.coerceIn(0, ti.length); e = e.coerceIn(0, ti.length)
                if (e < s) e = s
                val buf = rt.getProperty(thisVal, JS_NewString(c, "buffer"))
                val info = JS_GetOpaque(c, buf) as? ArrayBufferInfo
                if (info == null) JS_UNDEFINED
                else makeTypedArray(c, rt, ti.kind, typedBpe(ti.kind), buf, info,
                                    ti.offset + s * typedBpe(ti.kind), e - s)
            }
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
                        var first = true
                        for (i in 0 until n) {
                            val k = rt.getElement(keys, JS_NewShortInt(i))
                            val pv = rt.getProperty(v, k)
                            if (JS_IsUndefined(pv)) continue   /* undefined 属性被省略 */
                            if (!first) sb.append(',')
                            first = false
                            sb.append(jsonQuote(rt.toStringValue(k)))
                            sb.append(':')
                            jsonStringify(ctx, rt, pv, sb, seen)
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

/* Number.prototype.toExponential：digits 为 null 时输出最短尾数 */
fun jsToExponential(d: Double, digits: Int?): String {
    if (d.isNaN()) return "NaN"
    if (d == Double.POSITIVE_INFINITY) return "Infinity"
    if (d == Double.NEGATIVE_INFINITY) return "-Infinity"
    val sign = if (d < 0 || (d == 0.0 && 1.0 / d < 0)) "-" else ""
    val a = Math.abs(d)
    var bd = java.math.BigDecimal(a).stripTrailingZeros()
    if (bd.signum() == 0) {
        val mant = if (digits == null || digits == 0) "0" else "0." + "0".repeat(digits)
        return "$sign${mant}e+0"
    }
    if (digits != null) {
        bd = bd.round(java.math.MathContext(digits + 1, java.math.RoundingMode.HALF_UP))
            .stripTrailingZeros()
        if (bd.signum() == 0) {
            val mant = if (digits == 0) "0" else "0." + "0".repeat(digits)
            return "$sign${mant}e+0"
        }
    }
    var unscaled = bd.unscaledValue().abs().toString()
    val exp = unscaled.length - 1 - bd.scale()
    val mant: String = if (digits == null) {
        if (unscaled.length == 1) unscaled else unscaled[0] + "." + unscaled.substring(1)
    } else {
        if (unscaled.length < digits + 1) unscaled = unscaled + "0".repeat(digits + 1 - unscaled.length)
        if (digits == 0) unscaled.substring(0, 1) else unscaled[0] + "." + unscaled.substring(1, digits + 1)
    }
    val esign = if (exp >= 0) "+" else "-"
    return sign + mant + "e" + esign + Math.abs(exp)
}

fun jsToPrecision(d: Double, p: Int): String {
    if (d.isNaN() || d == Double.POSITIVE_INFINITY || d == Double.NEGATIVE_INFINITY || d == 0.0) {
        return jsNumberToString(d)
    }
    val a = Math.abs(d)
    val bd0 = java.math.BigDecimal(a).stripTrailingZeros()
    val exp = bd0.unscaledValue().abs().toString().length - 1 - bd0.scale()
    if (exp < -6 || exp >= p) return jsToExponential(d, p - 1)
    val sign = if (d < 0) "-" else ""
    val bd = bd0.round(java.math.MathContext(p, java.math.RoundingMode.HALF_UP))
    var s = bd.toPlainString()
    val digitsBefore = if (exp >= 0) exp + 1 else 1
    val frac = (p - digitsBefore).coerceAtLeast(0)
    if (frac == 0) {
        s = s.substringBefore('.')
    } else {
        val dot = s.indexOf('.')
        val cur = if (dot < 0) 0 else s.length - dot - 1
        if (cur < frac) s = (if (dot < 0) "$s." else s) + "0".repeat(frac - cur)
        else if (cur > frac) s = s.substring(0, dot + 1 + frac)
    }
    return sign + s
}