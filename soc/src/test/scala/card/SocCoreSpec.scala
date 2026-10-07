package card

import java.nio.file.{Files, Paths}
import scala.collection.mutable
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.sim.{StreamDriver, StreamMonitor, StreamReadyRandomizer}
import card.contract._
import Contract.{Bar0, Message}
import TlpHost._

// S5b: the whole guest SoC in Verilator: VexiiRiscv's cluster (soc/vexii-cluster.sh -> tmp/vexii), the
// devices, the host link and a main-memory model. OpenSBI (linux/build-opensbi.sh -> tmp/sw) sits in
// main memory; the TLP-level host enables the link, releases GUEST_RESET and reads the console from
// CONSOLE_TX records. Skipped when the cluster or the firmware has not been generated.
class SocCoreSpec extends AnyFunSuite {
  val cluster = "tmp/vexii/VexiiCluster.v"
  val firmware = "tmp/sw/fw_payload.bin"
  // zero initial state: the cluster checks invariants on registers it never resets
  lazy val compiled = SimConfig.withConfig(SpinalConfig(targetDirectory = "tmp"))
    .addSimulatorFlag("--x-assign 0").addSimulatorFlag("--x-initial 0").compile {
      val c = SocCore(cluster)
      c.guest.toLines.io.axi.simPublic()
      c.guest.devices.io.axi.simPublic()
      c
    }
  val L = Message.Size

  /** Main memory as the board's controller would serve it: a line per request, in order. */
  class Ram(dut: SocCore) {
    val mem = new LineMemory
    val rsp = mutable.Queue[BigInt]()
    var reads, writes = 0L
    StreamReadyRandomizer(dut.io.ram, dut.clockDomain)
    StreamMonitor(dut.io.ram, dut.clockDomain) { p =>
      val a = p.address.toBigInt
      if (p.write.toBoolean) { mem.writeLine(a, p.data.toBigInt, p.mask.toBigInt); rsp.enqueue(0); writes += 1 }
      else { rsp.enqueue(mem.readLine(a)); reads += 1 }
    }
    StreamDriver(dut.io.ramRsp, dut.clockDomain) { p => if (rsp.nonEmpty) { p #= rsp.dequeue(); true } else false }
    /** Loads an image at `offset` from the start of main memory, skipping lines of zeros. */
    def load(offset: Long, image: Array[Byte]): Unit =
      for (i <- image.indices by L) {
        val chunk = image.slice(i, i + L).padTo(L, 0.toByte)
        if (chunk.exists(_ != 0)) mem.lines(BigInt(offset + i)) = chunk
      }
  }

  def console(rec: BigInt): Option[String] = {
    val l = Contract.record("CONSOLE_TX")
    if (((rec >> (8 * Message.KindOffset)) & 0xffff) != l.kind) return None
    val n = ((rec >> (8 * l.field("count").offset)) & 0xff).toInt
    Some((0 until n).map(i => ((rec >> (8 * (l.field("data").offset + i))) & 0xff).toChar).mkString)
  }

  // S7: four harts with coherent caches and a shared L2 (soc/vexii-cluster.sh tmp/vexii4 4, and
  // CARD_HARTS=4 linux/build-opensbi.sh ../soc/tmp/sw4). Slow: runs when SOC_SMP=1.
  test("four harts: OpenSBI finds them all and reaches its payload") {
    val cluster4 = "tmp/vexii4/VexiiCluster.v"; val firmware4 = "tmp/sw4/fw_payload.bin"
    val wanted = sys.env.contains("SOC_SMP") // a plain Boolean: assume() prints the operands of an expression
    val built = Files.exists(Paths.get(cluster4)) && Files.exists(Paths.get(firmware4))
    assume(wanted && built,
      "set SOC_SMP=1 after generating tmp/vexii4 and tmp/sw4")
    SimConfig.withConfig(SpinalConfig(targetDirectory = "tmp")).addSimulatorFlag("--x-assign 0")
      .addSimulatorFlag("--x-initial 0").compile(SocCore(cluster4)).doSim { dut =>
      dut.clockDomain.assertReset()
      dut.io.completerId #= CardId
      dut.io.busMaster #= true
      dut.io.ramReady #= true
      dut.io.linkReset #= false
      dut.clockDomain.forkStimulus(10)
      val host = new TlpHost(dut.io.rx, dut.io.tx, dut.clockDomain, BigInt("40000000", 16), BigInt("50000000", 16))
      val ram = new Ram(dut)
      ram.load(0, Files.readAllBytes(Paths.get(firmware4)))
      dut.clockDomain.waitSampling(20)
      host.setUp()
      host.hostWrite(Bar0.reg("GUEST_RESET"), 0)
      val out = new StringBuilder
      while (!out.toString.contains("Test payload running") && simTime() < 10 * 60000000L)
        host.takeRecord(10000).foreach { r => console(r).foreach { s => out ++= s; print(s.replace("\r", "")) } }
      println(s"\n[${simTime() / 10} cycles, ${ram.reads} line reads, ${ram.writes} line writes]")
      assert(out.toString.contains("Platform HART Count         : 4"), "OpenSBI did not find four harts")
      assert(out.toString.contains("Test payload running"), "OpenSBI did not reach its payload")
    }
  }

  test("a GUEST_RESET in the middle of a boot leaves the SoC bootable") {
    assume(Files.exists(Paths.get(cluster)) && Files.exists(Paths.get(firmware)))
    compiled.doSim { dut =>
      dut.clockDomain.assertReset()
      dut.io.completerId #= CardId
      dut.io.busMaster #= true
      dut.io.ramReady #= true
      dut.io.linkReset #= false
      dut.clockDomain.forkStimulus(10)
      val host = new TlpHost(dut.io.rx, dut.io.tx, dut.clockDomain, BigInt("40000000", 16), BigInt("50000000", 16))
      val ram = new Ram(dut)
      ram.load(0, Files.readAllBytes(Paths.get(firmware)))
      dut.clockDomain.waitSampling(20)
      host.setUp()
      val out = new StringBuilder
      def runUntil(text: String, cycles: Long): Unit = {
        val end = simTime() + 10 * cycles
        while (!out.toString.contains(text) && simTime() < end)
          host.takeRecord(10000).foreach(r => console(r).foreach(out ++= _))
      }
      for (cut <- Seq(400000L, 1234567L)) { // stop the cores while they are busy with memory, twice
        host.hostWrite(Bar0.reg("GUEST_RESET"), 0)
        dut.clockDomain.waitSampling(cut.toInt)
        host.hostWrite(Bar0.reg("GUEST_RESET"), 1)
        dut.clockDomain.waitSampling(100)
      }
      out.clear()
      ram.load(0, Files.readAllBytes(Paths.get(firmware))) // as cardd does: OpenSBI's first run changed its data
      host.hostWrite(Bar0.reg("GUEST_RESET"), 0)
      runUntil("OpenSBI v1.6", 5000000L)
      assert(out.toString.contains("OpenSBI v1.6"), s"no banner after the resets: ${out.toString.take(200)}")
    }
  }

  test("OpenSBI boots on the cluster and its banner reaches the host through CONSOLE_TX records") {
    assume(Files.exists(Paths.get(cluster)) && Files.exists(Paths.get(firmware)),
      "run soc/vexii-cluster.sh tmp/vexii and linux/build-opensbi.sh ../soc/tmp/sw first")
    compiled.doSim { dut =>
      dut.clockDomain.assertReset() // from the first evaluation: the cluster's assertions see no X state
      dut.io.completerId #= CardId
      dut.io.busMaster #= true
      dut.io.ramReady #= true
      dut.io.linkReset #= false
      dut.clockDomain.forkStimulus(10)
      val host = new TlpHost(dut.io.rx, dut.io.tx, dut.clockDomain, BigInt("40000000", 16), BigInt("50000000", 16))
      val ram = new Ram(dut)
      ram.load(0, Files.readAllBytes(Paths.get(firmware)))
      dut.clockDomain.waitSampling(20)
      if (sys.env.contains("SOC_TRACE")) { // SOC_TRACE=<first cycle>: the buses' transfers from then on
        val from = sys.env("SOC_TRACE").toLong
        val a = dut.guest.toLines.io.axi; val p = dut.guest.devices.io.axi
        fork {
          while (true) {
            dut.clockDomain.waitSampling()
            if (simTime() / 10 >= from) {
              def ev(s: String) = println(f"[${simTime() / 10}%8d] $s")
              if (a.ar.valid.toBoolean && a.ar.ready.toBoolean) ev(f"AR ${a.ar.addr.toLong}%08x len ${a.ar.len.toInt} size ${a.ar.size.toInt}")
              if (a.aw.valid.toBoolean && a.aw.ready.toBoolean) ev(f"AW ${a.aw.addr.toLong}%08x len ${a.aw.len.toInt} size ${a.aw.size.toInt}")
              if (p.ar.valid.toBoolean && p.ar.ready.toBoolean) ev(f"pAR ${p.ar.addr.toLong}%08x")
              if (p.r.valid.toBoolean && p.r.ready.toBoolean) ev(f"pR  ${p.r.data.toLong}%08x")
              if (p.aw.valid.toBoolean && p.aw.ready.toBoolean) ev(f"pAW ${p.aw.addr.toLong}%08x ${p.w.data.toLong}%08x strb ${p.w.strb.toInt}")
            }
          }
        }
      }
      host.setUp()
      host.hostWrite(Bar0.reg("GUEST_RESET"), 0)
      val out = new StringBuilder
      val budget = sys.env.get("SOC_CYCLES").map(_.toLong).getOrElse(20000000L) // cycles
      while (!out.toString.contains("Test payload running") && simTime() < 10 * budget)
        host.takeRecord(10000).foreach { r =>
          if (sys.env.contains("SOC_RECORDS")) println(f"\n[record seq ${(r & 0xffffffffL).toLong} kind ${((r >> 32) & 0xffff).toInt} at ${simTime() / 10}]")
          console(r).foreach { s => out ++= s; print(s.replace("\r", "")) }
        }
      println(s"\n[${simTime() / 10} cycles, ${ram.reads} line reads, ${ram.writes} line writes]")
      assert(out.toString.contains("OpenSBI"), "no OpenSBI banner")
      assert(out.toString.contains("Test payload running"), "OpenSBI did not reach its payload")
    }
  }
}
