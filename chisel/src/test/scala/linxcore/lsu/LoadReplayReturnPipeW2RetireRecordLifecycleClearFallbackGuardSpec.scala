package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2RetireRecordLifecycleClearFallbackGuardReference {
  final case class State(valid: Boolean, clearIndex: Int, physicalClearSeen: Boolean)
  final case class Result(
      state: State,
      capturePhysicalClear: Boolean,
      captureBlockedByNoPhysicalClear: Boolean,
      recordMatchesCapture: Boolean,
      duplicatePhysicalClear: Boolean,
      fallbackEligible: Boolean,
      fallbackClearValid: Boolean,
      fallbackClearIndex: Int,
      blockedByNoRecordLifecycleClear: Boolean,
      blockedByNoCaptureEvidence: Boolean,
      blockedByPriorPhysicalClear: Boolean,
      blockedByFallbackDisabled: Boolean)

  def step(
      state: State,
      enable: Boolean,
      flush: Boolean,
      fallbackEnable: Boolean,
      captureAccepted: Boolean,
      captureRowClearReady: Boolean,
      captureRowClearIndex: Int,
      physicalClearAccepted: Boolean,
      physicalClearIndex: Int,
      recordValid: Boolean,
      recordLifecycleClearReady: Boolean,
      recordRowClearIndex: Int,
      recordFire: Boolean): Result = {
    val active = enable && !flush
    val captureIntent = active && captureAccepted && captureRowClearReady
    val capturePhysicalClear =
      captureIntent && physicalClearAccepted && physicalClearIndex == captureRowClearIndex
    val recordCandidate = active && recordValid
    val recordClearReady = recordCandidate && recordLifecycleClearReady
    val recordMatchesCapture =
      recordClearReady && state.valid && recordRowClearIndex == state.clearIndex
    val duplicatePhysicalClear = recordMatchesCapture && state.physicalClearSeen
    val fallbackEligible = recordMatchesCapture && !state.physicalClearSeen
    val fallbackClearValid = fallbackEnable && fallbackEligible
    val nextState =
      if (flush || !enable) {
        State(valid = false, clearIndex = 0, physicalClearSeen = false)
      } else if (captureIntent) {
        State(valid = true, clearIndex = captureRowClearIndex, physicalClearSeen = capturePhysicalClear)
      } else if (recordFire && state.valid) {
        State(valid = false, clearIndex = state.clearIndex, physicalClearSeen = false)
      } else {
        state
      }

    Result(
      state = nextState,
      capturePhysicalClear = capturePhysicalClear,
      captureBlockedByNoPhysicalClear = captureIntent && !capturePhysicalClear,
      recordMatchesCapture = recordMatchesCapture,
      duplicatePhysicalClear = duplicatePhysicalClear,
      fallbackEligible = fallbackEligible,
      fallbackClearValid = fallbackClearValid,
      fallbackClearIndex = if (fallbackClearValid) recordRowClearIndex else 0,
      blockedByNoRecordLifecycleClear = recordCandidate && !recordLifecycleClearReady,
      blockedByNoCaptureEvidence = recordClearReady && !recordMatchesCapture,
      blockedByPriorPhysicalClear = recordMatchesCapture && state.physicalClearSeen,
      blockedByFallbackDisabled = fallbackEligible && !fallbackEnable)
  }
}

class LoadReplayReturnPipeW2RetireRecordLifecycleClearFallbackGuardSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2RetireRecordLifecycleClearFallbackGuardReference._

  test("blocks retained lifecycle clear when physical clear already accepted the same index") {
    val captured = step(
      state = State(valid = false, clearIndex = 0, physicalClearSeen = false),
      enable = true,
      flush = false,
      fallbackEnable = true,
      captureAccepted = true,
      captureRowClearReady = true,
      captureRowClearIndex = 5,
      physicalClearAccepted = true,
      physicalClearIndex = 5,
      recordValid = false,
      recordLifecycleClearReady = false,
      recordRowClearIndex = 0,
      recordFire = false)
    val retained = step(
      state = captured.state,
      enable = true,
      flush = false,
      fallbackEnable = true,
      captureAccepted = false,
      captureRowClearReady = false,
      captureRowClearIndex = 0,
      physicalClearAccepted = false,
      physicalClearIndex = 0,
      recordValid = true,
      recordLifecycleClearReady = true,
      recordRowClearIndex = 5,
      recordFire = false)

    assert(captured.capturePhysicalClear)
    assert(retained.recordMatchesCapture)
    assert(retained.duplicatePhysicalClear)
    assert(retained.blockedByPriorPhysicalClear)
    assert(!retained.fallbackClearValid)
  }

  test("emits retained lifecycle clear when no prior physical clear was captured") {
    val captured = step(
      state = State(valid = false, clearIndex = 0, physicalClearSeen = false),
      enable = true,
      flush = false,
      fallbackEnable = true,
      captureAccepted = true,
      captureRowClearReady = true,
      captureRowClearIndex = 3,
      physicalClearAccepted = false,
      physicalClearIndex = 0,
      recordValid = false,
      recordLifecycleClearReady = false,
      recordRowClearIndex = 0,
      recordFire = false)
    val retained = step(
      state = captured.state,
      enable = true,
      flush = false,
      fallbackEnable = true,
      captureAccepted = false,
      captureRowClearReady = false,
      captureRowClearIndex = 0,
      physicalClearAccepted = false,
      physicalClearIndex = 0,
      recordValid = true,
      recordLifecycleClearReady = true,
      recordRowClearIndex = 3,
      recordFire = false)

    assert(captured.captureBlockedByNoPhysicalClear)
    assert(retained.fallbackEligible)
    assert(retained.fallbackClearValid)
    assert(retained.fallbackClearIndex == 3)
  }

  test("reports missing retained lifecycle clear and capture evidence blockers") {
    val missingLifecycle = step(
      state = State(valid = true, clearIndex = 1, physicalClearSeen = false),
      enable = true,
      flush = false,
      fallbackEnable = true,
      captureAccepted = false,
      captureRowClearReady = false,
      captureRowClearIndex = 0,
      physicalClearAccepted = false,
      physicalClearIndex = 0,
      recordValid = true,
      recordLifecycleClearReady = false,
      recordRowClearIndex = 1,
      recordFire = false)
    val missingCapture = step(
      state = State(valid = false, clearIndex = 0, physicalClearSeen = false),
      enable = true,
      flush = false,
      fallbackEnable = true,
      captureAccepted = false,
      captureRowClearReady = false,
      captureRowClearIndex = 0,
      physicalClearAccepted = false,
      physicalClearIndex = 0,
      recordValid = true,
      recordLifecycleClearReady = true,
      recordRowClearIndex = 1,
      recordFire = false)

    assert(missingLifecycle.blockedByNoRecordLifecycleClear)
    assert(missingCapture.blockedByNoCaptureEvidence)
    assert(!missingCapture.fallbackClearValid)
  }

  test("fallback enable gates otherwise eligible retained lifecycle clear") {
    val result = step(
      state = State(valid = true, clearIndex = 2, physicalClearSeen = false),
      enable = true,
      flush = false,
      fallbackEnable = false,
      captureAccepted = false,
      captureRowClearReady = false,
      captureRowClearIndex = 0,
      physicalClearAccepted = false,
      physicalClearIndex = 0,
      recordValid = true,
      recordLifecycleClearReady = true,
      recordRowClearIndex = 2,
      recordFire = false)

    assert(result.fallbackEligible)
    assert(result.blockedByFallbackDisabled)
    assert(!result.fallbackClearValid)
  }

  test("Chisel LoadReplayReturnPipeW2RetireRecordLifecycleClearFallbackGuard elaborates") {
    val sv = ChiselStage.emitSystemVerilog(
      new LoadReplayReturnPipeW2RetireRecordLifecycleClearFallbackGuard(liqEntries = 8))

    assert(sv.contains("module LoadReplayReturnPipeW2RetireRecordLifecycleClearFallbackGuard"))
    assert(sv.contains("io_fallbackClearValid"))
    assert(sv.contains("io_duplicatePhysicalClear"))
  }
}
