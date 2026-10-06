package card

import java.nio.file.{Files, Paths}
import org.scalatest.funsuite.AnyFunSuite
import card.contract._

class ContractSpec extends AnyFunSuite {
  test("the generated files are up to date (run: sbt \"runMain card.contract.Generate\")") {
    for ((rel, text) <- Generate.outputs) {
      val p = Paths.get(rel)
      assert(Files.exists(p), s"$p is missing")
      assert(Files.readString(p) == text, s"$p is stale")
    }
  }
  test("messages put the sequence number at both ends") {
    val b = Contract.encode(Contract.record("NOTIFY"), 0x01020304L, 5, Map("queue" -> 1))
    assert(b.take(4).toSeq == Seq[Byte](4, 3, 2, 1) && b.takeRight(4).toSeq == Seq[Byte](4, 3, 2, 1))
    assert(b(4) == 1 && b(6) == 5 && b(8) == 1)
  }
}
