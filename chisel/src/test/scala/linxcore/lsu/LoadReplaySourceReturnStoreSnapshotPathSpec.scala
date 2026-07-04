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
      rawResponseSourceCandidate: Boolean,
      rawResponseSourceValid: Boolean,
      rawResponseSourceBlockedByLiveDisabled: Boolean,
      identityMatchCandidate: Boolean,
      identityMatchSelectedReady: Boolean,
      identityMatchIdentityMatches: Boolean,
      identityMatchResponseMatchesSelected: Boolean,
      identityMatchBlockedByNoQuery: Boolean,
      identityMatchBlockedByNoSelected: Boolean,
      identityMatchBlockedByStaleRow: Boolean,
      identityMatchBlockedByClusterMismatch: Boolean,
      identityMatchBlockedByEntryMismatch: Boolean,
      identityMatchInvalidResponseWithoutQuery: Boolean,
      responseMatchCandidate: Boolean,
      responseMatchMatched: Boolean,
      responseMatchOrdered: Boolean,
      responseMatchValid: Boolean,
      responseMatchWaitStore: Boolean,
      responseMatchDataValid: Boolean,
      responseMatchBlockedByNoQuery: Boolean,
      responseMatchBlockedByNoMatch: Boolean,
      responseMatchBlockedByScbOrder: Boolean,
      responseMatchInvalidResponseWithoutQuery: Boolean,
      responseMatchInvalidDataWithWaitStore: Boolean,
      queryIssueActive: Boolean,
      queryIssueRequestActive: Boolean,
      queryIssueCandidate: Boolean,
      queryIssueValid: Boolean,
      queryIssueIssued: Boolean,
      queryIssueBlockedByRequestDisabled: Boolean,
      queryIssueBlockedByNoLaunch: Boolean,
      queryIssueBlockedBySink: Boolean,
      requestControlBlockedByToken: Boolean,
      acceptedTokenCanAccept: Boolean,
      acceptedTokenValid: Boolean,
      acceptedTokenResidentValid: Boolean,
      acceptedTokenCaptureCandidate: Boolean,
      acceptedTokenCaptureAccepted: Boolean,
      acceptedTokenClearAccepted: Boolean,
      acceptedTokenPrecisePruned: Boolean,
      acceptedTokenBlockedByPreciseFlush: Boolean,
      acceptedTokenBlockedByOutstanding: Boolean,
      acceptedTokenClusterId: Int,
      acceptedTokenEntryId: Int,
      requestPayloadValid: Boolean,
      requestPayloadBlockedByNoIssue: Boolean,
      requestPayloadBlockedByNoSelected: Boolean,
      requestPayloadBlockedByStaleRow: Boolean,
      responseHeadReducedScbReturned: Boolean,
      rowStatePlanValid: Boolean,
      rowStatePlanNextScbReturned: Boolean,
      rowStatePlanNextStoreSourceReturned: Boolean,
      rowStatePlanInvalidStqApplyWithoutScb: Boolean,
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
      requestQueueBlockedByFull: Boolean,
      responseQueueEnqueueReady: Boolean,
      responseQueueEnqueueAccepted: Boolean,
      responseQueueEnqueueDropped: Boolean,
      responseQueueHeadValid: Boolean,
      responseQueueHeadConsumed: Boolean,
      responseQueuePending: Boolean,
      responseQueueFull: Boolean,
      responseQueueEmpty: Boolean,
      responseQueueCount: Int,
      responseQueueBlockedByDisabled: Boolean,
      responseQueueBlockedByFlush: Boolean,
      responseQueueBlockedByFull: Boolean,
      responseDrainDequeueReady: Boolean,
      responseDrainOrderedConsumed: Boolean,
      responseDrainStaleDropped: Boolean,
      responseDrainBlockedByNoHead: Boolean,
      responseDrainBlockedByNoAction: Boolean,
      responseDrainInvalidStaleWithOrdered: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      launchValid: Boolean,
      legacySnapshotReady: Boolean,
      requestEnable: Boolean = false,
      rawResponseLiveEnable: Boolean = false,
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
      responseRequestBid: Int = 0,
      responseRequestGid: Int = 0,
      responseRequestRid: Int = 0,
      responseRequestLoadLsId: Int = 0,
      responseRequestPeId: Int = 0,
      responseRequestStid: Int = 0,
      responseRequestTid: Int = 0,
      responseHeadStale: Boolean = false,
      scbReturned: Boolean = false,
      waitStoreIn: Boolean = false,
      dataValidIn: Boolean = false,
      rawDataValidIn: Boolean = false,
      dataSuppressedByWaitIn: Boolean = false,
      selectedLoadId: Int = 0,
      selectedBid: Int = 0,
      selectedGid: Int = 0,
      selectedRid: Int = 0,
      selectedLoadLsId: Int = 0,
      selectedPeId: Int = 0,
      selectedStid: Int = 0,
      selectedTid: Int = 0,
      selectedPc: BigInt = 0,
      selectedAddr: BigInt = 0,
      selectedSize: Int = 0,
      selectedRequestByteMask: BigInt = 0,
      selectedLineData: BigInt = 0,
      selectedValidMask: BigInt = 0,
      selectedRowValidMask: Int = 0,
      selectedRowScbReturnedMask: Int = 0,
      requestQueueState: Vector[LoadReplaySourceReturnStoreSnapshotRequestPayloadReference.Payload] = Vector.empty,
      responseQueueState: Vector[LoadReplaySourceReturnStoreSnapshotResponseQueueReference.Response] = Vector.empty,
      acceptedTokenState: LoadReplaySourceReturnStoreSnapshotAcceptedTokenReference.Token =
        LoadReplaySourceReturnStoreSnapshotAcceptedTokenReference.Token(),
      preciseFlush: Option[STQFlushPruneReference.Flush] = None): Result = {
    val precisePruneActive = enable && !flush && preciseFlush.exists(_.valid)
    val responseQueueCapacity = LoadReplaySourceReturnStoreSnapshotResponseQueueReference.step(
      state = responseQueueState,
      depth = 2,
      enable = enable,
      flush = flush,
      preciseFlush = preciseFlush,
      dequeueReady = false)
    val rawResponseSource = LoadReplaySourceReturnStoreSnapshotRawResponseSourceReference(
      enable = enable,
      flush = flush,
      liveEnable = rawResponseLiveEnable,
      rawValid = responseValidIn,
      clusterId = responseClusterId,
      entryId = responseEntryId,
      requestBid = responseRequestBid,
      requestGid = responseRequestGid,
      requestRid = responseRequestRid,
      requestLoadLsId = responseRequestLoadLsId,
      requestPeId = responseRequestPeId,
      requestStid = responseRequestStid,
      requestTid = responseRequestTid,
      waitStore = waitStoreIn,
      dataValid = dataValidIn,
      rawDataValid = rawDataValidIn,
      dataSuppressedByWait = dataSuppressedByWaitIn)
    val sinkResponseReady = enable && !flush && !responseQueueCapacity.full && !rawResponseSource.responseValid
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
      preciseFlush = preciseFlush,
      dequeueReady = requestSinkPreview.requestReady)
    val requestControl = LoadReplaySourceReturnStoreSnapshotRequestControlReference(
      enable = enable,
      flush = flush,
      requestEnable = requestEnable,
      launchValid = launchValid,
      rawSinkReady = requestQueueCapacity.enqueueReady,
      tokenCanAccept = enable && !flush && !precisePruneActive && !acceptedTokenState.valid)
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
      selectedPeId = selectedPeId,
      selectedStid = selectedStid,
      selectedTid = selectedTid,
      selectedPc = selectedPc,
      selectedAddr = selectedAddr,
      selectedSize = selectedSize,
      selectedRequestByteMask = selectedRequestByteMask)
    val requestQueue = LoadReplaySourceReturnStoreSnapshotRequestQueueReference.step(
      state = requestQueueState,
      depth = 2,
      enable = enable,
      flush = flush,
      preciseFlush = preciseFlush,
      enqueue = if (requestPayload.payload.valid) Some(requestPayload.payload) else None,
      dequeueReady = requestSinkPreview.requestReady)
    val requestSink = LoadReplaySourceReturnStoreSnapshotRequestSinkReference(
      enable = enable,
      flush = flush,
      request = requestQueue.head,
      rawSinkReady = sinkReady,
      responseReady = sinkResponseReady)
    val acceptedToken = LoadReplaySourceReturnStoreSnapshotAcceptedTokenReference.step(
      state = acceptedTokenState,
      enable = enable,
      flush = flush,
      queryIssued = queryIssue.queryIssued,
      selectedValid = selectedValidResolved,
      selectedRepick = selectedRepickResolved,
      selectedClusterId = selectedClusterIdResolved,
      selectedEntryId = selectedEntryIdResolved,
      responseConsumed = false,
      selectedBid = selectedBid,
      selectedGid = selectedGid,
      selectedLoadLsId = selectedLoadLsId,
      selectedPeId = selectedPeId,
      selectedStid = selectedStid,
      selectedTid = selectedTid,
      selectedLineData = selectedLineData,
      selectedValidMask = selectedValidMask,
      selectedRequestByteMask = selectedRequestByteMask,
      preciseFlush = preciseFlush)
    val sinkResponse =
      if (!rawResponseSource.responseValid && requestSink.responseValid) {
        Some(LoadReplaySourceReturnStoreSnapshotResponseQueueReference.Response(
          clusterId = requestSink.responseClusterId,
          entryId = requestSink.responseEntryId,
          requestBid = requestSink.responseRequestBid,
          requestGid = requestSink.responseRequestGid,
          requestRid = requestSink.responseRequestRid,
          requestLoadLsId = requestSink.responseRequestLoadLsId,
          requestPeId = requestSink.responseRequestPeId,
          requestStid = requestSink.responseRequestStid,
          requestTid = requestSink.responseRequestTid,
          waitStore = requestSink.responseWaitStore,
          dataValid = requestSink.responseDataValid))
      } else {
        None
      }
    val rawResponse = rawResponseSource.response.map(response =>
      LoadReplaySourceReturnStoreSnapshotResponseQueueReference.Response(
        clusterId = response.clusterId,
        entryId = response.entryId,
        requestBid = response.requestBid,
        requestGid = response.requestGid,
        requestRid = response.requestRid,
        requestLoadLsId = response.requestLoadLsId,
        requestPeId = response.requestPeId,
        requestStid = response.requestStid,
        requestTid = response.requestTid,
        waitStore = response.waitStore,
        dataValid = response.dataValid,
        rawDataValid = response.rawDataValid,
        dataSuppressedByWait = response.dataSuppressedByWait,
        waitStoreIndex = response.waitStoreIndex,
        waitStoreBid = response.waitStoreBid,
        waitStoreRid = response.waitStoreRid,
        waitStoreLsId = response.waitStoreLsId,
        waitStorePc = response.waitStorePc,
        dataMask = response.dataMask,
        data = response.data))
    val responseEnqueue = rawResponse.orElse(sinkResponse)
    val responseQueuePreview = LoadReplaySourceReturnStoreSnapshotResponseQueueReference.step(
      state = responseQueueState,
      depth = 2,
      enable = enable,
      flush = flush,
      preciseFlush = preciseFlush,
      enqueue = responseEnqueue,
      dequeueReady = false)
    val responseHeadState = LoadReplaySourceReturnStoreSnapshotResponseHeadStateReference(
      liqEntries = 4,
      enable = enable,
      flush = flush,
      reducedEnable = selectedIdentityEnable,
      headValid = responseQueuePreview.headValid,
      responseClusterId = responseQueuePreview.head.map(_.clusterId).getOrElse(0),
      responseEntryId = responseQueuePreview.head.map(_.entryId).getOrElse(0),
      repickMask = selectedRepickMask,
      rowProofEnable = selectedIdentityEnable,
      rowValidMask = selectedRowValidMask,
      rowScbReturnedMask = selectedRowScbReturnedMask,
      externalHeadStale = responseHeadStale)
    val identityMatch = LoadReplaySourceReturnStoreSnapshotIdentityMatchReference(
      enable = enable,
      flush = flush,
      queryIssued = acceptedToken.token.valid,
      selectedValid = acceptedToken.token.valid,
      selectedRepick = acceptedToken.token.repick,
      responseValid = responseQueuePreview.headValid,
      selectedClusterId = acceptedToken.token.clusterId,
      selectedEntryId = acceptedToken.token.entryId,
      responseClusterId = responseQueuePreview.head.map(_.clusterId).getOrElse(0),
      responseEntryId = responseQueuePreview.head.map(_.entryId).getOrElse(0))
    val responseMatch = LoadReplaySourceReturnStoreSnapshotResponseMatchReference(
      enable = enable,
      flush = flush,
      queryIssued = acceptedToken.token.valid,
      responseValidIn = responseQueuePreview.headValid,
      responseMatchesSelected = identityMatch.responseMatchesSelected,
      scbReturned = scbReturned || responseHeadState.reducedHeadScbReturned,
      waitStoreIn = responseQueuePreview.head.exists(_.waitStore),
      dataValidIn = responseQueuePreview.head.exists(_.dataValid))
    val responseDrain = LoadReplaySourceReturnStoreSnapshotResponseDrainReference(
      enable = enable,
      flush = flush,
      headValid = responseQueuePreview.headValid,
      orderedResponse = responseMatch.responseValid,
      headStale = responseHeadState.headStale)
    val responseQueue = LoadReplaySourceReturnStoreSnapshotResponseQueueReference.step(
      state = responseQueueState,
      depth = 2,
      enable = enable,
      flush = flush,
      preciseFlush = preciseFlush,
      enqueue = responseEnqueue,
      dequeueReady = responseDrain.dequeueReady)
    val responseApply = LoadReplaySourceReturnStoreSnapshotResponseApplyReference(
      liqEntries = 4,
      enable = enable,
      flush = flush,
      orderedConsumed = responseDrain.orderedConsumed,
      targetRepick = responseHeadState.reducedHeadRepick,
      targetOneHot = responseHeadState.reducedHeadOneHot,
      response = responseQueuePreview.head.map(response =>
        LoadReplaySourceReturnStoreSnapshotResponseApplyReference.Response(
          valid = true,
          waitStore = response.waitStore,
          dataValid = response.dataValid,
          rawDataValid = response.rawDataValid,
          dataSuppressedByWait = response.dataSuppressedByWait,
          waitStoreIndex = response.waitStoreIndex,
          waitStoreBid = response.waitStoreBid,
          waitStoreRid = response.waitStoreRid,
          waitStoreLsId = response.waitStoreLsId,
          waitStorePc = response.waitStorePc,
          dataMask = response.dataMask,
          data = response.data)).getOrElse(LoadReplaySourceReturnStoreSnapshotResponseApplyReference.Response()),
      rowLineData = acceptedToken.token.lineData,
      rowValidMask = acceptedToken.token.validMask,
      rowRequestMask = acceptedToken.token.requestByteMask)
    val acceptedContextComplete =
      acceptedToken.token.requestByteMask != 0 &&
        ((acceptedToken.token.validMask & acceptedToken.token.requestByteMask) == acceptedToken.token.requestByteMask)
    val rowStatePlan = LoadReplaySourceReturnStoreSnapshotRowStatePlanReference(
      enable = enable,
      flush = flush,
      applyValid = responseApply.applyValid,
      applyStqReturned = responseApply.stqReturned,
      waitStoreApply = responseApply.waitStoreApply,
      dataMergeApply = responseApply.dataMergeApply,
      dataNoMerge = responseApply.dataNoMerge,
      priorScbReturned = scbReturned || responseHeadState.reducedHeadScbReturned,
      priorStqReturned = false,
      priorLineData = acceptedToken.token.lineData,
      priorValidMask = acceptedToken.token.validMask,
      priorRequestComplete = acceptedContextComplete,
      mergedLineData = responseApply.mergedLineData,
      mergedValidMask = responseApply.mergedValidMask,
      mergedRequestComplete = responseApply.mergedRequestComplete)
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
      rawResponseSourceCandidate = rawResponseSource.candidate,
      rawResponseSourceValid = rawResponseSource.responseValid,
      rawResponseSourceBlockedByLiveDisabled = rawResponseSource.blockedByLiveDisabled,
      identityMatchCandidate = identityMatch.matchCandidate,
      identityMatchSelectedReady = identityMatch.selectedReady,
      identityMatchIdentityMatches = identityMatch.identityMatches,
      identityMatchResponseMatchesSelected = identityMatch.responseMatchesSelected,
      identityMatchBlockedByNoQuery = identityMatch.blockedByNoQuery,
      identityMatchBlockedByNoSelected = identityMatch.blockedByNoSelected,
      identityMatchBlockedByStaleRow = identityMatch.blockedByStaleRow,
      identityMatchBlockedByClusterMismatch = identityMatch.blockedByClusterMismatch,
      identityMatchBlockedByEntryMismatch = identityMatch.blockedByEntryMismatch,
      identityMatchInvalidResponseWithoutQuery = identityMatch.invalidResponseWithoutQuery,
      responseMatchCandidate = responseMatch.responseCandidate,
      responseMatchMatched = responseMatch.responseMatched,
      responseMatchOrdered = responseMatch.responseOrdered,
      responseMatchValid = responseMatch.responseValid,
      responseMatchWaitStore = responseMatch.waitStore,
      responseMatchDataValid = responseMatch.dataValid,
      responseMatchBlockedByNoQuery = responseMatch.blockedByNoQuery,
      responseMatchBlockedByNoMatch = responseMatch.blockedByNoMatch,
      responseMatchBlockedByScbOrder = responseMatch.blockedByScbOrder,
      responseMatchInvalidResponseWithoutQuery = responseMatch.invalidResponseWithoutQuery,
      responseMatchInvalidDataWithWaitStore = responseMatch.invalidDataWithWaitStore,
      queryIssueActive = queryIssue.active,
      queryIssueRequestActive = queryIssue.requestActive,
      queryIssueCandidate = queryIssue.queryCandidate,
      queryIssueValid = queryIssue.queryValid,
      queryIssueIssued = queryIssue.queryIssued,
      queryIssueBlockedByRequestDisabled = queryIssue.blockedByRequestDisabled,
      queryIssueBlockedByNoLaunch = queryIssue.blockedByNoLaunch,
      queryIssueBlockedBySink = queryIssue.blockedBySink,
      requestControlBlockedByToken = requestControl.blockedByToken,
      acceptedTokenCanAccept = acceptedToken.tokenCanAccept,
      acceptedTokenValid = acceptedToken.token.valid,
      acceptedTokenResidentValid = acceptedToken.residentTokenValid,
      acceptedTokenCaptureCandidate = acceptedToken.captureCandidate,
      acceptedTokenCaptureAccepted = acceptedToken.captureAccepted,
      acceptedTokenClearAccepted = acceptedToken.clearAccepted,
      acceptedTokenPrecisePruned = acceptedToken.precisePruned,
      acceptedTokenBlockedByPreciseFlush = acceptedToken.blockedByPreciseFlush,
      acceptedTokenBlockedByOutstanding = acceptedToken.blockedByOutstandingToken,
      acceptedTokenClusterId = acceptedToken.token.clusterId,
      acceptedTokenEntryId = acceptedToken.token.entryId,
      requestPayloadValid = requestPayload.payload.valid,
      requestPayloadBlockedByNoIssue = requestPayload.blockedByNoIssue,
      requestPayloadBlockedByNoSelected = requestPayload.blockedByNoSelected,
      requestPayloadBlockedByStaleRow = requestPayload.blockedByStaleRow,
      responseHeadReducedScbReturned = responseHeadState.reducedHeadScbReturned,
      rowStatePlanValid = rowStatePlan.planValid,
      rowStatePlanNextScbReturned = rowStatePlan.nextScbReturned,
      rowStatePlanNextStoreSourceReturned = rowStatePlan.nextStoreSourceReturned,
      rowStatePlanInvalidStqApplyWithoutScb = rowStatePlan.invalidStqApplyWithoutScb,
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
      requestQueueBlockedByFull = requestQueue.blockedByFull,
      responseQueueEnqueueReady = responseQueue.enqueueReady,
      responseQueueEnqueueAccepted = responseQueue.enqueueAccepted,
      responseQueueEnqueueDropped = responseQueue.enqueueDropped,
      responseQueueHeadValid = responseQueue.headValid,
      responseQueueHeadConsumed = responseQueue.headConsumed,
      responseQueuePending = responseQueue.pending,
      responseQueueFull = responseQueue.full,
      responseQueueEmpty = responseQueue.empty,
      responseQueueCount = responseQueue.count,
      responseQueueBlockedByDisabled = responseQueue.blockedByDisabled,
      responseQueueBlockedByFlush = responseQueue.blockedByFlush,
      responseQueueBlockedByFull = responseQueue.blockedByFull,
      responseDrainDequeueReady = responseDrain.dequeueReady,
      responseDrainOrderedConsumed = responseDrain.orderedConsumed,
      responseDrainStaleDropped = responseDrain.staleDropped,
      responseDrainBlockedByNoHead = responseDrain.blockedByNoHead,
      responseDrainBlockedByNoAction = responseDrain.blockedByNoAction,
      responseDrainInvalidStaleWithOrdered = responseDrain.invalidStaleWithOrdered)
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
    assert(result.acceptedTokenCanAccept)
    assert(result.acceptedTokenValid)
    assert(result.acceptedTokenCaptureAccepted)
    assert(!result.acceptedTokenResidentValid)
    assert(result.acceptedTokenClusterId == 0)
    assert(result.acceptedTokenEntryId == 2)
    assert(!result.requestControlBlockedByToken)
    assert(result.requestPayloadValid)
    assert(result.requestQueueEnqueueAccepted)
    assert(result.requestQueueHeadConsumed)
    assert(result.requestQueueEmpty)
    assert(result.evidenceResponseAccepted)
    assert(result.evidenceSnapshotRequired)
    assert(result.evidenceSnapshotValid)
    assert(result.controlLiveReady)
    assert(result.storeSnapshotReady)
    assert(result.identityMatchCandidate)
    assert(result.identityMatchSelectedReady)
    assert(result.identityMatchIdentityMatches)
    assert(result.identityMatchResponseMatchesSelected)
    assert(result.responseMatchCandidate)
    assert(result.responseMatchMatched)
    assert(result.responseMatchOrdered)
    assert(result.responseMatchValid)
    assert(!result.controlBlockedBySnapshot)
  }

  test("resident accepted-query token blocks another local STQ snapshot issue") {
    val residentToken = LoadReplaySourceReturnStoreSnapshotAcceptedTokenReference.Token(
      valid = true,
      repick = true,
      clusterId = 0,
      entryId = 1,
      bid = 6,
      gid = 1,
      loadLsId = 9,
      peId = 2,
      stid = 3,
      tid = 4,
      lineData = BigInt("11223344", 16),
      validMask = BigInt("ff", 16),
      requestByteMask = BigInt("ff", 16))
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
      selectedLoadId = 2,
      selectedBid = 6,
      selectedGid = 1,
      selectedRid = 7,
      selectedLoadLsId = 9,
      selectedPc = BigInt("400055f2", 16),
      selectedAddr = BigInt("40012040", 16),
      selectedSize = 8,
      selectedRequestByteMask = BigInt("ff", 16),
      acceptedTokenState = residentToken)

    assert(!result.acceptedTokenCanAccept)
    assert(result.acceptedTokenValid)
    assert(result.acceptedTokenResidentValid)
    assert(result.acceptedTokenEntryId == 1)
    assert(!result.acceptedTokenCaptureAccepted)
    assert(!result.acceptedTokenBlockedByOutstanding)
    assert(result.requestControlBlockedByToken)
    assert(result.queryIssueValid)
    assert(!result.queryIssueIssued)
    assert(result.queryIssueBlockedBySink)
    assert(!result.requestPayloadValid)
    assert(!result.requestQueueEnqueueAccepted)
  }

  test("precise flush prunes a resident accepted-query token before new issue") {
    val residentToken = LoadReplaySourceReturnStoreSnapshotAcceptedTokenReference.Token(
      valid = true,
      repick = true,
      clusterId = 0,
      entryId = 1,
      bid = 6,
      gid = 1,
      loadLsId = 9,
      peId = 2,
      stid = 3,
      tid = 4,
      lineData = BigInt("11223344", 16),
      validMask = BigInt("ff", 16),
      requestByteMask = BigInt("ff", 16))
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
      selectedLoadId = 2,
      selectedBid = 6,
      selectedGid = 1,
      selectedRid = 7,
      selectedLoadLsId = 9,
      selectedPeId = 2,
      selectedStid = 3,
      selectedTid = 4,
      selectedPc = BigInt("400055f2", 16),
      selectedAddr = BigInt("40012040", 16),
      selectedSize = 8,
      selectedRequestByteMask = BigInt("ff", 16),
      acceptedTokenState = residentToken,
      preciseFlush = Some(STQFlushPruneReference.Flush(
        stid = 3,
        peId = 2,
        tid = 4,
        bid = STQFlushPruneReference.Id(value = 6),
        lsId = STQFlushPruneReference.Id(value = 9),
        baseOnPE = true,
        baseOnThread = true)))

    assert(!result.acceptedTokenCanAccept)
    assert(result.acceptedTokenValid)
    assert(result.acceptedTokenResidentValid)
    assert(result.acceptedTokenPrecisePruned)
    assert(!result.acceptedTokenBlockedByPreciseFlush)
    assert(!result.acceptedTokenCaptureAccepted)
    assert(result.queryIssueValid)
    assert(!result.queryIssueIssued)
    assert(result.queryIssueBlockedBySink)
    assert(!result.requestPayloadValid)
    assert(!result.requestQueueEnqueueAccepted)
  }

  test("reduced row SCB proof feeds the row-state plan") {
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
      selectedRowValidMask = 0x4,
      selectedRowScbReturnedMask = 0x4,
      selectedLoadId = 2,
      selectedBid = 6,
      selectedGid = 1,
      selectedRid = 7,
      selectedLoadLsId = 9,
      selectedPeId = 2,
      selectedStid = 3,
      selectedTid = 4,
      selectedPc = BigInt("400055f2", 16),
      selectedAddr = BigInt("40012040", 16),
      selectedSize = 8,
      selectedRequestByteMask = BigInt("ff", 16),
      selectedValidMask = BigInt("ff", 16))

    assert(result.evidenceResponseAccepted)
    assert(result.identityMatchResponseMatchesSelected)
    assert(result.responseMatchOrdered)
    assert(result.responseHeadReducedScbReturned)
    assert(result.rowStatePlanValid)
    assert(result.rowStatePlanNextScbReturned)
    assert(result.rowStatePlanNextStoreSourceReturned)
    assert(!result.rowStatePlanInvalidStqApplyWithoutScb)
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
      rawResponseLiveEnable = true,
      responseValidIn = true,
      responseClusterId = 0,
      responseEntryId = 2,
      responseRequestBid = 6,
      responseRequestGid = 1,
      responseRequestRid = 7,
      responseRequestLoadLsId = 9,
      responseRequestPeId = 2,
      responseRequestStid = 3,
      responseRequestTid = 4,
      scbReturned = true,
      selectedLoadId = 2,
      selectedBid = 6,
      selectedGid = 1,
      selectedRid = 7,
      selectedLoadLsId = 9,
      selectedPeId = 2,
      selectedStid = 3,
      selectedTid = 4,
      selectedPc = BigInt("400055f2", 16),
      selectedAddr = BigInt("40012040", 16),
      selectedSize = 8,
      selectedRequestByteMask = BigInt("ff", 16) << 8)

    assert(result.queryIssueIssued)
    assert(result.requestPayloadValid)
    assert(result.requestQueueEnqueueAccepted)
    assert(!result.requestQueueHeadConsumed)
    assert(result.requestQueuePending)
    assert(result.responseQueueEnqueueAccepted)
    assert(result.responseQueueHeadConsumed)
    assert(result.responseDrainOrderedConsumed)
    assert(result.evidenceResponseAccepted)
    assert(result.storeSnapshotReady)
  }

  test("live-disabled raw response source does not occupy the response enqueue port") {
    val result = LoadReplaySourceReturnStoreSnapshotPathReference(
      enable = true,
      flush = false,
      launchValid = true,
      legacySnapshotReady = false,
      requestEnable = true,
      rawResponseLiveEnable = false,
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
      selectedPeId = 2,
      selectedStid = 3,
      selectedTid = 4,
      selectedPc = BigInt("400055f2", 16),
      selectedAddr = BigInt("40012040", 16),
      selectedSize = 8,
      selectedRequestByteMask = BigInt("ff", 16) << 8)

    assert(result.rawResponseSourceCandidate)
    assert(!result.rawResponseSourceValid)
    assert(result.rawResponseSourceBlockedByLiveDisabled)
    assert(result.identityMatchResponseMatchesSelected)
    assert(result.responseMatchOrdered)
    assert(result.responseQueueEnqueueAccepted)
    assert(result.responseQueueHeadConsumed)
    assert(result.responseDrainOrderedConsumed)
    assert(result.requestQueueHeadConsumed)
    assert(result.evidenceResponseAccepted)
    assert(result.storeSnapshotReady)
  }

  test("path-local response queue diagnostics report a full response FIFO") {
    val first = LoadReplaySourceReturnStoreSnapshotResponseQueueReference.Response(
      clusterId = 0,
      entryId = 1,
      requestBid = 6,
      requestGid = 1,
      requestRid = 7,
      requestLoadLsId = 9)
    val second = LoadReplaySourceReturnStoreSnapshotResponseQueueReference.Response(
      clusterId = 0,
      entryId = 2,
      requestBid = 7,
      requestGid = 1,
      requestRid = 8,
      requestLoadLsId = 10)
    val result = LoadReplaySourceReturnStoreSnapshotPathReference(
      enable = true,
      flush = false,
      launchValid = false,
      legacySnapshotReady = false,
      rawResponseLiveEnable = true,
      responseValidIn = true,
      responseClusterId = 0,
      responseEntryId = 3,
      responseQueueState = Vector(first, second))

    assert(!result.responseQueueEnqueueReady)
    assert(!result.responseQueueEnqueueAccepted)
    assert(result.responseQueueEnqueueDropped)
    assert(result.responseQueueFull)
    assert(result.responseQueueCount == 2)
    assert(result.responseQueueBlockedByFull)
    assert(result.responseQueueHeadValid)
    assert(!result.responseQueueHeadConsumed)
    assert(result.responseDrainBlockedByNoAction)
  }

  test("path-local response drain diagnostics report explicit stale-head drop") {
    val staleHead = LoadReplaySourceReturnStoreSnapshotResponseQueueReference.Response(
      clusterId = 0,
      entryId = 2,
      requestBid = 6,
      requestGid = 1,
      requestRid = 7,
      requestLoadLsId = 9)
    val result = LoadReplaySourceReturnStoreSnapshotPathReference(
      enable = true,
      flush = false,
      launchValid = false,
      legacySnapshotReady = false,
      responseHeadStale = true,
      responseQueueState = Vector(staleHead))

    assert(result.responseQueueHeadValid)
    assert(result.responseDrainDequeueReady)
    assert(result.responseDrainStaleDropped)
    assert(result.responseQueueHeadConsumed)
    assert(result.responseQueueEmpty)
    assert(result.responseQueueCount == 0)
    assert(!result.responseDrainOrderedConsumed)
    assert(!result.responseDrainInvalidStaleWithOrdered)
  }

  test("path-local identity diagnostics report response blockers") {
    val noQuery = LoadReplaySourceReturnStoreSnapshotPathReference(
      enable = true,
      flush = false,
      launchValid = false,
      legacySnapshotReady = false,
      rawResponseLiveEnable = true,
      responseValidIn = true,
      responseClusterId = 0,
      responseEntryId = 2,
      selectedIdentityEnable = true,
      selectedLaunchIndex = 2,
      selectedRepickMask = 0x4)
    val mismatch = LoadReplaySourceReturnStoreSnapshotPathReference(
      enable = true,
      flush = false,
      launchValid = true,
      legacySnapshotReady = false,
      requestEnable = true,
      rawResponseLiveEnable = true,
      responseValidIn = true,
      responseClusterId = 0,
      responseEntryId = 3,
      selectedIdentityEnable = true,
      selectedLaunchIndex = 2,
      selectedRepickMask = 0x4,
      selectedLoadId = 2,
      selectedBid = 6,
      selectedGid = 1,
      selectedRid = 7,
      selectedLoadLsId = 9,
      selectedPc = BigInt("400055f2", 16),
      selectedAddr = BigInt("40012040", 16),
      selectedSize = 8,
      selectedRequestByteMask = BigInt("ff", 16))
    val stale = LoadReplaySourceReturnStoreSnapshotPathReference(
      enable = true,
      flush = false,
      launchValid = false,
      legacySnapshotReady = false,
      rawResponseLiveEnable = true,
      responseValidIn = true,
      responseClusterId = 0,
      responseEntryId = 2,
      acceptedTokenState = LoadReplaySourceReturnStoreSnapshotAcceptedTokenReference.Token(
        valid = true,
        repick = false,
        clusterId = 0,
        entryId = 2))

    assert(noQuery.identityMatchCandidate)
    assert(noQuery.identityMatchBlockedByNoQuery)
    assert(noQuery.identityMatchInvalidResponseWithoutQuery)
    assert(noQuery.responseMatchBlockedByNoQuery)
    assert(noQuery.responseMatchInvalidResponseWithoutQuery)
    assert(!noQuery.identityMatchResponseMatchesSelected)

    assert(mismatch.identityMatchCandidate)
    assert(mismatch.identityMatchSelectedReady)
    assert(!mismatch.identityMatchIdentityMatches)
    assert(mismatch.identityMatchBlockedByEntryMismatch)
    assert(mismatch.responseMatchBlockedByNoMatch)
    assert(!mismatch.responseMatchMatched)

    assert(stale.identityMatchCandidate)
    assert(stale.identityMatchBlockedByStaleRow)
    assert(stale.responseMatchBlockedByNoMatch)
    assert(!stale.identityMatchResponseMatchesSelected)
  }

  test("path-local response diagnostics distinguish SCB ordering and malformed payloads") {
    val waitingScb = LoadReplaySourceReturnStoreSnapshotPathReference(
      enable = true,
      flush = false,
      launchValid = true,
      legacySnapshotReady = false,
      requestEnable = true,
      rawResponseLiveEnable = true,
      responseValidIn = true,
      responseClusterId = 0,
      responseEntryId = 2,
      dataValidIn = true,
      rawDataValidIn = true,
      selectedIdentityEnable = true,
      selectedLaunchIndex = 2,
      selectedRepickMask = 0x4,
      selectedLoadId = 2,
      selectedBid = 6,
      selectedGid = 1,
      selectedRid = 7,
      selectedLoadLsId = 9,
      selectedPc = BigInt("400055f2", 16),
      selectedAddr = BigInt("40012040", 16),
      selectedSize = 8,
      selectedRequestByteMask = BigInt("ff", 16))
    val malformed = LoadReplaySourceReturnStoreSnapshotPathReference(
      enable = true,
      flush = false,
      launchValid = true,
      legacySnapshotReady = false,
      requestEnable = true,
      rawResponseLiveEnable = true,
      responseValidIn = true,
      responseClusterId = 0,
      responseEntryId = 2,
      waitStoreIn = true,
      dataValidIn = true,
      rawDataValidIn = true,
      scbReturned = true,
      selectedIdentityEnable = true,
      selectedLaunchIndex = 2,
      selectedRepickMask = 0x4,
      selectedLoadId = 2,
      selectedBid = 6,
      selectedGid = 1,
      selectedRid = 7,
      selectedLoadLsId = 9,
      selectedPc = BigInt("400055f2", 16),
      selectedAddr = BigInt("40012040", 16),
      selectedSize = 8,
      selectedRequestByteMask = BigInt("ff", 16))

    assert(waitingScb.identityMatchResponseMatchesSelected)
    assert(waitingScb.responseMatchMatched)
    assert(!waitingScb.responseMatchOrdered)
    assert(waitingScb.responseMatchBlockedByScbOrder)
    assert(!waitingScb.responseMatchValid)

    assert(malformed.responseMatchOrdered)
    assert(malformed.responseMatchValid)
    assert(malformed.responseMatchWaitStore)
    assert(malformed.responseMatchDataValid)
    assert(malformed.responseMatchInvalidDataWithWaitStore)
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
      selectedPeId = 2,
      selectedStid = 3,
      selectedTid = 4,
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
    assert(sv.contains("io_preciseFlush_req_valid"))
    assert(sv.contains("io_rowMutationLiveEnable"))
    assert(sv.contains("io_rawResponseLiveEnable"))
    assert(sv.contains("LoadReplaySourceReturnStoreSnapshotRequestControl"))
    assert(sv.contains("LoadReplaySourceReturnStoreSnapshotRequestPayload"))
    assert(sv.contains("LoadReplaySourceReturnStoreSnapshotRequestQueue"))
    assert(sv.contains("LoadReplaySourceReturnStoreSnapshotRequestSink"))
    assert(sv.contains("LoadReplaySourceReturnStoreSnapshotLookup"))
    assert(sv.contains("LoadReplaySourceReturnStoreSnapshotSelectedIdentity"))
    assert(sv.contains("LoadReplaySourceReturnStoreSnapshotAcceptedToken"))
    assert(sv.contains("LoadReplaySourceReturnStoreSnapshotResponseQueue"))
    assert(sv.contains("LoadReplaySourceReturnStoreSnapshotRawResponseSource"))
    assert(sv.contains("LoadReplaySourceReturnStoreSnapshotResponseHeadState"))
    assert(sv.contains("LoadReplaySourceReturnStoreSnapshotResponseDrain"))
    assert(sv.contains("LoadReplaySourceReturnStoreSnapshotResponseApply"))
    assert(sv.contains("LoadReplaySourceReturnStoreSnapshotRowStatePlan"))
    assert(sv.contains("LoadReplaySourceReturnStoreSnapshotRowMutationRequest"))
    assert(sv.contains("LoadReplaySourceReturnStoreSnapshotIdentityMatch"))
    assert(sv.contains("LoadReplaySourceReturnStoreSnapshotResponseMatch"))
    assert(sv.contains("io_storeSnapshotReady"))
    assert(sv.contains("io_selectedLaunchIndex"))
    assert(sv.contains("io_selectedRowValidMask"))
    assert(sv.contains("io_selectedRowScbReturnedMask"))
    assert(sv.contains("io_requestPayload_requestByteMask"))
    assert(sv.contains("io_requestPayload_peId"))
    assert(sv.contains("io_requestPayload_stid"))
    assert(sv.contains("io_requestPayload_tid"))
    assert(sv.contains("io_requestQueueHead_requestByteMask"))
    assert(sv.contains("io_requestQueueHead_peId"))
    assert(sv.contains("io_requestQueueHead_stid"))
    assert(sv.contains("io_requestQueueHead_tid"))
    assert(sv.contains("io_responseQueueEnqueueAccepted"))
    assert(sv.contains("io_responseQueueHeadConsumed"))
    assert(sv.contains("io_responseQueueBlockedByFull"))
    assert(sv.contains("io_responseDrainOrderedConsumed"))
    assert(sv.contains("io_responseDrainStaleDropped"))
    assert(sv.contains("io_responseDrainBlockedByNoAction"))
    assert(sv.contains("io_lookupResponseDataValid"))
    assert(sv.contains("io_selectedLineData"))
    assert(sv.contains("io_rawDataValidIn"))
    assert(sv.contains("io_responseRequestBid_value"))
    assert(sv.contains("io_responseRequestLoadLsId_value"))
    assert(sv.contains("io_responseRequestPeId"))
    assert(sv.contains("io_responseRequestStid"))
    assert(sv.contains("io_responseRequestTid"))
    assert(sv.contains("io_responseWaitStoreRid_value"))
    assert(sv.contains("io_responseDataMask"))
    assert(sv.contains("io_rawResponseSourceBlockedByLiveDisabled"))
    assert(sv.contains("io_identityMatchResponseMatchesSelected"))
    assert(sv.contains("io_identityMatchBlockedByStaleRow"))
    assert(sv.contains("io_identityMatchBlockedByEntryMismatch"))
    assert(sv.contains("io_responseMatchOrdered"))
    assert(sv.contains("io_responseMatchBlockedByScbOrder"))
    assert(sv.contains("io_responseMatchInvalidDataWithWaitStore"))
    assert(sv.contains("tokenLineDataReg"))
    assert(sv.contains("io_responseApplyValid"))
    assert(sv.contains("io_responseApplyWaitStoreRid_value"))
    assert(sv.contains("io_rowStatePlanNextStqReturned"))
    assert(sv.contains("io_rowStatePlanInvalidStqApplyWithoutScb"))
    assert(sv.contains("io_rowMutationCandidateTargetMask"))
    assert(sv.contains("io_rowMutationBlockedByLiveDisabled"))
    assert(sv.contains("io_queryIssueCandidate"))
    assert(sv.contains("io_requestControlBlockedByToken"))
    assert(sv.contains("io_acceptedTokenCanAccept"))
    assert(sv.contains("io_acceptedTokenValid"))
    assert(sv.contains("io_acceptedTokenResidentValid"))
    assert(sv.contains("io_acceptedTokenCaptureAccepted"))
    assert(sv.contains("io_acceptedTokenClearAccepted"))
    assert(sv.contains("io_acceptedTokenPrecisePruned"))
    assert(sv.contains("io_acceptedTokenBlockedByPreciseFlush"))
    assert(sv.contains("io_acceptedTokenBlockedByOutstanding"))
    assert(sv.contains("io_acceptedTokenEntryId"))
  }
}
