package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import chisel3.util.Decoupled
import org.scalatest.funsuite.AnyFunSuite

class OooIexIssueE1IntegrationHarnessIO(val p: OooParams) extends Bundle {
  val s1 = Flipped(Decoupled(new OooIexS1Transaction(p)))
  val e1 = Vec(p.iexIssueDomainCount,
    Decoupled(new OooIexExecuteTransaction(p)))
  val dispatchRelease = Decoupled(new OooDispatchRelease(p))
  val transferFire = Output(Bool())
  val transferDomain = Output(UInt(p.iexIssueDomainWidth.W))
  val residentEntries = Output(Vec(p.iqClassCount,
    Vec(p.iqBankCount, UInt(p.iqBankEntryCountWidth.W))))
  val inFlightEntries = Output(Vec(p.iqClassCount,
    Vec(p.iqBankCount, UInt(p.iqBankEntryCountWidth.W))))
  val empty = Output(Bool())
}

class OooIexIssueE1IntegrationHarness(
    val p: OooParams,
    val topology: Seq[OooIexIssueDomainConfig]) extends Module {
  val io = IO(new OooIexIssueE1IntegrationHarnessIO(p))

  val issue = Module(new OooIexIssueReadFabric(p))
  val transfer = Module(new OooIexE1TransferFabric(p, topology))

  issue.io.s1 <> io.s1
  issue.io.wakeup.foreach(_ := 0.U.asTypeOf(issue.io.wakeup.head))
  issue.io.loadCancel.foreach(
    _ := 0.U.asTypeOf(issue.io.loadCancel.head))
  issue.io.ptagRecycle.valid := false.B
  issue.io.ptagRecycle.bits := 0.U.asTypeOf(issue.io.ptagRecycle.bits)
  issue.io.recoveryPrepare.valid := false.B
  issue.io.recoveryPrepare.bits :=
    0.U.asTypeOf(issue.io.recoveryPrepare.bits)
  issue.io.recoveryFire := false.B
  issue.io.pickClasses := transfer.io.pickClasses
  issue.io.pickBankEnables := transfer.io.pickBankEnables
  issue.io.pcReadResponses.foreach(
    _ := 0.U.asTypeOf(issue.io.pcReadResponses.head))
  issue.io.bypass.foreach(_ := 0.U.asTypeOf(issue.io.bypass.head))
  issue.io.pInit := 0.U.asTypeOf(issue.io.pInit)
  issue.io.pClear.foreach(_ := 0.U.asTypeOf(issue.io.pClear.head))
  issue.io.pWrite.foreach(_ := 0.U.asTypeOf(issue.io.pWrite.head))
  issue.io.tClear.foreach(_ := 0.U.asTypeOf(issue.io.tClear.head))
  issue.io.uClear.foreach(_ := 0.U.asTypeOf(issue.io.uClear.head))
  issue.io.tWrite.foreach(_ := 0.U.asTypeOf(issue.io.tWrite.head))
  issue.io.uWrite.foreach(_ := 0.U.asTypeOf(issue.io.uWrite.head))

  for (domain <- 0 until p.iexIssueDomainCount) {
    transfer.io.i2(domain) <> issue.io.i2(domain)
    io.e1(domain) <> transfer.io.e1(domain)
  }
  issue.io.release <> transfer.io.issueRelease
  io.dispatchRelease <> issue.io.dispatchRelease
  transfer.io.recoveryApply.valid := false.B
  transfer.io.recoveryApply.bits :=
    0.U.asTypeOf(transfer.io.recoveryApply.bits)
  transfer.io.loadCancel.foreach(
    _ := 0.U.asTypeOf(transfer.io.loadCancel.head))

  io.transferFire := transfer.io.issueRelease.fire
  io.transferDomain := transfer.io.releaseDomain.bits
  io.residentEntries := issue.io.residentEntries
  io.inFlightEntries := issue.io.inFlightEntries
  io.empty := issue.io.empty && transfer.io.empty
}

class OooIexIssueE1IntegrationSpec extends AnyFunSuite with ChiselSim {
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

  private val topology = Seq(
    OooIexIssueDomainConfig(OooUopClass.Alu.asUInt.litValue.toInt, 1),
    OooIexIssueDomainConfig(OooUopClass.Bru.asUInt.litValue.toInt, 1))

  private def pokeMember(target: RobMemberKey): Unit = {
    target.group.valid.poke(true.B)
    target.group.peId.poke(3.U)
    target.group.stid.poke(1.U)
    target.group.ridSlot.poke(2.U)
    target.group.ridGeneration.poke(1.U)
    target.bid.valid.poke(true.B)
    target.bid.value.poke(4.U)
    target.brobGeneration.poke(2.U)
    target.memberIndex.poke(0.U)
    target.residentGeneration.poke(5.U)
  }

  private def pokeSourceFreeAlu(
      dut: OooIexIssueE1IntegrationHarness): Unit = {
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
    transaction.decoded.uopMask.poke(1.U)

    request.pRename.valid.poke(true.B)
    request.pRename.peId.poke(3.U)
    request.pRename.stid.poke(1.U)
    request.pRename.epoch.poke(7.U)
    request.pRename.transactionId.poke(31.U)
    request.pRename.uopMask.poke(1.U)
    request.tuRename.valid.poke(true.B)
    request.tuRename.peId.poke(3.U)
    request.tuRename.stid.poke(1.U)
    request.tuRename.epoch.poke(7.U)
    request.tuRename.transactionId.poke(31.U)
    request.tuRename.uopMask.poke(1.U)
    request.dispatch.valid.poke(true.B)
    request.dispatch.peId.poke(3.U)
    request.dispatch.stid.poke(1.U)
    request.dispatch.epoch.poke(7.U)
    request.dispatch.transactionId.poke(31.U)
    request.dispatch.allocationMask.poke(1.U)

    val decoded = transaction.decoded.uops(0)
    val renamed = request.pRename.uops(0)
    val local = request.tuRename.uops(0)
    decoded.valid.poke(true.B)
    renamed.valid.poke(true.B)
    renamed.decoded.valid.poke(true.B)
    local.valid.poke(true.B)
    Seq(decoded, renamed.decoded).foreach { uop =>
      uop.opcode.poke(1.U)
      uop.recipe.valid.poke(true.B)
      uop.recipe.opcode.poke(1.U)
      uop.recipe.disposition.poke(OooOpcodeDisposition.Dispatch.U)
      uop.recipe.dispatchClass.poke(OooDispatchClass.Alu.U)
      uop.recipe.dispatchWrites.poke(1.U)
      uop.recipe.dispatchDemand(OooDispatchClass.Alu - 1).poke(1.U)
      uop.recipe.pcReadRequired.poke(false.B)
      uop.recipe.pcReadClass.poke(OooDispatchClass.Alu.U)
      uop.plannedChildCount.poke(1.U)
      uop.identity.key.primaryParent.valid.poke(true.B)
      uop.identity.key.primaryParent.peId.poke(3.U)
      uop.identity.key.primaryParent.stid.poke(1.U)
      uop.identity.key.primaryParent.instructionId.poke(101.U)
      uop.identity.key.primaryParent.epoch.poke(7.U)
      uop.identity.parentCount.poke(1.U)
      val parent = uop.identity.parents(0)
      parent.key.valid.poke(true.B)
      parent.key.peId.poke(3.U)
      parent.key.stid.poke(1.U)
      parent.key.instructionId.poke(101.U)
      parent.key.epoch.poke(7.U)
    }
    pokeMember(renamed.member)
    pokeMember(local.member)

    val allocation = request.dispatch.allocations(0)
    allocation.valid.poke(true.B)
    allocation.uopIndex.poke(0.U)
    allocation.childIndex.poke(0.U)
    allocation.reservation.valid.poke(true.B)
    allocation.reservation.uopClass.poke(OooUopClass.Alu)
    allocation.reservation.bank.poke(0.U)
    allocation.reservation.writePort.poke(0.U)
    allocation.reservation.speculativeSlot.poke(1.U)
    allocation.reservation.reservationEpoch.poke(9.U)
    dut.io.s1.valid.poke(true.B)
  }

  test("canonical IQ release and dispatch return share the E1 transfer") {
    simulate(new OooIexIssueE1IntegrationHarness(p, topology)) { dut =>
      dut.io.s1.valid.poke(false.B)
      dut.io.s1.bits.poke(0.U.asTypeOf(dut.io.s1.bits))
      dut.io.e1.foreach(_.ready.poke(false.B))
      dut.io.dispatchRelease.ready.poke(true.B)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      pokeSourceFreeAlu(dut)
      dut.io.s1.ready.expect(true.B)
      dut.clock.step()
      dut.io.s1.valid.poke(false.B)

      var cycles = 0
      while (!dut.io.transferFire.peek().litToBoolean && cycles < 12) {
        dut.clock.step()
        cycles += 1
      }
      assert(dut.io.transferFire.peek().litToBoolean,
        "the exact ALU row never crossed the canonical release boundary")
      dut.io.transferDomain.expect(0.U)
      dut.io.dispatchRelease.valid.expect(true.B)
      dut.io.dispatchRelease.bits.member.group.ridSlot.expect(2.U)
      dut.clock.step()

      dut.io.e1(0).valid.expect(true.B)
      dut.io.e1(0).bits.ownerClass.expect(OooUopClass.Alu)
      dut.io.e1(0).bits.i2.row.member.group.ridSlot.expect(2.U)
      dut.io.residentEntries(OooDispatchClass.Alu - 1)(0).expect(0.U)
      dut.io.inFlightEntries(OooDispatchClass.Alu - 1)(0).expect(0.U)
      dut.io.empty.expect(false.B)

      dut.io.e1(0).ready.poke(true.B)
      dut.clock.step()
      dut.io.empty.expect(true.B)
    }
  }
}
