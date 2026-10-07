# Bring-up roadmap: fundamentals before building

**Goal:** prove every fundamental of the [YPCB-00338-1P1](board/ypcb-00338-1p1/README.md) with the open
toolchain only (yosys, nextpnr-xilinx, prjxray, openFPGALoader; no Vivado-built bitstreams) before we
build anything real on it. This will take several sessions. Work the gates in order: each one is done
only when its proof is met on our card and recorded here, in the card README and in the
[LOG](board/ypcb-00338-1p1/LOG.md). Bugs found on the way go to [UPSTREAM.md](UPSTREAM.md).

Vivado-built designs (TiferKing's systest, MIG) are allowed only as **references**: something to decode
and compare against, never as the solution.

## Where we are (2026-10-06)

| # | Gate | Status | Proof so far |
|---|---|---|---|
| 0 | Remote control of the card and host | ✅ done | `fpga`, `bmc`; JTAG load/status/boundary scan; BMC in-band and over LAN (read-only) |
| 1 | Logic, I/O, the 50 MHz clock | ✅ done | blinky: LEDs driven at 0.37/1.49 Hz (`fpga pins`) |
| 2 | JTAG debug channel from a design | ✅ done | hello: BSCANE2 USER1, counter at 49.98 MHz (`fpga user1`) |
| 3 | Reliable load and startup | ✅ done | AC24 pull-up in `board.fasm`; repeated loads reach DONE |
| 4 | Clock management (MMCM/PLL) at full rate | ✅ done (BUFR/BUFH gaps logged) | MMCM + PLL: 13 outputs exact, 50–333 MHz incl. fractional /7.5, counters running at 333 MHz ([designs/clocks](designs/clocks/)). BUFG proven. BUFR and BUFH do not work yet (UPSTREAM 6, 7); nothing on our path needs them |
| 5 | Block RAM | ✅ done (with our nextpnr patches) | all 13 RAMB36/RAMB18 widths incl. both SDP modes: initial contents + millions of 200 MHz write/read passes, zero errors ([designs/bramwidths](designs/bramwidths/)). Stock nextpnr fails 36K x9 and 36K x72 SDP: two bugs fixed (UPSTREAM 5, 9) |
| 6 | DSP48 | ✅ done | 7 DSP48E1 at 200 MHz, 2^20 ops per datapath, checksums bit-exact vs Python: A/B/M/P pipeline registers, P-feedback accumulate, pre-adder (D port, ADREG), PCIN cascade with 17-bit shift ([designs/dsp](designs/dsp/)); stock nextpnr |
| 7 | Timing we can trust | ✅ done | 32/64-bit carry chains, the #134 carry shape and CE-gated SRLs all bit-exact at 200/250/333/400 MHz, fail at 450 ([designs/timing](designs/timing/)). nextpnr reports 235 MHz for the same design: its Fmax is a conservative floor (~1.7× pessimistic on carry chains). Both reported silicon bugs are absent in our toolchain: no `-nocarry` / `-nosrl` |
| 8 | PCIe: the card enumerates on dino | ✅ done | regymm/pcie_7x (PCIE_2_1 + GTX, no Vivado IP) built with our flow: dino's BIOS enumerates `10ee:0480`, link 5 GT/s x1, 1024 BAR0 writes/reads round-trip with zero errors ([designs/pcie-x1](designs/pcie-x1/)). Load, then `fpga pcie-cycle` (remove, rescan, enable memory); no reboot needed (the gate-8 belief that hot-add fails here was a missing Memory Space Enable, found 2026-10-06) |
| 9 | DDR3: both channels, open source | ✅ done (DDR3-667) | UberDDR3 with our prebuild.py fixes ([designs/uberddr3](designs/uberddr3/)): both channels, all 9 lanes each (72 bits with ECC), in one bitstream (UBER_DUAL=1). Both calibrate first time; [memtest.v](designs/uberddr3/memtest.v) writes and reads back each channel's full 2 GiB (2.25 GiB with ECC) with 0 errors at 5.04 GB/s each way, 94% of the DDR3-667 peak; the same after a cold power cycle of dino. Needed: CK/DQS clock inversion in the I/O tile (not a fabric LUT), CK delayed 60° (no write leveling without ODELAY; clean window +30° to +90° on both channels), internal VREF, one shared IDELAYCTRL, a pipelined memory-test compare. Open: about 1 placement in 4 fails to calibrate one lane (screen with `fpga check`); dual builds route in about an hour; DDR3-800+ untried (MIG runs this board at 1066 with PHASER write leveling) |
| 10 | Ready to build | 🟨 in progress | gates 0–9 done; card README updated; [GETTING-STARTED.md](GETTING-STARTED.md) and "Starting a new design" in [tools/openxc7.md](tools/openxc7.md) written; patched nextpnr built by [tools/build-nextpnr.sh](tools/build-nextpnr.sh) as the default; upstream: PRs and issues being filed (see [UPSTREAM.md](UPSTREAM.md)) |

Not gates, do when convenient: a cold power cycle through the BMC (written, not run; ask first), the
200 MHz input clocks (part of gate 9), boot from flash without JTAG (needs the owner's OK to write
flash), the openFPGALoader re-tests in UPSTREAM.md.

## Gate 4: clock management at full rate

**Why:** everything past a blinky needs clocks faster than 50 MHz, and DDR3 needs several related ones.
The chipdb generator reports 342 HCLK_CMT routing points with no known bits, so it might not work.

**Proof:** a design with an MMCME2_ADV (and separately a PLLE2_ADV) from SYS_CLK, exposing on USER1
the LOCKED flag and counters in each output domain. `fpga user1`-style reads give each clock's rate.
Pass: locked, and rates within 0.5 % of 100, 200 and 250 MHz (and a non-integer ratio, such as
166.67 MHz) for both primitives. Also BUFG, BUFH and BUFR from an MMCM output.

**Evidence to start from:** FeSens' openXC7 DDR3 log on this board shows `pll_locked=1`
([bonetto-soc](https://github.com/FeSens/bonetto-soc)). If a clock route cannot be expressed, decode
the systest (which has MIG MMCMs) with bit2fasm and compare the CMT tiles.

## Gate 5: block RAM

**Proof:** a RAMB36E1 and a RAMB18E1 (both port widths, initial contents from the bitstream), written
and read back through a USER register (a JTAG-to-memory bridge in the design), plus a design-side
self-test that checks a pattern and reports pass/fail. Pass: INIT contents read back exactly; a
walking pattern over the whole depth passes at 100 MHz or more.

## Gate 6: DSP48

**Proof:** DSP48E1 multiply-accumulate with the pipeline registers in use, checked against values
computed in fabric over USER1, at the gate-4 clock. The chipdb flags 78+78 DSP routing points with no
bits; watch for those.

## Gate 7: timing we can trust

**Why:** nextpnr's timing model for Kintex-7 -2 has not been checked against silicon, and two silicon
bugs are reported on this part (CARRY4 corner case, openXC7/nextpnr-xilinx#134; yosys `xilinx_srl`
clock-enable bug, YosysHQ/yosys#6058, said fixed after 0.67, we run 0.69).

**Proof:** a set of pipelined datapaths (adders using CARRY4, enable-gated SRL shift registers, BRAM to
DSP paths) run at stepped clocks from gate 4, each self-checking over USER1. Record the frequency
where each first fails, next to nextpnr's reported Fmax. Decide whether we build with `-nocarry` /
`-nosrl` from the results, not from hearsay.

## Gate 8: PCIe

**Why:** this is the riskiest unknown. If openXC7 cannot drive the GTX transceivers and the PCIE_2_1
hard block on Kintex-7, plans change. If it can, we get `lspci` visibility from dino and a fast data
path that makes gate 9 easier.

**Proof:** a design from [regymm/pcie_7x](https://github.com/regymm/pcie_7x)
(`pcie_7x_ypcb_k480t.xdc`): x1 first, then x4/x8. Pass: after `echo 1 | sudo tee /sys/bus/pci/rescan`,
dino shows our vendor/device ID; config space reads; a BAR read/write round-trips. Record the link
speed and width from `lspci -vv`. Remove the stale factory `04:00.0` first (see the dino page) and
watch for AER errors.

**Notes:** PERST is Y26 and the reference clock is J8/J7 (100 MHz from the slot). The GTX lane order
comes from the LiteX platform. Some hosts needed PERST inverted (ruidongwu).

## Gate 9: DDR3, open source

**Why:** the biggest gap. It works on this card only with Vivado's MIG. Under openXC7 others report
calibration stuck (UberDDR3 #44) or blocked (FeSens), and openXC7 has DDR3 flow bugs: `INTERNAL_VREF`
ignored, a pseudo-pip that aborts fasm2frames ([openXC7/nextpnr#20](https://github.com/openXC7/nextpnr/issues/20)).

**Done 2026-10-06** with UberDDR3; LiteDRAM was dropped (it never calibrated). **Proof:** LiteDRAM or UberDDR3 built with openXC7, channel 0 then channel 1: calibration completes;
a full memory test (all 2 GB per channel, address, data and pattern tests, ECC lanes too) passes;
measured bandwidth recorded. Repeat after a cold start.

**Method:** our edge is a known-good Vivado bitstream on this exact card. Decode the systest with
`bit2fasm` and diff its memory-bank IOI/IOB tiles (IDELAY/ODELAY taps, ISERDES/OSERDES modes, IN_TERM,
VREF, IDELAYCTRL, the 200 MHz clocks) against ours, the same way the AC24 bisection worked. Check the
200 MHz oscillators run (zollij saw none on the sibling board) before relying on them.

## Gate 10: ready to build

All of the above passing, the card README's facts updated, upstream drafts filed or decided, and a
short "how to start a new design" section in [tools/openxc7.md](tools/openxc7.md). Everything but the
upstream filing is done.

## How to pick this up in a new session

1. Read this page, then the card [README](board/ypcb-00338-1p1/README.md) and [tools/openxc7.md](tools/openxc7.md).
2. `fpga status` (is the card reachable and what is loaded?), `bmc status` (is the card host up?).
3. Take the first ⬜ gate. Put its test design in `designs/<gate-name>/`, build with `fpga build`,
   check over USER1 or boundary scan, and update the status table above with the proof.
