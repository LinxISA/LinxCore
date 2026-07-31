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
    dut.io.release.valid.poke(false.B)
    dut.io.release.bits.poke(0.U.asTypeOf(dut.io.release.bits))
    dut.io.releaseApply.poke(false.B)
    dut.io.brobPrepared.poke(0.U.asTypeOf(dut.io.brobPrepared))
    dut.io.recoveryPrepare.valid.poke(false.B)
    dut.io.recoveryPrepare.bits.poke(0.U.asTypeOf(dut.io.recoveryPrepare.bits))
    dut.io.recoveryApply.valid.poke(false.B)
    dut.io.recoveryApply.bits.poke(0.U.asTypeOf(dut.io.recoveryApply.bits))
  }

  private def lane(
      group: D3RenameGroup,
      lane: Int,
      id: Int,
      rid: Int,
      groupCount: Int = -1,
      stid: Int = 0): Unit = {
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
    row.uop.decoded.rob.memberIndex.poke(0.U)
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
      dut.io.events.foreach { event =>
        event.valid.poke(false.B)
        event.bits.poke(0.U.asTypeOf(event.bits))
      }
      dut.io.events(0).valid.poke(true.B)
      dut.io.events(0).bits.transactionId.poke(0x80.U)
      dut.io.events(0).bits.cause.poke(RecoveryCause.MemoryOrder)
      dut.io.events(0).bits.trigger.stid.poke(0.U)
      dut.io.events(0).bits.trigger.ridSlot.poke(1.U)
      dut.io.events(0).bits.redirectPc.poke(0x2000.U)
      dut.io.robPrepare.ready.poke(true.B)
      dut.io.robPrepared.valid.poke(false.B)
      dut.io.robPrepared.bits.poke(0.U.asTypeOf(dut.io.robPrepared.bits))
      (0 until 3).foreach { target =>
        dut.io.targets(target).prepare.ready.poke(true.B)
        dut.io.targets(target).prepared.valid.poke(false.B)
        dut.io.targets(target).prepared.bits.poke(0.U.asTypeOf(
          dut.io.targets(target).prepared.bits))
      }
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
      dut.io.events.foreach { event =>
        event.valid.poke(false.B)
        event.bits.poke(0.U.asTypeOf(event.bits))
      }
      dut.io.events(0).valid.poke(true.B)
      dut.io.events(0).bits.transactionId.poke(0x90.U)
      dut.io.events(0).bits.cause.poke(RecoveryCause.Debug)
      dut.io.events(0).bits.trigger.stid.poke(0.U)
      dut.io.robPrepare.ready.poke(true.B)
      dut.io.robPrepared.valid.poke(true.B)
      dut.io.robPrepared.bits.poke(0.U.asTypeOf(dut.io.robPrepared.bits))
      dut.io.robPrepared.bits.transactionId.poke(0x90.U)
      dut.io.robPrepared.bits.cause.poke(RecoveryCause.Debug)
      dut.io.robPrepared.bits.trigger.stid.poke(0.U)
      dut.clock.step()
      dut.io.abort.poke(true.B)
      dut.clock.step()
      dut.io.targets(0).abort.valid.expect(true.B)
      dut.io.targets(0).abort.bits.phase.expect(RecoveryPhase.Abort)
    }
  }

  test("RecoveryControl retains source one and selects the older retained event") {
    simulate(new RecoveryControl(params, targetCount = 3)) { dut =>
      dut.io.events.foreach { event =>
        event.valid.poke(false.B)
        event.bits.poke(0.U.asTypeOf(event.bits))
      }
      dut.io.robPrepare.ready.poke(true.B)
      dut.io.robPrepared.valid.poke(false.B)
      dut.io.events(1).valid.poke(true.B)
      dut.io.events(1).bits.transactionId.poke(0xa1.U)
      dut.io.events(1).bits.cause.poke(RecoveryCause.Branch)
      dut.io.events(1).bits.trigger.stid.poke(0.U)
      dut.io.events(1).bits.trigger.ridSlot.poke(1.U)
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
      dut.io.events.foreach { event =>
        event.valid.poke(false.B)
        event.bits.poke(0.U.asTypeOf(event.bits))
      }
      dut.io.events(0).valid.poke(true.B)
      dut.io.events(0).bits.transactionId.poke(0xb0.U)
      dut.io.events(0).bits.cause.poke(RecoveryCause.MemoryOrder)
      dut.io.robPrepare.ready.poke(true.B)
      dut.io.robPrepared.valid.poke(false.B)
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
      dut.io.events.foreach { event =>
        event.valid.poke(false.B)
        event.bits.poke(0.U.asTypeOf(event.bits))
      }
      dut.io.events(0).valid.poke(true.B)
      dut.io.events(0).bits.transactionId.poke(0xc0.U)
      dut.io.events(0).bits.cause.poke(RecoveryCause.Exception)
      dut.io.events(0).bits.trap.valid.poke(true.B)
      dut.io.events(0).bits.trigger.ridSlot.poke(0.U)
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
}
