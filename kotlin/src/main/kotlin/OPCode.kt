package com.mquickjs

enum class OPCodeEnum(val id: Int, val size: Int, val nPop: Int, val nPush: Int, val fmt: Int) {
    OP_invalid(0, 1, 0, 0, 0),
    OP_push_value(1, 5, 0, 1, 13),
    OP_push_const(2, 3, 0, 1, 7),
    OP_fclosure(3, 3, 0, 1, 7),
    OP_undefined(4, 1, 0, 1, 0),
    OP_null(5, 1, 0, 1, 0),
    OP_push_this(6, 1, 0, 1, 0),
    OP_push_false(7, 1, 0, 1, 0),
    OP_push_true(8, 1, 0, 1, 0),
    OP_object(9, 3, 0, 1, 5),
    OP_this_func(10, 1, 0, 1, 0),
    OP_arguments(11, 1, 0, 1, 0),
    OP_new_target(12, 1, 0, 1, 0),
    
    OP_drop(13, 1, 1, 0, 0),
    OP_nip(14, 1, 2, 1, 0),
    OP_dup(15, 1, 1, 2, 0),
    OP_dup1(16, 1, 2, 3, 0),
    OP_dup2(17, 1, 2, 4, 0),
    OP_insert2(18, 1, 2, 3, 0),
    OP_insert3(19, 1, 3, 4, 0),
    OP_perm3(20, 1, 3, 3, 0),
    OP_perm4(21, 1, 4, 4, 0),
    OP_swap(22, 1, 2, 2, 0),
    OP_rot3l(23, 1, 3, 3, 0),
    
    OP_call_constructor(24, 3, 1, 1, 9),
    OP_call(25, 3, 1, 1, 9),
    OP_call_method(26, 3, 2, 1, 9),
    OP_array_from(27, 3, 0, 1, 9),
    OP_return(28, 1, 1, 0, 0),
    OP_return_undef(29, 1, 0, 0, 0),
    OP_throw(30, 1, 1, 0, 0),
    OP_regexp(31, 1, 2, 1, 0),
    
    OP_get_field(32, 3, 1, 1, 7),
    OP_get_field2(33, 3, 1, 2, 7),
    OP_put_field(34, 3, 2, 0, 7),
    OP_get_array_el(35, 1, 2, 1, 0),
    OP_get_array_el2(36, 1, 2, 2, 0),
    OP_put_array_el(37, 1, 3, 0, 0),
    OP_get_length(38, 1, 1, 1, 0),
    OP_get_length2(39, 1, 1, 2, 0),
    OP_define_field(40, 3, 2, 1, 7),
    OP_define_getter(41, 3, 2, 1, 7),
    OP_define_setter(42, 3, 2, 1, 7),
    OP_set_proto(43, 1, 2, 1, 0),
    
    OP_get_loc(44, 3, 0, 1, 11),
    OP_put_loc(45, 3, 1, 0, 11),
    OP_get_arg(46, 3, 0, 1, 12),
    OP_put_arg(47, 3, 1, 0, 12),
    OP_get_var_ref(48, 3, 0, 1, 13),
    OP_put_var_ref(49, 3, 1, 0, 13),
    OP_get_var_ref_nocheck(50, 3, 0, 1, 13),
    OP_put_var_ref_nocheck(51, 3, 1, 0, 13),
    OP_if_false(52, 5, 1, 0, 14),
    OP_if_true(53, 5, 1, 0, 14),
    OP_goto(54, 5, 0, 0, 14),
    OP_catch(55, 5, 0, 1, 14),
    OP_gosub(56, 5, 0, 0, 14),
    OP_ret(57, 1, 1, 0, 0),
    
    OP_for_in_start(58, 1, 1, 1, 0),
    OP_for_of_start(59, 1, 1, 1, 0),
    OP_for_of_next(60, 1, 1, 3, 0),
    
    OP_neg(61, 1, 1, 1, 0),
    OP_plus(62, 1, 1, 1, 0),
    OP_dec(63, 1, 1, 1, 0),
    OP_inc(64, 1, 1, 1, 0),
    OP_post_dec(65, 1, 1, 2, 0),
    OP_post_inc(66, 1, 1, 2, 0),
    OP_not(67, 1, 1, 1, 0),
    OP_lnot(68, 1, 1, 1, 0),
    OP_typeof(69, 1, 1, 1, 0),
    OP_delete(70, 1, 2, 1, 0),
    
    OP_mul(71, 1, 2, 1, 0),
    OP_div(72, 1, 2, 1, 0),
    OP_mod(73, 1, 2, 1, 0),
    OP_add(74, 1, 2, 1, 0),
    OP_sub(75, 1, 2, 1, 0),
    OP_pow(76, 1, 2, 1, 0),
    OP_shl(77, 1, 2, 1, 0),
    OP_sar(78, 1, 2, 1, 0),
    OP_shr(79, 1, 2, 1, 0),
    OP_lt(80, 1, 2, 1, 0),
    OP_lte(81, 1, 2, 1, 0),
    OP_gt(82, 1, 2, 1, 0),
    OP_gte(83, 1, 2, 1, 0),
    OP_instanceof(84, 1, 2, 1, 0),
    OP_in(85, 1, 2, 1, 0),
    OP_eq(86, 1, 2, 1, 0),
    OP_neq(87, 1, 2, 1, 0),
    OP_strict_eq(88, 1, 2, 1, 0),
    OP_strict_neq(89, 1, 2, 1, 0),
    OP_and(90, 1, 2, 1, 0),
    OP_xor(91, 1, 2, 1, 0),
    OP_or(92, 1, 2, 1, 0),
    OP_nop(93, 1, 0, 0, 0),
    
    OP_push_minus1(94, 1, 0, 1, 1),
    OP_push_0(95, 1, 0, 1, 1),
    OP_push_1(96, 1, 0, 1, 1),
    OP_push_2(97, 1, 0, 1, 1),
    OP_push_3(98, 1, 0, 1, 1),
    OP_push_4(99, 1, 0, 1, 1),
    OP_push_5(100, 1, 0, 1, 1),
    OP_push_6(101, 1, 0, 1, 1),
    OP_push_7(102, 1, 0, 1, 1),
    OP_push_i8(103, 2, 0, 1, 3),
    OP_push_i16(104, 3, 0, 1, 4),
    OP_push_const8(105, 2, 0, 1, 6),
    OP_fclosure8(106, 2, 0, 1, 6),
    OP_push_empty_string(107, 1, 0, 1, 0),
    
    OP_get_loc8(108, 2, 0, 1, 2),
    OP_put_loc8(109, 2, 1, 0, 2),
    
    OP_get_loc0(110, 1, 0, 1, 0),
    OP_get_loc1(111, 1, 0, 1, 0),
    OP_get_loc2(112, 1, 0, 1, 0),
    OP_get_loc3(113, 1, 0, 1, 0),
    OP_put_loc0(114, 1, 1, 0, 0),
    OP_put_loc1(115, 1, 1, 0, 0),
    OP_put_loc2(116, 1, 1, 0, 0),
    OP_put_loc3(117, 1, 1, 0, 0),
    OP_get_arg0(118, 1, 0, 1, 0),
    OP_get_arg1(119, 1, 0, 1, 0),
    OP_get_arg2(120, 1, 0, 1, 0),
    OP_get_arg3(121, 1, 0, 1, 0),
    OP_put_arg0(122, 1, 1, 0, 0),
    OP_put_arg1(123, 1, 1, 0, 0),
    OP_put_arg2(124, 1, 1, 0, 0),
    OP_put_arg3(125, 1, 1, 0, 0);
    
    companion object {
        fun fromId(id: Int): OPCodeEnum? = entries.find { it.id == id }
    }
}

object OPCodeFormat {
    const val OP_FMT_none = 0
    const val OP_FMT_none_int = 1
    const val OP_FMT_none_loc = 2
    const val OP_FMT_loc8 = 2
    const val OP_FMT_none_arg = 3
    const val OP_FMT_u8 = 4
    const val OP_FMT_i8 = 3
    const val OP_FMT_const8 = 6
    const val OP_FMT_u16 = 5
    const val OP_FMT_i16 = 4
    const val OP_FMT_const16 = 7
    const val OP_FMT_npop = 9
    const val OP_FMT_loc = 11
    const val OP_FMT_arg = 12
    const val OP_FMT_var_ref = 13
    const val OP_FMT_u32 = 8
    const val OP_FMT_i32 = 8
    const val OP_FMT_label = 14
    const val OP_FMT_value = 13
}
