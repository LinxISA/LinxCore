package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.common.OperandClass
import org.scalatest.funsuite.AnyFunSuite

class OooIexIssueP1LaneSpec extends AnyFunSuite with ChiselSim {
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
    pMapQDepthPerStid = 4,
    tuMapQDepthPerStid = 4,
    tuRetireSourceDepthPerStid = 16)

  private def clear(dut: OooIexIssueP1Lane): Unit = {
    dut.io.s1.valid.poke(false.B)
    dut.io.s1.bits.poke(0.U.asTypeOf(dut.io.s1.bits))
    dut.io.wakeup.foreach(_.poke(0.U.asTypeOf(dut.io.wakeup.head)))
    dut.io.release.valid.poke(false.B)
    dut.io.release.bits.poke(0.U.asTypeOf(dut.io.release.bits))
    dut.io.dispatchRelease.ready.poke(false.B)
    dut.io.ptagRecycle.valid.poke(false.B)
    dut.io.ptagRecycle.bits.poke(0.U.asTypeOf(dut.io.ptagRecycle.bits))
    dut.io.recoveryPrepare.valid.poke(false.B)
    dut.io.recoveryPrepare.bits.poke(
      0.U.asTypeOf(dut.io.recoveryPrepare.bits))
    dut.io.recoveryFire.poke(false.B)
    dut.io.pickClass.poke(OooUopClass.Bru)
    dut.io.pickBankEnable.poke(1.U)
    dut.io.issuePolicy.poke(0.U.asTypeOf(dut.io.issuePolicy))
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
    dut.io.i2.ready.poke(false.B)
  }

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

  private def pokeLoadProducer(target: RobMemberKey): Unit = {
    target.poke(0.U.asTypeOf(target))
    target.group.valid.poke(true.B)
    target.group.peId.poke(3.U)
    target.group.stid.poke(1.U)
    target.group.ridSlot.poke(1.U)
    target.group.ridGeneration.poke(1.U)
    target.bid.valid.poke(true.B)
    target.bid.value.poke(3.U)
    target.brobGeneration.poke(2.U)
    target.memberIndex.poke(0.U)
    target.residentGeneration.poke(4.U)
  }

  private def pokeLoadToken(
      target: OooIexLoadGeneration,
      generation: Int): Unit = {
    target.poke(0.U.asTypeOf(target))
    target.valid.poke(true.B)
    pokeLoadProducer(target.producer)
    target.generation.poke(generation.U)
  }

  private def pokeOnePcBru(dut: OooIexIssueP1Lane): Unit = {
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
      uop.opcode.poke(73.U)
      uop.recipe.valid.poke(true.B)
      uop.recipe.opcode.poke(73.U)
      uop.recipe.disposition.poke(OooOpcodeDisposition.Dispatch.U)
      uop.recipe.dispatchClass.poke(OooDispatchClass.Bru.U)
      uop.recipe.dispatchWrites.poke(1.U)
      uop.recipe.dispatchDemand(OooDispatchClass.Bru - 1).poke(1.U)
      uop.recipe.pcReadRequired.poke(true.B)
      uop.recipe.pcReadClass.poke(OooDispatchClass.Bru.U)
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

    val pcToken = request.o3.parentPcTokens(0)(0)
    pcToken.valid.poke(true.B)
    pcToken.index.poke(5.U)
    pcToken.byteOffset.poke(6.U)
    pcToken.allocationEpoch.poke(3.U)

    val allocation = request.dispatch.allocations(0)
    allocation.valid.poke(true.B)
    allocation.uopIndex.poke(0.U)
    allocation.childIndex.poke(0.U)
    allocation.reservation.valid.poke(true.B)
    allocation.reservation.uopClass.poke(OooUopClass.Bru)
    allocation.reservation.bank.poke(0.U)
    allocation.reservation.writePort.poke(0.U)
    allocation.reservation.speculativeSlot.poke(1.U)
    allocation.reservation.reservationEpoch.poke(9.U)
    dut.io.s1.valid.poke(true.B)
  }

  test("denied PC read retries the exact IQ member and repicks it") {
    simulate(new OooIexIssueP1Lane(p)) { dut =>
      clear(dut)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      pokeOnePcBru(dut)
      dut.io.s1.ready.expect(true.B)
      dut.clock.step()
      dut.io.s1.valid.poke(false.B)
      dut.clock.step() // S2 bind
      dut.clock.step() // S3 resident
      dut.clock.step() // picker retains the exact scheduling token
      dut.clock.step() // exact token joins payload and enters I1

      dut.io.i1Occupied.expect(true.B)
      dut.io.inFlightEntries(OooDispatchClass.Bru - 1)(0).expect(1.U)
      dut.io.readAttempt.valid.expect(true.B)
      dut.io.readAttempt.bits.pcRequired.expect(true.B)
      dut.io.readAttempt.bits.pcToken.index.expect(5.U)
      dut.io.readAttempt.bits.pcToken.byteOffset.expect(6.U)

      dut.io.readDecisionValid.poke(true.B)
      dut.io.readGrant.poke(false.B)
      dut.io.retryFeedback.valid.expect(true.B)
      dut.io.retryFeedback.bits.member.group.ridSlot.expect(2.U)
      dut.io.retryFeedback.bits.reservation.speculativeSlot.expect(1.U)
      dut.clock.step()
      dut.io.readDecisionValid.poke(false.B)
      dut.io.inFlightEntries(OooDispatchClass.Bru - 1)(0).expect(0.U)
      dut.io.i1Occupied.expect(false.B)

      dut.clock.step() // retry makes the row selectable; picker retains it
      dut.clock.step() // repicked row enters I1 again
      dut.io.i1Occupied.expect(true.B)
      dut.io.inFlightEntries(OooDispatchClass.Bru - 1)(0).expect(1.U)
      dut.io.readAttempt.valid.expect(true.B)

      dut.io.readDecisionValid.poke(true.B)
      dut.io.readGrant.poke(true.B)
      dut.io.pcDataValid.poke(true.B)
      dut.io.pcData.poke("h80000126".U)
      dut.clock.step()
      dut.io.readDecisionValid.poke(false.B)
      dut.io.readGrant.poke(false.B)
      dut.io.i2.valid.expect(true.B)
      dut.io.i2.bits.pcValid.expect(true.B)
      dut.io.i2.bits.pc.expect("h80000126".U)
      dut.io.i2.bits.row.opcode.expect(73.U)
    }
  }

  test("cancels an exact speculative load copy and reissues a new generation") {
    simulate(new OooIexIssueP1Lane(p)) { dut =>
      clear(dut)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      pokeOnePcBru(dut)
      val request = dut.io.s1.bits
      val decoded = request.o3.request.reservation.transaction.decoded.uops(0)
      val renamed = request.pRename.uops(0)
      Seq(decoded.sources(0), renamed.decoded.sources(0)).foreach { source =>
        source.valid.poke(true.B)
        source.operandClass.poke(OperandClass.P)
        source.atag.poke(2.U)
      }
      renamed.sources(0).decoded.valid.poke(true.B)
      renamed.sources(0).decoded.operandClass.poke(OperandClass.P)
      renamed.sources(0).decoded.atag.poke(2.U)
      renamed.sources(0).pMapping.valid.poke(true.B)
      renamed.sources(0).pMapping.ptag.poke(17.U)
      renamed.sources(0).pMapping.ptagGeneration.poke(3.U)
      renamed.sources(0).pMapping.ready.poke(false.B)
      renamed.sources(0).pMapping.stid.poke(1.U)
      renamed.sources(0).pMapping.epoch.poke(7.U)

      dut.clock.step()
      dut.io.s1.valid.poke(false.B)
      dut.clock.step() // S2 bind
      dut.clock.step() // S3 resident, still not ready

      val wakeup = dut.io.wakeup(0)
      wakeup.bits.poke(0.U.asTypeOf(wakeup.bits))
      wakeup.bits.kind.poke(OooIexWakeupKind.SpeculativeLoad)
      wakeup.bits.stid.poke(1.U)
      wakeup.bits.epoch.poke(7.U)
      wakeup.bits.operandClass.poke(OperandClass.P)
      wakeup.bits.ptag.poke(17.U)
      wakeup.bits.ptagGeneration.poke(3.U)
      pokeLoadToken(wakeup.bits.load, generation = 7)
      wakeup.valid.poke(true.B)
      dut.clock.step()
      wakeup.valid.poke(false.B)
      dut.clock.step() // picker retains the now-ready row
      dut.clock.step() // bridge joins and enters I1
      dut.io.i1Occupied.expect(true.B)
      dut.io.readAttempt.valid.expect(false.B)

      val bypass = dut.io.bypass(0)
      bypass.bits.poke(0.U.asTypeOf(bypass.bits))
      bypass.bits.stid.poke(1.U)
      bypass.bits.epoch.poke(7.U)
      pokeLoadToken(bypass.bits.load, generation = 7)
      pokeLoadProducer(bypass.bits.producer)
      bypass.bits.operandClass.poke(OperandClass.P)
      bypass.bits.ptag.poke(17.U)
      bypass.bits.ptagGeneration.poke(3.U)
      bypass.bits.stage.poke(OooIexBypassStage.W1)
      bypass.bits.data.poke("h1111222233334444".U)
      bypass.valid.poke(true.B)
      dut.io.readAttempt.valid.expect(true.B)
      dut.io.readAttempt.bits.sourceMask.expect(0.U)
      dut.io.readDecisionValid.poke(true.B)
      dut.io.readGrant.poke(true.B)
      dut.io.pcDataValid.poke(true.B)
      dut.io.pcData.poke("h80000126".U)
      dut.clock.step()
      dut.io.readDecisionValid.poke(false.B)
      dut.io.readGrant.poke(false.B)
      bypass.valid.poke(false.B)
      dut.io.i2.valid.expect(true.B)
      dut.io.i2.bits.sourceData(0).expect("h1111222233334444".U)

      val cancel = dut.io.loadCancel(0)
      cancel.bits.poke(0.U.asTypeOf(cancel.bits))
      cancel.bits.stid.poke(1.U)
      cancel.bits.epoch.poke(7.U)
      pokeLoadToken(cancel.bits.load, generation = 7)
      cancel.valid.poke(true.B)
      dut.io.i2.valid.expect(false.B)
      dut.io.loadCanceled(2).valid.expect(true.B)
      dut.clock.step()
      cancel.valid.poke(false.B)
      dut.io.inFlightEntries(OooDispatchClass.Bru - 1)(0).expect(0.U)
      dut.io.i2.valid.expect(false.B)

      wakeup.bits.load.generation.poke(8.U)
      wakeup.valid.poke(true.B)
      dut.clock.step()
      wakeup.valid.poke(false.B)
      dut.clock.step()
      dut.clock.step()
      dut.io.i1Occupied.expect(true.B)
      bypass.bits.load.generation.poke(8.U)
      bypass.valid.poke(true.B)
      dut.io.readDecisionValid.poke(true.B)
      dut.io.readGrant.poke(true.B)
      dut.io.pcDataValid.poke(true.B)
      dut.clock.step()
      dut.io.readDecisionValid.poke(false.B)
      dut.io.readGrant.poke(false.B)
      bypass.valid.poke(false.B)
      dut.io.i2.valid.expect(true.B)
      dut.io.i2.bits.bypass(0).load.generation.expect(8.U)
    }
  }
}
