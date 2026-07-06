package linxcore.lsu

import chisel3._
import chisel3.util.log2Ceil

class LoadReplayReturnPipeW2RetireRecordLifecycleEvidenceLatchIO(
    val liqEntries: Int = 16)
    extends Bundle {
  private val liqPtrWidth = log2Ceil(liqEntries)

  val enable = Input(Bool())
  val flush = Input(Bool())
  val captureAccepted = Input(Bool())
  val captureLifecycleRowClearReady = Input(Bool())
  val captureRowClearIndex = Input(UInt(liqPtrWidth.W))
  val recordValid = Input(Bool())
  val recordFire = Input(Bool())

  val active = Output(Bool())
  val captureIntent = Output(Bool())
  val captureFromLifecycle = Output(Bool())
  val captureBlockedByNoLifecycle = Output(Bool())
  val clearAccepted = Output(Bool())
  val providerValid = Output(Bool())
  val providerRowClearReady = Output(Bool())
  val providerRowClearIndex = Output(UInt(liqPtrWidth.W))
  val providerValidWithoutRecord = Output(Bool())
  val recordValidWithoutProvider = Output(Bool())
}

class LoadReplayReturnPipeW2RetireRecordLifecycleEvidenceLatch(
    val liqEntries: Int = 16)
    extends Module {
  require(liqEntries > 1, "LIQ entries must be greater than one")
  require((liqEntries & (liqEntries - 1)) == 0, "LIQ entries must be a power of two")

  private val liqPtrWidth = log2Ceil(liqEntries)

  val io = IO(new LoadReplayReturnPipeW2RetireRecordLifecycleEvidenceLatchIO(liqEntries))

  val validReg = RegInit(false.B)
  val rowClearIndexReg = RegInit(0.U(liqPtrWidth.W))

  val active = io.enable && !io.flush
  val captureIntent = active && io.captureAccepted
  val captureFromLifecycle = captureIntent && io.captureLifecycleRowClearReady
  val clearAccepted = active && io.recordFire && validReg

  when(io.flush || !io.enable) {
    validReg := false.B
    rowClearIndexReg := 0.U
  }.otherwise {
    when(clearAccepted && !captureFromLifecycle) {
      validReg := false.B
    }
    when(captureFromLifecycle) {
      validReg := true.B
      rowClearIndexReg := io.captureRowClearIndex
    }
  }

  val providerValid = active && validReg && io.recordValid

  io.active := active
  io.captureIntent := captureIntent
  io.captureFromLifecycle := captureFromLifecycle
  io.captureBlockedByNoLifecycle := captureIntent && !io.captureLifecycleRowClearReady
  io.clearAccepted := clearAccepted
  io.providerValid := providerValid
  io.providerRowClearReady := providerValid
  io.providerRowClearIndex := Mux(providerValid, rowClearIndexReg, 0.U(liqPtrWidth.W))
  io.providerValidWithoutRecord := active && validReg && !io.recordValid
  io.recordValidWithoutProvider := active && io.recordValid && !validReg
}
