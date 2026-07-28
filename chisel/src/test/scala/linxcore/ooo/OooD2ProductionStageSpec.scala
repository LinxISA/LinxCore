package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

class OooD2ProductionStageSpec extends AnyFunSuite with ChiselSim {
  private def clearBuffer(dut: OooD2ThreadStageBuffer): Unit = {
    dut.io.in.valid.poke(false.B)
    dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
    dut.io.fence.foreach(_.poke(false.B))
    dut.io.cancel.foreach(_.poke(false.B))
    dut.io.out.ready.poke(false.B)
  }

  private def pokeTransaction(
      dut: OooD2ThreadStageBuffer,
      stid: Int,
      transactionId: Int,
      tailEpoch: Int): Unit = {
    dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
    dut.io.in.bits.plan.stid.poke(stid.U)
    dut.io.in.bits.plan.peId.poke(2.U)
    dut.io.in.bits.plan.epoch.poke(3.U)
    dut.io.in.bits.plan.transactionId.poke(transactionId.U)
    dut.io.in.bits.plan.virtualTailEpoch.poke(tailEpoch.U)
    dut.io.in.bits.decoded.stid.poke(stid.U)
    dut.io.in.bits.decoded.peId.poke(2.U)
    dut.io.in.bits.decoded.epoch.poke(3.U)
    dut.io.in.valid.poke(true.B)
  }

  test("retains private STID previews and holds one shared grant under backpressure") {
    val p = OooParams()
    simulate(new OooD2ThreadStageBuffer(p)) { dut =>
      clearBuffer(dut)
      pokeTransaction(dut, stid = 0, transactionId = 10, tailEpoch = 4)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()

      pokeTransaction(dut, stid = 2, transactionId = 20, tailEpoch = 7)
      dut.io.in.ready.expect(true.B)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.plan.stid.expect(0.U)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)

      dut.io.occupancy.expect(2.U)
      dut.io.out.bits.plan.stid.expect(0.U)
      dut.io.out.bits.plan.transactionId.expect(10.U)
      dut.io.out.bits.plan.virtualTailEpoch.expect(4.U)
      dut.clock.step(3)
      dut.io.out.bits.plan.transactionId.expect(10.U)

      dut.io.cancel(0).poke(true.B)
      dut.clock.step()
      dut.io.cancel(0).poke(false.B)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.plan.stid.expect(2.U)
      dut.io.out.bits.plan.transactionId.expect(20.U)
      dut.io.out.bits.plan.virtualTailEpoch.expect(7.U)
    }
  }

  test("retains the planner tail epoch even when the live snapshot advances") {
    val p = OooParams()
    simulate(new OooD2ProductionStage(p)) { dut =>
      dut.io.in.valid.poke(false.B)
      dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
      dut.io.tailSlot.foreach(_.poke(0.U))
      dut.io.tailGeneration.foreach(_.poke(0.U))
      dut.io.tailEpoch.foreach(_.poke(0.U))
      dut.io.nextTransactionId.foreach(_.poke(0.U))
      dut.io.fence.foreach(_.poke(false.B))
      dut.io.cancel.foreach(_.poke(false.B))
      dut.io.out.ready.poke(false.B)

      dut.io.tailSlot(1).poke(9.U)
      dut.io.tailGeneration(1).poke(2.U)
      dut.io.tailEpoch(1).poke(6.U)
      dut.io.nextTransactionId(1).poke(100.U)
      dut.io.in.bits.peId.poke(3.U)
      dut.io.in.bits.stid.poke(1.U)
      dut.io.in.bits.epoch.poke(8.U)
      dut.io.in.bits.uopMask.poke(1.U)
      dut.io.in.bits.acceptedInstructionMask.poke(1.U)
      val uop = dut.io.in.bits.uops(0)
      uop.valid.poke(true.B)
      uop.plannedChildCount.poke(1.U)
      uop.identity.parentCount.poke(1.U)
      uop.identity.parents(0).key.valid.poke(true.B)
      uop.identity.parents(0).key.peId.poke(3.U)
      uop.identity.parents(0).key.stid.poke(1.U)
      uop.identity.parents(0).key.epoch.poke(8.U)
      uop.identity.parents(0).traceOwner.poke(true.B)
      dut.io.in.valid.poke(true.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.io.tailEpoch(1).poke(7.U)

      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.plan.transactionId.expect(100.U)
      dut.io.out.bits.plan.firstVirtualGroup.ridSlot.expect(9.U)
      dut.io.out.bits.plan.firstVirtualGroup.ridGeneration.expect(2.U)
      dut.io.out.bits.plan.virtualTailEpoch.expect(6.U)
      dut.clock.step(2)
      dut.io.out.bits.plan.virtualTailEpoch.expect(6.U)
    }
  }


  test("fence preserves a retained D2 preview and lets an unrelated STID drain") {
    val p = OooParams()
    simulate(new OooD2ThreadStageBuffer(p)) { dut =>
      clearBuffer(dut)
      pokeTransaction(dut, stid = 1, transactionId = 31, tailEpoch = 2)
      dut.clock.step()
      pokeTransaction(dut, stid = 3, transactionId = 33, tailEpoch = 4)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)

      dut.io.fence(1).poke(true.B)
      dut.io.out.ready.poke(true.B)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.plan.stid.expect(3.U)
      dut.clock.step()
      dut.io.occupancy.expect(1.U)

      dut.io.fence(1).poke(false.B)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.plan.transactionId.expect(31.U)
    }
  }
}
