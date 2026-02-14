package app.muka.project.kquickjs.parser

import app.muka.project.kquickjs.*

enum class JSTokenVal(val value: Int) {
    TOK_EOF(0),
    TOK_NUMBER(128),
    TOK_STRING(129),
    TOK_IDENT(130),
    TOK_REGEXP(131),
    TOK_MUL_ASSIGN(132),
    TOK_DIV_ASSIGN(133),
    TOK_MOD_ASSIGN(134),
    TOK_PLUS_ASSIGN(135),
    TOK_MINUS_ASSIGN(136),
    TOK_SHL_ASSIGN(137),
    TOK_SAR_ASSIGN(138),
    TOK_SHR_ASSIGN(139),
    TOK_AND_ASSIGN(140),
    TOK_XOR_ASSIGN(141),
    TOK_OR_ASSIGN(142),
    TOK_POW_ASSIGN(143),
    TOK_DEC(144),
    TOK_INC(145),
    TOK_SHL(146),
    TOK_SAR(147),
    TOK_SHR(148),
    TOK_LT(149),
    TOK_LTE(150),
    TOK_GT(151),
    TOK_GTE(152),
    TOK_EQ(153),
    TOK_STRICT_EQ(154),
    TOK_NEQ(155),
    TOK_STRICT_NEQ(156),
    TOK_LAND(157),
    TOK_LOR(158),
    TOK_POW(159),
    
    TOK_FIRST_KEYWORD(160),
    TOK_NULL(161),
    TOK_FALSE(162),
    TOK_TRUE(163),
    TOK_IF(164),
    TOK_ELSE(165),
    TOK_RETURN(166),
    TOK_VAR(167),
    TOK_THIS(168),
    TOK_DELETE(169),
    TOK_VOID(170),
    TOK_TYPEOF(171),
    TOK_NEW(172),
    TOK_IN(173),
    TOK_INSTANCEOF(174),
    TOK_DO(175),
    TOK_WHILE(176),
    TOK_FOR(177),
    TOK_BREAK(178),
    TOK_CONTINUE(179),
    TOK_SWITCH(180),
    TOK_CASE(181),
    TOK_DEFAULT(182),
    TOK_THROW(183),
    TOK_TRY(184),
    TOK_CATCH(185),
    TOK_FINALLY(186),
    TOK_FUNCTION(187),
    TOK_DEBUGGER(188),
    TOK_WITH(189),
    TOK_CLASS(190),
    TOK_CONST(191),
    TOK_ENUM(192),
    TOK_EXPORT(193),
    TOK_EXTENDS(194),
    TOK_IMPORT(195),
    TOK_SUPER(196),
    TOK_IMPLEMENTS(197),
    TOK_INTERFACE(198),
    TOK_LET(199),
    TOK_PACKAGE(200),
    TOK_PRIVATE(201),
    TOK_PROTECTED(202),
    TOK_PUBLIC(203),
    TOK_STATIC(204),
    TOK_YIELD(205);
    
    companion object {
        fun fromValue(value: Int): JSTokenVal? = entries.find { it.value == value }
    }
}

data class JSToken(
    var val1: Int = 0,
    var sourcePos: Int = 0,
    var d: Double = 0.0,
    var reFlags: Int = 0,
    var reEndPos: Int = 0,
    var value: JSValue = JS_NULL
)

class JSParseState(
    val ctx: JSContext,
    val sourceBuf: ByteArray,
    val filename: String,
    val evalFlags: Int
) {
    var token = JSToken()
    var gotLf = false
    var isEval = (evalFlags and JS_EVAL_REPL) != 0
    var hasRetval = (evalFlags and JS_EVAL_RETVAL) != 0
    var isRepl = (evalFlags and JS_EVAL_REPL) != 0
    var hasColumn = (evalFlags and JS_EVAL_STRIP_COL) == 0
    var droppedResult = false
    
    var bufPos = 0
    val bufLen = sourceBuf.size
    
    var curFunc: JSValue = JS_NULL
    var byteCode: JSValue = JS_NULL
    var byteCodeLen = 0
    var lastOpcodePos = -1
    
    var cpoolLen = 0
    var hoistedCodeLen = 0
    var localVarsLen = 0
    var evalRetIdx = -1
    var topBreak: JSValue = JS_NULL
    
    var lineNum = 1
    var colNum = 1
    
    var errorMsg: String? = null
    var hasError = false
    
    var sourceStr: JSValue = JS_NULL
    var filenameStr: JSValue = JS_NULL
    var tokenValue: JSValue = JS_NULL
    
    val keywords = mapOf(
        "null" to JSTokenVal.TOK_NULL,
        "false" to JSTokenVal.TOK_FALSE,
        "true" to JSTokenVal.TOK_TRUE,
        "if" to JSTokenVal.TOK_IF,
        "else" to JSTokenVal.TOK_ELSE,
        "return" to JSTokenVal.TOK_RETURN,
        "var" to JSTokenVal.TOK_VAR,
        "this" to JSTokenVal.TOK_THIS,
        "delete" to JSTokenVal.TOK_DELETE,
        "void" to JSTokenVal.TOK_VOID,
        "typeof" to JSTokenVal.TOK_TYPEOF,
        "new" to JSTokenVal.TOK_NEW,
        "in" to JSTokenVal.TOK_IN,
        "instanceof" to JSTokenVal.TOK_INSTANCEOF,
        "do" to JSTokenVal.TOK_DO,
        "while" to JSTokenVal.TOK_WHILE,
        "for" to JSTokenVal.TOK_FOR,
        "break" to JSTokenVal.TOK_BREAK,
        "continue" to JSTokenVal.TOK_CONTINUE,
        "switch" to JSTokenVal.TOK_SWITCH,
        "case" to JSTokenVal.TOK_CASE,
        "default" to JSTokenVal.TOK_DEFAULT,
        "throw" to JSTokenVal.TOK_THROW,
        "try" to JSTokenVal.TOK_TRY,
        "catch" to JSTokenVal.TOK_CATCH,
        "finally" to JSTokenVal.TOK_FINALLY,
        "function" to JSTokenVal.TOK_FUNCTION,
        "debugger" to JSTokenVal.TOK_DEBUGGER,
        "with" to JSTokenVal.TOK_WITH,
        "class" to JSTokenVal.TOK_CLASS,
        "const" to JSTokenVal.TOK_CONST,
        "enum" to JSTokenVal.TOK_ENUM,
        "export" to JSTokenVal.TOK_EXPORT,
        "extends" to JSTokenVal.TOK_EXTENDS,
        "import" to JSTokenVal.TOK_IMPORT,
        "super" to JSTokenVal.TOK_SUPER,
        "implements" to JSTokenVal.TOK_IMPLEMENTS,
        "interface" to JSTokenVal.TOK_INTERFACE,
        "let" to JSTokenVal.TOK_LET,
        "package" to JSTokenVal.TOK_PACKAGE,
        "private" to JSTokenVal.TOK_PRIVATE,
        "protected" to JSTokenVal.TOK_PROTECTED,
        "public" to JSTokenVal.TOK_PUBLIC,
        "static" to JSTokenVal.TOK_STATIC,
        "yield" to JSTokenVal.TOK_YIELD
    )
    
    fun peekChar(offset: Int = 0): Int {
        val pos = bufPos + offset
        return if (pos < bufLen) sourceBuf[pos].toInt() and 0xFF else 0
    }
    
    fun advance(): Int {
        val c = peekChar()
        bufPos++
        if (c == '\n'.code) {
            lineNum++
            colNum = 1
        } else {
            colNum++
        }
        return c
    }
    
    fun skipWhitespace() {
        while (bufPos < bufLen) {
            when (val c = peekChar()) {
                ' '.code, '\t'.code, 12, 11, '\r'.code -> advance()
                '\n'.code -> {
                    gotLf = true
                    advance()
                }
                else -> break
            }
        }
    }
    
    fun skipComment(): Boolean {
        if (peekChar() == '/'.code) {
            when (peekChar(1)) {
                '*'.code -> {
                    advance()
                    advance()
                    while (bufPos < bufLen) {
                        if (peekChar() == '*'.code && peekChar(1) == '/'.code) {
                            advance()
                            advance()
                            return true
                        }
                        advance()
                    }
                    error("unexpected end of comment")
                    return false
                }
                '/'.code -> {
                    advance()
                    advance()
                    while (bufPos < bufLen && peekChar() != '\n'.code) {
                        advance()
                    }
                    return true
                }
            }
        }
        return true
    }
    
    fun nextToken() {
        while (true) {
            skipWhitespace()
            if (!skipComment()) return
            
            token.sourcePos = bufPos
            token.value = JS_NULL
            
            val c = peekChar()
            
            when (c) {
                0 -> {
                    token.val1 = JSTokenVal.TOK_EOF.value
                    return
                }
                '"'.code, '\''.code -> {
                    advance()
                    parseString(c)
                    return
                }
                in 'a'.code..'z'.code, in 'A'.code..'Z'.code, '_'.code, '$'.code -> {
                    parseIdent()
                    return
                }
                in '0'.code..'9'.code -> {
                    parseNumber()
                    return
                }
                '.'.code -> {
                    if (isDigit(peekChar(1))) {
                        parseNumber()
                        return
                    }
                    advance()
                    token.val1 = '.'.code
                    return
                }
                '*'.code -> {
                    advance()
                    when (peekChar()) {
                        '='.code -> {
                            advance()
                            token.val1 = JSTokenVal.TOK_MUL_ASSIGN.value
                        }
                        '*'.code -> {
                            advance()
                            if (peekChar() == '='.code) {
                                advance()
                                token.val1 = JSTokenVal.TOK_POW_ASSIGN.value
                            } else {
                                token.val1 = JSTokenVal.TOK_POW.value
                            }
                        }
                        else -> token.val1 = '*'.code
                    }
                    return
                }
                '%'.code -> {
                    advance()
                    if (peekChar() == '='.code) {
                        advance()
                        token.val1 = JSTokenVal.TOK_MOD_ASSIGN.value
                    } else {
                        token.val1 = '%'.code
                    }
                    return
                }
                '+'.code -> {
                    advance()
                    when (peekChar()) {
                        '='.code -> {
                            advance()
                            token.val1 = JSTokenVal.TOK_PLUS_ASSIGN.value
                        }
                        '+'.code -> {
                            advance()
                            token.val1 = JSTokenVal.TOK_INC.value
                        }
                        else -> token.val1 = '+'.code
                    }
                    return
                }
                '-'.code -> {
                    advance()
                    when (peekChar()) {
                        '='.code -> {
                            advance()
                            token.val1 = JSTokenVal.TOK_MINUS_ASSIGN.value
                        }
                        '-'.code -> {
                            advance()
                            token.val1 = JSTokenVal.TOK_DEC.value
                        }
                        else -> token.val1 = '-'.code
                    }
                    return
                }
                '<'.code -> {
                    advance()
                    when (peekChar()) {
                        '='.code -> {
                            advance()
                            token.val1 = JSTokenVal.TOK_LTE.value
                        }
                        '<'.code -> {
                            advance()
                            if (peekChar() == '='.code) {
                                advance()
                                token.val1 = JSTokenVal.TOK_SHL_ASSIGN.value
                            } else {
                                token.val1 = JSTokenVal.TOK_SHL.value
                            }
                        }
                        else -> token.val1 = '<'.code
                    }
                    return
                }
                '>'.code -> {
                    advance()
                    when (peekChar()) {
                        '='.code -> {
                            advance()
                            token.val1 = JSTokenVal.TOK_GTE.value
                        }
                        '>'.code -> {
                            advance()
                            when (peekChar()) {
                                '>'.code -> {
                                    advance()
                                    if (peekChar() == '='.code) {
                                        advance()
                                        token.val1 = JSTokenVal.TOK_SHR_ASSIGN.value
                                    } else {
                                        token.val1 = JSTokenVal.TOK_SHR.value
                                    }
                                }
                                '='.code -> {
                                    advance()
                                    token.val1 = JSTokenVal.TOK_SAR_ASSIGN.value
                                }
                                else -> token.val1 = JSTokenVal.TOK_SAR.value
                            }
                        }
                        else -> token.val1 = '>'.code
                    }
                    return
                }
                '='.code -> {
                    advance()
                    if (peekChar() == '='.code) {
                        advance()
                        if (peekChar() == '='.code) {
                            advance()
                            token.val1 = JSTokenVal.TOK_STRICT_EQ.value
                        } else {
                            token.val1 = JSTokenVal.TOK_EQ.value
                        }
                    } else {
                        token.val1 = '='.code
                    }
                    return
                }
                '!'.code -> {
                    advance()
                    if (peekChar() == '='.code) {
                        advance()
                        if (peekChar() == '='.code) {
                            advance()
                            token.val1 = JSTokenVal.TOK_STRICT_NEQ.value
                        } else {
                            token.val1 = JSTokenVal.TOK_NEQ.value
                        }
                    } else {
                        token.val1 = '!'.code
                    }
                    return
                }
                '&'.code -> {
                    advance()
                    when (peekChar()) {
                        '='.code -> {
                            advance()
                            token.val1 = JSTokenVal.TOK_AND_ASSIGN.value
                        }
                        '&'.code -> {
                            advance()
                            token.val1 = JSTokenVal.TOK_LAND.value
                        }
                        else -> token.val1 = '&'.code
                    }
                    return
                }
                '^'.code -> {
                    advance()
                    if (peekChar() == '='.code) {
                        advance()
                        token.val1 = JSTokenVal.TOK_XOR_ASSIGN.value
                    } else {
                        token.val1 = '^'.code
                    }
                    return
                }
                '|'.code -> {
                    advance()
                    when (peekChar()) {
                        '='.code -> {
                            advance()
                            token.val1 = JSTokenVal.TOK_OR_ASSIGN.value
                        }
                        '|'.code -> {
                            advance()
                            token.val1 = JSTokenVal.TOK_LOR.value
                        }
                        else -> token.val1 = '|'.code
                    }
                    return
                }
                '/'.code -> {
                    advance()
                    if (peekChar() == '='.code) {
                        advance()
                        token.val1 = JSTokenVal.TOK_DIV_ASSIGN.value
                    } else {
                        token.val1 = '/'.code
                    }
                    return
                }
                '('.code, ')'.code, '{'.code, '}'.code, '['.code, ']'.code,
                ';'.code, ','.code, ':'.code, '?'.code, '~'.code -> {
                    advance()
                    token.val1 = c
                    return
                }
                else -> {
                    if (c >= 128) {
                        error("unexpected character")
                        return
                    }
                    advance()
                    token.val1 = c
                    return
                }
            }
        }
    }
    
    fun parseString(quote: Int) {
        val sb = StringBuilder()
        while (bufPos < bufLen) {
            val c = peekChar()
            if (c == quote) {
                advance()
                token.val1 = JSTokenVal.TOK_STRING.value
                token.value = JS_NewString(ctx, sb.toString())
                return
            }
            if (c == '\\'.code) {
                advance()
                val escape = parseEscapeSequence()
                if (escape >= 0) {
                    if (escape < 0x10000) {
                        sb.append(escape.toChar())
                    } else {
                        val hi = ((escape - 0x10000) shr 10) + 0xD800
                        val lo = ((escape - 0x10000) and 0x3FF) + 0xDC00
                        sb.append(hi.toChar())
                        sb.append(lo.toChar())
                    }
                }
            } else {
                advance()
                if (c < 128) {
                    sb.append(c.toChar())
                } else {
                    val bytes = ByteArray(4)
                    bytes[0] = c.toByte()
                    var len = 1
                    while (len < 4 && (peekChar() and 0xC0) == 0x80) {
                        bytes[len++] = advance().toByte()
                    }
                    sb.append(String(bytes, 0, len, Charsets.UTF_8))
                }
            }
        }
        error("unterminated string")
    }
    
    fun parseEscapeSequence(): Int {
        val c = peekChar()
        advance()
        return when (c) {
            'n'.code -> '\n'.code
            't'.code -> '\t'.code
            'r'.code -> '\r'.code
            'b'.code -> '\b'.code
            'f'.code -> 12
            'v'.code -> 11
            '\\'.code -> '\\'.code
            '\''.code -> '\''.code
            '"'.code -> '"'.code
            '0'.code -> 0
            'x'.code -> {
                val h1 = fromHex(advance())
                val h2 = fromHex(advance())
                if (h1 < 0 || h2 < 0) {
                    error("invalid hex escape")
                    return -1
                }
                (h1 shl 4) or h2
            }
            'u'.code -> {
                if (peekChar() == '{'.code) {
                    advance()
                    var code = 0
                    while (bufPos < bufLen && peekChar() != '}'.code) {
                        val h = fromHex(advance())
                        if (h < 0) {
                            error("invalid unicode escape")
                            return -1
                        }
                        code = (code shl 4) or h
                    }
                    if (peekChar() == '}'.code) advance()
                    code
                } else {
                    val h1 = fromHex(advance())
                    val h2 = fromHex(advance())
                    val h3 = fromHex(advance())
                    val h4 = fromHex(advance())
                    if (h1 < 0 || h2 < 0 || h3 < 0 || h4 < 0) {
                        error("invalid unicode escape")
                        return -1
                    }
                    (h1 shl 12) or (h2 shl 8) or (h3 shl 4) or h4
                }
            }
            '\n'.code -> -1
            '\r'.code -> {
                if (peekChar() == '\n'.code) advance()
                -1
            }
            else -> c
        }
    }
    
    fun parseIdent() {
        val start = bufPos
        while (bufPos < bufLen) {
            val c = peekChar()
            if (c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code ||
                c in '0'.code..'9'.code || c == '_'.code || c == '$'.code) {
                advance()
            } else {
                break
            }
        }
        val ident = String(sourceBuf, start, bufPos - start, Charsets.UTF_8)
        
        val keyword = keywords[ident]
        if (keyword != null) {
            token.val1 = keyword.value
            token.value = JS_NewString(ctx, ident)
        } else {
            token.val1 = JSTokenVal.TOK_IDENT.value
            token.value = JS_NewString(ctx, ident)
        }
    }
    
    fun parseNumber() {
        val start = bufPos
        var c = peekChar()
        
        if (c == '0'.code) {
            advance()
            when (peekChar()) {
                'x'.code, 'X'.code -> {
                    advance()
                    var value = 0L
                    while (bufPos < bufLen) {
                        val h = fromHex(peekChar())
                        if (h < 0) break
                        advance()
                        value = (value shl 4) or h.toLong()
                    }
                    token.val1 = JSTokenVal.TOK_NUMBER.value
                    token.d = value.toDouble()
                    return
                }
                'b'.code, 'B'.code -> {
                    advance()
                    var value = 0L
                    while (bufPos < bufLen) {
                        val b = peekChar()
                        if (b == '0'.code || b == '1'.code) {
                            advance()
                            value = (value shl 1) or (b - '0'.code).toLong()
                        } else {
                            break
                        }
                    }
                    token.val1 = JSTokenVal.TOK_NUMBER.value
                    token.d = value.toDouble()
                    return
                }
                'o'.code, 'O'.code -> {
                    advance()
                    var value = 0L
                    while (bufPos < bufLen) {
                        val o = peekChar()
                        if (o in '0'.code..'7'.code) {
                            advance()
                            value = (value shl 3) or (o - '0'.code).toLong()
                        } else {
                            break
                        }
                    }
                    token.val1 = JSTokenVal.TOK_NUMBER.value
                    token.d = value.toDouble()
                    return
                }
            }
        }
        
        while (bufPos < bufLen && isDigit(peekChar())) {
            advance()
        }
        
        if (peekChar() == '.'.code) {
            advance()
            while (bufPos < bufLen && isDigit(peekChar())) {
                advance()
            }
        }
        
        if (peekChar() == 'e'.code || peekChar() == 'E'.code) {
            advance()
            if (peekChar() == '+'.code || peekChar() == '-'.code) {
                advance()
            }
            while (bufPos < bufLen && isDigit(peekChar())) {
                advance()
            }
        }
        
        val numStr = String(sourceBuf, start, bufPos - start, Charsets.UTF_8)
        token.val1 = JSTokenVal.TOK_NUMBER.value
        token.d = numStr.toDouble()
    }
    
    fun isDigit(c: Int): Boolean = c in '0'.code..'9'.code
    
    fun fromHex(c: Int): Int {
        return when (c) {
            in '0'.code..'9'.code -> c - '0'.code
            in 'a'.code..'f'.code -> c - 'a'.code + 10
            in 'A'.code..'F'.code -> c - 'A'.code + 10
            else -> -1
        }
    }
    
    fun error(msg: String) {
        errorMsg = "$filename:$lineNum:$colNum: $msg"
        hasError = true
    }
}
