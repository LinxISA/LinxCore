package linxcore.dtu

import chisel3._
import linxcore.params.CoreParams
import linxcore.top.interface.DTUIO

/** Debug, trace, and performance observation boundary.
  *
  * Architectural commit, trap, interrupt, and recovery decisions remain in
  * OOO. DTU only transports requests and observes already-selected events.
  */
class DTU(val p: CoreParams) extends Module {
  val io = IO(new DTUIO(p))

  private val debug = Module(new DebugControl(p))
  private val trace = Module(new TraceExport(p))
  private val counters = Module(new PerformanceCounters(p))

  debug.io.externalRequest <> io.debugRequest
  io.controlRequest <> debug.io.ownerRequest
  debug.io.ownerResponse <> io.controlResponse
  io.debugResponse <> debug.io.externalResponse

  trace.io.in <> io.traceIn
  io.traceOut <> trace.io.out

  io.commitIn.ready := true.B
  counters.io.traceAccepted := trace.io.accepted
  counters.io.traceDropped := trace.io.dropped
  counters.io.commitObserved := io.commitIn.fire
  counters.io.commitCount := io.commitIn.bits.count
  counters.io.debugRequestAccepted := io.debugRequest.fire
  io.performanceCounters := counters.io.values
}
