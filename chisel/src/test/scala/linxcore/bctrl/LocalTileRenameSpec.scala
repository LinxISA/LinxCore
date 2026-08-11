package linxcore.bctrl

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

class LocalTileRenameSpec extends AnyFunSuite with ChiselSim {
  private def clear(dut: LocalTileRename): Unit = {
    dut.io.allocate.valid.poke(false.B)
    dut.io.allocate.bits.poke(0.U.asTypeOf(dut.io.allocate.bits))
    dut.io.lookupLogical.poke(0.U)
    dut.io.release.foreach(_.poke(0.U.asTypeOf(dut.io.release.head)))
    dut.io.markReady.foreach(_.poke(0.U.asTypeOf(dut.io.markReady.head)))
  }

  private def allocate(
      dut: LocalTileRename,
      peMask: Int,
      hand: Int,
      sizeCode: Int): Unit = {
    dut.io.allocate.bits.peMask.poke(peMask.U)
    dut.io.allocate.bits.hand.poke(hand.U)
    dut.io.allocate.bits.sizeCode.poke(sizeCode.U)
    dut.io.allocate.valid.poke(true.B)
  }

  test("selected PEs allocate independent T U M N ring entries") {
    simulate(new LocalTileRename) { dut =>
      clear(dut)
      allocate(dut, peMask = 0x8, hand = 0, sizeCode = 1)
      dut.io.allocate.ready.expect(true.B)
      dut.io.allocation.valid.expect(true.B)
      dut.io.allocation.bits.logicalTags(0).expect(0.U)
      dut.io.allocation.bits.peMask.expect(0x8.U)
      dut.clock.step()

      allocate(dut, peMask = 0x4, hand = 0, sizeCode = 2)
      dut.io.allocation.bits.logicalTags(1).expect(0.U)
      dut.clock.step()

      allocate(dut, peMask = 0x8, hand = 0, sizeCode = 3)
      dut.io.allocation.bits.logicalTags(0).expect(1.U)
      dut.clock.step()

      allocate(dut, peMask = 0x8, hand = 1, sizeCode = 1)
      dut.io.allocation.bits.logicalTags(0).expect(16.U)
      dut.clock.step()
      allocate(dut, peMask = 0x8, hand = 2, sizeCode = 1)
      dut.io.allocation.bits.logicalTags(0).expect(32.U)
      dut.clock.step()
      allocate(dut, peMask = 0x8, hand = 3, sizeCode = 1)
      dut.io.allocation.bits.logicalTags(0).expect(48.U)
    }
  }

  test("producer lookup is per PE and readiness requires the exact renamed producer") {
    simulate(new LocalTileRename) { dut =>
      clear(dut)
      allocate(dut, peMask = 0xa, hand = 2, sizeCode = 2)
      val pe0Producer = dut.io.allocation.bits.producerTags(0).peek().litValue
      val pe2Producer = dut.io.allocation.bits.producerTags(2).peek().litValue
      assert(pe0Producer != pe2Producer)
      dut.clock.step()

      dut.io.allocate.valid.poke(false.B)
      dut.io.lookupLogical.poke(32.U)
      dut.io.lookup(0).valid.expect(true.B)
      dut.io.lookup(1).valid.expect(false.B)
      dut.io.lookup(2).valid.expect(true.B)
      dut.io.lookup(0).ready.expect(false.B)

      dut.io.markReady(0).valid.poke(true.B)
      dut.io.markReady(0).bits.poke(pe2Producer.U)
      dut.clock.step()
      dut.io.lookup(0).ready.expect(false.B)

      dut.io.markReady(0).bits.poke(pe0Producer.U)
      dut.clock.step()
      dut.io.markReady(0).valid.poke(false.B)
      dut.io.lookup(0).ready.expect(true.B)
      dut.io.lookup(2).ready.expect(false.B)
    }
  }

  test("capacity charge is per selected PE and zero mask is a strict no-op") {
    simulate(new LocalTileRename) { dut =>
      clear(dut)
      allocate(dut, peMask = 0x7, hand = 3, sizeCode = 7)
      dut.io.status.expect(LocalTileAllocateStatus.Applied)
      dut.io.allocation.bits.perPeCapacity.expect(8192.U)
      dut.io.allocation.bits.allocatedBytes.expect(24576.U)
      dut.clock.step()

      allocate(dut, peMask = 0, hand = 3, sizeCode = 0)
      dut.io.status.expect(LocalTileAllocateStatus.Noop)
      dut.io.allocate.ready.expect(true.B)
      dut.io.allocation.valid.expect(false.B)
      dut.clock.step()

      allocate(dut, peMask = 0x1, hand = 3, sizeCode = 0)
      dut.io.status.expect(LocalTileAllocateStatus.InvalidSize)
      dut.io.allocation.valid.expect(false.B)
    }
  }

  test("a full hand blocks until the exact oldest physical producer is released") {
    simulate(new LocalTileRename) { dut =>
      clear(dut)
      var firstProducer = BigInt(0)
      for (entry <- 0 until 16) {
        allocate(dut, peMask = 0x8, hand = 0, sizeCode = 1)
        dut.io.allocate.ready.expect(true.B)
        if (entry == 0) {
          firstProducer = dut.io.allocation.bits.producerTags(0).peek().litValue
        }
        dut.clock.step()
      }

      allocate(dut, peMask = 0x8, hand = 0, sizeCode = 1)
      dut.io.allocate.ready.expect(false.B)
      dut.io.status.expect(LocalTileAllocateStatus.NoCredit)

      dut.io.release(0).valid.poke(true.B)
      dut.io.release(0).bits.poke(firstProducer.U)
      dut.io.allocate.ready.expect(true.B)
      dut.io.allocation.valid.expect(true.B)
      dut.clock.step()
      dut.io.release(0).valid.poke(false.B)
      dut.io.allocate.valid.poke(false.B)
      dut.io.lookupLogical.poke(0.U)
      dut.io.lookup(0).valid.expect(true.B)
      dut.io.lookup(0).producerTag.expect((firstProducer + (1 << 8)).U)
    }
  }
}
