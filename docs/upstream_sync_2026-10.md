# 上游 mquickjs 同步对照（2026-10）

本文档记录 kotlin-implementation 分支对上游 [bellard/mquickjs](https://github.com/bellard/mquickjs) 的同步情况。

- 分叉基点：`ee50431`（2026-01-15，"fixed exception handling in String.prototype.toLowerCase/toUpperCase"）
- 本次同步范围：上游 `ee50431..upstream/main` 共 9 个提交（2026-06 两批、2026-09 一批）
- 本地已添加上游 remote：`upstream`（https://github.com/bellard/mquickjs），可用 `git log ee50431..upstream/main` 复查

## 一、上游提交对照清单

| 上游提交 | 内容 | 处理结果 |
|---------|------|---------|
| `203d5bb` | `JS_HasException()` + 异常状态改用 `JS_UNINITIALIZED` | ✅ 已同步（P0） |
| `49f1536` | `JS_CFUNC_f_f` 调用异常时不覆盖异常值 | ⚠️ 语义已记录，待 `callCFunction` 实现 defType 分发时采用（见"已知缺口"） |
| `e0542a9` | GC 释放时连续块逐个调用用户 finalizer | ✅ 已同步（P1a，`gcFreeUnmarked`） |
| `6d4d7eb` | `JS_NewObjectClassUser(n_values)` + `JS_SetUserValue/JS_GetUserValue` + GC 支持 | ✅ 已同步（P1b） |
| `e6458ea` | `JS_IsArray()` 公共 API | ✅ 已同步（P2） |
| `7b6e530` | `JS_GetContextOpaque()` | ✅ 已同步（P2，setter 一并补齐） |
| `e15c691` | `new Date()` 支持（`JS_NewDate`、`Date.prototype.valueOf`） | ✅ 已同步（P3，含宿主层构造器 `MQuickJS.mqjsDateConstructor`） |
| `48ff716` | 构建期工具限制 `JS_DEF_CLASS` 仅用于全局对象 | ➖ 不适用（Kotlin 无构建期 stdlib 生成工具） |
| `2b091e5` | 删除未使用的 C 头文件 | ➖ 不适用（C 专属清理） |

## 二、本次改动明细

### P0 异常状态语义（上游 `203d5bb`）

- `JSContext.currentException` 初始化改为 `JS_UNINITIALIZED`（原 `JS_UNDEFINED`）
- `JS_GetException` 取出后置 `JS_UNINITIALIZED`（原 `JS_UNDEFINED`）
- 新增 `JS_HasException(ctx)`：`!JS_IsUninitialized(currentException)`
- `throwOutOfMemory` 中的 `JS_NULL` **保持不变**（对照 C 的 `JS_Throw(ctx, JS_NULL)`：递归 OOM 保护时 JS_NULL 是异常对象本身）
- 上游 `JS_Call` catch 处的 `JS_NULL → JS_UNINITIALIZED` 改动：Kotlin 的 `OP_catch` 执行逻辑尚未实现，实现时直接采用新语义

### P1a GC 用户 finalizer（上游 `e0542a9`）

- 新增 `JSGC.gcFreeUnmarked()`：标记扫描后对死块释放前逐个调用 `cFinalizerTable` 中的用户 finalizer，随后将连续死块合并为一个 free 块（对齐 C `set_free_block`）
- 语义对齐修复版：对连续未标记区域内的**每个**用户对象块调用 finalizer（修复前 C 实现只调用区域首块）
- 存活块的标记位清除扩展到**所有 mtag**（对齐 C `b->gc_mark = 0`；原先只清 OBJECT，字符串/数组等块的标记位残留会导致永久泄漏）
- finalizer 回调传入侧表中的宿主 opaque，调用后清理侧表条目（约定：finalizer 中禁止调用 JS 函数）

### P1b 用户对象私有值（上游 `6d4d7eb`）

- `JS_NewObjectClassUser(ctx, classId, nValues)`：extra 区 = 1 个 opaque 占位槽 + nValues 个私有 JSValue 槽（初始化 `JS_UNDEFINED`；nValues 上限 254，对照 C 的 extra_size 位宽断言）
- 新增 `JS_SetUserValue / JS_GetUserValue`（索引越界抛 `IllegalArgumentException`，对照 C 的 assert）
- GC 三个阶段（mark flush / thread / update）均增加用户类默认分支：处理 opaque 槽之后的 nValues 个私有值

### P2 公共 API 补齐（上游 `e6458ea`、`7b6e530`）

- 新增 `JS_IsArray(ctx, obj)`
- 新增 `JS_GetContextOpaque(ctx)` 与 `JS_SetContextOpaque(ctx, opaque)`

### P3 Date 支持（上游 `e15c691`）

- 新增 `JS_NewDate(ctx, epochMs)`：`JS_CLASS_DATE` 对象，extra 槽 0 存 epoch 毫秒（对照 C `JSDate.dval`）
- 新增 `jsDateValueOf(ctx, thisVal)`：读取槽 0 毫秒并返回数值（对照 C `js_date_valueOf`；非 Date 对象抛 TypeError 待错误对象机制完成后接入）
- 新增宿主层构造器 `MQuickJS.mqjsDateConstructor(ctx, argc, argv)`（对照 C mqjs.c `js_date_constructor`）：无参=当前时间、单数值参数=该毫秒值、剥离 `FRAME_CF_CTOR` 标志位、非法参数抛异常
- JS 层的 `new Date()` 暴露还依赖：① stdlib 注册机制（将构造器挂到全局对象）② `OP_new` 的 C 构造器调用路径（当前 `JSRuntime` 对 C 函数对象的 new 直接返回空对象），随 stdlib 落地工程实现

## 三、顺带修复的移植缺陷（非上游变更）

同步过程中发现的 Kotlin 移植自身缺陷，已一并修复：

1. **死块释放机制整体缺失**：原实现无"标记清除 → 释放"阶段，GC 压缩从未实际回收任何对象（压缩循环空转，`newPtr` 恒等于 `ptr`）。这是所有下列缺陷长期未暴露的原因。
2. **`MemoryBlock.getBlockSize` OBJECT 分支读错偏移**：把 `classId`（ptr+2）当 `extraSize` 读（实际在 ptr+3），导致堆遍历步进错乱。
3. **JSGC 三处 `classId/extraSize` 读取颠倒**（gcMarkFlush / gcThreadBlock / gcUpdateThreadedPointers）：原先按 ptr+3 读 classId、ptr+2 读 extraSize，与写入侧（`JS_NewObjectProtoClass`）相反，闭包/数组/函数分支匹配全靠碰运气。
4. **压缩器指针更新机制错误**：原实现（裸地址 +delta 方案）delta 用错对象，且 context 根字段（globalObj、classProto、执行栈、GCRef 链、parseState）完全未参与 threading/update。已重写为**地址映射表方案**（pass1 建映射 → 按对象结构更新堆内字段与全部根区域 → pass2 搬移）。C 版使用 Jonkers 链表技法，Kotlin 的 context 字段位于 JVM 堆无法入链，映射表方案语义等价。
5. **GC thread/update 缺 `JS_CLASS_C_FUNCTION` 分支**：C 函数的 params 指针不经压缩更新，已补齐。
6. **`JS_SetOpaque/JS_GetOpaque` 存储位置错误**：原先写到 `ctx.opaque`（context 级单值），与 C 的 per-object（`p->u.user.opaque`）语义不符。已改为 `JSContext.userOpaqueTable`（按对象地址索引，GC 搬移时随块更新 key，对象释放时清理条目）。

## 四、已知缺口（后续跟进）

以下缺口已在第二轮同步中补齐（对照 C 原版实现）：

| 缺口 | 状态 |
|------|------|
| `callCFunction` 未按 `defType` 分发 | ✅ 已实现：generic/constructor/generic_magic/constructor_magic/generic_params/f_f 全分发，argc 携带 `FRAME_CF_CTOR` 等调用标志；f_f 采用上游 `49f1536` 语义 |
| `throwError` 为 TODO | ✅ 已实现：错误对象（class_id=JS_CLASS_ERROR，message/stack 槽）+ `JS_ThrowTypeError` 等系列包装 + `JS_ThrowUncatchable`；`JS_IsError` 修正为恒等判断（对照 C） |
| `OP_catch`/`OP_throw` 执行逻辑缺失 | ✅ 已实现：`OP_catch`（压 CATCH_OFFSET）/`OP_throw`/`OP_gosub`/`OP_ret` + `unwindToCatch` 本帧异常展开（catch 命中恢复执行、uncatchable 直接传播）；parser try/catch/finally 重写（catch 变量绑定、catch2 保护重抛、gosub/finally） |
| Date 的 JS 层暴露 | ✅ 已实现：最小 stdlib 注册（`OP_new` 走 C 构造器路径 + `FRAME_CF_CTOR`），`new Date(ms)` / `Date.now()` 在 JS 层可用 |

仍然存在的缺口：

| 缺口 | 位置 | 说明 |
|------|------|------|
| 闭包变量捕获（varref 机制） | parser/runtime | 内层函数读取外层函数变量未实现（退化为 `this.v` 属性读取）；OP_get_var_ref/ext_vars 机制未移植。`f()()` 链式调用本身已工作（见 testChainedCall），捕获外层变量的闭包待实现 |
| 属性 lhs 赋值表达式 | parser | `this.v = x` / `obj.p = v` 的赋值编译（put_field 的 lvalue 识别）未实现，仅支持局部变量赋值；构造器测试 testConstructorThisBinding 依赖此项（已 @Ignore） |
| catch 变量绑定读取为 undefined | parser/runtime | `catch (e)` 经 put_loc 绑定异常值后，块内 get_loc 读到 undefined，待定位；testErrorBacktrace 依赖此项（已 @Ignore，stack 回溯生成本身已实现并经 testGcPreservesErrorObject 间接覆盖） |
| build_backtrace 格式 | `JSContext.throwError` | stack 槽已生成基于 jsCallStack 的回溯文本；C 版的行号/列号信息未包含 |
| GC 未处理 `JS_CLASS_REGEXP` / TYPED_ARRAY | `JSGC` | 这些内建类尚未在 Kotlin 实现，实现时需同步补 GC 分支（JS_CLASS_ERROR 分支已补齐） |
| 顶层调用不追调返回的闭包中的变量捕获 | `JSRuntime.callFunction` | 同闭包变量捕获缺口 |
| `f_f` 参数转换异常路径 | `callCFunction` | Kotlin 的 `JS_ToNumber` 为纯转换（不产生异常），上游 `49f1536` 的异常优先结构已按 C 形态实现，但异常分支待 `JS_ToNumber` 异常化后生效 |

## 五、验证

- 回归测试：`kotlin/src/test/kotlin/GCTest.kt`（20 个用例，2 个 @Ignore 标注依赖配套缺口）
  - GC：死对象回收、finalizer、user value/opaque 搬移存活、闭包一致性、错误对象 GC 搬移
  - 异常：try/catch/finally、重抛、跨函数展开、错误对象创建与包装类
  - 公共 API：JS_IsArray / ContextOpaque / JS_NewDate + valueOf / 宿主 Date 构造器
  - 执行层：callCFunction defType 分发（f_f、constructor 的 CTOR 标志位）、
    Date JS 层集成（Date.now / new Date / 普通调用）、this 传递（o.f() 的 this.v）、
    put_lvalue 写回（变量/属性 × pre/post）、break/continue 跳转、f()() 链式调用
- 全量构建：`./gradlew build -x nativeBuild` 通过，共 144 个测试 0 失败

## 六、测试注意事项（值会移动）

GC 压缩会搬移堆块：**Kotlin 侧持有的 JSValue 局部值在 GC 后会过期**，须通过 GC root（执行栈 / `JSGCRef`）读回最新地址。测试代码中的标准模式：

```kotlin
ctx.pushValue(obj)          // 压栈保活
ctx.gc()                    // 可能搬移 obj
obj = ctx.memory.getJSValue(ctx.sp)  // 从栈上读回最新地址
```
