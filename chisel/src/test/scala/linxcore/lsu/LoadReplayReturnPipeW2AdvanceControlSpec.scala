package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2AdvanceControlReference {
  final case class Result(
      active: Boolean,
      livePromotionActive: Boolean,
      advanceEnable: Boolean,
      replaceOnClear: Boolean,
      usesFutureAdvance: Boolean,
      currentMatchesFuture: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByLivePromotionDisabled: Boolean,
      blockedByFutureAdvance: Boolean,
      invalidReplaceWithoutFutureWrite: Boolean,
      invalidFutureWriteWithoutAdvance: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      livePromotionEnable: Boolean,
      currentAdvanceReady: Boolean,
      futureAdvanceReady: Boolean,
      sameCycleReplaceReady: Boolean,
      futureWriteAccept: Boolean,
      writeCandidateValid: Boolean): Result = {
    val active = enable && !flush
    val livePromotionActive = active && livePromotionEnable
    val advanceEnable =
      if (livePromotionActive) futureAdvanceReady else currentAdvanceReady
    val replaceOnClear = livePromotionActive && sameCycleReplaceReady

    Result(
      active = active,
      livePromotionActive = livePromotionActive,
      advanceEnable = advanceEnable,
      replaceOnClear = replaceOnClear,
      usesFutureAdvance = livePromotionActive && futureAdvanceReady && !currentAdvanceReady,
      currentMatchesFuture = currentAdvanceReady == futureAdvanceReady,
      blockedByDisabled = !enable && writeCandidateValid,
      blockedByFlush = enable && flush && writeCandidateValid,
      blockedByLivePromotionDisabled =
        active && !livePromotionEnable && sameCycleReplaceReady,
      blockedByFutureAdvance =
        livePromotionActive && writeCandidateValid && !futureAdvanceReady,
      invalidReplaceWithoutFutureWrite = replaceOnClear && !futureWriteAccept,
      invalidFutureWriteWithoutAdvance =
        livePromotionActive && futureWriteAccept && !advanceEnable)
  }
}

class LoadReplayReturnPipeW2AdvanceControlSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2AdvanceControlReference._

  test("mirrors the current empty-only W1 advance gate while live promotion is disabled") {
    val blockedSameCycle = LoadReplayReturnPipeW2AdvanceControlReference(
      enable = true,
      flush = false,
      livePromotionEnable = false,
      currentAdvanceReady = false,
      futureAdvanceReady = true,
      sameCycleReplaceReady = true,
      futureWriteAccept = true,
      writeCandidateValid = true)

    assert(blockedSameCycle.active)
    assert(!blockedSameCycle.livePromotionActive)
    assert(!blockedSameCycle.advanceEnable)
    assert(!blockedSameCycle.replaceOnClear)
    assert(blockedSameCycle.blockedByLivePromotionDisabled)
    assert(!blockedSameCycle.currentMatchesFuture)

    val emptyCurrent = LoadReplayReturnPipeW2AdvanceControlReference(
      enable = true,
      flush = false,
      livePromotionEnable = false,
      currentAdvanceReady = true,
      futureAdvanceReady = true,
      sameCycleReplaceReady = false,
      futureWriteAccept = true,
      writeCandidateValid = true)
    assert(emptyCurrent.advanceEnable)
    assert(!emptyCurrent.replaceOnClear)
    assert(emptyCurrent.currentMatchesFuture)
  }

  test("selects future W2 readiness when live promotion is enabled") {
    val empty = LoadReplayReturnPipeW2AdvanceControlReference(
      enable = true,
      flush = false,
      livePromotionEnable = true,
      currentAdvanceReady = true,
      futureAdvanceReady = true,
      sameCycleReplaceReady = false,
      futureWriteAccept = true,
      writeCandidateValid = true)
    assert(empty.livePromotionActive)
    assert(empty.advanceEnable)
    assert(!empty.replaceOnClear)
    assert(!empty.usesFutureAdvance)

    val sameCycle = LoadReplayReturnPipeW2AdvanceControlReference(
      enable = true,
      flush = false,
      livePromotionEnable = true,
      currentAdvanceReady = false,
      futureAdvanceReady = true,
      sameCycleReplaceReady = true,
      futureWriteAccept = true,
      writeCandidateValid = true)
    assert(sameCycle.advanceEnable)
    assert(sameCycle.replaceOnClear)
    assert(sameCycle.usesFutureAdvance)
    assert(!sameCycle.blockedByLivePromotionDisabled)
  }

  test("reports disabled flush and future-readiness blockers") {
    val disabled = LoadReplayReturnPipeW2AdvanceControlReference(
      enable = false,
      flush = false,
      livePromotionEnable = true,
      currentAdvanceReady = true,
      futureAdvanceReady = true,
      sameCycleReplaceReady = true,
      futureWriteAccept = true,
      writeCandidateValid = true)
    assert(disabled.blockedByDisabled)
    assert(!disabled.active)
    assert(!disabled.livePromotionActive)
    assert(disabled.advanceEnable)

    val flushed = LoadReplayReturnPipeW2AdvanceControlReference(
      enable = true,
      flush = true,
      livePromotionEnable = true,
      currentAdvanceReady = false,
      futureAdvanceReady = false,
      sameCycleReplaceReady = true,
      futureWriteAccept = true,
      writeCandidateValid = true)
    assert(flushed.blockedByFlush)
    assert(!flushed.active)
    assert(!flushed.advanceEnable)

    val notReady = LoadReplayReturnPipeW2AdvanceControlReference(
      enable = true,
      flush = false,
      livePromotionEnable = true,
      currentAdvanceReady = false,
      futureAdvanceReady = false,
      sameCycleReplaceReady = false,
      futureWriteAccept = false,
      writeCandidateValid = true)
    assert(notReady.blockedByFutureAdvance)
    assert(!notReady.advanceEnable)
  }

  test("flags incoherent replacement and future-write evidence") {
    val replaceWithoutAccept = LoadReplayReturnPipeW2AdvanceControlReference(
      enable = true,
      flush = false,
      livePromotionEnable = true,
      currentAdvanceReady = false,
      futureAdvanceReady = true,
      sameCycleReplaceReady = true,
      futureWriteAccept = false,
      writeCandidateValid = true)
    assert(replaceWithoutAccept.replaceOnClear)
    assert(replaceWithoutAccept.invalidReplaceWithoutFutureWrite)

    val acceptWithoutAdvance = LoadReplayReturnPipeW2AdvanceControlReference(
      enable = true,
      flush = false,
      livePromotionEnable = true,
      currentAdvanceReady = false,
      futureAdvanceReady = false,
      sameCycleReplaceReady = false,
      futureWriteAccept = true,
      writeCandidateValid = true)
    assert(acceptWithoutAdvance.invalidFutureWriteWithoutAdvance)
  }

  test("Chisel LoadReplayReturnPipeW2AdvanceControl elaborates promotion diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPipeW2AdvanceControl)

    assert(sv.contains("module LoadReplayReturnPipeW2AdvanceControl"))
    assert(sv.contains("io_advanceEnable"))
    assert(sv.contains("io_replaceOnClear"))
    assert(sv.contains("io_invalidFutureWriteWithoutAdvance"))
  }
}
