package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

class OooS1GroupedRobFaultSpec extends AnyFunSuite with ChiselSim {
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

  private def clear(dut: OooS1GroupedRob): Unit = {
    dut.io.publish.valid.poke(false.B)
    dut.io.publish.bits.poke(0.U.asTypeOf(dut.io.publish.bits))
    dut.io.completion.valid.poke(false.B)
    dut.io.completion.bits.poke(0.U.asTypeOf(dut.io.completion.bits))
    dut.io.nonFlushEvidence.valid.poke(false.B)
    dut.io.nonFlushEvidence.bits.poke(
      0.U.asTypeOf(dut.io.nonFlushEvidence.bits))
    dut.io.interruptPending.foreach(_.poke(false.B))
    dut.io.recoveryPrepare.valid.poke(false.B)
    dut.io.recoveryPrepare.bits.poke(
      0.U.asTypeOf(dut.io.recoveryPrepare.bits))
    dut.io.recoveryFire.poke(false.B)
    dut.io.commit.ready.poke(false.B)
  }

  private def pokeOneMemberPublication(
      dut: OooS1GroupedRob,
      memberCount: Int = 1): Unit = {
    val request = dut.io.publish.bits
    request.poke(0.U.asTypeOf(request))
    val transaction = request.reservation.transaction
    transaction.plan.peId.poke(3.U)
    transaction.plan.stid.poke(1.U)
    transaction.plan.epoch.poke(7.U)
    transaction.plan.transactionId.poke(70.U)
    transaction.plan.groupCount.poke(1.U)
    transaction.plan.uopMask.poke(((1 << memberCount) - 1).U)
    transaction.plan.firstVirtualGroup.valid.poke(true.B)
    transaction.plan.firstVirtualGroup.peId.poke(3.U)
    transaction.plan.firstVirtualGroup.stid.poke(1.U)
    transaction.decoded.peId.poke(3.U)
    transaction.decoded.stid.poke(1.U)
    transaction.decoded.epoch.poke(7.U)
    transaction.decoded.uopMask.poke(((1 << memberCount) - 1).U)
    transaction.groupMask.poke(1.U)
    request.reservation.tailAfter.valid.poke(true.B)
    request.reservation.tailAfter.peId.poke(3.U)
    request.reservation.tailAfter.stid.poke(1.U)
    request.reservation.tailAfter.ridSlot.poke(1.U)

    val group = transaction.groups(0)
    group.valid.poke(true.B)
    group.key.valid.poke(true.B)
    group.key.peId.poke(3.U)
    group.key.stid.poke(1.U)
    group.logicalUopMask.poke(((1 << memberCount) - 1).U)
    group.physicalMemberCount.poke(memberCount.U)
    group.architecturalParentCount.poke(memberCount.U)

    for (uopIndex <- 0 until memberCount) {
      transaction.uopGroupIndex(uopIndex).poke(0.U)
      transaction.uopMemberBase(uopIndex).poke(uopIndex.U)
      val uop = transaction.decoded.uops(uopIndex)
      uop.valid.poke(true.B)
      uop.recipe.valid.poke(true.B)
      uop.recipe.disposition.poke(OooOpcodeDisposition.Dispatch.U)
      uop.recipe.dispatchClass.poke(OooDispatchClass.Alu.U)
      uop.recipe.sideEffectOwner.poke(OooSideEffectOwner.Iex.U)
      uop.plannedChildCount.poke(1.U)
      uop.identity.parentCount.poke(1.U)
      uop.identity.parents(0).key.valid.poke(true.B)
      uop.identity.parents(0).key.peId.poke(3.U)
      uop.identity.parents(0).key.stid.poke(1.U)
      uop.identity.parents(0).key.instructionId.poke((9 + uopIndex).U)
      uop.identity.parents(0).key.epoch.poke(7.U)
      uop.identity.parents(0).traceOwner.poke(true.B)
    }

    val binding = request.bindings(0)
    binding.valid.poke(true.B)
    binding.brob.valid.poke(true.B)
    binding.brob.bid.valid.poke(true.B)
    binding.brob.bid.value.poke(5.U)
    binding.brob.generation.poke(2.U)
    binding.residentGeneration.poke(4.U)
    dut.io.publish.valid.poke(true.B)
  }

  private def pokeFaultCompletion(
      dut: OooS1GroupedRob,
      memberIndex: Int = 0): Unit = {
    val completion = dut.io.completion.bits
    completion.poke(0.U.asTypeOf(completion))
    completion.key.group.valid.poke(true.B)
    completion.key.group.peId.poke(3.U)
    completion.key.group.stid.poke(1.U)
    completion.key.bid.valid.poke(true.B)
    completion.key.bid.value.poke(5.U)
    completion.key.brobGeneration.poke(2.U)
    completion.key.memberIndex.poke(memberIndex.U)
    completion.key.residentGeneration.poke(4.U)
    completion.faultValid.poke(true.B)
    completion.faultCause.poke(13.U)
    dut.io.completion.valid.poke(true.B)
  }

  test("retains a precise runtime fault on the exact completed member") {
    simulate(new OooS1GroupedRob(p)) { dut =>
      clear(dut)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      pokeOneMemberPublication(dut)
      dut.io.publish.ready.expect(true.B)
      dut.clock.step()
      dut.io.publish.valid.poke(false.B)

      pokeFaultCompletion(dut)
      dut.io.completion.ready.expect(true.B)
      dut.io.completionRejected.valid.expect(false.B)
      dut.clock.step()
      dut.io.completion.valid.poke(false.B)

      var cycles = 0
      while (!dut.io.commit.valid.peek().litToBoolean && cycles < 32) {
        dut.clock.step()
        cycles += 1
      }
      assert(dut.io.commit.valid.peek().litToBoolean,
        "faulted grouped-ROB row did not become committable")
      dut.io.commit.bits.groups(0).completedMembers.expect(1.U)
      dut.io.commit.bits.groups(0).faultedMembers.expect(1.U)
      dut.io.commit.bits.groups(0).memberFaultCauses(0).expect(13.U)
      dut.io.commit.bits.groups(0).preciseTrap.expect(true.B)
    }
  }

  test("prunes killed runtime fault state from a surviving pivot") {
    simulate(new OooS1GroupedRob(p)) { dut =>
      clear(dut)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      pokeOneMemberPublication(dut, memberCount = 2)
      dut.io.publish.ready.expect(true.B)
      dut.clock.step()
      dut.io.publish.valid.poke(false.B)
      pokeFaultCompletion(dut, memberIndex = 1)
      dut.io.completion.ready.expect(true.B)
      dut.clock.step()
      dut.io.completion.valid.poke(false.B)

      val recovery = dut.io.recoveryPrepare.bits
      recovery.poke(0.U.asTypeOf(recovery))
      recovery.rename.key.member.group.valid.poke(true.B)
      recovery.rename.key.member.group.peId.poke(3.U)
      recovery.rename.key.member.group.stid.poke(1.U)
      recovery.rename.key.member.bid.valid.poke(true.B)
      recovery.rename.key.member.bid.value.poke(5.U)
      recovery.rename.key.member.brobGeneration.poke(2.U)
      recovery.rename.key.member.residentGeneration.poke(4.U)
      recovery.rename.key.transactionId.poke(70.U)
      recovery.rename.key.epoch.poke(7.U)
      recovery.rename.killTrigger.poke(false.B)
      recovery.triggerMemberCount.poke(1.U)
      dut.io.recoveryPrepare.valid.poke(true.B)

      var cycles = 0
      while (!dut.io.recoveryPrepareReady.peek().litToBoolean &&
          !dut.io.recoveryRejected.valid.peek().litToBoolean && cycles < 64) {
        dut.clock.step()
        cycles += 1
      }
      assert(!dut.io.recoveryRejected.valid.peek().litToBoolean,
        s"fault-pruning recovery rejected: occupied=${dut.io.recoveryRejected.bits.occupied.peek().litValue} exact=${dut.io.recoveryRejected.bits.exactMatchCount.peek().litValue} shape=${dut.io.recoveryRejected.bits.triggerShapeMatch.peek().litToBoolean}")
      assert(dut.io.recoveryPrepareReady.peek().litToBoolean,
        "fault-pruning recovery did not prepare")
      dut.io.recoveryPrepared.survivingPivotValid.expect(true.B)
      dut.io.recoveryPrepared.survivingPivot.physicalMemberCount.expect(1.U)
      dut.io.recoveryPrepared.survivingPivot.faultedMembers.expect(0.U)
      dut.io.recoveryPrepared.survivingPivot.memberFaultCauses(1).expect(0.U)
      dut.io.recoveryPrepared.survivingPivot.preciseTrap.expect(false.B)
    }
  }
}
