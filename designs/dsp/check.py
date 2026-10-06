#!/usr/bin/env python3
"""Gate 6 check for designs/dsp: four DSP datapaths, 2^20 operations each, checksums vs Python.

Each datapath folds its results into cs = rotl64(cs, 1) ^ result; Python replays the same xorshift64
operands and arithmetic bit-exactly. Pass: all four done and all four checksums equal.
"""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../tools"))
from jtagdr import read_dr, words  # noqa: E402

M64 = (1 << 64) - 1
N = 1 << 20


def xs(x):
    x ^= (x << 13) & M64
    x ^= x >> 7
    x ^= (x << 17) & M64
    return x


def sx(v, bits):
    v &= (1 << bits) - 1
    return v - (1 << bits) if v >> (bits - 1) else v


def fold(cs, r):
    return (((cs << 1) | (cs >> 63)) & M64) ^ (r & M64)


def run(seed, op):
    x, cs, state = seed, 0, 0
    for _ in range(N):
        r, state = op(x, state)
        cs = fold(cs, r)
        x = xs(x)
    return cs


def mul(x, s):
    return sx(x, 25) * sx(x >> 30, 18), s


def mac(x, acc):
    acc = sx(acc + sx(x, 25) * sx(x >> 30, 18), 48)
    return acc, acc


def preadd(x, s):
    d = ((x >> 56) & 0xFF) << 17 | (x & 0x1FFFF)
    ad = sx(sx(x, 25) + sx(d, 25), 25)
    return ad * sx(x >> 30, 18), s


def wide(x, s):
    return (x & 0xFFFFFFFF) * (x >> 32), s


CASES = [("mul 25x18", 0x0123456789ABCDEF, mul), ("mac 48-bit", 0x0F1E2D3C4B5A6978, mac),
         ("preadd (A+D)xB", 0x1122334455667788, preadd), ("wide 32x32", 0xCAFEBABEDEADBEEF, wide)]

[(_, v)] = read_dr(bits=384, count=1)
w = words(v, 12)
if w[0] != 0x44535036:
    sys.exit("magic %08x != 44535036: is designs/dsp loaded?" % w[0])
ok = w[1] & 1 == 1
print(f"MMCM locked={w[1] & 1}")
for i, (name, seed, op) in enumerate(CASES):
    done = (w[1] >> (1 + i)) & 1
    got = w[2 + 2 * i] | (w[3 + 2 * i] << 32)
    want = run(seed, op)
    good = done == 1 and got == want
    ok &= good
    print(f"  {name:16s} done={done}  {got:016x} (want {want:016x})  {'ok' if good else 'FAIL'}")
print("PASS" if ok else "FAIL")
sys.exit(0 if ok else 1)
