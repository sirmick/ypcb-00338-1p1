# Upstream contributions

We use the open toolchain, so bugs we hit go back to the projects that own them. Each entry has the
evidence and a draft. **Nothing is filed or pushed without the owner's go-ahead**, since filing
publishes under his account. Before filing, search the project's existing issues; several of these
were found independently by others.

| # | Project | What | Status |
|---|---|---|---|
| 1 | [openXC7/demo-projects](https://github.com/openXC7/demo-projects) | `blinky-ypcb003381p1` does not hold AC24 high, so it resets the card at startup | draft PR ready; proven on our card |
| 2 | [openXC7/prjxray](https://github.com/openXC7/prjxray) (fasm2frames / db) | `LIOB33_SING_X0Y199` IOB_Y0 features: "invalid word address 101" | needs a minimal repro and a duplicate check (zollij reports dropped pulls on eight SING tiles) |
| 3 | [TiferKing/ypcb_00338_1p1_hack](https://github.com/TiferKing/ypcb_00338_1p1_hack) | README/XDC: document AC24 and the 50 MHz SYS_CLK | issue #3 already covers AC24; offer a README patch |
| 4 | [trabucayre/openFPGALoader](https://github.com/trabucayre/openFPGALoader) | `--read-register STAT` returned 0x0 for a configured XC7K480T; `--read-xadc` is Artix-only; BPI dump runs at ~2.6 KB/s | needs a clean repro now that AC24 is understood |
| 5 | [openXC7/nextpnr](https://github.com/openXC7/nextpnr) (xilinx packer) | RAMB36E1 in x9 mode writes the parity bit to only one RAMB18 half: every other word loses bit 8 | **fix proven on silicon**: [patch](upstream/nextpnr-0001-ramb36-x9-parity-both-halves.patch) |
| 6 | openXC7/nextpnr (BUFR on Kintex-7) | `meta/kintex7/site_type_BUFR.json` is missing (also zynq7, spartan7); with artix7's copied in, a pin-fed BUFR places and routes but the FASM has no `BUFR_Y*.IN_USE`/`BUFR_DIVIDE` (write_bufr never fires): the clock is dead; MMCM→BUFR does not route | two gaps found; low priority for us |
| 7 | openXC7/nextpnr (router) | a BUFH net fails to route ("Failed to route arc … from BUFHCE_X0Y0.O to SLICE…") | to investigate: loads outside the BUFH's clock region? |
| 8 | openXC7/nextpnr (timing) | BRAM data-input hold violations (−0.05 ns) fail the build, yet the same paths work on silicon; no hold fixing in the router | evidence collected; decide whether it is a model or a fixing issue |
| 9 | openXC7/nextpnr (xilinx FASM) | RAMB36E1 72-bit SDP: write path corrupts nearly every word (WRITE_WIDTH_A_18 missing on both halves) | **fix proven on silicon**: [patch](upstream/nextpnr-0002-ramb36-sdp72-write-width-a.patch) |
| 10 | YosysHQ/yosys (verilog frontend) | a user module parameter named `INIT` reaches `$readmemh` as an empty string; renaming it works | check for an existing report; may be by design (Xilinx primitive name) |
| 11 | YosysHQ/yosys (xilinx LUTRAM mapping) | a memory with `ram_style = "distributed"` and initial values maps to RAM64M with all-zero INIT (sync and async read); the same memory in BRAM keeps them | minimal repro in hand (8 lines); check against current yosys and existing issues |
| 12 | openXC7/nextpnr (BSCAN routing) | a second BSCANE2 (JTAG_CHAIN=2, placed at BSCAN_X0Y1) cannot route its SHIFT output to slices near the right edge (5M iterations per arc); the first BSCAN routes fine | seen once; needs a small repro |
| 14 | openXC7/nextpnr (xilinx FASM) | an OSERDESE2 with T1 tied to 0 ("always drive") gets `ZINV_T1` from the pad-tristate pseudo-pip rule while its T1 is routed from VCC_WIRE: the pin is permanently tri-stated | **fix proven on silicon**: [patch](upstream/nextpnr-0003-ologic-t1-tied-low-keeps-inverter.patch); LiteX is unaffected (bit-identical), UberDDR3-style designs may not be |
| 15 | openXC7/nextpnr (internal VREF) | SSTL15 banks get `VREF.V_675_MV` (the SSTL135 level) and one gets `ONLY_DIFF_IN_USE`; Vivado's MIG on this board claims external VREF | measured: without internal VREF DDR3 never calibrates (this card's banks need it, as openXC7/nextpnr-xilinx#92 found); 0.675 and 0.75 V behave the same. Low priority |
| 16 | openXC7/nextpnr (timing) | IOLOGIC fabric ports (SERDES D/Q/RST, IDELAY/ODELAY CNTVALUE/LD) have no timing class: paths to and from them are never analysed or placed timing-driven | draft [patch](upstream/nextpnr-0004-time-iologic-fabric-ports.patch), verified on the loopback report; nominal delays |
| 17 | YosysHQ/yosys + openXC7/nextpnr, and UberDDR3 (docs) | `!clk` on an OSERDESE2 CLK or into an OBUFDS becomes a fabric LUT, not the I/O tile's clock inverter (`IS_CLK_INVERTED` / `ZINV_CLK`); UberDDR3's no-ODELAY PHY path uses exactly that for DQS and CK, so CK/DQS land 0.6-1.2 ns late by placement | **fix proven on silicon** in designs/uberddr3/prebuild.py (4 of 4 builds pass); an inverter-absorption pass belongs in yosys or the nextpnr packer |
| 19 | openXC7/nextpnr (xilinx packer) | only one IDELAYCTRL cell per design ("Found more than one IDELAYCTRL cell!"): two DDR3 PHYs that each instantiate one cannot be combined | worked around (one IDELAYCTRL in our top level); low priority, but surprising for multi-interface designs |
| 13 | regymm/pcie_7x (docs) | on dino's Intel C602 / Xeon E5 v2 host a hot-added endpoint (rescan) enumerates but MMIO never reaches it; boot-time enumeration works. Also the YPCB XDC uses LVCMOS33 on 1.8 V banks and does not handle AC24 | worth a README note and an XDC fix |

## 1. demo-projects: AC24 in blinky-ypcb003381p1

**Evidence (2026-10-05).** Our openXC7 blinky (equivalent to the demo) leaves AC24 at the default
pull-down; every load ends with DONE low and the FPGA unconfigured. The same design with AC24 driven
high ([designs/blinky-ac24drive](designs/blinky-ac24drive/)) or pulled up through FASM reaches DONE
every time and blinks. Root cause and independent confirmations are in the
[card README](board/ypcb-00338-1p1/README.md#ac24-the-board-reset-pin).

**Draft change.** In `blinky-ypcb003381p1/top.v` add an output `ac24` tied to `1'b1`. In
`ypcb003381p1.xdc` add:

```text
# AC24 drives a board reset: if it is left at the default pull-down, the card resets the moment
# startup releases the I/O and DONE never goes high. Keep it high.
set_property PACKAGE_PIN AC24 [get_ports ac24]
set_property IOSTANDARD LVCMOS18 [get_ports ac24]
```

Also note in the README that the part is `-2` (`PART = xc7k480tffg1156-1` in the Makefile; the
cards are `-2FFG1156`; it does not change the bitstream).

## 2. prjxray: LIOB33_SING_X0Y199

**Evidence.** Adding `LIOB33_SING_X0Y199.IOB_Y0.PULLTYPE.NONE` to a FASM file and running fasm2frames
for `xc7k480tffg1156-2` prints `frame_clear: invalid word address 101 in line: …` and
`frame_set: invalid word address 101`. The SING tile at the top of the column (offset 99, 2 words) maps
its IOB_Y0 bits to word 101, past the end of the 101-word frame (0–100). The bottom SING tile
(`LIOB33_SING_X0Y150`, offset 0) works: we verified its PULLTYPE bits on hardware.

**To do before filing.** A two-line FASM repro; check prjxray-db's `tilegrid.json` entry and
`segbits_liob33.db` for the SING tiles (do SING tiles need their own segbits file, as `ppips_liob33_sing.db`
suggests?); check whether this is the same as zollij's "pull settings dropped on eight SING tiles".

## 3. TiferKing: document AC24

Issue #3 already identifies AC24. Offer a README paragraph and an XDC comment (as in 1), plus the
correction that SYS_CLK on AA28 is 50 MHz (the spreadsheet says 50M; the original XDC implies 100).

## 4. openFPGALoader

Re-test now that AC24 no longer confounds things:
- `--read-register STAT` on a configured card; compare with xsdb's IR capture.
- `--read-xadc` on Kintex-7: feature request (the XADC DRP over JTAG is the same on all 7-series).
- `--dump-flash` on BPI: ~2.6 KB/s because every word is a USB round trip; batching the reads
  (queue many before reading back) would bring it near Vivado's ~1 MB/s.

## 5. nextpnr: RAMB36 x9 parity reaches only one half

**Evidence (2026-10-05, [designs/bram](designs/bram/)).** A 4096×36 memory that yosys maps to four
RAMB36E1 in 4K×9 mode (WRITE_WIDTH_A=9) reads its initial contents correctly but, after writes, every
other word reads back with bit 8 of each 9-bit lane wrong (first failure: address 1, `a1fdf040` read,
`a5fff040` written: bits 17 and 26). A 1024×18 RAMB18 in the same design is clean. 260,000 write/read
passes at 200 MHz with zero errors after the fix.

**Cause.** In the x9 modes each RAMB18 half stores four data bits and its own copy of the parity bit,
from its own parity pin (DIPADIP0 for one half, DIPADIP1 for the other). The logical RAMB36E1 only
drives DIPADIP[0]; the packer already mirrors DIADI0 onto DIADI1 for the x1 modes but does nothing for
the parity bit at x9, so one half's parity pin is unconnected.

**Fix.** [nextpnr-0001-ramb36-x9-parity-both-halves.patch](upstream/nextpnr-0001-ramb36-x9-parity-both-halves.patch)
against openXC7/nextpnr `c68c1358`: in `pack_bram`, for WRITE_WIDTH_A/B == 9 connect DIPxDIP1 to
DIPxDIP0's net. The full width matrix ([designs/bramwidths](designs/bramwidths/)) is the regression
test to attach: stock nextpnr fails 36K 4096x9 and 36K 512x72; with patches 0001 and 0002 all 13 modes
pass.

## 9. nextpnr: RAMB36 72-bit SDP write width

**Evidence (2026-10-05, [designs/bramwidths](designs/bramwidths/)).** A 512×72 memory (RAMB36E1,
READ_WIDTH_A=72, WRITE_WIDTH_B=72, SDP) reads its initial contents correctly, but almost every word
written reads back wrong. The FASM has `SDP_WRITE_WIDTH_36` and `WRITE_WIDTH_B_18` on both RAMB18
halves but no `WRITE_WIDTH_A_18`; the working 18K 512×36 SDP memory has both. Adding
`WRITE_WIDTH_A_18` to both halves by hand makes it pass.

**Cause.** `write_bram_width` widens the unset opposite-side width for SDP, as Vivado does: it handles
RAMB18 READ 36, RAMB36 READ 72 and RAMB18 WRITE 36, but `a_side_writes_half_the_word` is
`(!is_36 && write_width_b == 36)`, so the RAMB36 WRITE 72 case never fires.

**Fix.** [nextpnr-0002-ramb36-sdp72-write-width-a.patch](upstream/nextpnr-0002-ramb36-sdp72-write-width-a.patch):
`is_36 ? (write_width_b == 72) : (write_width_b == 36)`, the twin of the read-side line.

## 14. nextpnr: OSERDES T1 tied low comes out tri-stated

**Evidence (2026-10-05, [designs/serdes-loop](designs/serdes-loop/)).** An OSERDESE2 drives a pad through
an IOBUF whose T comes from TQ, with `T1(1'b0)` ("always drive"). Boundary scan shows the OSERDES
output toggling and the pad tri-stated. The FASM has `INT_L_X0Y298.IMUX_L15.VCC_WIRE` (T1 routed from
the only constant an IMUX can select) together with `OLOGIC_Y0.ZINV_T1` (site inverter off), so
T1 = 1. Removing that one bit by hand drives the pad, and the loopback then captures data.

**Cause.** `pack_constants` turns a GND tie on an invertible pin into the VCC net plus `IS_T1_INVERTED`.
The cell writer honours that, but the pseudo-pip rule for "OLOGIC TQ drives the pad's tristate"
(added for an SD-card design whose T1 is a real signal) writes `ZINV_T1` unconditionally.

**Fix.** [nextpnr-0003-ologic-t1-tied-low-keeps-inverter.patch](upstream/nextpnr-0003-ologic-t1-tied-low-keeps-inverter.patch)
(applies after 0001 and 0002): the cell writer records OSERDES sites whose T1 is tied low (GND net, or
VCC net with `IS_T1_INVERTED`) and the pseudo-pip rule skips `ZINV_T1` for them; the cell writer also
treats a GND net as tied low. Verified: the loopback drives and captures with no FASM edits, and the
LiteX DDR3 build's FASM is unchanged.

## 16. nextpnr: IOLOGIC fabric ports are not timed

**Evidence (2026-10-05, [designs/uberddr3](designs/uberddr3/)).** The xilinx uarch has timing data only
for LUTs, flip-flops, F7/F8 muxes, CARRY4 and block RAM. Every other cell has no timing index, so
`get_port_timing_class_default` returns `TMG_IGNORE` for all its ports. Paths from the fabric into
OSERDESE2 D/T/OCE/RST, out of ISERDESE2 Q, and to IDELAYE2/ODELAYE2 CNTVALUEIN/LD/CE are neither
reported nor placed timing-driven. With the fix, the loopback design's report gains a path starting
at the IDELAY's CNTVALUEOUT; without it there is none.

**Fix (draft).** [nextpnr-0004-time-iologic-fabric-ports.patch](upstream/nextpnr-0004-time-iologic-fabric-ports.patch):
the uarch overrides `getPortTimingClass`/`getPortClockingInfo` and models those ports as registers on
CLKDIV (SERDES) or C (delays), with nominal DS182-scale setup 0.5 ns, hold 0.1 ns, clock-to-out
0.6 ns. Real values should come from the prjxray timing database once it covers IOLOGIC. Built and
used only as a test binary so far; not yet the default.

## 17. `!clk` on I/O clock pins becomes a fabric LUT

**Evidence (2026-10-05, [designs/uberddr3](designs/uberddr3/)).** Without ODELAY (HR banks), UberDDR3's
PHY drives the DDR3 CK output as `OBUFDS .I(!i_ddr3_clk)` and clocks the DQS OSERDESE2 with
`.CLK(!i_ddr3_clk)`. yosys maps the inversion to a LUT; nextpnr routes the clock through it. The
SDF shows that clock reaching the DQS OSERDES at 1573 ps in a passing build and 2095 ps in a failing
one; every global-clock SERDES clock arrives at 914 ps. Failing builds read the calibration pattern
back one beat late and reset forever.

**Fix (ours, in prebuild.py).** `IS_CLK_INVERTED(1'b1)` on the DQS OSERDES (nextpnr already writes
`ZINV_CLK` from it) and CK from an ODDR (D1=0, D2=1) on the plain clock. On the card: 4 of 4 seeds
calibrate first time and pass the burst test; the calibration results are identical across builds.

**Upstream.** yosys (or the nextpnr packer) could absorb a `$not` on OSERDESE2/ISERDESE2/ODDR clock
pins into the cell's inversion parameter, as Vivado does. UberDDR3 could use `IS_CLK_INVERTED` and
an ODDR-generated CK so it does not depend on that. Both drafts only; nothing filed.

## 18. PHASER_OUT/PHASER_IN are undocumented (write leveling on HR banks)

**Evidence (2026-10-06).** On HR banks (no ODELAY) MIG levels writes with PHASER_OUT fine/coarse taps
([AMD 35094](https://adaptivesupport.amd.com/s/article/35094?language=en_US)). In prjxray the PHASER sites
appear only as routing pass-throughs (ppips); no configuration bits exist. UberDDR3 skips write
leveling there, and [someone755/ddr3-controller](https://github.com/someone755/ddr3-controller) limits
itself to one chip for the same reason. On this card a single CK phase offset (+60°) is enough at
DDR3-667 for all nine fly-by chips, so this is not blocking.

**What it would take.** Fuzzers for PHASER_REF, PHASER_OUT_PHY, PHASER_IN_PHY, PHY_CONTROL and
IN/OUT_FIFO, run with a Vivado that can implement designs (WebPACK on a smaller 7-series part; the tile
types repeat), plus nextpnr placement and routing for the dedicated phaser clock paths. Our MIG
bitstream for this card is a reference to check against. Weeks of work; only worth it for DDR3-800+.
