#!/usr/bin/env python3
"""Bring in a LiteX-generated SoC for the openXC7 flow (gate 9).

LITEX_GATEWARE (env) is the LiteX build's gateware dir, default ~/fpga/litex-ypcb/build/ypcb_00338_1p1/gateware
(generated on dino, where the RISC-V compiler for the BIOS lives, and rsynced here). Copies the Verilog
as gen_litex.v and the memory .init files, and writes gen_litex.xdc keeping only what nextpnr reads:
LOC, IOSTANDARD, SLEW, IN_TERM. Timing constraints and INTERNAL_VREF are dropped (this board has an
external DDR3 VREF: Vivado's working MIG design sets no internal VREF either).
"""
import glob
import os
import re
import shutil
import sys

src = os.path.expanduser(os.environ.get("LITEX_GATEWARE", "~/fpga/litex-ypcb/build/ypcb_00338_1p1/gateware"))
out = sys.argv[1]
v = glob.glob(os.path.join(src, "*.v"))
x = glob.glob(os.path.join(src, "*.xdc"))
if len(v) != 1 or len(x) != 1:
    sys.exit(f"expected one .v and one .xdc in {src}")
shutil.copy(v[0], os.path.join(out, "gen_litex.v"))
# other sources (the CPU core from its pythondata package) are listed in LiteX's Vivado script
tcl = glob.glob(os.path.join(src, "*.tcl"))
extra = []
for line in open(tcl[0]) if tcl else []:
    m = re.match(r"\s*read_verilog\s+\{?([^}\s]+)\}?", line)
    if m and os.path.abspath(m.group(1)) != os.path.abspath(v[0]) and not m.group(1).endswith(os.path.basename(v[0])):
        extra.append(m.group(1))
for i, f in enumerate(extra):
    shutil.copy(f, os.path.join(out, f"gen_litex_src{i}.v"))
for f in glob.glob(os.path.join(src, "*.init")):
    shutil.copy(f, out)
keep = re.compile(r"^set_property (LOC|PACKAGE_PIN|IOSTANDARD|SLEW|IN_TERM) \S+ \[get_ports \{?[^ {}]+\}?\]\s*$")
lines = [l for l in open(x[0]) if keep.match(l)]
with open(os.path.join(out, "gen_litex.xdc"), "w") as f:
    f.writelines(lines)
print(f"litex: {os.path.basename(v[0])} + {len(extra)} more sources, {len(lines)} XDC lines kept")
