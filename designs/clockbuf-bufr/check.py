#!/usr/bin/env python3
"""Gate 4 (regional buffers) check for designs/clockbuf: BUFG, BUFH and BUFR rates vs the reference."""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../tools"))
from jtagdr import gray_to_bin, read_dr, words  # noqa: E402

CLOCKS = [("BUFG 200", 200.0), ("BUFG#2 200", 200.0), ("BUFR bypass 100", 100.0), ("BUFR /4 of 200", 50.0)]
(t0, a), (t1, b) = read_dr(bits=256, count=2, gap_ms=2000)
wa, wb = words(a, 8), words(b, 8)
if wa[0] != 0x42554648:
    sys.exit("magic %08x != 42554648: is designs/clockbuf loaded?" % wa[0])
ok = wb[1] & 1 == 1
print(f"MMCM locked={wb[1] & 1}")
d = [(gray_to_bin(y) - gray_to_bin(x)) & 0xFFFFFFFF for x, y in zip(wa[2:7], wb[2:7])]
for (name, want), n in zip(CLOCKS, d[1:]):
    mhz = n / d[0] * 50.0 if d[0] else 0.0
    good = abs(mhz - want) / want < 0.005
    ok &= good
    print(f"  {name:18s} {mhz:9.3f} MHz  (want {want:7.3f})  {'ok' if good else 'FAIL'}")
print("PASS" if ok else "FAIL")
sys.exit(0 if ok else 1)
