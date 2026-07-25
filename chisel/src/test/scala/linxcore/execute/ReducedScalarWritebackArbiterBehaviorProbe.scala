package linxcore.execute

import circt.stage.ChiselStage
import chisel3._
import org.scalatest.funsuite.AnyFunSuite

class ReducedScalarWritebackArbiterBehaviorProbeIO extends Bundle {
  val executeValid = Input(Bool())
  val executeTag = Input(UInt(6.W))
  val executeData = Input(UInt(64.W))

  val replayEnable = Input(Bool())
  val replayValid = Input(Bool())
  val replayTag = Input(UInt(6.W))
  val replayData = Input(UInt(64.W))

  val serviceEnable = Input(Bool())
  val serviceValid = Input(Bool())
  val serviceTag = Input(UInt(6.W))
  val serviceData = Input(UInt(64.W))

  val templateEnable = Input(Bool())
  val templateValid = Input(Bool())
  val templateTag = Input(UInt(6.W))
  val templateData = Input(UInt(64.W))

  val rfPortReady = Input(Bool())

  val writeValid = Output(Bool())
  val writeTag = Output(UInt(6.W))
  val writeData = Output(UInt(64.W))
  val writeFire = Output(Bool())
  val templateAdvance = Output(Bool())

  val selectedExecute = Output(Bool())
  val selectedReplay = Output(Bool())
  val selectedService = Output(Bool())
  val selectedTemplate = Output(Bool())
  val replayBlockedByDisabled = Output(Bool())
  val replayBlockedByExecute = Output(Bool())
  val serviceBlockedByDisabled = Output(Bool())
  val serviceBlockedByExecute = Output(Bool())
  val serviceBlockedByReplay = Output(Bool())
  val templateBlockedByDisabled = Output(Bool())
  val templateBlockedByExecute = Output(Bool())
  val templateBlockedByReplay = Output(Bool())
  val templateBlockedByService = Output(Bool())
}

class ReducedScalarWritebackArbiterBehaviorProbe extends Module {
  val io = IO(new ReducedScalarWritebackArbiterBehaviorProbeIO)

  private val arbiter = Module(new ReducedScalarWritebackArbiter())

  arbiter.io.executeValid := io.executeValid
  arbiter.io.executeTag := io.executeTag
  arbiter.io.executeData := io.executeData

  arbiter.io.replayEnable := io.replayEnable
  arbiter.io.replayValid := io.replayValid
  arbiter.io.replayTag := io.replayTag
  arbiter.io.replayData := io.replayData

  arbiter.io.serviceEnable := io.serviceEnable
  arbiter.io.serviceValid := io.serviceValid
  arbiter.io.serviceTag := io.serviceTag
  arbiter.io.serviceData := io.serviceData

  arbiter.io.templateEnable := io.templateEnable
  arbiter.io.templateValid := io.templateValid
  arbiter.io.templateTag := io.templateTag
  arbiter.io.templateData := io.templateData

  private val selectedTemplate = arbiter.io.selectedTemplate

  io.writeValid := arbiter.io.writeValid
  io.writeTag := arbiter.io.writeTag
  io.writeData := arbiter.io.writeData
  io.writeFire := arbiter.io.writeValid && io.rfPortReady
  io.templateAdvance := selectedTemplate && arbiter.io.writeValid && io.rfPortReady

  io.selectedExecute := arbiter.io.selectedExecute
  io.selectedReplay := arbiter.io.selectedReplay
  io.selectedService := arbiter.io.selectedService
  io.selectedTemplate := selectedTemplate
  io.replayBlockedByDisabled := arbiter.io.replayBlockedByDisabled
  io.replayBlockedByExecute := arbiter.io.replayBlockedByExecute
  io.serviceBlockedByDisabled := arbiter.io.serviceBlockedByDisabled
  io.serviceBlockedByExecute := arbiter.io.serviceBlockedByExecute
  io.serviceBlockedByReplay := arbiter.io.serviceBlockedByReplay
  io.templateBlockedByDisabled := arbiter.io.templateBlockedByDisabled
  io.templateBlockedByExecute := arbiter.io.templateBlockedByExecute
  io.templateBlockedByReplay := arbiter.io.templateBlockedByReplay
  io.templateBlockedByService := arbiter.io.templateBlockedByService
}

object EmitReducedScalarWritebackArbiterBehaviorProbe extends App {
  val targetDir = args.sliding(2, 1).collectFirst {
    case Array("--target-dir", dir) => dir
  }.getOrElse("generated/chisel-verilog/backend-reduced-scalar-writeback-arbiter-behavior-probe")

  ChiselStage.emitSystemVerilogFile(
    new ReducedScalarWritebackArbiterBehaviorProbe,
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info"),
    args = Array("--target-dir", targetDir))
}

class ReducedScalarWritebackArbiterBehaviorProbeSpec extends AnyFunSuite {
  test("elaborates a public wrapper around the real scalar writeback arbiter") {
    val sv = ChiselStage.emitSystemVerilog(new ReducedScalarWritebackArbiterBehaviorProbe)

    assert(sv.contains("module ReducedScalarWritebackArbiterBehaviorProbe"))
    assert(sv.contains("module ReducedScalarWritebackArbiter"))
    assert(sv.contains("io_serviceEnable"))
    assert(sv.contains("io_selectedService"))
    assert(sv.contains("io_templateEnable"))
    assert(sv.contains("io_selectedTemplate"))
    assert(sv.contains("io_templateAdvance"))
  }
}
