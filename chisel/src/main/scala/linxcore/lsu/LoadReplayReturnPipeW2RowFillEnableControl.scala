package linxcore.lsu

import chisel3._

class LoadReplayReturnPipeW2RowFillEnableControlIO extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val rowFillRequested = Input(Bool())
  val rowFillCandidateValid = Input(Bool())
  val sideEffectFireComplete = Input(Bool())
  val clearCommitReady = Input(Bool())
  val liveClearReady = Input(Bool())
  val replayRowLifecycleReady = Input(Bool())

  val active = Output(Bool())
  val requestActive = Output(Bool())
  val candidateValid = Output(Bool())
  val atomicPrerequisitesReady = Output(Bool())
  val rowFillEnable = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByRequestDisabled = Output(Bool())
  val blockedByNoCandidate = Output(Bool())
  val blockedByNoSideEffectCommit = Output(Bool())
  val blockedByNoClearCommit = Output(Bool())
  val blockedByLiveClearDisabled = Output(Bool())
  val blockedByNoReplayRowLifecycle = Output(Bool())
}

class LoadReplayReturnPipeW2RowFillEnableControl extends Module {
  val io = IO(new LoadReplayReturnPipeW2RowFillEnableControlIO)

  val active = io.enable && !io.flush
  val requestActive = active && io.rowFillRequested
  val candidateValid = active && io.rowFillCandidateValid
  val atomicPrerequisitesReady =
    candidateValid &&
      io.sideEffectFireComplete &&
      io.clearCommitReady &&
      io.liveClearReady &&
      io.replayRowLifecycleReady
  val rowFillEnable = requestActive && atomicPrerequisitesReady

  io.active := active
  io.requestActive := requestActive
  io.candidateValid := candidateValid
  io.atomicPrerequisitesReady := atomicPrerequisitesReady
  io.rowFillEnable := rowFillEnable
  io.blockedByDisabled := !io.enable && (io.rowFillRequested || io.rowFillCandidateValid)
  io.blockedByFlush := io.enable && io.flush && (io.rowFillRequested || io.rowFillCandidateValid)
  io.blockedByRequestDisabled := active && !io.rowFillRequested && io.rowFillCandidateValid
  io.blockedByNoCandidate := requestActive && !io.rowFillCandidateValid
  io.blockedByNoSideEffectCommit := candidateValid && !io.sideEffectFireComplete
  io.blockedByNoClearCommit := candidateValid && io.sideEffectFireComplete && !io.clearCommitReady
  io.blockedByLiveClearDisabled :=
    candidateValid && io.sideEffectFireComplete && io.clearCommitReady && !io.liveClearReady
  io.blockedByNoReplayRowLifecycle :=
    candidateValid && io.sideEffectFireComplete && io.clearCommitReady && io.liveClearReady &&
      !io.replayRowLifecycleReady
}
