package linxcore.ooo

import chisel3._
import chisel3.simulator.HasSimulator
import chisel3.simulator.scalatest.ChiselSim
import chisel3.util.{Decoupled, Valid}
import linxcore.common.{DestinationKind, OperandClass}
import org.scalatest.funsuite.AnyFunSuite
import svsim.verilator.Backend

class OooO3IexIntegrationHarnessIO(val p: OooParams) extends Bundle {
  val reserve = Flipped(Decoupled(new OooD2GroupedTransaction(p)))
  val release = Flipped(Decoupled(new OooIexIssueRelease(p)))
  val recoveryRequest = Flipped(Decoupled(new OooGlobalRecoveryRequest(p)))
  val query = Input(new OooIexSlotQuery(p))

  val publishFire = Output(Bool())
  val recoveryBusy = Output(Bool())
  val recoveryComplete = Output(Bool())
  val recoveryApplied = Valid(new OooGlobalRecoveryRequest(p))
  val s2Bind = Valid(new OooIexS2BindAck(p))
  val s3Enable = Valid(new OooIexS3Enable(p))
  val queryState = Output(OooIexIssueSlotState())
  val queryPickable = Output(Bool())
  val queryMember = Output(new RobMemberKey(p))
  val queryReservation = Output(new DispatchReservation(p))
  val queryMemoryOrder = Output(new OooMemoryOrderUopAllocation(p))
  val memoryOrderNext = Output(Vec(p.stidCount, new OooMemoryIdState(p)))
  val dispatchPublishedEntries = Output(Vec(p.iqClassCount,
    Vec(p.iqBankCount, UInt(p.iqBankEntryCountWidth.W))))
  val robOccupiedGroups = Output(Vec(p.stidCount,
    UInt(p.countWidth(p.robGroupsPerStid).W)))
  val mapQUsed = Output(Vec(p.stidCount, UInt(p.pMapQCountWidth.W)))
}

/** Minimal integration shell for the real O3/RENU/dispatch-to-IEX seam. */
class OooO3IexIntegrationHarness(val p: OooParams) extends Module {
  val io = IO(new OooO3IexIntegrationHarnessIO(p))

  val coordinator = Module(new OooO3RenameCoordinator(p))
  coordinator.io.storeCommit.ready := true.B
  val issue = Module(new OooIexIssue(p))

  coordinator.io.reserve.valid := io.reserve.valid
  coordinator.io.reserve.bits := io.reserve.bits
  io.reserve.ready := coordinator.io.reserve.ready
  coordinator.io.cancel.foreach(_ := false.B)
  coordinator.io.completion.valid := false.B
  coordinator.io.completion.bits := 0.U.asTypeOf(coordinator.io.completion.bits)
  coordinator.io.nonFlushEvidence.valid := false.B
  coordinator.io.nonFlushEvidence.bits :=
    0.U.asTypeOf(coordinator.io.nonFlushEvidence.bits)
  coordinator.io.interruptPending.foreach(_ := false.B)
  coordinator.io.commit.ready := false.B
  coordinator.io.fastBoundary.ready := true.B
  coordinator.io.fastWriteback.ready := true.B
  coordinator.io.fastWakeup.ready := true.B
  coordinator.io.fastTrace.ready := true.B
  coordinator.io.recoveryRequest <> io.recoveryRequest
  coordinator.io.queryStid := 0.U
  coordinator.io.queryAtag := 0.U
  coordinator.io.pcReadTokens.foreach { token =>
    token := 0.U.asTypeOf(token)
  }

  issue.io.s1 <> coordinator.io.iexS1
  coordinator.io.dispatchRelease <> issue.io.dispatchRelease
  issue.io.ptagRecycle <> coordinator.io.ptagRecycle
  issue.io.wakeup.foreach { wakeup =>
    wakeup.valid := false.B
    wakeup.bits := 0.U.asTypeOf(wakeup.bits)
  }
  issue.io.loadCancel.foreach { cancel =>
    cancel.valid := false.B
    cancel.bits := 0.U.asTypeOf(cancel.bits)
  }
  issue.io.release.valid := io.release.valid
  issue.io.release.bits := io.release.bits
  io.release.ready := issue.io.release.ready
  issue.io.query := io.query
  issue.io.pickBankEnable.foreach(_ := 0.U)
  issue.io.pickBankEnable(OooUopClass.Agu.asUInt.litValue.toInt) :=
    ((BigInt(1) << p.iqBankCount) - 1).U
  issue.io.issuePolicy := 0.U.asTypeOf(issue.io.issuePolicy)
  issue.io.pick.ready := false.B
  issue.io.pickRetry.valid := false.B
  issue.io.pickRetry.bits := 0.U.asTypeOf(issue.io.pickRetry.bits)
  issue.io.recoveryPrepare := coordinator.io.iexRecoveryPrepare
  coordinator.io.iexRecoveryPrepareReady := issue.io.recoveryPrepareReady
  coordinator.io.iexRecoveryPrepared := issue.io.recoveryPrepared
  coordinator.io.iexRecoveryRejected := issue.io.recoveryRejected
  issue.io.recoveryFire := coordinator.io.iexRecoveryFire

  io.publishFire := coordinator.io.publishFire
  io.recoveryBusy := coordinator.io.recoveryBusy
  io.recoveryComplete := coordinator.io.recoveryComplete
  io.recoveryApplied := coordinator.io.recoveryApplied
  io.s2Bind := issue.io.s2Bind
  io.s3Enable := issue.io.s3Enable
  io.queryState := issue.io.queryState
  io.queryPickable := issue.io.queryPickable
  io.queryMember := issue.io.queryRow.member
  io.queryReservation := issue.io.queryRow.reservation
  io.queryMemoryOrder := issue.io.queryRow.memoryOrder
  io.memoryOrderNext := coordinator.io.memoryOrderNext
  io.dispatchPublishedEntries := coordinator.io.dispatchPublishedEntries
  io.robOccupiedGroups := coordinator.io.robOccupiedGroups
  io.mapQUsed := coordinator.io.mapQUsed
}

class OooO3IexIntegrationSpec extends AnyFunSuite with ChiselSim {
  private implicit val boundedVerilator: HasSimulator =
    HasSimulator.simulators.verilator(verilatorSettings =
      Backend.CompilationSettings.default
        .withOutputSplit(Some(2000))
        .withOutputSplitCFuncs(Some(100))
        .withParallelism(Some(
          Backend.CompilationSettings.Parallelism.Different.default
            .withBuild(Some(1)).withVerilate(Some(1)))))

  private def clear(dut: OooO3IexIntegrationHarness): Unit = {
    dut.io.reserve.valid.poke(false.B)
    dut.io.reserve.bits.poke(0.U.asTypeOf(dut.io.reserve.bits))
    dut.io.release.valid.poke(false.B)
    dut.io.release.bits.poke(0.U.asTypeOf(dut.io.release.bits))
    dut.io.recoveryRequest.valid.poke(false.B)
    dut.io.recoveryRequest.bits.poke(
      0.U.asTypeOf(dut.io.recoveryRequest.bits))
    dut.io.query.poke(0.U.asTypeOf(dut.io.query))
  }

  private def pokeTransaction(dut: OooO3IexIntegrationHarness): Unit = {
    val transaction = dut.io.reserve.bits
    transaction.poke(0.U.asTypeOf(transaction))
    transaction.plan.peId.poke(3.U)
    transaction.plan.stid.poke(0.U)
    transaction.plan.epoch.poke(5.U)
    transaction.plan.transactionId.poke(0.U)
    transaction.plan.uopMask.poke(1.U)
    transaction.plan.groupCount.poke(1.U)
    transaction.plan.firstVirtualGroup.valid.poke(true.B)
    transaction.plan.firstVirtualGroup.peId.poke(3.U)
    transaction.plan.firstVirtualGroup.stid.poke(0.U)
    transaction.plan.firstVirtualGroup.ridSlot.poke(0.U)
    transaction.plan.firstVirtualGroup.ridGeneration.poke(0.U)
    transaction.plan.demand.pDestinations.poke(1.U)
    transaction.plan.demand.mapQRows.poke(1.U)
    transaction.plan.demand.dispatchWritesByClass(2).poke(1.U)
    transaction.plan.demand.loadIds.poke(1.U)

    transaction.decoded.peId.poke(3.U)
    transaction.decoded.stid.poke(0.U)
    transaction.decoded.epoch.poke(5.U)
    transaction.decoded.uopMask.poke(1.U)
    transaction.decoded.demand.loadIds.poke(1.U)
    transaction.groupMask.poke(1.U)

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

    transaction.uopGroupIndex(0).poke(0.U)
    transaction.uopMemberBase(0).poke(0.U)
    val uop = transaction.decoded.uops(0)
    uop.valid.poke(true.B)
    uop.opcode.poke(42.U)
    uop.recipe.valid.poke(true.B)
    uop.recipe.opcode.poke(42.U)
    uop.recipe.dispatchClass.poke(OooDispatchClass.Agu.U)
    uop.recipe.dispatchWrites.poke(1.U)
    uop.recipe.dispatchDemand(2).poke(1.U)
    uop.recipe.dispatchCapabilities(2).poke(
      OooIexDomainCapability.mask(OooIexDomainCapability.LoadAddress).U)
    uop.recipe.memoryRequestCount.poke(1.U)
    uop.memory.valid.poke(true.B)
    uop.memory.isLoad.poke(true.B)
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

  private def pokeGlobalRecovery(dut: OooO3IexIntegrationHarness): Unit = {
    val global = dut.io.recoveryRequest.bits
    global.poke(0.U.asTypeOf(global))
    val request = global.rename
    request.key.member.group.valid.poke(true.B)
    request.key.member.group.peId.poke(3.U)
    request.key.member.group.stid.poke(0.U)
    request.key.member.group.ridSlot.poke(0.U)
    request.key.member.group.ridGeneration.poke(0.U)
    request.key.member.bid.valid.poke(true.B)
    request.key.member.bid.value.poke(0.U)
    request.key.member.brobGeneration.poke(0.U)
    request.key.member.memberIndex.poke(0.U)
    request.key.member.residentGeneration.poke(1.U)
    request.key.cause.poke(OooRecoveryCause.Branch)
    request.key.transactionId.poke(0.U)
    request.key.epoch.poke(5.U)
    request.killTrigger.poke(true.B)
    global.triggerMemberCount.poke(1.U)
    dut.io.recoveryRequest.valid.poke(true.B)
  }

  test("publishes one exact O3 row then globally recovers ROB rename dispatch and IEX") {
    val p = OooParams(
      stidCount = 2,
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      dispatchWidth = 2,
      iqBankCount = 2,
      iqEntriesPerBank = 2,
      iqWritePortsPerBank = 2,
      robGroupsPerStid = 4,
      brobEntriesPerStid = 4,
      pTagStagingDepthPerBank = 2,
      pMapQDepthPerStid = 4,
      tuMapQDepthPerStid = 4,
      tuRetireSourceDepthPerStid = 8)
    simulate(new OooO3IexIntegrationHarness(p)) { dut =>
      clear(dut)
      dut.clock.step() // fill PTag staging
      pokeTransaction(dut)
      dut.io.reserve.ready.expect(true.B)
      dut.clock.step()
      dut.io.reserve.valid.poke(false.B)

      dut.io.publishFire.expect(true.B)
      dut.clock.step() // exact O3/RENU/dispatch/IEX-S1 common fire
      dut.io.dispatchPublishedEntries(2)(0).expect(1.U)
      dut.io.s2Bind.valid.expect(true.B)
      dut.clock.step() // S2 physical row bind
      dut.io.s3Enable.valid.expect(true.B)
      dut.clock.step() // S3 pick enable

      dut.io.query.uopClass.poke(OooUopClass.Agu)
      dut.io.query.bank.poke(0.U)
      dut.io.query.entry.poke(0.U)
      dut.io.queryState.expect(OooIexIssueSlotState.ResidentS3)
      dut.io.queryPickable.expect(true.B)
      dut.io.queryMember.memberIndex.expect(0.U)
      dut.io.queryMember.residentGeneration.expect(1.U)
      dut.io.queryReservation.valid.expect(true.B)
      dut.io.queryReservation.writePort.expect(0.U)
      dut.io.queryReservation.reservationEpoch.expect(1.U)
      dut.io.queryMemoryOrder.valid.expect(true.B)
      dut.io.queryMemoryOrder.memoryValid.expect(true.B)
      dut.io.queryMemoryOrder.isLoad.expect(true.B)
      dut.io.queryMemoryOrder.requestCount.expect(1.U)
      dut.io.queryMemoryOrder.firstLsid.expect(0.U)
      dut.io.queryMemoryOrder.firstTypeId.expect(0.U)
      dut.io.queryMemoryOrder.after.lsid.expect(1.U)
      dut.io.queryMemoryOrder.after.loadId.expect(1.U)
      dut.io.memoryOrderNext(0).lsid.expect(1.U)
      dut.io.memoryOrderNext(0).loadId.expect(1.U)

      dut.io.dispatchPublishedEntries(2)(0).expect(1.U)
      dut.io.robOccupiedGroups(0).expect(1.U)
      dut.io.mapQUsed(0).expect(1.U)

      pokeGlobalRecovery(dut)
      dut.io.recoveryRequest.ready.expect(true.B)
      dut.clock.step()
      dut.io.recoveryRequest.valid.poke(false.B)

      var cycles = 0
      var sawApplied = false
      var sawComplete = false
      while (dut.io.recoveryBusy.peek().litToBoolean && cycles < 64) {
        sawApplied ||= dut.io.recoveryApplied.valid.peek().litToBoolean
        sawComplete ||= dut.io.recoveryComplete.peek().litToBoolean
        dut.clock.step()
        cycles += 1
      }
      assert(cycles < 64, "global O3/IEX recovery did not complete")
      assert(sawApplied, "global recovery never produced one common apply")
      assert(sawComplete, "global recovery never reached R4 completion")
      dut.io.queryState.expect(OooIexIssueSlotState.Free)
      dut.io.dispatchPublishedEntries(0)(0).expect(0.U)
      dut.io.robOccupiedGroups(0).expect(0.U)
      dut.io.mapQUsed(0).expect(0.U)
      dut.io.memoryOrderNext(0).lsid.expect(0.U)
      dut.io.memoryOrderNext(0).loadId.expect(0.U)
    }
  }
}
