package linxcore.lsu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.params.{MemoryAccessAttributes, PhysicalMemoryRegion,
  SimulationParamProfiles}
import org.scalatest.funsuite.AnyFunSuite

class DSideTranslationReviewFixSpec extends AnyFunSuite with ChiselSim {
  test("Decoupled lookup retains the complete load across a PTW miss") {
    val p = SimulationParamProfiles.W4
    simulate(new DSideTranslation(p, entries = 2, pageBytes = 4096)) { dut =>
      dut.io.lookup.valid.poke(false.B)
      dut.io.lookup.bits.poke(0.U.asTypeOf(dut.io.lookup.bits))
      dut.io.result.ready.poke(false.B)
      dut.io.invalidate.poke(false.B)
      dut.io.memoryRequest.ready.poke(true.B)
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.memoryResponse.bits.poke(0.U.asTypeOf(dut.io.memoryResponse.bits))
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)

      dut.io.lookup.bits.load.address.poke(BigInt("8000000000001238", 16).U)
      dut.io.lookup.bits.load.sizeBytes.poke(8.U)
      dut.io.lookup.bits.load.identity.transaction.value.poke(37.U)
      dut.io.lookup.bits.load.identity.transaction.generation.poke(9.U)
      dut.io.lookup.bits.load.destination.ptag.poke(11.U)
      dut.io.lookup.valid.poke(true.B)
      dut.io.lookup.ready.expect(true.B)
      dut.clock.step()
      dut.io.lookup.valid.poke(false.B)

      while (!dut.io.memoryRequest.valid.peek().litToBoolean) dut.clock.step()
      val identity = dut.io.memoryRequest.bits.identity.peek()
      dut.clock.step()
      dut.io.memoryResponse.bits.identity.poke(identity)
      dut.io.memoryResponse.bits.data.poke(0x42.U)
      dut.io.memoryResponse.bits.attributesValid.poke(true.B)
      dut.io.memoryResponse.bits.readable.poke(true.B)
      dut.io.memoryResponse.bits.writable.poke(true.B)
      dut.io.memoryResponse.bits.cacheable.poke(true.B)
      dut.io.memoryResponse.valid.poke(true.B)
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(false.B)

      dut.io.result.valid.expect(true.B)
      dut.io.result.bits.request.load.identity.transaction.value.expect(37.U)
      dut.io.result.bits.request.load.identity.transaction.generation.expect(9.U)
      dut.io.result.bits.request.load.destination.ptag.expect(11.U)
      dut.io.result.bits.physicalAddress.expect(0x42238.U)
      dut.io.result.ready.poke(true.B)
      dut.clock.step()
      dut.io.quiescent.expect(true.B)
    }
  }

  test("Decoupled lookup retains the complete STA across a PTW miss") {
    val p = SimulationParamProfiles.W4
    simulate(new DSideTranslation(p, entries = 2, pageBytes = 4096)) { dut =>
      dut.io.lookup.valid.poke(false.B)
      dut.io.lookup.bits.poke(0.U.asTypeOf(dut.io.lookup.bits))
      dut.io.result.ready.poke(false.B)
      dut.io.invalidate.poke(false.B)
      dut.io.memoryRequest.ready.poke(true.B)
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.memoryResponse.bits.poke(0.U.asTypeOf(dut.io.memoryResponse.bits))
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)

      dut.io.lookup.bits.isStore.poke(true.B)
      dut.io.lookup.bits.store.address.poke(BigInt("8000000000003450", 16).U)
      dut.io.lookup.bits.store.sizeBytes.poke(8.U)
      dut.io.lookup.bits.store.identity.transaction.value.poke(41.U)
      dut.io.lookup.bits.store.identity.transaction.generation.poke(12.U)
      dut.io.lookup.bits.store.identity.lsid.poke(17.U)
      dut.io.lookup.bits.store.memoryOrder.firstSid.poke(23.U)
      dut.io.lookup.bits.store.requestCount.poke(1.U)
      dut.io.lookup.bits.store.pair.poke(true.B)
      dut.io.lookup.valid.poke(true.B)
      dut.io.lookup.ready.expect(true.B)
      dut.clock.step()
      dut.io.lookup.valid.poke(false.B)

      while (!dut.io.memoryRequest.valid.peek().litToBoolean) dut.clock.step()
      val identity = dut.io.memoryRequest.bits.identity.peek()
      dut.clock.step()
      dut.io.memoryResponse.bits.identity.poke(identity)
      dut.io.memoryResponse.bits.data.poke(0x52.U)
      dut.io.memoryResponse.bits.attributesValid.poke(true.B)
      dut.io.memoryResponse.bits.readable.poke(true.B)
      dut.io.memoryResponse.bits.writable.poke(true.B)
      dut.io.memoryResponse.bits.cacheable.poke(true.B)
      dut.io.memoryResponse.valid.poke(true.B)
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(false.B)

      dut.io.result.valid.expect(true.B)
      dut.io.result.bits.request.store.identity.transaction.value.expect(41.U)
      dut.io.result.bits.request.store.identity.transaction.generation.expect(12.U)
      dut.io.result.bits.request.store.identity.lsid.expect(17.U)
      dut.io.result.bits.request.store.memoryOrder.firstSid.expect(23.U)
      dut.io.result.bits.request.store.requestCount.expect(1.U)
      dut.io.result.bits.request.store.pair.expect(true.B)
      dut.io.result.bits.physicalAddress.expect(0x52450.U)
      dut.io.result.ready.poke(true.B)
      dut.clock.step()
      dut.io.quiescent.expect(true.B)
    }
  }

  test("one canonical physical attribute source governs identity and translated mappings") {
    val base = SimulationParamProfiles.W4
    val p = base.copy(lsu = base.lsu.copy(physicalMemoryRegions = Seq(
      PhysicalMemoryRegion(0x1000, BigInt("fffffffffffff000", 16),
        MemoryAccessAttributes(writable = false)))))
    simulate(new DSideTranslation(p, entries = 2, pageBytes = 4096)) { dut =>
      dut.io.lookup.valid.poke(false.B)
      dut.io.lookup.bits.poke(0.U.asTypeOf(dut.io.lookup.bits))
      dut.io.result.ready.poke(false.B)
      dut.io.invalidate.poke(false.B)
      dut.io.memoryRequest.ready.poke(true.B)
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.memoryResponse.bits.poke(0.U.asTypeOf(dut.io.memoryResponse.bits))
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)

      dut.io.lookup.bits.isStore.poke(true.B)
      dut.io.lookup.bits.store.address.poke(0x1000.U)
      dut.io.lookup.bits.store.sizeBytes.poke(8.U)
      dut.io.lookup.valid.poke(true.B)
      dut.clock.step()
      dut.io.lookup.valid.poke(false.B)
      dut.io.result.bits.accessFault.expect(true.B)
      dut.io.result.ready.poke(true.B)
      dut.clock.step()

      dut.io.result.ready.poke(false.B)
      dut.io.lookup.bits.store.address.poke(BigInt("8000000000002000", 16).U)
      dut.io.lookup.valid.poke(true.B)
      dut.clock.step()
      dut.io.lookup.valid.poke(false.B)
      while (!dut.io.memoryRequest.valid.peek().litToBoolean) dut.clock.step()
      val identity = dut.io.memoryRequest.bits.identity.peek()
      dut.clock.step()
      dut.io.memoryResponse.bits.identity.poke(identity)
      dut.io.memoryResponse.bits.data.poke(1.U)
      dut.io.memoryResponse.bits.attributesValid.poke(true.B)
      dut.io.memoryResponse.bits.readable.poke(true.B)
      dut.io.memoryResponse.bits.writable.poke(true.B)
      dut.io.memoryResponse.bits.cacheable.poke(true.B)
      dut.io.memoryResponse.valid.poke(true.B)
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.result.valid.expect(true.B)
      dut.io.result.bits.accessFault.expect(true.B)
    }
  }
}
