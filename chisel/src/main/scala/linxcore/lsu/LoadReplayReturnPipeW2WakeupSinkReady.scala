package linxcore.lsu

import chisel3._

class LoadReplayReturnPipeW2WakeupSinkReadyIO extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val liveEnable = Input(Bool())
  val wakeupRequired = Input(Bool())
  val sinkReady = Input(Bool())

  val candidateValid = Output(Bool())
  val wakeupArmed = Output(Bool())
  val wakeupSinkReady = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoWakeup = Output(Bool())
  val blockedBySink = Output(Bool())
  val blockedByLiveDisabled = Output(Bool())
}

class LoadReplayReturnPipeW2WakeupSinkReady extends Module {
  val io = IO(new LoadReplayReturnPipeW2WakeupSinkReadyIO)

  val candidateValid = io.enable && !io.flush && io.wakeupRequired
  val wakeupArmed = candidateValid && io.sinkReady

  io.candidateValid := candidateValid
  io.wakeupArmed := wakeupArmed
  io.wakeupSinkReady := wakeupArmed && io.liveEnable
  io.blockedByDisabled := !io.enable && io.wakeupRequired
  io.blockedByFlush := io.enable && io.flush && io.wakeupRequired
  io.blockedByNoWakeup := io.enable && !io.flush && !io.wakeupRequired
  io.blockedBySink := candidateValid && !io.sinkReady
  io.blockedByLiveDisabled := wakeupArmed && !io.liveEnable
}
