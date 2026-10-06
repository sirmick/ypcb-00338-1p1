#!/usr/bin/env python3
"""SoC roadmap S0: what one VexiiRiscv core costs under openXC7.

Generates VexiiRiscv's MicroSoc (one core, 64 KiB RAM at 0x8000_0000, CLINT, PLIC, UART, LEDs) from a local
checkout (VEXII_SRC, default ~/fpga/src/VexiiRiscv) into the build directory, with the core options
LiteX uses for its "debian" variant (RV64IMAFDC, Sv39, 4-way L1s, BTB/RAS/GShare), or VEXII_CORE=imac
for RV64IMAC without FPU. The RAM holds sw/selftest.elf (VEXII_CORE=imac fails its FPU test by design:
the point there is the size). Needs sbt (coursier), a JDK and riscv64-unknown-elf-gcc.
"""
import os, shutil, subprocess, sys

out = sys.argv[1]
src = os.path.expanduser(os.environ.get("VEXII_SRC", "~/fpga/src/VexiiRiscv"))
core = os.environ.get("VEXII_CORE", "debian")
common = ("--xlen=64 --with-fetch-l1 --with-lsu-l1 --fetch-l1-ways=4 --fetch-l1-mem-data-width-min=64 "
          "--lsu-l1-ways=4 --lsu-l1-mem-data-width-min=64 --with-btb --with-ras --with-gshare --regfile-sync")
isa = {"debian": "--with-isa=m,a,c,f,d,s,u,zicntr,zihpm --fma-reduced-accuracy --fpu-ignore-subnormal",
       "imac": "--with-isa=m,a,c,s,u,zicntr,zihpm"}[core]
# The self-test (sw/) runs from RAM and reports on three LEDs through the demo peripheral
here = os.path.dirname(os.path.abspath(__file__))
subprocess.run(["make", "-s", "-C", os.path.join(here, "sw")], check=True)
elf = os.path.join(here, "sw", "selftest.elf")
args = (f"{common} {isa} --jtag-tap false --ram-bytes 65536 --system-frequency 50000000 "
        f"--ram-elf {elf} --demo-peripheral leds=3,buttons=1")
env = dict(os.environ, PATH=os.environ["PATH"] + ":" + os.path.expanduser("~/.local/share/coursier/bin"))
r = subprocess.run(["sbt", "-batch", f"Test/runMain vexiiriscv.soc.micro.MicroSocGen {args}"], cwd=src, env=env,
                   capture_output=True, text=True)
if r.returncode != 0 or not os.path.exists(os.path.join(src, "MicroSoc.v")):
    sys.exit(r.stdout[-3000:] + r.stderr[-2000:])
shutil.copy(os.path.join(src, "MicroSoc.v"), os.path.join(out, "gen_microsoc.v"))
for f in os.listdir(src):                 # memory initialisation files ($readmemb) for the RAM
    if f.startswith("MicroSoc.v_") and f.endswith(".bin"):
        shutil.copy(os.path.join(src, f), os.path.join(out, f))
rev = subprocess.run(["git", "-C", src, "rev-parse", "--short", "HEAD"], capture_output=True, text=True).stdout.strip()
print(f"vexii-s0: MicroSoc, core {core}, VexiiRiscv {rev}")
