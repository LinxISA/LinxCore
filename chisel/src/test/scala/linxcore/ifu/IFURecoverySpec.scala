package linxcore.ifu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.common.{BoundaryKind, InterfaceParams}
import linxcore.frontend._
import org.scalatest.funsuite.AnyFunSuite

class IFURecoverySpec extends AnyFunSuite with ChiselSim {
  private val p = InterfaceParams()

  private def clear(dut: IFURecovery): Unit = {
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
      reason: IfuInnerFlushReason.Type,
      restartPc: BigInt): Unit = {
    port.bits.poke(0.U.asTypeOf(port.bits))
    port.valid.poke(true.B)
    port.bits.valid.poke(true.B)
    port.bits.threadId.poke(0.U)
    port.bits.transactionId.poke(transactionId.U)
    port.bits.fetchSeq.poke(transactionId.U)
    port.bits.oldEpoch.poke(0.U)
    port.bits.restartPc.poke(restartPc.U)
    port.bits.reason.poke(reason)
    port.bits.scope.poke(IfuPruneScope.KillAllThreadState)
  }

  test("backend typed recovery overrides a held prediction correction") {
    simulate(new IFURecovery(p, threadCount = 1)) { dut =>
      clear(dut)
      pokeProposal(dut.io.prediction, 7, IfuInnerFlushReason.PredictionCorrection, 0x100)
      dut.io.prediction.ready.expect(true.B)
      dut.clock.step()

      clear(dut)
      pokeProposal(dut.io.backend, 7, IfuInnerFlushReason.BruRecovery, 0x200)
      dut.io.out.ready.poke(true.B)
      dut.io.out.valid.expect(false.B)
      dut.io.backend.ready.expect(true.B)
      dut.io.acceptedBackend.expect(true.B)
      dut.clock.step()

      clear(dut)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.reason.expect(IfuInnerFlushReason.BruRecovery)
      dut.io.out.bits.restartPc.expect(0x200.U)
    }
  }

  test("redirect priority and retained hold preserve backpressure") {
    simulate(new IFURecovery(p, threadCount = 1)) { dut =>
      clear(dut)
      pokeProposal(dut.io.backend, 1, IfuInnerFlushReason.BruRecovery, 0x1000)
      pokeProposal(dut.io.itlb, 2, IfuInnerFlushReason.ItlbMiss, 0x2000)
      pokeProposal(dut.io.prediction, 3, IfuInnerFlushReason.PredictionCorrection, 0x3000)
      dut.io.backend.ready.expect(true.B)
      dut.io.itlb.ready.expect(false.B)
      dut.io.prediction.ready.expect(false.B)
      dut.clock.step()

      clear(dut)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.transactionId.expect(1.U)
      dut.io.backend.ready.expect(false.B)
      dut.clock.step(3)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.restartPc.expect(0x1000.U)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()
      dut.io.out.valid.expect(false.B)
    }
  }

  test("backend feedback accepts only OOO-authored validation and emits no IEX control port") {
    val sv = circt.stage.ChiselStage.emitSystemVerilog(
      new IFUBackendFeedback(p))
    assert(sv.contains("module IFUBackendFeedback"))
    assert(sv.contains("validation"))
    assert(!sv.contains("iex"))
    assert(!sv.contains("Iex"))
  }

  test("mispredict training and recovery remain atomic at the OOO feedback boundary") {
    simulate(new IFUBackendFeedback(p)) { dut =>
      dut.io.validation.valid.poke(false.B)
      dut.io.validation.bits.poke(0.U.asTypeOf(dut.io.validation.bits))
      dut.io.resolve.ready.poke(false.B)
      dut.io.backendRecovery.ready.poke(false.B)

      val event = dut.io.validation.bits
      event.uop.valid.poke(true.B)
      event.uop.peId.poke(3.U)
      event.uop.threadId.poke(0.U)
      event.uop.checkpointId.poke(7.U)
      event.uop.uid.fetchPacketUid.poke(0x66.U)
      event.uop.prediction.valid.poke(true.B)
      event.uop.prediction.predictionTag.poke(0x44.U)
      event.uop.prediction.transactionId.poke(0x55.U)
      event.uop.prediction.fetchPacketUid.poke(0x66.U)
      event.uop.prediction.fetchSeq.poke(0x77.U)
      event.uop.prediction.requestPc.poke(0x2000.U)
      event.uop.prediction.taken.poke(false.B)
      event.uop.prediction.branchPc.poke(0x2080.U)
      event.uop.prediction.target.poke(0x3000.U)
      event.uop.prediction.fallthroughPc.poke(0x2100.U)
      event.uop.prediction.kind.poke(BoundaryKind.Cond)
      event.uop.prediction.provider.poke(PredictionProvider.LongTage.asUInt)
      event.uop.prediction.stage.poke(BSideStage.BF4.asUInt)
      event.uop.prediction.confidence.poke(3.U)
      event.uop.prediction.checkpointId.poke(7.U)
      event.uop.prediction.epoch.poke(2.U)
      event.point.poke(BranchValidationPoint.BruE1)
      event.setcKind.poke(SetcValidationKind.Condition)
      event.actualTaken.poke(true.B)
      event.actualBranchPc.poke(0x2080.U)
      event.actualTarget.poke(0x5000.U)
      event.actualFallthroughPc.poke(0x2100.U)
      event.actualKind.poke(BoundaryKind.Cond)
      dut.io.validation.valid.poke(true.B)
      dut.io.validation.ready.expect(true.B)
      dut.clock.step()
      dut.io.validation.valid.poke(false.B)

      dut.io.resolve.ready.poke(true.B)
      dut.io.backendRecovery.ready.poke(false.B)
      dut.io.resolve.valid.expect(false.B)
      dut.io.backendRecovery.valid.expect(true.B)
      dut.clock.step()

      dut.io.backendRecovery.ready.poke(true.B)
      dut.io.resolve.valid.expect(true.B)
      dut.io.backendRecovery.valid.expect(true.B)
      dut.io.resolve.bits.mispredict.expect(true.B)
      dut.io.backendRecovery.bits.restartPc.expect(0x5000.U)
    }
  }
}
