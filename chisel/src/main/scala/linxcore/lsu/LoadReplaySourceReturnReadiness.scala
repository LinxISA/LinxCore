package linxcore.lsu

import chisel3._

class LoadReplaySourceReturnReadinessIO extends Bundle {
  val enable = Input(Bool())
  val launchValid = Input(Bool())
  val baseDataReady = Input(Bool())
  val storeSnapshotReady = Input(Bool())
  val externalScbPending = Input(Bool())
  val externalScbReturned = Input(Bool())

  val candidateValid = Output(Bool())
  val storeSourceReturned = Output(Bool())
  val scbSourceReturned = Output(Bool())
  val sourceReturned = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByNoCandidate = Output(Bool())
  val blockedByBaseData = Output(Bool())
  val blockedByStoreSnapshot = Output(Bool())
  val blockedByScb = Output(Bool())
}

class LoadReplaySourceReturnReadiness extends Module {
  val io = IO(new LoadReplaySourceReturnReadinessIO)

  val candidateValid = io.enable && io.launchValid
  val storeSourceReturned = candidateValid && io.baseDataReady && io.storeSnapshotReady
  val scbSourceReturned = candidateValid && (!io.externalScbPending || io.externalScbReturned)
  val sourceReturned = storeSourceReturned && scbSourceReturned

  io.candidateValid := candidateValid
  io.storeSourceReturned := storeSourceReturned
  io.scbSourceReturned := scbSourceReturned
  io.sourceReturned := sourceReturned
  io.blockedByDisabled := !io.enable && io.launchValid
  io.blockedByNoCandidate := io.enable && !io.launchValid
  io.blockedByBaseData := candidateValid && !io.baseDataReady
  io.blockedByStoreSnapshot := candidateValid && io.baseDataReady && !io.storeSnapshotReady
  io.blockedByScb :=
    candidateValid && io.baseDataReady && io.storeSnapshotReady && io.externalScbPending && !io.externalScbReturned
}
