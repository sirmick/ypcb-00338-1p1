#!/usr/bin/env python3
"""Gate 9 prerequisite check for designs/clk200: rates of the two 200 MHz DDR3 reference oscillators."""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../tools"))
from jtagdr import gray_to_bin, read_dr, words  # noqa: E402

(t0, a), (t1, b) = read_dr(bits=128, count=2, gap_ms=2000)
wa, wb = words(a, 4), words(b, 4)
if wb[0] != 0x43323030:
    sys.exit("magic %08x != 43323030: is designs/clk200 loaded?" % wb[0])
d = [(gray_to_bin(y) - gray_to_bin(x)) & 0xFFFFFFFF for x, y in zip(wa[1:], wb[1:])]
ok = True
for name, n in (("clk200 #0 (AH27/AH28)", d[1]), ("clk200 #1 (G25/G26)", d[2])):
    mhz = n / d[0] * 50.0 if d[0] else 0.0
    good = abs(mhz - 200.0) < 1.0
    ok &= good
    print(f"  {name:22s} {mhz:9.3f} MHz  {'ok' if good else 'FAIL'}")
print("PASS" if ok else "FAIL")
sys.exit(0 if ok else 1)
