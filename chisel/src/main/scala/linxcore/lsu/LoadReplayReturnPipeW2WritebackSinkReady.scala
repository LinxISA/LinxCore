package linxcore.lsu

import chisel3._

class LoadReplayReturnPipeW2WritebackSinkReadyIO extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val liveEnable = Input(Bool())
  val writebackRequired = Input(Bool())
  val sinkReady = Input(Bool())

  val candidateValid = Output(Bool())
  val writebackArmed = Output(Bool())
  val writebackSinkReady = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoWriteback = Output(Bool())
  val blockedBySink = Output(Bool())
  val blockedByLiveDisabled = Output(Bool())
}

class LoadReplayReturnPipeW2WritebackSinkReady extends Module {
  val io = IO(new LoadReplayReturnPipeW2WritebackSinkReadyIO)

  val candidateValid = io.enable && !io.flush && io.writebackRequired
  val writebackArmed = candidateValid && io.sinkReady

  io.candidateValid := candidateValid
  io.writebackArmed := writebackArmed
  io.writebackSinkReady := writebackArmed && io.liveEnable
  io.blockedByDisabled := !io.enable && io.writebackRequired
  io.blockedByFlush := io.enable && io.flush && io.writebackRequired
  io.blockedByNoWriteback := io.enable && !io.flush && !io.writebackRequired
  io.blockedBySink := candidateValid && !io.sinkReady
  io.blockedByLiveDisabled := writebackArmed && !io.liveEnable
}
