package linxcore.frontend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.common.{BoundaryKind, InterfaceParams}
import org.scalatest.funsuite.AnyFunSuite

class D1InstructionDecodeStageSpec extends AnyFunSuite with ChiselSim {
  private val p = InterfaceParams()

  private def clear(dut: D1InstructionDecodeStage): Unit = {
    dut.io.in.valid.poke(false.B)
    dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
    dut.io.flush.poke(0.U.asTypeOf(dut.io.flush))
    dut.io.out.ready.poke(false.B)
  }

  private def presentFour(
      dut: D1InstructionDecodeStage,
      firstFetchSeq: Int = 20,
      firstPacketUid: Int = 100): Unit = {
    dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
    dut.io.in.valid.poke(true.B)
    dut.io.in.bits.validMask.poke("b1111".U)
    for (lane <- 0 until p.decodeWidth) {
      val entry = dut.io.in.bits.entries(lane)
      entry.pc.poke((0x2000 + lane * 2).U)
      entry.instructionUid.poke((0x900 + lane).U)
      entry.insn.poke((0x0008 | (lane + 1) << 6 | (lane + 2) << 11).U)
      entry.lenBytes.poke(2.U)
      entry.identity.peId.poke(3.U)
      entry.identity.threadId.poke(0.U)
      entry.identity.fetchPacketUid.poke((firstPacketUid + lane).U)
      entry.identity.fetchSeq.poke((firstFetchSeq + lane).U)
      entry.identity.fetchSlot.poke(lane.U)
      entry.identity.checkpointId.poke((7 + lane).U)
      entry.identity.epoch.poke(2.U)
      entry.prediction.valid.poke(true.B)
      entry.prediction.predictionTag.poke((0x400 + lane).U)
      entry.prediction.taken.poke((lane % 2 == 1).B)
      entry.prediction.branchPc.poke((0x2080 + lane * 4).U)
      entry.prediction.target.poke((0x3000 + lane * 8).U)
      entry.prediction.fallthroughPc.poke((0x2082 + lane * 4).U)
      entry.prediction.kind.poke(BoundaryKind.Cond)
      entry.prediction.provider.poke(PredictionProvider.LongTage)
      entry.prediction.stage.poke(BSideStage.BF4)
      entry.prediction.confidence.poke(3.U)
      entry.prediction.checkpointId.poke((7 + lane).U)
      entry.prediction.epoch.poke(2.U)
    }
  }

  test("decodes four fixed-width entries atomically and preserves every prediction sidecar") {
    simulate(new D1InstructionDecodeStage(p)) { dut =>
      clear(dut)
      presentFour(dut)

      dut.io.out.valid.expect(true.B)
      dut.io.in.ready.expect(false.B)
      dut.io.out.bits.validMask.expect("b1111".U)
      for (lane <- 0 until p.decodeWidth) {
        val out = dut.io.out.bits.entries(lane)
        out.valid.expect(true.B)
        out.pc.expect((0x2000 + lane * 2).U)
        out.uid.uid.expect((0x900 + lane).U)
        out.uid.fetchPacketUid.expect((100 + lane).U)
        out.predTaken.expect((lane % 2 == 1).B)
        out.prediction.predictionTag.expect((0x400 + lane).U)
        out.prediction.target.expect((0x3000 + lane * 8).U)
        out.prediction.provider.expect(PredictionProvider.LongTage.asUInt)
        out.prediction.stage.expect(BSideStage.BF4.asUInt)
        out.prediction.checkpointId.expect((7 + lane).U)
        out.prediction.epoch.expect(2.U)
      }

      dut.clock.step(2)
      dut.io.out.bits.entries(3).prediction.target.expect(0x3018.U)
      dut.io.out.ready.poke(true.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
    }
  }

  test("a precise inner flush preserves only the older dense prefix") {
    simulate(new D1InstructionDecodeStage(p)) { dut =>
      clear(dut)
      presentFour(dut, firstFetchSeq = 10, firstPacketUid = 100)
      dut.io.out.ready.poke(true.B)

      dut.io.flush.valid.poke(true.B)
      dut.io.flush.threadId.poke(0.U)
      dut.io.flush.transactionId.poke(102.U)
      dut.io.flush.fetchSeq.poke(12.U)
      dut.io.flush.oldEpoch.poke(2.U)
      dut.io.flush.scope.poke(IfuPruneScope.KillTriggerAndYounger)

      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.validMask.expect("b0011".U)
      dut.io.out.bits.entries(0).uid.uid.expect(0x900.U)
      dut.io.out.bits.entries(1).uid.uid.expect(0x901.U)
      dut.io.out.bits.entries(2).valid.expect(false.B)
      dut.io.out.bits.entries(3).valid.expect(false.B)
      dut.io.in.ready.expect(true.B)
    }
  }

  test("an invalid opcode remains a row in the dense group and is reported separately") {
    simulate(new D1InstructionDecodeStage(p)) { dut =>
      clear(dut)
      presentFour(dut)
      dut.io.in.bits.entries(0).insn.poke("hffff".U)
      dut.io.out.ready.poke(true.B)

      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.validMask.expect("b1111".U)
      dut.io.out.bits.invalidOpcodeMask.expect("b0001".U)
      dut.io.out.bits.entries(0).valid.expect(false.B)
      dut.io.out.bits.entries(1).valid.expect(true.B)
      dut.clock.step()
    }
  }
}
