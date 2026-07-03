package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2AtomicLiveRequestControlReference {
  final case class Result(
      active: Boolean,
      requestActive: Boolean,
      requestEvidenceValid: Boolean,
      sideEffectLiveRequested: Boolean,
      promotionRequested: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByRequestDisabled: Boolean,
      blockedByNoEvidence: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      requestEnable: Boolean,
      sideEffectCandidateValid: Boolean,
      sideEffectRequiredMask: Int,
      clearIntent: Boolean,
      writeCandidateValid: Boolean): Result = {
    val active = enable && !flush
    val requestActive = active && requestEnable
    val rawEvidence =
      (sideEffectCandidateValid && (sideEffectRequiredMask & 0x7) != 0) ||
        clearIntent ||
        writeCandidateValid
    val requestEvidenceValid = active && rawEvidence

    Result(
      active = active,
      requestActive = requestActive,
      requestEvidenceValid = requestEvidenceValid,
      sideEffectLiveRequested = requestActive,
      promotionRequested = requestActive,
      blockedByDisabled = !enable && (requestEnable || rawEvidence),
      blockedByFlush = enable && flush && (requestEnable || rawEvidence),
      blockedByRequestDisabled = active && !requestEnable && rawEvidence,
      blockedByNoEvidence = requestActive && !rawEvidence)
  }
}

class LoadReplayReturnPipeW2AtomicLiveRequestControlSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2AtomicLiveRequestControlReference._

  test("keeps both W2 live requests disabled when the top request is false") {
    val result = LoadReplayReturnPipeW2AtomicLiveRequestControlReference(
      enable = true,
      flush = false,
      requestEnable = false,
      sideEffectCandidateValid = true,
      sideEffectRequiredMask = 0x7,
      clearIntent = true,
      writeCandidateValid = true)

    assert(result.active)
    assert(!result.requestActive)
    assert(result.requestEvidenceValid)
    assert(!result.sideEffectLiveRequested)
    assert(!result.promotionRequested)
    assert(result.blockedByRequestDisabled)
  }

  test("drives side-effect and promotion requests from one active gate") {
    val result = LoadReplayReturnPipeW2AtomicLiveRequestControlReference(
      enable = true,
      flush = false,
      requestEnable = true,
      sideEffectCandidateValid = true,
      sideEffectRequiredMask = 0x5,
      clearIntent = false,
      writeCandidateValid = false)

    assert(result.requestActive)
    assert(result.requestEvidenceValid)
    assert(result.sideEffectLiveRequested)
    assert(result.promotionRequested)
    assert(!result.blockedByRequestDisabled)
    assert(!result.blockedByNoEvidence)
  }

  test("reports disabled and flush blockers without issuing requests") {
    val disabled = LoadReplayReturnPipeW2AtomicLiveRequestControlReference(
      enable = false,
      flush = false,
      requestEnable = true,
      sideEffectCandidateValid = true,
      sideEffectRequiredMask = 0x1,
      clearIntent = false,
      writeCandidateValid = false)
    val flushed = LoadReplayReturnPipeW2AtomicLiveRequestControlReference(
      enable = true,
      flush = true,
      requestEnable = true,
      sideEffectCandidateValid = false,
      sideEffectRequiredMask = 0x0,
      clearIntent = true,
      writeCandidateValid = false)

    assert(disabled.blockedByDisabled)
    assert(!disabled.active)
    assert(!disabled.sideEffectLiveRequested)
    assert(flushed.blockedByFlush)
    assert(!flushed.active)
    assert(!flushed.promotionRequested)
  }

  test("reports active requests that lack W2 evidence") {
    val result = LoadReplayReturnPipeW2AtomicLiveRequestControlReference(
      enable = true,
      flush = false,
      requestEnable = true,
      sideEffectCandidateValid = true,
      sideEffectRequiredMask = 0x0,
      clearIntent = false,
      writeCandidateValid = false)

    assert(result.requestActive)
    assert(!result.requestEvidenceValid)
    assert(result.sideEffectLiveRequested)
    assert(result.promotionRequested)
    assert(result.blockedByNoEvidence)
  }

  test("Chisel LoadReplayReturnPipeW2AtomicLiveRequestControl elaborates shared request ports") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPipeW2AtomicLiveRequestControl)

    assert(sv.contains("module LoadReplayReturnPipeW2AtomicLiveRequestControl"))
    assert(sv.contains("io_sideEffectLiveRequested"))
    assert(sv.contains("io_promotionRequested"))
    assert(sv.contains("io_requestEvidenceValid"))
  }
}
