package linxcore.frontend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import circt.stage.ChiselStage
import linxcore.common.{BoundaryKind, InterfaceParams}
import org.scalatest.funsuite.AnyFunSuite

class BSidePredictionPipelineSpec extends AnyFunSuite with ChiselSim {
  private val p = InterfaceParams()
  private val lineBytes = 16

  private def module =
    new BSidePredictionPipeline(
      p = p,
      lineBytes = lineBytes,
      threadCount = 1,
      boundaryEntries = 8,
      responseEntries = 8,
      trainingEntries = 4,
      nanoEntries = 8,
      ubtbEntries = 8,
      pbtbEntries = 8,
      bimEntries = 8,
      tageEntries = 8,
      ibtbEntries = 8,
      loopEntries = 8,
      rasDepth = 8)

  private def clear(dut: BSidePredictionPipeline): Unit = {
    dut.io.request.valid.poke(false.B)
    dut.io.request.bits.poke(0.U.asTypeOf(dut.io.request.bits))
    dut.io.boundary.valid.poke(false.B)
    dut.io.boundary.bits.poke(0.U.asTypeOf(dut.io.boundary.bits))
    dut.io.resolve.valid.poke(false.B)
    dut.io.resolve.bits.poke(0.U.asTypeOf(dut.io.resolve.bits))
    dut.io.response.ready.poke(false.B)
    dut.io.innerFlush.ready.poke(true.B)
    dut.io.prune.poke(0.U.asTypeOf(dut.io.prune))
  }

  private def sendBoundary(
      dut: BSidePredictionPipeline,
      transactionId: Int,
      requestPc: BigInt,
      hasBoundary: Boolean,
      branchPc: BigInt = 0,
      target: BigInt = 0,
      fallthroughPc: BigInt = 0,
      kind: BoundaryKind.Type = BoundaryKind.Fall,
      staticTaken: Boolean = false): Unit = {
    dut.io.boundary.bits.poke(0.U.asTypeOf(dut.io.boundary.bits))
    dut.io.boundary.valid.poke(true.B)
    dut.io.boundary.bits.valid.poke(hasBoundary.B)
    dut.io.boundary.bits.peId.poke(1.U)
    dut.io.boundary.bits.transactionId.poke(transactionId.U)
    dut.io.boundary.bits.threadId.poke(0.U)
    dut.io.boundary.bits.fetchPacketUid.poke(transactionId.U)
    dut.io.boundary.bits.fetchSeq.poke(transactionId.U)
    dut.io.boundary.bits.epoch.poke(0.U)
    dut.io.boundary.bits.checkpointId.poke((transactionId & 0x3f).U)
    dut.io.boundary.bits.branchPc.poke(branchPc.U)
    dut.io.boundary.bits.target.poke(target.U)
    val completedFallthrough =
      if (!hasBoundary && fallthroughPc == 0)
        (requestPc & ~BigInt(lineBytes - 1)) + lineBytes
      else fallthroughPc
    dut.io.boundary.bits.fallthroughPc.poke(completedFallthrough.U)
    dut.io.boundary.bits.kind.poke(kind)
    dut.io.boundary.bits.staticTaken.poke(staticTaken.B)
    dut.io.boundary.ready.expect(true.B)
    dut.clock.step()
    dut.io.boundary.valid.poke(false.B)
  }

  private def sendRequest(
      dut: BSidePredictionPipeline,
      transactionId: Int,
      pc: BigInt): Unit = {
    dut.io.request.bits.poke(0.U.asTypeOf(dut.io.request.bits))
    dut.io.request.valid.poke(true.B)
    dut.io.request.bits.pc.poke(pc.U)
    dut.io.request.bits.lineVa.poke((pc & ~BigInt(lineBytes - 1)).U)
    dut.io.request.bits.transactionId.poke(transactionId.U)
    dut.io.request.bits.identity.peId.poke(1.U)
    dut.io.request.bits.identity.threadId.poke(0.U)
    dut.io.request.bits.identity.fetchPacketUid.poke(transactionId.U)
    dut.io.request.bits.identity.fetchSeq.poke(transactionId.U)
    dut.io.request.bits.identity.checkpointId.poke((transactionId & 0x3f).U)
    dut.io.request.bits.identity.epoch.poke(0.U)
    dut.io.request.ready.expect(true.B)
    dut.clock.step()
    dut.io.request.valid.poke(false.B)
  }

  private def pokeResolve(
      dut: BSidePredictionPipeline,
      transactionId: Int,
      predictionTag: Int,
      requestPc: BigInt,
      branchPc: BigInt,
      target: BigInt,
      fallthroughPc: BigInt,
      kind: BoundaryKind.Type,
      taken: Boolean): Unit = {
    dut.io.resolve.bits.poke(0.U.asTypeOf(dut.io.resolve.bits))
    dut.io.resolve.valid.poke(true.B)
    dut.io.resolve.bits.transactionId.poke(transactionId.U)
    dut.io.resolve.bits.predictionTag.poke(predictionTag.U)
    dut.io.resolve.bits.threadId.poke(0.U)
    dut.io.resolve.bits.epoch.poke(0.U)
    dut.io.resolve.bits.checkpointId.poke((transactionId & 0x3f).U)
    dut.io.resolve.bits.requestPc.poke(requestPc.U)
    dut.io.resolve.bits.branchPc.poke(branchPc.U)
    dut.io.resolve.bits.target.poke(target.U)
    dut.io.resolve.bits.fallthroughPc.poke(fallthroughPc.U)
    dut.io.resolve.bits.kind.poke(kind)
    dut.io.resolve.bits.taken.poke(taken.B)
    dut.io.resolve.ready.expect(true.B)
    dut.clock.step()
    dut.io.resolve.valid.poke(false.B)
  }

  private def waitForResponse(dut: BSidePredictionPipeline, limit: Int = 24): Unit = {
    var cycles = 0
    while (!dut.io.response.valid.peek().litToBoolean && cycles < limit) {
      dut.clock.step()
      cycles += 1
    }
    assert(dut.io.response.valid.peek().litToBoolean, s"prediction response missing after $cycles cycles")
  }

  test("B-F4 static prediction is the final correction and restarts at the exact target") {
    simulate(module) { dut =>
      clear(dut)
      sendBoundary(
        dut,
        transactionId = 1,
        requestPc = 0x1000,
        hasBoundary = true,
        branchPc = 0x1004,
        target = 0x800,
        fallthroughPc = 0x1006,
        kind = BoundaryKind.Cond,
        staticTaken = true)
      sendRequest(dut, transactionId = 1, pc = 0x1000)

      waitForResponse(dut)
      dut.io.response.bits.finalResponse.expect(true.B)
      dut.io.response.bits.correction.expect(true.B)
      dut.io.response.bits.prediction.stage.expect(BSideStage.BF4)
      dut.io.response.bits.prediction.provider.expect(PredictionProvider.Static)
      dut.io.response.bits.prediction.predictionTag.expect(0.U)
      dut.io.response.bits.prediction.taken.expect(true.B)
      dut.io.response.bits.prediction.branchPc.expect(0x1004.U)
      dut.io.response.bits.prediction.target.expect(0x800.U)
      dut.io.response.bits.prediction.fallthroughPc.expect(0x1006.U)
      dut.io.response.bits.prediction.confidence.expect(1.U)
      dut.io.response.bits.prediction.epoch.expect(1.U)

      dut.io.response.ready.poke(true.B)
      dut.io.innerFlush.valid.expect(true.B)
      dut.io.innerFlush.bits.reason.expect(IfuInnerFlushReason.PredictionCorrection)
      dut.io.innerFlush.bits.transactionId.expect(1.U)
      dut.io.innerFlush.bits.fetchSeq.expect(1.U)
      dut.io.innerFlush.bits.oldEpoch.expect(0.U)
      dut.io.innerFlush.bits.newEpoch.expect(1.U)
      dut.io.innerFlush.bits.scope.expect(IfuPruneScope.PreserveTriggerKillYounger)
      dut.io.innerFlush.bits.restartPc.expect(0x800.U)
      dut.clock.step()
    }
  }

  test("B-F4 waits for an exact no-boundary event and preserves the sequential provider") {
    simulate(module) { dut =>
      clear(dut)
      sendRequest(dut, transactionId = 2, pc = 0x2000)
      dut.clock.step(8)
      dut.io.stageValid.expect("b10000".U)
      dut.io.response.valid.expect(false.B)

      sendBoundary(
        dut,
        transactionId = 2,
        requestPc = 0x2000,
        hasBoundary = false)
      waitForResponse(dut)
      dut.io.response.bits.finalResponse.expect(true.B)
      dut.io.response.bits.correction.expect(false.B)
      dut.io.response.bits.prediction.stage.expect(BSideStage.BF4)
      dut.io.response.bits.prediction.provider.expect(PredictionProvider.Sequential)
      dut.io.response.bits.prediction.taken.expect(false.B)
      dut.io.response.bits.prediction.fallthroughPc.expect(0x2010.U)
    }
  }

  test("trained B-F0 correction is followed by a non-correcting B-F4 confirmation") {
    simulate(module) { dut =>
      clear(dut)
      pokeResolve(
        dut,
        transactionId = 40,
        predictionTag = 40,
        requestPc = 0x3000,
        branchPc = 0x3004,
        target = 0x900,
        fallthroughPc = 0x3006,
        kind = BoundaryKind.Cond,
        taken = true)
      dut.clock.step()

      sendBoundary(
        dut,
        transactionId = 3,
        requestPc = 0x3000,
        hasBoundary = true,
        branchPc = 0x3004,
        target = 0x900,
        fallthroughPc = 0x3006,
        kind = BoundaryKind.Cond,
        staticTaken = true)
      sendRequest(dut, transactionId = 3, pc = 0x3000)

      waitForResponse(dut)
      dut.io.response.bits.finalResponse.expect(false.B)
      dut.io.response.bits.correction.expect(true.B)
      dut.io.response.bits.prediction.stage.expect(BSideStage.BF0)
      dut.io.response.bits.prediction.provider.expect(PredictionProvider.NanoBtb)
      dut.io.response.bits.prediction.epoch.expect(1.U)
      dut.io.response.ready.poke(true.B)
      dut.io.innerFlush.valid.expect(true.B)
      dut.io.innerFlush.bits.restartPc.expect(0x900.U)
      dut.clock.step()
      dut.io.response.ready.poke(false.B)

      waitForResponse(dut)
      dut.io.response.bits.finalResponse.expect(true.B)
      dut.io.response.bits.correction.expect(false.B)
      dut.io.response.bits.prediction.stage.expect(BSideStage.BF4)
      dut.io.response.bits.prediction.provider.expect(PredictionProvider.LongTage)
      dut.io.response.bits.prediction.epoch.expect(1.U)
      dut.io.innerFlush.valid.expect(false.B)
    }
  }

  test("prediction response remains stable under backpressure and correction pairs atomically with flush") {
    simulate(module) { dut =>
      clear(dut)
      sendBoundary(
        dut,
        transactionId = 4,
        requestPc = 0x4000,
        hasBoundary = true,
        branchPc = 0x4002,
        target = 0xa00,
        fallthroughPc = 0x4004,
        kind = BoundaryKind.Direct,
        staticTaken = true)
      sendRequest(dut, transactionId = 4, pc = 0x4000)
      waitForResponse(dut)

      val tag = dut.io.response.bits.prediction.predictionTag.peek().litValue
      val target = dut.io.response.bits.prediction.target.peek().litValue
      dut.clock.step(3)
      assert(dut.io.response.bits.prediction.predictionTag.peek().litValue == tag)
      assert(dut.io.response.bits.prediction.target.peek().litValue == target)
      dut.io.innerFlush.valid.expect(false.B)

      dut.io.response.ready.poke(true.B)
      dut.io.response.valid.expect(true.B)
      dut.io.innerFlush.valid.expect(true.B)
      dut.clock.step()
      dut.io.response.valid.expect(false.B)
    }
  }

  test("not-taken correction restarts at the instruction fallthrough rather than the next cache line") {
    simulate(module) { dut =>
      clear(dut)
      sendBoundary(
        dut,
        transactionId = 5,
        requestPc = 0x4800,
        hasBoundary = true,
        branchPc = 0x4804,
        target = 0xc00,
        fallthroughPc = 0x4806,
        kind = BoundaryKind.Cond,
        staticTaken = false)
      sendRequest(dut, transactionId = 5, pc = 0x4800)
      waitForResponse(dut)
      dut.io.response.bits.correction.expect(true.B)
      dut.io.response.bits.prediction.taken.expect(false.B)
      dut.io.response.ready.poke(true.B)
      dut.io.innerFlush.valid.expect(true.B)
      dut.io.innerFlush.bits.restartPc.expect(0x4806.U)
    }
  }

  test("B-F4 uses a nonempty RAS as the final return target authority") {
    simulate(module) { dut =>
      clear(dut)
      pokeResolve(
        dut,
        transactionId = 60,
        predictionTag = 60,
        requestPc = 0x7000,
        branchPc = 0x7002,
        target = 0x7100,
        fallthroughPc = 0x7004,
        kind = BoundaryKind.Call,
        taken = true)
      dut.clock.step()

      sendBoundary(
        dut,
        transactionId = 6,
        requestPc = 0x7100,
        hasBoundary = true,
        branchPc = 0x7102,
        target = 0,
        fallthroughPc = 0x7104,
        kind = BoundaryKind.Ret,
        staticTaken = true)
      sendRequest(dut, transactionId = 6, pc = 0x7100)
      waitForResponse(dut)
      dut.io.response.bits.finalResponse.expect(true.B)
      dut.io.response.bits.prediction.provider.expect(PredictionProvider.FinalRas)
      dut.io.response.bits.prediction.target.expect(0x7004.U)
    }
  }

  test("B-F4 gives the trained IBTB final authority for an indirect branch") {
    simulate(module) { dut =>
      clear(dut)
      pokeResolve(
        dut,
        transactionId = 70,
        predictionTag = 70,
        requestPc = 0x8000,
        branchPc = 0x8004,
        target = 0xd00,
        fallthroughPc = 0x8006,
        kind = BoundaryKind.Ind,
        taken = true)
      dut.clock.step()

      sendBoundary(
        dut,
        transactionId = 7,
        requestPc = 0x8000,
        hasBoundary = true,
        branchPc = 0x8004,
        target = 0,
        fallthroughPc = 0x8006,
        kind = BoundaryKind.Ind,
        staticTaken = true)
      sendRequest(dut, transactionId = 7, pc = 0x8000)

      waitForResponse(dut)
      dut.io.response.bits.prediction.provider.expect(PredictionProvider.NanoBtb)
      dut.io.response.ready.poke(true.B)
      dut.clock.step()
      dut.io.response.ready.poke(false.B)

      waitForResponse(dut)
      dut.io.response.bits.finalResponse.expect(true.B)
      dut.io.response.bits.correction.expect(false.B)
      dut.io.response.bits.prediction.provider.expect(PredictionProvider.IndirectBtb)
      dut.io.response.bits.prediction.target.expect(0xd00.U)
    }
  }

  test("B-SIDE accepts one fetch line per cycle and returns four consecutive finals") {
    simulate(module) { dut =>
      clear(dut)
      for (tx <- 0 until 4) {
        sendBoundary(
          dut,
          transactionId = tx,
          requestPc = 0x5000 + tx * lineBytes,
          hasBoundary = false)
      }

      dut.io.response.ready.poke(true.B)
      for (tx <- 0 until 4) {
        dut.io.request.bits.poke(0.U.asTypeOf(dut.io.request.bits))
        dut.io.request.valid.poke(true.B)
        dut.io.request.bits.pc.poke((0x5000 + tx * lineBytes).U)
        dut.io.request.bits.lineVa.poke((0x5000 + tx * lineBytes).U)
        dut.io.request.bits.transactionId.poke(tx.U)
        dut.io.request.bits.identity.peId.poke(1.U)
        dut.io.request.bits.identity.threadId.poke(0.U)
        dut.io.request.bits.identity.fetchPacketUid.poke(tx.U)
        dut.io.request.bits.identity.fetchSeq.poke(tx.U)
        dut.io.request.bits.identity.checkpointId.poke(tx.U)
        dut.io.request.ready.expect(true.B)
        dut.clock.step()
      }
      dut.io.request.valid.poke(false.B)

      var cycle = 0
      var finalCycles = Vector.empty[Int]
      while (finalCycles.size < 4 && cycle < 24) {
        if (dut.io.response.valid.peek().litToBoolean) {
          dut.io.response.bits.finalResponse.expect(true.B)
          dut.io.response.bits.correction.expect(false.B)
          finalCycles :+= cycle
        }
        dut.clock.step()
        cycle += 1
      }
      assert(finalCycles.size == 4, s"expected four final responses, got $finalCycles")
      assert(finalCycles.sliding(2).forall(pair => pair(1) - pair(0) == 1), finalCycles.toString)
    }
  }

  test("prediction correction prune preserves its producer and cancels younger B-SIDE work") {
    simulate(module) { dut =>
      clear(dut)
      sendBoundary(dut, transactionId = 30, requestPc = 0x9000, hasBoundary = false)
      sendBoundary(dut, transactionId = 31, requestPc = 0x9010, hasBoundary = false)
      sendRequest(dut, transactionId = 30, pc = 0x9000)
      sendRequest(dut, transactionId = 31, pc = 0x9010)

      dut.io.prune.valid.poke(true.B)
      dut.io.prune.threadId.poke(0.U)
      dut.io.prune.transactionId.poke(30.U)
      dut.io.prune.fetchSeq.poke(30.U)
      dut.io.prune.oldEpoch.poke(0.U)
      dut.io.prune.newEpoch.poke(1.U)
      dut.io.prune.scope.poke(IfuPruneScope.PreserveTriggerKillYounger)
      dut.clock.step()
      dut.io.prune.valid.poke(false.B)

      waitForResponse(dut)
      dut.io.response.bits.request.transactionId.expect(30.U)
      dut.io.response.bits.finalResponse.expect(true.B)
      dut.io.response.ready.poke(true.B)
      dut.clock.step()

      for (_ <- 0 until 12) {
        dut.io.response.valid.expect(false.B)
        dut.clock.step()
      }
      dut.io.stageValid.expect(0.U)
    }
  }

  test("duplicate retained training is detected by prediction identity") {
    simulate(module) { dut =>
      clear(dut)
      pokeResolve(
        dut,
        transactionId = 50,
        predictionTag = 7,
        requestPc = 0x6000,
        branchPc = 0x6004,
        target = 0xb00,
        fallthroughPc = 0x6006,
        kind = BoundaryKind.Cond,
        taken = true)
      dut.clock.step()

      pokeResolve(
        dut,
        transactionId = 50,
        predictionTag = 7,
        requestPc = 0x6000,
        branchPc = 0x6004,
        target = 0xb00,
        fallthroughPc = 0x6006,
        kind = BoundaryKind.Cond,
        taken = true)
      dut.io.duplicateTraining.expect(true.B)
      dut.clock.step()
      dut.io.duplicateTraining.expect(false.B)
    }
  }

  test("B-SIDE elaborates with five resident stages and retained correction ports") {
    val sv = ChiselStage.emitSystemVerilog(module)
    assert(sv.contains("module BSidePredictionPipeline"))
    assert(sv.contains("io_stageValid"))
    assert(sv.contains("io_response_valid"))
    assert(sv.contains("io_innerFlush_valid"))
    assert(sv.contains("io_boundaryCollision"))
  }
}
