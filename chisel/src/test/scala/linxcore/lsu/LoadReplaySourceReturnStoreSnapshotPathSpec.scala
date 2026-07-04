package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplaySourceReturnStoreSnapshotPathReference {
  final case class Result(
      storeSnapshotReady: Boolean,
      controlActive: Boolean,
      controlRequestActive: Boolean,
      controlSnapshotEvidenceValid: Boolean,
      controlLegacyReady: Boolean,
      controlLiveReady: Boolean,
      controlBlockedByRequestDisabled: Boolean,
      controlBlockedByLegacySnapshot: Boolean,
      controlBlockedBySnapshot: Boolean,
      evidenceActive: Boolean,
      evidenceRequestValid: Boolean,
      evidenceQueryActive: Boolean,
      evidenceResponseAccepted: Boolean,
      evidenceSnapshotRequired: Boolean,
      evidenceSnapshotValid: Boolean,
      evidenceWaitStoreReplay: Boolean,
      evidenceBlockedByNoQuery: Boolean,
      evidenceBlockedByNoResponse: Boolean,
      evidenceBlockedByWaitStore: Boolean,
      evidenceInvalidResponseWithoutQuery: Boolean,
      evidenceInvalidDataWithWaitStore: Boolean,
      queryIssueActive: Boolean,
      queryIssueRequestActive: Boolean,
      queryIssueCandidate: Boolean,
      queryIssueValid: Boolean,
      queryIssueIssued: Boolean,
      queryIssueBlockedByRequestDisabled: Boolean,
      queryIssueBlockedByNoLaunch: Boolean,
      queryIssueBlockedBySink: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      launchValid: Boolean,
      legacySnapshotReady: Boolean): Result = {
    val queryIssue = LoadReplaySourceReturnStoreSnapshotQueryIssueReference(
      enable = enable,
      flush = flush,
      requestEnable = false,
      launchValid = launchValid,
      sinkReady = false)
    val identityMatch = LoadReplaySourceReturnStoreSnapshotIdentityMatchReference(
      enable = enable,
      flush = flush,
      queryIssued = queryIssue.queryIssued,
      selectedValid = false,
      selectedRepick = false,
      responseValid = false,
      selectedClusterId = 0,
      selectedEntryId = 0,
      responseClusterId = 0,
      responseEntryId = 0)
    val responseMatch = LoadReplaySourceReturnStoreSnapshotResponseMatchReference(
      enable = enable,
      flush = flush,
      queryIssued = queryIssue.queryIssued,
      responseValidIn = false,
      responseMatchesSelected = identityMatch.responseMatchesSelected,
      scbReturned = false,
      waitStoreIn = false,
      dataValidIn = false)
    val evidence = LoadReplaySourceReturnStoreSnapshotEvidenceReference(
      enable = enable,
      flush = flush,
      launchValid = launchValid,
      queryIssued = queryIssue.queryIssued,
      responseValid = responseMatch.responseValid,
      waitStore = responseMatch.waitStore,
      dataValid = responseMatch.dataValid)
    val control = LoadReplaySourceReturnStoreSnapshotReadyControlReference(
      enable = enable,
      flush = flush,
      requestEnable = false,
      legacySnapshotReady = legacySnapshotReady,
      snapshotRequired = evidence.snapshotRequired,
      snapshotValid = evidence.snapshotValid)

    Result(
      storeSnapshotReady = control.storeSnapshotReady,
      controlActive = control.active,
      controlRequestActive = control.requestActive,
      controlSnapshotEvidenceValid = control.snapshotEvidenceValid,
      controlLegacyReady = control.legacyReady,
      controlLiveReady = control.liveReady,
      controlBlockedByRequestDisabled = control.blockedByRequestDisabled,
      controlBlockedByLegacySnapshot = control.blockedByLegacySnapshot,
      controlBlockedBySnapshot = control.blockedBySnapshot,
      evidenceActive = evidence.active,
      evidenceRequestValid = evidence.requestValid,
      evidenceQueryActive = evidence.queryActive,
      evidenceResponseAccepted = evidence.responseAccepted,
      evidenceSnapshotRequired = evidence.snapshotRequired,
      evidenceSnapshotValid = evidence.snapshotValid,
      evidenceWaitStoreReplay = evidence.waitStoreReplay,
      evidenceBlockedByNoQuery = evidence.blockedByNoQuery,
      evidenceBlockedByNoResponse = evidence.blockedByNoResponse,
      evidenceBlockedByWaitStore = evidence.blockedByWaitStore,
      evidenceInvalidResponseWithoutQuery = evidence.invalidResponseWithoutQuery,
      evidenceInvalidDataWithWaitStore = evidence.invalidDataWithWaitStore,
      queryIssueActive = queryIssue.active,
      queryIssueRequestActive = queryIssue.requestActive,
      queryIssueCandidate = queryIssue.queryCandidate,
      queryIssueValid = queryIssue.queryValid,
      queryIssueIssued = queryIssue.queryIssued,
      queryIssueBlockedByRequestDisabled = queryIssue.blockedByRequestDisabled,
      queryIssueBlockedByNoLaunch = queryIssue.blockedByNoLaunch,
      queryIssueBlockedBySink = queryIssue.blockedBySink)
  }
}

class LoadReplaySourceReturnStoreSnapshotPathSpec extends AnyFunSuite {
  import LoadReplaySourceReturnStoreSnapshotPathReference._

  test("current reduced path preserves legacy snapshot readiness") {
    val ready = LoadReplaySourceReturnStoreSnapshotPathReference(
      enable = true,
      flush = false,
      launchValid = true,
      legacySnapshotReady = true)
    val blocked = LoadReplaySourceReturnStoreSnapshotPathReference(
      enable = true,
      flush = false,
      launchValid = true,
      legacySnapshotReady = false)

    assert(ready.storeSnapshotReady)
    assert(ready.controlActive)
    assert(!ready.controlRequestActive)
    assert(ready.controlSnapshotEvidenceValid)
    assert(ready.controlBlockedByRequestDisabled)
    assert(ready.queryIssueCandidate)
    assert(!ready.queryIssueIssued)
    assert(ready.evidenceRequestValid)
    assert(ready.evidenceBlockedByNoQuery)
    assert(!blocked.storeSnapshotReady)
    assert(blocked.controlBlockedByLegacySnapshot)
  }

  test("dormant response side keeps live response and query issue disabled") {
    val result = LoadReplaySourceReturnStoreSnapshotPathReference(
      enable = true,
      flush = false,
      launchValid = true,
      legacySnapshotReady = true)

    assert(!result.queryIssueValid)
    assert(!result.queryIssueIssued)
    assert(result.queryIssueBlockedByRequestDisabled)
    assert(!result.evidenceQueryActive)
    assert(!result.evidenceResponseAccepted)
    assert(!result.evidenceSnapshotValid)
    assert(!result.evidenceWaitStoreReplay)
    assert(!result.controlLiveReady)
  }

  test("flush suppresses live diagnostics while keeping legacy fallback literal") {
    val result = LoadReplaySourceReturnStoreSnapshotPathReference(
      enable = true,
      flush = true,
      launchValid = true,
      legacySnapshotReady = true)

    assert(result.storeSnapshotReady)
    assert(!result.controlActive)
    assert(!result.controlSnapshotEvidenceValid)
    assert(!result.queryIssueActive)
    assert(!result.queryIssueCandidate)
    assert(!result.evidenceActive)
    assert(!result.evidenceRequestValid)
  }

  test("disabled reduced path produces no live request diagnostics") {
    val result = LoadReplaySourceReturnStoreSnapshotPathReference(
      enable = false,
      flush = false,
      launchValid = true,
      legacySnapshotReady = false)

    assert(!result.storeSnapshotReady)
    assert(!result.controlActive)
    assert(!result.queryIssueActive)
    assert(!result.evidenceActive)
    assert(!result.evidenceSnapshotRequired)
  }

  test("Chisel LoadReplaySourceReturnStoreSnapshotPath elaborates the composed owner") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplaySourceReturnStoreSnapshotPath)

    assert(sv.contains("module LoadReplaySourceReturnStoreSnapshotPath"))
    assert(sv.contains("LoadReplaySourceReturnStoreSnapshotIdentityMatch"))
    assert(sv.contains("LoadReplaySourceReturnStoreSnapshotResponseMatch"))
    assert(sv.contains("io_storeSnapshotReady"))
    assert(sv.contains("io_queryIssueCandidate"))
  }
}
