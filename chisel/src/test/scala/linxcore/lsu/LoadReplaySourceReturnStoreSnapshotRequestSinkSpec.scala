package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplaySourceReturnStoreSnapshotRequestSinkReference {
  import LoadReplaySourceReturnStoreSnapshotRequestPayloadReference.Payload

  final case class Result(
      active: Boolean,
      requestCandidate: Boolean,
      requestReady: Boolean,
      requestAccepted: Boolean,
      responseValid: Boolean,
      responseClusterId: Int,
      responseEntryId: Int,
      responseWaitStore: Boolean,
      responseDataValid: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByNoRequest: Boolean,
      blockedByRawSink: Boolean,
      blockedByResponse: Boolean,
      invalidDataWithWaitStore: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      request: Option[Payload] = None,
      rawSinkReady: Boolean = false,
      responseReady: Boolean = false,
      lookupWaitStore: Boolean = false,
      lookupDataValid: Boolean = false): Result = {
    val active = enable && !flush
    val hasRequest = request.exists(_.valid)
    val requestCandidate = active && hasRequest
    val requestReady = active && rawSinkReady && responseReady
    val requestAccepted = requestCandidate && requestReady
    val rawRequest = hasRequest || lookupWaitStore || lookupDataValid

    Result(
      active = active,
      requestCandidate = requestCandidate,
      requestReady = requestReady,
      requestAccepted = requestAccepted,
      responseValid = requestAccepted,
      responseClusterId = if (requestAccepted) request.get.clusterId else 0,
      responseEntryId = if (requestAccepted) request.get.entryId else 0,
      responseWaitStore = requestAccepted && lookupWaitStore,
      responseDataValid = requestAccepted && lookupDataValid,
      blockedByDisabled = !enable && rawRequest,
      blockedByFlush = enable && flush && rawRequest,
      blockedByNoRequest = active && request.isEmpty && (rawSinkReady || responseReady),
      blockedByRawSink = requestCandidate && !rawSinkReady,
      blockedByResponse = requestCandidate && rawSinkReady && !responseReady,
      invalidDataWithWaitStore = requestAccepted && lookupWaitStore && lookupDataValid)
  }
}

class LoadReplaySourceReturnStoreSnapshotRequestSinkSpec extends AnyFunSuite {
  import LoadReplaySourceReturnStoreSnapshotRequestPayloadReference.Payload
  import LoadReplaySourceReturnStoreSnapshotRequestSinkReference._

  private def request(idx: Int): Payload =
    Payload(
      valid = true,
      clusterId = 0,
      entryId = idx,
      loadId = idx,
      bid = 4 + idx,
      gid = 1,
      rid = 8 + idx,
      loadLsId = 16 + idx,
      pc = BigInt("400055f0", 16) + (idx * 4),
      addr = BigInt("40012000", 16) + (idx * 8),
      size = 8,
      requestByteMask = BigInt("ff", 16) << (idx * 8))

  test("accepts a queued request only when raw sink and response queue are both ready") {
    val result = LoadReplaySourceReturnStoreSnapshotRequestSinkReference(
      enable = true,
      flush = false,
      request = Some(request(2)),
      rawSinkReady = true,
      responseReady = true)

    assert(result.active)
    assert(result.requestCandidate)
    assert(result.requestReady)
    assert(result.requestAccepted)
    assert(result.responseValid)
    assert(result.responseClusterId == 0)
    assert(result.responseEntryId == 2)
    assert(!result.responseWaitStore)
    assert(!result.responseDataValid)
  }

  test("holds the request when the raw store-unit sink is not ready") {
    val result = LoadReplaySourceReturnStoreSnapshotRequestSinkReference(
      enable = true,
      flush = false,
      request = Some(request(1)),
      rawSinkReady = false,
      responseReady = true)

    assert(result.requestCandidate)
    assert(!result.requestReady)
    assert(!result.requestAccepted)
    assert(!result.responseValid)
    assert(result.blockedByRawSink)
    assert(!result.blockedByResponse)
  }

  test("holds the request when the response queue cannot accept the returned identity") {
    val result = LoadReplaySourceReturnStoreSnapshotRequestSinkReference(
      enable = true,
      flush = false,
      request = Some(request(1)),
      rawSinkReady = true,
      responseReady = false)

    assert(result.requestCandidate)
    assert(!result.requestReady)
    assert(!result.requestAccepted)
    assert(!result.responseValid)
    assert(!result.blockedByRawSink)
    assert(result.blockedByResponse)
  }

  test("passes lookup wait-store and data-valid diagnostics to the response shape") {
    val wait = LoadReplaySourceReturnStoreSnapshotRequestSinkReference(
      enable = true,
      flush = false,
      request = Some(request(3)),
      rawSinkReady = true,
      responseReady = true,
      lookupWaitStore = true,
      lookupDataValid = false)
    val invalid = LoadReplaySourceReturnStoreSnapshotRequestSinkReference(
      enable = true,
      flush = false,
      request = Some(request(3)),
      rawSinkReady = true,
      responseReady = true,
      lookupWaitStore = true,
      lookupDataValid = true)

    assert(wait.responseValid)
    assert(wait.responseWaitStore)
    assert(!wait.responseDataValid)
    assert(!wait.invalidDataWithWaitStore)
    assert(invalid.responseValid)
    assert(invalid.responseWaitStore)
    assert(invalid.responseDataValid)
    assert(invalid.invalidDataWithWaitStore)
  }

  test("disabled and flush states suppress request acceptance") {
    val disabled = LoadReplaySourceReturnStoreSnapshotRequestSinkReference(
      enable = false,
      flush = false,
      request = Some(request(1)),
      rawSinkReady = true,
      responseReady = true)
    val flushed = LoadReplaySourceReturnStoreSnapshotRequestSinkReference(
      enable = true,
      flush = true,
      request = Some(request(1)),
      rawSinkReady = true,
      responseReady = true)

    assert(!disabled.active)
    assert(!disabled.requestAccepted)
    assert(disabled.blockedByDisabled)
    assert(!flushed.active)
    assert(!flushed.requestAccepted)
    assert(flushed.blockedByFlush)
  }

  test("Chisel LoadReplaySourceReturnStoreSnapshotRequestSink elaborates the request-to-response owner") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplaySourceReturnStoreSnapshotRequestSink)

    assert(sv.contains("module LoadReplaySourceReturnStoreSnapshotRequestSink"))
    assert(sv.contains("io_requestReady"))
    assert(sv.contains("io_requestAccepted"))
    assert(sv.contains("io_responseValid"))
    assert(sv.contains("io_blockedByResponse"))
  }
}
