package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import chisel3.util.Decoupled
import org.scalatest.funsuite.AnyFunSuite

class OooIexIssueE1IntegrationHarnessIO(val p: OooParams) extends Bundle {
  val s1 = Flipped(Decoupled(new OooIexS1Transaction(p)))
  val e1 = Vec(p.iexIssueDomainCount,
    Decoupled(new OooIexExecuteTransaction(p)))
  val dispatchReleases = Vec(p.iexReleaseWidth,
    Decoupled(new OooDispatchRelease(p)))
  def dispatchRelease = dispatchReleases(0)
  val transferFire = Output(Bool())
  val transferFireMask = Output(UInt(p.iexReleaseWidth.W))
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
  for (port <- 0 until p.pcReadPorts) {
    issue.io.pcReadResponses(port).valid :=
      issue.io.pcReadRequests(port).valid
    issue.io.pcReadResponses(port).bits := ("h80000100".U + port.U)
  }
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
  issue.io.releases <> transfer.io.issueReleases
  io.dispatchReleases <> issue.io.dispatchReleases
  transfer.io.recoveryApply.valid := false.B
  transfer.io.recoveryApply.bits :=
    0.U.asTypeOf(transfer.io.recoveryApply.bits)
  transfer.io.loadCancel.foreach(
    _ := 0.U.asTypeOf(transfer.io.loadCancel.head))

  io.transferFire := transfer.io.issueRelease.fire
  io.transferFireMask := VecInit(transfer.io.issueReleases.map(_.fire)).asUInt
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
    iexReleaseWidth = 2,
    pMapQDepthPerStid = 4,
    tuMapQDepthPerStid = 4,
    tuRetireSourceDepthPerStid = 16)

  private val topology = Seq(
    OooIexIssueDomainConfig(
      OooUopClass.Alu.asUInt.litValue.toInt, 1, releasePort = 0),
    OooIexIssueDomainConfig(
      OooUopClass.Bru.asUInt.litValue.toInt, 1, releasePort = 1))

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

  private def pokeSourceFreeAluBru(
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

    def pokeUop(
        index: Int,
        opcode: Int,
        dispatchClass: Int,
        uopClass: OooUopClass.Type,
        pcRead: Boolean): Unit = {
      val decoded = transaction.decoded.uops(index)
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

    pokeUop(0, opcode = 1, OooDispatchClass.Alu,
      OooUopClass.Alu, pcRead = false)
    pokeUop(1, opcode = 73, OooDispatchClass.Bru,
      OooUopClass.Bru, pcRead = true)
    dut.io.s1.valid.poke(true.B)
  }

  test("canonical IQ release and dispatch return share the E1 transfer") {
    simulate(new OooIexIssueE1IntegrationHarness(p, topology)) { dut =>
      dut.io.s1.valid.poke(false.B)
      dut.io.s1.bits.poke(0.U.asTypeOf(dut.io.s1.bits))
      dut.io.e1.foreach(_.ready.poke(false.B))
      dut.io.dispatchReleases.foreach(_.ready.poke(true.B))
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      pokeSourceFreeAluBru(dut)
      dut.io.s1.ready.expect(true.B)
      dut.clock.step()
      dut.io.s1.valid.poke(false.B)

      var cycles = 0
      while (dut.io.transferFireMask.peek().litValue != 3 && cycles < 12) {
        dut.clock.step()
        cycles += 1
      }
      dut.io.transferFireMask.expect(3.U)
      dut.io.transferDomain.expect(0.U)
      dut.io.dispatchReleases.foreach(_.valid.expect(true.B))
      dut.io.dispatchReleases(0).bits.member.memberIndex.expect(0.U)
      dut.io.dispatchReleases(1).bits.member.memberIndex.expect(1.U)
      dut.clock.step()

      dut.io.e1(0).valid.expect(true.B)
      dut.io.e1(1).valid.expect(true.B)
      dut.io.e1(0).bits.ownerClass.expect(OooUopClass.Alu)
      dut.io.e1(1).bits.ownerClass.expect(OooUopClass.Bru)
      dut.io.e1(0).bits.i2.row.member.group.ridSlot.expect(2.U)
      dut.io.e1(1).bits.i2.row.member.memberIndex.expect(1.U)
      dut.io.residentEntries(OooDispatchClass.Alu - 1)(0).expect(0.U)
      dut.io.residentEntries(OooDispatchClass.Bru - 1)(0).expect(0.U)
      dut.io.inFlightEntries(OooDispatchClass.Alu - 1)(0).expect(0.U)
      dut.io.inFlightEntries(OooDispatchClass.Bru - 1)(0).expect(0.U)
      dut.io.empty.expect(false.B)

      dut.io.e1(0).ready.poke(true.B)
      dut.io.e1(1).ready.poke(true.B)
      dut.clock.step()
      dut.io.empty.expect(true.B)
    }
  }
}
