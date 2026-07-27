package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.common.DestinationKind
import org.scalatest.funsuite.AnyFunSuite

class OooO3FastResolveIntegrationSpec extends AnyFunSuite with ChiselSim {
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
    dut.io.ptagReturn.valid.poke(false.B)
    dut.io.ptagReturn.bits.poke(0.U.asTypeOf(dut.io.ptagReturn.bits))
    dut.io.dispatchRelease.valid.poke(false.B)
    dut.io.dispatchRelease.bits.poke(
      0.U.asTypeOf(dut.io.dispatchRelease.bits))
    dut.io.recoveryRequest.valid.poke(false.B)
    dut.io.recoveryRequest.bits.poke(
      0.U.asTypeOf(dut.io.recoveryRequest.bits))
    dut.io.queryStid.poke(0.U)
    dut.io.queryAtag.poke(10.U)
    dut.io.pcReadTokens.foreach(_.poke(
      0.U.asTypeOf(dut.io.pcReadTokens.head)))
  }

  private def pokeSetret(dut: OooO3RenameCoordinator): Unit = {
    val transaction = dut.io.reserve.bits
    transaction.poke(0.U.asTypeOf(transaction))
    transaction.plan.peId.poke(3.U)
    transaction.plan.stid.poke(0.U)
    transaction.plan.epoch.poke(5.U)
    transaction.plan.transactionId.poke(0.U)
    transaction.plan.uopMask.poke(1.U)
    transaction.plan.groupCount.poke(1.U)
    transaction.plan.virtualTailEpoch.poke(0.U)
    transaction.plan.firstVirtualGroup.valid.poke(true.B)
    transaction.plan.firstVirtualGroup.peId.poke(3.U)
    transaction.plan.firstVirtualGroup.stid.poke(0.U)
    transaction.plan.firstVirtualGroup.ridSlot.poke(0.U)
    transaction.plan.firstVirtualGroup.ridGeneration.poke(0.U)
    transaction.plan.demand.pDestinations.poke(1.U)
    transaction.plan.demand.mapQRows.poke(1.U)
    transaction.decoded.peId.poke(3.U)
    transaction.decoded.stid.poke(0.U)
    transaction.decoded.epoch.poke(5.U)
    transaction.decoded.uopMask.poke(1.U)
    transaction.groupMask.poke(1.U)
    transaction.uopGroupIndex(0).poke(0.U)
    transaction.uopMemberBase(0).poke(0.U)

    val group = transaction.groups(0)
    group.valid.poke(true.B)
    group.key.valid.poke(true.B)
    group.key.peId.poke(3.U)
    group.key.stid.poke(0.U)
    group.key.ridSlot.poke(0.U)
    group.key.ridGeneration.poke(0.U)
    group.logicalUopMask.poke(1.U)
    group.physicalMemberCount.poke(1.U)
    group.pMapQRows.poke(1.U)
    group.architecturalParentCount.poke(1.U)
    group.boundaryStart.poke(true.B)
    group.boundaryStop.poke(true.B)
    group.releasePcBase.poke(true.B)

    val uop = transaction.decoded.uops(0)
    uop.valid.poke(true.B)
    uop.opcode.poke(343.U)
    uop.recipe.valid.poke(true.B)
    uop.recipe.opcode.poke(343.U)
    uop.recipe.disposition.poke(OooOpcodeDisposition.FastResolve.U)
    uop.recipe.recipeKind.poke(OooOpcodeRecipeKind.Setret.U)
    uop.recipe.uopCountMin.poke(1.U)
    uop.recipe.uopCountMax.poke(1.U)
    uop.recipe.fastResolveClass.poke(
      OooFastResolveClass.ImmediateProducer.U)
    uop.recipe.pDestinationCount.poke(1.U)
    uop.recipe.sideEffectOwner.poke(OooSideEffectOwner.Iex.U)
    uop.plannedChildCount.poke(1.U)
    uop.immediateValid.poke(true.B)
    uop.immediate.poke(0x28.U)
    uop.identity.key.primaryParent.valid.poke(true.B)
    uop.identity.key.primaryParent.peId.poke(3.U)
    uop.identity.key.primaryParent.stid.poke(0.U)
    uop.identity.key.primaryParent.instructionId.poke(190.U)
    uop.identity.key.primaryParent.epoch.poke(5.U)
    uop.identity.key.uopOrdinal.poke(0.U)
    uop.identity.key.uopCount.poke(1.U)
    uop.identity.parentCount.poke(1.U)
    uop.identity.parents(0).key.valid.poke(true.B)
    uop.identity.parents(0).key.peId.poke(3.U)
    uop.identity.parents(0).key.stid.poke(0.U)
    uop.identity.parents(0).key.instructionId.poke(190.U)
    uop.identity.parents(0).key.epoch.poke(5.U)
    uop.identity.parents(0).pc.poke(0x400.U)
    uop.identity.parents(0).lengthBytes.poke(4.U)
    uop.destinations(0).valid.poke(true.B)
    uop.destinations(0).kind.poke(DestinationKind.Gpr)
    uop.destinations(0).atag.poke(10.U)
    dut.io.reserve.valid.poke(true.B)
  }

  private def waitForCommit(
      dut: OooO3RenameCoordinator,
      limit: Int = 32): Unit = {
    var cycles = 0
    while (!dut.io.commit.valid.peek().litToBoolean && cycles < limit) {
      dut.clock.step()
      cycles += 1
    }
    assert(cycles < limit, "timed out waiting for fast-resolved commit")
  }

  test("publishes SETRET without IQ residency and completes the exact ROB member") {
    val p = OooParams(
      stidCount = 2,
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      renameWidth = 2,
      dispatchWidth = 2,
      retireGroupWidth = 2,
      robGroupsPerStid = 8,
      brobEntriesPerStid = 8,
      pMapQDepthPerStid = 4,
      pTagStagingDepthPerBank = 2,
      pTagReturnWidth = 4,
      tuMapQDepthPerStid = 4,
      tuRetireSourceDepthPerStid = 16,
      tPhysRegs = 8,
      uPhysRegs = 8,
      iqBankCount = 2,
      iqEntriesPerBank = 4,
      iqWritePortsPerBank = 2)
    simulate(new OooO3RenameCoordinator(p)) { dut =>
      clear(dut)
      dut.clock.step()
      pokeSetret(dut)
      dut.io.reserve.ready.expect(true.B)
      dut.clock.step()
      dut.io.reserve.valid.poke(false.B)

      var prepareCycles = 0
      while (!dut.io.preparedValid.peek().litToBoolean && prepareCycles < 8) {
        dut.clock.step()
        prepareCycles += 1
      }
      assert(prepareCycles < 8,
        s"SETRET prepare missing: p=${dut.io.prepared.valid.peek().litValue} " +
          s"tu=${dut.io.tuPrepared.valid.peek().litValue} " +
          s"dispatch=${dut.io.dispatchPrepared.valid.peek().litValue} " +
          s"ptagProv=${dut.io.ptagProvisionalCount.peek().litValue}")

      dut.io.fastWriteback.ready.poke(false.B)
      dut.io.iexS1.ready.poke(true.B)
      dut.io.fastS1Rejected.valid.expect(false.B)
      dut.io.iexS1.valid.expect(true.B)
      dut.io.publishFire.expect(true.B)
      dut.clock.step()
      dut.io.iexS1.ready.poke(false.B)

      dut.io.fastPendingByStid(0).expect(1.U)
      dut.io.fastWriteback.valid.expect(true.B)
      dut.io.fastWriteback.bits.data.expect(0x428.U)
      dut.io.fastTerminalFire.expect(false.B)
      for (clazz <- 0 until p.iqClassCount; bank <- 0 until p.iqBankCount) {
        dut.io.dispatchPublishedEntries(clazz)(bank).expect(0.U)
      }
      dut.clock.step(2)
      dut.io.robOccupiedGroups(0).expect(1.U)
      dut.io.fastPendingByStid(0).expect(1.U)

      // O7 will eventually cancel this row in the common global transaction.
      // Until then rename-local recovery must not pass a retained fast-only
      // row and let its old-path writeback/completion escape afterward.
      dut.io.recoveryRequest.valid.poke(true.B)
      dut.io.recoveryRequest.bits.poke(
        0.U.asTypeOf(dut.io.recoveryRequest.bits))
      dut.io.recoveryRequest.bits.key.member.group.stid.poke(0.U)
      dut.io.recoveryRequest.ready.expect(false.B)
      dut.io.recoveryBusy.expect(false.B)
      dut.clock.step()
      dut.io.fastPendingByStid(0).expect(1.U)
      dut.io.fastWriteback.valid.expect(true.B)
      dut.io.fastTerminalFire.expect(false.B)
      dut.io.recoveryRequest.valid.poke(false.B)

      dut.io.fastWriteback.ready.poke(true.B)
      dut.io.fastTerminalFire.expect(true.B)
      dut.io.fastWakeup.valid.expect(true.B)
      dut.io.fastTrace.valid.expect(true.B)
      dut.clock.step()
      dut.io.fastPendingByStid(0).expect(0.U)

      waitForCommit(dut)
      dut.io.commit.bits.release.firstGroup.stid.expect(0.U)
      dut.io.commit.ready.poke(true.B)
      dut.clock.step()
      dut.io.commit.ready.poke(false.B)
      dut.io.robOccupiedGroups(0).expect(0.U)
      dut.io.mapQUsed(0).expect(0.U)
    }
  }
}
