#!/usr/bin/env python3
"""Sample any package pins by boundary scan (SAMPLE) and summarise them. Runs on dino.

    bscan.py PIN [PIN...] [--n 200]

For each pin: output-enable state, the pad level (input receiver; reads 0 on output-only differential
pins), and the core output value (what the OLOGIC drives), each as high/low/toggling across the samples.
Cell numbers come from the BSDL file, so any I/O pin works. Needs xsdb on PATH.
"""
import os
import re
import subprocess
import sys
import tempfile

BSDL = "/opt/Xilinx/2026.1/Vivado/ids_lite/ISE/kintex7/data/xc7k480t_ffg1156.bsd"
argv = sys.argv[1:]
n = 200
if "--n" in argv:
    i = argv.index("--n"); n = int(argv[i + 1]); del argv[i:i + 2]
args = argv
cells = {}
for line in open(BSDL):
    m = re.match(r'\s*"\s*(\d+)\s+\(BC_\d+,\s*(\w+),\s*(\w+),\s*X(?:,\s*(\d+),)?', line)
    if m:
        num, port, kind, ctl = int(m.group(1)), m.group(2), m.group(3), m.group(4)
        cells.setdefault(port.split("_")[-1], {})[kind] = num
        if ctl: cells[port.split("_")[-1]]["control"] = int(ctl)
want = []
for p in args:
    c = cells.get(p)
    if not c or "input" not in c:
        sys.exit(f"{p}: not an I/O pin in the BSDL")
    want.append((p, c.get("control"), c.get("output3"), c["input"]))
tcl = """connect
jtag targets -set -filter {name =~ "xc7k480t*"}
jtag lock
for {set i 0} {$i < %d} {incr i} {
  set s [jtag sequence]; $s irshift -state IDLE -int 6 1; $s drshift -state IDLE -capture -hex 1395 [string repeat 0 349]
  puts "S [$s run]"; $s delete
}
jtag unlock
""" % n
with tempfile.NamedTemporaryFile("w", suffix=".tcl", delete=False) as f:
    f.write(tcl)
out = subprocess.run(["xsdb", f.name], capture_output=True, text=True).stdout
os.unlink(f.name)
samples = [int.from_bytes(bytes.fromhex(l.split()[1].zfill(350)), "little") for l in out.splitlines() if l.startswith("S ")]
bit = lambda v, k: (v >> k) & 1 if k is not None else None
for p, ctl, o, i in want:
    pads = [bit(v, i) for v in samples]
    ctls = {bit(v, ctl) for v in samples} if ctl is not None else {None}
    oe = "input-only" if ctl is None else ("driven" if ctls == {0} else "tri-stated" if ctls == {1} else "mixed OE")
    def summary(vals):
        ones = sum(vals)
        return ("toggling" if 0 < ones < len(vals) else ("high" if ones else "low")) + f" ({ones}/{len(vals)})"
    outs = summary([bit(v, o) for v in samples]) if o is not None else "-"
    print(f"{p:6s} {oe:11s} pad {summary(pads):18s} core output {outs}")
