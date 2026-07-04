package linxcore.lsu

import chisel3._

class LoadReplaySourceReturnStoreSnapshotQueryIssueIO extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val requestEnable = Input(Bool())
  val launchValid = Input(Bool())
  val sinkReady = Input(Bool())

  val active = Output(Bool())
  val requestActive = Output(Bool())
  val queryCandidate = Output(Bool())
  val queryValid = Output(Bool())
  val queryIssued = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByRequestDisabled = Output(Bool())
  val blockedByNoLaunch = Output(Bool())
  val blockedBySink = Output(Bool())
}

class LoadReplaySourceReturnStoreSnapshotQueryIssue extends Module {
  val io = IO(new LoadReplaySourceReturnStoreSnapshotQueryIssueIO)

  val active = io.enable && !io.flush
  val rawRequest = io.requestEnable || io.launchValid
  val requestActive = active && io.requestEnable
  val queryCandidate = active && io.launchValid
  val queryValid = requestActive && io.launchValid
  val queryIssued = queryValid && io.sinkReady

  io.active := active
  io.requestActive := requestActive
  io.queryCandidate := queryCandidate
  io.queryValid := queryValid
  io.queryIssued := queryIssued
  io.blockedByDisabled := !io.enable && rawRequest
  io.blockedByFlush := io.enable && io.flush && rawRequest
  io.blockedByRequestDisabled := active && io.launchValid && !io.requestEnable
  io.blockedByNoLaunch := requestActive && !io.launchValid
  io.blockedBySink := queryValid && !io.sinkReady
}
