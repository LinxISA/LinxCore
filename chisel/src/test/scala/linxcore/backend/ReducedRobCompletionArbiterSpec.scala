package linxcore.backend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import circt.stage.ChiselStage
import linxcore.commit.{CommitTraceParams, CommitTraceRow}
import org.scalatest.funsuite.AnyFunSuite

object ReducedRobCompletionArbiterReference {
  final case class Source(
      valid: Boolean,
      robValue: Int,
      rowValid: Boolean,
      rowToken: Int)

  final case class Result(
      completeValid: Boolean,
      completeRobValue: Int,
      completeRowValid: Boolean,
      completeRowToken: Int,
      selectedExecute: Boolean,
      selectedReplay: Boolean,
      selectedService: Boolean,
      selectedTemplate: Boolean,
      selectedSourceMask: Int,
      replayBlockedByExecute: Boolean,
      serviceBlockedByExecute: Boolean,
      serviceBlockedByReplay: Boolean,
      templateBlockedByExecute: Boolean,
      templateBlockedByReplay: Boolean,
      completionContended: Boolean,
      sameRobCompletionContention: Boolean,
      differentRobCompletionContention: Boolean,
      protocolError: Boolean)

  def apply(
      execute: Source,
      replay: Source,
      service: Source,
      template: Source,
      templateParentSlot: Int,
      robEntries: Int = 8): Result = {
    require(
      !template.valid || template.robValue == templateParentSlot,
      "template completion parent slot must match its slot-valued completion payload")

    def invalid(src: Source): Boolean = src.valid && (src.robValue < 0 || src.robValue >= robEntries)

    val executeCandidate = execute.valid && !invalid(execute)
    val replayCandidate = replay.valid && !invalid(replay)
    val serviceCandidate = service.valid && !invalid(service)
    val templateCandidate = template.valid && !invalid(template)
    val selectedExecute = executeCandidate
    val selectedReplay = replayCandidate && !executeCandidate
    val selectedService = serviceCandidate && !executeCandidate && !replayCandidate
    val selectedTemplate =
      templateCandidate && !executeCandidate && !replayCandidate && !serviceCandidate
    val selected =
      if (selectedExecute) Some(execute)
      else if (selectedReplay) Some(replay)
      else if (selectedService) Some(service.copy(rowValid = false, rowToken = 0))
      else if (selectedTemplate) Some(template)
      else None
    val sourceMask =
      (if (selectedExecute) 0x1 else 0) |
        (if (selectedReplay) 0x2 else 0) |
        (if (selectedService) 0x4 else 0) |
        (if (selectedTemplate) 0x8 else 0)

    val sources = Seq(execute, replay, service, template)
    val contended = sources.count(_.valid) >= 2
    val validRids = sources.filter(_.valid).map(_.robValue)
    val differentRid = validRids.distinct.size > 1
    val invalidRid = sources.exists(invalid)

    Result(
      completeValid = selected.nonEmpty,
      completeRobValue = selected.map(_.robValue).getOrElse(0),
      completeRowValid = selected.exists(_.rowValid),
      completeRowToken = selected.map(_.rowToken).getOrElse(0),
      selectedExecute = selectedExecute,
      selectedReplay = selectedReplay,
      selectedService = selectedService,
      selectedTemplate = selectedTemplate,
      selectedSourceMask = sourceMask,
      replayBlockedByExecute = replay.valid && execute.valid,
      serviceBlockedByExecute = service.valid && execute.valid,
      serviceBlockedByReplay = service.valid && !execute.valid && replay.valid,
      templateBlockedByExecute = template.valid && execute.valid,
      templateBlockedByReplay = template.valid && !execute.valid && replay.valid,
      completionContended = contended,
      sameRobCompletionContention = contended && !differentRid,
      differentRobCompletionContention = differentRid,
      protocolError = invalidRid || differentRid)
  }
}

class ReducedRobCompletionArbiterProbeIO extends Bundle {
  val executeValid = Input(Bool())
  val executeRobValue = Input(UInt(3.W))
  val executeRowValid = Input(Bool())
  val executeRowToken = Input(UInt(64.W))
  val replayValid = Input(Bool())
  val replayRobValue = Input(UInt(3.W))
  val replayRowValid = Input(Bool())
  val replayRowToken = Input(UInt(64.W))
  val serviceValid = Input(Bool())
  val serviceRobValue = Input(UInt(3.W))
  val templateValid = Input(Bool())
  val templateRobValue = Input(UInt(3.W))
  val templateRowValid = Input(Bool())
  val templateRowToken = Input(UInt(64.W))
  val templateParentSlot = Input(UInt(3.W))

  val completeValid = Output(Bool())
  val completeRobValue = Output(UInt(3.W))
  val completeRowValid = Output(Bool())
  val completeRowToken = Output(UInt(64.W))
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

class ReducedRobCompletionArbiterProbe extends Module {
  private val traceParams = CommitTraceParams(robValueWidth = 3)
  val io = IO(new ReducedRobCompletionArbiterProbeIO)

  chisel3.layer.enable(chisel3.layers.Verification.Assert)

  private val arbiter = Module(
    new ReducedRobCompletionArbiter(
      ptrWidth = 3,
      traceParams = traceParams,
      robEntries = 6))

  private def driveRow(row: CommitTraceRow, valid: Bool, token: UInt): Unit = {
    row := 0.U.asTypeOf(new CommitTraceRow(traceParams))
    row.valid := valid
    row.seq := token
    row.pc := token
    row.insn := token ^ "h5a5a5a5a5a5a5a5a".U
    row.nextPc := token + 4.U
  }

  arbiter.io.executeCompleteValid := io.executeValid
  arbiter.io.executeCompleteRobValue := io.executeRobValue
  arbiter.io.executeCompleteRowValid := io.executeRowValid
  driveRow(arbiter.io.executeCompleteRow, io.executeRowValid, io.executeRowToken)

  arbiter.io.replayCompleteValid := io.replayValid
  arbiter.io.replayCompleteRobValue := io.replayRobValue
  arbiter.io.replayCompleteRowValid := io.replayRowValid
  driveRow(arbiter.io.replayCompleteRow, io.replayRowValid, io.replayRowToken)

  arbiter.io.serviceCompleteValid := io.serviceValid
  arbiter.io.serviceCompleteRobValue := io.serviceRobValue

  arbiter.io.templateCompleteValid := io.templateValid
  arbiter.io.templateCompleteRobValue := io.templateRobValue
  arbiter.io.templateCompleteRowValid := io.templateRowValid
  driveRow(arbiter.io.templateCompleteRow, io.templateRowValid, io.templateRowToken)
  arbiter.io.templateCompleteParentSlot := io.templateParentSlot

  io.completeValid := arbiter.io.completeValid
  io.completeRobValue := arbiter.io.completeRobValue
  io.completeRowValid := arbiter.io.completeRowValid
  io.completeRowToken := arbiter.io.completeRow.pc
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

class ReducedRobCompletionArbiterSpec extends AnyFunSuite with ChiselSim {
  import ReducedRobCompletionArbiterReference._

  private val executePayload = Source(valid = false, robValue = 2, rowValid = true, rowToken = 0x11)
  private val replayPayload = Source(valid = false, robValue = 5, rowValid = false, rowToken = 0x22)
  private val servicePayload = Source(valid = false, robValue = 3, rowValid = false, rowToken = 0)
  private val templatePayload = Source(valid = false, robValue = 4, rowValid = true, rowToken = 0x44)

  test("exhausts execute replay service template validity combinations") {
    for (validBits <- 0 until 16) {
      val execute = executePayload.copy(valid = (validBits & 0x8) != 0)
      val replay = replayPayload.copy(valid = (validBits & 0x4) != 0)
      val service = servicePayload.copy(valid = (validBits & 0x2) != 0)
      val template = templatePayload.copy(valid = (validBits & 0x1) != 0)
      val result = ReducedRobCompletionArbiterReference(
        execute,
        replay,
        service,
        template,
        templateParentSlot = template.robValue)
      val selected =
        if (execute.valid) Some(("execute", execute))
        else if (replay.valid) Some(("replay", replay))
        else if (service.valid) Some(("service", service.copy(rowValid = false, rowToken = 0)))
        else if (template.valid) Some(("template", template))
        else None
      val selections =
        Seq(result.selectedExecute, result.selectedReplay, result.selectedService, result.selectedTemplate)

      assert(result.completeValid == selected.nonEmpty, s"validBits=$validBits")
      assert(result.completeRobValue == selected.map(_._2.robValue).getOrElse(0), s"validBits=$validBits")
      assert(result.completeRowValid == selected.exists(_._2.rowValid), s"validBits=$validBits")
      assert(result.completeRowToken == selected.map(_._2.rowToken).getOrElse(0), s"validBits=$validBits")
      assert(selections.count(identity) == selected.size, s"validBits=$validBits")
      assert(result.selectedExecute == selected.exists(_._1 == "execute"), s"validBits=$validBits")
      assert(result.selectedReplay == selected.exists(_._1 == "replay"), s"validBits=$validBits")
      assert(result.selectedService == selected.exists(_._1 == "service"), s"validBits=$validBits")
      assert(result.selectedTemplate == selected.exists(_._1 == "template"), s"validBits=$validBits")
      assert(result.replayBlockedByExecute == (replay.valid && execute.valid), s"validBits=$validBits")
      assert(result.serviceBlockedByExecute == (service.valid && execute.valid), s"validBits=$validBits")
      assert(
        result.serviceBlockedByReplay == (service.valid && !execute.valid && replay.valid),
        s"validBits=$validBits")
      assert(result.templateBlockedByExecute == (template.valid && execute.valid), s"validBits=$validBits")
      assert(
        result.templateBlockedByReplay == (template.valid && !execute.valid && replay.valid),
        s"validBits=$validBits")
    }
  }

  test("forwards row-valid and row payload only from selected row-carrying sources") {
    val rowSources = Seq(executePayload, replayPayload, templatePayload)

    for {
      selectedIndex <- rowSources.indices
      rowValid <- Seq(false, true)
    } {
      val selectedSource = rowSources(selectedIndex).copy(valid = true, rowValid = rowValid)
      val execute = if (selectedIndex == 0) selectedSource else executePayload
      val replay = if (selectedIndex == 1) selectedSource else replayPayload
      val template = if (selectedIndex == 2) selectedSource else templatePayload
      val result = ReducedRobCompletionArbiterReference(
        execute,
        replay,
        servicePayload,
        template,
        templateParentSlot = template.robValue)

      assert(result.completeRobValue == selectedSource.robValue)
      assert(result.completeRowValid == rowValid)
      assert(result.completeRowToken == selectedSource.rowToken)
    }
  }

  test("service-only completion selects RID and never fabricates a trace row") {
    val result = ReducedRobCompletionArbiterReference(
      executePayload,
      replayPayload,
      servicePayload.copy(valid = true),
      templatePayload,
      templateParentSlot = templatePayload.robValue)

    assert(result.completeValid)
    assert(result.completeRobValue == servicePayload.robValue)
    assert(!result.completeRowValid)
    assert(result.completeRowToken == 0)
    assert(result.selectedService)
    assert(result.selectedSourceMask == 0x4)
    assert(!result.protocolError)
  }

  test("template-only completion remains a real lowest-priority row source") {
    val result = ReducedRobCompletionArbiterReference(
      executePayload,
      replayPayload,
      servicePayload,
      templatePayload.copy(valid = true),
      templateParentSlot = templatePayload.robValue)

    assert(result.completeValid)
    assert(result.completeRobValue == templatePayload.robValue)
    assert(result.completeRowValid)
    assert(result.completeRowToken == templatePayload.rowToken)
    assert(result.selectedTemplate)
    assert(result.selectedSourceMask == 0x8)
  }

  test("sanitizes every completion output while idle") {
    val result = ReducedRobCompletionArbiterReference(
      executePayload,
      replayPayload,
      servicePayload,
      templatePayload,
      templateParentSlot = templatePayload.robValue)

    assert(!result.completeValid)
    assert(result.completeRobValue == 0)
    assert(!result.completeRowValid)
    assert(result.completeRowToken == 0)
    assert(!result.selectedExecute)
    assert(!result.selectedReplay)
    assert(!result.selectedService)
    assert(!result.selectedTemplate)
    assert(result.selectedSourceMask == 0)
    assert(!result.replayBlockedByExecute)
    assert(!result.serviceBlockedByExecute)
    assert(!result.serviceBlockedByReplay)
    assert(!result.templateBlockedByExecute)
    assert(!result.templateBlockedByReplay)
    assert(!result.completionContended)
    assert(!result.protocolError)
  }

  test("flags same-RID and different-RID contention without issuing duplicate completions") {
    val same = ReducedRobCompletionArbiterReference(
      executePayload.copy(valid = true, robValue = 2),
      replayPayload.copy(valid = true, robValue = 2),
      servicePayload.copy(valid = true, robValue = 2),
      templatePayload.copy(valid = true, robValue = 2),
      templateParentSlot = 2)
    assert(same.completeRobValue == 2)
    assert(same.selectedExecute)
    assert(same.completionContended)
    assert(same.sameRobCompletionContention)
    assert(!same.differentRobCompletionContention)
    assert(!same.protocolError)

    val different = ReducedRobCompletionArbiterReference(
      executePayload.copy(valid = false),
      replayPayload.copy(valid = true, robValue = 1),
      servicePayload.copy(valid = true, robValue = 3),
      templatePayload.copy(valid = true, robValue = 3),
      templateParentSlot = 3)
    assert(different.completeRobValue == 1)
    assert(different.selectedReplay)
    assert(different.completionContended)
    assert(!different.sameRobCompletionContention)
    assert(different.differentRobCompletionContention)
    assert(different.protocolError)
  }

  test("invalid RID is rejected while lower-priority valid sources remain selectable") {
    val serviceInvalid = ReducedRobCompletionArbiterReference(
      executePayload,
      replayPayload,
      servicePayload.copy(valid = true, robValue = 6),
      templatePayload,
      templateParentSlot = templatePayload.robValue,
      robEntries = 6)
    assert(!serviceInvalid.completeValid)
    assert(serviceInvalid.protocolError)

    val executeInvalidReplayValid = ReducedRobCompletionArbiterReference(
      executePayload.copy(valid = true, robValue = 7),
      replayPayload.copy(valid = true, robValue = 4),
      servicePayload,
      templatePayload,
      templateParentSlot = templatePayload.robValue,
      robEntries = 6)
    assert(executeInvalidReplayValid.completeValid)
    assert(executeInvalidReplayValid.selectedReplay)
    assert(executeInvalidReplayValid.completeRobValue == 4)
    assert(executeInvalidReplayValid.protocolError)
  }

  test("rejects a valid template payload whose parent slot disagrees") {
    val mismatchedTemplate = templatePayload.copy(valid = true)

    intercept[IllegalArgumentException] {
      ReducedRobCompletionArbiterReference(
        executePayload,
        replayPayload,
        servicePayload,
        mismatchedTemplate,
        templateParentSlot = mismatchedTemplate.robValue + 1)
    }
  }

  test("Chisel ReducedRobCompletionArbiter elaborates service and template source interface") {
    val sv = ChiselStage.emitSystemVerilog(
      new ReducedRobCompletionArbiter(
        ptrWidth = 3,
        traceParams = CommitTraceParams(robValueWidth = 3),
        robEntries = 6))

    assert(sv.contains("module ReducedRobCompletionArbiter"))
    assert(sv.contains("io_executeCompleteValid"))
    assert(sv.contains("io_replayCompleteValid"))
    assert(sv.contains("io_serviceCompleteValid"))
    assert(sv.contains("io_serviceCompleteRobValue"))
    assert(sv.contains("io_templateCompleteValid"))
    assert(sv.contains("io_templateCompleteRobValue"))
    assert(sv.contains("io_templateCompleteParentSlot"))
    assert(sv.contains("io_selectedExecute"))
    assert(sv.contains("io_selectedReplay"))
    assert(sv.contains("io_selectedService"))
    assert(sv.contains("io_selectedTemplate"))
    assert(sv.contains("io_selectedSourceMask"))
    assert(sv.contains("io_replayBlockedByExecute"))
    assert(sv.contains("io_serviceBlockedByExecute"))
    assert(sv.contains("io_serviceBlockedByReplay"))
    assert(sv.contains("io_templateBlockedByExecute"))
    assert(sv.contains("io_templateBlockedByReplay"))
    assert(sv.contains("io_completionContended"))
    assert(sv.contains("io_sameRobCompletionContention"))
    assert(sv.contains("io_differentRobCompletionContention"))
    assert(sv.contains("io_protocolError"))
    assert(!sv.contains("exactComplete"))
  }

  test("Chisel behavior is combinational for service, template, priority, and invalid RID cases") {
    simulate(new ReducedRobCompletionArbiterProbe) { dut =>
      dut.io.executeValid.poke(false.B)
      dut.io.executeRobValue.poke(2.U)
      dut.io.executeRowValid.poke(true.B)
      dut.io.executeRowToken.poke(0x11.U)
      dut.io.replayValid.poke(false.B)
      dut.io.replayRobValue.poke(5.U)
      dut.io.replayRowValid.poke(true.B)
      dut.io.replayRowToken.poke(0x22.U)
      dut.io.serviceValid.poke(true.B)
      dut.io.serviceRobValue.poke(3.U)
      dut.io.templateValid.poke(false.B)
      dut.io.templateRobValue.poke(4.U)
      dut.io.templateRowValid.poke(true.B)
      dut.io.templateRowToken.poke(0x44.U)
      dut.io.templateParentSlot.poke(4.U)
      dut.io.completeValid.expect(true.B)
      dut.io.completeRobValue.expect(3.U)
      dut.io.completeRowValid.expect(false.B)
      dut.io.completeRowToken.expect(0.U)
      dut.io.selectedService.expect(true.B)
      dut.io.selectedSourceMask.expect(4.U)
      dut.io.protocolError.expect(false.B)

      dut.io.serviceValid.poke(false.B)
      dut.io.templateValid.poke(true.B)
      dut.io.completeRobValue.expect(4.U)
      dut.io.completeRowValid.expect(true.B)
      dut.io.completeRowToken.expect(0x44.U)
      dut.io.selectedTemplate.expect(true.B)
      dut.io.selectedSourceMask.expect(8.U)

      dut.io.executeValid.poke(true.B)
      dut.io.replayValid.poke(true.B)
      dut.io.serviceValid.poke(true.B)
      dut.io.templateValid.poke(true.B)
      dut.io.executeRobValue.poke(2.U)
      dut.io.replayRobValue.poke(2.U)
      dut.io.serviceRobValue.poke(2.U)
      dut.io.templateRobValue.poke(2.U)
      dut.io.templateParentSlot.poke(2.U)
      dut.io.completeRobValue.expect(2.U)
      dut.io.selectedExecute.expect(true.B)
      dut.io.selectedSourceMask.expect(1.U)
      dut.io.completionContended.expect(true.B)
      dut.io.sameRobCompletionContention.expect(true.B)
      dut.io.protocolError.expect(false.B)

      dut.io.executeValid.poke(false.B)
      dut.io.replayValid.poke(true.B)
      dut.io.serviceValid.poke(true.B)
      dut.io.templateValid.poke(true.B)
      dut.io.replayRobValue.poke(1.U)
      dut.io.serviceRobValue.poke(3.U)
      dut.io.templateRobValue.poke(3.U)
      dut.io.templateParentSlot.poke(3.U)
      dut.io.completeRobValue.expect(1.U)
      dut.io.selectedReplay.expect(true.B)
      dut.io.selectedSourceMask.expect(2.U)
      dut.io.differentRobCompletionContention.expect(true.B)
      dut.io.protocolError.expect(true.B)

      dut.io.replayValid.poke(false.B)
      dut.io.serviceValid.poke(true.B)
      dut.io.templateValid.poke(false.B)
      dut.io.serviceRobValue.poke(6.U)
      dut.io.completeValid.expect(false.B)
      dut.io.protocolError.expect(true.B)
    }
  }
}
