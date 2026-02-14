package com.mquickjs.runtime

import com.mquickjs.*
import com.mquickjs.JSMTags
import com.mquickjs.memory.getMTag
import com.mquickjs.OPCodeEnum

class JSRuntime(private val ctx: JSContext) {
    
    fun run(func: JSValue): JSValue {
        val funcPtr = JS_VALUE_TO_PTR(func)
        if (ctx.memory.getMTag(funcPtr) != JSMTags.JS_MTAG_FUNCTION_BYTECODE) {
            return JS_EXCEPTION
        }
        
        val byteCode = ctx.memory.getJSValue(funcPtr + 16)
        if (byteCode == JS_NULL) return JS_UNDEFINED
        
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
        var loopCount = 0
        val maxLoopCount = 100000
        
        while (pc < code.size) {
            loopCount++
            if (loopCount > maxLoopCount) {
                println("Error: Infinite loop detected, pc=$pc, op=${code[pc].toInt() and 0xFF}")
                return JS_EXCEPTION
            }
            
            val op = code[pc].toInt() and 0xFF
            pc++
            
            when (op) {
                OPCodeEnum.OP_undefined.id -> stack.add(JS_UNDEFINED)
                OPCodeEnum.OP_null.id -> stack.add(JS_NULL)
                OPCodeEnum.OP_push_false.id -> stack.add(JS_FALSE)
                OPCodeEnum.OP_push_true.id -> stack.add(JS_TRUE)
                OPCodeEnum.OP_push_this.id -> stack.add(ctx.globalObj)
                
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
                
                OPCodeEnum.OP_push_const.id, OPCodeEnum.OP_fclosure.id -> {
                    val idx = (code[pc].toInt() and 0xFF) or ((code[pc + 1].toInt() and 0xFF) shl 8)
                    pc += 2
                    val cpool = ctx.memory.getJSValue(funcPtr + 24)
                    if (cpool != JS_NULL) {
                        val cpoolPtr = JS_VALUE_TO_PTR(cpool)
                        stack.add(ctx.memory.getJSValue(cpoolPtr + 8 + idx * 8))
                    } else {
                        stack.add(JS_UNDEFINED)
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
                    val cpool = ctx.memory.getJSValue(funcPtr + 24)
                    val key = if (cpool != JS_NULL) {
                        val cpoolPtr = JS_VALUE_TO_PTR(cpool)
                        ctx.memory.getJSValue(cpoolPtr + 8 + idx * 8)
                    } else JS_UNDEFINED
                    stack.add(getProperty(obj, key))
                }
                
                OPCodeEnum.OP_get_field2.id -> {
                    val idx = readU16(code, pc)
                    pc += 2
                    if (stack.size < 2) {
                        return JS_EXCEPTION
                    }
                    // 对应C语言的sp--和sp[0] = sp[1]
                    // 在Kotlin中，我们需要移除栈顶元素，然后获取新的栈顶元素
                    val obj = stack[stack.size - 2] // 获取原来的栈顶下一个元素
                    stack.removeLast() // 移除栈顶元素
                    val cpool = ctx.memory.getJSValue(funcPtr + 24)
                    val key = if (cpool != JS_NULL) {
                        val cpoolPtr = JS_VALUE_TO_PTR(cpool)
                        ctx.memory.getJSValue(cpoolPtr + 8 + idx * 8)
                    } else JS_UNDEFINED
                    val val1 = getProperty(obj, key)
                    stack[stack.size - 1] = val1 // 用属性值替换栈顶的对象
                }
                
                OPCodeEnum.OP_put_field.id -> {
                    val idx = readU16(code, pc)
                    pc += 2
                    val val1 = stack.removeLast()
                    val obj = stack.removeLast()
                    val cpool = ctx.memory.getJSValue(funcPtr + 24)
                    val key = if (cpool != JS_NULL) {
                        val cpoolPtr = JS_VALUE_TO_PTR(cpool)
                        ctx.memory.getJSValue(cpoolPtr + 8 + idx * 8)
                    } else JS_UNDEFINED
                    setProperty(obj, key, val1)
                }
                
                OPCodeEnum.OP_define_field.id -> {
                    val idx = readU16(code, pc)
                    pc += 2
                    val val1 = stack.removeLast()
                    val obj = stack.removeLast()
                    val cpool = ctx.memory.getJSValue(funcPtr + 24)
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
                    if (stack.size < 2) {
                        return JS_EXCEPTION
                    }
                    val idx = stack[stack.size - 1]
                    val obj = stack[stack.size - 2]
                    val val1 = getElement(obj, idx)
                    stack[stack.size - 2] = val1
                    stack.removeAt(stack.size - 1)
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
                    stack.add(callFunction(funcVal, args))
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
                    stack.add(callMethod(funcVal, thisObj, args))
                }
                
                OPCodeEnum.OP_call_constructor.id -> {
                    val argc = readU16(code, pc)
                    pc += 2
                    val args = mutableListOf<JSValue>()
                    for (i in 0 until argc) {
                        args.add(0, stack.removeLast())
                    }
                    val funcVal = stack.removeLast()
                    stack.add(callConstructor(funcVal, args))
                }
            }
        }
        
        return if (stack.isEmpty()) JS_UNDEFINED else stack.last()
    }
    
    private fun readU16(code: ByteArray, pc: Int): Int {
        return (code[pc].toInt() and 0xFF) or ((code[pc + 1].toInt() and 0xFF) shl 8)
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
    
    fun setProperty(obj: JSValue, key: JSValue, val1: JSValue) {
        if (!JS_IsPtr(obj)) return
        val objPtr = JS_VALUE_TO_PTR(obj)
        if (ctx.memory.getMTag(objPtr) != JSMTags.JS_MTAG_OBJECT) return
        
        var props = ctx.memory.getJSValue(objPtr + 16)
        if (props == JS_NULL || props == ctx.emptyProps) {
            props = allocProps(4)
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
    
    fun callFunction(func: JSValue, args: List<JSValue>): JSValue {
        if (!JS_IsPtr(func)) return JS_EXCEPTION
        val funcPtr = JS_VALUE_TO_PTR(func)
        
        if (ctx.memory.getMTag(funcPtr) == JSMTags.JS_MTAG_OBJECT) {
            val classId = ctx.memory.getU8(funcPtr + 2)
            if (classId == JSObjectClassEnum.JS_CLASS_C_FUNCTION.value) {
                val funcIdx = ctx.memory.getI32(funcPtr + 24)
                return callCFunction(funcIdx, ctx.globalObj, args)
            }
        }
        
        if (ctx.memory.getMTag(funcPtr) == JSMTags.JS_MTAG_FUNCTION_BYTECODE) {
            return run(func)
        }
        
        return JS_EXCEPTION
    }
    
    fun callMethod(func: JSValue, thisObj: JSValue, args: List<JSValue>): JSValue {
        if (!JS_IsPtr(func)) return JS_EXCEPTION
        val funcPtr = JS_VALUE_TO_PTR(func)
        
        if (ctx.memory.getMTag(funcPtr) == JSMTags.JS_MTAG_OBJECT) {
            val classId = ctx.memory.getU8(funcPtr + 2)
            if (classId == JSObjectClassEnum.JS_CLASS_C_FUNCTION.value) {
                val funcIdx = ctx.memory.getI32(funcPtr + 24)
                return callCFunction(funcIdx, thisObj, args)
            }
        }
        
        return callFunction(func, args)
    }
    
    fun callConstructor(func: JSValue, args: List<JSValue>): JSValue {
        if (!JS_IsPtr(func)) return JS_EXCEPTION
        val funcPtr = JS_VALUE_TO_PTR(func)
        
        if (ctx.memory.getMTag(funcPtr) == JSMTags.JS_MTAG_OBJECT) {
            val classId = ctx.memory.getU8(funcPtr + 2)
            if (classId == JSObjectClassEnum.JS_CLASS_C_FUNCTION.value) {
                return JS_NewObject(ctx)
            }
        }
        
        return callFunction(func, args)
    }
    
    fun callCFunction(idx: Int, thisObj: JSValue, args: List<JSValue>): JSValue {
        if (ctx.cFunctionTable == null || idx >= ctx.cFunctionTable.size) return JS_UNDEFINED
        val def = ctx.cFunctionTable[idx]
        if (def.func != null) {
            return def.func.invoke(ctx, thisObj, args.size, args.toLongArray())
        }
        return JS_UNDEFINED
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
