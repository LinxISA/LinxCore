package linxcore.lsu

import chisel3._
import chisel3.util.Cat

class LoadReplayReturnPipeW2SideEffectIssuePermitIO extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val payloadPlanIssueValid = Input(Bool())
  val requiredMask = Input(UInt(3.W))
  val resolveSinkReady = Input(Bool())
  val writebackSinkReady = Input(Bool())
  val wakeupSinkReady = Input(Bool())

  val candidateValid = Output(Bool())
  val issueArmed = Output(Bool())
  val sinkReadyMask = Output(UInt(3.W))
  val missingReadyMask = Output(UInt(3.W))
  val acceptedMask = Output(UInt(3.W))
  val allRequiredSinksReady = Output(Bool())
  val issueAccepted = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoPlan = Output(Bool())
  val blockedByNoRequiredSink = Output(Bool())
  val blockedByResolveSink = Output(Bool())
  val blockedByWritebackSink = Output(Bool())
  val blockedByWakeupSink = Output(Bool())
}

class LoadReplayReturnPipeW2SideEffectIssuePermit extends Module {
  val io = IO(new LoadReplayReturnPipeW2SideEffectIssuePermitIO)

  val active = io.enable && !io.flush
  val candidateValid = active && io.payloadPlanIssueValid
  val hasRequiredSink = io.requiredMask.orR
  val issueArmed = candidateValid && hasRequiredSink

  val resolveRequired = io.requiredMask(0)
  val writebackRequired = io.requiredMask(1)
  val wakeupRequired = io.requiredMask(2)
  val resolveReady = !resolveRequired || io.resolveSinkReady
  val writebackReady = !writebackRequired || io.writebackSinkReady
  val wakeupReady = !wakeupRequired || io.wakeupSinkReady
  val allRequiredSinksReady = resolveReady && writebackReady && wakeupReady
  val issueAccepted = issueArmed && allRequiredSinksReady

  val missingResolve = issueArmed && resolveRequired && !io.resolveSinkReady
  val missingWriteback = issueArmed && writebackRequired && !io.writebackSinkReady
  val missingWakeup = issueArmed && wakeupRequired && !io.wakeupSinkReady

  io.candidateValid := candidateValid
  io.issueArmed := issueArmed
  io.sinkReadyMask := Cat(io.wakeupSinkReady, io.writebackSinkReady, io.resolveSinkReady)
  io.missingReadyMask := Cat(missingWakeup, missingWriteback, missingResolve)
  io.acceptedMask := Mux(issueAccepted, io.requiredMask, 0.U(3.W))
  io.allRequiredSinksReady := allRequiredSinksReady
  io.issueAccepted := issueAccepted
  io.blockedByDisabled := !io.enable && io.payloadPlanIssueValid
  io.blockedByFlush := io.enable && io.flush && io.payloadPlanIssueValid
  io.blockedByNoPlan := active && !io.payloadPlanIssueValid
  io.blockedByNoRequiredSink := candidateValid && !hasRequiredSink
  io.blockedByResolveSink := missingResolve
  io.blockedByWritebackSink := missingWriteback
  io.blockedByWakeupSink := missingWakeup
}
