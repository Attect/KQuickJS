KQuickJS
========

> ⚠️ **重要声明**
>
> 本项目是从MQuickJS官方仓库 [https://github.com/bellard/mquickjs](https://github.com/bellard/mquickjs) Fork 而来。
>
> **这不是MQuickJS官方仓库，与MQuickJS官方项目无关。**
>
> 本仓库内容为 **Attect** 基于原项目指导 AI 修改完善得来。如果您需要原项目的纯净实现，请前往官方仓库获取。

---

## 简介

**KQuickJS** 是一个面向嵌入式系统和JVM平台的JavaScript引擎，基于MicroQuickJS项目开发。

本项目在原C代码基础上进行了以下改进：

- **CMake构建系统**：将原项目改写为CMake管理，支持跨平台编译
- **Kotlin实现**：完整的Kotlin/JVM移植版本，支持GraalVM Native Image编译为原生可执行文件

KQuickJS可以编译和运行JavaScript程序，内存占用低至10KB（C版本）。整个引擎约需100KB的ROM空间（ARM Thumb-2代码）。Kotlin版本则提供了JVM和原生镜像两种运行方式。

## 版权说明

本项目包含多个组件，各组件版权归属如下：

### 原始C代码

核心C代码源自Micro QuickJS项目，版权归原作者所有：

- Copyright (c) 2017-2025 Fabrice Bellard
- Copyright (c) 2017-2025 Charlie Gordon

原始代码遵循MIT许可证发布。

### CMake构建系统

CMake构建系统配置、编译脚本及相关工程管理文件，由 **Attect** 指挥 **GLM5 AI** 完成改写和适配。

### Kotlin实现

Kotlin/JVM移植版本，由 **Attect** 指挥 **GLM5 AI** 完成开发。

---

## Kotlin 实现

KQuickJS提供了完整的Kotlin实现，将JavaScript引擎移植到Kotlin/JVM平台，并支持通过GraalVM Native Image编译为原生可执行文件。

### 功能特性

- 完整的JavaScript解析器和运行时
- 支持ES5严格模式
- 支持GraalVM Native Image编译
- 跨平台支持 (Windows/Linux/macOS)
- 内存管理系统
- 类型安全的值操作

### 环境要求

- JDK 17或更高版本
- Gradle 8.0+ (包含在项目中)
- GraalVM CE 17 (用于原生镜像构建)

### 构建

```bash
cd kotlin

# 构建JAR
./gradlew jar

# 运行测试
./gradlew test

# 构建原生镜像 (需要GraalVM)
./gradlew nativeCompile
```

### 使用命令行工具

```bash
# 显示帮助
java -jar kquickjs.jar -h

# 执行JS文件
java -jar kquickjs.jar script.js

# 执行表达式
java -jar kquickjs.jar -e "1 + 2"

# 进入交互模式
java -jar kquickjs.jar -i

# 设置内存限制
java -jar kquickjs.jar --memory-limit 16M script.js
```

### 原生镜像使用

构建原生镜像后，可直接运行无需JVM：

```bash
# Windows
kquickjs.exe -e "1 + 2"

# Linux/macOS
./kquickjs -e "1 + 2"
```

### 项目结构

```
kotlin/
├── src/main/kotlin/
│   ├── MQuickJS.kt              # 命令行入口
│   ├── JSContext.kt             # 上下文管理
│   ├── JSContextFactory.kt      # 上下文工厂
│   ├── JSValue.kt               # 值类型定义
│   ├── JSValueOps.kt            # 值操作
│   ├── JSConstants.kt           # 常量定义
│   ├── JSEnums.kt               # 枚举定义
│   ├── OPCode.kt                # 操作码定义
│   ├── JSGC.kt                  # 垃圾回收
│   ├── JSObject.kt              # 对象实现
│   ├── JSCFunctionDef.kt        # C函数定义
│   ├── memory/
│   │   ├── Memory.kt            # 内存管理
│   │   └── MemoryBlock.kt       # 内存块
│   ├── parser/
│   │   ├── JSLexer.kt           # 词法分析器
│   │   └── JSParser.kt          # 语法解析器
│   └── runtime/
│       └── JSRuntime.kt         # 运行时
├── src/test/kotlin/             # 测试用例
└── build.gradle.kts             # 构建配置
```

### 测试覆盖

Kotlin实现包含124个测试用例，覆盖：

- 基础语言特性 (变量、运算符、表达式)
- 控制流 (if/else、循环、switch)
- 函数和闭包
- 对象和数组操作
- 内置函数 (Math、String、Array)
- 异常处理

---

## C版本构建 (CMake)

本项目的C代码已改写为CMake构建系统管理。

### 环境要求

- CMake 3.10或更高版本
- C编译器（GCC、Clang或MSVC）

### 编译步骤

```bash
mkdir build
cd build
cmake ..
cmake --build .
ctest
```

### 编译选项

```bash
cmake .. -DCONFIG_SMALL=ON -DCONFIG_WERROR=OFF
```

| 选项 | 说明 | 默认值 |
|------|------|--------|
| CONFIG_PROFILE | 启用性能分析 | OFF |
| CONFIG_X86_32 | 32位x86架构 | OFF |
| CONFIG_ARM32 | 32位ARM架构 | OFF |
| CONFIG_WIN32 | Windows平台 | OFF |
| CONFIG_SMALL | 优化代码大小 | ON |
| CONFIG_WERROR | 警告视为错误 | OFF |

### 编译目标

```bash
# 静态库
cmake --build . --target mquickjs_static

# 动态库
cmake --build . --target mquickjs_shared

# 全部编译
cmake --build .
```

输出：
- 静态库：`mquickjs_static`
- 动态库：`mquickjs_shared`
- 可执行文件：`mqjs`（REPL）

---

## 严格模式

KQuickJS仅支持JavaScript的子集（主要是ES5）。它始终处于**严格模式**，禁用一些容易出错的JavaScript特性：

- 仅允许严格模式语法，不支持`with`关键字
- 全局变量必须使用`var`关键字声明
- 数组不能有空洞
- 仅支持全局`eval`
- 不支持值装箱

### ES5扩展

- `for of`支持（仅迭代数组）
- 类型化数组
- 幂运算符`**`
- 数学函数：`imul`、`clz32`、`fround`、`trunc`、`log2`、`log10`
- 字符串函数：`codePointAt`、`replaceAll`、`trimStart`、`trimEnd`
- `globalThis`全局属性

### 本仓库额外修改

- `let`关键字将被视为`var`，避免习惯问题

---

## 许可证

本项目采用MIT许可证发布。

### 许可证详情

- **原始C代码**：版权归Fabrice Bellard和Charlie Gordon所有，遵循MIT许可证
- **CMake构建系统**：由Attect指挥GLM5 AI完成，遵循MIT许可证
- **Kotlin实现**：由Attect指挥GLM5 AI完成，遵循MIT许可证

详见 [LICENSE](LICENSE) 文件。
