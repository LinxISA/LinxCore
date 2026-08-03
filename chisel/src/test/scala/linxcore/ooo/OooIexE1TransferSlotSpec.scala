package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.common.OperandClass
import linxcore.top.interface.RecoveryPhase
import org.scalatest.funsuite.AnyFunSuite

class OooIexE1TransferSlotSpec extends AnyFunSuite with ChiselSim {
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
    iqFreeSelectLeafEntries = 2,
    tuRetireSourceDepthPerStid = 16)

  private def clear(dut: OooIexE1TransferSlot): Unit = {
    dut.io.i2.valid.poke(false.B)
    dut.io.i2.bits.poke(0.U.asTypeOf(dut.io.i2.bits))
    dut.io.issueRelease.ready.poke(false.B)
    dut.io.e1.ready.poke(false.B)
    dut.io.recoveryApply.valid.poke(false.B)
    dut.io.recoveryApply.bits.poke(
      0.U.asTypeOf(dut.io.recoveryApply.bits))
    dut.io.loadCancel.foreach(
      _.poke(0.U.asTypeOf(dut.io.loadCancel.head)))
  }

  private def pokeMember(
      target: RobMemberKey,
      ridSlot: Int,
      memberIndex: Int = 0): Unit = {
    target.poke(0.U.asTypeOf(target))
    target.group.valid.poke(true.B)
    target.group.peId.poke(3.U)
    target.group.stid.poke(1.U)
    target.group.ridSlot.poke(ridSlot.U)
    target.group.ridGeneration.poke(1.U)
    target.bid.valid.poke(true.B)
    target.bid.value.poke(5.U)
    target.brobGeneration.poke(2.U)
    target.memberIndex.poke(memberIndex.U)
    target.residentGeneration.poke(4.U)
  }

  private def pokeLoadToken(
      target: OooIexLoadGeneration,
      generation: Int): Unit = {
    target.poke(0.U.asTypeOf(target))
    target.valid.poke(true.B)
    pokeMember(target.producer, ridSlot = 1, memberIndex = 1)
    target.generation.poke(generation.U)
  }

  private def pokeI2(
      dut: OooIexE1TransferSlot,
      ridSlot: Int,
      data: BigInt,
      speculativeLoad: Boolean = false,
      capability: BigInt = OooIexDomainCapability.mask(
        OooIexDomainCapability.SimpleAlu)): Unit = {
    val i2 = dut.io.i2.bits
    i2.poke(0.U.asTypeOf(i2))
    val row = i2.row.schedule
    row.valid.poke(true.B)
    row.peId.poke(3.U)
    row.stid.poke(1.U)
    row.epoch.poke(7.U)
    row.transactionId.poke((100 + ridSlot).U)
    pokeMember(row.member, ridSlot)
    row.reservation.valid.poke(true.B)
    row.reservation.uopClass.poke(OooUopClass.Alu)
    row.reservation.bank.poke(0.U)
    row.reservation.writePort.poke(0.U)
    row.reservation.speculativeSlot.poke(1.U)
    row.reservation.reservationEpoch.poke(9.U)
    i2.row.payload.recipe.dispatchCapabilities(
      OooDispatchClass.Alu - 1).poke(capability.U)
    row.inFlight.poke(true.B)
    row.sources(0).valid.poke(true.B)
    row.sources(0).ready.poke((!speculativeLoad).B)
    row.sources(0).specReady.poke(speculativeLoad.B)
    row.sources(0).operandClass.poke(OperandClass.P)
    row.sources(0).ptag.poke(17.U)
    row.sources(0).ptagGeneration.poke(3.U)
    if (speculativeLoad) {
      pokeLoadToken(row.sources(0).load, generation = 7)
    }
    i2.sourceMask.poke(1.U)
    i2.sourceData(0).poke(data.U)
    i2.bypassMask.poke(speculativeLoad.B.asUInt)
    if (speculativeLoad) {
      pokeLoadToken(i2.bypass(0).load, generation = 7)
    }
  }

  private def accept(dut: OooIexE1TransferSlot): Unit = {
    dut.io.i2.valid.poke(true.B)
    dut.io.issueRelease.ready.poke(true.B)
    dut.io.i2.ready.expect(true.B)
    dut.io.issueRelease.valid.expect(true.B)
    dut.clock.step()
    dut.io.i2.valid.poke(false.B)
    dut.io.issueRelease.ready.poke(false.B)
  }

  private def pokeRecovery(
      dut: OooIexE1TransferSlot,
      firstKilledSlot: Int): Unit = {
    val plan = dut.io.recoveryApply.bits
    plan.poke(0.U.asTypeOf(plan))
    plan.phase.poke(RecoveryPhase.Apply)
    plan.trigger.peId.poke(3.U)
    plan.trigger.stid.poke(1.U)
    if (firstKilledSlot < 4) {
      plan.firstKilledValid.poke(true.B)
      plan.firstKilled.peId.poke(3.U)
      plan.firstKilled.stid.poke(1.U)
      plan.firstKilled.ridSlot.poke(firstKilledSlot.U)
      plan.firstKilled.ridGeneration.poke(1.U)
      plan.lastKilled.peId.poke(3.U)
      plan.lastKilled.stid.poke(1.U)
      plan.lastKilled.ridSlot.poke(3.U)
      plan.lastKilled.ridGeneration.poke(1.U)
      plan.killedGroupCount.poke((4 - firstKilledSlot).U)
      plan.killedMemberCount.poke((4 - firstKilledSlot).U)
    }
    dut.io.recoveryApply.valid.poke(true.B)
  }

  test("transfers exact I2 ownership atomically and retains or kills E1") {
    simulate(new OooIexE1TransferSlot(p)) { dut =>
      clear(dut)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      // Release backpressure leaves ownership in I2 and allocates no E1 slot.
      pokeI2(dut, ridSlot = 2, data = BigInt("1122334455667788", 16))
      dut.io.i2.valid.poke(true.B)
      dut.io.issueRelease.valid.expect(true.B)
      dut.io.issueRelease.bits.member.group.ridSlot.expect(2.U)
      dut.io.i2.ready.expect(false.B)
      dut.io.occupied.expect(false.B)
      dut.clock.step(2)
      dut.io.issueRelease.bits.member.group.ridSlot.expect(2.U)

      // The one release fire is the one I2 accept fire.
      dut.io.issueRelease.ready.poke(true.B)
      dut.io.i2.ready.expect(true.B)
      dut.clock.step()
      dut.io.i2.valid.poke(false.B)
      dut.io.issueRelease.ready.poke(false.B)
      dut.io.occupied.expect(true.B)
      dut.io.e1.valid.expect(true.B)
      dut.io.e1.bits.ownerClass.expect(OooUopClass.Alu)
      dut.io.e1.bits.ownerLane.expect(0.U)
      dut.io.e1.bits.slotGeneration.expect(0.U)
      dut.io.e1.bits.i2.sourceData(0).expect("h1122334455667788".U)

      // E1 output is irrevocable while its consumer is blocked.
      dut.clock.step(2)
      dut.io.e1.bits.slotGeneration.expect(0.U)
      dut.io.e1.bits.i2.row.member.group.ridSlot.expect(2.U)

      // Drain the old owner and accept a new one on the same edge.
      pokeI2(dut, ridSlot = 3, data = BigInt("8877665544332211", 16))
      dut.io.i2.valid.poke(true.B)
      dut.io.issueRelease.ready.poke(true.B)
      dut.io.e1.ready.poke(true.B)
      dut.io.e1.bits.i2.row.member.group.ridSlot.expect(2.U)
      dut.io.i2.ready.expect(true.B)
      dut.clock.step()
      dut.io.i2.valid.poke(false.B)
      dut.io.issueRelease.ready.poke(false.B)
      dut.io.e1.ready.poke(false.B)
      dut.io.occupied.expect(true.B)
      dut.io.e1.bits.slotGeneration.expect(1.U)
      dut.io.e1.bits.i2.row.member.group.ridSlot.expect(3.U)
      dut.io.e1.bits.i2.sourceData(0).expect("h8877665544332211".U)

      // Exact ROB recovery, not an unrelated surviving prefix, owns the kill.
      pokeRecovery(dut, firstKilledSlot = 4)
      dut.io.e1.valid.expect(true.B)
      dut.io.killed.valid.expect(false.B)
      pokeRecovery(dut, firstKilledSlot = 2)
      dut.io.e1.valid.expect(false.B)
      dut.io.killed.valid.expect(true.B)
      dut.io.killed.bits.i2.row.member.group.ridSlot.expect(3.U)
      dut.clock.step()
      dut.io.recoveryApply.valid.poke(false.B)
      dut.io.occupied.expect(false.B)

      // A generation-mismatched load cancel cannot poison the new owner.
      pokeI2(dut, ridSlot = 2, data = BigInt("1020304050607080", 16),
        speculativeLoad = true)
      accept(dut)
      val cancel = dut.io.loadCancel(0)
      cancel.bits.poke(0.U.asTypeOf(cancel.bits))
      cancel.bits.stid.poke(1.U)
      cancel.bits.epoch.poke(7.U)
      pokeLoadToken(cancel.bits.load, generation = 6)
      cancel.valid.poke(true.B)
      dut.io.e1.valid.expect(true.B)
      dut.io.killed.valid.expect(false.B)
      cancel.bits.load.generation.poke(7.U)
      dut.io.e1.valid.expect(false.B)
      dut.io.killed.valid.expect(true.B)
      dut.io.killed.bits.slotGeneration.expect(2.U)
      dut.clock.step()
      cancel.valid.poke(false.B)
      dut.io.occupied.expect(false.B)
      dut.io.e1.valid.expect(false.B)

      // A class-mismatched transaction remains with I2 and is fail-stop
      // visible; no release can accidentally deallocate its IQ row.
      pokeI2(dut, ridSlot = 2, data = 1)
      dut.io.i2.bits.row.reservation.uopClass.poke(OooUopClass.Bru)
      dut.io.i2.valid.poke(true.B)
      dut.io.issueRelease.ready.poke(true.B)
      dut.io.i2.ready.expect(false.B)
      dut.io.issueRelease.valid.expect(false.B)
      dut.io.rejected.valid.expect(true.B)
      dut.io.rejected.bits.shapeExact.expect(false.B)
      dut.clock.step()
      dut.io.occupied.expect(false.B)
    }
  }

  test("rejects an I2 row whose recipe exceeds the retained domain capability") {
    import OooIexDomainCapability._
    simulate(new OooIexE1TransferSlot(p,
      acceptedCapabilities = mask(SimpleAlu),
      coreParams = OooRecoveryMembership.coreParams(p))) { dut =>
      clear(dut)
      pokeI2(dut, ridSlot = 1, data = 0,
        capability = mask(MultiCycleAlu))
      dut.io.i2.valid.poke(true.B)
      dut.io.issueRelease.ready.poke(true.B)
      dut.io.i2.ready.expect(false.B)
      dut.io.issueRelease.valid.expect(false.B)
      dut.io.rejected.valid.expect(true.B)
      dut.io.rejected.bits.shapeExact.expect(false.B)
    }
  }
}
