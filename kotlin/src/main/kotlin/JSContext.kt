package com.mquickjs

import com.mquickjs.memory.Memory
import com.mquickjs.memory.getBlockSize
import com.mquickjs.memory.getMTag

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
    
    var currentException: JSValue = JS_UNDEFINED
    var uniqueStrings: JSValue = JS_NULL
    var emptyProps: JSValue = JS_NULL
    var globalObj: JSValue = JS_NULL
    var minusZero: JSValue = JS_NULL
    
    val classProto: LongArray = LongArray(classCount)
    val classObj: LongArray = LongArray(classCount)
    
    var interruptHandler: ((ctx: JSContext) -> Int)? = null
    var writeFunc: ((buf: ByteArray, len: Int) -> Unit)? = null
    var opaque: Any? = null
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
        // TODO: Implement garbage collection
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
    
    fun throwError(errorClass: JSObjectClassEnum, message: String): JSValue {
        // TODO: Implement error creation
        currentException = JS_NULL
        return JS_EXCEPTION
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

class JSParseState
