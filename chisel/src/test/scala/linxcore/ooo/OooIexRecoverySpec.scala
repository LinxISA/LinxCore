package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.common.{DestinationKind, OperandClass}
import org.scalatest.funsuite.AnyFunSuite

/** Focused O7 recovery proof with the smallest physical IQ that can retain an
  * older member and a killed suffix simultaneously.  The owner is
  * instantiated directly; this is not a behavioral mock.
  */
class OooIexRecoverySpec extends AnyFunSuite with ChiselSim {
  private val p = OooParams(
    stidCount = 2,
    instructionDecodeWidth = 2,
    decodedUopWidth = 2,
    dispatchWidth = 2,
    robGroupsPerStid = 4,
    iqBankCount = 2,
    iqEntriesPerBank = 2,
    iqWritePortsPerBank = 2,
    pMapQDepthPerStid = 4,
    tuMapQDepthPerStid = 4,
    tuRetireSourceDepthPerStid = 8)

  private def clear(dut: OooIexIssue): Unit = {
    dut.io.s1.valid.poke(false.B)
    dut.io.s1.bits.poke(0.U.asTypeOf(dut.io.s1.bits))
    dut.io.wakeup.foreach { wakeup =>
      wakeup.valid.poke(false.B)
      wakeup.bits.poke(0.U.asTypeOf(wakeup.bits))
    }
    dut.io.loadCancel.foreach(
      _.poke(0.U.asTypeOf(dut.io.loadCancel.head)))
    dut.io.release.valid.poke(false.B)
    dut.io.release.bits.poke(0.U.asTypeOf(dut.io.release.bits))
    dut.io.dispatchRelease.ready.poke(true.B)
    dut.io.query.poke(0.U.asTypeOf(dut.io.query))
    dut.io.pickBankEnables.flatten.foreach(_.poke(0.U))
    dut.io.pickBankEnables(0)(OooUopClass.Alu.asUInt.litValue.toInt)
      .poke(((BigInt(1) << p.iqBankCount) - 1).U)
    dut.io.issuePolicy.poke(0.U.asTypeOf(dut.io.issuePolicy))
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
      member: RobMemberKey,
      stid: Int,
      ridSlot: Int,
      memberIndex: Int): Unit = {
    member.poke(0.U.asTypeOf(member))
    member.group.valid.poke(true.B)
    member.group.peId.poke(3.U)
    member.group.stid.poke(stid.U)
    member.group.ridSlot.poke(ridSlot.U)
    member.group.ridGeneration.poke(0.U)
    member.bid.valid.poke(true.B)
    member.bid.value.poke((stid + 1).U)
    member.brobGeneration.poke(2.U)
    member.memberIndex.poke(memberIndex.U)
    member.residentGeneration.poke(3.U)
  }

  private def pokeTransaction(
      dut: OooIexIssue,
      stid: Int,
      ridSlot: Int,
      transactionId: Int,
      entries: Vector[Int],
      pDestination: Option[(Int, Int)] = None,
      pSource: Option[(Int, Int)] = None): Unit = {
    val request = dut.io.s1.bits
    request.poke(0.U.asTypeOf(request))
    val transaction = request.o3.request.reservation.transaction
    val uopMask = (1 << entries.length) - 1
    transaction.plan.peId.poke(3.U)
    transaction.plan.stid.poke(stid.U)
    transaction.plan.epoch.poke(6.U)
    transaction.plan.transactionId.poke(transactionId.U)
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
    request.dispatch.allocationMask.poke(uopMask.U)

    entries.indices.foreach { lane =>
      val decoded = transaction.decoded.uops(lane)
      val pUop = request.pRename.uops(lane)
      val tuUop = request.tuRename.uops(lane)
      decoded.valid.poke(true.B)
      decoded.opcode.poke((40 + lane).U)
      decoded.plannedChildCount.poke(1.U)
      decoded.recipe.valid.poke(true.B)
      decoded.recipe.opcode.poke((40 + lane).U)
      decoded.recipe.disposition.poke(OooOpcodeDisposition.Dispatch.U)
      pUop.valid.poke(true.B)
      pUop.decoded.valid.poke(true.B)
      pUop.decoded.opcode.poke((40 + lane).U)
      pUop.decoded.plannedChildCount.poke(1.U)
      pUop.decoded.recipe.valid.poke(true.B)
      pUop.decoded.recipe.opcode.poke((40 + lane).U)
      pUop.decoded.recipe.disposition
        .poke(OooOpcodeDisposition.Dispatch.U)
      tuUop.valid.poke(true.B)
      pokeMember(pUop.member, stid, ridSlot, lane)
      pokeMember(tuUop.member, stid, ridSlot, lane)

      pDestination.foreach { case (ptag, generation) =>
        Seq(decoded.destinations(0), pUop.decoded.destinations(0))
          .foreach { destination =>
            destination.valid.poke(true.B)
            destination.kind.poke(DestinationKind.Gpr)
            destination.atag.poke(3.U)
          }
        pUop.destinations(0).currentPMapping.valid.poke(true.B)
        pUop.destinations(0).currentPMapping.ptag.poke(ptag.U)
        pUop.destinations(0).currentPMapping.ptagGeneration
          .poke(generation.U)
      }

      pSource.foreach { case (ptag, generation) =>
        Seq(decoded.sources(0), pUop.decoded.sources(0)).foreach { source =>
          source.valid.poke(true.B)
          source.operandClass.poke(OperandClass.P)
          source.atag.poke(2.U)
        }
        pUop.sources(0).decoded.valid.poke(true.B)
        pUop.sources(0).decoded.operandClass.poke(OperandClass.P)
        pUop.sources(0).decoded.atag.poke(2.U)
        pUop.sources(0).pMapping.valid.poke(true.B)
        pUop.sources(0).pMapping.ptag.poke(ptag.U)
        pUop.sources(0).pMapping.ptagGeneration.poke(generation.U)
        pUop.sources(0).pMapping.ready.poke(false.B)
        pUop.sources(0).pMapping.stid.poke(stid.U)
        pUop.sources(0).pMapping.epoch.poke(6.U)
      }

      val allocation = request.dispatch.allocations(lane)
      allocation.valid.poke(true.B)
      allocation.uopIndex.poke(lane.U)
      allocation.childIndex.poke(0.U)
      allocation.reservation.valid.poke(true.B)
      allocation.reservation.uopClass.poke(OooUopClass.Alu)
      allocation.reservation.bank.poke(0.U)
      allocation.reservation.writePort.poke(lane.U)
      allocation.reservation.speculativeSlot.poke(entries(lane).U)
      allocation.reservation.reservationEpoch.poke(1.U)
    }
    dut.io.s1.valid.poke(true.B)
  }

  private def pokeRecovery(
      dut: OooIexIssue,
      stid: Int,
      ridSlot: Int,
      oldMembers: Int,
      survivingMembers: Int): Unit = {
    val plan = dut.io.recoveryPrepare.bits
    plan.poke(0.U.asTypeOf(plan))
    plan.valid.poke(true.B)
    plan.oldHead.valid.poke(true.B)
    plan.oldHead.peId.poke(3.U)
    plan.oldHead.stid.poke(stid.U)
    plan.oldHead.ridSlot.poke(ridSlot.U)
    plan.oldHead.ridGeneration.poke(0.U)
    plan.oldOccupied.poke(1.U)
    plan.newOccupied.poke((if (survivingMembers == 0) 0 else 1).U)
    plan.pivotOffset.poke(0.U)
    pokeMember(plan.pivot, stid, ridSlot, 0)
    plan.pivotPhysicalMemberCount.poke(oldMembers.U)
    plan.survivingPivotValid.poke((survivingMembers != 0).B)
    plan.survivingPivotPhysicalMemberCount.poke(survivingMembers.U)
    dut.io.recoveryPrepare.valid.poke(true.B)
  }

  private def query(dut: OooIexIssue, entry: Int): Unit = {
    dut.io.query.uopClass.poke(OooUopClass.Alu)
    dut.io.query.bank.poke(0.U)
    dut.io.query.entry.poke(entry.U)
  }

  private def waitForPrepared(
      dut: OooIexIssue,
      whileScanning: => Unit,
      scanParams: OooParams = p): Int = {
    var cycles = 0
    while (!dut.io.recoveryPrepareReady.peek().litToBoolean &&
        cycles < scanParams.iexRecoveryScanCycles + 2) {
      whileScanning
      dut.clock.step()
      cycles += 1
    }
    assert(dut.io.recoveryPrepareReady.peek().litToBoolean,
      "retained IEX recovery scan did not prepare")
    assert(cycles == scanParams.iexRecoveryScanCycles + 1,
      s"expected one capture plus ${scanParams.iexRecoveryScanCycles} scan cycles, got $cycles")
    cycles
  }

  private def waitForRejected(dut: OooIexIssue): Unit = {
    var cycles = 0
    while (!dut.io.recoveryRejected.valid.peek().litToBoolean &&
        cycles < p.iexRecoveryScanCycles + 2) {
      dut.clock.step()
      cycles += 1
    }
    assert(dut.io.recoveryRejected.valid.peek().litToBoolean,
      "malformed resident window was not rejected by the timed scan")
  }

  test("prunes exact BoundS2 and ResidentS3 suffixes without stopping peers") {
    simulate(new OooIexIssue(p)) { dut =>
      clear(dut)

      pokeTransaction(dut, stid = 0, ridSlot = 0, transactionId = 1,
        entries = Vector(0, 1))
      dut.io.s1Rejected.bits.shapeExact.expect(true.B)
      dut.io.s1Rejected.bits.targetsExact.expect(true.B)
      dut.io.s1.ready.expect(true.B)
      dut.clock.step()
      dut.io.s1.valid.poke(false.B)
      dut.io.s2Bind.valid.expect(true.B)
      dut.clock.step()
      query(dut, 0)
      dut.io.queryState.expect(OooIexIssueSlotState.BoundS2)
      query(dut, 1)
      dut.io.queryState.expect(OooIexIssueSlotState.BoundS2)

      pokeRecovery(dut, stid = 0, ridSlot = 0, oldMembers = 2,
        survivingMembers = 1)
      dut.io.recoveryPrepareReady.expect(false.B)

      // Capture and then abort one partially completed scan.  Target rows are
      // frozen but remain physically resident because prepare is read-only.
      dut.clock.step()
      query(dut, 0)
      dut.io.queryState.expect(OooIexIssueSlotState.BoundS2)
      query(dut, 1)
      dut.io.queryState.expect(OooIexIssueSlotState.BoundS2)
      dut.io.recoveryPrepare.valid.poke(false.B)
      dut.clock.step()
      query(dut, 0)
      dut.io.queryState.expect(OooIexIssueSlotState.BoundS2)
      query(dut, 1)
      dut.io.queryState.expect(OooIexIssueSlotState.BoundS2)

      pokeRecovery(dut, stid = 0, ridSlot = 0, oldMembers = 2,
        survivingMembers = 1)
      waitForPrepared(dut, {
        query(dut, 0)
        dut.io.queryState.expect(OooIexIssueSlotState.BoundS2)
        query(dut, 1)
        dut.io.queryState.expect(OooIexIssueSlotState.BoundS2)
      })
      dut.io.recoveryPrepared.boundKilled.expect(1.U)
      dut.io.recoveryPrepared.residentKilled.expect(0.U)
      dut.io.recoveryFire.poke(true.B)
      dut.clock.step()
      dut.io.recoveryFire.poke(false.B)
      dut.io.recoveryPrepare.valid.poke(false.B)
      dut.io.s3Enable.valid.expect(true.B)
      dut.io.s3Enable.bits.bind.allocationMask.expect(1.U)
      dut.clock.step()
      query(dut, 0)
      dut.io.queryState.expect(OooIexIssueSlotState.ResidentS3)
      dut.io.queryPickable.expect(true.B)
      query(dut, 1)
      dut.io.queryState.expect(OooIexIssueSlotState.Free)

      pokeTransaction(dut, stid = 1, ridSlot = 1, transactionId = 2,
        entries = Vector(1))
      dut.io.s1.ready.expect(true.B)
      dut.clock.step()
      dut.io.s1.valid.poke(false.B)
      dut.io.s2Bind.valid.expect(true.B)
      dut.clock.step()
      dut.io.s3Enable.valid.expect(true.B)
      dut.clock.step()
      query(dut, 1)
      dut.io.queryState.expect(OooIexIssueSlotState.ResidentS3)

      pokeRecovery(dut, stid = 1, ridSlot = 1, oldMembers = 1,
        survivingMembers = 0)
      dut.io.recoveryPrepareReady.expect(false.B)
      waitForPrepared(dut, {
        query(dut, 0)
        dut.io.queryPickable.expect(true.B)
        query(dut, 1)
        dut.io.queryState.expect(OooIexIssueSlotState.ResidentS3)
        dut.io.queryPickable.expect(false.B)
      })
      dut.io.recoveryPrepared.boundKilled.expect(0.U)
      dut.io.recoveryPrepared.residentKilled.expect(1.U)
      query(dut, 0)
      dut.io.queryPickable.expect(true.B)
      query(dut, 1)
      dut.io.queryPickable.expect(false.B)
      dut.io.recoveryFire.poke(true.B)
      dut.clock.step()
      dut.io.recoveryFire.poke(false.B)
      dut.io.recoveryPrepare.valid.poke(false.B)
      query(dut, 0)
      dut.io.queryState.expect(OooIexIssueSlotState.ResidentS3)
      query(dut, 1)
      dut.io.queryState.expect(OooIexIssueSlotState.Free)

      // A plan whose old window does not contain a live target-STID row must
      // reject after scanning that row and must not prune it.
      pokeRecovery(dut, stid = 0, ridSlot = 1, oldMembers = 1,
        survivingMembers = 0)
      waitForRejected(dut)
      dut.io.recoveryPrepareReady.expect(false.B)
      dut.io.recoveryRejected.bits.residentRowsExact.expect(false.B)
      query(dut, 0)
      dut.io.queryState.expect(OooIexIssueSlotState.ResidentS3)
      dut.io.recoveryPrepare.valid.poke(false.B)
      dut.clock.step()
      query(dut, 0)
      dut.io.queryPickable.expect(true.B)
    }
  }

  test("rejects plan drift and identity-qualifies P-ready cleanup across reuse") {
    simulate(new OooIexIssue(p)) { dut =>
      clear(dut)

      pokeTransaction(dut, stid = 0, ridSlot = 0, transactionId = 10,
        entries = Vector(0), pDestination = Some(17 -> 3))
      dut.io.s1.ready.expect(true.B)
      dut.clock.step()
      dut.io.s1.valid.poke(false.B)
      dut.clock.step()
      dut.clock.step()
      query(dut, 0)
      dut.io.queryState.expect(OooIexIssueSlotState.ResidentS3)

      val wakeup = dut.io.wakeup(0)
      wakeup.bits.poke(0.U.asTypeOf(wakeup.bits))
      wakeup.bits.stid.poke(0.U)
      wakeup.bits.epoch.poke(6.U)
      wakeup.bits.operandClass.poke(OperandClass.P)
      wakeup.bits.ptag.poke(17.U)
      wakeup.bits.ptagGeneration.poke(3.U)
      wakeup.valid.poke(true.B)
      dut.clock.step()
      wakeup.valid.poke(false.B)

      pokeRecovery(dut, stid = 0, ridSlot = 0, oldMembers = 1,
        survivingMembers = 0)
      dut.clock.step()
      dut.io.recoveryPrepare.bits.survivingPivotPhysicalMemberCount.poke(1.U)
      dut.io.recoveryRejected.valid.expect(true.B)
      dut.clock.step()
      query(dut, 0)
      dut.io.queryState.expect(OooIexIssueSlotState.ResidentS3)
      dut.io.recoveryPrepare.valid.poke(false.B)
      dut.clock.step()

      // Capture a fresh exact plan and its old target P-ready identity.
      pokeRecovery(dut, stid = 0, ridSlot = 0, oldMembers = 1,
        survivingMembers = 0)
      dut.clock.step()

      // Recycle that exact old generation while the target row is scanned.
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

      // A peer then owns the same numerical PTag with a new generation.  The
      // retained target mask must not clear this identity at common apply.
      wakeup.bits.poke(0.U.asTypeOf(wakeup.bits))
      wakeup.bits.stid.poke(1.U)
      wakeup.bits.epoch.poke(6.U)
      wakeup.bits.operandClass.poke(OperandClass.P)
      wakeup.bits.ptag.poke(17.U)
      wakeup.bits.ptagGeneration.poke(4.U)
      wakeup.valid.poke(true.B)
      dut.clock.step()
      wakeup.valid.poke(false.B)
      dut.io.recoveryPrepareReady.expect(true.B)

      dut.io.recoveryFire.poke(true.B)
      dut.clock.step()
      dut.io.recoveryFire.poke(false.B)
      dut.io.recoveryPrepare.valid.poke(false.B)
      query(dut, 0)
      dut.io.queryState.expect(OooIexIssueSlotState.Free)

      pokeTransaction(dut, stid = 1, ridSlot = 1, transactionId = 11,
        entries = Vector(1), pSource = Some(17 -> 4))
      dut.io.s1.ready.expect(true.B)
      dut.clock.step()
      dut.io.s1.valid.poke(false.B)
      dut.clock.step()
      dut.clock.step()
      query(dut, 1)
      dut.io.queryState.expect(OooIexIssueSlotState.ResidentS3)
      dut.io.queryPickable.expect(true.B)

      // The complementary positive case: when the killed producer still owns
      // the exact captured identity at apply, its retained ready bit clears.
      pokeTransaction(dut, stid = 0, ridSlot = 0, transactionId = 12,
        entries = Vector(0), pDestination = Some(18 -> 2))
      dut.io.s1.ready.expect(true.B)
      dut.clock.step()
      dut.io.s1.valid.poke(false.B)
      dut.clock.step()
      dut.clock.step()

      wakeup.bits.poke(0.U.asTypeOf(wakeup.bits))
      wakeup.bits.stid.poke(0.U)
      wakeup.bits.epoch.poke(6.U)
      wakeup.bits.operandClass.poke(OperandClass.P)
      wakeup.bits.ptag.poke(18.U)
      wakeup.bits.ptagGeneration.poke(2.U)
      wakeup.valid.poke(true.B)
      dut.clock.step()
      wakeup.valid.poke(false.B)

      pokeRecovery(dut, stid = 0, ridSlot = 0, oldMembers = 1,
        survivingMembers = 0)
      waitForPrepared(dut, {
        query(dut, 1)
        dut.io.queryPickable.expect(true.B)
      })
      dut.io.recoveryFire.poke(true.B)
      dut.clock.step()
      dut.io.recoveryFire.poke(false.B)
      dut.io.recoveryPrepare.valid.poke(false.B)

      pokeTransaction(dut, stid = 1, ridSlot = 1, transactionId = 13,
        entries = Vector(0), pSource = Some(18 -> 2))
      dut.io.s1.ready.expect(true.B)
      dut.clock.step()
      dut.io.s1.valid.poke(false.B)
      dut.clock.step()
      dut.clock.step()
      query(dut, 0)
      dut.io.queryState.expect(OooIexIssueSlotState.ResidentS3)
      dut.io.queryPickable.expect(false.B)
    }
  }

  test("scans a non-default two-entry bank slice with exact latency") {
    val wideScan = p.copy(
      iqEntriesPerBank = 4,
      iexRecoveryScanEntriesPerBankPerCycle = 2)
    simulate(new OooIexIssue(wideScan)) { dut =>
      clear(dut)
      pokeTransaction(dut, stid = 0, ridSlot = 0, transactionId = 20,
        entries = Vector(2))
      dut.io.s1.ready.expect(true.B)
      dut.clock.step()
      dut.io.s1.valid.poke(false.B)
      dut.clock.step()
      query(dut, 2)
      dut.io.queryState.expect(OooIexIssueSlotState.BoundS2)

      pokeRecovery(dut, stid = 0, ridSlot = 0, oldMembers = 1,
        survivingMembers = 0)
      waitForPrepared(dut, {
        query(dut, 2)
        dut.io.queryState.expect(OooIexIssueSlotState.BoundS2)
      }, wideScan)
      dut.io.recoveryPrepared.boundKilled.expect(1.U)
      dut.io.recoveryFire.poke(true.B)
      dut.clock.step()
      dut.io.recoveryFire.poke(false.B)
      dut.io.recoveryPrepare.valid.poke(false.B)
      query(dut, 2)
      dut.io.queryState.expect(OooIexIssueSlotState.Free)
    }
  }
}
