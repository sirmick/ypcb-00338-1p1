// The card SoC: the host-link contract, the virtio-mmio shims, the mailbox and the DMA engine, in
// SpinalHDL, tested with SpinalSim (Verilator). `sbt test` runs every test; `sbt "runMain
// card.contract.Generate"` regenerates the contract's outputs (Rust, device tree, transcripts).
val spinalVersion = "1.12.2"

ThisBuild / scalaVersion := "2.13.14"
ThisBuild / organization := "card"

lazy val soc = (project in file("."))
  .settings(
    name := "card-soc",
    libraryDependencies ++= Seq(
      "com.github.spinalhdl" %% "spinalhdl-core" % spinalVersion,
      "com.github.spinalhdl" %% "spinalhdl-lib" % spinalVersion,
      compilerPlugin("com.github.spinalhdl" %% "spinalhdl-idsl-plugin" % spinalVersion),
      "org.scalatest" %% "scalatest" % "3.2.19" % Test
    ),
    fork := true,
    Test / parallelExecution := false
  )
