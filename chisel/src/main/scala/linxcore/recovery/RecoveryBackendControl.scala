package linxcore.recovery

import chisel3._
import chisel3.util.log2Ceil

import linxcore.bctrl.BID
import linxcore.rob.{ROBFullBidLookupRequest, ROBFullBidLookupResult, ROBID}

class RecoveryBackendControlIO(
    val nonLsuSourceCount: Int,
    val stidCount: Int,
    val peCount: Int,
    val entries: Int,
    val bidWidth: Int = BID.DefaultWidth,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8)
    extends Bundle {
  private val sourceCount = nonLsuSourceCount + 1
  private val sourceIndexWidth = math.max(1, log2Ceil(sourceCount))

  val nonLsuSources = Input(Vec(
    nonLsuSourceCount,
    new FullBidFlushReq(entries, bidWidth, peIdWidth, stidWidth, tidWidth)
  ))
  val nonLsuSourceReady = Output(Vec(nonLsuSourceCount, Bool()))
  val nonLsuSourceAccepted = Output(Vec(nonLsuSourceCount, Bool()))
  val lsuSource = Input(new FullBidFlushReq(entries, bidWidth, peIdWidth, stidWidth, tidWidth))
  val lsuSourceReady = Output(Bool())
  val lsuSourceAccepted = Output(Bool())

  val lsuFullBidLookupRequest = Input(new ROBFullBidLookupRequest(
    entries,
    peIdWidth,
    stidWidth,
    tidWidth
  ))
  val lsuFullBidLookup = Output(new ROBFullBidLookupResult(
    entries,
    bidWidth,
    peIdWidth,
    stidWidth,
    tidWidth
  ))
  val robFullBidLookupRequest = Output(new ROBFullBidLookupRequest(
    entries,
    peIdWidth,
    stidWidth,
    tidWidth
  ))
  val robFullBidLookup = Input(new ROBFullBidLookupResult(
    entries,
    bidWidth,
    peIdWidth,
    stidWidth,
    tidWidth
  ))

  val oldestValid = Input(Vec(stidCount, Bool()))
  val oldestBid = Input(Vec(stidCount, new ROBID(entries)))
  val oldestBlockComplete = Input(Vec(stidCount, Bool()))
  val intentReady = Input(Bool())
  val intent = Output(new RecoveryCleanupIntent(entries, bidWidth, peIdWidth, stidWidth, tidWidth))
  val robFlush = Output(new FlushBus(entries, peIdWidth, stidWidth, tidWidth))
  val intentAccepted = Output(Bool())
  val intentConsumed = Output(Bool())
  val intentProvenance = Output(new RecoveryProvenance(sourceCount))
  val sourceResolvedMask = Output(UInt(sourceCount.W))
  val consumedPayloadSourceMask = Output(UInt(sourceCount.W))
  val pending = Output(Bool())

  val sourcePendingMask = Output(UInt(sourceCount.W))
  val sourceSelectedValid = Output(Bool())
  val sourceSelected = Output(UInt(sourceIndexWidth.W))
  val sourceSelectedBlockBid = Output(UInt(bidWidth.W))
  val sourceBlockedByStid = Output(UInt(sourceCount.W))
  val sourceBlockedByPe = Output(UInt(sourceCount.W))
  val classGlobalFlushPendingMask = Output(UInt(stidCount.W))
  val classGlobalReplayPendingMask = Output(UInt(stidCount.W))
  val classPePendingMask = Output(UInt((stidCount * peCount).W))
}

/** Canonical backend recovery composition around the retained recovery fabric.
  *
  * The resident ROB owns full-BID lookup and row mutation. All other cleanup
  * consumers participate through one shared intent handshake; no side effect
  * is emitted while that intent is blocked.
  */
class RecoveryBackendControl(
    val nonLsuSourceCount: Int,
    val stidCount: Int,
    val peCount: Int,
    val entries: Int = 16,
    val bidWidth: Int = BID.DefaultWidth,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8)
    extends Module {
  require(nonLsuSourceCount >= 0, "non-LSU recovery source count cannot be negative")

  private val sourceCount = nonLsuSourceCount + 1
  private val lsuSourceIndex = nonLsuSourceCount
  val io = IO(new RecoveryBackendControlIO(
    nonLsuSourceCount,
    stidCount,
    peCount,
    entries,
    bidWidth,
    peIdWidth,
    stidWidth,
    tidWidth
  ))

  val recovery = Module(new RecoveryFabric(
    sourceCount,
    stidCount,
    peCount,
    entries,
    bidWidth,
    peIdWidth,
    stidWidth,
    tidWidth
  ))

  for (source <- 0 until nonLsuSourceCount) {
    recovery.io.sources(source) := io.nonLsuSources(source)
    io.nonLsuSourceReady(source) := recovery.io.sourceReady(source)
    io.nonLsuSourceAccepted(source) := recovery.io.sourceAccepted(source)
  }
  recovery.io.sources(lsuSourceIndex) := io.lsuSource
  io.lsuSourceReady := recovery.io.sourceReady(lsuSourceIndex)
  io.lsuSourceAccepted := recovery.io.sourceAccepted(lsuSourceIndex)

  io.robFullBidLookupRequest := io.lsuFullBidLookupRequest
  io.lsuFullBidLookup := io.robFullBidLookup

  recovery.io.oldestValid := io.oldestValid
  recovery.io.oldestBid := io.oldestBid
  recovery.io.oldestBlockComplete := io.oldestBlockComplete
  recovery.io.intentReady := io.intentReady

  io.intent := recovery.io.intent
  io.robFlush := recovery.io.intent.flush
  io.robFlush.req.valid :=
    recovery.io.intentConsumed && recovery.io.intent.robPruneValid
  io.intentAccepted := recovery.io.intentAccepted
  io.intentConsumed := recovery.io.intentConsumed
  io.intentProvenance := recovery.io.intentProvenance
  io.sourceResolvedMask := recovery.io.sourceResolvedMask
  io.consumedPayloadSourceMask := recovery.io.consumedPayloadSourceMask
  io.pending := recovery.io.pending

  io.sourcePendingMask := recovery.io.sourcePendingMask
  io.sourceSelectedValid := recovery.io.sourceSelectedValid
  io.sourceSelected := recovery.io.sourceSelected
  io.sourceSelectedBlockBid := recovery.io.sourceSelectedBlockBid
  io.sourceBlockedByStid := recovery.io.sourceBlockedByStid.asUInt
  io.sourceBlockedByPe := recovery.io.sourceBlockedByPe.asUInt
  io.classGlobalFlushPendingMask := recovery.io.classGlobalFlushPendingMask
  io.classGlobalReplayPendingMask := recovery.io.classGlobalReplayPendingMask
  io.classPePendingMask := recovery.io.classPePendingMask
}
