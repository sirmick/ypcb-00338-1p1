"""Read a design's JTAG USER data register (BSCANE2) through xsdb. Runs on dino.

    from jtagdr import read_dr
    for t, value in read_dr(bits=512, ir=0x02, count=2, gap_ms=1000): ...

Each sample is (milliseconds since the first read, int), the int assembled so bit 0 is the first bit
shifted out (the design's LSB). USER1-4 are IR 0x02, 0x03, 0x22, 0x23. Needs xsdb on PATH
(source /opt/Xilinx/2026.1/Vivado/settings64.sh); `fpga check` sets that up.
"""
import os
import subprocess
import tempfile

_TCL = r"""
connect
jtag targets -set -filter {name =~ "xc7k480t*"}
jtag lock
set t0 [clock milliseconds]
for {set i 0} {$i < %(count)d} {incr i} {
  if {$i > 0} { after %(gap)d }
  set s [jtag sequence]
  $s irshift -state IDLE -int 6 %(ir)d
  $s drshift -state IDLE -capture -hex %(bits)d [string repeat 0 %(nibbles)d]
  set r [$s run]; $s delete
  puts "DR [expr {[clock milliseconds] - $t0}] $r"
}
jtag unlock
"""


def read_dr(bits, ir=0x02, count=1, gap_ms=1000):
    """Shift `bits` zeros through USER register `ir`, `count` times, `gap_ms` apart."""
    nibbles = (bits + 3) // 4
    with tempfile.NamedTemporaryFile("w", suffix=".tcl", delete=False) as f:
        f.write(_TCL % dict(count=count, gap=gap_ms, ir=ir, bits=bits, nibbles=nibbles))
        script = f.name
    try:
        out = subprocess.run(["xsdb", script], capture_output=True, text=True,
                             timeout=60 + count * gap_ms / 1000).stdout
    finally:
        os.unlink(script)
    samples = []
    for line in out.splitlines():
        if line.startswith("DR "):
            _, t, hx = line.split()
            # xsdb prints the captured bits as hex, least significant byte first
            samples.append((int(t), int.from_bytes(bytes.fromhex(hx.zfill(nibbles + nibbles % 2)), "little")))
    if len(samples) != count:
        raise RuntimeError("xsdb returned %d of %d samples:\n%s" % (len(samples), count, out))
    return samples


_TCL_RW = r"""
connect
jtag targets -set -filter {name =~ "xc7k480t*"}
jtag lock
%(body)s
jtag unlock
"""


def xfer_dr(values, bits, ir=0x02):
    """Shift each value in `values` into USER register `ir` (one DR scan each, in order) and
    return what each scan captured. A design that latches on UPDATE takes the value as a write;
    the capture of scan n shows the state after scan n-1's update."""
    nibbles = (bits + 3) // 4
    body = []
    for v in values:
        hx = (v & ((1 << bits) - 1)).to_bytes((bits + 7) // 8, "little").hex()[:nibbles].ljust(nibbles, "0")
        body.append(f'set s [jtag sequence]; $s irshift -state IDLE -int 6 {ir}; '
                    f'$s drshift -state IDLE -capture -hex {bits} {hx}; puts "DR [$s run]"; $s delete')
    with tempfile.NamedTemporaryFile("w", suffix=".tcl", delete=False) as f:
        f.write(_TCL_RW % dict(body="\n".join(body)))
        script = f.name
    try:
        out = subprocess.run(["xsdb", script], capture_output=True, text=True, timeout=120 + len(values)).stdout
    finally:
        os.unlink(script)
    res = [int.from_bytes(bytes.fromhex(l.split()[1].zfill(nibbles + nibbles % 2)), "little")
           for l in out.splitlines() if l.startswith("DR ")]
    if len(res) != len(values):
        raise RuntimeError("xsdb returned %d of %d scans:\n%s" % (len(res), len(values), out))
    return res


def gray_to_bin(g):
    b = 0
    while g:
        b ^= g
        g >>= 1
    return b


def words(value, n, width=32):
    """Split a DR value into n words of `width` bits, word 0 first out."""
    mask = (1 << width) - 1
    return [(value >> (width * i)) & mask for i in range(n)]
