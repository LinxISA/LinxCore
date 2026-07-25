package linxcore.frontend

import chisel3._
import chisel3.util.{Cat, Decoupled, PopCount, Valid, log2Ceil}
import linxcore.common.InterfaceParams

class ISideF3LineAssemblerIO(
    val p: InterfaceParams = InterfaceParams(),
    val lineBytes: Int = 64)
    extends Bundle {
  val in = Flipped(Decoupled(new ISideF2Result(p, lineBytes)))
  val nextLineRequest = Decoupled(new ISideFetchRequest(p, lineBytes))
  val nextLineResponse = Flipped(Decoupled(new ISideLineResponse(p, lineBytes)))
  val out = Decoupled(new ISideAssembledGroup(p))
  val prefixCarry = Valid(new ISidePrefixCarry(p, lineBytes))
  val terminateResident = Input(Bool())
  val flush = Input(new IfuInnerFlush(p))

  val waitingForNextLine = Output(Bool())
  val staleNextLineResponse = Output(Bool())
}

class ISideF3LineAssembler(
    val p: InterfaceParams = InterfaceParams(),
    val lineBytes: Int = 64)
    extends Module {
  require(p.fetchWidth == 4)
  require(p.insnWidth == 64)
  require(lineBytes >= 8 && (lineBytes & (lineBytes - 1)) == 0)

  private val lineOffsetBits = log2Ceil(lineBytes)
  private val byteOffsetWidth = log2Ceil(lineBytes * 2 + 1)

  val io = IO(new ISideF3LineAssemblerIO(p, lineBytes))

  val residentValid = RegInit(false.B)
  val resident = RegInit(0.U.asTypeOf(new ISideF2Result(p, lineBytes)))
  val cursorOffset = RegInit(0.U(byteOffsetWidth.W))
  val secondLineValid = RegInit(false.B)
  val secondLine = RegInit(0.U.asTypeOf(new ISideLineResponse(p, lineBytes)))
  val nextRequestIssued = RegInit(false.B)
  val nextInstructionUid = RegInit(0.U(p.uopUidWidth.W))

  val killResident =
    residentValid &&
      IfuFlushContract.kills(resident.request.identity, resident.request.transactionId, io.flush)

  val combinedData = Cat(
    Mux(secondLineValid, secondLine.lineData, 0.U((lineBytes * 8).W)),
    resident.lineData)
  val offsets = Wire(Vec(p.fetchWidth, UInt(byteOffsetWidth.W)))
  val lengths = Wire(Vec(p.fetchWidth, UInt(p.lenWidth.W)))
  val laneValid = Wire(Vec(p.fetchWidth, Bool()))
  val laneNeedsSecond = Wire(Vec(p.fetchWidth, Bool()))
  val rawInsns = Wire(Vec(p.fetchWidth, UInt(p.insnWidth.W)))

  offsets(0) := cursorOffset

  for (lane <- 0 until p.fetchWidth) {
    val shifted = combinedData >> (offsets(lane) << 3)
    lengths(lane) := F4DecodeWindow.instructionLengthBytes(shifted)
    val startsInFirstLine = offsets(lane) < lineBytes.U
    val endOffset = offsets(lane) + lengths(lane)
    val crossesFirstLine = startsInFirstLine && endOffset > lineBytes.U
    val availableBytes = Mux(secondLineValid, (lineBytes * 2).U, lineBytes.U)
    val fitsAvailable = offsets(lane) < availableBytes && endOffset <= availableBytes
    val priorContinues = if (lane == 0) true.B else laneValid(lane - 1)

    laneNeedsSecond(lane) :=
      residentValid &&
        priorContinues &&
        crossesFirstLine &&
        !secondLineValid
    laneValid(lane) :=
      residentValid &&
        resident.status === ISideF2Status.Hit &&
        priorContinues &&
        startsInFirstLine &&
        fitsAvailable
    rawInsns(lane) :=
      shifted(p.insnWidth - 1, 0) &
        F4DecodeWindow.lowBytesMask(lengths(lane))

    if (lane + 1 < p.fetchWidth) {
      offsets(lane + 1) :=
        offsets(lane) + Mux(laneValid(lane), lengths(lane), 0.U)
    }
  }

  val needsSecondLine = laneNeedsSecond.reduce(_ || _)
  val finalLane = p.fetchWidth - 1
  val nextCursor = offsets(finalLane) + lengths(finalLane)
  val emittedEnd = WireDefault(cursorOffset)
  for (lane <- 0 until p.fetchWidth) {
    when(laneValid(lane)) {
      emittedEnd := offsets(lane) + lengths(lane)
    }
  }
  val residentContinuesAfterOut =
    laneValid(finalLane) &&
      !io.terminateResident &&
      nextCursor < lineBytes.U
  val nextLineIdentityMatch =
    io.nextLineResponse.bits.peId === resident.request.identity.peId &&
      io.nextLineResponse.bits.transactionId === resident.request.transactionId &&
      io.nextLineResponse.bits.threadId === resident.request.identity.threadId &&
      io.nextLineResponse.bits.fetchPacketUid === resident.request.identity.fetchPacketUid &&
      io.nextLineResponse.bits.fetchSeq === resident.request.identity.fetchSeq &&
      io.nextLineResponse.bits.checkpointId === resident.request.identity.checkpointId &&
      io.nextLineResponse.bits.epoch === resident.request.identity.epoch &&
      io.nextLineResponse.bits.lineVa === resident.request.lineVa + lineBytes.U

  io.nextLineRequest.valid :=
    residentValid &&
      needsSecondLine &&
      !nextRequestIssued &&
      !killResident
  io.nextLineRequest.bits := resident.request
  io.nextLineRequest.bits.pc := resident.request.lineVa + lineBytes.U
  io.nextLineRequest.bits.lineVa := resident.request.lineVa + lineBytes.U

  io.nextLineResponse.ready :=
    residentValid &&
      nextRequestIssued &&
      !secondLineValid &&
      nextLineIdentityMatch &&
      !killResident

  io.out.valid :=
    residentValid &&
      resident.status === ISideF2Status.Hit &&
      !needsSecondLine &&
      laneValid.asUInt.orR &&
      !killResident
  io.in.ready :=
    !residentValid ||
      killResident ||
      (io.out.fire && !residentContinuesAfterOut)
  io.out.bits := 0.U.asTypeOf(io.out.bits)
  io.out.bits.validMask := laneValid.asUInt
  io.out.bits.lineComplete := !laneValid(finalLane) || nextCursor >= lineBytes.U
  for (lane <- 0 until p.fetchWidth) {
    val laneOrdinal =
      if (lane == 0) 0.U else PopCount(VecInit(laneValid.take(lane)))
    io.out.bits.entries(lane).pc := resident.request.lineVa + offsets(lane)
    io.out.bits.entries(lane).instructionUid :=
      Mux(laneValid(lane), nextInstructionUid + laneOrdinal, 0.U)
    io.out.bits.entries(lane).insn := Mux(laneValid(lane), rawInsns(lane), 0.U)
    io.out.bits.entries(lane).lenBytes := Mux(laneValid(lane), lengths(lane), 0.U)
    io.out.bits.entries(lane).crossesLine :=
      laneValid(lane) && offsets(lane) + lengths(lane) > lineBytes.U
    io.out.bits.entries(lane).identity := resident.request.identity
    io.out.bits.entries(lane).identity.fetchSlot := lane.U
    io.out.bits.entries(lane).prediction := resident.request.prediction
  }

  io.prefixCarry.valid := io.out.fire && emittedEnd > lineBytes.U
  io.prefixCarry.bits := 0.U.asTypeOf(io.prefixCarry.bits)
  io.prefixCarry.bits.successorTransactionId := resident.request.transactionId + 1.U
  io.prefixCarry.bits.successorIdentity := resident.request.identity
  io.prefixCarry.bits.successorIdentity.fetchPacketUid :=
    resident.request.identity.fetchPacketUid + 1.U
  io.prefixCarry.bits.successorIdentity.fetchSeq := resident.request.identity.fetchSeq + 1.U
  io.prefixCarry.bits.successorIdentity.fetchSlot := 0.U
  io.prefixCarry.bits.successorIdentity.checkpointId :=
    resident.request.identity.checkpointId + 1.U
  io.prefixCarry.bits.successorLineVa := resident.request.lineVa + lineBytes.U
  io.prefixCarry.bits.successorPc := resident.request.lineVa + emittedEnd

  io.waitingForNextLine := residentValid && needsSecondLine
  io.staleNextLineResponse :=
    io.nextLineResponse.valid &&
      residentValid &&
      nextRequestIssued &&
      !nextLineIdentityMatch

  when(killResident || (io.out.fire && !residentContinuesAfterOut)) {
    residentValid := false.B
    cursorOffset := 0.U
    secondLineValid := false.B
    nextRequestIssued := false.B
  }
  when(io.out.fire && residentContinuesAfterOut) {
    cursorOffset := nextCursor
    secondLineValid := false.B
    nextRequestIssued := false.B
  }
  when(io.in.fire) {
    residentValid := true.B
    resident := io.in.bits
    cursorOffset := io.in.bits.request.pc(lineOffsetBits - 1, 0)
    secondLineValid := false.B
    nextRequestIssued := false.B
  }
  when(io.nextLineRequest.fire) {
    nextRequestIssued := true.B
  }
  when(io.nextLineResponse.fire) {
    secondLineValid := true.B
    secondLine := io.nextLineResponse.bits
  }

  when(io.out.fire) {
    nextInstructionUid := nextInstructionUid + PopCount(io.out.bits.validMask)
    assert(PopCount(io.out.bits.validMask) =/= 0.U)
  }
}
