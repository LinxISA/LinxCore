package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

class OooS1GroupedRobSpec extends AnyFunSuite with ChiselSim {
  private def clear(dut: OooS1GroupedRob): Unit = {
    dut.io.publish.valid.poke(false.B)
    dut.io.publish.bits.poke(0.U.asTypeOf(dut.io.publish.bits))
    dut.io.completion.valid.poke(false.B)
    dut.io.completion.bits.poke(0.U.asTypeOf(dut.io.completion.bits))
    dut.io.commit.ready.poke(false.B)
  }

  private def pokePublication(
      dut: OooS1GroupedRob,
      stid: Int,
      transactionId: Int,
      firstSlot: Int,
      firstGeneration: Int,
      groupMembers: Seq[Int],
      initiallyComplete: Set[Int] = Set.empty,
      peId: Int = 2,
      claimEpoch: Int = 0,
      brobBid: Int = 7,
      brobGeneration: Int = 3,
      residentGeneration: Int = 5): Unit = {
    val p = dut.p
    dut.io.publish.bits.poke(0.U.asTypeOf(dut.io.publish.bits))
    val reservation = dut.io.publish.bits.reservation
    val transaction = reservation.transaction
    transaction.plan.peId.poke(peId.U)
    transaction.plan.stid.poke(stid.U)
    transaction.plan.epoch.poke(11.U)
    transaction.plan.transactionId.poke(transactionId.U)
    transaction.plan.groupCount.poke(groupMembers.size.U)
    transaction.plan.firstVirtualGroup.valid.poke(true.B)
    transaction.plan.firstVirtualGroup.peId.poke(peId.U)
    transaction.plan.firstVirtualGroup.stid.poke(stid.U)
    transaction.plan.firstVirtualGroup.ridSlot.poke(firstSlot.U)
    transaction.plan.firstVirtualGroup.ridGeneration.poke(firstGeneration.U)
    transaction.decoded.peId.poke(peId.U)
    transaction.decoded.stid.poke(stid.U)
    transaction.decoded.epoch.poke(11.U)
    transaction.groupMask.poke(((1 << groupMembers.size) - 1).U)
    reservation.claimEpoch.poke(claimEpoch.U)
    val absoluteTail = firstSlot + groupMembers.size
    reservation.tailAfter.valid.poke(true.B)
    reservation.tailAfter.peId.poke(peId.U)
    reservation.tailAfter.stid.poke(stid.U)
    reservation.tailAfter.ridSlot.poke((absoluteTail % p.robGroupsPerStid).U)
    reservation.tailAfter.ridGeneration.poke(
      (firstGeneration + absoluteTail / p.robGroupsPerStid).U)

    groupMembers.zipWithIndex.foreach { case (memberCount, groupIndex) =>
      val absoluteSlot = firstSlot + groupIndex
      val slot = absoluteSlot % p.robGroupsPerStid
      val generation = firstGeneration + absoluteSlot / p.robGroupsPerStid
      val group = transaction.groups(groupIndex)
      group.valid.poke(true.B)
      group.key.valid.poke(true.B)
      group.key.peId.poke(peId.U)
      group.key.stid.poke(stid.U)
      group.key.ridSlot.poke(slot.U)
      group.key.ridGeneration.poke(generation.U)
      group.logicalUopMask.poke((1 << groupIndex).U)
      group.physicalMemberCount.poke(memberCount.U)
      group.architecturalParentCount.poke(1.U)

      val binding = dut.io.publish.bits.bindings(groupIndex)
      binding.valid.poke(true.B)
      binding.brob.valid.poke(true.B)
      binding.brob.bid.valid.poke(true.B)
      binding.brob.bid.value.poke(brobBid.U)
      binding.brob.generation.poke(brobGeneration.U)
      binding.residentGeneration.poke(residentGeneration.U)
      val initialMask = if (initiallyComplete(groupIndex)) (1 << memberCount) - 1 else 0
      binding.initiallyCompletedMembers.poke(initialMask.U)
    }
    dut.io.publish.valid.poke(true.B)
  }

  private def pokeCompletion(
      dut: OooS1GroupedRob,
      stid: Int,
      slot: Int,
      ridGeneration: Int,
      member: Int,
      peId: Int = 2,
      brobBid: Int = 7,
      brobGeneration: Int = 3,
      residentGeneration: Int = 5): Unit = {
    dut.io.completion.bits.poke(0.U.asTypeOf(dut.io.completion.bits))
    val key = dut.io.completion.bits.key
    key.group.valid.poke(true.B)
    key.group.peId.poke(peId.U)
    key.group.stid.poke(stid.U)
    key.group.ridSlot.poke(slot.U)
    key.group.ridGeneration.poke(ridGeneration.U)
    key.bid.valid.poke(true.B)
    key.bid.value.poke(brobBid.U)
    key.brobGeneration.poke(brobGeneration.U)
    key.memberIndex.poke(member.U)
    key.residentGeneration.poke(residentGeneration.U)
    dut.io.completion.valid.poke(true.B)
  }

  private def acceptCompletion(dut: OooS1GroupedRob): Unit = {
    dut.io.completion.ready.expect(true.B)
    dut.io.completionRejected.valid.expect(false.B)
    dut.clock.step()
    dut.io.completion.valid.poke(false.B)
  }

  test("publishes every group atomically and retains an older-first commit batch") {
    val p = OooParams(robGroupsPerStid = 8)
    simulate(new OooS1GroupedRob(p)) { dut =>
      clear(dut)
      pokePublication(dut, stid = 1, transactionId = 0, firstSlot = 0,
        firstGeneration = 0, groupMembers = Seq(2, 1))
      dut.io.publish.ready.expect(true.B)
      dut.clock.step()
      dut.io.publish.valid.poke(false.B)
      dut.io.occupiedGroups(1).expect(2.U)

      pokeCompletion(dut, stid = 1, slot = 1, ridGeneration = 0, member = 0)
      acceptCompletion(dut)
      dut.clock.step()
      dut.io.commit.valid.expect(false.B)

      pokeCompletion(dut, stid = 1, slot = 0, ridGeneration = 0, member = 0)
      acceptCompletion(dut)
      pokeCompletion(dut, stid = 1, slot = 0, ridGeneration = 0, member = 1)
      acceptCompletion(dut)
      dut.clock.step()

      dut.io.commit.valid.expect(true.B)
      dut.io.commit.bits.release.firstGroup.stid.expect(1.U)
      dut.io.commit.bits.release.firstGroup.ridSlot.expect(0.U)
      dut.io.commit.bits.release.groupCount.expect(2.U)
      dut.io.commit.bits.groups(0).physicalMemberCount.expect(2.U)
      dut.io.commit.bits.groups(1).physicalMemberCount.expect(1.U)
      val heldTransaction = dut.io.commit.bits.groups(0).transactionId.peek().litValue
      val heldCount = dut.io.commit.bits.release.groupCount.peek().litValue
      dut.clock.step(2)
      dut.io.commit.bits.groups(0).transactionId.expect(heldTransaction.U)
      dut.io.commit.bits.release.groupCount.expect(heldCount.U)

      dut.io.commit.ready.poke(true.B)
      dut.clock.step()
      dut.io.commit.ready.poke(false.B)
      dut.io.commit.valid.expect(false.B)
      dut.io.occupiedGroups(1).expect(0.U)
      dut.io.headSlot(1).expect(2.U)
      dut.io.headEpoch(1).expect(1.U)
    }
  }

  test("consumes stale and duplicate completions without mutating the live row") {
    val p = OooParams(robGroupsPerStid = 8)
    simulate(new OooS1GroupedRob(p)) { dut =>
      clear(dut)
      pokePublication(dut, stid = 0, transactionId = 0, firstSlot = 0,
        firstGeneration = 0, groupMembers = Seq(2))
      dut.clock.step()
      dut.io.publish.valid.poke(false.B)

      pokeCompletion(dut, stid = 0, slot = 0, ridGeneration = 1, member = 0)
      dut.io.completionRejected.valid.expect(true.B)
      dut.clock.step()
      dut.io.completion.valid.poke(false.B)

      pokeCompletion(dut, stid = 0, slot = 0, ridGeneration = 0, member = 0,
        brobGeneration = 4)
      dut.io.completionRejected.valid.expect(true.B)
      dut.clock.step()
      dut.io.completion.valid.poke(false.B)

      pokeCompletion(dut, stid = 0, slot = 0, ridGeneration = 0, member = 2)
      dut.io.completionRejected.valid.expect(true.B)
      dut.clock.step()
      dut.io.completion.valid.poke(false.B)

      pokeCompletion(dut, stid = 0, slot = 0, ridGeneration = 0, member = 0)
      acceptCompletion(dut)
      pokeCompletion(dut, stid = 0, slot = 0, ridGeneration = 0, member = 0)
      dut.io.completionRejected.valid.expect(true.B)
      dut.clock.step()
      dut.io.completion.valid.poke(false.B)

      dut.clock.step()
      dut.io.commit.valid.expect(false.B)
      pokeCompletion(dut, stid = 0, slot = 0, ridGeneration = 0, member = 1)
      acceptCompletion(dut)
      dut.clock.step()
      dut.io.commit.valid.expect(true.B)
      dut.io.commit.bits.release.groupCount.expect(1.U)
    }
  }

  test("rejects a colliding multi-group publication without partial slot writes") {
    val p = OooParams(robGroupsPerStid = 8)
    simulate(new OooS1GroupedRob(p)) { dut =>
      clear(dut)
      pokePublication(dut, stid = 2, transactionId = 0, firstSlot = 0,
        firstGeneration = 0, groupMembers = Seq(1))
      dut.clock.step()

      pokePublication(dut, stid = 2, transactionId = 1, firstSlot = 0,
        firstGeneration = 0, groupMembers = Seq(1, 1))
      dut.io.publish.ready.expect(false.B)
      dut.io.publicationRejected.valid.expect(true.B)
      dut.clock.step()
      dut.io.occupiedGroups(2).expect(1.U)

      pokePublication(dut, stid = 2, transactionId = 1, firstSlot = 1,
        firstGeneration = 0, groupMembers = Seq(1))
      dut.io.publish.ready.expect(true.B)
      dut.clock.step()
      dut.io.publish.valid.poke(false.B)
      dut.io.occupiedGroups(2).expect(2.U)
    }
  }

  test("rejects a publication ahead of the live tail and an invalid BROB binding") {
    val p = OooParams(robGroupsPerStid = 8)
    simulate(new OooS1GroupedRob(p)) { dut =>
      clear(dut)
      pokePublication(dut, stid = 2, transactionId = 0, firstSlot = 0,
        firstGeneration = 0, groupMembers = Seq(1))
      dut.clock.step()

      pokePublication(dut, stid = 2, transactionId = 1, firstSlot = 2,
        firstGeneration = 0, groupMembers = Seq(1))
      dut.io.publish.ready.expect(false.B)
      dut.io.publicationRejected.valid.expect(true.B)
      dut.clock.step()
      dut.io.occupiedGroups(2).expect(1.U)

      pokePublication(dut, stid = 2, transactionId = 1, firstSlot = 1,
        firstGeneration = 0, groupMembers = Seq(1))
      dut.io.publish.bits.bindings(0).brob.valid.poke(false.B)
      dut.io.publish.ready.expect(false.B)
      dut.clock.step()
      dut.io.occupiedGroups(2).expect(1.U)

      pokePublication(dut, stid = 2, transactionId = 1, firstSlot = 1,
        firstGeneration = 0, groupMembers = Seq(1))
      dut.io.publish.ready.expect(true.B)
      dut.clock.step()
      dut.io.publish.valid.poke(false.B)
      dut.io.occupiedGroups(2).expect(2.U)
    }
  }

  test("keeps STIDs isolated and advances an exact wrapped head generation") {
    val p = OooParams(robGroupsPerStid = 8, retireGroupWidth = 4)
    simulate(new OooS1GroupedRob(p)) { dut =>
      clear(dut)
      pokePublication(dut, stid = 1, transactionId = 0, firstSlot = 0,
        firstGeneration = 0, groupMembers = Seq.fill(4)(1),
        initiallyComplete = Set(0, 1, 2, 3))
      dut.clock.step()
      dut.io.publish.valid.poke(false.B)
      dut.clock.step()
      dut.io.commit.ready.poke(true.B)
      dut.clock.step()
      dut.io.commit.ready.poke(false.B)

      pokePublication(dut, stid = 1, transactionId = 1, firstSlot = 4,
        firstGeneration = 0, groupMembers = Seq.fill(3)(1),
        initiallyComplete = Set(0, 1, 2))
      dut.clock.step()
      dut.io.publish.valid.poke(false.B)
      dut.clock.step()
      dut.io.commit.ready.poke(true.B)
      dut.clock.step()
      dut.io.commit.ready.poke(false.B)
      dut.io.headSlot(1).expect(7.U)
      dut.io.headGeneration(1).expect(0.U)

      pokePublication(dut, stid = 1, transactionId = 2, firstSlot = 7,
        firstGeneration = 0, groupMembers = Seq(1, 1), initiallyComplete = Set(0, 1))
      dut.clock.step()
      pokePublication(dut, stid = 3, transactionId = 0, firstSlot = 0,
        firstGeneration = 0, groupMembers = Seq(1))
      dut.clock.step()
      dut.io.publish.valid.poke(false.B)

      dut.clock.step()
      dut.io.commit.valid.expect(true.B)
      dut.io.commit.bits.release.firstGroup.stid.expect(1.U)
      dut.io.commit.bits.release.firstGroup.ridSlot.expect(7.U)
      dut.io.commit.bits.release.firstGroup.ridGeneration.expect(0.U)
      dut.io.commit.bits.release.groupCount.expect(2.U)
      dut.io.commit.ready.poke(true.B)
      dut.clock.step()
      dut.io.commit.ready.poke(false.B)
      dut.io.headSlot(1).expect(1.U)
      dut.io.headGeneration(1).expect(1.U)
      dut.io.occupiedGroups(1).expect(0.U)
      dut.io.occupiedGroups(3).expect(1.U)
      dut.clock.step(2)
      dut.io.commit.valid.expect(false.B)
    }
  }

  test("elaborates publication and retirement independently at 2 4 and 6 decode width") {
    Seq(2, 4, 6).foreach { width =>
      val p = OooParams(
        instructionDecodeWidth = width,
        retireGroupWidth = 4,
        robGroupsPerStid = 8)
      simulate(new OooS1GroupedRob(p)) { dut =>
        clear(dut)
        pokePublication(dut, stid = 0, transactionId = 0, firstSlot = 0,
          firstGeneration = 0, groupMembers = Seq.fill(width)(1),
          initiallyComplete = (0 until width).toSet)
        dut.io.publish.ready.expect(true.B)
        dut.clock.step()
        dut.io.publish.valid.poke(false.B)
        dut.clock.step()
        dut.io.commit.valid.expect(true.B)
        dut.io.commit.bits.release.groupCount.expect(math.min(width, 4).U)
      }
    }
  }
}
