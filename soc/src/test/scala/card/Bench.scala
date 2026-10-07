package card

import scala.collection.mutable
import spinal.core._
import spinal.core.sim._
import spinal.lib.sim.{StreamDriver, StreamMonitor, StreamReadyRandomizer}
import card.contract._
import Contract.{Bar0, Message, VirtioMmio => V}

/** The test bench around CardLink: host memory (inbox, staging, host reads), guest memory (the
  * card's memory port), and the guest's and host's register buses. Shared by CardLinkSpec and
  * CosimSpec. */
object BenchConfig {
  val InboxBase = BigInt("40000000", 16)
  val StagingBase = BigInt("50000000", 16)
  val StagingSize = 0x10000
  val D = BigInt(Contract.Map.DmaRegion)
  val L = Message.Size
}
import BenchConfig._

/** Byte-addressed memory, as lines of 64 bytes. */
class LineMemory {
  val lines = mutable.Map[BigInt, Array[Byte]]()
  def line(a: BigInt): Array[Byte] = lines.getOrElseUpdate(a, new Array[Byte](L))
  def write(a: BigInt, bytes: Seq[Int]): Unit = for ((v, i) <- bytes.zipWithIndex) { val x = a + i; line(x - x % L)((x % L).toInt) = v.toByte }
  def read(a: BigInt, n: Int): Seq[Int] = (0 until n).map { i => val x = a + i; line(x - x % L)((x % L).toInt) & 0xff }
  def writeLine(a: BigInt, data: BigInt, mask: BigInt): Unit = { val l = line(a); for (i <- 0 until L if mask.testBit(i)) l(i) = ((data >> (8 * i)) & 0xff).toByte }
  def readLine(a: BigInt): BigInt = BigInt(1, line(a).reverse)
  def touched: Set[BigInt] = lines.keySet.toSet
}

class Bench(dut: CardLink) {
  val host = new LineMemory   // the inbox and staging
  val guest = new LineMemory  // DDR3 channel B and the ring region
  val records = mutable.Queue[(BigInt, BigInt)]()
  val memRsp = mutable.Queue[BigInt]()
  val hostRsp = mutable.Queue[BigInt]()
  dut.io.guest.valid #= false
  dut.io.uart.valid #= false
  dut.io.ramReady #= true
  dut.io.linkReset #= false
  dut.io.host.valid #= false
  dut.clockDomain.forkStimulus(10)
  // the link and the memory accept at random moments, so back-pressure is exercised everywhere
  StreamReadyRandomizer(dut.io.hostWrite, dut.clockDomain)
  StreamReadyRandomizer(dut.io.hostRead, dut.clockDomain)
  StreamReadyRandomizer(dut.io.mem, dut.clockDomain)
  StreamMonitor(dut.io.hostWrite, dut.clockDomain) { p =>
    val a = p.address.toBigInt
    host.writeLine(a, p.data.toBigInt, p.mask.toBigInt)
    if (a >= InboxBase && a < InboxBase + (L << 8)) records.enqueue((a, p.data.toBigInt))
  }
  StreamMonitor(dut.io.hostRead, dut.clockDomain) { p => hostRsp.enqueue(host.readLine(p.toBigInt)) }
  StreamDriver(dut.io.hostReadRsp, dut.clockDomain) { p => if (hostRsp.nonEmpty) { p.data #= hostRsp.dequeue(); p.failed #= false; true } else false }
  StreamMonitor(dut.io.mem, dut.clockDomain) { p =>
    val a = p.address.toBigInt
    assert(a % L == 0, "unaligned memory request")
    if (p.write.toBoolean) { guest.writeLine(a, p.data.toBigInt, p.mask.toBigInt); memRsp.enqueue(0) }
    else memRsp.enqueue(guest.readLine(a))
  }
  StreamDriver(dut.io.memRsp, dut.clockDomain) { p => if (memRsp.nonEmpty) { p #= memRsp.dequeue(); true } else false }
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
  def uartWrite(reg: Int, v: Int): Unit = access(dut.io.uart, true, reg, v)
  def uartRead(reg: Int): Int = access(dut.io.uart, false, reg, 0).toInt

  var log2 = 3
  var nextRecord = 1L
  var nextCommand = 1L
  def setUp(log2: Int): Unit = {
    this.log2 = log2
    assert(hostRead(Bar0.reg("MAGIC")) == Contract.Magic)
    assert(hostRead(Bar0.reg("VERSION")) == Contract.Version)
    assert(hostRead(Bar0.reg("SLOTS")) == 8)
    assert(hostRead(Bar0.reg("CMD_ENTRIES_LOG2")) == Bar0.CmdEntriesLog2)
    hostWrite(Bar0.reg("INBOX_ADDR_LO"), InboxBase & 0xffffffffL)
    hostWrite(Bar0.reg("INBOX_ADDR_HI"), InboxBase >> 32)
    hostWrite(Bar0.reg("INBOX_ENTRIES_LOG2"), log2)
    hostWrite(Bar0.reg("STAGING_ADDR_LO"), StagingBase & 0xffffffffL)
    hostWrite(Bar0.reg("STAGING_ADDR_HI"), StagingBase >> 32)
    hostWrite(Bar0.reg("STAGING_SIZE"), StagingSize)
    hostWrite(Bar0.reg("ENABLE"), 1)
    assert((hostRead(Bar0.reg("STATUS")) & 1) == 1)
  }
  /** The next record: it must be `name` with these fields, at the right inbox entry. Consumes it. */
  def expectRecord(name: String, slot: Int, values: Map[String, BigInt]): Unit = {
    var c = 0
    while (records.isEmpty) { dut.clockDomain.waitSampling(); c += 1; assert(c < 5000, s"no record (expected $name)") }
    val (addr, data) = records.dequeue()
    val want = BigInt(1, Contract.encode(Contract.record(name), nextRecord, slot, values).reverse)
    assert(data == want, s"record $nextRecord: expected $name $values, got ${decode(data)}")
    assert(addr == InboxBase + ((nextRecord - 1) % (1 << log2)) * L, s"record $nextRecord at 0x${addr.toString(16)}")
    hostWrite(Bar0.reg("INBOX_CONSUMED"), nextRecord)
    nextRecord += 1
  }
  def decode(d: BigInt): String = {
    val kind = ((d >> 32) & 0xffff).toInt
    Contract.records.find(_.kind == kind).map(l => l.name + l.fields.map(f => s" ${f.name}=${(d >> (8 * f.offset)) & ((BigInt(1) << (8 * f.bytes)) - 1)}").mkString).getOrElse(s"kind $kind")
  }
  def command(name: String, slot: Int, values: Map[String, BigInt]): Unit = {
    val bytes = Contract.encode(Contract.command(name), nextCommand, slot, values)
    sendRaw(nextCommand, bytes)
    nextCommand += 1
  }
  def sendRaw(seq: Long, bytes: Array[Byte]): Unit = {
    val base = Bar0.CmdRing + ((seq - 1) % (1 << Bar0.CmdEntriesLog2)).toInt * L
    for (i <- 0 until L / 4) hostWrite(base + 4 * i, BigInt(1, bytes.slice(4 * i, 4 * i + 4).reverse))
    hostWrite(Bar0.reg("CMD_PRODUCED"), seq)
  }
  def ackCommand(): Unit = expectRecord("CMD_ACK", 0, Map("cmd_seq" -> (nextCommand - 1)))
  def irqSettles(slot: Int, level: Boolean): Unit = {
    var c = 0
    while (((dut.io.irq.toBigInt >> slot) & 1) == 1 != level) { dut.clockDomain.waitSampling(); c += 1; assert(c < 500, s"irq $slot never became $level") }
  }
  def refused: Boolean = (hostRead(Bar0.reg("STATUS")) & 4) == 4
  /** Programs slot 0 as a blk device and enables queue 0 with its used ring at `used`. */
  def readyQueue(size: Int, used: BigInt): Unit = {
    hostWrite(Bar0.SlotProgBase + Bar0.slotReg("DEVICE_ID"), V.DeviceBlk)
    hostWrite(Bar0.SlotProgBase + Bar0.slotReg("QUEUE_NUM_MAX"), 256)
    guestWrite(0, "QueueSel", 0)
    guestWrite(0, "QueueNum", size)
    guestWrite(0, "QueueDeviceLow", used & 0xffffffffL)
    guestWrite(0, "QueueDeviceHigh", used >> 32)
    guestWrite(0, "QueueReady", 1)
    expectRecord("QUEUE", 0, Map("queue" -> 0, "size" -> size, "ready" -> 1, "device" -> used))
  }
}

