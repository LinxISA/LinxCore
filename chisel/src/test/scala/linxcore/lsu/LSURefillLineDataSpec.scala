package linxcore.lsu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.params.SimulationParamProfiles
import org.scalatest.funsuite.AnyFunSuite

class LSURefillLineDataSpec extends AnyFunSuite with ChiselSim {
  test("nonuniform 64-byte response preserves bytes at multiple offsets") {
    val p = SimulationParamProfiles.W2
    simulate(new LSURefillLineData(p)) { dut =>
      dut.io.response.poke(0.U.asTypeOf(dut.io.response))
      val bytes = (0 until p.lsu.lineBytes).map(index =>
        BigInt((index * 37 + 11) & 0xff))
      val line = bytes.zipWithIndex.map { case (byte, index) =>
        byte << (index * 8)
      }.reduce(_ | _)
      dut.io.response.data.poke(bytes.take(8).zipWithIndex.map {
        case (byte, index) => byte << (index * 8)
      }.reduce(_ | _).U)
      dut.io.response.lineData.poke(line.U)
      dut.io.lineData.expect(line.U)
      val observed = dut.io.lineData.peek().litValue
      Seq(0, 7, 8, 31, 47, 63).foreach { offset =>
        assert(((observed >> (offset * 8)) & 0xff) == bytes(offset))
      }
      assert(bytes.slice(8, 16).distinct.size > 1)
    }
  }
}
