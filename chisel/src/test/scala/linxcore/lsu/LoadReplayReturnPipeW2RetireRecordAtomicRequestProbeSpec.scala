package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2RetireRecordAtomicRequestProbeReference {
  final case class Result(
      active: Boolean,
      requestEvidenceValid: Boolean,
      rowFillCandidateAligned: Boolean,
      rowFillEnableAligned: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByNoLifecycleRow: Boolean,
      blockedByNoRowFillCandidate: Boolean,
      blockedByNoRowFillEnable: Boolean,
      invalidRowFillEnableWithoutEvidence: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      retireRecordValid: Boolean,
      lifecycleRowClearReady: Boolean,
      rowFillCandidateValid: Boolean,
      rowFillEnable: Boolean): Result = {
    val active = enable && !flush
    val observedIntent =
      retireRecordValid ||
        lifecycleRowClearReady ||
        rowFillCandidateValid ||
        rowFillEnable
    val requestEvidenceValid = active && retireRecordValid && lifecycleRowClearReady
    val rowFillCandidateAligned = requestEvidenceValid && rowFillCandidateValid
    val rowFillEnableAligned = rowFillCandidateAligned && rowFillEnable

    Result(
      active = active,
      requestEvidenceValid = requestEvidenceValid,
      rowFillCandidateAligned = rowFillCandidateAligned,
      rowFillEnableAligned = rowFillEnableAligned,
      blockedByDisabled = !enable && observedIntent,
      blockedByFlush = enable && flush && observedIntent,
      blockedByNoLifecycleRow = active && retireRecordValid && !lifecycleRowClearReady,
      blockedByNoRowFillCandidate = requestEvidenceValid && !rowFillCandidateValid,
      blockedByNoRowFillEnable = rowFillCandidateAligned && !rowFillEnable,
      invalidRowFillEnableWithoutEvidence = rowFillEnable && !requestEvidenceValid)
  }
}

class LoadReplayReturnPipeW2RetireRecordAtomicRequestProbeSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2RetireRecordAtomicRequestProbeReference._

  test("retained record request evidence aligns with row-fill candidate and enable") {
    val result = LoadReplayReturnPipeW2RetireRecordAtomicRequestProbeReference(
      enable = true,
      flush = false,
      retireRecordValid = true,
      lifecycleRowClearReady = true,
      rowFillCandidateValid = true,
      rowFillEnable = true)

    assert(result.active)
    assert(result.requestEvidenceValid)
    assert(result.rowFillCandidateAligned)
    assert(result.rowFillEnableAligned)
    assert(!result.blockedByNoRowFillCandidate)
    assert(!result.blockedByNoRowFillEnable)
  }

  test("retained record without lifecycle row blocks before row-fill alignment") {
    val result = LoadReplayReturnPipeW2RetireRecordAtomicRequestProbeReference(
      enable = true,
      flush = false,
      retireRecordValid = true,
      lifecycleRowClearReady = false,
      rowFillCandidateValid = true,
      rowFillEnable = true)

    assert(!result.requestEvidenceValid)
    assert(!result.rowFillCandidateAligned)
    assert(!result.rowFillEnableAligned)
    assert(result.blockedByNoLifecycleRow)
    assert(result.invalidRowFillEnableWithoutEvidence)
  }

  test("retained request evidence exposes row-fill candidate and enable blockers") {
    val noCandidate = LoadReplayReturnPipeW2RetireRecordAtomicRequestProbeReference(
      enable = true,
      flush = false,
      retireRecordValid = true,
      lifecycleRowClearReady = true,
      rowFillCandidateValid = false,
      rowFillEnable = true)
    val noEnable = LoadReplayReturnPipeW2RetireRecordAtomicRequestProbeReference(
      enable = true,
      flush = false,
      retireRecordValid = true,
      lifecycleRowClearReady = true,
      rowFillCandidateValid = true,
      rowFillEnable = false)

    assert(noCandidate.requestEvidenceValid)
    assert(noCandidate.blockedByNoRowFillCandidate)
    assert(noCandidate.invalidRowFillEnableWithoutEvidence == false)
    assert(noEnable.rowFillCandidateAligned)
    assert(noEnable.blockedByNoRowFillEnable)
  }

  test("disabled and flush states suppress request evidence") {
    val disabled = LoadReplayReturnPipeW2RetireRecordAtomicRequestProbeReference(
      enable = false,
      flush = false,
      retireRecordValid = true,
      lifecycleRowClearReady = true,
      rowFillCandidateValid = true,
      rowFillEnable = true)
    val flushed = LoadReplayReturnPipeW2RetireRecordAtomicRequestProbeReference(
      enable = true,
      flush = true,
      retireRecordValid = true,
      lifecycleRowClearReady = true,
      rowFillCandidateValid = true,
      rowFillEnable = true)

    assert(!disabled.active)
    assert(!disabled.requestEvidenceValid)
    assert(disabled.blockedByDisabled)
    assert(!flushed.active)
    assert(!flushed.requestEvidenceValid)
    assert(flushed.blockedByFlush)
  }

  test("Chisel LoadReplayReturnPipeW2RetireRecordAtomicRequestProbe elaborates diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPipeW2RetireRecordAtomicRequestProbe)

    assert(sv.contains("module LoadReplayReturnPipeW2RetireRecordAtomicRequestProbe"))
    assert(sv.contains("io_requestEvidenceValid"))
    assert(sv.contains("io_blockedByNoRowFillEnable"))
  }
}
