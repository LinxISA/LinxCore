package linxcore.lsu

import chisel3._

class LoadReplayReturnPipeW2SideEffectReadyIO extends Bundle {
  val enable = Input(Bool())
  val candidateValid = Input(Bool())
  val resolveRequired = Input(Bool())
  val resolveSinkReady = Input(Bool())
  val writebackRequired = Input(Bool())
  val writebackSinkReady = Input(Bool())
  val wakeupRequired = Input(Bool())
  val wakeupSinkReady = Input(Bool())

  val readyCandidateValid = Output(Bool())
  val resolveReady = Output(Bool())
  val writebackReady = Output(Bool())
  val wakeupReady = Output(Bool())
  val sideEffectsReady = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByNoCandidate = Output(Bool())
  val blockedByResolve = Output(Bool())
  val blockedByWriteback = Output(Bool())
  val blockedByWakeup = Output(Bool())
}

class LoadReplayReturnPipeW2SideEffectReady extends Module {
  val io = IO(new LoadReplayReturnPipeW2SideEffectReadyIO)

  val readyCandidateValid = io.enable && io.candidateValid
  val resolveReady = !io.resolveRequired || io.resolveSinkReady
  val writebackReady = !io.writebackRequired || io.writebackSinkReady
  val wakeupReady = !io.wakeupRequired || io.wakeupSinkReady

  io.readyCandidateValid := readyCandidateValid
  io.resolveReady := readyCandidateValid && resolveReady
  io.writebackReady := readyCandidateValid && writebackReady
  io.wakeupReady := readyCandidateValid && wakeupReady
  io.sideEffectsReady := readyCandidateValid && resolveReady && writebackReady && wakeupReady

  io.blockedByDisabled := !io.enable && io.candidateValid
  io.blockedByNoCandidate := io.enable && !io.candidateValid
  io.blockedByResolve := readyCandidateValid && io.resolveRequired && !io.resolveSinkReady
  io.blockedByWriteback := readyCandidateValid && io.writebackRequired && !io.writebackSinkReady
  io.blockedByWakeup := readyCandidateValid && io.wakeupRequired && !io.wakeupSinkReady
}
