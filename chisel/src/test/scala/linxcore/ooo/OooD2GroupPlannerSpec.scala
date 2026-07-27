package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

class OooD2GroupPlannerSpec extends AnyFunSuite with ChiselSim {
  private def clear(dut: OooD2GroupPlanner): Unit = {
    dut.io.in.valid.poke(false.B)
    dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
    dut.io.out.ready.poke(true.B)
    dut.io.tailSlot.foreach(_.poke(0.U))
    dut.io.tailGeneration.foreach(_.poke(0.U))
    dut.io.tailEpoch.foreach(_.poke(0.U))
    dut.io.nextTransactionId.foreach(_.poke(0.U))
  }

  private def driveUop(
      dut: OooD2GroupPlanner,
      index: Int,
      instructionId: Int,
      plannedChildren: Int = 1,
      boundaryStart: Boolean = false,
      boundaryStop: Boolean = false,
      predictedTaken: Boolean = false,
      preciseTrap: Boolean = false,
      traceParentCount: Int = 1): Unit = {
    val uop = dut.io.in.bits.uops(index)
    uop.valid.poke(true.B)
    uop.plannedChildCount.poke(plannedChildren.U)
    uop.preciseTrap.poke(preciseTrap.B)
    uop.identity.key.primaryParent.valid.poke(true.B)
    uop.identity.key.primaryParent.peId.poke(2.U)
    uop.identity.key.primaryParent.stid.poke(1.U)
    uop.identity.key.primaryParent.instructionId.poke(instructionId.U)
    uop.identity.key.primaryParent.epoch.poke(7.U)
    uop.identity.parentCount.poke(traceParentCount.U)
    for (parentIndex <- 0 until traceParentCount) {
      val parent = uop.identity.parents(parentIndex)
      parent.key.valid.poke(true.B)
      parent.key.peId.poke(2.U)
      parent.key.stid.poke(1.U)
      parent.key.instructionId.poke((instructionId + parentIndex).U)
      parent.key.epoch.poke(7.U)
      parent.traceOwner.poke(true.B)
      parent.prediction.valid.poke(true.B)
      parent.prediction.taken.poke((predictedTaken && parentIndex == 0).B)
      parent.prediction.epoch.poke(7.U)
    }
    uop.identity.boundary.start.poke(boundaryStart.B)
    uop.identity.boundary.stop.poke(boundaryStop.B)
  }

  private def drivePacket(dut: OooD2GroupPlanner, uopCount: Int): Unit = {
    dut.io.in.bits.peId.poke(2.U)
    dut.io.in.bits.stid.poke(1.U)
    dut.io.in.bits.epoch.poke(7.U)
    dut.io.in.bits.uopMask.poke(((1 << uopCount) - 1).U)
    dut.io.in.bits.acceptedInstructionMask.poke(((1 << math.min(uopCount, 4)) - 1).U)
    dut.io.in.valid.poke(true.B)
  }

  test("packs four ordinary parents into one virtual group without physical mutation") {
    val p = OooParams()
    simulate(new OooD2GroupPlanner(p)) { dut =>
      clear(dut)
      for (index <- 0 until 4) driveUop(dut, index, 10 + index)
      drivePacket(dut, 4)
      dut.io.tailSlot(1).poke(5.U)
      dut.io.tailGeneration(1).poke(3.U)
      dut.io.tailEpoch(1).poke(11.U)
      dut.io.nextTransactionId(1).poke(99.U)

      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.plan.transactionId.expect(99.U)
      dut.io.out.bits.plan.virtualTailEpoch.expect(11.U)
      dut.io.out.bits.plan.groupCount.expect(1.U)
      dut.io.out.bits.plan.demand.robGroups.expect(1.U)
      dut.io.out.bits.groupMask.expect(1.U)
      dut.io.out.bits.groups(0).key.ridSlot.expect(5.U)
      dut.io.out.bits.groups(0).key.ridGeneration.expect(3.U)
      dut.io.out.bits.groups(0).logicalUopMask.expect("b00001111".U)
      dut.io.out.bits.groups(0).architecturalParentCount.expect(4.U)
      dut.io.out.bits.groups(0).physicalMemberCount.expect(4.U)
      for (index <- 0 until 4) {
        dut.io.out.bits.uopGroupIndex(index).expect(0.U)
        dut.io.out.bits.uopMemberBase(index).expect(index.U)
      }
    }
  }

  test("starts and closes groups at explicit block boundaries") {
    val p = OooParams()
    simulate(new OooD2GroupPlanner(p)) { dut =>
      clear(dut)
      driveUop(dut, 0, 20)
      driveUop(dut, 1, 21, boundaryStart = true)
      driveUop(dut, 2, 22, boundaryStop = true)
      driveUop(dut, 3, 23)
      drivePacket(dut, 4)

      dut.io.out.bits.plan.groupCount.expect(3.U)
      dut.io.out.bits.groupMask.expect("b111".U)
      dut.io.out.bits.groups(0).logicalUopMask.expect("b00000001".U)
      dut.io.out.bits.groups(1).logicalUopMask.expect("b00000110".U)
      dut.io.out.bits.groups(2).logicalUopMask.expect("b00001000".U)
      dut.io.out.bits.groups(1).boundaryStart.expect(true.B)
      dut.io.out.bits.groups(1).boundaryStop.expect(true.B)
    }
  }

  test("splits before physical member overflow and computes member bases") {
    val p = OooParams()
    simulate(new OooD2GroupPlanner(p)) { dut =>
      clear(dut)
      for (index <- 0 until 4) {
        driveUop(dut, index, 30 + index, plannedChildren = 4)
      }
      drivePacket(dut, 4)

      dut.io.out.bits.plan.groupCount.expect(2.U)
      dut.io.out.bits.groups(0).logicalUopMask.expect("b00000111".U)
      dut.io.out.bits.groups(0).physicalMemberCount.expect(12.U)
      dut.io.out.bits.groups(1).logicalUopMask.expect("b00001000".U)
      dut.io.out.bits.groups(1).physicalMemberCount.expect(4.U)
      Seq(0, 4, 8, 0).zipWithIndex.foreach { case (base, index) =>
        dut.io.out.bits.uopMemberBase(index).expect(base.U)
      }
    }
  }

  test("closes at a PC release boundary and advances virtual generation on wrap") {
    val p = OooParams()
    simulate(new OooD2GroupPlanner(p)) { dut =>
      clear(dut)
      driveUop(dut, 0, 40)
      driveUop(dut, 1, 41, predictedTaken = true)
      driveUop(dut, 2, 42, boundaryStop = true)
      driveUop(dut, 3, 43)
      drivePacket(dut, 4)
      dut.io.tailSlot(1).poke((p.robGroupsPerStid - 1).U)
      dut.io.tailGeneration(1).poke(9.U)

      dut.io.out.bits.plan.groupCount.expect(3.U)
      dut.io.out.bits.groups(0).releasePcBase.expect(true.B)
      dut.io.out.bits.groups(0).key.ridSlot.expect((p.robGroupsPerStid - 1).U)
      dut.io.out.bits.groups(0).key.ridGeneration.expect(9.U)
      dut.io.out.bits.groups(1).key.ridSlot.expect(0.U)
      dut.io.out.bits.groups(1).key.ridGeneration.expect(10.U)
      dut.io.out.bits.groups(2).key.ridSlot.expect(1.U)
      dut.io.out.bits.groups(2).key.ridGeneration.expect(10.U)
    }
  }

  test("elaborates the virtual planner at every production instruction width") {
    Seq(2, 4, 6).foreach { width =>
      val p = OooParams(instructionDecodeWidth = width)
      simulate(new OooD2GroupPlanner(p)) { dut =>
        clear(dut)
        driveUop(dut, 0, 50 + width)
        drivePacket(dut, 1)
        dut.io.out.valid.expect(true.B)
        dut.io.out.bits.plan.groupCount.expect(1.U)
        dut.io.out.bits.groups(0).logicalUopMask.expect(1.U)
      }
    }
  }

  test("represents the independent four-parent cap in a two-wide configuration") {
    val p = OooParams(instructionDecodeWidth = 2)
    simulate(new OooD2GroupPlanner(p)) { dut =>
      clear(dut)
      driveUop(dut, 0, 60, traceParentCount = 3)
      driveUop(dut, 1, 63, traceParentCount = 1)
      drivePacket(dut, 2)
      dut.io.out.bits.plan.groupCount.expect(1.U)
      dut.io.out.bits.groups(0).architecturalParentCount.expect(4.U)
    }

    simulate(new OooD2GroupPlanner(p)) { dut =>
      clear(dut)
      driveUop(dut, 0, 70, traceParentCount = 3)
      driveUop(dut, 1, 73, traceParentCount = 2)
      drivePacket(dut, 2)
      dut.io.out.bits.plan.groupCount.expect(2.U)
      dut.io.out.bits.groups(0).architecturalParentCount.expect(3.U)
      dut.io.out.bits.groups(1).architecturalParentCount.expect(2.U)
    }
  }
}
