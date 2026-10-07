package card

import spinal.core._
import spinal.lib._
import card.contract.Contract.{Message, Map => M}

// S5c: the guest SoC on the card. Three clock domains, joined by asynchronous FIFOs:
//  - pcie: the hard block's user clock (its 64-bit stream, registered on both sides: nextpnr does not
//    time the hard block's pins);
//  - soc:  the cores, the devices and the host link (SocCore), 50 MHz;
//  - ddr:  UberDDR3's controller clock; main memory is its Wishbone port, one 64-byte line per access.

/** UberDDR3's user port (pipelined Wishbone, one 512-bit line per transfer), as the master drives it. */
case class LineWishbone(addressWidth: Int) extends Bundle with IMasterSlave {
  val cyc, stb, we = Bool()
  val addr = UInt(addressWidth bits)
  val wdata = Bits(8 * Message.Size bits)
  val sel = Bits(Message.Size bits)
  val stall, ack = Bool()
  val rdata = Bits(8 * Message.Size bits)
  override def asMaster(): Unit = { out(cyc, stb, we, addr, wdata, sel); in(stall, ack, rdata) }
}

/** Line requests onto UberDDR3's pipelined Wishbone port, up to `depth` in flight: a request is issued
  * whenever the controller does not stall, and its answers (in order; a write is answered once
  * acknowledged) wait in a FIFO that credits keep from overflowing. */
case class LinesToWishbone(addressWidth: Int, depth: Int = 8) extends Component {
  val io = new Bundle {
    val cmd = slave(Stream(MemCmd()))
    val rsp = master(Stream(Bits(8 * Message.Size bits)))
    val wb = master(LineWishbone(addressWidth))
  }
  val answers = StreamFifo(Bits(8 * Message.Size bits), depth)
  val pending = Reg(UInt(log2Up(depth + 1) bits)) init 0 // issued or waiting to issue, not yet taken
  val stage = io.cmd.haltWhen(pending === depth).m2sPipe()
  io.wb.cyc := True
  io.wb.stb := stage.valid
  io.wb.we := stage.write
  io.wb.addr := (stage.address >> log2Up(Message.Size)).resized
  io.wb.wdata := stage.data
  io.wb.sel := stage.mask
  stage.ready := !io.wb.stall
  answers.io.push.valid := io.wb.ack
  answers.io.push.payload := io.wb.rdata
  io.rsp << answers.io.pop
  pending := pending + U(io.cmd.fire) - U(io.rsp.fire)
}

case class CardS5(clusterRtl: String, wbAddressWidth: Int = 25) extends Component {
  val io = new Bundle {
    val pcieClk, pcieReset = in Bool ()
    val rx = slave(Stream(Fragment(Axis64())))
    val tx = master(Stream(Fragment(Axis64())))
    val completerId = in Bits (16 bits)
    val busMaster = in Bool ()
    val ddrClk, ddrReset = in Bool ()
    val wb = master(LineWishbone(wbAddressWidth))
    /** UberDDR3 calibrated and its self-test done (ddr domain) */
    val ramReady = in Bool ()
    val guestReset = out Bool () // soc domain, for the debug register
  }
  val pcieCd = ClockDomain(io.pcieClk, io.pcieReset)
  val ddrCd = ClockDomain(io.ddrClk, io.ddrReset)
  val socCd = ClockDomain.current

  val core = SocCore(clusterRtl)
  core.io.completerId := BufferCC(io.completerId)
  core.io.busMaster := BufferCC(io.busMaster)
  core.io.ramReady := BufferCC(io.ramReady, False)
  core.io.linkReset := BufferCC(io.pcieReset, True) // a host that resets the link is starting over
  io.guestReset := core.io.guestReset

  // the hard block's stream: registered at the pins (reset with the hard block), then across. The crossing
  // FIFOs are reset only with the SoC, on both sides: a link reset that cleared just one side's pointers
  // would make the other side read stale entries as new TLPs.
  val pcieStableCd = ClockDomain(io.pcieClk, ResetCtrl.asyncAssertSyncDeassert(socCd.readResetWire, ClockDomain(io.pcieClk)))
  val rxPins = pcieCd(io.rx.m2sPipe())
  val rxCross = StreamFifoCC(Fragment(Axis64()), 16, pcieStableCd, socCd)
  rxCross.io.push << rxPins
  core.io.rx << rxCross.io.pop
  val txCross = StreamFifoCC(Fragment(Axis64()), 16, socCd, pcieStableCd)
  txCross.io.push << core.io.tx
  io.tx << pcieCd(txCross.io.pop.s2mPipe().m2sPipe())

  // main memory: across to the controller's clock and onto its Wishbone port
  val wb = ddrCd(LinesToWishbone(wbAddressWidth))
  wb.io.cmd << core.io.ram.queue(8, socCd, ddrCd)
  core.io.ramRsp << wb.io.rsp.queue(8, ddrCd, socCd)
  io.wb <> wb.io.wb
}

/** designs/soc-s5's prebuild: `sbt "runMain card.VerilogS5 <dir> <cluster.v>"` writes <dir>/CardS5.v. */
object VerilogS5 extends App {
  SpinalConfig(targetDirectory = args(0)).generateVerilog(CardS5(args(1)))
}
