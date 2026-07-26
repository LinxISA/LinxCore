package linxcore.top

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

class FullBidBlockConditionOwnerSpec extends AnyFunSuite with ChiselSim {
  private def clearInputs(dut: FullBidBlockConditionOwner): Unit = {
    dut.io.captureValid.poke(false.B)
    dut.io.captureTaken.poke(false.B)
    dut.io.captureBlockBidValid.poke(false.B)
    dut.io.captureBlockBid.poke(0.U)
    dut.io.activeBlockValid.poke(false.B)
    dut.io.activeBlockBid.poke(0.U)
    dut.io.fretValid.poke(false.B)
    dut.io.fretBlockBidValid.poke(false.B)
    dut.io.fretBlockBid.poke(0.U)
    dut.io.fretConsume.poke(false.B)
  }

  test("SETC condition survives unrelated markers and is consumed only by matching full BID FRET") {
    simulate(new FullBidBlockConditionOwner(16)) { dut =>
      clearInputs(dut)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      dut.io.captureValid.poke(true.B)
      dut.io.captureTaken.poke(true.B)
      dut.io.captureBlockBidValid.poke(true.B)
      dut.io.captureBlockBid.poke(0x1234.U)
      dut.io.activeBlockValid.poke(true.B)
      dut.io.activeBlockBid.poke(0x1234.U)
      dut.io.activeConditionValid.expect(true.B)
      dut.io.activeConditionTaken.expect(true.B)
      dut.clock.step()

      clearInputs(dut)
      dut.io.activeBlockValid.poke(true.B)
      dut.io.activeBlockBid.poke(0x5678.U)
      dut.io.activeConditionValid.expect(false.B)
      dut.clock.step()

      clearInputs(dut)
      dut.io.fretValid.poke(true.B)
      dut.io.fretBlockBidValid.poke(true.B)
      dut.io.fretBlockBid.poke(0x5678.U)
      dut.io.fretConditionValid.expect(false.B)

      dut.io.fretBlockBid.poke(0x1234.U)
      dut.io.fretConditionValid.expect(true.B)
      dut.io.fretConditionTaken.expect(true.B)
      dut.io.fretConsume.poke(true.B)
      dut.clock.step()

      clearInputs(dut)
      dut.io.fretValid.poke(true.B)
      dut.io.fretBlockBidValid.poke(true.B)
      dut.io.fretBlockBid.poke(0x1234.U)
      dut.io.fretConditionValid.expect(false.B)
    }
  }
}
