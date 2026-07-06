package linxcore.lsu

import chisel3._

class LoadReplayReturnPipeW2RetireRecordRowFillEnableControlIO extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val rowFillRequested = Input(Bool())
  val retireRecordValid = Input(Bool())
  val lifecycleRowClearReady = Input(Bool())
  val rowFillCandidateValid = Input(Bool())

  val active = Output(Bool())
  val requestEvidenceValid = Output(Bool())
  val candidateAligned = Output(Bool())
  val rowFillEnable = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByRequestDisabled = Output(Bool())
  val blockedByNoLifecycleRow = Output(Bool())
  val blockedByNoRowFillCandidate = Output(Bool())
  val invalidRowFillCandidateWithoutEvidence = Output(Bool())
}

class LoadReplayReturnPipeW2RetireRecordRowFillEnableControl extends Module {
  val io = IO(new LoadReplayReturnPipeW2RetireRecordRowFillEnableControlIO)

  val active = io.enable && !io.flush
  val observedIntent =
    io.rowFillRequested ||
      io.retireRecordValid ||
      io.lifecycleRowClearReady ||
      io.rowFillCandidateValid
  val requestEvidenceValid =
    active &&
      io.retireRecordValid &&
      io.lifecycleRowClearReady
  val candidateAligned = requestEvidenceValid && io.rowFillCandidateValid
  val rowFillEnable = io.rowFillRequested && candidateAligned

  io.active := active
  io.requestEvidenceValid := requestEvidenceValid
  io.candidateAligned := candidateAligned
  io.rowFillEnable := rowFillEnable
  io.blockedByDisabled := !io.enable && observedIntent
  io.blockedByFlush := io.enable && io.flush && observedIntent
  io.blockedByRequestDisabled := candidateAligned && !io.rowFillRequested
  io.blockedByNoLifecycleRow := active && io.retireRecordValid && !io.lifecycleRowClearReady
  io.blockedByNoRowFillCandidate := requestEvidenceValid && !io.rowFillCandidateValid
  io.invalidRowFillCandidateWithoutEvidence := io.rowFillCandidateValid && !requestEvidenceValid
}
