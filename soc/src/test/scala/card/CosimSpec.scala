package card

import java.io.{BufferedReader, File, InputStreamReader, PrintWriter}
import java.nio.file.Files
import scala.sys.process._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import card.contract._
import BenchConfig._

// S3's co-simulation: cardd's real backend (cardd/examples/cosim.rs, the same Link, CopyMem, queue
// and blk code cardd runs) drives the card's RTL. Each BAR access and host-memory access cardd makes
// is a line on its stdout, carried out here against the simulated card.
class CosimSpec extends AnyFunSuite {
  test("cardd serves the blk_read transcript's virtio-blk read against the RTL") {
    val carddDir = new File("../cardd")
    assert(Process(Seq("cargo", "build", "-q", "--example", "cosim"), carddDir).! == 0, "cargo build failed")
    val disk = Files.createTempFile("cosim-disk", ".img")
    val image = Array.fill[Byte](16 * 512)(0)
    for ((v, i) <- Transcripts.sector5.zipWithIndex) image(5 * 512 + i) = v.toByte
    Files.write(disk, image)
    val t = Transcripts.blkRead

    SimConfig.withConfig(SpinalConfig(targetDirectory = "tmp")).compile(CardLink(8)).doSim { dut =>
      val b = new Bench(dut)
      val pb = new java.lang.ProcessBuilder(new File(carddDir, "target/debug/examples/cosim").getPath,
        InboxBase.toString(16), StagingBase.toString(16), StagingSize.toHexString, disk.toString)
      pb.redirectError(java.lang.ProcessBuilder.Redirect.INHERIT)
      val p = pb.start()
      val in = new BufferedReader(new InputStreamReader(p.getInputStream))
      val out = new PrintWriter(p.getOutputStream)
      def reply(s: String): Unit = { out.println(s); out.flush() }
      def hex(bytes: Seq[Int]) = bytes.map(x => f"$x%02x").mkString
      def unhex(s: String) = s.grouped(2).map(Integer.parseInt(_, 16)).toSeq

      // the guest's side of blk_read, up to its notify; the driver accepts what the device offers
      def guestSide(): Unit = {
        val offered = scala.collection.mutable.Map[Long, Long]()
        var sel = 0L
        val upToNotify = t.steps.takeWhile { case GuestWrite(_, "QueueNotify", _) => false; case _ => true } ++
          t.steps.find { case GuestWrite(_, "QueueNotify", _) => true; case _ => false }
        for (s <- upToNotify) s match {
          case GuestWrite(slot, "DeviceFeaturesSel", v) => sel = v; b.guestWrite(slot, "DeviceFeaturesSel", v)
          case GuestRead(slot, "DeviceFeatures", _) => offered(sel) = b.guestRead(slot, "DeviceFeatures").toLong
          case GuestWrite(slot, "DriverFeaturesSel", v) => sel = v; b.guestWrite(slot, "DriverFeaturesSel", v)
          case GuestWrite(slot, "DriverFeatures", _) => b.guestWrite(slot, "DriverFeatures", BigInt(offered.getOrElse(sel, 0L)))
          case GuestWrite(slot, reg, v) => b.guestWrite(slot, reg, v)
          case GuestRead(slot, reg, v) => assert(b.guestRead(slot, reg) == v, s"guest read $reg")
          case GuestMem(a, bytes) => b.guest.write(a, bytes)
          case _ =>
        }
      }

      var served = -1
      var requests = 0
      while (served < 0) {
        val line = in.readLine()
        assert(line != null, "cardd's cosim driver exited early")
        requests += 1
        val f = line.split(" ")
        f(0) match {
          case "W" => b.hostWrite(f(1).toInt, BigInt(f(2)))
          case "R" => reply(b.hostRead(f(1).toInt).toString)
          case "I" => dut.clockDomain.waitSampling(4); reply(hex(b.host.read(InboxBase + f(1).toInt, f(2).toInt)))
          case "S" => reply(hex(b.host.read(StagingBase + f(1).toInt, f(2).toInt)))
          case "T" => b.host.write(StagingBase + f(1).toInt, unhex(f(2)))
          case "G" => guestSide(); reply("ok")
          case "D" => served = f(1).toInt
          case other => fail(s"unknown request: $line")
        }
      }
      assert(p.waitFor() == 0, "cardd's cosim driver failed")
      assert(served == 1)
      for (s <- t.steps) s match {
        case ExpectGuestMem(a, bytes) => assert(b.guest.read(a, bytes.size) == bytes, s"guest memory at 0x${a.toHexString}")
        case _ =>
      }
      b.irqSettles(0, true)
      assert(b.guestRead(0, "InterruptStatus") == 1)
      println(s"co-simulation: cardd made $requests requests; ${simTime() / 10} cycles")
    }
    Files.deleteIfExists(disk)
  }
}
