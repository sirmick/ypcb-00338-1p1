package card

import scala.collection.mutable
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.sim.StreamMonitor
import card.contract._
import Contract.{Bar0, Message, VirtioMmio => V}

// S2: the virtio-mmio shims and the BAR0 mailbox, driven by the shared transcripts (configuration A).
class CardLinkSpec extends AnyFunSuite {
  lazy val compiled = SimConfig.withConfig(SpinalConfig(targetDirectory = "tmp")).compile(CardLink(8))
  val InboxBase = BigInt("40000000", 16)

  class Bench(dut: CardLink) {
    val written = mutable.Queue[(BigInt, BigInt)]()
    dut.io.guest.valid #= false
    dut.io.host.valid #= false
    dut.io.hostWrite.ready #= true
    dut.clockDomain.forkStimulus(10)
    StreamMonitor(dut.io.hostWrite, dut.clockDomain) { p => written.enqueue((p.address.toBigInt, p.data.toBigInt)) }
    dut.clockDomain.waitSampling(5)

    private def access(b: RegBus, write: Boolean, address: BigInt, data: BigInt): BigInt = {
      b.valid #= true; b.write #= write; b.address #= address; b.wdata #= data
      var result: Option[BigInt] = None
      var cycles = 0
      while (result.isEmpty) {
        sleep(1) // let the combinational ready and rdata settle
        if (b.ready.toBoolean) result = Some(b.rdata.toBigInt)
        dut.clockDomain.waitSampling()
        cycles += 1
        assert(cycles < 1000, s"bus stuck at 0x${address.toString(16)}")
      }
      b.valid #= false
      result.get
    }
    def hostWrite(a: Int, v: BigInt): Unit = access(dut.io.host, true, a, v)
    def hostRead(a: Int): BigInt = access(dut.io.host, false, a, 0)
    def guestWrite(slot: Int, reg: String, v: BigInt): Unit = access(dut.io.guest, true, slot * 0x1000 + V.reg(reg), v)
    def guestRead(slot: Int, reg: String): BigInt = access(dut.io.guest, false, slot * 0x1000 + V.reg(reg), 0)

    def setUp(log2: Int): Unit = {
      assert(hostRead(Bar0.reg("MAGIC")) == Contract.Magic)
      assert(hostRead(Bar0.reg("VERSION")) == Contract.Version)
      assert(hostRead(Bar0.reg("SLOTS")) == 8)
      assert(hostRead(Bar0.reg("CMD_ENTRIES_LOG2")) == Bar0.CmdEntriesLog2)
      hostWrite(Bar0.reg("INBOX_ADDR_LO"), InboxBase & 0xffffffffL)
      hostWrite(Bar0.reg("INBOX_ADDR_HI"), InboxBase >> 32)
      hostWrite(Bar0.reg("INBOX_ENTRIES_LOG2"), log2)
      hostWrite(Bar0.reg("ENABLE"), 1)
      assert((hostRead(Bar0.reg("STATUS")) & 1) == 1)
    }
    def nextRecord(maxCycles: Int = 2000): (BigInt, BigInt) = {
      var c = 0
      while (written.isEmpty) { dut.clockDomain.waitSampling(); c += 1; assert(c < maxCycles, "no record arrived") }
      written.dequeue()
    }
    def sendCommand(seq: Long, bytes: Array[Byte]): Unit = {
      val base = Bar0.CmdRing + ((seq - 1) % (1 << Bar0.CmdEntriesLog2)).toInt * Message.Size
      for (i <- 0 until Message.Size / 4) hostWrite(base + 4 * i, BigInt(1, bytes.slice(4 * i, 4 * i + 4).reverse))
      hostWrite(Bar0.reg("CMD_PRODUCED"), seq)
    }
    def irqSettles(slot: Int, level: Boolean): Unit = {
      var c = 0
      while (((dut.io.irq.toBigInt >> slot) & 1) == 1 != level) { dut.clockDomain.waitSampling(); c += 1; assert(c < 200, s"irq $slot never became $level") }
    }
  }

  private def le(b: Array[Byte]) = BigInt(1, b.reverse)
  private val replayable = Transcripts.all.filter(_.steps.exists { case _: GuestWrite | _: GuestRead => true; case _ => false })

  for (t <- replayable) test(s"transcript ${t.name}: ${t.doc}") {
    compiled.doSim { dut =>
      val b = new Bench(dut)
      val log2 = 3 // 8 entries: the transcript wraps the inbox, so the host must keep up
      b.setUp(log2)
      var rec = 0L; var cmd = 0L
      for (step <- t.steps) step match {
        case HostProgram(slot, reg, v) => b.hostWrite(Bar0.SlotProgBase + slot * Bar0.SlotProgStride + Bar0.slotReg(reg), v)
        case GuestWrite(slot, reg, v) => b.guestWrite(slot, reg, v)
        case GuestRead(slot, reg, v) => assert(b.guestRead(slot, reg) == v, s"$reg on slot $slot")
        case ExpectRecord(n, slot, values) =>
          rec += 1
          val (addr, data) = b.nextRecord()
          assert(data == le(Contract.encode(Contract.record(n), rec, slot, values)), s"record $rec ($n)")
          assert(addr == InboxBase + ((rec - 1) % (1 << log2)) * Message.Size, s"record $rec landed at 0x${addr.toString(16)}")
          b.hostWrite(Bar0.reg("INBOX_CONSUMED"), rec)
        case HostCommand(n, slot, values) =>
          cmd += 1
          b.sendCommand(cmd, Contract.encode(Contract.command(n), cmd, slot, values))
        case ExpectIrq(slot, level) => b.irqSettles(slot, level)
      }
      dut.clockDomain.waitSampling(200)
      assert(b.written.isEmpty, s"${b.written.size} records the transcript does not expect")
      assert((b.hostRead(Bar0.reg("STATUS")) & 4) == 0, "a command was refused")
    }
  }

  test("BAR0 offsets outside the mailbox read 0 and ignore writes") {
    compiled.doSim { dut =>
      val b = new Bench(dut)
      val unused = Seq(0x040, 0x0ffc, 0x2000, 0x3ffc, Bar0.SlotProgBase + 8 * Bar0.SlotProgStride, 0xfffc)
      for (a <- unused) b.hostWrite(a, BigInt("ffffffff", 16))
      for (a <- unused) assert(b.hostRead(a) == 0, f"offset 0x$a%x")
      assert(b.hostRead(Bar0.reg("MAGIC")) == Contract.Magic)
      assert(b.hostRead(Bar0.reg("STATUS")) == 0)
      for (s <- 0 until 8) assert(b.guestRead(s, "DeviceID") == 0, s"slot $s was programmed")
    }
  }

  test("a command whose sequence numbers are wrong is refused, acknowledged, and does nothing") {
    compiled.doSim { dut =>
      val b = new Bench(dut)
      b.setUp(3)
      val bytes = Contract.encode(Contract.command("INTERRUPT"), 5, 0, Map("bits" -> 1)) // the card expects 1
      b.sendCommand(1, bytes)
      val (_, ack) = b.nextRecord()
      assert(ack == le(Contract.encode(Contract.record("CMD_ACK"), 1, 0, Map("cmd_seq" -> 1))))
      assert((b.hostRead(Bar0.reg("STATUS")) & 4) == 4)
      assert((dut.io.irq.toBigInt & 1) == 0)
      // torn: the tail copy differs
      val torn = Contract.encode(Contract.command("INTERRUPT"), 2, 0, Map("bits" -> 1))
      torn(Message.TailOffset) = 9
      b.sendCommand(2, torn)
      b.nextRecord()
      assert((dut.io.irq.toBigInt & 1) == 0)
    }
  }

  test("the card never overruns the host inbox") {
    compiled.doSim { dut =>
      val b = new Bench(dut)
      b.setUp(1) // two entries
      for (i <- 0 until 4) b.guestWrite(3, "QueueNotify", i)
      dut.clockDomain.waitSampling(100)
      assert(b.written.size == 2, s"${b.written.size} records written into a two-entry inbox")
      assert((b.hostRead(Bar0.reg("STATUS")) & 2) == 2, "STATUS does not show the inbox full")
      b.written.clear()
      b.hostWrite(Bar0.reg("INBOX_CONSUMED"), 2)
      dut.clockDomain.waitSampling(100)
      assert(b.written.size == 2)
      val queues = b.written.map { case (_, d) => ((d >> (8 * Contract.record("NOTIFY").field("queue").offset)) & 0xffff).toInt }
      assert(queues == Seq(2, 3))
    }
  }
}
