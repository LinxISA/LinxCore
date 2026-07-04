package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeResidencyAdvanceLiveControlReference {
  final case class Result(
      active: Boolean,
      requestActive: Boolean,
      advanceEvidenceValid: Boolean,
      advanceEnable: Boolean,
      evidenceTargetIsAgu: Boolean,
      evidenceTargetIsLda: Boolean,
      evidenceTargetPipeIndex: Int,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByRequestDisabled: Boolean,
      blockedByNoSlot: Boolean,
      blockedByInvalidTarget: Boolean,
      blockedByNoEvidence: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      requestEnable: Boolean,
      slotOccupied: Boolean,
      slotTargetIsAgu: Boolean,
      slotTargetIsLda: Boolean,
      slotPipeIndex: Int): Result = {
    val active = enable && !flush
    val requestActive = active && requestEnable
    val targetValid = slotTargetIsAgu ^ slotTargetIsLda
    val rawEvidence = slotOccupied && targetValid
    val advanceEvidenceValid = active && rawEvidence

    Result(
      active = active,
      requestActive = requestActive,
      advanceEvidenceValid = advanceEvidenceValid,
      advanceEnable = requestActive && rawEvidence,
      evidenceTargetIsAgu = advanceEvidenceValid && slotTargetIsAgu,
      evidenceTargetIsLda = advanceEvidenceValid && slotTargetIsLda,
      evidenceTargetPipeIndex = if (advanceEvidenceValid) slotPipeIndex else 0,
      blockedByDisabled = !enable && (requestEnable || rawEvidence),
      blockedByFlush = enable && flush && (requestEnable || rawEvidence),
      blockedByRequestDisabled = active && !requestEnable && rawEvidence,
      blockedByNoSlot = requestActive && !slotOccupied,
      blockedByInvalidTarget = requestActive && slotOccupied && !targetValid,
      blockedByNoEvidence = requestActive && !rawEvidence)
  }
}

class LoadReplayReturnPipeResidencyAdvanceLiveControlSpec extends AnyFunSuite {
  import LoadReplayReturnPipeResidencyAdvanceLiveControlReference._

  test("keeps E4-to-W1 advance disabled when the top request is false") {
    val result = LoadReplayReturnPipeResidencyAdvanceLiveControlReference(
      enable = true,
      flush = false,
      requestEnable = false,
      slotOccupied = true,
      slotTargetIsAgu = false,
      slotTargetIsLda = true,
      slotPipeIndex = 1)

    assert(result.active)
    assert(!result.requestActive)
    assert(result.advanceEvidenceValid)
    assert(!result.advanceEnable)
    assert(result.evidenceTargetIsLda)
    assert(result.evidenceTargetPipeIndex == 1)
    assert(result.blockedByRequestDisabled)
  }

  test("enables advance only with an active request and valid resident slot evidence") {
    val result = LoadReplayReturnPipeResidencyAdvanceLiveControlReference(
      enable = true,
      flush = false,
      requestEnable = true,
      slotOccupied = true,
      slotTargetIsAgu = true,
      slotTargetIsLda = false,
      slotPipeIndex = 0)

    assert(result.requestActive)
    assert(result.advanceEvidenceValid)
    assert(result.advanceEnable)
    assert(result.evidenceTargetIsAgu)
    assert(!result.blockedByNoEvidence)
  }

  test("reports disabled and flush blockers without enabling advance") {
    val disabled = LoadReplayReturnPipeResidencyAdvanceLiveControlReference(
      enable = false,
      flush = false,
      requestEnable = true,
      slotOccupied = true,
      slotTargetIsAgu = false,
      slotTargetIsLda = true,
      slotPipeIndex = 0)
    assert(disabled.blockedByDisabled)
    assert(!disabled.active)
    assert(!disabled.advanceEnable)

    val flushed = LoadReplayReturnPipeResidencyAdvanceLiveControlReference(
      enable = true,
      flush = true,
      requestEnable = true,
      slotOccupied = true,
      slotTargetIsAgu = false,
      slotTargetIsLda = true,
      slotPipeIndex = 0)
    assert(flushed.blockedByFlush)
    assert(!flushed.active)
    assert(!flushed.advanceEvidenceValid)
    assert(!flushed.advanceEnable)
  }

  test("reports empty-slot and invalid-target blockers for active requests") {
    val empty = LoadReplayReturnPipeResidencyAdvanceLiveControlReference(
      enable = true,
      flush = false,
      requestEnable = true,
      slotOccupied = false,
      slotTargetIsAgu = false,
      slotTargetIsLda = true,
      slotPipeIndex = 0)
    assert(empty.blockedByNoSlot)
    assert(empty.blockedByNoEvidence)
    assert(!empty.advanceEnable)

    val missingTarget = LoadReplayReturnPipeResidencyAdvanceLiveControlReference(
      enable = true,
      flush = false,
      requestEnable = true,
      slotOccupied = true,
      slotTargetIsAgu = false,
      slotTargetIsLda = false,
      slotPipeIndex = 1)
    assert(missingTarget.blockedByInvalidTarget)
    assert(missingTarget.blockedByNoEvidence)
    assert(!missingTarget.advanceEnable)

    val bothTargets = LoadReplayReturnPipeResidencyAdvanceLiveControlReference(
      enable = true,
      flush = false,
      requestEnable = true,
      slotOccupied = true,
      slotTargetIsAgu = true,
      slotTargetIsLda = true,
      slotPipeIndex = 1)
    assert(bothTargets.blockedByInvalidTarget)
    assert(bothTargets.blockedByNoEvidence)
    assert(!bothTargets.advanceEnable)
  }

  test("Chisel LoadReplayReturnPipeResidencyAdvanceLiveControl elaborates live-control diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(
      new LoadReplayReturnPipeResidencyAdvanceLiveControl(returnPipeCount = 2))

    assert(sv.contains("module LoadReplayReturnPipeResidencyAdvanceLiveControl"))
    assert(sv.contains("io_advanceEvidenceValid"))
    assert(sv.contains("io_advanceEnable"))
    assert(sv.contains("io_evidenceTargetPipeIndex"))
    assert(sv.contains("io_blockedByRequestDisabled"))
    assert(sv.contains("io_blockedByInvalidTarget"))
  }
}
