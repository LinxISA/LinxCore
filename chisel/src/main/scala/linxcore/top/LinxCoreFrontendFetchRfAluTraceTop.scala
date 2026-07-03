package linxcore.top

import chisel3._
import chisel3.util.{Cat, Fill, UIntToOH, log2Ceil}

import linxcore.backend.DecodeRenameROBPath
import linxcore.commit.{CommitTraceParams, CommitTracePort}
import linxcore.common.{CoreParams, DestinationKind, InterfaceParams, OperandClass}
import linxcore.execute.{ReducedScalarAluExecute, ReducedScalarIssueQueue, ReducedScalarRegisterFile, ReducedScalarWritebackArbiter}
import linxcore.frontend.{F4DecodeWindow, F4DenseSlotQueue, F4Slot, FrontendFetchPacketSource, ReducedBfuBodyCutArm, ReducedBfuBodyCutPredictor, ReducedBfuGeometryPredictionLatch, ReducedBfuLocalBodyWindow, ReducedBfuPendingRuntimeBodyEndCandidate, ReducedBfuPromotedRuntimeBodyEndOracle, ReducedBfuResolvedBodyEndOwner, ReducedBfuResolvedBodyEndPending, ReducedBfuResolvedBodyEndSource, ReducedBfuStaticGeometryProducer}
import linxcore.lsu.{LoadInflightStatus, LoadLookupArbiter, LoadReplayBaseDataAlign, LoadReplayDestination, LoadReplayLaunchReadiness, LoadReplayReturnConsumerReady, LoadReplayReturnDataExtract, LoadReplayReturnIexDataCandidate, LoadReplayReturnIexDrainPermit, LoadReplayReturnIexPipeInsertCandidate, LoadReplayReturnLaneCompletionCandidate, LoadReplayReturnLretEntry, LoadReplayReturnLretPayload, LoadReplayReturnLretSink, LoadReplayReturnPipeBudget, LoadReplayReturnPipePermit, LoadReplayReturnPipeSelect, LoadReplayReturnPublishControl, LoadReplayReturnPublishReady, LoadReplayReturnPublishRequest, LoadReplayReturnReadiness, LoadReplayReturnRobResolveDataCandidate, LoadReplayReturnSideEffectReady, LoadReplayReturnWakeupCandidate, LoadReplayReturnWritebackCandidate, LoadReplaySourceReturnReadiness, LoadResolveQueue, MDBConflictDetect, MDBConflictLoadEntry, MDBConflictStoreProbe, MDBQueueBus, MDBQueueFanout, MDBStoreWakeupEntry, ReducedLoadReplayCompletionDrain, ReducedLoadReplayLiqAllocPath, ReducedLoadReplayRelaunchQueue, ReducedLoadWaitReplaySlot, ReducedStoreCommitFreeOwner, ReducedStoreExecResultBridge, ReducedStoreMemoryOverlay, ReducedStoreResidentForward, ResidentStoreForwardStoreSnapshot, ResidentStoreReplayWakeup, SCBRowBank, STQCommitDrain, STQCommitDrainRequest, STQStoreType, StoreDispatchExecResult}
import linxcore.recovery.{ExecEngineType, FlushBus, FlushType, RecoveryCleanupIntent}
import linxcore.rob.{ROBEntryStatus, ROBID}

class LinxCoreFrontendFetchRfAluTraceTopIO(
    val p: InterfaceParams,
    val traceParams: CommitTraceParams,
    val decRenQueueDepth: Int = 4,
    val issueQueueDepth: Int = 4,
    val denseSlotQueueDepth: Int = 8,
    val storeDispatchQueueDepth: Int = 4,
    val storeExecBufferEntries: Int = 4,
    val storeMemoryLineEntries: Int = 64,
    val mapQDepth: Int = 32,
    val gprMapQDepth: Int = 32,
    val physRegs: Int = 64)
    extends Bundle {
  private val ptrWidth = log2Ceil(p.robEntries)
  private val sizeWidth = log2Ceil(p.robEntries + 1)
  private val decRenCountWidth = log2Ceil(decRenQueueDepth + 1)
  private val issueCountWidth = log2Ceil(issueQueueDepth + 1)
  private val denseSlotQueueCountWidth = log2Ceil(denseSlotQueueDepth + 1)
  private val denseSlotQueueSlotWidth = math.max(1, log2Ceil(p.decodeWidth))
  private val storeDispatchCountWidth = log2Ceil(storeDispatchQueueDepth + 1)
  private val storeExecBufferCountWidth = log2Ceil(storeExecBufferEntries + 1)
  private val storeStqCountWidth = log2Ceil(p.robEntries + 1)
  private val storeCommitIssueWidth = if (p.robEntries >= 4) 2 else 1
  private val storeScbRequestCount = storeCommitIssueWidth * 2
  private val storeMemoryCommitBypassRequestCount = traceParams.commitWidth * 2
  private val storeMemoryRequestCount = storeScbRequestCount + storeMemoryCommitBypassRequestCount
  private val storeScbRequestCountWidth = log2Ceil(storeScbRequestCount + 1)
  private val storeMemoryLineCountWidth = log2Ceil(storeMemoryLineEntries + 1)
  private val loadReplayRelaunchQueueCountWidth = log2Ceil(2 + 1)
  private val loadReplayLretSinkCountWidth = log2Ceil(2 + 1)
  private val mdbConflictOrdinalWidth = log2Ceil(p.robEntries * 2)
  private val gprFreeWidth = log2Ceil(physRegs + 1)
  private val gprMapQFreeWidth = log2Ceil(gprMapQDepth + 1)
  private val tuCountWidth = log2Ceil(mapQDepth + 1)

  val startValid = Input(Bool())
  val startPc = Input(UInt(p.pcWidth.W))
  val restartValid = Input(Bool())
  val restartPc = Input(UInt(p.pcWidth.W))
  val reducedBfuBodyValid = Input(Bool())
  val reducedBfuHeaderPc = Input(UInt(p.pcWidth.W))
  val reducedBfuHSizeBytes = Input(UInt(p.pcWidth.W))
  val reducedBfuBSizeBytes = Input(UInt(p.pcWidth.W))
  val frontendFlushValid = Input(Bool())
  val peId = Input(UInt(p.peIdWidth.W))
  val threadId = Input(UInt(p.threadIdWidth.W))

  val fetchReqReady = Input(Bool())
  val fetchRespValid = Input(Bool())
  val fetchRespWindow = Input(UInt(p.windowWidth.W))

  val rfInitValid = Input(Bool())
  val rfInitArchTag = Input(UInt(p.archRegWidth.W))
  val rfInitData = Input(UInt(p.immWidth.W))
  val deallocReady = Input(Bool())
  val loadLookupData = Input(UInt(p.immWidth.W))

  val fetchReqValid = Output(Bool())
  val fetchReqPc = Output(UInt(p.pcWidth.W))
  val fetchRespReady = Output(Bool())
  val sourceActive = Output(Bool())
  val sourceWaitingResponse = Output(Bool())
  val sourcePacketValid = Output(Bool())
  val sourceReqFire = Output(Bool())
  val sourceRespFire = Output(Bool())
  val sourceOutFire = Output(Bool())
  val sourceAdvanceZero = Output(Bool())
  val sourceAdvanceBytes = Output(UInt(4.W))
  val sourceCurrentPc = Output(UInt(p.pcWidth.W))
  val sourceIssuedPc = Output(UInt(p.pcWidth.W))
  val sourceNextPktUid = Output(UInt(p.uopUidWidth.W))
  val reducedBodyCutActive = Output(Bool())
  val reducedBodyCutFire = Output(Bool())
  val reducedBodyCutAdvanceBytes = Output(UInt(4.W))
  val reducedBfuStaticGeometryValid = Output(Bool())
  val reducedBfuStaticHeaderActive = Output(Bool())
  val reducedBfuStaticLearnedFire = Output(Bool())
  val reducedBfuStaticResolvedLearnedFire = Output(Bool())
  val reducedBfuResolvedBodyEndAccepted = Output(Bool())
  val reducedBfuResolvedBodyEndHeaderMismatch = Output(Bool())
  val reducedBfuResolvedBodyEndInactiveDrop = Output(Bool())
  val reducedBfuResolvedBodyEndFlushDrop = Output(Bool())
  val reducedBfuResolvedBodyEndUnderflow = Output(Bool())
  val reducedBfuResolvedBodyEndSourceRuntimeSelected = Output(Bool())
  val reducedBfuResolvedBodyEndSourceReplaySelected = Output(Bool())
  val reducedBfuResolvedBodyEndSourceRuntimeReplayComparable = Output(Bool())
  val reducedBfuResolvedBodyEndSourceRuntimeReplayMatch = Output(Bool())
  val reducedBfuResolvedBodyEndSourceRuntimeReplayMismatch = Output(Bool())
  val reducedBfuResolvedBodyEndSourceRuntimeFeedbackFire = Output(Bool())
  val reducedBfuResolvedBodyEndSourceRuntimePending = Output(Bool())
  val reducedBfuResolvedBodyEndSourceRuntimePendingConsumeFire = Output(Bool())
  val reducedBfuResolvedBodyEndSourceRuntimePendingDropMismatch = Output(Bool())
  val reducedBfuResolvedBodyEndSourceRuntimePendingCandidateComparable = Output(Bool())
  val reducedBfuResolvedBodyEndSourceRuntimePendingCandidateMatch = Output(Bool())
  val reducedBfuResolvedBodyEndSourceRuntimePendingCandidateMismatch = Output(Bool())
  val reducedBfuPendingRuntimeCandidateValid = Output(Bool())
  val reducedBfuPendingRuntimeCandidatePendingWithoutActiveHeader = Output(Bool())
  val reducedBfuPendingRuntimeCandidateActiveHeaderMismatch = Output(Bool())
  val reducedBfuPendingRuntimeCandidateReplayComparable = Output(Bool())
  val reducedBfuPendingRuntimeCandidateReplayMatch = Output(Bool())
  val reducedBfuPendingRuntimeCandidateReplayMismatch = Output(Bool())
  val reducedBfuPromotedRuntimeBodyEndOraclePending = Output(Bool())
  val reducedBfuPromotedRuntimeBodyEndOracleCaptureFire = Output(Bool())
  val reducedBfuPromotedRuntimeBodyEndOracleReplayComparable = Output(Bool())
  val reducedBfuPromotedRuntimeBodyEndOracleReplayMatch = Output(Bool())
  val reducedBfuPromotedRuntimeBodyEndOracleReplayMismatch = Output(Bool())
  val reducedBfuPromotedRuntimeBodyEndOracleOverwritePending = Output(Bool())
  val reducedBfuStaticExternalComparable = Output(Bool())
  val reducedBfuStaticExternalMatch = Output(Bool())
  val reducedBfuStaticExternalMismatch = Output(Bool())
  val reducedBfuStaticExternalHeaderMismatch = Output(Bool())
  val reducedBfuStaticExternalHSizeMismatch = Output(Bool())
  val reducedBfuStaticExternalBSizeMismatch = Output(Bool())
  val reducedBfuBodyCutArmComparable = Output(Bool())
  val reducedBfuBodyCutArmAccepted = Output(Bool())
  val reducedBfuBodyCutArmMismatch = Output(Bool())
  val reducedBfuBodyCutArmHeaderMismatch = Output(Bool())
  val reducedBfuBodyCutArmHSizeMismatch = Output(Bool())
  val reducedBfuBodyCutArmBSizeMismatch = Output(Bool())
  val reducedBfuLocalBodyWindowActive = Output(Bool())
  val reducedBfuLocalBodyWindowArmFire = Output(Bool())
  val reducedBfuLocalBodyWindowReleaseFire = Output(Bool())
  val reducedBfuLocalBodyWindowArmSlot = Output(UInt(log2Ceil(p.decodeWidth).W))

  val f4ValidMask = Output(UInt(p.decodeWidth.W))
  val f4SlotCount = Output(UInt(log2Ceil(p.decodeWidth + 1).W))
  val denseSlotQueueInFire = Output(Bool())
  val denseSlotQueueOutFire = Output(Bool())
  val denseSlotQueueInSlotCount = Output(UInt(log2Ceil(p.decodeWidth + 1).W))
  val denseSlotQueueCount = Output(UInt(denseSlotQueueCountWidth.W))
  val denseSlotQueueHeadSlot = Output(UInt(denseSlotQueueSlotWidth.W))
  val denseSlotQueueFull = Output(Bool())
  val denseSlotQueueEmpty = Output(Bool())
  val admittedMarkerDrainBarrier = Output(Bool())
  val decodeReady = Output(Bool())
  val decodeQueuePushReady = Output(Bool())
  val decodeAllocReady = Output(Bool())
  val decodeGprReservationReady = Output(Bool())
  val decodeSelectedClosesActiveRedirect = Output(Bool())
  val decodeSelectedNeedsGprReservation = Output(Bool())
  val gprReservationCount = Output(UInt(decRenCountWidth.W))
  val gprReservationNeed = Output(UInt(decRenCountWidth.W))
  val selectedValid = Output(Bool())
  val selectedRobValue = Output(UInt(ptrWidth.W))
  val selectedBlockBid = Output(UInt(p.blockBidWidth.W))
  val blockMarkerSkipFire = Output(Bool())
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
  val blockMarkerAllocBid = Output(UInt(p.blockBidWidth.W))
  val blockMarkerActiveValid = Output(Bool())
  val blockMarkerActiveBid = Output(UInt(p.blockBidWidth.W))
  val blockMarkerActiveTarget = Output(UInt(p.pcWidth.W))
  val blockMarkerStopRedirectValid = Output(Bool())
  val blockMarkerStopRedirectPc = Output(UInt(p.pcWidth.W))
  val robMarkerRetireSourceLifecycleReady = Output(Bool())
  val robMarkerRetireSourceLifecycleFire = Output(Bool())
  val robMarkerRetireSourceLifecycleBoundaryFire = Output(Bool())
  val robMarkerRetireSourceLifecycleStopFire = Output(Bool())
  val robMarkerRetireSourceValid = Output(Bool())
  val robMarkerRetireSourceBoundary = Output(Bool())
  val robMarkerRetireSourceStop = Output(Bool())
  val robMarkerRetireSourceLast = Output(Bool())
  val robMarkerRetireSourceBidValid = Output(Bool())
  val robMarkerRetireSourceBidWrap = Output(Bool())
  val robMarkerRetireSourceBidValue = Output(UInt(p.robIndexWidth.W))
  val robMarkerRetireSourceRidValid = Output(Bool())
  val robMarkerRetireSourceRidWrap = Output(Bool())
  val robMarkerRetireSourceRidValue = Output(UInt(p.robIndexWidth.W))
  val robMarkerRetireSourceStid = Output(UInt(p.threadIdWidth.W))
  val robMarkerRetireSourceBlockBidValid = Output(Bool())
  val robMarkerRetireSourceBlockBid = Output(UInt(p.blockBidWidth.W))
  val robMarkerRetireSourceBoundaryTarget = Output(UInt(p.pcWidth.W))
  val decRenPushFire = Output(Bool())
  val decRenPopFire = Output(Bool())
  val decRenCount = Output(UInt(decRenCountWidth.W))
  val decRenValid = Output(Bool())
  val decRenHeadPc = Output(UInt(p.pcWidth.W))
  val decRenHeadRidValid = Output(Bool())
  val decRenHeadRidValue = Output(UInt(p.robIndexWidth.W))
  val renamedOutValid = Output(Bool())
  val renamedAccepted = Output(Bool())
  val executeAccepted = Output(Bool())
  val executeBusy = Output(Bool())
  val executeCompleteValid = Output(Bool())
  val executeCompleteRobValue = Output(UInt(ptrWidth.W))
  val executeLoadWaitHold = Output(Bool())
  val loadLookupValid = Output(Bool())
  val loadLookupAddr = Output(UInt(p.immWidth.W))
  val loadLookupPc = Output(UInt(p.pcWidth.W))
  val loadLookupDstValid = Output(Bool())
  val loadLookupDstKind = Output(UInt(2.W))
  val loadLookupDstArchTag = Output(UInt(p.archRegWidth.W))
  val loadLookupDstRelTag = Output(UInt(p.archRegWidth.W))
  val loadLookupDstPhysTag = Output(UInt(p.physRegWidth.W))
  val loadLookupDstOldPhysTag = Output(UInt(p.physRegWidth.W))
  val loadLookupExecuteGranted = Output(Bool())
  val loadLookupReplayGranted = Output(Bool())
  val reducedStoreDispatchEnabled = Output(Bool())
  val reducedStoreExecCompleteStoreValid = Output(Bool())
  val reducedStoreExecCaptureFire = Output(Bool())
  val reducedStoreExecCaptureBlocked = Output(Bool())
  val reducedStoreExecCaptureDuplicate = Output(Bool())
  val reducedStoreExecStaMatch = Output(Bool())
  val reducedStoreExecStdMatch = Output(Bool())
  val reducedStoreExecValidMask = Output(UInt(storeExecBufferEntries.W))
  val reducedStoreExecBufferCount = Output(UInt(storeExecBufferCountWidth.W))
  val reducedStoreStaExecValid = Output(Bool())
  val reducedStoreStdExecValid = Output(Bool())
  val reducedStoreCommitStoreSeen = Output(Bool())
  val reducedStoreCommitStoreMatched = Output(Bool())
  val reducedStoreCommitStoreUnmatched = Output(Bool())
  val reducedStoreCommitMatchMask = Output(UInt(p.robEntries.W))
  val reducedStoreCommitPendingMarkMask = Output(UInt(p.robEntries.W))
  val reducedStoreCommitPendingFreeMask = Output(UInt(p.robEntries.W))
  val reducedStoreCommitPendingMarkCount = Output(UInt(storeStqCountWidth.W))
  val reducedStoreCommitPendingFreeCount = Output(UInt(storeStqCountWidth.W))
  val reducedStoreCommitMarkValid = Output(Bool())
  val reducedStoreCommitMarkIndex = Output(UInt(ptrWidth.W))
  val reducedStoreCommitMarkAccepted = Output(Bool())
  val reducedStoreCommitMarkIgnored = Output(Bool())
  val reducedStoreCommitMarkBlocked = Output(Bool())
  val reducedStoreCommitFreeValid = Output(Bool())
  val reducedStoreCommitFreeIndex = Output(UInt(ptrWidth.W))
  val reducedStoreCommitFreeAccepted = Output(Bool())
  val reducedStoreCommitFreeIgnored = Output(Bool())
  val reducedStoreCommitFreeAcceptedMask = Output(UInt(p.robEntries.W))
  val reducedStoreCommitFreeIgnoredMask = Output(UInt(p.robEntries.W))
  val reducedStoreCommitFreeCount = Output(UInt(storeStqCountWidth.W))
  val reducedStoreCommitFreeBlocked = Output(Bool())
  val reducedStoreDrainEnqueueAccepted = Output(Bool())
  val reducedStoreDrainEnqueueDuplicate = Output(Bool())
  val reducedStoreDrainIssueValidMask = Output(UInt(storeCommitIssueWidth.W))
  val reducedStoreDrainIssueCount = Output(UInt(log2Ceil(storeCommitIssueWidth + 1).W))
  val reducedStoreDrainEarlyFreeMask = Output(UInt(p.robEntries.W))
  val reducedStoreDrainQueueCount = Output(UInt(storeStqCountWidth.W))
  val reducedStoreDrainEmpty = Output(Bool())
  val reducedStoreDrainOrderError = Output(Bool())
  val reducedStoreScbReadyForDrain = Output(Bool())
  val reducedStoreScbAcceptedMask = Output(UInt(storeScbRequestCount.W))
  val reducedStoreScbStalledMask = Output(UInt(storeScbRequestCount.W))
  val reducedStoreScbCommitFreeMaskValid = Output(Bool())
  val reducedStoreScbCommitFreeMask = Output(UInt(p.robEntries.W))
  val reducedStoreScbCommitFreeCount = Output(UInt(storeScbRequestCountWidth.W))
  val reducedStoreScbValidMask = Output(UInt(p.robEntries.W))
  val reducedStoreScbEntryCount = Output(UInt(storeStqCountWidth.W))
  val reducedStoreMemoryValidMask = Output(UInt(storeMemoryLineEntries.W))
  val reducedStoreMemoryLineCount = Output(UInt(storeMemoryLineCountWidth.W))
  val reducedStoreMemoryLoadForwardMask = Output(UInt(8.W))
  val reducedStoreMemoryStoreDroppedMask = Output(UInt(storeMemoryRequestCount.W))
  val reducedStoreResidentForwardMask = Output(UInt(8.W))
  val reducedStoreResidentWaitMask = Output(UInt(8.W))
  val reducedStoreResidentEligibleMask = Output(UInt(p.robEntries.W))
  val reducedStoreResidentReadyForward = Output(Bool())
  val reducedStoreResidentWaitBlocked = Output(Bool())
  val reducedStoreResidentWaitStoreValid = Output(Bool())
  val reducedStoreResidentWaitStoreIndex = Output(UInt(ptrWidth.W))
  val reducedStoreResidentWaitStoreBidValid = Output(Bool())
  val reducedStoreResidentWaitStoreBidWrap = Output(Bool())
  val reducedStoreResidentWaitStoreBidValue = Output(UInt(ptrWidth.W))
  val reducedStoreResidentWaitStoreLsIdValid = Output(Bool())
  val reducedStoreResidentWaitStoreLsIdWrap = Output(Bool())
  val reducedStoreResidentWaitStoreLsIdValue = Output(UInt(ptrWidth.W))
  val reducedStoreResidentWaitStorePc = Output(UInt(p.pcWidth.W))
  val reducedStoreResidentLoadCrossesLine = Output(Bool())
  val reducedStoreResidentReplayWakeValid = Output(Bool())
  val reducedStoreResidentReplayWakeSelected = Output(Bool())
  val reducedStoreResidentReplayWakeIdentityMatch = Output(Bool())
  val reducedStoreResidentReplayWakeReady = Output(Bool())
  val reducedStoreResidentReplayWakeCrossesLine = Output(Bool())
  val reducedStoreResidentReplayWakeLineAddr = Output(UInt(p.immWidth.W))
  val reducedStoreResidentReplayWakeByteMask = Output(UInt(64.W))
  val reducedLoadWaitReplayActive = Output(Bool())
  val reducedLoadWaitReplayCaptureAccepted = Output(Bool())
  val reducedLoadWaitReplayClearValid = Output(Bool())
  val reducedLoadWaitReplayStoredWaitValid = Output(Bool())
  val reducedLoadWaitReplayStoredWaitPc = Output(UInt(p.pcWidth.W))
  val reducedLoadWaitReplayRelaunchValid = Output(Bool())
  val reducedLoadWaitReplayRelaunchPc = Output(UInt(p.pcWidth.W))
  val reducedLoadWaitReplayRelaunchAddr = Output(UInt(p.immWidth.W))
  val reducedLoadWaitReplayRelaunchSize = Output(UInt(7.W))
  val reducedLoadWaitReplayRelaunchDstValid = Output(Bool())
  val reducedLoadWaitReplayRelaunchDstKind = Output(UInt(2.W))
  val reducedLoadWaitReplayRelaunchDstArchTag = Output(UInt(p.archRegWidth.W))
  val reducedLoadWaitReplayRelaunchDstRelTag = Output(UInt(p.archRegWidth.W))
  val reducedLoadWaitReplayRelaunchDstPhysTag = Output(UInt(p.physRegWidth.W))
  val reducedLoadWaitReplayRelaunchDstOldPhysTag = Output(UInt(p.physRegWidth.W))
  val reducedLoadWaitReplayRelaunchBidValid = Output(Bool())
  val reducedLoadWaitReplayRelaunchBidWrap = Output(Bool())
  val reducedLoadWaitReplayRelaunchBidValue = Output(UInt(ptrWidth.W))
  val reducedLoadWaitReplayRelaunchGidValid = Output(Bool())
  val reducedLoadWaitReplayRelaunchGidWrap = Output(Bool())
  val reducedLoadWaitReplayRelaunchGidValue = Output(UInt(ptrWidth.W))
  val reducedLoadWaitReplayRelaunchRidValid = Output(Bool())
  val reducedLoadWaitReplayRelaunchRidWrap = Output(Bool())
  val reducedLoadWaitReplayRelaunchRidValue = Output(UInt(ptrWidth.W))
  val reducedLoadWaitReplayRelaunchLsIdValid = Output(Bool())
  val reducedLoadWaitReplayRelaunchLsIdWrap = Output(Bool())
  val reducedLoadWaitReplayRelaunchLsIdValue = Output(UInt(ptrWidth.W))
  val reducedLoadWaitReplayRelaunchYoungestStoreIdValid = Output(Bool())
  val reducedLoadWaitReplayRelaunchYoungestStoreIdWrap = Output(Bool())
  val reducedLoadWaitReplayRelaunchYoungestStoreIdValue = Output(UInt(ptrWidth.W))
  val reducedLoadWaitReplayRelaunchYoungestStoreLsIdValid = Output(Bool())
  val reducedLoadWaitReplayRelaunchYoungestStoreLsIdWrap = Output(Bool())
  val reducedLoadWaitReplayRelaunchYoungestStoreLsIdValue = Output(UInt(ptrWidth.W))
  val reducedLoadReplayQueueEnqueueReady = Output(Bool())
  val reducedLoadReplayQueueEnqueueAccepted = Output(Bool())
  val reducedLoadReplayQueueEnqueueDropped = Output(Bool())
  val reducedLoadReplayQueueOutValid = Output(Bool())
  val reducedLoadReplayQueueOutFire = Output(Bool())
  val reducedLoadReplayQueuePending = Output(Bool())
  val reducedLoadReplayQueueFull = Output(Bool())
  val reducedLoadReplayQueueCount = Output(UInt(loadReplayRelaunchQueueCountWidth.W))
  val reducedLoadReplayQueueOutPc = Output(UInt(p.pcWidth.W))
  val reducedLoadReplayQueueOutAddr = Output(UInt(p.immWidth.W))
  val reducedLoadReplayQueueOutSize = Output(UInt(7.W))
  val reducedLoadReplayQueueOutReturnSignExtend = Output(Bool())
  val reducedLoadReplayQueueOutDstValid = Output(Bool())
  val reducedLoadReplayQueueOutDstKind = Output(UInt(2.W))
  val reducedLoadReplayQueueOutDstArchTag = Output(UInt(p.archRegWidth.W))
  val reducedLoadReplayQueueOutDstRelTag = Output(UInt(p.archRegWidth.W))
  val reducedLoadReplayQueueOutDstPhysTag = Output(UInt(p.physRegWidth.W))
  val reducedLoadReplayQueueOutDstOldPhysTag = Output(UInt(p.physRegWidth.W))
  val reducedLoadReplayQueueOutBidValid = Output(Bool())
  val reducedLoadReplayQueueOutBidWrap = Output(Bool())
  val reducedLoadReplayQueueOutBidValue = Output(UInt(ptrWidth.W))
  val reducedLoadReplayQueueOutGidValid = Output(Bool())
  val reducedLoadReplayQueueOutGidWrap = Output(Bool())
  val reducedLoadReplayQueueOutGidValue = Output(UInt(ptrWidth.W))
  val reducedLoadReplayQueueOutRidValid = Output(Bool())
  val reducedLoadReplayQueueOutRidWrap = Output(Bool())
  val reducedLoadReplayQueueOutRidValue = Output(UInt(ptrWidth.W))
  val reducedLoadReplayQueueOutLsIdValid = Output(Bool())
  val reducedLoadReplayQueueOutLsIdWrap = Output(Bool())
  val reducedLoadReplayQueueOutLsIdValue = Output(UInt(ptrWidth.W))
  val reducedLoadReplayQueueOutYoungestStoreIdValid = Output(Bool())
  val reducedLoadReplayQueueOutYoungestStoreIdWrap = Output(Bool())
  val reducedLoadReplayQueueOutYoungestStoreIdValue = Output(UInt(ptrWidth.W))
  val reducedLoadReplayQueueOutYoungestStoreLsIdValid = Output(Bool())
  val reducedLoadReplayQueueOutYoungestStoreLsIdWrap = Output(Bool())
  val reducedLoadReplayQueueOutYoungestStoreLsIdValue = Output(UInt(ptrWidth.W))
  val reducedLoadReplayDrainConsumeReady = Output(Bool())
  val reducedLoadReplayDrainMatchValid = Output(Bool())
  val reducedLoadReplayDrainMismatch = Output(Bool())
  val reducedLoadReplayDrainPcMismatch = Output(Bool())
  val reducedLoadReplayDrainAddrMismatch = Output(Bool())
  val reducedLoadReplayDrainSizeMismatch = Output(Bool())
  val reducedLoadReplayDrainBidMismatch = Output(Bool())
  val reducedLoadReplayDrainGidMismatch = Output(Bool())
  val reducedLoadReplayDrainRidMismatch = Output(Bool())
  val reducedLoadReplayDrainLsIdMismatch = Output(Bool())
  val reducedLoadReplayLiqAllocEnabled = Output(Bool())
  val reducedLoadReplayLiqAllocConsumeReady = Output(Bool())
  val reducedLoadReplayLiqAllocCandidateUsable = Output(Bool())
  val reducedLoadReplayLiqAllocBlockedByAlloc = Output(Bool())
  val reducedLoadReplayLiqAllocValid = Output(Bool())
  val reducedLoadReplayLiqAllocReady = Output(Bool())
  val reducedLoadReplayLiqAllocAccepted = Output(Bool())
  val reducedLoadReplayLiqAllocIndex = Output(UInt(ptrWidth.W))
  val reducedLoadReplayLiqAllocLoadIdValid = Output(Bool())
  val reducedLoadReplayLiqAllocLoadIdWrap = Output(Bool())
  val reducedLoadReplayLiqAllocLoadIdValue = Output(UInt(ptrWidth.W))
  val reducedLoadReplayLiqOccupiedMask = Output(UInt(p.robEntries.W))
  val reducedLoadReplayLiqWaitMask = Output(UInt(p.robEntries.W))
  val reducedLoadReplayLiqLaunchWaitMask = Output(UInt(p.robEntries.W))
  val reducedLoadReplayLiqLaunchWaitStoreBlockedMask = Output(UInt(p.robEntries.W))
  val reducedLoadReplayLiqLaunchTileBlockedMask = Output(UInt(p.robEntries.W))
  val reducedLoadReplayLiqLaunchUnblockedWaitMask = Output(UInt(p.robEntries.W))
  val reducedLoadReplayLiqLaunchRequestCompleteMask = Output(UInt(p.robEntries.W))
  val reducedLoadReplayLiqLaunchDataHitMask = Output(UInt(p.robEntries.W))
  val reducedLoadReplayLiqLaunchCandidateMask = Output(UInt(p.robEntries.W))
  val reducedLoadReplayLiqLaunchMask = Output(UInt(p.robEntries.W))
  val reducedLoadReplayLiqLaunchValid = Output(Bool())
  val reducedLoadReplayLiqLaunchIndex = Output(UInt(ptrWidth.W))
  val reducedLoadReplayLiqLaunchDriveValid = Output(Bool())
  val reducedLoadReplayLiqLaunchReady = Output(Bool())
  val reducedLoadReplayLiqLaunchAccepted = Output(Bool())
  val reducedLoadReplayLiqLaunchCandidateCount = Output(UInt(storeStqCountWidth.W))
  val reducedLoadReplayLiqLaunchSelectedLoadIdValid = Output(Bool())
  val reducedLoadReplayLiqLaunchSelectedLoadIdWrap = Output(Bool())
  val reducedLoadReplayLiqLaunchSelectedLoadIdValue = Output(UInt(ptrWidth.W))
  val reducedLoadReplayLiqLaunchSelectedBidValid = Output(Bool())
  val reducedLoadReplayLiqLaunchSelectedBidWrap = Output(Bool())
  val reducedLoadReplayLiqLaunchSelectedBidValue = Output(UInt(ptrWidth.W))
  val reducedLoadReplayLiqLaunchSelectedGidValid = Output(Bool())
  val reducedLoadReplayLiqLaunchSelectedGidWrap = Output(Bool())
  val reducedLoadReplayLiqLaunchSelectedGidValue = Output(UInt(ptrWidth.W))
  val reducedLoadReplayLiqLaunchSelectedRidValid = Output(Bool())
  val reducedLoadReplayLiqLaunchSelectedRidWrap = Output(Bool())
  val reducedLoadReplayLiqLaunchSelectedRidValue = Output(UInt(ptrWidth.W))
  val reducedLoadReplayLiqLaunchSelectedLoadLsIdValid = Output(Bool())
  val reducedLoadReplayLiqLaunchSelectedLoadLsIdWrap = Output(Bool())
  val reducedLoadReplayLiqLaunchSelectedLoadLsIdValue = Output(UInt(ptrWidth.W))
  val reducedLoadReplayLiqLaunchSelectedPc = Output(UInt(p.pcWidth.W))
  val reducedLoadReplayLiqLaunchSelectedAddr = Output(UInt(p.immWidth.W))
  val reducedLoadReplayLiqLaunchSelectedSize = Output(UInt(7.W))
  val reducedLoadReplayLiqLaunchSelectedReturnSignExtend = Output(Bool())
  val reducedLoadReplayLiqLaunchSelectedDstValid = Output(Bool())
  val reducedLoadReplayLiqLaunchSelectedDstKind = Output(UInt(2.W))
  val reducedLoadReplayLiqLaunchSelectedDstArchTag = Output(UInt(p.archRegWidth.W))
  val reducedLoadReplayLiqLaunchSelectedDstRelTag = Output(UInt(p.archRegWidth.W))
  val reducedLoadReplayLiqLaunchSelectedDstPhysTag = Output(UInt(p.physRegWidth.W))
  val reducedLoadReplayLiqLaunchSelectedDstOldPhysTag = Output(UInt(p.physRegWidth.W))
  val reducedLoadReplayLiqLaunchSelectedRequestByteMask = Output(UInt(64.W))
  val reducedLoadReplayLiqLaunchSelectedSpecWakeup = Output(Bool())
  val reducedLoadReplayLiqLaunchSelectedStackValid = Output(Bool())
  val reducedLoadReplayLiqBaseLookupValid = Output(Bool())
  val reducedLoadReplayLiqBaseLookupCrossesLine = Output(Bool())
  val reducedLoadReplayLiqBaseLookupPc = Output(UInt(p.pcWidth.W))
  val reducedLoadReplayLiqBaseLookupAddr = Output(UInt(p.immWidth.W))
  val reducedLoadReplayLiqBaseLookupSize = Output(UInt(7.W))
  val reducedLoadReplayLiqBaseLookupRequestByteMask = Output(UInt(64.W))
  val reducedLoadReplayLiqBaseLineValidMask = Output(UInt(64.W))
  val reducedLoadReplayLiqBaseDataReturned = Output(Bool())
  val reducedLoadReplayLiqBaseLookupGranted = Output(Bool())
  val reducedLoadReplayLiqBaseLookupBlockedByExecute = Output(Bool())
  val reducedLoadReplayLiqLaunchReadinessCandidateValid = Output(Bool())
  val reducedLoadReplayLiqLaunchReadinessBaseDataReady = Output(Bool())
  val reducedLoadReplayLiqLaunchReadinessSourcesReturned = Output(Bool())
  val reducedLoadReplayLiqLaunchReadinessReady = Output(Bool())
  val reducedLoadReplayLiqLaunchReadinessEnable = Output(Bool())
  val reducedLoadReplayLiqLaunchReadinessBlockedByDisabled = Output(Bool())
  val reducedLoadReplayLiqLaunchReadinessBlockedByNoCandidate = Output(Bool())
  val reducedLoadReplayLiqLaunchReadinessBlockedByBaseLookup = Output(Bool())
  val reducedLoadReplayLiqLaunchReadinessBlockedByBaseData = Output(Bool())
  val reducedLoadReplayLiqLaunchReadinessBlockedByScb = Output(Bool())
  val reducedLoadReplayLiqLaunchReadinessBlockedByReturn = Output(Bool())
  val reducedLoadReplayLiqSourceReturnCandidateValid = Output(Bool())
  val reducedLoadReplayLiqSourceReturnStoreSnapshotReady = Output(Bool())
  val reducedLoadReplayLiqSourceReturnStoreSourceReturned = Output(Bool())
  val reducedLoadReplayLiqSourceReturnScbSourceReturned = Output(Bool())
  val reducedLoadReplayLiqSourceReturnSourceReturned = Output(Bool())
  val reducedLoadReplayLiqSourceReturnBlockedByDisabled = Output(Bool())
  val reducedLoadReplayLiqSourceReturnBlockedByNoCandidate = Output(Bool())
  val reducedLoadReplayLiqSourceReturnBlockedByBaseData = Output(Bool())
  val reducedLoadReplayLiqSourceReturnBlockedByStoreSnapshot = Output(Bool())
  val reducedLoadReplayLiqSourceReturnBlockedByScb = Output(Bool())
  val reducedLoadReplayLiqReturnConsumerCandidateValid = Output(Bool())
  val reducedLoadReplayLiqReturnConsumerLretSinkReady = Output(Bool())
  val reducedLoadReplayLiqReturnConsumerWakeupSinkReady = Output(Bool())
  val reducedLoadReplayLiqReturnConsumerWakeupRequired = Output(Bool())
  val reducedLoadReplayLiqReturnConsumerReady = Output(Bool())
  val reducedLoadReplayLiqReturnConsumerBlockedByDisabled = Output(Bool())
  val reducedLoadReplayLiqReturnConsumerBlockedByNoCandidate = Output(Bool())
  val reducedLoadReplayLiqReturnConsumerBlockedBySources = Output(Bool())
  val reducedLoadReplayLiqReturnConsumerBlockedByLretSink = Output(Bool())
  val reducedLoadReplayLiqReturnConsumerBlockedByWakeupSink = Output(Bool())
  val reducedLoadReplayLiqReturnPipeBudgetCandidateValid = Output(Bool())
  val reducedLoadReplayLiqReturnPipeBudgetEnable = Output(Bool())
  val reducedLoadReplayLiqReturnPipeBudgetConsumerReady = Output(Bool())
  val reducedLoadReplayLiqReturnPipeBudgetAvailable = Output(Bool())
  val reducedLoadReplayLiqReturnPipeBudgetBlockedByDisabled = Output(Bool())
  val reducedLoadReplayLiqReturnPipeBudgetBlockedByNoCandidate = Output(Bool())
  val reducedLoadReplayLiqReturnPipeBudgetBlockedBySources = Output(Bool())
  val reducedLoadReplayLiqReturnPipeBudgetBlockedByBudgetDisabled = Output(Bool())
  val reducedLoadReplayLiqReturnPipeBudgetBlockedByConsumer = Output(Bool())
  val reducedLoadReplayLiqReturnPipePermitCandidateValid = Output(Bool())
  val reducedLoadReplayLiqReturnPipePermitPipeBudgetAvailable = Output(Bool())
  val reducedLoadReplayLiqReturnPipePermitMask = Output(UInt(1.W))
  val reducedLoadReplayLiqReturnPipePermitValid = Output(Bool())
  val reducedLoadReplayLiqReturnPipePermitBlockedByDisabled = Output(Bool())
  val reducedLoadReplayLiqReturnPipePermitBlockedByNoCandidate = Output(Bool())
  val reducedLoadReplayLiqReturnPipePermitBlockedBySources = Output(Bool())
  val reducedLoadReplayLiqReturnPipePermitBlockedByPipeBudget = Output(Bool())
  val reducedLoadReplayLiqReturnPipeAvailableMask = Output(UInt(1.W))
  val reducedLoadReplayLiqReturnPipeCandidateValid = Output(Bool())
  val reducedLoadReplayLiqReturnPipeAvailable = Output(Bool())
  val reducedLoadReplayLiqReturnPipeSelectedPipeIndex = Output(UInt(1.W))
  val reducedLoadReplayLiqReturnPipeBlockedByDisabled = Output(Bool())
  val reducedLoadReplayLiqReturnPipeBlockedByNoCandidate = Output(Bool())
  val reducedLoadReplayLiqReturnPipeBlockedBySources = Output(Bool())
  val reducedLoadReplayLiqReturnPipeBlockedByNoPipe = Output(Bool())
  val reducedLoadReplayLiqReturnReadinessCandidateValid = Output(Bool())
  val reducedLoadReplayLiqReturnReadinessReady = Output(Bool())
  val reducedLoadReplayLiqReturnReadinessSelectedPipeIndex = Output(UInt(1.W))
  val reducedLoadReplayLiqReturnReadinessBlockedByDisabled = Output(Bool())
  val reducedLoadReplayLiqReturnReadinessBlockedByNoCandidate = Output(Bool())
  val reducedLoadReplayLiqReturnReadinessBlockedBySources = Output(Bool())
  val reducedLoadReplayLiqReturnReadinessBlockedByReturnPipe = Output(Bool())
  val reducedLoadReplayLiqReturnDataCandidateValid = Output(Bool())
  val reducedLoadReplayLiqReturnDataRequestByteMask = Output(UInt(64.W))
  val reducedLoadReplayLiqReturnDataBytesComplete = Output(Bool())
  val reducedLoadReplayLiqReturnDataCrossLine = Output(Bool())
  val reducedLoadReplayLiqReturnDataSizeSupported = Output(Bool())
  val reducedLoadReplayLiqReturnDataRawData = Output(UInt(p.immWidth.W))
  val reducedLoadReplayLiqReturnData = Output(UInt(p.immWidth.W))
  val reducedLoadReplayLiqReturnDataValid = Output(Bool())
  val reducedLoadReplayLiqReturnDataBlockedByDisabled = Output(Bool())
  val reducedLoadReplayLiqReturnDataBlockedByNoCandidate = Output(Bool())
  val reducedLoadReplayLiqReturnDataBlockedByZeroSize = Output(Bool())
  val reducedLoadReplayLiqReturnDataBlockedByUnsupportedSize = Output(Bool())
  val reducedLoadReplayLiqReturnDataBlockedByCrossLine = Output(Bool())
  val reducedLoadReplayLiqReturnDataBlockedByIncompleteBytes = Output(Bool())
  val reducedLoadReplayLiqReturnPublishCandidateValid = Output(Bool())
  val reducedLoadReplayLiqReturnPublishDataReady = Output(Bool())
  val reducedLoadReplayLiqReturnPublishReady = Output(Bool())
  val reducedLoadReplayLiqReturnPublishBlockedByDisabled = Output(Bool())
  val reducedLoadReplayLiqReturnPublishBlockedByNoCandidate = Output(Bool())
  val reducedLoadReplayLiqReturnPublishBlockedByData = Output(Bool())
  val reducedLoadReplayLiqReturnPublishBlockedByConsumer = Output(Bool())
  val reducedLoadReplayLiqLretPayloadCandidateValid = Output(Bool())
  val reducedLoadReplayLiqLretPayloadValid = Output(Bool())
  val reducedLoadReplayLiqLretPayloadBidValid = Output(Bool())
  val reducedLoadReplayLiqLretPayloadBidWrap = Output(Bool())
  val reducedLoadReplayLiqLretPayloadBidValue = Output(UInt(ptrWidth.W))
  val reducedLoadReplayLiqLretPayloadGidValid = Output(Bool())
  val reducedLoadReplayLiqLretPayloadGidWrap = Output(Bool())
  val reducedLoadReplayLiqLretPayloadGidValue = Output(UInt(ptrWidth.W))
  val reducedLoadReplayLiqLretPayloadRidValid = Output(Bool())
  val reducedLoadReplayLiqLretPayloadRidWrap = Output(Bool())
  val reducedLoadReplayLiqLretPayloadRidValue = Output(UInt(ptrWidth.W))
  val reducedLoadReplayLiqLretPayloadLoadLsIdValid = Output(Bool())
  val reducedLoadReplayLiqLretPayloadLoadLsIdWrap = Output(Bool())
  val reducedLoadReplayLiqLretPayloadLoadLsIdValue = Output(UInt(ptrWidth.W))
  val reducedLoadReplayLiqLretPayloadPc = Output(UInt(p.pcWidth.W))
  val reducedLoadReplayLiqLretPayloadAddr = Output(UInt(p.immWidth.W))
  val reducedLoadReplayLiqLretPayloadSize = Output(UInt(7.W))
  val reducedLoadReplayLiqLretPayloadDstValid = Output(Bool())
  val reducedLoadReplayLiqLretPayloadDstKind = Output(UInt(2.W))
  val reducedLoadReplayLiqLretPayloadDstArchTag = Output(UInt(p.archRegWidth.W))
  val reducedLoadReplayLiqLretPayloadDstRelTag = Output(UInt(p.archRegWidth.W))
  val reducedLoadReplayLiqLretPayloadDstPhysTag = Output(UInt(p.physRegWidth.W))
  val reducedLoadReplayLiqLretPayloadDstOldPhysTag = Output(UInt(p.physRegWidth.W))
  val reducedLoadReplayLiqLretPayloadData = Output(UInt(p.immWidth.W))
  val reducedLoadReplayLiqLretPayloadPipeIndex = Output(UInt(1.W))
  val reducedLoadReplayLiqLretPayloadSpecWakeup = Output(Bool())
  val reducedLoadReplayLiqLretPayloadStackValid = Output(Bool())
  val reducedLoadReplayLiqLretPayloadWakeupRequired = Output(Bool())
  val reducedLoadReplayLiqLretPayloadBlockedByDisabled = Output(Bool())
  val reducedLoadReplayLiqLretPayloadBlockedByNoCandidate = Output(Bool())
  val reducedLoadReplayLiqLretPayloadBlockedByData = Output(Bool())
  val reducedLoadReplayLiqLretSinkEnqueueReady = Output(Bool())
  val reducedLoadReplayLiqLretSinkEnqueueAccepted = Output(Bool())
  val reducedLoadReplayLiqLretSinkEnqueueDropped = Output(Bool())
  val reducedLoadReplayLiqLretSinkDrainValid = Output(Bool())
  val reducedLoadReplayLiqLretSinkDrainFire = Output(Bool())
  val reducedLoadReplayLiqLretSinkPending = Output(Bool())
  val reducedLoadReplayLiqLretSinkFull = Output(Bool())
  val reducedLoadReplayLiqLretSinkEmpty = Output(Bool())
  val reducedLoadReplayLiqLretSinkCount = Output(UInt(loadReplayLretSinkCountWidth.W))
  val reducedLoadReplayLiqLretSinkBlockedByFlush = Output(Bool())
  val reducedLoadReplayLiqLretSinkBlockedByNoPayload = Output(Bool())
  val reducedLoadReplayLiqLretSinkBlockedByFull = Output(Bool())
  val reducedLoadReplayLiqLretSinkBlockedByDrain = Output(Bool())
  val reducedLoadReplayLiqLretDrainPermitEnable = Output(Bool())
  val reducedLoadReplayLiqLretDrainPermitPipeOccupiedMask = Output(UInt(1.W))
  val reducedLoadReplayLiqLretDrainPermitPipeFreeMask = Output(UInt(1.W))
  val reducedLoadReplayLiqLretDrainPermitAnyPipeFree = Output(Bool())
  val reducedLoadReplayLiqLretDrainPermitSelectedPipeIndex = Output(UInt(1.W))
  val reducedLoadReplayLiqLretDrainPermitReady = Output(Bool())
  val reducedLoadReplayLiqLretDrainPermitBlockedByDisabled = Output(Bool())
  val reducedLoadReplayLiqLretDrainPermitBlockedByFlush = Output(Bool())
  val reducedLoadReplayLiqLretDrainPermitBlockedByNoEntry = Output(Bool())
  val reducedLoadReplayLiqLretDrainPermitBlockedByPipeFull = Output(Bool())
  val reducedLoadReplayLiqLretIexDataEnable = Output(Bool())
  val reducedLoadReplayLiqLretIexDataRobRowValid = Output(Bool())
  val reducedLoadReplayLiqLretIexDataRobRowNeedFlush = Output(Bool())
  val reducedLoadReplayLiqLretIexDataCandidateValid = Output(Bool())
  val reducedLoadReplayLiqLretIexDataWouldDrain = Output(Bool())
  val reducedLoadReplayLiqLretIexDataSetMemDataValid = Output(Bool())
  val reducedLoadReplayLiqLretIexDataBlockedByDisabled = Output(Bool())
  val reducedLoadReplayLiqLretIexDataBlockedByFlush = Output(Bool())
  val reducedLoadReplayLiqLretIexDataBlockedByNoEntry = Output(Bool())
  val reducedLoadReplayLiqLretIexDataBlockedByInvalidEntry = Output(Bool())
  val reducedLoadReplayLiqLretIexDataBlockedByDrain = Output(Bool())
  val reducedLoadReplayLiqLretIexDataBlockedByRobMissing = Output(Bool())
  val reducedLoadReplayLiqLretIexDataBlockedByNeedFlush = Output(Bool())
  val reducedLoadReplayLiqLretRobResolveCandidateValid = Output(Bool())
  val reducedLoadReplayLiqLretRobResolveValid = Output(Bool())
  val reducedLoadReplayLiqLretRobResolveReadyForPipeInsert = Output(Bool())
  val reducedLoadReplayLiqLretRobResolveMarkAllDestinationsDataValid = Output(Bool())
  val reducedLoadReplayLiqLretRobResolveMarkDestinationDataValid = Output(Bool())
  val reducedLoadReplayLiqLretRobResolveRetLaneIncrement = Output(Bool())
  val reducedLoadReplayLiqLretRobResolveData = Output(UInt(p.immWidth.W))
  val reducedLoadReplayLiqLretRobResolveDstPhysTag = Output(UInt(p.physRegWidth.W))
  val reducedLoadReplayLiqLretRobResolveBlockedByDisabled = Output(Bool())
  val reducedLoadReplayLiqLretRobResolveBlockedByFlush = Output(Bool())
  val reducedLoadReplayLiqLretRobResolveBlockedByNoSetMemData = Output(Bool())
  val reducedLoadReplayLiqLretRobResolveBlockedByUnsupportedMultiLane = Output(Bool())
  val reducedLoadReplayLiqLretRobResolveBlockedByInvalidRid = Output(Bool())
  val reducedLoadReplayLiqLretRobResolveBlockedByNoDestination = Output(Bool())
  val reducedLoadReplayLiqLretLaneCompletionCandidateValid = Output(Bool())
  val reducedLoadReplayLiqLretLaneCompletionCompleteValid = Output(Bool())
  val reducedLoadReplayLiqLretLaneCompletionReadyForPipeInsert = Output(Bool())
  val reducedLoadReplayLiqLretLaneCompletionRetLaneAfterResolve = Output(UInt(9.W))
  val reducedLoadReplayLiqLretLaneCompletionRequiresAllLanes = Output(Bool())
  val reducedLoadReplayLiqLretLaneCompletionBlockedByDisabled = Output(Bool())
  val reducedLoadReplayLiqLretLaneCompletionBlockedByFlush = Output(Bool())
  val reducedLoadReplayLiqLretLaneCompletionBlockedByNoResolve = Output(Bool())
  val reducedLoadReplayLiqLretLaneCompletionBlockedByZeroReturnedLanes = Output(Bool())
  val reducedLoadReplayLiqLretLaneCompletionBlockedByInvalidRealReqCnt = Output(Bool())
  val reducedLoadReplayLiqLretLaneCompletionBlockedByScalarLoadPairIncomplete = Output(Bool())
  val reducedLoadReplayLiqLretLaneCompletionBlockedByVectorMemIncomplete = Output(Bool())
  val reducedLoadReplayLiqLretIexPipeInsertCandidateValid = Output(Bool())
  val reducedLoadReplayLiqLretIexPipeInsertValid = Output(Bool())
  val reducedLoadReplayLiqLretIexPipeInsertIsLoadReturn = Output(Bool())
  val reducedLoadReplayLiqLretIexPipeInsertPipeIndex = Output(UInt(1.W))
  val reducedLoadReplayLiqLretIexPipeInsertLoadToUsePipeIndex = Output(UInt(1.W))
  val reducedLoadReplayLiqLretIexPipeInsertWakeupRequired = Output(Bool())
  val reducedLoadReplayLiqLretIexPipeInsertBlockedByDisabled = Output(Bool())
  val reducedLoadReplayLiqLretIexPipeInsertBlockedByFlush = Output(Bool())
  val reducedLoadReplayLiqLretIexPipeInsertBlockedByNoSetMemData = Output(Bool())
  val reducedLoadReplayLiqLretIexPipeInsertBlockedByNoPipe = Output(Bool())
  val reducedLoadReplayLiqLretIexPipeInsertBlockedByInvalidRid = Output(Bool())
  val reducedLoadReplayLiqWritebackCandidateValid = Output(Bool())
  val reducedLoadReplayLiqWritebackValid = Output(Bool())
  val reducedLoadReplayLiqWritebackTag = Output(UInt(p.physRegWidth.W))
  val reducedLoadReplayLiqWritebackData = Output(UInt(p.immWidth.W))
  val reducedLoadReplayLiqWritebackIgnoredNoDestination = Output(Bool())
  val reducedLoadReplayLiqWritebackIgnoredNonGprDestination = Output(Bool())
  val reducedLoadReplayLiqWritebackBlockedByDisabled = Output(Bool())
  val reducedLoadReplayLiqWritebackArbiterReplayEnabled = Output(Bool())
  val reducedLoadReplayLiqWritebackArbiterSelectedExecute = Output(Bool())
  val reducedLoadReplayLiqWritebackArbiterSelectedReplay = Output(Bool())
  val reducedLoadReplayLiqWritebackArbiterReplayBlockedByDisabled = Output(Bool())
  val reducedLoadReplayLiqWritebackArbiterReplayBlockedByExecute = Output(Bool())
  val reducedLoadReplayLiqSideEffectCandidateValid = Output(Bool())
  val reducedLoadReplayLiqSideEffectLretReady = Output(Bool())
  val reducedLoadReplayLiqSideEffectWritebackReady = Output(Bool())
  val reducedLoadReplayLiqSideEffectWakeupReady = Output(Bool())
  val reducedLoadReplayLiqSideEffectReady = Output(Bool())
  val reducedLoadReplayLiqSideEffectBlockedByDisabled = Output(Bool())
  val reducedLoadReplayLiqSideEffectBlockedByNoPayload = Output(Bool())
  val reducedLoadReplayLiqSideEffectBlockedByLret = Output(Bool())
  val reducedLoadReplayLiqSideEffectBlockedByWriteback = Output(Bool())
  val reducedLoadReplayLiqSideEffectBlockedByWakeup = Output(Bool())
  val reducedLoadReplayLiqPublishControlCandidateValid = Output(Bool())
  val reducedLoadReplayLiqPublishControlLiveEnable = Output(Bool())
  val reducedLoadReplayLiqPublishControlArmed = Output(Bool())
  val reducedLoadReplayLiqPublishControlFire = Output(Bool())
  val reducedLoadReplayLiqPublishControlBlockedByDisabled = Output(Bool())
  val reducedLoadReplayLiqPublishControlBlockedByNoPayload = Output(Bool())
  val reducedLoadReplayLiqPublishControlBlockedByPublish = Output(Bool())
  val reducedLoadReplayLiqPublishControlBlockedBySideEffects = Output(Bool())
  val reducedLoadReplayLiqPublishControlBlockedByLiveDisabled = Output(Bool())
  val reducedLoadReplayLiqPublishRequestValid = Output(Bool())
  val reducedLoadReplayLiqPublishRequestLret = Output(Bool())
  val reducedLoadReplayLiqPublishRequestWriteback = Output(Bool())
  val reducedLoadReplayLiqPublishRequestWakeup = Output(Bool())
  val reducedLoadReplayLiqPublishRequestMask = Output(UInt(3.W))
  val reducedLoadReplayLiqPublishRequestBlockedByNoFire = Output(Bool())
  val reducedLoadReplayLiqPublishRequestInvalidFireWithoutPayload = Output(Bool())
  val reducedLoadReplayLiqWakeupCandidateValid = Output(Bool())
  val reducedLoadReplayLiqWakeupRequired = Output(Bool())
  val reducedLoadReplayLiqWakeupValid = Output(Bool())
  val reducedLoadReplayLiqWakeupKind = Output(UInt(2.W))
  val reducedLoadReplayLiqWakeupTag = Output(UInt(p.physRegWidth.W))
  val reducedLoadReplayLiqWakeupReducedGprValid = Output(Bool())
  val reducedLoadReplayLiqWakeupNonGpr = Output(Bool())
  val reducedLoadReplayLiqWakeupSuppressedNotRequired = Output(Bool())
  val reducedLoadReplayLiqWakeupIgnoredNoDestination = Output(Bool())
  val reducedLoadReplayLiqWakeupBlockedByDisabled = Output(Bool())
  val reducedLoadReplayLiqRepickMask = Output(UInt(p.robEntries.W))
  val reducedLoadReplayLiqMissMask = Output(UInt(p.robEntries.W))
  val reducedLoadReplayLiqResolvedMask = Output(UInt(p.robEntries.W))
  val reducedLoadReplayLiqE4UpdateValid = Output(Bool())
  val reducedLoadReplayLiqE4UpdateIndex = Output(UInt(ptrWidth.W))
  val reducedLoadReplayLiqE4MissKind = Output(UInt(3.W))
  val reducedLoadReplayLiqE4WakeupValid = Output(Bool())
  val reducedLoadReplayLiqLhqRecordValid = Output(Bool())
  val reducedLoadReplayLiqResidentCount = Output(UInt(storeStqCountWidth.W))
  val reducedLoadReplayLiqEmpty = Output(Bool())
  val reducedLoadReplayLiqFull = Output(Bool())
  val reducedLoadReplayResolveQueuePushReady = Output(Bool())
  val reducedLoadReplayResolveQueuePushAccepted = Output(Bool())
  val reducedLoadReplayResolveQueueClearPending = Output(Bool())
  val reducedLoadReplayResolveQueueClearAccepted = Output(Bool())
  val reducedLoadReplayResolveQueueClearIndex = Output(UInt(ptrWidth.W))
  val reducedLoadReplayResolveQueueRetireValid = Output(Bool())
  val reducedLoadReplayResolveQueueRetireIsLoadStore = Output(Bool())
  val reducedLoadReplayResolveQueueRetireIsLoad = Output(Bool())
  val reducedLoadReplayResolveQueueRetireIsStore = Output(Bool())
  val reducedLoadReplayResolveQueueRetireBidValid = Output(Bool())
  val reducedLoadReplayResolveQueueRetireBidWrap = Output(Bool())
  val reducedLoadReplayResolveQueueRetireBidValue = Output(UInt(ptrWidth.W))
  val reducedLoadReplayResolveQueueRetireRidValid = Output(Bool())
  val reducedLoadReplayResolveQueueRetireRidWrap = Output(Bool())
  val reducedLoadReplayResolveQueueRetireRidValue = Output(UInt(ptrWidth.W))
  val reducedLoadReplayResolveQueueRetireLsIdValid = Output(Bool())
  val reducedLoadReplayResolveQueueRetireLsIdWrap = Output(Bool())
  val reducedLoadReplayResolveQueueRetireLsIdValue = Output(UInt(ptrWidth.W))
  val reducedLoadReplayResolveQueuePreciseFlushValid = Output(Bool())
  val reducedLoadReplayResolveQueuePreciseFlushBidValid = Output(Bool())
  val reducedLoadReplayResolveQueuePreciseFlushBidWrap = Output(Bool())
  val reducedLoadReplayResolveQueuePreciseFlushBidValue = Output(UInt(ptrWidth.W))
  val reducedLoadReplayResolveQueuePreciseFlushRidValid = Output(Bool())
  val reducedLoadReplayResolveQueuePreciseFlushRidWrap = Output(Bool())
  val reducedLoadReplayResolveQueuePreciseFlushRidValue = Output(UInt(ptrWidth.W))
  val reducedLoadReplayResolveQueuePreciseFlushLsIdValid = Output(Bool())
  val reducedLoadReplayResolveQueuePreciseFlushLsIdWrap = Output(Bool())
  val reducedLoadReplayResolveQueuePreciseFlushLsIdValue = Output(UInt(ptrWidth.W))
  val reducedLoadReplayResolveQueueFlushPruneMask = Output(UInt(p.robEntries.W))
  val reducedLoadReplayResolveQueueFlushPruneCount = Output(UInt(storeStqCountWidth.W))
  val reducedLoadReplayResolveQueueRetireMask = Output(UInt(p.robEntries.W))
  val reducedLoadReplayResolveQueueRetireCount = Output(UInt(storeStqCountWidth.W))
  val reducedLoadReplayResolveQueueValidMask = Output(UInt(p.robEntries.W))
  val reducedLoadReplayResolveQueueCount = Output(UInt(storeStqCountWidth.W))
  val reducedLoadReplayResolveQueueEmpty = Output(Bool())
  val reducedLoadReplayResolveQueueFull = Output(Bool())
  val reducedLoadReplayResolveQueueHeadValid = Output(Bool())
  val reducedLoadReplayResolveQueueHeadPeId = Output(UInt(p.peIdWidth.W))
  val reducedLoadReplayResolveQueueHeadStid = Output(UInt(p.threadIdWidth.W))
  val reducedLoadReplayResolveQueueHeadTid = Output(UInt(p.threadIdWidth.W))
  val reducedLoadReplayResolveQueueHeadPc = Output(UInt(p.pcWidth.W))
  val reducedLoadReplayResolveQueueHeadAddr = Output(UInt(p.immWidth.W))
  val reducedLoadReplayResolveQueueHeadSize = Output(UInt(7.W))
  val reducedLoadReplayResolveQueueHeadBidValid = Output(Bool())
  val reducedLoadReplayResolveQueueHeadBidWrap = Output(Bool())
  val reducedLoadReplayResolveQueueHeadBidValue = Output(UInt(ptrWidth.W))
  val reducedLoadReplayResolveQueueHeadGidValid = Output(Bool())
  val reducedLoadReplayResolveQueueHeadGidWrap = Output(Bool())
  val reducedLoadReplayResolveQueueHeadGidValue = Output(UInt(ptrWidth.W))
  val reducedLoadReplayResolveQueueHeadRidValid = Output(Bool())
  val reducedLoadReplayResolveQueueHeadRidWrap = Output(Bool())
  val reducedLoadReplayResolveQueueHeadRidValue = Output(UInt(ptrWidth.W))
  val reducedLoadReplayResolveQueueHeadLsIdValid = Output(Bool())
  val reducedLoadReplayResolveQueueHeadLsIdWrap = Output(Bool())
  val reducedLoadReplayResolveQueueHeadLsIdValue = Output(UInt(ptrWidth.W))
  val reducedMdbConflictStoreValid = Output(Bool())
  val reducedMdbConflictActiveCandidateMask = Output(UInt(p.robEntries.W))
  val reducedMdbConflictResolveCandidateMask = Output(UInt(p.robEntries.W))
  val reducedMdbConflictWaitStoreMask = Output(UInt(p.robEntries.W))
  val reducedMdbConflictWaitStoreCount = Output(UInt(storeStqCountWidth.W))
  val reducedMdbConflictValid = Output(Bool())
  val reducedMdbConflictFromResolveQueue = Output(Bool())
  val reducedMdbConflictActiveIndex = Output(UInt(ptrWidth.W))
  val reducedMdbConflictResolveIndex = Output(UInt(ptrWidth.W))
  val reducedMdbConflictOrdinal = Output(UInt(mdbConflictOrdinalWidth.W))
  val reducedMdbConflictInnerFlush = Output(Bool())
  val reducedMdbConflictNukeFlush = Output(Bool())
  val reducedMdbConflictLoadBidValid = Output(Bool())
  val reducedMdbConflictLoadBidWrap = Output(Bool())
  val reducedMdbConflictLoadBidValue = Output(UInt(ptrWidth.W))
  val reducedMdbConflictLoadLsIdValid = Output(Bool())
  val reducedMdbConflictLoadLsIdWrap = Output(Bool())
  val reducedMdbConflictLoadLsIdValue = Output(UInt(ptrWidth.W))
  val reducedMdbConflictStoreBidValid = Output(Bool())
  val reducedMdbConflictStoreBidWrap = Output(Bool())
  val reducedMdbConflictStoreBidValue = Output(UInt(ptrWidth.W))
  val reducedMdbConflictStoreLsIdValid = Output(Bool())
  val reducedMdbConflictStoreLsIdWrap = Output(Bool())
  val reducedMdbConflictStoreLsIdValue = Output(UInt(ptrWidth.W))
  val reducedMdbFanoutLookupValid = Output(Bool())
  val reducedMdbFanoutLookupReady = Output(Bool())
  val reducedMdbFanoutLookupAccepted = Output(Bool())
  val reducedMdbFanoutLookupProcessed = Output(Bool())
  val reducedMdbFanoutDeleteValid = Output(Bool())
  val reducedMdbFanoutDeleteReady = Output(Bool())
  val reducedMdbFanoutDeleteAccepted = Output(Bool())
  val reducedMdbFanoutDeleteProcessed = Output(Bool())
  val reducedMdbFanoutPhaseStalledByFanout = Output(Bool())
  val reducedMdbFanoutLuOutValid = Output(Bool())
  val reducedMdbFanoutLuOutHit = Output(Bool())
  val reducedMdbFanoutLuOutStoreBidValid = Output(Bool())
  val reducedMdbFanoutLuOutStoreBidWrap = Output(Bool())
  val reducedMdbFanoutLuOutStoreBidValue = Output(UInt(ptrWidth.W))
  val reducedMdbFanoutSuOutValid = Output(Bool())
  val reducedMdbFanoutSuOutHit = Output(Bool())
  val reducedMdbFanoutSuOutStoreBidValid = Output(Bool())
  val reducedMdbFanoutSuOutStoreBidWrap = Output(Bool())
  val reducedMdbFanoutSuOutStoreBidValue = Output(UInt(ptrWidth.W))
  val reducedMdbFanoutRecordValid = Output(Bool())
  val reducedMdbFanoutRecordReady = Output(Bool())
  val reducedMdbFanoutRecordAccepted = Output(Bool())
  val reducedMdbFanoutRecordProcessed = Output(Bool())
  val reducedMdbFanoutBmdbReportValid = Output(Bool())
  val reducedMdbFanoutBmdbLoadBidValid = Output(Bool())
  val reducedMdbFanoutBmdbLoadBidWrap = Output(Bool())
  val reducedMdbFanoutBmdbLoadBidValue = Output(UInt(ptrWidth.W))
  val reducedMdbFanoutBmdbStoreBidValid = Output(Bool())
  val reducedMdbFanoutBmdbStoreBidWrap = Output(Bool())
  val reducedMdbFanoutBmdbStoreBidValue = Output(UInt(ptrWidth.W))
  val reducedMdbFanoutBmdbStoreStid = Output(UInt(p.threadIdWidth.W))
  val reducedMdbFanoutDeleteMatched = Output(Bool())
  val reducedMdbFanoutDeleteReleased = Output(Bool())
  val reducedMdbFanoutDeleteDroppedBelowStall = Output(Bool())
  val reducedMdbFanoutRecordOverflow = Output(Bool())
  val reducedMdbFanoutRecordOrderIllegal = Output(Bool())
  val reducedMdbFanoutSsitValidMask = Output(UInt(p.robEntries.W))
  val reducedMdbFanoutSuMatchedStore = Output(Bool())
  val reducedMdbFanoutSuStorePending = Output(Bool())
  val reducedMdbFanoutSuWakeupValid = Output(Bool())
  val reducedMdbFanoutSuWakeupIndex = Output(UInt(ptrWidth.W))
  val reducedLoadWaitReplaySlotPc = Output(UInt(p.pcWidth.W))
  val reducedLoadWaitReplaySlotAddr = Output(UInt(p.immWidth.W))
  val reducedLoadWaitReplayRelaunchReturnSignExtend = Output(Bool())
  val storeDispatchReady = Output(Bool())
  val storeDispatchFire = Output(Bool())
  val storeDispatchSplit = Output(Bool())
  val storeStaQueueValid = Output(Bool())
  val storeStdQueueValid = Output(Bool())
  val storeStaEnqueueFire = Output(Bool())
  val storeStdEnqueueFire = Output(Bool())
  val storeStaDequeueFire = Output(Bool())
  val storeStdDequeueFire = Output(Bool())
  val storeDispatchInputProtocolError = Output(Bool())
  val storeStaQueueCount = Output(UInt(storeDispatchCountWidth.W))
  val storeStdQueueCount = Output(UInt(storeDispatchCountWidth.W))
  val storeStaInsertReady = Output(Bool())
  val storeStdInsertReady = Output(Bool())
  val storeSelectedSta = Output(Bool())
  val storeSelectedStd = Output(Bool())
  val storeBlockedByStaExec = Output(Bool())
  val storeBlockedByStdExec = Output(Bool())
  val storeStqInsertValid = Output(Bool())
  val storeStqInsertAccepted = Output(Bool())
  val storeStqInsertAllocated = Output(Bool())
  val storeStqInsertMerged = Output(Bool())
  val storeStqInsertConflict = Output(Bool())
  val storeStqInsertIndex = Output(UInt(ptrWidth.W))
  val storeStqOccupiedMask = Output(UInt(p.robEntries.W))
  val storeStqWaitMask = Output(UInt(p.robEntries.W))
  val storeStqCommitMask = Output(UInt(p.robEntries.W))
  val storeStqResidentCount = Output(UInt(storeStqCountWidth.W))
  val storeStqOutstandingWaitCount = Output(UInt(storeStqCountWidth.W))
  val storeStqEmpty = Output(Bool())
  val storeStqFull = Output(Bool())
  val storeStqStall = Output(Bool())
  val executeUnsupported = Output(Bool())
  val executeUnsupportedOpcode = Output(UInt(p.opcodeWidth.W))
  val robAllocFire = Output(Bool())
  val robRenameUpdateAttemptValid = Output(Bool())
  val robRenameUpdateReady = Output(Bool())
  val robRenameUpdateFire = Output(Bool())
  val robRenameUpdateIgnored = Output(Bool())
  val completeAccepted = Output(Bool())
  val completeIgnored = Output(Bool())

  val rfReadReadyMask = Output(UInt(3.W))
  val rfAllReadReady = Output(Bool())
  val rfReadyMask = Output(UInt(physRegs.W))
  val rfWriteValid = Output(Bool())
  val rfWriteTag = Output(UInt(p.physRegWidth.W))
  val rfWriteData = Output(UInt(p.immWidth.W))
  val executeCompleteSrcPhysValidMask = Output(UInt(3.W))
  val executeCompleteSrcPhysTag = Output(Vec(3, UInt(p.physRegWidth.W)))
  val executeCompletePc = Output(UInt(p.pcWidth.W))
  val executeCompleteInsn = Output(UInt(p.insnWidth.W))
  val executeCompleteWbReg = Output(UInt(traceParams.regWidth.W))
  val rfStateError = Output(Bool())
  val issueQueueEnqueueFire = Output(Bool())
  val issueQueuePickFire = Output(Bool())
  val issueQueueIssueFire = Output(Bool())
  val issueQueueCancelFire = Output(Bool())
  val issueQueueReleaseFire = Output(Bool())
  val issueQueueCount = Output(UInt(issueCountWidth.W))
  val issueQueueIssuedCount = Output(UInt(issueCountWidth.W))
  val issueQueueNotIssuedCount = Output(UInt(issueCountWidth.W))
  val issueQueueHeadValid = Output(Bool())
  val issueQueueHeadIssued = Output(Bool())
  val issueQueueHeadPc = Output(UInt(p.pcWidth.W))
  val issueQueueHeadOpcode = Output(UInt(p.opcodeWidth.W))
  val issueQueueHeadSrcValidMask = Output(UInt(3.W))
  val issueQueueHeadSrcClass = Output(Vec(3, OperandClass()))
  val issueQueueHeadSrcPhysTag = Output(Vec(3, UInt(p.physRegWidth.W)))
  val issueQueueHeadSrcRelTag = Output(Vec(3, UInt(p.archRegWidth.W)))
  val issueQueueSourceReadyMask = Output(UInt(3.W))
  val issueQueueAllSourcesReady = Output(Bool())
  val issueQueueSelectedValid = Output(Bool())
  val issueQueueSelectedIndex = Output(UInt(log2Ceil(issueQueueDepth).W))
  val issueQueueSelectedReadReady = Output(Bool())
  val issueQueueI1Valid = Output(Bool())
  val issueQueueI2Valid = Output(Bool())
  val issueQueueStageBusy = Output(Bool())
  val issueQueueBlockedBySource = Output(Bool())
  val issueQueueBlockedByRead = Output(Bool())
  val issueQueueBlockedByOutput = Output(Bool())
  val issueQueueBlockedByIssued = Output(Bool())
  val localTReadyMask = Output(UInt(4.W))
  val localUReadyMask = Output(UInt(4.W))
  val localTPendingCount = Output(UInt(issueCountWidth.W))
  val localUPendingCount = Output(UInt(issueCountWidth.W))
  val localIncomingUsesLocal = Output(Bool())
  val localIncomingBlocked = Output(Bool())
  val decodeBlockedByRename = Output(Bool())
  val decodeBlockedByRob = Output(Bool())
  val decodeBlockedByOutput = Output(Bool())
  val decodeBlockedByTURename = Output(Bool())
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
  val gprCommitBlockBid = Output(UInt(p.blockBidWidth.W))
  val gprCommittedMapQCount = Output(UInt(gprMapQFreeWidth.W))
  val gprReleasedPhysCount = Output(UInt(gprFreeWidth.W))
  val tuRenameSourceUnderflowMask = Output(UInt(3.W))
  val tuRenameActiveBankValid = Output(Bool())
  val tuRenameBlockedByTAlloc = Output(Bool())
  val tuRenameBlockedByUAlloc = Output(Bool())
  val tuRenameTUsedEntries = Output(UInt(tuCountWidth.W))
  val tuRenameUUsedEntries = Output(UInt(tuCountWidth.W))
  val tuRetireCommandValid = Output(Bool())
  val tuRetireCommandFire = Output(Bool())
  val tuRetireLocalBlockCommitPending = Output(Bool())
  val tuRetireLocalBlockCommitValid = Output(Bool())
  val tuRetireLocalBlockCommitReady = Output(Bool())
  val tuRetireLocalBlockCommitFire = Output(Bool())
  val tuRetireAccepted = Output(Bool())
  val tuRetireMiss = Output(Bool())
  val tuRetireReleaseMismatch = Output(Bool())
  val tuRetireUnsupported = Output(Bool())

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
  val robDeallocBlockLastValid = Output(Bool())
  val robDeallocBlockLastBlockBid = Output(UInt(p.blockBidWidth.W))
  val blockScalarDoneFire = Output(Bool())
  val blockScalarDoneBid = Output(UInt(p.blockBidWidth.W))
  val blockRetireFire = Output(Bool())
  val blockRetireBid = Output(UInt(p.blockBidWidth.W))
  val empty = Output(Bool())
  val full = Output(Bool())
  val size = Output(UInt(sizeWidth.W))
  val outstandingCount = Output(UInt(sizeWidth.W))
  val commitHeadValid = Output(Bool())
  val commitHeadStatus = Output(ROBEntryStatus())
  val commitHeadRobValue = Output(UInt(ptrWidth.W))
  val occupiedMask = Output(UInt(p.robEntries.W))
  val completedMask = Output(UInt(p.robEntries.W))
  val retiredMask = Output(UInt(p.robEntries.W))
  val blockAllocatedMask = Output(UInt(p.robEntries.W))
  val blockCompleteMask = Output(UInt(p.robEntries.W))
  val blockPendingMask = Output(UInt(p.robEntries.W))
  val idle = Output(Bool())
}

class LinxCoreFrontendFetchRfAluTraceTop(
    val coreParams: CoreParams = CoreParams(),
    val decRenQueueDepth: Int = 4,
    val issueQueueDepth: Int = 4,
    val denseSlotQueueDepth: Int = 8,
    val storeDispatchQueueDepth: Int = 4,
    val storeExecBufferEntries: Int = 4,
    val mapQDepth: Int = 32,
    val gprMapQDepth: Int = 32,
    val archRegs: Int = 24,
    val physRegs: Int = 64,
    val skipBlockMarkers: Boolean = true,
    val useMarkerDecodeContext: Boolean = false,
    val useReducedStoreDispatchStq: Boolean = false,
    val useReducedLoadReplayLiqAlloc: Boolean = false)
    extends Module {
  require(physRegs > 0 && (physRegs & (physRegs - 1)) == 0, "physical register count must be a power of two")
  private val reducedStoreMemoryLineEntries = 64
  private val reducedLoadReplayRelaunchQueueDepth = 2
  private val p = LinxCoreFrontendFetchRfAluTraceTop.interfaceParamsFor(
    coreParams,
    physRegWidth = log2Ceil(physRegs)
  )
  private val traceParams = LinxCoreFrontendFetchRfAluTraceTop.traceParamsFor(p)
  val io = IO(new LinxCoreFrontendFetchRfAluTraceTopIO(
    p = p,
    traceParams = traceParams,
    decRenQueueDepth = decRenQueueDepth,
    issueQueueDepth = issueQueueDepth,
    denseSlotQueueDepth = denseSlotQueueDepth,
    storeDispatchQueueDepth = storeDispatchQueueDepth,
    storeExecBufferEntries = storeExecBufferEntries,
    storeMemoryLineEntries = reducedStoreMemoryLineEntries,
    mapQDepth = mapQDepth,
    gprMapQDepth = gprMapQDepth,
    physRegs = physRegs
  ))

  val source = Module(new FrontendFetchPacketSource(p))
  val f4 = Module(new F4DecodeWindow(p))
  val denseSlots = Module(new F4DenseSlotQueue(p, depth = denseSlotQueueDepth))
  val path = Module(new DecodeRenameROBPath(
    p = p,
    traceParams = traceParams,
    decRenQueueDepth = decRenQueueDepth,
    storeDispatchQueueDepth = storeDispatchQueueDepth,
    physRegs = physRegs,
    mapQDepth = mapQDepth,
    gprMapQDepth = gprMapQDepth,
    useMarkerDecodeContext = useMarkerDecodeContext,
    skipBlockMarkers = skipBlockMarkers,
    reducedStoreDispatchBypass = !useReducedStoreDispatchStq
  ))
  val rf = Module(new ReducedScalarRegisterFile(p, archRegs = archRegs, physRegs = physRegs))
  val rfWritebackArbiter = Module(new ReducedScalarWritebackArbiter(
    dataWidth = p.immWidth,
    physRegWidth = p.physRegWidth
  ))
  val issue = Module(new ReducedScalarIssueQueue(p, depth = issueQueueDepth))
  val execute = Module(new ReducedScalarAluExecute(p, traceParams))
  val storeExecBridge = Module(new ReducedStoreExecResultBridge(
    p = p,
    traceParams = traceParams,
    bufferEntries = storeExecBufferEntries,
    mapQDepth = mapQDepth,
    peIdWidth = p.peIdWidth,
    stidWidth = p.threadIdWidth,
    tidWidth = p.threadIdWidth
  ))
  val storeCommitOwner = Module(new ReducedStoreCommitFreeOwner(
    entries = p.robEntries,
    traceParams = traceParams,
    peIdWidth = p.peIdWidth,
    stidWidth = p.threadIdWidth,
    tidWidth = p.threadIdWidth,
    mapQDepth = mapQDepth
  ))
  private val reducedStoreCommitIssueWidth = if (p.robEntries >= 4) 2 else 1
  private val reducedStoreScbRequestCount = reducedStoreCommitIssueWidth * 2
  private val reducedStoreCommitBypassRequestCount = traceParams.commitWidth * 2
  private val reducedStoreMemoryRequestCount = reducedStoreScbRequestCount + reducedStoreCommitBypassRequestCount
  val reducedStoreCommitDrain = Module(new STQCommitDrain(
    entries = p.robEntries,
    queueEntries = p.robEntries,
    issueWidth = reducedStoreCommitIssueWidth,
    peIdWidth = p.peIdWidth,
    stidWidth = p.threadIdWidth,
    tidWidth = p.threadIdWidth,
    mapQDepth = mapQDepth
  ))
  val reducedStoreScb = Module(new SCBRowBank(
    stqEntries = p.robEntries,
    scbEntries = p.robEntries,
    requestCount = reducedStoreScbRequestCount,
    responseBufferDepth = 4
  ))
  val reducedStoreMemoryOverlay = Module(new ReducedStoreMemoryOverlay(
    stqEntries = p.robEntries,
    requestCount = reducedStoreMemoryRequestCount,
    lineEntries = reducedStoreMemoryLineEntries
  ))
  val reducedStoreResidentForward = Module(new ReducedStoreResidentForward(
    entries = p.robEntries,
    peIdWidth = p.peIdWidth,
    stidWidth = p.threadIdWidth,
    tidWidth = p.threadIdWidth,
    mapQDepth = mapQDepth
  ))
  val reducedReplayLiqStoreSnapshot = Module(new ResidentStoreForwardStoreSnapshot(
    entries = p.robEntries,
    peIdWidth = p.peIdWidth,
    stidWidth = p.threadIdWidth,
    tidWidth = p.threadIdWidth,
    mapQDepth = mapQDepth
  ))
  val reducedReplayLiqBaseDataAlign = Module(new LoadReplayBaseDataAlign(
    addrWidth = p.immWidth,
    dataWidth = p.immWidth
  ))
  val loadLookupArbiter = Module(new LoadLookupArbiter(
    addrWidth = p.immWidth,
    pcWidth = p.pcWidth
  ))
  val reducedReplayLiqSourceReturnReadiness = Module(new LoadReplaySourceReturnReadiness)
  val reducedReplayLiqReturnConsumerReady = Module(new LoadReplayReturnConsumerReady)
  val reducedReplayLiqReturnPipeBudget = Module(new LoadReplayReturnPipeBudget)
  val reducedReplayLiqReturnPipePermit = Module(new LoadReplayReturnPipePermit(returnPipeCount = 1))
  val reducedReplayLiqReturnPipeSelect = Module(new LoadReplayReturnPipeSelect(returnPipeCount = 1))
  val reducedReplayLiqReturnReadiness = Module(new LoadReplayReturnReadiness(returnPipeCount = 1))
  val reducedReplayLiqReturnDataExtract = Module(new LoadReplayReturnDataExtract(
    addrWidth = p.immWidth,
    dataWidth = p.immWidth
  ))
  val reducedReplayLiqReturnPublishReady = Module(new LoadReplayReturnPublishReady)
  val reducedReplayLiqReturnLretPayload = Module(new LoadReplayReturnLretPayload(
    idEntries = p.robEntries,
    addrWidth = p.immWidth,
    pcWidth = p.pcWidth,
    dataWidth = p.immWidth,
    returnPipeCount = 1,
    archRegWidth = p.archRegWidth,
    physRegWidth = p.physRegWidth
  ))
  val reducedReplayLiqReturnLretSink = Module(new LoadReplayReturnLretSink(
    idEntries = p.robEntries,
    depth = 2,
    addrWidth = p.immWidth,
    pcWidth = p.pcWidth,
    dataWidth = p.immWidth,
    returnPipeCount = 1,
    archRegWidth = p.archRegWidth,
    physRegWidth = p.physRegWidth
  ))
  val reducedReplayLiqReturnIexDrainPermit = Module(new LoadReplayReturnIexDrainPermit(returnPipeCount = 1))
  val reducedReplayLiqReturnIexDataCandidate = Module(new LoadReplayReturnIexDataCandidate(
    idEntries = p.robEntries,
    addrWidth = p.immWidth,
    pcWidth = p.pcWidth,
    dataWidth = p.immWidth,
    returnPipeCount = 1,
    archRegWidth = p.archRegWidth,
    physRegWidth = p.physRegWidth
  ))
  val reducedReplayLiqReturnRobResolveDataCandidate = Module(new LoadReplayReturnRobResolveDataCandidate(
    idEntries = p.robEntries,
    dataWidth = p.immWidth,
    archRegWidth = p.archRegWidth,
    physRegWidth = p.physRegWidth
  ))
  val reducedReplayLiqReturnLaneCompletionCandidate = Module(new LoadReplayReturnLaneCompletionCandidate(
    countWidth = 8
  ))
  val reducedReplayLiqReturnIexPipeInsertCandidate = Module(new LoadReplayReturnIexPipeInsertCandidate(
    idEntries = p.robEntries,
    addrWidth = p.immWidth,
    pcWidth = p.pcWidth,
    dataWidth = p.immWidth,
    returnPipeCount = 1,
    archRegWidth = p.archRegWidth,
    physRegWidth = p.physRegWidth
  ))
  val reducedReplayLiqReturnWritebackCandidate = Module(new LoadReplayReturnWritebackCandidate(
    dataWidth = p.immWidth,
    archRegWidth = p.archRegWidth,
    physRegWidth = p.physRegWidth
  ))
  val reducedReplayLiqReturnWakeupCandidate = Module(new LoadReplayReturnWakeupCandidate(
    archRegWidth = p.archRegWidth,
    physRegWidth = p.physRegWidth
  ))
  val reducedReplayLiqReturnSideEffectReady = Module(new LoadReplayReturnSideEffectReady)
  val reducedReplayLiqReturnPublishControl = Module(new LoadReplayReturnPublishControl)
  val reducedReplayLiqReturnPublishRequest = Module(new LoadReplayReturnPublishRequest)
  val reducedReplayLiqLaunchReadiness = Module(new LoadReplayLaunchReadiness)
  val reducedStoreResidentReplayWakeup = Module(new ResidentStoreReplayWakeup(
    entries = p.robEntries,
    peIdWidth = p.peIdWidth,
    stidWidth = p.threadIdWidth,
    tidWidth = p.threadIdWidth,
    mapQDepth = mapQDepth
  ))
  val reducedLoadWaitReplaySlot = Module(new ReducedLoadWaitReplaySlot(
    idEntries = p.robEntries,
    storeEntries = p.robEntries,
    archRegWidth = p.archRegWidth,
    physRegWidth = p.physRegWidth
  ))
  val reducedLoadReplayRelaunchQueue = Module(new ReducedLoadReplayRelaunchQueue(
    idEntries = p.robEntries,
    depth = reducedLoadReplayRelaunchQueueDepth,
    archRegWidth = p.archRegWidth,
    physRegWidth = p.physRegWidth
  ))
  val reducedLoadReplayLiqAllocPath = Module(new ReducedLoadReplayLiqAllocPath(
    liqEntries = p.robEntries,
    idEntries = p.robEntries,
    storeEntries = p.robEntries,
    archRegWidth = p.archRegWidth,
    physRegWidth = p.physRegWidth
  ))
  val reducedLoadReplayResolveQueue = Module(new LoadResolveQueue(
    queueEntries = p.robEntries,
    liqEntries = p.robEntries,
    idEntries = p.robEntries,
    addrWidth = p.immWidth,
    pcWidth = p.pcWidth,
    peIdWidth = p.peIdWidth,
    stidWidth = p.threadIdWidth,
    tidWidth = p.threadIdWidth
  ))
  val reducedMdbConflictDetect = Module(new MDBConflictDetect(
    entries = p.robEntries,
    loadEntries = p.robEntries,
    resolveEntries = p.robEntries,
    addrWidth = p.immWidth,
    pcWidth = p.pcWidth,
    peIdWidth = p.peIdWidth,
    stidWidth = p.threadIdWidth,
    tidWidth = p.threadIdWidth
  ))
  val reducedMdbQueueFanout = Module(new MDBQueueFanout(
    entries = p.robEntries,
    ssitEntries = p.robEntries,
    commandQueueEntries = 4,
    outputQueueEntries = 4,
    storeEntries = p.robEntries,
    addrWidth = p.immWidth,
    pcWidth = p.pcWidth,
    stidWidth = p.threadIdWidth
  ))
  val reducedLoadReplayCompletionDrain = Module(new ReducedLoadReplayCompletionDrain(
    idEntries = p.robEntries
  ))

  val localQueueDepth = 4
  val localTData = RegInit(VecInit(Seq.fill(localQueueDepth)(0.U(p.immWidth.W))))
  val localUData = RegInit(VecInit(Seq.fill(localQueueDepth)(0.U(p.immWidth.W))))
  val localTReady = RegInit(VecInit(Seq.fill(localQueueDepth)(false.B)))
  val localUReady = RegInit(VecInit(Seq.fill(localQueueDepth)(false.B)))
  val localPendingCountWidth = log2Ceil(issueQueueDepth + 2)
  val localTPendingCount = RegInit(0.U(localPendingCountWidth.W))
  val localUPendingCount = RegInit(0.U(localPendingCountWidth.W))
  val scalarSpValue = RegInit(0.U(p.immWidth.W))

  private def pushLocal(
      data: Vec[UInt],
      ready: Vec[Bool],
      value: UInt): Unit = {
    for (idx <- (1 until localQueueDepth).reverse) {
      data(idx) := data(idx - 1)
      ready(idx) := ready(idx - 1)
    }
    data(0) := value
    ready(0) := true.B
  }

  val markerRedirectPending = RegInit(false.B)
  val markerRedirectPcReg = RegInit(0.U(p.pcWidth.W))
  val bodyCutRestartPending = RegInit(false.B)
  val bodyCutRestartPcReg = RegInit(0.U(p.pcWidth.W))
  val reducedLoadReplayResolveClearPending = RegInit(false.B)
  val reducedLoadReplayResolveClearIndex = RegInit(0.U(log2Ceil(p.robEntries).W))
  val scalarRedirectPending = RegInit(false.B)
  val scalarRedirectBidReg = RegInit(ROBID.disabled(p.robEntries))
  val scalarRedirectRidReg = RegInit(ROBID.disabled(p.robEntries))
  val scalarRedirectLsIdReg = RegInit(ROBID.disabled(p.robEntries))
  val scalarRedirectResolveLsIdValidReg = RegInit(false.B)
  val scalarRedirectStidReg = RegInit(0.U(p.threadIdWidth.W))
  val scalarRedirectBlockBidReg = RegInit(0.U(p.blockBidWidth.W))
  val scalarRedirectOrderValidReg = RegInit(false.B)
  val scalarRedirectOrderReg = RegInit(0.U(p.uopUidWidth.W))
  val blockBranchTakenValid = RegInit(false.B)
  val blockBranchTaken = RegInit(false.B)
  val markerOnlyRedirectFire = path.io.blockMarkerStopRedirectValid && !execute.io.redirectValid
  val markerRedirectNeedsBackendCleanup = execute.io.redirectValid || ((!skipBlockMarkers).B && markerOnlyRedirectFire)
  val markerRedirectFire = markerOnlyRedirectFire || execute.io.redirectValid
  val admittedMarkerDrainBarrier = RegInit(false.B)
  val selectedSlotOH = UIntToOH(path.io.selectedSlot, p.decodeWidth)
  val selectedMarkerMask = path.io.blockBoundaryMask | path.io.blockStopMask
  val admittedMarkerDrainFire =
    (!skipBlockMarkers).B && denseSlots.io.outFire && path.io.selectedValid && (selectedMarkerMask & selectedSlotOH).orR
  val markerRedirectRetireSource = path.io.robMarkerRetireSource
  val markerRedirectSourceBid =
    Mux(markerRedirectRetireSource.valid, markerRedirectRetireSource.bid, ROBID.disabled(p.robEntries))
  val markerRedirectSourceRid =
    Mux(markerRedirectRetireSource.valid, markerRedirectRetireSource.rid, ROBID.disabled(p.robEntries))
  val markerRedirectSourceStid =
    Mux(markerRedirectRetireSource.valid, markerRedirectRetireSource.stid, 0.U(p.threadIdWidth.W))
  val markerRedirectSourceBlockBid =
    Mux(
      markerRedirectRetireSource.valid && markerRedirectRetireSource.blockBidValid,
      markerRedirectRetireSource.blockBid,
      0.U(p.blockBidWidth.W))
  val markerRedirectCleanupBid =
    Mux(path.io.blockMarkerStopRedirectValid, ROBID.inc(markerRedirectSourceBid), markerRedirectSourceBid)
  val frontendPipeFlush = io.frontendFlushValid || markerRedirectPending
  val backendPipeFlush = io.frontendFlushValid || scalarRedirectPending
  val externalBfuGeometryValid = io.reducedBfuBodyValid
  val staticBfuGeometry = Module(new ReducedBfuStaticGeometryProducer(p))
  val localBfuCutFeedbackPending = Module(new ReducedBfuResolvedBodyEndPending(p))
  val pendingRuntimeBodyEndCandidate = Module(new ReducedBfuPendingRuntimeBodyEndCandidate(p))
  val promotedRuntimeBodyEndOracle = Module(new ReducedBfuPromotedRuntimeBodyEndOracle(p))
  val resolvedBfuBodyEndSource = Module(new ReducedBfuResolvedBodyEndSource(p))
  val resolvedBfuBodyEnd = Module(new ReducedBfuResolvedBodyEndOwner(p))
  resolvedBfuBodyEndSource.io.runtimeValid := pendingRuntimeBodyEndCandidate.io.candidateValid
  resolvedBfuBodyEndSource.io.runtimeHeaderPc := pendingRuntimeBodyEndCandidate.io.candidateHeaderPc
  resolvedBfuBodyEndSource.io.runtimeHSizeBytes := pendingRuntimeBodyEndCandidate.io.candidateHSizeBytes
  resolvedBfuBodyEndSource.io.runtimeBodyEndPc := pendingRuntimeBodyEndCandidate.io.candidateBodyEndPc
  resolvedBfuBodyEndSource.io.replayValid := externalBfuGeometryValid
  resolvedBfuBodyEndSource.io.replayHeaderPc := io.reducedBfuHeaderPc
  resolvedBfuBodyEndSource.io.replayHSizeBytes := io.reducedBfuHSizeBytes
  resolvedBfuBodyEndSource.io.replayBSizeBytes := io.reducedBfuBSizeBytes
  localBfuCutFeedbackPending.io.flushValid := frontendPipeFlush || io.startValid || io.restartValid || markerRedirectFire
  localBfuCutFeedbackPending.io.captureValid := false.B
  localBfuCutFeedbackPending.io.captureHeaderPc := 0.U
  localBfuCutFeedbackPending.io.captureHSizeBytes := 0.U
  localBfuCutFeedbackPending.io.captureBodyEndPc := 0.U
  localBfuCutFeedbackPending.io.candidateValid := externalBfuGeometryValid
  localBfuCutFeedbackPending.io.candidateHeaderPc := io.reducedBfuHeaderPc
  localBfuCutFeedbackPending.io.candidateHSizeBytes := io.reducedBfuHSizeBytes
  localBfuCutFeedbackPending.io.candidateBSizeBytes := io.reducedBfuBSizeBytes
  localBfuCutFeedbackPending.io.consumeValid := resolvedBfuBodyEndSource.io.selectedRuntime
  pendingRuntimeBodyEndCandidate.io.pendingValid := localBfuCutFeedbackPending.io.pending
  pendingRuntimeBodyEndCandidate.io.pendingHeaderPc := localBfuCutFeedbackPending.io.pendingHeaderPc
  pendingRuntimeBodyEndCandidate.io.pendingHSizeBytes := localBfuCutFeedbackPending.io.pendingHSizeBytes
  pendingRuntimeBodyEndCandidate.io.pendingBodyEndPc := localBfuCutFeedbackPending.io.pendingBodyEndPc
  pendingRuntimeBodyEndCandidate.io.headerActive := staticBfuGeometry.io.headerActive
  pendingRuntimeBodyEndCandidate.io.activeHeaderPc := staticBfuGeometry.io.headerPc
  pendingRuntimeBodyEndCandidate.io.replayValid := externalBfuGeometryValid
  pendingRuntimeBodyEndCandidate.io.replayHeaderPc := io.reducedBfuHeaderPc
  pendingRuntimeBodyEndCandidate.io.replayHSizeBytes := io.reducedBfuHSizeBytes
  pendingRuntimeBodyEndCandidate.io.replayBSizeBytes := io.reducedBfuBSizeBytes
  promotedRuntimeBodyEndOracle.io.flushValid := frontendPipeFlush || io.startValid || io.restartValid || markerRedirectFire
  promotedRuntimeBodyEndOracle.io.promoteValid :=
    resolvedBfuBodyEndSource.io.selectedRuntime && pendingRuntimeBodyEndCandidate.io.candidateValid
  promotedRuntimeBodyEndOracle.io.promoteHeaderPc := pendingRuntimeBodyEndCandidate.io.candidateHeaderPc
  promotedRuntimeBodyEndOracle.io.promoteHSizeBytes := pendingRuntimeBodyEndCandidate.io.candidateHSizeBytes
  promotedRuntimeBodyEndOracle.io.promoteBodyEndPc := pendingRuntimeBodyEndCandidate.io.candidateBodyEndPc
  promotedRuntimeBodyEndOracle.io.replayValid := externalBfuGeometryValid
  promotedRuntimeBodyEndOracle.io.replayHeaderPc := io.reducedBfuHeaderPc
  promotedRuntimeBodyEndOracle.io.replayHSizeBytes := io.reducedBfuHSizeBytes
  promotedRuntimeBodyEndOracle.io.replayBSizeBytes := io.reducedBfuBSizeBytes
  resolvedBfuBodyEnd.io.flushValid := frontendPipeFlush || io.startValid || io.restartValid
  resolvedBfuBodyEnd.io.headerActive := staticBfuGeometry.io.headerActive
  resolvedBfuBodyEnd.io.activeHeaderPc := staticBfuGeometry.io.headerPc
  resolvedBfuBodyEnd.io.resolvedValid := resolvedBfuBodyEndSource.io.resolvedValid
  resolvedBfuBodyEnd.io.resolvedHeaderPc := resolvedBfuBodyEndSource.io.resolvedHeaderPc
  resolvedBfuBodyEnd.io.resolvedHSizeBytes := resolvedBfuBodyEndSource.io.resolvedHSizeBytes
  resolvedBfuBodyEnd.io.resolvedBodyEndPc := resolvedBfuBodyEndSource.io.resolvedBodyEndPc
  staticBfuGeometry.io.flushValid := frontendPipeFlush || io.startValid || io.restartValid
  staticBfuGeometry.io.f4UpdateFire := source.io.outFire
  staticBfuGeometry.io.f4Valid := f4.io.d1.valid
  staticBfuGeometry.io.f4Slots := f4.io.slots
  staticBfuGeometry.io.f4ValidMask := f4.io.validMask
  staticBfuGeometry.io.resolvedBodyEndValid := resolvedBfuBodyEnd.io.geometryValid
  staticBfuGeometry.io.resolvedHeaderPc := resolvedBfuBodyEnd.io.geometryHeaderPc
  staticBfuGeometry.io.resolvedHSizeBytes := resolvedBfuBodyEnd.io.hsizeBytes
  staticBfuGeometry.io.resolvedBodyEndPc := resolvedBfuBodyEnd.io.bodyEndPc
  val staticExternalComparable = staticBfuGeometry.io.geometryValid && externalBfuGeometryValid
  val staticExternalHeaderMatch = staticBfuGeometry.io.headerPc === io.reducedBfuHeaderPc
  val staticExternalHSizeMatch = staticBfuGeometry.io.hsizeBytes === io.reducedBfuHSizeBytes
  val staticExternalBSizeMatch = staticBfuGeometry.io.bsizeBytes === io.reducedBfuBSizeBytes
  val staticExternalMatch =
    staticExternalComparable && staticExternalHeaderMatch && staticExternalHSizeMatch && staticExternalBSizeMatch
  val staticExternalHeaderMismatch = staticExternalComparable && !staticExternalHeaderMatch
  val staticExternalHSizeMismatch = staticExternalComparable && !staticExternalHSizeMatch
  val staticExternalBSizeMismatch = staticExternalComparable && !staticExternalBSizeMatch

  val staticBfuPrediction = Module(new ReducedBfuGeometryPredictionLatch(p))
  staticBfuPrediction.io.flushValid := io.frontendFlushValid || io.startValid || io.restartValid
  staticBfuPrediction.io.learnValid := resolvedBfuBodyEnd.io.geometryValid
  staticBfuPrediction.io.learnHeaderPc := resolvedBfuBodyEnd.io.geometryHeaderPc
  staticBfuPrediction.io.learnHSizeBytes := resolvedBfuBodyEnd.io.hsizeBytes
  staticBfuPrediction.io.learnBSizeBytes := resolvedBfuBodyEnd.io.bsizeBytes

  val bodyCutArm = Module(new ReducedBfuBodyCutArm(p))
  bodyCutArm.io.predictionValid := staticBfuPrediction.io.geometryValid
  bodyCutArm.io.predictionHeaderPc := staticBfuPrediction.io.headerPc
  bodyCutArm.io.predictionHSizeBytes := staticBfuPrediction.io.hsizeBytes
  bodyCutArm.io.predictionBSizeBytes := staticBfuPrediction.io.bsizeBytes
  bodyCutArm.io.armValid := externalBfuGeometryValid
  bodyCutArm.io.armHeaderPc := io.reducedBfuHeaderPc
  bodyCutArm.io.armHSizeBytes := io.reducedBfuHSizeBytes
  bodyCutArm.io.armBSizeBytes := io.reducedBfuBSizeBytes

  val localBfuBodyWindow = Module(new ReducedBfuLocalBodyWindow(p))
  localBfuBodyWindow.io.flushValid := frontendPipeFlush || io.startValid || io.restartValid || markerRedirectFire
  localBfuBodyWindow.io.f4ScanValid := f4.io.d1.valid
  localBfuBodyWindow.io.predictionValid := staticBfuPrediction.io.geometryValid
  localBfuBodyWindow.io.predictionHeaderPc := staticBfuPrediction.io.headerPc
  localBfuBodyWindow.io.predictionHSizeBytes := staticBfuPrediction.io.hsizeBytes
  localBfuBodyWindow.io.predictionBSizeBytes := staticBfuPrediction.io.bsizeBytes
  localBfuBodyWindow.io.f4Valid := f4.io.d1.valid
  localBfuBodyWindow.io.f4Slots := f4.io.slots
  localBfuBodyWindow.io.f4ValidMask := f4.io.validMask

  val bodyCutGeometryValid = localBfuBodyWindow.io.geometryValid || resolvedBfuBodyEnd.io.geometryValid
  val bodyCutHeaderPc = Mux(localBfuBodyWindow.io.geometryValid, localBfuBodyWindow.io.headerPc, resolvedBfuBodyEnd.io.geometryHeaderPc)
  val bodyCutHSizeBytes = Mux(localBfuBodyWindow.io.geometryValid, localBfuBodyWindow.io.hsizeBytes, resolvedBfuBodyEnd.io.hsizeBytes)
  val bodyCutBSizeBytes = Mux(localBfuBodyWindow.io.geometryValid, localBfuBodyWindow.io.bsizeBytes, resolvedBfuBodyEnd.io.bsizeBytes)

  val bodyCut = Module(new ReducedBfuBodyCutPredictor(p))
  bodyCut.io.geometryValid := bodyCutGeometryValid
  bodyCut.io.headerPc := bodyCutHeaderPc
  bodyCut.io.hsizeBytes := bodyCutHSizeBytes
  bodyCut.io.bsizeBytes := bodyCutBSizeBytes
  bodyCut.io.f4Valid := f4.io.d1.valid
  bodyCut.io.f4Pc := f4.io.d1.pc
  bodyCut.io.f4Slots := f4.io.slots
  bodyCut.io.f4ValidMask := f4.io.validMask
  bodyCut.io.f4TotalLenBytes := f4.io.totalLenBytes

  val reducedBodyCutActive = bodyCut.io.cutActive
  val frontendValidMask = bodyCut.io.validMask
  val frontendSlotCount = bodyCut.io.slotCount
  val effectiveSourceAdvanceBytes = bodyCut.io.advanceBytes
  val frontendSlots = Wire(Vec(p.decodeWidth, new F4Slot(p)))
  frontendSlots := f4.io.slots
  for (slot <- 0 until p.decodeWidth) {
    val slotEndPc = (f4.io.slots(slot).pc + f4.io.slots(slot).lenBytes.pad(p.pcWidth))(p.pcWidth - 1, 0)
    val reducedBodyCutLast =
      reducedBodyCutActive && f4.io.slots(slot).valid && frontendValidMask(slot) && slotEndPc === bodyCut.io.cutPc
    frontendSlots(slot).isLastInBlock := f4.io.slots(slot).isLastInBlock || reducedBodyCutLast
  }
  val bodyCutRestartFire = source.io.outFire && reducedBodyCutActive
  val localBfuCutFeedbackFire = bodyCutRestartFire && localBfuBodyWindow.io.geometryValid
  localBfuBodyWindow.io.cutFire := bodyCutRestartFire

  localBfuCutFeedbackPending.io.captureValid := localBfuCutFeedbackFire
  localBfuCutFeedbackPending.io.captureHeaderPc := localBfuBodyWindow.io.headerPc
  localBfuCutFeedbackPending.io.captureHSizeBytes := localBfuBodyWindow.io.hsizeBytes
  localBfuCutFeedbackPending.io.captureBodyEndPc := bodyCut.io.cutPc

  when(io.frontendFlushValid || io.restartValid || io.startValid) {
    markerRedirectPending := false.B
    markerRedirectPcReg := 0.U
    scalarRedirectPending := false.B
    scalarRedirectBidReg := ROBID.disabled(p.robEntries)
    scalarRedirectRidReg := ROBID.disabled(p.robEntries)
    scalarRedirectLsIdReg := ROBID.disabled(p.robEntries)
    scalarRedirectResolveLsIdValidReg := false.B
    scalarRedirectStidReg := 0.U
    scalarRedirectBlockBidReg := 0.U
    scalarRedirectOrderValidReg := false.B
    scalarRedirectOrderReg := 0.U
  }.elsewhen(markerRedirectFire) {
    markerRedirectPending := true.B
    markerRedirectPcReg := Mux(path.io.blockMarkerStopRedirectValid, path.io.blockMarkerStopRedirectPc, execute.io.redirectPc)
    scalarRedirectPending := markerRedirectNeedsBackendCleanup
    scalarRedirectBidReg :=
      Mux(
        markerRedirectNeedsBackendCleanup,
        Mux(execute.io.redirectValid, execute.io.releaseBid, markerRedirectCleanupBid),
        ROBID.disabled(p.robEntries))
    scalarRedirectRidReg :=
      Mux(
        markerRedirectNeedsBackendCleanup,
        Mux(execute.io.redirectValid, execute.io.releaseRid, markerRedirectSourceRid),
        ROBID.disabled(p.robEntries))
    scalarRedirectLsIdReg :=
      Mux(
        markerRedirectNeedsBackendCleanup && execute.io.redirectValid,
        lsidToReducedStoreId(execute.io.completeLsId),
        ROBID.disabled(p.robEntries))
    scalarRedirectResolveLsIdValidReg := markerRedirectNeedsBackendCleanup && execute.io.redirectValid
    scalarRedirectStidReg :=
      Mux(markerRedirectNeedsBackendCleanup, Mux(execute.io.redirectValid, execute.io.releaseStid, markerRedirectSourceStid), 0.U)
    scalarRedirectBlockBidReg :=
      Mux(
        markerRedirectNeedsBackendCleanup,
        Mux(
          execute.io.redirectValid,
          Mux(execute.io.completeRow.blockBidValid, execute.io.completeRow.blockBid, 0.U),
          markerRedirectSourceBlockBid),
        0.U)
    scalarRedirectOrderValidReg := markerRedirectNeedsBackendCleanup && execute.io.redirectValid
    scalarRedirectOrderReg := Mux(markerRedirectNeedsBackendCleanup && execute.io.redirectValid, execute.io.redirectOrder, 0.U)
  }.elsewhen(markerRedirectPending) {
    markerRedirectPending := false.B
    scalarRedirectPending := false.B
    scalarRedirectBidReg := ROBID.disabled(p.robEntries)
    scalarRedirectRidReg := ROBID.disabled(p.robEntries)
    scalarRedirectLsIdReg := ROBID.disabled(p.robEntries)
    scalarRedirectResolveLsIdValidReg := false.B
    scalarRedirectStidReg := 0.U
    scalarRedirectBlockBidReg := 0.U
    scalarRedirectOrderValidReg := false.B
    scalarRedirectOrderReg := 0.U
  }
  when(io.frontendFlushValid || io.restartValid || io.startValid || markerRedirectFire) {
    admittedMarkerDrainBarrier := false.B
  }.elsewhen(admittedMarkerDrainFire) {
    admittedMarkerDrainBarrier := true.B
  }.elsewhen(path.io.robMarkerRetireSourceLifecycleFire) {
    admittedMarkerDrainBarrier := false.B
  }
  when(io.frontendFlushValid || io.restartValid || io.startValid || markerRedirectFire || markerRedirectPending) {
    bodyCutRestartPending := false.B
    bodyCutRestartPcReg := 0.U
  }.elsewhen(bodyCutRestartFire) {
    bodyCutRestartPending := true.B
    bodyCutRestartPcReg := bodyCut.io.restartPc
  }.elsewhen(bodyCutRestartPending) {
    bodyCutRestartPending := false.B
    bodyCutRestartPcReg := 0.U
  }

  source.io.startValid := io.startValid
  source.io.startPc := io.startPc
  source.io.restartValid := io.restartValid || markerRedirectPending || bodyCutRestartPending
  source.io.restartPc := Mux(markerRedirectPending, markerRedirectPcReg, Mux(bodyCutRestartPending, bodyCutRestartPcReg, io.restartPc))
  source.io.flushValid := io.frontendFlushValid
  source.io.peId := io.peId
  source.io.threadId := io.threadId
  source.io.reqReady := io.fetchReqReady
  source.io.respValid := io.fetchRespValid
  source.io.respWindow := io.fetchRespWindow
  source.io.outReady := denseSlots.io.inReady
  source.io.advanceBytes := effectiveSourceAdvanceBytes

  f4.io.in := source.io.out
  f4.io.flushValid := frontendPipeFlush

  denseSlots.io.inD1 := f4.io.d1
  denseSlots.io.inSlots := frontendSlots
  denseSlots.io.inValidMask := frontendValidMask
  denseSlots.io.outReady := path.io.decodeReady && !admittedMarkerDrainBarrier
  denseSlots.io.flushValid := frontendPipeFlush

  path.io.d1 := denseSlots.io.outD1
  path.io.d1.valid := denseSlots.io.outD1.valid && !admittedMarkerDrainBarrier
  path.io.slots := denseSlots.io.outSlots
  path.io.validMask := Mux(admittedMarkerDrainBarrier, 0.U, denseSlots.io.outValidMask)
  path.io.flushValid := backendPipeFlush
  val localIncomingUsesLocal =
    path.io.decRenValid && path.io.decRenHeadUsesLocal
  val localIncomingBlocked =
    localIncomingUsesLocal && ((localTPendingCount =/= 0.U) || (localUPendingCount =/= 0.U))
  path.io.renamedOutReady := issue.io.inReady && !localIncomingBlocked
  val reducedStoreFlush = backendPipeFlush || io.startValid || io.restartValid || (!useReducedStoreDispatchStq).B
  storeExecBridge.io.flushValid := reducedStoreFlush
  storeExecBridge.io.completeValid := execute.io.completeValid && useReducedStoreDispatchStq.B
  storeExecBridge.io.completeRow := execute.io.completeRow
  storeExecBridge.io.completeBid := execute.io.releaseBid
  storeExecBridge.io.completeRid := execute.io.releaseRid
  storeExecBridge.io.completeStid := execute.io.releaseStid
  storeExecBridge.io.staQueueValid := path.io.storeStaQueueValid
  storeExecBridge.io.staQueue := path.io.storeStaQueue
  storeExecBridge.io.stdQueueValid := path.io.storeStdQueueValid
  storeExecBridge.io.stdQueue := path.io.storeStdQueue
  storeExecBridge.io.staConsumed := path.io.storeSelectedSta
  storeExecBridge.io.stdConsumed := path.io.storeSelectedStd
  val zeroStoreExec = 0.U.asTypeOf(new StoreDispatchExecResult(64, 64, p.peIdWidth, p.threadIdWidth, p.threadIdWidth))
  path.io.storeStaExec := Mux(useReducedStoreDispatchStq.B, storeExecBridge.io.staExec, zeroStoreExec)
  path.io.storeStdExec := Mux(useReducedStoreDispatchStq.B, storeExecBridge.io.stdExec, zeroStoreExec)
  storeCommitOwner.io.enable := useReducedStoreDispatchStq.B
  storeCommitOwner.io.directFreeEnable := false.B
  storeCommitOwner.io.flushValid := reducedStoreFlush
  storeCommitOwner.io.activeStid := io.threadId
  storeCommitOwner.io.commit := path.io.commit
  storeCommitOwner.io.commitValidMask := path.io.commitValidMask
  storeCommitOwner.io.stqRows := path.io.storeStqRows
  storeCommitOwner.io.markCommitAccepted := path.io.storeMarkCommitAccepted
  storeCommitOwner.io.markCommitIgnored := path.io.storeMarkCommitIgnored
  storeCommitOwner.io.commitFreeAccepted := path.io.storeCommitFreeAccepted
  storeCommitOwner.io.commitFreeIgnored := path.io.storeCommitFreeIgnored
  storeCommitOwner.io.commitFreeAcceptedMask := path.io.storeCommitFreeAcceptedMask
  storeCommitOwner.io.commitFreeIgnoredMask := path.io.storeCommitFreeIgnoredMask

  val reducedStoreScbReadyForDrain = useReducedStoreDispatchStq.B && reducedStoreScb.io.modelBatchReady && !reducedStoreFlush
  reducedStoreCommitDrain.io.enqueueValid := useReducedStoreDispatchStq.B && path.io.storeMarkCommitAccepted
  reducedStoreCommitDrain.io.enqueueIndex := storeCommitOwner.io.markCommitIndex
  reducedStoreCommitDrain.io.enqueueBid := path.io.storeStqRows(storeCommitOwner.io.markCommitIndex).bid
  reducedStoreCommitDrain.io.enqueueLsId := path.io.storeStqRows(storeCommitOwner.io.markCommitIndex).lsId
  reducedStoreCommitDrain.io.flushValid := reducedStoreFlush
  reducedStoreCommitDrain.io.issueEnable := reducedStoreScbReadyForDrain
  reducedStoreCommitDrain.io.primaryReadyMask := Fill(p.robEntries, reducedStoreScbReadyForDrain)
  reducedStoreCommitDrain.io.secondaryReadyMask := Fill(p.robEntries, reducedStoreScbReadyForDrain)
  reducedStoreCommitDrain.io.rows := path.io.storeStqRows

  reducedStoreScb.io.reqs := reducedStoreCommitDrain.io.memReqs
  reducedStoreScb.io.evictEnable := useReducedStoreDispatchStq.B
  reducedStoreScb.io.dcacheReady := true.B
  reducedStoreScb.io.dcacheWriteHit := true.B
  reducedStoreScb.io.dcacheTagHit := true.B
  reducedStoreScb.io.l2RequestReady := true.B
  reducedStoreScb.io.rawRespValid := false.B
  reducedStoreScb.io.rawRespTxnId := 0.U
  reducedStoreScb.io.rawRespWrite := false.B
  reducedStoreScb.io.rawRespUpgrade := false.B

  private def zeroReducedStoreMemoryReq: STQCommitDrainRequest = {
    val req = Wire(new STQCommitDrainRequest(p.robEntries, p.immWidth, p.immWidth, 4))
    req := 0.U.asTypeOf(req)
    req
  }

  private def lsidToReducedStoreId(lsid: UInt): ROBID = {
    val out = Wire(new ROBID(p.robEntries))
    val idWidth = log2Ceil(p.robEntries)
    val wrapBit = if (p.lsidWidth > idWidth) lsid(idWidth) else false.B
    out.valid := true.B
    out.wrap := wrapBit
    out.value := lsid(idWidth - 1, 0)
    out
  }

  val reducedStoreMemoryReqs = Wire(Vec(reducedStoreMemoryRequestCount, new STQCommitDrainRequest(p.robEntries, p.immWidth, p.immWidth, 4)))
  val reducedStoreMemoryAcceptedVec = Wire(Vec(reducedStoreMemoryRequestCount, Bool()))
  for (idx <- 0 until reducedStoreMemoryRequestCount) {
    reducedStoreMemoryReqs(idx) := zeroReducedStoreMemoryReq
    reducedStoreMemoryAcceptedVec(idx) := false.B
  }

  for (idx <- 0 until reducedStoreScbRequestCount) {
    reducedStoreMemoryReqs(idx) := reducedStoreCommitDrain.io.memReqs(idx)
    reducedStoreMemoryAcceptedVec(idx) :=
      useReducedStoreDispatchStq.B && reducedStoreScb.io.acceptedMask(idx)
  }

  for (slot <- 0 until traceParams.commitWidth) {
    val commitStoreRow = path.io.commit.rows(slot)
    val commitStoreValid =
      useReducedStoreDispatchStq.B && commitStoreRow.valid && commitStoreRow.mem.valid && commitStoreRow.mem.isStore
    val commitStoreOffset = Wire(UInt(7.W))
    val commitStoreSizeWide = Wire(UInt(7.W))
    commitStoreOffset := commitStoreRow.mem.addr(5, 0)
    commitStoreSizeWide := commitStoreRow.mem.size
    val commitStoreCrosses = STQCommitDrain.crossesScalarCacheline(commitStoreRow.mem.addr, commitStoreRow.mem.size)
    val commitStoreFirstSize = Mux(commitStoreCrosses, 64.U(7.W) - commitStoreOffset, commitStoreSizeWide)
    val commitStoreSecondSize = commitStoreSizeWide - commitStoreFirstSize
    val commitStoreSecondAddr = (Cat(commitStoreRow.mem.addr(p.immWidth - 1, 6), 0.U(6.W)) + 64.U)(p.immWidth - 1, 0)
    val commitStoreAllDataBits = Fill(p.immWidth, 1.B).asUInt
    val commitStoreFirstData = commitStoreRow.mem.wdata & (commitStoreAllDataBits >> (commitStoreSecondSize << 3))
    val commitStoreSecondData = commitStoreRow.mem.wdata >> (commitStoreFirstSize << 3)

    val firstReq = Wire(new STQCommitDrainRequest(p.robEntries, p.immWidth, p.immWidth, 4))
    firstReq := zeroReducedStoreMemoryReq
    firstReq.valid := commitStoreValid
    firstReq.split := commitStoreCrosses
    firstReq.segment := 0.U
    firstReq.last := !commitStoreCrosses
    firstReq.addr := commitStoreRow.mem.addr
    firstReq.data := commitStoreFirstData
    firstReq.size := commitStoreFirstSize(3, 0)

    val secondReq = Wire(new STQCommitDrainRequest(p.robEntries, p.immWidth, p.immWidth, 4))
    secondReq := zeroReducedStoreMemoryReq
    secondReq.valid := commitStoreValid && commitStoreCrosses
    secondReq.split := commitStoreCrosses
    secondReq.segment := 1.U
    secondReq.last := true.B
    secondReq.addr := commitStoreSecondAddr
    secondReq.data := commitStoreSecondData
    secondReq.size := commitStoreSecondSize(3, 0)

    val base = reducedStoreScbRequestCount + slot * 2
    reducedStoreMemoryReqs(base) := firstReq
    reducedStoreMemoryAcceptedVec(base) := commitStoreValid
    reducedStoreMemoryReqs(base + 1) := secondReq
    reducedStoreMemoryAcceptedVec(base + 1) := commitStoreValid && commitStoreCrosses
  }

  reducedStoreMemoryOverlay.io.flush := io.startValid || io.restartValid || (!useReducedStoreDispatchStq).B
  reducedStoreMemoryOverlay.io.storeReqs := reducedStoreMemoryReqs
  reducedStoreMemoryOverlay.io.storeAcceptedMask := reducedStoreMemoryAcceptedVec.asUInt
  reducedStoreMemoryOverlay.io.loadValid := useReducedStoreDispatchStq.B && execute.io.loadLookupValid
  reducedStoreMemoryOverlay.io.loadAddr := execute.io.loadLookupAddr
  reducedStoreMemoryOverlay.io.baseLoadData := io.loadLookupData

  reducedStoreResidentForward.io.enable := useReducedStoreDispatchStq.B
  reducedStoreResidentForward.io.loadValid := useReducedStoreDispatchStq.B && execute.io.loadLookupValid
  reducedStoreResidentForward.io.loadAddr := execute.io.loadLookupAddr
  reducedStoreResidentForward.io.loadSize := execute.io.loadLookupSize
  reducedStoreResidentForward.io.loadBid := execute.io.loadLookupBid
  val reducedLoadLookupLsId = lsidToReducedStoreId(execute.io.loadLookupLsId)
  reducedStoreResidentForward.io.loadLsId := reducedLoadLookupLsId
  reducedStoreResidentForward.io.baseLoadData := reducedStoreMemoryOverlay.io.loadData
  reducedStoreResidentForward.io.rows := path.io.storeStqRows

  val executeLoadLookupDst = Wire(new LoadReplayDestination(p.archRegWidth, p.physRegWidth))
  executeLoadLookupDst.valid := execute.io.loadLookupDst.valid
  executeLoadLookupDst.kind := execute.io.loadLookupDst.kind
  executeLoadLookupDst.archTag := execute.io.loadLookupDst.archTag
  executeLoadLookupDst.relTag := execute.io.loadLookupDst.relTag
  executeLoadLookupDst.physTag := execute.io.loadLookupDst.physTag
  executeLoadLookupDst.oldPhysTag := execute.io.loadLookupDst.oldPhysTag

  reducedLoadWaitReplaySlot.io.flush := reducedStoreFlush
  reducedLoadWaitReplaySlot.io.captureValid :=
    useReducedStoreDispatchStq.B && execute.io.loadLookupValid && reducedStoreResidentForward.io.waitBlocked
  reducedLoadWaitReplaySlot.io.capturePc := execute.io.loadLookupPc
  reducedLoadWaitReplaySlot.io.captureAddr := execute.io.loadLookupAddr
  reducedLoadWaitReplaySlot.io.captureSize := execute.io.loadLookupSize
  reducedLoadWaitReplaySlot.io.captureReturnSignExtend := execute.io.loadLookupReturnSignExtend
  reducedLoadWaitReplaySlot.io.captureDst := executeLoadLookupDst
  reducedLoadWaitReplaySlot.io.captureBid := execute.io.loadLookupBid
  reducedLoadWaitReplaySlot.io.captureGid := execute.io.loadLookupGid
  reducedLoadWaitReplaySlot.io.captureRid := execute.io.loadLookupRid
  reducedLoadWaitReplaySlot.io.captureLsId := reducedLoadLookupLsId
  reducedLoadWaitReplaySlot.io.captureYoungestStoreId := execute.io.loadLookupBid
  reducedLoadWaitReplaySlot.io.captureYoungestStoreLsId := reducedLoadLookupLsId
  reducedLoadWaitReplaySlot.io.captureWaitStore := reducedStoreResidentForward.io.waitStore
  reducedLoadWaitReplaySlot.io.replayWakeValid := reducedStoreResidentReplayWakeup.io.wakeValid
  reducedLoadWaitReplaySlot.io.replayWake := reducedStoreResidentReplayWakeup.io.wake
  reducedStoreResidentReplayWakeup.io.enable := useReducedStoreDispatchStq.B
  reducedStoreResidentReplayWakeup.io.waitStore := reducedLoadWaitReplaySlot.io.storedWaitStore
  reducedStoreResidentReplayWakeup.io.rows := path.io.storeStqRows
  reducedLoadReplayRelaunchQueue.io.flush := reducedStoreFlush
  reducedLoadReplayRelaunchQueue.io.enqueueValid :=
    useReducedStoreDispatchStq.B && reducedLoadWaitReplaySlot.io.relaunch.valid
  reducedLoadReplayRelaunchQueue.io.enqueue := reducedLoadWaitReplaySlot.io.relaunch
  val reducedLoadReplayLiqAllocEnabled =
    useReducedStoreDispatchStq.B && useReducedLoadReplayLiqAlloc.B
  reducedLoadReplayLiqAllocPath.io.flush := reducedStoreFlush
  reducedLoadReplayLiqAllocPath.io.candidateValid :=
    reducedLoadReplayLiqAllocEnabled && reducedLoadReplayRelaunchQueue.io.outValid
  reducedLoadReplayLiqAllocPath.io.candidate := reducedLoadReplayRelaunchQueue.io.out
  reducedReplayLiqStoreSnapshot.io.enable := reducedLoadReplayLiqAllocEnabled
  reducedReplayLiqStoreSnapshot.io.rows := path.io.storeStqRows
  reducedReplayLiqBaseDataAlign.io.enable := reducedLoadReplayLiqAllocEnabled
  reducedReplayLiqBaseDataAlign.io.loadValid := reducedLoadReplayLiqAllocPath.io.launchValid
  reducedReplayLiqBaseDataAlign.io.loadAddr := reducedLoadReplayLiqAllocPath.io.launchSelectedAddr
  reducedReplayLiqBaseDataAlign.io.loadSize := reducedLoadReplayLiqAllocPath.io.launchSelectedSize
  reducedReplayLiqBaseDataAlign.io.loadData := io.loadLookupData
  loadLookupArbiter.io.executeValid := execute.io.loadLookupValid
  loadLookupArbiter.io.executeAddr := execute.io.loadLookupAddr
  loadLookupArbiter.io.executePc := execute.io.loadLookupPc
  loadLookupArbiter.io.replayValid := reducedReplayLiqBaseDataAlign.io.requestValid
  loadLookupArbiter.io.replayAddr := reducedLoadReplayLiqAllocPath.io.launchSelectedAddr
  loadLookupArbiter.io.replayPc := reducedLoadReplayLiqAllocPath.io.launchSelectedPc
  val reducedReplayLiqBaseDataReady =
    loadLookupArbiter.io.replayGranted && reducedReplayLiqBaseDataAlign.io.dataReturned
  val reducedReplayLiqStoreSnapshotReady = reducedLoadReplayLiqAllocEnabled
  val reducedReplayLiqReturnPipeBudgetEnable = reducedLoadReplayLiqAllocEnabled
  val reducedReplayLiqReturnLretSinkReady = false.B
  val reducedReplayLiqReturnWakeupSinkReady = false.B
  reducedReplayLiqSourceReturnReadiness.io.enable := reducedLoadReplayLiqAllocEnabled
  reducedReplayLiqSourceReturnReadiness.io.launchValid := reducedLoadReplayLiqAllocPath.io.launchValid
  reducedReplayLiqSourceReturnReadiness.io.baseDataReady := reducedReplayLiqBaseDataReady
  reducedReplayLiqSourceReturnReadiness.io.storeSnapshotReady := reducedReplayLiqStoreSnapshotReady
  reducedReplayLiqSourceReturnReadiness.io.externalScbPending := false.B
  reducedReplayLiqSourceReturnReadiness.io.externalScbReturned := false.B
  reducedReplayLiqReturnConsumerReady.io.enable := reducedLoadReplayLiqAllocEnabled
  reducedReplayLiqReturnConsumerReady.io.launchValid := reducedLoadReplayLiqAllocPath.io.launchValid
  reducedReplayLiqReturnConsumerReady.io.sourcesReturned := reducedReplayLiqSourceReturnReadiness.io.sourceReturned
  reducedReplayLiqReturnConsumerReady.io.specWakeup := reducedLoadReplayLiqAllocPath.io.launchSelectedSpecWakeup
  reducedReplayLiqReturnConsumerReady.io.stackValid := reducedLoadReplayLiqAllocPath.io.launchSelectedStackValid
  reducedReplayLiqReturnConsumerReady.io.lretSinkReady := reducedReplayLiqReturnLretSinkReady
  reducedReplayLiqReturnConsumerReady.io.wakeupSinkReady := reducedReplayLiqReturnWakeupSinkReady
  reducedReplayLiqReturnPipeBudget.io.enable := reducedLoadReplayLiqAllocEnabled
  reducedReplayLiqReturnPipeBudget.io.launchValid := reducedLoadReplayLiqAllocPath.io.launchValid
  reducedReplayLiqReturnPipeBudget.io.sourcesReturned := reducedReplayLiqSourceReturnReadiness.io.sourceReturned
  reducedReplayLiqReturnPipeBudget.io.pipeBudgetEnable := reducedReplayLiqReturnPipeBudgetEnable
  reducedReplayLiqReturnPipeBudget.io.consumerReady := reducedReplayLiqReturnConsumerReady.io.consumerReady
  reducedReplayLiqReturnPipePermit.io.enable := reducedLoadReplayLiqAllocEnabled
  reducedReplayLiqReturnPipePermit.io.launchValid := reducedLoadReplayLiqAllocPath.io.launchValid
  reducedReplayLiqReturnPipePermit.io.sourcesReturned := reducedReplayLiqSourceReturnReadiness.io.sourceReturned
  reducedReplayLiqReturnPipePermit.io.pipeBudgetAvailable := reducedReplayLiqReturnPipeBudget.io.pipeBudgetAvailable
  reducedReplayLiqReturnPipeSelect.io.enable := reducedLoadReplayLiqAllocEnabled
  reducedReplayLiqReturnPipeSelect.io.launchValid := reducedLoadReplayLiqAllocPath.io.launchValid
  reducedReplayLiqReturnPipeSelect.io.sourcesReturned := reducedReplayLiqSourceReturnReadiness.io.sourceReturned
  reducedReplayLiqReturnPipeSelect.io.pipeAvailableMask := reducedReplayLiqReturnPipePermit.io.pipeAvailableMask
  reducedReplayLiqReturnReadiness.io.enable := reducedLoadReplayLiqAllocEnabled
  reducedReplayLiqReturnReadiness.io.launchValid := reducedLoadReplayLiqAllocPath.io.launchValid
  reducedReplayLiqReturnReadiness.io.sourcesReturned := reducedReplayLiqSourceReturnReadiness.io.sourceReturned
  reducedReplayLiqReturnReadiness.io.returnPipeAvailable := reducedReplayLiqReturnPipeSelect.io.pipeAvailable
  reducedReplayLiqReturnReadiness.io.returnPipeIndex := reducedReplayLiqReturnPipeSelect.io.selectedPipeIndex
  val reducedReplayLiqE2BaseData =
    Mux(loadLookupArbiter.io.replayGranted, reducedReplayLiqBaseDataAlign.io.lineData, 0.U)
  val reducedReplayLiqE2BaseValidMask =
    Mux(loadLookupArbiter.io.replayGranted, reducedReplayLiqBaseDataAlign.io.lineValidMask, 0.U)
  reducedReplayLiqReturnDataExtract.io.enable := reducedLoadReplayLiqAllocEnabled
  reducedReplayLiqReturnDataExtract.io.returnValid := reducedReplayLiqSourceReturnReadiness.io.sourceReturned
  reducedReplayLiqReturnDataExtract.io.lineData := reducedReplayLiqE2BaseData
  reducedReplayLiqReturnDataExtract.io.lineValidMask := reducedReplayLiqE2BaseValidMask
  reducedReplayLiqReturnDataExtract.io.addr := reducedLoadReplayLiqAllocPath.io.launchSelectedAddr
  reducedReplayLiqReturnDataExtract.io.size := reducedLoadReplayLiqAllocPath.io.launchSelectedSize
  reducedReplayLiqReturnDataExtract.io.signExtend := reducedLoadReplayLiqAllocPath.io.launchSelectedReturnSignExtend
  reducedReplayLiqReturnPublishReady.io.enable := reducedLoadReplayLiqAllocEnabled
  reducedReplayLiqReturnPublishReady.io.launchValid := reducedLoadReplayLiqAllocPath.io.launchValid
  reducedReplayLiqReturnPublishReady.io.dataValid := reducedReplayLiqReturnDataExtract.io.dataValid
  reducedReplayLiqReturnPublishReady.io.consumerReady := reducedReplayLiqReturnConsumerReady.io.consumerReady
  reducedReplayLiqReturnLretPayload.io.enable := reducedLoadReplayLiqAllocEnabled
  reducedReplayLiqReturnLretPayload.io.launchValid := reducedLoadReplayLiqAllocPath.io.launchValid
  reducedReplayLiqReturnLretPayload.io.dataValid := reducedReplayLiqReturnDataExtract.io.dataValid
  reducedReplayLiqReturnLretPayload.io.selectedBid := reducedLoadReplayLiqAllocPath.io.launchSelectedBid
  reducedReplayLiqReturnLretPayload.io.selectedGid := reducedLoadReplayLiqAllocPath.io.launchSelectedGid
  reducedReplayLiqReturnLretPayload.io.selectedRid := reducedLoadReplayLiqAllocPath.io.launchSelectedRid
  reducedReplayLiqReturnLretPayload.io.selectedLoadLsId := reducedLoadReplayLiqAllocPath.io.launchSelectedLoadLsId
  reducedReplayLiqReturnLretPayload.io.selectedPc := reducedLoadReplayLiqAllocPath.io.launchSelectedPc
  reducedReplayLiqReturnLretPayload.io.selectedAddr := reducedLoadReplayLiqAllocPath.io.launchSelectedAddr
  reducedReplayLiqReturnLretPayload.io.selectedSize := reducedLoadReplayLiqAllocPath.io.launchSelectedSize
  reducedReplayLiqReturnLretPayload.io.selectedDst := reducedLoadReplayLiqAllocPath.io.launchSelectedDst
  reducedReplayLiqReturnLretPayload.io.returnData := reducedReplayLiqReturnDataExtract.io.data
  reducedReplayLiqReturnLretPayload.io.returnPipeIndex := reducedReplayLiqReturnReadiness.io.selectedPipeIndex
  reducedReplayLiqReturnLretPayload.io.specWakeup := reducedLoadReplayLiqAllocPath.io.launchSelectedSpecWakeup
  reducedReplayLiqReturnLretPayload.io.stackValid := reducedLoadReplayLiqAllocPath.io.launchSelectedStackValid
  reducedReplayLiqReturnWritebackCandidate.io.enable := reducedLoadReplayLiqAllocEnabled
  reducedReplayLiqReturnWritebackCandidate.io.payloadValid := reducedReplayLiqReturnLretPayload.io.payloadValid
  reducedReplayLiqReturnWritebackCandidate.io.payloadDst := reducedReplayLiqReturnLretPayload.io.payloadDst
  reducedReplayLiqReturnWritebackCandidate.io.payloadData := reducedReplayLiqReturnLretPayload.io.payloadData
  reducedReplayLiqReturnWakeupCandidate.io.enable := reducedLoadReplayLiqAllocEnabled
  reducedReplayLiqReturnWakeupCandidate.io.payloadValid := reducedReplayLiqReturnLretPayload.io.payloadValid
  reducedReplayLiqReturnWakeupCandidate.io.payloadWakeupRequired := reducedReplayLiqReturnLretPayload.io.wakeupRequired
  reducedReplayLiqReturnWakeupCandidate.io.payloadDst := reducedReplayLiqReturnLretPayload.io.payloadDst
  reducedReplayLiqLaunchReadiness.io.enable := reducedLoadReplayLiqAllocEnabled
  reducedReplayLiqLaunchReadiness.io.launchValid := reducedLoadReplayLiqAllocPath.io.launchValid
  reducedReplayLiqLaunchReadiness.io.baseLookupGranted := loadLookupArbiter.io.replayGranted
  reducedReplayLiqLaunchReadiness.io.baseDataReturned := reducedReplayLiqBaseDataAlign.io.dataReturned
  reducedReplayLiqLaunchReadiness.io.scbReturned := reducedReplayLiqSourceReturnReadiness.io.sourceReturned
  reducedReplayLiqLaunchReadiness.io.returnReady := reducedReplayLiqReturnReadiness.io.returnReady
  reducedLoadReplayLiqAllocPath.io.launchEnable := reducedReplayLiqLaunchReadiness.io.launchEnable
  reducedLoadReplayLiqAllocPath.io.e2Stores := reducedReplayLiqStoreSnapshot.io.stores
  reducedLoadReplayLiqAllocPath.io.e2BaseData := reducedReplayLiqE2BaseData
  reducedLoadReplayLiqAllocPath.io.e2BaseValidMask := reducedReplayLiqE2BaseValidMask
  reducedLoadReplayLiqAllocPath.io.e2LoadDataReturned := reducedReplayLiqBaseDataReady
  reducedLoadReplayLiqAllocPath.io.e2ScbReturned := reducedReplayLiqSourceReturnReadiness.io.sourceReturned
  reducedLoadReplayLiqAllocPath.io.e2ReturnReady := reducedReplayLiqReturnReadiness.io.returnReady
  reducedLoadReplayLiqAllocPath.io.clearResolvedValid := reducedLoadReplayResolveClearPending
  reducedLoadReplayLiqAllocPath.io.clearResolvedIndex := reducedLoadReplayResolveClearIndex
  val reducedLoadReplayResolvePreciseFlush =
    Wire(new FlushBus(p.robEntries, peIdWidth = p.peIdWidth, stidWidth = p.threadIdWidth, tidWidth = p.threadIdWidth))
  reducedLoadReplayResolvePreciseFlush := 0.U.asTypeOf(reducedLoadReplayResolvePreciseFlush)
  val reducedLoadReplayResolveHardFlush =
    io.frontendFlushValid || io.startValid || io.restartValid || (!useReducedStoreDispatchStq).B ||
      (scalarRedirectPending && !scalarRedirectResolveLsIdValidReg)
  reducedLoadReplayResolveQueue.io.flush := reducedLoadReplayResolveHardFlush
  reducedLoadReplayResolveQueue.io.preciseFlush := reducedLoadReplayResolvePreciseFlush
  reducedLoadReplayResolveQueue.io.pushValid :=
    reducedLoadReplayLiqAllocEnabled && reducedLoadReplayLiqAllocPath.io.lhqRecordValid
  reducedLoadReplayResolveQueue.io.pushPeId := io.peId
  reducedLoadReplayResolveQueue.io.pushStid := io.threadId
  reducedLoadReplayResolveQueue.io.pushTid := io.threadId
  reducedLoadReplayResolveQueue.io.pushRecord := reducedLoadReplayLiqAllocPath.io.lhqRecord
  val reducedLoadReplayResolveRetireSource = Wire(chiselTypeOf(path.io.commitMemoryOrder(0)))
  reducedLoadReplayResolveRetireSource := 0.U.asTypeOf(reducedLoadReplayResolveRetireSource)
  for (slot <- 0 until traceParams.commitWidth) {
    when(path.io.commitMemoryOrder(slot).valid) {
      reducedLoadReplayResolveRetireSource := path.io.commitMemoryOrder(slot)
    }
  }
  val reducedLoadReplayResolveRetireLsId = lsidToReducedStoreId(reducedLoadReplayResolveRetireSource.lsId)
  reducedLoadReplayResolveQueue.io.retireValid :=
    reducedLoadReplayLiqAllocEnabled && reducedLoadReplayResolveRetireSource.valid
  reducedLoadReplayResolveQueue.io.retireBid := reducedLoadReplayResolveRetireSource.bid
  reducedLoadReplayResolveQueue.io.retireLsId := reducedLoadReplayResolveRetireLsId
  val reducedMdbStoreProbe = Wire(new MDBConflictStoreProbe(
    p.robEntries,
    addrWidth = p.immWidth,
    pcWidth = p.pcWidth,
    peIdWidth = p.peIdWidth,
    stidWidth = p.threadIdWidth,
    tidWidth = p.threadIdWidth
  ))
  reducedMdbStoreProbe := 0.U.asTypeOf(reducedMdbStoreProbe)
  reducedMdbStoreProbe.bid := ROBID.disabled(p.robEntries)
  reducedMdbStoreProbe.gid := ROBID.disabled(p.robEntries)
  reducedMdbStoreProbe.rid := ROBID.disabled(p.robEntries)
  reducedMdbStoreProbe.lsId := ROBID.disabled(p.robEntries)
  reducedMdbStoreProbe.valid := reducedLoadReplayLiqAllocEnabled && path.io.storeStqInsertAccepted
  reducedMdbStoreProbe.addrOnly := path.io.storeStqInsert.storeType === STQStoreType.Addr
  reducedMdbStoreProbe.isTile := !path.io.storeStqInsert.scalarIex
  reducedMdbStoreProbe.peId := path.io.storeStqInsert.peId
  reducedMdbStoreProbe.stid := path.io.storeStqInsert.stid
  reducedMdbStoreProbe.tid := path.io.storeStqInsert.tid
  reducedMdbStoreProbe.bid := path.io.storeStqInsert.bid
  reducedMdbStoreProbe.gid := path.io.storeStqInsert.gid
  reducedMdbStoreProbe.rid := path.io.storeStqInsert.rid
  reducedMdbStoreProbe.lsId := path.io.storeStqInsert.lsId
  reducedMdbStoreProbe.pc := path.io.storeStqInsert.pc
  reducedMdbStoreProbe.addr := path.io.storeStqInsert.addr
  reducedMdbStoreProbe.size := path.io.storeStqInsert.size.pad(7)

  val reducedMdbActiveLoads = Wire(Vec(
    p.robEntries,
    new MDBConflictLoadEntry(
      p.robEntries,
      addrWidth = p.immWidth,
      pcWidth = p.pcWidth,
      peIdWidth = p.peIdWidth,
      stidWidth = p.threadIdWidth,
      tidWidth = p.threadIdWidth
    )
  ))
  for (idx <- 0 until p.robEntries) {
    val liqRow = reducedLoadReplayLiqAllocPath.io.rows(idx)
    val active = Wire(chiselTypeOf(reducedMdbActiveLoads(idx)))
    active := 0.U.asTypeOf(active)
    active.bid := ROBID.disabled(p.robEntries)
    active.gid := ROBID.disabled(p.robEntries)
    active.rid := ROBID.disabled(p.robEntries)
    active.lsId := ROBID.disabled(p.robEntries)
    active.valid := reducedLoadReplayLiqAllocEnabled && liqRow.valid
    active.resolved := liqRow.status === LoadInflightStatus.Resolved
    active.isTile := liqRow.isTile
    active.peId := io.peId
    active.stid := io.threadId
    active.tid := io.threadId
    active.bid := liqRow.bid
    active.gid := liqRow.gid
    active.rid := liqRow.rid
    active.lsId := liqRow.loadLsId
    active.pc := liqRow.pc
    active.addr := liqRow.addr
    active.size := liqRow.size
    reducedMdbActiveLoads(idx) := active
  }
  reducedMdbConflictDetect.io.store := reducedMdbStoreProbe
  reducedMdbConflictDetect.io.activeLoads := reducedMdbActiveLoads
  reducedMdbConflictDetect.io.resolvedQueue := reducedLoadReplayResolveQueue.io.conflictRows
  val reducedMdbZeroBus = Wire(new MDBQueueBus(
    p.robEntries,
    addrWidth = p.immWidth,
    pcWidth = p.pcWidth,
    stidWidth = p.threadIdWidth
  ))
  reducedMdbZeroBus := 0.U.asTypeOf(reducedMdbZeroBus)
  reducedMdbZeroBus.ldInfo.bid := ROBID.disabled(p.robEntries)
  reducedMdbZeroBus.ldInfo.lsId := ROBID.disabled(p.robEntries)
  reducedMdbZeroBus.stInfo.bid := ROBID.disabled(p.robEntries)
  reducedMdbZeroBus.stInfo.lsId := ROBID.disabled(p.robEntries)

  val reducedMdbRecordBus = Wire(chiselTypeOf(reducedMdbZeroBus))
  reducedMdbRecordBus := reducedMdbZeroBus
  reducedMdbRecordBus.valid := reducedMdbConflictDetect.io.conflictValid
  reducedMdbRecordBus.ldInfo.valid := reducedMdbConflictDetect.io.conflictValid
  reducedMdbRecordBus.ldInfo.pc := reducedMdbConflictDetect.io.record.load.pc
  reducedMdbRecordBus.ldInfo.bid := reducedMdbConflictDetect.io.record.load.bid
  reducedMdbRecordBus.ldInfo.lsId := reducedMdbConflictDetect.io.record.load.lsId
  reducedMdbRecordBus.ldInfo.stid := reducedMdbConflictDetect.io.record.load.stid
  reducedMdbRecordBus.ldInfo.addr := reducedMdbConflictDetect.io.record.load.addr
  reducedMdbRecordBus.ldInfo.size := reducedMdbConflictDetect.io.record.load.size
  reducedMdbRecordBus.ldInfo.isTile := reducedMdbConflictDetect.io.record.load.isTile
  reducedMdbRecordBus.stInfo.valid := reducedMdbConflictDetect.io.conflictValid
  reducedMdbRecordBus.stInfo.pc := reducedMdbConflictDetect.io.record.store.pc
  reducedMdbRecordBus.stInfo.bid := reducedMdbConflictDetect.io.record.store.bid
  reducedMdbRecordBus.stInfo.lsId := reducedMdbConflictDetect.io.record.store.lsId
  reducedMdbRecordBus.stInfo.stid := reducedMdbConflictDetect.io.record.store.stid
  reducedMdbRecordBus.stInfo.addr := reducedMdbConflictDetect.io.record.store.addr
  reducedMdbRecordBus.stInfo.size := reducedMdbConflictDetect.io.record.store.size
  reducedMdbRecordBus.stInfo.isTile := reducedMdbConflictDetect.io.record.store.isTile
  reducedMdbRecordBus.conf := 1.U

  val reducedMdbLookupRow =
    reducedLoadReplayLiqAllocPath.io.rows(reducedLoadReplayLiqAllocPath.io.launchIndex)
  val reducedMdbFanoutLookupValid =
    reducedLoadReplayLiqAllocEnabled && reducedLoadReplayLiqAllocPath.io.launchAccepted && !reducedMdbLookupRow.isTile
  val reducedMdbLookupBus = Wire(chiselTypeOf(reducedMdbZeroBus))
  reducedMdbLookupBus := reducedMdbZeroBus
  reducedMdbLookupBus.valid := reducedMdbFanoutLookupValid
  reducedMdbLookupBus.ldInfo.valid := reducedMdbFanoutLookupValid
  reducedMdbLookupBus.ldInfo.pc := reducedMdbLookupRow.pc
  reducedMdbLookupBus.ldInfo.bid := reducedMdbLookupRow.bid
  reducedMdbLookupBus.ldInfo.lsId := reducedMdbLookupRow.loadLsId
  reducedMdbLookupBus.ldInfo.stid := io.threadId
  reducedMdbLookupBus.ldInfo.addr := reducedMdbLookupRow.addr
  reducedMdbLookupBus.ldInfo.size := reducedMdbLookupRow.size
  reducedMdbLookupBus.ldInfo.isTile := reducedMdbLookupRow.isTile
  reducedMdbLookupBus.conf := 1.U

  val reducedMdbFanoutStoreRows = Wire(Vec(
    p.robEntries,
    new MDBStoreWakeupEntry(
      p.robEntries,
      p.robEntries,
      addrWidth = p.immWidth,
      pcWidth = p.pcWidth,
      stidWidth = p.threadIdWidth
    )
  ))
  for (idx <- 0 until p.robEntries) {
    val stqRow = path.io.storeStqRows(idx)
    val wakeRow = Wire(chiselTypeOf(reducedMdbFanoutStoreRows(idx)))
    wakeRow := 0.U.asTypeOf(wakeRow)
    wakeRow.bid := ROBID.disabled(p.robEntries)
    wakeRow.lsId := ROBID.disabled(p.robEntries)
    wakeRow.valid := reducedLoadReplayLiqAllocEnabled && stqRow.valid
    wakeRow.storeIndex := idx.U
    wakeRow.pc := stqRow.pc
    wakeRow.bid := stqRow.bid
    wakeRow.lsId := stqRow.lsId
    wakeRow.stid := stqRow.stid
    wakeRow.addr := stqRow.addr
    wakeRow.size := stqRow.size.pad(7)
    wakeRow.addrReady := stqRow.addrReady
    wakeRow.dataReady := stqRow.dataReady
    wakeRow.isTile := !stqRow.scalarIex
    reducedMdbFanoutStoreRows(idx) := wakeRow
  }
  reducedMdbQueueFanout.io.lookupIn := reducedMdbLookupBus
  reducedMdbQueueFanout.io.lookupInValid := reducedMdbFanoutLookupValid
  reducedMdbQueueFanout.io.deleteIn := reducedMdbZeroBus
  reducedMdbQueueFanout.io.deleteInValid := false.B
  reducedMdbQueueFanout.io.recordIn := reducedMdbRecordBus
  val reducedMdbFanoutRecordValid =
    reducedLoadReplayLiqAllocEnabled && reducedMdbConflictDetect.io.conflictValid
  reducedMdbQueueFanout.io.recordInValid := reducedMdbFanoutRecordValid
  reducedMdbQueueFanout.io.luDequeueReady := true.B
  reducedMdbQueueFanout.io.suCheckReady := true.B
  reducedMdbQueueFanout.io.storeRows := reducedMdbFanoutStoreRows
  when(reducedStoreFlush || !reducedLoadReplayLiqAllocEnabled) {
    reducedLoadReplayResolveClearPending := false.B
  }.otherwise {
    when(reducedLoadReplayLiqAllocPath.io.clearResolvedAccepted) {
      reducedLoadReplayResolveClearPending := false.B
    }
    when(reducedLoadReplayResolveQueue.io.pushAccepted) {
      reducedLoadReplayResolveClearPending := true.B
      reducedLoadReplayResolveClearIndex := reducedLoadReplayLiqAllocPath.io.lhqRecord.loadId.value
    }
  }
  val reducedCompleteLoadLsId = lsidToReducedStoreId(execute.io.completeLsId)
  reducedLoadReplayCompletionDrain.io.candidateValid :=
    reducedLoadReplayRelaunchQueue.io.outValid && !reducedLoadReplayLiqAllocEnabled
  reducedLoadReplayCompletionDrain.io.candidate := reducedLoadReplayRelaunchQueue.io.out
  reducedLoadReplayCompletionDrain.io.completeValid :=
    useReducedStoreDispatchStq.B && !useReducedLoadReplayLiqAlloc.B && execute.io.completeValid
  reducedLoadReplayCompletionDrain.io.completeMemLoad :=
    execute.io.completeRow.mem.valid && !execute.io.completeRow.mem.isStore
  reducedLoadReplayCompletionDrain.io.completePc := execute.io.completeRow.pc
  reducedLoadReplayCompletionDrain.io.completeAddr := execute.io.completeRow.mem.addr
  reducedLoadReplayCompletionDrain.io.completeSize := execute.io.completeRow.mem.size
  reducedLoadReplayCompletionDrain.io.completeBid := execute.io.releaseBid
  reducedLoadReplayCompletionDrain.io.completeGid := execute.io.releaseGid
  reducedLoadReplayCompletionDrain.io.completeRid := execute.io.releaseRid
  reducedLoadReplayCompletionDrain.io.completeLsId := reducedCompleteLoadLsId
  reducedLoadReplayRelaunchQueue.io.outReady := Mux(
    reducedLoadReplayLiqAllocEnabled,
    reducedLoadReplayLiqAllocPath.io.candidateConsumeReady,
    reducedLoadReplayCompletionDrain.io.consumeReady
  )

  path.io.storeMarkCommitValid := storeCommitOwner.io.markCommitValid
  path.io.storeMarkCommitIndex := storeCommitOwner.io.markCommitIndex
  path.io.storeCommitFreeValid := false.B
  path.io.storeCommitFreeIndex := 0.U
  path.io.storeCommitFreeMaskValid := useReducedStoreDispatchStq.B && reducedStoreScb.io.commitFreeMaskValid
  path.io.storeCommitFreeMask := Mux(useReducedStoreDispatchStq.B, reducedStoreScb.io.commitFreeMask, 0.U)
  path.io.checkpointValid := false.B
  path.io.checkpointBid := ROBID.disabled(p.robEntries)
  path.io.commitValid := false.B
  path.io.commitBid := ROBID.disabled(p.robEntries)
  path.io.commitBlockBid := 0.U
  val pathCleanup = Wire(new RecoveryCleanupIntent(p.robEntries, peIdWidth = p.peIdWidth, stidWidth = p.threadIdWidth, tidWidth = p.threadIdWidth))
  pathCleanup := 0.U.asTypeOf(pathCleanup)
  pathCleanup.valid := scalarRedirectPending
  pathCleanup.flush.req.valid := scalarRedirectPending
  pathCleanup.flush.req.typ := FlushType.InnerFlush
  pathCleanup.flush.req.peId := io.peId
  pathCleanup.flush.req.tid := io.threadId
  pathCleanup.flush.req.stid := scalarRedirectStidReg
  pathCleanup.flush.req.bid := scalarRedirectBidReg
  pathCleanup.flush.req.gid := ROBID.disabled(p.robEntries)
  pathCleanup.flush.req.rid := ROBID.inc(scalarRedirectRidReg)
  pathCleanup.flush.req.lsId := ROBID.disabled(p.robEntries)
  pathCleanup.flush.req.execEngine := ExecEngineType.Scalar
  pathCleanup.flush.req.fetchTpcValid := false.B
  pathCleanup.flush.req.immediateFlush := true.B
  pathCleanup.flush.baseOnBid := false.B
  pathCleanup.flush.baseOnGroup := false.B
  pathCleanup.flush.baseOnPE := false.B
  pathCleanup.flush.baseOnThread := false.B
  pathCleanup.flush.simtReplay := false.B
  pathCleanup.flush.mtcReplay := false.B
  pathCleanup.robPruneValid := scalarRedirectPending
  pathCleanup.renameFlushValid := scalarRedirectPending
  pathCleanup.backendFlushValid := scalarRedirectPending
  pathCleanup.blockFlushValid := scalarRedirectPending
  pathCleanup.blockFlushBid := scalarRedirectBlockBidReg
  pathCleanup.reportQueueFlushValid := scalarRedirectPending
  reducedLoadReplayResolvePreciseFlush := pathCleanup.flush
  reducedLoadReplayResolvePreciseFlush.req.valid :=
    reducedLoadReplayLiqAllocEnabled && scalarRedirectPending && scalarRedirectResolveLsIdValidReg
  // ResolveQ is MemReq-shaped; scalar precise pruning compares BID plus LSID, not ROB RID.
  reducedLoadReplayResolvePreciseFlush.req.lsId := scalarRedirectLsIdReg
  path.io.cleanup := pathCleanup
  path.io.scalarCleanupOrderValid := scalarRedirectOrderValidReg
  path.io.scalarCleanupOrder := scalarRedirectOrderReg
  path.io.completeValid := execute.io.completeValid
  path.io.completeRobValue := execute.io.completeRobValue
  path.io.completeRowValid := execute.io.completeValid
  path.io.completeRow := execute.io.completeRow
  path.io.blockBranchTakenValid := blockBranchTakenValid
  path.io.blockBranchTaken := blockBranchTaken
  path.io.scalarRedirectValid := execute.io.redirectValid
  path.io.scalarRedirectStid := execute.io.releaseStid
  path.io.deallocReady := io.deallocReady

  issue.io.inValid := path.io.renamedOutValid && !localIncomingBlocked
  issue.io.in := path.io.renamedOut
  issue.io.flushValid := backendPipeFlush
  issue.io.releaseValid := execute.io.releaseValid
  issue.io.releaseBid := execute.io.releaseBid
  issue.io.releaseRid := execute.io.releaseRid
  issue.io.releaseStid := execute.io.releaseStid
  issue.io.readyMask := rf.io.readyMask
  issue.io.localTReadyMask := localTReady.asUInt
  issue.io.localUReadyMask := localUReady.asUInt

  rf.io.initValid := io.rfInitValid
  rf.io.initArchTag := io.rfInitArchTag
  rf.io.initData := io.rfInitData
  for (idx <- 0 until 3) {
    val readIsT = issue.io.readValid(idx) && (issue.io.readOperandClass(idx) === OperandClass.T)
    val readIsU = issue.io.readValid(idx) && (issue.io.readOperandClass(idx) === OperandClass.U)
    val readIsLocal = readIsT || readIsU
    val readIsScalarSp =
      issue.io.readValid(idx) &&
        (issue.io.readOperandClass(idx) === OperandClass.P) &&
        (issue.io.readRelTag(idx) === 1.U)
    val rel = issue.io.readRelTag(idx)(1, 0)
    val localReadReady = Mux(readIsT, localTReady(rel), Mux(readIsU, localUReady(rel), false.B))
    val localReadData = Mux(readIsT, localTData(rel), localUData(rel))
    val scalarReadData = Mux(readIsScalarSp, scalarSpValue, rf.io.readData(idx))

    rf.io.readValid(idx) := issue.io.readValid(idx) && !readIsLocal
    rf.io.readTags(idx) := issue.io.readTags(idx)
    issue.io.readReady(idx) := Mux(readIsLocal, localReadReady, rf.io.readReady(idx))
    issue.io.readData(idx) := Mux(readIsLocal, localReadData, scalarReadData)
  }
  rf.io.clearValid := issue.io.enqueueDstValid
  rf.io.clearTag := issue.io.enqueueDstTag
  rfWritebackArbiter.io.executeValid := execute.io.completeDstPhysValid
  rfWritebackArbiter.io.executeTag := execute.io.completeDstPhysTag
  rfWritebackArbiter.io.executeData := execute.io.completeDstData
  rfWritebackArbiter.io.replayEnable := false.B
  rfWritebackArbiter.io.replayValid := reducedReplayLiqReturnWritebackCandidate.io.writeValid
  rfWritebackArbiter.io.replayTag := reducedReplayLiqReturnWritebackCandidate.io.writeTag
  rfWritebackArbiter.io.replayData := reducedReplayLiqReturnWritebackCandidate.io.writeData
  val reducedReplayLiqRfWritebackSinkReady =
    rfWritebackArbiter.io.replayEnable && !rfWritebackArbiter.io.replayBlockedByExecute
  reducedReplayLiqReturnSideEffectReady.io.enable := reducedLoadReplayLiqAllocEnabled
  reducedReplayLiqReturnSideEffectReady.io.payloadValid := reducedReplayLiqReturnLretPayload.io.payloadValid
  reducedReplayLiqReturnSideEffectReady.io.lretSinkReady := reducedReplayLiqReturnLretSinkReady
  reducedReplayLiqReturnSideEffectReady.io.writebackRequired := reducedReplayLiqReturnWritebackCandidate.io.writeValid
  reducedReplayLiqReturnSideEffectReady.io.writebackSinkReady := reducedReplayLiqRfWritebackSinkReady
  reducedReplayLiqReturnSideEffectReady.io.wakeupRequired := reducedReplayLiqReturnWakeupCandidate.io.wakeupRequired
  reducedReplayLiqReturnSideEffectReady.io.wakeupSinkReady := reducedReplayLiqReturnWakeupSinkReady
  reducedReplayLiqReturnPublishControl.io.enable := reducedLoadReplayLiqAllocEnabled
  reducedReplayLiqReturnPublishControl.io.liveEnable := false.B
  reducedReplayLiqReturnPublishControl.io.payloadValid := reducedReplayLiqReturnLretPayload.io.payloadValid
  reducedReplayLiqReturnPublishControl.io.publishReady := reducedReplayLiqReturnPublishReady.io.publishReady
  reducedReplayLiqReturnPublishControl.io.sideEffectsReady := reducedReplayLiqReturnSideEffectReady.io.sideEffectsReady
  reducedReplayLiqReturnPublishRequest.io.publishFire := reducedReplayLiqReturnPublishControl.io.publishFire
  reducedReplayLiqReturnPublishRequest.io.payloadValid := reducedReplayLiqReturnLretPayload.io.payloadValid
  reducedReplayLiqReturnPublishRequest.io.writebackRequired := reducedReplayLiqReturnWritebackCandidate.io.writeValid
  reducedReplayLiqReturnPublishRequest.io.wakeupRequired := reducedReplayLiqReturnWakeupCandidate.io.wakeupRequired
  val reducedReplayLiqReturnLretSinkEntry = Wire(new LoadReplayReturnLretEntry(
    idEntries = p.robEntries,
    addrWidth = p.immWidth,
    pcWidth = p.pcWidth,
    dataWidth = p.immWidth,
    returnPipeCount = 1,
    archRegWidth = p.archRegWidth,
    physRegWidth = p.physRegWidth
  ))
  reducedReplayLiqReturnLretSinkEntry.valid := reducedReplayLiqReturnLretPayload.io.payloadValid
  reducedReplayLiqReturnLretSinkEntry.bid := reducedReplayLiqReturnLretPayload.io.payloadBid
  reducedReplayLiqReturnLretSinkEntry.gid := reducedReplayLiqReturnLretPayload.io.payloadGid
  reducedReplayLiqReturnLretSinkEntry.rid := reducedReplayLiqReturnLretPayload.io.payloadRid
  reducedReplayLiqReturnLretSinkEntry.loadLsId := reducedReplayLiqReturnLretPayload.io.payloadLoadLsId
  reducedReplayLiqReturnLretSinkEntry.pc := reducedReplayLiqReturnLretPayload.io.payloadPc
  reducedReplayLiqReturnLretSinkEntry.addr := reducedReplayLiqReturnLretPayload.io.payloadAddr
  reducedReplayLiqReturnLretSinkEntry.size := reducedReplayLiqReturnLretPayload.io.payloadSize
  reducedReplayLiqReturnLretSinkEntry.dst := reducedReplayLiqReturnLretPayload.io.payloadDst
  reducedReplayLiqReturnLretSinkEntry.data := reducedReplayLiqReturnLretPayload.io.payloadData
  reducedReplayLiqReturnLretSinkEntry.pipeIndex := reducedReplayLiqReturnLretPayload.io.payloadPipeIndex
  reducedReplayLiqReturnLretSinkEntry.specWakeup := reducedReplayLiqReturnLretPayload.io.payloadSpecWakeup
  reducedReplayLiqReturnLretSinkEntry.stackValid := reducedReplayLiqReturnLretPayload.io.payloadStackValid
  reducedReplayLiqReturnLretSink.io.flush := reducedStoreFlush
  reducedReplayLiqReturnLretSink.io.enqueueValid := reducedReplayLiqReturnPublishRequest.io.lretRequest
  reducedReplayLiqReturnLretSink.io.enqueue := reducedReplayLiqReturnLretSinkEntry
  reducedReplayLiqReturnLretSink.io.drainReady := false.B
  val reducedReplayLiqReturnIexPipeOccupiedMask = 1.U(1.W)
  reducedReplayLiqReturnIexDrainPermit.io.enable := reducedLoadReplayLiqAllocEnabled
  reducedReplayLiqReturnIexDrainPermit.io.flush := reducedStoreFlush
  reducedReplayLiqReturnIexDrainPermit.io.sinkValid := reducedReplayLiqReturnLretSink.io.drainValid
  reducedReplayLiqReturnIexDrainPermit.io.pipeOccupiedMask := reducedReplayLiqReturnIexPipeOccupiedMask
  path.io.robStatusLookupValid :=
    reducedLoadReplayLiqAllocEnabled && !reducedStoreFlush &&
      reducedReplayLiqReturnLretSink.io.drainValid && reducedReplayLiqReturnLretSink.io.drain.valid
  path.io.robStatusLookupRid := reducedReplayLiqReturnLretSink.io.drain.rid
  val reducedReplayLiqReturnIexDataRobRowValid = path.io.robStatusLookup.rowValid
  val reducedReplayLiqReturnIexDataRobRowNeedFlush = path.io.robStatusLookup.needFlush
  reducedReplayLiqReturnIexDataCandidate.io.enable := reducedLoadReplayLiqAllocEnabled
  reducedReplayLiqReturnIexDataCandidate.io.flush := reducedStoreFlush
  reducedReplayLiqReturnIexDataCandidate.io.sinkValid := reducedReplayLiqReturnLretSink.io.drainValid
  reducedReplayLiqReturnIexDataCandidate.io.drainReady := reducedReplayLiqReturnIexDrainPermit.io.drainReady
  reducedReplayLiqReturnIexDataCandidate.io.entry := reducedReplayLiqReturnLretSink.io.drain
  reducedReplayLiqReturnIexDataCandidate.io.robRowValid := reducedReplayLiqReturnIexDataRobRowValid
  reducedReplayLiqReturnIexDataCandidate.io.robRowNeedFlush := reducedReplayLiqReturnIexDataRobRowNeedFlush
  reducedReplayLiqReturnRobResolveDataCandidate.io.enable := reducedLoadReplayLiqAllocEnabled
  reducedReplayLiqReturnRobResolveDataCandidate.io.flush := reducedStoreFlush
  reducedReplayLiqReturnRobResolveDataCandidate.io.setMemDataValid :=
    reducedReplayLiqReturnIexDataCandidate.io.setMemDataValid
  reducedReplayLiqReturnRobResolveDataCandidate.io.reducedSingleLane := true.B
  reducedReplayLiqReturnRobResolveDataCandidate.io.memRid :=
    reducedReplayLiqReturnIexDataCandidate.io.memRid
  reducedReplayLiqReturnRobResolveDataCandidate.io.memDst :=
    reducedReplayLiqReturnIexDataCandidate.io.memDst
  reducedReplayLiqReturnRobResolveDataCandidate.io.memData :=
    reducedReplayLiqReturnIexDataCandidate.io.memData
  reducedReplayLiqReturnLaneCompletionCandidate.io.enable := reducedLoadReplayLiqAllocEnabled
  reducedReplayLiqReturnLaneCompletionCandidate.io.flush := reducedStoreFlush
  reducedReplayLiqReturnLaneCompletionCandidate.io.resolveValid :=
    reducedReplayLiqReturnRobResolveDataCandidate.io.readyForPipeInsert
  reducedReplayLiqReturnLaneCompletionCandidate.io.scalarLoadPair := false.B
  reducedReplayLiqReturnLaneCompletionCandidate.io.vectorOrMemMultiLane := false.B
  reducedReplayLiqReturnLaneCompletionCandidate.io.retLaneBefore := 0.U
  reducedReplayLiqReturnLaneCompletionCandidate.io.returnedLaneCount :=
    Mux(reducedReplayLiqReturnRobResolveDataCandidate.io.retLaneIncrement, 1.U, 0.U)
  reducedReplayLiqReturnLaneCompletionCandidate.io.realReqCnt := 1.U
  reducedReplayLiqReturnIexPipeInsertCandidate.io.enable := reducedLoadReplayLiqAllocEnabled
  reducedReplayLiqReturnIexPipeInsertCandidate.io.flush := reducedStoreFlush
  reducedReplayLiqReturnIexPipeInsertCandidate.io.setMemDataValid :=
    reducedReplayLiqReturnLaneCompletionCandidate.io.readyForPipeInsert
  reducedReplayLiqReturnIexPipeInsertCandidate.io.pipeInsertReady :=
    reducedReplayLiqReturnIexDrainPermit.io.drainReady
  reducedReplayLiqReturnIexPipeInsertCandidate.io.pipeInsertIndex :=
    reducedReplayLiqReturnIexDrainPermit.io.selectedPipeIndex
  reducedReplayLiqReturnIexPipeInsertCandidate.io.memBid :=
    reducedReplayLiqReturnIexDataCandidate.io.memBid
  reducedReplayLiqReturnIexPipeInsertCandidate.io.memGid :=
    reducedReplayLiqReturnIexDataCandidate.io.memGid
  reducedReplayLiqReturnIexPipeInsertCandidate.io.memRid :=
    reducedReplayLiqReturnIexDataCandidate.io.memRid
  reducedReplayLiqReturnIexPipeInsertCandidate.io.memLoadLsId :=
    reducedReplayLiqReturnIexDataCandidate.io.memLoadLsId
  reducedReplayLiqReturnIexPipeInsertCandidate.io.memPc :=
    reducedReplayLiqReturnIexDataCandidate.io.memPc
  reducedReplayLiqReturnIexPipeInsertCandidate.io.memAddr :=
    reducedReplayLiqReturnIexDataCandidate.io.memAddr
  reducedReplayLiqReturnIexPipeInsertCandidate.io.memSize :=
    reducedReplayLiqReturnIexDataCandidate.io.memSize
  reducedReplayLiqReturnIexPipeInsertCandidate.io.memDst :=
    reducedReplayLiqReturnIexDataCandidate.io.memDst
  reducedReplayLiqReturnIexPipeInsertCandidate.io.memData :=
    reducedReplayLiqReturnIexDataCandidate.io.memData
  reducedReplayLiqReturnIexPipeInsertCandidate.io.memLoadToUsePipeIndex :=
    reducedReplayLiqReturnIexDataCandidate.io.memPipeIndex
  reducedReplayLiqReturnIexPipeInsertCandidate.io.memSpecWakeup :=
    reducedReplayLiqReturnIexDataCandidate.io.memSpecWakeup
  reducedReplayLiqReturnIexPipeInsertCandidate.io.memStackValid :=
    reducedReplayLiqReturnIexDataCandidate.io.memStackValid
  rf.io.writeValid := rfWritebackArbiter.io.writeValid
  rf.io.writeTag := rfWritebackArbiter.io.writeTag
  rf.io.writeData := rfWritebackArbiter.io.writeData
  val scalarSpWriteback =
    execute.io.completeValid && execute.io.completeRow.wb.valid && execute.io.completeRow.wb.reg === 1.U
  when(io.rfInitValid && io.rfInitArchTag === 1.U) {
    scalarSpValue := io.rfInitData
  }.elsewhen(execute.io.fretStkSpRestoreValid) {
    scalarSpValue := execute.io.fretStkSpRestoreData
  }.elsewhen(scalarSpWriteback) {
    scalarSpValue := execute.io.completeRow.wb.data
  }

  val localReset = backendPipeFlush || io.startValid || io.restartValid
  val localDstAllocT =
    issue.io.enqueueFire && path.io.renamedOut.dst(0).valid && (path.io.renamedOut.dst(0).kind === DestinationKind.T)
  val localDstAllocU =
    issue.io.enqueueFire && path.io.renamedOut.dst(0).valid && (path.io.renamedOut.dst(0).kind === DestinationKind.U)
  val localCompleteT =
    execute.io.completeValid && execute.io.completeRow.wb.valid && (execute.io.completeRow.wb.reg === 31.U)
  val localCompleteU =
    execute.io.completeValid && execute.io.completeRow.wb.valid && (execute.io.completeRow.wb.reg === 30.U)

  val localTPendingAfterComplete =
    Mux(localCompleteT && localTPendingCount =/= 0.U, localTPendingCount - 1.U, localTPendingCount)
  val localUPendingAfterComplete =
    Mux(localCompleteU && localUPendingCount =/= 0.U, localUPendingCount - 1.U, localUPendingCount)

  when(localReset) {
    for (idx <- 0 until localQueueDepth) {
      localTData(idx) := 0.U
      localUData(idx) := 0.U
      localTReady(idx) := false.B
      localUReady(idx) := false.B
    }
    localTPendingCount := 0.U
    localUPendingCount := 0.U
  }.otherwise {
    localTPendingCount := localTPendingAfterComplete + localDstAllocT.asUInt
    localUPendingCount := localUPendingAfterComplete + localDstAllocU.asUInt

    when(localCompleteT) {
      pushLocal(localTData, localTReady, execute.io.completeRow.wb.data)
    }.elsewhen(localCompleteU) {
      pushLocal(localUData, localUReady, execute.io.completeRow.wb.data)
    }
  }

  issue.io.issueReady := execute.io.inReady
  execute.io.inValid := issue.io.issueValid
  execute.io.in := issue.io.issueUop
  execute.io.srcData := issue.io.issueSrcData
  execute.io.loadLookupData := reducedStoreResidentForward.io.loadData
  execute.io.loadLookupWaitBlocked := reducedStoreResidentForward.io.waitBlocked
  execute.io.stackPointerData := scalarSpValue
  execute.io.flushValid := backendPipeFlush
  execute.io.fretStkFallbackTargetValid := path.io.blockMarkerActiveValid && path.io.blockMarkerActiveTarget =/= 0.U
  execute.io.fretStkFallbackTarget := path.io.blockMarkerActiveTarget
  execute.io.fretStkConditionValid := blockBranchTakenValid
  execute.io.fretStkConditionTaken := blockBranchTaken

  val blockBoundaryConsumed = denseSlots.io.outFire && path.io.blockMarkerSkipValid && path.io.blockMarkerBoundary
  when(localReset) {
    blockBranchTakenValid := false.B
    blockBranchTaken := false.B
  }.elsewhen(blockBoundaryConsumed) {
    blockBranchTakenValid := false.B
    blockBranchTaken := false.B
  }.elsewhen(execute.io.branchConditionValid) {
    blockBranchTakenValid := true.B
    blockBranchTaken := execute.io.branchConditionTaken
  }

  io.fetchReqValid := source.io.reqValid
  io.fetchReqPc := source.io.reqPc
  io.fetchRespReady := source.io.respReady
  io.sourceActive := source.io.active
  io.sourceWaitingResponse := source.io.waitingResponse
  io.sourcePacketValid := source.io.packetValid
  io.sourceReqFire := source.io.reqFire
  io.sourceRespFire := source.io.respFire
  io.sourceOutFire := source.io.outFire
  io.sourceAdvanceZero := source.io.advanceZero
  io.sourceAdvanceBytes := effectiveSourceAdvanceBytes
  io.sourceCurrentPc := source.io.currentPc
  io.sourceIssuedPc := source.io.issuedPc
  io.sourceNextPktUid := source.io.nextPktUid

  io.reducedBodyCutActive := reducedBodyCutActive
  io.reducedBodyCutFire := bodyCutRestartFire
  io.reducedBodyCutAdvanceBytes := Mux(reducedBodyCutActive, effectiveSourceAdvanceBytes, 0.U)
  io.reducedBfuStaticGeometryValid := staticBfuGeometry.io.geometryValid
  io.reducedBfuStaticHeaderActive := staticBfuGeometry.io.headerActive
  io.reducedBfuStaticLearnedFire := staticBfuGeometry.io.learnedFire
  io.reducedBfuStaticResolvedLearnedFire := staticBfuGeometry.io.resolvedLearnedFire
  io.reducedBfuResolvedBodyEndAccepted := resolvedBfuBodyEnd.io.accepted
  io.reducedBfuResolvedBodyEndHeaderMismatch := resolvedBfuBodyEnd.io.headerMismatch
  io.reducedBfuResolvedBodyEndInactiveDrop := resolvedBfuBodyEnd.io.inactiveDrop
  io.reducedBfuResolvedBodyEndFlushDrop := resolvedBfuBodyEnd.io.flushDrop
  io.reducedBfuResolvedBodyEndUnderflow := resolvedBfuBodyEnd.io.bodyEndUnderflow
  io.reducedBfuResolvedBodyEndSourceRuntimeSelected := resolvedBfuBodyEndSource.io.selectedRuntime
  io.reducedBfuResolvedBodyEndSourceReplaySelected := resolvedBfuBodyEndSource.io.selectedReplay
  io.reducedBfuResolvedBodyEndSourceRuntimeReplayComparable := resolvedBfuBodyEndSource.io.runtimeReplayComparable
  io.reducedBfuResolvedBodyEndSourceRuntimeReplayMatch := resolvedBfuBodyEndSource.io.runtimeReplayMatch
  io.reducedBfuResolvedBodyEndSourceRuntimeReplayMismatch :=
    resolvedBfuBodyEndSource.io.runtimeReplayHeaderMismatch ||
      resolvedBfuBodyEndSource.io.runtimeReplayHSizeMismatch ||
      resolvedBfuBodyEndSource.io.runtimeReplayBodyEndMismatch
  io.reducedBfuResolvedBodyEndSourceRuntimeFeedbackFire := localBfuCutFeedbackPending.io.captureFire
  io.reducedBfuResolvedBodyEndSourceRuntimePending := localBfuCutFeedbackPending.io.pending
  io.reducedBfuResolvedBodyEndSourceRuntimePendingConsumeFire := localBfuCutFeedbackPending.io.consumeFire
  io.reducedBfuResolvedBodyEndSourceRuntimePendingDropMismatch := localBfuCutFeedbackPending.io.dropMismatch
  io.reducedBfuResolvedBodyEndSourceRuntimePendingCandidateComparable :=
    localBfuCutFeedbackPending.io.candidateComparable
  io.reducedBfuResolvedBodyEndSourceRuntimePendingCandidateMatch :=
    localBfuCutFeedbackPending.io.candidateMatch
  io.reducedBfuResolvedBodyEndSourceRuntimePendingCandidateMismatch :=
    localBfuCutFeedbackPending.io.candidateMismatch
  io.reducedBfuPendingRuntimeCandidateValid := pendingRuntimeBodyEndCandidate.io.candidateValid
  io.reducedBfuPendingRuntimeCandidatePendingWithoutActiveHeader :=
    pendingRuntimeBodyEndCandidate.io.pendingWithoutActiveHeader
  io.reducedBfuPendingRuntimeCandidateActiveHeaderMismatch :=
    pendingRuntimeBodyEndCandidate.io.activeHeaderMismatch
  io.reducedBfuPendingRuntimeCandidateReplayComparable :=
    pendingRuntimeBodyEndCandidate.io.replayComparable
  io.reducedBfuPendingRuntimeCandidateReplayMatch :=
    pendingRuntimeBodyEndCandidate.io.replayMatch
  io.reducedBfuPendingRuntimeCandidateReplayMismatch :=
    pendingRuntimeBodyEndCandidate.io.replayHeaderMismatch ||
      pendingRuntimeBodyEndCandidate.io.replayHSizeMismatch ||
      pendingRuntimeBodyEndCandidate.io.replayBodyEndMismatch
  io.reducedBfuPromotedRuntimeBodyEndOraclePending := promotedRuntimeBodyEndOracle.io.pending
  io.reducedBfuPromotedRuntimeBodyEndOracleCaptureFire := promotedRuntimeBodyEndOracle.io.captureFire
  io.reducedBfuPromotedRuntimeBodyEndOracleReplayComparable :=
    promotedRuntimeBodyEndOracle.io.replayComparable
  io.reducedBfuPromotedRuntimeBodyEndOracleReplayMatch :=
    promotedRuntimeBodyEndOracle.io.replayMatch
  io.reducedBfuPromotedRuntimeBodyEndOracleReplayMismatch :=
    promotedRuntimeBodyEndOracle.io.replayHeaderMismatch ||
      promotedRuntimeBodyEndOracle.io.replayHSizeMismatch ||
      promotedRuntimeBodyEndOracle.io.replayBodyEndMismatch
  io.reducedBfuPromotedRuntimeBodyEndOracleOverwritePending :=
    promotedRuntimeBodyEndOracle.io.overwritePending
  io.reducedBfuStaticExternalComparable := staticExternalComparable
  io.reducedBfuStaticExternalMatch := staticExternalMatch
  io.reducedBfuStaticExternalMismatch :=
    staticExternalHeaderMismatch || staticExternalHSizeMismatch || staticExternalBSizeMismatch
  io.reducedBfuStaticExternalHeaderMismatch := staticExternalHeaderMismatch
  io.reducedBfuStaticExternalHSizeMismatch := staticExternalHSizeMismatch
  io.reducedBfuStaticExternalBSizeMismatch := staticExternalBSizeMismatch
  io.reducedBfuBodyCutArmComparable := bodyCutArm.io.comparable
  io.reducedBfuBodyCutArmAccepted := bodyCutArm.io.accepted
  io.reducedBfuBodyCutArmMismatch :=
    bodyCutArm.io.headerMismatch || bodyCutArm.io.hsizeMismatch || bodyCutArm.io.bsizeMismatch
  io.reducedBfuBodyCutArmHeaderMismatch := bodyCutArm.io.headerMismatch
  io.reducedBfuBodyCutArmHSizeMismatch := bodyCutArm.io.hsizeMismatch
  io.reducedBfuBodyCutArmBSizeMismatch := bodyCutArm.io.bsizeMismatch
  io.reducedBfuLocalBodyWindowActive := localBfuBodyWindow.io.active
  io.reducedBfuLocalBodyWindowArmFire := localBfuBodyWindow.io.armFire
  io.reducedBfuLocalBodyWindowReleaseFire := localBfuBodyWindow.io.releaseFire
  io.reducedBfuLocalBodyWindowArmSlot := localBfuBodyWindow.io.armSlot
  io.f4ValidMask := frontendValidMask
  io.f4SlotCount := frontendSlotCount
  io.denseSlotQueueInFire := denseSlots.io.inFire
  io.denseSlotQueueOutFire := denseSlots.io.outFire
  io.denseSlotQueueInSlotCount := denseSlots.io.inSlotCount
  io.denseSlotQueueCount := denseSlots.io.count
  io.denseSlotQueueHeadSlot := denseSlots.io.headSlotIndex
  io.denseSlotQueueFull := denseSlots.io.full
  io.denseSlotQueueEmpty := denseSlots.io.empty
  io.admittedMarkerDrainBarrier := admittedMarkerDrainBarrier
  io.decodeReady := path.io.decodeReady
  io.decodeQueuePushReady := path.io.decodeQueuePushReady
  io.decodeAllocReady := path.io.decodeAllocReady
  io.decodeGprReservationReady := path.io.decodeGprReservationReady
  io.decodeSelectedClosesActiveRedirect := path.io.decodeSelectedClosesActiveRedirect
  io.decodeSelectedNeedsGprReservation := path.io.decodeSelectedNeedsGprReservation
  io.gprReservationCount := path.io.gprReservationCount
  io.gprReservationNeed := path.io.gprReservationNeed
  io.selectedValid := path.io.selectedValid
  io.selectedRobValue := path.io.selectedRobValue
  io.selectedBlockBid := path.io.selectedBlockBid
  io.blockMarkerSkipFire := denseSlots.io.outFire && path.io.blockMarkerSkipValid
  io.blockMarkerSkipValid := path.io.blockMarkerSkipValid
  io.blockMarkerMixedPacket := path.io.blockMarkerMixedPacket
  io.blockMarkerBoundary := path.io.blockMarkerBoundary
  io.blockMarkerStop := path.io.blockMarkerStop
  io.blockMarkerPc := path.io.blockMarkerPc
  io.blockMarkerInsn := path.io.blockMarkerInsn
  io.blockMarkerLen := path.io.blockMarkerLen
  io.blockMarkerTarget := path.io.blockMarkerTarget
  io.blockMarkerAllocReady := path.io.blockMarkerAllocReady
  io.blockMarkerLifecycleConflict := path.io.blockMarkerLifecycleConflict
  io.blockMarkerAllocFire := path.io.blockMarkerAllocFire
  io.blockMarkerAllocBid := path.io.blockMarkerAllocBid
  io.blockMarkerActiveValid := path.io.blockMarkerActiveValid
  io.blockMarkerActiveBid := path.io.blockMarkerActiveBid
  io.blockMarkerActiveTarget := path.io.blockMarkerActiveTarget
  io.blockMarkerStopRedirectValid := path.io.blockMarkerStopRedirectValid
  io.blockMarkerStopRedirectPc := path.io.blockMarkerStopRedirectPc
  io.robMarkerRetireSourceLifecycleReady := path.io.robMarkerRetireSourceLifecycleReady
  io.robMarkerRetireSourceLifecycleFire := path.io.robMarkerRetireSourceLifecycleFire
  io.robMarkerRetireSourceLifecycleBoundaryFire := path.io.robMarkerRetireSourceLifecycleBoundaryFire
  io.robMarkerRetireSourceLifecycleStopFire := path.io.robMarkerRetireSourceLifecycleStopFire
  io.robMarkerRetireSourceValid := path.io.robMarkerRetireSource.valid
  io.robMarkerRetireSourceBoundary := path.io.robMarkerRetireSource.isBoundary
  io.robMarkerRetireSourceStop := path.io.robMarkerRetireSource.isStop
  io.robMarkerRetireSourceLast := path.io.robMarkerRetireSource.isLast
  io.robMarkerRetireSourceBidValid := path.io.robMarkerRetireSource.bid.valid
  io.robMarkerRetireSourceBidWrap := path.io.robMarkerRetireSource.bid.wrap
  io.robMarkerRetireSourceBidValue := path.io.robMarkerRetireSource.bid.value
  io.robMarkerRetireSourceRidValid := path.io.robMarkerRetireSource.rid.valid
  io.robMarkerRetireSourceRidWrap := path.io.robMarkerRetireSource.rid.wrap
  io.robMarkerRetireSourceRidValue := path.io.robMarkerRetireSource.rid.value
  io.robMarkerRetireSourceStid := path.io.robMarkerRetireSource.stid
  io.robMarkerRetireSourceBlockBidValid := path.io.robMarkerRetireSource.blockBidValid
  io.robMarkerRetireSourceBlockBid := path.io.robMarkerRetireSource.blockBid
  io.robMarkerRetireSourceBoundaryTarget := path.io.robMarkerRetireSource.boundaryTarget
  io.decRenPushFire := path.io.decRenPushFire
  io.decRenPopFire := path.io.decRenPopFire
  io.decRenCount := path.io.decRenCount
  io.decRenValid := path.io.decRenValid
  io.decRenHeadPc := path.io.decRenHeadPc
  io.decRenHeadRidValid := path.io.decRenHeadRidValid
  io.decRenHeadRidValue := path.io.decRenHeadRidValue
  io.renamedOutValid := path.io.renamedOutValid
  io.renamedAccepted := path.io.accepted
  io.executeAccepted := execute.io.accepted
  io.executeBusy := execute.io.busy
  io.executeCompleteValid := execute.io.completeValid
  io.executeCompleteRobValue := execute.io.completeRobValue
  io.executeLoadWaitHold := execute.io.loadWaitHold
  io.loadLookupValid := loadLookupArbiter.io.lookupValid
  io.loadLookupAddr := loadLookupArbiter.io.lookupAddr
  io.loadLookupPc := loadLookupArbiter.io.lookupPc
  io.loadLookupDstValid := executeLoadLookupDst.valid
  io.loadLookupDstKind := executeLoadLookupDst.kind.asUInt
  io.loadLookupDstArchTag := executeLoadLookupDst.archTag
  io.loadLookupDstRelTag := executeLoadLookupDst.relTag
  io.loadLookupDstPhysTag := executeLoadLookupDst.physTag
  io.loadLookupDstOldPhysTag := executeLoadLookupDst.oldPhysTag
  io.loadLookupExecuteGranted := loadLookupArbiter.io.executeGranted
  io.loadLookupReplayGranted := loadLookupArbiter.io.replayGranted
  io.reducedStoreDispatchEnabled := useReducedStoreDispatchStq.B
  io.reducedStoreExecCompleteStoreValid := storeExecBridge.io.completeStoreValid
  io.reducedStoreExecCaptureFire := storeExecBridge.io.captureFire
  io.reducedStoreExecCaptureBlocked := storeExecBridge.io.captureBlocked
  io.reducedStoreExecCaptureDuplicate := storeExecBridge.io.captureDuplicate
  io.reducedStoreExecStaMatch := storeExecBridge.io.staMatch
  io.reducedStoreExecStdMatch := storeExecBridge.io.stdMatch
  io.reducedStoreExecValidMask := storeExecBridge.io.validMask
  io.reducedStoreExecBufferCount := storeExecBridge.io.bufferCount
  io.reducedStoreStaExecValid := storeExecBridge.io.staExec.valid
  io.reducedStoreStdExecValid := storeExecBridge.io.stdExec.valid
  io.reducedStoreCommitStoreSeen := storeCommitOwner.io.commitStoreSeen
  io.reducedStoreCommitStoreMatched := storeCommitOwner.io.commitStoreMatched
  io.reducedStoreCommitStoreUnmatched := storeCommitOwner.io.commitStoreUnmatched
  io.reducedStoreCommitMatchMask := storeCommitOwner.io.matchMask
  io.reducedStoreCommitPendingMarkMask := storeCommitOwner.io.pendingMarkMask
  io.reducedStoreCommitPendingFreeMask := storeCommitOwner.io.pendingFreeMask
  io.reducedStoreCommitPendingMarkCount := storeCommitOwner.io.pendingMarkCount
  io.reducedStoreCommitPendingFreeCount := storeCommitOwner.io.pendingFreeCount
  io.reducedStoreCommitMarkValid := storeCommitOwner.io.markCommitValid
  io.reducedStoreCommitMarkIndex := storeCommitOwner.io.markCommitIndex
  io.reducedStoreCommitMarkAccepted := path.io.storeMarkCommitAccepted
  io.reducedStoreCommitMarkIgnored := path.io.storeMarkCommitIgnored
  io.reducedStoreCommitMarkBlocked := storeCommitOwner.io.markBlocked
  io.reducedStoreCommitFreeValid := path.io.storeCommitFreeMaskValid
  io.reducedStoreCommitFreeIndex := 0.U
  io.reducedStoreCommitFreeAccepted := path.io.storeCommitFreeAcceptedMask.orR
  io.reducedStoreCommitFreeIgnored := path.io.storeCommitFreeIgnoredMask.orR
  io.reducedStoreCommitFreeAcceptedMask := path.io.storeCommitFreeAcceptedMask
  io.reducedStoreCommitFreeIgnoredMask := path.io.storeCommitFreeIgnoredMask
  io.reducedStoreCommitFreeCount := path.io.storeCommitFreeCount
  io.reducedStoreCommitFreeBlocked := storeCommitOwner.io.freeBlocked
  io.reducedStoreDrainEnqueueAccepted := reducedStoreCommitDrain.io.enqueueAccepted
  io.reducedStoreDrainEnqueueDuplicate := reducedStoreCommitDrain.io.enqueueDuplicate
  io.reducedStoreDrainIssueValidMask := reducedStoreCommitDrain.io.issueValidMask
  io.reducedStoreDrainIssueCount := reducedStoreCommitDrain.io.issueCount
  io.reducedStoreDrainEarlyFreeMask := reducedStoreCommitDrain.io.commitFreeMask
  io.reducedStoreDrainQueueCount := reducedStoreCommitDrain.io.queueCount
  io.reducedStoreDrainEmpty := reducedStoreCommitDrain.io.empty
  io.reducedStoreDrainOrderError := reducedStoreCommitDrain.io.orderError
  io.reducedStoreScbReadyForDrain := reducedStoreScbReadyForDrain
  io.reducedStoreScbAcceptedMask := reducedStoreScb.io.acceptedMask
  io.reducedStoreScbStalledMask := reducedStoreScb.io.stalledMask
  io.reducedStoreScbCommitFreeMaskValid := reducedStoreScb.io.commitFreeMaskValid
  io.reducedStoreScbCommitFreeMask := reducedStoreScb.io.commitFreeMask
  io.reducedStoreScbCommitFreeCount := reducedStoreScb.io.commitFreeCount
  io.reducedStoreScbValidMask := reducedStoreScb.io.validMask
  io.reducedStoreScbEntryCount := reducedStoreScb.io.entryCount
  io.reducedStoreMemoryValidMask := reducedStoreMemoryOverlay.io.validMask
  io.reducedStoreMemoryLineCount := reducedStoreMemoryOverlay.io.lineCount
  io.reducedStoreMemoryLoadForwardMask := reducedStoreMemoryOverlay.io.loadForwardMask
  io.reducedStoreMemoryStoreDroppedMask := reducedStoreMemoryOverlay.io.storeDroppedMask
  io.reducedStoreResidentForwardMask := reducedStoreResidentForward.io.loadForwardMask
  io.reducedStoreResidentWaitMask := reducedStoreResidentForward.io.waitMask
  io.reducedStoreResidentEligibleMask := reducedStoreResidentForward.io.eligibleStoreMask
  io.reducedStoreResidentReadyForward := reducedStoreResidentForward.io.readyForward
  io.reducedStoreResidentWaitBlocked := reducedStoreResidentForward.io.waitBlocked
  io.reducedStoreResidentWaitStoreValid := reducedStoreResidentForward.io.waitStore.valid
  io.reducedStoreResidentWaitStoreIndex := reducedStoreResidentForward.io.waitStore.storeIndex
  io.reducedStoreResidentWaitStoreBidValid := reducedStoreResidentForward.io.waitStore.storeId.valid
  io.reducedStoreResidentWaitStoreBidWrap := reducedStoreResidentForward.io.waitStore.storeId.wrap
  io.reducedStoreResidentWaitStoreBidValue := reducedStoreResidentForward.io.waitStore.storeId.value
  io.reducedStoreResidentWaitStoreLsIdValid := reducedStoreResidentForward.io.waitStore.storeLsId.valid
  io.reducedStoreResidentWaitStoreLsIdWrap := reducedStoreResidentForward.io.waitStore.storeLsId.wrap
  io.reducedStoreResidentWaitStoreLsIdValue := reducedStoreResidentForward.io.waitStore.storeLsId.value
  io.reducedStoreResidentWaitStorePc := reducedStoreResidentForward.io.waitStore.pc
  io.reducedStoreResidentLoadCrossesLine := reducedStoreResidentForward.io.loadCrossesLine
  io.reducedStoreResidentReplayWakeValid := reducedStoreResidentReplayWakeup.io.wakeValid
  io.reducedStoreResidentReplayWakeSelected := reducedStoreResidentReplayWakeup.io.selectedRowValid
  io.reducedStoreResidentReplayWakeIdentityMatch := reducedStoreResidentReplayWakeup.io.identityMatch
  io.reducedStoreResidentReplayWakeReady := reducedStoreResidentReplayWakeup.io.selectedRowReady
  io.reducedStoreResidentReplayWakeCrossesLine := reducedStoreResidentReplayWakeup.io.selectedRowCrossesLine
  io.reducedStoreResidentReplayWakeLineAddr := reducedStoreResidentReplayWakeup.io.wake.lineAddr
  io.reducedStoreResidentReplayWakeByteMask := reducedStoreResidentReplayWakeup.io.wakeByteMask
  io.reducedLoadWaitReplayActive := reducedLoadWaitReplaySlot.io.active
  io.reducedLoadWaitReplayCaptureAccepted := reducedLoadWaitReplaySlot.io.captureAccepted
  io.reducedLoadWaitReplayClearValid := reducedLoadWaitReplaySlot.io.waitStoreClear
  io.reducedLoadWaitReplayStoredWaitValid := reducedLoadWaitReplaySlot.io.storedWaitStore.valid
  io.reducedLoadWaitReplayStoredWaitPc := reducedLoadWaitReplaySlot.io.storedWaitStore.pc
  io.reducedLoadWaitReplayRelaunchValid := reducedLoadWaitReplaySlot.io.relaunch.valid
  io.reducedLoadWaitReplayRelaunchPc := reducedLoadWaitReplaySlot.io.relaunch.pc
  io.reducedLoadWaitReplayRelaunchAddr := reducedLoadWaitReplaySlot.io.relaunch.addr
  io.reducedLoadWaitReplayRelaunchSize := reducedLoadWaitReplaySlot.io.relaunch.size
  io.reducedLoadWaitReplayRelaunchReturnSignExtend := reducedLoadWaitReplaySlot.io.relaunch.returnSignExtend
  io.reducedLoadWaitReplayRelaunchDstValid := reducedLoadWaitReplaySlot.io.relaunch.dst.valid
  io.reducedLoadWaitReplayRelaunchDstKind := reducedLoadWaitReplaySlot.io.relaunch.dst.kind.asUInt
  io.reducedLoadWaitReplayRelaunchDstArchTag := reducedLoadWaitReplaySlot.io.relaunch.dst.archTag
  io.reducedLoadWaitReplayRelaunchDstRelTag := reducedLoadWaitReplaySlot.io.relaunch.dst.relTag
  io.reducedLoadWaitReplayRelaunchDstPhysTag := reducedLoadWaitReplaySlot.io.relaunch.dst.physTag
  io.reducedLoadWaitReplayRelaunchDstOldPhysTag := reducedLoadWaitReplaySlot.io.relaunch.dst.oldPhysTag
  io.reducedLoadWaitReplayRelaunchBidValid := reducedLoadWaitReplaySlot.io.relaunch.bid.valid
  io.reducedLoadWaitReplayRelaunchBidWrap := reducedLoadWaitReplaySlot.io.relaunch.bid.wrap
  io.reducedLoadWaitReplayRelaunchBidValue := reducedLoadWaitReplaySlot.io.relaunch.bid.value
  io.reducedLoadWaitReplayRelaunchGidValid := reducedLoadWaitReplaySlot.io.relaunch.gid.valid
  io.reducedLoadWaitReplayRelaunchGidWrap := reducedLoadWaitReplaySlot.io.relaunch.gid.wrap
  io.reducedLoadWaitReplayRelaunchGidValue := reducedLoadWaitReplaySlot.io.relaunch.gid.value
  io.reducedLoadWaitReplayRelaunchRidValid := reducedLoadWaitReplaySlot.io.relaunch.rid.valid
  io.reducedLoadWaitReplayRelaunchRidWrap := reducedLoadWaitReplaySlot.io.relaunch.rid.wrap
  io.reducedLoadWaitReplayRelaunchRidValue := reducedLoadWaitReplaySlot.io.relaunch.rid.value
  io.reducedLoadWaitReplayRelaunchLsIdValid := reducedLoadWaitReplaySlot.io.relaunch.loadLsId.valid
  io.reducedLoadWaitReplayRelaunchLsIdWrap := reducedLoadWaitReplaySlot.io.relaunch.loadLsId.wrap
  io.reducedLoadWaitReplayRelaunchLsIdValue := reducedLoadWaitReplaySlot.io.relaunch.loadLsId.value
  io.reducedLoadWaitReplayRelaunchYoungestStoreIdValid := reducedLoadWaitReplaySlot.io.relaunch.youngestStoreId.valid
  io.reducedLoadWaitReplayRelaunchYoungestStoreIdWrap := reducedLoadWaitReplaySlot.io.relaunch.youngestStoreId.wrap
  io.reducedLoadWaitReplayRelaunchYoungestStoreIdValue := reducedLoadWaitReplaySlot.io.relaunch.youngestStoreId.value
  io.reducedLoadWaitReplayRelaunchYoungestStoreLsIdValid := reducedLoadWaitReplaySlot.io.relaunch.youngestStoreLsId.valid
  io.reducedLoadWaitReplayRelaunchYoungestStoreLsIdWrap := reducedLoadWaitReplaySlot.io.relaunch.youngestStoreLsId.wrap
  io.reducedLoadWaitReplayRelaunchYoungestStoreLsIdValue := reducedLoadWaitReplaySlot.io.relaunch.youngestStoreLsId.value
  io.reducedLoadReplayQueueEnqueueReady := reducedLoadReplayRelaunchQueue.io.enqueueReady
  io.reducedLoadReplayQueueEnqueueAccepted := reducedLoadReplayRelaunchQueue.io.enqueueAccepted
  io.reducedLoadReplayQueueEnqueueDropped := reducedLoadReplayRelaunchQueue.io.enqueueDropped
  io.reducedLoadReplayQueueOutValid := reducedLoadReplayRelaunchQueue.io.outValid
  io.reducedLoadReplayQueueOutFire := reducedLoadReplayRelaunchQueue.io.outFire
  io.reducedLoadReplayQueuePending := reducedLoadReplayRelaunchQueue.io.pending
  io.reducedLoadReplayQueueFull := reducedLoadReplayRelaunchQueue.io.full
  io.reducedLoadReplayQueueCount := reducedLoadReplayRelaunchQueue.io.count
  io.reducedLoadReplayQueueOutPc := reducedLoadReplayRelaunchQueue.io.out.pc
  io.reducedLoadReplayQueueOutAddr := reducedLoadReplayRelaunchQueue.io.out.addr
  io.reducedLoadReplayQueueOutSize := reducedLoadReplayRelaunchQueue.io.out.size
  io.reducedLoadReplayQueueOutReturnSignExtend := reducedLoadReplayRelaunchQueue.io.out.returnSignExtend
  io.reducedLoadReplayQueueOutDstValid := reducedLoadReplayRelaunchQueue.io.out.dst.valid
  io.reducedLoadReplayQueueOutDstKind := reducedLoadReplayRelaunchQueue.io.out.dst.kind.asUInt
  io.reducedLoadReplayQueueOutDstArchTag := reducedLoadReplayRelaunchQueue.io.out.dst.archTag
  io.reducedLoadReplayQueueOutDstRelTag := reducedLoadReplayRelaunchQueue.io.out.dst.relTag
  io.reducedLoadReplayQueueOutDstPhysTag := reducedLoadReplayRelaunchQueue.io.out.dst.physTag
  io.reducedLoadReplayQueueOutDstOldPhysTag := reducedLoadReplayRelaunchQueue.io.out.dst.oldPhysTag
  io.reducedLoadReplayQueueOutBidValid := reducedLoadReplayRelaunchQueue.io.out.bid.valid
  io.reducedLoadReplayQueueOutBidWrap := reducedLoadReplayRelaunchQueue.io.out.bid.wrap
  io.reducedLoadReplayQueueOutBidValue := reducedLoadReplayRelaunchQueue.io.out.bid.value
  io.reducedLoadReplayQueueOutGidValid := reducedLoadReplayRelaunchQueue.io.out.gid.valid
  io.reducedLoadReplayQueueOutGidWrap := reducedLoadReplayRelaunchQueue.io.out.gid.wrap
  io.reducedLoadReplayQueueOutGidValue := reducedLoadReplayRelaunchQueue.io.out.gid.value
  io.reducedLoadReplayQueueOutRidValid := reducedLoadReplayRelaunchQueue.io.out.rid.valid
  io.reducedLoadReplayQueueOutRidWrap := reducedLoadReplayRelaunchQueue.io.out.rid.wrap
  io.reducedLoadReplayQueueOutRidValue := reducedLoadReplayRelaunchQueue.io.out.rid.value
  io.reducedLoadReplayQueueOutLsIdValid := reducedLoadReplayRelaunchQueue.io.out.loadLsId.valid
  io.reducedLoadReplayQueueOutLsIdWrap := reducedLoadReplayRelaunchQueue.io.out.loadLsId.wrap
  io.reducedLoadReplayQueueOutLsIdValue := reducedLoadReplayRelaunchQueue.io.out.loadLsId.value
  io.reducedLoadReplayQueueOutYoungestStoreIdValid := reducedLoadReplayRelaunchQueue.io.out.youngestStoreId.valid
  io.reducedLoadReplayQueueOutYoungestStoreIdWrap := reducedLoadReplayRelaunchQueue.io.out.youngestStoreId.wrap
  io.reducedLoadReplayQueueOutYoungestStoreIdValue := reducedLoadReplayRelaunchQueue.io.out.youngestStoreId.value
  io.reducedLoadReplayQueueOutYoungestStoreLsIdValid := reducedLoadReplayRelaunchQueue.io.out.youngestStoreLsId.valid
  io.reducedLoadReplayQueueOutYoungestStoreLsIdWrap := reducedLoadReplayRelaunchQueue.io.out.youngestStoreLsId.wrap
  io.reducedLoadReplayQueueOutYoungestStoreLsIdValue := reducedLoadReplayRelaunchQueue.io.out.youngestStoreLsId.value
  io.reducedLoadReplayDrainConsumeReady := reducedLoadReplayCompletionDrain.io.consumeReady
  io.reducedLoadReplayDrainMatchValid := reducedLoadReplayCompletionDrain.io.matchValid
  io.reducedLoadReplayDrainMismatch := reducedLoadReplayCompletionDrain.io.mismatch
  io.reducedLoadReplayDrainPcMismatch := reducedLoadReplayCompletionDrain.io.pcMismatch
  io.reducedLoadReplayDrainAddrMismatch := reducedLoadReplayCompletionDrain.io.addrMismatch
  io.reducedLoadReplayDrainSizeMismatch := reducedLoadReplayCompletionDrain.io.sizeMismatch
  io.reducedLoadReplayDrainBidMismatch := reducedLoadReplayCompletionDrain.io.bidMismatch
  io.reducedLoadReplayDrainGidMismatch := reducedLoadReplayCompletionDrain.io.gidMismatch
  io.reducedLoadReplayDrainRidMismatch := reducedLoadReplayCompletionDrain.io.ridMismatch
  io.reducedLoadReplayDrainLsIdMismatch := reducedLoadReplayCompletionDrain.io.lsIdMismatch
  io.reducedLoadReplayLiqAllocEnabled := reducedLoadReplayLiqAllocEnabled
  io.reducedLoadReplayLiqAllocConsumeReady := reducedLoadReplayLiqAllocPath.io.candidateConsumeReady
  io.reducedLoadReplayLiqAllocCandidateUsable := reducedLoadReplayLiqAllocPath.io.candidateUsable
  io.reducedLoadReplayLiqAllocBlockedByAlloc := reducedLoadReplayLiqAllocPath.io.candidateBlockedByAlloc
  io.reducedLoadReplayLiqAllocValid := reducedLoadReplayLiqAllocPath.io.allocValid
  io.reducedLoadReplayLiqAllocReady := reducedLoadReplayLiqAllocPath.io.allocReady
  io.reducedLoadReplayLiqAllocAccepted := reducedLoadReplayLiqAllocPath.io.allocAccepted
  io.reducedLoadReplayLiqAllocIndex := reducedLoadReplayLiqAllocPath.io.allocIndex
  io.reducedLoadReplayLiqAllocLoadIdValid := reducedLoadReplayLiqAllocPath.io.allocLoadId.valid
  io.reducedLoadReplayLiqAllocLoadIdWrap := reducedLoadReplayLiqAllocPath.io.allocLoadId.wrap
  io.reducedLoadReplayLiqAllocLoadIdValue := reducedLoadReplayLiqAllocPath.io.allocLoadId.value
  io.reducedLoadReplayLiqOccupiedMask := reducedLoadReplayLiqAllocPath.io.occupiedMask
  io.reducedLoadReplayLiqWaitMask := reducedLoadReplayLiqAllocPath.io.waitMask
  io.reducedLoadReplayLiqLaunchWaitMask := reducedLoadReplayLiqAllocPath.io.launchWaitMask
  io.reducedLoadReplayLiqLaunchWaitStoreBlockedMask := reducedLoadReplayLiqAllocPath.io.launchWaitStoreBlockedMask
  io.reducedLoadReplayLiqLaunchTileBlockedMask := reducedLoadReplayLiqAllocPath.io.launchTileBlockedMask
  io.reducedLoadReplayLiqLaunchUnblockedWaitMask := reducedLoadReplayLiqAllocPath.io.launchUnblockedWaitMask
  io.reducedLoadReplayLiqLaunchRequestCompleteMask := reducedLoadReplayLiqAllocPath.io.launchRequestCompleteMask
  io.reducedLoadReplayLiqLaunchDataHitMask := reducedLoadReplayLiqAllocPath.io.launchDataHitMask
  io.reducedLoadReplayLiqLaunchCandidateMask := reducedLoadReplayLiqAllocPath.io.launchCandidateMask
  io.reducedLoadReplayLiqLaunchMask := reducedLoadReplayLiqAllocPath.io.launchMask
  io.reducedLoadReplayLiqLaunchValid := reducedLoadReplayLiqAllocPath.io.launchValid
  io.reducedLoadReplayLiqLaunchIndex := reducedLoadReplayLiqAllocPath.io.launchIndex
  io.reducedLoadReplayLiqLaunchDriveValid := reducedLoadReplayLiqAllocPath.io.launchDriveValid
  io.reducedLoadReplayLiqLaunchReady := reducedLoadReplayLiqAllocPath.io.launchReady
  io.reducedLoadReplayLiqLaunchAccepted := reducedLoadReplayLiqAllocPath.io.launchAccepted
  io.reducedLoadReplayLiqLaunchCandidateCount := reducedLoadReplayLiqAllocPath.io.launchCandidateCount
  io.reducedLoadReplayLiqLaunchSelectedLoadIdValid := reducedLoadReplayLiqAllocPath.io.launchSelectedLoadId.valid
  io.reducedLoadReplayLiqLaunchSelectedLoadIdWrap := reducedLoadReplayLiqAllocPath.io.launchSelectedLoadId.wrap
  io.reducedLoadReplayLiqLaunchSelectedLoadIdValue := reducedLoadReplayLiqAllocPath.io.launchSelectedLoadId.value
  io.reducedLoadReplayLiqLaunchSelectedBidValid := reducedLoadReplayLiqAllocPath.io.launchSelectedBid.valid
  io.reducedLoadReplayLiqLaunchSelectedBidWrap := reducedLoadReplayLiqAllocPath.io.launchSelectedBid.wrap
  io.reducedLoadReplayLiqLaunchSelectedBidValue := reducedLoadReplayLiqAllocPath.io.launchSelectedBid.value
  io.reducedLoadReplayLiqLaunchSelectedGidValid := reducedLoadReplayLiqAllocPath.io.launchSelectedGid.valid
  io.reducedLoadReplayLiqLaunchSelectedGidWrap := reducedLoadReplayLiqAllocPath.io.launchSelectedGid.wrap
  io.reducedLoadReplayLiqLaunchSelectedGidValue := reducedLoadReplayLiqAllocPath.io.launchSelectedGid.value
  io.reducedLoadReplayLiqLaunchSelectedRidValid := reducedLoadReplayLiqAllocPath.io.launchSelectedRid.valid
  io.reducedLoadReplayLiqLaunchSelectedRidWrap := reducedLoadReplayLiqAllocPath.io.launchSelectedRid.wrap
  io.reducedLoadReplayLiqLaunchSelectedRidValue := reducedLoadReplayLiqAllocPath.io.launchSelectedRid.value
  io.reducedLoadReplayLiqLaunchSelectedLoadLsIdValid := reducedLoadReplayLiqAllocPath.io.launchSelectedLoadLsId.valid
  io.reducedLoadReplayLiqLaunchSelectedLoadLsIdWrap := reducedLoadReplayLiqAllocPath.io.launchSelectedLoadLsId.wrap
  io.reducedLoadReplayLiqLaunchSelectedLoadLsIdValue := reducedLoadReplayLiqAllocPath.io.launchSelectedLoadLsId.value
  io.reducedLoadReplayLiqLaunchSelectedPc := reducedLoadReplayLiqAllocPath.io.launchSelectedPc
  io.reducedLoadReplayLiqLaunchSelectedAddr := reducedLoadReplayLiqAllocPath.io.launchSelectedAddr
  io.reducedLoadReplayLiqLaunchSelectedSize := reducedLoadReplayLiqAllocPath.io.launchSelectedSize
  io.reducedLoadReplayLiqLaunchSelectedReturnSignExtend := reducedLoadReplayLiqAllocPath.io.launchSelectedReturnSignExtend
  io.reducedLoadReplayLiqLaunchSelectedDstValid := reducedLoadReplayLiqAllocPath.io.launchSelectedDst.valid
  io.reducedLoadReplayLiqLaunchSelectedDstKind := reducedLoadReplayLiqAllocPath.io.launchSelectedDst.kind.asUInt
  io.reducedLoadReplayLiqLaunchSelectedDstArchTag := reducedLoadReplayLiqAllocPath.io.launchSelectedDst.archTag
  io.reducedLoadReplayLiqLaunchSelectedDstRelTag := reducedLoadReplayLiqAllocPath.io.launchSelectedDst.relTag
  io.reducedLoadReplayLiqLaunchSelectedDstPhysTag := reducedLoadReplayLiqAllocPath.io.launchSelectedDst.physTag
  io.reducedLoadReplayLiqLaunchSelectedDstOldPhysTag := reducedLoadReplayLiqAllocPath.io.launchSelectedDst.oldPhysTag
  io.reducedLoadReplayLiqLaunchSelectedRequestByteMask := reducedLoadReplayLiqAllocPath.io.launchSelectedRequestByteMask
  io.reducedLoadReplayLiqLaunchSelectedSpecWakeup := reducedLoadReplayLiqAllocPath.io.launchSelectedSpecWakeup
  io.reducedLoadReplayLiqLaunchSelectedStackValid := reducedLoadReplayLiqAllocPath.io.launchSelectedStackValid
  io.reducedLoadReplayLiqBaseLookupValid := reducedReplayLiqBaseDataAlign.io.requestValid
  io.reducedLoadReplayLiqBaseLookupCrossesLine := reducedReplayLiqBaseDataAlign.io.requestCrossesLine
  io.reducedLoadReplayLiqBaseLookupPc := reducedLoadReplayLiqAllocPath.io.launchSelectedPc
  io.reducedLoadReplayLiqBaseLookupAddr := reducedLoadReplayLiqAllocPath.io.launchSelectedAddr
  io.reducedLoadReplayLiqBaseLookupSize := reducedLoadReplayLiqAllocPath.io.launchSelectedSize
  io.reducedLoadReplayLiqBaseLookupRequestByteMask := reducedReplayLiqBaseDataAlign.io.requestByteMask
  io.reducedLoadReplayLiqBaseLineValidMask := reducedReplayLiqBaseDataAlign.io.lineValidMask
  io.reducedLoadReplayLiqBaseDataReturned := reducedReplayLiqBaseDataReady
  io.reducedLoadReplayLiqBaseLookupGranted := loadLookupArbiter.io.replayGranted
  io.reducedLoadReplayLiqBaseLookupBlockedByExecute := loadLookupArbiter.io.replayBlockedByExecute
  io.reducedLoadReplayLiqLaunchReadinessCandidateValid := reducedReplayLiqLaunchReadiness.io.candidateValid
  io.reducedLoadReplayLiqLaunchReadinessBaseDataReady := reducedReplayLiqLaunchReadiness.io.baseDataReady
  io.reducedLoadReplayLiqLaunchReadinessSourcesReturned := reducedReplayLiqLaunchReadiness.io.sourcesReturned
  io.reducedLoadReplayLiqLaunchReadinessReady := reducedReplayLiqLaunchReadiness.io.launchReady
  io.reducedLoadReplayLiqLaunchReadinessEnable := reducedReplayLiqLaunchReadiness.io.launchEnable
  io.reducedLoadReplayLiqLaunchReadinessBlockedByDisabled :=
    reducedReplayLiqLaunchReadiness.io.blockedByDisabled
  io.reducedLoadReplayLiqLaunchReadinessBlockedByNoCandidate :=
    reducedReplayLiqLaunchReadiness.io.blockedByNoCandidate
  io.reducedLoadReplayLiqLaunchReadinessBlockedByBaseLookup :=
    reducedReplayLiqLaunchReadiness.io.blockedByBaseLookup
  io.reducedLoadReplayLiqLaunchReadinessBlockedByBaseData :=
    reducedReplayLiqLaunchReadiness.io.blockedByBaseData
  io.reducedLoadReplayLiqLaunchReadinessBlockedByScb :=
    reducedReplayLiqLaunchReadiness.io.blockedByScb
  io.reducedLoadReplayLiqLaunchReadinessBlockedByReturn :=
    reducedReplayLiqLaunchReadiness.io.blockedByReturn
  io.reducedLoadReplayLiqSourceReturnCandidateValid :=
    reducedReplayLiqSourceReturnReadiness.io.candidateValid
  io.reducedLoadReplayLiqSourceReturnStoreSnapshotReady := reducedReplayLiqStoreSnapshotReady
  io.reducedLoadReplayLiqSourceReturnStoreSourceReturned :=
    reducedReplayLiqSourceReturnReadiness.io.storeSourceReturned
  io.reducedLoadReplayLiqSourceReturnScbSourceReturned :=
    reducedReplayLiqSourceReturnReadiness.io.scbSourceReturned
  io.reducedLoadReplayLiqSourceReturnSourceReturned :=
    reducedReplayLiqSourceReturnReadiness.io.sourceReturned
  io.reducedLoadReplayLiqSourceReturnBlockedByDisabled :=
    reducedReplayLiqSourceReturnReadiness.io.blockedByDisabled
  io.reducedLoadReplayLiqSourceReturnBlockedByNoCandidate :=
    reducedReplayLiqSourceReturnReadiness.io.blockedByNoCandidate
  io.reducedLoadReplayLiqSourceReturnBlockedByBaseData :=
    reducedReplayLiqSourceReturnReadiness.io.blockedByBaseData
  io.reducedLoadReplayLiqSourceReturnBlockedByStoreSnapshot :=
    reducedReplayLiqSourceReturnReadiness.io.blockedByStoreSnapshot
  io.reducedLoadReplayLiqSourceReturnBlockedByScb :=
    reducedReplayLiqSourceReturnReadiness.io.blockedByScb
  io.reducedLoadReplayLiqReturnConsumerCandidateValid :=
    reducedReplayLiqReturnConsumerReady.io.candidateValid
  io.reducedLoadReplayLiqReturnConsumerLretSinkReady :=
    reducedReplayLiqReturnLretSinkReady
  io.reducedLoadReplayLiqReturnConsumerWakeupSinkReady :=
    reducedReplayLiqReturnWakeupSinkReady
  io.reducedLoadReplayLiqReturnConsumerWakeupRequired :=
    reducedReplayLiqReturnConsumerReady.io.wakeupRequired
  io.reducedLoadReplayLiqReturnConsumerReady :=
    reducedReplayLiqReturnConsumerReady.io.consumerReady
  io.reducedLoadReplayLiqReturnConsumerBlockedByDisabled :=
    reducedReplayLiqReturnConsumerReady.io.blockedByDisabled
  io.reducedLoadReplayLiqReturnConsumerBlockedByNoCandidate :=
    reducedReplayLiqReturnConsumerReady.io.blockedByNoCandidate
  io.reducedLoadReplayLiqReturnConsumerBlockedBySources :=
    reducedReplayLiqReturnConsumerReady.io.blockedBySources
  io.reducedLoadReplayLiqReturnConsumerBlockedByLretSink :=
    reducedReplayLiqReturnConsumerReady.io.blockedByLretSink
  io.reducedLoadReplayLiqReturnConsumerBlockedByWakeupSink :=
    reducedReplayLiqReturnConsumerReady.io.blockedByWakeupSink
  io.reducedLoadReplayLiqReturnPipeBudgetCandidateValid :=
    reducedReplayLiqReturnPipeBudget.io.candidateValid
  io.reducedLoadReplayLiqReturnPipeBudgetEnable :=
    reducedReplayLiqReturnPipeBudgetEnable
  io.reducedLoadReplayLiqReturnPipeBudgetConsumerReady :=
    reducedReplayLiqReturnConsumerReady.io.consumerReady
  io.reducedLoadReplayLiqReturnPipeBudgetAvailable :=
    reducedReplayLiqReturnPipeBudget.io.pipeBudgetAvailable
  io.reducedLoadReplayLiqReturnPipeBudgetBlockedByDisabled :=
    reducedReplayLiqReturnPipeBudget.io.blockedByDisabled
  io.reducedLoadReplayLiqReturnPipeBudgetBlockedByNoCandidate :=
    reducedReplayLiqReturnPipeBudget.io.blockedByNoCandidate
  io.reducedLoadReplayLiqReturnPipeBudgetBlockedBySources :=
    reducedReplayLiqReturnPipeBudget.io.blockedBySources
  io.reducedLoadReplayLiqReturnPipeBudgetBlockedByBudgetDisabled :=
    reducedReplayLiqReturnPipeBudget.io.blockedByBudgetDisabled
  io.reducedLoadReplayLiqReturnPipeBudgetBlockedByConsumer :=
    reducedReplayLiqReturnPipeBudget.io.blockedByConsumer
  io.reducedLoadReplayLiqReturnPipePermitCandidateValid :=
    reducedReplayLiqReturnPipePermit.io.candidateValid
  io.reducedLoadReplayLiqReturnPipePermitPipeBudgetAvailable :=
    reducedReplayLiqReturnPipeBudget.io.pipeBudgetAvailable
  io.reducedLoadReplayLiqReturnPipePermitMask :=
    reducedReplayLiqReturnPipePermit.io.pipeAvailableMask
  io.reducedLoadReplayLiqReturnPipePermitValid :=
    reducedReplayLiqReturnPipePermit.io.permitValid
  io.reducedLoadReplayLiqReturnPipePermitBlockedByDisabled :=
    reducedReplayLiqReturnPipePermit.io.blockedByDisabled
  io.reducedLoadReplayLiqReturnPipePermitBlockedByNoCandidate :=
    reducedReplayLiqReturnPipePermit.io.blockedByNoCandidate
  io.reducedLoadReplayLiqReturnPipePermitBlockedBySources :=
    reducedReplayLiqReturnPipePermit.io.blockedBySources
  io.reducedLoadReplayLiqReturnPipePermitBlockedByPipeBudget :=
    reducedReplayLiqReturnPipePermit.io.blockedByPipeBudget
  io.reducedLoadReplayLiqReturnPipeAvailableMask :=
    reducedReplayLiqReturnPipePermit.io.pipeAvailableMask
  io.reducedLoadReplayLiqReturnPipeCandidateValid :=
    reducedReplayLiqReturnPipeSelect.io.candidateValid
  io.reducedLoadReplayLiqReturnPipeAvailable :=
    reducedReplayLiqReturnPipeSelect.io.pipeAvailable
  io.reducedLoadReplayLiqReturnPipeSelectedPipeIndex :=
    reducedReplayLiqReturnPipeSelect.io.selectedPipeIndex
  io.reducedLoadReplayLiqReturnPipeBlockedByDisabled :=
    reducedReplayLiqReturnPipeSelect.io.blockedByDisabled
  io.reducedLoadReplayLiqReturnPipeBlockedByNoCandidate :=
    reducedReplayLiqReturnPipeSelect.io.blockedByNoCandidate
  io.reducedLoadReplayLiqReturnPipeBlockedBySources :=
    reducedReplayLiqReturnPipeSelect.io.blockedBySources
  io.reducedLoadReplayLiqReturnPipeBlockedByNoPipe :=
    reducedReplayLiqReturnPipeSelect.io.blockedByNoPipe
  io.reducedLoadReplayLiqReturnReadinessCandidateValid :=
    reducedReplayLiqReturnReadiness.io.candidateValid
  io.reducedLoadReplayLiqReturnReadinessReady :=
    reducedReplayLiqReturnReadiness.io.returnReady
  io.reducedLoadReplayLiqReturnReadinessSelectedPipeIndex :=
    reducedReplayLiqReturnReadiness.io.selectedPipeIndex
  io.reducedLoadReplayLiqReturnReadinessBlockedByDisabled :=
    reducedReplayLiqReturnReadiness.io.blockedByDisabled
  io.reducedLoadReplayLiqReturnReadinessBlockedByNoCandidate :=
    reducedReplayLiqReturnReadiness.io.blockedByNoCandidate
  io.reducedLoadReplayLiqReturnReadinessBlockedBySources :=
    reducedReplayLiqReturnReadiness.io.blockedBySources
  io.reducedLoadReplayLiqReturnReadinessBlockedByReturnPipe :=
    reducedReplayLiqReturnReadiness.io.blockedByReturnPipe
  io.reducedLoadReplayLiqReturnDataCandidateValid := reducedReplayLiqReturnDataExtract.io.candidateValid
  io.reducedLoadReplayLiqReturnDataRequestByteMask := reducedReplayLiqReturnDataExtract.io.requestByteMask
  io.reducedLoadReplayLiqReturnDataBytesComplete := reducedReplayLiqReturnDataExtract.io.bytesComplete
  io.reducedLoadReplayLiqReturnDataCrossLine := reducedReplayLiqReturnDataExtract.io.crossLine
  io.reducedLoadReplayLiqReturnDataSizeSupported := reducedReplayLiqReturnDataExtract.io.sizeSupported
  io.reducedLoadReplayLiqReturnDataRawData := reducedReplayLiqReturnDataExtract.io.rawData
  io.reducedLoadReplayLiqReturnData := reducedReplayLiqReturnDataExtract.io.data
  io.reducedLoadReplayLiqReturnDataValid := reducedReplayLiqReturnDataExtract.io.dataValid
  io.reducedLoadReplayLiqReturnDataBlockedByDisabled :=
    reducedReplayLiqReturnDataExtract.io.blockedByDisabled
  io.reducedLoadReplayLiqReturnDataBlockedByNoCandidate :=
    reducedReplayLiqReturnDataExtract.io.blockedByNoCandidate
  io.reducedLoadReplayLiqReturnDataBlockedByZeroSize :=
    reducedReplayLiqReturnDataExtract.io.blockedByZeroSize
  io.reducedLoadReplayLiqReturnDataBlockedByUnsupportedSize :=
    reducedReplayLiqReturnDataExtract.io.blockedByUnsupportedSize
  io.reducedLoadReplayLiqReturnDataBlockedByCrossLine :=
    reducedReplayLiqReturnDataExtract.io.blockedByCrossLine
  io.reducedLoadReplayLiqReturnDataBlockedByIncompleteBytes :=
    reducedReplayLiqReturnDataExtract.io.blockedByIncompleteBytes
  io.reducedLoadReplayLiqReturnPublishCandidateValid :=
    reducedReplayLiqReturnPublishReady.io.candidateValid
  io.reducedLoadReplayLiqReturnPublishDataReady :=
    reducedReplayLiqReturnPublishReady.io.dataReady
  io.reducedLoadReplayLiqReturnPublishReady :=
    reducedReplayLiqReturnPublishReady.io.publishReady
  io.reducedLoadReplayLiqReturnPublishBlockedByDisabled :=
    reducedReplayLiqReturnPublishReady.io.blockedByDisabled
  io.reducedLoadReplayLiqReturnPublishBlockedByNoCandidate :=
    reducedReplayLiqReturnPublishReady.io.blockedByNoCandidate
  io.reducedLoadReplayLiqReturnPublishBlockedByData :=
    reducedReplayLiqReturnPublishReady.io.blockedByData
  io.reducedLoadReplayLiqReturnPublishBlockedByConsumer :=
    reducedReplayLiqReturnPublishReady.io.blockedByConsumer
  io.reducedLoadReplayLiqLretPayloadCandidateValid :=
    reducedReplayLiqReturnLretPayload.io.candidateValid
  io.reducedLoadReplayLiqLretPayloadValid :=
    reducedReplayLiqReturnLretPayload.io.payloadValid
  io.reducedLoadReplayLiqLretPayloadBidValid :=
    reducedReplayLiqReturnLretPayload.io.payloadBid.valid
  io.reducedLoadReplayLiqLretPayloadBidWrap :=
    reducedReplayLiqReturnLretPayload.io.payloadBid.wrap
  io.reducedLoadReplayLiqLretPayloadBidValue :=
    reducedReplayLiqReturnLretPayload.io.payloadBid.value
  io.reducedLoadReplayLiqLretPayloadGidValid :=
    reducedReplayLiqReturnLretPayload.io.payloadGid.valid
  io.reducedLoadReplayLiqLretPayloadGidWrap :=
    reducedReplayLiqReturnLretPayload.io.payloadGid.wrap
  io.reducedLoadReplayLiqLretPayloadGidValue :=
    reducedReplayLiqReturnLretPayload.io.payloadGid.value
  io.reducedLoadReplayLiqLretPayloadRidValid :=
    reducedReplayLiqReturnLretPayload.io.payloadRid.valid
  io.reducedLoadReplayLiqLretPayloadRidWrap :=
    reducedReplayLiqReturnLretPayload.io.payloadRid.wrap
  io.reducedLoadReplayLiqLretPayloadRidValue :=
    reducedReplayLiqReturnLretPayload.io.payloadRid.value
  io.reducedLoadReplayLiqLretPayloadLoadLsIdValid :=
    reducedReplayLiqReturnLretPayload.io.payloadLoadLsId.valid
  io.reducedLoadReplayLiqLretPayloadLoadLsIdWrap :=
    reducedReplayLiqReturnLretPayload.io.payloadLoadLsId.wrap
  io.reducedLoadReplayLiqLretPayloadLoadLsIdValue :=
    reducedReplayLiqReturnLretPayload.io.payloadLoadLsId.value
  io.reducedLoadReplayLiqLretPayloadPc :=
    reducedReplayLiqReturnLretPayload.io.payloadPc
  io.reducedLoadReplayLiqLretPayloadAddr :=
    reducedReplayLiqReturnLretPayload.io.payloadAddr
  io.reducedLoadReplayLiqLretPayloadSize :=
    reducedReplayLiqReturnLretPayload.io.payloadSize
  io.reducedLoadReplayLiqLretPayloadDstValid :=
    reducedReplayLiqReturnLretPayload.io.payloadDst.valid
  io.reducedLoadReplayLiqLretPayloadDstKind :=
    reducedReplayLiqReturnLretPayload.io.payloadDst.kind.asUInt
  io.reducedLoadReplayLiqLretPayloadDstArchTag :=
    reducedReplayLiqReturnLretPayload.io.payloadDst.archTag
  io.reducedLoadReplayLiqLretPayloadDstRelTag :=
    reducedReplayLiqReturnLretPayload.io.payloadDst.relTag
  io.reducedLoadReplayLiqLretPayloadDstPhysTag :=
    reducedReplayLiqReturnLretPayload.io.payloadDst.physTag
  io.reducedLoadReplayLiqLretPayloadDstOldPhysTag :=
    reducedReplayLiqReturnLretPayload.io.payloadDst.oldPhysTag
  io.reducedLoadReplayLiqLretPayloadData :=
    reducedReplayLiqReturnLretPayload.io.payloadData
  io.reducedLoadReplayLiqLretPayloadPipeIndex :=
    reducedReplayLiqReturnLretPayload.io.payloadPipeIndex
  io.reducedLoadReplayLiqLretPayloadSpecWakeup :=
    reducedReplayLiqReturnLretPayload.io.payloadSpecWakeup
  io.reducedLoadReplayLiqLretPayloadStackValid :=
    reducedReplayLiqReturnLretPayload.io.payloadStackValid
  io.reducedLoadReplayLiqLretPayloadWakeupRequired :=
    reducedReplayLiqReturnLretPayload.io.wakeupRequired
  io.reducedLoadReplayLiqLretPayloadBlockedByDisabled :=
    reducedReplayLiqReturnLretPayload.io.blockedByDisabled
  io.reducedLoadReplayLiqLretPayloadBlockedByNoCandidate :=
    reducedReplayLiqReturnLretPayload.io.blockedByNoCandidate
  io.reducedLoadReplayLiqLretPayloadBlockedByData :=
    reducedReplayLiqReturnLretPayload.io.blockedByData
  io.reducedLoadReplayLiqLretSinkEnqueueReady :=
    reducedReplayLiqReturnLretSink.io.enqueueReady
  io.reducedLoadReplayLiqLretSinkEnqueueAccepted :=
    reducedReplayLiqReturnLretSink.io.enqueueAccepted
  io.reducedLoadReplayLiqLretSinkEnqueueDropped :=
    reducedReplayLiqReturnLretSink.io.enqueueDropped
  io.reducedLoadReplayLiqLretSinkDrainValid :=
    reducedReplayLiqReturnLretSink.io.drainValid
  io.reducedLoadReplayLiqLretSinkDrainFire :=
    reducedReplayLiqReturnLretSink.io.drainFire
  io.reducedLoadReplayLiqLretSinkPending :=
    reducedReplayLiqReturnLretSink.io.pending
  io.reducedLoadReplayLiqLretSinkFull :=
    reducedReplayLiqReturnLretSink.io.full
  io.reducedLoadReplayLiqLretSinkEmpty :=
    reducedReplayLiqReturnLretSink.io.empty
  io.reducedLoadReplayLiqLretSinkCount :=
    reducedReplayLiqReturnLretSink.io.count
  io.reducedLoadReplayLiqLretSinkBlockedByFlush :=
    reducedReplayLiqReturnLretSink.io.blockedByFlush
  io.reducedLoadReplayLiqLretSinkBlockedByNoPayload :=
    reducedReplayLiqReturnLretSink.io.blockedByNoPayload
  io.reducedLoadReplayLiqLretSinkBlockedByFull :=
    reducedReplayLiqReturnLretSink.io.blockedByFull
  io.reducedLoadReplayLiqLretSinkBlockedByDrain :=
    reducedReplayLiqReturnLretSink.io.blockedByDrain
  io.reducedLoadReplayLiqLretDrainPermitEnable :=
    reducedReplayLiqReturnIexDrainPermit.io.enable
  io.reducedLoadReplayLiqLretDrainPermitPipeOccupiedMask :=
    reducedReplayLiqReturnIexPipeOccupiedMask
  io.reducedLoadReplayLiqLretDrainPermitPipeFreeMask :=
    reducedReplayLiqReturnIexDrainPermit.io.pipeFreeMask
  io.reducedLoadReplayLiqLretDrainPermitAnyPipeFree :=
    reducedReplayLiqReturnIexDrainPermit.io.anyPipeFree
  io.reducedLoadReplayLiqLretDrainPermitSelectedPipeIndex :=
    reducedReplayLiqReturnIexDrainPermit.io.selectedPipeIndex
  io.reducedLoadReplayLiqLretDrainPermitReady :=
    reducedReplayLiqReturnIexDrainPermit.io.drainReady
  io.reducedLoadReplayLiqLretDrainPermitBlockedByDisabled :=
    reducedReplayLiqReturnIexDrainPermit.io.blockedByDisabled
  io.reducedLoadReplayLiqLretDrainPermitBlockedByFlush :=
    reducedReplayLiqReturnIexDrainPermit.io.blockedByFlush
  io.reducedLoadReplayLiqLretDrainPermitBlockedByNoEntry :=
    reducedReplayLiqReturnIexDrainPermit.io.blockedByNoEntry
  io.reducedLoadReplayLiqLretDrainPermitBlockedByPipeFull :=
    reducedReplayLiqReturnIexDrainPermit.io.blockedByPipeFull
  io.reducedLoadReplayLiqLretIexDataEnable :=
    reducedReplayLiqReturnIexDataCandidate.io.enable
  io.reducedLoadReplayLiqLretIexDataRobRowValid :=
    reducedReplayLiqReturnIexDataRobRowValid
  io.reducedLoadReplayLiqLretIexDataRobRowNeedFlush :=
    reducedReplayLiqReturnIexDataRobRowNeedFlush
  io.reducedLoadReplayLiqLretIexDataCandidateValid :=
    reducedReplayLiqReturnIexDataCandidate.io.candidateValid
  io.reducedLoadReplayLiqLretIexDataWouldDrain :=
    reducedReplayLiqReturnIexDataCandidate.io.wouldDrain
  io.reducedLoadReplayLiqLretIexDataSetMemDataValid :=
    reducedReplayLiqReturnIexDataCandidate.io.setMemDataValid
  io.reducedLoadReplayLiqLretIexDataBlockedByDisabled :=
    reducedReplayLiqReturnIexDataCandidate.io.blockedByDisabled
  io.reducedLoadReplayLiqLretIexDataBlockedByFlush :=
    reducedReplayLiqReturnIexDataCandidate.io.blockedByFlush
  io.reducedLoadReplayLiqLretIexDataBlockedByNoEntry :=
    reducedReplayLiqReturnIexDataCandidate.io.blockedByNoEntry
  io.reducedLoadReplayLiqLretIexDataBlockedByInvalidEntry :=
    reducedReplayLiqReturnIexDataCandidate.io.blockedByInvalidEntry
  io.reducedLoadReplayLiqLretIexDataBlockedByDrain :=
    reducedReplayLiqReturnIexDataCandidate.io.blockedByDrain
  io.reducedLoadReplayLiqLretIexDataBlockedByRobMissing :=
    reducedReplayLiqReturnIexDataCandidate.io.blockedByRobMissing
  io.reducedLoadReplayLiqLretIexDataBlockedByNeedFlush :=
    reducedReplayLiqReturnIexDataCandidate.io.blockedByNeedFlush
  io.reducedLoadReplayLiqLretRobResolveCandidateValid :=
    reducedReplayLiqReturnRobResolveDataCandidate.io.candidateValid
  io.reducedLoadReplayLiqLretRobResolveValid :=
    reducedReplayLiqReturnRobResolveDataCandidate.io.resolveValid
  io.reducedLoadReplayLiqLretRobResolveReadyForPipeInsert :=
    reducedReplayLiqReturnRobResolveDataCandidate.io.readyForPipeInsert
  io.reducedLoadReplayLiqLretRobResolveMarkAllDestinationsDataValid :=
    reducedReplayLiqReturnRobResolveDataCandidate.io.markAllDestinationsDataValid
  io.reducedLoadReplayLiqLretRobResolveMarkDestinationDataValid :=
    reducedReplayLiqReturnRobResolveDataCandidate.io.markDestinationDataValid
  io.reducedLoadReplayLiqLretRobResolveRetLaneIncrement :=
    reducedReplayLiqReturnRobResolveDataCandidate.io.retLaneIncrement
  io.reducedLoadReplayLiqLretRobResolveData :=
    reducedReplayLiqReturnRobResolveDataCandidate.io.resolveData
  io.reducedLoadReplayLiqLretRobResolveDstPhysTag :=
    reducedReplayLiqReturnRobResolveDataCandidate.io.resolveDst.physTag
  io.reducedLoadReplayLiqLretRobResolveBlockedByDisabled :=
    reducedReplayLiqReturnRobResolveDataCandidate.io.blockedByDisabled
  io.reducedLoadReplayLiqLretRobResolveBlockedByFlush :=
    reducedReplayLiqReturnRobResolveDataCandidate.io.blockedByFlush
  io.reducedLoadReplayLiqLretRobResolveBlockedByNoSetMemData :=
    reducedReplayLiqReturnRobResolveDataCandidate.io.blockedByNoSetMemData
  io.reducedLoadReplayLiqLretRobResolveBlockedByUnsupportedMultiLane :=
    reducedReplayLiqReturnRobResolveDataCandidate.io.blockedByUnsupportedMultiLane
  io.reducedLoadReplayLiqLretRobResolveBlockedByInvalidRid :=
    reducedReplayLiqReturnRobResolveDataCandidate.io.blockedByInvalidRid
  io.reducedLoadReplayLiqLretRobResolveBlockedByNoDestination :=
    reducedReplayLiqReturnRobResolveDataCandidate.io.blockedByNoDestination
  io.reducedLoadReplayLiqLretLaneCompletionCandidateValid :=
    reducedReplayLiqReturnLaneCompletionCandidate.io.candidateValid
  io.reducedLoadReplayLiqLretLaneCompletionCompleteValid :=
    reducedReplayLiqReturnLaneCompletionCandidate.io.completeValid
  io.reducedLoadReplayLiqLretLaneCompletionReadyForPipeInsert :=
    reducedReplayLiqReturnLaneCompletionCandidate.io.readyForPipeInsert
  io.reducedLoadReplayLiqLretLaneCompletionRetLaneAfterResolve :=
    reducedReplayLiqReturnLaneCompletionCandidate.io.retLaneAfterResolve
  io.reducedLoadReplayLiqLretLaneCompletionRequiresAllLanes :=
    reducedReplayLiqReturnLaneCompletionCandidate.io.requiresAllLanes
  io.reducedLoadReplayLiqLretLaneCompletionBlockedByDisabled :=
    reducedReplayLiqReturnLaneCompletionCandidate.io.blockedByDisabled
  io.reducedLoadReplayLiqLretLaneCompletionBlockedByFlush :=
    reducedReplayLiqReturnLaneCompletionCandidate.io.blockedByFlush
  io.reducedLoadReplayLiqLretLaneCompletionBlockedByNoResolve :=
    reducedReplayLiqReturnLaneCompletionCandidate.io.blockedByNoResolve
  io.reducedLoadReplayLiqLretLaneCompletionBlockedByZeroReturnedLanes :=
    reducedReplayLiqReturnLaneCompletionCandidate.io.blockedByZeroReturnedLanes
  io.reducedLoadReplayLiqLretLaneCompletionBlockedByInvalidRealReqCnt :=
    reducedReplayLiqReturnLaneCompletionCandidate.io.blockedByInvalidRealReqCnt
  io.reducedLoadReplayLiqLretLaneCompletionBlockedByScalarLoadPairIncomplete :=
    reducedReplayLiqReturnLaneCompletionCandidate.io.blockedByScalarLoadPairIncomplete
  io.reducedLoadReplayLiqLretLaneCompletionBlockedByVectorMemIncomplete :=
    reducedReplayLiqReturnLaneCompletionCandidate.io.blockedByVectorMemIncomplete
  io.reducedLoadReplayLiqLretIexPipeInsertCandidateValid :=
    reducedReplayLiqReturnIexPipeInsertCandidate.io.candidateValid
  io.reducedLoadReplayLiqLretIexPipeInsertValid :=
    reducedReplayLiqReturnIexPipeInsertCandidate.io.insertValid
  io.reducedLoadReplayLiqLretIexPipeInsertIsLoadReturn :=
    reducedReplayLiqReturnIexPipeInsertCandidate.io.insertIsLoadReturn
  io.reducedLoadReplayLiqLretIexPipeInsertPipeIndex :=
    reducedReplayLiqReturnIexPipeInsertCandidate.io.insertPipeIndex
  io.reducedLoadReplayLiqLretIexPipeInsertLoadToUsePipeIndex :=
    reducedReplayLiqReturnIexPipeInsertCandidate.io.insertLoadToUsePipeIndex
  io.reducedLoadReplayLiqLretIexPipeInsertWakeupRequired :=
    reducedReplayLiqReturnIexPipeInsertCandidate.io.insertWakeupRequired
  io.reducedLoadReplayLiqLretIexPipeInsertBlockedByDisabled :=
    reducedReplayLiqReturnIexPipeInsertCandidate.io.blockedByDisabled
  io.reducedLoadReplayLiqLretIexPipeInsertBlockedByFlush :=
    reducedReplayLiqReturnIexPipeInsertCandidate.io.blockedByFlush
  io.reducedLoadReplayLiqLretIexPipeInsertBlockedByNoSetMemData :=
    reducedReplayLiqReturnIexPipeInsertCandidate.io.blockedByNoSetMemData
  io.reducedLoadReplayLiqLretIexPipeInsertBlockedByNoPipe :=
    reducedReplayLiqReturnIexPipeInsertCandidate.io.blockedByNoPipe
  io.reducedLoadReplayLiqLretIexPipeInsertBlockedByInvalidRid :=
    reducedReplayLiqReturnIexPipeInsertCandidate.io.blockedByInvalidRid
  io.reducedLoadReplayLiqWritebackCandidateValid :=
    reducedReplayLiqReturnWritebackCandidate.io.candidateValid
  io.reducedLoadReplayLiqWritebackValid :=
    reducedReplayLiqReturnWritebackCandidate.io.writeValid
  io.reducedLoadReplayLiqWritebackTag :=
    reducedReplayLiqReturnWritebackCandidate.io.writeTag
  io.reducedLoadReplayLiqWritebackData :=
    reducedReplayLiqReturnWritebackCandidate.io.writeData
  io.reducedLoadReplayLiqWritebackIgnoredNoDestination :=
    reducedReplayLiqReturnWritebackCandidate.io.ignoredNoDestination
  io.reducedLoadReplayLiqWritebackIgnoredNonGprDestination :=
    reducedReplayLiqReturnWritebackCandidate.io.ignoredNonGprDestination
  io.reducedLoadReplayLiqWritebackBlockedByDisabled :=
    reducedReplayLiqReturnWritebackCandidate.io.blockedByDisabled
  io.reducedLoadReplayLiqWritebackArbiterReplayEnabled :=
    rfWritebackArbiter.io.replayEnable
  io.reducedLoadReplayLiqWritebackArbiterSelectedExecute :=
    rfWritebackArbiter.io.selectedExecute
  io.reducedLoadReplayLiqWritebackArbiterSelectedReplay :=
    rfWritebackArbiter.io.selectedReplay
  io.reducedLoadReplayLiqWritebackArbiterReplayBlockedByDisabled :=
    rfWritebackArbiter.io.replayBlockedByDisabled
  io.reducedLoadReplayLiqWritebackArbiterReplayBlockedByExecute :=
    rfWritebackArbiter.io.replayBlockedByExecute
  io.reducedLoadReplayLiqSideEffectCandidateValid :=
    reducedReplayLiqReturnSideEffectReady.io.candidateValid
  io.reducedLoadReplayLiqSideEffectLretReady :=
    reducedReplayLiqReturnSideEffectReady.io.lretReady
  io.reducedLoadReplayLiqSideEffectWritebackReady :=
    reducedReplayLiqReturnSideEffectReady.io.writebackReady
  io.reducedLoadReplayLiqSideEffectWakeupReady :=
    reducedReplayLiqReturnSideEffectReady.io.wakeupReady
  io.reducedLoadReplayLiqSideEffectReady :=
    reducedReplayLiqReturnSideEffectReady.io.sideEffectsReady
  io.reducedLoadReplayLiqSideEffectBlockedByDisabled :=
    reducedReplayLiqReturnSideEffectReady.io.blockedByDisabled
  io.reducedLoadReplayLiqSideEffectBlockedByNoPayload :=
    reducedReplayLiqReturnSideEffectReady.io.blockedByNoPayload
  io.reducedLoadReplayLiqSideEffectBlockedByLret :=
    reducedReplayLiqReturnSideEffectReady.io.blockedByLret
  io.reducedLoadReplayLiqSideEffectBlockedByWriteback :=
    reducedReplayLiqReturnSideEffectReady.io.blockedByWriteback
  io.reducedLoadReplayLiqSideEffectBlockedByWakeup :=
    reducedReplayLiqReturnSideEffectReady.io.blockedByWakeup
  io.reducedLoadReplayLiqPublishControlCandidateValid :=
    reducedReplayLiqReturnPublishControl.io.candidateValid
  io.reducedLoadReplayLiqPublishControlLiveEnable :=
    reducedReplayLiqReturnPublishControl.io.liveEnable
  io.reducedLoadReplayLiqPublishControlArmed :=
    reducedReplayLiqReturnPublishControl.io.publishArmed
  io.reducedLoadReplayLiqPublishControlFire :=
    reducedReplayLiqReturnPublishControl.io.publishFire
  io.reducedLoadReplayLiqPublishControlBlockedByDisabled :=
    reducedReplayLiqReturnPublishControl.io.blockedByDisabled
  io.reducedLoadReplayLiqPublishControlBlockedByNoPayload :=
    reducedReplayLiqReturnPublishControl.io.blockedByNoPayload
  io.reducedLoadReplayLiqPublishControlBlockedByPublish :=
    reducedReplayLiqReturnPublishControl.io.blockedByPublish
  io.reducedLoadReplayLiqPublishControlBlockedBySideEffects :=
    reducedReplayLiqReturnPublishControl.io.blockedBySideEffects
  io.reducedLoadReplayLiqPublishControlBlockedByLiveDisabled :=
    reducedReplayLiqReturnPublishControl.io.blockedByLiveDisabled
  io.reducedLoadReplayLiqPublishRequestValid :=
    reducedReplayLiqReturnPublishRequest.io.requestValid
  io.reducedLoadReplayLiqPublishRequestLret :=
    reducedReplayLiqReturnPublishRequest.io.lretRequest
  io.reducedLoadReplayLiqPublishRequestWriteback :=
    reducedReplayLiqReturnPublishRequest.io.writebackRequest
  io.reducedLoadReplayLiqPublishRequestWakeup :=
    reducedReplayLiqReturnPublishRequest.io.wakeupRequest
  io.reducedLoadReplayLiqPublishRequestMask :=
    reducedReplayLiqReturnPublishRequest.io.requestMask
  io.reducedLoadReplayLiqPublishRequestBlockedByNoFire :=
    reducedReplayLiqReturnPublishRequest.io.blockedByNoFire
  io.reducedLoadReplayLiqPublishRequestInvalidFireWithoutPayload :=
    reducedReplayLiqReturnPublishRequest.io.invalidFireWithoutPayload
  io.reducedLoadReplayLiqWakeupCandidateValid :=
    reducedReplayLiqReturnWakeupCandidate.io.candidateValid
  io.reducedLoadReplayLiqWakeupRequired :=
    reducedReplayLiqReturnWakeupCandidate.io.wakeupRequired
  io.reducedLoadReplayLiqWakeupValid :=
    reducedReplayLiqReturnWakeupCandidate.io.wakeupValid
  io.reducedLoadReplayLiqWakeupKind :=
    reducedReplayLiqReturnWakeupCandidate.io.wakeupKind.asUInt
  io.reducedLoadReplayLiqWakeupTag :=
    reducedReplayLiqReturnWakeupCandidate.io.wakeupTag
  io.reducedLoadReplayLiqWakeupReducedGprValid :=
    reducedReplayLiqReturnWakeupCandidate.io.reducedGprWakeupValid
  io.reducedLoadReplayLiqWakeupNonGpr :=
    reducedReplayLiqReturnWakeupCandidate.io.nonGprWakeup
  io.reducedLoadReplayLiqWakeupSuppressedNotRequired :=
    reducedReplayLiqReturnWakeupCandidate.io.suppressedWakeupNotRequired
  io.reducedLoadReplayLiqWakeupIgnoredNoDestination :=
    reducedReplayLiqReturnWakeupCandidate.io.ignoredNoDestination
  io.reducedLoadReplayLiqWakeupBlockedByDisabled :=
    reducedReplayLiqReturnWakeupCandidate.io.blockedByDisabled
  io.reducedLoadReplayLiqRepickMask := reducedLoadReplayLiqAllocPath.io.repickMask
  io.reducedLoadReplayLiqMissMask := reducedLoadReplayLiqAllocPath.io.missMask
  io.reducedLoadReplayLiqResolvedMask := reducedLoadReplayLiqAllocPath.io.resolvedMask
  io.reducedLoadReplayLiqE4UpdateValid := reducedLoadReplayLiqAllocPath.io.e4UpdateValid
  io.reducedLoadReplayLiqE4UpdateIndex := reducedLoadReplayLiqAllocPath.io.e4UpdateIndex
  io.reducedLoadReplayLiqE4MissKind := reducedLoadReplayLiqAllocPath.io.e4MissKind.asUInt
  io.reducedLoadReplayLiqE4WakeupValid := reducedLoadReplayLiqAllocPath.io.e4WakeupValid
  io.reducedLoadReplayLiqLhqRecordValid := reducedLoadReplayLiqAllocPath.io.lhqRecordValid
  io.reducedLoadReplayLiqResidentCount := reducedLoadReplayLiqAllocPath.io.residentCount
  io.reducedLoadReplayLiqEmpty := reducedLoadReplayLiqAllocPath.io.empty
  io.reducedLoadReplayLiqFull := reducedLoadReplayLiqAllocPath.io.full
  io.reducedLoadReplayResolveQueuePushReady := reducedLoadReplayResolveQueue.io.pushReady
  io.reducedLoadReplayResolveQueuePushAccepted := reducedLoadReplayResolveQueue.io.pushAccepted
  io.reducedLoadReplayResolveQueueClearPending := reducedLoadReplayResolveClearPending
  io.reducedLoadReplayResolveQueueClearAccepted := reducedLoadReplayLiqAllocPath.io.clearResolvedAccepted
  io.reducedLoadReplayResolveQueueClearIndex := reducedLoadReplayResolveClearIndex
  io.reducedLoadReplayResolveQueueRetireValid := reducedLoadReplayResolveQueue.io.retireValid
  io.reducedLoadReplayResolveQueueRetireIsLoadStore := reducedLoadReplayResolveRetireSource.isLoadStore
  io.reducedLoadReplayResolveQueueRetireIsLoad := reducedLoadReplayResolveRetireSource.isLoad
  io.reducedLoadReplayResolveQueueRetireIsStore := reducedLoadReplayResolveRetireSource.isStore
  io.reducedLoadReplayResolveQueueRetireBidValid := reducedLoadReplayResolveRetireSource.bid.valid
  io.reducedLoadReplayResolveQueueRetireBidWrap := reducedLoadReplayResolveRetireSource.bid.wrap
  io.reducedLoadReplayResolveQueueRetireBidValue := reducedLoadReplayResolveRetireSource.bid.value
  io.reducedLoadReplayResolveQueueRetireRidValid := reducedLoadReplayResolveRetireSource.rid.valid
  io.reducedLoadReplayResolveQueueRetireRidWrap := reducedLoadReplayResolveRetireSource.rid.wrap
  io.reducedLoadReplayResolveQueueRetireRidValue := reducedLoadReplayResolveRetireSource.rid.value
  io.reducedLoadReplayResolveQueueRetireLsIdValid := reducedLoadReplayResolveRetireLsId.valid
  io.reducedLoadReplayResolveQueueRetireLsIdWrap := reducedLoadReplayResolveRetireLsId.wrap
  io.reducedLoadReplayResolveQueueRetireLsIdValue := reducedLoadReplayResolveRetireLsId.value
  io.reducedLoadReplayResolveQueuePreciseFlushValid := reducedLoadReplayResolvePreciseFlush.req.valid
  io.reducedLoadReplayResolveQueuePreciseFlushBidValid := reducedLoadReplayResolvePreciseFlush.req.bid.valid
  io.reducedLoadReplayResolveQueuePreciseFlushBidWrap := reducedLoadReplayResolvePreciseFlush.req.bid.wrap
  io.reducedLoadReplayResolveQueuePreciseFlushBidValue := reducedLoadReplayResolvePreciseFlush.req.bid.value
  io.reducedLoadReplayResolveQueuePreciseFlushRidValid := reducedLoadReplayResolvePreciseFlush.req.rid.valid
  io.reducedLoadReplayResolveQueuePreciseFlushRidWrap := reducedLoadReplayResolvePreciseFlush.req.rid.wrap
  io.reducedLoadReplayResolveQueuePreciseFlushRidValue := reducedLoadReplayResolvePreciseFlush.req.rid.value
  io.reducedLoadReplayResolveQueuePreciseFlushLsIdValid := reducedLoadReplayResolvePreciseFlush.req.lsId.valid
  io.reducedLoadReplayResolveQueuePreciseFlushLsIdWrap := reducedLoadReplayResolvePreciseFlush.req.lsId.wrap
  io.reducedLoadReplayResolveQueuePreciseFlushLsIdValue := reducedLoadReplayResolvePreciseFlush.req.lsId.value
  io.reducedLoadReplayResolveQueueFlushPruneMask := reducedLoadReplayResolveQueue.io.flushPruneMask
  io.reducedLoadReplayResolveQueueFlushPruneCount := reducedLoadReplayResolveQueue.io.flushPruneCount
  io.reducedLoadReplayResolveQueueRetireMask := reducedLoadReplayResolveQueue.io.retireMask
  io.reducedLoadReplayResolveQueueRetireCount := reducedLoadReplayResolveQueue.io.retireCount
  io.reducedLoadReplayResolveQueueValidMask := reducedLoadReplayResolveQueue.io.validMask
  io.reducedLoadReplayResolveQueueCount := reducedLoadReplayResolveQueue.io.count
  io.reducedLoadReplayResolveQueueEmpty := reducedLoadReplayResolveQueue.io.empty
  io.reducedLoadReplayResolveQueueFull := reducedLoadReplayResolveQueue.io.full
  val reducedLoadReplayResolveQueueHead = reducedLoadReplayResolveQueue.io.conflictRows(0)
  io.reducedLoadReplayResolveQueueHeadValid := reducedLoadReplayResolveQueueHead.valid
  io.reducedLoadReplayResolveQueueHeadPeId := reducedLoadReplayResolveQueueHead.peId
  io.reducedLoadReplayResolveQueueHeadStid := reducedLoadReplayResolveQueueHead.stid
  io.reducedLoadReplayResolveQueueHeadTid := reducedLoadReplayResolveQueueHead.tid
  io.reducedLoadReplayResolveQueueHeadPc := reducedLoadReplayResolveQueueHead.pc
  io.reducedLoadReplayResolveQueueHeadAddr := reducedLoadReplayResolveQueueHead.addr
  io.reducedLoadReplayResolveQueueHeadSize := reducedLoadReplayResolveQueueHead.size
  io.reducedLoadReplayResolveQueueHeadBidValid := reducedLoadReplayResolveQueueHead.bid.valid
  io.reducedLoadReplayResolveQueueHeadBidWrap := reducedLoadReplayResolveQueueHead.bid.wrap
  io.reducedLoadReplayResolveQueueHeadBidValue := reducedLoadReplayResolveQueueHead.bid.value
  io.reducedLoadReplayResolveQueueHeadGidValid := reducedLoadReplayResolveQueueHead.gid.valid
  io.reducedLoadReplayResolveQueueHeadGidWrap := reducedLoadReplayResolveQueueHead.gid.wrap
  io.reducedLoadReplayResolveQueueHeadGidValue := reducedLoadReplayResolveQueueHead.gid.value
  io.reducedLoadReplayResolveQueueHeadRidValid := reducedLoadReplayResolveQueueHead.rid.valid
  io.reducedLoadReplayResolveQueueHeadRidWrap := reducedLoadReplayResolveQueueHead.rid.wrap
  io.reducedLoadReplayResolveQueueHeadRidValue := reducedLoadReplayResolveQueueHead.rid.value
  io.reducedLoadReplayResolveQueueHeadLsIdValid := reducedLoadReplayResolveQueueHead.lsId.valid
  io.reducedLoadReplayResolveQueueHeadLsIdWrap := reducedLoadReplayResolveQueueHead.lsId.wrap
  io.reducedLoadReplayResolveQueueHeadLsIdValue := reducedLoadReplayResolveQueueHead.lsId.value
  io.reducedMdbConflictStoreValid := reducedMdbStoreProbe.valid
  io.reducedMdbConflictActiveCandidateMask := reducedMdbConflictDetect.io.activeCandidateMask
  io.reducedMdbConflictResolveCandidateMask := reducedMdbConflictDetect.io.resolveCandidateMask
  io.reducedMdbConflictWaitStoreMask := reducedMdbConflictDetect.io.waitStoreMask
  io.reducedMdbConflictWaitStoreCount := reducedMdbConflictDetect.io.waitStoreCount
  io.reducedMdbConflictValid := reducedMdbConflictDetect.io.conflictValid
  io.reducedMdbConflictFromResolveQueue := reducedMdbConflictDetect.io.conflictFromResolveQueue
  io.reducedMdbConflictActiveIndex := reducedMdbConflictDetect.io.conflictActiveIndex
  io.reducedMdbConflictResolveIndex := reducedMdbConflictDetect.io.conflictResolveIndex
  io.reducedMdbConflictOrdinal := reducedMdbConflictDetect.io.conflictOrdinal
  io.reducedMdbConflictInnerFlush := reducedMdbConflictDetect.io.innerFlush
  io.reducedMdbConflictNukeFlush := reducedMdbConflictDetect.io.nukeFlush
  io.reducedMdbConflictLoadBidValid := reducedMdbConflictDetect.io.record.load.bid.valid
  io.reducedMdbConflictLoadBidWrap := reducedMdbConflictDetect.io.record.load.bid.wrap
  io.reducedMdbConflictLoadBidValue := reducedMdbConflictDetect.io.record.load.bid.value
  io.reducedMdbConflictLoadLsIdValid := reducedMdbConflictDetect.io.record.load.lsId.valid
  io.reducedMdbConflictLoadLsIdWrap := reducedMdbConflictDetect.io.record.load.lsId.wrap
  io.reducedMdbConflictLoadLsIdValue := reducedMdbConflictDetect.io.record.load.lsId.value
  io.reducedMdbConflictStoreBidValid := reducedMdbConflictDetect.io.record.store.bid.valid
  io.reducedMdbConflictStoreBidWrap := reducedMdbConflictDetect.io.record.store.bid.wrap
  io.reducedMdbConflictStoreBidValue := reducedMdbConflictDetect.io.record.store.bid.value
  io.reducedMdbConflictStoreLsIdValid := reducedMdbConflictDetect.io.record.store.lsId.valid
  io.reducedMdbConflictStoreLsIdWrap := reducedMdbConflictDetect.io.record.store.lsId.wrap
  io.reducedMdbConflictStoreLsIdValue := reducedMdbConflictDetect.io.record.store.lsId.value
  io.reducedMdbFanoutLookupValid := reducedMdbFanoutLookupValid
  io.reducedMdbFanoutLookupReady := reducedMdbQueueFanout.io.lookupInReady
  io.reducedMdbFanoutLookupAccepted := reducedMdbQueueFanout.io.lookupInAccepted
  io.reducedMdbFanoutLookupProcessed := reducedMdbQueueFanout.io.lookupProcessed
  io.reducedMdbFanoutDeleteValid := false.B
  io.reducedMdbFanoutDeleteReady := reducedMdbQueueFanout.io.deleteInReady
  io.reducedMdbFanoutDeleteAccepted := reducedMdbQueueFanout.io.deleteInAccepted
  io.reducedMdbFanoutDeleteProcessed := reducedMdbQueueFanout.io.deleteProcessed
  io.reducedMdbFanoutPhaseStalledByFanout := reducedMdbQueueFanout.io.phaseStalledByFanout
  io.reducedMdbFanoutLuOutValid := reducedMdbQueueFanout.io.luOutValid
  io.reducedMdbFanoutLuOutHit := reducedMdbQueueFanout.io.luOut.hit
  io.reducedMdbFanoutLuOutStoreBidValid := reducedMdbQueueFanout.io.luOut.stInfo.bid.valid
  io.reducedMdbFanoutLuOutStoreBidWrap := reducedMdbQueueFanout.io.luOut.stInfo.bid.wrap
  io.reducedMdbFanoutLuOutStoreBidValue := reducedMdbQueueFanout.io.luOut.stInfo.bid.value
  io.reducedMdbFanoutSuOutValid := reducedMdbQueueFanout.io.suOutValid
  io.reducedMdbFanoutSuOutHit := reducedMdbQueueFanout.io.suOut.hit
  io.reducedMdbFanoutSuOutStoreBidValid := reducedMdbQueueFanout.io.suOut.stInfo.bid.valid
  io.reducedMdbFanoutSuOutStoreBidWrap := reducedMdbQueueFanout.io.suOut.stInfo.bid.wrap
  io.reducedMdbFanoutSuOutStoreBidValue := reducedMdbQueueFanout.io.suOut.stInfo.bid.value
  io.reducedMdbFanoutRecordValid := reducedMdbFanoutRecordValid
  io.reducedMdbFanoutRecordReady := reducedMdbQueueFanout.io.recordInReady
  io.reducedMdbFanoutRecordAccepted := reducedMdbQueueFanout.io.recordInAccepted
  io.reducedMdbFanoutRecordProcessed := reducedMdbQueueFanout.io.recordProcessed
  io.reducedMdbFanoutBmdbReportValid := reducedMdbQueueFanout.io.bmdbReportValid
  io.reducedMdbFanoutBmdbLoadBidValid := reducedMdbQueueFanout.io.bmdbLoadBid.valid
  io.reducedMdbFanoutBmdbLoadBidWrap := reducedMdbQueueFanout.io.bmdbLoadBid.wrap
  io.reducedMdbFanoutBmdbLoadBidValue := reducedMdbQueueFanout.io.bmdbLoadBid.value
  io.reducedMdbFanoutBmdbStoreBidValid := reducedMdbQueueFanout.io.bmdbStoreBid.valid
  io.reducedMdbFanoutBmdbStoreBidWrap := reducedMdbQueueFanout.io.bmdbStoreBid.wrap
  io.reducedMdbFanoutBmdbStoreBidValue := reducedMdbQueueFanout.io.bmdbStoreBid.value
  io.reducedMdbFanoutBmdbStoreStid := reducedMdbQueueFanout.io.bmdbStoreStid
  io.reducedMdbFanoutDeleteMatched := reducedMdbQueueFanout.io.deleteMatched
  io.reducedMdbFanoutDeleteReleased := reducedMdbQueueFanout.io.deleteReleased
  io.reducedMdbFanoutDeleteDroppedBelowStall := reducedMdbQueueFanout.io.deleteDroppedBelowStall
  io.reducedMdbFanoutRecordOverflow := reducedMdbQueueFanout.io.recordOverflow
  io.reducedMdbFanoutRecordOrderIllegal := reducedMdbQueueFanout.io.recordOrderIllegal
  io.reducedMdbFanoutSsitValidMask := reducedMdbQueueFanout.io.ssitValidMask
  io.reducedMdbFanoutSuMatchedStore := reducedMdbQueueFanout.io.suMatchedStore
  io.reducedMdbFanoutSuStorePending := reducedMdbQueueFanout.io.suStorePending
  io.reducedMdbFanoutSuWakeupValid := reducedMdbQueueFanout.io.suWakeup.valid
  io.reducedMdbFanoutSuWakeupIndex := reducedMdbQueueFanout.io.suWakeup.storeIndex
  io.reducedLoadWaitReplaySlotPc := reducedLoadWaitReplaySlot.io.slotPc
  io.reducedLoadWaitReplaySlotAddr := reducedLoadWaitReplaySlot.io.slotAddr
  io.storeDispatchReady := path.io.storeDispatchReady
  io.storeDispatchFire := path.io.storeDispatchFire
  io.storeDispatchSplit := path.io.storeDispatchSplit
  io.storeStaQueueValid := path.io.storeStaQueueValid
  io.storeStdQueueValid := path.io.storeStdQueueValid
  io.storeStaEnqueueFire := path.io.storeStaEnqueueFire
  io.storeStdEnqueueFire := path.io.storeStdEnqueueFire
  io.storeStaDequeueFire := path.io.storeStaDequeueFire
  io.storeStdDequeueFire := path.io.storeStdDequeueFire
  io.storeDispatchInputProtocolError := path.io.storeDispatchInputProtocolError
  io.storeStaQueueCount := path.io.storeStaQueueCount
  io.storeStdQueueCount := path.io.storeStdQueueCount
  io.storeStaInsertReady := path.io.storeStaInsertReady
  io.storeStdInsertReady := path.io.storeStdInsertReady
  io.storeSelectedSta := path.io.storeSelectedSta
  io.storeSelectedStd := path.io.storeSelectedStd
  io.storeBlockedByStaExec := path.io.storeBlockedByStaExec
  io.storeBlockedByStdExec := path.io.storeBlockedByStdExec
  io.storeStqInsertValid := path.io.storeStqInsertValid
  io.storeStqInsertAccepted := path.io.storeStqInsertAccepted
  io.storeStqInsertAllocated := path.io.storeStqInsertAllocated
  io.storeStqInsertMerged := path.io.storeStqInsertMerged
  io.storeStqInsertConflict := path.io.storeStqInsertConflict
  io.storeStqInsertIndex := path.io.storeStqInsertIndex
  io.storeStqOccupiedMask := path.io.storeStqOccupiedMask
  io.storeStqWaitMask := path.io.storeStqWaitMask
  io.storeStqCommitMask := path.io.storeStqCommitMask
  io.storeStqResidentCount := path.io.storeStqResidentCount
  io.storeStqOutstandingWaitCount := path.io.storeStqOutstandingWaitCount
  io.storeStqEmpty := path.io.storeStqEmpty
  io.storeStqFull := path.io.storeStqFull
  io.storeStqStall := path.io.storeStqStall
  io.executeUnsupported := execute.io.unsupported
  io.executeUnsupportedOpcode := execute.io.unsupportedOpcode
  io.robAllocFire := path.io.robAllocFire
  io.robRenameUpdateAttemptValid := path.io.robRenameUpdateAttemptValid
  io.robRenameUpdateReady := path.io.robRenameUpdateReady
  io.robRenameUpdateFire := path.io.robRenameUpdateFire
  io.robRenameUpdateIgnored := path.io.robRenameUpdateIgnored
  io.completeAccepted := path.io.completeAccepted
  io.completeIgnored := path.io.completeIgnored

  io.rfReadReadyMask := rf.io.readReady.asUInt
  io.rfAllReadReady := rf.io.allReadReady
  io.rfReadyMask := rf.io.readyMask
  io.rfWriteValid := rf.io.writeValid
  io.rfWriteTag := rf.io.writeTag
  io.rfWriteData := rf.io.writeData
  io.executeCompleteSrcPhysValidMask := execute.io.completeSrcPhysValid.asUInt
  io.executeCompleteSrcPhysTag := execute.io.completeSrcPhysTag
  io.executeCompletePc := execute.io.completeRow.pc
  io.executeCompleteInsn := execute.io.completeRow.insn
  io.executeCompleteWbReg := execute.io.completeRow.wb.reg
  io.rfStateError := rf.io.stateError
  io.issueQueueEnqueueFire := issue.io.enqueueFire
  io.issueQueuePickFire := issue.io.pickFire
  io.issueQueueIssueFire := issue.io.issueFire
  io.issueQueueCancelFire := issue.io.cancelFire
  io.issueQueueReleaseFire := issue.io.releaseFire
  io.issueQueueCount := issue.io.count
  io.issueQueueIssuedCount := issue.io.issuedCount
  io.issueQueueNotIssuedCount := issue.io.notIssuedCount
  io.issueQueueHeadValid := issue.io.headValid
  io.issueQueueHeadIssued := issue.io.headIssued
  io.issueQueueHeadPc := issue.io.headPc
  io.issueQueueHeadOpcode := issue.io.headOpcode
  io.issueQueueHeadSrcValidMask := issue.io.headSrcValidMask
  io.issueQueueHeadSrcClass := issue.io.headSrcOperandClass
  io.issueQueueHeadSrcPhysTag := issue.io.headSrcPhysTag
  io.issueQueueHeadSrcRelTag := issue.io.headSrcRelTag
  io.issueQueueSourceReadyMask := issue.io.sourceReadyMask
  io.issueQueueAllSourcesReady := issue.io.allSourcesReady
  io.issueQueueSelectedValid := issue.io.selectedValid
  io.issueQueueSelectedIndex := issue.io.selectedIndex
  io.issueQueueSelectedReadReady := issue.io.selectedReadReady
  io.issueQueueI1Valid := issue.io.i1Valid
  io.issueQueueI2Valid := issue.io.i2Valid
  io.issueQueueStageBusy := issue.io.stageBusy
  io.issueQueueBlockedBySource := issue.io.blockedBySource
  io.issueQueueBlockedByRead := issue.io.blockedByRead
  io.issueQueueBlockedByOutput := issue.io.blockedByOutput
  io.issueQueueBlockedByIssued := issue.io.blockedByIssued
  io.localTReadyMask := localTReady.asUInt
  io.localUReadyMask := localUReady.asUInt
  io.localTPendingCount := localTPendingCount
  io.localUPendingCount := localUPendingCount
  io.localIncomingUsesLocal := localIncomingUsesLocal
  io.localIncomingBlocked := localIncomingBlocked
  io.decodeBlockedByRename := path.io.blockedByRename
  io.decodeBlockedByRob := path.io.blockedByRob
  io.decodeBlockedByOutput := path.io.blockedByOutput
  io.decodeBlockedByTURename := path.io.blockedByTURename
  io.gprFreeCount := path.io.gprFreeCount
  io.gprMapQValidCount := path.io.gprMapQValidCount
  io.gprMapQFreeCount := path.io.gprMapQFreeCount
  io.gprSmapLiveCount := path.io.gprSmapLiveCount
  io.gprCmapLiveCount := path.io.gprCmapLiveCount
  io.gprMapQLiveCount := path.io.gprMapQLiveCount
  io.gprLivePhysCount := path.io.gprLivePhysCount
  io.gprFreeFromLiveCount := path.io.gprFreeFromLiveCount
  io.gprFreeListMismatchCount := path.io.gprFreeListMismatchCount
  io.gprNextMapQValidCount := path.io.gprNextMapQValidCount
  io.gprNextMapQLiveCount := path.io.gprNextMapQLiveCount
  io.gprNextLivePhysCount := path.io.gprNextLivePhysCount
  io.gprNextFreeFromLiveCount := path.io.gprNextFreeFromLiveCount
  io.gprCommitAccepted := path.io.gprCommitAccepted
  io.gprCommitBlockBid := path.io.gprCommitBlockBid
  io.gprCommittedMapQCount := path.io.gprCommittedMapQCount
  io.gprReleasedPhysCount := path.io.gprReleasedPhysCount
  io.tuRenameSourceUnderflowMask := path.io.tuRenameSourceUnderflowMask
  io.tuRenameActiveBankValid := path.io.tuRenameActiveBankValid
  io.tuRenameBlockedByTAlloc := path.io.tuRenameBlockedByTAlloc
  io.tuRenameBlockedByUAlloc := path.io.tuRenameBlockedByUAlloc
  io.tuRenameTUsedEntries := path.io.tuRenameTUsedEntries
  io.tuRenameUUsedEntries := path.io.tuRenameUUsedEntries
  io.tuRetireCommandValid := path.io.tuRetireCommandValid
  io.tuRetireCommandFire := path.io.tuRetireCommandFire
  io.tuRetireLocalBlockCommitPending := path.io.tuRetireLocalBlockCommitPending
  io.tuRetireLocalBlockCommitValid := path.io.tuRetireLocalBlockCommitValid
  io.tuRetireLocalBlockCommitReady := path.io.tuRetireLocalBlockCommitReady
  io.tuRetireLocalBlockCommitFire := path.io.tuRetireLocalBlockCommitFire
  io.tuRetireAccepted := path.io.tuRetireAccepted
  io.tuRetireMiss := path.io.tuRetireMiss
  io.tuRetireReleaseMismatch := path.io.tuRetireReleaseMismatch
  io.tuRetireUnsupported := path.io.tuRetireUnsupported

  io.commit := path.io.commit
  io.commitValidMask := path.io.commitValidMask
  io.commitCount := path.io.commitCount
  io.commitMonitorValidMask := path.io.commitMonitorValidMask
  io.commitMonitorValidCount := path.io.commitMonitorValidCount
  io.commitSkippedSlot := path.io.commitSkippedSlot
  io.commitDuplicateIdentity := path.io.commitDuplicateIdentity
  io.commitSlotMismatch := path.io.commitSlotMismatch
  io.commitInvalidSideEffect := path.io.commitInvalidSideEffect
  io.commitContractError := path.io.commitContractError

  io.deallocValidMask := path.io.deallocValidMask
  io.deallocCount := path.io.deallocCount
  io.robDeallocBlockLastValid := path.io.robDeallocBlockLastValid
  io.robDeallocBlockLastBlockBid := path.io.robDeallocBlockLastBlockBid
  io.blockScalarDoneFire := path.io.blockScalarDoneFire
  io.blockScalarDoneBid := path.io.blockScalarDoneBid
  io.blockRetireFire := path.io.blockRetireFire
  io.blockRetireBid := path.io.blockRetireBid
  io.empty := path.io.empty
  io.full := path.io.full
  io.size := path.io.size
  io.outstandingCount := path.io.outstandingCount
  io.commitHeadValid := path.io.commitHeadValid
  io.commitHeadStatus := path.io.commitHeadStatus
  io.commitHeadRobValue := path.io.commitHeadRobValue
  io.occupiedMask := path.io.occupiedMask
  io.completedMask := path.io.completedMask
  io.retiredMask := path.io.retiredMask
  io.blockAllocatedMask := path.io.blockAllocatedMask
  io.blockCompleteMask := path.io.blockCompleteMask
  io.blockPendingMask := path.io.blockPendingMask
  io.idle := path.io.empty && issue.io.empty && !execute.io.busy &&
    !source.io.waitingResponse && !source.io.packetValid && storeCommitOwner.io.idle &&
    ((!useReducedStoreDispatchStq).B || reducedStoreCommitDrain.io.empty) &&
    (!reducedLoadReplayLiqAllocEnabled ||
      (reducedLoadReplayLiqAllocPath.io.empty && reducedLoadReplayResolveQueue.io.empty))
}

object LinxCoreFrontendFetchRfAluTraceTop {
  def interfaceParamsFor(coreParams: CoreParams, physRegWidth: Int = 6): InterfaceParams =
    InterfaceParams(
      robEntries = coreParams.robEntries,
      commitWidth = coreParams.commitWidth,
      physRegWidth = physRegWidth
    )

  def traceParamsFor(p: InterfaceParams): CommitTraceParams =
    CommitTraceParams(
      commitWidth = p.commitWidth,
      robValueWidth = p.robIndexWidth
    )
}

object EmitLinxCoreFrontendFetchRfAluTraceTop extends App {
  circt.stage.ChiselStage.emitSystemVerilogFile(
    new LinxCoreFrontendFetchRfAluTraceTop(
      CoreParams(robEntries = 8, commitWidth = 2),
      mapQDepth = 32,
      gprMapQDepth = 256,
      physRegs = 128),
    args = Array("--target-dir", "../generated/chisel-verilog/frontend-fetch-rf-alu-trace-top"),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
}
