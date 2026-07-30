package linxcore.lsu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

class LoadForwardResultRetainerSpec extends AnyFunSuite with ChiselSim {
  private def pokeResult(
      result: LoadInflightForwardResult,
      slot: Int,
      generation: Int): Unit = {
    result.poke(0.U.asTypeOf(result))
    result.identity.loadId.valid.poke(true.B)
    result.identity.loadId.slot.poke(slot.U)
    result.identity.loadId.generation.poke(generation.U)
  }

  test("retains a retryable head and drains it only on accept or permanent stale") {
    simulate(new LoadForwardResultRetainer(
      idEntries = 8,
      storeEntries = 4,
      depth = 3,
      lsidWidth = 40)) { dut =>
      dut.io.flush.poke(false.B)
      dut.io.in.valid.poke(false.B)
      dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
      dut.io.sinkAccepted.poke(false.B)
      dut.io.sinkRejectedPermanent.poke(false.B)
      dut.io.sinkRetryRequired.poke(false.B)
      dut.clock.step()

      pokeResult(dut.io.in.bits, slot = 1, generation = 2)
      dut.io.in.valid.poke(true.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)

      dut.io.outValid.expect(true.B)
      dut.io.out.identity.loadId.slot.expect(1.U)
      dut.io.sinkRetryRequired.poke(true.B)
      dut.clock.step(2)
      dut.io.outValid.expect(true.B)
      dut.io.out.identity.loadId.slot.expect(1.U)
      dut.io.count.expect(1.U)

      dut.io.sinkRetryRequired.poke(false.B)
      dut.io.sinkAccepted.poke(true.B)
      dut.clock.step()
      dut.io.sinkAccepted.poke(false.B)
      dut.io.outValid.expect(false.B)
      dut.io.count.expect(0.U)

      pokeResult(dut.io.in.bits, slot = 2, generation = 3)
      dut.io.in.valid.poke(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.io.outValid.expect(true.B)
      dut.io.sinkRejectedPermanent.poke(true.B)
      dut.clock.step()
      dut.io.sinkRejectedPermanent.poke(false.B)
      dut.io.outValid.expect(false.B)
      dut.io.count.expect(0.U)

      pokeResult(dut.io.in.bits, slot = 3, generation = 4)
      dut.io.in.valid.poke(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.io.outValid.expect(true.B)
      dut.io.sinkRetryRequired.poke(true.B)
      dut.io.flush.poke(true.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)
      dut.io.sinkRetryRequired.poke(false.B)
      dut.io.outValid.expect(false.B)
      dut.io.count.expect(0.U)
      dut.io.protocolError.expect(false.B)
    }
  }
}
