package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2RetireRecordWakeupFallbackGuardReference {
  final case class State(
      valid: Boolean,
      ridValue: Int,
      physicalWakeupSeen: Boolean,
      physicalReducedGpr: Boolean,
      physicalTag: Int)
  final case class Id(valid: Boolean, value: Int)
  final case class Result(
      state: State,
      capturePhysicalWakeup: Boolean,
      captureBlockedByNoPhysicalWakeup: Boolean,
      recordMatchesCapture: Boolean,
      physicalWakeupPayloadMatches: Boolean,
      duplicatePhysicalWakeup: Boolean,
      fallbackEligible: Boolean,
      fallbackWakeupValid: Boolean,
      fallbackReducedGprWakeup: Boolean,
      fallbackWakeupTag: Int,
      blockedByNoRecordWakeup: Boolean,
      blockedByNoCaptureEvidence: Boolean,
      blockedByPriorPhysicalWakeup: Boolean,
      blockedByPhysicalWakeupPayloadMismatch: Boolean,
      blockedByFallbackDisabled: Boolean)

  def step(
      state: State,
      enable: Boolean,
      flush: Boolean,
      fallbackEnable: Boolean,
      captureAccepted: Boolean,
      captureRid: Id,
      physicalWakeupValid: Boolean,
      physicalWakeupRid: Id,
      physicalReducedGprWakeup: Boolean,
      physicalWakeupTag: Int,
      recordValid: Boolean,
      recordRid: Id,
      recordWakeupValid: Boolean,
      recordReducedGprWakeup: Boolean,
      recordWakeupTag: Int,
      recordFire: Boolean): Result = {
    val active = enable && !flush
    val captureIntent = active && captureAccepted && captureRid.valid
    val capturePhysicalWakeup =
      captureIntent && physicalWakeupValid && physicalWakeupRid.valid &&
        physicalWakeupRid.value == captureRid.value
    val recordCandidate = active && recordValid
    val recordMatchesCapture =
      recordCandidate && recordRid.valid && state.valid && recordRid.value == state.ridValue
    val recordWakeupReady = recordCandidate && recordWakeupValid
    val physicalWakeupPayloadMatches =
      recordWakeupReady && state.physicalWakeupSeen &&
        recordReducedGprWakeup == state.physicalReducedGpr &&
        recordWakeupTag == state.physicalTag
    val duplicatePhysicalWakeup = recordMatchesCapture && physicalWakeupPayloadMatches
    val fallbackEligible = recordWakeupReady && recordMatchesCapture && !state.physicalWakeupSeen
    val fallbackWakeupValid = fallbackEnable && fallbackEligible
    val nextState =
      if (flush || !enable) {
        State(valid = false, ridValue = 0, physicalWakeupSeen = false, physicalReducedGpr = false, physicalTag = 0)
      } else if (captureIntent) {
        State(
          valid = true,
          ridValue = captureRid.value,
          physicalWakeupSeen = capturePhysicalWakeup,
          physicalReducedGpr = if (capturePhysicalWakeup) physicalReducedGprWakeup else false,
          physicalTag = if (capturePhysicalWakeup) physicalWakeupTag else 0)
      } else if (recordFire && state.valid) {
        State(valid = false, ridValue = state.ridValue, physicalWakeupSeen = false, physicalReducedGpr = false, physicalTag = 0)
      } else {
        state
      }

    Result(
      state = nextState,
      capturePhysicalWakeup = capturePhysicalWakeup,
      captureBlockedByNoPhysicalWakeup = captureIntent && !capturePhysicalWakeup,
      recordMatchesCapture = recordMatchesCapture,
      physicalWakeupPayloadMatches = physicalWakeupPayloadMatches,
      duplicatePhysicalWakeup = duplicatePhysicalWakeup,
      fallbackEligible = fallbackEligible,
      fallbackWakeupValid = fallbackWakeupValid,
      fallbackReducedGprWakeup = fallbackWakeupValid && recordReducedGprWakeup,
      fallbackWakeupTag = if (fallbackWakeupValid) recordWakeupTag else 0,
      blockedByNoRecordWakeup = recordCandidate && !recordWakeupValid,
      blockedByNoCaptureEvidence = recordWakeupReady && !recordMatchesCapture,
      blockedByPriorPhysicalWakeup = recordWakeupReady && recordMatchesCapture && state.physicalWakeupSeen,
      blockedByPhysicalWakeupPayloadMismatch =
        recordWakeupReady && recordMatchesCapture && state.physicalWakeupSeen &&
          !physicalWakeupPayloadMatches,
      blockedByFallbackDisabled = fallbackEligible && !fallbackEnable)
  }
}

class LoadReplayReturnPipeW2RetireRecordWakeupFallbackGuardSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2RetireRecordWakeupFallbackGuardReference._

  test("blocks retained fallback when physical wakeup was captured for the same RID and payload") {
    val captured = step(
      state = State(valid = false, ridValue = 0, physicalWakeupSeen = false, physicalReducedGpr = false, physicalTag = 0),
      enable = true,
      flush = false,
      fallbackEnable = true,
      captureAccepted = true,
      captureRid = Id(valid = true, value = 5),
      physicalWakeupValid = true,
      physicalWakeupRid = Id(valid = true, value = 5),
      physicalReducedGprWakeup = true,
      physicalWakeupTag = 12,
      recordValid = false,
      recordRid = Id(valid = false, value = 0),
      recordWakeupValid = false,
      recordReducedGprWakeup = false,
      recordWakeupTag = 0,
      recordFire = false)
    val retained = step(
      state = captured.state,
      enable = true,
      flush = false,
      fallbackEnable = true,
      captureAccepted = false,
      captureRid = Id(valid = false, value = 0),
      physicalWakeupValid = false,
      physicalWakeupRid = Id(valid = false, value = 0),
      physicalReducedGprWakeup = false,
      physicalWakeupTag = 0,
      recordValid = true,
      recordRid = Id(valid = true, value = 5),
      recordWakeupValid = true,
      recordReducedGprWakeup = true,
      recordWakeupTag = 12,
      recordFire = false)

    assert(captured.capturePhysicalWakeup)
    assert(retained.recordMatchesCapture)
    assert(retained.physicalWakeupPayloadMatches)
    assert(retained.duplicatePhysicalWakeup)
    assert(retained.blockedByPriorPhysicalWakeup)
    assert(!retained.fallbackWakeupValid)
  }

  test("emits retained fallback when no prior physical wakeup was captured") {
    val captured = step(
      state = State(valid = false, ridValue = 0, physicalWakeupSeen = false, physicalReducedGpr = false, physicalTag = 0),
      enable = true,
      flush = false,
      fallbackEnable = true,
      captureAccepted = true,
      captureRid = Id(valid = true, value = 3),
      physicalWakeupValid = false,
      physicalWakeupRid = Id(valid = false, value = 0),
      physicalReducedGprWakeup = false,
      physicalWakeupTag = 0,
      recordValid = false,
      recordRid = Id(valid = false, value = 0),
      recordWakeupValid = false,
      recordReducedGprWakeup = false,
      recordWakeupTag = 0,
      recordFire = false)
    val retained = step(
      state = captured.state,
      enable = true,
      flush = false,
      fallbackEnable = true,
      captureAccepted = false,
      captureRid = Id(valid = false, value = 0),
      physicalWakeupValid = false,
      physicalWakeupRid = Id(valid = false, value = 0),
      physicalReducedGprWakeup = false,
      physicalWakeupTag = 0,
      recordValid = true,
      recordRid = Id(valid = true, value = 3),
      recordWakeupValid = true,
      recordReducedGprWakeup = true,
      recordWakeupTag = 9,
      recordFire = false)

    assert(captured.captureBlockedByNoPhysicalWakeup)
    assert(retained.fallbackEligible)
    assert(retained.fallbackWakeupValid)
    assert(retained.fallbackReducedGprWakeup)
    assert(retained.fallbackWakeupTag == 9)
  }

  test("reports payload mismatch separately from prior physical wakeup") {
    val result = step(
      state = State(valid = true, ridValue = 2, physicalWakeupSeen = true, physicalReducedGpr = true, physicalTag = 7),
      enable = true,
      flush = false,
      fallbackEnable = true,
      captureAccepted = false,
      captureRid = Id(valid = false, value = 0),
      physicalWakeupValid = false,
      physicalWakeupRid = Id(valid = false, value = 0),
      physicalReducedGprWakeup = false,
      physicalWakeupTag = 0,
      recordValid = true,
      recordRid = Id(valid = true, value = 2),
      recordWakeupValid = true,
      recordReducedGprWakeup = false,
      recordWakeupTag = 7,
      recordFire = false)

    assert(result.blockedByPriorPhysicalWakeup)
    assert(result.blockedByPhysicalWakeupPayloadMismatch)
    assert(!result.duplicatePhysicalWakeup)
    assert(!result.fallbackWakeupValid)
  }

  test("reports missing record wakeup and capture evidence blockers") {
    val missingWakeup = step(
      state = State(valid = true, ridValue = 1, physicalWakeupSeen = false, physicalReducedGpr = false, physicalTag = 0),
      enable = true,
      flush = false,
      fallbackEnable = true,
      captureAccepted = false,
      captureRid = Id(valid = false, value = 0),
      physicalWakeupValid = false,
      physicalWakeupRid = Id(valid = false, value = 0),
      physicalReducedGprWakeup = false,
      physicalWakeupTag = 0,
      recordValid = true,
      recordRid = Id(valid = true, value = 1),
      recordWakeupValid = false,
      recordReducedGprWakeup = false,
      recordWakeupTag = 0,
      recordFire = false)
    val missingCapture = step(
      state = State(valid = false, ridValue = 0, physicalWakeupSeen = false, physicalReducedGpr = false, physicalTag = 0),
      enable = true,
      flush = false,
      fallbackEnable = true,
      captureAccepted = false,
      captureRid = Id(valid = false, value = 0),
      physicalWakeupValid = false,
      physicalWakeupRid = Id(valid = false, value = 0),
      physicalReducedGprWakeup = false,
      physicalWakeupTag = 0,
      recordValid = true,
      recordRid = Id(valid = true, value = 1),
      recordWakeupValid = true,
      recordReducedGprWakeup = true,
      recordWakeupTag = 2,
      recordFire = false)

    assert(missingWakeup.blockedByNoRecordWakeup)
    assert(missingCapture.blockedByNoCaptureEvidence)
    assert(!missingCapture.fallbackWakeupValid)
  }

  test("fallback enable gates otherwise eligible retained wakeup") {
    val result = step(
      state = State(valid = true, ridValue = 1, physicalWakeupSeen = false, physicalReducedGpr = false, physicalTag = 0),
      enable = true,
      flush = false,
      fallbackEnable = false,
      captureAccepted = false,
      captureRid = Id(valid = false, value = 0),
      physicalWakeupValid = false,
      physicalWakeupRid = Id(valid = false, value = 0),
      physicalReducedGprWakeup = false,
      physicalWakeupTag = 0,
      recordValid = true,
      recordRid = Id(valid = true, value = 1),
      recordWakeupValid = true,
      recordReducedGprWakeup = true,
      recordWakeupTag = 2,
      recordFire = false)

    assert(result.fallbackEligible)
    assert(result.blockedByFallbackDisabled)
    assert(!result.fallbackWakeupValid)
  }

  test("Chisel LoadReplayReturnPipeW2RetireRecordWakeupFallbackGuard elaborates") {
    val sv = ChiselStage.emitSystemVerilog(
      new LoadReplayReturnPipeW2RetireRecordWakeupFallbackGuard(
        idEntries = 8,
        physRegWidth = 6))

    assert(sv.contains("module LoadReplayReturnPipeW2RetireRecordWakeupFallbackGuard"))
    assert(sv.contains("io_fallbackWakeupValid"))
    assert(sv.contains("io_blockedByPriorPhysicalWakeup"))
  }
}
