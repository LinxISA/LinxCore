package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnTloadCompletionCandidateReference {
  final case class Result(
      candidateValid: Boolean,
      tloadCandidateValid: Boolean,
      subInstCntAfter: Int,
      tileScbSendValid: Boolean,
      tileScbIsLast: Boolean,
      completeValid: Boolean,
      readyForPipeInsert: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByNoLaneCompletion: Boolean,
      blockedByInvalidSubInstCnt: Boolean,
      blockedByTloadPending: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      laneCompletionValid: Boolean,
      isMemIex: Boolean,
      isTload: Boolean,
      subInstCntBefore: Int): Result = {
    val candidateValid = enable && !flush && laneCompletionValid
    val tloadCandidateValid = candidateValid && isMemIex && isTload
    val decrementValid = tloadCandidateValid && subInstCntBefore != 0
    val subInstCntAfter = if (decrementValid) subInstCntBefore - 1 else subInstCntBefore
    val tloadComplete = decrementValid && subInstCntAfter == 0
    val completeValid = candidateValid && (!tloadCandidateValid || tloadComplete)

    Result(
      candidateValid = candidateValid,
      tloadCandidateValid = tloadCandidateValid,
      subInstCntAfter = if (candidateValid) subInstCntAfter else 0,
      tileScbSendValid = decrementValid,
      tileScbIsLast = decrementValid && subInstCntAfter == 0,
      completeValid = completeValid,
      readyForPipeInsert = completeValid,
      blockedByDisabled = !enable && laneCompletionValid,
      blockedByFlush = enable && flush && laneCompletionValid,
      blockedByNoLaneCompletion = enable && !flush && !laneCompletionValid,
      blockedByInvalidSubInstCnt = tloadCandidateValid && subInstCntBefore == 0,
      blockedByTloadPending = decrementValid && subInstCntAfter != 0)
  }
}

class LoadReplayReturnTloadCompletionCandidateSpec extends AnyFunSuite {
  import LoadReplayReturnTloadCompletionCandidateReference._

  test("ordinary non-TLOAD replay passes through after lane completion") {
    val result = LoadReplayReturnTloadCompletionCandidateReference(
      enable = true,
      flush = false,
      laneCompletionValid = true,
      isMemIex = false,
      isTload = false,
      subInstCntBefore = 0)

    assert(result.candidateValid)
    assert(!result.tloadCandidateValid)
    assert(!result.tileScbSendValid)
    assert(result.completeValid)
    assert(result.readyForPipeInsert)
  }

  test("non-final TLOAD sends tile-SCB sequence and blocks pipe insertion") {
    val result = LoadReplayReturnTloadCompletionCandidateReference(
      enable = true,
      flush = false,
      laneCompletionValid = true,
      isMemIex = true,
      isTload = true,
      subInstCntBefore = 3)

    assert(result.tloadCandidateValid)
    assert(result.subInstCntAfter == 2)
    assert(result.tileScbSendValid)
    assert(!result.tileScbIsLast)
    assert(!result.completeValid)
    assert(!result.readyForPipeInsert)
    assert(result.blockedByTloadPending)
  }

  test("final TLOAD sub-instruction can proceed to pipe insertion") {
    val result = LoadReplayReturnTloadCompletionCandidateReference(
      enable = true,
      flush = false,
      laneCompletionValid = true,
      isMemIex = true,
      isTload = true,
      subInstCntBefore = 1)

    assert(result.tloadCandidateValid)
    assert(result.subInstCntAfter == 0)
    assert(result.tileScbSendValid)
    assert(result.tileScbIsLast)
    assert(result.completeValid)
    assert(result.readyForPipeInsert)
  }

  test("reports disabled flush missing-lane-completion and invalid-count blockers") {
    val disabled = LoadReplayReturnTloadCompletionCandidateReference(
      enable = false,
      flush = false,
      laneCompletionValid = true,
      isMemIex = false,
      isTload = false,
      subInstCntBefore = 0)
    assert(disabled.blockedByDisabled)
    assert(!disabled.candidateValid)

    val flushed = LoadReplayReturnTloadCompletionCandidateReference(
      enable = true,
      flush = true,
      laneCompletionValid = true,
      isMemIex = false,
      isTload = false,
      subInstCntBefore = 0)
    assert(flushed.blockedByFlush)
    assert(!flushed.candidateValid)

    val noLaneCompletion = LoadReplayReturnTloadCompletionCandidateReference(
      enable = true,
      flush = false,
      laneCompletionValid = false,
      isMemIex = false,
      isTload = false,
      subInstCntBefore = 0)
    assert(noLaneCompletion.blockedByNoLaneCompletion)
    assert(!noLaneCompletion.candidateValid)

    val invalidSubInstCnt = LoadReplayReturnTloadCompletionCandidateReference(
      enable = true,
      flush = false,
      laneCompletionValid = true,
      isMemIex = true,
      isTload = true,
      subInstCntBefore = 0)
    assert(invalidSubInstCnt.blockedByInvalidSubInstCnt)
    assert(!invalidSubInstCnt.completeValid)
  }

  test("Chisel LoadReplayReturnTloadCompletionCandidate elaborates TLOAD diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnTloadCompletionCandidate)

    assert(sv.contains("module LoadReplayReturnTloadCompletionCandidate"))
    assert(sv.contains("io_tileScbSendValid"))
    assert(sv.contains("io_tileScbIsLast"))
    assert(sv.contains("io_blockedByTloadPending"))
  }
}
