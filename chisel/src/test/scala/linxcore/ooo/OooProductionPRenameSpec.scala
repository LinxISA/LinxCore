package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.common.{DestinationKind, OperandClass}
import org.scalatest.funsuite.AnyFunSuite

class OooProductionPRenameSpec extends AnyFunSuite with ChiselSim {
  private def clear(dut: OooProductionPRename): Unit = {
    dut.io.prepare.valid.poke(false.B)
    dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
    dut.io.ptagLease.poke(0.U.asTypeOf(dut.io.ptagLease))
    dut.io.publishFire.poke(false.B)
    dut.io.queryStid.poke(0.U)
    dut.io.queryAtag.poke(0.U)
  }

  private def pokeTwoUopChain(
      dut: OooProductionPRename,
      stid: Int,
      transactionId: Int,
      atag: Int,
      firstPtag: Int,
      secondPtag: Int): Unit = {
    dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
    dut.io.ptagLease.poke(0.U.asTypeOf(dut.io.ptagLease))

    val request = dut.io.prepare.bits.request
    val reservation = request.reservation
    val transaction = reservation.transaction
    transaction.plan.peId.poke(3.U)
    transaction.plan.stid.poke(stid.U)
    transaction.plan.epoch.poke(5.U)
    transaction.plan.transactionId.poke(transactionId.U)
    transaction.plan.uopMask.poke(3.U)
    transaction.plan.groupCount.poke(1.U)
    transaction.plan.demand.pDestinations.poke(2.U)
    transaction.plan.demand.mapQRows.poke(2.U)
    transaction.decoded.peId.poke(3.U)
    transaction.decoded.stid.poke(stid.U)
    transaction.decoded.epoch.poke(5.U)
    transaction.decoded.uopMask.poke(3.U)
    transaction.groupMask.poke(1.U)
    transaction.groups(0).valid.poke(true.B)
    transaction.groups(0).key.valid.poke(true.B)
    transaction.groups(0).key.peId.poke(3.U)
    transaction.groups(0).key.stid.poke(stid.U)
    transaction.groups(0).key.ridSlot.poke(4.U)
    transaction.groups(0).key.ridGeneration.poke(2.U)
    transaction.groups(0).logicalUopMask.poke(3.U)
    transaction.groups(0).physicalMemberCount.poke(2.U)
    request.bindings(0).valid.poke(true.B)
    request.bindings(0).brob.valid.poke(true.B)
    request.bindings(0).brob.bid.valid.poke(true.B)
    request.bindings(0).brob.bid.value.poke(7.U)
    request.bindings(0).brob.generation.poke(3.U)
    request.bindings(0).pcBase.valid.poke(true.B)
    request.bindings(0).residentGeneration.poke(9.U)

    for (uopIndex <- 0 until 2) {
      val uop = transaction.decoded.uops(uopIndex)
      uop.valid.poke(true.B)
      uop.plannedChildCount.poke(1.U)
      uop.sources(0).valid.poke(true.B)
      uop.sources(0).operandClass.poke(OperandClass.P)
      uop.sources(0).atag.poke(atag.U)
      uop.destinations(0).valid.poke(true.B)
      uop.destinations(0).kind.poke(DestinationKind.Gpr)
      uop.destinations(0).atag.poke(atag.U)
      transaction.uopGroupIndex(uopIndex).poke(0.U)
      transaction.uopMemberBase(uopIndex).poke(uopIndex.U)
    }

    dut.io.ptagLease.valid.poke(true.B)
    dut.io.ptagLease.peId.poke(3.U)
    dut.io.ptagLease.stid.poke(stid.U)
    dut.io.ptagLease.epoch.poke(5.U)
    dut.io.ptagLease.transactionId.poke(transactionId.U)
    dut.io.ptagLease.allocationMask.poke(5.U) // flat rows 0 and 2
    Seq(firstPtag, secondPtag).zipWithIndex.foreach { case (ptag, uopIndex) =>
      val flatIndex = uopIndex * dut.p.maxDestinationOperands
      val allocation = dut.io.ptagLease.allocations(flatIndex)
      allocation.valid.poke(true.B)
      allocation.uopIndex.poke(uopIndex.U)
      allocation.destinationIndex.poke(0.U)
      allocation.atag.poke(atag.U)
      allocation.token.valid.poke(true.B)
      allocation.token.ptag.poke(ptag.U)
      allocation.token.bank.poke((ptag % dut.p.pTagBanks).U)
      allocation.token.generation.poke(1.U)
    }
    dut.io.prepare.valid.poke(true.B)
  }

  private def publish(dut: OooProductionPRename): Unit = {
    dut.io.prepareReady.expect(true.B)
    dut.io.publishFire.poke(true.B)
    dut.clock.step()
    dut.io.publishFire.poke(false.B)
    dut.io.prepare.valid.poke(false.B)
  }

  test("forwards same-transaction P RAW and WAW while publishing exact MapQ rows") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      pMapQDepthPerStid = 4,
      pTagStagingDepthPerBank = 2,
      pTagReturnWidth = 4)
    simulate(new OooProductionPRename(p)) { dut =>
      clear(dut)
      dut.io.queryStid.poke(1.U)
      dut.io.queryAtag.poke(3.U)
      dut.io.speculativeMapping.valid.expect(true.B)
      dut.io.speculativeMapping.ptag.expect(27.U)
      dut.io.committedMapping.ptag.expect(27.U)

      pokeTwoUopChain(dut, stid = 1, transactionId = 10, atag = 3,
        firstPtag = 96, secondPtag = 97)
      dut.io.prepareReady.expect(true.B)
      dut.io.prepared.uops(0).sources(0).pMapping.ptag.expect(27.U)
      dut.io.prepared.uops(1).sources(0).pMapping.ptag.expect(96.U)
      dut.io.prepared.uops(0).destinations(0)
        .previousPMapping.ptag.expect(27.U)
      dut.io.prepared.uops(1).destinations(0)
        .previousPMapping.ptag.expect(96.U)
      dut.io.prepared.uops(1).destinations(0)
        .currentPMapping.ptag.expect(97.U)
      dut.io.prepared.uops(1).destinations(0)
        .currentPMapping.producerBindingValid.expect(false.B)
      dut.io.prepared.mapQRowMask.expect(5.U)
      dut.io.prepared.mapQRows(0).mapQIndex.expect(0.U)
      dut.io.prepared.mapQRows(2).mapQIndex.expect(1.U)
      dut.io.prepared.mapQRows(2).member.memberIndex.expect(1.U)
      dut.clock.step(2)
      dut.io.mapQUsed(1).expect(0.U)
      dut.io.speculativeMapping.ptag.expect(27.U)

      publish(dut)
      dut.io.queryStid.poke(1.U)
      dut.io.queryAtag.poke(3.U)
      dut.io.mapQUsed(1).expect(2.U)
      dut.io.speculativeMapping.ptag.expect(97.U)
      dut.io.committedMapping.ptag.expect(27.U)
      dut.io.mapQUsed(0).expect(0.U)
    }
  }

  test("rejects a malformed lease without mutating speculative state") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      pMapQDepthPerStid = 4,
      pTagStagingDepthPerBank = 2,
      pTagReturnWidth = 4)
    simulate(new OooProductionPRename(p)) { dut =>
      clear(dut)
      pokeTwoUopChain(dut, stid = 2, transactionId = 12, atag = 4,
        firstPtag = 96, secondPtag = 97)
      dut.io.ptagLease.allocations(2).atag.poke(5.U)
      dut.io.prepareReady.expect(false.B)
      dut.io.prepareRejected.valid.expect(true.B)
      dut.clock.step(2)
      dut.io.mapQUsed(2).expect(0.U)
      dut.io.queryStid.poke(2.U)
      dut.io.queryAtag.poke(4.U)
      dut.io.speculativeMapping.ptag.expect(52.U)

      pokeTwoUopChain(dut, stid = 2, transactionId = 12, atag = 4,
        firstPtag = 96, secondPtag = 97)
      dut.io.prepare.bits.request.reservation.transaction.decoded
        .uops(1).plannedChildCount.poke(2.U)
      dut.io.prepareReady.expect(false.B)
      dut.clock.step()
      dut.io.mapQUsed(2).expect(0.U)

      pokeTwoUopChain(dut, stid = 2, transactionId = 12, atag = 4,
        firstPtag = 96, secondPtag = 97)
      dut.io.prepare.bits.request.reservation.transaction
        .uopMemberBase(1).poke(0.U)
      dut.io.prepareReady.expect(false.B)
      dut.clock.step()
      dut.io.mapQUsed(2).expect(0.U)
    }
  }

  test("applies per-STID MapQ capacity atomically") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      pMapQDepthPerStid = 4,
      pTagStagingDepthPerBank = 2,
      pTagReturnWidth = 4)
    simulate(new OooProductionPRename(p)) { dut =>
      clear(dut)
      pokeTwoUopChain(dut, stid = 0, transactionId = 20, atag = 1,
        firstPtag = 96, secondPtag = 97)
      publish(dut)
      pokeTwoUopChain(dut, stid = 0, transactionId = 22, atag = 2,
        firstPtag = 98, secondPtag = 99)
      dut.io.prepared.mapQRows(0).mapQIndex.expect(2.U)
      dut.io.prepared.mapQRows(2).mapQIndex.expect(3.U)
      publish(dut)
      dut.io.mapQUsed(0).expect(4.U)

      pokeTwoUopChain(dut, stid = 0, transactionId = 24, atag = 3,
        firstPtag = 100, secondPtag = 101)
      dut.io.prepareReady.expect(false.B)
      dut.io.prepareRejected.bits.freeRows.expect(0.U)
      dut.clock.step()
      dut.io.mapQUsed(0).expect(4.U)
      dut.io.queryAtag.poke(3.U)
      dut.io.speculativeMapping.ptag.expect(3.U)
    }
  }

  test("rejects an out-of-range six-wide uop group index without mutation") {
    val p = OooParams(
      instructionDecodeWidth = 6,
      decodedUopWidth = 6,
      pMapQDepthPerStid = 4,
      pTagStagingDepthPerBank = 6,
      pTagReturnWidth = 4)
    simulate(new OooProductionPRename(p)) { dut =>
      clear(dut)
      pokeTwoUopChain(dut, stid = 0, transactionId = 0, atag = 1,
        firstPtag = 96, secondPtag = 97)
      dut.io.prepare.bits.request.reservation.transaction
        .uopGroupIndex(1).poke(7.U)
      dut.io.prepareReady.expect(false.B)
      dut.io.prepareRejected.valid.expect(true.B)
      dut.clock.step()
      dut.io.mapQUsed(0).expect(0.U)
      dut.io.queryAtag.poke(1.U)
      dut.io.speculativeMapping.ptag.expect(1.U)
    }
  }

  test("elaborates production P rename at instruction widths 2 4 and 6") {
    Seq(2, 4, 6).foreach { width =>
      val p = OooParams(
        instructionDecodeWidth = width,
        pMapQDepthPerStid = 4)
      circt.stage.ChiselStage.emitSystemVerilog(new OooProductionPRename(p))
    }
  }
}
