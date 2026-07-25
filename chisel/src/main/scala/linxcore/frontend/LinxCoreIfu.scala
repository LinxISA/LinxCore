package linxcore.frontend

import chisel3._
import chisel3.util.{Decoupled, Valid, log2Ceil}
import linxcore.common.InterfaceParams

class LinxCoreIfuIO(
    val p: InterfaceParams = InterfaceParams(),
    val threadCount: Int = 1,
    val lineBytes: Int = 64,
    val pageBytes: Int = 4096,
    val missEntries: Int = 4,
    val joinEntries: Int = 8,
    val instructionBufferDepth: Int = 16)
    extends Bundle {
  private val joinCountWidth = log2Ceil(joinEntries + 1)
  private val missMaskWidth = missEntries

  val start = Flipped(Valid(new ISideStartRequest(p)))
  val backendRedirect = Flipped(Decoupled(new IfuInnerFlush(p)))
  val branchResolve = Flipped(Decoupled(new BSideResolveUpdate(p)))

  val ptwRequest = Decoupled(new ISidePtwRequest(p, lineBytes, pageBytes))
  val ptwRefill = Flipped(Valid(new ISideItlbRefill(p, pageBytes)))
  val lineRead = Decoupled(new ISideLineReadRequest(p, lineBytes))
  val lineRefill = Flipped(Decoupled(new ISideLineResponse(p, lineBytes)))
  val fetchFault = Decoupled(new ISideFetchFault(p, lineBytes))

  val invalidateItlb = Input(Bool())
  val invalidateL1I = Input(Bool())
  val d1ThreadId = Input(UInt(p.threadIdWidth.W))
  val d1 = Decoupled(new D1InstructionGroup(p))

  val canonicalFlush = Valid(new IfuInnerFlush(p))
  val active = Output(Vec(threadCount, Bool()))
  val currentPc = Output(Vec(threadCount, UInt(p.pcWidth.W)))
  val epochs = Output(Vec(threadCount, UInt(p.blockEpochWidth.W)))
  val missValidMask = Output(UInt(missMaskWidth.W))
  val missOrphanMask = Output(UInt(missMaskWidth.W))
  val joinCount = Output(UInt(joinCountWidth.W))
  val lineContextCount = Output(UInt(joinCountWidth.W))
  val lineContextCompletedMask = Output(UInt(joinEntries.W))
  val bSideStageValid = Output(UInt(5.W))
  val ptwPending = Output(Bool())
  val crossLinePending = Output(Bool())
  val f3WaitingForNextLine = Output(Bool())
  val staleF2Result = Output(Bool())
}

/** Production IFU composition.
  *
  * I-SIDE and B-SIDE are independent decoupled engines. I-SIDE owns F0 through
  * F4, parallel ITLB/L1I lookup, miss/refill replay, exact cross-line assembly,
  * the final-prediction join, the instruction buffer, and D1 gathering.
  * B-SIDE owns B-F0 through B-F4 and receives only an F0 request plus the exact
  * boundary-completion message from I-F4. All redirect proposals pass through
  * one canonical epoch allocator before the accepted flush is broadcast.
  */
class LinxCoreIfu(
    val p: InterfaceParams = InterfaceParams(),
    val threadCount: Int = 1,
    val lineBytes: Int = 64,
    val pageBytes: Int = 4096,
    val itlbEntries: Int = 16,
    val l1iSets: Int = 64,
    val missEntries: Int = 4,
    val joinEntries: Int = 8,
    val maxGroupsPerTransaction: Int = 8,
    val instructionBufferDepth: Int = 16)
    extends Module {
  require(p.fetchWidth == 4)
  require(p.decodeWidth == 4)
  require(p.insnWidth == 64)

  val io = IO(
    new LinxCoreIfuIO(
      p,
      threadCount,
      lineBytes,
      pageBytes,
      missEntries,
      joinEntries,
      instructionBufferDepth))

  val f0 = Module(new ISideF0PcSelect(p, threadCount, lineBytes))
  val f1 = Module(new ISideF1Lookup(p, lineBytes))
  val itlb = Module(new ISideITLB(p, itlbEntries, lineBytes, pageBytes))
  val l1i = Module(new ISideL1I(p, l1iSets, lineBytes))
  val f2 = Module(new ISideF2Resolve(p, lineBytes, pageBytes))
  val misses = Module(new ISideFetchMissTable(p, missEntries, lineBytes))
  val lineContexts = Module(new ISideLineContextQueue(p, lineBytes, joinEntries))
  val f3 = Module(new ISideF3LineAssembler(p, lineBytes))
  val f4 = Module(new ISideF4Predecode(p, threadCount))
  val bSide = Module(new BSidePredictionPipeline(p, lineBytes, threadCount))
  val redirects = Module(new IfuRedirectArbiter(p, threadCount))
  val join =
    Module(new IfuPredictionJoin(p, lineBytes, joinEntries, maxGroupsPerTransaction))
  val instructionBuffer =
    Module(new InstructionBuffer(p, instructionBufferDepth, threadCount))
  val d1 = Module(new D1DecodeGroupGather(p))

  val noFlush = 0.U.asTypeOf(new IfuInnerFlush(p))
  val acceptedRedirect = Wire(new IfuInnerFlush(p))
  acceptedRedirect := noFlush
  acceptedRedirect.valid := redirects.io.out.valid
  when(redirects.io.out.valid) {
    acceptedRedirect := redirects.io.out.bits
    acceptedRedirect.valid := true.B
  }
  redirects.io.out.ready := true.B

  f0.io.start := io.start
  f0.io.backendRestart.valid := false.B
  f0.io.backendRestart.bits := 0.U.asTypeOf(f0.io.backendRestart.bits)
  f0.io.predictionCorrection := acceptedRedirect
  // F0 advances the speculative sequential frontier on every accepted line.
  // A delayed non-correcting B-F4 final must never move that frontier backward;
  // only the canonical correction/recovery path may replace it.
  f0.io.resolvedNextPc.valid := false.B
  f0.io.resolvedNextPc.bits := 0.U.asTypeOf(f0.io.resolvedNextPc.bits)

  redirects.io.epochSeed.valid := f0.io.startAccepted
  redirects.io.epochSeed.bits.threadId := io.start.bits.threadId
  redirects.io.epochSeed.bits.epoch := f0.io.startEpoch
  redirects.io.backend.valid := io.backendRedirect.valid
  redirects.io.backend.bits := io.backendRedirect.bits
  io.backendRedirect.ready := redirects.io.backend.ready
  redirects.io.itlb <> f2.io.innerFlush
  redirects.io.prediction <> bSide.io.innerFlush

  val startFlush = Wire(new IfuInnerFlush(p))
  startFlush := noFlush
  startFlush.valid := f0.io.startAccepted
  startFlush.peId := io.start.bits.peId
  startFlush.threadId := io.start.bits.threadId
  startFlush.restartPc := io.start.bits.pc
  startFlush.newEpoch := f0.io.startEpoch
  startFlush.reason := IfuInnerFlushReason.FetchReplay
  startFlush.scope := IfuPruneScope.KillAllThreadState
  startFlush.ghrAction := GhrRecoveryAction.Reset

  val stateFlush = Wire(new IfuInnerFlush(p))
  stateFlush := Mux(f0.io.startAccepted, startFlush, acceptedRedirect)

  itlb.io.innerFlush := stateFlush
  l1i.io.innerFlush := stateFlush
  f2.io.externalFlush := stateFlush
  misses.io.innerFlush := stateFlush
  lineContexts.io.flush := stateFlush
  f3.io.flush := stateFlush
  f4.io.flush := stateFlush
  bSide.io.prune := stateFlush
  join.io.flush := stateFlush
  instructionBuffer.io.flush := stateFlush
  d1.io.flush := stateFlush

  itlb.io.invalidate := io.invalidateItlb
  itlb.io.refill := io.ptwRefill
  l1i.io.invalidate := io.invalidateL1I
  l1i.io.refill <> misses.io.l1iRefill
  misses.io.refill.valid := io.lineRefill.valid
  misses.io.refill.bits := io.lineRefill.bits
  io.lineRefill.ready := misses.io.refill.ready

  val ptwPendingValid = RegInit(false.B)
  val ptwPendingRequest =
    RegInit(0.U.asTypeOf(new ISideFetchRequest(p, lineBytes)))

  // One normal lookup port serves replay, cross-line continuation, and new F0
  // traffic. Replay has priority so a filled miss cannot starve behind fetch.
  val lookupFromRetry = misses.io.retry.valid
  val successorContextResident =
    lineContexts.io.headValid &&
      lineContexts.io.headRequest.identity.peId ===
        f3.io.nextLineRequest.bits.identity.peId &&
      lineContexts.io.headRequest.transactionId ===
        f3.io.nextLineRequest.bits.transactionId + 1.U &&
      lineContexts.io.headRequest.identity.threadId ===
        f3.io.nextLineRequest.bits.identity.threadId &&
      lineContexts.io.headRequest.identity.fetchPacketUid ===
        f3.io.nextLineRequest.bits.identity.fetchPacketUid + 1.U &&
      lineContexts.io.headRequest.identity.fetchSeq ===
        f3.io.nextLineRequest.bits.identity.fetchSeq + 1.U &&
      lineContexts.io.headRequest.identity.checkpointId ===
        f3.io.nextLineRequest.bits.identity.checkpointId + 1.U &&
      lineContexts.io.headRequest.identity.epoch ===
        f3.io.nextLineRequest.bits.identity.epoch &&
      lineContexts.io.headRequest.lineVa === f3.io.nextLineRequest.bits.lineVa
  val successorContextMatchesContinuation =
    successorContextResident && lineContexts.io.headCompleted
  val contextContinuationPending = RegInit(false.B)
  val contextContinuationResponse =
    RegInit(0.U.asTypeOf(new ISideLineResponse(p, lineBytes)))
  val lookupFromContext =
    !lookupFromRetry &&
      f3.io.nextLineRequest.valid &&
      successorContextMatchesContinuation &&
      !contextContinuationPending
  val lookupFromCrossLine =
    !lookupFromRetry &&
      f3.io.nextLineRequest.valid &&
      !successorContextResident &&
      !contextContinuationPending
  val lookupFromF0 =
    !lookupFromRetry &&
      !f3.io.nextLineRequest.valid &&
      f0.io.fetch.valid &&
      !ptwPendingValid &&
      join.io.allocate.ready &&
      lineContexts.io.allocate.ready

  f1.io.in.valid :=
    !stateFlush.valid &&
      (lookupFromRetry || lookupFromCrossLine || lookupFromF0)
  f1.io.in.bits := Mux(
    lookupFromRetry,
    misses.io.retry.bits,
    Mux(lookupFromCrossLine, f3.io.nextLineRequest.bits, f0.io.fetch.bits))

  misses.io.retry.ready :=
    !stateFlush.valid &&
      lookupFromRetry &&
      f1.io.in.ready
  f3.io.nextLineRequest.ready :=
    !stateFlush.valid &&
      (lookupFromContext || (lookupFromCrossLine && f1.io.in.ready))
  f0.io.fetch.ready :=
    !stateFlush.valid &&
      lookupFromF0 &&
      f1.io.in.ready

  join.io.allocate.valid :=
    !stateFlush.valid &&
      lookupFromF0 &&
      f1.io.in.ready
  join.io.allocate.bits := f0.io.fetch.bits

  lineContexts.io.allocate.valid :=
    !stateFlush.valid &&
      lookupFromF0 &&
      f1.io.in.ready
  lineContexts.io.allocate.bits := f0.io.fetch.bits

  f1.io.itlbRequest <> itlb.io.lookup
  f1.io.l1iRequest <> l1i.io.lookup
  f2.io.translation <> itlb.io.response
  f2.io.cacheCandidate <> l1i.io.response

  val crossLineRequestValid = RegInit(false.B)
  val crossLineRequest =
    RegInit(0.U.asTypeOf(new ISideFetchRequest(p, lineBytes)))

  def exactRequest(lhs: ISideFetchRequest, rhs: ISideFetchRequest): Bool =
    lhs.identity.peId === rhs.identity.peId &&
      lhs.transactionId === rhs.transactionId &&
      lhs.identity.threadId === rhs.identity.threadId &&
      lhs.identity.fetchPacketUid === rhs.identity.fetchPacketUid &&
      lhs.identity.fetchSeq === rhs.identity.fetchSeq &&
      lhs.identity.checkpointId === rhs.identity.checkpointId &&
      lhs.identity.epoch === rhs.identity.epoch &&
      lhs.lineVa === rhs.lineVa

  val f2CrossLine =
    crossLineRequestValid &&
      exactRequest(f2.io.result.bits.request, crossLineRequest)
  val f2Hit = f2.io.result.bits.status === ISideF2Status.Hit
  val f2ItlbMiss = f2.io.result.bits.status === ISideF2Status.ItlbMiss
  val f2AccessFault = f2.io.result.bits.status === ISideF2Status.AccessFault
  val f2L1IMiss = f2.io.result.bits.status === ISideF2Status.L1IMiss
  val f2Stale = f2.io.result.bits.status === ISideF2Status.Stale

  lineContexts.io.complete.valid :=
    f2.io.result.valid &&
      f2Hit &&
      !f2CrossLine
  lineContexts.io.complete.bits := f2.io.result.bits

  f3.io.in <> lineContexts.io.out
  lineContexts.io.carry := f3.io.prefixCarry

  val f2NextLineResponse = Wire(new ISideLineResponse(p, lineBytes))
  f2NextLineResponse := 0.U.asTypeOf(f2NextLineResponse)
  f2NextLineResponse.peId := f2.io.result.bits.request.identity.peId
  f2NextLineResponse.transactionId := f2.io.result.bits.request.transactionId
  f2NextLineResponse.threadId := f2.io.result.bits.request.identity.threadId
  f2NextLineResponse.fetchPacketUid := f2.io.result.bits.request.identity.fetchPacketUid
  f2NextLineResponse.fetchSeq := f2.io.result.bits.request.identity.fetchSeq
  f2NextLineResponse.checkpointId := f2.io.result.bits.request.identity.checkpointId
  f2NextLineResponse.epoch := f2.io.result.bits.request.identity.epoch
  f2NextLineResponse.lineVa := f2.io.result.bits.request.lineVa
  f2NextLineResponse.linePa := f2.io.result.bits.linePa
  f2NextLineResponse.lineData := f2.io.result.bits.lineData

  f3.io.nextLineResponse.valid :=
    contextContinuationPending ||
      (f2.io.result.valid && f2Hit && f2CrossLine)
  f3.io.nextLineResponse.bits :=
    Mux(contextContinuationPending, contextContinuationResponse, f2NextLineResponse)

  when(stateFlush.valid) {
    contextContinuationPending := false.B
  }.elsewhen(f3.io.nextLineResponse.fire && contextContinuationPending) {
    contextContinuationPending := false.B
  }
  when(f3.io.nextLineRequest.fire && lookupFromContext) {
    contextContinuationPending := true.B
    contextContinuationResponse := 0.U.asTypeOf(contextContinuationResponse)
    contextContinuationResponse.peId := f3.io.nextLineRequest.bits.identity.peId
    contextContinuationResponse.transactionId := f3.io.nextLineRequest.bits.transactionId
    contextContinuationResponse.threadId := f3.io.nextLineRequest.bits.identity.threadId
    contextContinuationResponse.fetchPacketUid :=
      f3.io.nextLineRequest.bits.identity.fetchPacketUid
    contextContinuationResponse.fetchSeq := f3.io.nextLineRequest.bits.identity.fetchSeq
    contextContinuationResponse.checkpointId :=
      f3.io.nextLineRequest.bits.identity.checkpointId
    contextContinuationResponse.epoch := f3.io.nextLineRequest.bits.identity.epoch
    contextContinuationResponse.lineVa := f3.io.nextLineRequest.bits.lineVa
    contextContinuationResponse.linePa := lineContexts.io.out.bits.linePa
    contextContinuationResponse.lineData := lineContexts.io.out.bits.lineData
  }

  io.ptwRequest.valid := f2.io.result.valid && f2ItlbMiss
  io.ptwRequest.bits := 0.U.asTypeOf(io.ptwRequest.bits)
  io.ptwRequest.bits.request := f2.io.result.bits.request
  io.ptwRequest.bits.vpn :=
    f2.io.result.bits.request.lineVa(p.pcWidth - 1, log2Ceil(pageBytes))

  val ptwPendingVpn =
    ptwPendingRequest.lineVa(p.pcWidth - 1, log2Ceil(pageBytes))
  val cancelPtwPending =
    ptwPendingValid &&
      stateFlush.valid &&
      stateFlush.reason =/= IfuInnerFlushReason.ItlbMiss &&
      IfuFlushContract.kills(
        ptwPendingRequest.identity,
        ptwPendingRequest.transactionId,
        stateFlush)
  val completePtwPending =
    ptwPendingValid &&
      io.ptwRefill.valid &&
      io.ptwRefill.bits.vpn === ptwPendingVpn

  when(cancelPtwPending || completePtwPending) {
    ptwPendingValid := false.B
  }
  when(io.ptwRequest.fire) {
    assert(!ptwPendingValid, "one unresolved IFU transaction may own only one PTW miss")
    ptwPendingValid := true.B
    ptwPendingRequest := f2.io.result.bits.request
  }

  io.fetchFault.valid := f2.io.result.valid && f2AccessFault
  io.fetchFault.bits.request := f2.io.result.bits.request
  io.fetchFault.bits.linePa := f2.io.result.bits.linePa

  io.lineRead.valid :=
    f2.io.result.valid &&
      f2L1IMiss &&
      misses.io.allocate.ready
  io.lineRead.bits.request := f2.io.result.bits.request
  io.lineRead.bits.linePa := f2.io.result.bits.linePa
  misses.io.allocate.valid :=
    f2.io.result.valid &&
      f2L1IMiss &&
      io.lineRead.ready
  misses.io.allocate.bits := f2.io.result.bits

  f2.io.result.ready := Mux(
    f2Hit,
    Mux(f2CrossLine, f3.io.nextLineResponse.ready, lineContexts.io.complete.ready),
    Mux(
      f2ItlbMiss,
      io.ptwRequest.ready,
      Mux(
        f2AccessFault,
        io.fetchFault.ready,
        Mux(f2L1IMiss, misses.io.allocate.ready && io.lineRead.ready, true.B))))

  when(f3.io.nextLineRequest.fire && lookupFromCrossLine) {
    crossLineRequestValid := true.B
    crossLineRequest := f3.io.nextLineRequest.bits
  }
  when(
    stateFlush.valid &&
      crossLineRequestValid &&
      IfuFlushContract.kills(
        crossLineRequest.identity,
        crossLineRequest.transactionId,
        stateFlush)) {
    crossLineRequestValid := false.B
  }.elsewhen(f2.io.result.fire && f2CrossLine && (f2Hit || f2ItlbMiss || f2AccessFault)) {
    crossLineRequestValid := false.B
  }

  f3.io.terminateResident := f4.io.acceptedStop
  f4.io.in <> f3.io.out
  join.io.iSide <> f4.io.out
  bSide.io.boundary <> f4.io.boundary

  bSide.io.request <> f0.io.predictionRequest
  bSide.io.resolve.valid := io.branchResolve.valid
  bSide.io.resolve.bits := io.branchResolve.bits
  io.branchResolve.ready := bSide.io.resolve.ready
  join.io.prediction <> bSide.io.response

  instructionBuffer.io.enq <> join.io.out
  instructionBuffer.io.deqThreadId := io.d1ThreadId
  d1.io.in <> instructionBuffer.io.deq
  io.d1 <> d1.io.out

  io.canonicalFlush.valid := acceptedRedirect.valid
  io.canonicalFlush.bits := acceptedRedirect
  io.active := f0.io.active
  io.currentPc := f0.io.currentPc
  io.epochs := redirects.io.epochs
  io.missValidMask := misses.io.validMask
  io.missOrphanMask := misses.io.orphanMask
  io.joinCount := join.io.count
  io.lineContextCount := lineContexts.io.count
  io.lineContextCompletedMask := lineContexts.io.completedMask
  io.bSideStageValid := bSide.io.stageValid
  io.ptwPending := ptwPendingValid
  io.crossLinePending := crossLineRequestValid || contextContinuationPending
  io.f3WaitingForNextLine := f3.io.waitingForNextLine
  io.staleF2Result := f2.io.result.valid && f2Stale

  when(f0.io.fetch.fire) {
    assert(join.io.allocate.fire, "F0 lookup and final-prediction join allocation must be atomic")
    assert(
      lineContexts.io.allocate.fire,
      "F0 lookup and ordered line-context allocation must be atomic")
  }
  when(join.io.allocate.fire) {
    assert(f0.io.fetch.fire, "join rows may only be allocated by an accepted F0 transaction")
  }
  when(lineContexts.io.allocate.fire) {
    assert(f0.io.fetch.fire, "line contexts may only be allocated by an accepted F0 transaction")
  }
  when(
    f3.io.nextLineRequest.valid &&
      successorContextResident &&
      !lineContexts.io.headCompleted) {
    assert(
      !f3.io.nextLineRequest.ready,
      "I-F3 must await an incomplete prefetched successor instead of launching a duplicate lookup")
  }
  when(f2.io.result.fire && f2L1IMiss) {
    assert(
      misses.io.allocate.fire && io.lineRead.fire,
      "L1I miss-table allocation and external line request must be atomic")
  }
}
