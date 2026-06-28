package linxcore.rename

import circt.stage.ChiselStage
import linxcore.common.InterfaceParams
import linxcore.rob.ROBIDValue
import org.scalatest.funsuite.AnyFunSuite

object TULinkRecoveryCleanupPathReference {
  import TULinkFlushSequencePublisherReference.{Dst, Source}
  import TULinkRenameReference.BankState

  final case class State(t: BankState, u: BankState)
  final case class Result(
      state: State,
      publisher: TULinkFlushSequencePublisherReference.Result,
      cleanupActive: Boolean,
      cleanupBlockedBySource: Boolean,
      tReleased: Set[Int],
      uReleased: Set[Int])

  def cleanup(
      state: State,
      cleanupValid: Boolean,
      backendFlushValid: Boolean,
      baseOnBid: Boolean,
      bid: ROBIDValue,
      rid: ROBIDValue,
      stid: Int,
      source: Source,
      mapQDepth: Int): Result = {
    val publisher = TULinkFlushSequencePublisherReference.publish(
      cleanupValid = cleanupValid,
      backendFlushValid = backendFlushValid,
      baseOnBid = baseOnBid,
      bid = bid,
      rid = rid,
      stid = stid,
      source = source,
      mapQDepth = mapQDepth)
    val active = cleanupValid && backendFlushValid
    val blocked = active && !publisher.flushValid

    if (!publisher.flushValid) {
      Result(state, publisher, cleanupActive = active, cleanupBlockedBySource = blocked, Set.empty, Set.empty)
    } else {
      val (tNext, tReleased) = TULinkRenameReference.flush(
        state.t,
        flushBid = bid,
        flushRid = rid,
        flushSeq = publisher.tSeq,
        baseOnBid = baseOnBid)
      val (uNext, uReleased) = TULinkRenameReference.flush(
        state.u,
        flushBid = bid,
        flushRid = rid,
        flushSeq = publisher.uSeq,
        baseOnBid = baseOnBid)
      Result(
        State(tNext, uNext),
        publisher,
        cleanupActive = active,
        cleanupBlockedBySource = blocked,
        tReleased = tReleased,
        uReleased = uReleased)
    }
  }

  def source(
      valid: Boolean,
      bid: ROBIDValue,
      rid: ROBIDValue,
      stid: Int,
      tSeq: ROBIDValue,
      uSeq: ROBIDValue,
      dst: Dst): Source =
    Source(valid = valid, bid = bid, rid = rid, stid = stid, tSeq = tSeq, uSeq = uSeq, dst = dst)
}

class TULinkRecoveryCleanupPathSpec extends AnyFunSuite {
  import TULinkFlushSequencePublisherReference._
  import TULinkRecoveryCleanupPathReference._
  import TULinkRenameReference.{allocate, initial}

  private val mapQDepth = 8
  private val localRegs = 8
  private val bid1 = ROBIDValue(value = 1)
  private val bid2 = ROBIDValue(value = 2)
  private val rid0 = ROBIDValue(value = 0)
  private val rid1 = ROBIDValue(value = 1)
  private val rid2 = ROBIDValue(value = 2)
  private val gid = ROBIDValue(value = 0)

  private def populatedState(): (State, ROBIDValue, ROBIDValue, ROBIDValue, ROBIDValue, ROBIDValue) = {
    val (t1, _, tSeq0) = allocate(initial(mapQDepth), bid1, rid0, gid, localRegs)
    val (t2, _, tSeq1) = allocate(t1, bid1, rid1, gid, localRegs)
    val (t3, _, tSeq2) = allocate(t2, bid2, rid0, gid, localRegs)
    val (u1, _, uSeq0) = allocate(initial(mapQDepth), bid1, rid0, gid, localRegs)
    val (u2, _, uSeq1) = allocate(u1, bid2, rid0, gid, localRegs)
    (State(t3, u2), tSeq0, tSeq1, tSeq2, uSeq0, uSeq1)
  }

  test("matching non-base cleanup publishes selected-row sequences into T/U rename flush") {
    val (state, _, tSeq1, _, uSeq0, _) = populatedState()
    val result = cleanup(
      state,
      cleanupValid = true,
      backendFlushValid = true,
      baseOnBid = false,
      bid = bid1,
      rid = rid1,
      stid = 0,
      source = source(true, bid1, rid1, 0, tSeq1, uSeq0, NoDst),
      mapQDepth = mapQDepth)

    assert(result.publisher.flushValid)
    assert(result.publisher.sourceMatched)
    assert(!result.cleanupBlockedBySource)
    assert(result.tReleased == Set(1, 2))
    assert(result.uReleased == Set(1))
    assert(result.state.t.usedEntries == 1)
    assert(result.state.u.usedEntries == 1)
  }

  test("a selected row that owns the T destination applies GetPrevRegSeq before pruning") {
    val (state, tSeq0, tSeq1, _, uSeq0, _) = populatedState()
    val result = cleanup(
      state,
      cleanupValid = true,
      backendFlushValid = true,
      baseOnBid = false,
      bid = bid1,
      rid = rid1,
      stid = 0,
      source = source(true, bid1, rid1, 0, tSeq1, uSeq0, TDst),
      mapQDepth = mapQDepth)

    assert(result.publisher.flushValid)
    assert(result.publisher.tPrevApplied)
    assert(!result.publisher.uPrevApplied)
    assert(result.publisher.tSeq == tSeq0)
    assert(result.tReleased == Set(1, 2))
  }

  test("base-on-BID cleanup bypasses the selected-row source and prunes by block") {
    val (state, _, _, _, _, _) = populatedState()
    val result = cleanup(
      state,
      cleanupValid = true,
      backendFlushValid = true,
      baseOnBid = true,
      bid = bid2,
      rid = rid2,
      stid = 0,
      source = source(false, bid1, rid0, 0, ROBIDValue(), ROBIDValue(), NoDst),
      mapQDepth = mapQDepth)

    assert(result.publisher.flushValid)
    assert(!result.publisher.sourceRequired)
    assert(!result.cleanupBlockedBySource)
    assert(result.tReleased == Set(2))
    assert(result.uReleased == Set(1))
    assert(result.state.t.usedEntries == 2)
    assert(result.state.u.usedEntries == 1)
  }

  test("missing or mismatched selected-row sources block local T/U state changes") {
    val (state, _, tSeq1, _, uSeq0, _) = populatedState()
    val missing = cleanup(
      state,
      cleanupValid = true,
      backendFlushValid = true,
      baseOnBid = false,
      bid = bid1,
      rid = rid1,
      stid = 0,
      source = source(false, bid1, rid1, 0, tSeq1, uSeq0, NoDst),
      mapQDepth = mapQDepth)
    assert(!missing.publisher.flushValid)
    assert(missing.publisher.missingSource)
    assert(missing.cleanupBlockedBySource)
    assert(missing.state == state)

    val mismatch = cleanup(
      state,
      cleanupValid = true,
      backendFlushValid = true,
      baseOnBid = false,
      bid = bid1,
      rid = rid1,
      stid = 0,
      source = source(true, bid1, rid2, 0, tSeq1, uSeq0, NoDst),
      mapQDepth = mapQDepth)
    assert(!mismatch.publisher.flushValid)
    assert(mismatch.publisher.sourceMismatch)
    assert(mismatch.cleanupBlockedBySource)
    assert(mismatch.state == state)
  }

  test("inactive cleanup does not block rename-owner maintenance") {
    val (state, _, tSeq1, _, uSeq0, _) = populatedState()
    val result = cleanup(
      state,
      cleanupValid = true,
      backendFlushValid = false,
      baseOnBid = false,
      bid = bid1,
      rid = rid1,
      stid = 0,
      source = source(false, bid1, rid2, 0, tSeq1, uSeq0, NoDst),
      mapQDepth = mapQDepth)

    assert(!result.publisher.flushValid)
    assert(!result.cleanupActive)
    assert(!result.cleanupBlockedBySource)
    assert(result.state == state)
  }

  test("IO exposes T/U rename state, publisher command, and source diagnostics") {
    val p = InterfaceParams(robEntries = 8)
    val io = new TULinkRecoveryCleanupPathIO(p, localRegsT = 8, localRegsU = 8, mapQDepth = 8, bidWidth = 16)

    assert(io.flushSource.tSeq.value.getWidth == 3)
    assert(io.flushSource.uSeq.value.getWidth == 3)
    assert(io.publisherFlushTSeq.value.getWidth == 3)
    assert(io.publisherFlushUSeq.value.getWidth == 3)
    assert(io.tSeq.value.getWidth == 3)
    assert(io.uSeq.value.getWidth == 3)
    assert(io.tMapQValidMask.getWidth == 8)
    assert(io.uMapQValidMask.getWidth == 8)
  }

  test("TULinkRecoveryCleanupPath elaborates as the flush publisher plus T/U rename composition owner") {
    val sv = ChiselStage.emitSystemVerilog(
      new TULinkRecoveryCleanupPath(
        p = InterfaceParams(robEntries = 8),
        localRegsT = 8,
        localRegsU = 8,
        mapQDepth = 8,
        bidWidth = 16)
    )

    assert(sv.contains("module TULinkRecoveryCleanupPath"))
    assert(sv.contains("module TULinkFlushSequencePublisher"))
    assert(sv.contains("module TULinkRename"))
    assert(sv.contains("io_cleanupBlockedBySource"))
    assert(sv.contains("io_flushSourceMismatch"))
    assert(sv.contains("io_publisherFlushTSeq_value"))
    assert(!sv.contains("ScalarDecodeRenameBridge"))
  }
}
