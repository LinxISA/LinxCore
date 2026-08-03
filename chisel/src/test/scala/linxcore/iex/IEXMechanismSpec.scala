package linxcore.iex

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import chisel3.util.{Decoupled, Valid}
import linxcore.ooo._
import linxcore.params.{CoreParams, ParamProfiles, SimulationParamProfiles}
import linxcore.top.interface.{D3RenameGroup, DispatchTxn,
  PcBufferD3Prepared, UopClass}
import org.scalatest.funsuite.AnyFunSuite

private class IEXPcReadHarnessIO(val core: CoreParams, val p: OooParams)
    extends Bundle {
  val prepare = Flipped(Valid(new D3RenameGroup(core)))
  val prepareReady = Output(Bool())
  val prepared = Output(new PcBufferD3Prepared(core))
  val publishFire = Input(Bool())
  val p1 = Flipped(Decoupled(new OooIexP1Request(p)))
  val i2 = Decoupled(new OooIexI2Transaction(p))
}

/** Real canonical PC owner, readyless read arbiter, and retained I2 lane. */
private class IEXPcReadHarness(val core: CoreParams) extends Module {
  val p = OooIexPhysicalProfile.fromCoreParams(core).params
  val io = IO(new IEXPcReadHarnessIO(core, p))
  val pc = Module(new OooPcBuffer(core))
  val reads = Module(new OooIexAtomicReadArbiter(core, p))
  val lane = Module(new OooIexP1I2Lane(core, p))

  pc.io.prepare := io.prepare
  io.prepareReady := pc.io.prepareReady
  io.prepared := pc.io.prepared
  pc.io.publicationIdentity.valid := io.publishFire
  pc.io.publicationIdentity.bits :=
    0.U.asTypeOf(pc.io.publicationIdentity.bits)
  pc.io.publicationIdentity.bits.count := pc.io.prepared.count
  for (laneIndex <- 0 until core.ooo.d3PrefixWidth) {
    pc.io.publicationIdentity.bits.entries(laneIndex).valid :=
      laneIndex.U < pc.io.prepared.count
    pc.io.publicationIdentity.bits.entries(laneIndex).rob :=
      io.prepare.bits.entries(laneIndex).uop.decoded.rob
  }
  pc.io.publishFire := io.publishFire
  pc.io.commitPreview.valid := false.B
  pc.io.commitPreview.bits := 0.U.asTypeOf(pc.io.commitPreview.bits)
  pc.io.commitApply := false.B
  pc.io.recovery.prepare.valid := false.B
  pc.io.recovery.prepare.bits :=
    0.U.asTypeOf(pc.io.recovery.prepare.bits)
  pc.io.recovery.prepared.ready := true.B
  pc.io.recovery.apply.valid := false.B
  pc.io.recovery.apply.bits := 0.U.asTypeOf(pc.io.recovery.apply.bits)
  pc.io.recovery.abort.valid := false.B
  pc.io.recovery.abort.bits := 0.U.asTypeOf(pc.io.recovery.abort.bits)

  lane.io.p1 <> io.p1
  reads.io.attempts.foreach(_ := 0.U.asTypeOf(reads.io.attempts.head))
  reads.io.attempts(0) := lane.io.readAttempt
  lane.io.readDecisionValid := reads.io.decisionValid(0)
  lane.io.readGrant := reads.io.grant(0)
  lane.io.sourceDataValid := reads.io.sourceDataValid(0)
  lane.io.sourceData := reads.io.sourceData(0)
  lane.io.pcDataValid := reads.io.pcDataValid(0)
  lane.io.pcData := reads.io.pcData(0)
  reads.io.pReadResponses.foreach { response =>
    response.valid := true.B
    response.bits := "h1122334455667788".U
  }
  reads.io.tReadResponses.foreach(_ := 0.U.asTypeOf(
    reads.io.tReadResponses.head))
  reads.io.uReadResponses.foreach(_ := 0.U.asTypeOf(
    reads.io.uReadResponses.head))
  for (port <- 0 until p.pcReadPorts) {
    pc.io.readAddress(port) := reads.io.pcReadRequests(port).bits
    reads.io.pcReadResponses(port) := pc.io.readPcBase(port)
  }
  lane.io.bypass.foreach(_ := 0.U.asTypeOf(lane.io.bypass.head))
  lane.io.loadCancel.foreach(_ := 0.U.asTypeOf(lane.io.loadCancel.head))
  lane.io.stageCancel.foreach { cancel =>
    cancel.valid := false.B
    cancel.bits := 0.U.asTypeOf(cancel.bits)
  }
  lane.io.recoveryApply.valid := false.B
  lane.io.recoveryApply.bits := 0.U.asTypeOf(lane.io.recoveryApply.bits)
  lane.io.repick.ready := true.B
  io.i2 <> lane.io.i2
}

/** Canonical neutral-name mechanism coverage for the private IEX cutover. */
class IEXMechanismSpec extends AnyFunSuite with ChiselSim {
  private def pokeAluDispatch(
      txn: DispatchTxn,
      transactionId: Int,
      ridSlot: Int,
      source: Boolean = false): Unit = {
    txn.poke(0.U.asTypeOf(txn))
    txn.transactionId.poke(transactionId.U)
    txn.uop.decoded.valid.poke(true.B)
    txn.uop.decoded.uopClass.poke(UopClass.Alu)
    txn.uop.decoded.opcode.poke(0x31.U)
    txn.uop.decoded.rob.peId.poke(3.U)
    txn.uop.decoded.rob.stid.poke(0.U)
    txn.uop.decoded.rob.ridSlot.poke(ridSlot.U)
    txn.uop.decoded.rob.ridGeneration.poke(1.U)
    txn.uop.decoded.rob.memberIndex.poke(0.U)
    txn.uop.decoded.rob.residentGeneration.poke(7.U)
    // Keep the identity legal when directed simulation narrows the local
    // BROB namespace together with its retained capacity.
    txn.uop.decoded.rob.bid.poke(ridSlot.U)
    txn.uop.decoded.rob.brobGeneration.poke(11.U)
    txn.uop.decoded.instruction.parent.identity.peId.poke(3.U)
    txn.uop.decoded.instruction.parent.identity.stid.poke(0.U)
    txn.uop.decoded.instruction.parent.identity.instructionId.poke(
      transactionId.U)
    txn.uop.decoded.instruction.parent.identity.epoch.poke(7.U)
    val classification = txn.uop.decoded.classification
    classification.valid.poke(true.B)
    classification.disposition.poke(OooOpcodeDisposition.Dispatch.U)
    classification.kind.poke(OooOpcodeRecipeKind.Single.U)
    classification.uopCountMin.poke(1.U)
    classification.uopCountMax.poke(1.U)
    classification.sideEffectOwner.poke(OooSideEffectOwner.Iex.U)
    classification.dispatchWrites.poke(1.U)
    classification.dispatchClass.poke(OooDispatchClass.Alu.U)
    classification.dispatchDemand(OooDispatchClass.Alu - 1).poke(1.U)
    classification.executionPipeCapability(OooDispatchClass.Alu - 1).poke(
      OooIexDomainCapability.mask(OooIexDomainCapability.SimpleAlu).U)
    if (source) {
      txn.uop.sources(0).valid.poke(true.B)
      txn.uop.sources(0).kind.poke(linxcore.top.interface.OperandKind.Gpr)
      txn.uop.sources(0).ptagValid.poke(true.B)
      txn.uop.sources(0).ptag.poke(17.U)
      txn.uop.sources(0).pGeneration.poke(9.U)
      txn.uop.sources(0).ready.poke(true.B)
    }
  }

  test("W2 W4 W6 W8 derive matching main and bounded issue topology") {
    Seq(2, 4, 6, 8).foreach { width =>
      val core = ParamProfiles.forWidth(width)
      val profile = OooIexPhysicalProfile.fromCoreParams(core)
      val simulationCore = SimulationParamProfiles.forWidth(width)
      val simulationProfile =
        OooIexPhysicalProfile.fromCoreParams(simulationCore)
      assert(profile.params.dispatchWidth == width)
      assert(profile.params.iexIssueDomainCount ==
        profile.pickerFunctions.length)
      assert(profile.transferConfigs.length == profile.pickerFunctions.length)
      assert(profile.pickerFunctions.count(
        _.hasCapability(OooIexDomainCapability.SimpleAlu)) ==
        core.iex.aluPipes)
      assert(profile.pickerFunctions.count(
        _.hasCapability(OooIexDomainCapability.Branch)) ==
        core.iex.bruPipes)
      assert(profile.pickerFunctions.count(
        _.hasCapability(OooIexDomainCapability.LoadAddress)) ==
        core.iex.aguPipes)
      assert(simulationProfile.pickerFunctions == profile.pickerFunctions)
      assert(simulationProfile.transferConfigs == profile.transferConfigs)
    }
  }

  test("shared-resource ownership denies a peer and rotates after acceptance") {
    val p = OooParams(iexIssueDomainCount = 2)
    val capability = OooIexDomainCapability.MultiCycleAlu
    val resources = Seq(OooIexSharedResourceConfig(
      "shared-mul", capability, Seq(0, 1)))
    simulate(new OooIexSharedResourceArbiter(p, resources)) { dut =>
      val mask = OooIexDomainCapability.mask(capability)
      dut.io.requestValid.poke(3.U)
      dut.io.capabilities.foreach(_.poke(mask.U))
      dut.io.accepted.poke(0.U)
      dut.io.eligible.expect(1.U)
      dut.io.conflicted.expect(2.U)
      dut.io.accepted.poke(1.U)
      dut.clock.step()
      dut.io.accepted.poke(0.U)
      dut.io.eligible.expect(2.U)
      dut.io.conflicted.expect(1.U)
    }
  }

  test("P1 fabric claims parallel ALU domains and isolates a denied peer") {
    val core = SimulationParamProfiles.W4
    val profile = OooIexPhysicalProfile.fromCoreParams(core)
    val p = profile.params
    val aguClass = OooDispatchClass.Agu - 1
    val aguOwners = profile.transferConfigs.filter(
      _.classBankEnables(aguClass) != 0)
    val aguCapabilityMask = OooIexDomainCapability.mask(
      OooIexDomainCapability.LoadAddress) |
      OooIexDomainCapability.mask(OooIexDomainCapability.StoreAddress)
    assert(aguOwners.nonEmpty)
    assert(aguOwners.forall(owner =>
      (owner.capabilities & ~aguCapabilityMask) == 0))
    assert(aguOwners.combinations(2).exists { pair =>
      (pair.head.classBankEnables(aguClass) &
        pair.last.classBankEnables(aguClass)) != 0 &&
        (pair.head.capabilities & pair.last.capabilities) == 0
    })
    simulate(new OooIexIssueP1Fabric(core)) { dut =>
      dut.io.dispatch.aluDispatch.foreach { channel =>
        channel.valid.poke(false.B)
        channel.bits.poke(0.U.asTypeOf(channel.bits))
      }
      dut.io.dispatch.bruDispatch.foreach { channel =>
        channel.valid.poke(false.B)
        channel.bits.poke(0.U.asTypeOf(channel.bits))
      }
      dut.io.dispatch.aguDispatch.foreach { channel =>
        channel.valid.poke(false.B)
        channel.bits.poke(0.U.asTypeOf(channel.bits))
      }
      dut.io.dispatch.storeDispatch.foreach { channel =>
        channel.valid.poke(false.B)
        channel.bits.poke(0.U.asTypeOf(channel.bits))
      }
      dut.io.dispatch.systemDispatch.foreach { channel =>
        channel.valid.poke(false.B)
        channel.bits.poke(0.U.asTypeOf(channel.bits))
      }
      dut.io.dispatch.cmdDispatch.foreach { channel =>
        channel.valid.poke(false.B)
        channel.bits.poke(0.U.asTypeOf(channel.bits))
      }
      dut.io.wakeup.foreach(_.poke(0.U.asTypeOf(dut.io.wakeup.head)))
      dut.io.loadCancel.foreach(_.poke(0.U.asTypeOf(dut.io.loadCancel.head)))
      dut.io.operandReadyBits.poke(0.U.asTypeOf(dut.io.operandReadyBits))
      dut.io.releases.foreach { release =>
        release.valid.poke(false.B)
        release.bits.poke(0.U.asTypeOf(release.bits))
      }
      dut.io.recovery.prepare.valid.poke(false.B)
      dut.io.recovery.prepare.bits.poke(
        0.U.asTypeOf(dut.io.recovery.prepare.bits))
      dut.io.recovery.prepared.ready.poke(true.B)
      dut.io.recovery.apply.valid.poke(false.B)
      dut.io.recovery.apply.bits.poke(
        0.U.asTypeOf(dut.io.recovery.apply.bits))
      dut.io.recovery.abort.valid.poke(false.B)
      dut.io.recovery.abort.bits.poke(
        0.U.asTypeOf(dut.io.recovery.abort.bits))
      for (domain <- 0 until p.iexIssueDomainCount) {
        dut.io.pickBankEnables(domain).zip(
          profile.transferConfigs(domain).classBankEnables).foreach {
          case (target, mask) => target.poke(mask.U)
        }
        dut.io.readDecisionValid(domain).poke(false.B)
        dut.io.readGrant(domain).poke(false.B)
        dut.io.sourceDataValid(domain).poke(0.U)
        dut.io.sourceData(domain).foreach(_.poke(0.U))
        dut.io.pcDataValid(domain).poke(false.B)
        dut.io.pcData(domain).poke(0.U)
        dut.io.i2(domain).ready.poke(false.B)
      }
      dut.io.issuePolicy.poke(0.U.asTypeOf(dut.io.issuePolicy))
      dut.io.stageCancels.flatten.foreach { cancel =>
        cancel.valid.poke(false.B)
        cancel.bits.poke(0.U.asTypeOf(cancel.bits))
      }
      dut.io.bypass.foreach(_.poke(0.U.asTypeOf(dut.io.bypass.head)))
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      pokeAluDispatch(dut.io.dispatch.aluDispatch(0).bits, 40, 1)
      pokeAluDispatch(dut.io.dispatch.aluDispatch(1).bits, 41, 2)
      dut.io.dispatch.aluDispatch(0).valid.poke(true.B)
      dut.io.dispatch.aluDispatch(1).valid.poke(true.B)
      dut.io.dispatch.aluDispatch(0).ready.expect(true.B)
      dut.io.dispatch.aluDispatch(1).ready.expect(true.B)
      dut.clock.step()
      dut.io.dispatch.aluDispatch.foreach(_.valid.poke(false.B))
      dut.clock.step(3)

      val contenders = dut.io.readAttempts.zipWithIndex.filter {
        case (attempt, _) => attempt.valid.peek().litToBoolean
      }
      assert(contenders.length == 2)
      val winner = contenders.head._2
      val loser = contenders.last._2
      dut.io.readDecisionValid(winner).poke(true.B)
      dut.io.readGrant(winner).poke(true.B)
      dut.io.readDecisionValid(loser).poke(true.B)
      dut.io.readGrant(loser).poke(false.B)
      dut.io.retryFeedback(loser).valid.expect(true.B)
      dut.clock.step()
      dut.io.i2(winner).valid.expect(true.B)
      dut.io.i2(loser).valid.expect(false.B)
    }
  }

  test("composed operand files return missing data as an exact IQ repick") {
    val core = SimulationParamProfiles.W2
    val profile = OooIexPhysicalProfile.fromCoreParams(core)
    val p = profile.params
    simulate(new OooIexIssueReadFabric(core)) { dut =>
      dut.io.dispatch.aluDispatch.foreach { channel =>
        channel.valid.poke(false.B)
        channel.bits.poke(0.U.asTypeOf(channel.bits))
      }
      Seq(dut.io.dispatch.bruDispatch, dut.io.dispatch.aguDispatch,
        dut.io.dispatch.systemDispatch, dut.io.dispatch.cmdDispatch)
        .foreach(_.foreach { channel =>
          channel.valid.poke(false.B)
          channel.bits.poke(0.U.asTypeOf(channel.bits))
        })
      dut.io.dispatch.storeDispatch.foreach { channel =>
        channel.valid.poke(false.B)
        channel.bits.poke(0.U.asTypeOf(channel.bits))
      }
      dut.io.wakeup.foreach(_.poke(0.U.asTypeOf(dut.io.wakeup.head)))
      dut.io.loadCancel.foreach(_.poke(0.U.asTypeOf(dut.io.loadCancel.head)))
      dut.io.releases.foreach { release =>
        release.valid.poke(false.B)
        release.bits.poke(0.U.asTypeOf(release.bits))
      }
      dut.io.recovery.prepare.valid.poke(false.B)
      dut.io.recovery.prepare.bits.poke(
        0.U.asTypeOf(dut.io.recovery.prepare.bits))
      dut.io.recovery.prepared.ready.poke(true.B)
      dut.io.recovery.apply.valid.poke(false.B)
      dut.io.recovery.apply.bits.poke(
        0.U.asTypeOf(dut.io.recovery.apply.bits))
      dut.io.recovery.abort.valid.poke(false.B)
      dut.io.recovery.abort.bits.poke(
        0.U.asTypeOf(dut.io.recovery.abort.bits))
      dut.io.issuePolicy.poke(0.U.asTypeOf(dut.io.issuePolicy))
      dut.io.stageCancels.flatten.foreach { cancel =>
        cancel.valid.poke(false.B)
        cancel.bits.poke(0.U.asTypeOf(cancel.bits))
      }
      dut.io.pcReadResponses.foreach(
        _.poke(0.U.asTypeOf(dut.io.pcReadResponses.head)))
      dut.io.bypass.foreach(_.poke(0.U.asTypeOf(dut.io.bypass.head)))
      dut.io.pInit.poke(0.U.asTypeOf(dut.io.pInit))
      dut.io.pClear.foreach(_.poke(0.U.asTypeOf(dut.io.pClear.head)))
      dut.io.pWrite.foreach(_.poke(0.U.asTypeOf(dut.io.pWrite.head)))
      dut.io.tClear.foreach(_.poke(0.U.asTypeOf(dut.io.tClear.head)))
      dut.io.uClear.foreach(_.poke(0.U.asTypeOf(dut.io.uClear.head)))
      dut.io.tWrite.foreach(_.poke(0.U.asTypeOf(dut.io.tWrite.head)))
      dut.io.uWrite.foreach(_.poke(0.U.asTypeOf(dut.io.uWrite.head)))
      dut.io.i2.foreach(_.ready.poke(false.B))
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      // Admit the source while its exact physical-register owner is ready,
      // then clear that owner before the retained I1 read. This exercises the
      // intended ready-at-admission/data-missing-at-read retry path.
      dut.io.pInit.valid.poke(true.B)
      dut.io.pInit.bits.key.stid.poke(0.U)
      dut.io.pInit.bits.key.epoch.poke(7.U)
      dut.io.pInit.bits.key.ptag.poke(17.U)
      dut.io.pInit.bits.key.generation.poke(9.U)
      dut.io.pInit.bits.data.poke("h1122334455667788".U)
      dut.clock.step()
      dut.io.pInit.valid.poke(false.B)

      val channel = dut.io.dispatch.aluDispatch.head
      pokeAluDispatch(channel.bits, 70, 1, source = true)
      channel.valid.poke(true.B)
      channel.ready.expect(true.B)
      dut.clock.step()
      channel.valid.poke(false.B)
      dut.io.pClear(0).valid.poke(true.B)
      dut.io.pClear(0).bits.stid.poke(0.U)
      dut.io.pClear(0).bits.epoch.poke(7.U)
      dut.io.pClear(0).bits.ptag.poke(17.U)
      dut.io.pClear(0).bits.generation.poke(9.U)
      dut.clock.step()
      dut.io.pClear(0).valid.poke(false.B)

      var sawInvalid = false
      var sawRetry = false
      for (_ <- 0 until 8) {
        sawInvalid ||= dut.io.readRejected.exists(
          _.valid.peek().litToBoolean)
        sawRetry ||= dut.io.retryFeedback.exists(
          _.valid.peek().litToBoolean)
        dut.clock.step()
      }
      assert(sawInvalid)
      assert(sawRetry)
      assert(dut.io.sharedMalformedMask.peek().litValue == 0)
    }
  }

  private def pokeRob(
      target: linxcore.top.interface.RobIdentity,
      rid: Int): Unit = {
    target.peId.poke(3.U)
    target.stid.poke(0.U)
    target.ridSlot.poke(rid.U)
    target.ridGeneration.poke(0.U)
    target.memberIndex.poke(0.U)
    target.residentGeneration.poke((20 + rid).U)
    target.bid.poke(rid.U)
    target.brobGeneration.poke((7 + rid).U)
  }

  test("real PC buffer base reaches a retained I2 transaction") {
    val core = SimulationParamProfiles.W2
    simulate(new IEXPcReadHarness(core)) { dut =>
      val p = dut.p
      dut.io.prepare.valid.poke(false.B)
      dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
      dut.io.publishFire.poke(false.B)
      dut.io.p1.valid.poke(false.B)
      dut.io.p1.bits.poke(0.U.asTypeOf(dut.io.p1.bits))
      dut.io.i2.ready.poke(false.B)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      val entry = dut.io.prepare.bits.entries(0)
      dut.io.prepare.bits.count.poke(1.U)
      dut.io.prepare.bits.groupCount.poke(1.U)
      dut.io.prepare.bits.groups(0).valid.poke(true.B)
      dut.io.prepare.bits.groups(0).peId.poke(3.U)
      dut.io.prepare.bits.groups(0).stid.poke(0.U)
      dut.io.prepare.bits.groups(0).ridSlot.poke(1.U)
      entry.uop.decoded.valid.poke(true.B)
      pokeRob(entry.uop.decoded.rob, 1)
      entry.uop.decoded.instruction.parent.pc.poke("h80000120".U)
      entry.uop.decoded.instruction.parent.lengthBytes.poke(2.U)
      entry.uop.decoded.instruction.parent.identity.peId.poke(3.U)
      entry.uop.decoded.instruction.parent.identity.stid.poke(0.U)
      entry.uop.decoded.instruction.parent.identity.instructionId.poke(9.U)
      entry.uop.decoded.instruction.parent.identity.epoch.poke(1.U)
      dut.io.prepare.valid.poke(true.B)
      dut.io.prepareReady.expect(true.B)
      val token = dut.io.prepared.lanes(0)
      val index = token.pcBufferIndex.peek().litValue
      val epoch = token.allocationEpoch.peek().litValue
      dut.io.publishFire.poke(true.B)
      dut.clock.step()
      dut.io.prepare.valid.poke(false.B)
      dut.io.publishFire.poke(false.B)

      val request = dut.io.p1.bits
      request.poke(0.U.asTypeOf(request))
      request.row.schedule.valid.poke(true.B)
      request.row.schedule.peId.poke(3.U)
      request.row.schedule.stid.poke(0.U)
      request.row.schedule.epoch.poke(1.U)
      request.row.schedule.transactionId.poke(9.U)
      request.row.schedule.member.group.valid.poke(true.B)
      request.row.schedule.member.group.peId.poke(3.U)
      request.row.schedule.member.group.stid.poke(0.U)
      request.row.schedule.member.group.ridSlot.poke(1.U)
      request.row.schedule.member.bid.valid.poke(true.B)
      request.row.schedule.reservation.valid.poke(true.B)
      request.row.schedule.reservation.uopClass.poke(OooUopClass.Alu)
      request.row.schedule.reservation.bank.poke(0.U)
      request.row.schedule.reservation.speculativeSlot.poke(0.U)
      request.row.payload.uopKey.primaryParent.valid.poke(true.B)
      request.row.payload.uopKey.primaryParent.peId.poke(3.U)
      request.row.payload.uopKey.primaryParent.stid.poke(0.U)
      request.row.payload.parentCount.poke(1.U)
      request.row.payload.parentPcTokens(0).valid.poke(true.B)
      request.row.payload.parentPcTokens(0).index.poke(index.U)
      request.row.payload.parentPcTokens(0).byteOffset.poke(6.U)
      request.row.payload.parentPcTokens(0).allocationEpoch.poke(epoch.U)
      request.pcReadRequired.poke(true.B)
      request.pcParentIndex.poke(0.U)
      dut.io.p1.valid.poke(true.B)
      dut.clock.step()
      dut.io.p1.valid.poke(false.B)
      dut.clock.step()
      dut.io.i2.valid.expect(true.B)
      dut.io.i2.bits.pcValid.expect(true.B)
      dut.io.i2.bits.pc.expect("h80000126".U)
    }
  }
}
