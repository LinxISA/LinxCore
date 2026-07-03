package linxcore.lsu

import chisel3._

class LoadReplayReturnPipeW2SideEffectFireVectorIO extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val issueAccepted = Input(Bool())
  val acceptedMask = Input(UInt(3.W))
  val requestMask = Input(UInt(3.W))
  val payloadValidMask = Input(UInt(3.W))

  val candidateValid = Output(Bool())
  val fireValid = Output(Bool())
  val fireMask = Output(UInt(3.W))
  val resolveFire = Output(Bool())
  val writebackFire = Output(Bool())
  val wakeupFire = Output(Bool())
  val requestMissingMask = Output(UInt(3.W))
  val payloadMissingMask = Output(UInt(3.W))
  val requestExtraMask = Output(UInt(3.W))
  val payloadExtraMask = Output(UInt(3.W))
  val requestMatchesAccepted = Output(Bool())
  val payloadMatchesAccepted = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoIssueAccept = Output(Bool())
  val blockedByNoAcceptedSink = Output(Bool())
  val blockedByRequestMismatch = Output(Bool())
  val blockedByPayloadMismatch = Output(Bool())
  val invalidAcceptedWithoutRequest = Output(Bool())
  val invalidAcceptedWithoutPayload = Output(Bool())
}

class LoadReplayReturnPipeW2SideEffectFireVector extends Module {
  val io = IO(new LoadReplayReturnPipeW2SideEffectFireVectorIO)

  val active = io.enable && !io.flush
  val candidateValid = active && io.issueAccepted
  val hasAcceptedSink = io.acceptedMask.orR

  val requestMissingMask = io.acceptedMask & ~io.requestMask
  val payloadMissingMask = io.acceptedMask & ~io.payloadValidMask
  val requestExtraMask = io.requestMask & ~io.acceptedMask
  val payloadExtraMask = io.payloadValidMask & ~io.acceptedMask
  val requestMatchesAccepted = io.requestMask === io.acceptedMask
  val payloadMatchesAccepted = io.payloadValidMask === io.acceptedMask
  val fireValid =
    candidateValid && hasAcceptedSink && requestMatchesAccepted && payloadMatchesAccepted
  val fireMask = Mux(fireValid, io.acceptedMask, 0.U(3.W))

  io.candidateValid := candidateValid
  io.fireValid := fireValid
  io.fireMask := fireMask
  io.resolveFire := fireMask(0)
  io.writebackFire := fireMask(1)
  io.wakeupFire := fireMask(2)
  io.requestMissingMask := requestMissingMask
  io.payloadMissingMask := payloadMissingMask
  io.requestExtraMask := requestExtraMask
  io.payloadExtraMask := payloadExtraMask
  io.requestMatchesAccepted := requestMatchesAccepted
  io.payloadMatchesAccepted := payloadMatchesAccepted
  io.blockedByDisabled := !io.enable && io.issueAccepted
  io.blockedByFlush := io.enable && io.flush && io.issueAccepted
  io.blockedByNoIssueAccept := active && !io.issueAccepted
  io.blockedByNoAcceptedSink := candidateValid && !hasAcceptedSink
  io.blockedByRequestMismatch := candidateValid && !requestMatchesAccepted
  io.blockedByPayloadMismatch := candidateValid && !payloadMatchesAccepted
  io.invalidAcceptedWithoutRequest := candidateValid && requestMissingMask.orR
  io.invalidAcceptedWithoutPayload := candidateValid && payloadMissingMask.orR
}
