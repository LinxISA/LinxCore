package linxcore.lsu

import chisel3._

class LoadReplayReturnPipeW2RetireRecordLifecycleRequestProbeIO extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val retireRecordValid = Input(Bool())
  val lifecycleRowClearReady = Input(Bool())
  val atomicRequestActive = Input(Bool())
  val rowFillCandidateValid = Input(Bool())
  val rowFillEnable = Input(Bool())

  val active = Output(Bool())
  val requestCandidate = Output(Bool())
  val livePromotionCandidate = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoLifecycleRow = Output(Bool())
  val blockedByNoAtomicRequest = Output(Bool())
  val blockedByNoRowFillCandidate = Output(Bool())
  val blockedByNoRowFillEnable = Output(Bool())
  val invalidRowFillEnableWithoutRequest = Output(Bool())
}

class LoadReplayReturnPipeW2RetireRecordLifecycleRequestProbe extends Module {
  val io = IO(new LoadReplayReturnPipeW2RetireRecordLifecycleRequestProbeIO)

  val active = io.enable && !io.flush
  val observedIntent =
    io.retireRecordValid ||
      io.lifecycleRowClearReady ||
      io.atomicRequestActive ||
      io.rowFillCandidateValid ||
      io.rowFillEnable
  val requestCandidate = active && io.retireRecordValid && io.lifecycleRowClearReady
  val livePromotionCandidate =
    requestCandidate &&
      io.atomicRequestActive &&
      io.rowFillCandidateValid &&
      io.rowFillEnable

  io.active := active
  io.requestCandidate := requestCandidate
  io.livePromotionCandidate := livePromotionCandidate
  io.blockedByDisabled := !io.enable && observedIntent
  io.blockedByFlush := io.enable && io.flush && observedIntent
  io.blockedByNoLifecycleRow := active && io.retireRecordValid && !io.lifecycleRowClearReady
  io.blockedByNoAtomicRequest := requestCandidate && !io.atomicRequestActive
  io.blockedByNoRowFillCandidate := requestCandidate && io.atomicRequestActive && !io.rowFillCandidateValid
  io.blockedByNoRowFillEnable :=
    requestCandidate && io.atomicRequestActive && io.rowFillCandidateValid && !io.rowFillEnable
  io.invalidRowFillEnableWithoutRequest := io.rowFillEnable && !requestCandidate
}
