# KQuickJS P0 修复与性能优化记录（2026-11-11）

> 对应评估报告：[performance_evaluation.md](performance_evaluation.md)。
> 本文记录本轮修复的根因、实现与复测数据；所有数字为本机同一环境实测（Ryzen 9 7950X /
> Windows 11 / JDK 17.0.6 / GraalVM CE 17.0.9 / gcc 15.2 -O2）。

---

## 1. P0 修复

### 1.1 GC 保活缺失：`split` / `match` / `JSON.parse` 静默错误

**根因**：标准库若干“先创建结果容器、再循环写入”的路径持有裸 `JSValue`，循环内的
`JS_NewString` 等分配会触发 GC 压缩，容器搬移后写入旧地址，返回悬垂指针。
小堆下表现为 `NaN` / 空对象，退出码仍为 0。

修复位置（`kotlin/src/main/kotlin/JSStdlib.kt`）：

| 路径 | 修复 |
|---|---|
| `split` 非正则分隔符 | 结果数组加入 `gcKeepAlive`，循环与写长度均从保活槽取址 |
| `regexpMatch`（全局 `String.match`） | 同上 |
| `jsonParse` 对象分支 | 结果对象保活，`try/finally` 中弹出，避免递归累积 |
| `jsonParse` 数组分支 | 同上 |

新增 `GCRegressionTest`（5 例）：16 MB 堆下反复 split 1 万元素字符串、全局 match、
5000 键对象/5000 元素数组的 `JSON.parse`，断言结果正确（修复前该组用例会得到
`NaN` / 0 个属性）。

### 1.2 JIT 失效：`runBody` 巨型方法

**根因**：`JSRuntime.runBody` 原始字节码 **13,182 字节**，超过 HotSpot
`HugeMethodLimit`（默认 8000），C1/C2 直接拒绝编译；整个 JS 指令循环长期以解释方式
执行（`-Xint` 与默认模式仅差 29%–93% 可佐证）。

**修复**：

1. 将冷门/中频 opcode 分支体拆为独立处理器方法（约 30 个 op），主循环只保留调用与
   控制流；处理器统一约定返回新 pc 或 `OP_EXCEPTION`。
2. **关键点**：循环内原有 40+ 个 `return`，每个返回点都会被编译器复制一份
   `finally` 清理代码（popFrame/回收栈/弹出保活），这是方法体积膨胀的主要来源。
   改为“统一出口 + `exitKind`”后，体积大幅下降。

结果：`runBody` 降至 **4,935 字节**，`-XX:+PrintCompilation` 显示其被 C1/C2 编译
（含 OSR）：

```
120  476   !   3  JSRuntime::runBody (4935 bytes)
136  542   !   4  JSRuntime::runBody (4935 bytes)
493  590 %  !  4  JSRuntime::runBody @ 4665 (4935 bytes)
```

### 1.3 连带修复：属性表扩容与回收（全量 microbench 暴露）

JIT 生效后执行速度提升，使 `microbench` 的 `prop_delete` 用例迭代次数变大，暴露了
属性表的三个问题：

1. **线性扩容**：每次槽位满只增加 1 个槽（`size + 3`），连续插入 O(n²)；
   改为按槽数翻倍，并保持“槽位 = 首槽 + 3*i”的对齐（否则尾槽哨兵会落到槽的
   value/next 单元上）。
2. **空槽哨兵错误**：新槽整体写 0，使空槽的 key 变成合法整数 0，读取 `o[0]` 会命中
   空槽并返回 0；改为写入 `UNINITIALIZED/UNDEFINED/0` 三元组。
3. **删除不回收**：删除只把 key 标为 `UNINITIALIZED`，表只增不减；删空时直接丢弃
   属性表，空洞超过 3/4 时用 `compactProps` 重建（1.25 倍容量）。

修复前 `prop_delete` 在默认 16 MB 堆下会挂起或 OOM；修复后线性完成。

### 1.4 解析 O(n²)

`lineColOf` 原先对每个 pc→源码位置标记都从源码起点扫描，4000 函数时整体为 O(n²)。
改为 `JSParseState` 初始化时预建行起始偏移表，二分定位行号后仅扫描行内字节。

| 函数数 | 修复前 | 修复后 |
|---:|---:|---:|
| 500 | 531 ms | 296 ms |
| 1000 | 886 ms | 345 ms |
| 2000 | 1934 ms | 504 ms |
| 4000 | 6678 ms | 952 ms |

### 1.5 其它优化

- **字节码内容缓存**：恢复“块地址 → ByteArray”缓存，附长度校验；`JSGC` 每次压缩后
  整体清空，避免陈旧副本（`ByteCodeCacheRegressionTest` 覆盖重定义/GC 场景）。
- **整数键哈希**：`hashProp` 对 Int 键直接计算十进制字符串哈希，不再
  `jsNumberToString` 分配；`prop_read` 625→250 ns、`prop_write` 350→162 ns、
  `prop_create` 750→475 ns。

---

## 2. 复测结果

### 2.1 端到端工作负载（3 次中位数，ms）

| 用例 | 修复前 jvm | 修复后 jvm | native | C (-O2) | jvm/C |
|---|---:|---:|---:|---:|---:|
| 冷启动 `-e 1+1` | 190.6 | 154.8 | 20.3 | 17.7 | 8.7x |
| fib(27) 63.6 万次调用 | 6147.9 | **633.0** | 330.3 | 30.2 | 21x |
| alloc_gc（30 万对象） | 6593.0 | **733.1** | 919.6 | 61.0 | 12x |
| mandelbrot | 3022.8 | **455.3** | 153.9 | 30.8 | 14.8x |
| parse_compile（4000 函数） | 5605.5 | **973.8** | 1386.3 | 343.2 | 2.8x |
| parse_exec（4000 函数） | 5696.6 | **1060.0** | 1652.2 | 323.7 | 3.3x |

> 启动时间来自 `startup_bench.py`（30 次中位数，含约 15–24 ms 进程创建下限），
> 其余来自 `run_perf.ps1`（3 次中位数）。C 列数值与历史报告略有差异，属同机不同时段的
> 正常波动；本表用于同批次对比。

### 2.2 microbench（完整 42 项）

| 引擎 | 套件耗时 | 汇总值（ns/op 之和） | 相对 C | 峰值 RSS |
|---|---:|---:|---:|---:|
| 修复前 jvm | 412.9 s | 568,975.8 | 222.7x | 1.30 GB |
| 修复后 jvm | **142.7 s** | **59,915.5** | **22.6x** | 1.36 GB |
| native（修复后） | 149.4 s | 80,406.8 | 30.4x | 0.34 GB |
| c-o2 | 146.9 s | 2,648.6 | 1x | 20.4 MB |

> 说明：本批次 C 版套件耗时 146.9 s（历史 122.8 s），差异来自机器时段波动；
> 汇总值均取自同一批次输出。JVM 汇总值降至修复前的 **1/9.5**，并已优于原生镜像
> （JIT 对解释器循环的优化强于 AOT）。

### 2.3 关键单操作（ns/op，取自完整套件）

| 用例 | 修复前 jvm | 修复后 jvm | C |
|---|---:|---:|---:|
| empty_loop | 5000 | **85** | 6 |
| prop_read | 10500 | **250** | 4.5 |
| prop_write | 8500 | **162** | 5 |
| array_read | 9500 | **550** | 6.5 |
| func_call | — | **850** | 12.5 |
| int_arith | 12500 | **750** | 10 |
| string_build1 | 15000 | **10200** | 480 |
| regexp_ascii | 21000 | **2800** | 100 |

### 2.4 部署体积

| 产物 | 修复前 | 修复后 |
|---|---:|---:|
| `kquickjs.exe`（原生镜像） | 227.2 MB | **31.2 MB** |
| `kquickjs-1.0.0.jar` | 2.1 MB | 2.1 MB |
| `mqjs.exe`（C） | 1.3 MB | 1.3 MB |

原生镜像体积通过把 `resource-config.json` 的 `".*"` 全量资源包含改为空清单实现
（构建日志中的 embedded resources 从 171.7 MB 降为 0），四个 JS 测试套件与
split/JSON 回归在裁剪后的镜像上均通过。

### 2.5 内存与最小堆

| 指标 | jvm / native | c-o2 |
|---|---:|---:|
| 标准库引导后引擎堆占用 | 80,592 B | 6,360 B |
| 可运行 `print(1+1)` 的最小堆 | 160 KB | 64 KB |
| 16 MB 堆下 `string_ops`（split 1 万段 ×200） | 正确（2000000） | 正确 |
| 无界分配 | `Uncaught Error: out of memory` | `InternalError: out of memory` |

本轮已修复 `split`/`JSON.parse` 在小堆 GC 下的静默错误，`string_ops` 在
16/32/64/128 MB 全部返回正确结果；标准库引导占用与最小堆未变化，仍是后续瘦身目标。

---

## 3. 正确性验证

- Kotlin 全量测试 **176 例全部通过**（含新增 5 例 GC 回归）；
- `tests/test_language.js`、`test_loop.js`、`test_closure.js`、`test_builtin.js`
  经 CLI 与 JUnit 双路径通过；
- 完整 `microbench.js` 在 JVM / native / C 上均正常完成；
- 小堆回归：16 MB 堆下 split/JSON 大输入结果正确，属性增删循环不再 OOM/挂起。

---

## 4. 后续建议（未在本轮完成）

1. `prop_delete` 仍约 6.5 µs/op（数值键建表/删除的固有成本），可继续优化属性表；
2. 字符串/正则仍为 20–30x C，可做 WTF-8 换算与 `Pattern` 缓存专项；
3. JVM 冷启动约 155 ms，若面向 CLI 场景可考虑 AppCDS / 原生镜像；
4. 标准库引导仍占 80 KB（C 版 6.4 KB），可做惰性注册/共享常量瘦身。