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
      taken: Boolean,
      mispredict: Boolean = false,
      epoch: Int = 0): Unit = {
    dut.io.resolve.bits.poke(0.U.asTypeOf(dut.io.resolve.bits))
    dut.io.resolve.valid.poke(true.B)
    dut.io.resolve.bits.peId.poke(1.U)
    dut.io.resolve.bits.transactionId.poke(transactionId.U)
    dut.io.resolve.bits.predictionTag.poke(predictionTag.U)
    dut.io.resolve.bits.threadId.poke(0.U)
    dut.io.resolve.bits.fetchPacketUid.poke(transactionId.U)
    dut.io.resolve.bits.fetchSeq.poke(transactionId.U)
    dut.io.resolve.bits.epoch.poke(epoch.U)
    dut.io.resolve.bits.checkpointId.poke((transactionId & 0x3f).U)
    dut.io.resolve.bits.requestPc.poke(requestPc.U)
    dut.io.resolve.bits.branchPc.poke(branchPc.U)
    dut.io.resolve.bits.target.poke(target.U)
    dut.io.resolve.bits.fallthroughPc.poke(fallthroughPc.U)
    dut.io.resolve.bits.kind.poke(kind)
    dut.io.resolve.bits.taken.poke(taken.B)
    dut.io.resolve.bits.mispredict.poke(mispredict.B)
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

  private def waitForFinal(dut: BSidePredictionPipeline, limit: Int = 32): Unit = {
    dut.io.response.ready.poke(true.B)
    var cycles = 0
    var finalSeen = false
    while (!finalSeen && cycles < limit) {
      finalSeen =
        dut.io.response.valid.peek().litToBoolean &&
          dut.io.response.bits.finalResponse.peek().litToBoolean
      if (!finalSeen) {
        dut.clock.step()
        cycles += 1
      }
    }
    assert(finalSeen, s"final prediction response missing after $cycles cycles")
  }

  private def applyCanonicalCorrection(
      dut: BSidePredictionPipeline,
      transactionId: Int,
      predictionTag: Int,
      taken: Boolean,
      kind: BoundaryKind.Type,
      rasPushAddress: BigInt = 0): Unit = {
    dut.io.prune.poke(0.U.asTypeOf(dut.io.prune))
    dut.io.prune.valid.poke(true.B)
    dut.io.prune.peId.poke(1.U)
    dut.io.prune.threadId.poke(0.U)
    dut.io.prune.transactionId.poke(transactionId.U)
    dut.io.prune.fetchPacketUid.poke(transactionId.U)
    dut.io.prune.fetchSeq.poke(transactionId.U)
    dut.io.prune.oldEpoch.poke(0.U)
    dut.io.prune.newEpoch.poke(1.U)
    dut.io.prune.checkpointId.poke((transactionId & 0x3f).U)
    dut.io.prune.reason.poke(IfuInnerFlushReason.PredictionCorrection)
    dut.io.prune.scope.poke(IfuPruneScope.PreserveTriggerKillYounger)
    dut.io.prune.historyKeyValid.poke(true.B)
    dut.io.prune.predictionTag.poke(predictionTag.U)
    dut.io.prune.ghrAction.poke(GhrRecoveryAction.RestoreTrigger)
    dut.io.prune.ghrAppendValid.poke((kind == BoundaryKind.Cond).B)
    dut.io.prune.ghrAppendTaken.poke(taken.B)
    dut.io.prune.rasAction.poke(RasRecoveryAction.RestoreTrigger)
    dut.io.prune.rasUpdate.poke(
      (if (!taken) RasUpdateAction.None
       else if (kind == BoundaryKind.Call || kind == BoundaryKind.ICall) RasUpdateAction.Push
       else if (kind == BoundaryKind.Ret) RasUpdateAction.Pop
       else RasUpdateAction.None))
    dut.io.prune.rasPushAddress.poke(rasPushAddress.U)
    dut.clock.step()
    dut.io.prune.valid.poke(false.B)
  }

  private def trainPrediction(
      dut: BSidePredictionPipeline,
      transactionId: Int,
      requestPc: BigInt,
      branchPc: BigInt,
      target: BigInt,
      fallthroughPc: BigInt,
      kind: BoundaryKind.Type,
      taken: Boolean): Int = {
    sendBoundary(
      dut = dut,
      transactionId = transactionId,
      requestPc = requestPc,
      hasBoundary = true,
      branchPc = branchPc,
      target = target,
      fallthroughPc = fallthroughPc,
      kind = kind,
      staticTaken = taken)
    sendRequest(dut, transactionId, requestPc)
    waitForFinal(dut)
    dut.io.response.bits.correction.expect(true.B)
    val predictionTag = dut.io.response.bits.prediction.predictionTag.peek().litValue.toInt
    dut.clock.step()
    dut.io.response.ready.poke(false.B)
    applyCanonicalCorrection(
      dut,
      transactionId,
      predictionTag,
      taken,
      kind,
      rasPushAddress = fallthroughPc)
    pokeResolve(
      dut,
      transactionId,
      predictionTag,
      requestPc,
      branchPc,
      target,
      fallthroughPc,
      kind,
      taken,
      epoch = 1)
    dut.clock.step()
    predictionTag
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
      dut.io.innerFlush.bits.historyKeyValid.expect(true.B)
      dut.io.innerFlush.bits.predictionTag.expect(0.U)
      dut.io.innerFlush.bits.fetchPacketUid.expect(1.U)
      dut.io.innerFlush.bits.ghrAction.expect(GhrRecoveryAction.RestoreTrigger)
      dut.io.innerFlush.bits.ghrAppendValid.expect(true.B)
      dut.io.innerFlush.bits.ghrAppendTaken.expect(true.B)
      dut.io.innerFlush.bits.rasAction.expect(RasRecoveryAction.RestoreTrigger)
      dut.io.innerFlush.bits.rasUpdate.expect(RasUpdateAction.None)
      dut.clock.step()
      dut.io.speculativeGhr(0).expect(0.U)
      applyCanonicalCorrection(
        dut,
        transactionId = 1,
        predictionTag = 0,
        taken = true,
        kind = BoundaryKind.Cond)
      dut.io.speculativeGhr(0).expect(1.U)
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
      trainPrediction(
        dut,
        transactionId = 40,
        requestPc = 0x3000,
        branchPc = 0x3004,
        target = 0x900,
        fallthroughPc = 0x3006,
        kind = BoundaryKind.Cond,
        taken = true)
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

  test("B-F4 long TAGE direction outranks a conflicting static fallback") {
    simulate(module) { dut =>
      clear(dut)
      trainPrediction(
        dut,
        transactionId = 41,
        requestPc = 0x3400,
        branchPc = 0x3404,
        target = 0x3800,
        fallthroughPc = 0x3406,
        kind = BoundaryKind.Cond,
        taken = false)
      sendBoundary(
        dut,
        transactionId = 8,
        requestPc = 0x3400,
        hasBoundary = true,
        branchPc = 0x3404,
        target = 0x3800,
        fallthroughPc = 0x3406,
        kind = BoundaryKind.Cond,
        staticTaken = true)
      sendRequest(dut, transactionId = 8, pc = 0x3400)

      waitForFinal(dut)
      dut.io.response.bits.prediction.provider.expect(PredictionProvider.LongTage)
      dut.io.response.bits.prediction.taken.expect(false.B)
      dut.io.response.bits.correction.expect(false.B)
    }
  }

  test("non-conditional resolution never pollutes BIM or TAGE direction tables") {
    simulate(module) { dut =>
      clear(dut)
      trainPrediction(
        dut,
        transactionId = 42,
        requestPc = 0x3600,
        branchPc = 0x3604,
        target = 0x3a00,
        fallthroughPc = 0x3606,
        kind = BoundaryKind.Direct,
        taken = true)
      sendBoundary(
        dut,
        transactionId = 9,
        requestPc = 0x3600,
        hasBoundary = true,
        branchPc = 0x3604,
        target = 0x3a00,
        fallthroughPc = 0x3606,
        kind = BoundaryKind.Cond,
        staticTaken = false)
      sendRequest(dut, transactionId = 9, pc = 0x3600)

      waitForFinal(dut)
      dut.io.response.bits.prediction.provider.expect(PredictionProvider.Static)
      dut.io.response.bits.prediction.taken.expect(false.B)
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

  test("canonical prune stalls a surviving correction response for the whole cycle") {
    simulate(module) { dut =>
      clear(dut)
      sendBoundary(
        dut,
        transactionId = 12,
        requestPc = 0x4200,
        hasBoundary = true,
        branchPc = 0x4204,
        target = 0xa80,
        fallthroughPc = 0x4206,
        kind = BoundaryKind.Cond,
        staticTaken = true)
      sendRequest(dut, transactionId = 12, pc = 0x4200)
      waitForResponse(dut)

      dut.io.response.ready.poke(true.B)
      dut.io.prune.valid.poke(true.B)
      dut.io.prune.threadId.poke(0.U)
      dut.io.prune.transactionId.poke(12.U)
      dut.io.prune.fetchSeq.poke(12.U)
      dut.io.prune.oldEpoch.poke(0.U)
      dut.io.prune.scope.poke(IfuPruneScope.PreserveTriggerKillYounger)
      dut.io.response.valid.expect(false.B)
      dut.io.innerFlush.valid.expect(false.B)
      dut.clock.step()
      dut.io.prune.valid.poke(false.B)

      dut.io.response.valid.expect(true.B)
      dut.io.innerFlush.valid.expect(true.B)
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
      trainPrediction(
        dut,
        transactionId = 60,
        requestPc = 0x7000,
        branchPc = 0x7002,
        target = 0x7100,
        fallthroughPc = 0x7004,
        kind = BoundaryKind.Call,
        taken = true)
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

  test("trained return uses the request-owned RAS at B-F1 before B-F4 final") {
    simulate(module) { dut =>
      clear(dut)
      trainPrediction(
        dut,
        transactionId = 61,
        requestPc = 0x7008,
        branchPc = 0x700a,
        target = 0x7100,
        fallthroughPc = 0x700c,
        kind = BoundaryKind.Call,
        taken = true)
      trainPrediction(
        dut,
        transactionId = 62,
        requestPc = 0x7100,
        branchPc = 0x7102,
        target = 0x700c,
        fallthroughPc = 0x7104,
        kind = BoundaryKind.Ret,
        taken = true)
      trainPrediction(
        dut,
        transactionId = 63,
        requestPc = 0x7208,
        branchPc = 0x720a,
        target = 0x7100,
        fallthroughPc = 0x720c,
        kind = BoundaryKind.Call,
        taken = true)

      sendBoundary(
        dut,
        transactionId = 7,
        requestPc = 0x7100,
        hasBoundary = true,
        branchPc = 0x7102,
        target = 0,
        fallthroughPc = 0x7104,
        kind = BoundaryKind.Ret,
        staticTaken = true)
      sendRequest(dut, transactionId = 7, pc = 0x7100)

      waitForResponse(dut)
      dut.io.response.bits.prediction.stage.expect(BSideStage.BF0)
      dut.io.response.bits.prediction.provider.expect(PredictionProvider.NanoBtb)
      val predictionTag = dut.io.response.bits.prediction.predictionTag.peek().litValue.toInt
      dut.io.response.ready.poke(true.B)
      dut.clock.step()
      dut.io.response.ready.poke(false.B)
      applyCanonicalCorrection(
        dut,
        transactionId = 7,
        predictionTag = predictionTag,
        taken = true,
        kind = BoundaryKind.Ret)

      waitForResponse(dut)
      dut.io.response.bits.finalResponse.expect(false.B)
      dut.io.response.bits.correction.expect(true.B)
      dut.io.response.bits.prediction.stage.expect(BSideStage.BF1)
      dut.io.response.bits.prediction.provider.expect(PredictionProvider.FastRas)
      dut.io.response.bits.prediction.target.expect(0x720c.U)
    }
  }

  test("B-F4 gives the trained IBTB final authority for an indirect branch") {
    simulate(module) { dut =>
      clear(dut)
      trainPrediction(
        dut,
        transactionId = 70,
        requestPc = 0x8000,
        branchPc = 0x8004,
        target = 0xd00,
        fallthroughPc = 0x8006,
        kind = BoundaryKind.Ind,
        taken = true)
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
      val predictionTag = trainPrediction(
        dut,
        transactionId = 50,
        requestPc = 0x6000,
        branchPc = 0x6004,
        target = 0xb00,
        fallthroughPc = 0x6006,
        kind = BoundaryKind.Cond,
        taken = true)
      pokeResolve(
        dut,
        transactionId = 50,
        predictionTag = predictionTag,
        requestPc = 0x6000,
        branchPc = 0x6004,
        target = 0xb00,
        fallthroughPc = 0x6006,
        kind = BoundaryKind.Cond,
        taken = true,
        epoch = 1)
      dut.io.duplicateTraining.expect(true.B)
      dut.clock.step()
      dut.io.duplicateTraining.expect(false.B)
    }
  }

  test("exact backend recovery trains the retained checkpoint before pruning it") {
    simulate(module) { dut =>
      clear(dut)
      sendBoundary(
        dut,
        transactionId = 14,
        requestPc = 0x6400,
        hasBoundary = true,
        branchPc = 0x6404,
        target = 0xb40,
        fallthroughPc = 0x6406,
        kind = BoundaryKind.Direct,
        staticTaken = true)
      sendRequest(dut, transactionId = 14, pc = 0x6400)
      waitForFinal(dut)
      val predictionTag = dut.io.response.bits.prediction.predictionTag.peek().litValue.toInt
      dut.clock.step()
      dut.io.response.ready.poke(false.B)
      applyCanonicalCorrection(
        dut,
        transactionId = 14,
        predictionTag = predictionTag,
        taken = true,
        kind = BoundaryKind.Direct)

      pokeResolve(
        dut,
        transactionId = 14,
        predictionTag = predictionTag,
        requestPc = 0x6400,
        branchPc = 0x6404,
        target = 0xb80,
        fallthroughPc = 0x6406,
        kind = BoundaryKind.Direct,
        taken = true,
        mispredict = true,
        epoch = 1)

      dut.io.prune.poke(0.U.asTypeOf(dut.io.prune))
      dut.io.prune.valid.poke(true.B)
      dut.io.prune.peId.poke(1.U)
      dut.io.prune.threadId.poke(0.U)
      dut.io.prune.transactionId.poke(14.U)
      dut.io.prune.fetchPacketUid.poke(14.U)
      dut.io.prune.fetchSeq.poke(14.U)
      dut.io.prune.oldEpoch.poke(1.U)
      dut.io.prune.newEpoch.poke(2.U)
      dut.io.prune.checkpointId.poke(14.U)
      dut.io.prune.reason.poke(IfuInnerFlushReason.BruRecovery)
      dut.io.prune.scope.poke(IfuPruneScope.KillAllThreadState)
      dut.io.prune.historyKeyValid.poke(true.B)
      dut.io.prune.predictionTag.poke(predictionTag.U)
      dut.io.prune.ghrAction.poke(GhrRecoveryAction.RestoreTrigger)
      dut.io.prune.rasAction.poke(RasRecoveryAction.RestoreTrigger)
      dut.io.staleTraining.expect(false.B)
      dut.clock.step()
      dut.io.prune.valid.poke(false.B)
      dut.io.historyCount.expect(0.U)

      pokeResolve(
        dut,
        transactionId = 14,
        predictionTag = predictionTag,
        requestPc = 0x6400,
        branchPc = 0x6404,
        target = 0xb80,
        fallthroughPc = 0x6406,
        kind = BoundaryKind.Direct,
        taken = true,
        mispredict = true,
        epoch = 1)
      dut.io.duplicateTraining.expect(true.B)
    }
  }

  test("training without an exact request-owned checkpoint is rejected as stale") {
    simulate(module) { dut =>
      clear(dut)
      pokeResolve(
        dut,
        transactionId = 90,
        predictionTag = 3,
        requestPc = 0xa000,
        branchPc = 0xa004,
        target = 0xe00,
        fallthroughPc = 0xa006,
        kind = BoundaryKind.Cond,
        taken = true)
      dut.io.staleTraining.expect(true.B)
      dut.io.historyCount.expect(0.U)
      dut.clock.step()
      dut.io.staleTraining.expect(false.B)
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
