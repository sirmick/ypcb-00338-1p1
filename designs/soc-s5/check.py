#!/usr/bin/env python3
"""JTAG-side debug for designs/soc-s5: PCIe link, DDR3 calibration, guest reset, TLP and memory counters."""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../tools"))
from jtagdr import read_dr, words  # noqa: E402

(t0, a), (t1, b) = read_dr(bits=768, count=2, gap_ms=1000)
wa, w = words(a, 24), words(b, 24)
if w[0] != 0x53354344:
    sys.exit("magic %08x != 53354344: is designs/soc-s5 loaded?" % w[0])
st = w[1]
print(f"pcie: lnk_up={st & 1} user_reset={(st >> 1) & 1} ltssm=0x{(st >> 4) & 0x3F:02x} "
      f"mem_enable={(st >> 10) & 1} bus_master={(st >> 11) & 1}")
print(f"ddr3: pll_locked={(st >> 3) & 1} calib_complete={(st >> 12) & 1} ram_ready={(st >> 13) & 1} "
      f"calibration state={(st >> 15) & 0x1F}")
print(f"guest_reset={(st >> 14) & 1}  SoC clock {((w[2] - wa[2]) & 0xFFFFFFFF) / ((t1 - t0) * 1000):.2f} MHz")
print(f"RX TLPs {w[3]}  TX TLPs {w[4]}  wishbone requests {w[5]} acks {w[6]}")
