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

  test("SocCore and CardS5 elaborate: no combinational loops between the parts") {
    // the cluster stays a black box here, so this runs in the default suite; the parts' own tests cannot
    // see a loop that only closes when they are joined (the write fork and the arbiter's ready once did)
    SpinalConfig(targetDirectory = "tmp/elaborate").generateVerilog(SocCore("unused.v"))
    SpinalConfig(targetDirectory = "tmp/elaborate").generateVerilog(CardS5("unused.v"))
  }

  test("the line arbiter answers each master's requests, in order, with several in flight, under random traffic") {
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
          if (q.nonEmpty && issued(i) - got(i).size < 3) { // up to three in flight per master
            val (w, a, d) = q.dequeue(); p.write #= w; p.address #= a; p.data #= d; p.mask #= (BigInt(1) << L) - 1; issued(i) += 1; true
          } else false
        }
      }
      dut.clockDomain.waitSamplingWhere(got.forall(_.size == 40))
      for (i <- 0 until 3) assert(got(i).drop(20).toSeq == plans(i).map(_._2), s"master $i read back something else")
    }
  }

  /** Main memory behind a line port: answers in order, after a random delay, with random back-pressure. */
  def lineMemory(cmd: spinal.lib.Stream[MemCmd], rsp: spinal.lib.Stream[Bits], cd: ClockDomain, mem: LineMemory): Unit = {
    val q = mutable.Queue[(Long, BigInt)]()
    val r = new Random(9)
    StreamReadyRandomizer(cmd, cd)
    StreamMonitor(cmd, cd) { p =>
      val a = p.address.toBigInt
      val due = simTime() + 10 * (2 + r.nextInt(20))
      if (p.write.toBoolean) { mem.writeLine(a, p.data.toBigInt, p.mask.toBigInt); q.enqueue((due, 0)) } else q.enqueue((due, mem.readLine(a)))
    }
    StreamDriver(rsp, cd) { p => if (q.nonEmpty && q.head._1 <= simTime()) { p #= q.dequeue()._2; true } else false }
  }

  test("the AXI4 bridge keeps several bursts in flight: overlapping reads and writes, answers in order") {
    val cfg = SocCore.mBusConfig
    SimConfig.withConfig(SpinalConfig(targetDirectory = "tmp")).compile(Axi4ToLines(cfg, 0x80000000L)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      val mem = new LineMemory
      // one memory for both ports, as the SoC's arbiter gives them
      lineMemory(dut.io.read, dut.io.readRsp, dut.clockDomain, mem)
      lineMemory(dut.io.write, dut.io.writeRsp, dut.clockDomain, mem)
      val a = dut.io.axi
      val r = new Random(11)
      val base = 0x80000000L
      // writes: 24 bursts of 1-8 beats at random 8-byte-aligned addresses in their own lines, issued back to back
      val writes = (0 until 24).map(i => (base + 0x1000 + 0x80 * i + 8 * r.nextInt(8), 1 + r.nextInt(8), i))
      val wdata = writes.map { case (_, n, _) => Seq.fill(n)(BigInt(64, r)) }
      fork {
        for ((addr, n, id) <- writes) {
          a.aw.valid #= true; a.aw.addr #= addr; a.aw.len #= n - 1; a.aw.size #= 3; a.aw.burst #= 1; a.aw.id #= id
          dut.clockDomain.waitSamplingWhere(a.aw.ready.toBoolean)
        }
        a.aw.valid #= false
      }
      fork {
        for (((_, n, _), d) <- writes.zip(wdata); k <- 0 until n) {
          a.w.valid #= true; a.w.data #= d(k); a.w.strb #= 0xff; a.w.last #= k == n - 1
          dut.clockDomain.waitSamplingWhere(a.w.ready.toBoolean)
        }
        a.w.valid #= false
      }
      val bids = mutable.ArrayBuffer[Int]()
      a.b.ready #= true; a.r.ready #= true; a.ar.valid #= false
      fork { while (true) { dut.clockDomain.waitSampling(); if (a.b.valid.toBoolean) bids += a.b.id.toInt } }
      dut.clockDomain.waitSamplingWhere(bids.size == writes.size)
      assert(bids == writes.map(_._3), s"B responses out of order: $bids")
      for (((addr, n, _), d) <- writes.zip(wdata); k <- 0 until n)
        assert(mem.read(addr + 8 * k - base, 8) == (0 until 8).map(b => ((d(k) >> (8 * b)) & 0xff).toInt), f"write 0x${addr + 8 * k}%x")
      // reads: the same bursts back, issued back to back; beats must come in burst order
      val got = mutable.ArrayBuffer[(Int, BigInt)]()
      fork { while (true) { dut.clockDomain.waitSampling(); if (a.r.valid.toBoolean && a.r.ready.toBoolean) got += ((a.r.id.toInt, a.r.data.toBigInt)) } }
      fork { while (true) { a.r.ready #= r.nextInt(4) != 0; dut.clockDomain.waitSampling() } }
      for ((addr, n, id) <- writes) {
        a.ar.valid #= true; a.ar.addr #= addr; a.ar.len #= n - 1; a.ar.size #= 3; a.ar.burst #= 1; a.ar.id #= id
        dut.clockDomain.waitSamplingWhere(a.ar.ready.toBoolean)
      }
      a.ar.valid #= false
      dut.clockDomain.waitSamplingWhere(got.size == writes.map(_._2).sum)
      assert(got == writes.zip(wdata).flatMap { case ((_, n, id), d) => d.map(x => (id, x)) }, "read data or order wrong")
    }
  }

  test("UberDDR3's Wishbone port: requests issue back to back under stalls, answers return in order") {
    SimConfig.withConfig(SpinalConfig(targetDirectory = "tmp")).compile(LinesToWishbone(25)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      val wb = dut.io.wb
      val mem = new LineMemory
      val r = new Random(3)
      val acks = mutable.Queue[(Long, BigInt)]()
      var maxInFlight, inFlight = 0
      wb.stall #= true; wb.ack #= false
      fork {
        while (true) {
          wb.stall #= r.nextInt(3) == 0
          dut.clockDomain.waitSampling()
          if (wb.stb.toBoolean && !wb.stall.toBoolean) {
            val a = wb.addr.toBigInt * 64
            val v = if (wb.we.toBoolean) { mem.writeLine(a, wb.wdata.toBigInt, wb.sel.toBigInt); BigInt(0) } else mem.readLine(a)
            acks.enqueue((simTime() + 10 * (3 + r.nextInt(12)), v)); inFlight += 1; maxInFlight = maxInFlight max inFlight
          }
        }
      }
      fork {
        while (true) {
          dut.clockDomain.waitSampling()
          if (acks.nonEmpty && acks.head._1 <= simTime()) { wb.ack #= true; wb.rdata #= acks.dequeue()._2; inFlight -= 1 } else wb.ack #= false
        }
      }
      val plan = (0 until 40).map(i => (i % 2 == 0, BigInt(64 * (i / 2)), BigInt(512, r)))
      val cmds = mutable.Queue(plan: _*)
      StreamDriver(dut.io.cmd, dut.clockDomain) { p =>
        if (cmds.nonEmpty) { val (w, a, d) = cmds.dequeue(); p.write #= w; p.address #= a; p.data #= d; p.mask #= (BigInt(1) << 64) - 1; true } else false
      }
      val got = mutable.ArrayBuffer[BigInt]()
      StreamReadyRandomizer(dut.io.rsp, dut.clockDomain)
      StreamMonitor(dut.io.rsp, dut.clockDomain) { p => got += p.toBigInt }
      dut.clockDomain.waitSamplingWhere(got.size == plan.size)
      for (((w, _, d), i) <- plan.zipWithIndex if !w) assert(got(i) == plan(i - 1)._3, s"read $i returned the wrong line")
      assert(maxInFlight > 1, "never more than one request in flight")
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
