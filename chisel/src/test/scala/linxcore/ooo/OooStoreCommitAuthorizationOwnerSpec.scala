package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.params.SimulationParamProfiles
import org.scalatest.funsuite.AnyFunSuite

class OooStoreCommitAuthorizationOwnerSpec extends AnyFunSuite with ChiselSim {
  test("store batch is invisible before common commit fire then drains exactly once") {
    simulate(new OooStoreCommitAuthorizationOwner(
      SimulationParamProfiles.W2)) { dut =>
      dut.io.commit.bits.poke(0.U.asTypeOf(dut.io.commit.bits))
      dut.io.commit.valid.poke(true.B)
      dut.io.commit.bits.count.poke(2.U)
      val pair = dut.io.commit.bits.entries(0)
      pair.memoryValid.poke(true.B)
      pair.memoryStore.poke(true.B)
      pair.memory.transaction.value.poke(9.U)
      pair.memory.transaction.generation.poke(3.U)
      pair.memoryOrder.firstLsid.poke(21.U)
      pair.memoryOrder.firstSid.poke(31.U)
      pair.memoryOrder.requestCount.poke(2.U)
      val scalar = dut.io.commit.bits.entries(1)
      scalar.memoryValid.poke(true.B)
      scalar.memoryStore.poke(true.B)
      scalar.memory.transaction.value.poke(10.U)
      scalar.memory.transaction.generation.poke(4.U)
      scalar.memoryOrder.firstLsid.poke(23.U)
      scalar.memoryOrder.firstSid.poke(33.U)
      scalar.memoryOrder.requestCount.poke(1.U)
      dut.io.applied.poke(false.B)
      dut.io.storeCommit.ready.poke(false.B)

      dut.io.canAccept.expect(true.B)
      dut.io.drained.expect(true.B)
      dut.io.storeCommit.valid.expect(false.B,
        "prepared store tokens must not become visible before common commit fire")
      dut.clock.step(2)
      dut.io.storeCommit.valid.expect(false.B)

      dut.io.applied.poke(true.B)
      dut.clock.step()
      dut.io.applied.poke(false.B)
      dut.io.commit.valid.poke(false.B)
      dut.io.drained.expect(false.B)
      dut.io.storeCommit.valid.expect(true.B)
      dut.io.storeCommit.bits.beat.expect(0.U)
      dut.io.storeCommit.bits.transaction.value.expect(9.U)
      dut.io.storeCommit.bits.transaction.generation.expect(3.U)
      val held = dut.io.storeCommit.bits.peek()
      dut.clock.step(2)
      dut.io.storeCommit.bits.expect(held)

      dut.io.storeCommit.ready.poke(true.B)
      dut.clock.step()
      dut.io.storeCommit.valid.expect(true.B)
      dut.io.storeCommit.bits.beat.expect(1.U)
      dut.clock.step()
      dut.io.storeCommit.valid.expect(true.B)
      dut.io.storeCommit.bits.beat.expect(0.U)
      dut.io.storeCommit.bits.transaction.value.expect(10.U)
      dut.io.storeCommit.bits.transaction.generation.expect(4.U)
      dut.clock.step()
      dut.io.storeCommit.valid.expect(false.B)
      dut.io.drained.expect(true.B)
    }
  }
}
