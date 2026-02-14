package com.mquickjs

import com.mquickjs.memory.Memory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryTest {
    
    @Test
    fun testMemoryBasics() {
        val mem = Memory(1024)
        
        mem.putU8(0, 0x42)
        assertEquals(0x42, mem.getU8(0))
        
        mem.putU16(2, 0x1234)
        assertEquals(0x1234, mem.getU16(2))
        
        mem.putU32(4, 0xDEADBEEF)
        assertEquals(0xDEADBEEFL, mem.getU32(4))
        
        mem.putU64(8, 0x0123456789ABCDEF)
        assertEquals(0x0123456789ABCDEF, mem.getU64(8))
    }
    
    @Test
    fun testFloat64() {
        val mem = Memory(1024)
        
        val d = 3.14159265358979
        mem.putFloat64(0, d)
        assertEquals(d, mem.getFloat64(0), 1e-15)
        
        mem.putFloat64(8, -0.0)
        assertEquals(-0.0, mem.getFloat64(8))
        
        mem.putFloat64(16, Double.NaN)
        assertTrue(mem.getFloat64(16).isNaN())
    }
    
    @Test
    fun testJSValue() {
        val mem = Memory(1024)
        
        mem.putJSValue(0, JS_NULL)
        assertEquals(JS_NULL, mem.getJSValue(0))
        
        mem.putJSValue(8, JS_NewShortInt(42))
        assertEquals(JS_NewShortInt(42), mem.getJSValue(8))
    }
    
    @Test
    fun testAlignment() {
        val mem = Memory(1024)
        
        assertEquals(0, mem.align(0, 8))
        assertEquals(8, mem.align(1, 8))
        assertEquals(8, mem.align(7, 8))
        assertEquals(16, mem.align(9, 8))
        assertEquals(16, mem.align(16, 8))
    }
    
    @Test
    fun testString() {
        val mem = Memory(1024)
        
        val str = "Hello, World!"
        val len = mem.putString(0, str)
        assertEquals(str, mem.getString(0))
        assertTrue(len > str.length)
    }
}
