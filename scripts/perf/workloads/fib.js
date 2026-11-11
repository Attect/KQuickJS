/* 递归函数调用基准：fib(27) 共约 63 万次调用，全部走解释器调用帧。 */
var n = 27;

function fib(x) {
    return x < 2 ? x : fib(x - 1) + fib(x - 2);
}

print(fib(n));