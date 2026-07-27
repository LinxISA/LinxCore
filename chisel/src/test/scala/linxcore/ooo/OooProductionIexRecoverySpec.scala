package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

/** Focused O7 recovery proof with the smallest physical IQ that can retain an
  * older member and a killed suffix simultaneously.  The production owner is
  * instantiated directly; this is not a behavioral mock.
  */
class OooProductionIexRecoverySpec extends AnyFunSuite with ChiselSim {
  private val p = OooParams(
    stidCount = 2,
    instructionDecodeWidth = 2,
    decodedUopWidth = 2,
    dispatchWidth = 2,
    robGroupsPerStid = 4,
    iqBankCount = 2,
    iqEntriesPerBank = 2,
    iqWritePortsPerBank = 2,
    pMapQDepthPerStid = 4,
    tuMapQDepthPerStid = 4,
    tuRetireSourceDepthPerStid = 8)

  private def clear(dut: OooProductionIexIssue): Unit = {
    dut.io.s1.valid.poke(false.B)
    dut.io.s1.bits.poke(0.U.asTypeOf(dut.io.s1.bits))
    dut.io.wakeup.foreach { wakeup =>
      wakeup.valid.poke(false.B)
      wakeup.bits.poke(0.U.asTypeOf(wakeup.bits))
    }
    dut.io.release.valid.poke(false.B)
    dut.io.release.bits.poke(0.U.asTypeOf(dut.io.release.bits))
    dut.io.dispatchRelease.ready.poke(true.B)
    dut.io.query.poke(0.U.asTypeOf(dut.io.query))
    dut.io.recoveryPrepare.valid.poke(false.B)
    dut.io.recoveryPrepare.bits.poke(
      0.U.asTypeOf(dut.io.recoveryPrepare.bits))
    dut.io.recoveryFire.poke(false.B)
  }

  private def pokeMember(
      member: RobMemberKey,
      stid: Int,
      ridSlot: Int,
      memberIndex: Int): Unit = {
    member.poke(0.U.asTypeOf(member))
    member.group.valid.poke(true.B)
    member.group.peId.poke(3.U)
    member.group.stid.poke(stid.U)
    member.group.ridSlot.poke(ridSlot.U)
    member.group.ridGeneration.poke(0.U)
    member.bid.valid.poke(true.B)
    member.bid.value.poke((stid + 1).U)
    member.brobGeneration.poke(2.U)
    member.memberIndex.poke(memberIndex.U)
    member.residentGeneration.poke(3.U)
  }

  private def pokeTransaction(
      dut: OooProductionIexIssue,
      stid: Int,
      ridSlot: Int,
      transactionId: Int,
      entries: Vector[Int]): Unit = {
    val request = dut.io.s1.bits
    request.poke(0.U.asTypeOf(request))
    val transaction = request.o3.request.reservation.transaction
    val uopMask = (1 << entries.length) - 1
    transaction.plan.peId.poke(3.U)
    transaction.plan.stid.poke(stid.U)
    transaction.plan.epoch.poke(6.U)
    transaction.plan.transactionId.poke(transactionId.U)
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
    request.dispatch.allocationMask.poke(uopMask.U)

    entries.indices.foreach { lane =>
      val decoded = transaction.decoded.uops(lane)
      val pUop = request.pRename.uops(lane)
      val tuUop = request.tuRename.uops(lane)
      decoded.valid.poke(true.B)
      decoded.opcode.poke((40 + lane).U)
      decoded.plannedChildCount.poke(1.U)
      pUop.valid.poke(true.B)
      pUop.decoded.valid.poke(true.B)
      pUop.decoded.opcode.poke((40 + lane).U)
      pUop.decoded.plannedChildCount.poke(1.U)
      tuUop.valid.poke(true.B)
      pokeMember(pUop.member, stid, ridSlot, lane)
      pokeMember(tuUop.member, stid, ridSlot, lane)

      val allocation = request.dispatch.allocations(lane)
      allocation.valid.poke(true.B)
      allocation.uopIndex.poke(lane.U)
      allocation.childIndex.poke(0.U)
      allocation.reservation.valid.poke(true.B)
      allocation.reservation.uopClass.poke(OooUopClass.Alu)
      allocation.reservation.bank.poke(0.U)
      allocation.reservation.writePort.poke(lane.U)
      allocation.reservation.speculativeSlot.poke(entries(lane).U)
      allocation.reservation.reservationEpoch.poke(1.U)
    }
    dut.io.s1.valid.poke(true.B)
  }

  private def pokeRecovery(
      dut: OooProductionIexIssue,
      stid: Int,
      ridSlot: Int,
      oldMembers: Int,
      survivingMembers: Int): Unit = {
    val plan = dut.io.recoveryPrepare.bits
    plan.poke(0.U.asTypeOf(plan))
    plan.valid.poke(true.B)
    plan.oldHead.valid.poke(true.B)
    plan.oldHead.peId.poke(3.U)
    plan.oldHead.stid.poke(stid.U)
    plan.oldHead.ridSlot.poke(ridSlot.U)
    plan.oldHead.ridGeneration.poke(0.U)
    plan.oldOccupied.poke(1.U)
    plan.newOccupied.poke((if (survivingMembers == 0) 0 else 1).U)
    plan.pivotOffset.poke(0.U)
    pokeMember(plan.pivot, stid, ridSlot, 0)
    plan.pivotPhysicalMemberCount.poke(oldMembers.U)
    plan.survivingPivotValid.poke((survivingMembers != 0).B)
    plan.survivingPivotPhysicalMemberCount.poke(survivingMembers.U)
    dut.io.recoveryPrepare.valid.poke(true.B)
  }

  private def query(dut: OooProductionIexIssue, entry: Int): Unit = {
    dut.io.query.uopClass.poke(OooUopClass.Alu)
    dut.io.query.bank.poke(0.U)
    dut.io.query.entry.poke(entry.U)
  }

  test("prunes exact BoundS2 and ResidentS3 suffixes without stopping peers") {
    simulate(new OooProductionIexIssue(p)) { dut =>
      clear(dut)

      pokeTransaction(dut, stid = 0, ridSlot = 0, transactionId = 1,
        entries = Vector(0, 1))
      dut.io.s1.ready.expect(true.B)
      dut.clock.step()
      dut.io.s1.valid.poke(false.B)
      dut.io.s2Bind.valid.expect(true.B)
      dut.clock.step()
      query(dut, 0)
      dut.io.queryState.expect(OooIexIssueSlotState.BoundS2)
      query(dut, 1)
      dut.io.queryState.expect(OooIexIssueSlotState.BoundS2)

      pokeRecovery(dut, stid = 0, ridSlot = 0, oldMembers = 2,
        survivingMembers = 1)
      dut.io.recoveryPrepareReady.expect(true.B)
      dut.io.recoveryPrepared.boundKilled.expect(1.U)
      dut.io.recoveryPrepared.residentKilled.expect(0.U)
      dut.io.recoveryFire.poke(true.B)
      dut.clock.step()
      dut.io.recoveryFire.poke(false.B)
      dut.io.recoveryPrepare.valid.poke(false.B)
      dut.io.s3Enable.valid.expect(true.B)
      dut.io.s3Enable.bits.bind.allocationMask.expect(1.U)
      dut.clock.step()
      query(dut, 0)
      dut.io.queryState.expect(OooIexIssueSlotState.ResidentS3)
      dut.io.queryPickable.expect(true.B)
      query(dut, 1)
      dut.io.queryState.expect(OooIexIssueSlotState.Free)

      pokeTransaction(dut, stid = 1, ridSlot = 1, transactionId = 2,
        entries = Vector(1))
      dut.io.s1.ready.expect(true.B)
      dut.clock.step()
      dut.io.s1.valid.poke(false.B)
      dut.io.s2Bind.valid.expect(true.B)
      dut.clock.step()
      dut.io.s3Enable.valid.expect(true.B)
      dut.clock.step()
      query(dut, 1)
      dut.io.queryState.expect(OooIexIssueSlotState.ResidentS3)

      pokeRecovery(dut, stid = 1, ridSlot = 1, oldMembers = 1,
        survivingMembers = 0)
      dut.io.recoveryPrepareReady.expect(true.B)
      dut.io.recoveryPrepared.boundKilled.expect(0.U)
      dut.io.recoveryPrepared.residentKilled.expect(1.U)
      query(dut, 0)
      dut.io.queryPickable.expect(true.B)
      query(dut, 1)
      dut.io.queryPickable.expect(false.B)
      dut.io.recoveryFire.poke(true.B)
      dut.clock.step()
      dut.io.recoveryFire.poke(false.B)
      dut.io.recoveryPrepare.valid.poke(false.B)
      query(dut, 0)
      dut.io.queryState.expect(OooIexIssueSlotState.ResidentS3)
      query(dut, 1)
      dut.io.queryState.expect(OooIexIssueSlotState.Free)
    }
  }
}
