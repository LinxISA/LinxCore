package linxcore.backend

import chisel3._
import chisel3.util.{log2Ceil, PriorityEncoder, Queue}

import linxcore.bctrl.{
  BID,
  BlockMarkerDecodeContext,
  BlockMarkerLifecycle,
  BlockMarkerRetireSourceSerializer,
  BlockScalarDoneSequencer
}
import linxcore.commit.{CommitTraceParams, CommitTracePort, CommitTraceRow}
import linxcore.common._
import linxcore.frontend.{F4Slot, FrontendDecodeStage}
import linxcore.lsu.{STQEntryBankRow, STQStoreRequest, StoreDispatchExecResult, StoreDispatchSTQPath}
import linxcore.recovery.{FullBidRecoveryBridge, RecoveryCleanupIntent}
import linxcore.rename.{
  ScalarTURenameBridge,
  StoreSplitIssuePayload,
  StoreSplitPayload,
  TULinkRetireCommandPath
}
import linxcore.rob.{ROBEntryStatus, ROBID, ROBMemoryOrderCommit, ROBRowCommitTraceLookupResult, ROBRowStatusLookupResult}

class DecodeRenameROBPathIO(
    val p: InterfaceParams = InterfaceParams(),
    val traceParams: CommitTraceParams = CommitTraceParams(),
    val bidWidth: Int = BID.DefaultWidth,
    val stidWidth: Int = 8,
    val peIdWidth: Int = 8,
    val tidWidth: Int = 8,
    val blockTypeWidth: Int = 4,
    val trapCauseWidth: Int = 32,
    val decRenQueueDepth: Int = 4,
    val storeDispatchQueueDepth: Int = 4,
    val loadStoreSerialWidth: Int = 64,
    val physRegs: Int = 64,
    val mapQDepth: Int = 32,
    val gprMapQDepth: Int = 32,
    val tuRetireSourceQueueDepth: Int = 8,
    val tuRetireRelationCmapDepth: Int = 8,
    val markerRetireSourceQueueDepth: Int = 8)
    extends Bundle {
  private val slotWidth = math.max(1, log2Ceil(p.decodeWidth))
  private val ptrWidth = log2Ceil(p.robEntries)
  private val sizeWidth = log2Ceil(p.robEntries + 1)
  private val decRenPtrWidth = math.max(1, log2Ceil(decRenQueueDepth))
  private val decRenCountWidth = log2Ceil(decRenQueueDepth + 1)
  private val gprReservationCountWidth = decRenCountWidth
  private val storeDispatchCountWidth = log2Ceil(storeDispatchQueueDepth + 1)
  private val stqCountWidth = log2Ceil(p.robEntries + 1)
  private val gprFreeWidth = log2Ceil(physRegs + 1)
  private val gprMapQFreeWidth = log2Ceil(gprMapQDepth + 1)
  private val tuCountWidth = log2Ceil(Seq(32, 32, mapQDepth).max + 1)
  private val tuRetireSourceCountWidth = log2Ceil(traceParams.commitWidth + 1)
  private val tuRetireSourceQueueCountWidth = log2Ceil(tuRetireSourceQueueDepth + 1)
  private val tuRetireRelationCountWidth = log2Ceil(tuRetireRelationCmapDepth + 1)
  private val markerRetireSourceCountWidth = log2Ceil(traceParams.commitWidth + 1)
  private val markerRetireSourceQueueCountWidth = log2Ceil(markerRetireSourceQueueDepth + 1)

  val d1 = Input(new FrontendDecodePacket(p))
  val slots = Input(Vec(p.decodeWidth, new F4Slot(p)))
  val validMask = Input(UInt(p.decodeWidth.W))
  val flushValid = Input(Bool())

  val renamedOutReady = Input(Bool())
  val storeStaExec = Input(new StoreDispatchExecResult(64, 64, peIdWidth, stidWidth, tidWidth))
  val storeStdExec = Input(new StoreDispatchExecResult(64, 64, peIdWidth, stidWidth, tidWidth))
  val storeMarkCommitValid = Input(Bool())
  val storeMarkCommitIndex = Input(UInt(ptrWidth.W))
  val storeCommitFreeValid = Input(Bool())
  val storeCommitFreeIndex = Input(UInt(ptrWidth.W))
  val storeCommitFreeMaskValid = Input(Bool())
  val storeCommitFreeMask = Input(UInt(p.robEntries.W))
  val checkpointValid = Input(Bool())
  val checkpointBid = Input(new ROBID(p.robEntries))
  val commitValid = Input(Bool())
  val commitBid = Input(new ROBID(p.robEntries))
  val commitBlockBid = Input(UInt(bidWidth.W))
  val cleanup = Input(new RecoveryCleanupIntent(p.robEntries, bidWidth, peIdWidth, stidWidth, tidWidth))
  val scalarCleanupOrderValid = Input(Bool())
  val scalarCleanupOrder = Input(UInt(p.uopUidWidth.W))

  val completeValid = Input(Bool())
  val completeRobValue = Input(UInt(ptrWidth.W))
  val completeRowValid = Input(Bool())
  val completeRow = Input(new CommitTraceRow(traceParams))
  val blockBranchTakenValid = Input(Bool())
  val blockBranchTaken = Input(Bool())
  val scalarRedirectValid = Input(Bool())
  val scalarRedirectStid = Input(UInt(stidWidth.W))
  val deallocReady = Input(Bool())
  val deallocHoldMask = Input(UInt(p.robEntries.W))

  val decodedValidMask = Output(UInt(p.decodeWidth.W))
  val invalidOpcodeMask = Output(UInt(p.decodeWidth.W))
  val blockBoundaryMask = Output(UInt(p.decodeWidth.W))
  val blockStopMask = Output(UInt(p.decodeWidth.W))
  val loadMask = Output(UInt(p.decodeWidth.W))
  val storeMask = Output(UInt(p.decodeWidth.W))
  val selectedValid = Output(Bool())
  val selectedSlot = Output(UInt(slotWidth.W))
  val selectedRobValue = Output(UInt(ptrWidth.W))
  val selectedBlockBid = Output(UInt(bidWidth.W))
  val blockMarkerSkipValid = Output(Bool())
  val blockMarkerMixedPacket = Output(Bool())
  val blockMarkerBoundary = Output(Bool())
  val blockMarkerStop = Output(Bool())
  val blockMarkerPc = Output(UInt(p.pcWidth.W))
  val blockMarkerInsn = Output(UInt(p.insnWidth.W))
  val blockMarkerLen = Output(UInt(4.W))
  val blockMarkerTarget = Output(UInt(p.pcWidth.W))
  val blockMarkerAllocReady = Output(Bool())
  val blockMarkerLifecycleConflict = Output(Bool())
  val blockMarkerAllocFire = Output(Bool())
  val blockMarkerAllocBid = Output(UInt(bidWidth.W))
  val blockMarkerActiveValid = Output(Bool())
  val blockMarkerActiveBid = Output(UInt(bidWidth.W))
  val blockMarkerActiveTarget = Output(UInt(p.pcWidth.W))
  val blockMarkerStopRedirectValid = Output(Bool())
  val blockMarkerStopRedirectPc = Output(UInt(p.pcWidth.W))
  val decodeReady = Output(Bool())
  val decodeQueuePushReady = Output(Bool())
  val decodeAllocReady = Output(Bool())
  val decodeGprReservationReady = Output(Bool())
  val decodeSelectedClosesActiveRedirect = Output(Bool())
  val decodeSelectedNeedsGprReservation = Output(Bool())
  val gprReservationCount = Output(UInt(gprReservationCountWidth.W))
  val gprReservationNeed = Output(UInt(gprReservationCountWidth.W))
  val decRenPushReady = Output(Bool())
  val decRenPushFire = Output(Bool())
  val decRenPopFire = Output(Bool())
  val decRenValid = Output(Bool())
  val decRenHead = Output(UInt(decRenPtrWidth.W))
  val decRenTail = Output(UInt(decRenPtrWidth.W))
  val decRenCount = Output(UInt(decRenCountWidth.W))
  val decRenEmpty = Output(Bool())
  val decRenFull = Output(Bool())
  val decRenHeadPc = Output(UInt(p.pcWidth.W))
  val decRenHeadUsesLocal = Output(Bool())
  val decRenHeadRidValid = Output(Bool())
  val decRenHeadRidValue = Output(UInt(p.robIndexWidth.W))
  val selectedIsLoad = Output(Bool())
  val selectedIsStore = Output(Bool())
  val selectedMemoryValid = Output(Bool())
  val lsidAssignFire = Output(Bool())
  val selectedLsId = Output(UInt(p.lsidWidth.W))
  val selectedLoadId = Output(UInt(loadStoreSerialWidth.W))
  val selectedStoreId = Output(UInt(loadStoreSerialWidth.W))
  val nextLsId = Output(UInt(p.lsidWidth.W))
  val nextLoadId = Output(UInt(loadStoreSerialWidth.W))
  val nextStoreId = Output(UInt(loadStoreSerialWidth.W))
  val storeSplitIntent = Output(Bool())

  val renamedOutValid = Output(Bool())
  val renamedOut = Output(new RenamedUop(p))
  val storeDispatchReady = Output(Bool())
  val storeDispatchFire = Output(Bool())
  val storeDispatchSplit = Output(Bool())
  val storeDispatchBlockedBySta = Output(Bool())
  val storeDispatchBlockedByStd = Output(Bool())
  val storeSta = Output(new StoreSplitIssuePayload(p, mapQDepth))
  val storeStd = Output(new StoreSplitIssuePayload(p, mapQDepth))
  val storeUnsplit = Output(new StoreSplitIssuePayload(p, mapQDepth))
  val storeStaQueueValid = Output(Bool())
  val storeStdQueueValid = Output(Bool())
  val storeStaQueue = Output(new StoreSplitIssuePayload(p, mapQDepth))
  val storeStdQueue = Output(new StoreSplitIssuePayload(p, mapQDepth))
  val storeStaEnqueueFire = Output(Bool())
  val storeStdEnqueueFire = Output(Bool())
  val storeStaDequeueFire = Output(Bool())
  val storeStdDequeueFire = Output(Bool())
  val storeDispatchInputProtocolError = Output(Bool())
  val storeStaQueueCount = Output(UInt(storeDispatchCountWidth.W))
  val storeStdQueueCount = Output(UInt(storeDispatchCountWidth.W))
  val storeStaQueueFull = Output(Bool())
  val storeStdQueueFull = Output(Bool())
  val storeStaInsertReady = Output(Bool())
  val storeStdInsertReady = Output(Bool())
  val storeStaInsertCanMerge = Output(Bool())
  val storeStdInsertCanMerge = Output(Bool())
  val storeStaInsertCanAllocate = Output(Bool())
  val storeStdInsertCanAllocate = Output(Bool())
  val storeSelectedSta = Output(Bool())
  val storeSelectedStd = Output(Bool())
  val storeBlockedByStaExec = Output(Bool())
  val storeBlockedByStdExec = Output(Bool())
  val storeBlockedByStaInsert = Output(Bool())
  val storeBlockedByStdInsert = Output(Bool())
  val storeStdBypassStaBlocked = Output(Bool())
  val storeStqInsertValid = Output(Bool())
  val storeStqInsert = Output(new STQStoreRequest(p.robEntries, 64, 64, peIdWidth, stidWidth, tidWidth, 4, 8, mapQDepth))
  val storeStqInsertAccepted = Output(Bool())
  val storeStqInsertAllocated = Output(Bool())
  val storeStqInsertMerged = Output(Bool())
  val storeStqInsertConflict = Output(Bool())
  val storeStqInsertIndex = Output(UInt(ptrWidth.W))
  val storeMarkCommitAccepted = Output(Bool())
  val storeMarkCommitIgnored = Output(Bool())
  val storeCommitFreeAccepted = Output(Bool())
  val storeCommitFreeIgnored = Output(Bool())
  val storeCommitFreeAcceptedMask = Output(UInt(p.robEntries.W))
  val storeCommitFreeIgnoredMask = Output(UInt(p.robEntries.W))
  val storeCommitFreeCount = Output(UInt(stqCountWidth.W))
  val storeStqFlushApplied = Output(Bool())
  val storeStqFlushMatchMask = Output(UInt(p.robEntries.W))
  val storeStqFlushFreeMask = Output(UInt(p.robEntries.W))
  val storeStqFlushStatusBlockedMask = Output(UInt(p.robEntries.W))
  val storeStqFlushFreeCount = Output(UInt(stqCountWidth.W))
  val storeLsuTULinkSource = Output(new TULinkFlushSequenceSource(p, mapQDepth, stidWidth))
  val storeLsuTULinkSourceMatched = Output(Bool())
  val storeLsuTULinkSourceMultipleMatch = Output(Bool())
  val storeStqRows = Output(Vec(p.robEntries, new STQEntryBankRow(p.robEntries, 64, 64, peIdWidth, stidWidth, tidWidth, 4, 8, mapQDepth)))
  val storeStqOccupiedMask = Output(UInt(p.robEntries.W))
  val storeStqWaitMask = Output(UInt(p.robEntries.W))
  val storeStqCommitMask = Output(UInt(p.robEntries.W))
  val storeStqAddrReadyMask = Output(UInt(p.robEntries.W))
  val storeStqDataReadyMask = Output(UInt(p.robEntries.W))
  val storeStqResidentCount = Output(UInt(stqCountWidth.W))
  val storeStqOutstandingWaitCount = Output(UInt(stqCountWidth.W))
  val storeStqEmpty = Output(Bool())
  val storeStqFull = Output(Bool())
  val storeStqStall = Output(Bool())
  val accepted = Output(Bool())
  val robAllocAttemptValid = Output(Bool())
  val robAllocReady = Output(Bool())
  val robAllocFire = Output(Bool())
  val robAllocBlockedByBrob = Output(Bool())
  val robAllocBlockedByRob = Output(Bool())
  val robAllocDuplicateIdentity = Output(Bool())
  val robRenameUpdateAttemptValid = Output(Bool())
  val robRenameUpdateReady = Output(Bool())
  val robRenameUpdateFire = Output(Bool())
  val robRenameUpdateIgnored = Output(Bool())
  val robMarkerRowCompletePending = Output(Bool())
  val robMarkerRowCompleteFire = Output(Bool())
  val blockedByMaintenance = Output(Bool())
  val blockedByRename = Output(Bool())
  val blockedByRob = Output(Bool())
  val blockedByOutput = Output(Bool())
  val unsupported = Output(Bool())
  val unsupportedSrcMask = Output(UInt(3.W))
  val unsupportedDst = Output(Bool())
  val unsupportedOperandClass = Output(Bool())
  val blockedByTURename = Output(Bool())
  val gprFreeCount = Output(UInt(gprFreeWidth.W))
  val gprMapQValidCount = Output(UInt(gprMapQFreeWidth.W))
  val gprMapQFreeCount = Output(UInt(gprMapQFreeWidth.W))
  val gprSmapLiveCount = Output(UInt(gprFreeWidth.W))
  val gprCmapLiveCount = Output(UInt(gprFreeWidth.W))
  val gprMapQLiveCount = Output(UInt(gprFreeWidth.W))
  val gprLivePhysCount = Output(UInt(gprFreeWidth.W))
  val gprFreeFromLiveCount = Output(UInt(gprFreeWidth.W))
  val gprFreeListMismatchCount = Output(UInt(gprFreeWidth.W))
  val gprNextMapQValidCount = Output(UInt(gprMapQFreeWidth.W))
  val gprNextMapQLiveCount = Output(UInt(gprFreeWidth.W))
  val gprNextLivePhysCount = Output(UInt(gprFreeWidth.W))
  val gprNextFreeFromLiveCount = Output(UInt(gprFreeWidth.W))
  val gprCommitAccepted = Output(Bool())
  val gprCommitBlockBid = Output(UInt(bidWidth.W))
  val gprCommittedMapQCount = Output(UInt(gprMapQFreeWidth.W))
  val gprReleasedPhysCount = Output(UInt(gprFreeWidth.W))
  val tuRenameReady = Output(Bool())
  val tuRenameAccepted = Output(Bool())
  val tuRenameActivePeId = Output(UInt(peIdWidth.W))
  val tuRenameActiveStid = Output(UInt(stidWidth.W))
  val tuRenameActivePeInRange = Output(Bool())
  val tuRenameActiveStidInRange = Output(Bool())
  val tuRenameActiveBankValid = Output(Bool())
  val tuRenameTSeq = Output(new ROBID(mapQDepth))
  val tuRenameUSeq = Output(new ROBID(mapQDepth))
  val tuRenameDstValid = Output(Bool())
  val tuRenameDstKind = Output(DestinationKind())
  val tuRenameNeedsTAlloc = Output(Bool())
  val tuRenameNeedsUAlloc = Output(Bool())
  val tuRenameBlockedByTAlloc = Output(Bool())
  val tuRenameBlockedByUAlloc = Output(Bool())
  val tuRenameSourceUnderflowMask = Output(UInt(3.W))
  val tuRenameTUsedEntries = Output(UInt(tuCountWidth.W))
  val tuRenameUUsedEntries = Output(UInt(tuCountWidth.W))

  val allocBlockBid = Output(UInt(bidWidth.W))
  val allocRobValue = Output(UInt(ptrWidth.W))
  val completeAccepted = Output(Bool())
  val completeIgnored = Output(Bool())
  val commit = Output(new CommitTracePort(traceParams))
  val commitMemoryOrder = Output(Vec(traceParams.commitWidth, new ROBMemoryOrderCommit(p.robEntries, p.lsidWidth)))
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
  val robDeallocTURetireSource = Output(Vec(traceParams.commitWidth, new TULinkRetireSource(p, mapQDepth, stidWidth, peIdWidth)))
  val robDeallocBlockMarkerRetireSource =
    Output(Vec(traceParams.commitWidth, new BlockMarkerRetireSource(
      entries = p.robEntries,
      blockBidWidth = traceParams.blockBidWidth,
      pcWidth = traceParams.pcWidth,
      insnWidth = traceParams.insnWidth,
      lenWidth = traceParams.lenWidth,
      peIdWidth = peIdWidth,
      stidWidth = stidWidth
    )))
  val robMarkerRetireSourceWindowReady = Output(Bool())
  val robMarkerRetireSourceValidMask = Output(UInt(traceParams.commitWidth.W))
  val robMarkerRetireSourceEnqueueCount = Output(UInt(markerRetireSourceCountWidth.W))
  val robMarkerRetireSourceQueueCount = Output(UInt(markerRetireSourceQueueCountWidth.W))
  val robMarkerRetireSourceQueueFull = Output(Bool())
  val robMarkerRetireSourceQueueEmpty = Output(Bool())
  val robMarkerRetireSourceDequeued = Output(Bool())
  val robMarkerRetireSourcePruneCount = Output(UInt(markerRetireSourceQueueCountWidth.W))
  val robMarkerRetireSourceLifecycleReady = Output(Bool())
  val robMarkerRetireSourceLifecycleFire = Output(Bool())
  val robMarkerRetireSourceLifecycleBoundaryFire = Output(Bool())
  val robMarkerRetireSourceLifecycleStopFire = Output(Bool())
  val robMarkerRetireSource =
    Output(new BlockMarkerRetireSource(
      entries = p.robEntries,
      blockBidWidth = traceParams.blockBidWidth,
      pcWidth = traceParams.pcWidth,
      insnWidth = traceParams.insnWidth,
      lenWidth = traceParams.lenWidth,
      peIdWidth = peIdWidth,
      stidWidth = stidWidth
    ))
  val robDeallocBlockLastValid = Output(Bool())
  val robDeallocBlockLastBid = Output(new ROBID(p.robEntries))
  val robDeallocBlockLastGid = Output(new ROBID(p.robEntries))
  val robDeallocBlockLastBlockBid = Output(UInt(bidWidth.W))
  val blockScalarDoneFire = Output(Bool())
  val blockScalarDoneBid = Output(UInt(bidWidth.W))
  val blockRetireFire = Output(Bool())
  val blockRetireBid = Output(UInt(bidWidth.W))
  val tuRetireSourceWindowReady = Output(Bool())
  val tuRetireSourceValidMask = Output(UInt(traceParams.commitWidth.W))
  val tuRetireSourceEnqueueCount = Output(UInt(tuRetireSourceCountWidth.W))
  val tuRetireSourceQueueCount = Output(UInt(tuRetireSourceQueueCountWidth.W))
  val tuRetireSourceQueueFull = Output(Bool())
  val tuRetireSourceQueueEmpty = Output(Bool())
  val tuRetireSourceDequeued = Output(Bool())
  val tuRetireCleanupActive = Output(Bool())
  val tuRetireSourcePruneCount = Output(UInt(tuRetireSourceQueueCountWidth.W))
  val tuRetireRelationPruneTCount = Output(UInt(tuRetireRelationCountWidth.W))
  val tuRetireRelationPruneUCount = Output(UInt(tuRetireRelationCountWidth.W))
  val tuRetireCommandValid = Output(Bool())
  val tuRetireCommandKind = Output(DestinationKind())
  val tuRetireCommandSeq = Output(new ROBID(mapQDepth))
  val tuRetireCommandDealloc = Output(Bool())
  val tuRetireCommandPeId = Output(UInt(peIdWidth.W))
  val tuRetireCommandStid = Output(UInt(stidWidth.W))
  val tuRetireCommandFire = Output(Bool())
  val tuRetireAutoCleanBlockPending = Output(Bool())
  val tuRetireAutoCleanBlockValid = Output(Bool())
  val tuRetireAutoCleanBlockBid = Output(new ROBID(p.robEntries))
  val tuRetireLocalBlockCommitPending = Output(Bool())
  val tuRetireLocalBlockCommitValid = Output(Bool())
  val tuRetireLocalBlockCommitReady = Output(Bool())
  val tuRetireLocalBlockCommitBid = Output(new ROBID(p.robEntries))
  val tuRetireLocalBlockCommitStid = Output(UInt(stidWidth.W))
  val tuRetireLocalBlockCommitFire = Output(Bool())
  val tuRetireLocalBlockCommitAccepted = Output(Bool())
  val tuRetireLocalBlockCommitStidMatch = Output(Bool())
  val tuRetireLocalBlockCommitBlockedByStid = Output(Bool())
  val tuRetireLocalBlockCommitFanoutStidInRange = Output(Bool())
  val tuRetireLocalBlockCommitFanoutBlockedByStidRange = Output(Bool())
  val tuRetireLocalBlockCommitFanoutBlockedByBankReady = Output(Bool())
  val tuRetireLocalBlockCommitFanoutTargetPeMask = Output(UInt(1.W))
  val tuRetireLocalBlockCommitFanoutReadyPeMask = Output(UInt(1.W))
  val tuRetireUnsupportedDst = Output(Bool())
  val tuRetireRelationPreReleaseT = Output(Bool())
  val tuRetireRelationPreReleaseU = Output(Bool())
  val tuRetireRelationPressureReleaseT = Output(Bool())
  val tuRetireRelationPressureReleaseU = Output(Bool())
  val tuRetireRelationPendingMark = Output(Bool())
  val tuRetireRelationPendingPostReleaseT = Output(Bool())
  val tuRetireRelationPendingPostReleaseU = Output(Bool())
  val tuRetireRelationTCount = Output(UInt(tuRetireRelationCountWidth.W))
  val tuRetireRelationUCount = Output(UInt(tuRetireRelationCountWidth.W))
  val tuRetireAccepted = Output(Bool())
  val tuRetireMiss = Output(Bool())
  val tuRetireReleaseMismatch = Output(Bool())
  val tuRetireUnsupported = Output(Bool())
  val tuRetirePeInRange = Output(Bool())
  val tuRetireStidInRange = Output(Bool())
  val tuRetireBankValid = Output(Bool())

  val flushApplied = Output(Bool())
  val robTULinkSource = Output(new TULinkFlushSequenceSource(p, mapQDepth, stidWidth))
  val robTULinkSourceMatched = Output(Bool())
  val robTULinkSourceMultipleMatch = Output(Bool())
  val tuCleanupPublisherFlushValid = Output(Bool())
  val tuCleanupPublisherFlushBaseOnBid = Output(Bool())
  val tuCleanupPublisherFlushBid = Output(new ROBID(p.robEntries))
  val tuCleanupPublisherFlushRid = Output(new ROBID(p.robEntries))
  val tuCleanupPublisherFlushTSeq = Output(new ROBID(mapQDepth))
  val tuCleanupPublisherFlushUSeq = Output(new ROBID(mapQDepth))
  val tuCleanupActive = Output(Bool())
  val tuCleanupBlockedBySource = Output(Bool())
  val tuCleanupFlushSourceRequired = Output(Bool())
  val tuCleanupFlushSourceMatched = Output(Bool())
  val tuCleanupFlushMissingSource = Output(Bool())
  val tuCleanupFlushSourceMismatch = Output(Bool())
  val tuCleanupSelectedFlushSource = Output(new TULinkFlushSequenceSource(p, mapQDepth, stidWidth))
  val tuCleanupRobSourceMatched = Output(Bool())
  val tuCleanupLsuSourceMatched = Output(Bool())
  val tuCleanupRobSourceMismatched = Output(Bool())
  val tuCleanupLsuSourceMismatched = Output(Bool())
  val tuCleanupMultipleSourcesMatched = Output(Bool())
  val tuCleanupSourceConflict = Output(Bool())
  val tuCleanupSelectorSourceMissing = Output(Bool())
  val tuCleanupSelectedFromRob = Output(Bool())
  val tuCleanupSelectedFromLsu = Output(Bool())
  val tuCleanupFlushTPrevApplied = Output(Bool())
  val tuCleanupFlushUPrevApplied = Output(Bool())
  val empty = Output(Bool())
  val full = Output(Bool())
  val size = Output(UInt(sizeWidth.W))
  val outstandingCount = Output(UInt(sizeWidth.W))
  val commitHeadValid = Output(Bool())
  val commitHeadStatus = Output(ROBEntryStatus())
  val commitHeadRobValue = Output(UInt(ptrWidth.W))
  val robStatusLookupValid = Input(Bool())
  val robStatusLookupRid = Input(new ROBID(p.robEntries))
  val robStatusLookup = Output(new ROBRowStatusLookupResult(p.robEntries))
  val robCommitTraceLookupValid = Input(Bool())
  val robCommitTraceLookupRid = Input(new ROBID(p.robEntries))
  val robCommitTraceLookupSourceTraceEnable = Input(Bool())
  val robCommitTraceLookup = Output(new ROBRowCommitTraceLookupResult(p.robEntries, traceParams))
  val occupiedMask = Output(UInt(p.robEntries.W))
  val completedMask = Output(UInt(p.robEntries.W))
  val retiredMask = Output(UInt(p.robEntries.W))
  val blockAllocatedMask = Output(UInt(p.robEntries.W))
  val blockCompleteMask = Output(UInt(p.robEntries.W))
  val blockPendingMask = Output(UInt(p.robEntries.W))
}

class DecodeRenameROBPath(
    val p: InterfaceParams = InterfaceParams(),
    val traceParams: CommitTraceParams = CommitTraceParams(),
    val bidWidth: Int = BID.DefaultWidth,
    val stidWidth: Int = 8,
    val peIdWidth: Int = 8,
    val tidWidth: Int = 8,
    val localStid: Int = 0,
    val scalarStidCount: Int = 1,
    val blockTypeWidth: Int = 4,
    val trapCauseWidth: Int = 32,
    val scalarArchRegs: Int = 24,
    val physRegs: Int = 64,
    val mapQDepth: Int = 32,
    val gprMapQDepth: Int = 32,
    val decRenQueueDepth: Int = 4,
    val storeDispatchQueueDepth: Int = 4,
    val loadStoreSerialWidth: Int = 64,
    val tuRetireSourceQueueDepth: Int = 8,
    val tuRetireRelationCmapDepth: Int = 8,
    val markerRetireSourceQueueDepth: Int = 8,
    val tuRetireReleaseThreshold: Int = 4,
    val blockRenameCommitQueueDepth: Int = 16,
    val scalarContinuationGprCutThreshold: Int = 32,
    val useMarkerDecodeContext: Boolean = false,
    val skipBlockMarkers: Boolean = false,
    val reducedStoreDispatchBypass: Boolean = false)
    extends Module {
  require(traceParams.robValueWidth >= p.robIndexWidth, "trace ROB value must hold DecodeRenameROBPath ROB index")
  require(traceParams.commitWidth == p.commitWidth, "trace commit width must match InterfaceParams")
  require(traceParams.blockBidWidth == p.blockBidWidth, "trace block BID width must match InterfaceParams")
  require(traceParams.pcWidth == p.pcWidth, "trace PC width must match InterfaceParams")
  require(traceParams.insnWidth == p.insnWidth, "trace instruction width must match InterfaceParams")
  require(traceParams.lenWidth == p.lenWidth, "trace instruction length width must match InterfaceParams")
  require((p.robEntries & (p.robEntries - 1)) == 0, "ROB entries must be a power of two")
  require(decRenQueueDepth > 0 && (decRenQueueDepth & (decRenQueueDepth - 1)) == 0,
    "decode-to-rename queue depth must be a power of two")
  require(tuRetireSourceQueueDepth >= traceParams.commitWidth,
    "T/U retire source queue must hold one full ROB dealloc window")
  require((tuRetireSourceQueueDepth & (tuRetireSourceQueueDepth - 1)) == 0,
    "T/U retire source queue depth must be a power of two")
  require(markerRetireSourceQueueDepth > 1, "marker retire source queue depth must be greater than one")
  require(markerRetireSourceQueueDepth >= traceParams.commitWidth,
    "marker retire source queue must hold one full ROB dealloc window")
  require((markerRetireSourceQueueDepth & (markerRetireSourceQueueDepth - 1)) == 0,
    "marker retire source queue depth must be a power of two")
  require(blockRenameCommitQueueDepth > 1,
    "block rename commit queue depth must be greater than one")
  require((blockRenameCommitQueueDepth & (blockRenameCommitQueueDepth - 1)) == 0,
    "block rename commit queue depth must be a power of two")
  require(scalarContinuationGprCutThreshold > 1,
    "scalar continuation GPR cut threshold must be greater than one")
  require(scalarStidCount > 0, "DecodeRenameROBPath must expose at least one scalar STID lane")
  require(BigInt(scalarStidCount) <= (BigInt(1) << stidWidth), "scalar STID count must fit stidWidth")

  private val zeroRobId = 0.U.asTypeOf(new ROBID(p.robEntries))
  private val zeroLocalSeq = 0.U.asTypeOf(new ROBID(mapQDepth))
  private val gprReservationCountWidth = log2Ceil(decRenQueueDepth + 1)
  private def fitReg(tag: UInt): UInt =
    tag.pad(traceParams.regWidth)(traceParams.regWidth - 1, 0)

  private def robIdValue(id: ROBID): UInt =
    id.value.pad(32)(31, 0)

  private def commitReservationRow(uop: DecodedUop): CommitTraceRow = {
    val row = Wire(new CommitTraceRow(traceParams))
    row := 0.U.asTypeOf(row)
    row.valid := uop.valid
    row.identity.bid := robIdValue(uop.bid)
    row.identity.gid := robIdValue(uop.gid)
    row.identity.rid := robIdValue(uop.rid)
    row.blockBidValid := uop.blockBidValid
    row.blockBid := uop.blockBid
    row.pc := uop.pc
    row.insn := uop.insnRaw
    row.len := uop.insnLen
    row.nextPc := uop.pc + uop.insnLen
    row.src0.valid := uop.valid && uop.src(0).valid
    row.src0.reg := fitReg(uop.src(0).archTag)
    row.src1.valid := uop.valid && uop.src(1).valid
    row.src1.reg := fitReg(uop.src(1).archTag)
    row.dst.valid := uop.valid && uop.dst(0).valid
    row.dst.reg := fitReg(uop.dst(0).archTag)
    row
  }
  val io = IO(new DecodeRenameROBPathIO(
    p = p,
    traceParams = traceParams,
    bidWidth = bidWidth,
    stidWidth = stidWidth,
    peIdWidth = peIdWidth,
    tidWidth = tidWidth,
    blockTypeWidth = blockTypeWidth,
    trapCauseWidth = trapCauseWidth,
    decRenQueueDepth = decRenQueueDepth,
    storeDispatchQueueDepth = storeDispatchQueueDepth,
    loadStoreSerialWidth = loadStoreSerialWidth,
    physRegs = physRegs,
    mapQDepth = mapQDepth,
    gprMapQDepth = gprMapQDepth,
    tuRetireSourceQueueDepth = tuRetireSourceQueueDepth,
    tuRetireRelationCmapDepth = tuRetireRelationCmapDepth,
    markerRetireSourceQueueDepth = markerRetireSourceQueueDepth
  ))

  val decode = Module(new FrontendDecodeStage(p))
  decode.io.d1 := io.d1
  decode.io.slots := io.slots
  decode.io.validMask := io.validMask
  decode.io.flushValid := io.flushValid

  io.decodedValidMask := decode.io.outValidMask
  io.invalidOpcodeMask := decode.io.invalidOpcodeMask
  io.blockBoundaryMask := decode.io.blockBoundaryMask
  io.blockStopMask := decode.io.blockStopMask
  io.loadMask := decode.io.loadMask
  io.storeMask := decode.io.storeMask

  val markerMask = decode.io.blockBoundaryMask | decode.io.blockStopMask
  val markerValidMask = decode.io.outValidMask & markerMask
  val nonMarkerValidMask = decode.io.outValidMask & ~markerMask
  val skipMarkers = skipBlockMarkers.B
  val selectedMask = Mux(skipMarkers, nonMarkerValidMask, decode.io.outValidMask)
  val markerOnlyPacket = skipMarkers && markerValidMask.orR && !nonMarkerValidMask.orR
  val mixedMarkerPacket = skipMarkers && markerValidMask.orR && nonMarkerValidMask.orR

  val selectedAny = selectedMask.orR && !mixedMarkerPacket
  val selectedSlot = PriorityEncoder(selectedMask)
  val selected = Wire(new DecodedUop(p))
  selected := 0.U.asTypeOf(selected)
  for (slot <- 0 until p.decodeWidth) {
    when(selectedAny && selectedSlot === slot.U) {
      selected := decode.io.out(slot)
    }
  }

  val markerSlot = PriorityEncoder(markerValidMask)
  val marker = Wire(new DecodedUop(p))
  marker := 0.U.asTypeOf(marker)
  for (slot <- 0 until p.decodeWidth) {
    when(markerValidMask.orR && markerSlot === slot.U) {
      marker := decode.io.out(slot)
    }
  }

  val selectedIsLoad = selectedAny && VecInit(decode.io.loadMask.asBools)(selectedSlot)
  val selectedIsStore = selectedAny && VecInit(decode.io.storeMask.asBools)(selectedSlot)
  val selectedNeedsGprReservation =
    selectedAny && selected.dst(0).valid && (selected.dst(0).kind === DestinationKind.Gpr) &&
      (selected.dst(0).archTag < scalarArchRegs.U)
  val markerBoundary = markerOnlyPacket && VecInit(decode.io.blockBoundaryMask.asBools)(markerSlot)
  val markerStop = markerOnlyPacket && VecInit(decode.io.blockStopMask.asBools)(markerSlot)
  val selectedStid = selected.threadId.pad(stidWidth)(stidWidth - 1, 0)
  val markerStid = marker.threadId.pad(stidWidth)(stidWidth - 1, 0)
  val localScalarRedirectValid = Wire(Bool())
  val localScalarRedirectStid = Wire(UInt(stidWidth.W))

  val allocator = Module(new DispatchROBAllocator(
    entries = p.robEntries,
    traceParams = traceParams,
    bidWidth = bidWidth,
    peIdWidth = peIdWidth,
    tidWidth = tidWidth,
    blockTypeWidth = blockTypeWidth,
    trapCauseWidth = trapCauseWidth,
    mapQDepth = mapQDepth,
    stidWidth = stidWidth,
    lsidWidth = p.lsidWidth
  ))
  val blockLifecycleFlush = io.cleanup.valid && (io.cleanup.backendFlushValid || io.cleanup.blockFlushValid)
  val blockScalarDoneSeq = Module(new BlockScalarDoneSequencer(bidWidth))
  val markerLifecycle = Module(new BlockMarkerLifecycle(
    entries = p.robEntries,
    bidWidth = bidWidth,
    pcWidth = p.pcWidth,
    insnWidth = p.insnWidth,
    lenWidth = p.lenWidth,
    peIdWidth = peIdWidth,
    stidWidth = stidWidth,
    stidCount = scalarStidCount
  ))
  val robBlockLastScalarDoneFire = allocator.io.deallocBlockLastValid
  val markerLifecycleConflict = robBlockLastScalarDoneFire
  markerLifecycle.io.flushValid := blockLifecycleFlush
  markerLifecycle.io.markerBoundary := markerBoundary
  markerLifecycle.io.markerStop := markerStop
  markerLifecycle.io.markerPc := marker.pc
  markerLifecycle.io.markerTarget := marker.boundaryTarget
  markerLifecycle.io.markerInsnLen := marker.insnLen
  markerLifecycle.io.markerBoundaryKind := marker.boundaryKind
  markerLifecycle.io.markerStid := markerStid
  markerLifecycle.io.markerAllocReady := allocator.io.blockAllocOnlyReady
  markerLifecycle.io.markerAllocBid := allocator.io.blockAllocOnlyBid
  markerLifecycle.io.branchTakenValid := io.blockBranchTakenValid
  markerLifecycle.io.branchTaken := io.blockBranchTaken
  markerLifecycle.io.scalarWorkPending := !allocator.io.empty
  markerLifecycle.io.markerLifecycleConflict := markerLifecycleConflict
  markerLifecycle.io.retirePending := blockScalarDoneSeq.io.retirePending
  markerLifecycle.io.scalarRedirectValid := localScalarRedirectValid
  markerLifecycle.io.scalarRedirectStid := localScalarRedirectStid
  markerLifecycle.io.scalarBlockStartFire :=
    allocator.io.allocFire && selectedAny && !markerLifecycle.io.activeValid
  markerLifecycle.io.scalarBlockStartStid := selectedStid
  markerLifecycle.io.scalarBlockStartBid := allocator.io.allocBlockBid
  markerLifecycle.io.robBlockLastValid := robBlockLastScalarDoneFire
  markerLifecycle.io.robBlockLastBid := allocator.io.deallocBlockLastBlockBid
  markerLifecycle.io.activeQueryStid := selectedStid

  val scalarContinuationGprCut = Wire(Bool())
  scalarContinuationGprCut := false.B
  val decRenFlush = io.flushValid || (io.cleanup.valid && io.cleanup.backendFlushValid)
  val markerDecodeContextOpt =
    if (useMarkerDecodeContext) {
      val ctx = Module(new BlockMarkerDecodeContext(
        bidWidth = bidWidth,
        pcWidth = p.pcWidth,
        stidWidth = stidWidth,
        stidCount = scalarStidCount
      ))
      ctx.io.flushValid := decRenFlush || blockLifecycleFlush
      ctx.io.decodeValid := selectedAny
      ctx.io.decodeFire := allocator.io.allocFire
      ctx.io.decodeBoundary := selected.sob
      ctx.io.decodeStop := selected.eob
      ctx.io.decodeLast := selected.isLastInBlock || scalarContinuationGprCut
      ctx.io.decodeStid := selectedStid
      ctx.io.decodeAllocBid := allocator.io.allocBlockBid
      ctx.io.decodeTarget := selected.boundaryTarget
      ctx.io.decodeBoundaryKind := selected.boundaryKind
      ctx.io.scalarRedirectValid := localScalarRedirectValid
      ctx.io.scalarRedirectStid := localScalarRedirectStid
      ctx.io.robBlockLastValid := robBlockLastScalarDoneFire
      ctx.io.robBlockLastBid := allocator.io.deallocBlockLastBlockBid
      ctx.io.queryStid := selectedStid
      Some(ctx)
    } else {
      None
    }
  val memIds = Module(new DecodeLoadStoreIdAssign(p, serialWidth = loadStoreSerialWidth))
  memIds.io.in := selected
  memIds.io.isLoad := selectedIsLoad
  memIds.io.isStore := selectedIsStore
  memIds.io.isDczva := false.B
  memIds.io.isLoadStorePair := selected.isLoadStorePair
  memIds.io.isStorePcr := selected.isStorePcr
  memIds.io.cacheMaintainNoSplit := selected.cacheMaintainNoSplit
  memIds.io.storeSplitRequest := selectedIsStore
  memIds.io.stackSetRequest := false.B
  memIds.io.flushValid := decRenFlush
  memIds.io.restoreValid := false.B
  memIds.io.restoreLsId := 0.U
  memIds.io.restoreLoadId := 0.U
  memIds.io.restoreStoreId := 0.U

  val activeBlockValid = markerDecodeContextOpt.map(_.io.activeValid).getOrElse(markerLifecycle.io.activeValid)
  val activeBlockBid = markerDecodeContextOpt.map(_.io.activeBid).getOrElse(markerLifecycle.io.activeBid)
  val activeBlockTarget = markerDecodeContextOpt.map(_.io.activeTarget).getOrElse(markerLifecycle.io.activeTarget)
  val activeBlockCond = markerDecodeContextOpt.map(_.io.activeCond).getOrElse(markerLifecycle.io.activeCond)
  val activeBlockUnconditionalRedirect =
    markerDecodeContextOpt.map(_.io.activeUnconditionalRedirect).getOrElse(markerLifecycle.io.activeUnconditionalRedirect)
  val selectedClosesActiveRedirectIntent =
    selectedAny && !selected.sob && !selected.eob && activeBlockValid && activeBlockTarget =/= 0.U &&
      selected.pc === activeBlockTarget &&
      (activeBlockUnconditionalRedirect || (activeBlockCond && io.blockBranchTakenValid && io.blockBranchTaken))
  val selectedClosesActiveRedirect = Wire(Bool())
  selectedClosesActiveRedirect := false.B
  val selectedBlockBid =
    markerDecodeContextOpt
      .map(_.io.decodeBlockBid)
      .getOrElse(Mux(activeBlockValid, activeBlockBid, allocator.io.allocBlockBid))
  val selectedUsesExistingBlock =
    markerDecodeContextOpt.map(_.io.decodeUsesExistingBlock).getOrElse(activeBlockValid)
  val selectedExistingBlockBid =
    markerDecodeContextOpt.map(_.io.decodeActiveBid).getOrElse(activeBlockBid)
  val decodeContextMarkerCloseIntent =
    markerDecodeContextOpt.map(ctx =>
      selectedAny && ctx.io.decodeStidInRange && ctx.io.decodeActiveValid && (selected.sob || selected.eob)
    ).getOrElse(false.B)
  val decodeContextCloseIntent = decodeContextMarkerCloseIntent || selectedClosesActiveRedirectIntent
  val decodeContextClosingBlockBid =
    markerDecodeContextOpt.map(_.io.decodeActiveBid).getOrElse(0.U(bidWidth.W))
  val scalarContinuationGprCutCountWidth = log2Ceil(scalarContinuationGprCutThreshold + 1)
  val scalarContinuationGprCutCount = RegInit(0.U(scalarContinuationGprCutCountWidth.W))
  val selectedScalarBody = selectedAny && !selected.sob && !selected.eob
  val selectedContinuationGprWrite =
    selectedScalarBody && selectedUsesExistingBlock && selectedNeedsGprReservation
  scalarContinuationGprCut :=
    selectedContinuationGprWrite &&
      scalarContinuationGprCutCount === (scalarContinuationGprCutThreshold - 1).U(scalarContinuationGprCutCountWidth.W)

  val selectedForQueue = Wire(new DecodedUop(p))
  selectedForQueue := memIds.io.out
  selectedForQueue.isLastInBlock := memIds.io.out.isLastInBlock || scalarContinuationGprCut
  selectedForQueue.valid := selectedAny
  selectedForQueue.bid :=
    FullBidRecoveryBridge.fullBidToRobId(selectedBlockBid, selectedAny, p.robEntries, bidWidth)
  selectedForQueue.gid := zeroRobId
  selectedForQueue.gid.valid := selectedAny
  selectedForQueue.rid.valid := selectedAny
  selectedForQueue.rid.wrap := allocator.io.allocRobWrap
  selectedForQueue.rid.value := allocator.io.allocRobValue
  selectedForQueue.blockBidValid := selectedAny
  selectedForQueue.blockBid := selectedBlockBid

  val gprReservationCount = RegInit(0.U(gprReservationCountWidth.W))
  val gprFreeBudget = Wire(UInt(log2Ceil(physRegs + 1).W))
  val gprMapQFreeBudget = Wire(UInt(log2Ceil(gprMapQDepth + 1).W))
  val selectedGprReservationSlots =
    Mux(selectedNeedsGprReservation, 1.U(gprReservationCountWidth.W), 0.U(gprReservationCountWidth.W))
  val gprReservationNeed = gprReservationCount + selectedGprReservationSlots
  val gprReservationReady =
    (gprReservationNeed <= gprFreeBudget) && (gprReservationNeed <= gprMapQFreeBudget)
  val decodeContextCloseSafe = Wire(Bool())
  decodeContextCloseSafe := true.B

  val decRenPush = Wire(new DecodedUop(p))
  decRenPush := selectedForQueue
  decRenPush.valid :=
    selectedAny && allocator.io.allocReady && gprReservationReady && decodeContextCloseSafe &&
      !selectedClosesActiveRedirect

  val decRenQ = Module(new DecodeRenameQueue(p, depth = decRenQueueDepth))
  decRenQ.io.push := decRenPush
  decRenQ.io.flushValid := decRenFlush
  memIds.io.accept := decRenQ.io.pushFire
  decodeContextCloseSafe := !decodeContextCloseIntent || (decRenQ.io.empty && allocator.io.empty)
  selectedClosesActiveRedirect := selectedClosesActiveRedirectIntent && decodeContextCloseSafe
  localScalarRedirectValid := io.scalarRedirectValid || selectedClosesActiveRedirect
  localScalarRedirectStid := Mux(selectedClosesActiveRedirect, selectedStid, io.scalarRedirectStid)
  when(decRenFlush || blockLifecycleFlush) {
    scalarContinuationGprCutCount := 0.U
  }.elsewhen(allocator.io.allocFire && selectedAny) {
    when(selected.sob || selected.eob || scalarContinuationGprCut) {
      scalarContinuationGprCutCount := 0.U
    }.elsewhen(selectedNeedsGprReservation) {
      when(selectedUsesExistingBlock) {
        scalarContinuationGprCutCount := scalarContinuationGprCutCount + 1.U
      }.otherwise {
        scalarContinuationGprCutCount := 1.U
      }
    }.elsewhen(!selectedUsesExistingBlock) {
      scalarContinuationGprCutCount := 0.U
    }
  }

  val queuedForRename = Wire(new DecodedUop(p))
  queuedForRename := decRenQ.io.out
  val queuedMarkerForRename = queuedForRename.valid && (queuedForRename.sob || queuedForRename.eob)
  val queuedNeedsGprReservation =
    queuedForRename.valid && queuedForRename.dst(0).valid && (queuedForRename.dst(0).kind === DestinationKind.Gpr) &&
      (queuedForRename.dst(0).archTag < scalarArchRegs.U)

  val rename = Module(new ScalarTURenameBridge(
    p = p,
    traceParams = traceParams,
    scalarArchRegs = scalarArchRegs,
    physRegs = physRegs,
    mapQDepth = mapQDepth,
    gprMapQDepth = gprMapQDepth,
    bidWidth = bidWidth,
    stidWidth = stidWidth,
    peIdWidth = peIdWidth,
    tidWidth = tidWidth,
    localStid = localStid,
    scalarStidCount = scalarStidCount
  ))
  val activeTURenamePeId = queuedForRename.peId.pad(peIdWidth)(peIdWidth - 1, 0)
  val activeTURenameStid = queuedForRename.threadId.pad(stidWidth)(stidWidth - 1, 0)
  val renameCommitValid = Wire(Bool())
  val renameCommitBid = Wire(new ROBID(p.robEntries))
  val renameCommitBlockBid = Wire(UInt(bidWidth.W))
  gprFreeBudget := rename.io.gprFreeCount
  gprMapQFreeBudget := rename.io.gprMapQFreeCount
  rename.io.in := queuedForRename
  rename.io.activePeId := activeTURenamePeId
  rename.io.activeStid := activeTURenameStid
  val tuRetirePath = Module(new TULinkRetireCommandPath(
    p = p,
    sourceWidth = traceParams.commitWidth,
    mapQDepth = mapQDepth,
    sourceQueueDepth = tuRetireSourceQueueDepth,
    cmapDepth = tuRetireRelationCmapDepth,
    releaseThreshold = tuRetireReleaseThreshold,
    peIdWidth = peIdWidth,
    stidWidth = stidWidth
  ))
  val markerRetireSerializer = Module(new BlockMarkerRetireSourceSerializer(
    p = p,
    sourceWidth = traceParams.commitWidth,
    sourceQueueDepth = markerRetireSourceQueueDepth,
    peIdWidth = peIdWidth,
    stidWidth = stidWidth
  ))
  val storeDispatch = Module(new StoreDispatchSTQPath(
    p = p,
    queueDepth = storeDispatchQueueDepth,
    entries = p.robEntries,
    peIdWidth = peIdWidth,
    stidWidth = stidWidth,
    tidWidth = tidWidth,
    mapQDepth = mapQDepth
  ))
  storeDispatch.io.flush := io.cleanup.flush
  storeDispatch.io.queueFlushValid := decRenFlush && !io.cleanup.flush.req.valid
  storeDispatch.io.staExec := io.storeStaExec
  storeDispatch.io.stdExec := io.storeStdExec
  storeDispatch.io.markCommitValid := io.storeMarkCommitValid
  storeDispatch.io.markCommitIndex := io.storeMarkCommitIndex
  storeDispatch.io.commitFreeValid := io.storeCommitFreeValid
  storeDispatch.io.commitFreeIndex := io.storeCommitFreeIndex
  storeDispatch.io.commitFreeMaskValid := io.storeCommitFreeMaskValid
  storeDispatch.io.commitFreeMask := io.storeCommitFreeMask

  val storeDispatchBypass = reducedStoreDispatchBypass.B
  val queuedStoreActive = queuedForRename.valid && queuedForRename.isStore
  val queuedStoreSplit =
    queuedStoreActive && queuedForRename.storeSplitIntent &&
      !queuedForRename.isLoadStorePair && !queuedForRename.cacheMaintainNoSplit
  val storeDispatchReadyForHead =
    storeDispatchBypass || !queuedStoreActive ||
      Mux(queuedStoreSplit, storeDispatch.io.staReady && storeDispatch.io.stdReady, storeDispatch.io.staReady)

  val markerCompletionPending = RegInit(false.B)
  val markerCompletionRobValue = Reg(UInt(p.robIndexWidth.W))
  val markerCompletionRow = Reg(new CommitTraceRow(traceParams))
  val markerCanConsumeRename = !markerCompletionPending
  rename.io.outReady :=
    Mux(queuedMarkerForRename, markerCanConsumeRename, io.renamedOutReady && storeDispatchReadyForHead)
  rename.io.robAllocReady := allocator.io.renameUpdateReady
  rename.io.checkpointValid := io.checkpointValid
  rename.io.checkpointBid := io.checkpointBid
  rename.io.commitValid := renameCommitValid
  rename.io.commitBid := renameCommitBid
  rename.io.commitBlockBid := renameCommitBlockBid
  rename.io.cleanup := io.cleanup
  rename.io.cleanupOrderValid := io.scalarCleanupOrderValid
  rename.io.cleanupOrder := io.scalarCleanupOrder

  decRenQ.io.popReady := rename.io.inReady

  val storeSplit = Module(new StoreSplitPayload(p, mapQDepth))
  val renamedOutIsMarker = rename.io.outValid && (rename.io.out.sob || rename.io.out.eob)
  val storeSplitIn = Wire(new RenamedUop(p))
  storeSplitIn := rename.io.out
  when(renamedOutIsMarker) {
    storeSplitIn.valid := false.B
  }
  storeSplit.io.in := storeSplitIn
  storeSplit.io.tSeq := rename.io.tuTSeq
  storeSplit.io.uSeq := rename.io.tuUSeq
  storeSplit.io.tuDstValid := rename.io.tuDstValid
  storeSplit.io.tuDstKind := rename.io.tuDstKind
  storeSplit.io.staReady := Mux(storeDispatchBypass, true.B, storeDispatch.io.staReady)
  storeSplit.io.stdReady := Mux(storeDispatchBypass, true.B, storeDispatch.io.stdReady)

  val bypassedStorePayload = 0.U.asTypeOf(new StoreSplitIssuePayload(p, mapQDepth))
  storeDispatch.io.staIn := Mux(storeDispatchBypass, bypassedStorePayload, storeSplit.io.sta)
  storeDispatch.io.stdIn := Mux(storeDispatchBypass, bypassedStorePayload, storeSplit.io.std)
  storeDispatch.io.unsplitIn := Mux(storeDispatchBypass, bypassedStorePayload, storeSplit.io.unsplit)

  allocator.io.flush := io.cleanup.flush
  allocator.io.allocValid :=
    selectedAny && decRenQ.io.pushReady && gprReservationReady && decodeContextCloseSafe &&
      !selectedClosesActiveRedirect
  allocator.io.allocUsesExistingBlock := selectedUsesExistingBlock
  allocator.io.allocExistingBlockBid := selectedExistingBlockBid
  allocator.io.allocRow := commitReservationRow(selectedForQueue)
  allocator.io.allocGid := selectedForQueue.gid
  val selectedAllocTid = selectedForQueue.threadId.pad(tidWidth)(tidWidth - 1, 0)
  val selectedAllocStid = selectedForQueue.threadId.pad(stidWidth)(stidWidth - 1, 0)
  val selectedAllocPeId = selectedForQueue.peId.pad(peIdWidth)(peIdWidth - 1, 0)
  val selectedAllocBlockType = selectedForQueue.boundaryKind.asUInt.pad(blockTypeWidth)(blockTypeWidth - 1, 0)
  val markerAllocTid = marker.threadId.pad(tidWidth)(tidWidth - 1, 0)
  val markerAllocStid = markerStid
  val markerAllocPeId = marker.peId.pad(peIdWidth)(peIdWidth - 1, 0)
  val markerAllocBlockType = marker.boundaryKind.asUInt.pad(blockTypeWidth)(blockTypeWidth - 1, 0)
  allocator.io.allocTid := Mux(markerBoundary, markerAllocTid, selectedAllocTid)
  allocator.io.allocStid := Mux(markerBoundary, markerAllocStid, selectedAllocStid)
  allocator.io.allocLsId := selectedForQueue.lsid
  allocator.io.allocIsLoad := selectedForQueue.valid && selectedForQueue.isLoad
  allocator.io.allocIsStore := selectedForQueue.valid && selectedForQueue.isStore
  allocator.io.allocTSeq := zeroLocalSeq
  allocator.io.allocUSeq := zeroLocalSeq
  allocator.io.allocTUDstValid := false.B
  allocator.io.allocTUDstKind := DestinationKind.None
  allocator.io.allocIsLast := selectedForQueue.isLastInBlock
  allocator.io.allocMarkerBoundary := selectedForQueue.sob
  allocator.io.allocMarkerStop := selectedForQueue.eob
  allocator.io.allocMarkerBoundaryKind := selectedForQueue.boundaryKind
  allocator.io.allocMarkerBoundaryTarget := selectedForQueue.boundaryTarget
  allocator.io.allocPeId := Mux(markerBoundary, markerAllocPeId, selectedAllocPeId)
  allocator.io.allocBlockType := Mux(markerBoundary, markerAllocBlockType, selectedAllocBlockType)
  allocator.io.allocNeedsEngine := false.B
  allocator.io.blockAllocOnlyValid := markerLifecycle.io.blockAllocOnlyValid
  allocator.io.renameUpdateValid := rename.io.robAllocAttemptValid
  allocator.io.renameUpdateRid := queuedForRename.rid
  allocator.io.renameUpdateRow := rename.io.robAllocRow
  allocator.io.renameUpdateTSeq := rename.io.tuTSeq
  allocator.io.renameUpdateUSeq := rename.io.tuUSeq
  allocator.io.renameUpdateTUDstValid := rename.io.tuDstValid
  allocator.io.renameUpdateTUDstKind := rename.io.tuDstKind
  val markerCompletionPortValid = markerCompletionPending && !io.completeValid
  allocator.io.completeValid := io.completeValid || markerCompletionPortValid
  allocator.io.completeRobValue := Mux(io.completeValid, io.completeRobValue, markerCompletionRobValue)
  allocator.io.completeRowValid := Mux(io.completeValid, io.completeRowValid, true.B)
  allocator.io.completeRow := Mux(io.completeValid, io.completeRow, markerCompletionRow)
  allocator.io.deallocReady :=
    io.deallocReady &&
      tuRetirePath.io.sourceWindowReady &&
      markerRetireSerializer.io.sourceWindowReady &&
      !tuRetirePath.io.cleanupActive
  allocator.io.deallocHoldMask := io.deallocHoldMask
  allocator.io.statusLookupValid := io.robStatusLookupValid
  allocator.io.statusLookupRid := io.robStatusLookupRid
  allocator.io.commitTraceLookupValid := io.robCommitTraceLookupValid
  allocator.io.commitTraceLookupRid := io.robCommitTraceLookupRid
  allocator.io.commitTraceLookupSourceTraceEnable := io.robCommitTraceLookupSourceTraceEnable
  val decodeContextScalarDoneFire =
    (allocator.io.allocFire && decodeContextMarkerCloseIntent && decodeContextCloseSafe) ||
      selectedClosesActiveRedirect
  val blockScalarDoneFire = decodeContextScalarDoneFire || markerLifecycle.io.scalarDoneValid
  val blockScalarDoneBid = Mux(decodeContextScalarDoneFire, decodeContextClosingBlockBid, markerLifecycle.io.scalarDoneBid)
  blockScalarDoneSeq.io.flushValid := io.flushValid && !io.cleanup.valid
  blockScalarDoneSeq.io.inValid := blockScalarDoneFire
  blockScalarDoneSeq.io.inBid := blockScalarDoneBid
  allocator.io.blockScalarDoneValid := blockScalarDoneSeq.io.scalarDoneValid
  allocator.io.blockScalarDoneBid := blockScalarDoneSeq.io.scalarDoneBid
  allocator.io.blockScalarTrapValid := false.B
  allocator.io.blockScalarTrapCause := 0.U
  allocator.io.blockEngineDoneValid := false.B
  allocator.io.blockEngineDoneBid := 0.U
  allocator.io.blockEngineTrapValid := false.B
  allocator.io.blockEngineTrapCause := 0.U
  allocator.io.blockRetireValid := blockScalarDoneSeq.io.retireValid
  allocator.io.blockRetireBid := blockScalarDoneSeq.io.retireBid
  allocator.io.blockFlushValid := io.cleanup.blockFlushValid
  allocator.io.blockFlushBid := io.cleanup.blockFlushBid
  allocator.io.blockQueryBid := allocator.io.allocBlockBid
  val blockRenameCommitQ = withReset(reset.asBool || (io.flushValid && !io.cleanup.valid)) {
    Module(new Queue(UInt(bidWidth.W), blockRenameCommitQueueDepth))
  }
  blockRenameCommitQ.io.enq.valid := blockScalarDoneSeq.io.retireValid
  blockRenameCommitQ.io.enq.bits := blockScalarDoneSeq.io.retireBid
  assert(
    !blockScalarDoneSeq.io.retireValid || blockRenameCommitQ.io.enq.ready,
    "block rename commit queue overflow")
  val internalBlockCommitValid = blockRenameCommitQ.io.deq.valid
  val internalBlockCommitFullBid = blockRenameCommitQ.io.deq.bits
  val internalBlockCommitBid =
    FullBidRecoveryBridge.fullBidToRobId(
      internalBlockCommitFullBid,
      internalBlockCommitValid,
      p.robEntries,
      bidWidth)
  renameCommitValid := io.commitValid || internalBlockCommitValid
  renameCommitBid := Mux(io.commitValid, io.commitBid, internalBlockCommitBid)
  renameCommitBlockBid := Mux(io.commitValid, io.commitBlockBid, internalBlockCommitFullBid)
  blockRenameCommitQ.io.deq.ready := !io.commitValid && rename.io.commitAccepted

  rename.io.robSource := allocator.io.robTULinkSource
  rename.io.lsuSource := storeDispatch.io.lsuTULinkSource
  tuRetirePath.io.sources := allocator.io.deallocTURetireSource
  tuRetirePath.io.clear := false.B
  tuRetirePath.io.flush := io.cleanup.flush
  tuRetirePath.io.cleanBlockValid := false.B
  tuRetirePath.io.cleanBlockBid := zeroRobId
  tuRetirePath.io.cleanGroupValid := false.B
  tuRetirePath.io.cleanGroupBid := zeroRobId
  tuRetirePath.io.cleanGroupGid := zeroRobId
  val tuRetireCommandTerminal =
    rename.io.tuRetireAccepted || rename.io.tuRetireMiss ||
      rename.io.tuRetireReleaseMismatch || rename.io.tuRetireUnsupported
  tuRetirePath.io.commandReady := tuRetireCommandTerminal
  tuRetirePath.io.localBlockCommitReady := rename.io.tuLocalBlockCommitReady
  rename.io.tuRetireValid := tuRetirePath.io.command.valid
  rename.io.tuRetireKind := tuRetirePath.io.command.kind
  rename.io.tuRetireSeq := tuRetirePath.io.command.seq
  rename.io.tuRetireDealloc := tuRetirePath.io.command.dealloc
  rename.io.tuRetirePeId := tuRetirePath.io.command.peId
  rename.io.tuRetireStid := tuRetirePath.io.command.stid
  rename.io.tuLocalBlockCommitValid := tuRetirePath.io.localBlockCommitValid
  rename.io.tuLocalBlockCommitBid := tuRetirePath.io.localBlockCommitBid
  rename.io.tuLocalBlockCommitStid := tuRetirePath.io.localBlockCommitStid

  markerRetireSerializer.io.sources := allocator.io.deallocBlockMarkerRetireSource
  markerRetireSerializer.io.clear := false.B
  markerRetireSerializer.io.flush := io.cleanup.flush
  markerRetireSerializer.io.outReady := markerLifecycle.io.retiredMarkerReady
  markerLifecycle.io.retiredMarker := markerRetireSerializer.io.out

  val markerRenameAccepted = rename.io.accepted && queuedMarkerForRename
  val markerCompletionAccepted = markerCompletionPortValid && allocator.io.completeAccepted
  when(decRenFlush || blockLifecycleFlush) {
    markerCompletionPending := false.B
  }.elsewhen(markerCompletionAccepted) {
    markerCompletionPending := false.B
  }.elsewhen(markerRenameAccepted) {
    markerCompletionPending := true.B
    markerCompletionRobValue := queuedForRename.rid.value
    markerCompletionRow := rename.io.robAllocRow
  }

  io.selectedValid := selectedAny
  io.selectedSlot := selectedSlot
  io.selectedRobValue := allocator.io.allocRobValue
  io.selectedBlockBid := selectedBlockBid
  io.blockMarkerSkipValid := markerOnlyPacket && !mixedMarkerPacket
  io.blockMarkerMixedPacket := mixedMarkerPacket
  io.blockMarkerBoundary := markerBoundary
  io.blockMarkerStop := markerStop
  io.blockMarkerPc := marker.pc
  io.blockMarkerInsn := marker.insnRaw
  io.blockMarkerLen := marker.insnLen
  io.blockMarkerTarget := marker.boundaryTarget
  io.blockMarkerAllocReady := allocator.io.blockAllocOnlyReady
  io.blockMarkerLifecycleConflict := markerLifecycleConflict
  io.blockMarkerAllocFire := markerLifecycle.io.markerAllocFire
  io.blockMarkerAllocBid := allocator.io.blockAllocOnlyBid
  io.blockMarkerActiveValid := activeBlockValid
  io.blockMarkerActiveBid := activeBlockBid
  io.blockMarkerActiveTarget := activeBlockTarget
  io.blockMarkerStopRedirectValid := markerLifecycle.io.stopRedirectValid
  io.blockMarkerStopRedirectPc := markerLifecycle.io.stopRedirectPc
  io.decodeReady := !mixedMarkerPacket &&
    Mux(
      markerOnlyPacket,
      markerLifecycle.io.markerReady,
      decRenQ.io.pushReady && allocator.io.allocReady && gprReservationReady && decodeContextCloseSafe &&
        !selectedClosesActiveRedirect)
  io.decodeQueuePushReady := decRenQ.io.pushReady
  io.decodeAllocReady := allocator.io.allocReady
  io.decodeGprReservationReady := gprReservationReady
  io.decodeSelectedClosesActiveRedirect := selectedClosesActiveRedirect
  io.decodeSelectedNeedsGprReservation := selectedNeedsGprReservation
  io.gprReservationCount := gprReservationCount
  io.gprReservationNeed := gprReservationNeed
  io.decRenPushReady :=
    decRenQ.io.pushReady && allocator.io.allocReady && gprReservationReady && decodeContextCloseSafe &&
      !selectedClosesActiveRedirect
  io.decRenPushFire := decRenQ.io.pushFire
  io.decRenPopFire := decRenQ.io.popFire
  io.decRenValid := decRenQ.io.out.valid
  io.decRenHead := decRenQ.io.head
  io.decRenTail := decRenQ.io.tail
  io.decRenCount := decRenQ.io.count
  io.decRenEmpty := decRenQ.io.empty
  io.decRenFull := decRenQ.io.full
  io.decRenHeadPc := queuedForRename.pc
  io.decRenHeadUsesLocal := queuedForRename.valid &&
    (VecInit((0 until 3).map { idx =>
      queuedForRename.src(idx).valid &&
        ((queuedForRename.src(idx).operandClass === OperandClass.T) ||
          (queuedForRename.src(idx).operandClass === OperandClass.U))
    }).asUInt.orR ||
      (queuedForRename.dst(0).valid &&
        ((queuedForRename.dst(0).kind === DestinationKind.T) ||
          (queuedForRename.dst(0).kind === DestinationKind.U))))
  io.decRenHeadRidValid := queuedForRename.rid.valid
  io.decRenHeadRidValue := queuedForRename.rid.value
  io.selectedIsLoad := selectedIsLoad
  io.selectedIsStore := selectedIsStore
  io.selectedMemoryValid := memIds.io.memoryValid
  io.lsidAssignFire := memIds.io.assignFire
  io.selectedLsId := memIds.io.assignedLsId
  io.selectedLoadId := memIds.io.assignedLoadId
  io.selectedStoreId := memIds.io.assignedStoreId
  io.nextLsId := memIds.io.nextLsId
  io.nextLoadId := memIds.io.nextLoadId
  io.nextStoreId := memIds.io.nextStoreId
  io.storeSplitIntent := memIds.io.storeSplitIntent

  io.renamedOutValid := rename.io.outValid && !renamedOutIsMarker
  io.renamedOut := rename.io.out
  io.storeDispatchReady := storeDispatchReadyForHead
  io.storeDispatchFire := storeSplit.io.fire
  io.storeDispatchSplit := storeSplit.io.split
  io.storeDispatchBlockedBySta := !storeDispatchBypass && queuedStoreActive && !storeDispatch.io.staReady
  io.storeDispatchBlockedByStd := !storeDispatchBypass && queuedStoreSplit && !storeDispatch.io.stdReady
  io.storeSta := storeSplit.io.sta
  io.storeStd := storeSplit.io.std
  io.storeUnsplit := storeSplit.io.unsplit
  io.storeStaQueueValid := storeDispatch.io.staQueueValid
  io.storeStdQueueValid := storeDispatch.io.stdQueueValid
  io.storeStaQueue := storeDispatch.io.staQueue
  io.storeStdQueue := storeDispatch.io.stdQueue
  io.storeStaEnqueueFire := storeDispatch.io.staEnqueueFire
  io.storeStdEnqueueFire := storeDispatch.io.stdEnqueueFire
  io.storeStaDequeueFire := storeDispatch.io.staDequeueFire
  io.storeStdDequeueFire := storeDispatch.io.stdDequeueFire
  io.storeDispatchInputProtocolError := storeDispatch.io.inputProtocolError
  io.storeStaQueueCount := storeDispatch.io.staQueueCount
  io.storeStdQueueCount := storeDispatch.io.stdQueueCount
  io.storeStaQueueFull := storeDispatch.io.staQueueFull
  io.storeStdQueueFull := storeDispatch.io.stdQueueFull
  io.storeStaInsertReady := storeDispatch.io.staInsertReady
  io.storeStdInsertReady := storeDispatch.io.stdInsertReady
  io.storeStaInsertCanMerge := storeDispatch.io.staInsertCanMerge
  io.storeStdInsertCanMerge := storeDispatch.io.stdInsertCanMerge
  io.storeStaInsertCanAllocate := storeDispatch.io.staInsertCanAllocate
  io.storeStdInsertCanAllocate := storeDispatch.io.stdInsertCanAllocate
  io.storeSelectedSta := storeDispatch.io.selectedSta
  io.storeSelectedStd := storeDispatch.io.selectedStd
  io.storeBlockedByStaExec := storeDispatch.io.blockedByStaExec
  io.storeBlockedByStdExec := storeDispatch.io.blockedByStdExec
  io.storeBlockedByStaInsert := storeDispatch.io.blockedByStaInsert
  io.storeBlockedByStdInsert := storeDispatch.io.blockedByStdInsert
  io.storeStdBypassStaBlocked := storeDispatch.io.stdBypassStaBlocked
  io.storeStqInsertValid := storeDispatch.io.insertValid
  io.storeStqInsert := storeDispatch.io.insert
  io.storeStqInsertAccepted := storeDispatch.io.insertAccepted
  io.storeStqInsertAllocated := storeDispatch.io.insertAllocated
  io.storeStqInsertMerged := storeDispatch.io.insertMerged
  io.storeStqInsertConflict := storeDispatch.io.insertConflict
  io.storeStqInsertIndex := storeDispatch.io.insertIndex
  io.storeMarkCommitAccepted := storeDispatch.io.markCommitAccepted
  io.storeMarkCommitIgnored := storeDispatch.io.markCommitIgnored
  io.storeCommitFreeAccepted := storeDispatch.io.commitFreeAccepted
  io.storeCommitFreeIgnored := storeDispatch.io.commitFreeIgnored
  io.storeCommitFreeAcceptedMask := storeDispatch.io.commitFreeAcceptedMask
  io.storeCommitFreeIgnoredMask := storeDispatch.io.commitFreeIgnoredMask
  io.storeCommitFreeCount := storeDispatch.io.commitFreeCount
  io.storeStqFlushApplied := storeDispatch.io.stqFlushApplied
  io.storeStqFlushMatchMask := storeDispatch.io.stqFlushMatchMask
  io.storeStqFlushFreeMask := storeDispatch.io.stqFlushFreeMask
  io.storeStqFlushStatusBlockedMask := storeDispatch.io.stqFlushStatusBlockedMask
  io.storeStqFlushFreeCount := storeDispatch.io.stqFlushFreeCount
  io.storeLsuTULinkSource := storeDispatch.io.lsuTULinkSource
  io.storeLsuTULinkSourceMatched := storeDispatch.io.lsuTULinkSourceMatched
  io.storeLsuTULinkSourceMultipleMatch := storeDispatch.io.lsuTULinkSourceMultipleMatch
  io.storeStqRows := storeDispatch.io.stqRows
  io.storeStqOccupiedMask := storeDispatch.io.stqOccupiedMask
  io.storeStqWaitMask := storeDispatch.io.stqWaitMask
  io.storeStqCommitMask := storeDispatch.io.stqCommitMask
  io.storeStqAddrReadyMask := storeDispatch.io.stqAddrReadyMask
  io.storeStqDataReadyMask := storeDispatch.io.stqDataReadyMask
  io.storeStqResidentCount := storeDispatch.io.stqResidentCount
  io.storeStqOutstandingWaitCount := storeDispatch.io.stqOutstandingWaitCount
  io.storeStqEmpty := storeDispatch.io.stqEmpty
  io.storeStqFull := storeDispatch.io.stqFull
  io.storeStqStall := storeDispatch.io.stqStall
  val gprReservationPush = decRenQ.io.pushFire && selectedNeedsGprReservation
  val gprReservationPop = decRenQ.io.popFire && queuedNeedsGprReservation
  when(decRenFlush) {
    gprReservationCount := 0.U
  }.elsewhen(gprReservationPush && !gprReservationPop) {
    gprReservationCount := gprReservationCount + 1.U
  }.elsewhen(!gprReservationPush && gprReservationPop) {
    gprReservationCount := gprReservationCount - 1.U
  }
  io.accepted := rename.io.accepted
  io.robAllocAttemptValid := allocator.io.allocValid
  io.robAllocReady := allocator.io.allocReady
  io.robAllocFire := allocator.io.allocFire
  io.robAllocBlockedByBrob := allocator.io.allocBlockedByBrob
  io.robAllocBlockedByRob := allocator.io.allocBlockedByRob
  io.robAllocDuplicateIdentity := allocator.io.allocDuplicateIdentity
  io.robRenameUpdateAttemptValid := allocator.io.renameUpdateValid
  io.robRenameUpdateReady := allocator.io.renameUpdateReady
  io.robRenameUpdateFire := allocator.io.renameUpdateAccepted
  io.robRenameUpdateIgnored := allocator.io.renameUpdateIgnored
  io.robMarkerRowCompletePending := markerCompletionPending
  io.robMarkerRowCompleteFire := markerCompletionAccepted
  io.blockedByMaintenance := rename.io.blockedByMaintenance
  io.blockedByRename := rename.io.blockedByRename
  io.blockedByRob := rename.io.blockedByRob
  io.blockedByOutput := rename.io.blockedByOutput
  io.unsupported := rename.io.unsupported
  io.unsupportedSrcMask := rename.io.unsupportedSrcMask
  io.unsupportedDst := rename.io.unsupportedDst
  io.unsupportedOperandClass := rename.io.unsupportedOperandClass
  io.blockedByTURename := rename.io.blockedByTURename
  io.gprFreeCount := rename.io.gprFreeCount
  io.gprMapQValidCount := rename.io.gprMapQValidCount
  io.gprMapQFreeCount := rename.io.gprMapQFreeCount
  io.gprSmapLiveCount := rename.io.gprSmapLiveCount
  io.gprCmapLiveCount := rename.io.gprCmapLiveCount
  io.gprMapQLiveCount := rename.io.gprMapQLiveCount
  io.gprLivePhysCount := rename.io.gprLivePhysCount
  io.gprFreeFromLiveCount := rename.io.gprFreeFromLiveCount
  io.gprFreeListMismatchCount := rename.io.gprFreeListMismatchCount
  io.gprNextMapQValidCount := rename.io.gprNextMapQValidCount
  io.gprNextMapQLiveCount := rename.io.gprNextMapQLiveCount
  io.gprNextLivePhysCount := rename.io.gprNextLivePhysCount
  io.gprNextFreeFromLiveCount := rename.io.gprNextFreeFromLiveCount
  io.gprCommitAccepted := rename.io.commitAccepted
  io.gprCommitBlockBid := renameCommitBlockBid
  io.gprCommittedMapQCount := rename.io.gprCommittedMapQCount
  io.gprReleasedPhysCount := rename.io.gprReleasedPhysCount
  io.tuRenameReady := rename.io.tuReady
  io.tuRenameAccepted := rename.io.tuAccepted
  io.tuRenameActivePeId := activeTURenamePeId
  io.tuRenameActiveStid := activeTURenameStid
  io.tuRenameActivePeInRange := rename.io.tuActivePeInRange
  io.tuRenameActiveStidInRange := rename.io.tuActiveStidInRange
  io.tuRenameActiveBankValid := rename.io.tuActiveBankValid
  io.tuRenameTSeq := rename.io.tuTSeq
  io.tuRenameUSeq := rename.io.tuUSeq
  io.tuRenameDstValid := rename.io.tuDstValid
  io.tuRenameDstKind := rename.io.tuDstKind
  io.tuRenameNeedsTAlloc := rename.io.needsTAlloc
  io.tuRenameNeedsUAlloc := rename.io.needsUAlloc
  io.tuRenameBlockedByTAlloc := rename.io.tuBlockedByTAlloc
  io.tuRenameBlockedByUAlloc := rename.io.tuBlockedByUAlloc
  io.tuRenameSourceUnderflowMask := rename.io.tuSourceUnderflowMask
  io.tuRenameTUsedEntries := rename.io.tuTUsedEntries
  io.tuRenameUUsedEntries := rename.io.tuUUsedEntries

  io.allocBlockBid := allocator.io.allocBlockBid
  io.allocRobValue := allocator.io.allocRobValue
  io.completeAccepted := io.completeValid && allocator.io.completeAccepted
  io.completeIgnored := io.completeValid && allocator.io.completeIgnored
  io.commit := allocator.io.commit
  io.commitMemoryOrder := allocator.io.commitMemoryOrder
  io.commitValidMask := allocator.io.commitValidMask
  io.commitCount := allocator.io.commitCount
  io.commitMonitorValidMask := allocator.io.commitMonitorValidMask
  io.commitMonitorValidCount := allocator.io.commitMonitorValidCount
  io.commitSkippedSlot := allocator.io.commitSkippedSlot
  io.commitDuplicateIdentity := allocator.io.commitDuplicateIdentity
  io.commitSlotMismatch := allocator.io.commitSlotMismatch
  io.commitInvalidSideEffect := allocator.io.commitInvalidSideEffect
  io.commitContractError := allocator.io.commitContractError
  io.deallocValidMask := allocator.io.deallocValidMask
  io.deallocCount := allocator.io.deallocCount
  io.robDeallocTURetireSource := allocator.io.deallocTURetireSource
  io.robDeallocBlockMarkerRetireSource := allocator.io.deallocBlockMarkerRetireSource
  io.robMarkerRetireSourceWindowReady := markerRetireSerializer.io.sourceWindowReady
  io.robMarkerRetireSourceValidMask := markerRetireSerializer.io.sourceValidMask
  io.robMarkerRetireSourceEnqueueCount := markerRetireSerializer.io.sourceEnqueueCount
  io.robMarkerRetireSourceQueueCount := markerRetireSerializer.io.sourceQueueCount
  io.robMarkerRetireSourceQueueFull := markerRetireSerializer.io.sourceQueueFull
  io.robMarkerRetireSourceQueueEmpty := markerRetireSerializer.io.sourceQueueEmpty
  io.robMarkerRetireSourceDequeued := markerRetireSerializer.io.sourceDequeued
  io.robMarkerRetireSourcePruneCount := markerRetireSerializer.io.sourcePruneCount
  io.robMarkerRetireSourceLifecycleReady := markerLifecycle.io.retiredMarkerReady
  io.robMarkerRetireSourceLifecycleFire := markerLifecycle.io.retiredMarkerFire
  io.robMarkerRetireSourceLifecycleBoundaryFire := markerLifecycle.io.retiredMarkerBoundaryFire
  io.robMarkerRetireSourceLifecycleStopFire := markerLifecycle.io.retiredMarkerStopFire
  io.robMarkerRetireSource := markerRetireSerializer.io.out
  io.robDeallocBlockLastValid := allocator.io.deallocBlockLastValid
  io.robDeallocBlockLastBid := allocator.io.deallocBlockLastBid
  io.robDeallocBlockLastGid := allocator.io.deallocBlockLastGid
  io.robDeallocBlockLastBlockBid := allocator.io.deallocBlockLastBlockBid
  io.blockScalarDoneFire := blockScalarDoneFire
  io.blockScalarDoneBid := blockScalarDoneBid
  io.blockRetireFire := blockScalarDoneSeq.io.retireValid
  io.blockRetireBid := blockScalarDoneSeq.io.retireBid
  io.tuRetireSourceWindowReady := tuRetirePath.io.sourceWindowReady
  io.tuRetireSourceValidMask := tuRetirePath.io.sourceValidMask
  io.tuRetireSourceEnqueueCount := tuRetirePath.io.sourceEnqueueCount
  io.tuRetireSourceQueueCount := tuRetirePath.io.sourceQueueCount
  io.tuRetireSourceQueueFull := tuRetirePath.io.sourceQueueFull
  io.tuRetireSourceQueueEmpty := tuRetirePath.io.sourceQueueEmpty
  io.tuRetireSourceDequeued := tuRetirePath.io.sourceDequeued
  io.tuRetireCleanupActive := tuRetirePath.io.cleanupActive
  io.tuRetireSourcePruneCount := tuRetirePath.io.sourcePruneCount
  io.tuRetireRelationPruneTCount := tuRetirePath.io.relationPruneTCount
  io.tuRetireRelationPruneUCount := tuRetirePath.io.relationPruneUCount
  io.tuRetireCommandValid := tuRetirePath.io.command.valid
  io.tuRetireCommandKind := tuRetirePath.io.command.kind
  io.tuRetireCommandSeq := tuRetirePath.io.command.seq
  io.tuRetireCommandDealloc := tuRetirePath.io.command.dealloc
  io.tuRetireCommandPeId := tuRetirePath.io.command.peId
  io.tuRetireCommandStid := tuRetirePath.io.command.stid
  io.tuRetireCommandFire := tuRetirePath.io.commandFire
  io.tuRetireAutoCleanBlockPending := tuRetirePath.io.autoCleanBlockPending
  io.tuRetireAutoCleanBlockValid := tuRetirePath.io.autoCleanBlockValid
  io.tuRetireAutoCleanBlockBid := tuRetirePath.io.autoCleanBlockBid
  io.tuRetireLocalBlockCommitPending := tuRetirePath.io.localBlockCommitPending
  io.tuRetireLocalBlockCommitValid := tuRetirePath.io.localBlockCommitValid
  io.tuRetireLocalBlockCommitReady := rename.io.tuLocalBlockCommitReady
  io.tuRetireLocalBlockCommitBid := tuRetirePath.io.localBlockCommitBid
  io.tuRetireLocalBlockCommitStid := tuRetirePath.io.localBlockCommitStid
  io.tuRetireLocalBlockCommitFire := tuRetirePath.io.localBlockCommitFire
  io.tuRetireLocalBlockCommitAccepted := rename.io.tuLocalBlockCommitAccepted
  io.tuRetireLocalBlockCommitStidMatch := rename.io.tuLocalBlockCommitStidMatch
  io.tuRetireLocalBlockCommitBlockedByStid := rename.io.tuLocalBlockCommitBlockedByStid
  io.tuRetireLocalBlockCommitFanoutStidInRange := rename.io.tuLocalBlockCommitFanoutStidInRange
  io.tuRetireLocalBlockCommitFanoutBlockedByStidRange := rename.io.tuLocalBlockCommitFanoutBlockedByStidRange
  io.tuRetireLocalBlockCommitFanoutBlockedByBankReady := rename.io.tuLocalBlockCommitFanoutBlockedByBankReady
  io.tuRetireLocalBlockCommitFanoutTargetPeMask := rename.io.tuLocalBlockCommitFanoutTargetPeMask
  io.tuRetireLocalBlockCommitFanoutReadyPeMask := rename.io.tuLocalBlockCommitFanoutReadyPeMask
  io.tuRetireUnsupportedDst := tuRetirePath.io.unsupportedDst
  io.tuRetireRelationPreReleaseT := tuRetirePath.io.preReleaseT
  io.tuRetireRelationPreReleaseU := tuRetirePath.io.preReleaseU
  io.tuRetireRelationPressureReleaseT := tuRetirePath.io.pressureReleaseT
  io.tuRetireRelationPressureReleaseU := tuRetirePath.io.pressureReleaseU
  io.tuRetireRelationPendingMark := tuRetirePath.io.pendingMark
  io.tuRetireRelationPendingPostReleaseT := tuRetirePath.io.pendingPostReleaseT
  io.tuRetireRelationPendingPostReleaseU := tuRetirePath.io.pendingPostReleaseU
  io.tuRetireRelationTCount := tuRetirePath.io.tCount
  io.tuRetireRelationUCount := tuRetirePath.io.uCount
  io.tuRetireAccepted := rename.io.tuRetireAccepted
  io.tuRetireMiss := rename.io.tuRetireMiss
  io.tuRetireReleaseMismatch := rename.io.tuRetireReleaseMismatch
  io.tuRetireUnsupported := rename.io.tuRetireUnsupported
  io.tuRetirePeInRange := rename.io.tuRetirePeInRange
  io.tuRetireStidInRange := rename.io.tuRetireStidInRange
  io.tuRetireBankValid := rename.io.tuRetireBankValid
  io.flushApplied := allocator.io.flushApplied
  io.robTULinkSource := allocator.io.robTULinkSource
  io.robTULinkSourceMatched := allocator.io.robTULinkSourceMatched
  io.robTULinkSourceMultipleMatch := allocator.io.robTULinkSourceMultipleMatch
  io.tuCleanupPublisherFlushValid := rename.io.tuCleanupPublisherFlushValid
  io.tuCleanupPublisherFlushBaseOnBid := rename.io.tuCleanupPublisherFlushBaseOnBid
  io.tuCleanupPublisherFlushBid := rename.io.tuCleanupPublisherFlushBid
  io.tuCleanupPublisherFlushRid := rename.io.tuCleanupPublisherFlushRid
  io.tuCleanupPublisherFlushTSeq := rename.io.tuCleanupPublisherFlushTSeq
  io.tuCleanupPublisherFlushUSeq := rename.io.tuCleanupPublisherFlushUSeq
  io.tuCleanupActive := rename.io.tuCleanupActive
  io.tuCleanupBlockedBySource := rename.io.tuCleanupBlockedBySource
  io.tuCleanupFlushSourceRequired := rename.io.tuCleanupFlushSourceRequired
  io.tuCleanupFlushSourceMatched := rename.io.tuCleanupFlushSourceMatched
  io.tuCleanupFlushMissingSource := rename.io.tuCleanupFlushMissingSource
  io.tuCleanupFlushSourceMismatch := rename.io.tuCleanupFlushSourceMismatch
  io.tuCleanupSelectedFlushSource := rename.io.tuCleanupSelectedFlushSource
  io.tuCleanupRobSourceMatched := rename.io.tuCleanupRobSourceMatched
  io.tuCleanupLsuSourceMatched := rename.io.tuCleanupLsuSourceMatched
  io.tuCleanupRobSourceMismatched := rename.io.tuCleanupRobSourceMismatched
  io.tuCleanupLsuSourceMismatched := rename.io.tuCleanupLsuSourceMismatched
  io.tuCleanupMultipleSourcesMatched := rename.io.tuCleanupMultipleSourcesMatched
  io.tuCleanupSourceConflict := rename.io.tuCleanupSourceConflict
  io.tuCleanupSelectorSourceMissing := rename.io.tuCleanupSelectorSourceMissing
  io.tuCleanupSelectedFromRob := rename.io.tuCleanupSelectedFromRob
  io.tuCleanupSelectedFromLsu := rename.io.tuCleanupSelectedFromLsu
  io.tuCleanupFlushTPrevApplied := rename.io.tuCleanupFlushTPrevApplied
  io.tuCleanupFlushUPrevApplied := rename.io.tuCleanupFlushUPrevApplied
  io.empty := allocator.io.empty
  io.full := allocator.io.full
  io.size := allocator.io.size
  io.outstandingCount := allocator.io.outstandingCount
  io.commitHeadValid := allocator.io.commitHeadValid
  io.commitHeadStatus := allocator.io.commitHeadStatus
  io.commitHeadRobValue := allocator.io.commitHeadRobValue
  io.robStatusLookup := allocator.io.statusLookup
  io.robCommitTraceLookup := allocator.io.commitTraceLookup
  io.occupiedMask := allocator.io.occupiedMask
  io.completedMask := allocator.io.completedMask
  io.retiredMask := allocator.io.retiredMask
  io.blockAllocatedMask := allocator.io.blockAllocatedMask
  io.blockCompleteMask := allocator.io.blockCompleteMask
  io.blockPendingMask := allocator.io.blockPendingMask
}
