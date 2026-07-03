package linxcore.lsu

import chisel3._
import chisel3.util.Cat

class LoadReplayReturnPipeW2SideEffectPayloadPlanIO extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val sideEffectCandidateValid = Input(Bool())
  val requestValid = Input(Bool())
  val resolveRequired = Input(Bool())
  val writebackRequired = Input(Bool())
  val wakeupRequired = Input(Bool())
  val resolveRequest = Input(Bool())
  val writebackRequest = Input(Bool())
  val wakeupRequest = Input(Bool())
  val resolvePayloadValid = Input(Bool())
  val writebackPayloadValid = Input(Bool())
  val wakeupPayloadValid = Input(Bool())

  val candidateValid = Output(Bool())
  val requiredMask = Output(UInt(3.W))
  val requestMask = Output(UInt(3.W))
  val payloadValidMask = Output(UInt(3.W))
  val requestMatchesRequired = Output(Bool())
  val payloadMatchesRequired = Output(Bool())
  val issueValid = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoCandidate = Output(Bool())
  val blockedByNoRequest = Output(Bool())
  val blockedByRequestMismatch = Output(Bool())
  val blockedByMissingResolvePayload = Output(Bool())
  val blockedByMissingWritebackPayload = Output(Bool())
  val blockedByMissingWakeupPayload = Output(Bool())
  val blockedByUnexpectedResolvePayload = Output(Bool())
  val blockedByUnexpectedWritebackPayload = Output(Bool())
  val blockedByUnexpectedWakeupPayload = Output(Bool())
  val invalidRequestWithoutCandidate = Output(Bool())
  val invalidPayloadWithoutRequest = Output(Bool())
}

class LoadReplayReturnPipeW2SideEffectPayloadPlan extends Module {
  val io = IO(new LoadReplayReturnPipeW2SideEffectPayloadPlanIO)

  val requiredMask = Cat(io.wakeupRequired, io.writebackRequired, io.resolveRequired)
  val requestMask = Cat(io.wakeupRequest, io.writebackRequest, io.resolveRequest)
  val payloadValidMask = Cat(io.wakeupPayloadValid, io.writebackPayloadValid, io.resolvePayloadValid)
  val active = io.enable && !io.flush
  val candidateValid = active && io.sideEffectCandidateValid
  val requestMatchesRequired = requestMask === requiredMask
  val payloadMatchesRequired = payloadValidMask === requiredMask
  val issueValid = candidateValid && io.requestValid && requestMatchesRequired && payloadMatchesRequired

  io.candidateValid := candidateValid
  io.requiredMask := requiredMask
  io.requestMask := requestMask
  io.payloadValidMask := payloadValidMask
  io.requestMatchesRequired := requestMatchesRequired
  io.payloadMatchesRequired := payloadMatchesRequired
  io.issueValid := issueValid
  io.blockedByDisabled := !io.enable
  io.blockedByFlush := io.enable && io.flush
  io.blockedByNoCandidate := active && !io.sideEffectCandidateValid
  io.blockedByNoRequest := candidateValid && !io.requestValid
  io.blockedByRequestMismatch := candidateValid && io.requestValid && !requestMatchesRequired
  io.blockedByMissingResolvePayload :=
    candidateValid && io.requestValid && io.resolveRequired && !io.resolvePayloadValid
  io.blockedByMissingWritebackPayload :=
    candidateValid && io.requestValid && io.writebackRequired && !io.writebackPayloadValid
  io.blockedByMissingWakeupPayload :=
    candidateValid && io.requestValid && io.wakeupRequired && !io.wakeupPayloadValid
  io.blockedByUnexpectedResolvePayload :=
    candidateValid && io.requestValid && !io.resolveRequired && io.resolvePayloadValid
  io.blockedByUnexpectedWritebackPayload :=
    candidateValid && io.requestValid && !io.writebackRequired && io.writebackPayloadValid
  io.blockedByUnexpectedWakeupPayload :=
    candidateValid && io.requestValid && !io.wakeupRequired && io.wakeupPayloadValid
  io.invalidRequestWithoutCandidate := io.requestValid && !io.sideEffectCandidateValid
  io.invalidPayloadWithoutRequest := !io.requestValid && payloadValidMask.orR
}
