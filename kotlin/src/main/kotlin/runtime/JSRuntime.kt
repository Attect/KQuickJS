package app.muka.project.kquickjs.runtime

import app.muka.project.kquickjs.*
import app.muka.project.kquickjs.JSMTags
import app.muka.project.kquickjs.memory.getMTag
import app.muka.project.kquickjs.OPCodeEnum

class JSRuntime(private val ctx: JSContext) {
    
    /* 顶层入口：this 默认为全局对象（对照 C JS_Eval 的顶层帧） */
    fun run(func: JSValue): JSValue = run(func, ctx.globalObj)
    
    fun run(func: JSValue, thisObj: JSValue): JSValue = run(func, thisObj, emptyList())
    
    fun run(func: JSValue, thisObj: JSValue, args: List<JSValue>, closure: JSValue = JS_UNDEFINED): JSValue {
        /* 维护 JS 调用栈记录（Kotlin 无 C 的 fp 帧链，用于生成错误对象的 stack 回溯） */
        ctx.jsCallStack.addLast(readFuncName(func))
        try {
            return runBody(func, thisObj, args, closure)
        } finally {
            ctx.jsCallStack.removeLast()
        }
    }
    
    /* 读取函数字节码的函数名（FUNCTION_BYTECODE 布局：funcName 在 ptr+8） */
    private fun readFuncName(func: JSValue): String {
        val funcPtr = JS_VALUE_TO_PTR(func)
        val name = ctx.memory.getJSValue(funcPtr + 8)
        return if (JS_IsString(ctx, name)) jsGetString(ctx, name) else "<anonymous>"
    }
    
    private fun runBody(func: JSValue, thisObj: JSValue, args: List<JSValue>,
                        closure: JSValue = JS_UNDEFINED): JSValue {
        /* func/thisObj/closure/args 加入 GC 保活列表：函数体执行期间的分配可能触发
         * GC 压缩搬移，Kotlin 局部值会过期——执行中通过保活列表读取最新地址。
         * 帧布局：keepStart=func, +1=thisObj, +2=closure, +3..=args */
        val keepStart = ctx.gcKeepAlive.size
        ctx.gcKeepAlive.add(func)
        ctx.gcKeepAlive.add(thisObj)
        ctx.gcKeepAlive.add(closure)
        for (a in args) ctx.gcKeepAlive.add(a)
        
        var funcKept = func
        var thisObjKept = thisObj
        
        val funcPtr = JS_VALUE_TO_PTR(funcKept)
        if (ctx.memory.getMTag(funcPtr) != JSMTags.JS_MTAG_FUNCTION_BYTECODE) {
            while (ctx.gcKeepAlive.size > keepStart) ctx.gcKeepAlive.removeAt(ctx.gcKeepAlive.size - 1)
            return JS_EXCEPTION
        }
        
        val byteCode = ctx.memory.getJSValue(funcPtr + 16)
        if (byteCode == JS_NULL) {
            while (ctx.gcKeepAlive.size > keepStart) ctx.gcKeepAlive.removeAt(ctx.gcKeepAlive.size - 1)
            return JS_UNDEFINED
        }
        
        val byteCodePtr = JS_VALUE_TO_PTR(byteCode)
        val byteCodeLen = ctx.memory.getI32(byteCodePtr + 4)
        val code = ctx.memory.getBytes(byteCodePtr + 8, byteCodeLen)
        
        val varsObj = ctx.memory.getJSValue(funcPtr + 32)
        val varsSize = if (varsObj != JS_NULL) {
            val varsPtr = JS_VALUE_TO_PTR(varsObj)
            ctx.memory.getI32(varsPtr + 4)
        } else 0
        
        var pc = 0
        val stack = mutableListOf<JSValue>()
        val vars = MutableList(varsSize) { JS_UNDEFINED }
        /* 操作数栈与局部变量表是 JVM 集合，GC 无法自动发现其中的值：
         * 注册为根，压缩搬移时由 JSGC 就地改写（否则执行期触发 GC 后
         * 集合里会残留悬垂地址，表现为 "not a function"、越界访问等） */
        val rootBase = ctx.jvmRoots.size
        ctx.jvmRoots.add(stack)
        ctx.jvmRoots.add(vars)
        /* 函数参数填入 vars 前段（bytecode 头 u16 的高位存 argCount） */
        val argCount = ctx.memory.getU16(funcPtr) shr 4
        for (i in 0 until minOf(args.size, argCount, varsSize)) {
            vars[i] = args[i]
        }
        
        try {
        while (pc < code.size) {
            
            /* GC 可能在上一条指令的分配中触发并搬移对象：从保活列表刷新
             * func 的最新地址（funcPtr 随之重算）；thisObj 由 OP_push_this
             * 从保活列表读取 */
            funcKept = ctx.gcKeepAlive[keepStart]
            thisObjKept = ctx.gcKeepAlive[keepStart + 1]
            fun funcPtrNow(): Int = JS_VALUE_TO_PTR(ctx.gcKeepAlive[keepStart])
            
            val op = code[pc].toInt() and 0xFF
            if (System.getenv("MQJS_TRACE") != null) {
                val e = OPCodeEnum.entries.find { it.id == op }
                val stackDump = stack.joinToString(",") { v ->
                    when {
                        JS_IsInt(v) -> JS_VALUE_GET_INT(v).toString()
                        JS_IsUndefined(v) -> "undef"
                        JS_IsNull(v) -> "null"
                        JS_IsPtr(v) -> "p${JS_VALUE_TO_PTR(v)}"
                        else -> "v$v"
                    }
                }
                println("TRACE pc=$pc ${e?.name ?: "?"} [$stackDump] vars=${vars.joinToString(",") { v ->
                    when {
                        JS_IsInt(v) -> JS_VALUE_GET_INT(v).toString()
                        JS_IsUndefined(v) -> "undef"
                        else -> "v$v"
                    }
                }}")
            }
            pc++
            
            when (op) {
                /* 闭包捕获变量读写（对照 C OP_get_var_ref/OP_put_var_ref：
                 * 槽为 UNINITIALIZED 时抛 ReferenceError） */
                OPCodeEnum.OP_get_var_ref.id -> {
                    val idx = readU16(code, pc)
                    pc += 2
                    if (idx < 0 || idx >= ctx.varrefPool.size) {
                        return JS_ThrowReferenceError(ctx, "variable is not defined")
                    }
                    val v = ctx.varrefPool[idx]
                    if (JS_IsUninitialized(v)) {
                        return JS_ThrowReferenceError(ctx, "variable is not defined")
                    }
                    stack.add(v)
                }
                
                OPCodeEnum.OP_put_var_ref.id -> {
                    val idx = readU16(code, pc)
                    pc += 2
                    val v = stack.removeLast()
                    while (ctx.varrefPool.size <= idx) {
                        ctx.varrefPool.add(JS_UNDEFINED)
                    }
                    ctx.varrefPool[idx] = v
                }
                
                /* 全局标识符读写（对照 C GLOBAL varref 经 globalObj 属性解析；
                 * 读取时属性不存在抛 ReferenceError） */
                OPCodeEnum.OP_get_global.id -> {
                    val idx = readU16(code, pc)
                    pc += 2
                    val cpool = ctx.memory.getJSValue(funcPtrNow() + 24)
                    val name = if (cpool != JS_NULL) {
                        ctx.memory.getJSValue(JS_VALUE_TO_PTR(cpool) + 8 + idx * 8)
                    } else JS_UNDEFINED
                    val v = getProperty(ctx.globalObj, name)
                    if (JS_IsUndefined(v) && !hasProperty(ctx.globalObj, name)) {
                        return JS_ThrowReferenceError(ctx, "'${jsGetString(ctx, name)}' is not defined")
                    }
                    stack.add(v)
                }
                
                OPCodeEnum.OP_get_global_undef.id -> {
                    /* typeof 操作数：不存在时返回 undefined，不抛异常 */
                    val idx = readU16(code, pc)
                    pc += 2
                    val cpool = ctx.memory.getJSValue(funcPtrNow() + 24)
                    val name = if (cpool != JS_NULL) {
                        ctx.memory.getJSValue(JS_VALUE_TO_PTR(cpool) + 8 + idx * 8)
                    } else JS_UNDEFINED
                    stack.add(getProperty(ctx.globalObj, name))
                }
                
                OPCodeEnum.OP_put_global.id -> {
                    val idx = readU16(code, pc)
                    pc += 2
                    val v = stack.removeLast()
                    val cpool = ctx.memory.getJSValue(funcPtrNow() + 24)
                    val name = if (cpool != JS_NULL) {
                        ctx.memory.getJSValue(JS_VALUE_TO_PTR(cpool) + 8 + idx * 8)
                    } else JS_UNDEFINED
                    setProperty(ctx.globalObj, name, v)
                }
                OPCodeEnum.OP_undefined.id -> stack.add(JS_UNDEFINED)
                OPCodeEnum.OP_null.id -> stack.add(JS_NULL)
                OPCodeEnum.OP_push_false.id -> stack.add(JS_FALSE)
                OPCodeEnum.OP_push_true.id -> stack.add(JS_TRUE)
                /* 压入当前函数帧的 this（对照 C fp[FRAME_OFFSET_THIS_OBJ]）；
                   从保活列表读取以获取 GC 搬移后的最新地址 */
                OPCodeEnum.OP_push_this.id -> stack.add(ctx.gcKeepAlive[keepStart + 1])
                /* 当前闭包对象（具名函数表达式在其内部引用自身） */
                OPCodeEnum.OP_this_func.id -> stack.add(ctx.gcKeepAlive[keepStart + 2])
                
                OPCodeEnum.OP_push_0.id -> stack.add(JS_NewShortInt(0))
                OPCodeEnum.OP_push_1.id -> stack.add(JS_NewShortInt(1))
                OPCodeEnum.OP_push_2.id -> stack.add(JS_NewShortInt(2))
                OPCodeEnum.OP_push_3.id -> stack.add(JS_NewShortInt(3))
                OPCodeEnum.OP_push_4.id -> stack.add(JS_NewShortInt(4))
                OPCodeEnum.OP_push_5.id -> stack.add(JS_NewShortInt(5))
                OPCodeEnum.OP_push_6.id -> stack.add(JS_NewShortInt(6))
                OPCodeEnum.OP_push_7.id -> stack.add(JS_NewShortInt(7))
                OPCodeEnum.OP_push_minus1.id -> stack.add(JS_NewShortInt(-1))
                
                OPCodeEnum.OP_push_i8.id -> {
                    val v = code[pc].toByte().toInt()
                    pc++
                    stack.add(JS_NewShortInt(v))
                }
                
                OPCodeEnum.OP_push_i16.id -> {
                    val v = ((code[pc].toInt() and 0xFF) or ((code[pc + 1].toInt() and 0xFF) shl 8)).toShort().toInt()
                    pc += 2
                    stack.add(JS_NewShortInt(v))
                }
                
                OPCodeEnum.OP_push_const.id -> {
                    val idx = (code[pc].toInt() and 0xFF) or ((code[pc + 1].toInt() and 0xFF) shl 8)
                    pc += 2
                    val cpool = ctx.memory.getJSValue(funcPtrNow() + 24)
                    if (cpool != JS_NULL) {
                        val cpoolPtr = JS_VALUE_TO_PTR(cpool)
                        stack.add(ctx.memory.getJSValue(cpoolPtr + 8 + idx * 8))
                    } else {
                        stack.add(JS_UNDEFINED)
                    }
                }
                
                OPCodeEnum.OP_fclosure.id -> {
                    /* 从 cpool 的函数字节码创建 JS_CLASS_CLOSURE 对象（extra 槽 0 = funcBytecode） */
                    val idx = readU16(code, pc)
                    pc += 2
                    val cpool = ctx.memory.getJSValue(funcPtrNow() + 24)
                    val funcBytecode = if (cpool != JS_NULL) {
                        ctx.memory.getJSValue(JS_VALUE_TO_PTR(cpool) + 8 + idx * 8)
                    } else JS_NULL
                    
                    /* 分配会触发 GC：函数字节码入保活槽，之后从槽位读取 */
                    ctx.gcKeepAlive.add(funcBytecode)
                    try {
                        val closure = JS_NewObjectProtoClass(ctx, ctx.classProto[JSObjectClassEnum.JS_CLASS_CLOSURE.value],
                                                             JSObjectClassEnum.JS_CLASS_CLOSURE.value, 1)
                        if (JS_IsException(closure)) return JS_EXCEPTION
                        val closurePtr = JS_VALUE_TO_PTR(closure)
                        ctx.memory.putJSValue(closurePtr + 24, ctx.gcKeepAlive[ctx.gcKeepAlive.size - 1])
                        stack.add(closure)
                        
                        /* 捕获变量初始化：把当前帧同名实参/局部的当前值写入 varref 池槽 */
                        val fb = ctx.gcKeepAlive[ctx.gcKeepAlive.size - 1]
                        if (JS_IsPtr(fb)) {
                            val capArr = ctx.memory.getJSValue(JS_VALUE_TO_PTR(fb) + 64)
                            if (capArr != JS_NULL && !JS_IsException(capArr)) {
                                val capPtr = JS_VALUE_TO_PTR(capArr)
                                val capCount = ctx.memory.getI32(capPtr + 4) / 2
                                val curVarsTab = ctx.memory.getJSValue(funcPtrNow() + 32)
                                if (curVarsTab != JS_NULL) {
                                    val tabPtr = JS_VALUE_TO_PTR(curVarsTab)
                                    val tabLen = ctx.memory.getI32(tabPtr + 4)
                                    for (ci in 0 until capCount) {
                                        val name = ctx.memory.getJSValue(capPtr + 8 + 2 * ci * 8)
                                        val slot = JS_VALUE_GET_INT(ctx.memory.getJSValue(capPtr + 16 + 2 * ci * 8))
                                        for (j in 0 until minOf(tabLen, vars.size)) {
                                            val vn = ctx.memory.getJSValue(tabPtr + 8 + j * 8)
                                            if (vn == name || jsValueEquals(vn, name)) {
                                                while (ctx.varrefPool.size <= slot) {
                                                    ctx.varrefPool.add(JS_UNDEFINED)
                                                }
                                                ctx.varrefPool[slot] = vars[j]
                                                break
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        
                        /* 函数对象自动获得 prototype（constructor 指回闭包），
                         * 供 new F()/instanceof/F.prototype.constructor 使用 */
                        val fbPtr = JS_VALUE_TO_PTR(ctx.gcKeepAlive[ctx.gcKeepAlive.size - 1])
                        val fname = ctx.memory.getJSValue(fbPtr + 8)
                        if (JS_IsString(ctx, fname)) {
                            setProperty(stack.last(), JS_NewString(ctx, "name"), fname)
                        }
                        val protoObj = JS_NewObject(ctx)
                        if (!JS_IsException(protoObj)) {
                            ctx.gcKeepAlive.add(protoObj)
                            try {
                                /* 闭包在解释器栈顶（GC 根），protoObj 在保活槽 */
                                val keyCtor = JS_NewString(ctx, "constructor")
                                setProperty(ctx.gcKeepAlive[ctx.gcKeepAlive.size - 1], keyCtor, stack.last())
                                val keyProto = JS_NewString(ctx, "prototype")
                                setProperty(stack.last(), keyProto, ctx.gcKeepAlive[ctx.gcKeepAlive.size - 1])
                            } finally {
                                ctx.gcKeepAlive.removeAt(ctx.gcKeepAlive.size - 1)
                            }
                        }
                    } finally {
                        ctx.gcKeepAlive.removeAt(ctx.gcKeepAlive.size - 1)
                    }
                }
                
                OPCodeEnum.OP_drop.id -> stack.removeLast()
                OPCodeEnum.OP_dup.id -> stack.add(stack.last())
                OPCodeEnum.OP_swap.id -> {
                    val a = stack.removeLast()
                    val b = stack.removeLast()
                    stack.add(a)
                    stack.add(b)
                }
                OPCodeEnum.OP_nip.id -> {
                    val a = stack.removeLast()
                    stack.removeLast()
                    stack.add(a)
                }
                
                OPCodeEnum.OP_add.id -> {
                    val b = stack.removeLast()
                    val a = stack.removeLast()
                    stack.add(doAdd(a, b))
                }
                
                OPCodeEnum.OP_sub.id -> {
                    val b = stack.removeLast()
                    val a = stack.removeLast()
                    stack.add(doSub(a, b))
                }
                
                OPCodeEnum.OP_mul.id -> {
                    val b = stack.removeLast()
                    val a = stack.removeLast()
                    stack.add(doMul(a, b))
                }
                
                OPCodeEnum.OP_div.id -> {
                    val b = stack.removeLast()
                    val a = stack.removeLast()
                    stack.add(doDiv(a, b))
                }
                
                OPCodeEnum.OP_mod.id -> {
                    val b = stack.removeLast()
                    val a = stack.removeLast()
                    stack.add(doMod(a, b))
                }
                
                OPCodeEnum.OP_neg.id -> {
                    val a = stack.removeLast()
                    stack.add(doNeg(a))
                }
                
                OPCodeEnum.OP_plus.id -> {
                    val a = stack.removeLast()
                    stack.add(if (JS_IsInt(a) || JS_IsShortFloat(a)) a
                              else JS_NewFloat64(ctx, toNumber(a)))
                }
                
                OPCodeEnum.OP_pow.id -> {
                    val b = stack.removeLast()
                    val a = stack.removeLast()
                    stack.add(JS_NewFloat64(ctx, Math.pow(toNumber(a), toNumber(b))))
                }
                
                OPCodeEnum.OP_lnot.id -> {
                    val a = stack.removeLast()
                    stack.add(if (toBoolean(a)) JS_FALSE else JS_TRUE)
                }
                
                OPCodeEnum.OP_lt.id -> {
                    val b = stack.removeLast()
                    val a = stack.removeLast()
                    stack.add(doLt(a, b))
                }
                
                OPCodeEnum.OP_gt.id -> {
                    val b = stack.removeLast()
                    val a = stack.removeLast()
                    stack.add(doLt(b, a))
                }
                
                OPCodeEnum.OP_lte.id -> {
                    val b = stack.removeLast()
                    val a = stack.removeLast()
                    stack.add(doLte(a, b))
                }
                
                OPCodeEnum.OP_gte.id -> {
                    val b = stack.removeLast()
                    val a = stack.removeLast()
                    stack.add(doLte(b, a))
                }
                
                OPCodeEnum.OP_eq.id -> {
                    val b = stack.removeLast()
                    val a = stack.removeLast()
                    stack.add(doEq(a, b))
                }
                
                OPCodeEnum.OP_neq.id -> {
                    val b = stack.removeLast()
                    val a = stack.removeLast()
                    stack.add(if (toBoolean(doEq(a, b))) JS_FALSE else JS_TRUE)
                }
                
                OPCodeEnum.OP_strict_eq.id -> {
                    val b = stack.removeLast()
                    val a = stack.removeLast()
                    stack.add(if (strictEquals(a, b)) JS_TRUE else JS_FALSE)
                }
                
                OPCodeEnum.OP_strict_neq.id -> {
                    val b = stack.removeLast()
                    val a = stack.removeLast()
                    stack.add(if (strictEquals(a, b)) JS_FALSE else JS_TRUE)
                }
                
                OPCodeEnum.OP_shl.id -> {
                    val b = stack.removeLast()
                    val a = stack.removeLast()
                    stack.add(doShl(a, b))
                }
                
                OPCodeEnum.OP_sar.id -> {
                    val b = stack.removeLast()
                    val a = stack.removeLast()
                    stack.add(doSar(a, b))
                }
                
                OPCodeEnum.OP_shr.id -> {
                    val b = stack.removeLast()
                    val a = stack.removeLast()
                    stack.add(doShr(a, b))
                }
                
                OPCodeEnum.OP_and.id -> {
                    val b = stack.removeLast()
                    val a = stack.removeLast()
                    stack.add(doAnd(a, b))
                }
                
                OPCodeEnum.OP_or.id -> {
                    val b = stack.removeLast()
                    val a = stack.removeLast()
                    stack.add(doOr(a, b))
                }
                
                OPCodeEnum.OP_xor.id -> {
                    val b = stack.removeLast()
                    val a = stack.removeLast()
                    stack.add(doXor(a, b))
                }
                
                OPCodeEnum.OP_not.id -> {
                    val a = stack.removeLast()
                    stack.add(doNot(a))
                }
                
                OPCodeEnum.OP_if_false.id -> {
                    val offset = readI32(code, pc)
                    pc += 4
                    if (!toBoolean(stack.removeLast())) {
                        pc += offset
                    }
                }
                
                OPCodeEnum.OP_if_true.id -> {
                    val offset = readI32(code, pc)
                    pc += 4
                    if (toBoolean(stack.removeLast())) {
                        pc += offset
                    }
                }
                
                OPCodeEnum.OP_goto.id -> {
                    val offset = readI32(code, pc)
                    pc += 4
                    pc += offset
                }
                
                OPCodeEnum.OP_return.id -> return stack.removeLast()
                OPCodeEnum.OP_return_undef.id -> return JS_UNDEFINED
                
                OPCodeEnum.OP_regexp.id -> {
                    /* [pattern, flags] -> RegExp 对象 */
                    val flags = jsGetString(ctx, stack.removeLast())
                    val pattern = jsGetString(ctx, stack.removeLast())
                    val re = JSStdlib.newRegExp(ctx, pattern, flags)
                    if (JS_IsException(re)) {
                        val target = unwindToCatch(stack)
                        if (target < 0) return JS_EXCEPTION
                        pc = target
                    } else {
                        stack.add(re)
                    }
                }
                
                OPCodeEnum.OP_object.id -> {
                    val n = readU16(code, pc)
                    pc += 2
                    stack.add(JS_NewObject(ctx))
                }
                
                OPCodeEnum.OP_array_from.id -> {
                    val argc = readU16(code, pc)
                    pc += 2
                    /* JS_NewArray 的分配可能触发 GC 搬移：此刻元素仍在解释器栈上
                     * （栈是 GC 根，随搬移自动更新），先建数组再取元素 */
                    val arr = JS_NewArray(ctx, argc)
                    val keepBase = ctx.gcKeepAlive.size
                    /* 元素先出栈入保活列表：随后补分配元素存储（见下）可能触发
                     * GC 搬移，Kotlin 局部值会过期，保活列表中的值随搬移更新 */
                    ctx.gcKeepAlive.add(arr)
                    val elems = ArrayList<JSValue>(argc)
                    for (i in 0 until argc) elems.add(stack.removeLast())
                    for (e in elems) ctx.gcKeepAlive.add(e)

                    /* JS_NewArray 不分配元素存储（arrData 为 JS_NULL）：补分配并
                     * 写入元素（对照 C OP_array_from 的数组构造语义） */
                    val arrKept = ctx.gcKeepAlive[keepBase]
                    val arrPtr = JS_VALUE_TO_PTR(arrKept)
                    var arrData = ctx.memory.getJSValue(arrPtr + 24)
                    if (arrData == JS_NULL) {
                        arrData = allocValueArray(argc)
                        if (arrData != JS_NULL) {
                            ctx.memory.putJSValue(arrPtr + 24, arrData)
                        }
                    }
                    if (arrData != JS_NULL) {
                        val dataPtr = JS_VALUE_TO_PTR(arrData)
                        /* elems[i] 为第 argc-1-i 个元素（出栈序与写入序相反） */
                        for (j in 0 until argc) {
                            ctx.memory.putJSValue(
                                dataPtr + 8 + j * 8,
                                ctx.gcKeepAlive[keepBase + 1 + (argc - 1 - j)]
                            )
                        }
                    }
                    while (ctx.gcKeepAlive.size > keepBase) ctx.gcKeepAlive.removeAt(ctx.gcKeepAlive.size - 1)
                    stack.add(arrKept)
                }
                
                OPCodeEnum.OP_get_field.id -> {
                    val idx = readU16(code, pc)
                    pc += 2
                    val obj = stack.removeLast()
                    val cpool = ctx.memory.getJSValue(funcPtrNow() + 24)
                    val key = if (cpool != JS_NULL) {
                        val cpoolPtr = JS_VALUE_TO_PTR(cpool)
                        ctx.memory.getJSValue(cpoolPtr + 8 + idx * 8)
                    } else JS_UNDEFINED
                    val v = getProperty(obj, key)
                    if (System.getenv("MQJS_TRACE") != null) {
                        println("TRACE get_field obj=${if (JS_IsPtr(obj)) JS_VALUE_TO_PTR(obj) else obj} key='${jsGetString(ctx, key)}' -> isUnd=${JS_IsUndefined(v)}")
                    }
                    stack.add(v)
                }
                
                OPCodeEnum.OP_get_field2.id -> {
                    /* 对照 C：sp-- 后 sp[0] = get_field(sp[0], atom)
                     * —— 栈顶对象被保留，属性值压入其上（[obj] → [obj, value]），
                     * 供方法调用把 obj 作为 this 使用 */
                    val idx = readU16(code, pc)
                    pc += 2
                    if (stack.isEmpty()) {
                        return JS_EXCEPTION
                    }
                    val obj = stack.last()
                    val cpool = ctx.memory.getJSValue(funcPtrNow() + 24)
                    val key = if (cpool != JS_NULL) {
                        val cpoolPtr = JS_VALUE_TO_PTR(cpool)
                        ctx.memory.getJSValue(cpoolPtr + 8 + idx * 8)
                    } else JS_UNDEFINED
                    stack.add(getProperty(obj, key))
                }
                
                OPCodeEnum.OP_put_field.id -> {
                    val idx = readU16(code, pc)
                    pc += 2
                    val val1 = stack.removeLast()
                    val obj = stack.removeLast()
                    val cpool = ctx.memory.getJSValue(funcPtrNow() + 24)
                    val key = if (cpool != JS_NULL) {
                        val cpoolPtr = JS_VALUE_TO_PTR(cpool)
                        ctx.memory.getJSValue(cpoolPtr + 8 + idx * 8)
                    } else JS_UNDEFINED
                    /* 数组只接受 length 与规范索引属性；其他键按 C 版语义抛 TypeError */
                    var badKey = false
                    if (JS_IsPtr(obj)) {
                        val op = JS_VALUE_TO_PTR(obj)
                        if (ctx.memory.getMTag(op) == JSMTags.JS_MTAG_OBJECT &&
                            ctx.memory.getU8(op + 2) == JSObjectClassEnum.JS_CLASS_ARRAY.value &&
                            JS_IsString(ctx, key)) {
                            val ks = jsGetString(ctx, key)
                            if (ks != "length" && arrayIndexFromString(ks) < 0) {
                                JS_ThrowTypeError(ctx, "invalid array index")
                                badKey = true
                            }
                        }
                    }
                    if (badKey) {
                        val target = unwindToCatch(stack)
                        if (target < 0) return JS_EXCEPTION
                        pc = target
                    } else {
                        setProperty(obj, key, val1)
                    }
                    if (System.getenv("MQJS_TRACE") != null) {
                        println("TRACE put_field obj=${if (JS_IsPtr(obj)) JS_VALUE_TO_PTR(obj) else obj} key='${jsGetString(ctx, key)}' val=${if (JS_IsInt(val1)) JS_VALUE_GET_INT(val1) else -1}")
                    }
                }
                
                OPCodeEnum.OP_define_field.id -> {
                    val idx = readU16(code, pc)
                    pc += 2
                    val val1 = stack.removeLast()
                    val obj = stack.removeLast()
                    val cpool = ctx.memory.getJSValue(funcPtrNow() + 24)
                    val key = if (cpool != JS_NULL) {
                        val cpoolPtr = JS_VALUE_TO_PTR(cpool)
                        ctx.memory.getJSValue(cpoolPtr + 8 + idx * 8)
                    } else JS_UNDEFINED
                    defineProperty(obj, key, val1)
                    stack.add(obj)
                }
                
                OPCodeEnum.OP_get_array_el.id -> {
                    val idx = stack.removeLast()
                    val obj = stack.removeLast()
                    stack.add(getElement(obj, idx))
                }
                
                OPCodeEnum.OP_get_array_el2.id -> {
                    /* 对照 C：与 get_field2 同构——弹出 key 但保留 obj（[obj, key] → [obj, val]） */
                    if (stack.size < 2) {
                        return JS_EXCEPTION
                    }
                    val idx = stack.removeLast()
                    val obj = stack.last()
                    val val1 = getElement(obj, idx)
                    stack.add(val1)
                }
                
                OPCodeEnum.OP_put_array_el.id -> {
                    val val1 = stack.removeLast()
                    val idx = stack.removeLast()
                    val obj = stack.removeLast()
                    var badIndex = false
                    if (JS_IsPtr(obj)) {
                        val op = JS_VALUE_TO_PTR(obj)
                        if (ctx.memory.getMTag(op) == JSMTags.JS_MTAG_OBJECT &&
                            ctx.memory.getU8(op + 2) == JSObjectClassEnum.JS_CLASS_ARRAY.value &&
                            !isArrayIndexValue(idx)) {
                            JS_ThrowTypeError(ctx, "invalid array index")
                            badIndex = true
                        }
                    }
                    if (badIndex) {
                        val target = unwindToCatch(stack)
                        if (target < 0) return JS_EXCEPTION
                        pc = target
                    } else {
                        setElement(obj, idx, val1)
                    }
                }
                
                OPCodeEnum.OP_get_length.id -> {
                    val obj = stack.removeLast()
                    stack.add(getLength(obj))
                }
                
                OPCodeEnum.OP_get_length2.id -> {
                    val obj = stack.last()
                    stack.add(getLength(obj))
                }
                
                OPCodeEnum.OP_typeof.id -> {
                    stack.add(JS_NewString(ctx, jsTypeof(stack.removeLast())))
                }
                
                OPCodeEnum.OP_in.id -> {
                    val obj = stack.removeLast()
                    val key = stack.removeLast()
                    stack.add(JS_NewBool(hasProperty(obj, key)))
                }
                
                OPCodeEnum.OP_instanceof.id -> {
                    val ctor = stack.removeLast()
                    val obj = stack.removeLast()
                    val r = jsInstanceof(obj, ctor)
                    if (JS_IsException(r)) {
                        val target = unwindToCatch(stack)
                        if (target < 0) return JS_EXCEPTION
                        pc = target
                    } else {
                        stack.add(r)
                    }
                }
                
                OPCodeEnum.OP_delete.id -> {
                    val key = stack.removeLast()
                    val obj = stack.removeLast()
                    deleteProperty(obj, key)
                    stack.add(JS_TRUE)
                }
                
                OPCodeEnum.OP_arguments.id -> {
                    /* arguments：把本帧实参复制成数组对象（length/索引均可用）。
                     * 实参已由 runBody 放入 gcKeepAlive（keepStart+2 起） */
                    val argc = args.size
                    val arr = JS_NewArray(ctx, argc)
                    if (JS_IsException(arr)) return JS_EXCEPTION
                    ctx.gcKeepAlive.add(arr)
                    try {
                        val tab = allocValueArray(argc)
                        if (!JS_IsNull(tab) && !JS_IsException(tab)) {
                            val tabPtr = JS_VALUE_TO_PTR(tab)
                            for (i in 0 until argc) {
                                ctx.memory.putJSValue(tabPtr + 8 + i * 8,
                                                      ctx.gcKeepAlive[keepStart + 3 + i])
                            }
                            val arrPtr = JS_VALUE_TO_PTR(ctx.gcKeepAlive[ctx.gcKeepAlive.size - 1])
                            ctx.memory.putJSValue(arrPtr + 24, tab)
                            ctx.memory.putI32(arrPtr + 32, argc)
                        }
                        stack.add(ctx.gcKeepAlive[ctx.gcKeepAlive.size - 1])
                    } finally {
                        ctx.gcKeepAlive.removeAt(ctx.gcKeepAlive.size - 1)
                    }
                }
                
                OPCodeEnum.OP_define_getter.id, OPCodeEnum.OP_define_setter.id -> {
                    val idx = readU16(code, pc)
                    pc += 2
                    val func = stack.removeLast()
                    val obj = stack.removeLast()
                    val cpool = ctx.memory.getJSValue(funcPtrNow() + 24)
                    val key = if (cpool != JS_NULL) {
                        ctx.memory.getJSValue(JS_VALUE_TO_PTR(cpool) + 8 + idx * 8)
                    } else JS_UNDEFINED
                    defineAccessor(obj, key, func, isGetter = op == OPCodeEnum.OP_define_getter.id)
                    stack.add(obj)
                }
                
                OPCodeEnum.OP_set_proto.id -> {
                    val proto = stack.removeLast()
                    val obj = stack.removeLast()
                    if (JS_IsPtr(obj)) {
                        val objPtr = JS_VALUE_TO_PTR(obj)
                        if (ctx.memory.getMTag(objPtr) == JSMTags.JS_MTAG_OBJECT) {
                            val newProto = if (JS_IsPtr(proto) &&
                                ctx.memory.getMTag(JS_VALUE_TO_PTR(proto)) == JSMTags.JS_MTAG_OBJECT) {
                                proto
                            } else if (JS_IsNull(proto)) {
                                JS_NULL
                            } else {
                                ctx.memory.getJSValue(objPtr + 8)
                            }
                            ctx.memory.putJSValue(objPtr + 8, newProto)
                        }
                    }
                    stack.add(obj)
                }
                
                OPCodeEnum.OP_for_in_start.id -> {
                    /* obj -> 迭代器（VALUE_ARRAY [keysArray, pos]） */
                    val obj = stack.removeLast()
                    val keys = jsObjectKeys(obj)
                    if (JS_IsException(keys)) {
                        val target = unwindToCatch(stack)
                        if (target < 0) return JS_EXCEPTION
                        pc = target
                    } else {
                        ctx.gcKeepAlive.add(keys)
                        try {
                            val iter = allocValueArray(2)
                            if (JS_IsNull(iter) || JS_IsException(iter)) return JS_EXCEPTION
                            val iterPtr = JS_VALUE_TO_PTR(iter)
                            ctx.memory.putJSValue(iterPtr + 8, ctx.gcKeepAlive[ctx.gcKeepAlive.size - 1])
                            ctx.memory.putJSValue(iterPtr + 16, JS_NewShortInt(0))
                            stack.add(iter)
                        } finally {
                            ctx.gcKeepAlive.removeAt(ctx.gcKeepAlive.size - 1)
                        }
                    }
                }
                
                OPCodeEnum.OP_for_of_start.id -> {
                    /* for-of：迭代数组元素。迭代器结构同 for-in（[数组对象, pos]） */
                    val obj = stack.removeLast()
                    var ok = false
                    if (JS_IsPtr(obj)) {
                        val p = JS_VALUE_TO_PTR(obj)
                        ok = ctx.memory.getMTag(p) == JSMTags.JS_MTAG_OBJECT &&
                             ctx.memory.getU8(p + 2) == JSObjectClassEnum.JS_CLASS_ARRAY.value
                    }
                    if (!ok) {
                        val r = JS_ThrowTypeError(ctx, "unsupported type in for...of")
                        if (JS_IsException(r)) {
                            val target = unwindToCatch(stack)
                            if (target < 0) return JS_EXCEPTION
                            pc = target
                        }
                    } else {
                        ctx.gcKeepAlive.add(obj)
                        try {
                            val iter = allocValueArray(2)
                            if (JS_IsNull(iter) || JS_IsException(iter)) return JS_EXCEPTION
                            val iterPtr = JS_VALUE_TO_PTR(iter)
                            ctx.memory.putJSValue(iterPtr + 8, ctx.gcKeepAlive[ctx.gcKeepAlive.size - 1])
                            ctx.memory.putJSValue(iterPtr + 16, JS_NewShortInt(0))
                            stack.add(iter)
                        } finally {
                            ctx.gcKeepAlive.removeAt(ctx.gcKeepAlive.size - 1)
                        }
                    }
                }
                
                OPCodeEnum.OP_for_of_next.id -> {
                    /* iter -> iter value done（done 在栈顶，供 if_false 消费） */
                    val iter = stack.last()
                    val iterPtr = JS_VALUE_TO_PTR(iter)
                    val keys = ctx.memory.getJSValue(iterPtr + 8)
                    val pos = JS_VALUE_GET_INT(ctx.memory.getJSValue(iterPtr + 16))
                    val keyArrPtr = JS_VALUE_TO_PTR(keys)
                    val len = ctx.memory.getI32(keyArrPtr + 32)
                    if (pos >= len) {
                        stack.add(JS_UNDEFINED)
                        stack.add(JS_TRUE)
                    } else {
                        val tab = ctx.memory.getJSValue(keyArrPtr + 24)
                        val elem = if (tab == JS_NULL) JS_UNDEFINED
                                   else ctx.memory.getJSValue(JS_VALUE_TO_PTR(tab) + 8 + pos * 8)
                        stack.add(elem)
                        stack.add(JS_FALSE)
                        ctx.memory.putJSValue(iterPtr + 16, JS_NewShortInt(pos + 1))
                    }
                }
                
                OPCodeEnum.OP_get_loc.id -> {
                    val idx = readU16(code, pc)
                    pc += 2
                    val v = vars[idx]
                    if (System.getenv("MQJS_THROW_TRACE") != null && JS_IsPtr(v)) {
                        val vp = JS_VALUE_TO_PTR(v)
                        if (vp in ctx.heapBase until ctx.heapFree &&
                            ctx.memory.getMTag(vp) == JSMTags.JS_MTAG_FREE) {
                            var nm = "?"
                            val vt = ctx.memory.getJSValue(funcPtrNow() + 32)
                            if (vt != JS_NULL) {
                                val vtp = JS_VALUE_TO_PTR(vt)
                                val nv = ctx.memory.getJSValue(vtp + 8 + idx * 8)
                                if (JS_IsString(ctx, nv)) nm = jsGetString(ctx, nv)
                            }
                            System.err.println("STALE_LOC idx=$idx name=$nm val=$v heapFree=${ctx.heapFree}")
                        }
                    }
                    stack.add(v)
                }
                
                OPCodeEnum.OP_put_loc.id -> {
                    val idx = readU16(code, pc)
                    pc += 2
                    val val1 = stack.removeLast()
                    if (System.getenv("MQJS_THROW_TRACE") != null && JS_IsPtr(val1)) {
                        val vp = JS_VALUE_TO_PTR(val1)
                        if (vp >= ctx.heapFree || (vp >= ctx.heapBase && ctx.memory.getMTag(vp) == JSMTags.JS_MTAG_FREE)) {
                            var nm = "?"
                            val vt = ctx.memory.getJSValue(funcPtrNow() + 32)
                            if (vt != JS_NULL) {
                                val vtp = JS_VALUE_TO_PTR(vt)
                                val nv = ctx.memory.getJSValue(vtp + 8 + idx * 8)
                                if (JS_IsString(ctx, nv)) nm = jsGetString(ctx, nv)
                            }
                            System.err.println("STALE_PUT idx=$idx name=$nm val=$val1 heapFree=${ctx.heapFree}")
                        }
                    }
                    vars[idx] = val1
                }
                
                OPCodeEnum.OP_get_loc0.id, OPCodeEnum.OP_get_loc1.id, OPCodeEnum.OP_get_loc2.id, OPCodeEnum.OP_get_loc3.id -> {
                    val idx = op - OPCodeEnum.OP_get_loc0.id
                    stack.add(vars[idx])
                }
                
                OPCodeEnum.OP_put_loc0.id, OPCodeEnum.OP_put_loc1.id, OPCodeEnum.OP_put_loc2.id, OPCodeEnum.OP_put_loc3.id -> {
                    val idx = op - OPCodeEnum.OP_put_loc0.id
                    vars[idx] = stack.removeLast()
                }
                
                OPCodeEnum.OP_call.id -> {
                    val argc = readU16(code, pc)
                    pc += 2
                    val args = mutableListOf<JSValue>()
                    for (i in 0 until argc) {
                        args.add(0, stack.removeLast())
                    }
                    val funcVal = stack.removeLast()
                    /* 普通调用的 this 为 undefined（对照 C generic_function_call 压入 JS_UNDEFINED） */
                    val r = callFunction(funcVal, args, JS_UNDEFINED)
                    if (JS_IsException(r) && JS_HasException(ctx)) {
                        /* 调用产生异常：本帧展开，命中 catch 则继续执行（对照 C call_exception） */
                        val target = unwindToCatch(stack)
                        if (target < 0) return JS_EXCEPTION
                        pc = target
                    } else {
                        stack.add(r)
                    }
                }
                
                OPCodeEnum.OP_call_method.id -> {
                    val argc = readU16(code, pc)
                    pc += 2
                    val args = mutableListOf<JSValue>()
                    for (i in 0 until argc) {
                        args.add(0, stack.removeLast())
                    }
                    val funcVal = stack.removeLast()
                    val thisObj = stack.removeLast()
                    val r = callMethod(funcVal, thisObj, args)
                    if (JS_IsException(r) && JS_HasException(ctx)) {
                        val target = unwindToCatch(stack)
                        if (target < 0) return JS_EXCEPTION
                        pc = target
                    } else {
                        stack.add(r)
                    }
                }
                
                OPCodeEnum.OP_call_constructor.id -> {
                    val argc = readU16(code, pc)
                    pc += 2
                    val args = mutableListOf<JSValue>()
                    for (i in 0 until argc) {
                        args.add(0, stack.removeLast())
                    }
                    val funcVal = stack.removeLast()
                    val r = callConstructor(funcVal, args)
                    if (JS_IsException(r) && JS_HasException(ctx)) {
                        val target = unwindToCatch(stack)
                        if (target < 0) return JS_EXCEPTION
                        pc = target
                    } else {
                        stack.add(r)
                    }
                }
                
                OPCodeEnum.OP_catch.id -> {
                    /* 压入 catch 目标偏移（对照 C：JS_VALUE_MAKE_SPECIAL(JS_TAG_CATCH_OFFSET, 目标)），
                     * 异常展开时该值被替换为异常本身 */
                    val diff = readI32(code, pc)
                    pc += 4
                    stack.add(JS_VALUE_MAKE_SPECIAL(JSTags.JS_TAG_CATCH_OFFSET, pc + diff))
                }
                
                OPCodeEnum.OP_throw.id -> {
                    /* 弹出异常值并抛出，转入本帧异常处理（对照 C OP_throw → goto exception） */
                    val ex = stack.removeLast()
                    JS_Throw(ctx, ex)
                    val target = unwindToCatch(stack)
                    if (target < 0) return JS_EXCEPTION
                    pc = target
                }
                
                OPCodeEnum.OP_gosub.id -> {
                    /* 压入返回地址后跳转（finally 机制，对照 C OP_gosub） */
                    val diff = readI32(code, pc)
                    pc += 4
                    stack.add(JS_NewShortInt(pc))
                    if (System.getenv("MQJS_TRACE") != null) {
                        println("TRACE gosub pushed addr=$pc raw=${JS_NewShortInt(pc)}")
                    }
                    pc += diff
                }
                
                OPCodeEnum.OP_ret.id -> {
                    /* 从 finally 返回（对照 C OP_ret：栈顶为 ShortInt 返回地址） */
                    val ret = stack.removeLast()
                    if (System.getenv("MQJS_TRACE") != null) {
                        println("TRACE ret popped raw=$ret isInt=${JS_IsInt(ret)} addr=${if (JS_IsInt(ret)) JS_VALUE_GET_INT(ret) else -1}")
                    }
                    if (!JS_IsInt(ret)) return JS_EXCEPTION
                    pc = JS_VALUE_GET_INT(ret)
                }
            }
        }
        } finally {
            /* 注销本帧的 JVM 侧根与 func/thisObj/args 保活 */
            while (ctx.jvmRoots.size > rootBase) ctx.jvmRoots.removeAt(ctx.jvmRoots.size - 1)
            while (ctx.gcKeepAlive.size > keepStart) ctx.gcKeepAlive.removeAt(ctx.gcKeepAlive.size - 1)
        }
        
        return if (stack.isEmpty()) JS_UNDEFINED else stack.last()
    }
    
    private fun readU16(code: ByteArray, pc: Int): Int {
        return (code[pc].toInt() and 0xFF) or ((code[pc + 1].toInt() and 0xFF) shl 8)
    }
    
    /* 本帧异常展开（对照 C JS_Call 的 exception 标签）：
     * 从栈顶向栈底扫描本帧操作数栈中的 CATCH_OFFSET 值。
     * 找到：用异常值替换该槽并截断其上内容，返回 catch 目标 pc（调用方继续执行）；
     * 未找到：清空本帧栈，返回 -1（向上传播）；
     * 异常不可捕获时不清异常状态、直接向上传播（对照 C sp = stack_top 分支） */
    private fun unwindToCatch(stack: MutableList<JSValue>): Int {
        if (ctx.currentExceptionIsUncatchable) {
            stack.clear()
            return -1
        }
        for (i in stack.indices.reversed()) {
            val v = stack[i]
            if (JS_VALUE_GET_SPECIAL_TAG(v) == JSTags.JS_TAG_CATCH_OFFSET) {
                val target = JS_VALUE_GET_SPECIAL_VALUE(v)
                stack[i] = ctx.currentException
                while (stack.size > i + 1) stack.removeAt(stack.size - 1)
                ctx.currentException = JS_UNINITIALIZED
                ctx.currentExceptionIsUncatchable = false
                return target
            }
        }
        stack.clear()
        return -1
    }
    
    private fun readI32(code: ByteArray, pc: Int): Int {
        return (code[pc].toInt() and 0xFF) or
               ((code[pc + 1].toInt() and 0xFF) shl 8) or
               ((code[pc + 2].toInt() and 0xFF) shl 16) or
               ((code[pc + 3].toInt() and 0xFF) shl 24)
    }
    
    fun toNumber(v0: JSValue): Double {
        val v = toPrimitive(v0, 1)
        if (JS_IsInt(v)) return JS_VALUE_GET_INT(v).toDouble()
        if (JS_IsShortFloat(v)) return jsGetShortFloat(v)
        if (JS_IsPtr(v)) {
            val ptr = JS_VALUE_TO_PTR(v)
            when (ctx.memory.getMTag(ptr)) {
                JSMTags.JS_MTAG_FLOAT64 -> return ctx.memory.getFloat64(ptr + 8)
                JSMTags.JS_MTAG_STRING -> {
                    val s = jsGetString(ctx, v).trim()
                    if (s.isEmpty()) return 0.0
                    return try {
                        when {
                            s.startsWith("0x") || s.startsWith("0X") -> s.substring(2).toLong(16).toDouble()
                            s.startsWith("0b") || s.startsWith("0B") -> s.substring(2).toLong(2).toDouble()
                            s.startsWith("0o") || s.startsWith("0O") -> s.substring(2).toLong(8).toDouble()
                            else -> s.toDouble()
                        }
                    } catch (e: NumberFormatException) {
                        Double.NaN
                    }
                }
            }
        }
        if (JS_IsNull(v)) return 0.0
        if (JS_IsUndefined(v)) return Double.NaN
        if (JS_IsBool(v)) return if (JS_VALUE_GET_SPECIAL_VALUE(v) != 0) 1.0 else 0.0
        return Double.NaN
    }
    
    /* ToPrimitive：对象先尝试 valueOf/toString（hint：0 default，1 number，2 string） */
    fun toPrimitive(v: JSValue, hint: Int): JSValue {
        if (!JS_IsPtr(v)) return v
        val ptr0 = JS_VALUE_TO_PTR(v)
        if (ctx.memory.getMTag(ptr0) != JSMTags.JS_MTAG_OBJECT) return v
        ctx.gcKeepAlive.add(v)
        try {
            val order = if (hint == 2) arrayOf("toString", "valueOf") else arrayOf("valueOf", "toString")
            for (name in order) {
                val key = JS_NewString(ctx, name)
                val m = getProperty(ctx.gcKeepAlive[ctx.gcKeepAlive.size - 1], key)
                if (JS_IsFunction(ctx, m)) {
                    val r = callFunction(m, emptyList(), ctx.gcKeepAlive[ctx.gcKeepAlive.size - 1])
                    if (!JS_IsException(r)) {
                        val rp = if (JS_IsPtr(r)) JS_VALUE_TO_PTR(r) else -1
                        if (rp < 0 || ctx.memory.getMTag(rp) != JSMTags.JS_MTAG_OBJECT) {
                            return r
                        }
                    }
                }
            }
            return ctx.gcKeepAlive[ctx.gcKeepAlive.size - 1]
        } finally {
            ctx.gcKeepAlive.removeAt(ctx.gcKeepAlive.size - 1)
        }
    }
    
    /* ToString（用于字符串拼接） */
    fun toStringValue(v0: JSValue): String {
        val v = if (JS_IsPtr(v0) &&
                    ctx.memory.getMTag(JS_VALUE_TO_PTR(v0)) == JSMTags.JS_MTAG_OBJECT) {
            toPrimitive(v0, 2)
        } else v0
        return jsGetString(ctx, v)
    }
    
    fun strictEquals(a: JSValue, b: JSValue): Boolean {
        if (a == b) return true
        if (JS_IsString(ctx, a) && JS_IsString(ctx, b)) {
            return jsGetString(ctx, a) == jsGetString(ctx, b)
        }
        /* 数值跨表示形式相等：整数 2 与浮点 2.0 是同一个 JS 数值 */
        if (isNumberValue(a) && isNumberValue(b)) {
            return toNumber(a) == toNumber(b)
        }
        return false
    }
    
    fun isNumberValue(v: JSValue): Boolean {
        if (JS_IsInt(v) || JS_IsShortFloat(v)) return true
        if (!JS_IsPtr(v)) return false
        val p = JS_VALUE_TO_PTR(v)
        return ctx.memory.getMTag(p) == JSMTags.JS_MTAG_FLOAT64
    }
    
    fun toBoolean(v: JSValue): Boolean {
        if (JS_IsInt(v)) return JS_VALUE_GET_INT(v) != 0
        if (JS_IsShortFloat(v)) return jsGetShortFloat(v) != 0.0
        if (JS_IsNull(v) || JS_IsUndefined(v)) return false
        if (JS_IsBool(v)) return JS_VALUE_GET_SPECIAL_VALUE(v) != 0
        if (JS_IsPtr(v)) {
            val ptr = JS_VALUE_TO_PTR(v)
            if (ctx.memory.getMTag(ptr) == JSMTags.JS_MTAG_FLOAT64) {
                val d = ctx.memory.getFloat64(ptr + 8)
                return !d.isNaN() && d != 0.0
            }
            return true
        }
        return true
    }
    
    fun toInt32(v: JSValue): Int {
        val d = toNumber(v)
        if (d.isNaN() || d.isInfinite()) return 0
        /* 按 2^32 取模后解释为有符号 32 位（ECMAScript ToInt32），
         * 不能直接用 Double.toInt()（会饱和到 Int.MAX） */
        var n = (d % 4294967296.0).toLong()
        if (n < 0) n += 4294967296L
        return n.toInt()
    }
    
    fun toUInt32(v: JSValue): Long {
        val d = toNumber(v)
        if (d.isNaN() || d.isInfinite()) return 0
        var n = (d % 4294967296.0).toLong()
        if (n < 0) n += 4294967296L
        return n and 0xFFFFFFFFL
    }
    
    fun doAdd(a: JSValue, b: JSValue): JSValue {
        if (JS_IsInt(a) && JS_IsInt(b)) {
            val ia = JS_VALUE_GET_INT(a)
            val ib = JS_VALUE_GET_INT(b)
            val r = ia.toLong() + ib.toLong()
            if (r in JS_SHORTINT_MIN.toLong()..JS_SHORTINT_MAX.toLong()) {
                return JS_NewShortInt(r.toInt())
            }
            return JS_NewFloat64(ctx, r.toDouble())
        }
        
        /* ToPrimitive 之后再决定字符串拼接还是数值加法 */
        val pa = toPrimitive(a, 0)
        val pb = toPrimitive(b, 0)
        if (JS_IsString(ctx, pa) || JS_IsString(ctx, pb)) {
            return JS_NewString(ctx, toStringValue(pa) + toStringValue(pb))
        }
        
        return JS_NewFloat64(ctx, toNumber(pa) + toNumber(pb))
    }
    
    fun doSub(a: JSValue, b: JSValue): JSValue {
        if (JS_IsInt(a) && JS_IsInt(b)) {
            val ia = JS_VALUE_GET_INT(a)
            val ib = JS_VALUE_GET_INT(b)
            val r = ia.toLong() - ib.toLong()
            if (r in JS_SHORTINT_MIN.toLong()..JS_SHORTINT_MAX.toLong()) {
                return JS_NewShortInt(r.toInt())
            }
            return JS_NewFloat64(ctx, r.toDouble())
        }
        return JS_NewFloat64(ctx, toNumber(a) - toNumber(b))
    }
    
    fun doMul(a: JSValue, b: JSValue): JSValue {
        if (JS_IsInt(a) && JS_IsInt(b)) {
            val ia = JS_VALUE_GET_INT(a)
            val ib = JS_VALUE_GET_INT(b)
            /* 0 * 负数 = -0（JS 的零符号语义） */
            if (ia == 0 || ib == 0) {
                return if ((ia < 0) != (ib < 0)) JS_NewFloat64(ctx, -0.0)
                       else JS_NewShortInt(0)
            }
            val r = ia.toLong() * ib.toLong()
            if (r in JS_SHORTINT_MIN.toLong()..JS_SHORTINT_MAX.toLong()) {
                return JS_NewShortInt(r.toInt())
            }
        }
        return JS_NewFloat64(ctx, toNumber(a) * toNumber(b))
    }
    
    fun doDiv(a: JSValue, b: JSValue): JSValue {
        return JS_NewFloat64(ctx, toNumber(a) / toNumber(b))
    }
    
    fun doMod(a: JSValue, b: JSValue): JSValue {
        if (JS_IsInt(a) && JS_IsInt(b)) {
            val va = JS_VALUE_GET_INT(a)
            val vb = JS_VALUE_GET_INT(b)
            if (va >= 0 && vb > 0) {
                return JS_NewShortInt(va % vb)
            }
        }
        return JS_NewFloat64(ctx, toNumber(a) % toNumber(b))
    }
    
    fun doNeg(a: JSValue): JSValue {
        if (JS_IsInt(a)) {
            val ia = JS_VALUE_GET_INT(a)
            /* -0 必须是 -0.0（1 / -0 === -Infinity） */
            if (ia == 0) return JS_NewFloat64(ctx, -0.0)
            if (ia > JS_SHORTINT_MIN) {
                return JS_NewShortInt(-ia)
            }
        }
        return JS_NewFloat64(ctx, -toNumber(a))
    }
    
    fun doShl(a: JSValue, b: JSValue): JSValue {
        val ia = toInt32(a)
        val ib = toInt32(b) and 31
        return JS_NewShortInt(ia shl ib)
    }
    
    fun doSar(a: JSValue, b: JSValue): JSValue {
        val ia = toInt32(a)
        val ib = toInt32(b) and 31
        return JS_NewShortInt(ia shr ib)
    }
    
    fun doShr(a: JSValue, b: JSValue): JSValue {
        val ia = toUInt32(a)
        val ib = toInt32(b) and 31
        return JS_NewFloat64(ctx, (ia shr ib).toDouble())
    }
    
    fun doAnd(a: JSValue, b: JSValue): JSValue {
        return JS_NewShortInt(toInt32(a) and toInt32(b))
    }
    
    fun doOr(a: JSValue, b: JSValue): JSValue {
        return JS_NewShortInt(toInt32(a) or toInt32(b))
    }
    
    fun doXor(a: JSValue, b: JSValue): JSValue {
        return JS_NewShortInt(toInt32(a) xor toInt32(b))
    }
    
    fun doNot(a: JSValue): JSValue {
        return JS_NewShortInt(toInt32(a).inv())
    }
    
    fun doLt(a: JSValue, b: JSValue): JSValue {
        if (JS_IsString(ctx, a) && JS_IsString(ctx, b)) {
            val sa = jsGetString(ctx, a)
            val sb = jsGetString(ctx, b)
            return if (sa < sb) JS_TRUE else JS_FALSE
        }
        return if (toNumber(a) < toNumber(b)) JS_TRUE else JS_FALSE
    }
    
    fun doLte(a: JSValue, b: JSValue): JSValue {
        if (JS_IsString(ctx, a) && JS_IsString(ctx, b)) {
            val sa = jsGetString(ctx, a)
            val sb = jsGetString(ctx, b)
            return if (sa <= sb) JS_TRUE else JS_FALSE
        }
        return if (toNumber(a) <= toNumber(b)) JS_TRUE else JS_FALSE
    }
    
    fun doEq(a: JSValue, b: JSValue): JSValue {
        if (strictEquals(a, b)) return JS_TRUE
        if (JS_IsNull(a) && JS_IsUndefined(b)) return JS_TRUE
        if (JS_IsUndefined(a) && JS_IsNull(b)) return JS_TRUE
        if (JS_IsInt(a) && JS_IsBool(b)) return if (JS_VALUE_GET_INT(a) == JS_VALUE_GET_SPECIAL_VALUE(b)) JS_TRUE else JS_FALSE
        if (JS_IsBool(a) && JS_IsInt(b)) return if (JS_VALUE_GET_SPECIAL_VALUE(a) == JS_VALUE_GET_INT(b)) JS_TRUE else JS_FALSE
        if (JS_IsString(ctx, a) && JS_IsString(ctx, b)) {
            return if (jsGetString(ctx, a) == jsGetString(ctx, b)) JS_TRUE else JS_FALSE
        }
        return if (toNumber(a) == toNumber(b)) JS_TRUE else JS_FALSE
    }
    
    fun jsGetString(ctx: JSContext, v: JSValue): String {
        if (!JS_IsPtr(v)) {
            if (JS_IsInt(v)) return JS_VALUE_GET_INT(v).toString()
            if (JS_IsShortFloat(v)) return jsNumberToString(jsGetShortFloat(v))
            if (JS_IsNull(v)) return "null"
            if (JS_IsUndefined(v)) return "undefined"
            if (JS_IsBool(v)) return if (JS_VALUE_GET_SPECIAL_VALUE(v) != 0) "true" else "false"
            return ""
        }
        val ptr = JS_VALUE_TO_PTR(v)
        if (ctx.memory.getMTag(ptr) != JSMTags.JS_MTAG_STRING) {
            if (ctx.memory.getMTag(ptr) == JSMTags.JS_MTAG_FLOAT64) {
                return jsNumberToString(ctx.memory.getFloat64(ptr + 8))
            }
            return "[object]"
        }
        val len = ctx.memory.getI32(ptr + 4)
        return jsDecodeString(ctx.memory.getBytes(ptr + 8, len))
    }
    
    /* 读取对象属性：自身属性表 → 数组 length 回退 → 原型链。
     * 数组 length 回退：本引擎解析器未实现 OP_get_length 特化（C 引擎的
     * `.length` 走专用字节码），`.length` 会落到普通属性查找并得到 undefined，
     * 在此补齐 ES 数组 length 语义（长度字段存于数组对象头 ptr+32） */
    fun getProperty(obj: JSValue, key: JSValue, receiver: JSValue = obj): JSValue {
        /* 原始值属性访问：字符串取 length/索引后走 String.prototype；
         * 数字/布尔走各自原型（方法调用 this 保持原始值） */
        if (JS_IsString(ctx, obj)) {
            if (JS_IsString(ctx, key) && jsGetString(ctx, key) == "length") {
                return JS_NewShortInt(jsGetString(ctx, obj).length)
            }
            if (JS_IsInt(key)) {
                val s = jsGetString(ctx, obj)
                val i = JS_VALUE_GET_INT(key)
                if (i in s.indices) return JS_NewString(ctx, s[i].toString())
            }
            return getProperty(ctx.classProto[JSObjectClassEnum.JS_CLASS_STRING.value], key, obj)
        }
        if (!JS_IsPtr(obj)) {
            if (JS_IsInt(obj) || JS_IsShortFloat(obj)) {
                return getProperty(ctx.classProto[JSObjectClassEnum.JS_CLASS_NUMBER.value], key, obj)
            }
            if (JS_IsBool(obj)) {
                return getProperty(ctx.classProto[JSObjectClassEnum.JS_CLASS_BOOLEAN.value], key, obj)
            }
            return JS_UNDEFINED
        }
        val objPtr = JS_VALUE_TO_PTR(obj)
        if (ctx.memory.getMTag(objPtr) != JSMTags.JS_MTAG_OBJECT) return JS_UNDEFINED

        val props = ctx.memory.getJSValue(objPtr + 16)
        if (props != JS_NULL && props != ctx.emptyProps) {
            val propsPtr = JS_VALUE_TO_PTR(props)
            val hashMask = JS_VALUE_GET_INT(ctx.memory.getJSValue(propsPtr + 16))
            val h = hashProp(key, hashMask)
            var idx = JS_VALUE_GET_INT(ctx.memory.getJSValue(propsPtr + 24 + h * 8)) shr 1
            while (idx != 0) {
                val propPtr = propsPtr + 8 + idx * 8
                val propKey = ctx.memory.getJSValue(propPtr)
                if (propKey == key || jsPropKeyEquals(propKey, key)) {
                    val v = ctx.memory.getJSValue(propPtr + 8)
                    if (isAccessorValue(v)) {
                        /* 访问器属性：调用 getter（this 为接收者） */
                        val getter = ctx.memory.getJSValue(JS_VALUE_TO_PTR(v) + 24)
                        if (JS_IsFunction(ctx, getter)) {
                            return callFunction(getter, emptyList(), receiver)
                        }
                        return JS_UNDEFINED
                    }
                    return v
                }
                idx = JS_VALUE_GET_INT(ctx.memory.getJSValue(propPtr + 16)) shr 1
            }
        }

        val classId = ctx.memory.getU8(objPtr + 2)
        /* Error 实例的 message/stack 存在 extra 槽（对照 C 的 p->u.error） */
        if (classId == JSObjectClassEnum.JS_CLASS_ERROR.value && JS_IsString(ctx, key)) {
            when (jsGetString(ctx, key)) {
                "message" -> return ctx.memory.getJSValue(objPtr + 24)
                "stack" -> return ctx.memory.getJSValue(objPtr + 32)
            }
        }
        if (classId == JSObjectClassEnum.JS_CLASS_ARRAY.value && JS_IsString(ctx, key)) {
            when (jsGetString(ctx, key)) {
                "length" -> return JS_NewShortInt(ctx.memory.getI32(objPtr + 32))
            }
        }
        if (classId == JSObjectClassEnum.JS_CLASS_ARRAY.value && JS_IsInt(key)) {
            val i = JS_VALUE_GET_INT(key)
            if (i >= 0 && i < ctx.memory.getI32(objPtr + 32)) {
                return getElement(obj, key)
            }
        }

        val proto = ctx.memory.getJSValue(objPtr + 8)
        if (proto != JS_NULL) {
            val r = getProperty(proto, key, receiver)
            if (JS_IsUndefined(r) && System.getenv("MQJS_THROW_TRACE") != null &&
                JS_IsString(ctx, key)) {
                val ks = jsGetString(ctx, key)
                if (ks == "slice" || ks == "pop" || ks == "push") {
                    System.err.println("PROP_MISS key=$ks obj=$obj objHeap=${JS_VALUE_TO_PTR(obj)} heapFree=${ctx.heapFree} proto=$proto")
                }
            }
            return r
        }
        return JS_UNDEFINED
    }
    
    /* 自有可枚举属性名 → 新建的数组对象（数组元素为字符串键）。
     * 与 C 的 for-in 近似语义一致：数组先给 0..len-1 索引键，再给普通属性 */
    fun jsObjectKeys(obj: JSValue): JSValue {
        val names = ArrayList<String>()
        if (JS_IsPtr(obj)) {
            val p = JS_VALUE_TO_PTR(obj)
            if (ctx.memory.getMTag(p) == JSMTags.JS_MTAG_OBJECT) {
                val classId = ctx.memory.getU8(p + 2)
                if (classId == JSObjectClassEnum.JS_CLASS_ARRAY.value) {
                    val len = ctx.memory.getI32(p + 32)
                    for (i in 0 until len) names.add(i.toString())
                }
                val props = ctx.memory.getJSValue(p + 16)
                if (props != JS_NULL && props != ctx.emptyProps) {
                    val pp = JS_VALUE_TO_PTR(props)
                    val size = ctx.memory.getI32(pp + 4)
                    val hashMask = JS_VALUE_GET_INT(ctx.memory.getJSValue(pp + 16))
                    var idx = 2 + hashMask + 1
                    while (idx + 3 <= size) {
                        val k = ctx.memory.getJSValue(pp + 8 + idx * 8)
                        if (k != JS_UNINITIALIZED && JS_IsString(ctx, k)) {
                            val s = jsGetString(ctx, k)
                            if (!names.contains(s)) names.add(s)
                        }
                        idx += 3
                    }
                }
            }
        }
        
        val n = names.size
        val arr = JS_NewArray(ctx, n)
        if (JS_IsException(arr)) return arr
        ctx.gcKeepAlive.add(arr)
        try {
            if (n > 0) {
                val tab = allocValueArray(n)
                if (!JS_IsNull(tab) && !JS_IsException(tab)) {
                    ctx.gcKeepAlive.add(tab)
                    try {
                        for (i in 0 until n) {
                            val ks = JS_NewString(ctx, names[i])
                            val tabPtr = JS_VALUE_TO_PTR(ctx.gcKeepAlive[ctx.gcKeepAlive.size - 1])
                            ctx.memory.putJSValue(tabPtr + 8 + i * 8, ks)
                        }
                        val ap = JS_VALUE_TO_PTR(ctx.gcKeepAlive[ctx.gcKeepAlive.size - 2])
                        ctx.memory.putJSValue(ap + 24, ctx.gcKeepAlive[ctx.gcKeepAlive.size - 1])
                        ctx.memory.putI32(ap + 32, n)
                    } finally {
                        ctx.gcKeepAlive.removeAt(ctx.gcKeepAlive.size - 1)
                    }
                }
            }
            return ctx.gcKeepAlive[ctx.gcKeepAlive.size - 1]
        } finally {
            ctx.gcKeepAlive.removeAt(ctx.gcKeepAlive.size - 1)
        }
    }
    
    fun jsTypeof(v: JSValue): String {
        if (JS_IsInt(v) || JS_IsShortFloat(v)) return "number"
        if (JS_IsBool(v)) return "boolean"
        if (JS_IsNull(v)) return "object"
        if (JS_IsUndefined(v)) return "undefined"
        if (!JS_IsPtr(v)) return "undefined"
        val ptr = JS_VALUE_TO_PTR(v)
        when (ctx.memory.getMTag(ptr)) {
            JSMTags.JS_MTAG_STRING -> return "string"
            JSMTags.JS_MTAG_FLOAT64 -> return "number"
            JSMTags.JS_MTAG_OBJECT -> {
                val classId = ctx.memory.getU8(ptr + 2)
                if (classId == JSObjectClassEnum.JS_CLASS_CLOSURE.value ||
                    classId == JSObjectClassEnum.JS_CLASS_C_FUNCTION.value) {
                    return "function"
                }
                return "object"
            }
        }
        return "object"
    }
    
    /* instanceof：沿对象的原型链查找构造器的 prototype */
    fun jsInstanceof(obj: JSValue, ctor: JSValue): JSValue {
        if (!JS_IsFunction(ctx, ctor)) return JS_ThrowTypeError(ctx, "invalid 'instanceof' right operand")
        val protoKey = JS_NewString(ctx, "prototype")
        ctx.gcKeepAlive.add(protoKey)
        ctx.gcKeepAlive.add(ctor)
        try {
            val target = getProperty(ctx.gcKeepAlive[ctx.gcKeepAlive.size - 1],
                                     ctx.gcKeepAlive[ctx.gcKeepAlive.size - 2])
            if (!JS_IsPtr(target) ||
                ctx.memory.getMTag(JS_VALUE_TO_PTR(target)) != JSMTags.JS_MTAG_OBJECT) {
                return JS_ThrowTypeError(ctx, "invalid 'instanceof' right operand")
            }
            if (!JS_IsPtr(obj) ||
                ctx.memory.getMTag(JS_VALUE_TO_PTR(obj)) != JSMTags.JS_MTAG_OBJECT) {
                return JS_FALSE
            }
            var cur = ctx.memory.getJSValue(JS_VALUE_TO_PTR(obj) + 8)
            var guard = 0
            while (JS_IsPtr(cur) && guard++ < 100000) {
                if (cur == target) return JS_TRUE
                val p = JS_VALUE_TO_PTR(cur)
                if (ctx.memory.getMTag(p) != JSMTags.JS_MTAG_OBJECT) break
                cur = ctx.memory.getJSValue(p + 8)
            }
            return JS_FALSE
        } finally {
            ctx.gcKeepAlive.removeAt(ctx.gcKeepAlive.size - 1)
            ctx.gcKeepAlive.removeAt(ctx.gcKeepAlive.size - 1)
        }
    }
    
    /* 访问器描述符：类为 OBJECT 且 extra_size == 2，槽 0=getting 槽 1=setter。
     * 普通对象 extra_size 恒为 0，因此可作为可靠标记（无需改动属性表布局） */
    fun isAccessorValue(v: JSValue): Boolean {
        if (!JS_IsPtr(v)) return false
        val p = JS_VALUE_TO_PTR(v)
        return ctx.memory.getMTag(p) == JSMTags.JS_MTAG_OBJECT &&
               ctx.memory.getU8(p + 2) == JSObjectClassEnum.JS_CLASS_OBJECT.value &&
               ctx.memory.getU8(p + 3) == 2
    }
    
    fun JS_NewAccessor(getter: JSValue, setter: JSValue): JSValue {
        val obj = JS_NewObjectProtoClass(ctx, JS_NULL, JSObjectClassEnum.JS_CLASS_OBJECT.value, 2)
        if (JS_IsException(obj)) return obj
        val p = JS_VALUE_TO_PTR(obj)
        ctx.memory.putJSValue(p + 24, getter)
        ctx.memory.putJSValue(p + 32, setter)
        return obj
    }
    
    /* 对象字面量的 get/set：已有同名访问器时合并另一个方向，否则新建 */
    fun defineAccessor(obj: JSValue, key: JSValue, func: JSValue, isGetter: Boolean) {
        if (!JS_IsPtr(obj)) return
        val objPtr = JS_VALUE_TO_PTR(obj)
        if (ctx.memory.getMTag(objPtr) != JSMTags.JS_MTAG_OBJECT) return
        
        /* 查找自有属性值（不触发 getter） */
        var existing: JSValue? = null
        val props = ctx.memory.getJSValue(objPtr + 16)
        if (props != JS_NULL && props != ctx.emptyProps) {
            val propsPtr = JS_VALUE_TO_PTR(props)
            val hashMask = JS_VALUE_GET_INT(ctx.memory.getJSValue(propsPtr + 16))
            val h = hashProp(key, hashMask)
            var idx = JS_VALUE_GET_INT(ctx.memory.getJSValue(propsPtr + 24 + h * 8)) shr 1
            while (idx != 0) {
                val propPtr = propsPtr + 8 + idx * 8
                val propKey = ctx.memory.getJSValue(propPtr)
                if (propKey == key || jsPropKeyEquals(propKey, key)) {
                    existing = ctx.memory.getJSValue(propPtr + 8)
                    break
                }
                idx = JS_VALUE_GET_INT(ctx.memory.getJSValue(propPtr + 16)) shr 1
            }
        }
        
        if (existing != null && isAccessorValue(existing)) {
            val p = JS_VALUE_TO_PTR(existing)
            ctx.memory.putJSValue(p + if (isGetter) 24 else 32, func)
            return
        }
        val oldGetter = if (existing != null && isAccessorValue(existing))
            ctx.memory.getJSValue(JS_VALUE_TO_PTR(existing) + 24) else JS_UNDEFINED
        val oldSetter = if (existing != null && isAccessorValue(existing))
            ctx.memory.getJSValue(JS_VALUE_TO_PTR(existing) + 32) else JS_UNDEFINED
        val desc = JS_NewAccessor(if (isGetter) func else oldGetter,
                                  if (isGetter) oldSetter else func)
        if (JS_IsException(desc)) return
        setProperty(obj, key, desc)
    }
    
    /* 删除自有属性（不处理数组元素与原型） */
    fun deleteProperty(obj: JSValue, key: JSValue) {
        if (!JS_IsPtr(obj)) return
        val objPtr = JS_VALUE_TO_PTR(obj)
        if (ctx.memory.getMTag(objPtr) != JSMTags.JS_MTAG_OBJECT) return
        val props = ctx.memory.getJSValue(objPtr + 16)
        if (props == JS_NULL || props == ctx.emptyProps) return
        val propsPtr = JS_VALUE_TO_PTR(props)
        val hashMask = JS_VALUE_GET_INT(ctx.memory.getJSValue(propsPtr + 16))
        val h = hashProp(key, hashMask)
        var prevIdx = 0
        var idx = JS_VALUE_GET_INT(ctx.memory.getJSValue(propsPtr + 24 + h * 8)) shr 1
        while (idx != 0) {
            val propPtr = propsPtr + 8 + idx * 8
            val propKey = ctx.memory.getJSValue(propPtr)
            if (propKey == key || jsPropKeyEquals(propKey, key)) {
                val next = JS_VALUE_GET_INT(ctx.memory.getJSValue(propPtr + 16)) shr 1
                if (prevIdx == 0) {
                    ctx.memory.putJSValue(propsPtr + 24 + h * 8, JS_NewShortInt(next shl 1))
                } else {
                    ctx.memory.putJSValue(propsPtr + 8 + prevIdx * 8 + 16, JS_NewShortInt(next shl 1))
                }
                ctx.memory.putJSValue(propPtr, JS_UNINITIALIZED)
                ctx.memory.putJSValue(propPtr + 8, JS_UNDEFINED)
                val cnt = JS_VALUE_GET_INT(ctx.memory.getJSValue(propsPtr + 8)) - 1
                ctx.memory.putJSValue(propsPtr + 8, JS_NewShortInt(cnt))
                return
            }
            prevIdx = idx
            idx = JS_VALUE_GET_INT(ctx.memory.getJSValue(propPtr + 16)) shr 1
        }
    }
    
    fun jsValueEquals(a: JSValue, b: JSValue): Boolean {
        if (a == b) return true
        if (JS_IsString(ctx, a) && JS_IsString(ctx, b)) {
            return jsGetString(ctx, a) == jsGetString(ctx, b)
        }
        return false
    }
    
    /* 属性键等价：字符串键与数值键按 ToString 归一（a[1] ≡ a["1"]） */
    fun jsPropKeyEquals(a: JSValue, b: JSValue): Boolean {
        if (a == b) return true
        if (JS_IsString(ctx, a) && JS_IsString(ctx, b)) {
            return jsGetString(ctx, a) == jsGetString(ctx, b)
        }
        if (JS_IsString(ctx, a) && isNumberValue(b)) {
            return jsGetString(ctx, a) == jsNumberToString(toNumber(b))
        }
        if (isNumberValue(a) && JS_IsString(ctx, b)) {
            return jsNumberToString(toNumber(a)) == jsGetString(ctx, b)
        }
        if (isNumberValue(a) && isNumberValue(b)) {
            return toNumber(a) == toNumber(b)
        }
        return false
    }
    
    /* 检查对象（含原型链）是否存在指定属性（对照 C find_own_property 的原型遍历） */
    fun hasProperty(obj: JSValue, key: JSValue): Boolean {
        if (!JS_IsPtr(obj)) return false
        val objPtr = JS_VALUE_TO_PTR(obj)
        if (ctx.memory.getMTag(objPtr) != JSMTags.JS_MTAG_OBJECT) return false
        
        var props = ctx.memory.getJSValue(objPtr + 16)
        if (props != JS_NULL && props != ctx.emptyProps) {
            val propsPtr = JS_VALUE_TO_PTR(props)
            val hashMask = JS_VALUE_GET_INT(ctx.memory.getJSValue(propsPtr + 16))
            val h = hashProp(key, hashMask)
            var idx = JS_VALUE_GET_INT(ctx.memory.getJSValue(propsPtr + 24 + h * 8)) shr 1
            while (idx != 0) {
                val propPtr = propsPtr + 8 + idx * 8
                val propKey = ctx.memory.getJSValue(propPtr)
                if (propKey == key || jsPropKeyEquals(propKey, key)) {
                    return true
                }
                idx = JS_VALUE_GET_INT(ctx.memory.getJSValue(propPtr + 16)) shr 1
            }
        }
        
        /* 数组 length 回退（与 getProperty 保持一致，见其注释） */
        val classIdHp = ctx.memory.getU8(objPtr + 2)
        if (classIdHp == JSObjectClassEnum.JS_CLASS_ARRAY.value && JS_IsString(ctx, key) &&
            jsGetString(ctx, key) == "length"
        ) {
            return true
        }
        if (classIdHp == JSObjectClassEnum.JS_CLASS_ARRAY.value && JS_IsInt(key)) {
            val i = JS_VALUE_GET_INT(key)
            if (i >= 0 && i < ctx.memory.getI32(objPtr + 32)) return true
        }

        val proto = ctx.memory.getJSValue(objPtr + 8)
        if (proto != JS_NULL) {
            return hasProperty(proto, key)
        }
        return false
    }
    
    /* 规范数组索引字符串（"0"、"12"；不接受 "01"、"1.2"、"-1" 等） */
    fun arrayIndexFromString(s: String): Int {
        if (s.isEmpty() || s.length > 10) return -1
        if (s.length > 1 && s[0] == '0') return -1
        var v = 0L
        for (ch in s) {
            if (ch < '0' || ch > '9') return -1
            v = v * 10 + (ch - '0')
            if (v > 0xFFFFFFFEL) return -1
        }
        return v.toInt()
    }
    
    fun isArrayIndexValue(idx: JSValue): Boolean {
        if (JS_IsInt(idx)) return JS_VALUE_GET_INT(idx) >= 0
        if (JS_IsShortFloat(idx)) {
            val d = jsGetShortFloat(idx)
            return d >= 0.0 && d <= 0xFFFFFFFEL.toDouble() && d == Math.floor(d)
        }
        if (JS_IsPtr(idx)) {
            val p = JS_VALUE_TO_PTR(idx)
            when (ctx.memory.getMTag(p)) {
                JSMTags.JS_MTAG_FLOAT64 -> {
                    val d = ctx.memory.getFloat64(p + 8)
                    return d >= 0.0 && d <= 0xFFFFFFFEL.toDouble() && d == Math.floor(d)
                }
                JSMTags.JS_MTAG_STRING -> return arrayIndexFromString(jsGetString(ctx, idx)) >= 0
            }
        }
        return false
    }
    
    fun setProperty(obj: JSValue, key: JSValue, val1: JSValue) {
        /* obj/key/val1 保活：写入路径的 allocProps/扩容 malloc 可能触发 GC 搬移，
         * Kotlin 局部值会过期——内部通过保活列表读取最新地址 */
        ctx.gcKeepAlive.add(obj)
        ctx.gcKeepAlive.add(key)
        ctx.gcKeepAlive.add(val1)
        try {
            setPropertyInternal()
        } finally {
            repeat(3) { ctx.gcKeepAlive.removeAt(ctx.gcKeepAlive.size - 1) }
        }
    }
    
    private fun setPropertyInternal() {
        val obj = ctx.gcKeepAlive[ctx.gcKeepAlive.size - 3]
        var key = ctx.gcKeepAlive[ctx.gcKeepAlive.size - 2]
        var val1 = ctx.gcKeepAlive[ctx.gcKeepAlive.size - 1]
        
        if (!JS_IsPtr(obj)) return
        var objPtr = JS_VALUE_TO_PTR(obj)
        if (ctx.memory.getMTag(objPtr) != JSMTags.JS_MTAG_OBJECT) return
        
        /* 数组 length 赋值：调整长度并清除被截断的元素（对照 C 的 set_array_length） */
        if (ctx.memory.getU8(objPtr + 2) == JSObjectClassEnum.JS_CLASS_ARRAY.value &&
            JS_IsString(ctx, key) && jsGetString(ctx, key) == "length") {
            val newLen = toInt32(ctx.gcKeepAlive[ctx.gcKeepAlive.size - 1]).coerceAtLeast(0)
            val oldLen = ctx.memory.getI32(objPtr + 32)
            if (newLen < oldLen) {
                val data = ctx.memory.getJSValue(objPtr + 24)
                if (data != JS_NULL) {
                    val dataPtr = JS_VALUE_TO_PTR(data)
                    val cap = ctx.memory.getI32(dataPtr + 4)
                    for (i in newLen until minOf(oldLen, cap)) {
                        ctx.memory.putJSValue(dataPtr + 8 + i * 8, JS_UNDEFINED)
                    }
                }
            }
            ctx.memory.putI32(objPtr + 32, newLen)
            return
        }
        
        /* 数组的数字/规范索引键写入元素区 */
        if (ctx.memory.getU8(objPtr + 2) == JSObjectClassEnum.JS_CLASS_ARRAY.value) {
            var aidx = -1
            if (JS_IsInt(key)) aidx = JS_VALUE_GET_INT(key)
            else if (JS_IsString(ctx, key)) aidx = arrayIndexFromString(jsGetString(ctx, key))
            if (aidx >= 0) {
                setElement(ctx.gcKeepAlive[ctx.gcKeepAlive.size - 3], JS_NewShortInt(aidx),
                           ctx.gcKeepAlive[ctx.gcKeepAlive.size - 1])
                return
            }
        }
        
        var props = ctx.memory.getJSValue(objPtr + 16)
        if (props == JS_NULL || props == ctx.emptyProps) {
            props = allocProps(4)
            /* allocProps 的 malloc 可能触发 GC 搬移 obj/key/value：从保活列表刷新 */
            objPtr = JS_VALUE_TO_PTR(ctx.gcKeepAlive[ctx.gcKeepAlive.size - 3])
            key = ctx.gcKeepAlive[ctx.gcKeepAlive.size - 2]
            val1 = ctx.gcKeepAlive[ctx.gcKeepAlive.size - 1]
            ctx.memory.putJSValue(objPtr + 16, props)
        }
        
        var propsPtr = JS_VALUE_TO_PTR(props)
        var propCount = JS_VALUE_GET_INT(ctx.memory.getJSValue(propsPtr + 8))
        var hashMask = JS_VALUE_GET_INT(ctx.memory.getJSValue(propsPtr + 16))
        var size = ctx.memory.getI32(propsPtr + 4)
        
        val h = hashProp(key, hashMask)
        var idx = JS_VALUE_GET_INT(ctx.memory.getJSValue(propsPtr + 24 + h * 8)) shr 1
        
        while (idx != 0) {
            val propPtr = propsPtr + 8 + idx * 8
            val propKey = ctx.memory.getJSValue(propPtr)
            if (propKey == key || jsPropKeyEquals(propKey, key)) {
                val cur = ctx.memory.getJSValue(propPtr + 8)
                if (isAccessorValue(cur)) {
                    /* 访问器属性：调用 setter（this 为接收者），无 setter 时忽略赋值 */
                    val setter = ctx.memory.getJSValue(JS_VALUE_TO_PTR(cur) + 32)
                    if (JS_IsFunction(ctx, setter)) {
                        callFunction(setter,
                            listOf(ctx.gcKeepAlive[ctx.gcKeepAlive.size - 1]),
                            ctx.gcKeepAlive[ctx.gcKeepAlive.size - 3])
                    }
                    return
                }
                ctx.memory.putJSValue(propPtr + 8, val1)
                return
            }
            idx = JS_VALUE_GET_INT(ctx.memory.getJSValue(propPtr + 16)) shr 1
        }
        
        var lastPropPtr = propsPtr + 8 + (size - 3) * 8
        var lastKey = ctx.memory.getJSValue(lastPropPtr)
        
        if (lastKey != JS_UNINITIALIZED) {
            val newSize = size + 3
            val newProps = ctx.malloc(8 + newSize * 8, JSMTags.JS_MTAG_VALUE_ARRAY)
            if (newProps == 0) return
            
            /* 扩容的 malloc 可能触发 GC 搬移 obj/props：从保活列表刷新 obj 地址，
               并重新读取 obj 的 props 槽（GC 已将其更新为搬移后的 props 位置） */
            objPtr = JS_VALUE_TO_PTR(ctx.gcKeepAlive[ctx.gcKeepAlive.size - 3])
            key = ctx.gcKeepAlive[ctx.gcKeepAlive.size - 2]
            val1 = ctx.gcKeepAlive[ctx.gcKeepAlive.size - 1]
            props = ctx.memory.getJSValue(objPtr + 16)
            propsPtr = JS_VALUE_TO_PTR(props)
            
            ctx.memory.putI32(newProps + 4, newSize)
            for (i in 0 until size) {
                ctx.memory.putJSValue(newProps + 8 + i * 8, ctx.memory.getJSValue(propsPtr + 8 + i * 8))
            }
            for (i in size until newSize) {
                ctx.memory.putJSValue(newProps + 8 + i * 8, JS_NewShortInt(0))
            }
            
            ctx.memory.putJSValue(objPtr + 16, JS_VALUE_FROM_PTR(newProps))
            props = JS_VALUE_FROM_PTR(newProps)
            propsPtr = newProps
            
            propCount = JS_VALUE_GET_INT(ctx.memory.getJSValue(propsPtr + 8))
            hashMask = JS_VALUE_GET_INT(ctx.memory.getJSValue(propsPtr + 16))
            size = newSize
            
            lastPropPtr = propsPtr + 8 + (size - 3) * 8
            ctx.memory.putJSValue(lastPropPtr, JS_UNINITIALIZED)
            ctx.memory.putJSValue(lastPropPtr + 16, JS_NewShortInt((size - 3) shl 1))
            lastKey = JS_UNINITIALIZED
        }
        
        val firstFree = JS_VALUE_GET_INT(ctx.memory.getJSValue(lastPropPtr + 16)) shr 1
        
        if (firstFree + 3 > size) {
            return
        }
        
        val propPtr = propsPtr + 8 + firstFree * 8
        ctx.memory.putJSValue(propPtr, key)
        ctx.memory.putJSValue(propPtr + 8, val1)
        ctx.memory.putJSValue(propPtr + 16, ctx.memory.getJSValue(propsPtr + 24 + h * 8))
        ctx.memory.putJSValue(propsPtr + 24 + h * 8, JS_NewShortInt(firstFree shl 1))
        propCount++
        ctx.memory.putJSValue(propsPtr + 8, JS_NewShortInt(propCount))
        
        val nextFree = firstFree + 3
        if (nextFree < size) {
            ctx.memory.putJSValue(lastPropPtr + 16, JS_NewShortInt(nextFree shl 1))
        }
    }
    
    fun hashProp(prop: JSValue, hashMask: Int): Int {
        /* 属性键按字符串语义处理：数值键也要与等价的字符串键同桶 */
        val keyStr: String? = when {
            JS_IsString(ctx, prop) -> jsGetString(ctx, prop)
            JS_IsInt(prop) || JS_IsShortFloat(prop) -> jsNumberToString(toNumber(prop))
            JS_IsPtr(prop) && ctx.memory.getMTag(JS_VALUE_TO_PTR(prop)) == JSMTags.JS_MTAG_FLOAT64 ->
                jsNumberToString(toNumber(prop))
            else -> null
        }
        if (keyStr != null) {
            var h = 0
            for (c in keyStr) {
                h = (h * 31 + c.code) and hashMask
            }
            return h
        }
        return ((prop / JSW) xor (prop % JSW)).toInt() and hashMask
    }
    
    fun getFirstFree(propsPtr: Int): Int {
        val size = ctx.memory.getI32(propsPtr + 4)
        val lastPropPtr = propsPtr + 8 + (size - 3) * 8
        val key = ctx.memory.getJSValue(lastPropPtr)
        return if (key == JS_UNINITIALIZED) {
            JS_VALUE_GET_INT(ctx.memory.getJSValue(lastPropPtr + 16)) shr 1
        } else {
            size
        }
    }
    
    fun defineProperty(obj: JSValue, key: JSValue, val1: JSValue) {
        setProperty(obj, key, val1)
    }
    
    fun getElement(obj: JSValue, idx: JSValue): JSValue {
        /* 字符串索引：str[i] 返回单字符 */
        if (JS_IsString(ctx, obj)) return getProperty(obj, idx)
        if (!JS_IsPtr(obj)) return JS_UNDEFINED
        val objPtr = JS_VALUE_TO_PTR(obj)
        if (ctx.memory.getMTag(objPtr) != JSMTags.JS_MTAG_OBJECT) return JS_UNDEFINED
        
        val classId = ctx.memory.getU8(objPtr + 2)
        /* 类型化数组：元素读写走其 ArrayBuffer 存储 */
        val typedInfo = JS_GetOpaque(ctx, obj)
        if (typedInfo is JSStdlib.TypedArrayInfo) {
            val i = toInt32(idx)
            if (i < 0 || i >= typedInfo.length) return JS_UNDEFINED
            return JSStdlib.readTyped(ctx, typedInfo, i)
        }
        if (classId == JSObjectClassEnum.JS_CLASS_ARRAY.value) {
            val i = toInt32(idx)
            val arrLen = ctx.memory.getI32(objPtr + 32)
            val arrData = ctx.memory.getJSValue(objPtr + 24)
            if (arrData != JS_NULL && i >= 0 && i < arrLen) {
                val arrDataPtr = JS_VALUE_TO_PTR(arrData)
                val size = ctx.memory.getI32(arrDataPtr + 4)
                if (i < size) {
                    return ctx.memory.getJSValue(arrDataPtr + 8 + i * 8)
                }
                return JS_UNDEFINED
            }
        }
        
        return getProperty(obj, idx)
    }
    
    fun setElement(obj: JSValue, idx: JSValue, val1: JSValue) {
        if (!JS_IsPtr(obj)) return
        val objPtr = JS_VALUE_TO_PTR(obj)
        if (ctx.memory.getMTag(objPtr) != JSMTags.JS_MTAG_OBJECT) return
        
        val classId = ctx.memory.getU8(objPtr + 2)
        val typedInfo = JS_GetOpaque(ctx, obj)
        if (typedInfo is JSStdlib.TypedArrayInfo) {
            val i = toInt32(idx)
            if (i >= 0 && i < typedInfo.length) {
                JSStdlib.writeTyped(ctx, this, typedInfo, i, val1)
            }
            return
        }
        if (classId == JSObjectClassEnum.JS_CLASS_ARRAY.value) {
            val i = toInt32(idx)
            var arrData = ctx.memory.getJSValue(objPtr + 24)
            if (arrData == JS_NULL) {
                arrData = allocValueArray(i + 1)
                ctx.memory.putJSValue(objPtr + 24, arrData)
            }
            val arrDataPtr = JS_VALUE_TO_PTR(arrData)
            var size = ctx.memory.getI32(arrDataPtr + 4)
            if (i >= size) {
                arrData = resizeValueArray(arrData, i + 1)
                ctx.memory.putJSValue(objPtr + 24, arrData)
                size = i + 1
            }
            val finalArrDataPtr = JS_VALUE_TO_PTR(arrData)
            ctx.memory.putJSValue(finalArrDataPtr + 8 + i * 8, val1)
            
            val len = ctx.memory.getI32(objPtr + 32)
            if (i >= len) {
                ctx.memory.putI32(objPtr + 32, i + 1)
            }
            return
        }
        
        setProperty(obj, idx, val1)
    }
    
    fun getLength(obj: JSValue): JSValue {
        if (!JS_IsPtr(obj)) return JS_NewShortInt(0)
        val objPtr = JS_VALUE_TO_PTR(obj)
        if (ctx.memory.getMTag(objPtr) != JSMTags.JS_MTAG_OBJECT) return JS_NewShortInt(0)
        
        val classId = ctx.memory.getU8(objPtr + 2)
        if (classId == JSObjectClassEnum.JS_CLASS_ARRAY.value) {
            return JS_NewShortInt(ctx.memory.getI32(objPtr + 32))
        }
        if (classId == JSObjectClassEnum.JS_CLASS_STRING.value) {
            val len = ctx.memory.getI32(objPtr + 4)
            return JS_NewShortInt(len)
        }
        
        return getProperty(obj, JS_NewString(ctx, "length"))
    }
    
    /* 调用函数：thisObj 为调用方提供的 this（普通调用对照 C 压入的 JS_UNDEFINED，
     * 方法调用为对象本身，顶层默认为全局对象） */
    fun callFunction(func: JSValue, args: List<JSValue>, thisObj: JSValue = ctx.globalObj): JSValue {
        if (!JS_IsPtr(func)) return JS_ThrowTypeError(ctx, "not a function")
        val funcPtr = JS_VALUE_TO_PTR(func)
        
        if (ctx.memory.getMTag(funcPtr) == JSMTags.JS_MTAG_OBJECT) {
            val classId = ctx.memory.getU8(funcPtr + 2)
            if (classId == JSObjectClassEnum.JS_CLASS_C_FUNCTION.value) {
                val funcIdx = ctx.memory.getI32(funcPtr + 24)
                val params = ctx.memory.getJSValue(funcPtr + 28)
                return callCFunction(funcIdx, thisObj, args, 0, params)
            }
            if (classId == JSObjectClassEnum.JS_CLASS_CLOSURE.value) {
                /* 闭包对象：extra 槽 0 存函数字节码 */
                val funcBytecode = ctx.memory.getJSValue(funcPtr + 24)
                return run(funcBytecode, thisObj, args, func)
            }
        }
        
        if (ctx.memory.getMTag(funcPtr) == JSMTags.JS_MTAG_FUNCTION_BYTECODE) {
            return run(func, thisObj, args)
        }
        
        return JS_ThrowTypeError(ctx, "not a function")
    }
    
    fun callMethod(func: JSValue, thisObj: JSValue, args: List<JSValue>): JSValue {
        if (!JS_IsFunction(ctx, func) && System.getenv("MQJS_THROW_TRACE") != null) {
            val tp = if (JS_IsPtr(thisObj)) JS_VALUE_TO_PTR(thisObj) else -1
            val mt = if (tp in ctx.heapBase until ctx.memory.size) ctx.memory.getMTag(tp) else -9
            val proto = if (JS_IsPtr(thisObj) && mt == JSMTags.JS_MTAG_OBJECT) ctx.memory.getJSValue(tp + 8) else -1
            System.err.println("CALL_METHOD nonfunc func=$func this=$thisObj mtag=$mt heapFree=${ctx.heapFree} proto=$proto stack=${ctx.jsCallStack}")
        }
        if (!JS_IsPtr(func)) return JS_ThrowTypeError(ctx, "not a function")
        val funcPtr = JS_VALUE_TO_PTR(func)
        
        if (ctx.memory.getMTag(funcPtr) == JSMTags.JS_MTAG_OBJECT) {
            val classId = ctx.memory.getU8(funcPtr + 2)
            if (classId == JSObjectClassEnum.JS_CLASS_C_FUNCTION.value) {
                val funcIdx = ctx.memory.getI32(funcPtr + 24)
                val params = ctx.memory.getJSValue(funcPtr + 28)
                return callCFunction(funcIdx, thisObj, args, 0, params)
            }
        }
        
        return callFunction(func, args, thisObj)
    }
    
    /* 构造器调用（对照 C OP_call_constructor：call_flags |= FRAME_CF_CTOR）：
     * - C 构造器：this 为 JS_UNDEFINED（对照 C generic_function_call 压入的 this），
     *   argc 带 FRAME_CF_CTOR 位，返回值直接作为 new 结果
     * - JS 构造器（对照 C js_call_constructor_start）：创建 this（proto 取函数的
     *   prototype 属性，非对象则回退 Object.prototype），传入执行；
     *   返回值为对象时用返回值，否则用 this
     * - 非 C 函数对象：回退普通调用 */
    fun callConstructor(func: JSValue, args: List<JSValue>): JSValue {
        if (!JS_IsPtr(func)) return JS_ThrowTypeError(ctx, "not a constructor")
        val funcPtr = JS_VALUE_TO_PTR(func)
        
        if (ctx.memory.getMTag(funcPtr) == JSMTags.JS_MTAG_OBJECT) {
            val classId = ctx.memory.getU8(funcPtr + 2)
            if (classId == JSObjectClassEnum.JS_CLASS_C_FUNCTION.value) {
                val funcIdx = ctx.memory.getI32(funcPtr + 24)
                val params = ctx.memory.getJSValue(funcPtr + 28)
                return callCFunction(funcIdx, JS_UNDEFINED, args, FRAME_CF_CTOR, params)
            }
            if (classId == JSObjectClassEnum.JS_CLASS_CLOSURE.value) {
                /* 创建 this：proto 取函数的 prototype 属性，非对象回退 Object.prototype */
                val proto = getProperty(func, JS_NewString(ctx, "prototype"))
                val protoVal = if (JS_IsPtr(proto) &&
                    ctx.memory.getMTag(JS_VALUE_TO_PTR(proto)) == JSMTags.JS_MTAG_OBJECT) {
                    proto
                } else {
                    ctx.classProto[JSObjectClassEnum.JS_CLASS_OBJECT.value]
                }
                val thisObj = JS_NewObjectProtoClass(ctx, protoVal,
                                                     JSObjectClassEnum.JS_CLASS_OBJECT.value, 0)
                if (JS_IsException(thisObj)) return JS_EXCEPTION
                
                val r = callFunction(func, args, thisObj)
                if (JS_IsException(r)) return JS_EXCEPTION
                if (System.getenv("MQJS_TRACE") != null) {
                    println("TRACE ctor: thisObj=${JS_VALUE_TO_PTR(thisObj)} r=${r} rIsPtr=${JS_IsPtr(r)}")
                    val pv = getProperty(thisObj, JS_NewString(ctx, "v"))
                    println("TRACE ctor: thisObj.v after body=${if (JS_IsUndefined(pv)) "undef" else pv}")
                }
                /* 返回值为对象则采用之，否则用 this（对照 ES new 语义） */
                return if (JS_IsPtr(r) &&
                    ctx.memory.getMTag(JS_VALUE_TO_PTR(r)) == JSMTags.JS_MTAG_OBJECT) {
                    r
                } else {
                    thisObj
                }
            }
        }
        
        return JS_ThrowTypeError(ctx, "not a constructor")
    }
    
    /* 调用 C 函数（对照 C JS_Call 的 c_function 路径）：
     * 按 defType 分发到对应的函数形态；callFlags 携带 FRAME_CF_CTOR 等调用标志，
     * 传给 C 函数的 argc 同时包含个数与标志位（对照 C call_flags & (CTOR | ARGC_MASK)）。
     * f_f 路径采用上游 49f1536 修复语义：参数转换异常时不得用计算结果覆盖异常值 */
    fun callCFunction(idx: Int, thisObj: JSValue, args: List<JSValue>,
                      callFlags: Int = 0, params: JSValue = JS_UNDEFINED): JSValue {
        /* 函数定义查找：先查建上下文时的定长基础表，超出范围再查桥接层扩展表
         * （extFunctionTable 的槽位编号从基础表长度起算，见 JSContext 注释） */
        val base = ctx.cFunctionTable
        val def = if (base != null && idx < base.size) {
            base[idx]
        } else {
            ctx.extFunctionTable.getOrNull(idx - (base?.size ?: 0))
        } ?: return JS_ThrowTypeError(ctx, "invalid c function index")
        val callArgc = (args.size and FRAME_CF_ARGC_MASK) or callFlags
        /* C 函数实参保活：原生函数内部可能分配并触发 GC，参数/this 必须可被
         * 标记与搬移更新（native() 通过 ArgsView 按槽位读取最新值） */
        val keep = ctx.gcKeepAlive.size
        ctx.gcKeepAlive.add(thisObj)
        for (a in args) ctx.gcKeepAlive.add(a)
        val savedArgBase = ctx.cArgBase
        ctx.cArgBase = keep
        try {
            val freshThis = ctx.gcKeepAlive[keep]
            val argv = LongArray(args.size) { ctx.gcKeepAlive[keep + 1 + it] }
            return when (def.defType) {
                JSCFunctionDefEnum.JS_CFUNC_generic.value,
                JSCFunctionDefEnum.JS_CFUNC_constructor.value ->
                    def.func?.invoke(ctx, freshThis, callArgc, argv) ?: JS_UNDEFINED
                JSCFunctionDefEnum.JS_CFUNC_generic_magic.value,
                JSCFunctionDefEnum.JS_CFUNC_constructor_magic.value ->
                    def.funcMagic?.invoke(ctx, freshThis, callArgc, argv, def.magic) ?: JS_UNDEFINED
                JSCFunctionDefEnum.JS_CFUNC_generic_params.value ->
                    def.funcParams?.invoke(ctx, freshThis, callArgc, argv, params) ?: JS_UNDEFINED
                JSCFunctionDefEnum.JS_CFUNC_f_f.value -> {
                    val d = JS_ToNumber(ctx, ctx.gcKeepAlive[keep + 1])
                    JS_NewFloat64(ctx, def.funcFF?.invoke(d) ?: Double.NaN)
                }
                else -> JS_UNDEFINED
            }
        } finally {
            ctx.cArgBase = savedArgBase
            while (ctx.gcKeepAlive.size > keep) {
                ctx.gcKeepAlive.removeAt(ctx.gcKeepAlive.size - 1)
            }
        }
    }
    
    fun allocProps(n: Int): JSValue {
        if (n <= 0) return JS_NULL
        val hashSizeLog2 = if (n <= 1) 0 else (32 - Integer.numberOfLeadingZeros(n - 1)) - 1
        val hashMask = (1 shl hashSizeLog2) - 1
        val firstFree = 2 + hashMask + 1
        val size = firstFree + 3 * n
        
        val ptr = ctx.malloc(8 + size * 8, JSMTags.JS_MTAG_VALUE_ARRAY)
        if (ptr == 0) return JS_NULL
        
        ctx.memory.putI32(ptr + 4, size)
        ctx.memory.putJSValue(ptr + 8, JS_NewShortInt(0))  // prop_count = 0
        ctx.memory.putJSValue(ptr + 16, JS_NewShortInt(hashMask))  // hash_mask
        
        // 初始化哈希表
        for (i in 0..hashMask) {
            ctx.memory.putJSValue(ptr + 24 + i * 8, JS_NewShortInt(0))
        }
        
        // 初始化属性槽
        for (i in 0 until n) {
            val propOffset = ptr + 8 + (2 + hashMask + 1 + 3 * i) * 8
            ctx.memory.putJSValue(propOffset, JS_UNINITIALIZED)  // key
            ctx.memory.putJSValue(propOffset + 8, JS_UNDEFINED)  // value
            ctx.memory.putJSValue(propOffset + 16, JS_NewShortInt(0))  // hash_next
        }
        
        // 设置最后一个属性的 hash_next 为 firstFree << 1
        val lastPropOffset = ptr + 8 + (2 + hashMask + 1 + 3 * (n - 1)) * 8
        ctx.memory.putJSValue(lastPropOffset + 16, JS_NewShortInt(firstFree shl 1))
        
        return JS_VALUE_FROM_PTR(ptr)
    }
    
    fun allocValueArray(size: Int): JSValue {
        val ptr = ctx.malloc(8 + size * 8, JSMTags.JS_MTAG_VALUE_ARRAY)
        if (ptr == 0) return JS_NULL
        ctx.memory.putI32(ptr + 4, size)
        for (i in 0 until size) {
            ctx.memory.putJSValue(ptr + 8 + i * 8, JS_UNDEFINED)
        }
        return JS_VALUE_FROM_PTR(ptr)
    }
    
    fun resizeValueArray(val1: JSValue, newSize: Int): JSValue {
        val oldPtr = JS_VALUE_TO_PTR(val1)
        val oldSize = ctx.memory.getI32(oldPtr + 4)
        val newPtr = ctx.malloc(8 + newSize * 8, JSMTags.JS_MTAG_VALUE_ARRAY)
        if (newPtr == 0) return val1
        ctx.memory.putI32(newPtr + 4, newSize)
        for (i in 0 until minOf(oldSize, newSize)) {
            ctx.memory.putJSValue(newPtr + 8 + i * 8, ctx.memory.getJSValue(oldPtr + 8 + i * 8))
        }
        for (i in oldSize until newSize) {
            ctx.memory.putJSValue(newPtr + 8 + i * 8, JS_UNDEFINED)
        }
        return JS_VALUE_FROM_PTR(newPtr)
    }
    
    fun jsStringEquals(a: JSValue, b: JSValue): Boolean {
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
