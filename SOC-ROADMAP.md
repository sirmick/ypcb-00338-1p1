# SoC roadmap: virtio over PCIe, and Debian on four cores

**Goal:** turn the proven card ([ROADMAP.md](ROADMAP.md), gates 0–9) into a system-on-chip that
boots **Debian on four VexiiRiscv cores (RV64GC, Sv39)**, with its disk and network served as
**virtio devices by a userland backend on the host** over PCIe, all built with open tools. Work the
stages in order; each is done only when its proof is met and recorded here and in the
[log](board/ypcb-00338-1p1/LOG.md).

Decisions (owner, 2026-10-06):
- RTL in **SpinalHDL**, tested with **SpinalSim** (Verilator underneath).
- It all lives in this repository: useful to anyone with the card.
- The show-off target: **four cores with FPU** (RV64IMAFDC), so stock Debian riscv64 runs.

## The design in one page

```text
 host (Linux)                                   card (XC7K480T)
 ┌───────────────────────────┐   PCIe Gen2 x1   ┌────────────────────────────────────────────────┐
 │ cardd (Rust, userland,    │◀── posted ───────│ DMA engine / forwarder ── window checks        │
 │ VFIO): console, blk, net  │    writes        │   │                                            │
 │ host inbox + staging      │                  │ virtio-mmio shims ── ring region (BRAM)        │
 │ (pinned, IOMMU-mapped)    │─── posted ──────▶│ BAR0 mailbox (card inbox), doorbells → PLIC    │
 └───────────────────────────┘    writes        │ 4× VexiiRiscv RV64GC ─ coherent L1, shared L2  │
                                                │ DDR3 channel A: main memory                    │
                                                │ DDR3 channel B: DMA only (buffers)             │
                                                │ CLINT, PLIC, 16550 at QEMU virt's addresses    │
                                                └────────────────────────────────────────────────┘
```

- **The SoC copies QEMU `virt`'s map** (CLINT, PLIC, a 16550, virtio-mmio slots, RAM at
  `0x8000_0000`), so OpenSBI and a stock Linux kernel need only a device tree.
- **Each side's inbox lives in its own memory, and only posted writes cross the link.** The host's
  inbox is pinned host memory; the card's is the BAR0 mailbox. The one read that crosses is the DMA
  engine fetching bulk data from host staging.
- **The host never parses a guest pointer.** Every descriptor is checked against a channel-B window
  on the card; `cardd` parses only fixed-format records in its own memory.
- **The virtio rings stay on the card.** Linux puts its rings and buffers in channel B through a
  `restricted-dma-pool` (one DMA pool per device; the BRAM ring region is for drivers that can
  place rings separately).
- **One versioned contract** (BAR0 offsets, record layouts, mailbox commands, magic and version)
  generates the SpinalHDL constants, the Rust constants, the device-tree fragment and the shared
  test transcripts, so RTL and backend cannot disagree.
- **Two phases:** first `cardd` walks the rings through mailbox copy commands; later a forwarder in
  the fabric pushes whole requests.

## Layout

```text
soc/                       SpinalHDL project (sbt): the SoC, shims, mailbox, DMA engine; `sbt test`
soc/src/.../card/contract/ the contract (Contract.scala), transcripts, generator (Generate.scala)
soc/gen/                   generated: contract.dtsi, CONTRACT.md
cardd/                     the host backend (Rust, no dependencies yet); `cargo test`
cardd/src/contract.rs      generated from the contract
linux/                     (S5) OpenSBI, kernel config, device trees, Debian root filesystem scripts
```

## Where we are (2026-10-06)

| # | Stage | Status | Proof so far |
|---|---|---|---|
| S0 | Toolchain, and the core's cost under openXC7 | ✅ done | VexiiRiscv's MicroSoc with LiteX's "debian" core options (RV64IMAFDC, Sv39, 4-way L1s, BTB/RAS/GShare): 16.5k LUTs, 8.5k FFs, 54 BRAM, 16 DSP, place and route 2 min, nextpnr Fmax 118.7 MHz (RV64IMAC: 10.9k LUTs, 124.5 MHz). A self-test of integer, M, A and double-precision FPU instructions passes on the card at 50 MHz ([designs/vexii-s0](designs/vexii-s0/)) |
| S1 | The contract and its generators | ✅ done | [Contract.scala](soc/src/main/scala/card/contract/Contract.scala) generates Rust ([cardd/src/contract.rs](cardd/src/contract.rs)), a device-tree fragment, [a readable table](soc/gen/CONTRACT.md) and shared transcripts; the RTL's message encoder (SpinalSim, 12 kinds) and cardd (5 tests) produce identical bytes; editing the source makes the freshness test fail until all four outputs are regenerated |
| S2 | virtio-mmio shim and the mailbox, tested both sides | ⬜ | |
| S3 | Phase-1 copy engine; hostile cases; co-simulation | ⬜ | |
| S4 | On the card: link latency and card-initiated DMA | ⬜ | |
| S5 | One core boots Linux | ⬜ | |
| S6 | virtio-blk and virtio-net through `cardd`; Debian | ⬜ | |
| S7 | Four cores: SMP Debian | ⬜ | |
| S8 | The forwarder and confined DMA windows | ⬜ | |

## S0: toolchain, and the core's cost under openXC7

**Why:** four RV64GC cores with FPUs is the largest design this flow has seen; our dual-channel DDR3
build (36k LUTs) already took an hour to route. Measure before designing around it.
**Proof:** VexiiRiscv generated and simulated (SpinalSim/Verilator); one RV64GC core with FPU, Sv39
and its L1 caches synthesised, placed and routed for the XC7K480T: LUTs, FFs, BRAM, DSP, routing
time and nextpnr's Fmax recorded, and the same for RV64IMAC without FPU. The extrapolation to four
cores plus L2 written down.

**Done 2026-10-06.** The core alone synthesises to about 11k LUTs, 7.5k FFs, 38 BRAM and 16 DSP, with
227 LUTRAM primitives even with `--regfile-sync`; openXC7 places them, and the self-test's results
are right. Four cores and an L2 extrapolate to roughly 60–70k LUTs, about 11% of the device; routing
time, not area, is the thing to watch.

## S1: the contract

**Proof:** one source file defines BAR0's offsets, the notify and request record layouts, the
mailbox command format, the magic and the version; generators emit SpinalHDL and Rust constants, a
device-tree fragment and test transcripts (guest actions in, host-inbox records expected;
completions in, used-ring contents expected). A change to the source changes all four.

**Done 2026-10-06.** The contract is a Scala object, so the RTL reads it directly; records and commands
are 64 bytes with the sequence number at both ends. Running it: `cd soc && sbt test` (SpinalSim,
Verilator) and `cd cardd && cargo test`; after editing the contract, `sbt "runMain
card.contract.Generate"`.

## S2: the shim and the mailbox

**Proof (SpinalSim, configuration A):** the virtio-mmio v2 register file per slot (blk and net IDs,
features, `QueueNumMax`, config space) answers the guest locally; `QueueNotify`, `Status`, queue
addresses and driver features become notify records in the host-inbox model; a doorbell sets
`InterruptStatus` and drives a level PLIC line; BAR offsets outside the mailbox read zero and
ignore writes. `cardd`'s fake-card tests replay the same transcripts and pass.

## S3: the copy engine, hostile cases, co-simulation

**Proof:** (configuration B) mailbox copy commands move a channel-B range into host staging only
after the window check; (configuration D) looping chains, out-of-window descriptors, overlong
chains, `INDIRECT`, torn and stale records are refused; `cardd`'s fuzz targets run clean; and
`cardd` itself runs end to end against a Verilator model of the card RTL (co-simulation), serving
a virtio-blk read to a simulated guest.

## S4: on the card

**Proof:** a bitstream with `pcie_7x`, DDR3 and the mailbox: `cardd` opens it through VFIO, rings
doorbells and receives MSI; **the card writes records into pinned host memory** (bus mastering,
not yet proven by gate 8, which tested host-to-card BAR access only); doorbell, interrupt and
inbox round trips measured with a fabric cycle counter. Note dino's quirk: load the bitstream, then
warm-reboot so the BIOS enumerates the card.

## S5: one core boots Linux

**Proof:** one VexiiRiscv RV64GC (Sv39, FPU) on a TileLink SoC with DDR3 channel A, CLINT, PLIC and
the 16550; OpenSBI, a stock kernel and an initramfs boot to a shell, the console reaching the host
through `cardd`'s socket. A memory test from Linux over channel A passes.

## S6: virtio and Debian

**Proof:** virtio-blk and virtio-net shims served by `cardd` (disk image, tap device), the guest's
buffers bounced through a `restricted-dma-pool` in channel B; Debian riscv64 boots from the
virtio disk; `fio` and `iperf3` numbers recorded; the attack cases above still refused.

## S7: four cores

**Proof:** four coherent cores with a shared L2: SMP Linux and Debian; `stress-ng` and a memory
test across all harts pass for an hour; timing closes under openXC7 at a recorded frequency; DDR
bandwidth and coherence measured.

## S8: the forwarder and confined DMA

**Proof:** (configuration C) a fabric walker forwards whole requests (`EVENT_IDX` on and off; queue
sizes 8/64/256; 1 and 3 slots); every bus master but the cores decodes only into channel-B windows,
with a SymbiYosys proof that no master leaves its window, and the attack cases on the card.

## Risks

- **Size and routing time.** Four FPU cores and an L2 may take many hours to route, or not converge.
  S0 measures; pipelining at clock-region boundaries and floorplanning are the levers.
- **DDR3 placement failures.** About one placement in four fails one lane's calibration
  (ROADMAP gate 9): screen with `fpga check` until it is explained.
- **Card-initiated DMA** is unproven on this host.
- **This exact SoC is new.** VexiiRiscv's proven Linux systems use LiteX; ours is SpinalHDL's TileLink
  generator with UberDDR3 and `pcie_7x`.
