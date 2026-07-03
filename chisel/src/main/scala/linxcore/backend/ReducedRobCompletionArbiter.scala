package linxcore.backend

import chisel3._

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

  val completeValid = Output(Bool())
  val completeRobValue = Output(UInt(ptrWidth.W))
  val completeRowValid = Output(Bool())
  val completeRow = Output(new CommitTraceRow(traceParams))
  val selectedExecute = Output(Bool())
  val selectedReplay = Output(Bool())
  val replayBlockedByExecute = Output(Bool())
}

class ReducedRobCompletionArbiter(
    val ptrWidth: Int,
    val traceParams: CommitTraceParams = CommitTraceParams())
    extends Module {
  require(ptrWidth > 0, "ptrWidth must be positive")

  val io = IO(new ReducedRobCompletionArbiterIO(ptrWidth, traceParams))

  val selectedExecute = io.executeCompleteValid
  val selectedReplay = io.replayCompleteValid && !io.executeCompleteValid

  io.selectedExecute := selectedExecute
  io.selectedReplay := selectedReplay
  io.replayBlockedByExecute := io.replayCompleteValid && io.executeCompleteValid
  io.completeValid := selectedExecute || selectedReplay
  io.completeRobValue :=
    Mux(selectedExecute, io.executeCompleteRobValue, Mux(selectedReplay, io.replayCompleteRobValue, 0.U))
  io.completeRowValid :=
    Mux(selectedExecute, io.executeCompleteRowValid, Mux(selectedReplay, io.replayCompleteRowValid, false.B))
  io.completeRow := Mux(
    selectedExecute,
    io.executeCompleteRow,
    Mux(selectedReplay, io.replayCompleteRow, 0.U.asTypeOf(new CommitTraceRow(traceParams))))
}
