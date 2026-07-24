package linxcore.backend

import chisel3._
import chisel3.util.Cat

import linxcore.commit.{CommitTraceParams, CommitTraceRow}

class ReducedRobCompletionArbiterIO(
    val ptrWidth: Int,
    val traceParams: CommitTraceParams = CommitTraceParams())
    extends Bundle {
  val executeCompleteValid = Input(Bool())
  val executeCompleteRobValue = Input(UInt(ptrWidth.W))
  val executeCompleteRowValid = Input(Bool())
  val executeCompleteRow = Input(new CommitTraceRow(traceParams))

  val replayCompleteValid = Input(Bool())
  val replayCompleteRobValue = Input(UInt(ptrWidth.W))
  val replayCompleteRowValid = Input(Bool())
  val replayCompleteRow = Input(new CommitTraceRow(traceParams))

  val serviceCompleteValid = Input(Bool())
  val serviceCompleteRobValue = Input(UInt(ptrWidth.W))
  val serviceCompleteRowValid = Input(Bool())
  val serviceCompleteRow = Input(new CommitTraceRow(traceParams))

  val templateCompleteValid = Input(Bool())
  val templateCompleteRobValue = Input(UInt(ptrWidth.W))
  val templateCompleteRowValid = Input(Bool())
  val templateCompleteRow = Input(new CommitTraceRow(traceParams))
  val templateCompleteParentSlot = Input(UInt(ptrWidth.W))

  val completeValid = Output(Bool())
  val completeRobValue = Output(UInt(ptrWidth.W))
  val completeRowValid = Output(Bool())
  val completeRow = Output(new CommitTraceRow(traceParams))
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

class ReducedRobCompletionArbiter(
    val ptrWidth: Int,
    val traceParams: CommitTraceParams = CommitTraceParams(),
    val robEntries: Int = -1)
    extends Module {
  require(ptrWidth > 0, "ptrWidth must be positive")
  private val effectiveRobEntries = if (robEntries < 0) 1 << ptrWidth else robEntries
  require(effectiveRobEntries > 0, "robEntries must be positive")
  require(effectiveRobEntries <= (1 << ptrWidth), "robEntries must fit in ptrWidth")

  val io = IO(new ReducedRobCompletionArbiterIO(ptrWidth, traceParams))

  private val executeRidInvalid = io.executeCompleteValid &&
    (io.executeCompleteRobValue >= effectiveRobEntries.U)
  private val replayRidInvalid = io.replayCompleteValid &&
    (io.replayCompleteRobValue >= effectiveRobEntries.U)
  private val serviceRidInvalid = io.serviceCompleteValid &&
    (io.serviceCompleteRobValue >= effectiveRobEntries.U)
  private val templateRidInvalid = io.templateCompleteValid &&
    (io.templateCompleteRobValue >= effectiveRobEntries.U)

  private val executeCandidate = io.executeCompleteValid && !executeRidInvalid
  private val replayCandidate = io.replayCompleteValid && !replayRidInvalid
  private val serviceCandidate = io.serviceCompleteValid && !serviceRidInvalid
  private val templateCandidate = io.templateCompleteValid && !templateRidInvalid

  val selectedExecute = executeCandidate
  val selectedReplay = replayCandidate && !executeCandidate
  val selectedService = serviceCandidate && !executeCandidate && !replayCandidate
  val selectedTemplate =
    templateCandidate && !executeCandidate && !replayCandidate && !serviceCandidate

  io.selectedExecute := selectedExecute
  io.selectedReplay := selectedReplay
  io.selectedService := selectedService
  io.selectedTemplate := selectedTemplate
  io.selectedSourceMask :=
    Cat(selectedTemplate, selectedService, selectedReplay, selectedExecute)
  io.replayBlockedByExecute := io.replayCompleteValid && io.executeCompleteValid
  io.serviceBlockedByExecute := io.serviceCompleteValid && io.executeCompleteValid
  io.serviceBlockedByReplay :=
    io.serviceCompleteValid && !io.executeCompleteValid && io.replayCompleteValid
  io.templateBlockedByExecute := io.templateCompleteValid && io.executeCompleteValid
  io.templateBlockedByReplay :=
    io.templateCompleteValid && !io.executeCompleteValid && io.replayCompleteValid
  io.completeValid := selectedExecute || selectedReplay || selectedService || selectedTemplate
  io.completeRobValue := Mux(
    selectedExecute,
    io.executeCompleteRobValue,
    Mux(
      selectedReplay,
      io.replayCompleteRobValue,
      Mux(
        selectedService,
        io.serviceCompleteRobValue,
        Mux(selectedTemplate, io.templateCompleteRobValue, 0.U))))
  io.completeRowValid := Mux(
    selectedExecute,
    io.executeCompleteRowValid,
    Mux(
      selectedReplay,
      io.replayCompleteRowValid,
      Mux(selectedService, io.serviceCompleteRowValid, Mux(selectedTemplate, io.templateCompleteRowValid, false.B))))
  io.completeRow := Mux(
    selectedExecute,
    io.executeCompleteRow,
    Mux(
      selectedReplay,
      io.replayCompleteRow,
      Mux(
        selectedService,
        io.serviceCompleteRow,
        Mux(
          selectedTemplate,
          io.templateCompleteRow,
          0.U.asTypeOf(new CommitTraceRow(traceParams))))))

  private val executeReplayContend = io.executeCompleteValid && io.replayCompleteValid
  private val executeServiceContend = io.executeCompleteValid && io.serviceCompleteValid
  private val replayServiceContend = io.replayCompleteValid && io.serviceCompleteValid
  private val executeTemplateContend = io.executeCompleteValid && io.templateCompleteValid
  private val replayTemplateContend = io.replayCompleteValid && io.templateCompleteValid
  private val serviceTemplateContend = io.serviceCompleteValid && io.templateCompleteValid
  private val anyContention =
    executeReplayContend || executeServiceContend || replayServiceContend ||
      executeTemplateContend || replayTemplateContend || serviceTemplateContend
  private val differentRidContention =
    (executeReplayContend && (io.executeCompleteRobValue =/= io.replayCompleteRobValue)) ||
      (executeServiceContend && (io.executeCompleteRobValue =/= io.serviceCompleteRobValue)) ||
      (replayServiceContend && (io.replayCompleteRobValue =/= io.serviceCompleteRobValue)) ||
      (executeTemplateContend && (io.executeCompleteRobValue =/= io.templateCompleteRobValue)) ||
      (replayTemplateContend && (io.replayCompleteRobValue =/= io.templateCompleteRobValue)) ||
      (serviceTemplateContend && (io.serviceCompleteRobValue =/= io.templateCompleteRobValue))
  private val invalidRid =
    executeRidInvalid || replayRidInvalid || serviceRidInvalid || templateRidInvalid

  io.completionContended := anyContention
  io.sameRobCompletionContention := anyContention && !differentRidContention
  io.differentRobCompletionContention := differentRidContention
  io.protocolError := invalidRid || differentRidContention

  when(io.templateCompleteValid) {
    assert(
      io.templateCompleteParentSlot === io.templateCompleteRobValue,
      "template completion parent slot must match its slot-valued completion payload")
  }
}
