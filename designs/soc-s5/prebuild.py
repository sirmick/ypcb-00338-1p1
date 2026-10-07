#!/usr/bin/env python3
"""SoC roadmap S5c: the guest SoC on the card. Brings together, in the build directory:
- the PCIe hard-block wrapper vendored in designs/pcie-x1 (regymm/pcie_7x, CERN-OHL-P);
- UberDDR3 for channel A, 8 byte lanes (GPL-3.0: copied from your checkout at build time by
  designs/uberddr3/prebuild.py, never kept here), and its pin constraints, joined with PCIe's;
- VexiiRiscv's cluster (soc/vexii-cluster.sh; SOC_S5_CORES, default 1);
- the card (soc/: CardS5 = SocCore and its clock crossings), generated with sbt.
Needs sbt (coursier), a JDK, a VexiiRiscv checkout and an UberDDR3 checkout at 79d8fd3 (UBERDDR3_SRC).
"""
import os, shutil, subprocess, sys

out = sys.argv[1]
here = os.path.dirname(os.path.abspath(__file__))
hw = os.path.abspath(os.path.join(here, "..", ".."))
soc = os.path.join(hw, "soc")
cores = os.environ.get("SOC_S5_CORES", "1")

for f in ("pcie_7x.v", "pcie_block.v", "pcie_axi_rx.v", "pcie_axi_tx.v", "pcie_tx_thrtl_ctl.v",
          "pipe_wrapper_gtx.v", "pcie_brams.v", "xilinx_pcie_mmcm.v"):
    shutil.copy(os.path.join(hw, "designs", "pcie-x1", f), os.path.join(out, "gen_" + f))

uber = os.path.join(out, "uber")
os.makedirs(uber, exist_ok=True)
env = dict(os.environ, UBER_LANES="8", UBER_CHANNEL="0")
env.setdefault("UBERDDR3_SRC", os.path.expanduser("~/fpga/src/UberDDR3-79d8fd3"))
subprocess.run([sys.executable, os.path.join(hw, "designs", "uberddr3", "prebuild.py"), uber], env=env, check=True)
for f in ("gen_uber0.v", "gen_uber1.v", "gen_uber2.v"):
    shutil.copy(os.path.join(uber, f), os.path.join(out, f))
# The SoC's 50 MHz clock comes from the DDR3 PLL's free sixth output (1000 MHz / 20)
def sub(text, a, b):
    assert a in text, a
    return text.replace(a, b)
wiz = open(os.path.join(uber, "gen_uber_clkwiz.v")).read()
wiz = sub(wiz, "  output wire        clk_out5,\n", "  output wire        clk_out5,\n  output wire        clk_out6,\n")
wiz = sub(wiz, "    .CLKIN1_PERIOD        (20.000)",
          "    .CLKOUT5_DIVIDE       (20), // 1000 MHz / 20 = 50 MHz: the SoC\n    .CLKOUT5_PHASE        (0.000),\n"
          "    .CLKOUT5_DUTY_CYCLE   (0.500),\n    .CLKIN1_PERIOD        (20.000)")
wiz = sub(wiz, "    .CLKOUT4             (clk_out5_pll),\n", "    .CLKOUT4             (clk_out5_pll),\n    .CLKOUT5             (clk_out6_pll),\n")
wiz = sub(wiz, "endmodule", "  wire clk_out6_pll;\n  BUFG clkout6_buf (.O(clk_out6), .I(clk_out6_pll));\nendmodule")
open(os.path.join(out, "gen_uber_clkwiz.v"), "w").write(wiz)
with open(os.path.join(out, "gen_soc_s5.xdc"), "w") as x:
    x.write(open(os.path.join(uber, "gen_uber.xdc")).read())
    x.write("\n# PCIe (designs/pcie-x1)\n")
    for line in open(os.path.join(hw, "designs", "pcie-x1", "pcie-x1.xdc")):
        if any(p in line for p in ("sys_clk", "pci_exp", "sys_rst_n")):
            x.write(line)

vexii = os.path.join(out, "vexii")
subprocess.run([os.path.join(soc, "vexii-cluster.sh"), vexii, cores], check=True)
shutil.copy(os.path.join(vexii, "VexiiCluster.v"), os.path.join(out, "gen_vexii.v"))

gen = os.path.join(out, "card-rtl")
penv = dict(os.environ, PATH=os.environ["PATH"] + ":" + os.path.expanduser("~/.local/share/coursier/bin"))
r = subprocess.run(["sbt", "-batch", f"runMain card.VerilogS5 {gen} {os.path.join(vexii, 'VexiiCluster.v')}"],
                   cwd=soc, env=penv, capture_output=True, text=True)
if r.returncode != 0 or not os.path.exists(os.path.join(gen, "CardS5.v")):
    sys.exit(r.stdout[-3000:] + r.stderr[-2000:])
shutil.copy(os.path.join(gen, "CardS5.v"), os.path.join(out, "gen_card.v"))
print(f"soc-s5: CardS5, {cores} core(s)")
