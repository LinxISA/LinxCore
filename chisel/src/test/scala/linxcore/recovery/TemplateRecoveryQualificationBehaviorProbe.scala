package linxcore.recovery

import circt.stage.ChiselStage
import chisel3._
import linxcore.bctrl.TemplateParentIdentity
import linxcore.common.InterfaceParams
import org.scalatest.funsuite.AnyFunSuite

class TemplateRecoveryQualificationProbeIdentity(val p: InterfaceParams) extends Bundle {
  val generation = UInt(16.W)
  val stid = UInt(p.threadIdWidth.W)
  val bidValid = Bool()
  val bidWrap = Bool()
  val bidValue = UInt(p.robIndexWidth.W)
  val gidValid = Bool()
  val gidWrap = Bool()
  val gidValue = UInt(p.robIndexWidth.W)
  val ridValid = Bool()
  val ridWrap = Bool()
  val ridValue = UInt(p.robIndexWidth.W)
  val robSlot = UInt(p.robIndexWidth.W)
}

class TemplateRecoveryQualificationBehaviorProbeIO(val p: InterfaceParams) extends Bundle {
  val activeValid = Input(Bool())
  val activeIdentity = Input(new TemplateRecoveryQualificationProbeIdentity(p))
  val completed = Input(Bool())
  val committed = Input(Bool())

  val recoveryValid = Input(Bool())
  val recoveryIdentity = Input(new TemplateRecoveryQualificationProbeIdentity(p))
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

class TemplateRecoveryQualificationBehaviorProbe extends Module {
  private val p = InterfaceParams()
  val io = IO(new TemplateRecoveryQualificationBehaviorProbeIO(p))

  private def assignIdentity(
      destination: TemplateParentIdentity,
      source: TemplateRecoveryQualificationProbeIdentity): Unit = {
    destination := 0.U.asTypeOf(destination)
    destination.generation := source.generation
    destination.stid := source.stid
    destination.bid.valid := source.bidValid
    destination.bid.wrap := source.bidWrap
    destination.bid.value := source.bidValue
    destination.gid.valid := source.gidValid
    destination.gid.wrap := source.gidWrap
    destination.gid.value := source.gidValue
    destination.rid.valid := source.ridValid
    destination.rid.wrap := source.ridWrap
    destination.rid.value := source.ridValue
    destination.robSlot := source.robSlot
  }

  private val qualification = Module(new TemplateRecoveryQualification(p))
  qualification.io.activeValid := io.activeValid
  assignIdentity(qualification.io.activeIdentity, io.activeIdentity)
  qualification.io.completed := io.completed
  qualification.io.committed := io.committed
  qualification.io.recovery.valid := io.recoveryValid
  assignIdentity(qualification.io.recovery.bits, io.recoveryIdentity)
  qualification.io.recoveryKillsActive := io.recoveryKillsActive
  qualification.io.sourceResolved := io.sourceResolved
  qualification.io.restartValid := io.restartValid
  qualification.io.globalClear := io.globalClear

  io.recoverySelf := qualification.io.recoverySelf
  io.externalPreCompletionKill := qualification.io.externalPreCompletionKill
  io.ignoredRecovery := qualification.io.ignoredRecovery
  io.illegalDiscardAttempt := qualification.io.illegalDiscardAttempt
  io.selfRestartPending := qualification.io.selfRestartPending
  io.selfRestartQualified := qualification.io.selfRestartQualified
  io.preserveActive := qualification.io.preserveActive
  io.clearActive := qualification.io.clearActive
}

object EmitTemplateRecoveryQualificationBehaviorProbe extends App {
  val targetDir = args.sliding(2, 1).collectFirst {
    case Array("--target-dir", dir) => dir
  }.getOrElse("generated/chisel-verilog/recovery-template-qualification-behavior-probe")

  ChiselStage.emitSystemVerilogFile(
    new TemplateRecoveryQualificationBehaviorProbe,
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info"),
    args = Array("--target-dir", targetDir))
}

class TemplateRecoveryQualificationBehaviorProbeSpec extends AnyFunSuite {
  test("probe binds the real phase-qualified template recovery owner") {
    val p = InterfaceParams()
    val io = new TemplateRecoveryQualificationBehaviorProbeIO(p)

    assert(io.activeIdentity.generation.getWidth == 16)
    assert(io.activeIdentity.stid.getWidth == p.threadIdWidth)
    assert(io.activeIdentity.bidValid.getWidth == 1)
    assert(io.activeIdentity.bidWrap.getWidth == 1)
    assert(io.activeIdentity.bidValue.getWidth == p.robIndexWidth)
    assert(io.activeIdentity.gidValid.getWidth == 1)
    assert(io.activeIdentity.gidWrap.getWidth == 1)
    assert(io.activeIdentity.gidValue.getWidth == p.robIndexWidth)
    assert(io.activeIdentity.ridValid.getWidth == 1)
    assert(io.activeIdentity.ridWrap.getWidth == 1)
    assert(io.activeIdentity.ridValue.getWidth == p.robIndexWidth)
    assert(io.activeIdentity.robSlot.getWidth == p.robIndexWidth)
    assert(io.completed.getWidth == 1)
    assert(io.committed.getWidth == 1)
    assert(io.activeValid.getWidth == 1)
    assert(io.recoverySelf.getWidth == 1)
    assert(io.selfRestartPending.getWidth == 1)
    assert(io.selfRestartQualified.getWidth == 1)
  }
}
