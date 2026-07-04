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
      queryIssueBlockedBySink: Boolean,
      requestPayloadValid: Boolean,
      requestPayloadBlockedByNoIssue: Boolean,
      requestPayloadBlockedByNoSelected: Boolean,
      requestPayloadBlockedByStaleRow: Boolean,
      requestQueueEnqueueReady: Boolean,
      requestQueueEnqueueAccepted: Boolean,
      requestQueueEnqueueDropped: Boolean,
      requestQueueHeadValid: Boolean,
      requestQueueHeadConsumed: Boolean,
      requestQueuePending: Boolean,
      requestQueueFull: Boolean,
      requestQueueEmpty: Boolean,
      requestQueueCount: Int,
      requestQueueBlockedByDisabled: Boolean,
      requestQueueBlockedByFlush: Boolean,
      requestQueueBlockedByFull: Boolean)

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
      dataValidIn: Boolean = false,
      selectedLoadId: Int = 0,
      selectedBid: Int = 0,
      selectedGid: Int = 0,
      selectedRid: Int = 0,
      selectedLoadLsId: Int = 0,
      selectedPc: BigInt = 0,
      selectedAddr: BigInt = 0,
      selectedSize: Int = 0,
      selectedRequestByteMask: BigInt = 0,
      requestQueueState: Vector[LoadReplaySourceReturnStoreSnapshotRequestPayloadReference.Payload] = Vector.empty): Result = {
    val responseQueueCapacity = LoadReplaySourceReturnStoreSnapshotResponseQueueReference.step(
      state = Vector.empty,
      depth = 2,
      enable = enable,
      flush = flush,
      dequeueReady = false)
    val sinkResponseReady = enable && !flush && !responseQueueCapacity.full && !responseValidIn
    val initialRequestQueueHead = requestQueueState.headOption
    val requestSinkPreview = LoadReplaySourceReturnStoreSnapshotRequestSinkReference(
      enable = enable,
      flush = flush,
      request = initialRequestQueueHead,
      rawSinkReady = sinkReady,
      responseReady = sinkResponseReady)
    val requestQueueCapacity = LoadReplaySourceReturnStoreSnapshotRequestQueueReference.step(
      state = requestQueueState,
      depth = 2,
      enable = enable,
      flush = flush,
      dequeueReady = requestSinkPreview.requestReady)
    val requestControl = LoadReplaySourceReturnStoreSnapshotRequestControlReference(
      enable = enable,
      flush = flush,
      requestEnable = requestEnable,
      launchValid = launchValid,
      rawSinkReady = requestQueueCapacity.enqueueReady,
      tokenCanAccept = true)
    val queryIssue = LoadReplaySourceReturnStoreSnapshotQueryIssueReference(
      enable = enable,
      flush = flush,
      requestEnable = requestControl.queryRequestEnable,
      launchValid = launchValid,
      sinkReady = requestControl.querySinkReady)
    val projectedSelectedIdentity = LoadReplaySourceReturnStoreSnapshotSelectedIdentityReference(
      liqEntries = 4,
      enable = enable && selectedIdentityEnable,
      flush = flush,
      launchValid = launchValid,
      launchIndex = selectedLaunchIndex,
      repickMask = selectedRepickMask)
    val selectedValidResolved =
      if (selectedIdentityEnable) projectedSelectedIdentity.selectedValid else selectedValid
    val selectedRepickResolved =
      if (selectedIdentityEnable) projectedSelectedIdentity.selectedRepick else selectedRepick
    val selectedClusterIdResolved =
      if (selectedIdentityEnable) projectedSelectedIdentity.selectedClusterId else selectedClusterId
    val selectedEntryIdResolved =
      if (selectedIdentityEnable) projectedSelectedIdentity.selectedEntryId else selectedEntryId
    val requestPayload = LoadReplaySourceReturnStoreSnapshotRequestPayloadReference(
      enable = enable,
      flush = flush,
      queryIssued = queryIssue.queryIssued,
      selectedValid = selectedValidResolved,
      selectedRepick = selectedRepickResolved,
      selectedClusterId = selectedClusterIdResolved,
      selectedEntryId = selectedEntryIdResolved,
      selectedLoadId = selectedLoadId,
      selectedBid = selectedBid,
      selectedGid = selectedGid,
      selectedRid = selectedRid,
      selectedLoadLsId = selectedLoadLsId,
      selectedPc = selectedPc,
      selectedAddr = selectedAddr,
      selectedSize = selectedSize,
      selectedRequestByteMask = selectedRequestByteMask)
    val requestQueue = LoadReplaySourceReturnStoreSnapshotRequestQueueReference.step(
      state = requestQueueState,
      depth = 2,
      enable = enable,
      flush = flush,
      enqueue = if (requestPayload.payload.valid) Some(requestPayload.payload) else None,
      dequeueReady = requestSinkPreview.requestReady)
    val requestSink = LoadReplaySourceReturnStoreSnapshotRequestSinkReference(
      enable = enable,
      flush = flush,
      request = requestQueue.head,
      rawSinkReady = sinkReady,
      responseReady = sinkResponseReady)
    val acceptedToken = LoadReplaySourceReturnStoreSnapshotAcceptedTokenReference.step(
      state = LoadReplaySourceReturnStoreSnapshotAcceptedTokenReference.Token(),
      enable = enable,
      flush = flush,
      queryIssued = queryIssue.queryIssued,
      selectedValid = selectedValidResolved,
      selectedRepick = selectedRepickResolved,
      selectedClusterId = selectedClusterIdResolved,
      selectedEntryId = selectedEntryIdResolved,
      responseConsumed = false)
    val sinkResponse =
      if (!responseValidIn && requestSink.responseValid) {
        Some(LoadReplaySourceReturnStoreSnapshotResponseQueueReference.Response(
          clusterId = requestSink.responseClusterId,
          entryId = requestSink.responseEntryId,
          waitStore = requestSink.responseWaitStore,
          dataValid = requestSink.responseDataValid))
      } else {
        None
      }
    val rawResponse =
      if (responseValidIn) {
        Some(LoadReplaySourceReturnStoreSnapshotResponseQueueReference.Response(
          clusterId = responseClusterId,
          entryId = responseEntryId,
          waitStore = waitStoreIn,
          dataValid = dataValidIn))
      } else {
        None
      }
    val responseQueue = LoadReplaySourceReturnStoreSnapshotResponseQueueReference.step(
      state = Vector.empty,
      depth = 2,
      enable = enable,
      flush = flush,
      enqueue = rawResponse.orElse(sinkResponse),
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
      queryIssueBlockedBySink = queryIssue.blockedBySink,
      requestPayloadValid = requestPayload.payload.valid,
      requestPayloadBlockedByNoIssue = requestPayload.blockedByNoIssue,
      requestPayloadBlockedByNoSelected = requestPayload.blockedByNoSelected,
      requestPayloadBlockedByStaleRow = requestPayload.blockedByStaleRow,
      requestQueueEnqueueReady = requestQueue.enqueueReady,
      requestQueueEnqueueAccepted = requestQueue.enqueueAccepted,
      requestQueueEnqueueDropped = requestQueue.enqueueDropped,
      requestQueueHeadValid = requestQueue.headValid,
      requestQueueHeadConsumed = requestQueue.headConsumed,
      requestQueuePending = requestQueue.pending,
      requestQueueFull = requestQueue.full,
      requestQueueEmpty = requestQueue.empty,
      requestQueueCount = requestQueue.count,
      requestQueueBlockedByDisabled = requestQueue.blockedByDisabled,
      requestQueueBlockedByFlush = requestQueue.blockedByFlush,
      requestQueueBlockedByFull = requestQueue.blockedByFull)
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
    assert(!result.requestPayloadValid)
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
      responseValidIn = false,
      scbReturned = true,
      selectedLoadId = 2,
      selectedBid = 6,
      selectedGid = 1,
      selectedRid = 7,
      selectedLoadLsId = 9,
      selectedPc = BigInt("400055f2", 16),
      selectedAddr = BigInt("40012040", 16),
      selectedSize = 8,
      selectedRequestByteMask = BigInt("ff", 16) << 8)

    assert(result.queryIssueIssued)
    assert(result.requestPayloadValid)
    assert(result.requestQueueEnqueueAccepted)
    assert(result.requestQueueHeadConsumed)
    assert(result.requestQueueEmpty)
    assert(result.evidenceResponseAccepted)
    assert(result.evidenceSnapshotRequired)
    assert(result.evidenceSnapshotValid)
    assert(result.controlLiveReady)
    assert(result.storeSnapshotReady)
    assert(!result.controlBlockedBySnapshot)
  }

  test("raw response priority keeps request head resident when the response enqueue port is occupied") {
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
      selectedLoadId = 2,
      selectedBid = 6,
      selectedGid = 1,
      selectedRid = 7,
      selectedLoadLsId = 9,
      selectedPc = BigInt("400055f2", 16),
      selectedAddr = BigInt("40012040", 16),
      selectedSize = 8,
      selectedRequestByteMask = BigInt("ff", 16) << 8)

    assert(result.queryIssueIssued)
    assert(result.requestPayloadValid)
    assert(result.requestQueueEnqueueAccepted)
    assert(!result.requestQueueHeadConsumed)
    assert(result.requestQueuePending)
    assert(result.evidenceResponseAccepted)
    assert(result.storeSnapshotReady)
  }

  test("request queue stores issued payload while future raw sink is stalled") {
    val result = LoadReplaySourceReturnStoreSnapshotPathReference(
      enable = true,
      flush = false,
      launchValid = true,
      legacySnapshotReady = false,
      requestEnable = true,
      sinkReady = false,
      selectedIdentityEnable = true,
      selectedLaunchIndex = 1,
      selectedRepickMask = 0x2,
      selectedLoadId = 1,
      selectedBid = 5,
      selectedGid = 1,
      selectedRid = 6,
      selectedLoadLsId = 7,
      selectedPc = BigInt("400055e8", 16),
      selectedAddr = BigInt("40012020", 16),
      selectedSize = 8,
      selectedRequestByteMask = BigInt("ff", 16) << 4)

    assert(result.queryIssueIssued)
    assert(!result.queryIssueBlockedBySink)
    assert(result.requestPayloadValid)
    assert(result.requestQueueEnqueueReady)
    assert(result.requestQueueEnqueueAccepted)
    assert(result.requestQueueHeadValid)
    assert(!result.requestQueueHeadConsumed)
    assert(result.requestQueuePending)
    assert(result.requestQueueCount == 1)
    assert(!result.evidenceResponseAccepted)
    assert(!result.storeSnapshotReady)
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
    assert(sv.contains("LoadReplaySourceReturnStoreSnapshotRequestControl"))
    assert(sv.contains("LoadReplaySourceReturnStoreSnapshotRequestPayload"))
    assert(sv.contains("LoadReplaySourceReturnStoreSnapshotRequestQueue"))
    assert(sv.contains("LoadReplaySourceReturnStoreSnapshotRequestSink"))
    assert(sv.contains("LoadReplaySourceReturnStoreSnapshotLookup"))
    assert(sv.contains("LoadReplaySourceReturnStoreSnapshotSelectedIdentity"))
    assert(sv.contains("LoadReplaySourceReturnStoreSnapshotAcceptedToken"))
    assert(sv.contains("LoadReplaySourceReturnStoreSnapshotResponseQueue"))
    assert(sv.contains("LoadReplaySourceReturnStoreSnapshotResponseHeadState"))
    assert(sv.contains("LoadReplaySourceReturnStoreSnapshotResponseDrain"))
    assert(sv.contains("LoadReplaySourceReturnStoreSnapshotResponseApply"))
    assert(sv.contains("LoadReplaySourceReturnStoreSnapshotRowStatePlan"))
    assert(sv.contains("LoadReplaySourceReturnStoreSnapshotRowMutationRequest"))
    assert(sv.contains("LoadReplaySourceReturnStoreSnapshotIdentityMatch"))
    assert(sv.contains("LoadReplaySourceReturnStoreSnapshotResponseMatch"))
    assert(sv.contains("io_storeSnapshotReady"))
    assert(sv.contains("io_selectedLaunchIndex"))
    assert(sv.contains("io_requestPayload_requestByteMask"))
    assert(sv.contains("io_requestQueueHead_requestByteMask"))
    assert(sv.contains("io_lookupResponseDataValid"))
    assert(sv.contains("io_selectedLineData"))
    assert(sv.contains("tokenLineDataReg"))
    assert(sv.contains("io_responseApplyValid"))
    assert(sv.contains("io_responseApplyWaitStoreRid_value"))
    assert(sv.contains("io_rowStatePlanNextStqReturned"))
    assert(sv.contains("io_rowStatePlanInvalidStqApplyWithoutScb"))
    assert(sv.contains("io_rowMutationCandidateTargetMask"))
    assert(sv.contains("io_rowMutationBlockedByLiveDisabled"))
    assert(sv.contains("io_queryIssueCandidate"))
  }
}
