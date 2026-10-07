#!/usr/bin/env python3
"""JTAG-side debug for designs/soc-s4: link state, Bus Master Enable and TLP counters (USER1)."""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../tools"))
from jtagdr import read_dr, words  # noqa: E402

(t0, a), (t1, b) = read_dr(bits=768, count=2, gap_ms=1000)
wa, w = words(a, 24), words(b, 24)
if w[0] != 0x53344344:
    sys.exit("magic %08x != 53344344: is designs/soc-s4 loaded?" % w[0])
st = w[1]
print(f"user_lnk_up={st & 1} user_reset={(st >> 1) & 1} mmcm_lock={(st >> 2) & 1} "
      f"gt_reset_fsm={(st >> 3) & 0x1F} ltssm=0x{(st >> 8) & 0x3F:02x} "
      f"mem_enable={(st >> 14) & 1} bus_master={(st >> 15) & 1} irq=0x{(st >> 16) & 0xFF:02x}")
print(f"user_clk {((w[2] - wa[2]) & 0xFFFFFFFF) / ((t1 - t0) * 1000):.2f} MHz")
print(f"RX TLPs {w[3]}  TX TLPs {w[4]}  RX stall cycles {w[5]}  TX stall cycles {w[10]}")
print(f"last RX TLP first beat {w[7]:08x}_{w[6]:08x}")
print(f"last TX TLP first beat {w[9]:08x}_{w[8]:08x}")
