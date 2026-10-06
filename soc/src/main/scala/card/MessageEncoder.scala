package card

import spinal.core._
import card.contract.{Contract, Layout}

/** Packs one kind of record or command into its 64 bytes, laid out as the contract says:
  * byte k of the message is bits 8k+7..8k of `bytes`. Combinational. */
case class MessageEncoder(layout: Layout) extends Component {
  val seq = in UInt (32 bits)
  val slot = in UInt (16 bits)
  val fields = layout.fields.map(f => in(UInt(8 * f.bytes bits)).setName("field_" + f.name))
  val bytes = out Bits (8 * Contract.Message.Size bits)

  private def at(byteOffset: Int, byteCount: Int) = bytes(8 * (byteOffset + byteCount) - 1 downto 8 * byteOffset)
  bytes := 0
  at(Contract.Message.SeqOffset, 4) := seq.asBits
  at(Contract.Message.KindOffset, 2) := B(layout.kind, 16 bits)
  at(Contract.Message.SlotOffset, 2) := slot.asBits
  at(Contract.Message.TailOffset, 4) := seq.asBits
  for ((f, port) <- layout.fields.zip(fields)) at(f.offset, f.bytes) := port.asBits
}
