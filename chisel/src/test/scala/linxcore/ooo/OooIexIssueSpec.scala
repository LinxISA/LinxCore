package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import circt.stage.ChiselStage
import linxcore.common.{DestinationKind, OperandClass}
import org.scalatest.funsuite.AnyFunSuite

private object OooIexIssueSpec {
  final case class Allocation(
      uopIndex: Int,
      childIndex: Int,
      uopClass: Int,
      bank: Int,
      port: Int,
      entry: Int,
      reservationEpoch: Int = 1)
}

class OooIexIssueSpec extends AnyFunSuite with ChiselSim {
  import OooIexIssueSpec._

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

  private def clear(dut: OooIexIssue): Unit = {
    dut.io.s1.valid.poke(false.B)
    dut.io.s1.bits.poke(0.U.asTypeOf(dut.io.s1.bits))
    dut.io.wakeup.foreach { wakeup =>
      wakeup.valid.poke(false.B)
      wakeup.bits.poke(0.U.asTypeOf(wakeup.bits))
    }
    dut.io.release.valid.poke(false.B)
    dut.io.release.bits.poke(0.U.asTypeOf(dut.io.release.bits))
    dut.io.dispatchRelease.ready.poke(false.B)
    dut.io.query.poke(0.U.asTypeOf(dut.io.query))
    dut.io.pickClass.poke(OooUopClass.Alu)
    dut.io.pickBankEnable.poke(0.U)
    dut.io.pick.ready.poke(false.B)
    dut.io.pickRetry.valid.poke(false.B)
    dut.io.pickRetry.bits.poke(0.U.asTypeOf(dut.io.pickRetry.bits))
    dut.io.recoveryPrepare.valid.poke(false.B)
    dut.io.recoveryPrepare.bits.poke(
      0.U.asTypeOf(dut.io.recoveryPrepare.bits))
    dut.io.recoveryFire.poke(false.B)
    dut.io.ptagRecycle.valid.poke(false.B)
    dut.io.ptagRecycle.bits.poke(0.U.asTypeOf(dut.io.ptagRecycle.bits))
  }

  private def pokeMember(
      target: RobMemberKey,
      stid: Int,
      memberIndex: Int,
      residentGeneration: Int = 7): Unit = {
    target.group.valid.poke(true.B)
    target.group.peId.poke(3.U)
    target.group.stid.poke(stid.U)
    target.group.ridSlot.poke(1.U)
    target.group.ridGeneration.poke(2.U)
    target.bid.valid.poke(true.B)
    target.bid.value.poke(4.U)
    target.brobGeneration.poke(5.U)
    target.memberIndex.poke(memberIndex.U)
    target.residentGeneration.poke(residentGeneration.U)
  }

  private def pokeTransaction(
      dut: OooIexIssue,
      stid: Int,
      transactionId: Int,
      allocations: Vector[Allocation],
      pSourceReady: Option[Boolean] = None,
      pSourceGeneration: Int = 3): Unit = {
    val request = dut.io.s1.bits
    request.poke(0.U.asTypeOf(request))
    val transaction = request.o3.request.reservation.transaction
    transaction.plan.peId.poke(3.U)
    transaction.plan.stid.poke(stid.U)
    transaction.plan.epoch.poke(6.U)
    transaction.plan.transactionId.poke(transactionId.U)

    val activeUops = allocations.map(_.uopIndex).distinct.sorted
    val uopMask = activeUops.map(index => 1 << index).sum
    transaction.decoded.peId.poke(3.U)
    transaction.decoded.stid.poke(stid.U)
    transaction.decoded.epoch.poke(6.U)
    transaction.decoded.uopMask.poke(uopMask.U)

    request.pRename.valid.poke(true.B)
    request.pRename.peId.poke(3.U)
    request.pRename.stid.poke(stid.U)
    request.pRename.epoch.poke(6.U)
    request.pRename.transactionId.poke(transactionId.U)
    request.pRename.uopMask.poke(uopMask.U)
    request.tuRename.valid.poke(true.B)
    request.tuRename.peId.poke(3.U)
    request.tuRename.stid.poke(stid.U)
    request.tuRename.epoch.poke(6.U)
    request.tuRename.transactionId.poke(transactionId.U)
    request.tuRename.uopMask.poke(uopMask.U)
    request.dispatch.valid.poke(true.B)
    request.dispatch.peId.poke(3.U)
    request.dispatch.stid.poke(stid.U)
    request.dispatch.epoch.poke(6.U)
    request.dispatch.transactionId.poke(transactionId.U)
    request.dispatch.allocationMask.poke(
      ((BigInt(1) << allocations.length) - 1).U)

    activeUops.foreach { uopIndex =>
      val childCount = allocations.filter(_.uopIndex == uopIndex)
        .map(_.childIndex).max + 1
      val decoded = transaction.decoded.uops(uopIndex)
      val pUop = request.pRename.uops(uopIndex)
      val tuUop = request.tuRename.uops(uopIndex)
      decoded.valid.poke(true.B)
      decoded.opcode.poke((40 + uopIndex).U)
      decoded.plannedChildCount.poke(childCount.U)
      pUop.valid.poke(true.B)
      pUop.decoded.valid.poke(true.B)
      pUop.decoded.opcode.poke((40 + uopIndex).U)
      pUop.decoded.plannedChildCount.poke(childCount.U)
      tuUop.valid.poke(true.B)
      pokeMember(pUop.member, stid, memberIndex = uopIndex * 2)
      pokeMember(tuUop.member, stid, memberIndex = uopIndex * 2)

      Seq(decoded, pUop.decoded).foreach { logical =>
        logical.identity.key.primaryParent.valid.poke(true.B)
        logical.identity.key.primaryParent.peId.poke(3.U)
        logical.identity.key.primaryParent.stid.poke(stid.U)
        logical.identity.key.primaryParent.instructionId
          .poke((100 + uopIndex).U)
        logical.identity.key.primaryParent.epoch.poke(6.U)
        logical.identity.parentCount.poke(1.U)
        logical.recipe.valid.poke(true.B)
        logical.recipe.opcode.poke((40 + uopIndex).U)
        logical.recipe.disposition.poke(OooOpcodeDisposition.Dispatch.U)
        logical.recipe.pcReadRequired.poke(false.B)
        val parent = logical.identity.parents(0)
        parent.key.valid.poke(true.B)
        parent.key.peId.poke(3.U)
        parent.key.stid.poke(stid.U)
        parent.key.instructionId.poke((100 + uopIndex).U)
        parent.key.epoch.poke(6.U)
        parent.prediction.valid.poke(true.B)
        parent.prediction.predictionTag.poke((20 + uopIndex).U)
      }
      val pcToken = request.o3.parentPcTokens(uopIndex)(0)
      pcToken.valid.poke(true.B)
      pcToken.index.poke((uopIndex + stid * 4).U)
      pcToken.byteOffset.poke((uopIndex * 4).U)
      pcToken.allocationEpoch.poke(2.U)

      Seq(decoded.destinations(0), pUop.decoded.destinations(0))
        .foreach { destination =>
          destination.valid.poke(true.B)
          destination.kind.poke(DestinationKind.Gpr)
          destination.atag.poke(3.U)
        }
      pUop.destinations(0).currentPMapping.valid.poke(true.B)
      pUop.destinations(0).currentPMapping.ptag.poke((30 + uopIndex).U)
      pUop.destinations(0).currentPMapping.ptagGeneration.poke(4.U)

      pSourceReady.foreach { ready =>
        decoded.sources(0).valid.poke(true.B)
        decoded.sources(0).operandClass.poke(OperandClass.P)
        decoded.sources(0).atag.poke(2.U)
        pUop.decoded.sources(0).valid.poke(true.B)
        pUop.decoded.sources(0).operandClass.poke(OperandClass.P)
        pUop.decoded.sources(0).atag.poke(2.U)
        pUop.sources(0).decoded.valid.poke(true.B)
        pUop.sources(0).decoded.operandClass.poke(OperandClass.P)
        pUop.sources(0).decoded.atag.poke(2.U)
        pUop.sources(0).pMapping.valid.poke(true.B)
        pUop.sources(0).pMapping.ptag.poke(17.U)
        pUop.sources(0).pMapping.ptagGeneration.poke(pSourceGeneration.U)
        pUop.sources(0).pMapping.ready.poke(ready.B)
        pUop.sources(0).pMapping.stid.poke(stid.U)
        pUop.sources(0).pMapping.epoch.poke(6.U)
      }
    }

    allocations.zipWithIndex.foreach { case (value, lane) =>
      val allocation = request.dispatch.allocations(lane)
      allocation.valid.poke(true.B)
      allocation.uopIndex.poke(value.uopIndex.U)
      allocation.childIndex.poke(value.childIndex.U)
      allocation.reservation.valid.poke(true.B)
      pokeClass(allocation.reservation.uopClass, value.uopClass)
      allocation.reservation.bank.poke(value.bank.U)
      allocation.reservation.writePort.poke(value.port.U)
      allocation.reservation.speculativeSlot.poke(value.entry.U)
      allocation.reservation.reservationEpoch.poke(value.reservationEpoch.U)
    }
    dut.io.s1.valid.poke(true.B)
  }

  private def query(
      dut: OooIexIssue,
      uopClass: Int,
      bank: Int,
      entry: Int): Unit = {
    pokeClass(dut.io.query.uopClass, uopClass)
    dut.io.query.bank.poke(bank.U)
    dut.io.query.entry.poke(entry.U)
  }

  private def advanceToS3(dut: OooIexIssue): Unit = {
    dut.io.s1.ready.expect(true.B)
    dut.clock.step() // retained S1
    dut.io.s1.valid.poke(false.B)
    dut.io.s2Bind.valid.expect(true.B)
    dut.clock.step() // exact S2 bind
    dut.io.s3Enable.valid.expect(true.B)
    dut.clock.step() // S3 resident/pick-enable
  }

  test("keeps S1 S2 and S3 as independent retained stages") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      dispatchWidth = 2,
      robGroupsPerStid = 8,
      iqBankCount = 2,
      iqEntriesPerBank = 4,
      iqWritePortsPerBank = 2,
      pMapQDepthPerStid = 4,
      tuMapQDepthPerStid = 4,
      tuRetireSourceDepthPerStid = 16)
    simulate(new OooIexIssue(p)) { dut =>
      clear(dut)
      val allocation = Allocation(0, 0, 0, 0, 0, 1)
      pokeTransaction(dut, 1, 9, Vector(allocation))
      query(dut, 0, 0, 1)
      dut.io.queryState.expect(OooIexIssueSlotState.Free)
      dut.io.s1.ready.expect(true.B)
      dut.clock.step()
      dut.io.s1.valid.poke(false.B)
      dut.io.s1Occupied(1).expect(true.B)
      dut.io.s2Bind.valid.expect(true.B)
      dut.io.s2Bind.bits.transactionId.expect(9.U)
      dut.io.queryState.expect(OooIexIssueSlotState.Free)
      dut.clock.step()
      dut.io.s1Occupied(1).expect(false.B)
      dut.io.s3Enable.valid.expect(true.B)
      dut.io.queryState.expect(OooIexIssueSlotState.BoundS2)
      dut.io.queryPickable.expect(false.B)
      dut.clock.step()
      dut.io.queryState.expect(OooIexIssueSlotState.ResidentS3)
      dut.io.queryPickable.expect(true.B)
      dut.io.queryRow.member.memberIndex.expect(0.U)
      dut.io.queryRow.transactionId.expect(9.U)
      dut.io.queryRow.opcode.expect(40.U)
      dut.io.queryRow.primaryPrediction.valid.expect(true.B)
      dut.io.queryRow.primaryPrediction.predictionTag.expect(20.U)
      dut.io.queryRow.parentPcTokens(0).valid.expect(true.B)
      dut.io.queryRow.pcParentIndexValid.expect(true.B)
      dut.io.queryRow.pcParentIndex.expect(0.U)
      dut.io.queryRow.recipe.pcReadRequired.expect(false.B)
      dut.io.queryRow.destinations(0).valid.expect(true.B)
      dut.io.queryRow.destinations(0).ptag.expect(30.U)
    }
  }

  test("registers wakeup before it becomes visible to S3 eligibility") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      dispatchWidth = 2,
      robGroupsPerStid = 8,
      iqBankCount = 2,
      iqEntriesPerBank = 4,
      iqWritePortsPerBank = 2,
      pMapQDepthPerStid = 4,
      tuMapQDepthPerStid = 4,
      tuRetireSourceDepthPerStid = 16)
    simulate(new OooIexIssue(p)) { dut =>
      clear(dut)
      pokeTransaction(dut, 0, 2,
        Vector(Allocation(0, 0, 0, 0, 0, 0)),
        pSourceReady = Some(false))
      query(dut, 0, 0, 0)
      advanceToS3(dut)
      dut.io.queryPickable.expect(false.B)

      val wakeup = dut.io.wakeup(0)
      wakeup.bits.stid.poke(0.U)
      wakeup.bits.epoch.poke(6.U)
      wakeup.bits.operandClass.poke(OperandClass.P)
      wakeup.bits.ptag.poke(17.U)
      wakeup.bits.ptagGeneration.poke(3.U)
      wakeup.valid.poke(true.B)
      dut.io.queryPickable.expect(false.B)
      dut.clock.step()
      wakeup.valid.poke(false.B)
      dut.io.queryPickable.expect(true.B)

      // A later consumer must observe the generation-qualified ready table
      // even though it never overlapped the original one-cycle wakeup pulse.
      pokeTransaction(dut, 0, 3,
        Vector(Allocation(0, 0, 0, 0, 0, 1)),
        pSourceReady = Some(false), pSourceGeneration = 4)
      query(dut, 0, 0, 1)
      advanceToS3(dut)
      dut.io.queryPickable.expect(false.B)

      pokeTransaction(dut, 0, 4,
        Vector(Allocation(0, 0, 0, 0, 0, 2)),
        pSourceReady = Some(false), pSourceGeneration = 3)
      query(dut, 0, 0, 2)
      advanceToS3(dut)
      dut.io.queryPickable.expect(true.B)

      // Recycling the exact generation must clear the retained ready record
      // before that physical tag can be issued again.
      dut.io.ptagRecycle.bits.poke(
        0.U.asTypeOf(dut.io.ptagRecycle.bits))
      dut.io.ptagRecycle.bits.count.poke(1.U)
      dut.io.ptagRecycle.bits.tokens(0).valid.poke(true.B)
      dut.io.ptagRecycle.bits.tokens(0).ptag.poke(17.U)
      dut.io.ptagRecycle.bits.tokens(0).generation.poke(3.U)
      dut.io.ptagRecycle.valid.poke(true.B)
      dut.io.ptagRecycle.ready.expect(true.B)
      dut.clock.step()
      dut.io.ptagRecycle.valid.poke(false.B)

      pokeTransaction(dut, 0, 7,
        Vector(Allocation(0, 0, 0, 0, 0, 3)),
        pSourceReady = Some(false), pSourceGeneration = 3)
      query(dut, 0, 0, 3)
      advanceToS3(dut)
      dut.io.queryPickable.expect(false.B)

      // T/U readiness uses the same retained principle but exact local
      // sequence identity rather than PTag allocation generation.
      wakeup.bits.poke(0.U.asTypeOf(wakeup.bits))
      wakeup.bits.stid.poke(0.U)
      wakeup.bits.epoch.poke(6.U)
      wakeup.bits.operandClass.poke(OperandClass.T)
      wakeup.bits.localTag.poke(5.U)
      wakeup.bits.localSequence.valid.poke(true.B)
      wakeup.bits.localSequence.index.poke(1.U)
      wakeup.bits.localSequence.generation.poke(2.U)
      wakeup.valid.poke(true.B)
      dut.clock.step()
      wakeup.valid.poke(false.B)

      pokeTransaction(dut, 0, 5,
        Vector(Allocation(0, 0, 0, 1, 0, 0)))
      val tRequest = dut.io.s1.bits
      val tDecoded = tRequest.o3.request.reservation.transaction.decoded.uops(0)
      val tPUop = tRequest.pRename.uops(0)
      val tLocalSource = tRequest.tuRename.uops(0).sources(0)
      Seq(tDecoded.sources(0), tPUop.decoded.sources(0)).foreach { source =>
        source.valid.poke(true.B)
        source.operandClass.poke(OperandClass.T)
        source.relativeIndex.poke(0.U)
      }
      tLocalSource.valid.poke(true.B)
      tLocalSource.kind.poke(DestinationKind.T)
      tLocalSource.relativeIndex.poke(0.U)
      tLocalSource.physicalTag.poke(5.U)
      tLocalSource.stid.poke(0.U)
      tLocalSource.epoch.poke(6.U)
      tLocalSource.sequence.valid.poke(true.B)
      tLocalSource.sequence.index.poke(1.U)
      tLocalSource.sequence.generation.poke(2.U)
      query(dut, 0, 1, 0)
      advanceToS3(dut)
      dut.io.queryPickable.expect(true.B)

      wakeup.bits.poke(0.U.asTypeOf(wakeup.bits))
      wakeup.bits.stid.poke(0.U)
      wakeup.bits.epoch.poke(6.U)
      wakeup.bits.operandClass.poke(OperandClass.U)
      wakeup.bits.localTag.poke(6.U)
      wakeup.bits.localSequence.valid.poke(true.B)
      wakeup.bits.localSequence.index.poke(2.U)
      wakeup.bits.localSequence.generation.poke(3.U)
      wakeup.valid.poke(true.B)
      dut.clock.step()
      wakeup.valid.poke(false.B)

      pokeTransaction(dut, 0, 6,
        Vector(Allocation(0, 0, 0, 1, 0, 1)))
      val uRequest = dut.io.s1.bits
      val uDecoded = uRequest.o3.request.reservation.transaction.decoded.uops(0)
      val uPUop = uRequest.pRename.uops(0)
      val uLocalSource = uRequest.tuRename.uops(0).sources(0)
      Seq(uDecoded.sources(0), uPUop.decoded.sources(0)).foreach { source =>
        source.valid.poke(true.B)
        source.operandClass.poke(OperandClass.U)
        source.relativeIndex.poke(0.U)
      }
      uLocalSource.valid.poke(true.B)
      uLocalSource.kind.poke(DestinationKind.U)
      uLocalSource.relativeIndex.poke(0.U)
      uLocalSource.physicalTag.poke(6.U)
      uLocalSource.stid.poke(0.U)
      uLocalSource.epoch.poke(6.U)
      uLocalSource.sequence.valid.poke(true.B)
      uLocalSource.sequence.index.poke(2.U)
      uLocalSource.sequence.generation.poke(3.U)
      query(dut, 0, 1, 1)
      advanceToS3(dut)
      dut.io.queryPickable.expect(true.B)
    }
  }

  test("captures a producer wakeup on the same edge as consumer S2 bind") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      dispatchWidth = 2,
      robGroupsPerStid = 8,
      iqBankCount = 2,
      iqEntriesPerBank = 4,
      iqWritePortsPerBank = 2,
      pMapQDepthPerStid = 4,
      tuMapQDepthPerStid = 4,
      tuRetireSourceDepthPerStid = 16)
    simulate(new OooIexIssue(p)) { dut =>
      clear(dut)
      pokeTransaction(dut, 0, 7,
        Vector(Allocation(0, 0, 0, 0, 0, 3)),
        pSourceReady = Some(false))
      query(dut, 0, 0, 3)
      dut.io.s1.ready.expect(true.B)
      dut.clock.step() // retain S1
      dut.io.s1.valid.poke(false.B)
      dut.io.s2Bind.valid.expect(true.B)

      val wakeup = dut.io.wakeup(0)
      wakeup.bits.stid.poke(0.U)
      wakeup.bits.epoch.poke(6.U)
      wakeup.bits.operandClass.poke(OperandClass.P)
      wakeup.bits.ptag.poke(17.U)
      wakeup.bits.ptagGeneration.poke(3.U)
      wakeup.valid.poke(true.B)
      dut.clock.step() // bind S2 and capture the same-cycle wakeup
      wakeup.valid.poke(false.B)
      dut.io.queryState.expect(OooIexIssueSlotState.BoundS2)
      dut.io.queryPickable.expect(false.B)

      dut.clock.step() // registered S3 enable
      dut.io.queryState.expect(OooIexIssueSlotState.ResidentS3)
      dut.io.queryPickable.expect(true.B)
    }
  }

  test("retains independent STID S1 rows and binds them fairly") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      dispatchWidth = 2,
      robGroupsPerStid = 8,
      iqBankCount = 2,
      iqEntriesPerBank = 4,
      iqWritePortsPerBank = 2,
      pMapQDepthPerStid = 4,
      tuMapQDepthPerStid = 4,
      tuRetireSourceDepthPerStid = 16)
    simulate(new OooIexIssue(p)) { dut =>
      clear(dut)
      pokeTransaction(dut, 0, 1,
        Vector(Allocation(0, 0, 0, 0, 0, 0)))
      dut.io.s1.ready.expect(true.B)
      dut.clock.step()

      // The first row has not reached S2 yet, but its pending S1 claim must
      // already exclude the same physical target for every other STID.
      pokeTransaction(dut, 2, 2,
        Vector(Allocation(0, 0, 0, 0, 0, 0)))
      dut.io.s1.ready.expect(false.B)
      dut.io.s1Rejected.valid.expect(true.B)

      pokeTransaction(dut, 2, 2,
        Vector(Allocation(0, 0, 0, 1, 0, 0)))
      dut.io.s1.ready.expect(true.B)
      dut.io.s2Bind.valid.expect(true.B)
      dut.io.s2Bind.bits.stid.expect(0.U)
      dut.clock.step()
      dut.io.s1.valid.poke(false.B)
      dut.io.s2Bind.valid.expect(true.B)
      dut.io.s2Bind.bits.stid.expect(2.U)
      dut.clock.step()
      dut.io.s1Occupied(0).expect(false.B)
      dut.io.s1Occupied(2).expect(false.B)
    }
  }

  test("releases resident state and dispatch ownership on one exact fire") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      dispatchWidth = 2,
      robGroupsPerStid = 8,
      iqBankCount = 2,
      iqEntriesPerBank = 4,
      iqWritePortsPerBank = 2,
      pMapQDepthPerStid = 4,
      tuMapQDepthPerStid = 4,
      tuRetireSourceDepthPerStid = 16)
    simulate(new OooIexIssue(p)) { dut =>
      clear(dut)
      val allocation = Allocation(0, 0, 0, 1, 1, 2, reservationEpoch = 3)
      pokeTransaction(dut, 3, 11, Vector(allocation))
      query(dut, 0, 1, 2)
      advanceToS3(dut)

      dut.io.pickClass.poke(OooUopClass.Alu)
      dut.io.pickBankEnable.poke("b10".U)
      dut.clock.step()
      dut.io.pick.valid.expect(true.B)
      dut.io.pick.bits.query.bank.expect(1.U)
      dut.io.pick.bits.query.entry.expect(2.U)

      val retry = dut.io.pickRetry.bits
      retry.poke(0.U.asTypeOf(retry))
      pokeMember(retry.member, 3, memberIndex = 0)
      retry.reservation.valid.poke(true.B)
      retry.reservation.uopClass.poke(OooUopClass.Alu)
      retry.reservation.bank.poke(1.U)
      retry.reservation.writePort.poke(1.U)
      retry.reservation.speculativeSlot.poke(2.U)
      retry.reservation.reservationEpoch.poke(3.U)

      // A bridge/P1 shape failure returns the exact token on the claim edge.
      // The claim and retry must collapse to a resident, pickable row.
      dut.io.pick.ready.poke(true.B)
      dut.io.pickRetry.valid.poke(true.B)
      dut.io.pickRetryRejected.valid.expect(false.B)
      dut.clock.step()
      dut.io.pick.ready.poke(false.B)
      dut.io.pickRetry.valid.poke(false.B)
      dut.io.inFlightEntries(0)(1).expect(0.U)
      dut.io.queryPickable.expect(true.B)

      dut.clock.step()
      dut.io.pick.valid.expect(true.B)
      dut.io.pick.ready.poke(true.B)
      dut.clock.step()
      dut.io.pick.ready.poke(false.B)
      dut.io.inFlightEntries(0)(1).expect(1.U)
      dut.io.queryPickable.expect(false.B)

      dut.io.pickRetry.valid.poke(true.B)
      dut.io.pickRetryRejected.valid.expect(false.B)
      dut.clock.step()
      dut.io.pickRetry.valid.poke(false.B)
      dut.io.inFlightEntries(0)(1).expect(0.U)
      dut.io.queryPickable.expect(true.B)

      dut.clock.step()
      dut.io.pick.valid.expect(true.B)
      dut.io.pick.ready.poke(true.B)
      dut.clock.step()
      dut.io.pick.ready.poke(false.B)
      dut.io.inFlightEntries(0)(1).expect(1.U)

      val release = dut.io.release.bits
      release.poke(0.U.asTypeOf(release))
      pokeMember(release.member, 3, memberIndex = 0)
      pokeMember(release.dispatch.member, 3, memberIndex = 0)
      release.dispatch.peId.poke(3.U)
      release.dispatch.stid.poke(3.U)
      release.dispatch.epoch.poke(6.U)
      release.dispatch.transactionId.poke(11.U)
      release.dispatch.reservation.valid.poke(true.B)
      release.dispatch.reservation.uopClass.poke(OooUopClass.Alu)
      release.dispatch.reservation.bank.poke(1.U)
      release.dispatch.reservation.writePort.poke(1.U)
      release.dispatch.reservation.speculativeSlot.poke(2.U)
      release.dispatch.reservation.reservationEpoch.poke(4.U)
      dut.io.release.valid.poke(true.B)
      dut.io.release.ready.expect(false.B)
      dut.io.releaseRejected.valid.expect(true.B)
      dut.clock.step()
      dut.io.queryState.expect(OooIexIssueSlotState.ResidentS3)

      release.dispatch.reservation.reservationEpoch.poke(3.U)
      dut.io.dispatchRelease.ready.poke(false.B)
      dut.io.releaseRejected.valid.expect(false.B)
      dut.io.release.ready.expect(false.B)
      dut.io.dispatchRelease.valid.expect(true.B)
      dut.clock.step()
      dut.io.queryState.expect(OooIexIssueSlotState.ResidentS3)

      dut.io.dispatchRelease.ready.poke(true.B)
      dut.io.release.ready.expect(true.B)
      dut.clock.step()
      dut.io.release.valid.poke(false.B)
      dut.io.queryState.expect(OooIexIssueSlotState.Free)
    }
  }

  test("rejects duplicate physical targets and binds a split bundle atomically") {
    val p = OooParams(
      instructionDecodeWidth = 2,
      decodedUopWidth = 2,
      dispatchWidth = 4,
      robGroupsPerStid = 8,
      iqBankCount = 2,
      iqEntriesPerBank = 4,
      iqWritePortsPerBank = 2,
      pMapQDepthPerStid = 4,
      tuMapQDepthPerStid = 4,
      tuRetireSourceDepthPerStid = 16)
    simulate(new OooIexIssue(p)) { dut =>
      clear(dut)
      val first = Allocation(0, 0, 2, 0, 0, 1)
      pokeTransaction(dut, 1, 4,
        Vector(first, first.copy(childIndex = 1, port = 1)))
      dut.io.s1.ready.expect(false.B)
      dut.io.s1Rejected.valid.expect(true.B)
      dut.clock.step()
      dut.io.s1.valid.poke(false.B)
      dut.io.residentEntries(2)(0).expect(0.U)

      pokeTransaction(dut, 1, 5,
        Vector(first, first.copy(childIndex = 1, port = 1, entry = 2)))
      dut.io.s1.ready.expect(true.B)
      dut.clock.step()
      dut.io.s1.valid.poke(false.B)
      dut.io.s2Bind.valid.expect(true.B)
      dut.io.s2Bind.bits.allocationMask.expect("b0011".U)
      dut.clock.step(2)
      dut.io.residentEntries(2)(0).expect(2.U)
      query(dut, 2, 0, 1)
      dut.io.queryRow.member.memberIndex.expect(0.U)
      query(dut, 2, 0, 2)
      dut.io.queryRow.member.memberIndex.expect(1.U)
    }
  }

  test("elaborates the IEX residency boundary at instruction widths 2 4 and 6") {
    Seq(2, 4, 6).foreach { width =>
      val p = OooParams(
        instructionDecodeWidth = width,
        decodedUopWidth = width,
        dispatchWidth = width * 2,
        robGroupsPerStid = 8,
        iqBankCount = 2,
        iqEntriesPerBank = 4,
        iqWritePortsPerBank = 2,
        pTagStagingDepthPerBank = 8,
        pMapQDepthPerStid = 16,
        tuMapQDepthPerStid = 16,
        tuRetireSourceDepthPerStid = 64)
      val sv = ChiselStage.emitSystemVerilog(new OooIexIssue(p))
      assert(sv.contains("module OooIexIssue"))
    }
  }
}
