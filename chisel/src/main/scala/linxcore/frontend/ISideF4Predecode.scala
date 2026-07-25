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
  val acceptedStop = Output(Bool())
  val flush = Input(new IfuInnerFlush(p))
}

object ISideBoundaryTargetDecode {
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
  val activeBoundaryIdentity =
    RegInit(VecInit(Seq.fill(threadCount)(0.U.asTypeOf(new IfuFetchIdentity(p)))))
  val activeBoundaryTransaction =
    RegInit(VecInit(Seq.fill(threadCount)(0.U(p.uopUidWidth.W))))

  val inputIdentity = io.in.bits.entries(0).identity
  val inputTransaction = inputIdentity.fetchPacketUid
  val inputThreadSupported = inputIdentity.threadId < threadCount.U
  val inputThread = threadIndex(inputIdentity.threadId)
  val killed =
    IfuFlushContract.kills(inputIdentity, inputTransaction, io.flush)

  val laneMeta = Wire(Vec(p.fetchWidth, new FrontendOpcodeMeta(p)))
  val startLane = Wire(Vec(p.fetchWidth, Bool()))
  val stopLane = Wire(Vec(p.fetchWidth, Bool()))
  val allowLane = Wire(Vec(p.fetchWidth, Bool()))
  val outputValid = Wire(Vec(p.fetchWidth, Bool()))
  allowLane(0) := true.B

  for (lane <- 0 until p.fetchWidth) {
    val candidate = io.in.bits.entries(lane)
    laneMeta(lane) :=
      FrontendOpcodeDecodeTable.decode(p, candidate.insn, candidate.lenBytes)
    val laneInputValid = io.in.bits.validMask(lane)
    val laneAllowed = laneInputValid && allowLane(lane)
    startLane(lane) := laneAllowed && laneMeta(lane).valid && laneMeta(lane).isBlockBoundary
    stopLane(lane) := laneAllowed && laneMeta(lane).valid && laneMeta(lane).isBlockStop
    outputValid(lane) := laneAllowed

    io.out.bits.entries(lane).pc := candidate.pc
    io.out.bits.entries(lane).insn := candidate.insn
    io.out.bits.entries(lane).lenBytes := candidate.lenBytes
    io.out.bits.entries(lane).isBlockStart := startLane(lane)
    io.out.bits.entries(lane).isBlockStop := stopLane(lane)
    io.out.bits.entries(lane).identity := candidate.identity
    io.out.bits.entries(lane).prediction := candidate.prediction

    if (lane + 1 < p.fetchWidth) {
      allowLane(lane + 1) := allowLane(lane) && laneInputValid && !stopLane(lane)
    }
  }

  val startPresent = startLane.asUInt.orR
  val stopPresent = stopLane.asUInt.orR
  val startIndex = PriorityEncoder(startLane.asUInt)
  val stopIndex = PriorityEncoder(stopLane.asUInt)
  val startMeta = laneMeta(startIndex)
  val startCandidate = io.in.bits.entries(startIndex)
  val stopCandidate = io.in.bits.entries(stopIndex)
  val lastLane = PriorityEncoder(Reverse(outputValid.asUInt))
  val lastCandidate = io.in.bits.entries((p.fetchWidth - 1).U - lastLane)
  val decodedStartTarget =
    startCandidate.pc +
      Mux(
        ISideBoundaryTargetDecode.hasTarget(startMeta, p),
        ISideBoundaryTargetDecode.offset(startCandidate.insn, startMeta, p),
        0.U)
  val effectiveBoundaryValid =
    startPresent ||
      (inputThreadSupported && activeBoundaryValid(inputThread))
  val effectiveBoundaryKind =
    Mux(startPresent, startMeta.boundaryKind, activeBoundaryKind(inputThread))
  val effectiveBoundaryTarget =
    Mux(startPresent, decodedStartTarget, activeBoundaryTarget(inputThread))
  val transactionComplete = stopPresent || io.in.bits.lineComplete
  val terminalCanPublish = !transactionComplete || io.boundary.ready

  io.out.bits.validMask := outputValid.asUInt
  io.out.bits.transactionComplete := transactionComplete
  io.out.valid := io.in.valid && !killed && terminalCanPublish

  io.boundary.valid :=
    io.in.valid &&
      !killed &&
      transactionComplete &&
      io.out.ready
  io.boundary.bits := 0.U.asTypeOf(io.boundary.bits)
  io.boundary.bits.valid := stopPresent && effectiveBoundaryValid
  io.boundary.bits.peId := inputIdentity.peId
  io.boundary.bits.transactionId := inputTransaction
  io.boundary.bits.threadId := inputIdentity.threadId
  io.boundary.bits.fetchPacketUid := inputIdentity.fetchPacketUid
  io.boundary.bits.fetchSeq := inputIdentity.fetchSeq
  io.boundary.bits.epoch := inputIdentity.epoch
  io.boundary.bits.checkpointId := inputIdentity.checkpointId
  io.boundary.bits.branchPc := Mux(stopPresent, stopCandidate.pc, 0.U)
  io.boundary.bits.target := effectiveBoundaryTarget
  io.boundary.bits.fallthroughPc :=
    Mux(
      stopPresent,
      stopCandidate.pc + stopCandidate.lenBytes,
      lastCandidate.pc + lastCandidate.lenBytes)
  io.boundary.bits.kind := effectiveBoundaryKind
  io.boundary.bits.staticTaken :=
    Mux(
      effectiveBoundaryKind === BoundaryKind.Cond,
      effectiveBoundaryTarget < stopCandidate.pc,
      effectiveBoundaryKind =/= BoundaryKind.Fall)

  io.in.ready :=
    killed ||
      (io.out.ready && terminalCanPublish)
  io.acceptedStop := io.in.fire && !killed && stopPresent

  for (thread <- 0 until threadCount) {
    when(
      activeBoundaryValid(thread) &&
        IfuFlushContract.kills(
          activeBoundaryIdentity(thread),
          activeBoundaryTransaction(thread),
          io.flush)) {
      activeBoundaryValid(thread) := false.B
    }
  }

  when(io.in.fire && !killed && inputThreadSupported) {
    when(startPresent) {
      activeBoundaryValid(inputThread) := true.B
      activeBoundaryKind(inputThread) := startMeta.boundaryKind
      activeBoundaryTarget(inputThread) := decodedStartTarget
      activeBoundaryIdentity(inputThread) := inputIdentity
      activeBoundaryTransaction(inputThread) := inputTransaction
    }
    when(stopPresent) {
      activeBoundaryValid(inputThread) := false.B
    }
  }

  when(io.boundary.fire) {
    assert(io.out.fire, "terminal I-F4 group and boundary completion must be accepted atomically")
  }
}
