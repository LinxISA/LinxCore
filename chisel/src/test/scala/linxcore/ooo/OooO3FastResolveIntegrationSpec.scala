package linxcore.ooo

import chisel3._
import chisel3.util.Decoupled
import chisel3.simulator.scalatest.ChiselSim
import linxcore.common.DestinationKind
import org.scalatest.funsuite.AnyFunSuite

private class OooRawIcallPipelineIO(val p: OooParams) extends Bundle {
  val raw = Flipped(Decoupled(new OooRawInstructionGroup(p)))
  val fastReady = Input(Bool())
  val preparedValid = Output(Bool())
  val publishFire = Output(Bool())
  val fastRejected = Output(Bool())
  val fastTerminalFire = Output(Bool())
  val fastWriteback = Decoupled(new OooFastResolveWriteback(p))
  val fastBoundary = Decoupled(new OooFastResolveBoundaryRequest(p))
}

private class OooRawIcallPipeline(val p: OooParams) extends Module {
  val io = IO(new OooRawIcallPipelineIO(p))

  val d1 = Module(new OooD1Decode(p))
  val d2 = Module(new OooD2GroupPlanner(p))
  val o3 = Module(new OooO3RenameCoordinator(p))
  d1.io.in <> io.raw
  d2.io.in <> d1.io.out
  d2.io.tailSlot := o3.io.d3TailSlot
  d2.io.tailGeneration := o3.io.d3TailGeneration
  d2.io.tailEpoch := o3.io.d3TailEpoch
  d2.io.nextTransactionId := o3.io.d3NextTransactionId
  o3.io.reserve <> d2.io.out

  o3.io.cancel.foreach(_ := false.B)
  o3.io.iexS1.ready := true.B
  io.fastWriteback.valid := o3.io.fastWriteback.valid
  io.fastWriteback.bits := o3.io.fastWriteback.bits
  o3.io.fastWriteback.ready := io.fastWriteback.ready && io.fastReady
  io.fastBoundary.valid := o3.io.fastBoundary.valid
  io.fastBoundary.bits := o3.io.fastBoundary.bits
  o3.io.fastBoundary.ready := io.fastBoundary.ready
  o3.io.fastWakeup.ready := true.B
  o3.io.fastTrace.ready := true.B
  o3.io.completions.foreach { completion =>
    completion.valid := false.B
    completion.bits := 0.U.asTypeOf(completion.bits)
  }
  o3.io.nonFlushEvidence.valid := false.B
  o3.io.nonFlushEvidence.bits := 0.U.asTypeOf(o3.io.nonFlushEvidence.bits)
  o3.io.interruptPending.foreach(_ := false.B)
  o3.io.commit.ready := false.B
  o3.io.storeCommit.ready := true.B
  o3.io.ptagRecycle.ready := true.B
  o3.io.dispatchReleases.foreach { release =>
    release.valid := false.B
    release.bits := 0.U.asTypeOf(release.bits)
  }
  o3.io.recoveryRequest.valid := false.B
  o3.io.recoveryRequest.bits := 0.U.asTypeOf(o3.io.recoveryRequest.bits)
  o3.io.iexRecoveryPrepareReady := true.B
  o3.io.iexRecoveryPrepared := 0.U.asTypeOf(o3.io.iexRecoveryPrepared)
  o3.io.iexRecoveryRejected.valid := false.B
  o3.io.iexRecoveryRejected.bits := 0.U.asTypeOf(o3.io.iexRecoveryRejected.bits)
  o3.io.queryStid := 0.U
  o3.io.queryAtag := 1.U
  o3.io.pcReadTokens.foreach(_ := 0.U.asTypeOf(o3.io.pcReadTokens.head))

  io.preparedValid := o3.io.preparedValid
  io.publishFire := o3.io.publishFire
  io.fastRejected := o3.io.fastS1Rejected.valid
  io.fastTerminalFire := o3.io.fastTerminalFire
}

class OooO3FastResolveIntegrationSpec extends AnyFunSuite with ChiselSim {
  private def rule(symbol: String): OooOpcodeRecipeTable.Rule =
    OooOpcodeRecipeTable.Rules.find(_.symbol == symbol).getOrElse(
      fail(s"missing generated recipe for $symbol"))

  private def clear(dut: OooO3RenameCoordinator): Unit = {
    dut.io.reserve.valid.poke(false.B)
    dut.io.reserve.bits.poke(0.U.asTypeOf(dut.io.reserve.bits))
    dut.io.cancel.foreach(_.poke(false.B))
    dut.io.iexS1.ready.poke(false.B)
    dut.io.fastBoundary.ready.poke(true.B)
    dut.io.fastWriteback.ready.poke(true.B)
    dut.io.fastWakeup.ready.poke(true.B)
    dut.io.fastTrace.ready.poke(true.B)
    dut.io.completions.foreach { completion =>
      completion.valid.poke(false.B)
      completion.bits.poke(0.U.asTypeOf(completion.bits))
    }
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

      // A malformed global request is accepted into the retained coordinator,
      // then rejected without applying any fast-owner mutation.
      dut.io.recoveryRequest.valid.poke(true.B)
      dut.io.recoveryRequest.bits.poke(
        0.U.asTypeOf(dut.io.recoveryRequest.bits))
      dut.io.recoveryRequest.bits.rename.key.member.group.stid.poke(0.U)
      dut.io.recoveryRequest.ready.expect(true.B)
      dut.io.recoveryBusy.expect(false.B)
      dut.clock.step()
      dut.io.recoveryRequest.valid.poke(false.B)
      var recoveryCycles = 0
      while (dut.io.recoveryBusy.peek().litToBoolean && recoveryCycles < 8) {
        dut.clock.step()
        recoveryCycles += 1
      }
      assert(recoveryCycles < 8, "malformed global recovery did not abort")
      dut.io.fastPendingByStid(0).expect(1.U)
      dut.io.fastWriteback.valid.expect(true.B)
      dut.io.fastTerminalFire.expect(false.B)

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

  test("admits raw fused ICALL through D1 rename and fast resolve") {
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
    simulate(new OooRawIcallPipeline(p)) { dut =>
      dut.io.raw.valid.poke(false.B)
      dut.io.raw.bits.poke(0.U.asTypeOf(dut.io.raw.bits))
      dut.io.fastReady.poke(false.B)
      dut.io.fastWriteback.ready.poke(true.B)
      dut.io.fastBoundary.ready.poke(true.B)
      dut.clock.step()

      val uimm5 = 13
      val raw = rule("OP_BSTART_ICALL").value |
        (BigInt(uimm5) << 22)
      val group = dut.io.raw.bits
      group.peId.poke(3.U)
      group.stid.poke(0.U)
      group.epoch.poke(5.U)
      group.validMask.poke(1.U)
      val entry = group.entries(0)
      entry.parent.key.valid.poke(true.B)
      entry.parent.key.peId.poke(3.U)
      entry.parent.key.stid.poke(0.U)
      entry.parent.key.instructionId.poke(190.U)
      entry.parent.key.epoch.poke(5.U)
      entry.parent.pc.poke(0x400.U)
      entry.parent.rawInstruction.poke(raw.U)
      entry.parent.lengthBytes.poke(4.U)
      entry.parent.traceOwner.poke(true.B)
      entry.parent.preciseExceptionOwner.poke(true.B)
      entry.retiringBargBpcnValid.poke(true.B)
      entry.retiringBargBpcn.poke(0x900.U)
      dut.io.raw.valid.poke(true.B)
      while (!dut.io.raw.ready.peek().litToBoolean) {
        dut.clock.step()
      }
      dut.clock.step()
      dut.io.raw.valid.poke(false.B)

      var cycles = 0
      while (!dut.io.fastWriteback.valid.peek().litToBoolean && cycles < 24) {
        dut.clock.step()
        cycles += 1
      }
      assert(cycles < 24, "raw ICALL did not reach fast resolve")
      dut.io.fastRejected.expect(false.B)
      dut.io.fastWriteback.bits.data.expect((0x400 + 2 + (uimm5 << 1)).U)
      dut.io.fastBoundary.bits.targetValid.expect(true.B)
      dut.io.fastBoundary.bits.target.expect(0x900.U)
      dut.io.fastReady.poke(true.B)
      dut.io.fastWriteback.valid.expect(true.B)
      dut.io.fastBoundary.valid.expect(true.B)
      dut.io.fastTerminalFire.expect(true.B)
      dut.clock.step()
    }
  }
}
