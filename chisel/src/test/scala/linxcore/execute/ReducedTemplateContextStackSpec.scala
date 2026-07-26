package linxcore.execute

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

class ReducedTemplateContextStackSpec extends AnyFunSuite with ChiselSim {
  private def clear(dut: ReducedTemplateContextStack): Unit = {
    dut.io.flush.poke(false.B)
    dut.io.cancel.poke(false.B)
    dut.io.captureStart.poke(false.B)
    dut.io.captureStartArch.poke(0.U)
    dut.io.captureEndArch.poke(0.U)
    dut.io.captureReadReady.poke(false.B)
    dut.io.captureReadData.poke(0.U)
    dut.io.restoreStart.poke(false.B)
    dut.io.restoreWriteReady.poke(false.B)
    for (arch <- 0 until 24) {
      dut.io.captureMap(arch).poke(arch.U)
      dut.io.restoreMap(arch).poke((arch + 24).U)
    }
  }

  private def capture(
      dut: ReducedTemplateContextStack,
      start: Int,
      end: Int,
      dataBase: Int): Unit = {
    dut.io.captureStartArch.poke(start.U)
    dut.io.captureEndArch.poke(end.U)
    dut.io.captureStart.poke(true.B)
    dut.clock.step()
    dut.io.captureStart.poke(false.B)
    while (dut.io.captureBusy.peek().litToBoolean) {
      val tag = dut.io.captureReadTag.peek().litValue.toInt
      dut.io.captureReadReady.poke(true.B)
      dut.io.captureReadData.poke((dataBase + tag).U)
      dut.clock.step()
    }
    dut.io.captureReadReady.poke(false.B)
  }

  private def restore(dut: ReducedTemplateContextStack): Seq[(Int, Int)] = {
    dut.io.restoreStart.poke(true.B)
    dut.clock.step()
    dut.io.restoreStart.poke(false.B)
    val writes = scala.collection.mutable.ArrayBuffer.empty[(Int, Int)]
    while (dut.io.restoreBusy.peek().litToBoolean) {
      writes += ((
        dut.io.restoreWriteTag.peek().litValue.toInt,
        dut.io.restoreWriteData.peek().litValue.toInt))
      dut.io.restoreWriteReady.poke(true.B)
      dut.clock.step()
    }
    dut.io.restoreWriteReady.poke(false.B)
    writes.toSeq
  }

  test("nested template frames restore in LIFO register-ring order") {
    simulate(new ReducedTemplateContextStack(frameDepth = 4)) { dut =>
      clear(dut)

      capture(dut, start = 10, end = 12, dataBase = 1000)
      dut.io.frameCount.expect(1.U)
      capture(dut, start = 22, end = 3, dataBase = 2000)
      dut.io.frameCount.expect(2.U)

      assert(restore(dut) == Seq(
        (46, 2022),
        (47, 2023),
        (26, 2002),
        (27, 2003)))
      dut.io.frameCount.expect(1.U)

      assert(restore(dut) == Seq(
        (34, 1010),
        (35, 1011),
        (36, 1012)))
      dut.io.frameCount.expect(0.U)
      dut.io.overflow.expect(false.B)
      dut.io.underflow.expect(false.B)
    }
  }

  test("suffix recovery cancels an in-flight restore without popping the committed frame") {
    simulate(new ReducedTemplateContextStack(frameDepth = 4)) { dut =>
      clear(dut)
      capture(dut, start = 10, end = 12, dataBase = 1000)
      dut.io.frameCount.expect(1.U)

      dut.io.restoreStart.poke(true.B)
      dut.clock.step()
      dut.io.restoreStart.poke(false.B)
      dut.io.restoreBusy.expect(true.B)
      dut.io.restoreWriteReady.poke(true.B)
      dut.clock.step()

      dut.io.cancel.poke(true.B)
      dut.clock.step()
      dut.io.cancel.poke(false.B)
      dut.io.restoreWriteReady.poke(false.B)
      dut.io.restoreBusy.expect(false.B)
      dut.io.restoreDone.expect(false.B)
      dut.io.frameCount.expect(1.U)

      assert(restore(dut) == Seq(
        (34, 1010),
        (35, 1011),
        (36, 1012)))
      dut.io.frameCount.expect(0.U)
    }
  }
}
