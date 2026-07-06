package linxcore.lsu

import chisel3._

class LoadReplayReturnPipeW2AtomicRequestGateIO extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val liveModeEnable = Input(Bool())
  val slotOccupied = Input(Bool())
  val sideEffectCandidateValid = Input(Bool())
  val sideEffectRequiredMask = Input(UInt(3.W))
  val sideEffectSinksReady = Input(Bool())
  val clearIntent = Input(Bool())
  val clearCommitReady = Input(Bool())
  val rowFillCandidateValid = Input(Bool())
  val lifecycleRowClearReady = Input(Bool())
  val writeCandidateValid = Input(Bool())

  val active = Output(Bool())
  val policyRequestEnableCandidate = Output(Bool())
  val gatedRequestEnable = Output(Bool())
  val requestActive = Output(Bool())
  val requestEvidenceValid = Output(Bool())
  val sideEffectLiveRequested = Output(Bool())
  val promotionRequested = Output(Bool())
  val blockedByModeDisabled = Output(Bool())
  val blockedByPolicy = Output(Bool())
  val blockedByPolicyNoEvidence = Output(Bool())
  val blockedByPolicyNoSideEffectSink = Output(Bool())
  val blockedByPolicyNoClearCommit = Output(Bool())
  val blockedByPolicyNoRowFillCandidate = Output(Bool())
  val blockedByPolicyNoLifecycleRow = Output(Bool())
  val blockedByPolicyNoRequiredSideEffect = Output(Bool())
  val invalidSideEffectWithoutSlot = Output(Bool())
  val invalidClearWithoutSlot = Output(Bool())
  val invalidRowFillWithoutSlot = Output(Bool())
  val invalidRequestWithoutEvidence = Output(Bool())
}

class LoadReplayReturnPipeW2AtomicRequestGate extends Module {
  val io = IO(new LoadReplayReturnPipeW2AtomicRequestGateIO)

  val policy = Module(new LoadReplayReturnPipeW2AtomicRequestEnablePolicy)
  policy.io.enable := io.enable
  policy.io.flush := io.flush
  policy.io.slotOccupied := io.slotOccupied
  policy.io.sideEffectCandidateValid := io.sideEffectCandidateValid
  policy.io.sideEffectRequiredMask := io.sideEffectRequiredMask
  policy.io.sideEffectSinksReady := io.sideEffectSinksReady
  policy.io.clearIntent := io.clearIntent
  policy.io.clearCommitReady := io.clearCommitReady
  policy.io.rowFillCandidateValid := io.rowFillCandidateValid
  policy.io.lifecycleRowClearReady := io.lifecycleRowClearReady
  policy.io.writeCandidateValid := io.writeCandidateValid

  val gatedRequestEnable = io.liveModeEnable && policy.io.requestEnableCandidate

  val request = Module(new LoadReplayReturnPipeW2AtomicLiveRequestControl)
  request.io.enable := io.enable
  request.io.flush := io.flush
  request.io.requestEnable := gatedRequestEnable
  request.io.sideEffectCandidateValid := io.sideEffectCandidateValid
  request.io.sideEffectRequiredMask := io.sideEffectRequiredMask
  request.io.clearIntent := io.clearIntent
  request.io.writeCandidateValid := io.writeCandidateValid

  io.active := policy.io.active
  io.policyRequestEnableCandidate := policy.io.requestEnableCandidate
  io.gatedRequestEnable := gatedRequestEnable
  io.requestActive := request.io.requestActive
  io.requestEvidenceValid := request.io.requestEvidenceValid
  io.sideEffectLiveRequested := request.io.sideEffectLiveRequested
  io.promotionRequested := request.io.promotionRequested
  io.blockedByModeDisabled := policy.io.requestEnableCandidate && !io.liveModeEnable
  io.blockedByPolicy := io.liveModeEnable && !policy.io.requestEnableCandidate
  io.blockedByPolicyNoEvidence := policy.io.blockedByNoEvidence
  io.blockedByPolicyNoSideEffectSink := policy.io.blockedByNoSideEffectSink
  io.blockedByPolicyNoClearCommit := policy.io.blockedByNoClearCommit
  io.blockedByPolicyNoRowFillCandidate := policy.io.blockedByNoRowFillCandidate
  io.blockedByPolicyNoLifecycleRow := policy.io.blockedByNoLifecycleRow
  io.blockedByPolicyNoRequiredSideEffect := policy.io.blockedByNoRequiredSideEffect
  io.invalidSideEffectWithoutSlot := policy.io.invalidSideEffectWithoutSlot
  io.invalidClearWithoutSlot := policy.io.invalidClearWithoutSlot
  io.invalidRowFillWithoutSlot := policy.io.invalidRowFillWithoutSlot
  io.invalidRequestWithoutEvidence := request.io.blockedByNoEvidence
}
