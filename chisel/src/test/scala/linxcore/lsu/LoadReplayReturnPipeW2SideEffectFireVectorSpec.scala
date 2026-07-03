package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2SideEffectFireVectorReference {
  final case class Result(
      candidateValid: Boolean,
      fireValid: Boolean,
      fireMask: Int,
      resolveFire: Boolean,
      writebackFire: Boolean,
      wakeupFire: Boolean,
      requestMissingMask: Int,
      payloadMissingMask: Int,
      requestExtraMask: Int,
      payloadExtraMask: Int,
      requestMatchesAccepted: Boolean,
      payloadMatchesAccepted: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByNoIssueAccept: Boolean,
      blockedByNoAcceptedSink: Boolean,
      blockedByRequestMismatch: Boolean,
      blockedByPayloadMismatch: Boolean,
      invalidAcceptedWithoutRequest: Boolean,
      invalidAcceptedWithoutPayload: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      issueAccepted: Boolean,
      acceptedMask: Int,
      requestMask: Int,
      payloadValidMask: Int): Result = {
    val active = enable && !flush
    val candidateValid = active && issueAccepted
    val accepted = acceptedMask & 0x7
    val request = requestMask & 0x7
    val payload = payloadValidMask & 0x7
    val hasAcceptedSink = accepted != 0
    val requestMissingMask = accepted & (~request & 0x7)
    val payloadMissingMask = accepted & (~payload & 0x7)
    val requestExtraMask = request & (~accepted & 0x7)
    val payloadExtraMask = payload & (~accepted & 0x7)
    val requestMatchesAccepted = request == accepted
    val payloadMatchesAccepted = payload == accepted
    val fireValid =
      candidateValid && hasAcceptedSink && requestMatchesAccepted && payloadMatchesAccepted
    val fireMask = if (fireValid) accepted else 0

    Result(
      candidateValid = candidateValid,
      fireValid = fireValid,
      fireMask = fireMask,
      resolveFire = (fireMask & 0x1) != 0,
      writebackFire = (fireMask & 0x2) != 0,
      wakeupFire = (fireMask & 0x4) != 0,
      requestMissingMask = requestMissingMask,
      payloadMissingMask = payloadMissingMask,
      requestExtraMask = requestExtraMask,
      payloadExtraMask = payloadExtraMask,
      requestMatchesAccepted = requestMatchesAccepted,
      payloadMatchesAccepted = payloadMatchesAccepted,
      blockedByDisabled = !enable && issueAccepted,
      blockedByFlush = enable && flush && issueAccepted,
      blockedByNoIssueAccept = active && !issueAccepted,
      blockedByNoAcceptedSink = candidateValid && !hasAcceptedSink,
      blockedByRequestMismatch = candidateValid && !requestMatchesAccepted,
      blockedByPayloadMismatch = candidateValid && !payloadMatchesAccepted,
      invalidAcceptedWithoutRequest = candidateValid && requestMissingMask != 0,
      invalidAcceptedWithoutPayload = candidateValid && payloadMissingMask != 0)
  }
}

class LoadReplayReturnPipeW2SideEffectFireVectorSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2SideEffectFireVectorReference._

  test("fires resolve writeback and wakeup when accepted request and payload masks match") {
    val result = LoadReplayReturnPipeW2SideEffectFireVectorReference(
      enable = true,
      flush = false,
      issueAccepted = true,
      acceptedMask = 0x7,
      requestMask = 0x7,
      payloadValidMask = 0x7)

    assert(result.candidateValid)
    assert(result.fireValid)
    assert(result.fireMask == 0x7)
    assert(result.resolveFire)
    assert(result.writebackFire)
    assert(result.wakeupFire)
    assert(result.requestMatchesAccepted)
    assert(result.payloadMatchesAccepted)
  }

  test("fires a resolve-only accepted side effect without optional sink pulses") {
    val result = LoadReplayReturnPipeW2SideEffectFireVectorReference(
      enable = true,
      flush = false,
      issueAccepted = true,
      acceptedMask = 0x1,
      requestMask = 0x1,
      payloadValidMask = 0x1)

    assert(result.fireValid)
    assert(result.fireMask == 0x1)
    assert(result.resolveFire)
    assert(!result.writebackFire)
    assert(!result.wakeupFire)
  }

  test("stays dormant while the issue permit has not accepted") {
    val result = LoadReplayReturnPipeW2SideEffectFireVectorReference(
      enable = true,
      flush = false,
      issueAccepted = false,
      acceptedMask = 0x0,
      requestMask = 0x7,
      payloadValidMask = 0x7)

    assert(!result.candidateValid)
    assert(!result.fireValid)
    assert(result.fireMask == 0x0)
    assert(result.blockedByNoIssueAccept)
    assert(!result.blockedByRequestMismatch)
    assert(!result.blockedByPayloadMismatch)
  }

  test("blocks disabled and flushed accepted vectors without firing sinks") {
    val disabled = LoadReplayReturnPipeW2SideEffectFireVectorReference(
      enable = false,
      flush = false,
      issueAccepted = true,
      acceptedMask = 0x1,
      requestMask = 0x1,
      payloadValidMask = 0x1)
    val flushed = LoadReplayReturnPipeW2SideEffectFireVectorReference(
      enable = true,
      flush = true,
      issueAccepted = true,
      acceptedMask = 0x1,
      requestMask = 0x1,
      payloadValidMask = 0x1)

    assert(!disabled.fireValid)
    assert(disabled.blockedByDisabled)
    assert(!flushed.fireValid)
    assert(flushed.blockedByFlush)
  }

  test("flags accepted issue with no accepted sink mask") {
    val result = LoadReplayReturnPipeW2SideEffectFireVectorReference(
      enable = true,
      flush = false,
      issueAccepted = true,
      acceptedMask = 0x0,
      requestMask = 0x0,
      payloadValidMask = 0x0)

    assert(result.candidateValid)
    assert(!result.fireValid)
    assert(result.blockedByNoAcceptedSink)
  }

  test("flags request and payload masks that do not match accepted sinks") {
    val requestMismatch = LoadReplayReturnPipeW2SideEffectFireVectorReference(
      enable = true,
      flush = false,
      issueAccepted = true,
      acceptedMask = 0x7,
      requestMask = 0x3,
      payloadValidMask = 0x7)
    val payloadMismatch = LoadReplayReturnPipeW2SideEffectFireVectorReference(
      enable = true,
      flush = false,
      issueAccepted = true,
      acceptedMask = 0x3,
      requestMask = 0x3,
      payloadValidMask = 0x7)

    assert(!requestMismatch.fireValid)
    assert(requestMismatch.requestMissingMask == 0x4)
    assert(requestMismatch.blockedByRequestMismatch)
    assert(requestMismatch.invalidAcceptedWithoutRequest)
    assert(!payloadMismatch.fireValid)
    assert(payloadMismatch.payloadExtraMask == 0x4)
    assert(payloadMismatch.blockedByPayloadMismatch)
    assert(!payloadMismatch.invalidAcceptedWithoutPayload)
  }

  test("Chisel LoadReplayReturnPipeW2SideEffectFireVector elaborates fire diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPipeW2SideEffectFireVector)

    assert(sv.contains("module LoadReplayReturnPipeW2SideEffectFireVector"))
    assert(sv.contains("io_issueAccepted"))
    assert(sv.contains("io_acceptedMask"))
    assert(sv.contains("io_requestMask"))
    assert(sv.contains("io_payloadValidMask"))
    assert(sv.contains("io_fireMask"))
    assert(sv.contains("io_resolveFire"))
    assert(sv.contains("io_blockedByPayloadMismatch"))
  }
}
