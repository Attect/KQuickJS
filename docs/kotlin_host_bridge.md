# KQuickJS Kotlin 宿主桥接层使用文档

本文档介绍 KQuickJS Kotlin 实现新增的 **Java/Kotlin 宿主方法桥接层**（源码位于
`kotlin/src/main/kotlin/bridge/`），用于在 JavaScript 脚本中调用 JVM 宿主注入的
能力（HTTP 客户端、浏览器自动化、日志、本地存储、端口监听等）。

## 目录

- [概述](#概述)
- [快速上手](#快速上手)
- [类型转换层](#类型转换层)
- [便捷注册 API](#便捷注册-api)
- [属性绑定（近似方案与取舍）](#属性绑定近似方案与取舍)
- [异常桥接](#异常桥接)
- [宿主对象生命周期](#宿主对象生命周期)
- [阻塞调用约定](#阻塞调用约定)
- [引擎层配套修改说明](#引擎层配套修改说明)
- [已知限制](#已知限制)

## 概述

桥接层的设计目标：

1. **零运行时反射**：全部基于 lambda 与显式注册，兼容 GraalVM Native Image；
2. **突破定长函数表**：`JS_NewContext` 之后即可动态注册任意数量的宿主函数
   （内部经由 `JSContext.extFunctionTable` 可扩表）；
3. **可定位的类型错误**：参数转换失败时，JS 侧收到的 TypeError 消息包含
   函数路径、参数位置、参数名、期望类型与实际类型；
4. **异常不炸穿**：宿主 lambda 抛出的任何 Kotlin 异常都被转为 JS Error 对象；
   反方向 JS 错误被包装为宿主可捕获的 Kotlin 异常；
5. **宿主对象生命周期**：Kotlin 实例可作为 JS 对象的 opaque 挂载，GC 搬移后
   引用保持正确，对象回收时自动执行宿主清理回调。

核心类一览：

| 类/对象 | 职责 |
|---------|------|
| `JSBridge` | 桥接层入口：命名空间创建、eval/call 辅助、宿主对象生命周期 |
| `JSBridgeNamespace` | 命名空间句柄：函数/构造器/属性/宿主对象注册 |
| `JSBridgeCall` | 宿主函数参数读取器（按位/按名的严格类型读取） |
| `JSBridgeConvert` | JSValue 与 Kotlin 类型的双向转换 |
| `JSRawValue` | JSValue 在 Kotlin 容器中的原样包装 |
| `JSBridgeException` | 桥接层内部异常（携带 JS 错误种类） |
| `JSBridgeScriptException` | JS 错误的反向封装（宿主可捕获） |
| `JSPlatformExample` | platform 命名空间完整示例 |

## 快速上手

```kotlin
// 1. 创建上下文（与现有方式一致）
val ctx = JS_NewContext(mem, memSize, stdlib)

// 2. 创建桥接层并注册宿主函数
val bridge = JSBridge(ctx)
bridge.namespace("platform").apply {
    // 命名空间函数：platform.log.info(msg)
    namespace("log").function("info", listOf("msg")) { _, args ->
        println("[info] ${args.string("msg")}")
        null  // 返回 null → JS undefined
    }
    // 0 参定型签名：platform.now()
    function("now") { _ -> System.currentTimeMillis() }

    // 任务示例签名 (ctx, url: String, timeoutMs: Int) → Map
    namespace("http").function("get", "url", "timeoutMs") { _, url, timeoutMs ->
        mapOf("status" to 200, "body" to "…", "timeoutMs" to timeoutMs)
    }
}

// 3. JS 侧调用
val status = bridge.eval("platform.http.get('https://example.test', 100).status")  // 200
```

完整可运行的 platform 示例（含日志汇聚、模拟 HTTP、超时约定）见
`bridge/JSPlatformExample.kt`，配套测试见 `kotlin/src/test/kotlin/JSBridgeTest.kt`。

## 类型转换层

### 双向映射表

| Kotlin 类型 | → JS | JS → Kotlin（严格读取） | JS → Kotlin（通用转换） |
|-------------|------|--------------------------|--------------------------|
| `null` | `null` | — | `null`（null/undefined 均映射 null） |
| `Boolean` | boolean | `boolean()`（仅接受 boolean） | `Boolean` |
| `Int` | number | `int()`（整数值 number） | `Int`（整数值）→ `Long`（超范围） |
| `Long` | number | `long()` | 同上 |
| `Double`/`Float` | number | `double()`（任意 number） | `Double`（非整数值） |
| `String` | string | `string()`（仅接受 string，不自动 toString） | `String` |
| `ByteArray` | number 数组（0..255） | `bytes()` | — |
| `List` | JS 数组 | `list()` | `List<Any?>`（递归） |
| `Map` | JS 普通对象 | `map()` | `Map<String, Any?>`（递归） |
| `JSRawValue` | 原样 JSValue | — | 原样 JSValue |

约定说明：

- **严格读取策略**：参数读取不做隐式转换（如 number 不自动转 boolean/string），
  避免歧义；需要宽容语义时用 `any()` 取通用转换结果后自行判型；
- **`*_OrNull` 系列**：参数缺失或为 undefined 时返回 null，类型不符仍抛可定位
  的 TypeError；
- **Map 的 key**：写入 JS 对象时非 String key 取 `toString()`；
- **通用转换中的非数据 JS 值**（函数、宿主对象、错误对象等）以 `JSRawValue`
  包装回传，`fromKotlin` 识别后原样透传；
- **重要**：`JSValue` 是 `Long` 的 typealias。宿主 lambda 返回裸 `JSValue`（如
  `JS_NewInt32(...)` 的结果）会被误当 Kotlin 数字再次转换，必须包一层
  `JSRawValue(...)`。

### 性能说明

- 数字读取直接从 JSValue 位模式取值，不经 Any? 装箱；
- 参数按需读取：`JSBridgeCall` 的各读取器只在被调用时转换对应参数；
- JS→Kotlin 通用转换全程不在 JS 堆分配，不会触发 GC 搬移；
- 递归深度受 `JSBridgeConvert.MAX_DEPTH`（64）保护，超出抛 RangeError。

## 便捷注册 API

### 命名空间

```kotlin
bridge.namespace("platform.http")   // 自动逐段创建普通对象，已存在则复用
```

- 同名普通对象复用；同名函数/非对象属性上创建子命名空间会抛
  `JSBridgeException(TYPE_ERROR)`；
- 命名空间句柄不缓存 JS 对象地址，每次操作按路径从 globalObj 重新解析
  （解析全程只读，规避 GC 搬移导致的悬空 JSValue）；
- 命名空间内的函数名可含 `.`（如 `function("log.info", …)`），自动创建中间层。

### 函数变体

```kotlin
// 定型签名（每个参数个数一种形态，避免重载歧义）
ns.function("now") { ctx -> ... }                                   // 0 参
ns.function("echo", "msg") { ctx, msg -> ... }                      // 1 参 (String)
ns.function("get", "url", "timeoutMs") { ctx, url, t -> ... }       // 2 参 (String, Int)

// 通用形态：参数按名/按位读取（任意个数与类型组合）
ns.function("call", listOf("name", "value")) { ctx, args ->
    val name = args.string("name")      // 按名（要求注册时声明参数名）
    val value = args.int(1, "value")    // 按位 + 定位名
    ...
}

// 原生形态：直接处理 JSValue（高性能/可变参数；宿主自担引擎 GC 约定）
ns.functionRaw("sum", 0, -1) { ctx, thisVal, args ->
    ... // 返回 JSValue；返回 JS_EXCEPTION 表示已挂起 JS 异常
}
```

- 定型签名经 `JSBridgeCall` 的严格转换器实现，转换失败抛出可定位 TypeError；
- 参数个数严格校验：不符时抛 `TypeError`（消息含期望与实际个数）；
- 通用形态各读取器见 `JSBridgeCall`（string/int/long/double/boolean/bytes/
  list/map/any/jsValue 及对应 `*OrNull`，均支持按位与按名两种定位）。

### 构造器变体

```kotlin
// 便捷变体：返回的 Kotlin 实例自动包装为宿主对象
bridge.constructor("Point", listOf("x", "y")) { _, args ->
    PointData(args.int("x"), args.int("y"))
}
bridge.eval("var p = new Point(3, 4)")   // JS 侧正常 new

// 原生变体：直接返回 JSValue 作为 new 结果
ns.constructorRaw("Raw", 0, -1) { ctx, thisVal, args -> ... }
```

注意：本引擎的 C 构造器不提供 prototype 挂载机制（`new` 产生的对象无法预挂
方法），实例方法建议用工厂函数 + 宿主对象属性挂载或 JS 侧组合实现。

### 数据属性

```kotlin
ns.property("version", "1.0.0")
ns.property("limits.max", 100)   // 名可含 "."，自动创建中间命名空间
```

## 属性绑定（近似方案与取舍）

**引擎现状结论**（已核实 C 引擎源码）：

- `mquickjs.c` 内部存在 get/set 属性语义：`JS_DefinePropertyInternal`（约 2946 行）
  支持 getter/setter 参数，属性以 `JS_PROP_GETSET` 类型存储为双元素 JSValueArray，
  读取/写入路径有 getset 分支（约 2598/3300 行），对象字面量的 `get/set` 由
  `OP_define_getter/OP_define_setter` 字节码支持（约 6081 行）；
- **但 `mquickjs.h` 没有宿主侧 get/set 型函数的公开注册 API**（`JSCFunctionDefEnum`
  仅六种 defType，均非访问器），即宿主（C/Kotlin）函数无法注册为属性访问器；
- Kotlin 解释器目前也未实现 `OP_define_getter/OP_define_setter` 字节码。

**桥接层方案**：以命名约定的一对普通函数近似属性访问器：

```kotlin
ns.getter("status") { _ -> status }                    // → status.getStatus()
ns.getterSetter("port",
    { _ -> port },                                     // → cfg.getPort()
    { _, args -> port = args.int("port"); null }       // → cfg.setPort(v)
)
```

**取舍**：JS 侧必须以 `obj.getXxx()` 方法调用形式访问，无法支持 `obj.xxx` 的
属性语法；换来的是零引擎侵入。若未来引擎补齐宿主级 getset 注册 API 与对应
字节码解释，可在桥接层无缝替换实现而保持注册 API 不变。

## 异常桥接

### 宿主 → JS（Kotlin 异常转为 JS Error）

宿主 lambda 抛出的**任何** `Throwable` 都被桥接层捕获并按"简化映射表"转为对应
原型的 JS Error 对象，异常类名并入错误消息（`"<函数路径>: 宿主异常 - <类名>: <消息>"`）：

| Kotlin 异常 | JS Error 类型 |
|-------------|---------------|
| `JSBridgeException`（kind=TYPE_ERROR/RANGE_ERROR/...） | 对应类型 |
| `IndexOutOfBoundsException`、`ArithmeticException` | `RangeError` |
| `ClassCastException`、`NumberFormatException`、`IllegalArgumentException` | `TypeError` |
| 其他 `Throwable` | `Error` |

JS 侧正常 try/catch 捕获：

```js
try {
    platform.http.get(123, 100);   // string 参数收到 number
} catch (e) {
    // e.message 形如：
    // platform.http.get 的参数 0 'url' 应为 string，实际为 number(123)
}
```

宿主也可主动抛 `JSBridgeException(JSErrorKind.TYPE_ERROR, "…")` 指定错误类型。

### JS → 宿主（JS 错误包装为 Kotlin 异常）

`bridge.eval` / `bridge.call` 在 JS 侧抛错时抛出 `JSBridgeScriptException`：

```kotlin
try {
    bridge.eval("boom()")
} catch (e: JSBridgeScriptException) {
    println(e.jsErrorName)   // "TypeError"（经错误对象原型反查）
    println(e.jsMessage)     // JS 错误的 message
    println(e.jsStack)       // JS 错误的 stack 回溯（可能为 null）
}
```

辅助方法：`bridge.errorMessage(v)` / `bridge.errorStack(v)` 直接读取错误对象的
message/stack 槽。

> 注意：本引擎错误对象的 message/stack 存于对象 extra 槽（非普通属性），
> JS 代码目前无法直接 `e.message` 读取；JS 侧需要消息时可注册一个宿主辅助函数
> 转发 `bridge.errorMessage`（参见测试中的 `sys.errmsg`）。

## 宿主对象生命周期

```kotlin
// 创建：Kotlin 实例作为 opaque 挂在 JS 普通对象上
val res = FakeResource(7)
val jsObj = bridge.newHostObject(res, onFinalize = { it -> (it as FakeResource).close() })

// 取回（支持类型校验）
val back = bridge.getHostObject(jsObj)                      // Any?
val typed = bridge.getHostObject(jsObj, FakeResource::class.java)  // FakeResource?
bridge.isHostObject(jsObj)                                  // true

// 挂载到命名空间属性
bridge.namespace("res").hostObject("current", res) { it -> (it as FakeResource).close() }
```

保证机制：

- opaque 存放于 `ctx.userOpaqueTable`（按对象块地址索引），**GC 压缩搬移对象时
  表 key 随块自动更新**，宿主引用始终正确（测试 `testHostObjectOpaqueSurvivesGCMove`
  验证强制 GC 后读取仍一致）；
- 对象被 GC 回收时，桥接层注册在 `ctx.extFinalizerTable` 的实例分发 finalizer
  按 opaque 中的 `JSBridgeHostRef` 回调 `onFinalize`（测试
  `testHostObjectFinalizerOnGC` 验证）；finalizer 内**禁止调用 JS 函数**（与
  C 引擎约定一致），回调异常被捕获并输出 stderr，不影响回收流程；
- 该机制复用 `JS_CLASS_OBJECT` 类（Kotlin 侧 `JS_SetOpaque/JS_GetOpaque` 已扩展
  为任意对象可挂 opaque），无需在建上下文时预留 user 类空间；使用 user 类
  （classId ≥ 28）+ `cFinalizerTable` 的传统路径不受影响。

## 阻塞调用约定

**必须明确**：`ctx.interruptHandler` 仅在解释器执行字节码的间隙被检查；一旦
进入宿主函数，解释器循环被挂起，**宿主函数内的长时间阻塞无法被 interruptHandler
打断**（例如阻塞式网络 I/O、锁等待、`Thread.sleep`）。

推荐模式：

1. **阻塞等待类宿主函数自带 `timeoutMs` 参数**，在等待循环中检查超时；
2. **超时返回标记值而非抛异常或永久阻塞**，让 JS 侧以普通分支处理：

   ```js
   var r = platform.http.get(url, 3000);
   if (r.timeout) {
       // 超时处理
   } else {
       // 正常处理 r.status / r.body
   }
   ```

3. 需要硬中断时，应把阻塞动作放到宿主自己的工作线程，宿主函数只做
   有界等待（`Future.get(timeout)` 等），超时后返回标记值并取消任务。

## 引擎层配套修改说明

为支撑桥接层，对 `kotlin/` 内引擎文件做了以下**加法式**修改（不改变既有公共
API 语义；其中两项为修复既有缺陷，一并说明）：

| 文件 | 修改 | 性质 |
|------|------|------|
| `JSContext.kt` | 新增 `extFunctionTable`（可扩函数表）与 `extFinalizerTable`（动态 finalizer 表） | 新增 |
| `runtime/JSRuntime.kt` | `callCFunction` 在 funcIdx 超出基础定长表时按偏移查找扩展表 | 新增 |
| `runtime/JSRuntime.kt` | `getProperty/hasProperty` 补齐数组 `length` 回退（解析器未发射 `OP_get_length`，`.length` 原本恒为 undefined） | 缺陷修复 |
| `runtime/JSRuntime.kt` | `OP_array_from` 补齐元素存储分配（原先数组字面量元素被丢弃，`[1,2,3]` 实为空数组） | 缺陷修复 |
| `parser/JSParser.kt` | 数组元素自增/自减与复合赋值（`a[i]++`、`a[i] += v`）补齐 lvalue 写回（原先不写回/栈序错乱） | 缺陷修复 |
| `JSObject.kt` | `JS_SetOpaque/JS_GetOpaque` 放宽为任意对象可挂 opaque（Kotlin 侧 opaque 是 JVM 引用表而非块内槽位，GC 已对全部对象统一维护；C 版仅 user 对象有 opaque 语义） | 扩展 |
| `JSGC.kt` | 回收路径对非 user 类对象增加 `extFinalizerTable` 查找（桥接层宿主对象的实例分发 finalizer） | 新增 |
| `memory/MemoryBlock.kt` | `getBlockSize` 修正 VALUE_ARRAY/BYTE_ARRAY 块大小多算 8 字节的问题（与全部分配点 `malloc(8 + n)` 对齐；原缺陷导致 GC 压缩后堆块错位、属性查找失效） | 缺陷修复 |

## 已知限制

- **typeof 未实现**：解析器/解释器未实现 `typeof` 语义（当前直接返回操作数），
  判断类型请用桥接层取回后判型；
- **`undefined` 字面量**：词法上被处理为标识符（全局未定义即 ReferenceError），
  需要该值时可用无初始化 `var u; u` 或经宿主转换（null）；
- **错误 message/stack 非 JS 可读属性**：存于错误对象 extra 槽，JS 侧无法直接
  `e.message`，经宿主辅助函数转发（见"异常桥接"）；
- **顶层 `var` 不跨 eval 持久**：每次 `JS_Eval` 是独立程序帧（引擎既有语义），
  跨调用传值请通过全局对象属性（赋值语句）或宿主持有 JSValue；
- **构造器无 prototype 挂载**：见"构造器变体"；
- **纯数据转换**：类型转换层只处理数据类型（含 `JSRawValue` 透传），宿主函数
  返回任意其他 Kotlin 对象会得到 TypeError（宿主对象请走生命周期 API 包装）。
