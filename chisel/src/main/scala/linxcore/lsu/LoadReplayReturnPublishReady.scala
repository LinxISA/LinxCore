package linxcore.lsu

import chisel3._

class LoadReplayReturnPublishReadyIO extends Bundle {
  val enable = Input(Bool())
  val launchValid = Input(Bool())
  val dataValid = Input(Bool())
  val consumerReady = Input(Bool())

  val candidateValid = Output(Bool())
  val dataReady = Output(Bool())
  val publishReady = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByNoCandidate = Output(Bool())
  val blockedByData = Output(Bool())
  val blockedByConsumer = Output(Bool())
}

class LoadReplayReturnPublishReady extends Module {
  val io = IO(new LoadReplayReturnPublishReadyIO)

  val candidateValid = io.enable && io.launchValid
  val dataReady = candidateValid && io.dataValid

  io.candidateValid := candidateValid
  io.dataReady := dataReady
  io.publishReady := dataReady && io.consumerReady

  io.blockedByDisabled := !io.enable && io.launchValid
  io.blockedByNoCandidate := io.enable && !io.launchValid
  io.blockedByData := candidateValid && !io.dataValid
  io.blockedByConsumer := dataReady && !io.consumerReady
}
