package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2PromotionControlReference {
  final case class Result(
      active: Boolean,
      requestActive: Boolean,
      emptyPromotion: Boolean,
      sameCyclePromotion: Boolean,
      livePromotionEnable: Boolean,
      liveClearEnable: Boolean,
      advanceLivePromotionEnable: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByPromotionDisabled: Boolean,
      blockedByClearIntent: Boolean,
      invalidClearIntentWithoutSlot: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      promotionRequested: Boolean,
      slotOccupied: Boolean,
      clearIntent: Boolean,
      writeCandidateValid: Boolean): Result = {
    val active = enable && !flush
    val requestActive = active && promotionRequested
    val validClearIntent = slotOccupied && clearIntent
    val emptyPromotion = requestActive && !slotOccupied && !clearIntent
    val sameCyclePromotion = requestActive && validClearIntent
    val livePromotionEnable = emptyPromotion || sameCyclePromotion

    Result(
      active = active,
      requestActive = requestActive,
      emptyPromotion = emptyPromotion,
      sameCyclePromotion = sameCyclePromotion,
      livePromotionEnable = livePromotionEnable,
      liveClearEnable = sameCyclePromotion,
      advanceLivePromotionEnable = livePromotionEnable,
      blockedByDisabled = !enable && (clearIntent || writeCandidateValid),
      blockedByFlush = enable && flush && (clearIntent || writeCandidateValid),
      blockedByPromotionDisabled =
        active && !promotionRequested && (clearIntent || writeCandidateValid),
      blockedByClearIntent = requestActive && slotOccupied && !clearIntent,
      invalidClearIntentWithoutSlot = active && !slotOccupied && clearIntent)
  }
}

class LoadReplayReturnPipeW2PromotionControlSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2PromotionControlReference._

  test("keeps current W2 clear and advance promotion disabled by default") {
    val result = LoadReplayReturnPipeW2PromotionControlReference(
      enable = true,
      flush = false,
      promotionRequested = false,
      slotOccupied = true,
      clearIntent = true,
      writeCandidateValid = true)

    assert(result.active)
    assert(!result.requestActive)
    assert(!result.livePromotionEnable)
    assert(!result.liveClearEnable)
    assert(!result.advanceLivePromotionEnable)
    assert(result.blockedByPromotionDisabled)
  }

  test("enables only advance live promotion for an empty W2 slot") {
    val result = LoadReplayReturnPipeW2PromotionControlReference(
      enable = true,
      flush = false,
      promotionRequested = true,
      slotOccupied = false,
      clearIntent = false,
      writeCandidateValid = true)

    assert(result.requestActive)
    assert(result.emptyPromotion)
    assert(!result.sameCyclePromotion)
    assert(result.livePromotionEnable)
    assert(!result.liveClearEnable)
    assert(result.advanceLivePromotionEnable)
  }

  test("enables clear and advance promotion together for same-cycle refill") {
    val result = LoadReplayReturnPipeW2PromotionControlReference(
      enable = true,
      flush = false,
      promotionRequested = true,
      slotOccupied = true,
      clearIntent = true,
      writeCandidateValid = true)

    assert(result.requestActive)
    assert(!result.emptyPromotion)
    assert(result.sameCyclePromotion)
    assert(result.livePromotionEnable)
    assert(result.liveClearEnable)
    assert(result.advanceLivePromotionEnable)
  }

  test("reports disabled flush and missing-clear blockers") {
    val disabled = LoadReplayReturnPipeW2PromotionControlReference(
      enable = false,
      flush = false,
      promotionRequested = true,
      slotOccupied = true,
      clearIntent = true,
      writeCandidateValid = false)
    assert(disabled.blockedByDisabled)
    assert(!disabled.active)
    assert(!disabled.livePromotionEnable)

    val flushed = LoadReplayReturnPipeW2PromotionControlReference(
      enable = true,
      flush = true,
      promotionRequested = true,
      slotOccupied = true,
      clearIntent = true,
      writeCandidateValid = false)
    assert(flushed.blockedByFlush)
    assert(!flushed.active)
    assert(!flushed.livePromotionEnable)

    val missingClear = LoadReplayReturnPipeW2PromotionControlReference(
      enable = true,
      flush = false,
      promotionRequested = true,
      slotOccupied = true,
      clearIntent = false,
      writeCandidateValid = true)
    assert(missingClear.blockedByClearIntent)
    assert(!missingClear.livePromotionEnable)
  }

  test("rejects clear-intent evidence when W2 is empty") {
    val result = LoadReplayReturnPipeW2PromotionControlReference(
      enable = true,
      flush = false,
      promotionRequested = true,
      slotOccupied = false,
      clearIntent = true,
      writeCandidateValid = true)

    assert(result.invalidClearIntentWithoutSlot)
    assert(!result.emptyPromotion)
    assert(!result.sameCyclePromotion)
    assert(!result.livePromotionEnable)
  }

  test("Chisel LoadReplayReturnPipeW2PromotionControl elaborates live enable ports") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPipeW2PromotionControl)

    assert(sv.contains("module LoadReplayReturnPipeW2PromotionControl"))
    assert(sv.contains("io_livePromotionEnable"))
    assert(sv.contains("io_liveClearEnable"))
    assert(sv.contains("io_advanceLivePromotionEnable"))
  }
}
