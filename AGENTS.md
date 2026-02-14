# mquickjs 开发规范

## 语言

 - 在编写任何文档时，必须使用中文。
 - 在编写任何代码注释时，必须使用中文。
 - 在思考和计划时，必须使用中文。

## 文档位置

所有文档位于 `docs/` 目录下

## 构建系统

本项目是基于 CMake 3.10+ 的 C99 项目。主要构建命令如下：

```bash
cmake -B build -S .
cmake --build build
ctest                      # 运行所有测试
cmake --build build --target test
```

### 可用的 CMake 选项

| 选项 | 说明 | 默认值 |
|------|------|--------|
| `CONFIG_PROFILE` | 启用性能分析 | OFF |
| `CONFIG_X86_32` | 32位 x86 架构 | OFF |
| `CONFIG_ARM32` | 32位 ARM 架构 | OFF |
| `CONFIG_WIN32` | Windows 平台（自动检测） | 自动检测 |
| `CONFIG_SOFTFLOAT` | 软浮点支持 | OFF |
| `CONFIG_ASAN` | 地址 sanitizer | OFF |
| `CONFIG_GPROF` | gprof 性能分析 | OFF |
| `CONFIG_SMALL` | 优化代码大小 | ON |
| `CONFIG_WERROR` | 将警告视为错误 | OFF |

### 构建目标

| 目标 | 描述 |
|------|------|
| `mquickjs_static` | 静态库 (`libmquickjs_static.a` / `.lib`) |
| `mquickjs_shared` | 动态库 (`libmquickjs.so` / `.dll`) |
| `mqjs` | 交互式解释器可执行文件 |
| `example` | C API 使用示例程序 |

### 运行单个测试

```bash
# 运行特定 JavaScript 测试
build/mqjs tests/test_closure.js
build/mqjs tests/test_language.js
build/mqjs tests/test_builtin.js
build/mqjs tests/test_loop.js
build/example tests/test_rect.js

# 构建并运行特定目标
cmake --build build --target mqjs
cmake --build build --target test
```

### 其他测试目标

- `microbench` - 运行微基准测试套件
- `octane` - 运行 Octane 基准测试（内存限制为 256MB）
- `size` - 显示二进制文件大小统计

## 代码风格规范

### 通用规则

1. **C 标准**：C99，启用 GNU 扩展 (`-D_GNU_SOURCE`)
2. **编译标志**：`-Wall -g -fno-math-errno -fno-trapping-math`
3. **优化选项**：`CONFIG_SMALL=ON` 时使用 `-Os`，否则使用 `-O2`

### 命名约定

| 实体类型 | 约定 | 示例 |
|---------|------|------|
| 类型 | 大驼峰命名法，前缀 `JS` | `JSContext`, `JSValue`, `JSGCRef` |
| 函数 | 小写下划线命名法，前缀 `js_` | `js_print`, `js_gc`, `JS_NewInt32` |
| 宏/常量 | 全大写下划线命名法 | `JS_TAG_INT`, `JS_TRUE` |
| 变量 | 小写下划线命名法 | `ctx`, `argc`, `buf_len` |

### 文件组织

- **头文件**：使用基于文件名的包含保护（如 `#ifndef MQUICKJS_H`）
- **源文件**：核心引擎包括 `mquickjs.c`、`dtoa.c`、`libm.c`、`cutils.c`
- **构建工具**：`mquickjs_build.c`、`mqjs_stdlib.c`、`example_stdlib.c`

### 头文件模板

```c
#ifndef MODULE_NAME_H
#define MODULE_NAME_H

#include <inttypes.h>  // 或其他必需的系统头文件

// 函数声明、类型定义、宏定义

#endif /* MODULE_NAME_H */
```

### 注释风格

- 所有文档使用 C 风格注释 (`/* ... */`)
- 文件头部包含版权说明和 MIT 许可证
- 函数注释描述功能、参数和返回值
- 行内注释解释非显而易见的逻辑

### 错误处理

- 使用 `JS_EXCEPTION` 作为异常指示器
- 使用 `JS_IsException()` 检查返回值
- 使用 `JS_ThrowTypeError()`、`JS_ThrowReferenceError()` 等抛出错误
- 调用 `JS_ToCString()` 后，必须使用 `JS_FreeCString()` 释放字符串

### 内存管理（关键）

MQuickJS 使用紧凑型垃圾回收器。重要规则：

1. **无需显式释放值** - 不需要调用 `JS_FreeValue()`
2. **值可能移动** - 在跨 API 调用存储时，必须使用指向 `JSValue` 的指针
3. **长生命周期值使用 GC 引用**：
   ```c
   JSGCRef ref;
   JSValue *val = JS_PushGCRef(ctx, &ref);
   // ... 使用 val ...
   JS_PopGCRef(ctx, &ref);
   ```

### 严格模式要求

引擎仅支持 JavaScript 严格模式：

- 禁止 `with` 语句
- 所有全局变量必须使用 `var` 声明
- 数组不能有"空洞" - 不支持 `[1, , 3]` 语法
- 仅支持间接（全局）`eval`：`(0, eval)('code')`
- 不支持值装箱：不支持 `new Number(1)`

### 测试要求

1. 所有测试位于 `tests/` 目录，文件名格式为 `test_*.js`
2. 使用 `assert(actual, expected, message)` 进行断言验证
3. 使用 `assert_throws(ErrorType, func)` 进行异常测试
4. 测试必须在无用户交互的情况下运行

## Kotlin 移植版本

Kotlin 移植版本位于 `kotlin/` 目录下，使用 Gradle 构建系统。

### 构建命令

```bash
cd kotlin
./gradlew build        # 构建项目
./gradlew test         # 运行所有测试
./gradlew test --tests MQuickJSTest.testObjectLiteral  # 运行单个测试
```

### 项目结构

```
kotlin/
├── src/main/kotlin/
│   ├── MQuickJS.kt           # 核心类型定义和公共 API
│   ├── parser/               # JavaScript 解析器
│   │   ├── JSParser.kt       # 语法解析器
│   │   └── JSParseState.kt   # 解析状态
│   └── runtime/
│       └── JSRuntime.kt      # 字节码执行引擎
└── src/test/kotlin/
    └── MQuickJSTest.kt       # 测试用例
```

### 关键实现注意事项

1. **栈操作转换**：C 语言栈向低地址增长，Kotlin 使用 `MutableList`。转换规则见 `docs/kotlin_runtime_fix_guide.md`
2. **内存模型**：Kotlin 版本使用 `ByteArray` 模拟内存，通过偏移量访问
3. **值表示**：`JSValue` 为 64 位整数，使用标签区分类型

### 参考文档

- [Kotlin 运行时修复指南](docs/kotlin_runtime_fix_guide.md) - 记录字节码执行引擎的修复过程和栈操作转换规则

## AI 助手注意事项

1. **不要修改** `build/` 或 `cmake-build-*` 目录中的构建产物
2. 生成的头文件 (`mquickjs_atom.h`、`mqjs_stdlib.h`) 在构建过程中创建
3. 添加函数时，需更新 `mquickjs_build.c` 将其包含到标准库中
4. 当 `CONFIG_WERROR=ON` 时，所有代码必须能通过 `-Wall -Werror` 编译
5. 所有字符串操作使用 UTF-8 编码
6. 可移植 C 代码请使用 `cutils.h` 中的工具函数（如 container_of、min/max 等）
7. **Kotlin 项目**：修改 `JSRuntime.kt` 时，参考 C 语言原始实现确保栈操作正确
8. **调试方法**：遇到难以定位的问题时，在 C 代码中添加相同输出并对比执行流程
