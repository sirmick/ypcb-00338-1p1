#!/usr/bin/env python3
"""Write the $readmemh files for bramwidths: init_w<W>_a<AW>.hex holding f(a) truncated to W bits."""
import os
import sys

MEMS = [(1, 15), (2, 14), (4, 13), (9, 12), (18, 11), (36, 10), (72, 9), (9, 11), (18, 10), (36, 9),
        (4, 12), (2, 13), (1, 14)]


def f(a):
    """The 72-bit value the Verilog's f() builds: {a[7:0], a^{a[7:0],a[15:8]}, a^16'hA5C3, ~a, a^{15'd0,^a}}."""
    a &= 0xFFFF
    par = bin(a).count("1") & 1
    swap = ((a & 0xFF) << 8) | (a >> 8)
    return ((a & 0xFF) << 64) | ((a ^ swap) << 48) | ((a ^ 0xA5C3) << 32) | ((~a & 0xFFFF) << 16) | (a ^ par)


out = sys.argv[1]
for w, aw in sorted(set(MEMS)):
    digits = (w + 3) // 4
    with open(os.path.join(out, f"init_w{w}_a{aw}.hex"), "w") as fh:
        fh.writelines(f"{f(a) & ((1 << w) - 1):0{digits}x}\n" for a in range(1 << aw))
