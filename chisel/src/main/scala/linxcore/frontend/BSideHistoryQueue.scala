package linxcore.frontend

import chisel3._
import chisel3.util.{Decoupled, PopCount, Valid, log2Ceil}
import linxcore.common.InterfaceParams

class BSideHistoryQueueIO(
    val p: InterfaceParams = InterfaceParams(),
    val lineBytes: Int = 64,
    val threadCount: Int = 1,
    val entries: Int = 16,
    val rasDepth: Int = 8)
    extends Bundle {
  private val countWidth = log2Ceil(entries + 1)

  val allocate = Flipped(Decoupled(new BSideHistoryAllocate(p, lineBytes)))
  val correction = Flipped(Valid(new BSidePredictionUpdate(p, lineBytes)))
  val resolve = Flipped(Valid(new BSideResolveUpdate(p)))
  val release = Flipped(Valid(new BSidePredictionUpdate(p, lineBytes)))
  val prune = Input(new IfuInnerFlush(p))

  val allocateHistory = Output(UInt(BSideHistoryContract.GhrWidth.W))
  val allocateRasTopValid = Output(Bool())
  val allocateRasTop = Output(UInt(p.pcWidth.W))
  val resolveHistory = Output(UInt(BSideHistoryContract.GhrWidth.W))
  val resolveMatch = Output(Bool())
  val correctionMatch = Output(Bool())
  val count = Output(UInt(countWidth.W))
  val validMask = Output(UInt(entries.W))
  val speculativeGhr = Output(Vec(threadCount, UInt(BSideHistoryContract.GhrWidth.W)))
  val speculativeRasCount =
    Output(Vec(threadCount, UInt(math.max(1, log2Ceil(rasDepth + 1)).W)))
  val redirectPending = Output(Vec(threadCount, Bool()))
}

/** Request-owned speculative GHR and return-address-stack checkpoint queue.
  *
  * B-F0 snapshots GHR before a request enters the prediction pipeline. Later
  * predictor stages and resolved training use immutable request snapshots
  * rather than whichever speculative GHR/RAS state happens to be live when
  * they execute. A correction proposal only reserves the affected STID. GHR
  * and RAS change after the redirect arbiter returns the canonical prune, so
  * predictor recovery and all other IFU state observe one ordering point.
  */
class BSideHistoryQueue(
    val p: InterfaceParams = InterfaceParams(),
    val lineBytes: Int = 64,
    val threadCount: Int = 1,
    val entries: Int = 16,
    val rasDepth: Int = 8)
    extends Module {
  require(threadCount > 0 && (threadCount & (threadCount - 1)) == 0)
  require(threadCount <= (1 << p.threadIdWidth))
  require(entries > 0 && (entries & (entries - 1)) == 0)
  require(rasDepth > 0 && (rasDepth & (rasDepth - 1)) == 0)

  private val indexWidth = math.max(1, log2Ceil(entries))
  private val threadIndexWidth = math.max(1, log2Ceil(threadCount))
  private val countWidth = log2Ceil(entries + 1)
  private val rasPointerWidth = math.max(1, log2Ceil(rasDepth))
  private val rasCountWidth = math.max(1, log2Ceil(rasDepth + 1))

  val io = IO(new BSideHistoryQueueIO(p, lineBytes, threadCount, entries, rasDepth))

  val rows = RegInit(
    VecInit(Seq.fill(entries)(0.U.asTypeOf(new BSideHistoryCheckpoint(p, lineBytes, rasDepth)))))
  val speculativeGhr =
    RegInit(VecInit(Seq.fill(threadCount)(0.U(BSideHistoryContract.GhrWidth.W))))
  val redirectPending = RegInit(VecInit(Seq.fill(threadCount)(false.B)))
  val speculativeRas = RegInit(
    VecInit(Seq.fill(threadCount)(VecInit(Seq.fill(rasDepth)(0.U(p.pcWidth.W))))))
  val speculativeRasSp =
    RegInit(VecInit(Seq.fill(threadCount)(0.U(rasPointerWidth.W))))
  val speculativeRasCount =
    RegInit(VecInit(Seq.fill(threadCount)(0.U(rasCountWidth.W))))

  def threadIndex(threadId: UInt): UInt =
    if (threadCount == 1) 0.U(threadIndexWidth.W) else threadId(threadIndexWidth - 1, 0)

  def rowIndex(predictionTag: UInt): UInt =
    if (entries == 1) 0.U(indexWidth.W) else predictionTag(indexWidth - 1, 0)

  def rowAt(index: UInt): BSideHistoryCheckpoint =
    if (entries == 1) rows.head else rows(index)

  def exactCheckpoint(
      row: BSideHistoryCheckpoint,
      peId: UInt,
      transactionId: UInt,
      predictionTag: UInt,
      threadId: UInt,
      fetchPacketUid: UInt,
      fetchSeq: UInt,
      epoch: UInt,
      checkpointId: UInt,
      requestPc: UInt): Bool =
    row.valid &&
      row.request.identity.peId === peId &&
      row.request.transactionId === transactionId &&
      row.predictionTag === predictionTag &&
      row.request.identity.threadId === threadId &&
      row.request.identity.fetchPacketUid === fetchPacketUid &&
      row.request.identity.fetchSeq === fetchSeq &&
      row.request.identity.epoch === epoch &&
      row.request.identity.checkpointId === checkpointId &&
      row.request.pc === requestPc

  def exactFlushRequest(
      request: ISideFetchRequest,
      predictionTag: UInt,
      flush: IfuInnerFlush): Bool =
    request.identity.peId === flush.peId &&
      request.identity.threadId === flush.threadId &&
      request.transactionId === flush.transactionId &&
      request.identity.fetchPacketUid === flush.fetchPacketUid &&
      request.identity.fetchSeq === flush.fetchSeq &&
      request.identity.epoch === flush.oldEpoch &&
      request.identity.checkpointId === flush.checkpointId &&
      (!flush.historyKeyValid || predictionTag === flush.predictionTag)

  val allocateIndex = rowIndex(io.allocate.bits.predictionTag)
  val allocateThread = threadIndex(io.allocate.bits.request.identity.threadId)
  io.allocate.ready :=
    !rowAt(allocateIndex).valid &&
      !redirectPending(allocateThread) &&
      !io.prune.valid
  io.allocateHistory := speculativeGhr(allocateThread)
  io.allocateRasTopValid := speculativeRasCount(allocateThread) =/= 0.U
  io.allocateRasTop := speculativeRas(allocateThread)(speculativeRasSp(allocateThread))

  val correctionIndex = rowIndex(io.correction.bits.prediction.predictionTag)
  val correctionRow = rowAt(correctionIndex)
  val correctionMatch =
    exactCheckpoint(
      correctionRow,
      io.correction.bits.request.identity.peId,
      io.correction.bits.request.transactionId,
      io.correction.bits.prediction.predictionTag,
      io.correction.bits.request.identity.threadId,
      io.correction.bits.request.identity.fetchPacketUid,
      io.correction.bits.request.identity.fetchSeq,
      io.correction.bits.request.identity.epoch,
      io.correction.bits.request.identity.checkpointId,
      io.correction.bits.request.pc)
  io.correctionMatch := io.correction.valid && correctionMatch

  val resolveIndex = rowIndex(io.resolve.bits.predictionTag)
  val resolveRow = rowAt(resolveIndex)
  val resolveMatch =
    exactCheckpoint(
      resolveRow,
      io.resolve.bits.peId,
      io.resolve.bits.transactionId,
      io.resolve.bits.predictionTag,
      io.resolve.bits.threadId,
      io.resolve.bits.fetchPacketUid,
      io.resolve.bits.fetchSeq,
      io.resolve.bits.epoch,
      io.resolve.bits.checkpointId,
      io.resolve.bits.requestPc)
  io.resolveMatch := io.resolve.valid && resolveMatch
  io.resolveHistory := resolveRow.ghrBefore

  val releaseIndex = rowIndex(io.release.bits.prediction.predictionTag)
  val releaseRow = rowAt(releaseIndex)
  val releaseMatch =
    exactCheckpoint(
      releaseRow,
      io.release.bits.request.identity.peId,
      io.release.bits.request.transactionId,
      io.release.bits.prediction.predictionTag,
      io.release.bits.request.identity.threadId,
      io.release.bits.request.identity.fetchPacketUid,
      io.release.bits.request.identity.fetchSeq,
      io.release.bits.request.identity.epoch,
      io.release.bits.request.identity.checkpointId,
      io.release.bits.request.pc)

  val pruneThread = threadIndex(io.prune.threadId)
  val pruneLiveMatches = Wire(Vec(entries, Bool()))
  val pruneKilled = Wire(Vec(entries, Bool()))
  for (entry <- 0 until entries) {
    pruneLiveMatches(entry) :=
      rows(entry).valid &&
        exactFlushRequest(rows(entry).request, rows(entry).predictionTag, io.prune)
    pruneKilled(entry) :=
      rows(entry).valid &&
        IfuFlushContract.kills(
          rows(entry).request.identity,
          rows(entry).request.transactionId,
          io.prune)
  }
  val pruneLiveMatch = pruneLiveMatches.asUInt.orR
  val pruneLiveIndex = chisel3.util.PriorityEncoder(pruneLiveMatches.asUInt)
  val oldestKilledValid = Wire(Vec(entries + 1, Bool()))
  val oldestKilledIndex = Wire(Vec(entries + 1, UInt(indexWidth.W)))
  oldestKilledValid(0) := false.B
  oldestKilledIndex(0) := 0.U
  for (entry <- 0 until entries) {
    val previous = rowAt(oldestKilledIndex(entry))
    val chooseEntry =
      pruneKilled(entry) &&
        (!oldestKilledValid(entry) ||
          IfuFlushContract.isYounger(
            previous.request.identity.fetchSeq,
            rows(entry).request.identity.fetchSeq))
    oldestKilledValid(entry + 1) := oldestKilledValid(entry) || pruneKilled(entry)
    oldestKilledIndex(entry + 1) := Mux(chooseEntry, entry.U, oldestKilledIndex(entry))
  }
  val fallbackRow = rowAt(oldestKilledIndex(entries))
  val allowUnkeyedItlbFallback =
    io.prune.reason === IfuInnerFlushReason.ItlbMiss && !io.prune.historyKeyValid

  val recoveryBaseValid =
    pruneLiveMatch || (allowUnkeyedItlbFallback && oldestKilledValid(entries))
  val recoveryBase = Wire(UInt(BSideHistoryContract.GhrWidth.W))
  recoveryBase := speculativeGhr(pruneThread)
  when(pruneLiveMatch) {
    recoveryBase := rowAt(pruneLiveIndex).ghrBefore
  }.elsewhen(allowUnkeyedItlbFallback && oldestKilledValid(entries)) {
    recoveryBase := fallbackRow.ghrBefore
  }
  val repairedHistory =
    Mux(
      io.prune.ghrAppendValid,
      BSideHistoryContract.appendConditional(recoveryBase, io.prune.ghrAppendTaken),
      recoveryBase)
  val pruneKillsAppliedHistory =
    VecInit((0 until entries).map(entry => pruneKilled(entry) && rows(entry).appliedValid)).asUInt.orR
  val recoveryRas = Wire(Vec(rasDepth, UInt(p.pcWidth.W)))
  val recoveryRasSp = Wire(UInt(rasPointerWidth.W))
  val recoveryRasCount = Wire(UInt(rasCountWidth.W))
  recoveryRas := speculativeRas(pruneThread)
  recoveryRasSp := speculativeRasSp(pruneThread)
  recoveryRasCount := speculativeRasCount(pruneThread)
  when(pruneLiveMatch) {
    recoveryRas := rowAt(pruneLiveIndex).rasBefore
    recoveryRasSp := rowAt(pruneLiveIndex).rasSpBefore
    recoveryRasCount := rowAt(pruneLiveIndex).rasCountBefore
  }.elsewhen(allowUnkeyedItlbFallback && oldestKilledValid(entries)) {
    recoveryRas := fallbackRow.rasBefore
    recoveryRasSp := fallbackRow.rasSpBefore
    recoveryRasCount := fallbackRow.rasCountBefore
  }
  val repairedRas = Wire(Vec(rasDepth, UInt(p.pcWidth.W)))
  val repairedRasSp = Wire(UInt(rasPointerWidth.W))
  val repairedRasCount = Wire(UInt(rasCountWidth.W))
  repairedRas := recoveryRas
  repairedRasSp := recoveryRasSp
  repairedRasCount := recoveryRasCount
  when(io.prune.rasUpdate === RasUpdateAction.Push) {
    val nextSp = recoveryRasSp + 1.U
    repairedRas(nextSp) := io.prune.rasPushAddress
    repairedRasSp := nextSp
    repairedRasCount := Mux(recoveryRasCount === rasDepth.U, rasDepth.U, recoveryRasCount + 1.U)
  }.elsewhen(io.prune.rasUpdate === RasUpdateAction.Pop) {
    when(recoveryRasCount =/= 0.U) {
      repairedRasSp := recoveryRasSp - 1.U
      repairedRasCount := recoveryRasCount - 1.U
    }
  }
  val pruneKillsAppliedRas =
    VecInit((0 until entries).map(entry => pruneKilled(entry) && rows(entry).rasAppliedValid)).asUInt.orR

  when(io.prune.valid) {
    for (entry <- 0 until entries) {
      when(pruneKilled(entry)) {
        rows(entry).valid := false.B
      }
    }

    when(io.prune.ghrAction === GhrRecoveryAction.Reset) {
      speculativeGhr(pruneThread) := 0.U
    }.elsewhen(io.prune.ghrAction === GhrRecoveryAction.RestoreTrigger) {
      when(recoveryBaseValid) {
        speculativeGhr(pruneThread) := repairedHistory
      }
    }

    when(io.prune.rasAction === RasRecoveryAction.Reset) {
      speculativeRas(pruneThread) := 0.U.asTypeOf(speculativeRas(pruneThread))
      speculativeRasSp(pruneThread) := 0.U
      speculativeRasCount(pruneThread) := 0.U
    }.elsewhen(io.prune.rasAction === RasRecoveryAction.RestoreTrigger) {
      when(recoveryBaseValid) {
        speculativeRas(pruneThread) := repairedRas
        speculativeRasSp(pruneThread) := repairedRasSp
        speculativeRasCount(pruneThread) := repairedRasCount
      }
    }

    when(
      io.prune.ghrAction === GhrRecoveryAction.RestoreTrigger &&
        io.prune.historyKeyValid && pruneLiveMatch &&
        io.prune.scope === IfuPruneScope.PreserveTriggerKillYounger) {
      rowAt(pruneLiveIndex).appliedValid := io.prune.ghrAppendValid
      rowAt(pruneLiveIndex).appliedTaken := io.prune.ghrAppendTaken
    }
    when(
      io.prune.rasAction === RasRecoveryAction.RestoreTrigger &&
        io.prune.historyKeyValid && pruneLiveMatch &&
        io.prune.scope === IfuPruneScope.PreserveTriggerKillYounger) {
      rowAt(pruneLiveIndex).rasAppliedValid := io.prune.rasUpdate =/= RasUpdateAction.None
    }
    when(
      io.prune.historyKeyValid && pruneLiveMatch &&
        io.prune.scope === IfuPruneScope.PreserveTriggerKillYounger) {
      // The producer survives a frontend correction and is transported to D1
      // in the newly allocated epoch. Keep its request-owned history key in
      // the same canonical epoch domain so a later backend resolve/recovery
      // can still identify this exact checkpoint.
      rowAt(pruneLiveIndex).request.identity.epoch := io.prune.newEpoch
    }
    redirectPending(pruneThread) := false.B
  }.otherwise {
    when(io.allocate.fire) {
      rowAt(allocateIndex) := 0.U.asTypeOf(rowAt(allocateIndex))
      rowAt(allocateIndex).valid := true.B
      rowAt(allocateIndex).request := io.allocate.bits.request
      rowAt(allocateIndex).predictionTag := io.allocate.bits.predictionTag
      rowAt(allocateIndex).ghrBefore := io.allocateHistory
      rowAt(allocateIndex).rasBefore := speculativeRas(allocateThread)
      rowAt(allocateIndex).rasSpBefore := speculativeRasSp(allocateThread)
      rowAt(allocateIndex).rasCountBefore := speculativeRasCount(allocateThread)
    }

    when(io.correction.valid && correctionMatch) {
      val thread = threadIndex(correctionRow.request.identity.threadId)
      redirectPending(thread) := true.B
    }

    when(io.resolve.valid && resolveMatch) {
      when(!io.resolve.bits.mispredict) {
        rowAt(resolveIndex).valid := false.B
      }
    }

    when(io.release.valid && releaseMatch) {
      rowAt(releaseIndex).valid := false.B
    }
  }

  io.count := PopCount(rows.map(_.valid))
  io.validMask := VecInit(rows.map(_.valid)).asUInt
  io.speculativeGhr := speculativeGhr
  io.speculativeRasCount := speculativeRasCount
  io.redirectPending := redirectPending

  when(io.correction.valid) {
    assert(correctionMatch, "prediction correction must restore an exact B-F0 history checkpoint")
  }
  when(
    io.prune.valid && io.prune.historyKeyValid &&
      io.prune.ghrAction === GhrRecoveryAction.RestoreTrigger) {
    assert(
      pruneLiveMatch,
      "keyed GHR recovery must match a live request-owned checkpoint")
  }
  when(io.prune.valid && pruneKillsAppliedHistory) {
    assert(
      io.prune.ghrAction =/= GhrRecoveryAction.None,
      "a prune that removes applied conditional history must carry reset or restore intent")
  }
  when(
    io.prune.valid && io.prune.historyKeyValid &&
      io.prune.rasAction === RasRecoveryAction.RestoreTrigger) {
    assert(
      pruneLiveMatch,
      "keyed RAS recovery must match a live request-owned checkpoint")
  }
  when(io.prune.valid && pruneKillsAppliedRas) {
    assert(
      io.prune.rasAction =/= RasRecoveryAction.None,
      "a prune that removes applied speculative RAS state must carry reset or restore intent")
  }
  when(
    io.prune.valid && io.prune.reason =/= IfuInnerFlushReason.ItlbMiss &&
      (io.prune.ghrAction === GhrRecoveryAction.RestoreTrigger ||
        io.prune.rasAction === RasRecoveryAction.RestoreTrigger)) {
    assert(
      io.prune.historyKeyValid,
      "non-ITLB speculative-history recovery must carry an exact request-owned key")
  }
  when(io.resolve.valid && resolveMatch) {
    assert(resolveRow.predictionTag === io.resolve.bits.predictionTag)
  }
}
