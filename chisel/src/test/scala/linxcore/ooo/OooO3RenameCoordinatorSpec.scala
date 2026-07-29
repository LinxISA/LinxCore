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
    dut.io.iexS1.ready.poke(false.B)
    dut.io.fastBoundary.ready.poke(true.B)
    dut.io.fastWriteback.ready.poke(true.B)
    dut.io.fastWakeup.ready.poke(true.B)
    dut.io.fastTrace.ready.poke(true.B)
    dut.io.completion.valid.poke(false.B)
    dut.io.completion.bits.poke(0.U.asTypeOf(dut.io.completion.bits))
    dut.io.nonFlushEvidence.valid.poke(false.B)
    dut.io.nonFlushEvidence.bits.poke(
      0.U.asTypeOf(dut.io.nonFlushEvidence.bits))
    dut.io.interruptPending.foreach(_.poke(false.B))
    dut.io.commit.ready.poke(false.B)
    dut.io.storeCommit.ready.poke(true.B)
    dut.io.ptagRecycle.ready.poke(true.B)
    dut.io.dispatchRelease.valid.poke(false.B)
    dut.io.dispatchRelease.bits.poke(
      0.U.asTypeOf(dut.io.dispatchRelease.bits))
    dut.io.recoveryRequest.valid.poke(false.B)
    dut.io.recoveryRequest.bits.poke(
      0.U.asTypeOf(dut.io.recoveryRequest.bits))
    dut.io.iexRecoveryPrepareReady.poke(true.B)
    dut.io.iexRecoveryPrepared.poke(
      0.U.asTypeOf(dut.io.iexRecoveryPrepared))
    dut.io.iexRecoveryRejected.valid.poke(false.B)
    dut.io.iexRecoveryRejected.bits.poke(
      0.U.asTypeOf(dut.io.iexRecoveryRejected.bits))
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
      uop.recipe.valid.poke(true.B)
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

  private def waitForCommit(
      dut: OooO3RenameCoordinator,
      limit: Int = 32): Unit = {
    var cycles = 0
    while (!dut.io.commit.valid.peek().litToBoolean && cycles < limit) {
      dut.clock.step()
      cycles += 1
    }
    assert(cycles < limit,
      "timed out waiting for the shared P/T/U commit owners")
  }

  private def waitForPCommitBusy(
      dut: OooO3RenameCoordinator,
      limit: Int = 32): Unit = {
    var cycles = 0
    while (!dut.io.pCommitBusy.peek().litToBoolean && cycles < limit) {
      dut.clock.step()
      cycles += 1
    }
    assert(cycles < limit,
      "timed out waiting for the retained P commit walk")
  }

  private def acceptPtagRecycle(
      dut: OooO3RenameCoordinator,
      expectedPtag: Int,
      limit: Int = 16): Unit = {
    var cycles = 0
    while (!dut.io.ptagRecycle.valid.peek().litToBoolean && cycles < limit) {
      dut.clock.step()
      cycles += 1
    }
    assert(cycles < limit, "timed out waiting for retained PTag recycle")
    dut.io.ptagRecycle.bits.count.expect(1.U)
    dut.io.ptagRecycle.bits.tokens(0).ptag.expect(expectedPtag.U)
    dut.clock.step()
  }

  private def pokeLocalRenameChain(
      dut: OooO3RenameCoordinator,
      tailEpoch: Int = 0,
      stid: Int = 0,
      transactionId: Int = 0,
      firstRid: Int = 0): Unit = {
    pokeOneDestination(dut, tailEpoch = tailEpoch, uopCount = 2,
      stid = stid, transactionId = transactionId, firstRid = firstRid)
    val transaction = dut.io.reserve.bits
    transaction.plan.demand.pDestinations.poke(0.U)
    transaction.plan.demand.mapQRows.poke(0.U)
    transaction.plan.demand.tAllocations.poke(1.U)
    transaction.plan.demand.uAllocations.poke(1.U)
    transaction.groups(0).pMapQRows.poke(0.U)

    val producer = transaction.decoded.uops(0)
    producer.sources(0).valid.poke(false.B)
    producer.destinations(0).kind.poke(DestinationKind.T)
    producer.destinations(0).relativeIndex.poke(0.U)

    val consumer = transaction.decoded.uops(1)
    consumer.sources(0).operandClass.poke(OperandClass.T)
    consumer.sources(0).relativeIndex.poke(0.U)
    consumer.destinations(0).kind.poke(DestinationKind.U)
    consumer.destinations(0).relativeIndex.poke(0.U)
  }

  private def pokeMixedRenameTransaction(
      dut: OooO3RenameCoordinator,
      tailEpoch: Int,
      stid: Int,
      transactionId: Int,
      firstRid: Int,
      atag: Int = 1,
      basePc: Int = 512): Unit = {
    pokeOneDestination(dut, tailEpoch = tailEpoch, uopCount = 2,
      stid = stid, transactionId = transactionId, firstRid = firstRid,
      atag = atag, basePc = basePc)
    val transaction = dut.io.reserve.bits
    transaction.plan.demand.pDestinations.poke(1.U)
    transaction.plan.demand.mapQRows.poke(1.U)
    transaction.plan.demand.tAllocations.poke(1.U)
    transaction.plan.demand.uAllocations.poke(1.U)
    transaction.groups(0).pMapQRows.poke(1.U)

    val first = transaction.decoded.uops(0)
    first.destinations(1).valid.poke(true.B)
    first.destinations(1).kind.poke(DestinationKind.T)
    first.destinations(1).relativeIndex.poke(0.U)

    val second = transaction.decoded.uops(1)
    second.destinations(0).kind.poke(DestinationKind.U)
    second.destinations(0).relativeIndex.poke(0.U)
  }

  private def pokeRecoveryRequest(
      dut: OooO3RenameCoordinator,
      stid: Int,
      transactionId: Int,
      rid: Int,
      bid: BigInt,
      brobGeneration: BigInt,
      memberIndex: BigInt,
      residentGeneration: BigInt,
      killTrigger: Boolean,
      epoch: Int = 5): Unit = {
    val global = dut.io.recoveryRequest.bits
    val request = global.rename
    request.poke(0.U.asTypeOf(request))
    request.key.member.group.valid.poke(true.B)
    request.key.member.group.peId.poke(3.U)
    request.key.member.group.stid.poke(stid.U)
    request.key.member.group.ridSlot.poke(
      (rid % dut.p.robGroupsPerStid).U)
    request.key.member.group.ridGeneration.poke(
      (rid / dut.p.robGroupsPerStid).U)
    request.key.member.bid.valid.poke(true.B)
    request.key.member.bid.value.poke(bid.U)
    request.key.member.brobGeneration.poke(brobGeneration.U)
    request.key.member.memberIndex.poke(memberIndex.U)
    request.key.member.residentGeneration.poke(residentGeneration.U)
    request.key.cause.poke(OooRecoveryCause.Branch)
    request.key.transactionId.poke(transactionId.U)
    request.key.epoch.poke(epoch.U)
    request.killTrigger.poke(killTrigger.B)
    global.triggerMemberCount.poke(1.U)
    dut.io.iexRecoveryPrepared.valid.poke(true.B)
    dut.io.iexRecoveryPrepared.stid.poke(stid.U)
  }

  test("joins D3 PTag ROB BROB PC SMAP and MapQ publication on one fire") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      iqBankCount = 2,
      iqEntriesPerBank = 4,
      iqWritePortsPerBank = 2,
      robGroupsPerStid = 8,
      tuRetireSourceDepthPerStid = 16,
      brobEntriesPerStid = 8,
      pMapQDepthPerStid = 4,
      pTagStagingDepthPerBank = 2,
      pTagReturnWidth = 4)
    simulate(new OooO3RenameCoordinator(p)) { dut =>
      clear(dut)
      dut.clock.step() // refill the PTag staging rows
      pokeOneDestination(dut)
      dut.io.reserve.bits.decoded.uops(0).recipe.valid.poke(true.B)
      dut.io.reserve.bits.decoded.uops(0).recipe.dispatchWrites.poke(1.U)
      dut.io.reserve.bits.decoded.uops(0).recipe.dispatchDemand(0).poke(1.U)
      dut.io.reserve.bits.decoded.uops(0).recipe.dispatchCapabilities(0).poke(
        OooIexDomainCapability.mask(OooIexDomainCapability.SimpleAlu).U)
      dut.io.reserve.bits.plan.demand.dispatchWritesByClass(0).poke(1.U)
      dut.io.reserve.ready.expect(true.B)
      dut.clock.step()
      dut.io.reserve.valid.poke(false.B)

      dut.io.tuPublicationRejected.valid.expect(false.B)
      dut.io.tuPrepared.valid.expect(true.B)
      dut.io.prepared.valid.expect(true.B)
      dut.io.preparedValid.expect(true.B)
      dut.io.prepared.uops(0).sources(0).pMapping.ptag.expect(1.U)
      dut.io.prepared.uops(0).destinations(0)
        .currentPMapping.ptag.expect(96.U)
      dut.io.prepared.uops(0).destinations(0)
        .currentPMapping.producerBindingValid.expect(true.B)
      dut.io.prepared.uops(0).destinations(0)
        .currentPMapping.producerIqClass.expect(OooUopClass.Alu)
      dut.io.prepared.uops(0).destinations(0)
        .currentPMapping.producerIqBank.expect(0.U)
      dut.io.prepared.uops(0).destinations(0)
        .currentPMapping.producerIqEntry.expect(0.U)
      dut.io.prepared.uops(0).destinations(0)
        .currentPMapping.producerIqEpoch.expect(1.U)
      dut.io.dispatchPrepared.valid.expect(true.B)
      dut.io.dispatchPrepared.allocations(0).reservation
        .uopClass.expect(OooUopClass.Alu)
      dut.io.ptagProvisionalCount.expect(1.U)
      dut.io.ptagPublishedCount.expect(0.U)
      dut.io.mapQUsed(0).expect(0.U)
      dut.io.robOccupiedGroups(0).expect(0.U)
      dut.io.iexS1.valid.expect(true.B)
      dut.io.iexS1.bits.o3.request.reservation.transaction.plan
        .transactionId.expect(0.U)
      dut.io.iexS1.bits.pRename.uops(0).destinations(0)
        .currentPMapping.ptag.expect(96.U)
      dut.io.iexS1.bits.dispatch.allocations(0).reservation
        .reservationEpoch.expect(1.U)
      dut.clock.step(2)
      dut.io.ptagProvisionalCount.expect(1.U)
      dut.io.mapQUsed(0).expect(0.U)
      dut.io.iexS1.valid.expect(true.B)
      dut.io.iexS1.bits.o3.request.reservation.transaction.plan
        .transactionId.expect(0.U)
      dut.io.iexS1.bits.pRename.uops(0).destinations(0)
        .currentPMapping.ptag.expect(96.U)
      dut.io.iexS1.bits.dispatch.allocations(0).reservation
        .reservationEpoch.expect(1.U)

      dut.io.iexS1.ready.poke(true.B)
      dut.io.publishFire.expect(true.B)
      dut.clock.step()
      dut.io.iexS1.ready.poke(false.B)
      dut.io.ptagProvisionalCount.expect(0.U)
      dut.io.ptagPublishedCount.expect(1.U)
      dut.io.mapQUsed(0).expect(1.U)
      dut.io.robOccupiedGroups(0).expect(1.U)
      dut.io.dispatchPublishedEntries(0)(0).expect(1.U)
      dut.io.queryAtag.poke(1.U)
      dut.io.speculativeMapping.ptag.expect(96.U)
      dut.io.committedMapping.ptag.expect(1.U)

      val dispatchRelease = dut.io.dispatchRelease.bits
      dispatchRelease.poke(0.U.asTypeOf(dispatchRelease))
      dispatchRelease.peId.poke(3.U)
      dispatchRelease.stid.poke(0.U)
      dispatchRelease.epoch.poke(5.U)
      dispatchRelease.transactionId.poke(0.U)
      dispatchRelease.member.group.valid.poke(true.B)
      dispatchRelease.member.group.peId.poke(3.U)
      dispatchRelease.member.group.stid.poke(0.U)
      dispatchRelease.member.group.ridSlot.poke(0.U)
      dispatchRelease.member.group.ridGeneration.poke(0.U)
      dispatchRelease.member.bid.valid.poke(true.B)
      dispatchRelease.member.bid.value.poke(0.U)
      dispatchRelease.member.brobGeneration.poke(0.U)
      dispatchRelease.member.memberIndex.poke(0.U)
      dispatchRelease.member.residentGeneration.poke(1.U)
      dispatchRelease.reservation.valid.poke(true.B)
      dispatchRelease.reservation.uopClass.poke(OooUopClass.Alu)
      dispatchRelease.reservation.bank.poke(0.U)
      dispatchRelease.reservation.writePort.poke(0.U)
      dispatchRelease.reservation.speculativeSlot.poke(0.U)
      dispatchRelease.reservation.reservationEpoch.poke(1.U)
      dut.io.dispatchRelease.valid.poke(true.B)
      dut.io.dispatchRelease.ready.expect(true.B)
      dut.clock.step()
      dut.io.dispatchRelease.valid.poke(false.B)
      dut.io.dispatchPublishedEntries(0)(0).expect(0.U)

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
      waitForPCommitBusy(dut)
      dut.io.pCommitRejected.valid.expect(false.B)
      dut.io.pCommitBusy.expect(true.B)
      dut.io.commit.valid.expect(false.B)
      dut.clock.step() // return the replaced reset mapping and advance CMAP
      waitForCommit(dut)
      dut.io.commit.ready.poke(true.B)
      dut.clock.step()
      dut.io.commit.ready.poke(false.B)
      dut.io.robOccupiedGroups(0).expect(0.U)
      dut.io.mapQUsed(0).expect(0.U)
      dut.io.committedMapping.ptag.expect(96.U)
      dut.io.ptagPublishedCount.expect(1.U)
    }
  }

  test("publishes T and U sequential rename on the same O3 terminal fire") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      iqBankCount = 2,
      iqEntriesPerBank = 4,
      iqWritePortsPerBank = 2,
      robGroupsPerStid = 8,
      tuRetireSourceDepthPerStid = 16,
      brobEntriesPerStid = 8,
      pMapQDepthPerStid = 4,
      pTagStagingDepthPerBank = 2,
      pTagReturnWidth = 4,
      tPhysRegs = 8,
      uPhysRegs = 8,
      tuMapQDepthPerStid = 8)
    simulate(new OooO3RenameCoordinator(p)) { dut =>
      clear(dut)
      dut.clock.step()
      pokeLocalRenameChain(dut, stid = 1, transactionId = 0)
      dut.io.reserve.ready.expect(true.B)
      dut.clock.step()
      dut.io.reserve.valid.poke(false.B)

      dut.io.tuPublicationRejected.valid.expect(false.B)
      dut.io.tuPrepared.valid.expect(true.B)
      dut.io.prepared.valid.expect(true.B)
      dut.io.preparedValid.expect(true.B)
      dut.io.tuPrepared.uops(1).sources(0).valid.expect(true.B)
      dut.io.tuPrepared.uops(1).sources(0).physicalTag.expect(0.U)
      dut.io.tuPrepared.uops(0).destinations(0).physicalTag.expect(0.U)
      dut.io.tuPrepared.uops(1).destinations(0).physicalTag.expect(0.U)
      dut.io.tuPrepared.rows(0).member.memberIndex.expect(0.U)
      dut.io.tuPrepared.rows(2).member.memberIndex.expect(1.U)
      dut.io.tMapQUsed(1).expect(0.U)
      dut.io.uMapQUsed(1).expect(0.U)

      dut.io.iexS1.ready.poke(true.B)
      dut.io.publishFire.expect(true.B)
      dut.clock.step()
      dut.io.iexS1.ready.poke(false.B)
      dut.io.tMapQUsed(1).expect(1.U)
      dut.io.uMapQUsed(1).expect(1.U)
      dut.io.tMapQUsed(0).expect(0.U)
      dut.io.robOccupiedGroups(1).expect(1.U)

      completeMembers(dut, stid = 1, rid = 0, memberCount = 2)
      waitForCommit(dut)
      dut.io.tuCommitBusy.expect(true.B)
      dut.io.commit.ready.poke(true.B)
      dut.clock.step()
      dut.io.commit.ready.poke(false.B)
      dut.io.tMapQUsed(1).expect(0.U)
      dut.io.uMapQUsed(1).expect(0.U)
      dut.io.tuRetireSourceUsed(1).expect(0.U)
      dut.io.tRelationUsed(1).expect(0.U)
      dut.io.uRelationUsed(1).expect(0.U)
      dut.io.robOccupiedGroups(1).expect(0.U)
    }
  }

  test("consumes stale T/U-underflow plans without orphaning rename leases") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      iqBankCount = 2,
      iqEntriesPerBank = 4,
      iqWritePortsPerBank = 2,
      robGroupsPerStid = 8,
      tuRetireSourceDepthPerStid = 16,
      brobEntriesPerStid = 8,
      pMapQDepthPerStid = 4,
      pTagStagingDepthPerBank = 2,
      pTagReturnWidth = 4)
    simulate(new OooO3RenameCoordinator(p)) { dut =>
      clear(dut)
      dut.clock.step()
      pokeOneDestination(dut, tailEpoch = 1)
      dut.io.reserve.bits.decoded.uops(0).sources(0)
        .operandClass.poke(OperandClass.T)
      dut.io.reserve.bits.decoded.uops(0).sources(0)
        .relativeIndex.poke(0.U)
      dut.io.tuReserveRejected.valid.expect(true.B)
      dut.io.reserve.ready.expect(true.B)
      dut.clock.step()
      dut.io.reserve.valid.poke(false.B)
      dut.io.preparedValid.expect(false.B)
      dut.io.ptagProvisionalCount.expect(0.U)
      dut.io.ptagPublishedCount.expect(0.U)
      dut.io.mapQUsed(0).expect(0.U)
      dut.io.tMapQUsed(0).expect(0.U)
      dut.io.uMapQUsed(0).expect(0.U)
      dut.io.robOccupiedGroups(0).expect(0.U)

      // Stale consumption changes no D3 transaction/tail or rename lease.
      pokeOneDestination(dut, tailEpoch = 0, transactionId = 0)
      dut.io.reserve.ready.expect(true.B)
    }
  }

  test("serializes WAW CMAP commit and returns the exact old PTag before ROB deallocation") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      iqBankCount = 2,
      iqEntriesPerBank = 4,
      iqWritePortsPerBank = 2,
      robGroupsPerStid = 8,
      tuRetireSourceDepthPerStid = 16,
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
      dut.io.iexS1.ready.poke(true.B)
      dut.io.publishFire.expect(true.B)
      dut.clock.step()
      dut.io.iexS1.ready.poke(false.B)
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

      waitForPCommitBusy(dut)
      dut.io.ptagRecycle.valid.expect(false.B)
      acceptPtagRecycle(dut, expectedPtag = 1)
      dut.io.queryAtag.poke(1.U)
      dut.io.committedMapping.ptag.expect(96.U)
      dut.io.mapQUsed(0).expect(1.U)
      dut.io.ptagPublishedCount.expect(2.U)
      dut.io.ptagRecycle.valid.expect(false.B)
      acceptPtagRecycle(dut, expectedPtag = 96)
      dut.io.committedMapping.ptag.expect(97.U)
      dut.io.mapQUsed(0).expect(0.U)
      dut.io.ptagPublishedCount.expect(1.U)
      waitForCommit(dut)
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
      iqBankCount = 2,
      iqEntriesPerBank = 4,
      iqWritePortsPerBank = 2,
      robGroupsPerStid = 8,
      tuRetireSourceDepthPerStid = 16,
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
      dut.io.iexS1.ready.poke(true.B)
      dut.io.publishFire.expect(true.B)
      dut.clock.step()
      dut.io.iexS1.ready.poke(false.B)
      dut.io.mapQUsed(0).expect(2.U)

      pokeOneDestination(dut, tailEpoch = 1, uopCount = 2,
        transactionId = 1, firstRid = 1, atag = 2, basePc = 512)
      dut.io.reserve.ready.expect(true.B)
      dut.clock.step()
      dut.io.reserve.valid.poke(false.B)
      dut.io.preparedValid.expect(false.B) // MapQ is full; row stays provisional.

      completeMembers(dut, stid = 0, rid = 0, memberCount = 2)
      waitForPCommitBusy(dut)
      dut.io.preparedValid.expect(false.B)
      dut.clock.step(2) // width-one return of identity PTag 1, then PTag 96
      waitForCommit(dut)
      dut.io.commit.ready.poke(true.B)
      dut.clock.step()
      dut.io.commit.ready.poke(false.B)
      dut.io.pCommitBusy.expect(false.B)
      dut.io.mapQUsed(0).expect(0.U)

      dut.io.preparedValid.expect(true.B)
      dut.io.iexS1.ready.poke(true.B)
      dut.io.publishFire.expect(true.B)
      dut.clock.step()
      dut.io.iexS1.ready.poke(false.B)
      dut.io.mapQUsed(0).expect(2.U)
      dut.io.robOccupiedGroups(0).expect(1.U)
    }
  }

  test("retains an exposed younger prepare before starting same-STID commit") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      iqBankCount = 2,
      iqEntriesPerBank = 4,
      iqWritePortsPerBank = 2,
      robGroupsPerStid = 8,
      tuRetireSourceDepthPerStid = 16,
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
      dut.io.iexS1.ready.poke(true.B)
      dut.io.publishFire.expect(true.B)
      dut.clock.step()
      dut.io.iexS1.ready.poke(false.B)

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

      dut.io.iexS1.ready.poke(true.B)
      dut.io.publishFire.expect(true.B)
      dut.clock.step()
      dut.io.iexS1.ready.poke(false.B)
      dut.io.mapQUsed(0).expect(2.U)

      dut.clock.step() // now the older retained ROB batch may lock
      dut.io.pCommitBusy.expect(true.B)
      dut.clock.step() // return reset PTag 1 and commit the first MapQ row
      waitForCommit(dut)
      dut.io.commit.ready.poke(true.B)
      dut.clock.step()
      dut.io.commit.ready.poke(false.B)
      dut.io.pCommitBusy.expect(false.B)
      dut.io.mapQUsed(0).expect(1.U)
      dut.io.robOccupiedGroups(0).expect(1.U)
    }
  }

  test("recovers P T and U atomically while unrelated STIDs keep publishing") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      iqBankCount = 2,
      iqEntriesPerBank = 4,
      iqWritePortsPerBank = 2,
      robGroupsPerStid = 8,
      tuRetireSourceDepthPerStid = 16,
      brobEntriesPerStid = 8,
      pMapQDepthPerStid = 8,
      pTagStagingDepthPerBank = 2,
      pTagReturnWidth = 1,
      tPhysRegs = 8,
      uPhysRegs = 8,
      tuMapQDepthPerStid = 8)
    simulate(new OooO3RenameCoordinator(p)) { dut =>
      clear(dut)
      dut.clock.step()

      // Publish the exact survivor anchor on STID 1, transaction zero.
      pokeOneDestination(dut, stid = 1, transactionId = 0, firstRid = 0,
        atag = 1, basePc = 256)
      dut.io.reserve.ready.expect(true.B)
      dut.clock.step()
      dut.io.reserve.valid.poke(false.B)
      dut.io.preparedValid.expect(true.B)
      val survivorPtag = dut.io.prepared.uops(0).destinations(0)
        .currentPMapping.ptag.peek().litValue
      val anchor = dut.io.tuPrepared.uops(0).member
      val anchorBid = anchor.bid.value.peek().litValue
      val anchorBrobGeneration = anchor.brobGeneration.peek().litValue
      val anchorMemberIndex = anchor.memberIndex.peek().litValue
      val anchorResidentGeneration = anchor.residentGeneration.peek().litValue
      dut.io.iexS1.ready.poke(true.B)
      dut.io.publishFire.expect(true.B)
      dut.clock.step()
      dut.io.iexS1.ready.poke(false.B)

      // Publish a younger transaction whose suffix owns P, T, and U state.
      pokeMixedRenameTransaction(dut, tailEpoch = 1, stid = 1,
        transactionId = 1, firstRid = 1)
      dut.io.reserve.ready.expect(true.B)
      dut.clock.step()
      dut.io.reserve.valid.poke(false.B)
      dut.io.preparedValid.expect(true.B)
      val killedPtag = dut.io.prepared.uops(0).destinations(0)
        .currentPMapping.ptag.peek().litValue
      assert(killedPtag != survivorPtag)
      dut.io.iexS1.ready.poke(true.B)
      dut.io.publishFire.expect(true.B)
      dut.clock.step()
      dut.io.iexS1.ready.poke(false.B)
      dut.io.mapQUsed(1).expect(2.U)
      dut.io.tMapQUsed(1).expect(1.U)
      dut.io.uMapQUsed(1).expect(1.U)
      dut.io.tuRetireSourceUsed(1).expect(3.U)
      dut.io.ptagPublishedCount.expect(2.U)

      pokeRecoveryRequest(dut, stid = 1, transactionId = 0, rid = 0,
        bid = anchorBid, brobGeneration = anchorBrobGeneration,
        memberIndex = anchorMemberIndex,
        residentGeneration = anchorResidentGeneration,
        killTrigger = false)
      dut.io.recoveryRequest.valid.poke(true.B)
      dut.io.recoveryRequest.ready.expect(true.B)
      dut.clock.step()
      dut.io.recoveryRequest.valid.poke(false.B)
      dut.io.recoveryBusy.expect(true.B)
      dut.io.recoveryStid.expect(1.U)

      // The target STID is fenced from D3 as soon as scan authority is live.
      pokeOneDestination(dut, tailEpoch = 2, stid = 1,
        transactionId = 2, firstRid = 2, atag = 2, basePc = 768)
      dut.io.reserve.ready.expect(false.B)
      dut.clock.step()

      // STID 2 remains independent and can reserve and publish during recovery.
      pokeOneDestination(dut, stid = 2, transactionId = 0, firstRid = 0,
        atag = 2, basePc = 1024)
      dut.io.reserve.ready.expect(true.B)
      dut.clock.step()
      dut.io.reserve.valid.poke(false.B)
      dut.io.preparedValid.expect(true.B)
      dut.io.iexS1.ready.poke(true.B)
      dut.io.publishFire.expect(true.B)
      dut.clock.step()
      dut.io.iexS1.ready.poke(false.B)
      dut.io.mapQUsed(2).expect(1.U)

      var cycles = 0
      var sawCommonComplete = false
      var sawTypedComplete = false
      while (dut.io.recoveryBusy.peek().litToBoolean && cycles < 64) {
        sawCommonComplete ||= dut.io.recoveryComplete.peek().litToBoolean
        if (dut.io.recoveryCompleted.valid.peek().litToBoolean) {
          sawTypedComplete = true
          dut.io.recoveryCompleted.bits.rename.key.member.group.stid.expect(1.U)
          dut.io.recoveryCompleted.bits.rename.key.transactionId.expect(0.U)
        }
        dut.clock.step()
        cycles += 1
      }
      assert(cycles < 64, "timed out waiting for atomic rename recovery")
      assert(sawCommonComplete,
        "coordinator never joined scanner, P, and T/U completion")
      assert(sawTypedComplete,
        "coordinator never published the exact completed recovery request")

      dut.io.mapQUsed(1).expect(1.U)
      dut.io.tMapQUsed(1).expect(0.U)
      dut.io.uMapQUsed(1).expect(0.U)
      dut.io.tuRetireSourceUsed(1).expect(1.U)
      dut.io.ptagPublishedCount.expect(2.U)
      dut.io.queryStid.poke(1.U)
      dut.io.queryAtag.poke(1.U)
      dut.io.speculativeMapping.ptag.expect(survivorPtag.U)
      dut.io.mapQUsed(0).expect(0.U)
      dut.io.mapQUsed(2).expect(1.U)
      dut.io.mapQUsed(3).expect(0.U)
      dut.io.tMapQUsed(2).expect(0.U)
      dut.io.uMapQUsed(2).expect(0.U)

      // A stale exact key is diagnosed by the scanner and mutates no owner.
      pokeRecoveryRequest(dut, stid = 1, transactionId = 0, rid = 0,
        bid = anchorBid, brobGeneration = anchorBrobGeneration,
        memberIndex = anchorMemberIndex,
        residentGeneration = anchorResidentGeneration + 1,
        killTrigger = false)
      dut.io.recoveryRequest.valid.poke(true.B)
      dut.io.recoveryRequest.ready.expect(true.B)
      dut.clock.step()
      dut.io.recoveryRequest.valid.poke(false.B)
      var rejectCycles = 0
      while (!dut.io.recoveryRejected.valid.peek().litToBoolean &&
          rejectCycles < 16) {
        dut.clock.step()
        rejectCycles += 1
      }
      assert(rejectCycles < 16, "timed out waiting for stale-key diagnostic")
      dut.io.pRecoveryRejected.valid.expect(false.B)
      dut.io.tuRecoveryRejected.valid.expect(false.B)
      dut.io.mapQUsed(1).expect(1.U)
      dut.io.tuRetireSourceUsed(1).expect(1.U)
      dut.io.ptagPublishedCount.expect(2.U)

      var abortCycles = 0
      var sawTypedAbort = false
      while (dut.io.recoveryBusy.peek().litToBoolean && abortCycles < 32) {
        if (dut.io.recoveryAborted.valid.peek().litToBoolean) {
          sawTypedAbort = true
          dut.io.recoveryAborted.bits.rename.key.member.group.stid.expect(1.U)
          dut.io.recoveryAborted.bits.rename.key.member.residentGeneration
            .expect((anchorResidentGeneration + 1).U)
        }
        dut.clock.step()
        abortCycles += 1
      }
      assert(abortCycles < 32, "timed out draining rejected global recovery")
      assert(sawTypedAbort,
        "coordinator never published the exact aborted recovery request")
    }
  }
}
