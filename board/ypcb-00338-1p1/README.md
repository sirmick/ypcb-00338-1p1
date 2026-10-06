# YPCB-00338-1P1 (Kintex-7 XC7K480T PCIe card)

A surplus datacenter FPGA accelerator, sold cheaply as "Inspur XC7K480T development board",
"YZCA-00338" or "00338-P1". Ours lives in host [dino](../../hosts/README.md). We drive it with the
[open toolchain](../../tools/openxc7.md) over [JTAG](../../tools/jtag.md); the day-to-day commands
are in [`fpga`](../../README.md#the-fpga-command).

What happened when is in [LOG.md](LOG.md); this page is the current truth. Facts we measured on our
card are marked **(measured)**; facts from other people are linked to their source.

## The rules

- **Never program an eFUSE or any OTP bit.** Fuse access is read-only (`fpga fuses`).
- **Ask before writing the BPI flash.** SRAM loads over JTAG are fine; they vanish at power-off.
- **Pin AC24 must never be pulled down** (see [AC24](#ac24-the-board-reset-pin)). Every build gets
  [`board.fasm`](board.fasm), which pulls it up.
- The factory image is kept ([factory-flash/](#the-factory-flash)) but will not be restored.

## At a glance

| | |
|---|---|
| FPGA | XC7K480T-2FFG1156 (U3), IDCODE `0x23751093` = rev 2 **(measured)** |
| Configuration | Master BPI x16, M[2:0] = 010 **(measured, STAT MODE=2)**; JTAG on J1 |
| Flash | Micron MT28GU512 BPI NOR, 64 MiB, x16 (U4) |
| Memory | Two 72-bit DDR3 channels (64 data + 8 ECC), 2 GB each, 9× MT41K256M8 per channel |
| Host link | PCIe x8 (Gen2 proven by others), ref clock 100 MHz from the slot |
| Clocks | 50 MHz on AA28 **(measured)**; 200 MHz LVDS on AH27/AH28 and G25/G26 (DDR references) |
| User I/O | 3 LEDs, LM73 temperature sensor, PCIe SMBus, reset button |
| Identity | Baidu product ID `SCH-001-003` (the string is in our flash); built by Inspur, a Celestica sibling exists |

Board photo: [ypcb003381p1_image.jpg](ypcb003381p1_image.jpg). Reference designators (from
[ne5532's teardown](https://www.cnblogs.com/ne5532/articles/18534334)):

| Ref | Part | Ref | Part |
|---|---|---|---|
| U3 | XC7K480T FPGA | U23 | LM73 temperature sensor |
| U4 | MT28GU512 BPI flash | U22, U26 | PCA9517A I²C buffers |
| U5–U13 | DDR3 channel 0 | U2 | TXS0108 1.8 V/3.3 V level shifter (purpose unknown) |
| U1, U14–U21 | DDR3 channel 1 | Y2 | 50 MHz oscillator |
| J1 | JTAG header | Y1, Y3 | 200 MHz differential oscillators |
| SW1 | → INIT_B | SW2 | → a GPIO, used as design reset (probably R28) |

**No schematic is public.** Every source agrees (the [Ukrainian reseller](https://fpga.com.ua/index.php?product_id=329&route=product%2Fproduct)
says so outright); the pin map below was reverse-engineered by
[TiferKing](https://github.com/TiferKing/ypcb_00338_1p1_hack) and others.

## With open tools (measured, 2026-10-06)

| Block | Result | Design |
|---|---|---|
| 50 MHz clock, LEDs, startup | works once AC24 is pulled up | [blinky](../../designs/blinky/) |
| BSCANE2 debug register | works; a second BSCANE2 does not route | [hello](../../designs/hello/) |
| MMCM / PLL | 13 outputs exact, 50–333 MHz, fractional /7.5; BUFG works, BUFR/BUFH do not | [clocks](../../designs/clocks/) |
| 200 MHz oscillators | both run: 199.999 MHz on AH27/AH28 and G25/G26 | [clk200](../../designs/clk200/) |
| Block RAM | all RAMB36/RAMB18 widths, with our nextpnr patches 0001/0002 | [bramwidths](../../designs/bramwidths/) |
| DSP48E1 | pipelines, cascade, pre-adder bit-exact at 200 MHz | [dsp](../../designs/dsp/) |
| Fabric timing | carry chains and SRLs correct at 400 MHz, fail at 450; nextpnr says 235 | [timing](../../designs/timing/) |
| PCIe | Gen2 x1 endpoint, BAR0 round-trips, regymm/pcie_7x | [pcie-x1](../../designs/pcie-x1/) |
| I/O SERDES and IDELAY | loopback eye 18 taps at 500 Mb/s, with our nextpnr patch 0003 | [serdes-loop](../../designs/serdes-loop/) |
| **DDR3** | both channels, 9 lanes each, in one bitstream, DDR3-667: 2 GiB each at 5.04 GB/s, 0 errors, also after a cold start | [uberddr3](../../designs/uberddr3/) |

DDR3 on this card, the facts that matter (details in [designs/uberddr3](../../designs/uberddr3/) and
the [log](LOG.md)):

- **The DDR3 banks need internal VREF.** Without it nothing calibrates; nextpnr sets 0.675 V on its
  own, and 0.75 V behaves the same. (Vivado's MIG uses the board's external VREF; our input buffers
  differ from its, which may be why.)
- **No ODELAY (HR banks), so no write leveling.** CK reaches the nine chips one after another
  (fly-by), and UberDDR3 cannot adjust DQS per chip. One global CK delay must suit all nine: from its
  own PLL output, CK calibrates cleanly from +30° to +90° on both channels; +60° is the default.
  Vivado's MIG levels writes with the undocumented PHASER_OUT and runs this board at DDR3-1066.
- **CK and DQS clock inversion must happen in the I/O tile**, not in a fabric LUT, or the clock
  arrives late by an amount that depends on placement.
- Lanes 0–3 (bank 11) settle at DQ input delays of 3–6 taps, lanes 4–7 (banks 12/13) near 30: one
  bus-wide read setting would not fit both.
- The board has no DM pins; channel 0 is banks 11–13 (address/command in 12), channel 1 banks 16–18.

## Pins

Every I/O bank on this part is HR (high range). The single-ended user pins are in banks 14 and 15
at 1.8 V, LVCMOS18; bank 14 holds the dual-purpose configuration pins, so designs need
`CONFIG_VOLTAGE 1.8` and `CFGBVS GND` (as in the openTPU and openFPGALoader XDCs). DDR3 channel 0
uses banks 11–13 and channel 1 banks 16–18, SSTL15 at 1.5 V (package file:
[datasheets/xc7k480tffg1156-pkg.txt](../../datasheets/xc7k480tffg1156-pkg.txt)).

| Signal | Pin(s) | Notes |
|---|---|---|
| SYS_CLK 50 MHz | AA28 | MRCC, bank 14. **50 MHz measured** (as TiferKing's board files say) |
| CLK200 #0 (p/n) | AH27 / AH28 | bank 12, DDR ch0 MIG reference; LiteX uses LVDS_25, TiferKing LVDS + DQS_BIAS |
| CLK200 #1 (p/n) | G25 / G26 | bank 17, DDR ch1 MIG reference |
| RESETN (SW2?) | R28 | design reset input |
| **AC24** | AC24 | **board reset: pull up or drive high, never low** |
| LED red / green / yellow | P30 / M30 / N30 | active high **(measured)** |
| LM73 SCL / SDA / ALERT | N24 / N25 / P25 | LM73 at I²C 0x4A ([FeSens](https://github.com/FeSens/openTPU/blob/main/docs/observability.md)) |
| PCIe SMBus SCL / SDA | R26 / R27 | nothing answers on it (FeSens) |
| PCIe PERST# | Y26 | |
| PCIe ref clock | J8 / J7 | 100 MHz from the slot |
| PCIe lanes 0..7 TX p | F2 H2 K2 M2 N4 P2 T2 U4 | RX p: H6 J4 K6 L4 M6 P6 R4 T6 (lane 6 RX n is E3) |
| BPI flash | A1–A25, DQ0–15, WE# T34, OE# T33, CE# V30, ADV# M31 | banks 14/15; full list in the LiteX platform |
| DDR3 ch0 / ch1 | banks 11–13 / 16–18 | full lists: [ypcb003381p1.xdc](ypcb003381p1.xdc), [MEMORY_CH0.ucf](MEMORY_CH0.ucf), [MEMORY_CH1.ucf](MEMORY_CH1.ucf), LiteX platform |

The most complete machine-readable pin list is the LiteX platform
[`ypcb_00338_1p1.py`](https://github.com/litex-hub/litex-boards/blob/master/litex_boards/platforms/ypcb_00338_1p1.py);
TiferKing's [physical_pinout.xlsx](physical_pinout.xlsx) maps every DDR3 chip ball. Fan control and
fan tach pins are undocumented anywhere.

### J1 JTAG header

Standard Xilinx 2×7 order: odd pins 1–13 GND; 2 VREF, 4 TMS, 6 TCK, 8 TDO, 10 TDI, 12 nc, 14 SRST
([TiferKing's pinout image](https://www.tiferking.cn/wp-content/uploads/2024/12/jtag_hs3_pinout.png)).
Our Digilent JTAG-HS3 works on it. The Celestica sibling has a 2 mm header; ours looks like 0.1",
not confirmed. With FT232H cables the chain shows the FPGA alone; a Xilinx Platform Cable USB II
reportedly also shows a phantom `0x10931093` device.

## AC24, the board reset pin

AC24 (`IO_25_14`, `LIOB33_SING_X0Y150` IOB_Y0) is wired to a board reset. Any bitstream that leaves
it at the unused-pin default (pull-down) resets the card the moment startup releases the I/O: DONE
never goes high, the FPGA ends up unconfigured, and every tool reports a startup failure
(Vivado: `End of startup status: LOW`; xsdb: `DONE PIN is not HIGH`). It hits Vivado bitstreams too,
TiferKing's systest included.

- **openXC7:** append `LIOB33_SING_X0Y150.IOB_Y0.PULLTYPE.PULLUP` to the FASM ([board.fasm](board.fasm);
  `tools/xc7-build.sh` does it). Verified: PULLUP and NONE both reach DONE.
- **Vivado:** `set_property BITSTREAM.CONFIG.UNUSEDPIN PULLUP [current_design]`, or drive AC24 high.
- How we found it: bisecting a bitstream that does start down to one bit
  ([tools/bitstream/start-bisect.py](../../tools/bitstream/start-bisect.py), ~40 loads). Found
  independently by Maccraft123 ([TiferKing#3](https://github.com/TiferKing/ypcb_00338_1p1_hack/issues/3))
  and explained by [zollij](https://gist.github.com/zollij/bf49d29b0ab79d38346dda2a0e32b5b9)
  (patching COR0 `GTS_CYCLE=Keep` lets startup finish, which pins the reset to GTS release).
- **(measured)** With the design not driving it, the AC24 pad reads 0 even with no pull, so the board
  pulls it low; the factory image drives it high as an output. The undocumented CTL1 bit 12 that
  Vivado sets with `UNUSEDPIN PULLUP` does nothing on its own (tried by us and by zollij).

## Configuration and status

- Master BPI: after power-up or JPROGRAM the FPGA boots the factory image from flash in about 3.5 s
  **(measured)**. JTAG loads override it without trouble once AC24 is handled.
- eFUSE (read-only, 2026-10-04): FUSE_CNTL `0x000000C0` (only reserved bits 6–7; no AES or lock bits),
  FUSE_USER `0`, FUSE_DNA raw capture `231e3703125b0f3a`.
- Good STAT after a successful boot: `0x4107afc` (DONE, EOS, GWE, GTS released, MODE 010, x16).
- Checking state without disturbing anything: `fpga status` (IR capture bit 5 = DONE, plus the DONE,
  INIT_B and AC24 pads by boundary scan).

## Power and thermals (measured)

| Condition | Die temperature |
|---|---|
| Unconfigured | ~42 °C |
| TiferKing systest (MicroBlaze, 2× MIG, XDMA) | 51–55 °C |
| openXC7 blinky | 55 → 62 °C over 3 min, still rising |

Measured in dino's chassis airflow; the card is passively cooled. The limit is 85 °C (commercial
grade). **VCCINT reads 0.959 V**, at or just under the datasheet minimum of 0.97 V (XADC is ±1%);
watch it under heavy designs. VCCAUX 1.79–1.80 V. Read with `fpga temp`.

## The factory flash

- One 18.73 MB uncompressed, unencrypted bitstream at offset 0, WBSTAR = 0 (no multiboot, no
  fallback). Its PCIe endpoint is `10ee:7028` with a 2 MB BAR0. Probably Baidu's 2015–16 SDA
  accelerator firmware (guess).
- ASCII record `SCH-001-003-20160217-0227` at `0x3FC0000` (last block): Baidu product ID, then
  date and unit number. The rest of the flash is erased.
- Kept in [factory-flash/](factory-flash/) (git-ignored, read-only, SHA-256 in `factory.sha256`) and
  on dino in `~/fpga/flash-backup/`:
  - `factory-flash-64M.raw.bin`: raw flash, Vivado readback order.
  - `factory-image-bitorder.bin`: the bitstream in loadable order (each 16-bit word byte-swapped and
    bit-reversed relative to raw). Loads with `fpga load`.
- Other people's dumps of both variants: [LukeVassallo's share](https://cloud.lukevassallo.com/index.php/s/N5LsR6RKdfmsPy3).
- `fpga flash-readback <out.bin>` reads all 64 MiB in ~60 s (Vivado Lab). openFPGALoader's
  bpiOverJtag dump runs at ~2.6 KB/s, too slow to use.

## Known-good designs

| Design | Built with | Proves | Notes |
|---|---|---|---|
| [designs/blinky](../../designs/blinky/) | openXC7 | outputs, 50 MHz clock | `fpga pins` shows 0.37 / 1.49 Hz |
| [designs/hello](../../designs/hello/) | openXC7 | BSCANE2 / USER1 | `fpga user1`: magic `c0ffee42`, counter at 49.98 MHz |
| [designs/pcie-x1](../../designs/pcie-x1/) | openXC7 | PCIe Gen2 x1 | enumerates as `10ee:0480`; BAR0 writes and reads round-trip |
| [designs/uberddr3](../../designs/uberddr3/) | openXC7 + UberDDR3 | both DDR3 channels | `fpga check`, `fpga memtest`: 2 GiB per channel, 0 errors |
| TiferKing systest `top_wrapper.bit` | Vivado 2022.2 | MicroBlaze, 2× MIG DDR3, GPIO | Pulls AC24 down: patch it before reuse. Copy on dino: `~/fpga/flash-backup/ref/` |
| openFPGALoader `bpiOverJtag_xc7k480tffg1156` | Vivado | flash access | Always starts (it leaves AC24 unpulled) |

**DDR3 proven by sample with the systest (2026-10-04):** both channels pass an address-line test
(bits 2..30), a data-bus walk and 256 KiB of pseudo-random blocks per channel. ECC was on, with no
CE/UE logged after clearing. MicroBlaze address map: GPIO `0x40000000`, IIC `0x40800000` /
`0x40810000`, EMC (flash) `0x60000000`, MIG ECC CSR `0x76100000` / `0x76200000`, **DDR ch0
`0x1_0000_0000` and ch1 `0x1_8000_0000`** (36-bit addresses). `0x80000000` is DDR only from the
XDMA side. Scripts: `scripts/ddr-test.xsdb.tcl`, `scripts/ddr-ecc-check.xsdb.tcl`.

Other people's working designs for this card:

| Project | What |
|---|---|
| [TiferKing/ypcb_00338_1p1_hack](https://github.com/TiferKing/ypcb_00338_1p1_hack) | Vivado board files, XDC, MIG settings, MicroBlaze + XDMA + MIG example |
| [litex-boards ypcb_00338_1p1](https://github.com/litex-hub/litex-boards/blob/master/litex_boards/targets/ypcb_00338_1p1.py) | LiteX target: both DDR3 channels, PCIe x1/x4/x8 |
| [FeSens/openTPU](https://github.com/FeSens/openTPU) | Large Vivado + LiteDRAM design; both DDR3 channels calibrate; PCIe Gen2 x8 XDMA |
| [FeSens/inspur-adventures](https://github.com/FeSens/inspur-adventures), [bonetto-soc](https://github.com/FeSens/bonetto-soc) | openXC7 bring-up and DDR3 validation |
| [regymm/pcie_7x](https://github.com/regymm/pcie_7x) | open-toolchain PCIe (`pcie_7x_ypcb_k480t.xdc`) |
| [openXC7 demo-projects blinky-ypcb003381p1](https://github.com/openXC7/demo-projects/tree/main/blinky-ypcb003381p1) | the upstream openXC7 blinky |
| [UberDDR3 ypcb_00338_1p1 example](https://github.com/AngeloJacobo/UberDDR3/tree/main/example_demo/ypcb_00338_1p1) | open DDR3 controller: one lane; under openXC7 it calibrates only in some placements (issue #44), which our fixes resolve |

## Scripts

Run them through [`fpga`](../../README.md#the-fpga-command); the raw scripts are in [scripts/](scripts/).

| Script | Tool | Does |
|---|---|---|
| `ir-status.xsdb.tcl` | xsdb | INIT/DONE from the IR capture, non-invasive |
| `cfgpins.xsdb.tcl` | xsdb | DONE, INIT_B and AC24 pads by boundary scan |
| `load.xsdb.tcl` | xsdb | load a .bit/.bin, report DONE now and 5 s later |
| `pins-sample.xsdb.tcl` | xsdb | 100 boundary-scan samples of the LED pins |
| `user1-read.xsdb.tcl` | xsdb | read hello's USER1: magic + counter rate |
| `jprogram.xsdb.tcl` | xsdb | JPROGRAM; the factory image then boots from flash |
| `fuse-read.xsdb.tcl` | xsdb | IDCODE and eFUSE registers, shifting zeros only |
| `xadc-temp.vivado.tcl` | vivado_lab | die temperature and rails |
| `flash-readback.vivado.tcl` | vivado_lab | whole flash, read-only (never program or erase) |
| `ddr-test.xsdb.tcl`, `ddr-ecc-check.xsdb.tcl` | xsdb | systest only: DDR3 tests and ECC counters |
| `systest-leds-ddr.xsdb.tcl` | xsdb | systest only: LEDs via GPIO (its DDR part uses the wrong address) |

Boundary-scan cells (BSDL `/opt/Xilinx/2026.1/Vivado/ids_lite/ISE/kintex7/data/xc7k480t_ffg1156.bsd`,
1395 cells; control cell 1 = tri-stated):

| Pin | control / output / input |
|---|---|
| P30 (led0) | 851 / 852 / 853 |
| M30 (led1) | 845 / 846 / 847 |
| N30 (led2) | 848 / 849 / 850 |
| AC24 | 635 / 636 / 637 |
| AA28 (SYS_CLK) | – / – / 709 |
| DONE / INIT_B | 11 / 12 / 13, 8 / 9 / 10 |

## Open questions

The bring-up plan is [ROADMAP.md](../../ROADMAP.md); these are the card-level unknowns.


- What exactly AC24 resets (the PCIe slot, a power sequencer, the TXS0108?). Only the schematic or a
  meter on the board would tell.
- Fan control and tach pins.
- Why about one placement in four leaves one DDR3 lane uncalibrated, with identical I/O configuration
  and clean timing (see the [ROADMAP](../../ROADMAP.md)).
- DDR3 above 667 MT/s without write leveling.
