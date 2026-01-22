MicroQuickJS
============

## 简介

MicroQuickJS（简称MQuickJS）是一款面向嵌入式系统的JavaScript引擎。它可以编译和运行JavaScript程序，内存占用低至10KB。整个引擎约需100KB的ROM空间（ARM Thumb-2代码），包括C库。其运行速度可与QuickJS相媲美。

MQuickJS仅支持JavaScript的[子集](#javascript子集参考)，接近ES5标准。它实现了**严格模式**，禁止一些容易出错或低效的JavaScript语法结构。

尽管MQuickJS与QuickJS共享大量代码，但其内部实现不同，以降低内存占用。特别是，它依赖追踪式垃圾回收器，虚拟机不使用CPU栈，字符串以UTF-8存储。

## 构建

本项目使用CMake构建系统。请按照以下步骤编译：

### 环境要求

- CMake 3.10或更高版本
- C编译器（GCC、Clang或MSVC）

### 编译步骤

```bash
# 创建构建目录
mkdir build
cd build

# 配置项目
cmake ..

# 编译
cmake --build .

# 运行测试
ctest
```

### 编译选项

CMake配置时可以使用以下选项：

```bash
cmake .. -DCONFIG_PROFILE=OFF -DCONFIG_X86_32=OFF -DCONFIG_ARM32=OFF -DCONFIG_WIN32=OFF -DCONFIG_SOFTFLOAT=OFF -DCONFIG_ASAN=OFF -DCONFIG_GPROF=OFF -DCONFIG_SMALL=ON -DCONFIG_WERROR=OFF
```

| 选项 | 说明 | 默认值 |
|------|------|--------|
| CONFIG_PROFILE | 启用性能分析 | OFF |
| CONFIG_X86_32 | 32位x86架构 | OFF |
| CONFIG_ARM32 | 32位ARM架构 | OFF |
| CONFIG_WIN32 | Windows平台 | OFF |
| CONFIG_SOFTFLOAT | 软浮点支持 | OFF |
| CONFIG_ASAN | 地址 sanitizer | OFF |
| CONFIG_GPROF | gprof性能分析 | OFF |
| CONFIG_SMALL | 优化代码大小 | ON |
| CONFIG_WERROR | 警告视为错误 | OFF |

### 编译静态库

默认情况下会编译静态库：

```bash
# 编译静态库
cmake --build . --target mquickjs_static

# 静态库输出位置
# Linux/macOS: build/libmquickjs_static.a
# Windows: build/mquickjs_static.lib
```

### 编译动态库

```bash
# 编译动态库
cmake --build . --target mquickjs_shared

# 动态库输出位置
# Linux: build/libmquickjs.so
# macOS: build/libmquickjs.dylib
# Windows: build/mquickjs.dll
```

### 同时编译所有目标

```bash
cmake --build .
```

这将编译：
- 静态库：mquickjs_static
- 动态库：mquickjs_shared
- 可执行文件：mqjs（REPL）
- 可执行文件：example（示例程序）

## REPL

MQuickJS提供了一个交互式解释器`mqjs`。

### 使用方法

```
用法: mqjs [选项] [文件 [参数]]
-h  --help            显示帮助信息
-e  --eval EXPR       执行表达式EXPR
-i  --interactive     进入交互模式
-I  --include file    包含额外文件
-d  --dump            输出内存使用统计
    --memory-limit n  限制内存使用量为n字节
--no-column           移除调试信息中的列号
-o FILE               将字节码保存到FILE
-m32                  强制生成32位字节码（与-o选项配合使用）
-b  --allow-bytecode  允许输入文件中的字节码
```

### 示例

使用10KB内存运行程序：

```bash
./mqjs --memory-limit 10k tests/mandelbrot.js
```

### 字节码持久化

除了正常执行脚本外，`mqjs`还可以将编译后的字节码输出到持久存储（文件或ROM）：

```bash
./mqjs -o mandelbrot.bin tests/mandelbrot.js
```

然后可以像普通脚本一样运行编译后的字节码：

```bash
./mqjs -b mandelbrot.bin
```

字节码格式取决于CPU的字节序和字长（32位或64位）。在64位CPU上，可以使用`-m32`选项生成可在32位嵌入式系统上运行的32位字节码。

使用`--no-column`选项可以移除列号调试信息（仅保留行号），以节省存储空间。

## 严格模式

MQuickJS仅支持JavaScript的子集（主要是ES5）。它始终处于**严格模式**，禁用一些容易出错的JavaScript特性。严格模式是JavaScript的子集，因此在其他JavaScript引擎中仍能正常工作。主要特点如下：

- 仅允许严格模式语法，因此不支持`with`关键字，全局变量必须使用`var`关键字声明。

- 数组不能有空洞。在数组末尾之后写入元素是不允许的：

```javascript
a = []
a[0] = 1;  // 扩展数组长度是允许的
a[10] = 2; // 抛出TypeError
```

如果需要带有空洞的类数组对象，请改用普通对象：

```javascript
a = {}
a[0] = 1;
a[10] = 2;
```

`new Array(len)`仍然按预期工作，但数组元素初始化为`undefined`。带有空洞的数组字面量是语法错误：

```javascript
[ 1, , 3 ] // 语法错误
```

- 仅支持全局`eval`，因此无法访问或修改局部变量：

```javascript
eval('1 + 2');       // 禁止
(1, eval)('1 + 2');  // 允许
```

- 不支持值装箱：`new Number(1)`不支持，也从不必要。

## JavaScript子集参考

- 仅支持严格模式，强调ES5兼容性。

- `Array`对象：
  - 没有空洞。
  - 数字属性始终由数组对象处理，不会转发到其原型。
  - 越界写入是错误，除非在数组末尾。
  - `length`属性是数组原型中的getter/setter。

- 所有属性都是可写、可枚举和可配置的。

- `for in`仅迭代对象的自身属性。应与以下常用模式一起使用，以与标准JavaScript保持一致的行为：

```javascript
for(var prop in obj) {
    if (obj.hasOwnProperty(prop)) {
        ...
    }
}
```

始终优先使用`for of`，它在数组上受支持：

```javascript
for(var prop of Object.keys(obj)) {
    ...
}
```

- `prototype`、`length`和`name`是函数对象中的getter/setter。

- C函数不能有自己的属性（但C构造函数的行为符合预期）。

- 支持全局对象，但不鼓励使用。它不能包含getter/setter，直接在其中创建的属性在执行脚本中不可见为全局变量。

- 与`catch`关键字关联的变量是普通变量。

- 不支持直接`eval`。仅支持间接（=全局）`eval`。

- 不支持值装箱（例如`new Number(1)`不支持）。

- 正则表达式：
  - 大小写折叠仅适用于ASCII字符。
  - 匹配仅支持unicode，即`/./`匹配unicode码点而不是UTF-16字符（带`u`标志时）。

- 字符串：`toLowerCase`/`toUpperCase`仅处理ASCII字符。

- 日期：仅支持`Date.now()`。

### ES5扩展

- `for of`支持，但仅迭代数组。不支持自定义迭代器（目前）。

- 类型化数组。

- `\u{hex}`在字符串字面量中被接受。

- 数学函数：`imul`、`clz32`、`fround`、`trunc`、`log2`、`log10`。

- 幂运算符`**`。

- 正则表达式：接受dotall（`s`）、sticky（`y`）和unicode（`u`）标志。在unicode模式下，不支持unicode属性。

- 字符串函数：`codePointAt`、`replaceAll`、`trimStart`、`trimEnd`。

- `globalThis`全局属性。

### 本仓库额外修改

- `let`关键字将被视为`var`，避免习惯问题。

## C API

MQuickJS的C API与QuickJS非常相似（参见`mquickjs.h`）。但由于使用紧凑型垃圾回收器，存在重要差异。

### 引擎初始化

MQuickJS几乎不依赖C库。特别是它不使用`malloc()`、`free()`或`printf()`。创建MQuickJS上下文时，必须提供一个内存缓冲区。引擎只在此缓冲区中分配内存：

```c
#include "mquickjs.h"

JSContext *ctx;
uint8_t mem_buf[8192];

ctx = JS_NewContext(mem_buf, sizeof(mem_buf), &js_stdlib);
if (!ctx) {
    fprintf(stderr, "Failed to create context\n");
    return -1;
}

// 使用ctx执行JavaScript代码...

JS_FreeContext(ctx);
```

`JS_FreeContext(ctx)`仅需要调用用户对象的终结器，因为引擎不分配系统内存。

### 内存处理

由于存在紧凑型垃圾回收器，需要注意以下重要差异：

1. 不需要显式释放值（没有`JS_FreeValue()`）。

2. 每次调用JS分配时，对象的地址可能会移动。一般规则是避免在C中使用`JSValue`类型的变量。它们只能临时存在于MQuickJS API调用之间。在其他情况下，始终使用指向`JSValue`的指针。`JS_PushGCRef()`返回一个临时不透明`JSValue`的指针，存储在`JSGCRef`变量中。必须使用`JS_PopGCRef()`释放临时引用。当对象移动时，`JSGCRef`中的不透明值会自动更新。

示例：

```c
static JSValue my_js_function(JSContext *ctx, JSValue this_val, int argc, JSValue *argv)
{
    JSGCRef obj1_ref, obj2_ref;
    JSValue *obj1, *obj2, ret;

    ret = JS_EXCEPTION;
    obj1 = JS_PushGCRef(ctx, &obj1_ref);
    obj2 = JS_PushGCRef(ctx, &obj2_ref);
    if (!obj1 || !obj2)
        goto fail;

    *obj1 = JS_NewObject(ctx);
    if (JS_IsException(*obj1))
        goto fail;
    *obj2 = JS_NewObject(ctx);  // obj1可能会移动
    if (JS_IsException(*obj2))
        goto fail;

    JS_SetPropertyStr(ctx, *obj1, "x", *obj2);  // obj1和obj2可能会移动
    ret = *obj1;

fail:
    JS_PopGCRef(ctx, &obj2_ref);
    JS_PopGCRef(ctx, &obj1_ref);
    return ret;
}
```

在PC上运行时，可以使用`DEBUG_GC`定义强制JS分配器在每次分配时总是移动对象。这是检查是否使用了无效`JSValue`的好方法。

### 标准库

标准库由自定义工具（`mquickjs_build.c`）编译为可驻留在ROM中的C结构。因此，标准库实例化非常快速，几乎不需要RAM。`mqjs`提供了一个标准库示例（`mqjs_stdlib.c`），其编译结果为`mqjs_stdlib.h`。

### 在项目中使用MQuickJS库

#### 使用静态库

在CMake项目中：

```cmake
add_executable(my_app main.c)
target_link_libraries(my_app mquickjs_static)
```

直接链接：

```bash
gcc -o my_app main.c -I. -L./build -lmquickjs_static -lm
```

#### 使用动态库

在CMake项目中：

```cmake
add_executable(my_app main.c)
target_link_libraries(my_app mquickjs_shared)
```

直接链接：

```bash
gcc -o my_app main.c -I. -L./build -lmquickjs -lm
```

#### 完整示例

创建一个简单的C程序，使用MQuickJS执行JavaScript代码：

```c
#include <stdio.h>
#include <string.h>
#include "mquickjs.h"

int main(int argc, char *argv[])
{
    uint8_t mem_buf[8192];
    JSContext *ctx;
    JSValue result;
    const char *code = "1 + 2 * 3";

    ctx = JS_NewContext(mem_buf, sizeof(mem_buf), &js_stdlib);
    if (!ctx) {
        fprintf(stderr, "Failed to create context\n");
        return 1;
    }

    result = JS_Eval(ctx, code, strlen(code), "<input>", JS_EVAL_TYPE_GLOBAL);
    if (JS_IsException(result)) {
        JSValue error = JS_GetPropertyStr(ctx, result, "message");
        const char *error_msg = JS_ToCString(ctx, error);
        fprintf(stderr, "Error: %s\n", error_msg);
        JS_FreeCString(ctx, error_msg);
        JS_FreeValue(ctx, error);
        JS_FreeValue(ctx, result);
        JS_FreeContext(ctx);
        return 1;
    }

    int value = JS_ToInt32(ctx, result);
    printf("Result: %d\n", value);

    JS_FreeValue(ctx, result);
    JS_FreeContext(ctx);

    return 0;
}
```

编译并运行：

```bash
# 使用静态库编译
gcc -o my_example example.c -I. -I./build -L./build -lmquickjs_static -lm

# 运行
./my_example
```

### 持久化字节码

`mqjs`生成的字节码可以从ROM执行。在这种情况下，必须在烧录到ROM之前重新定位它（参见`JS_RelocateBytecode()`）。然后使用`JS_LoadBytecode()`实例化，并使用`JS_Run()`作为正常脚本运行（参见`mqjs.c`）。

与QuickJS一样，字节码级别不保证向后兼容性。此外，字节码在执行之前不会被验证。仅运行来自可信源的JavaScript字节码。

### 数学库和浮点仿真

MQuickJS包含自己的小型数学库（`libm.c`）。此外，如果CPU没有浮点支持，它包含自己的浮点仿真器，可能比GCC工具链提供的更小。

### 字节码反汇编

可以使用`JS_DumpFunction`函数打印函数的字节码用于调试：

```c
JSValue func_obj = JS_GetPropertyStr(ctx, global, "myFunction");
if (JS_IsFunction(ctx, func_obj)) {
    JS_DumpFunction(ctx, func_obj);
}
JS_FreeValue(ctx, func_obj);
```

## 内部实现和与QuickJS的比较

### 垃圾回收

使用追踪式和紧凑型垃圾回收器代替引用计数。它允许更小的对象。GC为每个分配的内存块增加几位的开销。此外，避免了内存碎片。

引擎有自己的内存分配器，不依赖C库的malloc。

### 值和对象表示

值的大小与CPU字相同（因此在32位CPU上是32位）。一个值可以包含：

- 31位整数（1位标签）
- 单个unicode码点（因此是一到两个16位代码单元的字符串）
- 64位浮点数，在64位CPU字上使用小数指数
- 指向内存块的指针。内存块中存储了标签。

JavaScript对象至少需要3个CPU字（因此在32位CPU上是12字节）。根据对象类可能会分配额外的数据。属性存储在哈希表中。每个属性至少需要3个CPU字。标准库对象的属性可能驻留在ROM中。

属性键是`JSValue`，与QuickJS不同（QuickJS有特定类型）。它们可以是字符串或正31位整数。字符串属性键是内部化的（唯一的）。

字符串内部存储为WTF-8（UTF-8 + 未配对的代理项），而不是QuickJS中的8或16位数组。代理对不显式存储，但在通过JavaScript中的16位代码单元迭代时仍然可见。因此保持了与JavaScript和UTF-8的完全兼容性。

C函数可以作为单个值存储以减少开销。在这种情况下，不能添加额外的属性。大多数标准库函数都以这种方式存储。

### 标准库

整个标准库驻留在ROM中。它在编译时生成。只有少数对象在RAM中创建。因此引擎实例化时间非常低。

### 字节码

这是基于栈的字节码（与QuickJS类似）。然而，字节码通过间接表引用原子。

行和列号信息使用[指数Golomb编码](https://en.wikipedia.org/wiki/Exponential-Golomb_coding)压缩。

### 编译

解析器与QuickJS非常相似，但它避免递归，因此C栈使用是有界的。没有抽象语法树。字节码通过多种优化技巧一次生成（QuickJS有多个优化过程）。

## 测试和基准

### 运行测试

```bash
# 在build目录下运行
ctest

# 或运行特定测试
./mqjs ../tests/test_closure.js
./mqjs ../tests/test_language.js
./mqjs ../tests/test_loop.js
./mqjs ../tests/test_builtin.js
```

### 运行微基准测试

```bash
./mqjs ../tests/microbench.js
```

### 运行Octane基准测试

```bash
./mqjs --memory-limit 256M ../tests/octane/run.js
```

### 查看二进制文件大小

```bash
# Linux/macOS
size ./mqjs

# Windows
dumpbin /SIZE ./mqjs.exe
```

## 许可证

MQuickJS根据MIT许可证发布。

除非另有说明，MQuickJS源代码的版权归Fabrice Bellard和Charlie Gordon所有。
