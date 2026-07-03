package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2ReplayRowLifecycleCommitPermitReference {
  final case class Result(
      active: Boolean,
      commitCandidate: Boolean,
      rowFillPermit: Boolean,
      lifecycleClearCommitEnable: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByNoSelection: Boolean,
      blockedByNoRowFillEnable: Boolean,
      blockedByNoRowFillCandidate: Boolean,
      blockedByNoLifecycleRow: Boolean,
      invalidRowFillWithoutSelection: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      lifecycleClearSelected: Boolean,
      rowFillEnable: Boolean,
      rowFillCandidateValid: Boolean,
      lifecycleRowClearReady: Boolean): Result = {
    val active = enable && !flush
    val observedIntent =
      lifecycleClearSelected || rowFillEnable || rowFillCandidateValid || lifecycleRowClearReady
    val commitCandidate = active && lifecycleClearSelected
    val rowFillPermit = active && rowFillEnable
    val lifecycleClearCommitEnable = commitCandidate && rowFillEnable

    Result(
      active = active,
      commitCandidate = commitCandidate,
      rowFillPermit = rowFillPermit,
      lifecycleClearCommitEnable = lifecycleClearCommitEnable,
      blockedByDisabled = !enable && observedIntent,
      blockedByFlush = enable && flush && observedIntent,
      blockedByNoSelection = active && rowFillEnable && !lifecycleClearSelected,
      blockedByNoRowFillEnable = commitCandidate && !rowFillEnable,
      blockedByNoRowFillCandidate = commitCandidate && !rowFillCandidateValid,
      blockedByNoLifecycleRow =
        active && !lifecycleRowClearReady && (lifecycleClearSelected || rowFillEnable),
      invalidRowFillWithoutSelection = rowFillEnable && !lifecycleClearSelected)
  }
}

class LoadReplayReturnPipeW2ReplayRowLifecycleCommitPermitSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2ReplayRowLifecycleCommitPermitReference._

  test("selected lifecycle clear commits only with row-fill permit") {
    val result = LoadReplayReturnPipeW2ReplayRowLifecycleCommitPermitReference(
      enable = true,
      flush = false,
      lifecycleClearSelected = true,
      rowFillEnable = true,
      rowFillCandidateValid = true,
      lifecycleRowClearReady = true)

    assert(result.active)
    assert(result.commitCandidate)
    assert(result.rowFillPermit)
    assert(result.lifecycleClearCommitEnable)
    assert(!result.invalidRowFillWithoutSelection)
  }

  test("selected lifecycle clear waits for row-fill enable") {
    val result = LoadReplayReturnPipeW2ReplayRowLifecycleCommitPermitReference(
      enable = true,
      flush = false,
      lifecycleClearSelected = true,
      rowFillEnable = false,
      rowFillCandidateValid = true,
      lifecycleRowClearReady = true)

    assert(result.commitCandidate)
    assert(!result.rowFillPermit)
    assert(!result.lifecycleClearCommitEnable)
    assert(result.blockedByNoRowFillEnable)
  }

  test("row-fill without lifecycle selection is reported as invalid permit shape") {
    val result = LoadReplayReturnPipeW2ReplayRowLifecycleCommitPermitReference(
      enable = true,
      flush = false,
      lifecycleClearSelected = false,
      rowFillEnable = true,
      rowFillCandidateValid = true,
      lifecycleRowClearReady = true)

    assert(!result.commitCandidate)
    assert(result.rowFillPermit)
    assert(!result.lifecycleClearCommitEnable)
    assert(result.blockedByNoSelection)
    assert(result.invalidRowFillWithoutSelection)
  }

  test("missing row-fill candidate and lifecycle row stay observable") {
    val result = LoadReplayReturnPipeW2ReplayRowLifecycleCommitPermitReference(
      enable = true,
      flush = false,
      lifecycleClearSelected = true,
      rowFillEnable = false,
      rowFillCandidateValid = false,
      lifecycleRowClearReady = false)

    assert(result.commitCandidate)
    assert(!result.lifecycleClearCommitEnable)
    assert(result.blockedByNoRowFillEnable)
    assert(result.blockedByNoRowFillCandidate)
    assert(result.blockedByNoLifecycleRow)
  }

  test("disabled and flush states suppress commit permit") {
    val disabled = LoadReplayReturnPipeW2ReplayRowLifecycleCommitPermitReference(
      enable = false,
      flush = false,
      lifecycleClearSelected = true,
      rowFillEnable = true,
      rowFillCandidateValid = true,
      lifecycleRowClearReady = true)
    val flushed = LoadReplayReturnPipeW2ReplayRowLifecycleCommitPermitReference(
      enable = true,
      flush = true,
      lifecycleClearSelected = true,
      rowFillEnable = true,
      rowFillCandidateValid = true,
      lifecycleRowClearReady = true)

    assert(!disabled.active)
    assert(!disabled.lifecycleClearCommitEnable)
    assert(disabled.blockedByDisabled)
    assert(!flushed.active)
    assert(!flushed.lifecycleClearCommitEnable)
    assert(flushed.blockedByFlush)
  }

  test("Chisel LoadReplayReturnPipeW2ReplayRowLifecycleCommitPermit elaborates diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPipeW2ReplayRowLifecycleCommitPermit)

    assert(sv.contains("module LoadReplayReturnPipeW2ReplayRowLifecycleCommitPermit"))
    assert(sv.contains("io_lifecycleClearCommitEnable"))
    assert(sv.contains("io_invalidRowFillWithoutSelection"))
  }
}
