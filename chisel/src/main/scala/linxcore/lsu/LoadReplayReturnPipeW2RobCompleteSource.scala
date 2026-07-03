package linxcore.lsu

import chisel3._
import chisel3.util.log2Ceil

import linxcore.commit.{CommitTraceParams, CommitTraceRow}
import linxcore.rob.ROBID

class LoadReplayReturnPipeW2RobCompleteSourceIO(
    val idEntries: Int = 16,
    val traceParams: CommitTraceParams = CommitTraceParams())
    extends Bundle {
  private val ptrWidth = log2Ceil(idEntries)

  val enable = Input(Bool())
  val flush = Input(Bool())
  val resolveValid = Input(Bool())
  val resolveRid = Input(new ROBID(idEntries))
  val executeCompleteValid = Input(Bool())

  val sinkReady = Output(Bool())
  val active = Output(Bool())
  val candidateValid = Output(Bool())
  val completeValid = Output(Bool())
  val completeRobValue = Output(UInt(ptrWidth.W))
  val completeRowValid = Output(Bool())
  val completeRow = Output(new CommitTraceRow(traceParams))
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoResolve = Output(Bool())
  val blockedByInvalidRid = Output(Bool())
  val blockedByExecute = Output(Bool())
}

class LoadReplayReturnPipeW2RobCompleteSource(
    val idEntries: Int = 16,
    val traceParams: CommitTraceParams = CommitTraceParams())
    extends Module {
  require(idEntries > 1, "idEntries must be greater than one")
  require((idEntries & (idEntries - 1)) == 0, "idEntries must be a power of two")

  private val ptrWidth = log2Ceil(idEntries)

  val io = IO(new LoadReplayReturnPipeW2RobCompleteSourceIO(idEntries, traceParams))

  val active = io.enable && !io.flush
  val candidateValid = active && io.resolveValid
  val legalCandidate = candidateValid && io.resolveRid.valid
  val sinkReady = !io.executeCompleteValid
  val completeValid = legalCandidate && sinkReady

  io.sinkReady := sinkReady
  io.active := active
  io.candidateValid := candidateValid
  io.completeValid := completeValid
  io.completeRobValue := Mux(completeValid, io.resolveRid.value, 0.U(ptrWidth.W))
  io.completeRowValid := false.B
  io.completeRow := 0.U.asTypeOf(new CommitTraceRow(traceParams))
  io.blockedByDisabled := !io.enable && io.resolveValid
  io.blockedByFlush := io.enable && io.flush && io.resolveValid
  io.blockedByNoResolve := active && !io.resolveValid
  io.blockedByInvalidRid := candidateValid && !io.resolveRid.valid
  io.blockedByExecute := legalCandidate && io.executeCompleteValid
}
