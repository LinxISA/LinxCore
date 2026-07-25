package linxcore.frontend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.common.InterfaceParams
import org.scalatest.funsuite.AnyFunSuite

class IfuRedirectArbiterSpec extends AnyFunSuite with ChiselSim {
  private val p = InterfaceParams()

  private def clear(dut: IfuRedirectArbiter): Unit = {
    dut.io.epochSeed.valid.poke(false.B)
    dut.io.epochSeed.bits.poke(0.U.asTypeOf(dut.io.epochSeed.bits))
    dut.io.backend.valid.poke(false.B)
    dut.io.backend.bits.poke(0.U.asTypeOf(dut.io.backend.bits))
    dut.io.itlb.valid.poke(false.B)
    dut.io.itlb.bits.poke(0.U.asTypeOf(dut.io.itlb.bits))
    dut.io.prediction.valid.poke(false.B)
    dut.io.prediction.bits.poke(0.U.asTypeOf(dut.io.prediction.bits))
    dut.io.out.ready.poke(false.B)
  }

  private def pokeProposal(
      port: chisel3.util.DecoupledIO[IfuInnerFlush],
      transactionId: Int,
      fetchSeq: Int,
      oldEpoch: Int,
      reason: IfuInnerFlushReason.Type,
      scope: IfuPruneScope.Type): Unit = {
    port.bits.poke(0.U.asTypeOf(port.bits))
    port.valid.poke(true.B)
    port.bits.valid.poke(true.B)
    port.bits.threadId.poke(0.U)
    port.bits.transactionId.poke(transactionId.U)
    port.bits.fetchSeq.poke(fetchSeq.U)
    port.bits.oldEpoch.poke(oldEpoch.U)
    port.bits.newEpoch.poke(0x3f.U)
    port.bits.reason.poke(reason)
    port.bits.scope.poke(scope)
  }

  test("assigns canonical monotonically increasing epochs even for late older corrections") {
    simulate(new IfuRedirectArbiter(p, threadCount = 1)) { dut =>
      clear(dut)
      dut.io.epochSeed.valid.poke(true.B)
      dut.io.epochSeed.bits.threadId.poke(0.U)
      dut.io.epochSeed.bits.epoch.poke(4.U)
      dut.clock.step()

      clear(dut)
      pokeProposal(
        dut.io.prediction,
        transactionId = 11,
        fetchSeq = 11,
        oldEpoch = 4,
        reason = IfuInnerFlushReason.PredictionCorrection,
        scope = IfuPruneScope.PreserveTriggerKillYounger)
      dut.io.prediction.ready.expect(true.B)
      dut.clock.step()

      clear(dut)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.newEpoch.expect(5.U)
      dut.io.out.bits.oldEpoch.expect(4.U)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()

      clear(dut)
      pokeProposal(
        dut.io.prediction,
        transactionId = 10,
        fetchSeq = 10,
        oldEpoch = 4,
        reason = IfuInnerFlushReason.PredictionCorrection,
        scope = IfuPruneScope.PreserveTriggerKillYounger)
      dut.io.prediction.ready.expect(true.B)
      dut.clock.step()

      clear(dut)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.transactionId.expect(10.U)
      dut.io.out.bits.newEpoch.expect(6.U)
      dut.io.epochs(0).expect(6.U)
    }
  }

  test("gives backend priority over ITLB and prediction proposals") {
    simulate(new IfuRedirectArbiter(p, threadCount = 1)) { dut =>
      clear(dut)
      pokeProposal(
        dut.io.backend,
        transactionId = 1,
        fetchSeq = 1,
        oldEpoch = 0,
        reason = IfuInnerFlushReason.FetchReplay,
        scope = IfuPruneScope.KillAllThreadState)
      pokeProposal(
        dut.io.itlb,
        transactionId = 2,
        fetchSeq = 2,
        oldEpoch = 0,
        reason = IfuInnerFlushReason.ItlbMiss,
        scope = IfuPruneScope.KillTriggerAndYounger)
      pokeProposal(
        dut.io.prediction,
        transactionId = 3,
        fetchSeq = 3,
        oldEpoch = 0,
        reason = IfuInnerFlushReason.PredictionCorrection,
        scope = IfuPruneScope.PreserveTriggerKillYounger)

      dut.io.backend.ready.expect(true.B)
      dut.io.itlb.ready.expect(false.B)
      dut.io.prediction.ready.expect(false.B)
      dut.clock.step()

      clear(dut)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.transactionId.expect(1.U)
      dut.io.acceptedBackend.expect(false.B)
    }
  }
}
