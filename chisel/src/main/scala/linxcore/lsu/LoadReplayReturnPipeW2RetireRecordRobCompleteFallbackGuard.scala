package linxcore.lsu

import chisel3._
import chisel3.util.log2Ceil

import linxcore.commit.{CommitTraceParams, CommitTraceRow}
import linxcore.rob.ROBID

class LoadReplayReturnPipeW2RetireRecordRobCompleteFallbackGuardIO(
    val idEntries: Int = 16,
    val traceParams: CommitTraceParams = CommitTraceParams())
    extends Bundle {
  private val ptrWidth = log2Ceil(idEntries)

  val enable = Input(Bool())
  val flush = Input(Bool())
  val fallbackEnable = Input(Bool())
  val captureAccepted = Input(Bool())
  val captureRid = Input(new ROBID(idEntries))
  val physicalCompleteValid = Input(Bool())
  val physicalCompleteRobValue = Input(UInt(ptrWidth.W))
  val recordValid = Input(Bool())
  val recordRid = Input(new ROBID(idEntries))
  val recordFire = Input(Bool())
  val retainedCompleteRowValid = Input(Bool())
  val retainedCompleteRow = Input(new CommitTraceRow(traceParams))

  val active = Output(Bool())
  val captureIntent = Output(Bool())
  val capturePhysicalComplete = Output(Bool())
  val captureBlockedByNoPhysicalComplete = Output(Bool())
  val recordCandidate = Output(Bool())
  val recordMatchesCapture = Output(Bool())
  val duplicatePhysicalComplete = Output(Bool())
  val fallbackEligible = Output(Bool())
  val fallbackCompleteValid = Output(Bool())
  val fallbackCompleteRobValue = Output(UInt(ptrWidth.W))
  val fallbackCompleteRowValid = Output(Bool())
  val fallbackCompleteRow = Output(new CommitTraceRow(traceParams))
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoRecord = Output(Bool())
  val blockedByInvalidRid = Output(Bool())
  val blockedByNoRetainedCompleteRow = Output(Bool())
  val blockedByNoCaptureEvidence = Output(Bool())
  val blockedByPriorPhysicalComplete = Output(Bool())
  val blockedByFallbackDisabled = Output(Bool())
}

class LoadReplayReturnPipeW2RetireRecordRobCompleteFallbackGuard(
    val idEntries: Int = 16,
    val traceParams: CommitTraceParams = CommitTraceParams())
    extends Module {
  require(idEntries > 1, "idEntries must be greater than one")
  require((idEntries & (idEntries - 1)) == 0, "idEntries must be a power of two")

  private val ptrWidth = log2Ceil(idEntries)

  val io = IO(new LoadReplayReturnPipeW2RetireRecordRobCompleteFallbackGuardIO(idEntries, traceParams))

  val captureValidReg = RegInit(false.B)
  val captureRidValueReg = RegInit(0.U(ptrWidth.W))
  val physicalCompleteSeenReg = RegInit(false.B)

  val active = io.enable && !io.flush
  val captureIntent = active && io.captureAccepted && io.captureRid.valid
  val capturePhysicalComplete =
    captureIntent && io.physicalCompleteValid && (io.physicalCompleteRobValue === io.captureRid.value)
  val clearCapture = active && io.recordFire && captureValidReg

  when(io.flush || !io.enable) {
    captureValidReg := false.B
    captureRidValueReg := 0.U
    physicalCompleteSeenReg := false.B
  }.otherwise {
    when(clearCapture && !captureIntent) {
      captureValidReg := false.B
      physicalCompleteSeenReg := false.B
    }
    when(captureIntent) {
      captureValidReg := true.B
      captureRidValueReg := io.captureRid.value
      physicalCompleteSeenReg := capturePhysicalComplete
    }
  }

  val recordCandidate = active && io.recordValid
  val recordRidValid = io.recordRid.valid
  val recordMatchesCapture =
    recordCandidate && recordRidValid && captureValidReg && (io.recordRid.value === captureRidValueReg)
  val duplicatePhysicalComplete = recordMatchesCapture && physicalCompleteSeenReg
  val retainedRowReady = recordCandidate && io.retainedCompleteRowValid
  val fallbackEligible = retainedRowReady && recordMatchesCapture && !physicalCompleteSeenReg
  val fallbackCompleteValid = io.fallbackEnable && fallbackEligible

  io.active := active
  io.captureIntent := captureIntent
  io.capturePhysicalComplete := capturePhysicalComplete
  io.captureBlockedByNoPhysicalComplete := captureIntent && !capturePhysicalComplete
  io.recordCandidate := recordCandidate
  io.recordMatchesCapture := recordMatchesCapture
  io.duplicatePhysicalComplete := duplicatePhysicalComplete
  io.fallbackEligible := fallbackEligible
  io.fallbackCompleteValid := fallbackCompleteValid
  io.fallbackCompleteRobValue := Mux(fallbackCompleteValid, io.recordRid.value, 0.U(ptrWidth.W))
  io.fallbackCompleteRowValid := fallbackCompleteValid
  io.fallbackCompleteRow :=
    Mux(fallbackCompleteValid, io.retainedCompleteRow, 0.U.asTypeOf(new CommitTraceRow(traceParams)))
  io.blockedByDisabled :=
    !io.enable && (io.captureAccepted || io.recordValid || io.retainedCompleteRowValid)
  io.blockedByFlush :=
    io.enable && io.flush && (io.captureAccepted || io.recordValid || io.retainedCompleteRowValid)
  io.blockedByNoRecord := active && !io.recordValid && io.retainedCompleteRowValid
  io.blockedByInvalidRid := recordCandidate && !recordRidValid
  io.blockedByNoRetainedCompleteRow := recordCandidate && !io.retainedCompleteRowValid
  io.blockedByNoCaptureEvidence := retainedRowReady && !recordMatchesCapture
  io.blockedByPriorPhysicalComplete := retainedRowReady && duplicatePhysicalComplete
  io.blockedByFallbackDisabled := fallbackEligible && !io.fallbackEnable
}
