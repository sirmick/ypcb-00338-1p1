package card

import spinal.core._
import spinal.lib._
import card.contract.{Contract, Layout}
import Contract.{Message, VirtioMmio => V}

// S4: the host link over PCIe. PcieLink sits on the 7-series PCIE_2_1 hard block's 64-bit AXI-stream
// interface (the format regymm/pcie_7x's bridge uses) and carries everything CardLink needs:
//  - the host's reads and writes of BAR0 arrive as MemRd/MemWr TLPs and become RegBus accesses; a
//    read gets a CplD back;
//  - the card's posted writes into host memory (records, copied data) leave as MemWr TLPs, one per
//    64-byte line, covering just the masked bytes;
//  - COPY_FROM_HOST's reads of host staging leave as MemRd TLPs (16 DWs, one outstanding), and the
//    CplD that answers each becomes a line on hostReadRsp.
// Every DW on the stream, header or payload, is in PCIe's byte order: its first byte in bits 31:24, so
// a payload DW's lowest-addressed byte is its most significant (proven on the card, S4b: the first build
// assumed little-endian payloads and the host read the magic number byte-reversed). DW0 is in a beat's
// low half. The card does no DMA until the host sets Bus Master Enable.

/** One beat of the hard block's 64-bit AXI stream; `keep` has a bit per byte (0x0F or 0xFF). */
case class Axis64() extends Bundle {
  val data = Bits(64 bits)
  val keep = Bits(8 bits)
}

object Tlp {
  // fmt (3 bits) and type (5 bits), as DW0[31:24]
  val MRd32 = 0x00; val MRd64 = 0x20; val MWr32 = 0x40; val MWr64 = 0x60; val Cpl = 0x0a; val CplD = 0x4a
  val MaxDws = 4 + 16
  /** A payload DW between the stream's byte order and the little-endian order of the card's buses. */
  def swap(d: Bits): Bits = EndiannessSwap(d)
}

case class PcieLink(slots: Int = Contract.Map.VirtioSlots) extends Component {
  val io = new Bundle {
    val rx = slave(Stream(Fragment(Axis64())))
    val tx = master(Stream(Fragment(Axis64())))
    /** {bus, device, function}: the requester and completer ID */
    val completerId = in Bits (16 bits)
    /** the host has set Bus Master Enable in the command register */
    val busMaster = in Bool ()
    val guest = slave(RegBus(log2Up(slots) + 12))
    val mem = master(Stream(MemCmd()))
    val memRsp = slave(Stream(Bits(8 * Message.Size bits)))
    val irq = out Bits (slots bits)
  }
  val link = CardLink(slots)
  link.io.guest <> io.guest
  io.mem << link.io.mem
  link.io.memRsp << io.memRsp
  io.irq := link.io.irq

  // ---- receive: collect a TLP's DWs, then act on it ----
  val rxDws = Vec.fill(Tlp.MaxDws)(Reg(Bits(32 bits)))
  val rxCount = Reg(UInt(log2Up(Tlp.MaxDws + 2) bits)) init 0
  val rxOverflow = Reg(Bool()) init False
  val rxFull = Reg(Bool()) init False // a whole TLP is collected and waiting to be handled
  io.rx.ready := !rxFull
  when(io.rx.fire) {
    for (half <- 0 until 2) {
      val k = rxCount + half
      when(io.rx.fragment.keep(4 * half) && k < Tlp.MaxDws) { rxDws(k.resized) := io.rx.fragment.data(32 * half + 31 downto 32 * half) }
      when(io.rx.fragment.keep(4 * half) && k >= Tlp.MaxDws) { rxOverflow := True }
    }
    rxCount := rxCount + (io.rx.fragment.keep(4) ? U(2) | U(1))
    when(io.rx.last) { rxFull := True }
  }
  val dw0 = rxDws(0); val dw1 = rxDws(1); val dw2 = rxDws(2); val dw3 = rxDws(3)
  val fmtType = dw0(31 downto 24)
  val is4dw = dw0(29)
  val lengthDw = dw0(9 downto 0).asUInt
  val hdrDws = is4dw ? U(4, 3 bits) | U(3, 3 bits)
  val reqAddr = (is4dw ? dw3 | dw2).asUInt // low 32 bits of the address; BAR0 decodes the low 16

  // the completer: BAR0 accesses through RegBus
  val host = link.io.host
  host.valid := False
  host.write := False
  host.address := 0
  host.wdata := 0
  val wrIndex = Reg(UInt(5 bits)) init 0
  val cplDws = Vec.fill(4)(Reg(Bits(32 bits)))
  val cplPending = Reg(Bool()) init False

  // the requester's read: one MemRd outstanding, answered by one CplD of 16 DWs
  val rdPending = Reg(Bool()) init False
  val rdLine = Reg(Bits(8 * Message.Size bits))
  val rdDone = Reg(Bool()) init False
  link.io.hostReadRsp.valid := rdDone
  link.io.hostReadRsp.payload := rdLine
  when(link.io.hostReadRsp.fire) { rdDone := False }

  def finishRx(): Unit = { rxFull := False; rxCount := 0; rxOverflow := False; wrIndex := 0 }
  when(rxFull) {
    when(rxOverflow) {
      finishRx() // longer than any TLP the link uses: dropped
    } elsewhen (fmtType === Tlp.MWr32 || fmtType === Tlp.MWr64) {
      // a host write to BAR0: one RegBus write per DW
      host.valid := True
      host.write := True
      host.address := (reqAddr + (wrIndex.resize(32 bits) |<< 2))(15 downto 0)
      host.wdata := Tlp.swap(rxDws((hdrDws.resize(5 bits) + wrIndex).resized))
      when(host.ready) {
        wrIndex := wrIndex + 1
        when(wrIndex + 1 === lengthDw || wrIndex + 1 + hdrDws >= Tlp.MaxDws) { finishRx() }
      }
    } elsewhen ((fmtType === Tlp.MRd32 || fmtType === Tlp.MRd64) && !cplPending) {
      // a host read of BAR0: one DW, answered with a CplD (3 header DWs + the data)
      host.valid := True
      host.address := reqAddr(15 downto 0)
      when(host.ready) {
        cplDws(0) := B(Tlp.CplD, 8 bits) ## dw0(23 downto 10) ## B(1, 10 bits)
        cplDws(1) := io.completerId ## B(0, 4 bits) ## B(4, 12 bits) // status 0, byte count 4
        cplDws(2) := dw1(31 downto 16) ## dw1(15 downto 8) ## B(0, 1 bits) ## reqAddr(6 downto 2).asBits ## B(0, 2 bits)
        cplDws(3) := Tlp.swap(host.rdata)
        cplPending := True
        finishRx()
      }
    } elsewhen (fmtType === Tlp.CplD && rdPending) {
      // the completion of our read: 16 data DWs after a 3-DW header
      for (i <- 0 until 16) rdLine(32 * i + 31 downto 32 * i) := Tlp.swap(rxDws(3 + i))
      rdPending := False
      rdDone := True
      finishRx()
    } elsewhen (!(fmtType === Tlp.MRd32 || fmtType === Tlp.MRd64)) {
      finishRx() // anything else (messages, unexpected completions) is dropped
    }
  }

  // ---- the requester: lines out as MemWr, line reads out as MemRd ----
  // a register stage after CardLink, so its line arithmetic and the header's are not one long path
  val wr = link.io.hostWrite.m2sPipe()
  val firstByte = OHToUInt(OHMasking.first(wr.mask))
  val lastByte = U(Message.Size - 1) - OHToUInt(OHMasking.first(wr.mask.reversed))
  val startDw = firstByte(5 downto 2)
  val endDw = lastByte(5 downto 2)
  val nDw = (endDw - startDw).resize(5 bits) + 1
  val maskDw = wr.mask.subdivideIn(4 bits)
  val firstBe = maskDw(startDw)
  val lastBe = (startDw === endDw) ? B(0, 4 bits) | maskDw(endDw)
  val wrAddr = (wr.address(63 downto 6) @@ startDw @@ U(0, 2 bits)) // the line's address: no carry
  val wr64 = wr.address(63 downto 32) =/= 0
  val reqId = io.completerId

  // ---- transmit: one TLP at a time, completions first ----
  object Src extends SpinalEnum { val NONE, CPL, MWR, MRD = newElement() }
  val src = Reg(Src()) init Src.NONE
  val txIndex = Reg(UInt(5 bits)) init 0
  val txTotal = Reg(UInt(5 bits)) init 0
  val txHdr = Vec.fill(4)(Reg(Bits(32 bits)))
  val txHdrDws = Reg(UInt(3 bits))
  val txLine = Reg(Bits(8 * Message.Size bits))
  val txStart = Reg(UInt(4 bits))
  wr.ready := False
  link.io.hostRead.ready := False
  when(src === Src.NONE) {
    when(cplPending) {
      src := Src.CPL
      for (i <- 0 until 4) txHdr(i) := cplDws(i)
      txHdrDws := 4 // the completion's 3 header DWs and its data DW are sent as one block
      txTotal := 4
      txIndex := 0
    } elsewhen (io.busMaster && wr.valid) {
      src := Src.MWR
      txHdr(0) := B(wr64 ? U(Tlp.MWr64, 8 bits) | U(Tlp.MWr32, 8 bits)) ## B(0, 14 bits) ## nDw.resize(10 bits).asBits
      txHdr(1) := reqId ## B(0, 8 bits) ## lastBe ## firstBe
      txHdr(2) := wr64 ? wrAddr(63 downto 32).asBits | (wrAddr(31 downto 2) ## B(0, 2 bits))
      txHdr(3) := wrAddr(31 downto 2) ## B(0, 2 bits)
      txHdrDws := wr64 ? U(4, 3 bits) | U(3, 3 bits)
      txTotal := (wr64 ? U(4, 5 bits) | U(3, 5 bits)) + nDw
      txLine := wr.data
      txStart := startDw
      txIndex := 0
      wr.ready := True
    } elsewhen (io.busMaster && link.io.hostRead.valid && !rdPending && !rdDone) {
      src := Src.MRD
      val a = link.io.hostRead.payload
      val r64 = a(63 downto 32) =/= 0
      txHdr(0) := B(r64 ? U(Tlp.MRd64, 8 bits) | U(Tlp.MRd32, 8 bits)) ## B(0, 14 bits) ## B(16, 10 bits)
      txHdr(1) := reqId ## B(0, 8 bits) ## B(0xff, 8 bits)
      txHdr(2) := r64 ? a(63 downto 32).asBits | (a(31 downto 2) ## B(0, 2 bits))
      txHdr(3) := a(31 downto 2) ## B(0, 2 bits)
      txHdrDws := r64 ? U(4, 3 bits) | U(3, 3 bits)
      txTotal := r64 ? U(4, 5 bits) | U(3, 5 bits)
      txIndex := 0
      rdPending := True
      link.io.hostRead.ready := True
    }
  }
  def dwAt(k: UInt): Bits = {
    val d = Bits(32 bits)
    when(k < txHdrDws) { d := txHdr(k.resized) } otherwise {
      d := Tlp.swap(txLine.subdivideIn(32 bits)((txStart.resize(5 bits) + k - txHdrDws.resize(5 bits)).resized))
    }
    d
  }
  val k0 = txIndex
  val k1 = txIndex + 1
  val two = k1 < txTotal
  io.tx.valid := src =/= Src.NONE
  io.tx.fragment.data := dwAt(k1) ## dwAt(k0)
  io.tx.fragment.keep := two ? B(0xff, 8 bits) | B(0x0f, 8 bits)
  io.tx.last := txIndex + 2 >= txTotal
  when(io.tx.fire) {
    txIndex := txIndex + 2
    when(io.tx.last) {
      when(src === Src.CPL) { cplPending := False }
      src := Src.NONE
    }
  }
}
