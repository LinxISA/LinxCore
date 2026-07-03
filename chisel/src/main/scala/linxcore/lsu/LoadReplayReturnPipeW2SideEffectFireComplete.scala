package linxcore.lsu

import chisel3._
import chisel3.util.Cat

class LoadReplayReturnPipeW2SideEffectFireCompleteIO extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val fireVectorValid = Input(Bool())
  val fireMask = Input(UInt(3.W))
  val resolveFirePayloadValid = Input(Bool())
  val writebackFirePayloadValid = Input(Bool())
  val wakeupFirePayloadValid = Input(Bool())

  val candidateValid = Output(Bool())
  val observedFireMask = Output(UInt(3.W))
  val payloadFireMask = Output(UInt(3.W))
  val missingPayloadFireMask = Output(UInt(3.W))
  val unexpectedPayloadFireMask = Output(UInt(3.W))
  val payloadMatchesFire = Output(Bool())
  val fireComplete = Output(Bool())
  val futureClearEligible = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoFireVector = Output(Bool())
  val blockedByNoFireSink = Output(Bool())
  val blockedByPayloadMismatch = Output(Bool())
  val invalidFireWithoutPayload = Output(Bool())
  val invalidPayloadWithoutFire = Output(Bool())
}

class LoadReplayReturnPipeW2SideEffectFireComplete extends Module {
  val io = IO(new LoadReplayReturnPipeW2SideEffectFireCompleteIO)

  val active = io.enable && !io.flush
  val payloadFireMask = Cat(
    io.wakeupFirePayloadValid,
    io.writebackFirePayloadValid,
    io.resolveFirePayloadValid)
  val candidateValid = active && io.fireVectorValid
  val fireMaskHasSink = io.fireMask.orR
  val missingPayloadFireMask = io.fireMask & ~payloadFireMask
  val unexpectedPayloadFireMask = payloadFireMask & ~io.fireMask
  val payloadMatchesFire = payloadFireMask === io.fireMask
  val fireComplete = candidateValid && fireMaskHasSink && payloadMatchesFire

  io.candidateValid := candidateValid
  io.observedFireMask := Mux(candidateValid, io.fireMask, 0.U(3.W))
  io.payloadFireMask := payloadFireMask
  io.missingPayloadFireMask := missingPayloadFireMask
  io.unexpectedPayloadFireMask := unexpectedPayloadFireMask
  io.payloadMatchesFire := payloadMatchesFire
  io.fireComplete := fireComplete
  io.futureClearEligible := fireComplete
  io.blockedByDisabled := !io.enable && (io.fireVectorValid || payloadFireMask.orR)
  io.blockedByFlush := io.enable && io.flush && (io.fireVectorValid || payloadFireMask.orR)
  io.blockedByNoFireVector := active && !io.fireVectorValid && payloadFireMask.orR
  io.blockedByNoFireSink := candidateValid && !fireMaskHasSink
  io.blockedByPayloadMismatch := candidateValid && !payloadMatchesFire
  io.invalidFireWithoutPayload := candidateValid && missingPayloadFireMask.orR
  io.invalidPayloadWithoutFire :=
    (active && !io.fireVectorValid && payloadFireMask.orR) ||
      (candidateValid && unexpectedPayloadFireMask.orR)
}
