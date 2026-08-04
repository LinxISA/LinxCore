package linxcore.top

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.ctu.CTU
import linxcore.ooo.{CommitControl, RecoveryControl}
import linxcore.params.{CoreParams, ParamProfiles}
import linxcore.top.interface._
import org.scalatest.funsuite.AnyFunSuite

class RecoveryIntegrationSpec extends AnyFunSuite with ChiselSim {
  private val p: CoreParams = {
    val base = ParamProfiles.W4
    base.copy(ooo = base.ooo.copy(
      stidCount = 2,
      stidIdentityEntries = 2,
      robGroupsPerStid = 4,
      robBankCount = 4,
      brobEntriesPerStid = 4,
      gprPhysRegs = 64,
      gprMapQDepthPerStid = 64))
  }

  private def clearCommit(dut: CommitControl): Unit = {
    dut.io.rob.valid.poke(false.B)
    dut.io.rob.bits.poke(0.U.asTypeOf(dut.io.rob.bits))
    dut.io.residentHeads.foreach(_.poke(0.U.asTypeOf(dut.io.residentHeads.head)))
    dut.io.recoveryFence.foreach(_.poke(false.B))
    dut.io.interrupts.foreach(_.poke(0.U.asTypeOf(dut.io.interrupts.head)))
    dut.io.interruptBoundaryValid.poke(false.B)
    dut.io.interruptBoundary.poke(0.U.asTypeOf(dut.io.interruptBoundary))
    dut.io.robReleaseReady.poke(true.B)
    dut.io.renameReleaseReady.poke(true.B)
    dut.io.brobReleaseReady.poke(true.B)
    dut.io.pcBufferCommitReady.poke(true.B)
    dut.io.out.ready.poke(false.B)
    dut.io.debugRequest.valid.poke(false.B)
    dut.io.debugRequest.bits.poke(0.U.asTypeOf(dut.io.debugRequest.bits))
    dut.io.debugResponse.ready.poke(false.B)
    dut.io.robNoflushReady.valid.poke(false.B)
    dut.io.robNoflushReady.bits.poke(0.U.asTypeOf(dut.io.robNoflushReady.bits))
    dut.io.robNoflush.ready.poke(false.B)
  }

  private def clearRecovery(dut: RecoveryControl): Unit = {
    dut.io.events.foreach { event =>
      event.valid.poke(false.B)
      event.bits.poke(0.U.asTypeOf(event.bits))
    }
    dut.io.interrupts.foreach(_.poke(0.U.asTypeOf(dut.io.interrupts.head)))
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
    dut.io.targets.foreach { target =>
      target.prepare.ready.poke(true.B)
      target.prepared.valid.poke(false.B)
      target.prepared.bits.poke(0.U.asTypeOf(target.prepared.bits))
    }
  }

  test("OOO selects only the highest-priority interrupt for the precise STID boundary") {
    simulate(new CommitControl(p)) { dut =>
      clearCommit(dut)
      dut.io.rob.valid.poke(true.B)
      dut.io.rob.bits.headValid.poke(true.B)
      dut.io.rob.bits.head.stid.poke(0.U)
      dut.io.interruptBoundaryValid.poke(true.B)
      dut.io.interruptBoundary.stid.poke(0.U)
      dut.io.interrupts(0).valid.poke(true.B)
      dut.io.interrupts(0).stid.poke(0.U)
      dut.io.interrupts(0).cause.poke(7.U)
      dut.io.interrupts(0).priority.poke(3.U)
      dut.io.interrupts(1).valid.poke(true.B)
      dut.io.interrupts(1).stid.poke(1.U)
      dut.io.interrupts(1).cause.poke(9.U)
      dut.io.interrupts(1).priority.poke(15.U)

      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.trap.valid.expect(true.B)
      dut.io.out.bits.trap.kind.expect(TrapKind.Interrupt)
      dut.io.out.bits.trap.cause.expect(7.U)
      dut.io.out.bits.trap.rob.stid.expect(0.U)
    }
  }

  test("a synchronous head fault has priority over an interrupt") {
    simulate(new CommitControl(p)) { dut =>
      clearCommit(dut)
      dut.io.rob.valid.poke(true.B)
      dut.io.rob.bits.count.poke(1.U)
      dut.io.rob.bits.entries(0).valid.poke(true.B)
      dut.io.rob.bits.entries(0).commit.trap.valid.poke(true.B)
      dut.io.rob.bits.entries(0).commit.trap.kind.poke(TrapKind.Exception)
      dut.io.rob.bits.entries(0).commit.trap.cause.poke(0x21.U)
      dut.io.interruptBoundaryValid.poke(true.B)
      dut.io.interrupts(0).valid.poke(true.B)
      dut.io.interrupts(0).stid.poke(0.U)
      dut.io.interrupts(0).cause.poke(0x12.U)
      dut.io.interrupts(0).priority.poke(15.U)

      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.trap.kind.expect(TrapKind.Exception)
      dut.io.out.bits.trap.cause.expect(0x21.U)
    }
  }

  test("OOO accepts halt only at a commit boundary and resume releases it") {
    simulate(new CommitControl(p)) { dut =>
      clearCommit(dut)
      dut.io.rob.bits.count.poke(1.U)
      dut.io.rob.bits.headValid.poke(true.B)
      dut.io.rob.bits.head.stid.poke(0.U)
      dut.io.interruptBoundary.stid.poke(0.U)
      dut.io.debugRequest.valid.poke(true.B)
      dut.io.debugRequest.bits.transactionId.poke(0x44.U)
      dut.io.debugRequest.bits.command.poke(DebugCommand.Halt)
      dut.io.debugRequest.bits.stid.poke(0.U)
      dut.io.debugRequest.ready.expect(true.B)
      dut.clock.step()
      dut.io.debugRequest.valid.poke(false.B)
      dut.io.rob.valid.poke(true.B)
      dut.io.interruptBoundaryValid.poke(true.B)

      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.trap.valid.expect(true.B)
      dut.io.out.bits.trap.kind.expect(TrapKind.Debug)
      dut.io.out.bits.trap.rob.stid.expect(0.U)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()
      dut.io.out.ready.poke(false.B)
      dut.io.rob.valid.poke(false.B)
      dut.io.interruptBoundaryValid.poke(false.B)
      dut.io.halted.expect(true.B)
      dut.io.debugResponse.valid.expect(true.B)
      dut.io.debugResponse.bits.transactionId.expect(0x44.U)
      dut.io.debugResponse.bits.accepted.expect(true.B)
      dut.io.debugResponse.ready.poke(true.B)
      dut.clock.step()
      dut.io.debugResponse.ready.poke(false.B)

      dut.io.debugRequest.valid.poke(true.B)
      dut.io.debugRequest.bits.transactionId.poke(0x45.U)
      dut.io.debugRequest.bits.command.poke(DebugCommand.Resume)
      dut.io.debugRequest.bits.stid.poke(0.U)
      dut.io.debugRequest.ready.expect(true.B)
      dut.clock.step()
      dut.io.debugRequest.valid.poke(false.B)
      dut.io.halted.expect(false.B)
      dut.io.debugResponse.valid.expect(true.B)
      dut.io.debugResponse.bits.transactionId.expect(0x45.U)
      dut.io.debugResponse.bits.accepted.expect(true.B)
    }
  }

  test("prepare fans out once and one missing acknowledgement stalls common apply") {
    simulate(new RecoveryControl(p, targetCount = 3)) { dut =>
      clearRecovery(dut)
      dut.io.events(0).valid.poke(true.B)
      dut.io.events(0).bits.transactionId.poke(0x80.U)
      dut.io.events(0).bits.cause.poke(RecoveryCause.MemoryOrder)
      dut.io.events(0).bits.trigger.stid.poke(0.U)
      dut.io.robCandidateStatus(0).valid.poke(true.B)
      dut.io.robCandidateStatus(0).bits.transactionId.poke(0x80.U)
      dut.io.robCandidateStatus(0).bits.trigger.stid.poke(0.U)
      dut.io.robCandidateStatus(0).bits.eligible.poke(true.B)
      dut.clock.step()
      dut.io.events(0).valid.poke(false.B)
      dut.io.robCandidateStatus(0).valid.poke(false.B)
      dut.io.robPrepared.valid.poke(true.B)
      dut.io.robPrepared.bits.poke(dut.io.robPrepare.bits.peek())
      dut.clock.step()
      dut.io.robPrepared.valid.poke(false.B)

      dut.io.targets.foreach(_.prepare.valid.expect(true.B))
      dut.clock.step()
      dut.io.targets.foreach(_.prepare.valid.expect(false.B))
      for (target <- 0 until 2) {
        dut.io.targets(target).prepared.valid.poke(true.B)
        dut.io.targets(target).prepared.bits.poke(
          dut.io.targets(target).prepare.bits.peek())
      }
      dut.clock.step()
      dut.io.targets.foreach(_.apply.valid.expect(false.B))

      dut.io.targets(0).prepared.valid.poke(false.B)
      dut.io.targets(1).prepared.valid.poke(false.B)
      dut.io.targets(2).prepared.valid.poke(true.B)
      dut.io.targets(2).prepared.bits.poke(
        dut.io.targets(2).prepare.bits.peek())
      dut.clock.step()
      dut.io.targets.foreach(_.apply.valid.expect(true.B))
    }
  }

  test("a target-STID prepare does not block unrelated CTU admission") {
    simulate(new CTU(p)) { dut =>
      dut.io.fromIfu.valid.poke(false.B)
      dut.io.fromIfu.bits.poke(0.U.asTypeOf(dut.io.fromIfu.bits))
      dut.io.toOoo.ready.poke(true.B)
      dut.io.trace.ready.poke(true.B)
      dut.io.recovery.prepare.valid.poke(true.B)
      dut.io.recovery.prepare.bits.poke(0.U.asTypeOf(dut.io.recovery.prepare.bits))
      dut.io.recovery.prepare.bits.transactionId.poke(0x91.U)
      dut.io.recovery.prepare.bits.phase.poke(RecoveryPhase.Prepare)
      dut.io.recovery.prepare.bits.cause.poke(RecoveryCause.Branch)
      dut.io.recovery.prepare.bits.trigger.stid.poke(0.U)
      dut.io.recovery.prepared.ready.poke(false.B)
      dut.io.recovery.apply.valid.poke(false.B)
      dut.io.recovery.apply.bits.poke(0.U.asTypeOf(dut.io.recovery.apply.bits))
      dut.io.recovery.abort.valid.poke(false.B)
      dut.io.recovery.abort.bits.poke(0.U.asTypeOf(dut.io.recovery.abort.bits))
      dut.clock.step()
      dut.io.recovery.prepare.valid.poke(false.B)

      dut.io.fromIfu.bits.count.poke(1.U)
      dut.io.fromIfu.bits.entries(0).identity.stid.poke(1.U)
      dut.io.fromIfu.bits.entries(0).identity.instructionId.poke(0x55.U)
      dut.io.fromIfu.bits.entries(0).identity.epoch.poke(1.U)
      dut.io.fromIfu.bits.entries(0).instruction.poke(5.U)
      dut.io.fromIfu.bits.entries(0).lengthBytes.poke(2.U)
      dut.io.fromIfu.valid.poke(true.B)
      dut.io.fromIfu.ready.expect(true.B)
      dut.clock.step()
      dut.io.fromIfu.valid.poke(false.B)
      var cycles = 0
      while (!dut.io.toOoo.valid.peek().litToBoolean && cycles < 8) {
        dut.clock.step()
        cycles += 1
      }
      assert(cycles < 8, "unrelated STID did not make forward progress")
      dut.io.toOoo.valid.expect(true.B)
      dut.io.toOoo.bits.entries(0).parent.identity.stid.expect(1.U)
      dut.io.toOoo.bits.entries(0).parent.identity.instructionId.expect(0x55.U)
    }
  }
}
