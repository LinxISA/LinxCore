package linxcore.lsu

import chisel3._
import chisel3.util.log2Ceil

class LoadReplayReturnPipeW2RetireRecordLifecycleClearFallbackGuardIO(
    val liqEntries: Int = 16)
    extends Bundle {
  private val liqPtrWidth = log2Ceil(liqEntries)

  val enable = Input(Bool())
  val flush = Input(Bool())
  val fallbackEnable = Input(Bool())
  val captureAccepted = Input(Bool())
  val captureRowClearReady = Input(Bool())
  val captureRowClearIndex = Input(UInt(liqPtrWidth.W))
  val physicalClearAccepted = Input(Bool())
  val physicalClearIndex = Input(UInt(liqPtrWidth.W))
  val recordValid = Input(Bool())
  val recordLifecycleClearReady = Input(Bool())
  val recordRowClearIndex = Input(UInt(liqPtrWidth.W))
  val recordFire = Input(Bool())

  val active = Output(Bool())
  val captureIntent = Output(Bool())
  val capturePhysicalClear = Output(Bool())
  val captureBlockedByNoPhysicalClear = Output(Bool())
  val recordCandidate = Output(Bool())
  val recordMatchesCapture = Output(Bool())
  val duplicatePhysicalClear = Output(Bool())
  val fallbackEligible = Output(Bool())
  val fallbackClearValid = Output(Bool())
  val fallbackClearIndex = Output(UInt(liqPtrWidth.W))
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoRecord = Output(Bool())
  val blockedByNoRecordLifecycleClear = Output(Bool())
  val blockedByNoCaptureEvidence = Output(Bool())
  val blockedByPriorPhysicalClear = Output(Bool())
  val blockedByFallbackDisabled = Output(Bool())
}

class LoadReplayReturnPipeW2RetireRecordLifecycleClearFallbackGuard(
    val liqEntries: Int = 16)
    extends Module {
  require(liqEntries > 1, "LIQ entries must be greater than one")
  require((liqEntries & (liqEntries - 1)) == 0, "LIQ entries must be a power of two")

  private val liqPtrWidth = log2Ceil(liqEntries)

  val io = IO(new LoadReplayReturnPipeW2RetireRecordLifecycleClearFallbackGuardIO(liqEntries))

  val captureValidReg = RegInit(false.B)
  val captureRowClearIndexReg = RegInit(0.U(liqPtrWidth.W))
  val physicalClearSeenReg = RegInit(false.B)

  val active = io.enable && !io.flush
  val captureIntent = active && io.captureAccepted && io.captureRowClearReady
  val capturePhysicalClear =
    captureIntent &&
      io.physicalClearAccepted &&
      (io.physicalClearIndex === io.captureRowClearIndex)
  val clearCapture = active && io.recordFire && captureValidReg

  when(io.flush || !io.enable) {
    captureValidReg := false.B
    captureRowClearIndexReg := 0.U
    physicalClearSeenReg := false.B
  }.otherwise {
    when(clearCapture && !captureIntent) {
      captureValidReg := false.B
      physicalClearSeenReg := false.B
    }
    when(captureIntent) {
      captureValidReg := true.B
      captureRowClearIndexReg := io.captureRowClearIndex
      physicalClearSeenReg := capturePhysicalClear
    }
  }

  val recordCandidate = active && io.recordValid
  val recordClearReady = recordCandidate && io.recordLifecycleClearReady
  val recordMatchesCapture =
    recordClearReady && captureValidReg && (io.recordRowClearIndex === captureRowClearIndexReg)
  val duplicatePhysicalClear = recordMatchesCapture && physicalClearSeenReg
  val fallbackEligible = recordMatchesCapture && !physicalClearSeenReg
  val fallbackClearValid = io.fallbackEnable && fallbackEligible

  io.active := active
  io.captureIntent := captureIntent
  io.capturePhysicalClear := capturePhysicalClear
  io.captureBlockedByNoPhysicalClear := captureIntent && !capturePhysicalClear
  io.recordCandidate := recordCandidate
  io.recordMatchesCapture := recordMatchesCapture
  io.duplicatePhysicalClear := duplicatePhysicalClear
  io.fallbackEligible := fallbackEligible
  io.fallbackClearValid := fallbackClearValid
  io.fallbackClearIndex := Mux(fallbackClearValid, io.recordRowClearIndex, 0.U(liqPtrWidth.W))
  io.blockedByDisabled :=
    !io.enable && (io.captureAccepted || io.recordValid || io.recordLifecycleClearReady)
  io.blockedByFlush :=
    io.enable && io.flush && (io.captureAccepted || io.recordValid || io.recordLifecycleClearReady)
  io.blockedByNoRecord := active && !io.recordValid && io.recordLifecycleClearReady
  io.blockedByNoRecordLifecycleClear := recordCandidate && !io.recordLifecycleClearReady
  io.blockedByNoCaptureEvidence := recordClearReady && !recordMatchesCapture
  io.blockedByPriorPhysicalClear := recordMatchesCapture && physicalClearSeenReg
  io.blockedByFallbackDisabled := fallbackEligible && !io.fallbackEnable
}
