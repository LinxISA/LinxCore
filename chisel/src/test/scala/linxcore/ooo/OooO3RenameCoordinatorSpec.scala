package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.common.{DestinationKind, OperandClass}
import org.scalatest.funsuite.AnyFunSuite

class OooO3RenameCoordinatorSpec extends AnyFunSuite with ChiselSim {
  private def clear(dut: OooO3RenameCoordinator): Unit = {
    dut.io.reserve.valid.poke(false.B)
    dut.io.reserve.bits.poke(0.U.asTypeOf(dut.io.reserve.bits))
    dut.io.cancel.foreach(_.poke(false.B))
    dut.io.publishPermit.poke(false.B)
    dut.io.completion.valid.poke(false.B)
    dut.io.completion.bits.poke(0.U.asTypeOf(dut.io.completion.bits))
    dut.io.commit.ready.poke(false.B)
    dut.io.ptagReturn.valid.poke(false.B)
    dut.io.ptagReturn.bits.poke(0.U.asTypeOf(dut.io.ptagReturn.bits))
    dut.io.queryStid.poke(0.U)
    dut.io.queryAtag.poke(0.U)
    dut.io.pcReadTokens.foreach(_.poke(0.U.asTypeOf(dut.io.pcReadTokens.head)))
  }

  private def pokeOneDestination(
      dut: OooO3RenameCoordinator,
      tailEpoch: Int = 0,
      uopCount: Int = 1,
      stid: Int = 0,
      transactionId: Int = 0,
      firstRid: Int = 0,
      atag: Int = 1,
      basePc: Int = 256): Unit = {
    dut.io.reserve.bits.poke(0.U.asTypeOf(dut.io.reserve.bits))
    val transaction = dut.io.reserve.bits
    transaction.plan.peId.poke(3.U)
    transaction.plan.stid.poke(stid.U)
    transaction.plan.epoch.poke(5.U)
    transaction.plan.transactionId.poke(transactionId.U)
    val uopMask = (1 << uopCount) - 1
    transaction.plan.uopMask.poke(uopMask.U)
    transaction.plan.groupCount.poke(1.U)
    transaction.plan.virtualTailEpoch.poke(tailEpoch.U)
    transaction.plan.firstVirtualGroup.valid.poke(true.B)
    transaction.plan.firstVirtualGroup.peId.poke(3.U)
    transaction.plan.firstVirtualGroup.stid.poke(stid.U)
    transaction.plan.firstVirtualGroup.ridSlot.poke(
      (firstRid % dut.p.robGroupsPerStid).U)
    transaction.plan.firstVirtualGroup.ridGeneration.poke(
      (firstRid / dut.p.robGroupsPerStid).U)
    transaction.plan.demand.pDestinations.poke(uopCount.U)
    transaction.plan.demand.mapQRows.poke(uopCount.U)
    transaction.decoded.peId.poke(3.U)
    transaction.decoded.stid.poke(stid.U)
    transaction.decoded.epoch.poke(5.U)
    transaction.decoded.uopMask.poke(uopMask.U)
    transaction.groupMask.poke(1.U)

    val group = transaction.groups(0)
    group.valid.poke(true.B)
    group.key.valid.poke(true.B)
    group.key.peId.poke(3.U)
    group.key.stid.poke(stid.U)
    group.key.ridSlot.poke((firstRid % dut.p.robGroupsPerStid).U)
    group.key.ridGeneration.poke((firstRid / dut.p.robGroupsPerStid).U)
    group.logicalUopMask.poke(uopMask.U)
    group.physicalMemberCount.poke(uopCount.U)
    group.pMapQRows.poke(uopCount.U)
    group.architecturalParentCount.poke(uopCount.U)
    group.boundaryStart.poke(true.B)
    group.boundaryStop.poke(true.B)
    group.releasePcBase.poke(true.B)

    for (uopIndex <- 0 until uopCount) {
      transaction.uopGroupIndex(uopIndex).poke(0.U)
      transaction.uopMemberBase(uopIndex).poke(uopIndex.U)
      val uop = transaction.decoded.uops(uopIndex)
      uop.valid.poke(true.B)
      uop.plannedChildCount.poke(1.U)
      uop.identity.parentCount.poke(1.U)
      uop.identity.parents(0).key.valid.poke(true.B)
      uop.identity.parents(0).key.peId.poke(3.U)
      uop.identity.parents(0).key.stid.poke(stid.U)
      uop.identity.parents(0).key.instructionId.poke(
        (100 + 10 * transactionId + uopIndex).U)
      uop.identity.parents(0).key.epoch.poke(5.U)
      uop.identity.parents(0).pc.poke((basePc + 4 * uopIndex).U)
      uop.sources(0).valid.poke(true.B)
      uop.sources(0).operandClass.poke(OperandClass.P)
      uop.sources(0).atag.poke(atag.U)
      uop.destinations(0).valid.poke(true.B)
      uop.destinations(0).kind.poke(DestinationKind.Gpr)
      uop.destinations(0).atag.poke(atag.U)
    }
    dut.io.reserve.valid.poke(true.B)
  }

  private def completeMembers(
      dut: OooO3RenameCoordinator,
      stid: Int,
      rid: Int,
      memberCount: Int,
      bid: Int = 0,
      brobGeneration: Int = 0,
      residentGeneration: Int = 1): Unit = {
    for (memberIndex <- 0 until memberCount) {
      dut.io.completion.bits.poke(0.U.asTypeOf(dut.io.completion.bits))
      val key = dut.io.completion.bits.key
      key.group.valid.poke(true.B)
      key.group.peId.poke(3.U)
      key.group.stid.poke(stid.U)
      key.group.ridSlot.poke((rid % dut.p.robGroupsPerStid).U)
      key.group.ridGeneration.poke((rid / dut.p.robGroupsPerStid).U)
      key.bid.valid.poke(true.B)
      key.bid.value.poke(bid.U)
      key.brobGeneration.poke(brobGeneration.U)
      key.memberIndex.poke(memberIndex.U)
      key.residentGeneration.poke(residentGeneration.U)
      dut.io.completion.valid.poke(true.B)
      dut.io.completion.ready.expect(true.B)
      dut.clock.step()
      dut.io.completion.valid.poke(false.B)
    }
  }

  test("joins D3 PTag ROB BROB PC SMAP and MapQ publication on one fire") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      robGroupsPerStid = 8,
      brobEntriesPerStid = 8,
      pMapQDepthPerStid = 4,
      pTagStagingDepthPerBank = 2,
      pTagReturnWidth = 4)
    simulate(new OooO3RenameCoordinator(p)) { dut =>
      clear(dut)
      dut.clock.step() // refill the PTag staging rows
      pokeOneDestination(dut)
      dut.io.reserve.ready.expect(true.B)
      dut.clock.step()
      dut.io.reserve.valid.poke(false.B)

      dut.io.preparedValid.expect(true.B)
      dut.io.prepared.uops(0).sources(0).pMapping.ptag.expect(1.U)
      dut.io.prepared.uops(0).destinations(0)
        .currentPMapping.ptag.expect(96.U)
      dut.io.ptagProvisionalCount.expect(1.U)
      dut.io.ptagPublishedCount.expect(0.U)
      dut.io.mapQUsed(0).expect(0.U)
      dut.io.robOccupiedGroups(0).expect(0.U)
      dut.clock.step(2)
      dut.io.ptagProvisionalCount.expect(1.U)
      dut.io.mapQUsed(0).expect(0.U)

      dut.io.publishPermit.poke(true.B)
      dut.io.publishFire.expect(true.B)
      dut.clock.step()
      dut.io.publishPermit.poke(false.B)
      dut.io.ptagProvisionalCount.expect(0.U)
      dut.io.ptagPublishedCount.expect(1.U)
      dut.io.mapQUsed(0).expect(1.U)
      dut.io.robOccupiedGroups(0).expect(1.U)
      dut.io.queryAtag.poke(1.U)
      dut.io.speculativeMapping.ptag.expect(96.U)
      dut.io.committedMapping.ptag.expect(1.U)

      // Recovery return remains sealed; commit uses the exact MapQ/CMAP owner.
      dut.io.ptagReturn.bits.count.poke(1.U)
      dut.io.ptagReturn.bits.tokens(0).valid.poke(true.B)
      dut.io.ptagReturn.bits.tokens(0).ptag.poke(96.U)
      dut.io.ptagReturn.bits.tokens(0).bank.poke(0.U)
      dut.io.ptagReturn.bits.tokens(0).generation.poke(1.U)
      dut.io.ptagReturn.valid.poke(true.B)
      dut.io.ptagReturn.ready.expect(false.B)
      dut.clock.step()
      dut.io.ptagReturn.valid.poke(false.B)
      dut.io.ptagPublishedCount.expect(1.U)

      val completion = dut.io.completion.bits.key
      completion.group.valid.poke(true.B)
      completion.group.peId.poke(3.U)
      completion.group.stid.poke(0.U)
      completion.group.ridSlot.poke(0.U)
      completion.group.ridGeneration.poke(0.U)
      completion.bid.valid.poke(true.B)
      completion.bid.value.poke(0.U)
      completion.brobGeneration.poke(0.U)
      completion.memberIndex.poke(0.U)
      completion.residentGeneration.poke(1.U)
      dut.io.completion.valid.poke(true.B)
      dut.io.completion.ready.expect(true.B)
      dut.clock.step()
      dut.io.completion.valid.poke(false.B)
      dut.clock.step() // ROB forms retained commit batch
      dut.io.pCommitBusy.expect(false.B)
      dut.clock.step() // P rename locks retained commit batch
      dut.io.pCommitRejected.valid.expect(false.B)
      dut.io.pCommitBusy.expect(true.B)
      dut.io.commit.valid.expect(false.B)
      dut.clock.step() // return the replaced reset mapping and advance CMAP
      dut.io.commit.valid.expect(true.B)
      dut.io.commit.ready.poke(true.B)
      dut.clock.step()
      dut.io.commit.ready.poke(false.B)
      dut.io.robOccupiedGroups(0).expect(0.U)
      dut.io.mapQUsed(0).expect(0.U)
      dut.io.committedMapping.ptag.expect(96.U)
      dut.io.ptagPublishedCount.expect(1.U)
    }
  }

  test("consumes a stale D2 plan without orphaning a PTag lease") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      robGroupsPerStid = 8,
      brobEntriesPerStid = 8,
      pMapQDepthPerStid = 4,
      pTagStagingDepthPerBank = 2,
      pTagReturnWidth = 4)
    simulate(new OooO3RenameCoordinator(p)) { dut =>
      clear(dut)
      dut.clock.step()
      pokeOneDestination(dut, tailEpoch = 1)
      dut.io.reserve.ready.expect(true.B)
      dut.clock.step()
      dut.io.reserve.valid.poke(false.B)
      dut.io.preparedValid.expect(false.B)
      dut.io.ptagProvisionalCount.expect(0.U)
      dut.io.ptagPublishedCount.expect(0.U)
      dut.io.mapQUsed(0).expect(0.U)
      dut.io.robOccupiedGroups(0).expect(0.U)
    }
  }

  test("serializes WAW CMAP commit and returns the exact old PTag before ROB deallocation") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      robGroupsPerStid = 8,
      brobEntriesPerStid = 8,
      pMapQDepthPerStid = 4,
      pTagStagingDepthPerBank = 2,
      pTagReturnWidth = 1)
    simulate(new OooO3RenameCoordinator(p)) { dut =>
      clear(dut)
      dut.clock.step()
      pokeOneDestination(dut, uopCount = 2)
      dut.io.reserve.ready.expect(true.B)
      dut.clock.step()
      dut.io.reserve.valid.poke(false.B)
      dut.io.preparedValid.expect(true.B)
      dut.io.prepared.uops(0).destinations(0)
        .currentPMapping.ptag.expect(96.U)
      dut.io.prepared.uops(1).destinations(0)
        .currentPMapping.ptag.expect(97.U)
      dut.io.publishPermit.poke(true.B)
      dut.io.publishFire.expect(true.B)
      dut.clock.step()
      dut.io.publishPermit.poke(false.B)
      dut.io.ptagPublishedCount.expect(2.U)
      dut.io.mapQUsed(0).expect(2.U)

      for (memberIndex <- 0 until 2) {
        dut.io.completion.bits.poke(0.U.asTypeOf(dut.io.completion.bits))
        val key = dut.io.completion.bits.key
        key.group.valid.poke(true.B)
        key.group.peId.poke(3.U)
        key.group.stid.poke(0.U)
        key.group.ridSlot.poke(0.U)
        key.group.ridGeneration.poke(0.U)
        key.bid.valid.poke(true.B)
        key.bid.value.poke(0.U)
        key.brobGeneration.poke(0.U)
        key.memberIndex.poke(memberIndex.U)
        key.residentGeneration.poke(1.U)
        dut.io.completion.valid.poke(true.B)
        dut.io.completion.ready.expect(true.B)
        dut.clock.step()
        dut.io.completion.valid.poke(false.B)
      }

      dut.clock.step() // ROB forms commit batch
      dut.clock.step() // P rename locks it
      dut.io.pCommitBusy.expect(true.B)
      dut.clock.step() // return reset PTag 1 and commit CMAP 1 -> 96
      dut.io.queryAtag.poke(1.U)
      dut.io.committedMapping.ptag.expect(96.U)
      dut.io.mapQUsed(0).expect(1.U)
      dut.io.ptagPublishedCount.expect(2.U)
      dut.clock.step() // return PTag 96 and commit CMAP 96 -> 97
      dut.io.committedMapping.ptag.expect(97.U)
      dut.io.mapQUsed(0).expect(0.U)
      dut.io.ptagPublishedCount.expect(1.U)
      dut.io.commit.valid.expect(true.B)
      dut.io.robOccupiedGroups(0).expect(1.U)
      dut.io.commit.ready.poke(true.B)
      dut.clock.step()
      dut.io.commit.ready.poke(false.B)
      dut.io.robOccupiedGroups(0).expect(0.U)
      dut.io.ptagPublishedCount.expect(1.U)
    }
  }

  test("lets an older commit drain a full MapQ ahead of a younger provisional row") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      robGroupsPerStid = 8,
      brobEntriesPerStid = 8,
      pMapQDepthPerStid = 2,
      pTagStagingDepthPerBank = 2,
      pTagReturnWidth = 1)
    simulate(new OooO3RenameCoordinator(p)) { dut =>
      clear(dut)
      dut.clock.step()

      pokeOneDestination(dut, uopCount = 2)
      dut.io.reserve.ready.expect(true.B)
      dut.clock.step()
      dut.io.reserve.valid.poke(false.B)
      dut.io.publishPermit.poke(true.B)
      dut.io.publishFire.expect(true.B)
      dut.clock.step()
      dut.io.publishPermit.poke(false.B)
      dut.io.mapQUsed(0).expect(2.U)

      pokeOneDestination(dut, tailEpoch = 1, uopCount = 2,
        transactionId = 1, firstRid = 1, atag = 2, basePc = 512)
      dut.io.reserve.ready.expect(true.B)
      dut.clock.step()
      dut.io.reserve.valid.poke(false.B)
      dut.io.preparedValid.expect(false.B) // MapQ is full; row stays provisional.

      completeMembers(dut, stid = 0, rid = 0, memberCount = 2)
      dut.clock.step() // form retained ROB commit
      dut.clock.step() // lock the P commit walk despite the younger provisional
      dut.io.pCommitBusy.expect(true.B)
      dut.io.preparedValid.expect(false.B)
      dut.clock.step(2) // width-one return of identity PTag 1, then PTag 96
      dut.io.commit.valid.expect(true.B)
      dut.io.commit.ready.poke(true.B)
      dut.clock.step()
      dut.io.commit.ready.poke(false.B)
      dut.io.pCommitBusy.expect(false.B)
      dut.io.mapQUsed(0).expect(0.U)

      dut.io.preparedValid.expect(true.B)
      dut.io.publishPermit.poke(true.B)
      dut.io.publishFire.expect(true.B)
      dut.clock.step()
      dut.io.publishPermit.poke(false.B)
      dut.io.mapQUsed(0).expect(2.U)
      dut.io.robOccupiedGroups(0).expect(1.U)
    }
  }

  test("retains an exposed younger prepare before starting same-STID commit") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      robGroupsPerStid = 8,
      brobEntriesPerStid = 8,
      pMapQDepthPerStid = 4,
      pTagStagingDepthPerBank = 2,
      pTagReturnWidth = 1)
    simulate(new OooO3RenameCoordinator(p)) { dut =>
      clear(dut)
      dut.clock.step()

      pokeOneDestination(dut)
      dut.clock.step()
      dut.io.reserve.valid.poke(false.B)
      dut.io.publishPermit.poke(true.B)
      dut.io.publishFire.expect(true.B)
      dut.clock.step()
      dut.io.publishPermit.poke(false.B)

      pokeOneDestination(dut, tailEpoch = 1, transactionId = 1,
        firstRid = 1, atag = 2, basePc = 512)
      dut.io.reserve.ready.expect(true.B)
      dut.clock.step()
      dut.io.reserve.valid.poke(false.B)
      dut.io.preparedValid.expect(true.B)
      dut.io.prepared.transactionId.expect(1.U)
      val retainedPtag = dut.io.prepared.uops(0).destinations(0)
        .currentPMapping.ptag.peek().litValue

      completeMembers(dut, stid = 0, rid = 0, memberCount = 1)
      dut.clock.step(3)
      dut.io.pCommitBusy.expect(false.B)
      dut.io.preparedValid.expect(true.B)
      dut.io.prepared.transactionId.expect(1.U)
      dut.io.prepared.uops(0).destinations(0)
        .currentPMapping.ptag.expect(retainedPtag.U)

      dut.io.publishPermit.poke(true.B)
      dut.io.publishFire.expect(true.B)
      dut.clock.step()
      dut.io.publishPermit.poke(false.B)
      dut.io.mapQUsed(0).expect(2.U)

      dut.clock.step() // now the older retained ROB batch may lock
      dut.io.pCommitBusy.expect(true.B)
      dut.clock.step() // return reset PTag 1 and commit the first MapQ row
      dut.io.commit.valid.expect(true.B)
      dut.io.commit.ready.poke(true.B)
      dut.clock.step()
      dut.io.commit.ready.poke(false.B)
      dut.io.pCommitBusy.expect(false.B)
      dut.io.mapQUsed(0).expect(1.U)
      dut.io.robOccupiedGroups(0).expect(1.U)
    }
  }
}
