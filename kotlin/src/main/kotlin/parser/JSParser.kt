package com.mquickjs.parser

import com.mquickjs.*
import com.mquickjs.JSMTags.JS_MTAG_BYTE_ARRAY
import com.mquickjs.JSMTags.JS_MTAG_FUNCTION_BYTECODE
import com.mquickjs.JSMTags.JS_MTAG_OBJECT
import com.mquickjs.JSMTags.JS_MTAG_STRING
import com.mquickjs.JSMTags.JS_MTAG_VALUE_ARRAY
import com.mquickjs.memory.getMTag

class JSParser(private val s: JSParseState) {
    
    private val ctx = s.ctx
    
    fun parse(): JSValue {
        if (!initFunction()) return JS_EXCEPTION
        
        if (s.hasRetval) {
            s.evalRetIdx = addLocalVar(JS_NewString(ctx, "_ret_"))
        }
        
        while (s.token.val1 != JSTokenVal.TOK_EOF.value && !s.hasError) {
            if (!parseStatement()) break
        }
        
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
                emitOp(OPCodeEnum.OP_undefined)
                s.nextToken()
                if (s.token.val1 == JSTokenVal.TOK_IDENT.value) {
                    s.nextToken()
                }
                expectSemi()
                return true
            }
            JSTokenVal.TOK_CONTINUE.value -> {
                s.nextToken()
                if (s.token.val1 == JSTokenVal.TOK_IDENT.value) {
                    s.nextToken()
                }
                expectSemi()
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
            
            if (s.token.val1 == '='.code) {
                s.nextToken()
                if (!parseAssignExpr()) return false
            } else {
                emitOp(OPCodeEnum.OP_undefined)
            }
            
            val idx = addLocalVar(name)
            emitOpWithArg(OPCodeEnum.OP_put_loc, idx)
            
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
        
        s.curFunc = allocFunctionBytecode()
        if (JS_IsException(s.curFunc)) {
            s.curFunc = savedFunc
            return false
        }
        
        setFunctionName(s.curFunc, funcName)
        
        s.byteCode = allocByteArray(256)
        s.byteCodeLen = 0
        
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
        
        val cpoolIdx = addConstPool(newFunc)
        emitOpWithArg(OPCodeEnum.OP_fclosure, cpoolIdx)
        
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
        
        val condPos = s.byteCodeLen
        if (!parseExpr()) return false
        expect(')'.code)
        
        val endPos = emitJump(OPCodeEnum.OP_if_false)
        
        if (!parseStatement()) return false
        
        emitJumpTo(OPCodeEnum.OP_goto, condPos)
        patchJump(endPos)
        
        return true
    }
    
    fun parseDoWhile(): Boolean {
        s.nextToken()
        
        val startPos = s.byteCodeLen
        if (!parseStatement()) return false
        
        expect(JSTokenVal.TOK_WHILE.value)
        expect('('.code)
        if (!parseExpr()) return false
        expect(')'.code)
        expect(';'.code)
        
        emitJumpTo(OPCodeEnum.OP_if_true, startPos)
        
        return true
    }
    
    fun parseFor(): Boolean {
        s.nextToken()
        expect('('.code)
        
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
        
        val condPos = s.byteCodeLen
        var endPos = -1
        
        if (s.token.val1 != ';'.code) {
            if (!parseExpr()) return false
            endPos = emitJump(OPCodeEnum.OP_if_false)
            expect(';'.code)
        } else {
            s.nextToken()
        }
        
        val updatePos = s.byteCodeLen
        if (s.token.val1 != ')'.code) {
            if (!parseExpr()) return false
            emitOp(OPCodeEnum.OP_drop)
        }
        expect(')'.code)
        
        val bodyPos = s.byteCodeLen
        if (!parseStatement()) return false
        
        emitJumpTo(OPCodeEnum.OP_goto, updatePos)
        
        if (endPos >= 0) {
            patchJump(endPos)
        }
        
        return true
    }
    
    fun parseTry(): Boolean {
        s.nextToken()
        
        emitOp(OPCodeEnum.OP_undefined)
        
        val catchPos = emitJump(OPCodeEnum.OP_catch)
        
        if (!parseBlock()) return false
        
        val endPos = emitJump(OPCodeEnum.OP_goto)
        
        patchJump(catchPos)
        
        if (s.token.val1 == JSTokenVal.TOK_CATCH.value) {
            s.nextToken()
            expect('('.code)
            if (s.token.val1 != JSTokenVal.TOK_IDENT.value) {
                s.error("expected identifier")
                return false
            }
            s.nextToken()
            expect(')'.code)
            if (!parseBlock()) return false
        }
        
        if (s.token.val1 == JSTokenVal.TOK_FINALLY.value) {
            s.nextToken()
            if (!parseBlock()) return false
        }
        
        patchJump(endPos)
        
        return true
    }
    
    fun parseSwitch(): Boolean {
        s.nextToken()
        expect('('.code)
        if (!parseExpr()) return false
        expect(')'.code)
        expect('{'.code)
        
        val defaultPos = -1
        val endPos = emitJump(OPCodeEnum.OP_goto)
        
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
        return true
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
                
                emitOp(OPCodeEnum.OP_dup)
                when (lvalueOp) {
                    OPCodeEnum.OP_get_loc.id -> emitOpWithArg(OPCodeEnum.OP_put_loc, lvalueArg)
                    OPCodeEnum.OP_get_field.id -> emitOpWithArg(OPCodeEnum.OP_put_field, lvalueArg)
                    OPCodeEnum.OP_get_array_el.id -> emitOp(OPCodeEnum.OP_put_array_el)
                }
                return true
            }
            JSTokenVal.TOK_PLUS_ASSIGN.value -> {
                val lvalueOp = getLastOp()
                val lvalueArg = getLastOpArg()
                removeLastOp()
                
                when (lvalueOp) {
                    OPCodeEnum.OP_get_loc.id -> emitOpWithArg(OPCodeEnum.OP_get_loc, lvalueArg)
                    OPCodeEnum.OP_get_field.id -> emitOpWithArg(OPCodeEnum.OP_get_field, lvalueArg)
                }
                
                s.nextToken()
                if (!parseAssignExpr()) return false
                emitOp(OPCodeEnum.OP_add)
                
                emitOp(OPCodeEnum.OP_dup)
                when (lvalueOp) {
                    OPCodeEnum.OP_get_loc.id -> emitOpWithArg(OPCodeEnum.OP_put_loc, lvalueArg)
                    OPCodeEnum.OP_get_field.id -> emitOpWithArg(OPCodeEnum.OP_put_field, lvalueArg)
                    OPCodeEnum.OP_get_array_el.id -> emitOp(OPCodeEnum.OP_put_array_el)
                }
                return true
            }
            JSTokenVal.TOK_MINUS_ASSIGN.value -> {
                val lvalueOp = getLastOp()
                val lvalueArg = getLastOpArg()
                removeLastOp()
                
                when (lvalueOp) {
                    OPCodeEnum.OP_get_loc.id -> emitOpWithArg(OPCodeEnum.OP_get_loc, lvalueArg)
                    OPCodeEnum.OP_get_field.id -> emitOpWithArg(OPCodeEnum.OP_get_field, lvalueArg)
                }
                
                s.nextToken()
                if (!parseAssignExpr()) return false
                emitOp(OPCodeEnum.OP_sub)
                
                emitOp(OPCodeEnum.OP_dup)
                when (lvalueOp) {
                    OPCodeEnum.OP_get_loc.id -> emitOpWithArg(OPCodeEnum.OP_put_loc, lvalueArg)
                    OPCodeEnum.OP_get_field.id -> emitOpWithArg(OPCodeEnum.OP_put_field, lvalueArg)
                    OPCodeEnum.OP_get_array_el.id -> emitOp(OPCodeEnum.OP_put_array_el)
                }
                return true
            }
            JSTokenVal.TOK_MUL_ASSIGN.value -> {
                val lvalueOp = getLastOp()
                val lvalueArg = getLastOpArg()
                removeLastOp()
                
                when (lvalueOp) {
                    OPCodeEnum.OP_get_loc.id -> emitOpWithArg(OPCodeEnum.OP_get_loc, lvalueArg)
                    OPCodeEnum.OP_get_field.id -> emitOpWithArg(OPCodeEnum.OP_get_field, lvalueArg)
                }
                
                s.nextToken()
                if (!parseAssignExpr()) return false
                emitOp(OPCodeEnum.OP_mul)
                
                emitOp(OPCodeEnum.OP_dup)
                when (lvalueOp) {
                    OPCodeEnum.OP_get_loc.id -> emitOpWithArg(OPCodeEnum.OP_put_loc, lvalueArg)
                    OPCodeEnum.OP_get_field.id -> emitOpWithArg(OPCodeEnum.OP_put_field, lvalueArg)
                    OPCodeEnum.OP_get_array_el.id -> emitOp(OPCodeEnum.OP_put_array_el)
                }
                return true
            }
            JSTokenVal.TOK_DIV_ASSIGN.value -> {
                val lvalueOp = getLastOp()
                val lvalueArg = getLastOpArg()
                removeLastOp()
                
                when (lvalueOp) {
                    OPCodeEnum.OP_get_loc.id -> emitOpWithArg(OPCodeEnum.OP_get_loc, lvalueArg)
                    OPCodeEnum.OP_get_field.id -> emitOpWithArg(OPCodeEnum.OP_get_field, lvalueArg)
                }
                
                s.nextToken()
                if (!parseAssignExpr()) return false
                emitOp(OPCodeEnum.OP_div)
                
                emitOp(OPCodeEnum.OP_dup)
                when (lvalueOp) {
                    OPCodeEnum.OP_get_loc.id -> emitOpWithArg(OPCodeEnum.OP_put_loc, lvalueArg)
                    OPCodeEnum.OP_get_field.id -> emitOpWithArg(OPCodeEnum.OP_put_field, lvalueArg)
                    OPCodeEnum.OP_get_array_el.id -> emitOp(OPCodeEnum.OP_put_array_el)
                }
                return true
            }
            JSTokenVal.TOK_MOD_ASSIGN.value -> {
                val lvalueOp = getLastOp()
                val lvalueArg = getLastOpArg()
                removeLastOp()
                
                when (lvalueOp) {
                    OPCodeEnum.OP_get_loc.id -> emitOpWithArg(OPCodeEnum.OP_get_loc, lvalueArg)
                    OPCodeEnum.OP_get_field.id -> emitOpWithArg(OPCodeEnum.OP_get_field, lvalueArg)
                }
                
                s.nextToken()
                if (!parseAssignExpr()) return false
                emitOp(OPCodeEnum.OP_mod)
                
                emitOp(OPCodeEnum.OP_dup)
                when (lvalueOp) {
                    OPCodeEnum.OP_get_loc.id -> emitOpWithArg(OPCodeEnum.OP_put_loc, lvalueArg)
                    OPCodeEnum.OP_get_field.id -> emitOpWithArg(OPCodeEnum.OP_put_field, lvalueArg)
                    OPCodeEnum.OP_get_array_el.id -> emitOp(OPCodeEnum.OP_put_array_el)
                }
                return true
            }
            JSTokenVal.TOK_SHL_ASSIGN.value -> {
                val lvalueOp = getLastOp()
                val lvalueArg = getLastOpArg()
                removeLastOp()
                
                when (lvalueOp) {
                    OPCodeEnum.OP_get_loc.id -> emitOpWithArg(OPCodeEnum.OP_get_loc, lvalueArg)
                    OPCodeEnum.OP_get_field.id -> emitOpWithArg(OPCodeEnum.OP_get_field, lvalueArg)
                }
                
                s.nextToken()
                if (!parseAssignExpr()) return false
                emitOp(OPCodeEnum.OP_shl)
                
                emitOp(OPCodeEnum.OP_dup)
                when (lvalueOp) {
                    OPCodeEnum.OP_get_loc.id -> emitOpWithArg(OPCodeEnum.OP_put_loc, lvalueArg)
                    OPCodeEnum.OP_get_field.id -> emitOpWithArg(OPCodeEnum.OP_put_field, lvalueArg)
                    OPCodeEnum.OP_get_array_el.id -> emitOp(OPCodeEnum.OP_put_array_el)
                }
                return true
            }
            JSTokenVal.TOK_SAR_ASSIGN.value -> {
                val lvalueOp = getLastOp()
                val lvalueArg = getLastOpArg()
                removeLastOp()
                
                when (lvalueOp) {
                    OPCodeEnum.OP_get_loc.id -> emitOpWithArg(OPCodeEnum.OP_get_loc, lvalueArg)
                    OPCodeEnum.OP_get_field.id -> emitOpWithArg(OPCodeEnum.OP_get_field, lvalueArg)
                }
                
                s.nextToken()
                if (!parseAssignExpr()) return false
                emitOp(OPCodeEnum.OP_sar)
                
                emitOp(OPCodeEnum.OP_dup)
                when (lvalueOp) {
                    OPCodeEnum.OP_get_loc.id -> emitOpWithArg(OPCodeEnum.OP_put_loc, lvalueArg)
                    OPCodeEnum.OP_get_field.id -> emitOpWithArg(OPCodeEnum.OP_put_field, lvalueArg)
                    OPCodeEnum.OP_get_array_el.id -> emitOp(OPCodeEnum.OP_put_array_el)
                }
                return true
            }
            JSTokenVal.TOK_SHR_ASSIGN.value -> {
                val lvalueOp = getLastOp()
                val lvalueArg = getLastOpArg()
                removeLastOp()
                
                when (lvalueOp) {
                    OPCodeEnum.OP_get_loc.id -> emitOpWithArg(OPCodeEnum.OP_get_loc, lvalueArg)
                    OPCodeEnum.OP_get_field.id -> emitOpWithArg(OPCodeEnum.OP_get_field, lvalueArg)
                }
                
                s.nextToken()
                if (!parseAssignExpr()) return false
                emitOp(OPCodeEnum.OP_shr)
                
                emitOp(OPCodeEnum.OP_dup)
                when (lvalueOp) {
                    OPCodeEnum.OP_get_loc.id -> emitOpWithArg(OPCodeEnum.OP_put_loc, lvalueArg)
                    OPCodeEnum.OP_get_field.id -> emitOpWithArg(OPCodeEnum.OP_put_field, lvalueArg)
                    OPCodeEnum.OP_get_array_el.id -> emitOp(OPCodeEnum.OP_put_array_el)
                }
                return true
            }
            JSTokenVal.TOK_AND_ASSIGN.value -> {
                val lvalueOp = getLastOp()
                val lvalueArg = getLastOpArg()
                removeLastOp()
                
                when (lvalueOp) {
                    OPCodeEnum.OP_get_loc.id -> emitOpWithArg(OPCodeEnum.OP_get_loc, lvalueArg)
                    OPCodeEnum.OP_get_field.id -> emitOpWithArg(OPCodeEnum.OP_get_field, lvalueArg)
                }
                
                s.nextToken()
                if (!parseAssignExpr()) return false
                emitOp(OPCodeEnum.OP_and)
                
                emitOp(OPCodeEnum.OP_dup)
                when (lvalueOp) {
                    OPCodeEnum.OP_get_loc.id -> emitOpWithArg(OPCodeEnum.OP_put_loc, lvalueArg)
                    OPCodeEnum.OP_get_field.id -> emitOpWithArg(OPCodeEnum.OP_put_field, lvalueArg)
                    OPCodeEnum.OP_get_array_el.id -> emitOp(OPCodeEnum.OP_put_array_el)
                }
                return true
            }
            JSTokenVal.TOK_XOR_ASSIGN.value -> {
                val lvalueOp = getLastOp()
                val lvalueArg = getLastOpArg()
                removeLastOp()
                
                when (lvalueOp) {
                    OPCodeEnum.OP_get_loc.id -> emitOpWithArg(OPCodeEnum.OP_get_loc, lvalueArg)
                    OPCodeEnum.OP_get_field.id -> emitOpWithArg(OPCodeEnum.OP_get_field, lvalueArg)
                }
                
                s.nextToken()
                if (!parseAssignExpr()) return false
                emitOp(OPCodeEnum.OP_xor)
                
                emitOp(OPCodeEnum.OP_dup)
                when (lvalueOp) {
                    OPCodeEnum.OP_get_loc.id -> emitOpWithArg(OPCodeEnum.OP_put_loc, lvalueArg)
                    OPCodeEnum.OP_get_field.id -> emitOpWithArg(OPCodeEnum.OP_put_field, lvalueArg)
                    OPCodeEnum.OP_get_array_el.id -> emitOp(OPCodeEnum.OP_put_array_el)
                }
                return true
            }
            JSTokenVal.TOK_OR_ASSIGN.value -> {
                val lvalueOp = getLastOp()
                val lvalueArg = getLastOpArg()
                removeLastOp()
                
                when (lvalueOp) {
                    OPCodeEnum.OP_get_loc.id -> emitOpWithArg(OPCodeEnum.OP_get_loc, lvalueArg)
                    OPCodeEnum.OP_get_field.id -> emitOpWithArg(OPCodeEnum.OP_get_field, lvalueArg)
                }
                
                s.nextToken()
                if (!parseAssignExpr()) return false
                emitOp(OPCodeEnum.OP_or)
                
                emitOp(OPCodeEnum.OP_dup)
                when (lvalueOp) {
                    OPCodeEnum.OP_get_loc.id -> emitOpWithArg(OPCodeEnum.OP_put_loc, lvalueArg)
                    OPCodeEnum.OP_get_field.id -> emitOpWithArg(OPCodeEnum.OP_put_field, lvalueArg)
                    OPCodeEnum.OP_get_array_el.id -> emitOp(OPCodeEnum.OP_put_array_el)
                }
                return true
            }
        }
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
                emitOp(OPCodeEnum.OP_inc)
                return true
            }
            JSTokenVal.TOK_DEC.value -> {
                s.nextToken()
                if (!parseUnaryExpr()) return false
                emitOp(OPCodeEnum.OP_dec)
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
                    emitOp(OPCodeEnum.OP_post_inc)
                }
                JSTokenVal.TOK_DEC.value -> {
                    s.nextToken()
                    emitOp(OPCodeEnum.OP_post_dec)
                }
                '('.code -> {
                    s.nextToken()
                    val argc = parseArgs()
                    expect(')'.code)
                    emitOpWithArg(OPCodeEnum.OP_call, argc)
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
                    val idx = addConstPool(name)
                    emitOpWithArg(OPCodeEnum.OP_get_field, idx)
                }
                else -> break
            }
        }
        
        return true
    }
    
    fun parseMemberExpr(): Boolean {
        if (!parsePrimaryExpr()) return false
        
        while (true) {
            when (s.token.val1) {
                '['.code -> {
                    s.nextToken()
                    if (!parseExpr()) return false
                    expect(']'.code)
                    emitOp(OPCodeEnum.OP_get_array_el2)
                }
                '.'.code -> {
                    s.nextToken()
                    if (s.token.val1 != JSTokenVal.TOK_IDENT.value) {
                        s.error("expected identifier after '.'")
                        return false
                    }
                    val name = s.token.value
                    s.nextToken()
                    val idx = addConstPool(name)
                    emitOpWithArg(OPCodeEnum.OP_get_field2, idx)
                }
                else -> break
            }
        }
        
        return true
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
                val idx = findLocalVar(name)
                if (idx >= 0) {
                    emitOpWithArg(OPCodeEnum.OP_get_loc, idx)
                } else {
                    val cidx = addConstPool(name)
                    emitOpWithArg(OPCodeEnum.OP_get_field, cidx)
                }
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
        
        s.curFunc = allocFunctionBytecode()
        if (JS_IsException(s.curFunc)) {
            s.curFunc = savedFunc
            return false
        }
        
        s.byteCode = allocByteArray(256)
        s.byteCodeLen = 0
        
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
    
    fun emitOp(op: OPCodeEnum) {
        ensureByteCodeSize(1)
        val ptr = JS_VALUE_TO_PTR(s.byteCode)
        s.lastOpcodePos = s.byteCodeLen
        ctx.memory.putU8(ptr + 8 + s.byteCodeLen, op.id)
        s.byteCodeLen++
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
