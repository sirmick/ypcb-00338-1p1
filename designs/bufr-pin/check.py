#!/usr/bin/env python3
"""Gate 4 (BUFR from a pin) check for designs/bufr-pin."""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../tools"))
from jtagdr import gray_to_bin, read_dr, words  # noqa: E402

(t0, a), (t1, b) = read_dr(bits=160, count=2, gap_ms=2000)
wa, wb = words(a, 5), words(b, 5)
if wb[0] != 0x42554652:
    sys.exit("magic %08x != 42554652: is designs/bufr-pin loaded?" % wb[0])
d = [(gray_to_bin(y) - gray_to_bin(x)) & 0xFFFFFFFF for x, y in zip(wa[1:4], wb[1:4])]
ok = True
for name, want, n in (("BUFR bypass", 50.0, d[1]), ("BUFR /4", 12.5, d[2])):
    mhz = n / d[0] * 50.0 if d[0] else 0.0
    good = abs(mhz - want) / want < 0.005
    ok &= good
    print(f"  {name:12s} {mhz:8.3f} MHz (want {want})  {'ok' if good else 'FAIL'}")
print("PASS" if ok else "FAIL")
sys.exit(0 if ok else 1)
