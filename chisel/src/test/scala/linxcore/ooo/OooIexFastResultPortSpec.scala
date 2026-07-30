package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.common.OperandClass
import org.scalatest.funsuite.AnyFunSuite

class OooIexFastResultPortSpec extends AnyFunSuite with ChiselSim {
  private def clear(dut: OooIexFastResultPort): Unit = {
    dut.io.writeback.valid.poke(false.B)
    dut.io.writeback.bits.poke(0.U.asTypeOf(dut.io.writeback.bits))
    dut.io.wakeup.valid.poke(false.B)
    dut.io.wakeup.bits.poke(0.U.asTypeOf(dut.io.wakeup.bits))
    dut.io.pWriteReady.poke(false.B)
  }

  private def offer(dut: OooIexFastResultPort): Unit = {
    dut.io.writeback.valid.poke(true.B)
    dut.io.writeback.bits.stid.poke(1.U)
    dut.io.writeback.bits.epoch.poke(3.U)
    dut.io.writeback.bits.ptag.poke(29.U)
    dut.io.writeback.bits.ptagGeneration.poke(7.U)
    dut.io.writeback.bits.data.poke("h123456789abcdef0".U)
    dut.io.wakeup.valid.poke(true.B)
    dut.io.wakeup.bits.stid.poke(1.U)
    dut.io.wakeup.bits.epoch.poke(3.U)
    dut.io.wakeup.bits.operandClass.poke(OperandClass.P)
    dut.io.wakeup.bits.ptag.poke(29.U)
    dut.io.wakeup.bits.ptagGeneration.poke(7.U)
  }

  test("waits for exact PRF owner preflight before atomic result acceptance") {
    simulate(new OooIexFastResultPort()) { dut =>
      clear(dut)
      offer(dut)

      dut.io.writeback.ready.expect(false.B)
      dut.io.wakeup.ready.expect(false.B)
      dut.io.pWrite.valid.expect(false.B)
      dut.io.issueWakeup.valid.expect(false.B)

      dut.io.pWriteReady.poke(true.B)
      dut.io.writeback.ready.expect(true.B)
      dut.io.wakeup.ready.expect(true.B)
      dut.io.accepted.expect(true.B)
      dut.io.pWrite.valid.expect(true.B)
      dut.io.pWrite.bits.commit.expect(true.B)
      dut.io.pWrite.bits.key.stid.expect(1.U)
      dut.io.pWrite.bits.key.epoch.expect(3.U)
      dut.io.pWrite.bits.key.ptag.expect(29.U)
      dut.io.pWrite.bits.key.generation.expect(7.U)
      dut.io.pWrite.bits.data.expect("h123456789abcdef0".U)
      dut.io.issueWakeup.valid.expect(true.B)
      dut.clock.step()
    }
  }

  test("rejects mismatched fast writeback and wakeup identities") {
    assertThrows[Exception] {
      simulate(new OooIexFastResultPort()) { dut =>
        clear(dut)
        offer(dut)
        dut.io.wakeup.bits.ptagGeneration.poke(8.U)
        dut.io.pWriteReady.poke(true.B)
        dut.io.rejected.expect(true.B)
        dut.io.writeback.ready.expect(false.B)
        dut.io.wakeup.ready.expect(false.B)
        dut.clock.step()
      }
    }
  }
}
