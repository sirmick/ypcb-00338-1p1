package card

import spinal.core._
import spinal.lib._
import card.contract.Contract
import Contract.Message

// S4b: the card as it first meets a real host. PcieLink on the hard block's AXI stream, and a block-RAM
// stand-in for guest memory where DDR3 will go: 64 KiB, so both DMA windows (the DMA region and the ring
// region) alias onto it by the address's low 16 bits. No guest CPU yet: the guest's register port is tied
// off, so the slots and their interrupts sit idle and the host drives everything through the mailbox.

/** Guest memory in block RAM: one 64-byte line per access, writes by byte mask, a response per command. */
case class GuestBram(bytes: Int = 64 * 1024) extends Component {
  val io = new Bundle {
    val cmd = slave(Stream(MemCmd()))
    val rsp = master(Stream(Bits(8 * Message.Size bits)))
  }
  val lines = bytes / Message.Size
  val ram = Mem(Bits(8 * Message.Size bits), lines)
  val rspValid = Reg(Bool()) init False
  io.cmd.ready := !rspValid || io.rsp.ready
  val index = io.cmd.address(log2Up(bytes) - 1 downto log2Up(Message.Size))
  ram.write(index, io.cmd.data, enable = io.cmd.fire && io.cmd.write, mask = io.cmd.mask)
  io.rsp.payload := ram.readSync(index, enable = io.cmd.fire && !io.cmd.write)
  when(io.rsp.fire) { rspValid := False }
  when(io.cmd.fire) { rspValid := True }
  io.rsp.valid := rspValid
}

case class CardS4() extends Component {
  val io = new Bundle {
    val rx = slave(Stream(Fragment(Axis64())))
    val tx = master(Stream(Fragment(Axis64())))
    val completerId = in Bits (16 bits)
    val busMaster = in Bool ()
    val irq = out Bits (Contract.Map.VirtioSlots bits)
  }
  val link = PcieLink()
  val mem = GuestBram()
  // Registers on both sides of the hard block: nextpnr has no timing model for PCIE_2_1's pins, so a
  // path into or out of them is never checked. The first build drove s_axis_tx_tdata straight from
  // the TLP mux, and on the card bits 18 and 25 arrived late in full-speed bursts (S4b).
  link.io.rx << io.rx.m2sPipe()
  io.tx << link.io.tx.s2mPipe().m2sPipe()
  link.io.completerId := io.completerId
  link.io.busMaster := io.busMaster
  link.io.guest.valid := False
  link.io.guest.write := False
  link.io.guest.address := 0
  link.io.guest.wdata := 0
  link.io.ramReady := True // block RAM
  link.io.linkReset := False // the whole card is in the hard block's clock domain and its reset
  link.io.uart.valid := False
  link.io.uart.write := False
  link.io.uart.address := 0
  link.io.uart.wdata := 0
  mem.io.cmd << link.io.mem
  link.io.memRsp << mem.io.rsp
  io.irq := link.io.irq
}

/** designs/soc-s4's prebuild: `sbt "runMain card.VerilogS4 <dir>"` writes <dir>/CardS4.v. */
object VerilogS4 extends App {
  SpinalConfig(targetDirectory = args.headOption.getOrElse("tmp/rtl")).generateVerilog(CardS4())
}
