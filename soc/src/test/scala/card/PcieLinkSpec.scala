package card

import scala.collection.mutable
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.sim.{StreamDriver, StreamMonitor, StreamReadyRandomizer}
import card.contract._
import Contract.{Bar0, Message, VirtioMmio => V}

// S4a: the host link over PCIe TLPs. A TLP-level host model stands where the hard block and the root
// complex are: it turns the card's MemWr TLPs into host memory, answers its MemRd TLPs with CplD, and
// plays the host CPU's 32-bit BAR0 accesses as MemWr/MemRd TLPs.
class PcieLinkSpec extends AnyFunSuite {
  lazy val compiled = SimConfig.withConfig(SpinalConfig(targetDirectory = "tmp")).compile(PcieLink(8))
  val Bar0Base = BigInt("f0000000", 16)
  val HostId = 0x0000
  val CardId = 0x0400 // bus 4, device 0, function 0
  val L = Message.Size
  /** Payload DWs travel with their lowest-addressed byte in bits 31:24, as the hard block delivers them. */
  def swap(dw: BigInt): BigInt = (0 until 4).map(b => ((dw >> (8 * b)) & 0xff) << (8 * (3 - b))).reduce(_ | _)

  class PcieBench(dut: PcieLink, val inboxBase: BigInt, val stagingBase: BigInt) {
    val host = new LineMemory
    val guest = new LineMemory
    val records = mutable.Queue[(BigInt, BigInt)]()
    val rxBeats = mutable.Queue[(BigInt, Int, Boolean)]()
    val cplData = mutable.Queue[BigInt]()
    val memRsp = mutable.Queue[BigInt]()
    var tlpsFromCard = mutable.ArrayBuffer[(Int, BigInt, Int)]() // (fmtType, address, length)
    val stagingSize = 0x10000
    dut.io.guest.valid #= false
    dut.io.completerId #= CardId
    dut.io.busMaster #= true
    dut.clockDomain.forkStimulus(10)
    StreamReadyRandomizer(dut.io.tx, dut.clockDomain)
    StreamReadyRandomizer(dut.io.mem, dut.clockDomain)
    StreamDriver(dut.io.rx, dut.clockDomain) { p =>
      if (rxBeats.nonEmpty) { val (d, k, last) = rxBeats.dequeue(); p.fragment.data #= d; p.fragment.keep #= k; p.last #= last; true } else false
    }
    StreamMonitor(dut.io.mem, dut.clockDomain) { p =>
      val a = p.address.toBigInt
      if (p.write.toBoolean) { guest.writeLine(a, p.data.toBigInt, p.mask.toBigInt); memRsp.enqueue(0) } else memRsp.enqueue(guest.readLine(a))
    }
    StreamDriver(dut.io.memRsp, dut.clockDomain) { p => if (memRsp.nonEmpty) { p #= memRsp.dequeue(); true } else false }

    // ---- TLPs from the card ----
    private val dws = mutable.ArrayBuffer[Long]()
    StreamMonitor(dut.io.tx, dut.clockDomain) { p =>
      val d = p.fragment.data.toBigInt
      dws += (d & 0xffffffffL).toLong
      if (p.fragment.keep.toInt == 0xff) dws += ((d >> 32) & 0xffffffffL).toLong
      if (p.last.toBoolean) { tlpFromCard(dws.toSeq); dws.clear() }
    }
    def tlpFromCard(t: Seq[Long]): Unit = {
      val fmtType = ((t(0) >> 24) & 0xff).toInt
      val len = (t(0) & 0x3ff).toInt match { case 0 => 1024; case n => n }
      val is4 = (fmtType & 0x20) != 0
      val hdr = if (is4) 4 else 3
      val addr = if (is4) (BigInt(t(2)) << 32) | (t(3) & 0xfffffffcL) else BigInt(t(2) & 0xfffffffcL)
      fmtType match {
        case Tlp.MWr32 | Tlp.MWr64 =>
          assert(((t(1) >> 16) & 0xffff) == CardId, "MemWr with the wrong requester ID")
          assert(t.size == hdr + len, s"MemWr of ${t.size} DWs, header says $hdr + $len")
          val firstBe = (t(1) & 0xf).toInt; val lastBe = ((t(1) >> 4) & 0xf).toInt
          assert(if (len == 1) lastBe == 0 else lastBe != 0, "byte enables")
          for (i <- 0 until len) {
            val be = if (i == 0) firstBe else if (i == len - 1) lastBe else 0xf
            val dw = t(hdr + i)
            for (b <- 0 until 4 if ((be >> b) & 1) == 1) host.write(addr + 4 * i + b, Seq(((dw >> (8 * (3 - b))) & 0xff).toInt))
          }
          val line = addr - addr % L
          if (line >= inboxBase && line < inboxBase + (L << 8)) records.enqueue((line, host.readLine(line)))
          tlpsFromCard += ((fmtType, addr, len))
        case Tlp.MRd32 | Tlp.MRd64 =>
          assert(len == 16 && addr % L == 0, "a line read")
          tlpsFromCard += ((fmtType, addr, len))
          val tag = (t(1) >> 8) & 0xff
          val reqId = (t(1) >> 16) & 0xffff
          val data = host.readLine(addr)
          val cpl = Seq(BigInt(Tlp.CplD.toLong << 24 | 16), BigInt(HostId.toLong << 16 | 64), BigInt(reqId << 16 | tag << 8)) ++
            (0 until 16).map(i => swap((data >> (32 * i)) & 0xffffffffL))
          send(cpl)
        case Tlp.CplD =>
          assert(((t(2) >> 16) & 0xffff) == HostId, "a completion for someone else")
          cplData.enqueue(swap(BigInt(t(3))))
        case other => fail(f"unexpected TLP from the card: fmt/type 0x$other%02x")
      }
    }
    /** Sends a TLP to the card, two DWs per beat. */
    def send(t: Seq[BigInt]): Unit = {
      val beats = t.grouped(2).toSeq
      for ((b, i) <- beats.zipWithIndex)
        rxBeats.enqueue((if (b.size == 2) b(0) | (b(1) << 32) else b(0), if (b.size == 2) 0xff else 0x0f, i == beats.size - 1))
    }
    def hostWrite(off: Int, v: BigInt): Unit = {
      send(Seq(BigInt(Tlp.MWr32.toLong << 24 | 1), BigInt(HostId.toLong << 16 | 0x0f), Bar0Base + off, swap(v)))
      dut.clockDomain.waitSampling(4)
    }
    def hostRead(off: Int): BigInt = {
      send(Seq(BigInt(Tlp.MRd32.toLong << 24 | 1), BigInt(HostId.toLong << 16 | 0x5a << 8 | 0x0f), Bar0Base + off))
      var c = 0
      while (cplData.isEmpty) { dut.clockDomain.waitSampling(); c += 1; assert(c < 2000, "no completion") }
      cplData.dequeue()
    }

    private def access(write: Boolean, address: BigInt, data: BigInt): BigInt = {
      val b = dut.io.guest
      b.valid #= true; b.write #= write; b.address #= address; b.wdata #= data
      var result: Option[BigInt] = None
      var cycles = 0
      while (result.isEmpty) {
        sleep(1)
        if (b.ready.toBoolean) result = Some(b.rdata.toBigInt)
        dut.clockDomain.waitSampling(); cycles += 1
        assert(cycles < 2000, "guest bus stuck")
      }
      b.valid #= false
      result.get
    }
    def guestWrite(slot: Int, reg: String, v: BigInt): Unit = access(true, slot * 0x1000 + V.reg(reg), v)
    def guestRead(slot: Int, reg: String): BigInt = access(false, slot * 0x1000 + V.reg(reg), 0)

    var nextRecord = 1L
    var nextCommand = 1L
    val log2 = 3
    def setUp(): Unit = {
      assert(hostRead(Bar0.reg("MAGIC")) == Contract.Magic)
      assert(hostRead(Bar0.reg("VERSION")) == Contract.Version)
      hostWrite(Bar0.reg("INBOX_ADDR_LO"), inboxBase & 0xffffffffL)
      hostWrite(Bar0.reg("INBOX_ADDR_HI"), inboxBase >> 32)
      hostWrite(Bar0.reg("INBOX_ENTRIES_LOG2"), log2)
      hostWrite(Bar0.reg("STAGING_ADDR_LO"), stagingBase & 0xffffffffL)
      hostWrite(Bar0.reg("STAGING_ADDR_HI"), stagingBase >> 32)
      hostWrite(Bar0.reg("STAGING_SIZE"), stagingSize)
      hostWrite(Bar0.reg("ENABLE"), 1)
      assert((hostRead(Bar0.reg("STATUS")) & 1) == 1)
    }
    def expectRecord(name: String, slot: Int, values: Map[String, BigInt]): Unit = {
      var c = 0
      while (records.isEmpty) { dut.clockDomain.waitSampling(); c += 1; assert(c < 20000, s"no record (expected $name)") }
      val (addr, data) = records.dequeue()
      assert(data == BigInt(1, Contract.encode(Contract.record(name), nextRecord, slot, values).reverse), s"record $nextRecord ($name)")
      assert(addr == inboxBase + ((nextRecord - 1) % (1 << log2)) * L)
      hostWrite(Bar0.reg("INBOX_CONSUMED"), nextRecord)
      nextRecord += 1
    }
    def command(name: String, slot: Int, values: Map[String, BigInt]): Unit = {
      val bytes = Contract.encode(Contract.command(name), nextCommand, slot, values)
      val base = Bar0.CmdRing + ((nextCommand - 1) % (1 << Bar0.CmdEntriesLog2)).toInt * L
      for (i <- 0 until L / 4) hostWrite(base + 4 * i, BigInt(1, bytes.slice(4 * i, 4 * i + 4).reverse))
      hostWrite(Bar0.reg("CMD_PRODUCED"), nextCommand)
      nextCommand += 1
    }
    def irqSettles(slot: Int, level: Boolean): Unit = {
      var c = 0
      while (((dut.io.irq.toBigInt >> slot) & 1) == 1 != level) { dut.clockDomain.waitSampling(); c += 1; assert(c < 2000, s"irq $slot never became $level") }
    }
  }

  def replay(b: PcieBench, t: Transcript): Unit = {
    b.setUp()
    for (step <- t.steps) step match {
      case HostProgram(slot, reg, v) => b.hostWrite(Bar0.SlotProgBase + slot * Bar0.SlotProgStride + Bar0.slotReg(reg), v)
      case GuestWrite(slot, reg, v) => b.guestWrite(slot, reg, v)
      case GuestRead(slot, reg, v) => assert(b.guestRead(slot, reg) == v, s"$reg on slot $slot")
      case ExpectRecord(n, slot, values) => b.expectRecord(n, slot, values)
      case HostCommand(n, slot, values) => b.command(n, slot, values)
      case ExpectIrq(slot, level) => b.irqSettles(slot, level)
      case GuestMem(a, bytes) => b.guest.write(a, bytes)
      case StagingWrite(o, bytes) => b.host.write(b.stagingBase + o, bytes)
      case ExpectStaging(o, bytes) => assert(b.host.read(b.stagingBase + o, bytes.size) == bytes, s"staging at 0x${o.toHexString}")
      case ExpectGuestMem(a, bytes) => assert(b.guest.read(a, bytes.size) == bytes, s"guest memory at 0x${a.toHexString}")
    }
  }

  test("blk_read over TLPs, inbox and staging below 4 GiB (3-DW headers)") {
    compiled.doSim { dut =>
      val b = new PcieBench(dut, BigInt("40000000", 16), BigInt("50000000", 16))
      replay(b, Transcripts.blkRead)
      assert(b.tlpsFromCard.forall { case (ft, _, _) => ft == Tlp.MWr32 || ft == Tlp.MRd32 })
      assert(b.tlpsFromCard.exists(_._1 == Tlp.MRd32), "COPY_FROM_HOST read nothing")
    }
  }

  test("blk_read over TLPs, inbox and staging above 4 GiB (4-DW headers)") {
    compiled.doSim { dut =>
      val b = new PcieBench(dut, BigInt("123400000", 16), BigInt("abc000000", 16))
      replay(b, Transcripts.blkRead)
      assert(b.tlpsFromCard.forall { case (ft, _, _) => ft == Tlp.MWr64 || ft == Tlp.MRd64 })
    }
  }

  test("a partial line goes out as one MemWr covering just its bytes") {
    compiled.doSim { dut =>
      val b = new PcieBench(dut, BigInt("40000000", 16), BigInt("50000000", 16))
      b.setUp()
      // neighbours that must survive: the guest's line around the range, and staging around its copy
      b.guest.write(BigInt(Contract.Map.DmaRegion) + 0x400, Seq.fill(64)(0x55))
      b.host.write(BigInt("50000000", 16), Seq.fill(0x40)(0xaa))
      b.guest.write(BigInt(Contract.Map.DmaRegion) + 0x40d, (0 until 20).map(_ + 1))
      b.command("COPY_TO_HOST", 0, Map("tag" -> 9, "len" -> 20, "guest" -> (BigInt(Contract.Map.DmaRegion) + 0x40d), "staging" -> 0x0d))
      b.expectRecord("COPY_DONE", 0, Map("tag" -> 9, "status" -> 0))
      val data = b.tlpsFromCard.filter { case (_, a, _) => a >= BigInt("50000000", 16) }
      assert(data.size == 1, s"${data.size} data TLPs")
      val (_, addr, len) = data.head
      assert(addr == BigInt("5000000c", 16) && len == 6, f"MemWr at 0x$addr%x of $len DWs") // bytes 0x0d..0x20: DWs 3..8
      assert(b.host.read(BigInt("50000000", 16), 0x40) == Seq.fill(0x0d)(0xaa) ++ (1 to 20) ++ Seq.fill(0x40 - 0x0d - 20)(0xaa),
        "bytes outside the copy changed in host staging")
    }
  }

  test("no DMA until the host enables bus mastering") {
    compiled.doSim { dut =>
      val b = new PcieBench(dut, BigInt("40000000", 16), BigInt("50000000", 16))
      dut.io.busMaster #= false
      b.setUp()
      b.guestWrite(2, "QueueNotify", 0)
      dut.clockDomain.waitSampling(500)
      assert(b.tlpsFromCard.isEmpty, "the card wrote without Bus Master Enable")
      dut.io.busMaster #= true
      b.expectRecord("NOTIFY", 2, Map("queue" -> 0))
    }
  }

  test("oversized and unknown TLPs are dropped, and the link keeps working") {
    compiled.doSim { dut =>
      val b = new PcieBench(dut, BigInt("40000000", 16), BigInt("50000000", 16))
      b.send(Seq(BigInt(Tlp.MWr32.toLong << 24 | 30), BigInt(0x0f), BigInt("f0000000", 16) + Bar0.reg("ENABLE")) ++ Seq.fill(30)(BigInt(1)))
      b.send(Seq(BigInt(0x30L << 24 | 0x14L << 24 | 1), BigInt(0), BigInt(0), BigInt(0))) // a message TLP
      b.send(Seq(BigInt(Tlp.CplD.toLong << 24 | 16), BigInt(64), BigInt(0)) ++ Seq.fill(16)(BigInt(7))) // a completion nobody asked for
      dut.clockDomain.waitSampling(200)
      assert((b.hostRead(Bar0.reg("STATUS")) & 1) == 0, "the oversized write enabled the card")
      b.setUp()
      b.guestWrite(1, "QueueNotify", 3)
      b.expectRecord("NOTIFY", 1, Map("queue" -> 3))
    }
  }
}
