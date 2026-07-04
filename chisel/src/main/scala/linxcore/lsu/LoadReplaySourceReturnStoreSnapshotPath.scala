package linxcore.lsu

import chisel3._

class LoadReplaySourceReturnStoreSnapshotPathIO(
    clusterIdWidth: Int,
    entryIdWidth: Int) extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val requestEnable = Input(Bool())
  val launchValid = Input(Bool())
  val sinkReady = Input(Bool())
  val selectedValid = Input(Bool())
  val selectedRepick = Input(Bool())
  val responseValidIn = Input(Bool())
  val selectedClusterId = Input(UInt(clusterIdWidth.W))
  val selectedEntryId = Input(UInt(entryIdWidth.W))
  val responseClusterId = Input(UInt(clusterIdWidth.W))
  val responseEntryId = Input(UInt(entryIdWidth.W))
  val scbReturned = Input(Bool())
  val waitStoreIn = Input(Bool())
  val dataValidIn = Input(Bool())
  val legacySnapshotReady = Input(Bool())

  val storeSnapshotReady = Output(Bool())
  val controlActive = Output(Bool())
  val controlRequestActive = Output(Bool())
  val controlSnapshotEvidenceValid = Output(Bool())
  val controlLegacyReady = Output(Bool())
  val controlLiveReady = Output(Bool())
  val controlBlockedByRequestDisabled = Output(Bool())
  val controlBlockedByLegacySnapshot = Output(Bool())
  val controlBlockedBySnapshot = Output(Bool())

  val evidenceActive = Output(Bool())
  val evidenceRequestValid = Output(Bool())
  val evidenceQueryActive = Output(Bool())
  val evidenceResponseAccepted = Output(Bool())
  val evidenceSnapshotRequired = Output(Bool())
  val evidenceSnapshotValid = Output(Bool())
  val evidenceWaitStoreReplay = Output(Bool())
  val evidenceBlockedByNoQuery = Output(Bool())
  val evidenceBlockedByNoResponse = Output(Bool())
  val evidenceBlockedByWaitStore = Output(Bool())
  val evidenceInvalidResponseWithoutQuery = Output(Bool())
  val evidenceInvalidDataWithWaitStore = Output(Bool())

  val queryIssueActive = Output(Bool())
  val queryIssueRequestActive = Output(Bool())
  val queryIssueCandidate = Output(Bool())
  val queryIssueValid = Output(Bool())
  val queryIssueIssued = Output(Bool())
  val queryIssueBlockedByRequestDisabled = Output(Bool())
  val queryIssueBlockedByNoLaunch = Output(Bool())
  val queryIssueBlockedBySink = Output(Bool())
}

class LoadReplaySourceReturnStoreSnapshotPath(
    clusterIdWidth: Int = 2,
    entryIdWidth: Int = 4) extends Module {
  require(clusterIdWidth > 0, "clusterIdWidth must be positive")
  require(entryIdWidth > 0, "entryIdWidth must be positive")

  val io = IO(new LoadReplaySourceReturnStoreSnapshotPathIO(
    clusterIdWidth = clusterIdWidth,
    entryIdWidth = entryIdWidth
  ))

  val queryIssue = Module(new LoadReplaySourceReturnStoreSnapshotQueryIssue)
  val identityMatch = Module(new LoadReplaySourceReturnStoreSnapshotIdentityMatch(
    clusterIdWidth = clusterIdWidth,
    entryIdWidth = entryIdWidth
  ))
  val responseMatch = Module(new LoadReplaySourceReturnStoreSnapshotResponseMatch)
  val evidence = Module(new LoadReplaySourceReturnStoreSnapshotEvidence)
  val control = Module(new LoadReplaySourceReturnStoreSnapshotReadyControl)

  queryIssue.io.enable := io.enable
  queryIssue.io.flush := io.flush
  queryIssue.io.requestEnable := io.requestEnable
  queryIssue.io.launchValid := io.launchValid
  queryIssue.io.sinkReady := io.sinkReady

  identityMatch.io.enable := io.enable
  identityMatch.io.flush := io.flush
  identityMatch.io.queryIssued := queryIssue.io.queryIssued
  identityMatch.io.selectedValid := io.selectedValid
  identityMatch.io.selectedRepick := io.selectedRepick
  identityMatch.io.responseValid := io.responseValidIn
  identityMatch.io.selectedClusterId := io.selectedClusterId
  identityMatch.io.selectedEntryId := io.selectedEntryId
  identityMatch.io.responseClusterId := io.responseClusterId
  identityMatch.io.responseEntryId := io.responseEntryId

  responseMatch.io.enable := io.enable
  responseMatch.io.flush := io.flush
  responseMatch.io.queryIssued := queryIssue.io.queryIssued
  responseMatch.io.responseValidIn := io.responseValidIn
  responseMatch.io.responseMatchesSelected := identityMatch.io.responseMatchesSelected
  responseMatch.io.scbReturned := io.scbReturned
  responseMatch.io.waitStoreIn := io.waitStoreIn
  responseMatch.io.dataValidIn := io.dataValidIn

  evidence.io.enable := io.enable
  evidence.io.flush := io.flush
  evidence.io.launchValid := io.launchValid
  evidence.io.queryIssued := queryIssue.io.queryIssued
  evidence.io.responseValid := responseMatch.io.responseValid
  evidence.io.waitStore := responseMatch.io.waitStore
  evidence.io.dataValid := responseMatch.io.dataValid

  control.io.enable := io.enable
  control.io.flush := io.flush
  control.io.requestEnable := io.requestEnable
  control.io.legacySnapshotReady := io.legacySnapshotReady
  control.io.snapshotRequired := evidence.io.snapshotRequired
  control.io.snapshotValid := evidence.io.snapshotValid

  io.storeSnapshotReady := control.io.storeSnapshotReady
  io.controlActive := control.io.active
  io.controlRequestActive := control.io.requestActive
  io.controlSnapshotEvidenceValid := control.io.snapshotEvidenceValid
  io.controlLegacyReady := control.io.legacyReady
  io.controlLiveReady := control.io.liveReady
  io.controlBlockedByRequestDisabled := control.io.blockedByRequestDisabled
  io.controlBlockedByLegacySnapshot := control.io.blockedByLegacySnapshot
  io.controlBlockedBySnapshot := control.io.blockedBySnapshot

  io.evidenceActive := evidence.io.active
  io.evidenceRequestValid := evidence.io.requestValid
  io.evidenceQueryActive := evidence.io.queryActive
  io.evidenceResponseAccepted := evidence.io.responseAccepted
  io.evidenceSnapshotRequired := evidence.io.snapshotRequired
  io.evidenceSnapshotValid := evidence.io.snapshotValid
  io.evidenceWaitStoreReplay := evidence.io.waitStoreReplay
  io.evidenceBlockedByNoQuery := evidence.io.blockedByNoQuery
  io.evidenceBlockedByNoResponse := evidence.io.blockedByNoResponse
  io.evidenceBlockedByWaitStore := evidence.io.blockedByWaitStore
  io.evidenceInvalidResponseWithoutQuery := evidence.io.invalidResponseWithoutQuery
  io.evidenceInvalidDataWithWaitStore := evidence.io.invalidDataWithWaitStore

  io.queryIssueActive := queryIssue.io.active
  io.queryIssueRequestActive := queryIssue.io.requestActive
  io.queryIssueCandidate := queryIssue.io.queryCandidate
  io.queryIssueValid := queryIssue.io.queryValid
  io.queryIssueIssued := queryIssue.io.queryIssued
  io.queryIssueBlockedByRequestDisabled := queryIssue.io.blockedByRequestDisabled
  io.queryIssueBlockedByNoLaunch := queryIssue.io.blockedByNoLaunch
  io.queryIssueBlockedBySink := queryIssue.io.blockedBySink
}
