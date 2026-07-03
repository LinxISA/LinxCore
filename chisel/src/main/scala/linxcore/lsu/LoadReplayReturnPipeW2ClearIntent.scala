package linxcore.lsu

import chisel3._

class LoadReplayReturnPipeW2ClearIntentIO extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val slotOccupied = Input(Bool())
  val completionClearSlot = Input(Bool())
  val completionPermitted = Input(Bool())
  val fireComplete = Input(Bool())
  val liveClearEnable = Input(Bool())

  val candidateValid = Output(Bool())
  val preClearEligible = Output(Bool())
  val permitEligible = Output(Bool())
  val postFireEligible = Output(Bool())
  val completionPermitMatchesClear = Output(Bool())
  val fireCompleteMatchesClear = Output(Bool())
  val clearIntent = Output(Bool())
  val liveClear = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoSlot = Output(Bool())
  val blockedByCompletionClear = Output(Bool())
  val blockedByCompletionPermit = Output(Bool())
  val blockedByFireComplete = Output(Bool())
  val blockedByLiveClearDisabled = Output(Bool())
  val blockedByCompletionPermitMismatch = Output(Bool())
  val blockedByFireCompleteMismatch = Output(Bool())
  val invalidEvidenceWithoutSlot = Output(Bool())
}

class LoadReplayReturnPipeW2ClearIntent extends Module {
  val io = IO(new LoadReplayReturnPipeW2ClearIntentIO)

  val active = io.enable && !io.flush
  val candidateValid = active && io.slotOccupied
  val anyClearEvidence = io.completionClearSlot || io.completionPermitted || io.fireComplete
  val completionPermitMatchesClear = io.completionClearSlot === io.completionPermitted
  val fireCompleteMatchesClear = io.completionClearSlot === io.fireComplete
  val clearIntent =
    candidateValid && io.completionClearSlot && io.completionPermitted && io.fireComplete

  io.candidateValid := candidateValid
  io.preClearEligible := candidateValid && io.completionClearSlot
  io.permitEligible := candidateValid && io.completionPermitted
  io.postFireEligible := candidateValid && io.fireComplete
  io.completionPermitMatchesClear := completionPermitMatchesClear
  io.fireCompleteMatchesClear := fireCompleteMatchesClear
  io.clearIntent := clearIntent
  io.liveClear := clearIntent && io.liveClearEnable
  io.blockedByDisabled := !io.enable && io.slotOccupied
  io.blockedByFlush := io.enable && io.flush && io.slotOccupied
  io.blockedByNoSlot := active && !io.slotOccupied
  io.blockedByCompletionClear := candidateValid && !io.completionClearSlot
  io.blockedByCompletionPermit := candidateValid && !io.completionPermitted
  io.blockedByFireComplete := candidateValid && !io.fireComplete
  io.blockedByLiveClearDisabled := clearIntent && !io.liveClearEnable
  io.blockedByCompletionPermitMismatch := candidateValid && !completionPermitMatchesClear
  io.blockedByFireCompleteMismatch := candidateValid && !fireCompleteMatchesClear
  io.invalidEvidenceWithoutSlot := active && !io.slotOccupied && anyClearEvidence
}
