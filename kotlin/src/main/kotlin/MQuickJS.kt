package app.muka.project.kquickjs

import app.muka.project.kquickjs.memory.getBlockSize
import app.muka.project.kquickjs.memory.getGCMark
import app.muka.project.kquickjs.memory.getMTag
import app.muka.project.kquickjs.parser.JSParseState
import app.muka.project.kquickjs.parser.JSParser
import app.muka.project.kquickjs.runtime.JSRuntime
import java.io.File
import java.io.FileOutputStream
import java.util.Scanner

object MQuickJS {
    
    private const val VERSION = "MicroQuickJS Kotlin"
    private const val DEFAULT_MEM_SIZE = 16 * 1024 * 1024
    private const val JS_BYTECODE_VERSION = 0x0001 or (8 shl 12)
    
    @JvmStatic
    fun main(args: Array<String>) {
        var optind = 0
        var interactive = false
        var expr: String? = null
        var memSize = DEFAULT_MEM_SIZE
        var dumpMemory = 0
        var parseFlags = 0
        var outFilename: String? = null
        var allowBytecode = false
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
                    opt == 'o' -> {
                        if (currentArg.isNotEmpty()) {
                            outFilename = currentArg
                            currentArg = ""
                        } else if (optind < args.size) {
                            outFilename = args[optind++]
                        } else {
                            System.err.println("missing filename for -o")
                            System.exit(2)
                        }
                        longopt = ""
                    }
                    opt == 'b' || longopt == "allow-bytecode" -> {
                        allowBytecode = true
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
        
        if (outFilename != null) {
            if (optind >= args.size) {
                System.err.println("expecting input filename")
                System.exit(1)
            }
            val inputFilename = args[optind]
            compileFile(inputFilename, outFilename, memSize, dumpMemory, parseFlags)
            return
        }
        
        val ctx = createContext(memSize)
        
        for (includeFile in includeList) {
            if (!evalFile(ctx, includeFile, parseFlags, allowBytecode)) {
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
            if (!evalFile(ctx, filename, parseFlags, allowBytecode)) {
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
    
    private fun compileFile(filename: String, outFilename: String, memSize: Int, dumpMemory: Int, parseFlags: Int) {
        val file = File(filename)
        if (!file.exists()) {
            System.err.println("$filename: No such file or directory")
            System.exit(1)
        }
        
        val ctx = createContext(memSize)
        val code = file.readText()
        val bytes = code.toByteArray()
        
        val state = JSParseState(ctx, bytes, filename, parseFlags)
        val parser = JSParser(state)
        val result = parser.parse()
        
        if (JS_IsException(result)) {
            System.err.println("Parse error: ${state.errorMsg}")
            System.exit(1)
        }
        
        if (dumpMemory > 0) {
            dumpMemory(ctx, dumpMemory >= 2)
        }
        
        writeBytecode(ctx, outFilename, result)
    }
    
    private fun writeBytecode(ctx: JSContext, filename: String, mainFunc: JSValue) {
        val heapSize = ctx.heapFree - ctx.heapBase
        
        FileOutputStream(filename).use { fos ->
            val header = ByteArray(24)
            header[0] = (JS_BYTECODE_MAGIC and 0xFF).toByte()
            header[1] = ((JS_BYTECODE_MAGIC shr 8) and 0xFF).toByte()
            header[2] = (JS_BYTECODE_VERSION and 0xFF).toByte()
            header[3] = ((JS_BYTECODE_VERSION shr 8) and 0xFF).toByte()
            
            val baseAddr = ctx.heapBase
            header[4] = (baseAddr and 0xFF).toByte()
            header[5] = ((baseAddr shr 8) and 0xFF).toByte()
            header[6] = ((baseAddr shr 16) and 0xFF).toByte()
            header[7] = ((baseAddr shr 24) and 0xFF).toByte()
            header[8] = ((baseAddr shr 32) and 0xFF).toByte()
            header[9] = ((baseAddr shr 40) and 0xFF).toByte()
            header[10] = ((baseAddr shr 48) and 0xFF).toByte()
            header[11] = ((baseAddr shr 56) and 0xFF).toByte()
            
            val uniqueStrings = ctx.uniqueStrings
            header[12] = (uniqueStrings and 0xFF).toByte()
            header[13] = ((uniqueStrings shr 8) and 0xFF).toByte()
            header[14] = ((uniqueStrings shr 16) and 0xFF).toByte()
            header[15] = ((uniqueStrings shr 24) and 0xFF).toByte()
            header[16] = ((uniqueStrings shr 32) and 0xFF).toByte()
            header[17] = ((uniqueStrings shr 40) and 0xFF).toByte()
            header[18] = ((uniqueStrings shr 48) and 0xFF).toByte()
            header[19] = ((uniqueStrings shr 56) and 0xFF).toByte()
            
            header[20] = (mainFunc and 0xFF).toByte()
            header[21] = ((mainFunc shr 8) and 0xFF).toByte()
            header[22] = ((mainFunc shr 16) and 0xFF).toByte()
            header[23] = ((mainFunc shr 24) and 0xFF).toByte()
            
            fos.write(header)
            
            val heapData = ctx.memory.buffer.array()
            fos.write(heapData, ctx.heapBase, heapSize)
        }
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
    
    private fun evalFile(ctx: JSContext, filename: String, parseFlags: Int, allowBytecode: Boolean): Boolean {
        val file = File(filename)
        if (!file.exists()) {
            System.err.println("$filename: No such file or directory")
            return false
        }
        
        val bytes = file.readBytes()
        
        if (allowBytecode && isBytecode(bytes)) {
            return loadBytecode(ctx, bytes)
        }
        
        val code = bytes.toString(Charsets.UTF_8)
        return evalString(ctx, code, filename, false, parseFlags)
    }
    
    private fun isBytecode(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        val magic = (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
        return magic == JS_BYTECODE_MAGIC
    }
    
    private fun loadBytecode(ctx: JSContext, bytes: ByteArray): Boolean {
        if (bytes.size < 24) {
            System.err.println("Invalid bytecode file: too small")
            return false
        }
        
        val magic = (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
        if (magic != JS_BYTECODE_MAGIC) {
            System.err.println("Invalid bytecode file: bad magic")
            return false
        }
        
        val version = (bytes[2].toInt() and 0xFF) or ((bytes[3].toInt() and 0xFF) shl 8)
        if (version != JS_BYTECODE_VERSION) {
            System.err.println("Bytecode version mismatch: expected $JS_BYTECODE_VERSION, got $version")
            return false
        }
        
        val baseAddr = (bytes[4].toLong() and 0xFF) or
                      ((bytes[5].toLong() and 0xFF) shl 8) or
                      ((bytes[6].toLong() and 0xFF) shl 16) or
                      ((bytes[7].toLong() and 0xFF) shl 24) or
                      ((bytes[8].toLong() and 0xFF) shl 32) or
                      ((bytes[9].toLong() and 0xFF) shl 40) or
                      ((bytes[10].toLong() and 0xFF) shl 48) or
                      ((bytes[11].toLong() and 0xFF) shl 56)
        
        val mainFunc = (bytes[20].toLong() and 0xFF) or
                      ((bytes[21].toLong() and 0xFF) shl 8) or
                      ((bytes[22].toLong() and 0xFF) shl 16) or
                      ((bytes[23].toLong() and 0xFF) shl 24)
        
        val heapData = bytes.copyOfRange(24, bytes.size)
        
        if (heapData.size > ctx.memory.size) {
            System.err.println("Bytecode too large for memory")
            return false
        }
        
        val offset = baseAddr.toInt()
        ctx.memory.buffer.position(offset)
        ctx.memory.buffer.put(heapData)
        ctx.heapFree = offset + heapData.size
        
        val relocatedMainFunc = mainFunc + offset
        
        val runtime = JSRuntime(ctx)
        val result = runtime.callFunction(relocatedMainFunc, emptyList())
        
        return !JS_IsException(result)
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
        val mtagMemSize = IntArray(JSMTags.JS_MTAG_COUNT)
        val mtagCount = IntArray(JSMTags.JS_MTAG_COUNT)
        var totSize = 0
        
        if (verbose) {
            println("%10s %s %8s %15s %10s %10s %s".format("OFFSET", "M", "SIZE", "TAG", "PROTO", "PROPS", "EXTRA"))
        }
        
        var ptr = ctx.heapBase
        while (ptr < ctx.heapFree) {
            val mtag = ctx.memory.getMTag(ptr)
            val size = ctx.memory.getBlockSize(ptr)
            val gcMark = ctx.memory.getGCMark(ptr)
            mtagMemSize[mtag] += size
            mtagCount[mtag]++
            totSize += size
            
            if (verbose) {
                val markChar = if (gcMark != 0) "*" else " "
                print("0x%08x %s %8d %15s".format(ptr - ctx.heapBase, markChar, size, JSMTags.getMTagName(mtag)))
                if (mtag != JSMTags.JS_MTAG_FREE) {
                    if (mtag == JSMTags.JS_MTAG_OBJECT) {
                        val proto = ctx.memory.getJSValue(ptr + 8)
                        val props = ctx.memory.getJSValue(ptr + 16)
                        print(" 0x%08x 0x%08x".format(
                            if (JS_IsPtr(proto)) JS_VALUE_TO_PTR(proto) - ctx.heapBase else proto,
                            if (JS_IsPtr(props)) JS_VALUE_TO_PTR(props) - ctx.heapBase else props
                        ))
                    } else {
                        print(" %10s %10s".format("", ""))
                    }
                    print(" ")
                    printValueRaw(ctx, JS_VALUE_FROM_PTR(ptr))
                }
                println()
            }
            
            ptr = ptr + size
        }
        
        println("%15s %8s %8s %8s %8s".format("TAG", "COUNT", "AVG_SIZE", "SIZE", "RATIO"))
        for (i in 0 until JSMTags.JS_MTAG_COUNT) {
            if (mtagCount[i] != 0) {
                val avgSize = mtagMemSize[i] / mtagCount[i]
                val ratio = (mtagMemSize[i] * 100 / totSize)
                println("%15s %8d %8d %8d %7d%%".format(
                    JSMTags.getMTagName(i),
                    mtagCount[i],
                    avgSize,
                    mtagMemSize[i],
                    ratio
                ))
            }
        }
        println("heap size=${ctx.heapFree - ctx.heapBase}/${ctx.memory.size} stack_size=${ctx.memory.size - ctx.sp}")
    }
    
    private fun printValueRaw(ctx: JSContext, val1: JSValue) {
        when {
            JS_IsInt(val1) -> print(JS_VALUE_GET_INT(val1))
            JS_IsShortFloat(val1) -> print(jsGetShortFloat(val1))
            JS_IsString(ctx, val1) -> print("\"${jsGetString(ctx, val1)}\"")
            JS_IsUndefined(val1) -> print("undefined")
            JS_IsNull(val1) -> print("null")
            JS_IsBool(val1) -> print(if (val1 == JS_TRUE) "true" else "false")
            JS_IsPtr(val1) -> {
                val ptr = JS_VALUE_TO_PTR(val1)
                val mtag = ctx.memory.getMTag(ptr)
                when (mtag) {
                    JSMTags.JS_MTAG_FLOAT64 -> print(ctx.memory.getFloat64(ptr + 8))
                    JSMTags.JS_MTAG_STRING -> print("\"${jsGetString(ctx, val1)}\"")
                    JSMTags.JS_MTAG_OBJECT -> {
                        val classId = ctx.memory.getU8(ptr + 2)
                        val className = JSObjectClassEnum.fromValue(classId)?.name ?: "UNKNOWN"
                        print("[object $className]")
                    }
                    JSMTags.JS_MTAG_FUNCTION_BYTECODE -> print("[bytecode]")
                    JSMTags.JS_MTAG_VALUE_ARRAY -> {
                        val len = ctx.memory.getI32(ptr + 4)
                        print("[array:$len]")
                    }
                    JSMTags.JS_MTAG_BYTE_ARRAY -> {
                        val len = ctx.memory.getI32(ptr + 4)
                        print("[bytes:$len]")
                    }
                    JSMTags.JS_MTAG_VARREF -> print("[varref]")
                    else -> print("[ptr:0x${ptr.toString(16)}]")
                }
            }
            else -> print(val1)
        }
    }
}
