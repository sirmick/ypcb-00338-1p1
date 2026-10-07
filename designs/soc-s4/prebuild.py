#!/usr/bin/env python3
"""SoC roadmap S4b: copies in the PCIe hard-block wrapper vendored in designs/pcie-x1 (regymm/pcie_7x,
CERN-OHL-P) and generates the card (soc/: CardS4, PcieLink and guest block RAM) with sbt.
SOC_S4_GEN2=1 builds the Gen2 variant (125 MHz user clock; build with FREQ=125). Needs sbt (coursier)
and a JDK.
"""
import os, shutil, subprocess, sys

out = sys.argv[1]
here = os.path.dirname(os.path.abspath(__file__))
pcie = os.path.join(here, "..", "pcie-x1")
soc = os.path.join(here, "..", "..", "soc")
for f in ("pcie_7x.v", "pcie_block.v", "pcie_axi_rx.v", "pcie_axi_tx.v", "pcie_tx_thrtl_ctl.v",
          "pipe_wrapper_gtx.v", "pcie_brams.v", "xilinx_pcie_mmcm.v"):
    shutil.copy(os.path.join(pcie, f), os.path.join(out, "gen_" + f))
gen = os.path.join(out, "card-rtl")
env = dict(os.environ, PATH=os.environ["PATH"] + ":" + os.path.expanduser("~/.local/share/coursier/bin"))
r = subprocess.run(["sbt", "-batch", f"runMain card.VerilogS4 {gen}"], cwd=soc, env=env, capture_output=True, text=True)
if r.returncode != 0 or not os.path.exists(os.path.join(gen, "CardS4.v")):
    sys.exit(r.stdout[-3000:] + r.stderr[-2000:])
with open(os.path.join(out, "gen_card.v"), "w") as f:
    if os.environ.get("SOC_S4_GEN2") == "1":
        f.write("`define SOC_S4_GEN2\n")
    f.write(open(os.path.join(gen, "CardS4.v")).read())
print(f"soc-s4: CardS4, Gen{2 if os.environ.get('SOC_S4_GEN2') == '1' else 1}")
