package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2ClearIntentReference {
  final case class Result(
      candidateValid: Boolean,
      preClearEligible: Boolean,
      permitEligible: Boolean,
      postFireEligible: Boolean,
      completionPermitMatchesClear: Boolean,
      fireCompleteMatchesClear: Boolean,
      clearIntent: Boolean,
      liveClear: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByNoSlot: Boolean,
      blockedByCompletionClear: Boolean,
      blockedByCompletionPermit: Boolean,
      blockedByFireComplete: Boolean,
      blockedByLiveClearDisabled: Boolean,
      blockedByCompletionPermitMismatch: Boolean,
      blockedByFireCompleteMismatch: Boolean,
      invalidEvidenceWithoutSlot: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      slotOccupied: Boolean,
      completionClearSlot: Boolean,
      completionPermitted: Boolean,
      fireComplete: Boolean,
      liveClearEnable: Boolean): Result = {
    val active = enable && !flush
    val candidateValid = active && slotOccupied
    val anyClearEvidence = completionClearSlot || completionPermitted || fireComplete
    val completionPermitMatchesClear = completionClearSlot == completionPermitted
    val fireCompleteMatchesClear = completionClearSlot == fireComplete
    val clearIntent = candidateValid && completionClearSlot && completionPermitted && fireComplete

    Result(
      candidateValid = candidateValid,
      preClearEligible = candidateValid && completionClearSlot,
      permitEligible = candidateValid && completionPermitted,
      postFireEligible = candidateValid && fireComplete,
      completionPermitMatchesClear = completionPermitMatchesClear,
      fireCompleteMatchesClear = fireCompleteMatchesClear,
      clearIntent = clearIntent,
      liveClear = clearIntent && liveClearEnable,
      blockedByDisabled = !enable && slotOccupied,
      blockedByFlush = enable && flush && slotOccupied,
      blockedByNoSlot = active && !slotOccupied,
      blockedByCompletionClear = candidateValid && !completionClearSlot,
      blockedByCompletionPermit = candidateValid && !completionPermitted,
      blockedByFireComplete = candidateValid && !fireComplete,
      blockedByLiveClearDisabled = clearIntent && !liveClearEnable,
      blockedByCompletionPermitMismatch = candidateValid && !completionPermitMatchesClear,
      blockedByFireCompleteMismatch = candidateValid && !fireCompleteMatchesClear,
      invalidEvidenceWithoutSlot = active && !slotOccupied && anyClearEvidence)
  }
}

class LoadReplayReturnPipeW2ClearIntentSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2ClearIntentReference._

  test("forms a dormant clear intent when pre-clear permit and post-fire evidence agree") {
    val result = LoadReplayReturnPipeW2ClearIntentReference(
      enable = true,
      flush = false,
      slotOccupied = true,
      completionClearSlot = true,
      completionPermitted = true,
      fireComplete = true,
      liveClearEnable = false)

    assert(result.candidateValid)
    assert(result.preClearEligible)
    assert(result.permitEligible)
    assert(result.postFireEligible)
    assert(result.completionPermitMatchesClear)
    assert(result.fireCompleteMatchesClear)
    assert(result.clearIntent)
    assert(!result.liveClear)
    assert(result.blockedByLiveClearDisabled)
  }

  test("emits live clear only behind the explicit live clear enable") {
    val result = LoadReplayReturnPipeW2ClearIntentReference(
      enable = true,
      flush = false,
      slotOccupied = true,
      completionClearSlot = true,
      completionPermitted = true,
      fireComplete = true,
      liveClearEnable = true)

    assert(result.clearIntent)
    assert(result.liveClear)
    assert(!result.blockedByLiveClearDisabled)
  }

  test("blocks without resident W2 slot and reports stray clear evidence") {
    val result = LoadReplayReturnPipeW2ClearIntentReference(
      enable = true,
      flush = false,
      slotOccupied = false,
      completionClearSlot = true,
      completionPermitted = true,
      fireComplete = true,
      liveClearEnable = true)

    assert(!result.candidateValid)
    assert(!result.clearIntent)
    assert(!result.liveClear)
    assert(result.blockedByNoSlot)
    assert(result.invalidEvidenceWithoutSlot)
  }

  test("blocks disabled and flushed resident W2 slots") {
    val disabled = LoadReplayReturnPipeW2ClearIntentReference(
      enable = false,
      flush = false,
      slotOccupied = true,
      completionClearSlot = true,
      completionPermitted = true,
      fireComplete = true,
      liveClearEnable = true)
    val flushed = LoadReplayReturnPipeW2ClearIntentReference(
      enable = true,
      flush = true,
      slotOccupied = true,
      completionClearSlot = true,
      completionPermitted = true,
      fireComplete = true,
      liveClearEnable = true)

    assert(!disabled.clearIntent)
    assert(disabled.blockedByDisabled)
    assert(!flushed.clearIntent)
    assert(flushed.blockedByFlush)
  }

  test("keeps intent low when the pre-clear owner does not clear") {
    val result = LoadReplayReturnPipeW2ClearIntentReference(
      enable = true,
      flush = false,
      slotOccupied = true,
      completionClearSlot = false,
      completionPermitted = true,
      fireComplete = true,
      liveClearEnable = true)

    assert(!result.clearIntent)
    assert(result.blockedByCompletionClear)
    assert(result.blockedByCompletionPermitMismatch)
    assert(result.blockedByFireCompleteMismatch)
  }

  test("keeps intent low when pre-clear permit and post-fire completion diverge") {
    val result = LoadReplayReturnPipeW2ClearIntentReference(
      enable = true,
      flush = false,
      slotOccupied = true,
      completionClearSlot = true,
      completionPermitted = true,
      fireComplete = false,
      liveClearEnable = true)

    assert(!result.clearIntent)
    assert(result.preClearEligible)
    assert(result.permitEligible)
    assert(!result.postFireEligible)
    assert(result.blockedByFireComplete)
    assert(result.blockedByFireCompleteMismatch)
    assert(!result.blockedByCompletionPermitMismatch)
  }

  test("keeps intent low when the pre-clear permit is missing") {
    val result = LoadReplayReturnPipeW2ClearIntentReference(
      enable = true,
      flush = false,
      slotOccupied = true,
      completionClearSlot = true,
      completionPermitted = false,
      fireComplete = true,
      liveClearEnable = true)

    assert(!result.clearIntent)
    assert(result.preClearEligible)
    assert(!result.permitEligible)
    assert(result.blockedByCompletionPermit)
    assert(result.blockedByCompletionPermitMismatch)
    assert(!result.blockedByFireCompleteMismatch)
  }

  test("Chisel LoadReplayReturnPipeW2ClearIntent elaborates clear diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPipeW2ClearIntent)

    assert(sv.contains("module LoadReplayReturnPipeW2ClearIntent"))
    assert(sv.contains("io_completionClearSlot"))
    assert(sv.contains("io_completionPermitted"))
    assert(sv.contains("io_fireComplete"))
    assert(sv.contains("io_clearIntent"))
    assert(sv.contains("io_liveClear"))
    assert(sv.contains("io_invalidEvidenceWithoutSlot"))
  }
}
