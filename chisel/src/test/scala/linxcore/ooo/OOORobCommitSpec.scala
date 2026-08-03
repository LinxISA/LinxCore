package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.params.{CoreParams, ParamProfiles}
import linxcore.top.interface._
import org.scalatest.funsuite.AnyFunSuite

class OOORobCommitSpec extends AnyFunSuite with ChiselSim {
  private def params(width: Int, stids: Int = 1): CoreParams = {
    val base = ParamProfiles.forWidth(width)
    val groups = Iterator.iterate(1)(_ * 2).dropWhile(_ < math.max(4, width)).next()
    base.copy(
      ooo = base.ooo.copy(
        stidCount = stids,
        robGroupsPerStid = groups,
        maxInstructionsPerRobGroup = 2,
        robBankCount = groups,
        brobEntriesPerStid = 4,
        retireWidth = base.widths.retireWidth,
        gprPhysRegs = if (stids == 1 && width <= 4) 32 else 64,
        gprMapQDepthPerStid = if (stids == 1 && width <= 4) 32 else 64,
        tPhysRegs = math.max(8, groups * 2),
        uPhysRegs = math.max(8, groups * 2),
        tuMapQDepthPerStid = math.max(16, groups * 2)))
  }

  private def clearRob(dut: ROB): Unit = {
    dut.io.prepare.valid.poke(false.B)
    dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
    dut.io.publicationTransactionBase.poke(0.U)
    dut.io.brobPrepared.poke(0.U.asTypeOf(dut.io.brobPrepared))
    dut.io.publishFire.poke(false.B)
    dut.io.completion.valid.poke(false.B)
    dut.io.completion.bits.poke(0.U.asTypeOf(dut.io.completion.bits))
    dut.io.commit.ready.poke(true.B)
    dut.io.commitApply.poke(false.B)
    dut.io.release.valid.poke(false.B)
    dut.io.release.bits.poke(0.U.asTypeOf(dut.io.release.bits))
    dut.io.releaseApply.poke(false.B)
    dut.io.recoveryPrepare.valid.poke(false.B)
    dut.io.recoveryPrepare.bits.poke(0.U.asTypeOf(dut.io.recoveryPrepare.bits))
    dut.io.recoveryApply.valid.poke(false.B)
    dut.io.recoveryApply.bits.poke(0.U.asTypeOf(dut.io.recoveryApply.bits))
    dut.io.recoveryAbort.valid.poke(false.B)
    dut.io.recoveryAbort.bits.poke(0.U.asTypeOf(dut.io.recoveryAbort.bits))
    dut.io.recoveryCandidate.foreach { candidate =>
      candidate.valid.poke(false.B)
      candidate.bits.poke(0.U.asTypeOf(candidate.bits))
    }
  }

  private def clearCommitControlExtensions(dut: CommitControl): Unit = {
    dut.io.residentHeads.foreach(_.poke(0.U.asTypeOf(dut.io.residentHeads.head)))
    dut.io.recoveryFence.foreach(_.poke(false.B))
    dut.io.pcBufferCommitReady.poke(true.B)
    dut.io.robNoflushReady.valid.poke(false.B)
    dut.io.robNoflushReady.bits.poke(0.U.asTypeOf(dut.io.robNoflushReady.bits))
    dut.io.robNoflush.ready.poke(false.B)
  }

  private def clearBrob(dut: BROB): Unit = {
    dut.io.prepare.valid.poke(false.B)
    dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
    dut.io.robPrepared.poke(0.U.asTypeOf(dut.io.robPrepared))
    dut.io.publishFire.poke(false.B)
    dut.io.release.valid.poke(false.B)
    dut.io.release.bits.poke(0.U.asTypeOf(dut.io.release.bits))
    dut.io.releaseApply.poke(false.B)
    dut.io.recoveryPrepare.valid.poke(false.B)
    dut.io.recoveryPrepare.bits.poke(0.U.asTypeOf(dut.io.recoveryPrepare.bits))
    dut.io.recoveryApply.valid.poke(false.B)
    dut.io.recoveryApply.bits.poke(0.U.asTypeOf(dut.io.recoveryApply.bits))
    dut.io.recoveryAbort.valid.poke(false.B)
    dut.io.recoveryAbort.bits.poke(0.U.asTypeOf(dut.io.recoveryAbort.bits))
  }

  private def bindBrobPrepared(
      dut: BROB,
      count: Int): Seq[RobIdentity] = {
    dut.io.robPrepared.poke(0.U.asTypeOf(dut.io.robPrepared))
    dut.io.robPrepared.count.poke(count.U)
    (0 until count).foreach { laneIndex =>
      dut.io.robPrepared.entries(laneIndex).valid.poke(true.B)
      dut.io.robPrepared.entries(laneIndex).rob.poke(
        dut.io.prepare.bits.entries(laneIndex).uop.decoded.rob.peek())
      dut.io.robPrepared.entries(laneIndex).rob.bid.poke(
        dut.io.prepared.entries(laneIndex).bid.peek())
      dut.io.robPrepared.entries(laneIndex).rob.brobGeneration.poke(
        dut.io.prepared.entries(laneIndex).brobGeneration.peek())
    }
    (0 until count).map(laneIndex =>
      dut.io.robPrepared.entries(laneIndex).rob.peek())
  }

  private def lane(
      group: D3RenameGroup,
      lane: Int,
      id: Int,
      rid: Int,
      member: Int,
      early: Boolean = false,
      boundary: Boolean = false,
      blockStart: Boolean = false,
      blockStop: Boolean = false,
      secondDest: Boolean = false,
      groupCount: Int = -1,
      stid: Int = 0): Unit = {
    group.count.poke((lane + 1).U)
    group.groupCount.poke((if (groupCount >= 0) groupCount else lane + 1).U)
    group.memoryOrder.valid.poke(true.B)
    group.memoryOrder.stid.poke(stid.U)
    group.memoryOrder.count.poke((lane + 1).U)
    if (rid < group.groups.length) {
      group.groups(rid).valid.poke(true.B)
      group.groups(rid).peId.poke(1.U)
      group.groups(rid).stid.poke(stid.U)
      group.groups(rid).ridSlot.poke(rid.U)
      group.groups(rid).ridGeneration.poke(0.U)
    }
    val row = group.entries(lane)
    row.uop.decoded.valid.poke(true.B)
    row.uop.decoded.instruction.parent.identity.peId.poke(1.U)
    row.uop.decoded.instruction.parent.identity.stid.poke(stid.U)
    row.uop.decoded.instruction.parent.identity.instructionId.poke(id.U)
    row.uop.decoded.instruction.parent.identity.epoch.poke(3.U)
    row.uop.decoded.rob.peId.poke(1.U)
    row.uop.decoded.rob.stid.poke(stid.U)
    row.uop.decoded.rob.ridSlot.poke(rid.U)
    row.uop.decoded.rob.ridGeneration.poke(0.U)
    row.uop.decoded.rob.memberIndex.poke(member.U)
    row.uop.decoded.blockBoundary.poke(boundary.B)
    row.uop.destinations(0).valid.poke(true.B)
    row.uop.destinations(0).kind.poke(OperandKind.Gpr)
    row.uop.destinations(0).atag.poke(1.U)
    row.history(0).valid.poke(true.B)
    row.history(0).kind.poke(OperandKind.Gpr)
    row.history(0).atag.poke(1.U)
    row.history(0).ptag.poke(((8 + id) % 24).U)
    row.history(0).previousPtag.poke(1.U)
    if (secondDest) {
      row.uop.destinations(1).valid.poke(true.B)
      row.uop.destinations(1).kind.poke(OperandKind.Gpr)
      row.uop.destinations(1).atag.poke(2.U)
      row.history(1).valid.poke(true.B)
      row.history(1).kind.poke(OperandKind.Gpr)
      row.history(1).atag.poke(2.U)
      row.history(1).ptag.poke(((18 + id) % 24).U)
      row.history(1).previousPtag.poke(2.U)
    }
    row.earlyRobComplete.poke(early.B)
    row.residentBound.poke(false.B)
    row.brobBound.poke(false.B)
    row.blockStart.poke(blockStart.B)
    row.blockStop.poke(blockStop.B)
    row.uop.decoded.immediateValid.poke(blockStart.B)
    row.uop.decoded.immediate.poke((if (blockStop) 1 else 0).U)
  }

  private def publish(dut: ROB, count: Int): Seq[RobIdentity] = {
    bindBrobPrepared(dut, count)
    dut.io.prepare.valid.poke(true.B)
    dut.io.prepare.ready.expect(true.B)
    (0 until count).foreach { lane =>
      dut.io.prepared.entries(lane).valid.expect(true.B)
    }
    val ids = (0 until count).map(lane => dut.io.prepared.entries(lane).rob.peek())
    dut.io.publishFire.poke(true.B)
    dut.clock.step()
    dut.io.prepare.valid.poke(false.B)
    dut.io.publishFire.poke(false.B)
    ids
  }

  private def bindBrobPrepared(dut: ROB, count: Int): Unit = {
    dut.io.brobPrepared.poke(0.U.asTypeOf(dut.io.brobPrepared))
    dut.io.brobPrepared.count.poke(count.U)
    (0 until count).foreach { laneIndex =>
      val row = dut.io.prepare.bits.entries(laneIndex)
      val continuesPreviousBlock = laneIndex > 0 &&
        !row.blockStart.peek().litToBoolean
      dut.io.brobPrepared.entries(laneIndex).valid.poke(true.B)
      dut.io.brobPrepared.entries(laneIndex).stid.poke(
        row.uop.decoded.rob.stid.peek())
      dut.io.brobPrepared.entries(laneIndex).bid.poke(
        if (continuesPreviousBlock)
          dut.io.brobPrepared.entries(laneIndex - 1).bid.peek()
        else row.uop.decoded.rob.bid.peek())
      dut.io.brobPrepared.entries(laneIndex).brobGeneration.poke(
        if (continuesPreviousBlock)
          dut.io.brobPrepared.entries(laneIndex - 1).brobGeneration.peek()
        else row.uop.decoded.rob.brobGeneration.peek())
      dut.io.brobPrepared.entries(laneIndex).allocated.poke(
        row.blockStart.peek())
    }
  }

  private def complete(dut: ROB, rob: RobIdentity, accepted: Boolean): Unit = {
    dut.io.completion.bits.rob.poke(rob)
    dut.io.completion.valid.poke(true.B)
    dut.io.completion.ready.expect(true.B)
    dut.clock.step()
    dut.io.completionAccepted.valid.expect(accepted.B)
    dut.io.completionRejected.valid.expect((!accepted).B)
    dut.io.completion.valid.poke(false.B)
  }

  private def retirePreview(dut: ROB): Unit = {
    dut.io.commit.valid.expect(true.B)
    dut.io.commitApply.poke(true.B)
    dut.clock.step()
    dut.io.commitApply.poke(false.B)
  }

  test("ROB publishes grouped D3 prefix, binds exact tails, and rejects stale reuse") {
    simulate(new ROB(params(4))) { dut =>
      clearRob(dut)
      dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
      lane(dut.io.prepare.bits, 0, id = 1, rid = 0, member = 0,
        early = true, groupCount = 1)
      lane(dut.io.prepare.bits, 1, id = 2, rid = 0, member = 1,
        early = true, groupCount = 1)
      val ids = publish(dut, 2)
      assert(ids(0).ridSlot.litValue == 0)
      assert(ids(1).memberIndex.litValue == 1)
      dut.io.ridTailSlot(0).expect(1.U)

      dut.io.commit.valid.expect(true.B)
      dut.io.commit.bits.count.expect(2.U)
      dut.io.commit.bits.entries(0).commit.rob.memberIndex.expect(0.U)
      dut.io.commit.bits.entries(1).commit.rob.memberIndex.expect(1.U)
      retirePreview(dut)
      dut.io.release.valid.poke(true.B)
      dut.io.release.bits.count.poke(2.U)
      dut.io.release.bits.lanes(0).valid.poke(true.B)
      dut.io.release.bits.lanes(0).rob.poke(ids(0))
      dut.io.release.bits.lanes(1).valid.poke(true.B)
      dut.io.release.bits.lanes(1).rob.poke(ids(1))
      dut.clock.step()
      dut.io.release.valid.poke(false.B)
      dut.io.releaseApply.poke(false.B)

      dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
      lane(dut.io.prepare.bits, 0, id = 3, rid = 1, member = 0,
        groupCount = 1)
      val reused = publish(dut, 1).head
      complete(dut, ids.head, accepted = false)
      complete(dut, reused, accepted = true)
    }
  }

  test("ROB exposes NFRDY for the oldest unresolved row after a completed prefix") {
    simulate(new ROB(params(4))) { dut =>
      clearRob(dut)
      dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
      lane(dut.io.prepare.bits, 0, id = 10, rid = 0, member = 0,
        groupCount = 2)
      lane(dut.io.prepare.bits, 1, id = 11, rid = 1, member = 0,
        groupCount = 2)
      dut.io.prepare.bits.entries(1).uop.decoded.uopClass.poke(UopClass.System)
      dut.io.prepare.bits.entries(1).uop.destinations.foreach(_.valid.poke(false.B))
      val ids = publish(dut, 2)

      dut.io.residentHeads(0).valid.expect(false.B,
        "a younger system row is not the resident head")
      complete(dut, ids.head, accepted = true)
      dut.io.residentHeads(0).valid.expect(true.B)
      dut.io.residentHeads(0).rob.expect(ids(1))
      dut.io.residentHeads(0).transactionId.expect(1.U)
      dut.io.residentHeads(0).noflushEligible.expect(true.B)

      complete(dut, ids(1), accepted = true)
      dut.io.residentHeads(0).valid.expect(false.B,
        "a resolved system row cannot remain NFRDY-authorizable")
    }
  }

  test("ROB commits two members from one group before the next group") {
    simulate(new ROB(params(4))) { dut =>
      clearRob(dut)
      lane(dut.io.prepare.bits, 0, id = 30, rid = 0, member = 0,
        early = true, groupCount = 2)
      lane(dut.io.prepare.bits, 1, id = 31, rid = 0, member = 1,
        early = true, groupCount = 2)
      lane(dut.io.prepare.bits, 2, id = 32, rid = 1, member = 0,
        early = true, groupCount = 2)
      publish(dut, 3)
      dut.io.commit.valid.expect(true.B)
      dut.io.commit.bits.count.expect(3.U)
      dut.io.commit.bits.entries(0).commit.rob.ridSlot.expect(0.U)
      dut.io.commit.bits.entries(0).commit.rob.memberIndex.expect(0.U)
      dut.io.commit.bits.entries(1).commit.rob.ridSlot.expect(0.U)
      dut.io.commit.bits.entries(1).commit.rob.memberIndex.expect(1.U)
      dut.io.commit.bits.entries(2).commit.rob.ridSlot.expect(1.U)
    }
  }

  test("ROB commits an eligible STID1 prefix when STID0 has no completed head") {
    simulate(new ROB(params(4, stids = 2))) { dut =>
      clearRob(dut)
      dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
      lane(dut.io.prepare.bits, 0, id = 40, rid = 0, member = 0,
        early = true, groupCount = 1, stid = 1)
      publish(dut, 1)
      dut.io.commit.valid.expect(true.B)
      dut.io.commit.bits.count.expect(1.U)
      dut.io.commit.bits.entries(0).commit.rob.stid.expect(1.U)
    }
  }

  test("ROB commits only the oldest continuous completed prefix and retains under backpressure") {
    simulate(new ROB(params(4))) { dut =>
      clearRob(dut)
      lane(dut.io.prepare.bits, 0, id = 10, rid = 0, member = 0,
        early = true, groupCount = 3)
      lane(dut.io.prepare.bits, 1, id = 11, rid = 1, member = 0,
        groupCount = 3)
      lane(dut.io.prepare.bits, 2, id = 12, rid = 2, member = 0,
        early = true, groupCount = 3)
      val ids = publish(dut, 3)
      dut.io.commit.valid.expect(true.B)
      dut.io.commit.bits.count.expect(1.U)
      dut.io.commit.ready.poke(false.B)
      dut.clock.step(2)
      dut.io.commit.bits.count.expect(1.U)
      dut.io.commit.bits.entries(0).commit.rob.ridSlot
        .expect(ids(0).ridSlot.litValue.U)
      complete(dut, ids(1), accepted = true)
      dut.io.commit.bits.count.expect(1.U)
      dut.io.commit.ready.poke(true.B)
      retirePreview(dut)
      dut.io.commit.bits.count.expect(2.U)
    }
  }

  test("BROB allocates per STID in order and release of one STID does not block another") {
    simulate(new BROB(params(4, stids = 2))) { dut =>
      clearBrob(dut)
      dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
      lane(dut.io.prepare.bits, 0, id = 20, rid = 0, member = 0,
        blockStart = true, groupCount = 1, stid = 0)
      dut.io.prepare.valid.poke(true.B)
      bindBrobPrepared(dut, 1)
      dut.io.prepare.ready.expect(true.B)
      dut.io.prepared.entries(0).bid.expect(0.U)
      dut.io.publishFire.poke(true.B)
      dut.clock.step()
      dut.io.prepare.valid.poke(false.B)
      dut.io.publishFire.poke(false.B)
      dut.io.release.valid.poke(true.B)
      dut.io.release.bits.count.poke(1.U)
      dut.io.release.bits.entries(0).stid.poke(0.U)
      dut.io.release.bits.entries(0).bid.poke(0.U)
      dut.io.release.bits.entries(0).brobGeneration.poke(1.U)
      dut.clock.step()
      dut.io.releaseRejected.valid.expect(true.B)
      dut.io.release.valid.poke(false.B)

      dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
      lane(dut.io.prepare.bits, 0, id = 21, rid = 0, member = 0,
        blockStart = true, groupCount = 1, stid = 1)
      dut.io.prepare.valid.poke(true.B)
      bindBrobPrepared(dut, 1)
      dut.io.prepare.ready.expect(true.B)
      dut.io.prepared.entries(0).bid.expect(0.U)
    }
  }

  test("CommitControl preserves secondary destination history and trap beats interrupt") {
    simulate(new CommitControl(params(4))) { dut =>
      clearCommitControlExtensions(dut)
      dut.io.rob.valid.poke(true.B)
      dut.io.rob.bits.count.poke(1.U)
      dut.io.rob.bits.entries(0).valid.poke(true.B)
      dut.io.rob.bits.entries(0).commit.trap.valid.poke(true.B)
      dut.io.rob.bits.entries(0).commit.trap.kind.poke(TrapKind.Exception)
      dut.io.rob.bits.entries(0).rename.history(0).valid.poke(true.B)
      dut.io.rob.bits.entries(0).rename.history(1).valid.poke(true.B)
      dut.io.interrupts(0).valid.poke(true.B)
      dut.io.interrupts(0).priority.poke(7.U)
      dut.io.interruptBoundaryValid.poke(true.B)
      dut.io.out.ready.poke(false.B)
      dut.io.robReleaseReady.poke(false.B)
      dut.io.renameReleaseReady.poke(false.B)
      dut.io.brobReleaseReady.poke(false.B)
      dut.clock.step()
      dut.io.out.valid.expect(false.B)
      dut.io.robReleaseReady.poke(true.B)
      dut.io.renameReleaseReady.poke(true.B)
      dut.io.brobReleaseReady.poke(true.B)
      dut.clock.step()
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.commit.count.expect(1.U)
      dut.io.out.bits.trap.valid.expect(true.B)
      dut.io.out.bits.trap.kind.expect(TrapKind.Exception)
      dut.io.out.bits.rename.count.expect(1.U)
      dut.io.out.bits.rename.lanes(0).history(1).valid.expect(true.B)
      dut.io.rob.bits.count.poke(0.U)
      dut.io.interrupts(0).valid.poke(false.B)
      dut.io.interruptBoundaryValid.poke(false.B)
      dut.clock.step()
      dut.io.out.bits.commit.count.expect(1.U)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()
      dut.io.out.valid.expect(false.B)
    }
  }

  test("CommitControl tracks an expanding exact ROB prefix before owner readiness") {
    simulate(new CommitControl(params(4))) { dut =>
      clearCommitControlExtensions(dut)
      dut.io.rob.bits.poke(0.U.asTypeOf(dut.io.rob.bits))
      dut.io.rob.valid.poke(true.B)
      dut.io.out.ready.poke(true.B)
      dut.io.robReleaseReady.poke(true.B)
      dut.io.renameReleaseReady.poke(true.B)
      dut.io.brobReleaseReady.poke(false.B)
      dut.io.rob.bits.count.poke(1.U)
      dut.io.rob.bits.entries(0).valid.poke(true.B)
      dut.io.rob.bits.entries(0).commit.rob.peId.poke(1.U)
      dut.io.rob.bits.entries(0).commit.rob.stid.poke(0.U)
      dut.io.rob.bits.entries(0).commit.rob.ridSlot.poke(2.U)
      dut.io.rob.bits.entries(0).commit.rob.ridGeneration.poke(3.U)
      dut.io.rob.bits.entries(0).commit.rob.memberIndex.poke(0.U)
      dut.io.rob.bits.entries(0).commit.rob.residentGeneration.poke(4.U)
      dut.io.rob.bits.entries(0).commit.rob.bid.poke(1.U)
      dut.io.rob.bits.entries(0).commit.rob.brobGeneration.poke(5.U)
      dut.io.rob.bits.entries(0).commit.robGroupLast.poke(true.B)
      dut.clock.step()
      dut.io.out.valid.expect(false.B)

      dut.io.rob.bits.count.poke(2.U)
      dut.io.rob.bits.entries(1).valid.poke(true.B)
      dut.io.rob.bits.entries(1).commit.rob.peId.poke(1.U)
      dut.io.rob.bits.entries(1).commit.rob.stid.poke(0.U)
      dut.io.rob.bits.entries(1).commit.rob.ridSlot.poke(3.U)
      dut.io.rob.bits.entries(1).commit.rob.ridGeneration.poke(6.U)
      dut.io.rob.bits.entries(1).commit.rob.memberIndex.poke(0.U)
      dut.io.rob.bits.entries(1).commit.rob.residentGeneration.poke(7.U)
      dut.io.rob.bits.entries(1).commit.rob.bid.poke(1.U)
      dut.io.rob.bits.entries(1).commit.rob.brobGeneration.poke(5.U)
      dut.io.rob.bits.entries(1).commit.robGroupLast.poke(true.B)
      dut.io.brobReleaseReady.poke(true.B)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.brobRelease.count.expect(2.U)
      dut.io.out.bits.brobRelease.entries(0).expect(
        dut.io.rob.bits.entries(0).commit.rob.peek())
      dut.io.out.bits.brobRelease.entries(1).expect(
        dut.io.rob.bits.entries(1).commit.rob.peek())
      dut.io.out.bits.robRelease.count.expect(2.U)
      dut.io.out.bits.rename.count.expect(2.U)
      for (lane <- 2 until params(4).widths.retireWidth) {
        dut.io.out.bits.robRelease.lanes(lane).valid.expect(false.B)
        dut.io.out.bits.brobRelease.entries(lane).expect(
          0.U.asTypeOf(dut.io.out.bits.brobRelease.entries(lane)))
      }
    }
  }

  test("CommitControl projects the maximum exact retire prefix") {
    val p = params(4)
    simulate(new CommitControl(p)) { dut =>
      clearCommitControlExtensions(dut)
      dut.io.rob.bits.poke(0.U.asTypeOf(dut.io.rob.bits))
      dut.io.rob.valid.poke(true.B)
      dut.io.out.ready.poke(false.B)
      dut.io.robReleaseReady.poke(true.B)
      dut.io.renameReleaseReady.poke(true.B)
      dut.io.brobReleaseReady.poke(true.B)
      dut.io.rob.bits.count.poke(p.widths.retireWidth.U)
      for (lane <- 0 until p.widths.retireWidth) {
        val rob = dut.io.rob.bits.entries(lane).commit.rob
        dut.io.rob.bits.entries(lane).valid.poke(true.B)
        rob.peId.poke(1.U)
        rob.ridSlot.poke(lane.U)
        rob.ridGeneration.poke((lane + 1).U)
        rob.memberIndex.poke((lane % p.ooo.maxInstructionsPerRobGroup).U)
        rob.residentGeneration.poke((lane + 2).U)
        rob.bid.poke(1.U)
        rob.brobGeneration.poke(9.U)
      }
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.brobRelease.count.expect(p.widths.retireWidth.U)
      for (lane <- 0 until p.widths.retireWidth) {
        dut.io.out.bits.brobRelease.entries(lane).expect(
          dut.io.rob.bits.entries(lane).commit.rob.peek())
      }
    }
  }

  test("CommitControl emits exactly one common fire after all owner readiness") {
    simulate(new CommitControl(params(4))) { dut =>
      clearCommitControlExtensions(dut)
      dut.io.rob.valid.poke(true.B)
      dut.io.rob.bits.count.poke(1.U)
      dut.io.rob.bits.entries(0).valid.poke(true.B)
      dut.io.out.ready.poke(true.B)
      dut.io.interruptBoundaryValid.poke(false.B)
      dut.io.robReleaseReady.poke(false.B)
      dut.io.renameReleaseReady.poke(false.B)
      dut.io.brobReleaseReady.poke(false.B)
      dut.io.out.valid.expect(false.B)
      dut.clock.step(2)
      dut.io.out.valid.expect(false.B)
      dut.io.robReleaseReady.poke(true.B)
      dut.clock.step()
      dut.io.out.valid.expect(false.B)
      dut.io.renameReleaseReady.poke(true.B)
      dut.clock.step()
      dut.io.out.valid.expect(false.B)
      dut.io.brobReleaseReady.poke(true.B)
      dut.io.out.valid.expect(true.B)
      dut.clock.step()
      dut.io.out.valid.expect(false.B)
    }
  }

  test("CommitControl zero-lane trap and interrupt bypass ordinary release readiness") {
    simulate(new CommitControl(params(4))) { dut =>
      clearCommitControlExtensions(dut)
      dut.io.rob.valid.poke(true.B)
      dut.io.rob.bits.poke(0.U.asTypeOf(dut.io.rob.bits))
      dut.io.rob.bits.headValid.poke(true.B)
      dut.io.rob.bits.head.stid.poke(0.U)
      dut.io.rob.bits.headTrap.valid.poke(true.B)
      dut.io.rob.bits.headTrap.kind.poke(TrapKind.Exception)
      dut.io.out.ready.poke(true.B)
      dut.io.robReleaseReady.poke(false.B)
      dut.io.renameReleaseReady.poke(false.B)
      dut.io.brobReleaseReady.poke(false.B)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.commit.count.expect(0.U)
      dut.io.out.bits.trap.kind.expect(TrapKind.Exception)
      dut.clock.step()
      dut.io.out.valid.expect(false.B)
      dut.io.rob.valid.poke(false.B)
      dut.clock.step()

      dut.io.rob.valid.poke(true.B)
      dut.io.rob.bits.poke(0.U.asTypeOf(dut.io.rob.bits))
      dut.io.interruptBoundaryValid.poke(true.B)
      dut.io.interruptBoundary.stid.poke(0.U)
      dut.io.interrupts(0).valid.poke(true.B)
      dut.io.interrupts(0).priority.poke(1.U)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.commit.count.expect(0.U)
      dut.io.out.bits.trap.kind.expect(TrapKind.Interrupt)
    }
  }

  test("CommitControl accepts distinct back-to-back previews while ROB valid stays high") {
    simulate(new CommitControl(params(4))) { dut =>
      clearCommitControlExtensions(dut)
      dut.io.rob.valid.poke(true.B)
      dut.io.rob.bits.poke(0.U.asTypeOf(dut.io.rob.bits))
      dut.io.rob.bits.count.poke(1.U)
      dut.io.rob.bits.entries(0).valid.poke(true.B)
      dut.io.rob.bits.entries(0).commit.rob.ridSlot.poke(0.U)
      dut.io.rob.bits.entries(0).commit.rob.memberIndex.poke(0.U)
      dut.io.out.ready.poke(true.B)
      dut.io.robReleaseReady.poke(true.B)
      dut.io.renameReleaseReady.poke(true.B)
      dut.io.brobReleaseReady.poke(true.B)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.commit.entries(0).rob.ridSlot.expect(0.U)
      dut.clock.step()

      dut.io.rob.bits.poke(0.U.asTypeOf(dut.io.rob.bits))
      dut.io.rob.bits.count.poke(1.U)
      dut.io.rob.bits.entries(0).valid.poke(true.B)
      dut.io.rob.bits.entries(0).commit.rob.ridSlot.poke(1.U)
      dut.io.rob.bits.entries(0).commit.rob.memberIndex.poke(0.U)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.commit.entries(0).rob.ridSlot.expect(1.U)
      dut.clock.step()
      dut.io.out.valid.expect(false.B)
    }
  }

  test("CommitControl accepts expanded prefix with the same first ROB identity") {
    simulate(new CommitControl(params(4))) { dut =>
      clearCommitControlExtensions(dut)
      dut.io.rob.valid.poke(true.B)
      dut.io.rob.bits.poke(0.U.asTypeOf(dut.io.rob.bits))
      dut.io.rob.bits.count.poke(1.U)
      dut.io.rob.bits.entries(0).valid.poke(true.B)
      dut.io.rob.bits.entries(0).commit.rob.ridSlot.poke(0.U)
      dut.io.rob.bits.entries(0).commit.rob.memberIndex.poke(0.U)
      dut.io.out.ready.poke(true.B)
      dut.io.robReleaseReady.poke(true.B)
      dut.io.renameReleaseReady.poke(true.B)
      dut.io.brobReleaseReady.poke(true.B)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.commit.count.expect(1.U)
      dut.clock.step()

      dut.io.rob.bits.count.poke(2.U)
      dut.io.rob.bits.entries(0).valid.poke(true.B)
      dut.io.rob.bits.entries(0).commit.rob.ridSlot.poke(0.U)
      dut.io.rob.bits.entries(0).commit.rob.memberIndex.poke(0.U)
      dut.io.rob.bits.entries(1).valid.poke(true.B)
      dut.io.rob.bits.entries(1).commit.rob.ridSlot.poke(1.U)
      dut.io.rob.bits.entries(1).commit.rob.memberIndex.poke(0.U)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.commit.count.expect(2.U)
    }
  }

  test("CommitControl accepts same-head trap changes and ordinary-to-trap transitions") {
    simulate(new CommitControl(params(4))) { dut =>
      clearCommitControlExtensions(dut)
      dut.io.rob.valid.poke(true.B)
      dut.io.rob.bits.poke(0.U.asTypeOf(dut.io.rob.bits))
      dut.io.out.ready.poke(true.B)
      dut.io.robReleaseReady.poke(true.B)
      dut.io.renameReleaseReady.poke(true.B)
      dut.io.brobReleaseReady.poke(true.B)
      dut.io.rob.bits.headValid.poke(true.B)
      dut.io.rob.bits.head.stid.poke(0.U)
      dut.io.rob.bits.head.ridSlot.poke(0.U)
      dut.io.rob.bits.headTrap.valid.poke(true.B)
      dut.io.rob.bits.headTrap.kind.poke(TrapKind.Exception)
      dut.io.rob.bits.headTrap.cause.poke(0x10.U)
      dut.io.rob.bits.headTrap.rob.stid.poke(0.U)
      dut.io.rob.bits.headTrap.rob.ridSlot.poke(0.U)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.trap.cause.expect(0x10.U)
      dut.clock.step()

      dut.io.rob.bits.headTrap.cause.poke(0x11.U)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.trap.cause.expect(0x11.U)
      dut.clock.step()

      dut.io.rob.bits.poke(0.U.asTypeOf(dut.io.rob.bits))
      dut.io.rob.bits.count.poke(1.U)
      dut.io.rob.bits.entries(0).valid.poke(true.B)
      dut.io.rob.bits.entries(0).commit.rob.stid.poke(0.U)
      dut.io.rob.bits.entries(0).commit.rob.ridSlot.poke(0.U)
      dut.io.rob.bits.entries(0).commit.rob.memberIndex.poke(0.U)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.commit.count.expect(1.U)
      dut.clock.step()

      dut.io.rob.bits.count.poke(0.U)
      dut.io.rob.bits.headValid.poke(true.B)
      dut.io.rob.bits.head.stid.poke(0.U)
      dut.io.rob.bits.head.ridSlot.poke(0.U)
      dut.io.rob.bits.headTrap.valid.poke(true.B)
      dut.io.rob.bits.headTrap.kind.poke(TrapKind.Exception)
      dut.io.rob.bits.headTrap.cause.poke(0x12.U)
      dut.io.rob.bits.headTrap.rob.stid.poke(0.U)
      dut.io.rob.bits.headTrap.rob.ridSlot.poke(0.U)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.commit.count.expect(0.U)
      dut.io.out.bits.trap.cause.expect(0x12.U)
    }
  }

  test("CommitControl holds a backpressured transaction and then accepts advanced preview") {
    simulate(new CommitControl(params(4))) { dut =>
      clearCommitControlExtensions(dut)
      dut.io.rob.valid.poke(true.B)
      dut.io.rob.bits.poke(0.U.asTypeOf(dut.io.rob.bits))
      dut.io.rob.bits.count.poke(1.U)
      dut.io.rob.bits.entries(0).valid.poke(true.B)
      dut.io.rob.bits.entries(0).commit.rob.ridSlot.poke(0.U)
      dut.io.rob.bits.entries(0).commit.rob.memberIndex.poke(0.U)
      dut.io.out.ready.poke(false.B)
      dut.io.robReleaseReady.poke(true.B)
      dut.io.renameReleaseReady.poke(true.B)
      dut.io.brobReleaseReady.poke(true.B)
      dut.clock.step()

      dut.io.rob.bits.count.poke(2.U)
      dut.io.rob.bits.entries(1).valid.poke(true.B)
      dut.io.rob.bits.entries(1).commit.rob.ridSlot.poke(1.U)
      dut.io.rob.bits.entries(1).commit.rob.memberIndex.poke(0.U)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.commit.count.expect(1.U)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.commit.count.expect(2.U)
    }
  }

  test("CommitControl does not repeat when only inactive lanes change") {
    simulate(new CommitControl(params(4))) { dut =>
      clearCommitControlExtensions(dut)
      dut.io.rob.valid.poke(true.B)
      dut.io.rob.bits.poke(0.U.asTypeOf(dut.io.rob.bits))
      dut.io.rob.bits.count.poke(1.U)
      dut.io.rob.bits.entries(0).valid.poke(true.B)
      dut.io.rob.bits.entries(0).commit.rob.ridSlot.poke(0.U)
      dut.io.rob.bits.entries(0).commit.rob.memberIndex.poke(0.U)
      dut.io.rob.bits.entries(1).commit.rob.ridSlot.poke(1.U)
      dut.io.out.ready.poke(true.B)
      dut.io.robReleaseReady.poke(true.B)
      dut.io.renameReleaseReady.poke(true.B)
      dut.io.brobReleaseReady.poke(true.B)
      dut.io.out.valid.expect(true.B)
      dut.clock.step()

      dut.io.rob.bits.entries(1).commit.rob.ridSlot.poke(2.U)
      dut.io.rob.bits.entries(1).rename.history(0).valid.poke(true.B)
      dut.io.rob.bits.entries(1).rename.history(0).ptag.poke(9.U)
      dut.io.out.valid.expect(false.B)
    }
  }

  test("CommitControl requires exact NFRDY and never repeats a resident head") {
    simulate(new CommitControl(params(4, stids = 2))) { dut =>
      dut.io.rob.valid.poke(false.B)
      dut.io.rob.bits.poke(0.U.asTypeOf(dut.io.rob.bits))
      dut.io.interrupts.foreach(_.poke(0.U.asTypeOf(dut.io.interrupts.head)))
      dut.io.interruptBoundaryValid.poke(false.B)
      dut.io.interruptBoundary.poke(0.U.asTypeOf(dut.io.interruptBoundary))
      dut.io.robReleaseReady.poke(true.B)
      dut.io.renameReleaseReady.poke(true.B)
      dut.io.brobReleaseReady.poke(true.B)
      dut.io.out.ready.poke(true.B)
      clearCommitControlExtensions(dut)

      def pokeHead(stid: Int, transactionId: Int, instructionId: Int,
          ridSlot: Int): Unit = {
        val head = dut.io.residentHeads(stid)
        head.valid.poke(true.B)
        head.noflushEligible.poke(true.B)
        head.transactionId.poke(transactionId.U)
        head.instruction.peId.poke(3.U)
        head.instruction.stid.poke(stid.U)
        head.instruction.instructionId.poke(instructionId.U)
        head.rob.peId.poke(3.U)
        head.rob.stid.poke(stid.U)
        head.rob.ridSlot.poke(ridSlot.U)
        head.rob.ridGeneration.poke(2.U)
        head.rob.memberIndex.poke(0.U)
      }
      def pokeNfrdy(stid: Int, transactionId: Int, instructionId: Int,
          ridSlot: Int): Unit = {
        val ready = dut.io.robNoflushReady
        ready.valid.poke(true.B)
        ready.bits.transactionId.poke(transactionId.U)
        ready.bits.instruction.peId.poke(3.U)
        ready.bits.instruction.stid.poke(stid.U)
        ready.bits.instruction.instructionId.poke(instructionId.U)
        ready.bits.rob.peId.poke(3.U)
        ready.bits.rob.stid.poke(stid.U)
        ready.bits.rob.ridSlot.poke(ridSlot.U)
        ready.bits.rob.ridGeneration.poke(2.U)
        ready.bits.rob.memberIndex.poke(0.U)
      }

      pokeHead(0, 0x123, 0x456, 1)
      dut.io.robNoflush.valid.expect(false.B,
        "ROB shape alone is not legality/drain authority")
      pokeNfrdy(0, 0x122, 0x456, 1)
      dut.io.robNoflush.valid.expect(false.B)
      dut.io.robNoflushReady.ready.expect(true.B,
        "a stale NFRDY proof must drain without granting authorization")
      pokeNfrdy(0, 0x123, 0x456, 1)
      dut.io.robNoflush.valid.expect(true.B)
      dut.io.robNoflush.bits.transactionId.expect(0x123.U)
      dut.io.robNoflush.bits.instruction.instructionId.expect(0x456.U)
      dut.io.robNoflush.bits.rob.ridSlot.expect(1.U)
      val retained = dut.io.robNoflush.bits.peek()
      dut.clock.step(2)
      dut.io.robNoflush.valid.expect(true.B)
      dut.io.robNoflush.bits.expect(retained)

      dut.io.recoveryFence(1).poke(true.B)
      dut.io.robNoflush.valid.expect(true.B)
      dut.io.recoveryFence(1).poke(false.B)
      dut.io.recoveryFence(0).poke(true.B)
      dut.io.robNoflush.valid.expect(false.B)
      dut.io.robNoflushReady.ready.expect(false.B,
        "recovery fencing must not consume an exact unaccepted NFRDY proof")
      dut.clock.step()
      dut.io.recoveryFence(0).poke(false.B)
      dut.io.robNoflush.valid.expect(true.B)

      dut.io.residentHeads(0).noflushEligible.poke(false.B)
      dut.io.robNoflush.valid.expect(false.B,
        "a stalled authorization must not outlive head eligibility")
      dut.io.residentHeads(0).noflushEligible.poke(true.B)
      dut.io.robNoflush.valid.expect(true.B)

      dut.io.robNoflush.ready.poke(true.B)
      dut.clock.step()
      dut.io.robNoflush.valid.expect(false.B)

      pokeHead(1, 0x220, 0x550, 2)
      pokeNfrdy(1, 0x220, 0x550, 2)
      dut.io.robNoflush.valid.expect(true.B)
      dut.io.robNoflush.bits.transactionId.expect(0x220.U)
      dut.clock.step()
      dut.io.robNoflush.valid.expect(false.B)

      pokeNfrdy(0, 0x123, 0x456, 1)
      dut.io.robNoflush.valid.expect(false.B,
        "an accepted member remains suppressed after another STID wins")

      dut.io.residentHeads(0).valid.poke(false.B)
      dut.clock.step()
      pokeHead(0, 0x124, 0x457, 3)
      pokeNfrdy(0, 0x124, 0x457, 3)
      dut.io.robNoflush.valid.expect(true.B)
    }
  }

  test("ROB release preflight does not mutate before common commit apply") {
    simulate(new ROB(params(4))) { dut =>
      clearRob(dut)
      lane(dut.io.prepare.bits, 0, id = 80, rid = 0, member = 0,
        early = true, groupCount = 1)
      val id = publish(dut, 1).head
      dut.io.commit.ready.poke(true.B)
      dut.io.commit.valid.expect(true.B)
      dut.clock.step()
      dut.io.release.valid.poke(true.B)
      dut.io.release.bits.count.poke(1.U)
      dut.io.release.bits.lanes(0).valid.poke(true.B)
      dut.io.release.bits.lanes(0).rob.poke(id)
      dut.io.releaseReady.expect(true.B)
      dut.clock.step()
      dut.io.commit.valid.expect(true.B)
      dut.io.releaseReady.expect(true.B)
      dut.io.commitApply.poke(true.B)
      dut.io.releaseApply.poke(true.B)
      dut.clock.step()
      dut.io.commitApply.poke(false.B)
      dut.io.releaseApply.poke(false.B)
      dut.io.commit.valid.expect(false.B)
      dut.io.releaseReady.expect(false.B)
    }
  }

  test("ROB recovery clears retained commit preview over killed suffix") {
    simulate(new ROB(params(4))) { dut =>
      clearRob(dut)
      lane(dut.io.prepare.bits, 0, id = 100, rid = 0, member = 0,
        early = true, groupCount = 2)
      lane(dut.io.prepare.bits, 1, id = 101, rid = 1, member = 0,
        early = true, groupCount = 2)
      val ids = publish(dut, 2)
      dut.io.commit.ready.poke(false.B)
      dut.io.commit.valid.expect(true.B)
      dut.io.commit.bits.count.expect(2.U)
      dut.clock.step()
      dut.io.recoveryPrepare.valid.poke(true.B)
      dut.io.recoveryPrepare.bits.transactionId.poke(0x71.U)
      dut.io.recoveryPrepare.bits.phase.poke(RecoveryPhase.Prepare)
      dut.io.recoveryPrepare.bits.cause.poke(RecoveryCause.Branch)
      dut.io.recoveryPrepare.bits.trigger.poke(ids(0))
      dut.io.recoveryPrepare.ready.expect(true.B)
      dut.clock.step()
      dut.io.recoveryPrepare.valid.poke(false.B)
      dut.io.recoveryApply.valid.poke(true.B)
      dut.io.recoveryApply.bits.poke(dut.io.recoveryPrepared.bits.peek())
      dut.io.recoveryApply.bits.phase.poke(RecoveryPhase.Apply)
      dut.clock.step()
      dut.io.recoveryApply.valid.poke(false.B)
      dut.io.commit.ready.poke(true.B)
      dut.io.commit.valid.expect(true.B)
      dut.io.commit.bits.count.expect(1.U)
      dut.io.commit.bits.entries(0).commit.rob.ridSlot.expect(0.U)
      dut.io.commit.bits.entries(0).commit.rob.memberIndex.expect(0.U)
      retirePreview(dut)
      dut.io.release.valid.poke(true.B)
      dut.io.release.bits.count.poke(1.U)
      dut.io.release.bits.lanes(0).valid.poke(true.B)
      dut.io.release.bits.lanes(0).rob.poke(ids(0))
      dut.io.releaseReady.expect(true.B)
    }
  }

  test("ROB rejects malformed D3 shape and consumes exact BROB bindings") {
    simulate(new ROB(params(4))) { dut =>
      clearRob(dut)
      dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
      lane(dut.io.prepare.bits, 0, id = 50, rid = 1, member = 0,
        early = true, groupCount = 1)
      dut.io.prepare.valid.poke(true.B)
      dut.io.prepare.ready.expect(false.B)
      dut.io.prepare.bits.entries(0).uop.decoded.rob.ridSlot.poke(0.U)
      dut.io.prepare.bits.entries(0).uop.decoded.rob.memberIndex.poke(1.U)
      dut.io.prepare.ready.expect(false.B)
      dut.io.prepare.bits.entries(0).uop.decoded.rob.memberIndex.poke(0.U)
      dut.io.prepare.bits.entries(0).brobBound.poke(true.B)
      dut.io.brobPrepared.count.poke(1.U)
      dut.io.brobPrepared.entries(0).valid.poke(true.B)
      dut.io.brobPrepared.entries(0).stid.poke(0.U)
      dut.io.brobPrepared.entries(0).bid.poke(3.U)
      dut.io.brobPrepared.entries(0).brobGeneration.poke(2.U)
      dut.io.prepare.ready.expect(false.B)
      dut.io.prepare.bits.entries(0).uop.decoded.rob.bid.poke(3.U)
      dut.io.prepare.bits.entries(0).uop.decoded.rob.brobGeneration.poke(2.U)
      dut.io.prepare.bits.entries(0).residentBound.poke(true.B)
      dut.io.prepare.bits.entries(0).uop.decoded.rob.residentGeneration.poke(1.U)
      dut.io.prepare.ready.expect(false.B)
      dut.io.prepare.bits.entries(0).uop.decoded.rob.residentGeneration.poke(0.U)
      dut.io.prepare.ready.expect(true.B)
      dut.io.prepared.entries(0).rob.bid.expect(3.U)
      dut.io.prepared.entries(0).rob.brobGeneration.expect(2.U)
      dut.io.brobPrepared.entries(0).stid.poke(1.U)
      dut.io.prepare.ready.expect(false.B)
    }
  }

  test("ROB release validates the whole prefix before mutation") {
    simulate(new ROB(params(4))) { dut =>
      clearRob(dut)
      lane(dut.io.prepare.bits, 0, id = 60, rid = 0, member = 0,
        early = true, groupCount = 1)
      val id = publish(dut, 1).head
      retirePreview(dut)
      dut.io.release.valid.poke(true.B)
      dut.io.release.bits.count.poke(1.U)
      dut.io.release.bits.lanes(0).valid.poke(false.B)
      dut.io.release.bits.lanes(0).rob.poke(id)
      dut.io.releaseReady.expect(false.B)
      dut.io.releaseApply.poke(true.B)
      dut.clock.step()
      dut.io.ridTailSlot(0).expect(1.U)
      dut.io.release.bits.lanes(0).valid.poke(true.B)
      dut.io.releaseReady.expect(true.B)
      dut.clock.step()
      dut.io.ridHeadSlot(0).expect(1.U)
    }
  }

  test("BROB waits for final block member and validates recovery prepare") {
    simulate(new BROB(params(4))) { dut =>
      clearBrob(dut)
      lane(dut.io.prepare.bits, 0, id = 70, rid = 0, member = 0,
        blockStart = true, blockStop = false, groupCount = 1)
      lane(dut.io.prepare.bits, 1, id = 71, rid = 0, member = 1,
        blockStart = false, blockStop = true, groupCount = 1)
      dut.io.prepare.valid.poke(true.B)
      bindBrobPrepared(dut, 2)
      dut.io.prepare.ready.expect(true.B)
      dut.io.publishFire.poke(true.B)
      dut.clock.step()
      dut.io.prepare.valid.poke(false.B)
      dut.io.publishFire.poke(false.B)
      dut.io.release.valid.poke(true.B)
      dut.io.release.bits.count.poke(1.U)
      dut.io.release.bits.entries(0).stid.poke(0.U)
      dut.io.release.bits.entries(0).bid.poke(0.U)
      dut.io.release.bits.entries(0).brobGeneration.poke(0.U)
      dut.io.release.bits.entries(0).ridSlot.poke(0.U)
      dut.io.release.bits.entries(0).memberIndex.poke(0.U)
      dut.io.releaseReady.expect(false.B)
      dut.io.release.bits.count.poke(2.U)
      dut.io.release.bits.entries(1).stid.poke(0.U)
      dut.io.release.bits.entries(1).bid.poke(0.U)
      dut.io.release.bits.entries(1).brobGeneration.poke(0.U)
      dut.io.release.bits.entries(1).ridSlot.poke(0.U)
      dut.io.release.bits.entries(1).memberIndex.poke(1.U)
      dut.io.releaseReady.expect(true.B)
      dut.io.recoveryPrepare.valid.poke(true.B)
      dut.io.recoveryPrepare.bits.phase.poke(RecoveryPhase.Prepare)
      dut.io.recoveryPrepare.bits.firstKilledValid.poke(true.B)
      dut.io.recoveryPrepare.bits.firstKilled.stid.poke(0.U)
      dut.io.recoveryPrepare.bits.firstKilled.bid.poke(0.U)
      dut.io.recoveryPrepare.bits.firstKilled.brobGeneration.poke(1.U)
      dut.io.recoveryPrepare.ready.expect(false.B)
    }
  }

  test("ROB rejects unsupported bank geometry and maps nontrivial bank profiles") {
    def exercise(width: Int, banks: Int, sameBankSlot: Int): Unit = {
      val base = params(width).copy(ooo = params(width).ooo.copy(
        robGroupsPerStid = 8,
        robBankCount = banks))
      simulate(new ROB(base)) { dut =>
        clearRob(dut)
        def publishOne(idValue: Int, rid: Int, early: Boolean = false): RobIdentity = {
          dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
          lane(dut.io.prepare.bits, 0, id = idValue, rid = rid, member = 0,
            early = early, groupCount = 1)
          bindBrobPrepared(dut, 1)
          dut.io.prepare.valid.poke(true.B)
          dut.io.prepare.ready.expect(true.B)
          val rob = dut.io.prepared.entries(0).rob.peek()
          dut.io.publishFire.poke(true.B)
          dut.clock.step()
          dut.io.prepare.valid.poke(false.B)
          dut.io.publishFire.poke(false.B)
          rob
        }
        val first = publishOne(90, 0, early = true)
        val middle = (1 until sameBankSlot).map(rid => publishOne(90 + rid, rid))
        val sameBank = publishOne(100 + sameBankSlot, sameBankSlot)
        complete(dut, sameBank, accepted = true)
        retirePreview(dut)
        dut.io.release.valid.poke(true.B)
        dut.io.release.bits.count.poke(1.U)
        dut.io.release.bits.lanes(0).valid.poke(true.B)
        dut.io.release.bits.lanes(0).rob.poke(first)
        dut.io.releaseReady.expect(true.B)
        dut.io.releaseApply.poke(true.B)
        dut.clock.step()
        dut.io.release.valid.poke(false.B)
        dut.io.releaseApply.poke(false.B)
        complete(dut, first, accepted = false)
        complete(dut, sameBank, accepted = false)

        dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
        lane(dut.io.prepare.bits, 0, id = 130, rid = sameBankSlot,
          member = 0, groupCount = 1)
        dut.io.prepare.valid.poke(true.B)
        dut.io.prepare.ready.expect(false.B)
        dut.io.prepare.valid.poke(false.B)
        middle.foreach(_ => ())
        ((sameBankSlot + 1) until 8).foreach { rid =>
          publishOne(140 + rid, rid)
        }
        dut.io.ridTailSlot(0).expect(0.U)
        dut.io.ridTailGeneration(0).expect(1.U)
        dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
        lane(dut.io.prepare.bits, 0, id = 180, rid = 0, member = 0,
          groupCount = 1)
        dut.io.prepare.bits.entries(0).uop.decoded.rob.ridGeneration.poke(1.U)
        bindBrobPrepared(dut, 1)
        dut.io.prepare.valid.poke(true.B)
        dut.io.prepare.ready.expect(true.B)
        dut.io.prepared.entries(0).rob.ridSlot.expect(0.U)
        dut.io.prepared.entries(0).rob.ridGeneration.expect(1.U)
        dut.io.prepared.entries(0).rob.residentGeneration.expect(1.U)
      }
    }
    exercise(width = 2, banks = 2, sameBankSlot = 2)
    exercise(width = 4, banks = 4, sameBankSlot = 4)
    val base = params(2).copy(ooo = params(2).ooo.copy(
      robGroupsPerStid = 8,
      robBankCount = 2))
    simulate(new ROB(base)) { dut =>
      clearRob(dut)
      lane(dut.io.prepare.bits, 0, id = 90, rid = 0, member = 0,
        early = true, groupCount = 1)
      val id = publish(dut, 1).head
      retirePreview(dut)
      dut.io.release.valid.poke(true.B)
      dut.io.release.bits.count.poke(1.U)
      dut.io.release.bits.lanes(0).valid.poke(true.B)
      dut.io.release.bits.lanes(0).rob.poke(id)
      dut.io.releaseReady.expect(true.B)
    }
    assertThrows[IllegalArgumentException] {
      simulate(new ROB(base.copy(ooo = base.ooo.copy(
        robGroupsPerStid = 6,
        robBankCount = 4)))) { _ => }
    }
  }

  test("ROB elaborates W2 W4 W6 and W8 without fixed W4 bank assumptions") {
    Seq(2, 4, 6, 8).foreach { width =>
      simulate(new ROB(params(width))) { dut =>
        clearRob(dut)
        dut.io.ridTailSlot(0).expect(0.U)
      }
    }
  }

  test("ROB rejects unsafe recovery age token widths") {
    val unsafe = params(2).copy(transactionIdWidth = 4, ooo = params(2).ooo.copy(
      stidCount = 2,
      robGroupsPerStid = 4,
      maxInstructionsPerRobGroup = 2,
      robBankCount = 4,
      gprPhysRegs = 64,
      gprMapQDepthPerStid = 64,
      tPhysRegs = 16,
      uPhysRegs = 16,
      tuMapQDepthPerStid = 16))
    assertThrows[IllegalArgumentException] {
      simulate(new ROB(unsafe)) { _ => }
    }
    assertThrows[IllegalArgumentException] {
      simulate(new RecoveryControl(unsafe, targetCount = 1)) { _ => }
    }
  }
}
