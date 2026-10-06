#!/usr/bin/env python3
"""Gate 5 (every width) check for designs/bramwidths: initial contents and pattern loops, 13 modes."""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../tools"))
from jtagdr import gray_to_bin, read_dr, words  # noqa: E402

MEMS = ["36K 32768x1", "36K 16384x2", "36K 8192x4", "36K 4096x9", "36K 2048x18", "36K 1024x36",
        "36K 512x72 (SDP)", "18K 2048x9", "18K 1024x18", "18K 512x36 (SDP)", "18K 4096x4",
        "18K 8192x2", "18K 16384x1"]
(t0, a), (t1, b) = read_dr(bits=1312, count=2, gap_ms=2000)
wa, wb = words(a, 41), words(b, 41)
if wb[0] != 0x42524D57:
    sys.exit("magic %08x != 42524d57: is designs/bramwidths loaded?" % wb[0])
ok = wb[1] & 1 == 1
print(f"MMCM locked={wb[1] & 1}")
for i, name in enumerate(MEMS):
    ie, pe = wb[2 + 3 * i], wb[3 + 3 * i]
    p0, p1 = gray_to_bin(wa[4 + 3 * i]), gray_to_bin(wb[4 + 3 * i])
    good = ie == 0 and pe == 0 and p1 > p0
    ok &= good
    print(f"  {name:18s} init errors {ie:6d}  pattern errors {pe:10d}  passes {p1:8d}  {'ok' if good else 'FAIL'}")
print("PASS" if ok else "FAIL")
sys.exit(0 if ok else 1)
