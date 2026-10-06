#!/usr/bin/env python3
"""Run memtest.v over JTAG: write and/or read back 2^len words (64 data bytes each, plus the ECC lane)
of the DDR3 channel this bitstream drives, then report errors and bandwidth.

    memtest.py [--len 25] [--mode wr|w|r] [--seed N] [--channel 0|1]

--len 25 is the whole 2 GiB channel. --mode r --seed N re-reads what a write with seed N left, for
example to check retention after a wait. Needs designs/uberddr3 loaded and calibrated (check.py).
"""
import argparse, os, sys, time
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../tools"))
from jtagdr import read_dr, xfer_dr, words  # noqa: E402

BITS, CLK_HZ = 1024, 83.333e6
ap = argparse.ArgumentParser()
ap.add_argument("--len", type=int, default=25)
ap.add_argument("--mode", choices=("wr", "w", "r"), default="wr")
ap.add_argument("--seed", type=int)
ap.add_argument("--channel", type=int, choices=(0, 1), default=0,
                help="in a dual-channel build (UBER_DUAL=1), which channel to test")
a = ap.parse_args()


def status():
    w = words(read_dr(BITS)[0][1], 32)
    if w[0] != 0x55424433:
        sys.exit("magic %08x: is designs/uberddr3 loaded?" % w[0])
    return w


w = status()
dual = (w[3] >> 1) & 1
if a.channel and not dual:
    sys.exit("this bitstream drives one channel (built with UBER_CHANNEL); --channel needs UBER_DUAL=1")
# select the channel without starting a run (same seq), then read its state
xfer_dr([(0x4D << 24) | (a.channel << 15) | ((w[22] >> 1) & 0xFF)], BITS)
w = status()
if not ((w[1] >> 1) & 1 and (w[1] >> 2) & 0x1F == 23):
    sys.exit("controller not calibrated (run check.py)")
done = (w[22] >> 1) & 0xFF
seq = (done % 255) + 1                                      # a new run number starts a run
seed = a.seed if a.seed is not None else seq
mode = {"wr": 3, "w": 1, "r": 2}[a.mode]
xfer_dr([(0x4D << 24) | ((seed & 0xFF) << 16) | (a.channel << 15) | (mode << 13) | (a.len << 8) | seq], BITS)
t0 = time.time()
while True:
    w = status()
    if (w[22] >> 1) & 0xFF == seq and not w[22] & 1:
        break
    if time.time() - t0 > 120:
        sys.exit("timed out: busy=%d seq_done=%d" % (w[22] & 1, (w[22] >> 1) & 0xFF))
    time.sleep(0.5)
n = 1 << a.len
errors, first, mask = w[23], w[24], w[25] | w[26] << 32 | w[27] << 64
print(f"{n} words ({n * 64 / 2**30:.3f} GiB data, {n * 72 / 2**30:.3f} GiB with ECC lane), mode {a.mode}, seed {seed & 0xFF}" + (f", channel {a.channel}" if dual else ""))
for name, cyc in (("write", w[28]), ("read", w[29])):
    if cyc:
        s = cyc / CLK_HZ
        print(f"  {name}: {s * 1000:.1f} ms, {n * 64 / s / 1e9:.2f} GB/s data "
              f"({n * 64 / s / 1e9 / (64 * CLK_HZ / 1e9) * 100:.0f}% of the 5.33 GB/s DDR3-667 x64 peak)")
if a.mode != "w":
    print(f"  errors: {errors} words" + (f", first at word {first:#x}" if errors else ""))
    if mask:
        bad = [f"lane {i // 8} DQ{i % 8}" for i in range(72) if mask >> i & 1]
        print("  failing DQ lines: " + ", ".join(bad))
ok = errors == 0
print("PASS" if ok else "FAIL")
sys.exit(0 if ok else 1)
