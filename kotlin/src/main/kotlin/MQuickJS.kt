package com.mquickjs

import com.mquickjs.parser.JSParseState
import com.mquickjs.parser.JSParser
import com.mquickjs.runtime.JSRuntime
import java.io.File
import java.util.Scanner

object MQuickJS {
    
    private const val VERSION = "MicroQuickJS Kotlin 1.0.0"
    private const val DEFAULT_MEM_SIZE = 16 * 1024 * 1024
    
    @JvmStatic
    fun main(args: Array<String>) {
        var optind = 0
        var interactive = false
        var expr: String? = null
        var memSize = DEFAULT_MEM_SIZE
        val includeList = mutableListOf<String>()
        
        while (optind < args.size && args[optind].startsWith("-")) {
            val arg = args[optind].substring(1)
            optind++
            
            if (arg.isEmpty()) break
            
            when {
                arg == "h" || arg == "-help" -> {
                    printHelp()
                    return
                }
                arg == "e" || arg == "-eval" -> {
                    if (optind < args.size) {
                        expr = args[optind++]
                    } else {
                        System.err.println("missing expression for -e")
                        System.exit(2)
                    }
                }
                arg == "i" || arg == "-interactive" -> {
                    interactive = true
                }
                arg == "I" || arg == "-include" -> {
                    if (optind < args.size) {
                        includeList.add(args[optind++])
                    } else {
                        System.err.println("expecting filename")
                        System.exit(1)
                    }
                }
                arg == "-memory-limit" -> {
                    if (optind < args.size) {
                        val limitStr = args[optind++]
                        memSize = parseMemoryLimit(limitStr)
                    } else {
                        System.err.println("expecting memory limit")
                        System.exit(1)
                    }
                }
                arg == "v" || arg == "-version" -> {
                    println(VERSION)
                    return
                }
                else -> {
                    System.err.println("mqjs: unknown option '-$arg'")
                    printHelp()
                    return
                }
            }
        }
        
        val ctx = createContext(memSize)
        
        for (includeFile in includeList) {
            if (!evalFile(ctx, includeFile)) {
                System.exit(1)
            }
        }
        
        if (expr != null) {
            if (!evalString(ctx, expr, "<cmdline>")) {
                System.exit(1)
            }
        } else if (optind >= args.size) {
            interactive = true
        } else {
            val filename = args[optind]
            if (!evalFile(ctx, filename)) {
                System.exit(1)
            }
        }
        
        if (interactive) {
            runREPL(ctx)
        }
    }
    
    private fun printHelp() {
        println("""
MicroQuickJS Kotlin
usage: mqjs [options] [file [args]]
-h  --help            list options
-e  --eval EXPR       evaluate EXPR
-i  --interactive     go to interactive mode
-I  --include file    include an additional file
    --memory-limit n  limit the memory usage to 'n' bytes
-v  --version         show version
        """.trimIndent())
    }
    
    private fun parseMemoryLimit(s: String): Int {
        val multipliers = mapOf(
            'k' to 1024,
            'K' to 1024,
            'm' to 1024 * 1024,
            'M' to 1024 * 1024,
            'g' to 1024 * 1024 * 1024,
            'G' to 1024 * 1024 * 1024
        )
        
        val lastChar = s.last()
        return if (lastChar in multipliers) {
            val num = s.dropLast(1).toDouble()
            (num * multipliers[lastChar]!!).toInt()
        } else {
            s.toInt()
        }
    }
    
    private fun createContext(memSize: Int): JSContext {
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
    
    private fun evalString(ctx: JSContext, code: String, filename: String, isRepl: Boolean = false): Boolean {
        val bytes = code.toByteArray()
        val evalFlags = if (isRepl) JS_EVAL_RETVAL or JS_EVAL_REPL else JS_EVAL_RETVAL
        val state = JSParseState(ctx, bytes, filename, evalFlags)
        val parser = JSParser(state)
        val result = parser.parse()
        
        if (JS_IsException(result)) {
            System.err.println("Parse error: ${state.errorMsg}")
            return false
        }
        
        val runtime = JSRuntime(ctx)
        val runtimeResult = runtime.callFunction(result, emptyList())
        
        if (JS_IsException(runtimeResult)) {
            System.err.println("Runtime error")
            return false
        }
        
        printResult(ctx, runtimeResult)
        return true
    }
    
    private fun evalFile(ctx: JSContext, filename: String): Boolean {
        val file = File(filename)
        if (!file.exists()) {
            System.err.println("File not found: $filename")
            return false
        }
        
        val code = file.readText()
        return evalString(ctx, code, filename)
    }
    
    private fun printResult(ctx: JSContext, result: JSValue) {
        when {
            JS_IsInt(result) -> println(JS_VALUE_GET_INT(result))
            JS_IsShortFloat(result) -> println(jsGetShortFloat(result))
            JS_IsString(ctx, result) -> println(jsGetString(ctx, result))
            JS_IsUndefined(result) -> println("undefined")
            JS_IsNull(result) -> println("null")
            JS_IsBool(result) -> println(if (result == JS_TRUE) "true" else "false")
            else -> println(result)
        }
    }
    
    private fun runREPL(ctx: JSContext) {
        val scanner = Scanner(System.`in`)
        println("MicroQuickJS Kotlin REPL")
        println("Type 'exit' to quit")
        println()
        
        while (true) {
            print("mqjs > ")
            val line = scanner.nextLine() ?: break
            
            if (line.trim() == "exit") break
            if (line.isBlank()) continue
            
            try {
                evalString(ctx, line, "<repl>", isRepl = true)
            } catch (e: Exception) {
                System.err.println("Error: ${e.message}")
            }
        }
        
        println("Goodbye!")
    }
}
