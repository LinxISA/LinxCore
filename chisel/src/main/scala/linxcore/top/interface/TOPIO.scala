package linxcore.top.interface

import chisel3._
import chisel3.util._
import linxcore.params.CoreParams

class TOPIO(val p: CoreParams) extends Bundle {
  val instructionMemoryRequest = Decoupled(new MemoryRequestTxn(p))
  val instructionMemoryResponse =
    Flipped(Decoupled(new MemoryResponseTxn(p)))
  val dataMemoryRequest = Vec(p.lsu.loadPipes + p.lsu.storePipes,
    Decoupled(new MemoryRequestTxn(p)))
  val dataMemoryResponse = Flipped(Vec(
    p.lsu.loadPipes + p.lsu.storePipes,
    Decoupled(new MemoryResponseTxn(p))))
  val pInit = Flipped(Decoupled(new PFileInitTxn(p)))
  val bootstrapComplete = Input(Bool())
  val bootstrapReady = Output(Bool())
  val interrupt = Flipped(Valid(new InterruptRequest(p)))
  val debugRequest = Flipped(Decoupled(new DebugRequest(p)))
  val debugResponse = Decoupled(new DebugResponse(p))
  val systemIssue = Vec(p.iex.systemMulticycleQueues,
    Decoupled(new SystemIssueTxn(p)))
  val cmdIssue = Decoupled(new CmdIssueTxn(p))
  val loadReissueRequest = Flipped(Decoupled(new LoadReplayRequestTxn(p)))
  val memoryFault = Decoupled(new LSUMemoryFaultTxn(p))
  val maintenance = Flipped(Decoupled(new LSUMaintenanceTxn(p)))
  val maintenanceResult = Decoupled(new LSUMaintenanceResult(p))
  val commit = Decoupled(new CommitTxn(p))
  val trap = Decoupled(new TrapEvent(p))
  val trace = Decoupled(new TracePacket(p))
  val performanceCounters = Output(Vec(
    p.dtu.performanceCounterCount, UInt(64.W)))
  val lsuQuiescent = Output(Bool())
  val lsuProtocolError = Output(Bool())
}
