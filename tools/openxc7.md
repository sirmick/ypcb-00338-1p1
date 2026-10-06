# Working with openXC7 (the open 7-series toolchain)

We build every bitstream for the [YPCB-00338-1P1](../board/ypcb-00338-1p1/README.md) with open tools
and send fixes upstream as we find bugs ([UPSTREAM.md](../UPSTREAM.md)). This page covers what is
installed, how a build flows, how to debug a bitstream, and what is known to be broken. Vivado cannot
build for the XC7K480T without a paid licence; Vivado Lab Edition (free) is only used for flash
readback and the system monitor.

## What is installed (dino, copied to buzz)

From [openXC7/toolchain-installer](https://github.com/openXC7/toolchain-installer) (built from
source, installer `0cb3156`, 2026-09-30) into `~/opt/openxc7`; `source ~/opt/openxc7/export.sh` puts
it on PATH. Checkouts are in `~/src/openxc7-toolchain-installer/`. The same tree is rsynced to
[buzz](../hosts/README.md), where builds run (much faster single-thread).

| Component | Version | Upstream (where bugs go) |
|---|---|---|
| yosys | 0.69 (`9f75ca1f9`, 2026-09-09) | [YosysHQ/yosys](https://github.com/YosysHQ/yosys) |
| nextpnr-xilinx (himbaechel) | `c68c1358` (1.0.0-29, 2026-09-30) | [openXC7/nextpnr](https://github.com/openXC7/nextpnr) |
| prjxray (fasm2frames, bitread, bit2fasm, xc7frames2bit) | `ed3331c6` (2026-09-19) | [openXC7/prjxray](https://github.com/openXC7/prjxray) |
| prjxray-db | `517d66a` (2026-09-26) | [openXC7/prjxray-db](https://github.com/openXC7/prjxray-db) |
| fpga-assembler (fpga-as) | `cf0e3f0` | [hansfbaier/fpga-assembler](https://github.com/hansfbaier/fpga-assembler) |
| openFPGALoader | 1.1.1 (oss-cad-suite 2026-09-17, `/opt/oss-cad-suite`) | [trabucayre/openFPGALoader](https://github.com/trabucayre/openFPGALoader) |

The kintex7 database is `~/opt/openxc7/share/nextpnr/prjxray-db/kintex7/`: `xc7k480t/` (fabric:
tilegrid, tileconn, node wires), `xc7k480tffg1156-2/` (package pins, part.yaml), `segbits_*.db` (the
bit meaning of every feature), `ppips_*`, `mask_*`.

## The build flow

`fpga build designs/<name>` runs [`xc7-build.sh`](xc7-build.sh) (locally if the toolchain is installed, else on the card host):

```text
*.v ──yosys synth_xilinx──▶ .json ──nextpnr-xilinx──▶ .fasm ──+ board.fasm──▶ .board.fasm
    ──fasm2frames──▶ .frames ──xc7frames2bit──▶ .bit
```

- **chipdb** (once, ~2 min, 63 MB): `xilinx_gen.py --xray <db>/kintex7 --device xc7k480t` then
  `bbasm`. Pass the **fabric** name (`xc7k480t`), not the package; the package comes from the XDC.
  Kept at `~/fpga/chipdb/xc7k480tffg1156.bin`.
- **Clock**: SYS_CLK is 50 MHz; `--freq 50` sets the timing target (override with `FREQ=`).
- **Board FASM**: [`board.fasm`](../board/ypcb-00338-1p1/board.fasm) is appended to every design's
  FASM. Today it holds the AC24 pull-up, without which nothing reaches DONE. Put board-wide pin
  defaults there rather than in each design.
- **XDC**: nextpnr-xilinx reads `LOC`/`PACKAGE_PIN`, `IOSTANDARD`, `SLEW` and `IN_TERM`. It ignores
  `INTERNAL_VREF` ([openXC7/nextpnr#20](https://github.com/openXC7/nextpnr/issues/20)) but sets internal
  VREF 0.675 V on every bank with SSTL15 inputs by itself, and on this card the DDR3 banks **need** an
  internal VREF (without one, DDR3 never calibrates; 0.75 V works the same). Check the FASM for anything
  else that matters. Multi-port lists like `get_ports {a b}` crash the parser: one port per line.
- **Unused pins** are PULLDOWN in an openXC7 bitstream: that is the all-zero frame state, and
  nothing in the flow changes it. Vivado's `UNUSEDPIN` setting has no equivalent; add FASM lines.
- Build products land in `designs/<name>/build/` (or `$BUILD`, git-ignored).
- **Patched nextpnr (the default)**: [`build-nextpnr.sh`](build-nextpnr.sh) builds openXC7/nextpnr
  `c68c1358` plus [upstream/](../upstream/) patches 0001–0003 (BRAM x9 parity, BRAM 72-bit SDP, OSERDES
  T1 tied low; all proven on silicon) into `~/fpga/nextpnr-dev-bin`, and `xc7-build.sh` uses it when
  present. Without them the BRAM widths and any OSERDES "always drive" output are broken.
  `NEXTPNR_IOL=1` adds draft patch 0004 (timing for IOLOGIC fabric ports). `TOOL_PATH=` (empty) builds
  with the stock nextpnr.
- **Memory initial contents**: use `$readmemh` with files written by the design's `prebuild.py`.
  An initial `for` loop over a large memory takes yosys tens of minutes to evaluate (7 s with
  `$readmemh`). Give the file-name parameter a non-empty default: yosys runs `$readmemh` with the default while
  parsing, so an empty one fails ("Can not open file ``", [YosysHQ/yosys#2966](https://github.com/YosysHQ/yosys/issues/2966); `read_verilog -defer` also avoids it).
- **Clocks**: feed MMCM/PLL `CLKIN1` straight from the clock-capable pin, not from a BUFG that also
  drives fabric; the latter made nextpnr report hold violations from a 0.85 ns skew estimate.
- **Timing**: nextpnr's reported Fmax is a conservative floor on this part, not a prediction: a
  design it rates at 235 MHz runs correctly at 400 MHz and fails at 450 (designs/timing). Targeting
  above it needs `NEXTPNR_ARGS=--timing-allow-fail` and a silicon check (sweep with `TIMING_MHZ=`).
- **Checking a design**: put a `check.py` beside it that reads its USER registers with
  [`tools/jtagdr.py`](jtagdr.py); `fpga check designs/<name>` runs it.

## Starting a new design

1. `mkdir designs/<name>`; put the Verilog there (`*.v`, top module named like the directory, or pass
   the top as a second argument: `fpga build designs/<name> <top>`) and an XDC with one
   `set_property` per port: `PACKAGE_PIN` and `IOSTANDARD` (LVCMOS18 for the user pins in banks 14/15,
   SSTL15 / DIFF_SSTL15 for DDR3). Copy pins from [designs/blinky](../designs/blinky/) or the card page.
2. Clock from `SYS_CLK` (AA28, 50 MHz) straight into an MMCM or PLL; use BUFGs, not BUFR/BUFH (they do
   not work in openXC7 yet).
3. Add a JTAG register early: a BSCANE2 (USER1) shifting out a snapshot of status, as in
   [designs/hello](../designs/hello/); a second BSCANE2 does not route, so multiplex one register.
   Write a `check.py` that reads it with [`jtagdr.py`](jtagdr.py) and prints PASS/FAIL.
4. `fpga build designs/<name>`, `fpga load designs/<name>/build/<name>.bit`, `fpga check designs/<name>`.
5. Generated sources: a `prebuild.py` in the design directory runs first, with the build directory as
   its argument; files it writes as `gen_*.v` / `gen_*.xdc` are used (see designs/uberddr3).

Lessons that cost us days:

- **Read yosys's warnings.** "Identifier ... is implicitly declared" or "used but has no driver" is
  almost always a real bug (a misspelled or renamed net becomes a floating wire). `default_nettype
  none` is not enforced across files.
- **Clock inversion belongs in the I/O tile.** `!clk` on an OSERDES/ODDR clock or into an OBUFDS
  becomes a fabric LUT, and the clock then arrives 0.6–1.2 ns late depending on placement. Use
  `IS_CLK_INVERTED` on the OSERDES, or generate the inverted clock with an ODDR (D1=0, D2=1).
- **nextpnr does not time paths to or from SERDES and delay cells** (patch 0004 is a draft fix), and it
  reports but does not repair hold. Register signals on both sides of those primitives.
- **Pipeline wide buses.** A 576-bit compare in one cycle crossed the die and failed timing; registering
  the data where it arrives and reducing it before it moves fixed it and cut routing time.
- **One IDELAYCTRL per design**: nextpnr copies it to every bank with IDELAYs and rejects a second one.
- Treat a build that fails in only some placements as a bug to find, not a seed to change: the two we
  chased were a fabric clock inverter and (still open) one DDR3 lane in about one placement in four.

## Reading and checking a bitstream

| Want | Tool |
|---|---|
| Features set by a .bit (works on Vivado bitstreams too) | `python3 ~/opt/openxc7/venv/lib/python3.12/site-packages/utils/bit2fasm.py --db-root <db>/kintex7 --part xc7k480tffg1156-2 --bitread $(which bitread) x.bit` (`--verbose` also lists bits the db cannot name; 2 min for a full Vivado design) |
| Frames with addresses | `bitread -part_file <db>/kintex7/xc7k480tffg1156-2/part.yaml -z -o x.frm x.bit` |
| Configuration packets (COR0, CTL0/1, CRC, START...) | [`tools/bitstream/packets.py`](bitstream/packets.py) `x.bit` |
| A frame address → tiles | `tilegrid.json`: tiles whose `bits.CLB_IO_CLK.baseaddr` = FAR & ~0x7f; minor = FAR & 0x7f; word = tile offset + n |
| A bit → feature | `segbits_<tiletype>.db`: `minor_bit`, where bit = (word − tile offset) × 32 + bit |
| Only the non-zero frames (small, fast to load) | [`tools/bitstream/sparsebit.py`](bitstream/sparsebit.py) `x.bit x.frames out.bin` |

Comparing our bitstream with a Vivado one, tile by tile, is the fastest way to find a bad or missing
feature: decode both with bit2fasm and `comm` the sorted lines for the tiles involved.

## Debugging a design that will not start

1. `fpga load` then `fpga status`. "INIT=1 DONE=0" with no CRC error means configuration was fine
   and startup failed.
2. If a different bitstream does start, bisect the difference on the hardware:
   [`tools/bitstream/start-bisect.py`](bitstream/start-bisect.py) (`empty`, `prefix`, `window`,
   `bits`). It found AC24 in ~40 loads.
3. A boundary-scan pattern of every pin tri-stated (`fpga pins`: "TRI-STATED") means the FPGA is not
   configured; a load that "succeeded" may have been undone.
4. Trust xsdb's IR capture over openFPGALoader's STAT read.

## Known problems (openXC7 on the XC7K480T)

Check [UPSTREAM.md](../UPSTREAM.md) for what we have reported.

| Problem | Effect | Workaround | Status |
|---|---|---|---|
| fasm2frames on `LIOB33_SING_X0Y199` IOB_Y0 | "invalid word address 101": the top single IOB of the bank gets no bits | avoid setting features on it | seen by us; not yet reported |
| prjxray drops pull settings on eight SING tiles of the 480T (not AC24) | wrong pull on those pins | check with bit2fasm | reported by [zollij](https://gist.github.com/zollij/bf49d29b0ab79d38346dda2a0e32b5b9) |
| `INTERNAL_VREF` ignored (xdc.cc) | DDR3 input VREF wrong | patch FASM | [openXC7/nextpnr#20](https://github.com/openXC7/nextpnr/issues/20) |
| `IOI_OCLKM_0.IOI_IMUX31_1` pseudo-pip emitted as a feature | fasm2frames aborts | strip the line | [openXC7/nextpnr#20](https://github.com/openXC7/nextpnr/issues/20) |
| Router occasionally exits with code 2 | random build failure | rerun with another seed | [openXC7/nextpnr#20](https://github.com/openXC7/nextpnr/issues/20) |
| CARRY4 lane with O and CO both used in fabric misbehaved in silicon | wrong arithmetic | none needed: fixed upstream (Aug 2026); our build passes the shape at 400 MHz (designs/timing) | [openXC7/nextpnr-xilinx#134](https://github.com/openXC7/nextpnr-xilinx/issues/134) (closed) |
| yosys 0.67 `xilinx_srl` ignores clock enable | enable-gated shift registers shift every clock | none needed: yosys 0.69 wires CE (netlist checked) and CE-gated SRLs pass at 400 MHz | [YosysHQ/yosys#6058](https://github.com/YosysHQ/yosys/issues/6058) (fixed in #6059) |
| RAMB36 x9 parity reaches one half only (packer) | every other word loses its 9th bits | our patch 0001 (default in builds) | [UPSTREAM 5](../UPSTREAM.md) |
| RAMB36 72-bit SDP write width missing (FASM) | 72-bit SDP writes corrupt | our patch 0002 (default in builds) | [UPSTREAM 9](../UPSTREAM.md) |
| BUFR on Kintex-7: no site metadata, no IN_USE/DIVIDE bits; MMCM→BUFR unroutable | BUFR unusable | use BUFG | [UPSTREAM 6](../UPSTREAM.md) |
| BUFH net fails to route | BUFH unusable | use BUFG | [UPSTREAM 7](../UPSTREAM.md) |
| BRAM DI hold violations reported, fine on silicon; no hold fixing | builds fail at 200 MHz | `NEXTPNR_ARGS=--timing-allow-fail`, then prove on hardware | [UPSTREAM 8](../UPSTREAM.md) |
| yosys: a module parameter named `INIT` arrives empty in `$readmemh` | synth error | name it something else (`INIT_FILE`) | [UPSTREAM 10](../UPSTREAM.md) |
| 342 HCLK_CMT, 78+78 DSP and others: pips with no bits in the db | MMCM/PLL clock routing and DSP routing may not be expressible | none yet | to prove: see the card README checklist |
| 3,938 bits in a Vivado design that prjxray cannot name (CMT, sysmon, CLB, IOI) | features we cannot generate | none | to investigate when we need them |

## Still to prove on this card

Tracked as gates in [ROADMAP.md](../ROADMAP.md): MMCM/PLL, BRAM, DSP, timing, PCIe, DDR3.

## References

- [prjxray documentation](https://f4pga.readthedocs.io/projects/prjxray/en/latest/): the bitstream
  format, FASM, tile and segbit databases.
- [UG470](../datasheets/README.md) chapter 5 (configuration details: packets, registers, STAT, COR0,
  eFUSE) and chapter 10 (JTAG instructions).
- [openXC7 demo-projects](https://github.com/openXC7/demo-projects), including
  `blinky-ypcb003381p1`.
