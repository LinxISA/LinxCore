package linxcore.lsu

import chisel3._

class LoadReplayReturnPipeW2AtomicLiveRequestControlIO extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val requestEnable = Input(Bool())
  val sideEffectCandidateValid = Input(Bool())
  val sideEffectRequiredMask = Input(UInt(3.W))
  val clearIntent = Input(Bool())
  val writeCandidateValid = Input(Bool())

  val active = Output(Bool())
  val requestActive = Output(Bool())
  val requestEvidenceValid = Output(Bool())
  val sideEffectLiveRequested = Output(Bool())
  val promotionRequested = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByRequestDisabled = Output(Bool())
  val blockedByNoEvidence = Output(Bool())
}

class LoadReplayReturnPipeW2AtomicLiveRequestControl extends Module {
  val io = IO(new LoadReplayReturnPipeW2AtomicLiveRequestControlIO)

  val active = io.enable && !io.flush
  val requestActive = active && io.requestEnable
  val rawEvidence =
    (io.sideEffectCandidateValid && io.sideEffectRequiredMask.orR) ||
      io.clearIntent ||
      io.writeCandidateValid
  val requestEvidenceValid = active && rawEvidence

  io.active := active
  io.requestActive := requestActive
  io.requestEvidenceValid := requestEvidenceValid
  io.sideEffectLiveRequested := requestActive
  io.promotionRequested := requestActive
  io.blockedByDisabled := !io.enable && (io.requestEnable || rawEvidence)
  io.blockedByFlush := io.enable && io.flush && (io.requestEnable || rawEvidence)
  io.blockedByRequestDisabled := active && !io.requestEnable && rawEvidence
  io.blockedByNoEvidence := requestActive && !rawEvidence
}
