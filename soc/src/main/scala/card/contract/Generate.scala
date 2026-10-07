package card.contract

import java.nio.file.{Files, Path, Paths}
import Contract.{Bar0, Map => M, Message, VirtioMmio => V}

// Writes the contract's outputs, relative to the soc/ directory:
//   ../cardd/src/contract.rs      Rust constants and message layouts for the host backend
//   ../cardd/tests/generated/transcripts.rs  the shared transcripts, with every message's expected bytes
//   gen/contract.dtsi             the device-tree fragment for the virtio slots and the DMA pool
//   gen/CONTRACT.md               the contract as tables, for people
// `sbt "runMain card.contract.Generate"` writes them; ContractSpec checks they are up to date.
object Generate {
  val header = "Generated from soc/src/main/scala/card/contract/Contract.scala by Generate.scala. Do not edit."

  def outputs: Seq[(String, String)] = Seq(
    "../cardd/src/contract.rs" -> rust,
    "../cardd/tests/generated/transcripts.rs" -> transcripts,
    "gen/contract.dtsi" -> dtsi,
    "gen/CONTRACT.md" -> markdown
  )

  def main(args: Array[String]): Unit = {
    val root = Paths.get(args.headOption.getOrElse("."))
    for ((rel, text) <- outputs) {
      val p = root.resolve(rel).normalize()
      Files.createDirectories(p.getParent)
      Files.writeString(p, text)
      println(s"wrote $p")
    }
  }

  // MagicValue -> MAGIC_VALUE, DeviceID -> DEVICE_ID, InterruptACK -> INTERRUPT_ACK
  def snake(s: String): String =
    s.replaceAll("([a-z0-9])([A-Z])", "$1_$2").replaceAll("([A-Z])([A-Z][a-z])", "$1_$2").toUpperCase
  private def hex(v: Long) = f"0x$v%x"
  private def regConsts(regs: Seq[Reg], indent: String) =
    regs.map(r => s"$indent/// ${r.access} ${r.doc}".replaceAll(" +$", "") + s"\n${indent}pub const ${snake(r.name)}: usize = ${hex(r.offset)};").mkString("\n")
  private def layouts(ls: Seq[Layout]) = ls.map { l =>
    val fs = l.fields.map(fl => s"""Field { name: "${fl.name}", offset: ${fl.offset}, bytes: ${fl.bytes} }""").mkString(", ")
    s"""    /// ${l.doc}\n    pub const ${l.name}: Layout = Layout { name: "${l.name}", kind: ${l.kind}, fields: &[$fs] };"""
  }.mkString("\n")

  def rust: String =
    s"""// $header
       |#![allow(dead_code)]
       |
       |/// Contract version, read at BAR0 VERSION; cardd refuses any other.
       |pub const VERSION: u32 = ${Contract.Version};
       |/// BAR0 MAGIC: "CARD".
       |pub const MAGIC: u32 = ${hex(Contract.Magic)};
       |
       |/// The guest's address map (QEMU virt's).
       |pub mod map {
       |    pub const CLINT: u64 = ${hex(M.Clint)};
       |    pub const PLIC: u64 = ${hex(M.Plic)};
       |    pub const UART: u64 = ${hex(M.Uart)};
       |    pub const UART_IRQ: u32 = ${M.UartIrq};
       |    pub const VIRTIO_BASE: u64 = ${hex(M.VirtioBase)};
       |    pub const VIRTIO_STRIDE: u64 = ${hex(M.VirtioStride)};
       |    pub const VIRTIO_SLOTS: usize = ${M.VirtioSlots};
       |    pub const fn virtio_irq(slot: usize) -> u32 { 1 + slot as u32 }
       |    pub const RING_REGION: u64 = ${hex(M.RingRegion)};
       |    pub const RING_REGION_SIZE: u64 = ${hex(M.RingRegionSize)};
       |    pub const RAM: u64 = ${hex(M.Ram)};
       |    pub const RAM_SIZE: u64 = ${hex(M.RamSize)};
       |    pub const DMA_REGION: u64 = ${hex(M.DmaRegion)};
       |    pub const DMA_REGION_SIZE: u64 = ${hex(M.DmaRegionSize)};
       |}
       |
       |/// virtio-mmio v2, as each shim presents it to the guest.
       |pub mod virtio_mmio {
       |    pub const MAGIC_VALUE: u32 = ${hex(V.MagicValue)};
       |    pub const VERSION: u32 = ${V.VersionValue};
       |    pub const VENDOR_ID: u32 = ${hex(V.VendorId)};
       |    pub const CONFIG_BYTES: usize = ${V.ConfigBytes};
       |    pub const QUEUES_PER_SLOT: usize = ${V.QueuesPerSlot};
       |    pub const STATUS_ACKNOWLEDGE: u8 = ${V.Acknowledge};
       |    pub const STATUS_DRIVER: u8 = ${V.Driver};
       |    pub const STATUS_DRIVER_OK: u8 = ${V.DriverOk};
       |    pub const STATUS_FEATURES_OK: u8 = ${V.FeaturesOk};
       |    pub const STATUS_FAILED: u8 = ${V.Failed};
       |    pub const F_EVENT_IDX: u32 = ${V.FeatureEventIdx};
       |    pub const F_VERSION_1: u32 = ${V.FeatureVersion1};
       |    pub const F_ACCESS_PLATFORM: u32 = ${V.FeatureAccessPlatform};
       |    pub const DEVICE_NET: u32 = ${V.DeviceNet};
       |    pub const DEVICE_BLK: u32 = ${V.DeviceBlk};
       |    pub mod reg {
       |${regConsts(V.regs, "        ")}
       |    }
       |}
       |
       |/// BAR0: the mailbox.
       |pub mod bar0 {
       |    pub const SIZE: usize = ${hex(Bar0.Size)};
       |    pub const CMD_RING: usize = ${hex(Bar0.CmdRing)};
       |    pub const CMD_ENTRIES_LOG2: u32 = ${Bar0.CmdEntriesLog2};
       |    pub const SLOT_PROG_BASE: usize = ${hex(Bar0.SlotProgBase)};
       |    pub const SLOT_PROG_STRIDE: usize = ${hex(Bar0.SlotProgStride)};
       |    pub mod reg {
       |${regConsts(Bar0.regs, "        ")}
       |    }
       |    /// Per-slot programming registers, relative to SLOT_PROG_BASE + slot * SLOT_PROG_STRIDE.
       |    pub mod slot_reg {
       |${regConsts(Bar0.slotRegs, "        ")}
       |    }
       |}
       |
       |/// COPY_DONE status codes.
       |pub mod copy_status {
       |    pub const DONE: u16 = ${Contract.CopyStatus.Done};
       |    pub const OUTSIDE_WINDOW: u16 = ${Contract.CopyStatus.OutsideWindow};
       |    pub const BAD_LENGTH: u16 = ${Contract.CopyStatus.BadLength};
       |    pub const MISALIGNED: u16 = ${Contract.CopyStatus.Misaligned};
       |}
       |
       |/// The DMA windows (base, size) a guest range in a copy or a used ring must lie wholly inside.
       |pub const DMA_WINDOWS: &[(u64, u64)] = &[${Contract.dmaWindows.map { case (b, sz) => s"(${hex(b)}, ${hex(sz)})" }.mkString(", ")}];
       |
       |/// Records and commands: 64 bytes, little-endian, sequence number at both ends.
       |pub mod message {
       |    pub const SIZE: usize = ${Message.Size};
       |    pub const SEQ_OFFSET: usize = ${Message.SeqOffset};
       |    pub const KIND_OFFSET: usize = ${Message.KindOffset};
       |    pub const SLOT_OFFSET: usize = ${Message.SlotOffset};
       |    pub const TAIL_OFFSET: usize = ${Message.TailOffset};
       |}
       |
       |#[derive(Debug, PartialEq, Eq)]
       |pub struct Field { pub name: &'static str, pub offset: usize, pub bytes: usize }
       |
       |#[derive(Debug, PartialEq, Eq)]
       |pub struct Layout { pub name: &'static str, pub kind: u16, pub fields: &'static [Field] }
       |
       |/// Card to host, in the host inbox.
       |pub mod record {
       |    use super::{Field, Layout};
       |${layouts(Contract.records)}
       |    pub const ALL: &[&Layout] = &[${Contract.records.map(l => "&" + l.name).mkString(", ")}];
       |}
       |
       |/// Host to card, in the BAR0 command ring.
       |pub mod command {
       |    use super::{Field, Layout};
       |${layouts(Contract.commands)}
       |    pub const ALL: &[&Layout] = &[${Contract.commands.map(l => "&" + l.name).mkString(", ")}];
       |}
       |""".stripMargin

  private def bytesOf(v: BigInt, n: Int): String = (0 until n).map(i => f"0x${((v >> (8 * i)) & 0xff).toInt}%02x").mkString(", ")
  private def valuesRust(l: Layout, values: Map[String, BigInt]) =
    l.fields.filter(fl => values.contains(fl.name)).map(fl => s"""("${fl.name}", &[${bytesOf(values(fl.name), fl.bytes)}])""").mkString(", ")

  def transcripts: String = {
    def step(s: Step, q: Long): String = s match {
      case HostProgram(slot, reg, v) => s"HostProgram { slot: $slot, reg: bar0::slot_reg::${snake(reg)}, value: ${hex(v)} }"
      case GuestWrite(slot, reg, v) => s"GuestWrite { slot: $slot, reg: virtio_mmio::reg::${snake(reg)}, value: ${hex(v)} }"
      case GuestRead(slot, reg, v) => s"GuestRead { slot: $slot, reg: virtio_mmio::reg::${snake(reg)}, expect: ${hex(v)} }"
      case ExpectIrq(slot, level) => s"ExpectIrq { slot: $slot, level: $level }"
      case GuestMem(a, b) => s"GuestMem { address: ${hex(a)}, bytes: &[${b.mkString(", ")}] }"
      case StagingWrite(o, b) => s"StagingWrite { offset: ${hex(o)}, bytes: &[${b.mkString(", ")}] }"
      case ExpectStaging(o, b) => s"ExpectStaging { offset: ${hex(o)}, bytes: &[${b.mkString(", ")}] }"
      case ExpectGuestMem(a, b) => s"ExpectGuestMem { address: ${hex(a)}, bytes: &[${b.mkString(", ")}] }"
      case r @ ExpectRecord(n, slot, values) =>
        val l = Contract.record(n)
        s"ExpectRecord(Msg { layout: &record::$n, seq: $q, slot: $slot, values: &[${valuesRust(l, values)}],\n            bytes: [${Contract.encode(l, q, slot, values).map(b => f"0x${b & 0xff}%02x").mkString(", ")}] })"
      case c @ HostCommand(n, slot, values) =>
        val l = Contract.command(n)
        s"HostCommand(Msg { layout: &command::$n, seq: $q, slot: $slot, values: &[${valuesRust(l, values)}],\n            bytes: [${Contract.encode(l, q, slot, values).map(b => f"0x${b & 0xff}%02x").mkString(", ")}] })"
    }
    val ts = Transcripts.all.map { t =>
      val lines = t.steps.zip(t.sequenceNumbers).map { case (s, q) => "            Step::" + step(s, q) + "," }
      s"""    Transcript {\n        name: "${t.name}",\n        doc: "${t.doc}",\n        steps: &[\n${lines.mkString("\n")}\n        ],\n    },"""
    }.mkString("\n")
    s"""// $header
       |// The shared transcripts. The RTL's SpinalSim tests replay the same steps against the card.
       |#![allow(dead_code)]
       |
       |use cardd::contract::{bar0, command, record, virtio_mmio, Layout};
       |
       |pub struct Msg {
       |    pub layout: &'static Layout,
       |    pub seq: u32,
       |    pub slot: u16,
       |    /// field name and its value, little-endian, at the field's full width
       |    pub values: &'static [(&'static str, &'static [u8])],
       |    /// the 64 bytes both sides must produce
       |    pub bytes: [u8; 64],
       |}
       |
       |pub enum Step {
       |    HostProgram { slot: usize, reg: usize, value: u32 },
       |    GuestWrite { slot: usize, reg: usize, value: u32 },
       |    GuestRead { slot: usize, reg: usize, expect: u32 },
       |    ExpectRecord(Msg),
       |    HostCommand(Msg),
       |    ExpectIrq { slot: usize, level: bool },
       |    GuestMem { address: u64, bytes: &'static [u8] },
       |    StagingWrite { offset: u64, bytes: &'static [u8] },
       |    ExpectStaging { offset: u64, bytes: &'static [u8] },
       |    ExpectGuestMem { address: u64, bytes: &'static [u8] },
       |}
       |
       |pub struct Transcript {
       |    pub name: &'static str,
       |    pub doc: &'static str,
       |    pub steps: &'static [Step],
       |}
       |
       |pub const TRANSCRIPTS: &[Transcript] = &[
       |$ts
       |];
       |""".stripMargin
  }

  def dtsi: String = {
    val slots = (0 until M.VirtioSlots).map { s =>
      val a = M.VirtioBase + s * M.VirtioStride
      s"""\t\tvirtio_mmio@${a.toHexString} {
         |\t\t\tcompatible = "virtio,mmio";
         |\t\t\treg = <0x0 ${hex(a)} 0x0 ${hex(M.VirtioStride)}>;
         |\t\t\tinterrupts = <${M.virtioIrq(s)}>;
         |\t\t\tinterrupt-parent = <&plic>;
         |\t\t\tmemory-region = <&card_dma_pool>;
         |\t\t};""".stripMargin
    }.mkString("\n")
    s"""/* $header */
       |/* The card's virtio-mmio slots and its DMA region (DDR3 channel B). Every virtio buffer and ring a
       | * driver allocates comes from the restricted DMA pool, so it lies inside the card's DMA window.
       | * The including tree provides the PLIC with the label `plic`. */
       |/ {
       |\treserved-memory {
       |\t\t#address-cells = <2>;
       |\t\t#size-cells = <2>;
       |\t\tranges;
       |\t\tcard_dma_pool: restricted-dma@${M.DmaRegion.toHexString} {
       |\t\t\tcompatible = "restricted-dma-pool";
       |\t\t\treg = <${hex(M.DmaRegion >>> 32)} ${hex(M.DmaRegion & 0xffffffffL)} ${hex(M.DmaRegionSize >>> 32)} ${hex(M.DmaRegionSize & 0xffffffffL)}>;
       |\t\t};
       |\t};
       |\tsoc {
       |\t\t#address-cells = <2>;
       |\t\t#size-cells = <2>;
       |$slots
       |\t};
       |};
       |""".stripMargin
  }

  def markdown: String = {
    def regTable(rs: Seq[Reg]) = "| Offset | Name | Access | |\n|---|---|---|---|\n" +
      rs.map(r => s"| `${hex(r.offset)}` | `${r.name}` | ${r.access} | ${r.doc} |").mkString("\n")
    def layoutTable(ls: Seq[Layout]) = ls.map { l =>
      s"### ${l.name} (kind ${l.kind})\n\n${l.doc}.\n\n| Offset | Field | Bytes | |\n|---|---|---|---|\n" +
        l.fields.map(fl => s"| ${fl.offset} | `${fl.name}` | ${fl.bytes} | ${fl.doc} |").mkString("\n")
    }.mkString("\n\n")
    s"""<!-- $header -->
       @# The host-link contract, version ${Contract.Version}
       @
       @Everything the card's RTL and the host backend (`cardd`) agree on. Source:
       @[Contract.scala](../src/main/scala/card/contract/Contract.scala).
       @
       @## Messages
       @
       @Records (card to host, into the host inbox) and commands (host to card, into the BAR0 command
       @ring) are ${Message.Size} bytes, little-endian. Bytes 0-3 hold the sequence number (1, 2, 3, ... per
       @stream, never 0), 4-5 the kind, 6-7 the slot, and 60-63 the sequence number again: a message whose
       @two copies differ is torn, and one whose number is not the next expected is stale.
       @
       @## Records
       @
       @${layoutTable(Contract.records)}
       @
       @## Commands
       @
       @${layoutTable(Contract.commands)}
       @
       @## BAR0 (${Bar0.Size / 1024} KiB)
       @
       @${regTable(Bar0.regs)}
       @
       @The command ring is at `${hex(Bar0.CmdRing)}`: ${1 << Bar0.CmdEntriesLog2} entries of ${Message.Size} bytes.
       @Slot programming registers are at `${hex(Bar0.SlotProgBase)} + slot * ${hex(Bar0.SlotProgStride)}`:
       @
       @${regTable(Bar0.slotRegs)}
       @
       @Every other BAR0 offset reads as 0 and ignores writes.
       @
       @## virtio-mmio (what the guest sees at each slot)
       @
       @${regTable(V.regs)}
       @
       @## The guest's address map
       @
       @| | Base | Size |
       @|---|---|---|
       @| CLINT | `${hex(M.Clint)}` | `${hex(M.ClintSize)}` |
       @| PLIC | `${hex(M.Plic)}` | `${hex(M.PlicSize)}` |
       @| 16550 UART (IRQ ${M.UartIrq}) | `${hex(M.Uart)}` | `${hex(M.UartSize)}` |
       @| virtio-mmio slots 0-${M.VirtioSlots - 1} (IRQ 1-${M.VirtioSlots}) | `${hex(M.VirtioBase)}` | `${hex(M.VirtioStride)}` each |
       @| Ring region (block RAM, uncached) | `${hex(M.RingRegion)}` | `${hex(M.RingRegionSize)}` |
       @| RAM (DDR3 channel A) | `${hex(M.Ram)}` | `${hex(M.RamSize)}` |
       @| DMA region (DDR3 channel B, uncached) | `${hex(M.DmaRegion)}` | `${hex(M.DmaRegionSize)}` |
       @""".stripMargin('@')
  }
}
