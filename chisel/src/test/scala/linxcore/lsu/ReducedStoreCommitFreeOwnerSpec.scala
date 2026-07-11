package linxcore.lsu

import circt.stage.ChiselStage
import linxcore.commit.CommitTraceParams
import org.scalatest.funsuite.AnyFunSuite

object ReducedStoreCommitFreeOwnerReference {
  final case class Id(value: Int = 0, wrap: Boolean = false, valid: Boolean = true)
  sealed trait Status
  case object Wait extends Status
  case object Commit extends Status
  case object Idle extends Status
  final case class Row(
      index: Int,
      bid: Id = Id(),
      gid: Id = Id(),
      rid: Id = Id(),
      stid: Int = 0,
      status: Status = Wait,
      storeAll: Boolean = true,
      addrReady: Boolean = true,
      dataReady: Boolean = true)
  final case class CommitRow(
      bid: Id = Id(),
      gid: Id = Id(),
      rid: Id = Id(),
      rob: Id = Id(valid = false),
      store: Boolean = true,
      blockBid: Int = 0,
      blockBidValid: Boolean = true)
  final case class StepResult(
      seen: Boolean,
      matched: Boolean,
      unmatched: Boolean,
      blockedByNonFlush: Boolean,
      matchMask: Int,
      markValid: Boolean,
      markIndex: Option[Int],
      freeValid: Boolean,
      freeIndex: Option[Int],
      pendingMark: Int,
      pendingFree: Int)

  final class Model(entries: Int) {
    private var pendingMark = 0
    private var pendingFree = 0
    private var pendingCommitIdentities = Vector.empty[CommitRow]

    private def markable(row: Row, activeStid: Int): Boolean =
      row.rid.valid && row.stid == activeStid && row.status == Wait &&
        row.storeAll && row.addrReady && row.dataReady

    private def first(mask: Int): Option[Int] =
      (0 until entries).find(idx => ((mask >> idx) & 1) == 1)

    private def sameCommitIdentity(row: Row, commit: CommitRow): Boolean =
      row.bid.valid && row.gid.valid && row.rid.valid &&
        row.bid.value == commit.bid.value &&
        row.gid.value == commit.gid.value &&
        row.rid.value == commit.rid.value

    def step(
        rows: Seq[Row],
        commits: Seq[CommitRow] = Seq.empty,
        activeStid: Int = 0,
        markAccepted: Boolean = false,
        freeAccepted: Boolean = false,
        enable: Boolean = true,
        flush: Boolean = false,
        directFreeEnable: Boolean = true,
        nonFlushHead: Int = 0,
        nonFlushPrefixCount: Int = Int.MaxValue): StepResult = {
      val markValid = enable && !flush && pendingMark != 0
      val markIndex = first(pendingMark)
      val markOh = markIndex.map(1 << _).getOrElse(0)
      val freeValid = enable && directFreeEnable && !flush && pendingFree != 0
      val freeIndex = first(pendingFree)
      val freeOh = freeIndex.map(1 << _).getOrElse(0)

      val storeCommits = commits.filter(_.store)
      def blockSafe(commit: CommitRow): Boolean =
        commit.blockBidValid && commit.blockBid - nonFlushHead >= 0 &&
          commit.blockBid - nonFlushHead < nonFlushPrefixCount
      val markSources = (pendingCommitIdentities ++ storeCommits).filter(blockSafe)
      val slotMatched = storeCommits.map { commit =>
        blockSafe(commit) && rows.exists(row => markable(row, activeStid) && sameCommitIdentity(row, commit))
      }
      val alreadyPending = storeCommits.map { commit =>
        pendingCommitIdentities.exists(pending =>
          pending.bid.value == commit.bid.value &&
            pending.gid.value == commit.gid.value &&
            pending.rid.value == commit.rid.value)
      }
      val matchMask = rows.foldLeft(0) { case (mask, row) =>
        if (markable(row, activeStid) && markSources.exists(commit => sameCommitIdentity(row, commit))) mask | (1 << row.index) else mask
      }
      val pendingMatched = pendingCommitIdentities.filter { commit =>
        blockSafe(commit) && rows.exists(row => markable(row, activeStid) && sameCommitIdentity(row, commit))
      }
      val newlyPending = storeCommits.zip(slotMatched).zip(alreadyPending).collect {
        case ((commit, matched), isPending) if !matched && !isPending => commit
      }

      if (!enable || flush) {
        pendingMark = 0
        pendingFree = 0
        pendingCommitIdentities = Vector.empty
      } else {
        val markClear = if (markAccepted) markOh else 0
        val freeAdd = if (markAccepted) markOh else 0
        val freeClear = if (freeAccepted) freeOh else 0
        pendingMark = (pendingMark | matchMask) & ~markClear
        pendingFree =
          if (directFreeEnable) (pendingFree | freeAdd) & ~freeClear
          else 0
        pendingCommitIdentities =
          (pendingCommitIdentities.filterNot(pendingMatched.contains) ++ newlyPending).take(entries)
      }

      StepResult(
        seen = storeCommits.nonEmpty,
        matched = slotMatched.contains(true),
        unmatched = slotMatched.contains(false),
        blockedByNonFlush = storeCommits.exists(commit => !blockSafe(commit)),
        matchMask = matchMask,
        markValid = markValid,
        markIndex = markIndex.filter(_ => markValid),
        freeValid = freeValid,
        freeIndex = freeIndex.filter(_ => freeValid),
        pendingMark = pendingMark,
        pendingFree = pendingFree
      )
    }
  }
}

class ReducedStoreCommitFreeOwnerSpec extends AnyFunSuite {
  import ReducedStoreCommitFreeOwnerReference._

  test("reference maps committed store ROB rows to pending STQ mark bits") {
    val model = new Model(entries = 8)
    val rows = Seq(Row(index = 3, bid = Id(value = 2), gid = Id(value = 0), rid = Id(value = 5, wrap = true), stid = 1))

    val captured = model.step(
      rows = rows,
      commits = Seq(CommitRow(bid = Id(value = 2), gid = Id(value = 0), rid = Id(value = 5), rob = Id(value = 5, wrap = false))),
      activeStid = 1)
    assert(captured.seen)
    assert(captured.matched)
    assert(!captured.unmatched)
    assert(captured.matchMask == (1 << 3))
    assert(captured.pendingMark == (1 << 3))

    val mark = model.step(rows = rows, activeStid = 1)
    assert(mark.markValid)
    assert(mark.markIndex.contains(3))
  }

  test("reference matches committed stores by commit identity rather than physical ROB sideband") {
    val model = new Model(entries = 8)
    val rows = Seq(Row(index = 4, bid = Id(value = 3, wrap = true), gid = Id(value = 1), rid = Id(value = 6, wrap = true)))

    val captured = model.step(
      rows = rows,
      commits = Seq(CommitRow(bid = Id(value = 3), gid = Id(value = 1), rid = Id(value = 6), rob = Id(value = 2, wrap = false))))

    assert(captured.seen)
    assert(captured.matched)
    assert(!captured.unmatched)
    assert(captured.matchMask == (1 << 4))
  }

  test("reference turns accepted marks into later frees and clears accepted frees") {
    val model = new Model(entries = 8)
    val waitRows = Seq(Row(index = 2, rid = Id(value = 2), stid = 0))

    model.step(rows = waitRows, commits = Seq(CommitRow(rid = Id(value = 2))))
    val marked = model.step(rows = waitRows, markAccepted = true)
    assert(marked.markValid)
    assert(marked.pendingMark == 0)
    assert(marked.pendingFree == (1 << 2))

    val commitRows = Seq(Row(index = 2, rid = Id(value = 2), status = Commit))
    val free = model.step(rows = commitRows)
    assert(free.freeValid)
    assert(free.freeIndex.contains(2))

    val cleared = model.step(rows = commitRows, freeAccepted = true)
    assert(cleared.pendingFree == 0)
  }

  test("reference buffers committed store identity until the STQ row becomes markable") {
    val model = new Model(entries = 8)
    val store = CommitRow(bid = Id(value = 4), gid = Id(value = 0), rid = Id(value = 3))

    val earlyCommit = model.step(rows = Seq.empty, commits = Seq(store))
    assert(earlyCommit.seen)
    assert(!earlyCommit.matched)
    assert(earlyCommit.unmatched)
    assert(earlyCommit.pendingMark == 0)

    val rowAppears = model.step(rows = Seq(Row(index = 6, bid = Id(value = 4), gid = Id(value = 0), rid = Id(value = 3))))
    assert(rowAppears.matchMask == (1 << 6))
    assert(rowAppears.pendingMark == (1 << 6))

    val mark = model.step(rows = Seq(Row(index = 6, bid = Id(value = 4), gid = Id(value = 0), rid = Id(value = 3))))
    assert(mark.markValid)
    assert(mark.markIndex.contains(6))
  }

  test("reference retains a committed store until its full block BID enters the strong prefix") {
    val model = new Model(entries = 8)
    val row = Row(index = 5, bid = Id(value = 2), gid = Id(value = 1), rid = Id(value = 6))
    val store = CommitRow(
      bid = Id(value = 2),
      gid = Id(value = 1),
      rid = Id(value = 6),
      blockBid = 9)

    val blocked = model.step(
      rows = Seq(row),
      commits = Seq(store),
      nonFlushHead = 8,
      nonFlushPrefixCount = 1)
    assert(blocked.blockedByNonFlush)
    assert(!blocked.matched)
    assert(blocked.pendingMark == 0)

    val released = model.step(
      rows = Seq(row),
      nonFlushHead = 8,
      nonFlushPrefixCount = 2)
    assert(released.matchMask == (1 << 5))
    assert(released.pendingMark == (1 << 5))
  }

  test("reference can disable direct free tracking after mark acceptance") {
    val model = new Model(entries = 8)
    val rows = Seq(Row(index = 2, rid = Id(value = 2), stid = 0))

    model.step(rows = rows, commits = Seq(CommitRow(rid = Id(value = 2))))
    val marked = model.step(rows = rows, markAccepted = true, directFreeEnable = false)

    assert(marked.markValid)
    assert(marked.pendingMark == 0)
    assert(marked.pendingFree == 0)
    assert(!model.step(rows = rows, directFreeEnable = false).freeValid)
  }

  test("reference reports unmatched store commits and ignores non-ready rows") {
    val model = new Model(entries = 4)
    val rows = Seq(Row(index = 1, rid = Id(value = 1), dataReady = false))

    val result = model.step(rows = rows, commits = Seq(CommitRow(rid = Id(value = 1))))
    assert(result.seen)
    assert(!result.matched)
    assert(result.unmatched)
    assert(result.matchMask == 0)
    assert(result.pendingMark == 0)
  }

  test("ReducedStoreCommitFreeOwner elaborates mark and free diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(
      new ReducedStoreCommitFreeOwner(
        entries = 8,
        traceParams = CommitTraceParams(commitWidth = 2, robValueWidth = 3),
        mapQDepth = 8)
    )

    assert(sv.contains("module ReducedStoreCommitFreeOwner"))
    assert(sv.contains("io_directFreeEnable"))
    assert(sv.contains("io_markCommitValid"))
    assert(sv.contains("io_commitFreeValid"))
    assert(sv.contains("io_commitStoreMatched"))
    assert(sv.contains("io_nonFlushHeadBid"))
    assert(sv.contains("io_nonFlushPrefixCount"))
    assert(sv.contains("io_commitStoreBlockedByNonFlush"))
    assert(sv.contains("io_pendingMarkMask"))
    assert(sv.contains("io_pendingFreeMask"))
    assert(sv.contains("io_idle"))
  }
}
