package linxcore.rename

import chisel3._

import linxcore.bctrl.BID
import linxcore.common.{DestinationKind, InterfaceParams}
import linxcore.recovery.RecoveryCleanupIntent
import linxcore.rob.ROBID

class TULinkFlushSequenceSource(
    val p: InterfaceParams = InterfaceParams(),
    val mapQDepth: Int = 32,
    val stidWidth: Int = 8)
    extends Bundle {
  val valid = Bool()
  val bid = new ROBID(p.robEntries)
  val rid = new ROBID(p.robEntries)
  val stid = UInt(stidWidth.W)
  val tSeq = new ROBID(mapQDepth)
  val uSeq = new ROBID(mapQDepth)
  val dstValid = Bool()
  val dstKind = DestinationKind()
}

class TULinkFlushSequencePublisherIO(
    val p: InterfaceParams = InterfaceParams(),
    val mapQDepth: Int = 32,
    val bidWidth: Int = BID.DefaultWidth,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8)
    extends Bundle {
  val cleanup = Input(new RecoveryCleanupIntent(p.robEntries, bidWidth, peIdWidth, stidWidth, tidWidth))
  val source = Input(new TULinkFlushSequenceSource(p, mapQDepth, stidWidth))

  val flushValid = Output(Bool())
  val flushBaseOnBid = Output(Bool())
  val flushBid = Output(new ROBID(p.robEntries))
  val flushRid = Output(new ROBID(p.robEntries))
  val flushTSeq = Output(new ROBID(mapQDepth))
  val flushUSeq = Output(new ROBID(mapQDepth))

  val sourceRequired = Output(Bool())
  val sourceMatched = Output(Bool())
  val missingSource = Output(Bool())
  val sourceMismatch = Output(Bool())
  val tPrevApplied = Output(Bool())
  val uPrevApplied = Output(Bool())
}

class TULinkFlushSequencePublisher(
    val p: InterfaceParams = InterfaceParams(),
    val mapQDepth: Int = 32,
    val bidWidth: Int = BID.DefaultWidth,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8)
    extends Module {
  require(mapQDepth > 1 && (mapQDepth & (mapQDepth - 1)) == 0, "T/U mapQ depth must be a power of two")

  val io = IO(new TULinkFlushSequencePublisherIO(
    p,
    mapQDepth,
    bidWidth,
    peIdWidth,
    stidWidth,
    tidWidth
  ))

  private def zeroSeq: ROBID =
    ROBID.zero(mapQDepth)

  val cleanupActive = io.cleanup.valid && io.cleanup.backendFlushValid
  val sourceRequired = cleanupActive && !io.cleanup.flush.baseOnBid
  val bidMatches = ROBID.equal(io.source.bid, io.cleanup.flush.req.bid)
  val ridMatches = ROBID.equal(io.source.rid, io.cleanup.flush.req.rid)
  val stidMatches = io.source.stid === io.cleanup.flush.req.stid
  val sourceMatched = io.source.valid && bidMatches && ridMatches && stidMatches
  val sourceUsable = !sourceRequired || sourceMatched

  val sourceTSeq = Wire(new ROBID(mapQDepth))
  val sourceUSeq = Wire(new ROBID(mapQDepth))
  sourceTSeq := zeroSeq
  sourceUSeq := zeroSeq
  when(io.source.valid) {
    sourceTSeq := io.source.tSeq
    sourceUSeq := io.source.uSeq
  }

  val ownsT = io.source.valid && io.source.dstValid && (io.source.dstKind === DestinationKind.T)
  val ownsU = io.source.valid && io.source.dstValid && (io.source.dstKind === DestinationKind.U)
  val publishedTSeq = Wire(new ROBID(mapQDepth))
  val publishedUSeq = Wire(new ROBID(mapQDepth))
  publishedTSeq := sourceTSeq
  publishedUSeq := sourceUSeq
  when(ownsT) {
    publishedTSeq := ROBID.sub(sourceTSeq, 1.U)
  }
  when(ownsU) {
    publishedUSeq := ROBID.sub(sourceUSeq, 1.U)
  }

  io.flushValid := cleanupActive && sourceUsable
  io.flushBaseOnBid := io.cleanup.flush.baseOnBid
  io.flushBid := io.cleanup.flush.req.bid
  io.flushRid := io.cleanup.flush.req.rid
  io.flushTSeq := publishedTSeq
  io.flushUSeq := publishedUSeq

  io.sourceRequired := sourceRequired
  io.sourceMatched := sourceMatched
  io.missingSource := sourceRequired && !io.source.valid
  io.sourceMismatch := sourceRequired && io.source.valid && !sourceMatched
  io.tPrevApplied := cleanupActive && sourceUsable && ownsT
  io.uPrevApplied := cleanupActive && sourceUsable && ownsU
}
