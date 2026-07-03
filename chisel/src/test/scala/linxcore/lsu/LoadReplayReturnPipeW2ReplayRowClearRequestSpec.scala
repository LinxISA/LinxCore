package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2ReplayRowClearRequestReference {
  final case class Result(
      active: Boolean,
      existingClearCandidate: Boolean,
      lifecycleClearCandidate: Boolean,
      lifecycleClearEnable: Boolean,
      lifecycleClearSelected: Boolean,
      clearResolvedValid: Boolean,
      clearResolvedIndex: Int,
      existingClearAccepted: Boolean,
      lifecycleClearAccepted: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByExistingClear: Boolean,
      blockedByLifecycleRequestDisabled: Boolean,
      blockedByNoLifecycleRow: Boolean,
      blockedByLifecycleCommitDisabled: Boolean,
      invalidLifecycleCommitWithoutSelection: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      existingClearValid: Boolean,
      existingClearIndex: Int,
      lifecycleClearRequestEnable: Boolean,
      lifecycleClearCommitEnable: Boolean,
      lifecycleRowClearReady: Boolean,
      lifecycleRowClearIndex: Int,
      clearResolvedAccepted: Boolean): Result = {
    val active = enable && !flush
    val anyClearIntent = existingClearValid || lifecycleClearRequestEnable || lifecycleRowClearReady
    val existingClearCandidate = active && existingClearValid
    val lifecycleRequested = active && lifecycleClearRequestEnable
    val lifecycleClearCandidate = lifecycleRequested && lifecycleRowClearReady
    val lifecycleClearSelected = lifecycleClearCandidate && !existingClearCandidate
    val committedLifecycleClear = lifecycleClearSelected && lifecycleClearCommitEnable
    val clearResolvedValid = existingClearCandidate || committedLifecycleClear
    val clearResolvedIndex =
      if (existingClearCandidate) existingClearIndex
      else if (committedLifecycleClear) lifecycleRowClearIndex
      else 0

    Result(
      active = active,
      existingClearCandidate = existingClearCandidate,
      lifecycleClearCandidate = lifecycleClearCandidate,
      lifecycleClearEnable = lifecycleClearSelected,
      lifecycleClearSelected = lifecycleClearSelected,
      clearResolvedValid = clearResolvedValid,
      clearResolvedIndex = clearResolvedIndex,
      existingClearAccepted = clearResolvedAccepted && existingClearCandidate,
      lifecycleClearAccepted = clearResolvedAccepted && committedLifecycleClear,
      blockedByDisabled = !enable && anyClearIntent,
      blockedByFlush = enable && flush && anyClearIntent,
      blockedByExistingClear = lifecycleRequested && existingClearCandidate,
      blockedByLifecycleRequestDisabled =
        active && lifecycleRowClearReady && !lifecycleClearRequestEnable,
      blockedByNoLifecycleRow = lifecycleRequested && !lifecycleRowClearReady,
      blockedByLifecycleCommitDisabled = lifecycleClearSelected && !lifecycleClearCommitEnable,
      invalidLifecycleCommitWithoutSelection =
        lifecycleClearCommitEnable && !lifecycleClearSelected)
  }
}

class LoadReplayReturnPipeW2ReplayRowClearRequestSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2ReplayRowClearRequestReference._

  test("passes through the existing ResolveQ delayed clear path") {
    val result = LoadReplayReturnPipeW2ReplayRowClearRequestReference(
      enable = true,
      flush = false,
      existingClearValid = true,
      existingClearIndex = 3,
      lifecycleClearRequestEnable = false,
      lifecycleClearCommitEnable = false,
      lifecycleRowClearReady = false,
      lifecycleRowClearIndex = 1,
      clearResolvedAccepted = true)

    assert(result.existingClearCandidate)
    assert(result.clearResolvedValid)
    assert(result.clearResolvedIndex == 3)
    assert(result.existingClearAccepted)
    assert(!result.lifecycleClearEnable)
    assert(!result.lifecycleClearAccepted)
  }

  test("exposes lifecycle clear readiness without committing the clear") {
    val result = LoadReplayReturnPipeW2ReplayRowClearRequestReference(
      enable = true,
      flush = false,
      existingClearValid = false,
      existingClearIndex = 0,
      lifecycleClearRequestEnable = true,
      lifecycleClearCommitEnable = false,
      lifecycleRowClearReady = true,
      lifecycleRowClearIndex = 2,
      clearResolvedAccepted = true)

    assert(result.lifecycleClearCandidate)
    assert(result.lifecycleClearEnable)
    assert(result.lifecycleClearSelected)
    assert(!result.clearResolvedValid)
    assert(result.clearResolvedIndex == 0)
    assert(result.blockedByLifecycleCommitDisabled)
    assert(!result.lifecycleClearAccepted)
  }

  test("commits lifecycle clear only when the row-fill owner commits") {
    val result = LoadReplayReturnPipeW2ReplayRowClearRequestReference(
      enable = true,
      flush = false,
      existingClearValid = false,
      existingClearIndex = 0,
      lifecycleClearRequestEnable = true,
      lifecycleClearCommitEnable = true,
      lifecycleRowClearReady = true,
      lifecycleRowClearIndex = 2,
      clearResolvedAccepted = true)

    assert(result.lifecycleClearEnable)
    assert(result.clearResolvedValid)
    assert(result.clearResolvedIndex == 2)
    assert(result.lifecycleClearAccepted)
    assert(!result.blockedByLifecycleCommitDisabled)
  }

  test("gives the existing clear path priority over lifecycle clear") {
    val result = LoadReplayReturnPipeW2ReplayRowClearRequestReference(
      enable = true,
      flush = false,
      existingClearValid = true,
      existingClearIndex = 3,
      lifecycleClearRequestEnable = true,
      lifecycleClearCommitEnable = true,
      lifecycleRowClearReady = true,
      lifecycleRowClearIndex = 2,
      clearResolvedAccepted = true)

    assert(result.existingClearCandidate)
    assert(!result.lifecycleClearEnable)
    assert(!result.lifecycleClearSelected)
    assert(result.clearResolvedValid)
    assert(result.clearResolvedIndex == 3)
    assert(result.existingClearAccepted)
    assert(!result.lifecycleClearAccepted)
    assert(result.blockedByExistingClear)
    assert(result.invalidLifecycleCommitWithoutSelection)
  }

  test("reports disabled flush and missing lifecycle row blockers") {
    val disabled = LoadReplayReturnPipeW2ReplayRowClearRequestReference(
      enable = false,
      flush = false,
      existingClearValid = false,
      existingClearIndex = 0,
      lifecycleClearRequestEnable = true,
      lifecycleClearCommitEnable = false,
      lifecycleRowClearReady = true,
      lifecycleRowClearIndex = 2,
      clearResolvedAccepted = false)
    val flushed = LoadReplayReturnPipeW2ReplayRowClearRequestReference(
      enable = true,
      flush = true,
      existingClearValid = true,
      existingClearIndex = 1,
      lifecycleClearRequestEnable = false,
      lifecycleClearCommitEnable = false,
      lifecycleRowClearReady = false,
      lifecycleRowClearIndex = 0,
      clearResolvedAccepted = false)
    val noRow = LoadReplayReturnPipeW2ReplayRowClearRequestReference(
      enable = true,
      flush = false,
      existingClearValid = false,
      existingClearIndex = 0,
      lifecycleClearRequestEnable = true,
      lifecycleClearCommitEnable = false,
      lifecycleRowClearReady = false,
      lifecycleRowClearIndex = 0,
      clearResolvedAccepted = false)

    assert(disabled.blockedByDisabled)
    assert(flushed.blockedByFlush)
    assert(noRow.blockedByNoLifecycleRow)
    assert(!disabled.clearResolvedValid)
    assert(!flushed.clearResolvedValid)
    assert(!noRow.clearResolvedValid)
  }

  test("Chisel LoadReplayReturnPipeW2ReplayRowClearRequest elaborates diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPipeW2ReplayRowClearRequest(liqEntries = 4))

    assert(sv.contains("module LoadReplayReturnPipeW2ReplayRowClearRequest"))
    assert(sv.contains("io_lifecycleClearEnable"))
    assert(sv.contains("io_existingClearAccepted"))
    assert(sv.contains("io_blockedByExistingClear"))
  }
}
