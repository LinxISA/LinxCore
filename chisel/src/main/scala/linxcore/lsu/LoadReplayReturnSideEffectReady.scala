package linxcore.lsu

import chisel3._

class LoadReplayReturnSideEffectReadyIO extends Bundle {
  val enable = Input(Bool())
  val payloadValid = Input(Bool())
  val lretSinkReady = Input(Bool())
  val writebackRequired = Input(Bool())
  val writebackSinkReady = Input(Bool())
  val wakeupRequired = Input(Bool())
  val wakeupSinkReady = Input(Bool())

  val candidateValid = Output(Bool())
  val lretReady = Output(Bool())
  val writebackReady = Output(Bool())
  val wakeupReady = Output(Bool())
  val sideEffectsReady = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByNoPayload = Output(Bool())
  val blockedByLret = Output(Bool())
  val blockedByWriteback = Output(Bool())
  val blockedByWakeup = Output(Bool())
}

class LoadReplayReturnSideEffectReady extends Module {
  val io = IO(new LoadReplayReturnSideEffectReadyIO)

  val candidateValid = io.enable && io.payloadValid
  val writebackReady = !io.writebackRequired || io.writebackSinkReady
  val wakeupReady = !io.wakeupRequired || io.wakeupSinkReady

  io.candidateValid := candidateValid
  io.lretReady := candidateValid && io.lretSinkReady
  io.writebackReady := candidateValid && writebackReady
  io.wakeupReady := candidateValid && wakeupReady
  io.sideEffectsReady := candidateValid && io.lretSinkReady && writebackReady && wakeupReady

  io.blockedByDisabled := !io.enable && io.payloadValid
  io.blockedByNoPayload := io.enable && !io.payloadValid
  io.blockedByLret := candidateValid && !io.lretSinkReady
  io.blockedByWriteback := candidateValid && io.writebackRequired && !io.writebackSinkReady
  io.blockedByWakeup := candidateValid && io.wakeupRequired && !io.wakeupSinkReady
}
