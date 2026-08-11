package linxcore.dtu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import _root_.circt.stage.ChiselStage
import linxcore.params.ParamProfiles
import linxcore.top.interface._
import org.scalatest.funsuite.AnyFunSuite

class DTUSpec extends AnyFunSuite with ChiselSim {
  private val p = ParamProfiles.W4

  private def clear(dut: DTU): Unit = {
    dut.io.traceIn.valid.poke(false.B)
    dut.io.traceIn.bits.poke(0.U.asTypeOf(dut.io.traceIn.bits))
    dut.io.traceOverflowDropped.poke(0.U)
    dut.io.commitIn.valid.poke(false.B)
    dut.io.commitIn.bits.poke(0.U.asTypeOf(dut.io.commitIn.bits))
    dut.io.debugRequest.valid.poke(false.B)
    dut.io.debugRequest.bits.poke(0.U.asTypeOf(dut.io.debugRequest.bits))
    dut.io.controlRequest.ready.poke(false.B)
    dut.io.controlResponse.valid.poke(false.B)
    dut.io.controlResponse.bits.poke(0.U.asTypeOf(dut.io.controlResponse.bits))
    dut.io.debugResponse.ready.poke(false.B)
    dut.io.traceOut.ready.poke(false.B)
  }

  test("trace observation never backpressures a stalled exporter") {
    simulate(new DTU(p)) { dut =>
      clear(dut)
      dut.io.traceIn.valid.poke(true.B)
      dut.io.traceIn.bits.count.poke(1.U)
      dut.io.traceIn.bits.entries(0).cycle.poke(11.U)
      dut.io.traceIn.ready.expect(true.B)
      dut.clock.step()

      dut.io.traceOut.valid.expect(true.B)
      dut.io.traceOut.bits.entries(0).cycle.expect(11.U)
      dut.io.traceIn.bits.entries(0).cycle.poke(12.U)
      dut.io.traceIn.ready.expect(true.B)
      dut.clock.step()

      dut.io.traceOut.valid.expect(true.B)
      dut.io.traceOut.bits.entries(0).cycle.expect(11.U)
      dut.io.performanceCounters(PerformanceCounterIndex.TraceAccepted)
        .expect(1.U)
      dut.io.performanceCounters(PerformanceCounterIndex.TraceDropped)
        .expect(1.U)
    }
  }

  test("trace event counters include explicit concurrent-source overflow") {
    simulate(new DTU(p)) { dut =>
      clear(dut)
      dut.io.traceOut.ready.poke(true.B)
      dut.io.traceIn.valid.poke(true.B)
      dut.io.traceIn.bits.count.poke(4.U)
      dut.io.traceOverflowDropped.poke(3.U)
      dut.clock.step()
      dut.io.traceIn.valid.poke(false.B)
      dut.io.traceOverflowDropped.poke(0.U)
      dut.io.performanceCounters(PerformanceCounterIndex.TraceAccepted)
        .expect(4.U)
      dut.io.performanceCounters(PerformanceCounterIndex.TraceDropped)
        .expect(3.U)
    }
  }

  test("commit observations count retired entries without controlling commit") {
    simulate(new DTU(p)) { dut =>
      clear(dut)
      dut.io.commitIn.valid.poke(true.B)
      dut.io.commitIn.bits.count.poke(3.U)
      dut.io.commitIn.ready.expect(true.B)
      dut.clock.step()
      dut.io.commitIn.valid.poke(false.B)

      dut.io.performanceCounters(PerformanceCounterIndex.CommitTransactions)
        .expect(1.U)
      dut.io.performanceCounters(PerformanceCounterIndex.CommittedInstructions)
        .expect(3.U)
    }
  }

  test("halt and resume requests are retained for the OOO control owner") {
    simulate(new DTU(p)) { dut =>
      clear(dut)
      dut.io.debugRequest.valid.poke(true.B)
      dut.io.debugRequest.bits.transactionId.poke(0x31.U)
      dut.io.debugRequest.bits.command.poke(DebugCommand.Halt)
      dut.io.debugRequest.bits.stid.poke(1.U)
      dut.io.debugRequest.ready.expect(true.B)
      dut.clock.step()
      dut.io.debugRequest.valid.poke(false.B)

      dut.io.controlRequest.valid.expect(true.B)
      dut.io.controlRequest.bits.transactionId.expect(0x31.U)
      dut.io.controlRequest.bits.command.expect(DebugCommand.Halt)
      dut.clock.step(3)
      dut.io.controlRequest.valid.expect(true.B)
      dut.io.controlRequest.bits.transactionId.expect(0x31.U)

      dut.io.controlRequest.ready.poke(true.B)
      dut.clock.step()
      dut.io.controlRequest.ready.poke(false.B)
      dut.io.controlResponse.valid.poke(true.B)
      dut.io.controlResponse.bits.transactionId.poke(0x31.U)
      dut.io.controlResponse.bits.accepted.poke(true.B)
      dut.io.debugResponse.valid.expect(true.B)
      dut.io.debugResponse.bits.transactionId.expect(0x31.U)
      dut.io.debugResponse.bits.accepted.expect(true.B)
      dut.io.debugResponse.ready.poke(true.B)
      dut.clock.step()
    }
  }

  test("DTU IO exposes observations and requests but no recovery authority") {
    val io = new DTUIO(p)
    assert(io.elements.keySet == Set(
      "traceIn", "traceOverflowDropped", "commitIn", "debugRequest", "debugResponse",
      "controlRequest", "controlResponse", "traceOut",
      "performanceCounters"))
    assert(!io.elements.keySet.exists(_.toLowerCase.contains("recovery")))

    val sv = ChiselStage.emitSystemVerilog(new DTU(p))
    assert(sv.contains("module DTU"))
    assert(!sv.contains("RecoveryPlan"))
    assert(!sv.contains("CommitControl"))
    assert(!sv.contains("RecoveryControl"))
  }
}
