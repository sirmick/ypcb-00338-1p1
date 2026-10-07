package card

import spinal.core._

/** Writes the host link's Verilog for synthesis checks: `sbt "runMain card.Verilog"` -> tmp/rtl/. */
object Verilog extends App {
  SpinalConfig(targetDirectory = "tmp/rtl").generateVerilog(CardLink())
}
