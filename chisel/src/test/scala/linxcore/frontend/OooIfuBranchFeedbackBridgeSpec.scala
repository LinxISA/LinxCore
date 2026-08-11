package linxcore.frontend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.common.BoundaryKind
import linxcore.params.SimulationParamProfiles
import linxcore.top.interface.{BranchValidationKind, RecoveryCause}
import org.scalatest.funsuite.AnyFunSuite

class OooIfuBranchFeedbackBridgeSpec extends AnyFunSuite with ChiselSim {
  private val p = SimulationParamProfiles.W2

  private def clear(dut: OooIfuBranchFeedbackBridge): Unit = {
    dut.io.validation.valid.poke(false.B)
    dut.io.validation.bits.poke(0.U.asTypeOf(dut.io.validation.bits))
    dut.io.resolve.ready.poke(true.B)
    dut.io.recovery.ready.poke(true.B)
    dut.reset.poke(true.B)
    dut.clock.step()
    dut.reset.poke(false.B)
  }

  private def pokeConditional(
      dut: OooIfuBranchFeedbackBridge,
      predictedTaken: Boolean,
      actualTaken: Boolean): Unit = {
    val event = dut.io.validation.bits
    event.poke(0.U.asTypeOf(event))
    event.rob.peId.poke(3.U)
    event.rob.stid.poke(0.U)
    event.rob.ridSlot.poke(7.U)
    event.rob.ridGeneration.poke(2.U)
    event.rob.memberIndex.poke(1.U)
    event.rob.residentGeneration.poke(4.U)
    event.rob.bid.poke(9.U)
    event.rob.brobGeneration.poke(5.U)
    event.instruction.peId.poke(3.U)
    event.instruction.stid.poke(0.U)
    event.instruction.instructionId.poke(91.U)
    event.instruction.epoch.poke(11.U)
    event.kind.poke(BranchValidationKind.Condition)
    event.predictionValid.poke(true.B)
    event.predictionTag.poke(55.U)
    event.predictionTransactionId.poke(56.U)
    event.fetchPacketUid.poke(57.U)
    event.fetchSeq.poke(58.U)
    event.predictionEpoch.poke(11.U)
    event.checkpointId.poke(1.U)
    event.requestPc.poke(0x1000.U)
    event.predictedTaken.poke(predictedTaken.B)
    event.predictedBranchPc.poke(0x1010.U)
    event.predictedTarget.poke(0x1080.U)
    event.predictedFallthroughPc.poke(0x1020.U)
    event.predictedKind.poke(BoundaryKind.Cond)
    event.actualTaken.poke(actualTaken.B)
    event.actualBranchPc.poke(0x1010.U)
    event.actualTarget.poke(0x1080.U)
    event.actualFallthroughPc.poke(0x1020.U)
    event.actualKind.poke(BoundaryKind.Cond)
    dut.io.validation.valid.poke(true.B)
  }

  test("publishes conditional training without recovery when prediction matches") {
    simulate(new OooIfuBranchFeedbackBridge(p)) { dut =>
      clear(dut)
      pokeConditional(dut, predictedTaken = false, actualTaken = false)
      dut.io.validation.ready.expect(true.B)
      dut.clock.step()
      dut.io.validation.valid.poke(false.B)

      dut.io.resolve.valid.expect(true.B)
      dut.io.resolve.bits.mispredict.expect(false.B)
      dut.io.resolve.bits.taken.expect(false.B)
      dut.io.recovery.valid.expect(false.B)
      dut.clock.step()
    }
  }

  test("publishes exact ROB recovery atomically with mismatching training") {
    simulate(new OooIfuBranchFeedbackBridge(p)) { dut =>
      clear(dut)
      dut.io.resolve.ready.poke(false.B)
      pokeConditional(dut, predictedTaken = false, actualTaken = true)
      dut.clock.step()
      dut.io.validation.valid.poke(false.B)

      dut.io.resolve.valid.expect(true.B)
      dut.io.resolve.bits.mispredict.expect(true.B)
      dut.io.recovery.valid.expect(false.B)
      dut.io.resolve.ready.poke(true.B)
      dut.io.recovery.ready.poke(true.B)
      dut.io.resolve.valid.expect(true.B)
      dut.io.resolve.bits.mispredict.expect(true.B)
      dut.io.recovery.valid.expect(true.B)
      dut.io.recovery.bits.cause.expect(RecoveryCause.Branch)
      dut.io.recovery.bits.trigger.ridSlot.expect(7.U)
      dut.io.recovery.bits.trigger.memberIndex.expect(1.U)
      dut.io.recovery.bits.redirectPc.expect(0x1080.U)
      dut.clock.step()
    }
  }
}
