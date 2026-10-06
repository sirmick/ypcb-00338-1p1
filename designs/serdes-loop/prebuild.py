#!/usr/bin/env python3
"""Write gen_serdes.xdc for the loopback pin chosen by SL_PIN (env): sstl15 (default) or lvcmos18.

sstl15:   A29, DDR3 channel 1 DQ0 (bank 16, unused: the DRAM sits in reset with DQ high-Z), SSTL15 with
          IN_TERM UNTUNED_SPLIT_50 as Vivado's MIG configures these pins.
lvcmos18: P30, user LED 0 (bank 15), LVCMOS18: no VREF involved.
"""
import os
import sys

pin = os.environ.get("SL_PIN", "sstl15")
pad = {"sstl15": ("A29", "SSTL15", "set_property IN_TERM UNTUNED_SPLIT_50 [get_ports pad]\n"),
       "lvcmos18": ("P30", "LVCMOS18", "")}[pin]
leds = ("M30", "N30")
with open(os.path.join(sys.argv[1], "gen_serdes.xdc"), "w") as f:
    f.write("set_property LOC AA28 [get_ports SYS_CLK]\nset_property IOSTANDARD LVCMOS18 [get_ports SYS_CLK]\n")
    f.write(f"set_property LOC {pad[0]} [get_ports pad]\nset_property IOSTANDARD {pad[1]} [get_ports pad]\n{pad[2]}")
    for i, p in enumerate(leds):
        f.write(f"set_property LOC {p} [get_ports {{led[{i}]}}]\nset_property IOSTANDARD LVCMOS18 [get_ports {{led[{i}]}}]\n")
clkb = os.environ.get("SL_CLKB", "invert")       # invert (~sys4x, as LiteDRAM) or mmcm (180-degree output)
with open(os.path.join(sys.argv[1], "gen_params.v"), "w") as f:
    f.write("`define CLKB_MMCM\n" if clkb == "mmcm" else "")
    if os.environ.get("SL_RATE", "full") == "half":
        f.write("`define SL_HALF\n")
print(f"serdes-loop: pad on {pad[0]} ({pad[1]}), CLKB {clkb}, rate {os.environ.get('SL_RATE', 'full')}")
