package linxcore.lsu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

class STQRobCommitIngressSpec extends AnyFunSuite with ChiselSim {
  private def clear(dut: STQRobCommitIngress): Unit = {
    dut.io.commit.valid.poke(false.B)
    dut.io.commit.bits.poke(0.U.asTypeOf(dut.io.commit.bits))
    dut.io.rows.poke(0.U.asTypeOf(dut.io.rows))
    dut.io.memoryAttributes.poke(0.U.asTypeOf(dut.io.memoryAttributes))
    dut.io.recoveryActive.poke(false.B)
    dut.io.drainEnqueueReady.poke(false.B)
  }

  private def pokeToken(dut: STQRobCommitIngress, beat: Int = 0): Unit = {
    val token = dut.io.commit.bits
    token.poke(0.U.asTypeOf(token))
    token.logicalFirstLsid.poke(100.U)
    token.logicalFirstStoreId.poke(40.U)
    token.logicalRequestCount.poke(2.U)
    token.logicalBeat.poke(beat.U)
    token.exactOwner.valid.poke(true.B)
    token.exactOwner.peId.poke(3.U)
    token.exactOwner.stid.poke(1.U)
    token.exactOwner.nativeBidValid.poke(true.B)
    token.exactOwner.nativeBid.poke(7.U)
    token.exactOwner.brobGeneration.poke(2.U)
    token.exactOwner.ridSlot.poke(5.U)
    token.exactOwner.ridGeneration.poke(4.U)
    token.exactOwner.memberIndex.poke(1.U)
    token.exactOwner.residentGeneration.poke(9.U)
  }

  private def pokeMatchingRow(
      dut: STQRobCommitIngress,
      index: Int,
      beat: Int = 0,
      ready: Boolean = true): Unit = {
    val row = dut.io.rows(index)
    row.valid.poke(true.B)
    row.status.poke(STQEntryStatus.Wait)
    row.storeType.poke(STQStoreType.All)
    row.peId.poke(3.U)
    row.stid.poke(1.U)
    row.bid.valid.poke(true.B)
    row.addrReady.poke(ready.B)
    row.dataReady.poke(ready.B)
    row.lsIdFull.poke((100 + beat).U)
    row.storeIdFullValid.poke(true.B)
    row.storeIdFull.poke((40 + beat).U)
    row.logicalStoreValid.poke(true.B)
    row.logicalFirstLsid.poke(100.U)
    row.logicalFirstStoreId.poke(40.U)
    row.logicalRequestCount.poke(2.U)
    row.logicalBeat.poke(beat.U)
    row.exactOwner.valid.poke(true.B)
    row.exactOwner.peId.poke(3.U)
    row.exactOwner.stid.poke(1.U)
    row.exactOwner.nativeBidValid.poke(true.B)
    row.exactOwner.nativeBid.poke(7.U)
    row.exactOwner.brobGeneration.poke(2.U)
    row.exactOwner.ridSlot.poke(5.U)
    row.exactOwner.ridGeneration.poke(4.U)
    row.exactOwner.memberIndex.poke(1.U)
    row.exactOwner.residentGeneration.poke(9.U)
    dut.io.memoryAttributes(index).valid.poke(true.B)
    dut.io.memoryAttributes(index).memoryClass.poke(
      STQMemoryClass.NormalCacheable)
  }

  test("accepts only a unique converged exact STQ row with CommitQ credit") {
    simulate(new STQRobCommitIngress(entries = 4, robEntries = 8,
      lsidWidth = 40)) { dut =>
      clear(dut)
      pokeToken(dut, beat = 1)
      pokeMatchingRow(dut, index = 2, beat = 1)
      dut.io.commit.valid.poke(true.B)

      dut.io.commit.ready.expect(false.B)
      dut.io.markValid.expect(false.B)
      dut.io.notReady.expect(true.B)

      dut.io.drainEnqueueReady.poke(true.B)
      dut.io.commit.ready.expect(true.B)
      dut.io.markValid.expect(true.B)
      dut.io.markIndex.expect(2.U)
      dut.io.accepted.expect(true.B)
    }
  }

  test("distinguishes missing duplicate and half-filled exact rows") {
    simulate(new STQRobCommitIngress(entries = 4, robEntries = 8)) { dut =>
      clear(dut)
      pokeToken(dut)
      dut.io.commit.valid.poke(true.B)
      dut.io.drainEnqueueReady.poke(true.B)
      dut.io.missing.expect(true.B)
      dut.io.commit.ready.expect(false.B)

      pokeMatchingRow(dut, index = 0, ready = false)
      dut.io.missing.expect(false.B)
      dut.io.notReady.expect(true.B)
      dut.io.commit.ready.expect(false.B)

      pokeMatchingRow(dut, index = 1, ready = true)
      dut.io.multiple.expect(true.B)
      dut.io.notReady.expect(false.B)
      dut.io.commit.ready.expect(false.B)
    }
  }

  test("recovery fences an otherwise exact token without losing identity") {
    simulate(new STQRobCommitIngress(entries = 4, robEntries = 8)) { dut =>
      clear(dut)
      pokeToken(dut)
      pokeMatchingRow(dut, index = 3)
      dut.io.commit.valid.poke(true.B)
      dut.io.drainEnqueueReady.poke(true.B)
      dut.io.recoveryActive.poke(true.B)
      dut.io.notReady.expect(true.B)
      dut.io.commit.ready.expect(false.B)
      dut.io.markValid.expect(false.B)

      dut.io.recoveryActive.poke(false.B)
      dut.io.commit.ready.expect(true.B)
      dut.io.markIndex.expect(3.U)
    }
  }

  test("missing and faulting memory classifications fail closed") {
    simulate(new STQRobCommitIngress(entries = 4, robEntries = 8)) { dut =>
      clear(dut)
      pokeToken(dut)
      pokeMatchingRow(dut, index = 2)
      dut.io.commit.valid.poke(true.B)
      dut.io.drainEnqueueReady.poke(true.B)

      dut.io.memoryAttributes(2).valid.poke(false.B)
      dut.io.classificationMissing.expect(true.B)
      dut.io.commit.ready.expect(false.B)

      dut.io.memoryAttributes(2).valid.poke(true.B)
      dut.io.memoryAttributes(2).memoryClass.poke(STQMemoryClass.Fault)
      dut.io.classificationMissing.expect(false.B)
      dut.io.classificationFault.expect(true.B)
      dut.io.commit.ready.expect(false.B)

      dut.io.memoryAttributes(2).memoryClass.poke(
        STQMemoryClass.DeviceMmio)
      dut.io.classificationFault.expect(false.B)
      dut.io.commit.ready.expect(true.B)
    }
  }
}
