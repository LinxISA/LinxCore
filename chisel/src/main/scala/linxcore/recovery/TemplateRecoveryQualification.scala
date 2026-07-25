package linxcore.recovery

import chisel3._
import chisel3.util.Valid
import linxcore.bctrl.TemplateParentIdentity
import linxcore.common.InterfaceParams

class TemplateRecoveryQualificationIO(val p: InterfaceParams) extends Bundle {
  val activeValid = Input(Bool())
  val activeIdentity = Input(new TemplateParentIdentity(p))
  val completed = Input(Bool())
  val committed = Input(Bool())

  val recovery = Flipped(Valid(new TemplateParentIdentity(p)))
  val recoveryKillsActive = Input(Bool())
  val sourceResolved = Input(Bool())
  val restartValid = Input(Bool())
  val globalClear = Input(Bool())

  val recoverySelf = Output(Bool())
  val externalPreCompletionKill = Output(Bool())
  val ignoredRecovery = Output(Bool())
  val illegalDiscardAttempt = Output(Bool())
  val selfRestartPending = Output(Bool())
  val selfRestartQualified = Output(Bool())
  val preserveActive = Output(Bool())
  val clearActive = Output(Bool())
}

class TemplateRecoveryQualification(val p: InterfaceParams) extends Module {
  val io = IO(new TemplateRecoveryQualificationIO(p))

  private val retainedPhase = io.completed || io.committed
  private val matchingRecovery =
    io.recovery.valid && TemplateParentIdentity.sameKey(io.activeIdentity, io.recovery.bits)

  private val recoverySelf =
    io.activeValid && retainedPhase && matchingRecovery && !io.globalClear
  private val externalPreCompletionKill =
    io.activeValid &&
      !retainedPhase &&
      io.recovery.valid &&
      !matchingRecovery &&
      io.recoveryKillsActive &&
      !io.globalClear
  private val illegalDiscardAttempt =
    io.activeValid &&
      retainedPhase &&
      io.recovery.valid &&
      !matchingRecovery &&
      io.recoveryKillsActive &&
      !io.globalClear
  private val ignoredRecovery =
    io.activeValid &&
      io.recovery.valid &&
      !recoverySelf &&
      !externalPreCompletionKill &&
      !illegalDiscardAttempt &&
      !io.globalClear

  private val selfRestartPendingReg = RegInit(false.B)
  private val pendingThisCycle =
    io.activeValid && (selfRestartPendingReg || recoverySelf) && !io.globalClear
  private val selfRestartQualified = pendingThisCycle && io.restartValid

  when(io.globalClear || !io.activeValid || selfRestartQualified) {
    selfRestartPendingReg := false.B
  }.elsewhen(recoverySelf) {
    selfRestartPendingReg := true.B
  }

  io.recoverySelf := recoverySelf
  io.externalPreCompletionKill := externalPreCompletionKill
  io.ignoredRecovery := ignoredRecovery
  io.illegalDiscardAttempt := illegalDiscardAttempt
  io.selfRestartPending := pendingThisCycle
  io.selfRestartQualified := selfRestartQualified
  io.clearActive := io.globalClear || externalPreCompletionKill
  io.preserveActive := io.activeValid && !io.clearActive

  // Source resolution must not erase a retained self-restart token.
  dontTouch(io.sourceResolved)
}
