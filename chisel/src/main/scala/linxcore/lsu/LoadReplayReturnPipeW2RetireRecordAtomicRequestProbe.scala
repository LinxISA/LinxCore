package linxcore.lsu

import chisel3._

class LoadReplayReturnPipeW2RetireRecordAtomicRequestProbeIO extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val retireRecordValid = Input(Bool())
  val lifecycleRowClearReady = Input(Bool())
  val rowFillCandidateValid = Input(Bool())
  val rowFillEnable = Input(Bool())

  val active = Output(Bool())
  val requestEvidenceValid = Output(Bool())
  val rowFillCandidateAligned = Output(Bool())
  val rowFillEnableAligned = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoLifecycleRow = Output(Bool())
  val blockedByNoRowFillCandidate = Output(Bool())
  val blockedByNoRowFillEnable = Output(Bool())
  val invalidRowFillEnableWithoutEvidence = Output(Bool())
}

class LoadReplayReturnPipeW2RetireRecordAtomicRequestProbe extends Module {
  val io = IO(new LoadReplayReturnPipeW2RetireRecordAtomicRequestProbeIO)

  val active = io.enable && !io.flush
  val observedIntent =
    io.retireRecordValid ||
      io.lifecycleRowClearReady ||
      io.rowFillCandidateValid ||
      io.rowFillEnable
  val requestEvidenceValid = active && io.retireRecordValid && io.lifecycleRowClearReady
  val rowFillCandidateAligned = requestEvidenceValid && io.rowFillCandidateValid
  val rowFillEnableAligned = rowFillCandidateAligned && io.rowFillEnable

  io.active := active
  io.requestEvidenceValid := requestEvidenceValid
  io.rowFillCandidateAligned := rowFillCandidateAligned
  io.rowFillEnableAligned := rowFillEnableAligned
  io.blockedByDisabled := !io.enable && observedIntent
  io.blockedByFlush := io.enable && io.flush && observedIntent
  io.blockedByNoLifecycleRow := active && io.retireRecordValid && !io.lifecycleRowClearReady
  io.blockedByNoRowFillCandidate := requestEvidenceValid && !io.rowFillCandidateValid
  io.blockedByNoRowFillEnable := rowFillCandidateAligned && !io.rowFillEnable
  io.invalidRowFillEnableWithoutEvidence := io.rowFillEnable && !requestEvidenceValid
}
