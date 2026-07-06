package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2RetireRecordReference {
  final case class Result(
      captureCandidate: Boolean,
      payloadValid: Boolean,
      captureValid: Boolean,
      captureReady: Boolean,
      captureAccepted: Boolean,
      captureDropped: Boolean,
      recordFire: Boolean,
      pendingAfter: Boolean,
      capturedWithLretEnqueue: Boolean,
      blockedByInvalidPayload: Boolean,
      blockedByFull: Boolean)

  def step(
      enable: Boolean,
      flush: Boolean,
      slotOccupied: Boolean,
      completionClearSlot: Boolean,
      clearIntent: Boolean,
      liveClear: Boolean,
      lretEnqueueAccepted: Boolean,
      retirePayloadValid: Boolean,
      recordReady: Boolean,
      recordValidBefore: Boolean): Result = {
    val active = enable && !flush
    val captureCandidate =
      active && slotOccupied && completionClearSlot && clearIntent && liveClear
    val captureValid = captureCandidate && retirePayloadValid
    val recordFire = recordValidBefore && recordReady && !flush
    val captureReady = !recordValidBefore || recordFire
    val captureAccepted = captureValid && captureReady
    val captureDropped = captureValid && !captureReady
    val pendingAfter =
      if (flush) false
      else if (captureAccepted) true
      else if (recordFire) false
      else recordValidBefore

    Result(
      captureCandidate = captureCandidate,
      payloadValid = captureCandidate && retirePayloadValid,
      captureValid = captureValid,
      captureReady = captureReady,
      captureAccepted = captureAccepted,
      captureDropped = captureDropped,
      recordFire = recordFire,
      pendingAfter = pendingAfter,
      capturedWithLretEnqueue = captureAccepted && lretEnqueueAccepted,
      blockedByInvalidPayload = captureCandidate && !retirePayloadValid,
      blockedByFull = captureDropped)
  }
}

class LoadReplayReturnPipeW2RetireRecordSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2RetireRecordReference._

  test("captures a live W2 clear payload into an empty retire record") {
    val result = LoadReplayReturnPipeW2RetireRecordReference.step(
      enable = true,
      flush = false,
      slotOccupied = true,
      completionClearSlot = true,
      clearIntent = true,
      liveClear = true,
      lretEnqueueAccepted = true,
      retirePayloadValid = true,
      recordReady = false,
      recordValidBefore = false)

    assert(result.captureCandidate)
    assert(result.payloadValid)
    assert(result.captureValid)
    assert(result.captureReady)
    assert(result.captureAccepted)
    assert(result.pendingAfter)
    assert(result.capturedWithLretEnqueue)
  }

  test("blocks capture when the W2 clear is not live") {
    val result = LoadReplayReturnPipeW2RetireRecordReference.step(
      enable = true,
      flush = false,
      slotOccupied = true,
      completionClearSlot = true,
      clearIntent = true,
      liveClear = false,
      lretEnqueueAccepted = true,
      retirePayloadValid = true,
      recordReady = false,
      recordValidBefore = false)

    assert(!result.captureCandidate)
    assert(!result.captureValid)
    assert(!result.captureAccepted)
    assert(!result.pendingAfter)
  }

  test("reports invalid payload under an otherwise live W2 clear") {
    val result = LoadReplayReturnPipeW2RetireRecordReference.step(
      enable = true,
      flush = false,
      slotOccupied = true,
      completionClearSlot = true,
      clearIntent = true,
      liveClear = true,
      lretEnqueueAccepted = false,
      retirePayloadValid = false,
      recordReady = false,
      recordValidBefore = false)

    assert(result.captureCandidate)
    assert(!result.payloadValid)
    assert(!result.captureAccepted)
    assert(result.blockedByInvalidPayload)
  }

  test("drops capture when the retire record is full and not consumed") {
    val result = LoadReplayReturnPipeW2RetireRecordReference.step(
      enable = true,
      flush = false,
      slotOccupied = true,
      completionClearSlot = true,
      clearIntent = true,
      liveClear = true,
      lretEnqueueAccepted = false,
      retirePayloadValid = true,
      recordReady = false,
      recordValidBefore = true)

    assert(result.captureValid)
    assert(!result.captureReady)
    assert(result.captureDropped)
    assert(result.blockedByFull)
    assert(result.pendingAfter)
  }

  test("allows same-cycle consume and recapture") {
    val result = LoadReplayReturnPipeW2RetireRecordReference.step(
      enable = true,
      flush = false,
      slotOccupied = true,
      completionClearSlot = true,
      clearIntent = true,
      liveClear = true,
      lretEnqueueAccepted = true,
      retirePayloadValid = true,
      recordReady = true,
      recordValidBefore = true)

    assert(result.recordFire)
    assert(result.captureReady)
    assert(result.captureAccepted)
    assert(result.pendingAfter)
  }

  test("Chisel LoadReplayReturnPipeW2RetireRecord elaborates record controls") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPipeW2RetireRecord)

    assert(sv.contains("module LoadReplayReturnPipeW2RetireRecord"))
    assert(sv.contains("io_captureAccepted"))
    assert(sv.contains("io_recordValid"))
    assert(sv.contains("io_recordFire"))
    assert(sv.contains("io_recordFromLretEnqueue"))
  }
}
