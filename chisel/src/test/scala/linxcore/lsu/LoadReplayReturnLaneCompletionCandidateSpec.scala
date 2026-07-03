package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnLaneCompletionCandidateReference {
  final case class Result(
      candidateValid: Boolean,
      retLaneAfterResolve: Int,
      requiresAllLanes: Boolean,
      completeValid: Boolean,
      readyForPipeInsert: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByNoResolve: Boolean,
      blockedByZeroReturnedLanes: Boolean,
      blockedByInvalidRealReqCnt: Boolean,
      blockedByScalarLoadPairIncomplete: Boolean,
      blockedByVectorMemIncomplete: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      resolveValid: Boolean,
      scalarLoadPair: Boolean,
      vectorOrMemMultiLane: Boolean,
      retLaneBefore: Int,
      returnedLaneCount: Int,
      realReqCnt: Int): Result = {
    val candidateValid = enable && !flush && resolveValid
    val retLaneAfterResolve = retLaneBefore + returnedLaneCount
    val hasReturnedLane = returnedLaneCount != 0
    val requiresAllLanes = scalarLoadPair || vectorOrMemMultiLane
    val hasValidReqCnt = !requiresAllLanes || realReqCnt != 0
    val allLanesReturned = !requiresAllLanes || retLaneAfterResolve >= realReqCnt
    val completeValid = candidateValid && hasReturnedLane && hasValidReqCnt && allLanesReturned

    Result(
      candidateValid = candidateValid,
      retLaneAfterResolve = if (candidateValid) retLaneAfterResolve else 0,
      requiresAllLanes = candidateValid && requiresAllLanes,
      completeValid = completeValid,
      readyForPipeInsert = completeValid,
      blockedByDisabled = !enable && resolveValid,
      blockedByFlush = enable && flush && resolveValid,
      blockedByNoResolve = enable && !flush && !resolveValid,
      blockedByZeroReturnedLanes = candidateValid && !hasReturnedLane,
      blockedByInvalidRealReqCnt = candidateValid && requiresAllLanes && !hasValidReqCnt,
      blockedByScalarLoadPairIncomplete =
        candidateValid && scalarLoadPair && hasValidReqCnt && hasReturnedLane &&
          retLaneAfterResolve < realReqCnt,
      blockedByVectorMemIncomplete =
        candidateValid && !scalarLoadPair && vectorOrMemMultiLane && hasValidReqCnt &&
          hasReturnedLane && retLaneAfterResolve < realReqCnt)
  }
}

class LoadReplayReturnLaneCompletionCandidateSpec extends AnyFunSuite {
  import LoadReplayReturnLaneCompletionCandidateReference._

  test("ordinary scalar returned data can proceed without a realReqCnt gate") {
    val result = LoadReplayReturnLaneCompletionCandidateReference(
      enable = true,
      flush = false,
      resolveValid = true,
      scalarLoadPair = false,
      vectorOrMemMultiLane = false,
      retLaneBefore = 0,
      returnedLaneCount = 1,
      realReqCnt = 0)

    assert(result.candidateValid)
    assert(result.retLaneAfterResolve == 1)
    assert(!result.requiresAllLanes)
    assert(result.completeValid)
    assert(result.readyForPipeInsert)
  }

  test("scalar load-pair waits until the returned lane count reaches realReqCnt") {
    val incomplete = LoadReplayReturnLaneCompletionCandidateReference(
      enable = true,
      flush = false,
      resolveValid = true,
      scalarLoadPair = true,
      vectorOrMemMultiLane = false,
      retLaneBefore = 0,
      returnedLaneCount = 1,
      realReqCnt = 2)
    assert(incomplete.candidateValid)
    assert(incomplete.requiresAllLanes)
    assert(!incomplete.completeValid)
    assert(incomplete.blockedByScalarLoadPairIncomplete)

    val complete = LoadReplayReturnLaneCompletionCandidateReference(
      enable = true,
      flush = false,
      resolveValid = true,
      scalarLoadPair = true,
      vectorOrMemMultiLane = false,
      retLaneBefore = 1,
      returnedLaneCount = 1,
      realReqCnt = 2)
    assert(complete.completeValid)
    assert(complete.readyForPipeInsert)
    assert(!complete.blockedByScalarLoadPairIncomplete)
  }

  test("vector or MEM multi-lane rows wait for realReqCnt before pipe insertion") {
    val incomplete = LoadReplayReturnLaneCompletionCandidateReference(
      enable = true,
      flush = false,
      resolveValid = true,
      scalarLoadPair = false,
      vectorOrMemMultiLane = true,
      retLaneBefore = 2,
      returnedLaneCount = 1,
      realReqCnt = 4)
    assert(!incomplete.completeValid)
    assert(incomplete.blockedByVectorMemIncomplete)

    val complete = LoadReplayReturnLaneCompletionCandidateReference(
      enable = true,
      flush = false,
      resolveValid = true,
      scalarLoadPair = false,
      vectorOrMemMultiLane = true,
      retLaneBefore = 3,
      returnedLaneCount = 1,
      realReqCnt = 4)
    assert(complete.completeValid)
    assert(complete.readyForPipeInsert)
  }

  test("reports disabled flush no-resolve zero-lane and invalid-count blockers") {
    val disabled = LoadReplayReturnLaneCompletionCandidateReference(
      enable = false,
      flush = false,
      resolveValid = true,
      scalarLoadPair = false,
      vectorOrMemMultiLane = false,
      retLaneBefore = 0,
      returnedLaneCount = 1,
      realReqCnt = 0)
    assert(disabled.blockedByDisabled)
    assert(!disabled.candidateValid)

    val flushed = LoadReplayReturnLaneCompletionCandidateReference(
      enable = true,
      flush = true,
      resolveValid = true,
      scalarLoadPair = false,
      vectorOrMemMultiLane = false,
      retLaneBefore = 0,
      returnedLaneCount = 1,
      realReqCnt = 0)
    assert(flushed.blockedByFlush)
    assert(!flushed.candidateValid)

    val noResolve = LoadReplayReturnLaneCompletionCandidateReference(
      enable = true,
      flush = false,
      resolveValid = false,
      scalarLoadPair = false,
      vectorOrMemMultiLane = false,
      retLaneBefore = 0,
      returnedLaneCount = 1,
      realReqCnt = 0)
    assert(noResolve.blockedByNoResolve)
    assert(!noResolve.candidateValid)

    val zeroLane = LoadReplayReturnLaneCompletionCandidateReference(
      enable = true,
      flush = false,
      resolveValid = true,
      scalarLoadPair = false,
      vectorOrMemMultiLane = false,
      retLaneBefore = 0,
      returnedLaneCount = 0,
      realReqCnt = 0)
    assert(zeroLane.blockedByZeroReturnedLanes)
    assert(!zeroLane.completeValid)

    val invalidReqCnt = LoadReplayReturnLaneCompletionCandidateReference(
      enable = true,
      flush = false,
      resolveValid = true,
      scalarLoadPair = true,
      vectorOrMemMultiLane = false,
      retLaneBefore = 0,
      returnedLaneCount = 1,
      realReqCnt = 0)
    assert(invalidReqCnt.blockedByInvalidRealReqCnt)
    assert(!invalidReqCnt.completeValid)
  }

  test("Chisel LoadReplayReturnLaneCompletionCandidate elaborates lane diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnLaneCompletionCandidate)

    assert(sv.contains("module LoadReplayReturnLaneCompletionCandidate"))
    assert(sv.contains("io_retLaneAfterResolve"))
    assert(sv.contains("io_readyForPipeInsert"))
    assert(sv.contains("io_blockedByScalarLoadPairIncomplete"))
    assert(sv.contains("io_blockedByVectorMemIncomplete"))
  }
}
