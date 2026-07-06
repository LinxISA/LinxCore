package linxcore.lsu

import chisel3._

import linxcore.commit.CommitTraceParams
import linxcore.rob.ROBID

class LoadReplayReturnPipeW2RetireRecordInstructionMetadataLatchIO(
    val idEntries: Int = 16,
    val traceParams: CommitTraceParams = CommitTraceParams())
    extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val captureAccepted = Input(Bool())
  val capturePayloadRid = Input(new ROBID(idEntries))
  val w2Rid = Input(new ROBID(idEntries))
  val w2InstructionValid = Input(Bool())
  val w2InstructionRaw = Input(UInt(traceParams.insnWidth.W))
  val w2InstructionLen = Input(UInt(traceParams.lenWidth.W))
  val drainInstructionCapture = Input(Bool())
  val drainRid = Input(new ROBID(idEntries))
  val drainInstructionRaw = Input(UInt(traceParams.insnWidth.W))
  val drainInstructionLen = Input(UInt(traceParams.lenWidth.W))
  val recordFire = Input(Bool())
  val recordFireRid = Input(new ROBID(idEntries))
  val recordValid = Input(Bool())
  val recordRid = Input(new ROBID(idEntries))

  val captureIntent = Output(Bool())
  val capturePayloadRidValid = Output(Bool())
  val w2RidValid = Output(Bool())
  val w2RidMatchesCapture = Output(Bool())
  val w2MetadataReady = Output(Bool())
  val captureFromW2 = Output(Bool())
  val captureFromDrain = Output(Bool())
  val captureBlockedByNoPayloadRid = Output(Bool())
  val captureBlockedByNoW2Rid = Output(Bool())
  val captureBlockedByRidMismatch = Output(Bool())
  val captureBlockedByNoW2Metadata = Output(Bool())
  val clearAccepted = Output(Bool())
  val providerValid = Output(Bool())
  val providerRaw = Output(UInt(traceParams.insnWidth.W))
  val providerLen = Output(UInt(traceParams.lenWidth.W))
}

class LoadReplayReturnPipeW2RetireRecordInstructionMetadataLatch(
    val idEntries: Int = 16,
    val traceParams: CommitTraceParams = CommitTraceParams())
    extends Module {
  require(idEntries > 1, "idEntries must be greater than one")
  require((idEntries & (idEntries - 1)) == 0, "idEntries must be a power of two")

  val io = IO(new LoadReplayReturnPipeW2RetireRecordInstructionMetadataLatchIO(idEntries, traceParams))

  val validReg = RegInit(false.B)
  val ridReg = RegInit(ROBID.disabled(idEntries))
  val rawReg = RegInit(0.U(traceParams.insnWidth.W))
  val lenReg = RegInit(0.U(traceParams.lenWidth.W))

  val active = io.enable && !io.flush
  val captureIntent = active && io.captureAccepted
  val capturePayloadRidValid = io.capturePayloadRid.valid
  val w2RidValid = io.w2Rid.valid
  val w2RidMatchesCapture =
    capturePayloadRidValid &&
      w2RidValid &&
      ROBID.equal(io.capturePayloadRid, io.w2Rid)
  val w2MetadataReady =
    io.w2InstructionValid &&
      (io.w2InstructionLen =/= 0.U)
  val captureFromW2 =
    captureIntent &&
      w2RidMatchesCapture &&
      w2MetadataReady
  val drainCaptureCandidate =
    active &&
      io.drainInstructionCapture &&
      io.drainRid.valid &&
      (io.drainInstructionLen =/= 0.U)
  val captureFromDrain = !captureFromW2 && drainCaptureCandidate
  val captureBlockedByNoPayloadRid =
    captureIntent &&
      !capturePayloadRidValid
  val captureBlockedByNoW2Rid =
    captureIntent &&
      capturePayloadRidValid &&
      !w2RidValid
  val captureBlockedByRidMismatch =
    captureIntent &&
      capturePayloadRidValid &&
      w2RidValid &&
      !w2RidMatchesCapture
  val captureBlockedByNoW2Metadata =
    captureIntent &&
      w2RidMatchesCapture &&
      !w2MetadataReady
  val clearAccepted =
    io.recordFire &&
      io.recordFireRid.valid &&
      validReg &&
      ROBID.equal(io.recordFireRid, ridReg)
  val providerValid =
    validReg &&
      io.recordValid &&
      io.recordRid.valid &&
      ROBID.equal(io.recordRid, ridReg)

  when(io.flush || !io.enable) {
    validReg := false.B
    ridReg := ROBID.disabled(idEntries)
    rawReg := 0.U
    lenReg := 0.U
  }.elsewhen(captureFromW2) {
    validReg := true.B
    ridReg := io.capturePayloadRid
    rawReg := io.w2InstructionRaw
    lenReg := io.w2InstructionLen
  }.elsewhen(captureFromDrain) {
    validReg := true.B
    ridReg := io.drainRid
    rawReg := io.drainInstructionRaw
    lenReg := io.drainInstructionLen
  }.elsewhen(clearAccepted) {
    validReg := false.B
    ridReg := ROBID.disabled(idEntries)
    rawReg := 0.U
    lenReg := 0.U
  }

  io.captureIntent := captureIntent
  io.capturePayloadRidValid := capturePayloadRidValid
  io.w2RidValid := w2RidValid
  io.w2RidMatchesCapture := w2RidMatchesCapture
  io.w2MetadataReady := w2MetadataReady
  io.captureFromW2 := captureFromW2
  io.captureFromDrain := captureFromDrain
  io.captureBlockedByNoPayloadRid := captureBlockedByNoPayloadRid
  io.captureBlockedByNoW2Rid := captureBlockedByNoW2Rid
  io.captureBlockedByRidMismatch := captureBlockedByRidMismatch
  io.captureBlockedByNoW2Metadata := captureBlockedByNoW2Metadata
  io.clearAccepted := clearAccepted
  io.providerValid := providerValid
  io.providerRaw := rawReg
  io.providerLen := lenReg
}
