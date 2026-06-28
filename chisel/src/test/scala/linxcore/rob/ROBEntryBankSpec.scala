package linxcore.rob

import circt.stage.ChiselStage
import linxcore.common.DestinationKind
import linxcore.commit.CommitTraceParams
import org.scalatest.funsuite.AnyFunSuite

object ROBEntryBankReference {
  sealed trait Status
  case object Free extends Status
  case object Allocated extends Status
  case object Renamed extends Status
  case object Issued extends Status
  case object Completed extends Status
  case object Retired extends Status
  case object Fault extends Status
  case object NeedFlush extends Status
  sealed trait TUDst
  case object NoTUDst extends TUDst
  case object TDst extends TUDst
  case object UDst extends TUDst

  final case class Row(
      pc: BigInt,
      insn: BigInt,
      length: Int,
      bid: BigInt,
      gid: BigInt,
      rid: BigInt,
      blockBid: BigInt,
      robValue: Int = -1,
      commitSlot: Int = -1)

  final case class Id(valid: Boolean = true, wrap: Boolean = false, value: Int = 0)
  final case class TUSidecar(
      stid: Int = 0,
      tSeq: Id = Id(),
      uSeq: Id = Id(),
      dst: TUDst = NoTUDst,
      isLast: Boolean = false)
  final case class TUSource(
      valid: Boolean,
      bid: Id,
      rid: Id,
      stid: Int,
      tSeq: Id,
      uSeq: Id,
      dst: TUDst)
  final case class TURetireSource(
      valid: Boolean,
      bid: Id,
      gid: Id,
      rid: Id,
      stid: Int,
      isLast: Boolean,
      tSeq: Id,
      uSeq: Id,
      dst: TUDst)

  private final case class Entry(row: Row, status: Status, bid: Id, gid: Id, rid: Id, tu: TUSidecar)

  final class Model(entries: Int, commitWidth: Int) {
    require(entries > 1 && (entries & (entries - 1)) == 0)
    require(commitWidth > 0 && commitWidth <= entries)

    private val table = Array.fill[Option[Entry]](entries)(None)
    private var allocPtr = 0
    private var allocWrap = false
    private var commitPtr = 0
    private var commitWrap = false
    private var deallocPtr = 0
    private var deallocWrap = false
    private var count = 0
    private var outstanding = 0

    private def advance(value: Int, wrap: Boolean): (Int, Boolean) = {
      val next = (value + 1) & (entries - 1)
      (next, wrap ^ (next == 0))
    }

    private def duplicateIdentity(row: Row): Boolean =
      table.flatten.exists(e => (e.row.bid, e.row.gid, e.row.rid) == ((row.bid, row.gid, row.rid)))

    private def scanWrapFor(value: Int, headValue: Int, headWrap: Boolean): Boolean =
      headWrap ^ (value < headValue)

    private def normalizedBid(row: Row): Id =
      Id(value = (row.bid.toInt & (entries - 1)))

    private def normalizedGid(row: Row): Id =
      Id(value = (row.gid.toInt & (entries - 1)))

    private def less(lhs: Id, rhs: Id): Boolean =
      if (lhs.wrap == rhs.wrap) lhs.value < rhs.value else lhs.value > rhs.value

    private def equal(lhs: Id, rhs: Id): Boolean =
      lhs.wrap == rhs.wrap && lhs.value == rhs.value

    private def lessEqual(lhs: Id, rhs: Id): Boolean =
      less(lhs, rhs) || equal(lhs, rhs)

    private def lessEqualBidRid(srcBid: Id, srcRid: Id, dstBid: Id, dstRid: Id): Boolean =
      less(srcBid, dstBid) || (equal(srcBid, dstBid) && lessEqual(srcRid, dstRid))

    private def osdActive(status: Status): Boolean =
      status == Allocated || status == Renamed || status == Issued || status == Completed || status == NeedFlush

    def size: Int = count

    def outstandingCount: Int = outstanding

    def allocPointer: Int = allocPtr

    def commitPointer: Int = commitPtr

    def deallocPointer: Int = deallocPtr

    def statusAt(value: Int): Status =
      table(value).map(_.status).getOrElse(Free)

    def alloc(row: Row, bid: Option[Id] = None, tu: TUSidecar = TUSidecar()): Option[Int] = {
      if (count == entries || duplicateIdentity(row)) {
        return None
      }
      val rob = allocPtr
      val rid = Id(wrap = allocWrap, value = allocPtr)
      table(allocPtr) = Some(
        Entry(row.copy(robValue = rob), Allocated, bid.getOrElse(normalizedBid(row)), normalizedGid(row), rid, tu)
      )
      val (nextAllocPtr, nextAllocWrap) = advance(allocPtr, allocWrap)
      allocPtr = nextAllocPtr
      allocWrap = nextAllocWrap
      count += 1
      outstanding += 1
      Some(rob)
    }

    def robTUSource(flushBid: Id, flushRid: Id, stid: Int, baseOnBid: Boolean): Option[TUSource] = {
      if (baseOnBid) {
        return None
      }
      table.flatten.collectFirst {
        case Entry(_, status, bid, _, rid, tu)
            if status != Free && equal(bid, flushBid) && equal(rid, flushRid) && tu.stid == stid =>
          TUSource(valid = true, bid = bid, rid = rid, stid = stid, tSeq = tu.tSeq, uSeq = tu.uSeq, dst = tu.dst)
      }
    }

    def complete(robValue: Int): Boolean =
      table(robValue) match {
        case Some(entry)
            if entry.status == Allocated || entry.status == Renamed ||
              entry.status == Issued || entry.status == Completed =>
          table(robValue) = Some(entry.copy(status = Completed))
          true
        case _ =>
          false
      }

    def commit(): Seq[Row] = {
      val out = collection.mutable.ArrayBuffer.empty[Row]
      while (out.size < commitWidth) {
        table(commitPtr) match {
          case Some(entry) if entry.status == Completed =>
            out += entry.row.copy(commitSlot = out.size)
            table(commitPtr) = Some(entry.copy(status = Retired))
            val (nextCommitPtr, nextCommitWrap) = advance(commitPtr, commitWrap)
            commitPtr = nextCommitPtr
            commitWrap = nextCommitWrap
            outstanding -= 1
          case _ =>
            return out.toSeq
        }
      }
      out.toSeq
    }

    def deallocTURetireSources(ready: Boolean = true): Seq[TURetireSource] = {
      if (!ready) {
        return Seq.empty
      }
      val out = collection.mutable.ArrayBuffer.empty[TURetireSource]
      while (out.size < commitWidth) {
        val idx = (deallocPtr + out.size) & (entries - 1)
        table(idx) match {
          case Some(Entry(_, Retired, bid, gid, rid, tu)) =>
            out += TURetireSource(
              valid = true,
              bid = bid,
              gid = gid,
              rid = rid,
              stid = tu.stid,
              isLast = tu.isLast,
              tSeq = tu.tSeq,
              uSeq = tu.uSeq,
              dst = tu.dst
            )
          case _ =>
            return out.toSeq
        }
      }
      out.toSeq
    }

    def dealloc(ready: Boolean = true): Seq[Int] = {
      if (!ready) {
        return Seq.empty
      }
      val out = collection.mutable.ArrayBuffer.empty[Int]
      while (out.size < commitWidth) {
        table(deallocPtr) match {
          case Some(entry) if entry.status == Retired =>
            out += deallocPtr
            table(deallocPtr) = None
            val (nextDeallocPtr, nextDeallocWrap) = advance(deallocPtr, deallocWrap)
            deallocPtr = nextDeallocPtr
            deallocWrap = nextDeallocWrap
            count -= 1
          case _ =>
            return out.toSeq
        }
      }
      out.toSeq
    }

    def flush(flushBid: Id, flushRid: Id, baseOnBid: Boolean): Seq[Int] = {
      var found = false
      var overCommitPtr = false
      val pruned = collection.mutable.ArrayBuffer.empty[Int]

      for (offset <- table.indices) {
        val idx = (deallocPtr + offset) & (entries - 1)
        if (idx == commitPtr) {
          overCommitPtr = true
        }

        val direct = table(idx).exists { entry =>
          if (baseOnBid) {
            lessEqual(flushBid, entry.bid)
          } else {
            lessEqualBidRid(flushBid, flushRid, entry.bid, entry.rid)
          }
        }

        if (direct && !found) {
          allocPtr = idx
          allocWrap = scanWrapFor(idx, deallocPtr, deallocWrap)
          found = true
          if (!overCommitPtr) {
            commitPtr = idx
            commitWrap = scanWrapFor(idx, deallocPtr, deallocWrap)
          }
        }

        if (found) {
          table(idx).foreach { entry =>
            pruned += idx
            if (osdActive(entry.status)) {
              outstanding -= 1
            }
            table(idx) = None
            count -= 1
          }
        }
      }

      if (count == 0) {
        commitPtr = allocPtr
        commitWrap = allocWrap
        deallocPtr = allocPtr
        deallocWrap = allocWrap
      }
      if (outstanding == 0) {
        commitPtr = allocPtr
        commitWrap = allocWrap
      }

      pruned.toSeq
    }
  }
}

class ROBEntryBankSpec extends AnyFunSuite {
  import ROBEntryBankReference._

  private def id(value: Int, wrap: Boolean = false): Id =
    Id(wrap = wrap, value = value)

  private def row(n: Int): Row =
    Row(
      pc = 0x1000 + (n * 4),
      insn = 0x13,
      length = 4,
      bid = 1,
      gid = 0,
      rid = n,
      blockBid = 0x100 + n
    )

  test("reference keeps commit and dealloc as separate pointer walks") {
    val rob = new Model(entries = 8, commitWidth = 2)
    val r0 = rob.alloc(row(0)).get

    assert(rob.statusAt(r0) == Allocated)
    assert(rob.size == 1)
    assert(rob.outstandingCount == 1)

    assert(rob.complete(r0))
    val committed = rob.commit()
    assert(committed.map(_.rid) == Seq(0))
    assert(committed.map(_.commitSlot) == Seq(0))
    assert(rob.statusAt(r0) == Retired)
    assert(rob.size == 1)
    assert(rob.outstandingCount == 0)

    assert(rob.dealloc() == Seq(r0))
    assert(rob.statusAt(r0) == Free)
    assert(rob.size == 0)
  }

  test("reference blocks commit on incomplete head even when younger rows complete") {
    val rob = new Model(entries = 8, commitWidth = 2)
    val r0 = rob.alloc(row(0)).get
    val r1 = rob.alloc(row(1)).get
    val r2 = rob.alloc(row(2)).get

    assert(rob.complete(r1))
    assert(rob.complete(r2))
    assert(rob.commit().isEmpty)

    assert(rob.complete(r0))
    assert(rob.commit().map(_.rid) == Seq(0, 1))
    assert(rob.commit().map(_.rid) == Seq(2))
  }

  test("reference rejects duplicate identity until deallocation frees the row") {
    val rob = new Model(entries = 8, commitWidth = 2)
    val first = rob.alloc(row(4)).get
    assert(rob.alloc(row(4)).isEmpty)

    assert(rob.complete(first))
    assert(rob.commit().map(_.rid) == Seq(4))
    assert(rob.alloc(row(4)).isEmpty)

    assert(rob.dealloc() == Seq(first))
    assert(rob.alloc(row(4)).nonEmpty)
  }

  test("reference deallocReady can hold retired rows and keep the bank full") {
    val rob = new Model(entries = 2, commitWidth = 2)
    val r0 = rob.alloc(row(0)).get
    val r1 = rob.alloc(row(1)).get
    assert(rob.complete(r0))
    assert(rob.complete(r1))

    assert(rob.commit().map(_.rid) == Seq(0, 1))
    assert(rob.size == 2)
    assert(rob.outstandingCount == 0)
    assert(rob.alloc(row(2)).isEmpty)
    assert(rob.dealloc(ready = false).isEmpty)
    assert(rob.size == 2)

    assert(rob.dealloc() == Seq(r0, r1))
    assert(rob.alloc(row(2)).nonEmpty)
  }

  test("reference ignores completion for free and retired rows") {
    val rob = new Model(entries = 8, commitWidth = 2)
    assert(!rob.complete(0))

    val r0 = rob.alloc(row(0)).get
    assert(rob.complete(r0))
    assert(rob.commit().map(_.rid) == Seq(0))
    assert(!rob.complete(r0))
  }

  test("reference flush prunes target RID and reuses the first pruned slot for allocation") {
    val rob = new Model(entries = 8, commitWidth = 2)
    val r0 = rob.alloc(row(0)).get
    rob.alloc(row(1)).get
    val r2 = rob.alloc(row(2)).get
    val r3 = rob.alloc(row(3)).get

    assert(rob.complete(r0))
    assert(
      rob.flush(flushBid = id(1), flushRid = id(2), baseOnBid = false) == Seq(r2, r3)
    )
    assert(rob.size == 2)
    assert(rob.outstandingCount == 2)
    assert(rob.allocPointer == r2)
    assert(rob.commitPointer == r0)
    assert(rob.statusAt(r2) == Free)

    assert(rob.commit().map(_.rid) == Seq(0))
    assert(rob.alloc(row(4)).contains(r2))
  }

  test("reference flush clears retired rows without decrementing outstanding work") {
    val rob = new Model(entries = 8, commitWidth = 2)
    val r0 = rob.alloc(row(0)).get
    val r1 = rob.alloc(row(1)).get
    assert(rob.complete(r0))
    assert(rob.complete(r1))
    assert(rob.commit().map(_.rid) == Seq(0, 1))
    assert(rob.outstandingCount == 0)
    assert(rob.size == 2)

    assert(
      rob.flush(flushBid = id(1), flushRid = id(0), baseOnBid = false) == Seq(r0, r1)
    )
    assert(rob.outstandingCount == 0)
    assert(rob.size == 0)
    assert(rob.allocPointer == r0)
    assert(rob.commitPointer == r0)
    assert(rob.deallocPointer == r0)
  }

  test("reference flush uses native row IDs instead of commit trace identity") {
    val rob = new Model(entries = 8, commitWidth = 2)
    rob.alloc(row(0).copy(rid = 99), bid = Some(id(1))).get
    rob.alloc(row(1).copy(rid = 100), bid = Some(id(1))).get
    val r2 = rob.alloc(row(2).copy(rid = 101), bid = Some(id(1))).get
    val r3 = rob.alloc(row(3).copy(rid = 102), bid = Some(id(1))).get

    assert(
      rob.flush(flushBid = id(1), flushRid = id(2), baseOnBid = false) == Seq(r2, r3)
    )
    assert(rob.size == 2)
    assert(rob.allocPointer == r2)
  }

  test("reference exposes ROB T/U sidecar only for exact non-base source matches") {
    val rob = new Model(entries = 8, commitWidth = 2)
    rob.alloc(
      row(0),
      bid = Some(id(1)),
      tu = TUSidecar(stid = 0, tSeq = id(5), uSeq = id(6), dst = NoTUDst)
    ).get
    val r1 = rob.alloc(
      row(1),
      bid = Some(id(1)),
      tu = TUSidecar(stid = 1, tSeq = id(2, wrap = true), uSeq = id(3), dst = TDst)
    ).get

    assert(
      rob.robTUSource(flushBid = id(1), flushRid = id(r1), stid = 1, baseOnBid = false)
        .contains(TUSource(true, id(1), id(r1), 1, id(2, wrap = true), id(3), TDst))
    )
    assert(rob.robTUSource(flushBid = id(1), flushRid = id(r1), stid = 0, baseOnBid = false).isEmpty)
    assert(rob.robTUSource(flushBid = id(1), flushRid = id(r1), stid = 1, baseOnBid = true).isEmpty)

    assert(rob.flush(flushBid = id(1), flushRid = id(r1), baseOnBid = false) == Seq(r1))
    assert(rob.robTUSource(flushBid = id(1), flushRid = id(r1), stid = 1, baseOnBid = false).isEmpty)
  }

  test("reference exposes dealloc-row T/U retire sources with native BID/GID/RID") {
    val rob = new Model(entries = 8, commitWidth = 2)
    val r0 = rob.alloc(
      row(0).copy(gid = 3),
      bid = Some(id(2)),
      tu = TUSidecar(stid = 1, tSeq = id(4), uSeq = id(5), dst = TDst, isLast = false)
    ).get
    val r1 = rob.alloc(
      row(1).copy(gid = 3),
      bid = Some(id(2)),
      tu = TUSidecar(stid = 1, tSeq = id(6), uSeq = id(7), dst = UDst, isLast = true)
    ).get

    assert(rob.complete(r0))
    assert(rob.complete(r1))
    assert(rob.commit().map(_.rid) == Seq(0, 1))
    assert(rob.deallocTURetireSources() == Seq(
      TURetireSource(true, id(2), id(3), id(r0), 1, false, id(4), id(5), TDst),
      TURetireSource(true, id(2), id(3), id(r1), 1, true, id(6), id(7), UDst)
    ))
    assert(rob.dealloc() == Seq(r0, r1))
  }

  test("Chisel ROBEntryBank elaborates with status masks and commit monitor outputs") {
    val sv = ChiselStage.emitSystemVerilog(
      new ROBEntryBank(
        entries = 8,
        traceParams = CommitTraceParams(commitWidth = 2, robValueWidth = 3),
        mapQDepth = 8,
        stidWidth = 4
      )
    )

    assert(sv.contains("module ROBEntryBank"))
    assert(sv.contains("io_deallocReady"))
    assert(sv.contains("io_allocBid"))
    assert(sv.contains("io_allocGid"))
    assert(sv.contains("io_allocIsLast"))
    assert(sv.contains("io_allocTSeq_value"))
    assert(sv.contains("io_allocTUDstKind"))
    assert(sv.contains("io_robTULinkSource_tSeq_value"))
    assert(sv.contains("io_deallocTURetireSource_0_tSeq_value"))
    assert(sv.contains("io_deallocTURetireSource_0_isLast"))
    assert(sv.contains("io_robTULinkSourceMatched"))
    assert(sv.contains("io_completedMask"))
    assert(sv.contains("io_retiredMask"))
    assert(sv.contains("io_flushPruneMask"))
    assert(sv.contains("io_flushCommitRebased"))
    assert(sv.contains("io_commitContractError"))
    assert(sv.contains("CommitTraceMonitor"))
    assert(DestinationKind.T.asUInt.litValue == 2)
    assert(DestinationKind.U.asUInt.litValue == 3)
  }
}
