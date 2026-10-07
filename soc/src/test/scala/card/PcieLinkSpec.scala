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
  import TlpHost._
  val L = Message.Size

  class PcieBench(dut: PcieLink, inboxBase: BigInt, stagingBase: BigInt)
      extends TlpHost(dut.io.rx, dut.io.tx, dut.clockDomain, inboxBase, stagingBase) {
    val guest = new LineMemory
    val memRsp = mutable.Queue[BigInt]()
    dut.io.guest.valid #= false
    dut.io.uart.valid #= false
    dut.io.ramReady #= true
    dut.io.completerId #= CardId
    dut.io.busMaster #= true
    dut.clockDomain.forkStimulus(10)
    StreamReadyRandomizer(dut.io.mem, dut.clockDomain)
    StreamMonitor(dut.io.mem, dut.clockDomain) { p =>
      val a = p.address.toBigInt
      if (p.write.toBoolean) { guest.writeLine(a, p.data.toBigInt, p.mask.toBigInt); memRsp.enqueue(0) } else memRsp.enqueue(guest.readLine(a))
    }
    StreamDriver(dut.io.memRsp, dut.clockDomain) { p => if (memRsp.nonEmpty) { p #= memRsp.dequeue(); true } else false }

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
