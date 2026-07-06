package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2RetireRecordRfWritebackFallbackGuardReference {
  final case class State(
      valid: Boolean,
      ridValue: Int,
      physicalWritebackSeen: Boolean,
      physicalTag: Int,
      physicalData: BigInt)
  final case class Id(valid: Boolean, value: Int)
  final case class Result(
      state: State,
      capturePhysicalWriteback: Boolean,
      captureBlockedByNoPhysicalWriteback: Boolean,
      recordMatchesCapture: Boolean,
      physicalWritebackPayloadMatches: Boolean,
      duplicatePhysicalWriteback: Boolean,
      fallbackEligible: Boolean,
      fallbackWritebackValid: Boolean,
      fallbackWritebackTag: Int,
      fallbackWritebackData: BigInt,
      blockedByNoRecordWriteback: Boolean,
      blockedByNoCaptureEvidence: Boolean,
      blockedByPriorPhysicalWriteback: Boolean,
      blockedByPhysicalWritebackPayloadMismatch: Boolean,
      blockedByFallbackDisabled: Boolean)

  def step(
      state: State,
      enable: Boolean,
      flush: Boolean,
      fallbackEnable: Boolean,
      captureAccepted: Boolean,
      captureRid: Id,
      physicalWritebackValid: Boolean,
      physicalWritebackRid: Id,
      physicalWritebackTag: Int,
      physicalWritebackData: BigInt,
      recordValid: Boolean,
      recordRid: Id,
      recordWritebackValid: Boolean,
      recordWritebackTag: Int,
      recordWritebackData: BigInt,
      recordFire: Boolean): Result = {
    val active = enable && !flush
    val captureIntent = active && captureAccepted && captureRid.valid
    val capturePhysicalWriteback =
      captureIntent && physicalWritebackValid && physicalWritebackRid.valid &&
        physicalWritebackRid.value == captureRid.value
    val recordCandidate = active && recordValid
    val recordMatchesCapture =
      recordCandidate && recordRid.valid && state.valid && recordRid.value == state.ridValue
    val recordWritebackReady = recordCandidate && recordWritebackValid
    val physicalWritebackPayloadMatches =
      recordWritebackReady && state.physicalWritebackSeen &&
        recordWritebackTag == state.physicalTag &&
        recordWritebackData == state.physicalData
    val duplicatePhysicalWriteback = recordMatchesCapture && physicalWritebackPayloadMatches
    val fallbackEligible = recordWritebackReady && recordMatchesCapture && !state.physicalWritebackSeen
    val fallbackWritebackValid = fallbackEnable && fallbackEligible
    val nextState =
      if (flush || !enable) {
        State(valid = false, ridValue = 0, physicalWritebackSeen = false, physicalTag = 0, physicalData = 0)
      } else if (captureIntent) {
        State(
          valid = true,
          ridValue = captureRid.value,
          physicalWritebackSeen = capturePhysicalWriteback,
          physicalTag = if (capturePhysicalWriteback) physicalWritebackTag else 0,
          physicalData = if (capturePhysicalWriteback) physicalWritebackData else 0)
      } else if (recordFire && state.valid) {
        State(valid = false, ridValue = state.ridValue, physicalWritebackSeen = false, physicalTag = 0, physicalData = 0)
      } else {
        state
      }

    Result(
      state = nextState,
      capturePhysicalWriteback = capturePhysicalWriteback,
      captureBlockedByNoPhysicalWriteback = captureIntent && !capturePhysicalWriteback,
      recordMatchesCapture = recordMatchesCapture,
      physicalWritebackPayloadMatches = physicalWritebackPayloadMatches,
      duplicatePhysicalWriteback = duplicatePhysicalWriteback,
      fallbackEligible = fallbackEligible,
      fallbackWritebackValid = fallbackWritebackValid,
      fallbackWritebackTag = if (fallbackWritebackValid) recordWritebackTag else 0,
      fallbackWritebackData = if (fallbackWritebackValid) recordWritebackData else 0,
      blockedByNoRecordWriteback = recordCandidate && !recordWritebackValid,
      blockedByNoCaptureEvidence = recordWritebackReady && !recordMatchesCapture,
      blockedByPriorPhysicalWriteback = recordWritebackReady && recordMatchesCapture && state.physicalWritebackSeen,
      blockedByPhysicalWritebackPayloadMismatch =
        recordWritebackReady && recordMatchesCapture && state.physicalWritebackSeen &&
          !physicalWritebackPayloadMatches,
      blockedByFallbackDisabled = fallbackEligible && !fallbackEnable)
  }
}

class LoadReplayReturnPipeW2RetireRecordRfWritebackFallbackGuardSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2RetireRecordRfWritebackFallbackGuardReference._

  test("blocks retained fallback when physical RF writeback was captured for the same RID and payload") {
    val captured = step(
      state = State(valid = false, ridValue = 0, physicalWritebackSeen = false, physicalTag = 0, physicalData = 0),
      enable = true,
      flush = false,
      fallbackEnable = true,
      captureAccepted = true,
      captureRid = Id(valid = true, value = 5),
      physicalWritebackValid = true,
      physicalWritebackRid = Id(valid = true, value = 5),
      physicalWritebackTag = 12,
      physicalWritebackData = BigInt("1122334455667788", 16),
      recordValid = false,
      recordRid = Id(valid = false, value = 0),
      recordWritebackValid = false,
      recordWritebackTag = 0,
      recordWritebackData = 0,
      recordFire = false)
    val retained = step(
      state = captured.state,
      enable = true,
      flush = false,
      fallbackEnable = true,
      captureAccepted = false,
      captureRid = Id(valid = false, value = 0),
      physicalWritebackValid = false,
      physicalWritebackRid = Id(valid = false, value = 0),
      physicalWritebackTag = 0,
      physicalWritebackData = 0,
      recordValid = true,
      recordRid = Id(valid = true, value = 5),
      recordWritebackValid = true,
      recordWritebackTag = 12,
      recordWritebackData = BigInt("1122334455667788", 16),
      recordFire = false)

    assert(captured.capturePhysicalWriteback)
    assert(retained.recordMatchesCapture)
    assert(retained.physicalWritebackPayloadMatches)
    assert(retained.duplicatePhysicalWriteback)
    assert(retained.blockedByPriorPhysicalWriteback)
    assert(!retained.fallbackWritebackValid)
  }

  test("emits retained fallback when no prior physical RF writeback was captured") {
    val captured = step(
      state = State(valid = false, ridValue = 0, physicalWritebackSeen = false, physicalTag = 0, physicalData = 0),
      enable = true,
      flush = false,
      fallbackEnable = true,
      captureAccepted = true,
      captureRid = Id(valid = true, value = 3),
      physicalWritebackValid = false,
      physicalWritebackRid = Id(valid = false, value = 0),
      physicalWritebackTag = 0,
      physicalWritebackData = 0,
      recordValid = false,
      recordRid = Id(valid = false, value = 0),
      recordWritebackValid = false,
      recordWritebackTag = 0,
      recordWritebackData = 0,
      recordFire = false)
    val retained = step(
      state = captured.state,
      enable = true,
      flush = false,
      fallbackEnable = true,
      captureAccepted = false,
      captureRid = Id(valid = false, value = 0),
      physicalWritebackValid = false,
      physicalWritebackRid = Id(valid = false, value = 0),
      physicalWritebackTag = 0,
      physicalWritebackData = 0,
      recordValid = true,
      recordRid = Id(valid = true, value = 3),
      recordWritebackValid = true,
      recordWritebackTag = 9,
      recordWritebackData = 42,
      recordFire = false)

    assert(captured.captureBlockedByNoPhysicalWriteback)
    assert(retained.fallbackEligible)
    assert(retained.fallbackWritebackValid)
    assert(retained.fallbackWritebackTag == 9)
    assert(retained.fallbackWritebackData == 42)
  }

  test("reports payload mismatch separately from prior physical writeback") {
    val result = step(
      state = State(valid = true, ridValue = 2, physicalWritebackSeen = true, physicalTag = 7, physicalData = 99),
      enable = true,
      flush = false,
      fallbackEnable = true,
      captureAccepted = false,
      captureRid = Id(valid = false, value = 0),
      physicalWritebackValid = false,
      physicalWritebackRid = Id(valid = false, value = 0),
      physicalWritebackTag = 0,
      physicalWritebackData = 0,
      recordValid = true,
      recordRid = Id(valid = true, value = 2),
      recordWritebackValid = true,
      recordWritebackTag = 8,
      recordWritebackData = 99,
      recordFire = false)

    assert(result.blockedByPriorPhysicalWriteback)
    assert(result.blockedByPhysicalWritebackPayloadMismatch)
    assert(!result.duplicatePhysicalWriteback)
    assert(!result.fallbackWritebackValid)
  }

  test("reports missing record writeback and capture evidence blockers") {
    val missingWriteback = step(
      state = State(valid = true, ridValue = 1, physicalWritebackSeen = false, physicalTag = 0, physicalData = 0),
      enable = true,
      flush = false,
      fallbackEnable = true,
      captureAccepted = false,
      captureRid = Id(valid = false, value = 0),
      physicalWritebackValid = false,
      physicalWritebackRid = Id(valid = false, value = 0),
      physicalWritebackTag = 0,
      physicalWritebackData = 0,
      recordValid = true,
      recordRid = Id(valid = true, value = 1),
      recordWritebackValid = false,
      recordWritebackTag = 0,
      recordWritebackData = 0,
      recordFire = false)
    val missingCapture = step(
      state = State(valid = false, ridValue = 0, physicalWritebackSeen = false, physicalTag = 0, physicalData = 0),
      enable = true,
      flush = false,
      fallbackEnable = true,
      captureAccepted = false,
      captureRid = Id(valid = false, value = 0),
      physicalWritebackValid = false,
      physicalWritebackRid = Id(valid = false, value = 0),
      physicalWritebackTag = 0,
      physicalWritebackData = 0,
      recordValid = true,
      recordRid = Id(valid = true, value = 1),
      recordWritebackValid = true,
      recordWritebackTag = 2,
      recordWritebackData = 3,
      recordFire = false)

    assert(missingWriteback.blockedByNoRecordWriteback)
    assert(missingCapture.blockedByNoCaptureEvidence)
    assert(!missingCapture.fallbackWritebackValid)
  }

  test("fallback enable gates otherwise eligible retained writeback") {
    val result = step(
      state = State(valid = true, ridValue = 1, physicalWritebackSeen = false, physicalTag = 0, physicalData = 0),
      enable = true,
      flush = false,
      fallbackEnable = false,
      captureAccepted = false,
      captureRid = Id(valid = false, value = 0),
      physicalWritebackValid = false,
      physicalWritebackRid = Id(valid = false, value = 0),
      physicalWritebackTag = 0,
      physicalWritebackData = 0,
      recordValid = true,
      recordRid = Id(valid = true, value = 1),
      recordWritebackValid = true,
      recordWritebackTag = 2,
      recordWritebackData = 3,
      recordFire = false)

    assert(result.fallbackEligible)
    assert(result.blockedByFallbackDisabled)
    assert(!result.fallbackWritebackValid)
  }

  test("Chisel LoadReplayReturnPipeW2RetireRecordRfWritebackFallbackGuard elaborates") {
    val sv = ChiselStage.emitSystemVerilog(
      new LoadReplayReturnPipeW2RetireRecordRfWritebackFallbackGuard(
        idEntries = 8,
        dataWidth = 32,
        physRegWidth = 5))

    assert(sv.contains("module LoadReplayReturnPipeW2RetireRecordRfWritebackFallbackGuard"))
    assert(sv.contains("io_fallbackWritebackValid"))
    assert(sv.contains("io_blockedByPriorPhysicalWriteback"))
  }
}
