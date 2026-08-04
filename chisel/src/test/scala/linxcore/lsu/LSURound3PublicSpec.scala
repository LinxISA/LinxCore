package linxcore.lsu

import chisel3._
import linxcore.params.SimulationParamProfiles
import org.scalatest.funsuite.AnyFunSuite

class LSURound3PublicSpec extends AnyFunSuite
    with CacheableStorePublicTestSupport {

  private def expectFailedOwnershipResponse(
      dut: LSU,
      denied: Boolean,
      corrupt: Boolean): Unit = {
    val p = dut.p
    val (value, generation) = publishCacheableStore(dut, 0x6808)
    val response = dut.io.memoryResponse(p.lsu.loadPipes + 1)
    response.bits.poke(0.U.asTypeOf(response.bits))
    response.bits.identity.value.poke(value.U)
    response.bits.identity.generation.poke(generation.U)
    response.bits.denied.poke(denied.B)
    response.bits.corrupt.poke(corrupt.B)
    response.valid.poke(true.B)
    response.ready.expect(true.B)
    dut.clock.step()
    response.valid.poke(false.B)

    dut.io.protocolError.expect(true.B)
    dut.io.quiescent.expect(false.B)
    pokeLoad(dut, 0x6808, transaction = 71)
    dut.io.iex.loadAddress(0).ready.expect(false.B)
  }

  test("denied AcquireWrite fails closed without completing the public SCB path") {
    val p = SimulationParamProfiles.W4
    simulate(new LSU(p)) { dut =>
      initialize(dut)
      expectFailedOwnershipResponse(dut, denied = true, corrupt = false)
    }
  }

  test("corrupt AcquireWrite fails closed without completing the public SCB path") {
    val p = SimulationParamProfiles.W4
    simulate(new LSU(p)) { dut =>
      initialize(dut)
      expectFailedOwnershipResponse(dut, denied = false, corrupt = true)
    }
  }

  test("AcquireWrite preserves untouched cacheline bytes around the exact store") {
    val p = SimulationParamProfiles.W4
    val originalLine = (0 until 64).foldLeft(BigInt(0)) { (line, byte) =>
      line | (BigInt(byte) << (byte * 8))
    }
    simulate(new LSU(p)) { dut =>
      initialize(dut)
      val (value, generation) = publishCacheableStore(dut, 0x6808)
      val response = dut.io.memoryResponse(p.lsu.loadPipes + 1)
      response.bits.poke(0.U.asTypeOf(response.bits))
      response.bits.identity.value.poke(value.U)
      response.bits.identity.generation.poke(generation.U)
      response.bits.lineData.poke(originalLine.U)
      response.valid.poke(true.B)
      response.ready.expect(true.B)
      dut.clock.step()
      response.valid.poke(false.B)

      var drainCycles = 0
      while (!dut.io.quiescent.peek().litToBoolean && drainCycles < 64) {
        dut.clock.step()
        drainCycles += 1
      }
      assert(drainCycles < 64, "successful ownership response did not drain")

      def expectLoad(address: BigInt, transaction: Int, expected: BigInt): Unit = {
        pokeLoad(dut, address, transaction)
        dut.io.iex.loadAddress(0).ready.expect(true.B)
        dut.clock.step()
        dut.io.iex.loadAddress(0).valid.poke(false.B)
        var cycles = 0
        while (!dut.io.iex.loadResult(0).valid.peek().litToBoolean && cycles < 32) {
          dut.clock.step()
          cycles += 1
        }
        assert(cycles < 32, f"load at 0x$address%x did not return")
        dut.io.iex.loadResult(0).bits.data.expect(expected.U)
        dut.clock.step()
      }

      expectLoad(0x6800, 72, BigInt("0706050403020100", 16))
      expectLoad(0x6808, 73, BigInt("1122334455667788", 16))
      expectLoad(0x6810, 74, BigInt("1716151413121110", 16))
      dut.io.protocolError.expect(false.B)
    }
  }
}
