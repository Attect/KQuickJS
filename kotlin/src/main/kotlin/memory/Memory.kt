package app.muka.project.kquickjs.memory

import app.muka.project.kquickjs.JSW
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Memory(val size: Int) {
    val buffer: ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
    
    var heapBase: Int = 0
    var heapFree: Int = 0
    var stackTop: Int = size
    var stackBottom: Int = size
    
    fun getU8(offset: Int): Int = buffer.get(offset).toInt() and 0xFF
    fun getI8(offset: Int): Int = buffer.get(offset).toInt()
    
    fun putU8(offset: Int, value: Int) {
        buffer.put(offset, value.toByte())
    }
    
    fun getU16(offset: Int): Int = buffer.getShort(offset).toInt() and 0xFFFF
    fun getI16(offset: Int): Int = buffer.getShort(offset).toInt()
    
    fun putU16(offset: Int, value: Int) {
        buffer.putShort(offset, value.toShort())
    }
    
    fun getU32(offset: Int): Long = buffer.getInt(offset).toLong() and 0xFFFFFFFFL
    fun getI32(offset: Int): Int = buffer.getInt(offset)
    
    fun putU32(offset: Int, value: Long) {
        buffer.putInt(offset, value.toInt())
    }
    
    fun putI32(offset: Int, value: Int) {
        buffer.putInt(offset, value)
    }
    
    fun getU64(offset: Int): Long = buffer.getLong(offset)
    fun getI64(offset: Int): Long = buffer.getLong(offset)
    
    fun putU64(offset: Int, value: Long) {
        buffer.putLong(offset, value)
    }
    
    fun getFloat64(offset: Int): Double = buffer.getDouble(offset)
    fun putFloat64(offset: Int, value: Double) {
        buffer.putDouble(offset, value)
    }
    
    fun getBytes(offset: Int, length: Int): ByteArray {
        val bytes = ByteArray(length)
        buffer.position(offset)
        buffer.get(bytes)
        return bytes
    }
    
    fun putBytes(offset: Int, bytes: ByteArray) {
        buffer.position(offset)
        buffer.put(bytes)
    }
    
    fun putBytes(offset: Int, bytes: ByteArray, srcOffset: Int, length: Int) {
        buffer.position(offset)
        buffer.put(bytes, srcOffset, length)
    }
    
    fun copy(srcOffset: Int, dstOffset: Int, length: Int) {
        val bytes = ByteArray(length)
        buffer.position(srcOffset)
        buffer.get(bytes)
        buffer.position(dstOffset)
        buffer.put(bytes)
    }
    
    fun fill(offset: Int, value: Byte, length: Int) {
        for (i in 0 until length) {
            buffer.put(offset + i, value)
        }
    }
    
    fun clear(offset: Int, length: Int) {
        fill(offset, 0, length)
    }
    
    fun align(offset: Int, alignment: Int): Int = (offset + alignment - 1) and (alignment - 1).inv()
    
    fun alignToJSW(offset: Int): Int = align(offset, JSW)
    
    fun isValidOffset(offset: Int): Boolean = offset >= 0 && offset < size
    
    fun getString(offset: Int, maxLength: Int = Int.MAX_VALUE): String {
        val sb = StringBuilder()
        var i = offset
        while (i < size && sb.length < maxLength) {
            val b = buffer.get(i)
            if (b == 0.toByte()) break
            sb.append(b.toInt().toChar())
            i++
        }
        return sb.toString()
    }
    
    fun putString(offset: Int, str: String): Int {
        val bytes = str.toByteArray(Charsets.UTF_8)
        putBytes(offset, bytes)
        putU8(offset + bytes.size, 0)
        return bytes.size + 1
    }
    
    fun getJSValue(offset: Int): Long = getU64(offset)
    fun putJSValue(offset: Int, value: Long) = putU64(offset, value)
    
    fun getJSWord(offset: Int): Long = getU64(offset)
    fun putJSWord(offset: Int, value: Long) = putU64(offset, value)
}
