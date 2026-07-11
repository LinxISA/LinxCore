package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayMdbLookupWaitPlanReference {
  import STQFlushPruneReference.Id

  final case class Lookup(
      valid: Boolean = true,
      hit: Boolean = true,
      loadBid: Id = Id(valid = true),
      loadLsId: Id = Id(valid = true),
      storeBid: Id = Id(valid = true),
      storePc: BigInt = 0,
      loadInfoValid: Boolean = true,
      storeInfoValid: Boolean = true,
      loadTile: Boolean = false,
      storeTile: Boolean = false)

  final case class Row(
      valid: Boolean = true,
      repick: Boolean = true,
      waitState: Boolean = false,
      tile: Boolean = false,
      bid: Id = Id(valid = true),
      loadLsId: Id = Id(valid = true))

  final case class Result(
      active: Boolean,
      lookupHit: Boolean,
      candidateMask: BigInt,
      candidateCount: Int,
      targetValid: Boolean,
      targetIndex: Int,
      multiTarget: Boolean,
      waitIntentValid: Boolean,
      requestValid: Boolean,
      requestTargetMask: BigInt,
      setWaitStatus: Boolean,
      clearReturnState: Boolean,
      lineWrite: Boolean,
      waitStoreWrite: Boolean,
      nextWaitStore: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByNoLookup: Boolean,
      blockedByLookupMiss: Boolean,
      blockedByMissingLoadInfo: Boolean,
      blockedByMissingStoreInfo: Boolean,
      blockedByTile: Boolean,
      blockedByNoTarget: Boolean,
      blockedByMultiTarget: Boolean,
      blockedByMissingStoreIndex: Boolean,
      blockedByMissingStoreLsId: Boolean)

  private def sameId(lhs: Id, rhs: Id): Boolean =
    lhs.wrap == rhs.wrap && lhs.value == rhs.value

  def apply(
      enable: Boolean,
      flush: Boolean,
      lookup: Option[Lookup],
      rows: Seq[Row],
      storeIndexValid: Boolean = true,
      storeLsIdValid: Boolean = true,
      storeLsId: Id = Id(valid = true)): Result = {
    val active = enable && !flush
    val lookupIntent = lookup.exists(_.valid)
    val current = lookup.getOrElse(Lookup(valid = false, hit = false))
    val loadInfoValid = current.loadInfoValid && current.loadBid.valid && current.loadLsId.valid
    val storeInfoValid = current.storeInfoValid && current.storeBid.valid
    val scalarLookup = !current.loadTile && !current.storeTile
    val lookupHit = active && lookupIntent && current.hit && loadInfoValid && storeInfoValid && scalarLookup
    val candidateMask = rows.zipWithIndex.foldLeft(BigInt(0)) { case (mask, (row, idx)) =>
      val candidate =
        lookupHit &&
          row.valid &&
          (row.repick || row.waitState) &&
          !row.tile &&
          row.bid.valid &&
          row.loadLsId.valid &&
          sameId(row.bid, current.loadBid) &&
          sameId(row.loadLsId, current.loadLsId)
      if (candidate) mask | (BigInt(1) << idx) else mask
    }
    val candidateCount = candidateMask.bitCount
    val targetValid = lookupHit && candidateCount == 1
    val targetIndex =
      if (candidateMask == 0) 0
      else rows.indices.find(idx => ((candidateMask >> idx) & 1) == 1).getOrElse(0)
    val multiTarget = lookupHit && candidateCount > 1
    val waitIntentValid = targetValid
    val requestValid = waitIntentValid

    Result(
      active = active,
      lookupHit = lookupHit,
      candidateMask = candidateMask,
      candidateCount = candidateCount,
      targetValid = targetValid,
      targetIndex = targetIndex,
      multiTarget = multiTarget,
      waitIntentValid = waitIntentValid,
      requestValid = requestValid,
      requestTargetMask = if (requestValid) candidateMask else BigInt(0),
      setWaitStatus = requestValid,
      clearReturnState = requestValid,
      lineWrite = requestValid,
      waitStoreWrite = requestValid,
      nextWaitStore = requestValid,
      blockedByDisabled = !enable && lookupIntent,
      blockedByFlush = enable && flush && lookupIntent,
      blockedByNoLookup = active && !lookupIntent,
      blockedByLookupMiss = active && lookupIntent && !current.hit,
      blockedByMissingLoadInfo = active && lookupIntent && current.hit && !loadInfoValid,
      blockedByMissingStoreInfo = active && lookupIntent && current.hit && loadInfoValid && !storeInfoValid,
      blockedByTile = active && lookupIntent && current.hit && loadInfoValid && storeInfoValid && !scalarLookup,
      blockedByNoTarget = lookupHit && candidateCount == 0,
      blockedByMultiTarget = multiTarget,
      blockedByMissingStoreIndex = waitIntentValid && !storeIndexValid,
      blockedByMissingStoreLsId = waitIntentValid && (!storeLsIdValid || !storeLsId.valid))
  }
}

class LoadReplayMdbLookupWaitPlanSpec extends AnyFunSuite {
  import LoadReplayMdbLookupWaitPlanReference._
  import STQFlushPruneReference.Id

  private def id(value: Int, valid: Boolean = true): Id =
    Id(valid = valid, value = value)

  test("plans a wait-store mutation for exactly one repick LIQ row and resolved native store identity") {
    val lookup = Lookup(loadBid = id(5), loadLsId = id(3), storeBid = id(2), storePc = 0x2400)
    val result = LoadReplayMdbLookupWaitPlanReference(
      enable = true,
      flush = false,
      lookup = Some(lookup),
      rows = Seq(
        Row(valid = true, repick = false, bid = id(5), loadLsId = id(3)),
        Row(bid = id(5), loadLsId = id(3)),
        Row(bid = id(6), loadLsId = id(3)),
        Row(valid = false, bid = id(5), loadLsId = id(3))))

    assert(result.lookupHit)
    assert(result.candidateMask == BigInt(2))
    assert(result.candidateCount == 1)
    assert(result.targetValid)
    assert(result.targetIndex == 1)
    assert(result.waitIntentValid)
    assert(result.requestValid)
    assert(result.requestTargetMask == BigInt(2))
    assert(result.setWaitStatus)
    assert(result.clearReturnState)
    assert(result.lineWrite)
    assert(result.waitStoreWrite)
    assert(result.nextWaitStore)
  }

  test("publishes MDB wait request even when native store index and LSID are unresolved") {
    val lookup = Lookup(loadBid = id(5), loadLsId = id(3), storeBid = id(2), storePc = 0x2400)
    val missingIndex = LoadReplayMdbLookupWaitPlanReference(
      enable = true,
      flush = false,
      lookup = Some(lookup),
      rows = Seq(Row(bid = id(5), loadLsId = id(3))),
      storeIndexValid = false)
    val missingLsId = LoadReplayMdbLookupWaitPlanReference(
      enable = true,
      flush = false,
      lookup = Some(lookup),
      rows = Seq(Row(bid = id(5), loadLsId = id(3))),
      storeLsId = id(0, valid = false))

    assert(missingIndex.waitIntentValid)
    assert(missingIndex.requestValid)
    assert(missingIndex.blockedByMissingStoreIndex)
    assert(missingLsId.waitIntentValid)
    assert(missingLsId.requestValid)
    assert(missingLsId.blockedByMissingStoreLsId)
  }

  test("targets a newly allocated Wait row before first launch") {
    val lookup = Lookup(loadBid = id(5), loadLsId = id(3), storeBid = id(2), storePc = 0x2400)
    val result = LoadReplayMdbLookupWaitPlanReference(
      enable = true,
      flush = false,
      lookup = Some(lookup),
      rows = Seq(Row(repick = false, waitState = true, bid = id(5), loadLsId = id(3))))

    assert(result.targetValid)
    assert(result.requestValid)
    assert(result.setWaitStatus)
  }

  test("suppresses ambiguous row matches and tile lookups") {
    val lookup = Lookup(loadBid = id(5), loadLsId = id(3), storeBid = id(2), storePc = 0x2400)
    val multi = LoadReplayMdbLookupWaitPlanReference(
      enable = true,
      flush = false,
      lookup = Some(lookup),
      rows = Seq(Row(bid = id(5), loadLsId = id(3)), Row(bid = id(5), loadLsId = id(3))))
    val tile = LoadReplayMdbLookupWaitPlanReference(
      enable = true,
      flush = false,
      lookup = Some(lookup.copy(loadTile = true)),
      rows = Seq(Row(bid = id(5), loadLsId = id(3))))

    assert(multi.multiTarget)
    assert(multi.blockedByMultiTarget)
    assert(!multi.requestValid)
    assert(tile.blockedByTile)
    assert(!tile.lookupHit)
    assert(!tile.requestValid)
  }

  test("reports disabled, flushed, miss, missing metadata, and no-target blockers") {
    val lookup = Lookup(loadBid = id(5), loadLsId = id(3), storeBid = id(2), storePc = 0x2400)
    val disabled = LoadReplayMdbLookupWaitPlanReference(false, false, Some(lookup), Seq(Row(bid = id(5), loadLsId = id(3))))
    val flushed = LoadReplayMdbLookupWaitPlanReference(true, true, Some(lookup), Seq(Row(bid = id(5), loadLsId = id(3))))
    val miss = LoadReplayMdbLookupWaitPlanReference(true, false, Some(lookup.copy(hit = false)), Seq(Row(bid = id(5), loadLsId = id(3))))
    val missingLoad = LoadReplayMdbLookupWaitPlanReference(true, false, Some(lookup.copy(loadInfoValid = false)), Seq(Row()))
    val missingStore = LoadReplayMdbLookupWaitPlanReference(true, false, Some(lookup.copy(storeInfoValid = false)), Seq(Row()))
    val noTarget = LoadReplayMdbLookupWaitPlanReference(true, false, Some(lookup), Seq(Row(bid = id(8), loadLsId = id(3))))

    assert(disabled.blockedByDisabled)
    assert(flushed.blockedByFlush)
    assert(miss.blockedByLookupMiss)
    assert(missingLoad.blockedByMissingLoadInfo)
    assert(missingStore.blockedByMissingStoreInfo)
    assert(noTarget.blockedByNoTarget)
    assert(!noTarget.requestValid)
  }

  test("elaborates the MDB lookup wait planner") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayMdbLookupWaitPlan(
      liqEntries = 4,
      idEntries = 16,
      storeEntries = 8,
      lineBytes = 64
    ))
    assert(sv.contains("module LoadReplayMdbLookupWaitPlan"))
    assert(sv.contains("io_requestValid"))
    assert(sv.contains("io_blockedByMissingStoreIndex"))
  }
}
