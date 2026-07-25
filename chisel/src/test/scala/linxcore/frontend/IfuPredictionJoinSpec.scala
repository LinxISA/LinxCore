package linxcore.frontend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.common.{BoundaryKind, InterfaceParams}
import org.scalatest.funsuite.AnyFunSuite

class IfuPredictionJoinSpec extends AnyFunSuite with ChiselSim {
  private val p = InterfaceParams()
  private val lineBytes = 16

  private def clear(dut: IfuPredictionJoin): Unit = {
    dut.io.allocate.valid.poke(false.B)
    dut.io.allocate.bits.poke(0.U.asTypeOf(dut.io.allocate.bits))
    dut.io.iSide.valid.poke(false.B)
    dut.io.iSide.bits.poke(0.U.asTypeOf(dut.io.iSide.bits))
    dut.io.prediction.valid.poke(false.B)
    dut.io.prediction.bits.poke(0.U.asTypeOf(dut.io.prediction.bits))
    dut.io.flush.poke(0.U.asTypeOf(dut.io.flush))
    dut.io.out.ready.poke(false.B)
  }

  private def allocate(dut: IfuPredictionJoin, transactionId: Int, epoch: Int): Unit = {
    dut.io.allocate.bits.poke(0.U.asTypeOf(dut.io.allocate.bits))
    dut.io.allocate.valid.poke(true.B)
    dut.io.allocate.bits.pc.poke((0x1000 + transactionId * lineBytes).U)
    dut.io.allocate.bits.lineVa.poke((0x1000 + transactionId * lineBytes).U)
    dut.io.allocate.bits.transactionId.poke(transactionId.U)
    dut.io.allocate.bits.identity.peId.poke(1.U)
    dut.io.allocate.bits.identity.threadId.poke(0.U)
    dut.io.allocate.bits.identity.fetchPacketUid.poke(transactionId.U)
    dut.io.allocate.bits.identity.fetchSeq.poke(transactionId.U)
    dut.io.allocate.bits.identity.checkpointId.poke((transactionId & 0x3f).U)
    dut.io.allocate.bits.identity.epoch.poke(epoch.U)
    dut.io.allocate.ready.expect(true.B)
    dut.clock.step()
    dut.io.allocate.valid.poke(false.B)
  }

  private def sendGroup(
      dut: IfuPredictionJoin,
      transactionId: Int,
      epoch: Int,
      basePc: BigInt,
      complete: Boolean): Unit = {
    dut.io.iSide.bits.poke(0.U.asTypeOf(dut.io.iSide.bits))
    dut.io.iSide.valid.poke(true.B)
    dut.io.iSide.bits.validMask.poke("b1111".U)
    dut.io.iSide.bits.transactionComplete.poke(complete.B)
    for (lane <- 0 until p.fetchWidth) {
      val entry = dut.io.iSide.bits.entries(lane)
      entry.pc.poke((basePc + lane * 2).U)
      entry.insn.poke((0x10 + lane).U)
      entry.lenBytes.poke(2.U)
      entry.identity.peId.poke(1.U)
      entry.identity.threadId.poke(0.U)
      entry.identity.fetchPacketUid.poke(transactionId.U)
      entry.identity.fetchSeq.poke(transactionId.U)
      entry.identity.fetchSlot.poke(lane.U)
      entry.identity.checkpointId.poke((transactionId & 0x3f).U)
      entry.identity.epoch.poke(epoch.U)
    }
    dut.io.iSide.ready.expect(true.B)
    dut.clock.step()
    dut.io.iSide.valid.poke(false.B)
  }

  private def sendPrediction(
      dut: IfuPredictionJoin,
      transactionId: Int,
      epoch: Int,
      correction: Boolean,
      finalResponse: Boolean,
      target: BigInt): Unit = {
    dut.io.prediction.bits.poke(0.U.asTypeOf(dut.io.prediction.bits))
    dut.io.prediction.valid.poke(true.B)
    dut.io.prediction.bits.request.transactionId.poke(transactionId.U)
    dut.io.prediction.bits.request.identity.peId.poke(1.U)
    dut.io.prediction.bits.request.identity.threadId.poke(0.U)
    dut.io.prediction.bits.request.identity.fetchPacketUid.poke(transactionId.U)
    dut.io.prediction.bits.request.identity.fetchSeq.poke(transactionId.U)
    dut.io.prediction.bits.request.identity.checkpointId.poke((transactionId & 0x3f).U)
    dut.io.prediction.bits.request.identity.epoch.poke(epoch.U)
    dut.io.prediction.bits.prediction.valid.poke(true.B)
    dut.io.prediction.bits.prediction.predictionTag.poke((0x80 + transactionId).U)
    dut.io.prediction.bits.prediction.taken.poke(true.B)
    dut.io.prediction.bits.prediction.branchPc.poke((0x1000 + transactionId * lineBytes + 6).U)
    dut.io.prediction.bits.prediction.target.poke(target.U)
    dut.io.prediction.bits.prediction.fallthroughPc.poke((0x1000 + transactionId * lineBytes + 8).U)
    dut.io.prediction.bits.prediction.kind.poke(BoundaryKind.Cond)
    dut.io.prediction.bits.prediction.provider.poke(PredictionProvider.Static)
    dut.io.prediction.bits.prediction.stage.poke(BSideStage.BF4)
    dut.io.prediction.bits.prediction.checkpointId.poke((transactionId & 0x3f).U)
    dut.io.prediction.bits.prediction.epoch.poke(epoch.U)
    dut.io.prediction.bits.correction.poke(correction.B)
    dut.io.prediction.bits.finalResponse.poke(finalResponse.B)
    dut.io.prediction.ready.expect(true.B)
    dut.clock.step()
    dut.io.prediction.valid.poke(false.B)
  }

  test("retains multiple I-F4 groups and stamps the final prediction into every lane") {
    simulate(new IfuPredictionJoin(p, lineBytes, entries = 4, maxGroupsPerTransaction = 4)) { dut =>
      clear(dut)
      allocate(dut, transactionId = 1, epoch = 0)
      sendGroup(dut, transactionId = 1, epoch = 0, basePc = 0x1010, complete = false)
      sendGroup(dut, transactionId = 1, epoch = 0, basePc = 0x1018, complete = true)
      sendPrediction(
        dut,
        transactionId = 1,
        epoch = 0,
        correction = false,
        finalResponse = true,
        target = 0x800)

      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.transactionComplete.expect(false.B)
      dut.io.out.bits.entries(0).pc.expect(0x1010.U)
      for (lane <- 0 until p.fetchWidth) {
        dut.io.out.bits.entries(lane).prediction.target.expect(0x800.U)
        dut.io.out.bits.entries(lane).prediction.stage.expect(BSideStage.BF4)
      }
      dut.io.out.ready.poke(true.B)
      dut.clock.step()

      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.transactionComplete.expect(true.B)
      dut.io.out.bits.entries(0).pc.expect(0x1018.U)
      dut.clock.step()
      dut.io.out.valid.expect(false.B)
      dut.io.count.expect(0.U)
    }
  }

  test("waits for canonical correction and prunes a younger completed transaction") {
    simulate(new IfuPredictionJoin(p, lineBytes, entries = 4, maxGroupsPerTransaction = 4)) { dut =>
      clear(dut)
      allocate(dut, transactionId = 10, epoch = 0)
      allocate(dut, transactionId = 11, epoch = 0)
      sendPrediction(
        dut,
        transactionId = 10,
        epoch = 0,
        correction = true,
        finalResponse = true,
        target = 0x900)
      sendPrediction(
        dut,
        transactionId = 11,
        epoch = 0,
        correction = false,
        finalResponse = true,
        target = 0xa00)
      sendGroup(dut, transactionId = 10, epoch = 0, basePc = 0x10a0, complete = true)
      sendGroup(dut, transactionId = 11, epoch = 0, basePc = 0x10b0, complete = true)

      dut.io.out.valid.expect(false.B)
      dut.io.headWaitingForCorrection.expect(true.B)

      dut.io.flush.valid.poke(true.B)
      dut.io.flush.threadId.poke(0.U)
      dut.io.flush.transactionId.poke(10.U)
      dut.io.flush.fetchSeq.poke(10.U)
      dut.io.flush.oldEpoch.poke(0.U)
      dut.io.flush.newEpoch.poke(3.U)
      dut.io.flush.scope.poke(IfuPruneScope.PreserveTriggerKillYounger)
      dut.clock.step()
      dut.io.flush.valid.poke(false.B)

      dut.io.count.expect(1.U)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.entries(0).identity.epoch.expect(3.U)
      dut.io.out.bits.entries(0).prediction.epoch.expect(3.U)
      dut.io.out.bits.entries(0).prediction.target.expect(0x900.U)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()
      dut.io.count.expect(0.U)
      dut.io.out.valid.expect(false.B)
    }
  }

  test("retires a transaction that fills every group slot without wrapping emit index") {
    simulate(new IfuPredictionJoin(p, lineBytes, entries = 4, maxGroupsPerTransaction = 8)) { dut =>
      clear(dut)
      allocate(dut, transactionId = 2, epoch = 0)
      for (group <- 0 until 8) {
        sendGroup(
          dut,
          transactionId = 2,
          epoch = 0,
          basePc = 0x2000 + group * 8,
          complete = group == 7)
      }
      sendPrediction(
        dut,
        transactionId = 2,
        epoch = 0,
        correction = false,
        finalResponse = true,
        target = 0x2800)

      dut.io.out.ready.poke(true.B)
      for (group <- 0 until 8) {
        dut.io.out.valid.expect(true.B)
        dut.io.out.bits.entries(0).pc.expect((0x2000 + group * 8).U)
        dut.clock.step()
      }
      dut.io.out.valid.expect(false.B)
      dut.io.count.expect(0.U)
    }
  }
}
