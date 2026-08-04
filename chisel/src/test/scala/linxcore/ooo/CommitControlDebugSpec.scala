package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.params.{CoreParams, ParamProfiles}
import linxcore.top.interface._
import org.scalatest.funsuite.AnyFunSuite

class CommitControlDebugSpec extends AnyFunSuite with ChiselSim {
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

  private def clear(dut: CommitControl): Unit = {
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
    dut.io.debugRequest.valid.poke(false.B)
    dut.io.debugRequest.bits.poke(0.U.asTypeOf(dut.io.debugRequest.bits))
    dut.io.debugResponse.ready.poke(false.B)
    dut.io.out.ready.poke(true.B)
    dut.io.robNoflushReady.valid.poke(false.B)
    dut.io.robNoflushReady.bits.poke(0.U.asTypeOf(dut.io.robNoflushReady.bits))
    dut.io.robNoflush.ready.poke(false.B)
  }

  test("one boundary selector orders normal commit, interrupt, synchronous fault, halt, and resume") {
    simulate(new CommitControl(p)) { dut =>
      clear(dut)

      dut.io.rob.valid.poke(true.B)
      dut.io.rob.bits.count.poke(1.U)
      dut.io.rob.bits.entries(0).valid.poke(true.B)
      dut.io.rob.bits.entries(0).commit.rob.stid.poke(0.U)
      dut.io.rob.bits.entries(0).commit.rob.ridSlot.poke(1.U)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.trap.valid.expect(false.B)
      dut.io.out.bits.commit.count.expect(1.U)
      dut.clock.step()

      dut.io.rob.valid.poke(false.B)
      dut.clock.step()
      dut.io.rob.valid.poke(true.B)
      dut.io.rob.bits.poke(0.U.asTypeOf(dut.io.rob.bits))
      dut.io.interruptBoundaryValid.poke(true.B)
      dut.io.interruptBoundary.stid.poke(0.U)
      dut.io.interruptBoundary.ridSlot.poke(0.U)
      dut.io.interrupts(0).valid.poke(true.B)
      dut.io.interrupts(0).stid.poke(0.U)
      dut.io.interrupts(0).cause.poke(7.U)
      dut.io.interrupts(0).priority.poke(3.U)
      dut.io.interrupts(1).valid.poke(true.B)
      dut.io.interrupts(1).stid.poke(1.U)
      dut.io.interrupts(1).cause.poke(9.U)
      dut.io.interrupts(1).priority.poke(15.U)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.trap.kind.expect(TrapKind.Interrupt)
      dut.io.out.bits.trap.cause.expect(7.U)
      dut.clock.step()

      dut.io.rob.valid.poke(false.B)
      dut.io.interruptBoundaryValid.poke(false.B)
      dut.io.interrupts.foreach(_.poke(0.U.asTypeOf(dut.io.interrupts.head)))
      dut.clock.step()
      dut.io.debugRequest.valid.poke(true.B)
      dut.io.debugRequest.bits.transactionId.poke(0x44.U)
      dut.io.debugRequest.bits.command.poke(DebugCommand.Halt)
      dut.io.debugRequest.bits.stid.poke(0.U)
      dut.io.debugRequest.ready.expect(true.B)
      dut.clock.step()

      dut.io.debugRequest.valid.poke(false.B)
      dut.io.rob.valid.poke(true.B)
      dut.io.rob.bits.poke(0.U.asTypeOf(dut.io.rob.bits))
      dut.io.rob.bits.count.poke(1.U)
      dut.io.rob.bits.entries(0).valid.poke(true.B)
      dut.io.rob.bits.entries(0).commit.trap.valid.poke(true.B)
      dut.io.rob.bits.entries(0).commit.trap.kind.poke(TrapKind.Exception)
      dut.io.rob.bits.entries(0).commit.trap.cause.poke(0x21.U)
      dut.io.interruptBoundaryValid.poke(true.B)
      dut.io.interruptBoundary.stid.poke(0.U)
      dut.io.interruptBoundary.ridSlot.poke(1.U)
      dut.io.interrupts(0).valid.poke(true.B)
      dut.io.interrupts(0).stid.poke(0.U)
      dut.io.interrupts(0).cause.poke(0x12.U)
      dut.io.interrupts(0).priority.poke(15.U)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.trap.kind.expect(TrapKind.Exception)
      dut.io.out.bits.trap.cause.expect(0x21.U)
      dut.clock.step()

      dut.io.rob.valid.poke(false.B)
      dut.io.interruptBoundaryValid.poke(false.B)
      dut.io.interrupts.foreach(_.poke(0.U.asTypeOf(dut.io.interrupts.head)))
      dut.clock.step()
      dut.io.rob.valid.poke(true.B)
      dut.io.rob.bits.poke(0.U.asTypeOf(dut.io.rob.bits))
      dut.io.interruptBoundaryValid.poke(true.B)
      dut.io.interruptBoundary.stid.poke(0.U)
      dut.io.interruptBoundary.ridSlot.poke(0.U)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.trap.kind.expect(TrapKind.Debug)
      dut.io.out.bits.trap.rob.ridSlot.expect(0.U)
      dut.clock.step()

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
}
