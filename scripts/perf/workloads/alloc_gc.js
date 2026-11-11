/* 内存分配 / GC 压力基准：30 万个小对象 + 2 万个数组 + 拼接字符串。
 * 依赖 16MB 默认引擎堆，用于观察 GC 触发频率与吞吐。 */
var total = 0;
var i;
var j;

for (i = 0; i < 20000; i++) {
    var arr = [];
    for (j = 0; j < 15; j++) {
        arr.push({ x: j, y: j * 2, s: "item-" + j });
    }
    total += arr.length;
}
print(total);