package linxcore.frontend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

class LinxCoreIfuThroughputSpec extends AnyFunSuite with ChiselSim {
  private def pulseStart(dut: LinxCoreIfuThroughputProbe, pc: BigInt): Unit = {
    dut.io.startValid.poke(true.B)
    dut.io.startPc.poke(pc.U)
    dut.clock.step()
    dut.io.startValid.poke(false.B)
  }

  test("a warmed architectural IFU sustains thirty-two consecutive four-wide D1 groups") {
    simulate(new LinxCoreIfuThroughputProbe) { dut =>
      dut.io.startValid.poke(false.B)
      dut.io.startPc.poke(0x1000.U)
      dut.io.d1Ready.poke(true.B)

      pulseStart(dut, 0x1000)
      var warmCycles = 0
      while (dut.io.lineRefillCount.peek().litValue < 4 && warmCycles < 800) {
        dut.clock.step()
        warmCycles += 1
      }
      assert(dut.io.lineRefillCount.peek().litValue >= 4, "four cachelines did not warm")

      pulseStart(dut, 0x1000)
      var fillCycles = 0
      var joinPeak = BigInt(0)
      var contextPeak = BigInt(0)
      while (!dut.io.d1Valid.peek().litToBoolean && fillCycles < 160) {
        joinPeak = joinPeak.max(dut.io.joinCount.peek().litValue)
        contextPeak = contextPeak.max(dut.io.lineContextCount.peek().litValue)
        dut.clock.step()
        fillCycles += 1
      }
      assert(dut.io.d1Valid.peek().litToBoolean, "warmed IFU did not reach D1")

      for (group <- 0 until 32) {
        joinPeak = joinPeak.max(dut.io.joinCount.peek().litValue)
        contextPeak = contextPeak.max(dut.io.lineContextCount.peek().litValue)
        dut.io.d1Valid.expect(true.B)
        dut.io.d1Fire.expect(true.B)
        dut.io.d1ValidMask.expect("b1111".U)
        for (lane <- 0 until 4) {
          dut.io.d1Pc(lane).expect((0x1000 + group * 8 + lane * 2).U)
          dut.io.d1PredictionStage(lane).expect(BSideStage.BF4.asUInt)
          dut.io.d1PredictionFinal(lane).expect(true.B)
        }
        dut.io.canonicalFlushValid.expect(false.B)
        dut.clock.step()
      }
      assert(joinPeak >= 2, s"expected multiple in-flight prediction joins, peak=$joinPeak")
      assert(contextPeak >= 2, s"expected multiple in-flight line contexts, peak=$contextPeak")
    }
  }
}
