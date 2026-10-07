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

/** A posted write of one 64-byte line into host memory (a record, or copied data); `mask` has a bit
  * per byte written. `address` is 64-byte aligned. */
case class HostWrite() extends Bundle {
  val address = UInt(64 bits)
  val data = Bits(8 * Message.Size bits)
  val mask = Bits(Message.Size bits)
}

/** A request on the card's guest-memory port (DDR3 channel B and the ring region): one 64-byte
  * line, `address` 64-byte aligned. Every request gets one response, data for a read. */
case class MemCmd() extends Bundle {
  val write = Bool()
  val address = UInt(64 bits)
  val data = Bits(8 * Message.Size bits)
  val mask = Bits(Message.Size bits)
}

/** What the command processor needs to know about one of a slot's queues. */
case class QueueInfo() extends Bundle {
  val size = UInt(16 bits)
  val ready = Bool()
  val used = UInt(64 bits)
  val usedIdx = UInt(16 bits)
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
    val queueInfo = out(Vec.fill(V.QueuesPerSlot)(QueueInfo()))
    /** a USED_PUSH published one more used entry on this queue */
    val usedAdvance = slave(Flow(UInt(log2Up(V.QueuesPerSlot) bits)))
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
  // the used index the card has published on each queue: restarts when the queue is (re)enabled
  val usedIdx = Vec.fill(V.QueuesPerSlot)(Reg(UInt(16 bits)) init 0)
  when(io.usedAdvance.valid) { usedIdx(io.usedAdvance.payload) := usedIdx(io.usedAdvance.payload) + 1 }
  for (i <- 0 until V.QueuesPerSlot) {
    io.queueInfo(i).size := queues(i).num.asUInt
    io.queueInfo(i).ready := queues(i).ready
    io.queueInfo(i).used := queues(i).device.asUInt
    io.queueInfo(i).usedIdx := usedIdx(i)
  }

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
          usedIdx(queueSel(log2Up(V.QueuesPerSlot) - 1 downto 0)) := U(0, 16 bits)
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
          usedIdx.foreach(_ := U(0, 16 bits))
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
  * host inbox. The processor executes INTERRUPT, the two copies and USED_PUSH, each as a sequence of
  * states (one step per clock); a malformed command is refused (STATUS bit 2) and still acknowledged. */
case class HostLink(slots: Int) extends Component {
  val io = new Bundle {
    val host = slave(RegBus(16))
    val prog = Vec(master(Flow(ProgWrite())), slots)
    val setInterrupt = Vec(master(Flow(Bits(32 bits))), slots)
    val records = Vec(slave(Stream(Bits(8 * Message.Size bits))), slots)
    val queueInfo = in(Vec.fill(slots)(Vec.fill(V.QueuesPerSlot)(QueueInfo())))
    val usedAdvance = Vec(master(Flow(UInt(log2Up(V.QueuesPerSlot) bits))), slots)
    val hostWrite = master(Stream(HostWrite()))
    val hostRead = master(Stream(UInt(64 bits)))
    val hostReadRsp = slave(Stream(Bits(8 * Message.Size bits)))
    val mem = master(Stream(MemCmd()))
    val memRsp = slave(Stream(Bits(8 * Message.Size bits)))
  }
  private def r(n: String) = U(Bar0.reg(n), 16 bits)
  private val entries = 1 << Bar0.CmdEntriesLog2
  private val wordsPerMsg = Message.Size / 4
  private val lineBits = log2Up(Message.Size) // copies and memory move 64-byte lines

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

  // ---- the command processor ----
  val cmdNext = Reg(UInt(32 bits)) init 1
  val bufWords = Vec.fill(wordsPerMsg)(Reg(Bits(32 bits)))
  val buf = bufWords.asBits // word 0 in the low bits, as the message lays bytes out
  val word = Reg(UInt(log2Up(wordsPerMsg + 1) bits)) init 0
  val waiting = (cmdProduced - (cmdNext - 1)) =/= 0 && !(cmdProduced - (cmdNext - 1)).msb
  val readAddr = UInt(log2Up(entries * wordsPerMsg) bits)
  readAddr := (((cmdNext - 1) & (entries - 1)) * wordsPerMsg + word.resize(32 bits)).resized
  val readData = ring.readSync(readAddr)

  // the command being executed
  val cslot = Reg(UInt(16 bits))
  val ckind = Reg(UInt(16 bits))
  val tag = Reg(Bits(32 bits))
  val len = Reg(UInt(32 bits))
  val guest = Reg(UInt(64 bits))
  val staging = Reg(UInt(32 bits))
  val copyStatus = Reg(UInt(16 bits))
  val line = Reg(UInt(64 bits))       // the guest line being copied
  val lineData = Reg(Bits(8 * Message.Size bits))
  val pushQueue = Reg(UInt(log2Up(V.QueuesPerSlot) bits))
  val pushEntry = Reg(Bits(64 bits))  // {len, id} as the used ring stores it: id at the lower address
  val elemAddr = Reg(UInt(64 bits))
  val usedAddr = Reg(UInt(64 bits))
  val newIdx = Reg(UInt(16 bits))

  private val c2h = Contract.command("COPY_TO_HOST")
  private val h2c = Contract.command("COPY_FROM_HOST")
  private val push = Contract.command("USED_PUSH")
  private val irq = Contract.command("INTERRUPT")
  private def cf(l: Layout, n: String) = Pack.field(l, buf, n)

  // the line arithmetic of a copy: masks for the first and last lines, and the staging line
  def lineOf(x: UInt): UInt = x(63 downto lineBits) @@ U(0, lineBits bits)
  val firstLine = lineOf(guest)
  val lastByte = guest + len.resize(64 bits) - 1
  val lastLine = lineOf(lastByte)
  val isLast = line === lastLine
  val lo = (line === firstLine) ? guest(lineBits - 1 downto 0) | U(0, lineBits bits)
  val hi = isLast ? lastByte(lineBits - 1 downto 0) | U(Message.Size - 1, lineBits bits)
  val copyMask = Bits(Message.Size bits)
  for (i <- 0 until Message.Size) copyMask(i) := U(i, lineBits bits) >= lo && U(i, lineBits bits) <= hi
  val hostLine = stagingAddr.asUInt + (staging(31 downto lineBits) @@ U(0, lineBits bits)).resize(64 bits) + (line - firstLine)

  /** [x, x + n) lies wholly inside one DMA window. */
  def insideWindows(x: UInt, n: UInt): Bool = Contract.dmaWindows.map { case (base, size) =>
    val end = x.resize(65 bits) + n.resize(65 bits)
    x >= U(base, 64 bits) && end <= U(BigInt(base) + BigInt(size), 65 bits)
  }.reduce(_ || _)

  // records: COPY_DONE and CMD_ACK, in that order for a copy
  val done = Stream(Bits(8 * Message.Size bits))
  done.valid := False
  done.payload := Pack(Contract.record("COPY_DONE"), cslot, "tag" -> tag, "status" -> copyStatus.asBits)
  val ack = Stream(Bits(8 * Message.Size bits))
  ack.valid := False
  ack.payload := Pack(Contract.record("CMD_ACK"), U(0, 16 bits), "cmd_seq" -> (cmdNext - 1).asBits)
  val copyWrite = Stream(HostWrite())
  copyWrite.valid := False
  copyWrite.payload.address := hostLine
  copyWrite.payload.data := lineData
  copyWrite.payload.mask := copyMask

  io.setInterrupt.foreach { f => f.valid := False; f.payload := 0 }
  io.usedAdvance.foreach { f => f.valid := False; f.payload := pushQueue }
  io.hostRead.valid := False
  io.hostRead.payload := hostLine
  io.hostReadRsp.ready := False
  io.mem.valid := False
  io.mem.payload.write := False
  io.mem.payload.address := line
  io.mem.payload.data := lineData
  io.mem.payload.mask := copyMask
  io.memRsp.ready := False

  // used-ring writes: a pattern repeated in every 8-byte lane, and a mask that picks the bytes
  def lanes(v: Bits): Bits = Cat(Seq.fill(Message.Size / 8)(v))
  def byteMask(offset: UInt, n: Int): Bits = {
    val m = Bits(Message.Size bits)
    for (i <- 0 until Message.Size) m(i) := U(i, lineBits + 1 bits) >= offset.resize(lineBits + 1) && U(i, lineBits + 1 bits) < offset.resize(lineBits + 1) + n
    m
  }

  object S extends SpinalEnum {
    val IDLE, READ, EXEC, CHECK, C2H_READ, C2H_WAIT, C2H_WRITE, H2C_REQ, H2C_WAIT, H2C_WRITE, H2C_ACK, DONE,
        PUSH_CHECK, PUSH_ELEM, PUSH_ELEM_ACK, PUSH_ELEM2, PUSH_ELEM2_ACK, PUSH_IDX, PUSH_IDX_ACK, ACK = newElement()
  }
  val state = Reg(S()) init S.IDLE
  switch(state) {
    is(S.IDLE) { word := 0; when(enable && waiting) { state := S.READ } }
    is(S.READ) {
      // readSync returns the word addressed in the previous cycle
      when(word =/= 0) { bufWords((word - 1).resize(log2Up(wordsPerMsg) bits)) := readData }
      word := word + 1
      when(word === wordsPerMsg) { state := S.EXEC }
    }
    is(S.EXEC) {
      val ok = Pack.seqHead(buf).asUInt === cmdNext && Pack.seqTail(buf).asUInt === cmdNext
      val k = Pack.kind(buf).asUInt
      val s = Pack.slot(buf).asUInt
      cslot := s
      ckind := k
      cmdNext := cmdNext + 1
      state := S.ACK
      when(!ok || s >= slots) {
        refused := True
      } elsewhen (k === irq.kind) {
        for (i <- 0 until slots) when(s === i) {
          io.setInterrupt(i).valid := True
          io.setInterrupt(i).payload := cf(irq, "bits")
        }
      } elsewhen (k === c2h.kind || k === h2c.kind) {
        tag := cf(c2h, "tag")
        len := cf(c2h, "len").asUInt
        guest := cf(c2h, "guest").asUInt
        staging := cf(c2h, "staging").asUInt
        state := S.CHECK
      } elsewhen (k === push.kind) {
        pushQueue := cf(push, "queue").asUInt.resized
        pushEntry := cf(push, "len") ## cf(push, "id")
        when(cf(push, "queue").asUInt < V.QueuesPerSlot) { state := S.PUSH_CHECK } otherwise { refused := True }
      } otherwise {
        refused := True
      }
    }

    // ---- COPY_TO_HOST and COPY_FROM_HOST ----
    is(S.CHECK) {
      line := firstLine
      when(len === 0 || staging.resize(33 bits) + len.resize(33 bits) > stagingSize.asUInt.resize(33 bits)) {
        copyStatus := Contract.CopyStatus.BadLength; state := S.DONE
      } elsewhen (staging(lineBits - 1 downto 0) =/= guest(lineBits - 1 downto 0)) {
        copyStatus := Contract.CopyStatus.Misaligned; state := S.DONE
      } elsewhen (!insideWindows(guest, len)) {
        copyStatus := Contract.CopyStatus.OutsideWindow; state := S.DONE
      } otherwise {
        copyStatus := Contract.CopyStatus.Done
        state := (ckind === c2h.kind) ? S.C2H_READ | S.H2C_REQ
      }
    }
    is(S.C2H_READ) { io.mem.valid := True; when(io.mem.ready) { state := S.C2H_WAIT } }
    is(S.C2H_WAIT) { io.memRsp.ready := True; when(io.memRsp.valid) { lineData := io.memRsp.payload; state := S.C2H_WRITE } }
    is(S.C2H_WRITE) {
      copyWrite.valid := True
      when(copyWrite.ready) { when(isLast) { state := S.DONE } otherwise { line := line + Message.Size; state := S.C2H_READ } }
    }
    is(S.H2C_REQ) { io.hostRead.valid := True; when(io.hostRead.ready) { state := S.H2C_WAIT } }
    is(S.H2C_WAIT) { io.hostReadRsp.ready := True; when(io.hostReadRsp.valid) { lineData := io.hostReadRsp.payload; state := S.H2C_WRITE } }
    is(S.H2C_WRITE) { io.mem.valid := True; io.mem.payload.write := True; when(io.mem.ready) { state := S.H2C_ACK } }
    is(S.H2C_ACK) {
      io.memRsp.ready := True
      when(io.memRsp.valid) { when(isLast) { state := S.DONE } otherwise { line := line + Message.Size; state := S.H2C_REQ } }
    }
    is(S.DONE) { done.valid := True; when(done.ready) { state := S.ACK } }

    // ---- USED_PUSH: the entry, then (once it has landed) the index ----
    is(S.PUSH_CHECK) {
      val qi = io.queueInfo(cslot(log2Up(slots) - 1 downto 0))(pushQueue)
      val ringBytes = (qi.size.resize(32 bits) |<< 3) + 6
      val powerOfTwo = qi.size =/= 0 && (qi.size & (qi.size - 1)) === 0
      usedAddr := qi.used
      elemAddr := qi.used + 4 + ((qi.usedIdx & (qi.size - 1)).resize(64 bits) |<< 3)
      newIdx := qi.usedIdx + 1
      when(qi.ready && powerOfTwo && qi.used(2 downto 0) === 0 && insideWindows(qi.used, ringBytes)) {
        state := S.PUSH_ELEM
      } otherwise { refused := True; state := S.ACK }
    }
    is(S.PUSH_ELEM) {
      // the entry starts 4 bytes into an 8-byte lane: id in that lane's upper half, len in the next lane's lower half
      io.mem.valid := True
      io.mem.payload.write := True
      io.mem.payload.address := lineOf(elemAddr)
      io.mem.payload.data := lanes(pushEntry(31 downto 0) ## pushEntry(63 downto 32))
      io.mem.payload.mask := byteMask(elemAddr(lineBits - 1 downto 0), 8)
      when(io.mem.ready) { state := S.PUSH_ELEM_ACK }
    }
    is(S.PUSH_ELEM_ACK) {
      io.memRsp.ready := True
      // an entry at offset 60 spills its len into the next line
      when(io.memRsp.valid) { state := (elemAddr(lineBits - 1 downto 0) > Message.Size - 8) ? S.PUSH_ELEM2 | S.PUSH_IDX }
    }
    is(S.PUSH_ELEM2) {
      io.mem.valid := True
      io.mem.payload.write := True
      io.mem.payload.address := lineOf(elemAddr) + Message.Size
      io.mem.payload.data := lanes(pushEntry(31 downto 0) ## pushEntry(63 downto 32))
      io.mem.payload.mask := byteMask(U(0, lineBits bits), 4) // only an entry at offset 60 spills: its 4-byte len
      when(io.mem.ready) { state := S.PUSH_ELEM2_ACK }
    }
    is(S.PUSH_ELEM2_ACK) { io.memRsp.ready := True; when(io.memRsp.valid) { state := S.PUSH_IDX } }
    is(S.PUSH_IDX) {
      // the used index is the u16 at used + 2 (used is 8-byte aligned): bytes 2-3 of its lane
      io.mem.valid := True
      io.mem.payload.write := True
      io.mem.payload.address := lineOf(usedAddr + 2)
      io.mem.payload.data := lanes(B(0, 32 bits) ## newIdx.asBits ## B(0, 16 bits))
      io.mem.payload.mask := byteMask((usedAddr + 2)(lineBits - 1 downto 0), 2)
      when(io.mem.ready) { state := S.PUSH_IDX_ACK }
    }
    is(S.PUSH_IDX_ACK) {
      io.memRsp.ready := True
      when(io.memRsp.valid) {
        for (i <- 0 until slots) when(cslot === i) { io.usedAdvance(i).valid := True }
        state := S.ACK
      }
    }
    is(S.ACK) { ack.valid := True; when(ack.ready) { state := S.IDLE } }
  }

  // ---- the inbox writer: one stream of records, numbered 1, 2, 3, ..., never overrunning the host ----
  val arbiter = StreamArbiterFactory().roundRobin.on(io.records.toSeq ++ Seq(done, ack))
  val seq = Reg(UInt(32 bits)) init 1
  when(write && a === r("ENABLE") && io.host.wdata(0) && !enable) { seq := 1 }
  val outstanding = seq - 1 - inboxConsumed
  val capacity = (U(1, 33 bits) << inboxLog2).resize(33 bits)
  val space = outstanding.resize(33 bits) < capacity
  full := enable && arbiter.valid && !space
  val inboxWrite = Stream(HostWrite())
  inboxWrite.valid := arbiter.valid && enable && space
  arbiter.ready := inboxWrite.ready && enable && space
  val index = (seq - 1) & (capacity - 1).resize(32 bits)
  inboxWrite.payload.address := inboxAddr.asUInt + (index.resize(64 bits) |<< lineBits)
  val data = Bits(8 * Message.Size bits)
  data := arbiter.payload
  Pack.seqHead(data) := seq.asBits
  Pack.seqTail(data) := seq.asBits
  inboxWrite.payload.data := data
  inboxWrite.payload.mask := B(Message.Size bits, default -> True)
  when(inboxWrite.fire) { seq := seq + 1 }

  // copied data and records share the link; a copy's COPY_DONE follows its last data write
  io.hostWrite << StreamArbiterFactory().lowerFirst.on(Seq(copyWrite, inboxWrite))
}

/** The host link's top level: `slots` virtio-mmio shims and the BAR0 mailbox. */
case class CardLink(slots: Int = Contract.Map.VirtioSlots) extends Component {
  val io = new Bundle {
    /** The guest's accesses, relative to the first slot: slot in the bits above 12. */
    val guest = slave(RegBus(log2Up(slots) + 12))
    val host = slave(RegBus(16))
    val hostWrite = master(Stream(HostWrite()))
    val hostRead = master(Stream(UInt(64 bits)))
    val hostReadRsp = slave(Stream(Bits(8 * Message.Size bits)))
    val mem = master(Stream(MemCmd()))
    val memRsp = slave(Stream(Bits(8 * Message.Size bits)))
    val irq = out Bits (slots bits)
  }
  val shims = (0 until slots).map(VirtioMmioShim(_))
  val link = HostLink(slots)
  link.io.host <> io.host
  io.hostWrite << link.io.hostWrite
  io.hostRead << link.io.hostRead
  link.io.hostReadRsp << io.hostReadRsp
  io.mem << link.io.mem
  link.io.memRsp << io.memRsp
  val sel = io.guest.address(io.guest.address.high downto 12)
  for ((sh, i) <- shims.zipWithIndex) {
    sh.io.guest.valid := io.guest.valid && sel === i
    sh.io.guest.write := io.guest.write
    sh.io.guest.address := io.guest.address(11 downto 0)
    sh.io.guest.wdata := io.guest.wdata
    sh.io.prog << link.io.prog(i)
    sh.io.setInterrupt << link.io.setInterrupt(i)
    link.io.records(i) << sh.io.records
    link.io.queueInfo(i) := sh.io.queueInfo
    sh.io.usedAdvance << link.io.usedAdvance(i)
    io.irq(i) := sh.io.irq
  }
  io.guest.ready := Vec(shims.map(_.io.guest.ready))(sel)
  io.guest.rdata := Vec(shims.map(_.io.guest.rdata))(sel)
}
