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

/** Line requests onto UberDDR3's Wishbone port, one at a time; a write is answered once acknowledged. */
case class LinesToWishbone(addressWidth: Int) extends Component {
  val io = new Bundle {
    val cmd = slave(Stream(MemCmd()))
    val rsp = master(Stream(Bits(8 * Message.Size bits)))
    val wb = master(LineWishbone(addressWidth))
  }
  val issuing = Reg(Bool()) init False
  val waiting = Reg(Bool()) init False
  val rspValid = Reg(Bool()) init False
  val rspData = Reg(Bits(8 * Message.Size bits))
  io.cmd.ready := !issuing && !waiting && !rspValid
  val cmd = RegNextWhen(io.cmd.payload, io.cmd.fire)
  when(io.cmd.fire) { issuing := True }
  io.wb.cyc := True
  io.wb.stb := issuing
  io.wb.we := cmd.write
  io.wb.addr := (cmd.address >> log2Up(Message.Size)).resized
  io.wb.wdata := cmd.data
  io.wb.sel := cmd.mask
  when(issuing && !io.wb.stall) { issuing := False; waiting := True }
  when(waiting && io.wb.ack) { waiting := False; rspValid := True; rspData := io.wb.rdata }
  io.rsp.valid := rspValid
  io.rsp.payload := rspData
  when(io.rsp.fire) { rspValid := False }
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
  io.guestReset := core.io.guestReset

  // the hard block's stream: registered at the pins, then across
  val rxPins = pcieCd(io.rx.m2sPipe())
  core.io.rx << rxPins.queue(16, pcieCd, socCd)
  val txCross = core.io.tx.queue(16, socCd, pcieCd)
  io.tx << pcieCd(txCross.s2mPipe().m2sPipe())

  // main memory: across to the controller's clock and onto its Wishbone port
  val wb = ddrCd(LinesToWishbone(wbAddressWidth))
  wb.io.cmd << core.io.ram.queue(4, socCd, ddrCd)
  core.io.ramRsp << wb.io.rsp.queue(4, ddrCd, socCd)
  io.wb <> wb.io.wb
}

/** designs/soc-s5's prebuild: `sbt "runMain card.VerilogS5 <dir> <cluster.v>"` writes <dir>/CardS5.v. */
object VerilogS5 extends App {
  SpinalConfig(targetDirectory = args(0)).generateVerilog(CardS5(args(1)))
}
