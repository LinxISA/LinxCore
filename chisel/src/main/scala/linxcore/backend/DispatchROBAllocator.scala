package linxcore.backend

import chisel3._
import chisel3.util.log2Ceil
import linxcore.bctrl.{BID, BrobEntryMeta, BrobMetaTracker}
import linxcore.commit.{CommitTraceParams, CommitTracePort, CommitTraceRow}
import linxcore.common.{DestinationKind, InterfaceParams, TULinkFlushSequenceSource}
import linxcore.recovery.{FlushBus, FullBidRecoveryBridge}
import linxcore.rob.{ROBEntryBank, ROBEntryStatus, ROBID}

class DispatchROBAllocatorIO(
    val entries: Int,
    val traceParams: CommitTraceParams,
    val bidWidth: Int = BID.DefaultWidth,
    val peIdWidth: Int = 8,
    val tidWidth: Int = 8,
    val blockTypeWidth: Int = 4,
    val trapCauseWidth: Int = 32,
    val mapQDepth: Int = 32,
    val stidWidth: Int = 8)
    extends Bundle {
  private val ptrWidth = log2Ceil(entries)
  private val sizeWidth = log2Ceil(entries + 1)
  private val sourceParams = InterfaceParams(robEntries = entries)

  val flush = Input(new FlushBus(entries))

  val allocValid = Input(Bool())
  val allocReady = Output(Bool())
  val allocFire = Output(Bool())
  val allocBlockedByBrob = Output(Bool())
  val allocBlockedByRob = Output(Bool())
  val allocDuplicateIdentity = Output(Bool())
  val allocRow = Input(new CommitTraceRow(traceParams))
  val allocTid = Input(UInt(tidWidth.W))
  val allocStid = Input(UInt(stidWidth.W))
  val allocTSeq = Input(new ROBID(mapQDepth))
  val allocUSeq = Input(new ROBID(mapQDepth))
  val allocTUDstValid = Input(Bool())
  val allocTUDstKind = Input(DestinationKind())
  val allocPeId = Input(UInt(peIdWidth.W))
  val allocBlockType = Input(UInt(blockTypeWidth.W))
  val allocNeedsEngine = Input(Bool())
  val allocBlockBid = Output(UInt(bidWidth.W))
  val allocRobValue = Output(UInt(ptrWidth.W))

  val completeValid = Input(Bool())
  val completeRobValue = Input(UInt(ptrWidth.W))
  val completeAccepted = Output(Bool())
  val completeIgnored = Output(Bool())
  val deallocReady = Input(Bool())

  val blockScalarDoneValid = Input(Bool())
  val blockScalarDoneBid = Input(UInt(bidWidth.W))
  val blockScalarTrapValid = Input(Bool())
  val blockScalarTrapCause = Input(UInt(trapCauseWidth.W))
  val blockEngineDoneValid = Input(Bool())
  val blockEngineDoneBid = Input(UInt(bidWidth.W))
  val blockEngineTrapValid = Input(Bool())
  val blockEngineTrapCause = Input(UInt(trapCauseWidth.W))
  val blockRetireValid = Input(Bool())
  val blockRetireBid = Input(UInt(bidWidth.W))
  val blockFlushValid = Input(Bool())
  val blockFlushBid = Input(UInt(bidWidth.W))
  val blockQueryBid = Input(UInt(bidWidth.W))
  val blockQuery = Output(new BrobEntryMeta(entries, bidWidth, peIdWidth, tidWidth, blockTypeWidth, trapCauseWidth))
  val blockQueryAllocated = Output(Bool())
  val blockQueryComplete = Output(Bool())
  val blockAllocatedMask = Output(UInt(entries.W))
  val blockCompleteMask = Output(UInt(entries.W))
  val blockPendingMask = Output(UInt(entries.W))

  val commit = Output(new CommitTracePort(traceParams))
  val commitValidMask = Output(UInt(traceParams.commitWidth.W))
  val commitCount = Output(UInt(log2Ceil(traceParams.commitWidth + 1).W))
  val commitMonitorValidMask = Output(UInt(traceParams.commitWidth.W))
  val commitMonitorValidCount = Output(UInt(log2Ceil(traceParams.commitWidth + 1).W))
  val commitSkippedSlot = Output(Bool())
  val commitDuplicateIdentity = Output(Bool())
  val commitSlotMismatch = Output(Bool())
  val commitInvalidSideEffect = Output(Bool())
  val commitContractError = Output(Bool())

  val deallocValidMask = Output(UInt(traceParams.commitWidth.W))
  val deallocCount = Output(UInt(log2Ceil(traceParams.commitWidth + 1).W))

  val flushApplied = Output(Bool())
  val flushPruneMask = Output(UInt(entries.W))
  val flushResidentDecrement = Output(UInt(sizeWidth.W))
  val flushOutstandingDecrement = Output(UInt(sizeWidth.W))
  val flushAllocRebased = Output(Bool())
  val flushAllocRebaseValue = Output(UInt(ptrWidth.W))
  val flushCommitRebased = Output(Bool())
  val flushCommitRebaseValue = Output(UInt(ptrWidth.W))
  val flushClearedAll = Output(Bool())
  val robTULinkSource = Output(new TULinkFlushSequenceSource(sourceParams, mapQDepth, stidWidth))
  val robTULinkSourceMatched = Output(Bool())
  val robTULinkSourceMultipleMatch = Output(Bool())

  val empty = Output(Bool())
  val full = Output(Bool())
  val size = Output(UInt(sizeWidth.W))
  val outstandingCount = Output(UInt(sizeWidth.W))
  val commitHeadValid = Output(Bool())
  val commitHeadStatus = Output(ROBEntryStatus())
  val commitHeadRobValue = Output(UInt(ptrWidth.W))
  val deallocHeadValid = Output(Bool())
  val deallocHeadStatus = Output(ROBEntryStatus())
  val deallocHeadRobValue = Output(UInt(ptrWidth.W))
  val occupiedMask = Output(UInt(entries.W))
  val completedMask = Output(UInt(entries.W))
  val retiredMask = Output(UInt(entries.W))
}

class DispatchROBAllocator(
    val entries: Int = 16,
    val traceParams: CommitTraceParams = CommitTraceParams(),
    val bidWidth: Int = BID.DefaultWidth,
    val peIdWidth: Int = 8,
    val tidWidth: Int = 8,
    val blockTypeWidth: Int = 4,
    val trapCauseWidth: Int = 32,
    val mapQDepth: Int = 32,
    val stidWidth: Int = 8)
    extends Module {
  require(entries > 1, "allocator entries must be greater than one")
  require((entries & (entries - 1)) == 0, "allocator entries must be a power of two")
  require(traceParams.robValueWidth >= log2Ceil(entries), "ROB trace value must hold entry index")
  require(traceParams.blockBidWidth <= bidWidth, "generated BID must fit the commit trace block BID sideband")

  private val ptrWidth = log2Ceil(entries)
  private val uniqWidth = bidWidth - ptrWidth
  require(uniqWidth > 0, "BID width must include uniqueness bits")

  val io = IO(new DispatchROBAllocatorIO(
    entries,
    traceParams,
    bidWidth,
    peIdWidth,
    tidWidth,
    blockTypeWidth,
    trapCauseWidth,
    mapQDepth,
    stidWidth
  ))

  private def bidToRobId(bid: UInt): ROBID = {
    FullBidRecoveryBridge.fullBidToRobId(bid, true.B, entries, bidWidth)
  }

  val blockSlot = RegInit(0.U(ptrWidth.W))
  val blockUniq = RegInit(0.U(uniqWidth.W))
  val nextBlockBid = BID.fromParts(blockUniq, blockSlot, entries, bidWidth)
  io.allocBlockBid := nextBlockBid

  val brob = Module(new BrobMetaTracker(
    entries = entries,
    bidWidth = bidWidth,
    peIdWidth = peIdWidth,
    tidWidth = tidWidth,
    blockTypeWidth = blockTypeWidth,
    trapCauseWidth = trapCauseWidth
  ))

  val rob = Module(new ROBEntryBank(
    entries = entries,
    traceParams = traceParams,
    mapQDepth = mapQDepth,
    stidWidth = stidWidth
  ))

  val robAllocRow = Wire(new CommitTraceRow(traceParams))
  robAllocRow := io.allocRow
  robAllocRow.blockBidValid := true.B
  robAllocRow.blockBid := nextBlockBid

  rob.io.flush := io.flush
  rob.io.allocValid := io.allocValid && brob.io.allocReady
  rob.io.allocRow := robAllocRow
  rob.io.allocBid := bidToRobId(nextBlockBid)
  rob.io.allocStid := io.allocStid
  rob.io.allocTSeq := io.allocTSeq
  rob.io.allocUSeq := io.allocUSeq
  rob.io.allocTUDstValid := io.allocTUDstValid
  rob.io.allocTUDstKind := io.allocTUDstKind
  rob.io.completeValid := io.completeValid
  rob.io.completeRobValue := io.completeRobValue
  rob.io.deallocReady := io.deallocReady

  brob.io.allocValid := io.allocValid && rob.io.allocReady
  brob.io.allocBid := nextBlockBid
  brob.io.allocTid := io.allocTid
  brob.io.allocPeId := io.allocPeId
  brob.io.allocBlockType := io.allocBlockType
  brob.io.allocNeedsEngine := io.allocNeedsEngine
  brob.io.scalarDoneValid := io.blockScalarDoneValid
  brob.io.scalarDoneBid := io.blockScalarDoneBid
  brob.io.scalarTrapValid := io.blockScalarTrapValid
  brob.io.scalarTrapCause := io.blockScalarTrapCause
  brob.io.engineDoneValid := io.blockEngineDoneValid
  brob.io.engineDoneBid := io.blockEngineDoneBid
  brob.io.engineTrapValid := io.blockEngineTrapValid
  brob.io.engineTrapCause := io.blockEngineTrapCause
  brob.io.retireValid := io.blockRetireValid
  brob.io.retireBid := io.blockRetireBid
  brob.io.flushValid := io.blockFlushValid
  brob.io.flushBid := io.blockFlushBid
  brob.io.queryBid := io.blockQueryBid

  io.allocReady := brob.io.allocReady && rob.io.allocReady
  io.allocFire := io.allocValid && io.allocReady
  io.allocBlockedByBrob := io.allocValid && !brob.io.allocReady
  io.allocBlockedByRob := io.allocValid && brob.io.allocReady && !rob.io.allocReady
  io.allocDuplicateIdentity := rob.io.allocDuplicateIdentity
  io.allocRobValue := rob.io.allocRobValue

  when(io.allocFire) {
    when(blockSlot === (entries - 1).U) {
      blockSlot := 0.U
      blockUniq := blockUniq + 1.U
    }.otherwise {
      blockSlot := blockSlot + 1.U
    }
  }

  io.blockQuery := brob.io.query
  io.blockQueryAllocated := brob.io.queryAllocated
  io.blockQueryComplete := brob.io.queryComplete
  io.blockAllocatedMask := brob.io.allocatedMask
  io.blockCompleteMask := brob.io.completeMask
  io.blockPendingMask := brob.io.pendingMask

  io.completeAccepted := rob.io.completeAccepted
  io.completeIgnored := rob.io.completeIgnored
  io.commit := rob.io.commit
  io.commitValidMask := rob.io.commitValidMask
  io.commitCount := rob.io.commitCount
  io.commitMonitorValidMask := rob.io.commitMonitorValidMask
  io.commitMonitorValidCount := rob.io.commitMonitorValidCount
  io.commitSkippedSlot := rob.io.commitSkippedSlot
  io.commitDuplicateIdentity := rob.io.commitDuplicateIdentity
  io.commitSlotMismatch := rob.io.commitSlotMismatch
  io.commitInvalidSideEffect := rob.io.commitInvalidSideEffect
  io.commitContractError := rob.io.commitContractError
  io.deallocValidMask := rob.io.deallocValidMask
  io.deallocCount := rob.io.deallocCount
  io.flushApplied := rob.io.flushApplied
  io.flushPruneMask := rob.io.flushPruneMask
  io.flushResidentDecrement := rob.io.flushResidentDecrement
  io.flushOutstandingDecrement := rob.io.flushOutstandingDecrement
  io.flushAllocRebased := rob.io.flushAllocRebased
  io.flushAllocRebaseValue := rob.io.flushAllocRebaseValue
  io.flushCommitRebased := rob.io.flushCommitRebased
  io.flushCommitRebaseValue := rob.io.flushCommitRebaseValue
  io.flushClearedAll := rob.io.flushClearedAll
  io.robTULinkSource := rob.io.robTULinkSource
  io.robTULinkSourceMatched := rob.io.robTULinkSourceMatched
  io.robTULinkSourceMultipleMatch := rob.io.robTULinkSourceMultipleMatch
  io.empty := rob.io.empty
  io.full := rob.io.full
  io.size := rob.io.size
  io.outstandingCount := rob.io.outstandingCount
  io.commitHeadValid := rob.io.commitHeadValid
  io.commitHeadStatus := rob.io.commitHeadStatus
  io.commitHeadRobValue := rob.io.commitHeadRobValue
  io.deallocHeadValid := rob.io.deallocHeadValid
  io.deallocHeadStatus := rob.io.deallocHeadStatus
  io.deallocHeadRobValue := rob.io.deallocHeadRobValue
  io.occupiedMask := rob.io.occupiedMask
  io.completedMask := rob.io.completedMask
  io.retiredMask := rob.io.retiredMask
}
