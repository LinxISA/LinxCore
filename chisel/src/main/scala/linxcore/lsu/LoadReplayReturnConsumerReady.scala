package linxcore.lsu

import chisel3._

class LoadReplayReturnConsumerReadyIO extends Bundle {
  val enable = Input(Bool())
  val launchValid = Input(Bool())
  val sourcesReturned = Input(Bool())
  val specWakeup = Input(Bool())
  val stackValid = Input(Bool())
  val lretSinkReady = Input(Bool())
  val wakeupSinkReady = Input(Bool())

  val candidateValid = Output(Bool())
  val wakeupRequired = Output(Bool())
  val consumerReady = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByNoCandidate = Output(Bool())
  val blockedBySources = Output(Bool())
  val blockedByLretSink = Output(Bool())
  val blockedByWakeupSink = Output(Bool())
}

class LoadReplayReturnConsumerReady extends Module {
  val io = IO(new LoadReplayReturnConsumerReadyIO)

  val candidateValid = io.enable && io.launchValid
  val wakeupRequired = candidateValid && !io.specWakeup && !io.stackValid

  io.candidateValid := candidateValid
  io.wakeupRequired := wakeupRequired
  io.consumerReady := io.enable && io.lretSinkReady && (!wakeupRequired || io.wakeupSinkReady)
  io.blockedByDisabled := !io.enable && io.launchValid
  io.blockedByNoCandidate := io.enable && !io.launchValid
  io.blockedBySources := candidateValid && !io.sourcesReturned
  io.blockedByLretSink := candidateValid && io.sourcesReturned && !io.lretSinkReady
  io.blockedByWakeupSink := candidateValid && io.sourcesReturned && io.lretSinkReady &&
    wakeupRequired && !io.wakeupSinkReady
}
