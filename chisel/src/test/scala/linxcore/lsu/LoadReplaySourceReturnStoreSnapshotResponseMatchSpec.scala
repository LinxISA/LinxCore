package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplaySourceReturnStoreSnapshotResponseMatchReference {
  final case class Result(
      active: Boolean,
      responseCandidate: Boolean,
      responseMatched: Boolean,
      responseOrdered: Boolean,
      responseValid: Boolean,
      waitStore: Boolean,
      dataValid: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByNoQuery: Boolean,
      blockedByNoMatch: Boolean,
      blockedByScbOrder: Boolean,
      invalidResponseWithoutQuery: Boolean,
      invalidDataWithWaitStore: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      queryIssued: Boolean,
      responseValidIn: Boolean,
      responseMatchesSelected: Boolean,
      scbReturned: Boolean,
      waitStoreIn: Boolean,
      dataValidIn: Boolean): Result = {
    val active = enable && !flush
    val rawResponse = responseValidIn || waitStoreIn || dataValidIn
    val responseCandidate = active && responseValidIn
    val responseHasQuery = responseCandidate && queryIssued
    val responseMatched = responseHasQuery && responseMatchesSelected
    val responseOrdered = responseMatched && scbReturned

    Result(
      active = active,
      responseCandidate = responseCandidate,
      responseMatched = responseMatched,
      responseOrdered = responseOrdered,
      responseValid = responseOrdered,
      waitStore = responseOrdered && waitStoreIn,
      dataValid = responseOrdered && dataValidIn,
      blockedByDisabled = !enable && rawResponse,
      blockedByFlush = enable && flush && rawResponse,
      blockedByNoQuery = responseCandidate && !queryIssued,
      blockedByNoMatch = responseHasQuery && !responseMatchesSelected,
      blockedByScbOrder = responseMatched && !scbReturned,
      invalidResponseWithoutQuery = active && responseValidIn && !queryIssued,
      invalidDataWithWaitStore = responseOrdered && waitStoreIn && dataValidIn)
  }
}

class LoadReplaySourceReturnStoreSnapshotResponseMatchSpec extends AnyFunSuite {
  import LoadReplaySourceReturnStoreSnapshotResponseMatchReference._

  test("inactive raw response exposes disabled and flush blockers") {
    val disabled = LoadReplaySourceReturnStoreSnapshotResponseMatchReference(
      enable = false,
      flush = false,
      queryIssued = true,
      responseValidIn = true,
      responseMatchesSelected = true,
      scbReturned = true,
      waitStoreIn = false,
      dataValidIn = true)
    val flushed = LoadReplaySourceReturnStoreSnapshotResponseMatchReference(
      enable = true,
      flush = true,
      queryIssued = true,
      responseValidIn = true,
      responseMatchesSelected = true,
      scbReturned = true,
      waitStoreIn = false,
      dataValidIn = true)

    assert(disabled.blockedByDisabled)
    assert(!disabled.responseValid)
    assert(flushed.blockedByFlush)
    assert(!flushed.responseValid)
  }

  test("raw response waits for an issued selected-row query") {
    val result = LoadReplaySourceReturnStoreSnapshotResponseMatchReference(
      enable = true,
      flush = false,
      queryIssued = false,
      responseValidIn = true,
      responseMatchesSelected = true,
      scbReturned = true,
      waitStoreIn = false,
      dataValidIn = false)

    assert(result.responseCandidate)
    assert(!result.responseMatched)
    assert(!result.responseValid)
    assert(result.blockedByNoQuery)
    assert(result.invalidResponseWithoutQuery)
  }

  test("unmatched response is ignored before evidence accepts it") {
    val result = LoadReplaySourceReturnStoreSnapshotResponseMatchReference(
      enable = true,
      flush = false,
      queryIssued = true,
      responseValidIn = true,
      responseMatchesSelected = false,
      scbReturned = true,
      waitStoreIn = false,
      dataValidIn = true)

    assert(result.responseCandidate)
    assert(!result.responseMatched)
    assert(!result.responseOrdered)
    assert(!result.responseValid)
    assert(!result.dataValid)
    assert(result.blockedByNoMatch)
  }

  test("matched response is blocked until SCB return ordering is satisfied") {
    val result = LoadReplaySourceReturnStoreSnapshotResponseMatchReference(
      enable = true,
      flush = false,
      queryIssued = true,
      responseValidIn = true,
      responseMatchesSelected = true,
      scbReturned = false,
      waitStoreIn = false,
      dataValidIn = true)

    assert(result.responseMatched)
    assert(!result.responseOrdered)
    assert(!result.responseValid)
    assert(!result.dataValid)
    assert(result.blockedByScbOrder)
  }

  test("ordered response forwards wait-store and data evidence") {
    val data = LoadReplaySourceReturnStoreSnapshotResponseMatchReference(
      enable = true,
      flush = false,
      queryIssued = true,
      responseValidIn = true,
      responseMatchesSelected = true,
      scbReturned = true,
      waitStoreIn = false,
      dataValidIn = true)
    val wait = LoadReplaySourceReturnStoreSnapshotResponseMatchReference(
      enable = true,
      flush = false,
      queryIssued = true,
      responseValidIn = true,
      responseMatchesSelected = true,
      scbReturned = true,
      waitStoreIn = true,
      dataValidIn = false)

    assert(data.responseValid)
    assert(data.dataValid)
    assert(!data.waitStore)
    assert(wait.responseValid)
    assert(wait.waitStore)
    assert(!wait.dataValid)
  }

  test("ordered wait-store response with data reports malformed payload") {
    val result = LoadReplaySourceReturnStoreSnapshotResponseMatchReference(
      enable = true,
      flush = false,
      queryIssued = true,
      responseValidIn = true,
      responseMatchesSelected = true,
      scbReturned = true,
      waitStoreIn = true,
      dataValidIn = true)

    assert(result.responseValid)
    assert(result.waitStore)
    assert(result.dataValid)
    assert(result.invalidDataWithWaitStore)
  }

  test("Chisel LoadReplaySourceReturnStoreSnapshotResponseMatch elaborates diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplaySourceReturnStoreSnapshotResponseMatch)

    assert(sv.contains("module LoadReplaySourceReturnStoreSnapshotResponseMatch"))
    assert(sv.contains("io_responseOrdered"))
    assert(sv.contains("io_blockedByScbOrder"))
    assert(sv.contains("io_invalidDataWithWaitStore"))
  }
}
