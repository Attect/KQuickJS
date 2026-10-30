package app.muka.project.kquickjs.bridge

import app.muka.project.kquickjs.FRAME_CF_ARGC_MASK
import app.muka.project.kquickjs.FRAME_CF_CTOR
import app.muka.project.kquickjs.JSCFunctionDef
import app.muka.project.kquickjs.JSCFunctionDefEnum
import app.muka.project.kquickjs.JSContext
import app.muka.project.kquickjs.JSObjectClassEnum
import app.muka.project.kquickjs.JSValue
import app.muka.project.kquickjs.JS_IsError
import app.muka.project.kquickjs.JS_IsException
import app.muka.project.kquickjs.JS_IsPtr
import app.muka.project.kquickjs.JS_IsString
import app.muka.project.kquickjs.JS_IsUndefined
import app.muka.project.kquickjs.JS_EVAL_RETVAL
import app.muka.project.kquickjs.JS_Eval
import app.muka.project.kquickjs.JS_GetException
import app.muka.project.kquickjs.JS_NewCFunctionParams
import app.muka.project.kquickjs.JS_NewObject
import app.muka.project.kquickjs.JS_NewObjectProtoClass
import app.muka.project.kquickjs.JS_NewString
import app.muka.project.kquickjs.JS_NULL
import app.muka.project.kquickjs.JS_SetOpaque
import app.muka.project.kquickjs.JS_UNDEFINED
import app.muka.project.kquickjs.JS_GetOpaque
import app.muka.project.kquickjs.JS_VALUE_TO_PTR
import app.muka.project.kquickjs.JSMTags
import app.muka.project.kquickjs.jsGetString
import app.muka.project.kquickjs.memory.getMTag
import app.muka.project.kquickjs.runtime.JSRuntime

/* =====================================================================
 * KQuickJS 宿主方法桥接层：builder 式注册 API
 *
 * - 在 JS_NewContext 之后即可向全局对象注册宿主函数与嵌套命名空间对象
 *   （如 platform.http.get），无需预建定长 cFunctionTable；
 * - 函数定义挂入 JSContext.extFunctionTable（可扩表），funcIdx 自动分配；
 * - 宿主函数以普通 Kotlin lambda 书写（定型签名 fun interface，兼容
 *   GraalVM Native Image，零运行时反射）；
 * - 宿主 lambda 抛出的任何异常被捕获并转为 JS Error 对象，不会炸穿
 *   解释器调用栈；反方向 JS 错误包装为 JSBridgeScriptException。
 * ===================================================================== */

/* ---------------- 宿主函数定型签名（fun interface） ----------------
 * 不使用 kotlin.Function 泛型形态：同为 Function3 的 (String,Int) 与
 * (String,String) 版本会因泛型擦除产生 JVM 重载冲突。同时设计上每个
 * 参数个数只提供一种定型签名（0/1/2 参），避免 Kotlin 在 lambda 调用点
 * 的重载歧义；更多参数、其他类型组合或需要 JSValue 原样访问时，统一走
 * 参数名读取的通用形态（HostFnArgs + JSBridgeCall）。 */

/** 0 参数宿主函数 */
fun interface HostFn0 {
    fun invoke(ctx: JSContext): Any?
}

/** 1 参数（string）宿主函数 */
fun interface HostFn1String {
    fun invoke(ctx: JSContext, p0: String): Any?
}

/** 2 参数（string, int）宿主函数（桥接层推荐签名形态） */
fun interface HostFn2StringInt {
    fun invoke(ctx: JSContext, p0: String, p1: Int): Any?
}

/** 通用形态宿主函数：参数经 [JSBridgeCall] 按名/按位读取 */
fun interface HostFnArgs {
    fun invoke(ctx: JSContext, args: JSBridgeCall): Any?
}

/** 原生形态宿主函数：直接处理 JSValue（高性能/可变参数路径） */
fun interface HostRawFn {
    fun invoke(ctx: JSContext, thisVal: JSValue, args: JSBridgeCall): JSValue
}

/**
 * 宿主对象引用包装：作为 opaque 挂在 JS 对象上，
 * GC 回收对象时由桥接层的实例分发 finalizer 调用 [onFinalize]。
 */
class JSBridgeHostRef internal constructor(
    /** 宿主持有的 Kotlin 实例 */
    val instance: Any?,
    /** 清理回调（GC finalizer 内执行，禁止在其中调用 JS 函数） */
    private val onFinalize: ((Any?) -> Unit)?
) {
    /** 由 GC finalizer 调用；回调异常吞掉并输出 stderr，避免影响回收流程 */
    internal fun runFinalize() {
        try {
            onFinalize?.invoke(instance)
        } catch (t: Throwable) {
            /* finalizer 内禁止调用 JS 函数；宿主清理回调的异常只记录不传播 */
            System.err.println("JSBridge 宿主对象 finalizer 异常: ${t::class.simpleName}: ${t.message}")
        }
    }
}

/**
 * 宿主函数调用参数读取器：为宿主 lambda 提供按位/按名的严格类型读取。
 * 转换失败抛出的 [JSBridgeException] 携带"函数路径 + 参数位置 + 参数名 +
 * 期望类型 + 实际类型"，最终呈现为可定位的 JS TypeError。
 */
class JSBridgeCall internal constructor(
    private val ctx: JSContext,
    private val argv: LongArray,
    /** 实际参数个数（已剥离构造器调用标志位） */
    val size: Int,
    private val funcPath: String,
    private val paramNames: List<String>
) {
    /** 参数定位描述："参数 1 'timeoutMs'" */
    private fun what(index: Int, name: String?): String {
        val paramName = name ?: paramNames.getOrElse(index) { "arg$index" }
        return "$funcPath 的参数 $index '$paramName'"
    }

    private fun requirePresent(index: Int, name: String?) {
        if (index < 0 || index >= size) {
            val paramName = name ?: paramNames.getOrElse(index) { "arg$index" }
            throw JSBridgeException(
                JSErrorKind.TYPE_ERROR,
                "$funcPath 缺少参数 $index '$paramName'：实际收到 $size 个参数"
            )
        }
        if (index >= argv.size) {
            throw JSBridgeException(JSErrorKind.INTERNAL_ERROR, "$funcPath 参数 $index 越界（内部错误）")
        }
    }

    /** 该位置的参数是否存在（数量足够且不是 undefined） */
    fun isProvided(index: Int): Boolean = index in 0 until size && !JS_IsUndefined(argv[index])

    /** 该位置的原始 JSValue（缺参返回 undefined） */
    fun jsValue(index: Int): JSValue = if (index in 0 until size) argv[index] else JS_UNDEFINED

    /** 第 [index] 个参数是否为 undefined（含缺参） */
    fun isUndefined(index: Int): Boolean = index >= size || JS_IsUndefined(argv[index])

    /* ---------------- 按位读取 ---------------- */

    fun string(index: Int, name: String? = null): String {
        requirePresent(index, name)
        return JSBridgeConvert.toJsString(ctx, argv[index], what(index, name))
    }

    fun stringOrNull(index: Int, name: String? = null): String? {
        if (isUndefined(index)) return null
        return JSBridgeConvert.toJsString(ctx, argv[index], what(index, name))
    }

    fun int(index: Int, name: String? = null): Int {
        requirePresent(index, name)
        return JSBridgeConvert.toJsInt(ctx, argv[index], what(index, name))
    }

    fun intOrNull(index: Int, name: String? = null): Int? {
        if (isUndefined(index)) return null
        return JSBridgeConvert.toJsInt(ctx, argv[index], what(index, name))
    }

    fun long(index: Int, name: String? = null): Long {
        requirePresent(index, name)
        return JSBridgeConvert.toJsLong(ctx, argv[index], what(index, name))
    }

    fun longOrNull(index: Int, name: String? = null): Long? {
        if (isUndefined(index)) return null
        return JSBridgeConvert.toJsLong(ctx, argv[index], what(index, name))
    }

    fun double(index: Int, name: String? = null): Double {
        requirePresent(index, name)
        return JSBridgeConvert.toJsDouble(ctx, argv[index], what(index, name))
    }

    fun doubleOrNull(index: Int, name: String? = null): Double? {
        if (isUndefined(index)) return null
        return JSBridgeConvert.toJsDouble(ctx, argv[index], what(index, name))
    }

    fun boolean(index: Int, name: String? = null): Boolean {
        requirePresent(index, name)
        return JSBridgeConvert.toJsBoolean(ctx, argv[index], what(index, name))
    }

    fun booleanOrNull(index: Int, name: String? = null): Boolean? {
        if (isUndefined(index)) return null
        return JSBridgeConvert.toJsBoolean(ctx, argv[index], what(index, name))
    }

    fun bytes(index: Int, name: String? = null): ByteArray {
        requirePresent(index, name)
        return JSBridgeConvert.toJsBytes(ctx, argv[index], what(index, name))
    }

    fun list(index: Int, name: String? = null): List<Any?> {
        requirePresent(index, name)
        return JSBridgeConvert.toJsList(ctx, argv[index], what(index, name))
    }

    fun map(index: Int, name: String? = null): Map<String, Any?> {
        requirePresent(index, name)
        return JSBridgeConvert.toJsMap(ctx, argv[index], what(index, name))
    }

    /** 通用转换读取（宽松语义，规则见 [JSBridgeConvert.toKotlin]） */
    fun any(index: Int): Any? = if (index in 0 until size) JSBridgeConvert.toKotlin(ctx, argv[index]) else null

    /* ---------------- 按名读取（要求注册时声明了参数名） ---------------- */

    private fun indexOfName(name: String): Int {
        val idx = paramNames.indexOf(name)
        if (idx < 0) {
            throw JSBridgeException(
                JSErrorKind.INTERNAL_ERROR,
                "$funcPath 注册时未声明参数名 '$name'，无法按名读取"
            )
        }
        return idx
    }

    fun string(name: String): String = string(indexOfName(name), name)
    fun stringOrNull(name: String): String? = stringOrNull(indexOfName(name), name)
    fun int(name: String): Int = int(indexOfName(name), name)
    fun intOrNull(name: String): Int? = intOrNull(indexOfName(name), name)
    fun long(name: String): Long = long(indexOfName(name), name)
    fun longOrNull(name: String): Long? = longOrNull(indexOfName(name), name)
    fun double(name: String): Double = double(indexOfName(name), name)
    fun doubleOrNull(name: String): Double? = doubleOrNull(indexOfName(name), name)
    fun boolean(name: String): Boolean = boolean(indexOfName(name), name)
    fun booleanOrNull(name: String): Boolean? = booleanOrNull(indexOfName(name), name)
    fun bytes(name: String): ByteArray = bytes(indexOfName(name), name)
    fun list(name: String): List<Any?> = list(indexOfName(name), name)
    fun map(name: String): Map<String, Any?> = map(indexOfName(name), name)
}

/**
 * 宿主桥接层入口。用法：
 * ```kotlin
 * val bridge = JSBridge(ctx)
 * bridge.namespace("platform").apply {
 *     namespace("log").function("info", "msg") { c, args -> ...; null }
 *     function("now") { c -> System.currentTimeMillis() }
 *     function("http.get", "url", "timeoutMs") { c, url, timeoutMs -> mapOf(...) }
 * }
 * ```
 */
class JSBridge(val ctx: JSContext) {

    internal val runtime = JSRuntime(ctx)

    init {
        /* 错误类原型初始化：JS_NewContext 仅将 classProto 统一置为 JS_NULL，
         * 引擎又未在 Kotlin 侧注册标准库原型。桥接层为错误类（Error/TypeError/
         * RangeError/...）创建各自独立的空原型对象，使错误对象可通过原型链区分
         * 具体类型（pendingScriptException 的 jsErrorName 依赖此区分）。
         * classProto 为 GC 根，原型对象随堆搬移自动更新，重复构造幂等 */
        for (e in JSObjectClassEnum.entries) {
            if (e.value in JSObjectClassEnum.JS_CLASS_ERROR.value until JSObjectClassEnum.JS_CLASS_USER.value) {
                if (ctx.classProto[e.value] == JS_NULL) {
                    ctx.classProto[e.value] = JS_NewObject(ctx)
                }
            }
        }
    }

    /** 解析/创建命名空间路径（如 "platform.http"），段间自动创建普通对象 */
    fun namespace(path: String): JSBridgeNamespace {
        val segments = if (path.isEmpty()) emptyList() else path.split('.')
        /* 逐段在前缀命名空间上创建子段（ensureChildNamespace 在"父对象"上
         * 创建传入的段名，因此句柄必须始终指向不含当前段的前缀） */
        var cur = JSBridgeNamespace(this, emptyList())
        for (seg in segments) {
            cur.ensureChildNamespace(seg)
            cur = JSBridgeNamespace(this, cur.segments + seg)
        }
        return cur
    }

    /* ---------------- 顶层便捷注册（path 最后一段为函数/属性名） ---------------- */

    fun function(path: String, fn: HostFn0) = namespace(parentPath(path)).function(lastSegment(path), fn)

    fun function(path: String, p0: String, fn: HostFn1String) =
        namespace(parentPath(path)).function(lastSegment(path), p0, fn)

    fun function(path: String, p0: String, p1: String, fn: HostFn2StringInt) =
        namespace(parentPath(path)).function(lastSegment(path), p0, p1, fn)

    fun function(path: String, paramNames: List<String>, fn: HostFnArgs) =
        namespace(parentPath(path)).function(lastSegment(path), paramNames, fn)

    fun functionRaw(path: String, minArgs: Int, maxArgs: Int, fn: HostRawFn) =
        namespace(parentPath(path)).functionRaw(lastSegment(path), minArgs, maxArgs, fn)

    fun constructor(path: String, paramNames: List<String>, fn: HostFnArgs) =
        namespace(parentPath(path)).constructor(lastSegment(path), paramNames, fn)

    fun property(path: String, value: Any?) = namespace(parentPath(path)).property(lastSegment(path), value)

    /* ---------------- JS → Kotlin 调用辅助（异常桥接反方向） ---------------- */

    /**
     * 执行 JS 代码并转换结果（自动带 JS_EVAL_RETVAL）。
     * JS 侧抛错时抛出 [JSBridgeScriptException]（携带 JS 错误 message/stack），
     * 宿主可 try/catch 捕获。
     */
    fun eval(code: String, filename: String = "<bridge>"): Any? {
        val result = JS_Eval(ctx, code, filename, JS_EVAL_RETVAL)
        if (JS_IsException(result)) throw pendingScriptException()
        return JSBridgeConvert.toKotlin(ctx, result)
    }

    /**
     * 调用 JS 函数并转换结果。参数经 [JSBridgeConvert.fromKotlin] 转换；
     * 转换与调用之间用 gcKeepAlive 保活，避免 GC 搬移导致值失效。
     */
    fun call(func: JSValue, args: List<Any?> = emptyList()): Any? {
        val keepBase = ctx.gcKeepAlive.size
        ctx.gcKeepAlive.add(func)
        try {
            for (a in args) {
                ctx.gcKeepAlive.add(JSBridgeConvert.fromKotlin(ctx, a))
            }
            /* 分配结束后从保活列表回读最新地址（GC 搬移时已自动更新） */
            val funcKept = ctx.gcKeepAlive[keepBase]
            val jsArgs = ArrayList<JSValue>(args.size)
            for (i in args.indices) jsArgs.add(ctx.gcKeepAlive[keepBase + 1 + i])
            val r = runtime.callFunction(funcKept, jsArgs)
            if (JS_IsException(r)) throw pendingScriptException()
            return JSBridgeConvert.toKotlin(ctx, r)
        } finally {
            while (ctx.gcKeepAlive.size > keepBase) ctx.gcKeepAlive.removeAt(ctx.gcKeepAlive.size - 1)
        }
    }

    /**
     * 读取当前挂起的 JS 异常并包装为 [JSBridgeScriptException]。
     * 错误对象布局：槽 0 = message、槽 1 = stack（见 JSContext.throwError）。
     */
    private fun pendingScriptException(): JSBridgeScriptException {
        val ex = JS_GetException(ctx)
        if (JS_IsError(ctx, ex)) {
            val ptr = JS_VALUE_TO_PTR(ex)
            val msg = ctx.memory.getJSValue(ptr + 24)
            val stack = ctx.memory.getJSValue(ptr + 32)
            val proto = ctx.memory.getJSValue(ptr + 8)
            return JSBridgeScriptException(
                errorNameFromProto(proto),
                if (JS_IsString(ctx, msg)) jsGetString(ctx, msg) else "",
                if (JS_IsString(ctx, stack)) jsGetString(ctx, stack) else null
            )
        }
        if (JS_IsString(ctx, ex)) {
            return JSBridgeScriptException(null, jsGetString(ctx, ex), null)
        }
        return JSBridgeScriptException(null, JSBridgeConvert.describeType(ctx, ex), null)
    }

    /** 由错误对象原型反查 JS 错误类名（Error/TypeError/...） */
    private fun errorNameFromProto(proto: JSValue): String? {
        if (proto == JS_NULL) return null
        for (e in JSObjectClassEnum.entries) {
            if (e.value < JSObjectClassEnum.JS_CLASS_ERROR.value) continue
            if (ctx.classProto[e.value] == proto) return e.jsErrorName()
        }
        return null
    }

    /**
     * 读取 JS 错误对象的 message（错误对象布局：槽 0 = message、槽 1 = stack，
     * 见 JSContext.throwError）。注意：本引擎的 message 存于对象 extra 槽而非
     * 普通属性，JS 代码无法直接 `e.message` 读取，宿主侧用本辅助获取。
     * 非错误对象返回 null。
     */
    fun errorMessage(v: JSValue): String? {
        if (!JS_IsError(ctx, v)) return null
        val msg = ctx.memory.getJSValue(JS_VALUE_TO_PTR(v) + 24)
        return if (JS_IsString(ctx, msg)) jsGetString(ctx, msg) else null
    }

    /** 读取 JS 错误对象的 stack 回溯（可能为 null） */
    fun errorStack(v: JSValue): String? {
        if (!JS_IsError(ctx, v)) return null
        val stack = ctx.memory.getJSValue(JS_VALUE_TO_PTR(v) + 32)
        return if (JS_IsString(ctx, stack)) jsGetString(ctx, stack) else null
    }

    /* ---------------- 宿主对象生命周期 ---------------- */

    /**
     * 创建包装 Kotlin 实例的宿主对象：JS 普通对象 + opaque 挂 [JSBridgeHostRef]，
     * 并确保 GC 侧的实例分发 finalizer 已注册。对象被 GC 回收时回调
     * [onFinalize]（禁止在其中调用 JS 函数）。
     * opaque 引用表按对象块地址维护，GC 搬移时随块更新，宿主引用始终正确。
     */
    fun newHostObject(instance: Any?, onFinalize: ((Any?) -> Unit)? = null, proto: JSValue = JS_NULL): JSValue {
        val obj = if (proto == JS_NULL) {
            JS_NewObject(ctx)
        } else {
            JS_NewObjectProtoClass(ctx, proto, JSObjectClassEnum.JS_CLASS_OBJECT.value, 0)
        }
        if (JS_IsException(obj)) {
            throw JSBridgeException(JSErrorKind.INTERNAL_ERROR, "创建宿主对象失败：内存不足")
        }
        JS_SetOpaque(ctx, obj, JSBridgeHostRef(instance, onFinalize))
        ensureHostFinalizerDispatch()
        return obj
    }

    /** 判断 JS 对象是否为桥接层宿主对象（opaque 为 [JSBridgeHostRef]） */
    fun isHostObject(v: JSValue): Boolean = JS_GetOpaque(ctx, v) is JSBridgeHostRef

    /** 读取宿主对象包装的 Kotlin 实例；非宿主对象返回 null */
    fun getHostObject(v: JSValue): Any? = (JS_GetOpaque(ctx, v) as? JSBridgeHostRef)?.instance

    /** 读取宿主对象实例并校验类型；非宿主对象或类型不符返回 null */
    fun <T : Any> getHostObject(v: JSValue, klass: Class<T>): T? {
        val instance = getHostObject(v) ?: return null
        return if (klass.isInstance(instance)) klass.cast(instance) else null
    }

    /** 注册 JS_CLASS_OBJECT 的实例分发 finalizer（幂等） */
    private fun ensureHostFinalizerDispatch() {
        val key = JSObjectClassEnum.JS_CLASS_OBJECT.value
        if (ctx.extFinalizerTable.containsKey(key)) return
        ctx.extFinalizerTable[key] = { _, opaque ->
            (opaque as? JSBridgeHostRef)?.runFinalize()
        }
    }

    /* ---------------- 注册内部实现（供 JSBridgeNamespace 调用） ---------------- */

    /** 在扩展函数表分配槽位并返回 funcIdx（无 JS 堆分配，无 GC 风险） */
    internal fun allocFuncIdx(def: JSCFunctionDef): Int {
        val idx = (ctx.cFunctionTable?.size ?: 0) + ctx.extFunctionTable.size
        ctx.extFunctionTable.add(def)
        return idx
    }

    /**
     * 宿主 lambda 统一调用包装：剥离构造器标志位 → 参数个数校验 →
     * 构造 JSBridgeCall → 调用并转换返回值；任何 Kotlin 异常转为 JS Error。
     */
    internal fun invokeHost(
        thisVal: JSValue, argc: Int, argv: LongArray,
        funcPath: String, paramNames: List<String>,
        minArgs: Int, maxArgs: Int, isCtor: Boolean, autoWrapHost: Boolean,
        adapter: (JSContext, JSValue, JSBridgeCall) -> Any?
    ): JSValue {
        try {
            val realArgc = argc and FRAME_CF_ARGC_MASK and FRAME_CF_CTOR.inv()
            if (realArgc < minArgs || (maxArgs >= 0 && realArgc > maxArgs)) {
                val expected = if (minArgs == maxArgs) "$minArgs" else
                    "$minArgs..${if (maxArgs < 0) "任意" else maxArgs}"
                throw JSBridgeException(
                    JSErrorKind.TYPE_ERROR,
                    "$funcPath 期望 $expected 个参数，实际收到 $realArgc 个"
                )
            }
            val call = JSBridgeCall(ctx, argv, realArgc, funcPath, paramNames)
            val result = adapter(ctx, thisVal, call)
            if (isCtor && autoWrapHost && result != null && result !is JSRawValue) {
                /* 构造器便捷变体：返回的 Kotlin 实例自动包装为宿主对象 */
                return newHostObject(result)
            }
            return JSBridgeConvert.fromKotlin(ctx, result)
        } catch (t: Throwable) {
            /* 异常桥接：任何宿主异常都转为 JS Error，不炸穿解释器调用栈 */
            val (kind, msg) = JSBridgeConvert.mapException(t, funcPath)
            return ctx.throwError(kind.classEnum, msg)
        }
    }

    /** 挂载值到指定父对象（带 GC 保活：key 分配可能触发搬移） */
    internal fun mountChild(parent: JSValue, name: String, value: JSValue) {
        val keepBase = ctx.gcKeepAlive.size
        ctx.gcKeepAlive.add(parent)
        ctx.gcKeepAlive.add(value)
        try {
            val key = JS_NewString(ctx, name)
            if (JS_IsException(key)) {
                throw JSBridgeException(JSErrorKind.INTERNAL_ERROR, "注册 '$name' 失败：内存不足")
            }
            val parentKept = ctx.gcKeepAlive[keepBase]
            val valueKept = ctx.gcKeepAlive[keepBase + 1]
            runtime.setProperty(parentKept, key, valueKept)
        } finally {
            while (ctx.gcKeepAlive.size > keepBase) ctx.gcKeepAlive.removeAt(ctx.gcKeepAlive.size - 1)
        }
    }

    internal companion object {
        /** 判断 JS 值是否为普通对象（复用为命名空间对象） */
        internal fun isPlainObject(ctx: JSContext, v: JSValue): Boolean {
            if (!JS_IsPtr(v)) return false
            val ptr = JS_VALUE_TO_PTR(v)
            return ctx.memory.getMTag(ptr) == JSMTags.JS_MTAG_OBJECT &&
                ctx.memory.getU8(ptr + 2) == JSObjectClassEnum.JS_CLASS_OBJECT.value
        }
    }
}

private fun JSObjectClassEnum.jsErrorName(): String = when (this) {
    JSObjectClassEnum.JS_CLASS_ERROR -> "Error"
    JSObjectClassEnum.JS_CLASS_EVAL_ERROR -> "EvalError"
    JSObjectClassEnum.JS_CLASS_RANGE_ERROR -> "RangeError"
    JSObjectClassEnum.JS_CLASS_REFERENCE_ERROR -> "ReferenceError"
    JSObjectClassEnum.JS_CLASS_SYNTAX_ERROR -> "SyntaxError"
    JSObjectClassEnum.JS_CLASS_TYPE_ERROR -> "TypeError"
    JSObjectClassEnum.JS_CLASS_URI_ERROR -> "URIError"
    JSObjectClassEnum.JS_CLASS_INTERNAL_ERROR -> "InternalError"
    else -> name
}

private fun parentPath(path: String): String {
    val idx = path.lastIndexOf('.')
    return if (idx <= 0) "" else path.substring(0, idx)
}

private fun lastSegment(path: String): String {
    val idx = path.lastIndexOf('.')
    return if (idx < 0) path else path.substring(idx + 1)
}

/**
 * 命名空间句柄：JS 普通对象 + 路径。对象地址不缓存——每次操作按路径
 * 从 globalObj 重新解析（读取操作不分配 JS 堆，地址始终有效），
 * 从根本上规避 GC 搬移导致的悬空 JSValue。
 */
class JSBridgeNamespace internal constructor(
    private val bridge: JSBridge,
    internal val segments: List<String>
) {
    private val ctx: JSContext get() = bridge.ctx

    /** 按路径从全局对象解析命名空间对象（全程只读，无 GC 风险） */
    internal fun resolveObj(): JSValue {
        val keepBase = ctx.gcKeepAlive.size
        try {
            var cur = ctx.globalObj
            ctx.gcKeepAlive.add(cur)
            for (seg in segments) {
                val key = JS_NewString(ctx, seg)
                if (JS_IsException(key)) {
                    throw JSBridgeException(JSErrorKind.INTERNAL_ERROR, "解析命名空间失败：内存不足")
                }
                cur = bridge.runtime.getProperty(ctx.gcKeepAlive.last(), key)
                if (!JSBridge.isPlainObject(ctx, cur)) {
                    throw JSBridgeException(
                        JSErrorKind.INTERNAL_ERROR,
                        "命名空间解析失败：'${segments.joinToString(".")}' 的 '$seg' 不是对象"
                    )
                }
                ctx.gcKeepAlive.add(cur)
            }
            return ctx.gcKeepAlive.last()
        } finally {
            while (ctx.gcKeepAlive.size > keepBase) ctx.gcKeepAlive.removeAt(ctx.gcKeepAlive.size - 1)
        }
    }

    /** 子命名空间（name 可含 "." 表示更深层级） */
    fun namespace(name: String): JSBridgeNamespace {
        val childSegments = name.split('.')
        for (seg in childSegments) {
            ensureChildNamespace(seg)
        }
        return JSBridgeNamespace(bridge, segments + childSegments)
    }

    /** 确保子命名空间对象存在：已存在普通对象则复用；同名函数/属性则报错 */
    internal fun ensureChildNamespace(seg: String) {
        val keepBase = ctx.gcKeepAlive.size
        try {
            val parent = resolveObj()
            ctx.gcKeepAlive.add(parent)
            val key = JS_NewString(ctx, seg)
            if (JS_IsException(key)) {
                throw JSBridgeException(JSErrorKind.INTERNAL_ERROR, "创建命名空间失败：内存不足")
            }
            val parentKept = ctx.gcKeepAlive[keepBase]
            val existing = bridge.runtime.getProperty(parentKept, key)
            when {
                JSBridge.isPlainObject(ctx, existing) -> return /* 复用 */
                !JS_IsUndefined(existing) -> throw JSBridgeException(
                    JSErrorKind.TYPE_ERROR,
                    "${fullPath()} 的 '$seg' 已存在且不是命名空间对象，无法创建子命名空间"
                )
                else -> {
                    val obj = JS_NewObject(ctx)
                    if (JS_IsException(obj)) {
                        throw JSBridgeException(JSErrorKind.INTERNAL_ERROR, "创建命名空间失败：内存不足")
                    }
                    /* JS_NewObject 可能触发 GC 搬移：从保活列表回读 parent 最新地址 */
                    val parentRefreshed = ctx.gcKeepAlive[keepBase]
                    bridge.mountChild(parentRefreshed, seg, obj)
                }
            }
        } finally {
            while (ctx.gcKeepAlive.size > keepBase) ctx.gcKeepAlive.removeAt(ctx.gcKeepAlive.size - 1)
        }
    }

    private fun fullPath(): String = segments.joinToString(".")

    /** 注册名（name 可含 "."，自动创建中间命名空间）→ (leaf 命名空间, 函数名) */
    private fun splitName(name: String): Pair<JSBridgeNamespace, String> {
        val idx = name.lastIndexOf('.')
        return if (idx < 0) {
            this to name
        } else {
            namespace(name.substring(0, idx)) to name.substring(idx + 1)
        }
    }

    /* ---------------- 普通函数注册 ---------------- */

    /** 0 参数函数 */
    fun function(name: String, fn: HostFn0) {
        register(name, emptyList()) { c, _, _ -> fn.invoke(c) }
    }

    /** 1 参数（string）函数 */
    fun function(name: String, p0: String, fn: HostFn1String) {
        register(name, listOf(p0)) { c, _, call -> fn.invoke(c, call.string(0)) }
    }

    /** 2 参数（string, int）函数——桥接层推荐签名形态 */
    fun function(name: String, p0: String, p1: String, fn: HostFn2StringInt) {
        register(name, listOf(p0, p1)) { c, _, call -> fn.invoke(c, call.string(0), call.int(1)) }
    }

    /** 通用形态函数：参数经 [JSBridgeCall] 读取（任意参数个数/类型） */
    fun function(name: String, paramNames: List<String>, fn: HostFnArgs) {
        register(name, paramNames) { c, _, call -> fn.invoke(c, call) }
    }

    /**
     * 原生形态函数：宿主直接处理 JSValue 并返回 [JSValue]
     * （高性能路径/可变参数；返回 JS_EXCEPTION 表示已挂起 JS 异常）。
     */
    fun functionRaw(name: String, minArgs: Int, maxArgs: Int, fn: HostRawFn) {
        register(name, emptyList(), minArgs, maxArgs) { c, thisVal, call ->
            JSRawValue(fn.invoke(c, thisVal, call))
        }
    }

    /* ---------------- 构造器注册 ---------------- */

    /**
     * 构造器（便捷变体）：`new Name(...)` 调用宿主 lambda，
     * 返回的非 null Kotlin 实例自动包装为宿主对象（返回 JSRawValue/null 时原样使用）。
     * 注意：本引擎 C 构造器不提供 prototype 挂载机制，实例方法建议用
     * 工厂函数 + 宿主对象属性挂载或 JS 侧组合，见桥接层文档。
     */
    fun constructor(name: String, paramNames: List<String>, fn: HostFnArgs) {
        register(name, paramNames, isCtor = true, autoWrapHost = true) { c, _, call -> fn.invoke(c, call) }
    }

    /** 构造器（原生变体）：直接返回 JSValue 作为 new 结果 */
    fun constructorRaw(name: String, minArgs: Int, maxArgs: Int, fn: HostRawFn) {
        register(name, emptyList(), minArgs, maxArgs, isCtor = true) { c, thisVal, call ->
            JSRawValue(fn.invoke(c, thisVal, call))
        }
    }

    /* ---------------- 属性绑定（近似）与数据属性 ---------------- */

    /** 写入数据属性（常量/配置快照等） */
    fun property(name: String, value: Any?) {
        val (leaf, propName) = splitName(name)
        val v = JSBridgeConvert.fromKotlin(ctx, value)
        /* 先保活再解析（解析含字符串分配，可能触发 GC 搬移） */
        val keepBase = ctx.gcKeepAlive.size
        ctx.gcKeepAlive.add(v)
        try {
            val leafObj = leaf.resolveObj()
            val vKept = ctx.gcKeepAlive[keepBase]
            bridge.mountChild(leafObj, propName, vKept)
        } finally {
            while (ctx.gcKeepAlive.size > keepBase) ctx.gcKeepAlive.removeAt(ctx.gcKeepAlive.size - 1)
        }
    }

    /**
     * 近似属性绑定（getter）：注册为 `get<Name>` 普通函数，JS 侧以
     * `obj.get<Name>()` 调用。本引擎未提供宿主级 get/set 访问器注册 API
     * （C 引擎内部 JS_PROP_GETSET 语义未公开、Kotlin 解释器亦未实现
     * OP_define_getter/setter），取舍说明见桥接层文档。
     */
    fun getter(name: String, fn: HostFn0) {
        function("get${name.capitalizeFirst()}", fn)
    }

    /**
     * 近似属性绑定（getter/setter 对）：注册为 `get<Name>`/`set<Name>`
     * 一对普通函数。
     */
    fun getterSetter(name: String, get: HostFn0, set: HostFnArgs) {
        getter(name, get)
        function("set${name.capitalizeFirst()}", listOf(name)) { c, args ->
            set.invoke(c, args)
            null
        }
    }

    private fun String.capitalizeFirst(): String =
        if (isEmpty()) this else substring(0, 1).uppercase() + substring(1)

    /* ---------------- 宿主对象挂载 ---------------- */

    /** 创建宿主对象并挂载为本命名空间属性，返回该 JS 对象 */
    fun hostObject(name: String, instance: Any?, onFinalize: ((Any?) -> Unit)? = null): JSValue {
        val obj = bridge.newHostObject(instance, onFinalize)
        val (leaf, propName) = splitName(name)
        /* 先保活再解析（解析含字符串分配，可能触发 GC 搬移） */
        val keepBase = ctx.gcKeepAlive.size
        ctx.gcKeepAlive.add(obj)
        try {
            val leafObj = leaf.resolveObj()
            val objKept = ctx.gcKeepAlive[keepBase]
            bridge.mountChild(leafObj, propName, objKept)
            return objKept
        } finally {
            while (ctx.gcKeepAlive.size > keepBase) ctx.gcKeepAlive.removeAt(ctx.gcKeepAlive.size - 1)
        }
    }

    /* ---------------- 注册核心 ---------------- */

    /**
     * 注册核心：创建 JSCFunctionDef → 分配扩展表槽位 → 创建函数对象 →
     * 挂载到命名空间。funcPath 按注册时的完整路径生成（含中间命名空间）。
     */
    private fun register(
        name: String,
        paramNames: List<String>,
        minArgs: Int = paramNames.size,
        maxArgs: Int = paramNames.size,
        isCtor: Boolean = false,
        autoWrapHost: Boolean = false,
        adapter: (JSContext, JSValue, JSBridgeCall) -> Any?
    ) {
        val (leaf, funcName) = splitName(name)
        val funcPath = (leaf.segments + funcName).joinToString(".")
        val bridgeRef = bridge
        val def = JSCFunctionDef(
            func = { c, thisVal, argc, argv ->
                bridgeRef.invokeHost(
                    thisVal, argc, argv, funcPath, paramNames,
                    minArgs, maxArgs, isCtor, autoWrapHost, adapter
                )
            },
            defType = if (isCtor) {
                JSCFunctionDefEnum.JS_CFUNC_constructor.value
            } else {
                JSCFunctionDefEnum.JS_CFUNC_generic.value
            },
            argCount = paramNames.size
        )
        val idx = bridge.allocFuncIdx(def)
        val funcObj = JS_NewCFunctionParams(ctx, idx, JS_NULL)
        if (JS_IsException(funcObj)) {
            throw JSBridgeException(JSErrorKind.INTERNAL_ERROR, "注册 '$funcPath' 失败：内存不足")
        }
        /* 解析叶命名空间含字符串分配（可能触发 GC 搬移）：先保活 funcObj，
         * 挂载时从保活列表回读最新地址 */
        val keepBase = ctx.gcKeepAlive.size
        ctx.gcKeepAlive.add(funcObj)
        try {
            val leafObj = leaf.resolveObj()
            val funcObjKept = ctx.gcKeepAlive[keepBase]
            bridge.mountChild(leafObj, funcName, funcObjKept)
        } finally {
            while (ctx.gcKeepAlive.size > keepBase) ctx.gcKeepAlive.removeAt(ctx.gcKeepAlive.size - 1)
        }
    }
}
