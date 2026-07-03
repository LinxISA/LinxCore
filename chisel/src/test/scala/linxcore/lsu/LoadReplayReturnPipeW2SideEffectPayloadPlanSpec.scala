package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2SideEffectPayloadPlanReference {
  final case class Result(
      candidateValid: Boolean,
      requiredMask: Int,
      requestMask: Int,
      payloadValidMask: Int,
      requestMatchesRequired: Boolean,
      payloadMatchesRequired: Boolean,
      issueValid: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByNoCandidate: Boolean,
      blockedByNoRequest: Boolean,
      blockedByRequestMismatch: Boolean,
      blockedByMissingResolvePayload: Boolean,
      blockedByMissingWritebackPayload: Boolean,
      blockedByMissingWakeupPayload: Boolean,
      blockedByUnexpectedResolvePayload: Boolean,
      blockedByUnexpectedWritebackPayload: Boolean,
      blockedByUnexpectedWakeupPayload: Boolean,
      invalidRequestWithoutCandidate: Boolean,
      invalidPayloadWithoutRequest: Boolean)

  def mask(resolve: Boolean, writeback: Boolean, wakeup: Boolean): Int =
    (if (wakeup) 4 else 0) |
      (if (writeback) 2 else 0) |
      (if (resolve) 1 else 0)

  def apply(
      enable: Boolean,
      flush: Boolean,
      sideEffectCandidateValid: Boolean,
      requestValid: Boolean,
      resolveRequired: Boolean,
      writebackRequired: Boolean,
      wakeupRequired: Boolean,
      resolveRequest: Boolean,
      writebackRequest: Boolean,
      wakeupRequest: Boolean,
      resolvePayloadValid: Boolean,
      writebackPayloadValid: Boolean,
      wakeupPayloadValid: Boolean): Result = {
    val requiredMask = mask(resolveRequired, writebackRequired, wakeupRequired)
    val requestMask = mask(resolveRequest, writebackRequest, wakeupRequest)
    val payloadValidMask = mask(resolvePayloadValid, writebackPayloadValid, wakeupPayloadValid)
    val active = enable && !flush
    val candidateValid = active && sideEffectCandidateValid
    val requestMatchesRequired = requestMask == requiredMask
    val payloadMatchesRequired = payloadValidMask == requiredMask

    Result(
      candidateValid = candidateValid,
      requiredMask = requiredMask,
      requestMask = requestMask,
      payloadValidMask = payloadValidMask,
      requestMatchesRequired = requestMatchesRequired,
      payloadMatchesRequired = payloadMatchesRequired,
      issueValid = candidateValid && requestValid && requestMatchesRequired && payloadMatchesRequired,
      blockedByDisabled = !enable,
      blockedByFlush = enable && flush,
      blockedByNoCandidate = active && !sideEffectCandidateValid,
      blockedByNoRequest = candidateValid && !requestValid,
      blockedByRequestMismatch = candidateValid && requestValid && !requestMatchesRequired,
      blockedByMissingResolvePayload = candidateValid && requestValid && resolveRequired && !resolvePayloadValid,
      blockedByMissingWritebackPayload =
        candidateValid && requestValid && writebackRequired && !writebackPayloadValid,
      blockedByMissingWakeupPayload = candidateValid && requestValid && wakeupRequired && !wakeupPayloadValid,
      blockedByUnexpectedResolvePayload =
        candidateValid && requestValid && !resolveRequired && resolvePayloadValid,
      blockedByUnexpectedWritebackPayload =
        candidateValid && requestValid && !writebackRequired && writebackPayloadValid,
      blockedByUnexpectedWakeupPayload =
        candidateValid && requestValid && !wakeupRequired && wakeupPayloadValid,
      invalidRequestWithoutCandidate = requestValid && !sideEffectCandidateValid,
      invalidPayloadWithoutRequest = !requestValid && payloadValidMask != 0)
  }
}

class LoadReplayReturnPipeW2SideEffectPayloadPlanSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2SideEffectPayloadPlanReference._

  test("issues when resolve writeback and wakeup requests all have matching payloads") {
    val result = LoadReplayReturnPipeW2SideEffectPayloadPlanReference(
      enable = true,
      flush = false,
      sideEffectCandidateValid = true,
      requestValid = true,
      resolveRequired = true,
      writebackRequired = true,
      wakeupRequired = true,
      resolveRequest = true,
      writebackRequest = true,
      wakeupRequest = true,
      resolvePayloadValid = true,
      writebackPayloadValid = true,
      wakeupPayloadValid = true)

    assert(result.candidateValid)
    assert(result.issueValid)
    assert(result.requiredMask == 0x7)
    assert(result.requestMask == 0x7)
    assert(result.payloadValidMask == 0x7)
    assert(result.requestMatchesRequired)
    assert(result.payloadMatchesRequired)
  }

  test("allows resolve-only side-effect plans") {
    val result = LoadReplayReturnPipeW2SideEffectPayloadPlanReference(
      enable = true,
      flush = false,
      sideEffectCandidateValid = true,
      requestValid = true,
      resolveRequired = true,
      writebackRequired = false,
      wakeupRequired = false,
      resolveRequest = true,
      writebackRequest = false,
      wakeupRequest = false,
      resolvePayloadValid = true,
      writebackPayloadValid = false,
      wakeupPayloadValid = false)

    assert(result.issueValid)
    assert(result.requiredMask == 0x1)
    assert(!result.blockedByMissingWritebackPayload)
    assert(!result.blockedByMissingWakeupPayload)
  }

  test("keeps a side-effect candidate dormant until the post-completion request fires") {
    val result = LoadReplayReturnPipeW2SideEffectPayloadPlanReference(
      enable = true,
      flush = false,
      sideEffectCandidateValid = true,
      requestValid = false,
      resolveRequired = true,
      writebackRequired = false,
      wakeupRequired = false,
      resolveRequest = false,
      writebackRequest = false,
      wakeupRequest = false,
      resolvePayloadValid = false,
      writebackPayloadValid = false,
      wakeupPayloadValid = false)

    assert(result.candidateValid)
    assert(!result.issueValid)
    assert(result.blockedByNoRequest)
    assert(!result.blockedByRequestMismatch)
  }

  test("blocks disabled and flushed plans before side-effect issue") {
    val disabled = LoadReplayReturnPipeW2SideEffectPayloadPlanReference(
      enable = false,
      flush = false,
      sideEffectCandidateValid = true,
      requestValid = true,
      resolveRequired = true,
      writebackRequired = false,
      wakeupRequired = false,
      resolveRequest = true,
      writebackRequest = false,
      wakeupRequest = false,
      resolvePayloadValid = true,
      writebackPayloadValid = false,
      wakeupPayloadValid = false)
    val flushed = LoadReplayReturnPipeW2SideEffectPayloadPlanReference(
      enable = true,
      flush = true,
      sideEffectCandidateValid = true,
      requestValid = true,
      resolveRequired = true,
      writebackRequired = false,
      wakeupRequired = false,
      resolveRequest = true,
      writebackRequest = false,
      wakeupRequest = false,
      resolvePayloadValid = true,
      writebackPayloadValid = false,
      wakeupPayloadValid = false)

    assert(!disabled.candidateValid)
    assert(disabled.blockedByDisabled)
    assert(!flushed.candidateValid)
    assert(flushed.blockedByFlush)
  }

  test("flags request masks that diverge from the required side-effect mask") {
    val result = LoadReplayReturnPipeW2SideEffectPayloadPlanReference(
      enable = true,
      flush = false,
      sideEffectCandidateValid = true,
      requestValid = true,
      resolveRequired = true,
      writebackRequired = true,
      wakeupRequired = true,
      resolveRequest = true,
      writebackRequest = true,
      wakeupRequest = false,
      resolvePayloadValid = true,
      writebackPayloadValid = true,
      wakeupPayloadValid = true)

    assert(!result.issueValid)
    assert(!result.requestMatchesRequired)
    assert(result.blockedByRequestMismatch)
    assert(result.requestMask == 0x3)
    assert(result.requiredMask == 0x7)
  }

  test("flags missing required payloads independently") {
    val result = LoadReplayReturnPipeW2SideEffectPayloadPlanReference(
      enable = true,
      flush = false,
      sideEffectCandidateValid = true,
      requestValid = true,
      resolveRequired = true,
      writebackRequired = true,
      wakeupRequired = true,
      resolveRequest = true,
      writebackRequest = true,
      wakeupRequest = true,
      resolvePayloadValid = true,
      writebackPayloadValid = false,
      wakeupPayloadValid = false)

    assert(!result.issueValid)
    assert(!result.payloadMatchesRequired)
    assert(result.blockedByMissingWritebackPayload)
    assert(result.blockedByMissingWakeupPayload)
    assert(!result.blockedByMissingResolvePayload)
  }

  test("flags unexpected payloads for non-required side effects") {
    val result = LoadReplayReturnPipeW2SideEffectPayloadPlanReference(
      enable = true,
      flush = false,
      sideEffectCandidateValid = true,
      requestValid = true,
      resolveRequired = true,
      writebackRequired = false,
      wakeupRequired = false,
      resolveRequest = true,
      writebackRequest = false,
      wakeupRequest = false,
      resolvePayloadValid = true,
      writebackPayloadValid = true,
      wakeupPayloadValid = true)

    assert(!result.issueValid)
    assert(result.blockedByUnexpectedWritebackPayload)
    assert(result.blockedByUnexpectedWakeupPayload)
  }

  test("reports requests or payloads that bypass the W2 candidate/request owner") {
    val noCandidate = LoadReplayReturnPipeW2SideEffectPayloadPlanReference(
      enable = true,
      flush = false,
      sideEffectCandidateValid = false,
      requestValid = true,
      resolveRequired = true,
      writebackRequired = false,
      wakeupRequired = false,
      resolveRequest = true,
      writebackRequest = false,
      wakeupRequest = false,
      resolvePayloadValid = true,
      writebackPayloadValid = false,
      wakeupPayloadValid = false)
    val payloadWithoutRequest = LoadReplayReturnPipeW2SideEffectPayloadPlanReference(
      enable = true,
      flush = false,
      sideEffectCandidateValid = true,
      requestValid = false,
      resolveRequired = true,
      writebackRequired = false,
      wakeupRequired = false,
      resolveRequest = false,
      writebackRequest = false,
      wakeupRequest = false,
      resolvePayloadValid = true,
      writebackPayloadValid = false,
      wakeupPayloadValid = false)

    assert(!noCandidate.issueValid)
    assert(noCandidate.invalidRequestWithoutCandidate)
    assert(!payloadWithoutRequest.issueValid)
    assert(payloadWithoutRequest.invalidPayloadWithoutRequest)
  }

  test("Chisel LoadReplayReturnPipeW2SideEffectPayloadPlan elaborates compact masks") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPipeW2SideEffectPayloadPlan)

    assert(sv.contains("module LoadReplayReturnPipeW2SideEffectPayloadPlan"))
    assert(sv.contains("io_requiredMask"))
    assert(sv.contains("io_requestMask"))
    assert(sv.contains("io_payloadValidMask"))
    assert(sv.contains("io_issueValid"))
    assert(sv.contains("io_blockedByMissingWakeupPayload"))
  }
}
