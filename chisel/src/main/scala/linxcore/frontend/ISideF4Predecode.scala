package linxcore.frontend

import chisel3._
import chisel3.util.{Cat, Decoupled, Fill, PriorityEncoder, Reverse, log2Ceil}
import linxcore.common.{BoundaryKind, InterfaceParams}

class ISideF4PredecodeIO(
    val p: InterfaceParams = InterfaceParams(),
    val threadCount: Int = 1)
    extends Bundle {
  val in = Flipped(Decoupled(new ISideAssembledGroup(p)))
  val out = Decoupled(new InstructionBufferEnqueueGroup(p))
  val boundary = Decoupled(new BSideBoundaryMetadata(p))
  val acceptedStart = Output(Bool())
  val acceptedStop = Output(Bool())
  val terminateResident = Output(Bool())
  val flush = Input(new IfuInnerFlush(p))
}

object ISideBoundaryTargetDecode {
  /** BSTART encodings that describe control-flow continuation and therefore
    * open B-SIDE predictor state.  Execution-domain BSTART encodings still
    * delimit blocks, but do not themselves provide a prediction target.
    */
  def isControlFlowStart(meta: FrontendOpcodeMeta, p: InterfaceParams): Bool =
    Seq(
      FrontendOpcodeDecodeTable.OP_BSTART_FP_COND,
      FrontendOpcodeDecodeTable.OP_BSTART_FP_DIRECT,
      FrontendOpcodeDecodeTable.OP_BSTART_FP_IND,
      FrontendOpcodeDecodeTable.OP_BSTART_FP_RET,
      FrontendOpcodeDecodeTable.OP_BSTART_ICALL,
      FrontendOpcodeDecodeTable.OP_BSTART_IND,
      FrontendOpcodeDecodeTable.OP_BSTART_RET,
      FrontendOpcodeDecodeTable.OP_BSTART_SPLIT_COND,
      FrontendOpcodeDecodeTable.OP_BSTART_SPLIT_DIRECT,
      FrontendOpcodeDecodeTable.OP_BSTART_STD_CALL,
      FrontendOpcodeDecodeTable.OP_BSTART_STD_COND,
      FrontendOpcodeDecodeTable.OP_BSTART_STD_DIRECT,
      FrontendOpcodeDecodeTable.OP_C_BSTART_COND,
      FrontendOpcodeDecodeTable.OP_C_BSTART_DIRECT,
      FrontendOpcodeDecodeTable.OP_HL_BSTART_FP_CALL,
      FrontendOpcodeDecodeTable.OP_HL_BSTART_FP_COND,
      FrontendOpcodeDecodeTable.OP_HL_BSTART_FP_DIRECT
    ).map(opcode => meta.opcode === opcode.U(p.opcodeWidth.W)).reduce(_ || _)

  def signExtend(value: UInt, width: Int, outWidth: Int): UInt = {
    require(outWidth >= width)
    Cat(Fill(outWidth - width, value(width - 1)), value)
  }

  def hasTarget(meta: FrontendOpcodeMeta, p: InterfaceParams): Bool =
    Seq(
      FrontendOpcodeDecodeTable.OP_C_BSTART_COND,
      FrontendOpcodeDecodeTable.OP_C_BSTART_DIRECT,
      FrontendOpcodeDecodeTable.OP_BSTART_SPLIT_COND,
      FrontendOpcodeDecodeTable.OP_BSTART_SPLIT_DIRECT,
      FrontendOpcodeDecodeTable.OP_BSTART_STD_CALL,
      FrontendOpcodeDecodeTable.OP_BSTART_STD_COND,
      FrontendOpcodeDecodeTable.OP_BSTART_STD_DIRECT
    ).map(opcode => meta.opcode === opcode.U(p.opcodeWidth.W)).reduce(_ || _)

  def offset(insn: UInt, meta: FrontendOpcodeMeta, p: InterfaceParams): UInt = {
    val cOffset = signExtend(Cat(insn(15, 4), 0.U(1.W)), 13, p.pcWidth)
    val splitOffset = signExtend(Cat(insn(31, 7), 0.U(1.W)), 26, p.pcWidth)
    val stdOffset = signExtend(Cat(insn(31, 15), 0.U(1.W)), 18, p.pcWidth)
    val highOffset =
      signExtend(Cat(insn(15, 4), insn(47, 31), 0.U(1.W)), 30, p.pcWidth)

    Mux(
      meta.immKind === FrontendOpcodeDecodeTable.ImmSIMM12.U,
      cOffset,
      Mux(
        meta.immKind === FrontendOpcodeDecodeTable.ImmSIMM25.U,
        splitOffset,
        Mux(
          meta.immKind === FrontendOpcodeDecodeTable.ImmSIMM17.U,
          stdOffset,
          highOffset)))
  }
}

object ISideReturnBoundaryDecode {
  def isCompressedSetret(insn: UInt, meta: FrontendOpcodeMeta, p: InterfaceParams): Bool =
    meta.valid &&
      meta.lenBytes === 2.U &&
      (insn(15, 0) & "hf83f".U) === "h5016".U

  def isSetret(insn: UInt, meta: FrontendOpcodeMeta, p: InterfaceParams): Bool =
    isCompressedSetret(insn, meta, p) ||
      meta.opcode === FrontendOpcodeDecodeTable.OP_SETRET.U(p.opcodeWidth.W) ||
      meta.opcode === FrontendOpcodeDecodeTable.OP_HL_SETRET.U(p.opcodeWidth.W)

  /** Return-address semantics shared with D1/execute: SETRET writes its own
    * instruction PC plus a halfword-scaled immediate.  SETRET is metadata for
    * a resident CALL/ICALL block; it is not itself a predecode boundary.
    */
  def target(pc: UInt, insn: UInt, meta: FrontendOpcodeMeta, p: InterfaceParams): UInt = {
    val compressedOffset = Cat(insn(10, 6), 0.U(1.W)).pad(p.pcWidth)
    val standardOffset = Cat(insn(31, 12), 0.U(1.W)).pad(p.pcWidth)
    val highRaw = Cat(insn(15, 4), insn(47, 28))
    val highOffset =
      (ISideBoundaryTargetDecode.signExtend(highRaw, 32, p.pcWidth) << 1)(p.pcWidth - 1, 0)

    pc + Mux(
      isCompressedSetret(insn, meta, p),
      compressedOffset,
      Mux(
        meta.opcode === FrontendOpcodeDecodeTable.OP_SETRET.U(p.opcodeWidth.W),
        standardOffset,
        highOffset))
  }
}

class ISideF4Predecode(
    val p: InterfaceParams = InterfaceParams(),
    val threadCount: Int = 1)
    extends Module {
  require(p.fetchWidth == 4)
  require(p.insnWidth == 64)
  require(threadCount > 0 && (threadCount & (threadCount - 1)) == 0)
  require(threadCount <= (1 << p.threadIdWidth))

  private val threadIndexWidth = math.max(1, log2Ceil(threadCount))

  val io = IO(new ISideF4PredecodeIO(p, threadCount))

  def threadIndex(threadId: UInt): UInt =
    if (threadCount == 1) 0.U(threadIndexWidth.W) else threadId(threadIndexWidth - 1, 0)

  val activeBoundaryValid = RegInit(VecInit(Seq.fill(threadCount)(false.B)))
  val activeBoundaryKind =
    RegInit(VecInit(Seq.fill(threadCount)(BoundaryKind.Fall)))
  val activeBoundaryTarget =
    RegInit(VecInit(Seq.fill(threadCount)(0.U(p.pcWidth.W))))
  val activeBoundaryPc =
    RegInit(VecInit(Seq.fill(threadCount)(0.U(p.pcWidth.W))))
  val activeBoundaryIdentity =
    RegInit(VecInit(Seq.fill(threadCount)(0.U.asTypeOf(new IfuFetchIdentity(p)))))
  val activeBoundaryTransaction =
    RegInit(VecInit(Seq.fill(threadCount)(0.U(p.uopUidWidth.W))))
  val activeReturnBoundaryValid = RegInit(VecInit(Seq.fill(threadCount)(false.B)))
  val activeReturnBoundaryPc =
    RegInit(VecInit(Seq.fill(threadCount)(0.U(p.pcWidth.W))))

  val inputIdentity = io.in.bits.entries(0).identity
  val inputTransaction = io.in.bits.entries(0).transactionId
  val inputThreadSupported = inputIdentity.threadId < threadCount.U
  val inputThread = threadIndex(inputIdentity.threadId)
  val killed =
    IfuFlushContract.kills(inputIdentity, inputTransaction, io.flush)

  val laneMeta = Wire(Vec(p.fetchWidth, new FrontendOpcodeMeta(p)))
  val markerStartLane = Wire(Vec(p.fetchWidth, Bool()))
  val predictionStartLane = Wire(Vec(p.fetchWidth, Bool()))
  val stopLane = Wire(Vec(p.fetchWidth, Bool()))
  val setretLane = Wire(Vec(p.fetchWidth, Bool()))
  val allowLane = Wire(Vec(p.fetchWidth, Bool()))
  val outputValid = Wire(Vec(p.fetchWidth, Bool()))
  allowLane(0) := true.B

  for (lane <- 0 until p.fetchWidth) {
    val candidate = io.in.bits.entries(lane)
    laneMeta(lane) :=
      FrontendOpcodeDecodeTable.decode(p, candidate.insn, candidate.lenBytes)
    val laneInputValid = io.in.bits.validMask(lane)
    val laneAllowed = laneInputValid && allowLane(lane)
    markerStartLane(lane) :=
      laneAllowed && laneMeta(lane).valid && laneMeta(lane).isBlockBoundary
    predictionStartLane(lane) :=
      markerStartLane(lane) && ISideBoundaryTargetDecode.isControlFlowStart(laneMeta(lane), p)
    stopLane(lane) := laneAllowed && laneMeta(lane).valid && laneMeta(lane).isBlockStop
    setretLane(lane) :=
      laneAllowed && ISideReturnBoundaryDecode.isSetret(candidate.insn, laneMeta(lane), p)
    outputValid(lane) := laneAllowed

    io.out.bits.entries(lane).pc := candidate.pc
    io.out.bits.entries(lane).instructionUid := candidate.instructionUid
    io.out.bits.entries(lane).transactionId := candidate.transactionId
    io.out.bits.entries(lane).insn := candidate.insn
    io.out.bits.entries(lane).lenBytes := candidate.lenBytes
    io.out.bits.entries(lane).isBlockStart := markerStartLane(lane)
    io.out.bits.entries(lane).isBlockStop := stopLane(lane)
    io.out.bits.entries(lane).identity := candidate.identity
    io.out.bits.entries(lane).prediction := candidate.prediction

    if (lane + 1 < p.fetchWidth) {
      allowLane(lane + 1) :=
        allowLane(lane) && laneInputValid && !predictionStartLane(lane)
    }
  }

  val markerStartPresent = markerStartLane.asUInt.orR
  val predictionStartPresent = predictionStartLane.asUInt.orR
  val stopPresent = stopLane.asUInt.orR
  val markerStartIndex = PriorityEncoder(markerStartLane.asUInt)
  val predictionStartIndex = PriorityEncoder(predictionStartLane.asUInt)
  val stopIndex = PriorityEncoder(stopLane.asUInt)
  val predictionStartMeta = laneMeta(predictionStartIndex)
  val markerStartCandidate = io.in.bits.entries(markerStartIndex)
  val predictionStartCandidate = io.in.bits.entries(predictionStartIndex)
  val stopCandidate = io.in.bits.entries(stopIndex)
  val inheritedPrediction = io.in.bits.entries(0).prediction
  val inheritedBoundaryValid =
    inheritedPrediction.valid && inheritedPrediction.kind =/= BoundaryKind.Fall
  val priorBoundaryValid =
    inheritedBoundaryValid ||
      (inputThreadSupported && activeBoundaryValid(inputThread))
  val priorBoundaryKind =
    Mux(inheritedBoundaryValid, inheritedPrediction.kind, activeBoundaryKind(inputThread))
  val priorBoundaryTarget =
    Mux(inheritedBoundaryValid, inheritedPrediction.target, activeBoundaryTarget(inputThread))
  val priorBoundaryPc =
    Mux(inheritedBoundaryValid, inheritedPrediction.branchPc, activeBoundaryPc(inputThread))
  val inheritedReturnBoundaryValid =
    inheritedBoundaryValid &&
      (inheritedPrediction.kind === BoundaryKind.Call ||
        inheritedPrediction.kind === BoundaryKind.ICall)
  val priorReturnBoundaryValid =
    (inputThreadSupported && activeBoundaryValid(inputThread) &&
      activeReturnBoundaryValid(inputThread)) || inheritedReturnBoundaryValid
  val priorReturnBoundaryPc =
    Mux(
      inputThreadSupported && activeBoundaryValid(inputThread) &&
        activeReturnBoundaryValid(inputThread),
      activeReturnBoundaryPc(inputThread),
      inheritedPrediction.fallthroughPc)
  // A following BSTART closes the current block.  Do not consume that next
  // marker into the current transaction: publish the prior block's terminal
  // metadata, then let the selected continuation refetch the marker when the
  // prior prediction is not taken.
  val startClosesPrior =
    markerStartPresent && inputThreadSupported && priorBoundaryValid
  // SETRET defines the sequential return cutpoint of a CALL/ICALL block.  It
  // is not emitted as a block marker, but once observed it lets I-F4 stop the
  // resident line before FRET/FENTRY bytes at the return address can escape
  // to D1 while B-F4 is still selecting the call target.
  val knownReturnBoundaryValid =
    inputThreadSupported &&
      activeBoundaryValid(inputThread) &&
      activeReturnBoundaryValid(inputThread)
  val returnCutLane = Wire(Vec(p.fetchWidth, Bool()))
  for (lane <- 0 until p.fetchWidth) {
    returnCutLane(lane) :=
      outputValid(lane) &&
        knownReturnBoundaryValid &&
        io.in.bits.entries(lane).pc >= activeReturnBoundaryPc(inputThread)
  }
  val returnClosesPrior = returnCutLane.asUInt.orR
  val returnCutIndex = PriorityEncoder(returnCutLane.asUInt)
  val closesPrior = startClosesPrior || returnClosesPrior
  // A standalone execution-domain BSTOP is still an architectural marker,
  // but it does not complete a B-SIDE prediction transaction.  Preserve the
  // following same-cacheline lanes in that case.  When a control boundary is
  // active, BSTOP remains the terminal cut point and BF4 owns the continuation.
  val stopClosesControl = stopPresent && priorBoundaryValid
  val emittedValid = Wire(Vec(p.fetchWidth, Bool()))
  for (lane <- 0 until p.fetchWidth) {
    emittedValid(lane) :=
      outputValid(lane) &&
        (!startClosesPrior || lane.U < markerStartIndex) &&
        (!returnClosesPrior || lane.U < returnCutIndex) &&
        (!stopClosesControl || lane.U <= stopIndex)
  }
  val emittedSetret = setretLane.asUInt & emittedValid.asUInt
  val returnBoundaryPresent = emittedSetret.orR
  val returnBoundaryIndex = PriorityEncoder(emittedSetret)
  val returnBoundaryCandidate = io.in.bits.entries(returnBoundaryIndex)
  val decodedReturnBoundaryPc =
    ISideReturnBoundaryDecode.target(
      returnBoundaryCandidate.pc,
      returnBoundaryCandidate.insn,
      laneMeta(returnBoundaryIndex),
      p)
  val returnBoundaryBelongsToCall =
    returnBoundaryPresent &&
      priorBoundaryValid &&
      (priorBoundaryKind === BoundaryKind.Call || priorBoundaryKind === BoundaryKind.ICall)
  val effectiveReturnBoundaryValid = priorReturnBoundaryValid || returnBoundaryBelongsToCall
  val effectiveReturnBoundaryPc =
    Mux(returnBoundaryPresent, decodedReturnBoundaryPc, priorReturnBoundaryPc)
  val lastLane = PriorityEncoder(Reverse(outputValid.asUInt))
  val lastCandidate = io.in.bits.entries((p.fetchWidth - 1).U - lastLane)
  val decodedStartTarget =
    predictionStartCandidate.pc +
      Mux(
        ISideBoundaryTargetDecode.hasTarget(predictionStartMeta, p),
        ISideBoundaryTargetDecode.offset(predictionStartCandidate.insn, predictionStartMeta, p),
        0.U)
  val effectiveBoundaryValid =
    predictionStartPresent ||
      (inputThreadSupported && priorBoundaryValid)
  val effectiveBoundaryKind =
    Mux(predictionStartPresent, predictionStartMeta.boundaryKind, priorBoundaryKind)
  val effectiveBoundaryTarget =
    Mux(predictionStartPresent, decodedStartTarget, priorBoundaryTarget)
  // A non-control execution-domain BSTART may be followed by a control BSTART
  // in the same scalar block.  It therefore opens no predictor state by
  // itself.  When an older control boundary is active, however, every BSTART
  // is the architectural start of the next block and closes that older state.
  val transactionComplete =
    closesPrior || predictionStartPresent || stopClosesControl || io.in.bits.lineComplete
  // BSTART contains enough information to steer an unconditional direct/call
  // block before its (potentially cachelines-away) BSTOP is observed.  Waiting
  // until transaction completion let intermediate cachelines retire with a
  // sequential prediction, so a cold direct block could execute its body
  // before the late marker redirect arrived.
  val boundaryEvent = closesPrior || predictionStartPresent || transactionComplete
  val boundaryCanPublish = !boundaryEvent || io.boundary.ready

  io.out.bits.validMask := emittedValid.asUInt
  io.out.bits.transactionComplete := transactionComplete
  io.out.valid := io.in.valid && !killed && boundaryCanPublish

  io.boundary.valid :=
    io.in.valid &&
      !killed &&
      boundaryEvent &&
      io.out.ready
  io.boundary.bits := 0.U.asTypeOf(io.boundary.bits)
  // Repeat the active control boundary on every completed body transaction.
  // A block may span cachelines, while B-SIDE/join ownership is per fetch
  // transaction; without this carry, intermediate body lanes (including
  // SETC) would receive a synthetic Fall sidecar instead of their block's
  // final BF4 prediction.
  io.boundary.bits.valid := effectiveBoundaryValid
  io.boundary.bits.peId := inputIdentity.peId
  io.boundary.bits.transactionId := inputTransaction
  io.boundary.bits.threadId := inputIdentity.threadId
  io.boundary.bits.fetchPacketUid := inputIdentity.fetchPacketUid
  io.boundary.bits.fetchSeq := inputIdentity.fetchSeq
  io.boundary.bits.epoch := inputIdentity.epoch
  io.boundary.bits.checkpointId := inputIdentity.checkpointId
  io.boundary.bits.branchPc :=
    Mux(
      closesPrior,
      priorBoundaryPc,
      Mux(
        stopPresent,
        stopCandidate.pc,
        Mux(
          predictionStartPresent,
          predictionStartCandidate.pc,
          Mux(effectiveBoundaryValid, priorBoundaryPc, 0.U))))
  io.boundary.bits.target :=
    Mux(closesPrior, priorBoundaryTarget, effectiveBoundaryTarget)
  io.boundary.bits.fallthroughPc :=
    Mux(
      closesPrior,
      Mux(
        returnClosesPrior,
        activeReturnBoundaryPc(inputThread),
        Mux(effectiveReturnBoundaryValid, effectiveReturnBoundaryPc, markerStartCandidate.pc)),
      Mux(
        stopPresent,
        Mux(
          effectiveReturnBoundaryValid,
          effectiveReturnBoundaryPc,
          stopCandidate.pc + stopCandidate.lenBytes),
        Mux(
          predictionStartPresent,
          predictionStartCandidate.pc + predictionStartCandidate.lenBytes,
          Mux(
            effectiveReturnBoundaryValid,
            effectiveReturnBoundaryPc,
            lastCandidate.pc + lastCandidate.lenBytes))))
  io.boundary.bits.kind :=
    Mux(closesPrior, priorBoundaryKind, effectiveBoundaryKind)
  io.boundary.bits.staticTaken :=
    Mux(
      io.boundary.bits.kind === BoundaryKind.Cond,
      io.boundary.bits.target < io.boundary.bits.branchPc,
      io.boundary.bits.kind =/= BoundaryKind.Fall)
  io.boundary.bits.continuationReady := stopPresent || closesPrior

  io.in.ready :=
    killed ||
      (io.out.ready && boundaryCanPublish)
  io.acceptedStart := io.in.fire && !killed && predictionStartPresent && !closesPrior
  io.acceptedStop := io.in.fire && !killed && stopPresent
  // A BSTART that closes a prior block is deliberately not installed as the
  // new active boundary yet, but it still ends this F3 resident.  The prior
  // block's BF4 final steer refetches that marker as a fresh transaction.
  // Otherwise F3 would advance past the marker and a later boundary from the
  // same line transaction could overwrite the prior terminal metadata.
  io.terminateResident :=
    io.in.fire && !killed && (closesPrior || predictionStartPresent || stopClosesControl)

  for (thread <- 0 until threadCount) {
    when(
      activeBoundaryValid(thread) &&
        IfuFlushContract.kills(
          activeBoundaryIdentity(thread),
          activeBoundaryTransaction(thread),
          io.flush)) {
      activeBoundaryValid(thread) := false.B
      activeReturnBoundaryValid(thread) := false.B
    }
  }

  when(io.in.fire && !killed && inputThreadSupported) {
    when(closesPrior && !predictionStartPresent) {
      activeBoundaryValid(inputThread) := false.B
      activeReturnBoundaryValid(inputThread) := false.B
    }
    when(predictionStartPresent) {
      when(closesPrior) {
        activeBoundaryValid(inputThread) := false.B
        activeReturnBoundaryValid(inputThread) := false.B
      }.otherwise {
        activeBoundaryValid(inputThread) := true.B
        activeBoundaryKind(inputThread) := predictionStartMeta.boundaryKind
        activeBoundaryTarget(inputThread) := decodedStartTarget
        activeBoundaryPc(inputThread) := predictionStartCandidate.pc
        activeBoundaryIdentity(inputThread) := inputIdentity
        activeBoundaryTransaction(inputThread) := inputTransaction
        activeReturnBoundaryValid(inputThread) := false.B
      }
    }
    when(
      returnBoundaryBelongsToCall &&
        !closesPrior) {
      // A progressive predictor correction may have retired the original
      // local BSTART register before the refetched body reaches SETRET.  The
      // inherited final sidecar is an exact copy of that boundary, so rebuild
      // the resident control context together with the return cutpoint.
      activeBoundaryValid(inputThread) := true.B
      activeBoundaryKind(inputThread) := priorBoundaryKind
      activeBoundaryTarget(inputThread) := priorBoundaryTarget
      activeBoundaryPc(inputThread) := priorBoundaryPc
      activeBoundaryIdentity(inputThread) := inputIdentity
      activeBoundaryTransaction(inputThread) := inputTransaction
      activeReturnBoundaryValid(inputThread) := true.B
      activeReturnBoundaryPc(inputThread) := decodedReturnBoundaryPc
    }
    when(stopPresent) {
      activeBoundaryValid(inputThread) := false.B
      activeReturnBoundaryValid(inputThread) := false.B
    }
  }

  when(io.boundary.fire) {
    assert(io.out.fire, "terminal I-F4 group and boundary completion must be accepted atomically")
  }
}
