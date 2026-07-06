package linxcore.lsu

import circt.stage.ChiselStage
import linxcore.commit.CommitTraceParams
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2RetireRecordRobCompleteFallbackGuardReference {
  final case class State(valid: Boolean, ridValue: Int, physicalCompleteSeen: Boolean)
  final case class Id(valid: Boolean, value: Int)
  final case class Result(
      state: State,
      active: Boolean,
      captureIntent: Boolean,
      capturePhysicalComplete: Boolean,
      captureBlockedByNoPhysicalComplete: Boolean,
      recordCandidate: Boolean,
      recordMatchesCapture: Boolean,
      duplicatePhysicalComplete: Boolean,
      fallbackEligible: Boolean,
      fallbackCompleteValid: Boolean,
      fallbackCompleteRobValue: Int,
      blockedByNoRetainedCompleteRow: Boolean,
      blockedByNoCaptureEvidence: Boolean,
      blockedByPriorPhysicalComplete: Boolean,
      blockedByFallbackDisabled: Boolean)

  def step(
      state: State,
      enable: Boolean,
      flush: Boolean,
      fallbackEnable: Boolean,
      captureAccepted: Boolean,
      captureRid: Id,
      physicalCompleteValid: Boolean,
      physicalCompleteRobValue: Int,
      recordValid: Boolean,
      recordRid: Id,
      recordFire: Boolean,
      retainedCompleteRowValid: Boolean): Result = {
    val active = enable && !flush
    val captureIntent = active && captureAccepted && captureRid.valid
    val capturePhysicalComplete =
      captureIntent && physicalCompleteValid && physicalCompleteRobValue == captureRid.value
    val recordCandidate = active && recordValid
    val recordMatchesCapture =
      recordCandidate && recordRid.valid && state.valid && recordRid.value == state.ridValue
    val duplicatePhysicalComplete = recordMatchesCapture && state.physicalCompleteSeen
    val retainedRowReady = recordCandidate && retainedCompleteRowValid
    val fallbackEligible = retainedRowReady && recordMatchesCapture && !state.physicalCompleteSeen
    val fallbackCompleteValid = fallbackEnable && fallbackEligible
    val nextState =
      if (flush || !enable) {
        State(valid = false, ridValue = 0, physicalCompleteSeen = false)
      } else if (captureIntent) {
        State(valid = true, ridValue = captureRid.value, physicalCompleteSeen = capturePhysicalComplete)
      } else if (recordFire && state.valid) {
        State(valid = false, ridValue = state.ridValue, physicalCompleteSeen = false)
      } else {
        state
      }

    Result(
      state = nextState,
      active = active,
      captureIntent = captureIntent,
      capturePhysicalComplete = capturePhysicalComplete,
      captureBlockedByNoPhysicalComplete = captureIntent && !capturePhysicalComplete,
      recordCandidate = recordCandidate,
      recordMatchesCapture = recordMatchesCapture,
      duplicatePhysicalComplete = duplicatePhysicalComplete,
      fallbackEligible = fallbackEligible,
      fallbackCompleteValid = fallbackCompleteValid,
      fallbackCompleteRobValue = if (fallbackCompleteValid) recordRid.value else 0,
      blockedByNoRetainedCompleteRow = recordCandidate && !retainedCompleteRowValid,
      blockedByNoCaptureEvidence = retainedRowReady && !recordMatchesCapture,
      blockedByPriorPhysicalComplete = retainedRowReady && duplicatePhysicalComplete,
      blockedByFallbackDisabled = fallbackEligible && !fallbackEnable)
  }
}

class LoadReplayReturnPipeW2RetireRecordRobCompleteFallbackGuardSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2RetireRecordRobCompleteFallbackGuardReference._

  test("blocks retained fallback when physical ROB completion was captured for the same RID") {
    val captured = step(
      state = State(valid = false, ridValue = 0, physicalCompleteSeen = false),
      enable = true,
      flush = false,
      fallbackEnable = true,
      captureAccepted = true,
      captureRid = Id(valid = true, value = 5),
      physicalCompleteValid = true,
      physicalCompleteRobValue = 5,
      recordValid = false,
      recordRid = Id(valid = false, value = 0),
      recordFire = false,
      retainedCompleteRowValid = false)
    val retained = step(
      state = captured.state,
      enable = true,
      flush = false,
      fallbackEnable = true,
      captureAccepted = false,
      captureRid = Id(valid = false, value = 0),
      physicalCompleteValid = false,
      physicalCompleteRobValue = 0,
      recordValid = true,
      recordRid = Id(valid = true, value = 5),
      recordFire = false,
      retainedCompleteRowValid = true)

    assert(captured.capturePhysicalComplete)
    assert(retained.recordMatchesCapture)
    assert(retained.duplicatePhysicalComplete)
    assert(retained.blockedByPriorPhysicalComplete)
    assert(!retained.fallbackCompleteValid)
  }

  test("emits retained fallback when no prior physical completion was captured") {
    val captured = step(
      state = State(valid = false, ridValue = 0, physicalCompleteSeen = false),
      enable = true,
      flush = false,
      fallbackEnable = true,
      captureAccepted = true,
      captureRid = Id(valid = true, value = 3),
      physicalCompleteValid = false,
      physicalCompleteRobValue = 0,
      recordValid = false,
      recordRid = Id(valid = false, value = 0),
      recordFire = false,
      retainedCompleteRowValid = false)
    val retained = step(
      state = captured.state,
      enable = true,
      flush = false,
      fallbackEnable = true,
      captureAccepted = false,
      captureRid = Id(valid = false, value = 0),
      physicalCompleteValid = false,
      physicalCompleteRobValue = 0,
      recordValid = true,
      recordRid = Id(valid = true, value = 3),
      recordFire = false,
      retainedCompleteRowValid = true)

    assert(captured.captureBlockedByNoPhysicalComplete)
    assert(retained.fallbackEligible)
    assert(retained.fallbackCompleteValid)
    assert(retained.fallbackCompleteRobValue == 3)
  }

  test("reports missing retained row and capture evidence blockers") {
    val missingRow = step(
      state = State(valid = true, ridValue = 2, physicalCompleteSeen = false),
      enable = true,
      flush = false,
      fallbackEnable = true,
      captureAccepted = false,
      captureRid = Id(valid = false, value = 0),
      physicalCompleteValid = false,
      physicalCompleteRobValue = 0,
      recordValid = true,
      recordRid = Id(valid = true, value = 2),
      recordFire = false,
      retainedCompleteRowValid = false)
    val missingCapture = step(
      state = State(valid = false, ridValue = 0, physicalCompleteSeen = false),
      enable = true,
      flush = false,
      fallbackEnable = true,
      captureAccepted = false,
      captureRid = Id(valid = false, value = 0),
      physicalCompleteValid = false,
      physicalCompleteRobValue = 0,
      recordValid = true,
      recordRid = Id(valid = true, value = 2),
      recordFire = false,
      retainedCompleteRowValid = true)

    assert(missingRow.blockedByNoRetainedCompleteRow)
    assert(missingCapture.blockedByNoCaptureEvidence)
    assert(!missingCapture.fallbackCompleteValid)
  }

  test("fallback enable gates otherwise eligible retained completion") {
    val result = step(
      state = State(valid = true, ridValue = 1, physicalCompleteSeen = false),
      enable = true,
      flush = false,
      fallbackEnable = false,
      captureAccepted = false,
      captureRid = Id(valid = false, value = 0),
      physicalCompleteValid = false,
      physicalCompleteRobValue = 0,
      recordValid = true,
      recordRid = Id(valid = true, value = 1),
      recordFire = false,
      retainedCompleteRowValid = true)

    assert(result.fallbackEligible)
    assert(result.blockedByFallbackDisabled)
    assert(!result.fallbackCompleteValid)
  }

  test("Chisel LoadReplayReturnPipeW2RetireRecordRobCompleteFallbackGuard elaborates") {
    val sv = ChiselStage.emitSystemVerilog(
      new LoadReplayReturnPipeW2RetireRecordRobCompleteFallbackGuard(
        idEntries = 8,
        traceParams = CommitTraceParams(robValueWidth = 3)))

    assert(sv.contains("module LoadReplayReturnPipeW2RetireRecordRobCompleteFallbackGuard"))
    assert(sv.contains("io_fallbackCompleteValid"))
    assert(sv.contains("io_blockedByPriorPhysicalComplete"))
  }
}
