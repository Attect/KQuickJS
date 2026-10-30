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
    /* JS 调用栈记录（最内层在末尾）：Kotlin 无 C 的 fp 帧链，
     * 由 JSRuntime.run 进入/退出时维护，用于生成错误对象的 stack 回溯 */
    val jsCallStack: ArrayDeque<String> = ArrayDeque()
    /* 原生代码分配期间的临时 GC 保活列表：JSGC 的 mark 与压缩根更新会遍历它。
     * 注意不要用 pushValue/popValue（ctx.sp）保活——解释器运行中 ctx.sp 与
     * 局部缓存的 sp 不同步，会破坏执行栈 */
    val gcKeepAlive: MutableList<JSValue> = mutableListOf()
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
        
        /* 生成 stack 回溯字符串（build_backtrace 的 Kotlin 等价物，基于 jsCallStack） */
        val stackStr = jsCallStack.joinToString("\n") { "    at $it" }
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
