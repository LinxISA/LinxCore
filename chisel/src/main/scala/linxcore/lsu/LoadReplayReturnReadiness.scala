package linxcore.lsu

import chisel3._
import chisel3.util.log2Ceil

class LoadReplayReturnReadinessIO(val returnPipeCount: Int = 1) extends Bundle {
  private val returnPipeIndexWidth = math.max(1, log2Ceil(returnPipeCount))

  val enable = Input(Bool())
  val launchValid = Input(Bool())
  val sourcesReturned = Input(Bool())
  val returnPipeAvailable = Input(Bool())
  val returnPipeIndex = Input(UInt(returnPipeIndexWidth.W))

  val candidateValid = Output(Bool())
  val returnReady = Output(Bool())
  val selectedPipeIndex = Output(UInt(returnPipeIndexWidth.W))
  val blockedByDisabled = Output(Bool())
  val blockedByNoCandidate = Output(Bool())
  val blockedBySources = Output(Bool())
  val blockedByReturnPipe = Output(Bool())
}

class LoadReplayReturnReadiness(val returnPipeCount: Int = 1) extends Module {
  require(returnPipeCount > 0, "returnPipeCount must be positive")

  private val returnPipeIndexWidth = math.max(1, log2Ceil(returnPipeCount))

  val io = IO(new LoadReplayReturnReadinessIO(returnPipeCount))

  val candidateValid = io.enable && io.launchValid
  val returnReady = candidateValid && io.sourcesReturned && io.returnPipeAvailable

  io.candidateValid := candidateValid
  io.returnReady := returnReady
  io.selectedPipeIndex := Mux(returnReady, io.returnPipeIndex, 0.U(returnPipeIndexWidth.W))
  io.blockedByDisabled := !io.enable && io.launchValid
  io.blockedByNoCandidate := io.enable && !io.launchValid
  io.blockedBySources := candidateValid && !io.sourcesReturned
  io.blockedByReturnPipe := candidateValid && io.sourcesReturned && !io.returnPipeAvailable
}
