package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplaySourceReturnStoreSnapshotAcceptedTokenReference {
  final case class Token(
      valid: Boolean = false,
      repick: Boolean = false,
      clusterId: Int = 0,
      entryId: Int = 0,
      bid: Int = 0,
      gid: Int = 0,
      loadLsId: Int = 0,
      peId: Int = 0,
      stid: Int = 0,
      tid: Int = 0,
      lineData: BigInt = 0,
      validMask: BigInt = 0,
      requestByteMask: BigInt = 0)

  final case class Result(
      active: Boolean,
      tokenCanAccept: Boolean,
      token: Token,
      residentTokenValid: Boolean,
      captureCandidate: Boolean,
      captureAccepted: Boolean,
      captureBypass: Boolean,
      clearAccepted: Boolean,
      precisePruned: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByPreciseFlush: Boolean,
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
      responseConsumed: Boolean,
      selectedBid: Int = 0,
      selectedGid: Int = 0,
      selectedLoadLsId: Int = 0,
      selectedPeId: Int = 0,
      selectedStid: Int = 0,
      selectedTid: Int = 0,
      selectedLineData: BigInt = 0,
      selectedValidMask: BigInt = 0,
      selectedRequestByteMask: BigInt = 0,
      preciseFlush: Option[STQFlushPruneReference.Flush] = None): Result = {
    val precisePruneActive = enable && !flush && preciseFlush.exists(_.valid)
    val active = enable && !flush && !precisePruneActive
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
        Token(
          valid = true,
          repick = true,
          clusterId = selectedClusterId,
          entryId = selectedEntryId,
          bid = selectedBid,
          gid = selectedGid,
          loadLsId = selectedLoadLsId,
          peId = selectedPeId,
          stid = selectedStid,
          tid = selectedTid,
          lineData = selectedLineData,
          validMask = selectedValidMask,
          requestByteMask = selectedRequestByteMask)
      } else {
        Token()
      }
    val precisePruned =
      precisePruneActive && state.valid && preciseFlush.exists(flush =>
        STQFlushPruneReference.matches(
          flush,
          STQFlushPruneReference.Row(
            valid = true,
            stid = state.stid,
            peId = state.peId,
            tid = state.tid,
            bid = STQFlushPruneReference.Id(value = state.bid),
            gid = STQFlushPruneReference.Id(value = state.gid),
            lsId = STQFlushPruneReference.Id(value = state.loadLsId)
          )
        ))
    val clearAccepted = active && responseConsumed && token.valid
    val next =
      if (flush || precisePruned || clearAccepted) {
        Token()
      } else if (captureAccepted) {
        Token(
          valid = true,
          repick = selectedRepick,
          clusterId = selectedClusterId,
          entryId = selectedEntryId,
          bid = selectedBid,
          gid = selectedGid,
          loadLsId = selectedLoadLsId,
          peId = selectedPeId,
          stid = selectedStid,
          tid = selectedTid,
          lineData = selectedLineData,
          validMask = selectedValidMask,
          requestByteMask = selectedRequestByteMask)
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
      precisePruned = precisePruned,
      blockedByDisabled = !enable && queryIssued,
      blockedByFlush = enable && flush && queryIssued,
      blockedByPreciseFlush = precisePruneActive && queryIssued,
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
      responseConsumed = false,
      selectedBid = 4,
      selectedGid = 1,
      selectedLoadLsId = 9,
      selectedPeId = 2,
      selectedStid = 3,
      selectedTid = 4,
      selectedLineData = BigInt("1122334455667788", 16),
      selectedValidMask = BigInt("0f", 16),
      selectedRequestByteMask = BigInt("ff", 16))

    assert(result.tokenCanAccept)
    assert(result.captureAccepted)
    assert(result.captureBypass)
    assert(result.token.valid)
    assert(result.token.entryId == 2)
    assert(result.token.bid == 4)
    assert(result.token.loadLsId == 9)
    assert(result.token.stid == 3)
    assert(result.token.lineData == BigInt("1122334455667788", 16))
    assert(result.token.validMask == BigInt("0f", 16))
    assert(result.token.requestByteMask == BigInt("ff", 16))
    assert(result.next.valid)
    assert(result.next.entryId == 2)
    assert(result.next.bid == 4)
    assert(result.next.loadLsId == 9)
    assert(result.next.stid == 3)
    assert(result.next.lineData == BigInt("1122334455667788", 16))
    assert(result.next.validMask == BigInt("0f", 16))
    assert(result.next.requestByteMask == BigInt("ff", 16))
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
      responseConsumed = true,
      selectedLineData = BigInt("aabbccdd", 16),
      selectedValidMask = BigInt("33", 16),
      selectedRequestByteMask = BigInt("3f", 16))

    assert(result.captureAccepted)
    assert(result.captureBypass)
    assert(result.clearAccepted)
    assert(result.token.valid)
    assert(result.token.lineData == BigInt("aabbccdd", 16))
    assert(result.token.validMask == BigInt("33", 16))
    assert(result.token.requestByteMask == BigInt("3f", 16))
    assert(!result.next.valid)
    assert(result.next.lineData == 0)
    assert(result.next.validMask == 0)
    assert(result.next.requestByteMask == 0)
  }

  test("resident token blocks replacement until a response consumes it") {
    val state = Token(
      valid = true,
      repick = true,
      clusterId = 0,
      entryId = 1,
      bid = 4,
      gid = 1,
      loadLsId = 9,
      peId = 2,
      stid = 3,
      tid = 4,
      lineData = BigInt("12345678", 16),
      validMask = BigInt("0f", 16),
      requestByteMask = BigInt("ff", 16))
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
    assert(result.token.lineData == BigInt("12345678", 16))
    assert(result.token.validMask == BigInt("0f", 16))
    assert(result.token.requestByteMask == BigInt("ff", 16))
    assert(result.next.entryId == 1)
    assert(result.next.lineData == BigInt("12345678", 16))
  }

  test("response consumption clears a resident token") {
    val result = step(
      state = Token(
        valid = true,
        repick = true,
        clusterId = 0,
        entryId = 3,
        bid = 4,
        gid = 1,
        loadLsId = 9,
        peId = 2,
        stid = 3,
        tid = 4,
        lineData = BigInt("feedface", 16),
        validMask = BigInt("f0", 16),
        requestByteMask = BigInt("ff", 16)),
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
    assert(result.token.lineData == BigInt("feedface", 16))
    assert(!result.next.valid)
    assert(result.next.lineData == 0)
    assert(result.next.validMask == 0)
    assert(result.next.requestByteMask == 0)
  }

  test("precise flush prunes a resident matching accepted-query token") {
    val state = Token(
      valid = true,
      repick = true,
      clusterId = 0,
      entryId = 3,
      bid = 2,
      gid = 1,
      loadLsId = 5,
      peId = 7,
      stid = 3,
      tid = 4,
      lineData = BigInt("feedface", 16),
      validMask = BigInt("ff", 16),
      requestByteMask = BigInt("ff", 16))
    val result = step(
      state = state,
      enable = true,
      flush = false,
      queryIssued = true,
      selectedValid = true,
      selectedRepick = true,
      selectedClusterId = 0,
      selectedEntryId = 1,
      responseConsumed = false,
      preciseFlush = Some(STQFlushPruneReference.Flush(
        stid = 3,
        peId = 7,
        tid = 4,
        bid = STQFlushPruneReference.Id(value = 2),
        lsId = STQFlushPruneReference.Id(value = 5),
        baseOnPE = true,
        baseOnThread = true)))

    assert(!result.active)
    assert(!result.tokenCanAccept)
    assert(result.token.valid)
    assert(result.token.entryId == 3)
    assert(result.precisePruned)
    assert(result.blockedByPreciseFlush)
    assert(!result.captureAccepted)
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
    assert(sv.contains("tokenBidReg"))
    assert(sv.contains("io_preciseFlush_req_valid"))
    assert(sv.contains("tokenLineDataReg"))
    assert(sv.contains("io_tokenCanAccept"))
    assert(sv.contains("io_tokenRequestByteMask"))
    assert(sv.contains("io_precisePruned"))
    assert(sv.contains("io_blockedByPreciseFlush"))
    assert(sv.contains("io_blockedByOutstandingToken"))
  }
}
