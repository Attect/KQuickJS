# KQuickJS 全面性能评估报告

> 评估对象：Kotlin/JVM 移植版（默认 JVM、GraalVM 原生镜像两种运行形态）与 C 参考实现。
> 评估日期：2026-11-11。
> 本文所有数据均由本机实测得到，复现脚本与原始结果见第 7 节。

> **更新（2026-11-11）**：本文记录的 P0 问题（split/match/JSON.parse 的 GC 保活缺失、
> `runBody` 巨型方法导致默认 JVM 主循环从未被 JIT 编译）已修复，并完成了属性表、
> 解析线性化、原生镜像体积等优化。修复后关键指标：完整 microbench 汇总值
> 568,975.8 → **59,915.5**（C 的 22.6x，修复前 222.7x）；fib(27) 6.15 s → **0.63 s**；
> mandelbrot 3.02 s → **0.46 s**；4000 函数解析 5.6 s → **0.97 s**；
> 原生镜像 227 MB → **31 MB**。复测详情与实现说明见
> [performance_optimization_2026-11.md](performance_optimization_2026-11.md)。
> 以下正文保留修复前的基线数据，作为前后对比依据。

---

## 摘要

1. **默认 JVM 形态存在决定性性能问题**：解释器主循环 `JSRuntime.runBody` 的字节码长度为
   **13,182 字节**，超过 HotSpot 的 `HugeMethodLimit`（默认 8000），因此**从未被 JIT 编译**，
   全程以字节码解释方式执行。计算密集型负载因此比 C 版慢 **约 500 倍**（完整套件 39 项
   可比值几何平均 544.8x，汇总值 222.7x）。
2. **仅一个 JVM 参数即可换回约 10 倍性能**：`-XX:-DontCompileHugeMethods` 强制 C2 尝试编译
   `runBody` 后，fib(27) 从 6.15 s 降到 0.74 s（8.3x），15 项可比值（不含单位不同的
   `sort_bench`）几何平均降到 53.1x C。
   但该方式不稳定——同一参数下 mandelbrot 仍因 C1/C2 编译失败（`out of virtual registers`、
   `out of nodes after split`）而无任何收益。**根本解法是把 13 KB 的巨型分发方法拆小。**
3. **原生镜像形态最佳**：启动约 35 ms（JVM 约 190 ms，C 约 25 ms），fib 357.6 ms、
   mandelbrot 234.9 ms，比默认 JVM 快 7–17 倍；但 microbench 上仍比 C 慢 **约 29–37 倍**
   （几何平均 36.9x），端到端负载按类型不同在 2.6–43 倍之间，且**解析性能反而比 JVM 慢 2.4 倍**
   （4000 函数 13.8 s vs 5.6 s）。
4. **解析/编译吞吐明显偏低且随规模超线性恶化**：4000 个函数（398 KB）JVM 需 5.6 s、
   原生镜像 13.8 s、C 仅 0.33 s；函数数从 2000 增到 4000 时 Kotlin 耗时增长约 3.5–3.8 倍
   （近似 O(n²)）。
5. **内存足迹与部署体积**：标准库引导占用 **80,592 B**（C 版 6,360 B，12.7 倍），最小可用堆
   160 KB（C 64 KB）；解析 4000 函数时 JVM 峰值 RSS **812 MB–1.25 GB**（C 10 MB，原生 79 MB）；
   原生可执行文件 **227 MB**（`resource-config.json` 用 `.*` 内嵌了约 172 MB 资源），
   而 C 可执行文件仅 1.35 MB。
6. **发现影响基准有效性与生产可用性的正确性缺陷（P0）**：`String.prototype.split` 非正则路径、
   全局 `String.match` 与 `JSON.parse` 的对象/数组构造路径均未把目标值放入 GC 保活列表，
   GC 压缩搬移后返回悬垂指针。在 16–128 MB 堆区间内（取决于输入规模）`split` 与
   `JSON.parse` 会**静默**返回 `NaN` / 空对象（退出码仍为 0），只有加大堆到不触发 GC
   才正确；C 版在 16 MB 堆下全部正确。本报告的字符串基准因此改用 128 MB 堆，详见 4.6 节。

---

## 1. 评估环境与方法

### 1.1 硬件与系统

| 项目 | 值 |
|---|---|
| CPU | AMD Ryzen 9 7950X（16 核 / 32 线程，最高 4.5 GHz） |
| 内存 | 132.8 GB |
| 操作系统 | Windows 11 专业版 10.0.26300 |
| C 编译器 | MinGW-w64 gcc 15.2.0，`-O2`（`CONFIG_SMALL=OFF`） |
| JVM | Microsoft OpenJDK 17.0.6（HotSpot，默认参数） |
| 原生镜像 | GraalVM CE 17.0.9（native-image 23.0-b22，serial GC） |
| 构建 | CMake 3.24.4、Gradle 8.5 |

### 1.2 被测形态

| 代号 | 说明 | 启动方式 |
|---|---|---|
| `jvm` | Kotlin 版 JAR，HotSpot 默认参数 | `java -jar kquickjs-1.0.0.jar` |
| `jvm-opt` | 同上，附加 `-XX:-DontCompileHugeMethods` | `java -XX:-DontCompileHugeMethods -jar ...` |
| `native` | GraalVM Native Image（`nativeCompile`） | `kquickjs.exe` |
| `c-o2` | C 参考实现，gcc -O2 | `mqjs.exe` |

构建产物：

| 产物 | 大小 |
|---|---|
| `kquickjs-1.0.0.jar` | 2,245,809 B（2.14 MiB） |
| `kquickjs.exe`（原生镜像） | 227,217,408 B（216.7 MiB） |
| `mqjs.exe`（C，-O2） | 1,354,934 B（1.29 MiB） |

### 1.3 测量口径

- 所有用例均为**独立进程**运行，包含引擎初始化与标准库引导；计时在进程外使用
  `System.Diagnostics.Process`，从 `Start()` 到退出，峰值 RSS 每 10 ms 采样一次。
- 常规用例重复 3 次取**中位数**，并记录峰值 RSS 的最大值。
- microbench 单项沿用套件自身的计时逻辑（`Date.now`，1 ms 分辨率，取多轮最小值），
  每个引擎单独启动进程，因此包含各自的冷启动与 JIT 预热过程。
- 完整 `tests/microbench.js`（42 项）：C 与默认 JVM 均跑完整套件
  （C 122.8 s；JVM 412.9 s、峰值 RSS 1.30 GB）；`jvm-opt` 与 `native` 为控制总时长，
  按 16 项代表性用例逐项对比（见第 3 节）。
- 机器为空闲状态运行；Windows 定时器与 JIT 波动使单次测量存在约 ±5% 噪声。

---

## 2. 端到端工作负载结果

### 2.1 综合表（单位：ms，中位数）

| 用例 | 说明 | jvm | jvm-opt | native | c-o2 | jvm/C | opt/C | native/C |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| startup_eval | `-e 1+1` 冷启动 | 190.6 | 193.4 | 34.7 | 24.9 | 7.7x | 7.8x | 1.4x |
| fib27 | 递归 63.6 万次调用 | 6147.9 | 738.3 | 357.6 | 47.3 | 130x | 15.6x | 7.6x |
| alloc_gc | 30 万个对象 + 2 万数组 | 6593.0 | 1041.9 | 932.0 | 78.6 | 84x | 13.3x | 11.9x |
| string_ops | 1 万段拼接 ×200 次切分（128 MB 堆） | 573.0 | 593.0 | 925.0 | 352.0 | 1.6x | 1.7x | 2.6x |
| mandelbrot | 80×25 二阶 Mandelbrot | 3022.8 | 3035.6 | 234.9 | 46.2 | 65x | 66x | 5.1x |
| parse_compile | 4000 函数 398 KB，仅解析+写字节码 | 5605.5 | 5471.6 | 13780.0 | 327.6 | 17x | 16.7x | 42x |
| parse_exec | 4000 函数，解析+执行 | 5696.6 | 5690.9 | 14300.0 | 329.6 | 17x | 17.3x | 43x |

> 启动时间含约 24–31 ms 的进程创建/测量下限（本机 Python `subprocess` 空进程下限实测
> 中位数 31 ms、最小 24 ms），因此 C 与 native 的真实启动差异被该下限掩盖，
> 可确定的是 JVM 额外多出约 160 ms（`jvm` 与 `jvm-opt` 分别单独测量）。

### 2.2 换算后的吞吐

| 指标 | jvm | jvm-opt | native | c-o2 |
|---|---:|---:|---:|---:|
| fib 调用速率 | 103 k/s | 861 k/s | 1.78 M/s | 13.4 M/s |
| 解析吞吐（398 KB） | 69.3 KB/s | 71.0 KB/s | 28.2 KB/s | 1186 KB/s |
| 解析函数速率 | 714 函数/s | 731 函数/s | 290 函数/s | 12,210 函数/s |

### 2.3 峰值 RSS（MB）

| 用例 | jvm | jvm-opt | native | c-o2 |
|---|---:|---:|---:|---:|
| startup_eval | 86.4 | 86.7 | 6.1 | 3.6 |
| fib27 | 173.5 | 194.1 | 76.5 | 4.4 |
| alloc_gc | 197.5 | 266.4 | 77.7 | 21.0 |
| mandelbrot | 105.9 | 200.7 | 56.1 | 5.7 |
| parse_compile | 1087.8 | 1250.0 | 78.9 | 10.4 |
| parse_exec | 812.5 | 987.4 | 79.3 | 10.5 |

JVM 的 RSS 主要来自 JVM 堆按需扩张（默认最大堆约为物理内存的 1/4），而非引擎自身
堆（引擎堆固定为 `--memory-limit` 指定的 ByteArray，默认 16 MB）。用 `-Xmx256m`
跑同一解析负载，耗时 6.72 s（默认 5.6–6.5 s，基本不变），峰值 RSS 的上限随之为约 250 MB。

---

## 3. microbench 逐项对比（ns/操作）

### 3.1 完整套件（42 项）

| 引擎 | 用例数 | 套件耗时 | 汇总值（各用例 ns/op 之和） | 峰值 RSS | 相对 C |
|---|---:|---:|---:|---:|---:|
| jvm | 42 | 412.9 s | 568,975.80 | 1,304.1 MB | **222.7x** |
| c-o2 | 42 | 122.8 s | 2,554.40 | 21.0 MB | 1x |

- 39 项可比用例的几何平均：**544.8x**（JVM 为 C 的倍数）。
- 差距最大的用例：`global_write_strict`（1,733x）、`array_write`（1,600x）、
  `array_length_read`（1,482x）、`string_length`（1,464x）、`array_read`（1,462x）、
  `prop_write`（1,417x）、`global_read`（1,364x）、`prop_read`（1,312x）。
- 差距最小的用例：`string_build1`（28x）、`string_build2`（31x）、`regexp_replace`（42x）、
  `float_to_string`（100x）、`date_now`（198x）、`regexp_utf16`（210x）。
- JVM 套件总耗时只有 C 的 3.4 倍，是因为 microbench 会自动缩放迭代次数以凑满约 100 ms
  的测量窗口；**ns/op 的差距（222.7x 汇总值、544.8x 几何平均）才反映真实单操作成本**。

### 3.2 代表用例（16 项，含 jvm-opt 与 native）

C 版完整套件耗时 122.8 s，`total 2554.40`；下表为 16 项代表用例
（数值越小越好，取套件内部最小值）：

| 用例 | jvm | jvm-opt | native | c-o2 | jvm/C | opt/C | native/C |
|---|---:|---:|---:|---:|---:|---:|---:|
| empty_loop | 5000 | 340 | 130 | 6.0 | 833x | 57x | 22x |
| prop_read | 10500 | 700 | 325 | 6.5 | 1615x | 108x | 50x |
| prop_write | 7875 | 500 | 250 | 5.0 | 1575x | 100x | 50x |
| array_read | 8500 | 550 | 220 | 6.8 | 1250x | 81x | 32x |
| array_write | 7000 | 500 | 260 | 5.0 | 1400x | 100x | 52x |
| array_push | 10000 | 840 | 1040 | 24.0 | 417x | 35x | 43x |
| array_pop | 16500 | 1000 | 920 | 21.6 | 764x | 46x | 43x |
| closure_read | 7000 | 500 | 225 | 6.5 | 1077x | 77x | 35x |
| func_call | 13750 | 975 | 400 | 13.5 | 1019x | 72x | 30x |
| int_arith | 14000 | 900 | 400 | 11.0 | 1273x | 82x | 36x |
| float_arith | 16200 | 1200 | 500 | 18.0 | 900x | 67x | 28x |
| string_build1 | 14000 | 10200 | 13000 | 540 | 26x | 19x | 24x |
| string_build2 | 15000 | 10200 | 18000 | 500 | 30x | 20x | 36x |
| int_to_string | 13000 | 900 | 800 | 44 | 295x | 20x | 18x |
| regexp_ascii | 19800 | 4000 | 12000 | 101 | 196x | 40x | 119x |
| **几何平均（不含 sort_bench）** | — | — | — | — | **526.8x** | **53.1x** | **36.9x** |

> `sort_bench` 返回的是“总时间 / 元素数 / 1000”，单位与其它项不同，不参与几何平均；
> 实测 jvm 0.8、jvm-opt 1.4、native 1.6、c-o2 2.0，仅能说明 Kotlin 的数组排序
> （委托 JVM `Arrays.sort`）在排序场景并不落后，C 版 `qsort` 反而较慢。

### 观察

- **纯计算、属性访问、数组读写、闭包与函数调用**：默认 JVM 慢 400–1600 倍，是解释执行的
  典型特征；开启 `-DontCompileHugeMethods` 后差距缩小到 20–108 倍；原生镜像 18–52 倍。
- **字符串构建（string_build1/2）是例外**：JVM 仅慢 19–30 倍，且 `jvm-opt` 只改善约 1.4 倍。
  说明该用例的时间主要花在 `StringBuilder`/WTF-8 转换等 Kotlin 标准库操作上，
  而不是解释器分发——这类负载应优先优化字符串实现（见第 5 节 P2）。
- **正则**：原生镜像最差（119x C），默认 JVM 196x。`java.util.regex` 的每次 `exec`
  匹配开销远高于 C 版自带的正则引擎，需要缓存 `Pattern` 并减少匹配结果包装。
- **数组 push/pop**：native 与 jvm-opt 几乎持平（1040 vs 840 ns），说明原生镜像没有
  为集合扩容路径带来额外收益，瓶颈在 Kotlin 实现的元素写入/GC 检查。

---

## 4. 根因分析

### 4.1 核心：解释器主循环从未被 JIT 编译

`kotlin/src/main/kotlin/runtime/JSRuntime.kt` 的 `runBody` 把所有 opcode 写在**一个方法**
的单个 `when (op)` 中，javap/类文件解析结果显示其 `code_length = 13182` 字节：

```
CODE_LEN  METHOD
   13182  app.muka.project.kquickjs.runtime.JSRuntime.runBody(JJLjava/util/List;J)J
超过 HugeMethodLimit(8000) 的方法数: 1
```

Kotlin 版把整个 JS 指令循环放在该方法内部（一个方法调用内含完整指令循环），因此：

- HotSpot 默认 `DontCompileHugeMethods=true`，**C1/C2 直接拒绝编译**该方法的任何版本；
- `-XX:+PrintCompilation` 在 mandelbrot/fib 运行期间**从未出现 `JSRuntime::runBody`**，
  只有小方法（如 `runBody$funcPtrNow`）被编译；
- 用 `-Xint`（纯解释）对比：mandelbrot 3.90 s（默认 3.02 s）、fib 11.84 s（默认 6.15 s），
  仅慢 29%–93%，证明默认模式的主体同样是解释执行，JIT 只编译了属性访问、分配等辅助方法。

强制编译（`-XX:-DontCompileHugeMethods`）后的结果：

| 用例 | 默认 | 强制编译 | 提升 |
|---|---:|---:|---:|
| fib27 | 6147.9 ms | 738.3 ms | **8.3x** |
| alloc_gc | 6593.0 ms | 1041.9 ms | **6.3x** |
| mandelbrot | 3022.8 ms | 3035.6 ms | 无 |
| parse_exec | 5696.6 ms | 5690.9 ms | 无 |

PrintCompilation 显示，强制编译时 C1/C2 对 `runBody` 仍经常失败：

```
JSRuntime::runBody (13182 bytes)  COMPILE SKIPPED: out of virtual registers in LIR generator (retry at different tier)
JSRuntime::runBody @ 490 (13182 bytes)  COMPILE SKIPPED: out of nodes after split (retry at different tier)
```

即：13 KB 的单方法不仅超过阈值，还超出 C2 的寄存器/节点预算。**因此真正的修复不是调参数，
而是结构性拆分**：把 `when (op)` 的各个分支体提取为独立的 `private fun opXxx(...)`，
主循环只保留“取指 + 跳转”，使每个编译单元降到 1 KB 量级；主循环本身也可拆成
若干 `handleXxx(pc, ...): Int` 段。拆分后默认 JVM 即可获得至少 6–10 倍提升，
并消除“同一份代码在不同负载下有时能编译、有时不能”的不确定性。

### 4.2 每次函数调用都复制一份字节码

`JSRuntime.kt` 第 78–81 行：

```kotlin
/* 字节码每次从堆复制（不缓存）：... */
val code = ctx.memory.getBytes(byteCodePtr + 8, byteCodeLen)
```

该注释说明最近一次提交（`ff9b92e`）为修复“指针地址为 key 的缓存命中陈旧副本”
而**移除了缓存**，退化为每次调用分配并复制一份 `ByteArray`。对 fib(27) 这类
63 万次调用的负载，即产生 63 万次额外分配/复制，同时增加 GC 压力。

建议：以「函数对象标识 + GC 时代号（epoch）」为 key 缓存，或在 `JSGC` 搬移时同步更新
缓存 key；也可把执行循环改为直接在 `Memory.buffer` 上按偏移取指（仅在 GC 搬移后重取基址），
避免整段复制。

### 4.3 解析性能超线性

固定模板生成 N 个小函数，仅解析并写字节码（`-o`），耗时如下：

| 函数数 | 文件大小 | jvm | native | c-o2 |
|---:|---:|---:|---:|---:|
| 500 | 49 KB | 531 ms | 342 ms | 84 ms |
| 1000 | 99 KB | 886 ms | 924 ms | 69 ms |
| 2000 | 198 KB | 1934 ms | 3660 ms | 134 ms |
| 4000 | 398 KB | 6678 ms | 13780 ms | 353 ms |

> 上表为单次测量；4000 行在 3 次重复下的中位数为 JVM 5605 ms、C 328 ms（2.1 节）。
> 单次与中位数差异属正常波动，不影响超线性的结论。

- 函数数翻倍时，Kotlin 耗时增长 2.7–4.0 倍（近似 O(n²)），C 版基本线性（2 倍左右）。
- 原生镜像在 4000 函数时反而比 JVM 慢 2.4 倍，说明该路径在 AOT 下未获得足够优化
  （或触发了串行 GC 的频繁回收）。
- 可疑点：原子/属性名驻留、全局对象属性表、字节码/常量池扩容策略中的线性查找。
  C 版对应的哈希表实现仅需 0.33 s，可作为对照基线。

### 4.4 内存足迹

**标准库引导占用**（`-d` 内存画像，`-e "1+1"`）：

| 引擎 | 引擎堆占用 | 对象数 | value_array | string | 最小可用堆 |
|---|---:|---:|---:|---:|---:|
| Kotlin（jvm/native） | 80,592 B | 199 | 56,688 B / 173 个 | 8,072 B / 283 个 | 160 KB |
| C | 6,360 B | 60 | 3,760 B / 10 个 | 24 B / 1 个 | 64 KB |

Kotlin 版在运行时构造标准库对象（199 个对象、173 个值数组），而 C 版使用预生成的
紧凑表，导致引导占用为 C 的 **12.7 倍**，最小可用堆为其 2.5 倍。这与项目“低至 10KB
内存”的定位有明显差距。

**运行期峰值 RSS** 见 2.3 节：JVM 在解析 4000 函数时可达 812 MB–1.25 GB；原生镜像稳定在
约 79 MB；C 约 10 MB。JVM 的 GC 日志显示 6.5 s 内仅 3 次年轻代回收（另有 1 行 G1 初始化），
停顿 1.5–2.2 ms，峰值 RSS 主要来自 G1 按分配速率扩张年轻代（386 MB → 35 MB），
而不是垃圾回收停顿。

### 4.5 原生镜像体积

`kotlin/src/main/resources/META-INF/native-image/resource-config.json` 使用：

```json
{ "resources": { "includes": [ { "pattern": ".*" } ] } }
```

该“全量内嵌”规则使原生镜像包含 **171.72 MB 的 embedded resources**（构建日志），
最终可执行文件 227 MB，而 JAR 仅 2.1 MB、C 可执行文件 1.3 MB。嵌入的资源对引擎功能
没有必要（引擎不读取 classpath 资源），改为白名单或移除该配置即可把体积降到
数十 MB 量级。

### 4.6 正确性缺陷：GC 保活缺失使 `split` / `JSON.parse` 静默返回错误结果

这是本次评估中最重要的发现，优先级高于所有性能问题：**引擎会在不报错、退出码为 0 的情况下
返回错误结果**，根因是标准库若干“先创建数组/对象、再循环写入”的路径没有把目标放入 GC 保活
列表，循环内部分配触发 GC 压缩后裸 `JSValue` 变为悬垂指针。

**现象 1：`String.prototype.split`**

`scripts/perf/workloads/string_ops.js`（把 1 万段字符串拼接后反复 `split(",")` 200 次）：

| 堆大小 | jvm 结果 | native 结果 | c-o2 结果 |
|---|---|---|---|
| 16 MB | `NaN` | `NaN` | `2000000` |
| 32 MB | `NaN` | `NaN` | `2000000` |
| 64 MB | `NaN` | `NaN` | `2000000` |
| 128 MB | `2000000`（正确） | `2000000`（正确） | `2000000` |

**现象 2：`JSON.parse`**

| 用例 | 16 MB | 128 MB | 256 MB | c-o2（16 MB） |
|---|---|---|---|---|
| 3000 键对象，解析 1 次（键数） | 0（错误） | 3000 | — | 3000 |
| 4000 键对象，解析 1 次（键数） | 0 | 0 | 4000 | 4000 |
| 5000 键对象，解析 100 次（键数累加） | 0 | 0 | — | 500000 |
| 5000 元素数组，解析 100 次（长度累加） | `NaN` | 500000 | — | 500000 |

在 split 循环中额外加入 `typeof arr.length !== "number"` 检查后，同一负载反而全部通过，
说明缺陷与**字节码布局/GC 触发时机**相关，而非堆容量不足（16 MB 下的 `push` 循环、
`new Array(10000)` 循环均正确；无界分配也能正确抛出 `Uncaught Error: out of memory`）。
`JSON.parse` 的 4000 键用例在 128 MB 仍失败、256 MB 才通过，进一步证明 GC 触发点由
分配计数决定，加大堆只能推迟而不能消除问题。

**根因位置**（`kotlin/src/main/kotlin/JSStdlib.kt`）：

| 位置 | 路径 | 问题 |
|---|---|---|
| 1145–1148 | `split` 非正则分隔符 | `out` 未保活，循环内 `JS_NewString` 分配 |
| 1777–1784 | `regexpMatch`（全局 `String.match`） | `out` 未保活，循环内 `JS_NewString` 分配 |
| 2627–2644 | `jsonParse` 对象分支 | `obj` 未保活，递归解析与 `JS_NewString(key)` 分配 |
| 2647–2661 | `jsonParse` 数组分支 | `arr` 未保活，递归解析分配 |

以 split 为例：

```kotlin
val out = JS_NewArray(c, parts.size)
for (i in parts.indices) rt.setElement(out, JS_NewShortInt(i), JS_NewString(c, parts[i]))
c.memory.putI32(JS_VALUE_TO_PTR(out) + 32, parts.size)
out
```

`out` 是未注册到 GC 根中的裸 `JSValue`。循环内 `JS_NewString` 分配并可能触发 GC 压缩，
数组被搬移后 `out` 成为悬垂指针，`setElement` 写入旧地址，`JS_VALUE_TO_PTR(out) + 32`
也会写坏堆；最终返回的值可能指向无效块，表现为 `arr.length === undefined` / 对象无属性。
同文件的 `split` 正则路径（1129–1134 行）、`Array.prototype.slice`（695–707 行）、
`Set.prototype.values`（2386–2395 行）等路径已正确使用 `gcKeepAlive`，说明这是遗漏而非设计。
`regexpMatch` 分支未在本次压测中复现失败（依赖匹配次数与 GC 时机），但代码模式与 split
完全一致，应一并修复。

**建议修复**（split 示例，与正则路径一致）：

```kotlin
val out = JS_NewArray(c, parts.size)
val keep = c.gcKeepAlive.size
c.gcKeepAlive.add(out)
for (i in parts.indices) rt.setElement(c.gcKeepAlive[keep], JS_NewShortInt(i), JS_NewString(c, parts[i]))
c.memory.putI32(JS_VALUE_TO_PTR(c.gcKeepAlive[keep]) + 32, parts.size)
c.gcKeepAlive[keep]
```

`jsonParse` 需在创建 `obj`/`arr` 后立即保活，并在返回前（`try/finally`）弹出，
避免递归解析导致保活列表随嵌套深度增长。修复后建议补充回归用例：
16 MB 堆下反复 split 1 万元素字符串、解析 3000/4000 键对象，断言结果非 NaN 且键数正确。

> 验证：本次评估在本地临时应用了上述 split 补丁（未提交），16/32/64 MB 堆下
> `string_ops.js` 均返回 `2000000`；回退补丁并重建后立即复现 `NaN`，确认根因判断成立。

本报告的字符串基准因此改用 128 MB 堆（不触发 GC），并在结论中单独标注。

---

## 5. 优化建议（按投入产出比排序）

| 优先级 | 建议 | 预期收益 | 风险/成本 | 涉及位置 |
|---|---|---|---|---|
| P0 | 拆分 `runBody` 巨型分发方法（每 opcode/每组 opcode 一个 < 8 KB 的方法） | 默认 JVM 提升 6–10x，消除编译不确定性 | 中，需谨慎保持栈/帧语义 | `runtime/JSRuntime.kt` |
| P0 | 修复 `split` / `regexpMatch` / `jsonParse` 的 GC 保活（4.6 节） | 消除静默错误结果（大输入下必现） | 低 | `JSStdlib.kt:1145/1777/2627/2647` |
| P1 | 字节码零拷贝或按「函数标识 + GC epoch」缓存 | 调用密集负载减少每次调用的分配/复制，预计 10%–30% | 中，需处理 GC 搬移 | `JSRuntime.kt:78-81` |
| P1 | 定位解析 O(n²) 热点（原子表/属性表/扩容）并改为哈希或几何扩容 | 4000 函数解析从 5.6 s 降到接近 C 的量级 | 中 | `parser/JSParser.kt`、`JSLexer.kt` |
| P1 | 原生镜像资源配置改白名单/删除 `.*` | 体积 227 MB → 预计 30–50 MB | 低 | `native-image/resource-config.json` |
| P2 | 字符串路径优化：减少 WTF-8↔UTF-16 往返、复用 `StringBuilder` | string_build 类负载从 20–36x C 降到 10x 内 | 中 | `JSStdlib.kt` |
| P2 | 正则：缓存 `Pattern` 编译结果，避免每次 `exec` 重建 | regexp 从 119x C 降到 20x 内 | 低 | `JSStdlib.kt`（RegExp 部分） |
| P2 | 标准库引导瘦身（惰性注册、共享常量） | 80 KB → 更接近 C 的 6 KB，最小堆下调 | 中 | `JSStdlib.kt` |
| P3 | JVM 部署参数：`-Xmx256m` 控制 RSS；文档化 `-XX:-DontCompileHugeMethods` 作为临时缓解 | RSS 1.2 GB → 250 MB；部分负载 6–8x | 低（但非根治） | 文档/启动脚本 |

关于 P0 的拆分方式，建议保持现有语义的最小改动：

```kotlin
while (pc < code.size) {
    val op = code[pc].toInt() and 0xFF
    pc++
    pc = when (op) {
        OPCodeEnum.OP_get_var_ref.id -> opGetVarRef(...)
        ...
        else -> throw IllegalStateException("bad opcode $op")
    }
}
```

每个 `opXxx` 只包含原分支体，主循环方法降到数百字节，C1/C2 可正常内联/编译；
配合 `-XX:-DontCompileHugeMethods` 不再是必需条件。

---

## 6. 结论与适用场景

| 维度 | 结论 |
|---|---|
| 计算吞吐 | C 为基准；native 慢 29–37x（microbench 几何平均 36.9x）；jvm-opt 比 native 再慢约 1.4x 且强制编译不稳定；jvm 默认比 jvm-opt 慢约 10x |
| 启动 | C ≈ native（约 25–35 ms，含测量下限）≪ JVM（约 190 ms） |
| 解析/编译 | C 快 17x（JVM）、42x（native）；Kotlin 侧呈超线性 |
| 内存 | C 3.6–21 MB ≪ native 56–79 MB ≪ JVM 86–1250 MB（峰值 RSS） |
| 体积 | C 1.3 MB ≪ JAR 2.1 MB ≪ native 227 MB（可优化到数十 MB） |
| 正确性 | `split` / `JSON.parse` 在 GC 触发时静默返回错误结果（P0），修复前不建议用于生产 |

**适用建议**：

- 资源受限的嵌入式场景仍应选择 C 版；Kotlin 版当前的最小堆与解析/计算开销尚不匹配
  “10 KB 级引擎”的定位。
- Kotlin/JVM 形态适合“启动一次、长时间驻留、以宿主桥接与 I/O 为主”的场景；
  在修复 `runBody` 拆分前，不建议用于计算密集型脚本。
- 原生镜像形态在启动与吞吐上明显优于 JVM，但必须先裁剪资源、修复解析与内存问题，
  否则 227 MB 的体积与 80 MB 的 RSS 会限制其嵌入式适用性。

---

## 7. 复现方式

```powershell
# 1. 构建
cmake -B cmake-build-c-o2 -S . -G "MinGW Makefiles" -DCMAKE_C_COMPILER=E:/mingw64/bin/gcc.exe -DCONFIG_SMALL=OFF
cmake --build cmake-build-c-o2 -j 8 --target mqjs
cd kotlin; .\gradlew.bat --offline jar
$env:JAVA_HOME = $env:GRAALVM_HOME; .\gradlew.bat --offline nativeCompile
cd ..

# 2. 端到端基准（跳过耗时的完整 microbench）
pwsh -File scripts/perf/run_perf.ps1 -Reps 3 -SkipMicrobench

# 3. 分项 microbench
pwsh -File scripts/perf/run_microbench_subset.ps1

# 4. 启动开销（Python 计时，含空进程下限对照）
python scripts/perf/startup_bench.py 30

# 5. 内存探针（最小堆、OOM 行为、split 失败点）
pwsh -File scripts/perf/probe_memory.ps1

# 6. 方法体积检查（验证 HugeMethodLimit）
python scripts/perf/jvm_method_sizes.py kotlin/build/libs/kquickjs-1.0.0.jar
```

脚本与结果文件：

- `scripts/perf/run_perf.ps1`：端到端基准与完整 microbench；
- `scripts/perf/run_microbench_subset.ps1`：microbench 单项对比；
- `scripts/perf/gen_parse_workload.ps1`：生成解析负载；
- `scripts/perf/startup_bench.py`：冷启动测量；
- `scripts/perf/probe_memory.ps1`：内存极限探针；
- `scripts/perf/jvm_method_sizes.py`：类文件方法字节码统计；
- `scripts/perf/workloads/`：fib / alloc_gc / string_ops 负载；
- `perf-results/`：JSON 结果、microbench 原始输出、GC 日志、内存探针输出。

---

## 附录 A：完整 microbench 原始汇总

| 引擎 | 用例数 | 耗时 | 汇总值 | 峰值 RSS | 原始输出 |
|---|---:|---:|---:|---:|---|
| jvm | 42 | 412.9 s | 568,975.80 | 1,304.1 MB | `perf-results/microbench-jvm-*.txt` |
| c-o2 | 42 | 122.8 s | 2,554.40 | 21.0 MB | `perf-results/microbench-c-o2-*.txt` |

`jvm-opt` 与 `native` 未跑完整套件；同期分项结果见 `perf-results/microbench-subset-*.csv`。

## 附录 B：关键证据摘要

| 证据 | 数值/内容 |
|---|---|
| `runBody` 字节码长度 | 13,182 B（阈值 8,000） |
| 默认 JVM 下 `runBody` 编译记录 | 无（`-XX:+PrintCompilation`） |
| 强制编译失败的 C2 信息 | `out of virtual registers in LIR generator`、`out of nodes after split` |
| 强制编译 fib 收益 | 6147.9 ms → 738.3 ms（8.3x） |
| Kotlin 标准库引导占用 | 80,592 B（C：6,360 B） |
| JVM 解析 4000 函数峰值 RSS | 812.5 MB（`-Xmx256m` 时约 250 MB，耗时不变） |
| 原生镜像体积/资源内嵌 | 227,217,408 B / 171.72 MB embedded resources |
| split GC 缺陷触发阈值 | 16/32/64 MB 堆失败，128 MB 成功；C 在 16 MB 成功 |
| JSON.parse GC 缺陷 | 3000 键对象：16 MB 失败、128 MB 成功；4000 键对象：128 MB 失败、256 MB 成功；C 在 16 MB 成功 |