package card

import scala.collection.mutable
import scala.util.Random
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.sim.{StreamDriver, StreamMonitor, StreamReadyRandomizer}
import card.contract.Contract.Message

// S5: the SoC's glue on its own. The line arbiter returns each response to the master that asked;
// the AXI4 bridge turns bursts of any size and alignment into line reads and masked line writes.
class SocPartsSpec extends AnyFunSuite {
  val L = Message.Size

  test("the line arbiter answers each master's requests, in order, under random traffic") {
    SimConfig.withConfig(SpinalConfig(targetDirectory = "tmp")).compile(LineArbiter(3)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      val mem = new LineMemory
      val rsp = mutable.Queue[BigInt]()
      StreamReadyRandomizer(dut.io.down, dut.clockDomain)
      StreamMonitor(dut.io.down, dut.clockDomain) { p =>
        val a = p.address.toBigInt
        if (p.write.toBoolean) { mem.writeLine(a, p.data.toBigInt, p.mask.toBigInt); rsp.enqueue(0) } else rsp.enqueue(mem.readLine(a))
      }
      StreamDriver(dut.io.downRsp, dut.clockDomain) { p => if (rsp.nonEmpty) { p #= rsp.dequeue(); true } else false }
      val got = Array.fill(3)(mutable.Queue[BigInt]())
      for (i <- 0 until 3) {
        StreamReadyRandomizer(dut.io.upRsp(i), dut.clockDomain)
        StreamMonitor(dut.io.upRsp(i), dut.clockDomain) { p => got(i).enqueue(p.toBigInt) }
      }
      // each master writes its own lines, then reads them back
      val plans = (0 until 3).map { i =>
        val r = new Random(i)
        (0 until 20).map(k => (BigInt(0x10000 * i + L * k), BigInt(512, r)))
      }
      val issued = Array.fill(3)(0)
      for (i <- 0 until 3) {
        val writes = plans(i).map { case (a, d) => (true, a, d) }
        val reads = plans(i).map { case (a, _) => (false, a, BigInt(0)) }
        val q = mutable.Queue((writes ++ reads): _*)
        StreamDriver(dut.io.up(i), dut.clockDomain) { p =>
          if (q.nonEmpty && got(i).size == issued(i)) { // one outstanding per master, as the SoC's masters are
            val (w, a, d) = q.dequeue(); p.write #= w; p.address #= a; p.data #= d; p.mask #= (BigInt(1) << L) - 1; issued(i) += 1; true
          } else false
        }
      }
      dut.clockDomain.waitSamplingWhere(got.forall(_.size == 40))
      for (i <- 0 until 3) assert(got(i).drop(20).toSeq == plans(i).map(_._2), s"master $i read back something else")
    }
  }

  test("AXI4 bursts become line accesses: narrow, unaligned and line-crossing bursts read and write the right bytes") {
    val cfg = SocCore.mBusConfig
    SimConfig.withConfig(SpinalConfig(targetDirectory = "tmp")).compile(Axi4ToLines(cfg, 0x80000000L)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      val mem = new LineMemory
      var lineOps = 0
      for ((c, r) <- Seq((dut.io.read, dut.io.readRsp), (dut.io.write, dut.io.writeRsp))) {
        val q = mutable.Queue[BigInt]()
        StreamReadyRandomizer(c, dut.clockDomain)
        StreamMonitor(c, dut.clockDomain) { p =>
          lineOps += 1
          val a = p.address.toBigInt
          if (p.write.toBoolean) { mem.writeLine(a, p.data.toBigInt, p.mask.toBigInt); q.enqueue(0) } else q.enqueue(mem.readLine(a))
        }
        StreamDriver(r, dut.clockDomain) { p => if (q.nonEmpty) { p #= q.dequeue(); true } else false }
      }
      val a = dut.io.axi
      a.ar.valid #= false; a.aw.valid #= false; a.w.valid #= false; a.r.ready #= true; a.b.ready #= true
      val model = new LineMemory
      val r = new Random(5)
      def write(addr: Long, size: Int, beats: Int): Unit = {
        a.aw.valid #= true; a.aw.addr #= addr; a.aw.len #= beats - 1; a.aw.size #= size; a.aw.burst #= 1; a.aw.id #= 3
        dut.clockDomain.waitSamplingWhere(a.aw.ready.toBoolean); a.aw.valid #= false
        var x = addr
        for (k <- 0 until beats) {
          val data = BigInt(64, r)
          val lane = (x % 8).toInt
          val strb = ((1 << (1 << size)) - 1) << lane
          a.w.valid #= true; a.w.data #= data; a.w.strb #= strb; a.w.last #= k == beats - 1
          dut.clockDomain.waitSamplingWhere(a.w.ready.toBoolean)
          for (b <- 0 until 8 if ((strb >> b) & 1) == 1) model.write(x - lane + b - 0x80000000L, Seq(((data >> (8 * b)) & 0xff).toInt))
          x += 1 << size
        }
        a.w.valid #= false
        dut.clockDomain.waitSamplingWhere(a.b.valid.toBoolean)
        assert(a.b.id.toInt == 3)
      }
      def read(addr: Long, size: Int, beats: Int): Unit = {
        a.ar.valid #= true; a.ar.addr #= addr; a.ar.len #= beats - 1; a.ar.size #= size; a.ar.burst #= 1; a.ar.id #= 5
        dut.clockDomain.waitSamplingWhere(a.ar.ready.toBoolean); a.ar.valid #= false
        var x = addr
        for (k <- 0 until beats) {
          dut.clockDomain.waitSamplingWhere(a.r.valid.toBoolean)
          val lane = (x % 8).toInt
          val want = model.read(x - lane - 0x80000000L, 8)
          val got = (0 until 8).map(b => ((a.r.data.toBigInt >> (8 * b)) & 0xff).toInt)
          for (b <- lane until lane + (1 << size)) assert(got(b) == want(b), f"read 0x$x%x beat $k byte $b")
          assert(a.r.last.toBoolean == (k == beats - 1) && a.r.id.toInt == 5)
          x += 1 << size
        }
      }
      write(0x80000000L, 3, 8)        // a whole line, as the caches write
      read(0x80000000L, 3, 8)
      val before = lineOps
      read(0x80000000L, 3, 8)
      assert(lineOps - before == 1, "a line burst must cost one line read")
      write(0x80000038L, 3, 4)        // crosses into the next line
      read(0x80000030L, 3, 6)
      write(0x80000101L, 0, 5)        // bytes
      write(0x80000106L, 1, 3)        // halfwords
      write(0x8000010cL, 2, 2)        // words
      read(0x80000100L, 0, 20)
      read(0x80000100L, 3, 3)
    }
  }
}
