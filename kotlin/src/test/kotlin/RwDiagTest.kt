package app.muka.project.kquickjs

import app.muka.project.kquickjs.bridge.JSBridge
import kotlin.test.Test

class RwDiagTest {
    @Test
    fun diag() {
        val mem = ByteArray(1024 * 1024)
        val stdlib = JSSTDLibraryDef(
            stdlibTable = LongArray(0), cFunctionTable = emptyArray(),
            cFinalizerTable = emptyArray(), stdlibTableLen = 0, stdlibTableAlign = 8,
            sortedAtomsOffset = 0, globalObjectOffset = 0,
            classCount = JSObjectClassEnum.JS_CLASS_USER.value
        )
        val ctx = JS_NewContext(mem, 1024 * 1024, stdlib)
        val bridge = JSBridge(ctx)
        bridge.function("__stset", listOf("k", "v")) { _, args ->
            println("DIAG __stset called k=" + args.jsValue(0)); null
        }
        bridge.function("__stget", listOf("k")) { _, args ->
            println("DIAG __stget called k=" + args.jsValue(0)); null
        }
        println("DIAG typeof __stset = " + bridge.eval("typeof __stset"))
        bridge.eval("function f() { var a = 1; __stset('a', a); return __stget('a'); }")
        println("DIAG typeof f = " + bridge.eval("typeof f"))
        println("DIAG f() = " + bridge.eval("f()"))
        println("DIAG f() typeof = " + bridge.eval("typeof f()"))
        println("DIAG g1 def = " + bridge.eval("function g1() { var t0 = 0; return 1; }"))
        println("DIAG typeof g1 = " + bridge.eval("typeof g1"))
        println("DIAG g1() = " + bridge.eval("g1()"))
    }
}
