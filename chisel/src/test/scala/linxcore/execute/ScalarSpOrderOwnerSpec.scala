package linxcore.execute

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.commit.CommitTraceParams
import linxcore.common.InterfaceParams
import org.scalatest.funsuite.AnyFunSuite

class ScalarSpOrderOwnerSpec extends AnyFunSuite with ChiselSim {
  private val p = InterfaceParams()
  private val trace = CommitTraceParams(
    commitWidth = p.commitWidth,
    robValueWidth = p.robIndexWidth,
    blockBidWidth = p.blockBidWidth,
    pcWidth = p.pcWidth,
    insnWidth = p.insnWidth,
    lenWidth = p.lenWidth)

  private def clearInputs(dut: ScalarSpOrderOwner): Unit = {
    dut.io.flushValid.poke(false.B)
    dut.io.initValid.poke(false.B)
    dut.io.initData.poke(0.U)
    dut.io.recoveryRestoreValid.poke(false.B)
    dut.io.recoveryRestoreStid.poke(0.U)
    dut.io.recoveryRestoreData.poke(0.U)
    dut.io.reserveValid.poke(false.B)
    dut.io.reserve.poke(0.U.asTypeOf(dut.io.reserve))
    dut.io.terminalValid.poke(false.B)
    dut.io.terminal.poke(0.U.asTypeOf(dut.io.terminal))
    dut.io.terminalProducedValid.poke(false.B)
    dut.io.terminalProducedData.poke(0.U)
    dut.io.commit.poke(0.U.asTypeOf(dut.io.commit))
    dut.io.commitValidMask.poke(0.U)
  }

  test("redirect recovery preserves the returning FRET stack-pointer value") {
    simulate(new ScalarSpOrderOwner(p, trace, depth = 4, stidCount = 1)) { dut =>
      clearInputs(dut)
      dut.io.initValid.poke(true.B)
      dut.io.initData.poke(0x1000.U)
      dut.clock.step()
      clearInputs(dut)
      dut.io.currentSp.expect(0x1000.U)

      dut.io.reserveValid.poke(true.B)
      dut.io.reserve.access.valid.poke(true.B)
      dut.io.reserve.access.read.poke(true.B)
      dut.io.reserve.access.write.poke(true.B)
      dut.io.reserve.stid.poke(0.U)
      dut.io.reserve.bid.valid.poke(true.B)
      dut.io.reserve.bid.wrap.poke(false.B)
      dut.io.reserve.bid.value.poke(2.U)
      dut.io.reserve.rid.valid.poke(true.B)
      dut.io.reserve.rid.wrap.poke(false.B)
      dut.io.reserve.rid.value.poke(3.U)
      dut.io.reserve.epoch.poke(0.U)
      dut.clock.step()
      clearInputs(dut)
      dut.io.issueHeadValid.expect(true.B)

      dut.io.terminalValid.poke(true.B)
      dut.io.terminal.access.valid.poke(true.B)
      dut.io.terminal.access.read.poke(true.B)
      dut.io.terminal.access.write.poke(true.B)
      dut.io.terminal.stid.poke(0.U)
      dut.io.terminal.bid.valid.poke(true.B)
      dut.io.terminal.bid.wrap.poke(false.B)
      dut.io.terminal.bid.value.poke(2.U)
      dut.io.terminal.rid.valid.poke(true.B)
      dut.io.terminal.rid.wrap.poke(false.B)
      dut.io.terminal.rid.value.poke(3.U)
      dut.io.terminal.epoch.poke(0.U)
      dut.io.terminalProducedValid.poke(true.B)
      dut.io.terminalProducedData.poke(0x1080.U)
      dut.clock.step()
      clearInputs(dut)

      dut.io.flushValid.poke(true.B)
      dut.io.recoveryRestoreValid.poke(true.B)
      dut.io.recoveryRestoreStid.poke(0.U)
      dut.io.recoveryRestoreData.poke(0x1080.U)
      dut.clock.step()
      clearInputs(dut)

      dut.io.currentSp.expect(0x1080.U)
      dut.io.issueHeadValid.expect(false.B)
      dut.io.protocolError.expect(false.B)
    }
  }
}
