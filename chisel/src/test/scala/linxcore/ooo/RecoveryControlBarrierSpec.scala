package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.params.{CoreParams, ParamProfiles}
import linxcore.top.interface._
import org.scalatest.funsuite.AnyFunSuite

class RecoveryControlBarrierSpec extends AnyFunSuite with ChiselSim {
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

  private def clear(dut: RecoveryControl): Unit = {
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

  test("prepare fans out once and common apply waits for every acknowledgement") {
    simulate(new RecoveryControl(p, targetCount = 3)) { dut =>
      clear(dut)
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
}
