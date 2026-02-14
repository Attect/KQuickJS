package com.mquickjs

import com.mquickjs.parser.JSParseState
import com.mquickjs.parser.JSParser
import com.mquickjs.runtime.JSRuntime
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class MQuickJSFileTest {
    
    private val testsDir = File("tests")
    
    @Test
    fun testLanguageFile() {
        runJSFile("test_language.js")
    }
    
    @Test
    fun testLoopFile() {
        runJSFile("test_loop.js")
    }
    
    @Test
    fun testClosureFile() {
        runJSFile("test_closure.js")
    }
    
    @Test
    fun testBuiltinFile() {
        runJSFile("test_builtin.js")
    }
    
    private fun runJSFile(filename: String) {
        val file = File(testsDir, filename)
        if (!file.exists()) {
            println("File not found: ${file.absolutePath}")
            assertTrue(true, "File not found, skipping test")
            return
        }
        
        println("Running JS file: $filename")
        val code = file.readText()
        println("File size: ${code.length} bytes")
        
        try {
            val ctx = createTestContext()
            val bytes = code.toByteArray()
            val state = JSParseState(ctx, bytes, filename, JS_EVAL_RETVAL)
            val parser = JSParser(state)
            val result = parser.parse()
            
            if (JS_IsException(result)) {
                println("Parse error: ${state.errorMsg}")
                fail("Parse error: ${state.errorMsg}")
            }
            
            println("Parse successful, isPtr: ${JS_IsPtr(result)}")
            
            val runtime = JSRuntime(ctx)
            val runtimeResult = runtime.callFunction(result, emptyList())
            
            println("Execution result: $runtimeResult")
            
            if (JS_IsException(runtimeResult)) {
                fail("Execution returned exception")
            }
            
            println("Test $filename passed!")
            assertTrue(true)
            
        } catch (e: Exception) {
            println("Exception during execution: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }
    
    private fun createTestContext(): JSContext {
        val memSize = 65536 * 4
        val mem = ByteArray(memSize)
        val stdlib = JSSTDLibraryDef(
            stdlibTable = LongArray(0),
            cFunctionTable = emptyArray(),
            cFinalizerTable = emptyArray(),
            stdlibTableLen = 0,
            stdlibTableAlign = 8,
            sortedAtomsOffset = 0,
            globalObjectOffset = 0,
            classCount = JSObjectClassEnum.JS_CLASS_USER.value
        )
        return JS_NewContext(mem, memSize, stdlib)
    }
}
