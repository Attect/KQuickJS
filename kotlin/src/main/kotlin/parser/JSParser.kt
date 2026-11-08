package app.muka.project.kquickjs.parser

import app.muka.project.kquickjs.*
import app.muka.project.kquickjs.JSMTags.JS_MTAG_BYTE_ARRAY
import app.muka.project.kquickjs.JSMTags.JS_MTAG_FUNCTION_BYTECODE
import app.muka.project.kquickjs.JSMTags.JS_MTAG_OBJECT
import app.muka.project.kquickjs.JSMTags.JS_MTAG_STRING
import app.muka.project.kquickjs.JSMTags.JS_MTAG_VALUE_ARRAY
import app.muka.project.kquickjs.memory.getMTag
import app.muka.project.kquickjs.runtime.JSRuntime

class JSParser(private val s: JSParseState) {
    
    private val ctx = s.ctx
    
    /* 函数编译上下文（嵌套栈）：记录已发射的 loc 指令（供闭包捕获时回改）与
     * 已提升的捕获槽映射。顶层上下文在 parse() 入口压入。
     * curFunc/byteCode 不缓存在字段中，而是固定在 ctx.gcKeepAlive 的槽位里：
     * 解析期间的分配可能触发 GC 压缩搬移，槽位内容会随搬移更新，字段缓存
     * 则会变成悬垂地址（长脚本解析崩溃的根因之一） */
    private class LocEmit(val name: String, val byteCode: JSValue, val pos: Int, val isPut: Boolean)
    
    private class FuncCompileScope(val keepBase: Int) {
        var localVarsLen = 0                                   /* 本层局部变量数（参数+声明） */
        var byteCodeLen = 0                                    /* 本层已发射长度（emitOp 同步，供捕获回改扫描） */
        val varrefSlots = HashMap<String, Int>()               /* 被内层捕获的局部 var → varref 池槽 */
        val locEmitLog = mutableListOf<LocEmit>()              /* 已发射的 get_loc/put_loc 记录 */
        /* 本函数从外层捕获的变量：(名称, varref 池槽)。闭包创建时据此
         * 把当前帧的实参/局部值写入捕获槽 */
        val captures = mutableListOf<Pair<String, Int>>()
    }
    
    private val compileScopes = ArrayDeque<FuncCompileScope>()
    
    /* for-in 左侧表达式解析期间禁止把 in 当运算符（对照 C PF_NO_IN） */
    private var noInFor = false
    
    /* typeof 的操作数：未声明标识符不得抛 ReferenceError */
    private var inTypeof = false
    
    /* 标签语句的 break 目标栈（同名取最内层） */
    private class LabelEntry(val name: String, val finallyDepth: Int) {
        val breaks = mutableListOf<Int>()
    }
    
    private val labelEntries = ArrayDeque<LabelEntry>()
    
    /* 与 break/continue 目标栈平行的 finally 深度栈：记录目标所在的 finally 层级，
     * 使 break/continue/return 能先执行其内部的 finally 块 */
    private val breakDepths = ArrayDeque<Int>()
    private val continueDepths = ArrayDeque<Int>()
    
    private class FinallyEntry {
        val gosubs = mutableListOf<Int>()
    }
    
    private val finallyStack = ArrayDeque<FinallyEntry>()
    
    private fun pushBreakTarget(list: MutableList<Int>) {
        s.breakLabelStack.addLast(list)
        breakDepths.addLast(finallyStack.size)
    }
    
    private fun popBreakTarget() {
        s.breakLabelStack.removeLast()
        breakDepths.removeLast()
    }
    
    private fun pushContinueTarget(list: MutableList<Int>) {
        s.continueLabelStack.addLast(list)
        continueDepths.addLast(finallyStack.size)
    }
    
    private fun popContinueTarget() {
        s.continueLabelStack.removeLast()
        continueDepths.removeLast()
    }
    
    /* 为跳出到 targetDepth 之外的每一层 finally 生成 gosub（栈上压占位值保持平衡） */
    private fun emitFinallyGosubs(targetDepth: Int) {
        for (i in finallyStack.size - 1 downTo targetDepth) {
            emitOp(OPCodeEnum.OP_undefined)
            finallyStack[i].gosubs.add(emitJump(OPCodeEnum.OP_gosub))
            emitOp(OPCodeEnum.OP_drop)
        }
    }
    
    /* return 需要执行所有打开着的 finally；返回值保持在栈顶 */
    private fun emitReturnWithFinally(hasVal: Boolean) {
        if (!hasVal) emitOp(OPCodeEnum.OP_undefined)
        for (i in finallyStack.size - 1 downTo 0) {
            emitOp(OPCodeEnum.OP_undefined)
            finallyStack[i].gosubs.add(emitJump(OPCodeEnum.OP_gosub))
            emitOp(OPCodeEnum.OP_drop)
        }
        emitOp(OPCodeEnum.OP_return)
    }
    
    /* 声明局部变量：已存在则复用槽位（var 的重复声明/参数同名/函数名提升） */
    private fun declareLocal(name: JSValue): Int {
        val idx = findLocalVar(name)
        return if (idx >= 0) idx else addLocalVar(name)
    }
    
    /* 词法级跳过表达式时判断 '/' 是否为正则起始（基于前一个 token） */
    private fun regexCanStart(prev: Int): Boolean {
        if (prev < 0) return true
        when (prev) {
            '('.code, '['.code, '{'.code, ','.code, ';'.code, ':'.code, '?'.code,
            '='.code, '+'.code, '-'.code, '*'.code, '/'.code, '%'.code, '!'.code,
            '~'.code, '&'.code, '|'.code, '^'.code, '<'.code, '>'.code,
            JSTokenVal.TOK_RETURN.value, JSTokenVal.TOK_THROW.value,
            JSTokenVal.TOK_CASE.value, JSTokenVal.TOK_DELETE.value,
            JSTokenVal.TOK_VOID.value, JSTokenVal.TOK_TYPEOF.value,
            JSTokenVal.TOK_NEW.value, JSTokenVal.TOK_IN.value,
            JSTokenVal.TOK_INSTANCEOF.value, JSTokenVal.TOK_DO.value,
            JSTokenVal.TOK_ELSE.value, JSTokenVal.TOK_VAR.value,
            JSTokenVal.TOK_LAND.value, JSTokenVal.TOK_LOR.value,
            JSTokenVal.TOK_EQ.value, JSTokenVal.TOK_NEQ.value ->
                return prev != '/'.code
        }
        return false
    }
    
    /* 跳过 var 初始化表达式，停在深度 0 的 ',' ';' 或声明作用域结束的 '}' */
    private fun skipVarInitializer() {
        var depth = 0
        var prev = '='.code
        while (s.token.val1 != JSTokenVal.TOK_EOF.value && !s.hasError) {
            if (s.token.val1 == '/'.code && regexCanStart(prev)) {
                if (!s.parseRegexpLiteral()) return
                prev = JSTokenVal.TOK_REGEXP.value
                s.nextToken()
                continue
            }
            when (s.token.val1) {
                '('.code, '['.code, '{'.code -> depth++
                ')'.code, ']'.code -> if (depth > 0) depth--
                '}'.code -> {
                    if (depth == 0) return
                    depth--
                }
                ','.code, ';'.code -> if (depth == 0) return
            }
            prev = s.token.val1
            s.nextToken()
        }
    }
    
    /* var/function 声明提升（对照 C 的 define_var/js_parse_local_functions）：
     * 扫描整个函数体（跳过嵌套函数），把所有 var 与函数声明名加入局部变量表。
     * 这样内层函数捕获外层"稍后声明"的变量时也能正确解析为局部/捕获变量。 */
    private fun hoistDeclarations(untilEof: Boolean, topLevel: Boolean = false) {
        val savedBuf = s.bufPos
        val savedLine = s.lineNum
        val savedCol = s.colNum
        val savedLf = s.gotLf
        val savedToken = s.token.copy()
        var braceDepth = 0
        var prev = -1
        while (s.token.val1 != JSTokenVal.TOK_EOF.value && !s.hasError) {
            /* 提升扫描是词法级的：正则字面量要整体跳过，避免其中的括号影响深度 */
            if (s.token.val1 == '/'.code && regexCanStart(prev)) {
                if (!s.parseRegexpLiteral()) break
                prev = JSTokenVal.TOK_REGEXP.value
                s.nextToken()
                continue
            }
            val cur = s.token.val1
            when (cur) {
                '}'.code -> {
                    if (braceDepth == 0 && !untilEof) break
                    if (braceDepth > 0) braceDepth--
                }
                '{'.code -> braceDepth++
                JSTokenVal.TOK_VAR.value -> {
                    s.nextToken()
                    while (s.token.val1 == JSTokenVal.TOK_IDENT.value) {
                        /* 顶层程序按 eval 语义使用全局变量，不建立局部槽 */
                        if (!topLevel) declareLocal(s.token.value)
                        s.nextToken()
                        if (s.token.val1 == '='.code) {
                            s.nextToken()
                            skipVarInitializer()
                        }
                        if (s.token.val1 == ','.code) {
                            s.nextToken()
                            continue
                        }
                        break
                    }
                    prev = cur
                    continue
                }
                JSTokenVal.TOK_FUNCTION.value -> {
                    s.nextToken()
                    if (s.token.val1 == JSTokenVal.TOK_IDENT.value) {
                        if (!topLevel) declareLocal(s.token.value)
                        s.nextToken()
                    }
                    if (s.token.val1 == '('.code) skipBalancedParens()
                    if (s.token.val1 == '{'.code) skipBalancedBraces()
                    prev = cur
                    continue
                }
            }
            prev = cur
            s.nextToken()
        }
        seekSource(savedBuf, savedLine, savedCol, savedLf, savedToken)
    }
    
    /* switch 子句：记录 case 表达式与 body 的源位置快照，便于"先分发、后本体"两段编译 */
    private class SwitchClause(
        val isDefault: Boolean,
        val exprBuf: Int, val exprLine: Int, val exprCol: Int, val exprLf: Boolean, val exprToken: JSToken?,
        val bodyBuf: Int, val bodyLine: Int, val bodyCol: Int, val bodyLf: Boolean, val bodyToken: JSToken?
    )
    
    private fun switchScanNextClause() {
        var depth = 0
        while (s.token.val1 != JSTokenVal.TOK_EOF.value && !s.hasError) {
            when (s.token.val1) {
                '('.code, '['.code, '{'.code -> depth++
                ')'.code, ']'.code -> if (depth > 0) depth--
                '}'.code -> {
                    if (depth == 0) return
                    depth--
                }
                JSTokenVal.TOK_CASE.value, JSTokenVal.TOK_DEFAULT.value -> if (depth == 0) return
            }
            s.nextToken()
        }
    }
    
    /* 编译作用域入栈：当前函数对象与字节码放入 GC 保活列表（槽位固定），
     * 作用域存续期间一律通过 scopeCurFunc/scopeByteCode 读取最新地址 */
    private fun pushCompileScope() {
        val keepBase = ctx.gcKeepAlive.size
        ctx.gcKeepAlive.add(s.curFunc)
        ctx.gcKeepAlive.add(s.byteCode)
        compileScopes.addLast(FuncCompileScope(keepBase))
    }
    
    private fun popCompileScope() {
        val scope = compileScopes.removeLast()
        /* 作用域存续期间的临时保活均已配对弹出，尾部两项必是本作用域的槽 */
        ctx.gcKeepAlive.removeAt(scope.keepBase + 1)
        ctx.gcKeepAlive.removeAt(scope.keepBase)
    }
    
    private fun scopeCurFunc(scope: FuncCompileScope): JSValue = ctx.gcKeepAlive[scope.keepBase]
    
    /* 把本函数的捕获表序列化到函数字节码 +64：VALUE_ARRAY [name, slot, ...]，
     * 供 OP_fclosure 在创建闭包时把外层帧的当前值写入捕获槽 */
    private fun storeCaptures() {
        val scope = compileScopes.lastOrNull() ?: return
        if (scope.captures.isEmpty()) return
        val n = scope.captures.size
        val arr = allocValueArray(n * 2)
        if (!JS_IsNull(arr) && !JS_IsException(arr)) {
            ctx.gcKeepAlive.add(arr)
            try {
                for (i in 0 until n) {
                    val nameStr = scope.captures[i].first
                    val slot = scope.captures[i].second
                    val jsName = JS_NewString(ctx, nameStr)
                    val p = JS_VALUE_TO_PTR(ctx.gcKeepAlive[ctx.gcKeepAlive.size - 1])
                    ctx.memory.putJSValue(p + 8 + 2 * i * 8, jsName)
                    ctx.memory.putJSValue(p + 16 + 2 * i * 8, JS_NewShortInt(slot))
                }
                ctx.memory.putJSValue(JS_VALUE_TO_PTR(s.curFunc) + 64,
                                      ctx.gcKeepAlive[ctx.gcKeepAlive.size - 1])
            } finally {
                ctx.gcKeepAlive.removeAt(ctx.gcKeepAlive.size - 1)
            }
        }
        scope.captures.clear()
    }
    
    private fun scopeByteCode(scope: FuncCompileScope): JSValue = ctx.gcKeepAlive[scope.keepBase + 1]
    
    private fun updateScopeByteCode(scope: FuncCompileScope, byteCode: JSValue) {
        ctx.gcKeepAlive[scope.keepBase + 1] = byteCode
    }
    
    fun parse(): JSValue {
        /* 解析期间把解析状态挂到 ctx：GC 依据它保活并搬移 curFunc/byteCode/token
         * （对照 C 的 ctx->parse_state），否则长脚本解析中触发的 GC 会让解析器
         * 持有的 JSValue 变成悬垂地址 */
        val savedParseState = ctx.parseState
        ctx.parseState = s
        try {
            if (!initFunction()) return JS_EXCEPTION
            
            pushCompileScope()
            try {
                if (s.hasRetval) {
                    s.evalRetIdx = addLocalVar(JS_NewString(ctx, "_ret_"))
                }
                /* 顶层程序同样做 var/function 声明提升 */
                hoistDeclarations(untilEof = true, topLevel = true)
                
                while (s.token.val1 != JSTokenVal.TOK_EOF.value && !s.hasError) {
                    if (!parseStatement()) break
                }
            } finally {
                popCompileScope()
            }
            
            if (s.hasError) {
                return JS_EXCEPTION
            }
            
            return finalizeFunction()
        } finally {
            ctx.parseState = savedParseState
        }
    }
    
    fun initFunction(): Boolean {
        s.curFunc = allocFunctionBytecode()
        if (JS_IsException(s.curFunc)) return false
        
        s.byteCode = allocByteArray(256)
        if (JS_IsException(s.byteCode)) return false
        
        s.byteCodeLen = 0
        s.lastOpcodePos = -1
        /* 每个函数独立计数（对照 C reset_parse_state） */
        s.cpoolLen = 0
        s.posMarks = HashMap()
        s.localVarsLen = 0
        s.evalRetIdx = -1
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
        /* 收缩到精确长度后再挂到函数对象上：块大小由 +4 的长度字段推导，
         * 容量与长度不一致会让 GC 堆遍历错位（见 shrinkByteArray 注释） */
        peepholeOptimize()
        val exactByteCode = shrinkByteArray(s.byteCode, s.byteCodeLen)
        if (JS_IsException(exactByteCode)) {
            s.error("out of memory")
            return JS_EXCEPTION
        }
        s.byteCode = exactByteCode
        val funcPtr = JS_VALUE_TO_PTR(s.curFunc)
        ctx.memory.putJSValue(funcPtr + 16, s.byteCode)
        finishPc2line()
        return s.curFunc
    }
    
    fun parseStatement(): Boolean {
        /* 标签语句 `IDENT ':' statement`：先预读一个 token 判断，不是标签则回退 */
        if (s.token.val1 == JSTokenVal.TOK_IDENT.value) {
            val savedBuf = s.bufPos
            val savedLine = s.lineNum
            val savedCol = s.colNum
            val savedLf = s.gotLf
            val savedToken = s.token.copy()
            val labelName = jsGetString(ctx, s.token.value)
            s.nextToken()
            if (s.token.val1 == ':'.code) {
                s.nextToken()
                val entry = LabelEntry(labelName, finallyStack.size)
                labelEntries.addLast(entry)
                val ok: Boolean
                try {
                    ok = parseStatement()
                } finally {
                    labelEntries.removeLast()
                }
                val exitPos = s.byteCodeLen
                for (p in entry.breaks) patchJumpTo(p, exitPos)
                return ok
            }
            seekSource(savedBuf, savedLine, savedCol, savedLf, savedToken)
        }
        
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
                var labelName: String? = null
                /* 行终止符之后不能跟标签名（ASI：break\nfoo 是 break; foo;） */
                if (!s.gotLf && s.token.val1 == JSTokenVal.TOK_IDENT.value) {
                    labelName = jsGetString(ctx, s.token.value)
                    s.nextToken()
                }
                expectSemi()
                if (labelName != null) {
                    /* 带标签的 break：跳到同名标签语句末尾 */
                    val entry = labelEntries.lastOrNull { it.name == labelName }
                    if (entry == null) {
                        s.error("label '$labelName' not found")
                        return false
                    }
                    emitFinallyGosubs(entry.finallyDepth)
                    entry.breaks.add(emitJump(OPCodeEnum.OP_goto))
                    return true
                }
                /* 跳转目标占位：由最内层循环/switch 编译结束时 patch 到出口 */
                val labels = s.breakLabelStack.lastOrNull()
                if (labels == null) {
                    s.error("illegal break statement")
                    return false
                }
                emitFinallyGosubs(breakDepths.last())
                labels.add(emitJump(OPCodeEnum.OP_goto))
                return true
            }
            JSTokenVal.TOK_CONTINUE.value -> {
                s.nextToken()
                /* 行终止符后不解析标签（ASI） */
                if (!s.gotLf && s.token.val1 == JSTokenVal.TOK_IDENT.value) {
                    s.nextToken()
                }
                expectSemi()
                /* 跳转目标占位：由最内层循环编译结束时 patch 到条件/update 处 */
                val labels = s.continueLabelStack.lastOrNull()
                if (labels == null) {
                    s.error("illegal continue statement")
                    return false
                }
                emitFinallyGosubs(continueDepths.last())
                labels.add(emitJump(OPCodeEnum.OP_goto))
                return true
            }
            JSTokenVal.TOK_RETURN.value -> {
                s.nextToken()
                if (s.token.val1 != ';'.code && s.token.val1 != '}'.code && s.token.val1 != JSTokenVal.TOK_EOF.value && !s.gotLf) {
                    if (!parseExpr()) return false
                    emitReturnWithFinally(hasVal = true)
                } else {
                    emitReturnWithFinally(hasVal = false)
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
            /* 变量名要跨初始化表达式使用：初始化里的字符串常量等分配可能触发
             * GC 搬移，故放入保活槽，之后一律从槽位读取最新地址 */
            val nameKeepIdx = ctx.gcKeepAlive.size
            ctx.gcKeepAlive.add(s.token.value)
            try {
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
                    
                    val cidx = addConstPool(ctx.gcKeepAlive[nameKeepIdx])
                    emitOpWithArg(OPCodeEnum.OP_put_field, cidx)
                } else if (compileScopes.size == 1) {
                    /* 顶层程序（对照 C 的 eval 语义）：var 定义全局变量 */
                    if (s.token.val1 == '='.code) {
                        s.nextToken()
                        if (!parseAssignExpr()) return false
                    } else {
                        emitOp(OPCodeEnum.OP_undefined)
                    }
                    val cidx = addConstPool(ctx.gcKeepAlive[nameKeepIdx])
                    emitOpWithArg(OPCodeEnum.OP_define_global, cidx)
                } else {
                    // 正常模式：将变量存储到局部变量（顶层脚本亦为顶层函数帧的局部）
                    if (s.token.val1 == '='.code) {
                        s.nextToken()
                        if (!parseAssignExpr()) return false
                    } else {
                        emitOp(OPCodeEnum.OP_undefined)
                    }
                    
                    val nameVal = ctx.gcKeepAlive[nameKeepIdx]
                    val nameStr = jsGetString(ctx, nameVal)
                    val idx = declareLocal(nameVal)
                    /* 若该变量已被内层函数捕获，值必须写入 varref 槽 */
                    val slot = compileScopes.lastOrNull()?.varrefSlots?.get(nameStr)
                    if (slot != null) {
                        emitOpWithArg(OPCodeEnum.OP_put_var_ref, slot)
                    } else {
                        emitOpWithArg(OPCodeEnum.OP_put_loc, idx)
                    }
                }
            } finally {
                ctx.gcKeepAlive.removeAt(nameKeepIdx)
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
        /* 函数名要跨整个函数体编译使用：入 GC 保活列表，后续分配触发搬移时
         * 仍从槽位读取最新地址 */
        val nameKeepIdx = ctx.gcKeepAlive.size
        ctx.gcKeepAlive.add(s.token.value)
        try {
            s.nextToken()
            
            val savedByteCodeLen = s.byteCodeLen
            val savedLocalVarsLen = s.localVarsLen
            val savedEvalRetIdx = s.evalRetIdx
            val savedCpoolLen = s.cpoolLen
        val savedPosMarks = s.posMarks
            
            s.curFunc = allocFunctionBytecode()
            if (JS_IsException(s.curFunc)) {
                s.curFunc = scopeCurFunc(compileScopes.last())
                return false
            }
            
            setFunctionName(s.curFunc, ctx.gcKeepAlive[nameKeepIdx])
            
            s.byteCode = allocByteArray(256)
            if (JS_IsException(s.byteCode)) {
                s.curFunc = scopeCurFunc(compileScopes.last())
                s.byteCode = scopeByteCode(compileScopes.last())
                return false
            }
            s.byteCodeLen = 0
            s.localVarsLen = 0
            /* 常量池长度按函数独立计数（对照 C reset_parse_state 的 s->cpool_len = 0）：
             * 若沿用外层计数，内层函数的大量常量会把计数器撑大，回到外层后
             * addConstPool 会越界读取外层常量池数组，把堆内任意数据当作 JSValue
             * 解引用（长脚本 IndexOutOfBoundsException 的根因） */
            s.cpoolLen = 0
        s.posMarks = HashMap()
            s.evalRetIdx = -1
            
            /* 函数编译上下文入栈（闭包捕获的回改作用域）——byteCode 为本层对象，
               内层捕获本层变量时据此回改本层已发射的 get_loc/put_loc */
            pushCompileScope()
            try {
                expect('('.code)
                val argCount = parseParams()
                expect(')'.code)
                
                setArgCount(s.curFunc, argCount)
                
                expect('{'.code)
                hoistDeclarations(untilEof = false)
                while (s.token.val1 != '}'.code && s.token.val1 != JSTokenVal.TOK_EOF.value && !s.hasError) {
                    if (!parseStatement()) break
                }
                expect('}'.code)
                
                emitOp(OPCodeEnum.OP_return_undef)
                
                /* 收缩到精确长度：块大小按 +4 长度字段推导，容量大于长度会使
                 * GC 堆遍历错位并破坏堆（对照 C 的 js_shrink_byte_array） */
                peepholeOptimize()
        val exactByteCode = shrinkByteArray(s.byteCode, s.byteCodeLen)
                if (JS_IsException(exactByteCode)) {
                    s.error("out of memory")
                } else {
                    s.byteCode = exactByteCode
                }
                val newFuncPtr = JS_VALUE_TO_PTR(s.curFunc)
                ctx.memory.putJSValue(newFuncPtr + 16, s.byteCode)
            finishPc2line()
                storeCaptures()
            } finally {
                popCompileScope()
            }
            
            val newFunc = s.curFunc
            /* 恢复外层编译状态：curFunc/byteCode 从外层作用域的保活槽读取，
             * 避免使用编译内层期间可能已被 GC 搬移的旧地址 */
            s.curFunc = scopeCurFunc(compileScopes.last())
            s.byteCode = scopeByteCode(compileScopes.last())
            s.byteCodeLen = savedByteCodeLen
            s.localVarsLen = savedLocalVarsLen
            s.evalRetIdx = savedEvalRetIdx
            s.cpoolLen = savedCpoolLen
        s.posMarks = savedPosMarks
            
            val cpoolIdx = addConstPool(newFunc)
            emitOpWithArg(OPCodeEnum.OP_fclosure, cpoolIdx)
            
            /* 函数名绑定：顶层程序定义为全局变量，函数体内为局部（对照 C 的函数声明） */
            val nameVal = ctx.gcKeepAlive[nameKeepIdx]
            if (compileScopes.size == 1) {
                val cidx = addConstPool(nameVal)
                emitOpWithArg(OPCodeEnum.OP_define_global, cidx)
            } else {
                val nameIdx = declareLocal(nameVal)
                val nameSlot = compileScopes.lastOrNull()?.varrefSlots?.get(jsGetString(ctx, nameVal))
                if (nameSlot != null) {
                    emitOpWithArg(OPCodeEnum.OP_put_var_ref, nameSlot)
                } else {
                    emitOpWithArg(OPCodeEnum.OP_put_loc, nameIdx)
                }
            }
            
            return !s.hasError
        } finally {
            ctx.gcKeepAlive.removeAt(nameKeepIdx)
        }
    }
    
    fun parseParams(): Int {
        var count = 0
        if (s.token.val1 != ')'.code) {
            do {
                if (s.token.val1 != JSTokenVal.TOK_IDENT.value) {
                    s.error("expected parameter name")
                    return count
                }
                declareLocal(s.token.value)
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
        pushBreakTarget(breakLabels)
        pushContinueTarget(continueLabels)
        
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
            popBreakTarget()
            popContinueTarget()
        }
    }
    
    fun parseDoWhile(): Boolean {
        s.nextToken()
        
        val breakLabels = mutableListOf<Int>()
        val continueLabels = mutableListOf<Int>()
        pushBreakTarget(breakLabels)
        pushContinueTarget(continueLabels)
        
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
            popBreakTarget()
            popContinueTarget()
        }
    }
    
    fun parseFor(): Boolean {
        s.nextToken()
        expect('('.code)
        if (isForInAhead()) {
            return parseForIn()
        }
        return parseForClassic()
    }
    
    /* 判断 for 的括号内是否为 for-in/of：在括号深度 0 先遇到 in、而非 ';'。
     * 只做词法扫描并回退，不产生字节码 */
    private fun isForInAhead(): Boolean {
        val savedBuf = s.bufPos
        val savedLine = s.lineNum
        val savedCol = s.colNum
        val savedLf = s.gotLf
        val savedToken = s.token.copy()
        var depth = 0
        var result = false
        while (s.token.val1 != JSTokenVal.TOK_EOF.value && !s.hasError) {
            when (s.token.val1) {
                ';'.code -> if (depth == 0) break
                JSTokenVal.TOK_IN.value -> if (depth == 0) { result = true; break }
                JSTokenVal.TOK_IDENT.value -> {
                    /* for (x of arr) 的 of 是标识符 */
                    if (depth == 0 && jsGetString(ctx, s.token.value) == "of") {
                        result = true
                        break
                    }
                }
                '('.code, '['.code, '{'.code -> depth++
                ')'.code, ']'.code, '}'.code -> if (depth == 0) break else depth--
            }
            s.nextToken()
        }
        seekSource(savedBuf, savedLine, savedCol, savedLf, savedToken)
        return result
    }
    
    /* for (lhs in expr) body —— 字节码布局对照 C parse_for 的 for-in 分支：
     *   goto expr
     * next:  <lhs = 栈顶值>; goto body
     * expr:  <expr>; for_in_start; goto cont
     * body:  <stmt>
     * cont:  for_of_next; if_false next; drop
     * break: drop（释放迭代器） */
    private fun parseForIn(): Boolean {
        val breakLabels = mutableListOf<Int>()
        val continueLabels = mutableListOf<Int>()
        pushBreakTarget(breakLabels)
        pushContinueTarget(continueLabels)
        try {
            var varIdx = -1
            var lvalueOp = -1
            var lvalueArg = -1
            var tmpObj = -1
            var tmpIdx = -1
            var forOf = false
            var varGlobalCidx = -1
            
            if (s.token.val1 == JSTokenVal.TOK_VAR.value) {
                s.nextToken()
                if (s.token.val1 != JSTokenVal.TOK_IDENT.value) {
                    s.error("expected identifier")
                    return false
                }
                ctx.gcKeepAlive.add(s.token.value)
                try {
                    s.nextToken()
                    forOf = s.token.val1 == JSTokenVal.TOK_IDENT.value &&
                            jsGetString(ctx, s.token.value) == "of"
                    if (s.token.val1 != JSTokenVal.TOK_IN.value && !forOf) {
                        s.error("expected 'in' or 'of'")
                        return false
                    }
                    if (compileScopes.size == 1) {
                        varGlobalCidx = addConstPool(ctx.gcKeepAlive[ctx.gcKeepAlive.size - 1])
                    } else {
                        varIdx = declareLocal(ctx.gcKeepAlive[ctx.gcKeepAlive.size - 1])
                    }
                } finally {
                    ctx.gcKeepAlive.removeAt(ctx.gcKeepAlive.size - 1)
                }
            } else {
                noInFor = true
                try {
                    if (!parseConditionalExpr()) return false
                } finally {
                    noInFor = false
                }
                lvalueOp = getLastOp()
                lvalueArg = getLastOpArg()
                removeLastOp()
                forOf = s.token.val1 == JSTokenVal.TOK_IDENT.value &&
                        jsGetString(ctx, s.token.value) == "of"
                if (s.token.val1 != JSTokenVal.TOK_IN.value && !forOf) {
                    s.error("expected 'in' or 'of'")
                    return false
                }
                
                /* 属性/数组元素左值：循环前求出基对象并存入临时槽（只求值一次） */
                if (lvalueOp == OPCodeEnum.OP_get_field.id) {
                    tmpObj = ensureTmpVar()
                    emitOpWithArg(OPCodeEnum.OP_put_loc, tmpObj)
                } else if (lvalueOp == OPCodeEnum.OP_get_array_el.id) {
                    tmpIdx = ensureTmpVar()
                    tmpObj = ensureTmpVar()
                    emitOpWithArg(OPCodeEnum.OP_put_loc, tmpIdx)
                    emitOpWithArg(OPCodeEnum.OP_put_loc, tmpObj)
                }
            }
            
            s.nextToken()   /* 消费 in / of */
            
            val gotoExpr = emitJump(OPCodeEnum.OP_goto)
            
            /* next: 把栈顶的迭代值写回左值 */
            val nextPos = s.byteCodeLen
            when {
                varGlobalCidx >= 0 -> emitOpWithArg(OPCodeEnum.OP_define_global, varGlobalCidx)
                varIdx >= 0 -> emitOpWithArg(OPCodeEnum.OP_put_loc, varIdx)
                lvalueOp == OPCodeEnum.OP_get_loc.id -> emitOpWithArg(OPCodeEnum.OP_put_loc, lvalueArg)
                lvalueOp == OPCodeEnum.OP_get_global.id -> emitOpWithArg(OPCodeEnum.OP_put_global, lvalueArg)
                lvalueOp == OPCodeEnum.OP_get_var_ref.id -> emitOpWithArg(OPCodeEnum.OP_put_var_ref, lvalueArg)
                lvalueOp == OPCodeEnum.OP_get_field.id -> {
                    val tmpVal = ensureTmpVar()
                    emitOpWithArg(OPCodeEnum.OP_put_loc, tmpVal)
                    emitOpWithArg(OPCodeEnum.OP_get_loc, tmpObj)
                    emitOpWithArg(OPCodeEnum.OP_get_loc, tmpVal)
                    emitOpWithArg(OPCodeEnum.OP_put_field, lvalueArg)
                }
                lvalueOp == OPCodeEnum.OP_get_array_el.id -> {
                    val tmpVal = ensureTmpVar()
                    emitOpWithArg(OPCodeEnum.OP_put_loc, tmpVal)
                    emitOpWithArg(OPCodeEnum.OP_get_loc, tmpObj)
                    emitOpWithArg(OPCodeEnum.OP_get_loc, tmpIdx)
                    emitOpWithArg(OPCodeEnum.OP_get_loc, tmpVal)
                    emitOp(OPCodeEnum.OP_put_array_el)
                }
                else -> {
                    s.error("unsupported for-in target")
                    return false
                }
            }
            val gotoBody = emitJump(OPCodeEnum.OP_goto)
            
            /* expr: 求值可迭代对象并创建迭代器 */
            val exprPos = s.byteCodeLen
            patchJumpTo(gotoExpr, exprPos)
            if (!parseExpr()) return false
            emitOp(if (forOf) OPCodeEnum.OP_for_of_start else OPCodeEnum.OP_for_in_start)
            val gotoCont = emitJump(OPCodeEnum.OP_goto)
            
            expect(')'.code)
            
            /* body */
            val bodyPos = s.byteCodeLen
            patchJumpTo(gotoBody, bodyPos)
            if (!parseStatement()) return false
            
            /* cont: 取下一个键；结束则跳到 next（由 if_false 消费 done） */
            val contPos = s.byteCodeLen
            patchJumpTo(gotoCont, contPos)
            emitOp(OPCodeEnum.OP_for_of_next)
            val ifFalse = emitJump(OPCodeEnum.OP_if_false)
            patchJumpTo(ifFalse, nextPos)
            emitOp(OPCodeEnum.OP_drop)
            
            /* break: 释放迭代器 */
            val breakPos = s.byteCodeLen
            for (p in breakLabels) patchJumpTo(p, breakPos)
            for (p in continueLabels) patchJumpTo(p, contPos)
            emitOp(OPCodeEnum.OP_drop)
            return true
        } finally {
            popBreakTarget()
            popContinueTarget()
        }
    }
    
    fun parseForClassic(): Boolean {
        
        /* break → 循环出口；continue → update 处（体编译后跳回 update 再进条件） */
        val breakLabels = mutableListOf<Int>()
        val continueLabels = mutableListOf<Int>()
        pushBreakTarget(breakLabels)
        pushContinueTarget(continueLabels)
        
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
                /* 无条件的 for：当前 token 是条件分隔 ';'，消费后应到 ')' */
                s.nextToken()
                expect(')'.code)
                if (!parseStatement()) return false
                emitJumpTo(OPCodeEnum.OP_goto, condPos)
                val exitPos = s.byteCodeLen
                for (p in continueLabels) patchJumpTo(p, condPos)
                for (p in breakLabels) patchJumpTo(p, exitPos)
                return true
            }
        } finally {
            popBreakTarget()
            popContinueTarget()
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
         * - 无 catch 的 finally 在异常路径 gosub 后重抛
         * 若存在 finally，则把本层登记到 finallyStack：try/catch 体内的
         * break/continue/return 会先 gosub 执行 finally 再跳转 */
        val hasFinally = hasFinallyAfterTry()
        val finallyEntry = if (hasFinally) FinallyEntry() else null
        if (finallyEntry != null) finallyStack.addLast(finallyEntry)
        var finallyStillPushed = finallyEntry != null
        try {
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
                val catchVarIdx = declareLocal(name)
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
            
            /* label_finally：所有 gosub（正常/异常/break/return）汇合 */
            for (pos in jumpsToFinally) patchJump(pos)
            if (finallyEntry != null) {
                for (pos in finallyEntry.gosubs) patchJump(pos)
            }
            
            /* 编译 finally 本体前出栈：finally 自身的 break/return 不应重复执行自身 */
            if (finallyStillPushed) {
                finallyStack.removeLast()
                finallyStillPushed = false
            }
            
            if (s.token.val1 == JSTokenVal.TOK_FINALLY.value) {
                s.nextToken()
                if (!parseBlock()) return false
            }
            emitOp(OPCodeEnum.OP_ret)
            
            /* label_end */
            for (pos in jumpsToEnd) patchJump(pos)
            
            return true
        } finally {
            if (finallyStillPushed) finallyStack.removeLast()
        }
    }
    
    private fun skipBalancedParens() {
        var depth = 0
        while (s.token.val1 != JSTokenVal.TOK_EOF.value && !s.hasError) {
            when (s.token.val1) {
                '('.code -> depth++
                ')'.code -> {
                    depth--
                    if (depth == 0) { s.nextToken(); return }
                }
            }
            s.nextToken()
        }
    }
    
    private fun skipBalancedBraces() {
        var depth = 0
        while (s.token.val1 != JSTokenVal.TOK_EOF.value && !s.hasError) {
            when (s.token.val1) {
                '{'.code -> depth++
                '}'.code -> {
                    depth--
                    if (depth == 0) { s.nextToken(); return }
                }
            }
            s.nextToken()
        }
    }
    
    /* 预读判断 try 之后是否有 finally（词法级扫描，不产生字节码） */
    private fun hasFinallyAfterTry(): Boolean {
        val savedBuf = s.bufPos
        val savedLine = s.lineNum
        val savedCol = s.colNum
        val savedLf = s.gotLf
        val savedToken = s.token.copy()
        skipBalancedBraces()
        if (s.token.val1 == JSTokenVal.TOK_CATCH.value) {
            s.nextToken()
            if (s.token.val1 == '('.code) skipBalancedParens()
            skipBalancedBraces()
        }
        val result = s.token.val1 == JSTokenVal.TOK_FINALLY.value
        seekSource(savedBuf, savedLine, savedCol, savedLf, savedToken)
        return result
    }
    
    fun parseSwitch(): Boolean {
        s.nextToken()
        expect('('.code)
        if (!parseExpr()) return false
        expect(')'.code)
        expect('{'.code)
        
        val breakLabels = mutableListOf<Int>()
        pushBreakTarget(breakLabels)
        try {
            /* 第一遍：扫描子句，记录 case 表达式/body 的源码位置 */
            val clauses = ArrayList<SwitchClause>()
            var endBuf = 0; var endLine = 0; var endCol = 0; var endLf = false
            var endToken: JSToken? = null
            while (s.token.val1 != '}'.code && s.token.val1 != JSTokenVal.TOK_EOF.value && !s.hasError) {
                when (s.token.val1) {
                    JSTokenVal.TOK_CASE.value -> {
                        s.nextToken()
                        val eBuf = s.bufPos; val eLine = s.lineNum; val eCol = s.colNum
                        val eLf = s.gotLf; val eTok = s.token.copy()
                        /* 跳到本 case 的 ':'（跳过括号内的三元/对象字面量等） */
                        var depth = 0
                        while (s.token.val1 != JSTokenVal.TOK_EOF.value && !s.hasError) {
                            val t = s.token.val1
                            if (t == ':'.code && depth == 0) break
                            when (t) {
                                '('.code, '['.code, '{'.code -> depth++
                                ')'.code, ']'.code, '}'.code -> if (depth > 0) depth--
                            }
                            s.nextToken()
                        }
                        expect(':'.code)
                        val bBuf = s.bufPos; val bLine = s.lineNum; val bCol = s.colNum
                        val bLf = s.gotLf; val bTok = s.token.copy()
                        clauses.add(SwitchClause(false, eBuf, eLine, eCol, eLf, eTok, bBuf, bLine, bCol, bLf, bTok))
                        switchScanNextClause()
                    }
                    JSTokenVal.TOK_DEFAULT.value -> {
                        s.nextToken()
                        expect(':'.code)
                        val bBuf = s.bufPos; val bLine = s.lineNum; val bCol = s.colNum
                        val bLf = s.gotLf; val bTok = s.token.copy()
                        clauses.add(SwitchClause(true, 0, 0, 0, false, null, bBuf, bLine, bCol, bLf, bTok))
                        switchScanNextClause()
                    }
                    else -> switchScanNextClause()
                }
            }
            if (!s.hasError && s.token.val1 == '}'.code) {
                endBuf = s.bufPos; endLine = s.lineNum; endCol = s.colNum
                endLf = s.gotLf; endToken = s.token.copy()
            }
            if (s.hasError) return false
            
            /* 第二遍：先分发（严格相等比较链），再按源码顺序编译各 body */
            val discTmp = ensureTmpVar()
            emitOpWithArg(OPCodeEnum.OP_put_loc, discTmp)
            
            val clauseJumps = IntArray(clauses.size) { -1 }
            for (i in clauses.indices) {
                val cl = clauses[i]
                if (cl.isDefault) continue
                seekSource(cl.exprBuf, cl.exprLine, cl.exprCol, cl.exprLf, cl.exprToken)
                if (!parseExpr()) return false
                emitOpWithArg(OPCodeEnum.OP_get_loc, discTmp)
                emitOp(OPCodeEnum.OP_strict_eq)
                clauseJumps[i] = emitJump(OPCodeEnum.OP_if_true)
            }
            val noMatchJump = emitJump(OPCodeEnum.OP_goto)
            
            val bodyPos = IntArray(clauses.size)
            var defaultIdx = -1
            for (i in clauses.indices) {
                val cl = clauses[i]
                bodyPos[i] = s.byteCodeLen
                if (cl.isDefault) defaultIdx = i
                else patchJumpTo(clauseJumps[i], bodyPos[i])
                seekSource(cl.bodyBuf, cl.bodyLine, cl.bodyCol, cl.bodyLf, cl.bodyToken)
                while (s.token.val1 != JSTokenVal.TOK_CASE.value &&
                       s.token.val1 != JSTokenVal.TOK_DEFAULT.value &&
                       s.token.val1 != '}'.code &&
                       s.token.val1 != JSTokenVal.TOK_EOF.value && !s.hasError) {
                    if (!parseStatement()) return false
                }
            }
            
            /* 回到 switch 的 '}' 并收尾；无匹配时跳到 default body 或出口 */
            seekSource(endBuf, endLine, endCol, endLf, endToken)
            val endPos = s.byteCodeLen
            patchJumpTo(noMatchJump, if (defaultIdx >= 0) bodyPos[defaultIdx] else endPos)
            for (p in breakLabels) patchJumpTo(p, endPos)
            expect('}'.code)
            return true
        } finally {
            popBreakTarget()
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
        /* 逗号表达式：a, b, c —— 前面的值依次丢弃，整个表达式的值为最后一个 */
        if (!parseAssignExpr()) return false
        while (s.token.val1 == ','.code) {
            s.nextToken()
            emitOp(OPCodeEnum.OP_drop)
            if (!parseAssignExpr()) return false
        }
        return true
    }
    
    fun parseAssignExpr(): Boolean {
        if (!parseConditionalExpr()) return false
        
        when (s.token.val1) {
            '='.code -> {
                val lvalueOp = getLastOp()
                val lvalueArg = getLastOpArg()
                val lvaluePos = if (s.lastOpcodePos >= 0)
                    (s.posMarks[s.lastOpcodePos] ?: s.token.sourcePos) else s.token.sourcePos
                removeLastOp()
                
                s.nextToken()
                if (!parseAssignExpr()) return false
                
                /* rhs 值复制后保存到临时槽（put_* 写回会消费栈上的副本），
                   读回 tmp 作为赋值表达式的值 */
                emitOp(OPCodeEnum.OP_dup)
                val tmpIdx = ensureTmpVar()
                emitOpWithArg(OPCodeEnum.OP_put_loc, tmpIdx)
                s.curPos = lvaluePos
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
        val assignPos = s.token.sourcePos
        val lvalueOp = getLastOp()
        val lvalueArg = getLastOpArg()
        removeLastOp()

        /* 数组元素（a[E] op= v）：obj/idx 已在栈顶，存临时槽后按需重载，
         * 写回时 put_array_el 消费 [obj, idx, val]（idx 为运行期栈值，
         * 不能像 put_field 那样编码进指令参数） */
        if (lvalueOp == OPCodeEnum.OP_get_array_el.id) {
            val tmpObj = ensureTmpVar()
            val tmpIdx = ensureTmpVar()
            val tmpVal = ensureTmpVar()
            emitOpWithArg(OPCodeEnum.OP_put_loc, tmpIdx)   /* [obj] */
            emitOpWithArg(OPCodeEnum.OP_put_loc, tmpObj)   /* [] */
            emitOpWithArg(OPCodeEnum.OP_get_loc, tmpObj)
            emitOpWithArg(OPCodeEnum.OP_get_loc, tmpIdx)
            emitOp(OPCodeEnum.OP_get_array_el)             /* [old] */
            s.nextToken()
            if (!parseAssignExpr()) return false           /* [old, rhs] */
            s.curPos = assignPos
            emitOp(binOp)                                  /* [new] */
            emitOpWithArg(OPCodeEnum.OP_put_loc, tmpVal)   /* [] */
            emitOpWithArg(OPCodeEnum.OP_get_loc, tmpObj)
            emitOpWithArg(OPCodeEnum.OP_get_loc, tmpIdx)
            emitOpWithArg(OPCodeEnum.OP_get_loc, tmpVal)   /* [obj, idx, new] */
            emitOp(OPCodeEnum.OP_put_array_el)             /* [] 写回 */
            emitOpWithArg(OPCodeEnum.OP_get_loc, tmpVal)   /* [new] 表达式值 */
            return true
        }

        /* 复合赋值需要读取旧值参与运算（槽目标也要读） */
        reemitLValueLoad(lvalueOp, lvalueArg, forCompound = true)
        
        s.nextToken()
        if (!parseAssignExpr()) return false
        s.curPos = assignPos
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
            /* a || b：保留左值作为短路结果（dup 后再判断，假值路径 drop 后求右值） */
            emitOp(OPCodeEnum.OP_dup)
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
            /* a && b：保留左值作为短路结果 */
            emitOp(OPCodeEnum.OP_dup)
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
                    /* for-in 的左侧表达式不允许把 in 当作运算符消费（对照 C 的 PF_NO_IN） */
                    if (noInFor) break
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
                    val opPos = s.token.sourcePos
                    s.nextToken()
                    if (!parseMultiplicativeExpr()) return false
                    s.curPos = opPos
                    emitOp(OPCodeEnum.OP_add)
                }
                '-'.code -> {
                    val opPos = s.token.sourcePos
                    s.nextToken()
                    if (!parseMultiplicativeExpr()) return false
                    s.curPos = opPos
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
                    val opPos = s.token.sourcePos
                    s.nextToken()
                    if (!parseUnaryExpr()) return false
                    s.curPos = opPos
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
                    val opPos = s.token.sourcePos
                    s.nextToken()
                    if (!parseUnaryExpr()) return false
                    s.curPos = opPos
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
                val opPos = s.token.sourcePos
                s.nextToken()
                if (!parseUnaryExpr()) return false
                s.curPos = opPos
                emitOp(OPCodeEnum.OP_plus)
                return true
            }
            '-'.code -> {
                val opPos = s.token.sourcePos
                s.nextToken()
                if (!parseUnaryExpr()) return false
                s.curPos = opPos
                emitOp(OPCodeEnum.OP_neg)
                return true
            }
            '!'.code -> {
                val opPos = s.token.sourcePos
                s.nextToken()
                if (!parseUnaryExpr()) return false
                s.curPos = opPos
                emitOp(OPCodeEnum.OP_lnot)
                return true
            }
            '~'.code -> {
                val opPos = s.token.sourcePos
                s.nextToken()
                if (!parseUnaryExpr()) return false
                s.curPos = opPos
                emitOp(OPCodeEnum.OP_not)
                return true
            }
            JSTokenVal.TOK_INC.value -> {
                val opPos = s.token.sourcePos
                s.nextToken()
                if (!parseUnaryExpr()) return false
                s.curPos = opPos
                emitLValueIncDec(inc = true, pre = true)
                return true
            }
            JSTokenVal.TOK_DEC.value -> {
                val opPos = s.token.sourcePos
                s.nextToken()
                if (!parseUnaryExpr()) return false
                s.curPos = opPos
                emitLValueIncDec(inc = false, pre = true)
                return true
            }
            JSTokenVal.TOK_TYPEOF.value -> {
                s.nextToken()
                val savedTypeof = inTypeof
                inTypeof = true
                try {
                    if (!parseUnaryExpr()) return false
                } finally {
                    inTypeof = savedTypeof
                }
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
                /* delete 的操作数需要 [obj, key] 而不是属性读取结果：
                 * 撤掉最后一条 get_field/get_array_el，改为压入 key */
                when (getLastOp()) {
                    OPCodeEnum.OP_get_field.id -> {
                        val idx = getLastOpArg()
                        removeLastOp()
                        emitOpWithArg(OPCodeEnum.OP_push_const, idx)
                        emitOp(OPCodeEnum.OP_delete)
                    }
                    OPCodeEnum.OP_get_array_el.id -> {
                        removeLastOp()
                        emitOp(OPCodeEnum.OP_delete)
                    }
                    else -> {
                        emitOp(OPCodeEnum.OP_drop)
                        emitOp(OPCodeEnum.OP_push_true)
                    }
                }
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
                return parseSuffixes()
            }
        }
        
        return parsePostfixExpr()
    }
    
    fun parsePostfixExpr(): Boolean {
        if (!parseMemberExpr()) return false
        return parseSuffixes()
    }
    
    /* 后缀链：属性/索引/调用/自增。new 表达式之后也要继续解析（new F().x） */
    private fun parseSuffixes(): Boolean {
        while (true) {
            when (s.token.val1) {
                JSTokenVal.TOK_INC.value -> {
                    val opPos = s.token.sourcePos
                    s.nextToken()
                    s.curPos = opPos
                    emitLValueIncDec(inc = true, pre = false)
                }
                JSTokenVal.TOK_DEC.value -> {
                    val opPos = s.token.sourcePos
                    s.nextToken()
                    s.curPos = opPos
                    emitLValueIncDec(inc = false, pre = false)
                }
                '('.code -> {
                    val callPos = s.token.sourcePos
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
                    s.curPos = callPos
                    if (isMethodCall) {
                        emitOpWithArg(OPCodeEnum.OP_call_method, argc)
                    } else {
                        emitOpWithArg(OPCodeEnum.OP_call, argc)
                    }
                }
                '['.code -> {
                    val opPos = s.token.sourcePos
                    s.nextToken()
                    if (!parseExpr()) return false
                    expect(']'.code)
                    s.curPos = opPos
                    emitOp(OPCodeEnum.OP_get_array_el)
                }
                '.'.code -> {
                    val opPos = s.token.sourcePos
                    s.nextToken()
                    /* 属性名允许关键字（obj.if / obj.in 等） */
                    if (!JS_IsString(ctx, s.token.value)) {
                        s.error("expected identifier after '.'")
                        return false
                    }
                    /* 属性名在消费 token 前先入常量池：后续词法分析可能触发 GC */
                    val idx = addConstPool(s.token.value)
                    s.nextToken()
                    /* 先按普通属性读取发射（消费 obj），若后续紧跟 ( 再回改为 get_field2 */
                    s.curPos = opPos
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
        /* 自增/自减统一用 "值 + 1" 或 "值 - 1"：
         * 自减不能用 "-1 再加"（那会变成 +1） */
        val deltaOp = OPCodeEnum.OP_push_1
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
                    emitOp(OPCodeEnum.OP_plus)                  /* 后置表达式值转为数值 */
                }
            }
            OPCodeEnum.OP_get_var_ref.id -> {
                val idx = readU16From(lastPos + 1)
                val tmp = ensureTmpVar()
                if (pre) {
                    emitOp(deltaOp); emitOp(addOp)
                    emitOp(OPCodeEnum.OP_dup)
                    emitOpWithArg(OPCodeEnum.OP_put_loc, tmp)
                    emitOpWithArg(OPCodeEnum.OP_put_var_ref, idx)
                    emitOpWithArg(OPCodeEnum.OP_get_loc, tmp)
                } else {
                    emitOpWithArg(OPCodeEnum.OP_put_loc, tmp)
                    emitOpWithArg(OPCodeEnum.OP_get_var_ref, idx)
                    emitOp(deltaOp); emitOp(addOp)
                    emitOpWithArg(OPCodeEnum.OP_put_var_ref, idx)
                    emitOpWithArg(OPCodeEnum.OP_get_loc, tmp)
                    emitOp(OPCodeEnum.OP_plus)
                }
            }
            OPCodeEnum.OP_get_global.id -> {
                val idx = readU16From(lastPos + 1)
                val tmp = ensureTmpVar()
                if (pre) {
                    emitOp(deltaOp); emitOp(addOp)
                    emitOp(OPCodeEnum.OP_dup)
                    emitOpWithArg(OPCodeEnum.OP_put_loc, tmp)
                    emitOpWithArg(OPCodeEnum.OP_put_global, idx)
                    emitOpWithArg(OPCodeEnum.OP_get_loc, tmp)
                } else {
                    emitOpWithArg(OPCodeEnum.OP_put_loc, tmp)
                    emitOpWithArg(OPCodeEnum.OP_get_global, idx)
                    emitOp(deltaOp); emitOp(addOp)
                    emitOpWithArg(OPCodeEnum.OP_put_global, idx)
                    emitOpWithArg(OPCodeEnum.OP_get_loc, tmp)
                    emitOp(OPCodeEnum.OP_plus)
                }
            }
            OPCodeEnum.OP_get_array_el.id -> {
                /* 数组元素（a[E]++/--）：obj/idx 存临时槽（只求值一次），
                 * put_array_el 消费 [obj, idx, val]，post 形式表达式值为旧值。
                 * 先移除原 get_array_el 读取指令（保留其前面的 obj/idx 压栈指令，
                 * 与 get_loc 分支"原读取指令留存"的处理不同：数组读取是消耗
                 * obj/idx 的弹出式指令，留存会残留旧值破坏栈序） */
                removeLastOp()
                val tmpObj = ensureTmpVar()
                val tmpIdx = ensureTmpVar()
                val tmpVal = ensureTmpVar()
                if (pre) {
                    emitOpWithArg(OPCodeEnum.OP_put_loc, tmpIdx)  /* [obj] */
                    emitOpWithArg(OPCodeEnum.OP_put_loc, tmpObj)  /* [] */
                    emitOpWithArg(OPCodeEnum.OP_get_loc, tmpObj)
                    emitOpWithArg(OPCodeEnum.OP_get_loc, tmpIdx)
                    emitOp(OPCodeEnum.OP_get_array_el)            /* [old] */
                    emitOp(deltaOp); emitOp(addOp)                /* [new] */
                    emitOpWithArg(OPCodeEnum.OP_put_loc, tmpVal)  /* [] */
                    emitOpWithArg(OPCodeEnum.OP_get_loc, tmpObj)
                    emitOpWithArg(OPCodeEnum.OP_get_loc, tmpIdx)
                    emitOpWithArg(OPCodeEnum.OP_get_loc, tmpVal)  /* [obj, idx, new] */
                    emitOp(OPCodeEnum.OP_put_array_el)            /* [] 写回 */
                    emitOpWithArg(OPCodeEnum.OP_get_loc, tmpVal)  /* [new] */
                } else {
                    emitOpWithArg(OPCodeEnum.OP_put_loc, tmpIdx)  /* [obj] */
                    emitOpWithArg(OPCodeEnum.OP_put_loc, tmpObj)  /* [] */
                    emitOpWithArg(OPCodeEnum.OP_get_loc, tmpObj)
                    emitOpWithArg(OPCodeEnum.OP_get_loc, tmpIdx)
                    emitOp(OPCodeEnum.OP_get_array_el)            /* [old] */
                    emitOpWithArg(OPCodeEnum.OP_put_loc, tmpVal)  /* [] 存旧值 */
                    emitOpWithArg(OPCodeEnum.OP_get_loc, tmpObj)
                    emitOpWithArg(OPCodeEnum.OP_get_loc, tmpIdx)
                    emitOpWithArg(OPCodeEnum.OP_get_loc, tmpVal)
                    emitOp(deltaOp); emitOp(addOp)                /* [obj, idx, new] */
                    emitOp(OPCodeEnum.OP_put_array_el)            /* [] 写回 */
                    emitOpWithArg(OPCodeEnum.OP_get_loc, tmpVal)  /* [old] */
                    emitOp(OPCodeEnum.OP_plus)                    /* 后置表达式值转为数值 */
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
                    emitOp(OPCodeEnum.OP_plus)                  /* 后置表达式值转为数值 */
                }
            }
            else -> {
                /* 不支持写回的 lvalue 形式：退化为对栈顶值的 inc/dec */
                emitOp(deltaOp); emitOp(addOp)
            }
        }
    }
    
    fun parsePrimaryExpr(): Boolean {
        /* 表达式起始位置的 '/' 是正则字面量（词法器按除号处理，这里回退重扫） */
        if (s.token.val1 == '/'.code) {
            if (!s.parseRegexpLiteral()) return false
        }
        when (s.token.val1) {
            JSTokenVal.TOK_REGEXP.value -> {
                val patIdx = addConstPool(s.token.value)
                val flagsIdx = addConstPool(JS_NewString(ctx, s.token.regexpFlags))
                emitOpWithArg(OPCodeEnum.OP_push_const, patIdx)
                emitOpWithArg(OPCodeEnum.OP_push_const, flagsIdx)
                emitOp(OPCodeEnum.OP_regexp)
                s.nextToken()
                return true
            }
            JSTokenVal.TOK_BIGINT.value -> {
                val idx = addConstPool(s.token.value)
                emitOpWithArg(OPCodeEnum.OP_push_const, idx)
                s.nextToken()
                return true
            }
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
                /* 后续 token 的词法分析（如关键字 in/instanceof）也会分配字符串并
                 * 可能触发 GC：变量名放入保活槽后再消费 token */
                val nameKeepIdx = ctx.gcKeepAlive.size
                ctx.gcKeepAlive.add(s.token.value)
                val identPos = s.token.sourcePos
                try {
                    s.nextToken()
                    /* 变量访问统一发射：局部 get_loc / 捕获 get_var_ref / 全局 get_global */
                    s.curPos = identPos
                    emitVarLoad(ctx.gcKeepAlive[nameKeepIdx])
                } finally {
                    ctx.gcKeepAlive.removeAt(nameKeepIdx)
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
            /* 属性名可为标识符、关键字（{if: 2}）或字符串 */
            if (!JS_IsString(ctx, s.token.value)) {
                s.error("expected property name")
                return false
            }
            
            /* 区分访问器：get/set 后紧跟属性名（而非 ':' '(' ',' '}'） */
            var accessor = 0
            if (s.token.val1 == JSTokenVal.TOK_IDENT.value) {
                val n = jsGetString(ctx, s.token.value)
                if (n == "get" || n == "set") {
                    val savedBuf = s.bufPos
                    val savedLine = s.lineNum
                    val savedCol = s.colNum
                    val savedLf = s.gotLf
                    val savedToken = s.token.copy()
                    s.nextToken()
                    if (JS_IsString(ctx, s.token.value)) {
                        accessor = if (n == "get") 1 else 2
                    } else {
                        seekSource(savedBuf, savedLine, savedCol, savedLf, savedToken)
                    }
                }
            }
            
            /* 属性名在消费 token 前先入常量池：后续词法分析可能触发 GC 搬移 */
            val keyKeepIdx = ctx.gcKeepAlive.size
            ctx.gcKeepAlive.add(s.token.value)
            try {
                val keyIdx = addConstPool(ctx.gcKeepAlive[keyKeepIdx])
                s.nextToken()
                
                if (accessor != 0) {
                    /* get x() {...} / set x(v) {...}，token 此时位于 '(' */
                    if (!parseFunctionBody(keyKeepIdx)) return false
                    emitOpWithArg(
                        if (accessor == 1) OPCodeEnum.OP_define_getter else OPCodeEnum.OP_define_setter,
                        keyIdx
                    )
                } else if (s.token.val1 == '('.code) {
                    /* 方法简写：f(v) { ... } */
                    if (!parseFunctionBody(keyKeepIdx)) return false
                    emitOpWithArg(OPCodeEnum.OP_define_field, keyIdx)
                } else if (s.token.val1 == ':'.code) {
                    s.nextToken()
                    if (!parseAssignExpr()) return false
                    val keyName = jsGetString(ctx, ctx.gcKeepAlive[keyKeepIdx])
                    if (keyName == "__proto__") {
                        emitOp(OPCodeEnum.OP_set_proto)
                    } else {
                        emitOpWithArg(OPCodeEnum.OP_define_field, keyIdx)
                    }
                } else {
                    s.error("expected ':' or '(' in object literal")
                    return false
                }
            } finally {
                ctx.gcKeepAlive.removeAt(keyKeepIdx)
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
        
        /* 具名函数表达式：名字要跨整个函数体使用，先入保活槽 */
        var nameKeepIdx = -1
        if (s.token.val1 == JSTokenVal.TOK_IDENT.value) {
            nameKeepIdx = ctx.gcKeepAlive.size
            ctx.gcKeepAlive.add(s.token.value)
            s.nextToken()
        }
        try {
            return parseFunctionBody(if (nameKeepIdx >= 0) nameKeepIdx else null)
        } finally {
            if (nameKeepIdx >= 0) ctx.gcKeepAlive.removeAt(nameKeepIdx)
        }
    }
    
    /* 编译函数体并压入闭包：要求 s.token 位于 '('。
     * nameKeepIdx 为函数名在保活列表中的槽位（null 表示匿名） */
    private fun parseFunctionBody(nameKeepIdx: Int?): Boolean {
        val savedByteCodeLen = s.byteCodeLen
        val savedLocalVarsLen = s.localVarsLen
        val savedEvalRetIdx = s.evalRetIdx
        val savedCpoolLen = s.cpoolLen
        val savedPosMarks = s.posMarks
        
        s.curFunc = allocFunctionBytecode()
        if (JS_IsException(s.curFunc)) {
            s.curFunc = scopeCurFunc(compileScopes.last())
            return false
        }
        
        s.byteCode = allocByteArray(256)
        if (JS_IsException(s.byteCode)) {
            s.curFunc = scopeCurFunc(compileScopes.last())
            s.byteCode = scopeByteCode(compileScopes.last())
            return false
        }
        s.byteCodeLen = 0
        s.localVarsLen = 0
        /* 常量池长度按函数独立计数（见 parseFunctionDecl 中的说明） */
        s.cpoolLen = 0
        s.posMarks = HashMap()
        s.evalRetIdx = -1
        
        /* 函数编译上下文入栈（闭包捕获的回改作用域）——byteCode 为本层对象 */
        pushCompileScope()
        try {
            if (nameKeepIdx != null) {
                setFunctionName(s.curFunc, ctx.gcKeepAlive[nameKeepIdx])
            }
            
            expect('('.code)
            val argCount = parseParams()
            expect(')'.code)
            
            setArgCount(s.curFunc, argCount)
            
            expect('{'.code)
            /* 具名函数表达式：函数名在自身作用域内可见（绑定为局部变量） */
            if (nameKeepIdx != null) {
                val nameVal = ctx.gcKeepAlive[nameKeepIdx]
                val idx = declareLocal(nameVal)
                emitOp(OPCodeEnum.OP_this_func)
                emitOpWithArg(OPCodeEnum.OP_put_loc, idx)
            }
            hoistDeclarations(untilEof = false)
            while (s.token.val1 != '}'.code && s.token.val1 != JSTokenVal.TOK_EOF.value && !s.hasError) {
                if (!parseStatement()) break
            }
            expect('}'.code)
            
            emitOp(OPCodeEnum.OP_return_undef)
            
            /* 收缩到精确长度（同 parseFunctionDecl 的说明） */
            peepholeOptimize()
        val exactByteCode = shrinkByteArray(s.byteCode, s.byteCodeLen)
            if (JS_IsException(exactByteCode)) {
                s.error("out of memory")
            } else {
                s.byteCode = exactByteCode
            }
            val newFuncPtr = JS_VALUE_TO_PTR(s.curFunc)
            ctx.memory.putJSValue(newFuncPtr + 16, s.byteCode)
            finishPc2line()
            storeCaptures()
        } finally {
            popCompileScope()
        }
        
        val newFunc = s.curFunc
        /* 恢复外层编译状态：从外层作用域的保活槽读取最新地址 */
        s.curFunc = scopeCurFunc(compileScopes.last())
        s.byteCode = scopeByteCode(compileScopes.last())
        s.byteCodeLen = savedByteCodeLen
        s.localVarsLen = savedLocalVarsLen
        s.evalRetIdx = savedEvalRetIdx
        s.cpoolLen = savedCpoolLen
        s.posMarks = savedPosMarks
        
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
        s.posMarks[s.lastOpcodePos] = if (s.curPos >= 0) s.curPos else s.token.sourcePos
        ctx.memory.putU8(ptr + 8 + s.byteCodeLen, op.id)
        s.byteCodeLen++
        syncScopeLen()
    }
    
    fun emitI8(v: Byte) {
        ensureByteCodeSize(2)
        val ptr = JS_VALUE_TO_PTR(s.byteCode)
        s.lastOpcodePos = s.byteCodeLen
        s.posMarks[s.lastOpcodePos] = if (s.curPos >= 0) s.curPos else s.token.sourcePos
        ctx.memory.putU8(ptr + 8 + s.byteCodeLen, OPCodeEnum.OP_push_i8.id)
        ctx.memory.putU8(ptr + 8 + s.byteCodeLen + 1, v.toInt())
        s.byteCodeLen += 2
    }
    
    fun emitOpWithArg(op: OPCodeEnum, arg: Int) {
        ensureByteCodeSize(3)
        val ptr = JS_VALUE_TO_PTR(s.byteCode)
        s.lastOpcodePos = s.byteCodeLen
        s.posMarks[s.lastOpcodePos] = if (s.curPos >= 0) s.curPos else s.token.sourcePos
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
        s.lastOpcodePos = pos
        s.posMarks[pos] = if (s.curPos >= 0) s.curPos else s.token.sourcePos
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
            val newByteCode = resizeByteArray(s.byteCode, newSize)
            if (JS_IsException(newByteCode)) {
                s.error("out of memory")
                return
            }
            s.byteCode = newByteCode
            /* 当前作用域保存的捕获回改目标必须同步到新对象（旧数组已被替换） */
            compileScopes.lastOrNull()?.let { updateScopeByteCode(it, newByteCode) }
        }
    }
    
    fun allocByteArray(size: Int): JSValue {
        val ptr = ctx.malloc(8 + size, JS_MTAG_BYTE_ARRAY)
        if (ptr == 0) return JS_EXCEPTION
        ctx.memory.putI32(ptr + 4, size)
        return JS_VALUE_FROM_PTR(ptr)
    }
    
    /* 扩容字节码数组。
     * malloc 可能触发 GC 压缩搬移，val1 在搬移后必须从保活槽重读，
     * 否则会从旧地址拷贝数据（长脚本解析期内存搬移导致的悬垂引用） */
    fun resizeByteArray(val1: JSValue, newSize: Int): JSValue {
        ctx.gcKeepAlive.add(val1)
        try {
            val oldPtr = JS_VALUE_TO_PTR(ctx.gcKeepAlive[ctx.gcKeepAlive.size - 1])
            val oldSize = ctx.memory.getI32(oldPtr + 4)
            val newPtr = ctx.malloc(8 + newSize, JS_MTAG_BYTE_ARRAY)
            if (newPtr == 0) return JS_EXCEPTION
            ctx.memory.putI32(newPtr + 4, newSize)
            val srcPtr = JS_VALUE_TO_PTR(ctx.gcKeepAlive[ctx.gcKeepAlive.size - 1])
            ctx.memory.copy(srcPtr + 8, newPtr + 8, minOf(oldSize, newSize))
            return JS_VALUE_FROM_PTR(newPtr)
        } finally {
            ctx.gcKeepAlive.removeAt(ctx.gcKeepAlive.size - 1)
        }
    }
    
    /* 把字节码数组收缩到精确长度（对照 C js_shrink_byte_array）。
     * 关键：内存层的块大小是按 BYTE_ARRAY 的 +4 长度字段推导的。编译期数组
     * 分配的是容量（如 256），若只把 +4 改成已用长度而不收缩块，GC 遍历堆时
     * 会按较小长度计算块大小并错位，把后续堆块当成别的对象，最终读写越界
     * ——长脚本在解析后触发 GC 时崩溃的根因之一 */
    fun shrinkByteArray(val1: JSValue, newSize: Int): JSValue {
        ctx.gcKeepAlive.add(val1)
        try {
            val newPtr = ctx.malloc(8 + newSize, JS_MTAG_BYTE_ARRAY)
            if (newPtr == 0) return JS_EXCEPTION
            ctx.memory.putI32(newPtr + 4, newSize)
            val srcPtr = JS_VALUE_TO_PTR(ctx.gcKeepAlive[ctx.gcKeepAlive.size - 1])
            ctx.memory.copy(srcPtr + 8, newPtr + 8, newSize)
            return JS_VALUE_FROM_PTR(newPtr)
        } finally {
            ctx.gcKeepAlive.removeAt(ctx.gcKeepAlive.size - 1)
        }
    }
    
    /* 源码字节偏移 → (line<<16 | col)（列按 UTF-8 码点计，对照 C 的 get_line_col） */
    private fun lineColOf(pos: Int): Int {
        var line = 1
        var col = 1
        var i = 0
        while (i < pos && i < s.sourceBuf.size) {
            val c = s.sourceBuf[i].toInt() and 0xFF
            if (c == '\n'.code) {
                line++; col = 1
            } else if (c < 0x80 || c >= 0xC0) {
                col++
            }
            i++
        }
        return (line shl 16) or (col and 0xFFFF)
    }
    
    /* 把当前函数的 pc→源码偏移 表转换为 pc→行列 稀疏表（Kotlin 侧，GC 搬移键） */
    private fun finishPc2line() {
        if (s.curFunc == JS_NULL) return
        val marks = s.posMarks
        val keys = marks.keys.sorted()
        val arr = IntArray(keys.size * 2)
        for ((i, k) in keys.withIndex()) {
            arr[i * 2] = k
            arr[i * 2 + 1] = lineColOf(marks[k]!!)
        }
        ctx.pc2lineMap[JS_VALUE_TO_PTR(s.curFunc)] = arr
    }
    
    /* 读取/写入字节码指定位置的辅助（peephole 使用） */
    private fun bcU8(pos: Int): Int = ctx.memory.getU8(JS_VALUE_TO_PTR(s.byteCode) + 8 + pos)
    private fun bcU16(pos: Int): Int = ctx.memory.getU16(JS_VALUE_TO_PTR(s.byteCode) + 8 + pos)
    private fun bcI32(pos: Int): Int = ctx.memory.getI32(JS_VALUE_TO_PTR(s.byteCode) + 8 + pos)
    private fun bcPutU8(pos: Int, v: Int) {
        ctx.memory.putU8(JS_VALUE_TO_PTR(s.byteCode) + 8 + pos, v)
    }
    private fun bcPutU16(pos: Int, v: Int) {
        ctx.memory.putU16(JS_VALUE_TO_PTR(s.byteCode) + 8 + pos, v)
    }
    private fun bcPutU32(pos: Int, v: Long) {
        ctx.memory.putU32(JS_VALUE_TO_PTR(s.byteCode) + 8 + pos, v)
    }

    /* 解释器超级指令融合（对照 C 的 peephole 优化思路）：
     * 识别常见的自增/复合赋值/比较跳转序列，替换为单条超级指令；
     * 新指令占用与原序列相同的字节长度（剩余字节填 NOP），跳转偏移无需修正；
     * 含跳转目标落点的序列不融合，避免跳过目标。 */
    private fun peepholeOptimize() {
        val len = s.byteCodeLen
        if (len < 8) return
        val sizeOf = IntArray(256) { 1 }
        for (e in OPCodeEnum.entries) {
            if (e.id in 0..255) sizeOf[e.id] = if (e.size > 0) e.size else 1
        }
        fun isJump(op: Int) = op == OPCodeEnum.OP_goto.id || op == OPCodeEnum.OP_if_false.id ||
                              op == OPCodeEnum.OP_if_true.id
        val targets = HashSet<Int>()
        var p = 0
        while (p < len) {
            val op = bcU8(p)
            if (isJump(op)) targets.add(p + 5 + bcI32(p + 1))
            p += sizeOf[op]
        }
        fun hasTargetInside(from: Int, toExcl: Int): Boolean {
            for (t in targets) if (t > from && t < toExcl) return true
            return false
        }
        fun fillNop(from: Int, toExcl: Int) {
            for (j in from until toExcl) bcPutU8(j, OPCodeEnum.OP_nop.id)
        }
        p = 0
        while (p < len) {
            val op = bcU8(p)
            if (op == OPCodeEnum.OP_put_loc.id) {
                /* i++ / i--（语句上下文）：前一条 get_loc 已把旧值压栈，
                 * 超级指令执行时需先弹出该旧值，保持与原序列一致的栈净效果 */
                val tmp = bcU16(p + 1)
                if (p + 16 <= len &&
                    bcU8(p + 3) == OPCodeEnum.OP_get_loc.id &&
                    bcU8(p + 6) == OPCodeEnum.OP_push_1.id &&
                    (bcU8(p + 7) == OPCodeEnum.OP_add.id || bcU8(p + 7) == OPCodeEnum.OP_sub.id) &&
                    bcU8(p + 8) == OPCodeEnum.OP_put_loc.id && bcU16(p + 9) == bcU16(p + 4) &&
                    bcU8(p + 11) == OPCodeEnum.OP_get_loc.id && bcU16(p + 12) == tmp &&
                    bcU8(p + 14) == OPCodeEnum.OP_plus.id &&
                    bcU8(p + 15) == OPCodeEnum.OP_drop.id &&
                    bcU16(p + 4) != tmp && !hasTargetInside(p, p + 16)) {
                    val idx = bcU16(p + 4)
                    bcPutU8(p, if (bcU8(p + 7) == OPCodeEnum.OP_add.id) OPCodeEnum.OP_inc_loc.id
                               else OPCodeEnum.OP_dec_loc.id)
                    bcPutU16(p + 1, idx)
                    fillNop(p + 3, p + 16)
                    p += 16
                    continue
                }
            } else if (op == OPCodeEnum.OP_get_loc.id) {
                val a = bcU16(p + 1)
                /* s += b（局部变量） */
                if (p + 18 <= len &&
                    bcU8(p + 3) == OPCodeEnum.OP_get_loc.id &&
                    bcU8(p + 6) == OPCodeEnum.OP_add.id &&
                    bcU8(p + 7) == OPCodeEnum.OP_dup.id &&
                    bcU8(p + 8) == OPCodeEnum.OP_put_loc.id &&
                    bcU8(p + 11) == OPCodeEnum.OP_put_loc.id && bcU16(p + 12) == a &&
                    bcU8(p + 14) == OPCodeEnum.OP_get_loc.id &&
                    bcU16(p + 15) == bcU16(p + 9) &&
                    bcU8(p + 17) == OPCodeEnum.OP_drop.id &&
                    !hasTargetInside(p, p + 18)) {
                    bcPutU8(p, OPCodeEnum.OP_add_loc_loc.id)
                    bcPutU16(p + 1, a)
                    bcPutU16(p + 3, bcU16(p + 4))
                    fillNop(p + 5, p + 18)
                    p += 18
                    continue
                }
                /* s += <小整数> */
                val pushOp = bcU8(p + 3)
                var imm = 0
                var immOk = false
                if (pushOp in OPCodeEnum.OP_push_0.id..OPCodeEnum.OP_push_7.id) {
                    imm = pushOp - OPCodeEnum.OP_push_0.id; immOk = true
                } else if (pushOp == OPCodeEnum.OP_push_i8.id) {
                    imm = bcU8(p + 4).toByte().toInt(); immOk = true
                }
                if (immOk && p + 16 <= len &&
                    bcU8(p + 4) == OPCodeEnum.OP_add.id &&
                    bcU8(p + 5) == OPCodeEnum.OP_dup.id &&
                    bcU8(p + 6) == OPCodeEnum.OP_put_loc.id &&
                    bcU8(p + 9) == OPCodeEnum.OP_put_loc.id && bcU16(p + 10) == a &&
                    bcU8(p + 12) == OPCodeEnum.OP_get_loc.id &&
                    bcU16(p + 13) == bcU16(p + 7) &&
                    bcU8(p + 15) == OPCodeEnum.OP_drop.id &&
                    !hasTargetInside(p, p + 16)) {
                    bcPutU8(p, OPCodeEnum.OP_add_loc_imm.id)
                    bcPutU16(p + 1, a)
                    bcPutU8(p + 2, imm and 0xFF)
                    fillNop(p + 3, p + 16)
                    p += 16
                    continue
                }
                /* get_loc a; get_loc b; <cmp>; if_false */
                if (p + 12 <= len && bcU8(p + 3) == OPCodeEnum.OP_get_loc.id &&
                    bcU8(p + 7) == OPCodeEnum.OP_if_false.id) {
                    val cmpOp = bcU8(p + 6)
                    val cmpOk = cmpOp == OPCodeEnum.OP_lt.id || cmpOp == OPCodeEnum.OP_lte.id ||
                                cmpOp == OPCodeEnum.OP_gt.id || cmpOp == OPCodeEnum.OP_gte.id ||
                                cmpOp == OPCodeEnum.OP_eq.id || cmpOp == OPCodeEnum.OP_neq.id ||
                                cmpOp == OPCodeEnum.OP_strict_eq.id || cmpOp == OPCodeEnum.OP_strict_neq.id
                    if (cmpOk && !hasTargetInside(p, p + 12)) {
                        val off = bcI32(p + 8)
                        bcPutU8(p, OPCodeEnum.OP_cmp_loc_loc_jump.id)
                        bcPutU8(p + 1, cmpOp)
                        bcPutU16(p + 2, a)
                        bcPutU16(p + 4, bcU16(p + 4))
                        bcPutU32(p + 6, off.toLong() and 0xFFFFFFFFL)
                        fillNop(p + 10, p + 12)
                        p += 12
                        continue
                    }
                }
                /* get_loc a; push_imm; <binop> → 融合为 OP_bin_loc_imm（结果压栈） */
                val binPush = bcU8(p + 3)
                var binImm = 0
                var binImmOk = false
                var binPad = false
                if (binPush in OPCodeEnum.OP_push_0.id..OPCodeEnum.OP_push_7.id) {
                    binImm = binPush - OPCodeEnum.OP_push_0.id; binImmOk = true
                } else if (binPush == OPCodeEnum.OP_push_i8.id) {
                    binImm = bcU8(p + 4).toByte().toInt(); binImmOk = true; binPad = true
                }
                if (binImmOk) {
                    val opPos = if (binPad) p + 5 else p + 4
                    val kind = bcU8(opPos)
                    if (isBinOp(kind) && p + (if (binPad) 6 else 5) <= len &&
                        !hasTargetInside(p, p + (if (binPad) 6 else 5))) {
                        val total = if (binPad) 6 else 5
                        bcPutU8(p, OPCodeEnum.OP_bin_loc_imm.id)
                        bcPutU8(p + 1, kind or (if (binPad) 0x80 else 0))
                        bcPutU16(p + 2, a)
                        bcPutU8(p + 4, binImm and 0xFF)
                        if (binPad) fillNop(p + 5, p + 6)
                        p += total
                        continue
                    }
                }
                /* get_loc a; get_loc b; <binop> → OP_bin_loc_loc（结果压栈） */
                if (p + 7 <= len && bcU8(p + 3) == OPCodeEnum.OP_get_loc.id) {
                    val kind = bcU8(p + 6)
                    if (isBinOp(kind) && !hasTargetInside(p, p + 7)) {
                        val b = bcU16(p + 4)
                        bcPutU8(p, OPCodeEnum.OP_bin_loc_loc.id)
                        bcPutU8(p + 1, kind)
                        bcPutU16(p + 2, a)
                        bcPutU16(p + 4, b)
                        fillNop(p + 6, p + 7)
                        p += 7
                        continue
                    }
                }
            }
            p += sizeOf[op]
        }
    }
    
    /* 可融合的二元运算 opcode（结果为一个值） */
    private fun isBinOp(kind: Int): Boolean = when (kind) {
        OPCodeEnum.OP_add.id, OPCodeEnum.OP_sub.id, OPCodeEnum.OP_mul.id,
        OPCodeEnum.OP_div.id, OPCodeEnum.OP_mod.id, OPCodeEnum.OP_shl.id,
        OPCodeEnum.OP_sar.id, OPCodeEnum.OP_shr.id, OPCodeEnum.OP_and.id,
        OPCodeEnum.OP_or.id, OPCodeEnum.OP_xor.id, OPCodeEnum.OP_lt.id,
        OPCodeEnum.OP_lte.id, OPCodeEnum.OP_gt.id, OPCodeEnum.OP_gte.id,
        OPCodeEnum.OP_eq.id, OPCodeEnum.OP_neq.id,
        OPCodeEnum.OP_strict_eq.id, OPCodeEnum.OP_strict_neq.id -> true
        else -> false
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
        /* 常量索引以 u16 编码（OP_push_const/OP_*_field 的操作数），与 C 一致
         * 在超限时报错，避免索引被截断后指向错误常量 */
        if (s.cpoolLen > 0xFFFF) {
            s.error("too many constants")
            return 0
        }
        
        /* 分配可能触发 GC 压缩搬移：val1 入保活列表，之后一律从槽位读取最新地址 */
        ctx.gcKeepAlive.add(val1)
        try {
            var cpool = ctx.memory.getJSValue(JS_VALUE_TO_PTR(s.curFunc) + 24)
            
            if (JS_IsNull(cpool)) {
                cpool = allocValueArray(16)
                if (JS_IsException(cpool)) {
                    s.error("out of memory")
                    return 0
                }
                ctx.memory.putJSValue(JS_VALUE_TO_PTR(s.curFunc) + 24, cpool)
            }
            
            var cpoolPtr = JS_VALUE_TO_PTR(cpool)
            val capacity = ctx.memory.getI32(cpoolPtr + 4)
            val value = ctx.gcKeepAlive[ctx.gcKeepAlive.size - 1]
            
            /* 只在数组容量内比较。cpoolLen 若因缺陷大于容量，旧实现会把堆内
             * 任意 8 字节当作 JSValue 解引用（长脚本 IndexOutOfBoundsException
             * 的直接触发点），这里做防御性截断 */
            val scanLen = minOf(s.cpoolLen, capacity)
            for (i in 0 until scanLen) {
                val existing = ctx.memory.getJSValue(cpoolPtr + 8 + i * 8)
                if (jsValueEquals(ctx, existing, value)) return i
            }
            
            val idx = s.cpoolLen
            if (idx >= capacity) {
                /* 容量至少覆盖 idx+1：常量池计数跨函数泄漏时 size*2 仍可能不足，
                 * 会越界写入相邻堆块 */
                val newSize = maxOf(capacity * 2, idx + 1)
                cpool = resizeValueArray(cpool, newSize)
                if (JS_IsException(cpool)) {
                    s.error("out of memory")
                    return 0
                }
                ctx.memory.putJSValue(JS_VALUE_TO_PTR(s.curFunc) + 24, cpool)
                cpoolPtr = JS_VALUE_TO_PTR(cpool)
            }
            
            val writeVal = ctx.gcKeepAlive[ctx.gcKeepAlive.size - 1]
            ctx.memory.putJSValue(cpoolPtr + 8 + idx * 8, writeVal)
            s.cpoolLen++
            
            return idx
        } finally {
            ctx.gcKeepAlive.removeAt(ctx.gcKeepAlive.size - 1)
        }
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
        /* 新槽位初始化为 undefined（对照 C js_alloc_value_array）：未初始化槽
         * 里的任意堆数据被当作 JSValue 解引用会造成越界访问 */
        for (i in 0 until size) {
            ctx.memory.putJSValue(ptr + 8 + i * 8, JS_UNDEFINED)
        }
        return JS_VALUE_FROM_PTR(ptr)
    }
    
    /* 扩容值数组。malloc 可能触发 GC 压缩搬移，val1 必须经保活槽在搬移后
     * 重新取址，否则会从旧地址拷贝数据 */
    fun resizeValueArray(val1: JSValue, newSize: Int): JSValue {
        ctx.gcKeepAlive.add(val1)
        try {
            val oldPtr = JS_VALUE_TO_PTR(ctx.gcKeepAlive[ctx.gcKeepAlive.size - 1])
            val oldSize = ctx.memory.getI32(oldPtr + 4)
            val newPtr = ctx.malloc(8 + newSize * 8, JS_MTAG_VALUE_ARRAY)
            if (newPtr == 0) return JS_EXCEPTION
            ctx.memory.putI32(newPtr + 4, newSize)
            val srcPtr = JS_VALUE_TO_PTR(ctx.gcKeepAlive[ctx.gcKeepAlive.size - 1])
            val copyLen = minOf(oldSize, newSize)
            for (i in 0 until copyLen) {
                ctx.memory.putJSValue(newPtr + 8 + i * 8, ctx.memory.getJSValue(srcPtr + 8 + i * 8))
            }
            /* 剩余槽位初始化为 undefined（对照 C js_alloc_value_array 的 init_base） */
            for (i in copyLen until newSize) {
                ctx.memory.putJSValue(newPtr + 8 + i * 8, JS_UNDEFINED)
            }
            return JS_VALUE_FROM_PTR(newPtr)
        } finally {
            ctx.gcKeepAlive.removeAt(ctx.gcKeepAlive.size - 1)
        }
    }
    
    fun addLocalVar(name: JSValue): Int {
        if (s.localVarsLen >= JS_MAX_LOCAL_VARS) {
            s.error("too many local variables")
            return 0
        }
        
        /* 分配可能触发 GC 搬移：name 入保活列表，写入时取最新地址 */
        ctx.gcKeepAlive.add(name)
        try {
            var vars = ctx.memory.getJSValue(JS_VALUE_TO_PTR(s.curFunc) + 32)
            
            if (JS_IsNull(vars)) {
                vars = allocValueArray(16)
                if (JS_IsException(vars)) {
                    s.error("out of memory")
                    return 0
                }
                ctx.memory.putJSValue(JS_VALUE_TO_PTR(s.curFunc) + 32, vars)
            }
            
            var varsPtr = JS_VALUE_TO_PTR(vars)
            val capacity = ctx.memory.getI32(varsPtr + 4)
            val idx = s.localVarsLen
            
            if (idx >= capacity) {
                val newSize = maxOf(capacity * 2, idx + 1)
                vars = resizeValueArray(vars, newSize)
                if (JS_IsException(vars)) {
                    s.error("out of memory")
                    return 0
                }
                ctx.memory.putJSValue(JS_VALUE_TO_PTR(s.curFunc) + 32, vars)
                varsPtr = JS_VALUE_TO_PTR(vars)
            }
            
            ctx.memory.putJSValue(varsPtr + 8 + idx * 8, ctx.gcKeepAlive[ctx.gcKeepAlive.size - 1])
            s.localVarsLen++
            compileScopes.lastOrNull()?.localVarsLen = s.localVarsLen
            
            return idx
        } finally {
            ctx.gcKeepAlive.removeAt(ctx.gcKeepAlive.size - 1)
        }
    }
    
    fun findLocalVar(name: JSValue): Int {
        val ptr = JS_VALUE_TO_PTR(s.curFunc)
        val vars = ctx.memory.getJSValue(ptr + 32)
        if (JS_IsNull(vars)) return -1
        
        val varsPtr = JS_VALUE_TO_PTR(vars)
        val capacity = ctx.memory.getI32(varsPtr + 4)
        for (i in 0 until minOf(s.localVarsLen, capacity)) {
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
        val capacity = ctx.memory.getI32(varsPtr + 4)
        for (i in 0 until minOf(count, capacity)) {
            val v = ctx.memory.getJSValue(varsPtr + 8 + i * 8)
            if (jsStringEquals(ctx, v, name)) return i
        }
        return -1
    }
    
    /* 标识符变量读取的统一发射：局部 → get_loc；已捕获/可捕获的外层变量 → get_var_ref；
     * 未声明 → get_global（未找到属性时运行时抛 ReferenceError） */
    private fun emitVarLoad(name: JSValue): Boolean {
        /* 查找捕获变量时可能分配字符串并触发 GC：名字入保活槽，统一从槽位读取 */
        ctx.gcKeepAlive.add(name)
        try {
            val nameStr = jsGetString(ctx, ctx.gcKeepAlive[ctx.gcKeepAlive.size - 1])
            
            val localIdx = findLocalVar(ctx.gcKeepAlive[ctx.gcKeepAlive.size - 1])
            
            /* arguments 是函数帧内建对象（未被同名局部变量遮蔽时） */
            if (localIdx < 0 && nameStr == "arguments") {
                emitOp(OPCodeEnum.OP_arguments)
                return true
            }
            
            if (localIdx >= 0) {
                /* 本层变量若已被提升（内层捕获）则走 varref 槽 */
                val scope = compileScopes.lastOrNull()
                val slot = scope?.varrefSlots?.get(nameStr)
                if (slot != null) {
                    emitOpWithArg(OPCodeEnum.OP_get_var_ref, slot)
                } else {
                    emitOpWithArg(OPCodeEnum.OP_get_loc, localIdx)
                }
                if (ctx.traceEnabled) {
                    println("DBG load '$nameStr' local=$localIdx slot=$slot scopes=${compileScopes.size}")
                }
                return true
            }
            
            /* 沿函数编译栈向外查找可捕获的外层局部变量 */
            val captured = tryCaptureOuterVar(nameStr)
            if (captured >= 0) {
                emitOpWithArg(OPCodeEnum.OP_get_var_ref, captured)
                if (ctx.traceEnabled) {
                    println("DBG load '$nameStr' captured=$captured scopes=${compileScopes.size}")
                }
                return true
            }
            
            /* 未声明的全局标识符 */
            val cidx = addConstPool(ctx.gcKeepAlive[ctx.gcKeepAlive.size - 1])
            emitOpWithArg(
                if (inTypeof) OPCodeEnum.OP_get_global_undef else OPCodeEnum.OP_get_global,
                cidx
            )
            if (ctx.traceEnabled) {
                println("DBG load '$nameStr' global=$cidx scopes=${compileScopes.size}")
            }
            return true
        } finally {
            ctx.gcKeepAlive.removeAt(ctx.gcKeepAlive.size - 1)
        }
    }
    
    /* 标识符变量写入的统一发射：局部 → put_loc；捕获槽 → put_var_ref；全局 → put_global */
    private fun emitVarStore(name: JSValue, valueLoaded: Boolean): Boolean {
        ctx.gcKeepAlive.add(name)
        try {
            val nameStr = jsGetString(ctx, ctx.gcKeepAlive[ctx.gcKeepAlive.size - 1])
            
            val localIdx = findLocalVar(ctx.gcKeepAlive[ctx.gcKeepAlive.size - 1])
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
            val cidx = addConstPool(ctx.gcKeepAlive[ctx.gcKeepAlive.size - 1])
            emitOpWithArg(OPCodeEnum.OP_put_global, cidx)
            return true
        } finally {
            ctx.gcKeepAlive.removeAt(ctx.gcKeepAlive.size - 1)
        }
    }
    
    /* 内层引用外层局部变量：分配捕获槽并回改外层已发射的 loc 指令（对照 C
     * add_ext_var + varref 提升）。返回池槽索引；未找到可捕获的外层变量返回 -1 */
    private fun tryCaptureOuterVar(nameStr: String): Int {
        /* 从栈顶（当前内层）向栈底（顶层）查找声明该变量的外层层 */
        for (i in compileScopes.indices.reversed().drop(1)) {
            val scope = compileScopes[i]
            
            val existing = scope.varrefSlots[nameStr]
            if (existing != null) return existing
            
            /* JS_NewString 可能触发 GC 搬移：作用域内函数/字节码地址在分配后
             * 从保活槽重新读取 */
            val name = JS_NewString(ctx, nameStr)
            if (JS_IsException(name)) return -1
            val localIdx = findLocalVarIn(scopeCurFunc(scope), scope.localVarsLen, name)
            if (localIdx >= 0) {
                val slot = ctx.varrefPool.size.also { ctx.varrefPool.add(JS_UNDEFINED) }
                scope.varrefSlots[nameStr] = slot
                val rewritten = rewriteLocToVarRef(scopeByteCode(scope), scope.byteCodeLen, localIdx, slot)
                /* 外层尚无该变量的读写指令（参数或稍后声明）：闭包创建时需把
                 * 当前帧中的值复制进捕获槽；已有指令时槽位由外层代码维护，
                 * 复制反而会用未写入的局部槽值覆盖正确值 */
                if (rewritten == 0) {
                    compileScopes.last().captures.add(nameStr to slot)
                }
                return slot
            }
        }
        return -1
    }
    
    /* 扫描函数字节码，将读写指定局部变量的 get_loc/put_loc 改写为
     * get_var_ref/put_var_ref（操作数换为 varref 池槽；指令同为 3 字节）。
     * 字节码与长度来自编译上下文快照——编译中的函数对象 +16 槽尚未挂载字节码 */
    private fun rewriteLocToVarRef(byteCode: JSValue, codeLen: Int, localIdx: Int, slot: Int): Int {
        if (byteCode == JS_NULL || codeLen <= 0) return 0
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
        if (ctx.traceEnabled) {
            println("DBG rewrite len=$len localIdx=$localIdx slot=$slot rewritten=$rewritten")
        }
        return rewritten
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

