package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import chisel3.util.{Decoupled, Valid}
import linxcore.common.{DestinationKind, OperandClass}
import org.scalatest.funsuite.AnyFunSuite

class OooO3IexIntegrationHarnessIO(val p: OooParams) extends Bundle {
  val reserve = Flipped(Decoupled(new OooD2GroupedTransaction(p)))
  val release = Flipped(Decoupled(new OooIexIssueRelease(p)))
  val query = Input(new OooIexSlotQuery(p))

  val publishFire = Output(Bool())
  val s2Bind = Valid(new OooIexS2BindAck(p))
  val s3Enable = Valid(new OooIexS3Enable(p))
  val queryState = Output(OooIexIssueSlotState())
  val queryPickable = Output(Bool())
  val queryMember = Output(new RobMemberKey(p))
  val queryReservation = Output(new DispatchReservation(p))
  val dispatchPublishedEntries = Output(Vec(p.iqClassCount,
    Vec(p.iqBankCount, UInt(p.iqBankEntryCountWidth.W))))
}

/** Minimal integration shell for the real O3/RENU/dispatch-to-IEX seam. */
class OooO3IexIntegrationHarness(val p: OooParams) extends Module {
  val io = IO(new OooO3IexIntegrationHarnessIO(p))

  val coordinator = Module(new OooO3RenameCoordinator(p))
  val issue = Module(new OooProductionIexIssue(p))

  coordinator.io.reserve.valid := io.reserve.valid
  coordinator.io.reserve.bits := io.reserve.bits
  io.reserve.ready := coordinator.io.reserve.ready
  coordinator.io.cancel.foreach(_ := false.B)
  coordinator.io.completion.valid := false.B
  coordinator.io.completion.bits := 0.U.asTypeOf(coordinator.io.completion.bits)
  coordinator.io.commit.ready := false.B
  coordinator.io.ptagReturn.valid := false.B
  coordinator.io.ptagReturn.bits :=
    0.U.asTypeOf(coordinator.io.ptagReturn.bits)
  coordinator.io.fastBoundary.ready := true.B
  coordinator.io.fastWriteback.ready := true.B
  coordinator.io.fastWakeup.ready := true.B
  coordinator.io.fastTrace.ready := true.B
  coordinator.io.recoveryRequest.valid := false.B
  coordinator.io.recoveryRequest.bits :=
    0.U.asTypeOf(coordinator.io.recoveryRequest.bits)
  coordinator.io.queryStid := 0.U
  coordinator.io.queryAtag := 0.U
  coordinator.io.pcReadTokens.foreach { token =>
    token := 0.U.asTypeOf(token)
  }

  issue.io.s1 <> coordinator.io.iexS1
  coordinator.io.dispatchRelease <> issue.io.dispatchRelease
  issue.io.wakeup.foreach { wakeup =>
    wakeup.valid := false.B
    wakeup.bits := 0.U.asTypeOf(wakeup.bits)
  }
  issue.io.release.valid := io.release.valid
  issue.io.release.bits := io.release.bits
  io.release.ready := issue.io.release.ready
  issue.io.query := io.query

  io.publishFire := coordinator.io.publishFire
  io.s2Bind := issue.io.s2Bind
  io.s3Enable := issue.io.s3Enable
  io.queryState := issue.io.queryState
  io.queryPickable := issue.io.queryPickable
  io.queryMember := issue.io.queryRow.member
  io.queryReservation := issue.io.queryRow.reservation
  io.dispatchPublishedEntries := coordinator.io.dispatchPublishedEntries
}

class OooO3IexIntegrationSpec extends AnyFunSuite with ChiselSim {
  private def clear(dut: OooO3IexIntegrationHarness): Unit = {
    dut.io.reserve.valid.poke(false.B)
    dut.io.reserve.bits.poke(0.U.asTypeOf(dut.io.reserve.bits))
    dut.io.release.valid.poke(false.B)
    dut.io.release.bits.poke(0.U.asTypeOf(dut.io.release.bits))
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
    transaction.plan.demand.dispatchWritesByClass(0).poke(1.U)

    transaction.decoded.peId.poke(3.U)
    transaction.decoded.stid.poke(0.U)
    transaction.decoded.epoch.poke(5.U)
    transaction.decoded.uopMask.poke(1.U)
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
    uop.recipe.dispatchClass.poke(OooDispatchClass.Alu.U)
    uop.recipe.dispatchWrites.poke(1.U)
    uop.recipe.dispatchDemand(0).poke(1.U)
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

  private def pokeExactRelease(dut: OooO3IexIntegrationHarness): Unit = {
    val release = dut.io.release.bits
    release.poke(0.U.asTypeOf(release))
    release.member.group.valid.poke(true.B)
    release.member.group.peId.poke(3.U)
    release.member.group.stid.poke(0.U)
    release.member.group.ridSlot.poke(0.U)
    release.member.group.ridGeneration.poke(0.U)
    release.member.bid.valid.poke(true.B)
    release.member.bid.value.poke(0.U)
    release.member.brobGeneration.poke(0.U)
    release.member.memberIndex.poke(0.U)
    release.member.residentGeneration.poke(1.U)
    release.dispatch.peId.poke(3.U)
    release.dispatch.stid.poke(0.U)
    release.dispatch.epoch.poke(5.U)
    release.dispatch.transactionId.poke(0.U)
    release.dispatch.reservation.valid.poke(true.B)
    release.dispatch.reservation.uopClass.poke(OooUopClass.Alu)
    release.dispatch.reservation.bank.poke(0.U)
    release.dispatch.reservation.writePort.poke(0.U)
    release.dispatch.reservation.speculativeSlot.poke(0.U)
    release.dispatch.reservation.reservationEpoch.poke(1.U)
    dut.io.release.valid.poke(true.B)
  }

  test("publishes one exact O3 row through S1 S2 S3 and releases both owners") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      dispatchWidth = 2,
      iqBankCount = 2,
      iqEntriesPerBank = 4,
      iqWritePortsPerBank = 2,
      robGroupsPerStid = 8,
      brobEntriesPerStid = 8,
      pTagStagingDepthPerBank = 2,
      pMapQDepthPerStid = 4,
      tuMapQDepthPerStid = 4,
      tuRetireSourceDepthPerStid = 16)
    simulate(new OooO3IexIntegrationHarness(p)) { dut =>
      clear(dut)
      dut.clock.step() // fill PTag staging
      pokeTransaction(dut)
      dut.io.reserve.ready.expect(true.B)
      dut.clock.step()
      dut.io.reserve.valid.poke(false.B)

      dut.io.publishFire.expect(true.B)
      dut.clock.step() // exact O3/RENU/dispatch/IEX-S1 common fire
      dut.io.dispatchPublishedEntries(0)(0).expect(1.U)
      dut.io.s2Bind.valid.expect(true.B)
      dut.clock.step() // S2 physical row bind
      dut.io.s3Enable.valid.expect(true.B)
      dut.clock.step() // S3 pick enable

      dut.io.query.uopClass.poke(OooUopClass.Alu)
      dut.io.query.bank.poke(0.U)
      dut.io.query.entry.poke(0.U)
      dut.io.queryState.expect(OooIexIssueSlotState.ResidentS3)
      dut.io.queryPickable.expect(true.B)
      dut.io.queryMember.memberIndex.expect(0.U)
      dut.io.queryMember.residentGeneration.expect(1.U)
      dut.io.queryReservation.valid.expect(true.B)
      dut.io.queryReservation.writePort.expect(0.U)
      dut.io.queryReservation.reservationEpoch.expect(1.U)

      pokeExactRelease(dut)
      dut.io.release.ready.expect(true.B)
      dut.clock.step()
      dut.io.release.valid.poke(false.B)
      dut.io.queryState.expect(OooIexIssueSlotState.Free)
      dut.io.dispatchPublishedEntries(0)(0).expect(0.U)
    }
  }
}
