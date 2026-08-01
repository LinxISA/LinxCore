package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.params.{CoreParams, ParamProfiles}
import linxcore.top.interface._
import org.scalatest.funsuite.AnyFunSuite

class OOORecoverySpec extends AnyFunSuite with ChiselSim {
  private def params: CoreParams = {
    val base = ParamProfiles.W4
    base.copy(
      ooo = base.ooo.copy(
        stidCount = 2,
        robGroupsPerStid = 4,
        maxInstructionsPerRobGroup = 2,
        robBankCount = 4,
        brobEntriesPerStid = 4,
        retireWidth = base.widths.retireWidth,
        gprPhysRegs = 64,
        gprMapQDepthPerStid = 64,
        tPhysRegs = 8,
        uPhysRegs = 8,
        tuMapQDepthPerStid = 8))
  }

  private def clearRob(dut: ROB): Unit = {
    dut.io.prepare.valid.poke(false.B)
    dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
    dut.io.publishFire.poke(false.B)
    dut.io.completion.valid.poke(false.B)
    dut.io.completion.bits.poke(0.U.asTypeOf(dut.io.completion.bits))
    dut.io.commit.ready.poke(false.B)
    dut.io.commitApply.poke(false.B)
    dut.io.release.valid.poke(false.B)
    dut.io.release.bits.poke(0.U.asTypeOf(dut.io.release.bits))
    dut.io.releaseApply.poke(false.B)
    dut.io.brobPrepared.poke(0.U.asTypeOf(dut.io.brobPrepared))
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

  private def lane(
      group: D3RenameGroup,
      lane: Int,
      id: Int,
      rid: Int,
      groupCount: Int = -1,
      stid: Int = 0,
      member: Int = 0,
      blockStart: Boolean = false,
      blockStop: Boolean = false): Unit = {
    group.count.poke((lane + 1).U)
    group.groupCount.poke((if (groupCount >= 0) groupCount else lane + 1).U)
    group.groups(lane).valid.poke(true.B)
    group.groups(lane).peId.poke(1.U)
    group.groups(lane).stid.poke(stid.U)
    group.groups(lane).ridSlot.poke(rid.U)
    group.groups(lane).ridGeneration.poke(0.U)
    val row = group.entries(lane)
    row.uop.decoded.valid.poke(true.B)
    row.uop.decoded.instruction.parent.identity.peId.poke(1.U)
    row.uop.decoded.instruction.parent.identity.stid.poke(stid.U)
    row.uop.decoded.instruction.parent.identity.instructionId.poke(id.U)
    row.uop.decoded.instruction.parent.identity.epoch.poke(1.U)
    row.uop.decoded.rob.peId.poke(1.U)
    row.uop.decoded.rob.stid.poke(stid.U)
    row.uop.decoded.rob.ridSlot.poke(rid.U)
    row.uop.decoded.rob.ridGeneration.poke(0.U)
    row.uop.decoded.rob.memberIndex.poke(member.U)
    row.uop.decoded.rob.bid.poke(rid.U)
    row.uop.decoded.rob.brobGeneration.poke(0.U)
    row.blockStart.poke(blockStart.B)
    row.blockStop.poke(blockStop.B)
    row.history(0).valid.poke(true.B)
  }

  private def publish(dut: ROB, count: Int): Seq[RobIdentity] = {
    dut.io.prepare.valid.poke(true.B)
    dut.io.prepare.ready.expect(true.B)
    val ids = (0 until count).map(lane => dut.io.prepared.entries(lane).rob.peek())
    dut.io.publishFire.poke(true.B)
    dut.clock.step()
    dut.io.prepare.valid.poke(false.B)
    dut.io.publishFire.poke(false.B)
    ids
  }

  private def clearRecoveryControl(dut: RecoveryControl): Unit = {
    dut.io.events.foreach { event =>
      event.valid.poke(false.B)
      event.bits.poke(0.U.asTypeOf(event.bits))
    }
    dut.io.interrupts.foreach(_.poke(0.U.asTypeOf(dut.io.interrupts(0))))
    dut.io.interruptBoundaryValid.poke(false.B)
    dut.io.interruptBoundary.poke(0.U.asTypeOf(dut.io.interruptBoundary))
    dut.io.abort.poke(false.B)
    dut.io.robCandidateStatus.foreach { status =>
      status.valid.poke(false.B)
      status.bits.poke(0.U.asTypeOf(status.bits))
    }
    dut.io.robPrepare.ready.poke(true.B)
    dut.io.robPrepared.valid.poke(false.B)
    dut.io.robPrepared.bits.poke(0.U.asTypeOf(dut.io.robPrepared.bits))
    dut.io.robAbort.valid.expect(false.B)
    (0 until dut.io.targets.length).foreach { target =>
      dut.io.targets(target).prepare.ready.poke(true.B)
      dut.io.targets(target).prepared.valid.poke(false.B)
      dut.io.targets(target).prepared.bits.poke(0.U.asTypeOf(
        dut.io.targets(target).prepared.bits))
    }
  }

  private def authorizeCandidate(
      dut: RecoveryControl,
      source: Int,
      transactionId: Int,
      stid: Int,
      rid: Int,
      age: Int,
      member: Int = 0,
      eligible: Boolean = true,
      headTrap: Boolean = false): Unit = {
    val status = dut.io.robCandidateStatus(source)
    status.valid.poke(true.B)
    status.bits.transactionId.poke(transactionId.U)
    status.bits.trigger.stid.poke(stid.U)
    status.bits.trigger.ridSlot.poke(rid.U)
    status.bits.trigger.ridGeneration.poke(0.U)
    status.bits.trigger.memberIndex.poke(member.U)
    status.bits.eligible.poke(eligible.B)
    status.bits.rejected.poke((!eligible).B)
    status.bits.ageToken.poke(age.U)
    status.bits.headTrap.poke(headTrap.B)
  }

  private def driveRecoveryEvent(
      dut: RecoveryControl,
      source: Int,
      transactionId: Int,
      cause: RecoveryCause.Type,
      stid: Int,
      rid: Int,
      redirectPc: Int = 0x4000): Unit = {
    dut.io.events(source).valid.poke(true.B)
    dut.io.events(source).bits.transactionId.poke(transactionId.U)
    dut.io.events(source).bits.cause.poke(cause)
    dut.io.events(source).bits.trigger.peId.poke(0.U)
    dut.io.events(source).bits.trigger.stid.poke(stid.U)
    dut.io.events(source).bits.trigger.ridSlot.poke(rid.U)
    dut.io.events(source).bits.trigger.ridGeneration.poke(0.U)
    dut.io.events(source).bits.trigger.memberIndex.poke(0.U)
    dut.io.events(source).bits.redirectPc.poke(redirectPc.U)
    dut.io.events(source).bits.instruction.epoch.poke(3.U)
  }

  private def preparedFromSeed(
      dut: RecoveryControl,
      firstKilledRid: Int = 1,
      lastKilledRid: Int = 2): Unit = {
    dut.io.robPrepared.bits.poke(dut.io.robPrepare.bits.peek())
    dut.io.robPrepared.bits.phase.poke(RecoveryPhase.Prepare)
    dut.io.robPrepared.bits.firstKilledValid.poke(true.B)
    dut.io.robPrepared.bits.firstKilled.poke(dut.io.robPrepare.bits.trigger.peek())
    dut.io.robPrepared.bits.firstKilled.ridSlot.poke(firstKilledRid.U)
    dut.io.robPrepared.bits.lastKilled.poke(dut.io.robPrepare.bits.trigger.peek())
    dut.io.robPrepared.bits.lastKilled.ridSlot.poke(lastKilledRid.U)
    dut.io.robPrepared.bits.killedGroupCount.poke(2.U)
    dut.io.robPrepared.bits.killedMemberCount.poke(2.U)
  }

  private def pokePreparedResponse(
      dut: RecoveryControl,
      transactionId: Int,
      cause: RecoveryCause.Type,
      peId: Int,
      stid: Int,
      rid: Int,
      ridGeneration: Int,
      member: Int,
      residentGeneration: Int,
      bid: Int,
      brobGeneration: Int,
      redirectPc: Int,
      newEpoch: Int,
      phase: RecoveryPhase.Type = RecoveryPhase.Prepare,
      firstKilledRid: Int = 1,
      lastKilledRid: Int = 2): Unit = {
    dut.io.robPrepared.bits.poke(0.U.asTypeOf(dut.io.robPrepared.bits))
    dut.io.robPrepared.bits.transactionId.poke(transactionId.U)
    dut.io.robPrepared.bits.phase.poke(phase)
    dut.io.robPrepared.bits.cause.poke(cause)
    dut.io.robPrepared.bits.trigger.peId.poke(peId.U)
    dut.io.robPrepared.bits.trigger.stid.poke(stid.U)
    dut.io.robPrepared.bits.trigger.ridSlot.poke(rid.U)
    dut.io.robPrepared.bits.trigger.ridGeneration.poke(ridGeneration.U)
    dut.io.robPrepared.bits.trigger.memberIndex.poke(member.U)
    dut.io.robPrepared.bits.trigger.residentGeneration.poke(residentGeneration.U)
    dut.io.robPrepared.bits.trigger.bid.poke(bid.U)
    dut.io.robPrepared.bits.trigger.brobGeneration.poke(brobGeneration.U)
    dut.io.robPrepared.bits.redirectPc.poke(redirectPc.U)
    dut.io.robPrepared.bits.newEpoch.poke(newEpoch.U)
    dut.io.robPrepared.bits.firstKilledValid.poke(true.B)
    dut.io.robPrepared.bits.firstKilled.peId.poke(peId.U)
    dut.io.robPrepared.bits.firstKilled.stid.poke(stid.U)
    dut.io.robPrepared.bits.firstKilled.ridSlot.poke(firstKilledRid.U)
    dut.io.robPrepared.bits.firstKilled.ridGeneration.poke(ridGeneration.U)
    dut.io.robPrepared.bits.lastKilled.peId.poke(peId.U)
    dut.io.robPrepared.bits.lastKilled.stid.poke(stid.U)
    dut.io.robPrepared.bits.lastKilled.ridSlot.poke(lastKilledRid.U)
    dut.io.robPrepared.bits.lastKilled.ridGeneration.poke(ridGeneration.U)
    dut.io.robPrepared.bits.killedGroupCount.poke(2.U)
    dut.io.robPrepared.bits.killedMemberCount.poke(2.U)
  }

  private def pokeRound6SeedEvent(dut: RecoveryControl): Unit = {
    driveRecoveryEvent(dut, 0, 0x160, RecoveryCause.MemoryOrder, stid = 0,
      rid = 1, redirectPc = 0x4100)
    authorizeCandidate(dut, 0, 0x160, stid = 0, rid = 1, age = 1)
    dut.io.robPrepared.valid.poke(false.B)
  }

  private def pokeRound6Response(
      dut: RecoveryControl,
      transactionId: Int = 0x160,
      cause: RecoveryCause.Type = RecoveryCause.MemoryOrder,
      peId: Int = 0,
      stid: Int = 0,
      rid: Int = 1,
      ridGeneration: Int = 0,
      member: Int = 0,
      residentGeneration: Int = 0,
      bid: Int = 0,
      brobGeneration: Int = 0,
      redirectPc: Int = 0x4100,
      newEpoch: Int = 4,
      phase: RecoveryPhase.Type = RecoveryPhase.Prepare,
      firstKilledRid: Int = 2,
      lastKilledRid: Int = 3): Unit = {
    pokePreparedResponse(dut, transactionId, cause, peId, stid, rid,
      ridGeneration, member, residentGeneration, bid, brobGeneration,
      redirectPc, newEpoch, phase, firstKilledRid, lastKilledRid)
  }

  private def fireStableRound6Response(
      dut: RecoveryControl,
      pokeResponse: () => Unit,
      maxCycles: Int = 4): Unit = {
    dut.io.robPrepared.valid.poke(true.B)
    pokeResponse()
    var cycles = 0
    while (!dut.io.robPrepared.ready.peek().litToBoolean && cycles < maxCycles) {
      dut.clock.step()
      cycles += 1
    }
    dut.io.robPrepared.ready.expect(true.B)
    dut.clock.step()
    dut.io.robPrepared.valid.poke(false.B)
  }

  test("ROB recovery prepare returns the exact suffix and apply prunes only target STID") {
    simulate(new ROB(params)) { dut =>
      clearRob(dut)
      lane(dut.io.prepare.bits, 0, id = 1, rid = 0, groupCount = 3, stid = 0)
      lane(dut.io.prepare.bits, 1, id = 2, rid = 1, groupCount = 3, stid = 0)
      lane(dut.io.prepare.bits, 2, id = 3, rid = 2, groupCount = 3, stid = 0)
      val ids = publish(dut, 3)
      dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
      lane(dut.io.prepare.bits, 0, id = 4, rid = 0, groupCount = 1, stid = 1)
      publish(dut, 1)

      dut.io.recoveryPrepare.valid.poke(true.B)
      dut.io.recoveryPrepare.bits.transactionId.poke(0x44.U)
      dut.io.recoveryPrepare.bits.phase.poke(RecoveryPhase.Prepare)
      dut.io.recoveryPrepare.bits.cause.poke(RecoveryCause.MemoryOrder)
      dut.io.recoveryPrepare.bits.trigger.poke(ids(1))
      dut.io.recoveryPrepare.bits.redirectPc.poke(0x1000.U)
      dut.io.recoveryPrepare.ready.expect(true.B)
      dut.io.recoveryPrepared.valid.expect(true.B)
      dut.io.recoveryPrepared.bits.firstKilledValid.expect(true.B)
      dut.io.recoveryPrepared.bits.firstKilled.ridSlot.expect(1.U)
      dut.io.recoveryPrepared.bits.lastKilled.ridSlot.expect(2.U)
      dut.io.recoveryPrepared.bits.killedGroupCount.expect(2.U)
      dut.io.recoveryPrepared.bits.killedMemberCount.expect(2.U)
      dut.clock.step()
      dut.io.recoveryPrepare.valid.poke(false.B)
      dut.io.ridTailSlot(0).expect(3.U)

      dut.io.recoveryApply.valid.poke(true.B)
      dut.io.recoveryApply.bits.poke(dut.io.recoveryPrepared.bits.peek())
      dut.io.recoveryApply.bits.phase.poke(RecoveryPhase.Apply)
      dut.clock.step()
      dut.io.recoveryApply.valid.poke(false.B)
      dut.io.ridTailSlot(0).expect(1.U)
      dut.io.ridTailSlot(1).expect(1.U)
    }
  }

  test("branch recovery from member zero kills member one in the same group") {
    simulate(new ROB(params)) { dut =>
      clearRob(dut)
      lane(dut.io.prepare.bits, 0, id = 10, rid = 0, groupCount = 2, stid = 0)
      lane(dut.io.prepare.bits, 1, id = 11, rid = 0, groupCount = 2, stid = 0)
      dut.io.prepare.bits.entries(1).uop.decoded.rob.memberIndex.poke(1.U)
      lane(dut.io.prepare.bits, 2, id = 12, rid = 1, groupCount = 2, stid = 0)
      val ids = publish(dut, 3)
      dut.io.recoveryPrepare.valid.poke(true.B)
      dut.io.recoveryPrepare.bits.transactionId.poke(0x45.U)
      dut.io.recoveryPrepare.bits.phase.poke(RecoveryPhase.Prepare)
      dut.io.recoveryPrepare.bits.cause.poke(RecoveryCause.Branch)
      dut.io.recoveryPrepare.bits.trigger.poke(ids(0))
      dut.io.recoveryPrepare.ready.expect(true.B)
      dut.io.recoveryPrepared.bits.firstKilled.memberIndex.expect(1.U)
      dut.io.recoveryPrepared.bits.firstKilled.ridSlot.expect(0.U)
      dut.io.recoveryPrepared.bits.killedMemberCount.expect(2.U)
    }
  }

  test("ROB recovery distinguishes killed members from affected groups") {
    simulate(new ROB(params)) { dut =>
      clearRob(dut)
      lane(dut.io.prepare.bits, 0, id = 20, rid = 0, groupCount = 2,
        stid = 0, member = 0)
      lane(dut.io.prepare.bits, 1, id = 21, rid = 0, groupCount = 2,
        stid = 0, member = 1)
      lane(dut.io.prepare.bits, 2, id = 22, rid = 1, groupCount = 2,
        stid = 0, member = 0)
      val ids = publish(dut, 3)
      dut.io.recoveryPrepare.valid.poke(true.B)
      dut.io.recoveryPrepare.bits.transactionId.poke(0x51.U)
      dut.io.recoveryPrepare.bits.phase.poke(RecoveryPhase.Prepare)
      dut.io.recoveryPrepare.bits.cause.poke(RecoveryCause.Branch)
      dut.io.recoveryPrepare.bits.trigger.poke(ids(0))
      dut.io.recoveryPrepare.ready.expect(true.B)
      dut.io.recoveryPrepared.bits.firstKilled.ridSlot.expect(0.U)
      dut.io.recoveryPrepared.bits.firstKilled.memberIndex.expect(1.U)
      dut.io.recoveryPrepared.bits.lastKilled.ridSlot.expect(1.U)
      dut.io.recoveryPrepared.bits.killedMemberCount.expect(2.U)
      dut.io.recoveryPrepared.bits.killedGroupCount.expect(2.U)
      dut.io.recoveryPrepared.bits.survivingTailValid.expect(true.B)
      dut.io.recoveryPrepared.bits.survivingTail.memberIndex.expect(0.U)
      dut.clock.step()
      dut.io.recoveryPrepare.valid.poke(false.B)
      dut.io.recoveryApply.valid.poke(true.B)
      dut.io.recoveryApply.bits.poke(dut.io.recoveryPrepared.bits.peek())
      dut.io.recoveryApply.bits.phase.poke(RecoveryPhase.Apply)
      dut.clock.step()
      dut.io.recoveryApply.valid.poke(false.B)
      dut.io.ridTailSlot(0).expect(1.U)
    }
  }

  test("ROB recovery from member one preserves trigger and kills only younger groups") {
    simulate(new ROB(params)) { dut =>
      clearRob(dut)
      lane(dut.io.prepare.bits, 0, id = 30, rid = 0, groupCount = 2,
        stid = 0, member = 0)
      lane(dut.io.prepare.bits, 1, id = 31, rid = 0, groupCount = 2,
        stid = 0, member = 1)
      lane(dut.io.prepare.bits, 2, id = 32, rid = 1, groupCount = 2,
        stid = 0, member = 0)
      val ids = publish(dut, 3)
      dut.io.recoveryPrepare.valid.poke(true.B)
      dut.io.recoveryPrepare.bits.transactionId.poke(0x52.U)
      dut.io.recoveryPrepare.bits.phase.poke(RecoveryPhase.Prepare)
      dut.io.recoveryPrepare.bits.cause.poke(RecoveryCause.Branch)
      dut.io.recoveryPrepare.bits.trigger.poke(ids(1))
      dut.io.recoveryPrepare.ready.expect(true.B)
      dut.io.recoveryPrepared.bits.firstKilled.ridSlot.expect(1.U)
      dut.io.recoveryPrepared.bits.firstKilled.memberIndex.expect(0.U)
      dut.io.recoveryPrepared.bits.killedMemberCount.expect(1.U)
      dut.io.recoveryPrepared.bits.killedGroupCount.expect(1.U)
      dut.io.recoveryPrepared.bits.survivingTail.memberIndex.expect(1.U)
    }
  }

  test("BROB recovery preserves older surviving blocks and unrelated STID") {
    simulate(new BROB(params)) { dut =>
      def clearBrob(): Unit = {
        dut.io.prepare.valid.poke(false.B)
        dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
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
      def publishBlock(id: Int, rid: Int, stid: Int = 0): RobIdentity = {
        dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
        lane(dut.io.prepare.bits, 0, id = id, rid = rid, groupCount = 1,
          stid = stid, member = 0, blockStart = true, blockStop = true)
        dut.io.prepare.valid.poke(true.B)
        dut.io.prepare.ready.expect(true.B)
        val rob = dut.io.prepare.bits.entries(0).uop.decoded.rob.peek()
        dut.io.publishFire.poke(true.B)
        dut.clock.step()
        dut.io.prepare.valid.poke(false.B)
        dut.io.publishFire.poke(false.B)
        rob
      }
      clearBrob()
      val older = publishBlock(40, 0)
      val younger = publishBlock(41, 1)
      publishBlock(42, 0, stid = 1)
      dut.io.recoveryPrepare.valid.poke(true.B)
      dut.io.recoveryPrepare.bits.transactionId.poke(0x53.U)
      dut.io.recoveryPrepare.bits.phase.poke(RecoveryPhase.Prepare)
      dut.io.recoveryPrepare.bits.cause.poke(RecoveryCause.MemoryOrder)
      dut.io.recoveryPrepare.bits.trigger.poke(younger)
      dut.io.recoveryPrepare.bits.firstKilledValid.poke(true.B)
      dut.io.recoveryPrepare.bits.firstKilled.poke(younger)
      dut.io.recoveryPrepare.bits.lastKilled.poke(younger)
      dut.io.recoveryPrepare.bits.killedMemberCount.poke(1.U)
      dut.io.recoveryPrepare.bits.killedGroupCount.poke(1.U)
      dut.io.recoveryPrepare.ready.expect(true.B)
      dut.clock.step()
      dut.io.recoveryPrepare.valid.poke(false.B)
      dut.io.recoveryApply.valid.poke(true.B)
      dut.io.recoveryApply.bits.poke(dut.io.recoveryPrepared.bits.peek())
      dut.io.recoveryApply.bits.phase.poke(RecoveryPhase.Apply)
      dut.clock.step()
      dut.io.recoveryApply.valid.poke(false.B)
      dut.io.release.valid.poke(true.B)
      dut.io.release.bits.count.poke(1.U)
      dut.io.release.bits.entries(0).poke(older)
      dut.io.releaseReady.expect(true.B)
      dut.io.releaseApply.poke(true.B)
      dut.clock.step()
      dut.io.release.valid.poke(false.B)
      dut.io.releaseApply.poke(false.B)
      dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
      lane(dut.io.prepare.bits, 0, id = 43, rid = 1, groupCount = 1,
        stid = 0, member = 0, blockStart = true, blockStop = true)
      dut.io.prepare.valid.poke(true.B)
      dut.io.prepare.ready.expect(true.B)
    }
  }

  test("BROB blocks release between retained recovery prepare and apply") {
    simulate(new BROB(params)) { dut =>
      def clearBrob(): Unit = {
        dut.io.prepare.valid.poke(false.B)
        dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
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
      def publishBlock(id: Int, rid: Int): RobIdentity = {
        dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
        lane(dut.io.prepare.bits, 0, id = id, rid = rid, groupCount = 1,
          stid = 0, member = 0, blockStart = true, blockStop = true)
        dut.io.prepare.valid.poke(true.B)
        dut.io.prepare.ready.expect(true.B)
        val rob = dut.io.prepare.bits.entries(0).uop.decoded.rob.peek()
        dut.io.publishFire.poke(true.B)
        dut.clock.step()
        dut.io.prepare.valid.poke(false.B)
        dut.io.publishFire.poke(false.B)
        rob
      }
      clearBrob()
      val older = publishBlock(50, 0)
      val younger = publishBlock(51, 1)
      dut.io.recoveryPrepare.valid.poke(true.B)
      dut.io.recoveryPrepare.bits.transactionId.poke(0x61.U)
      dut.io.recoveryPrepare.bits.phase.poke(RecoveryPhase.Prepare)
      dut.io.recoveryPrepare.bits.cause.poke(RecoveryCause.MemoryOrder)
      dut.io.recoveryPrepare.bits.trigger.poke(younger)
      dut.io.recoveryPrepare.bits.firstKilledValid.poke(true.B)
      dut.io.recoveryPrepare.bits.firstKilled.poke(younger)
      dut.io.recoveryPrepare.bits.lastKilled.poke(younger)
      dut.io.recoveryPrepare.bits.killedMemberCount.poke(1.U)
      dut.io.recoveryPrepare.bits.killedGroupCount.poke(1.U)
      dut.io.recoveryPrepare.ready.expect(true.B)
      dut.clock.step()
      dut.io.recoveryPrepare.valid.poke(false.B)
      dut.io.release.valid.poke(true.B)
      dut.io.release.bits.count.poke(1.U)
      dut.io.release.bits.entries(0).poke(older)
      dut.io.releaseReady.expect(false.B)
    }
  }

  test("BROB recovery preserves a partially killed open current block") {
    simulate(new BROB(params)) { dut =>
      def clearBrob(): Unit = {
        dut.io.prepare.valid.poke(false.B)
        dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
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
      clearBrob()
      dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
      lane(dut.io.prepare.bits, 0, id = 60, rid = 0, groupCount = 1,
        stid = 0, member = 0, blockStart = true, blockStop = false)
      lane(dut.io.prepare.bits, 1, id = 61, rid = 0, groupCount = 1,
        stid = 0, member = 1, blockStart = false, blockStop = false)
      dut.io.prepare.valid.poke(true.B)
      dut.io.prepare.ready.expect(true.B)
      val survivor = dut.io.prepare.bits.entries(0).uop.decoded.rob.peek()
      val killed = dut.io.prepare.bits.entries(1).uop.decoded.rob.peek()
      dut.io.publishFire.poke(true.B)
      dut.clock.step()
      dut.io.prepare.valid.poke(false.B)
      dut.io.publishFire.poke(false.B)

      dut.io.recoveryPrepare.valid.poke(true.B)
      dut.io.recoveryPrepare.bits.transactionId.poke(0x62.U)
      dut.io.recoveryPrepare.bits.phase.poke(RecoveryPhase.Prepare)
      dut.io.recoveryPrepare.bits.cause.poke(RecoveryCause.Branch)
      dut.io.recoveryPrepare.bits.trigger.poke(survivor)
      dut.io.recoveryPrepare.bits.survivingTailValid.poke(true.B)
      dut.io.recoveryPrepare.bits.survivingTail.poke(survivor)
      dut.io.recoveryPrepare.bits.firstKilledValid.poke(true.B)
      dut.io.recoveryPrepare.bits.firstKilled.poke(killed)
      dut.io.recoveryPrepare.bits.lastKilled.poke(killed)
      dut.io.recoveryPrepare.bits.killedMemberCount.poke(1.U)
      dut.io.recoveryPrepare.bits.killedGroupCount.poke(1.U)
      dut.io.recoveryPrepare.ready.expect(true.B)
      dut.clock.step()
      dut.io.recoveryPrepare.valid.poke(false.B)
      dut.io.recoveryApply.valid.poke(true.B)
      dut.io.recoveryApply.bits.poke(dut.io.recoveryPrepared.bits.peek())
      dut.io.recoveryApply.bits.phase.poke(RecoveryPhase.Apply)
      dut.clock.step()
      dut.io.recoveryApply.valid.poke(false.B)

      dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
      lane(dut.io.prepare.bits, 0, id = 62, rid = 0, groupCount = 1,
        stid = 0, member = 1, blockStart = false, blockStop = true)
      dut.io.prepare.valid.poke(true.B)
      dut.io.prepare.ready.expect(true.B)
      val closing = dut.io.prepare.bits.entries(0).uop.decoded.rob.peek()
      dut.io.publishFire.poke(true.B)
      dut.clock.step()
      dut.io.prepare.valid.poke(false.B)
      dut.io.publishFire.poke(false.B)

      dut.io.release.valid.poke(true.B)
      dut.io.release.bits.count.poke(2.U)
      dut.io.release.bits.entries(0).poke(survivor)
      dut.io.release.bits.entries(1).poke(closing)
      dut.io.releaseReady.expect(true.B)
    }
  }

  test("RecoveryPlanContract ignores only phase and checks wrapped suffix membership") {
    simulate(new RecoveryPlanContractProbe(params)) { dut =>
      dut.io.a.transactionId.poke(7.U)
      dut.io.a.phase.poke(RecoveryPhase.Prepare)
      dut.io.a.trigger.stid.poke(0.U)
      dut.io.a.trigger.peId.poke(1.U)
      dut.io.a.trigger.ridSlot.poke(3.U)
      dut.io.a.trigger.ridGeneration.poke(2.U)
      dut.io.a.firstKilledValid.poke(true.B)
      dut.io.a.firstKilled.peId.poke(1.U)
      dut.io.a.firstKilled.stid.poke(0.U)
      dut.io.a.firstKilled.ridSlot.poke(3.U)
      dut.io.a.firstKilled.ridGeneration.poke(2.U)
      dut.io.a.firstKilled.memberIndex.poke(1.U)
      dut.io.a.lastKilled.peId.poke(1.U)
      dut.io.a.lastKilled.stid.poke(0.U)
      dut.io.a.lastKilled.ridSlot.poke(1.U)
      dut.io.a.lastKilled.ridGeneration.poke(3.U)
      dut.io.a.lastKilled.memberIndex.poke(0.U)
      dut.io.a.killedGroupCount.poke(3.U)
      dut.io.a.killedMemberCount.poke(3.U)
      dut.io.b.poke(dut.io.a.peek())
      dut.io.b.phase.poke(RecoveryPhase.Apply)
      dut.io.member.stid.poke(0.U)
      dut.io.member.peId.poke(1.U)
      dut.io.member.ridGeneration.poke(3.U)
      dut.io.member.ridSlot.poke(0.U)
      dut.io.member.memberIndex.poke(0.U)
      dut.io.sameIgnoringPhase.expect(true.B)
      dut.io.memberKilled.expect(true.B)
      dut.io.legalWindow.expect(true.B)

      dut.io.member.ridGeneration.poke(1.U)
      dut.io.memberKilled.expect(false.B)
      dut.io.member.ridGeneration.poke(2.U)
      dut.io.member.ridSlot.poke(3.U)
      dut.io.member.memberIndex.poke(0.U)
      dut.io.memberKilled.expect(false.B)
    }
  }

  test("RecoveryControl waits for three identical prepares and emits one common apply") {
    simulate(new RecoveryControl(params, targetCount = 3)) { dut =>
      clearRecoveryControl(dut)
      dut.io.events(0).valid.poke(true.B)
      dut.io.events(0).bits.transactionId.poke(0x80.U)
      dut.io.events(0).bits.cause.poke(RecoveryCause.MemoryOrder)
      dut.io.events(0).bits.trigger.stid.poke(0.U)
      dut.io.events(0).bits.trigger.ridSlot.poke(1.U)
      dut.io.events(0).bits.redirectPc.poke(0x2000.U)
      authorizeCandidate(dut, 0, 0x80, stid = 0, rid = 1, age = 1)
      dut.clock.step()
      dut.io.robPrepare.valid.expect(true.B)
      dut.io.robPrepare.bits.trigger.ridSlot.expect(1.U)
      dut.io.events(0).valid.poke(false.B)
      dut.io.robPrepared.valid.poke(true.B)
      dut.io.robPrepared.bits.poke(dut.io.robPrepare.bits.peek())
      dut.io.robPrepared.bits.firstKilledValid.poke(true.B)
      dut.io.robPrepared.bits.firstKilled.ridSlot.poke(1.U)
      dut.io.robPrepared.bits.lastKilled.ridSlot.poke(2.U)
      dut.io.robPrepared.bits.killedGroupCount.poke(2.U)
      dut.io.robPrepared.bits.killedMemberCount.poke(2.U)
      dut.clock.step()
      dut.io.robPrepared.valid.poke(false.B)
      (0 until 3).foreach { target =>
        dut.io.targets(target).prepare.valid.expect(true.B)
        dut.io.targets(target).apply.valid.expect(false.B)
      }
      dut.io.targets(0).prepared.valid.poke(true.B)
      dut.io.targets(0).prepared.bits.poke(dut.io.targets(0).prepare.bits.peek())
      dut.io.targets(1).prepared.valid.poke(true.B)
      dut.io.targets(1).prepared.bits.poke(dut.io.targets(1).prepare.bits.peek())
      dut.clock.step()
      dut.io.targets(0).apply.valid.expect(false.B)
      dut.io.targets(2).prepared.valid.poke(true.B)
      dut.io.targets(2).prepared.bits.poke(dut.io.targets(2).prepare.bits.peek())
      dut.clock.step()
      (0 until 3).foreach { target =>
        dut.io.targets(target).apply.valid.expect(true.B)
        dut.io.targets(target).apply.bits.transactionId.expect(0x80.U)
      }
      dut.clock.step()
    }
  }

  test("abort is non-mutating for a retained recovery transaction") {
    simulate(new RecoveryControl(params, targetCount = 3)) { dut =>
      clearRecoveryControl(dut)
      dut.io.events(0).valid.poke(true.B)
      dut.io.events(0).bits.transactionId.poke(0x90.U)
      dut.io.events(0).bits.cause.poke(RecoveryCause.Debug)
      dut.io.events(0).bits.trigger.stid.poke(0.U)
      authorizeCandidate(dut, 0, 0x90, stid = 0, rid = 0, age = 1)
      dut.clock.step()
      dut.io.events(0).valid.poke(false.B)
      dut.io.robPrepared.valid.poke(true.B)
      dut.io.robPrepared.bits.poke(dut.io.robPrepare.bits.peek())
      dut.clock.step()
      dut.io.robPrepared.valid.poke(false.B)
      dut.io.abort.poke(true.B)
      dut.clock.step()
      dut.io.targets(0).abort.valid.expect(true.B)
      dut.io.targets(0).abort.bits.phase.expect(RecoveryPhase.Abort)
    }
  }

  test("RecoveryControl retains source one and selects the older retained event") {
    simulate(new RecoveryControl(params, targetCount = 3)) { dut =>
      clearRecoveryControl(dut)
      dut.io.events(1).valid.poke(true.B)
      dut.io.events(1).bits.transactionId.poke(0xa1.U)
      dut.io.events(1).bits.cause.poke(RecoveryCause.Branch)
      dut.io.events(1).bits.trigger.stid.poke(0.U)
      dut.io.events(1).bits.trigger.ridSlot.poke(1.U)
      authorizeCandidate(dut, 1, 0xa1, stid = 0, rid = 1, age = 1)
      dut.clock.step()
      dut.io.robPrepare.valid.expect(true.B)
      dut.io.robPrepare.bits.transactionId.expect(0xa1.U)

      dut.io.robPrepare.ready.poke(false.B)
      dut.io.events(0).valid.poke(true.B)
      dut.io.events(0).bits.transactionId.poke(0xa0.U)
      dut.io.events(0).bits.cause.poke(RecoveryCause.MemoryOrder)
      dut.io.events(0).bits.trigger.stid.poke(0.U)
      dut.io.events(0).bits.trigger.ridSlot.poke(3.U)
      dut.clock.step()
      dut.io.events(1).valid.poke(false.B)
      dut.io.robPrepare.ready.poke(true.B)
      dut.io.robPrepare.valid.expect(true.B)
      dut.io.robPrepare.bits.transactionId.expect(0xa1.U)
    }
  }

  test("RecoveryControl sends prepare once per target and ignores mismatched acknowledgements") {
    simulate(new RecoveryControl(params, targetCount = 3)) { dut =>
      clearRecoveryControl(dut)
      dut.io.events(0).valid.poke(true.B)
      dut.io.events(0).bits.transactionId.poke(0xb0.U)
      dut.io.events(0).bits.cause.poke(RecoveryCause.MemoryOrder)
      authorizeCandidate(dut, 0, 0xb0, stid = 0, rid = 0, age = 1)
      (0 until 3).foreach { target =>
        dut.io.targets(target).prepare.ready.poke(target != 2)
        dut.io.targets(target).prepared.valid.poke(false.B)
        dut.io.targets(target).prepared.bits.poke(0.U.asTypeOf(
          dut.io.targets(target).prepared.bits))
      }
      dut.clock.step()
      dut.io.robPrepared.valid.poke(true.B)
      dut.io.robPrepared.bits.poke(dut.io.robPrepare.bits.peek())
      dut.clock.step()
      dut.io.events(0).valid.poke(false.B)
      dut.io.robPrepared.valid.poke(false.B)
      dut.io.targets(0).prepare.valid.expect(true.B)
      dut.io.targets(1).prepare.valid.expect(true.B)
      dut.clock.step()
      dut.io.targets(0).prepare.valid.expect(false.B)
      dut.io.targets(1).prepare.valid.expect(false.B)
      dut.io.targets(2).prepare.valid.expect(true.B)
      dut.io.targets(0).prepared.valid.poke(true.B)
      dut.io.targets(0).prepared.bits.poke(dut.io.targets(0).prepare.bits.peek())
      dut.io.targets(0).prepared.bits.transactionId.poke(0xff.U)
      dut.io.targets(1).prepared.valid.poke(true.B)
      dut.io.targets(1).prepared.bits.poke(dut.io.targets(1).prepare.bits.peek())
      dut.clock.step()
      dut.io.targets(0).apply.valid.expect(false.B)
      dut.io.targets(2).prepare.ready.poke(true.B)
      dut.io.targets(0).prepared.bits.transactionId.poke(0xb0.U)
      dut.io.targets(2).prepared.valid.poke(true.B)
      dut.io.targets(2).prepared.bits.poke(dut.io.targets(2).prepare.bits.peek())
      dut.clock.step()
      dut.io.targets(0).apply.valid.expect(true.B)
    }
  }

  test("RecoveryControl selects precise trap before interrupt and gates interrupt at boundary") {
    simulate(new RecoveryControl(params, targetCount = 3)) { dut =>
      clearRecoveryControl(dut)
      dut.io.events(0).valid.poke(true.B)
      dut.io.events(0).bits.transactionId.poke(0xc0.U)
      dut.io.events(0).bits.cause.poke(RecoveryCause.Exception)
      dut.io.events(0).bits.trap.valid.poke(true.B)
      dut.io.events(0).bits.trigger.ridSlot.poke(0.U)
      authorizeCandidate(dut, 0, 0xc0, stid = 0, rid = 0, age = 1,
        headTrap = true)
      dut.io.interrupts(0).valid.poke(true.B)
      dut.io.interruptBoundaryValid.poke(true.B)
      dut.io.interruptBoundary.ridSlot.poke(0.U)
      dut.io.robPrepare.ready.poke(true.B)
      dut.clock.step()
      dut.io.robPrepare.valid.expect(true.B)
      dut.io.robPrepare.bits.cause.expect(RecoveryCause.Exception)
      dut.io.events(0).valid.poke(false.B)
      dut.io.abort.poke(true.B)
      dut.clock.step()
      dut.io.abort.poke(false.B)
      dut.io.interruptBoundaryValid.poke(false.B)
      dut.clock.step()
      dut.io.robPrepare.valid.expect(false.B)
      dut.io.interruptBoundaryValid.poke(true.B)
      dut.clock.step()
      dut.io.robPrepare.valid.expect(true.B)
      dut.io.robPrepare.bits.cause.expect(RecoveryCause.Interrupt)
    }
  }

  test("RecoveryControl does not let enum Exception outrank an older ordinary event") {
    simulate(new RecoveryControl(params, targetCount = 3)) { dut =>
      clearRecoveryControl(dut)
      dut.io.events(0).valid.poke(true.B)
      dut.io.events(0).bits.transactionId.poke(0xe0.U)
      dut.io.events(0).bits.cause.poke(RecoveryCause.Exception)
      dut.io.events(0).bits.trigger.stid.poke(1.U)
      dut.io.events(0).bits.trigger.ridSlot.poke(3.U)
      dut.io.events(1).valid.poke(true.B)
      dut.io.events(1).bits.transactionId.poke(0xe1.U)
      dut.io.events(1).bits.cause.poke(RecoveryCause.MemoryOrder)
      dut.io.events(1).bits.trigger.stid.poke(0.U)
      dut.io.events(1).bits.trigger.ridSlot.poke(1.U)
      authorizeCandidate(dut, 0, 0xe0, stid = 1, rid = 3, age = 5)
      authorizeCandidate(dut, 1, 0xe1, stid = 0, rid = 1, age = 1)
      dut.clock.step()
      dut.io.robPrepare.valid.expect(true.B)
      dut.io.robPrepare.bits.transactionId.expect(0xe1.U)
      dut.io.robPrepare.bits.cause.expect(RecoveryCause.MemoryOrder)
    }
  }

  test("RecoveryControl does not deadlock on stale source zero when source one can be eligible") {
    simulate(new RecoveryControl(params, targetCount = 1)) { dut =>
      clearRecoveryControl(dut)
      dut.io.events(0).valid.poke(true.B)
      dut.io.events(0).bits.transactionId.poke(0xf0.U)
      dut.io.events(0).bits.cause.poke(RecoveryCause.MemoryOrder)
      dut.io.events(0).bits.trigger.stid.poke(0.U)
      dut.io.events(0).bits.trigger.ridSlot.poke(3.U)
      dut.io.events(1).valid.poke(true.B)
      dut.io.events(1).bits.transactionId.poke(0xf1.U)
      dut.io.events(1).bits.cause.poke(RecoveryCause.MemoryOrder)
      dut.io.events(1).bits.trigger.stid.poke(1.U)
      dut.io.events(1).bits.trigger.ridSlot.poke(0.U)
      authorizeCandidate(dut, 0, 0xf0, stid = 0, rid = 3, age = 1,
        eligible = false)
      authorizeCandidate(dut, 1, 0xf1, stid = 1, rid = 0, age = 0)
      dut.clock.step()
      dut.io.robPrepare.valid.expect(true.B)
      dut.io.robPrepare.bits.transactionId.expect(0xf1.U)
    }
  }

  test("RecoveryControl waits for every active producer status before selecting") {
    simulate(new RecoveryControl(params, targetCount = 1)) { dut =>
      clearRecoveryControl(dut)
      dut.io.events(0).valid.poke(true.B)
      dut.io.events(0).bits.transactionId.poke(0xd0.U)
      dut.io.events(0).bits.cause.poke(RecoveryCause.MemoryOrder)
      dut.io.events(0).bits.trigger.stid.poke(0.U)
      dut.io.events(0).bits.trigger.ridSlot.poke(2.U)
      dut.io.events(1).valid.poke(true.B)
      dut.io.events(1).bits.transactionId.poke(0xd1.U)
      dut.io.events(1).bits.cause.poke(RecoveryCause.Branch)
      dut.io.events(1).bits.trigger.stid.poke(0.U)
      dut.io.events(1).bits.trigger.ridSlot.poke(1.U)
      authorizeCandidate(dut, 0, 0xd0, stid = 0, rid = 2, age = 5)
      dut.clock.step()
      dut.io.robPrepare.valid.expect(false.B)
      dut.io.robCandidateStatus(0).valid.poke(false.B)
      dut.clock.step()
      dut.io.robPrepare.valid.expect(false.B)
      authorizeCandidate(dut, 1, 0xd1, stid = 0, rid = 1, age = 1)
      dut.clock.step()
      dut.io.robPrepare.valid.expect(true.B)
      dut.io.robPrepare.bits.transactionId.expect(0xd1.U)
    }
  }

  test("RecoveryControl retains an early status until a slower peer resolves") {
    simulate(new RecoveryControl(params, targetCount = 1)) { dut =>
      clearRecoveryControl(dut)
      dut.io.events(0).valid.poke(true.B)
      dut.io.events(0).bits.transactionId.poke(0xd2.U)
      dut.io.events(0).bits.cause.poke(RecoveryCause.MemoryOrder)
      dut.io.events(0).bits.trigger.stid.poke(0.U)
      dut.io.events(0).bits.trigger.ridSlot.poke(3.U)
      dut.io.events(1).valid.poke(true.B)
      dut.io.events(1).bits.transactionId.poke(0xd3.U)
      dut.io.events(1).bits.cause.poke(RecoveryCause.Branch)
      dut.io.events(1).bits.trigger.stid.poke(0.U)
      dut.io.events(1).bits.trigger.ridSlot.poke(0.U)
      authorizeCandidate(dut, 1, 0xd3, stid = 0, rid = 0, age = 1)
      dut.clock.step()
      dut.io.robPrepare.valid.expect(false.B)
      dut.io.robCandidateStatus(1).valid.poke(false.B)
      authorizeCandidate(dut, 0, 0xd2, stid = 0, rid = 3, age = 5)
      dut.clock.step()
      dut.io.robPrepare.valid.expect(true.B)
      dut.io.robPrepare.bits.transactionId.expect(0xd3.U)
    }
  }

  test("RecoveryControl does not let interrupt bypass an unresolved producer") {
    simulate(new RecoveryControl(params, targetCount = 1)) { dut =>
      clearRecoveryControl(dut)
      dut.io.events(0).valid.poke(true.B)
      dut.io.events(0).bits.transactionId.poke(0xd4.U)
      dut.io.events(0).bits.cause.poke(RecoveryCause.MemoryOrder)
      dut.io.events(0).bits.trigger.stid.poke(0.U)
      dut.io.events(0).bits.trigger.ridSlot.poke(1.U)
      dut.io.interrupts(0).valid.poke(true.B)
      dut.io.interrupts(0).priority.poke(3.U)
      dut.io.interruptBoundaryValid.poke(true.B)
      dut.io.interruptBoundary.stid.poke(0.U)
      dut.clock.step()
      dut.io.robPrepare.valid.expect(false.B)
      authorizeCandidate(dut, 0, 0xd4, stid = 0, rid = 1, age = 1,
        eligible = false)
      dut.clock.step()
      dut.io.robPrepare.valid.expect(true.B)
      dut.io.robPrepare.bits.cause.expect(RecoveryCause.Interrupt)
    }
  }

  test("ROB ignores wrong-phase and duplicate recovery apply without mutating twice") {
    simulate(new ROB(params)) { dut =>
      clearRob(dut)
      lane(dut.io.prepare.bits, 0, id = 200, rid = 0, groupCount = 2,
        stid = 0)
      lane(dut.io.prepare.bits, 1, id = 201, rid = 1, groupCount = 2,
        stid = 0)
      val ids = publish(dut, 2)
      dut.io.recoveryPrepare.valid.poke(true.B)
      dut.io.recoveryPrepare.bits.transactionId.poke(0x120.U)
      dut.io.recoveryPrepare.bits.phase.poke(RecoveryPhase.Prepare)
      dut.io.recoveryPrepare.bits.cause.poke(RecoveryCause.MemoryOrder)
      dut.io.recoveryPrepare.bits.trigger.poke(ids(1))
      dut.io.recoveryPrepare.ready.expect(true.B)
      dut.clock.step()
      val prepared = dut.io.recoveryPrepared.bits.peek()
      dut.io.recoveryPrepare.valid.poke(false.B)
      dut.io.recoveryApply.valid.poke(true.B)
      dut.io.recoveryApply.bits.poke(prepared)
      dut.io.recoveryApply.bits.phase.poke(RecoveryPhase.Prepare)
      dut.clock.step()
      dut.io.recoveryApply.valid.poke(false.B)
      dut.io.recoveryPrepare.valid.poke(true.B)
      dut.io.recoveryPrepare.bits.transactionId.poke(0x121.U)
      dut.io.recoveryPrepare.bits.phase.poke(RecoveryPhase.Prepare)
      dut.io.recoveryPrepare.bits.cause.poke(RecoveryCause.MemoryOrder)
      dut.io.recoveryPrepare.bits.trigger.poke(ids(1))
      dut.io.recoveryPrepare.ready.expect(false.B)
      dut.io.recoveryPrepare.valid.poke(false.B)

      dut.io.recoveryApply.valid.poke(true.B)
      dut.io.recoveryApply.bits.poke(prepared)
      dut.io.recoveryApply.bits.phase.poke(RecoveryPhase.Apply)
      dut.clock.step()
      dut.io.recoveryApply.valid.poke(false.B)
      dut.io.ridTailSlot(0).expect(1.U)
      dut.io.recoveryApply.valid.poke(true.B)
      dut.io.recoveryApply.bits.poke(prepared)
      dut.io.recoveryApply.bits.phase.poke(RecoveryPhase.Apply)
      dut.clock.step()
      dut.io.recoveryApply.valid.poke(false.B)
      dut.io.ridTailSlot(0).expect(1.U)
      dut.io.recoveryPrepare.valid.poke(true.B)
      dut.io.recoveryPrepare.bits.transactionId.poke(0x122.U)
      dut.io.recoveryPrepare.bits.phase.poke(RecoveryPhase.Prepare)
      dut.io.recoveryPrepare.bits.cause.poke(RecoveryCause.Branch)
      dut.io.recoveryPrepare.bits.trigger.poke(ids(0))
      dut.io.recoveryPrepare.ready.expect(true.B)
    }
  }

  test("ROB abort clears only the matching retained recovery transaction") {
    simulate(new ROB(params)) { dut =>
      clearRob(dut)
      lane(dut.io.prepare.bits, 0, id = 210, rid = 0, groupCount = 1,
        stid = 0)
      val id = publish(dut, 1).head
      dut.io.recoveryPrepare.valid.poke(true.B)
      dut.io.recoveryPrepare.bits.transactionId.poke(0x130.U)
      dut.io.recoveryPrepare.bits.phase.poke(RecoveryPhase.Prepare)
      dut.io.recoveryPrepare.bits.cause.poke(RecoveryCause.MemoryOrder)
      dut.io.recoveryPrepare.bits.trigger.poke(id)
      dut.io.recoveryPrepare.ready.expect(true.B)
      dut.clock.step()
      val prepared = dut.io.recoveryPrepared.bits.peek()
      dut.io.recoveryPrepare.valid.poke(false.B)
      dut.io.recoveryAbort.valid.poke(true.B)
      dut.io.recoveryAbort.bits.poke(prepared)
      dut.io.recoveryAbort.bits.transactionId.poke(0x131.U)
      dut.io.recoveryAbort.bits.phase.poke(RecoveryPhase.Abort)
      dut.clock.step()
      dut.io.recoveryPrepare.valid.poke(true.B)
      dut.io.recoveryPrepare.bits.poke(prepared)
      dut.io.recoveryPrepare.bits.transactionId.poke(0x132.U)
      dut.io.recoveryPrepare.ready.expect(false.B)
      dut.io.recoveryPrepare.valid.poke(false.B)
      dut.io.recoveryAbort.bits.poke(prepared)
      dut.io.recoveryAbort.bits.phase.poke(RecoveryPhase.Abort)
      dut.clock.step()
      dut.io.recoveryAbort.valid.poke(false.B)
      dut.io.recoveryPrepare.valid.poke(true.B)
      dut.io.recoveryPrepare.bits.poke(prepared)
      dut.io.recoveryPrepare.bits.transactionId.poke(0x133.U)
      dut.io.recoveryPrepare.bits.phase.poke(RecoveryPhase.Prepare)
      dut.io.recoveryPrepare.ready.expect(true.B)
      dut.io.ridTailSlot(0).expect(1.U)
    }
  }

  test("BROB abort clears matching retained recovery and leaves state releasable") {
    simulate(new BROB(params)) { dut =>
      def clearBrob(): Unit = {
        dut.io.prepare.valid.poke(false.B)
        dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
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
      clearBrob()
      lane(dut.io.prepare.bits, 0, id = 220, rid = 0, groupCount = 1,
        stid = 0, member = 0, blockStart = true, blockStop = true)
      dut.io.prepare.valid.poke(true.B)
      dut.io.prepare.ready.expect(true.B)
      val id = dut.io.prepare.bits.entries(0).uop.decoded.rob.peek()
      dut.io.publishFire.poke(true.B)
      dut.clock.step()
      dut.io.prepare.valid.poke(false.B)
      dut.io.publishFire.poke(false.B)
      dut.io.recoveryPrepare.valid.poke(true.B)
      dut.io.recoveryPrepare.bits.transactionId.poke(0x140.U)
      dut.io.recoveryPrepare.bits.phase.poke(RecoveryPhase.Prepare)
      dut.io.recoveryPrepare.bits.cause.poke(RecoveryCause.MemoryOrder)
      dut.io.recoveryPrepare.bits.trigger.poke(id)
      dut.io.recoveryPrepare.bits.firstKilledValid.poke(true.B)
      dut.io.recoveryPrepare.bits.firstKilled.poke(id)
      dut.io.recoveryPrepare.bits.lastKilled.poke(id)
      dut.io.recoveryPrepare.bits.killedMemberCount.poke(1.U)
      dut.io.recoveryPrepare.bits.killedGroupCount.poke(1.U)
      dut.io.recoveryPrepare.ready.expect(true.B)
      dut.clock.step()
      val prepared = dut.io.recoveryPrepared.bits.peek()
      dut.io.recoveryPrepare.valid.poke(false.B)
      dut.io.recoveryAbort.valid.poke(true.B)
      dut.io.recoveryAbort.bits.poke(prepared)
      dut.io.recoveryAbort.bits.transactionId.poke(0x141.U)
      dut.io.recoveryAbort.bits.phase.poke(RecoveryPhase.Abort)
      dut.clock.step()
      dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
      lane(dut.io.prepare.bits, 0, id = 221, rid = 1, groupCount = 1,
        stid = 0, member = 0, blockStart = true, blockStop = true)
      dut.io.prepare.valid.poke(true.B)
      dut.io.prepare.ready.expect(false.B)
      dut.io.prepare.valid.poke(false.B)
      dut.io.recoveryAbort.bits.poke(prepared)
      dut.io.recoveryAbort.bits.phase.poke(RecoveryPhase.Abort)
      dut.clock.step()
      dut.io.recoveryAbort.valid.poke(false.B)
      dut.io.release.valid.poke(true.B)
      dut.io.release.bits.count.poke(1.U)
      dut.io.release.bits.entries(0).poke(id)
      dut.io.releaseReady.expect(true.B)
    }
  }

  test("RecoveryControl abort before ROB request fire cancels without terminals") {
    simulate(new RecoveryControl(params, targetCount = 2)) { dut =>
      clearRecoveryControl(dut)
      driveRecoveryEvent(dut, 0, 0x150, RecoveryCause.MemoryOrder, stid = 0,
        rid = 1)
      authorizeCandidate(dut, 0, 0x150, stid = 0, rid = 1, age = 1)
      dut.clock.step()

      dut.io.events(0).valid.poke(false.B)
      dut.io.robPrepare.ready.poke(true.B)
      dut.io.abort.poke(true.B)
      dut.io.robPrepare.valid.expect(false.B)
      dut.io.robAbort.valid.expect(false.B)
      dut.io.targets(0).abort.valid.expect(false.B)
      dut.io.targets(1).abort.valid.expect(false.B)
      dut.clock.step()

      dut.io.abort.poke(false.B)
      driveRecoveryEvent(dut, 0, 0x151, RecoveryCause.Branch, stid = 0,
        rid = 2)
      authorizeCandidate(dut, 0, 0x151, stid = 0, rid = 2, age = 2)
      dut.clock.step()
      dut.io.robPrepare.valid.expect(true.B)
      dut.io.robPrepare.bits.transactionId.expect(0x151.U)
    }
  }

  test("RecoveryControl abort after ROB request waits for exact ROB abort plan") {
    simulate(new RecoveryControl(params, targetCount = 2)) { dut =>
      clearRecoveryControl(dut)
      driveRecoveryEvent(dut, 0, 0x152, RecoveryCause.MemoryOrder, stid = 0,
        rid = 1)
      authorizeCandidate(dut, 0, 0x152, stid = 0, rid = 1, age = 1)
      dut.clock.step()

      dut.io.events(0).valid.poke(false.B)
      dut.io.robPrepare.ready.poke(true.B)
      dut.io.robPrepared.valid.poke(false.B)
      dut.io.robPrepare.valid.expect(true.B)
      dut.clock.step()
      dut.io.abort.poke(true.B)
      dut.io.robAbort.valid.expect(false.B)
      dut.io.targets(0).abort.valid.expect(false.B)
      dut.io.targets(1).abort.valid.expect(false.B)
      dut.clock.step()

      dut.io.robPrepared.valid.poke(true.B)
      preparedFromSeed(dut, firstKilledRid = 1, lastKilledRid = 2)
      dut.clock.step()
      dut.io.robAbort.valid.expect(true.B)
      dut.io.robAbort.bits.transactionId.expect(0x152.U)
      dut.io.robAbort.bits.phase.expect(RecoveryPhase.Abort)
      dut.io.robAbort.bits.firstKilledValid.expect(true.B)
      dut.io.robAbort.bits.firstKilled.ridSlot.expect(1.U)
      dut.io.targets(0).abort.valid.expect(false.B)
      dut.io.targets(1).abort.valid.expect(false.B)

      dut.io.abort.poke(false.B)
      dut.io.robPrepared.valid.poke(false.B)
      dut.clock.step()
      driveRecoveryEvent(dut, 0, 0x153, RecoveryCause.Branch, stid = 0,
        rid = 3)
      authorizeCandidate(dut, 0, 0x153, stid = 0, rid = 3, age = 3)
      dut.clock.step()
      dut.io.robPrepare.valid.expect(true.B)
      dut.io.robPrepare.bits.transactionId.expect(0x153.U)
    }
  }

  test("RecoveryControl final target ack with abort emits only abort") {
    simulate(new RecoveryControl(params, targetCount = 2)) { dut =>
      clearRecoveryControl(dut)
      driveRecoveryEvent(dut, 0, 0x154, RecoveryCause.MemoryOrder, stid = 0,
        rid = 1)
      authorizeCandidate(dut, 0, 0x154, stid = 0, rid = 1, age = 1)
      dut.clock.step()

      dut.io.events(0).valid.poke(false.B)
      dut.io.robPrepared.valid.poke(true.B)
      preparedFromSeed(dut, firstKilledRid = 1, lastKilledRid = 2)
      dut.clock.step()
      dut.io.robPrepared.valid.poke(false.B)
      (0 until 2).foreach { target =>
        dut.io.targets(target).prepared.valid.poke(true.B)
        dut.io.targets(target).prepared.bits.poke(
          dut.io.targets(target).prepare.bits.peek())
      }
      dut.io.abort.poke(true.B)
      dut.clock.step()

      dut.io.targets(0).apply.valid.expect(false.B)
      dut.io.targets(1).apply.valid.expect(false.B)
      dut.io.targets(0).abort.valid.expect(true.B)
      dut.io.targets(1).abort.valid.expect(true.B)
      dut.io.robAbort.valid.expect(true.B)
      dut.io.robAbort.bits.transactionId.expect(0x154.U)
    }
  }

  test("RecoveryControl abort during visible apply does not schedule post-apply abort") {
    simulate(new RecoveryControl(params, targetCount = 1)) { dut =>
      clearRecoveryControl(dut)
      driveRecoveryEvent(dut, 0, 0x155, RecoveryCause.MemoryOrder, stid = 0,
        rid = 1)
      authorizeCandidate(dut, 0, 0x155, stid = 0, rid = 1, age = 1)
      dut.clock.step()

      dut.io.events(0).valid.poke(false.B)
      dut.io.robPrepared.valid.poke(true.B)
      preparedFromSeed(dut, firstKilledRid = 1, lastKilledRid = 1)
      dut.clock.step()
      dut.io.robPrepared.valid.poke(false.B)
      dut.io.targets(0).prepared.valid.poke(true.B)
      dut.io.targets(0).prepared.bits.poke(dut.io.targets(0).prepare.bits.peek())
      dut.clock.step()
      dut.io.targets(0).apply.valid.expect(true.B)
      dut.io.abort.poke(true.B)
      dut.clock.step()
      dut.io.abort.poke(false.B)
      dut.io.targets(0).abort.valid.expect(false.B)
      dut.io.robAbort.valid.expect(false.B)
    }
  }

  test("RecoveryControl ignores unsolicited ROB response before request fires") {
    simulate(new RecoveryControl(params, targetCount = 1)) { dut =>
      clearRecoveryControl(dut)
      pokeRound6SeedEvent(dut)
      dut.clock.step()

      dut.io.events(0).valid.poke(false.B)
      dut.io.robPrepare.ready.poke(false.B)
      fireStableRound6Response(dut,
        () => pokeRound6Response(dut, firstKilledRid = 2, lastKilledRid = 3))
      dut.io.robPrepare.valid.expect(true.B)
      dut.io.targets(0).prepare.valid.expect(false.B)
      dut.io.robAbort.valid.expect(false.B)

      dut.io.robPrepare.ready.poke(true.B)
      dut.clock.step()
      dut.io.robPrepare.valid.expect(false.B)
      dut.io.targets(0).prepare.valid.expect(false.B)
      dut.io.robAbort.valid.expect(false.B)

      fireStableRound6Response(dut,
        () => pokeRound6Response(dut, firstKilledRid = 2, lastKilledRid = 3))
      dut.io.targets(0).prepare.valid.expect(true.B)
      dut.io.targets(0).prepare.bits.transactionId.expect(0x160.U)
      dut.io.targets(0).prepare.bits.firstKilled.ridSlot.expect(2.U)
    }
  }

  test("RecoveryControl drains held ROB response when abort suppresses request") {
    simulate(new RecoveryControl(params, targetCount = 1)) { dut =>
      clearRecoveryControl(dut)
      pokeRound6SeedEvent(dut)
      dut.clock.step()

      dut.io.events(0).valid.poke(false.B)
      dut.io.robPrepare.ready.poke(false.B)
      dut.io.abort.poke(true.B)
      fireStableRound6Response(dut,
        () => pokeRound6Response(dut, firstKilledRid = 2, lastKilledRid = 3))
      dut.io.robPrepare.valid.expect(false.B)
      dut.io.targets(0).prepare.valid.expect(false.B)
      dut.io.robAbort.valid.expect(false.B)

      dut.io.abort.poke(false.B)
      dut.io.robPrepare.ready.poke(true.B)
      pokeRound6SeedEvent(dut)
      dut.clock.step()
      dut.io.events(0).valid.poke(false.B)
      dut.io.robPrepare.valid.expect(true.B)
      dut.io.robPrepared.valid.poke(false.B)
      dut.clock.step()
      dut.io.targets(0).prepare.valid.expect(false.B)
      dut.io.robAbort.valid.expect(false.B)

      fireStableRound6Response(dut,
        () => pokeRound6Response(dut, firstKilledRid = 2, lastKilledRid = 3))
      dut.io.targets(0).prepare.valid.expect(true.B)
      dut.io.targets(0).prepare.bits.transactionId.expect(0x160.U)
      dut.io.targets(0).prepare.bits.firstKilled.ridSlot.expect(2.U)
    }
  }

  test("RecoveryControl drains same-cycle mismatched ROB response while request fires once") {
    simulate(new RecoveryControl(params, targetCount = 1)) { dut =>
      clearRecoveryControl(dut)
      pokeRound6SeedEvent(dut)
      dut.clock.step()

      dut.io.events(0).valid.poke(false.B)
      dut.io.robPrepare.ready.poke(true.B)
      dut.io.robPrepared.valid.poke(true.B)
      pokeRound6Response(dut, transactionId = 0x161)
      dut.io.robPrepare.valid.expect(true.B)
      dut.io.robPrepared.ready.expect(true.B)
      dut.clock.step()
      dut.io.robPrepare.valid.expect(false.B)
      dut.io.targets(0).prepare.valid.expect(false.B)
      dut.io.robAbort.valid.expect(false.B)

      dut.io.robPrepared.valid.poke(false.B)
      dut.clock.step()
      dut.io.robPrepare.valid.expect(false.B)

      fireStableRound6Response(dut,
        () => pokeRound6Response(dut, firstKilledRid = 2, lastKilledRid = 3))
      dut.io.targets(0).prepare.valid.expect(true.B)
      dut.io.targets(0).prepare.bits.transactionId.expect(0x160.U)
      dut.io.targets(0).prepare.bits.firstKilled.ridSlot.expect(2.U)
    }
  }

  test("RecoveryControl waits through mismatched ROB responses in WaitRob") {
    simulate(new RecoveryControl(params, targetCount = 1)) { dut =>
      clearRecoveryControl(dut)
      pokeRound6SeedEvent(dut)
      dut.clock.step()

      dut.io.events(0).valid.poke(false.B)
      dut.io.robPrepare.ready.poke(true.B)
      dut.io.robPrepared.valid.poke(false.B)
      dut.io.robPrepare.valid.expect(true.B)
      dut.clock.step()
      dut.io.robPrepare.valid.expect(false.B)

      val mismatches = Seq[() => Unit](
        () => pokeRound6Response(dut, transactionId = 0x161),
        () => pokeRound6Response(dut, cause = RecoveryCause.Branch),
        () => pokeRound6Response(dut, peId = 1),
        () => pokeRound6Response(dut, stid = 1),
        () => pokeRound6Response(dut, rid = 2),
        () => pokeRound6Response(dut, ridGeneration = 1),
        () => pokeRound6Response(dut, member = 1),
        () => pokeRound6Response(dut, residentGeneration = 1),
        () => pokeRound6Response(dut, bid = 1),
        () => pokeRound6Response(dut, brobGeneration = 1),
        () => pokeRound6Response(dut, redirectPc = 0x4200),
        () => pokeRound6Response(dut, newEpoch = 5),
        () => pokeRound6Response(dut, phase = RecoveryPhase.Apply))
      mismatches.foreach { pokeMismatch =>
        fireStableRound6Response(dut, pokeMismatch)
        dut.io.targets(0).prepare.valid.expect(false.B)
        dut.io.robAbort.valid.expect(false.B)
        dut.io.robPrepare.valid.expect(false.B)
      }

      fireStableRound6Response(dut,
        () => pokeRound6Response(dut, firstKilledRid = 2, lastKilledRid = 3))
      dut.io.targets(0).prepare.valid.expect(true.B)
      dut.io.targets(0).prepare.bits.transactionId.expect(0x160.U)
      dut.io.targets(0).prepare.bits.firstKilled.ridSlot.expect(2.U)
      dut.io.targets(0).prepare.bits.lastKilled.ridSlot.expect(3.U)
    }
  }

  test("RecoveryControl waits through mismatched ROB responses in WaitRobAbort") {
    simulate(new RecoveryControl(params, targetCount = 1)) { dut =>
      clearRecoveryControl(dut)
      pokeRound6SeedEvent(dut)
      dut.clock.step()

      dut.io.events(0).valid.poke(false.B)
      dut.io.robPrepare.ready.poke(true.B)
      dut.io.robPrepared.valid.poke(false.B)
      dut.io.robPrepare.valid.expect(true.B)
      dut.clock.step()
      dut.io.abort.poke(true.B)
      dut.clock.step()

      val mismatches = Seq[() => Unit](
        () => pokeRound6Response(dut, transactionId = 0x161),
        () => pokeRound6Response(dut, cause = RecoveryCause.Branch),
        () => pokeRound6Response(dut, peId = 1),
        () => pokeRound6Response(dut, stid = 1),
        () => pokeRound6Response(dut, rid = 2),
        () => pokeRound6Response(dut, ridGeneration = 1),
        () => pokeRound6Response(dut, member = 1),
        () => pokeRound6Response(dut, residentGeneration = 1),
        () => pokeRound6Response(dut, bid = 1),
        () => pokeRound6Response(dut, brobGeneration = 1),
        () => pokeRound6Response(dut, redirectPc = 0x4200),
        () => pokeRound6Response(dut, newEpoch = 5),
        () => pokeRound6Response(dut, phase = RecoveryPhase.Abort))
      mismatches.foreach { pokeMismatch =>
        fireStableRound6Response(dut, pokeMismatch)
        dut.io.targets(0).prepare.valid.expect(false.B)
        dut.io.targets(0).abort.valid.expect(false.B)
        dut.io.robAbort.valid.expect(false.B)
        dut.io.robPrepare.valid.expect(false.B)
      }

      fireStableRound6Response(dut,
        () => pokeRound6Response(dut, firstKilledRid = 2, lastKilledRid = 3))
      dut.io.robAbort.valid.expect(true.B)
      dut.io.robAbort.bits.transactionId.expect(0x160.U)
      dut.io.robAbort.bits.phase.expect(RecoveryPhase.Abort)
      dut.io.robAbort.bits.firstKilled.ridSlot.expect(2.U)
      dut.io.targets(0).abort.valid.expect(false.B)
      dut.io.targets(0).prepare.valid.expect(false.B)
    }
  }

  test("ROB simultaneous matching apply and abort is non-mutating") {
    simulate(new ROB(params)) { dut =>
      clearRob(dut)
      lane(dut.io.prepare.bits, 0, id = 230, rid = 0, groupCount = 2,
        stid = 0)
      lane(dut.io.prepare.bits, 1, id = 231, rid = 1, groupCount = 2,
        stid = 0)
      val ids = publish(dut, 2)
      dut.io.recoveryPrepare.valid.poke(true.B)
      dut.io.recoveryPrepare.bits.transactionId.poke(0x156.U)
      dut.io.recoveryPrepare.bits.phase.poke(RecoveryPhase.Prepare)
      dut.io.recoveryPrepare.bits.cause.poke(RecoveryCause.MemoryOrder)
      dut.io.recoveryPrepare.bits.trigger.poke(ids(1))
      dut.io.recoveryPrepare.ready.expect(true.B)
      dut.clock.step()
      val prepared = dut.io.recoveryPrepared.bits.peek()
      dut.io.recoveryPrepare.valid.poke(false.B)
      dut.io.recoveryApply.valid.poke(true.B)
      dut.io.recoveryApply.bits.poke(prepared)
      dut.io.recoveryApply.bits.phase.poke(RecoveryPhase.Apply)
      dut.io.recoveryAbort.valid.poke(true.B)
      dut.io.recoveryAbort.bits.poke(prepared)
      dut.io.recoveryAbort.bits.phase.poke(RecoveryPhase.Abort)
      dut.clock.step()

      dut.io.recoveryApply.valid.poke(false.B)
      dut.io.recoveryAbort.valid.poke(false.B)
      dut.io.ridTailSlot(0).expect(2.U)
      dut.io.recoveryPrepare.valid.poke(true.B)
      dut.io.recoveryPrepare.bits.poke(prepared)
      dut.io.recoveryPrepare.bits.transactionId.poke(0x157.U)
      dut.io.recoveryPrepare.bits.phase.poke(RecoveryPhase.Prepare)
      dut.io.recoveryPrepare.ready.expect(true.B)
    }
  }

  test("BROB simultaneous matching apply and abort is non-mutating") {
    simulate(new BROB(params)) { dut =>
      def clearBrob(): Unit = {
        dut.io.prepare.valid.poke(false.B)
        dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
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
      clearBrob()
      lane(dut.io.prepare.bits, 0, id = 240, rid = 0, groupCount = 1,
        stid = 0, member = 0, blockStart = true, blockStop = true)
      dut.io.prepare.valid.poke(true.B)
      dut.io.prepare.ready.expect(true.B)
      val id = dut.io.prepare.bits.entries(0).uop.decoded.rob.peek()
      dut.io.publishFire.poke(true.B)
      dut.clock.step()
      dut.io.prepare.valid.poke(false.B)
      dut.io.publishFire.poke(false.B)

      dut.io.recoveryPrepare.valid.poke(true.B)
      dut.io.recoveryPrepare.bits.transactionId.poke(0x158.U)
      dut.io.recoveryPrepare.bits.phase.poke(RecoveryPhase.Prepare)
      dut.io.recoveryPrepare.bits.cause.poke(RecoveryCause.MemoryOrder)
      dut.io.recoveryPrepare.bits.trigger.poke(id)
      dut.io.recoveryPrepare.bits.firstKilledValid.poke(true.B)
      dut.io.recoveryPrepare.bits.firstKilled.poke(id)
      dut.io.recoveryPrepare.bits.lastKilled.poke(id)
      dut.io.recoveryPrepare.bits.killedMemberCount.poke(1.U)
      dut.io.recoveryPrepare.bits.killedGroupCount.poke(1.U)
      dut.io.recoveryPrepare.ready.expect(true.B)
      dut.clock.step()
      val prepared = dut.io.recoveryPrepared.bits.peek()
      dut.io.recoveryPrepare.valid.poke(false.B)
      dut.io.recoveryApply.valid.poke(true.B)
      dut.io.recoveryApply.bits.poke(prepared)
      dut.io.recoveryApply.bits.phase.poke(RecoveryPhase.Apply)
      dut.io.recoveryAbort.valid.poke(true.B)
      dut.io.recoveryAbort.bits.poke(prepared)
      dut.io.recoveryAbort.bits.phase.poke(RecoveryPhase.Abort)
      dut.clock.step()

      dut.io.recoveryApply.valid.poke(false.B)
      dut.io.recoveryAbort.valid.poke(false.B)
      dut.io.release.valid.poke(true.B)
      dut.io.release.bits.count.poke(1.U)
      dut.io.release.bits.entries(0).poke(id)
      dut.io.releaseReady.expect(true.B)
    }
  }
}
