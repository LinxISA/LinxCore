package linxcore.lsu

import chisel3._
import chisel3.util.log2Ceil

import linxcore.rob.ROBID

class LoadReplayReturnPipeW2RetireRecordWakeupFallbackGuardIO(
    val idEntries: Int = 16,
    val physRegWidth: Int = 6)
    extends Bundle {
  private val ptrWidth = log2Ceil(idEntries)

  val enable = Input(Bool())
  val flush = Input(Bool())
  val fallbackEnable = Input(Bool())
  val captureAccepted = Input(Bool())
  val captureRid = Input(new ROBID(idEntries))
  val physicalWakeupValid = Input(Bool())
  val physicalWakeupRid = Input(new ROBID(idEntries))
  val physicalReducedGprWakeup = Input(Bool())
  val physicalWakeupTag = Input(UInt(physRegWidth.W))
  val recordValid = Input(Bool())
  val recordRid = Input(new ROBID(idEntries))
  val recordWakeupValid = Input(Bool())
  val recordReducedGprWakeup = Input(Bool())
  val recordWakeupTag = Input(UInt(physRegWidth.W))
  val recordFire = Input(Bool())

  val active = Output(Bool())
  val captureIntent = Output(Bool())
  val capturePhysicalWakeup = Output(Bool())
  val captureBlockedByNoPhysicalWakeup = Output(Bool())
  val recordCandidate = Output(Bool())
  val recordMatchesCapture = Output(Bool())
  val physicalWakeupPayloadMatches = Output(Bool())
  val duplicatePhysicalWakeup = Output(Bool())
  val fallbackEligible = Output(Bool())
  val fallbackWakeupValid = Output(Bool())
  val fallbackReducedGprWakeup = Output(Bool())
  val fallbackWakeupTag = Output(UInt(physRegWidth.W))
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoRecord = Output(Bool())
  val blockedByInvalidRid = Output(Bool())
  val blockedByNoRecordWakeup = Output(Bool())
  val blockedByNoCaptureEvidence = Output(Bool())
  val blockedByPriorPhysicalWakeup = Output(Bool())
  val blockedByPhysicalWakeupPayloadMismatch = Output(Bool())
  val blockedByFallbackDisabled = Output(Bool())
}

class LoadReplayReturnPipeW2RetireRecordWakeupFallbackGuard(
    val idEntries: Int = 16,
    val physRegWidth: Int = 6)
    extends Module {
  require(idEntries > 1, "idEntries must be greater than one")
  require((idEntries & (idEntries - 1)) == 0, "idEntries must be a power of two")
  require(physRegWidth > 0, "physRegWidth must be positive")

  private val ptrWidth = log2Ceil(idEntries)

  val io = IO(new LoadReplayReturnPipeW2RetireRecordWakeupFallbackGuardIO(
    idEntries,
    physRegWidth
  ))

  val captureValidReg = RegInit(false.B)
  val captureRidValueReg = RegInit(0.U(ptrWidth.W))
  val physicalWakeupSeenReg = RegInit(false.B)
  val physicalReducedGprWakeupReg = RegInit(false.B)
  val physicalWakeupTagReg = RegInit(0.U(physRegWidth.W))

  val active = io.enable && !io.flush
  val captureIntent = active && io.captureAccepted && io.captureRid.valid
  val capturePhysicalWakeup =
    captureIntent &&
      io.physicalWakeupValid &&
      io.physicalWakeupRid.valid &&
      (io.physicalWakeupRid.value === io.captureRid.value)
  val clearCapture = active && io.recordFire && captureValidReg

  when(io.flush || !io.enable) {
    captureValidReg := false.B
    captureRidValueReg := 0.U
    physicalWakeupSeenReg := false.B
    physicalReducedGprWakeupReg := false.B
    physicalWakeupTagReg := 0.U
  }.otherwise {
    when(clearCapture && !captureIntent) {
      captureValidReg := false.B
      physicalWakeupSeenReg := false.B
      physicalReducedGprWakeupReg := false.B
      physicalWakeupTagReg := 0.U
    }
    when(captureIntent) {
      captureValidReg := true.B
      captureRidValueReg := io.captureRid.value
      physicalWakeupSeenReg := capturePhysicalWakeup
      physicalReducedGprWakeupReg := Mux(capturePhysicalWakeup, io.physicalReducedGprWakeup, false.B)
      physicalWakeupTagReg := Mux(capturePhysicalWakeup, io.physicalWakeupTag, 0.U)
    }
  }

  val recordCandidate = active && io.recordValid
  val recordRidValid = io.recordRid.valid
  val recordMatchesCapture =
    recordCandidate && recordRidValid && captureValidReg && (io.recordRid.value === captureRidValueReg)
  val recordWakeupReady = recordCandidate && io.recordWakeupValid
  val physicalWakeupPayloadMatches =
    recordWakeupReady &&
      physicalWakeupSeenReg &&
      (io.recordReducedGprWakeup === physicalReducedGprWakeupReg) &&
      (io.recordWakeupTag === physicalWakeupTagReg)
  val duplicatePhysicalWakeup = recordMatchesCapture && physicalWakeupPayloadMatches
  val fallbackEligible = recordWakeupReady && recordMatchesCapture && !physicalWakeupSeenReg
  val fallbackWakeupValid = io.fallbackEnable && fallbackEligible

  io.active := active
  io.captureIntent := captureIntent
  io.capturePhysicalWakeup := capturePhysicalWakeup
  io.captureBlockedByNoPhysicalWakeup := captureIntent && !capturePhysicalWakeup
  io.recordCandidate := recordCandidate
  io.recordMatchesCapture := recordMatchesCapture
  io.physicalWakeupPayloadMatches := physicalWakeupPayloadMatches
  io.duplicatePhysicalWakeup := duplicatePhysicalWakeup
  io.fallbackEligible := fallbackEligible
  io.fallbackWakeupValid := fallbackWakeupValid
  io.fallbackReducedGprWakeup := fallbackWakeupValid && io.recordReducedGprWakeup
  io.fallbackWakeupTag := Mux(fallbackWakeupValid, io.recordWakeupTag, 0.U)
  io.blockedByDisabled :=
    !io.enable && (io.captureAccepted || io.recordValid || io.recordWakeupValid)
  io.blockedByFlush :=
    io.enable && io.flush && (io.captureAccepted || io.recordValid || io.recordWakeupValid)
  io.blockedByNoRecord := active && !io.recordValid && io.recordWakeupValid
  io.blockedByInvalidRid := recordCandidate && !recordRidValid
  io.blockedByNoRecordWakeup := recordCandidate && !io.recordWakeupValid
  io.blockedByNoCaptureEvidence := recordWakeupReady && !recordMatchesCapture
  io.blockedByPriorPhysicalWakeup := recordWakeupReady && recordMatchesCapture && physicalWakeupSeenReg
  io.blockedByPhysicalWakeupPayloadMismatch :=
    recordWakeupReady && recordMatchesCapture && physicalWakeupSeenReg && !physicalWakeupPayloadMatches
  io.blockedByFallbackDisabled := fallbackEligible && !io.fallbackEnable
}
