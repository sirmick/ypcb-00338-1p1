# Getting started

From a bare card to a DDR3 memory test, with open tools building every bitstream.

## What you need

- **The card** in a PCIe slot (it takes power from the slot; a host that boots with it is easiest),
  plus a fan or chassis airflow: it is passively cooled and reaches 60 °C with small designs.
- **A JTAG cable** on header J1 (Xilinx 2×7 order). We use a Digilent JTAG-HS3; other FT232H cables work
  with openFPGALoader.
- **A Linux machine** for builds. Synthesis is single-threaded: a fast core matters more than many. A
  large design needs 16 GB+; the chipdb generator needs about 13 GB once.

Our own setup (the card in one host, builds on another, BMC access) is described in
[hosts/README.md](hosts/README.md).

## 1. The open toolchain

Install openXC7 with its [toolchain installer](https://github.com/openXC7/toolchain-installer) into
`~/opt/openxc7` (yosys, nextpnr-xilinx, prjxray and its database, fpga-assembler). The first build
generates the XC7K480T chipdb (about 2 minutes and 13 GB of RAM, once) into
`~/fpga/chipdb/xc7k480tffg1156.bin`.

Details and the versions we use are in [tools/openxc7.md](tools/openxc7.md).

## 2. The patched nextpnr

Stock nextpnr has three bugs that break block RAM widths and OSERDES outputs on this part. Build ours
(openXC7/nextpnr plus [upstream/](upstream/) patches) into `~/fpga/nextpnr-dev-bin`, where the build
script looks for it:

```text
tools/build-nextpnr.sh
```

## 3. JTAG tools

The `fpga` command loads bitstreams and reads designs' JTAG registers with **xsdb** and **hw_server**,
and reads flash and the system monitor with **Vivado Lab Edition**: AMD's free tools, no licence
needed. Point it at them with `XSDB_SETTINGS` and `VIVADO_LAB_SETTINGS` (paths to each
`settings64.sh`) if they are not in the default places. Loading alone also works with openFPGALoader
(`openFPGALoader -c digilent_hs3 x.bit`); see [tools/jtag.md](tools/jtag.md) for what each tool is
good and bad at.

## 4. Configure `fpga`

- On the machine with the cable, run `fpga` directly.
- From another machine, set `FPGA_HOST` to the card host's ssh name: `fpga` copies this tree to
  `~/hw` there and runs over ssh. Builds run locally if `~/opt/openxc7` exists here.

```text
fpga status          # DONE/INIT; the factory image shows DONE
fpga temp            # die temperature and rails (keep it under 85 °C)
```

## 5. First designs

```text
fpga build designs/blinky && fpga load designs/blinky/build/blinky.bit && fpga pins
fpga build designs/hello  && fpga load designs/hello/build/hello.bit   && fpga user1
```

blinky blinks the LEDs (`fpga pins` measures them by boundary scan); hello proves the JTAG debug
register that every other design's `check.py` relies on. A load lasts until power-off or `fpga
jprogram`, after which the factory image boots from flash again.

## 6. DDR3

[designs/uberddr3](designs/uberddr3/) builds AngeloJacobo's [UberDDR3](https://github.com/AngeloJacobo/UberDDR3)
controller with our fixes applied at build time. UberDDR3 is GPL-3.0, so it is not in this repository:

```text
git clone https://github.com/AngeloJacobo/UberDDR3 ~/fpga/src/UberDDR3   # tested at 79d8fd3
fpga build designs/uberddr3 ypcb_00338_1p1_ddr3                          # channel 0, all 9 lanes
fpga load designs/uberddr3/build/ypcb_00338_1p1_ddr3.bit
fpga check designs/uberddr3          # calibration state: PASS when calibrated
fpga memtest                         # all 2 GiB: write, read back, errors and bandwidth
```

`UBER_CHANNEL=1` builds channel 1; `UBER_DUAL=1` builds both (an hour to route), then
`fpga check designs/uberddr3 1` and `fpga memtest --channel 1`. About one placement in four fails to
calibrate one lane (open, see [ROADMAP.md](ROADMAP.md)): if `check` fails, rebuild with
`NEXTPNR_ARGS="--seed N"`.

## 7. Your own design

See "Starting a new design" in [tools/openxc7.md](tools/openxc7.md), and read the card page's rules
first: [board/ypcb-00338-1p1/README.md](board/ypcb-00338-1p1/README.md).
