package linxcore.top

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.params.SimulationParamProfiles
import org.scalatest.funsuite.AnyFunSuite

class TracePrefixPackerSpec extends AnyFunSuite with ChiselSim {
  test("simultaneous sources pack a deterministic prefix and count overflow") {
    simulate(new TracePrefixPacker(SimulationParamProfiles.W4, 3)) { dut =>
      dut.io.out.ready.poke(false.B)
      dut.io.in.foreach { source =>
        source.bits.poke(0.U.asTypeOf(source.bits))
        source.valid.poke(true.B)
        source.bits.count.poke(2.U)
      }
      for (source <- 0 until 3; lane <- 0 until 2) {
        dut.io.in(source).bits.entries(lane).payload
          .poke((source * 10 + lane).U)
      }
      dut.io.in.foreach(_.ready.expect(true.B))
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.count.expect(4.U)
      Seq(0, 1, 10, 11).zipWithIndex.foreach { case (value, lane) =>
        dut.io.out.bits.entries(lane).payload.expect(value.U)
      }
      dut.io.dropped.expect(2.U)
    }
  }
}
