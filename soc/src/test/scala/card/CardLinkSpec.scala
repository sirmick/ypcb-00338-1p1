package card

import scala.collection.mutable
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import card.contract._
import Contract.{Bar0, Message, VirtioMmio => V}
import BenchConfig._

// S2 and S3: the virtio-mmio shims, the BAR0 mailbox and the command processor (copies, used-ring
// pushes), driven by the shared transcripts and by hostile commands.
class CardLinkSpec extends AnyFunSuite {
  lazy val compiled = SimConfig.withConfig(SpinalConfig(targetDirectory = "tmp")).compile(CardLink(8))

  private val replayable = Transcripts.all.filter(_.steps.exists { case _: GuestWrite | _: GuestRead => true; case _ => false })

  for (t <- replayable) test(s"transcript ${t.name}: ${t.doc}") {
    compiled.doSim { dut =>
      val b = new Bench(dut)
      b.setUp(3) // 8 entries: the transcripts wrap the inbox, so the host must keep up
      for (step <- t.steps) step match {
        case HostProgram(slot, reg, v) => b.hostWrite(Bar0.SlotProgBase + slot * Bar0.SlotProgStride + Bar0.slotReg(reg), v)
        case GuestWrite(slot, reg, v) => b.guestWrite(slot, reg, v)
        case GuestRead(slot, reg, v) => assert(b.guestRead(slot, reg) == v, s"$reg on slot $slot")
        case ExpectRecord(n, slot, values) => b.expectRecord(n, slot, values)
        case HostCommand(n, slot, values) => b.command(n, slot, values)
        case ExpectIrq(slot, level) => b.irqSettles(slot, level)
        case GuestMem(a, bytes) => b.guest.write(a, bytes)
        case StagingWrite(o, bytes) => b.host.write(StagingBase + o, bytes)
        case ExpectStaging(o, bytes) => assert(b.host.read(StagingBase + o, bytes.size) == bytes, s"staging at 0x${o.toHexString}")
        case ExpectGuestMem(a, bytes) => assert(b.guest.read(a, bytes.size) == bytes, s"guest memory at 0x${a.toHexString}")
      }
      dut.clockDomain.waitSampling(300)
      assert(b.records.isEmpty, s"${b.records.size} records the transcript does not expect: ${b.records.map(r => b.decode(r._2))}")
      assert(!b.refused, "a command was refused")
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
      b.sendRaw(1, Contract.encode(Contract.command("INTERRUPT"), 5, 0, Map("bits" -> 1))) // the card expects 1
      b.nextCommand = 2
      b.ackCommand()
      assert(b.refused)
      assert((dut.io.irq.toBigInt & 1) == 0)
      val torn = Contract.encode(Contract.command("INTERRUPT"), 2, 0, Map("bits" -> 1))
      torn(Message.TailOffset) = 9
      b.sendRaw(2, torn)
      b.nextCommand = 3
      b.ackCommand()
      assert((dut.io.irq.toBigInt & 1) == 0)
    }
  }

  test("the card never overruns the host inbox") {
    compiled.doSim { dut =>
      val b = new Bench(dut)
      b.setUp(1) // two entries
      for (i <- 0 until 4) b.guestWrite(3, "QueueNotify", i)
      dut.clockDomain.waitSampling(200)
      assert(b.records.size == 2, s"${b.records.size} records written into a two-entry inbox")
      assert((b.hostRead(Bar0.reg("STATUS")) & 2) == 2, "STATUS does not show the inbox full")
      b.records.clear()
      b.hostWrite(Bar0.reg("INBOX_CONSUMED"), 2)
      dut.clockDomain.waitSampling(200)
      val queues = b.records.map { case (_, d) => ((d >> (8 * Contract.record("NOTIFY").field("queue").offset)) & 0xffff).toInt }
      assert(queues == Seq(2, 3))
    }
  }

  test("copies outside the windows, of bad length, beyond staging or misaligned are refused and move nothing") {
    compiled.doSim { dut =>
      val b = new Bench(dut)
      b.setUp(4)
      val cases = Seq[(BigInt, Long, Long, Int, String)](
        (BigInt(Contract.Map.Ram), 64, 0, Contract.CopyStatus.OutsideWindow, "main memory"),
        (D + Contract.Map.DmaRegionSize - 16, 32, 0x30, Contract.CopyStatus.OutsideWindow, "across the window's end"),
        (D - 64, 128, 0, Contract.CopyStatus.OutsideWindow, "across the window's start"),
        (BigInt("ffffffffffffffc0", 16), 128, 0, Contract.CopyStatus.OutsideWindow, "wrapping past 2^64"),
        (D, 0, 0, Contract.CopyStatus.BadLength, "zero length"),
        (D, 64, StagingSize - 32, Contract.CopyStatus.BadLength, "beyond staging"),
        (D + 3, 16, 0, Contract.CopyStatus.Misaligned, "staging misaligned with the guest address"))
      var tag = 100
      for ((g, len, staging, status, what) <- cases; name <- Seq("COPY_TO_HOST", "COPY_FROM_HOST")) {
        val hostBefore = b.host.touched; val guestBefore = b.guest.touched
        tag += 1
        b.command(name, 0, Map("tag" -> tag, "len" -> len, "guest" -> g, "staging" -> staging))
        b.expectRecord("COPY_DONE", 0, Map("tag" -> tag, "status" -> status))
        b.ackCommand()
        assert(b.guest.touched == guestBefore, s"$name $what touched guest memory")
        assert(b.host.touched.filter(_ < InboxBase) == hostBefore.filter(_ < InboxBase) &&
          b.host.touched.filter(_ >= StagingBase) == hostBefore.filter(_ >= StagingBase), s"$name $what touched staging")
      }
      assert(!b.refused, "a copy refusal is reported in COPY_DONE, not STATUS")
    }
  }

  test("a copy writes exactly its bytes: partial first and last lines keep their neighbours") {
    compiled.doSim { dut =>
      val b = new Bench(dut)
      b.setUp(4)
      b.guest.write(D + 0x400, Seq.fill(256)(0xee))
      b.host.write(StagingBase + 0x200, (0 until 256).map(_ & 0xff))
      b.command("COPY_FROM_HOST", 0, Map("tag" -> 1, "len" -> 150, "guest" -> (D + 0x40d), "staging" -> 0x20d))
      b.expectRecord("COPY_DONE", 0, Map("tag" -> 1, "status" -> 0)); b.ackCommand()
      assert(b.guest.read(D + 0x400, 0x0d).forall(_ == 0xee), "bytes before the copy changed")
      assert(b.guest.read(D + 0x40d, 150) == (0x0d until 0x0d + 150).map(_ & 0xff))
      assert(b.guest.read(D + 0x40d + 150, 256 - 0x0d - 150).forall(_ == 0xee), "bytes after the copy changed")
      b.host.write(StagingBase + 0x600, Seq.fill(256)(0x11))
      b.command("COPY_TO_HOST", 0, Map("tag" -> 2, "len" -> 70, "guest" -> (D + 0x43f), "staging" -> 0x63f))
      b.expectRecord("COPY_DONE", 0, Map("tag" -> 2, "status" -> 0)); b.ackCommand()
      assert(b.host.read(StagingBase + 0x600, 0x3f).forall(_ == 0x11))
      assert(b.host.read(StagingBase + 0x63f, 70) == b.guest.read(D + 0x43f, 70))
      assert(b.host.read(StagingBase + 0x63f + 70, 64).forall(_ == 0x11))
    }
  }

  test("USED_PUSH fills the used ring in order, wraps it, and spills an entry across a line") {
    compiled.doSim { dut =>
      val b = new Bench(dut)
      b.setUp(4)
      val used = D + 0x2000 // 64-byte aligned, so entry 7 starts at byte 60 and spills into the next line
      b.readyQueue(8, used)
      for (i <- 0 until 10) {
        b.command("USED_PUSH", 0, Map("queue" -> 0, "id" -> (100 + i), "len" -> (1000 + i)))
        b.ackCommand()
        assert(b.guest.read(used + 2, 2) == Seq((i + 1) & 0xff, (i + 1) >> 8), s"used index after push $i")
        val e = used + 4 + 8 * (i % 8)
        assert(b.guest.read(e, 8) == (0 until 4).map(k => ((100 + i) >> (8 * k)) & 0xff) ++ (0 until 4).map(k => ((1000 + i) >> (8 * k)) & 0xff), s"entry $i")
      }
      assert(b.guest.read(used, 2) == Seq(0, 0), "the card wrote the used ring's flags")
      assert(!b.refused)
    }
  }

  test("USED_PUSH on a queue that is not ready, or whose ring is outside the windows, is refused") {
    compiled.doSim { dut =>
      val b = new Bench(dut)
      b.setUp(4)
      b.command("USED_PUSH", 0, Map("queue" -> 0, "id" -> 1, "len" -> 1))
      b.ackCommand()
      assert(b.refused && b.guest.touched.isEmpty, "pushed to a queue that is not ready")
      b.readyQueue(8, BigInt(Contract.Map.Ram) + 0x1000) // the guest points the used ring at main memory
      b.command("USED_PUSH", 0, Map("queue" -> 0, "id" -> 1, "len" -> 1))
      b.ackCommand()
      assert(b.guest.touched.isEmpty, "wrote a used ring outside the windows")
    }
  }
}
