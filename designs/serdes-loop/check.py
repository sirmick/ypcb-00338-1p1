#!/usr/bin/env python3
"""Sweep IDELAY taps x bitslip on designs/serdes-loop and show where the loopback is clean.

For each (tap, bitslip) the design counts, over 4096 words, how often the received word equals the
transmitted word k cycles earlier (k = 0..15). A working PHY shows 4096 at one k for a run of taps
at the right bitslip. Prints a tap x bitslip map of the best match fraction.
"""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../tools"))
from jtagdr import xfer_dr, words  # noqa: E402

BITS = 352
st0 = xfer_dr([0], BITS)[0]
w = words(st0, 11)
if w[0] != 0x5345524C:
    sys.exit("magic %08x != 5345524c: is designs/serdes-loop loaded?" % w[0])
print(f"locked={w[1] & 1} idelayctrl_rdy={(w[1] >> 1) & 1}")
seq = ((w[1] >> 3) & 0xFF) + 1
# One xsdb session for the whole sweep: per point, a write scan then two read scans (a measurement
# takes ~35 us; each scan takes milliseconds, so the result is ready by the second read).
plan, scans = [], []
for slip in range(8):
    for tap in range(32):
        n = 0 if tap else (1 if slip else 0)        # bitslip is cumulative: one pulse per new row
        ctrl = ((seq & 0xFF) << 8) | (n << 5) | tap
        plan.append((slip, tap, seq & 0xFF))
        scans += [ctrl, ctrl, ctrl]
        seq += 1
res = xfer_dr(scans, BITS)
grid = {}
for i, (slip, tap, sq) in enumerate(plan):
    r = words(res[3 * i + 2], 11)
    if not ((r[1] >> 2) & 1 and ((r[1] >> 3) & 0xFF) == sq):
        grid[slip, tap] = (0, 0)                    # measurement not finished: count as a miss
        continue
    m = [(r[3 + k // 2] >> (16 * (k % 2))) & 0xFFFF for k in range(16)]
    best = max(range(16), key=lambda k: m[k])
    grid[slip, tap] = (m[best], best)
print("best match fraction per bitslip (rows) x IDELAY tap 0..31 (cols): # = 100%, + >= 90%, . < 90%")
ok = False
for slip in range(8):
    row = "".join("#" if grid[slip, t][0] == 4096 else ("+" if grid[slip, t][0] >= 3686 else ".") for t in range(32))
    full = [t for t in range(32) if grid[slip, t][0] == 4096]
    ok |= len(full) >= 3
    print(f"  slip {slip}: {row}  ({len(full)} clean taps{', offset ' + str(grid[slip, full[0]][1]) if full else ''})")
print("PASS" if ok else "FAIL")
sys.exit(0 if ok else 1)
