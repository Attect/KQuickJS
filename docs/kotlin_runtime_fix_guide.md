# Kotlin 运行时修复指南

本文档记录了 mquickjs Kotlin 移植版本中字节码执行引擎的修复过程，包括问题分析、解决思路和最终方案。

## 背景

mquickjs 是一个轻量级 JavaScript 引擎的 C 语言实现，我们将其移植到 Kotlin 平台。在移植过程中，字节码执行引擎中的栈操作逻辑存在多个错误，导致对象属性访问和数组元素访问等功能无法正常工作。

## 问题发现方法

由于问题难以直接定位，我们采用了以下调试策略：

1. **创建调试版本的 C 代码**：将 C 语言代码复制到新目录，添加与 Kotlin 相同的调试输出信息
2. **编写最小测试用例**：分析测试不通过的 JavaScript 用例，编写仅测试问题逻辑的新用例
3. **对比执行流程**：使用修改后的 C 版本和 Kotlin 版本分别执行，比对逻辑差异
4. **定位并修复问题**：根据差异分析结果修复 Kotlin 代码

## 已修复的问题

### 1. OP_put_field 实现错误

**问题描述**：
在原始 Kotlin 实现中，`OP_put_field` 操作码从栈中移除了对象，导致后续操作无法访问该对象。

**错误代码**：
```kotlin
OPCodeEnum.OP_put_field.id -> {
    val idx = readU16(code, pc)
    pc += 2
    val val1 = stack.removeLast()
    val obj = stack.removeLast()  // 错误：移除了对象
    // ...
    setProperty(obj, key, val1)
    stack.add(obj)  // 尝试重新添加，但破坏了栈状态
}
```

**修复方案**：
```kotlin
OPCodeEnum.OP_put_field.id -> {
    val idx = readU16(code, pc)
    pc += 2
    val val1 = stack.removeLast()
    val obj = stack.last()  // 正确：只获取对象引用，不移除
    // ...
    setProperty(obj, key, val1)
    // 不需要修改栈，对象保留在栈中
}
```

**C 语言参考实现**：
```c
CASE(OP_put_field):
    {
        int idx;
        JSValue val, obj, prop;
        JSValueArray *cpool = JS_VALUE_TO_PTR(b->cpool);
        idx = get_u16(pc);
        pc += 2;
        prop = cpool->arr[idx];
        val = sp[0];      // 获取栈顶值
        sp++;             // 移除栈顶值（注意：C语言栈向低地址增长）
        obj = sp[0];      // 获取对象（不移除）
        JS_SetProperty(ctx, obj, prop, val);
    }
    BREAK;
```

### 2. OP_get_field2 实现错误

**问题描述**：
`OP_get_field2` 是 `OP_get_field` 的优化版本，用于在栈中已有对象时获取属性值。原始 Kotlin 实现的栈操作逻辑与 C 语言版本不一致。

**C 语言实现分析**：
```c
CASE(OP_get_field2):
    sp--;              // 栈指针向高地址移动（相当于移除一个元素）
    sp[0] = sp[1];     // 将下一个元素复制到栈顶
    goto get_field_common;
```

**关键理解**：
- C 语言中，栈从高地址向低地址增长
- `sp--` 将栈指针向高地址移动，相当于"展开"一个栈元素
- `sp[0] = sp[1]` 将原来的 `sp[1]` 复制到新的 `sp[0]` 位置

**修复方案**：
```kotlin
OPCodeEnum.OP_get_field2.id -> {
    val idx = readU16(code, pc)
    pc += 2
    if (stack.size < 2) {
        return JS_EXCEPTION
    }
    // 对应 C 语言的 sp-- 和 sp[0] = sp[1]
    val obj = stack[stack.size - 2]  // 获取栈顶下一个元素
    stack.removeLast()               // 移除栈顶元素
    val cpool = ctx.memory.getJSValue(funcPtr + 24)
    val key = if (cpool != JS_NULL) {
        val cpoolPtr = JS_VALUE_TO_PTR(cpool)
        ctx.memory.getJSValue(cpoolPtr + 8 + idx * 8)
    } else JS_UNDEFINED
    val val1 = getProperty(obj, key)
    stack[stack.size - 1] = val1     // 用属性值替换栈顶的对象
}
```

### 3. OP_get_array_el2 实现错误

**问题描述**：
`OP_get_array_el2` 是 `OP_get_array_el` 的优化版本，用于在栈中已有数组和索引时获取元素值。原始 Kotlin 实现的栈操作逻辑与 C 语言版本不一致。

**C 语言实现分析**：
```c
CASE(OP_get_array_el2):
    val = sp[0];       // 保存栈顶（索引）
    sp[0] = sp[1];     // 将数组复制到栈顶
    goto get_array_el_common;
```

**修复方案**：
```kotlin
OPCodeEnum.OP_get_array_el2.id -> {
    if (stack.size < 2) {
        return JS_EXCEPTION
    }
    val idx = stack[stack.size - 1]   // 获取索引
    val obj = stack[stack.size - 2]   // 获取数组
    val val1 = getElement(obj, idx)
    stack[stack.size - 2] = val1      // 用元素值替换数组
    stack.removeAt(stack.size - 1)    // 移除索引
}
```

## 栈操作的关键理解

### C 语言栈模型

在 C 语言实现中，栈指针 `sp` 指向栈顶元素，栈向低地址增长：

```
高地址
┌────────────┐
│  栈元素 1   │  sp[1]
├────────────┤
│  栈元素 0   │  sp[0] ← sp 指向这里
└────────────┘
低地址
```

- `sp--`：栈指针向高地址移动，相当于"展开"栈（减少栈元素）
- `sp++`：栈指针向低地址移动，相当于"压入"栈（增加栈元素）

### Kotlin 栈模型

在 Kotlin 实现中，我们使用 `MutableList` 作为栈：

```kotlin
val stack = mutableListOf<JSValue>()
```

- `stack.add(value)`：压入元素
- `stack.removeLast()`：弹出元素
- `stack.last()`：获取栈顶元素
- `stack[stack.size - 2]`：获取栈顶下一个元素

### 转换规则

| C 语言操作 | Kotlin 等价操作 |
|-----------|----------------|
| `sp--` | `stack.removeLast()` |
| `sp++` | `stack.add(value)` |
| `sp[0]` | `stack.last()` |
| `sp[1]` | `stack[stack.size - 2]` |
| `sp[0] = value` | `stack[stack.size - 1] = value` |

## 测试用例

### 对象属性访问测试

```javascript
var obj = { x: 1, y: 2 };
obj.x + obj.y;  // 期望结果: 3
```

### 数组元素访问测试

```javascript
var arr = [1, 2, 3];
arr[0] + arr[1] + arr[2];  // 期望结果: 6
```

## 调试技巧

### 添加栈状态跟踪

在关键操作码执行前后打印栈状态：

```kotlin
OPCodeEnum.OP_get_field2.id -> {
    println("OP_get_field2: stack before = $stack")
    // ... 执行操作 ...
    println("OP_get_field2: stack after = $stack")
}
```

### 对比 C 和 Kotlin 的执行流程

1. 在 C 代码中添加相同的调试输出
2. 使用相同的 JavaScript 代码执行
3. 对比两个版本的输出，找出差异

## 已知限制

当前实现中，测试用例仅验证代码能够执行而不崩溃，尚未验证执行结果的正确性。后续需要：

1. 完善 `getProperty` 和 `getElement` 方法的实现
2. 添加结果验证的测试用例
3. 确保所有操作码的实现与 C 语言版本一致

## 新增修复

### 4. OP_not 操作码缺失

**问题描述**：
Kotlin 实现中缺少 `OP_not` 操作码（按位取反 `~`）的处理，导致 `~1` 等表达式无法执行。

**修复方案**：
```kotlin
OPCodeEnum.OP_not.id -> {
    val a = stack.removeLast()
    stack.add(doNot(a))
}

fun doNot(a: JSValue): JSValue {
    return JS_NewShortInt(toInt32(a).inv())
}
```

### 5. doMod 方法缺少整数快速路径

**问题描述**：
`doMod` 方法始终返回浮点数，而 C 语言版本在两个操作数都是正整数时会返回整数。

**修复方案**：
```kotlin
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
```

## 测试覆盖

创建了以下测试文件：

### 1. MQuickJSLanguageTest.kt（36 个测试用例）

1. **基础运算测试**：加、减、乘、除、取模
2. **位运算测试**：左移、算术右移、与、或、异或、按位取反
3. **比较运算测试**：小于、大于、逻辑非
4. **对象测试**：对象字面量、属性访问、in 运算符
5. **数组测试**：数组字面量、长度、元素访问
6. **变量测试**：变量声明、赋值
7. **函数测试**：函数定义、返回值
8. **增量/减量测试**：前置/后置 ++、--
9. **typeof 测试**：类型检测

### 2. MQuickJSLoopTest.kt（19 个测试用例）

1. **while 循环测试**：基本 while、break、continue
2. **do-while 循环测试**：基本 do-while、break
3. **for 循环测试**：基本 for、break、continue、嵌套循环
4. **for-in 循环测试**：对象遍历、数组遍历
5. **switch 语句测试**：case、default、fallthrough
6. **try-catch-finally 测试**：异常捕获、finally 执行

### 3. MQuickJSClosureTest.kt（7 个测试用例）

1. **基础闭包测试**：简单闭包、修改外部变量、返回函数
2. **嵌套闭包测试**：多层嵌套、修改多层变量
3. **递归闭包测试**：斐波那契数列

### 4. MQuickJSBuiltinTest.kt（35 个测试用例）

1. **Object 测试**：创建对象、属性访问
2. **Array 测试**：创建数组、push、pop、join、reverse、concat、shift、unshift、indexOf、lastIndexOf、slice、sort
3. **String 测试**：长度、charAt、substring、indexOf、concat、lastIndexOf、split、toLowerCase、toUpperCase、trim、replace、repeat
4. **Math 测试**：floor、ceil、abs
5. **parseInt/parseFloat 测试**：字符串转数字
6. **Number 测试**：toFixed、toExponential
7. **JSON 测试**：parse、stringify

### 5. MQuickJSFileTest.kt（4 个测试用例）

直接执行 tests/ 目录下的 JS 测试文件：

1. **test_language.js**：语言基础测试
2. **test_loop.js**：循环测试
3. **test_closure.js**：闭包测试
4. **test_builtin.js**：内置函数测试

## 调试改进

在 JSRuntime 中添加了循环计数器，用于检测无限循环：

```kotlin
var loopCount = 0
val maxLoopCount = 100000

while (pc < code.size) {
    loopCount++
    if (loopCount > maxLoopCount) {
        println("Error: Infinite loop detected, pc=$pc, op=${code[pc].toInt() and 0xFF}")
        return JS_EXCEPTION
    }
    // ...
}
```

这有助于在调试过程中快速定位循环相关的问题。

## 命令行程序

创建了 Kotlin 版本的命令行程序，实现了与 C 语言版本类似的功能：

### 使用方法

```bash
# 显示帮助
java -jar mquickjs.jar -h

# 执行 JS 文件
java -jar mquickjs.jar script.js

# 执行表达式
java -jar mquickjs.jar -e "1 + 2"

# 进入交互模式
java -jar mquickjs.jar -i

# 包含其他文件
java -jar mquickjs.jar -I utils.js script.js

# 设置内存限制
java -jar mquickjs.jar --memory-limit 16M script.js
```

### 命令行选项

| 选项 | 说明 |
|------|------|
| `-h, --help` | 显示帮助信息 |
| `-e, --eval EXPR` | 执行 JavaScript 表达式 |
| `-i, --interactive` | 进入交互模式 (REPL) |
| `-I, --include file` | 包含额外的 JS 文件 |
| `--memory-limit n` | 设置内存限制 (支持 K/M/G 后缀) |
| `-v, --version` | 显示版本信息 |

### 相关文件

- `kotlin/src/main/kotlin/MQuickJS.kt` - 命令行程序入口
- `kotlin/src/main/kotlin/JSContextFactory.kt` - 上下文工厂和 JS_Eval 函数

## GraalVM Native Image 构建

项目已配置支持 GraalVM Native Image，可以将 Kotlin 程序编译为原生可执行文件，无需 JVM 即可运行。

### 环境要求

- GraalVM CE 17 或更高版本
- Native Image 组件（通过 `gu install native-image` 安装）
- Windows: Visual Studio Build Tools (C++ 工作负载)
- Linux: GCC 工具链
- macOS: Xcode Command Line Tools

### 本地构建

**Windows (PowerShell):**
```powershell
# 设置 GraalVM 环境
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\graalvm-ce-17.0.9"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

# 构建 JAR
.\gradlew.bat jar

# 构建原生镜像
.\gradlew.bat nativeCompile

# 运行
.\build\native\nativeCompile\mquickjs-kotlin.exe -e "1 + 2"
```

**Linux/macOS:**
```bash
# 设置 GraalVM 环境
export JAVA_HOME=$HOME/.jdks/graalvm-ce-17.0.9
export PATH=$JAVA_HOME/bin:$PATH

# 构建
./gradlew jar nativeCompile

# 运行
./build/native/nativeCompile/mquickjs-kotlin -e "1 + 2"
```

### 使用构建脚本

项目提供了便捷的构建脚本：

**Windows:**
```powershell
.\scripts\build-native.ps1 -All
```

**Linux/macOS:**
```bash
chmod +x scripts/build-native.sh
./scripts/build-native.sh --all
```

### 构建产物

构建完成后，产物位于 `dist/` 目录：

| 文件 | 说明 |
|------|------|
| `mqjs-windows-x64.exe` | Windows x64 原生可执行文件 |
| `mqjs-linux-x64` | Linux x64 原生可执行文件 |
| `mqjs-macos-x64` | macOS Intel 原生可执行文件 |
| `mqjs-macos-aarch64` | macOS Apple Silicon 原生可执行文件 |
| `mqjs-1.0.0.jar` | 跨平台 JAR 文件 |

### GitHub Actions CI/CD

项目配置了 GitHub Actions 工作流 (`.github/workflows/native-image.yml`)，支持：

- 自动测试
- 多平台原生镜像构建 (Windows/Linux/macOS)
- 发布版本自动创建 GitHub Release

触发条件：
- 推送到 main/master 分支
- 创建 v* 标签
- 手动触发

### 配置文件

GraalVM Native Image 相关配置文件位于 `src/main/resources/META-INF/native-image/`：

| 文件 | 说明 |
|------|------|
| `reflect-config.json` | 反射配置 |
| `resource-config.json` | 资源配置 |
| `native-image.properties` | 构建参数 |
| `proxy-config.json` | 动态代理配置 |

## 相关文件

- `kotlin/src/main/kotlin/runtime/JSRuntime.kt` - Kotlin 运行时实现
- `kotlin/src/main/kotlin/MQuickJS.kt` - 命令行程序入口
- `kotlin/src/main/kotlin/JSContextFactory.kt` - 上下文工厂
- `kotlin/src/test/kotlin/MQuickJSTest.kt` - 基础测试用例
- `kotlin/src/test/kotlin/MQuickJSLanguageTest.kt` - 语言特性测试用例
- `kotlin/src/test/kotlin/MQuickJSLoopTest.kt` - 循环测试用例
- `kotlin/src/test/kotlin/MQuickJSClosureTest.kt` - 闭包测试用例
- `kotlin/src/test/kotlin/MQuickJSBuiltinTest.kt` - 内置函数测试用例
- `kotlin/src/test/kotlin/MQuickJSFileTest.kt` - JS文件执行测试用例
- `mquickjs.c` - C 语言原始实现（参考）

## 更新历史

- **2024-02-13**：初始版本，记录 OP_put_field、OP_get_field2、OP_get_array_el2 的修复过程
- **2024-02-13**：添加 OP_not 操作码实现，修复 doMod 方法的整数快速路径
- **2024-02-13**：创建完整的测试套件，包含 124 个测试用例
- **2024-02-14**：创建 Kotlin 命令行程序，实现与 C 语言版本类似的功能
- **2024-02-14**：配置 GraalVM Native Image 支持，实现多平台原生二进制构建
