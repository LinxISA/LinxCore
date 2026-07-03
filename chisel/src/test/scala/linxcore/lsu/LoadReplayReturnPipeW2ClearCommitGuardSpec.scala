package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2ClearCommitGuardReference {
  final case class Id(valid: Boolean = true, wrap: Boolean = false, value: Int = 0)

  final case class Result(
      active: Boolean,
      candidateValid: Boolean,
      slotRidValid: Boolean,
      resolveFireRidValid: Boolean,
      resolveMatchesSlot: Boolean,
      robMatchesSlot: Boolean,
      robMatchesResolve: Boolean,
      commitClearReady: Boolean,
      liveClearReady: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByNoSlot: Boolean,
      blockedByNoClearIntent: Boolean,
      blockedByLiveClearDisabled: Boolean,
      blockedByInvalidSlotRid: Boolean,
      blockedByNoResolveFire: Boolean,
      blockedByInvalidResolveRid: Boolean,
      blockedByNoRobComplete: Boolean,
      blockedByResolveSlotMismatch: Boolean,
      blockedByRobSlotMismatch: Boolean,
      blockedByRobResolveMismatch: Boolean,
      invalidResolveWithoutClear: Boolean,
      invalidRobCompleteWithoutClear: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      slotOccupied: Boolean,
      slotRid: Id,
      clearIntent: Boolean,
      liveClear: Boolean,
      resolveFireValid: Boolean,
      resolveFireRid: Id,
      robCompleteValid: Boolean,
      robCompleteRobValue: Int): Result = {
    val active = enable && !flush
    val candidateValid = active && slotOccupied
    val clearCandidate = candidateValid && clearIntent
    val slotRidValid = slotRid.valid
    val resolveFireRidValid = resolveFireValid && resolveFireRid.valid
    val resolveMatchesSlot =
      resolveFireRidValid &&
        slotRidValid &&
        resolveFireRid.wrap == slotRid.wrap &&
        resolveFireRid.value == slotRid.value
    val robMatchesSlot =
      robCompleteValid && slotRidValid && robCompleteRobValue == slotRid.value
    val robMatchesResolve =
      robCompleteValid && resolveFireRidValid && robCompleteRobValue == resolveFireRid.value
    val commitClearReady =
      clearCandidate && slotRidValid && resolveMatchesSlot && robMatchesSlot && robMatchesResolve

    Result(
      active = active,
      candidateValid = candidateValid,
      slotRidValid = slotRidValid,
      resolveFireRidValid = resolveFireRidValid,
      resolveMatchesSlot = resolveMatchesSlot,
      robMatchesSlot = robMatchesSlot,
      robMatchesResolve = robMatchesResolve,
      commitClearReady = commitClearReady,
      liveClearReady = commitClearReady && liveClear,
      blockedByDisabled = !enable && slotOccupied,
      blockedByFlush = enable && flush && slotOccupied,
      blockedByNoSlot = active && !slotOccupied,
      blockedByNoClearIntent = candidateValid && !clearIntent,
      blockedByLiveClearDisabled = commitClearReady && !liveClear,
      blockedByInvalidSlotRid = clearCandidate && !slotRidValid,
      blockedByNoResolveFire = clearCandidate && !resolveFireValid,
      blockedByInvalidResolveRid = clearCandidate && resolveFireValid && !resolveFireRid.valid,
      blockedByNoRobComplete = clearCandidate && !robCompleteValid,
      blockedByResolveSlotMismatch =
        clearCandidate && resolveFireRidValid && slotRidValid && !resolveMatchesSlot,
      blockedByRobSlotMismatch =
        clearCandidate && robCompleteValid && slotRidValid && !robMatchesSlot,
      blockedByRobResolveMismatch =
        clearCandidate && robCompleteValid && resolveFireRidValid && !robMatchesResolve,
      invalidResolveWithoutClear = candidateValid && !clearIntent && resolveFireValid,
      invalidRobCompleteWithoutClear = candidateValid && !clearIntent && robCompleteValid)
  }
}

class LoadReplayReturnPipeW2ClearCommitGuardSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2ClearCommitGuardReference._

  test("accepts commit-clear evidence when slot resolve and ROB completion agree") {
    val result = LoadReplayReturnPipeW2ClearCommitGuardReference(
      enable = true,
      flush = false,
      slotOccupied = true,
      slotRid = Id(valid = true, wrap = true, value = 5),
      clearIntent = true,
      liveClear = true,
      resolveFireValid = true,
      resolveFireRid = Id(valid = true, wrap = true, value = 5),
      robCompleteValid = true,
      robCompleteRobValue = 5)

    assert(result.candidateValid)
    assert(result.resolveMatchesSlot)
    assert(result.robMatchesSlot)
    assert(result.robMatchesResolve)
    assert(result.commitClearReady)
    assert(result.liveClearReady)
    assert(!result.blockedByLiveClearDisabled)
  }

  test("keeps coherent evidence dormant when live clear remains disabled") {
    val result = LoadReplayReturnPipeW2ClearCommitGuardReference(
      enable = true,
      flush = false,
      slotOccupied = true,
      slotRid = Id(valid = true, value = 2),
      clearIntent = true,
      liveClear = false,
      resolveFireValid = true,
      resolveFireRid = Id(valid = true, value = 2),
      robCompleteValid = true,
      robCompleteRobValue = 2)

    assert(result.commitClearReady)
    assert(!result.liveClearReady)
    assert(result.blockedByLiveClearDisabled)
  }

  test("blocks clear evidence until ROB completion arrives") {
    val result = LoadReplayReturnPipeW2ClearCommitGuardReference(
      enable = true,
      flush = false,
      slotOccupied = true,
      slotRid = Id(valid = true, value = 3),
      clearIntent = true,
      liveClear = true,
      resolveFireValid = true,
      resolveFireRid = Id(valid = true, value = 3),
      robCompleteValid = false,
      robCompleteRobValue = 0)

    assert(!result.commitClearReady)
    assert(!result.liveClearReady)
    assert(result.blockedByNoRobComplete)
    assert(!result.blockedByResolveSlotMismatch)
  }

  test("reports resolve and ROB identity mismatches independently") {
    val resolveMismatch = LoadReplayReturnPipeW2ClearCommitGuardReference(
      enable = true,
      flush = false,
      slotOccupied = true,
      slotRid = Id(valid = true, wrap = false, value = 1),
      clearIntent = true,
      liveClear = true,
      resolveFireValid = true,
      resolveFireRid = Id(valid = true, wrap = true, value = 1),
      robCompleteValid = true,
      robCompleteRobValue = 1)
    val robMismatch = LoadReplayReturnPipeW2ClearCommitGuardReference(
      enable = true,
      flush = false,
      slotOccupied = true,
      slotRid = Id(valid = true, value = 4),
      clearIntent = true,
      liveClear = true,
      resolveFireValid = true,
      resolveFireRid = Id(valid = true, value = 4),
      robCompleteValid = true,
      robCompleteRobValue = 5)

    assert(!resolveMismatch.commitClearReady)
    assert(resolveMismatch.blockedByResolveSlotMismatch)
    assert(!resolveMismatch.blockedByRobSlotMismatch)
    assert(!robMismatch.commitClearReady)
    assert(robMismatch.blockedByRobSlotMismatch)
    assert(robMismatch.blockedByRobResolveMismatch)
  }

  test("reports invalid or stray evidence without a clear intent") {
    val invalidResolve = LoadReplayReturnPipeW2ClearCommitGuardReference(
      enable = true,
      flush = false,
      slotOccupied = true,
      slotRid = Id(valid = true, value = 4),
      clearIntent = true,
      liveClear = true,
      resolveFireValid = true,
      resolveFireRid = Id(valid = false, value = 4),
      robCompleteValid = true,
      robCompleteRobValue = 4)
    val stray = LoadReplayReturnPipeW2ClearCommitGuardReference(
      enable = true,
      flush = false,
      slotOccupied = true,
      slotRid = Id(valid = true, value = 4),
      clearIntent = false,
      liveClear = false,
      resolveFireValid = true,
      resolveFireRid = Id(valid = true, value = 4),
      robCompleteValid = true,
      robCompleteRobValue = 4)

    assert(!invalidResolve.commitClearReady)
    assert(invalidResolve.blockedByInvalidResolveRid)
    assert(stray.blockedByNoClearIntent)
    assert(stray.invalidResolveWithoutClear)
    assert(stray.invalidRobCompleteWithoutClear)
  }

  test("suppresses disabled flushed and empty slots") {
    val disabled = LoadReplayReturnPipeW2ClearCommitGuardReference(
      enable = false,
      flush = false,
      slotOccupied = true,
      slotRid = Id(value = 0),
      clearIntent = true,
      liveClear = true,
      resolveFireValid = true,
      resolveFireRid = Id(value = 0),
      robCompleteValid = true,
      robCompleteRobValue = 0)
    val flushed = LoadReplayReturnPipeW2ClearCommitGuardReference(
      enable = true,
      flush = true,
      slotOccupied = true,
      slotRid = Id(value = 0),
      clearIntent = true,
      liveClear = true,
      resolveFireValid = true,
      resolveFireRid = Id(value = 0),
      robCompleteValid = true,
      robCompleteRobValue = 0)
    val empty = LoadReplayReturnPipeW2ClearCommitGuardReference(
      enable = true,
      flush = false,
      slotOccupied = false,
      slotRid = Id(value = 0),
      clearIntent = true,
      liveClear = true,
      resolveFireValid = true,
      resolveFireRid = Id(value = 0),
      robCompleteValid = true,
      robCompleteRobValue = 0)

    assert(disabled.blockedByDisabled)
    assert(!disabled.commitClearReady)
    assert(flushed.blockedByFlush)
    assert(!flushed.commitClearReady)
    assert(empty.blockedByNoSlot)
    assert(!empty.commitClearReady)
  }

  test("Chisel LoadReplayReturnPipeW2ClearCommitGuard elaborates guard diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPipeW2ClearCommitGuard(idEntries = 8))

    assert(sv.contains("module LoadReplayReturnPipeW2ClearCommitGuard"))
    assert(sv.contains("io_slotRid"))
    assert(sv.contains("io_resolveFireRid"))
    assert(sv.contains("io_robCompleteRobValue"))
    assert(sv.contains("io_commitClearReady"))
    assert(sv.contains("io_liveClearReady"))
    assert(sv.contains("io_blockedByResolveSlotMismatch"))
    assert(sv.contains("io_blockedByRobResolveMismatch"))
  }
}
