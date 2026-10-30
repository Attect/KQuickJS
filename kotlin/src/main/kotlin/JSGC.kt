package app.muka.project.kquickjs

import app.muka.project.kquickjs.memory.getBlockSize
import app.muka.project.kquickjs.memory.getMTag
import app.muka.project.kquickjs.memory.getGCMark

object JSGC {
    
    fun gc(ctx: JSContext, keepAtoms: Boolean = true) {
        gcMarkAll(ctx, keepAtoms)
        gcFreeUnmarked(ctx)
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
        
        /* 原生代码分配期间的临时保活值 */
        for (v in ctx.gcKeepAlive) {
            gcMark(ctx, markStack, v)
        }
        
        /* 闭包捕获变量槽池（对照 C 的 varref 对象图） */
        for (v in ctx.varrefPool) {
            gcMark(ctx, markStack, v)
        }
        
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
    
    /* 释放未标记块（对照 C gc_mark_all 的释放阶段，含上游 #71 修复语义）：
     * - 存活块：清除标记位（对所有 mtag 生效，对齐 C 的 b->gc_mark = 0）
     * - 死块：遍历连续未标记区域，对其中每个用户对象块调用 finalizer
     *   （修复前 C 实现只调用区域首块的 finalizer），随后合并为一个 free 块 */
    private fun gcFreeUnmarked(ctx: JSContext) {
        var ptr = ctx.heapBase
        while (ptr < ctx.heapFree) {
            val size = ctx.memory.getBlockSize(ptr)
            
            if (ctx.memory.getGCMark(ptr) != 0) {
                /* 存活块：清除标记位 */
                ctx.memory.putU8(ptr, ctx.memory.getU8(ptr) and 0xFE)
                ptr += size
            } else {
                /* 死块：遍历连续未标记区域 */
                var ptr1 = ptr
                var total = 0
                while (ptr1 < ctx.heapFree && ctx.memory.getGCMark(ptr1) == 0) {
                    val size1 = ctx.memory.getBlockSize(ptr1)
                    
                    if (ctx.memory.getMTag(ptr1) == JSMTags.JS_MTAG_OBJECT) {
                        val classId = ctx.memory.getU8(ptr1 + 2)
                        if (classId >= JSObjectClassEnum.JS_CLASS_USER.value) {
                            val fin = ctx.cFinalizerTable?.getOrNull(classId - JSObjectClassEnum.JS_CLASS_USER.value)
                            if (fin != null) {
                                /* 调用用户 finalizer（禁止在其中调用 JS 函数） */
                                fin(ctx, ctx.userOpaqueTable[ptr1])
                            }
                            /* 对象已释放，清理宿主 opaque 引用表条目 */
                            ctx.userOpaqueTable.remove(ptr1)
                        }
                    }
                    
                    total += size1
                    ptr1 += size1
                }
                
                /* 合并连续死块为一个 free 块（对照 C set_free_block） */
                ctx.setFreeBlock(ptr, total)
                ptr += total
            }
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
                
                val classId = ctx.memory.getU8(ptr + 2)
                val extraSize = ctx.memory.getU8(ptr + 3)
                
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
                    JSObjectClassEnum.JS_CLASS_ERROR.value -> {
                        /* 错误对象：mark message（槽 0）与 stack（槽 1），对照 C p->u.error */
                        gcMark(ctx, markStack, ctx.memory.getJSValue(ptr + 24))
                        gcMark(ctx, markStack, ctx.memory.getJSValue(ptr + 32))
                    }
                    else -> {
                        /* 用户对象：标记 opaque 占位槽之后的私有 JSValue（同步上游 #80） */
                        if (classId >= JSObjectClassEnum.JS_CLASS_USER.value) {
                            for (i in 0 until extraSize - 1) {
                                val userVal = ctx.memory.getJSValue(ptr + 24 + (1 + i) * 8)
                                gcMark(ctx, markStack, userVal)
                            }
                        }
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
    
    /* 压缩堆（对照 C gc_compact_heap 的搬移语义）：
     * pass1 为存活块建立 旧地址→新地址 映射；随后按对象结构更新堆内字段与
     * 根区域的指针（ctx 字段、字符串位置缓存、执行栈、GCRef、parseState）；
     * pass2 搬移块并收缩堆顶。Kotlin 的 context 字段位于 JVM 堆上，无法参与
     * C 版 Jonkers 链表技法，故改用映射表实现，语义等价 */
    private fun gcCompactHeap(ctx: JSContext) {
        /* pass1：建立存活块的地址映射 */
        val moveMap = HashMap<Int, Int>()
        var newPtr = ctx.heapBase
        var ptr = ctx.heapBase
        while (ptr < ctx.heapFree) {
            val size = ctx.memory.getBlockSize(ptr)
            if (ctx.memory.getMTag(ptr) != JSMTags.JS_MTAG_FREE) {
                moveMap[ptr] = newPtr
                newPtr += size
            }
            ptr += size
        }
        
        if (moveMap.isEmpty()) return
        
        /* 更新堆内所有块的指针字段 */
        ptr = ctx.heapBase
        while (ptr < ctx.heapFree) {
            val size = ctx.memory.getBlockSize(ptr)
            if (ctx.memory.getMTag(ptr) != JSMTags.JS_MTAG_FREE) {
                gcUpdateBlockPointers(ctx, ptr, moveMap)
            }
            ptr += size
        }
        
        /* 更新根区域指针 */
        gcUpdateRootPointers(ctx, moveMap)
        
        /* 宿主 opaque 引用表的 key 随块搬移更新（保持 per-object 语义） */
        if (ctx.userOpaqueTable.isNotEmpty()) {
            val remapped = HashMap<Int, Any?>()
            for ((oldPtr, v) in ctx.userOpaqueTable) {
                remapped[moveMap[oldPtr] ?: oldPtr] = v
            }
            ctx.userOpaqueTable.clear()
            ctx.userOpaqueTable.putAll(remapped)
        }
        
        /* pass2：搬移存活块 */
        newPtr = ctx.heapBase
        ptr = ctx.heapBase
        while (ptr < ctx.heapFree) {
            val size = ctx.memory.getBlockSize(ptr)
            if (ctx.memory.getMTag(ptr) != JSMTags.JS_MTAG_FREE) {
                if (newPtr != ptr) {
                    ctx.memory.copy(ptr, newPtr, size)
                }
                newPtr += size
            }
            ptr += size
        }
        ctx.heapFree = newPtr
    }
    
    /* 按块结构更新其内部 GC 指针字段（旧地址 → 新地址） */
    private fun gcUpdateBlockPointers(ctx: JSContext, ptr: Int, moveMap: HashMap<Int, Int>) {
        fun remap(offset: Int) {
            val v = ctx.memory.getJSValue(offset)
            if (JS_IsPtr(v)) {
                val np = moveMap[JS_VALUE_TO_PTR(v)]
                if (np != null) {
                    ctx.memory.putJSValue(offset, JS_VALUE_FROM_PTR(np))
                }
            }
        }
        
        when (ctx.memory.getMTag(ptr)) {
            JSMTags.JS_MTAG_OBJECT -> {
                remap(ptr + 8)   /* proto */
                remap(ptr + 16)  /* props */
                
                val classId = ctx.memory.getU8(ptr + 2)
                val extraSize = ctx.memory.getU8(ptr + 3)
                
                when (classId) {
                    JSObjectClassEnum.JS_CLASS_CLOSURE.value -> {
                        remap(ptr + 24)
                        for (i in 0 until extraSize - 1) {
                            remap(ptr + 32 + i * 8)
                        }
                    }
                    JSObjectClassEnum.JS_CLASS_C_FUNCTION.value -> {
                        if (extraSize > 1) {
                            remap(ptr + 24)
                        }
                    }
                    JSObjectClassEnum.JS_CLASS_ARRAY.value -> {
                        remap(ptr + 24)
                    }
                    JSObjectClassEnum.JS_CLASS_ERROR.value -> {
                        /* 错误对象：更新 message（槽 0）与 stack（槽 1）指针，对照 C p->u.error */
                        remap(ptr + 24)
                        remap(ptr + 32)
                    }
                    else -> {
                        /* 用户对象：更新 opaque 占位槽之后的私有 JSValue（同步上游 #80） */
                        if (classId >= JSObjectClassEnum.JS_CLASS_USER.value) {
                            for (i in 0 until extraSize - 1) {
                                remap(ptr + 24 + (1 + i) * 8)
                            }
                        }
                    }
                }
            }
            JSMTags.JS_MTAG_VALUE_ARRAY -> {
                val size = ctx.memory.getI32(ptr + 4)
                for (i in 0 until size) {
                    remap(ptr + 8 + i * 8)
                }
            }
            JSMTags.JS_MTAG_VARREF -> {
                val isDetached = ctx.memory.getU8(ptr + 2) and 1
                if (isDetached == 0) {
                    remap(ptr + 16)
                }
            }
            JSMTags.JS_MTAG_FUNCTION_BYTECODE -> {
                for (off in intArrayOf(8, 16, 24, 32, 40, 48, 56)) {
                    remap(ptr + off)
                }
            }
        }
    }
    
    /* 更新根区域指针：context 字段、字符串位置缓存、执行栈、GCRef 链、parseState */
    private fun gcUpdateRootPointers(ctx: JSContext, moveMap: HashMap<Int, Int>) {
        fun remapValue(v: JSValue): JSValue {
            if (JS_IsPtr(v)) {
                val np = moveMap[JS_VALUE_TO_PTR(v)]
                if (np != null) {
                    return JS_VALUE_FROM_PTR(np)
                }
            }
            return v
        }
        
        ctx.uniqueStrings = remapValue(ctx.uniqueStrings)
        ctx.currentException = remapValue(ctx.currentException)
        ctx.emptyProps = remapValue(ctx.emptyProps)
        ctx.globalObj = remapValue(ctx.globalObj)
        ctx.minusZero = remapValue(ctx.minusZero)
        
        /* 临时保活列表中的值随块搬移更新 */
        for (i in ctx.gcKeepAlive.indices) {
            ctx.gcKeepAlive[i] = remapValue(ctx.gcKeepAlive[i])
        }
        
        /* 闭包捕获变量槽池随块搬移更新 */
        for (i in ctx.varrefPool.indices) {
            ctx.varrefPool[i] = remapValue(ctx.varrefPool[i])
        }
        
        for (i in ctx.classProto.indices) {
            ctx.classProto[i] = remapValue(ctx.classProto[i])
            ctx.classObj[i] = remapValue(ctx.classObj[i])
        }
        
        for (i in 0 until JS_STRING_POS_CACHE_SIZE) {
            val ce = ctx.stringPosCache[i]
            ce.str = remapValue(ce.str)
        }
        
        var sp = ctx.sp
        while (sp < ctx.memory.size) {
            ctx.memory.putJSValue(sp, remapValue(ctx.memory.getJSValue(sp)))
            sp += JSW
        }
        
        var refOffset = ctx.topGCRef
        while (refOffset != 0) {
            val prev = ctx.memory.getI32(refOffset)
            ctx.memory.putJSValue(refOffset + 8, remapValue(ctx.memory.getJSValue(refOffset + 8)))
            refOffset = prev
        }
        
        refOffset = ctx.lastGCRef
        while (refOffset != 0) {
            val prev = ctx.memory.getI32(refOffset)
            ctx.memory.putJSValue(refOffset + 8, remapValue(ctx.memory.getJSValue(refOffset + 8)))
            refOffset = prev
        }
        
        if (ctx.parseState != null) {
            val ps = ctx.parseState!!
            ps.sourceStr = remapValue(ps.sourceStr)
            ps.filenameStr = remapValue(ps.filenameStr)
            ps.tokenValue = remapValue(ps.tokenValue)
            ps.curFunc = remapValue(ps.curFunc)
            ps.byteCode = remapValue(ps.byteCode)
        }
    }
    
}
