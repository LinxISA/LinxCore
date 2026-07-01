package linxcore.top

import chisel3._
import chisel3.util.log2Ceil

import linxcore.backend.DecodeRenameROBPath
import linxcore.commit.{CommitTraceParams, CommitTracePort}
import linxcore.common.{CoreParams, DestinationKind, InterfaceParams, OperandClass}
import linxcore.execute.{ReducedScalarAluExecute, ReducedScalarIssueQueue, ReducedScalarRegisterFile}
import linxcore.frontend.{F4DecodeWindow, F4DenseSlotQueue, FrontendFetchPacketSource, ReducedBfuBodyCutArm, ReducedBfuBodyCutPredictor, ReducedBfuGeometryPredictionLatch, ReducedBfuLocalBodyWindow, ReducedBfuPendingRuntimeBodyEndCandidate, ReducedBfuPromotedRuntimeBodyEndOracle, ReducedBfuResolvedBodyEndOwner, ReducedBfuResolvedBodyEndPending, ReducedBfuResolvedBodyEndSource, ReducedBfuStaticGeometryProducer}
import linxcore.lsu.StoreDispatchExecResult
import linxcore.recovery.{ExecEngineType, FlushType, RecoveryCleanupIntent}
import linxcore.rob.{ROBEntryStatus, ROBID}

class LinxCoreFrontendFetchRfAluTraceTopIO(
    val p: InterfaceParams,
    val traceParams: CommitTraceParams,
    val decRenQueueDepth: Int = 4,
    val issueQueueDepth: Int = 4,
    val denseSlotQueueDepth: Int = 8,
    val mapQDepth: Int = 32,
    val physRegs: Int = 64)
    extends Bundle {
  private val ptrWidth = log2Ceil(p.robEntries)
  private val sizeWidth = log2Ceil(p.robEntries + 1)
  private val decRenCountWidth = log2Ceil(decRenQueueDepth + 1)
  private val issueCountWidth = log2Ceil(issueQueueDepth + 1)
  private val denseSlotQueueCountWidth = log2Ceil(denseSlotQueueDepth + 1)
  private val denseSlotQueueSlotWidth = math.max(1, log2Ceil(p.decodeWidth))
  private val gprFreeWidth = log2Ceil(physRegs + 1)
  private val gprMapQFreeWidth = log2Ceil(mapQDepth + 1)
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
  val decodeReady = Output(Bool())
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
  val loadLookupValid = Output(Bool())
  val loadLookupAddr = Output(UInt(p.immWidth.W))
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
  val gprMapQFreeCount = Output(UInt(gprMapQFreeWidth.W))
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
    val mapQDepth: Int = 32,
    val archRegs: Int = 24,
    val physRegs: Int = 64)
    extends Module {
  require(physRegs > 0 && (physRegs & (physRegs - 1)) == 0, "physical register count must be a power of two")
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
    mapQDepth = mapQDepth,
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
    skipBlockMarkers = true,
    reducedStoreDispatchBypass = true
  ))
  val rf = Module(new ReducedScalarRegisterFile(p, archRegs = archRegs, physRegs = physRegs))
  val issue = Module(new ReducedScalarIssueQueue(p, depth = issueQueueDepth))
  val execute = Module(new ReducedScalarAluExecute(p, traceParams))

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
  val scalarRedirectPending = RegInit(false.B)
  val scalarRedirectBidReg = RegInit(ROBID.disabled(p.robEntries))
  val scalarRedirectRidReg = RegInit(ROBID.disabled(p.robEntries))
  val scalarRedirectStidReg = RegInit(0.U(p.threadIdWidth.W))
  val scalarRedirectBlockBidReg = RegInit(0.U(p.blockBidWidth.W))
  val blockBranchTakenValid = RegInit(false.B)
  val blockBranchTaken = RegInit(false.B)
  val markerRedirectFire = path.io.blockMarkerStopRedirectValid || execute.io.redirectValid
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
    scalarRedirectStidReg := 0.U
    scalarRedirectBlockBidReg := 0.U
  }.elsewhen(markerRedirectFire) {
    markerRedirectPending := true.B
    markerRedirectPcReg := Mux(path.io.blockMarkerStopRedirectValid, path.io.blockMarkerStopRedirectPc, execute.io.redirectPc)
    scalarRedirectPending := execute.io.redirectValid
    scalarRedirectBidReg := Mux(execute.io.redirectValid, execute.io.releaseBid, ROBID.disabled(p.robEntries))
    scalarRedirectRidReg := Mux(execute.io.redirectValid, execute.io.releaseRid, ROBID.disabled(p.robEntries))
    scalarRedirectStidReg := Mux(execute.io.redirectValid, execute.io.releaseStid, 0.U)
    scalarRedirectBlockBidReg :=
      Mux(execute.io.redirectValid && execute.io.completeRow.blockBidValid, execute.io.completeRow.blockBid, 0.U)
  }.elsewhen(markerRedirectPending) {
    markerRedirectPending := false.B
    scalarRedirectPending := false.B
    scalarRedirectBidReg := ROBID.disabled(p.robEntries)
    scalarRedirectRidReg := ROBID.disabled(p.robEntries)
    scalarRedirectStidReg := 0.U
    scalarRedirectBlockBidReg := 0.U
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
  denseSlots.io.inSlots := f4.io.slots
  denseSlots.io.inValidMask := frontendValidMask
  denseSlots.io.outReady := path.io.decodeReady
  denseSlots.io.flushValid := frontendPipeFlush

  path.io.d1 := denseSlots.io.outD1
  path.io.slots := denseSlots.io.outSlots
  path.io.validMask := denseSlots.io.outValidMask
  path.io.flushValid := backendPipeFlush
  val localIncomingUsesLocal =
    path.io.decRenValid && path.io.decRenHeadUsesLocal
  val localIncomingBlocked =
    localIncomingUsesLocal && ((localTPendingCount =/= 0.U) || (localUPendingCount =/= 0.U))
  path.io.renamedOutReady := issue.io.inReady && !localIncomingBlocked
  path.io.storeStaExec := 0.U.asTypeOf(new StoreDispatchExecResult(64, 64, p.peIdWidth, p.threadIdWidth, p.threadIdWidth))
  path.io.storeStdExec := 0.U.asTypeOf(new StoreDispatchExecResult(64, 64, p.peIdWidth, p.threadIdWidth, p.threadIdWidth))
  path.io.storeMarkCommitValid := false.B
  path.io.storeMarkCommitIndex := 0.U
  path.io.storeCommitFreeValid := false.B
  path.io.storeCommitFreeIndex := 0.U
  path.io.storeCommitFreeMaskValid := false.B
  path.io.storeCommitFreeMask := 0.U
  path.io.checkpointValid := false.B
  path.io.checkpointBid := ROBID.disabled(p.robEntries)
  path.io.commitValid := false.B
  path.io.commitBid := ROBID.disabled(p.robEntries)
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
  path.io.cleanup := pathCleanup
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
  rf.io.writeValid := execute.io.completeDstPhysValid
  rf.io.writeTag := execute.io.completeDstPhysTag
  rf.io.writeData := execute.io.completeDstData
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
  execute.io.loadLookupData := io.loadLookupData
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
  io.decodeReady := path.io.decodeReady
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
  io.loadLookupValid := execute.io.loadLookupValid
  io.loadLookupAddr := execute.io.loadLookupAddr
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
  io.gprMapQFreeCount := path.io.gprMapQFreeCount
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
    !source.io.waitingResponse && !source.io.packetValid
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
    new LinxCoreFrontendFetchRfAluTraceTop(CoreParams(robEntries = 8, commitWidth = 2), mapQDepth = 32, physRegs = 128),
    args = Array("--target-dir", "../generated/chisel-verilog/frontend-fetch-rf-alu-trace-top"),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
}
