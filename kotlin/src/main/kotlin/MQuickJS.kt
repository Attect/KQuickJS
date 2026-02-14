package com.mquickjs

import com.mquickjs.parser.JSParseState
import com.mquickjs.parser.JSParser
import com.mquickjs.runtime.JSRuntime
import java.io.File
import java.util.Scanner

object MQuickJS {
    
    private const val VERSION = "MicroQuickJS Kotlin"
    private const val DEFAULT_MEM_SIZE = 16 * 1024 * 1024
    
    @JvmStatic
    fun main(args: Array<String>) {
        var optind = 0
        var interactive = false
        var expr: String? = null
        var memSize = DEFAULT_MEM_SIZE
        var dumpMemory = 0
        var parseFlags = 0
        val includeList = mutableListOf<String>()
        
        while (optind < args.size && args[optind].startsWith("-")) {
            val arg = args[optind].substring(1)
            var longopt = ""
            optind++
            
            if (arg.isEmpty()) break
            
            if (arg.startsWith("-")) {
                longopt = arg.substring(1)
                if (longopt.isEmpty()) break
            }
            
            var currentArg = arg
            if (longopt.isNotEmpty()) {
                currentArg = ""
            }
            
            while (currentArg.isNotEmpty() || longopt.isNotEmpty()) {
                val opt = if (currentArg.isNotEmpty()) {
                    val c = currentArg[0]
                    currentArg = currentArg.substring(1)
                    c
                } else {
                    '\u0000'
                }
                
                when {
                    opt == 'h' || opt == '?' || longopt == "help" -> {
                        printHelp()
                        return
                    }
                    opt == 'e' || longopt == "eval" -> {
                        if (currentArg.isNotEmpty()) {
                            expr = currentArg
                            currentArg = ""
                        } else if (optind < args.size) {
                            expr = args[optind++]
                        } else {
                            System.err.println("missing expression for -e")
                            System.exit(2)
                        }
                        longopt = ""
                    }
                    longopt == "memory-limit" -> {
                        if (optind >= args.size) {
                            System.err.println("expecting memory limit")
                            System.exit(1)
                        }
                        memSize = parseMemoryLimit(args[optind++])
                        longopt = ""
                    }
                    opt == 'd' || longopt == "dump" -> {
                        dumpMemory++
                        longopt = ""
                    }
                    opt == 'i' || longopt == "interactive" -> {
                        interactive = true
                        longopt = ""
                    }
                    opt == 'I' || longopt == "include" -> {
                        if (optind >= args.size) {
                            System.err.println("expecting filename")
                            System.exit(1)
                        }
                        includeList.add(args[optind++])
                        longopt = ""
                    }
                    longopt == "no-column" -> {
                        parseFlags = parseFlags or JS_EVAL_STRIP_COL
                        longopt = ""
                    }
                    else -> {
                        if (opt != '\u0000') {
                            System.err.println("qjs: unknown option '-$opt'")
                        } else {
                            System.err.println("qjs: unknown option '--$longopt'")
                        }
                        printHelp()
                        return
                    }
                }
            }
        }
        
        val ctx = createContext(memSize)
        
        for (includeFile in includeList) {
            if (!evalFile(ctx, includeFile, parseFlags)) {
                System.exit(1)
            }
        }
        
        if (expr != null) {
            if (!evalString(ctx, expr, "<cmdline>", false, parseFlags or JS_EVAL_REPL)) {
                System.exit(1)
            }
        } else if (optind >= args.size) {
            interactive = true
        } else {
            val filename = args[optind]
            if (!evalFile(ctx, filename, parseFlags)) {
                System.exit(1)
            }
        }
        
        if (interactive) {
            runREPL(ctx, parseFlags)
        }
        
        if (dumpMemory > 0) {
            dumpMemory(ctx, dumpMemory >= 2)
        }
    }
    
    private fun printHelp() {
        println("""
MicroQuickJS
usage: mqjs [options] [file [args]]
-h  --help            list options
-e  --eval EXPR       evaluate EXPR
-i  --interactive     go to interactive mode
-I  --include file    include an additional file
-d  --dump            dump the memory usage stats
    --memory-limit n  limit the memory usage to 'n' bytes
--no-column           no column number in debug information
-o FILE               save the bytecode to FILE
-m32                  force 32 bit bytecode output (use with -o)
-b  --allow-bytecode  allow bytecode in input file
        """.trimIndent())
        System.exit(1)
    }
    
    private fun parseMemoryLimit(s: String): Int {
        val lastChar = s.last().lowercaseChar()
        val numStr = if (lastChar == 'g' || lastChar == 'm' || lastChar == 'k') s.dropLast(1) else s
        var count = numStr.toDouble()
        when (lastChar) {
            'g' -> count *= 1024 * 1024 * 1024
            'm' -> count *= 1024 * 1024
            'k' -> count *= 1024
        }
        return count.toInt()
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
    
    private fun evalString(ctx: JSContext, code: String, filename: String, isRepl: Boolean, parseFlags: Int): Boolean {
        val bytes = code.toByteArray()
        val evalFlags = if (isRepl) parseFlags or JS_EVAL_RETVAL or JS_EVAL_REPL else parseFlags
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
        
        if (isRepl) {
            printResult(ctx, runtimeResult)
        }
        return true
    }
    
    private fun evalFile(ctx: JSContext, filename: String, parseFlags: Int): Boolean {
        val file = File(filename)
        if (!file.exists()) {
            System.err.println("$filename: No such file or directory")
            return false
        }
        
        val code = file.readText()
        return evalString(ctx, code, filename, false, parseFlags)
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
    
    private fun runREPL(ctx: JSContext, parseFlags: Int) {
        val scanner = Scanner(System.`in`)
        
        while (true) {
            print("mqjs > ")
            val line = try {
                scanner.nextLine() ?: break
            } catch (e: Exception) {
                break
            }
            
            if (line.isBlank()) continue
            
            try {
                evalString(ctx, line, "<cmdline>", true, parseFlags)
            } catch (e: Exception) {
                System.err.println("Error: ${e.message}")
            }
        }
    }
    
    private fun dumpMemory(ctx: JSContext, verbose: Boolean) {
        println("Memory dump not yet implemented in Kotlin version")
    }
}
