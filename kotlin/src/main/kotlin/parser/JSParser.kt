package app.muka.project.kquickjs.parser

import app.muka.project.kquickjs.*
import app.muka.project.kquickjs.JSMTags.JS_MTAG_BYTE_ARRAY
import app.muka.project.kquickjs.JSMTags.JS_MTAG_FUNCTION_BYTECODE
import app.muka.project.kquickjs.JSMTags.JS_MTAG_OBJECT
import app.muka.project.kquickjs.JSMTags.JS_MTAG_STRING
import app.muka.project.kquickjs.JSMTags.JS_MTAG_VALUE_ARRAY
import app.muka.project.kquickjs.memory.getMTag

class JSParser(private val s: JSParseState) {
    
    private val ctx = s.ctx
    
    /* 函数编译上下文（嵌套栈）：记录已发射的 loc 指令（供闭包捕获时回改）与
     * 已提升的捕获槽映射。顶层上下文在 parse() 入口压入 */
    private class LocEmit(val name: String, val byteCode: JSValue, val pos: Int, val isPut: Boolean)
    
    private class FuncCompileScope(val curFunc: JSValue, val byteCode: JSValue) {
        var localVarsLen = 0                                   /* 本层局部变量数（参数+声明） */
        var byteCodeLen = 0                                    /* 本层已发射长度（emitOp 同步，供捕获回改扫描） */
        val varrefSlots = HashMap<String, Int>()               /* 被内层捕获的局部 var → varref 池槽 */
        val locEmitLog = mutableListOf<LocEmit>()              /* 已发射的 get_loc/put_loc 记录 */
    }
    
    private val compileScopes = ArrayDeque<FuncCompileScope>()
    
    fun parse(): JSValue {
        if (!initFunction()) return JS_EXCEPTION
        
        compileScopes.addLast(FuncCompileScope(s.curFunc, s.byteCode))
        
        if (s.hasRetval) {
            s.evalRetIdx = addLocalVar(JS_NewString(ctx, "_ret_"))
        }
        
        while (s.token.val1 != JSTokenVal.TOK_EOF.value && !s.hasError) {
            if (!parseStatement()) break
        }
        
        compileScopes.removeLast()
        
        if (s.hasError) {
            return JS_EXCEPTION
        }
        
        return finalizeFunction()
    }
    
    fun initFunction(): Boolean {
        s.curFunc = allocFunctionBytecode()
        if (JS_IsException(s.curFunc)) return false
        
        s.byteCode = allocByteArray(256)
        if (JS_IsException(s.byteCode)) return false
        
        s.byteCodeLen = 0
        s.nextToken()
        return true
    }
    
    fun finalizeFunction(): JSValue {
        if (s.evalRetIdx >= 0) {
            emitOpWithArg(OPCodeEnum.OP_get_loc, s.evalRetIdx)
            emitOp(OPCodeEnum.OP_return)
        } else {
            emitOp(OPCodeEnum.OP_return_undef)
        }
        val funcPtr = JS_VALUE_TO_PTR(s.curFunc)
        ctx.memory.putJSValue(funcPtr + 16, s.byteCode)
        val byteCodePtr = JS_VALUE_TO_PTR(s.byteCode)
        ctx.memory.putI32(byteCodePtr + 4, s.byteCodeLen)
        return s.curFunc
    }
    
    fun parseStatement(): Boolean {
        when (s.token.val1) {
            ';'.code -> {
                s.nextToken()
                return true
            }
            '{'.code -> {
                return parseBlock()
            }
            JSTokenVal.TOK_VAR.value -> {
                return parseVarDecl()
            }
            JSTokenVal.TOK_FUNCTION.value -> {
                return parseFunctionDecl()
            }
            JSTokenVal.TOK_IF.value -> {
                return parseIf()
            }
            JSTokenVal.TOK_WHILE.value -> {
                return parseWhile()
            }
            JSTokenVal.TOK_DO.value -> {
                return parseDoWhile()
            }
            JSTokenVal.TOK_FOR.value -> {
                return parseFor()
            }
            JSTokenVal.TOK_BREAK.value -> {
                s.nextToken()
                if (s.token.val1 == JSTokenVal.TOK_IDENT.value) {
                    s.nextToken()
                }
                expectSemi()
                /* 跳转目标占位：由最内层循环/switch 编译结束时 patch 到出口 */
                val labels = s.breakLabelStack.lastOrNull()
                if (labels == null) {
                    s.error("illegal break statement")
                    return false
                }
                labels.add(emitJump(OPCodeEnum.OP_goto))
                return true
            }
            JSTokenVal.TOK_CONTINUE.value -> {
                s.nextToken()
                if (s.token.val1 == JSTokenVal.TOK_IDENT.value) {
                    s.nextToken()
                }
                expectSemi()
                /* 跳转目标占位：由最内层循环编译结束时 patch 到条件/update 处 */
                val labels = s.continueLabelStack.lastOrNull()
                if (labels == null) {
                    s.error("illegal continue statement")
                    return false
                }
                labels.add(emitJump(OPCodeEnum.OP_goto))
                return true
            }
            JSTokenVal.TOK_RETURN.value -> {
                s.nextToken()
                if (s.token.val1 != ';'.code && s.token.val1 != '}'.code && s.token.val1 != JSTokenVal.TOK_EOF.value && !s.gotLf) {
                    if (!parseExpr()) return false
                    emitOp(OPCodeEnum.OP_return)
                } else {
                    emitOp(OPCodeEnum.OP_return_undef)
                }
                expectSemi()
                return true
            }
            JSTokenVal.TOK_THROW.value -> {
                s.nextToken()
                if (!parseExpr()) return false
                emitOp(OPCodeEnum.OP_throw)
                expectSemi()
                return true
            }
            JSTokenVal.TOK_TRY.value -> {
                return parseTry()
            }
            JSTokenVal.TOK_SWITCH.value -> {
                return parseSwitch()
            }
            else -> {
                return parseExprStatement()
            }
        }
    }
    
    fun parseBlock(): Boolean {
        expect('{'.code)
        while (s.token.val1 != '}'.code && s.token.val1 != JSTokenVal.TOK_EOF.value && !s.hasError) {
            if (!parseStatement()) return false
        }
        expect('}'.code)
        return true
    }
    
    fun parseVarDecl(): Boolean {
        s.nextToken()
        do {
            if (s.token.val1 != JSTokenVal.TOK_IDENT.value) {
                s.error("expected identifier")
                return false
            }
            val name = s.token.value
            s.nextToken()
            
            if (s.isRepl) {
                // REPL 模式：将变量存储到全局对象
                // 栈顺序需要是 [obj, val]
                emitOp(OPCodeEnum.OP_push_this)  // 先压入全局对象
                
                if (s.token.val1 == '='.code) {
                    s.nextToken()
                    if (!parseAssignExpr()) return false
                } else {
                    emitOp(OPCodeEnum.OP_undefined)
                }
                
                val cidx = addConstPool(name)
                emitOpWithArg(OPCodeEnum.OP_put_field, cidx)
            } else {
                // 正常模式：将变量存储到局部变量（顶层脚本亦为顶层函数帧的局部）
                if (s.token.val1 == '='.code) {
                    s.nextToken()
                    if (!parseAssignExpr()) return false
                } else {
                    emitOp(OPCodeEnum.OP_undefined)
                }
                
                val idx = addLocalVar(name)
                emitOpWithArg(OPCodeEnum.OP_put_loc, idx)
            }
            
        } while (s.token.val1 == ','.code && run { s.nextToken(); true })
        
        expectSemi()
        return true
    }
    
    fun parseFunctionDecl(): Boolean {
        s.nextToken()
        
        if (s.token.val1 != JSTokenVal.TOK_IDENT.value) {
            s.error("expected function name")
            return false
        }
        val funcName = s.token.value
        s.nextToken()
        
        val savedFunc = s.curFunc
        val savedByteCode = s.byteCode
        val savedByteCodeLen = s.byteCodeLen
        val savedLocalVarsLen = s.localVarsLen
        val savedEvalRetIdx = s.evalRetIdx
        
        s.curFunc = allocFunctionBytecode()
        if (JS_IsException(s.curFunc)) {
            s.curFunc = savedFunc
            return false
        }
        
        setFunctionName(s.curFunc, funcName)
        
        s.byteCode = allocByteArray(256)
        s.byteCodeLen = 0
        s.localVarsLen = 0
        s.evalRetIdx = -1
        
        /* 函数编译上下文入栈（闭包捕获的回改作用域）——byteCode 为本层对象，
           内层捕获本层变量时据此回改本层已发射的 get_loc/put_loc */
        compileScopes.addLast(FuncCompileScope(s.curFunc, s.byteCode))
        
        expect('('.code)
        val argCount = parseParams()
        expect(')'.code)
        
        setArgCount(s.curFunc, argCount)
        
        expect('{'.code)
        while (s.token.val1 != '}'.code && s.token.val1 != JSTokenVal.TOK_EOF.value && !s.hasError) {
            if (!parseStatement()) break
        }
        expect('}'.code)
        
        emitOp(OPCodeEnum.OP_return_undef)
        
        val newFuncPtr = JS_VALUE_TO_PTR(s.curFunc)
        ctx.memory.putJSValue(newFuncPtr + 16, s.byteCode)
        val byteCodePtr = JS_VALUE_TO_PTR(s.byteCode)
        ctx.memory.putI32(byteCodePtr + 4, s.byteCodeLen)
        
        val newFunc = s.curFunc
        s.curFunc = savedFunc
        s.byteCode = savedByteCode
        s.byteCodeLen = savedByteCodeLen
        s.localVarsLen = savedLocalVarsLen
        s.evalRetIdx = savedEvalRetIdx
        compileScopes.removeLast()
        
        val cpoolIdx = addConstPool(newFunc)
        emitOpWithArg(OPCodeEnum.OP_fclosure, cpoolIdx)
        
        /* 函数名绑定：闭包对象存入局部变量（fclosure 压栈后写入，对照 C 的函数声明 hoisting） */
        val nameIdx = addLocalVar(funcName)
        emitOpWithArg(OPCodeEnum.OP_put_loc, nameIdx)
        
        return !s.hasError
    }
    
    fun parseParams(): Int {
        var count = 0
        if (s.token.val1 != ')'.code) {
            do {
                if (s.token.val1 != JSTokenVal.TOK_IDENT.value) {
                    s.error("expected parameter name")
                    return count
                }
                addLocalVar(s.token.value)
                count++
                s.nextToken()
            } while (s.token.val1 == ','.code && run { s.nextToken(); true })
        }
        return count
    }
    
    fun parseIf(): Boolean {
        s.nextToken()
        expect('('.code)
        if (!parseExpr()) return false
        expect(')'.code)
        
        val elsePos = emitJump(OPCodeEnum.OP_if_false)
        
        if (!parseStatement()) return false
        
        if (s.token.val1 == JSTokenVal.TOK_ELSE.value) {
            s.nextToken()
            val endPos = emitJump(OPCodeEnum.OP_goto)
            patchJump(elsePos)
            if (!parseStatement()) return false
            patchJump(endPos)
        } else {
            patchJump(elsePos)
        }
        
        return true
    }
    
    fun parseWhile(): Boolean {
        s.nextToken()
        expect('('.code)
        
        /* break → 循环出口；continue → 条件（condPos 在体编译前已知） */
        val breakLabels = mutableListOf<Int>()
        val continueLabels = mutableListOf<Int>()
        s.breakLabelStack.addLast(breakLabels)
        s.continueLabelStack.addLast(continueLabels)
        
        val condPos = s.byteCodeLen
        try {
            if (!parseExpr()) return false
            expect(')'.code)
            
            val endPos = emitJump(OPCodeEnum.OP_if_false)
            
            if (!parseStatement()) return false
            
            emitJumpTo(OPCodeEnum.OP_goto, condPos)
            patchJump(endPos)
            
            val exitPos = s.byteCodeLen
            for (p in continueLabels) patchJumpTo(p, condPos)
            for (p in breakLabels) patchJumpTo(p, exitPos)
            return true
        } finally {
            s.breakLabelStack.removeLast()
            s.continueLabelStack.removeLast()
        }
    }
    
    fun parseDoWhile(): Boolean {
        s.nextToken()
        
        val breakLabels = mutableListOf<Int>()
        val continueLabels = mutableListOf<Int>()
        s.breakLabelStack.addLast(breakLabels)
        s.continueLabelStack.addLast(continueLabels)
        
        val startPos = s.byteCodeLen
        try {
            if (!parseStatement()) return false
            
            /* continue 目标 = 条件判断处（体编译后的当前位置） */
            val condPos = s.byteCodeLen
            expect(JSTokenVal.TOK_WHILE.value)
            expect('('.code)
            if (!parseExpr()) return false
            expect(')'.code)
            expect(';'.code)
            
            emitJumpTo(OPCodeEnum.OP_if_true, startPos)
            
            val exitPos = s.byteCodeLen
            for (p in continueLabels) patchJumpTo(p, condPos)
            for (p in breakLabels) patchJumpTo(p, exitPos)
            return true
        } finally {
            s.breakLabelStack.removeLast()
            s.continueLabelStack.removeLast()
        }
    }
    
    fun parseFor(): Boolean {
        s.nextToken()
        expect('('.code)
        
        /* break → 循环出口；continue → update 处（体编译后跳回 update 再进条件） */
        val breakLabels = mutableListOf<Int>()
        val continueLabels = mutableListOf<Int>()
        s.breakLabelStack.addLast(breakLabels)
        s.continueLabelStack.addLast(continueLabels)
        
        try {
            if (s.token.val1 != ';'.code) {
                if (s.token.val1 == JSTokenVal.TOK_VAR.value) {
                    if (!parseVarDecl()) return false
                } else {
                    if (!parseExpr()) return false
                    emitOp(OPCodeEnum.OP_drop)
                    expect(';'.code)
                }
            } else {
                s.nextToken()
            }
            
            /* 物理布局对照 C parse_for：cond 与 if_false 在前，body 在中，
             * update 在 body 之后。update 的源 token 在 body 源之前，
             * 对照 C 的 expr3_pos/js_skip_expr/js_parse_seek：先记录并跳过
             * update 源，body 编译完成后回跳源位置编译 update */
            val condPos = s.byteCodeLen
            var endPos = -1
            
            if (s.token.val1 != ';'.code) {
                if (!parseExpr()) return false
                endPos = emitJump(OPCodeEnum.OP_if_false)
                expect(';'.code)
                /* 消费 ';' 后 s.token 已是 update 首词、bufPos 在其后：快照此状态供回跳 */
                val updateSrcBuf = s.bufPos
                val updateSrcLine = s.lineNum
                val updateSrcCol = s.colNum
                val updateSrcLf = s.gotLf
                val updateSrcToken = s.token.copy()
                
                /* 跳过 update 源 token */
                if (s.token.val1 != ')'.code) {
                    skipExpr()
                }
                /* 此时 s.token == ')' 且 bufPos 已指向 body 源首：先记录再消费 ')' */
                val bodySrcBuf = s.bufPos
                val bodySrcLine = s.lineNum
                val bodySrcCol = s.colNum
                val bodySrcLf = s.gotLf
                expect(')'.code)
                
                if (!parseStatement()) return false
                
                /* body 尾跳回 update（目标占位，update 紧随其后发射） */
                val gotoUpdate = emitJump(OPCodeEnum.OP_goto)
                val updatePos = s.byteCodeLen
                val bodyEndBuf = s.bufPos
                val bodyEndLine = s.lineNum
                val bodyEndCol = s.colNum
                val bodyEndLf = s.gotLf
                val bodyEndToken = s.token.copy()
                
                /* 回跳 update 源位置编译 update 表达式 */
                seekSource(updateSrcBuf, updateSrcLine, updateSrcCol, updateSrcLf, updateSrcToken)
                if (s.token.val1 != ')'.code) {
                    if (!parseExpr()) return false
                    emitOp(OPCodeEnum.OP_drop)
                }
                expect(')'.code)
                
                emitJumpTo(OPCodeEnum.OP_goto, condPos)
                
                /* body 尾的 goto 目标是 updatePos（不能用当前位定址） */
                patchJumpTo(gotoUpdate, updatePos)
                
                if (endPos >= 0) {
                    patchJump(endPos)
                }
                
                /* 回跳 body 之后的源位置，继续解析后续语句 */
                seekSource(bodyEndBuf, bodyEndLine, bodyEndCol, bodyEndLf, bodyEndToken)
                
                val exitPos = s.byteCodeLen
                for (p in continueLabels) patchJumpTo(p, updatePos)
                for (p in breakLabels) patchJumpTo(p, exitPos)
                return true
            } else {
                /* 无条件的 for：cond 分支不存在，body/闭括号按普通顺序处理 */
                s.nextToken()
                if (!parseStatement()) return false
                emitJumpTo(OPCodeEnum.OP_goto, condPos)
                val exitPos = s.byteCodeLen
                for (p in breakLabels) patchJumpTo(p, exitPos)
                return true
            }
        } finally {
            s.breakLabelStack.removeLast()
            s.continueLabelStack.removeLast()
        }
    }
    
    /* 跳过一个表达式源的 token（用于 for 的 update 延迟编译，对照 C js_skip_expr）：
     * 仅做括号深度平衡，遇到与 ')' 配平的 ')' 即停 */
    private fun skipExpr() {
        var depth = 0
        while (s.token.val1 != JSTokenVal.TOK_EOF.value && !s.hasError) {
            when (s.token.val1) {
                '('.code, '['.code, '{'.code -> depth++
                ')'.code -> if (depth == 0) return else depth--
                ']'.code, '}'.code -> depth--
            }
            s.nextToken()
        }
    }
    
    /* 解析位置回跳（对照 C js_parse_seek）：恢复源缓冲位置与已缓存的 token。
     * bufPos 指向"已读 token 之后"，直接 nextToken 会丢失缓存中的当前 token
     * （例如 body 块后的语句首词），因此必须同时恢复 token 快照 */
    private fun seekSource(bufPos: Int, lineNum: Int, colNum: Int, gotLf: Boolean,
                           tokenSnapshot: JSToken?) {
        s.bufPos = bufPos
        s.lineNum = lineNum
        s.colNum = colNum
        s.gotLf = gotLf
        if (tokenSnapshot != null) {
            s.token = tokenSnapshot.copy()
        } else {
            s.nextToken()
        }
    }
    
    fun parseTry(): Boolean {
        s.nextToken()
        
        /* 对照 C js_parse_statement 的 try 编译：
         * - try 块前 OP_catch 压入 catch 目标偏移（异常时由 VM 替换为异常值）
         * - 正常路径丢弃 offset 后经 gosub 执行 finally
         * - catch(e) 将异常值存入局部变量；catch 块内异常经 catch2 保护后重抛
         * - 无 catch 的 finally 在异常路径 gosub 后重抛 */
        val jumpsToFinally = mutableListOf<Int>()
        val jumpsToEnd = mutableListOf<Int>()
        val labelCatch = emitJump(OPCodeEnum.OP_catch)
        
        if (!parseBlock()) return false
        
        emitOp(OPCodeEnum.OP_drop)                         /* 丢弃 catch offset */
        emitOp(OPCodeEnum.OP_undefined)                    /* 占位保持栈平衡 */
        jumpsToFinally.add(emitJump(OPCodeEnum.OP_gosub))
        emitOp(OPCodeEnum.OP_drop)                         /* 丢弃 gosub 返回地址 */
        jumpsToEnd.add(emitJump(OPCodeEnum.OP_goto))
        
        var hasCatch = false
        var labelCatch2 = -1
        
        if (s.token.val1 == JSTokenVal.TOK_CATCH.value) {
            hasCatch = true
            s.nextToken()
            expect('('.code)
            if (s.token.val1 != JSTokenVal.TOK_IDENT.value) {
                s.error("expected identifier")
                return false
            }
            val name = s.token.value
            val catchVarIdx = addLocalVar(name)
            s.nextToken()
            expect(')'.code)
            
            /* label_catch：异常值已由 VM 压入栈顶，存入 catch 变量 */
            patchJump(labelCatch)
            emitOpWithArg(OPCodeEnum.OP_put_loc, catchVarIdx)
            labelCatch2 = emitJump(OPCodeEnum.OP_catch)    /* catch 块内异常保护 */
            
            if (!parseBlock()) return false
            
            emitOp(OPCodeEnum.OP_drop)                     /* 丢弃 catch2 offset */
            emitOp(OPCodeEnum.OP_undefined)
            jumpsToFinally.add(emitJump(OPCodeEnum.OP_gosub))
            emitOp(OPCodeEnum.OP_drop)
            jumpsToEnd.add(emitJump(OPCodeEnum.OP_goto))
            
            /* label_catch2：catch 块内抛出的异常（异常值在栈顶）执行 finally 后重抛 */
            patchJump(labelCatch2)
            jumpsToFinally.add(emitJump(OPCodeEnum.OP_gosub))
            emitOp(OPCodeEnum.OP_throw)
        } else if (s.token.val1 == JSTokenVal.TOK_FINALLY.value) {
            /* 无 catch 的 finally：异常路径执行 finally 后重抛 */
            patchJump(labelCatch)
            jumpsToFinally.add(emitJump(OPCodeEnum.OP_gosub))
            emitOp(OPCodeEnum.OP_throw)
        } else {
            s.error("expecting catch or finally")
            return false
        }
        
        /* label_finally：所有 gosub 汇合（栈顶为 gosub 返回地址） */
        for (pos in jumpsToFinally) patchJump(pos)
        if (s.token.val1 == JSTokenVal.TOK_FINALLY.value) {
            s.nextToken()
            if (!parseBlock()) return false
        }
        emitOp(OPCodeEnum.OP_ret)
        
        /* label_end */
        for (pos in jumpsToEnd) patchJump(pos)
        
        return true
    }
    
    fun parseSwitch(): Boolean {
        s.nextToken()
        expect('('.code)
        if (!parseExpr()) return false
        expect(')'.code)
        expect('{'.code)
        
        /* switch 也是合法的 break 环境（对照 C push_break_entry）；
         * case 值的分发跳转链尚未实现（既有半成品），break 跳到 switch 尾 */
        val breakLabels = mutableListOf<Int>()
        s.breakLabelStack.addLast(breakLabels)
        
        val defaultPos = -1
        val endPos = emitJump(OPCodeEnum.OP_goto)
        
        try {
            while (s.token.val1 != '}'.code && s.token.val1 != JSTokenVal.TOK_EOF.value && !s.hasError) {
                when (s.token.val1) {
                    JSTokenVal.TOK_CASE.value -> {
                        s.nextToken()
                        if (!parseExpr()) return false
                        expect(':'.code)
                        while (s.token.val1 != JSTokenVal.TOK_CASE.value && 
                               s.token.val1 != JSTokenVal.TOK_DEFAULT.value && 
                               s.token.val1 != '}'.code) {
                            if (!parseStatement()) return false
                        }
                    }
                    JSTokenVal.TOK_DEFAULT.value -> {
                        s.nextToken()
                        expect(':'.code)
                        while (s.token.val1 != JSTokenVal.TOK_CASE.value && 
                               s.token.val1 != '}'.code) {
                            if (!parseStatement()) return false
                        }
                    }
                    else -> {
                        s.error("expected case or default")
                        return false
                    }
                }
            }
            
            expect('}'.code)
            val exitPos = s.byteCodeLen
            for (p in breakLabels) patchJumpTo(p, exitPos)
            return true
        } finally {
            s.breakLabelStack.removeLast()
        }
    }
    
    fun parseExprStatement(): Boolean {
        if (s.token.val1 == JSTokenVal.TOK_FUNCTION.value) {
            return parseFunctionDecl()
        }
        
        if (!parseExpr()) return false
        if (s.evalRetIdx >= 0) {
            emitOpWithArg(OPCodeEnum.OP_put_loc, s.evalRetIdx)
        } else {
            emitOp(OPCodeEnum.OP_drop)
        }
        expectSemi()
        return true
    }
    
    fun expectSemi() {
        if (s.token.val1 != ';'.code) {
            if (s.token.val1 == JSTokenVal.TOK_EOF.value || s.token.val1 == '}'.code || s.gotLf) {
                return
            }
            s.error("expected ';'")
        } else {
            s.nextToken()
        }
    }
    
    fun parseExpr(): Boolean {
        return parseAssignExpr()
    }
    
    fun parseAssignExpr(): Boolean {
        if (!parseConditionalExpr()) return false
        
        when (s.token.val1) {
            '='.code -> {
                val lvalueOp = getLastOp()
                val lvalueArg = getLastOpArg()
                removeLastOp()
                
                s.nextToken()
                if (!parseAssignExpr()) return false
                
                /* rhs 值复制后保存到临时槽（put_* 写回会消费栈上的副本），
                   读回 tmp 作为赋值表达式的值 */
                emitOp(OPCodeEnum.OP_dup)
                val tmpIdx = ensureTmpVar()
                emitOpWithArg(OPCodeEnum.OP_put_loc, tmpIdx)
                
                when (lvalueOp) {
                    OPCodeEnum.OP_get_loc.id -> emitOpWithArg(OPCodeEnum.OP_put_loc, lvalueArg)
                    OPCodeEnum.OP_get_var_ref.id -> emitOpWithArg(OPCodeEnum.OP_put_var_ref, lvalueArg)
                    OPCodeEnum.OP_get_global.id -> emitOpWithArg(OPCodeEnum.OP_put_global, lvalueArg)
                    OPCodeEnum.OP_get_field.id -> emitOpWithArg(OPCodeEnum.OP_put_field, lvalueArg)
                    OPCodeEnum.OP_get_array_el.id -> emitOp(OPCodeEnum.OP_put_array_el)
                }
                
                emitOpWithArg(OPCodeEnum.OP_get_loc, tmpIdx)
                return true
            }
            JSTokenVal.TOK_PLUS_ASSIGN.value -> return emitCompoundAssign(OPCodeEnum.OP_add)
            JSTokenVal.TOK_MINUS_ASSIGN.value -> return emitCompoundAssign(OPCodeEnum.OP_sub)
            JSTokenVal.TOK_MUL_ASSIGN.value -> return emitCompoundAssign(OPCodeEnum.OP_mul)
            JSTokenVal.TOK_DIV_ASSIGN.value -> return emitCompoundAssign(OPCodeEnum.OP_div)
            JSTokenVal.TOK_MOD_ASSIGN.value -> return emitCompoundAssign(OPCodeEnum.OP_mod)
            JSTokenVal.TOK_SHL_ASSIGN.value -> return emitCompoundAssign(OPCodeEnum.OP_shl)
            JSTokenVal.TOK_SAR_ASSIGN.value -> return emitCompoundAssign(OPCodeEnum.OP_sar)
            JSTokenVal.TOK_SHR_ASSIGN.value -> return emitCompoundAssign(OPCodeEnum.OP_shr)
            JSTokenVal.TOK_AND_ASSIGN.value -> return emitCompoundAssign(OPCodeEnum.OP_and)
            JSTokenVal.TOK_XOR_ASSIGN.value -> return emitCompoundAssign(OPCodeEnum.OP_xor)
            JSTokenVal.TOK_OR_ASSIGN.value -> return emitCompoundAssign(OPCodeEnum.OP_or)
        }
        return true
    }
    
    /* lvalue 读取指令的重发：仅复合赋值需要（读取旧值参与运算）。
     * get_field/get_array_el 重发为保留 obj 的对象携带形式（get_field2/get_array_el2）；
     * 简单 = 无需重发——removeLastOp 已移除 lhs 读取指令，rhs 后 dup+put_* 即可
     * （重发读取会在栈上残留旧值，破坏 put_* 的 obj/val 配对） */
    private fun reemitLValueLoad(lvalueOp: Int, lvalueArg: Int, forCompound: Boolean) {
        if (!forCompound) return
        when (lvalueOp) {
            OPCodeEnum.OP_get_field.id -> emitOpWithArg(OPCodeEnum.OP_get_field2, lvalueArg)
            OPCodeEnum.OP_get_array_el.id -> emitOpWithArg(OPCodeEnum.OP_get_array_el2, lvalueArg)
            OPCodeEnum.OP_get_loc.id -> emitOpWithArg(OPCodeEnum.OP_get_loc, lvalueArg)
            OPCodeEnum.OP_get_var_ref.id -> emitOpWithArg(OPCodeEnum.OP_get_var_ref, lvalueArg)
            OPCodeEnum.OP_get_global.id -> emitOpWithArg(OPCodeEnum.OP_get_global, lvalueArg)
        }
    }
    
    /* 对象携带形式的读取（get_field2/get_array_el2 压栈后保留 obj 供 put_* 消费） */
    private fun isObjCarrying(lvalueOp: Int): Boolean {
        return lvalueOp == OPCodeEnum.OP_get_field.id || lvalueOp == OPCodeEnum.OP_get_array_el.id
    }
    
    /* 复合赋值（x op= v）：读取旧值 → rhs → 运算 → 复制结果 → 临时槽保存 → 写回 →
       读回临时槽作为表达式值（对照 C put_lvalue 的值语义，规避栈序问题） */
    private fun emitCompoundAssign(binOp: OPCodeEnum): Boolean {
        val lvalueOp = getLastOp()
        val lvalueArg = getLastOpArg()
        removeLastOp()
        
        /* 复合赋值需要读取旧值参与运算（槽目标也要读） */
        reemitLValueLoad(lvalueOp, lvalueArg, forCompound = true)
        
        s.nextToken()
        if (!parseAssignExpr()) return false
        emitOp(binOp)
        
        /* 结果复制后保存到临时槽，写回消费栈上副本，读回作为表达式值 */
        emitOp(OPCodeEnum.OP_dup)
        val tmpIdx = ensureTmpVar()
        emitOpWithArg(OPCodeEnum.OP_put_loc, tmpIdx)
        
        when (lvalueOp) {
            OPCodeEnum.OP_get_loc.id -> emitOpWithArg(OPCodeEnum.OP_put_loc, lvalueArg)
            OPCodeEnum.OP_get_var_ref.id -> emitOpWithArg(OPCodeEnum.OP_put_var_ref, lvalueArg)
            OPCodeEnum.OP_get_global.id -> emitOpWithArg(OPCodeEnum.OP_put_global, lvalueArg)
            OPCodeEnum.OP_get_field.id -> emitOpWithArg(OPCodeEnum.OP_put_field, lvalueArg)
            OPCodeEnum.OP_get_array_el.id -> emitOp(OPCodeEnum.OP_put_array_el)
        }
        emitOpWithArg(OPCodeEnum.OP_get_loc, tmpIdx)
        return true
    }
    
    fun parseAssignment(): Boolean {
        val lastOp = getLastOp()
        when (lastOp) {
            OPCodeEnum.OP_get_loc.id -> {
                val idx = getLastOpArg()
                removeLastOp()
                emitOpWithArg(OPCodeEnum.OP_put_loc, idx)
            }
            OPCodeEnum.OP_get_field.id -> {
                val idx = getLastOpArg()
                removeLastOp()
                emitOpWithArg(OPCodeEnum.OP_put_field, idx)
            }
            OPCodeEnum.OP_get_array_el.id -> {
                removeLastOp()
                emitOp(OPCodeEnum.OP_put_array_el)
            }
        }
        return true
    }
    
    fun getLastOp(): Int {
        if (s.lastOpcodePos < 0) return -1
        val ptr = JS_VALUE_TO_PTR(s.byteCode)
        return ctx.memory.getU8(ptr + 8 + s.lastOpcodePos).toInt()
    }
    
    fun getLastOpArg(): Int {
        if (s.lastOpcodePos < 0) return -1
        val ptr = JS_VALUE_TO_PTR(s.byteCode)
        return ctx.memory.getU16(ptr + 8 + s.lastOpcodePos + 1)
    }
    
    fun removeLastOp() {
        if (s.lastOpcodePos < 0) return
        s.byteCodeLen = s.lastOpcodePos
        s.lastOpcodePos = -1
    }
    
    fun parseConditionalExpr(): Boolean {
        if (!parseLogicalOrExpr()) return false
        
        if (s.token.val1 == '?'.code) {
            s.nextToken()
            val elsePos = emitJump(OPCodeEnum.OP_if_false)
            if (!parseAssignExpr()) return false
            val endPos = emitJump(OPCodeEnum.OP_goto)
            patchJump(elsePos)
            expect(':'.code)
            if (!parseConditionalExpr()) return false
            patchJump(endPos)
        }
        
        return true
    }
    
    fun parseLogicalOrExpr(): Boolean {
        if (!parseLogicalAndExpr()) return false
        
        while (s.token.val1 == JSTokenVal.TOK_LOR.value) {
            s.nextToken()
            val endPos = emitJump(OPCodeEnum.OP_if_true)
            emitOp(OPCodeEnum.OP_drop)
            if (!parseLogicalAndExpr()) return false
            patchJump(endPos)
        }
        
        return true
    }
    
    fun parseLogicalAndExpr(): Boolean {
        if (!parseBitwiseOrExpr()) return false
        
        while (s.token.val1 == JSTokenVal.TOK_LAND.value) {
            s.nextToken()
            val endPos = emitJump(OPCodeEnum.OP_if_false)
            emitOp(OPCodeEnum.OP_drop)
            if (!parseBitwiseOrExpr()) return false
            patchJump(endPos)
        }
        
        return true
    }
    
    fun parseBitwiseOrExpr(): Boolean {
        if (!parseBitwiseXorExpr()) return false
        
        while (s.token.val1 == '|'.code) {
            s.nextToken()
            if (!parseBitwiseXorExpr()) return false
            emitOp(OPCodeEnum.OP_or)
        }
        
        return true
    }
    
    fun parseBitwiseXorExpr(): Boolean {
        if (!parseBitwiseAndExpr()) return false
        
        while (s.token.val1 == '^'.code) {
            s.nextToken()
            if (!parseBitwiseAndExpr()) return false
            emitOp(OPCodeEnum.OP_xor)
        }
        
        return true
    }
    
    fun parseBitwiseAndExpr(): Boolean {
        if (!parseEqualityExpr()) return false
        
        while (s.token.val1 == '&'.code) {
            s.nextToken()
            if (!parseEqualityExpr()) return false
            emitOp(OPCodeEnum.OP_and)
        }
        
        return true
    }
    
    fun parseEqualityExpr(): Boolean {
        if (!parseRelationalExpr()) return false
        
        while (true) {
            when (s.token.val1) {
                JSTokenVal.TOK_EQ.value -> {
                    s.nextToken()
                    if (!parseRelationalExpr()) return false
                    emitOp(OPCodeEnum.OP_eq)
                }
                JSTokenVal.TOK_NEQ.value -> {
                    s.nextToken()
                    if (!parseRelationalExpr()) return false
                    emitOp(OPCodeEnum.OP_neq)
                }
                JSTokenVal.TOK_STRICT_EQ.value -> {
                    s.nextToken()
                    if (!parseRelationalExpr()) return false
                    emitOp(OPCodeEnum.OP_strict_eq)
                }
                JSTokenVal.TOK_STRICT_NEQ.value -> {
                    s.nextToken()
                    if (!parseRelationalExpr()) return false
                    emitOp(OPCodeEnum.OP_strict_neq)
                }
                else -> break
            }
        }
        
        return true
    }
    
    fun parseRelationalExpr(): Boolean {
        if (!parseShiftExpr()) return false
        
        while (true) {
            when (s.token.val1) {
                '<'.code -> {
                    s.nextToken()
                    if (!parseShiftExpr()) return false
                    emitOp(OPCodeEnum.OP_lt)
                }
                '>'.code -> {
                    s.nextToken()
                    if (!parseShiftExpr()) return false
                    emitOp(OPCodeEnum.OP_gt)
                }
                JSTokenVal.TOK_LTE.value -> {
                    s.nextToken()
                    if (!parseShiftExpr()) return false
                    emitOp(OPCodeEnum.OP_lte)
                }
                JSTokenVal.TOK_GTE.value -> {
                    s.nextToken()
                    if (!parseShiftExpr()) return false
                    emitOp(OPCodeEnum.OP_gte)
                }
                JSTokenVal.TOK_IN.value -> {
                    s.nextToken()
                    if (!parseShiftExpr()) return false
                    emitOp(OPCodeEnum.OP_in)
                }
                JSTokenVal.TOK_INSTANCEOF.value -> {
                    s.nextToken()
                    if (!parseShiftExpr()) return false
                    emitOp(OPCodeEnum.OP_instanceof)
                }
                else -> break
            }
        }
        
        return true
    }
    
    fun parseShiftExpr(): Boolean {
        if (!parseAdditiveExpr()) return false
        
        while (true) {
            when (s.token.val1) {
                JSTokenVal.TOK_SHL.value -> {
                    s.nextToken()
                    if (!parseAdditiveExpr()) return false
                    emitOp(OPCodeEnum.OP_shl)
                }
                JSTokenVal.TOK_SAR.value -> {
                    s.nextToken()
                    if (!parseAdditiveExpr()) return false
                    emitOp(OPCodeEnum.OP_sar)
                }
                JSTokenVal.TOK_SHR.value -> {
                    s.nextToken()
                    if (!parseAdditiveExpr()) return false
                    emitOp(OPCodeEnum.OP_shr)
                }
                else -> break
            }
        }
        
        return true
    }
    
    fun parseAdditiveExpr(): Boolean {
        if (!parseMultiplicativeExpr()) return false
        
        while (true) {
            when (s.token.val1) {
                '+'.code -> {
                    s.nextToken()
                    if (!parseMultiplicativeExpr()) return false
                    emitOp(OPCodeEnum.OP_add)
                }
                '-'.code -> {
                    s.nextToken()
                    if (!parseMultiplicativeExpr()) return false
                    emitOp(OPCodeEnum.OP_sub)
                }
                else -> break
            }
        }
        
        return true
    }
    
    fun parseMultiplicativeExpr(): Boolean {
        if (!parseUnaryExpr()) return false
        
        while (true) {
            when (s.token.val1) {
                '*'.code -> {
                    s.nextToken()
                    if (!parseUnaryExpr()) return false
                    emitOp(OPCodeEnum.OP_mul)
                }
                '/'.code -> {
                    s.nextToken()
                    if (!parseUnaryExpr()) return false
                    emitOp(OPCodeEnum.OP_div)
                }
                '%'.code -> {
                    s.nextToken()
                    if (!parseUnaryExpr()) return false
                    emitOp(OPCodeEnum.OP_mod)
                }
                JSTokenVal.TOK_POW.value -> {
                    s.nextToken()
                    if (!parseUnaryExpr()) return false
                    emitOp(OPCodeEnum.OP_pow)
                }
                else -> break
            }
        }
        
        return true
    }
    
    fun parseUnaryExpr(): Boolean {
        when (s.token.val1) {
            '+'.code -> {
                s.nextToken()
                if (!parseUnaryExpr()) return false
                emitOp(OPCodeEnum.OP_plus)
                return true
            }
            '-'.code -> {
                s.nextToken()
                if (!parseUnaryExpr()) return false
                emitOp(OPCodeEnum.OP_neg)
                return true
            }
            '!'.code -> {
                s.nextToken()
                if (!parseUnaryExpr()) return false
                emitOp(OPCodeEnum.OP_lnot)
                return true
            }
            '~'.code -> {
                s.nextToken()
                if (!parseUnaryExpr()) return false
                emitOp(OPCodeEnum.OP_not)
                return true
            }
            JSTokenVal.TOK_INC.value -> {
                s.nextToken()
                if (!parseUnaryExpr()) return false
                emitLValueIncDec(inc = true, pre = true)
                return true
            }
            JSTokenVal.TOK_DEC.value -> {
                s.nextToken()
                if (!parseUnaryExpr()) return false
                emitLValueIncDec(inc = false, pre = true)
                return true
            }
            JSTokenVal.TOK_TYPEOF.value -> {
                s.nextToken()
                if (!parseUnaryExpr()) return false
                emitOp(OPCodeEnum.OP_typeof)
                return true
            }
            JSTokenVal.TOK_VOID.value -> {
                s.nextToken()
                if (!parseUnaryExpr()) return false
                emitOp(OPCodeEnum.OP_drop)
                emitOp(OPCodeEnum.OP_undefined)
                return true
            }
            JSTokenVal.TOK_DELETE.value -> {
                s.nextToken()
                if (!parseUnaryExpr()) return false
                emitOp(OPCodeEnum.OP_delete)
                return true
            }
            JSTokenVal.TOK_NEW.value -> {
                s.nextToken()
                if (!parseMemberExpr()) return false
                if (s.token.val1 == '('.code) {
                    s.nextToken()
                    val argc = parseArgs()
                    expect(')'.code)
                    emitOpWithArg(OPCodeEnum.OP_call_constructor, argc)
                } else {
                    emitOpWithArg(OPCodeEnum.OP_call_constructor, 0)
                }
                return true
            }
        }
        
        return parsePostfixExpr()
    }
    
    fun parsePostfixExpr(): Boolean {
        if (!parseMemberExpr()) return false
        
        while (true) {
            when (s.token.val1) {
                JSTokenVal.TOK_INC.value -> {
                    s.nextToken()
                    emitLValueIncDec(inc = true, pre = false)
                }
                JSTokenVal.TOK_DEC.value -> {
                    s.nextToken()
                    emitLValueIncDec(inc = false, pre = false)
                }
                '('.code -> {
                    s.nextToken()
                    /* 方法调用识别（对照 C js_parse_postfix_expr）：
                     * 前一条指令是 OP_get_field 时，回改为 OP_get_field2（保留 obj 作 this）
                     * 并改用 OP_call_method；否则普通调用 OP_call（this 由 VM 提供为 undefined） */
                    val isMethodCall = isLastOpcodeGetField()
                    if (isMethodCall) {
                        rewriteLastOpcode(OPCodeEnum.OP_get_field2)
                    }
                    val argc = parseArgs()
                    expect(')'.code)
                    if (isMethodCall) {
                        emitOpWithArg(OPCodeEnum.OP_call_method, argc)
                    } else {
                        emitOpWithArg(OPCodeEnum.OP_call, argc)
                    }
                }
                '['.code -> {
                    s.nextToken()
                    if (!parseExpr()) return false
                    expect(']'.code)
                    emitOp(OPCodeEnum.OP_get_array_el)
                }
                '.'.code -> {
                    s.nextToken()
                    if (s.token.val1 != JSTokenVal.TOK_IDENT.value) {
                        s.error("expected identifier after '.'")
                        return false
                    }
                    val name = s.token.value
                    s.nextToken()
                    /* 先按普通属性读取发射（消费 obj），若后续紧跟 ( 再回改为 get_field2 */
                    val idx = addConstPool(name)
                    emitOpWithArg(OPCodeEnum.OP_get_field, idx)
                }
                else -> break
            }
        }
        
        return true
    }
    
    /* member 链（. / [] / 调用 / 自增）已统一并入 parsePostfixExpr 的循环
     * （对照 C js_parse_postfix_expr 的单层结构：'.' 先发 get_field 占位，
     *  遇 ( 或 ++/-- 时回改 get_field2 / get_array_el2 并做方法调用或写回） */
    fun parseMemberExpr(): Boolean {
        return parsePrimaryExpr()
    }
    
    /* 检查最后发射的指令是否为 OP_get_field（方法调用识别用） */
    private fun isLastOpcodeGetField(): Boolean {
        if (s.lastOpcodePos < 0) return false
        val ptr = JS_VALUE_TO_PTR(s.byteCode)
        return ctx.memory.getU8(ptr + 8 + s.lastOpcodePos) == OPCodeEnum.OP_get_field.id
    }
    
    /* 将最后发射的指令改写为指定 opcode（对照 C 的 byte_code[last_opcode_pos] = ...） */
    private fun rewriteLastOpcode(op: OPCodeEnum) {
        if (s.lastOpcodePos < 0) return
        val ptr = JS_VALUE_TO_PTR(s.byteCode)
        ctx.memory.putU8(ptr + 8 + s.lastOpcodePos, op.id)
    }
    
    /* 读取字节码中指定位置的 u16 操作数 */
    private fun readU16From(pos: Int): Int {
        val ptr = JS_VALUE_TO_PTR(s.byteCode)
        return ctx.memory.getU16(ptr + 8 + pos)
    }
    
    /* 自增/自减编译期临时槽（保存表达式值；与 C 的栈重排指令等价的 Kotlin 方案） */
    private fun ensureTmpVar(): Int = addLocalVar(JS_NewString(ctx, "@inc_tmp"))
    
    /* 自增/自减的 lvalue 写回（对照 C put_lvalue 机制的 Kotlin 等价物）：
     * - 局部变量（lastOpcode=OP_get_loc）：读写变量槽
     * - 对象属性（lastOpcode=OP_get_field）：回改为 OP_get_field2 保留 obj，OP_put_field 写回
     * - 其他形式：暂不支持写回，退化为对栈顶值的 inc/dec
     * post 形式的表达式值为旧值、pre 形式为新值（对照 ES 语义） */
    private fun emitLValueIncDec(inc: Boolean, pre: Boolean) {
        val deltaOp = if (inc) OPCodeEnum.OP_push_1 else OPCodeEnum.OP_push_minus1
        val addOp = if (inc) OPCodeEnum.OP_add else OPCodeEnum.OP_sub
        
        val lastPos = s.lastOpcodePos
        val lastOp = if (lastPos >= 0) {
            val ptr = JS_VALUE_TO_PTR(s.byteCode)
            ctx.memory.getU8(ptr + 8 + lastPos).toInt()
        } else -1
        
        when (lastOp) {
            OPCodeEnum.OP_get_loc.id -> {
                val idx = readU16From(lastPos + 1)
                val tmp = ensureTmpVar()
                if (pre) {
                    emitOp(deltaOp); emitOp(addOp)              /* [new] */
                    emitOp(OPCodeEnum.OP_dup)                   /* [new, new] */
                    emitOpWithArg(OPCodeEnum.OP_put_loc, tmp)   /* [new] */
                    emitOpWithArg(OPCodeEnum.OP_put_loc, idx)   /* [] 写回 */
                    emitOpWithArg(OPCodeEnum.OP_get_loc, tmp)   /* [new] */
                } else {
                    emitOpWithArg(OPCodeEnum.OP_put_loc, tmp)   /* [] 存旧值 */
                    emitOpWithArg(OPCodeEnum.OP_get_loc, idx)   /* [old] */
                    emitOp(deltaOp); emitOp(addOp)              /* [new] */
                    emitOpWithArg(OPCodeEnum.OP_put_loc, idx)   /* [] 写回 */
                    emitOpWithArg(OPCodeEnum.OP_get_loc, tmp)   /* [old] */
                }
            }
            OPCodeEnum.OP_get_field.id -> {
                rewriteLastOpcode(OPCodeEnum.OP_get_field2)     /* [obj, old] */
                val cidx = readU16From(lastPos + 1)
                val tmp = ensureTmpVar()
                if (pre) {
                    emitOp(deltaOp); emitOp(addOp)              /* [obj, new] */
                    emitOp(OPCodeEnum.OP_dup)                   /* [obj, new, new] */
                    emitOpWithArg(OPCodeEnum.OP_put_loc, tmp)   /* [obj, new] */
                    emitOpWithArg(OPCodeEnum.OP_put_field, cidx) /* [] 写回 */
                    emitOpWithArg(OPCodeEnum.OP_get_loc, tmp)   /* [new] */
                } else {
                    emitOpWithArg(OPCodeEnum.OP_put_loc, tmp)   /* [obj] 存旧值 */
                    emitOpWithArg(OPCodeEnum.OP_get_loc, tmp)   /* [obj, old] */
                    emitOp(deltaOp); emitOp(addOp)              /* [obj, new] */
                    emitOpWithArg(OPCodeEnum.OP_put_field, cidx) /* [] 写回 */
                    emitOpWithArg(OPCodeEnum.OP_get_loc, tmp)   /* [old] */
                }
            }
            else -> {
                /* 不支持写回的 lvalue 形式：退化为对栈顶值的 inc/dec */
                emitOp(deltaOp); emitOp(addOp)
            }
        }
    }
    
    fun parsePrimaryExpr(): Boolean {
        when (s.token.val1) {
            JSTokenVal.TOK_NUMBER.value -> {
                val d = s.token.d
                val i = d.toInt()
                if (d == i.toDouble() && i in -5..7) {
                    emitOp(OPCodeEnum.entries.find { it.id == OPCodeEnum.OP_push_0.id + i } ?: OPCodeEnum.OP_push_0)
                } else if (d == i.toDouble() && i in Byte.MIN_VALUE..Byte.MAX_VALUE) {
                    emitI8(i.toByte())
                } else {
                    val idx = addConstPool(JS_NewFloat64(ctx, d))
                    emitOpWithArg(OPCodeEnum.OP_push_const, idx)
                }
                s.nextToken()
                return true
            }
            JSTokenVal.TOK_STRING.value -> {
                val idx = addConstPool(s.token.value)
                emitOpWithArg(OPCodeEnum.OP_push_const, idx)
                s.nextToken()
                return true
            }
            JSTokenVal.TOK_IDENT.value -> {
                val name = s.token.value
                s.nextToken()
                /* 变量访问统一发射：局部 get_loc / 捕获 get_var_ref / 全局 get_global */
                emitVarLoad(name)
                return true
            }
            JSTokenVal.TOK_NULL.value -> {
                emitOp(OPCodeEnum.OP_null)
                s.nextToken()
                return true
            }
            JSTokenVal.TOK_TRUE.value -> {
                emitOp(OPCodeEnum.OP_push_true)
                s.nextToken()
                return true
            }
            JSTokenVal.TOK_FALSE.value -> {
                emitOp(OPCodeEnum.OP_push_false)
                s.nextToken()
                return true
            }
            JSTokenVal.TOK_THIS.value -> {
                emitOp(OPCodeEnum.OP_push_this)
                s.nextToken()
                return true
            }
            '('.code -> {
                s.nextToken()
                if (!parseExpr()) return false
                expect(')'.code)
                return true
            }
            '{'.code -> {
                return parseObjectLiteral()
            }
            '['.code -> {
                return parseArrayLiteral()
            }
            JSTokenVal.TOK_FUNCTION.value -> {
                return parseFunctionExpr()
            }
            else -> {
                s.error("unexpected token")
                return false
            }
        }
    }
    
    fun parseObjectLiteral(): Boolean {
        expect('{'.code)
        emitOpWithArg(OPCodeEnum.OP_object, 0)
        
        while (s.token.val1 != '}'.code && s.token.val1 != JSTokenVal.TOK_EOF.value && !s.hasError) {
            val key: JSValue
            when (s.token.val1) {
                JSTokenVal.TOK_IDENT.value -> {
                    key = s.token.value
                    s.nextToken()
                }
                JSTokenVal.TOK_STRING.value -> {
                    key = s.token.value
                    s.nextToken()
                }
                else -> {
                    s.error("expected property name")
                    return false
                }
            }
            
            val keyIdx = addConstPool(key)
            
            if (s.token.val1 == ':'.code) {
                s.nextToken()
                if (!parseAssignExpr()) return false
                emitOpWithArg(OPCodeEnum.OP_define_field, keyIdx)
            } else {
                emitOpWithArg(OPCodeEnum.OP_get_field, keyIdx)
            }
            
            if (s.token.val1 == ','.code) {
                s.nextToken()
            }
        }
        
        expect('}'.code)
        return true
    }
    
    fun parseArrayLiteral(): Boolean {
        expect('['.code)
        
        var count = 0
        while (s.token.val1 != ']'.code && s.token.val1 != JSTokenVal.TOK_EOF.value && !s.hasError) {
            if (!parseAssignExpr()) return false
            count++
            if (s.token.val1 == ','.code) {
                s.nextToken()
            }
        }
        
        expect(']'.code)
        emitOpWithArg(OPCodeEnum.OP_array_from, count)
        return true
    }
    
    fun parseFunctionExpr(): Boolean {
        s.nextToken()
        
        val savedFunc = s.curFunc
        val savedByteCode = s.byteCode
        val savedByteCodeLen = s.byteCodeLen
        val savedLocalVarsLen = s.localVarsLen
        val savedEvalRetIdx = s.evalRetIdx
        
        s.curFunc = allocFunctionBytecode()
        if (JS_IsException(s.curFunc)) {
            s.curFunc = savedFunc
            return false
        }
        
        s.byteCode = allocByteArray(256)
        s.byteCodeLen = 0
        s.localVarsLen = 0
        s.evalRetIdx = -1
        
        /* 函数编译上下文入栈（闭包捕获的回改作用域）——byteCode 为本层对象 */
        compileScopes.addLast(FuncCompileScope(s.curFunc, s.byteCode))
        
        if (s.token.val1 == JSTokenVal.TOK_IDENT.value) {
            setFunctionName(s.curFunc, s.token.value)
            s.nextToken()
        }
        
        expect('('.code)
        val argCount = parseParams()
        expect(')'.code)
        
        setArgCount(s.curFunc, argCount)
        
        expect('{'.code)
        while (s.token.val1 != '}'.code && s.token.val1 != JSTokenVal.TOK_EOF.value && !s.hasError) {
            if (!parseStatement()) break
        }
        expect('}'.code)
        
        emitOp(OPCodeEnum.OP_return_undef)
        
        val newFuncPtr = JS_VALUE_TO_PTR(s.curFunc)
        ctx.memory.putJSValue(newFuncPtr + 16, s.byteCode)
        val byteCodePtr = JS_VALUE_TO_PTR(s.byteCode)
        ctx.memory.putI32(byteCodePtr + 4, s.byteCodeLen)
        
        val newFunc = s.curFunc
        s.curFunc = savedFunc
        s.byteCode = savedByteCode
        s.byteCodeLen = savedByteCodeLen
        s.localVarsLen = savedLocalVarsLen
        s.evalRetIdx = savedEvalRetIdx
        compileScopes.removeLast()
        
        val cpoolIdx = addConstPool(newFunc)
        emitOpWithArg(OPCodeEnum.OP_fclosure, cpoolIdx)
        
        return !s.hasError
    }
    
    fun parseArgs(): Int {
        var count = 0
        if (s.token.val1 != ')'.code) {
            do {
                if (!parseAssignExpr()) return count
                count++
            } while (s.token.val1 == ','.code && run { s.nextToken(); true })
        }
        return count
    }
    
    fun expect(tok: Int): Boolean {
        if (s.token.val1 != tok) {
            s.error("expected '${if (tok < 256) tok.toChar() else JSTokenVal.fromValue(tok)?.name ?: tok}'")
            return false
        }
        s.nextToken()
        return true
    }
    
    /* 发射指令后同步编译上下文的已发射长度（内层捕获本层变量时回改扫描用） */
    private fun syncScopeLen() {
        compileScopes.lastOrNull()?.byteCodeLen = s.byteCodeLen
    }
    
    fun emitOp(op: OPCodeEnum) {
        ensureByteCodeSize(1)
        val ptr = JS_VALUE_TO_PTR(s.byteCode)
        s.lastOpcodePos = s.byteCodeLen
        ctx.memory.putU8(ptr + 8 + s.byteCodeLen, op.id)
        s.byteCodeLen++
        syncScopeLen()
    }
    
    fun emitI8(v: Byte) {
        ensureByteCodeSize(2)
        val ptr = JS_VALUE_TO_PTR(s.byteCode)
        s.lastOpcodePos = s.byteCodeLen
        ctx.memory.putU8(ptr + 8 + s.byteCodeLen, OPCodeEnum.OP_push_i8.id)
        ctx.memory.putU8(ptr + 8 + s.byteCodeLen + 1, v.toInt())
        s.byteCodeLen += 2
    }
    
    fun emitOpWithArg(op: OPCodeEnum, arg: Int) {
        ensureByteCodeSize(3)
        val ptr = JS_VALUE_TO_PTR(s.byteCode)
        s.lastOpcodePos = s.byteCodeLen
        ctx.memory.putU8(ptr + 8 + s.byteCodeLen, op.id)
        ctx.memory.putU16(ptr + 8 + s.byteCodeLen + 1, arg)
        s.byteCodeLen += 3
        syncScopeLen()
    }
    
    fun emitJump(op: OPCodeEnum): Int {
        ensureByteCodeSize(5)
        val ptr = JS_VALUE_TO_PTR(s.byteCode)
        ctx.memory.putU8(ptr + 8 + s.byteCodeLen, op.id)
        ctx.memory.putU32(ptr + 8 + s.byteCodeLen + 1, 0L)
        val pos = s.byteCodeLen
        s.byteCodeLen += 5
        s.lastOpcodePos = -1
        return pos
    }
    
    fun emitJumpTo(op: OPCodeEnum, target: Int) {
        ensureByteCodeSize(5)
        val ptr = JS_VALUE_TO_PTR(s.byteCode)
        ctx.memory.putU8(ptr + 8 + s.byteCodeLen, op.id)
        ctx.memory.putU32(ptr + 8 + s.byteCodeLen + 1, (target - s.byteCodeLen - 5).toLong())
        s.byteCodeLen += 5
        s.lastOpcodePos = -1
    }
    
    fun patchJump(pos: Int) {
        val ptr = JS_VALUE_TO_PTR(s.byteCode)
        val offset = s.byteCodeLen - pos - 5
        ctx.memory.putU32(ptr + 8 + pos + 1, offset.toLong())
    }
    
    /* 跳转占位定址到指定目标位置（用于 continue/break 等目标在占位之后才确定的场景） */
    private fun patchJumpTo(pos: Int, target: Int) {
        val ptr = JS_VALUE_TO_PTR(s.byteCode)
        val offset = target - pos - 5
        ctx.memory.putU32(ptr + 8 + pos + 1, offset.toLong())
    }
    
    fun ensureByteCodeSize(n: Int) {
        val ptr = JS_VALUE_TO_PTR(s.byteCode)
        val size = ctx.memory.getI32(ptr + 4)
        if (s.byteCodeLen + n > size) {
            val newSize = maxOf(size * 2, s.byteCodeLen + n)
            s.byteCode = resizeByteArray(s.byteCode, newSize)
        }
    }
    
    fun allocByteArray(size: Int): JSValue {
        val ptr = ctx.malloc(8 + size, JS_MTAG_BYTE_ARRAY)
        if (ptr == 0) return JS_EXCEPTION
        ctx.memory.putI32(ptr + 4, size)
        return JS_VALUE_FROM_PTR(ptr)
    }
    
    fun resizeByteArray(val1: JSValue, newSize: Int): JSValue {
        val oldPtr = JS_VALUE_TO_PTR(val1)
        val oldSize = ctx.memory.getI32(oldPtr + 4)
        val newPtr = ctx.malloc(8 + newSize, JS_MTAG_BYTE_ARRAY)
        if (newPtr == 0) return JS_EXCEPTION
        ctx.memory.putI32(newPtr + 4, newSize)
        ctx.memory.copy(oldPtr + 8, newPtr + 8, minOf(oldSize, newSize))
        return JS_VALUE_FROM_PTR(newPtr)
    }
    
    fun allocFunctionBytecode(): JSValue {
        val ptr = ctx.mallocz(72, JS_MTAG_FUNCTION_BYTECODE)
        if (ptr == 0) return JS_EXCEPTION
        ctx.memory.putJSValue(ptr + 8, JS_NULL)
        ctx.memory.putJSValue(ptr + 16, JS_NULL)
        ctx.memory.putJSValue(ptr + 24, JS_NULL)
        ctx.memory.putJSValue(ptr + 32, JS_NULL)
        ctx.memory.putJSValue(ptr + 40, JS_NULL)
        ctx.memory.putJSValue(ptr + 56, JS_NULL)
        return JS_VALUE_FROM_PTR(ptr)
    }
    
    fun setFunctionName(func: JSValue, name: JSValue) {
        val ptr = JS_VALUE_TO_PTR(func)
        ctx.memory.putJSValue(ptr + 8, name)
    }
    
    fun setArgCount(func: JSValue, count: Int) {
        val ptr = JS_VALUE_TO_PTR(func)
        val header = ctx.memory.getU16(ptr)
        ctx.memory.putU16(ptr, (header and 0xFFF) or (count shl 4))
    }
    
    fun addConstPool(val1: JSValue): Int {
        val ptr = JS_VALUE_TO_PTR(s.curFunc)
        var cpool = ctx.memory.getJSValue(ptr + 24)
        
        if (JS_IsNull(cpool)) {
            cpool = allocValueArray(16)
            ctx.memory.putJSValue(ptr + 24, cpool)
        }
        
        val cpoolPtr = JS_VALUE_TO_PTR(cpool)
        
        for (i in 0 until s.cpoolLen) {
            val existing = ctx.memory.getJSValue(cpoolPtr + 8 + i * 8)
            if (jsValueEquals(ctx, existing, val1)) return i
        }
        
        val size = ctx.memory.getI32(cpoolPtr + 4)
        val idx = s.cpoolLen
        
        if (idx >= size) {
            cpool = resizeValueArray(cpool, size * 2)
            ctx.memory.putJSValue(ptr + 24, cpool)
        }
        
        val newCpoolPtr = JS_VALUE_TO_PTR(cpool)
        ctx.memory.putJSValue(newCpoolPtr + 8 + idx * 8, val1)
        s.cpoolLen++
        
        return idx
    }
    
    fun jsValueEquals(ctx: JSContext, a: JSValue, b: JSValue): Boolean {
        if (a == b) return true
        if (JS_IsString(ctx, a) && JS_IsString(ctx, b)) {
            val sa = jsGetString(ctx, a)
            val sb = jsGetString(ctx, b)
            return sa == sb
        }
        return false
    }
    
    fun allocValueArray(size: Int): JSValue {
        val ptr = ctx.malloc(8 + size * 8, JS_MTAG_VALUE_ARRAY)
        if (ptr == 0) return JS_EXCEPTION
        ctx.memory.putI32(ptr + 4, size)
        return JS_VALUE_FROM_PTR(ptr)
    }
    
    fun resizeValueArray(val1: JSValue, newSize: Int): JSValue {
        val oldPtr = JS_VALUE_TO_PTR(val1)
        val oldSize = ctx.memory.getI32(oldPtr + 4)
        val newPtr = ctx.malloc(8 + newSize * 8, JS_MTAG_VALUE_ARRAY)
        if (newPtr == 0) return JS_EXCEPTION
        ctx.memory.putI32(newPtr + 4, newSize)
        for (i in 0 until minOf(oldSize, newSize)) {
            ctx.memory.putJSValue(newPtr + 8 + i * 8, ctx.memory.getJSValue(oldPtr + 8 + i * 8))
        }
        return JS_VALUE_FROM_PTR(newPtr)
    }
    
    fun addLocalVar(name: JSValue): Int {
        val ptr = JS_VALUE_TO_PTR(s.curFunc)
        var vars = ctx.memory.getJSValue(ptr + 32)
        
        if (JS_IsNull(vars)) {
            vars = allocValueArray(16)
            ctx.memory.putJSValue(ptr + 32, vars)
        }
        
        val varsPtr = JS_VALUE_TO_PTR(vars)
        val size = ctx.memory.getI32(varsPtr + 4)
        val idx = s.localVarsLen
        
        if (idx >= size) {
            vars = resizeValueArray(vars, size * 2)
            ctx.memory.putJSValue(ptr + 32, vars)
        }
        
        val newVarsPtr = JS_VALUE_TO_PTR(vars)
        ctx.memory.putJSValue(newVarsPtr + 8 + idx * 8, name)
        s.localVarsLen++
        compileScopes.lastOrNull()?.localVarsLen = s.localVarsLen
        
        return idx
    }
    
    fun findLocalVar(name: JSValue): Int {
        val ptr = JS_VALUE_TO_PTR(s.curFunc)
        val vars = ctx.memory.getJSValue(ptr + 32)
        if (JS_IsNull(vars)) return -1
        
        val varsPtr = JS_VALUE_TO_PTR(vars)
        for (i in 0 until s.localVarsLen) {
            val v = ctx.memory.getJSValue(varsPtr + 8 + i * 8)
            if (jsStringEquals(ctx, v, name)) return i
        }
        return -1
    }
    
    /* 在指定函数的局部变量表中查找（闭包捕获的外层查找用） */
    private fun findLocalVarIn(func: JSValue, count: Int, name: JSValue): Int {
        val ptr = JS_VALUE_TO_PTR(func)
        val vars = ctx.memory.getJSValue(ptr + 32)
        if (JS_IsNull(vars)) return -1
        
        val varsPtr = JS_VALUE_TO_PTR(vars)
        for (i in 0 until count) {
            val v = ctx.memory.getJSValue(varsPtr + 8 + i * 8)
            if (jsStringEquals(ctx, v, name)) return i
        }
        return -1
    }
    
    /* 标识符变量读取的统一发射：局部 → get_loc；已捕获/可捕获的外层变量 → get_var_ref；
     * 未声明 → get_global（未找到属性时运行时抛 ReferenceError） */
    private fun emitVarLoad(name: JSValue): Boolean {
        val nameStr = jsGetString(ctx, name)
        
        val localIdx = findLocalVar(name)
        if (localIdx >= 0) {
            /* 本层变量若已被提升（内层捕获）则走 varref 槽 */
            val scope = compileScopes.lastOrNull()
            val slot = scope?.varrefSlots?.get(nameStr)
            if (slot != null) {
                emitOpWithArg(OPCodeEnum.OP_get_var_ref, slot)
            } else {
                emitOpWithArg(OPCodeEnum.OP_get_loc, localIdx)
            }
            if (System.getenv("MQJS_TRACE") != null) {
                println("DBG load '$nameStr' local=$localIdx slot=$slot scopes=${compileScopes.size}")
            }
            return true
        }
        
        /* 沿函数编译栈向外查找可捕获的外层局部变量 */
        val captured = tryCaptureOuterVar(nameStr)
        if (captured >= 0) {
            emitOpWithArg(OPCodeEnum.OP_get_var_ref, captured)
            if (System.getenv("MQJS_TRACE") != null) {
                println("DBG load '$nameStr' captured=$captured scopes=${compileScopes.size}")
            }
            return true
        }
        
        /* 未声明的全局标识符 */
        val cidx = addConstPool(name)
        emitOpWithArg(OPCodeEnum.OP_get_global, cidx)
        if (System.getenv("MQJS_TRACE") != null) {
            println("DBG load '$nameStr' global=$cidx scopes=${compileScopes.size}")
        }
        return true
    }
    
    /* 标识符变量写入的统一发射：局部 → put_loc；捕获槽 → put_var_ref；全局 → put_global */
    private fun emitVarStore(name: JSValue, valueLoaded: Boolean): Boolean {
        val nameStr = jsGetString(ctx, name)
        
        val localIdx = findLocalVar(name)
        if (localIdx >= 0) {
            if (!valueLoaded) emitOp(OPCodeEnum.OP_dup)
            emitOpWithArg(OPCodeEnum.OP_put_loc, localIdx)
            return true
        }
        
        val captured = tryCaptureOuterVar(nameStr)
        if (captured >= 0) {
            if (!valueLoaded) emitOp(OPCodeEnum.OP_dup)
            emitOpWithArg(OPCodeEnum.OP_put_var_ref, captured)
            return true
        }
        
        if (!valueLoaded) emitOp(OPCodeEnum.OP_dup)
        val cidx = addConstPool(name)
        emitOpWithArg(OPCodeEnum.OP_put_global, cidx)
        return true
    }
    
    /* 内层引用外层局部变量：分配捕获槽并回改外层已发射的 loc 指令（对照 C
     * add_ext_var + varref 提升）。返回池槽索引；未找到可捕获的外层变量返回 -1 */
    private fun tryCaptureOuterVar(nameStr: String): Int {
        /* 从栈顶（当前内层）向栈底（顶层）查找声明该变量的外层层 */
        for (i in compileScopes.indices.reversed().drop(1)) {
            val scope = compileScopes[i]
            
            val existing = scope.varrefSlots[nameStr]
            if (existing != null) return existing
            
            val name = JS_NewString(ctx, nameStr)
            val localIdx = findLocalVarIn(scope.curFunc, scope.localVarsLen, name)
            if (localIdx >= 0) {
                val slot = ctx.varrefPool.size.also { ctx.varrefPool.add(JS_UNDEFINED) }
                scope.varrefSlots[nameStr] = slot
                rewriteLocToVarRef(scope.byteCode, scope.byteCodeLen, localIdx, slot)
                return slot
            }
        }
        return -1
    }
    
    /* 扫描函数字节码，将读写指定局部变量的 get_loc/put_loc 改写为
     * get_var_ref/put_var_ref（操作数换为 varref 池槽；指令同为 3 字节）。
     * 字节码与长度来自编译上下文快照——编译中的函数对象 +16 槽尚未挂载字节码 */
    private fun rewriteLocToVarRef(byteCode: JSValue, codeLen: Int, localIdx: Int, slot: Int) {
        if (byteCode == JS_NULL || codeLen <= 0) return
        val bcPtr = JS_VALUE_TO_PTR(byteCode)
        val len = codeLen
        
        var rewritten = 0
        var i = 0
        while (i < len) {
            val op = ctx.memory.getU8(bcPtr + 8 + i).toInt()
            val e = OPCodeEnum.entries.find { it.id == op }
            if (e == null) {
                i++
                continue
            }
            if (op == OPCodeEnum.OP_get_loc.id &&
                ctx.memory.getU16(bcPtr + 8 + i + 1) == localIdx) {
                ctx.memory.putU8(bcPtr + 8 + i, OPCodeEnum.OP_get_var_ref.id)
                ctx.memory.putU16(bcPtr + 8 + i + 1, slot)
                rewritten++
            } else if (op == OPCodeEnum.OP_put_loc.id &&
                ctx.memory.getU16(bcPtr + 8 + i + 1) == localIdx) {
                ctx.memory.putU8(bcPtr + 8 + i, OPCodeEnum.OP_put_var_ref.id)
                ctx.memory.putU16(bcPtr + 8 + i + 1, slot)
                rewritten++
            }
            i += e.size
        }
        if (System.getenv("MQJS_TRACE") != null) {
            println("DBG rewrite len=$len localIdx=$localIdx slot=$slot rewritten=$rewritten")
        }
    }
    
    fun jsStringEquals(ctx: JSContext, a: JSValue, b: JSValue): Boolean {
        if (a == b) return true
        if (!JS_IsPtr(a) || !JS_IsPtr(b)) return false
        val ptrA = JS_VALUE_TO_PTR(a)
        val ptrB = JS_VALUE_TO_PTR(b)
        if (ctx.memory.getMTag(ptrA) != JSMTags.JS_MTAG_STRING || ctx.memory.getMTag(ptrB) != JSMTags.JS_MTAG_STRING) return false
        
        val lenA = ctx.memory.getI32(ptrA + 4)
        val lenB = ctx.memory.getI32(ptrB + 4)
        if (lenA != lenB) return false
        
        for (i in 0 until lenA) {
            if (ctx.memory.getU8(ptrA + 8 + i) != ctx.memory.getU8(ptrB + 8 + i)) return false
        }
        return true
    }
}

