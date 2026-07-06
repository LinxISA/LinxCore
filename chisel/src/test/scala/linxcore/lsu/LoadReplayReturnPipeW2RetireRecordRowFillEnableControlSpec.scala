package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2RetireRecordRowFillEnableControlReference {
  final case class Result(
      active: Boolean,
      requestEvidenceValid: Boolean,
      candidateAligned: Boolean,
      rowFillEnable: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByRequestDisabled: Boolean,
      blockedByNoLifecycleRow: Boolean,
      blockedByNoRowFillCandidate: Boolean,
      invalidRowFillCandidateWithoutEvidence: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      rowFillRequested: Boolean,
      retireRecordValid: Boolean,
      lifecycleRowClearReady: Boolean,
      rowFillCandidateValid: Boolean): Result = {
    val active = enable && !flush
    val observedIntent =
      rowFillRequested ||
        retireRecordValid ||
        lifecycleRowClearReady ||
        rowFillCandidateValid
    val requestEvidenceValid = active && retireRecordValid && lifecycleRowClearReady
    val candidateAligned = requestEvidenceValid && rowFillCandidateValid
    val rowFillEnable = rowFillRequested && candidateAligned

    Result(
      active = active,
      requestEvidenceValid = requestEvidenceValid,
      candidateAligned = candidateAligned,
      rowFillEnable = rowFillEnable,
      blockedByDisabled = !enable && observedIntent,
      blockedByFlush = enable && flush && observedIntent,
      blockedByRequestDisabled = candidateAligned && !rowFillRequested,
      blockedByNoLifecycleRow = active && retireRecordValid && !lifecycleRowClearReady,
      blockedByNoRowFillCandidate = requestEvidenceValid && !rowFillCandidateValid,
      invalidRowFillCandidateWithoutEvidence = rowFillCandidateValid && !requestEvidenceValid)
  }
}

class LoadReplayReturnPipeW2RetireRecordRowFillEnableControlSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2RetireRecordRowFillEnableControlReference._

  test("enables retained row fill when request evidence and candidate align") {
    val result = LoadReplayReturnPipeW2RetireRecordRowFillEnableControlReference(
      enable = true,
      flush = false,
      rowFillRequested = true,
      retireRecordValid = true,
      lifecycleRowClearReady = true,
      rowFillCandidateValid = true)

    assert(result.active)
    assert(result.requestEvidenceValid)
    assert(result.candidateAligned)
    assert(result.rowFillEnable)
  }

  test("keeps aligned retained candidate dormant while row-fill request is disabled") {
    val result = LoadReplayReturnPipeW2RetireRecordRowFillEnableControlReference(
      enable = true,
      flush = false,
      rowFillRequested = false,
      retireRecordValid = true,
      lifecycleRowClearReady = true,
      rowFillCandidateValid = true)

    assert(result.requestEvidenceValid)
    assert(result.candidateAligned)
    assert(!result.rowFillEnable)
    assert(result.blockedByRequestDisabled)
  }

  test("reports retained evidence blockers before candidate alignment") {
    val noLifecycle = LoadReplayReturnPipeW2RetireRecordRowFillEnableControlReference(
      enable = true,
      flush = false,
      rowFillRequested = true,
      retireRecordValid = true,
      lifecycleRowClearReady = false,
      rowFillCandidateValid = true)
    val noCandidate = LoadReplayReturnPipeW2RetireRecordRowFillEnableControlReference(
      enable = true,
      flush = false,
      rowFillRequested = true,
      retireRecordValid = true,
      lifecycleRowClearReady = true,
      rowFillCandidateValid = false)

    assert(noLifecycle.blockedByNoLifecycleRow)
    assert(noLifecycle.invalidRowFillCandidateWithoutEvidence)
    assert(!noLifecycle.rowFillEnable)
    assert(noCandidate.requestEvidenceValid)
    assert(noCandidate.blockedByNoRowFillCandidate)
    assert(!noCandidate.rowFillEnable)
  }

  test("disabled and flush states suppress retained row-fill enable") {
    val disabled = LoadReplayReturnPipeW2RetireRecordRowFillEnableControlReference(
      enable = false,
      flush = false,
      rowFillRequested = true,
      retireRecordValid = true,
      lifecycleRowClearReady = true,
      rowFillCandidateValid = true)
    val flushed = LoadReplayReturnPipeW2RetireRecordRowFillEnableControlReference(
      enable = true,
      flush = true,
      rowFillRequested = true,
      retireRecordValid = true,
      lifecycleRowClearReady = true,
      rowFillCandidateValid = true)

    assert(!disabled.active)
    assert(disabled.blockedByDisabled)
    assert(!disabled.rowFillEnable)
    assert(!flushed.active)
    assert(flushed.blockedByFlush)
    assert(!flushed.rowFillEnable)
  }

  test("Chisel LoadReplayReturnPipeW2RetireRecordRowFillEnableControl elaborates diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPipeW2RetireRecordRowFillEnableControl)

    assert(sv.contains("module LoadReplayReturnPipeW2RetireRecordRowFillEnableControl"))
    assert(sv.contains("io_requestEvidenceValid"))
    assert(sv.contains("io_rowFillEnable"))
  }
}
