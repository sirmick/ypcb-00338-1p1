# YPCB-00338-1P1 with open tools

The YPCB-00338-1P1 is a surplus datacenter accelerator card: a Kintex-7 **XC7K480T**, two 72-bit
**DDR3** channels (2 GiB each, with ECC), **PCIe x8**, a BPI flash, sold cheaply as the "Inspur
XC7K480T development board". This repository gets it working with **open tools only**: yosys,
nextpnr-xilinx and prjxray (the [openXC7](https://github.com/openXC7) flow), with no Vivado-built
bitstreams. Every claim here was measured on a real card.

There is no public schematic; the pin map comes from
[TiferKing](https://github.com/TiferKing/ypcb_00338_1p1_hack) and others, credited in
[references/](references/README.md).

## What works

| | Status | Proof |
|---|---|---|
| Configuration over JTAG, reliable startup | ✅ | pin AC24 is a board reset: every build pulls it up ([board.fasm](board/ypcb-00338-1p1/board.fasm)) |
| Logic, I/O, the 50 MHz clock | ✅ | [designs/blinky](designs/blinky/) |
| JTAG debug channel from a design (BSCANE2) | ✅ | [designs/hello](designs/hello/) |
| MMCM/PLL at full rate (50–333 MHz, fractional) | ✅ | [designs/clocks](designs/clocks/); BUFR/BUFH do not work yet |
| Block RAM, every width | ✅ with our nextpnr patches | [designs/bramwidths](designs/bramwidths/) |
| DSP48E1 | ✅ | [designs/dsp](designs/dsp/) |
| Timing: silicon runs to 400 MHz where nextpnr says 235 | ✅ | [designs/timing](designs/timing/) |
| PCIe Gen2 x1 endpoint | ✅ | [designs/pcie-x1](designs/pcie-x1/) (regymm/pcie_7x) |
| **DDR3: both channels, 9 lanes each, ECC lanes included** | ✅ DDR3-667 | [designs/uberddr3](designs/uberddr3/): 2 GiB per channel written and read back with 0 errors at 5.04 GB/s each, also after a cold start |

The full story, gate by gate, is in [ROADMAP.md](ROADMAP.md); what happened when, wrong turns
included, is in [the log](board/ypcb-00338-1p1/LOG.md). Bugs found in the open tools, with evidence
and fixes, are in [UPSTREAM.md](UPSTREAM.md).

## Start here

| Page | For |
|---|---|
| [GETTING-STARTED.md](GETTING-STARTED.md) | install the tools, build the patched nextpnr, build and load your first design |
| [board/ypcb-00338-1p1/README.md](board/ypcb-00338-1p1/README.md) | **the card**: pins, AC24, configuration, flash, thermals, known-good designs |
| [tools/openxc7.md](tools/openxc7.md) | the open toolchain: build flow, a new design, reading bitstreams, known bugs |
| [tools/jtag.md](tools/jtag.md) | cables, xsdb / Vivado Lab / openFPGALoader, remote checks (boundary scan, USER1, XADC) |
| [designs/README.md](designs/README.md) | the test designs and what each proves |
| [ROADMAP.md](ROADMAP.md) | the gates, their proofs, and what is still open |
| [SOC-ROADMAP.md](SOC-ROADMAP.md) | next: Debian on four VexiiRiscv cores, virtio over PCIe from a userland backend |
| [UPSTREAM.md](UPSTREAM.md) | toolchain bugs: evidence, patches, status |
| [hosts/README.md](hosts/README.md) | how our lab is set up (a card host and a build host), as an example |
| [datasheets/README.md](datasheets/README.md) | which datasheet, which table |
| [references/README.md](references/README.md) | everyone else's work on this card |

## The rules

- **Never program an eFUSE or any OTP bit, on any FPGA.** Fuse access here is read-only.
- **Pin AC24 must never be pulled down.** It resets the card; builds get the pull-up from `board.fasm`.
- Writing the BPI flash replaces the factory image: keep a copy first (`fpga flash-readback`).
- Don't commit datasheet PDFs, flash images, build products or `references/third-party/`
  ([.gitignore](.gitignore)).

## The `fpga` command

[`fpga`](fpga) runs every common operation. On the machine with the JTAG cable it acts directly; from
another machine it syncs this tree to the card host (`FPGA_HOST`, default `dino`) and runs there over
ssh. Builds run locally when the toolchain is installed locally.

```text
fpga status                 configuration state: DONE/INIT, DONE/INIT_B/AC24 pads
fpga build designs/blinky   openXC7 build (+ board.fasm)  -> designs/blinky/build/blinky.bit
fpga load designs/blinky/build/blinky.bit
fpga check designs/<x>      run a design's check.py: reads its JTAG register, prints PASS/FAIL
fpga memtest [--len 25]     DDR3 memory test on the loaded designs/uberddr3
fpga pins | user1 | temp    LEDs by boundary scan, hello's counter, die temperature and rails
fpga jprogram | fuses | flash-readback <out>     (fuses and flash are read-only)
fpga help
```

[`bmc`](bmc) drives the card host's BMC over the LAN (power state, power on, a power cycle), the way
back in when that host is hung or off.

## Layout

```text
.
├── fpga, bmc                  the commands above
├── GETTING-STARTED.md, ROADMAP.md, UPSTREAM.md
├── board/ypcb-00338-1p1/      card page, log, board.fasm, pin files, openocd.cfg, scripts/
├── designs/                   test designs, each with a check.py
├── tools/                     xc7-build.sh, build-nextpnr.sh, jtagdr.py, bscan.py, bitstream/, docs
├── upstream/                  our nextpnr patches (applied by tools/build-nextpnr.sh)
├── hosts/                     our lab setup, as an example
├── datasheets/                index (PDFs via fetch-docs.sh)
└── references/                index of other people's work; third-party/ copies (ignored)
```

## License

[MIT](LICENSE) for everything written here. Vendored third-party code keeps its own license
([designs/pcie-x1](designs/pcie-x1/): CERN-OHL-P), as do TiferKing's board files
([board/ypcb-00338-1p1/LICENSE](board/ypcb-00338-1p1/LICENSE), MIT). UberDDR3 (GPL-3.0) is not
included: [designs/uberddr3](designs/uberddr3/) copies it from your own checkout at build time.
