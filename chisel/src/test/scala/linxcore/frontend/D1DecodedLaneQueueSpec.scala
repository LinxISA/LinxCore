package linxcore.frontend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.common.InterfaceParams
import org.scalatest.funsuite.AnyFunSuite

class D1DecodedLaneQueueSpec extends AnyFunSuite with ChiselSim {
  private val p = InterfaceParams()

  private def clear(dut: D1DecodedLaneQueue): Unit = {
    dut.io.in.valid.poke(false.B)
    dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
    dut.io.out.ready.poke(false.B)
    dut.io.flush.poke(0.U.asTypeOf(dut.io.flush))
  }

  private def presentGroup(
      dut: D1DecodedLaneQueue,
      firstUid: Int,
      firstFetchSeq: Int,
      transactionBase: Int,
      epoch: Int = 2,
      validMask: Int = 0xf): Unit = {
    dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
    dut.io.in.valid.poke(true.B)
    dut.io.in.bits.validMask.poke(validMask.U)
    for (lane <- 0 until p.decodeWidth) {
      val row = dut.io.in.bits.entries(lane)
      row.valid.poke(((validMask >> lane) & 1).B)
      row.threadId.poke(0.U)
      row.pc.poke((0x2000 + firstUid * 2 + lane * 2).U)
      row.opcode.poke((0x100 + lane).U)
      row.uid.uid.poke((firstUid + lane).U)
      row.uid.fetchSlot.poke(lane.U)
      row.prediction.valid.poke(true.B)
      row.prediction.predictionTag.poke((0x400 + firstUid + lane).U)
      row.prediction.transactionId.poke((transactionBase + lane).U)
      row.prediction.fetchPacketUid.poke((0x800 + firstUid).U)
      row.prediction.fetchSeq.poke((firstFetchSeq + lane).U)
      row.prediction.target.poke((0x3000 + lane * 8).U)
      row.prediction.checkpointId.poke((7 + lane).U)
      row.prediction.epoch.poke(epoch.U)
      dut.io.in.bits.meta(lane).valid.poke(true.B)
      dut.io.in.bits.meta(lane).opcode.poke((0x100 + lane).U)
    }
    dut.io.in.bits.blockBoundaryMask.poke("b0001".U)
    dut.io.in.bits.blockStopMask.poke("b1000".U)
    dut.io.in.bits.loadMask.poke("b0010".U)
    dut.io.in.bits.storeMask.poke("b0100".U)
  }

  private def accept(dut: D1DecodedLaneQueue): Unit = {
    dut.io.in.ready.expect(true.B)
    dut.clock.step()
    dut.io.in.valid.poke(false.B)
  }

  test("accepts a four-wide group atomically and drains lanes in program order") {
    simulate(new D1DecodedLaneQueue(p, depth = 8)) { dut =>
      clear(dut)
      presentGroup(dut, firstUid = 10, firstFetchSeq = 20, transactionBase = 100)
      dut.io.inLaneCount.expect(4.U)
      accept(dut)

      dut.io.count.expect(4.U)
      dut.io.out.ready.poke(true.B)
      for (lane <- 0 until p.decodeWidth) {
        dut.io.out.valid.expect(true.B)
        dut.io.out.bits.validMask.expect((1 << lane).U)
        dut.io.out.bits.entries(lane).uid.uid.expect((10 + lane).U)
        dut.io.out.bits.entries(lane).prediction.transactionId.expect((100 + lane).U)
        dut.io.headLane.expect(lane.U)
        dut.io.nextSameGroupValid.expect((lane != p.decodeWidth - 1).B)
        if (lane != p.decodeWidth - 1) {
          dut.io.nextSameGroupUop.uid.uid.expect((11 + lane).U)
        }
        dut.io.out.bits.blockBoundaryMask.expect((if (lane == 0) 1 else 0).U)
        dut.io.out.bits.blockStopMask.expect((if (lane == 3) 8 else 0).U)
        dut.io.out.bits.loadMask.expect((if (lane == 1) 2 else 0).U)
        dut.io.out.bits.storeMask.expect((if (lane == 2) 4 else 0).U)
        dut.clock.step()
      }
      dut.io.out.valid.expect(false.B)
      dut.io.count.expect(0.U)
    }
  }

  test("holds the head stable under backend backpressure") {
    simulate(new D1DecodedLaneQueue(p, depth = 8)) { dut =>
      clear(dut)
      presentGroup(dut, firstUid = 40, firstFetchSeq = 50, transactionBase = 200)
      accept(dut)

      dut.io.out.ready.poke(false.B)
      for (_ <- 0 until 3) {
        dut.io.out.valid.expect(true.B)
        dut.io.out.bits.entries(0).uid.uid.expect(40.U)
        dut.io.nextSameGroupUop.uid.uid.expect(41.U)
        dut.io.count.expect(4.U)
        dut.clock.step()
      }
    }
  }

  test("uses registered capacity and restores four-lane credit after dequeue") {
    simulate(new D1DecodedLaneQueue(p, depth = 4)) { dut =>
      clear(dut)
      presentGroup(dut, firstUid = 60, firstFetchSeq = 70, transactionBase = 300)
      accept(dut)

      presentGroup(dut, firstUid = 80, firstFetchSeq = 90, transactionBase = 400)
      dut.io.in.ready.expect(false.B)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()
      dut.io.in.ready.expect(false.B)
      for (_ <- 0 until 3) {
        dut.clock.step()
      }
      dut.io.count.expect(0.U)
      dut.io.in.ready.expect(true.B)
    }
  }

  test("precise recovery prunes the trigger and younger lanes") {
    simulate(new D1DecodedLaneQueue(p, depth = 8)) { dut =>
      clear(dut)
      presentGroup(dut, firstUid = 100, firstFetchSeq = 10, transactionBase = 500)
      accept(dut)

      dut.io.flush.valid.poke(true.B)
      dut.io.flush.threadId.poke(0.U)
      dut.io.flush.oldEpoch.poke(2.U)
      dut.io.flush.fetchSeq.poke(12.U)
      dut.io.flush.transactionId.poke(502.U)
      dut.io.flush.scope.poke(IfuPruneScope.KillTriggerAndYounger)
      dut.io.out.valid.expect(false.B)
      dut.clock.step()

      dut.io.flush.valid.poke(false.B)
      dut.io.out.ready.poke(true.B)
      dut.io.count.expect(2.U)
      dut.io.out.bits.entries(0).uid.uid.expect(100.U)
      dut.clock.step()
      dut.io.out.bits.entries(1).uid.uid.expect(101.U)
    }
  }

  test("prediction correction preserves and rebases the trigger") {
    simulate(new D1DecodedLaneQueue(p, depth = 8)) { dut =>
      clear(dut)
      presentGroup(dut, firstUid = 120, firstFetchSeq = 20, transactionBase = 600)
      accept(dut)

      dut.io.flush.valid.poke(true.B)
      dut.io.flush.threadId.poke(0.U)
      dut.io.flush.oldEpoch.poke(2.U)
      dut.io.flush.newEpoch.poke(3.U)
      dut.io.flush.fetchSeq.poke(22.U)
      dut.io.flush.transactionId.poke(602.U)
      dut.io.flush.scope.poke(IfuPruneScope.PreserveTriggerKillYounger)
      dut.io.rebasedTrigger.expect(true.B)
      dut.clock.step()

      dut.io.flush.valid.poke(false.B)
      dut.io.out.ready.poke(true.B)
      dut.io.count.expect(3.U)
      dut.io.out.bits.entries(0).uid.uid.expect(120.U)
      dut.clock.step()
      dut.io.out.bits.entries(1).uid.uid.expect(121.U)
      dut.clock.step()
      dut.io.out.bits.entries(2).uid.uid.expect(122.U)
      dut.io.out.bits.entries(2).prediction.epoch.expect(3.U)
    }
  }

  test("rejects sparse groups but preserves invalid-opcode rows") {
    simulate(new D1DecodedLaneQueue(p, depth = 8)) { dut =>
      clear(dut)
      presentGroup(
        dut,
        firstUid = 140,
        firstFetchSeq = 30,
        transactionBase = 700,
        validMask = 0x5)
      dut.io.in.ready.expect(false.B)
      dut.io.rejectedMalformed.expect(true.B)

      presentGroup(
        dut,
        firstUid = 150,
        firstFetchSeq = 40,
        transactionBase = 800,
        validMask = 0x1)
      dut.io.in.bits.entries(0).valid.poke(false.B)
      dut.io.in.bits.invalidOpcodeMask.poke(1.U)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.io.out.bits.invalidOpcodeMask.expect(1.U)
    }
  }
}
