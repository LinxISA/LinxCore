package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

class OooD3ReservationAllocatorSpec extends AnyFunSuite with ChiselSim {
  private def clear(dut: OooD3ReservationAllocator): Unit = {
    dut.io.in.valid.poke(false.B)
    dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
    dut.io.release.valid.poke(false.B)
    dut.io.release.bits.poke(0.U.asTypeOf(dut.io.release.bits))
    dut.io.cancel.foreach(_.poke(false.B))
    dut.io.publishEligible.foreach(_.poke(true.B))
    dut.io.recoveryPrepare.valid.poke(false.B)
    dut.io.recoveryPrepare.bits.poke(
      0.U.asTypeOf(dut.io.recoveryPrepare.bits))
    dut.io.recoveryFire.poke(false.B)
    dut.io.out.ready.poke(false.B)
  }

  private def pokePlan(
      dut: OooD3ReservationAllocator,
      stid: Int,
      transactionId: Int,
      groupCount: Int,
      tailSlot: Int,
      tailGeneration: Int,
      tailEpoch: Int): Unit = {
    dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
    val plan = dut.io.in.bits.plan
    plan.stid.poke(stid.U)
    plan.peId.poke(2.U)
    plan.epoch.poke(3.U)
    plan.transactionId.poke(transactionId.U)
    plan.groupCount.poke(groupCount.U)
    dut.io.in.bits.groupMask.poke(((1 << groupCount) - 1).U)
    plan.virtualTailEpoch.poke(tailEpoch.U)
    plan.firstVirtualGroup.valid.poke(true.B)
    plan.firstVirtualGroup.peId.poke(2.U)
    plan.firstVirtualGroup.stid.poke(stid.U)
    plan.firstVirtualGroup.ridSlot.poke(tailSlot.U)
    plan.firstVirtualGroup.ridGeneration.poke(tailGeneration.U)
    dut.io.in.bits.decoded.peId.poke(2.U)
    dut.io.in.bits.decoded.stid.poke(stid.U)
    dut.io.in.bits.decoded.epoch.poke(3.U)
    for (groupIndex <- 0 until groupCount) {
      val absolute = tailSlot + groupIndex
      val group = dut.io.in.bits.groups(groupIndex)
      group.valid.poke(true.B)
      group.key.valid.poke(true.B)
      group.key.peId.poke(2.U)
      group.key.stid.poke(stid.U)
      group.key.ridSlot.poke((absolute % dut.p.robGroupsPerStid).U)
      group.key.ridGeneration.poke((tailGeneration + absolute / dut.p.robGroupsPerStid).U)
    }
    dut.io.in.valid.poke(true.B)
  }

  private def pokeRecoveryPlan(
      dut: OooD3ReservationAllocator,
      stid: Int,
      oldOccupied: Int,
      newOccupied: Int,
      oldTailSlot: Int,
      oldTailGeneration: Int,
      newTailSlot: Int,
      newTailGeneration: Int): Unit = {
    val plan = dut.io.recoveryPrepare.bits
    plan.poke(0.U.asTypeOf(plan))
    plan.valid.poke(true.B)
    plan.request.rename.key.member.group.valid.poke(true.B)
    plan.request.rename.key.member.group.peId.poke(2.U)
    plan.request.rename.key.member.group.stid.poke(stid.U)
    plan.request.rename.key.member.bid.valid.poke(true.B)
    plan.oldHead.valid.poke(true.B)
    plan.oldHead.peId.poke(2.U)
    plan.oldHead.stid.poke(stid.U)
    plan.oldHead.ridSlot.poke(0.U)
    plan.oldHead.ridGeneration.poke(0.U)
    plan.oldOccupied.poke(oldOccupied.U)
    plan.pivotOffset.poke(0.U)
    plan.survivingPivotValid.poke((newOccupied > 0).B)
    plan.newOccupied.poke(newOccupied.U)
    plan.killedGroupCount.poke((oldOccupied - newOccupied).U)
    plan.killedGroupMask.poke(((1 << (oldOccupied - newOccupied)) - 1).U)
    plan.oldTail.valid.poke(true.B)
    plan.oldTail.peId.poke(2.U)
    plan.oldTail.stid.poke(stid.U)
    plan.oldTail.ridSlot.poke(oldTailSlot.U)
    plan.oldTail.ridGeneration.poke(oldTailGeneration.U)
    plan.newTail.valid.poke(true.B)
    plan.newTail.peId.poke(2.U)
    plan.newTail.stid.poke(stid.U)
    plan.newTail.ridSlot.poke(newTailSlot.U)
    plan.newTail.ridGeneration.poke(newTailGeneration.U)
    dut.io.recoveryPrepare.valid.poke(true.B)
  }

  test("claims a fresh preview provisionally and publishes only on the S1 handshake") {
    val p = OooParams(robGroupsPerStid = 8)
    simulate(new OooD3ReservationAllocator(p)) { dut =>
      clear(dut)
      pokePlan(dut, 1, transactionId = 0, groupCount = 2, 0, 0, 0)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)

      dut.io.tailSlot(1).expect(2.U)
      dut.io.tailGeneration(1).expect(0.U)
      dut.io.tailEpoch(1).expect(1.U)
      dut.io.nextTransactionId(1).expect(1.U)
      dut.io.usedGroups(1).expect(2.U)
      dut.io.provisionalMask.expect("b0010".U)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.claimEpoch.expect(0.U)
      dut.io.out.bits.transaction.plan.transactionId.expect(0.U)
      dut.clock.step(2)
      dut.io.out.bits.transaction.plan.groupCount.expect(2.U)

      dut.io.out.ready.poke(true.B)
      dut.clock.step()
      dut.io.provisionalMask.expect(0.U)
      dut.io.usedGroups(1).expect(2.U)
    }
  }

  test("prepares exact ROB-tail recovery and cancels an unexposed provisional row") {
    val p = OooParams(robGroupsPerStid = 8)
    simulate(new OooD3ReservationAllocator(p)) { dut =>
      clear(dut)

      pokePlan(dut, 1, transactionId = 0, groupCount = 3, 0, 0, 0)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()
      dut.io.out.ready.poke(false.B)
      dut.io.usedGroups(1).expect(3.U)
      dut.io.publishedGroups(1).expect(3.U)

      pokePlan(dut, 0, transactionId = 0, groupCount = 1, 0, 0, 0)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.transaction.plan.stid.expect(0.U)
      dut.clock.step()

      pokePlan(dut, 1, transactionId = 1, groupCount = 2, 3, 0, 1)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.io.usedGroups(1).expect(5.U)
      dut.io.publishedGroups(1).expect(3.U)
      dut.io.tailSlot(1).expect(5.U)
      dut.io.tailEpoch(1).expect(2.U)

      pokeRecoveryPlan(dut, stid = 1, oldOccupied = 3, newOccupied = 1,
        oldTailSlot = 3, oldTailGeneration = 0,
        newTailSlot = 1, newTailGeneration = 0)
      dut.io.recoveryPrepareReady.expect(true.B)
      dut.io.recoveryRejected.valid.expect(false.B)
      dut.clock.step()
      dut.io.usedGroups(1).expect(5.U)
      dut.io.publishedGroups(1).expect(3.U)

      dut.io.recoveryFire.poke(true.B)
      dut.clock.step()
      dut.io.recoveryFire.poke(false.B)
      dut.io.recoveryPrepare.valid.poke(false.B)
      dut.io.usedGroups(1).expect(1.U)
      dut.io.publishedGroups(1).expect(1.U)
      dut.io.tailSlot(1).expect(1.U)
      dut.io.tailGeneration(1).expect(0.U)
      dut.io.tailEpoch(1).expect(3.U)
      dut.io.headSlot(1).expect(0.U)
      dut.io.headGeneration(1).expect(0.U)
      dut.io.provisionalMask.expect("b0001".U)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.transaction.plan.stid.expect(0.U)
    }
  }

  test("rejects a stale ROB recovery plan without allocator mutation") {
    val p = OooParams(robGroupsPerStid = 8)
    simulate(new OooD3ReservationAllocator(p)) { dut =>
      clear(dut)
      pokePlan(dut, 1, transactionId = 0, groupCount = 2, 0, 0, 0)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()
      dut.io.out.ready.poke(false.B)

      pokeRecoveryPlan(dut, stid = 1, oldOccupied = 2, newOccupied = 1,
        oldTailSlot = 3, oldTailGeneration = 0,
        newTailSlot = 1, newTailGeneration = 0)
      dut.io.recoveryPrepareReady.expect(false.B)
      dut.io.recoveryRejected.valid.expect(true.B)
      dut.clock.step()
      dut.io.recoveryPrepare.valid.poke(false.B)
      dut.io.usedGroups(1).expect(2.U)
      dut.io.publishedGroups(1).expect(2.U)
      dut.io.tailSlot(1).expect(2.U)
      dut.io.tailEpoch(1).expect(1.U)
    }
  }

  test("consumes a stale preview with zero allocator mutation") {
    val p = OooParams(robGroupsPerStid = 8)
    simulate(new OooD3ReservationAllocator(p)) { dut =>
      clear(dut)
      pokePlan(dut, 0, transactionId = 9, groupCount = 1, 0, 0, tailEpoch = 4)
      dut.io.in.ready.expect(true.B)
      dut.io.staleRejected.valid.expect(true.B)
      dut.io.staleRejected.bits.plannedTailEpoch.expect(4.U)
      dut.io.staleRejected.bits.liveTailEpoch.expect(0.U)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)

      dut.io.tailSlot(0).expect(0.U)
      dut.io.tailEpoch(0).expect(0.U)
      dut.io.nextTransactionId(0).expect(0.U)
      dut.io.usedGroups(0).expect(0.U)
      dut.io.provisionalMask.expect(0.U)

      pokePlan(dut, 0, transactionId = 1, groupCount = 1, 0, 0, tailEpoch = 0)
      dut.io.in.ready.expect(true.B)
      dut.io.staleRejected.valid.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.io.tailSlot(0).expect(0.U)
      dut.io.tailEpoch(0).expect(0.U)
      dut.io.nextTransactionId(0).expect(0.U)
      dut.io.usedGroups(0).expect(0.U)

      pokePlan(dut, 0, transactionId = 0, groupCount = 1, 0, 0, tailEpoch = 0)
      dut.io.in.bits.groups(0).key.peId.poke(7.U)
      dut.io.in.ready.expect(true.B)
      dut.io.staleRejected.valid.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.io.tailSlot(0).expect(0.U)
      dut.io.tailEpoch(0).expect(0.U)
      dut.io.usedGroups(0).expect(0.U)

      pokePlan(dut, 0, transactionId = 0, groupCount = 1, 0, 0, tailEpoch = 0)
      dut.io.in.bits.plan.groupCount.poke(5.U)
      dut.io.in.ready.expect(true.B)
      dut.io.staleRejected.valid.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.io.usedGroups(0).expect(0.U)

      pokePlan(dut, 0, transactionId = 0, groupCount = 1, 0, 0, tailEpoch = 0)
      dut.io.in.bits.groups(1).valid.poke(true.B)
      dut.io.in.bits.groups(1).key.valid.poke(true.B)
      dut.io.in.ready.expect(true.B)
      dut.io.staleRejected.valid.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.io.usedGroups(0).expect(0.U)
    }
  }

  test("skips an ineligible STID and publishes another provisional row") {
    val p = OooParams(robGroupsPerStid = 8)
    simulate(new OooD3ReservationAllocator(p)) { dut =>
      clear(dut)
      pokePlan(dut, 0, transactionId = 0, groupCount = 1, 0, 0, 0)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.io.publishEligible(0).poke(false.B)
      dut.io.out.valid.expect(false.B)

      pokePlan(dut, 1, transactionId = 0, groupCount = 1, 0, 0, 0)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.io.provisionalMask.expect("b0011".U)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.transaction.plan.stid.expect(1.U)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()
      dut.io.out.ready.poke(false.B)
      dut.io.provisionalMask.expect("b0001".U)

      dut.io.publishEligible(0).poke(true.B)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.transaction.plan.stid.expect(0.U)
    }
  }

  test("never withdraws an exposed grant when eligibility changes") {
    val p = OooParams(robGroupsPerStid = 8)
    simulate(new OooD3ReservationAllocator(p)) { dut =>
      clear(dut)
      pokePlan(dut, 0, transactionId = 0, groupCount = 1, 0, 0, 0)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.transaction.plan.transactionId.expect(0.U)
      dut.clock.step() // capture the exposed blocked grant

      dut.io.publishEligible(0).poke(false.B)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.transaction.plan.transactionId.expect(0.U)
      dut.clock.step(3)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.transaction.plan.transactionId.expect(0.U)

      dut.io.out.ready.poke(true.B)
      dut.clock.step()
      dut.io.provisionalMask.expect(0.U)
    }
  }

  test("rolls back only a canceled provisional claim and preserves published usage") {
    val p = OooParams(robGroupsPerStid = 8)
    simulate(new OooD3ReservationAllocator(p)) { dut =>
      clear(dut)
      pokePlan(dut, 2, transactionId = 0, groupCount = 2, 0, 0, 0)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()
      dut.io.out.ready.poke(false.B)

      pokePlan(dut, 2, transactionId = 1, groupCount = 3, 2, 0, 1)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.io.tailSlot(2).expect(5.U)
      dut.io.usedGroups(2).expect(5.U)

      dut.io.cancel(2).poke(true.B)
      dut.clock.step()
      dut.io.cancel(2).poke(false.B)
      dut.io.tailSlot(2).expect(2.U)
      dut.io.tailGeneration(2).expect(0.U)
      dut.io.tailEpoch(2).expect(3.U)
      dut.io.usedGroups(2).expect(2.U)
      dut.io.provisionalMask.expect(0.U)
    }
  }

  test("rejects inexact release and encodes retire width independently of decode width") {
    val p = OooParams(instructionDecodeWidth = 2, retireGroupWidth = 4, robGroupsPerStid = 8)
    simulate(new OooD3ReservationAllocator(p)) { dut =>
      clear(dut)

      pokePlan(dut, 0, transactionId = 0, groupCount = 2, 0, 0, 0)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()
      dut.io.out.ready.poke(false.B)

      pokePlan(dut, 0, transactionId = 1, groupCount = 2, 2, 0, 1)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()
      dut.io.out.ready.poke(false.B)
      pokePlan(dut, 0, transactionId = 2, groupCount = 2, 4, 0, 2)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()
      dut.io.out.ready.poke(false.B)
      dut.io.usedGroups(0).expect(6.U)
      dut.io.publishedGroups(0).expect(6.U)

      dut.io.release.valid.poke(true.B)
      dut.io.release.bits.firstGroup.valid.poke(true.B)
      dut.io.release.bits.firstGroup.peId.poke(2.U)
      dut.io.release.bits.firstGroup.stid.poke(0.U)
      dut.io.release.bits.firstGroup.ridSlot.poke(0.U)
      dut.io.release.bits.firstGroup.ridGeneration.poke(1.U)
      dut.io.release.bits.headEpoch.poke(0.U)
      dut.io.release.bits.groupCount.poke(4.U)
      dut.io.release.ready.expect(false.B)
      dut.io.releaseRejected.valid.expect(true.B)
      dut.clock.step()
      dut.io.usedGroups(0).expect(6.U)
      dut.io.publishedGroups(0).expect(6.U)
      dut.io.headSlot(0).expect(0.U)

      dut.io.release.bits.firstGroup.ridGeneration.poke(0.U)
      dut.io.release.bits.groupCount.poke(5.U)
      dut.io.release.ready.expect(false.B)
      dut.io.releaseRejected.valid.expect(true.B)
      dut.clock.step()
      dut.io.usedGroups(0).expect(6.U)
      dut.io.publishedGroups(0).expect(6.U)

      dut.io.release.bits.groupCount.poke(4.U)
      dut.io.release.ready.expect(true.B)
      dut.io.releaseRejected.valid.expect(false.B)
      dut.clock.step()
      dut.io.release.valid.poke(false.B)
      dut.io.usedGroups(0).expect(2.U)
      dut.io.publishedGroups(0).expect(2.U)
      dut.io.headSlot(0).expect(4.U)
      dut.io.headGeneration(0).expect(0.U)
      dut.io.headEpoch(0).expect(1.U)
    }
  }
}
