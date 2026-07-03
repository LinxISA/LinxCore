package linxcore.lsu

import chisel3._

class LoadReplayReturnPipeW2ReplayRowLifecycleCommitPermitIO extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val lifecycleClearSelected = Input(Bool())
  val rowFillEnable = Input(Bool())
  val rowFillCandidateValid = Input(Bool())
  val lifecycleRowClearReady = Input(Bool())

  val active = Output(Bool())
  val commitCandidate = Output(Bool())
  val rowFillPermit = Output(Bool())
  val lifecycleClearCommitEnable = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoSelection = Output(Bool())
  val blockedByNoRowFillEnable = Output(Bool())
  val blockedByNoRowFillCandidate = Output(Bool())
  val blockedByNoLifecycleRow = Output(Bool())
  val invalidRowFillWithoutSelection = Output(Bool())
}

class LoadReplayReturnPipeW2ReplayRowLifecycleCommitPermit extends Module {
  val io = IO(new LoadReplayReturnPipeW2ReplayRowLifecycleCommitPermitIO)

  val active = io.enable && !io.flush
  val observedIntent =
    io.lifecycleClearSelected || io.rowFillEnable || io.rowFillCandidateValid || io.lifecycleRowClearReady
  val commitCandidate = active && io.lifecycleClearSelected
  val rowFillPermit = active && io.rowFillEnable
  val lifecycleClearCommitEnable = commitCandidate && io.rowFillEnable

  io.active := active
  io.commitCandidate := commitCandidate
  io.rowFillPermit := rowFillPermit
  io.lifecycleClearCommitEnable := lifecycleClearCommitEnable
  io.blockedByDisabled := !io.enable && observedIntent
  io.blockedByFlush := io.enable && io.flush && observedIntent
  io.blockedByNoSelection := active && io.rowFillEnable && !io.lifecycleClearSelected
  io.blockedByNoRowFillEnable := commitCandidate && !io.rowFillEnable
  io.blockedByNoRowFillCandidate := commitCandidate && !io.rowFillCandidateValid
  io.blockedByNoLifecycleRow :=
    active && !io.lifecycleRowClearReady && (io.lifecycleClearSelected || io.rowFillEnable)
  io.invalidRowFillWithoutSelection := io.rowFillEnable && !io.lifecycleClearSelected
}
