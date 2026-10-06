#!/usr/bin/env python3
"""Gate 7 check for designs/timing: four structures' checksums at the build's clock vs Python."""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../tools"))
from jtagdr import read_dr, words  # noqa: E402

M64, M32, N = (1 << 64) - 1, (1 << 32) - 1, 1 << 20


def xs(x):
    x ^= (x << 13) & M64
    x ^= x >> 7
    return x ^ ((x << 17) & M64)


def run(seed, step):
    x, cs, st = seed, 0, None
    for _ in range(N):
        r, st = step(x, st)
        cs = (((cs << 1) | (cs >> 63)) & M64) ^ r
        x = xs(x)
    return cs


def add32(x, acc):
    acc = ((acc or 0) + (x & M32)) & M32
    return acc, acc


def add64(x, acc):
    acc = ((acc or 0) + x) & M64
    return acc, acc


def carry(x, _):
    a, b = x & M32, x >> 32
    return (int(a < b) << 33) | (a + b), None


def srl_ce(x, st):
    s32, s16, s3 = st or (0, 0, 0)
    if (x >> 40) & 1:
        s32 = ((s32 << 1) | (x & 1)) & M32
        s16 = ((s16 << 1) | ((x >> 1) & 1)) & 0xFFFF
        s3 = ((s3 << 1) | ((x >> 2) & 1)) & 7
    return ((s3 >> 2) << 2) | ((s16 >> 15) << 1) | (s32 >> 31), (s32, s16, s3)


CASES = [("add32", 0x0123456789ABCDEF, add32), ("add64", 0x0F1E2D3C4B5A6978, add64),
         ("carry O+CO (#134)", 0x1122334455667788, carry), ("srl with CE (#6058)", 0xCAFEBABEDEADBEEF, srl_ce)]

[(_, v)] = read_dr(bits=384, count=1)
w = words(v, 12)
if w[0] != 0x54494D37:
    sys.exit("magic %08x != 54494d37: is designs/timing loaded?" % w[0])
print(f"clock {w[2]} MHz, MMCM locked={w[1] & 1}")
ok = w[1] & 1 == 1
for i, (name, seed, step) in enumerate(CASES):
    done = (w[1] >> (1 + i)) & 1
    got = w[3 + 2 * i] | (w[4 + 2 * i] << 32)
    want = run(seed, step)
    good = done == 1 and got == want
    ok &= good
    print(f"  {name:22s} done={done}  {'ok' if good else 'FAIL'}  {got:016x}{'' if good else f' (want {want:016x})'}")
print("PASS" if ok else "FAIL")
sys.exit(0 if ok else 1)
