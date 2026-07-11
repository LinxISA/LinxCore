package linxcore.lsu

import chisel3._

class MDBConflictTransactionControlIO extends Bundle {
  val enable = Input(Bool())
  val candidateValid = Input(Bool())
  val recordRequired = Input(Bool())
  val waitPlanRequired = Input(Bool())
  val recoveryRequired = Input(Bool())
  val recordReady = Input(Bool())
  val waitPlanReady = Input(Bool())
  val recoveryReady = Input(Bool())

  val candidateReady = Output(Bool())
  val accepted = Output(Bool())
  val recordValid = Output(Bool())
  val waitPlanValid = Output(Bool())
  val recoveryValid = Output(Bool())
}

/** Commits all side effects derived from one MDB conflict decision atomically. */
class MDBConflictTransactionControl extends Module {
  val io = IO(new MDBConflictTransactionControlIO)

  val requiredSinksReady =
    (!io.recordRequired || io.recordReady) &&
      (!io.waitPlanRequired || io.waitPlanReady) &&
      (!io.recoveryRequired || io.recoveryReady)

  io.candidateReady := io.enable && requiredSinksReady
  io.accepted := io.candidateValid && io.candidateReady
  io.recordValid := io.accepted && io.recordRequired
  io.waitPlanValid := io.accepted && io.waitPlanRequired
  io.recoveryValid := io.accepted && io.recoveryRequired
}
