package linxcore.lsu

import chisel3._

class LoadReplaySourceReturnStoreSnapshotRequestControlIO extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val requestEnable = Input(Bool())
  val launchValid = Input(Bool())
  val rawSinkReady = Input(Bool())
  val tokenCanAccept = Input(Bool())

  val active = Output(Bool())
  val requestActive = Output(Bool())
  val queryCandidate = Output(Bool())
  val queryRequestEnable = Output(Bool())
  val querySinkReady = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByRequestDisabled = Output(Bool())
  val blockedByNoLaunch = Output(Bool())
  val blockedBySink = Output(Bool())
  val blockedByToken = Output(Bool())
}

class LoadReplaySourceReturnStoreSnapshotRequestControl extends Module {
  val io = IO(new LoadReplaySourceReturnStoreSnapshotRequestControlIO)

  val active = io.enable && !io.flush
  val rawRequest = io.requestEnable || io.launchValid || io.rawSinkReady
  val requestActive = active && io.requestEnable
  val queryCandidate = active && io.launchValid
  val queryReadyCandidate = requestActive && io.launchValid
  val sinkReady = active && io.requestEnable && io.rawSinkReady && io.tokenCanAccept

  io.active := active
  io.requestActive := requestActive
  io.queryCandidate := queryCandidate
  io.queryRequestEnable := io.requestEnable
  io.querySinkReady := sinkReady
  io.blockedByDisabled := !io.enable && rawRequest
  io.blockedByFlush := io.enable && io.flush && rawRequest
  io.blockedByRequestDisabled := active && io.launchValid && !io.requestEnable
  io.blockedByNoLaunch := requestActive && !io.launchValid
  io.blockedBySink := queryReadyCandidate && !io.rawSinkReady
  io.blockedByToken := queryReadyCandidate && io.rawSinkReady && !io.tokenCanAccept
}
