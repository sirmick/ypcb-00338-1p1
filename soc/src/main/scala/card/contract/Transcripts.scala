package card.contract

import Contract.{VirtioMmio => V}

// Shared test transcripts: what a guest driver does, what the host programs and sends, and what each
// side must then observe. The RTL tests (SpinalSim) replay the guest and host steps against the card;
// cardd's tests (generated: cardd/tests/generated/transcripts.rs) replay the host side against a fake card.
// Records in a transcript carry sequence numbers 1, 2, 3, ... in order; so do commands.

sealed trait Step
/** The host writes a per-slot programming register in BAR0 (before the guest looks). */
case class HostProgram(slot: Int, reg: String, value: Long) extends Step
/** The guest writes a virtio-mmio register of a slot. */
case class GuestWrite(slot: Int, reg: String, value: Long) extends Step
/** The guest reads a virtio-mmio register of a slot and must see `expect`. */
case class GuestRead(slot: Int, reg: String, expect: Long) extends Step
/** The card must write this record into the host inbox next. */
case class ExpectRecord(name: String, slot: Int, values: Map[String, BigInt]) extends Step
/** The host writes this command into the command ring and rings CMD_PRODUCED. */
case class HostCommand(name: String, slot: Int, values: Map[String, BigInt]) extends Step
/** The slot's PLIC line must be at this level. */
case class ExpectIrq(slot: Int, level: Boolean) extends Step

case class Transcript(name: String, doc: String, steps: Seq[Step]) {
  /** For each step, the sequence number its message gets (records and commands count separately; 0 for
    * steps that are not messages). */
  def sequenceNumbers: Seq[Long] = {
    var r = 0L; var c = 0L
    steps.map {
      case _: ExpectRecord => r += 1; r
      case _: HostCommand => c += 1; c
      case _ => 0L
    }
  }
}

object Transcripts {
  private def lo(v: Long) = v & 0xffffffffL
  private def hi(v: Long) = (v >>> 32) & 0xffffffffL
  private val blkFeatures = (1L << V.FeatureEventIdx) | (1L << V.FeatureVersion1) | (1L << V.FeatureAccessPlatform)
  private val (desc, driver, device) = (V_DMA + 0x0000L, V_DMA + 0x1000L, V_DMA + 0x2000L)
  private def V_DMA = Contract.Map.DmaRegion

  /** A virtio-blk driver bringing up slot 0 (virtio 1.2, 3.1.1), one notify, one interrupt. */
  val blkInit = Transcript("blk_init", "virtio-blk on slot 0: driver initialisation, queue 0, a notify, an interrupt", Seq(
    HostProgram(0, "DEVICE_ID", V.DeviceBlk),
    HostProgram(0, "FEATURES_LO", lo(blkFeatures)),
    HostProgram(0, "FEATURES_HI", hi(blkFeatures)),
    HostProgram(0, "QUEUE_NUM_MAX", 256),
    GuestRead(0, "MagicValue", V.MagicValue),
    GuestRead(0, "Version", V.VersionValue),
    GuestRead(0, "DeviceID", V.DeviceBlk),
    GuestRead(0, "VendorID", V.VendorId),
    GuestWrite(0, "Status", 0),
    ExpectRecord("STATUS", 0, Map("status" -> 0)),
    GuestWrite(0, "Status", V.Acknowledge),
    ExpectRecord("STATUS", 0, Map("status" -> V.Acknowledge)),
    GuestWrite(0, "Status", V.Acknowledge | V.Driver),
    ExpectRecord("STATUS", 0, Map("status" -> (V.Acknowledge | V.Driver))),
    GuestWrite(0, "DeviceFeaturesSel", 0),
    GuestRead(0, "DeviceFeatures", lo(blkFeatures)),
    GuestWrite(0, "DeviceFeaturesSel", 1),
    GuestRead(0, "DeviceFeatures", hi(blkFeatures)),
    GuestWrite(0, "DriverFeaturesSel", 0),
    GuestWrite(0, "DriverFeatures", lo(blkFeatures)),
    GuestWrite(0, "DriverFeaturesSel", 1),
    GuestWrite(0, "DriverFeatures", hi(blkFeatures)),
    GuestWrite(0, "Status", V.Acknowledge | V.Driver | V.FeaturesOk),
    ExpectRecord("FEATURES", 0, Map("features" -> blkFeatures)),
    ExpectRecord("STATUS", 0, Map("status" -> (V.Acknowledge | V.Driver | V.FeaturesOk))),
    GuestRead(0, "Status", V.Acknowledge | V.Driver | V.FeaturesOk),
    GuestWrite(0, "QueueSel", 0),
    GuestRead(0, "QueueNumMax", 256),
    GuestWrite(0, "QueueNum", 128),
    GuestWrite(0, "QueueDescLow", lo(desc)), GuestWrite(0, "QueueDescHigh", hi(desc)),
    GuestWrite(0, "QueueDriverLow", lo(driver)), GuestWrite(0, "QueueDriverHigh", hi(driver)),
    GuestWrite(0, "QueueDeviceLow", lo(device)), GuestWrite(0, "QueueDeviceHigh", hi(device)),
    GuestWrite(0, "QueueReady", 1),
    ExpectRecord("QUEUE", 0, Map("queue" -> 0, "size" -> 128, "ready" -> 1, "desc" -> desc, "driver" -> driver, "device" -> device)),
    GuestRead(0, "QueueReady", 1),
    GuestWrite(0, "Status", V.Acknowledge | V.Driver | V.FeaturesOk | V.DriverOk),
    ExpectRecord("STATUS", 0, Map("status" -> (V.Acknowledge | V.Driver | V.FeaturesOk | V.DriverOk))),
    GuestWrite(0, "QueueNotify", 0),
    ExpectRecord("NOTIFY", 0, Map("queue" -> 0)),
    ExpectIrq(0, false),
    HostCommand("INTERRUPT", 0, Map("bits" -> 1)),
    ExpectRecord("CMD_ACK", 0, Map("cmd_seq" -> 1)),
    ExpectIrq(0, true),
    GuestRead(0, "InterruptStatus", 1),
    GuestWrite(0, "InterruptACK", 1),
    ExpectIrq(0, false),
    GuestRead(0, "InterruptStatus", 0)
  ))

  /** One example of every record and command, for encoding tests on both sides. */
  val everyMessage = Transcript("every_message", "one of each record and command, with fields near their limits", Seq(
    ExpectRecord("NOTIFY", 7, Map("queue" -> 1)),
    ExpectRecord("STATUS", 0, Map("status" -> 0x8f)),
    ExpectRecord("QUEUE", 3, Map("queue" -> 1, "size" -> 256, "ready" -> 1,
      "desc" -> BigInt("1fffff000", 16), "driver" -> BigInt("100000040", 16), "device" -> BigInt("17ffffc00", 16))),
    ExpectRecord("FEATURES", 1, Map("features" -> BigInt("300000000", 16))),
    ExpectRecord("COPY_DONE", 2, Map("tag" -> 0xdeadbeefL, "status" -> 1)),
    ExpectRecord("CONSOLE_TX", 0, Map("count" -> 5, "data" -> BigInt(1, "hello".getBytes.reverse))),
    ExpectRecord("CMD_ACK", 0, Map("cmd_seq" -> 0xfffffffeL)),
    HostCommand("COPY_TO_HOST", 2, Map("tag" -> 1, "len" -> 4096, "guest" -> BigInt("100002000", 16), "staging" -> 0x10000)),
    HostCommand("COPY_FROM_HOST", 2, Map("tag" -> 2, "len" -> 512, "guest" -> BigInt("100003000", 16), "staging" -> 0)),
    HostCommand("USED_PUSH", 0, Map("queue" -> 0, "id" -> 17, "len" -> 513)),
    HostCommand("INTERRUPT", 0, Map("bits" -> 3)),
    HostCommand("CONSOLE_RX", 0, Map("count" -> 2, "data" -> BigInt(1, "ls".getBytes.reverse)))
  ))

  val all = Seq(blkInit, everyMessage)
}
