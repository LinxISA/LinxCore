package linxcore.top.interface

import chisel3._
import chisel3.util._
import linxcore.params.CoreParams

class TOPIO(val p: CoreParams) extends Bundle {
  val instructionMemoryRequest = Decoupled(new MemoryRequestTxn(p))
  val instructionMemoryResponse =
    Flipped(Decoupled(new MemoryResponseTxn(p)))
  val dataMemoryRequest = Decoupled(new MemoryRequestTxn(p))
  val dataMemoryResponse = Flipped(Decoupled(new MemoryResponseTxn(p)))
  val interrupt = Flipped(Valid(new InterruptRequest(p)))
  val debugRequest = Flipped(Decoupled(new DebugRequest(p)))
  val debugResponse = Decoupled(new DebugResponse(p))
  val commit = Decoupled(new CommitTxn(p))
  val trap = Decoupled(new TrapEvent(p))
  val trace = Decoupled(new TracePacket(p))
}
