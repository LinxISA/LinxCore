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
      legacySnapshotReady: Boolean,
      requestEnable: Boolean = false,
      sinkReady: Boolean = false,
      selectedIdentityEnable: Boolean = false,
      selectedLaunchIndex: Int = 0,
      selectedRepickMask: Int = 0,
      selectedValid: Boolean = false,
      selectedRepick: Boolean = false,
      responseValidIn: Boolean = false,
      selectedClusterId: Int = 0,
      selectedEntryId: Int = 0,
      responseClusterId: Int = 0,
      responseEntryId: Int = 0,
      responseHeadStale: Boolean = false,
      scbReturned: Boolean = false,
      waitStoreIn: Boolean = false,
      dataValidIn: Boolean = false): Result = {
    val queryIssue = LoadReplaySourceReturnStoreSnapshotQueryIssueReference(
      enable = enable,
      flush = flush,
      requestEnable = requestEnable,
      launchValid = launchValid,
      sinkReady = sinkReady)
    val projectedSelectedIdentity = LoadReplaySourceReturnStoreSnapshotSelectedIdentityReference(
      liqEntries = 4,
      enable = enable && selectedIdentityEnable,
      flush = flush,
      launchValid = launchValid,
      launchIndex = selectedLaunchIndex,
      repickMask = selectedRepickMask)
    val acceptedToken = LoadReplaySourceReturnStoreSnapshotAcceptedTokenReference.step(
      state = LoadReplaySourceReturnStoreSnapshotAcceptedTokenReference.Token(),
      enable = enable,
      flush = flush,
      queryIssued = queryIssue.queryIssued,
      selectedValid = if (selectedIdentityEnable) projectedSelectedIdentity.selectedValid else selectedValid,
      selectedRepick = if (selectedIdentityEnable) projectedSelectedIdentity.selectedRepick else selectedRepick,
      selectedClusterId = if (selectedIdentityEnable) projectedSelectedIdentity.selectedClusterId else selectedClusterId,
      selectedEntryId = if (selectedIdentityEnable) projectedSelectedIdentity.selectedEntryId else selectedEntryId,
      responseConsumed = false)
    val responseQueue = LoadReplaySourceReturnStoreSnapshotResponseQueueReference.step(
      state = Vector.empty,
      depth = 2,
      enable = enable,
      flush = flush,
      enqueue =
        if (responseValidIn) {
          Some(LoadReplaySourceReturnStoreSnapshotResponseQueueReference.Response(
            clusterId = responseClusterId,
            entryId = responseEntryId,
            waitStore = waitStoreIn,
            dataValid = dataValidIn))
        } else {
          None
        },
      dequeueReady = false)
    val identityMatch = LoadReplaySourceReturnStoreSnapshotIdentityMatchReference(
      enable = enable,
      flush = flush,
      queryIssued = acceptedToken.token.valid,
      selectedValid = acceptedToken.token.valid,
      selectedRepick = acceptedToken.token.repick,
      responseValid = responseQueue.headValid,
      selectedClusterId = acceptedToken.token.clusterId,
      selectedEntryId = acceptedToken.token.entryId,
      responseClusterId = responseQueue.head.map(_.clusterId).getOrElse(0),
      responseEntryId = responseQueue.head.map(_.entryId).getOrElse(0))
    val responseMatch = LoadReplaySourceReturnStoreSnapshotResponseMatchReference(
      enable = enable,
      flush = flush,
      queryIssued = acceptedToken.token.valid,
      responseValidIn = responseQueue.headValid,
      responseMatchesSelected = identityMatch.responseMatchesSelected,
      scbReturned = scbReturned,
      waitStoreIn = responseQueue.head.exists(_.waitStore),
      dataValidIn = responseQueue.head.exists(_.dataValid))
    val evidence = LoadReplaySourceReturnStoreSnapshotEvidenceReference(
      enable = enable,
      flush = flush,
      launchValid = launchValid,
      queryIssued = acceptedToken.token.valid,
      responseValid = responseMatch.responseValid,
      waitStore = responseMatch.waitStore,
      dataValid = responseMatch.dataValid)
    val control = LoadReplaySourceReturnStoreSnapshotReadyControlReference(
      enable = enable,
      flush = flush,
      requestEnable = requestEnable,
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

  test("projected selected identity can complete a future live STQ snapshot") {
    val result = LoadReplaySourceReturnStoreSnapshotPathReference(
      enable = true,
      flush = false,
      launchValid = true,
      legacySnapshotReady = false,
      requestEnable = true,
      sinkReady = true,
      selectedIdentityEnable = true,
      selectedLaunchIndex = 2,
      selectedRepickMask = 0x4,
      responseValidIn = true,
      responseClusterId = 0,
      responseEntryId = 2,
      scbReturned = true,
      waitStoreIn = false,
      dataValidIn = true)

    assert(result.queryIssueIssued)
    assert(result.evidenceResponseAccepted)
    assert(result.evidenceSnapshotRequired)
    assert(result.evidenceSnapshotValid)
    assert(result.controlLiveReady)
    assert(result.storeSnapshotReady)
    assert(!result.controlBlockedBySnapshot)
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
    assert(sv.contains("LoadReplaySourceReturnStoreSnapshotSelectedIdentity"))
    assert(sv.contains("LoadReplaySourceReturnStoreSnapshotAcceptedToken"))
    assert(sv.contains("LoadReplaySourceReturnStoreSnapshotResponseQueue"))
    assert(sv.contains("LoadReplaySourceReturnStoreSnapshotResponseDrain"))
    assert(sv.contains("LoadReplaySourceReturnStoreSnapshotIdentityMatch"))
    assert(sv.contains("LoadReplaySourceReturnStoreSnapshotResponseMatch"))
    assert(sv.contains("io_storeSnapshotReady"))
    assert(sv.contains("io_selectedLaunchIndex"))
    assert(sv.contains("io_queryIssueCandidate"))
  }
}
