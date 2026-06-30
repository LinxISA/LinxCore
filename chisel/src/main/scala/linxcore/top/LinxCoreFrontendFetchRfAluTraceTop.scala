package linxcore.top

import chisel3._
import chisel3.util.log2Ceil

import linxcore.backend.DecodeRenameROBPath
import linxcore.commit.{CommitTraceParams, CommitTracePort}
import linxcore.common.{CoreParams, DestinationKind, InterfaceParams, OperandClass}
import linxcore.execute.{ReducedScalarAluExecute, ReducedScalarIssueQueue, ReducedScalarRegisterFile}
import linxcore.frontend.{F4DecodeWindow, F4DenseSlotQueue, FrontendFetchPacketSource}
import linxcore.lsu.StoreDispatchExecResult
import linxcore.recovery.RecoveryCleanupIntent
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
  private val tuCountWidth = log2Ceil(mapQDepth + 1)

  val startValid = Input(Bool())
  val startPc = Input(UInt(p.pcWidth.W))
  val restartValid = Input(Bool())
  val restartPc = Input(UInt(p.pcWidth.W))
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
  val decodeBlockedByRename = Output(Bool())
  val decodeBlockedByRob = Output(Bool())
  val decodeBlockedByOutput = Output(Bool())
  val decodeBlockedByTURename = Output(Bool())
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
  private val p = LinxCoreFrontendFetchRfAluTraceTop.interfaceParamsFor(coreParams)
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
    mapQDepth = mapQDepth,
    skipBlockMarkers = true
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
  val markerRedirectFire = path.io.blockMarkerStopRedirectValid
  val frontendPipeFlush = io.frontendFlushValid || markerRedirectPending

  when(io.frontendFlushValid || io.restartValid || io.startValid) {
    markerRedirectPending := false.B
    markerRedirectPcReg := 0.U
  }.elsewhen(markerRedirectFire) {
    markerRedirectPending := true.B
    markerRedirectPcReg := path.io.blockMarkerStopRedirectPc
  }.elsewhen(markerRedirectPending) {
    markerRedirectPending := false.B
  }

  source.io.startValid := io.startValid
  source.io.startPc := io.startPc
  source.io.restartValid := io.restartValid || markerRedirectPending
  source.io.restartPc := Mux(markerRedirectPending, markerRedirectPcReg, io.restartPc)
  source.io.flushValid := io.frontendFlushValid
  source.io.peId := io.peId
  source.io.threadId := io.threadId
  source.io.reqReady := io.fetchReqReady
  source.io.respValid := io.fetchRespValid
  source.io.respWindow := io.fetchRespWindow
  source.io.outReady := denseSlots.io.inReady
  source.io.advanceBytes := f4.io.totalLenBytes

  f4.io.in := source.io.out
  f4.io.flushValid := frontendPipeFlush

  denseSlots.io.inD1 := f4.io.d1
  denseSlots.io.inSlots := f4.io.slots
  denseSlots.io.inValidMask := f4.io.validMask
  denseSlots.io.outReady := path.io.decodeReady
  denseSlots.io.flushValid := frontendPipeFlush

  path.io.d1 := denseSlots.io.outD1
  path.io.slots := denseSlots.io.outSlots
  path.io.validMask := denseSlots.io.outValidMask
  path.io.flushValid := io.frontendFlushValid
  val localIncomingBlocked =
    (localTPendingCount =/= 0.U) || (localUPendingCount =/= 0.U)
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
  path.io.cleanup := 0.U.asTypeOf(new RecoveryCleanupIntent(p.robEntries, peIdWidth = p.peIdWidth, stidWidth = p.threadIdWidth, tidWidth = p.threadIdWidth))
  path.io.completeValid := execute.io.completeValid
  path.io.completeRobValue := execute.io.completeRobValue
  path.io.completeRowValid := execute.io.completeValid
  path.io.completeRow := execute.io.completeRow
  path.io.deallocReady := io.deallocReady

  issue.io.inValid := path.io.renamedOutValid && !localIncomingBlocked
  issue.io.in := path.io.renamedOut
  issue.io.flushValid := io.frontendFlushValid
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
    val rel = issue.io.readRelTag(idx)(1, 0)
    val localReadReady = Mux(readIsT, localTReady(rel), Mux(readIsU, localUReady(rel), false.B))
    val localReadData = Mux(readIsT, localTData(rel), localUData(rel))

    rf.io.readValid(idx) := issue.io.readValid(idx) && !readIsLocal
    rf.io.readTags(idx) := issue.io.readTags(idx)
    issue.io.readReady(idx) := Mux(readIsLocal, localReadReady, rf.io.readReady(idx))
    issue.io.readData(idx) := Mux(readIsLocal, localReadData, rf.io.readData(idx))
  }
  rf.io.clearValid := issue.io.enqueueDstValid
  rf.io.clearTag := issue.io.enqueueDstTag
  rf.io.writeValid := execute.io.completeDstPhysValid
  rf.io.writeTag := execute.io.completeDstPhysTag
  rf.io.writeData := execute.io.completeDstData

  val localReset = io.frontendFlushValid || io.startValid || io.restartValid
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
  io.sourceAdvanceBytes := f4.io.totalLenBytes
  io.sourceCurrentPc := source.io.currentPc
  io.sourceIssuedPc := source.io.issuedPc
  io.sourceNextPktUid := source.io.nextPktUid

  io.f4ValidMask := f4.io.validMask
  io.f4SlotCount := f4.io.slotCount
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
  io.decodeBlockedByRename := path.io.blockedByRename
  io.decodeBlockedByRob := path.io.blockedByRob
  io.decodeBlockedByOutput := path.io.blockedByOutput
  io.decodeBlockedByTURename := path.io.blockedByTURename
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
  io.idle := path.io.empty && issue.io.empty && !execute.io.busy &&
    !source.io.waitingResponse && !source.io.packetValid
}

object LinxCoreFrontendFetchRfAluTraceTop {
  def interfaceParamsFor(coreParams: CoreParams): InterfaceParams =
    InterfaceParams(
      robEntries = coreParams.robEntries,
      commitWidth = coreParams.commitWidth
    )

  def traceParamsFor(p: InterfaceParams): CommitTraceParams =
    CommitTraceParams(
      commitWidth = p.commitWidth,
      robValueWidth = p.robIndexWidth
    )
}

object EmitLinxCoreFrontendFetchRfAluTraceTop extends App {
  circt.stage.ChiselStage.emitSystemVerilogFile(
    new LinxCoreFrontendFetchRfAluTraceTop(CoreParams(robEntries = 8, commitWidth = 2), mapQDepth = 8),
    args = Array("--target-dir", "../generated/chisel-verilog/frontend-fetch-rf-alu-trace-top"),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
}
