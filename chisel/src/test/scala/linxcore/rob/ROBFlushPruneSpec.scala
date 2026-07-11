package linxcore.rob

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object ROBFlushPruneReference {
  final case class Id(wrap: Boolean = false, value: Int = 0)
  sealed trait Status
  case object Free extends Status
  case object Allocated extends Status
  case object Renamed extends Status
  case object Issued extends Status
  case object Completed extends Status
  case object Retired extends Status
  case object Fault extends Status
  case object NeedFlush extends Status

  final case class Row(
      valid: Boolean = false,
      status: Status = Free,
      bid: Id = Id(),
      rid: Id = Id(),
      peId: Int = 0,
      stid: Int = 0,
      tid: Int = 0)

  final case class Result(
      directMask: Int,
      pruneMask: Int,
      beforeCommitMask: Int,
      outstandingMask: Int,
      firstPrune: Option[Int],
      commitRebase: Option[Int],
      residentDecrement: Int,
      outstandingDecrement: Int)

  def less(lhs: Id, rhs: Id): Boolean =
    if (lhs.wrap == rhs.wrap) lhs.value < rhs.value else lhs.value > rhs.value

  def lessEqual(lhs: Id, rhs: Id): Boolean =
    less(lhs, rhs) || lhs == rhs

  def lessEqualBidRid(srcBid: Id, srcRid: Id, dstBid: Id, dstRid: Id): Boolean =
    less(srcBid, dstBid) || (srcBid == dstBid && lessEqual(srcRid, dstRid))

  def osdActive(status: Status): Boolean =
    Set[Status](Allocated, Renamed, Issued, Completed, NeedFlush).contains(status)

  def prune(
      rows: Seq[Row],
      deallocHead: Int,
      commitHead: Int,
      flushBid: Id,
      flushRid: Id,
      baseOnBid: Boolean,
      flushValid: Boolean = true,
      flushPeId: Int = 0,
      flushStid: Int = 0,
      flushTid: Int = 0,
      baseOnPE: Boolean = false,
      baseOnThread: Boolean = false): Result = {
    val entries = rows.size
    var found = false
    var seenCommit = false
    var directMask = 0
    var pruneMask = 0
    var beforeCommitMask = 0
    var outstandingMask = 0
    var firstPrune = Option.empty[Int]
    var commitRebase = Option.empty[Int]

    for (offset <- rows.indices) {
      val idx = (deallocHead + offset) & (entries - 1)
      if (idx == commitHead) {
        seenCommit = true
      }
      val row = rows(idx)
      val inScope =
        row.stid == flushStid &&
          (!baseOnPE || row.peId == flushPeId) &&
          (!baseOnThread || row.tid == flushTid)
      val direct =
        flushValid && row.valid && inScope &&
          (if (baseOnBid) lessEqual(flushBid, row.bid)
           else lessEqualBidRid(flushBid, flushRid, row.bid, row.rid))

      if (direct) {
        directMask |= 1 << idx
        if (firstPrune.isEmpty) {
          firstPrune = Some(idx)
        }
      }
      found ||= direct

      if (row.valid && inScope && found) {
        pruneMask |= 1 << idx
        if (!seenCommit) {
          beforeCommitMask |= 1 << idx
          if (commitRebase.isEmpty) {
            commitRebase = Some(idx)
          }
        }
        if (osdActive(row.status)) {
          outstandingMask |= 1 << idx
        }
      }
    }

    Result(
      directMask = directMask,
      pruneMask = pruneMask,
      beforeCommitMask = beforeCommitMask,
      outstandingMask = outstandingMask,
      firstPrune = firstPrune,
      commitRebase = commitRebase,
      residentDecrement = Integer.bitCount(pruneMask),
      outstandingDecrement = Integer.bitCount(outstandingMask)
    )
  }
}

class ROBFlushPruneSpec extends AnyFunSuite {
  import ROBFlushPruneReference._

  private def live(status: Status, bid: Int, rid: Int): Row =
    Row(valid = true, status = status, bid = Id(value = bid), rid = Id(value = rid))

  test("base-on-BID prune starts at the first matching BID and clears younger valid rows") {
    val rows = Seq(
      live(Completed, bid = 1, rid = 0),
      live(Allocated, bid = 2, rid = 0),
      live(Issued, bid = 3, rid = 0),
      live(Retired, bid = 4, rid = 0),
      Row(),
      live(NeedFlush, bid = 5, rid = 0),
      Row(),
      Row()
    )

    val result = prune(rows, deallocHead = 0, commitHead = 4, flushBid = Id(value = 3), flushRid = Id(), baseOnBid = true)

    assert(result.directMask == 0x2c)
    assert(result.pruneMask == 0x2c)
    assert(result.beforeCommitMask == 0x0c)
    assert(result.outstandingMask == 0x24)
    assert(result.firstPrune.contains(2))
    assert(result.commitRebase.contains(2))
    assert(result.residentDecrement == 3)
    assert(result.outstandingDecrement == 2)
  }

  test("RID-based prune keeps same-BID older RIDs and prunes from the target RID") {
    val rows = Seq(
      live(Completed, bid = 2, rid = 0),
      live(Completed, bid = 2, rid = 1),
      live(Completed, bid = 2, rid = 2),
      live(Completed, bid = 3, rid = 0),
      Row(),
      Row(),
      Row(),
      Row()
    )

    val result = prune(rows, deallocHead = 0, commitHead = 0, flushBid = Id(value = 2), flushRid = Id(value = 1), baseOnBid = false)

    assert(result.directMask == 0x0e)
    assert(result.pruneMask == 0x0e)
    assert(result.beforeCommitMask == 0)
    assert(result.firstPrune.contains(1))
    assert(result.commitRebase.isEmpty)
  }

  test("scan wraps from dealloc head and skips invalid rows after pruning starts") {
    val rows = Seq(
      live(Renamed, bid = 5, rid = 0),
      Row(),
      live(Completed, bid = 6, rid = 0),
      Row(),
      Row(),
      Row(),
      live(Allocated, bid = 3, rid = 0),
      live(Issued, bid = 4, rid = 0)
    )

    val result = prune(rows, deallocHead = 6, commitHead = 2, flushBid = Id(value = 4), flushRid = Id(), baseOnBid = true)

    assert(result.directMask == 0x85)
    assert(result.pruneMask == 0x85)
    assert(result.beforeCommitMask == 0x81)
    assert(result.outstandingMask == 0x85)
    assert(result.firstPrune.contains(7))
    assert(result.commitRebase.contains(7))
    assert(result.residentDecrement == 3)
  }

  test("outstanding decrement excludes retired rows but resident decrement includes them") {
    val rows = Seq(
      live(Retired, bid = 4, rid = 0),
      live(Fault, bid = 5, rid = 0),
      live(NeedFlush, bid = 6, rid = 0),
      live(Completed, bid = 7, rid = 0),
      Row(),
      Row(),
      Row(),
      Row()
    )

    val result = prune(rows, deallocHead = 0, commitHead = 7, flushBid = Id(value = 4), flushRid = Id(), baseOnBid = true)

    assert(result.pruneMask == 0x0f)
    assert(result.outstandingMask == 0x0c)
    assert(result.residentDecrement == 4)
    assert(result.outstandingDecrement == 2)
  }

  test("invalid flush requests do not prune any row") {
    val rows = Seq.fill(8)(live(Allocated, bid = 1, rid = 0))
    val result = prune(rows, deallocHead = 0, commitHead = 0, flushBid = Id(value = 1), flushRid = Id(), baseOnBid = true, flushValid = false)

    assert(result.directMask == 0)
    assert(result.pruneMask == 0)
    assert(result.firstPrune.isEmpty)
    assert(result.residentDecrement == 0)
    assert(result.outstandingDecrement == 0)
  }

  test("prune propagation stays within STID and optional PE/thread scope") {
    val rows = Seq(
      live(Completed, bid = 1, rid = 0),
      live(Allocated, bid = 2, rid = 1).copy(peId = 1, tid = 1),
      live(Issued, bid = 2, rid = 2).copy(stid = 1),
      live(NeedFlush, bid = 3, rid = 3).copy(peId = 1, tid = 1),
      Row(), Row(), Row(), Row()
    )

    val result = prune(
      rows,
      deallocHead = 0,
      commitHead = 0,
      flushBid = Id(value = 2),
      flushRid = Id(),
      baseOnBid = true,
      flushPeId = 1,
      flushStid = 0,
      flushTid = 1,
      baseOnPE = true,
      baseOnThread = true
    )

    assert(result.directMask == 0x0a)
    assert(result.pruneMask == 0x0a)
    assert(result.residentDecrement == 2)
  }

  test("Chisel ROBFlushPrune elaborates with model-derived masks and counts") {
    val sv = ChiselStage.emitSystemVerilog(new ROBFlushPrune(entries = 8))

    assert(sv.contains("module ROBFlushPrune"))
    assert(sv.contains("io_directMatchMask"))
    assert(sv.contains("io_pruneMask"))
    assert(sv.contains("io_outstandingPruneMask"))
    assert(sv.contains("io_rows_0_stid"))
    assert(sv.contains("io_rows_0_peId"))
    assert(sv.contains("io_rows_0_tid"))
    assert(sv.contains("io_commitRebaseNeeded"))
  }
}
