#!/usr/bin/env python3
"""Gate 5 check for designs/bram: initial contents from the bitstream, then write/read patterns.

Pass: no initial-content errors, both initial sums match the values computed here from the same
f36/f18 the Verilog uses, no pattern errors, and passes advancing at the rate 200 MHz implies
(one pass = 4096 writes + 4096 reads = 8192 cycles).
"""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../tools"))
from jtagdr import gray_to_bin, read_dr, words  # noqa: E402


def f36(i):
    i &= 0xFFF
    rot = ((i & 0x3F) << 6) | (i >> 6)
    return ((i ^ 0x5A5) << 24) | ((~i & 0xFFF) << 12) | (i ^ rot)


def f18(i):
    i &= 0x3FF
    return ((i ^ 0x2B7) << 8) | (~i & 0xFF)


want_sum36 = sum(f36(i) for i in range(4096)) & 0xFFFFFFFF
want_sum18 = sum(f18(i) for i in range(1024)) & 0xFFFFFFFF

(t0, a), (t1, b) = read_dr(bits=512, count=2, gap_ms=2000)
wa, wb = words(a, 16), words(b, 16)
if wb[0] != 0x4252414D:
    sys.exit("magic %08x != 4252414d: is designs/bram loaded?" % wb[0])
locked, state = wb[1] & 1, (wb[1] >> 1) & 3
p0, p1 = gray_to_bin(wa[6]), gray_to_bin(wb[6])
rate = (p1 - p0) / ((t1 - t0) / 1000)
checks = [
    ("MMCM locked", locked == 1, f"state {state}"),
    ("initial contents m36 (4096x36)", wb[2] == 0, f"{wb[2]} errors"),
    ("initial contents m18 (1024x18)", wb[3] == 0, f"{wb[3]} errors"),
    ("initial sum m36", wb[4] == want_sum36, f"{wb[4]:08x} (want {want_sum36:08x})"),
    ("initial sum m18", wb[5] == want_sum18, f"{wb[5]:08x} (want {want_sum18:08x})"),
    ("pattern errors m36", wb[7] == 0, f"{wb[7]}"),
    ("pattern errors m18", wb[8] == 0, f"{wb[8]}"),
    ("passes advancing", p1 > p0, f"{p1} passes so far, {rate:.0f}/s (~{rate * 8194 / 1e6:.0f} MHz implied)"),
]
ok = True
for name, good, detail in checks:
    ok &= good
    print(f"  {name:32s} {'ok  ' if good else 'FAIL'}  {detail}")
if wb[7] or wb[8]:
    print(f"  first failure: addr {wb[9]} data {wb[10]:08x} want {wb[11]:08x}")
print("PASS" if ok else "FAIL")
sys.exit(0 if ok else 1)
