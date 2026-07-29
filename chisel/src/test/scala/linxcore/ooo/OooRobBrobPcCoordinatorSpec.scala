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
    dut.io.memoryOrder.poke(0.U.asTypeOf(dut.io.memoryOrder))
    dut.io.publishPermit.poke(false.B)
    dut.io.completion.valid.poke(false.B)
    dut.io.completion.bits.poke(0.U.asTypeOf(dut.io.completion.bits))
    dut.io.nonFlushEvidence.valid.poke(false.B)
    dut.io.nonFlushEvidence.bits.poke(
      0.U.asTypeOf(dut.io.nonFlushEvidence.bits))
    dut.io.interruptPending.foreach(_.poke(false.B))
    dut.io.commit.ready.poke(false.B)
    dut.io.recoveryRequest.valid.poke(false.B)
    dut.io.recoveryRequest.bits.poke(
      0.U.asTypeOf(dut.io.recoveryRequest.bits))
    dut.io.recoveryApply.poke(false.B)
    dut.io.recoveryAbort.poke(false.B)
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
      transaction.uopMemberBase(index).poke(0.U)

      val uop = transaction.decoded.uops(index)
      uop.valid.poke(true.B)
      uop.plannedChildCount.poke(1.U)
      uop.recipe.valid.poke(true.B)
      uop.recipe.disposition.poke(OooOpcodeDisposition.Dispatch.U)
      uop.recipe.sideEffectOwner.poke(OooSideEffectOwner.Iex.U)
      uop.recipe.dispatchClass.poke(OooDispatchClass.Alu.U)
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

  private def pokeRecovery(
      dut: OooRobBrobPcCoordinator,
      stid: Int,
      absoluteRid: Int,
      bid: Int,
      brobGeneration: Int,
      residentGeneration: Int,
      transactionId: Int,
      epoch: Int,
      killTrigger: Boolean = false,
      triggerMemberCount: Int = 1,
      peId: Int = 3): Unit = {
    val request = dut.io.recoveryRequest.bits
    request.poke(0.U.asTypeOf(request))
    request.rename.key.member.group.valid.poke(true.B)
    request.rename.key.member.group.peId.poke(peId.U)
    request.rename.key.member.group.stid.poke(stid.U)
    request.rename.key.member.group.ridSlot.poke(
      (absoluteRid % dut.p.robGroupsPerStid).U)
    request.rename.key.member.group.ridGeneration.poke(
      (absoluteRid / dut.p.robGroupsPerStid).U)
    request.rename.key.member.bid.valid.poke(true.B)
    request.rename.key.member.bid.value.poke(bid.U)
    request.rename.key.member.brobGeneration.poke(brobGeneration.U)
    request.rename.key.member.memberIndex.poke(0.U)
    request.rename.key.member.residentGeneration.poke(
      residentGeneration.U)
    request.rename.key.transactionId.poke(transactionId.U)
    request.rename.key.epoch.poke(epoch.U)
    request.rename.killTrigger.poke(killTrigger.B)
    request.triggerMemberCount.poke(triggerMemberCount.U)
    dut.io.recoveryRequest.valid.poke(true.B)
  }

  private def waitRecoveryPrepared(
      dut: OooRobBrobPcCoordinator,
      maxCycles: Int = 64): Unit = {
    var cycles = 0
    while (!dut.io.recoveryPreparedValid.peek().litToBoolean &&
      cycles < maxCycles) {
      dut.clock.step()
      cycles += 1
    }
    assert(dut.io.recoveryPreparedValid.peek().litToBoolean,
      s"physical recovery did not prepare within $maxCycles cycles")
  }

  private def waitRobRecoveryRejected(
      dut: OooRobBrobPcCoordinator,
      maxCycles: Int = 64): Unit = {
    var cycles = 0
    while (!dut.io.robRecoveryRejected.valid.peek().litToBoolean &&
      cycles < maxCycles) {
      dut.clock.step()
      cycles += 1
    }
    assert(dut.io.robRecoveryRejected.valid.peek().litToBoolean,
      s"ROB recovery did not reject within $maxCycles cycles")
  }

  private def waitForCommit(
      dut: OooRobBrobPcCoordinator,
      maxCycles: Int = 32): Unit = {
    var cycles = 0
    while (!dut.io.commit.valid.peek().litToBoolean && cycles < maxCycles) {
      dut.clock.step()
      cycles += 1
    }
    assert(dut.io.commit.valid.peek().litToBoolean,
      s"coordinated commit did not become valid within $maxCycles cycles")
  }

  private def waitForNonFlushPrefix(
      dut: OooRobBrobPcCoordinator,
      stid: Int,
      expected: Int,
      maxCycles: Int = 64): Unit = {
    var cycles = 0
    while (dut.io.nonFlushWindows(stid).prefixCount.peek().litValue != expected &&
        cycles < maxCycles) {
      dut.clock.step()
      cycles += 1
    }
    assert(dut.io.nonFlushWindows(stid).prefixCount.peek().litValue == expected,
      s"STID $stid non-flush prefix did not reach $expected within $maxCycles cycles")
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
      waitForCommit(dut)
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
      waitForCommit(dut)
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

  test("bridges exact typed non-flush evidence without mutating commit owners") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      robGroupsPerStid = 8,
      brobEntriesPerStid = 8)
    simulate(new OooRobBrobPcCoordinator(p)) { dut =>
      clear(dut)
      pokeTransaction(dut, stid = 2, transactionId = 0, firstRid = 0,
        tailEpoch = 0, pcs = Seq(512), starts = Set(0),
        stops = Set(0), releases = Set(0))
      val recipe = dut.io.reserve.bits.decoded.uops(0).recipe
      recipe.mayTrap.poke(true.B)
      recipe.mayTrapLate.poke(true.B)
      recipe.memoryRequestCount.poke(1.U)
      recipe.sideEffectOwner.poke(OooSideEffectOwner.Lsu.U)
      recipe.dispatchClass.poke(OooDispatchClass.Agu.U)
      reserve(dut)

      dut.io.preparedValid.expect(true.B)
      val bid = dut.io.prepared.request.bindings(0).brob.bid.value.peek().litValue
      val brobGeneration =
        dut.io.prepared.request.bindings(0).brob.generation.peek().litValue
      val residentGeneration =
        dut.io.prepared.request.bindings(0).residentGeneration.peek().litValue
      dut.io.publishPermit.poke(true.B)
      dut.clock.step()
      dut.io.publishPermit.poke(false.B)
      dut.clock.step()
      dut.io.nonFlushWindows(2).valid.expect(true.B)
      dut.io.nonFlushWindows(2).prefixCount.expect(0.U)

      val evidence = dut.io.nonFlushEvidence.bits
      evidence.poke(0.U.asTypeOf(evidence))
      evidence.key.group.valid.poke(true.B)
      evidence.key.group.peId.poke(3.U)
      evidence.key.group.stid.poke(2.U)
      evidence.key.group.ridSlot.poke(0.U)
      evidence.key.group.ridGeneration.poke(0.U)
      evidence.key.bid.valid.poke(true.B)
      evidence.key.bid.value.poke(bid.U)
      evidence.key.brobGeneration.poke(brobGeneration.U)
      evidence.key.memberIndex.poke(0.U)
      evidence.key.residentGeneration.poke(residentGeneration.U)
      evidence.proofs.poke((OooNonFlushProof.ExceptionSafeMask |
        OooNonFlushProof.MemorySafeMask |
        OooNonFlushProof.ControlSafeMask).U)
      dut.io.nonFlushEvidence.valid.poke(true.B)
      dut.io.nonFlushEvidence.ready.expect(true.B)
      dut.io.nonFlushEvidenceRejected.valid.expect(false.B)
      dut.clock.step()
      dut.io.nonFlushEvidence.valid.poke(false.B)
      waitForNonFlushPrefix(dut, stid = 2, expected = 1)

      dut.io.nonFlushWindows(2).head.ridSlot.expect(0.U)
      dut.io.nonFlushWindows(2).prefixCount.expect(1.U)
      dut.io.robOccupiedGroups(2).expect(1.U)
      dut.io.commit.valid.expect(false.B)
    }
  }

  test("retains one exact ROB D3 BROB PC recovery plan until common apply") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      robGroupsPerStid = 8,
      brobEntriesPerStid = 8,
      pcBufferEntries = 32)
    simulate(new OooRobBrobPcCoordinator(p)) { dut =>
      clear(dut)

      // Two independently closed groups force two native blocks and two PC
      // bases, so the common recovery must mutate every physical owner.
      pokeTransaction(dut, stid = 1, transactionId = 0, firstRid = 0,
        tailEpoch = 0, pcs = Seq(100, 300), starts = Set(0, 1),
        stops = Set(0, 1), releases = Set(0, 1))
      reserve(dut)
      dut.io.preparedValid.expect(true.B)
      val killedPcIndex = dut.io.prepared.request.bindings(1).pcBase.index
        .peek().litValue
      val killedPcEpoch = dut.io.prepared.request.bindings(1).pcBase
        .allocationEpoch.peek().litValue
      dut.io.publishPermit.poke(true.B)
      dut.io.publishFire.expect(true.B)
      dut.clock.step()
      dut.io.publishPermit.poke(false.B)

      // An unrelated STID remains live through both prepare and apply.
      pokeTransaction(dut, stid = 2, transactionId = 0, firstRid = 0,
        tailEpoch = 0, pcs = Seq(700), starts = Set(0), stops = Set(0),
        releases = Set(0))
      reserve(dut)
      dut.io.publishPermit.poke(true.B)
      dut.io.publishFire.expect(true.B)
      dut.clock.step()
      dut.io.publishPermit.poke(false.B)

      dut.io.robOccupiedGroups(1).expect(2.U)
      dut.io.d3PublishedGroups(1).expect(2.U)
      dut.io.brobUsedBlocks(1).expect(2.U)
      dut.io.pcUsedBases(1).expect(2.U)
      dut.io.robOccupiedGroups(2).expect(1.U)

      pokeRecovery(dut, stid = 1, absoluteRid = 0, bid = 0,
        brobGeneration = 0, residentGeneration = 1,
        transactionId = 0, epoch = 5)
      dut.io.recoveryRequest.ready.expect(true.B)
      dut.clock.step()
      dut.io.recoveryRequest.valid.poke(false.B)
      dut.io.recoveryBusy.expect(true.B)
      waitRecoveryPrepared(dut)

      dut.io.recoveryPrepared.valid.expect(true.B)
      dut.io.recoveryPrepared.oldOccupied.expect(2.U)
      dut.io.recoveryPrepared.newOccupied.expect(1.U)
      dut.io.recoveryPrepared.killedGroupCount.expect(1.U)
      dut.io.recoveryPrepared.survivingPivotValid.expect(true.B)

      dut.io.pcReadTokens(0).valid.poke(true.B)
      dut.io.pcReadTokens(0).index.poke(killedPcIndex.U)
      dut.io.pcReadTokens(0).byteOffset.poke(0.U)
      dut.io.pcReadTokens(0).allocationEpoch.poke(killedPcEpoch.U)
      dut.io.pcReadValid(0).expect(true.B)

      // Arbitrary global-owner backpressure cannot let any lower owner apply.
      dut.clock.step(3)
      dut.io.recoveryPreparedValid.expect(true.B)
      dut.io.robOccupiedGroups(1).expect(2.U)
      dut.io.d3PublishedGroups(1).expect(2.U)
      dut.io.brobUsedBlocks(1).expect(2.U)
      dut.io.pcUsedBases(1).expect(2.U)

      // A composed-owner reject can abort this side-effect-free lower plan.
      dut.io.recoveryAbort.poke(true.B)
      dut.clock.step()
      dut.io.recoveryAbort.poke(false.B)
      dut.io.recoveryBusy.expect(false.B)
      dut.io.robOccupiedGroups(1).expect(2.U)
      dut.io.d3PublishedGroups(1).expect(2.U)
      dut.io.brobUsedBlocks(1).expect(2.U)
      dut.io.pcUsedBases(1).expect(2.U)

      pokeRecovery(dut, stid = 1, absoluteRid = 0, bid = 0,
        brobGeneration = 0, residentGeneration = 1,
        transactionId = 0, epoch = 5)
      dut.io.recoveryRequest.ready.expect(true.B)
      dut.clock.step()
      dut.io.recoveryRequest.valid.poke(false.B)
      waitRecoveryPrepared(dut)

      dut.io.recoveryApply.poke(true.B)
      dut.io.recoveryApplied.valid.expect(true.B)
      dut.io.recoveryApplied.bits.rename.key.member.group.stid.expect(1.U)
      dut.clock.step()
      dut.io.recoveryApply.poke(false.B)
      dut.io.recoveryBusy.expect(false.B)
      dut.io.robOccupiedGroups(1).expect(1.U)
      dut.io.d3PublishedGroups(1).expect(1.U)
      dut.io.brobUsedBlocks(1).expect(1.U)
      dut.io.pcUsedBases(1).expect(1.U)
      dut.io.pcReadValid(0).expect(false.B)
      dut.io.robOccupiedGroups(2).expect(1.U)
      dut.io.d3PublishedGroups(2).expect(1.U)
      dut.io.brobUsedBlocks(2).expect(1.U)
      dut.io.pcUsedBases(2).expect(1.U)

      // A younger D3 row that has already exposed valid must publish before
      // recovery capture; the request remains stable and then kills that row.
      pokeTransaction(dut, stid = 1, transactionId = 1, firstRid = 1,
        tailEpoch = 2, pcs = Seq(500), starts = Set(0), stops = Set(0),
        releases = Set(0))
      reserve(dut)
      dut.io.preparedValid.expect(true.B)
      pokeRecovery(dut, stid = 1, absoluteRid = 0, bid = 0,
        brobGeneration = 0, residentGeneration = 1,
        transactionId = 0, epoch = 5)
      dut.io.recoveryRequest.ready.expect(false.B)
      dut.io.publishPermit.poke(true.B)
      dut.io.publishFire.expect(true.B)
      dut.clock.step()
      dut.io.publishPermit.poke(false.B)
      dut.io.recoveryRequest.ready.expect(true.B)
      dut.clock.step()
      dut.io.recoveryRequest.valid.poke(false.B)
      waitRecoveryPrepared(dut)
      dut.io.recoveryPrepared.newOccupied.expect(1.U)
      dut.io.recoveryApply.poke(true.B)
      dut.io.recoveryApplied.valid.expect(true.B)
      dut.clock.step()
      dut.io.recoveryApply.poke(false.B)
      dut.io.robOccupiedGroups(1).expect(1.U)
      dut.io.d3PublishedGroups(1).expect(1.U)

      // A stale exact key is consumed by the ROB authority and cannot create a
      // partial D3/BROB/PC mutation.
      pokeRecovery(dut, stid = 1, absoluteRid = 0, bid = 0,
        brobGeneration = 0, residentGeneration = 0,
        transactionId = 0, epoch = 5)
      dut.io.recoveryRequest.ready.expect(true.B)
      dut.clock.step()
      dut.io.recoveryRequest.valid.poke(false.B)
      waitRobRecoveryRejected(dut)
      dut.io.d3RecoveryRejected.valid.expect(false.B)
      dut.io.brobRecoveryRejected.valid.expect(false.B)
      dut.io.pcRecoveryRejected.valid.expect(false.B)
      dut.clock.step()
      dut.io.recoveryBusy.expect(false.B)
      dut.io.robOccupiedGroups(1).expect(1.U)
      dut.io.d3PublishedGroups(1).expect(1.U)
      dut.io.brobUsedBlocks(1).expect(1.U)
      dut.io.pcUsedBases(1).expect(1.U)

      // A retained same-STID commit is another non-retractable Decoupled
      // obligation. Recovery capture waits for it to fire, then the now-missing
      // exact key is rejected by the ROB without reaching lower owners.
      pokeTransaction(dut, stid = 0, transactionId = 0, firstRid = 0,
        tailEpoch = 0, pcs = Seq(900), starts = Set(0), stops = Set(0),
        releases = Set(0))
      reserve(dut)
      dut.io.publishPermit.poke(true.B)
      dut.io.publishFire.expect(true.B)
      dut.clock.step()
      dut.io.publishPermit.poke(false.B)
      complete(dut, stid = 0, absoluteRid = 0, bid = 0,
        brobGeneration = 0, residentGeneration = 1)
      waitForCommit(dut)
      dut.io.commit.valid.expect(true.B)
      dut.io.commit.bits.release.firstGroup.stid.expect(0.U)
      pokeRecovery(dut, stid = 0, absoluteRid = 0, bid = 0,
        brobGeneration = 0, residentGeneration = 1,
        transactionId = 0, epoch = 5)
      dut.io.recoveryRequest.ready.expect(false.B)
      dut.io.commit.ready.poke(true.B)
      dut.clock.step()
      dut.io.commit.ready.poke(false.B)
      dut.io.recoveryRequest.ready.expect(true.B)
      dut.clock.step()
      dut.io.recoveryRequest.valid.poke(false.B)
      waitRobRecoveryRejected(dut)
      dut.io.d3RecoveryRejected.valid.expect(false.B)
      dut.io.brobRecoveryRejected.valid.expect(false.B)
      dut.io.pcRecoveryRejected.valid.expect(false.B)
      dut.clock.step()
      dut.io.recoveryBusy.expect(false.B)
      dut.io.robOccupiedGroups(0).expect(0.U)

      // Move every physical head forward, fill a complete eight-row window,
      // then remove the two rows after RID/BID/PC wrap.  The coordinator must
      // retain the wrap-qualified old/new tails rather than compare slots.
      pokeTransaction(dut, stid = 3, transactionId = 0, firstRid = 0,
        tailEpoch = 0, pcs = Seq(1000, 1200), starts = Set(0, 1),
        stops = Set(0, 1), releases = Set(0, 1))
      reserve(dut)
      dut.io.publishPermit.poke(true.B)
      dut.io.publishFire.expect(true.B)
      dut.clock.step()
      dut.io.publishPermit.poke(false.B)
      complete(dut, stid = 3, absoluteRid = 1, bid = 1,
        brobGeneration = 0, residentGeneration = 1)
      complete(dut, stid = 3, absoluteRid = 0, bid = 0,
        brobGeneration = 0, residentGeneration = 1)
      waitForCommit(dut)
      dut.io.commit.valid.expect(true.B)
      dut.io.commit.ready.poke(true.B)
      dut.clock.step()
      dut.io.commit.ready.poke(false.B)
      dut.io.robOccupiedGroups(3).expect(0.U)

      Seq(
        (1, 2, 2, Seq(1400L, 1600L)),
        (2, 4, 3, Seq(1800L, 2000L)),
        (3, 6, 4, Seq(2200L, 2400L)),
        (4, 8, 5, Seq(2600L, 2800L))).foreach {
        case (transactionId, firstRid, tailEpoch, pcs) =>
          pokeTransaction(dut, stid = 3, transactionId = transactionId,
            firstRid = firstRid, tailEpoch = tailEpoch, pcs = pcs,
            starts = Set(0, 1), stops = Set(0, 1), releases = Set(0, 1))
          reserve(dut)
          dut.io.publishPermit.poke(true.B)
          dut.io.publishFire.expect(true.B)
          dut.clock.step()
          dut.io.publishPermit.poke(false.B)
      }
      dut.io.robOccupiedGroups(3).expect(8.U)
      dut.io.d3PublishedGroups(3).expect(8.U)
      dut.io.brobUsedBlocks(3).expect(8.U)
      dut.io.pcUsedBases(3).expect(8.U)

      pokeRecovery(dut, stid = 3, absoluteRid = 7, bid = 7,
        brobGeneration = 0, residentGeneration = 1,
        transactionId = 3, epoch = 5)
      dut.io.recoveryRequest.ready.expect(true.B)
      dut.clock.step()
      dut.io.recoveryRequest.valid.poke(false.B)
      waitRecoveryPrepared(dut)
      dut.io.recoveryPrepared.oldHead.ridSlot.expect(2.U)
      dut.io.recoveryPrepared.oldHead.ridGeneration.expect(0.U)
      dut.io.recoveryPrepared.oldOccupied.expect(8.U)
      dut.io.recoveryPrepared.newOccupied.expect(6.U)
      dut.io.recoveryPrepared.killedGroupCount.expect(2.U)
      dut.io.recoveryPrepared.firstKilledGroup.ridSlot.expect(0.U)
      dut.io.recoveryPrepared.firstKilledGroup.ridGeneration.expect(1.U)
      dut.io.recoveryPrepared.newTail.ridSlot.expect(0.U)
      dut.io.recoveryPrepared.newTail.ridGeneration.expect(1.U)
      dut.io.recoveryApply.poke(true.B)
      dut.io.recoveryApplied.valid.expect(true.B)
      dut.clock.step()
      dut.io.recoveryApply.poke(false.B)
      dut.io.robOccupiedGroups(3).expect(6.U)
      dut.io.d3PublishedGroups(3).expect(6.U)
      dut.io.brobUsedBlocks(3).expect(6.U)
      dut.io.pcUsedBases(3).expect(6.U)
      dut.io.robOccupiedGroups(1).expect(1.U)
      dut.io.robOccupiedGroups(2).expect(1.U)
    }
  }
}
