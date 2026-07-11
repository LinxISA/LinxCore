package linxcore.recovery

import chisel3._
import chisel3.util.log2Ceil

import linxcore.bctrl.BID
import linxcore.rob.ROBID

class RecoveryFabricIO(
    val sourceCount: Int,
    val stidCount: Int,
    val peCount: Int,
    val entries: Int,
    val bidWidth: Int = BID.DefaultWidth,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8)
    extends Bundle {
  private val sourceIndexWidth = math.max(1, log2Ceil(sourceCount))
  private val stidIndexWidth = math.max(1, log2Ceil(stidCount))
  private val peIndexWidth = math.max(1, log2Ceil(peCount))

  val sources = Input(Vec(sourceCount, new FullBidFlushReq(entries, bidWidth, peIdWidth, stidWidth, tidWidth)))
  val sourceReady = Output(Vec(sourceCount, Bool()))
  val sourceAccepted = Output(Vec(sourceCount, Bool()))
  val sourceBlockedByStid = Output(Vec(sourceCount, Bool()))
  val sourceBlockedByPe = Output(Vec(sourceCount, Bool()))
  val oldestBid = Input(Vec(stidCount, new ROBID(entries)))
  val oldestBlockComplete = Input(Vec(stidCount, Bool()))

  val intentReady = Input(Bool())
  val intent = Output(new RecoveryCleanupIntent(entries, bidWidth, peIdWidth, stidWidth, tidWidth))
  val intentAccepted = Output(Bool())
  val intentConsumed = Output(Bool())
  val pending = Output(Bool())

  val sourcePendingMask = Output(UInt(sourceCount.W))
  val sourceSelectedValid = Output(Bool())
  val sourceSelected = Output(UInt(sourceIndexWidth.W))
  val sourceSelectedStid = Output(UInt(stidIndexWidth.W))
  val sourceSelectedBlockBid = Output(UInt(bidWidth.W))
  val classSelected = Output(RecoveryActionClass())
  val classSelectedStid = Output(UInt(stidIndexWidth.W))
  val classSelectedPe = Output(UInt(peIndexWidth.W))
  val classGlobalFlushPendingMask = Output(UInt(stidCount.W))
  val classGlobalReplayPendingMask = Output(UInt(stidCount.W))
  val classPePendingMask = Output(UInt((stidCount * peCount).W))
  val classDroppedByOlder = Output(Bool())
  val classDroppedByComplete = Output(Bool())
  val classMerged = Output(Bool())
  val classBlockedByStid = Output(Bool())
  val classBlockedByPe = Output(Bool())
}

/** Canonical retained recovery composition.
  *
  * Producer arbitration, model class resolution, and cleanup fanout remain
  * separate child owners. This wrapper defines their production handshake and
  * exposes enough state to prove that no report is lost between boundaries.
  */
class RecoveryFabric(
    val sourceCount: Int,
    val stidCount: Int,
    val peCount: Int,
    val entries: Int = 16,
    val bidWidth: Int = BID.DefaultWidth,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8)
    extends Module {
  val io = IO(new RecoveryFabricIO(
    sourceCount,
    stidCount,
    peCount,
    entries,
    bidWidth,
    peIdWidth,
    stidWidth,
    tidWidth
  ))

  val sourceArbiter = Module(new RecoverySourceArbiter(
    sourceCount,
    stidCount,
    entries,
    bidWidth,
    peIdWidth,
    stidWidth,
    tidWidth
  ))
  val classMerge = Module(new RecoveryClassMerge(
    stidCount,
    peCount,
    entries,
    bidWidth,
    peIdWidth,
    stidWidth,
    tidWidth
  ))
  val cleanup = Module(new RecoveryCleanupControl(
    entries,
    bidWidth,
    peIdWidth,
    stidWidth,
    tidWidth
  ))

  val filteredSources = Wire(chiselTypeOf(io.sources))
  for (source <- 0 until sourceCount) {
    val peInRange = io.sources(source).peId < peCount.U
    filteredSources(source) := io.sources(source)
    filteredSources(source).valid := io.sources(source).valid && peInRange
  }
  sourceArbiter.io.sources := filteredSources
  sourceArbiter.io.oldestBid := io.oldestBid
  sourceArbiter.io.outReady := classMerge.io.inReady

  classMerge.io.in := sourceArbiter.io.out
  classMerge.io.oldestBid := io.oldestBid
  classMerge.io.oldestBlockComplete := io.oldestBlockComplete
  classMerge.io.outReady := cleanup.io.reqReady

  cleanup.io.req := classMerge.io.out
  cleanup.io.ringReq := 0.U.asTypeOf(cleanup.io.ringReq)
  cleanup.io.intentReady := io.intentReady

  for (source <- 0 until sourceCount) {
    val stidInRange = io.sources(source).stid < stidCount.U
    val peInRange = io.sources(source).peId < peCount.U
    io.sourceReady(source) := sourceArbiter.io.sourceReady(source) && peInRange
    io.sourceAccepted(source) := io.sources(source).valid && io.sourceReady(source)
    io.sourceBlockedByStid(source) := io.sources(source).valid && !stidInRange
    io.sourceBlockedByPe(source) := io.sources(source).valid && stidInRange && !peInRange
  }
  io.intent := cleanup.io.intent
  io.intentAccepted := cleanup.io.accepted
  io.intentConsumed := cleanup.io.consumed
  io.pending := sourceArbiter.io.pendingMask.orR || classMerge.io.pending || cleanup.io.pending

  io.sourcePendingMask := sourceArbiter.io.pendingMask
  io.sourceSelectedValid := sourceArbiter.io.selectedSourceValid
  io.sourceSelected := sourceArbiter.io.selectedSource
  io.sourceSelectedStid := sourceArbiter.io.selectedStid
  io.sourceSelectedBlockBid := sourceArbiter.io.out.blockBid
  io.classSelected := classMerge.io.selectedClass
  io.classSelectedStid := classMerge.io.selectedStid
  io.classSelectedPe := classMerge.io.selectedPe
  io.classGlobalFlushPendingMask := classMerge.io.globalFlushPendingMask
  io.classGlobalReplayPendingMask := classMerge.io.globalReplayPendingMask
  io.classPePendingMask := classMerge.io.pePendingMask
  io.classDroppedByOlder := classMerge.io.inDroppedByOlder
  io.classDroppedByComplete := classMerge.io.inDroppedByComplete
  io.classMerged := classMerge.io.inMerged
  io.classBlockedByStid := classMerge.io.inBlockedByStid
  io.classBlockedByPe := classMerge.io.inBlockedByPe
}
