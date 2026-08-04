package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import chisel3.util.{Decoupled, Valid}
import linxcore.params.{CoreParams, ParamProfiles}
import linxcore.top.interface._
import org.scalatest.funsuite.AnyFunSuite

class RobBrobPublicationCoordinatorIO(val p: CoreParams) extends Bundle {
  val prepare = Flipped(Decoupled(new D3RenameGroup(p)))
  val robPrepared = Output(new OOORobPrepared(p))
  val brobPrepared = Output(new BROBPrepared(p))
  val robCommitReady = Input(Bool())
  val robCommit = Output(new OOORobCommitPreview(p))
  val robCommitValid = Output(Bool())
  val robCommitApply = Input(Bool())
  val robRelease = Flipped(Valid(new OOORobReleaseTxn(p)))
  val robReleaseReady = Output(Bool())
  val robReleaseApply = Input(Bool())
  val brobRelease = Flipped(Valid(new BROBReleaseTxn(p)))
  val brobReleaseReady = Output(Bool())
  val brobReleaseApply = Input(Bool())
  val robRecoveryPrepare = Flipped(Decoupled(new RecoveryPlan(p)))
  val robRecoveryPrepared = Valid(new RecoveryPlan(p))
  val robRecoveryApply = Flipped(Valid(new RecoveryPlan(p)))
  val robRecoveryAbort = Flipped(Valid(new RecoveryPlan(p)))
  val brobRecoveryPrepare = Flipped(Decoupled(new RecoveryPlan(p)))
  val brobRecoveryPrepared = Valid(new RecoveryPlan(p))
  val brobRecoveryApply = Flipped(Valid(new RecoveryPlan(p)))
  val brobRecoveryAbort = Flipped(Valid(new RecoveryPlan(p)))
}

class RobBrobPublicationCoordinator(val p: CoreParams) extends Module {
  val io = IO(new RobBrobPublicationCoordinatorIO(p))

  private val rob = Module(new ROB(p))
  private val brob = Module(new BROB(p))

  rob.io.prepare.valid := io.prepare.valid
  rob.io.prepare.bits := io.prepare.bits
  rob.io.publicationTransactionBase := 0.U
  brob.io.prepare.valid := io.prepare.valid
  brob.io.prepare.bits := io.prepare.bits
  rob.io.brobPrepared := brob.io.prepared
  brob.io.robPrepared := rob.io.prepared
  io.prepare.ready := rob.io.prepare.ready && brob.io.prepare.ready
  val publishFire = io.prepare.valid && io.prepare.ready
  rob.io.publishFire := publishFire
  brob.io.publishFire := publishFire
  io.robPrepared := rob.io.prepared
  io.brobPrepared := brob.io.prepared

  rob.io.completion.valid := false.B
  rob.io.completion.bits := 0.U.asTypeOf(rob.io.completion.bits)
  rob.io.commit.ready := io.robCommitReady
  io.robCommitValid := rob.io.commit.valid
  io.robCommit := rob.io.commit.bits
  rob.io.commitApply := io.robCommitApply

  rob.io.release.valid := io.robRelease.valid
  rob.io.release.bits := io.robRelease.bits
  io.robReleaseReady := rob.io.releaseReady
  rob.io.releaseApply := io.robReleaseApply
  brob.io.release.valid := io.brobRelease.valid
  brob.io.release.bits := io.brobRelease.bits
  io.brobReleaseReady := brob.io.releaseReady
  brob.io.releaseApply := io.brobReleaseApply

  rob.io.recoveryPrepare.valid := io.robRecoveryPrepare.valid
  rob.io.recoveryPrepare.bits := io.robRecoveryPrepare.bits
  io.robRecoveryPrepare.ready := rob.io.recoveryPrepare.ready
  io.robRecoveryPrepared := rob.io.recoveryPrepared
  rob.io.recoveryApply.valid := io.robRecoveryApply.valid
  rob.io.recoveryApply.bits := io.robRecoveryApply.bits
  rob.io.recoveryAbort.valid := io.robRecoveryAbort.valid
  rob.io.recoveryAbort.bits := io.robRecoveryAbort.bits
  for (source <- 0 until 2) {
    rob.io.recoveryCandidate(source).valid := false.B
    rob.io.recoveryCandidate(source).bits := 0.U.asTypeOf(
      rob.io.recoveryCandidate(source).bits)
  }

  brob.io.recoveryPrepare.valid := io.brobRecoveryPrepare.valid
  brob.io.recoveryPrepare.bits := io.brobRecoveryPrepare.bits
  io.brobRecoveryPrepare.ready := brob.io.recoveryPrepare.ready
  io.brobRecoveryPrepared := brob.io.recoveryPrepared
  brob.io.recoveryApply.valid := io.brobRecoveryApply.valid
  brob.io.recoveryApply.bits := io.brobRecoveryApply.bits
  brob.io.recoveryAbort.valid := io.brobRecoveryAbort.valid
  brob.io.recoveryAbort.bits := io.brobRecoveryAbort.bits
}

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
    dut.io.publicationTransactionBase.poke(0.U)
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
    group.memoryOrder.valid.poke(true.B)
    group.memoryOrder.stid.poke(stid.U)
    group.memoryOrder.count.poke((lane + 1).U)
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
      count: Int,
      residentGenerations: Seq[Int] = Seq.empty): Seq[RobIdentity] = {
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
      val residentGeneration =
        if (laneIndex < residentGenerations.length) residentGenerations(laneIndex)
        else 0
      dut.io.robPrepared.entries(laneIndex).rob.residentGeneration.poke(
        residentGeneration.U)
    }
    (0 until count).map(laneIndex =>
      dut.io.robPrepared.entries(laneIndex).rob.peek())
  }

  private def pokeBoundRob(
      target: RobIdentity,
      raw: RobIdentity,
      bid: Int,
      brobGeneration: Int,
      residentGeneration: Int): Unit = {
    target.poke(raw)
    target.bid.poke(bid.U)
    target.brobGeneration.poke(brobGeneration.U)
    target.residentGeneration.poke(residentGeneration.U)
  }

  private def clearCoordinator(dut: RobBrobPublicationCoordinator): Unit = {
    dut.io.prepare.valid.poke(false.B)
    dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
    dut.io.robCommitReady.poke(true.B)
    dut.io.robCommitApply.poke(false.B)
    dut.io.robRelease.valid.poke(false.B)
    dut.io.robRelease.bits.poke(0.U.asTypeOf(dut.io.robRelease.bits))
    dut.io.robReleaseApply.poke(false.B)
    dut.io.brobRelease.valid.poke(false.B)
    dut.io.brobRelease.bits.poke(0.U.asTypeOf(dut.io.brobRelease.bits))
    dut.io.brobReleaseApply.poke(false.B)
    dut.io.robRecoveryPrepare.valid.poke(false.B)
    dut.io.robRecoveryPrepare.bits.poke(0.U.asTypeOf(dut.io.robRecoveryPrepare.bits))
    dut.io.robRecoveryApply.valid.poke(false.B)
    dut.io.robRecoveryApply.bits.poke(0.U.asTypeOf(dut.io.robRecoveryApply.bits))
    dut.io.robRecoveryAbort.valid.poke(false.B)
    dut.io.robRecoveryAbort.bits.poke(0.U.asTypeOf(dut.io.robRecoveryAbort.bits))
    dut.io.brobRecoveryPrepare.valid.poke(false.B)
    dut.io.brobRecoveryPrepare.bits.poke(0.U.asTypeOf(dut.io.brobRecoveryPrepare.bits))
    dut.io.brobRecoveryApply.valid.poke(false.B)
    dut.io.brobRecoveryApply.bits.poke(0.U.asTypeOf(dut.io.brobRecoveryApply.bits))
    dut.io.brobRecoveryAbort.valid.poke(false.B)
    dut.io.brobRecoveryAbort.bits.poke(0.U.asTypeOf(dut.io.brobRecoveryAbort.bits))
  }

  private def retireCoordinatorHead(
      dut: RobBrobPublicationCoordinator,
      count: Int): Unit = {
    dut.io.robCommitReady.poke(true.B)
    dut.io.robCommitValid.expect(true.B)
    dut.io.robCommit.count.expect(count.U)
    dut.io.robCommitApply.poke(true.B)
    dut.clock.step()
    dut.io.robCommitApply.poke(false.B)
  }

  private def releaseCoordinator(
      dut: RobBrobPublicationCoordinator,
      ids: Seq[RobIdentity]): Unit = {
    dut.io.robRelease.valid.poke(true.B)
    dut.io.robRelease.bits.count.poke(ids.length.U)
    dut.io.brobRelease.valid.poke(true.B)
    dut.io.brobRelease.bits.count.poke(ids.length.U)
    ids.zipWithIndex.foreach { case (id, laneIndex) =>
      dut.io.robRelease.bits.lanes(laneIndex).valid.poke(true.B)
      dut.io.robRelease.bits.lanes(laneIndex).rob.poke(id)
      dut.io.brobRelease.bits.entries(laneIndex).poke(id)
    }
    dut.io.robReleaseReady.expect(true.B)
    dut.io.brobReleaseReady.expect(true.B)
    dut.io.robReleaseApply.poke(true.B)
    dut.io.brobReleaseApply.poke(true.B)
    dut.clock.step()
    dut.io.robRelease.valid.poke(false.B)
    dut.io.robReleaseApply.poke(false.B)
    dut.io.brobRelease.valid.poke(false.B)
    dut.io.brobReleaseApply.poke(false.B)
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

  test("ROB reuses the recovered tail as the next commit head after survivor retirement") {
    simulate(new ROB(params)) { dut =>
      clearRob(dut)
      lane(dut.io.prepare.bits, 0, id = 300, rid = 0, groupCount = 3, stid = 0)
      lane(dut.io.prepare.bits, 1, id = 301, rid = 1, groupCount = 3, stid = 0)
      lane(dut.io.prepare.bits, 2, id = 302, rid = 2, groupCount = 3, stid = 0)
      (0 until 3).foreach { index =>
        dut.io.prepare.bits.entries(index).earlyRobComplete.poke(true.B)
      }
      val ids = publish(dut, 3)

      dut.io.recoveryPrepare.valid.poke(true.B)
      dut.io.recoveryPrepare.bits.transactionId.poke(0x160.U)
      dut.io.recoveryPrepare.bits.phase.poke(RecoveryPhase.Prepare)
      dut.io.recoveryPrepare.bits.cause.poke(RecoveryCause.Branch)
      dut.io.recoveryPrepare.bits.trigger.poke(ids.head)
      dut.io.recoveryPrepare.ready.expect(true.B)
      val prepared = dut.io.recoveryPrepared.bits.peek()
      dut.clock.step()
      dut.io.recoveryPrepare.valid.poke(false.B)
      dut.io.recoveryApply.valid.poke(true.B)
      dut.io.recoveryApply.bits.poke(prepared)
      dut.io.recoveryApply.bits.phase.poke(RecoveryPhase.Apply)
      dut.clock.step()
      dut.io.recoveryApply.valid.poke(false.B)

      dut.io.commit.valid.expect(true.B)
      dut.io.commit.bits.count.expect(1.U)
      dut.io.commit.bits.head.expect(ids.head)
      dut.io.release.valid.poke(true.B)
      dut.io.release.bits.count.poke(1.U)
      dut.io.release.bits.lanes.head.valid.poke(true.B)
      dut.io.release.bits.lanes.head.rob.poke(ids.head)
      dut.io.releaseReady.expect(true.B)
      dut.io.commit.ready.poke(true.B)
      dut.io.commitApply.poke(true.B)
      dut.io.releaseApply.poke(true.B)
      dut.clock.step()
      dut.io.commitApply.poke(false.B)
      dut.io.release.valid.poke(false.B)
      dut.io.releaseApply.poke(false.B)
      dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
      lane(dut.io.prepare.bits, 0, id = 303, rid = 1, groupCount = 1, stid = 0)
      dut.io.prepare.bits.entries.head.earlyRobComplete.poke(true.B)
      val redirected = publish(dut, 1).head
      dut.io.commit.valid.expect(true.B)
      dut.io.commit.bits.count.expect(1.U)
      dut.io.commit.bits.head.expect(redirected)
      dut.io.commit.bits.entries.head.valid.expect(true.B)
      dut.io.commit.bits.entries.head.commit.trap.valid.expect(false.B)
    }
  }

  test("ROB staletrap is absent after recovery removes the last row") {
    simulate(new ROB(params)) { dut =>
      clearRob(dut)
      dut.io.commit.ready.poke(true.B)
      lane(dut.io.prepare.bits, 0, id = 310, rid = 0, groupCount = 1,
        stid = 0)
      dut.io.prepare.bits.entries.head.earlyRobComplete.poke(true.B)
      dut.io.prepare.bits.entries.head.trap.valid.poke(true.B)
      dut.io.prepare.bits.entries.head.trap.cause.poke(7.U)
      val id = publish(dut, 1).head
      dut.io.commit.valid.expect(true.B)

      dut.io.recoveryPrepare.valid.poke(true.B)
      dut.io.recoveryPrepare.bits.transactionId.poke(0x162.U)
      dut.io.recoveryPrepare.bits.phase.poke(RecoveryPhase.Prepare)
      dut.io.recoveryPrepare.bits.cause.poke(RecoveryCause.Exception)
      dut.io.recoveryPrepare.bits.trigger.poke(id)
      dut.io.recoveryPrepare.bits.firstKilledValid.poke(true.B)
      dut.io.recoveryPrepare.bits.firstKilled.poke(id)
      dut.io.recoveryPrepare.bits.lastKilled.poke(id)
      dut.io.recoveryPrepare.bits.killedMemberCount.poke(1.U)
      dut.io.recoveryPrepare.bits.killedGroupCount.poke(1.U)
      dut.io.recoveryPrepare.ready.expect(true.B)
      val prepared = dut.io.recoveryPrepared.bits.peek()
      dut.clock.step()
      dut.io.recoveryPrepare.valid.poke(false.B)
      dut.io.recoveryApply.valid.poke(true.B)
      dut.io.recoveryApply.bits.poke(prepared)
      dut.io.recoveryApply.bits.phase.poke(RecoveryPhase.Apply)
      dut.clock.step()
      dut.io.recoveryApply.valid.poke(false.B)

      dut.io.commit.valid.expect(false.B)
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

  test("full ROB branch recovery at the youngest member preserves the memory tail") {
    val base = params
    val p = base.copy(ooo = base.ooo.copy(
      robGroupsPerStid = 4,
      maxInstructionsPerRobGroup = 1,
      robBankCount = 4,
      brobEntriesPerStid = 4))
    simulate(new ROB(p)) { dut =>
      clearRob(dut)
      dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
      for (laneIndex <- 0 until 4) {
        lane(dut.io.prepare.bits, laneIndex, id = 80 + laneIndex,
          rid = laneIndex, groupCount = 4, stid = 0, member = 0)
        val row = dut.io.prepare.bits.entries(laneIndex)
        row.uop.decoded.memory.valid.poke(true.B)
        row.uop.decoded.memory.isLoad.poke(true.B)
        row.uop.decoded.memory.requestCount.poke(1.U)
        row.memoryOrder.requestCount.poke(1.U)
        row.memoryOrder.firstLsid.poke(laneIndex.U)
        row.memoryOrder.firstLid.poke(laneIndex.U)
      }
      dut.io.prepare.bits.memoryOrder.after.lsid.poke(4.U)
      dut.io.prepare.bits.memoryOrder.after.lid.poke(4.U)
      val ids = publish(dut, 4)

      dut.io.recoveryPrepare.valid.poke(true.B)
      dut.io.recoveryPrepare.bits.transactionId.poke(0x4f.U)
      dut.io.recoveryPrepare.bits.phase.poke(RecoveryPhase.Prepare)
      dut.io.recoveryPrepare.bits.cause.poke(RecoveryCause.Branch)
      dut.io.recoveryPrepare.bits.trigger.poke(ids.last)
      dut.io.recoveryPrepare.ready.expect(true.B)
      dut.io.recoveryPrepared.valid.expect(true.B)
      dut.io.recoveryPrepared.bits.firstKilledValid.expect(false.B)
      dut.io.recoveryPrepared.bits.killedMemberCount.expect(0.U)
      dut.io.recoveryPrepared.bits.killedGroupCount.expect(0.U)
      dut.io.recoveryPrepared.bits.survivingTail.expect(ids.last)
      dut.io.memoryRecoveryPrepared.oldTail.lsid.expect(4.U)
      dut.io.memoryRecoveryPrepared.newTail.lsid.expect(4.U)
      dut.io.memoryRecoveryPrepared.oldTail.lid.expect(4.U)
      dut.io.memoryRecoveryPrepared.newTail.lid.expect(4.U)
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
        val ids = bindBrobPrepared(dut, 1)
        dut.io.prepare.ready.expect(true.B)
        dut.io.publishFire.poke(true.B)
        dut.clock.step()
        dut.io.prepare.valid.poke(false.B)
        dut.io.publishFire.poke(false.B)
        ids(0)
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
      bindBrobPrepared(dut, 1)
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
        val ids = bindBrobPrepared(dut, 1)
        dut.io.prepare.ready.expect(true.B)
        dut.io.publishFire.poke(true.B)
        dut.clock.step()
        dut.io.prepare.valid.poke(false.B)
        dut.io.publishFire.poke(false.B)
        ids(0)
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
      val openIds = bindBrobPrepared(dut, 2)
      dut.io.prepare.ready.expect(true.B)
      val survivor = openIds(0)
      val killed = openIds(1)
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
      val closingIds = bindBrobPrepared(dut, 1)
      dut.io.prepare.ready.expect(true.B)
      val closing = closingIds(0)
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

  test("BROB reopens a released recovery survivor for redirected closure") {
    simulate(new BROB(params)) { dut =>
      clearBrob(dut)
      dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
      lane(dut.io.prepare.bits, 0, id = 320, rid = 0, groupCount = 3,
        stid = 0, member = 0, blockStart = true, blockStop = false)
      lane(dut.io.prepare.bits, 1, id = 321, rid = 1, groupCount = 3,
        stid = 0, member = 0, blockStart = false, blockStop = false)
      lane(dut.io.prepare.bits, 2, id = 322, rid = 2, groupCount = 3,
        stid = 0, member = 0, blockStart = false, blockStop = true)
      dut.io.prepare.valid.poke(true.B)
      val initial = bindBrobPrepared(dut, 3)
      dut.io.prepare.ready.expect(true.B)
      dut.io.publishFire.poke(true.B)
      dut.clock.step()
      dut.io.prepare.valid.poke(false.B)
      dut.io.publishFire.poke(false.B)

      dut.io.recoveryPrepare.valid.poke(true.B)
      dut.io.recoveryPrepare.bits.transactionId.poke(0x161.U)
      dut.io.recoveryPrepare.bits.phase.poke(RecoveryPhase.Prepare)
      dut.io.recoveryPrepare.bits.cause.poke(RecoveryCause.Branch)
      dut.io.recoveryPrepare.bits.trigger.poke(initial.head)
      dut.io.recoveryPrepare.bits.survivingTailValid.poke(true.B)
      dut.io.recoveryPrepare.bits.survivingTail.poke(initial.head)
      dut.io.recoveryPrepare.bits.firstKilledValid.poke(true.B)
      dut.io.recoveryPrepare.bits.firstKilled.poke(initial(1))
      dut.io.recoveryPrepare.bits.lastKilled.poke(initial(2))
      dut.io.recoveryPrepare.bits.killedMemberCount.poke(2.U)
      dut.io.recoveryPrepare.bits.killedGroupCount.poke(2.U)
      dut.io.recoveryPrepare.ready.expect(true.B)
      val prepared = dut.io.recoveryPrepared.bits.peek()
      dut.clock.step()
      dut.io.recoveryPrepare.valid.poke(false.B)
      dut.io.recoveryApply.valid.poke(true.B)
      dut.io.recoveryApply.bits.poke(prepared)
      dut.io.recoveryApply.bits.phase.poke(RecoveryPhase.Apply)
      dut.clock.step()
      dut.io.recoveryApply.valid.poke(false.B)

      dut.io.release.valid.poke(true.B)
      dut.io.release.bits.count.poke(1.U)
      dut.io.release.bits.entries.head.poke(initial.head)
      dut.io.releaseReady.expect(true.B)
      dut.io.releaseApply.poke(true.B)
      dut.clock.step()
      dut.io.release.valid.poke(false.B)
      dut.io.releaseApply.poke(false.B)
      dut.io.debugUsed(0).expect(0.U)

      dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
      lane(dut.io.prepare.bits, 0, id = 323, rid = 1, groupCount = 1,
        stid = 0, member = 0, blockStart = false, blockStop = true)
      dut.io.prepare.valid.poke(true.B)
      val redirected = bindBrobPrepared(dut, 1).head
      dut.io.prepared.entries.head.allocated.expect(true.B)
      dut.io.prepared.entries.head.bid.expect(1.U)
      dut.io.prepare.ready.expect(true.B)
      dut.io.publishFire.poke(true.B)
      dut.clock.step()
      dut.io.prepare.valid.poke(false.B)
      dut.io.publishFire.poke(false.B)
      dut.io.debugUsed(0).expect(1.U)

      dut.io.release.valid.poke(true.B)
      dut.io.release.bits.count.poke(1.U)
      dut.io.release.bits.entries.head.poke(redirected)
      dut.io.releaseReady.expect(true.B)
    }
  }

  test("BROB recovery shortens a closed block that straddles the killed suffix") {
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
      def publishClosedBlock(
          baseId: Int,
          rid: Int,
          stid: Int = 0): Seq[RobIdentity] = {
        dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
        lane(dut.io.prepare.bits, 0, id = baseId, rid = rid, groupCount = 1,
          stid = stid, member = 0, blockStart = true, blockStop = false)
        lane(dut.io.prepare.bits, 1, id = baseId + 1, rid = rid, groupCount = 1,
          stid = stid, member = 1, blockStart = false, blockStop = true)
        dut.io.prepare.valid.poke(true.B)
        val ids = bindBrobPrepared(dut, 2)
        dut.io.prepare.ready.expect(true.B)
        dut.io.prepared.entries(0).bid.expect(rid.U)
        dut.io.prepared.entries(1).bid.expect(rid.U)
        dut.io.publishFire.poke(true.B)
        dut.clock.step()
        dut.io.prepare.valid.poke(false.B)
        dut.io.publishFire.poke(false.B)
        ids
      }

      clearBrob()
      val closed = publishClosedBlock(baseId = 70, rid = 0)
      val survivor = closed(0)
      val killed = closed(1)
      dut.io.recoveryPrepare.valid.poke(true.B)
      dut.io.recoveryPrepare.bits.transactionId.poke(0x63.U)
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
      val prepared = dut.io.recoveryPrepared.bits.peek()
      dut.io.recoveryPrepare.valid.poke(false.B)
      dut.io.recoveryApply.valid.poke(true.B)
      dut.io.recoveryApply.bits.poke(prepared)
      dut.io.recoveryApply.bits.phase.poke(RecoveryPhase.Apply)
      dut.clock.step()
      dut.io.recoveryApply.valid.poke(false.B)

      dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
      lane(dut.io.prepare.bits, 0, id = 72, rid = 1, groupCount = 1,
        stid = 0, member = 0, blockStart = true, blockStop = true)
      dut.io.prepare.valid.poke(true.B)
      val nextIds = bindBrobPrepared(dut, 1)
      dut.io.prepare.ready.expect(true.B)
      dut.io.prepared.entries(0).bid.expect(1.U)
      dut.io.prepared.entries(0).brobGeneration.expect(0.U)
      val next = nextIds(0)
      dut.io.publishFire.poke(true.B)
      dut.clock.step()
      dut.io.prepare.valid.poke(false.B)
      dut.io.publishFire.poke(false.B)

      dut.io.release.valid.poke(true.B)
      dut.io.release.bits.count.poke(1.U)
      dut.io.release.bits.entries(0).poke(survivor)
      dut.io.releaseReady.expect(true.B)
      dut.io.releaseApply.poke(true.B)
      dut.clock.step()
      dut.io.releaseApply.poke(false.B)
      dut.io.release.bits.entries(0).poke(next)
      dut.io.releaseReady.expect(true.B)
    }
  }

  test("BROB shortened cross-group block releases through its surviving tail") {
    simulate(new BROB(params)) { dut =>
      clearBrob(dut)
      dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
      lane(dut.io.prepare.bits, 0, id = 80, rid = 0, groupCount = 3,
        blockStart = true)
      lane(dut.io.prepare.bits, 1, id = 81, rid = 1, groupCount = 3)
      lane(dut.io.prepare.bits, 2, id = 82, rid = 2, groupCount = 3,
        blockStop = true)
      dut.io.prepare.valid.poke(true.B)
      val ids = bindBrobPrepared(dut, 3)
      dut.io.prepare.ready.expect(true.B)
      dut.io.publishFire.poke(true.B)
      dut.clock.step()
      dut.io.prepare.valid.poke(false.B)
      dut.io.publishFire.poke(false.B)

      val survivor = ids(1)
      val killed = ids(2)
      dut.io.recoveryPrepare.valid.poke(true.B)
      dut.io.recoveryPrepare.bits.transactionId.poke(0x64.U)
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
      val prepared = dut.io.recoveryPrepared.bits.peek()
      dut.io.recoveryPrepare.valid.poke(false.B)
      dut.io.recoveryApply.valid.poke(true.B)
      dut.io.recoveryApply.bits.poke(prepared)
      dut.io.recoveryApply.bits.phase.poke(RecoveryPhase.Apply)
      dut.clock.step()
      dut.io.recoveryApply.valid.poke(false.B)

      dut.io.release.valid.poke(true.B)
      dut.io.release.bits.count.poke(2.U)
      dut.io.release.bits.entries(0).poke(ids(0))
      dut.io.release.bits.entries(1).poke(survivor)
      dut.io.releaseReady.expect(true.B)
    }
  }

  test("BROB recovery reuses wholly killed younger slots after a closed straddler") {
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
      def publishClosedBlock(baseId: Int, bid: Int): Seq[RobIdentity] = {
        dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
        lane(dut.io.prepare.bits, 0, id = baseId, rid = bid, groupCount = 1,
          stid = 0, member = 0, blockStart = true, blockStop = false)
        lane(dut.io.prepare.bits, 1, id = baseId + 1, rid = bid, groupCount = 1,
          stid = 0, member = 1, blockStart = false, blockStop = true)
        dut.io.prepare.valid.poke(true.B)
        val ids = bindBrobPrepared(dut, 2)
        dut.io.prepare.ready.expect(true.B)
        dut.io.prepared.entries(0).bid.expect(bid.U)
        dut.io.publishFire.poke(true.B)
        dut.clock.step()
        dut.io.prepare.valid.poke(false.B)
        dut.io.publishFire.poke(false.B)
        ids
      }
      def publishSingleBlock(id: Int, bid: Int): RobIdentity = {
        dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
        lane(dut.io.prepare.bits, 0, id = id, rid = bid, groupCount = 1,
          stid = 0, member = 0, blockStart = true, blockStop = true)
        dut.io.prepare.valid.poke(true.B)
        val ids = bindBrobPrepared(dut, 1)
        dut.io.prepare.ready.expect(true.B)
        dut.io.prepared.entries(0).bid.expect(bid.U)
        dut.io.publishFire.poke(true.B)
        dut.clock.step()
        dut.io.prepare.valid.poke(false.B)
        dut.io.publishFire.poke(false.B)
        ids(0)
      }

      clearBrob()
      val straddler = publishClosedBlock(baseId = 90, bid = 0)
      val younger = publishSingleBlock(id = 92, bid = 1)
      val survivor = straddler(0)
      val killedInsideStraddler = straddler(1)
      dut.io.recoveryPrepare.valid.poke(true.B)
      dut.io.recoveryPrepare.bits.transactionId.poke(0x65.U)
      dut.io.recoveryPrepare.bits.phase.poke(RecoveryPhase.Prepare)
      dut.io.recoveryPrepare.bits.cause.poke(RecoveryCause.Branch)
      dut.io.recoveryPrepare.bits.trigger.poke(survivor)
      dut.io.recoveryPrepare.bits.survivingTailValid.poke(true.B)
      dut.io.recoveryPrepare.bits.survivingTail.poke(survivor)
      dut.io.recoveryPrepare.bits.firstKilledValid.poke(true.B)
      dut.io.recoveryPrepare.bits.firstKilled.poke(killedInsideStraddler)
      dut.io.recoveryPrepare.bits.lastKilled.poke(younger)
      dut.io.recoveryPrepare.bits.killedMemberCount.poke(2.U)
      dut.io.recoveryPrepare.bits.killedGroupCount.poke(2.U)
      dut.io.recoveryPrepare.ready.expect(true.B)
      dut.clock.step()
      val prepared = dut.io.recoveryPrepared.bits.peek()
      dut.io.recoveryPrepare.valid.poke(false.B)
      dut.io.recoveryApply.valid.poke(true.B)
      dut.io.recoveryApply.bits.poke(prepared)
      dut.io.recoveryApply.bits.phase.poke(RecoveryPhase.Apply)
      dut.clock.step()
      dut.io.recoveryApply.valid.poke(false.B)

      val first = publishSingleBlock(id = 93, bid = 1)
      val second = publishSingleBlock(id = 94, bid = 2)
      val third = publishSingleBlock(id = 95, bid = 3)
      dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
      lane(dut.io.prepare.bits, 0, id = 96, rid = 0, groupCount = 1,
        stid = 0, member = 0, blockStart = true, blockStop = true)
      dut.io.prepare.valid.poke(true.B)
      dut.io.prepare.ready.expect(false.B)
      dut.io.prepare.valid.poke(false.B)

      dut.io.release.valid.poke(true.B)
      dut.io.release.bits.count.poke(1.U)
      dut.io.release.bits.entries(0).poke(survivor)
      dut.io.releaseReady.expect(true.B)
      dut.io.releaseApply.poke(true.B)
      dut.clock.step()
      dut.io.releaseApply.poke(false.B)
      dut.io.release.bits.entries(0).poke(first)
      dut.io.releaseReady.expect(true.B)
      dut.io.release.bits.entries(0).poke(second)
      dut.io.releaseReady.expect(false.B)
      dut.io.release.bits.entries(0).poke(third)
      dut.io.releaseReady.expect(false.B)
    }
  }

  test("BROB recovery computes wrapped straddler successor generation") {
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
      def publishSingleBlock(id: Int, bid: Int, gen: Int): RobIdentity = {
        dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
        lane(dut.io.prepare.bits, 0, id = id, rid = bid, groupCount = 1,
          stid = 0, member = 0, blockStart = true, blockStop = true)
        dut.io.prepare.bits.entries(0).uop.decoded.rob.brobGeneration.poke(gen.U)
        dut.io.prepare.valid.poke(true.B)
        val ids = bindBrobPrepared(dut, 1)
        dut.io.prepare.ready.expect(true.B)
        dut.io.prepared.entries(0).bid.expect(bid.U)
        dut.io.prepared.entries(0).brobGeneration.expect(gen.U)
        dut.io.publishFire.poke(true.B)
        dut.clock.step()
        dut.io.prepare.valid.poke(false.B)
        dut.io.publishFire.poke(false.B)
        ids(0)
      }
      def releaseOne(rob: RobIdentity): Unit = {
        dut.io.release.valid.poke(true.B)
        dut.io.release.bits.count.poke(1.U)
        dut.io.release.bits.entries(0).poke(rob)
        dut.io.releaseReady.expect(true.B)
        dut.io.releaseApply.poke(true.B)
        dut.clock.step()
        dut.io.release.valid.poke(false.B)
        dut.io.releaseApply.poke(false.B)
      }
      def publishClosedBlock(baseId: Int, bid: Int, gen: Int): Seq[RobIdentity] = {
        dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
        lane(dut.io.prepare.bits, 0, id = baseId, rid = bid, groupCount = 1,
          stid = 0, member = 0, blockStart = true, blockStop = false)
        lane(dut.io.prepare.bits, 1, id = baseId + 1, rid = bid, groupCount = 1,
          stid = 0, member = 1, blockStart = false, blockStop = true)
        dut.io.prepare.bits.entries(0).uop.decoded.rob.brobGeneration.poke(gen.U)
        dut.io.prepare.bits.entries(1).uop.decoded.rob.brobGeneration.poke(gen.U)
        dut.io.prepare.valid.poke(true.B)
        val ids = bindBrobPrepared(dut, 2)
        dut.io.prepare.ready.expect(true.B)
        dut.io.prepared.entries(0).bid.expect(bid.U)
        dut.io.prepared.entries(0).brobGeneration.expect(gen.U)
        dut.io.publishFire.poke(true.B)
        dut.clock.step()
        dut.io.prepare.valid.poke(false.B)
        dut.io.publishFire.poke(false.B)
        ids
      }

      clearBrob()
      releaseOne(publishSingleBlock(id = 100, bid = 0, gen = 0))
      releaseOne(publishSingleBlock(id = 101, bid = 1, gen = 0))
      releaseOne(publishSingleBlock(id = 102, bid = 2, gen = 0))
      val straddler = publishClosedBlock(baseId = 103, bid = 3, gen = 0)
      val younger = publishSingleBlock(id = 105, bid = 0, gen = 1)
      val survivor = straddler(0)
      val killedInsideStraddler = straddler(1)

      dut.io.recoveryPrepare.valid.poke(true.B)
      dut.io.recoveryPrepare.bits.transactionId.poke(0x66.U)
      dut.io.recoveryPrepare.bits.phase.poke(RecoveryPhase.Prepare)
      dut.io.recoveryPrepare.bits.cause.poke(RecoveryCause.Branch)
      dut.io.recoveryPrepare.bits.trigger.poke(survivor)
      dut.io.recoveryPrepare.bits.survivingTailValid.poke(true.B)
      dut.io.recoveryPrepare.bits.survivingTail.poke(survivor)
      dut.io.recoveryPrepare.bits.firstKilledValid.poke(true.B)
      dut.io.recoveryPrepare.bits.firstKilled.poke(killedInsideStraddler)
      dut.io.recoveryPrepare.bits.lastKilled.poke(younger)
      dut.io.recoveryPrepare.bits.killedMemberCount.poke(2.U)
      dut.io.recoveryPrepare.bits.killedGroupCount.poke(2.U)
      dut.io.recoveryPrepare.ready.expect(true.B)
      dut.clock.step()
      val prepared = dut.io.recoveryPrepared.bits.peek()
      dut.io.recoveryPrepare.valid.poke(false.B)
      dut.io.recoveryApply.valid.poke(true.B)
      dut.io.recoveryApply.bits.poke(prepared)
      dut.io.recoveryApply.bits.phase.poke(RecoveryPhase.Apply)
      dut.clock.step()
      dut.io.recoveryApply.valid.poke(false.B)

      val next = publishSingleBlock(id = 106, bid = 0, gen = 1)
      dut.io.release.valid.poke(true.B)
      dut.io.release.bits.count.poke(1.U)
      dut.io.release.bits.entries(0).poke(survivor)
      dut.io.releaseReady.expect(true.B)
      dut.io.releaseApply.poke(true.B)
      dut.clock.step()
      dut.io.releaseApply.poke(false.B)
      dut.io.release.bits.entries(0).poke(next)
      dut.io.releaseReady.expect(true.B)
    }
  }

  test("BROB recovery rejects malformed suffix BID and generation identities") {
    def expectRejected(corrupt: BROB => Unit): Unit = {
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
        def publishClosedBlock(baseId: Int, bid: Int): Seq[RobIdentity] = {
          dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
          lane(dut.io.prepare.bits, 0, id = baseId, rid = bid, groupCount = 1,
            stid = 0, member = 0, blockStart = true, blockStop = false)
          lane(dut.io.prepare.bits, 1, id = baseId + 1, rid = bid, groupCount = 1,
            stid = 0, member = 1, blockStart = false, blockStop = true)
          dut.io.prepare.valid.poke(true.B)
          val ids = bindBrobPrepared(dut, 2)
          dut.io.prepare.ready.expect(true.B)
          dut.io.publishFire.poke(true.B)
          dut.clock.step()
          dut.io.prepare.valid.poke(false.B)
          dut.io.publishFire.poke(false.B)
          ids
        }
        def publishSingleBlock(id: Int, bid: Int): RobIdentity = {
          dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
          lane(dut.io.prepare.bits, 0, id = id, rid = bid, groupCount = 1,
            stid = 0, member = 0, blockStart = true, blockStop = true)
          dut.io.prepare.valid.poke(true.B)
          val ids = bindBrobPrepared(dut, 1)
          dut.io.prepare.ready.expect(true.B)
          dut.io.publishFire.poke(true.B)
          dut.clock.step()
          dut.io.prepare.valid.poke(false.B)
          dut.io.publishFire.poke(false.B)
          ids(0)
        }

        clearBrob()
        val straddler = publishClosedBlock(baseId = 110, bid = 0)
        val younger = publishSingleBlock(id = 112, bid = 1)
        dut.io.recoveryPrepare.valid.poke(true.B)
        dut.io.recoveryPrepare.bits.transactionId.poke(0x67.U)
        dut.io.recoveryPrepare.bits.phase.poke(RecoveryPhase.Prepare)
        dut.io.recoveryPrepare.bits.cause.poke(RecoveryCause.Branch)
        dut.io.recoveryPrepare.bits.trigger.poke(straddler(0))
        dut.io.recoveryPrepare.bits.survivingTailValid.poke(true.B)
        dut.io.recoveryPrepare.bits.survivingTail.poke(straddler(0))
        dut.io.recoveryPrepare.bits.firstKilledValid.poke(true.B)
        dut.io.recoveryPrepare.bits.firstKilled.poke(straddler(1))
        dut.io.recoveryPrepare.bits.lastKilled.poke(younger)
        dut.io.recoveryPrepare.bits.killedMemberCount.poke(2.U)
        dut.io.recoveryPrepare.bits.killedGroupCount.poke(2.U)
        corrupt(dut)
        dut.io.recoveryPrepare.ready.expect(false.B)
        dut.io.recoveryPrepare.valid.poke(false.B)

        dut.io.release.valid.poke(true.B)
        dut.io.release.bits.count.poke(2.U)
        dut.io.release.bits.entries(0).poke(straddler(0))
        dut.io.release.bits.entries(1).poke(straddler(1))
        dut.io.releaseReady.expect(true.B)
      }
    }

    expectRejected(_.io.recoveryPrepare.bits.firstKilled.bid.poke(1.U))
    expectRejected(_.io.recoveryPrepare.bits.firstKilled.brobGeneration.poke(1.U))
    expectRejected(_.io.recoveryPrepare.bits.lastKilled.bid.poke(0.U))
    expectRejected(_.io.recoveryPrepare.bits.lastKilled.brobGeneration.poke(1.U))
    expectRejected(_.io.recoveryPrepare.bits.firstKilled.bid.poke(
      params.ooo.brobEntriesPerStid.U))
    expectRejected(_.io.recoveryPrepare.bits.lastKilled.bid.poke(
      params.ooo.brobEntriesPerStid.U))
  }

  test("BROB recovery rejects stale reused-BID generation after wrap") {
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
      def publishSingleBlock(id: Int, bid: Int, gen: Int): RobIdentity = {
        dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
        lane(dut.io.prepare.bits, 0, id = id, rid = bid, groupCount = 1,
          stid = 0, member = 0, blockStart = true, blockStop = true)
        dut.io.prepare.bits.entries(0).uop.decoded.rob.brobGeneration.poke(gen.U)
        dut.io.prepare.valid.poke(true.B)
        val ids = bindBrobPrepared(dut, 1)
        dut.io.prepare.ready.expect(true.B)
        dut.io.publishFire.poke(true.B)
        dut.clock.step()
        dut.io.prepare.valid.poke(false.B)
        dut.io.publishFire.poke(false.B)
        ids(0)
      }
      def releaseOne(rob: RobIdentity): Unit = {
        dut.io.release.valid.poke(true.B)
        dut.io.release.bits.count.poke(1.U)
        dut.io.release.bits.entries(0).poke(rob)
        dut.io.releaseReady.expect(true.B)
        dut.io.releaseApply.poke(true.B)
        dut.clock.step()
        dut.io.release.valid.poke(false.B)
        dut.io.releaseApply.poke(false.B)
      }
      def publishClosedBlock(baseId: Int, bid: Int, gen: Int): Seq[RobIdentity] = {
        dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
        lane(dut.io.prepare.bits, 0, id = baseId, rid = bid, groupCount = 1,
          stid = 0, member = 0, blockStart = true, blockStop = false)
        lane(dut.io.prepare.bits, 1, id = baseId + 1, rid = bid, groupCount = 1,
          stid = 0, member = 1, blockStart = false, blockStop = true)
        dut.io.prepare.bits.entries(0).uop.decoded.rob.brobGeneration.poke(gen.U)
        dut.io.prepare.bits.entries(1).uop.decoded.rob.brobGeneration.poke(gen.U)
        dut.io.prepare.valid.poke(true.B)
        val ids = bindBrobPrepared(dut, 2)
        dut.io.prepare.ready.expect(true.B)
        dut.io.publishFire.poke(true.B)
        dut.clock.step()
        dut.io.prepare.valid.poke(false.B)
        dut.io.publishFire.poke(false.B)
        ids
      }

      clearBrob()
      releaseOne(publishSingleBlock(id = 120, bid = 0, gen = 0))
      releaseOne(publishSingleBlock(id = 121, bid = 1, gen = 0))
      releaseOne(publishSingleBlock(id = 122, bid = 2, gen = 0))
      val straddler = publishClosedBlock(baseId = 123, bid = 3, gen = 0)
      val younger = publishSingleBlock(id = 125, bid = 0, gen = 1)
      dut.io.recoveryPrepare.valid.poke(true.B)
      dut.io.recoveryPrepare.bits.transactionId.poke(0x68.U)
      dut.io.recoveryPrepare.bits.phase.poke(RecoveryPhase.Prepare)
      dut.io.recoveryPrepare.bits.cause.poke(RecoveryCause.Branch)
      dut.io.recoveryPrepare.bits.trigger.poke(straddler(0))
      dut.io.recoveryPrepare.bits.survivingTailValid.poke(true.B)
      dut.io.recoveryPrepare.bits.survivingTail.poke(straddler(0))
      dut.io.recoveryPrepare.bits.firstKilledValid.poke(true.B)
      dut.io.recoveryPrepare.bits.firstKilled.poke(straddler(1))
      dut.io.recoveryPrepare.bits.lastKilled.poke(younger)
      dut.io.recoveryPrepare.bits.lastKilled.brobGeneration.poke(0.U)
      dut.io.recoveryPrepare.bits.killedMemberCount.poke(2.U)
      dut.io.recoveryPrepare.bits.killedGroupCount.poke(2.U)
      dut.io.recoveryPrepare.ready.expect(false.B)
      dut.io.recoveryPrepare.valid.poke(false.B)

      dut.io.release.valid.poke(true.B)
      dut.io.release.bits.count.poke(2.U)
      dut.io.release.bits.entries(0).poke(straddler(0))
      dut.io.release.bits.entries(1).poke(straddler(1))
      dut.io.releaseReady.expect(true.B)
      dut.io.releaseApply.poke(true.B)
      dut.clock.step()
      dut.io.releaseApply.poke(false.B)
      dut.io.release.bits.count.poke(1.U)
      dut.io.release.bits.entries(0).poke(younger)
      dut.io.releaseReady.expect(true.B)
    }
  }

  test("BROB publishes allocator-bound identities when D3 residency is unbound") {
    simulate(new BROB(params)) { dut =>
      def publishRawSingle(
          id: Int,
          rid: Int,
          ridGeneration: Int = 0,
          residentGeneration: Int = 1): (RobIdentity, Int, Int) = {
        dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
        lane(dut.io.prepare.bits, 0, id = id, rid = rid, groupCount = 1,
          stid = 0, member = 0, blockStart = true, blockStop = true)
        dut.io.prepare.bits.entries(0).uop.decoded.rob.ridGeneration.poke(
          ridGeneration.U)
        dut.io.prepare.bits.entries(0).uop.decoded.rob.bid.poke(0.U)
        dut.io.prepare.bits.entries(0).uop.decoded.rob.brobGeneration.poke(0.U)
        dut.io.prepare.bits.entries(0).uop.decoded.rob.residentGeneration.poke(0.U)
        dut.io.prepare.valid.poke(true.B)
        val bound = bindBrobPrepared(dut, 1, Seq(residentGeneration))
        dut.io.prepare.ready.expect(true.B)
        val bid = dut.io.prepared.entries(0).bid.peek().litValue.toInt
        val gen = dut.io.prepared.entries(0).brobGeneration.peek().litValue.toInt
        dut.io.publishFire.poke(true.B)
        dut.clock.step()
        dut.io.prepare.valid.poke(false.B)
        dut.io.publishFire.poke(false.B)
        (bound(0), bid, gen)
      }
      def releaseOne(raw: RobIdentity, bid: Int, gen: Int): Unit = {
        dut.io.release.valid.poke(true.B)
        dut.io.release.bits.count.poke(1.U)
        pokeBoundRob(dut.io.release.bits.entries(0), raw, bid, gen,
          residentGeneration = 1)
        dut.io.releaseReady.expect(true.B)
        dut.io.releaseApply.poke(true.B)
        dut.clock.step()
        dut.io.release.valid.poke(false.B)
        dut.io.releaseApply.poke(false.B)
      }
      def publishRawClosed(baseId: Int): (RobIdentity, RobIdentity, Int, Int) = {
        dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
        lane(dut.io.prepare.bits, 0, id = baseId, rid = 3, groupCount = 1,
          stid = 0, member = 0, blockStart = true, blockStop = false)
        lane(dut.io.prepare.bits, 1, id = baseId + 1, rid = 3, groupCount = 1,
          stid = 0, member = 1, blockStart = false, blockStop = true)
        for (laneIndex <- 0 until 2) {
          dut.io.prepare.bits.entries(laneIndex).uop.decoded.rob.bid.poke(0.U)
          dut.io.prepare.bits.entries(laneIndex).uop.decoded.rob.brobGeneration.poke(0.U)
          dut.io.prepare.bits.entries(laneIndex).uop.decoded.rob.residentGeneration.poke(0.U)
        }
        dut.io.prepare.valid.poke(true.B)
        val bound = bindBrobPrepared(dut, 2, Seq(7, 8))
        dut.io.prepare.ready.expect(true.B)
        dut.io.prepared.entries(0).bid.expect(3.U)
        dut.io.prepared.entries(1).bid.expect(3.U)
        val gen = dut.io.prepared.entries(0).brobGeneration.peek().litValue.toInt
        dut.io.publishFire.poke(true.B)
        dut.clock.step()
        dut.io.prepare.valid.poke(false.B)
        dut.io.publishFire.poke(false.B)
        (bound(0), bound(1), 3, gen)
      }

      clearBrob(dut)
      val prefill0 = publishRawSingle(130, rid = 0)
      releaseOne(prefill0._1, prefill0._2, prefill0._3)
      val prefill1 = publishRawSingle(131, rid = 1)
      releaseOne(prefill1._1, prefill1._2, prefill1._3)
      val prefill2 = publishRawSingle(132, rid = 2)
      releaseOne(prefill2._1, prefill2._2, prefill2._3)
      val (survivorRaw, killedRaw, straddlerBid, straddlerGen) =
        publishRawClosed(133)
      val (youngerRaw, youngerBid, youngerGen) =
        publishRawSingle(135, rid = 0, ridGeneration = 1,
          residentGeneration = 9)
      assert(youngerBid == 0)
      assert(youngerGen == 1)

      dut.io.recoveryPrepare.valid.poke(true.B)
      dut.io.recoveryPrepare.bits.transactionId.poke(0x169.U)
      dut.io.recoveryPrepare.bits.phase.poke(RecoveryPhase.Prepare)
      dut.io.recoveryPrepare.bits.cause.poke(RecoveryCause.Branch)
      pokeBoundRob(dut.io.recoveryPrepare.bits.trigger, survivorRaw,
        straddlerBid, straddlerGen, residentGeneration = 7)
      dut.io.recoveryPrepare.bits.survivingTailValid.poke(true.B)
      pokeBoundRob(dut.io.recoveryPrepare.bits.survivingTail, survivorRaw,
        straddlerBid, straddlerGen, residentGeneration = 7)
      dut.io.recoveryPrepare.bits.firstKilledValid.poke(true.B)
      pokeBoundRob(dut.io.recoveryPrepare.bits.firstKilled, killedRaw,
        straddlerBid, straddlerGen, residentGeneration = 8)
      pokeBoundRob(dut.io.recoveryPrepare.bits.lastKilled, youngerRaw,
        youngerBid, youngerGen, residentGeneration = 9)
      dut.io.recoveryPrepare.bits.killedMemberCount.poke(2.U)
      dut.io.recoveryPrepare.bits.killedGroupCount.poke(2.U)
      dut.io.recoveryPrepare.ready.expect(true.B)
      dut.clock.step()
      val prepared = dut.io.recoveryPrepared.bits.peek()
      dut.io.recoveryPrepare.valid.poke(false.B)
      dut.io.recoveryApply.valid.poke(true.B)
      dut.io.recoveryApply.bits.poke(prepared)
      dut.io.recoveryApply.bits.phase.poke(RecoveryPhase.Apply)
      dut.clock.step()
      dut.io.recoveryApply.valid.poke(false.B)

      dut.io.release.valid.poke(true.B)
      dut.io.release.bits.count.poke(1.U)
      pokeBoundRob(dut.io.release.bits.entries(0), survivorRaw, straddlerBid,
        straddlerGen, residentGeneration = 7)
      dut.io.releaseReady.expect(true.B)
    }
  }

  test("ROB and BROB coordinate bound publication for unbound D3 residency") {
    simulate(new RobBrobPublicationCoordinator(params)) { dut =>
      def publishUnbound(
          baseId: Int,
          rid: Int,
          ridGeneration: Int,
          count: Int,
          blockStopLast: Boolean): Seq[RobIdentity] = {
        dut.io.prepare.bits.poke(0.U.asTypeOf(dut.io.prepare.bits))
        for (laneIndex <- 0 until count) {
          lane(dut.io.prepare.bits, laneIndex, id = baseId + laneIndex, rid = rid,
            groupCount = 1, stid = 0, member = laneIndex,
            blockStart = laneIndex == 0, blockStop = blockStopLast && laneIndex == count - 1)
          dut.io.prepare.bits.entries(laneIndex).earlyRobComplete.poke(true.B)
          dut.io.prepare.bits.entries(laneIndex).brobBound.poke(false.B)
          dut.io.prepare.bits.entries(laneIndex).uop.decoded.rob.ridGeneration.poke(
            ridGeneration.U)
          dut.io.prepare.bits.entries(laneIndex).uop.decoded.rob.bid.poke(0.U)
          dut.io.prepare.bits.entries(laneIndex).uop.decoded.rob.brobGeneration.poke(0.U)
          dut.io.prepare.bits.entries(laneIndex).uop.decoded.rob.residentGeneration.poke(0.U)
        }
        dut.io.prepare.valid.poke(true.B)
        dut.io.prepare.ready.expect(true.B)
        val ids = (0 until count).map(laneIndex =>
          dut.io.robPrepared.entries(laneIndex).rob.peek())
        dut.clock.step()
        dut.io.prepare.valid.poke(false.B)
        ids
      }

      def publishRetireReleaseSingle(
          baseId: Int,
          rid: Int,
          expectedBid: Int,
          expectedBrobGeneration: Int,
          expectedResidentGeneration: Int): Unit = {
        val ids = publishUnbound(baseId, rid = rid, ridGeneration = 0,
          count = 1, blockStopLast = true)
        assert(ids.head.bid.litValue == expectedBid)
        assert(ids.head.brobGeneration.litValue == expectedBrobGeneration)
        assert(ids.head.residentGeneration.litValue == expectedResidentGeneration)
        retireCoordinatorHead(dut, 1)
        releaseCoordinator(dut, ids)
      }

      clearCoordinator(dut)
      publishRetireReleaseSingle(140, rid = 0, expectedBid = 0,
        expectedBrobGeneration = 0, expectedResidentGeneration = 0)
      publishRetireReleaseSingle(141, rid = 1, expectedBid = 1,
        expectedBrobGeneration = 0, expectedResidentGeneration = 0)
      publishRetireReleaseSingle(142, rid = 2, expectedBid = 2,
        expectedBrobGeneration = 0, expectedResidentGeneration = 0)

      val straddler = publishUnbound(143, rid = 3, ridGeneration = 0,
        count = 2, blockStopLast = true)
      assert(straddler(0).bid.litValue == 3)
      assert(straddler(1).bid.litValue == 3)
      assert(straddler(0).brobGeneration.litValue == 0)
      assert(straddler(1).brobGeneration.litValue == 0)
      assert(straddler(0).residentGeneration.litValue == 0)
      assert(straddler(1).residentGeneration.litValue == 0)
      retireCoordinatorHead(dut, 2)

      val younger = publishUnbound(145, rid = 0, ridGeneration = 1,
        count = 1, blockStopLast = true)
      assert(younger.head.bid.litValue == 0)
      assert(younger.head.brobGeneration.litValue == 1)
      assert(younger.head.residentGeneration.litValue == 1)
      retireCoordinatorHead(dut, 1)

      dut.io.robRecoveryPrepare.valid.poke(true.B)
      dut.io.robRecoveryPrepare.bits.transactionId.poke(0x16a.U)
      dut.io.robRecoveryPrepare.bits.phase.poke(RecoveryPhase.Prepare)
      dut.io.robRecoveryPrepare.bits.cause.poke(RecoveryCause.Branch)
      dut.io.robRecoveryPrepare.bits.trigger.poke(straddler(0))
      dut.io.robRecoveryPrepare.bits.redirectPc.poke(0x6000.U)
      dut.io.robRecoveryPrepare.bits.newEpoch.poke(4.U)
      dut.io.robRecoveryPrepare.ready.expect(true.B)
      dut.io.robRecoveryPrepared.valid.expect(true.B)
      dut.io.robRecoveryPrepared.bits.firstKilledValid.expect(true.B)
      assert(dut.io.robRecoveryPrepared.bits.firstKilled.peek().litValue ===
        straddler(1).litValue)
      assert(dut.io.robRecoveryPrepared.bits.lastKilled.peek().litValue ===
        younger.head.litValue)
      assert(dut.io.robRecoveryPrepared.bits.survivingTail.peek().litValue ===
        straddler(0).litValue)

      dut.io.brobRecoveryPrepare.valid.poke(true.B)
      dut.io.brobRecoveryPrepare.bits.poke(dut.io.robRecoveryPrepared.bits.peek())
      dut.io.brobRecoveryPrepare.ready.expect(true.B)
      dut.clock.step()
      val plan = dut.io.robRecoveryPrepared.bits.peek()
      dut.io.robRecoveryPrepare.valid.poke(false.B)
      dut.io.brobRecoveryPrepare.valid.poke(false.B)
      dut.io.robRecoveryApply.valid.poke(true.B)
      dut.io.robRecoveryApply.bits.poke(plan)
      dut.io.robRecoveryApply.bits.phase.poke(RecoveryPhase.Apply)
      dut.io.brobRecoveryApply.valid.poke(true.B)
      dut.io.brobRecoveryApply.bits.poke(plan)
      dut.io.brobRecoveryApply.bits.phase.poke(RecoveryPhase.Apply)
      dut.clock.step()
      dut.io.robRecoveryApply.valid.poke(false.B)
      dut.io.brobRecoveryApply.valid.poke(false.B)

      releaseCoordinator(dut, Seq(straddler(0)))
    }
  }

  test("BROB abort preserves a closed block that straddles recovery") {
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
      lane(dut.io.prepare.bits, 0, id = 80, rid = 0, groupCount = 1,
        stid = 0, member = 0, blockStart = true, blockStop = false)
      lane(dut.io.prepare.bits, 1, id = 81, rid = 0, groupCount = 1,
        stid = 0, member = 1, blockStart = false, blockStop = true)
      dut.io.prepare.valid.poke(true.B)
      val ids = bindBrobPrepared(dut, 2)
      dut.io.prepare.ready.expect(true.B)
      val survivor = ids(0)
      val killed = ids(1)
      dut.io.publishFire.poke(true.B)
      dut.clock.step()
      dut.io.prepare.valid.poke(false.B)
      dut.io.publishFire.poke(false.B)

      dut.io.recoveryPrepare.valid.poke(true.B)
      dut.io.recoveryPrepare.bits.transactionId.poke(0x64.U)
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
      val prepared = dut.io.recoveryPrepared.bits.peek()
      dut.io.recoveryPrepare.valid.poke(false.B)
      dut.io.recoveryAbort.valid.poke(true.B)
      dut.io.recoveryAbort.bits.poke(prepared)
      dut.io.recoveryAbort.bits.phase.poke(RecoveryPhase.Abort)
      dut.clock.step()
      dut.io.recoveryAbort.valid.poke(false.B)

      dut.io.release.valid.poke(true.B)
      dut.io.release.bits.count.poke(2.U)
      dut.io.release.bits.entries(0).poke(survivor)
      dut.io.release.bits.entries(1).poke(killed)
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

  test("RecoveryControl ignores target acknowledgements before prepare fires") {
    simulate(new RecoveryControl(params, targetCount = 1)) { dut =>
      clearRecoveryControl(dut)
      dut.io.events(0).valid.poke(true.B)
      dut.io.events(0).bits.transactionId.poke(0xb1.U)
      dut.io.events(0).bits.cause.poke(RecoveryCause.MemoryOrder)
      authorizeCandidate(dut, 0, 0xb1, stid = 0, rid = 0, age = 1)
      dut.io.targets(0).prepare.ready.poke(false.B)
      dut.clock.step()
      dut.io.robPrepared.valid.poke(true.B)
      dut.io.robPrepared.bits.poke(dut.io.robPrepare.bits.peek())
      dut.clock.step()
      dut.io.events(0).valid.poke(false.B)
      dut.io.robPrepared.valid.poke(false.B)
      dut.io.targets(0).prepare.valid.expect(true.B)
      dut.io.targets(0).prepared.valid.poke(true.B)
      dut.io.targets(0).prepared.bits.poke(dut.io.targets(0).prepare.bits.peek())
      dut.clock.step()
      dut.io.targets(0).apply.valid.expect(false.B)
      dut.io.targets(0).prepare.ready.poke(true.B)
      dut.io.targets(0).prepared.valid.poke(false.B)
      dut.clock.step()
      dut.io.targets(0).apply.valid.expect(false.B)
      dut.io.targets(0).prepared.valid.poke(true.B)
      dut.io.targets(0).prepared.bits.poke(dut.io.targets(0).prepare.bits.peek())
      dut.io.targets(0).prepared.bits.phase.poke(RecoveryPhase.Apply)
      dut.clock.step()
      dut.io.targets(0).apply.valid.expect(false.B)
      dut.io.targets(0).prepared.bits.phase.poke(RecoveryPhase.Prepare)
      dut.clock.step()
      dut.io.targets(0).apply.valid.expect(true.B)
    }
  }

  test("RecoveryControl does not carry stale target acknowledgements across abort retry") {
    simulate(new RecoveryControl(params, targetCount = 1)) { dut =>
      clearRecoveryControl(dut)
      dut.io.events(0).valid.poke(true.B)
      dut.io.events(0).bits.transactionId.poke(0xb2.U)
      dut.io.events(0).bits.cause.poke(RecoveryCause.MemoryOrder)
      authorizeCandidate(dut, 0, 0xb2, stid = 0, rid = 0, age = 1)
      dut.io.targets(0).prepare.ready.poke(false.B)
      dut.clock.step()
      dut.io.robPrepared.valid.poke(true.B)
      dut.io.robPrepared.bits.poke(dut.io.robPrepare.bits.peek())
      dut.clock.step()
      dut.io.events(0).valid.poke(false.B)
      dut.io.robPrepared.valid.poke(false.B)
      dut.io.targets(0).prepared.valid.poke(true.B)
      dut.io.targets(0).prepared.bits.poke(dut.io.targets(0).prepare.bits.peek())
      dut.clock.step()
      dut.io.abort.poke(true.B)
      dut.clock.step()
      dut.io.abort.poke(false.B)
      dut.io.targets(0).prepared.valid.poke(false.B)
      dut.io.targets(0).apply.valid.expect(false.B)
      dut.clock.step()
      dut.io.targets(0).apply.valid.expect(false.B)
    }

    simulate(new RecoveryControl(params, targetCount = 1)) { dut =>
      clearRecoveryControl(dut)
      dut.io.robPrepare.ready.poke(false.B)
      dut.io.events(0).valid.poke(true.B)
      dut.io.events(0).bits.transactionId.poke(0xb3.U)
      dut.io.events(0).bits.cause.poke(RecoveryCause.MemoryOrder)
      dut.io.events(0).bits.trigger.stid.poke(0.U)
      dut.io.events(0).bits.trigger.ridSlot.poke(1.U)
      authorizeCandidate(dut, 0, 0xb3, stid = 0, rid = 1, age = 1)
      dut.io.targets(0).prepare.ready.poke(true.B)
      dut.clock.step()
      dut.io.robPrepare.valid.expect(true.B)
      dut.io.robPrepared.valid.poke(true.B)
      dut.io.robPrepared.bits.poke(dut.io.robPrepare.bits.peek())
      dut.io.robPrepare.ready.poke(true.B)
      dut.clock.step()
      dut.io.events(0).valid.poke(false.B)
      dut.io.robPrepared.valid.poke(false.B)
      dut.io.targets(0).prepare.valid.expect(true.B)
      dut.io.targets(0).prepared.valid.poke(true.B)
      dut.io.targets(0).prepared.bits.poke(dut.io.targets(0).prepare.bits.peek())
      dut.io.targets(0).prepared.bits.transactionId.poke(0xb2.U)
      dut.clock.step()
      dut.io.targets(0).apply.valid.expect(false.B)
      dut.clock.step()
      dut.io.targets(0).prepared.valid.poke(true.B)
      dut.io.targets(0).prepared.bits.poke(dut.io.targets(0).prepare.bits.peek())
      dut.clock.step()
      dut.io.targets(0).apply.valid.expect(true.B)
    }
  }

  test("RecoveryControl drains held matching target acknowledgements before retry") {
    simulate(new RecoveryControl(params, targetCount = 1)) { dut =>
      def startAttempt(): Unit = {
        dut.io.events(0).valid.poke(true.B)
        dut.io.events(0).bits.transactionId.poke(0xb4.U)
        dut.io.events(0).bits.cause.poke(RecoveryCause.MemoryOrder)
        dut.io.events(0).bits.trigger.stid.poke(0.U)
        dut.io.events(0).bits.trigger.ridSlot.poke(0.U)
        authorizeCandidate(dut, 0, 0xb4, stid = 0, rid = 0, age = 1)
        dut.clock.step()
        dut.io.robPrepare.valid.expect(true.B)
      }

      def preparedPlanFromRequest(): RecoveryPlan = {
        dut.io.robPrepared.valid.poke(true.B)
        dut.io.robPrepared.bits.poke(dut.io.robPrepare.bits.peek())
        dut.io.robPrepared.bits.phase.poke(RecoveryPhase.Prepare)
        dut.io.robPrepared.bits.firstKilledValid.poke(true.B)
        dut.io.robPrepared.bits.firstKilled.poke(dut.io.robPrepare.bits.trigger.peek())
        dut.io.robPrepared.bits.firstKilled.ridSlot.poke(0.U)
        dut.io.robPrepared.bits.lastKilled.poke(dut.io.robPrepare.bits.trigger.peek())
        dut.io.robPrepared.bits.lastKilled.ridSlot.poke(0.U)
        dut.io.robPrepared.bits.killedGroupCount.poke(1.U)
        dut.io.robPrepared.bits.killedMemberCount.poke(1.U)
        dut.io.robPrepared.bits.peek()
      }

      def holdMatchingTargetPrepared(plan: RecoveryPlan): Unit = {
        dut.io.targets(0).prepared.valid.poke(true.B)
        dut.io.targets(0).prepared.bits.poke(plan)
        dut.io.targets(0).prepared.bits.phase.poke(RecoveryPhase.Prepare)
      }

      def acceptRobPlanWhileHoldingTarget(plan: RecoveryPlan): Unit = {
        holdMatchingTargetPrepared(plan)
        dut.io.targets(0).prepared.ready.expect(true.B)
        dut.clock.step()
        dut.io.targets(0).prepared.valid.poke(false.B)
      }

      clearRecoveryControl(dut)
      startAttempt()
      val firstPlan = preparedPlanFromRequest()
      acceptRobPlanWhileHoldingTarget(firstPlan)
      dut.io.events(0).valid.poke(false.B)
      dut.io.robPrepared.valid.poke(false.B)
      dut.io.targets(0).prepare.valid.expect(true.B)
      dut.clock.step()
      dut.io.targets(0).apply.valid.expect(false.B)
      dut.io.abort.poke(true.B)
      dut.clock.step()
      dut.io.abort.poke(false.B)
      dut.io.targets(0).prepared.valid.poke(false.B)
      dut.io.targets(0).apply.valid.expect(false.B)
      dut.clock.step()

      startAttempt()
      val retryPlan = preparedPlanFromRequest()
      acceptRobPlanWhileHoldingTarget(retryPlan)
      dut.io.events(0).valid.poke(false.B)
      dut.io.robPrepared.valid.poke(false.B)
      dut.clock.step()
      dut.io.targets(0).apply.valid.expect(false.B)

      dut.io.targets(0).prepared.valid.poke(false.B)
      dut.clock.step()
      dut.io.targets(0).apply.valid.expect(false.B)
      holdMatchingTargetPrepared(retryPlan)
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
      val ids = bindBrobPrepared(dut, 1)
      dut.io.prepare.ready.expect(true.B)
      val id = ids(0)
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
      val ids = bindBrobPrepared(dut, 1)
      dut.io.prepare.ready.expect(true.B)
      val id = ids(0)
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
