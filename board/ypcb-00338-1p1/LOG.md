# YPCB-00338-1P1 bring-up log

What we did and learned, newest first. Current facts live in [README.md](README.md); this page keeps
the path, including conclusions that turned out wrong, so nobody re-derives them.

## 2026-10-06 (evening): the card's DMA through VFIO

- designs/soc-s4: PcieLink on the hard block (Gen1 x1, 62.5 MHz user clock) with 64 KiB of block RAM
  as guest memory. cardd gained a VFIO module (raw ioctls, no new dependencies) and examples/s4.rs.
  The card enumerates as 10ee:0484 with a 64 KiB BAR0; it is alone in IOMMU group 41.
- First build: timing failed at 44 MHz. The TLP header builder added a 64-bit address where the line
  address only needs its DW index ORed in, and CardLink's line arithmetic ran straight into it. A
  register stage and the OR: 88 MHz.
- First run: the magic number read back byte-reversed (`0x43415244`). Payload DWs on the hard block's
  stream are in PCIe byte order, like headers; PcieLink and its TLP model assumed little-endian.
- Second run: copies completed, records arrived, but data was wrong in a few places, and a restarted
  test was refused (the card kept the last session's sequence numbers; ENABLE 0 then 1 now resets
  them). Two kinds of wrong data: single bits set (0→1 only) at nearly repeatable guest addresses, and
  whole reads returning stale staging. The kernel log had IOMMU faults at `0x4000100000000`, staging's
  address with bit 50 set.
- Diagnosis: constant fills read back perfectly (4 MB); random data failed only at guest bytes 0x11
  and 0x18, and varied between reads, so the error was on the way out. In the first MemWr of a burst
  those bytes sit on `s_axis_tx_tdata` bits 18 and 25, and the fault address had header bit 50 = DW2
  bit 18. nextpnr has no timing model for the hard block's pins, and the TX data came straight from a
  16:1 DW mux. With registers on both stream boundaries: zero errors, no faults.
- Result: 64 KiB round trip and 2,000 random copies pass. BAR0 read 1.6 µs median; a 4-byte copy round
  trip 7.9 µs (to host) and 9.9 µs (from host); 64 KiB copies 163 MB/s to host, 30 MB/s from host.

## 2026-10-06 (afternoon): the host link over PCIe TLPs

- regymm's pcie_7x bridge is completer-only (it answers BAR accesses); the card's DMA needs a requester.
  PcieLink (SpinalHDL) talks to the PCIE_2_1 hard block's 64-bit AXI stream directly: BAR0 MemWr/MemRd
  to RegBus (CplD back, fields checked against regymm's bridge), MemWr for each 64-byte line covering
  just its masked bytes, MemRd plus CplD for COPY_FROM_HOST, 3- and 4-DW headers, packet-level
  arbitration, no DMA before Bus Master Enable.
- A TLP-level host model replays blk_read through it below and above 4 GiB. A mutant sending a full
  first-DW byte enable first survived (neighbouring bytes were all zero); the test now fills the
  neighbours, and catches it.

## 2026-10-06 (small hours): cardd serves a virtio-blk read against the RTL

- S3, parts 2 and 3: cardd's phase-1 backend (GuestMem through copy commands, the split-queue walker,
  virtio-blk IN/OUT/FLUSH/GET_ID) serves blk_read against a Rust model of the card; hostile chains
  (loops, buffers in main memory, INDIRECT, readable after writable, next outside the queue, an
  available index running ahead) are refused without a panic. Randomised input: 100,000 rings and
  requests, 100,000 messages, no panic, nothing outside the windows touched.
- Co-simulation: cardd/examples/cosim.rs (the real Link, CopyMem, queue and blk code) talks to the
  SpinalSim bench over a pipe, one line per BAR or host-memory access; it serves the read against the
  RTL in 322 requests and 628 cycles, and guest memory ends as the transcript says.

## 2026-10-06 (later that night): copies and used-ring pushes in RTL

- S3, part 1: the command processor executes COPY_TO_HOST, COPY_FROM_HOST and USED_PUSH as state
  machines (64-byte lines, byte masks, window checks against channel B and the ring region).
  SpinalSim: the blk_read transcript (a full virtio-blk read served by copies) passes with random
  back-pressure on every interface; hostile copies (outside the windows, across their ends, wrapping
  past 2^64, zero length, beyond staging, misaligned) are refused and move nothing; partial lines keep
  their neighbours; the used ring wraps and an entry spilling across a line lands whole. Disabling the
  window check fails two tests. 23 RTL tests.
- A used-ring entry is 4-byte aligned (used + 4 + 8k) and can straddle a 64-byte line: the push writes
  twice then. The card requires the used ring itself to be 8-byte aligned.

## 2026-10-06 (night): the virtio-mmio shim and the mailbox

- S2: 8 virtio-mmio v2 shims and the BAR0 mailbox in SpinalHDL, replaying the blk_init transcript in
  SpinalSim: every record's bytes and inbox address match; BAR0 outside the mailbox reads 0; a
  command with bad sequence numbers is refused and acknowledged; a two-entry inbox is never overrun.
  Two deliberate bugs (no FEATURES record; wrong inbox stride) each fail the transcript test.
- The protocol gained an acknowledgement in the transcript: the card sends CMD_ACK after each command,
  so the host knows which ring entries are free.
- cardd (Rust, no dependencies) replays the same transcript from the host side.

## 2026-10-06 (late evening): the host-link contract

- S1: one Scala contract (BAR0, 64-byte records and commands, virtio-mmio registers, the guest map) generates
  cardd's Rust constants, a device-tree fragment (dtc-checked), a table and transcripts. SpinalHDL 1.12.2's
  message encoder and cardd agree byte for byte; a source edit trips the freshness test until regenerated.
- Scala's stripMargin also strips interpolated lines that start with `|` (it ate the Markdown tables).

## 2026-10-06 (evening): a 64-bit core with an FPU runs on the card

- SoC roadmap started ([SOC-ROADMAP.md](../../SOC-ROADMAP.md)): Debian on four VexiiRiscv cores, virtio
  over PCIe through a userland backend. Toolchain on buzz: JDK 21, Verilator, sbt via coursier, RISC-V
  cross compilers, dtc, mmdebstrap.
- S0: VexiiRiscv's MicroSoc with LiteX's "debian" core options: 16.5k LUTs, routes in 2 min, nextpnr
  Fmax 118.7 MHz. Its 227 LUTRAM primitives place fine. A bare-metal self-test (integer, M, A, FMA,
  square root, single and double precision) passes on the card: LED0 at 1.99 Hz, timed with rdcycle
  at 50 MHz.

## 2026-10-06 (morning): cold start, gate 9 done

- Clean poweroff of dino, BMC reported off within 6 s, 60 s off, `bmc on` over LAN: ssh back in 2 min 15 s.
  The card booted its factory flash image (59 C afterwards).
- Dual build (seed 6) loaded over JTAG: both channels calibrate first time; 2 GiB each, 0 errors,
  5.04 GB/s each way. Gate 9's proof is complete at DDR3-667.

## 2026-10-06 (morning): both channels in one bitstream

- UBER_DUAL=1: a second controller instance on channel 1's pins, sharing the PLL clocks. nextpnr allows one
  IDELAYCTRL per design (it copies it to every bank with delays), so it moved from UberDDR3's PHY copy into
  the top level, its ready signal fed to each PHY. One USER1 register serves both channels (a second BSCAN
  does not route): a control bit selects the channel shown and tested.
- First dual builds: channel 1 never left read training. My generator had renamed `(ddr3_clk)` to an
  undeclared `ddr3b_clk` with the pins; yosys only warned. prebuild.py now checks channel 1's connections.
- The unpipelined memory-test compare failed timing in the dual build (82.67 MHz against 83.33; the path
  had 10.75 ns of routing across the die). Pipelined (capture, XOR, fold, count; checked in simulation
  with an injected bit error): 97.6 MHz. Most routing congestion is the two controllers themselves.
- Debug register fix: status word 1 was 31 bits wide, so the cycle counter and flags above it read one
  bit low. The early "41.7 MHz controller clock" was this, not host timing: it reads 83.35 MHz now.
- Dual seed 6: both channels calibrate first time; 2 GiB each, 0 errors, 5.04 GB/s each. Seed 5 never
  converged in routing (139 passes, 3 h).

## 2026-10-06 (night): channel 0 memory test, and a Vivado reference

- Channel 0 seeds 5-8 with memtest.v all calibrate (3 failures in 11 placements so far). Seeds 5 and 6:
  2 GiB written and read twice, 0 errors, 5.04 GB/s each way; re-read after 120 s, 0 errors. A re-read
  with the wrong seed reports every word and every DQ line: the test detects errors.
- TiferKing's YPCB_00338_1P1_software.zip: a Vivado MicroBlaze design whose MIG runs both channels at
  DDR3-1066 with ECC. Decoded with bit2fasm: no internal VREF on any bank (external VREF), and its DQ
  inputs differ from ours: IBUF_LOW_PWR=FALSE (`ZIBUF_LOW_PWR`), IN_TERM UNTUNED_SPLIT_50 (we use 40), and
  IBUFDISABLE/INTERMDISABLE driven (input and termination on only while reading). Candidates for more
  read margin, and perhaps for why our builds need internal VREF; not yet tried.

## 2026-10-06 (later): channel 1, and a memory test

- Channel 1 (banks 16/17/18, pins from MEMORY_CH1.ucf via UBER_CHANNEL=1): CK 0 retries (lanes 2, 3, 8),
  +30 / +60 / +90 clean, +120 fails. The same window as channel 0.
- memtest.v on the user Wishbone port, controlled over USER1 (`fpga memtest`): channel 1, 2^25 words
  (2 GiB data, 2.25 GiB with ECC), two seeds: 0 errors, 426 ms per pass, 5.04 GB/s each way.
- Channel 0 with memtest.v: seed 1, 2 fail and seeds 3, 4 pass (default nextpnr); with patch 0004, seed 3
  fails and 1, 2, 4 pass. Deterministic per build (reloads agree; older passing builds still pass, card at
  45 C). Failing builds: one lane (4, or 0/2/3) never passes the write/read-back check; its read-back is
  not a shifted pattern, as if never written. Identical between failing and passing builds: every
  I/O-tile bit (lane 4's tiles including routing), every SERDES/IDELAY/ODDR clock (global, same delay),
  no reported hold violations, setup slack ~6 ns with SERDES ports timed. Not yet explained.

## 2026-10-06: channel 0 at full width

- 9 byte lanes (DQ 0-71 with the ECC lane, DQS 0-8; the ECC lane's pins from MEMORY_CH0.ucf). The board
  has no DM pins. Lanes 0-3 are in bank 11, 4-6 and 8 in bank 13, 7 in bank 12 with address/command.
- Calibrated and passed the burst test, but only after retries: lane 8 (and 7, sometimes 0 or 5) failed
  the write/read-back check with the one-beat shift. UberDDR3 skips write leveling without ODELAY, so
  nothing adjusts DQS to each chip's CK arrival on the fly-by route.
- VREF: no internal VREF (the board's external reference) never calibrates; internal 0.75 V and 0.675 V
  behave the same. The banks need internal VREF; nextpnr's 0.675 V works.
- CK phase sweep (CK on its own PLL output, seed 1, two loads each): -60 fails, -30 retries (lane 4),
  0 retries (lanes 7, 8), +30 / +60 / +90 clean, +120 fails in DQS training, +150 fails. Default +60.
- +60 on seeds 1-4, 8 loads: calibrates first time every time, burst test 0 errors.
- PHASER_OUT (MIG's write leveling on HR banks) is undocumented in prjxray: sites appear only as routing.
  Not needed at DDR3-667; a candidate project if we need higher rates.

## 2026-10-05 (small hours): UberDDR3 passes on every build

- Cause of the failing builds, from nextpnr's SDF: the no-ODELAY PHY clocks DQS and the CK output
  from `!i_ddr3_clk`, built as a fabric LUT. That clock reached the DQS OSERDES at 2095 ps (failing)
  or 1573 ps (passing); all other SERDES clocks at 914 ps. Not the SERDES reset: its spread was larger
  in the passing build (2.3 ns against 1.5 ns), and well inside the 12 ns CLKDIV period.
- Fix in prebuild.py: `IS_CLK_INVERTED` on the DQS OSERDES, CK from an ODDR. Seeds 1-4: calibrate first
  time, burst test 33,554,431 correct reads and 0 wrong each, identical calibration (idelay DQ 3 / DQS 12,
  previously 24 / 1).
- Builds are reproducible: the same seed and sources give the same FASM (only net-name comments differ).

## 2026-10-05 (late night): why some UberDDR3 builds fail

- With error capture and a calibration snapshot over USER1 (designs/uberddr3/check.py): failing
  builds never reach the burst test. Read-after-write during calibration returns the pattern shifted
  by exactly one beat (half a DDR clock): written `91 77 29 8c d0 ad 51 c1`, read
  `77 29 8c d0 ad 51 c1 80`. UberDDR3 searches only whole-clock shifts, so it resets forever (13,338
  resets seen). The passing build is one beat off the other way, which its write shift corrects.
- All builds settle on the same delays (idelay DQ 24 / DQS 1, odelay DQ 1 / DQS 10); the DQS
  pattern position (`dq_target_index`) differs: 19 passing, 33/35 failing. The serialisers' word
  boundary differs between builds.
- Ruled out: global and I/O-tile clock routing (identical in passing and failing builds; only the
  horizontal clock track numbers change); the ISERDES CLKB inversion (same in all builds); untimed
  fabric-to-SERDES paths (UPSTREAM.md item 16: with them timed, every build keeps ~6 ns of slack).
- Pass/fail follows placement, not the seed number: adding debug logic changed which seeds pass.
- Suspect: the SERDES reset, fanned out through fabric to 43 serialisers, released on different
  fast-clock edges.

## 2026-10-05 (night): UberDDR3 calibrates

- Loopback at half rate (500 Mb/s): an 18-tap clean window, about 1.4 ns of a 2 ns bit; at 1 Gb/s only
  2-3 taps. A fixed 600-800 ps is lost at both rates: the primitives work, the margin is the clocking.
- LiteDRAM at DDR3-800 and DDR3-667: still no read window. With DDR3 MPR mode on (a fixed pattern, no
  writes needed) reads return all ones: its read capture or its mode-register write is wrong.
- UberDDR3 (GPL; sources copied into the build dir by prebuild.py, never into this repo), its YPCB
  example at DDR3-667 with one byte lane: calibration completes on every build, stock or patched
  nextpnr. Its BIST resets and recalibrates on any wrong read; the full BIST passes on seeds 2 and 4 and
  loops on seeds 1 and 3 (both timing targets give identical builds). nextpnr does not model the
  CLK/CLKDIV alignment between the 333 MHz and 83 MHz global clocks at the serialisers: the likely cause.
- Clock measurements: always measure against the 50 MHz reference; host-timed rates can be wrong.

## 2026-10-05 (evening): gate 9 groundwork

- Both 200 MHz DDR3 reference oscillators run. A LiteX SoC (VexRiscv, LiteDRAM A7 PHY) built with our
  flow boots and its BIOS works over the JTAG UART, but read leveling finds no window.
- Ruled out for LiteDRAM: DDR clock and command path (boundary scan: the clock serialiser toggles,
  control pins idle correctly), IOB settings (patched to Vivado's MIG values), internal VREF and
  ONLY_DIFF bits (nextpnr sets 0.675 V on SSTL15 banks; the board has an external VREF).
- Loopback test of the PHY primitives found nextpnr bug 14: an OSERDES with T1 tied low came out
  permanently tri-stated. Fixed in nextpnr (patch 0003); the loopback then captures, with a narrow
  window. LiteX is not affected by this bug, so its DDR3 failure has another cause.

## 2026-10-05 (later): gates 4 and 5

- **Gate 4**: an MMCM and a PLL from the open tools lock and give 13 exact outputs, 50–333 MHz, with
  counters running at 333 MHz. Feeding the CMTs from a BUFG that also drives fabric produced bogus hold
  violations; feed them from the pin. BUFR does not exist for Kintex-7 in openXC7 (missing metadata;
  with it added, the enable bits are still not written) and BUFH does not route: logged, parked.
- **Gate 5**: two nextpnr BRAM bugs found and fixed, both proven on silicon: RAMB36 x9 parity reaches
  one half only (packer), RAMB36 72-bit SDP write width missing (FASM writer). With both patches all
  13 RAMB36/RAMB18 widths pass at 200 MHz. nextpnr's BRAM hold violations turned out pessimistic.
- **Gate 6**: DSP48E1 multiply, P-feedback accumulate, pre-adder and PCIN cascade (all inside the
  DSPs, per the yosys netlist) are bit-exact against Python over 2^20 operations each, first try.
- **Gate 7**: carry chains, the #134 carry shape and CE-gated SRLs are bit-exact at 200–400 MHz and
  fail at 450; nextpnr rates the same design 235 MHz. Both reported silicon bugs are absent in our
  toolchain (yosys 0.69 has the SRL CE fix; nextpnr has the carry fix).
- **Gate 8**: regymm/pcie_7x enumerates at Gen2 x1 and BAR0 round-trips 1024 words. Hot-adding it by
  rescan looked like a design failure (reads all-ones, zero TLPs into the hard block); USER1 debug
  counters showed memory requests never reached the card. A warm reboot (the card keeps its
  configuration) let the BIOS enumerate it, and MMIO worked. Also found: yosys drops LUTRAM initial
  values; a second BSCAN does not route.
- Builds moved to buzz (dino's toolchain copied; 47 s vs 2–3 min); yosys took 20+ min on large
  initial-content loops until `$readmemh` replaced them (7 s).

## 2026-10-05: AC24 found; outputs and USER1 proven; tooling organised

- Gap review against "ready to build": open-source clocking, BRAM, DSP, timing, PCIe and DDR3 are all
  unproven. Written up as gates in [ROADMAP.md](../../ROADMAP.md); gate 4 (MMCM/PLL) is next.
- IPMI over the LAN proven read-only from buzz (`bmc`); the power cycle is still not run.

- **Every JTAG load had started failing** (from ~22:20 the night before), Vivado-built systest
  included: no CRC/ID error, INIT_B high, startup phase 000, DONE low. A dino reboot did not help (it
  does not power-cycle the card; see [dino](../../hosts/README.md)).
- Ruled out, in order: cable bit errors (BYPASS, 2 Mbit at 1–30 MHz, 0 errors), bitstream size and
  load time (a 55 KB sparse bitstream failed), the startup clock (the always-working bridge has the
  same COR0), CTL1 bit 12, power rails.
- **Bisection on hardware** of openFPGALoader's bpiOverJtag bridge (which always started): ~191k
  frame-write packets → one 3-frame write at FAR `0x00400026` → one bit (word 0, bit 30), about 40
  loads in all. That bit is AC24's pull type: the unused-pin default PULLDOWN is the whole problem.
  Others found and explained the same pin: AC24 is a board reset that fires when startup releases
  the I/O ([TiferKing#3](https://github.com/TiferKing/ypcb_00338_1p1_hack/issues/3),
  [zollij](https://gist.github.com/zollij/bf49d29b0ab79d38346dda2a0e32b5b9)).
- AC24's pad reads 0 when undriven, even with no pull; the factory image drives it high as an output.
  It is not on the DONE net (the DONE pad reads 1 while AC24 reads 0).
- With AC24 pulled up: the openXC7 blinky drives all three LEDs at the expected rates (boundary scan),
  three loads in a row reach DONE, and hello's BSCANE2 USER1 returns `c0ffee42` and a counter at
  49.92–49.98 MHz.
- **Corrected the same day:** I first claimed fasm2frames puts `LIOB33_SING` IOB_Y0 bits 64 bits too
  high, because a FASM-level fix "did nothing". A direct test showed fasm2frames places
  `LIOB33_SING_X0Y150.IOB_Y0.PULLTYPE.*` correctly and both PULLUP and NONE reach DONE. The earlier
  failed test is unexplained. The fix is now a one-line [board.fasm](board.fasm). The real fasm2frames
  error is at the top SING tile: `LIOB33_SING_X0Y199` gives "invalid word address 101"
  ([UPSTREAM](../../UPSTREAM.md)).
- Organised the tree: the `fpga` command, `tools/xc7-build.sh`, `tools/bitstream/`, these pages, local
  copies of other people's material in `references/third-party/` (git-ignored).
- BMC in-band works from dino (`sudo ipmitool`, `/dev/ipmi0`). Over-LAN access is parked: the BMC is reachable from buzz, not from dino, and we have no LAN credentials yet.

## 2026-10-04: first bring-up

- JTAG cable arrived: Digilent JTAG-HS3. Chain: one XC7K480T, rev 2. eFUSEs read (read-only):
  untouched.
- Factory flash backed up: Vivado Lab Edition (installed in ~/opt/Xilinx, no licence or sudo needed)
  reads 64 MiB in 60 s. openFPGALoader's bridge manages 2.6 KB/s. Two Vivado reads are identical and
  match an openFPGALoader read after byte-swap and bit-reversal. The flash holds one 18.73 MB bitstream
  plus the `SCH-001-003-20160217-0227` record.
- openXC7: the chipdb builds with `--device xc7k480t`, and designs place and route.
- **SYS_CLK is 50 MHz, not 100**: measured from the blinky's counter by boundary scan, and later by
  USER1.
- TiferKing's Vivado systest loaded over JTAG: MicroBlaze visible in xsdb, LEDs driven through GPIO.
  DDR3 proven by sample on both channels at the CPU's 36-bit addresses (`0x1_0000_0000`,
  `0x1_8000_0000`). The first attempt used `0x80000000`, which is DDR only from XDMA, and saw aliasing.
- Thermal: 51–55 °C with the systest, rising past 62 °C with the openXC7 blinky. VCCINT 0.959 V.
- **Conclusions from this day that were wrong** (all of them were silently failed loads caused by AC24):
  - "openXC7 outputs stay tri-stated, ZINV_T1 missing". The pattern `110 110 110` is just an
    unconfigured chip, and adding ZINV_T1 by hand forces tri-state. Vivado leaves it unset too.
  - "BSCANE2 makes startup hang".
  - "Loads alternate between working and failing".
  - "openXC7 puts led[1] on M31": that was a decode labelling mix-up; M30 drives fine.
  - "openFPGALoader's HS2/HS3 profile resets the FPGA on release". Its STAT read is unreliable
    (it read 0x0 for a configured chip); use xsdb for checks.
- Decoding TiferKing's Vivado bitstream with prjxray `bit2fasm` showed our LED tiles are bit-identical
  to Vivado's. Its 3,938 bits that prjxray cannot name sit in CMT, sysmon, CLB and other IOI tiles.
