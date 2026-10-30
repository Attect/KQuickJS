package app.muka.project.kquickjs.runtime

import app.muka.project.kquickjs.*
import app.muka.project.kquickjs.JSMTags
import app.muka.project.kquickjs.memory.getMTag
import app.muka.project.kquickjs.OPCodeEnum

class JSRuntime(private val ctx: JSContext) {
    
    /* 顶层入口：this 默认为全局对象（对照 C JS_Eval 的顶层帧） */
    fun run(func: JSValue): JSValue = run(func, ctx.globalObj)
    
    fun run(func: JSValue, thisObj: JSValue): JSValue = run(func, thisObj, emptyList())
    
    fun run(func: JSValue, thisObj: JSValue, args: List<JSValue>): JSValue {
        /* 维护 JS 调用栈记录（Kotlin 无 C 的 fp 帧链，用于生成错误对象的 stack 回溯） */
        ctx.jsCallStack.addLast(readFuncName(func))
        try {
            return runBody(func, thisObj, args)
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
    
    private fun runBody(func: JSValue, thisObj: JSValue, args: List<JSValue>): JSValue {
        /* func/thisObj/args 元素加入 GC 保活列表：函数体执行期间的分配可能触发
         * GC 压缩搬移，Kotlin 局部值会过期——执行中通过保活列表读取最新地址 */
        val keepStart = ctx.gcKeepAlive.size
        ctx.gcKeepAlive.add(func)
        ctx.gcKeepAlive.add(thisObj)
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
        /* 函数参数填入 vars 前段（bytecode 头 u16 的高位存 argCount） */
        val argCount = ctx.memory.getU16(funcPtr) shr 4
        for (i in 0 until minOf(args.size, argCount, varsSize)) {
            vars[i] = args[i]
        }
        var loopCount = 0
        val maxLoopCount = 100000
        
        try {
        while (pc < code.size) {
            loopCount++
            if (loopCount > maxLoopCount) {
                println("Error: Infinite loop detected, pc=$pc, op=${code[pc].toInt() and 0xFF}")
                return JS_EXCEPTION
            }
            
            /* GC 可能在上一条指令的分配中触发并搬移对象：从保活列表刷新
             * func 的最新地址（funcPtr 随之重算）；thisObj 由 OP_push_this
             * 从保活列表读取 */
            funcKept = ctx.gcKeepAlive[keepStart]
            thisObjKept = ctx.gcKeepAlive[keepStart + 1]
            val iterFuncPtr = JS_VALUE_TO_PTR(funcKept)
            
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
                    val cpool = ctx.memory.getJSValue(iterFuncPtr + 24)
                    val name = if (cpool != JS_NULL) {
                        ctx.memory.getJSValue(JS_VALUE_TO_PTR(cpool) + 8 + idx * 8)
                    } else JS_UNDEFINED
                    val v = getProperty(ctx.globalObj, name)
                    if (JS_IsUndefined(v) && !hasProperty(ctx.globalObj, name)) {
                        return JS_ThrowReferenceError(ctx, "'${jsGetString(ctx, name)}' is not defined")
                    }
                    stack.add(v)
                }
                
                OPCodeEnum.OP_put_global.id -> {
                    val idx = readU16(code, pc)
                    pc += 2
                    val v = stack.removeLast()
                    val cpool = ctx.memory.getJSValue(iterFuncPtr + 24)
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
                    val cpool = ctx.memory.getJSValue(iterFuncPtr + 24)
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
                    val cpool = ctx.memory.getJSValue(iterFuncPtr + 24)
                    val funcBytecode = if (cpool != JS_NULL) {
                        ctx.memory.getJSValue(JS_VALUE_TO_PTR(cpool) + 8 + idx * 8)
                    } else JS_NULL
                    
                    val closure = JS_NewObjectProtoClass(ctx, JS_NULL,
                                                         JSObjectClassEnum.JS_CLASS_CLOSURE.value, 1)
                    if (JS_IsException(closure)) return JS_EXCEPTION
                    val closurePtr = JS_VALUE_TO_PTR(closure)
                    ctx.memory.putJSValue(closurePtr + 24, funcBytecode)
                    stack.add(closure)
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
                    stack.add(if (a == b) JS_TRUE else JS_FALSE)
                }
                
                OPCodeEnum.OP_strict_neq.id -> {
                    val b = stack.removeLast()
                    val a = stack.removeLast()
                    stack.add(if (a == b) JS_FALSE else JS_TRUE)
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
                
                OPCodeEnum.OP_object.id -> {
                    val n = readU16(code, pc)
                    pc += 2
                    stack.add(JS_NewObject(ctx))
                }
                
                OPCodeEnum.OP_array_from.id -> {
                    val argc = readU16(code, pc)
                    pc += 2
                    val arr = JS_NewArray(ctx, argc)
                    val arrPtr = JS_VALUE_TO_PTR(arr)
                    val arrData = ctx.memory.getJSValue(arrPtr + 24)
                    for (i in argc - 1 downTo 0) {
                        val v = stack.removeLast()
                        if (arrData != JS_NULL) {
                            val arrDataPtr = JS_VALUE_TO_PTR(arrData)
                            ctx.memory.putJSValue(arrDataPtr + 8 + i * 8, v)
                        }
                    }
                    stack.add(arr)
                }
                
                OPCodeEnum.OP_get_field.id -> {
                    val idx = readU16(code, pc)
                    pc += 2
                    val obj = stack.removeLast()
                    val cpool = ctx.memory.getJSValue(iterFuncPtr + 24)
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
                    val cpool = ctx.memory.getJSValue(iterFuncPtr + 24)
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
                    val cpool = ctx.memory.getJSValue(iterFuncPtr + 24)
                    val key = if (cpool != JS_NULL) {
                        val cpoolPtr = JS_VALUE_TO_PTR(cpool)
                        ctx.memory.getJSValue(cpoolPtr + 8 + idx * 8)
                    } else JS_UNDEFINED
                    setProperty(obj, key, val1)
                    if (System.getenv("MQJS_TRACE") != null) {
                        println("TRACE put_field obj=${if (JS_IsPtr(obj)) JS_VALUE_TO_PTR(obj) else obj} key='${jsGetString(ctx, key)}' val=${if (JS_IsInt(val1)) JS_VALUE_GET_INT(val1) else -1}")
                    }
                }
                
                OPCodeEnum.OP_define_field.id -> {
                    val idx = readU16(code, pc)
                    pc += 2
                    val val1 = stack.removeLast()
                    val obj = stack.removeLast()
                    val cpool = ctx.memory.getJSValue(iterFuncPtr + 24)
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
                    setElement(obj, idx, val1)
                }
                
                OPCodeEnum.OP_get_length.id -> {
                    val obj = stack.removeLast()
                    stack.add(getLength(obj))
                }
                
                OPCodeEnum.OP_get_length2.id -> {
                    val obj = stack.last()
                    stack.add(getLength(obj))
                }
                
                OPCodeEnum.OP_get_loc.id -> {
                    val idx = readU16(code, pc)
                    pc += 2
                    val v = vars[idx]
                    stack.add(v)
                }
                
                OPCodeEnum.OP_put_loc.id -> {
                    val idx = readU16(code, pc)
                    pc += 2
                    val val1 = stack.removeLast()
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
            /* 移除本层的 func/thisObj/args 保活（递归时每层有自己的 keepStart 区间） */
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
    
    fun toNumber(v: JSValue): Double {
        if (JS_IsInt(v)) return JS_VALUE_GET_INT(v).toDouble()
        if (JS_IsShortFloat(v)) return jsGetShortFloat(v)
        if (JS_IsPtr(v)) {
            val ptr = JS_VALUE_TO_PTR(v)
            if (ctx.memory.getMTag(ptr) == JSMTags.JS_MTAG_FLOAT64) {
                return ctx.memory.getFloat64(ptr + 8)
            }
        }
        if (JS_IsNull(v)) return 0.0
        if (JS_IsUndefined(v)) return Double.NaN
        if (JS_IsBool(v)) return if (JS_VALUE_GET_SPECIAL_VALUE(v) != 0) 1.0 else 0.0
        return Double.NaN
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
        return d.toInt()
    }
    
    fun toUInt32(v: JSValue): Long {
        val d = toNumber(v)
        if (d.isNaN() || d.isInfinite()) return 0
        return d.toLong() and 0xFFFFFFFFL
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
        
        if (JS_IsString(ctx, a) || JS_IsString(ctx, b)) {
            val sa = jsGetString(ctx, a)
            val sb = jsGetString(ctx, b)
            return JS_NewString(ctx, sa + sb)
        }
        
        val na = toNumber(a)
        val nb = toNumber(b)
        return JS_NewFloat64(ctx, na + nb)
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
        if (a == b) return JS_TRUE
        if (JS_IsNull(a) && JS_IsUndefined(b)) return JS_TRUE
        if (JS_IsUndefined(a) && JS_IsNull(b)) return JS_TRUE
        if (JS_IsInt(a) && JS_IsBool(b)) return if (JS_VALUE_GET_INT(a) == JS_VALUE_GET_SPECIAL_VALUE(b)) JS_TRUE else JS_FALSE
        if (JS_IsBool(a) && JS_IsInt(b)) return if (JS_VALUE_GET_SPECIAL_VALUE(a) == JS_VALUE_GET_INT(b)) JS_TRUE else JS_FALSE
        return if (toNumber(a) == toNumber(b)) JS_TRUE else JS_FALSE
    }
    
    fun jsGetString(ctx: JSContext, v: JSValue): String {
        if (!JS_IsPtr(v)) {
            if (JS_IsInt(v)) return JS_VALUE_GET_INT(v).toString()
            if (JS_IsNull(v)) return "null"
            if (JS_IsUndefined(v)) return "undefined"
            if (JS_IsBool(v)) return if (JS_VALUE_GET_SPECIAL_VALUE(v) != 0) "true" else "false"
            return ""
        }
        val ptr = JS_VALUE_TO_PTR(v)
        if (ctx.memory.getMTag(ptr) != JSMTags.JS_MTAG_STRING) {
            if (ctx.memory.getMTag(ptr) == JSMTags.JS_MTAG_FLOAT64) {
                return ctx.memory.getFloat64(ptr + 8).toString()
            }
            return "[object]"
        }
        val len = ctx.memory.getI32(ptr + 4)
        return ctx.memory.getString(ptr + 8, len)
    }
    
    fun getProperty(obj: JSValue, key: JSValue): JSValue {
        if (!JS_IsPtr(obj)) return JS_UNDEFINED
        val objPtr = JS_VALUE_TO_PTR(obj)
        if (ctx.memory.getMTag(objPtr) != JSMTags.JS_MTAG_OBJECT) return JS_UNDEFINED
        
        val props = ctx.memory.getJSValue(objPtr + 16)
        if (props == JS_NULL || props == ctx.emptyProps) return JS_UNDEFINED
        
        val propsPtr = JS_VALUE_TO_PTR(props)
        val hashMask = JS_VALUE_GET_INT(ctx.memory.getJSValue(propsPtr + 16))
        
        val h = hashProp(key, hashMask)
        var idx = JS_VALUE_GET_INT(ctx.memory.getJSValue(propsPtr + 24 + h * 8)) shr 1
        
        while (idx != 0) {
            val propPtr = propsPtr + 8 + idx * 8
            val propKey = ctx.memory.getJSValue(propPtr)
            if (propKey == key || jsValueEquals(propKey, key)) {
                return ctx.memory.getJSValue(propPtr + 8)
            }
            idx = JS_VALUE_GET_INT(ctx.memory.getJSValue(propPtr + 16)) shr 1
        }
        
        val proto = ctx.memory.getJSValue(objPtr + 8)
        if (proto != JS_NULL) {
            return getProperty(proto, key)
        }
        
        return JS_UNDEFINED
    }
    
    fun jsValueEquals(a: JSValue, b: JSValue): Boolean {
        if (a == b) return true
        if (JS_IsString(ctx, a) && JS_IsString(ctx, b)) {
            val sa = jsGetString(ctx, a)
            val sb = jsGetString(ctx, b)
            return sa == sb
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
                if (propKey == key || jsValueEquals(propKey, key)) {
                    return true
                }
                idx = JS_VALUE_GET_INT(ctx.memory.getJSValue(propPtr + 16)) shr 1
            }
        }
        
        val proto = ctx.memory.getJSValue(objPtr + 8)
        if (proto != JS_NULL) {
            return hasProperty(proto, key)
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
        val key = ctx.gcKeepAlive[ctx.gcKeepAlive.size - 2]
        val val1 = ctx.gcKeepAlive[ctx.gcKeepAlive.size - 1]
        
        if (!JS_IsPtr(obj)) return
        var objPtr = JS_VALUE_TO_PTR(obj)
        if (ctx.memory.getMTag(objPtr) != JSMTags.JS_MTAG_OBJECT) return
        
        var props = ctx.memory.getJSValue(objPtr + 16)
        if (props == JS_NULL || props == ctx.emptyProps) {
            props = allocProps(4)
            /* allocProps 的 malloc 可能触发 GC 搬移 obj：从保活列表刷新 obj 地址 */
            objPtr = JS_VALUE_TO_PTR(ctx.gcKeepAlive[ctx.gcKeepAlive.size - 3])
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
            if (propKey == key || jsValueEquals(propKey, key)) {
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
        /* 属性哈希基于 key 内容（字符串逐字符、数值取值本身），与堆地址无关；
         * hash_next 链存储的也是属性槽的相对索引——因此 GC 压缩搬移对象后
         * 属性表无需 rehash（对照 C js_rehash_props：C 的哈希基于指针所以需要） */
        if (JS_IsString(ctx, prop)) {
            val str = jsGetString(ctx, prop)
            var h = 0
            for (c in str) {
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
        if (!JS_IsPtr(obj)) return JS_UNDEFINED
        val objPtr = JS_VALUE_TO_PTR(obj)
        if (ctx.memory.getMTag(objPtr) != JSMTags.JS_MTAG_OBJECT) return JS_UNDEFINED
        
        val classId = ctx.memory.getU8(objPtr + 2)
        if (classId == JSObjectClassEnum.JS_CLASS_ARRAY.value) {
            val i = toInt32(idx)
            val arrData = ctx.memory.getJSValue(objPtr + 24)
            if (arrData != JS_NULL) {
                val arrDataPtr = JS_VALUE_TO_PTR(arrData)
                val size = ctx.memory.getI32(arrDataPtr + 4)
                if (i >= 0 && i < size) {
                    return ctx.memory.getJSValue(arrDataPtr + 8 + i * 8)
                }
            }
        }
        
        return getProperty(obj, idx)
    }
    
    fun setElement(obj: JSValue, idx: JSValue, val1: JSValue) {
        if (!JS_IsPtr(obj)) return
        val objPtr = JS_VALUE_TO_PTR(obj)
        if (ctx.memory.getMTag(objPtr) != JSMTags.JS_MTAG_OBJECT) return
        
        val classId = ctx.memory.getU8(objPtr + 2)
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
                return run(funcBytecode, thisObj, args)
            }
        }
        
        if (ctx.memory.getMTag(funcPtr) == JSMTags.JS_MTAG_FUNCTION_BYTECODE) {
            return run(func, thisObj, args)
        }
        
        return JS_ThrowTypeError(ctx, "not a function")
    }
    
    fun callMethod(func: JSValue, thisObj: JSValue, args: List<JSValue>): JSValue {
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
        if (ctx.cFunctionTable == null || idx >= ctx.cFunctionTable.size) {
            return JS_ThrowTypeError(ctx, "invalid c function index")
        }
        val def = ctx.cFunctionTable[idx]
        val callArgc = (args.size and FRAME_CF_ARGC_MASK) or callFlags
        val argv = args.toLongArray()
        return when (def.defType) {
            JSCFunctionDefEnum.JS_CFUNC_generic.value,
            JSCFunctionDefEnum.JS_CFUNC_constructor.value ->
                def.func?.invoke(ctx, thisObj, callArgc, argv) ?: JS_UNDEFINED
            JSCFunctionDefEnum.JS_CFUNC_generic_magic.value,
            JSCFunctionDefEnum.JS_CFUNC_constructor_magic.value ->
                def.funcMagic?.invoke(ctx, thisObj, callArgc, argv, def.magic) ?: JS_UNDEFINED
            JSCFunctionDefEnum.JS_CFUNC_generic_params.value ->
                def.funcParams?.invoke(ctx, thisObj, callArgc, argv, params) ?: JS_UNDEFINED
            JSCFunctionDefEnum.JS_CFUNC_f_f.value -> {
                val d = JS_ToNumber(ctx, args.getOrElse(0) { JS_UNDEFINED })
                JS_NewFloat64(ctx, def.funcFF?.invoke(d) ?: Double.NaN)
            }
            else -> JS_UNDEFINED
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
