package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.params.SimulationParamProfiles
import org.scalatest.funsuite.AnyFunSuite

class OooFastResultAtomicPublishSpec extends AnyFunSuite with ChiselSim {
  test("either blocked peer prevents every fast-result side effect") {
    simulate(new OooFastResultAtomicPublish(SimulationParamProfiles.W2)) { dut =>
      dut.io.source.bits.poke(0.U.asTypeOf(dut.io.source.bits))
      dut.io.source.bits.value.poke("h1234".U)
      dut.io.source.valid.poke(true.B)

      dut.io.iex.ready.poke(true.B)
      dut.io.rob.ready.poke(false.B)
      dut.io.source.ready.expect(false.B)
      dut.io.iex.valid.expect(false.B)
      dut.io.rob.valid.expect(true.B)
      dut.io.rob.valid.expect(true.B)
      dut.io.rob.ready.expect(false.B)

      dut.io.iex.ready.poke(false.B)
      dut.io.rob.ready.poke(true.B)
      dut.io.source.ready.expect(false.B)
      dut.io.iex.valid.expect(true.B)
      dut.io.iex.valid.expect(true.B)
      dut.io.iex.ready.expect(false.B)
      dut.io.rob.valid.expect(false.B)

      dut.io.iex.ready.poke(true.B)
      dut.io.rob.ready.poke(true.B)
      dut.io.source.ready.expect(true.B)
      dut.io.iex.valid.expect(true.B)
      dut.io.iex.ready.expect(true.B)
      dut.io.rob.valid.expect(true.B)
      dut.io.rob.ready.expect(true.B)
      dut.io.iex.bits.writeback.value.expect("h1234".U)
      dut.io.rob.bits.value.expect("h1234".U)
      dut.clock.step()
    }
  }
}
