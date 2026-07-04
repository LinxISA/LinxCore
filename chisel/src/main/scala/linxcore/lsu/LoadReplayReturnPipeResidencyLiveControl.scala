package linxcore.lsu

import chisel3._

class LoadReplayReturnPipeResidencyLiveControlIO extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val requestEnable = Input(Bool())
  val insertCandidateValid = Input(Bool())
  val insertValid = Input(Bool())
  val selectedPipeOccupied = Input(Bool())

  val active = Output(Bool())
  val requestActive = Output(Bool())
  val residencyEvidenceValid = Output(Bool())
  val liveEnable = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByRequestDisabled = Output(Bool())
  val blockedByNoInsertCandidate = Output(Bool())
  val blockedByNoInsertValid = Output(Bool())
  val blockedByPipeOccupied = Output(Bool())
  val blockedByNoEvidence = Output(Bool())
}

class LoadReplayReturnPipeResidencyLiveControl extends Module {
  val io = IO(new LoadReplayReturnPipeResidencyLiveControlIO)

  val active = io.enable && !io.flush
  val requestActive = active && io.requestEnable
  val rawEvidence = io.insertCandidateValid && io.insertValid && !io.selectedPipeOccupied
  val residencyEvidenceValid = active && rawEvidence
  val liveEnable = requestActive && rawEvidence

  io.active := active
  io.requestActive := requestActive
  io.residencyEvidenceValid := residencyEvidenceValid
  io.liveEnable := liveEnable
  io.blockedByDisabled := !io.enable && (io.requestEnable || rawEvidence)
  io.blockedByFlush := io.enable && io.flush && (io.requestEnable || rawEvidence)
  io.blockedByRequestDisabled := active && !io.requestEnable && rawEvidence
  io.blockedByNoInsertCandidate := requestActive && !io.insertCandidateValid
  io.blockedByNoInsertValid := requestActive && io.insertCandidateValid && !io.insertValid
  io.blockedByPipeOccupied := requestActive && io.insertCandidateValid && io.insertValid && io.selectedPipeOccupied
  io.blockedByNoEvidence := requestActive && !rawEvidence
}
