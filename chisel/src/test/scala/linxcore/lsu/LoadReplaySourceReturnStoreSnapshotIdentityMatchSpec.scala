package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplaySourceReturnStoreSnapshotIdentityMatchReference {
  final case class Result(
      active: Boolean,
      matchCandidate: Boolean,
      selectedReady: Boolean,
      clusterMatches: Boolean,
      entryMatches: Boolean,
      identityMatches: Boolean,
      responseMatchesSelected: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByNoQuery: Boolean,
      blockedByNoSelected: Boolean,
      blockedByStaleRow: Boolean,
      blockedByClusterMismatch: Boolean,
      blockedByEntryMismatch: Boolean,
      invalidResponseWithoutQuery: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      queryIssued: Boolean,
      selectedValid: Boolean,
      selectedRepick: Boolean,
      responseValid: Boolean,
      selectedClusterId: Int,
      selectedEntryId: Int,
      responseClusterId: Int,
      responseEntryId: Int): Result = {
    val active = enable && !flush
    val matchCandidate = active && responseValid
    val selectedReady = active && selectedValid && selectedRepick
    val responseHasQuery = matchCandidate && queryIssued
    val responseHasSelected = responseHasQuery && selectedValid
    val responseHasLiveSelected = responseHasSelected && selectedRepick
    val clusterMatches = selectedClusterId == responseClusterId
    val entryMatches = selectedEntryId == responseEntryId
    val identityMatches = clusterMatches && entryMatches

    Result(
      active = active,
      matchCandidate = matchCandidate,
      selectedReady = selectedReady,
      clusterMatches = clusterMatches,
      entryMatches = entryMatches,
      identityMatches = identityMatches,
      responseMatchesSelected = responseHasLiveSelected && identityMatches,
      blockedByDisabled = !enable && responseValid,
      blockedByFlush = enable && flush && responseValid,
      blockedByNoQuery = matchCandidate && !queryIssued,
      blockedByNoSelected = responseHasQuery && !selectedValid,
      blockedByStaleRow = responseHasSelected && !selectedRepick,
      blockedByClusterMismatch = responseHasLiveSelected && !clusterMatches,
      blockedByEntryMismatch = responseHasLiveSelected && clusterMatches && !entryMatches,
      invalidResponseWithoutQuery = active && responseValid && !queryIssued)
  }
}

class LoadReplaySourceReturnStoreSnapshotIdentityMatchSpec extends AnyFunSuite {
  import LoadReplaySourceReturnStoreSnapshotIdentityMatchReference._

  test("inactive raw response exposes disabled and flush blockers") {
    val disabled = LoadReplaySourceReturnStoreSnapshotIdentityMatchReference(
      enable = false,
      flush = false,
      queryIssued = true,
      selectedValid = true,
      selectedRepick = true,
      responseValid = true,
      selectedClusterId = 1,
      selectedEntryId = 3,
      responseClusterId = 1,
      responseEntryId = 3)
    val flushed = LoadReplaySourceReturnStoreSnapshotIdentityMatchReference(
      enable = true,
      flush = true,
      queryIssued = true,
      selectedValid = true,
      selectedRepick = true,
      responseValid = true,
      selectedClusterId = 1,
      selectedEntryId = 3,
      responseClusterId = 1,
      responseEntryId = 3)

    assert(disabled.blockedByDisabled)
    assert(!disabled.responseMatchesSelected)
    assert(flushed.blockedByFlush)
    assert(!flushed.responseMatchesSelected)
  }

  test("response waits for an issued query before identity can match") {
    val result = LoadReplaySourceReturnStoreSnapshotIdentityMatchReference(
      enable = true,
      flush = false,
      queryIssued = false,
      selectedValid = true,
      selectedRepick = true,
      responseValid = true,
      selectedClusterId = 1,
      selectedEntryId = 3,
      responseClusterId = 1,
      responseEntryId = 3)

    assert(result.matchCandidate)
    assert(result.identityMatches)
    assert(!result.responseMatchesSelected)
    assert(result.blockedByNoQuery)
    assert(result.invalidResponseWithoutQuery)
  }

  test("response without a selected row is not matched") {
    val result = LoadReplaySourceReturnStoreSnapshotIdentityMatchReference(
      enable = true,
      flush = false,
      queryIssued = true,
      selectedValid = false,
      selectedRepick = true,
      responseValid = true,
      selectedClusterId = 1,
      selectedEntryId = 3,
      responseClusterId = 1,
      responseEntryId = 3)

    assert(!result.selectedReady)
    assert(!result.responseMatchesSelected)
    assert(result.blockedByNoSelected)
  }

  test("stale selected row is rejected before identity match") {
    val result = LoadReplaySourceReturnStoreSnapshotIdentityMatchReference(
      enable = true,
      flush = false,
      queryIssued = true,
      selectedValid = true,
      selectedRepick = false,
      responseValid = true,
      selectedClusterId = 1,
      selectedEntryId = 3,
      responseClusterId = 1,
      responseEntryId = 3)

    assert(!result.selectedReady)
    assert(result.identityMatches)
    assert(!result.responseMatchesSelected)
    assert(result.blockedByStaleRow)
  }

  test("cluster and entry mismatches are diagnosed separately") {
    val cluster = LoadReplaySourceReturnStoreSnapshotIdentityMatchReference(
      enable = true,
      flush = false,
      queryIssued = true,
      selectedValid = true,
      selectedRepick = true,
      responseValid = true,
      selectedClusterId = 1,
      selectedEntryId = 3,
      responseClusterId = 2,
      responseEntryId = 3)
    val entry = LoadReplaySourceReturnStoreSnapshotIdentityMatchReference(
      enable = true,
      flush = false,
      queryIssued = true,
      selectedValid = true,
      selectedRepick = true,
      responseValid = true,
      selectedClusterId = 1,
      selectedEntryId = 3,
      responseClusterId = 1,
      responseEntryId = 4)

    assert(!cluster.responseMatchesSelected)
    assert(cluster.blockedByClusterMismatch)
    assert(!entry.responseMatchesSelected)
    assert(entry.blockedByEntryMismatch)
  }

  test("matching active repick row accepts response identity") {
    val result = LoadReplaySourceReturnStoreSnapshotIdentityMatchReference(
      enable = true,
      flush = false,
      queryIssued = true,
      selectedValid = true,
      selectedRepick = true,
      responseValid = true,
      selectedClusterId = 1,
      selectedEntryId = 3,
      responseClusterId = 1,
      responseEntryId = 3)

    assert(result.selectedReady)
    assert(result.identityMatches)
    assert(result.responseMatchesSelected)
    assert(!result.blockedByClusterMismatch)
    assert(!result.blockedByEntryMismatch)
  }

  test("Chisel LoadReplaySourceReturnStoreSnapshotIdentityMatch elaborates diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(
      new LoadReplaySourceReturnStoreSnapshotIdentityMatch(
        clusterIdWidth = 3,
        entryIdWidth = 5
      ))

    assert(sv.contains("module LoadReplaySourceReturnStoreSnapshotIdentityMatch"))
    assert(sv.contains("io_responseMatchesSelected"))
    assert(sv.contains("io_blockedByStaleRow"))
    assert(sv.contains("io_blockedByEntryMismatch"))
  }
}
