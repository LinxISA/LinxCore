package linxcore.lsu

import chisel3._
import chisel3.util.{PriorityEncoder, log2Ceil}

class LoadReplayReturnPipeSelectIO(val returnPipeCount: Int = 1) extends Bundle {
  private val returnPipeIndexWidth = math.max(1, log2Ceil(returnPipeCount))

  val enable = Input(Bool())
  val launchValid = Input(Bool())
  val sourcesReturned = Input(Bool())
  val pipeAvailableMask = Input(UInt(returnPipeCount.W))

  val candidateValid = Output(Bool())
  val pipeAvailable = Output(Bool())
  val selectedPipeIndex = Output(UInt(returnPipeIndexWidth.W))
  val blockedByDisabled = Output(Bool())
  val blockedByNoCandidate = Output(Bool())
  val blockedBySources = Output(Bool())
  val blockedByNoPipe = Output(Bool())
}

class LoadReplayReturnPipeSelect(val returnPipeCount: Int = 1) extends Module {
  require(returnPipeCount > 0, "returnPipeCount must be positive")

  private val returnPipeIndexWidth = math.max(1, log2Ceil(returnPipeCount))

  val io = IO(new LoadReplayReturnPipeSelectIO(returnPipeCount))

  val candidateValid = io.enable && io.launchValid
  val anyPipeAvailable = io.pipeAvailableMask.orR
  val selectedPipeIndex =
    if (returnPipeCount == 1) 0.U(returnPipeIndexWidth.W)
    else PriorityEncoder(io.pipeAvailableMask)
  val pipeAvailable = candidateValid && io.sourcesReturned && anyPipeAvailable

  io.candidateValid := candidateValid
  io.pipeAvailable := pipeAvailable
  io.selectedPipeIndex := Mux(pipeAvailable, selectedPipeIndex, 0.U(returnPipeIndexWidth.W))
  io.blockedByDisabled := !io.enable && io.launchValid
  io.blockedByNoCandidate := io.enable && !io.launchValid
  io.blockedBySources := candidateValid && !io.sourcesReturned
  io.blockedByNoPipe := candidateValid && io.sourcesReturned && !anyPipeAvailable
}
