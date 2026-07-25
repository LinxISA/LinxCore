package linxcore.frontend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.common.{BoundaryKind, InterfaceParams}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class BSideHistoryQueueSpec extends AnyFunSuite with ChiselSim with Matchers {
  private val p = InterfaceParams()
  private val lineBytes = 16

  private def module =
    new BSideHistoryQueue(p = p, lineBytes = lineBytes, threadCount = 1, entries = 4)

  private def twoThreadModule =
    new BSideHistoryQueue(p = p, lineBytes = lineBytes, threadCount = 2, entries = 4)

  private def oneEntryTwoThreadModule =
    new BSideHistoryQueue(p = p, lineBytes = lineBytes, threadCount = 2, entries = 1)

  private def clear(dut: BSideHistoryQueue): Unit = {
    dut.io.allocate.valid.poke(false.B)
    dut.io.allocate.bits.poke(0.U.asTypeOf(dut.io.allocate.bits))
    dut.io.correction.valid.poke(false.B)
    dut.io.correction.bits.poke(0.U.asTypeOf(dut.io.correction.bits))
    dut.io.resolve.valid.poke(false.B)
    dut.io.resolve.bits.poke(0.U.asTypeOf(dut.io.resolve.bits))
    dut.io.release.valid.poke(false.B)
    dut.io.release.bits.poke(0.U.asTypeOf(dut.io.release.bits))
    dut.io.prune.poke(0.U.asTypeOf(dut.io.prune))
  }

  private def allocate(
      dut: BSideHistoryQueue,
      transactionId: Int,
      predictionTag: Int,
      threadId: Int = 0,
      fetchSeq: Int = -1): BigInt = {
    val sequence = if (fetchSeq < 0) transactionId else fetchSeq
    dut.io.allocate.bits.poke(0.U.asTypeOf(dut.io.allocate.bits))
    dut.io.allocate.valid.poke(true.B)
    dut.io.allocate.bits.predictionTag.poke(predictionTag.U)
    dut.io.allocate.bits.request.pc.poke((0x1000 + transactionId * 0x10).U)
    dut.io.allocate.bits.request.lineVa.poke((0x1000 + transactionId * 0x10).U)
    dut.io.allocate.bits.request.transactionId.poke(transactionId.U)
    dut.io.allocate.bits.request.identity.peId.poke(1.U)
    dut.io.allocate.bits.request.identity.threadId.poke(threadId.U)
    dut.io.allocate.bits.request.identity.fetchPacketUid.poke(transactionId.U)
    dut.io.allocate.bits.request.identity.fetchSeq.poke(sequence.U)
    dut.io.allocate.bits.request.identity.checkpointId.poke((transactionId & 0x3f).U)
    dut.io.allocate.bits.request.identity.epoch.poke(0.U)
    dut.io.allocate.ready.expect(true.B)
    val snapshot = dut.io.allocateHistory.peek().litValue
    dut.clock.step()
    dut.io.allocate.valid.poke(false.B)
    snapshot
  }

  private def correctionIntent(
      dut: BSideHistoryQueue,
      transactionId: Int,
      predictionTag: Int,
      taken: Boolean,
      kind: BoundaryKind.Type = BoundaryKind.Cond,
      threadId: Int = 0,
      fetchSeq: Int = -1): Unit = {
    val sequence = if (fetchSeq < 0) transactionId else fetchSeq
    dut.io.correction.bits.poke(0.U.asTypeOf(dut.io.correction.bits))
    dut.io.correction.valid.poke(true.B)
    dut.io.correction.bits.request.pc.poke((0x1000 + transactionId * 0x10).U)
    dut.io.correction.bits.request.lineVa.poke((0x1000 + transactionId * 0x10).U)
    dut.io.correction.bits.request.transactionId.poke(transactionId.U)
    dut.io.correction.bits.request.identity.peId.poke(1.U)
    dut.io.correction.bits.request.identity.threadId.poke(threadId.U)
    dut.io.correction.bits.request.identity.fetchPacketUid.poke(transactionId.U)
    dut.io.correction.bits.request.identity.fetchSeq.poke(sequence.U)
    dut.io.correction.bits.request.identity.checkpointId.poke((transactionId & 0x3f).U)
    dut.io.correction.bits.request.identity.epoch.poke(0.U)
    dut.io.correction.bits.prediction.predictionTag.poke(predictionTag.U)
    dut.io.correction.bits.prediction.kind.poke(kind)
    dut.io.correction.bits.prediction.taken.poke(taken.B)
    dut.io.correctionMatch.expect(true.B)
    dut.clock.step()
    dut.io.correction.valid.poke(false.B)
  }

  private def canonicalPrune(
      dut: BSideHistoryQueue,
      transactionId: Int,
      predictionTag: Int,
      taken: Boolean,
      append: Boolean = true,
      threadId: Int = 0,
      fetchSeq: Int = -1,
      scope: IfuPruneScope.Type = IfuPruneScope.PreserveTriggerKillYounger): Unit = {
    val sequence = if (fetchSeq < 0) transactionId else fetchSeq
    dut.io.prune.poke(0.U.asTypeOf(dut.io.prune))
    dut.io.prune.valid.poke(true.B)
    dut.io.prune.peId.poke(1.U)
    dut.io.prune.threadId.poke(threadId.U)
    dut.io.prune.transactionId.poke(transactionId.U)
    dut.io.prune.fetchPacketUid.poke(transactionId.U)
    dut.io.prune.fetchSeq.poke(sequence.U)
    dut.io.prune.oldEpoch.poke(0.U)
    dut.io.prune.checkpointId.poke((transactionId & 0x3f).U)
    dut.io.prune.reason.poke(IfuInnerFlushReason.PredictionCorrection)
    dut.io.prune.scope.poke(scope)
    dut.io.prune.historyKeyValid.poke(true.B)
    dut.io.prune.predictionTag.poke(predictionTag.U)
    dut.io.prune.ghrAction.poke(GhrRecoveryAction.RestoreTrigger)
    dut.io.prune.ghrAppendValid.poke(append.B)
    dut.io.prune.ghrAppendTaken.poke(taken.B)
    dut.clock.step()
    dut.io.prune.valid.poke(false.B)
  }

  private def resolve(
      dut: BSideHistoryQueue,
      transactionId: Int,
      predictionTag: Int,
      taken: Boolean,
      mispredict: Boolean = false,
      threadId: Int = 0,
      fetchSeq: Int = -1): Unit = {
    val sequence = if (fetchSeq < 0) transactionId else fetchSeq
    dut.io.resolve.bits.poke(0.U.asTypeOf(dut.io.resolve.bits))
    dut.io.resolve.valid.poke(true.B)
    dut.io.resolve.bits.peId.poke(1.U)
    dut.io.resolve.bits.transactionId.poke(transactionId.U)
    dut.io.resolve.bits.predictionTag.poke(predictionTag.U)
    dut.io.resolve.bits.threadId.poke(threadId.U)
    dut.io.resolve.bits.fetchPacketUid.poke(transactionId.U)
    dut.io.resolve.bits.fetchSeq.poke(sequence.U)
    dut.io.resolve.bits.epoch.poke(0.U)
    dut.io.resolve.bits.checkpointId.poke((transactionId & 0x3f).U)
    dut.io.resolve.bits.requestPc.poke((0x1000 + transactionId * 0x10).U)
    dut.io.resolve.bits.kind.poke(BoundaryKind.Cond)
    dut.io.resolve.bits.taken.poke(taken.B)
    dut.io.resolve.bits.mispredict.poke(mispredict.B)
    dut.io.resolveMatch.expect(true.B)
    dut.clock.step()
    dut.io.resolve.valid.poke(false.B)
  }

  test("prediction correction changes GHR only at the canonical prune point") {
    simulate(module) { dut =>
      clear(dut)
      allocate(dut, transactionId = 1, predictionTag = 0) shouldBe 0
      correctionIntent(dut, transactionId = 1, predictionTag = 0, taken = true)
      dut.io.speculativeGhr(0).expect(0.U)
      dut.io.redirectPending(0).expect(true.B)

      canonicalPrune(dut, transactionId = 1, predictionTag = 0, taken = true)
      dut.io.speculativeGhr(0).expect(1.U)
      dut.io.redirectPending(0).expect(false.B)
      dut.io.validMask.expect("b0001".U)
    }
  }

  test("late re-correction restores the producer snapshot and kills younger history") {
    simulate(module) { dut =>
      clear(dut)
      allocate(dut, transactionId = 1, predictionTag = 0)
      correctionIntent(dut, transactionId = 1, predictionTag = 0, taken = true)
      canonicalPrune(dut, transactionId = 1, predictionTag = 0, taken = true)
      allocate(dut, transactionId = 2, predictionTag = 1) shouldBe 1

      correctionIntent(dut, transactionId = 1, predictionTag = 0, taken = false)
      canonicalPrune(dut, transactionId = 1, predictionTag = 0, taken = false)
      dut.io.speculativeGhr(0).expect(0.U)
      dut.io.validMask.expect("b0001".U)
      dut.io.count.expect(1.U)
    }
  }

  test("resolved training reads the immutable B-F0 history snapshot") {
    simulate(module) { dut =>
      clear(dut)
      allocate(dut, transactionId = 1, predictionTag = 0) shouldBe 0
      allocate(dut, transactionId = 2, predictionTag = 1) shouldBe 0
      correctionIntent(dut, transactionId = 2, predictionTag = 1, taken = true)
      canonicalPrune(dut, transactionId = 2, predictionTag = 1, taken = true)
      dut.io.speculativeGhr(0).expect(1.U)

      dut.io.resolve.valid.poke(true.B)
      dut.io.resolve.bits.poke(0.U.asTypeOf(dut.io.resolve.bits))
      dut.io.resolve.bits.peId.poke(1.U)
      dut.io.resolve.bits.transactionId.poke(1.U)
      dut.io.resolve.bits.predictionTag.poke(0.U)
      dut.io.resolve.bits.threadId.poke(0.U)
      dut.io.resolve.bits.fetchPacketUid.poke(1.U)
      dut.io.resolve.bits.fetchSeq.poke(1.U)
      dut.io.resolve.bits.checkpointId.poke(1.U)
      dut.io.resolve.bits.requestPc.poke(0x1010.U)
      dut.io.resolveMatch.expect(true.B)
      dut.io.resolveHistory.expect(0.U)
    }
  }

  test("backend actual direction repairs from a mispredict-retained checkpoint") {
    simulate(module) { dut =>
      clear(dut)
      allocate(dut, transactionId = 1, predictionTag = 0)
      correctionIntent(dut, transactionId = 1, predictionTag = 0, taken = true)
      canonicalPrune(dut, transactionId = 1, predictionTag = 0, taken = true)
      resolve(
        dut,
        transactionId = 1,
        predictionTag = 0,
        taken = false,
        mispredict = true)
      dut.io.validMask.expect("b0001".U)

      canonicalPrune(
        dut,
        transactionId = 1,
        predictionTag = 0,
        taken = false,
        scope = IfuPruneScope.KillAllThreadState)
      dut.io.speculativeGhr(0).expect(0.U)
    }
  }

  test("ITLB recovery without a trigger row restores the oldest killed snapshot") {
    simulate(module) { dut =>
      clear(dut)
      allocate(dut, transactionId = 1, predictionTag = 0)
      correctionIntent(dut, transactionId = 1, predictionTag = 0, taken = true)
      canonicalPrune(dut, transactionId = 1, predictionTag = 0, taken = true)
      resolve(dut, transactionId = 1, predictionTag = 0, taken = true)

      allocate(dut, transactionId = 6, predictionTag = 1, fetchSeq = 6) shouldBe 1
      allocate(dut, transactionId = 7, predictionTag = 2, fetchSeq = 7) shouldBe 1
      correctionIntent(dut, transactionId = 7, predictionTag = 2, taken = false, fetchSeq = 7)
      canonicalPrune(dut, transactionId = 7, predictionTag = 2, taken = false, fetchSeq = 7)
      dut.io.speculativeGhr(0).expect(2.U)

      dut.io.prune.poke(0.U.asTypeOf(dut.io.prune))
      dut.io.prune.valid.poke(true.B)
      dut.io.prune.peId.poke(1.U)
      dut.io.prune.threadId.poke(0.U)
      dut.io.prune.transactionId.poke(5.U)
      dut.io.prune.fetchPacketUid.poke(5.U)
      dut.io.prune.fetchSeq.poke(5.U)
      dut.io.prune.oldEpoch.poke(0.U)
      dut.io.prune.checkpointId.poke(5.U)
      dut.io.prune.reason.poke(IfuInnerFlushReason.ItlbMiss)
      dut.io.prune.scope.poke(IfuPruneScope.KillTriggerAndYounger)
      dut.io.prune.ghrAction.poke(GhrRecoveryAction.RestoreTrigger)
      dut.clock.step()
      dut.io.prune.valid.poke(false.B)
      dut.io.speculativeGhr(0).expect(1.U)
      dut.io.validMask.expect(0.U)
    }
  }

  test("a full history queue recovers capacity only after a resident row is released") {
    simulate(module) { dut =>
      clear(dut)
      for (entry <- 0 until 4) {
        allocate(dut, transactionId = entry, predictionTag = entry)
      }
      dut.io.count.expect(4.U)
      dut.io.allocate.bits.predictionTag.poke(4.U)
      dut.io.allocate.ready.expect(false.B)

      resolve(dut, transactionId = 0, predictionTag = 0, taken = false)
      dut.io.allocate.bits.poke(0.U.asTypeOf(dut.io.allocate.bits))
      dut.io.allocate.bits.predictionTag.poke(4.U)
      dut.io.allocate.bits.request.identity.threadId.poke(0.U)
      dut.io.allocate.ready.expect(true.B)
    }
  }

  test("history correction and reset remain isolated between STIDs") {
    simulate(twoThreadModule) { dut =>
      clear(dut)
      allocate(dut, transactionId = 1, predictionTag = 0, threadId = 0) shouldBe 0
      correctionIntent(
        dut,
        transactionId = 1,
        predictionTag = 0,
        taken = true,
        threadId = 0)
      canonicalPrune(
        dut,
        transactionId = 1,
        predictionTag = 0,
        taken = true,
        threadId = 0)

      allocate(dut, transactionId = 2, predictionTag = 1, threadId = 1) shouldBe 0
      correctionIntent(
        dut,
        transactionId = 2,
        predictionTag = 1,
        taken = true,
        threadId = 1)
      canonicalPrune(
        dut,
        transactionId = 2,
        predictionTag = 1,
        taken = true,
        threadId = 1)
      dut.io.speculativeGhr(0).expect(1.U)
      dut.io.speculativeGhr(1).expect(1.U)

      dut.io.prune.poke(0.U.asTypeOf(dut.io.prune))
      dut.io.prune.valid.poke(true.B)
      dut.io.prune.threadId.poke(0.U)
      dut.io.prune.scope.poke(IfuPruneScope.KillAllThreadState)
      dut.io.prune.ghrAction.poke(GhrRecoveryAction.Reset)
      dut.clock.step()
      dut.io.prune.valid.poke(false.B)
      dut.io.speculativeGhr(0).expect(0.U)
      dut.io.speculativeGhr(1).expect(1.U)
      dut.io.validMask.expect("b0010".U)
    }
  }

  test("one-entry history queue reuses its physical row across STIDs") {
    simulate(oneEntryTwoThreadModule) { dut =>
      clear(dut)
      allocate(dut, transactionId = 1, predictionTag = 0, threadId = 1) shouldBe 0
      correctionIntent(
        dut,
        transactionId = 1,
        predictionTag = 0,
        taken = true,
        threadId = 1)
      canonicalPrune(
        dut,
        transactionId = 1,
        predictionTag = 0,
        taken = true,
        threadId = 1)
      resolve(
        dut,
        transactionId = 1,
        predictionTag = 0,
        taken = true,
        threadId = 1)
      dut.io.count.expect(0.U)

      allocate(dut, transactionId = 2, predictionTag = 1, threadId = 0) shouldBe 0
      dut.io.validMask.expect(1.U)
      dut.io.speculativeGhr(0).expect(0.U)
      dut.io.speculativeGhr(1).expect(1.U)
    }
  }
}
