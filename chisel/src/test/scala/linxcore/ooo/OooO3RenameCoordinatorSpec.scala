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
    dut.io.publishPermit.poke(false.B)
    dut.io.completion.valid.poke(false.B)
    dut.io.completion.bits.poke(0.U.asTypeOf(dut.io.completion.bits))
    dut.io.commit.ready.poke(false.B)
    dut.io.ptagReturn.valid.poke(false.B)
    dut.io.ptagReturn.bits.poke(0.U.asTypeOf(dut.io.ptagReturn.bits))
    dut.io.queryStid.poke(0.U)
    dut.io.queryAtag.poke(0.U)
    dut.io.pcReadTokens.foreach(_.poke(0.U.asTypeOf(dut.io.pcReadTokens.head)))
  }

  private def pokeOneDestination(
      dut: OooO3RenameCoordinator,
      tailEpoch: Int = 0): Unit = {
    dut.io.reserve.bits.poke(0.U.asTypeOf(dut.io.reserve.bits))
    val transaction = dut.io.reserve.bits
    transaction.plan.peId.poke(3.U)
    transaction.plan.stid.poke(0.U)
    transaction.plan.epoch.poke(5.U)
    transaction.plan.transactionId.poke(0.U)
    transaction.plan.uopMask.poke(1.U)
    transaction.plan.groupCount.poke(1.U)
    transaction.plan.virtualTailEpoch.poke(tailEpoch.U)
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
    group.architecturalParentCount.poke(1.U)
    group.boundaryStart.poke(true.B)
    group.boundaryStop.poke(true.B)
    group.releasePcBase.poke(true.B)

    val uop = transaction.decoded.uops(0)
    uop.valid.poke(true.B)
    uop.plannedChildCount.poke(1.U)
    uop.identity.parentCount.poke(1.U)
    uop.identity.parents(0).key.valid.poke(true.B)
    uop.identity.parents(0).key.peId.poke(3.U)
    uop.identity.parents(0).key.stid.poke(0.U)
    uop.identity.parents(0).key.instructionId.poke(100.U)
    uop.identity.parents(0).key.epoch.poke(5.U)
    uop.identity.parents(0).pc.poke(256.U)
    uop.sources(0).valid.poke(true.B)
    uop.sources(0).operandClass.poke(OperandClass.P)
    uop.sources(0).atag.poke(1.U)
    uop.destinations(0).valid.poke(true.B)
    uop.destinations(0).kind.poke(DestinationKind.Gpr)
    uop.destinations(0).atag.poke(1.U)
    dut.io.reserve.valid.poke(true.B)
  }

  test("joins D3 PTag ROB BROB PC SMAP and MapQ publication on one fire") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      robGroupsPerStid = 8,
      brobEntriesPerStid = 8,
      pMapQDepthPerStid = 4,
      pTagStagingDepthPerBank = 2,
      pTagReturnWidth = 4)
    simulate(new OooO3RenameCoordinator(p)) { dut =>
      clear(dut)
      dut.clock.step() // refill the PTag staging rows
      pokeOneDestination(dut)
      dut.io.reserve.ready.expect(true.B)
      dut.clock.step()
      dut.io.reserve.valid.poke(false.B)

      dut.io.preparedValid.expect(true.B)
      dut.io.prepared.uops(0).sources(0).pMapping.ptag.expect(1.U)
      dut.io.prepared.uops(0).destinations(0)
        .currentPMapping.ptag.expect(96.U)
      dut.io.ptagProvisionalCount.expect(1.U)
      dut.io.ptagPublishedCount.expect(0.U)
      dut.io.mapQUsed(0).expect(0.U)
      dut.io.robOccupiedGroups(0).expect(0.U)
      dut.clock.step(2)
      dut.io.ptagProvisionalCount.expect(1.U)
      dut.io.mapQUsed(0).expect(0.U)

      dut.io.publishPermit.poke(true.B)
      dut.io.publishFire.expect(true.B)
      dut.clock.step()
      dut.io.publishPermit.poke(false.B)
      dut.io.ptagProvisionalCount.expect(0.U)
      dut.io.ptagPublishedCount.expect(1.U)
      dut.io.mapQUsed(0).expect(1.U)
      dut.io.robOccupiedGroups(0).expect(1.U)
      dut.io.queryAtag.poke(1.U)
      dut.io.speculativeMapping.ptag.expect(96.U)
      dut.io.committedMapping.ptag.expect(1.U)

      // O4.2 deliberately seals retirement until P CMAP/MapQ commit joins it.
      dut.io.ptagReturn.bits.count.poke(1.U)
      dut.io.ptagReturn.bits.tokens(0).valid.poke(true.B)
      dut.io.ptagReturn.bits.tokens(0).ptag.poke(96.U)
      dut.io.ptagReturn.bits.tokens(0).bank.poke(0.U)
      dut.io.ptagReturn.bits.tokens(0).generation.poke(1.U)
      dut.io.ptagReturn.valid.poke(true.B)
      dut.io.ptagReturn.ready.expect(false.B)
      dut.clock.step()
      dut.io.ptagReturn.valid.poke(false.B)
      dut.io.ptagPublishedCount.expect(1.U)

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
      dut.io.commit.ready.poke(true.B)
      dut.clock.step(2)
      dut.io.commit.valid.expect(false.B)
      dut.io.robOccupiedGroups(0).expect(1.U)
    }
  }

  test("consumes a stale D2 plan without orphaning a PTag lease") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      robGroupsPerStid = 8,
      brobEntriesPerStid = 8,
      pMapQDepthPerStid = 4,
      pTagStagingDepthPerBank = 2,
      pTagReturnWidth = 4)
    simulate(new OooO3RenameCoordinator(p)) { dut =>
      clear(dut)
      dut.clock.step()
      pokeOneDestination(dut, tailEpoch = 1)
      dut.io.reserve.ready.expect(true.B)
      dut.clock.step()
      dut.io.reserve.valid.poke(false.B)
      dut.io.preparedValid.expect(false.B)
      dut.io.ptagProvisionalCount.expect(0.U)
      dut.io.ptagPublishedCount.expect(0.U)
      dut.io.mapQUsed(0).expect(0.U)
      dut.io.robOccupiedGroups(0).expect(0.U)
    }
  }
}
