package linxcore.lsu

import chisel3._
import chisel3.util.log2Ceil

class LoadReplayReturnPipeResidencyCandidateIO(val returnPipeCount: Int = 1) extends Bundle {
  private val returnPipeIndexWidth = math.max(1, log2Ceil(returnPipeCount))

  val enable = Input(Bool())
  val flush = Input(Bool())
  val insertCandidateValid = Input(Bool())
  val insertValid = Input(Bool())
  val liveEnable = Input(Bool())
  val isVectorMachine = Input(Bool())
  val selectedPipeIndex = Input(UInt(returnPipeIndexWidth.W))
  val selectedPipeOccupied = Input(Bool())

  val candidateValid = Output(Bool())
  val residencyArmed = Output(Bool())
  val residencyWriteValid = Output(Bool())
  val liveEnabled = Output(Bool())
  val targetIsAgu = Output(Bool())
  val targetIsLda = Output(Bool())
  val targetPipeIndex = Output(UInt(returnPipeIndexWidth.W))
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoInsertCandidate = Output(Bool())
  val blockedByNoInsertValid = Output(Bool())
  val blockedByLiveDisabled = Output(Bool())
  val blockedByPipeOccupied = Output(Bool())
}

class LoadReplayReturnPipeResidencyCandidate(val returnPipeCount: Int = 1) extends Module {
  require(returnPipeCount > 0, "returnPipeCount must be positive")

  private val returnPipeIndexWidth = math.max(1, log2Ceil(returnPipeCount))

  val io = IO(new LoadReplayReturnPipeResidencyCandidateIO(returnPipeCount))

  val candidateValid = io.enable && !io.flush && io.insertCandidateValid
  val residencyArmed = candidateValid && io.insertValid
  val pipeWritable = residencyArmed && !io.selectedPipeOccupied
  val residencyWriteValid = pipeWritable && io.liveEnable

  io.candidateValid := candidateValid
  io.residencyArmed := residencyArmed
  io.residencyWriteValid := residencyWriteValid
  io.liveEnabled := io.liveEnable
  io.targetIsAgu := candidateValid && io.isVectorMachine
  io.targetIsLda := candidateValid && !io.isVectorMachine
  io.targetPipeIndex := Mux(candidateValid, io.selectedPipeIndex, 0.U(returnPipeIndexWidth.W))
  io.blockedByDisabled := !io.enable && io.insertCandidateValid
  io.blockedByFlush := io.enable && io.flush && io.insertCandidateValid
  io.blockedByNoInsertCandidate := io.enable && !io.flush && !io.insertCandidateValid
  io.blockedByNoInsertValid := candidateValid && !io.insertValid
  io.blockedByLiveDisabled := pipeWritable && !io.liveEnable
  io.blockedByPipeOccupied := candidateValid && io.selectedPipeOccupied
}
