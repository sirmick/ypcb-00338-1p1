package card

import spinal.core._
import spinal.lib._
import card.contract.Contract
import Contract.Message

// S5: the guest's console. A 16550 as Linux's 8250 driver and OpenSBI use it (registers 4 bytes apart,
// FIFOs, the four interrupt sources), whose wire is the host link: bytes the guest transmits leave in
// CONSOLE_TX records, and CONSOLE_RX commands fill its receive FIFO. Line settings (divisor, LCR,
// MCR) are stored and read back but change nothing; the modem lines read as connected (DCD, DSR, CTS).

case class Uart16550(fifoDepth: Int = 64, idleCycles: Int = 255) extends Component {
  val io = new Bundle {
    /** register index 0-7 (byte offset >> reg-shift), data in the low 8 bits */
    val bus = slave(RegBus(3))
    val rx = slave(Flow(Bits(8 bits)))
    val records = master(Stream(Bits(8 * Message.Size bits)))
    val irq = out Bool ()
  }
  private val consoleTx = Contract.record("CONSOLE_TX")
  private val maxBytes = consoleTx.field("data").bytes

  val ier = Reg(Bits(4 bits)) init 0
  val lcr = Reg(Bits(8 bits)) init 0
  val mcr = Reg(Bits(5 bits)) init 0
  val scr = Reg(Bits(8 bits)) init 0
  val dll = Reg(Bits(8 bits)) init 0
  val dlm = Reg(Bits(8 bits)) init 0
  val overrun = Reg(Bool()) init False
  val dlab = lcr(7)

  val rxFifo = StreamFifo(Bits(8 bits), fifoDepth)
  rxFifo.io.push.valid := io.rx.valid
  rxFifo.io.push.payload := io.rx.payload
  when(io.rx.valid && !rxFifo.io.push.ready) { overrun := True }
  rxFifo.io.pop.ready := False
  rxFifo.io.flush := False

  val txFifo = StreamFifo(Bits(8 bits), fifoDepth)
  txFifo.io.push.valid := False
  txFifo.io.push.payload := io.bus.wdata(7 downto 0)
  val txEmpty = txFifo.io.occupancy === 0

  // the transmitter-empty interrupt: raised when the FIFO drains, or when the driver enables it while
  // the FIFO is empty; cleared by writing THR or by reading IIR while it is the source
  val threPending = Reg(Bool()) init False
  when(txEmpty.rise(False)) { threPending := True }

  val rda = ier(0) && rxFifo.io.pop.valid
  val thri = ier(1) && threPending
  val rls = ier(2) && overrun
  val iirId = rls ? B"0110" | (rda ? B"0100" | (thri ? B"0010" | B"0001"))
  io.irq := !iirId(0)
  val lsr = B"0" ## txEmpty ## txEmpty ## B"00" ## B"0" ## overrun ## rxFifo.io.pop.valid

  val read = io.bus.valid && !io.bus.write
  val write = io.bus.valid && io.bus.write
  io.bus.ready := True
  io.bus.rdata := 0
  val d = io.bus.wdata(7 downto 0)
  switch(io.bus.address) {
    is(0) {
      when(dlab) { io.bus.rdata(7 downto 0) := dll; when(write) { dll := d } } otherwise {
        io.bus.rdata(7 downto 0) := rxFifo.io.pop.payload
        when(read) { rxFifo.io.pop.ready := True }
        when(write) { txFifo.io.push.valid := True; threPending := False }
      }
    }
    is(1) {
      when(dlab) { io.bus.rdata(7 downto 0) := dlm; when(write) { dlm := d } } otherwise {
        io.bus.rdata(3 downto 0) := ier
        when(write) {
          ier := d(3 downto 0)
          when(d(1) && txEmpty) { threPending := True }
        }
      }
    }
    is(2) {
      io.bus.rdata(7 downto 0) := B"11" ## B"00" ## iirId // FIFOs enabled
      when(read && iirId === B"0010") { threPending := False }
      when(write && d(1)) { rxFifo.io.flush := True }
    }
    is(3) { io.bus.rdata(7 downto 0) := lcr; when(write) { lcr := d } }
    is(4) { io.bus.rdata(4 downto 0) := mcr; when(write) { mcr := d(4 downto 0) } }
    is(5) { io.bus.rdata(7 downto 0) := lsr; when(read) { overrun := False } }
    is(6) { io.bus.rdata(7 downto 0) := B"10110000" }
    is(7) { io.bus.rdata(7 downto 0) := scr; when(write) { scr := d } }
  }
  txFifo.io.flush := False

  // the packer: up to 48 bytes per record, sent when full or when the guest pauses
  val buf = Vec.fill(maxBytes)(Reg(Bits(8 bits)))
  val count = Reg(UInt(log2Up(maxBytes + 1) bits)) init 0
  val idle = Reg(UInt(log2Up(idleCycles + 1) bits)) init 0
  val sending = Reg(Bool()) init False
  txFifo.io.pop.ready := !sending && count =/= maxBytes
  when(txFifo.io.pop.fire) { buf(count.resized) := txFifo.io.pop.payload; count := count + 1; idle := 0 }
  when(!txFifo.io.pop.fire && count =/= 0 && !sending && idle =/= idleCycles) { idle := idle + 1 }
  when(!sending && count =/= 0 && (count === maxBytes || idle === idleCycles)) { sending := True }
  io.records.valid := sending
  val data = Cat(buf.zipWithIndex.map { case (v, i) => (i < count) ? v | B(0, 8 bits) }) // nothing past count leaks
  io.records.payload := Pack(consoleTx, U(0, 16 bits), "count" -> count.asBits, "data" -> data)
  when(io.records.fire) { sending := False; count := 0; idle := 0 }
}
