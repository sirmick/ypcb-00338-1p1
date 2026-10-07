package card.contract

// The host-link contract: the one definition of everything the card's RTL and the host backend
// (cardd) must agree on. The RTL uses these values directly; Generate.scala writes them out as Rust
// (cardd/src/contract.rs), a device-tree fragment (gen/contract.dtsi), a readable table
// (gen/CONTRACT.md) and test transcripts (cardd/tests/generated/transcripts.rs). Change it here, then run
// `sbt "runMain card.contract.Generate"`; `sbt test` fails if a generated file is stale.
//
// Rules the layout follows (see SOC-ROADMAP.md, "The design in one page"):
//  - each side's inbox lives in its own memory, and only posted writes cross the link;
//  - records (card to host, into host memory) and commands (host to card, into the BAR0 command
//    ring) are 64 bytes, one cache line, little-endian, with the sequence number at both ends so a
//    torn or stale one is visible;
//  - the host never receives a guest pointer it has to trust: guest addresses in commands are
//    checked against the card's DMA windows before anything moves.

case class Field(name: String, offset: Int, bytes: Int, doc: String) {
  require(offset >= 8 && offset + bytes <= Contract.Message.TailOffset, s"$name overlaps the header or tail")
}

case class Layout(name: String, kind: Int, doc: String, fields: Seq[Field]) {
  fields.combinations(2).foreach { case Seq(a, b) =>
    require(a.offset + a.bytes <= b.offset || b.offset + b.bytes <= a.offset, s"$name: ${a.name} overlaps ${b.name}")
  }
  def field(n: String): Field = fields.find(_.name == n).getOrElse(sys.error(s"$name has no field $n"))
}

case class Reg(name: String, offset: Int, access: String, doc: String)

object Contract {
  val Version = 1
  // "CARD" in ASCII, as BAR0 offset 0 reads it on a little-endian host
  val Magic = 0x44524143L

  // ---- The guest's address map (QEMU virt's, so firmware and kernels need only a device tree) ----
  object Map {
    val Clint = 0x02000000L;      val ClintSize = 0x10000L
    val Plic = 0x0c000000L;       val PlicSize = 0x600000L
    val Uart = 0x10000000L;       val UartSize = 0x100L;   val UartIrq = 10
    val VirtioBase = 0x10001000L; val VirtioStride = 0x1000L; val VirtioSlots = 8
    def virtioIrq(slot: Int): Int = 1 + slot
    val RingRegion = 0x30000000L; val RingRegionSize = 0x10000L      // block RAM, uncached
    val Ram = 0x80000000L;        val RamSize = 0x80000000L          // DDR3 channel A
    val DmaRegion = 0x100000000L; val DmaRegionSize = 0x80000000L    // DDR3 channel B, DMA only, uncached
  }

  // ---- virtio-mmio v2: the register file each shim presents to the guest (virtio 1.2, 4.2.2) ----
  object VirtioMmio {
    val MagicValue = 0x74726976L // "virt"
    val VersionValue = 2
    val VendorId = 0x44524143L   // Magic, reused
    val regs = Seq(
      Reg("MagicValue", 0x000, "R", "0x74726976"),
      Reg("Version", 0x004, "R", "2"),
      Reg("DeviceID", 0x008, "R", "virtio device type, programmed by the host"),
      Reg("VendorID", 0x00c, "R", "programmed by the host"),
      Reg("DeviceFeatures", 0x010, "R", "32 bits of the host's features, selected by DeviceFeaturesSel"),
      Reg("DeviceFeaturesSel", 0x014, "W", ""),
      Reg("DriverFeatures", 0x020, "W", "forwarded as a FEATURES record when the driver sets FEATURES_OK"),
      Reg("DriverFeaturesSel", 0x024, "W", ""),
      Reg("QueueSel", 0x030, "W", ""),
      Reg("QueueNumMax", 0x034, "R", "programmed by the host"),
      Reg("QueueNum", 0x038, "W", ""),
      Reg("QueueReady", 0x044, "RW", "a write of 1 forwards a QUEUE record"),
      Reg("QueueNotify", 0x050, "W", "forwarded as a NOTIFY record"),
      Reg("InterruptStatus", 0x060, "R", "set by the host's INTERRUPT command; drives the slot's PLIC line"),
      Reg("InterruptACK", 0x064, "W", "clears InterruptStatus bits"),
      Reg("Status", 0x070, "RW", "every write is forwarded as a STATUS record; 0 resets the device"),
      Reg("QueueDescLow", 0x080, "W", ""), Reg("QueueDescHigh", 0x084, "W", ""),
      Reg("QueueDriverLow", 0x090, "W", ""), Reg("QueueDriverHigh", 0x094, "W", ""),
      Reg("QueueDeviceLow", 0x0a0, "W", ""), Reg("QueueDeviceHigh", 0x0a4, "W", ""),
      Reg("ConfigGeneration", 0x0fc, "R", "0"),
      Reg("Config", 0x100, "R", "device configuration space, programmed by the host (64 bytes)")
    )
    def reg(n: String): Int = regs.find(_.name == n).getOrElse(sys.error(s"no virtio-mmio register $n")).offset
    val ConfigBytes = 64
    val QueuesPerSlot = 2
    // Status bits (virtio 1.2, 2.1)
    val Acknowledge = 1; val Driver = 2; val DriverOk = 4; val FeaturesOk = 8; val Failed = 128
    // Feature bits the shims may offer
    val FeatureEventIdx = 29; val FeatureVersion1 = 32; val FeatureAccessPlatform = 33
    // Device types
    val DeviceNet = 1; val DeviceBlk = 2
  }

  // ---- BAR0: the host's window into the card (a mailbox, and nothing else) ----
  object Bar0 {
    val Size = 0x10000
    val regs = Seq(
      Reg("MAGIC", 0x000, "R", "0x44524143, \"CARD\""),
      Reg("VERSION", 0x004, "R", "contract version"),
      Reg("SLOTS", 0x008, "R", "number of virtio-mmio slots"),
      Reg("CMD_ENTRIES_LOG2", 0x00c, "R", "command ring entries, log2"),
      Reg("INBOX_ADDR_LO", 0x010, "W", "host inbox base (host bus address, 64-byte aligned)"),
      Reg("INBOX_ADDR_HI", 0x014, "W", ""),
      Reg("INBOX_ENTRIES_LOG2", 0x018, "W", "host inbox entries, log2"),
      Reg("INBOX_CONSUMED", 0x01c, "W", "sequence number of the last record the host has consumed"),
      Reg("STAGING_ADDR_LO", 0x020, "W", "host staging base (host bus address)"),
      Reg("STAGING_ADDR_HI", 0x024, "W", ""),
      Reg("STAGING_SIZE", 0x028, "W", "host staging size in bytes"),
      Reg("CMD_PRODUCED", 0x030, "W", "doorbell: sequence number of the last command written"),
      Reg("ENABLE", 0x034, "W", "1 once the registers above are set; 0 stops the card's writes"),
      Reg("STATUS", 0x038, "R", "bit 0 enabled, bit 1 inbox full, bit 2 a command was refused")
    )
    def reg(n: String): Int = regs.find(_.name == n).getOrElse(sys.error(s"no BAR0 register $n")).offset
    val CmdRing = 0x1000
    val CmdEntriesLog2 = 6
    val SlotProgBase = 0x4000
    val SlotProgStride = 0x100
    // Per-slot programming registers, relative to SlotProgBase + slot * SlotProgStride. The host
    // writes them once, before the guest looks; the guest's reads of the shim never cross the link.
    val slotRegs = Seq(
      Reg("DEVICE_ID", 0x00, "W", "0 leaves the slot empty"),
      Reg("FEATURES_LO", 0x08, "W", "device features, bits 0-31"),
      Reg("FEATURES_HI", 0x0c, "W", "device features, bits 32-63"),
      Reg("QUEUE_NUM_MAX", 0x10, "W", "largest queue size the device accepts"),
      Reg("CONFIG", 0x40, "W", "device configuration space (64 bytes)")
    )
    def slotReg(n: String): Int = slotRegs.find(_.name == n).getOrElse(sys.error(s"no slot register $n")).offset
    require(CmdRing + (64 << CmdEntriesLog2) <= SlotProgBase && SlotProgBase + Map.VirtioSlots * SlotProgStride <= Size)
  }

  // ---- Records and commands: 64 bytes, header at 0..8, sequence copy at 60..64 ----
  object Message {
    val Size = 64
    val SeqOffset = 0      // u32, never 0 for a valid message; 1, 2, 3, ... per stream
    val KindOffset = 4     // u16
    val SlotOffset = 6     // u16
    val TailOffset = 60    // u32, equal to the sequence number
  }
  private def f(n: String, o: Int, b: Int, d: String = "") = Field(n, o, b, d)

  // Card to host: written by the card into the host inbox (pinned host memory).
  val records = Seq(
    Layout("NOTIFY", 1, "the guest wrote QueueNotify", Seq(f("queue", 8, 2))),
    Layout("STATUS", 2, "the guest wrote Status (0 is a device reset)", Seq(f("status", 8, 1))),
    Layout("QUEUE", 3, "the guest set QueueReady: the queue's size and ring addresses (guest addresses)", Seq(
      f("queue", 8, 2), f("size", 10, 2), f("ready", 12, 1),
      f("desc", 16, 8), f("driver", 24, 8, "available ring"), f("device", 32, 8, "used ring"))),
    Layout("FEATURES", 4, "the guest set FEATURES_OK: the features it accepted", Seq(f("features", 8, 8))),
    Layout("COPY_DONE", 5, "a copy command finished", Seq(
      f("tag", 8, 4), f("status", 12, 2, "0 done; 1 outside the DMA windows; 2 bad length or outside staging; 3 misaligned"))),
    Layout("CONSOLE_TX", 6, "bytes the guest sent to the 16550", Seq(f("count", 8, 1), f("data", 12, 48))),
    Layout("CMD_ACK", 7, "commands consumed up to this sequence number", Seq(f("cmd_seq", 8, 4)))
  )

  // Host to card: written by the host into the BAR0 command ring, then CMD_PRODUCED.
  // Copies move whole 64-byte lines with byte masks, so a copy's staging offset must lie at the same
  // place in its line as the guest address (staging % 64 == guest % 64); otherwise COPY_DONE says 3.
  // A guest range must lie wholly inside one DMA window: the DMA region (channel B) or the ring region.
  /** Copy status codes (COPY_DONE's `status`). */
  object CopyStatus { val Done = 0; val OutsideWindow = 1; val BadLength = 2; val Misaligned = 3 }
  /** The DMA windows a guest range may lie in (S8 makes them programmable by the guest's kernel). */
  val dmaWindows = Seq((Map.DmaRegion, Map.DmaRegionSize), (Map.RingRegion, Map.RingRegionSize))

  val commands = Seq(
    Layout("COPY_TO_HOST", 1, "copy a guest range (checked against the window) into host staging", Seq(
      f("tag", 8, 4), f("len", 12, 4), f("guest", 16, 8), f("staging", 24, 4, "offset in host staging"))),
    Layout("COPY_FROM_HOST", 2, "copy from host staging into a guest range (checked against the window)", Seq(
      f("tag", 8, 4), f("len", 12, 4), f("guest", 16, 8), f("staging", 24, 4))),
    Layout("USED_PUSH", 3, "append {id, len} to a ready queue's used ring, then publish the new used index; refused if the ring lies outside the windows", Seq(
      f("queue", 8, 2), f("id", 12, 4), f("len", 16, 4))),
    Layout("INTERRUPT", 4, "set InterruptStatus bits (1 used buffer, 2 configuration change)", Seq(f("bits", 8, 4))),
    Layout("CONSOLE_RX", 5, "bytes for the 16550's receive FIFO", Seq(f("count", 8, 1), f("data", 12, 48)))
  )

  def record(n: String): Layout = records.find(_.name == n).getOrElse(sys.error(s"no record $n"))
  def command(n: String): Layout = commands.find(_.name == n).getOrElse(sys.error(s"no command $n"))

  /** The 64 bytes of a message, as both sides must lay it out. */
  def encode(l: Layout, seq: Long, slot: Int, values: Map[String, BigInt]): Array[Byte] = {
    val b = new Array[Byte](Message.Size)
    def put(off: Int, bytes: Int, v: BigInt): Unit = for (i <- 0 until bytes) b(off + i) = ((v >> (8 * i)) & 0xff).toByte
    put(Message.SeqOffset, 4, seq); put(Message.KindOffset, 2, l.kind); put(Message.SlotOffset, 2, slot)
    put(Message.TailOffset, 4, seq)
    values.foreach { case (n, v) => val fl = l.field(n); require(v >= 0 && v.bitLength <= 8 * fl.bytes, s"$n=$v too wide"); put(fl.offset, fl.bytes, v) }
    b
  }

  require(records.map(_.kind).distinct.size == records.size && commands.map(_.kind).distinct.size == commands.size)
}
