package com.mquickjs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JSValueTest {
    
    @Test
    fun testJSValueTags() {
        assertTrue(JS_IsInt(JS_NewShortInt(42)))
        assertEquals(42, JS_VALUE_GET_INT(JS_NewShortInt(42)))
        
        assertTrue(JS_IsNull(JS_NULL))
        assertTrue(JS_IsUndefined(JS_UNDEFINED))
        assertTrue(JS_IsBool(JS_TRUE))
        assertTrue(JS_IsBool(JS_FALSE))
        assertTrue(JS_IsException(JS_EXCEPTION))
        
        assertEquals(JS_TRUE, JS_NewBool(true))
        assertEquals(JS_FALSE, JS_NewBool(false))
    }
    
    @Test
    fun testSpecialValues() {
        assertEquals(JSTags.JS_TAG_NULL, JS_VALUE_GET_SPECIAL_TAG(JS_NULL))
        assertEquals(JSTags.JS_TAG_UNDEFINED, JS_VALUE_GET_SPECIAL_TAG(JS_UNDEFINED))
        assertEquals(JSTags.JS_TAG_BOOL, JS_VALUE_GET_SPECIAL_TAG(JS_TRUE))
        assertEquals(JSTags.JS_TAG_BOOL, JS_VALUE_GET_SPECIAL_TAG(JS_FALSE))
    }
    
    @Test
    fun testIntRange() {
        val min = JS_SHORTINT_MIN
        val max = JS_SHORTINT_MAX
        
        assertTrue(JS_IsInt(JS_NewShortInt(0)))
        assertTrue(JS_IsInt(JS_NewShortInt(min)))
        assertTrue(JS_IsInt(JS_NewShortInt(max)))
        
        assertEquals(min, JS_VALUE_GET_INT(JS_NewShortInt(min)))
        assertEquals(max, JS_VALUE_GET_INT(JS_NewShortInt(max)))
    }
    
    @Test
    fun testPtrConversion() {
        val ptr = 0x1000
        val val1 = JS_VALUE_FROM_PTR(ptr)
        assertTrue(JS_IsPtr(val1))
        assertEquals(ptr, JS_VALUE_TO_PTR(val1))
    }
}
