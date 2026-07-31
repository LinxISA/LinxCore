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
      branchPc: BigInt,
      target: BigInt,
      fallthroughPc: BigInt,
      kind: BoundaryKind.Type,
      staticTaken: Boolean,
      continuationReady: Boolean = true): Unit = {
    dut.io.boundary.bits.poke(0.U.asTypeOf(dut.io.boundary.bits))
    dut.io.boundary.valid.poke(true.B)
    dut.io.boundary.bits.valid.poke(true.B)
    dut.io.boundary.bits.peId.poke(1.U)
    dut.io.boundary.bits.transactionId.poke(transactionId.U)
    dut.io.boundary.bits.threadId.poke(0.U)
    dut.io.boundary.bits.fetchPacketUid.poke(transactionId.U)
    dut.io.boundary.bits.fetchSeq.poke(transactionId.U)
    dut.io.boundary.bits.epoch.poke(0.U)
    dut.io.boundary.bits.checkpointId.poke((transactionId & 0x1f).U)
    dut.io.boundary.bits.branchPc.poke(branchPc.U)
    dut.io.boundary.bits.target.poke(target.U)
    dut.io.boundary.bits.fallthroughPc.poke(fallthroughPc.U)
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

  private def applyCanonicalCorrection(
      dut: BSide,
      transactionId: Int,
      predictionTag: Int,
      taken: Boolean,
      kind: BoundaryKind.Type): Unit = {
    dut.io.prune.poke(0.U.asTypeOf(dut.io.prune))
    dut.io.prune.valid.poke(true.B)
    dut.io.prune.peId.poke(1.U)
    dut.io.prune.threadId.poke(0.U)
    dut.io.prune.transactionId.poke(transactionId.U)
    dut.io.prune.fetchPacketUid.poke(transactionId.U)
    dut.io.prune.fetchSeq.poke(transactionId.U)
    dut.io.prune.oldEpoch.poke(0.U)
    dut.io.prune.newEpoch.poke(1.U)
    dut.io.prune.checkpointId.poke((transactionId & 0x1f).U)
    dut.io.prune.reason.poke(IfuInnerFlushReason.PredictionCorrection)
    dut.io.prune.scope.poke(IfuPruneScope.PreserveTriggerKillYounger)
    dut.io.prune.historyKeyValid.poke(true.B)
    dut.io.prune.predictionTag.poke(predictionTag.U)
    dut.io.prune.ghrAction.poke(GhrRecoveryAction.RestoreTrigger)
    dut.io.prune.ghrAppendValid.poke((kind == BoundaryKind.Cond).B)
    dut.io.prune.ghrAppendTaken.poke(taken.B)
    dut.io.prune.rasAction.poke(RasRecoveryAction.RestoreTrigger)
    dut.clock.step()
    dut.io.prune.valid.poke(false.B)
  }

  test("B-SIDE provider order prefers BF4 over BF3 over BF2 over BF1 over BF0 over sequential") {
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

  test("prediction boundary elaborates under the new IFU package") {
    val sv = ChiselStage.emitSystemVerilog(module)
    assert(sv.contains("module BSide"))
    assert(!sv.contains("module BSidePredictionPipeline_1"))
  }
}
