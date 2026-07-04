package linxcore.lsu

import chisel3._

class LoadReplaySourceReturnStoreSnapshotReadyControlIO extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val requestEnable = Input(Bool())
  val legacySnapshotReady = Input(Bool())
  val snapshotRequired = Input(Bool())
  val snapshotValid = Input(Bool())

  val active = Output(Bool())
  val requestActive = Output(Bool())
  val snapshotEvidenceValid = Output(Bool())
  val legacyReady = Output(Bool())
  val liveReady = Output(Bool())
  val storeSnapshotReady = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByRequestDisabled = Output(Bool())
  val blockedByLegacySnapshot = Output(Bool())
  val blockedBySnapshot = Output(Bool())
}

class LoadReplaySourceReturnStoreSnapshotReadyControl extends Module {
  val io = IO(new LoadReplaySourceReturnStoreSnapshotReadyControlIO)

  val active = io.enable && !io.flush
  val requestActive = active && io.requestEnable
  val rawEvidence = io.snapshotRequired || io.snapshotValid
  val liveReady = requestActive && (!io.snapshotRequired || io.snapshotValid)

  io.active := active
  io.requestActive := requestActive
  io.snapshotEvidenceValid := active && rawEvidence
  io.legacyReady := io.legacySnapshotReady
  io.liveReady := liveReady
  io.storeSnapshotReady := Mux(io.requestEnable, liveReady, io.legacySnapshotReady)
  io.blockedByDisabled := !io.enable && (io.requestEnable || rawEvidence)
  io.blockedByFlush := io.enable && io.flush && (io.requestEnable || rawEvidence)
  io.blockedByRequestDisabled := active && !io.requestEnable && rawEvidence
  io.blockedByLegacySnapshot := active && !io.requestEnable && !io.legacySnapshotReady
  io.blockedBySnapshot := requestActive && io.snapshotRequired && !io.snapshotValid
}
