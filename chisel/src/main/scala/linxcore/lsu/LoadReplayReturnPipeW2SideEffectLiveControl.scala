package linxcore.lsu

import chisel3._
import chisel3.util.Cat

class LoadReplayReturnPipeW2SideEffectLiveControlIO extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val liveRequested = Input(Bool())
  val sideEffectCandidateValid = Input(Bool())
  val resolveRequired = Input(Bool())
  val writebackRequired = Input(Bool())
  val wakeupRequired = Input(Bool())

  val active = Output(Bool())
  val requestActive = Output(Bool())
  val candidateValid = Output(Bool())
  val requiredMask = Output(UInt(3.W))
  val liveEnableMask = Output(UInt(3.W))
  val anyRequired = Output(Bool())
  val allRequiredLiveEnabled = Output(Bool())
  val resolveLiveEnable = Output(Bool())
  val writebackLiveEnable = Output(Bool())
  val wakeupLiveEnable = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoCandidate = Output(Bool())
  val blockedByNoRequiredSink = Output(Bool())
  val blockedByLiveDisabled = Output(Bool())
}

class LoadReplayReturnPipeW2SideEffectLiveControl extends Module {
  val io = IO(new LoadReplayReturnPipeW2SideEffectLiveControlIO)

  val active = io.enable && !io.flush
  val requestActive = active && io.liveRequested
  val candidateValid = active && io.sideEffectCandidateValid
  val requiredMask = Cat(io.wakeupRequired, io.writebackRequired, io.resolveRequired)
  val anyRequired = requiredMask.orR
  val liveAllowed = candidateValid && io.liveRequested && anyRequired
  val liveEnableMask = Mux(liveAllowed, requiredMask, 0.U(3.W))

  io.active := active
  io.requestActive := requestActive
  io.candidateValid := candidateValid
  io.requiredMask := requiredMask
  io.liveEnableMask := liveEnableMask
  io.anyRequired := anyRequired
  io.allRequiredLiveEnabled := liveAllowed
  io.resolveLiveEnable := liveEnableMask(0)
  io.writebackLiveEnable := liveEnableMask(1)
  io.wakeupLiveEnable := liveEnableMask(2)
  io.blockedByDisabled := !io.enable && io.sideEffectCandidateValid
  io.blockedByFlush := io.enable && io.flush && io.sideEffectCandidateValid
  io.blockedByNoCandidate := active && !io.sideEffectCandidateValid
  io.blockedByNoRequiredSink := candidateValid && !anyRequired
  io.blockedByLiveDisabled := candidateValid && anyRequired && !io.liveRequested
}
