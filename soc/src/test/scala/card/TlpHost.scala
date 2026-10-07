package card

import scala.collection.mutable
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.sim.{StreamDriver, StreamMonitor, StreamReadyRandomizer}
import card.contract._
import Contract.{Bar0, Message}

object TlpHost {
  val Bar0Base = BigInt("f0000000", 16)
  val HostId = 0x0000
  val CardId = 0x0400 // bus 4, device 0, function 0
  /** Payload DWs travel with their lowest-addressed byte in bits 31:24, as the hard block delivers them. */
  def swap(dw: BigInt): BigInt = (0 until 4).map(b => ((dw >> (8 * b)) & 0xff) << (8 * (3 - b))).reduce(_ | _)
}
import TlpHost._

/** A TLP-level host: stands where the hard block and the root complex are. It turns the card's MemWr
  * TLPs into host memory, answers its MemRd TLPs with CplD, and plays the host CPU's 32-bit BAR0
  * accesses as MemWr/MemRd TLPs. Records written into the inbox are queued in `records`. */
class TlpHost(rx: Stream[Fragment[Axis64]], tx: Stream[Fragment[Axis64]], cd: ClockDomain, val inboxBase: BigInt, val stagingBase: BigInt) {
  private val L = Message.Size
  val host = new LineMemory
  val records = mutable.Queue[(BigInt, BigInt)]()
  val rxBeats = mutable.Queue[(BigInt, Int, Boolean)]()
  val cplData = mutable.Queue[BigInt]()
  var tlpsFromCard = mutable.ArrayBuffer[(Int, BigInt, Int)]() // (fmtType, address, length)
  val stagingSize = 0x10000
  StreamReadyRandomizer(tx, cd)
  StreamDriver(rx, cd) { p =>
    if (rxBeats.nonEmpty) { val (d, k, last) = rxBeats.dequeue(); p.fragment.data #= d; p.fragment.keep #= k; p.last #= last; true } else false
  }

  // ---- TLPs from the card ----
  private val dws = mutable.ArrayBuffer[Long]()
  StreamMonitor(tx, cd) { p =>
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
        readPolicy match {
          case "answer" => send(completion(addr, reqId, tag))
          case "error" => send(Seq(BigInt(Tlp.Cpl.toLong << 24), BigInt(HostId.toLong << 16 | 1 << 13 | 64), BigInt(reqId << 16 | tag << 8))) // UR
          case "drop" => unanswered.enqueue((addr, reqId, tag))
        }
      case Tlp.CplD =>
        assert(((t(2) >> 16) & 0xffff) == HostId, "a completion for someone else")
        cplData.enqueue(swap(BigInt(t(3))))
      case other => throw new AssertionError(f"unexpected TLP from the card: fmt/type 0x$other%02x")
    }
  }
  /** How the host answers the card's reads: "answer" (CplD), "error" (an Unsupported Request completion,
    * as when the IOMMU blocks the read) or "drop" (no answer; kept in `unanswered`). */
  var readPolicy = "answer"
  val unanswered = mutable.Queue[(BigInt, Long, Long)]()
  def completion(addr: BigInt, reqId: Long, tag: Long): Seq[BigInt] = {
    val data = host.readLine(addr)
    Seq(BigInt(Tlp.CplD.toLong << 24 | 16), BigInt(HostId.toLong << 16 | 64), BigInt(reqId << 16 | tag << 8)) ++
      (0 until 16).map(i => swap((data >> (32 * i)) & 0xffffffffL))
  }
  /** Sends a TLP to the card, two DWs per beat. */
  def send(t: Seq[BigInt]): Unit = {
    val beats = t.grouped(2).toSeq
    for ((b, i) <- beats.zipWithIndex)
      rxBeats.enqueue((if (b.size == 2) b(0) | (b(1) << 32) else b(0), if (b.size == 2) 0xff else 0x0f, i == beats.size - 1))
  }
  def hostWrite(off: Int, v: BigInt): Unit = {
    send(Seq(BigInt(Tlp.MWr32.toLong << 24 | 1), BigInt(HostId.toLong << 16 | 0x0f), Bar0Base + off, swap(v)))
    cd.waitSampling(4)
  }
  def hostRead(off: Int): BigInt = {
    send(Seq(BigInt(Tlp.MRd32.toLong << 24 | 1), BigInt(HostId.toLong << 16 | 0x5a << 8 | 0x0f), Bar0Base + off))
    var c = 0
    while (cplData.isEmpty) { cd.waitSampling(); c += 1; assert(c < 2000, "no completion") }
    cplData.dequeue()
  }

  var nextRecord = 1L
  var nextCommand = 1L
  var log2 = 3
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
    while (records.isEmpty) { cd.waitSampling(); c += 1; assert(c < 20000, s"no record (expected $name)") }
    val (addr, data) = records.dequeue()
    assert(data == BigInt(1, Contract.encode(Contract.record(name), nextRecord, slot, values).reverse), s"record $nextRecord ($name)")
    assert(addr == inboxBase + ((nextRecord - 1) % (1 << log2)) * L)
    hostWrite(Bar0.reg("INBOX_CONSUMED"), nextRecord)
    nextRecord += 1
  }
  /** The next record, whatever it is, within `cycles`: checked for its sequence number, consumed and
    * acknowledged. */
  def takeRecord(cycles: Int): Option[BigInt] = {
    var c = 0
    while (records.isEmpty && c < cycles) { cd.waitSampling(); c += 1 }
    if (records.isEmpty) return None
    val (_, data) = records.dequeue()
    assert((data & 0xffffffffL) == nextRecord && ((data >> (8 * Message.TailOffset)) & 0xffffffffL) == nextRecord, s"record $nextRecord out of sequence")
    hostWrite(Bar0.reg("INBOX_CONSUMED"), nextRecord)
    nextRecord += 1
    Some(data)
  }
  def command(name: String, slot: Int, values: Map[String, BigInt]): Unit = {
    val bytes = Contract.encode(Contract.command(name), nextCommand, slot, values)
    val base = Bar0.CmdRing + ((nextCommand - 1) % (1 << Bar0.CmdEntriesLog2)).toInt * L
    for (i <- 0 until L / 4) hostWrite(base + 4 * i, BigInt(1, bytes.slice(4 * i, 4 * i + 4).reverse))
    hostWrite(Bar0.reg("CMD_PRODUCED"), nextCommand)
    nextCommand += 1
  }
}
