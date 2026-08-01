package linxcore.iex

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.common.{DestinationKind, OperandClass}
import linxcore.ooo._
import linxcore.params.ParamProfiles
import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

private object IEXProductionMechanismSpec {
  final case class IssueAllocation(
      uopClass: Int,
      bank: Int,
      entry: Int,
      capabilities: BigInt)
}

/** Aggregated evidence for the production IEX children before the public-box
  * cutover. This suite intentionally instantiates no public `IEX` owner.
  */
class IEXProductionMechanismSpec extends AnyFunSuite with ChiselSim {
  import IEXProductionMechanismSpec._

  private val mechanismParams = OooParams(
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
    iexIssueDomainCount = 2,
    iexPReadPorts = 3,
    iexTReadPorts = 2,
    iexUReadPorts = 2,
    tuRetireSourceDepthPerStid = 16)

  private def pokeMember(
      target: RobMemberKey,
      stid: Int,
      ridSlot: Int,
      memberIndex: Int = 0): Unit = {
    target.poke(0.U.asTypeOf(target))
    target.group.valid.poke(true.B)
    target.group.peId.poke(3.U)
    target.group.stid.poke(stid.U)
    target.group.ridSlot.poke(ridSlot.U)
    target.group.ridGeneration.poke(1.U)
    target.bid.valid.poke(true.B)
    target.bid.value.poke(5.U)
    target.brobGeneration.poke(2.U)
    target.memberIndex.poke(memberIndex.U)
    target.residentGeneration.poke(4.U)
  }

  private def pokeClass(target: OooUopClass.Type, value: Int): Unit =
    value match {
      case 0 => target.poke(OooUopClass.Alu)
      case 1 => target.poke(OooUopClass.Bru)
      case 2 => target.poke(OooUopClass.Agu)
      case 3 => target.poke(OooUopClass.Std)
      case 4 => target.poke(OooUopClass.Fsu)
      case 5 => target.poke(OooUopClass.Sys)
      case 6 => target.poke(OooUopClass.Cmd)
      case 7 => target.poke(OooUopClass.Boundary)
    }

  private def clearIssue(dut: OooIexIssue): Unit = {
    dut.io.s1.valid.poke(false.B)
    dut.io.s1.bits.poke(0.U.asTypeOf(dut.io.s1.bits))
    dut.io.wakeup.foreach(_.poke(0.U.asTypeOf(dut.io.wakeup.head)))
    dut.io.loadCancel.foreach(
      _.poke(0.U.asTypeOf(dut.io.loadCancel.head)))
    dut.io.releases.foreach { release =>
      release.valid.poke(false.B)
      release.bits.poke(0.U.asTypeOf(release.bits))
    }
    dut.io.dispatchReleases.foreach(_.ready.poke(false.B))
    dut.io.queries.foreach(_.poke(0.U.asTypeOf(dut.io.queries.head)))
    dut.io.pickBankEnables.flatten.foreach(_.poke(0.U))
    dut.io.issuePolicy.poke(0.U.asTypeOf(dut.io.issuePolicy))
    dut.io.picks.foreach(_.ready.poke(false.B))
    dut.io.pickRetries.foreach(
      _.poke(0.U.asTypeOf(dut.io.pickRetries.head)))
    dut.io.recoveryPrepare.valid.poke(false.B)
    dut.io.recoveryPrepare.bits.poke(
      0.U.asTypeOf(dut.io.recoveryPrepare.bits))
    dut.io.recoveryFire.poke(false.B)
    dut.io.ptagRecycle.valid.poke(false.B)
    dut.io.ptagRecycle.bits.poke(0.U.asTypeOf(dut.io.ptagRecycle.bits))
  }

  private def pokeIssueTransaction(
      dut: OooIexIssue,
      stid: Int,
      transactionId: Int,
      ridSlot: Int,
      allocation: IssueAllocation): Unit = {
    val request = dut.io.s1.bits
    request.poke(0.U.asTypeOf(request))
    val transaction = request.o3.request.reservation.transaction
    transaction.plan.peId.poke(3.U)
    transaction.plan.stid.poke(stid.U)
    transaction.plan.epoch.poke(6.U)
    transaction.plan.transactionId.poke(transactionId.U)
    transaction.decoded.peId.poke(3.U)
    transaction.decoded.stid.poke(stid.U)
    transaction.decoded.epoch.poke(6.U)
    transaction.decoded.uopMask.poke(1.U)

    request.pRename.valid.poke(true.B)
    request.pRename.peId.poke(3.U)
    request.pRename.stid.poke(stid.U)
    request.pRename.epoch.poke(6.U)
    request.pRename.transactionId.poke(transactionId.U)
    request.pRename.uopMask.poke(1.U)
    request.tuRename.valid.poke(true.B)
    request.tuRename.peId.poke(3.U)
    request.tuRename.stid.poke(stid.U)
    request.tuRename.epoch.poke(6.U)
    request.tuRename.transactionId.poke(transactionId.U)
    request.tuRename.uopMask.poke(1.U)
    request.dispatch.valid.poke(true.B)
    request.dispatch.peId.poke(3.U)
    request.dispatch.stid.poke(stid.U)
    request.dispatch.epoch.poke(6.U)
    request.dispatch.transactionId.poke(transactionId.U)
    request.dispatch.allocationMask.poke(1.U)

    val decoded = transaction.decoded.uops(0)
    val pUop = request.pRename.uops(0)
    val tuUop = request.tuRename.uops(0)
    decoded.valid.poke(true.B)
    decoded.opcode.poke(40.U)
    decoded.plannedChildCount.poke(1.U)
    pUop.valid.poke(true.B)
    pUop.decoded.valid.poke(true.B)
    pUop.decoded.opcode.poke(40.U)
    pUop.decoded.plannedChildCount.poke(1.U)
    tuUop.valid.poke(true.B)
    pokeMember(pUop.member, stid, ridSlot)
    pokeMember(tuUop.member, stid, ridSlot)

    Seq(decoded, pUop.decoded).foreach { logical =>
      logical.identity.key.primaryParent.valid.poke(true.B)
      logical.identity.key.primaryParent.peId.poke(3.U)
      logical.identity.key.primaryParent.stid.poke(stid.U)
      logical.identity.key.primaryParent.instructionId.poke(transactionId.U)
      logical.identity.key.primaryParent.epoch.poke(6.U)
      logical.identity.parentCount.poke(1.U)
      logical.recipe.valid.poke(true.B)
      logical.recipe.opcode.poke(40.U)
      logical.recipe.disposition.poke(OooOpcodeDisposition.Dispatch.U)
      logical.recipe.dispatchDemand(allocation.uopClass).poke(1.U)
      logical.recipe.dispatchCapabilities(allocation.uopClass)
        .poke(allocation.capabilities.U)
      val parent = logical.identity.parents(0)
      parent.key.valid.poke(true.B)
      parent.key.peId.poke(3.U)
      parent.key.stid.poke(stid.U)
      parent.key.instructionId.poke(transactionId.U)
      parent.key.epoch.poke(6.U)
    }
    val pcToken = request.o3.parentPcTokens(0)(0)
    pcToken.valid.poke(true.B)
    pcToken.index.poke(stid.U)
    pcToken.allocationEpoch.poke(2.U)
    Seq(decoded.destinations(0), pUop.decoded.destinations(0)).foreach {
      destination =>
        destination.valid.poke(true.B)
        destination.kind.poke(DestinationKind.Gpr)
        destination.atag.poke(3.U)
    }
    pUop.destinations(0).currentPMapping.valid.poke(true.B)
    pUop.destinations(0).currentPMapping.ptag.poke((30 + ridSlot).U)
    pUop.destinations(0).currentPMapping.ptagGeneration.poke(4.U)
    pUop.destinations(0).previousPMapping.valid.poke(true.B)
    pUop.destinations(0).previousPMapping.ptag.poke((20 + ridSlot).U)
    pUop.destinations(0).previousPMapping.ptagGeneration.poke(3.U)

    val dispatch = request.dispatch.allocations(0)
    dispatch.valid.poke(true.B)
    dispatch.uopIndex.poke(0.U)
    dispatch.childIndex.poke(0.U)
    dispatch.reservation.valid.poke(true.B)
    pokeClass(dispatch.reservation.uopClass, allocation.uopClass)
    dispatch.reservation.bank.poke(allocation.bank.U)
    dispatch.reservation.writePort.poke(0.U)
    dispatch.reservation.speculativeSlot.poke(allocation.entry.U)
    dispatch.reservation.reservationEpoch.poke(1.U)
    dut.io.s1.valid.poke(true.B)
  }

  private def advanceIssueToS3(dut: OooIexIssue): Unit = {
    dut.io.s1.ready.expect(true.B)
    dut.clock.step()
    dut.io.s1.valid.poke(false.B)
    dut.io.s2Bind.valid.expect(true.B)
    dut.clock.step()
    dut.io.s3Enable.valid.expect(true.B)
    dut.clock.step()
  }
  test("W2 W4 W6 W8 derive the requested private topology from canonical parameters") {
    Seq(2, 4, 6, 8).foreach { width =>
      val profile = OooIexProductionPhysicalProfile(
        ParamProfiles.forWidth(width))

      assert(profile.aluCount == 2)
      assert(profile.bruCount == 1)
      assert(profile.aguCount == 2)
      assert(profile.stdCount == 2)
      assert(profile.systemMulticycleCount == 1)
      assert(profile.commandCount == 1)
      assert(profile.physical.params.instructionDecodeWidth == width)
      assert(profile.physical.params.dispatchWidth == width)
      assert(profile.physical.params.iqBankCount == 2)
      assert(profile.physical.params.iqEntriesPerBank == 32)
      assert(profile.physical.params.iqBankCount *
        profile.physical.params.iqEntriesPerBank == 64)
      assert(profile.physical.params.iexPReadPorts == 6)
      assert(profile.physical.params.iexPWritePorts == 5)
      assert(profile.physical.params.iexIssueDomainCount == 10)
      assert(profile.physical.pickerFunctions.length == 10)
      val aluResidency = profile.physical.ownersOf(OooDispatchClass.Alu - 1)
      assert(aluResidency.count(
        _.hasCapability(OooIexDomainCapability.SimpleAlu)) == 2)
      assert(aluResidency.count(
        _.hasCapability(OooIexDomainCapability.MultiCycleAlu)) == 1)
      assert(profile.physical.pickerFunctions.count(
        _.hasCapability(OooIexDomainCapability.SimpleAlu)) == 2)
      assert(profile.physical.pickerFunctions.count(
        _.hasCapability(OooIexDomainCapability.StoreData)) == 2)
      assert(profile.physical.ownersOf(OooDispatchClass.Bru - 1).size == 1)
      assert(profile.physical.ownersOf(OooDispatchClass.Agu - 1).size == 2)
      assert(profile.physical.ownersOf(OooDispatchClass.Std - 1).size == 2)
      assert(profile.physical.ownersOf(OooDispatchClass.Sys - 1).size == 1)
      assert(profile.physical.ownersOf(OooDispatchClass.Sys - 1).forall(
        _.hasCapability(OooIexDomainCapability.MultiCycleAlu)))
      assert(profile.physical.ownersOf(OooDispatchClass.Cmd - 1).size == 1)
      assert(profile.physical.ownersOf(OooDispatchClass.Std - 1).forall(
        _.hasCapability(OooIexDomainCapability.StoreData)))
    }
  }

  test("the W4 private profile elaborates the real execution child topology") {
    val profile = OooIexProductionPhysicalProfile(ParamProfiles.W4)
    val systemVerilog = ChiselStage.emitSystemVerilog(
      new OooIexExecutionCluster(profile.physical),
      firtoolOpts = Array("--disable-all-randomization"))

    assert(systemVerilog.contains("module OooIexExecutionCluster"))
    assert("OooIexAluPipeline alus_".r
      .findAllMatchIn(systemVerilog).length == 2)
    assert("OooIexBruPipeline brus_".r
      .findAllMatchIn(systemVerilog).length == 1)
    assert("OooIexAguPipeline agus_".r
      .findAllMatchIn(systemVerilog).length == 2)

    val compactParams = profile.physical.params.copy(
      stidCount = 2,
      robGroupsPerStid = 8,
      robBankCount = 8,
      robRecoveryScanGroupsPerCycle = 8,
      robNonFlushScanGroupsPerCycle = 8,
      pcBufferEntries = 32,
      pMapQDepthPerStid = 16,
      tuMapQDepthPerStid = 8,
      tuRetireSourceDepthPerStid = 32,
      iqEntriesPerBank = 4,
      iqFreeSelectLeafEntries = 2)
    val compactProfile = profile.physical.copy(params = compactParams)
    val pipelineChirrtl = ChiselStage.emitCHIRRTL(
      new OooIexExecutionPipeline(compactProfile))
    assert(pipelineChirrtl.contains("module OooIexExecutionPipeline"))
    assert(pipelineChirrtl.contains("inst issue of OooIexPipeline"))
    assert(pipelineChirrtl.contains("inst execute of OooIexExecutionCluster"))
  }

  test("real issue state exercises every canonical capability through retained claim and exact retry") {
    val canonical = OooIexProductionPhysicalProfile(ParamProfiles.W4).physical
    val p = canonical.params.copy(
      stidCount = 2,
      robGroupsPerStid = 8,
      robBankCount = 8,
      robRecoveryScanGroupsPerCycle = 8,
      robNonFlushScanGroupsPerCycle = 8,
      pMapQDepthPerStid = 16,
      tuMapQDepthPerStid = 8,
      tuRetireSourceDepthPerStid = 32,
      iqEntriesPerBank = 4,
      iqFreeSelectLeafEntries = 2,
      iexIssueDomainCount = 1,
      iexReleaseWidth = 1)
    val pickerCapabilityUnion = canonical.pickerFunctions
      .foldLeft(BigInt(0))(_ | _.capabilities)
    val residencyCapabilityUnion = canonical.residencyOwners
      .foldLeft(BigInt(0))(_ | _.capabilities)
    assert(pickerCapabilityUnion == OooIexDomainCapability.ValidMask)
    assert(residencyCapabilityUnion == OooIexDomainCapability.ValidMask)
    assert(pickerCapabilityUnion == residencyCapabilityUnion)
    val behaviorCapabilities = pickerCapabilityUnion
    simulate(new OooIexIssue(p, Seq(behaviorCapabilities))) { dut =>
      clearIssue(dut)
      val allBanks = (BigInt(1) << p.iqBankCount) - 1
      dut.io.pickBankEnables(0).foreach(_.poke(allBanks.U))
      val rows = Seq(
        (0, 11, 0, IssueAllocation(OooDispatchClass.Alu - 1, 0, 0,
          OooIexDomainCapability.mask(OooIexDomainCapability.SimpleAlu))),
        (0, 12, 1, IssueAllocation(OooDispatchClass.Alu - 1, 0, 1,
          OooIexDomainCapability.mask(OooIexDomainCapability.MultiCycleAlu))),
        (0, 13, 2, IssueAllocation(OooDispatchClass.Bru - 1, 0, 0,
          OooIexDomainCapability.mask(OooIexDomainCapability.Branch))),
        (0, 14, 3, IssueAllocation(OooDispatchClass.Agu - 1, 0, 0,
          OooIexDomainCapability.mask(OooIexDomainCapability.LoadAddress))),
        (1, 15, 0, IssueAllocation(OooDispatchClass.Agu - 1, 0, 1,
          OooIexDomainCapability.mask(OooIexDomainCapability.StoreAddress))),
        (1, 16, 1, IssueAllocation(OooDispatchClass.Std - 1, 0, 0,
          OooIexDomainCapability.mask(OooIexDomainCapability.StoreData))),
        (1, 17, 2, IssueAllocation(OooDispatchClass.Fsu - 1, 0, 0,
          OooIexDomainCapability.mask(OooIexDomainCapability.FloatingVector))),
        (1, 18, 3, IssueAllocation(OooDispatchClass.Sys - 1, 0, 0,
          OooIexDomainCapability.mask(OooIexDomainCapability.System))),
        (0, 19, 4, IssueAllocation(OooDispatchClass.Sys - 1, 0, 1,
          OooIexDomainCapability.mask(OooIexDomainCapability.PointerAuth))),
        (1, 20, 4, IssueAllocation(OooDispatchClass.Cmd - 1, 0, 0,
          OooIexDomainCapability.mask(OooIexDomainCapability.EngineCommand))))
      rows.foreach { case (stid, transactionId, ridSlot, allocation) =>
        pokeIssueTransaction(dut, stid, transactionId, ridSlot, allocation)
        advanceIssueToS3(dut)
        dut.io.residentEntries(allocation.uopClass)(allocation.bank).expect(1.U)

        dut.clock.step()
        for (_ <- 0 until 3) {
          dut.io.pick.valid.expect(true.B)
          dut.io.pick.bits.candidate.member.group.stid.expect(stid.U)
          dut.io.pick.bits.candidate.member.group.ridSlot.expect(ridSlot.U)
          dut.io.pick.ready.expect(false.B)
          dut.clock.step()
        }
        dut.io.pick.ready.poke(true.B)
        dut.clock.step()
        dut.io.pick.ready.poke(false.B)
        dut.io.inFlightEntries(allocation.uopClass)(allocation.bank).expect(1.U)

        val retry = dut.io.pickRetry.bits
        retry.poke(0.U.asTypeOf(retry))
        pokeMember(retry.member, stid = stid, ridSlot = ridSlot)
        retry.reservation.valid.poke(true.B)
        pokeClass(retry.reservation.uopClass, allocation.uopClass)
        retry.reservation.bank.poke(allocation.bank.U)
        retry.reservation.writePort.poke(0.U)
        retry.reservation.speculativeSlot.poke(allocation.entry.U)
        retry.reservation.reservationEpoch.poke(1.U)
        dut.io.pickRetry.valid.poke(true.B)
        dut.io.pickRetryRejected.valid.expect(false.B)
        dut.clock.step()
        dut.io.pickRetry.valid.poke(false.B)
        dut.io.inFlightEntries(allocation.uopClass)(allocation.bank).expect(0.U)

        dut.clock.step()
        dut.io.pick.valid.expect(true.B)
        dut.io.pick.bits.candidate.member.group.stid.expect(stid.U)
        dut.io.pick.bits.candidate.member.group.ridSlot.expect(ridSlot.U)
        dut.io.pick.ready.poke(true.B)
        dut.clock.step()
        dut.io.pick.ready.poke(false.B)

        val release = dut.io.release.bits
        release.poke(0.U.asTypeOf(release))
        pokeMember(release.member, stid = stid, ridSlot = ridSlot)
        pokeMember(release.dispatch.member, stid = stid, ridSlot = ridSlot)
        release.dispatch.peId.poke(3.U)
        release.dispatch.stid.poke(stid.U)
        release.dispatch.epoch.poke(6.U)
        release.dispatch.transactionId.poke(transactionId.U)
        release.dispatch.reservation.valid.poke(true.B)
        pokeClass(release.dispatch.reservation.uopClass, allocation.uopClass)
        release.dispatch.reservation.bank.poke(allocation.bank.U)
        release.dispatch.reservation.writePort.poke(0.U)
        release.dispatch.reservation.speculativeSlot.poke(allocation.entry.U)
        release.dispatch.reservation.reservationEpoch.poke(1.U)
        dut.io.dispatchRelease.ready.poke(true.B)
        dut.io.release.valid.poke(true.B)
        dut.io.release.ready.expect(true.B)
        dut.clock.step()
        dut.io.release.valid.poke(false.B)
        dut.io.dispatchRelease.ready.poke(false.B)
        dut.io.residentEntries(allocation.uopClass)(allocation.bank).expect(0.U)
      }
    }
  }

  test("oldest-ready selection preserves same-STID age and peer fairness under scoped recovery") {
    val p = mechanismParams
    val aluClass = OooDispatchClass.Alu - 1
    simulate(new OooIexOldestReadyPicker(p)) { dut =>
      dut.io.classBankEnables.foreach(_.poke(0.U))
      dut.io.classBankEnables(aluClass).poke("b11".U)
      dut.io.stidBlock.poke(0.U)
      dut.io.candidates.foreach(_.foreach(_.foreach(
        _.poke(0.U.asTypeOf(dut.io.candidates.head.head.head)))))
      dut.io.pick.ready.poke(false.B)
      dut.io.recoveryApply.valid.poke(false.B)
      dut.io.recoveryApply.bits.poke(0.U.asTypeOf(dut.io.recoveryApply.bits))

      def candidate(bank: Int, entry: Int, stid: Int, ridSlot: Int): Unit = {
        val row = dut.io.candidates(aluClass)(bank)(entry)
        row.poke(0.U.asTypeOf(row))
        row.eligible.poke(true.B)
        row.peId.poke(3.U)
        row.stid.poke(stid.U)
        row.epoch.poke(7.U)
        row.transactionId.poke((100 + ridSlot).U)
        pokeMember(row.member, stid, ridSlot)
        row.reservation.valid.poke(true.B)
        row.reservation.uopClass.poke(OooUopClass.Alu)
        row.reservation.bank.poke(bank.U)
        row.reservation.speculativeSlot.poke(entry.U)
        row.reservation.reservationEpoch.poke(9.U)
      }

      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      candidate(bank = 0, entry = 0, stid = 0, ridSlot = 1)
      candidate(bank = 1, entry = 0, stid = 0, ridSlot = 3)
      candidate(bank = 0, entry = 1, stid = 1, ridSlot = 2)
      dut.clock.step()
      dut.io.pick.bits.candidate.stid.expect(0.U)
      dut.io.pick.bits.query.bank.expect(0.U)
      dut.io.pick.bits.query.entry.expect(0.U)

      dut.io.pick.ready.poke(true.B)
      dut.clock.step()
      dut.io.candidates(aluClass)(0)(0).eligible.poke(false.B)
      dut.io.pick.bits.candidate.stid.expect(1.U)

      val recovery = dut.io.recoveryApply.bits
      recovery.poke(0.U.asTypeOf(recovery))
      recovery.valid.poke(true.B)
      recovery.oldHead.valid.poke(true.B)
      recovery.oldHead.peId.poke(3.U)
      recovery.oldHead.stid.poke(0.U)
      recovery.oldHead.ridSlot.poke(0.U)
      recovery.oldHead.ridGeneration.poke(1.U)
      recovery.oldOccupied.poke(4.U)
      recovery.newOccupied.poke(0.U)
      dut.io.recoveryApply.valid.poke(true.B)
      dut.io.pick.bits.candidate.stid.expect(1.U)
    }
  }

  test("atomic read admission grants one complete P T U group or no group") {
    val p = mechanismParams
    simulate(new OooIexAtomicReadArbiter(p)) { dut =>
      dut.io.attempts.foreach(_.poke(0.U.asTypeOf(dut.io.attempts.head)))
      dut.io.pReadResponses.foreach(
        _.poke(0.U.asTypeOf(dut.io.pReadResponses.head)))
      dut.io.tReadResponses.foreach(
        _.poke(0.U.asTypeOf(dut.io.tReadResponses.head)))
      dut.io.uReadResponses.foreach(
        _.poke(0.U.asTypeOf(dut.io.uReadResponses.head)))
      dut.io.pcReadResponses.foreach(
        _.poke(0.U.asTypeOf(dut.io.pcReadResponses.head)))

      val attempt = dut.io.attempts(0)
      attempt.valid.poke(true.B)
      attempt.bits.stid.poke(0.U)
      attempt.bits.epoch.poke(7.U)
      attempt.bits.transactionId.poke(31.U)
      pokeMember(attempt.bits.member, stid = 0, ridSlot = 2)
      attempt.bits.reservation.valid.poke(true.B)
      attempt.bits.reservation.uopClass.poke(OooUopClass.Alu)
      attempt.bits.reservation.bank.poke(0.U)
      attempt.bits.reservation.speculativeSlot.poke(1.U)
      attempt.bits.reservation.reservationEpoch.poke(9.U)
      attempt.bits.sourceMask.poke("b0111".U)
      Seq(OperandClass.P, OperandClass.T, OperandClass.U)
        .zipWithIndex.foreach { case (operandClass, index) =>
          val source = attempt.bits.sources(index)
          source.valid.poke(true.B)
          source.ready.poke(true.B)
          source.operandClass.poke(operandClass)
          source.ptag.poke((40 + index).U)
          source.ptagGeneration.poke(2.U)
          source.localTag.poke((index + 1).U)
          source.localSequence.valid.poke(true.B)
          source.localSequence.index.poke(index.U)
          source.localSequence.generation.poke((10 + index).U)
        }
      dut.io.pReadResponses(0).valid.poke(true.B)
      dut.io.pReadResponses(0).bits.poke(11.U)
      dut.io.tReadResponses(0).valid.poke(true.B)
      dut.io.tReadResponses(0).bits.poke(22.U)
      dut.io.uReadResponses(0).valid.poke(true.B)
      dut.io.uReadResponses(0).bits.poke(33.U)

      dut.io.selectedMask.expect(1.U)
      dut.io.grant(0).expect(true.B)
      dut.io.pReadRequests(0).valid.expect(true.B)
      dut.io.tReadRequests(0).valid.expect(true.B)
      dut.io.uReadRequests(0).valid.expect(true.B)
      dut.io.sourceDataValid(0).expect("b0111".U)
      dut.io.sourceData(0)(0).expect(11.U)
      dut.io.sourceData(0)(1).expect(22.U)
      dut.io.sourceData(0)(2).expect(33.U)

      dut.io.tReadResponses(0).valid.poke(false.B)
      dut.io.grant(0).expect(true.B)
      dut.io.sourceDataValid(0).expect("b0101".U)
    }
  }

  test("a denied or partial I1 read returns the exact row for retry") {
    val p = mechanismParams
    simulate(new OooIexP1I2Lane(p)) { dut =>
      dut.io.p1.valid.poke(false.B)
      dut.io.p1.bits.poke(0.U.asTypeOf(dut.io.p1.bits))
      dut.io.readDecisionValid.poke(false.B)
      dut.io.readGrant.poke(false.B)
      dut.io.sourceDataValid.poke(0.U)
      dut.io.sourceData.foreach(_.poke(0.U))
      dut.io.pcDataValid.poke(false.B)
      dut.io.pcData.poke(0.U)
      dut.io.bypass.foreach(
        _.poke(0.U.asTypeOf(dut.io.bypass.head)))
      dut.io.loadCancel.foreach(
        _.poke(0.U.asTypeOf(dut.io.loadCancel.head)))
      dut.io.stageCancel.foreach { cancel =>
        cancel.valid.poke(false.B)
        cancel.bits.poke(0.U.asTypeOf(cancel.bits))
      }
      dut.io.repick.ready.poke(true.B)
      dut.io.i2.ready.poke(false.B)
      dut.io.recoveryApply.valid.poke(false.B)
      dut.io.recoveryApply.bits.poke(
        0.U.asTypeOf(dut.io.recoveryApply.bits))

      val row = dut.io.p1.bits.row.schedule
      row.valid.poke(true.B)
      row.peId.poke(3.U)
      row.stid.poke(0.U)
      row.epoch.poke(7.U)
      row.transactionId.poke(44.U)
      pokeMember(row.member, stid = 0, ridSlot = 4)
      row.reservation.valid.poke(true.B)
      row.reservation.uopClass.poke(OooUopClass.Alu)
      row.reservation.bank.poke(0.U)
      row.reservation.speculativeSlot.poke(1.U)
      row.reservation.reservationEpoch.poke(9.U)
      row.sources(0).valid.poke(true.B)
      row.sources(0).ready.poke(true.B)
      row.sources(0).operandClass.poke(OperandClass.P)
      row.sources(1).valid.poke(true.B)
      row.sources(1).ready.poke(true.B)
      row.sources(1).operandClass.poke(OperandClass.T)
      dut.io.p1.bits.row.payload.uopKey.primaryParent.valid.poke(true.B)
      dut.io.p1.bits.row.payload.uopKey.primaryParent.peId.poke(3.U)
      dut.io.p1.bits.row.payload.uopKey.primaryParent.stid.poke(0.U)
      dut.io.p1.bits.row.payload.uopKey.primaryParent.instructionId.poke(9.U)

      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.p1.valid.poke(true.B)
      dut.clock.step()
      dut.io.p1.valid.poke(false.B)
      dut.io.readAttempt.valid.expect(true.B)
      dut.io.readDecisionValid.poke(true.B)
      dut.io.readGrant.poke(true.B)
      dut.io.sourceDataValid.poke("b0001".U)
      dut.io.repick.valid.expect(true.B)
      dut.io.repick.bits.member.group.ridSlot.expect(4.U)
      dut.io.repick.bits.reservation.speculativeSlot.expect(1.U)
      dut.io.i2.valid.expect(false.B)
    }
  }

  test("terminal publication retains its owner until P T U peers can fire atomically") {
    val p = mechanismParams
    simulate(new OooIexTerminalPublish(p)) { dut =>
      dut.io.alu.valid.poke(false.B)
      dut.io.alu.bits.poke(0.U.asTypeOf(dut.io.alu.bits))
      dut.io.bru.valid.poke(false.B)
      dut.io.bru.bits.poke(0.U.asTypeOf(dut.io.bru.bits))
      dut.io.load.valid.poke(false.B)
      dut.io.load.bits.poke(0.U.asTypeOf(dut.io.load.bits))
      dut.io.pWrite.foreach(_.ready.poke(true.B))
      dut.io.tWrite.foreach(_.ready.poke(true.B))
      dut.io.uWrite.foreach(_.ready.poke(true.B))
      dut.io.wakeup.foreach(_.ready.poke(true.B))
      dut.io.bctrl.ready.poke(true.B)
      dut.io.trace.ready.poke(true.B)
      dut.io.completion.ready.poke(true.B)

      val terminal = dut.io.alu.bits
      val execute = terminal.execute
      execute.ownerClass.poke(OooUopClass.Alu)
      val row = execute.i2.row.schedule
      row.valid.poke(true.B)
      row.peId.poke(3.U)
      row.stid.poke(1.U)
      row.epoch.poke(7.U)
      pokeMember(row.member, stid = 1, ridSlot = 2)
      row.reservation.valid.poke(true.B)
      row.reservation.uopClass.poke(OooUopClass.Alu)
      execute.i2.row.payload.opcode.poke(0x51.U)
      execute.i2.row.payload.recipe.valid.poke(true.B)
      execute.i2.row.payload.recipe.opcode.poke(0x51.U)
      execute.i2.row.payload.recipe.disposition.poke(
        OooOpcodeDisposition.Dispatch.U)
      execute.i2.row.payload.recipe.dispatchClass.poke(
        OooDispatchClass.Alu.U)
      execute.i2.row.payload.recipe.sideEffectOwner.poke(
        OooSideEffectOwner.Iex.U)
      execute.i2.row.payload.uopKey.primaryParent.valid.poke(true.B)
      execute.i2.row.payload.uopKey.primaryParent.peId.poke(3.U)
      execute.i2.row.payload.uopKey.primaryParent.stid.poke(1.U)
      execute.i2.row.payload.uopKey.primaryParent.instructionId.poke(9.U)
      execute.i2.row.payload.uopKey.uopCount.poke(1.U)

      def destination(target: OooIexDestinationState,
          kind: DestinationKind.Type, ordinal: Int): Unit = {
        target.valid.poke(true.B)
        target.kind.poke(kind)
        target.atag.poke((4 + ordinal).U)
        target.ptag.poke((30 + ordinal).U)
        target.ptagGeneration.poke(3.U)
        target.localTag.poke((6 + ordinal).U)
        target.localSequence.valid.poke(true.B)
        target.localSequence.index.poke((10 + ordinal).U)
        target.localSequence.generation.poke(2.U)
      }
      destination(row.destinations(0), DestinationKind.T, 0)
      destination(row.destinations(1), DestinationKind.U, 1)
      terminal.writebacks(0).valid.poke(true.B)
      destination(terminal.writebacks(0).destination, DestinationKind.T, 0)
      terminal.writebacks(0).data.poke(0x100.U)
      terminal.writebacks(1).valid.poke(true.B)
      destination(terminal.writebacks(1).destination, DestinationKind.U, 1)
      terminal.writebacks(1).data.poke(0x101.U)
      dut.io.alu.valid.poke(true.B)

      dut.io.uWrite(1).ready.poke(false.B)
      dut.io.alu.ready.expect(false.B)
      dut.io.tWrite(0).valid.expect(false.B)
      dut.io.uWrite(1).valid.expect(true.B)
      dut.io.terminalFire.expect(false.B)

      dut.io.uWrite(1).ready.poke(true.B)
      dut.io.alu.ready.expect(true.B)
      dut.io.tWrite(0).valid.expect(true.B)
      dut.io.uWrite(1).valid.expect(true.B)
      dut.io.wakeup(0).bits.operandClass.expect(OperandClass.T)
      dut.io.wakeup(1).bits.operandClass.expect(OperandClass.U)
      dut.io.terminalFire.expect(true.B)
    }
  }
}
