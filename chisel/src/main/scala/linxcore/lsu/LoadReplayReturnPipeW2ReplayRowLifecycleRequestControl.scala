package linxcore.lsu

import chisel3._

class LoadReplayReturnPipeW2ReplayRowLifecycleRequestControlIO extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val atomicRequestActive = Input(Bool())
  val rowFillCandidateValid = Input(Bool())
  val lifecycleRowClearReady = Input(Bool())

  val active = Output(Bool())
  val requestCandidate = Output(Bool())
  val lifecycleClearRequestEnable = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByRequestInactive = Output(Bool())
  val blockedByNoRowFillCandidate = Output(Bool())
  val blockedByNoLifecycleRow = Output(Bool())
}

class LoadReplayReturnPipeW2ReplayRowLifecycleRequestControl extends Module {
  val io = IO(new LoadReplayReturnPipeW2ReplayRowLifecycleRequestControlIO)

  val active = io.enable && !io.flush
  val observedIntent =
    io.atomicRequestActive || io.rowFillCandidateValid || io.lifecycleRowClearReady
  val requestCandidate = active && io.atomicRequestActive && io.rowFillCandidateValid

  io.active := active
  io.requestCandidate := requestCandidate
  io.lifecycleClearRequestEnable := requestCandidate
  io.blockedByDisabled := !io.enable && observedIntent
  io.blockedByFlush := io.enable && io.flush && observedIntent
  io.blockedByRequestInactive :=
    active && !io.atomicRequestActive && (io.rowFillCandidateValid || io.lifecycleRowClearReady)
  io.blockedByNoRowFillCandidate :=
    active && io.atomicRequestActive && !io.rowFillCandidateValid
  io.blockedByNoLifecycleRow := requestCandidate && !io.lifecycleRowClearReady
}
