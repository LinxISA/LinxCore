package linxcore.lsu

import chisel3._

class LoadReplayReturnFinalMetadataCandidateIO extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val tloadCompletionValid = Input(Bool())

  val candidateValid = Output(Bool())
  val isLoadReturnMarked = Output(Bool())
  val loadBranchResolveCalled = Output(Bool())
  val loadBranchResolveSideEffectValid = Output(Bool())
  val pipeCycleSidebandValid = Output(Bool())
  val readyForPipeInsert = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoTloadCompletion = Output(Bool())
}

class LoadReplayReturnFinalMetadataCandidate extends Module {
  val io = IO(new LoadReplayReturnFinalMetadataCandidateIO)

  val candidateValid = io.enable && !io.flush && io.tloadCompletionValid

  io.candidateValid := candidateValid
  io.isLoadReturnMarked := candidateValid
  io.loadBranchResolveCalled := candidateValid
  io.loadBranchResolveSideEffectValid := false.B
  io.pipeCycleSidebandValid := candidateValid
  io.readyForPipeInsert := candidateValid
  io.blockedByDisabled := !io.enable && io.tloadCompletionValid
  io.blockedByFlush := io.enable && io.flush && io.tloadCompletionValid
  io.blockedByNoTloadCompletion := io.enable && !io.flush && !io.tloadCompletionValid
}
