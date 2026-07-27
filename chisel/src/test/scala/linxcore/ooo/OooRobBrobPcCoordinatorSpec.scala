package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

class OooRobBrobPcCoordinatorSpec extends AnyFunSuite with ChiselSim {
  private def clear(dut: OooRobBrobPcCoordinator): Unit = {
    dut.io.reserve.valid.poke(false.B)
    dut.io.reserve.bits.poke(0.U.asTypeOf(dut.io.reserve.bits))
    dut.io.cancel.foreach(_.poke(false.B))
    dut.io.publishEligible.foreach(_.poke(true.B))
    dut.io.publishPermit.poke(false.B)
    dut.io.completion.valid.poke(false.B)
    dut.io.completion.bits.poke(0.U.asTypeOf(dut.io.completion.bits))
    dut.io.commit.ready.poke(false.B)
    dut.io.pcReadTokens.foreach(_.poke(0.U.asTypeOf(dut.io.pcReadTokens.head)))
  }

  private def pokeTransaction(
      dut: OooRobBrobPcCoordinator,
      stid: Int,
      transactionId: Int,
      firstRid: Int,
      tailEpoch: Int,
      pcs: Seq[Long],
      starts: Set[Int],
      stops: Set[Int],
      releases: Set[Int],
      peId: Int = 3,
      epoch: Int = 5): Unit = {
    dut.io.reserve.bits.poke(0.U.asTypeOf(dut.io.reserve.bits))
    val transaction = dut.io.reserve.bits
    transaction.plan.peId.poke(peId.U)
    transaction.plan.stid.poke(stid.U)
    transaction.plan.epoch.poke(epoch.U)
    transaction.plan.transactionId.poke(transactionId.U)
    transaction.plan.groupCount.poke(pcs.size.U)
    transaction.plan.virtualTailEpoch.poke(tailEpoch.U)
    transaction.plan.firstVirtualGroup.valid.poke(true.B)
    transaction.plan.firstVirtualGroup.peId.poke(peId.U)
    transaction.plan.firstVirtualGroup.stid.poke(stid.U)
    transaction.plan.firstVirtualGroup.ridSlot.poke(
      (firstRid % dut.p.robGroupsPerStid).U)
    transaction.plan.firstVirtualGroup.ridGeneration.poke(
      (firstRid / dut.p.robGroupsPerStid).U)
    transaction.decoded.peId.poke(peId.U)
    transaction.decoded.stid.poke(stid.U)
    transaction.decoded.epoch.poke(epoch.U)
    transaction.decoded.uopMask.poke(((1 << pcs.size) - 1).U)
    transaction.groupMask.poke(((1 << pcs.size) - 1).U)

    pcs.zipWithIndex.foreach { case (pc, index) =>
      val absoluteRid = firstRid + index
      val group = transaction.groups(index)
      group.valid.poke(true.B)
      group.key.valid.poke(true.B)
      group.key.peId.poke(peId.U)
      group.key.stid.poke(stid.U)
      group.key.ridSlot.poke((absoluteRid % dut.p.robGroupsPerStid).U)
      group.key.ridGeneration.poke((absoluteRid / dut.p.robGroupsPerStid).U)
      group.logicalUopMask.poke((1 << index).U)
      group.physicalMemberCount.poke(1.U)
      group.architecturalParentCount.poke(1.U)
      group.boundaryStart.poke(starts(index).B)
      group.boundaryStop.poke(stops(index).B)
      group.releasePcBase.poke(releases(index).B)
      transaction.uopGroupIndex(index).poke(index.U)

      val uop = transaction.decoded.uops(index)
      uop.valid.poke(true.B)
      uop.identity.parentCount.poke(1.U)
      uop.identity.parents(0).key.valid.poke(true.B)
      uop.identity.parents(0).key.peId.poke(peId.U)
      uop.identity.parents(0).key.stid.poke(stid.U)
      uop.identity.parents(0).key.instructionId.poke((100 + absoluteRid).U)
      uop.identity.parents(0).key.epoch.poke(epoch.U)
      uop.identity.parents(0).pc.poke(pc.U)
    }
    dut.io.reserve.valid.poke(true.B)
  }

  private def reserve(dut: OooRobBrobPcCoordinator): Unit = {
    dut.io.reserve.ready.expect(true.B)
    dut.clock.step()
    dut.io.reserve.valid.poke(false.B)
  }

  private def complete(
      dut: OooRobBrobPcCoordinator,
      stid: Int,
      absoluteRid: Int,
      bid: Int,
      brobGeneration: Int,
      residentGeneration: Int,
      peId: Int = 3): Unit = {
    val key = dut.io.completion.bits.key
    dut.io.completion.bits.poke(0.U.asTypeOf(dut.io.completion.bits))
    key.group.valid.poke(true.B)
    key.group.peId.poke(peId.U)
    key.group.stid.poke(stid.U)
    key.group.ridSlot.poke((absoluteRid % dut.p.robGroupsPerStid).U)
    key.group.ridGeneration.poke((absoluteRid / dut.p.robGroupsPerStid).U)
    key.bid.valid.poke(true.B)
    key.bid.value.poke(bid.U)
    key.brobGeneration.poke(brobGeneration.U)
    key.memberIndex.poke(0.U)
    key.residentGeneration.poke(residentGeneration.U)
    dut.io.completion.valid.poke(true.B)
    dut.io.completion.ready.expect(true.B)
    dut.clock.step()
    dut.io.completion.valid.poke(false.B)
  }

  test("publishes and retires D3 ROB BROB and PC state on common terminal fires") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      robGroupsPerStid = 8,
      brobEntriesPerStid = 8,
      pcBufferEntries = 64)
    simulate(new OooRobBrobPcCoordinator(p)) { dut =>
      clear(dut)
      pokeTransaction(dut, stid = 1, transactionId = 0, firstRid = 0,
        tailEpoch = 0, pcs = Seq(100, 106), starts = Set(0), stops = Set(1),
        releases = Set(1))
      reserve(dut)

      dut.io.preparedValid.expect(true.B)
      dut.io.prepared.request.bindings(0).brob.bid.value.expect(0.U)
      dut.io.prepared.request.bindings(1).brob.bid.value.expect(0.U)
      dut.io.prepared.request.bindings(0).residentGeneration.expect(1.U)
      dut.io.prepared.request.bindings(1).residentGeneration.expect(1.U)
      dut.io.prepared.parentPcTokens(0)(0).index.expect(16.U)
      dut.io.prepared.parentPcTokens(0)(0).byteOffset.expect(0.U)
      dut.io.prepared.parentPcTokens(1)(0).byteOffset.expect(6.U)
      dut.clock.step(3)
      dut.io.d3UsedGroups(1).expect(2.U)
      dut.io.d3PublishedGroups(1).expect(0.U)
      dut.io.robOccupiedGroups(1).expect(0.U)
      dut.io.brobUsedBlocks(1).expect(0.U)
      dut.io.pcUsedBases(1).expect(0.U)

      dut.io.publishPermit.poke(true.B)
      dut.io.publishFire.expect(true.B)
      dut.clock.step()
      dut.io.publishPermit.poke(false.B)
      dut.io.d3PublishedGroups(1).expect(2.U)
      dut.io.robOccupiedGroups(1).expect(2.U)
      dut.io.brobUsedBlocks(1).expect(1.U)
      dut.io.pcUsedBases(1).expect(1.U)

      dut.io.pcReadTokens(0).valid.poke(true.B)
      dut.io.pcReadTokens(0).index.poke(16.U)
      dut.io.pcReadTokens(0).byteOffset.poke(6.U)
      dut.io.pcReadTokens(0).allocationEpoch.poke(0.U)
      dut.io.pcReadValid(0).expect(true.B)
      dut.io.pcRead(0).expect(106.U)

      complete(dut, stid = 1, absoluteRid = 1, bid = 0,
        brobGeneration = 0, residentGeneration = 1)
      complete(dut, stid = 1, absoluteRid = 0, bid = 0,
        brobGeneration = 0, residentGeneration = 1)
      dut.clock.step()
      dut.io.commit.valid.expect(true.B)
      val retainedRid = dut.io.commit.bits.release.firstGroup.ridSlot.peek().litValue
      val retainedEpoch = dut.io.commit.bits.release.headEpoch.peek().litValue
      val retainedCount = dut.io.commit.bits.release.groupCount.peek().litValue
      dut.clock.step(2)
      dut.io.commit.valid.expect(true.B)
      dut.io.commit.bits.release.firstGroup.ridSlot.expect(retainedRid.U)
      dut.io.commit.bits.release.headEpoch.expect(retainedEpoch.U)
      dut.io.commit.bits.release.groupCount.expect(retainedCount.U)

      dut.io.commit.ready.poke(true.B)
      dut.clock.step()
      dut.io.commit.ready.poke(false.B)
      dut.io.d3UsedGroups(1).expect(0.U)
      dut.io.d3PublishedGroups(1).expect(0.U)
      dut.io.robOccupiedGroups(1).expect(0.U)
      dut.io.brobUsedBlocks(1).expect(0.U)
      dut.io.pcUsedBases(1).expect(0.U)
      dut.io.pcReadValid(0).expect(false.B)
    }
  }

  test("rejects malformed PC parent mapping without partial owner publication") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      robGroupsPerStid = 8,
      brobEntriesPerStid = 8)
    simulate(new OooRobBrobPcCoordinator(p)) { dut =>
      clear(dut)
      pokeTransaction(dut, stid = 2, transactionId = 0, firstRid = 0,
        tailEpoch = 0, pcs = Seq(40, 48), starts = Set(0), stops = Set(1),
        releases = Set(1))
      reserve(dut)
      // Corrupt the retained D3 packet before reservation is not possible; this
      // negative case instead enters D3 with a malformed inverse mapping that
      // only the PC owner is responsible for validating.
      // Re-create it through a fresh provisional transaction after cancellation.
      dut.io.cancel(2).poke(true.B)
      dut.clock.step()
      dut.io.cancel(2).poke(false.B)

      pokeTransaction(dut, stid = 2, transactionId = 1, firstRid = 0,
        tailEpoch = 2, pcs = Seq(40, 48), starts = Set(0), stops = Set(1),
        releases = Set(1))
      dut.io.reserve.bits.uopGroupIndex(1).poke(0.U)
      reserve(dut)
      dut.io.publishPermit.poke(true.B)
      dut.io.preparedValid.expect(false.B)
      dut.io.publishFire.expect(false.B)
      dut.io.pcPrepareRejected.valid.expect(true.B)
      dut.clock.step(2)
      dut.io.d3UsedGroups(2).expect(2.U)
      dut.io.d3PublishedGroups(2).expect(0.U)
      dut.io.robOccupiedGroups(2).expect(0.U)
      dut.io.brobUsedBlocks(2).expect(0.U)
      dut.io.pcUsedBases(2).expect(0.U)

      dut.io.cancel(2).poke(true.B)
      dut.clock.step()
      dut.io.cancel(2).poke(false.B)
      dut.io.d3UsedGroups(2).expect(0.U)
    }
  }

  test("commits one STID while publishing another STID in the same cycle") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      robGroupsPerStid = 8,
      brobEntriesPerStid = 8)
    simulate(new OooRobBrobPcCoordinator(p)) { dut =>
      clear(dut)
      pokeTransaction(dut, stid = 0, transactionId = 0, firstRid = 0,
        tailEpoch = 0, pcs = Seq(64), starts = Set(0), stops = Set(0),
        releases = Set(0))
      reserve(dut)
      dut.io.publishPermit.poke(true.B)
      dut.io.publishFire.expect(true.B)
      dut.clock.step()
      dut.io.publishPermit.poke(false.B)
      complete(dut, stid = 0, absoluteRid = 0, bid = 0,
        brobGeneration = 0, residentGeneration = 1)
      dut.clock.step()
      dut.io.commit.valid.expect(true.B)

      pokeTransaction(dut, stid = 1, transactionId = 0, firstRid = 0,
        tailEpoch = 0, pcs = Seq(128), starts = Set(0), stops = Set(0),
        releases = Set(0))
      reserve(dut)
      dut.io.publishPermit.poke(true.B)
      dut.io.commit.ready.poke(true.B)
      dut.io.publishFire.expect(true.B)
      dut.io.commit.valid.expect(true.B)
      dut.clock.step()
      dut.io.publishPermit.poke(false.B)
      dut.io.commit.ready.poke(false.B)

      dut.io.robOccupiedGroups(0).expect(0.U)
      dut.io.brobUsedBlocks(0).expect(0.U)
      dut.io.pcUsedBases(0).expect(0.U)
      dut.io.robOccupiedGroups(1).expect(1.U)
      dut.io.brobUsedBlocks(1).expect(1.U)
      dut.io.pcUsedBases(1).expect(1.U)
    }
  }

  test("elaborates coordinated publication at decode widths 2 4 and 6") {
    Seq(2, 4, 6).foreach { width =>
      val p = OooParams(
        instructionDecodeWidth = width,
        robGroupsPerStid = 8,
        brobEntriesPerStid = 8)
      simulate(new OooRobBrobPcCoordinator(p)) { dut =>
        clear(dut)
        pokeTransaction(dut, stid = 3, transactionId = 0, firstRid = 0,
          tailEpoch = 0, pcs = Seq(256), starts = Set(0), stops = Set(0),
          releases = Set(0))
        reserve(dut)
        dut.io.preparedValid.expect(true.B)
        dut.io.publishPermit.poke(true.B)
        dut.io.publishFire.expect(true.B)
        dut.clock.step()
        dut.io.robOccupiedGroups(3).expect(1.U)
      }
    }
  }
}
