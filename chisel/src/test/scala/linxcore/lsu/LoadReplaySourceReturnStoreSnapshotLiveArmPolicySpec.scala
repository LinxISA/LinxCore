package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplaySourceReturnStoreSnapshotLiveArmPolicyReference {
  final case class Result(
      active: Boolean,
      requestCandidate: Boolean,
      requestEnable: Boolean,
      sinkCandidate: Boolean,
      sinkReady: Boolean,
      responsePortBlocked: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByPolicyDisabled: Boolean,
      requestBlockedByNoLaunch: Boolean,
      requestBlockedByRowMutationDisabled: Boolean,
      requestBlockedByRequestQueue: Boolean,
      requestBlockedByAcceptedToken: Boolean,
      sinkBlockedByNoRequest: Boolean,
      sinkBlockedByRowMutationDisabled: Boolean,
      sinkBlockedByRawSink: Boolean,
      responseBlockedByQueueFull: Boolean,
      responseBlockedByRawResponse: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      policyEnable: Boolean,
      rowMutationLiveEnable: Boolean,
      launchValid: Boolean = false,
      requestQueueCanAccept: Boolean = false,
      acceptedTokenCanAccept: Boolean = false,
      requestHeadValid: Boolean = false,
      rawSinkAvailable: Boolean = false,
      responseQueueFull: Boolean = false,
      rawResponseValid: Boolean = false): Result = {
    val active = enable && !flush
    val policyActive = active && policyEnable
    val requestCandidate = policyActive && launchValid
    val requestStorageReady = requestQueueCanAccept && acceptedTokenCanAccept
    val requestEnable = requestCandidate && rowMutationLiveEnable && requestStorageReady
    val sinkHasRequest = requestHeadValid || requestEnable
    val sinkCandidate = policyActive && sinkHasRequest
    val sinkReady = sinkCandidate && rowMutationLiveEnable && rawSinkAvailable
    val responsePortBlocked = sinkCandidate && (responseQueueFull || rawResponseValid)
    val rawIntent =
      policyEnable ||
        rowMutationLiveEnable ||
        launchValid ||
        requestHeadValid ||
        rawSinkAvailable ||
        responseQueueFull ||
        rawResponseValid

    Result(
      active = active,
      requestCandidate = requestCandidate,
      requestEnable = requestEnable,
      sinkCandidate = sinkCandidate,
      sinkReady = sinkReady,
      responsePortBlocked = responsePortBlocked,
      blockedByDisabled = !enable && rawIntent,
      blockedByFlush = enable && flush && rawIntent,
      blockedByPolicyDisabled = active && !policyEnable && rawIntent,
      requestBlockedByNoLaunch = policyActive && !launchValid,
      requestBlockedByRowMutationDisabled = requestCandidate && !rowMutationLiveEnable,
      requestBlockedByRequestQueue = requestCandidate && rowMutationLiveEnable && !requestQueueCanAccept,
      requestBlockedByAcceptedToken =
        requestCandidate && rowMutationLiveEnable && requestQueueCanAccept && !acceptedTokenCanAccept,
      sinkBlockedByNoRequest = policyActive && !sinkHasRequest,
      sinkBlockedByRowMutationDisabled = sinkCandidate && !rowMutationLiveEnable,
      sinkBlockedByRawSink = sinkCandidate && rowMutationLiveEnable && !rawSinkAvailable,
      responseBlockedByQueueFull = sinkCandidate && responseQueueFull,
      responseBlockedByRawResponse = sinkCandidate && rawResponseValid)
  }
}

class LoadReplaySourceReturnStoreSnapshotLiveArmPolicySpec extends AnyFunSuite {
  import LoadReplaySourceReturnStoreSnapshotLiveArmPolicyReference._

  test("arms request and sink when row mutation and local capacities are ready") {
    val result = LoadReplaySourceReturnStoreSnapshotLiveArmPolicyReference(
      enable = true,
      flush = false,
      policyEnable = true,
      rowMutationLiveEnable = true,
      launchValid = true,
      requestQueueCanAccept = true,
      acceptedTokenCanAccept = true,
      rawSinkAvailable = true)

    assert(result.active)
    assert(result.requestCandidate)
    assert(result.requestEnable)
    assert(result.sinkCandidate)
    assert(result.sinkReady)
    assert(!result.responsePortBlocked)
  }

  test("row mutation live enable is a hard safety gate") {
    val result = LoadReplaySourceReturnStoreSnapshotLiveArmPolicyReference(
      enable = true,
      flush = false,
      policyEnable = true,
      rowMutationLiveEnable = false,
      launchValid = true,
      requestQueueCanAccept = true,
      acceptedTokenCanAccept = true,
      requestHeadValid = true,
      rawSinkAvailable = true)

    assert(result.requestCandidate)
    assert(!result.requestEnable)
    assert(result.requestBlockedByRowMutationDisabled)
    assert(result.sinkCandidate)
    assert(!result.sinkReady)
    assert(result.sinkBlockedByRowMutationDisabled)
  }

  test("distinguishes request queue and accepted-token request blockers") {
    val queueBlocked = LoadReplaySourceReturnStoreSnapshotLiveArmPolicyReference(
      enable = true,
      flush = false,
      policyEnable = true,
      rowMutationLiveEnable = true,
      launchValid = true,
      requestQueueCanAccept = false,
      acceptedTokenCanAccept = true)
    val tokenBlocked = LoadReplaySourceReturnStoreSnapshotLiveArmPolicyReference(
      enable = true,
      flush = false,
      policyEnable = true,
      rowMutationLiveEnable = true,
      launchValid = true,
      requestQueueCanAccept = true,
      acceptedTokenCanAccept = false)

    assert(!queueBlocked.requestEnable)
    assert(queueBlocked.requestBlockedByRequestQueue)
    assert(!queueBlocked.requestBlockedByAcceptedToken)
    assert(!tokenBlocked.requestEnable)
    assert(!tokenBlocked.requestBlockedByRequestQueue)
    assert(tokenBlocked.requestBlockedByAcceptedToken)
  }

  test("resident request sink arm stays separate from response-port blockers") {
    val result = LoadReplaySourceReturnStoreSnapshotLiveArmPolicyReference(
      enable = true,
      flush = false,
      policyEnable = true,
      rowMutationLiveEnable = true,
      requestHeadValid = true,
      rawSinkAvailable = true,
      responseQueueFull = true,
      rawResponseValid = true)

    assert(!result.requestCandidate)
    assert(!result.requestEnable)
    assert(result.requestBlockedByNoLaunch)
    assert(result.sinkCandidate)
    assert(result.sinkReady)
    assert(result.responsePortBlocked)
    assert(result.responseBlockedByQueueFull)
    assert(result.responseBlockedByRawResponse)
  }

  test("sink arm reports absent request and raw-sink backpressure") {
    val noRequest = LoadReplaySourceReturnStoreSnapshotLiveArmPolicyReference(
      enable = true,
      flush = false,
      policyEnable = true,
      rowMutationLiveEnable = true,
      launchValid = false,
      requestHeadValid = false,
      rawSinkAvailable = true)
    val rawSinkBlocked = LoadReplaySourceReturnStoreSnapshotLiveArmPolicyReference(
      enable = true,
      flush = false,
      policyEnable = true,
      rowMutationLiveEnable = true,
      requestHeadValid = true,
      rawSinkAvailable = false)

    assert(noRequest.requestBlockedByNoLaunch)
    assert(noRequest.sinkBlockedByNoRequest)
    assert(!noRequest.sinkCandidate)
    assert(rawSinkBlocked.sinkCandidate)
    assert(!rawSinkBlocked.sinkReady)
    assert(rawSinkBlocked.sinkBlockedByRawSink)
  }

  test("disabled, flush, and policy-disabled states suppress live arms") {
    val disabled = LoadReplaySourceReturnStoreSnapshotLiveArmPolicyReference(
      enable = false,
      flush = false,
      policyEnable = true,
      rowMutationLiveEnable = true,
      launchValid = true,
      requestHeadValid = true,
      rawSinkAvailable = true)
    val flushed = LoadReplaySourceReturnStoreSnapshotLiveArmPolicyReference(
      enable = true,
      flush = true,
      policyEnable = true,
      rowMutationLiveEnable = true,
      launchValid = true,
      requestHeadValid = true,
      rawSinkAvailable = true)
    val policyDisabled = LoadReplaySourceReturnStoreSnapshotLiveArmPolicyReference(
      enable = true,
      flush = false,
      policyEnable = false,
      rowMutationLiveEnable = true,
      launchValid = true,
      requestHeadValid = true,
      rawSinkAvailable = true)

    assert(!disabled.active)
    assert(disabled.blockedByDisabled)
    assert(!disabled.requestEnable)
    assert(!flushed.active)
    assert(flushed.blockedByFlush)
    assert(!flushed.sinkReady)
    assert(policyDisabled.active)
    assert(policyDisabled.blockedByPolicyDisabled)
    assert(!policyDisabled.requestEnable)
    assert(!policyDisabled.sinkReady)
  }

  test("Chisel LoadReplaySourceReturnStoreSnapshotLiveArmPolicy elaborates the policy owner") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplaySourceReturnStoreSnapshotLiveArmPolicy)

    assert(sv.contains("module LoadReplaySourceReturnStoreSnapshotLiveArmPolicy"))
    assert(sv.contains("io_requestEnable"))
    assert(sv.contains("io_sinkReady"))
    assert(sv.contains("io_requestBlockedByRowMutationDisabled"))
    assert(sv.contains("io_requestBlockedByAcceptedToken"))
    assert(sv.contains("io_sinkBlockedByRawSink"))
    assert(sv.contains("io_responseBlockedByRawResponse"))
  }
}
