#!/bin/bash
# Generates VexiiRiscv's LiteX SoC as the guest's cluster (SOC-ROADMAP S5): the cores, CLINT and PLIC at
# QEMU virt's addresses, an AXI4 port for main memory and an AXI-lite port for devices. The one place
# the core options live; soc/'s tests and designs/soc-s5 both use it.
#
#   vexii-cluster.sh <output dir> [cores]      -> <output dir>/VexiiCluster.v
#
# Needs a VexiiRiscv checkout (VEXII_SRC, default ~/fpga/src/VexiiRiscv), sbt (coursier) and a JDK.
set -eo pipefail
here=$(cd "$(dirname "$0")" && pwd)
out=$(mkdir -p "$1" && cd "$1" && pwd); cores=${2:-1}
src=${VEXII_SRC:-$HOME/fpga/src/VexiiRiscv}
export PATH=$PATH:$HOME/.local/share/coursier/bin
core="--xlen=64 --with-isa=m,a,c,f,d,s,u,zicntr,zihpm --with-fetch-l1 --with-lsu-l1 --fetch-l1-ways=4
      --fetch-l1-mem-data-width-min=64 --lsu-l1-ways=4 --lsu-l1-mem-data-width-min=64 --fma-reduced-accuracy
      --fpu-ignore-subnormal --with-btb --with-ras --with-gshare --regfile-sync"
# Devices sit in 0x1000_0000-0x1fff_ffff (the 16550 and the virtio slots): a device region must not
# cover the cluster's own CLINT and PLIC, or their accesses decode twice.
soc="--reset-vector=2147483648 --cpu-count=$cores --litedram-width=64
     --device-region clint=0x2000000 --device-region plic=0xc000000
     --memory-region=2147483648,2147483648,rwxc,m --memory-region=268435456,268435456,rw,p"
cd "$src"
sbt -batch "runMain vexiiriscv.soc.litex.SocGen $(echo $core $soc) --netlist-directory=$out --netlist-name=VexiiCluster" \
    > "$out/VexiiCluster.log" 2>&1 || { tail -30 "$out/VexiiCluster.log"; exit 1; }
cat "$here/vexii-rams.v" >> "$out/VexiiCluster.v"   # the RAMs it leaves as black boxes
# Prefix the netlist's submodules (StreamFifo, StreamArbiter_1, ...) so they cannot clash with another
# SpinalHDL design's modules of the same names in one simulation or synthesis
python3 - "$out/VexiiCluster.v" <<'PY'
import re, sys
p = sys.argv[1]; v = open(p).read()
names = [m for m in re.findall(r"^module\s+(\w+)", v, re.M) if m != "VexiiCluster"]
pat = re.compile(r"(?<![\w$])(" + "|".join(sorted(map(re.escape, names), key=len, reverse=True)) + r")(?![\w$])")
open(p, "w").write(pat.sub(r"Vx_\1", v))
PY
echo "VexiiCluster: $cores core(s), VexiiRiscv $(git -C "$src" rev-parse --short HEAD) -> $out/VexiiCluster.v"
