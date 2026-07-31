package linxcore.ifu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import circt.stage.ChiselStage
import linxcore.common.{BoundaryKind, InterfaceParams}
import linxcore.frontend._
import org.scalatest.funsuite.AnyFunSuite

class IFUPredictionSpec extends AnyFunSuite with ChiselSim {
  private val p = InterfaceParams()
  private val lineBytes = 16

  private def module =
    new BSide(
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

  private def clear(dut: BSide): Unit = {
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
      dut: BSide,
      transactionId: Int,
      requestPc: BigInt,
      branchPc: BigInt = 0,
      target: BigInt = 0,
      fallthroughPc: BigInt = 0,
      kind: BoundaryKind.Type = BoundaryKind.Fall,
      staticTaken: Boolean = false,
      hasBoundary: Boolean = true,
      continuationReady: Boolean = true): Unit = {
    dut.io.boundary.bits.poke(0.U.asTypeOf(dut.io.boundary.bits))
    dut.io.boundary.valid.poke(true.B)
    dut.io.boundary.bits.valid.poke(hasBoundary.B)
    dut.io.boundary.bits.peId.poke(1.U)
    dut.io.boundary.bits.transactionId.poke(transactionId.U)
    dut.io.boundary.bits.threadId.poke(0.U)
    dut.io.boundary.bits.fetchPacketUid.poke(transactionId.U)
    dut.io.boundary.bits.fetchSeq.poke(transactionId.U)
    dut.io.boundary.bits.epoch.poke(0.U)
    dut.io.boundary.bits.checkpointId.poke((transactionId & 0x1f).U)
    dut.io.boundary.bits.branchPc.poke(branchPc.U)
    dut.io.boundary.bits.target.poke(target.U)
    val completedFallthrough =
      if (!hasBoundary && fallthroughPc == 0)
        (requestPc & ~BigInt(lineBytes - 1)) + lineBytes
      else fallthroughPc
    dut.io.boundary.bits.fallthroughPc.poke(completedFallthrough.U)
    dut.io.boundary.bits.kind.poke(kind)
    dut.io.boundary.bits.staticTaken.poke(staticTaken.B)
    dut.io.boundary.bits.continuationReady.poke(continuationReady.B)
    dut.io.boundary.ready.expect(true.B)
    dut.clock.step()
    dut.io.boundary.valid.poke(false.B)
  }

  private def sendRequest(dut: BSide, transactionId: Int, pc: BigInt): Unit = {
    dut.io.request.bits.poke(0.U.asTypeOf(dut.io.request.bits))
    dut.io.request.valid.poke(true.B)
    dut.io.request.bits.pc.poke(pc.U)
    dut.io.request.bits.lineVa.poke((pc & ~BigInt(lineBytes - 1)).U)
    dut.io.request.bits.transactionId.poke(transactionId.U)
    dut.io.request.bits.identity.peId.poke(1.U)
    dut.io.request.bits.identity.threadId.poke(0.U)
    dut.io.request.bits.identity.fetchPacketUid.poke(transactionId.U)
    dut.io.request.bits.identity.fetchSeq.poke(transactionId.U)
    dut.io.request.bits.identity.checkpointId.poke((transactionId & 0x1f).U)
    dut.io.request.bits.identity.epoch.poke(0.U)
    dut.io.request.ready.expect(true.B)
    dut.clock.step()
    dut.io.request.valid.poke(false.B)
  }

  private def pokeResolve(
      dut: BSide,
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
    dut.io.resolve.bits.checkpointId.poke((transactionId & 0x1f).U)
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

  private def waitForFinal(dut: BSide, limit: Int = 32): Unit = {
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

  private def waitForResponse(dut: BSide, limit: Int = 24): Unit = {
    var cycles = 0
    while (!dut.io.response.valid.peek().litToBoolean && cycles < limit) {
      dut.clock.step()
      cycles += 1
    }
    assert(dut.io.response.valid.peek().litToBoolean, s"prediction response missing after $cycles cycles")
  }

  private def applyCanonicalCorrection(
      dut: BSide,
      transactionId: Int,
      predictionTag: Int,
      taken: Boolean,
      kind: BoundaryKind.Type,
      oldEpoch: Int = 0,
      newEpoch: Int = 1,
      scope: IfuPruneScope.Type = IfuPruneScope.PreserveTriggerKillYounger,
      reason: IfuInnerFlushReason.Type = IfuInnerFlushReason.PredictionCorrection,
      rasPushAddress: BigInt = 0): Unit = {
    dut.io.prune.poke(0.U.asTypeOf(dut.io.prune))
    dut.io.prune.valid.poke(true.B)
    dut.io.prune.peId.poke(1.U)
    dut.io.prune.threadId.poke(0.U)
    dut.io.prune.transactionId.poke(transactionId.U)
    dut.io.prune.fetchPacketUid.poke(transactionId.U)
    dut.io.prune.fetchSeq.poke(transactionId.U)
    dut.io.prune.oldEpoch.poke(oldEpoch.U)
    dut.io.prune.newEpoch.poke(newEpoch.U)
    dut.io.prune.checkpointId.poke((transactionId & 0x1f).U)
    dut.io.prune.reason.poke(reason)
    dut.io.prune.scope.poke(scope)
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

  private def resetSpeculativeHistory(dut: BSide): Unit = {
    dut.io.prune.poke(0.U.asTypeOf(dut.io.prune))
    dut.io.prune.valid.poke(true.B)
    dut.io.prune.reason.poke(IfuInnerFlushReason.FetchReplay)
    dut.io.prune.scope.poke(IfuPruneScope.KillAllThreadState)
    dut.io.prune.ghrAction.poke(GhrRecoveryAction.Reset)
    dut.io.prune.rasAction.poke(RasRecoveryAction.Reset)
    dut.clock.step()
    dut.io.prune.valid.poke(false.B)
  }

  private def trainPrediction(
      dut: BSide,
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
      branchPc = branchPc,
      target = target,
      fallthroughPc = fallthroughPc,
      kind = kind,
      staticTaken = taken)
    sendRequest(dut, transactionId, requestPc)
    waitForFinal(dut)
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

  private class ProviderRankProbe extends Module {
    val io = IO(Output(Vec(6, UInt(Prediction.ProviderRankWidth.W))))
    io(0) := Prediction.providerRank(BSideStage.Sequential)
    io(1) := Prediction.providerRank(BSideStage.BF0)
    io(2) := Prediction.providerRank(BSideStage.BF1)
    io(3) := Prediction.providerRank(BSideStage.BF2)
    io(4) := Prediction.providerRank(BSideStage.BF3)
    io(5) := Prediction.providerRank(BSideStage.BF4)
  }

  private def bSideWithTables(nano: Int, ub: Int, pb: Int, bim: Int = -1, tage: Int = 8): BSide =
    new BSide(
      p = p,
      lineBytes = lineBytes,
      threadCount = 1,
      boundaryEntries = 8,
      responseEntries = 8,
      trainingEntries = 4,
      nanoEntries = nano,
      ubtbEntries = ub,
      pbtbEntries = pb,
      bimEntries = if (bim < 0) pb else bim,
      tageEntries = tage,
      ibtbEntries = 8,
      loopEntries = 8,
      rasDepth = 8)

  test("B-SIDE provider order prefers BF4 over BF3 over BF2 over BF1 over BF0 over sequential") {
    simulate(new ProviderRankProbe) { dut =>
      dut.io(0).expect(0.U)
      dut.io(1).expect(1.U)
      dut.io(2).expect(2.U)
      dut.io(3).expect(3.U)
      dut.io(4).expect(4.U)
      dut.io(5).expect(5.U)
    }

    simulate(module) { dut =>
      clear(dut)
      sendRequest(dut, transactionId = 20, pc = 0x2000)
      dut.clock.step(8)
      dut.io.response.valid.expect(false.B)
      sendBoundary(dut, transactionId = 20, requestPc = 0x2000, hasBoundary = false)
      waitForResponse(dut)
      dut.io.response.bits.finalResponse.expect(true.B)
      dut.io.response.bits.prediction.provider.expect(PredictionProvider.Sequential)
      dut.io.response.bits.prediction.stage.expect(BSideStage.BF4)
      dut.io.response.bits.prediction.fallthroughPc.expect(0x2010.U)
    }

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
      sendBoundary(dut, 3, 0x3000, 0x3004, 0x900, 0x3006, BoundaryKind.Cond, staticTaken = true)
      sendRequest(dut, 3, 0x3000)
      waitForResponse(dut)
      dut.io.response.bits.finalResponse.expect(false.B)
      dut.io.response.bits.prediction.provider.expect(PredictionProvider.NanoBtb)
      dut.io.response.bits.prediction.stage.expect(BSideStage.BF0)
    }

    simulate(bSideWithTables(nano = 1, ub = 8, pb = 8)) { dut =>
      clear(dut)
      trainPrediction(dut, 50, 0x5000, 0x5004, 0x5400, 0x5006, BoundaryKind.Direct, taken = true)
      trainPrediction(dut, 51, 0x5010, 0x5014, 0x5410, 0x5016, BoundaryKind.Direct, taken = true)
      sendBoundary(dut, 4, 0x5000, 0x5004, 0x5400, 0x5006, BoundaryKind.Direct, staticTaken = true)
      sendRequest(dut, 4, 0x5000)
      waitForResponse(dut)
      dut.io.response.bits.finalResponse.expect(false.B)
      dut.io.response.bits.prediction.provider.expect(PredictionProvider.UBtb)
      dut.io.response.bits.prediction.stage.expect(BSideStage.BF1)
    }

    simulate(bSideWithTables(nano = 1, ub = 1, pb = 8)) { dut =>
      clear(dut)
      trainPrediction(dut, 60, 0x5200, 0x5204, 0x5600, 0x5206, BoundaryKind.Cond, taken = true)
      trainPrediction(dut, 61, 0x5210, 0x5214, 0x5610, 0x5216, BoundaryKind.Cond, taken = true)
      sendBoundary(dut, 5, 0x5200, 0x5204, 0x5600, 0x5206, BoundaryKind.Cond, staticTaken = true)
      sendRequest(dut, 5, 0x5200)
      waitForResponse(dut)
      dut.io.response.bits.finalResponse.expect(false.B)
      dut.io.response.bits.prediction.provider.expect(PredictionProvider.Bim)
      dut.io.response.bits.prediction.stage.expect(BSideStage.BF2)
    }

    simulate(bSideWithTables(nano = 1, ub = 1, pb = 16, bim = 1)) { dut =>
      clear(dut)
      trainPrediction(dut, 80, 0x5300, 0x5304, 0x5700, 0x5306, BoundaryKind.Cond, taken = false)
      trainPrediction(dut, 81, 0x5310, 0x5314, 0x5710, 0x5316, BoundaryKind.Cond, taken = true)
      trainPrediction(dut, 82, 0x5330, 0x5334, 0x5730, 0x5336, BoundaryKind.Cond, taken = true)
      trainPrediction(dut, 83, 0x5350, 0x5354, 0x5750, 0x5356, BoundaryKind.Cond, taken = true)
      resetSpeculativeHistory(dut)

      sendBoundary(dut, 6, 0x5300, 0x5304, 0x5700, 0x5306, BoundaryKind.Cond, staticTaken = true)
      sendRequest(dut, 6, 0x5300)
      waitForResponse(dut, limit = 32)
      dut.io.response.bits.finalResponse.expect(false.B)
      dut.io.response.bits.prediction.provider.expect(PredictionProvider.Bim)
      dut.io.response.bits.prediction.stage.expect(BSideStage.BF2)
      dut.io.response.bits.prediction.taken.expect(true.B)
      val bf2Tag = dut.io.response.bits.prediction.predictionTag.peek().litValue.toInt
      dut.io.response.ready.poke(true.B)
      dut.clock.step()
      dut.io.response.ready.poke(false.B)
      applyCanonicalCorrection(dut, 6, bf2Tag, taken = true, BoundaryKind.Cond)

      waitForResponse(dut, limit = 32)
      dut.io.response.bits.finalResponse.expect(false.B)
      dut.io.response.bits.prediction.provider.expect(PredictionProvider.ShortTage)
      dut.io.response.bits.prediction.stage.expect(BSideStage.BF3)
      dut.io.response.bits.prediction.taken.expect(false.B)
    }

    simulate(module) { dut =>
      clear(dut)

      sendBoundary(dut, 1, 0x1000, 0x1008, 0x1100, 0x1010, BoundaryKind.Cond, staticTaken = true)
      sendRequest(dut, 1, 0x1000)
      waitForFinal(dut)
      dut.io.response.bits.prediction.provider.expect(PredictionProvider.Static)
      dut.io.response.bits.prediction.stage.expect(BSideStage.BF4)
      val tag = dut.io.response.bits.prediction.predictionTag.peek().litValue.toInt
      dut.clock.step()
      dut.io.response.ready.poke(false.B)

      applyCanonicalCorrection(dut, 1, tag, taken = true, BoundaryKind.Cond)
      pokeResolve(dut, 1, tag, 0x1000, 0x1008, 0x1100, 0x1010, BoundaryKind.Cond, taken = false)

      sendBoundary(dut, 9, 0x1000, 0x1008, 0x1200, 0x1010, BoundaryKind.Cond, staticTaken = true)
      sendRequest(dut, 9, 0x1000)
      waitForFinal(dut)
      dut.io.response.bits.prediction.stage.expect(BSideStage.BF4)
      dut.io.response.bits.prediction.provider.expect(PredictionProvider.LongTage)
      dut.io.response.bits.prediction.taken.expect(false.B)
      dut.io.response.bits.prediction.target.expect(0x1200.U)
    }
  }

  test("stale training is rejected without mutating provider history") {
    simulate(module) { dut =>
      clear(dut)
      pokeResolve(
        dut,
        transactionId = 2,
        predictionTag = 3,
        requestPc = 0x2000,
        branchPc = 0x2008,
        target = 0x2200,
        fallthroughPc = 0x2010,
        kind = BoundaryKind.Cond,
        taken = true)
      dut.io.staleTraining.expect(true.B)
      dut.io.historyCount.expect(0.U)
      dut.clock.step()

      sendBoundary(dut, 10, 0x2000, 0x2008, 0x2100, 0x2010, BoundaryKind.Cond, staticTaken = false)
      sendRequest(dut, 10, 0x2000)
      waitForFinal(dut)
      dut.io.response.bits.prediction.provider.expect(PredictionProvider.Static)
      dut.io.response.bits.prediction.target.expect(0x2100.U)
    }
  }

  test("checkpoint-owned recovery restores exact GHR state and kills younger history") {
    simulate(module) { dut =>
      clear(dut)
      sendBoundary(dut, 11, 0x4100, 0x4104, 0x4400, 0x4106, BoundaryKind.Cond, staticTaken = true)
      sendRequest(dut, 11, 0x4100)
      waitForFinal(dut)
      val olderTag = dut.io.response.bits.prediction.predictionTag.peek().litValue.toInt
      dut.io.response.ready.poke(true.B)
      dut.io.innerFlush.valid.expect(true.B)
      dut.clock.step()
      dut.io.response.ready.poke(false.B)
      dut.io.speculativeGhr(0).expect(0.U)

      applyCanonicalCorrection(dut, 11, olderTag, taken = true, BoundaryKind.Cond)
      dut.io.speculativeGhr(0).expect(1.U)
      dut.io.historyCount.expect(1.U)

      sendRequest(dut, 12, 0x4200)
      dut.io.historyCount.expect(2.U)
      applyCanonicalCorrection(dut, 11, olderTag, taken = false, BoundaryKind.Cond)
      dut.io.speculativeGhr(0).expect(0.U)
      dut.io.historyCount.expect(1.U)
      dut.io.historyValidMask.expect((BigInt(1) << olderTag).U)
    }
  }

  test("RAS state mutates only at canonical B-SIDE prune and feeds return prediction") {
    simulate(module) { dut =>
      clear(dut)
      sendBoundary(dut, 21, 0x7000, 0x7002, 0x7100, 0x7004, BoundaryKind.Call, staticTaken = true)
      sendRequest(dut, 21, 0x7000)
      waitForFinal(dut)
      val callTag = dut.io.response.bits.prediction.predictionTag.peek().litValue.toInt
      dut.io.response.ready.poke(true.B)
      dut.io.innerFlush.valid.expect(true.B)
      dut.clock.step()
      dut.io.response.ready.poke(false.B)
      dut.io.speculativeRasCount(0).expect(0.U)

      applyCanonicalCorrection(
        dut,
        transactionId = 21,
        predictionTag = callTag,
        taken = true,
        kind = BoundaryKind.Call,
        rasPushAddress = 0x7004)
      dut.io.speculativeRasCount(0).expect(1.U)

      sendBoundary(dut, 22, 0x7100, 0x7102, 0, 0x7104, BoundaryKind.Ret, staticTaken = true)
      sendRequest(dut, 22, 0x7100)
      waitForFinal(dut)
      dut.io.response.bits.prediction.provider.expect(PredictionProvider.FinalRas)
      dut.io.response.bits.prediction.stage.expect(BSideStage.BF4)
      dut.io.response.bits.prediction.target.expect(0x7004.U)
    }
  }

  test("BTB TAGE BIM and loop state train only through exact public B-SIDE resolution") {
    simulate(module) { dut =>
      clear(dut)
      for (iteration <- 0 until 3) {
        trainPrediction(
          dut,
          transactionId = 70 + iteration,
          requestPc = 0x7400,
          branchPc = 0x7404,
          target = 0x7800,
          fallthroughPc = 0x7406,
          kind = BoundaryKind.Cond,
          taken = false)
      }

      sendBoundary(dut, 30, 0x7400, 0x7404, 0x7800, 0x7406, BoundaryKind.Cond, staticTaken = true)
      sendRequest(dut, 30, 0x7400)
      waitForFinal(dut)
      dut.io.response.bits.prediction.stage.expect(BSideStage.BF4)
      dut.io.response.bits.prediction.provider.expect(PredictionProvider.Loop)
      dut.io.response.bits.prediction.taken.expect(false.B)

      val exactTag = dut.io.response.bits.prediction.predictionTag.peek().litValue.toInt
      dut.clock.step()
      dut.io.response.ready.poke(false.B)
      applyCanonicalCorrection(dut, 30, exactTag, taken = false, BoundaryKind.Cond)
      dut.clock.step()
      pokeResolve(
        dut,
        transactionId = 30,
        predictionTag = exactTag + 1,
        requestPc = 0x7400,
        branchPc = 0x7404,
        target = 0x7c00,
        fallthroughPc = 0x7406,
        kind = BoundaryKind.Cond,
        taken = true)
      dut.io.staleTraining.expect(true.B)
      dut.clock.step()

      sendBoundary(dut, 31, 0x7400, 0x7404, 0x7800, 0x7406, BoundaryKind.Cond, staticTaken = true)
      sendRequest(dut, 31, 0x7400)
      waitForFinal(dut)
      dut.io.response.bits.prediction.provider.expect(PredictionProvider.Loop)
      dut.io.response.bits.prediction.taken.expect(false.B)
    }
  }

  test("prediction boundary elaborates under the new IFU package") {
    val sv = ChiselStage.emitSystemVerilog(module)
    assert(sv.contains("module BSide"))
    assert(!sv.contains("module BSidePredictionPipeline_1"))
  }
}
