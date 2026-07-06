package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2AtomicRequestEnablePolicyReference {
  final case class Result(
      active: Boolean,
      sideEffectEvidence: Boolean,
      residentEvidence: Boolean,
      emptyRefillEvidence: Boolean,
      sideEffectPrerequisitesReady: Boolean,
      residentCommitPrerequisitesReady: Boolean,
      residentRequestEnableCandidate: Boolean,
      emptyRefillRequestEnableCandidate: Boolean,
      requestEnableCandidate: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByNoEvidence: Boolean,
      blockedByNoSideEffectSink: Boolean,
      blockedByNoClearCommit: Boolean,
      blockedByNoRowFillCandidate: Boolean,
      blockedByNoLifecycleRow: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      slotOccupied: Boolean,
      sideEffectCandidateValid: Boolean,
      sideEffectRequiredMask: Int,
      sideEffectSinksReady: Boolean,
      clearIntent: Boolean,
      clearCommitReady: Boolean,
      rowFillCandidateValid: Boolean,
      lifecycleRowClearReady: Boolean,
      writeCandidateValid: Boolean): Result = {
    val active = enable && !flush
    val sideEffectEvidence = sideEffectCandidateValid && (sideEffectRequiredMask & 0x7) != 0
    val residentEvidence =
      slotOccupied &&
        (sideEffectEvidence || clearIntent || rowFillCandidateValid || writeCandidateValid)
    val emptyRefillEvidence = !slotOccupied && writeCandidateValid
    val observedEvidence = residentEvidence || emptyRefillEvidence
    val sideEffectPrerequisitesReady = !sideEffectEvidence || sideEffectSinksReady
    val residentCommitPrerequisitesReady =
      clearCommitReady && rowFillCandidateValid && lifecycleRowClearReady
    val residentRequestEnableCandidate =
      active && residentEvidence && sideEffectPrerequisitesReady && residentCommitPrerequisitesReady
    val emptyRefillRequestEnableCandidate = active && emptyRefillEvidence
    val requestEnableCandidate =
      residentRequestEnableCandidate || emptyRefillRequestEnableCandidate

    Result(
      active = active,
      sideEffectEvidence = sideEffectEvidence,
      residentEvidence = residentEvidence,
      emptyRefillEvidence = emptyRefillEvidence,
      sideEffectPrerequisitesReady = sideEffectPrerequisitesReady,
      residentCommitPrerequisitesReady = residentCommitPrerequisitesReady,
      residentRequestEnableCandidate = residentRequestEnableCandidate,
      emptyRefillRequestEnableCandidate = emptyRefillRequestEnableCandidate,
      requestEnableCandidate = requestEnableCandidate,
      blockedByDisabled = !enable && observedEvidence,
      blockedByFlush = enable && flush && observedEvidence,
      blockedByNoEvidence = active && !observedEvidence,
      blockedByNoSideEffectSink =
        active && residentEvidence && sideEffectEvidence && !sideEffectSinksReady,
      blockedByNoClearCommit =
        active && residentEvidence && sideEffectPrerequisitesReady && !clearCommitReady,
      blockedByNoRowFillCandidate =
        active && residentEvidence && sideEffectPrerequisitesReady && clearCommitReady &&
          !rowFillCandidateValid,
      blockedByNoLifecycleRow =
        active && residentEvidence && sideEffectPrerequisitesReady && clearCommitReady &&
          rowFillCandidateValid && !lifecycleRowClearReady)
  }
}

class LoadReplayReturnPipeW2AtomicRequestEnablePolicySpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2AtomicRequestEnablePolicyReference._

  test("arms a resident replay request only when pre-request W2 prerequisites are ready") {
    val result = LoadReplayReturnPipeW2AtomicRequestEnablePolicyReference(
      enable = true,
      flush = false,
      slotOccupied = true,
      sideEffectCandidateValid = true,
      sideEffectRequiredMask = 0x7,
      sideEffectSinksReady = true,
      clearIntent = true,
      clearCommitReady = true,
      rowFillCandidateValid = true,
      lifecycleRowClearReady = true,
      writeCandidateValid = true)

    assert(result.active)
    assert(result.sideEffectEvidence)
    assert(result.residentEvidence)
    assert(result.sideEffectPrerequisitesReady)
    assert(result.residentCommitPrerequisitesReady)
    assert(result.residentRequestEnableCandidate)
    assert(result.requestEnableCandidate)
  }

  test("allows an empty W2 refill request without requiring resident commit prerequisites") {
    val result = LoadReplayReturnPipeW2AtomicRequestEnablePolicyReference(
      enable = true,
      flush = false,
      slotOccupied = false,
      sideEffectCandidateValid = false,
      sideEffectRequiredMask = 0x0,
      sideEffectSinksReady = false,
      clearIntent = false,
      clearCommitReady = false,
      rowFillCandidateValid = false,
      lifecycleRowClearReady = false,
      writeCandidateValid = true)

    assert(result.emptyRefillEvidence)
    assert(result.emptyRefillRequestEnableCandidate)
    assert(result.requestEnableCandidate)
    assert(!result.residentRequestEnableCandidate)
  }

  test("reports resident blockers in pre-request model order") {
    val noSink = LoadReplayReturnPipeW2AtomicRequestEnablePolicyReference(
      enable = true,
      flush = false,
      slotOccupied = true,
      sideEffectCandidateValid = true,
      sideEffectRequiredMask = 0x1,
      sideEffectSinksReady = false,
      clearIntent = true,
      clearCommitReady = true,
      rowFillCandidateValid = true,
      lifecycleRowClearReady = true,
      writeCandidateValid = false)
    val noClear = LoadReplayReturnPipeW2AtomicRequestEnablePolicyReference(
      enable = true,
      flush = false,
      slotOccupied = true,
      sideEffectCandidateValid = true,
      sideEffectRequiredMask = 0x1,
      sideEffectSinksReady = true,
      clearIntent = true,
      clearCommitReady = false,
      rowFillCandidateValid = true,
      lifecycleRowClearReady = true,
      writeCandidateValid = false)
    val noRow = LoadReplayReturnPipeW2AtomicRequestEnablePolicyReference(
      enable = true,
      flush = false,
      slotOccupied = true,
      sideEffectCandidateValid = false,
      sideEffectRequiredMask = 0x0,
      sideEffectSinksReady = true,
      clearIntent = true,
      clearCommitReady = true,
      rowFillCandidateValid = false,
      lifecycleRowClearReady = true,
      writeCandidateValid = false)
    val noLifecycle = LoadReplayReturnPipeW2AtomicRequestEnablePolicyReference(
      enable = true,
      flush = false,
      slotOccupied = true,
      sideEffectCandidateValid = false,
      sideEffectRequiredMask = 0x0,
      sideEffectSinksReady = true,
      clearIntent = true,
      clearCommitReady = true,
      rowFillCandidateValid = true,
      lifecycleRowClearReady = false,
      writeCandidateValid = false)

    assert(noSink.blockedByNoSideEffectSink)
    assert(noClear.blockedByNoClearCommit)
    assert(noRow.blockedByNoRowFillCandidate)
    assert(noLifecycle.blockedByNoLifecycleRow)
    assert(!noLifecycle.requestEnableCandidate)
  }

  test("reports disabled flush and no-evidence conditions") {
    val disabled = LoadReplayReturnPipeW2AtomicRequestEnablePolicyReference(
      enable = false,
      flush = false,
      slotOccupied = true,
      sideEffectCandidateValid = true,
      sideEffectRequiredMask = 0x1,
      sideEffectSinksReady = true,
      clearIntent = false,
      clearCommitReady = true,
      rowFillCandidateValid = true,
      lifecycleRowClearReady = true,
      writeCandidateValid = false)
    val flushed = LoadReplayReturnPipeW2AtomicRequestEnablePolicyReference(
      enable = true,
      flush = true,
      slotOccupied = false,
      sideEffectCandidateValid = false,
      sideEffectRequiredMask = 0x0,
      sideEffectSinksReady = true,
      clearIntent = false,
      clearCommitReady = false,
      rowFillCandidateValid = false,
      lifecycleRowClearReady = false,
      writeCandidateValid = true)
    val noEvidence = LoadReplayReturnPipeW2AtomicRequestEnablePolicyReference(
      enable = true,
      flush = false,
      slotOccupied = false,
      sideEffectCandidateValid = false,
      sideEffectRequiredMask = 0x0,
      sideEffectSinksReady = true,
      clearIntent = false,
      clearCommitReady = true,
      rowFillCandidateValid = false,
      lifecycleRowClearReady = true,
      writeCandidateValid = false)

    assert(disabled.blockedByDisabled)
    assert(flushed.blockedByFlush)
    assert(noEvidence.blockedByNoEvidence)
    assert(!disabled.requestEnableCandidate)
    assert(!flushed.requestEnableCandidate)
    assert(!noEvidence.requestEnableCandidate)
  }

  test("Chisel LoadReplayReturnPipeW2AtomicRequestEnablePolicy elaborates policy diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPipeW2AtomicRequestEnablePolicy)

    assert(sv.contains("module LoadReplayReturnPipeW2AtomicRequestEnablePolicy"))
    assert(sv.contains("io_requestEnableCandidate"))
    assert(sv.contains("io_blockedByNoLifecycleRow"))
  }
}
