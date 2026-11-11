#!/usr/bin/env python3
"""冷启动开销测量：对每个引擎重复启动进程执行 `-e 1+1`，报告中位数/最小值。

用法：
    python scripts/perf/startup_bench.py [重复次数]

注意：测量值包含操作系统进程创建与 Python subprocess 的固定开销（本机约 24–31 ms），
因此 C/native 的数值是上界，比较时应关注 JVM 与其的差值。
"""

import os
import statistics
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

ENGINES = {
    "jvm": ["java", "-jar", "kotlin/build/libs/kquickjs-1.0.0.jar", "-e", "1+1"],
    "jvm-opt": ["java", "-XX:-DontCompileHugeMethods", "-jar",
                "kotlin/build/libs/kquickjs-1.0.0.jar", "-e", "1+1"],
    "native": ["kotlin/build/native/nativeCompile/kquickjs.exe", "-e", "1+1"],
    "c-o2": ["cmake-build-c-o2/mqjs.exe", "-e", "1+1"],
}


def bench(cmd, n):
    times = []
    for _ in range(n):
        t = time.perf_counter()
        subprocess.run(cmd, cwd=ROOT, stdout=subprocess.DEVNULL,
                       stderr=subprocess.DEVNULL, check=False)
        times.append((time.perf_counter() - t) * 1000.0)
    return times


def main():
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 30
    print("%-8s %10s %10s %10s" % ("engine", "min_ms", "median_ms", "max_ms"))
    for name, cmd in ENGINES.items():
        t = bench(cmd, n)
        print("%-8s %10.2f %10.2f %10.2f" % (name, min(t), statistics.median(t), max(t)))


if __name__ == "__main__":
    main()