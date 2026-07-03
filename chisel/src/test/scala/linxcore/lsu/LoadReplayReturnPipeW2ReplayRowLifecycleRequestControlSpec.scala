package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2ReplayRowLifecycleRequestControlReference {
  final case class Result(
      active: Boolean,
      requestCandidate: Boolean,
      lifecycleClearRequestEnable: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByRequestInactive: Boolean,
      blockedByNoRowFillCandidate: Boolean,
      blockedByNoLifecycleRow: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      atomicRequestActive: Boolean,
      rowFillCandidateValid: Boolean,
      lifecycleRowClearReady: Boolean): Result = {
    val active = enable && !flush
    val observedIntent = atomicRequestActive || rowFillCandidateValid || lifecycleRowClearReady
    val requestCandidate = active && atomicRequestActive && rowFillCandidateValid

    Result(
      active = active,
      requestCandidate = requestCandidate,
      lifecycleClearRequestEnable = requestCandidate,
      blockedByDisabled = !enable && observedIntent,
      blockedByFlush = enable && flush && observedIntent,
      blockedByRequestInactive =
        active && !atomicRequestActive && (rowFillCandidateValid || lifecycleRowClearReady),
      blockedByNoRowFillCandidate =
        active && atomicRequestActive && !rowFillCandidateValid,
      blockedByNoLifecycleRow = requestCandidate && !lifecycleRowClearReady)
  }
}

class LoadReplayReturnPipeW2ReplayRowLifecycleRequestControlSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2ReplayRowLifecycleRequestControlReference._

  test("request arms only from atomic request plus row-fill candidate") {
    val result = LoadReplayReturnPipeW2ReplayRowLifecycleRequestControlReference(
      enable = true,
      flush = false,
      atomicRequestActive = true,
      rowFillCandidateValid = true,
      lifecycleRowClearReady = true)

    assert(result.active)
    assert(result.requestCandidate)
    assert(result.lifecycleClearRequestEnable)
    assert(!result.blockedByNoLifecycleRow)
  }

  test("inactive atomic request keeps lifecycle clear request dormant") {
    val result = LoadReplayReturnPipeW2ReplayRowLifecycleRequestControlReference(
      enable = true,
      flush = false,
      atomicRequestActive = false,
      rowFillCandidateValid = true,
      lifecycleRowClearReady = true)

    assert(!result.requestCandidate)
    assert(!result.lifecycleClearRequestEnable)
    assert(result.blockedByRequestInactive)
  }

  test("missing row-fill candidate blocks request before lifecycle clear") {
    val result = LoadReplayReturnPipeW2ReplayRowLifecycleRequestControlReference(
      enable = true,
      flush = false,
      atomicRequestActive = true,
      rowFillCandidateValid = false,
      lifecycleRowClearReady = true)

    assert(!result.requestCandidate)
    assert(!result.lifecycleClearRequestEnable)
    assert(result.blockedByNoRowFillCandidate)
  }

  test("missing lifecycle row remains observable after request is armed") {
    val result = LoadReplayReturnPipeW2ReplayRowLifecycleRequestControlReference(
      enable = true,
      flush = false,
      atomicRequestActive = true,
      rowFillCandidateValid = true,
      lifecycleRowClearReady = false)

    assert(result.requestCandidate)
    assert(result.lifecycleClearRequestEnable)
    assert(result.blockedByNoLifecycleRow)
  }

  test("disabled and flush states suppress request") {
    val disabled = LoadReplayReturnPipeW2ReplayRowLifecycleRequestControlReference(
      enable = false,
      flush = false,
      atomicRequestActive = true,
      rowFillCandidateValid = true,
      lifecycleRowClearReady = true)
    val flushed = LoadReplayReturnPipeW2ReplayRowLifecycleRequestControlReference(
      enable = true,
      flush = true,
      atomicRequestActive = true,
      rowFillCandidateValid = true,
      lifecycleRowClearReady = true)

    assert(!disabled.active)
    assert(!disabled.lifecycleClearRequestEnable)
    assert(disabled.blockedByDisabled)
    assert(!flushed.active)
    assert(!flushed.lifecycleClearRequestEnable)
    assert(flushed.blockedByFlush)
  }

  test("Chisel LoadReplayReturnPipeW2ReplayRowLifecycleRequestControl elaborates diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPipeW2ReplayRowLifecycleRequestControl)

    assert(sv.contains("module LoadReplayReturnPipeW2ReplayRowLifecycleRequestControl"))
    assert(sv.contains("io_lifecycleClearRequestEnable"))
    assert(sv.contains("io_blockedByNoLifecycleRow"))
  }
}
