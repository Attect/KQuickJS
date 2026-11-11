#!/usr/bin/env python3
"""统计 JVM 类文件中各方法的字节码长度（Code 属性 code_length）。

用途：判断热点方法是否超过 HotSpot 的 -XX:HugeMethodLimit（默认 8000 字节），
超限方法不会被 C2 编译，解释器循环会长期以解释方式执行。
"""

import struct
import sys
import zipfile


def parse_class(data):
    pos = 8  # magic + minor/major
    cp_count = struct.unpack_from(">H", data, pos)[0]
    pos += 2
    cp = [None] * cp_count
    i = 1
    while i < cp_count:
        tag = data[pos]
        pos += 1
        if tag == 1:  # Utf8
            ln = struct.unpack_from(">H", data, pos)[0]
            pos += 2
            cp[i] = data[pos:pos + ln].decode("utf-8", "replace")
            pos += ln
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            pos += 4
        elif tag in (5, 6):
            pos += 8
            i += 1
        elif tag in (7, 8, 16, 19, 20):
            pos += 2
        elif tag in (15,):
            pos += 3
        else:
            raise ValueError("unknown cp tag %d at %d" % (tag, pos - 1))
        i += 1

    pos += 6  # access, this, super
    ifc = struct.unpack_from(">H", data, pos)[0]
    pos += 2 + ifc * 2
    fields = struct.unpack_from(">H", data, pos)[0]
    pos += 2
    for _ in range(fields):
        pos += 6
        n = struct.unpack_from(">H", data, pos)[0]
        pos += 2
        for _ in range(n):
            a_len = struct.unpack_from(">I", data, pos + 2)[0]
            pos += 6 + a_len
    methods = struct.unpack_from(">H", data, pos)[0]
    pos += 2
    out = []
    for _ in range(methods):
        access, name_i, desc_i, attr_n = struct.unpack_from(">HHHH", data, pos)
        pos += 8
        name = cp[name_i] or ""
        desc = cp[desc_i] or ""
        code_len = -1
        for _ in range(attr_n):
            a_name_i, a_len = struct.unpack_from(">HI", data, pos)
            pos += 6
            if cp[a_name_i] == "Code":
                code_len = struct.unpack_from(">I", data, pos + 4)[0]
            pos += a_len
        if code_len >= 0:
            out.append((code_len, name, desc, access))
    return out


def main():
    if len(sys.argv) < 2:
        print("usage: jvm_method_sizes.py <jar-or-class> [topN]")
        return 1
    path = sys.argv[1]
    topn = int(sys.argv[2]) if len(sys.argv) > 2 else 15
    entries = []
    if path.endswith(".jar"):
        with zipfile.ZipFile(path) as zf:
            for info in zf.infolist():
                if info.filename.endswith(".class"):
                    for cl, name, desc, acc in parse_class(zf.read(info)):
                        entries.append((cl, info.filename, name, desc))
    else:
        with open(path, "rb") as f:
            for cl, name, desc, acc in parse_class(f.read()):
                entries.append((cl, path, name, desc))
    entries.sort(reverse=True)
    print("%10s  %s" % ("CODE_LEN", "METHOD"))
    for cl, cls, name, desc in entries[:topn]:
        print("%10d  %s.%s%s  (%.1f KB)" % (cl, cls, name, desc, cl / 1024))
    over = [e for e in entries if e[0] > 8000]
    print("\n超过 HugeMethodLimit(8000) 的方法数: %d" % len(over))
    if over:
        print("其总字节码: %.1f KB" % (sum(e[0] for e in over) / 1024))
    return 0


if __name__ == "__main__":
    sys.exit(main())