package linxcore.backend

import circt.stage.ChiselStage
import chisel3._
import linxcore.commit.{CommitTraceParams, CommitTraceRow}
import org.scalatest.funsuite.AnyFunSuite

class ReducedRobCompletionArbiterBehaviorProbeIO extends Bundle {
  val executeValid = Input(Bool())
  val executeSlot = Input(UInt(3.W))
  val executeRowValid = Input(Bool())
  val executeRowToken = Input(UInt(64.W))

  val replayValid = Input(Bool())
  val replaySlot = Input(UInt(3.W))
  val replayRowValid = Input(Bool())
  val replayRowToken = Input(UInt(64.W))

  val templateValid = Input(Bool())
  val templateSlot = Input(UInt(3.W))
  val templateRowValid = Input(Bool())
  val templateRowToken = Input(UInt(64.W))
  val templateParentSlot = Input(UInt(3.W))

  val serviceValid = Input(Bool())
  val serviceSlot = Input(UInt(3.W))

  val completeValid = Output(Bool())
  val completeSlot = Output(UInt(3.W))
  val completeRowValid = Output(Bool())
  val completeRowToken = Output(UInt(64.W))
  val completeRowSignature = Output(UInt(64.W))
  val completeRowMemToken = Output(UInt(64.W))
  val selectedExecute = Output(Bool())
  val selectedReplay = Output(Bool())
  val selectedService = Output(Bool())
  val selectedTemplate = Output(Bool())
  val selectedSourceMask = Output(UInt(4.W))
  val replayBlockedByExecute = Output(Bool())
  val serviceBlockedByExecute = Output(Bool())
  val serviceBlockedByReplay = Output(Bool())
  val templateBlockedByExecute = Output(Bool())
  val templateBlockedByReplay = Output(Bool())
  val completionContended = Output(Bool())
  val sameRobCompletionContention = Output(Bool())
  val differentRobCompletionContention = Output(Bool())
  val protocolError = Output(Bool())
}

class ReducedRobCompletionArbiterBehaviorProbe extends Module {
  private val traceParams = CommitTraceParams(robValueWidth = 3)
  val io = IO(new ReducedRobCompletionArbiterBehaviorProbeIO)

  chisel3.layer.enable(chisel3.layers.Verification.Assert)

  private val arbiter = Module(
    new ReducedRobCompletionArbiter(
      ptrWidth = 3,
      traceParams = traceParams))

  private def driveRow(row: CommitTraceRow, valid: Bool, token: UInt): Unit = {
    row := 0.U.asTypeOf(new CommitTraceRow(traceParams))
    row.valid := valid
    row.seq := token
    row.pc := token
    row.insn := token ^ "h5a5a5a5a5a5a5a5a".U
    row.nextPc := token + 8.U
    row.mem.valid := valid
    row.mem.wdata := token ^ "ha5a5a5a5a5a5a5a5".U
  }

  arbiter.io.executeCompleteValid := io.executeValid
  arbiter.io.executeCompleteRobValue := io.executeSlot
  arbiter.io.executeCompleteRowValid := io.executeRowValid
  driveRow(arbiter.io.executeCompleteRow, io.executeRowValid, io.executeRowToken)

  arbiter.io.replayCompleteValid := io.replayValid
  arbiter.io.replayCompleteRobValue := io.replaySlot
  arbiter.io.replayCompleteRowValid := io.replayRowValid
  driveRow(arbiter.io.replayCompleteRow, io.replayRowValid, io.replayRowToken)

  arbiter.io.serviceCompleteValid := io.serviceValid
  arbiter.io.serviceCompleteRobValue := io.serviceSlot

  arbiter.io.templateCompleteValid := io.templateValid
  arbiter.io.templateCompleteRobValue := io.templateSlot
  arbiter.io.templateCompleteRowValid := io.templateRowValid
  driveRow(arbiter.io.templateCompleteRow, io.templateRowValid, io.templateRowToken)
  arbiter.io.templateCompleteParentSlot := io.templateParentSlot

  io.completeValid := arbiter.io.completeValid
  io.completeSlot := arbiter.io.completeRobValue
  io.completeRowValid := arbiter.io.completeRowValid
  io.completeRowToken := arbiter.io.completeRow.pc
  io.completeRowSignature := arbiter.io.completeRow.nextPc
  io.completeRowMemToken := arbiter.io.completeRow.mem.wdata
  io.selectedExecute := arbiter.io.selectedExecute
  io.selectedReplay := arbiter.io.selectedReplay
  io.selectedService := arbiter.io.selectedService
  io.selectedTemplate := arbiter.io.selectedTemplate
  io.selectedSourceMask := arbiter.io.selectedSourceMask
  io.replayBlockedByExecute := arbiter.io.replayBlockedByExecute
  io.serviceBlockedByExecute := arbiter.io.serviceBlockedByExecute
  io.serviceBlockedByReplay := arbiter.io.serviceBlockedByReplay
  io.templateBlockedByExecute := arbiter.io.templateBlockedByExecute
  io.templateBlockedByReplay := arbiter.io.templateBlockedByReplay
  io.completionContended := arbiter.io.completionContended
  io.sameRobCompletionContention := arbiter.io.sameRobCompletionContention
  io.differentRobCompletionContention := arbiter.io.differentRobCompletionContention
  io.protocolError := arbiter.io.protocolError
}

object EmitReducedRobCompletionArbiterBehaviorProbe extends App {
  val targetDir = args.sliding(2, 1).collectFirst {
    case Array("--target-dir", dir) => dir
  }.getOrElse("generated/chisel-verilog/backend-reduced-rob-completion-arbiter-behavior-probe")

  ChiselStage.emitSystemVerilogFile(
    new ReducedRobCompletionArbiterBehaviorProbe,
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info"),
    args = Array("--target-dir", targetDir))
}

class ReducedRobCompletionArbiterBehaviorProbeSpec extends AnyFunSuite {
  test("elaborates a public wrapper around the real completion arbiter") {
    val sv = ChiselStage.emitSystemVerilog(new ReducedRobCompletionArbiterBehaviorProbe)

    assert(sv.contains("module ReducedRobCompletionArbiterBehaviorProbe"))
    assert(sv.contains("module ReducedRobCompletionArbiter"))
    assert(!sv.toLowerCase.contains("exactcomplete"))
  }
}
