package linxcore.lsu

import chisel3._
import chisel3.util.log2Ceil

import linxcore.recovery.FlushBus
import linxcore.rob.ROBID

class LoadReplaySourceReturnStoreSnapshotPathIO(
    liqEntries: Int,
    idEntries: Int,
    clusterIdWidth: Int,
    entryIdWidth: Int,
    addrWidth: Int,
    dataWidth: Int,
    peIdWidth: Int,
    stidWidth: Int,
    tidWidth: Int,
    pcWidth: Int,
    lineBytes: Int,
    sizeWidth: Int,
    stqSizeWidth: Int,
    simtLaneWidth: Int,
    mapQDepth: Int,
    requestQueueDepth: Int,
    responseQueueDepth: Int) extends Bundle {
  private val liqPtrWidth = log2Ceil(liqEntries)
  private val requestQueueCountWidth = log2Ceil(requestQueueDepth + 1)
  private val responseQueueCountWidth = log2Ceil(responseQueueDepth + 1)

  val enable = Input(Bool())
  val flush = Input(Bool())
  val preciseFlush = Input(new FlushBus(idEntries, peIdWidth, stidWidth, tidWidth))
  val requestEnable = Input(Bool())
  val rowMutationLiveEnable = Input(Bool())
  val rawResponseLiveEnable = Input(Bool())
  val launchValid = Input(Bool())
  val sinkReady = Input(Bool())
  val selectedIdentityEnable = Input(Bool())
  val selectedLaunchIndex = Input(UInt(liqPtrWidth.W))
  val selectedRepickMask = Input(UInt(liqEntries.W))
  val selectedRowValidMask = Input(UInt(liqEntries.W))
  val selectedRowScbReturnedMask = Input(UInt(liqEntries.W))
  val selectedValid = Input(Bool())
  val selectedRepick = Input(Bool())
  val responseValidIn = Input(Bool())
  val selectedClusterId = Input(UInt(clusterIdWidth.W))
  val selectedEntryId = Input(UInt(entryIdWidth.W))
  val selectedLoadId = Input(new ROBID(liqEntries))
  val selectedBid = Input(new ROBID(idEntries))
  val selectedGid = Input(new ROBID(idEntries))
  val selectedRid = Input(new ROBID(idEntries))
  val selectedLoadLsId = Input(new ROBID(idEntries))
  val selectedPeId = Input(UInt(peIdWidth.W))
  val selectedStid = Input(UInt(stidWidth.W))
  val selectedTid = Input(UInt(tidWidth.W))
  val selectedPc = Input(UInt(pcWidth.W))
  val selectedAddr = Input(UInt(addrWidth.W))
  val selectedSize = Input(UInt(sizeWidth.W))
  val selectedRequestByteMask = Input(UInt(lineBytes.W))
  val selectedLineData = Input(UInt((lineBytes * 8).W))
  val selectedValidMask = Input(UInt(lineBytes.W))
  val responseClusterId = Input(UInt(clusterIdWidth.W))
  val responseEntryId = Input(UInt(entryIdWidth.W))
  val responseHeadStale = Input(Bool())
  val scbReturned = Input(Bool())
  val responseRequestBid = Input(new ROBID(idEntries))
  val responseRequestGid = Input(new ROBID(idEntries))
  val responseRequestRid = Input(new ROBID(idEntries))
  val responseRequestLoadLsId = Input(new ROBID(idEntries))
  val responseRequestPeId = Input(UInt(peIdWidth.W))
  val responseRequestStid = Input(UInt(stidWidth.W))
  val responseRequestTid = Input(UInt(tidWidth.W))
  val waitStoreIn = Input(Bool())
  val dataValidIn = Input(Bool())
  val rawDataValidIn = Input(Bool())
  val dataSuppressedByWaitIn = Input(Bool())
  val responseWaitStoreIndex = Input(UInt(log2Ceil(idEntries).W))
  val responseWaitStoreBid = Input(new ROBID(idEntries))
  val responseWaitStoreRid = Input(new ROBID(idEntries))
  val responseWaitStoreLsId = Input(new ROBID(idEntries))
  val responseWaitStorePc = Input(UInt(pcWidth.W))
  val responseDataMask = Input(UInt(lineBytes.W))
  val responseData = Input(UInt((lineBytes * 8).W))
  val legacySnapshotReady = Input(Bool())
  val stqRows = Input(Vec(idEntries, new STQEntryBankRow(
    idEntries,
    addrWidth,
    dataWidth,
    peIdWidth,
    stidWidth,
    tidWidth,
    stqSizeWidth,
    simtLaneWidth,
    mapQDepth,
    pcWidth
  )))

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

  val rawResponseSourceActive = Output(Bool())
  val rawResponseSourceCandidate = Output(Bool())
  val rawResponseSourceValid = Output(Bool())
  val rawResponseSourceBlockedByDisabled = Output(Bool())
  val rawResponseSourceBlockedByFlush = Output(Bool())
  val rawResponseSourceBlockedByLiveDisabled = Output(Bool())
  val rawResponseSourceInvalidDataWithWaitStore = Output(Bool())
  val rawResponseSourceInvalidDataValidWithoutRawData = Output(Bool())
  val rawResponseSourceInvalidSuppressedDataWithoutWait = Output(Bool())
  val rawResponseSourceInvalidSuppressedDataWithoutRawData = Output(Bool())

  val identityMatchActive = Output(Bool())
  val identityMatchCandidate = Output(Bool())
  val identityMatchSelectedReady = Output(Bool())
  val identityMatchClusterMatches = Output(Bool())
  val identityMatchEntryMatches = Output(Bool())
  val identityMatchIdentityMatches = Output(Bool())
  val identityMatchResponseMatchesSelected = Output(Bool())
  val identityMatchBlockedByDisabled = Output(Bool())
  val identityMatchBlockedByFlush = Output(Bool())
  val identityMatchBlockedByNoQuery = Output(Bool())
  val identityMatchBlockedByNoSelected = Output(Bool())
  val identityMatchBlockedByStaleRow = Output(Bool())
  val identityMatchBlockedByClusterMismatch = Output(Bool())
  val identityMatchBlockedByEntryMismatch = Output(Bool())
  val identityMatchInvalidResponseWithoutQuery = Output(Bool())

  val responseMatchActive = Output(Bool())
  val responseMatchCandidate = Output(Bool())
  val responseMatchMatched = Output(Bool())
  val responseMatchOrdered = Output(Bool())
  val responseMatchValid = Output(Bool())
  val responseMatchWaitStore = Output(Bool())
  val responseMatchDataValid = Output(Bool())
  val responseMatchBlockedByDisabled = Output(Bool())
  val responseMatchBlockedByFlush = Output(Bool())
  val responseMatchBlockedByNoQuery = Output(Bool())
  val responseMatchBlockedByNoMatch = Output(Bool())
  val responseMatchBlockedByScbOrder = Output(Bool())
  val responseMatchInvalidResponseWithoutQuery = Output(Bool())
  val responseMatchInvalidDataWithWaitStore = Output(Bool())

  val responseApplyActive = Output(Bool())
  val responseApplyCandidate = Output(Bool())
  val responseApplyValid = Output(Bool())
  val responseApplyStqReturned = Output(Bool())
  val responseApplyTargetMask = Output(UInt(liqEntries.W))
  val responseApplyWaitStore = Output(Bool())
  val responseApplyWaitStoreInfo = Output(new LoadStoreForwardWait(idEntries, idEntries, pcWidth))
  val responseApplyWaitStoreRid = Output(new ROBID(idEntries))
  val responseApplyDataMerge = Output(Bool())
  val responseApplyDataNoMerge = Output(Bool())
  val responseApplyMergedValidMask = Output(UInt(lineBytes.W))
  val responseApplyMergedLineData = Output(UInt((lineBytes * 8).W))
  val responseApplyMergedRequestComplete = Output(Bool())
  val responseApplyBlockedByNoOrderedResponse = Output(Bool())
  val responseApplyBlockedByNotRepick = Output(Bool())
  val responseApplyBlockedByWaitStore = Output(Bool())
  val responseApplyBlockedByNoData = Output(Bool())
  val responseApplyInvalidOrderedWithoutPayload = Output(Bool())
  val responseApplyInvalidDataWithWaitStore = Output(Bool())
  val responseApplyInvalidDataValidWithoutRawData = Output(Bool())
  val responseApplyInvalidSuppressedDataWithoutWait = Output(Bool())
  val rowStatePlanActive = Output(Bool())
  val rowStatePlanValid = Output(Bool())
  val rowStatePlanRewait = Output(Bool())
  val rowStatePlanDataMerge = Output(Bool())
  val rowStatePlanDataNoMerge = Output(Bool())
  val rowStatePlanSetWaitStatus = Output(Bool())
  val rowStatePlanKeepRepickStatus = Output(Bool())
  val rowStatePlanClearReturnState = Output(Bool())
  val rowStatePlanLineWrite = Output(Bool())
  val rowStatePlanWaitStoreWrite = Output(Bool())
  val rowStatePlanNextWaitStore = Output(Bool())
  val rowStatePlanNextWaitStoreInfo = Output(new LoadStoreForwardWait(idEntries, idEntries, pcWidth))
  val rowStatePlanNextWaitStoreRid = Output(new ROBID(idEntries))
  val rowStatePlanNextLineData = Output(UInt((lineBytes * 8).W))
  val rowStatePlanNextValidMask = Output(UInt(lineBytes.W))
  val rowStatePlanNextDataComplete = Output(Bool())
  val rowStatePlanNextScbReturned = Output(Bool())
  val rowStatePlanNextStqReturned = Output(Bool())
  val rowStatePlanNextStoreSourceReturned = Output(Bool())
  val rowStatePlanBlockedByNoApply = Output(Bool())
  val rowStatePlanInvalidApplyWithoutStqReturned = Output(Bool())
  val rowStatePlanInvalidStqReturnedWithoutApply = Output(Bool())
  val rowStatePlanInvalidStqApplyWithoutScb = Output(Bool())
  val rowMutationActive = Output(Bool())
  val rowMutationCandidateValid = Output(Bool())
  val rowMutationCandidateTargetMask = Output(UInt(liqEntries.W))
  val rowMutationCandidateTargetCount = Output(UInt(log2Ceil(liqEntries + 1).W))
  val rowMutationCandidateTargetIndex = Output(UInt(liqPtrWidth.W))
  val rowMutationTargetReady = Output(Bool())
  val rowMutationRequestValid = Output(Bool())
  val rowMutationRequestTargetMask = Output(UInt(liqEntries.W))
  val rowMutationRequestTargetIndex = Output(UInt(liqPtrWidth.W))
  val rowMutationStatusWrite = Output(Bool())
  val rowMutationSetWaitStatus = Output(Bool())
  val rowMutationKeepRepickStatus = Output(Bool())
  val rowMutationReturnStateWrite = Output(Bool())
  val rowMutationClearReturnState = Output(Bool())
  val rowMutationLineWrite = Output(Bool())
  val rowMutationWaitStoreWrite = Output(Bool())
  val rowMutationNextWaitStore = Output(Bool())
  val rowMutationNextWaitStoreInfo = Output(new LoadStoreForwardWait(idEntries, idEntries, pcWidth))
  val rowMutationNextWaitStoreRid = Output(new ROBID(idEntries))
  val rowMutationNextLineData = Output(UInt((lineBytes * 8).W))
  val rowMutationNextValidMask = Output(UInt(lineBytes.W))
  val rowMutationNextDataComplete = Output(Bool())
  val rowMutationNextScbReturned = Output(Bool())
  val rowMutationNextStqReturned = Output(Bool())
  val rowMutationNextStoreSourceReturned = Output(Bool())
  val rowMutationBlockedByNoPlan = Output(Bool())
  val rowMutationBlockedByNoTarget = Output(Bool())
  val rowMutationBlockedByLiveDisabled = Output(Bool())
  val rowMutationInvalidMultiTarget = Output(Bool())
  val rowMutationInvalidWriteWithoutPlan = Output(Bool())
  val rowMutationInvalidWaitStoreWithoutWaitStatus = Output(Bool())
  val rowMutationInvalidReturnWithoutSplitSources = Output(Bool())
  val rowMutationInvalidConflictingStatusWrite = Output(Bool())

  val queryIssueActive = Output(Bool())
  val queryIssueRequestActive = Output(Bool())
  val queryIssueCandidate = Output(Bool())
  val queryIssueValid = Output(Bool())
  val queryIssueIssued = Output(Bool())
  val queryIssueBlockedByRequestDisabled = Output(Bool())
  val queryIssueBlockedByNoLaunch = Output(Bool())
  val queryIssueBlockedBySink = Output(Bool())
  val requestControlBlockedByToken = Output(Bool())

  val acceptedTokenCanAccept = Output(Bool())
  val acceptedTokenValid = Output(Bool())
  val acceptedTokenResidentValid = Output(Bool())
  val acceptedTokenCaptureCandidate = Output(Bool())
  val acceptedTokenCaptureAccepted = Output(Bool())
  val acceptedTokenClearAccepted = Output(Bool())
  val acceptedTokenPrecisePruned = Output(Bool())
  val acceptedTokenBlockedByPreciseFlush = Output(Bool())
  val acceptedTokenBlockedByOutstanding = Output(Bool())
  val acceptedTokenClusterId = Output(UInt(clusterIdWidth.W))
  val acceptedTokenEntryId = Output(UInt(entryIdWidth.W))

  val requestPayloadActive = Output(Bool())
  val requestPayloadCaptureCandidate = Output(Bool())
  val requestPayloadValid = Output(Bool())
  val requestPayload = Output(new LoadReplaySourceReturnStoreSnapshotRequestPayloadBundle(
    liqEntries,
    idEntries,
    clusterIdWidth,
    entryIdWidth,
    addrWidth,
    pcWidth,
    lineBytes,
    sizeWidth,
    peIdWidth,
    stidWidth,
    tidWidth
  ))
  val requestPayloadBlockedByNoIssue = Output(Bool())
  val requestPayloadBlockedByNoSelected = Output(Bool())
  val requestPayloadBlockedByStaleRow = Output(Bool())

  val requestQueueEnqueueReady = Output(Bool())
  val requestQueueEnqueueAccepted = Output(Bool())
  val requestQueueEnqueueDropped = Output(Bool())
  val requestQueueHeadValid = Output(Bool())
  val requestQueueHead = Output(new LoadReplaySourceReturnStoreSnapshotRequestPayloadBundle(
    liqEntries,
    idEntries,
    clusterIdWidth,
    entryIdWidth,
    addrWidth,
    pcWidth,
    lineBytes,
    sizeWidth,
    peIdWidth,
    stidWidth,
    tidWidth
  ))
  val requestQueueHeadConsumed = Output(Bool())
  val requestQueuePending = Output(Bool())
  val requestQueueFull = Output(Bool())
  val requestQueueEmpty = Output(Bool())
  val requestQueueCount = Output(UInt(requestQueueCountWidth.W))
  val requestQueueBlockedByDisabled = Output(Bool())
  val requestQueueBlockedByFlush = Output(Bool())
  val requestQueuePrecisePruneMask = Output(UInt(requestQueueDepth.W))
  val requestQueuePrecisePruneCount = Output(UInt(requestQueueCountWidth.W))
  val requestQueueBlockedByPreciseFlush = Output(Bool())
  val requestQueueBlockedByFull = Output(Bool())
  val requestSinkActive = Output(Bool())
  val requestSinkCandidate = Output(Bool())
  val requestSinkReady = Output(Bool())
  val requestSinkAccepted = Output(Bool())
  val requestSinkResponseValid = Output(Bool())
  val requestSinkBlockedByDisabled = Output(Bool())
  val requestSinkBlockedByFlush = Output(Bool())
  val requestSinkBlockedByNoRequest = Output(Bool())
  val requestSinkBlockedByRawSink = Output(Bool())
  val requestSinkBlockedByResponse = Output(Bool())
  val requestSinkInvalidDataWithWaitStore = Output(Bool())
  val responseQueueEnqueueReady = Output(Bool())
  val responseQueueEnqueueAccepted = Output(Bool())
  val responseQueueEnqueueDropped = Output(Bool())
  val responseQueueHeadValid = Output(Bool())
  val responseQueueHeadConsumed = Output(Bool())
  val responseQueuePending = Output(Bool())
  val responseQueueFull = Output(Bool())
  val responseQueueEmpty = Output(Bool())
  val responseQueueCount = Output(UInt(responseQueueCountWidth.W))
  val responseQueuePrecisePruneMask = Output(UInt(responseQueueDepth.W))
  val responseQueuePrecisePruneCount = Output(UInt(responseQueueCountWidth.W))
  val responseQueueBlockedByPreciseFlush = Output(Bool())
  val responseQueueBlockedByDisabled = Output(Bool())
  val responseQueueBlockedByFlush = Output(Bool())
  val responseQueueBlockedByFull = Output(Bool())
  val responseDrainActive = Output(Bool())
  val responseDrainDequeueReady = Output(Bool())
  val responseDrainOrderedConsumed = Output(Bool())
  val responseDrainStaleDropped = Output(Bool())
  val responseDrainBlockedByNoHead = Output(Bool())
  val responseDrainBlockedByNoAction = Output(Bool())
  val responseDrainBlockedByDisabled = Output(Bool())
  val responseDrainBlockedByFlush = Output(Bool())
  val responseDrainInvalidStaleWithOrdered = Output(Bool())
  val lookupActive = Output(Bool())
  val lookupQueryValid = Output(Bool())
  val lookupLoadCrossesLine = Output(Bool())
  val lookupRequestMaskMismatch = Output(Bool())
  val lookupStoreSnapshotValidMask = Output(UInt(idEntries.W))
  val lookupStoreSnapshotWaitMask = Output(UInt(idEntries.W))
  val lookupStoreSnapshotCrossLineMask = Output(UInt(idEntries.W))
  val lookupEligibleStoreMask = Output(UInt(idEntries.W))
  val lookupForwardMask = Output(UInt(lineBytes.W))
  val lookupWaitMask = Output(UInt(lineBytes.W))
  val lookupWaitStoreValid = Output(Bool())
  val lookupRawDataValid = Output(Bool())
  val lookupResponseDataValid = Output(Bool())
  val lookupDataSuppressedByWait = Output(Bool())
  val lookupStoreBypassComplete = Output(Bool())
}

class LoadReplaySourceReturnStoreSnapshotPath(
    liqEntries: Int = 4,
    idEntries: Int = 16,
    clusterIdWidth: Int = 2,
    entryIdWidth: Int = 4,
    addrWidth: Int = 64,
    dataWidth: Int = 64,
    peIdWidth: Int = 8,
    stidWidth: Int = 8,
    tidWidth: Int = 8,
    pcWidth: Int = 64,
    lineBytes: Int = 64,
    sizeWidth: Int = 7,
    stqSizeWidth: Int = 4,
    simtLaneWidth: Int = 8,
    mapQDepth: Int = 32,
    requestQueueDepth: Int = 2,
    responseQueueDepth: Int = 2) extends Module {
  require(liqEntries > 1, "LIQ entries must be greater than one")
  require((liqEntries & (liqEntries - 1)) == 0, "LIQ entries must be a power of two")
  require(idEntries > 1, "ID entries must be greater than one")
  require((idEntries & (idEntries - 1)) == 0, "ID entries must be a power of two")
  require(clusterIdWidth > 0, "clusterIdWidth must be positive")
  require(entryIdWidth >= log2Ceil(liqEntries), "entryIdWidth must cover the reduced LIQ slot index")
  require(addrWidth >= 7, "LoadReplaySourceReturnStoreSnapshotPath needs 64-byte line addresses")
  require(dataWidth == 64, "LoadReplaySourceReturnStoreSnapshotPath currently consumes 64-bit scalar STQ rows")
  require(lineBytes == 64, "LoadReplaySourceReturnStoreSnapshotPath currently models 64-byte scalar cachelines")
  require(sizeWidth >= 7, "sizeWidth must cover 64-byte scalar lines")
  require(stqSizeWidth >= 4, "stqSizeWidth must cover scalar STQ row sizes")
  require(requestQueueDepth > 0, "requestQueueDepth must be nonzero")
  require(responseQueueDepth > 0, "responseQueueDepth must be nonzero")

  val io = IO(new LoadReplaySourceReturnStoreSnapshotPathIO(
    liqEntries = liqEntries,
    idEntries = idEntries,
    clusterIdWidth = clusterIdWidth,
    entryIdWidth = entryIdWidth,
    addrWidth = addrWidth,
    dataWidth = dataWidth,
    peIdWidth = peIdWidth,
    stidWidth = stidWidth,
    tidWidth = tidWidth,
    pcWidth = pcWidth,
    lineBytes = lineBytes,
    sizeWidth = sizeWidth,
    stqSizeWidth = stqSizeWidth,
    simtLaneWidth = simtLaneWidth,
    mapQDepth = mapQDepth,
    requestQueueDepth = requestQueueDepth,
    responseQueueDepth = responseQueueDepth
  ))

  val queryIssue = Module(new LoadReplaySourceReturnStoreSnapshotQueryIssue)
  val requestControl = Module(new LoadReplaySourceReturnStoreSnapshotRequestControl)
  val requestPayload = Module(new LoadReplaySourceReturnStoreSnapshotRequestPayload(
    liqEntries = liqEntries,
    idEntries = idEntries,
    clusterIdWidth = clusterIdWidth,
    entryIdWidth = entryIdWidth,
    addrWidth = addrWidth,
    pcWidth = pcWidth,
    lineBytes = lineBytes,
    sizeWidth = sizeWidth,
    peIdWidth = peIdWidth,
    stidWidth = stidWidth,
    tidWidth = tidWidth
  ))
  val requestQueue = Module(new LoadReplaySourceReturnStoreSnapshotRequestQueue(
    liqEntries = liqEntries,
    idEntries = idEntries,
    clusterIdWidth = clusterIdWidth,
    entryIdWidth = entryIdWidth,
    addrWidth = addrWidth,
    pcWidth = pcWidth,
    lineBytes = lineBytes,
    sizeWidth = sizeWidth,
    depth = requestQueueDepth,
    peIdWidth = peIdWidth,
    stidWidth = stidWidth,
    tidWidth = tidWidth
  ))
  val requestSink = Module(new LoadReplaySourceReturnStoreSnapshotRequestSink(
    liqEntries = liqEntries,
    idEntries = idEntries,
    clusterIdWidth = clusterIdWidth,
    entryIdWidth = entryIdWidth,
    addrWidth = addrWidth,
    pcWidth = pcWidth,
    lineBytes = lineBytes,
    sizeWidth = sizeWidth,
    peIdWidth = peIdWidth,
    stidWidth = stidWidth,
    tidWidth = tidWidth
  ))
  val lookup = Module(new LoadReplaySourceReturnStoreSnapshotLookup(
    liqEntries = liqEntries,
    idEntries = idEntries,
    clusterIdWidth = clusterIdWidth,
    entryIdWidth = entryIdWidth,
    addrWidth = addrWidth,
    dataWidth = dataWidth,
    peIdWidth = peIdWidth,
    stidWidth = stidWidth,
    tidWidth = tidWidth,
    requestSizeWidth = sizeWidth,
    stqSizeWidth = stqSizeWidth,
    simtLaneWidth = simtLaneWidth,
    mapQDepth = mapQDepth,
    pcWidth = pcWidth,
    lineBytes = lineBytes
  ))
  val selectedIdentity = Module(new LoadReplaySourceReturnStoreSnapshotSelectedIdentity(
    liqEntries = liqEntries,
    clusterIdWidth = clusterIdWidth,
    entryIdWidth = entryIdWidth
  ))
  val acceptedToken = Module(new LoadReplaySourceReturnStoreSnapshotAcceptedToken(
    idEntries = idEntries,
    clusterIdWidth = clusterIdWidth,
    entryIdWidth = entryIdWidth,
    peIdWidth = peIdWidth,
    stidWidth = stidWidth,
    tidWidth = tidWidth,
    lineBytes = lineBytes
  ))
  val responseQueue = Module(new LoadReplaySourceReturnStoreSnapshotResponseQueue(
    idEntries = idEntries,
    clusterIdWidth = clusterIdWidth,
    entryIdWidth = entryIdWidth,
    depth = responseQueueDepth,
    pcWidth = pcWidth,
    lineBytes = lineBytes,
    peIdWidth = peIdWidth,
    stidWidth = stidWidth,
    tidWidth = tidWidth
  ))
  val rawResponseSource = Module(new LoadReplaySourceReturnStoreSnapshotRawResponseSource(
    idEntries = idEntries,
    clusterIdWidth = clusterIdWidth,
    entryIdWidth = entryIdWidth,
    pcWidth = pcWidth,
    lineBytes = lineBytes,
    peIdWidth = peIdWidth,
    stidWidth = stidWidth,
    tidWidth = tidWidth
  ))
  val identityMatch = Module(new LoadReplaySourceReturnStoreSnapshotIdentityMatch(
    clusterIdWidth = clusterIdWidth,
    entryIdWidth = entryIdWidth
  ))
  val responseMatch = Module(new LoadReplaySourceReturnStoreSnapshotResponseMatch)
  val responseHeadState = Module(new LoadReplaySourceReturnStoreSnapshotResponseHeadState(
    liqEntries = liqEntries,
    clusterIdWidth = clusterIdWidth,
    entryIdWidth = entryIdWidth
  ))
  val responseDrain = Module(new LoadReplaySourceReturnStoreSnapshotResponseDrain)
  val responseApply = Module(new LoadReplaySourceReturnStoreSnapshotResponseApply(
    liqEntries = liqEntries,
    idEntries = idEntries,
    clusterIdWidth = clusterIdWidth,
    entryIdWidth = entryIdWidth,
    pcWidth = pcWidth,
    lineBytes = lineBytes,
    peIdWidth = peIdWidth,
    stidWidth = stidWidth,
    tidWidth = tidWidth
  ))
  val rowStatePlan = Module(new LoadReplaySourceReturnStoreSnapshotRowStatePlan(
    idEntries = idEntries,
    pcWidth = pcWidth,
    lineBytes = lineBytes
  ))
  val rowMutationRequest = Module(new LoadReplaySourceReturnStoreSnapshotRowMutationRequest(
    liqEntries = liqEntries,
    idEntries = idEntries,
    pcWidth = pcWidth,
    lineBytes = lineBytes
  ))
  val evidence = Module(new LoadReplaySourceReturnStoreSnapshotEvidence)
  val control = Module(new LoadReplaySourceReturnStoreSnapshotReadyControl)

  requestControl.io.enable := io.enable
  requestControl.io.flush := io.flush
  requestControl.io.requestEnable := io.requestEnable
  requestControl.io.launchValid := io.launchValid
  requestControl.io.rawSinkReady := requestQueue.io.enqueueReady
  requestControl.io.tokenCanAccept := acceptedToken.io.tokenCanAccept

  queryIssue.io.enable := io.enable
  queryIssue.io.flush := io.flush
  queryIssue.io.requestEnable := requestControl.io.queryRequestEnable
  queryIssue.io.launchValid := io.launchValid
  queryIssue.io.sinkReady := requestControl.io.querySinkReady

  selectedIdentity.io.enable := io.enable && io.selectedIdentityEnable
  selectedIdentity.io.flush := io.flush
  selectedIdentity.io.launchValid := io.launchValid
  selectedIdentity.io.launchIndex := io.selectedLaunchIndex
  selectedIdentity.io.repickMask := io.selectedRepickMask

  val selectedValid = Mux(io.selectedIdentityEnable, selectedIdentity.io.selectedValid, io.selectedValid)
  val selectedRepick = Mux(io.selectedIdentityEnable, selectedIdentity.io.selectedRepick, io.selectedRepick)
  val selectedClusterId = Mux(io.selectedIdentityEnable, selectedIdentity.io.selectedClusterId, io.selectedClusterId)
  val selectedEntryId = Mux(io.selectedIdentityEnable, selectedIdentity.io.selectedEntryId, io.selectedEntryId)

  requestPayload.io.enable := io.enable
  requestPayload.io.flush := io.flush
  requestPayload.io.queryIssued := queryIssue.io.queryIssued
  requestPayload.io.selectedValid := selectedValid
  requestPayload.io.selectedRepick := selectedRepick
  requestPayload.io.selectedClusterId := selectedClusterId
  requestPayload.io.selectedEntryId := selectedEntryId
  requestPayload.io.selectedLoadId := io.selectedLoadId
  requestPayload.io.selectedBid := io.selectedBid
  requestPayload.io.selectedGid := io.selectedGid
  requestPayload.io.selectedRid := io.selectedRid
  requestPayload.io.selectedLoadLsId := io.selectedLoadLsId
  requestPayload.io.selectedPeId := io.selectedPeId
  requestPayload.io.selectedStid := io.selectedStid
  requestPayload.io.selectedTid := io.selectedTid
  requestPayload.io.selectedPc := io.selectedPc
  requestPayload.io.selectedAddr := io.selectedAddr
  requestPayload.io.selectedSize := io.selectedSize
  requestPayload.io.selectedRequestByteMask := io.selectedRequestByteMask

  requestQueue.io.enable := io.enable
  requestQueue.io.flush := io.flush
  requestQueue.io.preciseFlush := io.preciseFlush
  requestQueue.io.enqueueValid := requestPayload.io.requestValid
  requestQueue.io.enqueueRequest := requestPayload.io.request
  requestQueue.io.dequeueReady := requestSink.io.requestReady

  lookup.io.enable := io.enable
  lookup.io.flush := io.flush
  lookup.io.requestValid := requestQueue.io.headValid
  lookup.io.request := requestQueue.io.head
  lookup.io.rows := io.stqRows
  lookup.io.cacheData := 0.U

  val lookupWaitStoreRid = WireDefault(0.U.asTypeOf(new ROBID(idEntries)))
  when(lookup.io.waitStoreValid) {
    lookupWaitStoreRid := io.stqRows(lookup.io.waitStore.storeIndex).rid
  }

  requestSink.io.enable := io.enable
  requestSink.io.flush := io.flush
  requestSink.io.requestValid := requestQueue.io.headValid
  requestSink.io.request := requestQueue.io.head
  requestSink.io.rawSinkReady := io.sinkReady
  requestSink.io.responseReady := !responseQueue.io.full && !rawResponseSource.io.responseValid
  requestSink.io.lookupWaitStore := lookup.io.waitStoreValid
  requestSink.io.lookupWaitStoreInfo := lookup.io.waitStore
  requestSink.io.lookupWaitStoreRid := lookupWaitStoreRid
  requestSink.io.lookupRawDataValid := lookup.io.rawDataValid
  requestSink.io.lookupDataValid := lookup.io.responseDataValid
  requestSink.io.lookupDataSuppressedByWait := lookup.io.dataSuppressedByWait
  requestSink.io.lookupDataMask := lookup.io.forwardMask
  requestSink.io.lookupData := lookup.io.forwardData

  acceptedToken.io.enable := io.enable
  acceptedToken.io.flush := io.flush
  acceptedToken.io.preciseFlush := io.preciseFlush
  acceptedToken.io.queryIssued := queryIssue.io.queryIssued
  acceptedToken.io.selectedValid := selectedValid
  acceptedToken.io.selectedRepick := selectedRepick
  acceptedToken.io.selectedClusterId := selectedClusterId
  acceptedToken.io.selectedEntryId := selectedEntryId
  acceptedToken.io.selectedBid := io.selectedBid
  acceptedToken.io.selectedGid := io.selectedGid
  acceptedToken.io.selectedLoadLsId := io.selectedLoadLsId
  acceptedToken.io.selectedPeId := io.selectedPeId
  acceptedToken.io.selectedStid := io.selectedStid
  acceptedToken.io.selectedTid := io.selectedTid
  acceptedToken.io.selectedLineData := io.selectedLineData
  acceptedToken.io.selectedValidMask := io.selectedValidMask
  acceptedToken.io.selectedRequestByteMask := io.selectedRequestByteMask
  acceptedToken.io.responseConsumed := responseDrain.io.orderedConsumed

  rawResponseSource.io.enable := io.enable
  rawResponseSource.io.flush := io.flush
  rawResponseSource.io.liveEnable := io.rawResponseLiveEnable
  rawResponseSource.io.rawValid := io.responseValidIn
  rawResponseSource.io.clusterId := io.responseClusterId
  rawResponseSource.io.entryId := io.responseEntryId
  rawResponseSource.io.requestBid := io.responseRequestBid
  rawResponseSource.io.requestGid := io.responseRequestGid
  rawResponseSource.io.requestRid := io.responseRequestRid
  rawResponseSource.io.requestLoadLsId := io.responseRequestLoadLsId
  rawResponseSource.io.requestPeId := io.responseRequestPeId
  rawResponseSource.io.requestStid := io.responseRequestStid
  rawResponseSource.io.requestTid := io.responseRequestTid
  rawResponseSource.io.waitStore := io.waitStoreIn
  rawResponseSource.io.dataValid := io.dataValidIn
  rawResponseSource.io.rawDataValid := io.rawDataValidIn
  rawResponseSource.io.dataSuppressedByWait := io.dataSuppressedByWaitIn
  rawResponseSource.io.waitStoreIndex := io.responseWaitStoreIndex
  rawResponseSource.io.waitStoreBid := io.responseWaitStoreBid
  rawResponseSource.io.waitStoreRid := io.responseWaitStoreRid
  rawResponseSource.io.waitStoreLsId := io.responseWaitStoreLsId
  rawResponseSource.io.waitStorePc := io.responseWaitStorePc
  rawResponseSource.io.dataMask := io.responseDataMask
  rawResponseSource.io.data := io.responseData

  responseQueue.io.enable := io.enable
  responseQueue.io.flush := io.flush
  responseQueue.io.preciseFlush := io.preciseFlush
  val enqueueSinkResponse = !rawResponseSource.io.responseValid && requestSink.io.responseValid
  responseQueue.io.enqueueValid := rawResponseSource.io.responseValid || enqueueSinkResponse
  responseQueue.io.enqueue := Mux(rawResponseSource.io.responseValid, rawResponseSource.io.response, requestSink.io.response)
  responseQueue.io.dequeueReady := responseDrain.io.dequeueReady

  identityMatch.io.enable := io.enable
  identityMatch.io.flush := io.flush
  identityMatch.io.queryIssued := acceptedToken.io.tokenValid
  identityMatch.io.selectedValid := acceptedToken.io.tokenValid
  identityMatch.io.selectedRepick := acceptedToken.io.tokenRepick
  identityMatch.io.responseValid := responseQueue.io.headValid
  identityMatch.io.selectedClusterId := acceptedToken.io.tokenClusterId
  identityMatch.io.selectedEntryId := acceptedToken.io.tokenEntryId
  identityMatch.io.responseClusterId := responseQueue.io.headClusterId
  identityMatch.io.responseEntryId := responseQueue.io.headEntryId

  responseMatch.io.enable := io.enable
  responseMatch.io.flush := io.flush
  responseMatch.io.queryIssued := acceptedToken.io.tokenValid
  responseMatch.io.responseValidIn := responseQueue.io.headValid
  responseMatch.io.responseMatchesSelected := identityMatch.io.responseMatchesSelected
  responseMatch.io.scbReturned := io.scbReturned || responseHeadState.io.reducedHeadScbReturned
  responseMatch.io.waitStoreIn := responseQueue.io.headWaitStore
  responseMatch.io.dataValidIn := responseQueue.io.headDataValid

  responseHeadState.io.enable := io.enable
  responseHeadState.io.flush := io.flush
  responseHeadState.io.reducedEnable := io.selectedIdentityEnable
  responseHeadState.io.headValid := responseQueue.io.headValid
  responseHeadState.io.responseClusterId := responseQueue.io.headClusterId
  responseHeadState.io.responseEntryId := responseQueue.io.headEntryId
  responseHeadState.io.repickMask := io.selectedRepickMask
  responseHeadState.io.rowProofEnable := io.selectedIdentityEnable
  responseHeadState.io.rowValidMask := io.selectedRowValidMask
  responseHeadState.io.rowScbReturnedMask := io.selectedRowScbReturnedMask
  responseHeadState.io.externalHeadStale := io.responseHeadStale

  responseDrain.io.enable := io.enable
  responseDrain.io.flush := io.flush
  responseDrain.io.headValid := responseQueue.io.headValid
  responseDrain.io.orderedResponse := responseMatch.io.responseValid
  responseDrain.io.headStale := responseHeadState.io.headStale

  responseApply.io.enable := io.enable
  responseApply.io.flush := io.flush
  responseApply.io.orderedConsumed := responseDrain.io.orderedConsumed
  responseApply.io.targetRepick := responseHeadState.io.reducedHeadRepick
  responseApply.io.targetOneHot := responseHeadState.io.reducedHeadOneHot
  responseApply.io.response := responseQueue.io.head
  responseApply.io.rowLineData := acceptedToken.io.tokenLineData
  responseApply.io.rowValidMask := acceptedToken.io.tokenValidMask
  responseApply.io.rowRequestMask := acceptedToken.io.tokenRequestByteMask

  val acceptedContextComplete =
    (acceptedToken.io.tokenRequestByteMask =/= 0.U) &&
      ((acceptedToken.io.tokenValidMask & acceptedToken.io.tokenRequestByteMask) === acceptedToken.io.tokenRequestByteMask)

  rowStatePlan.io.enable := io.enable
  rowStatePlan.io.flush := io.flush
  rowStatePlan.io.applyValid := responseApply.io.applyValid
  rowStatePlan.io.applyStqReturned := responseApply.io.stqReturned
  rowStatePlan.io.waitStoreApply := responseApply.io.waitStoreApply
  rowStatePlan.io.waitStoreInfo := responseApply.io.waitStoreInfo
  rowStatePlan.io.waitStoreRid := responseApply.io.waitStoreRid
  rowStatePlan.io.dataMergeApply := responseApply.io.dataMergeApply
  rowStatePlan.io.dataNoMerge := responseApply.io.dataNoMerge
  rowStatePlan.io.priorScbReturned := io.scbReturned || responseHeadState.io.reducedHeadScbReturned
  rowStatePlan.io.priorStqReturned := false.B
  rowStatePlan.io.priorLineData := acceptedToken.io.tokenLineData
  rowStatePlan.io.priorValidMask := acceptedToken.io.tokenValidMask
  rowStatePlan.io.priorRequestComplete := acceptedContextComplete
  rowStatePlan.io.mergedLineData := responseApply.io.mergedLineData
  rowStatePlan.io.mergedValidMask := responseApply.io.mergedValidMask
  rowStatePlan.io.mergedRequestComplete := responseApply.io.mergedRequestComplete

  rowMutationRequest.io.enable := io.enable
  rowMutationRequest.io.flush := io.flush
  rowMutationRequest.io.liveEnable := io.rowMutationLiveEnable
  rowMutationRequest.io.planValid := rowStatePlan.io.rowWriteValid
  rowMutationRequest.io.targetMask := responseApply.io.targetMask
  rowMutationRequest.io.setWaitStatus := rowStatePlan.io.setWaitStatus
  rowMutationRequest.io.keepRepickStatus := rowStatePlan.io.keepRepickStatus
  rowMutationRequest.io.clearReturnState := rowStatePlan.io.clearReturnState
  rowMutationRequest.io.lineWrite := rowStatePlan.io.lineWrite
  rowMutationRequest.io.waitStoreWrite := rowStatePlan.io.waitStoreWrite
  rowMutationRequest.io.nextWaitStore := rowStatePlan.io.nextWaitStore
  rowMutationRequest.io.nextWaitStoreInfo := rowStatePlan.io.nextWaitStoreInfo
  rowMutationRequest.io.nextWaitStoreRid := rowStatePlan.io.nextWaitStoreRid
  rowMutationRequest.io.nextLineData := rowStatePlan.io.nextLineData
  rowMutationRequest.io.nextValidMask := rowStatePlan.io.nextValidMask
  rowMutationRequest.io.nextDataComplete := rowStatePlan.io.nextDataComplete
  rowMutationRequest.io.nextScbReturned := rowStatePlan.io.nextScbReturned
  rowMutationRequest.io.nextStqReturned := rowStatePlan.io.nextStqReturned
  rowMutationRequest.io.nextStoreSourceReturned := rowStatePlan.io.nextStoreSourceReturned

  evidence.io.enable := io.enable
  evidence.io.flush := io.flush
  evidence.io.launchValid := io.launchValid
  evidence.io.queryIssued := acceptedToken.io.tokenValid
  evidence.io.responseValid := responseMatch.io.responseValid
  evidence.io.waitStore := responseMatch.io.waitStore
  evidence.io.dataValid := responseMatch.io.dataValid

  control.io.enable := io.enable
  control.io.flush := io.flush
  control.io.requestEnable := requestControl.io.queryRequestEnable
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

  io.rawResponseSourceActive := rawResponseSource.io.active
  io.rawResponseSourceCandidate := rawResponseSource.io.candidate
  io.rawResponseSourceValid := rawResponseSource.io.responseValid
  io.rawResponseSourceBlockedByDisabled := rawResponseSource.io.blockedByDisabled
  io.rawResponseSourceBlockedByFlush := rawResponseSource.io.blockedByFlush
  io.rawResponseSourceBlockedByLiveDisabled := rawResponseSource.io.blockedByLiveDisabled
  io.rawResponseSourceInvalidDataWithWaitStore := rawResponseSource.io.invalidDataWithWaitStore
  io.rawResponseSourceInvalidDataValidWithoutRawData := rawResponseSource.io.invalidDataValidWithoutRawData
  io.rawResponseSourceInvalidSuppressedDataWithoutWait := rawResponseSource.io.invalidSuppressedDataWithoutWait
  io.rawResponseSourceInvalidSuppressedDataWithoutRawData := rawResponseSource.io.invalidSuppressedDataWithoutRawData

  io.identityMatchActive := identityMatch.io.active
  io.identityMatchCandidate := identityMatch.io.matchCandidate
  io.identityMatchSelectedReady := identityMatch.io.selectedReady
  io.identityMatchClusterMatches := identityMatch.io.clusterMatches
  io.identityMatchEntryMatches := identityMatch.io.entryMatches
  io.identityMatchIdentityMatches := identityMatch.io.identityMatches
  io.identityMatchResponseMatchesSelected := identityMatch.io.responseMatchesSelected
  io.identityMatchBlockedByDisabled := identityMatch.io.blockedByDisabled
  io.identityMatchBlockedByFlush := identityMatch.io.blockedByFlush
  io.identityMatchBlockedByNoQuery := identityMatch.io.blockedByNoQuery
  io.identityMatchBlockedByNoSelected := identityMatch.io.blockedByNoSelected
  io.identityMatchBlockedByStaleRow := identityMatch.io.blockedByStaleRow
  io.identityMatchBlockedByClusterMismatch := identityMatch.io.blockedByClusterMismatch
  io.identityMatchBlockedByEntryMismatch := identityMatch.io.blockedByEntryMismatch
  io.identityMatchInvalidResponseWithoutQuery := identityMatch.io.invalidResponseWithoutQuery

  io.responseMatchActive := responseMatch.io.active
  io.responseMatchCandidate := responseMatch.io.responseCandidate
  io.responseMatchMatched := responseMatch.io.responseMatched
  io.responseMatchOrdered := responseMatch.io.responseOrdered
  io.responseMatchValid := responseMatch.io.responseValid
  io.responseMatchWaitStore := responseMatch.io.waitStore
  io.responseMatchDataValid := responseMatch.io.dataValid
  io.responseMatchBlockedByDisabled := responseMatch.io.blockedByDisabled
  io.responseMatchBlockedByFlush := responseMatch.io.blockedByFlush
  io.responseMatchBlockedByNoQuery := responseMatch.io.blockedByNoQuery
  io.responseMatchBlockedByNoMatch := responseMatch.io.blockedByNoMatch
  io.responseMatchBlockedByScbOrder := responseMatch.io.blockedByScbOrder
  io.responseMatchInvalidResponseWithoutQuery := responseMatch.io.invalidResponseWithoutQuery
  io.responseMatchInvalidDataWithWaitStore := responseMatch.io.invalidDataWithWaitStore

  io.responseApplyActive := responseApply.io.active
  io.responseApplyCandidate := responseApply.io.applyCandidate
  io.responseApplyValid := responseApply.io.applyValid
  io.responseApplyStqReturned := responseApply.io.stqReturned
  io.responseApplyTargetMask := responseApply.io.targetMask
  io.responseApplyWaitStore := responseApply.io.waitStoreApply
  io.responseApplyWaitStoreInfo := responseApply.io.waitStoreInfo
  io.responseApplyWaitStoreRid := responseApply.io.waitStoreRid
  io.responseApplyDataMerge := responseApply.io.dataMergeApply
  io.responseApplyDataNoMerge := responseApply.io.dataNoMerge
  io.responseApplyMergedValidMask := responseApply.io.mergedValidMask
  io.responseApplyMergedLineData := responseApply.io.mergedLineData
  io.responseApplyMergedRequestComplete := responseApply.io.mergedRequestComplete
  io.responseApplyBlockedByNoOrderedResponse := responseApply.io.blockedByNoOrderedResponse
  io.responseApplyBlockedByNotRepick := responseApply.io.blockedByNotRepick
  io.responseApplyBlockedByWaitStore := responseApply.io.blockedByWaitStore
  io.responseApplyBlockedByNoData := responseApply.io.blockedByNoData
  io.responseApplyInvalidOrderedWithoutPayload := responseApply.io.invalidOrderedWithoutPayload
  io.responseApplyInvalidDataWithWaitStore := responseApply.io.invalidDataWithWaitStore
  io.responseApplyInvalidDataValidWithoutRawData := responseApply.io.invalidDataValidWithoutRawData
  io.responseApplyInvalidSuppressedDataWithoutWait := responseApply.io.invalidSuppressedDataWithoutWait
  io.rowStatePlanActive := rowStatePlan.io.active
  io.rowStatePlanValid := rowStatePlan.io.planValid
  io.rowStatePlanRewait := rowStatePlan.io.rewaitApply
  io.rowStatePlanDataMerge := rowStatePlan.io.dataMergePlan
  io.rowStatePlanDataNoMerge := rowStatePlan.io.dataNoMergePlan
  io.rowStatePlanSetWaitStatus := rowStatePlan.io.setWaitStatus
  io.rowStatePlanKeepRepickStatus := rowStatePlan.io.keepRepickStatus
  io.rowStatePlanClearReturnState := rowStatePlan.io.clearReturnState
  io.rowStatePlanLineWrite := rowStatePlan.io.lineWrite
  io.rowStatePlanWaitStoreWrite := rowStatePlan.io.waitStoreWrite
  io.rowStatePlanNextWaitStore := rowStatePlan.io.nextWaitStore
  io.rowStatePlanNextWaitStoreInfo := rowStatePlan.io.nextWaitStoreInfo
  io.rowStatePlanNextWaitStoreRid := rowStatePlan.io.nextWaitStoreRid
  io.rowStatePlanNextLineData := rowStatePlan.io.nextLineData
  io.rowStatePlanNextValidMask := rowStatePlan.io.nextValidMask
  io.rowStatePlanNextDataComplete := rowStatePlan.io.nextDataComplete
  io.rowStatePlanNextScbReturned := rowStatePlan.io.nextScbReturned
  io.rowStatePlanNextStqReturned := rowStatePlan.io.nextStqReturned
  io.rowStatePlanNextStoreSourceReturned := rowStatePlan.io.nextStoreSourceReturned
  io.rowStatePlanBlockedByNoApply := rowStatePlan.io.blockedByNoApply
  io.rowStatePlanInvalidApplyWithoutStqReturned := rowStatePlan.io.invalidApplyWithoutStqReturned
  io.rowStatePlanInvalidStqReturnedWithoutApply := rowStatePlan.io.invalidStqReturnedWithoutApply
  io.rowStatePlanInvalidStqApplyWithoutScb := rowStatePlan.io.invalidStqApplyWithoutScb
  io.rowMutationActive := rowMutationRequest.io.active
  io.rowMutationCandidateValid := rowMutationRequest.io.candidateValid
  io.rowMutationCandidateTargetMask := rowMutationRequest.io.candidateTargetMask
  io.rowMutationCandidateTargetCount := rowMutationRequest.io.candidateTargetCount
  io.rowMutationCandidateTargetIndex := rowMutationRequest.io.candidateTargetIndex
  io.rowMutationTargetReady := rowMutationRequest.io.targetReady
  io.rowMutationRequestValid := rowMutationRequest.io.requestValid
  io.rowMutationRequestTargetMask := rowMutationRequest.io.requestTargetMask
  io.rowMutationRequestTargetIndex := rowMutationRequest.io.requestTargetIndex
  io.rowMutationStatusWrite := rowMutationRequest.io.statusWrite
  io.rowMutationSetWaitStatus := rowMutationRequest.io.setWaitStatusOut
  io.rowMutationKeepRepickStatus := rowMutationRequest.io.keepRepickStatusOut
  io.rowMutationReturnStateWrite := rowMutationRequest.io.returnStateWrite
  io.rowMutationClearReturnState := rowMutationRequest.io.clearReturnStateOut
  io.rowMutationLineWrite := rowMutationRequest.io.lineWriteOut
  io.rowMutationWaitStoreWrite := rowMutationRequest.io.waitStoreWriteOut
  io.rowMutationNextWaitStore := rowMutationRequest.io.nextWaitStoreOut
  io.rowMutationNextWaitStoreInfo := rowMutationRequest.io.nextWaitStoreInfoOut
  io.rowMutationNextWaitStoreRid := rowMutationRequest.io.nextWaitStoreRidOut
  io.rowMutationNextLineData := rowMutationRequest.io.nextLineDataOut
  io.rowMutationNextValidMask := rowMutationRequest.io.nextValidMaskOut
  io.rowMutationNextDataComplete := rowMutationRequest.io.nextDataCompleteOut
  io.rowMutationNextScbReturned := rowMutationRequest.io.nextScbReturnedOut
  io.rowMutationNextStqReturned := rowMutationRequest.io.nextStqReturnedOut
  io.rowMutationNextStoreSourceReturned := rowMutationRequest.io.nextStoreSourceReturnedOut
  io.rowMutationBlockedByNoPlan := rowMutationRequest.io.blockedByNoPlan
  io.rowMutationBlockedByNoTarget := rowMutationRequest.io.blockedByNoTarget
  io.rowMutationBlockedByLiveDisabled := rowMutationRequest.io.blockedByLiveDisabled
  io.rowMutationInvalidMultiTarget := rowMutationRequest.io.invalidMultiTarget
  io.rowMutationInvalidWriteWithoutPlan := rowMutationRequest.io.invalidWriteWithoutPlan
  io.rowMutationInvalidWaitStoreWithoutWaitStatus := rowMutationRequest.io.invalidWaitStoreWithoutWaitStatus
  io.rowMutationInvalidReturnWithoutSplitSources := rowMutationRequest.io.invalidReturnWithoutSplitSources
  io.rowMutationInvalidConflictingStatusWrite := rowMutationRequest.io.invalidConflictingStatusWrite

  io.queryIssueActive := queryIssue.io.active
  io.queryIssueRequestActive := queryIssue.io.requestActive
  io.queryIssueCandidate := queryIssue.io.queryCandidate
  io.queryIssueValid := queryIssue.io.queryValid
  io.queryIssueIssued := queryIssue.io.queryIssued
  io.queryIssueBlockedByRequestDisabled := queryIssue.io.blockedByRequestDisabled
  io.queryIssueBlockedByNoLaunch := queryIssue.io.blockedByNoLaunch
  io.queryIssueBlockedBySink := queryIssue.io.blockedBySink
  io.requestControlBlockedByToken := requestControl.io.blockedByToken

  io.acceptedTokenCanAccept := acceptedToken.io.tokenCanAccept
  io.acceptedTokenValid := acceptedToken.io.tokenValid
  io.acceptedTokenResidentValid := acceptedToken.io.residentTokenValid
  io.acceptedTokenCaptureCandidate := acceptedToken.io.captureCandidate
  io.acceptedTokenCaptureAccepted := acceptedToken.io.captureAccepted
  io.acceptedTokenClearAccepted := acceptedToken.io.clearAccepted
  io.acceptedTokenPrecisePruned := acceptedToken.io.precisePruned
  io.acceptedTokenBlockedByPreciseFlush := acceptedToken.io.blockedByPreciseFlush
  io.acceptedTokenBlockedByOutstanding := acceptedToken.io.blockedByOutstandingToken
  io.acceptedTokenClusterId := acceptedToken.io.tokenClusterId
  io.acceptedTokenEntryId := acceptedToken.io.tokenEntryId

  io.requestPayloadActive := requestPayload.io.active
  io.requestPayloadCaptureCandidate := requestPayload.io.captureCandidate
  io.requestPayloadValid := requestPayload.io.requestValid
  io.requestPayload := requestPayload.io.request
  io.requestPayloadBlockedByNoIssue := requestPayload.io.blockedByNoIssue
  io.requestPayloadBlockedByNoSelected := requestPayload.io.blockedByNoSelected
  io.requestPayloadBlockedByStaleRow := requestPayload.io.blockedByStaleRow

  io.requestQueueEnqueueReady := requestQueue.io.enqueueReady
  io.requestQueueEnqueueAccepted := requestQueue.io.enqueueAccepted
  io.requestQueueEnqueueDropped := requestQueue.io.enqueueDropped
  io.requestQueueHeadValid := requestQueue.io.headValid
  io.requestQueueHead := requestQueue.io.head
  io.requestQueueHeadConsumed := requestQueue.io.headConsumed
  io.requestQueuePending := requestQueue.io.pending
  io.requestQueueFull := requestQueue.io.full
  io.requestQueueEmpty := requestQueue.io.empty
  io.requestQueueCount := requestQueue.io.count
  io.requestQueueBlockedByDisabled := requestQueue.io.blockedByDisabled
  io.requestQueueBlockedByFlush := requestQueue.io.blockedByFlush
  io.requestQueuePrecisePruneMask := requestQueue.io.precisePruneMask
  io.requestQueuePrecisePruneCount := requestQueue.io.precisePruneCount
  io.requestQueueBlockedByPreciseFlush := requestQueue.io.blockedByPreciseFlush
  io.requestQueueBlockedByFull := requestQueue.io.blockedByFull
  io.requestSinkActive := requestSink.io.active
  io.requestSinkCandidate := requestSink.io.requestCandidate
  io.requestSinkReady := requestSink.io.requestReady
  io.requestSinkAccepted := requestSink.io.requestAccepted
  io.requestSinkResponseValid := requestSink.io.responseValid
  io.requestSinkBlockedByDisabled := requestSink.io.blockedByDisabled
  io.requestSinkBlockedByFlush := requestSink.io.blockedByFlush
  io.requestSinkBlockedByNoRequest := requestSink.io.blockedByNoRequest
  io.requestSinkBlockedByRawSink := requestSink.io.blockedByRawSink
  io.requestSinkBlockedByResponse := requestSink.io.blockedByResponse
  io.requestSinkInvalidDataWithWaitStore := requestSink.io.invalidDataWithWaitStore
  io.responseQueueEnqueueReady := responseQueue.io.enqueueReady
  io.responseQueueEnqueueAccepted := responseQueue.io.enqueueAccepted
  io.responseQueueEnqueueDropped := responseQueue.io.enqueueDropped
  io.responseQueueHeadValid := responseQueue.io.headValid
  io.responseQueueHeadConsumed := responseQueue.io.headConsumed
  io.responseQueuePending := responseQueue.io.pending
  io.responseQueueFull := responseQueue.io.full
  io.responseQueueEmpty := responseQueue.io.empty
  io.responseQueueCount := responseQueue.io.count
  io.responseQueuePrecisePruneMask := responseQueue.io.precisePruneMask
  io.responseQueuePrecisePruneCount := responseQueue.io.precisePruneCount
  io.responseQueueBlockedByPreciseFlush := responseQueue.io.blockedByPreciseFlush
  io.responseQueueBlockedByDisabled := responseQueue.io.blockedByDisabled
  io.responseQueueBlockedByFlush := responseQueue.io.blockedByFlush
  io.responseQueueBlockedByFull := responseQueue.io.blockedByFull
  io.responseDrainActive := responseDrain.io.active
  io.responseDrainDequeueReady := responseDrain.io.dequeueReady
  io.responseDrainOrderedConsumed := responseDrain.io.orderedConsumed
  io.responseDrainStaleDropped := responseDrain.io.staleDropped
  io.responseDrainBlockedByNoHead := responseDrain.io.blockedByNoHead
  io.responseDrainBlockedByNoAction := responseDrain.io.blockedByNoAction
  io.responseDrainBlockedByDisabled := responseDrain.io.blockedByDisabled
  io.responseDrainBlockedByFlush := responseDrain.io.blockedByFlush
  io.responseDrainInvalidStaleWithOrdered := responseDrain.io.invalidStaleWithOrdered
  io.lookupActive := lookup.io.active
  io.lookupQueryValid := lookup.io.queryValid
  io.lookupLoadCrossesLine := lookup.io.loadCrossesLine
  io.lookupRequestMaskMismatch := lookup.io.requestMaskMismatch
  io.lookupStoreSnapshotValidMask := lookup.io.storeSnapshotValidMask
  io.lookupStoreSnapshotWaitMask := lookup.io.storeSnapshotWaitMask
  io.lookupStoreSnapshotCrossLineMask := lookup.io.storeSnapshotCrossLineMask
  io.lookupEligibleStoreMask := lookup.io.eligibleStoreMask
  io.lookupForwardMask := lookup.io.forwardMask
  io.lookupWaitMask := lookup.io.waitMask
  io.lookupWaitStoreValid := lookup.io.waitStoreValid
  io.lookupRawDataValid := lookup.io.rawDataValid
  io.lookupResponseDataValid := lookup.io.responseDataValid
  io.lookupDataSuppressedByWait := lookup.io.dataSuppressedByWait
  io.lookupStoreBypassComplete := lookup.io.storeBypassComplete
}
