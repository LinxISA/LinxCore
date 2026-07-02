package linxcore.lsu

import chisel3._

class LoadReplayReturnPipePermitIO(val returnPipeCount: Int = 1) extends Bundle {
  val enable = Input(Bool())
  val launchValid = Input(Bool())
  val sourcesReturned = Input(Bool())
  val pipeBudgetAvailable = Input(Bool())

  val candidateValid = Output(Bool())
  val pipeAvailableMask = Output(UInt(returnPipeCount.W))
  val permitValid = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByNoCandidate = Output(Bool())
  val blockedBySources = Output(Bool())
  val blockedByPipeBudget = Output(Bool())
}

class LoadReplayReturnPipePermit(val returnPipeCount: Int = 1) extends Module {
  require(returnPipeCount > 0, "returnPipeCount must be positive")

  val io = IO(new LoadReplayReturnPipePermitIO(returnPipeCount))

  val candidateValid = io.enable && io.launchValid
  val permitValid = candidateValid && io.sourcesReturned && io.pipeBudgetAvailable

  io.candidateValid := candidateValid
  io.pipeAvailableMask := Mux(permitValid, 1.U(returnPipeCount.W), 0.U(returnPipeCount.W))
  io.permitValid := permitValid
  io.blockedByDisabled := !io.enable && io.launchValid
  io.blockedByNoCandidate := io.enable && !io.launchValid
  io.blockedBySources := candidateValid && !io.sourcesReturned
  io.blockedByPipeBudget := candidateValid && io.sourcesReturned && !io.pipeBudgetAvailable
}
