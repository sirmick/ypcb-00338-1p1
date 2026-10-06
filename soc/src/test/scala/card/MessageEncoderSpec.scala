package card

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import card.contract._

// The RTL packs every message in the shared transcripts into exactly the bytes the contract (and so
// cardd's tests) expect.
class MessageEncoderSpec extends AnyFunSuite {
  private val cases = for {
    t <- Transcripts.all
    (step, seq) <- t.steps.zip(t.sequenceNumbers)
    (layout, slot, values) <- step match {
      case ExpectRecord(n, s, v) => Seq((Contract.record(n), s, v))
      case HostCommand(n, s, v) => Seq((Contract.command(n), s, v))
      case _ => Nil
    }
  } yield (t.name, layout, seq, slot, values)

  for ((layout, group) <- cases.groupBy(_._2)) {
    test(s"${layout.name} (kind ${layout.kind}) packs as the contract says") {
      SimConfig.withConfig(SpinalConfig(targetDirectory = "tmp")).compile(MessageEncoder(layout)).doSim { dut =>
        for ((t, _, seq, slot, values) <- group) {
          dut.seq #= seq
          dut.slot #= slot
          for ((f, port) <- layout.fields.zip(dut.fields)) port #= values.getOrElse(f.name, BigInt(0))
          sleep(1)
          val expected = BigInt(1, Contract.encode(layout, seq, slot, values).reverse)
          assert(dut.bytes.toBigInt == expected, s"$t: ${layout.name} seq $seq")
        }
      }
    }
  }
}
