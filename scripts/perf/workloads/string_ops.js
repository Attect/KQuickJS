/* 字符串构建 / 切分基准：1 万段拼接 + 200 次 split（每次产出一万个片段）。
 *
 * 注意：Kotlin 版在 16–64MB 堆下会触发 split 的 GC 保活缺陷并静默得到 NaN
 * （见 docs/performance_evaluation.md 4.6），因此基准统一使用 --memory-limit 128M。 */
var parts = [];
var i;
var n = 0;

for (i = 0; i < 10000; i++) {
    parts.push("value-" + i);
}
var s = parts.join(",");
print(s.length);

for (i = 0; i < 200; i++) {
    var arr = s.split(",");
    n += arr.length;
}
print(n);