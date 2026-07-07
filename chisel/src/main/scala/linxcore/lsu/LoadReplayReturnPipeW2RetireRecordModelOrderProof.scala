package linxcore.lsu

import chisel3._

class LoadReplayReturnPipeW2RetireRecordModelOrderProofIO extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val recordValid = Input(Bool())
  val robReturnSideEffectValid = Input(Bool())
  val rfReturnSideEffectValid = Input(Bool())
  val wakeupReturnSideEffectValid = Input(Bool())
  val lifecycleEvidenceProviderValid = Input(Bool())
  val lifecycleClearFallbackValid = Input(Bool())
  val sideEffectOwnerEnable = Input(Bool())

  val active = Output(Bool())
  val recordCandidate = Output(Bool())
  val returnSideEffectsReady = Output(Bool())
  val retireClearEvidenceReady = Output(Bool())
  val returnThenRetireReady = Output(Bool())
  val fallbackOwnerModelOrderEligible = Output(Bool())
  val blockedByNoRecord = Output(Bool())
  val blockedByMissingReturnSideEffect = Output(Bool())
  val blockedByMissingRetireClearEvidence = Output(Bool())
  val blockedByOwnerDisabled = Output(Bool())
}

class LoadReplayReturnPipeW2RetireRecordModelOrderProof extends Module {
  val io = IO(new LoadReplayReturnPipeW2RetireRecordModelOrderProofIO)

  val active = io.enable && !io.flush
  val recordCandidate = active && io.recordValid
  val returnSideEffectsReady =
    recordCandidate &&
      io.robReturnSideEffectValid &&
      io.rfReturnSideEffectValid &&
      io.wakeupReturnSideEffectValid
  val retireClearEvidenceReady =
    recordCandidate &&
      io.lifecycleEvidenceProviderValid &&
      io.lifecycleClearFallbackValid
  val returnThenRetireReady = returnSideEffectsReady && retireClearEvidenceReady
  val fallbackOwnerModelOrderEligible = returnThenRetireReady && io.sideEffectOwnerEnable

  io.active := active
  io.recordCandidate := recordCandidate
  io.returnSideEffectsReady := returnSideEffectsReady
  io.retireClearEvidenceReady := retireClearEvidenceReady
  io.returnThenRetireReady := returnThenRetireReady
  io.fallbackOwnerModelOrderEligible := fallbackOwnerModelOrderEligible
  io.blockedByNoRecord := active && !io.recordValid
  io.blockedByMissingReturnSideEffect :=
    recordCandidate &&
      !(io.robReturnSideEffectValid &&
        io.rfReturnSideEffectValid &&
        io.wakeupReturnSideEffectValid)
  io.blockedByMissingRetireClearEvidence :=
    recordCandidate &&
      !(io.lifecycleEvidenceProviderValid &&
        io.lifecycleClearFallbackValid)
  io.blockedByOwnerDisabled := returnThenRetireReady && !io.sideEffectOwnerEnable
}
