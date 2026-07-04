package linxcore.lsu

import chisel3._

class LoadReplaySourceReturnStoreSnapshotIdentityMatchIO(
    clusterIdWidth: Int,
    entryIdWidth: Int) extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val queryIssued = Input(Bool())
  val selectedValid = Input(Bool())
  val selectedRepick = Input(Bool())
  val responseValid = Input(Bool())
  val selectedClusterId = Input(UInt(clusterIdWidth.W))
  val selectedEntryId = Input(UInt(entryIdWidth.W))
  val responseClusterId = Input(UInt(clusterIdWidth.W))
  val responseEntryId = Input(UInt(entryIdWidth.W))

  val active = Output(Bool())
  val matchCandidate = Output(Bool())
  val selectedReady = Output(Bool())
  val clusterMatches = Output(Bool())
  val entryMatches = Output(Bool())
  val identityMatches = Output(Bool())
  val responseMatchesSelected = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoQuery = Output(Bool())
  val blockedByNoSelected = Output(Bool())
  val blockedByStaleRow = Output(Bool())
  val blockedByClusterMismatch = Output(Bool())
  val blockedByEntryMismatch = Output(Bool())
  val invalidResponseWithoutQuery = Output(Bool())
}

class LoadReplaySourceReturnStoreSnapshotIdentityMatch(
    clusterIdWidth: Int = 2,
    entryIdWidth: Int = 4) extends Module {
  require(clusterIdWidth > 0, "clusterIdWidth must be positive")
  require(entryIdWidth > 0, "entryIdWidth must be positive")

  val io = IO(new LoadReplaySourceReturnStoreSnapshotIdentityMatchIO(
    clusterIdWidth = clusterIdWidth,
    entryIdWidth = entryIdWidth
  ))

  val active = io.enable && !io.flush
  val matchCandidate = active && io.responseValid
  val selectedReady = active && io.selectedValid && io.selectedRepick
  val responseHasQuery = matchCandidate && io.queryIssued
  val responseHasSelected = responseHasQuery && io.selectedValid
  val responseHasLiveSelected = responseHasSelected && io.selectedRepick
  val clusterMatches = io.selectedClusterId === io.responseClusterId
  val entryMatches = io.selectedEntryId === io.responseEntryId
  val identityMatches = clusterMatches && entryMatches

  io.active := active
  io.matchCandidate := matchCandidate
  io.selectedReady := selectedReady
  io.clusterMatches := clusterMatches
  io.entryMatches := entryMatches
  io.identityMatches := identityMatches
  io.responseMatchesSelected := responseHasLiveSelected && identityMatches
  io.blockedByDisabled := !io.enable && io.responseValid
  io.blockedByFlush := io.enable && io.flush && io.responseValid
  io.blockedByNoQuery := matchCandidate && !io.queryIssued
  io.blockedByNoSelected := responseHasQuery && !io.selectedValid
  io.blockedByStaleRow := responseHasSelected && !io.selectedRepick
  io.blockedByClusterMismatch := responseHasLiveSelected && !clusterMatches
  io.blockedByEntryMismatch := responseHasLiveSelected && clusterMatches && !entryMatches
  io.invalidResponseWithoutQuery := active && io.responseValid && !io.queryIssued
}
