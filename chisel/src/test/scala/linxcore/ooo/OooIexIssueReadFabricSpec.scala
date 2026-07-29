package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.common.OperandClass
import org.scalatest.funsuite.AnyFunSuite

class OooIexIssueReadFabricSpec extends AnyFunSuite with ChiselSim {
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
    iexIssueDomainCount = 1,
    pMapQDepthPerStid = 4,
    tuMapQDepthPerStid = 4,
    tuRetireSourceDepthPerStid = 16)

  private def clear(dut: OooIexIssueReadFabric): Unit = {
    dut.io.s1.valid.poke(false.B)
    dut.io.s1.bits.poke(0.U.asTypeOf(dut.io.s1.bits))
    dut.io.wakeup.foreach(_.poke(0.U.asTypeOf(dut.io.wakeup.head)))
    dut.io.release.valid.poke(false.B)
    dut.io.release.bits.poke(0.U.asTypeOf(dut.io.release.bits))
    dut.io.dispatchRelease.ready.poke(true.B)
    dut.io.ptagRecycle.valid.poke(false.B)
    dut.io.ptagRecycle.bits.poke(0.U.asTypeOf(dut.io.ptagRecycle.bits))
    dut.io.recoveryPrepare.valid.poke(false.B)
    dut.io.recoveryPrepare.bits.poke(
      0.U.asTypeOf(dut.io.recoveryPrepare.bits))
    dut.io.recoveryFire.poke(false.B)
    dut.io.pickClasses(0).poke(OooUopClass.Alu)
    dut.io.pickBankEnables(0).poke(1.U)
    dut.io.pcReadResponses.foreach(
      _.poke(0.U.asTypeOf(dut.io.pcReadResponses.head)))
    dut.io.bypass.foreach(
      _.poke(0.U.asTypeOf(dut.io.bypass.head)))
    dut.io.loadCancel.foreach(
      _.poke(0.U.asTypeOf(dut.io.loadCancel.head)))
    dut.io.pInit.poke(0.U.asTypeOf(dut.io.pInit))
    dut.io.pClear.foreach(_.poke(0.U.asTypeOf(dut.io.pClear.head)))
    dut.io.pWrite.foreach(_.poke(0.U.asTypeOf(dut.io.pWrite.head)))
    dut.io.tClear.foreach(_.poke(0.U.asTypeOf(dut.io.tClear.head)))
    dut.io.uClear.foreach(_.poke(0.U.asTypeOf(dut.io.uClear.head)))
    dut.io.tWrite.foreach(_.poke(0.U.asTypeOf(dut.io.tWrite.head)))
    dut.io.uWrite.foreach(_.poke(0.U.asTypeOf(dut.io.uWrite.head)))
    dut.io.i2(0).ready.poke(false.B)
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

  private def pokeOnePSourceAlu(dut: OooIexIssueReadFabric): Unit = {
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
      uop.sources(0).valid.poke(true.B)
      uop.sources(0).operandClass.poke(OperandClass.P)
      uop.sources(0).atag.poke(2.U)
    }
    renamed.sources(0).decoded.valid.poke(true.B)
    renamed.sources(0).decoded.operandClass.poke(OperandClass.P)
    renamed.sources(0).decoded.atag.poke(2.U)
    renamed.sources(0).pMapping.valid.poke(true.B)
    renamed.sources(0).pMapping.ptag.poke(40.U)
    renamed.sources(0).pMapping.ptagGeneration.poke(3.U)
    renamed.sources(0).pMapping.ready.poke(true.B)
    renamed.sources(0).pMapping.stid.poke(1.U)
    renamed.sources(0).pMapping.epoch.poke(7.U)
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

  private def pokeRelease(dut: OooIexIssueReadFabric): Unit = {
    val release = dut.io.release.bits
    release.poke(0.U.asTypeOf(release))
    pokeMember(release.member)
    release.dispatch.peId.poke(3.U)
    release.dispatch.stid.poke(1.U)
    release.dispatch.epoch.poke(7.U)
    release.dispatch.transactionId.poke(31.U)
    pokeMember(release.dispatch.member)
    release.dispatch.reservation.valid.poke(true.B)
    release.dispatch.reservation.uopClass.poke(OooUopClass.Alu)
    release.dispatch.reservation.bank.poke(0.U)
    release.dispatch.reservation.writePort.poke(0.U)
    release.dispatch.reservation.speculativeSlot.poke(1.U)
    release.dispatch.reservation.reservationEpoch.poke(9.U)
    dut.io.release.valid.poke(true.B)
  }

  test("missing canonical P data rejects and exact-repicks before I2") {
    simulate(new OooIexIssueReadFabric(p)) { dut =>
      clear(dut)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      pokeOnePSourceAlu(dut)
      dut.io.s1.ready.expect(true.B)
      dut.clock.step()
      dut.io.s1.valid.poke(false.B)

      var cycles = 0
      while (!dut.io.readAttempts(0).valid.peek().litToBoolean && cycles < 8) {
        dut.clock.step()
        cycles += 1
      }
      assert(dut.io.readAttempts(0).valid.peek().litToBoolean,
        "the exact ALU row never reached the canonical I1 read attempt")
      dut.io.readSelectedMask.expect(1.U)
      dut.io.readDeniedMask.expect(0.U)
      dut.io.readAttempts(0).bits.sourceMask.expect(1.U)
      dut.io.readAttempts(0).bits.sources(0).ptag.expect(40.U)
      dut.io.readRejected(0).valid.expect(true.B)
      dut.io.retryFeedback(0).valid.expect(true.B)
      dut.io.retryFeedback(0).bits.member.group.ridSlot.expect(2.U)
      dut.io.inFlightEntries(OooDispatchClass.Alu - 1)(0).expect(1.U)
      dut.clock.step()
      dut.io.inFlightEntries(OooDispatchClass.Alu - 1)(0).expect(0.U)

      dut.io.pInit.valid.poke(true.B)
      dut.io.pInit.bits.key.stid.poke(1.U)
      dut.io.pInit.bits.key.epoch.poke(7.U)
      dut.io.pInit.bits.key.ptag.poke(40.U)
      dut.io.pInit.bits.key.generation.poke(3.U)
      dut.io.pInit.bits.data.poke("h0123456789abcdef".U)
      dut.clock.step()
      dut.io.pInit.valid.poke(false.B)

      cycles = 0
      while (!dut.io.readAttempts(0).valid.peek().litToBoolean && cycles < 8) {
        dut.clock.step()
        cycles += 1
      }
      assert(dut.io.readAttempts(0).valid.peek().litToBoolean,
        "the rejected row did not become eligible for an exact repick")
      dut.io.readSelectedMask.expect(1.U)
      dut.io.readRejected(0).valid.expect(false.B)
      dut.clock.step()

      dut.io.i2(0).valid.expect(true.B)
      dut.io.i2(0).bits.sourceData(0).expect("h0123456789abcdef".U)
      dut.io.i2(0).bits.row.schedule.sources(0).ptag.expect(40.U)
      dut.io.i2(0).ready.poke(true.B)
      dut.clock.step()
      dut.io.i2(0).ready.poke(false.B)

      pokeRelease(dut)
      dut.io.release.ready.expect(true.B)
      dut.clock.step()
      dut.io.release.valid.poke(false.B)
      dut.io.empty.expect(true.B)
      dut.io.pProtocolError.expect(false.B)
      dut.io.localProtocolError.expect(false.B)
    }
  }
}
