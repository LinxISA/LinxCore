package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW1AdvanceCandidateReference {
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

class LoadReplayReturnPipeW1AdvanceCandidateSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW1AdvanceCandidateReference._

  test("advances a scalar LDA W1 slot and requests slot clear when enabled") {
    val result = LoadReplayReturnPipeW1AdvanceCandidateReference(
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

  test("keeps a vector AGU W1 slot resident while advance is live-disabled") {
    val result = LoadReplayReturnPipeW1AdvanceCandidateReference(
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

  test("reports disabled flush and empty-W1-slot blockers") {
    val disabled = LoadReplayReturnPipeW1AdvanceCandidateReference(
      enable = false,
      flush = false,
      advanceEnable = true,
      slotOccupied = true,
      slotTargetIsAgu = false,
      slotTargetIsLda = true,
      slotPipeIndex = 0)
    assert(disabled.blockedByDisabled)
    assert(!disabled.candidateValid)

    val flushed = LoadReplayReturnPipeW1AdvanceCandidateReference(
      enable = true,
      flush = true,
      advanceEnable = true,
      slotOccupied = true,
      slotTargetIsAgu = false,
      slotTargetIsLda = true,
      slotPipeIndex = 0)
    assert(flushed.blockedByFlush)
    assert(!flushed.candidateValid)

    val empty = LoadReplayReturnPipeW1AdvanceCandidateReference(
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

  test("rejects occupied W1 slots without exactly one target family") {
    val missingTarget = LoadReplayReturnPipeW1AdvanceCandidateReference(
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

    val bothTargets = LoadReplayReturnPipeW1AdvanceCandidateReference(
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

  test("Chisel LoadReplayReturnPipeW1AdvanceCandidate elaborates clear diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(
      new LoadReplayReturnPipeW1AdvanceCandidate(returnPipeCount = 2))

    assert(sv.contains("module LoadReplayReturnPipeW1AdvanceCandidate"))
    assert(sv.contains("io_clearSlot"))
    assert(sv.contains("io_blockedByAdvanceDisabled"))
    assert(sv.contains("io_blockedByInvalidTarget"))
  }
}
