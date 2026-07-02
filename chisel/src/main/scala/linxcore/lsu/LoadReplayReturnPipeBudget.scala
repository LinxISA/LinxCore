package linxcore.lsu

import chisel3._

class LoadReplayReturnPipeBudgetIO extends Bundle {
  val enable = Input(Bool())
  val launchValid = Input(Bool())
  val sourcesReturned = Input(Bool())
  val pipeBudgetEnable = Input(Bool())

  val candidateValid = Output(Bool())
  val pipeBudgetAvailable = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByNoCandidate = Output(Bool())
  val blockedBySources = Output(Bool())
  val blockedByBudgetDisabled = Output(Bool())
}

class LoadReplayReturnPipeBudget extends Module {
  val io = IO(new LoadReplayReturnPipeBudgetIO)

  val candidateValid = io.enable && io.launchValid

  io.candidateValid := candidateValid
  io.pipeBudgetAvailable := io.enable && io.pipeBudgetEnable
  io.blockedByDisabled := !io.enable && io.launchValid
  io.blockedByNoCandidate := io.enable && !io.launchValid
  io.blockedBySources := candidateValid && !io.sourcesReturned
  io.blockedByBudgetDisabled := candidateValid && io.sourcesReturned && !io.pipeBudgetEnable
}
