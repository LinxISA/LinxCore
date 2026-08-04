package linxcore.top.interface

import chisel3._
import chisel3.util._
import linxcore.params.CoreParams

class DTUIO(val p: CoreParams) extends Bundle {
  val traceIn = Flipped(Decoupled(new TracePacket(p)))
  val commitIn = Flipped(Decoupled(new CommitTxn(p)))
  val debugRequest = Flipped(Decoupled(new DebugRequest(p)))
  val debugResponse = Decoupled(new DebugResponse(p))
  val controlRequest = Decoupled(new DebugRequest(p))
  val controlResponse = Flipped(Decoupled(new DebugResponse(p)))
  val traceOut = Decoupled(new TracePacket(p))
  val performanceCounters = Output(Vec(
    p.dtu.performanceCounterCount, UInt(64.W)))
}
