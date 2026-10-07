package card

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import card.contract._
import Contract.{Bar0, Message}
import BenchConfig._

// S5: the boot window and the console. While GUEST_RESET holds the cores, the host may copy into main
// memory (how firmware and kernel get there); once they run, it may not. The 16550 sends what the
// guest writes as CONSOLE_TX records and receives CONSOLE_RX's bytes, with the interrupts Linux uses.
class ConsoleSpec extends AnyFunSuite {
  lazy val compiled = SimConfig.withConfig(SpinalConfig(targetDirectory = "tmp")).compile(CardLink(8))
  val Ram = BigInt(Contract.Map.Ram)
  val THR = 0; val IER = 1; val IIR = 2; val LCR = 3; val LSR = 5; val SCR = 7

  test("main memory is a copy target only while the guest is held in reset") {
    compiled.doSim { dut =>
      val b = new Bench(dut)
      assert(b.hostRead(Bar0.reg("GUEST_RESET")) == 1, "the guest must start held in reset")
      assert(dut.io.guestReset.toBoolean)
      b.setUp(4)
      b.host.write(StagingBase + 0x40, (0 until 128).map(_ & 0xff))
      b.command("COPY_FROM_HOST", 0, Map("tag" -> 1, "len" -> 128, "guest" -> (Ram + 0x200040), "staging" -> 0x40))
      b.expectRecord("COPY_DONE", 0, Map("tag" -> 1, "status" -> 0)); b.ackCommand()
      assert(b.guest.read(Ram + 0x200040, 128) == (0 until 128).map(_ & 0xff), "the boot copy did not land")
      b.command("COPY_TO_HOST", 0, Map("tag" -> 2, "len" -> 128, "guest" -> (Ram + 0x200040), "staging" -> 0x1040))
      b.expectRecord("COPY_DONE", 0, Map("tag" -> 2, "status" -> 0)); b.ackCommand()
      assert(b.host.read(StagingBase + 0x1040, 128) == (0 until 128).map(_ & 0xff), "reading back a boot copy failed")
      b.hostWrite(Bar0.reg("GUEST_RESET"), 0)
      dut.clockDomain.waitSampling(); sleep(1)
      assert(!dut.io.guestReset.toBoolean)
      val before = b.guest.touched
      for ((name, t) <- Seq("COPY_FROM_HOST" -> 3, "COPY_TO_HOST" -> 4)) {
        b.command(name, 0, Map("tag" -> t, "len" -> 64, "guest" -> (Ram + 0x200040), "staging" -> 0x40))
        b.expectRecord("COPY_DONE", 0, Map("tag" -> t, "status" -> Contract.CopyStatus.OutsideWindow)); b.ackCommand()
      }
      assert(b.guest.touched == before, "main memory was touched while the guest runs")
    }
  }

  test("the guest's bytes leave as CONSOLE_TX records, and are dropped while nobody listens") {
    compiled.doSim { dut =>
      val b = new Bench(dut)
      for (c <- "lost") b.uartWrite(THR, c.toInt) // the card is not enabled yet
      dut.clockDomain.waitSampling(600)
      assert((b.uartRead(LSR) & 0x60) == 0x60, "the transmitter must drain while the card is disabled")
      b.setUp(4)
      assert(b.records.isEmpty, "bytes written before the host enabled the card were kept")
      val msg = "Hello from the card's 16550, longer than one record can carry!"
      for (c <- msg) b.uartWrite(THR, c.toInt)
      def data(s: String) = BigInt(1, s.getBytes.reverse)
      b.expectRecord("CONSOLE_TX", 0, Map("count" -> 48, "data" -> data(msg.take(48))))
      b.expectRecord("CONSOLE_TX", 0, Map("count" -> (msg.length - 48), "data" -> data(msg.drop(48))))
      assert((b.uartRead(LSR) & 0x60) == 0x60)
    }
  }

  test("CONSOLE_RX fills the receive FIFO; DR, IIR and the interrupt follow it; overrun is reported") {
    compiled.doSim { dut =>
      val b = new Bench(dut)
      b.setUp(4)
      assert(b.uartRead(IIR) == 0xc1 && !dut.io.uartIrq.toBoolean, "an interrupt with nothing enabled")
      b.uartWrite(IER, 1) // received data available
      def rx(s: String): Unit = {
        b.command("CONSOLE_RX", 0, Map("count" -> s.length, "data" -> BigInt(1, s.getBytes.reverse)))
        b.ackCommand()
      }
      rx("ls\n")
      sleep(1)
      assert((b.uartRead(LSR) & 1) == 1 && dut.io.uartIrq.toBoolean && b.uartRead(IIR) == 0xc4)
      val got = (0 until 3).map(_ => b.uartRead(THR).toChar).mkString
      assert(got == "ls\n")
      assert((b.uartRead(LSR) & 1) == 0 && !dut.io.uartIrq.toBoolean, "DR or the interrupt stayed up")
      b.uartWrite(IER, 4) // line status only
      for (_ <- 0 until 2) rx("x" * 48) // 96 bytes into a 64-byte FIFO
      assert((b.uartRead(LSR) & 2) == 2, "no overrun reported")
      assert((b.uartRead(LSR) & 2) == 0, "reading LSR must clear the overrun bit")
      val kept = Iterator.continually(b.uartRead(THR)).takeWhile(_ => (b.uartRead(LSR) & 1) == 1).size
      assert(kept == 63, s"$kept bytes left after draining") // the first read happened before the check
    }
  }

  test("the transmitter-empty interrupt is raised on enabling and cleared by reading IIR or writing THR") {
    compiled.doSim { dut =>
      val b = new Bench(dut)
      b.setUp(4)
      b.uartWrite(IER, 2)
      dut.clockDomain.waitSampling(); sleep(1)
      assert(dut.io.uartIrq.toBoolean && b.uartRead(IIR) == 0xc2, "enabling THRI with the FIFO empty must interrupt")
      sleep(1)
      assert(!dut.io.uartIrq.toBoolean && b.uartRead(IIR) == 0xc1, "reading IIR must clear it")
      b.uartWrite(THR, 'a'.toInt)
      dut.clockDomain.waitSampling(20)
      assert(dut.io.uartIrq.toBoolean, "the FIFO drained without an interrupt")
      b.uartWrite(THR, 'b'.toInt)
      b.uartWrite(SCR, 0x5a); assert(b.uartRead(SCR) == 0x5a)
      b.uartWrite(LCR, 0x83); b.uartWrite(0, 0x1b); b.uartWrite(LCR, 0x03) // a divisor, as drivers set one
      assert(b.uartRead(LCR) == 3)
    }
  }
}
