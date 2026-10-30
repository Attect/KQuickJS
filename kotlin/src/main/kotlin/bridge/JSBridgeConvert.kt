package app.muka.project.kquickjs.bridge

import app.muka.project.kquickjs.JSObjectClassEnum
import app.muka.project.kquickjs.JSMTags
import app.muka.project.kquickjs.JSValue
import app.muka.project.kquickjs.JSContext
import app.muka.project.kquickjs.JS_IsBool
import app.muka.project.kquickjs.JS_IsException
import app.muka.project.kquickjs.JS_IsInt
import app.muka.project.kquickjs.JS_IsNull
import app.muka.project.kquickjs.JS_IsNumber
import app.muka.project.kquickjs.JS_IsPtr
import app.muka.project.kquickjs.JS_IsShortFloat
import app.muka.project.kquickjs.JS_IsString
import app.muka.project.kquickjs.JS_IsUndefined
import app.muka.project.kquickjs.JS_NewArray
import app.muka.project.kquickjs.JS_NewBool
import app.muka.project.kquickjs.JS_NewFloat64
import app.muka.project.kquickjs.JS_NewInt32
import app.muka.project.kquickjs.JS_NewInt64
import app.muka.project.kquickjs.JS_NewObject
import app.muka.project.kquickjs.JS_NewString
import app.muka.project.kquickjs.JS_TRUE
import app.muka.project.kquickjs.JS_UNINITIALIZED
import app.muka.project.kquickjs.JS_VALUE_GET_INT
import app.muka.project.kquickjs.JS_VALUE_TO_PTR
import app.muka.project.kquickjs.JS_NULL
import app.muka.project.kquickjs.jsGetString
import app.muka.project.kquickjs.jsGetShortFloat
import app.muka.project.kquickjs.memory.getMTag
import app.muka.project.kquickjs.runtime.JSRuntime

/* =====================================================================
 * 桥接层异常类型与 JSValue ↔ Kotlin 双向类型转换层
 *
 * 设计要点：
 * - 全程不使用运行时反射（兼容 GraalVM Native Image），类型判断均为
 *   显式 when 分支与 ::class.simpleName（核心 API，无需注册即可用）；
 * - 严格转换策略：JS 值类型与目标 Kotlin 类型不匹配时抛 JSBridgeException，
 *   异常消息携带"函数路径 + 参数位置 + 参数名 + 期望类型 + 实际类型"，
 *   由 JSBridge 统一转为可定位的 JS TypeError/RangeError；
 * - 高频路径避免重复分配：数字读取直接从 JSValue 位模式取值，
 *   不经过通用 Any? 装箱路径；JS→Kotlin 转换全程不在 JS 堆分配，
 *   因此不会触发 GC 搬移，地址只需读取一次。
 * ===================================================================== */

/**
 * JS 错误种类：桥接层抛出的异常最终映射为对应原型链的 JS Error 对象。
 */
enum class JSErrorKind(val classEnum: JSObjectClassEnum, val jsName: String) {
    /** 通用 Error */
    ERROR(JSObjectClassEnum.JS_CLASS_ERROR, "Error"),

    /** TypeError：类型不匹配、参数个数错误等 */
    TYPE_ERROR(JSObjectClassEnum.JS_CLASS_TYPE_ERROR, "TypeError"),

    /** RangeError：数值越界、嵌套深度超限等 */
    RANGE_ERROR(JSObjectClassEnum.JS_CLASS_RANGE_ERROR, "RangeError"),

    /** ReferenceError：宿主引用失效等（预留） */
    REFERENCE_ERROR(JSObjectClassEnum.JS_CLASS_REFERENCE_ERROR, "ReferenceError"),

    /** InternalError：宿主内部错误 */
    INTERNAL_ERROR(JSObjectClassEnum.JS_CLASS_INTERNAL_ERROR, "InternalError")
}

/**
 * 桥接层内部异常：携带应映射的 JS 错误种类。
 * JSBridge 捕获后按 [kind] 转为对应 JS Error，不会炸穿解释器调用栈。
 */
class JSBridgeException(val kind: JSErrorKind, message: String) : RuntimeException(message)

/**
 * 反方向异常封装：宿主通过 [JSBridge.eval]/[JSBridge.call] 调用 JS 时，
 * 若 JS 侧抛出错误，桥接层把 JS 错误信息（错误类名、message、stack）包装为
 * 本异常抛出，宿主代码可以普通 try/catch 捕获处理。
 */
class JSBridgeScriptException(
    /** JS 错误对象的原型类名（Error/TypeError/...），非错误对象时为 null */
    val jsErrorName: String?,
    /** JS 错误的 message；非错误对象时为值的字符串描述 */
    val jsMessage: String,
    /** JS 错误的 stack 回溯（可能为 null） */
    val jsStack: String?
) : RuntimeException("JS 侧异常${if (jsErrorName != null) "($jsErrorName)" else ""}: $jsMessage")

/**
 * JSValue 在 Kotlin 容器（List/Map）中的原样包装。
 *
 * JSValue 是 Long 的 typealias，无法与普通 Kotlin Long 在运行期区分；
 * 通用转换（[JSBridgeConvert.toKotlin]）遇到非数据类 JS 值（函数、宿主对象、
 * 错误对象等）时，以本包装类保存原值，[JSBridgeConvert.fromKotlin] 识别后
 * 原样透传，避免被误当数字转换。
 */
class JSRawValue(val value: JSValue)

/**
 * 类型转换层：JSValue 与 Kotlin 类型（null/Boolean/Int/Long/Double/String/
 * ByteArray/List/Map）的双向转换。
 */
object JSBridgeConvert {

    /** 通用转换的最大递归深度，超出抛 RangeError（防御循环引用与栈溢出） */
    const val MAX_DEPTH: Int = 64

    private const val PREVIEW_LEN: Int = 32

    /* ==================== JSValue → Kotlin ==================== */

    /**
     * 通用转换（宽松语义，用于返回值/对象属性的自动转换）：
     * - null/undefined → null；boolean → Boolean；number → Int/Long/Double（见下）；
     * - string → String；数组 → List<Any?>；普通对象 → Map<String, Any?>；
     * - 函数/宿主对象/错误对象等非数据值 → [JSRawValue] 原样包装。
     * number 的转换规则：整数值 → Int（超范围 → Long → Double），非整数值 → Double。
     */
    fun toKotlin(ctx: JSContext, v: JSValue): Any? = toKotlinInternal(ctx, v, 0)

    private fun toKotlinInternal(ctx: JSContext, v: JSValue, depth: Int): Any? {
        if (depth > MAX_DEPTH) {
            throw JSBridgeException(JSErrorKind.RANGE_ERROR, "转换嵌套深度超过 $MAX_DEPTH")
        }
        when {
            JS_IsNull(v) || JS_IsUndefined(v) -> return null
            JS_IsBool(v) -> return v == JS_TRUE
            JS_IsInt(v) -> return JS_VALUE_GET_INT(v)
            JS_IsShortFloat(v) -> return numberToKotlin(jsGetShortFloat(v))
        }
        if (JS_IsPtr(v)) {
            val ptr = JS_VALUE_TO_PTR(v)
            return when (ctx.memory.getMTag(ptr)) {
                JSMTags.JS_MTAG_FLOAT64 -> numberToKotlin(ctx.memory.getFloat64(ptr + 8))
                JSMTags.JS_MTAG_STRING -> jsGetString(ctx, v)
                JSMTags.JS_MTAG_OBJECT -> {
                    when (ctx.memory.getU8(ptr + 2)) {
                        JSObjectClassEnum.JS_CLASS_ARRAY.value -> arrayToList(ctx, v, depth)
                        JSObjectClassEnum.JS_CLASS_OBJECT.value -> objectToMap(ctx, v, depth)
                        else -> JSRawValue(v) /* 函数/宿主对象/错误对象等：原样包装回传 */
                    }
                }
                else -> JSRawValue(v)
            }
        }
        return JSRawValue(v)
    }

    /** 数字到 Kotlin 的通用映射：整数值 → Int/Long，其余 → Double */
    private fun numberToKotlin(d: Double): Any? {
        if (d.isFinite() && d == kotlin.math.floor(d) &&
            d >= Int.MIN_VALUE && d <= Int.MAX_VALUE
        ) {
            return d.toInt()
        }
        if (d.isFinite() && d == kotlin.math.floor(d) &&
            d >= Long.MIN_VALUE && d <= Long.MAX_VALUE
        ) {
            return d.toLong()
        }
        return d
    }

    /** JS 数组 → List<Any?>（元素递归通用转换） */
    private fun arrayToList(ctx: JSContext, arr: JSValue, depth: Int): List<Any?> {
        val ptr = JS_VALUE_TO_PTR(arr)
        val length = ctx.memory.getI32(ptr + 32)
        if (length <= 0) return emptyList()
        val arrData = ctx.memory.getJSValue(ptr + 24)
        var dataPtr = 0
        var dataLen = 0
        if (arrData != JS_NULL && JS_IsPtr(arrData)) {
            dataPtr = JS_VALUE_TO_PTR(arrData)
            dataLen = ctx.memory.getI32(dataPtr + 4)
        }
        val out = ArrayList<Any?>(length)
        for (i in 0 until length) {
            val elem = if (i < dataLen) {
                ctx.memory.getJSValue(dataPtr + 8 + i * 8)
            } else {
                app.muka.project.kquickjs.JS_UNDEFINED
            }
            out.add(toKotlinInternal(ctx, elem, depth + 1))
        }
        return out
    }

    /**
     * JS 普通对象 → Map<String, Any?>：遍历自身属性（不含原型链），
     * key 转 String，value 递归通用转换。
     */
    private fun objectToMap(ctx: JSContext, obj: JSValue, depth: Int): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for ((key, value) in ownPropertyEntries(ctx, obj)) {
            out[key] = toKotlinInternal(ctx, value, depth + 1)
        }
        return out
    }

    /**
     * 枚举对象的自身属性（key 字符串 → JSValue）。
     * 属性表布局与 JSRuntime.setPropertyInternal 一致：
     * [0]=prop_count、[1]=hash_mask、其后 hash_table 与 key/val/next 三元组。
     * 仅在无 JS 堆分配的转换路径中调用（转换期间不会触发 GC 搬移）。
     */
    fun ownPropertyEntries(ctx: JSContext, obj: JSValue): List<Pair<String, JSValue>> {
        if (!JS_IsPtr(obj)) return emptyList()
        val objPtr = JS_VALUE_TO_PTR(obj)
        if (ctx.memory.getMTag(objPtr) != JSMTags.JS_MTAG_OBJECT) return emptyList()
        val props = ctx.memory.getJSValue(objPtr + 16)
        if (props == JS_NULL || props == ctx.emptyProps || !JS_IsPtr(props)) return emptyList()
        val propsPtr = JS_VALUE_TO_PTR(props)
        val size = ctx.memory.getI32(propsPtr + 4)
        val hashMask = JS_VALUE_GET_INT(ctx.memory.getJSValue(propsPtr + 16))
        val firstSlot = 2 + hashMask + 1
        val slotCount = (size - firstSlot) / 3
        if (slotCount <= 0) return emptyList()
        val out = ArrayList<Pair<String, JSValue>>(slotCount)
        for (slot in 0 until slotCount) {
            val propPtr = propsPtr + 8 + (firstSlot + slot * 3) * 8
            val key = ctx.memory.getJSValue(propPtr)
            if (key != JS_UNINITIALIZED && JS_IsString(ctx, key)) {
                out.add(jsGetString(ctx, key) to ctx.memory.getJSValue(propPtr + 8))
            }
        }
        return out
    }

    /* ---------------- 严格类型读取（参数绑定用） ---------------- */

    /**
     * 读取 string 参数：仅接受 JS string（严格策略，不做自动 toString，
     * 避免隐式转换歧义），失败抛带定位信息的 TypeError。
     * [what] 为定位描述，如 "platform.http.get 的参数 0 'url'"。
     */
    fun toJsString(ctx: JSContext, v: JSValue, what: String): String {
        if (JS_IsString(ctx, v)) return jsGetString(ctx, v)
        throw typeMismatch(what, "string", ctx, v)
    }

    /**
     * 读取 int 参数：接受 JS number 中整数值（含 short int 与整数值 float64），
     * 非整数、越界或非数字类型抛 TypeError。
     */
    fun toJsInt(ctx: JSContext, v: JSValue, what: String): Int {
        if (JS_IsInt(v)) return JS_VALUE_GET_INT(v)
        val d = toJsDouble(ctx, v, what)
        if (d != kotlin.math.floor(d) || d < Int.MIN_VALUE || d > Int.MAX_VALUE) {
            throw JSBridgeException(
                JSErrorKind.TYPE_ERROR,
                "$what 应为 number(int)，实际为 ${describeType(ctx, v)}（非整数或超出 int 范围）"
            )
        }
        return d.toInt()
    }

    /** 读取 long 参数：规则同 [toJsInt]，取值范围为 Long */
    fun toJsLong(ctx: JSContext, v: JSValue, what: String): Long {
        if (JS_IsInt(v)) return JS_VALUE_GET_INT(v).toLong()
        val d = toJsDouble(ctx, v, what)
        if (d != kotlin.math.floor(d) || d < Long.MIN_VALUE || d > Long.MAX_VALUE) {
            throw JSBridgeException(
                JSErrorKind.TYPE_ERROR,
                "$what 应为 number(long)，实际为 ${describeType(ctx, v)}（非整数或超出 long 范围）"
            )
        }
        return d.toLong()
    }

    /**
     * 读取 double 参数：接受任意 JS number（int/short float/float64），
     * 其他类型严格拒绝。
     */
    fun toJsDouble(ctx: JSContext, v: JSValue, what: String): Double {
        if (JS_IsInt(v)) return JS_VALUE_GET_INT(v).toDouble()
        if (JS_IsShortFloat(v)) return jsGetShortFloat(v)
        if (JS_IsNumber(ctx, v)) {
            val ptr = JS_VALUE_TO_PTR(v)
            if (ctx.memory.getMTag(ptr) == JSMTags.JS_MTAG_FLOAT64) {
                return ctx.memory.getFloat64(ptr + 8)
            }
        }
        throw typeMismatch(what, "number", ctx, v)
    }

    /** 读取 boolean 参数：仅接受 JS boolean（严格策略） */
    fun toJsBoolean(ctx: JSContext, v: JSValue, what: String): Boolean {
        if (JS_IsBool(v)) return v == JS_TRUE
        throw typeMismatch(what, "boolean", ctx, v)
    }

    /**
     * 读取 ByteArray 参数：接受元素为 0..255 整数的 JS 数组。
     * （Kotlin 运行时未实现 ArrayBuffer/TypedArray 语义，桥接层以 number 数组
     * 近似字节序列，见桥接层文档"类型映射表"）
     */
    fun toJsBytes(ctx: JSContext, v: JSValue, what: String): ByteArray {
        val list = toJsList(ctx, v, what)
        val out = ByteArray(list.size)
        for (i in list.indices) {
            val n = list[i]
            if (n is Int && n in 0..255) {
                out[i] = n.toByte()
            } else {
                throw JSBridgeException(
                    JSErrorKind.TYPE_ERROR,
                    "$what 应为 byte 数组，第 $i 个元素 ${describeValue(n)} 不在 0..255 范围"
                )
            }
        }
        return out
    }

    /** 读取 List 参数：接受 JS 数组，元素递归通用转换 */
    fun toJsList(ctx: JSContext, v: JSValue, what: String): List<Any?> {
        if (JS_IsPtr(v)) {
            val ptr = JS_VALUE_TO_PTR(v)
            if (ctx.memory.getMTag(ptr) == JSMTags.JS_MTAG_OBJECT &&
                ctx.memory.getU8(ptr + 2) == JSObjectClassEnum.JS_CLASS_ARRAY.value
            ) {
                return arrayToList(ctx, v, 0)
            }
        }
        throw typeMismatch(what, "array", ctx, v)
    }

    /** 读取 Map 参数：接受 JS 普通对象，属性递归通用转换 */
    fun toJsMap(ctx: JSContext, v: JSValue, what: String): Map<String, Any?> {
        if (JS_IsPtr(v)) {
            val ptr = JS_VALUE_TO_PTR(v)
            if (ctx.memory.getMTag(ptr) == JSMTags.JS_MTAG_OBJECT &&
                ctx.memory.getU8(ptr + 2) == JSObjectClassEnum.JS_CLASS_OBJECT.value
            ) {
                return objectToMap(ctx, v, 0)
            }
        }
        throw typeMismatch(what, "object", ctx, v)
    }

    /* ==================== Kotlin → JSValue ==================== */

    /**
     * 通用转换：Kotlin 值 → JSValue（宽松语义，用于宿主函数返回值）。
     * - null → null；Boolean → boolean；Int/Long/Float/Double → number；
     * - String → string；ByteArray → number 数组（元素 0..255）；
     * - List → JS 数组；Map → JS 普通对象（key 非 String 时取 toString()，文档化约定）；
     * - [JSRawValue] → 原样 JSValue；
     * - 其他类型 → TypeError（文档化的简化映射，宿主应先转换为上述类型）。
     * List/Map 递归深度受 [MAX_DEPTH] 保护。
     */
    fun fromKotlin(ctx: JSContext, value: Any?): JSValue {
        val runtime = JSRuntime(ctx)
        return fromKotlinInternal(ctx, runtime, value, 0)
    }

    private fun fromKotlinInternal(ctx: JSContext, runtime: JSRuntime, value: Any?, depth: Int): JSValue {
        if (depth > MAX_DEPTH) {
            throw JSBridgeException(JSErrorKind.RANGE_ERROR, "返回值嵌套深度超过 $MAX_DEPTH")
        }
        return when (value) {
            null -> JS_NULL
            is JSRawValue -> value.value
            is Boolean -> JS_NewBool(value)
            is Int -> JS_NewInt32(ctx, value)
            is Long -> JS_NewInt64(ctx, value)
            is Float -> JS_NewFloat64(ctx, value.toDouble())
            is Double -> JS_NewFloat64(ctx, value)
            is String -> JS_NewString(ctx, value)
            is ByteArray -> {
                val arr = JS_NewArray(ctx, value.size)
                if (JS_IsException(arr)) return arr
                for (i in value.indices) {
                    val elem = JS_NewInt32(ctx, value[i].toInt() and 0xFF)
                    runtime.setElement(arr, JS_NewInt32(ctx, i), elem)
                }
                arr
            }
            is List<*> -> {
                val arr = JS_NewArray(ctx, value.size)
                if (JS_IsException(arr)) return arr
                for (i in value.indices) {
                    val elem = fromKotlinInternal(ctx, runtime, value[i], depth + 1)
                    runtime.setElement(arr, JS_NewInt32(ctx, i), elem)
                }
                arr
            }
            is Map<*, *> -> {
                val obj = JS_NewObject(ctx)
                if (JS_IsException(obj)) return obj
                for ((k, v) in value) {
                    val key = k?.toString() ?: "null"
                    val jsKey = JS_NewString(ctx, key)
                    if (JS_IsException(jsKey)) return jsKey
                    val jsVal = fromKotlinInternal(ctx, runtime, v, depth + 1)
                    /* 属性写入走 runtime.setProperty（内部对 obj/key/val 做 GC 保活，
                     * 分配触发 GC 搬移时地址自动刷新） */
                    runtime.setProperty(obj, jsKey, jsVal)
                }
                obj
            }
            else -> throw JSBridgeException(
                JSErrorKind.TYPE_ERROR,
                "不支持的宿主返回类型 ${value::class.simpleName ?: value::class.java.name}：" +
                    "支持 null/Boolean/Int/Long/Double/Float/String/ByteArray/List/Map/JSRawValue"
            )
        }
    }

    /* ==================== 错误描述与异常映射 ==================== */

    /** 生成可定位的类型不匹配异常（TypeError） */
    fun typeMismatch(what: String, expected: String, ctx: JSContext, v: JSValue): JSBridgeException =
        JSBridgeException(
            JSErrorKind.TYPE_ERROR,
            "$what 应为 $expected，实际为 ${describeType(ctx, v)}"
        )

    /** 描述 JS 值的类型（错误消息用，带值预览） */
    fun describeType(ctx: JSContext, v: JSValue): String {
        return when {
            JS_IsNull(v) -> "null"
            JS_IsUndefined(v) -> "undefined"
            JS_IsBool(v) -> "boolean(${if (v == JS_TRUE) "true" else "false"})"
            JS_IsInt(v) -> "number(${JS_VALUE_GET_INT(v)})"
            JS_IsShortFloat(v) -> "number(${jsGetShortFloat(v)})"
            JS_IsPtr(v) -> {
                val ptr = JS_VALUE_TO_PTR(v)
                when (ctx.memory.getMTag(ptr)) {
                    JSMTags.JS_MTAG_FLOAT64 -> "number(${ctx.memory.getFloat64(ptr + 8)})"
                    JSMTags.JS_MTAG_STRING -> "string(${preview(jsGetString(ctx, v))})"
                    JSMTags.JS_MTAG_OBJECT -> {
                        when (ctx.memory.getU8(ptr + 2)) {
                            JSObjectClassEnum.JS_CLASS_ARRAY.value -> "array"
                            JSObjectClassEnum.JS_CLASS_C_FUNCTION.value,
                            JSObjectClassEnum.JS_CLASS_CLOSURE.value -> "function"
                            JSObjectClassEnum.JS_CLASS_ERROR.value -> "error object"
                            else -> "object"
                        }
                    }
                    else -> "value"
                }
            }
            else -> "value"
        }
    }

    /** 描述已转换为 Kotlin 的值（byte 数组元素校验等场景） */
    fun describeValue(v: Any?): String {
        return when (v) {
            null -> "null"
            is String -> "string(${preview(v)})"
            else -> "${v::class.simpleName ?: "value"}($v)"
        }
    }

    private fun preview(s: String): String {
        val flat = s.replace("\n", "\\n")
        return if (flat.length <= PREVIEW_LEN) "\"$flat\"" else "\"${flat.take(PREVIEW_LEN)}...\""
    }

    /**
     * Kotlin 异常 → JS 错误映射（简化映射，完整表格见桥接层文档）：
     * - JSBridgeException → 按其 kind；
     * - IndexOutOfBoundsException / ArithmeticException → RangeError；
     * - ClassCastException / NumberFormatException / IllegalArgumentException → TypeError；
     * - 其他 → Error。
     * 异常类名并入消息文本，便于 JS 侧定位来源。
     */
    fun mapException(t: Throwable, funcPath: String): Pair<JSErrorKind, String> {
        if (t is JSBridgeException) {
            return t.kind to (t.message ?: "$funcPath: 桥接层异常")
        }
        val kind = when (t) {
            is IndexOutOfBoundsException, is ArithmeticException -> JSErrorKind.RANGE_ERROR
            is ClassCastException, is NumberFormatException, is IllegalArgumentException ->
                JSErrorKind.TYPE_ERROR
            else -> JSErrorKind.ERROR
        }
        val exName = t::class.simpleName ?: t::class.java.name
        val msg = t.message
        val detail = if (msg.isNullOrEmpty()) exName else "$exName: $msg"
        return kind to "$funcPath: 宿主异常 - $detail"
    }
}
