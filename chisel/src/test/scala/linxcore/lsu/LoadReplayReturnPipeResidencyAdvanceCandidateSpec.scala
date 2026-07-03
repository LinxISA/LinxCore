package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeResidencyAdvanceCandidateReference {
  final case class Result(
      candidateValid: Boolean,
      advanceValid: Boolean,
      clearSlot: Boolean,
      targetIsAgu: Boolean,
      targetIsLda: Boolean,
      targetPipeIndex: Int,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByNoSlot: Boolean,
      blockedByAdvanceDisabled: Boolean,
      blockedByInvalidTarget: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      advanceEnable: Boolean,
      slotOccupied: Boolean,
      slotTargetIsAgu: Boolean,
      slotTargetIsLda: Boolean,
      slotPipeIndex: Int): Result = {
    val candidateValid = enable && !flush && slotOccupied
    val targetValid = slotTargetIsAgu ^ slotTargetIsLda
    val advanceValid = candidateValid && targetValid && advanceEnable

    Result(
      candidateValid = candidateValid,
      advanceValid = advanceValid,
      clearSlot = advanceValid,
      targetIsAgu = candidateValid && targetValid && slotTargetIsAgu,
      targetIsLda = candidateValid && targetValid && slotTargetIsLda,
      targetPipeIndex = if (candidateValid && targetValid) slotPipeIndex else 0,
      blockedByDisabled = !enable && slotOccupied,
      blockedByFlush = enable && flush && slotOccupied,
      blockedByNoSlot = enable && !flush && !slotOccupied,
      blockedByAdvanceDisabled = candidateValid && targetValid && !advanceEnable,
      blockedByInvalidTarget = candidateValid && !targetValid)
  }
}

class LoadReplayReturnPipeResidencyAdvanceCandidateSpec extends AnyFunSuite {
  import LoadReplayReturnPipeResidencyAdvanceCandidateReference._

  test("advances a scalar LDA slot and requests slot clear when enabled") {
    val result = LoadReplayReturnPipeResidencyAdvanceCandidateReference(
      enable = true,
      flush = false,
      advanceEnable = true,
      slotOccupied = true,
      slotTargetIsAgu = false,
      slotTargetIsLda = true,
      slotPipeIndex = 1)

    assert(result.candidateValid)
    assert(result.advanceValid)
    assert(result.clearSlot)
    assert(!result.targetIsAgu)
    assert(result.targetIsLda)
    assert(result.targetPipeIndex == 1)
  }

  test("keeps a vector AGU slot resident while advance is live-disabled") {
    val result = LoadReplayReturnPipeResidencyAdvanceCandidateReference(
      enable = true,
      flush = false,
      advanceEnable = false,
      slotOccupied = true,
      slotTargetIsAgu = true,
      slotTargetIsLda = false,
      slotPipeIndex = 0)

    assert(result.candidateValid)
    assert(!result.advanceValid)
    assert(!result.clearSlot)
    assert(result.targetIsAgu)
    assert(!result.targetIsLda)
    assert(result.blockedByAdvanceDisabled)
  }

  test("reports disabled flush and empty-slot blockers") {
    val disabled = LoadReplayReturnPipeResidencyAdvanceCandidateReference(
      enable = false,
      flush = false,
      advanceEnable = true,
      slotOccupied = true,
      slotTargetIsAgu = false,
      slotTargetIsLda = true,
      slotPipeIndex = 0)
    assert(disabled.blockedByDisabled)
    assert(!disabled.candidateValid)

    val flushed = LoadReplayReturnPipeResidencyAdvanceCandidateReference(
      enable = true,
      flush = true,
      advanceEnable = true,
      slotOccupied = true,
      slotTargetIsAgu = false,
      slotTargetIsLda = true,
      slotPipeIndex = 0)
    assert(flushed.blockedByFlush)
    assert(!flushed.candidateValid)

    val empty = LoadReplayReturnPipeResidencyAdvanceCandidateReference(
      enable = true,
      flush = false,
      advanceEnable = true,
      slotOccupied = false,
      slotTargetIsAgu = false,
      slotTargetIsLda = false,
      slotPipeIndex = 1)
    assert(empty.blockedByNoSlot)
    assert(!empty.candidateValid)
    assert(empty.targetPipeIndex == 0)
  }

  test("rejects occupied slots without exactly one target family") {
    val missingTarget = LoadReplayReturnPipeResidencyAdvanceCandidateReference(
      enable = true,
      flush = false,
      advanceEnable = true,
      slotOccupied = true,
      slotTargetIsAgu = false,
      slotTargetIsLda = false,
      slotPipeIndex = 1)
    assert(missingTarget.candidateValid)
    assert(missingTarget.blockedByInvalidTarget)
    assert(!missingTarget.advanceValid)

    val bothTargets = LoadReplayReturnPipeResidencyAdvanceCandidateReference(
      enable = true,
      flush = false,
      advanceEnable = true,
      slotOccupied = true,
      slotTargetIsAgu = true,
      slotTargetIsLda = true,
      slotPipeIndex = 1)
    assert(bothTargets.candidateValid)
    assert(bothTargets.blockedByInvalidTarget)
    assert(!bothTargets.advanceValid)
  }

  test("Chisel LoadReplayReturnPipeResidencyAdvanceCandidate elaborates clear diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(
      new LoadReplayReturnPipeResidencyAdvanceCandidate(returnPipeCount = 2))

    assert(sv.contains("module LoadReplayReturnPipeResidencyAdvanceCandidate"))
    assert(sv.contains("io_clearSlot"))
    assert(sv.contains("io_blockedByAdvanceDisabled"))
    assert(sv.contains("io_blockedByInvalidTarget"))
  }
}
