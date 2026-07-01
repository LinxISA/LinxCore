package linxcore.frontend

import chisel3._
import linxcore.common.InterfaceParams

class ReducedBfuResolvedBodyEndSourceIO(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val runtimeValid = Input(Bool())
  val runtimeHeaderPc = Input(UInt(p.pcWidth.W))
  val runtimeHSizeBytes = Input(UInt(p.pcWidth.W))
  val runtimeBodyEndPc = Input(UInt(p.pcWidth.W))

  val replayValid = Input(Bool())
  val replayHeaderPc = Input(UInt(p.pcWidth.W))
  val replayHSizeBytes = Input(UInt(p.pcWidth.W))
  val replayBSizeBytes = Input(UInt(p.pcWidth.W))

  val resolvedValid = Output(Bool())
  val resolvedHeaderPc = Output(UInt(p.pcWidth.W))
  val resolvedHSizeBytes = Output(UInt(p.pcWidth.W))
  val resolvedBodyEndPc = Output(UInt(p.pcWidth.W))

  val replayBodyEndPc = Output(UInt(p.pcWidth.W))
  val selectedRuntime = Output(Bool())
  val selectedReplay = Output(Bool())
  val runtimeReplayComparable = Output(Bool())
  val runtimeReplayMatch = Output(Bool())
  val runtimeReplayHeaderMismatch = Output(Bool())
  val runtimeReplayHSizeMismatch = Output(Bool())
  val runtimeReplayBodyEndMismatch = Output(Bool())
}

class ReducedBfuResolvedBodyEndSource(val p: InterfaceParams = InterfaceParams()) extends Module {
  val io = IO(new ReducedBfuResolvedBodyEndSourceIO(p))

  val replayBodyBasePc = (io.replayHeaderPc + 2.U)(p.pcWidth - 1, 0)
  val replayBodyEndPc = (replayBodyBasePc + io.replayBSizeBytes)(p.pcWidth - 1, 0)
  val selectedRuntime = io.runtimeValid
  val selectedReplay = !io.runtimeValid && io.replayValid
  val comparable = io.runtimeValid && io.replayValid
  val headerMatch = io.runtimeHeaderPc === io.replayHeaderPc
  val hsizeMatch = io.runtimeHSizeBytes === io.replayHSizeBytes
  val bodyEndMatch = io.runtimeBodyEndPc === replayBodyEndPc
  val matchAll = comparable && headerMatch && hsizeMatch && bodyEndMatch

  io.resolvedValid := selectedRuntime || selectedReplay
  io.resolvedHeaderPc := Mux(selectedRuntime, io.runtimeHeaderPc, io.replayHeaderPc)
  io.resolvedHSizeBytes := Mux(selectedRuntime, io.runtimeHSizeBytes, io.replayHSizeBytes)
  io.resolvedBodyEndPc := Mux(selectedRuntime, io.runtimeBodyEndPc, replayBodyEndPc)

  io.replayBodyEndPc := replayBodyEndPc
  io.selectedRuntime := selectedRuntime
  io.selectedReplay := selectedReplay
  io.runtimeReplayComparable := comparable
  io.runtimeReplayMatch := matchAll
  io.runtimeReplayHeaderMismatch := comparable && !headerMatch
  io.runtimeReplayHSizeMismatch := comparable && !hsizeMatch
  io.runtimeReplayBodyEndMismatch := comparable && !bodyEndMatch
}
