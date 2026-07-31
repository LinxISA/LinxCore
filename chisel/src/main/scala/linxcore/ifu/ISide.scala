package linxcore.ifu

import chisel3._
import chisel3.util.{Cat, Decoupled, MuxCase, PopCount, Valid, is, log2Ceil, switch}
import linxcore.common.{BoundaryKind, InterfaceParams}
import linxcore.frontend._
import linxcore.params.CoreParams
import linxcore.top.interface._

private object ISideMemoryKind extends ChiselEnum {
  val Line, Translation = Value
}

object ISide {
  def interfaceParams(p: CoreParams): InterfaceParams = {
    val ingressWidth = 4
    val checkpointWidth =
      math.max(1, log2Ceil(p.ifu.predictionCheckpointEntries))
    val threadWidth = math.max(1, log2Ceil(p.ooo.stidCount))
    InterfaceParams(
      fetchWidth = ingressWidth,
      decodeWidth = ingressWidth,
      issueWidth = 4,
      commitWidth = 4,
      pcWidth = p.pcWidth,
      windowWidth = 64,
      opcodeWidth = p.opcodeWidth,
      insnWidth = p.instructionWidth,
      lenWidth = 4,
      archRegWidth = p.archRegWidth,
      physRegWidth = math.max(6, log2Ceil(p.ooo.gprPhysRegs)),
      robEntries = p.ooo.robGroupsPerStid,
      iqEntries = p.iex.scalarIssueEntries,
      blockBidWidth = 64,
      blockUidWidth = p.instructionIdWidth,
      uopUidWidth = p.transactionIdWidth,
      lsidWidth = p.lsidWidth,
      checkpointWidth = checkpointWidth,
      peIdWidth = p.peIdWidth,
      threadIdWidth = threadWidth,
      trapCauseWidth = p.trapCauseWidth,
      blockEpochWidth = p.epochWidth)
  }
}

private class ISideMemoryFault(
    val p: CoreParams,
    val fp: InterfaceParams)
    extends Bundle {
  val request =
    new ISideFetchRequest(fp, p.ifu.lineBytes)
  val cause = UInt(p.trapCauseWidth.W)
}

private class ISideMemoryAdapterIO(
    val p: CoreParams,
    val fp: InterfaceParams)
    extends Bundle {
  val lineRequest =
    Flipped(Decoupled(new ISideLineReadRequest(fp, p.ifu.lineBytes)))
  val lineRefill =
    Decoupled(new ISideLineResponse(fp, p.ifu.lineBytes))
  val translationRequest =
    Flipped(Decoupled(
      new ISidePtwRequest(fp, p.ifu.lineBytes, p.ifu.pageBytes)))
  val translationRefill =
    Valid(new ISideItlbRefill(fp, p.ifu.pageBytes))
  val lineFault = Decoupled(new ISideMemoryFault(p, fp))
  val memoryRequest = Decoupled(new MemoryRequestTxn(p))
  val memoryResponse = Flipped(Decoupled(new MemoryResponseTxn(p)))
  val staleResponse = Output(Bool())
}

/** Exact-identity adapter from retained IFU line/PTW traffic to 64-bit beats. */
private class ISideMemoryAdapter(
    val p: CoreParams,
    val fp: InterfaceParams)
    extends Module {
  private val beatBytes = p.dataWidth / 8
  private val beatsPerLine = p.ifu.lineBytes / beatBytes
  private val beatWidth = math.max(1, log2Ceil(beatsPerLine))
  private val pageOffsetBits = log2Ceil(p.ifu.pageBytes)
  private val ppnWidth = p.pcWidth - pageOffsetBits

  require(p.ifu.lineBytes % beatBytes == 0)
  require(beatsPerLine > 0)

  val io = IO(new ISideMemoryAdapterIO(p, fp))

  val active = RegInit(false.B)
  val kind = RegInit(ISideMemoryKind.Line)
  val lineRequest =
    RegInit(0.U.asTypeOf(new ISideLineReadRequest(fp, p.ifu.lineBytes)))
  val translationRequest =
    RegInit(0.U.asTypeOf(
      new ISidePtwRequest(fp, p.ifu.lineBytes, p.ifu.pageBytes)))
  val beat = RegInit(0.U(beatWidth.W))
  val lineBeats =
    RegInit(VecInit(Seq.fill(beatsPerLine)(0.U(p.dataWidth.W))))

  val requestOutstanding = RegInit(false.B)
  val outstandingIdentity =
    RegInit(0.U.asTypeOf(new MemoryTransactionIdentity(p)))
  val nextValue = RegInit(0.U(p.memoryTransactionIdWidth.W))
  val nextGeneration = RegInit(0.U(p.memoryTransactionGenerationWidth.W))

  val lineRefillValid = RegInit(false.B)
  val lineRefillData =
    RegInit(0.U((p.ifu.lineBytes * 8).W))
  val lineFaultValid = RegInit(false.B)
  val lineFaultRequest =
    RegInit(0.U.asTypeOf(
      new ISideFetchRequest(fp, p.ifu.lineBytes)))
  val lineFaultCause = RegInit(0.U(p.trapCauseWidth.W))

  val idle = !active && !lineRefillValid && !lineFaultValid
  io.translationRequest.ready := idle
  io.lineRequest.ready := idle && !io.translationRequest.valid

  when(io.translationRequest.fire) {
    active := true.B
    kind := ISideMemoryKind.Translation
    translationRequest := io.translationRequest.bits
    beat := 0.U
  }.elsewhen(io.lineRequest.fire) {
    active := true.B
    kind := ISideMemoryKind.Line
    lineRequest := io.lineRequest.bits
    beat := 0.U
  }

  io.memoryRequest.valid := active && !requestOutstanding
  io.memoryRequest.bits := 0.U.asTypeOf(io.memoryRequest.bits)
  io.memoryRequest.bits.identity.value := nextValue
  io.memoryRequest.bits.identity.generation := nextGeneration
  io.memoryRequest.bits.command := MemoryCommand.Read
  io.memoryRequest.bits.accessKind := Mux(
    kind === ISideMemoryKind.Translation,
    MemoryAccessKind.InstructionTranslation,
    MemoryAccessKind.InstructionLine)
  io.memoryRequest.bits.address := Mux(
    kind === ISideMemoryKind.Translation,
    translationRequest.vpn << pageOffsetBits,
    lineRequest.linePa + beat * beatBytes.U)
  io.memoryRequest.bits.sizeBytes := beatBytes.U
  io.memoryRequest.bits.instructionSide := true.B

  val requestFire = io.memoryRequest.fire
  val responseExpected = requestOutstanding || requestFire
  val expectedIdentity = Wire(new MemoryTransactionIdentity(p))
  expectedIdentity := Mux(
    requestFire,
    io.memoryRequest.bits.identity,
    outstandingIdentity)
  val responseMatches =
    responseExpected &&
      io.memoryResponse.bits.identity.value === expectedIdentity.value &&
      io.memoryResponse.bits.identity.generation === expectedIdentity.generation

  io.memoryResponse.ready := true.B
  io.staleResponse := io.memoryResponse.valid && !responseMatches

  when(requestFire) {
    requestOutstanding := true.B
    outstandingIdentity := io.memoryRequest.bits.identity
    val valueWrap = nextValue.andR
    nextValue := nextValue + 1.U
    when(valueWrap) {
      nextGeneration := nextGeneration + 1.U
    }
  }

  io.translationRefill.valid := false.B
  io.translationRefill.bits := 0.U.asTypeOf(io.translationRefill.bits)
  when(io.memoryResponse.fire && responseMatches) {
    requestOutstanding := false.B
    when(kind === ISideMemoryKind.Translation) {
      io.translationRefill.valid := true.B
      io.translationRefill.bits.vpn := translationRequest.vpn
      io.translationRefill.bits.ppn :=
        io.memoryResponse.bits.data(ppnWidth - 1, 0)
      io.translationRefill.bits.executable :=
        !io.memoryResponse.bits.denied && !io.memoryResponse.bits.corrupt
      active := false.B
    }.otherwise {
      when(io.memoryResponse.bits.denied || io.memoryResponse.bits.corrupt) {
        lineFaultValid := true.B
        lineFaultRequest := lineRequest.request
        lineFaultCause := io.memoryResponse.bits.errorCause
        active := false.B
      }.otherwise {
        val assembled = Wire(Vec(beatsPerLine, UInt(p.dataWidth.W)))
        assembled := lineBeats
        assembled(beat) := io.memoryResponse.bits.data
        lineBeats(beat) := assembled(beat)
        when(beat === (beatsPerLine - 1).U) {
          lineRefillData := Cat(assembled.reverse)
          lineRefillValid := true.B
          active := false.B
        }.otherwise {
          beat := beat + 1.U
        }
      }
    }
  }

  io.lineRefill.valid := lineRefillValid
  io.lineRefill.bits := 0.U.asTypeOf(io.lineRefill.bits)
  io.lineRefill.bits.peId := lineRequest.request.identity.peId
  io.lineRefill.bits.transactionId := lineRequest.request.transactionId
  io.lineRefill.bits.threadId := lineRequest.request.identity.threadId
  io.lineRefill.bits.fetchPacketUid :=
    lineRequest.request.identity.fetchPacketUid
  io.lineRefill.bits.fetchSeq := lineRequest.request.identity.fetchSeq
  io.lineRefill.bits.checkpointId :=
    lineRequest.request.identity.checkpointId
  io.lineRefill.bits.epoch := lineRequest.request.identity.epoch
  io.lineRefill.bits.lineVa := lineRequest.request.lineVa
  io.lineRefill.bits.linePa := lineRequest.linePa
  io.lineRefill.bits.lineData := lineRefillData

  when(io.lineRefill.fire) {
    lineRefillValid := false.B
  }

  io.lineFault.valid := lineFaultValid
  io.lineFault.bits.request := lineFaultRequest
  io.lineFault.bits.cause := lineFaultCause
  when(io.lineFault.fire) {
    lineFaultValid := false.B
  }

  when(io.memoryResponse.fire && responseMatches) {
    assert(requestOutstanding || requestFire)
  }
}

/** Canonical IFU I-SIDE owner and fixed-64-bit delivery boundary. */
class ISide(val p: CoreParams) extends Module {
  private val ingressWidth = 4
  private val fp = ISide.interfaceParams(p)

  val io = IO(new IFUIO(p))

  val engine = Module(new LinxCoreIfu(
    fp,
    threadCount = p.ooo.stidCount,
    lineBytes = p.ifu.lineBytes,
    pageBytes = p.ifu.pageBytes,
    itlbEntries = p.ifu.itlbEntries,
    l1iSets = p.ifu.l1iSets,
    missEntries = p.ifu.missEntries,
    joinEntries = p.ifu.joinEntries,
    maxGroupsPerTransaction = p.ifu.maxGroupsPerTransaction,
    instructionBufferDepth = ingressWidth,
    externalFetchBuffer = true))
  private val memory = Module(new ISideMemoryAdapter(p, fp))
  val fetchBuffer = Module(new FetchBuffer(p, ingressWidth))

  memory.io.lineRequest <> engine.io.lineRead
  engine.io.lineRefill <> memory.io.lineRefill
  memory.io.translationRequest <> engine.io.ptwRequest
  engine.io.ptwRefill := memory.io.translationRefill
  io.memoryRequest <> memory.io.memoryRequest
  memory.io.memoryResponse <> io.memoryResponse

  engine.io.invalidateItlb := false.B
  engine.io.invalidateL1I := false.B
  engine.io.branchResolve.valid := false.B
  engine.io.branchResolve.bits := 0.U.asTypeOf(engine.io.branchResolve.bits)
  engine.io.d1ThreadId := 0.U

  val recoveryPending = RegInit(false.B)
  val preparedValid = RegInit(false.B)
  val recoveryPlan = RegInit(0.U.asTypeOf(new RecoveryPlan(p)))
  val redirectQueued = RegInit(false.B)
  val redirectPlan = RegInit(0.U.asTypeOf(new RecoveryPlan(p)))

  def sameRecovery(lhs: RecoveryPlan, rhs: RecoveryPlan): Bool =
    lhs.transactionId === rhs.transactionId &&
      lhs.cause === rhs.cause &&
      lhs.trigger.asUInt === rhs.trigger.asUInt &&
      lhs.survivingTailValid === rhs.survivingTailValid &&
      lhs.survivingTail.asUInt === rhs.survivingTail.asUInt &&
      lhs.redirectPc === rhs.redirectPc &&
      lhs.newEpoch === rhs.newEpoch

  io.recovery.prepare.ready := !recoveryPending && !redirectQueued
  io.recovery.prepared.valid := preparedValid
  io.recovery.prepared.bits := recoveryPlan
  val prepareFire = io.recovery.prepare.fire
  val preparedCompletes =
    !preparedValid || io.recovery.prepared.fire
  val applyMatches = sameRecovery(io.recovery.apply.bits, recoveryPlan)
  val abortMatches = sameRecovery(io.recovery.abort.bits, recoveryPlan)
  val applyHit =
    recoveryPending && io.recovery.apply.valid && preparedCompletes &&
      applyMatches && io.recovery.apply.bits.phase === RecoveryPhase.Apply
  val abortHit =
    recoveryPending && io.recovery.abort.valid && preparedCompletes &&
      abortMatches && io.recovery.abort.bits.phase === RecoveryPhase.Abort

  when(prepareFire) {
    recoveryPending := true.B
    preparedValid := true.B
    recoveryPlan := io.recovery.prepare.bits
  }.elsewhen(applyHit) {
    preparedValid := false.B
    redirectQueued := true.B
    redirectPlan := io.recovery.apply.bits
  }.elsewhen(abortHit) {
    recoveryPending := false.B
    preparedValid := false.B
  }.elsewhen(io.recovery.prepared.fire) {
    preparedValid := false.B
  }

  engine.io.backendRedirect.valid := redirectQueued
  engine.io.backendRedirect.bits :=
    0.U.asTypeOf(engine.io.backendRedirect.bits)
  engine.io.backendRedirect.bits.valid := redirectQueued
  engine.io.backendRedirect.bits.peId := redirectPlan.trigger.peId
  engine.io.backendRedirect.bits.threadId := redirectPlan.trigger.stid
  engine.io.backendRedirect.bits.transactionId := redirectPlan.transactionId
  engine.io.backendRedirect.bits.fetchSeq := 0.U
  engine.io.backendRedirect.bits.oldEpoch :=
    (if (p.ooo.stidCount == 1) {
       engine.io.epochs(0)
     } else {
       engine.io.epochs(redirectPlan.trigger.stid)
     })
  engine.io.backendRedirect.bits.restartPc := redirectPlan.redirectPc
  engine.io.backendRedirect.bits.checkpointId := 0.U
  engine.io.backendRedirect.bits.newEpoch := redirectPlan.newEpoch
  engine.io.backendRedirect.bits.reason := IfuInnerFlushReason.OooRecovery
  engine.io.backendRedirect.bits.scope := IfuPruneScope.KillAllThreadState
  engine.io.backendRedirect.bits.fetchPacketUid := 0.U
  engine.io.backendRedirect.bits.ghrAction := GhrRecoveryAction.Reset
  engine.io.backendRedirect.bits.rasAction := RasRecoveryAction.Reset

  when(engine.io.backendRedirect.fire) {
    redirectQueued := false.B
    recoveryPending := false.B
  }

  val bootPending = RegInit(true.B)
  engine.io.start.valid := bootPending && !recoveryPending && !redirectQueued
  engine.io.start.bits := 0.U.asTypeOf(engine.io.start.bits)
  engine.io.start.bits.peId := 0.U
  engine.io.start.bits.threadId := 0.U
  engine.io.start.bits.pc := p.ifu.resetVector.U(p.pcWidth.W)
  when(engine.io.start.valid) {
    bootPending := false.B
  }

  val recoveryFence =
    recoveryPending || redirectQueued || prepareFire || applyHit || abortHit

  val ingress = Wire(Decoupled(new FetchBufferIngress(p, ingressWidth)))
  ingress.bits := 0.U.asTypeOf(ingress.bits)

  val lineFaultEpoch =
    (if (p.ooo.stidCount == 1) {
       engine.io.epochs(0)
     } else {
       engine.io.epochs(memory.io.lineFault.bits.request.identity.threadId)
     })
  val lineFaultStale =
    memory.io.lineFault.valid &&
      memory.io.lineFault.bits.request.identity.epoch =/= lineFaultEpoch
  val memoryFaultValid = memory.io.lineFault.valid && !lineFaultStale
  val faultValid = memoryFaultValid || engine.io.fetchFault.valid
  val d1Valid = engine.io.d1.valid && engine.io.d1.bits.validMask.orR
  val compactedD1 = Wire(Vec(ingressWidth, new InstructionBufferEntry(fp)))
  val d1Count = PopCount(engine.io.d1.bits.validMask)
  for (dst <- 0 until ingressWidth) {
    val candidates = (0 until ingressWidth).map { lane =>
      val rank =
        if (lane == 0) 0.U else PopCount(engine.io.d1.bits.validMask(lane - 1, 0))
      (engine.io.d1.bits.validMask(lane) && rank === dst.U) ->
        engine.io.d1.bits.entries(lane)
    }
    compactedD1(dst) := MuxCase(
      0.U.asTypeOf(new InstructionBufferEntry(fp)),
      candidates)
  }

  ingress.valid := !recoveryFence && (faultValid || d1Valid)
  ingress.bits.count := Mux(faultValid, 1.U, d1Count)
  memory.io.lineFault.ready :=
    lineFaultStale || (!recoveryFence && fetchBuffer.io.enq.ready)
  engine.io.fetchFault.ready :=
    !memoryFaultValid && !recoveryFence && fetchBuffer.io.enq.ready
  engine.io.d1.ready :=
    !recoveryFence && !faultValid && fetchBuffer.io.enq.ready

  def mapIdentity(
      out: InstructionIdentity,
      in: IfuFetchIdentity,
      instructionId: UInt): Unit = {
    out.peId := in.peId
    out.stid := in.threadId
    out.instructionId := instructionId
    out.epoch := in.epoch
  }

  def mapPrediction(
      out: PredictionMeta,
      in: BranchPredictionRecord,
      transactionId: UInt): Unit = {
    out.valid := in.valid
    out.predictionTag := in.predictionTag
    out.transactionId := transactionId
    out.checkpointId := in.checkpointId
    out.requestPc := in.requestPc
    out.taken := in.taken
    out.target := in.target
    out.fallthroughPc := in.fallthroughPc
    out.kind := PredictionKind.None
    switch(in.kind) {
      is(BoundaryKind.Fall) { out.kind := PredictionKind.Fall }
      is(BoundaryKind.Cond) { out.kind := PredictionKind.Conditional }
      is(BoundaryKind.Call) { out.kind := PredictionKind.Call }
      is(BoundaryKind.Ret) { out.kind := PredictionKind.Return }
      is(BoundaryKind.Direct) { out.kind := PredictionKind.Direct }
      is(BoundaryKind.Ind) { out.kind := PredictionKind.Indirect }
      is(BoundaryKind.ICall) { out.kind := PredictionKind.Indirect }
    }
    out.provider := in.provider.asUInt
    out.confidence := in.confidence
    out.epoch := in.epoch
  }

  for (lane <- 0 until ingressWidth) {
    val source = compactedD1(lane)
    val out = ingress.bits.entries(lane)
    mapIdentity(out.identity, source.identity, source.instructionUid)
    out.pc := source.pc
    out.instruction := source.insn
    out.lengthBytes := source.lenBytes
    mapPrediction(out.prediction, source.prediction, source.transactionId)
    out.fetchFault := false.B
    out.fetchFaultCause := 0.U
  }
  when(memoryFaultValid) {
    val out = ingress.bits.entries(0)
    mapIdentity(
      out.identity,
      memory.io.lineFault.bits.request.identity,
      memory.io.lineFault.bits.request.transactionId)
    out.pc := memory.io.lineFault.bits.request.pc
    out.instruction := 0.U
    out.lengthBytes := 0.U
    mapPrediction(
      out.prediction,
      memory.io.lineFault.bits.request.prediction,
      memory.io.lineFault.bits.request.transactionId)
    out.fetchFault := true.B
    out.fetchFaultCause := memory.io.lineFault.bits.cause
  }.elsewhen(engine.io.fetchFault.valid) {
    val out = ingress.bits.entries(0)
    mapIdentity(
      out.identity,
      engine.io.fetchFault.bits.request.identity,
      engine.io.fetchFault.bits.request.transactionId)
    out.pc := engine.io.fetchFault.bits.request.pc
    out.instruction := 0.U
    out.lengthBytes := 0.U
    mapPrediction(
      out.prediction,
      engine.io.fetchFault.bits.request.prediction,
      engine.io.fetchFault.bits.request.transactionId)
    out.fetchFault := true.B
    out.fetchFaultCause := 1.U
  }

  fetchBuffer.io.enq <> ingress
  fetchBuffer.io.prune.valid := applyHit
  fetchBuffer.io.prune.bits := io.recovery.apply.bits

  val traceValid = RegInit(false.B)
  val tracePacket = RegInit(0.U.asTypeOf(new TracePacket(p)))
  val traceCapacity = !traceValid || io.trace.ready
  io.toCtu.valid :=
    fetchBuffer.io.deq.valid && !recoveryFence
  io.toCtu.bits := fetchBuffer.io.deq.bits
  fetchBuffer.io.deq.ready :=
    io.toCtu.ready && !recoveryFence

  io.trace.valid := traceValid
  io.trace.bits := tracePacket
  val traceFires = io.trace.fire
  val packetFires = io.toCtu.fire
  val traceCapture = packetFires && traceCapacity
  when(traceFires && !traceCapture) {
    traceValid := false.B
  }
  when(traceCapture) {
    traceValid := true.B
    tracePacket := 0.U.asTypeOf(tracePacket)
    tracePacket.count := io.toCtu.bits.count
    for (lane <- 0 until p.widths.fetchWidth) {
      when(lane.U < io.toCtu.bits.count) {
        tracePacket.entries(lane).source := TraceSource.Ifu
        tracePacket.entries(lane).kind := TraceKind.Pipeline
        tracePacket.entries(lane).instructionValid := true.B
        tracePacket.entries(lane).instruction :=
          io.toCtu.bits.entries(lane).identity
        tracePacket.entries(lane).pc := io.toCtu.bits.entries(lane).pc
        tracePacket.entries(lane).payload :=
          io.toCtu.bits.entries(lane).lengthBytes
      }
    }
  }

  when(prepareFire) {
    assert(io.recovery.prepare.bits.phase === RecoveryPhase.Prepare)
  }
  when(io.recovery.apply.valid && recoveryPending) {
    assert(applyMatches)
    assert(preparedCompletes)
  }
  when(io.recovery.abort.valid && recoveryPending) {
    assert(abortMatches)
    assert(preparedCompletes)
  }
  when(ingress.fire) {
    assert(ingress.bits.count =/= 0.U)
    assert(ingress.bits.count <= ingressWidth.U)
  }
}
