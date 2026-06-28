package linxcore.rename

import chisel3._

import linxcore.bctrl.BID
import linxcore.common.{InterfaceParams, TULinkFlushSequenceSource}
import linxcore.recovery.RecoveryCleanupIntent
import linxcore.rob.ROBID

class TULinkFlushSourceSelectorIO(
    val p: InterfaceParams = InterfaceParams(),
    val mapQDepth: Int = 32,
    val bidWidth: Int = BID.DefaultWidth,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8)
    extends Bundle {
  val cleanup = Input(new RecoveryCleanupIntent(p.robEntries, bidWidth, peIdWidth, stidWidth, tidWidth))
  val robSource = Input(new TULinkFlushSequenceSource(p, mapQDepth, stidWidth))
  val lsuSource = Input(new TULinkFlushSequenceSource(p, mapQDepth, stidWidth))

  val source = Output(new TULinkFlushSequenceSource(p, mapQDepth, stidWidth))

  val cleanupActive = Output(Bool())
  val sourceRequired = Output(Bool())
  val robMatched = Output(Bool())
  val lsuMatched = Output(Bool())
  val robMismatched = Output(Bool())
  val lsuMismatched = Output(Bool())
  val multipleMatched = Output(Bool())
  val sourceConflict = Output(Bool())
  val sourceMissing = Output(Bool())
  val selectedFromRob = Output(Bool())
  val selectedFromLsu = Output(Bool())
}

class TULinkFlushSourceSelector(
    val p: InterfaceParams = InterfaceParams(),
    val mapQDepth: Int = 32,
    val bidWidth: Int = BID.DefaultWidth,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8)
    extends Module {
  require(mapQDepth > 1 && (mapQDepth & (mapQDepth - 1)) == 0, "T/U mapQ depth must be a power of two")

  val io = IO(new TULinkFlushSourceSelectorIO(
    p,
    mapQDepth,
    bidWidth,
    peIdWidth,
    stidWidth,
    tidWidth
  ))

  private def zeroSource: TULinkFlushSequenceSource = {
    val out = Wire(new TULinkFlushSequenceSource(p, mapQDepth, stidWidth))
    out := 0.U.asTypeOf(out)
    out
  }

  private def matches(source: TULinkFlushSequenceSource): Bool =
    source.valid &&
      ROBID.equal(source.bid, io.cleanup.flush.req.bid) &&
      ROBID.equal(source.rid, io.cleanup.flush.req.rid) &&
      (source.stid === io.cleanup.flush.req.stid)

  val cleanupActive = io.cleanup.valid && io.cleanup.backendFlushValid
  val sourceRequired = cleanupActive && !io.cleanup.flush.baseOnBid
  val robMatched = sourceRequired && matches(io.robSource)
  val lsuMatched = sourceRequired && matches(io.lsuSource)
  val multipleMatched = robMatched && lsuMatched
  val sameMatchedPayload = io.robSource.asUInt === io.lsuSource.asUInt
  val sourceConflict = multipleMatched && !sameMatchedPayload
  val selectedFromRob = robMatched && !sourceConflict
  val selectedFromLsu = !robMatched && lsuMatched && !sourceConflict

  val selected = Wire(new TULinkFlushSequenceSource(p, mapQDepth, stidWidth))
  selected := zeroSource
  when(selectedFromRob) {
    selected := io.robSource
  }.elsewhen(selectedFromLsu) {
    selected := io.lsuSource
  }

  io.source := selected
  io.cleanupActive := cleanupActive
  io.sourceRequired := sourceRequired
  io.robMatched := robMatched
  io.lsuMatched := lsuMatched
  io.robMismatched := sourceRequired && io.robSource.valid && !robMatched
  io.lsuMismatched := sourceRequired && io.lsuSource.valid && !lsuMatched
  io.multipleMatched := multipleMatched
  io.sourceConflict := sourceConflict
  io.sourceMissing := sourceRequired && !robMatched && !lsuMatched
  io.selectedFromRob := selectedFromRob
  io.selectedFromLsu := selectedFromLsu
}
