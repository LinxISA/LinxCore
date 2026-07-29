package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

class OooIexIssueP1FabricSpec extends AnyFunSuite with ChiselSim {
  private val p = OooParams(
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
    pMapQDepthPerStid = 4,
    tuMapQDepthPerStid = 4,
    tuRetireSourceDepthPerStid = 16)

  private def clear(dut: OooIexIssueP1Fabric): Unit = {
    dut.io.s1.valid.poke(false.B)
    dut.io.s1.bits.poke(0.U.asTypeOf(dut.io.s1.bits))
    dut.io.wakeup.foreach(_.poke(0.U.asTypeOf(dut.io.wakeup.head)))
    dut.io.release.valid.poke(false.B)
    dut.io.release.bits.poke(0.U.asTypeOf(dut.io.release.bits))
    dut.io.dispatchRelease.ready.poke(true.B)
    dut.io.ptagRecycle.valid.poke(false.B)
    dut.io.ptagRecycle.bits.poke(0.U.asTypeOf(dut.io.ptagRecycle.bits))
    dut.io.recoveryPrepare.valid.poke(false.B)
    dut.io.recoveryPrepare.bits.poke(
      0.U.asTypeOf(dut.io.recoveryPrepare.bits))
    dut.io.recoveryFire.poke(false.B)
    dut.io.pickClasses(0).poke(OooUopClass.Alu)
    dut.io.pickClasses(1).poke(OooUopClass.Bru)
    dut.io.pickBankEnables(0).poke(1.U)
    dut.io.pickBankEnables(1).poke(1.U)
    for (domain <- 0 until p.iexIssueDomainCount) {
      dut.io.readDecisionValid(domain).poke(false.B)
      dut.io.readGrant(domain).poke(false.B)
      dut.io.sourceDataValid(domain).poke(0.U)
      dut.io.sourceData(domain).foreach(_.poke(0.U))
      dut.io.pcDataValid(domain).poke(false.B)
      dut.io.pcData(domain).poke(0.U)
      dut.io.i2(domain).ready.poke(false.B)
    }
    dut.io.bypass.foreach(
      _.poke(0.U.asTypeOf(dut.io.bypass.head)))
  }

  private def pokeMember(target: RobMemberKey, memberIndex: Int): Unit = {
    target.group.valid.poke(true.B)
    target.group.peId.poke(3.U)
    target.group.stid.poke(1.U)
    target.group.ridSlot.poke(2.U)
    target.group.ridGeneration.poke(1.U)
    target.bid.valid.poke(true.B)
    target.bid.value.poke(4.U)
    target.brobGeneration.poke(2.U)
    target.memberIndex.poke(memberIndex.U)
    target.residentGeneration.poke(5.U)
  }

  private def pokeUop(
      dut: OooIexIssueP1Fabric,
      index: Int,
      opcode: Int,
      dispatchClass: Int,
      uopClass: OooUopClass.Type,
      pcRead: Boolean): Unit = {
    val request = dut.io.s1.bits
    val decoded = request.o3.request.reservation.transaction.decoded.uops(index)
    val renamed = request.pRename.uops(index)
    val local = request.tuRename.uops(index)
    decoded.valid.poke(true.B)
    renamed.valid.poke(true.B)
    renamed.decoded.valid.poke(true.B)
    local.valid.poke(true.B)
    Seq(decoded, renamed.decoded).foreach { uop =>
      uop.opcode.poke(opcode.U)
      uop.recipe.valid.poke(true.B)
      uop.recipe.opcode.poke(opcode.U)
      uop.recipe.disposition.poke(OooOpcodeDisposition.Dispatch.U)
      uop.recipe.dispatchClass.poke(dispatchClass.U)
      uop.recipe.dispatchWrites.poke(1.U)
      uop.recipe.dispatchDemand(dispatchClass - 1).poke(1.U)
      uop.recipe.pcReadRequired.poke(pcRead.B)
      uop.recipe.pcReadClass.poke(dispatchClass.U)
      uop.plannedChildCount.poke(1.U)
      uop.identity.key.primaryParent.valid.poke(true.B)
      uop.identity.key.primaryParent.peId.poke(3.U)
      uop.identity.key.primaryParent.stid.poke(1.U)
      uop.identity.key.primaryParent.instructionId.poke((101 + index).U)
      uop.identity.key.primaryParent.epoch.poke(7.U)
      uop.identity.parentCount.poke(1.U)
      val parent = uop.identity.parents(0)
      parent.key.valid.poke(true.B)
      parent.key.peId.poke(3.U)
      parent.key.stid.poke(1.U)
      parent.key.instructionId.poke((101 + index).U)
      parent.key.epoch.poke(7.U)
    }
    pokeMember(renamed.member, index)
    pokeMember(local.member, index)

    if (pcRead) {
      val pcToken = request.o3.parentPcTokens(index)(0)
      pcToken.valid.poke(true.B)
      pcToken.index.poke((5 + index).U)
      pcToken.byteOffset.poke((6 + index).U)
      pcToken.allocationEpoch.poke(3.U)
    }

    val allocation = request.dispatch.allocations(index)
    allocation.valid.poke(true.B)
    allocation.uopIndex.poke(index.U)
    allocation.childIndex.poke(0.U)
    allocation.reservation.valid.poke(true.B)
    allocation.reservation.uopClass.poke(uopClass)
    allocation.reservation.bank.poke(0.U)
    allocation.reservation.writePort.poke(index.U)
    allocation.reservation.speculativeSlot.poke((index + 1).U)
    allocation.reservation.reservationEpoch.poke((9 + index).U)
  }

  private def pokeTwoDomainTransaction(dut: OooIexIssueP1Fabric): Unit = {
    val request = dut.io.s1.bits
    request.poke(0.U.asTypeOf(request))
    val transaction = request.o3.request.reservation.transaction
    transaction.plan.peId.poke(3.U)
    transaction.plan.stid.poke(1.U)
    transaction.plan.epoch.poke(7.U)
    transaction.plan.transactionId.poke(31.U)
    transaction.decoded.peId.poke(3.U)
    transaction.decoded.stid.poke(1.U)
    transaction.decoded.epoch.poke(7.U)
    transaction.decoded.uopMask.poke(3.U)

    request.pRename.valid.poke(true.B)
    request.pRename.peId.poke(3.U)
    request.pRename.stid.poke(1.U)
    request.pRename.epoch.poke(7.U)
    request.pRename.transactionId.poke(31.U)
    request.pRename.uopMask.poke(3.U)
    request.tuRename.valid.poke(true.B)
    request.tuRename.peId.poke(3.U)
    request.tuRename.stid.poke(1.U)
    request.tuRename.epoch.poke(7.U)
    request.tuRename.transactionId.poke(31.U)
    request.tuRename.uopMask.poke(3.U)
    request.dispatch.valid.poke(true.B)
    request.dispatch.peId.poke(3.U)
    request.dispatch.stid.poke(1.U)
    request.dispatch.epoch.poke(7.U)
    request.dispatch.transactionId.poke(31.U)
    request.dispatch.allocationMask.poke(3.U)

    pokeUop(dut, 0, opcode = 1, OooDispatchClass.Alu,
      OooUopClass.Alu, pcRead = false)
    pokeUop(dut, 1, opcode = 73, OooDispatchClass.Bru,
      OooUopClass.Bru, pcRead = true)
    dut.io.s1.valid.poke(true.B)
  }

  private def pokeTwoAluBankTransaction(dut: OooIexIssueP1Fabric): Unit = {
    pokeTwoDomainTransaction(dut)
    val request = dut.io.s1.bits
    val decoded = request.o3.request.reservation.transaction.decoded.uops(1)
    val renamed = request.pRename.uops(1).decoded
    Seq(decoded, renamed).foreach { uop =>
      uop.recipe.dispatchClass.poke(OooDispatchClass.Alu.U)
      uop.recipe.dispatchDemand.poke(0.U.asTypeOf(uop.recipe.dispatchDemand))
      uop.recipe.dispatchDemand(OooDispatchClass.Alu - 1).poke(1.U)
      uop.recipe.pcReadRequired.poke(false.B)
      uop.recipe.pcReadClass.poke(OooDispatchClass.Alu.U)
    }
    request.dispatch.allocations(1).reservation.uopClass.poke(
      OooUopClass.Alu)
    request.dispatch.allocations(1).reservation.bank.poke(1.U)
  }

  private def pokeRelease(
      dut: OooIexIssueP1Fabric,
      memberIndex: Int,
      uopClass: OooUopClass.Type,
      bank: Int = 0): Unit = {
    val release = dut.io.release.bits
    release.poke(0.U.asTypeOf(release))
    pokeMember(release.member, memberIndex)
    release.dispatch.peId.poke(3.U)
    release.dispatch.stid.poke(1.U)
    release.dispatch.epoch.poke(7.U)
    release.dispatch.transactionId.poke(31.U)
    pokeMember(release.dispatch.member, memberIndex)
    release.dispatch.reservation.valid.poke(true.B)
    release.dispatch.reservation.uopClass.poke(uopClass)
    release.dispatch.reservation.bank.poke(bank.U)
    release.dispatch.reservation.writePort.poke(memberIndex.U)
    release.dispatch.reservation.speculativeSlot.poke((memberIndex + 1).U)
    release.dispatch.reservation.reservationEpoch.poke((9 + memberIndex).U)
    dut.io.release.valid.poke(true.B)
  }

  test("parallel domains claim independently and one denial does not cancel its peer") {
    simulate(new OooIexIssueP1Fabric(p)) { dut =>
      clear(dut)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.empty.expect(true.B)

      pokeTwoDomainTransaction(dut)
      dut.io.s1.ready.expect(true.B)
      dut.clock.step()
      dut.io.s1.valid.poke(false.B)
      dut.clock.step(4)

      dut.io.readAttempts(0).valid.expect(true.B)
      dut.io.readAttempts(0).bits.pcRequired.expect(false.B)
      dut.io.readAttempts(1).valid.expect(true.B)
      dut.io.readAttempts(1).bits.pcRequired.expect(true.B)
      dut.io.inFlightEntries(OooDispatchClass.Alu - 1)(0).expect(1.U)
      dut.io.inFlightEntries(OooDispatchClass.Bru - 1)(0).expect(1.U)

      dut.io.readDecisionValid(0).poke(true.B)
      dut.io.readGrant(0).poke(true.B)
      dut.io.readDecisionValid(1).poke(true.B)
      dut.io.readGrant(1).poke(false.B)
      dut.io.retryFeedback(1).valid.expect(true.B)
      dut.clock.step()
      dut.io.readDecisionValid(0).poke(false.B)
      dut.io.readGrant(0).poke(false.B)
      dut.io.readDecisionValid(1).poke(false.B)

      dut.io.i2(0).valid.expect(true.B)
      dut.io.i2(0).bits.row.reservation.uopClass.expect(OooUopClass.Alu)
      dut.io.i1Occupied(1).expect(false.B)
      dut.io.inFlightEntries(OooDispatchClass.Alu - 1)(0).expect(1.U)
      dut.io.inFlightEntries(OooDispatchClass.Bru - 1)(0).expect(0.U)

      dut.io.i2(0).ready.poke(true.B)
      dut.clock.step()
      dut.io.i2(0).ready.poke(false.B)
      dut.clock.step(2)
      dut.io.readAttempts(1).valid.expect(true.B)

      dut.io.readDecisionValid(1).poke(true.B)
      dut.io.readGrant(1).poke(true.B)
      dut.io.pcDataValid(1).poke(true.B)
      dut.io.pcData(1).poke("h80000127".U)
      dut.clock.step()
      dut.io.readDecisionValid(1).poke(false.B)
      dut.io.readGrant(1).poke(false.B)
      dut.io.i2(1).valid.expect(true.B)
      dut.io.i2(1).bits.pc.expect("h80000127".U)
      dut.io.i2(1).ready.poke(true.B)
      dut.clock.step()
      dut.io.i2(1).ready.poke(false.B)

      pokeRelease(dut, 0, OooUopClass.Alu)
      dut.io.release.ready.expect(true.B)
      dut.clock.step()
      pokeRelease(dut, 1, OooUopClass.Bru)
      dut.io.release.ready.expect(true.B)
      dut.clock.step()
      dut.io.release.valid.poke(false.B)

      dut.io.lanesEmpty.expect(true.B)
      dut.io.empty.expect(true.B)
      dut.io.residentEntries(OooDispatchClass.Alu - 1)(0).expect(0.U)
      dut.io.residentEntries(OooDispatchClass.Bru - 1)(0).expect(0.U)

      // Reuse the quiescent fabric with two ALU domains split by bank. This
      // covers the other legal topology relation without another elaboration.
      dut.io.pickClasses(0).poke(OooUopClass.Alu)
      dut.io.pickClasses(1).poke(OooUopClass.Alu)
      dut.io.pickBankEnables(0).poke(1.U)
      dut.io.pickBankEnables(1).poke(2.U)
      pokeTwoAluBankTransaction(dut)
      dut.io.s1.ready.expect(true.B)
      dut.clock.step()
      dut.io.s1.valid.poke(false.B)
      dut.clock.step(4)
      dut.io.readAttempts(0).valid.expect(true.B)
      dut.io.readAttempts(0).bits.reservation.bank.expect(0.U)
      dut.io.readAttempts(1).valid.expect(true.B)
      dut.io.readAttempts(1).bits.reservation.bank.expect(1.U)
      for (domain <- 0 until p.iexIssueDomainCount) {
        dut.io.readDecisionValid(domain).poke(true.B)
        dut.io.readGrant(domain).poke(true.B)
      }
      dut.clock.step()
      for (domain <- 0 until p.iexIssueDomainCount) {
        dut.io.readDecisionValid(domain).poke(false.B)
        dut.io.readGrant(domain).poke(false.B)
        dut.io.i2(domain).valid.expect(true.B)
        dut.io.i2(domain).ready.poke(true.B)
      }
      dut.clock.step()
      dut.io.i2.foreach(_.ready.poke(false.B))
      pokeRelease(dut, 0, OooUopClass.Alu, bank = 0)
      dut.clock.step()
      pokeRelease(dut, 1, OooUopClass.Alu, bank = 1)
      dut.clock.step()
      dut.io.release.valid.poke(false.B)
      dut.io.empty.expect(true.B)
    }
  }

  test("rejects overlapping class and bank projections") {
    intercept[Exception] {
      simulate(new OooIexIssueP1Fabric(p)) { dut =>
        clear(dut)
        dut.io.pickClasses(0).poke(OooUopClass.Alu)
        dut.io.pickClasses(1).poke(OooUopClass.Alu)
        dut.io.pickBankEnables(0).poke(1.U)
        dut.io.pickBankEnables(1).poke(1.U)
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)
        dut.clock.step()
      }
    }
  }
}
