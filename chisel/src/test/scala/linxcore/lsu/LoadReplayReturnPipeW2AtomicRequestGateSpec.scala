package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2AtomicRequestGateReference {
  final case class Result(
      active: Boolean,
      policyRequestEnableCandidate: Boolean,
      gatedRequestEnable: Boolean,
      requestActive: Boolean,
      requestEvidenceValid: Boolean,
      sideEffectLiveRequested: Boolean,
      promotionRequested: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByModeDisabled: Boolean,
      blockedByPolicy: Boolean,
      blockedByPolicyNoEvidence: Boolean,
      blockedByPolicyNoSideEffectSink: Boolean,
      blockedByPolicyNoClearCommit: Boolean,
      blockedByPolicyNoRowFillCandidate: Boolean,
      blockedByPolicyNoLifecycleRow: Boolean,
      blockedByPolicyNoRequiredSideEffect: Boolean,
      invalidSideEffectWithoutSlot: Boolean,
      invalidClearWithoutSlot: Boolean,
      invalidRowFillWithoutSlot: Boolean,
      invalidRequestWithoutEvidence: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      liveModeEnable: Boolean,
      slotOccupied: Boolean,
      sideEffectCandidateValid: Boolean,
      sideEffectRequiredMask: Int,
      sideEffectSinksReady: Boolean,
      clearIntent: Boolean,
      clearCommitReady: Boolean,
      rowFillCandidateValid: Boolean,
      lifecycleRowClearReady: Boolean,
      writeCandidateValid: Boolean,
      requestClearIntent: Boolean,
      requestWriteCandidateValid: Boolean): Result = {
    val policy = LoadReplayReturnPipeW2AtomicRequestEnablePolicyReference(
      enable = enable,
      flush = flush,
      slotOccupied = slotOccupied,
      sideEffectCandidateValid = sideEffectCandidateValid,
      sideEffectRequiredMask = sideEffectRequiredMask,
      sideEffectSinksReady = sideEffectSinksReady,
      clearIntent = clearIntent,
      clearCommitReady = clearCommitReady,
      rowFillCandidateValid = rowFillCandidateValid,
      lifecycleRowClearReady = lifecycleRowClearReady,
      writeCandidateValid = writeCandidateValid)
    val gatedRequestEnable = liveModeEnable && policy.requestEnableCandidate
    val request = LoadReplayReturnPipeW2AtomicLiveRequestControlReference(
      enable = enable,
      flush = flush,
      requestEnable = gatedRequestEnable,
      sideEffectCandidateValid = sideEffectCandidateValid,
      sideEffectRequiredMask = sideEffectRequiredMask,
      clearIntent = requestClearIntent,
      writeCandidateValid = requestWriteCandidateValid)

    Result(
      active = policy.active,
      policyRequestEnableCandidate = policy.requestEnableCandidate,
      gatedRequestEnable = gatedRequestEnable,
      requestActive = request.requestActive,
      requestEvidenceValid = request.requestEvidenceValid,
      sideEffectLiveRequested = request.sideEffectLiveRequested,
      promotionRequested = request.promotionRequested,
      blockedByDisabled = policy.blockedByDisabled,
      blockedByFlush = policy.blockedByFlush,
      blockedByModeDisabled = policy.requestEnableCandidate && !liveModeEnable,
      blockedByPolicy = liveModeEnable && !policy.requestEnableCandidate,
      blockedByPolicyNoEvidence = policy.blockedByNoEvidence,
      blockedByPolicyNoSideEffectSink = policy.blockedByNoSideEffectSink,
      blockedByPolicyNoClearCommit = policy.blockedByNoClearCommit,
      blockedByPolicyNoRowFillCandidate = policy.blockedByNoRowFillCandidate,
      blockedByPolicyNoLifecycleRow = policy.blockedByNoLifecycleRow,
      blockedByPolicyNoRequiredSideEffect = policy.blockedByNoRequiredSideEffect,
      invalidSideEffectWithoutSlot = policy.invalidSideEffectWithoutSlot,
      invalidClearWithoutSlot = policy.invalidClearWithoutSlot,
      invalidRowFillWithoutSlot = policy.invalidRowFillWithoutSlot,
      invalidRequestWithoutEvidence = request.blockedByNoEvidence)
  }
}

class LoadReplayReturnPipeW2AtomicRequestGateSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2AtomicRequestGateReference._

  test("passes the policy-approved request through when live mode is enabled") {
    val result = LoadReplayReturnPipeW2AtomicRequestGateReference(
      enable = true,
      flush = false,
      liveModeEnable = true,
      slotOccupied = true,
      sideEffectCandidateValid = true,
      sideEffectRequiredMask = 0x7,
      sideEffectSinksReady = true,
      clearIntent = true,
      clearCommitReady = true,
      rowFillCandidateValid = true,
      lifecycleRowClearReady = true,
      writeCandidateValid = true,
      requestClearIntent = true,
      requestWriteCandidateValid = true)

    assert(result.active)
    assert(result.policyRequestEnableCandidate)
    assert(result.gatedRequestEnable)
    assert(result.requestActive)
    assert(result.requestEvidenceValid)
    assert(result.sideEffectLiveRequested)
    assert(result.promotionRequested)
    assert(!result.blockedByModeDisabled)
    assert(!result.blockedByPolicy)
  }

  test("keeps a policy-approved request dormant while live mode is disabled") {
    val result = LoadReplayReturnPipeW2AtomicRequestGateReference(
      enable = true,
      flush = false,
      liveModeEnable = false,
      slotOccupied = false,
      sideEffectCandidateValid = false,
      sideEffectRequiredMask = 0x0,
      sideEffectSinksReady = true,
      clearIntent = false,
      clearCommitReady = false,
      rowFillCandidateValid = false,
      lifecycleRowClearReady = false,
      writeCandidateValid = true,
      requestClearIntent = false,
      requestWriteCandidateValid = true)

    assert(result.policyRequestEnableCandidate)
    assert(!result.gatedRequestEnable)
    assert(!result.requestActive)
    assert(result.requestEvidenceValid)
    assert(result.blockedByModeDisabled)
  }

  test("blocks live mode when the policy is missing prerequisites") {
    val result = LoadReplayReturnPipeW2AtomicRequestGateReference(
      enable = true,
      flush = false,
      liveModeEnable = true,
      slotOccupied = true,
      sideEffectCandidateValid = true,
      sideEffectRequiredMask = 0x1,
      sideEffectSinksReady = false,
      clearIntent = true,
      clearCommitReady = true,
      rowFillCandidateValid = true,
      lifecycleRowClearReady = true,
      writeCandidateValid = false,
      requestClearIntent = true,
      requestWriteCandidateValid = false)

    assert(!result.policyRequestEnableCandidate)
    assert(!result.gatedRequestEnable)
    assert(!result.requestActive)
    assert(result.blockedByPolicy)
    assert(result.blockedByPolicyNoSideEffectSink)
    assert(!result.invalidRequestWithoutEvidence)
  }

  test("surfaces malformed resident evidence before the live request owner") {
    val result = LoadReplayReturnPipeW2AtomicRequestGateReference(
      enable = true,
      flush = false,
      liveModeEnable = true,
      slotOccupied = false,
      sideEffectCandidateValid = true,
      sideEffectRequiredMask = 0x1,
      sideEffectSinksReady = true,
      clearIntent = true,
      clearCommitReady = true,
      rowFillCandidateValid = true,
      lifecycleRowClearReady = true,
      writeCandidateValid = false,
      requestClearIntent = true,
      requestWriteCandidateValid = false)

    assert(result.blockedByPolicy)
    assert(result.blockedByPolicyNoEvidence)
    assert(result.invalidSideEffectWithoutSlot)
    assert(result.invalidClearWithoutSlot)
    assert(result.invalidRowFillWithoutSlot)
    assert(!result.gatedRequestEnable)
  }

  test("keeps raw request evidence visible when policy prerequisites are tied dormant") {
    val result = LoadReplayReturnPipeW2AtomicRequestGateReference(
      enable = true,
      flush = false,
      liveModeEnable = false,
      slotOccupied = true,
      sideEffectCandidateValid = false,
      sideEffectRequiredMask = 0x0,
      sideEffectSinksReady = false,
      clearIntent = false,
      clearCommitReady = false,
      rowFillCandidateValid = false,
      lifecycleRowClearReady = false,
      writeCandidateValid = false,
      requestClearIntent = true,
      requestWriteCandidateValid = true)

    assert(!result.policyRequestEnableCandidate)
    assert(!result.gatedRequestEnable)
    assert(!result.requestActive)
    assert(result.requestEvidenceValid)
    assert(!result.invalidRequestWithoutEvidence)
  }

  test("Chisel LoadReplayReturnPipeW2AtomicRequestGate elaborates the composite boundary") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPipeW2AtomicRequestGate)

    assert(sv.contains("module LoadReplayReturnPipeW2AtomicRequestGate"))
    assert(sv.contains("LoadReplayReturnPipeW2AtomicRequestEnablePolicy"))
    assert(sv.contains("LoadReplayReturnPipeW2AtomicLiveRequestControl"))
    assert(sv.contains("io_gatedRequestEnable"))
    assert(sv.contains("io_invalidRequestWithoutEvidence"))
  }
}
