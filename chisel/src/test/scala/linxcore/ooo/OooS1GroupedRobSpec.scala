package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.common.DestinationKind
import org.scalatest.funsuite.AnyFunSuite

class OooS1GroupedRobSpec extends AnyFunSuite with ChiselSim {
  private def clear(dut: OooS1GroupedRob): Unit = {
    dut.io.publish.valid.poke(false.B)
    dut.io.publish.bits.poke(0.U.asTypeOf(dut.io.publish.bits))
    dut.io.completion.valid.poke(false.B)
    dut.io.completion.bits.poke(0.U.asTypeOf(dut.io.completion.bits))
    dut.io.nonFlushEvidence.valid.poke(false.B)
    dut.io.nonFlushEvidence.bits.poke(0.U.asTypeOf(dut.io.nonFlushEvidence.bits))
    dut.io.interruptPending.foreach(_.poke(false.B))
    dut.io.recoveryPrepare.valid.poke(false.B)
    dut.io.recoveryPrepare.bits.poke(
      0.U.asTypeOf(dut.io.recoveryPrepare.bits))
    dut.io.recoveryFire.poke(false.B)
    dut.io.commit.ready.poke(false.B)
  }

  private def pokePublication(
      dut: OooS1GroupedRob,
      stid: Int,
      transactionId: Int,
      firstSlot: Int,
      firstGeneration: Int,
      groupMembers: Seq[Int],
      groupPMapRows: Seq[Int] = Seq.empty,
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
    transaction.decoded.uopMask.poke(((1 << groupMembers.size) - 1).U)
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
      group.pMapQRows.poke(groupPMapRows.lift(groupIndex).getOrElse(0).U)
      group.architecturalParentCount.poke(1.U)

      val uop = transaction.decoded.uops(groupIndex)
      uop.valid.poke(true.B)
      uop.recipe.valid.poke(true.B)
      uop.recipe.disposition.poke(OooOpcodeDisposition.Dispatch.U)
      uop.recipe.sideEffectOwner.poke(OooSideEffectOwner.Iex.U)
      uop.recipe.dispatchClass.poke(OooDispatchClass.Alu.U)
      uop.plannedChildCount.poke(memberCount.U)
      uop.identity.parentCount.poke(1.U)
      uop.identity.parents(0).key.valid.poke(true.B)
      uop.identity.parents(0).key.peId.poke(peId.U)
      uop.identity.parents(0).key.stid.poke(stid.U)
      uop.identity.parents(0).key.instructionId.poke(
        (100 + transactionId * 10 + groupIndex).U)
      uop.identity.parents(0).key.epoch.poke(11.U)
      uop.identity.parents(0).traceOwner.poke(true.B)

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

  private def pokeIntraGroupPublication(
      dut: OooS1GroupedRob,
      stid: Int = 1,
      transactionId: Int = 0,
      firstSlot: Int = 0,
      firstGeneration: Int = 0,
      peId: Int = 2): Unit = {
    val p = dut.p
    val memberCounts = Seq(1, 2, 1, 1)
    val groupIndexes = Seq(0, 0, 1, 2)
    val memberBases = Seq(0, 1, 0, 0)
    dut.io.publish.bits.poke(0.U.asTypeOf(dut.io.publish.bits))
    val reservation = dut.io.publish.bits.reservation
    val transaction = reservation.transaction
    transaction.plan.peId.poke(peId.U)
    transaction.plan.stid.poke(stid.U)
    transaction.plan.epoch.poke(11.U)
    transaction.plan.transactionId.poke(transactionId.U)
    transaction.plan.groupCount.poke(3.U)
    transaction.plan.firstVirtualGroup.valid.poke(true.B)
    transaction.plan.firstVirtualGroup.peId.poke(peId.U)
    transaction.plan.firstVirtualGroup.stid.poke(stid.U)
    transaction.plan.firstVirtualGroup.ridSlot.poke(firstSlot.U)
    transaction.plan.firstVirtualGroup.ridGeneration.poke(firstGeneration.U)
    transaction.decoded.peId.poke(peId.U)
    transaction.decoded.stid.poke(stid.U)
    transaction.decoded.epoch.poke(11.U)
    transaction.decoded.uopMask.poke(15.U)
    transaction.groupMask.poke(7.U)
    reservation.tailAfter.valid.poke(true.B)
    reservation.tailAfter.peId.poke(peId.U)
    reservation.tailAfter.stid.poke(stid.U)
    reservation.tailAfter.ridSlot.poke(((firstSlot + 3) % p.robGroupsPerStid).U)
    reservation.tailAfter.ridGeneration.poke(
      (firstGeneration + (firstSlot + 3) / p.robGroupsPerStid).U)

    val groupMasks = Seq(3, 4, 8)
    val physicalCounts = Seq(3, 1, 1)
    val pMapRows = Seq(2, 0, 0)
    val parentCounts = Seq(2, 1, 1)
    for (groupIndex <- 0 until 3) {
      val absoluteSlot = firstSlot + groupIndex
      val group = transaction.groups(groupIndex)
      group.valid.poke(true.B)
      group.key.valid.poke(true.B)
      group.key.peId.poke(peId.U)
      group.key.stid.poke(stid.U)
      group.key.ridSlot.poke((absoluteSlot % p.robGroupsPerStid).U)
      group.key.ridGeneration.poke(
        (firstGeneration + absoluteSlot / p.robGroupsPerStid).U)
      group.logicalUopMask.poke(groupMasks(groupIndex).U)
      group.physicalMemberCount.poke(physicalCounts(groupIndex).U)
      group.pMapQRows.poke(pMapRows(groupIndex).U)
      group.architecturalParentCount.poke(parentCounts(groupIndex).U)

      val binding = dut.io.publish.bits.bindings(groupIndex)
      binding.valid.poke(true.B)
      binding.brob.valid.poke(true.B)
      binding.brob.bid.valid.poke(true.B)
      binding.brob.bid.value.poke(7.U)
      binding.brob.generation.poke(3.U)
      binding.residentGeneration.poke(5.U)
    }

    for (uopIndex <- 0 until 4) {
      transaction.uopGroupIndex(uopIndex).poke(groupIndexes(uopIndex).U)
      transaction.uopMemberBase(uopIndex).poke(memberBases(uopIndex).U)
      val uop = transaction.decoded.uops(uopIndex)
      uop.valid.poke(true.B)
      uop.recipe.valid.poke(true.B)
      uop.recipe.disposition.poke(OooOpcodeDisposition.Dispatch.U)
      uop.recipe.sideEffectOwner.poke(OooSideEffectOwner.Iex.U)
      uop.recipe.dispatchClass.poke(OooDispatchClass.Alu.U)
      uop.plannedChildCount.poke(memberCounts(uopIndex).U)
      uop.identity.parentCount.poke(1.U)
      uop.identity.parents(0).key.valid.poke(true.B)
      uop.identity.parents(0).key.peId.poke(peId.U)
      uop.identity.parents(0).key.stid.poke(stid.U)
      uop.identity.parents(0).key.instructionId.poke((200 + uopIndex).U)
      uop.identity.parents(0).key.epoch.poke(11.U)
      uop.identity.parents(0).traceOwner.poke(true.B)
      if (uopIndex < 2) {
        uop.destinations(0).valid.poke(true.B)
        uop.destinations(0).kind.poke(DestinationKind.Gpr)
        uop.destinations(0).atag.poke((uopIndex + 1).U)
      }
    }
    dut.io.publish.valid.poke(true.B)
  }

  private def pokeRecovery(
      dut: OooS1GroupedRob,
      stid: Int,
      slot: Int,
      ridGeneration: Int,
      member: Int,
      memberCount: Int,
      killTrigger: Boolean,
      transactionId: Int = 0,
      epoch: Int = 11,
      peId: Int = 2): Unit = {
    val request = dut.io.recoveryPrepare.bits
    request.poke(0.U.asTypeOf(request))
    request.rename.key.member.group.valid.poke(true.B)
    request.rename.key.member.group.peId.poke(peId.U)
    request.rename.key.member.group.stid.poke(stid.U)
    request.rename.key.member.group.ridSlot.poke(slot.U)
    request.rename.key.member.group.ridGeneration.poke(ridGeneration.U)
    request.rename.key.member.bid.valid.poke(true.B)
    request.rename.key.member.bid.value.poke(7.U)
    request.rename.key.member.brobGeneration.poke(3.U)
    request.rename.key.member.memberIndex.poke(member.U)
    request.rename.key.member.residentGeneration.poke(5.U)
    request.rename.key.transactionId.poke(transactionId.U)
    request.rename.key.epoch.poke(epoch.U)
    request.rename.killTrigger.poke(killTrigger.B)
    request.triggerMemberCount.poke(memberCount.U)
    dut.io.recoveryPrepare.valid.poke(true.B)
  }

  private def acceptCompletion(dut: OooS1GroupedRob): Unit = {
    dut.io.completion.ready.expect(true.B)
    dut.io.completionRejected.valid.expect(false.B)
    dut.clock.step()
    dut.io.completion.valid.poke(false.B)
  }

  private def pokeNonFlushEvidence(
      dut: OooS1GroupedRob,
      stid: Int,
      slot: Int,
      ridGeneration: Int,
      proofs: Int,
      member: Int = 0,
      peId: Int = 2,
      brobBid: Int = 7,
      brobGeneration: Int = 3,
      residentGeneration: Int = 5): Unit = {
    dut.io.nonFlushEvidence.bits.poke(
      0.U.asTypeOf(dut.io.nonFlushEvidence.bits))
    val evidence = dut.io.nonFlushEvidence.bits
    evidence.key.group.valid.poke(true.B)
    evidence.key.group.peId.poke(peId.U)
    evidence.key.group.stid.poke(stid.U)
    evidence.key.group.ridSlot.poke(slot.U)
    evidence.key.group.ridGeneration.poke(ridGeneration.U)
    evidence.key.bid.valid.poke(true.B)
    evidence.key.bid.value.poke(brobBid.U)
    evidence.key.brobGeneration.poke(brobGeneration.U)
    evidence.key.memberIndex.poke(member.U)
    evidence.key.residentGeneration.poke(residentGeneration.U)
    evidence.proofs.poke(proofs.U)
    dut.io.nonFlushEvidence.valid.poke(true.B)
  }

  private def acceptNonFlushEvidence(dut: OooS1GroupedRob): Unit = {
    dut.io.nonFlushEvidence.ready.expect(true.B)
    dut.io.nonFlushEvidenceRejected.valid.expect(false.B)
    dut.clock.step()
    dut.io.nonFlushEvidence.valid.poke(false.B)
  }

  test("admits parentless template continuations but requires the final parent") {
    val p = OooParams(robGroupsPerStid = 8)
    simulate(new OooS1GroupedRob(p)) { dut =>
      clear(dut)
      pokePublication(
        dut,
        stid = 1,
        transactionId = 90,
        firstSlot = 0,
        firstGeneration = 0,
        groupMembers = Seq(1),
        initiallyComplete = Set(0))
      val transaction = dut.io.publish.bits.reservation.transaction
      val group = transaction.groups(0)
      val uop = transaction.decoded.uops(0)
      group.architecturalParentCount.poke(0.U)
      uop.identity.parents(0).traceOwner.poke(false.B)
      uop.identity.templateValid.poke(true.B)
      uop.identity.key.uopOrdinal.poke(0.U)
      uop.identity.key.uopCount.poke(4.U)
      dut.io.publish.ready.expect(true.B)
      dut.clock.step()
      dut.io.publish.valid.poke(false.B)
      dut.clock.step()
      dut.io.commit.valid.expect(true.B)
      dut.io.commit.bits.groups(0).architecturalParentCount.expect(0.U)

      pokePublication(
        dut,
        stid = 2,
        transactionId = 91,
        firstSlot = 0,
        firstGeneration = 0,
        groupMembers = Seq(1))
      val finalTransaction = dut.io.publish.bits.reservation.transaction
      val finalGroup = finalTransaction.groups(0)
      val finalUop = finalTransaction.decoded.uops(0)
      finalGroup.architecturalParentCount.poke(0.U)
      finalUop.identity.parents(0).traceOwner.poke(false.B)
      finalUop.identity.templateValid.poke(true.B)
      finalUop.identity.key.uopOrdinal.poke(3.U)
      finalUop.identity.key.uopCount.poke(4.U)
      dut.io.publish.ready.expect(false.B)
    }
  }

  test("publishes every group atomically and retains an older-first commit batch") {
    val p = OooParams(robGroupsPerStid = 8)
    simulate(new OooS1GroupedRob(p)) { dut =>
      clear(dut)
      pokePublication(dut, stid = 1, transactionId = 0, firstSlot = 0,
        firstGeneration = 0, groupMembers = Seq(2, 1),
        groupPMapRows = Seq(2, 1))
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
      dut.io.commit.bits.groups(0).pMapQRows.expect(2.U)
      dut.io.commit.bits.groups(1).pMapQRows.expect(1.U)
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

  test("advances only an exact typed safe prefix and never treats evidence as commit") {
    val p = OooParams(
      instructionDecodeWidth = 4,
      robGroupsPerStid = 8)
    simulate(new OooS1GroupedRob(p)) { dut =>
      clear(dut)
      pokePublication(dut, stid = 1, transactionId = 0, firstSlot = 0,
        firstGeneration = 0, groupMembers = Seq(1, 1, 1))

      val memory = dut.io.publish.bits.reservation.transaction.decoded.uops(1).recipe
      memory.mayTrap.poke(true.B)
      memory.mayTrapLate.poke(true.B)
      memory.memoryRequestCount.poke(1.U)
      memory.sideEffectOwner.poke(OooSideEffectOwner.Lsu.U)
      memory.dispatchClass.poke(OooDispatchClass.Agu.U)

      val control = dut.io.publish.bits.reservation.transaction.decoded.uops(2).recipe
      control.mayTrap.poke(true.B)
      control.mayRedirect.poke(true.B)
      control.requiresTargetValidation.poke(true.B)
      control.nonspeculative.poke(true.B)
      control.sideEffectOwner.poke(OooSideEffectOwner.Bctrl.U)
      control.dispatchClass.poke(OooDispatchClass.Bru.U)

      dut.clock.step()
      dut.io.publish.valid.poke(false.B)
      dut.clock.step()
      dut.io.nonFlushWindows(1).valid.expect(true.B)
      dut.io.nonFlushWindows(1).head.ridSlot.expect(0.U)
      dut.io.nonFlushWindows(1).prefixCount.expect(1.U)
      dut.io.occupiedGroups(1).expect(3.U)
      dut.io.commit.valid.expect(false.B)

      pokeNonFlushEvidence(dut, stid = 1, slot = 1, ridGeneration = 0,
        proofs = OooNonFlushProof.ExceptionSafeMask |
          OooNonFlushProof.MemorySafeMask)
      acceptNonFlushEvidence(dut)
      dut.clock.step()
      dut.io.nonFlushWindows(1).prefixCount.expect(2.U)
      dut.io.occupiedGroups(1).expect(3.U)

      pokeNonFlushEvidence(dut, stid = 1, slot = 2, ridGeneration = 1,
        proofs = OooNonFlushProof.ExceptionSafeMask |
          OooNonFlushProof.ControlSafeMask)
      dut.io.nonFlushEvidenceRejected.valid.expect(true.B)
      dut.clock.step()
      dut.io.nonFlushEvidence.valid.poke(false.B)
      dut.io.nonFlushWindows(1).prefixCount.expect(2.U)

      pokeNonFlushEvidence(dut, stid = 1, slot = 2, ridGeneration = 0,
        proofs = OooNonFlushProof.ExceptionSafeMask |
          OooNonFlushProof.ControlSafeMask)
      acceptNonFlushEvidence(dut)
      dut.clock.step()
      dut.io.nonFlushWindows(1).prefixCount.expect(2.U)

      pokeNonFlushEvidence(dut, stid = 1, slot = 2, ridGeneration = 0,
        proofs = OooNonFlushProof.SerializationSafeMask)
      acceptNonFlushEvidence(dut)
      dut.clock.step()
      dut.io.nonFlushWindows(1).prefixCount.expect(3.U)
      dut.io.occupiedGroups(1).expect(3.U)
      dut.io.commit.valid.expect(false.B)

      pokeNonFlushEvidence(dut, stid = 1, slot = 2, ridGeneration = 0,
        proofs = OooNonFlushProof.ControlSafeMask)
      dut.io.nonFlushEvidenceRejected.valid.expect(true.B)
    }
  }

  test("freezes only the interrupted STID and keeps precise traps outside the window") {
    val p = OooParams(instructionDecodeWidth = 2, robGroupsPerStid = 8)
    simulate(new OooS1GroupedRob(p)) { dut =>
      clear(dut)
      dut.io.interruptPending(0).poke(true.B)
      pokePublication(dut, stid = 0, transactionId = 0, firstSlot = 0,
        firstGeneration = 0, groupMembers = Seq(1))
      dut.clock.step()
      pokePublication(dut, stid = 1, transactionId = 0, firstSlot = 0,
        firstGeneration = 0, groupMembers = Seq(1))
      dut.clock.step()
      dut.io.publish.valid.poke(false.B)
      dut.clock.step()
      dut.io.nonFlushWindows(0).prefixCount.expect(0.U)
      dut.io.nonFlushWindows(1).prefixCount.expect(1.U)

      dut.io.interruptPending(0).poke(false.B)
      dut.clock.step()
      dut.io.nonFlushWindows(0).prefixCount.expect(1.U)

      pokePublication(dut, stid = 2, transactionId = 0, firstSlot = 0,
        firstGeneration = 0, groupMembers = Seq(1))
      val trapGroup = dut.io.publish.bits.reservation.transaction.groups(0)
      trapGroup.preciseTrap.poke(true.B)
      val trapUop = dut.io.publish.bits.reservation.transaction.decoded.uops(0)
      trapUop.preciseTrap.poke(true.B)
      trapUop.recipe.mayTrap.poke(true.B)
      dut.clock.step()
      dut.io.publish.valid.poke(false.B)
      dut.clock.step()
      dut.io.nonFlushWindows(2).valid.expect(true.B)
      dut.io.nonFlushWindows(2).prefixCount.expect(0.U)

      pokeNonFlushEvidence(dut, stid = 2, slot = 0, ridGeneration = 0,
        proofs = OooNonFlushProof.ExceptionSafeMask)
      dut.io.nonFlushEvidenceRejected.valid.expect(true.B)
    }
  }

  test("moves the exact window head without using non-flush as deallocation authority") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      retireGroupWidth = 1,
      robGroupsPerStid = 8)
    simulate(new OooS1GroupedRob(p)) { dut =>
      clear(dut)
      pokePublication(dut, stid = 3, transactionId = 0, firstSlot = 0,
        firstGeneration = 0, groupMembers = Seq(1, 1),
        initiallyComplete = Set(0, 1))
      dut.clock.step()
      dut.io.publish.valid.poke(false.B)
      dut.clock.step()
      dut.io.nonFlushWindows(3).head.ridSlot.expect(0.U)
      dut.io.nonFlushWindows(3).prefixCount.expect(2.U)
      dut.io.occupiedGroups(3).expect(2.U)
      dut.io.commit.valid.expect(true.B)

      dut.io.commit.ready.poke(true.B)
      dut.clock.step()
      dut.io.commit.ready.poke(false.B)
      dut.io.nonFlushWindows(3).head.ridSlot.expect(1.U)
      dut.io.nonFlushWindows(3).prefixCount.expect(1.U)
      dut.io.occupiedGroups(3).expect(1.U)
    }
  }

  test("prepares and applies an exact intra-group survivor suffix recovery") {
    val p = OooParams(robGroupsPerStid = 8)
    simulate(new OooS1GroupedRob(p)) { dut =>
      clear(dut)
      pokeIntraGroupPublication(dut)
      dut.io.publish.ready.expect(true.B)
      dut.clock.step()
      dut.io.publish.valid.poke(false.B)
      dut.io.occupiedGroups(1).expect(3.U)

      pokeRecovery(dut, stid = 1, slot = 0, ridGeneration = 0,
        member = 0, memberCount = 1, killTrigger = false)
      dut.io.recoveryPrepareReady.expect(true.B)
      dut.io.recoveryPrepared.valid.expect(true.B)
      dut.io.recoveryPrepared.pivotOffset.expect(0.U)
      dut.io.recoveryPrepared.pivot.physicalMemberCount.expect(3.U)
      dut.io.recoveryPrepared.pivot.logicalUopMask.expect(3.U)
      dut.io.recoveryPrepared.survivingPivotValid.expect(true.B)
      dut.io.recoveryPrepared.survivingPivot.physicalMemberCount.expect(1.U)
      dut.io.recoveryPrepared.survivingPivot.logicalUopMask.expect(1.U)
      dut.io.recoveryPrepared.survivingTailValid.expect(true.B)
      dut.io.recoveryPrepared.survivingTail.key.ridSlot.expect(0.U)
      dut.io.recoveryPrepared.survivingPivot.pMapQRows.expect(1.U)
      dut.io.recoveryPrepared.survivingPivot.architecturalParentCount.expect(1.U)
      dut.io.recoveryPrepared.killedGroupCount.expect(2.U)
      dut.io.recoveryPrepared.killedGroupMask.expect(3.U)
      dut.io.recoveryPrepared.firstKilledGroup.valid.expect(true.B)
      dut.io.recoveryPrepared.firstKilledGroup.ridSlot.expect(1.U)
      dut.io.recoveryPrepared.killedGroups(0).key.ridSlot.expect(1.U)
      dut.io.recoveryPrepared.killedGroups(1).key.ridSlot.expect(2.U)
      dut.io.recoveryPrepared.oldTail.ridSlot.expect(3.U)
      dut.io.recoveryPrepared.newTail.ridSlot.expect(1.U)

      pokeCompletion(dut, stid = 1, slot = 0, ridGeneration = 0, member = 0)
      dut.io.completion.ready.expect(false.B)
      dut.io.commit.valid.expect(false.B)

      dut.io.recoveryFire.poke(true.B)
      dut.clock.step()
      dut.io.recoveryFire.poke(false.B)
      dut.io.recoveryPrepare.valid.poke(false.B)
      dut.io.completion.valid.poke(false.B)
      dut.io.occupiedGroups(1).expect(1.U)
      dut.io.nonFlushWindows(1).prefixCount.expect(0.U)

      pokeCompletion(dut, stid = 1, slot = 0, ridGeneration = 0, member = 1)
      dut.io.completion.ready.expect(true.B)
      dut.io.completionRejected.valid.expect(true.B)
      dut.clock.step()
      dut.io.completion.valid.poke(false.B)

      pokeCompletion(dut, stid = 1, slot = 0, ridGeneration = 0, member = 0)
      acceptCompletion(dut)
      dut.clock.step()
      dut.io.commit.valid.expect(true.B)
      dut.io.commit.bits.release.groupCount.expect(1.U)
      dut.io.commit.bits.groups(0).physicalMemberCount.expect(1.U)
      dut.io.commit.bits.groups(0).pMapQRows.expect(1.U)
    }
  }

  test("kills an exact logical trigger and can remove the complete ROB window") {
    val p = OooParams(robGroupsPerStid = 8)
    simulate(new OooS1GroupedRob(p)) { dut =>
      clear(dut)
      pokeIntraGroupPublication(dut)
      dut.clock.step()
      dut.io.publish.valid.poke(false.B)

      pokeRecovery(dut, stid = 1, slot = 0, ridGeneration = 0,
        member = 1, memberCount = 2, killTrigger = true)
      dut.io.recoveryPrepareReady.expect(true.B)
      dut.io.recoveryPrepared.survivingPivotValid.expect(true.B)
      dut.io.recoveryPrepared.survivingPivot.physicalMemberCount.expect(1.U)
      dut.io.recoveryPrepared.survivingPivot.logicalUopMask.expect(1.U)
      dut.io.recoveryPrepared.killedGroupCount.expect(2.U)
      dut.io.recoveryPrepared.killedGroupMask.expect(3.U)
      dut.io.recoveryFire.poke(true.B)
      dut.clock.step()
      dut.io.recoveryFire.poke(false.B)
      dut.io.recoveryPrepare.valid.poke(false.B)
      dut.io.occupiedGroups(1).expect(1.U)

      pokeRecovery(dut, stid = 1, slot = 0, ridGeneration = 0,
        member = 0, memberCount = 1, killTrigger = true)
      dut.io.recoveryPrepareReady.expect(true.B)
      dut.io.recoveryPrepared.survivingPivotValid.expect(false.B)
      dut.io.recoveryPrepared.survivingTailValid.expect(false.B)
      dut.io.recoveryPrepared.killedGroupCount.expect(1.U)
      dut.io.recoveryPrepared.killedGroupMask.expect(1.U)
      dut.io.recoveryPrepared.firstKilledGroup.ridSlot.expect(0.U)
      dut.io.recoveryPrepared.killedGroups(0).key.ridSlot.expect(0.U)
      dut.io.recoveryPrepared.newTail.ridSlot.expect(0.U)
      dut.io.recoveryFire.poke(true.B)
      dut.clock.step()
      dut.io.recoveryFire.poke(false.B)
      dut.io.recoveryPrepare.valid.poke(false.B)
      dut.io.occupiedGroups(1).expect(0.U)
      dut.io.commit.valid.expect(false.B)
    }
  }

  test("preserves the trigger group and truncates only younger groups across wrap") {
    val p = OooParams(robGroupsPerStid = 8)
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

      pokeIntraGroupPublication(dut, transactionId = 2, firstSlot = 7)
      dut.io.publish.ready.expect(true.B)
      dut.clock.step()
      dut.io.publish.valid.poke(false.B)

      pokeRecovery(dut, stid = 1, slot = 0, ridGeneration = 1,
        member = 0, memberCount = 1, killTrigger = false,
        transactionId = 2)
      dut.io.recoveryPrepareReady.expect(true.B)
      dut.io.recoveryPrepared.pivotOffset.expect(1.U)
      dut.io.recoveryPrepared.survivingPivotValid.expect(true.B)
      dut.io.recoveryPrepared.survivingTailValid.expect(true.B)
      dut.io.recoveryPrepared.survivingTail.key.ridSlot.expect(0.U)
      dut.io.recoveryPrepared.survivingTail.key.ridGeneration.expect(1.U)
      dut.io.recoveryPrepared.killedGroupCount.expect(1.U)
      dut.io.recoveryPrepared.killedGroupMask.expect(1.U)
      dut.io.recoveryPrepared.firstKilledGroup.ridSlot.expect(1.U)
      dut.io.recoveryPrepared.firstKilledGroup.ridGeneration.expect(1.U)
      dut.io.recoveryPrepared.killedGroups(0).key.ridSlot.expect(1.U)
      dut.io.recoveryPrepared.killedGroups(0).key.ridGeneration.expect(1.U)
      dut.io.recoveryPrepared.oldTail.ridSlot.expect(2.U)
      dut.io.recoveryPrepared.oldTail.ridGeneration.expect(1.U)
      dut.io.recoveryPrepared.newTail.ridSlot.expect(1.U)
      dut.io.recoveryPrepared.newTail.ridGeneration.expect(1.U)
      dut.io.commit.valid.expect(false.B)
      dut.clock.step()
      dut.io.recoveryPrepareReady.expect(true.B)
      dut.io.recoveryPrepared.valid.expect(true.B)
      dut.io.commit.valid.expect(false.B)
      dut.io.recoveryFire.poke(true.B)
      dut.io.recoveryPrepareReady.expect(true.B)
      dut.io.recoveryPrepared.valid.expect(true.B)
      dut.clock.step()
      dut.io.recoveryFire.poke(false.B)
      dut.io.recoveryPrepare.valid.poke(false.B)
      dut.io.occupiedGroups(1).expect(2.U)
      dut.io.headSlot(1).expect(7.U)
      dut.io.headGeneration(1).expect(0.U)
    }
  }

  test("rejects stale identity and non-logical trigger shapes with zero mutation") {
    val p = OooParams(robGroupsPerStid = 8)
    simulate(new OooS1GroupedRob(p)) { dut =>
      clear(dut)
      pokeIntraGroupPublication(dut)
      dut.clock.step()
      dut.io.publish.valid.poke(false.B)

      pokeRecovery(dut, stid = 1, slot = 0, ridGeneration = 1,
        member = 0, memberCount = 1, killTrigger = false)
      dut.io.recoveryPrepareReady.expect(false.B)
      dut.io.recoveryPrepared.valid.expect(false.B)
      dut.io.recoveryRejected.valid.expect(true.B)
      dut.io.recoveryRejected.bits.exactMatchCount.expect(0.U)
      dut.clock.step()

      pokeRecovery(dut, stid = 1, slot = 0, ridGeneration = 0,
        member = 1, memberCount = 1, killTrigger = true)
      dut.io.recoveryPrepareReady.expect(false.B)
      dut.io.recoveryRejected.valid.expect(true.B)
      dut.io.recoveryRejected.bits.exactMatchCount.expect(1.U)
      dut.io.recoveryRejected.bits.triggerShapeMatch.expect(false.B)
      dut.clock.step()

      dut.io.recoveryPrepare.valid.poke(false.B)
      dut.io.occupiedGroups(1).expect(3.U)
      dut.io.recoveryFire.poke(false.B)
    }
  }
}
