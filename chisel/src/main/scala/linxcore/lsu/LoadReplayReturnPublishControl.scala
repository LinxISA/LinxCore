package linxcore.lsu

import chisel3._

class LoadReplayReturnPublishControlIO extends Bundle {
  val enable = Input(Bool())
  val liveEnable = Input(Bool())
  val payloadValid = Input(Bool())
  val publishReady = Input(Bool())
  val sideEffectsReady = Input(Bool())

  val candidateValid = Output(Bool())
  val publishArmed = Output(Bool())
  val publishFire = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByNoPayload = Output(Bool())
  val blockedByPublish = Output(Bool())
  val blockedBySideEffects = Output(Bool())
  val blockedByLiveDisabled = Output(Bool())
}

class LoadReplayReturnPublishControl extends Module {
  val io = IO(new LoadReplayReturnPublishControlIO)

  val candidateValid = io.enable && io.payloadValid
  val publishArmed = candidateValid && io.publishReady && io.sideEffectsReady

  io.candidateValid := candidateValid
  io.publishArmed := publishArmed
  io.publishFire := publishArmed && io.liveEnable

  io.blockedByDisabled := !io.enable && io.payloadValid
  io.blockedByNoPayload := io.enable && !io.payloadValid
  io.blockedByPublish := candidateValid && !io.publishReady
  io.blockedBySideEffects := candidateValid && io.publishReady && !io.sideEffectsReady
  io.blockedByLiveDisabled := publishArmed && !io.liveEnable
}
