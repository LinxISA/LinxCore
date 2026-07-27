package linxcore.top

import chisel3._
import chisel3.util.{Cat, Decoupled, Mux1H, PopCount, PriorityEncoder, UIntToOH, log2Ceil}

import linxcore.commit.{CommitTraceParams, CommitTracePort}
import linxcore.common.{CoreParams, InterfaceParams}
import linxcore.frontend.ISideItlbRefill
import linxcore.system.{ReducedServiceRequestPayload, ReducedServiceRequestResponse}

object LinxCoreBenchmarkAutonomousTop {
  val BenchmarkRobEntries = 32
  val UartDataAddr: BigInt = BigInt("10000000", 16)
  val TestFinisherAddr: BigInt = BigInt("10009000", 16)
  val FinisherPass: BigInt = BigInt("5555", 16)

  val StatusIdle = 0
  val StatusFetch = 1
  val StatusUnsupported = 2
  val StatusFinisherPass = 3
  val StatusFinisherFail = 4

  def interfaceParamsFor(coreParams: CoreParams): InterfaceParams =
    LinxCoreFrontendFetchRfAluTraceTop.interfaceParamsFor(
      coreParams,
      physRegWidth = log2Ceil(coreParams.scalarBackend.gprPhysRegs)
    )

  def traceParamsFor(p: InterfaceParams): CommitTraceParams =
    LinxCoreFrontendFetchRfAluTraceTop.traceParamsFor(p)
}

class LinxCoreBenchmarkAutonomousTopIO(
    val p: InterfaceParams,
    val traceParams: CommitTraceParams,
    val mapQDepth: Int = LinxCoreBenchmarkAutonomousTop.BenchmarkRobEntries)
    extends Bundle {
  private val tuCountWidth = log2Ceil(mapQDepth + 1)

  val startValid = Input(Bool())
  val resetPc = Input(UInt(p.pcWidth.W))
  val resetSp = Input(UInt(p.immWidth.W))
  val restartValid = Input(Bool())
  val restartPc = Input(UInt(p.pcWidth.W))
  val restartSp = Input(UInt(p.immWidth.W))
  val flushValid = Input(Bool())
  val peId = Input(UInt(p.peIdWidth.W))
  val threadId = Input(UInt(p.threadIdWidth.W))

  val fetchReqReady = Input(Bool())
  val fetchRespValid = Input(Bool())
  val fetchRespWindow = Input(UInt(p.windowWidth.W))

  val fetchReqValid = Output(Bool())
  val fetchReqPc = Output(UInt(p.pcWidth.W))
  val fetchReqFire = Output(Bool())
  val fetchRespReady = Output(Bool())
  val fetchRespFire = Output(Bool())
  val fetchCurrentPc = Output(UInt(p.pcWidth.W))
  val sourceOutFire = Output(Bool())
  val sourceRestartValid = Output(Bool())
  val sourceRestartPc = Output(UInt(p.pcWidth.W))
  val debugSourceBlocked = Output(Bool())
  val debugSourceActive = Output(Bool())
  val debugSourceWaitingResponse = Output(Bool())
  val debugSourcePacketValid = Output(Bool())
  val debugBlockMarkerStopRedirectValid = Output(Bool())
  val debugBlockMarkerStopRedirectPc = Output(UInt(p.pcWidth.W))
  val debugMarkerRedirectFire = Output(Bool())
  val debugMarkerRedirectPending = Output(Bool())
  val debugMarkerRedirectPc = Output(UInt(p.pcWidth.W))
  val debugBodyCutAdvanceBytes = Output(UInt(4.W))
  val debugF4TotalLenBytes = Output(UInt(4.W))
  val debugReadinessBits = Output(UInt(8.W))
  val debugFretConditionBits = Output(UInt(8.W))
  val debugContinuationBits = Output(UInt(8.W))
  val debugLocalPendingCounts = Output(UInt(6.W))
  val debugLocalReadyMasks = Output(UInt(8.W))
  val debugLocalHeadPc = Output(UInt(p.pcWidth.W))
  val debugLocalExecuteCompleteValid = Output(Bool())
  val debugLocalCompletePc = Output(UInt(p.pcWidth.W))
  val debugLocalCompleteWbReg = Output(UInt(traceParams.regWidth.W))
  val debugDecodeBlockBits = Output(UInt(4.W))
  val debugDecodeReadyBits = Output(UInt(5.W))
  val debugTuRenameSourceUnderflowMask = Output(UInt(3.W))
  val debugTuRenameBlockedByTAlloc = Output(Bool())
  val debugTuRenameBlockedByUAlloc = Output(Bool())
  val debugTuRenameTUsedEntries = Output(UInt(tuCountWidth.W))
  val debugTuRenameUUsedEntries = Output(UInt(tuCountWidth.W))
  val debugTuRetireCommandValid = Output(Bool())
  val debugTuRetireCommandFire = Output(Bool())
  val debugTuRetireLocalBlockCommitPending = Output(Bool())
  val debugTuRetireLocalBlockCommitValid = Output(Bool())
  val debugTuRetireLocalBlockCommitReady = Output(Bool())
  val debugTuRetireLocalBlockCommitFire = Output(Bool())
  val debugTuRetireAccepted = Output(Bool())
  val debugTuRetireMiss = Output(Bool())
  val debugTuRetireReleaseMismatch = Output(Bool())
  val debugTuRetireUnsupported = Output(Bool())
  val debugGprReservationCount = Output(UInt(8.W))
  val debugGprReservationNeed = Output(UInt(8.W))
  val debugGprFreeCount = Output(UInt(8.W))
  val debugGprMapQValidCount = Output(UInt(8.W))
  val debugGprMapQFreeCount = Output(UInt(8.W))
  val debugGprFreeListMismatchCount = Output(UInt(8.W))
  val debugGprCommitAccepted = Output(Bool())
  val debugGprCommitBlockBid = Output(UInt(p.blockBidWidth.W))
  val debugGprCommittedMapQCount = Output(UInt(8.W))
  val debugGprReleasedPhysCount = Output(UInt(8.W))
  val debugRobRenameUpdateAttemptValid = Output(Bool())
  val debugRobRenameUpdateReady = Output(Bool())
  val debugRobRenameUpdateFire = Output(Bool())
  val debugRobRenameUpdateIgnored = Output(Bool())
  val debugCommitHeadValid = Output(Bool())
  val debugCommitHeadStatus = Output(UInt(3.W))
  val debugCommitHeadRobValue = Output(UInt(p.robIndexWidth.W))
  val debugRobOccupiedMask = Output(UInt(p.robEntries.W))
  val debugRobCompletedMask = Output(UInt(p.robEntries.W))
  val debugRobDeallocValidMask = Output(UInt(traceParams.commitWidth.W))
  val debugRobDeallocCount = Output(UInt(log2Ceil(traceParams.commitWidth + 1).W))
  val debugRobDeallocBlockLastValid = Output(Bool())
  val debugRobDeallocBlockLastBlockBid = Output(UInt(p.blockBidWidth.W))
  val debugBlockScalarDoneFire = Output(Bool())
  val debugBlockScalarDoneBid = Output(UInt(p.blockBidWidth.W))
  val debugBlockRetireFire = Output(Bool())
  val debugBlockRetireBid = Output(UInt(p.blockBidWidth.W))
  val debugDecRenHeadRidValid = Output(Bool())
  val debugDecRenHeadRidValue = Output(UInt(p.robIndexWidth.W))
  val debugRenamedOutValid = Output(Bool())
  val debugRenamedAccepted = Output(Bool())
  val debugIssueEnqueueFire = Output(Bool())
  val debugIssueInputValid = Output(Bool())
  val debugIssueInputPc = Output(UInt(p.pcWidth.W))
  val debugIssueInputOpcode = Output(UInt(p.opcodeWidth.W))
  val debugIssueInputBidValid = Output(Bool())
  val debugIssueInputBidWrap = Output(Bool())
  val debugIssueInputBidValue = Output(UInt(p.robIndexWidth.W))
  val debugIssueInputRidValid = Output(Bool())
  val debugIssueInputRidWrap = Output(Bool())
  val debugIssueInputRidValue = Output(UInt(p.robIndexWidth.W))
  val debugIssueInputStid = Output(UInt(p.threadIdWidth.W))
  val debugIssuePickFire = Output(Bool())
  val debugIssueFire = Output(Bool())
  val debugIssueOutputValid = Output(Bool())
  val debugIssueOutputPc = Output(UInt(p.pcWidth.W))
  val debugIssueOutputOpcode = Output(UInt(p.opcodeWidth.W))
  val debugIssueOutputBidValid = Output(Bool())
  val debugIssueOutputBidWrap = Output(Bool())
  val debugIssueOutputBidValue = Output(UInt(p.robIndexWidth.W))
  val debugIssueOutputRidValid = Output(Bool())
  val debugIssueOutputRidWrap = Output(Bool())
  val debugIssueOutputRidValue = Output(UInt(p.robIndexWidth.W))
  val debugIssueOutputStid = Output(UInt(p.threadIdWidth.W))
  val debugIssueHeadValid = Output(Bool())
  val debugIssueHeadIssued = Output(Bool())
  val debugIssueHeadPc = Output(UInt(p.pcWidth.W))
  val debugIssueHeadStid = Output(UInt(p.threadIdWidth.W))
  val debugIssueHeadBidValid = Output(Bool())
  val debugIssueHeadBidWrap = Output(Bool())
  val debugIssueHeadBidValue = Output(UInt(p.robIndexWidth.W))
  val debugIssueHeadRidValid = Output(Bool())
  val debugIssueHeadRidWrap = Output(Bool())
  val debugIssueHeadRidValue = Output(UInt(p.robIndexWidth.W))
  val debugIssueHeadSrcValidMask = Output(UInt(3.W))
  val debugIssueHeadSrcPhysTag = Output(Vec(3, UInt(p.physRegWidth.W)))
  val debugIssueSourceReadyMask = Output(UInt(3.W))
  val debugIssueAllSourcesReady = Output(Bool())
  val debugIssueSelectedValid = Output(Bool())
  val debugIssueSelectedIndex = Output(UInt(2.W))
  val debugIssueSelectedReadReady = Output(Bool())
  val debugIssueStageBits = Output(UInt(2.W))
  val debugIssueBlockedBits = Output(UInt(4.W))
  val debugIssueScalarSpOrderBlocked = Output(Bool())
  val debugIssueBankScalarSpOrderBlockedMask = Output(UInt(2.W))
  val debugScalarSpStid0IssueHeadValid = Output(Bool())
  val debugScalarSpStid0IssueHeadBidValid = Output(Bool())
  val debugScalarSpStid0IssueHeadBidWrap = Output(Bool())
  val debugScalarSpStid0IssueHeadBidValue = Output(UInt(p.robIndexWidth.W))
  val debugScalarSpStid0IssueHeadRidValid = Output(Bool())
  val debugScalarSpStid0IssueHeadRidWrap = Output(Bool())
  val debugScalarSpStid0IssueHeadRidValue = Output(UInt(p.robIndexWidth.W))
  val debugRfReadyMask = Output(UInt(64.W))
  val debugPWakeupValid = Output(Bool())
  val debugPWakeupTag = Output(UInt(p.physRegWidth.W))
  val debugExecuteAccepted = Output(Bool())
  val debugExecuteAcceptedIdentityValid = Output(Bool())
  val debugExecuteAcceptedPc = Output(UInt(p.pcWidth.W))
  val debugExecuteAcceptedOpcode = Output(UInt(p.opcodeWidth.W))
  val debugExecuteAcceptedBidValid = Output(Bool())
  val debugExecuteAcceptedBidWrap = Output(Bool())
  val debugExecuteAcceptedBidValue = Output(UInt(p.robIndexWidth.W))
  val debugExecuteAcceptedRidValid = Output(Bool())
  val debugExecuteAcceptedRidWrap = Output(Bool())
  val debugExecuteAcceptedRidValue = Output(UInt(p.robIndexWidth.W))
  val debugExecuteAcceptedStid = Output(UInt(p.threadIdWidth.W))
  val debugExecuteBusy = Output(Bool())
  val debugExecuteUnsupported = Output(Bool())
  val debugExecuteUnsupportedOpcode = Output(UInt(p.opcodeWidth.W))
  val debugExecuteCompleteRobValue = Output(UInt(p.robIndexWidth.W))
  val debugExecuteCompleteSrcPhysValidMask = Output(UInt(3.W))
  val debugExecuteCompleteSrcPhysTag = Output(Vec(3, UInt(p.physRegWidth.W)))
  val debugRobCompleteArbiterBits = Output(UInt(3.W))
  val debugRobCompleteResultBits = Output(UInt(2.W))
  val scalarLrReservationValidStid0 = Output(Bool())
  val scalarLrReservationLineStid0 = Output(UInt(p.immWidth.W))
  val scalarLrReservationCount = Output(UInt(8.W))
  val scalarLrReservationProtocolError = Output(Bool())
  val scalarLrReservationBlockedByFlush = Output(Bool())
  val scalarLrReservationCommittedStoreInvalidate = Output(Bool())
  val storeStaQueueValid = Output(Bool())
  val storeStdQueueValid = Output(Bool())
  val storeStaDequeueFire = Output(Bool())
  val storeStdDequeueFire = Output(Bool())
  val storeStaQueueCount = Output(UInt(8.W))
  val storeStdQueueCount = Output(UInt(8.W))
  val storeStaInsertReady = Output(Bool())
  val storeStdInsertReady = Output(Bool())
  val storeSelectedSta = Output(Bool())
  val storeSelectedStd = Output(Bool())
  val storeBlockedByStaExec = Output(Bool())
  val storeBlockedByStdExec = Output(Bool())
  val storeStqInsertValid = Output(Bool())
  val storeStqInsertAccepted = Output(Bool())
  val storeStqInsertConflict = Output(Bool())
  val storeStqInsertIndex = Output(UInt(p.robIndexWidth.W))
  val storeStqOccupiedMask = Output(UInt(p.robEntries.W))
  val storeStqWaitMask = Output(UInt(p.robEntries.W))
  val storeStqCommitMask = Output(UInt(p.robEntries.W))
  val storeStqAddrReadyMask = Output(UInt(p.robEntries.W))
  val storeStqDataReadyMask = Output(UInt(p.robEntries.W))
  val storeStqResidentCount = Output(UInt(8.W))
  val storeStqOutstandingWaitCount = Output(UInt(8.W))
  val storeStqEmpty = Output(Bool())
  val storeStqFull = Output(Bool())
  val storeStqStall = Output(Bool())

  val loadLookupData = Input(UInt(p.immWidth.W))
  val loadPairFirstLookupData = Input(UInt(p.immWidth.W))
  val loadLookupValid = Output(Bool())
  val loadLookupAddr = Output(UInt(p.immWidth.W))
  val loadPairFirstLookupValid = Output(Bool())
  val loadPairFirstLookupAddr = Output(UInt(p.immWidth.W))
  val loadLookupPc = Output(UInt(p.pcWidth.W))
  val loadLookupExecuteGranted = Output(Bool())
  val loadLookupReplayGranted = Output(Bool())
  val loadLookupDstValid = Output(Bool())
  val loadLookupDstKind = Output(UInt(2.W))
  val loadLookupDstArchTag = Output(UInt(p.archRegWidth.W))
  val loadLookupDstRelTag = Output(UInt(p.archRegWidth.W))
  val loadLookupDstPhysTag = Output(UInt(p.physRegWidth.W))
  val loadLookupDstOldPhysTag = Output(UInt(p.physRegWidth.W))
  val reducedServiceRequest = Decoupled(new ReducedServiceRequestPayload(p))
  val reducedServiceResponse = Flipped(Decoupled(new ReducedServiceRequestResponse(p)))

  val commit = Output(new CommitTracePort(traceParams))

  val storeObserveValid = Output(Bool())
  val storeObserveAddr = Output(UInt(p.immWidth.W))
  val storeObserveData = Output(UInt(p.immWidth.W))
  val storeObserveSize = Output(UInt(p.memSizeWidth.W))
  val storeObserveMask = Output(UInt(8.W))
  val storeObservePairValid = Output(Bool())
  val storeObservePairAddr = Output(UInt(p.immWidth.W))
  val storeObservePairData = Output(UInt(p.immWidth.W))
  val storeObservePairSize = Output(UInt(p.memSizeWidth.W))
  val storeObservePairMask = Output(UInt(8.W))
  val storeObservePc = Output(UInt(p.pcWidth.W))
  val storeObserveSeq = Output(UInt(traceParams.seqWidth.W))
  val storeObserveCycle = Output(UInt(traceParams.cycleWidth.W))
  val storeObserveSlot = Output(UInt(traceParams.slotWidth.W))
  val storeObserveBid = Output(UInt(32.W))
  val storeObserveGid = Output(UInt(32.W))
  val storeObserveRid = Output(UInt(32.W))
  val storeObserveRobValid = Output(Bool())
  val storeObserveRobWrap = Output(Bool())
  val storeObserveRobValue = Output(UInt(p.robIndexWidth.W))
  val storeObserveBlockBidValid = Output(Bool())
  val storeObserveBlockBid = Output(UInt(p.blockBidWidth.W))

  val uartWriteValid = Output(Bool())
  val uartWriteByte = Output(UInt(8.W))
  val finisherWriteValid = Output(Bool())
  val finisherCode = Output(UInt(16.W))
  val finisherPayload = Output(UInt(32.W))
  val finisherPass = Output(Bool())

  val bootSp = Output(UInt(p.immWidth.W))
  val active = Output(Bool())
  val halted = Output(Bool())
  val trapValid = Output(Bool())
  val unsupportedInstruction = Output(Bool())
  val unsupportedPc = Output(UInt(p.pcWidth.W))
  val status = Output(UInt(4.W))
}

private class BenchmarkStoreSideEffectPayload(
    val p: InterfaceParams,
    val traceParams: CommitTraceParams)
    extends Bundle {
  val addr = UInt(p.immWidth.W)
  val data = UInt(p.immWidth.W)
  val size = UInt(p.memSizeWidth.W)
  val mask = UInt(8.W)
  val pairValid = Bool()
  val pairAddr = UInt(p.immWidth.W)
  val pairData = UInt(p.immWidth.W)
  val pairSize = UInt(p.memSizeWidth.W)
  val pairMask = UInt(8.W)
  val pc = UInt(p.pcWidth.W)
  val seq = UInt(traceParams.seqWidth.W)
  val cycle = UInt(traceParams.cycleWidth.W)
  val slot = UInt(traceParams.slotWidth.W)
  val bid = UInt(32.W)
  val gid = UInt(32.W)
  val rid = UInt(32.W)
  val robValid = Bool()
  val robWrap = Bool()
  val robValue = UInt(p.robIndexWidth.W)
  val blockBidValid = Bool()
  val blockBid = UInt(p.blockBidWidth.W)
  val uartWrite = Bool()
  val uartByte = UInt(8.W)
  val finisherWrite = Bool()
  val finisherCode = UInt(16.W)
  val finisherPayload = UInt(32.W)
  val finisherPass = Bool()
}

class LinxCoreBenchmarkAutonomousTop(
    coreParams: CoreParams =
      CoreParams(robEntries = LinxCoreBenchmarkAutonomousTop.BenchmarkRobEntries, commitWidth = 2))
    extends Module {
  require(coreParams.commitWidth <= 2,
    "autonomous benchmark top observes at most two committed rows per cycle")

  private val p = LinxCoreBenchmarkAutonomousTop.interfaceParamsFor(coreParams)
  private val traceParams = LinxCoreBenchmarkAutonomousTop.traceParamsFor(p)
  val io = IO(new LinxCoreBenchmarkAutonomousTopIO(p, traceParams, coreParams.scalarLsu.mapQDepth))

  private val live = Module(new LinxCoreFrontendFetchRfAluTraceTop(
    coreParams = coreParams,
    archRegs = coreParams.scalarBackend.gprArchRegs,
    physRegs = coreParams.scalarBackend.gprPhysRegs,
    mapQDepth = coreParams.scalarLsu.mapQDepth,
    gprMapQDepth = coreParams.scalarBackend.gprMapQDepth,
    scalarStidCount = coreParams.scalarLsu.stidCount,
    useReducedStoreDispatchStq = true,
    useReducedStoreStaAddressExecBridge = true,
    useProductionD1Ingress = true
  ))

  private val productionIfu = Module(new LinxCoreProductionComposition(
    p = p,
    threadCount = 1,
    lineBytes = 64,
    pageBytes = 4096,
    itlbEntries = 16,
    l1iSets = 64,
    missEntries = 8,
    joinEntries = 8,
    maxGroupsPerTransaction = 8,
    instructionBufferDepth = 16,
    lineBridgeEntries = 8,
    feedbackEntries = 2
  ))
  private val lineFill = Module(new IfuWindowLineFillAdapter(p, lineBytes = 64))

  private val bootSpReg = RegInit(0.U(p.immWidth.W))
  private val haltedReg = RegInit(false.B)
  private val trapReg = RegInit(false.B)
  private val unsupportedReg = RegInit(false.B)
  private val unsupportedPcReg = RegInit(0.U(p.pcWidth.W))
  private val finisherSeenReg = RegInit(false.B)
  private val finisherPassReg = RegInit(false.B)
  private val finisherCodeReg = RegInit(0.U(16.W))
  private val finisherPayloadReg = RegInit(0.U(32.W))
  private val statusReg = RegInit(LinxCoreBenchmarkAutonomousTop.StatusIdle.U(4.W))

  private val startOrRestart = io.startValid || io.restartValid
  private val selectedSp = Mux(io.restartValid, io.restartSp, io.resetSp)
  private val sourceBlocked = haltedReg || trapReg || finisherSeenReg
  private val hardFlush = io.flushValid || sourceBlocked

  productionIfu.io.start.valid := io.startValid || io.restartValid || live.io.sourceRestartValid
  productionIfu.io.start.bits.peId := io.peId
  productionIfu.io.start.bits.threadId := io.threadId
  productionIfu.io.start.bits.pc := Mux(
    live.io.sourceRestartValid,
    live.io.sourceRestartPc,
    Mux(io.restartValid, io.restartPc, io.resetPc))

  private val ptwRefillValid = RegNext(productionIfu.io.ptwRequest.fire, false.B)
  private val ptwRefill = RegInit(0.U.asTypeOf(new ISideItlbRefill(p, pageBytes = 4096)))
  productionIfu.io.ptwRequest.ready := true.B
  when(productionIfu.io.ptwRequest.fire) {
    ptwRefill.vpn := productionIfu.io.ptwRequest.bits.vpn
    ptwRefill.ppn := productionIfu.io.ptwRequest.bits.vpn
    ptwRefill.executable := true.B
  }
  productionIfu.io.ptwRefill.valid := ptwRefillValid
  productionIfu.io.ptwRefill.bits := ptwRefill
  productionIfu.io.fetchFault.ready := true.B
  productionIfu.io.invalidateItlb := false.B
  productionIfu.io.invalidateL1I := false.B
  productionIfu.io.d1ThreadId := io.threadId
  productionIfu.io.backendValidation <> live.io.backendValidation

  lineFill.io.lineRequest <> productionIfu.io.memoryRequest
  productionIfu.io.memoryResponse <> lineFill.io.lineResponse
  lineFill.io.fetchReqReady := io.fetchReqReady && !sourceBlocked
  lineFill.io.fetchRespValid := io.fetchRespValid && !sourceBlocked
  lineFill.io.fetchRespWindow := io.fetchRespWindow

  live.io.productionD1 <> productionIfu.io.decoded
  live.io.productionIfuFlush := productionIfu.io.canonicalFlush.bits

  live.io.startValid := io.startValid
  live.io.startPc := io.resetPc
  live.io.restartValid := io.restartValid
  live.io.restartPc := io.restartPc
  live.io.bfuBodyValid := false.B
  live.io.bfuHeaderPc := 0.U
  live.io.bfuHSizeBytes := 0.U
  live.io.bfuBSizeBytes := 0.U
  live.io.frontendFlushValid := hardFlush
  live.io.peId := io.peId
  live.io.threadId := io.threadId
  live.io.fetchReqReady := false.B
  live.io.fetchRespValid := false.B
  live.io.fetchRespWindow := 0.U
  live.io.rfInitValid := startOrRestart
  live.io.rfInitArchTag := 1.U
  live.io.rfInitData := selectedSp
  live.io.deallocReady := !sourceBlocked
  live.io.loadLookupData := io.loadLookupData
  live.io.loadPairFirstLookupData := io.loadPairFirstLookupData
  live.io.reducedServiceRequest <> io.reducedServiceRequest
  live.io.reducedServiceResponse <> io.reducedServiceResponse

  private val acceptedRows = VecInit(live.io.commit.rows.map { row =>
    row.valid && !startOrRestart && !io.flushValid && !sourceBlocked
  })
  private val firstAcceptedIndex = PriorityEncoder(acceptedRows.asUInt)
  private val firstAcceptedRow = Mux1H(UIntToOH(firstAcceptedIndex, traceParams.commitWidth), live.io.commit.rows)
  private val unsupported = !startOrRestart && !io.flushValid && !sourceBlocked && live.io.commitContractError
  private val sideEffectPayloads = Wire(Vec(traceParams.commitWidth, new BenchmarkStoreSideEffectPayload(p, traceParams)))
  private val sideEffectValid = Wire(Vec(traceParams.commitWidth, Bool()))
  private val fatalCommit = Wire(Vec(traceParams.commitWidth, Bool()))
  private val zeroSideEffect = 0.U.asTypeOf(new BenchmarkStoreSideEffectPayload(p, traceParams))

  for (slot <- 0 until traceParams.commitWidth) {
    val row = live.io.commit.rows(slot)
    val storeSize = row.mem.size(p.memSizeWidth - 1, 0)
    val lowAddr = row.mem.addr(2, 0)
    val storeSizeFitsLane = storeSize =/= 0.U && storeSize <= 8.U
    val storeEndOffset = lowAddr +& storeSize
    val storeFitsObservationLane = storeSizeFitsLane && storeEndOffset <= 8.U
    val pairStoreInsn =
      ((row.insn & "h707f003f".U(traceParams.insnWidth.W)) === "h2059001e".U) ||
        ((row.insn & "h707f003f".U(traceParams.insnWidth.W)) === "h6059001e".U) ||
        ((row.insn & "h707f003f".U(traceParams.insnWidth.W)) === "h3059001e".U) ||
        ((row.insn & "h707f003f".U(traceParams.insnWidth.W)) === "h7059001e".U)
    val pairStoreAddr = row.mem.addr + storeSize
    val pairStoreLowAddr = pairStoreAddr(2, 0)
    val pairStoreEndOffset = pairStoreLowAddr +& storeSize
    val pairStoreFitsObservationLane = storeSizeFitsLane && pairStoreEndOffset <= 8.U
    val priorFatal =
      if (slot == 0) false.B else fatalCommit.asUInt(slot - 1, 0).orR
    val invalidStoreSideEffect =
      acceptedRows(slot) && !live.io.commitContractError && !row.trap.valid && row.mem.valid && row.mem.isStore &&
        (!storeFitsObservationLane || (pairStoreInsn && !pairStoreFitsObservationLane))
    fatalCommit(slot) := acceptedRows(slot) && (row.trap.valid || live.io.commitContractError || invalidStoreSideEffect)
    sideEffectValid(slot) :=
      acceptedRows(slot) && !priorFatal && !live.io.commitContractError && !row.trap.valid &&
        row.mem.valid && row.mem.isStore && storeFitsObservationLane

    val baseMaskWide = (1.U(9.W) << storeSize) - 1.U
    val shiftedMask = (baseMaskWide(7, 0) << lowAddr)(7, 0)
    val shiftedStoreData =
      (row.mem.wdata << Cat(lowAddr, 0.U(3.W)))(p.immWidth - 1, 0)
    val pairShiftedMask = (baseMaskWide(7, 0) << pairStoreLowAddr)(7, 0)
    val pairShiftedStoreData =
      (row.src1.data << Cat(pairStoreLowAddr, 0.U(3.W)))(p.immWidth - 1, 0)

    val payload = Wire(new BenchmarkStoreSideEffectPayload(p, traceParams))
    payload := zeroSideEffect
    payload.addr := row.mem.addr
    payload.data := shiftedStoreData
    payload.size := storeSize
    payload.mask := Mux(storeFitsObservationLane, shiftedMask, 0.U)
    payload.pairValid := pairStoreInsn && pairStoreFitsObservationLane
    payload.pairAddr := pairStoreAddr
    payload.pairData := pairShiftedStoreData
    payload.pairSize := storeSize
    payload.pairMask := Mux(pairStoreFitsObservationLane, pairShiftedMask, 0.U)
    payload.pc := row.pc
    payload.seq := row.seq
    payload.cycle := row.cycle
    payload.slot := row.slot
    payload.bid := row.identity.bid
    payload.gid := row.identity.gid
    payload.rid := row.identity.rid
    payload.robValid := row.rob.valid
    payload.robWrap := row.rob.wrap
    payload.robValue := row.rob.value
    payload.blockBidValid := row.blockBidValid
    payload.blockBid := row.blockBid
    payload.uartWrite := row.mem.addr === LinxCoreBenchmarkAutonomousTop.UartDataAddr.U(p.immWidth.W)
    payload.uartByte := row.mem.wdata(7, 0)
    payload.finisherWrite := row.mem.addr === LinxCoreBenchmarkAutonomousTop.TestFinisherAddr.U(p.immWidth.W)
    payload.finisherCode := row.mem.wdata(15, 0)
    payload.finisherPayload := row.mem.wdata(31, 0)
    payload.finisherPass := row.mem.wdata(15, 0) === LinxCoreBenchmarkAutonomousTop.FinisherPass.U(16.W)
    sideEffectPayloads(slot) := payload
  }

  private val trapCommit = (fatalCommit.asUInt & VecInit(live.io.commit.rows.map(_.trap.valid)).asUInt).orR
  private val invalidStoreSideEffect = (0 until traceParams.commitWidth).map { slot =>
    fatalCommit(slot) && !live.io.commit.rows(slot).trap.valid && !live.io.commitContractError
  }.reduce(_ || _)

  private val currentSideEffectValid = sideEffectValid.asUInt.orR
  assert(PopCount(sideEffectValid) <= 1.U,
    "autonomous benchmark top observed multiple committed stores in one cycle")
  private val currentSideEffectIndex = PriorityEncoder(sideEffectValid.asUInt)
  private val currentSideEffectPayload =
    Mux1H(UIntToOH(currentSideEffectIndex, traceParams.commitWidth), sideEffectPayloads)
  private val selectedSideEffectValid = currentSideEffectValid
  private val selectedSideEffect = currentSideEffectPayload

  when(startOrRestart) {
    bootSpReg := selectedSp
    haltedReg := false.B
    trapReg := false.B
    unsupportedReg := false.B
    unsupportedPcReg := 0.U
    finisherSeenReg := false.B
    finisherPassReg := false.B
    finisherCodeReg := 0.U
    finisherPayloadReg := 0.U
    statusReg := LinxCoreBenchmarkAutonomousTop.StatusFetch.U
  }.elsewhen(io.flushValid) {
    haltedReg := true.B
    trapReg := false.B
    unsupportedReg := false.B
    statusReg := LinxCoreBenchmarkAutonomousTop.StatusIdle.U
  }.elsewhen(selectedSideEffectValid && selectedSideEffect.finisherWrite) {
    finisherSeenReg := true.B
    finisherPassReg := selectedSideEffect.finisherPass
    finisherCodeReg := selectedSideEffect.finisherCode
    finisherPayloadReg := selectedSideEffect.finisherPayload
    haltedReg := true.B
    trapReg := !selectedSideEffect.finisherPass
    statusReg := Mux(
      selectedSideEffect.finisherPass,
      LinxCoreBenchmarkAutonomousTop.StatusFinisherPass.U,
      LinxCoreBenchmarkAutonomousTop.StatusFinisherFail.U
    )
  }.elsewhen(trapCommit || unsupported || invalidStoreSideEffect) {
    haltedReg := true.B
    trapReg := true.B
    unsupportedReg := unsupported || invalidStoreSideEffect
    unsupportedPcReg := firstAcceptedRow.pc
    statusReg := LinxCoreBenchmarkAutonomousTop.StatusUnsupported.U
  }

  io.fetchReqValid := lineFill.io.fetchReqValid && !sourceBlocked
  io.fetchReqPc := lineFill.io.fetchReqPc
  io.fetchReqFire := lineFill.io.fetchReqFire && !sourceBlocked
  io.fetchRespReady := lineFill.io.fetchRespReady && !sourceBlocked
  io.fetchRespFire := lineFill.io.fetchRespFire && !sourceBlocked
  io.fetchCurrentPc := productionIfu.io.currentPc(0)
  io.sourceOutFire := live.io.sourceOutFire
  io.sourceRestartValid := live.io.sourceRestartValid
  io.sourceRestartPc := live.io.sourceRestartPc
  io.debugSourceBlocked := sourceBlocked
  io.debugSourceActive := productionIfu.io.active(0)
  io.debugSourceWaitingResponse := lineFill.io.active
  io.debugSourcePacketValid := productionIfu.io.decoded.valid
  io.debugBlockMarkerStopRedirectValid := live.io.debugBlockMarkerStopRedirectValid
  io.debugBlockMarkerStopRedirectPc := live.io.debugBlockMarkerStopRedirectPc
  io.debugMarkerRedirectFire := live.io.debugMarkerRedirectFire
  io.debugMarkerRedirectPending := live.io.debugMarkerRedirectPending
  io.debugMarkerRedirectPc := live.io.debugMarkerRedirectPc
  io.debugBodyCutAdvanceBytes := productionIfu.io.lineOutstandingCount
  io.debugF4TotalLenBytes := productionIfu.io.joinCount
  io.debugReadinessBits := Cat(
    productionIfu.io.f3WaitingForNextLine,
    productionIfu.io.crossLinePending,
    productionIfu.io.ptwPending,
    productionIfu.io.bSideStageValid)
  io.debugFretConditionBits := live.io.debugFretConditionBits
  io.debugContinuationBits := live.io.debugContinuationBits
  io.debugLocalPendingCounts := Cat(live.io.localUPendingCount, live.io.localTPendingCount)
  io.debugLocalReadyMasks := Cat(live.io.localUReadyMask, live.io.localTReadyMask)
  io.debugLocalHeadPc := live.io.decRenHeadPc
  io.debugLocalExecuteCompleteValid := live.io.executeCompleteValid
  io.debugLocalCompletePc := live.io.executeCompletePc
  io.debugLocalCompleteWbReg := live.io.executeCompleteWbReg
  io.debugDecodeBlockBits := Cat(
    live.io.decodeBlockedByTURename,
    live.io.decodeBlockedByOutput,
    live.io.decodeBlockedByRob,
    live.io.decodeBlockedByRename)
  io.debugDecodeReadyBits := Cat(
    live.io.decodeSelectedNeedsGprReservation,
    live.io.decodeSelectedClosesActiveRedirect,
    live.io.decodeGprReservationReady,
    live.io.decodeAllocReady,
    live.io.decodeQueuePushReady)
  io.debugTuRenameSourceUnderflowMask := live.io.tuRenameSourceUnderflowMask
  io.debugTuRenameBlockedByTAlloc := live.io.tuRenameBlockedByTAlloc
  io.debugTuRenameBlockedByUAlloc := live.io.tuRenameBlockedByUAlloc
  io.debugTuRenameTUsedEntries := live.io.tuRenameTUsedEntries
  io.debugTuRenameUUsedEntries := live.io.tuRenameUUsedEntries
  io.debugTuRetireCommandValid := live.io.tuRetireCommandValid
  io.debugTuRetireCommandFire := live.io.tuRetireCommandFire
  io.debugTuRetireLocalBlockCommitPending := live.io.tuRetireLocalBlockCommitPending
  io.debugTuRetireLocalBlockCommitValid := live.io.tuRetireLocalBlockCommitValid
  io.debugTuRetireLocalBlockCommitReady := live.io.tuRetireLocalBlockCommitReady
  io.debugTuRetireLocalBlockCommitFire := live.io.tuRetireLocalBlockCommitFire
  io.debugTuRetireAccepted := live.io.tuRetireAccepted
  io.debugTuRetireMiss := live.io.tuRetireMiss
  io.debugTuRetireReleaseMismatch := live.io.tuRetireReleaseMismatch
  io.debugTuRetireUnsupported := live.io.tuRetireUnsupported
  io.debugGprReservationCount := live.io.gprReservationCount
  io.debugGprReservationNeed := live.io.gprReservationNeed
  io.debugGprFreeCount := live.io.gprFreeCount
  io.debugGprMapQValidCount := live.io.gprMapQValidCount
  io.debugGprMapQFreeCount := live.io.gprMapQFreeCount
  io.debugGprFreeListMismatchCount := live.io.gprFreeListMismatchCount
  io.debugGprCommitAccepted := live.io.gprCommitAccepted
  io.debugGprCommitBlockBid := live.io.gprCommitBlockBid
  io.debugGprCommittedMapQCount := live.io.gprCommittedMapQCount
  io.debugGprReleasedPhysCount := live.io.gprReleasedPhysCount
  io.debugRobRenameUpdateAttemptValid := live.io.robRenameUpdateAttemptValid
  io.debugRobRenameUpdateReady := live.io.robRenameUpdateReady
  io.debugRobRenameUpdateFire := live.io.robRenameUpdateFire
  io.debugRobRenameUpdateIgnored := live.io.robRenameUpdateIgnored
  io.debugCommitHeadValid := live.io.commitHeadValid
  io.debugCommitHeadStatus := live.io.commitHeadStatus.asUInt
  io.debugCommitHeadRobValue := live.io.commitHeadRobValue
  io.debugRobOccupiedMask := live.io.occupiedMask
  io.debugRobCompletedMask := live.io.completedMask
  io.debugRobDeallocValidMask := live.io.deallocValidMask
  io.debugRobDeallocCount := live.io.deallocCount
  io.debugRobDeallocBlockLastValid := live.io.robDeallocBlockLastValid
  io.debugRobDeallocBlockLastBlockBid := live.io.robDeallocBlockLastBlockBid
  io.debugBlockScalarDoneFire := live.io.blockScalarDoneFire
  io.debugBlockScalarDoneBid := live.io.blockScalarDoneBid
  io.debugBlockRetireFire := live.io.blockRetireFire
  io.debugBlockRetireBid := live.io.blockRetireBid
  io.debugDecRenHeadRidValid := live.io.decRenHeadRidValid
  io.debugDecRenHeadRidValue := live.io.decRenHeadRidValue
  io.debugRenamedOutValid := live.io.renamedOutValid
  io.debugRenamedAccepted := live.io.renamedAccepted
  io.debugIssueEnqueueFire := live.io.issueQueueEnqueueFire
  io.debugIssueInputValid := live.io.issueQueueInputValid
  io.debugIssueInputPc := live.io.issueQueueInputPc
  io.debugIssueInputOpcode := live.io.issueQueueInputOpcode
  io.debugIssueInputBidValid := live.io.issueQueueInputBidValid
  io.debugIssueInputBidWrap := live.io.issueQueueInputBidWrap
  io.debugIssueInputBidValue := live.io.issueQueueInputBidValue
  io.debugIssueInputRidValid := live.io.issueQueueInputRidValid
  io.debugIssueInputRidWrap := live.io.issueQueueInputRidWrap
  io.debugIssueInputRidValue := live.io.issueQueueInputRidValue
  io.debugIssueInputStid := live.io.issueQueueInputStid
  io.debugIssuePickFire := live.io.issueQueuePickFire
  io.debugIssueFire := live.io.issueQueueIssueFire
  io.debugIssueOutputValid := live.io.issueQueueOutputValid
  io.debugIssueOutputPc := live.io.issueQueueOutputPc
  io.debugIssueOutputOpcode := live.io.issueQueueOutputOpcode
  io.debugIssueOutputBidValid := live.io.issueQueueOutputBidValid
  io.debugIssueOutputBidWrap := live.io.issueQueueOutputBidWrap
  io.debugIssueOutputBidValue := live.io.issueQueueOutputBidValue
  io.debugIssueOutputRidValid := live.io.issueQueueOutputRidValid
  io.debugIssueOutputRidWrap := live.io.issueQueueOutputRidWrap
  io.debugIssueOutputRidValue := live.io.issueQueueOutputRidValue
  io.debugIssueOutputStid := live.io.issueQueueOutputStid
  io.debugIssueHeadValid := live.io.issueQueueHeadValid
  io.debugIssueHeadIssued := live.io.issueQueueHeadIssued
  io.debugIssueHeadPc := live.io.issueQueueHeadPc
  io.debugIssueHeadStid := live.io.issueQueueHeadStid
  io.debugIssueHeadBidValid := live.io.issueQueueHeadBidValid
  io.debugIssueHeadBidWrap := live.io.issueQueueHeadBidWrap
  io.debugIssueHeadBidValue := live.io.issueQueueHeadBidValue
  io.debugIssueHeadRidValid := live.io.issueQueueHeadRidValid
  io.debugIssueHeadRidWrap := live.io.issueQueueHeadRidWrap
  io.debugIssueHeadRidValue := live.io.issueQueueHeadRidValue
  io.debugIssueHeadSrcValidMask := live.io.issueQueueHeadSrcValidMask
  io.debugIssueHeadSrcPhysTag := live.io.issueQueueHeadSrcPhysTag
  io.debugIssueSourceReadyMask := live.io.issueQueueSourceReadyMask
  io.debugIssueAllSourcesReady := live.io.issueQueueAllSourcesReady
  io.debugIssueSelectedValid := live.io.issueQueueSelectedValid
  io.debugIssueSelectedIndex := live.io.issueQueueSelectedIndex
  io.debugIssueSelectedReadReady := live.io.issueQueueSelectedReadReady
  io.debugIssueStageBits := Cat(live.io.issueQueueI2Valid, live.io.issueQueueI1Valid)
  io.debugIssueBlockedBits := Cat(
    live.io.issueQueueBlockedByIssued,
    live.io.issueQueueBlockedByOutput,
    live.io.issueQueueBlockedByRead,
    live.io.issueQueueBlockedBySource)
  io.debugIssueScalarSpOrderBlocked := live.io.issueQueueScalarSpOrderBlocked
  io.debugIssueBankScalarSpOrderBlockedMask := live.io.issueQueueBankScalarSpOrderBlockedMask
  io.debugScalarSpStid0IssueHeadValid := live.io.scalarSpStid0IssueHeadValid
  io.debugScalarSpStid0IssueHeadBidValid := live.io.scalarSpStid0IssueHeadBidValid
  io.debugScalarSpStid0IssueHeadBidWrap := live.io.scalarSpStid0IssueHeadBidWrap
  io.debugScalarSpStid0IssueHeadBidValue := live.io.scalarSpStid0IssueHeadBidValue
  io.debugScalarSpStid0IssueHeadRidValid := live.io.scalarSpStid0IssueHeadRidValid
  io.debugScalarSpStid0IssueHeadRidWrap := live.io.scalarSpStid0IssueHeadRidWrap
  io.debugScalarSpStid0IssueHeadRidValue := live.io.scalarSpStid0IssueHeadRidValue
  io.debugRfReadyMask := live.io.rfReadyMask
  io.debugPWakeupValid := live.io.rfWriteValid
  io.debugPWakeupTag := live.io.rfWriteTag
  io.debugExecuteAccepted := live.io.executeAccepted
  io.debugExecuteAcceptedIdentityValid := live.io.executeAcceptedIdentityValid
  io.debugExecuteAcceptedPc := live.io.executeAcceptedPc
  io.debugExecuteAcceptedOpcode := live.io.executeAcceptedOpcode
  io.debugExecuteAcceptedBidValid := live.io.executeAcceptedBidValid
  io.debugExecuteAcceptedBidWrap := live.io.executeAcceptedBidWrap
  io.debugExecuteAcceptedBidValue := live.io.executeAcceptedBidValue
  io.debugExecuteAcceptedRidValid := live.io.executeAcceptedRidValid
  io.debugExecuteAcceptedRidWrap := live.io.executeAcceptedRidWrap
  io.debugExecuteAcceptedRidValue := live.io.executeAcceptedRidValue
  io.debugExecuteAcceptedStid := live.io.executeAcceptedStid
  io.debugExecuteBusy := live.io.executeBusy
  io.debugExecuteUnsupported := live.io.executeUnsupported
  io.debugExecuteUnsupportedOpcode := live.io.executeUnsupportedOpcode
  io.debugExecuteCompleteRobValue := live.io.executeCompleteRobValue
  io.debugExecuteCompleteSrcPhysValidMask := live.io.executeCompleteSrcPhysValidMask
  io.debugExecuteCompleteSrcPhysTag := live.io.executeCompleteSrcPhysTag
  io.debugRobCompleteArbiterBits := Cat(
    live.io.robCompleteArbiterReplayBlockedByExecute,
    live.io.robCompleteArbiterSelectedReplay,
    live.io.robCompleteArbiterSelectedExecute)
  io.debugRobCompleteResultBits := Cat(live.io.completeIgnored, live.io.completeAccepted)
  io.scalarLrReservationValidStid0 := live.io.scalarLrReservationValidStid0
  io.scalarLrReservationLineStid0 := live.io.scalarLrReservationLineStid0
  io.scalarLrReservationCount := live.io.scalarLrReservationCount
  io.scalarLrReservationProtocolError := live.io.scalarLrReservationProtocolError
  io.scalarLrReservationBlockedByFlush := live.io.scalarLrReservationBlockedByFlush
  io.scalarLrReservationCommittedStoreInvalidate := live.io.scalarLrReservationCommittedStoreInvalidate
  io.storeStaQueueValid := live.io.storeStaQueueValid
  io.storeStdQueueValid := live.io.storeStdQueueValid
  io.storeStaDequeueFire := live.io.storeStaDequeueFire
  io.storeStdDequeueFire := live.io.storeStdDequeueFire
  io.storeStaQueueCount := live.io.storeStaQueueCount
  io.storeStdQueueCount := live.io.storeStdQueueCount
  io.storeStaInsertReady := live.io.storeStaInsertReady
  io.storeStdInsertReady := live.io.storeStdInsertReady
  io.storeSelectedSta := live.io.storeSelectedSta
  io.storeSelectedStd := live.io.storeSelectedStd
  io.storeBlockedByStaExec := live.io.storeBlockedByStaExec
  io.storeBlockedByStdExec := live.io.storeBlockedByStdExec
  io.storeStqInsertValid := live.io.storeStqInsertValid
  io.storeStqInsertAccepted := live.io.storeStqInsertAccepted
  io.storeStqInsertConflict := live.io.storeStqInsertConflict
  io.storeStqInsertIndex := live.io.storeStqInsertIndex
  io.storeStqOccupiedMask := live.io.storeStqOccupiedMask
  io.storeStqWaitMask := live.io.storeStqWaitMask
  io.storeStqCommitMask := live.io.storeStqCommitMask
  io.storeStqAddrReadyMask := live.io.storeStqAddrReadyMask
  io.storeStqDataReadyMask := live.io.storeStqDataReadyMask
  io.storeStqResidentCount := live.io.storeStqResidentCount
  io.storeStqOutstandingWaitCount := live.io.storeStqOutstandingWaitCount
  io.storeStqEmpty := live.io.storeStqEmpty
  io.storeStqFull := live.io.storeStqFull
  io.storeStqStall := live.io.storeStqStall

  io.loadLookupValid := live.io.loadLookupValid && !sourceBlocked
  io.loadLookupAddr := live.io.loadLookupAddr
  io.loadPairFirstLookupValid := live.io.loadPairFirstLookupValid && !sourceBlocked
  io.loadPairFirstLookupAddr := live.io.loadPairFirstLookupAddr
  io.loadLookupPc := live.io.loadLookupPc
  io.loadLookupExecuteGranted := live.io.loadLookupExecuteGranted
  io.loadLookupReplayGranted := live.io.loadLookupReplayGranted
  io.loadLookupDstValid := live.io.loadLookupDstValid
  io.loadLookupDstKind := live.io.loadLookupDstKind
  io.loadLookupDstArchTag := live.io.loadLookupDstArchTag
  io.loadLookupDstRelTag := live.io.loadLookupDstRelTag
  io.loadLookupDstPhysTag := live.io.loadLookupDstPhysTag
  io.loadLookupDstOldPhysTag := live.io.loadLookupDstOldPhysTag

  io.commit := live.io.commit

  io.storeObserveValid := selectedSideEffectValid
  io.storeObserveAddr := selectedSideEffect.addr
  io.storeObserveData := selectedSideEffect.data
  io.storeObserveSize := selectedSideEffect.size
  io.storeObserveMask := selectedSideEffect.mask
  io.storeObservePairValid := selectedSideEffectValid && selectedSideEffect.pairValid
  io.storeObservePairAddr := selectedSideEffect.pairAddr
  io.storeObservePairData := selectedSideEffect.pairData
  io.storeObservePairSize := selectedSideEffect.pairSize
  io.storeObservePairMask := selectedSideEffect.pairMask
  io.storeObservePc := selectedSideEffect.pc
  io.storeObserveSeq := selectedSideEffect.seq
  io.storeObserveCycle := selectedSideEffect.cycle
  io.storeObserveSlot := selectedSideEffect.slot
  io.storeObserveBid := selectedSideEffect.bid
  io.storeObserveGid := selectedSideEffect.gid
  io.storeObserveRid := selectedSideEffect.rid
  io.storeObserveRobValid := selectedSideEffect.robValid
  io.storeObserveRobWrap := selectedSideEffect.robWrap
  io.storeObserveRobValue := selectedSideEffect.robValue
  io.storeObserveBlockBidValid := selectedSideEffect.blockBidValid
  io.storeObserveBlockBid := selectedSideEffect.blockBid

  io.uartWriteValid := selectedSideEffectValid && selectedSideEffect.uartWrite
  io.uartWriteByte := selectedSideEffect.uartByte
  io.finisherWriteValid := selectedSideEffectValid && selectedSideEffect.finisherWrite
  io.finisherCode := Mux(finisherSeenReg, finisherCodeReg, selectedSideEffect.finisherCode)
  io.finisherPayload := Mux(finisherSeenReg, finisherPayloadReg, selectedSideEffect.finisherPayload)
  io.finisherPass := finisherSeenReg && finisherPassReg

  io.bootSp := bootSpReg
  io.active := live.io.sourceActive && !sourceBlocked
  io.halted := haltedReg || trapReg || finisherSeenReg
  io.trapValid := trapReg
  io.unsupportedInstruction := unsupportedReg
  io.unsupportedPc := unsupportedPcReg
  io.status := statusReg
}

object EmitLinxCoreBenchmarkAutonomousTop extends App {
  circt.stage.ChiselStage.emitSystemVerilogFile(
    new LinxCoreBenchmarkAutonomousTop(),
    args = Array("--target-dir", "generated/chisel")
  )
}
