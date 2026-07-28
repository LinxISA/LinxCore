package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.common.{DestinationKind, OperandClass}
import org.scalatest.funsuite.AnyFunSuite

class OooPRenameSpec extends AnyFunSuite with ChiselSim {
  private def clear(dut: OooPRename): Unit = {
    dut.io.prepare.valid.poke(false.B)
    dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
    dut.io.ptagLease.poke(0.U.asTypeOf(dut.io.ptagLease))
    dut.io.dispatchLease.poke(0.U.asTypeOf(dut.io.dispatchLease))
    dut.io.publishFire.poke(false.B)
    dut.io.commitPrepare.valid.poke(false.B)
    dut.io.commitPrepare.bits.poke(0.U.asTypeOf(dut.io.commitPrepare.bits))
    dut.io.commitFire.poke(false.B)
    dut.io.ptagReturn.ready.poke(false.B)
    dut.io.queryStid.poke(0.U)
    dut.io.queryAtag.poke(0.U)
    dut.io.recoveryAuthorize.valid.poke(false.B)
    dut.io.recoveryAuthorize.bits.poke(
      0.U.asTypeOf(dut.io.recoveryAuthorize.bits))
    dut.io.recoverySource.valid.poke(false.B)
    dut.io.recoverySource.bits.poke(
      0.U.asTypeOf(dut.io.recoverySource.bits))
    dut.io.recoverySourcesDone.poke(false.B)
    dut.io.recoveryFinish.poke(false.B)
  }

  private def pokeTwoUopChain(
      dut: OooPRename,
      stid: Int,
      transactionId: Int,
      atag: Int,
      firstPtag: Int,
      secondPtag: Int,
      splitAcrossGroups: Boolean = false,
      firstRidSlot: Int = 4,
      firstRidGeneration: Int = 2): Unit = {
    dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
    dut.io.ptagLease.poke(0.U.asTypeOf(dut.io.ptagLease))

    val request = dut.io.prepare.bits.request
    val reservation = request.reservation
    val transaction = reservation.transaction
    transaction.plan.peId.poke(3.U)
    transaction.plan.stid.poke(stid.U)
    transaction.plan.epoch.poke(5.U)
    transaction.plan.transactionId.poke(transactionId.U)
    transaction.plan.uopMask.poke(3.U)
    val groupCount = if (splitAcrossGroups) 2 else 1
    transaction.plan.groupCount.poke(groupCount.U)
    transaction.plan.demand.pDestinations.poke(2.U)
    transaction.plan.demand.mapQRows.poke(2.U)
    transaction.decoded.peId.poke(3.U)
    transaction.decoded.stid.poke(stid.U)
    transaction.decoded.epoch.poke(5.U)
    transaction.decoded.uopMask.poke(3.U)
    transaction.groupMask.poke(((1 << groupCount) - 1).U)
    for (groupIndex <- 0 until groupCount) {
      val group = transaction.groups(groupIndex)
      group.valid.poke(true.B)
      group.key.valid.poke(true.B)
      group.key.peId.poke(3.U)
      group.key.stid.poke(stid.U)
      val absoluteSlot = firstRidSlot + groupIndex
      group.key.ridSlot.poke((absoluteSlot % dut.p.robGroupsPerStid).U)
      group.key.ridGeneration.poke(
        (firstRidGeneration + absoluteSlot / dut.p.robGroupsPerStid).U)
      group.logicalUopMask.poke(
        (if (splitAcrossGroups) 1 << groupIndex else 3).U)
      group.physicalMemberCount.poke(
        (if (splitAcrossGroups) 1 else 2).U)
      group.pMapQRows.poke((if (splitAcrossGroups) 1 else 2).U)
      request.bindings(groupIndex).valid.poke(true.B)
      request.bindings(groupIndex).brob.valid.poke(true.B)
      request.bindings(groupIndex).brob.bid.valid.poke(true.B)
      request.bindings(groupIndex).brob.bid.value.poke(7.U)
      request.bindings(groupIndex).brob.generation.poke(3.U)
      request.bindings(groupIndex).pcBase.valid.poke(true.B)
      request.bindings(groupIndex).residentGeneration.poke(9.U)
    }

    for (uopIndex <- 0 until 2) {
      val uop = transaction.decoded.uops(uopIndex)
      uop.valid.poke(true.B)
      uop.plannedChildCount.poke(1.U)
      uop.sources(0).valid.poke(true.B)
      uop.sources(0).operandClass.poke(OperandClass.P)
      uop.sources(0).atag.poke(atag.U)
      uop.destinations(0).valid.poke(true.B)
      uop.destinations(0).kind.poke(DestinationKind.Gpr)
      uop.destinations(0).atag.poke(atag.U)
      transaction.uopGroupIndex(uopIndex).poke(
        (if (splitAcrossGroups) uopIndex else 0).U)
      transaction.uopMemberBase(uopIndex).poke(
        (if (splitAcrossGroups) 0 else uopIndex).U)
    }

    dut.io.ptagLease.valid.poke(true.B)
    dut.io.ptagLease.peId.poke(3.U)
    dut.io.ptagLease.stid.poke(stid.U)
    dut.io.ptagLease.epoch.poke(5.U)
    dut.io.ptagLease.transactionId.poke(transactionId.U)
    dut.io.ptagLease.allocationMask.poke(5.U) // flat rows 0 and 2
    Seq(firstPtag, secondPtag).zipWithIndex.foreach { case (ptag, uopIndex) =>
      val flatIndex = uopIndex * dut.p.maxDestinationOperands
      val allocation = dut.io.ptagLease.allocations(flatIndex)
      allocation.valid.poke(true.B)
      allocation.uopIndex.poke(uopIndex.U)
      allocation.destinationIndex.poke(0.U)
      allocation.atag.poke(atag.U)
      allocation.token.valid.poke(true.B)
      allocation.token.ptag.poke(ptag.U)
      allocation.token.bank.poke((ptag % dut.p.pTagBanks).U)
      allocation.token.generation.poke(1.U)
    }
    dut.io.prepare.valid.poke(true.B)
  }

  private def publish(dut: OooPRename): Unit = {
    dut.io.prepareReady.expect(true.B)
    dut.io.publishFire.poke(true.B)
    dut.clock.step()
    dut.io.publishFire.poke(false.B)
    dut.io.prepare.valid.poke(false.B)
  }

  private def captureCommitSlice(dut: OooPRename): Unit = {
    dut.clock.step() // retain the ROB batch and enter the MapQ read stage
    dut.io.commitBusy.expect(true.B)
    dut.io.ptagReturn.valid.expect(false.B)
    dut.clock.step() // register the selected physical-subbank rows
    dut.io.ptagReturn.valid.expect(true.B)
  }

  private def captureNextCommitSlice(dut: OooPRename): Unit = {
    dut.io.ptagReturn.valid.expect(false.B)
    dut.clock.step()
    dut.io.ptagReturn.valid.expect(true.B)
  }

  private def pokeOneUopMapping(
      dut: OooPRename,
      stid: Int,
      transactionId: Int,
      atag: Int,
      ptag: Int): Unit = {
    pokeTwoUopChain(dut, stid, transactionId, atag, ptag, ptag + 1)
    val transaction = dut.io.prepare.bits.request.reservation.transaction
    transaction.plan.uopMask.poke(1.U)
    transaction.plan.demand.pDestinations.poke(1.U)
    transaction.plan.demand.mapQRows.poke(1.U)
    transaction.decoded.uopMask.poke(1.U)
    transaction.groups(0).logicalUopMask.poke(1.U)
    transaction.groups(0).physicalMemberCount.poke(1.U)
    transaction.groups(0).pMapQRows.poke(1.U)
    transaction.decoded.uops(1).valid.poke(false.B)
    transaction.decoded.uops(1).plannedChildCount.poke(0.U)
    transaction.decoded.uops(1).sources(0).valid.poke(false.B)
    transaction.decoded.uops(1).destinations(0).valid.poke(false.B)
    dut.io.ptagLease.allocationMask.poke(1.U)
    dut.io.ptagLease.allocations(2).poke(
      0.U.asTypeOf(dut.io.ptagLease.allocations(2)))
  }

  private def pokeOneGroupCommit(
      dut: OooPRename,
      stid: Int,
      transactionId: Int,
      pRows: Int,
      firstRidSlot: Int = 4,
      firstRidGeneration: Int = 2,
      logicalMask: Int = 3,
      physicalMemberCount: Int = 2): Unit = {
    dut.io.commitPrepare.bits.poke(0.U.asTypeOf(dut.io.commitPrepare.bits))
    val batch = dut.io.commitPrepare.bits
    batch.release.firstGroup.valid.poke(true.B)
    batch.release.firstGroup.peId.poke(3.U)
    batch.release.firstGroup.stid.poke(stid.U)
    batch.release.firstGroup.ridSlot.poke(firstRidSlot.U)
    batch.release.firstGroup.ridGeneration.poke(firstRidGeneration.U)
    batch.release.groupCount.poke(1.U)
    val group = batch.groups(0)
    group.valid.poke(true.B)
    group.key.valid.poke(true.B)
    group.key.peId.poke(3.U)
    group.key.stid.poke(stid.U)
    group.key.ridSlot.poke(firstRidSlot.U)
    group.key.ridGeneration.poke(firstRidGeneration.U)
    group.transactionId.poke(transactionId.U)
    group.brob.valid.poke(true.B)
    group.brob.bid.valid.poke(true.B)
    group.brob.bid.value.poke(7.U)
    group.brob.generation.poke(3.U)
    group.pcBase.valid.poke(true.B)
    group.residentGeneration.poke(9.U)
    group.logicalUopMask.poke(logicalMask.U)
    group.physicalMemberCount.poke(physicalMemberCount.U)
    group.completedMembers.poke(((1 << physicalMemberCount) - 1).U)
    group.pMapQRows.poke(pRows.U)
    dut.io.commitPrepare.valid.poke(true.B)
  }

  private def pokeTwoGroupCommit(
      dut: OooPRename,
      stid: Int,
      transactionId: Int,
      firstRidSlot: Int = 4,
      firstRidGeneration: Int = 2): Unit = {
    pokeOneGroupCommit(dut, stid, transactionId, pRows = 1,
      firstRidSlot = firstRidSlot, firstRidGeneration = firstRidGeneration)
    val batch = dut.io.commitPrepare.bits
    batch.release.groupCount.poke(2.U)
    val second = batch.groups(1)
    second.valid.poke(true.B)
    second.key.valid.poke(true.B)
    second.key.peId.poke(3.U)
    second.key.stid.poke(stid.U)
    val secondAbsoluteSlot = firstRidSlot + 1
    second.key.ridSlot.poke(
      (secondAbsoluteSlot % dut.p.robGroupsPerStid).U)
    second.key.ridGeneration.poke(
      (firstRidGeneration + secondAbsoluteSlot / dut.p.robGroupsPerStid).U)
    second.transactionId.poke(transactionId.U)
    second.brob.valid.poke(true.B)
    second.brob.bid.valid.poke(true.B)
    second.brob.bid.value.poke(7.U)
    second.brob.generation.poke(3.U)
    second.pcBase.valid.poke(true.B)
    second.residentGeneration.poke(9.U)
    second.logicalUopMask.poke(2.U)
    second.physicalMemberCount.poke(1.U)
    second.completedMembers.poke(1.U)
    second.pMapQRows.poke(1.U)
    val first = batch.groups(0)
    first.logicalUopMask.poke(1.U)
    first.physicalMemberCount.poke(1.U)
    first.completedMembers.poke(1.U)
  }

  private def pokeRecoveryRequest(
      request: OooRenameRecoveryRequest,
      dut: OooPRename,
      stid: Int,
      transactionId: Int,
      uopIndex: Int,
      killTrigger: Boolean,
      epoch: Int = 5): Unit = {
    request.poke(0.U.asTypeOf(request))
    request.key.member.group.valid.poke(true.B)
    request.key.member.group.peId.poke(3.U)
    request.key.member.group.stid.poke(stid.U)
    request.key.member.group.ridSlot.poke(4.U)
    request.key.member.group.ridGeneration.poke(2.U)
    request.key.member.bid.valid.poke(true.B)
    request.key.member.bid.value.poke(7.U)
    request.key.member.brobGeneration.poke(3.U)
    request.key.member.memberIndex.poke(uopIndex.U)
    request.key.member.residentGeneration.poke(9.U)
    request.key.cause.poke(OooRecoveryCause.Branch)
    request.key.transactionId.poke(transactionId.U)
    request.key.epoch.poke(epoch.U)
    request.killTrigger.poke(killTrigger.B)
  }

  private def startRecovery(
      dut: OooPRename,
      stid: Int,
      transactionId: Int,
      uopIndex: Int,
      killTrigger: Boolean): Unit = {
    pokeRecoveryRequest(dut.io.recoveryAuthorize.bits, dut, stid,
      transactionId, uopIndex, killTrigger)
    dut.io.recoveryAuthorize.valid.poke(true.B)
    dut.io.recoveryAuthorize.ready.expect(true.B)
    dut.clock.step()
    dut.io.recoveryAuthorize.valid.poke(false.B)
    dut.io.recoveryBusy.expect(true.B)
  }

  private def sendKilledSource(
      dut: OooPRename,
      triggerStid: Int,
      triggerTransactionId: Int,
      triggerUopIndex: Int,
      killedTransactionId: Int,
      killedUopIndex: Int,
      last: Boolean): Unit = {
    val transfer = dut.io.recoverySource.bits
    transfer.poke(0.U.asTypeOf(transfer))
    pokeRecoveryRequest(transfer.request, dut, triggerStid,
      triggerTransactionId, triggerUopIndex, killTrigger = false)
    val source = transfer.source
    source.valid.poke(true.B)
    source.transactionId.poke(killedTransactionId.U)
    source.epoch.poke(5.U)
    source.uopIndex.poke(killedUopIndex.U)
    source.member.group.valid.poke(true.B)
    source.member.group.peId.poke(3.U)
    source.member.group.stid.poke(triggerStid.U)
    source.member.group.ridSlot.poke(4.U)
    source.member.group.ridGeneration.poke(2.U)
    source.member.bid.valid.poke(true.B)
    source.member.bid.value.poke(7.U)
    source.member.brobGeneration.poke(3.U)
    source.member.memberIndex.poke(killedUopIndex.U)
    source.member.residentGeneration.poke(9.U)
    source.pDestinationCount.poke(1.U)
    transfer.last.poke(last.B)
    dut.io.recoverySource.valid.poke(true.B)
    dut.io.recoverySource.ready.expect(true.B)
    dut.clock.step()
    dut.io.recoverySource.valid.poke(false.B)
  }

  private def returnKilledTag(
      dut: OooPRename,
      expectedPtag: Int): Unit = {
    dut.io.ptagReturn.valid.expect(false.B)
    dut.clock.step() // register the killed tail row before returning its PTag
    dut.io.ptagReturn.valid.expect(true.B)
    dut.io.ptagReturn.bits.count.expect(1.U)
    dut.io.ptagReturn.bits.tokens(0).ptag.expect(expectedPtag.U)
    dut.io.ptagReturn.bits.tokens(0).generation.expect(1.U)
    dut.io.ptagReturn.ready.poke(true.B)
    dut.clock.step()
    dut.io.ptagReturn.ready.poke(false.B)
  }

  private def finishRecovery(dut: OooPRename): Unit = {
    var cycles = 0
    while (!dut.io.recoveryComplete.peek().litToBoolean && cycles < 64) {
      dut.clock.step()
      cycles += 1
    }
    assert(cycles < 64, "timed out waiting for P recovery replay")
    dut.io.recoveryFinish.poke(true.B)
    dut.clock.step()
    dut.io.recoveryFinish.poke(false.B)
    dut.io.recoveryBusy.expect(false.B)
  }

  test("forwards same-transaction P RAW and WAW while publishing exact MapQ rows") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      pMapQDepthPerStid = 4,
      pTagStagingDepthPerBank = 2,
      pTagReturnWidth = 4)
    simulate(new OooPRename(p)) { dut =>
      clear(dut)
      dut.io.queryStid.poke(1.U)
      dut.io.queryAtag.poke(3.U)
      dut.io.speculativeMapping.valid.expect(true.B)
      dut.io.speculativeMapping.ptag.expect(27.U)
      dut.io.committedMapping.ptag.expect(27.U)

      pokeTwoUopChain(dut, stid = 1, transactionId = 10, atag = 3,
        firstPtag = 96, secondPtag = 97)
      dut.io.prepareReady.expect(true.B)
      dut.io.prepared.uops(0).sources(0).pMapping.ptag.expect(27.U)
      dut.io.prepared.uops(1).sources(0).pMapping.ptag.expect(96.U)
      dut.io.prepared.uops(0).destinations(0)
        .previousPMapping.ptag.expect(27.U)
      dut.io.prepared.uops(1).destinations(0)
        .previousPMapping.ptag.expect(96.U)
      dut.io.prepared.uops(1).destinations(0)
        .currentPMapping.ptag.expect(97.U)
      dut.io.prepared.uops(1).destinations(0)
        .currentPMapping.producerBindingValid.expect(false.B)
      dut.io.prepared.mapQRowMask.expect(5.U)
      dut.io.prepared.mapQRows(0).mapQIndex.expect(0.U)
      dut.io.prepared.mapQRows(2).mapQIndex.expect(1.U)
      dut.io.prepared.mapQRows(2).member.memberIndex.expect(1.U)
      dut.clock.step(2)
      dut.io.mapQUsed(1).expect(0.U)
      dut.io.speculativeMapping.ptag.expect(27.U)

      publish(dut)
      dut.io.queryStid.poke(1.U)
      dut.io.queryAtag.poke(3.U)
      dut.io.mapQUsed(1).expect(2.U)
      dut.io.speculativeMapping.ptag.expect(97.U)
      dut.io.committedMapping.ptag.expect(27.U)
      dut.io.mapQUsed(0).expect(0.U)

      pokeOneGroupCommit(dut, stid = 1, transactionId = 10, pRows = 2)
      dut.io.commitReady.expect(false.B)
      captureCommitSlice(dut)
      dut.io.ptagReturn.bits.count.expect(2.U)
      dut.io.ptagReturn.bits.tokens(0).ptag.expect(27.U)
      dut.io.ptagReturn.bits.tokens(0).generation.expect(0.U)
      dut.io.ptagReturn.bits.tokens(1).ptag.expect(96.U)
      dut.io.ptagReturn.bits.tokens(1).generation.expect(1.U)
      dut.clock.step(2)
      dut.io.mapQUsed(1).expect(2.U)
      dut.io.committedMapping.ptag.expect(27.U)
      dut.io.ptagReturn.ready.poke(true.B)
      dut.clock.step()
      dut.io.ptagReturn.ready.poke(false.B)
      dut.io.commitReady.expect(true.B)
      dut.io.commitPrepared.mapQRowCount.expect(2.U)
      dut.io.mapQUsed(1).expect(0.U)
      dut.io.committedMapping.ptag.expect(97.U)
      dut.io.commitFire.poke(true.B)
      dut.clock.step()
      dut.io.commitFire.poke(false.B)
      dut.io.commitPrepare.valid.poke(false.B)
      dut.io.commitBusy.expect(false.B)
    }
  }

  test("rejects a malformed lease without mutating speculative state") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      pMapQDepthPerStid = 4,
      pTagStagingDepthPerBank = 2,
      pTagReturnWidth = 4)
    simulate(new OooPRename(p)) { dut =>
      clear(dut)
      pokeTwoUopChain(dut, stid = 2, transactionId = 12, atag = 4,
        firstPtag = 96, secondPtag = 97)
      dut.io.ptagLease.allocations(2).atag.poke(5.U)
      dut.io.prepareReady.expect(false.B)
      dut.io.prepareRejected.valid.expect(true.B)
      dut.clock.step(2)
      dut.io.mapQUsed(2).expect(0.U)
      dut.io.queryStid.poke(2.U)
      dut.io.queryAtag.poke(4.U)
      dut.io.speculativeMapping.ptag.expect(52.U)

      pokeTwoUopChain(dut, stid = 2, transactionId = 12, atag = 4,
        firstPtag = 96, secondPtag = 97)
      dut.io.prepare.bits.request.reservation.transaction.decoded
        .uops(1).plannedChildCount.poke(2.U)
      dut.io.prepareReady.expect(false.B)
      dut.clock.step()
      dut.io.mapQUsed(2).expect(0.U)

      pokeTwoUopChain(dut, stid = 2, transactionId = 12, atag = 4,
        firstPtag = 96, secondPtag = 97)
      dut.io.prepare.bits.request.reservation.transaction
        .uopMemberBase(1).poke(0.U)
      dut.io.prepareReady.expect(false.B)
      dut.clock.step()
      dut.io.mapQUsed(2).expect(0.U)

      pokeTwoUopChain(dut, stid = 2, transactionId = 12, atag = 4,
        firstPtag = 96, secondPtag = 97)
      val transaction = dut.io.prepare.bits.request.reservation.transaction
      for (uopIndex <- 0 until 2) {
        transaction.decoded.uops(uopIndex).recipe.dispatchWrites.poke(1.U)
        transaction.decoded.uops(uopIndex).recipe.dispatchDemand(0).poke(1.U)
      }
      transaction.plan.demand.dispatchWritesByClass(0).poke(2.U)
      dut.io.prepareReady.expect(false.B)
      dut.io.prepareRejected.valid.expect(true.B)
      dut.clock.step()
      dut.io.mapQUsed(2).expect(0.U)
    }
  }

  test("registers MapQ reads before pointer updates at return width one") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      pMapQDepthPerStid = 4,
      pTagStagingDepthPerBank = 2,
      pTagReturnWidth = 1)
    simulate(new OooPRename(p)) { dut =>
      clear(dut)
      pokeTwoUopChain(dut, stid = 0, transactionId = 0, atag = 1,
        firstPtag = 96, secondPtag = 97)
      publish(dut)
      dut.io.queryAtag.poke(1.U)
      pokeOneGroupCommit(dut, stid = 0, transactionId = 0, pRows = 2)
      captureCommitSlice(dut)
      dut.io.ptagReturn.bits.tokens(0).ptag.expect(1.U)
      dut.io.ptagReturn.bits.tokens(0).generation.expect(0.U)
      dut.clock.step(2)
      dut.io.mapQUsed(0).expect(2.U)
      dut.io.committedMapping.ptag.expect(1.U)
      dut.io.ptagReturn.bits.tokens(0).ptag.expect(1.U)

      dut.io.ptagReturn.ready.poke(true.B)
      dut.clock.step() // return reset mapping and commit first row
      dut.io.ptagReturn.ready.poke(false.B)
      dut.io.mapQUsed(0).expect(1.U)
      dut.io.committedMapping.ptag.expect(96.U)
      captureNextCommitSlice(dut)
      dut.io.ptagReturn.bits.count.expect(1.U)
      dut.io.ptagReturn.bits.tokens(0).ptag.expect(96.U)
      dut.io.ptagReturn.bits.tokens(0).generation.expect(1.U)
      dut.clock.step(2)
      dut.io.mapQUsed(0).expect(1.U)
      dut.io.committedMapping.ptag.expect(96.U)
      dut.io.ptagReturn.bits.tokens(0).ptag.expect(96.U)

      dut.io.ptagReturn.ready.poke(true.B)
      dut.clock.step()
      dut.io.ptagReturn.ready.poke(false.B)
      dut.io.mapQUsed(0).expect(0.U)
      dut.io.committedMapping.ptag.expect(97.U)
      dut.io.commitReady.expect(true.B)
      dut.io.commitFire.poke(true.B)
      dut.clock.step()
      dut.io.commitFire.poke(false.B)
      dut.io.commitPrepare.valid.poke(false.B)
      dut.io.commitBusy.expect(false.B)
    }
  }

  test("rejects underreported or stale commit identity with zero mutation") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      pMapQDepthPerStid = 4,
      pTagStagingDepthPerBank = 2,
      pTagReturnWidth = 1)
    simulate(new OooPRename(p)) { dut =>
      clear(dut)
      pokeTwoUopChain(dut, stid = 1, transactionId = 10, atag = 3,
        firstPtag = 96, secondPtag = 97)
      publish(dut)
      dut.io.queryStid.poke(1.U)
      dut.io.queryAtag.poke(3.U)

      pokeOneGroupCommit(dut, stid = 1, transactionId = 10, pRows = 1)
      dut.io.commitRejected.valid.expect(true.B)
      dut.clock.step(2)
      dut.io.commitBusy.expect(false.B)
      dut.io.mapQUsed(1).expect(2.U)
      dut.io.committedMapping.ptag.expect(27.U)

      pokeOneGroupCommit(dut, stid = 1, transactionId = 11, pRows = 2)
      dut.io.commitRejected.valid.expect(true.B)
      dut.clock.step(2)
      dut.io.commitBusy.expect(false.B)
      dut.io.mapQUsed(1).expect(2.U)
      dut.io.committedMapping.ptag.expect(27.U)
      dut.io.ptagReturn.valid.expect(false.B)
    }
  }

  test("validates a wrapped two-group commit before draining either group") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      retireGroupWidth = 2,
      robGroupsPerStid = 4,
      pMapQDepthPerStid = 4,
      pTagStagingDepthPerBank = 2,
      pTagReturnWidth = 2)
    simulate(new OooPRename(p)) { dut =>
      clear(dut)
      pokeTwoUopChain(dut, stid = 2, transactionId = 12, atag = 4,
        firstPtag = 96, secondPtag = 97, splitAcrossGroups = true,
        firstRidSlot = 3, firstRidGeneration = 2)
      dut.io.prepared.mapQRows(0).member.group.ridSlot.expect(3.U)
      dut.io.prepared.mapQRows(2).member.group.ridSlot.expect(0.U)
      dut.io.prepared.mapQRows(2).member.group.ridGeneration.expect(3.U)
      publish(dut)
      dut.io.queryStid.poke(2.U)
      dut.io.queryAtag.poke(4.U)

      pokeTwoGroupCommit(dut, stid = 2, transactionId = 12,
        firstRidSlot = 3, firstRidGeneration = 2)
      dut.io.commitPrepare.bits.groups(1).residentGeneration.poke(10.U)
      dut.io.commitRejected.valid.expect(true.B)
      dut.clock.step(2)
      dut.io.commitBusy.expect(false.B)
      dut.io.mapQUsed(2).expect(2.U)
      dut.io.committedMapping.ptag.expect(52.U)

      pokeTwoGroupCommit(dut, stid = 2, transactionId = 12,
        firstRidSlot = 3, firstRidGeneration = 2)
      captureCommitSlice(dut)
      dut.io.ptagReturn.bits.count.expect(2.U)
      dut.io.ptagReturn.ready.poke(true.B)
      dut.clock.step()
      dut.io.ptagReturn.ready.poke(false.B)
      dut.io.mapQUsed(2).expect(0.U)
      dut.io.committedMapping.ptag.expect(97.U)
      dut.io.commitReady.expect(true.B)
      dut.io.commitFire.poke(true.B)
      dut.clock.step()
      dut.io.commitFire.poke(false.B)
      dut.io.commitPrepare.valid.poke(false.B)
      dut.io.commitBusy.expect(false.B)
    }
  }

  test("applies per-STID MapQ capacity atomically") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      pMapQDepthPerStid = 4,
      pTagStagingDepthPerBank = 2,
      pTagReturnWidth = 4)
    simulate(new OooPRename(p)) { dut =>
      clear(dut)
      pokeTwoUopChain(dut, stid = 0, transactionId = 20, atag = 1,
        firstPtag = 96, secondPtag = 97)
      publish(dut)
      pokeTwoUopChain(dut, stid = 0, transactionId = 22, atag = 2,
        firstPtag = 98, secondPtag = 99)
      dut.io.prepared.mapQRows(0).mapQIndex.expect(2.U)
      dut.io.prepared.mapQRows(2).mapQIndex.expect(3.U)
      publish(dut)
      dut.io.mapQUsed(0).expect(4.U)

      pokeTwoUopChain(dut, stid = 0, transactionId = 24, atag = 3,
        firstPtag = 100, secondPtag = 101)
      dut.io.prepareReady.expect(false.B)
      dut.io.prepareRejected.bits.freeRows.expect(0.U)
      dut.clock.step()
      dut.io.mapQUsed(0).expect(4.U)
      dut.io.queryAtag.poke(3.U)
      dut.io.speculativeMapping.ptag.expect(3.U)
    }
  }

  test("preserves exact commit and recovery across a subbanked MapQ wrap") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      pMapQDepthPerStid = 4,
      pMapQSubbankCount = 2,
      pTagStagingDepthPerBank = 2,
      pTagReturnWidth = 2)
    simulate(new OooPRename(p)) { dut =>
      clear(dut)

      // Advance the logical head/tail by one row, then by two rows, so the
      // next two-row publication occupies logical indices 3 and 0.
      pokeOneUopMapping(dut, stid = 0, transactionId = 20, atag = 1,
        ptag = 96)
      dut.io.prepared.mapQRows(0).mapQIndex.expect(0.U)
      publish(dut)
      pokeOneGroupCommit(dut, stid = 0, transactionId = 20, pRows = 1,
        logicalMask = 1, physicalMemberCount = 1)
      captureCommitSlice(dut)
      dut.io.ptagReturn.bits.count.expect(1.U)
      dut.io.ptagReturn.ready.poke(true.B)
      dut.clock.step()
      dut.io.ptagReturn.ready.poke(false.B)
      dut.io.commitReady.expect(true.B)
      dut.io.commitFire.poke(true.B)
      dut.clock.step()
      dut.io.commitFire.poke(false.B)
      dut.io.commitPrepare.valid.poke(false.B)

      pokeTwoUopChain(dut, stid = 0, transactionId = 22, atag = 2,
        firstPtag = 97, secondPtag = 98)
      dut.io.prepared.mapQRows(0).mapQIndex.expect(1.U)
      dut.io.prepared.mapQRows(2).mapQIndex.expect(2.U)
      publish(dut)
      pokeOneGroupCommit(dut, stid = 0, transactionId = 22, pRows = 2)
      captureCommitSlice(dut)
      dut.io.ptagReturn.bits.count.expect(2.U)
      dut.io.ptagReturn.ready.poke(true.B)
      dut.clock.step()
      dut.io.ptagReturn.ready.poke(false.B)
      dut.io.commitReady.expect(true.B)
      dut.io.commitFire.poke(true.B)
      dut.clock.step()
      dut.io.commitFire.poke(false.B)
      dut.io.commitPrepare.valid.poke(false.B)

      pokeTwoUopChain(dut, stid = 0, transactionId = 24, atag = 3,
        firstPtag = 99, secondPtag = 100)
      dut.io.prepared.mapQRows(0).mapQIndex.expect(3.U)
      dut.io.prepared.mapQRows(2).mapQIndex.expect(0.U)
      publish(dut)
      dut.io.mapQUsed(0).expect(2.U)

      // Kill the youngest wrapped row. Recovery must drain index 0 and replay
      // the surviving index-3 prefix without changing logical queue order.
      startRecovery(dut, stid = 0, transactionId = 24, uopIndex = 0,
        killTrigger = false)
      sendKilledSource(dut, triggerStid = 0, triggerTransactionId = 24,
        triggerUopIndex = 0, killedTransactionId = 24,
        killedUopIndex = 1, last = true)
      returnKilledTag(dut, expectedPtag = 100)
      dut.io.recoverySourcesDone.poke(true.B)
      dut.clock.step()
      dut.io.recoverySourcesDone.poke(false.B)
      finishRecovery(dut)
      dut.io.mapQUsed(0).expect(1.U)
      dut.io.queryAtag.poke(3.U)
      dut.io.speculativeMapping.ptag.expect(99.U)

      pokeOneGroupCommit(dut, stid = 0, transactionId = 24, pRows = 1,
        logicalMask = 1, physicalMemberCount = 1)
      captureCommitSlice(dut)
      dut.io.ptagReturn.bits.count.expect(1.U)
      dut.io.ptagReturn.bits.tokens(0).ptag.expect(3.U)
      dut.io.ptagReturn.ready.poke(true.B)
      dut.clock.step()
      dut.io.ptagReturn.ready.poke(false.B)
      dut.io.commitReady.expect(true.B)
      dut.io.commitFire.poke(true.B)
      dut.clock.step()
      dut.io.commitFire.poke(false.B)
      dut.io.commitPrepare.valid.poke(false.B)
      dut.io.mapQUsed(0).expect(0.U)
      dut.io.committedMapping.ptag.expect(99.U)
    }
  }

  test("rejects an out-of-range six-wide uop group index without mutation") {
    val p = OooParams(
      instructionDecodeWidth = 6,
      decodedUopWidth = 6,
      pMapQDepthPerStid = 4,
      pTagStagingDepthPerBank = 6,
      pTagReturnWidth = 4)
    simulate(new OooPRename(p)) { dut =>
      clear(dut)
      pokeTwoUopChain(dut, stid = 0, transactionId = 0, atag = 1,
        firstPtag = 96, secondPtag = 97)
      dut.io.prepare.bits.request.reservation.transaction
        .uopGroupIndex(1).poke(7.U)
      dut.io.prepareReady.expect(false.B)
      dut.io.prepareRejected.valid.expect(true.B)
      dut.clock.step()
      dut.io.mapQUsed(0).expect(0.U)
      dut.io.queryAtag.poke(1.U)
      dut.io.speculativeMapping.ptag.expect(1.U)
    }
  }

  test("returns killed current PTags and rebuilds SMAP from surviving MapQ rows") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      pMapQDepthPerStid = 8,
      pTagStagingDepthPerBank = 2,
      pTagReturnWidth = 2)
    simulate(new OooPRename(p)) { dut =>
      clear(dut)
      pokeTwoUopChain(dut, stid = 1, transactionId = 10, atag = 3,
        firstPtag = 96, secondPtag = 97)
      publish(dut)
      pokeTwoUopChain(dut, stid = 1, transactionId = 11, atag = 3,
        firstPtag = 98, secondPtag = 99)
      publish(dut)
      dut.io.mapQUsed(1).expect(4.U)
      dut.io.queryStid.poke(1.U)
      dut.io.queryAtag.poke(3.U)
      dut.io.speculativeMapping.ptag.expect(99.U)
      dut.io.committedMapping.ptag.expect(27.U)

      startRecovery(dut, stid = 1, transactionId = 10, uopIndex = 1,
        killTrigger = false)

      // The affected STID is frozen, while an unrelated STID may still rename.
      pokeTwoUopChain(dut, stid = 1, transactionId = 12, atag = 4,
        firstPtag = 100, secondPtag = 101)
      dut.io.prepareReady.expect(false.B)
      pokeTwoUopChain(dut, stid = 2, transactionId = 13, atag = 4,
        firstPtag = 100, secondPtag = 101)
      dut.io.prepareReady.expect(true.B)
      publish(dut)
      dut.io.mapQUsed(2).expect(2.U)

      // Scanner order is youngest logical uop first. Current, not previous,
      // mappings are returned because these rows never became architectural.
      sendKilledSource(dut, triggerStid = 1, triggerTransactionId = 10,
        triggerUopIndex = 1, killedTransactionId = 11,
        killedUopIndex = 1, last = false)
      returnKilledTag(dut, expectedPtag = 99)
      sendKilledSource(dut, triggerStid = 1, triggerTransactionId = 10,
        triggerUopIndex = 1, killedTransactionId = 11,
        killedUopIndex = 0, last = true)
      returnKilledTag(dut, expectedPtag = 98)
      dut.io.mapQUsed(1).expect(2.U)

      dut.io.recoverySourcesDone.poke(true.B)
      dut.clock.step()
      dut.io.recoverySourcesDone.poke(false.B)
      finishRecovery(dut)

      dut.io.queryStid.poke(1.U)
      dut.io.queryAtag.poke(3.U)
      dut.io.speculativeMapping.ptag.expect(97.U)
      dut.io.committedMapping.ptag.expect(27.U)
      dut.io.mapQUsed(1).expect(2.U)

      // The surviving prefix remains an exact, committable MapQ chain.
      pokeOneGroupCommit(dut, stid = 1, transactionId = 10, pRows = 2)
      captureCommitSlice(dut)
      dut.io.ptagReturn.bits.count.expect(2.U)
      dut.io.ptagReturn.bits.tokens(0).ptag.expect(27.U)
      dut.io.ptagReturn.bits.tokens(1).ptag.expect(96.U)
      dut.io.ptagReturn.ready.poke(true.B)
      dut.clock.step()
      dut.io.ptagReturn.ready.poke(false.B)
      dut.io.commitReady.expect(true.B)
      dut.io.commitFire.poke(true.B)
      dut.clock.step()
      dut.io.commitFire.poke(false.B)
      dut.io.commitPrepare.valid.poke(false.B)
      dut.io.committedMapping.ptag.expect(97.U)
    }
  }

  test("replays a transaction-zero survivor without fabricating killed rows") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      pMapQDepthPerStid = 4,
      pTagStagingDepthPerBank = 2,
      pTagReturnWidth = 1)
    simulate(new OooPRename(p)) { dut =>
      clear(dut)
      pokeTwoUopChain(dut, stid = 0, transactionId = 0, atag = 1,
        firstPtag = 96, secondPtag = 97)
      publish(dut)
      startRecovery(dut, stid = 0, transactionId = 0, uopIndex = 1,
        killTrigger = false)
      dut.io.ptagReturn.valid.expect(false.B)
      dut.io.recoverySourcesDone.poke(true.B)
      dut.clock.step()
      dut.io.recoverySourcesDone.poke(false.B)
      finishRecovery(dut)
      dut.io.queryStid.poke(0.U)
      dut.io.queryAtag.poke(1.U)
      dut.io.speculativeMapping.ptag.expect(97.U)
      dut.io.committedMapping.ptag.expect(1.U)
      dut.io.mapQUsed(0).expect(2.U)
    }
  }

  test("rejects malformed authority and gives an exact commit capture priority") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      pMapQDepthPerStid = 4,
      pTagStagingDepthPerBank = 2,
      pTagReturnWidth = 2)
    simulate(new OooPRename(p)) { dut =>
      clear(dut)
      pokeTwoUopChain(dut, stid = 0, transactionId = 20, atag = 1,
        firstPtag = 96, secondPtag = 97)
      publish(dut)

      dut.io.recoveryAuthorize.bits.poke(
        0.U.asTypeOf(dut.io.recoveryAuthorize.bits))
      dut.io.recoveryAuthorize.valid.poke(true.B)
      dut.io.recoveryAuthorize.ready.expect(true.B)
      dut.clock.step()
      dut.io.recoveryAuthorize.valid.poke(false.B)
      dut.io.recoveryRejected.valid.expect(true.B)
      dut.io.recoveryRejected.bits.mapQCount.expect(2.U)
      dut.io.recoveryBusy.expect(false.B)
      dut.io.mapQUsed(0).expect(2.U)

      pokeOneGroupCommit(dut, stid = 0, transactionId = 20, pRows = 2)
      pokeRecoveryRequest(dut.io.recoveryAuthorize.bits, dut, stid = 0,
        transactionId = 20, uopIndex = 1, killTrigger = true)
      dut.io.recoveryAuthorize.valid.poke(true.B)
      dut.io.commitStartReady.expect(true.B)
      dut.io.recoveryAuthorize.ready.expect(false.B)
      dut.clock.step()
      dut.io.recoveryAuthorize.valid.poke(false.B)
      dut.io.commitBusy.expect(true.B)
      dut.io.ptagReturn.valid.expect(false.B)
      dut.clock.step()
      dut.io.ptagReturn.valid.expect(true.B)
      dut.io.ptagReturn.ready.poke(true.B)
      dut.clock.step()
      dut.io.ptagReturn.ready.poke(false.B)
      dut.io.commitReady.expect(true.B)
      dut.io.commitFire.poke(true.B)
      dut.clock.step()
      dut.io.commitFire.poke(false.B)
      dut.io.commitPrepare.valid.poke(false.B)
      dut.io.commitBusy.expect(false.B)
      dut.io.recoveryBusy.expect(false.B)
    }
  }

  test("elaborates P rename at widths 2 4 6 and subbanks 1 2 4") {
    Seq((2, 1), (4, 2), (6, 4)).foreach { case (width, subbanks) =>
      val p = OooParams(
        instructionDecodeWidth = width,
        pMapQDepthPerStid = 4,
        pMapQSubbankCount = subbanks)
      circt.stage.ChiselStage.emitSystemVerilog(new OooPRename(p))
    }
  }
}
