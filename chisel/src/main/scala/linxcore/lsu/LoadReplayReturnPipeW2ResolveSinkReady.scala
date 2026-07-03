package linxcore.lsu

import chisel3._

class LoadReplayReturnPipeW2ResolveSinkReadyIO extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val liveEnable = Input(Bool())
  val resolveRequired = Input(Bool())
  val sinkReady = Input(Bool())

  val candidateValid = Output(Bool())
  val resolveArmed = Output(Bool())
  val resolveSinkReady = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoResolve = Output(Bool())
  val blockedBySink = Output(Bool())
  val blockedByLiveDisabled = Output(Bool())
}

class LoadReplayReturnPipeW2ResolveSinkReady extends Module {
  val io = IO(new LoadReplayReturnPipeW2ResolveSinkReadyIO)

  val candidateValid = io.enable && !io.flush && io.resolveRequired
  val resolveArmed = candidateValid && io.sinkReady

  io.candidateValid := candidateValid
  io.resolveArmed := resolveArmed
  io.resolveSinkReady := resolveArmed && io.liveEnable
  io.blockedByDisabled := !io.enable && io.resolveRequired
  io.blockedByFlush := io.enable && io.flush && io.resolveRequired
  io.blockedByNoResolve := io.enable && !io.flush && !io.resolveRequired
  io.blockedBySink := candidateValid && !io.sinkReady
  io.blockedByLiveDisabled := resolveArmed && !io.liveEnable
}
