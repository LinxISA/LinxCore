package linxcore.lsu

import chisel3._

class LoadReplayReturnPipeW2AtomicRequestEnablePolicyIO extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
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
  val sideEffectEvidence = Output(Bool())
  val residentEvidence = Output(Bool())
  val emptyRefillEvidence = Output(Bool())
  val sideEffectPrerequisitesReady = Output(Bool())
  val residentCommitPrerequisitesReady = Output(Bool())
  val residentRequestEnableCandidate = Output(Bool())
  val emptyRefillRequestEnableCandidate = Output(Bool())
  val requestEnableCandidate = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoEvidence = Output(Bool())
  val blockedByNoSideEffectSink = Output(Bool())
  val blockedByNoClearCommit = Output(Bool())
  val blockedByNoRowFillCandidate = Output(Bool())
  val blockedByNoLifecycleRow = Output(Bool())
  val blockedByNoRequiredSideEffect = Output(Bool())
  val invalidSideEffectWithoutSlot = Output(Bool())
  val invalidClearWithoutSlot = Output(Bool())
  val invalidRowFillWithoutSlot = Output(Bool())
}

class LoadReplayReturnPipeW2AtomicRequestEnablePolicy extends Module {
  val io = IO(new LoadReplayReturnPipeW2AtomicRequestEnablePolicyIO)

  val active = io.enable && !io.flush
  val sideEffectEvidence = io.sideEffectCandidateValid && io.sideEffectRequiredMask.orR
  val residentEvidence =
    io.slotOccupied &&
      (sideEffectEvidence || io.clearIntent || io.rowFillCandidateValid || io.writeCandidateValid)
  val emptyRefillEvidence = !io.slotOccupied && io.writeCandidateValid
  val observedEvidence = residentEvidence || emptyRefillEvidence

  val sideEffectPrerequisitesReady = !sideEffectEvidence || io.sideEffectSinksReady
  val residentCommitPrerequisitesReady =
    io.clearCommitReady && io.rowFillCandidateValid && io.lifecycleRowClearReady
  val residentRequestEnableCandidate =
    active && residentEvidence && sideEffectPrerequisitesReady && residentCommitPrerequisitesReady
  val emptyRefillRequestEnableCandidate = active && emptyRefillEvidence
  val requestEnableCandidate = residentRequestEnableCandidate || emptyRefillRequestEnableCandidate

  io.active := active
  io.sideEffectEvidence := sideEffectEvidence
  io.residentEvidence := residentEvidence
  io.emptyRefillEvidence := emptyRefillEvidence
  io.sideEffectPrerequisitesReady := sideEffectPrerequisitesReady
  io.residentCommitPrerequisitesReady := residentCommitPrerequisitesReady
  io.residentRequestEnableCandidate := residentRequestEnableCandidate
  io.emptyRefillRequestEnableCandidate := emptyRefillRequestEnableCandidate
  io.requestEnableCandidate := requestEnableCandidate
  io.blockedByDisabled := !io.enable && observedEvidence
  io.blockedByFlush := io.enable && io.flush && observedEvidence
  io.blockedByNoEvidence := active && !observedEvidence
  io.blockedByNoSideEffectSink := active && residentEvidence && sideEffectEvidence && !io.sideEffectSinksReady
  io.blockedByNoClearCommit :=
    active && residentEvidence && sideEffectPrerequisitesReady && !io.clearCommitReady
  io.blockedByNoRowFillCandidate :=
    active && residentEvidence && sideEffectPrerequisitesReady && io.clearCommitReady &&
      !io.rowFillCandidateValid
  io.blockedByNoLifecycleRow :=
    active && residentEvidence && sideEffectPrerequisitesReady && io.clearCommitReady &&
      io.rowFillCandidateValid && !io.lifecycleRowClearReady
  io.blockedByNoRequiredSideEffect :=
    active && io.slotOccupied && io.sideEffectCandidateValid && !io.sideEffectRequiredMask.orR &&
      !io.clearIntent && !io.rowFillCandidateValid && !io.writeCandidateValid
  io.invalidSideEffectWithoutSlot := active && !io.slotOccupied && io.sideEffectCandidateValid
  io.invalidClearWithoutSlot := active && !io.slotOccupied && io.clearIntent
  io.invalidRowFillWithoutSlot := active && !io.slotOccupied && io.rowFillCandidateValid
}
