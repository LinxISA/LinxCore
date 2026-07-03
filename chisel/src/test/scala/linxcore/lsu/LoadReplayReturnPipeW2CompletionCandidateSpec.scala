package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2CompletionCandidateReference {
  final case class Dst(valid: Boolean, kind: Int, physTag: Int)

  final case class Result(
      candidateValid: Boolean,
      targetValid: Boolean,
      completeValid: Boolean,
      clearSlot: Boolean,
      targetIsAgu: Boolean,
      targetIsLda: Boolean,
      targetPipeIndex: Int,
      resolveRequired: Boolean,
      resolveValid: Boolean,
      writebackRequired: Boolean,
      writebackValid: Boolean,
      writebackTag: Int,
      writebackData: BigInt,
      writebackIgnoredNoDestination: Boolean,
      writebackIgnoredNonGprDestination: Boolean,
      wakeupRequired: Boolean,
      wakeupValid: Boolean,
      wakeupKind: Int,
      wakeupTag: Int,
      reducedGprWakeupValid: Boolean,
      nonGprWakeup: Boolean,
      suppressedWakeupNotRequired: Boolean,
      ignoredNoWakeupDestination: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByNoSlot: Boolean,
      blockedByInvalidTarget: Boolean,
      blockedBySideEffects: Boolean)

  private val NoneKind = 0
  private val GprKind = 1

  def apply(
      enable: Boolean,
      flush: Boolean,
      sideEffectsReady: Boolean,
      slotOccupied: Boolean,
      slotTargetIsAgu: Boolean,
      slotTargetIsLda: Boolean,
      slotPipeIndex: Int,
      dst: Dst,
      slotData: BigInt,
      slotWakeupRequired: Boolean): Result = {
    val candidateValid = enable && !flush && slotOccupied
    val targetValid = slotTargetIsAgu ^ slotTargetIsLda
    val sideEffectCandidateValid = candidateValid && targetValid
    val completeValid = sideEffectCandidateValid && sideEffectsReady
    val hasDestination = dst.valid && dst.kind != NoneKind
    val isGprDestination = hasDestination && dst.kind == GprKind
    val writebackRequired = sideEffectCandidateValid && isGprDestination
    val wakeupRequired = sideEffectCandidateValid && slotWakeupRequired
    val wakeupValid = completeValid && slotWakeupRequired && hasDestination

    Result(
      candidateValid = candidateValid,
      targetValid = candidateValid && targetValid,
      completeValid = completeValid,
      clearSlot = completeValid,
      targetIsAgu = sideEffectCandidateValid && slotTargetIsAgu,
      targetIsLda = sideEffectCandidateValid && slotTargetIsLda,
      targetPipeIndex = if (sideEffectCandidateValid) slotPipeIndex else 0,
      resolveRequired = sideEffectCandidateValid,
      resolveValid = completeValid,
      writebackRequired = writebackRequired,
      writebackValid = completeValid && isGprDestination,
      writebackTag = if (completeValid && isGprDestination) dst.physTag else 0,
      writebackData = if (completeValid && isGprDestination) slotData else BigInt(0),
      writebackIgnoredNoDestination = sideEffectCandidateValid && !hasDestination,
      writebackIgnoredNonGprDestination = sideEffectCandidateValid && hasDestination && !isGprDestination,
      wakeupRequired = wakeupRequired,
      wakeupValid = wakeupValid,
      wakeupKind = if (wakeupValid) dst.kind else NoneKind,
      wakeupTag = if (wakeupValid) dst.physTag else 0,
      reducedGprWakeupValid = wakeupValid && isGprDestination,
      nonGprWakeup = wakeupValid && !isGprDestination,
      suppressedWakeupNotRequired = sideEffectCandidateValid && !slotWakeupRequired,
      ignoredNoWakeupDestination = wakeupRequired && !hasDestination,
      blockedByDisabled = !enable && slotOccupied,
      blockedByFlush = enable && flush && slotOccupied,
      blockedByNoSlot = enable && !flush && !slotOccupied,
      blockedByInvalidTarget = candidateValid && !targetValid,
      blockedBySideEffects = sideEffectCandidateValid && !sideEffectsReady)
  }
}

class LoadReplayReturnPipeW2CompletionCandidateSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2CompletionCandidateReference._

  test("completes a ready scalar LDA W2 entry and emits side effects") {
    val result = LoadReplayReturnPipeW2CompletionCandidateReference(
      enable = true,
      flush = false,
      sideEffectsReady = true,
      slotOccupied = true,
      slotTargetIsAgu = false,
      slotTargetIsLda = true,
      slotPipeIndex = 1,
      dst = Dst(valid = true, kind = 1, physTag = 42),
      slotData = BigInt("8877665544332211", 16),
      slotWakeupRequired = true)

    assert(result.candidateValid)
    assert(result.targetValid)
    assert(result.completeValid)
    assert(result.clearSlot)
    assert(result.targetIsLda)
    assert(!result.targetIsAgu)
    assert(result.targetPipeIndex == 1)
    assert(result.resolveRequired)
    assert(result.resolveValid)
    assert(result.writebackRequired)
    assert(result.writebackValid)
    assert(result.writebackTag == 42)
    assert(result.writebackData == BigInt("8877665544332211", 16))
    assert(result.wakeupRequired)
    assert(result.wakeupValid)
    assert(result.reducedGprWakeupValid)
  }

  test("holds a W2 entry when side-effect sinks are not ready") {
    val result = LoadReplayReturnPipeW2CompletionCandidateReference(
      enable = true,
      flush = false,
      sideEffectsReady = false,
      slotOccupied = true,
      slotTargetIsAgu = true,
      slotTargetIsLda = false,
      slotPipeIndex = 0,
      dst = Dst(valid = true, kind = 1, physTag = 7),
      slotData = 0x55,
      slotWakeupRequired = true)

    assert(result.candidateValid)
    assert(result.targetIsAgu)
    assert(result.resolveRequired)
    assert(result.writebackRequired)
    assert(result.wakeupRequired)
    assert(!result.completeValid)
    assert(!result.clearSlot)
    assert(!result.resolveValid)
    assert(!result.writebackValid)
    assert(!result.wakeupValid)
    assert(result.blockedBySideEffects)
  }

  test("does not require writeback for missing or non-GPR destinations") {
    val missing = LoadReplayReturnPipeW2CompletionCandidateReference(
      enable = true,
      flush = false,
      sideEffectsReady = true,
      slotOccupied = true,
      slotTargetIsAgu = false,
      slotTargetIsLda = true,
      slotPipeIndex = 0,
      dst = Dst(valid = false, kind = 0, physTag = 9),
      slotData = 0x1234,
      slotWakeupRequired = true)
    val local = LoadReplayReturnPipeW2CompletionCandidateReference(
      enable = true,
      flush = false,
      sideEffectsReady = true,
      slotOccupied = true,
      slotTargetIsAgu = false,
      slotTargetIsLda = true,
      slotPipeIndex = 0,
      dst = Dst(valid = true, kind = 2, physTag = 9),
      slotData = 0x1234,
      slotWakeupRequired = true)

    assert(missing.completeValid)
    assert(missing.resolveValid)
    assert(!missing.writebackRequired)
    assert(!missing.writebackValid)
    assert(missing.writebackIgnoredNoDestination)
    assert(missing.ignoredNoWakeupDestination)
    assert(local.completeValid)
    assert(!local.writebackRequired)
    assert(!local.writebackValid)
    assert(local.writebackIgnoredNonGprDestination)
    assert(local.wakeupValid)
    assert(local.nonGprWakeup)
  }

  test("reports disabled flush no-slot invalid-target and optional-wakeup cases") {
    val disabled = LoadReplayReturnPipeW2CompletionCandidateReference(
      enable = false,
      flush = false,
      sideEffectsReady = true,
      slotOccupied = true,
      slotTargetIsAgu = false,
      slotTargetIsLda = true,
      slotPipeIndex = 0,
      dst = Dst(valid = true, kind = 1, physTag = 7),
      slotData = 0x55,
      slotWakeupRequired = true)
    val flushed = LoadReplayReturnPipeW2CompletionCandidateReference(
      enable = true,
      flush = true,
      sideEffectsReady = true,
      slotOccupied = true,
      slotTargetIsAgu = false,
      slotTargetIsLda = true,
      slotPipeIndex = 0,
      dst = Dst(valid = true, kind = 1, physTag = 7),
      slotData = 0x55,
      slotWakeupRequired = true)
    val empty = LoadReplayReturnPipeW2CompletionCandidateReference(
      enable = true,
      flush = false,
      sideEffectsReady = true,
      slotOccupied = false,
      slotTargetIsAgu = false,
      slotTargetIsLda = true,
      slotPipeIndex = 0,
      dst = Dst(valid = true, kind = 1, physTag = 7),
      slotData = 0x55,
      slotWakeupRequired = true)
    val invalid = LoadReplayReturnPipeW2CompletionCandidateReference(
      enable = true,
      flush = false,
      sideEffectsReady = true,
      slotOccupied = true,
      slotTargetIsAgu = true,
      slotTargetIsLda = true,
      slotPipeIndex = 0,
      dst = Dst(valid = true, kind = 1, physTag = 7),
      slotData = 0x55,
      slotWakeupRequired = true)
    val noWakeup = LoadReplayReturnPipeW2CompletionCandidateReference(
      enable = true,
      flush = false,
      sideEffectsReady = true,
      slotOccupied = true,
      slotTargetIsAgu = false,
      slotTargetIsLda = true,
      slotPipeIndex = 0,
      dst = Dst(valid = true, kind = 1, physTag = 7),
      slotData = 0x55,
      slotWakeupRequired = false)

    assert(disabled.blockedByDisabled)
    assert(!disabled.candidateValid)
    assert(flushed.blockedByFlush)
    assert(!flushed.candidateValid)
    assert(empty.blockedByNoSlot)
    assert(!empty.candidateValid)
    assert(invalid.candidateValid)
    assert(invalid.blockedByInvalidTarget)
    assert(!invalid.completeValid)
    assert(noWakeup.completeValid)
    assert(noWakeup.writebackValid)
    assert(!noWakeup.wakeupRequired)
    assert(!noWakeup.wakeupValid)
    assert(noWakeup.suppressedWakeupNotRequired)
  }

  test("Chisel LoadReplayReturnPipeW2CompletionCandidate elaborates W2 completion diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(
      new LoadReplayReturnPipeW2CompletionCandidate(returnPipeCount = 2))

    assert(sv.contains("module LoadReplayReturnPipeW2CompletionCandidate"))
    assert(sv.contains("io_sideEffectsReady"))
    assert(sv.contains("io_completeValid"))
    assert(sv.contains("io_clearSlot"))
    assert(sv.contains("io_resolveValid"))
    assert(sv.contains("io_writebackValid"))
    assert(sv.contains("io_wakeupValid"))
    assert(sv.contains("io_blockedBySideEffects"))
  }
}
