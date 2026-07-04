package linxcore.lsu

import chisel3._

class LoadReplaySourceReturnScbLiveControlIO extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val requestEnable = Input(Bool())
  val scbPendingEvidence = Input(Bool())
  val scbReturnedEvidence = Input(Bool())

  val active = Output(Bool())
  val requestActive = Output(Bool())
  val scbEvidenceValid = Output(Bool())
  val externalScbPending = Output(Bool())
  val externalScbReturned = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByRequestDisabled = Output(Bool())
  val blockedByNoPending = Output(Bool())
  val blockedByScbReturn = Output(Bool())
}

class LoadReplaySourceReturnScbLiveControl extends Module {
  val io = IO(new LoadReplaySourceReturnScbLiveControlIO)

  val active = io.enable && !io.flush
  val requestActive = active && io.requestEnable
  val rawEvidence = io.scbPendingEvidence || io.scbReturnedEvidence
  val externalScbPending = requestActive && io.scbPendingEvidence
  val externalScbReturned = externalScbPending && io.scbReturnedEvidence

  io.active := active
  io.requestActive := requestActive
  io.scbEvidenceValid := active && rawEvidence
  io.externalScbPending := externalScbPending
  io.externalScbReturned := externalScbReturned
  io.blockedByDisabled := !io.enable && (io.requestEnable || rawEvidence)
  io.blockedByFlush := io.enable && io.flush && (io.requestEnable || rawEvidence)
  io.blockedByRequestDisabled := active && !io.requestEnable && rawEvidence
  io.blockedByNoPending := requestActive && io.scbReturnedEvidence && !io.scbPendingEvidence
  io.blockedByScbReturn := requestActive && io.scbPendingEvidence && !io.scbReturnedEvidence
}
