package linxcore.lsu

import chisel3._
import chisel3.util.log2Ceil

class LoadReplayReturnPipeResidencyAdvanceCandidateIO(val returnPipeCount: Int = 1)
    extends Bundle {
  private val returnPipeIndexWidth = math.max(1, log2Ceil(returnPipeCount))

  val enable = Input(Bool())
  val flush = Input(Bool())
  val advanceEnable = Input(Bool())
  val slotOccupied = Input(Bool())
  val slotTargetIsAgu = Input(Bool())
  val slotTargetIsLda = Input(Bool())
  val slotPipeIndex = Input(UInt(returnPipeIndexWidth.W))

  val candidateValid = Output(Bool())
  val advanceValid = Output(Bool())
  val clearSlot = Output(Bool())
  val targetIsAgu = Output(Bool())
  val targetIsLda = Output(Bool())
  val targetPipeIndex = Output(UInt(returnPipeIndexWidth.W))
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoSlot = Output(Bool())
  val blockedByAdvanceDisabled = Output(Bool())
  val blockedByInvalidTarget = Output(Bool())
}

class LoadReplayReturnPipeResidencyAdvanceCandidate(val returnPipeCount: Int = 1)
    extends Module {
  require(returnPipeCount > 0, "returnPipeCount must be positive")

  private val returnPipeIndexWidth = math.max(1, log2Ceil(returnPipeCount))

  val io = IO(new LoadReplayReturnPipeResidencyAdvanceCandidateIO(returnPipeCount))

  val candidateValid = io.enable && !io.flush && io.slotOccupied
  val targetValid = io.slotTargetIsAgu ^ io.slotTargetIsLda
  val advanceValid = candidateValid && targetValid && io.advanceEnable

  io.candidateValid := candidateValid
  io.advanceValid := advanceValid
  io.clearSlot := advanceValid
  io.targetIsAgu := candidateValid && targetValid && io.slotTargetIsAgu
  io.targetIsLda := candidateValid && targetValid && io.slotTargetIsLda
  io.targetPipeIndex := Mux(candidateValid && targetValid, io.slotPipeIndex, 0.U(returnPipeIndexWidth.W))
  io.blockedByDisabled := !io.enable && io.slotOccupied
  io.blockedByFlush := io.enable && io.flush && io.slotOccupied
  io.blockedByNoSlot := io.enable && !io.flush && !io.slotOccupied
  io.blockedByAdvanceDisabled := candidateValid && targetValid && !io.advanceEnable
  io.blockedByInvalidTarget := candidateValid && !targetValid
}
