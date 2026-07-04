package linxcore.lsu

import chisel3._
import chisel3.util.Cat

class LoadReplayReturnSideEffectLiveControlIO extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val liveRequested = Input(Bool())
  val payloadValid = Input(Bool())
  val writebackRequired = Input(Bool())
  val wakeupRequired = Input(Bool())

  val active = Output(Bool())
  val requestActive = Output(Bool())
  val candidateValid = Output(Bool())
  val requiredMask = Output(UInt(3.W))
  val liveEnableMask = Output(UInt(3.W))
  val anyRequired = Output(Bool())
  val allRequiredLiveEnabled = Output(Bool())
  val publishLiveEnable = Output(Bool())
  val writebackLiveEnable = Output(Bool())
  val wakeupLiveEnable = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoPayload = Output(Bool())
  val blockedByLiveDisabled = Output(Bool())
}

class LoadReplayReturnSideEffectLiveControl extends Module {
  val io = IO(new LoadReplayReturnSideEffectLiveControlIO)

  val active = io.enable && !io.flush
  val requestActive = active && io.liveRequested
  val candidateValid = active && io.payloadValid
  val requiredMask = Mux(io.payloadValid, Cat(io.wakeupRequired, io.writebackRequired, true.B), 0.U(3.W))
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
  io.publishLiveEnable := liveEnableMask(0)
  io.writebackLiveEnable := liveEnableMask(1)
  io.wakeupLiveEnable := liveEnableMask(2)
  io.blockedByDisabled := !io.enable && io.payloadValid
  io.blockedByFlush := io.enable && io.flush && io.payloadValid
  io.blockedByNoPayload := active && !io.payloadValid
  io.blockedByLiveDisabled := candidateValid && anyRequired && !io.liveRequested
}
