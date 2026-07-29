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
  val residentEntries = Output(Vec(p.iqClassCount,
    Vec(p.iqBankCount, UInt(p.iqBankEntryCountWidth.W))))
  val inFlightEntries = Output(Vec(p.iqClassCount,
    Vec(p.iqBankCount, UInt(p.iqBankEntryCountWidth.W))))
  val empty = Output(Bool())
}

class OooIexIssueE1IntegrationHarness(
    val profile: OooIexPhysicalProfile) extends Module {
  val p = profile.params
  val io = IO(new OooIexIssueE1IntegrationHarnessIO(p))

  val pipeline = Module(new OooIexPipeline(profile))

  pipeline.io.s1 <> io.s1
  pipeline.io.wakeup.foreach(_ := 0.U.asTypeOf(pipeline.io.wakeup.head))
  pipeline.io.loadCancel.foreach(
    _ := 0.U.asTypeOf(pipeline.io.loadCancel.head))
  pipeline.io.ptagRecycle.valid := false.B
  pipeline.io.ptagRecycle.bits :=
    0.U.asTypeOf(pipeline.io.ptagRecycle.bits)
  pipeline.io.recoveryPrepare.valid := false.B
  pipeline.io.recoveryPrepare.bits :=
    0.U.asTypeOf(pipeline.io.recoveryPrepare.bits)
  pipeline.io.recoveryFire := false.B
  pipeline.io.issuePolicy := 0.U.asTypeOf(pipeline.io.issuePolicy)
  pipeline.io.stageCancels.flatten.foreach { cancel =>
    cancel.valid := false.B
    cancel.bits := 0.U.asTypeOf(cancel.bits)
  }
  for (port <- 0 until p.pcReadPorts) {
    pipeline.io.pcReadResponses(port).valid :=
      pipeline.io.pcReadRequests(port).valid
    pipeline.io.pcReadResponses(port).bits := ("h80000100".U + port.U)
  }
  pipeline.io.bypass.foreach(_ := 0.U.asTypeOf(pipeline.io.bypass.head))
  pipeline.io.pInit := 0.U.asTypeOf(pipeline.io.pInit)
  pipeline.io.pClear.foreach(_ := 0.U.asTypeOf(pipeline.io.pClear.head))
  pipeline.io.pWrite.foreach(_ := 0.U.asTypeOf(pipeline.io.pWrite.head))
  pipeline.io.tClear.foreach(_ := 0.U.asTypeOf(pipeline.io.tClear.head))
  pipeline.io.uClear.foreach(_ := 0.U.asTypeOf(pipeline.io.uClear.head))
  pipeline.io.tWrite.foreach(_ := 0.U.asTypeOf(pipeline.io.tWrite.head))
  pipeline.io.uWrite.foreach(_ := 0.U.asTypeOf(pipeline.io.uWrite.head))

  for (domain <- 0 until p.iexIssueDomainCount) {
    io.e1(domain) <> pipeline.io.e1(domain)
  }
  io.dispatchReleases <> pipeline.io.dispatchReleases

  io.transferFire := pipeline.io.transferFireMask.orR
  io.transferFireMask := pipeline.io.transferFireMask
  io.residentEntries := pipeline.io.residentEntries
  io.inFlightEntries := pipeline.io.inFlightEntries
  io.empty := pipeline.io.empty
}

class OooIexIssueE1IntegrationSpec extends AnyFunSuite with ChiselSim {
  private val baseP = OooParams(
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
    iqBankCount = 8,
    iqEntriesPerBank = 2,
    iqWritePortsPerBank = 2,
    iqFreeSelectLeafEntries = 2,
    pMapQDepthPerStid = 4,
    tuMapQDepthPerStid = 4,
    tuRetireSourceDepthPerStid = 16)
  private val profile = OooIexLinxPhysicalProfile(baseP)
  private val p = profile.params

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
        val capability = dispatchClass match {
          case OooDispatchClass.Alu => OooIexDomainCapability.mask(
            OooIexDomainCapability.SimpleAlu)
          case OooDispatchClass.Bru => OooIexDomainCapability.mask(
            OooIexDomainCapability.Branch)
          case _ => OooIexDomainCapability.ValidMask
        }
        uop.recipe.dispatchCapabilities(dispatchClass - 1)
          .poke(capability.U)
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
      allocation.reservation.speculativeSlot.poke(index.U)
      allocation.reservation.reservationEpoch.poke((9 + index).U)
    }

    pokeUop(0, opcode = 1, OooDispatchClass.Alu,
      OooUopClass.Alu, pcRead = false)
    pokeUop(1, opcode = 73, OooDispatchClass.Bru,
      OooUopClass.Bru, pcRead = true)
    dut.io.s1.valid.poke(true.B)
  }

  test("canonical IQ release and dispatch return share the E1 transfer") {
    simulate(new OooIexIssueE1IntegrationHarness(profile)) { dut =>
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
      val aluRelease = profile.picker("alu0").releasePort
      val bruRelease = profile.picker("bru0").releasePort
      val aluLane = profile.pickerIndex("alu0")
      val bruLane = profile.pickerIndex("bru0")
      val expectedFireMask = (BigInt(1) << aluRelease) |
        (BigInt(1) << bruRelease)
      while (dut.io.transferFireMask.peek().litValue != expectedFireMask &&
          cycles < 12) {
        dut.clock.step()
        cycles += 1
      }
      dut.io.transferFireMask.expect(expectedFireMask.U)
      dut.io.dispatchReleases(aluRelease).valid.expect(true.B)
      dut.io.dispatchReleases(bruRelease).valid.expect(true.B)
      dut.io.dispatchReleases(aluRelease).bits.member.memberIndex.expect(0.U)
      dut.io.dispatchReleases(bruRelease).bits.member.memberIndex.expect(1.U)
      dut.clock.step()

      dut.io.e1(aluLane).valid.expect(true.B)
      dut.io.e1(bruLane).valid.expect(true.B)
      dut.io.e1(aluLane).bits.ownerClass.expect(OooUopClass.Alu)
      dut.io.e1(bruLane).bits.ownerClass.expect(OooUopClass.Bru)
      dut.io.e1(aluLane).bits.i2.row.member.group.ridSlot.expect(2.U)
      dut.io.e1(bruLane).bits.i2.row.member.memberIndex.expect(1.U)
      dut.io.residentEntries(OooDispatchClass.Alu - 1)(0).expect(0.U)
      dut.io.residentEntries(OooDispatchClass.Bru - 1)(0).expect(0.U)
      dut.io.inFlightEntries(OooDispatchClass.Alu - 1)(0).expect(0.U)
      dut.io.inFlightEntries(OooDispatchClass.Bru - 1)(0).expect(0.U)
      dut.io.empty.expect(false.B)

      dut.io.e1(aluLane).ready.poke(true.B)
      dut.io.e1(bruLane).ready.poke(true.B)
      dut.clock.step()
      dut.io.empty.expect(true.B)
    }
  }
}
