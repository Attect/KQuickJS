package com.mquickjs

import com.mquickjs.memory.getBlockSize
import com.mquickjs.memory.getMTag
import com.mquickjs.memory.getGCMark

object JSGC {
    
    fun gc(ctx: JSContext, keepAtoms: Boolean = true) {
        gcMarkAll(ctx, keepAtoms)
        gcCompactHeap(ctx)
    }
    
    private fun gcMarkAll(ctx: JSContext, keepAtoms: Boolean) {
        val markStack = ArrayDeque<JSValue>()
        
        if (keepAtoms && ctx.atomTable.isNotEmpty()) {
            for (i in ctx.atomTable.indices) {
                val atomValue = ctx.atomTable[i]
                if (atomValue != JS_NULL && atomValue != 0L) {
                    gcMark(ctx, markStack, atomValue)
                }
            }
        }
        
        gcMark(ctx, markStack, ctx.currentException)
        gcMark(ctx, markStack, ctx.uniqueStrings)
        gcMark(ctx, markStack, ctx.emptyProps)
        gcMark(ctx, markStack, ctx.globalObj)
        gcMark(ctx, markStack, ctx.minusZero)
        
        for (i in ctx.classProto.indices) {
            gcMark(ctx, markStack, ctx.classProto[i])
            gcMark(ctx, markStack, ctx.classObj[i])
        }
        
        var sp = ctx.sp
        while (sp < ctx.memory.size) {
            val val1 = ctx.memory.getJSValue(sp)
            gcMark(ctx, markStack, val1)
            sp += JSW
        }
        
        var refOffset = ctx.topGCRef
        while (refOffset != 0) {
            val prev = ctx.memory.getI32(refOffset)
            val val1 = ctx.memory.getJSValue(refOffset + 8)
            gcMark(ctx, markStack, val1)
            refOffset = prev
        }
        
        refOffset = ctx.lastGCRef
        while (refOffset != 0) {
            val prev = ctx.memory.getI32(refOffset)
            val val1 = ctx.memory.getJSValue(refOffset + 8)
            gcMark(ctx, markStack, val1)
            refOffset = prev
        }
        
        if (ctx.parseState != null) {
            val ps = ctx.parseState!!
            gcMark(ctx, markStack, ps.sourceStr)
            gcMark(ctx, markStack, ps.filenameStr)
            gcMark(ctx, markStack, ps.tokenValue)
            gcMark(ctx, markStack, ps.curFunc)
            gcMark(ctx, markStack, ps.byteCode)
        }
        
        while (markStack.isNotEmpty()) {
            val val1 = markStack.removeLast()
            gcMarkFlush(ctx, markStack, val1)
        }
    }
    
    private fun gcMark(ctx: JSContext, markStack: ArrayDeque<JSValue>, val1: JSValue) {
        if (!JS_IsPtr(val1)) return
        
        val ptr = JS_VALUE_TO_PTR(val1)
        if (ptr < ctx.heapBase || ptr >= ctx.heapFree) return
        
        val gcMark = ctx.memory.getU8(ptr) and 1
        if (gcMark != 0) return
        
        ctx.memory.putU8(ptr, ctx.memory.getU8(ptr) or 1)
        
        val mtag = ctx.memory.getMTag(ptr)
        if (mtagHasReferences(mtag)) {
            markStack.addLast(val1)
        }
    }
    
    private fun mtagHasReferences(mtag: Int): Boolean {
        return when (mtag) {
            JSMTags.JS_MTAG_OBJECT,
            JSMTags.JS_MTAG_VALUE_ARRAY,
            JSMTags.JS_MTAG_VARREF,
            JSMTags.JS_MTAG_FUNCTION_BYTECODE -> true
            else -> false
        }
    }
    
    private fun gcMarkFlush(ctx: JSContext, markStack: ArrayDeque<JSValue>, val1: JSValue) {
        val ptr = JS_VALUE_TO_PTR(val1)
        val mtag = ctx.memory.getMTag(ptr)
        
        when (mtag) {
            JSMTags.JS_MTAG_OBJECT -> {
                val proto = ctx.memory.getJSValue(ptr + 8)
                val props = ctx.memory.getJSValue(ptr + 16)
                gcMark(ctx, markStack, proto)
                gcMark(ctx, markStack, props)
                
                val classId = ctx.memory.getU8(ptr + 3)
                val extraSize = ctx.memory.getU8(ptr + 2)
                
                when (classId) {
                    JSObjectClassEnum.JS_CLASS_CLOSURE.value -> {
                        val funcBytecode = ctx.memory.getJSValue(ptr + 24)
                        gcMark(ctx, markStack, funcBytecode)
                        for (i in 0 until extraSize - 1) {
                            val varRef = ctx.memory.getJSValue(ptr + 32 + i * 8)
                            gcMark(ctx, markStack, varRef)
                        }
                    }
                    JSObjectClassEnum.JS_CLASS_C_FUNCTION.value -> {
                        if (extraSize > 1) {
                            val params = ctx.memory.getJSValue(ptr + 24)
                            gcMark(ctx, markStack, params)
                        }
                    }
                    JSObjectClassEnum.JS_CLASS_ARRAY.value -> {
                        val tab = ctx.memory.getJSValue(ptr + 24)
                        gcMark(ctx, markStack, tab)
                    }
                }
            }
            JSMTags.JS_MTAG_VALUE_ARRAY -> {
                val size = ctx.memory.getI32(ptr + 4)
                for (i in 0 until size) {
                    val elem = ctx.memory.getJSValue(ptr + 8 + i * 8)
                    gcMark(ctx, markStack, elem)
                }
            }
            JSMTags.JS_MTAG_VARREF -> {
                val isDetached = ctx.memory.getU8(ptr + 2) and 1
                if (isDetached == 0) {
                    val value = ctx.memory.getJSValue(ptr + 16)
                    gcMark(ctx, markStack, value)
                }
            }
            JSMTags.JS_MTAG_FUNCTION_BYTECODE -> {
                val funcName = ctx.memory.getJSValue(ptr + 8)
                val byteCode = ctx.memory.getJSValue(ptr + 16)
                val cpool = ctx.memory.getJSValue(ptr + 24)
                val vars = ctx.memory.getJSValue(ptr + 32)
                val extVars = ctx.memory.getJSValue(ptr + 40)
                val filename = ctx.memory.getJSValue(ptr + 48)
                val pc2line = ctx.memory.getJSValue(ptr + 56)
                
                gcMark(ctx, markStack, funcName)
                gcMark(ctx, markStack, byteCode)
                gcMark(ctx, markStack, cpool)
                gcMark(ctx, markStack, vars)
                gcMark(ctx, markStack, extVars)
                gcMark(ctx, markStack, filename)
                gcMark(ctx, markStack, pc2line)
            }
        }
    }
    
    private fun gcCompactHeap(ctx: JSContext) {
        gcThreadPointer(ctx, ctx.uniqueStrings)
        gcThreadPointer(ctx, ctx.emptyProps)
        gcThreadPointer(ctx, ctx.globalObj)
        gcThreadPointer(ctx, ctx.minusZero)
        gcThreadPointer(ctx, ctx.currentException)
        
        for (i in ctx.classProto.indices) {
            gcThreadPointer(ctx, ctx.classProto[i])
            gcThreadPointer(ctx, ctx.classObj[i])
        }
        
        var sp = ctx.sp
        while (sp < ctx.memory.size) {
            val val1 = ctx.memory.getJSValue(sp)
            gcThreadPointer(ctx, val1)
            sp += JSW
        }
        
        var refOffset = ctx.topGCRef
        while (refOffset != 0) {
            val prev = ctx.memory.getI32(refOffset)
            gcThreadPointer(ctx, refOffset + 8)
            refOffset = prev
        }
        
        refOffset = ctx.lastGCRef
        while (refOffset != 0) {
            val prev = ctx.memory.getI32(refOffset)
            gcThreadPointer(ctx, refOffset + 8)
            refOffset = prev
        }
        
        if (ctx.parseState != null) {
            val ps = ctx.parseState!!
            gcThreadPointer(ctx, ps.sourceStr)
            gcThreadPointer(ctx, ps.filenameStr)
            gcThreadPointer(ctx, ps.tokenValue)
            gcThreadPointer(ctx, ps.curFunc)
            gcThreadPointer(ctx, ps.byteCode)
        }
        
        var newPtr = ctx.heapBase
        var ptr = ctx.heapBase
        
        while (ptr < ctx.heapFree) {
            gcUpdateThreadedPointers(ctx, ptr, newPtr)
            val size = ctx.memory.getBlockSize(ptr)
            val mtag = ctx.memory.getMTag(ptr)
            
            if (mtag != JSMTags.JS_MTAG_FREE) {
                gcThreadBlock(ctx, ptr)
                newPtr += size
            }
            ptr += size
        }
        
        newPtr = ctx.heapBase
        ptr = ctx.heapBase
        
        while (ptr < ctx.heapFree) {
            gcUpdateThreadedPointers(ctx, ptr, newPtr)
            val size = ctx.memory.getBlockSize(ptr)
            val mtag = ctx.memory.getMTag(ptr)
            
            if (mtag != JSMTags.JS_MTAG_FREE) {
                if (newPtr != ptr) {
                    ctx.memory.copy(ptr, newPtr, size)
                }
                newPtr += size
            }
            ptr += size
        }
        
        ctx.heapFree = newPtr
        
        ptr = ctx.heapBase
        while (ptr < ctx.heapFree) {
            val size = ctx.memory.getBlockSize(ptr)
            val mtag = ctx.memory.getMTag(ptr)
            
            if (mtag == JSMTags.JS_MTAG_OBJECT) {
                gcClearMark(ctx, ptr)
            }
            ptr += size
        }
    }
    
    private fun gcThreadPointer(ctx: JSContext, offset: Int) {
        val val1 = ctx.memory.getJSValue(offset)
        if (JS_IsPtr(val1)) {
            val ptr = JS_VALUE_TO_PTR(val1)
            if (ptr >= ctx.heapBase && ptr < ctx.heapFree) {
                ctx.memory.putJSValue(offset, ptr.toLong())
            }
        }
    }
    
    private fun gcThreadPointer(ctx: JSContext, val1: JSValue): JSValue {
        if (JS_IsPtr(val1)) {
            val ptr = JS_VALUE_TO_PTR(val1)
            if (ptr >= ctx.heapBase && ptr < ctx.heapFree) {
                return ptr.toLong()
            }
        }
        return val1
    }
    
    private fun gcThreadBlock(ctx: JSContext, ptr: Int) {
        val mtag = ctx.memory.getMTag(ptr)
        
        when (mtag) {
            JSMTags.JS_MTAG_OBJECT -> {
                val protoOffset = ptr + 8
                val propsOffset = ptr + 16
                
                var proto = ctx.memory.getJSValue(protoOffset)
                var props = ctx.memory.getJSValue(propsOffset)
                
                proto = gcThreadPointer(ctx, proto)
                props = gcThreadPointer(ctx, props)
                
                ctx.memory.putJSValue(protoOffset, proto)
                ctx.memory.putJSValue(propsOffset, props)
                
                val classId = ctx.memory.getU8(ptr + 3)
                val extraSize = ctx.memory.getU8(ptr + 2)
                
                when (classId) {
                    JSObjectClassEnum.JS_CLASS_CLOSURE.value -> {
                        var funcBytecode = ctx.memory.getJSValue(ptr + 24)
                        funcBytecode = gcThreadPointer(ctx, funcBytecode)
                        ctx.memory.putJSValue(ptr + 24, funcBytecode)
                        
                        for (i in 0 until extraSize - 1) {
                            var varRef = ctx.memory.getJSValue(ptr + 32 + i * 8)
                            varRef = gcThreadPointer(ctx, varRef)
                            ctx.memory.putJSValue(ptr + 32 + i * 8, varRef)
                        }
                    }
                    JSObjectClassEnum.JS_CLASS_ARRAY.value -> {
                        var tab = ctx.memory.getJSValue(ptr + 24)
                        tab = gcThreadPointer(ctx, tab)
                        ctx.memory.putJSValue(ptr + 24, tab)
                    }
                }
            }
            JSMTags.JS_MTAG_VALUE_ARRAY -> {
                val size = ctx.memory.getI32(ptr + 4)
                for (i in 0 until size) {
                    val elemOffset = ptr + 8 + i * 8
                    var elem = ctx.memory.getJSValue(elemOffset)
                    elem = gcThreadPointer(ctx, elem)
                    ctx.memory.putJSValue(elemOffset, elem)
                }
            }
            JSMTags.JS_MTAG_VARREF -> {
                val isDetached = ctx.memory.getU8(ptr + 2) and 1
                if (isDetached == 0) {
                    val valueOffset = ptr + 16
                    var value = ctx.memory.getJSValue(valueOffset)
                    value = gcThreadPointer(ctx, value)
                    ctx.memory.putJSValue(valueOffset, value)
                }
            }
            JSMTags.JS_MTAG_FUNCTION_BYTECODE -> {
                val offsets = intArrayOf(8, 16, 24, 32, 40, 48, 56)
                for (offset in offsets) {
                    val valOffset = ptr + offset
                    var val1 = ctx.memory.getJSValue(valOffset)
                    val1 = gcThreadPointer(ctx, val1)
                    ctx.memory.putJSValue(valOffset, val1)
                }
            }
        }
    }
    
    private fun gcUpdateThreadedPointers(ctx: JSContext, ptr: Int, newPtr: Int) {
        val mtag = ctx.memory.getMTag(ptr)
        if (mtag == JSMTags.JS_MTAG_FREE) return
        
        val offset = newPtr - ptr
        
        when (mtag) {
            JSMTags.JS_MTAG_OBJECT -> {
                gcUpdatePointer(ctx, ptr + 8, offset)
                gcUpdatePointer(ctx, ptr + 16, offset)
                
                val classId = ctx.memory.getU8(ptr + 3)
                val extraSize = ctx.memory.getU8(ptr + 2)
                
                when (classId) {
                    JSObjectClassEnum.JS_CLASS_CLOSURE.value -> {
                        gcUpdatePointer(ctx, ptr + 24, offset)
                        for (i in 0 until extraSize - 1) {
                            gcUpdatePointer(ctx, ptr + 32 + i * 8, offset)
                        }
                    }
                    JSObjectClassEnum.JS_CLASS_ARRAY.value -> {
                        gcUpdatePointer(ctx, ptr + 24, offset)
                    }
                }
            }
            JSMTags.JS_MTAG_VALUE_ARRAY -> {
                val size = ctx.memory.getI32(ptr + 4)
                for (i in 0 until size) {
                    gcUpdatePointer(ctx, ptr + 8 + i * 8, offset)
                }
            }
            JSMTags.JS_MTAG_VARREF -> {
                val isDetached = ctx.memory.getU8(ptr + 2) and 1
                if (isDetached == 0) {
                    gcUpdatePointer(ctx, ptr + 16, offset)
                }
            }
            JSMTags.JS_MTAG_FUNCTION_BYTECODE -> {
                val offsets = intArrayOf(8, 16, 24, 32, 40, 48, 56)
                for (off in offsets) {
                    gcUpdatePointer(ctx, ptr + off, offset)
                }
            }
        }
    }
    
    private fun gcUpdatePointer(ctx: JSContext, offset: Int, delta: Int) {
        val val1 = ctx.memory.getJSValue(offset)
        if (val1 in ctx.heapBase..<(ctx.heapFree + delta)) {
            ctx.memory.putJSValue(offset, val1 + delta)
        }
    }
    
    private fun gcClearMark(ctx: JSContext, ptr: Int) {
        val header = ctx.memory.getU8(ptr)
        ctx.memory.putU8(ptr, header and 0xFE)
    }
}
