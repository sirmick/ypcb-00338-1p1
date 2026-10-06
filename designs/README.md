# Designs

Small test designs for the [YPCB-00338-1P1](../board/ypcb-00338-1p1/README.md). Build one with
`fpga build designs/<name>` (output in `designs/<name>/build/`), load it with
`fpga load designs/<name>/build/<top>.bit`. Each directory holds the Verilog and one XDC; the top
module is the directory name unless given (`fpga build designs/blinky-ac24drive blinky_ac24drive`).
A design with a `check.py` is checked with `fpga check designs/<name>` (PASS/FAIL); a `prebuild.py`
writes generated inputs (such as `$readmemh` files) into the build directory first.

| Design | Proves | Check |
|---|---|---|
| [blinky](blinky/) | outputs drive, 50 MHz clock | `fpga pins`: 0.37 / 0.37 / 1.49 Hz, all driven |
| [hello](hello/) | BSCANE2 USER1 register | `fpga user1`: magic `c0ffee42`, ~50 MHz |
| [blinky-ac24drive](blinky-ac24drive/) | AC24 handled by the design itself (no board.fasm) | build with `BOARD_FASM=`; `fpga status` shows AC24 driven high |
| [clocks](clocks/) | gate 4: MMCM + PLL, 13 outputs 50–333 MHz | `fpga check designs/clocks` |
| [bram](bram/) | gate 5: 4096x36 + 1024x18 inferred BRAM at 200 MHz | `fpga check designs/bram` |
| [bramwidths](bramwidths/) | gate 5: all 13 RAMB36/RAMB18 widths | `fpga check designs/bramwidths` (needs the patched nextpnr) |
| [dsp](dsp/) | gate 6: DSP48E1 multiply, accumulate, pre-adder, cascade | `fpga check designs/dsp` (bit-exact vs Python) |
| [pcie-x1](pcie-x1/) | gate 8: PCIe Gen2 x1 endpoint (regymm/pcie_7x), BAR0 register file | load, warm-reboot dino, then `sudo python3 ~/hw/designs/pcie-x1/check-host.py` on dino; `fpga check designs/pcie-x1` shows link and TLP counters |
| [serdes-loop](serdes-loop/) | gate 9: OSERDES -> pad -> IDELAY -> ISERDES loopback, tap x bitslip sweep | `fpga check designs/serdes-loop` (SL_PIN, SL_RATE, SL_CLKB select variants) |
| [clk200](clk200/) | gate 9: the two 200 MHz DDR3 reference oscillators | `fpga check designs/clk200` |
| [litex-ddr3](litex-ddr3/) | abandoned: LiteX SoC with LiteDRAM; its BIOS runs over JTAG but LiteDRAM never calibrated (DDR3-1000/800/667) | `fpga bios designs/litex-ddr3/build/ypcb_00338_1p1.bit` |
| [uberddr3](uberddr3/) | gate 9: UberDDR3 (GPL, copied from your checkout at build time) with our fixes: all 9 lanes, CK phase, I/O-tile clock inversion; `UBER_CHANNEL=0/1`, `UBER_DUAL=1` for both, `UBER_CK_PHASE`; JTAG debug register and [memtest.v](uberddr3/memtest.v) | `fpga check designs/uberddr3 [channel]`, `fpga memtest [--channel 1]` |
| [timing](timing/) | gate 7: carry chains, #134 shape, CE-gated SRLs at a swept clock | `TIMING_MHZ=400 FREQ=400 BUILD=build-400 NEXTPNR_ARGS=--timing-allow-fail fpga build designs/timing`, then `fpga check designs/timing` |
| [clockbuf](clockbuf/), [clockbuf-bufr](clockbuf-bufr/), [bufr-pin](bufr-pin/) | BUFH / BUFR attempts | do not work yet (UPSTREAM 6, 7) |
| [vexii-s0](vexii-s0/) | SoC S0: VexiiRiscv's MicroSoc, one RV64IMAFDC core (LiteX's "debian" options), generated at build time from a VexiiRiscv checkout; a bare-metal self-test in RAM reports on the LEDs | `fpga pins`: LED0 at 2 Hz and LED2 on = pass |
