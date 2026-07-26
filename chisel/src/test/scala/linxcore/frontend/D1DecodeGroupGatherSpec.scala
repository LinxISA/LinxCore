package linxcore.frontend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.common.{BoundaryKind, InterfaceParams}
import org.scalatest.funsuite.AnyFunSuite

class D1DecodeGroupGatherSpec extends AnyFunSuite with ChiselSim {
  private val p = InterfaceParams()

  private def clear(dut: D1DecodeGroupGather): Unit = {
    dut.io.in.valid.poke(false.B)
    dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
    dut.io.out.ready.poke(false.B)
    dut.io.flush.poke(0.U.asTypeOf(dut.io.flush))
  }

  private def pokeGroup(dut: D1DecodeGroupGather, basePc: BigInt, threadId: Int, epoch: Int): Unit = {
    dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
    dut.io.in.valid.poke(true.B)
    dut.io.in.bits.validMask.poke("b1111".U)
    for (lane <- 0 until 4) {
      val entry = dut.io.in.bits.entries(lane)
      val pc = basePc + lane * 2
      entry.pc.poke(pc.U)
      entry.transactionId.poke(0x30.U)
      entry.insn.poke((0x10 + lane).U)
      entry.lenBytes.poke(2.U)
      entry.identity.threadId.poke(threadId.U)
      entry.identity.fetchSeq.poke((0x20 + lane).U)
      entry.identity.fetchSlot.poke(lane.U)
      entry.identity.epoch.poke(epoch.U)
      entry.prediction.valid.poke(true.B)
      entry.prediction.predictionTag.poke((0x40 + lane).U)
      entry.prediction.taken.poke((lane == 3).B)
      entry.prediction.branchPc.poke(pc.U)
      entry.prediction.target.poke((pc + 0x100).U)
      entry.prediction.fallthroughPc.poke((pc + 2).U)
      entry.prediction.kind.poke(BoundaryKind.Cond)
      entry.prediction.provider.poke(PredictionProvider.LongTage)
      entry.prediction.stage.poke(BSideStage.BF4)
      entry.prediction.confidence.poke((lane & 3).U)
      entry.prediction.epoch.poke(epoch.U)
    }
  }

  test("holds a four-wide D1 group under backpressure and preserves every prediction record") {
    simulate(new D1DecodeGroupGather(p)) { dut =>
      clear(dut)
      pokeGroup(dut, basePc = 0x1000, threadId = 0, epoch = 0)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()

      dut.io.in.valid.poke(false.B)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.validMask.expect("b1111".U)
      dut.io.out.bits.entries(0).pc.expect(0x1000.U)
      dut.io.out.bits.entries(3).pc.expect(0x1006.U)
      dut.io.out.bits.entries(0).prediction.provider.expect(PredictionProvider.LongTage)
      dut.io.out.bits.entries(3).prediction.taken.expect(true.B)
      dut.io.out.bits.entries(3).prediction.target.expect(0x1106.U)
      dut.io.out.bits.entries(3).prediction.fallthroughPc.expect(0x1008.U)
      dut.io.out.bits.entries(3).prediction.predictionTag.expect(0x43.U)
      dut.io.out.bits.entries(3).prediction.confidence.expect(3.U)
      dut.clock.step(2)
      dut.io.out.bits.entries(3).prediction.target.expect(0x1106.U)

      dut.io.out.ready.poke(true.B)
      dut.clock.step()
      dut.io.out.valid.expect(false.B)
    }
  }

  test("selective inner flush kills only the matching STID and accepts another STID") {
    simulate(new D1DecodeGroupGather(p)) { dut =>
      clear(dut)
      pokeGroup(dut, basePc = 0x1000, threadId = 0, epoch = 0)
      dut.clock.step()

      dut.io.in.valid.poke(false.B)
      dut.io.flush.valid.poke(true.B)
      dut.io.flush.threadId.poke(0.U)
      dut.io.flush.newEpoch.poke(1.U)
      dut.io.out.valid.expect(false.B)
      dut.clock.step()

      dut.io.flush.valid.poke(false.B)
      pokeGroup(dut, basePc = 0x8000, threadId = 1, epoch = 0)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.entries(0).pc.expect(0x8000.U)
    }
  }
}
