package card

import spinal.core._
import spinal.lib._
import card.contract.{Contract, Layout}
import Contract.{Bar0, Message, VirtioMmio => V}

// The card side of the host link (SOC-ROADMAP.md, S2): virtio-mmio shims the guest sees, and the
// BAR0 mailbox the host sees. Every value and layout comes from the contract.

/** A simple register bus: a transfer happens in the cycle where valid and ready are both high; for a
  * read, rdata is valid in that cycle. The SoC wraps it in TileLink (guest) or the PCIe BAR (host). */
case class RegBus(addressWidth: Int) extends Bundle with IMasterSlave {
  val valid = Bool()
  val write = Bool()
  val address = UInt(addressWidth bits)
  val wdata = Bits(32 bits)
  val ready = Bool()
  val rdata = Bits(32 bits)
  override def asMaster(): Unit = { out(valid, write, address, wdata); in(ready, rdata) }
  def fire: Bool = valid && ready
}

/** A host write to a slot's programming registers (offset within the slot's 0x100 bytes). */
case class ProgWrite() extends Bundle {
  val address = UInt(8 bits)
  val data = Bits(32 bits)
}

/** A posted write of one 64-byte record into host memory. */
case class HostWrite() extends Bundle {
  val address = UInt(64 bits)
  val data = Bits(8 * Message.Size bits)
}

object Pack {
  /** A message body, sequence numbers left zero, with `layout`'s kind, the slot and the fields. */
  def apply(layout: Layout, slot: UInt, fields: (String, Bits)*): Bits = {
    val b = Bits(8 * Message.Size bits)
    b := 0
    def at(o: Int, n: Int) = b(8 * (o + n) - 1 downto 8 * o)
    at(Message.KindOffset, 2) := B(layout.kind, 16 bits)
    at(Message.SlotOffset, 2) := slot.asBits.resized
    for ((n, v) <- fields) { val f = layout.field(n); at(f.offset, f.bytes) := v.resized }
    b
  }
  def field(layout: Layout, b: Bits, n: String): Bits = { val f = layout.field(n); b(8 * (f.offset + f.bytes) - 1 downto 8 * f.offset) }
  def seqHead(b: Bits): Bits = b(8 * (Message.SeqOffset + 4) - 1 downto 8 * Message.SeqOffset)
  def seqTail(b: Bits): Bits = b(8 * (Message.TailOffset + 4) - 1 downto 8 * Message.TailOffset)
  def kind(b: Bits): Bits = b(8 * (Message.KindOffset + 2) - 1 downto 8 * Message.KindOffset)
  def slot(b: Bits): Bits = b(8 * (Message.SlotOffset + 2) - 1 downto 8 * Message.SlotOffset)
}

/** One virtio-mmio v2 slot. The guest's reads are answered here, from values the host programmed;
  * the writes the host must see become records. */
case class VirtioMmioShim(slot: Int) extends Component {
  val io = new Bundle {
    val guest = slave(RegBus(12))
    val prog = slave(Flow(ProgWrite()))
    val setInterrupt = slave(Flow(Bits(32 bits)))
    val records = master(Stream(Bits(8 * Message.Size bits)))
    val irq = out Bool ()
  }
  private val slotId = U(slot, 16 bits)
  private def r(n: String) = U(V.reg(n), 12 bits)
  private def p(n: String) = U(Bar0.slotReg(n), 8 bits)

  // ---- programmed by the host, through BAR0 ----
  val deviceId = Reg(Bits(32 bits)) init 0
  val features = Reg(Bits(64 bits)) init 0
  val queueNumMax = Reg(Bits(32 bits)) init 0
  val config = Vec.fill(V.ConfigBytes / 4)(Reg(Bits(32 bits)) init 0)
  when(io.prog.valid) {
    switch(io.prog.address) {
      is(p("DEVICE_ID")) { deviceId := io.prog.data }
      is(p("FEATURES_LO")) { features(31 downto 0) := io.prog.data }
      is(p("FEATURES_HI")) { features(63 downto 32) := io.prog.data }
      is(p("QUEUE_NUM_MAX")) { queueNumMax := io.prog.data }
    }
    val c = io.prog.address - Bar0.slotReg("CONFIG")
    when(io.prog.address >= Bar0.slotReg("CONFIG") && c < V.ConfigBytes) { config(c(5 downto 2)) := io.prog.data }
  }

  // ---- the guest's registers ----
  case class Queue() extends Bundle {
    val num = Bits(16 bits)
    val ready = Bool()
    val desc, driver, device = Bits(64 bits)
  }
  val deviceFeaturesSel = Reg(Bits(32 bits)) init 0
  val driverFeaturesSel = Reg(Bits(32 bits)) init 0
  val driverFeatures = Reg(Bits(64 bits)) init 0
  val queueSel = Reg(UInt(32 bits)) init 0
  val status = Reg(Bits(8 bits)) init 0
  val interruptStatus = Reg(Bits(32 bits)) init 0
  val queues = Vec.fill(V.QueuesPerSlot)(Reg(Queue()))
  queues.foreach { q => q.num.init(0); q.ready.init(False); q.desc.init(0); q.driver.init(0); q.device.init(0) }
  val selValid = queueSel < V.QueuesPerSlot
  val q = queues(queueSel(log2Up(V.QueuesPerSlot) - 1 downto 0))

  // ---- records: a write may produce two (FEATURES then STATUS), so the bus waits for room ----
  val fifo = StreamFifo(Bits(8 * Message.Size bits), 4)
  io.records << fifo.io.pop
  val pending = Reg(Bool()) init False
  val pendingData = Reg(Bits(8 * Message.Size bits))
  val emit = Flow(Bits(8 * Message.Size bits))
  emit.valid := False
  emit.payload := 0
  fifo.io.push.valid := pending || emit.valid
  fifo.io.push.payload := pending ? pendingData | emit.payload
  when(pending && fifo.io.push.ready) { pending := False }
  io.guest.ready := !pending && fifo.io.availability >= 2

  // ---- writes ----
  val ack = Bits(32 bits)
  ack := 0
  val w = io.guest.wdata
  when(io.guest.fire && io.guest.write) {
    switch(io.guest.address) {
      is(r("DeviceFeaturesSel")) { deviceFeaturesSel := w }
      is(r("DriverFeaturesSel")) { driverFeaturesSel := w }
      is(r("DriverFeatures")) {
        when(driverFeaturesSel === 0) { driverFeatures(31 downto 0) := w }
        when(driverFeaturesSel === 1) { driverFeatures(63 downto 32) := w }
      }
      is(r("QueueSel")) { queueSel := w.asUInt }
      is(r("QueueNum")) { when(selValid) { q.num := w(15 downto 0) } }
      is(r("QueueDescLow")) { when(selValid) { q.desc(31 downto 0) := w } }
      is(r("QueueDescHigh")) { when(selValid) { q.desc(63 downto 32) := w } }
      is(r("QueueDriverLow")) { when(selValid) { q.driver(31 downto 0) := w } }
      is(r("QueueDriverHigh")) { when(selValid) { q.driver(63 downto 32) := w } }
      is(r("QueueDeviceLow")) { when(selValid) { q.device(31 downto 0) := w } }
      is(r("QueueDeviceHigh")) { when(selValid) { q.device(63 downto 32) := w } }
      is(r("QueueReady")) {
        when(selValid) {
          q.ready := w(0)
          emit.valid := True
          emit.payload := Pack(Contract.record("QUEUE"), slotId, "queue" -> queueSel.asBits, "size" -> q.num,
            "ready" -> w(0).asBits, "desc" -> q.desc, "driver" -> q.driver, "device" -> q.device)
        }
      }
      is(r("QueueNotify")) {
        emit.valid := True
        emit.payload := Pack(Contract.record("NOTIFY"), slotId, "queue" -> w(15 downto 0))
      }
      is(r("InterruptACK")) { ack := w }
      is(r("Status")) {
        val statusRecord = Pack(Contract.record("STATUS"), slotId, "status" -> w(7 downto 0))
        status := w(7 downto 0)
        emit.valid := True
        when(w(log2Up(V.FeaturesOk)) && !status(log2Up(V.FeaturesOk))) {
          // FEATURES_OK being set: the host learns the accepted features first
          emit.payload := Pack(Contract.record("FEATURES"), slotId, "features" -> driverFeatures)
          pending := True
          pendingData := statusRecord
        } otherwise {
          emit.payload := statusRecord
        }
        when(w === 0) { // device reset
          driverFeatures := 0
          deviceFeaturesSel := 0
          driverFeaturesSel := 0
          queueSel := 0
          queues.foreach(_.ready := False)
          ack := B(32 bits, default -> True)
        }
      }
    }
  }
  interruptStatus := (interruptStatus & ~ack) | (io.setInterrupt.valid ? io.setInterrupt.payload | B(0, 32 bits))
  io.irq := interruptStatus.orR

  // ---- reads ----
  io.guest.rdata := 0
  switch(io.guest.address) {
    is(r("MagicValue")) { io.guest.rdata := B(V.MagicValue, 32 bits) }
    is(r("Version")) { io.guest.rdata := B(V.VersionValue, 32 bits) }
    is(r("DeviceID")) { io.guest.rdata := deviceId }
    is(r("VendorID")) { io.guest.rdata := B(V.VendorId, 32 bits) }
    is(r("DeviceFeatures")) {
      when(deviceFeaturesSel === 0) { io.guest.rdata := features(31 downto 0) }
      when(deviceFeaturesSel === 1) { io.guest.rdata := features(63 downto 32) }
    }
    is(r("QueueNumMax")) { when(selValid) { io.guest.rdata := queueNumMax } }
    is(r("QueueReady")) { when(selValid) { io.guest.rdata := q.ready.asBits.resized } }
    is(r("InterruptStatus")) { io.guest.rdata := interruptStatus }
    is(r("Status")) { io.guest.rdata := status.resized }
  }
  val cfg = io.guest.address - V.reg("Config")
  when(io.guest.address >= V.reg("Config") && cfg < V.ConfigBytes) { io.guest.rdata := config(cfg(5 downto 2)) }
}

/** BAR0: the registers, the command ring and its processor, and the writer that puts records into the
  * host inbox. Commands not implemented yet (and malformed ones) are refused: STATUS bit 2. */
case class HostLink(slots: Int) extends Component {
  val io = new Bundle {
    val host = slave(RegBus(16))
    val prog = Vec(master(Flow(ProgWrite())), slots)
    val setInterrupt = Vec(master(Flow(Bits(32 bits))), slots)
    val records = Vec(slave(Stream(Bits(8 * Message.Size bits))), slots)
    val hostWrite = master(Stream(HostWrite()))
  }
  private def r(n: String) = U(Bar0.reg(n), 16 bits)
  private val entries = 1 << Bar0.CmdEntriesLog2
  private val wordsPerMsg = Message.Size / 4

  val inboxAddr = Reg(Bits(64 bits)) init 0
  val inboxLog2 = Reg(UInt(5 bits)) init 0
  val inboxConsumed = Reg(UInt(32 bits)) init 0
  val stagingAddr = Reg(Bits(64 bits)) init 0
  val stagingSize = Reg(Bits(32 bits)) init 0
  val cmdProduced = Reg(UInt(32 bits)) init 0
  val enable = Reg(Bool()) init False
  val refused = Reg(Bool()) init False
  val full = Bool()

  // ---- the host's accesses: every one completes in its cycle ----
  val ring = Mem(Bits(32 bits), entries * wordsPerMsg)
  val a = io.host.address
  val write = io.host.fire && io.host.write
  io.host.ready := True
  io.host.rdata := 0
  switch(a) {
    is(r("MAGIC")) { io.host.rdata := B(Contract.Magic, 32 bits) }
    is(r("VERSION")) { io.host.rdata := B(Contract.Version, 32 bits) }
    is(r("SLOTS")) { io.host.rdata := B(slots, 32 bits) }
    is(r("CMD_ENTRIES_LOG2")) { io.host.rdata := B(Bar0.CmdEntriesLog2, 32 bits) }
    is(r("STATUS")) { io.host.rdata := (refused ## full ## enable).resized }
  }
  when(write) {
    switch(a) {
      is(r("INBOX_ADDR_LO")) { inboxAddr(31 downto 0) := io.host.wdata }
      is(r("INBOX_ADDR_HI")) { inboxAddr(63 downto 32) := io.host.wdata }
      is(r("INBOX_ENTRIES_LOG2")) { inboxLog2 := io.host.wdata.asUInt.resized }
      is(r("INBOX_CONSUMED")) { inboxConsumed := io.host.wdata.asUInt }
      is(r("STAGING_ADDR_LO")) { stagingAddr(31 downto 0) := io.host.wdata }
      is(r("STAGING_ADDR_HI")) { stagingAddr(63 downto 32) := io.host.wdata }
      is(r("STAGING_SIZE")) { stagingSize := io.host.wdata }
      is(r("CMD_PRODUCED")) { cmdProduced := io.host.wdata.asUInt }
      is(r("ENABLE")) { enable := io.host.wdata(0) }
    }
  }
  val inRing = a >= Bar0.CmdRing && a < Bar0.CmdRing + entries * Message.Size
  ring.write((a - Bar0.CmdRing)(log2Up(entries * Message.Size) - 1 downto 2), io.host.wdata, write && inRing)
  val progOffset = a - Bar0.SlotProgBase
  val inProg = a >= Bar0.SlotProgBase && a < Bar0.SlotProgBase + slots * Bar0.SlotProgStride
  for (s <- 0 until slots) {
    io.prog(s).valid := write && inProg && (progOffset >> 8) === s
    io.prog(s).payload.address := progOffset(7 downto 0)
    io.prog(s).payload.data := io.host.wdata
  }

  // ---- the command processor: read an entry, check it, act, acknowledge ----
  val cmdNext = Reg(UInt(32 bits)) init 1
  val bufWords = Vec.fill(wordsPerMsg)(Reg(Bits(32 bits)))
  val buf = bufWords.asBits // word 0 in the low bits, as the message lays bytes out
  val word = Reg(UInt(log2Up(wordsPerMsg + 1) bits)) init 0
  val ack = Stream(Bits(8 * Message.Size bits))
  ack.valid := False
  ack.payload := Pack(Contract.record("CMD_ACK"), U(0, 16 bits), "cmd_seq" -> (cmdNext - 1).asBits)
  io.setInterrupt.foreach { f => f.valid := False; f.payload := 0 }
  val waiting = (cmdProduced - (cmdNext - 1)) =/= 0 && !(cmdProduced - (cmdNext - 1)).msb
  val readAddr = UInt(log2Up(entries * wordsPerMsg) bits)
  readAddr := (((cmdNext - 1) & (entries - 1)) * wordsPerMsg + word.resize(32 bits)).resized
  val readData = ring.readSync(readAddr)
  object State extends SpinalEnum { val IDLE, READ, EXEC, ACK = newElement() }
  val state = Reg(State()) init State.IDLE
  switch(state) {
    is(State.IDLE) { word := 0; when(enable && waiting) { state := State.READ } }
    is(State.READ) {
      // readSync returns the word addressed in the previous cycle
      when(word =/= 0) { bufWords((word - 1).resize(log2Up(wordsPerMsg) bits)) := readData }
      word := word + 1
      when(word === wordsPerMsg) { state := State.EXEC }
    }
    is(State.EXEC) {
      val ok = Pack.seqHead(buf).asUInt === cmdNext && Pack.seqTail(buf).asUInt === cmdNext
      val s = Pack.slot(buf).asUInt
      val isInterrupt = Pack.kind(buf).asUInt === Contract.command("INTERRUPT").kind
      when(ok && isInterrupt && s < slots) {
        for (i <- 0 until slots) when(s === i) {
          io.setInterrupt(i).valid := True
          io.setInterrupt(i).payload := Pack.field(Contract.command("INTERRUPT"), buf, "bits")
        }
      } otherwise { refused := True }
      cmdNext := cmdNext + 1
      state := State.ACK
    }
    is(State.ACK) { ack.valid := True; when(ack.ready) { state := State.IDLE } }
  }

  // ---- the inbox writer: one stream of records, numbered 1, 2, 3, ..., never overrunning the host ----
  val arbiter = StreamArbiterFactory().roundRobin.on(io.records.toSeq :+ ack)
  val seq = Reg(UInt(32 bits)) init 1
  when(write && a === r("ENABLE") && io.host.wdata(0) && !enable) { seq := 1 }
  val outstanding = seq - 1 - inboxConsumed
  val capacity = (U(1, 33 bits) << inboxLog2).resize(33 bits)
  val space = outstanding.resize(33 bits) < capacity
  full := enable && arbiter.valid && !space
  io.hostWrite.valid := arbiter.valid && enable && space
  arbiter.ready := io.hostWrite.ready && enable && space
  val index = (seq - 1) & (capacity - 1).resize(32 bits)
  io.hostWrite.payload.address := inboxAddr.asUInt + (index.resize(64 bits) |<< log2Up(Message.Size))
  val data = Bits(8 * Message.Size bits)
  data := arbiter.payload
  Pack.seqHead(data) := seq.asBits
  Pack.seqTail(data) := seq.asBits
  io.hostWrite.payload.data := data
  when(io.hostWrite.fire) { seq := seq + 1 }
}

/** The host link's top level: `slots` virtio-mmio shims and the BAR0 mailbox. */
case class CardLink(slots: Int = Contract.Map.VirtioSlots) extends Component {
  val io = new Bundle {
    /** The guest's accesses, relative to the first slot: slot in the bits above 12. */
    val guest = slave(RegBus(log2Up(slots) + 12))
    val host = slave(RegBus(16))
    val hostWrite = master(Stream(HostWrite()))
    val irq = out Bits (slots bits)
  }
  val shims = (0 until slots).map(VirtioMmioShim(_))
  val link = HostLink(slots)
  link.io.host <> io.host
  io.hostWrite << link.io.hostWrite
  val sel = io.guest.address(io.guest.address.high downto 12)
  for ((sh, i) <- shims.zipWithIndex) {
    sh.io.guest.valid := io.guest.valid && sel === i
    sh.io.guest.write := io.guest.write
    sh.io.guest.address := io.guest.address(11 downto 0)
    sh.io.guest.wdata := io.guest.wdata
    sh.io.prog << link.io.prog(i)
    sh.io.setInterrupt << link.io.setInterrupt(i)
    link.io.records(i) << sh.io.records
    io.irq(i) := sh.io.irq
  }
  io.guest.ready := Vec(shims.map(_.io.guest.ready))(sel)
  io.guest.rdata := Vec(shims.map(_.io.guest.rdata))(sel)
}
