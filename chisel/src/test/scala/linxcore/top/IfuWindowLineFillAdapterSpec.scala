package linxcore.top

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.common.InterfaceParams
import org.scalatest.funsuite.AnyFunSuite

class IfuWindowLineFillAdapterSpec extends AnyFunSuite with ChiselSim {
  private val p = InterfaceParams()

  private def clear(dut: IfuWindowLineFillAdapter): Unit = {
    dut.io.lineRequest.valid.poke(false.B)
    dut.io.lineRequest.bits.poke(0.U.asTypeOf(dut.io.lineRequest.bits))
    dut.io.lineResponse.ready.poke(false.B)
    dut.io.fetchReqReady.poke(false.B)
    dut.io.fetchRespValid.poke(false.B)
    dut.io.fetchRespWindow.poke(0.U)
  }

  test("assembles eight ordered ELF windows into one tagged little-endian line") {
    simulate(new IfuWindowLineFillAdapter(p)) { dut =>
      clear(dut)
      dut.io.lineRequest.valid.poke(true.B)
      dut.io.lineRequest.bits.tag.poke(0x55.U)
      dut.io.lineRequest.bits.linePa.poke(0x1200.U)
      dut.io.lineRequest.ready.expect(true.B)
      dut.clock.step()
      dut.io.lineRequest.valid.poke(false.B)

      for (beat <- 0 until 8) {
        dut.io.fetchReqValid.expect(true.B)
        dut.io.fetchReqPc.expect((0x1200 + beat * 8).U)
        dut.io.fetchReqReady.poke(true.B)
        dut.clock.step()
        dut.io.fetchReqReady.poke(false.B)
        dut.io.fetchReqValid.expect(false.B)
        dut.io.fetchRespReady.expect(true.B)
        dut.io.fetchRespWindow.poke((0x100 + beat).U)
        dut.io.fetchRespValid.poke(true.B)
        dut.clock.step()
        dut.io.fetchRespValid.poke(false.B)
      }

      val expected = (0 until 8).map(beat => BigInt(0x100 + beat) << (beat * 64)).sum
      dut.io.lineResponse.valid.expect(true.B)
      dut.io.lineResponse.bits.tag.expect(0x55.U)
      dut.io.lineResponse.bits.linePa.expect(0x1200.U)
      dut.io.lineResponse.bits.lineData.expect(expected.U)
      dut.clock.step(2)
      dut.io.lineResponse.valid.expect(true.B)
      dut.io.lineResponse.bits.lineData.expect(expected.U)

      dut.io.lineResponse.ready.poke(true.B)
      dut.clock.step()
      dut.io.lineResponse.valid.expect(false.B)
      dut.io.lineRequest.ready.expect(true.B)
    }
  }

  test("holds request address and rejects a second line while a response is pending") {
    simulate(new IfuWindowLineFillAdapter(p)) { dut =>
      clear(dut)
      dut.io.lineRequest.valid.poke(true.B)
      dut.io.lineRequest.bits.tag.poke(3.U)
      dut.io.lineRequest.bits.linePa.poke(0x4000.U)
      dut.clock.step()
      dut.io.lineRequest.bits.tag.poke(4.U)
      dut.io.lineRequest.bits.linePa.poke(0x8000.U)
      dut.io.lineRequest.ready.expect(false.B)

      dut.io.fetchReqValid.expect(true.B)
      dut.io.fetchReqPc.expect(0x4000.U)
      dut.clock.step(3)
      dut.io.fetchReqPc.expect(0x4000.U)
      dut.io.fetchReqReady.poke(true.B)
      dut.clock.step()
      dut.io.fetchReqReady.poke(false.B)
      dut.io.fetchRespReady.expect(true.B)
    }
  }
}

