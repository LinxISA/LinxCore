package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import circt.stage.ChiselStage
import linxcore.common.{DestinationKind, OperandClass}
import org.scalatest.funsuite.AnyFunSuite

private object OooIexIssueSpec {
  final case class Allocation(
      uopIndex: Int,
      childIndex: Int,
      uopClass: Int,
      bank: Int,
      port: Int,
      entry: Int,
      reservationEpoch: Int = 1)
}

class OooIexIssueSpec extends AnyFunSuite with ChiselSim {
  import OooIexIssueSpec._

  private def pokeClass(target: OooUopClass.Type, value: Int): Unit =
    value match {
      case 0 => target.poke(OooUopClass.Alu)
      case 1 => target.poke(OooUopClass.Bru)
      case 2 => target.poke(OooUopClass.Agu)
      case 3 => target.poke(OooUopClass.Std)
      case 4 => target.poke(OooUopClass.Fsu)
      case 5 => target.poke(OooUopClass.Sys)
      case 6 => target.poke(OooUopClass.Cmd)
      case 7 => target.poke(OooUopClass.Boundary)
    }

  private def clear(dut: OooIexIssue): Unit = {
    dut.io.s1.valid.poke(false.B)
    dut.io.s1.bits.poke(0.U.asTypeOf(dut.io.s1.bits))
    dut.io.wakeup.foreach { wakeup =>
      wakeup.valid.poke(false.B)
      wakeup.bits.poke(0.U.asTypeOf(wakeup.bits))
    }
    dut.io.loadCancel.foreach(
      _.poke(0.U.asTypeOf(dut.io.loadCancel.head)))
    dut.io.releases.foreach { release =>
      release.valid.poke(false.B)
      release.bits.poke(0.U.asTypeOf(release.bits))
    }
    dut.io.dispatchReleases.foreach(_.ready.poke(false.B))
    dut.io.query.poke(0.U.asTypeOf(dut.io.query))
    dut.io.pickClass.poke(OooUopClass.Alu)
    dut.io.pickBankEnable.poke(0.U)
    dut.io.issuePolicy.poke(0.U.asTypeOf(dut.io.issuePolicy))
    dut.io.pick.ready.poke(false.B)
    dut.io.pickRetry.valid.poke(false.B)
    dut.io.pickRetry.bits.poke(0.U.asTypeOf(dut.io.pickRetry.bits))
    dut.io.recoveryPrepare.valid.poke(false.B)
    dut.io.recoveryPrepare.bits.poke(
      0.U.asTypeOf(dut.io.recoveryPrepare.bits))
    dut.io.recoveryFire.poke(false.B)
    dut.io.ptagRecycle.valid.poke(false.B)
    dut.io.ptagRecycle.bits.poke(0.U.asTypeOf(dut.io.ptagRecycle.bits))
  }

  private def pokeMember(
      target: RobMemberKey,
      stid: Int,
      memberIndex: Int,
      residentGeneration: Int = 7,
      ridSlot: Int = 1): Unit = {
    target.group.valid.poke(true.B)
    target.group.peId.poke(3.U)
    target.group.stid.poke(stid.U)
    target.group.ridSlot.poke(ridSlot.U)
    target.group.ridGeneration.poke(2.U)
    target.bid.valid.poke(true.B)
    target.bid.value.poke(4.U)
    target.brobGeneration.poke(5.U)
    target.memberIndex.poke(memberIndex.U)
    target.residentGeneration.poke(residentGeneration.U)
  }

  private def pokeTransaction(
      dut: OooIexIssue,
      stid: Int,
      transactionId: Int,
      allocations: Vector[Allocation],
      pSourceReady: Option[Boolean] = None,
      pSourceGeneration: Int = 3,
      ridSlot: Int = 1,
      memberBase: Int = 0): Unit = {
    val request = dut.io.s1.bits
    request.poke(0.U.asTypeOf(request))
    val transaction = request.o3.request.reservation.transaction
    transaction.plan.peId.poke(3.U)
    transaction.plan.stid.poke(stid.U)
    transaction.plan.epoch.poke(6.U)
    transaction.plan.transactionId.poke(transactionId.U)

    val activeUops = allocations.map(_.uopIndex).distinct.sorted
    val uopMask = activeUops.map(index => 1 << index).sum
    transaction.decoded.peId.poke(3.U)
    transaction.decoded.stid.poke(stid.U)
    transaction.decoded.epoch.poke(6.U)
    transaction.decoded.uopMask.poke(uopMask.U)

    request.pRename.valid.poke(true.B)
    request.pRename.peId.poke(3.U)
    request.pRename.stid.poke(stid.U)
    request.pRename.epoch.poke(6.U)
    request.pRename.transactionId.poke(transactionId.U)
    request.pRename.uopMask.poke(uopMask.U)
    request.tuRename.valid.poke(true.B)
    request.tuRename.peId.poke(3.U)
    request.tuRename.stid.poke(stid.U)
    request.tuRename.epoch.poke(6.U)
    request.tuRename.transactionId.poke(transactionId.U)
    request.tuRename.uopMask.poke(uopMask.U)
    request.dispatch.valid.poke(true.B)
    request.dispatch.peId.poke(3.U)
    request.dispatch.stid.poke(stid.U)
    request.dispatch.epoch.poke(6.U)
    request.dispatch.transactionId.poke(transactionId.U)
    request.dispatch.allocationMask.poke(
      ((BigInt(1) << allocations.length) - 1).U)

    activeUops.foreach { uopIndex =>
      val childCount = allocations.filter(_.uopIndex == uopIndex)
        .map(_.childIndex).max + 1
      val decoded = transaction.decoded.uops(uopIndex)
      val pUop = request.pRename.uops(uopIndex)
      val tuUop = request.tuRename.uops(uopIndex)
      decoded.valid.poke(true.B)
      decoded.opcode.poke((40 + uopIndex).U)
      decoded.plannedChildCount.poke(childCount.U)
      pUop.valid.poke(true.B)
      pUop.decoded.valid.poke(true.B)
      pUop.decoded.opcode.poke((40 + uopIndex).U)
      pUop.decoded.plannedChildCount.poke(childCount.U)
      tuUop.valid.poke(true.B)
      pokeMember(pUop.member, stid,
        memberIndex = memberBase + uopIndex * 2, ridSlot = ridSlot)
      pokeMember(tuUop.member, stid,
        memberIndex = memberBase + uopIndex * 2, ridSlot = ridSlot)

      Seq(decoded, pUop.decoded).foreach { logical =>
        logical.identity.key.primaryParent.valid.poke(true.B)
        logical.identity.key.primaryParent.peId.poke(3.U)
        logical.identity.key.primaryParent.stid.poke(stid.U)
        logical.identity.key.primaryParent.instructionId
          .poke((100 + uopIndex).U)
        logical.identity.key.primaryParent.epoch.poke(6.U)
        logical.identity.parentCount.poke(1.U)
        logical.recipe.valid.poke(true.B)
        logical.recipe.opcode.poke((40 + uopIndex).U)
        logical.recipe.disposition.poke(OooOpcodeDisposition.Dispatch.U)
        logical.recipe.pcReadRequired.poke(false.B)
        val parent = logical.identity.parents(0)
        parent.key.valid.poke(true.B)
        parent.key.peId.poke(3.U)
        parent.key.stid.poke(stid.U)
        parent.key.instructionId.poke((100 + uopIndex).U)
        parent.key.epoch.poke(6.U)
        parent.prediction.valid.poke(true.B)
        parent.prediction.predictionTag.poke((20 + uopIndex).U)
      }
      val pcToken = request.o3.parentPcTokens(uopIndex)(0)
      pcToken.valid.poke(true.B)
      pcToken.index.poke((uopIndex + stid * 4).U)
      pcToken.byteOffset.poke((uopIndex * 4).U)
      pcToken.allocationEpoch.poke(2.U)

      Seq(decoded.destinations(0), pUop.decoded.destinations(0))
        .foreach { destination =>
          destination.valid.poke(true.B)
          destination.kind.poke(DestinationKind.Gpr)
          destination.atag.poke(3.U)
        }
      pUop.destinations(0).currentPMapping.valid.poke(true.B)
      pUop.destinations(0).currentPMapping.ptag.poke((30 + uopIndex).U)
      pUop.destinations(0).currentPMapping.ptagGeneration.poke(4.U)

      pSourceReady.foreach { ready =>
        decoded.sources(0).valid.poke(true.B)
        decoded.sources(0).operandClass.poke(OperandClass.P)
        decoded.sources(0).atag.poke(2.U)
        pUop.decoded.sources(0).valid.poke(true.B)
        pUop.decoded.sources(0).operandClass.poke(OperandClass.P)
        pUop.decoded.sources(0).atag.poke(2.U)
        pUop.sources(0).decoded.valid.poke(true.B)
        pUop.sources(0).decoded.operandClass.poke(OperandClass.P)
        pUop.sources(0).decoded.atag.poke(2.U)
        pUop.sources(0).pMapping.valid.poke(true.B)
        pUop.sources(0).pMapping.ptag.poke(17.U)
        pUop.sources(0).pMapping.ptagGeneration.poke(pSourceGeneration.U)
        pUop.sources(0).pMapping.ready.poke(ready.B)
        pUop.sources(0).pMapping.stid.poke(stid.U)
        pUop.sources(0).pMapping.epoch.poke(6.U)
      }
    }

    allocations.zipWithIndex.foreach { case (value, lane) =>
      val allocation = request.dispatch.allocations(lane)
      allocation.valid.poke(true.B)
      allocation.uopIndex.poke(value.uopIndex.U)
      allocation.childIndex.poke(value.childIndex.U)
      allocation.reservation.valid.poke(true.B)
      pokeClass(allocation.reservation.uopClass, value.uopClass)
      allocation.reservation.bank.poke(value.bank.U)
      allocation.reservation.writePort.poke(value.port.U)
      allocation.reservation.speculativeSlot.poke(value.entry.U)
      allocation.reservation.reservationEpoch.poke(value.reservationEpoch.U)
    }
    dut.io.s1.valid.poke(true.B)
  }

  private def pokeStoreTransaction(
      dut: OooIexIssue,
      stid: Int,
      transactionId: Int,
      ridSlot: Int,
      memberBase: Int,
      firstLsid: BigInt,
      firstStoreId: BigInt,
      allocations: Vector[Allocation]): Unit = {
    pokeTransaction(dut, stid, transactionId, allocations,
      ridSlot = ridSlot, memberBase = memberBase)
    val request = dut.io.s1.bits
    val transaction = request.o3.request.reservation.transaction
    val decoded = transaction.decoded.uops(0)
    val pUop = request.pRename.uops(0)
    Seq(decoded, pUop.decoded).foreach { logical =>
      logical.recipe.recipeKind.poke(OooOpcodeRecipeKind.ScalarStore.U)
      logical.recipe.lateSplitKind.poke(OooLateSplitKind.StoreAddressData.U)
      logical.recipe.sideEffectOwner.poke(OooSideEffectOwner.Lsu.U)
      logical.recipe.memoryRequestCount.poke(1.U)
      logical.memory.valid.poke(true.B)
      logical.memory.isLoad.poke(false.B)
      logical.memory.isStore.poke(true.B)
      logical.memory.accessBytes.poke(8.U)
      logical.memory.addressSourceMask.poke(1.U)
      logical.memory.dataSourceMask.poke(2.U)
      logical.destinations(0).valid.poke(false.B)
    }
    pUop.destinations(0).currentPMapping.valid.poke(false.B)
    transaction.plan.demand.storeIds.poke(1.U)
    transaction.decoded.demand.storeIds.poke(1.U)

    val memoryOrder = request.memoryOrder
    memoryOrder.valid.poke(true.B)
    memoryOrder.peId.poke(3.U)
    memoryOrder.stid.poke(stid.U)
    memoryOrder.epoch.poke(6.U)
    memoryOrder.transactionId.poke(transactionId.U)
    memoryOrder.uopMask.poke(1.U)
    memoryOrder.before.lsid.poke(firstLsid.U)
    memoryOrder.before.storeId.poke(firstStoreId.U)
    memoryOrder.after.lsid.poke((firstLsid + 1).U)
    memoryOrder.after.storeId.poke((firstStoreId + 1).U)

    val active = memoryOrder.uops(0)
    active.valid.poke(true.B)
    active.memoryValid.poke(true.B)
    active.isLoad.poke(false.B)
    active.isStore.poke(true.B)
    active.requestCount.poke(1.U)
    active.firstLsid.poke(firstLsid.U)
    active.firstTypeId.poke(firstStoreId.U)
    active.before.lsid.poke(firstLsid.U)
    active.before.storeId.poke(firstStoreId.U)
    active.after.lsid.poke((firstLsid + 1).U)
    active.after.storeId.poke((firstStoreId + 1).U)

    for (uopIndex <- 1 until memoryOrder.uops.length) {
      val inactive = memoryOrder.uops(uopIndex)
      inactive.valid.poke(false.B)
      inactive.memoryValid.poke(false.B)
      inactive.isLoad.poke(false.B)
      inactive.isStore.poke(false.B)
      inactive.requestCount.poke(0.U)
      inactive.firstLsid.poke((firstLsid + 1).U)
      inactive.firstTypeId.poke((firstStoreId + 1).U)
      inactive.before.lsid.poke((firstLsid + 1).U)
      inactive.before.storeId.poke((firstStoreId + 1).U)
      inactive.after.lsid.poke((firstLsid + 1).U)
      inactive.after.storeId.poke((firstStoreId + 1).U)
    }
  }

  private def query(
      dut: OooIexIssue,
      uopClass: Int,
      bank: Int,
      entry: Int): Unit = {
    pokeClass(dut.io.query.uopClass, uopClass)
    dut.io.query.bank.poke(bank.U)
    dut.io.query.entry.poke(entry.U)
  }

  private def advanceToS3(dut: OooIexIssue): Unit = {
    dut.io.s1.ready.expect(true.B)
    dut.clock.step() // retained S1
    dut.io.s1.valid.poke(false.B)
    dut.io.s2Bind.valid.expect(true.B)
    dut.clock.step() // exact S2 bind
    dut.io.s3Enable.valid.expect(true.B)
    dut.clock.step() // S3 resident/pick-enable
  }

  test("keeps S1 S2 and S3 as independent retained stages") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      dispatchWidth = 2,
      robGroupsPerStid = 8,
      iqBankCount = 2,
      iqEntriesPerBank = 4,
      iqWritePortsPerBank = 2,
      pMapQDepthPerStid = 4,
      tuMapQDepthPerStid = 4,
      tuRetireSourceDepthPerStid = 16)
    simulate(new OooIexIssue(p)) { dut =>
      clear(dut)
      val allocation = Allocation(0, 0, 0, 0, 0, 1)
      pokeTransaction(dut, 1, 9, Vector(allocation))
      query(dut, 0, 0, 1)
      dut.io.queryState.expect(OooIexIssueSlotState.Free)
      dut.io.s1.ready.expect(true.B)
      dut.clock.step()
      dut.io.s1.valid.poke(false.B)
      dut.io.s1Occupied(1).expect(true.B)
      dut.io.s2Bind.valid.expect(true.B)
      dut.io.s2Bind.bits.transactionId.expect(9.U)
      dut.io.queryState.expect(OooIexIssueSlotState.Free)
      dut.clock.step()
      dut.io.s1Occupied(1).expect(false.B)
      dut.io.s3Enable.valid.expect(true.B)
      dut.io.queryState.expect(OooIexIssueSlotState.BoundS2)
      dut.io.queryPickable.expect(false.B)
      dut.clock.step()
      dut.io.queryState.expect(OooIexIssueSlotState.ResidentS3)
      dut.io.queryPickable.expect(true.B)
      dut.io.queryRow.member.memberIndex.expect(0.U)
      dut.io.queryRow.transactionId.expect(9.U)
      dut.io.queryRow.opcode.expect(40.U)
      dut.io.queryRow.primaryPrediction.valid.expect(true.B)
      dut.io.queryRow.primaryPrediction.predictionTag.expect(20.U)
      dut.io.queryRow.parentPcTokens(0).valid.expect(true.B)
      dut.io.queryRow.pcParentIndexValid.expect(true.B)
      dut.io.queryRow.pcParentIndex.expect(0.U)
      dut.io.queryRow.recipe.pcReadRequired.expect(false.B)
      dut.io.queryRow.destinations(0).valid.expect(true.B)
      dut.io.queryRow.destinations(0).ptag.expect(30.U)
    }
  }

  test("projects split stores into disjoint AGU and STD operand rows") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      dispatchWidth = 2,
      robGroupsPerStid = 8,
      iqBankCount = 2,
      iqEntriesPerBank = 4,
      iqWritePortsPerBank = 2,
      pMapQDepthPerStid = 4,
      tuMapQDepthPerStid = 4,
      tuRetireSourceDepthPerStid = 16)
    simulate(new OooIexIssue(p)) { dut =>
      clear(dut)
      val allocations = Vector(
        Allocation(0, 0, 2, 0, 0, 1),
        Allocation(0, 1, 3, 0, 1, 2))
      pokeTransaction(dut, 1, 10, allocations)
      val decoded = dut.io.s1.bits.o3.request.reservation.transaction.decoded.uops(0)
      val transaction =
        dut.io.s1.bits.o3.request.reservation.transaction
      val pUop = dut.io.s1.bits.pRename.uops(0)
      Seq(decoded, pUop.decoded).foreach { logical =>
        logical.recipe.lateSplitKind.poke(OooLateSplitKind.StoreAddressData.U)
        logical.recipe.sideEffectOwner.poke(OooSideEffectOwner.Lsu.U)
        logical.recipe.memoryRequestCount.poke(1.U)
        logical.memory.valid.poke(true.B)
        logical.memory.isStore.poke(true.B)
        logical.memory.addressMode.poke(OooMemoryAddressMode.BaseIndex)
        logical.memory.accessBytes.poke(8.U)
        logical.memory.addressSourceMask.poke("b0110".U)
        logical.memory.dataSourceMask.poke("b0001".U)
        logical.destinations(0).valid.poke(false.B)
        for (sourceIndex <- 0 until 3) {
          logical.sources(sourceIndex).valid.poke(true.B)
          logical.sources(sourceIndex).operandClass.poke(OperandClass.P)
          logical.sources(sourceIndex).atag.poke((8 + sourceIndex).U)
        }
      }
      transaction.plan.demand.storeIds.poke(1.U)
      transaction.decoded.demand.storeIds.poke(1.U)
      dut.io.s1.ready.expect(false.B)
      dut.io.s1Rejected.valid.expect(true.B)
      dut.io.s1Rejected.bits.shapeExact.expect(false.B)
      val memoryOrder = dut.io.s1.bits.memoryOrder
      memoryOrder.valid.poke(true.B)
      memoryOrder.peId.poke(3.U)
      memoryOrder.stid.poke(1.U)
      memoryOrder.epoch.poke(6.U)
      memoryOrder.transactionId.poke(10.U)
      memoryOrder.uopMask.poke(1.U)
      memoryOrder.after.lsid.poke(1.U)
      memoryOrder.after.storeId.poke(1.U)
      memoryOrder.uops(0).valid.poke(true.B)
      memoryOrder.uops(0).memoryValid.poke(true.B)
      memoryOrder.uops(0).isStore.poke(true.B)
      memoryOrder.uops(0).requestCount.poke(1.U)
      memoryOrder.uops(0).after.lsid.poke(1.U)
      memoryOrder.uops(0).after.storeId.poke(1.U)
      memoryOrder.uops(1).before.lsid.poke(1.U)
      memoryOrder.uops(1).before.storeId.poke(1.U)
      memoryOrder.uops(1).firstLsid.poke(1.U)
      memoryOrder.uops(1).after.lsid.poke(1.U)
      memoryOrder.uops(1).after.storeId.poke(1.U)
      for (sourceIndex <- 0 until 3) {
        val renamed = pUop.sources(sourceIndex)
        renamed.decoded.valid.poke(true.B)
        renamed.decoded.operandClass.poke(OperandClass.P)
        renamed.decoded.atag.poke((8 + sourceIndex).U)
        renamed.pMapping.valid.poke(true.B)
        renamed.pMapping.ptag.poke((40 + sourceIndex).U)
        renamed.pMapping.ptagGeneration.poke(3.U)
        renamed.pMapping.ready.poke(true.B)
        renamed.pMapping.stid.poke(1.U)
        renamed.pMapping.epoch.poke(6.U)
      }

      advanceToS3(dut)
      query(dut, 2, 0, 1)
      dut.io.queryRow.sources(0).valid.expect(false.B)
      dut.io.queryRow.sources(1).valid.expect(true.B)
      dut.io.queryRow.sources(2).valid.expect(true.B)
      dut.io.queryRow.destinations(0).valid.expect(false.B)
      dut.io.queryRow.memoryOrder.memoryValid.expect(true.B)
      dut.io.queryRow.memoryOrder.firstLsid.expect(0.U)
      dut.io.queryRow.memoryOrder.firstTypeId.expect(0.U)
      query(dut, 3, 0, 2)
      dut.io.queryRow.sources(0).valid.expect(true.B)
      dut.io.queryRow.sources(1).valid.expect(false.B)
      dut.io.queryRow.sources(2).valid.expect(false.B)
      dut.io.queryRow.destinations(0).valid.expect(false.B)
      dut.io.queryRow.memoryOrder.memoryValid.expect(true.B)
      dut.io.queryRow.memoryOrder.firstLsid.expect(0.U)
      dut.io.queryRow.memoryOrder.firstTypeId.expect(0.U)
    }
  }

  test("rejects a split store whose child classes are swapped") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      dispatchWidth = 2,
      robGroupsPerStid = 8,
      iqBankCount = 2,
      iqEntriesPerBank = 4,
      iqWritePortsPerBank = 2,
      pMapQDepthPerStid = 4,
      tuMapQDepthPerStid = 4,
      tuRetireSourceDepthPerStid = 16)
    simulate(new OooIexIssue(p)) { dut =>
      clear(dut)
      pokeTransaction(dut, 1, 11, Vector(
        Allocation(0, 0, 3, 0, 0, 1),
        Allocation(0, 1, 2, 0, 1, 2)))
      val decoded = dut.io.s1.bits.o3.request.reservation.transaction.decoded.uops(0)
      val pDecoded = dut.io.s1.bits.pRename.uops(0).decoded
      Seq(decoded, pDecoded).foreach { logical =>
        logical.recipe.lateSplitKind.poke(OooLateSplitKind.StoreAddressData.U)
      }
      dut.io.s1.ready.expect(false.B)
      dut.io.s1Rejected.valid.expect(true.B)
      dut.io.s1Rejected.bits.shapeExact.expect(false.B)
    }
  }

  test("registers wakeup before it becomes visible to S3 eligibility") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      dispatchWidth = 2,
      robGroupsPerStid = 8,
      iqBankCount = 2,
      iqEntriesPerBank = 4,
      iqWritePortsPerBank = 2,
      pMapQDepthPerStid = 4,
      tuMapQDepthPerStid = 4,
      tuRetireSourceDepthPerStid = 16)
    simulate(new OooIexIssue(p)) { dut =>
      clear(dut)
      pokeTransaction(dut, 0, 2,
        Vector(Allocation(0, 0, 0, 0, 0, 0)),
        pSourceReady = Some(false))
      query(dut, 0, 0, 0)
      advanceToS3(dut)
      dut.io.queryPickable.expect(false.B)

      val wakeup = dut.io.wakeup(0)
      wakeup.bits.stid.poke(0.U)
      wakeup.bits.epoch.poke(6.U)
      wakeup.bits.operandClass.poke(OperandClass.P)
      wakeup.bits.ptag.poke(17.U)
      wakeup.bits.ptagGeneration.poke(3.U)
      wakeup.valid.poke(true.B)
      dut.io.queryPickable.expect(false.B)
      dut.clock.step()
      wakeup.valid.poke(false.B)
      dut.io.queryPickable.expect(true.B)

      // A later consumer must observe the generation-qualified ready table
      // even though it never overlapped the original one-cycle wakeup pulse.
      pokeTransaction(dut, 0, 3,
        Vector(Allocation(0, 0, 0, 0, 0, 1)),
        pSourceReady = Some(false), pSourceGeneration = 4)
      query(dut, 0, 0, 1)
      advanceToS3(dut)
      dut.io.queryPickable.expect(false.B)

      pokeTransaction(dut, 0, 4,
        Vector(Allocation(0, 0, 0, 0, 0, 2)),
        pSourceReady = Some(false), pSourceGeneration = 3)
      query(dut, 0, 0, 2)
      advanceToS3(dut)
      dut.io.queryPickable.expect(true.B)

      // Recycling the exact generation must clear the retained ready record
      // before that physical tag can be issued again.
      dut.io.ptagRecycle.bits.poke(
        0.U.asTypeOf(dut.io.ptagRecycle.bits))
      dut.io.ptagRecycle.bits.count.poke(1.U)
      dut.io.ptagRecycle.bits.tokens(0).valid.poke(true.B)
      dut.io.ptagRecycle.bits.tokens(0).ptag.poke(17.U)
      dut.io.ptagRecycle.bits.tokens(0).generation.poke(3.U)
      dut.io.ptagRecycle.valid.poke(true.B)
      dut.io.ptagRecycle.ready.expect(true.B)
      dut.clock.step()
      dut.io.ptagRecycle.valid.poke(false.B)

      pokeTransaction(dut, 0, 7,
        Vector(Allocation(0, 0, 0, 0, 0, 3)),
        pSourceReady = Some(false), pSourceGeneration = 3)
      query(dut, 0, 0, 3)
      advanceToS3(dut)
      dut.io.queryPickable.expect(false.B)

      // T/U readiness uses the same retained principle but exact local
      // sequence identity rather than PTag allocation generation.
      wakeup.bits.poke(0.U.asTypeOf(wakeup.bits))
      wakeup.bits.stid.poke(0.U)
      wakeup.bits.epoch.poke(6.U)
      wakeup.bits.operandClass.poke(OperandClass.T)
      wakeup.bits.localTag.poke(5.U)
      wakeup.bits.localSequence.valid.poke(true.B)
      wakeup.bits.localSequence.index.poke(1.U)
      wakeup.bits.localSequence.generation.poke(2.U)
      wakeup.valid.poke(true.B)
      dut.clock.step()
      wakeup.valid.poke(false.B)

      pokeTransaction(dut, 0, 5,
        Vector(Allocation(0, 0, 0, 1, 0, 0)))
      val tRequest = dut.io.s1.bits
      val tDecoded = tRequest.o3.request.reservation.transaction.decoded.uops(0)
      val tPUop = tRequest.pRename.uops(0)
      val tLocalSource = tRequest.tuRename.uops(0).sources(0)
      Seq(tDecoded.sources(0), tPUop.decoded.sources(0)).foreach { source =>
        source.valid.poke(true.B)
        source.operandClass.poke(OperandClass.T)
        source.relativeIndex.poke(0.U)
      }
      tLocalSource.valid.poke(true.B)
      tLocalSource.kind.poke(DestinationKind.T)
      tLocalSource.relativeIndex.poke(0.U)
      tLocalSource.physicalTag.poke(5.U)
      tLocalSource.stid.poke(0.U)
      tLocalSource.epoch.poke(6.U)
      tLocalSource.sequence.valid.poke(true.B)
      tLocalSource.sequence.index.poke(1.U)
      tLocalSource.sequence.generation.poke(2.U)
      query(dut, 0, 1, 0)
      advanceToS3(dut)
      dut.io.queryPickable.expect(true.B)

      wakeup.bits.poke(0.U.asTypeOf(wakeup.bits))
      wakeup.bits.stid.poke(0.U)
      wakeup.bits.epoch.poke(6.U)
      wakeup.bits.operandClass.poke(OperandClass.U)
      wakeup.bits.localTag.poke(6.U)
      wakeup.bits.localSequence.valid.poke(true.B)
      wakeup.bits.localSequence.index.poke(2.U)
      wakeup.bits.localSequence.generation.poke(3.U)
      wakeup.valid.poke(true.B)
      dut.clock.step()
      wakeup.valid.poke(false.B)

      pokeTransaction(dut, 0, 6,
        Vector(Allocation(0, 0, 0, 1, 0, 1)))
      val uRequest = dut.io.s1.bits
      val uDecoded = uRequest.o3.request.reservation.transaction.decoded.uops(0)
      val uPUop = uRequest.pRename.uops(0)
      val uLocalSource = uRequest.tuRename.uops(0).sources(0)
      Seq(uDecoded.sources(0), uPUop.decoded.sources(0)).foreach { source =>
        source.valid.poke(true.B)
        source.operandClass.poke(OperandClass.U)
        source.relativeIndex.poke(0.U)
      }
      uLocalSource.valid.poke(true.B)
      uLocalSource.kind.poke(DestinationKind.U)
      uLocalSource.relativeIndex.poke(0.U)
      uLocalSource.physicalTag.poke(6.U)
      uLocalSource.stid.poke(0.U)
      uLocalSource.epoch.poke(6.U)
      uLocalSource.sequence.valid.poke(true.B)
      uLocalSource.sequence.index.poke(2.U)
      uLocalSource.sequence.generation.poke(3.U)
      query(dut, 0, 1, 1)
      advanceToS3(dut)
      dut.io.queryPickable.expect(true.B)
    }
  }

  test("captures a producer wakeup on the same edge as consumer S2 bind") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      dispatchWidth = 2,
      robGroupsPerStid = 8,
      iqBankCount = 2,
      iqEntriesPerBank = 4,
      iqWritePortsPerBank = 2,
      pMapQDepthPerStid = 4,
      tuMapQDepthPerStid = 4,
      tuRetireSourceDepthPerStid = 16)
    simulate(new OooIexIssue(p)) { dut =>
      clear(dut)
      pokeTransaction(dut, 0, 7,
        Vector(Allocation(0, 0, 0, 0, 0, 3)),
        pSourceReady = Some(false))
      query(dut, 0, 0, 3)
      dut.io.s1.ready.expect(true.B)
      dut.clock.step() // retain S1
      dut.io.s1.valid.poke(false.B)
      dut.io.s2Bind.valid.expect(true.B)

      val wakeup = dut.io.wakeup(0)
      wakeup.bits.stid.poke(0.U)
      wakeup.bits.epoch.poke(6.U)
      wakeup.bits.operandClass.poke(OperandClass.P)
      wakeup.bits.ptag.poke(17.U)
      wakeup.bits.ptagGeneration.poke(3.U)
      wakeup.valid.poke(true.B)
      dut.clock.step() // bind S2 and capture the same-cycle wakeup
      wakeup.valid.poke(false.B)
      dut.io.queryState.expect(OooIexIssueSlotState.BoundS2)
      dut.io.queryPickable.expect(false.B)

      dut.clock.step() // registered S3 enable
      dut.io.queryState.expect(OooIexIssueSlotState.ResidentS3)
      dut.io.queryPickable.expect(true.B)
    }
  }

  test("keeps speculative load readiness IQ-local and generation-qualified") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      dispatchWidth = 2,
      robGroupsPerStid = 8,
      iqBankCount = 2,
      iqEntriesPerBank = 4,
      iqWritePortsPerBank = 2,
      pMapQDepthPerStid = 4,
      tuMapQDepthPerStid = 4,
      tuRetireSourceDepthPerStid = 16)
    simulate(new OooIexIssue(p)) { dut =>
      clear(dut)
      pokeTransaction(dut, 0, 20,
        Vector(Allocation(0, 0, 0, 0, 0, 0)),
        pSourceReady = Some(false))
      query(dut, 0, 0, 0)
      advanceToS3(dut)
      dut.io.queryPickable.expect(false.B)

      val wakeup = dut.io.wakeup(0)
      wakeup.bits.poke(0.U.asTypeOf(wakeup.bits))
      wakeup.bits.kind.poke(OooIexWakeupKind.SpeculativeLoad)
      wakeup.bits.stid.poke(0.U)
      wakeup.bits.epoch.poke(6.U)
      wakeup.bits.operandClass.poke(OperandClass.P)
      wakeup.bits.ptag.poke(17.U)
      wakeup.bits.ptagGeneration.poke(3.U)
      wakeup.bits.load.valid.poke(true.B)
      pokeMember(wakeup.bits.load.producer, 0, memberIndex = 5,
        residentGeneration = 9)
      wakeup.bits.load.generation.poke(7.U)
      wakeup.valid.poke(true.B)
      dut.io.queryPickable.expect(false.B)
      dut.clock.step()
      wakeup.valid.poke(false.B)

      dut.io.queryRow.sources(0).ready.expect(false.B)
      dut.io.queryRow.sources(0).specReady.expect(true.B)
      dut.io.queryRow.sources(0).load.valid.expect(true.B)
      dut.io.queryRow.sources(0).load.generation.expect(7.U)
      dut.io.queryRow.sources(0).load.producer.memberIndex.expect(5.U)
      dut.io.queryPickable.expect(true.B)

      val cancel = dut.io.loadCancel(0)
      cancel.bits.poke(0.U.asTypeOf(cancel.bits))
      cancel.bits.stid.poke(0.U)
      cancel.bits.epoch.poke(6.U)
      cancel.bits.load.valid.poke(true.B)
      pokeMember(cancel.bits.load.producer, 0, memberIndex = 5,
        residentGeneration = 9)
      cancel.bits.load.generation.poke(6.U)
      cancel.valid.poke(true.B)
      dut.clock.step()
      cancel.valid.poke(false.B)
      dut.io.queryRow.sources(0).specReady.expect(true.B)
      dut.io.queryRow.sources(0).load.generation.expect(7.U)

      cancel.bits.load.generation.poke(7.U)
      cancel.valid.poke(true.B)
      dut.io.queryPickable.expect(false.B)
      dut.clock.step()
      cancel.valid.poke(false.B)
      dut.io.queryRow.sources(0).ready.expect(false.B)
      dut.io.queryRow.sources(0).specReady.expect(false.B)
      dut.io.queryRow.sources(0).load.valid.expect(false.B)
      dut.io.queryPickable.expect(false.B)

      wakeup.bits.load.generation.poke(8.U)
      wakeup.valid.poke(true.B)
      dut.clock.step()
      wakeup.valid.poke(false.B)
      dut.io.queryRow.sources(0).specReady.expect(true.B)
      dut.io.queryRow.sources(0).load.generation.expect(8.U)
      dut.io.queryPickable.expect(true.B)

      // A consumer installed after the pulse must not inherit speculative
      // readiness from the non-speculative P ready table.
      pokeTransaction(dut, 0, 21,
        Vector(Allocation(0, 0, 0, 0, 0, 1)),
        pSourceReady = Some(false))
      query(dut, 0, 0, 1)
      advanceToS3(dut)
      dut.io.queryRow.sources(0).ready.expect(false.B)
      dut.io.queryRow.sources(0).specReady.expect(false.B)
      dut.io.queryPickable.expect(false.B)

      // A stable wakeup promotes both resident consumers and is the only
      // event allowed to populate the global ready table.
      wakeup.bits.poke(0.U.asTypeOf(wakeup.bits))
      wakeup.bits.kind.poke(OooIexWakeupKind.Committed)
      wakeup.bits.stid.poke(0.U)
      wakeup.bits.epoch.poke(6.U)
      wakeup.bits.operandClass.poke(OperandClass.P)
      wakeup.bits.ptag.poke(17.U)
      wakeup.bits.ptagGeneration.poke(3.U)
      wakeup.valid.poke(true.B)
      dut.clock.step()
      wakeup.valid.poke(false.B)
      dut.io.queryRow.sources(0).ready.expect(true.B)
      dut.io.queryRow.sources(0).specReady.expect(false.B)
      dut.io.queryPickable.expect(true.B)
      query(dut, 0, 0, 0)
      dut.io.queryRow.sources(0).ready.expect(true.B)
      dut.io.queryRow.sources(0).specReady.expect(false.B)
      dut.io.queryRow.sources(0).load.valid.expect(false.B)

      // The S2 bind edge has an explicit capture path because the ordinary
      // resident-row wakeup loop still sees the slot as free on that edge.
      pokeTransaction(dut, 0, 22,
        Vector(Allocation(0, 0, 0, 0, 0, 2)),
        pSourceReady = Some(false), pSourceGeneration = 4)
      query(dut, 0, 0, 2)
      dut.io.s1.ready.expect(true.B)
      dut.clock.step()
      dut.io.s1.valid.poke(false.B)
      dut.io.s2Bind.valid.expect(true.B)
      wakeup.bits.poke(0.U.asTypeOf(wakeup.bits))
      wakeup.bits.kind.poke(OooIexWakeupKind.SpeculativeLoad)
      wakeup.bits.stid.poke(0.U)
      wakeup.bits.epoch.poke(6.U)
      wakeup.bits.operandClass.poke(OperandClass.P)
      wakeup.bits.ptag.poke(17.U)
      wakeup.bits.ptagGeneration.poke(4.U)
      wakeup.bits.load.valid.poke(true.B)
      pokeMember(wakeup.bits.load.producer, 0, memberIndex = 6,
        residentGeneration = 10)
      wakeup.bits.load.generation.poke(8.U)
      wakeup.valid.poke(true.B)
      dut.clock.step()
      wakeup.valid.poke(false.B)
      dut.io.queryState.expect(OooIexIssueSlotState.BoundS2)
      dut.clock.step()
      dut.io.queryState.expect(OooIexIssueSlotState.ResidentS3)
      dut.io.queryRow.sources(0).ready.expect(false.B)
      dut.io.queryRow.sources(0).specReady.expect(true.B)
      dut.io.queryRow.sources(0).load.generation.expect(8.U)
      dut.io.queryPickable.expect(true.B)
    }
  }

  test("retains independent STID S1 rows and binds them fairly") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      dispatchWidth = 2,
      robGroupsPerStid = 8,
      iqBankCount = 2,
      iqEntriesPerBank = 4,
      iqWritePortsPerBank = 2,
      pMapQDepthPerStid = 4,
      tuMapQDepthPerStid = 4,
      tuRetireSourceDepthPerStid = 16)
    simulate(new OooIexIssue(p)) { dut =>
      clear(dut)
      pokeTransaction(dut, 0, 1,
        Vector(Allocation(0, 0, 0, 0, 0, 0)))
      dut.io.s1.ready.expect(true.B)
      dut.clock.step()

      // The first row has not reached S2 yet, but its pending S1 claim must
      // already exclude the same physical target for every other STID.
      pokeTransaction(dut, 2, 2,
        Vector(Allocation(0, 0, 0, 0, 0, 0)))
      dut.io.s1.ready.expect(false.B)
      dut.io.s1Rejected.valid.expect(true.B)

      pokeTransaction(dut, 2, 2,
        Vector(Allocation(0, 0, 0, 1, 0, 0)))
      dut.io.s1.ready.expect(true.B)
      dut.io.s2Bind.valid.expect(true.B)
      dut.io.s2Bind.bits.stid.expect(0.U)
      dut.clock.step()
      dut.io.s1.valid.poke(false.B)
      dut.io.s2Bind.valid.expect(true.B)
      dut.io.s2Bind.bits.stid.expect(2.U)
      dut.clock.step()
      dut.io.s1Occupied(0).expect(false.B)
      dut.io.s1Occupied(2).expect(false.B)
    }
  }

  test("releases resident state and dispatch ownership on one exact fire") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      dispatchWidth = 2,
      robGroupsPerStid = 8,
      iqBankCount = 2,
      iqEntriesPerBank = 4,
      iqWritePortsPerBank = 2,
      pMapQDepthPerStid = 4,
      tuMapQDepthPerStid = 4,
      tuRetireSourceDepthPerStid = 16)
    simulate(new OooIexIssue(p)) { dut =>
      clear(dut)
      val allocation = Allocation(0, 0, 0, 1, 1, 2, reservationEpoch = 3)
      pokeTransaction(dut, 3, 11, Vector(allocation))
      query(dut, 0, 1, 2)
      advanceToS3(dut)

      dut.io.pickClass.poke(OooUopClass.Alu)
      dut.io.pickBankEnable.poke("b10".U)
      dut.clock.step()
      dut.io.pick.valid.expect(true.B)
      dut.io.pick.bits.query.bank.expect(1.U)
      dut.io.pick.bits.query.entry.expect(2.U)

      val retry = dut.io.pickRetry.bits
      retry.poke(0.U.asTypeOf(retry))
      pokeMember(retry.member, 3, memberIndex = 0)
      retry.reservation.valid.poke(true.B)
      retry.reservation.uopClass.poke(OooUopClass.Alu)
      retry.reservation.bank.poke(1.U)
      retry.reservation.writePort.poke(1.U)
      retry.reservation.speculativeSlot.poke(2.U)
      retry.reservation.reservationEpoch.poke(3.U)

      // A bridge/P1 shape failure returns the exact token on the claim edge.
      // The claim and retry must collapse to a resident, pickable row.
      dut.io.pick.ready.poke(true.B)
      dut.io.pickRetry.valid.poke(true.B)
      dut.io.pickRetryRejected.valid.expect(false.B)
      dut.clock.step()
      dut.io.pick.ready.poke(false.B)
      dut.io.pickRetry.valid.poke(false.B)
      dut.io.inFlightEntries(0)(1).expect(0.U)
      dut.io.queryPickable.expect(true.B)

      dut.clock.step()
      dut.io.pick.valid.expect(true.B)
      dut.io.pick.ready.poke(true.B)
      dut.clock.step()
      dut.io.pick.ready.poke(false.B)
      dut.io.inFlightEntries(0)(1).expect(1.U)
      dut.io.queryPickable.expect(false.B)

      dut.io.pickRetry.valid.poke(true.B)
      dut.io.pickRetryRejected.valid.expect(false.B)
      dut.clock.step()
      dut.io.pickRetry.valid.poke(false.B)
      dut.io.inFlightEntries(0)(1).expect(0.U)
      dut.io.queryPickable.expect(true.B)

      dut.clock.step()
      dut.io.pick.valid.expect(true.B)
      dut.io.pick.ready.poke(true.B)
      dut.clock.step()
      dut.io.pick.ready.poke(false.B)
      dut.io.inFlightEntries(0)(1).expect(1.U)

      val release = dut.io.release.bits
      release.poke(0.U.asTypeOf(release))
      pokeMember(release.member, 3, memberIndex = 0)
      pokeMember(release.dispatch.member, 3, memberIndex = 0)
      release.dispatch.peId.poke(3.U)
      release.dispatch.stid.poke(3.U)
      release.dispatch.epoch.poke(6.U)
      release.dispatch.transactionId.poke(11.U)
      release.dispatch.reservation.valid.poke(true.B)
      release.dispatch.reservation.uopClass.poke(OooUopClass.Alu)
      release.dispatch.reservation.bank.poke(1.U)
      release.dispatch.reservation.writePort.poke(1.U)
      release.dispatch.reservation.speculativeSlot.poke(2.U)
      release.dispatch.reservation.reservationEpoch.poke(4.U)
      dut.io.release.valid.poke(true.B)
      dut.io.release.ready.expect(false.B)
      dut.io.releaseRejected.valid.expect(true.B)
      dut.clock.step()
      dut.io.queryState.expect(OooIexIssueSlotState.ResidentS3)

      release.dispatch.reservation.reservationEpoch.poke(3.U)
      dut.io.dispatchRelease.ready.poke(false.B)
      dut.io.releaseRejected.valid.expect(false.B)
      dut.io.release.ready.expect(false.B)
      dut.io.dispatchRelease.valid.expect(true.B)
      dut.clock.step()
      dut.io.queryState.expect(OooIexIssueSlotState.ResidentS3)

      dut.io.dispatchRelease.ready.poke(true.B)
      dut.io.release.ready.expect(true.B)
      dut.clock.step()
      dut.io.release.valid.poke(false.B)
      dut.io.queryState.expect(OooIexIssueSlotState.Free)
    }
  }

  test("policy blocks a resident row and invalidates a newly held pick token") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      dispatchWidth = 2,
      robGroupsPerStid = 8,
      iqBankCount = 2,
      iqEntriesPerBank = 4,
      iqWritePortsPerBank = 2,
      pMapQDepthPerStid = 4,
      tuMapQDepthPerStid = 4,
      tuRetireSourceDepthPerStid = 16)
    simulate(new OooIexIssue(p)) { dut =>
      clear(dut)
      val allocation = Allocation(0, 0, 0, 0, 0, 1)
      pokeTransaction(dut, 1, 17, Vector(allocation))
      query(dut, 0, 0, 1)
      advanceToS3(dut)

      dut.io.pickClass.poke(OooUopClass.Alu)
      dut.io.pickBankEnable.poke(1.U)
      dut.clock.step()
      dut.io.pick.valid.expect(true.B)

      dut.io.issuePolicy.classPressure(0).poke("b0010".U)
      val classPressure = 1 << OooIexIssueBlockReason.ClassPressure
      dut.io.pick.valid.expect(false.B)
      dut.io.pickPolicyBlocked.valid.expect(true.B)
      dut.io.pickPolicyBlocked.bits.reasonMask.expect(classPressure.U)
      dut.io.pickPolicyBlocked.bits.token.candidate.stid.expect(1.U)
      dut.io.queryPolicyReason.expect(classPressure.U)
      dut.io.queryPickable.expect(false.B)
      dut.io.policyBlockedCount(0).expect(1.U)
      dut.clock.step()

      // The policy event consumes only the stale held picker token. The IQ
      // row never became in-flight and is selected again after unblock.
      dut.io.pickPolicyBlocked.valid.expect(false.B)
      dut.io.inFlightEntries(0)(0).expect(0.U)
      dut.io.issuePolicy.classPressure(0).poke(0.U)
      dut.io.queryPickable.expect(true.B)
      dut.clock.step()
      dut.io.pick.valid.expect(true.B)
      dut.io.pick.ready.poke(true.B)
      dut.clock.step()
      dut.io.pick.ready.poke(false.B)
      dut.io.inFlightEntries(0)(0).expect(1.U)
    }
  }

  test("multi-release frees independent rows and blocks duplicate targets") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      dispatchWidth = 2,
      robGroupsPerStid = 8,
      iqBankCount = 2,
      iqEntriesPerBank = 4,
      iqWritePortsPerBank = 2,
      iexIssueDomainCount = 2,
      iexReleaseWidth = 2,
      pMapQDepthPerStid = 4,
      tuMapQDepthPerStid = 4,
      tuRetireSourceDepthPerStid = 16)
    simulate(new OooIexIssue(p)) { dut =>
      clear(dut)
      val allocations = Vector(
        Allocation(0, 0, 0, 0, 0, 1, reservationEpoch = 3),
        Allocation(1, 0, 1, 0, 1, 2, reservationEpoch = 4))
      pokeTransaction(dut, 1, 21, allocations)
      advanceToS3(dut)

      dut.io.pickClasses(0).poke(OooUopClass.Alu)
      dut.io.pickClasses(1).poke(OooUopClass.Bru)
      dut.io.pickBankEnables(0).poke(1.U)
      dut.io.pickBankEnables(1).poke(1.U)
      dut.clock.step()
      dut.io.picks.foreach(_.valid.expect(true.B))
      dut.io.picks.foreach(_.ready.poke(true.B))
      dut.clock.step()
      dut.io.picks.foreach(_.ready.poke(false.B))

      def pokeRelease(
          lane: Int,
          allocation: Allocation,
          memberIndex: Int): Unit = {
        val release = dut.io.releases(lane).bits
        release.poke(0.U.asTypeOf(release))
        pokeMember(release.member, 1, memberIndex)
        pokeMember(release.dispatch.member, 1, memberIndex)
        release.dispatch.peId.poke(3.U)
        release.dispatch.stid.poke(1.U)
        release.dispatch.epoch.poke(6.U)
        release.dispatch.transactionId.poke(21.U)
        release.dispatch.reservation.valid.poke(true.B)
        pokeClass(release.dispatch.reservation.uopClass,
          allocation.uopClass)
        release.dispatch.reservation.bank.poke(allocation.bank.U)
        release.dispatch.reservation.writePort.poke(allocation.port.U)
        release.dispatch.reservation.speculativeSlot.poke(allocation.entry.U)
        release.dispatch.reservation.reservationEpoch.poke(
          allocation.reservationEpoch.U)
        dut.io.releases(lane).valid.poke(true.B)
      }

      pokeRelease(0, allocations(0), memberIndex = 0)
      pokeRelease(1, allocations(1), memberIndex = 2)
      dut.io.dispatchReleases.foreach(_.ready.poke(true.B))
      dut.io.releases.foreach(_.ready.expect(true.B))
      dut.clock.step()
      dut.io.releases.foreach(_.valid.poke(false.B))
      dut.io.residentEntries(0)(0).expect(0.U)
      dut.io.residentEntries(1)(0).expect(0.U)

      val duplicate = Allocation(0, 0, 0, 0, 0, 3,
        reservationEpoch = 5)
      pokeTransaction(dut, 1, 22, Vector(duplicate))
      advanceToS3(dut)
      dut.io.pickClasses(0).poke(OooUopClass.Alu)
      dut.io.pickBankEnables(0).poke(1.U)
      dut.clock.step()
      dut.io.picks(0).valid.expect(true.B)
      dut.io.picks(0).ready.poke(true.B)
      dut.clock.step()
      dut.io.picks(0).ready.poke(false.B)

      pokeRelease(0, duplicate, memberIndex = 0)
      pokeRelease(1, duplicate, memberIndex = 0)
      dut.io.releases.foreach(_.bits.dispatch.transactionId.poke(22.U))
      dut.io.releases.foreach(_.ready.expect(false.B))
      dut.io.releaseRejecteds.foreach(_.valid.expect(true.B))
      dut.clock.step()
      dut.io.releases.foreach(_.valid.poke(false.B))
      dut.io.residentEntries(0)(0).expect(1.U)
      dut.io.inFlightEntries(0)(0).expect(1.U)
    }
  }

  test("rejects duplicate physical targets and binds a split bundle atomically") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      dispatchWidth = 4,
      robGroupsPerStid = 8,
      iqBankCount = 2,
      iqEntriesPerBank = 4,
      iqWritePortsPerBank = 2,
      pMapQDepthPerStid = 4,
      tuMapQDepthPerStid = 4,
      tuRetireSourceDepthPerStid = 16)
    simulate(new OooIexIssue(p)) { dut =>
      clear(dut)
      val first = Allocation(0, 0, 2, 0, 0, 1)
      pokeTransaction(dut, 1, 4,
        Vector(first, first.copy(childIndex = 1, port = 1)))
      dut.io.s1.ready.expect(false.B)
      dut.io.s1Rejected.valid.expect(true.B)
      dut.clock.step()
      dut.io.s1.valid.poke(false.B)
      dut.io.residentEntries(2)(0).expect(0.U)

      pokeTransaction(dut, 1, 5,
        Vector(first, first.copy(childIndex = 1, port = 1, entry = 2)))
      dut.io.s1.ready.expect(true.B)
      dut.clock.step()
      dut.io.s1.valid.poke(false.B)
      dut.io.s2Bind.valid.expect(true.B)
      dut.io.s2Bind.bits.allocationMask.expect("b0011".U)
      dut.clock.step(2)
      dut.io.residentEntries(2)(0).expect(2.U)
      query(dut, 2, 0, 1)
      dut.io.queryRow.member.memberIndex.expect(0.U)
      query(dut, 2, 0, 2)
      dut.io.queryRow.member.memberIndex.expect(1.U)
    }
  }

  test("keeps younger same-STID stores behind the cross-bank logical frontier") {
    val p = OooParams(
      stidCount = 2,
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      renameWidth = 2,
      dispatchWidth = 2,
      retireGroupWidth = 2,
      robGroupsPerStid = 8,
      robBankCount = 2,
      robRecoveryScanGroupsPerCycle = 2,
      robNonFlushScanGroupsPerCycle = 2,
      pcBufferEntries = 8,
      pcBankCount = 2,
      pcRecoveryScanGroupsPerCycle = 2,
      pcWritePorts = 2,
      iqBankCount = 2,
      iqEntriesPerBank = 4,
      iqWritePortsPerBank = 2,
      iqFreeSelectLeafEntries = 2,
      iexIssueDomainCount = 2,
      iexReleaseWidth = 2,
      pMapQDepthPerStid = 4,
      tuMapQDepthPerStid = 4,
      tuRetireSourceDepthPerStid = 16,
      lsidWidth = 40)
    simulate(new OooIexIssue(p)) { dut =>
      clear(dut)
      val older = Vector(
        Allocation(0, 0, 2, 0, 0, 0, reservationEpoch = 3),
        Allocation(0, 1, 3, 0, 1, 0, reservationEpoch = 4))
      val younger = Vector(
        Allocation(0, 0, 2, 0, 0, 1, reservationEpoch = 5),
        Allocation(0, 1, 3, 0, 1, 1, reservationEpoch = 6))

      pokeStoreTransaction(dut, stid = 0, transactionId = 30,
        ridSlot = 1, memberBase = 0, firstLsid = 20,
        firstStoreId = 10, allocations = older)
      advanceToS3(dut)
      pokeStoreTransaction(dut, stid = 0, transactionId = 31,
        ridSlot = 2, memberBase = 0, firstLsid = 23,
        firstStoreId = 11, allocations = younger)
      advanceToS3(dut)

      query(dut, uopClass = 2, bank = 0, entry = 0)
      dut.io.queryPickable.expect(true.B)
      dut.io.queryRow.storeOrder.firstStoreId.expect(10.U)
      query(dut, uopClass = 3, bank = 0, entry = 0)
      dut.io.queryPickable.expect(true.B)
      query(dut, uopClass = 2, bank = 0, entry = 1)
      dut.io.queryPickable.expect(false.B)
      query(dut, uopClass = 3, bank = 0, entry = 1)
      dut.io.queryPickable.expect(false.B)
      dut.io.storeFrontierBlocked(0).expect(2.U)
      dut.io.storeFrontierBlocked(1).expect(0.U)

      dut.io.pickClasses(0).poke(OooUopClass.Agu)
      dut.io.pickClasses(1).poke(OooUopClass.Std)
      dut.io.pickBankEnables.foreach(_.poke(1.U))
      dut.clock.step()
      dut.io.picks(0).valid.expect(true.B)
      dut.io.picks(0).bits.query.entry.expect(0.U)
      dut.io.picks(1).valid.expect(true.B)
      dut.io.picks(1).bits.query.entry.expect(0.U)
      dut.io.picks.foreach(_.ready.poke(true.B))
      dut.clock.step()
      dut.io.picks.foreach(_.ready.poke(false.B))

      def pokeRelease(
          lane: Int,
          allocation: Allocation,
          childIndex: Int): Unit = {
        val release = dut.io.releases(lane).bits
        release.poke(0.U.asTypeOf(release))
        pokeMember(release.member, stid = 0, memberIndex = childIndex,
          ridSlot = 1)
        pokeMember(release.dispatch.member, stid = 0,
          memberIndex = childIndex, ridSlot = 1)
        release.dispatch.peId.poke(3.U)
        release.dispatch.stid.poke(0.U)
        release.dispatch.epoch.poke(6.U)
        release.dispatch.transactionId.poke(30.U)
        release.dispatch.reservation.valid.poke(true.B)
        pokeClass(release.dispatch.reservation.uopClass,
          allocation.uopClass)
        release.dispatch.reservation.bank.poke(allocation.bank.U)
        release.dispatch.reservation.writePort.poke(allocation.port.U)
        release.dispatch.reservation.speculativeSlot.poke(allocation.entry.U)
        release.dispatch.reservation.reservationEpoch.poke(
          allocation.reservationEpoch.U)
        dut.io.releases(lane).valid.poke(true.B)
        dut.io.dispatchReleases(lane).ready.poke(true.B)
      }

      // Releasing only STA is insufficient: the resident STD child still
      // represents the older logical store across both physical IQ classes.
      pokeRelease(0, older(0), childIndex = 0)
      dut.io.releases(0).ready.expect(true.B)
      dut.clock.step()
      dut.io.releases(0).valid.poke(false.B)
      query(dut, uopClass = 2, bank = 0, entry = 1)
      dut.io.queryPickable.expect(false.B)
      dut.io.storeFrontierBlocked(0).expect(2.U)

      pokeRelease(0, older(1), childIndex = 1)
      dut.io.releases(0).ready.expect(true.B)
      dut.clock.step()
      dut.io.releases(0).valid.poke(false.B)
      query(dut, uopClass = 2, bank = 0, entry = 1)
      dut.io.queryPickable.expect(true.B)
      query(dut, uopClass = 3, bank = 0, entry = 1)
      dut.io.queryPickable.expect(true.B)
      dut.io.storeFrontierBlocked(0).expect(0.U)
    }
  }

  test("elaborates the IEX residency boundary at instruction widths 2 4 and 6") {
    Seq(2, 4, 6).foreach { width =>
      val p = OooParams(
        instructionDecodeWidth = width,
        decodedUopWidth = width,
        dispatchWidth = width * 2,
        robGroupsPerStid = 8,
        iqBankCount = 2,
        iqEntriesPerBank = 4,
        iqWritePortsPerBank = 2,
        pTagStagingDepthPerBank = 8,
        pMapQDepthPerStid = 16,
        tuMapQDepthPerStid = 16,
        tuRetireSourceDepthPerStid = 64)
      val sv = ChiselStage.emitSystemVerilog(new OooIexIssue(p))
      assert(sv.contains("module OooIexIssue"))
    }
  }
}
