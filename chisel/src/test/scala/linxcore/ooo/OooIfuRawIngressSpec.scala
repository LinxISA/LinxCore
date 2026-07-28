package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.common.{BoundaryKind, InterfaceParams}
import linxcore.frontend.{
  BSideStage,
  IfuPruneScope,
  PredictionProvider
}
import org.scalatest.funsuite.AnyFunSuite

class OooIfuRawIngressSpec extends AnyFunSuite with ChiselSim {
  private val ifuP = InterfaceParams()

  private def clear(dut: OooIfuRawIngress): Unit = {
    dut.io.ifuD1.valid.poke(false.B)
    dut.io.ifuD1.bits.poke(0.U.asTypeOf(dut.io.ifuD1.bits))
    dut.io.selectStid.poke(0.U)
    dut.io.fence.foreach(_.poke(false.B))
    dut.io.out.ready.poke(false.B)
    dut.io.flush.poke(0.U.asTypeOf(dut.io.flush))
  }

  private def pokeGroup(
      dut: OooIfuRawIngress,
      stid: Int,
      firstId: Int,
      lanes: Int = 4,
      epoch: Int = 7,
      peId: Int = 2): Unit = {
    require(lanes >= 1 && lanes <= ifuP.decodeWidth)
    dut.io.ifuD1.bits.poke(0.U.asTypeOf(dut.io.ifuD1.bits))
    dut.io.ifuD1.bits.validMask.poke(((1 << lanes) - 1).U)
    for (lane <- 0 until lanes) {
      val entry = dut.io.ifuD1.bits.entries(lane)
      val instructionId = firstId + lane
      val pc = 0x1000 + instructionId * 4
      entry.pc.poke(pc.U)
      entry.instructionUid.poke(instructionId.U)
      entry.transactionId.poke((0x2000 + instructionId).U)
      entry.insn.poke((BigInt("10000000", 16) + instructionId).U)
      entry.lenBytes.poke(((lane % 4 + 1) * 2).U)
      entry.identity.peId.poke(peId.U)
      entry.identity.threadId.poke(stid.U)
      entry.identity.fetchPacketUid.poke((0x3000 + instructionId).U)
      entry.identity.fetchSeq.poke((0x4000 + instructionId).U)
      entry.identity.fetchSlot.poke(lane.U)
      entry.identity.checkpointId.poke(5.U)
      entry.identity.epoch.poke(epoch.U)
      entry.prediction.valid.poke(true.B)
      entry.prediction.predictionTag.poke((0x5000 + instructionId).U)
      entry.prediction.requestPc.poke(pc.U)
      entry.prediction.taken.poke((lane == lanes - 1).B)
      entry.prediction.branchPc.poke(pc.U)
      entry.prediction.target.poke((pc + 0x100).U)
      entry.prediction.fallthroughPc.poke((pc + 4).U)
      entry.prediction.kind.poke(BoundaryKind.Cond)
      entry.prediction.provider.poke(PredictionProvider.LongTage)
      entry.prediction.stage.poke(BSideStage.BF4)
      entry.prediction.confidence.poke((lane & 3).U)
      entry.prediction.checkpointId.poke(5.U)
      entry.prediction.epoch.poke(epoch.U)
    }
    dut.io.ifuD1.valid.poke(true.B)
  }

  private def enqueue(
      dut: OooIfuRawIngress,
      stid: Int,
      firstId: Int,
      lanes: Int = 4,
      epoch: Int = 7): Unit = {
    pokeGroup(dut, stid, firstId, lanes, epoch)
    dut.io.ifuD1.ready.expect(true.B)
    dut.clock.step()
    dut.io.ifuD1.valid.poke(false.B)
  }

  test("adapts a four-wide IFU group into two-wide exact production prefixes") {
    val oooP = OooParams(instructionDecodeWidth = 2)
    simulate(new OooIfuRawIngress(ifuP, oooP, depthPerStid = 8)) { dut =>
      clear(dut)
      enqueue(dut, stid = 1, firstId = 10)

      dut.io.counts(1).expect(4.U)
      dut.io.eligibleMask.expect("b0010".U)
      dut.io.selectStid.poke(1.U)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.validMask.expect("b11".U)
      dut.io.out.bits.peId.expect(2.U)
      dut.io.out.bits.stid.expect(1.U)
      dut.io.out.bits.epoch.expect(7.U)

      val first = dut.io.out.bits.entries(0)
      first.parent.key.valid.expect(true.B)
      first.parent.key.instructionId.expect(10.U)
      first.parent.pc.expect((0x1000 + 10 * 4).U)
      first.parent.rawInstruction.expect((BigInt("10000000", 16) + 10).U)
      first.parent.prediction.transactionId.expect((0x2000 + 10).U)
      first.parent.prediction.fetchPacketUid.expect((0x3000 + 10).U)
      first.parent.prediction.fetchSeq.expect((0x4000 + 10).U)
      first.parent.prediction.predictionTag.expect((0x5000 + 10).U)
      first.parent.prediction.provider.expect(PredictionProvider.LongTage.asUInt)
      first.parent.prediction.stage.expect(BSideStage.BF4.asUInt)
      first.parent.traceOwner.expect(true.B)
      first.parent.preciseExceptionOwner.expect(true.B)

      dut.clock.step(2)
      dut.io.out.bits.entries(0).parent.key.instructionId.expect(10.U)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()
      dut.io.out.bits.entries(0).parent.key.instructionId.expect(12.U)
      dut.io.counts(1).expect(2.U)
      dut.clock.step()
      dut.io.counts(1).expect(0.U)
    }
  }

  test("gathers queued IFU groups into a six-wide prefix without reordering") {
    val oooP = OooParams(instructionDecodeWidth = 6)
    simulate(new OooIfuRawIngress(ifuP, oooP, depthPerStid = 16)) { dut =>
      clear(dut)
      dut.io.selectStid.poke(2.U)
      enqueue(dut, stid = 2, firstId = 20)
      enqueue(dut, stid = 2, firstId = 24)

      dut.io.counts(2).expect(8.U)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.validMask.expect("b111111".U)
      for (lane <- 0 until 6) {
        dut.io.out.bits.entries(lane).parent.key.instructionId.expect((20 + lane).U)
      }

      dut.io.out.ready.poke(true.B)
      dut.clock.step()
      dut.io.counts(2).expect(2.U)
      dut.io.out.bits.validMask.expect("b000011".U)
      dut.io.out.bits.entries(0).parent.key.instructionId.expect(26.U)
      dut.io.out.bits.entries(1).parent.key.instructionId.expect(27.U)
    }
  }

  test("uses same-bank dequeue credit to replace a full four-row reservoir") {
    val oooP = OooParams(instructionDecodeWidth = 4)
    simulate(new OooIfuRawIngress(ifuP, oooP, depthPerStid = 4)) { dut =>
      clear(dut)
      dut.io.selectStid.poke(2.U)
      enqueue(dut, stid = 2, firstId = 70)
      dut.io.counts(2).expect(4.U)

      pokeGroup(dut, stid = 2, firstId = 74)
      dut.io.out.ready.poke(true.B)
      dut.io.out.valid.expect(true.B)
      dut.io.ifuD1.ready.expect(true.B)
      dut.clock.step()
      dut.io.ifuD1.valid.poke(false.B)

      dut.io.counts(2).expect(4.U)
      dut.io.out.bits.entries(0).parent.key.instructionId.expect(74.U)
      dut.io.out.bits.entries(3).parent.key.instructionId.expect(77.U)
    }
  }

  test("keeps four STID banks isolated across a targeted hard flush") {
    val oooP = OooParams(instructionDecodeWidth = 4)
    simulate(new OooIfuRawIngress(ifuP, oooP, depthPerStid = 8)) { dut =>
      clear(dut)
      enqueue(dut, stid = 0, firstId = 30, epoch = 3)
      enqueue(dut, stid = 3, firstId = 40, epoch = 9)

      dut.io.selectStid.poke(3.U)
      dut.io.out.ready.poke(true.B)
      dut.io.flush.valid.poke(true.B)
      dut.io.flush.threadId.poke(0.U)
      dut.io.flush.scope.poke(IfuPruneScope.KillAllThreadState)
      dut.io.flush.newEpoch.poke(4.U)
      dut.io.out.valid.expect(false.B)
      dut.clock.step()
      dut.io.flush.valid.poke(false.B)

      dut.io.counts(0).expect(0.U)
      dut.io.counts(3).expect(4.U)
      dut.io.eligibleMask.expect("b1000".U)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.epoch.expect(9.U)
      dut.io.out.bits.entries(0).parent.key.instructionId.expect(40.U)
    }
  }

  test("prunes the trigger and younger raw rows without disturbing the older prefix") {
    val oooP = OooParams(instructionDecodeWidth = 4)
    simulate(new OooIfuRawIngress(ifuP, oooP, depthPerStid = 8)) { dut =>
      clear(dut)
      enqueue(dut, stid = 1, firstId = 60, epoch = 5)

      dut.io.flush.valid.poke(true.B)
      dut.io.flush.threadId.poke(1.U)
      dut.io.flush.scope.poke(IfuPruneScope.KillTriggerAndYounger)
      dut.io.flush.oldEpoch.poke(5.U)
      dut.io.flush.fetchSeq.poke((0x4000 + 61).U)
      dut.io.flush.transactionId.poke((0x2000 + 61).U)
      dut.io.flush.newEpoch.poke(6.U)
      dut.clock.step()
      dut.io.flush.valid.poke(false.B)

      dut.io.counts(1).expect(1.U)
      dut.io.selectStid.poke(1.U)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.validMask.expect(1.U)
      dut.io.out.bits.entries(0).parent.key.instructionId.expect(60.U)
      dut.io.out.bits.entries(0).parent.key.epoch.expect(5.U)
    }
  }

  test("fences one STID without mutating its raw rows while another STID advances") {
    val oooP = OooParams(instructionDecodeWidth = 4)
    simulate(new OooIfuRawIngress(ifuP, oooP, depthPerStid = 8)) { dut =>
      clear(dut)
      enqueue(dut, stid = 1, firstId = 80, epoch = 4)
      enqueue(dut, stid = 2, firstId = 90, epoch = 6)

      dut.io.fence(1).poke(true.B)
      dut.io.selectStid.poke(1.U)
      dut.io.out.valid.expect(false.B)
      dut.io.eligibleMask.expect("b0100".U)
      pokeGroup(dut, stid = 1, firstId = 84, epoch = 4)
      dut.io.ifuD1.ready.expect(false.B)
      dut.io.ifuD1.valid.poke(false.B)

      dut.io.selectStid.poke(2.U)
      dut.io.out.ready.poke(true.B)
      dut.io.out.valid.expect(true.B)
      dut.clock.step()
      dut.io.counts(2).expect(0.U)
      dut.io.counts(1).expect(4.U)

      dut.io.fence(1).poke(false.B)
      dut.io.selectStid.poke(1.U)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.entries(0).parent.key.instructionId.expect(80.U)
    }
  }

  test("elaborates and transfers a partial prefix at every production decode width") {
    Seq(2, 4, 6).foreach { width =>
      val oooP = OooParams(instructionDecodeWidth = width)
      simulate(new OooIfuRawIngress(ifuP, oooP, depthPerStid = 8)) { dut =>
        clear(dut)
        enqueue(dut, stid = 0, firstId = 50, lanes = 1)
        dut.io.out.valid.expect(true.B)
        dut.io.out.bits.validMask.expect(1.U)
        dut.io.out.bits.entries(0).parent.key.instructionId.expect(50.U)
      }
    }
  }
}
