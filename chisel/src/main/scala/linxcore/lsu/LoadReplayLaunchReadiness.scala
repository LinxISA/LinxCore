package linxcore.lsu

import chisel3._

class LoadReplayLaunchReadinessIO extends Bundle {
  val enable = Input(Bool())
  val launchValid = Input(Bool())
  val baseLookupGranted = Input(Bool())
  val baseDataReturned = Input(Bool())
  val scbReturned = Input(Bool())
  val returnReady = Input(Bool())

  val candidateValid = Output(Bool())
  val baseDataReady = Output(Bool())
  val sourcesReturned = Output(Bool())
  val launchReady = Output(Bool())
  val launchEnable = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByNoCandidate = Output(Bool())
  val blockedByBaseLookup = Output(Bool())
  val blockedByBaseData = Output(Bool())
  val blockedByScb = Output(Bool())
  val blockedByReturn = Output(Bool())
}

class LoadReplayLaunchReadiness extends Module {
  val io = IO(new LoadReplayLaunchReadinessIO)

  val candidateValid = io.enable && io.launchValid
  val baseDataReady = io.baseLookupGranted && io.baseDataReturned
  val sourcesReturned = baseDataReady && io.scbReturned
  val launchReady = candidateValid && sourcesReturned && io.returnReady

  io.candidateValid := candidateValid
  io.baseDataReady := baseDataReady
  io.sourcesReturned := sourcesReturned
  io.launchReady := launchReady
  io.launchEnable := launchReady

  io.blockedByDisabled := !io.enable && io.launchValid
  io.blockedByNoCandidate := io.enable && !io.launchValid
  io.blockedByBaseLookup := candidateValid && !io.baseLookupGranted
  io.blockedByBaseData := candidateValid && io.baseLookupGranted && !io.baseDataReturned
  io.blockedByScb := candidateValid && baseDataReady && !io.scbReturned
  io.blockedByReturn := candidateValid && sourcesReturned && !io.returnReady
}
