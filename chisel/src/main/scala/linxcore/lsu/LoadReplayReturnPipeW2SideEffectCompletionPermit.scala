package linxcore.lsu

import chisel3._
import chisel3.util.Cat

class LoadReplayReturnPipeW2SideEffectCompletionPermitIO extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val sideEffectCandidateValid = Input(Bool())
  val resolveRequired = Input(Bool())
  val writebackRequired = Input(Bool())
  val wakeupRequired = Input(Bool())
  val resolveSinkReady = Input(Bool())
  val writebackSinkReady = Input(Bool())
  val wakeupSinkReady = Input(Bool())
  val readyJoinSideEffectsReady = Input(Bool())

  val candidateValid = Output(Bool())
  val requiredMask = Output(UInt(3.W))
  val sinkReadyMask = Output(UInt(3.W))
  val missingReadyMask = Output(UInt(3.W))
  val allRequiredSinksReady = Output(Bool())
  val completionPermitted = Output(Bool())
  val matchesReadyJoin = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoCandidate = Output(Bool())
  val blockedByNoRequiredSink = Output(Bool())
  val blockedByResolveSink = Output(Bool())
  val blockedByWritebackSink = Output(Bool())
  val blockedByWakeupSink = Output(Bool())
  val blockedByReadyJoinMismatch = Output(Bool())
}

class LoadReplayReturnPipeW2SideEffectCompletionPermit extends Module {
  val io = IO(new LoadReplayReturnPipeW2SideEffectCompletionPermitIO)

  val active = io.enable && !io.flush
  val candidateValid = active && io.sideEffectCandidateValid
  val requiredMask = Cat(io.wakeupRequired, io.writebackRequired, io.resolveRequired)
  val hasRequiredSink = requiredMask.orR

  val resolveReady = !io.resolveRequired || io.resolveSinkReady
  val writebackReady = !io.writebackRequired || io.writebackSinkReady
  val wakeupReady = !io.wakeupRequired || io.wakeupSinkReady
  val allRequiredSinksReady = resolveReady && writebackReady && wakeupReady
  val completionPermitted = candidateValid && hasRequiredSink && allRequiredSinksReady
  val matchesReadyJoin = io.readyJoinSideEffectsReady === completionPermitted

  val missingResolve = candidateValid && io.resolveRequired && !io.resolveSinkReady
  val missingWriteback = candidateValid && io.writebackRequired && !io.writebackSinkReady
  val missingWakeup = candidateValid && io.wakeupRequired && !io.wakeupSinkReady

  io.candidateValid := candidateValid
  io.requiredMask := requiredMask
  io.sinkReadyMask := Cat(io.wakeupSinkReady, io.writebackSinkReady, io.resolveSinkReady)
  io.missingReadyMask := Cat(missingWakeup, missingWriteback, missingResolve)
  io.allRequiredSinksReady := allRequiredSinksReady
  io.completionPermitted := completionPermitted
  io.matchesReadyJoin := matchesReadyJoin
  io.blockedByDisabled := !io.enable && io.sideEffectCandidateValid
  io.blockedByFlush := io.enable && io.flush && io.sideEffectCandidateValid
  io.blockedByNoCandidate := active && !io.sideEffectCandidateValid
  io.blockedByNoRequiredSink := candidateValid && !hasRequiredSink
  io.blockedByResolveSink := missingResolve
  io.blockedByWritebackSink := missingWriteback
  io.blockedByWakeupSink := missingWakeup
  io.blockedByReadyJoinMismatch := !matchesReadyJoin
}
