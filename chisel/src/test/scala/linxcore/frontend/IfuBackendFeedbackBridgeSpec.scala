package linxcore.frontend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.common.{BoundaryKind, InterfaceParams}
import org.scalatest.funsuite.AnyFunSuite

class IfuBackendFeedbackBridgeSpec extends AnyFunSuite with ChiselSim {
  private val p = InterfaceParams()

  private def clear(dut: IfuBackendFeedbackBridge): Unit = {
    dut.io.validation.valid.poke(false.B)
    dut.io.validation.bits.poke(0.U.asTypeOf(dut.io.validation.bits))
    dut.io.resolve.ready.poke(false.B)
    dut.io.backendRecovery.ready.poke(false.B)
  }

  private def enqueue(
      dut: IfuBackendFeedbackBridge,
      point: BranchValidationPoint.Type,
      kind: BoundaryKind.Type,
      predictedTaken: Boolean,
      actualTaken: Boolean,
      predictedTarget: BigInt = 0x3000,
      actualTarget: BigInt = 0x3000,
      branchPc: BigInt = 0x2080,
      fallthroughPc: BigInt = 0x2100): Unit = {
    dut.io.validation.bits.poke(0.U.asTypeOf(dut.io.validation.bits))
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
    event.uop.prediction.taken.poke(predictedTaken.B)
    event.uop.prediction.branchPc.poke(branchPc.U)
    event.uop.prediction.target.poke(predictedTarget.U)
    event.uop.prediction.fallthroughPc.poke(fallthroughPc.U)
    event.uop.prediction.kind.poke(kind)
    event.uop.prediction.provider.poke(PredictionProvider.LongTage.asUInt)
    event.uop.prediction.stage.poke(BSideStage.BF4.asUInt)
    event.uop.prediction.confidence.poke(3.U)
    event.uop.prediction.checkpointId.poke(7.U)
    event.uop.prediction.epoch.poke(2.U)
    event.point.poke(point)
    event.setcKind.poke(
      if (point == BranchValidationPoint.Dispatch) SetcValidationKind.None
      else if (kind == BoundaryKind.Cond) SetcValidationKind.Condition
      else SetcValidationKind.Target)
    event.actualTaken.poke(actualTaken.B)
    event.actualBranchPc.poke(branchPc.U)
    event.actualTarget.poke(actualTarget.U)
    event.actualFallthroughPc.poke(fallthroughPc.U)
    event.actualKind.poke(kind)
    dut.io.validation.valid.poke(true.B)
    dut.io.validation.ready.expect(true.B)
    dut.clock.step()
    dut.io.validation.valid.poke(false.B)
  }

  test("conditional BRU validation compares direction but not target") {
    simulate(new IfuBackendFeedbackBridge(p)) { dut =>
      clear(dut)
      enqueue(
        dut,
        BranchValidationPoint.BruE1,
        BoundaryKind.Cond,
        predictedTaken = true,
        actualTaken = true,
        predictedTarget = 0x3000,
        actualTarget = 0x4000)

      dut.io.resolve.ready.poke(true.B)
      dut.io.backendRecovery.ready.poke(true.B)
      dut.io.pending.expect(true.B)
      dut.io.pendingMispredict.expect(false.B)
      dut.io.resolve.valid.expect(true.B)
      dut.io.resolve.bits.mispredict.expect(false.B)
      dut.io.resolve.bits.target.expect(0x4000.U)
      dut.io.resolve.bits.requestPc.expect(0x2000.U)
      dut.io.backendRecovery.valid.expect(false.B)
      dut.clock.step()
      dut.io.pending.expect(false.B)
    }
  }

  test("mispredict training and backend recovery advance atomically") {
    simulate(new IfuBackendFeedbackBridge(p)) { dut =>
      clear(dut)
      enqueue(
        dut,
        BranchValidationPoint.BruE1,
        BoundaryKind.Cond,
        predictedTaken = false,
        actualTaken = true,
        actualTarget = 0x5000)

      dut.io.resolve.ready.poke(true.B)
      dut.io.backendRecovery.ready.poke(false.B)
      dut.io.pendingMispredict.expect(true.B)
      dut.io.resolve.valid.expect(false.B)
      dut.io.backendRecovery.valid.expect(true.B)
      dut.clock.step(2)
      dut.io.pending.expect(true.B)

      dut.io.backendRecovery.ready.poke(true.B)
      dut.io.resolve.valid.expect(true.B)
      dut.io.backendRecovery.valid.expect(true.B)
      dut.io.resolve.bits.mispredict.expect(true.B)
      dut.io.backendRecovery.bits.reason.expect(IfuInnerFlushReason.BruRecovery)
      dut.io.backendRecovery.bits.scope.expect(IfuPruneScope.KillAllThreadState)
      dut.io.backendRecovery.bits.restartPc.expect(0x5000.U)
      dut.io.backendRecovery.bits.predictionTag.expect(0x44.U)
      dut.io.backendRecovery.bits.fetchPacketUid.expect(0x66.U)
      dut.io.backendRecovery.bits.fetchSeq.expect(0x77.U)
      dut.io.backendRecovery.bits.ghrAppendValid.expect(true.B)
      dut.io.backendRecovery.bits.ghrAppendTaken.expect(true.B)
      dut.clock.step()
      dut.io.pending.expect(false.B)
    }
  }

  test("Dispatch validates unconditional call target and requests RAS push") {
    simulate(new IfuBackendFeedbackBridge(p)) { dut =>
      clear(dut)
      enqueue(
        dut,
        BranchValidationPoint.Dispatch,
        BoundaryKind.Call,
        predictedTaken = true,
        actualTaken = true,
        predictedTarget = 0x3000,
        actualTarget = 0x3100,
        fallthroughPc = 0x2088)

      dut.io.resolve.ready.poke(true.B)
      dut.io.backendRecovery.ready.poke(true.B)
      dut.io.resolve.valid.expect(true.B)
      dut.io.resolve.bits.kind.expect(BoundaryKind.Call)
      dut.io.resolve.bits.mispredict.expect(true.B)
      dut.io.backendRecovery.valid.expect(true.B)
      dut.io.backendRecovery.bits.rasUpdate.expect(RasUpdateAction.Push)
      dut.io.backendRecovery.bits.rasPushAddress.expect(0x2088.U)
      dut.clock.step()
    }
  }

  test("BRU validates return target and restores fallthrough on conditional not-taken") {
    simulate(new IfuBackendFeedbackBridge(p)) { dut =>
      clear(dut)
      enqueue(
        dut,
        BranchValidationPoint.BruE1,
        BoundaryKind.Ret,
        predictedTaken = true,
        actualTaken = true,
        predictedTarget = 0x7000,
        actualTarget = 0x7100)

      dut.io.resolve.ready.poke(true.B)
      dut.io.backendRecovery.ready.poke(true.B)
      dut.io.backendRecovery.valid.expect(true.B)
      dut.io.backendRecovery.bits.restartPc.expect(0x7100.U)
      dut.io.backendRecovery.bits.rasUpdate.expect(RasUpdateAction.Pop)
      dut.io.backendRecovery.bits.ghrAppendValid.expect(false.B)
      dut.clock.step()

      enqueue(
        dut,
        BranchValidationPoint.BruE1,
        BoundaryKind.Cond,
        predictedTaken = true,
        actualTaken = false,
        fallthroughPc = 0x2200)
      dut.io.backendRecovery.bits.restartPc.expect(0x2200.U)
      dut.io.backendRecovery.bits.rasUpdate.expect(RasUpdateAction.None)
      dut.io.backendRecovery.bits.ghrAppendValid.expect(true.B)
      dut.io.backendRecovery.bits.ghrAppendTaken.expect(false.B)
      dut.clock.step()
    }
  }
}
