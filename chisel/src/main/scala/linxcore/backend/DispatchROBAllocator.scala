package linxcore.backend

import chisel3._
import chisel3.util.{log2Ceil, Mux1H}
import linxcore.bctrl.{BID, BrobEntryMeta, BrobMetaTracker, BrobOrderState, BrobRobAllocationAdmission, BrobStoreCountPublisher, BrobStoreRangeState}
import linxcore.commit.{CommitTraceParams, CommitTracePort, CommitTraceRow}
import linxcore.common.{
  BlockMarkerRetireSource,
  BoundaryKind,
  DestinationKind,
  InterfaceParams,
  TULinkFlushSequenceSource,
  TULinkRetireSource
}
import linxcore.recovery.{FlushBus, FullBidRecoveryBridge}
import linxcore.rob.{
  ROBEntryBank,
  ROBEntryStatus,
  ROBFullBidLookupRequest,
  ROBFullBidLookupResult,
  ROBID,
  ROBMemoryOrderCommit,
  RecoveryWatermarkJoin,
  ROBRowCommitTraceLookupResult,
  ROBRowStatusLookupResult
}

class DispatchROBAllocatorIO(
    val entries: Int,
    val traceParams: CommitTraceParams,
    val bidWidth: Int = BID.DefaultWidth,
    val peIdWidth: Int = 8,
    val tidWidth: Int = 8,
    val blockTypeWidth: Int = 4,
    val trapCauseWidth: Int = 32,
    val mapQDepth: Int = 32,
    val stidWidth: Int = 8,
    val lsidWidth: Int = 32,
    val stidCount: Int = 1,
    val storeSerialWidth: Int = 64)
    extends Bundle {
  private val ptrWidth = log2Ceil(entries)
  private val sizeWidth = log2Ceil(entries + 1)
  private val blockBidWidth = BID.slotBits(entries)
  private val sourceParams = InterfaceParams(robEntries = entries)

  val flush = Input(new FlushBus(entries))

  val allocValid = Input(Bool())
  val allocUsesExistingBlock = Input(Bool())
  val allocExistingBlockBid = Input(UInt(bidWidth.W))
  val allocReady = Output(Bool())
  val allocFire = Output(Bool())
  val allocBlockedByBrob = Output(Bool())
  val allocBlockedByRob = Output(Bool())
  val allocDuplicateIdentity = Output(Bool())
  val allocRow = Input(new CommitTraceRow(traceParams))
  val allocGid = Input(new ROBID(entries))
  val allocTid = Input(UInt(tidWidth.W))
  val allocStid = Input(UInt(stidWidth.W))
  val allocLsId = Input(UInt(lsidWidth.W))
  val allocIsLoad = Input(Bool())
  val allocIsStore = Input(Bool())
  val allocTSeq = Input(new ROBID(mapQDepth))
  val allocUSeq = Input(new ROBID(mapQDepth))
  val allocTUDstValid = Input(Bool())
  val allocTUDstKind = Input(DestinationKind())
  val allocIsLast = Input(Bool())
  val allocMarkerBoundary = Input(Bool())
  val allocMarkerStop = Input(Bool())
  val allocMarkerBoundaryKind = Input(BoundaryKind())
  val allocMarkerBoundaryTarget = Input(UInt(traceParams.pcWidth.W))
  val allocPeId = Input(UInt(peIdWidth.W))
  val allocBlockType = Input(UInt(blockTypeWidth.W))
  val allocNeedsEngine = Input(Bool())
  val allocBlockBid = Output(UInt(bidWidth.W))
  val allocRobValue = Output(UInt(ptrWidth.W))
  val allocRobWrap = Output(Bool())
  val blockAllocOnlyValid = Input(Bool())
  val blockAllocOnlyStid = Input(UInt(stidWidth.W))
  val blockAllocOnlyReady = Output(Bool())
  val blockAllocOnlyFire = Output(Bool())
  val blockAllocOnlyBid = Output(UInt(bidWidth.W))

  val renameUpdateValid = Input(Bool())
  val renameUpdateReady = Output(Bool())
  val renameUpdateAccepted = Output(Bool())
  val renameUpdateIgnored = Output(Bool())
  val renameUpdateRid = Input(new ROBID(entries))
  val renameUpdateRow = Input(new CommitTraceRow(traceParams))
  val renameUpdateTSeq = Input(new ROBID(mapQDepth))
  val renameUpdateUSeq = Input(new ROBID(mapQDepth))
  val renameUpdateTUDstValid = Input(Bool())
  val renameUpdateTUDstKind = Input(DestinationKind())

  val completeValid = Input(Bool())
  val completeRobValue = Input(UInt(ptrWidth.W))
  val completeRowValid = Input(Bool())
  val completeRow = Input(new CommitTraceRow(traceParams))
  val completeAccepted = Output(Bool())
  val completeIgnored = Output(Bool())
  val deallocReady = Input(Bool())
  val deallocHoldMask = Input(UInt(entries.W))

  val blockScalarDoneValid = Input(Bool())
  val blockScalarDoneBid = Input(UInt(bidWidth.W))
  val blockScalarDoneStid = Input(UInt(stidWidth.W))
  val blockExplicitStoreCountValid = Input(Bool())
  val blockExplicitStoreCountReady = Output(Bool())
  val blockExplicitStoreCountBid = Input(UInt(bidWidth.W))
  val blockExplicitStoreCountStid = Input(UInt(stidWidth.W))
  val blockExplicitStoreCountValue = Input(UInt(storeSerialWidth.W))
  val blockExplicitStoreCountAccepted = Output(Bool())
  val blockExplicitStoreCountCanceled = Output(Bool())
  val blockExplicitStoreCountBlockedByLiveWindow = Output(Bool())
  val blockStoreCountScalarPending = Output(Bool())
  val blockStoreCountExplicitPending = Output(Bool())
  val blockStoreCountSameBlockCollision = Output(Bool())
  val blockStoreCountDifferentBlockCollision = Output(Bool())
  val blockStoreCountConflict = Output(Bool())
  val blockScalarTrapValid = Input(Bool())
  val blockScalarTrapCause = Input(UInt(trapCauseWidth.W))
  val blockEngineDoneValid = Input(Bool())
  val blockEngineDoneBid = Input(UInt(bidWidth.W))
  val blockEngineDoneStid = Input(UInt(stidWidth.W))
  val blockEngineTrapValid = Input(Bool())
  val blockEngineTrapCause = Input(UInt(trapCauseWidth.W))
  val blockRetireReady = Input(Bool())
  val blockRetireValid = Output(Bool())
  val blockRetireFire = Output(Bool())
  val blockRetireBid = Output(UInt(bidWidth.W))
  val blockRetireStid = Output(UInt(stidWidth.W))
  val blockRetireMetadataAccepted = Output(Bool())
  val blockRetireMetadataIgnored = Output(Bool())
  val blockFlushValid = Input(Bool())
  val blockFlushBid = Input(UInt(blockBidWidth.W))
  val blockFlushPointerValid = Input(Bool())
  val blockFlushPointer = Input(UInt(bidWidth.W))
  val blockFlushStid = Input(UInt(stidWidth.W))
  val blockFlushInclusive = Input(Bool())
  val blockFlushFirstKilledBid = Output(UInt(bidWidth.W))
  val blockFlushOldAllocBid = Output(UInt(bidWidth.W))
  val blockFlushApplied = Output(Bool())
  val blockFlushStidInRange = Output(Bool())
  val blockFlushCanonicalMatch = Output(Bool())
  val blockFlushResolvedPivotBid = Output(UInt(bidWidth.W))
  val blockFlushLegacyPointerMismatch = Output(Bool())
  val blockFlushWindowValid = Output(Bool())
  val blockFlushRetainedCount = Output(UInt(sizeWidth.W))
  val blockAllocCursor = Output(Vec(stidCount, UInt(bidWidth.W)))
  val blockCommitCursor = Output(Vec(stidCount, UInt(bidWidth.W)))
  val blockLiveCount = Output(Vec(stidCount, UInt(sizeWidth.W)))
  val blockOrderEmpty = Output(Vec(stidCount, Bool()))
  val blockOrderFull = Output(Vec(stidCount, Bool()))
  val blockOrderHeadMismatch = Output(Vec(stidCount, Bool()))
  val blockNonFlushValid = Output(Vec(stidCount, Bool()))
  val blockNonFlushHeadBid = Output(Vec(stidCount, UInt(bidWidth.W)))
  val blockNonFlushFrontierBid = Output(Vec(stidCount, UInt(bidWidth.W)))
  val blockNonFlushPrefixCount = Output(Vec(stidCount, UInt(sizeWidth.W)))
  val blockNonFlushBlockedValid = Output(Vec(stidCount, Bool()))
  val blockNonFlushBlockedBid = Output(Vec(stidCount, UInt(bidWidth.W)))
  val blockStoreRangeCursor = Output(Vec(stidCount, UInt(bidWidth.W)))
  val blockNextStoreId = Output(Vec(stidCount, UInt(storeSerialWidth.W)))
  val blockStoreRangeAdvanceCount = Output(Vec(stidCount, UInt(sizeWidth.W)))
  val blockStoreRangeBlockedValid = Output(Vec(stidCount, Bool()))
  val blockStoreRangeBlockedBid = Output(Vec(stidCount, UInt(bidWidth.W)))
  val blockStoreRangeQueryHit = Output(Bool())
  val blockStoreRangeQueryCountKnown = Output(Bool())
  val blockStoreRangeQueryCount = Output(UInt(storeSerialWidth.W))
  val blockStoreRangeQueryStartValid = Output(Bool())
  val blockStoreRangeQueryStartId = Output(UInt(storeSerialWidth.W))
  val blockQueryBid = Input(UInt(bidWidth.W))
  val blockQueryStid = Input(UInt(stidWidth.W))
  val blockQuery = Output(new BrobEntryMeta(entries, bidWidth, peIdWidth, stidWidth, tidWidth, blockTypeWidth, trapCauseWidth))
  val blockQueryAllocated = Output(Bool())
  val blockQueryComplete = Output(Bool())
  val blockAllocatedMask = Output(UInt(entries.W))
  val blockCompleteMask = Output(UInt(entries.W))
  val blockPendingMask = Output(UInt(entries.W))
  val recoveryOldestValid = Output(Vec(stidCount, Bool()))
  val recoveryOldestBlockBid = Output(Vec(stidCount, UInt(bidWidth.W)))
  val recoveryOldestBid = Output(Vec(stidCount, new ROBID(entries)))
  val recoveryOldestRid = Output(Vec(stidCount, new ROBID(entries)))
  val recoveryOldestBlockComplete = Output(Vec(stidCount, Bool()))

  val commit = Output(new CommitTracePort(traceParams))
  val commitMemoryOrder = Output(Vec(traceParams.commitWidth, new ROBMemoryOrderCommit(entries, lsidWidth)))
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
  val deallocTURetireSource =
    Output(Vec(traceParams.commitWidth, new TULinkRetireSource(sourceParams, mapQDepth, stidWidth, peIdWidth)))
  val deallocBlockMarkerRetireSource =
    Output(Vec(traceParams.commitWidth, new BlockMarkerRetireSource(
      entries = entries,
      blockBidWidth = traceParams.blockBidWidth,
      pcWidth = traceParams.pcWidth,
      insnWidth = traceParams.insnWidth,
      lenWidth = traceParams.lenWidth,
      peIdWidth = peIdWidth,
      stidWidth = stidWidth
    )))
  val deallocBlockLastValid = Output(Bool())
  val deallocBlockLastBid = Output(new ROBID(entries))
  val deallocBlockLastGid = Output(new ROBID(entries))
  val deallocBlockLastBlockBid = Output(UInt(bidWidth.W))
  val deallocBlockLastStid = Output(UInt(stidWidth.W))

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

  val statusLookupValid = Input(Bool())
  val statusLookupRid = Input(new ROBID(entries))
  val statusLookup = Output(new ROBRowStatusLookupResult(entries))
  val commitTraceLookupValid = Input(Bool())
  val commitTraceLookupRid = Input(new ROBID(entries))
  val commitTraceLookupSourceTraceEnable = Input(Bool())
  val commitTraceLookup = Output(new ROBRowCommitTraceLookupResult(entries, traceParams))
  val fullBidLookupRequest = Input(new ROBFullBidLookupRequest(
    entries,
    peIdWidth,
    stidWidth,
    tidWidth
  ))
  val fullBidLookup = Output(new ROBFullBidLookupResult(
    entries,
    bidWidth,
    peIdWidth,
    stidWidth,
    tidWidth
  ))

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
    val stidWidth: Int = 8,
    val stidCount: Int = 1,
    val lsidWidth: Int = 32,
    val storeSerialWidth: Int = 64)
    extends Module {
  require(entries > 1, "allocator entries must be greater than one")
  require((entries & (entries - 1)) == 0, "allocator entries must be a power of two")
  require(traceParams.robValueWidth >= log2Ceil(entries), "ROB trace value must hold entry index")
  require(traceParams.blockBidWidth <= bidWidth, "generated BID must fit the commit trace block BID sideband")
  require(stidCount > 0, "allocator must track at least one STID")
  require(BigInt(stidCount) <= (BigInt(1) << stidWidth), "allocator STID count must fit stidWidth")

  private val ptrWidth = log2Ceil(entries)
  require(bidWidth > ptrWidth, "BID width must include uniqueness bits")

  val io = IO(new DispatchROBAllocatorIO(
    entries,
    traceParams,
    bidWidth,
    peIdWidth,
    tidWidth,
    blockTypeWidth,
    trapCauseWidth,
    mapQDepth,
    stidWidth,
    lsidWidth,
    stidCount,
    storeSerialWidth
  ))

  private def bidToRobId(bid: UInt): ROBID = {
    FullBidRecoveryBridge.fullBidToRobId(bid, true.B, entries, bidWidth)
  }

  val blockOrder = Module(new BrobOrderState(
    entries = entries,
    bidWidth = bidWidth,
    stidWidth = stidWidth,
    stidCount = stidCount
  ))
  val storeRanges = Module(new BrobStoreRangeState(
    entries = entries,
    bidWidth = bidWidth,
    stidWidth = stidWidth,
    stidCount = stidCount,
    storeIdWidth = storeSerialWidth,
    storeCountWidth = storeSerialWidth
  ))
  val storeCountPublisher = Module(new BrobStoreCountPublisher(
    entries = entries,
    bidWidth = bidWidth,
    stidWidth = stidWidth,
    stidCount = stidCount,
    storeCountWidth = storeSerialWidth
  ))
  val allocStidMatch = VecInit((0 until stidCount).map(idx => io.allocStid === idx.U(stidWidth.W)))
  val blockOnlyStidMatch = VecInit((0 until stidCount).map(idx => io.blockAllocOnlyStid === idx.U(stidWidth.W)))
  val allocNextBlockBid = Mux1H(allocStidMatch, blockOrder.io.allocCursor)
  val blockOnlyNextBlockBid = Mux1H(blockOnlyStidMatch, blockOrder.io.allocCursor)
  val storeRangeBlockOnlyCandidate = !io.allocValid && io.blockAllocOnlyValid
  io.allocBlockBid := allocNextBlockBid
  io.blockAllocOnlyBid := blockOnlyNextBlockBid

  val brob = Module(new BrobMetaTracker(
    entries = entries,
    bidWidth = bidWidth,
    peIdWidth = peIdWidth,
    stidWidth = stidWidth,
    stidCount = stidCount,
    tidWidth = tidWidth,
    blockTypeWidth = blockTypeWidth,
    trapCauseWidth = trapCauseWidth
  ))

  val rob = Module(new ROBEntryBank(
    entries = entries,
    traceParams = traceParams,
    mapQDepth = mapQDepth,
    peIdWidth = peIdWidth,
    stidWidth = stidWidth,
    lsidWidth = lsidWidth,
    tidWidth = tidWidth,
    stidCount = stidCount
  ))

  val scalarNeedsBrob = io.allocValid && !io.allocUsesExistingBlock
  val scalarStidInRange = allocStidMatch.asUInt.orR
  val blockOnlyStidInRange = blockOnlyStidMatch.asUInt.orR
  val scalarBlockOrderReady = scalarStidInRange && !Mux1H(allocStidMatch, blockOrder.io.full)
  val scalarStoreRangeReady = storeRanges.io.allocReady
  val blockOnlyOrderReady = blockOnlyStidInRange && !Mux1H(blockOnlyStidMatch, blockOrder.io.full) &&
    storeRanges.io.allocReady
  val scalarOrderAndRangeReady = scalarBlockOrderReady && scalarStoreRangeReady
  val scalarCanUseBrob = scalarStidInRange &&
    (!scalarNeedsBrob || (brob.io.allocReady && scalarOrderAndRangeReady))
  val scalarCanUseRob = rob.io.allocReady
  val scalarAdmission = Module(new BrobRobAllocationAdmission)
  scalarAdmission.io.allocValid := io.allocValid
  scalarAdmission.io.usesExistingBlock := io.allocUsesExistingBlock
  scalarAdmission.io.stidInRange := scalarStidInRange
  scalarAdmission.io.brobReady := brob.io.allocReady && scalarOrderAndRangeReady
  scalarAdmission.io.robReady := scalarCanUseRob
  scalarAdmission.io.recoveryValid := io.blockFlushValid
  val scalarAllocReady = scalarAdmission.io.allocReady
  val blockAllocOnlyReady =
    !io.allocValid && blockOnlyStidInRange && brob.io.allocReady && blockOnlyOrderReady && !io.blockFlushValid
  val blockAllocOnlyFire = io.blockAllocOnlyValid && blockAllocOnlyReady
  val scalarBrobAllocFire = scalarAdmission.io.brobAllocValid
  val rowBlockBid = Mux(io.allocUsesExistingBlock, io.allocExistingBlockBid, allocNextBlockBid)

  val robAllocRow = Wire(new CommitTraceRow(traceParams))
  robAllocRow := io.allocRow
  robAllocRow.blockBidValid := true.B
  robAllocRow.blockBid := rowBlockBid

  rob.io.flush := io.flush
  rob.io.allocValid := scalarAdmission.io.robAllocValid
  rob.io.allocRow := robAllocRow
  rob.io.allocBid := bidToRobId(rowBlockBid)
  rob.io.allocGid := io.allocGid
  rob.io.allocPeId := io.allocPeId
  rob.io.allocStid := io.allocStid
  rob.io.allocTid := io.allocTid
  rob.io.allocLsId := io.allocLsId
  rob.io.allocIsLoad := io.allocIsLoad
  rob.io.allocIsStore := io.allocIsStore
  rob.io.allocTSeq := io.allocTSeq
  rob.io.allocUSeq := io.allocUSeq
  rob.io.allocTUDstValid := io.allocTUDstValid
  rob.io.allocTUDstKind := io.allocTUDstKind
  rob.io.allocIsLast := io.allocIsLast
  rob.io.allocMarkerBoundary := io.allocMarkerBoundary
  rob.io.allocMarkerStop := io.allocMarkerStop
  rob.io.allocMarkerBoundaryKind := io.allocMarkerBoundaryKind
  rob.io.allocMarkerBoundaryTarget := io.allocMarkerBoundaryTarget
  rob.io.renameUpdateValid := io.renameUpdateValid
  rob.io.renameUpdateRid := io.renameUpdateRid
  rob.io.renameUpdateRow := io.renameUpdateRow
  rob.io.renameUpdateTSeq := io.renameUpdateTSeq
  rob.io.renameUpdateUSeq := io.renameUpdateUSeq
  rob.io.renameUpdateTUDstValid := io.renameUpdateTUDstValid
  rob.io.renameUpdateTUDstKind := io.renameUpdateTUDstKind
  rob.io.completeValid := io.completeValid
  rob.io.completeRobValue := io.completeRobValue
  rob.io.completeRowValid := io.completeRowValid
  rob.io.completeRow := io.completeRow
  rob.io.deallocReady := io.deallocReady
  rob.io.deallocHoldMask := io.deallocHoldMask
  rob.io.statusLookupValid := io.statusLookupValid
  rob.io.statusLookupRid := io.statusLookupRid
  rob.io.commitTraceLookupValid := io.commitTraceLookupValid
  rob.io.commitTraceLookupRid := io.commitTraceLookupRid
  rob.io.commitTraceLookupSourceTraceEnable := io.commitTraceLookupSourceTraceEnable
  rob.io.fullBidLookupRequest := io.fullBidLookupRequest

  brob.io.allocValid := scalarBrobAllocFire || blockAllocOnlyFire
  brob.io.allocBid := Mux(io.allocValid, allocNextBlockBid, blockOnlyNextBlockBid)
  brob.io.allocStid := Mux(io.allocValid, io.allocStid, io.blockAllocOnlyStid)
  brob.io.allocTid := io.allocTid
  brob.io.allocPeId := io.allocPeId
  brob.io.allocBlockType := io.allocBlockType
  brob.io.allocNeedsEngine := io.allocNeedsEngine
  brob.io.scalarDoneValid := io.blockScalarDoneValid
  brob.io.scalarDoneBid := io.blockScalarDoneBid
  brob.io.scalarDoneStid := io.blockScalarDoneStid
  brob.io.scalarTrapValid := io.blockScalarTrapValid
  brob.io.scalarTrapCause := io.blockScalarTrapCause
  brob.io.engineDoneValid := io.blockEngineDoneValid
  brob.io.engineDoneBid := io.blockEngineDoneBid
  brob.io.engineDoneStid := io.blockEngineDoneStid
  brob.io.engineTrapValid := io.blockEngineTrapValid
  brob.io.engineTrapCause := io.blockEngineTrapCause
  brob.io.retireValid := blockOrder.io.retireFire
  brob.io.retireBid := blockOrder.io.retireBid
  brob.io.retireStid := blockOrder.io.retireStid
  brob.io.flushValid := blockOrder.io.recoveryApplied
  brob.io.flushBid := blockOrder.io.recoveryResolvedPivotBid
  brob.io.flushStid := io.blockFlushStid
  brob.io.flushInclusive := io.blockFlushInclusive
  brob.io.queryBid := io.blockQueryBid
  brob.io.queryStid := io.blockQueryStid
  brob.io.orderHeadValid := blockOrder.io.headValid
  brob.io.orderHeadBid := blockOrder.io.commitCursor
  brob.io.orderLiveCount := blockOrder.io.liveCount

  io.allocReady := scalarAllocReady
  io.allocFire := scalarAdmission.io.allocFire
  io.allocBlockedByBrob := scalarNeedsBrob && !(brob.io.allocReady && scalarOrderAndRangeReady)
  io.allocBlockedByRob := io.allocValid && scalarCanUseBrob && !rob.io.allocReady
  io.allocDuplicateIdentity := rob.io.allocDuplicateIdentity
  io.allocRobValue := rob.io.allocRobValue
  io.allocRobWrap := rob.io.allocRobWrap
  io.blockAllocOnlyReady := blockAllocOnlyReady
  io.blockAllocOnlyFire := blockAllocOnlyFire
  io.renameUpdateReady := rob.io.renameUpdateReady
  io.renameUpdateAccepted := rob.io.renameUpdateAccepted
  io.renameUpdateIgnored := rob.io.renameUpdateIgnored

  blockOrder.io.allocValid := scalarBrobAllocFire || blockAllocOnlyFire
  blockOrder.io.allocBid := Mux(blockAllocOnlyFire, blockOnlyNextBlockBid, allocNextBlockBid)
  blockOrder.io.allocStid := Mux(blockAllocOnlyFire, io.blockAllocOnlyStid, io.allocStid)
  blockOrder.io.recoveryValid := io.blockFlushValid
  blockOrder.io.recoveryStid := io.blockFlushStid
  blockOrder.io.recoveryPivotBid := io.blockFlushBid
  blockOrder.io.recoveryTransportPointerValid := io.blockFlushPointerValid
  blockOrder.io.recoveryTransportPointer := io.blockFlushPointer
  blockOrder.io.recoveryInclusive := io.blockFlushInclusive
  blockOrder.io.headResident := brob.io.oldestValid
  blockOrder.io.headComplete := VecInit((0 until stidCount).map { stid =>
    brob.io.oldestComplete(stid) && storeRanges.io.headCountKnown(stid)
  })
  blockOrder.io.retireReady := io.blockRetireReady
  storeRanges.io.allocValid := blockOrder.io.allocApplied
  storeRanges.io.allocBid := Mux(storeRangeBlockOnlyCandidate, blockOnlyNextBlockBid, allocNextBlockBid)
  storeRanges.io.allocStid := Mux(storeRangeBlockOnlyCandidate, io.blockAllocOnlyStid, io.allocStid)
  storeRanges.io.storeObservedValid := scalarAdmission.io.allocFire && io.allocIsStore
  storeRanges.io.storeObservedBid := rowBlockBid
  storeRanges.io.storeObservedStid := io.allocStid
  storeCountPublisher.io.scalarValid := io.blockScalarDoneValid
  storeCountPublisher.io.scalarBid := io.blockScalarDoneBid
  storeCountPublisher.io.scalarStid := io.blockScalarDoneStid
  storeCountPublisher.io.explicitValid := io.blockExplicitStoreCountValid
  storeCountPublisher.io.explicitBid := io.blockExplicitStoreCountBid
  storeCountPublisher.io.explicitStid := io.blockExplicitStoreCountStid
  storeCountPublisher.io.explicitValue := io.blockExplicitStoreCountValue
  storeCountPublisher.io.orderHeadBid := blockOrder.io.commitCursor
  storeCountPublisher.io.orderLiveCount := blockOrder.io.liveCount
  storeCountPublisher.io.recoveryValid := blockOrder.io.recoveryApplied
  storeCountPublisher.io.recoveryStid := io.blockFlushStid
  storeCountPublisher.io.recoveryFirstKilledBid := blockOrder.io.recoveryFirstKilledBid
  storeRanges.io.countCertainValid := storeCountPublisher.io.publishValid
  storeRanges.io.countCertainBid := storeCountPublisher.io.publishBid
  storeRanges.io.countCertainStid := storeCountPublisher.io.publishStid
  storeRanges.io.countCertainUseValue := storeCountPublisher.io.publishUseValue
  storeRanges.io.countCertainValue := storeCountPublisher.io.publishValue
  storeCountPublisher.io.sinkAccepted := storeRanges.io.countCertainAccepted
  storeCountPublisher.io.sinkDuplicateMatch := storeRanges.io.countCertainDuplicateMatch
  storeCountPublisher.io.sinkConflict := storeRanges.io.countCertainConflict
  storeRanges.io.retireValid := blockOrder.io.retireFire
  storeRanges.io.retireBid := blockOrder.io.retireBid
  storeRanges.io.retireStid := blockOrder.io.retireStid
  storeRanges.io.recoveryValid := blockOrder.io.recoveryApplied
  storeRanges.io.recoveryStid := io.blockFlushStid
  storeRanges.io.recoveryFirstKilledBid := blockOrder.io.recoveryFirstKilledBid
  storeRanges.io.orderHeadBid := blockOrder.io.commitCursor
  storeRanges.io.orderLiveCount := blockOrder.io.liveCount
  storeRanges.io.queryBid := io.blockQueryBid
  storeRanges.io.queryStid := io.blockQueryStid
  io.blockRetireValid := blockOrder.io.retireValid
  io.blockRetireFire := blockOrder.io.retireFire
  io.blockRetireBid := blockOrder.io.retireBid
  io.blockRetireStid := blockOrder.io.retireStid
  io.blockRetireMetadataAccepted := brob.io.retireAccepted
  io.blockRetireMetadataIgnored := brob.io.retireIgnored
  io.blockFlushFirstKilledBid := blockOrder.io.recoveryFirstKilledBid
  io.blockFlushOldAllocBid := blockOrder.io.recoveryOldAllocBid
  io.blockFlushApplied := blockOrder.io.recoveryApplied
  io.blockFlushStidInRange := blockOrder.io.recoveryInRange
  io.blockFlushCanonicalMatch := blockOrder.io.recoveryCanonicalMatch
  io.blockFlushResolvedPivotBid := blockOrder.io.recoveryResolvedPivotBid
  io.blockFlushLegacyPointerMismatch := blockOrder.io.recoveryLegacyPointerMismatch
  io.blockFlushWindowValid := blockOrder.io.recoveryWindowValid
  io.blockFlushRetainedCount := blockOrder.io.recoveryRetainedCount
  io.blockAllocCursor := blockOrder.io.allocCursor
  io.blockCommitCursor := blockOrder.io.commitCursor
  io.blockLiveCount := blockOrder.io.liveCount
  io.blockOrderEmpty := blockOrder.io.empty
  io.blockOrderFull := blockOrder.io.full
  io.blockOrderHeadMismatch := blockOrder.io.headMismatch
  io.blockNonFlushValid := brob.io.nonFlushValid
  io.blockNonFlushHeadBid := brob.io.nonFlushHeadBid
  io.blockNonFlushFrontierBid := brob.io.nonFlushFrontierBid
  io.blockNonFlushPrefixCount := brob.io.nonFlushPrefixCount
  io.blockNonFlushBlockedValid := brob.io.nonFlushBlockedValid
  io.blockNonFlushBlockedBid := brob.io.nonFlushBlockedBid
  io.blockStoreRangeCursor := storeRanges.io.rangeCursorBid
  io.blockNextStoreId := storeRanges.io.nextStoreId
  io.blockStoreRangeAdvanceCount := storeRanges.io.advanceCount
  io.blockStoreRangeBlockedValid := storeRanges.io.blockedValid
  io.blockStoreRangeBlockedBid := storeRanges.io.blockedBid
  io.blockStoreRangeQueryHit := storeRanges.io.queryHit
  io.blockStoreRangeQueryCountKnown := storeRanges.io.query.countKnown
  io.blockStoreRangeQueryCount := storeRanges.io.query.storeCount
  io.blockStoreRangeQueryStartValid := storeRanges.io.query.startValid
  io.blockStoreRangeQueryStartId := storeRanges.io.query.startStoreId
  io.blockExplicitStoreCountReady := storeCountPublisher.io.explicitReady
  io.blockExplicitStoreCountAccepted := storeCountPublisher.io.explicitInputAccepted
  io.blockExplicitStoreCountCanceled :=
    storeCountPublisher.io.explicitInputCanceled || storeCountPublisher.io.explicitPendingCanceled
  io.blockExplicitStoreCountBlockedByLiveWindow := storeCountPublisher.io.explicitBlockedByLiveWindow
  io.blockStoreCountScalarPending := storeCountPublisher.io.scalarPending
  io.blockStoreCountExplicitPending := storeCountPublisher.io.explicitPending
  io.blockStoreCountSameBlockCollision := storeCountPublisher.io.sameBlockCollision
  io.blockStoreCountDifferentBlockCollision := storeCountPublisher.io.differentBlockCollision
  io.blockStoreCountConflict := storeRanges.io.countCertainConflict
  assert(!blockOrder.io.allocApplied || storeRanges.io.allocAccepted,
    "BROB order allocation must allocate the matching store-range row")
  assert(!storeRanges.io.storeObservedValid || storeRanges.io.storeObservedAccepted,
    "accepted scalar store must update its exact live BROB store-range row")
  assert(!blockOrder.io.retireFire || storeRanges.io.retireAccepted,
    "BROB ordered retire must remove the matching store-range row")
  assert(!storeRanges.io.recoveryMissingStart,
    "BROB store-range recovery cannot rewind an assigned suffix without its saved start ID")
  assert(!storeCountPublisher.io.scalarOverflow,
    "scalar block closure must enter the retained store-count publisher")
  assert(!storeCountPublisher.io.sinkConflictHeld,
    "authoritative block store count must not conflict with a frozen exact-BID count")
  assert(!blockOrder.io.retireFire || brob.io.retireAccepted,
    "BROB ordered retire must remove the selected resident metadata head")

  io.blockQuery := brob.io.query
  io.blockQueryAllocated := brob.io.queryAllocated
  io.blockQueryComplete := brob.io.queryComplete
  io.blockAllocatedMask := brob.io.allocatedMask
  io.blockCompleteMask := brob.io.completeMask
  io.blockPendingMask := brob.io.pendingMask
  val recoveryWatermark = Module(new RecoveryWatermarkJoin(entries, stidCount, bidWidth))
  for (stid <- 0 until stidCount) {
    recoveryWatermark.io.brobValid(stid) := brob.io.oldestValid(stid)
    recoveryWatermark.io.brobBlockBid(stid) := brob.io.oldestBid(stid)
    recoveryWatermark.io.brobComplete(stid) := brob.io.oldestComplete(stid)
    recoveryWatermark.io.robValid(stid) := rob.io.recoveryOldestValid(stid)
    recoveryWatermark.io.robRid(stid) := rob.io.recoveryOldestRid(stid)
    recoveryWatermark.io.robBlockBid(stid) := rob.io.recoveryOldestBlockBid(stid).pad(bidWidth)
  }
  io.recoveryOldestValid := recoveryWatermark.io.oldestValid
  io.recoveryOldestBlockBid := recoveryWatermark.io.oldestBlockBid
  io.recoveryOldestBid := recoveryWatermark.io.oldestBid
  io.recoveryOldestRid := recoveryWatermark.io.oldestRid
  io.recoveryOldestBlockComplete := recoveryWatermark.io.oldestBlockComplete

  io.completeAccepted := rob.io.completeAccepted
  io.completeIgnored := rob.io.completeIgnored
  io.commit := rob.io.commit
  io.commitMemoryOrder := rob.io.commitMemoryOrder
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
  io.deallocTURetireSource := rob.io.deallocTURetireSource
  io.deallocBlockMarkerRetireSource := rob.io.deallocBlockMarkerRetireSource
  io.deallocBlockLastValid := rob.io.deallocBlockLastValid
  io.deallocBlockLastBid := rob.io.deallocBlockLastBid
  io.deallocBlockLastGid := rob.io.deallocBlockLastGid
  io.deallocBlockLastBlockBid := rob.io.deallocBlockLastBlockBid.pad(bidWidth)(bidWidth - 1, 0)
  io.deallocBlockLastStid := rob.io.deallocBlockLastStid
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
  io.statusLookup := rob.io.statusLookup
  io.commitTraceLookup := rob.io.commitTraceLookup
  io.fullBidLookup := 0.U.asTypeOf(io.fullBidLookup)
  io.fullBidLookup.request := rob.io.fullBidLookup.request
  io.fullBidLookup.matched := rob.io.fullBidLookup.matched
  io.fullBidLookup.blockBidValid := rob.io.fullBidLookup.blockBidValid
  io.fullBidLookup.blockBid := rob.io.fullBidLookup.blockBid.pad(bidWidth)
  io.fullBidLookup.blockedByInvalidIdentity := rob.io.fullBidLookup.blockedByInvalidIdentity
  io.fullBidLookup.blockedByFree := rob.io.fullBidLookup.blockedByFree
  io.fullBidLookup.blockedByStaleRid := rob.io.fullBidLookup.blockedByStaleRid
  io.fullBidLookup.blockedByBid := rob.io.fullBidLookup.blockedByBid
  io.fullBidLookup.blockedByGid := rob.io.fullBidLookup.blockedByGid
  io.fullBidLookup.blockedByScope := rob.io.fullBidLookup.blockedByScope
  io.fullBidLookup.blockedByMissingBlockBid := rob.io.fullBidLookup.blockedByMissingBlockBid
  io.fullBidLookup.blockedByRingProjection := rob.io.fullBidLookup.blockedByRingProjection
  io.occupiedMask := rob.io.occupiedMask
  io.completedMask := rob.io.completedMask
  io.retiredMask := rob.io.retiredMask
}
