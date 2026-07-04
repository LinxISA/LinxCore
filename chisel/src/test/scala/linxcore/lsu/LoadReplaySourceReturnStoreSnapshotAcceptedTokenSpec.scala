package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplaySourceReturnStoreSnapshotAcceptedTokenReference {
  final case class Token(
      valid: Boolean = false,
      repick: Boolean = false,
      clusterId: Int = 0,
      entryId: Int = 0)

  final case class Result(
      active: Boolean,
      tokenCanAccept: Boolean,
      token: Token,
      residentTokenValid: Boolean,
      captureCandidate: Boolean,
      captureAccepted: Boolean,
      captureBypass: Boolean,
      clearAccepted: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByNoSelected: Boolean,
      blockedByStaleRow: Boolean,
      blockedByOutstandingToken: Boolean,
      next: Token)

  def step(
      state: Token,
      enable: Boolean,
      flush: Boolean,
      queryIssued: Boolean,
      selectedValid: Boolean,
      selectedRepick: Boolean,
      selectedClusterId: Int,
      selectedEntryId: Int,
      responseConsumed: Boolean): Result = {
    val active = enable && !flush
    val tokenCanAccept = active && !state.valid
    val captureCandidate = active && queryIssued
    val captureHasSelected = captureCandidate && selectedValid
    val captureReady = captureHasSelected && selectedRepick
    val captureAccepted = captureReady && !state.valid
    val captureBypass = captureAccepted
    val token =
      if (state.valid) {
        state
      } else if (captureBypass) {
        Token(valid = true, repick = true, clusterId = selectedClusterId, entryId = selectedEntryId)
      } else {
        Token()
      }
    val clearAccepted = active && responseConsumed && token.valid
    val next =
      if (flush || clearAccepted) {
        Token()
      } else if (captureAccepted) {
        Token(valid = true, repick = selectedRepick, clusterId = selectedClusterId, entryId = selectedEntryId)
      } else {
        state
      }

    Result(
      active = active,
      tokenCanAccept = tokenCanAccept,
      token = token,
      residentTokenValid = state.valid,
      captureCandidate = captureCandidate,
      captureAccepted = captureAccepted,
      captureBypass = captureBypass,
      clearAccepted = clearAccepted,
      blockedByDisabled = !enable && queryIssued,
      blockedByFlush = enable && flush && queryIssued,
      blockedByNoSelected = captureCandidate && !selectedValid,
      blockedByStaleRow = captureHasSelected && !selectedRepick,
      blockedByOutstandingToken = captureReady && state.valid,
      next = next)
  }
}

class LoadReplaySourceReturnStoreSnapshotAcceptedTokenSpec extends AnyFunSuite {
  import LoadReplaySourceReturnStoreSnapshotAcceptedTokenReference._

  test("captures an accepted selected-row query token") {
    val result = step(
      state = Token(),
      enable = true,
      flush = false,
      queryIssued = true,
      selectedValid = true,
      selectedRepick = true,
      selectedClusterId = 0,
      selectedEntryId = 2,
      responseConsumed = false)

    assert(result.tokenCanAccept)
    assert(result.captureAccepted)
    assert(result.captureBypass)
    assert(result.token.valid)
    assert(result.token.entryId == 2)
    assert(result.next.valid)
    assert(result.next.entryId == 2)
  }

  test("bypassed token can be consumed in the same cycle without becoming resident") {
    val result = step(
      state = Token(),
      enable = true,
      flush = false,
      queryIssued = true,
      selectedValid = true,
      selectedRepick = true,
      selectedClusterId = 0,
      selectedEntryId = 1,
      responseConsumed = true)

    assert(result.captureAccepted)
    assert(result.captureBypass)
    assert(result.clearAccepted)
    assert(result.token.valid)
    assert(!result.next.valid)
  }

  test("resident token blocks replacement until a response consumes it") {
    val state = Token(valid = true, repick = true, clusterId = 0, entryId = 1)
    val result = step(
      state = state,
      enable = true,
      flush = false,
      queryIssued = true,
      selectedValid = true,
      selectedRepick = true,
      selectedClusterId = 0,
      selectedEntryId = 3,
      responseConsumed = false)

    assert(!result.tokenCanAccept)
    assert(!result.captureAccepted)
    assert(result.blockedByOutstandingToken)
    assert(result.token.entryId == 1)
    assert(result.next.entryId == 1)
  }

  test("response consumption clears a resident token") {
    val result = step(
      state = Token(valid = true, repick = true, clusterId = 0, entryId = 3),
      enable = true,
      flush = false,
      queryIssued = false,
      selectedValid = false,
      selectedRepick = false,
      selectedClusterId = 0,
      selectedEntryId = 0,
      responseConsumed = true)

    assert(result.residentTokenValid)
    assert(result.clearAccepted)
    assert(result.token.valid)
    assert(result.token.entryId == 3)
    assert(!result.next.valid)
  }

  test("disabled, flushed, missing-selected, and stale-row captures are diagnosed") {
    val disabled = step(Token(), enable = false, flush = false, queryIssued = true, selectedValid = true, selectedRepick = true, 0, 1, false)
    val flushed = step(Token(), enable = true, flush = true, queryIssued = true, selectedValid = true, selectedRepick = true, 0, 1, false)
    val noSelected = step(Token(), enable = true, flush = false, queryIssued = true, selectedValid = false, selectedRepick = true, 0, 1, false)
    val stale = step(Token(), enable = true, flush = false, queryIssued = true, selectedValid = true, selectedRepick = false, 0, 1, false)

    assert(disabled.blockedByDisabled)
    assert(flushed.blockedByFlush)
    assert(noSelected.blockedByNoSelected)
    assert(stale.blockedByStaleRow)
    assert(!disabled.token.valid)
    assert(!flushed.token.valid)
    assert(!noSelected.token.valid)
    assert(!stale.token.valid)
  }

  test("Chisel LoadReplaySourceReturnStoreSnapshotAcceptedToken elaborates token storage") {
    val sv = ChiselStage.emitSystemVerilog(
      new LoadReplaySourceReturnStoreSnapshotAcceptedToken(
        clusterIdWidth = 2,
        entryIdWidth = 4
      ))

    assert(sv.contains("module LoadReplaySourceReturnStoreSnapshotAcceptedToken"))
    assert(sv.contains("tokenValidReg"))
    assert(sv.contains("io_tokenCanAccept"))
    assert(sv.contains("io_blockedByOutstandingToken"))
  }
}
