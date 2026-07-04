package linxcore.lsu

import chisel3._

class LoadReplaySourceReturnStoreSnapshotEvidenceIO extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val launchValid = Input(Bool())
  val queryIssued = Input(Bool())
  val responseValid = Input(Bool())
  val waitStore = Input(Bool())
  val dataValid = Input(Bool())

  val active = Output(Bool())
  val requestValid = Output(Bool())
  val queryActive = Output(Bool())
  val responseAccepted = Output(Bool())
  val snapshotRequired = Output(Bool())
  val snapshotValid = Output(Bool())
  val waitStoreReplay = Output(Bool())
  val mergeDataPresent = Output(Bool())
  val noDataReturn = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoLaunch = Output(Bool())
  val blockedByNoQuery = Output(Bool())
  val blockedByNoResponse = Output(Bool())
  val blockedByWaitStore = Output(Bool())
  val invalidResponseWithoutQuery = Output(Bool())
  val invalidWaitStoreWithoutResponse = Output(Bool())
  val invalidDataWithWaitStore = Output(Bool())
}

class LoadReplaySourceReturnStoreSnapshotEvidence extends Module {
  val io = IO(new LoadReplaySourceReturnStoreSnapshotEvidenceIO)

  val active = io.enable && !io.flush
  val rawEvidence =
    io.launchValid || io.queryIssued || io.responseValid || io.waitStore || io.dataValid
  val requestValid = active && io.launchValid
  val queryActive = requestValid && io.queryIssued
  val responseAccepted = queryActive && io.responseValid
  val waitStoreReplay = responseAccepted && io.waitStore
  val snapshotValid = responseAccepted && !io.waitStore

  io.active := active
  io.requestValid := requestValid
  io.queryActive := queryActive
  io.responseAccepted := responseAccepted
  io.snapshotRequired := requestValid
  io.snapshotValid := snapshotValid
  io.waitStoreReplay := waitStoreReplay
  io.mergeDataPresent := snapshotValid && io.dataValid
  io.noDataReturn := snapshotValid && !io.dataValid
  io.blockedByDisabled := !io.enable && rawEvidence
  io.blockedByFlush := io.enable && io.flush && rawEvidence
  io.blockedByNoLaunch := active && !io.launchValid && rawEvidence
  io.blockedByNoQuery := requestValid && !io.queryIssued
  io.blockedByNoResponse := queryActive && !io.responseValid
  io.blockedByWaitStore := waitStoreReplay
  io.invalidResponseWithoutQuery := active && io.responseValid && !io.queryIssued
  io.invalidWaitStoreWithoutResponse := active && io.waitStore && !io.responseValid
  io.invalidDataWithWaitStore := responseAccepted && io.waitStore && io.dataValid
}
