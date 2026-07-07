package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2RetireRecordModelOrderProofReference {
  final case class Result(
      active: Boolean,
      recordCandidate: Boolean,
      returnSideEffectsReady: Boolean,
      retireClearEvidenceReady: Boolean,
      returnThenRetireReady: Boolean,
      fallbackOwnerModelOrderEligible: Boolean,
      blockedByNoRecord: Boolean,
      blockedByMissingReturnSideEffect: Boolean,
      blockedByMissingRetireClearEvidence: Boolean,
      blockedByOwnerDisabled: Boolean)

  def step(
      enable: Boolean,
      flush: Boolean,
      recordValid: Boolean,
      robReturnSideEffectValid: Boolean,
      rfReturnSideEffectValid: Boolean,
      wakeupReturnSideEffectValid: Boolean,
      lifecycleEvidenceProviderValid: Boolean,
      lifecycleClearFallbackValid: Boolean,
      sideEffectOwnerEnable: Boolean): Result = {
    val active = enable && !flush
    val recordCandidate = active && recordValid
    val returnReady =
      recordCandidate &&
        robReturnSideEffectValid &&
        rfReturnSideEffectValid &&
        wakeupReturnSideEffectValid
    val retireClearReady =
      recordCandidate &&
        lifecycleEvidenceProviderValid &&
        lifecycleClearFallbackValid
    val orderedReady = returnReady && retireClearReady

    Result(
      active = active,
      recordCandidate = recordCandidate,
      returnSideEffectsReady = returnReady,
      retireClearEvidenceReady = retireClearReady,
      returnThenRetireReady = orderedReady,
      fallbackOwnerModelOrderEligible = orderedReady && sideEffectOwnerEnable,
      blockedByNoRecord = active && !recordValid,
      blockedByMissingReturnSideEffect =
        recordCandidate &&
          !(robReturnSideEffectValid && rfReturnSideEffectValid && wakeupReturnSideEffectValid),
      blockedByMissingRetireClearEvidence =
        recordCandidate && !(lifecycleEvidenceProviderValid && lifecycleClearFallbackValid),
      blockedByOwnerDisabled = orderedReady && !sideEffectOwnerEnable)
  }
}

class LoadReplayReturnPipeW2RetireRecordModelOrderProofSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2RetireRecordModelOrderProofReference._

  test("proves retained return side effects and retire-clear evidence under owner enable") {
    val result = step(
      enable = true,
      flush = false,
      recordValid = true,
      robReturnSideEffectValid = true,
      rfReturnSideEffectValid = true,
      wakeupReturnSideEffectValid = true,
      lifecycleEvidenceProviderValid = true,
      lifecycleClearFallbackValid = true,
      sideEffectOwnerEnable = true)

    assert(result.recordCandidate)
    assert(result.returnSideEffectsReady)
    assert(result.retireClearEvidenceReady)
    assert(result.returnThenRetireReady)
    assert(result.fallbackOwnerModelOrderEligible)
    assert(!result.blockedByOwnerDisabled)
  }

  test("reports missing return side-effect evidence separately from retire-clear evidence") {
    val missingReturn = step(
      enable = true,
      flush = false,
      recordValid = true,
      robReturnSideEffectValid = true,
      rfReturnSideEffectValid = false,
      wakeupReturnSideEffectValid = true,
      lifecycleEvidenceProviderValid = true,
      lifecycleClearFallbackValid = true,
      sideEffectOwnerEnable = true)
    val missingRetireClear = step(
      enable = true,
      flush = false,
      recordValid = true,
      robReturnSideEffectValid = true,
      rfReturnSideEffectValid = true,
      wakeupReturnSideEffectValid = true,
      lifecycleEvidenceProviderValid = false,
      lifecycleClearFallbackValid = true,
      sideEffectOwnerEnable = true)

    assert(missingReturn.blockedByMissingReturnSideEffect)
    assert(!missingReturn.returnThenRetireReady)
    assert(!missingReturn.blockedByMissingRetireClearEvidence)
    assert(missingRetireClear.blockedByMissingRetireClearEvidence)
    assert(!missingRetireClear.returnThenRetireReady)
    assert(!missingRetireClear.blockedByMissingReturnSideEffect)
  }

  test("requires record presence and side-effect owner enable") {
    val noRecord = step(
      enable = true,
      flush = false,
      recordValid = false,
      robReturnSideEffectValid = true,
      rfReturnSideEffectValid = true,
      wakeupReturnSideEffectValid = true,
      lifecycleEvidenceProviderValid = true,
      lifecycleClearFallbackValid = true,
      sideEffectOwnerEnable = true)
    val disabledOwner = step(
      enable = true,
      flush = false,
      recordValid = true,
      robReturnSideEffectValid = true,
      rfReturnSideEffectValid = true,
      wakeupReturnSideEffectValid = true,
      lifecycleEvidenceProviderValid = true,
      lifecycleClearFallbackValid = true,
      sideEffectOwnerEnable = false)

    assert(noRecord.blockedByNoRecord)
    assert(!noRecord.recordCandidate)
    assert(disabledOwner.returnThenRetireReady)
    assert(disabledOwner.blockedByOwnerDisabled)
    assert(!disabledOwner.fallbackOwnerModelOrderEligible)
  }

  test("flush suppresses model-order proof") {
    val result = step(
      enable = true,
      flush = true,
      recordValid = true,
      robReturnSideEffectValid = true,
      rfReturnSideEffectValid = true,
      wakeupReturnSideEffectValid = true,
      lifecycleEvidenceProviderValid = true,
      lifecycleClearFallbackValid = true,
      sideEffectOwnerEnable = true)

    assert(!result.active)
    assert(!result.recordCandidate)
    assert(!result.returnThenRetireReady)
  }

  test("Chisel LoadReplayReturnPipeW2RetireRecordModelOrderProof elaborates") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPipeW2RetireRecordModelOrderProof)

    assert(sv.contains("module LoadReplayReturnPipeW2RetireRecordModelOrderProof"))
    assert(sv.contains("io_returnThenRetireReady"))
    assert(sv.contains("io_fallbackOwnerModelOrderEligible"))
  }
}
