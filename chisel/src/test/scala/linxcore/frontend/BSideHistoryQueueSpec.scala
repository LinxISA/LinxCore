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

  private def smallRasModule =
    new BSideHistoryQueue(
      p = p,
      lineBytes = lineBytes,
      threadCount = 1,
      entries = 4,
      rasDepth = 2)

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
      scope: IfuPruneScope.Type = IfuPruneScope.PreserveTriggerKillYounger,
      rasUpdate: RasUpdateAction.Type = RasUpdateAction.None,
      rasPushAddress: BigInt = 0,
      reason: IfuInnerFlushReason.Type = IfuInnerFlushReason.PredictionCorrection,
      oldEpoch: Int = 0,
      newEpoch: Int = 0): Unit = {
    val sequence = if (fetchSeq < 0) transactionId else fetchSeq
    dut.io.prune.poke(0.U.asTypeOf(dut.io.prune))
    dut.io.prune.valid.poke(true.B)
    dut.io.prune.peId.poke(1.U)
    dut.io.prune.threadId.poke(threadId.U)
    dut.io.prune.transactionId.poke(transactionId.U)
    dut.io.prune.fetchPacketUid.poke(transactionId.U)
    dut.io.prune.fetchSeq.poke(sequence.U)
    dut.io.prune.oldEpoch.poke(oldEpoch.U)
    dut.io.prune.newEpoch.poke(newEpoch.U)
    dut.io.prune.checkpointId.poke((transactionId & 0x3f).U)
    dut.io.prune.reason.poke(reason)
    dut.io.prune.scope.poke(scope)
    dut.io.prune.historyKeyValid.poke(true.B)
    dut.io.prune.predictionTag.poke(predictionTag.U)
    dut.io.prune.ghrAction.poke(GhrRecoveryAction.RestoreTrigger)
    dut.io.prune.ghrAppendValid.poke(append.B)
    dut.io.prune.ghrAppendTaken.poke(taken.B)
    dut.io.prune.rasAction.poke(RasRecoveryAction.RestoreTrigger)
    dut.io.prune.rasUpdate.poke(rasUpdate)
    dut.io.prune.rasPushAddress.poke(rasPushAddress.U)
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
      fetchSeq: Int = -1,
      epoch: Int = 0): Unit = {
    val sequence = if (fetchSeq < 0) transactionId else fetchSeq
    dut.io.resolve.bits.poke(0.U.asTypeOf(dut.io.resolve.bits))
    dut.io.resolve.valid.poke(true.B)
    dut.io.resolve.bits.peId.poke(1.U)
    dut.io.resolve.bits.transactionId.poke(transactionId.U)
    dut.io.resolve.bits.predictionTag.poke(predictionTag.U)
    dut.io.resolve.bits.threadId.poke(threadId.U)
    dut.io.resolve.bits.fetchPacketUid.poke(transactionId.U)
    dut.io.resolve.bits.fetchSeq.poke(sequence.U)
    dut.io.resolve.bits.epoch.poke(epoch.U)
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

  test("call and return mutate request-owned RAS only at canonical prune") {
    simulate(module) { dut =>
      clear(dut)
      allocate(dut, transactionId = 1, predictionTag = 0)
      dut.io.allocateRasTopValid.expect(false.B)
      correctionIntent(
        dut,
        transactionId = 1,
        predictionTag = 0,
        taken = true,
        kind = BoundaryKind.Call)
      dut.io.speculativeRasCount(0).expect(0.U)
      canonicalPrune(
        dut,
        transactionId = 1,
        predictionTag = 0,
        taken = true,
        append = false,
        rasUpdate = RasUpdateAction.Push,
        rasPushAddress = 0x1800)
      dut.io.speculativeRasCount(0).expect(1.U)

      allocate(dut, transactionId = 2, predictionTag = 1)
      dut.io.allocateRasTopValid.expect(true.B)
      dut.io.allocateRasTop.expect(0x1800.U)
      correctionIntent(
        dut,
        transactionId = 2,
        predictionTag = 1,
        taken = true,
        kind = BoundaryKind.Ret)
      dut.io.speculativeRasCount(0).expect(1.U)
      canonicalPrune(
        dut,
        transactionId = 2,
        predictionTag = 1,
        taken = true,
        append = false,
        rasUpdate = RasUpdateAction.Pop)
      dut.io.speculativeRasCount(0).expect(0.U)
    }
  }

  test("late call re-correction restores its RAS snapshot and removes younger state") {
    simulate(module) { dut =>
      clear(dut)
      allocate(dut, transactionId = 1, predictionTag = 0)
      correctionIntent(
        dut,
        transactionId = 1,
        predictionTag = 0,
        taken = true,
        kind = BoundaryKind.Call)
      canonicalPrune(
        dut,
        transactionId = 1,
        predictionTag = 0,
        taken = true,
        append = false,
        rasUpdate = RasUpdateAction.Push,
        rasPushAddress = 0x1800)
      allocate(dut, transactionId = 2, predictionTag = 1)

      correctionIntent(
        dut,
        transactionId = 1,
        predictionTag = 0,
        taken = false,
        kind = BoundaryKind.Fall)
      canonicalPrune(
        dut,
        transactionId = 1,
        predictionTag = 0,
        taken = false,
        append = false)
      dut.io.speculativeRasCount(0).expect(0.U)
      dut.io.validMask.expect("b0001".U)
    }
  }

  test("full circular RAS overwrites the oldest return and empty pop is stable") {
    simulate(smallRasModule) { dut =>
      clear(dut)
      for (index <- 0 until 3) {
        val transactionId = index + 1
        allocate(dut, transactionId = transactionId, predictionTag = index)
        correctionIntent(
          dut,
          transactionId = transactionId,
          predictionTag = index,
          taken = true,
          kind = BoundaryKind.Call)
        canonicalPrune(
          dut,
          transactionId = transactionId,
          predictionTag = index,
          taken = true,
          append = false,
          rasUpdate = RasUpdateAction.Push,
          rasPushAddress = 0x1800 + index * 4)
        dut.io.speculativeRasCount(0).expect(math.min(index + 1, 2).U)
        resolve(dut, transactionId = transactionId, predictionTag = index, taken = true)
      }

      allocate(dut, transactionId = 4, predictionTag = 3)
      dut.io.allocateRasTopValid.expect(true.B)
      dut.io.allocateRasTop.expect(0x1808.U)
      correctionIntent(
        dut,
        transactionId = 4,
        predictionTag = 3,
        taken = true,
        kind = BoundaryKind.Ret)
      canonicalPrune(
        dut,
        transactionId = 4,
        predictionTag = 3,
        taken = true,
        append = false,
        rasUpdate = RasUpdateAction.Pop)
      dut.io.speculativeRasCount(0).expect(1.U)

      allocate(dut, transactionId = 5, predictionTag = 4)
      dut.io.allocateRasTop.expect(0x1804.U)
      correctionIntent(
        dut,
        transactionId = 5,
        predictionTag = 4,
        taken = true,
        kind = BoundaryKind.Ret)
      canonicalPrune(
        dut,
        transactionId = 5,
        predictionTag = 4,
        taken = true,
        append = false,
        rasUpdate = RasUpdateAction.Pop)
      dut.io.speculativeRasCount(0).expect(0.U)

      allocate(dut, transactionId = 6, predictionTag = 5)
      dut.io.allocateRasTopValid.expect(false.B)
      correctionIntent(
        dut,
        transactionId = 6,
        predictionTag = 5,
        taken = true,
        kind = BoundaryKind.Ret)
      canonicalPrune(
        dut,
        transactionId = 6,
        predictionTag = 5,
        taken = true,
        append = false,
        rasUpdate = RasUpdateAction.Pop)
      dut.io.speculativeRasCount(0).expect(0.U)
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

  test("immutable history keys survive multiple frontend epoch rebases before backend recovery") {
    simulate(module) { dut =>
      clear(dut)
      allocate(dut, transactionId = 1, predictionTag = 0)
      correctionIntent(dut, transactionId = 1, predictionTag = 0, taken = true)
      canonicalPrune(
        dut,
        transactionId = 1,
        predictionTag = 0,
        taken = true,
        newEpoch = 1)

      resolve(
        dut,
        transactionId = 1,
        predictionTag = 0,
        taken = false,
        mispredict = true,
        epoch = 9)
      dut.io.validMask.expect("b0001".U)

      canonicalPrune(
        dut,
        transactionId = 1,
        predictionTag = 0,
        taken = false,
        scope = IfuPruneScope.KillAllThreadState,
        reason = IfuInnerFlushReason.BruRecovery,
        oldEpoch = 9,
        newEpoch = 10)
      dut.io.validMask.expect(0.U)
      dut.io.speculativeGhr(0).expect(0.U)
    }
  }

  test("backend actual non-call restores the exact older RAS checkpoint and kills younger state") {
    simulate(module) { dut =>
      clear(dut)
      allocate(dut, transactionId = 1, predictionTag = 0)
      correctionIntent(
        dut,
        transactionId = 1,
        predictionTag = 0,
        taken = true,
        kind = BoundaryKind.Call)
      canonicalPrune(
        dut,
        transactionId = 1,
        predictionTag = 0,
        taken = true,
        append = false,
        rasUpdate = RasUpdateAction.Push,
        rasPushAddress = 0x1800)
      dut.io.speculativeRasCount(0).expect(1.U)
      resolve(
        dut,
        transactionId = 1,
        predictionTag = 0,
        taken = false,
        mispredict = true)

      allocate(dut, transactionId = 2, predictionTag = 1)
      correctionIntent(
        dut,
        transactionId = 2,
        predictionTag = 1,
        taken = true,
        kind = BoundaryKind.Call)
      canonicalPrune(
        dut,
        transactionId = 2,
        predictionTag = 1,
        taken = true,
        append = false,
        rasUpdate = RasUpdateAction.Push,
        rasPushAddress = 0x2800)
      dut.io.speculativeRasCount(0).expect(2.U)

      canonicalPrune(
        dut,
        transactionId = 1,
        predictionTag = 0,
        taken = false,
        append = false,
        scope = IfuPruneScope.KillAllThreadState,
        reason = IfuInnerFlushReason.FetchReplay)
      dut.io.speculativeRasCount(0).expect(0.U)
      dut.io.validMask.expect(0.U)
    }
  }

  test("non-ITLB history recovery rejects an unkeyed backend redirect") {
    intercept[Exception] {
      simulate(module) { dut =>
        clear(dut)
        allocate(dut, transactionId = 1, predictionTag = 0)
        dut.io.prune.poke(0.U.asTypeOf(dut.io.prune))
        dut.io.prune.valid.poke(true.B)
        dut.io.prune.peId.poke(1.U)
        dut.io.prune.threadId.poke(0.U)
        dut.io.prune.transactionId.poke(1.U)
        dut.io.prune.fetchPacketUid.poke(1.U)
        dut.io.prune.fetchSeq.poke(1.U)
        dut.io.prune.oldEpoch.poke(0.U)
        dut.io.prune.checkpointId.poke(1.U)
        dut.io.prune.reason.poke(IfuInnerFlushReason.FetchReplay)
        dut.io.prune.scope.poke(IfuPruneScope.KillAllThreadState)
        dut.io.prune.ghrAction.poke(GhrRecoveryAction.RestoreTrigger)
        dut.io.prune.rasAction.poke(RasRecoveryAction.RestoreTrigger)
        dut.clock.step()
      }
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
      dut.io.prune.rasAction.poke(RasRecoveryAction.RestoreTrigger)
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
      dut.io.prune.rasAction.poke(RasRecoveryAction.Reset)
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
