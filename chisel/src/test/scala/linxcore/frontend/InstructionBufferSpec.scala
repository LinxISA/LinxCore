package linxcore.frontend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import circt.stage.ChiselStage
import linxcore.common.{BoundaryKind, InterfaceParams}
import org.scalatest.funsuite.AnyFunSuite

class InstructionBufferSpec extends AnyFunSuite with ChiselSim {
  private val p = InterfaceParams()

  private def clear(dut: InstructionBuffer): Unit = {
    dut.io.enq.valid.poke(false.B)
    dut.io.enq.bits.poke(0.U.asTypeOf(dut.io.enq.bits))
    dut.io.deqThreadId.poke(0.U)
    dut.io.deq.ready.poke(false.B)
    dut.io.flush.poke(0.U.asTypeOf(dut.io.flush))
  }

  private def pokeGroup(
      dut: InstructionBuffer,
      threadId: Int,
      epoch: Int,
      pcs: Seq[BigInt],
      provider: PredictionProvider.Type = PredictionProvider.ShortTage): Unit = {
    dut.io.enq.bits.poke(0.U.asTypeOf(dut.io.enq.bits))
    dut.io.enq.valid.poke(true.B)
    dut.io.enq.bits.validMask.poke(((1 << pcs.size) - 1).U)
    pcs.zipWithIndex.foreach { case (pc, lane) =>
      val entry = dut.io.enq.bits.entries(lane)
      entry.pc.poke(pc.U)
      entry.insn.poke((BigInt("100000000000000f", 16) + lane).U)
      entry.lenBytes.poke(8.U)
      entry.isBlockStart.poke((lane == 0).B)
      entry.isBlockStop.poke((lane == pcs.size - 1).B)
      entry.identity.peId.poke(0.U)
      entry.identity.threadId.poke(threadId.U)
      entry.identity.fetchPacketUid.poke(0x40.U)
      entry.identity.fetchSeq.poke((0x80 + lane).U)
      entry.identity.fetchSlot.poke(lane.U)
      entry.identity.checkpointId.poke(3.U)
      entry.identity.epoch.poke(epoch.U)
      entry.prediction.valid.poke(true.B)
      entry.prediction.predictionTag.poke((0x100 + lane).U)
      entry.prediction.taken.poke((lane % 2 == 0).B)
      entry.prediction.branchPc.poke(pc.U)
      entry.prediction.target.poke((pc + 0x80).U)
      entry.prediction.fallthroughPc.poke((pc + 8).U)
      entry.prediction.kind.poke(BoundaryKind.Cond)
      entry.prediction.provider.poke(provider)
      entry.prediction.stage.poke(BSideStage.BF3)
      entry.prediction.confidence.poke((lane & 3).U)
      entry.prediction.checkpointId.poke(3.U)
      entry.prediction.epoch.poke(epoch.U)
    }
  }

  test("atomically enqueues and dequeues four fixed-width instructions with lane predictions") {
    simulate(new InstructionBuffer(p, depthPerThread = 8, threadCount = 1)) { dut =>
      clear(dut)
      pokeGroup(dut, threadId = 0, epoch = 0, pcs = Seq(0x1000, 0x1002, 0x1006, 0x100c))
      dut.io.enq.ready.expect(true.B)
      dut.clock.step()

      dut.io.enq.valid.poke(false.B)
      dut.io.deq.valid.expect(true.B)
      dut.io.deq.bits.validMask.expect("b1111".U)
      dut.io.deq.bits.entries(0).pc.expect(0x1000.U)
      dut.io.deq.bits.entries(3).pc.expect(0x100c.U)
      assert(dut.io.deq.bits.entries(0).insn.getWidth == 64)
      dut.io.deq.bits.entries(0).prediction.branchPc.expect(0x1000.U)
      dut.io.deq.bits.entries(3).prediction.target.expect(0x108c.U)
      dut.io.deq.bits.entries(3).prediction.fallthroughPc.expect(0x1014.U)
      dut.io.deq.bits.entries(3).prediction.predictionTag.expect(0x103.U)
      dut.io.deq.bits.entries(3).prediction.confidence.expect(3.U)
      dut.io.deq.bits.entries(0).prediction.provider.expect(PredictionProvider.ShortTage)
      dut.io.counts(0).expect(4.U)

      dut.io.deq.ready.poke(true.B)
      dut.clock.step()
      dut.io.deq.valid.expect(false.B)
      dut.io.counts(0).expect(0.U)
    }
  }

  test("supports a four-row dequeue and four-row enqueue in the same cycle") {
    simulate(new InstructionBuffer(p, depthPerThread = 4, threadCount = 1)) { dut =>
      clear(dut)
      pokeGroup(dut, threadId = 0, epoch = 0, pcs = Seq(0x1000, 0x1002, 0x1004, 0x1006))
      dut.clock.step()

      pokeGroup(dut, threadId = 0, epoch = 0, pcs = Seq(0x2000, 0x2002, 0x2004, 0x2006))
      dut.io.deq.ready.poke(true.B)
      dut.io.enq.ready.expect(true.B)
      dut.io.deq.bits.entries(0).pc.expect(0x1000.U)
      dut.clock.step()

      dut.io.enq.valid.poke(false.B)
      dut.io.deq.ready.poke(false.B)
      dut.io.counts(0).expect(4.U)
      dut.io.deq.bits.entries(0).pc.expect(0x2000.U)
      dut.io.deq.bits.entries(3).pc.expect(0x2006.U)
    }
  }

  test("keeps STID banks independent and flushes only the selected epoch domain") {
    simulate(new InstructionBuffer(p, depthPerThread = 8, threadCount = 2)) { dut =>
      clear(dut)
      pokeGroup(dut, threadId = 0, epoch = 0, pcs = Seq(0x1000, 0x1002))
      dut.clock.step()
      pokeGroup(dut, threadId = 1, epoch = 0, pcs = Seq(0x8000))
      dut.clock.step()

      dut.io.enq.valid.poke(false.B)
      dut.io.deqThreadId.poke(1.U)
      dut.io.deq.bits.validMask.expect("b0001".U)
      dut.io.deq.bits.entries(0).pc.expect(0x8000.U)

      dut.io.flush.valid.poke(true.B)
      dut.io.flush.threadId.poke(0.U)
      dut.io.flush.newEpoch.poke(1.U)
      dut.io.flush.restartPc.poke(0x4000.U)
      dut.io.flush.reason.poke(IfuInnerFlushReason.PredictionCorrection)
      dut.clock.step()

      dut.io.flush.valid.poke(false.B)
      dut.io.counts(0).expect(0.U)
      dut.io.counts(1).expect(1.U)
      dut.io.activeEpochs(0).expect(1.U)
      dut.io.deqThreadId.poke(1.U)
      dut.io.deq.valid.expect(true.B)
      dut.io.deq.bits.entries(0).pc.expect(0x8000.U)
    }
  }

  test("rejects stale epochs and malformed sparse enqueue masks") {
    simulate(new InstructionBuffer(p, depthPerThread = 8, threadCount = 1)) { dut =>
      clear(dut)
      dut.io.flush.valid.poke(true.B)
      dut.io.flush.threadId.poke(0.U)
      dut.io.flush.newEpoch.poke(2.U)
      dut.clock.step()
      dut.io.flush.valid.poke(false.B)

      pokeGroup(dut, threadId = 0, epoch = 1, pcs = Seq(0x1000))
      dut.io.enq.ready.expect(false.B)
      dut.io.enqRejectedStale.expect(true.B)

      pokeGroup(dut, threadId = 0, epoch = 2, pcs = Seq(0x2000, 0x2002))
      dut.io.enq.bits.validMask.poke("b0101".U)
      dut.io.enq.ready.expect(false.B)
      dut.io.enqRejectedMalformed.expect(true.B)

      dut.io.enq.bits.validMask.poke("b0011".U)
      dut.io.enq.ready.expect(true.B)
    }
  }

  test("InstructionBuffer elaborates with the architectural four-wide interface") {
    val sv = ChiselStage.emitSystemVerilog(new InstructionBuffer(p, depthPerThread = 8, threadCount = 2))
    assert(sv.contains("module InstructionBuffer"))
    assert(sv.contains("io_enq_bits_validMask"))
    assert(sv.contains("io_deq_bits_validMask"))
    assert(sv.contains("io_activeEpochs"))
  }
}
