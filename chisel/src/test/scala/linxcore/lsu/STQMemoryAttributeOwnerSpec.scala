package linxcore.lsu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

class STQMemoryAttributeOwnerSpec extends AnyFunSuite with ChiselSim {
  private def clear(dut: STQMemoryAttributeOwner): Unit = {
    dut.io.classify.valid.poke(false.B)
    dut.io.classify.bits.poke(0.U.asTypeOf(dut.io.classify.bits))
    dut.io.rows.poke(0.U.asTypeOf(dut.io.rows))
    dut.io.recoveryActive.poke(false.B)
  }

  private def pokeOwner(owner: STQExactOwner, stid: Int = 1): Unit = {
    owner.valid.poke(true.B)
    owner.peId.poke(2.U)
    owner.stid.poke(stid.U)
    owner.nativeBidValid.poke(true.B)
    owner.nativeBid.poke(7.U)
    owner.brobGeneration.poke(3.U)
    owner.ridSlot.poke(5.U)
    owner.ridGeneration.poke(4.U)
    owner.memberIndex.poke(1.U)
    owner.residentGeneration.poke(9.U)
  }

  private def pokeRow(
      dut: STQMemoryAttributeOwner,
      index: Int,
      generation: Int = 6,
      stid: Int = 1): Unit = {
    val row = dut.io.rows(index)
    row.valid.poke(true.B)
    row.status.poke(STQEntryStatus.Wait)
    row.addrReady.poke(true.B)
    row.leaseGeneration.poke(generation.U)
    pokeOwner(row.exactOwner, stid)
  }

  private def pokeToken(
      dut: STQMemoryAttributeOwner,
      index: Int,
      generation: Int = 6,
      memoryClass: STQMemoryClass.Type = STQMemoryClass.DeviceMmio,
      stid: Int = 1): Unit = {
    val token = dut.io.classify.bits
    token.poke(0.U.asTypeOf(token))
    token.lease.valid.poke(true.B)
    token.lease.index.poke(index.U)
    token.lease.generation.poke(generation.U)
    pokeOwner(token.exactOwner, stid)
    token.logicalBeat.poke(0.U)
    token.memoryClass.poke(memoryClass)
    dut.io.classify.valid.poke(true.B)
  }

  test("retains an exact PMA result and invalidates it on slot reuse") {
    simulate(new STQMemoryAttributeOwner(
      stqEntries = 4, robEntries = 8, stidWidth = 2)) { dut =>
      clear(dut)
      pokeRow(dut, index = 2)
      pokeToken(dut, index = 2)
      dut.io.classify.ready.expect(true.B)
      dut.io.accepted.expect(true.B)
      dut.clock.step()
      dut.io.classify.valid.poke(false.B)

      dut.io.attributes(2).valid.expect(true.B)
      dut.io.attributes(2).memoryClass.expect(STQMemoryClass.DeviceMmio)

      dut.io.rows(2).leaseGeneration.poke(7.U)
      dut.io.attributes(2).valid.expect(false.B)
      dut.io.attributes(2).memoryClass.expect(STQMemoryClass.Unknown)
    }
  }

  test("rejects unknown stale missing and duplicate-owner classifications") {
    simulate(new STQMemoryAttributeOwner(
      stqEntries = 4, robEntries = 8, stidWidth = 2)) { dut =>
      clear(dut)
      pokeRow(dut, index = 0)
      pokeToken(dut, index = 0, memoryClass = STQMemoryClass.Unknown)
      dut.io.malformed.expect(true.B)
      dut.io.classify.ready.expect(false.B)

      pokeToken(dut, index = 1)
      dut.io.classify.ready.expect(false.B)

      pokeToken(dut, index = 0, generation = 7)
      dut.io.classify.ready.expect(false.B)

      pokeRow(dut, index = 1)
      pokeToken(dut, index = 0)
      dut.io.multiple.expect(true.B)
      dut.io.classify.ready.expect(false.B)
    }
  }

  test("classification is single-assignment and recovery fences new state") {
    simulate(new STQMemoryAttributeOwner(
      stqEntries = 4, robEntries = 8, stidWidth = 2)) { dut =>
      clear(dut)
      pokeRow(dut, index = 3)
      pokeToken(dut, index = 3)
      dut.clock.step()

      dut.io.duplicate.expect(true.B)
      dut.io.classify.ready.expect(false.B)
      dut.io.classify.bits.memoryClass.poke(
        STQMemoryClass.NormalNonCacheable)
      dut.io.duplicate.expect(false.B)
      dut.io.conflict.expect(true.B)

      dut.io.classify.valid.poke(false.B)
      dut.io.rows(3).leaseGeneration.poke(7.U)
      pokeToken(dut, index = 3, generation = 7,
        memoryClass = STQMemoryClass.NormalCacheable)
      dut.io.recoveryActive.poke(true.B)
      dut.io.classify.ready.expect(false.B)
      dut.io.recoveryActive.poke(false.B)
      dut.io.classify.ready.expect(true.B)
    }
  }
}
