package linxcore.dtu

import chisel3._
import chisel3.util.{Decoupled, Queue}
import linxcore.params.CoreParams
import linxcore.top.interface.{DebugRequest, DebugResponse}

class DebugControlIO(val p: CoreParams) extends Bundle {
  val externalRequest = Flipped(Decoupled(new DebugRequest(p)))
  val ownerRequest = Decoupled(new DebugRequest(p))
  val ownerResponse = Flipped(Decoupled(new DebugResponse(p)))
  val externalResponse = Decoupled(new DebugResponse(p))
}

/** Retains debug transport while the OOO control owner is unavailable.
  *
  * This module does not interpret commands or select a commit boundary.
  */
class DebugControl(val p: CoreParams) extends Module {
  val io = IO(new DebugControlIO(p))

  private val requests = Module(new Queue(new DebugRequest(p), 1,
    pipe = false, flow = false))
  requests.io.enq <> io.externalRequest
  io.ownerRequest <> requests.io.deq
  io.externalResponse <> io.ownerResponse
}
