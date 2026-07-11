package linxcore.rob

import chisel3._
import chisel3.util.log2Ceil
import linxcore.recovery.FullBidRecoveryBridge

class ROBRecoveryWatermarkIO(
    val entries: Int,
    val stidCount: Int,
    val stidWidth: Int,
    val blockBidWidth: Int)
    extends Bundle {
  private val ptrWidth = log2Ceil(entries)

  val commitHead = Input(UInt(ptrWidth.W))
  val rowValid = Input(Vec(entries, Bool()))
  val rowStatus = Input(Vec(entries, ROBEntryStatus()))
  val rowStid = Input(Vec(entries, UInt(stidWidth.W)))
  val rowRid = Input(Vec(entries, new ROBID(entries)))
  val rowBlockBid = Input(Vec(entries, UInt(blockBidWidth.W)))

  val oldestValid = Output(Vec(stidCount, Bool()))
  val oldestRid = Output(Vec(stidCount, new ROBID(entries)))
  val oldestBlockBid = Output(Vec(stidCount, UInt(blockBidWidth.W)))
}

class ROBRecoveryWatermark(
    val entries: Int = 16,
    val stidCount: Int = 1,
    val stidWidth: Int = 8,
    val blockBidWidth: Int = 64)
    extends Module {
  require(entries > 1, "recovery watermark entries must be greater than one")
  require((entries & (entries - 1)) == 0, "recovery watermark entries must be a power of two")
  require(stidCount > 0, "recovery watermark must track at least one STID")
  require(BigInt(stidCount) <= (BigInt(1) << stidWidth), "recovery watermark STID count must fit stidWidth")

  private val ptrWidth = log2Ceil(entries)
  require(blockBidWidth > 0, "recovery watermark block BID width must be positive")
  val io = IO(new ROBRecoveryWatermarkIO(entries, stidCount, stidWidth, blockBidWidth))

  for (stid <- 0 until stidCount) {
    var found: Bool = false.B
    var selected: ROBID = 0.U.asTypeOf(new ROBID(entries))
    var selectedBlockBid: UInt = 0.U(blockBidWidth.W)

    for (offset <- 0 until entries) {
      val sum = io.commitHead +& offset.U
      val index = Mux(sum >= entries.U, sum - entries.U, sum)(ptrWidth - 1, 0)
      val eligible = io.rowValid(index) &&
        io.rowStid(index) === stid.U(stidWidth.W) &&
        io.rowStatus(index) =/= ROBEntryStatus.Free &&
        io.rowStatus(index) =/= ROBEntryStatus.Retired
      selected = Mux(!found && eligible, io.rowRid(index), selected)
      selectedBlockBid = Mux(!found && eligible, io.rowBlockBid(index), selectedBlockBid)
      found = found || eligible
    }

    io.oldestValid(stid) := found
    io.oldestRid(stid) := selected
    io.oldestBlockBid(stid) := selectedBlockBid
  }
}

class RecoveryWatermarkJoinIO(
    val entries: Int,
    val stidCount: Int,
    val bidWidth: Int)
    extends Bundle {
  val brobValid = Input(Vec(stidCount, Bool()))
  val brobBlockBid = Input(Vec(stidCount, UInt(bidWidth.W)))
  val brobComplete = Input(Vec(stidCount, Bool()))
  val robValid = Input(Vec(stidCount, Bool()))
  val robRid = Input(Vec(stidCount, new ROBID(entries)))
  val robBlockBid = Input(Vec(stidCount, UInt(bidWidth.W)))

  val oldestValid = Output(Vec(stidCount, Bool()))
  val oldestBlockBid = Output(Vec(stidCount, UInt(bidWidth.W)))
  val oldestBid = Output(Vec(stidCount, new ROBID(entries)))
  val oldestRid = Output(Vec(stidCount, new ROBID(entries)))
  val oldestBlockComplete = Output(Vec(stidCount, Bool()))
}

class RecoveryWatermarkJoin(
    val entries: Int = 16,
    val stidCount: Int = 1,
    val bidWidth: Int = 64)
    extends Module {
  require(entries > 1, "recovery watermark join entries must be greater than one")
  require((entries & (entries - 1)) == 0, "recovery watermark join entries must be a power of two")
  require(stidCount > 0, "recovery watermark join must track at least one STID")
  require(bidWidth > log2Ceil(entries), "recovery watermark join BID must include uniqueness bits")

  val io = IO(new RecoveryWatermarkJoinIO(entries, stidCount, bidWidth))

  for (stid <- 0 until stidCount) {
    val fullBidMatches = io.robBlockBid(stid) === io.brobBlockBid(stid)
    val valid = io.brobValid(stid) && io.robValid(stid) && fullBidMatches
    io.oldestValid(stid) := valid
    io.oldestBlockBid(stid) := io.brobBlockBid(stid)
    io.oldestBid(stid) := FullBidRecoveryBridge.fullBidToRobId(
      io.brobBlockBid(stid),
      valid,
      entries,
      bidWidth
    )
    io.oldestRid(stid) := io.robRid(stid)
    io.oldestBlockComplete(stid) := valid && io.brobComplete(stid)
  }
}
