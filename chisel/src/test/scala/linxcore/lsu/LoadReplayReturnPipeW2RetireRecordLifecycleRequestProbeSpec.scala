package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2RetireRecordLifecycleRequestProbeReference {
  final case class Result(
      active: Boolean,
      requestCandidate: Boolean,
      livePromotionCandidate: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByNoLifecycleRow: Boolean,
      blockedByNoAtomicRequest: Boolean,
      blockedByNoRowFillCandidate: Boolean,
      blockedByNoRowFillEnable: Boolean,
      invalidRowFillEnableWithoutRequest: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      retireRecordValid: Boolean,
      lifecycleRowClearReady: Boolean,
      atomicRequestActive: Boolean,
      rowFillCandidateValid: Boolean,
      rowFillEnable: Boolean): Result = {
    val active = enable && !flush
    val observedIntent =
      retireRecordValid ||
        lifecycleRowClearReady ||
        atomicRequestActive ||
        rowFillCandidateValid ||
        rowFillEnable
    val requestCandidate = active && retireRecordValid && lifecycleRowClearReady
    val livePromotionCandidate =
      requestCandidate &&
        atomicRequestActive &&
        rowFillCandidateValid &&
        rowFillEnable

    Result(
      active = active,
      requestCandidate = requestCandidate,
      livePromotionCandidate = livePromotionCandidate,
      blockedByDisabled = !enable && observedIntent,
      blockedByFlush = enable && flush && observedIntent,
      blockedByNoLifecycleRow = active && retireRecordValid && !lifecycleRowClearReady,
      blockedByNoAtomicRequest = requestCandidate && !atomicRequestActive,
      blockedByNoRowFillCandidate = requestCandidate && atomicRequestActive && !rowFillCandidateValid,
      blockedByNoRowFillEnable =
        requestCandidate && atomicRequestActive && rowFillCandidateValid && !rowFillEnable,
      invalidRowFillEnableWithoutRequest = rowFillEnable && !requestCandidate)
  }
}

class LoadReplayReturnPipeW2RetireRecordLifecycleRequestProbeSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2RetireRecordLifecycleRequestProbeReference._

  test("live promotion candidate requires record, lifecycle row, atomic request, and row fill") {
    val result = LoadReplayReturnPipeW2RetireRecordLifecycleRequestProbeReference(
      enable = true,
      flush = false,
      retireRecordValid = true,
      lifecycleRowClearReady = true,
      atomicRequestActive = true,
      rowFillCandidateValid = true,
      rowFillEnable = true)

    assert(result.active)
    assert(result.requestCandidate)
    assert(result.livePromotionCandidate)
    assert(!result.blockedByNoAtomicRequest)
    assert(!result.blockedByNoRowFillCandidate)
    assert(!result.blockedByNoRowFillEnable)
  }

  test("retained record with no lifecycle row blocks before live promotion") {
    val result = LoadReplayReturnPipeW2RetireRecordLifecycleRequestProbeReference(
      enable = true,
      flush = false,
      retireRecordValid = true,
      lifecycleRowClearReady = false,
      atomicRequestActive = true,
      rowFillCandidateValid = true,
      rowFillEnable = true)

    assert(!result.requestCandidate)
    assert(!result.livePromotionCandidate)
    assert(result.blockedByNoLifecycleRow)
    assert(result.invalidRowFillEnableWithoutRequest)
  }

  test("unique retained-record lifecycle row exposes missing live prerequisites") {
    val noAtomic = LoadReplayReturnPipeW2RetireRecordLifecycleRequestProbeReference(
      enable = true,
      flush = false,
      retireRecordValid = true,
      lifecycleRowClearReady = true,
      atomicRequestActive = false,
      rowFillCandidateValid = true,
      rowFillEnable = true)
    val noRowFillCandidate = LoadReplayReturnPipeW2RetireRecordLifecycleRequestProbeReference(
      enable = true,
      flush = false,
      retireRecordValid = true,
      lifecycleRowClearReady = true,
      atomicRequestActive = true,
      rowFillCandidateValid = false,
      rowFillEnable = true)
    val noRowFillEnable = LoadReplayReturnPipeW2RetireRecordLifecycleRequestProbeReference(
      enable = true,
      flush = false,
      retireRecordValid = true,
      lifecycleRowClearReady = true,
      atomicRequestActive = true,
      rowFillCandidateValid = true,
      rowFillEnable = false)

    assert(noAtomic.requestCandidate)
    assert(noAtomic.blockedByNoAtomicRequest)
    assert(noRowFillCandidate.requestCandidate)
    assert(noRowFillCandidate.blockedByNoRowFillCandidate)
    assert(noRowFillEnable.requestCandidate)
    assert(noRowFillEnable.blockedByNoRowFillEnable)
  }

  test("disabled and flush states suppress request candidate") {
    val disabled = LoadReplayReturnPipeW2RetireRecordLifecycleRequestProbeReference(
      enable = false,
      flush = false,
      retireRecordValid = true,
      lifecycleRowClearReady = true,
      atomicRequestActive = true,
      rowFillCandidateValid = true,
      rowFillEnable = true)
    val flushed = LoadReplayReturnPipeW2RetireRecordLifecycleRequestProbeReference(
      enable = true,
      flush = true,
      retireRecordValid = true,
      lifecycleRowClearReady = true,
      atomicRequestActive = true,
      rowFillCandidateValid = true,
      rowFillEnable = true)

    assert(!disabled.active)
    assert(!disabled.requestCandidate)
    assert(disabled.blockedByDisabled)
    assert(!flushed.active)
    assert(!flushed.requestCandidate)
    assert(flushed.blockedByFlush)
  }

  test("Chisel LoadReplayReturnPipeW2RetireRecordLifecycleRequestProbe elaborates diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPipeW2RetireRecordLifecycleRequestProbe)

    assert(sv.contains("module LoadReplayReturnPipeW2RetireRecordLifecycleRequestProbe"))
    assert(sv.contains("io_livePromotionCandidate"))
    assert(sv.contains("io_blockedByNoRowFillEnable"))
  }
}
