package linxcore.dtu

import chisel3._
import linxcore.params.CoreParams

object PerformanceCounterIndex {
  val TraceAccepted = 0
  val TraceDropped = 1
  val CommitTransactions = 2
  val CommittedInstructions = 3
  val DebugRequests = 4
  val RequiredCount = 5
}

class PerformanceCountersIO(val p: CoreParams) extends Bundle {
  val traceAccepted = Input(UInt(64.W))
  val traceDropped = Input(UInt(64.W))
  val commitObserved = Input(Bool())
  val commitCount = Input(UInt(
    linxcore.top.interface.PrefixPacketContract.countWidth(
      p.widths.retireWidth).W))
  val debugRequestAccepted = Input(Bool())
  val values = Output(Vec(p.dtu.performanceCounterCount, UInt(64.W)))
}

/** Monotonic observations only; no counter participates in control. */
class PerformanceCounters(val p: CoreParams) extends Module {
  require(p.dtu.performanceCounterCount >= PerformanceCounterIndex.RequiredCount)
  val io = IO(new PerformanceCountersIO(p))

  private val counters = RegInit(VecInit(
    Seq.fill(p.dtu.performanceCounterCount)(0.U(64.W))))
  when(io.traceAccepted.orR) {
    counters(PerformanceCounterIndex.TraceAccepted) :=
      counters(PerformanceCounterIndex.TraceAccepted) + io.traceAccepted
  }
  when(io.traceDropped.orR) {
    counters(PerformanceCounterIndex.TraceDropped) :=
      counters(PerformanceCounterIndex.TraceDropped) + io.traceDropped
  }
  when(io.commitObserved) {
    counters(PerformanceCounterIndex.CommitTransactions) :=
      counters(PerformanceCounterIndex.CommitTransactions) + 1.U
    counters(PerformanceCounterIndex.CommittedInstructions) :=
      counters(PerformanceCounterIndex.CommittedInstructions) + io.commitCount
  }
  when(io.debugRequestAccepted) {
    counters(PerformanceCounterIndex.DebugRequests) :=
      counters(PerformanceCounterIndex.DebugRequests) + 1.U
  }
  io.values := counters
}
