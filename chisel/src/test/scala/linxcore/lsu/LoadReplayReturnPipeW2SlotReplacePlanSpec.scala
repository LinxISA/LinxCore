package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2SlotReplacePlanReference {
  final case class Result(
      active: Boolean,
      writeTargetValid: Boolean,
      emptyWriteEligible: Boolean,
      sameCycleReplaceEligible: Boolean,
      sameCycleReplaceReady: Boolean,
      futureWriteAccept: Boolean,
      currentMatchesFutureWrite: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByNoWrite: Boolean,
      blockedByInvalidTarget: Boolean,
      blockedByOccupiedNoClear: Boolean,
      blockedByLiveClearDisabled: Boolean,
      blockedByCurrentStorage: Boolean,
      blockedByCurrentClearPriority: Boolean,
      invalidFutureAdvanceWithoutLiveClear: Boolean,
      invalidCurrentAcceptWithoutFuture: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      slotOccupied: Boolean,
      writeValid: Boolean,
      writeTargetIsAgu: Boolean,
      writeTargetIsLda: Boolean,
      clearIntent: Boolean,
      liveClear: Boolean,
      futureAdvanceReady: Boolean,
      currentSlotAccepted: Boolean,
      currentSlotBlockedByClear: Boolean): Result = {
    val active = enable && !flush
    val writeTargetValid = writeTargetIsAgu ^ writeTargetIsLda
    val writeCandidate = active && writeValid
    val emptyWriteEligible = writeCandidate && !slotOccupied && writeTargetValid
    val sameCycleReplaceEligible =
      writeCandidate && slotOccupied && writeTargetValid && clearIntent
    val sameCycleReplaceReady =
      sameCycleReplaceEligible && liveClear && futureAdvanceReady
    val futureWriteAccept = emptyWriteEligible || sameCycleReplaceReady

    Result(
      active = active,
      writeTargetValid = writeTargetValid,
      emptyWriteEligible = emptyWriteEligible,
      sameCycleReplaceEligible = sameCycleReplaceEligible,
      sameCycleReplaceReady = sameCycleReplaceReady,
      futureWriteAccept = futureWriteAccept,
      currentMatchesFutureWrite = currentSlotAccepted == futureWriteAccept,
      blockedByDisabled = !enable && writeValid,
      blockedByFlush = enable && flush && writeValid,
      blockedByNoWrite = active && !writeValid,
      blockedByInvalidTarget = writeCandidate && !writeTargetValid,
      blockedByOccupiedNoClear = writeCandidate && slotOccupied && writeTargetValid && !clearIntent,
      blockedByLiveClearDisabled = sameCycleReplaceEligible && !liveClear,
      blockedByCurrentStorage = futureWriteAccept && !currentSlotAccepted,
      blockedByCurrentClearPriority = sameCycleReplaceReady && currentSlotBlockedByClear,
      invalidFutureAdvanceWithoutLiveClear = active && slotOccupied && futureAdvanceReady && !liveClear,
      invalidCurrentAcceptWithoutFuture = currentSlotAccepted && !futureWriteAccept)
  }
}

class LoadReplayReturnPipeW2SlotReplacePlanSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2SlotReplacePlanReference._

  test("matches current W2 slot acceptance for an empty write") {
    val result = LoadReplayReturnPipeW2SlotReplacePlanReference(
      enable = true,
      flush = false,
      slotOccupied = false,
      writeValid = true,
      writeTargetIsAgu = false,
      writeTargetIsLda = true,
      clearIntent = false,
      liveClear = false,
      futureAdvanceReady = true,
      currentSlotAccepted = true,
      currentSlotBlockedByClear = false)

    assert(result.active)
    assert(result.writeTargetValid)
    assert(result.emptyWriteEligible)
    assert(!result.sameCycleReplaceEligible)
    assert(result.futureWriteAccept)
    assert(result.currentMatchesFutureWrite)
    assert(!result.blockedByCurrentStorage)
  }

  test("reports occupied W2 storage blocked before clear intent exists") {
    val result = LoadReplayReturnPipeW2SlotReplacePlanReference(
      enable = true,
      flush = false,
      slotOccupied = true,
      writeValid = true,
      writeTargetIsAgu = true,
      writeTargetIsLda = false,
      clearIntent = false,
      liveClear = false,
      futureAdvanceReady = false,
      currentSlotAccepted = false,
      currentSlotBlockedByClear = false)

    assert(!result.emptyWriteEligible)
    assert(!result.sameCycleReplaceEligible)
    assert(!result.futureWriteAccept)
    assert(result.currentMatchesFutureWrite)
    assert(result.blockedByOccupiedNoClear)
  }

  test("names same-cycle replacement eligibility while live clear is disabled") {
    val result = LoadReplayReturnPipeW2SlotReplacePlanReference(
      enable = true,
      flush = false,
      slotOccupied = true,
      writeValid = true,
      writeTargetIsAgu = false,
      writeTargetIsLda = true,
      clearIntent = true,
      liveClear = false,
      futureAdvanceReady = false,
      currentSlotAccepted = false,
      currentSlotBlockedByClear = false)

    assert(result.sameCycleReplaceEligible)
    assert(!result.sameCycleReplaceReady)
    assert(!result.futureWriteAccept)
    assert(result.currentMatchesFutureWrite)
    assert(result.blockedByLiveClearDisabled)
  }

  test("detects future same-cycle replacement that current storage cannot accept") {
    val result = LoadReplayReturnPipeW2SlotReplacePlanReference(
      enable = true,
      flush = false,
      slotOccupied = true,
      writeValid = true,
      writeTargetIsAgu = false,
      writeTargetIsLda = true,
      clearIntent = true,
      liveClear = true,
      futureAdvanceReady = true,
      currentSlotAccepted = false,
      currentSlotBlockedByClear = true)

    assert(result.sameCycleReplaceEligible)
    assert(result.sameCycleReplaceReady)
    assert(result.futureWriteAccept)
    assert(!result.currentMatchesFutureWrite)
    assert(result.blockedByCurrentStorage)
    assert(result.blockedByCurrentClearPriority)
  }

  test("rejects invalid targets and stray future readiness") {
    val invalidTarget = LoadReplayReturnPipeW2SlotReplacePlanReference(
      enable = true,
      flush = false,
      slotOccupied = false,
      writeValid = true,
      writeTargetIsAgu = true,
      writeTargetIsLda = true,
      clearIntent = false,
      liveClear = false,
      futureAdvanceReady = true,
      currentSlotAccepted = false,
      currentSlotBlockedByClear = false)
    val strayReady = LoadReplayReturnPipeW2SlotReplacePlanReference(
      enable = true,
      flush = false,
      slotOccupied = true,
      writeValid = false,
      writeTargetIsAgu = false,
      writeTargetIsLda = false,
      clearIntent = false,
      liveClear = false,
      futureAdvanceReady = true,
      currentSlotAccepted = false,
      currentSlotBlockedByClear = false)

    assert(invalidTarget.blockedByInvalidTarget)
    assert(!invalidTarget.futureWriteAccept)
    assert(strayReady.invalidFutureAdvanceWithoutLiveClear)
    assert(strayReady.blockedByNoWrite)
  }

  test("blocks disabled and flushed writes") {
    val disabled = LoadReplayReturnPipeW2SlotReplacePlanReference(
      enable = false,
      flush = false,
      slotOccupied = false,
      writeValid = true,
      writeTargetIsAgu = false,
      writeTargetIsLda = true,
      clearIntent = false,
      liveClear = false,
      futureAdvanceReady = true,
      currentSlotAccepted = false,
      currentSlotBlockedByClear = false)
    val flushed = LoadReplayReturnPipeW2SlotReplacePlanReference(
      enable = true,
      flush = true,
      slotOccupied = false,
      writeValid = true,
      writeTargetIsAgu = false,
      writeTargetIsLda = true,
      clearIntent = false,
      liveClear = false,
      futureAdvanceReady = true,
      currentSlotAccepted = false,
      currentSlotBlockedByClear = false)

    assert(disabled.blockedByDisabled)
    assert(!disabled.futureWriteAccept)
    assert(flushed.blockedByFlush)
    assert(!flushed.futureWriteAccept)
  }

  test("flags current acceptance when the future predicate rejects the write") {
    val result = LoadReplayReturnPipeW2SlotReplacePlanReference(
      enable = true,
      flush = false,
      slotOccupied = true,
      writeValid = true,
      writeTargetIsAgu = true,
      writeTargetIsLda = true,
      clearIntent = false,
      liveClear = false,
      futureAdvanceReady = false,
      currentSlotAccepted = true,
      currentSlotBlockedByClear = false)

    assert(!result.futureWriteAccept)
    assert(!result.currentMatchesFutureWrite)
    assert(result.invalidCurrentAcceptWithoutFuture)
  }

  test("Chisel LoadReplayReturnPipeW2SlotReplacePlan elaborates replacement diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPipeW2SlotReplacePlan)

    assert(sv.contains("module LoadReplayReturnPipeW2SlotReplacePlan"))
    assert(sv.contains("io_sameCycleReplaceEligible"))
    assert(sv.contains("io_futureWriteAccept"))
    assert(sv.contains("io_blockedByCurrentStorage"))
    assert(sv.contains("io_invalidFutureAdvanceWithoutLiveClear"))
  }
}
