#!/usr/bin/env python3
"""Gate 4 check for designs/clocks: are the MMCM and PLL locked, and is every output at its rate?

Reads USER1 twice, 2 s apart. Each clock's rate is its counter delta over the reference counter's
delta, times 50 MHz, so the host's timing jitter does not matter. Pass: both locked, no lost lock,
every rate within 0.5 %. Run with `fpga check designs/clocks`.
"""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../tools"))
from jtagdr import gray_to_bin, read_dr, words  # noqa: E402

REF_MHZ = 50.0
CLOCKS = [  # (name, expected MHz), in DR order after the reference
    ("MMCM out0 /7.5 (fractional)", 1000 / 7.5), ("MMCM out1 /5", 200.0), ("MMCM out2 /4", 250.0),
    ("MMCM out3 /6", 1000 / 6), ("MMCM out4 /10", 100.0), ("MMCM out5 /3", 1000 / 3),
    ("MMCM out6 /20", 50.0),
    ("PLL out0 /4", 250.0), ("PLL out1 /5", 200.0), ("PLL out2 /10", 100.0),
    ("PLL out3 /8", 125.0), ("PLL out4 /3", 1000 / 3), ("PLL out5 /6", 1000 / 6),
]

(t0, a), (t1, b) = read_dr(bits=512, count=2, gap_ms=2000)
wa, wb = words(a, 16), words(b, 16)
ok = True
if wa[0] != 0x434C4B34 or wb[0] != 0x434C4B34:
    sys.exit("magic %08x/%08x != 434c4b34: is designs/clocks loaded?" % (wa[0], wb[0]))
st = wb[1]
mmcm, pll, mmcm_lost, pll_lost = (st >> 0) & 1, (st >> 1) & 1, (st >> 2) & 1, (st >> 3) & 1
print(f"MMCM locked={mmcm} lost={mmcm_lost}   PLL locked={pll} lost={pll_lost}")
ok &= mmcm == 1 and pll == 1 and not mmcm_lost and not pll_lost

delta = [(gray_to_bin(y) - gray_to_bin(x)) & 0xFFFFFFFF for x, y in zip(wa[2:], wb[2:])]
ref = delta[0]
host_ms = t1 - t0
print(f"reference: {ref} counts in {host_ms} ms of host time = {ref / host_ms / 1000:.3f} MHz by the host clock")
for (name, want), d in zip(CLOCKS, delta[1:]):
    mhz = d / ref * REF_MHZ if ref else 0.0
    err = (mhz - want) / want * 100
    good = abs(err) < 0.5
    ok &= good
    print(f"  {name:28s} {mhz:9.3f} MHz  (want {want:8.3f}, {err:+.3f} %)  {'ok' if good else 'FAIL'}")
print("PASS" if ok else "FAIL")
sys.exit(0 if ok else 1)
