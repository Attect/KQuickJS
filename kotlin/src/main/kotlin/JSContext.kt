package app.muka.project.kquickjs

import app.muka.project.kquickjs.memory.Memory
import app.muka.project.kquickjs.memory.getBlockSize
import app.muka.project.kquickjs.memory.getMTag
import app.muka.project.kquickjs.parser.JSParseState

class JSContext(
    val memory: Memory,
    val classCount: Int,
    val atomTable: LongArray,
    val cFunctionTable: Array<JSCFunctionDef>? = null,
    val cFinalizerTable: Array<((ctx: JSContext, opaque: Any?) -> Unit)?>? = null
) {
    var heapBase: Int = 0
    var heapFree: Int = 0
    var stackTop: Int = memory.size
    var stackBottom: Int = memory.size
    var sp: Int = memory.size
    var fp: Int = memory.size
    var minFreeSize: Int = JS_MIN_FREE_SIZE
    var inOutOfMemory: Boolean = false
    var currentExceptionIsUncatchable: Boolean = false
    var interruptCounter: Int = JS_INTERRUPT_COUNTER_INIT
    var uniqueStringsLen: Int = 0
    var jsCallRecCount: Int = 0
    
    var topGCRef: Int = 0
    var lastGCRef: Int = 0
    
    var currentException: JSValue = JS_UNINITIALIZED
    var uniqueStrings: JSValue = JS_NULL
    var emptyProps: JSValue = JS_NULL
    var globalObj: JSValue = JS_NULL
    var minusZero: JSValue = JS_NULL
    
    val classProto: LongArray = LongArray(classCount)
    val classObj: LongArray = LongArray(classCount)
    
    var interruptHandler: ((ctx: JSContext) -> Int)? = null
    var writeFunc: ((buf: ByteArray, len: Int) -> Unit)? = null
    var opaque: Any? = null
    /* 用户对象的宿主 opaque 引用表：key 为对象块地址，GC 搬移时随块更新
     * （对照 C 版 p->u.user.opaque 的 per-object 语义，修复原先错存 ctx.opaque 的问题） */
    val userOpaqueTable: HashMap<Int, Any?> = HashMap()
    /* 桥接层扩展函数表：cFunctionTable 定长表之后的追加槽，供宿主桥接层在
     * JS_NewContext 之后动态注册函数（突破"创建后不可扩"的定长表限制）。
     * callCFunction 在 funcIdx 超出基础表范围时，按 (funcIdx - 基础表长度) 偏移查找 */
    val extFunctionTable: MutableList<JSCFunctionDef> = mutableListOf()
    /* 桥接层扩展 finalizer 表：classId → finalizer，供桥接层运行期动态注册。
     * - user 类（classId >= JS_CLASS_USER）：与 cFinalizerTable 等效的动态注册途径；
     * - 内置类（classId < JS_CLASS_USER，如 JS_CLASS_OBJECT）：桥接层宿主对象的
     *   实例分发 finalizer（GC 回收时按 opaque 中的宿主引用分发清理动作）。
     * GC 调用约定与 cFinalizerTable 相同：finalizer 内禁止调用 JS 函数 */
    val extFinalizerTable: HashMap<Int, ((ctx: JSContext, opaque: Any?) -> Unit)> = HashMap()
    /* JS 调用栈记录（最内层在末尾）：Kotlin 无 C 的 fp 帧链，
     * 由 JSRuntime.run 进入/退出时维护，用于生成错误对象的 stack 回溯 */
    val jsCallStack: ArrayDeque<String> = ArrayDeque()
    /* 原生代码分配期间的临时 GC 保活列表：JSGC 的 mark 与压缩根更新会遍历它。
     * 注意不要用 pushValue/popValue（ctx.sp）保活——解释器运行中 ctx.sp 与
     * 局部缓存的 sp 不同步，会破坏执行栈 */
    val gcKeepAlive: MutableList<JSValue> = mutableListOf()
    /* Function.prototype.bind 的绑定信息：每项为 gcKeepAlive 槽索引
     * [target, thisArg, firstBoundArg, boundArgCount]（槽位永久保活并由 GC 更新） */
    val boundFns: MutableList<IntArray> = mutableListOf()
    /* 当前 C 函数调用的实参保活基址（-1 表示不在 C 调用中）。
     * callCFunction 会把 this+args 放入 gcKeepAlive，原生函数通过 ArgsView
     * 逐次读取最新地址，避免回调内分配触发 GC 后参数悬垂 */
    var cArgBase: Int = -1
    /* 长期 JVM 根（bind 绑定信息等；随上下文存活，不随调用帧弹出） */
    val persistentRoots: MutableList<JSValue> = mutableListOf()
    
    /* 解释器调用帧：func 在 gcKeepAlive 的槽位 + 当前字节码 pc（用于错误回溯） */
    class JSFrame(val keep: Int, var pc: Int)
    val frames: MutableList<JSFrame> = mutableListOf()
    /* 函数字节码指针 → pc2line 稀疏表 [pc,packed,...]（GC 搬移时同步更新键） */
    val pc2lineMap: MutableMap<Int, IntArray> = HashMap()
    
    /* 读取函数字节码 +56 的 pc→(line<<16|col) 稀疏表，二分查找不超过 pc 的最近位置 */
    fun pcToLineCol(funcPtr: Int, pc: Int): Int {
        val arr = pc2lineMap[funcPtr] ?: return (1 shl 16) or 1
        val pairs = arr.size / 2
        var lo = 0
        var hi = pairs - 1
        var best = (1 shl 16) or 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (arr[mid * 2] <= pc) {
                best = arr[mid * 2 + 1]
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return best
    }
    
    /* 生成错误回溯字符串（对照 C 的 build_backtrace）：最内层帧在前 */
    fun buildBacktrace(): String {
        val sb = StringBuilder()
        for (i in frames.indices.reversed()) {
            val f = frames[i]
            if (f.keep < 0 || f.keep >= gcKeepAlive.size) continue
            val fn = gcKeepAlive[f.keep]
            if (!JS_IsPtr(fn)) continue
            val p = JS_VALUE_TO_PTR(fn)
            if (p < heapBase || p >= memory.size) continue
            if (memory.getMTag(p) != JSMTags.JS_MTAG_FUNCTION_BYTECODE) continue
            val nameV = memory.getJSValue(p + 8)
            val name = if (JS_IsString(this, nameV)) jsGetString(this, nameV) else "<anonymous>"
            val fileV = memory.getJSValue(p + 48)
            val file = if (JS_IsString(this, fileV)) jsGetString(this, fileV) else "<eval>"
            val packed = pcToLineCol(p, f.pc)
            sb.append("    at ").append(name).append(" (").append(file)
                .append(':').append(packed ushr 16).append(':').append(packed and 0xFFFF)
                .append(")\n")
        }
        return sb.toString().trimEnd()
    }
    /* 闭包捕获变量槽池：编译期被内层函数引用的外层局部变量提升为池槽，
     * 外层与内层统一通过槽读写（对照 C JSVarRef 的 detached 近似语义，
     * 槽值生命周期与上下文一致）。JSGC 作为根遍历 */
    val varrefPool: MutableList<JSValue> = mutableListOf()
    /* 解释器 JVM 侧根：runBody 的操作数栈与局部变量表是 Kotlin 集合，GC 无法
     * 自动发现其中的 JSValue；由 runBody 注册后，JSGC 负责标记这些值，并在
     * 压缩搬移时就地改写集合元素（否则执行期触发 GC 会留下悬垂地址） */
    val jvmRoots: MutableList<MutableList<JSValue>> = mutableListOf()
    var randomState: Long = 1
    
    var parseState: JSParseState? = null
    
    val stringPosCache: Array<JSStringPosCacheEntry> = Array(JS_STRING_POS_CACHE_SIZE) { JSStringPosCacheEntry() }
    var stringPosCacheCounter: Int = 0
    
    inner class JSStringPosCacheEntry {
        var str: JSValue = JS_NULL
        var strPos0: Int = 0
        var strPos1: Int = 0
    }
    
    fun pushGCRef(ref: JSGCRef): Int {
        ref.prev = topGCRef
        topGCRef = ref.offset
        ref.value = JS_UNDEFINED
        return ref.offset + 8
    }
    
    fun popGCRef(ref: JSGCRef): JSValue {
        topGCRef = ref.prev
        return ref.value
    }
    
    fun stackCheck(len: Int): Boolean {
        val newStackBottom = sp - len - JS_STACK_SLACK
        if (checkFreeMem(newStackBottom, len * JSW)) {
            return false
        }
        stackBottom = newStackBottom
        return true
    }
    
    fun checkFreeMem(stackBottom: Int, size: Int): Boolean {
        if ((stackBottom - heapFree) < size + minFreeSize) {
            gc()
            if ((stackBottom - heapFree) < size + minFreeSize) {
                throwOutOfMemory()
                return false
            }
        }
        return true
    }
    
    fun malloc(size: Int, mtag: Int): Int {
        if (size == 0) return 0
        val alignedSize = (size + JSW - 1) and (JSW - 1).inv()
        
        if (!checkFreeMem(stackBottom, alignedSize)) {
            return 0
        }
        
        val ptr = heapFree
        heapFree += alignedSize
        
        memory.putU8(ptr, (mtag shl 1))
        memory.putU8(ptr + 1, 0)
        
        return ptr
    }
    
    fun mallocz(size: Int, mtag: Int): Int {
        val ptr = malloc(size, mtag)
        if (ptr == 0) return 0
        if (size > 4) {
            memory.fill(ptr + 4, 0, size - 4)
        }
        return ptr
    }
    
    fun free(ptr: Int) {
        if (ptr == 0) return
        val size = memory.getBlockSize(ptr)
        val ptr1 = ptr + size
        if (ptr1 == heapFree) {
            heapFree = ptr
        }
    }
    
    fun shrink(ptr: Int, newSize: Int): Int {
        if (ptr == 0) return 0
        val alignedNewSize = (newSize + JSW - 1) and (JSW - 1).inv()
        if (alignedNewSize == 0) {
            free(ptr)
            return 0
        }
        val oldSize = memory.getBlockSize(ptr)
        if (alignedNewSize >= oldSize) return ptr
        
        val diff = oldSize - alignedNewSize
        if (diff > 0) {
            setFreeBlock(ptr + alignedNewSize, diff)
        }
        return ptr
    }
    
    fun setFreeBlock(ptr: Int, size: Int) {
        memory.putU8(ptr, (JSMTags.JS_MTAG_FREE shl 1))
        memory.putU8(ptr + 1, 0)
        memory.putU32(ptr + 4, ((size - 8) / JSW).toLong())
    }
    
    fun gc() {
        JSGC.gc(this, true)
    }
    
    fun throwOutOfMemory(): JSValue {
        if (inOutOfMemory) {
            currentException = JS_NULL
            return JS_EXCEPTION
        }
        inOutOfMemory = true
        minFreeSize = JS_MIN_FREE_SIZE - 256
        val ex = throwError(JSObjectClassEnum.JS_CLASS_INTERNAL_ERROR, "out of memory")
        inOutOfMemory = false
        minFreeSize = JS_MIN_FREE_SIZE
        return ex
    }
    
    /* 抛出错误对象（对照 C JS_ThrowError）：
     * 创建 class_id = JS_CLASS_ERROR 的对象，proto 取具体错误类的 class_proto，
     * extra 槽 0 = message 字符串、槽 1 = stack 回溯（build_backtrace 的 Kotlin 等价物，
     * 基于 jsCallStack 维护的调用栈记录；栈为空时 stack = JS_NULL） */
    fun throwError(errorClass: JSObjectClassEnum, message: String): JSValue {
        val msg = JS_NewString(this, message)
        if (JS_IsException(msg)) return JS_EXCEPTION
        
        /* 分配期间的值保活走 gcKeepAlive（不碰 ctx.sp，避免与解释器栈冲突） */
        gcKeepAlive.add(msg)
        val errorObj = JS_NewObjectProtoClass(this, classProto[errorClass.value],
                                              JSObjectClassEnum.JS_CLASS_ERROR.value, 2)
        if (JS_IsException(errorObj)) {
            gcKeepAlive.removeAt(gcKeepAlive.size - 1)
            return JS_EXCEPTION
        }
        gcKeepAlive.add(errorObj)
        
        /* 生成 stack 回溯字符串（对照 C 的 build_backtrace：帧 pc → 源码行列） */
        val stackStr = buildBacktrace()
        val stackVal = if (stackStr.isEmpty()) JS_NULL else {
            val sv = JS_NewString(this, stackStr)
            if (JS_IsException(sv)) JS_NULL else sv
        }
        
        /* LIFO 取回最新地址（分配期间 GC 搬移后列表中的值已被 remap 更新） */
        val errorObjKept = gcKeepAlive.removeAt(gcKeepAlive.size - 1)
        val msgFinal = gcKeepAlive.removeAt(gcKeepAlive.size - 1)
        val ptr = JS_VALUE_TO_PTR(errorObjKept)
        memory.putJSValue(ptr + 24, msgFinal)      /* 槽 0: message */
        memory.putJSValue(ptr + 32, stackVal)      /* 槽 1: stack */
        return JS_Throw(this, errorObjKept)
    }
    
    fun getAtom(a: Int): JSValue {
        return JS_VALUE_FROM_PTR(atomTable[a].toInt())
    }
    
    fun pushValue(value: JSValue) {
        sp -= JSW
        memory.putJSValue(sp, value)
    }
    
    fun popValue(): JSValue {
        val value = memory.getJSValue(sp)
        sp += JSW
        return value
    }
    
    fun peekValue(offset: Int = 0): JSValue {
        return memory.getJSValue(sp + offset * JSW)
    }
    
    fun pushArg(value: JSValue) {
        pushValue(value)
    }
}

class JSGCRef(val ctx: JSContext, val offset: Int = 0) {
    var prev: Int = 0
    var value: JSValue = JS_UNDEFINED
}
