package com.mquickjs.memory

import com.mquickjs.JSW
import com.mquickjs.JSMTags
import com.mquickjs.JS_VALUE_FROM_PTR

class MemoryBlock(val memory: Memory, val offset: Int) {
    
    val gcMark: Int
        get() = memory.getU8(offset) and 1
    
    val mtag: Int
        get() = (memory.getU8(offset) shr 1) and 0x7
    
    fun setHeader(mtag: Int, gcMark: Int = 0) {
        val header = (mtag shl 1) or gcMark
        memory.putU8(offset, header)
    }
    
    companion object {
        const val HEADER_SIZE = 8
        
        fun getMTag(memory: Memory, offset: Int): Int {
            return (memory.getU8(offset) shr 1) and 0x7
        }
        
        fun getGCMark(memory: Memory, offset: Int): Int {
            return memory.getU8(offset) and 1
        }
        
        fun setHeader(memory: Memory, offset: Int, mtag: Int, gcMark: Int = 0) {
            val header = (mtag shl 1) or gcMark
            memory.putU8(offset, header)
        }
        
        fun getBlockSize(memory: Memory, offset: Int): Int {
            val mtag = getMTag(memory, offset)
            return when (mtag) {
                JSMTags.JS_MTAG_OBJECT -> {
                    val extraSize = memory.getU8(offset + 2)
                    24 + extraSize * JSW
                }
                JSMTags.JS_MTAG_FLOAT64 -> 16
                JSMTags.JS_MTAG_STRING -> {
                    val len = memory.getI32(offset + 4)
                    16 + ((len + JSW) and (JSW - 1).inv())
                }
                JSMTags.JS_MTAG_BYTE_ARRAY -> {
                    val size = memory.getI32(offset + 4)
                    16 + ((size + JSW - 1) and (JSW - 1).inv())
                }
                JSMTags.JS_MTAG_VALUE_ARRAY -> {
                    val size = memory.getI32(offset + 4)
                    16 + size * JSW
                }
                JSMTags.JS_MTAG_FREE -> {
                    memory.getI32(offset + 4) * JSW + HEADER_SIZE
                }
                JSMTags.JS_MTAG_VARREF -> {
                    val isDetached = memory.getU8(offset + 2) and 1
                    if (isDetached != 0) 16 else 24
                }
                JSMTags.JS_MTAG_FUNCTION_BYTECODE -> 72
                else -> 0
            }
        }
    }
}

fun Memory.getBlockSize(offset: Int): Int = MemoryBlock.getBlockSize(this, offset)
fun Memory.getMTag(offset: Int): Int = MemoryBlock.getMTag(this, offset)
fun Memory.getGCMark(offset: Int): Int = MemoryBlock.getGCMark(this, offset)
