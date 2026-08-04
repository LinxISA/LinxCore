package linxcore.iex

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.ooo.OooOpcodeRecipeTable
import linxcore.top.interface.FrontEndOpKind
import org.scalatest.funsuite.AnyFunSuite

class DTUActivationTraceSpec extends AnyFunSuite with ChiselSim {
  private val compact = {
    val base = OOOIEXLSUActivationParams.W4
    base.copy(ooo = base.ooo.copy(
      robGroupsPerStid = 4,
      brobEntriesPerStid = 4))
  }

  private def initialize(dut: OOOIEXLSUActivationProbe): Unit = {
    dut.io.program.valid.poke(false.B)
    dut.io.program.bits.poke(0.U.asTypeOf(dut.io.program.bits))
    dut.io.commitReady.poke(true.B)
    dut.io.trapReady.poke(true.B)
    dut.io.cmdReady.poke(true.B)
    dut.io.systemReady.poke(true.B)
    dut.io.oooTraceReady.poke(true.B)
    dut.io.iexTraceReady.poke(false.B)
    dut.io.recoveryReady.poke(true.B)
    dut.io.lsuTraceReady.poke(true.B)
    dut.io.memoryReady.foreach(_.poke(true.B))
    dut.io.memoryResponseValid.poke(false.B)
    dut.io.memoryResponseId.poke(0.U)
    dut.io.memoryResponseGeneration.poke(0.U)
    dut.io.memoryResponseAddress.poke(0.U)
    dut.io.memoryResponseData.poke(0.U)
    dut.io.loadReissueRequest.valid.poke(false.B)
    dut.io.loadReissueRequest.bits.poke(
      0.U.asTypeOf(dut.io.loadReissueRequest.bits))
    dut.io.loadResultInject.valid.poke(false.B)
    dut.io.loadResultInject.bits.poke(
      0.U.asTypeOf(dut.io.loadResultInject.bits))
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
    while (!dut.io.bootstrapComplete.peek().litToBoolean) dut.clock.step()
  }

  private def sendClosedPair(dut: OOOIEXLSUActivationProbe): Unit = {
    val start = OooOpcodeRecipeTable.Rules.find(
      _.symbol == "OP_BSTART_FALL").get
    val addi = OooOpcodeRecipeTable.Rules.find(_.symbol == "OP_ADDI").get
    val stop = OooOpcodeRecipeTable.Rules.find(_.symbol == "OP_BSTOP").get
    val encodings = Seq(
      (start, start.value, 0x1000),
      (addi, addi.value | (BigInt(5) << 7) | (BigInt(7) << 20), 0x1004),
      (addi, addi.value | (BigInt(6) << 7) | (BigInt(9) << 20), 0x1008),
      (stop, stop.value, 0x100c))
    dut.io.program.bits.poke(0.U.asTypeOf(dut.io.program.bits))
    dut.io.program.bits.count.poke(encodings.size.U)
    encodings.zipWithIndex.foreach { case ((rule, raw, pc), lane) =>
      val entry = dut.io.program.bits.entries(lane)
      entry.kind.poke(FrontEndOpKind.Encoded64)
      entry.parent.identity.peId.poke(1.U)
      entry.parent.identity.stid.poke(0.U)
      entry.parent.identity.instructionId.poke((lane + 1).U)
      entry.parent.identity.epoch.poke(1.U)
      entry.parent.pc.poke(pc.U)
      entry.parent.instruction.poke(raw.U)
      entry.parent.lengthBytes.poke(rule.lenBytes.U)
      entry.parent.prediction.valid.poke(true.B)
      entry.parent.prediction.requestPc.poke(pc.U)
      entry.parent.prediction.fallthroughPc.poke((pc + rule.lenBytes).U)
      entry.parent.prediction.epoch.poke(1.U)
    }
    dut.io.program.valid.poke(true.B)
    while (!dut.io.program.ready.peek().litToBoolean) dut.clock.step()
    dut.clock.step()
    dut.io.program.valid.poke(false.B)
  }

  test("stalled external trace export cannot suppress terminal or commit progress") {
    simulate(new OOOIEXLSUActivationProbe(compact)) { dut =>
      initialize(dut)
      sendClosedPair(dut)

      var firstCycles = 0
      while ((!dut.io.dtuTraceValid.peek().litToBoolean ||
          dut.io.dtuTraceAcceptedCount.peek().litValue < 1 ||
          dut.io.rfWriteCount.peek().litValue < 1) && firstCycles < 64) {
        dut.clock.step()
        firstCycles += 1
      }
      dut.io.dtuTraceValid.expect(true.B)
      dut.io.dtuTraceAcceptedCount.expect(1.U)
      dut.io.rfWriteCount.expect(1.U)
      val retained = dut.io.dtuTrace.peek()
      dut.clock.step(3)
      dut.io.dtuTraceValid.expect(true.B)
      dut.io.dtuTrace.expect(retained)

      var cycles = 0
      while ((dut.io.rfWriteCount.peek().litValue < 2 ||
          dut.io.resolveCount.peek().litValue < 2 ||
          dut.io.commitCount.peek().litValue < 1 ||
          dut.io.dtuTraceAcceptedCount.peek().litValue < 2 ||
          dut.io.dtuTraceDroppedCount.peek().litValue < 1) && cycles < 96) {
        dut.clock.step()
        cycles += 1
      }

      val finalCounts =
        s"rf=${dut.io.rfWriteCount.peek().litValue} " +
          s"resolve=${dut.io.resolveCount.peek().litValue} " +
          s"commit=${dut.io.commitCount.peek().litValue} " +
          s"accepted=${dut.io.dtuTraceAcceptedCount.peek().litValue} " +
          s"dropped=${dut.io.dtuTraceDroppedCount.peek().litValue}"
      info(finalCounts)
      assert(dut.io.rfWriteCount.peek().litValue == 2, finalCounts)
      assert(dut.io.resolveCount.peek().litValue == 2, finalCounts)
      assert(dut.io.commitCount.peek().litValue == 1, finalCounts)
      assert(dut.io.dtuTraceAcceptedCount.peek().litValue == 2, finalCounts)
      assert(dut.io.dtuTraceDroppedCount.peek().litValue == 1, finalCounts)
      dut.io.dtuTraceValid.expect(true.B)
      dut.io.dtuTrace.expect(retained)
    }
  }
}
