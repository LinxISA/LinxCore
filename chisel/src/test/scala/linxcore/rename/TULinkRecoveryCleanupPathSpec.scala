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
      selector: TULinkFlushSourceSelectorReference.Result,
      cleanupActive: Boolean,
      cleanupBlockedBySource: Boolean,
      tReleased: Set[Int],
      uReleased: Set[Int])
  final case class LocalBlockCommitResult(
      state: State,
      ready: Boolean,
      accepted: Boolean,
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
      robSource: Source,
      lsuSource: Source,
      mapQDepth: Int): Result = {
    val selector = TULinkFlushSourceSelectorReference.select(
      cleanupValid = cleanupValid,
      backendFlushValid = backendFlushValid,
      baseOnBid = baseOnBid,
      bid = bid,
      rid = rid,
      stid = stid,
      robSource = robSource,
      lsuSource = lsuSource)
    val publisher = TULinkFlushSequencePublisherReference.publish(
      cleanupValid = cleanupValid,
      backendFlushValid = backendFlushValid,
      baseOnBid = baseOnBid,
      bid = bid,
      rid = rid,
      stid = stid,
      source = selector.source,
      mapQDepth = mapQDepth)
    val active = cleanupValid && backendFlushValid
    val blocked = active && !publisher.flushValid

    if (!publisher.flushValid) {
      Result(state, publisher, selector, cleanupActive = active, cleanupBlockedBySource = blocked, Set.empty, Set.empty)
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
        selector,
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

  def localBlockCommit(
      state: State,
      valid: Boolean,
      bid: ROBIDValue,
      externalCommitValid: Boolean = false,
      cleanupBlockedBySource: Boolean = false,
      publisherFlushValid: Boolean = false): LocalBlockCommitResult = {
    val ready = !externalCommitValid && !cleanupBlockedBySource && !publisherFlushValid
    if (!valid || !ready) {
      LocalBlockCommitResult(state, ready = ready, accepted = false, Set.empty, Set.empty)
    } else {
      val (tNext, tReleased) = TULinkRenameReference.blockCommit(state.t, bid)
      val (uNext, uReleased) = TULinkRenameReference.blockCommit(state.u, bid)
      LocalBlockCommitResult(State(tNext, uNext), ready = true, accepted = true, tReleased, uReleased)
    }
  }
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
  private val emptySource = source(false, ROBIDValue(), ROBIDValue(), 0, ROBIDValue(), ROBIDValue(), NoDst)

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
      robSource = source(true, bid1, rid1, 0, tSeq1, uSeq0, NoDst),
      lsuSource = emptySource,
      mapQDepth = mapQDepth)

    assert(result.selector.selectedFromRob)
    assert(result.publisher.flushValid)
    assert(result.publisher.sourceMatched)
    assert(!result.cleanupBlockedBySource)
    assert(result.tReleased == Set(1, 2))
    assert(result.uReleased == Set(1))
    assert(result.state.t.usedEntries == 1)
    assert(result.state.u.usedEntries == 1)
  }

  test("matching LSU source feeds cleanup when the ROB source is absent") {
    val (state, _, tSeq1, _, uSeq0, _) = populatedState()
    val result = cleanup(
      state,
      cleanupValid = true,
      backendFlushValid = true,
      baseOnBid = false,
      bid = bid1,
      rid = rid1,
      stid = 0,
      robSource = emptySource,
      lsuSource = source(true, bid1, rid1, 0, tSeq1, uSeq0, UDst),
      mapQDepth = mapQDepth)

    assert(result.selector.selectedFromLsu)
    assert(result.publisher.flushValid)
    assert(result.publisher.uPrevApplied)
    assert(result.uReleased == Set(1))
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
      robSource = source(true, bid1, rid1, 0, tSeq1, uSeq0, TDst),
      lsuSource = emptySource,
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
      robSource = emptySource,
      lsuSource = emptySource,
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
      robSource = emptySource,
      lsuSource = emptySource,
      mapQDepth = mapQDepth)
    assert(!missing.publisher.flushValid)
    assert(missing.publisher.missingSource)
    assert(missing.selector.sourceMissing)
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
      robSource = source(true, bid1, rid2, 0, tSeq1, uSeq0, NoDst),
      lsuSource = emptySource,
      mapQDepth = mapQDepth)
    assert(!mismatch.publisher.flushValid)
    assert(mismatch.selector.robMismatched)
    assert(mismatch.publisher.missingSource)
    assert(mismatch.cleanupBlockedBySource)
    assert(mismatch.state == state)
  }

  test("conflicting duplicate ROB and LSU sources block local T/U state changes") {
    val (state, _, tSeq1, _, uSeq0, _) = populatedState()
    val conflict = cleanup(
      state,
      cleanupValid = true,
      backendFlushValid = true,
      baseOnBid = false,
      bid = bid1,
      rid = rid1,
      stid = 0,
      robSource = source(true, bid1, rid1, 0, tSeq1, uSeq0, TDst),
      lsuSource = source(true, bid1, rid1, 0, tSeq1, ROBIDValue(value = 7), TDst),
      mapQDepth = mapQDepth)

    assert(conflict.selector.multipleMatched)
    assert(conflict.selector.sourceConflict)
    assert(!conflict.publisher.flushValid)
    assert(conflict.cleanupBlockedBySource)
    assert(conflict.state == state)
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
      robSource = source(false, bid1, rid2, 0, tSeq1, uSeq0, NoDst),
      lsuSource = emptySource,
      mapQDepth = mapQDepth)

    assert(!result.publisher.flushValid)
    assert(!result.cleanupActive)
    assert(!result.cleanupBlockedBySource)
    assert(result.state == state)
  }

  test("local block commit releases retired T/U head rows after scalar CleanCMAP") {
    val (state, tSeq0, tSeq1, _, uSeq0, _) = populatedState()
    val retired = State(
      TULinkRenameReference.reportRetired(
        TULinkRenameReference.reportRetired(state.t, tSeq0, dealloc = false),
        tSeq1,
        dealloc = false),
      TULinkRenameReference.reportRetired(state.u, uSeq0, dealloc = false))

    val result = localBlockCommit(retired, valid = true, bid = bid1)

    assert(result.ready)
    assert(result.accepted)
    assert(result.tReleased == Set(0, 1))
    assert(result.uReleased == Set(0))
    assert(result.state.t.usedEntries == 1)
    assert(result.state.u.usedEntries == 1)
  }

  test("local block commit waits behind external commit and recovery flush") {
    val (state, _, _, _, _, _) = populatedState()

    val externalCommit = localBlockCommit(state, valid = true, bid = bid1, externalCommitValid = true)
    assert(!externalCommit.ready)
    assert(!externalCommit.accepted)
    assert(externalCommit.state == state)

    val recoveryFlush = localBlockCommit(state, valid = true, bid = bid1, publisherFlushValid = true)
    assert(!recoveryFlush.ready)
    assert(!recoveryFlush.accepted)
    assert(recoveryFlush.state == state)
  }

  test("IO exposes T/U rename state, publisher command, and source diagnostics") {
    val p = InterfaceParams(robEntries = 8)
    val io = new TULinkRecoveryCleanupPathIO(p, localRegsT = 8, localRegsU = 8, mapQDepth = 8, bidWidth = 16)

    assert(io.robSource.tSeq.value.getWidth == 3)
    assert(io.lsuSource.uSeq.value.getWidth == 3)
    assert(io.selectedFlushSource.tSeq.value.getWidth == 3)
    assert(io.publisherFlushTSeq.value.getWidth == 3)
    assert(io.publisherFlushUSeq.value.getWidth == 3)
    assert(io.tSeq.value.getWidth == 3)
    assert(io.uSeq.value.getWidth == 3)
    assert(io.tMapQValidMask.getWidth == 8)
    assert(io.uMapQValidMask.getWidth == 8)
    assert(io.localBlockCommitBid.value.getWidth == 3)
    assert(io.localBlockCommitReady.getWidth == 1)
    assert(io.localBlockCommitAccepted.getWidth == 1)
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
    assert(sv.contains("module TULinkFlushSourceSelector"))
    assert(sv.contains("module TULinkFlushSequencePublisher"))
    assert(sv.contains("module TULinkRename"))
    assert(sv.contains("io_cleanupBlockedBySource"))
    assert(sv.contains("io_sourceConflict"))
    assert(sv.contains("io_selectedFromRob"))
    assert(sv.contains("io_publisherFlushTSeq_value"))
    assert(sv.contains("io_localBlockCommitReady"))
    assert(sv.contains("io_localBlockCommitAccepted"))
    assert(!sv.contains("ScalarDecodeRenameBridge"))
  }
}
