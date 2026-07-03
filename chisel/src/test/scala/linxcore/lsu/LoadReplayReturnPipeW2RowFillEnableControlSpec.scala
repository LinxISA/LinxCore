package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2RowFillEnableControlReference {
  final case class Result(
      active: Boolean,
      requestActive: Boolean,
      candidateValid: Boolean,
      atomicPrerequisitesReady: Boolean,
      rowFillEnable: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByRequestDisabled: Boolean,
      blockedByNoCandidate: Boolean,
      blockedByNoSideEffectCommit: Boolean,
      blockedByNoClearCommit: Boolean,
      blockedByLiveClearDisabled: Boolean,
      blockedByNoReplayRowLifecycle: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      rowFillRequested: Boolean,
      rowFillCandidateValid: Boolean,
      sideEffectFireComplete: Boolean,
      clearCommitReady: Boolean,
      liveClearReady: Boolean,
      replayRowLifecycleReady: Boolean): Result = {
    val active = enable && !flush
    val requestActive = active && rowFillRequested
    val candidateValid = active && rowFillCandidateValid
    val atomicPrerequisitesReady =
      candidateValid &&
        sideEffectFireComplete &&
        clearCommitReady &&
        liveClearReady &&
        replayRowLifecycleReady
    val rowFillEnable = requestActive && atomicPrerequisitesReady

    Result(
      active = active,
      requestActive = requestActive,
      candidateValid = candidateValid,
      atomicPrerequisitesReady = atomicPrerequisitesReady,
      rowFillEnable = rowFillEnable,
      blockedByDisabled = !enable && (rowFillRequested || rowFillCandidateValid),
      blockedByFlush = enable && flush && (rowFillRequested || rowFillCandidateValid),
      blockedByRequestDisabled = active && !rowFillRequested && rowFillCandidateValid,
      blockedByNoCandidate = requestActive && !rowFillCandidateValid,
      blockedByNoSideEffectCommit = candidateValid && !sideEffectFireComplete,
      blockedByNoClearCommit = candidateValid && sideEffectFireComplete && !clearCommitReady,
      blockedByLiveClearDisabled =
        candidateValid && sideEffectFireComplete && clearCommitReady && !liveClearReady,
      blockedByNoReplayRowLifecycle =
        candidateValid && sideEffectFireComplete && clearCommitReady && liveClearReady &&
          !replayRowLifecycleReady)
  }
}

class LoadReplayReturnPipeW2RowFillEnableControlSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2RowFillEnableControlReference._

  test("enables row fill only when the atomic request and every prerequisite are ready") {
    val result = LoadReplayReturnPipeW2RowFillEnableControlReference(
      enable = true,
      flush = false,
      rowFillRequested = true,
      rowFillCandidateValid = true,
      sideEffectFireComplete = true,
      clearCommitReady = true,
      liveClearReady = true,
      replayRowLifecycleReady = true)

    assert(result.active)
    assert(result.requestActive)
    assert(result.candidateValid)
    assert(result.atomicPrerequisitesReady)
    assert(result.rowFillEnable)
  }

  test("keeps a complete row candidate dormant while the request remains disabled") {
    val result = LoadReplayReturnPipeW2RowFillEnableControlReference(
      enable = true,
      flush = false,
      rowFillRequested = false,
      rowFillCandidateValid = true,
      sideEffectFireComplete = true,
      clearCommitReady = true,
      liveClearReady = true,
      replayRowLifecycleReady = true)

    assert(result.atomicPrerequisitesReady)
    assert(!result.rowFillEnable)
    assert(result.blockedByRequestDisabled)
  }

  test("reports missing atomic prerequisites in model order") {
    val noSideEffects = LoadReplayReturnPipeW2RowFillEnableControlReference(
      enable = true,
      flush = false,
      rowFillRequested = true,
      rowFillCandidateValid = true,
      sideEffectFireComplete = false,
      clearCommitReady = true,
      liveClearReady = true,
      replayRowLifecycleReady = true)
    val noClearCommit = LoadReplayReturnPipeW2RowFillEnableControlReference(
      enable = true,
      flush = false,
      rowFillRequested = true,
      rowFillCandidateValid = true,
      sideEffectFireComplete = true,
      clearCommitReady = false,
      liveClearReady = true,
      replayRowLifecycleReady = true)
    val noLiveClear = LoadReplayReturnPipeW2RowFillEnableControlReference(
      enable = true,
      flush = false,
      rowFillRequested = true,
      rowFillCandidateValid = true,
      sideEffectFireComplete = true,
      clearCommitReady = true,
      liveClearReady = false,
      replayRowLifecycleReady = true)
    val noLifecycle = LoadReplayReturnPipeW2RowFillEnableControlReference(
      enable = true,
      flush = false,
      rowFillRequested = true,
      rowFillCandidateValid = true,
      sideEffectFireComplete = true,
      clearCommitReady = true,
      liveClearReady = true,
      replayRowLifecycleReady = false)

    assert(noSideEffects.blockedByNoSideEffectCommit)
    assert(noClearCommit.blockedByNoClearCommit)
    assert(noLiveClear.blockedByLiveClearDisabled)
    assert(noLifecycle.blockedByNoReplayRowLifecycle)
    assert(!noLifecycle.rowFillEnable)
  }

  test("reports disabled flush and no-candidate blockers") {
    val disabled = LoadReplayReturnPipeW2RowFillEnableControlReference(
      enable = false,
      flush = false,
      rowFillRequested = true,
      rowFillCandidateValid = true,
      sideEffectFireComplete = true,
      clearCommitReady = true,
      liveClearReady = true,
      replayRowLifecycleReady = true)
    val flushed = LoadReplayReturnPipeW2RowFillEnableControlReference(
      enable = true,
      flush = true,
      rowFillRequested = true,
      rowFillCandidateValid = true,
      sideEffectFireComplete = true,
      clearCommitReady = true,
      liveClearReady = true,
      replayRowLifecycleReady = true)
    val noCandidate = LoadReplayReturnPipeW2RowFillEnableControlReference(
      enable = true,
      flush = false,
      rowFillRequested = true,
      rowFillCandidateValid = false,
      sideEffectFireComplete = true,
      clearCommitReady = true,
      liveClearReady = true,
      replayRowLifecycleReady = true)

    assert(disabled.blockedByDisabled)
    assert(flushed.blockedByFlush)
    assert(noCandidate.blockedByNoCandidate)
    assert(!disabled.rowFillEnable)
    assert(!flushed.rowFillEnable)
    assert(!noCandidate.rowFillEnable)
  }

  test("Chisel LoadReplayReturnPipeW2RowFillEnableControl elaborates row-fill diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPipeW2RowFillEnableControl)

    assert(sv.contains("module LoadReplayReturnPipeW2RowFillEnableControl"))
    assert(sv.contains("io_rowFillEnable"))
    assert(sv.contains("io_blockedByNoReplayRowLifecycle"))
  }
}
