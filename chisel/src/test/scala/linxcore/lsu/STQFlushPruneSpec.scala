package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object STQFlushPruneReference {
  final case class Id(valid: Boolean = true, wrap: Boolean = false, value: Int = 0)

  sealed trait Status
  case object Wait extends Status
  case object Commit extends Status
  case object Miss extends Status
  case object L2Wait extends Status
  case object Idle extends Status
  case object Resolved extends Status

  final case class Flush(
      valid: Boolean = true,
      stid: Int = 0,
      peId: Int = 0,
      tid: Int = 0,
      bid: Id = Id(),
      rid: Id = Id(),
      gid: Id = Id(),
      lsId: Id = Id(),
      baseOnBid: Boolean = false,
      baseOnGroup: Boolean = false,
      baseOnPE: Boolean = false,
      baseOnThread: Boolean = false,
      fullLsIdValid: Boolean = true,
      fullLsId: Option[BigInt] = None,
      lsidWidth: Int = 32)

  final case class Row(
      valid: Boolean = true,
      status: Status = Wait,
      stid: Int = 0,
      peId: Int = 0,
      tid: Int = 0,
      bid: Id = Id(),
      gid: Id = Id(),
      lsId: Id = Id(),
      fullLsId: Option[BigInt] = None)

  final case class Result(matchMask: Int, freeMask: Int, statusBlockedMask: Int, freeCount: Int)

  def less(lhs: Id, rhs: Id): Boolean =
    if (lhs.wrap == rhs.wrap) lhs.value < rhs.value else lhs.value > rhs.value

  def lessEqual(lhs: Id, rhs: Id): Boolean =
    less(lhs, rhs) || lhs == rhs

  def lessEqualBidLs(srcBid: Id, srcLsId: Id, dstBid: Id, dstLsId: Id): Boolean =
    less(srcBid, dstBid) || (srcBid == dstBid && lessEqual(srcLsId, dstLsId))

  def lessEqualBidGroupLs(srcBid: Id, srcGid: Id, srcLsId: Id, dstBid: Id, dstGid: Id, dstLsId: Id): Boolean =
    less(srcBid, dstBid) ||
      (srcBid == dstBid &&
        (less(srcGid, dstGid) || (srcGid == dstGid && lessEqual(srcLsId, dstLsId))))

  def lessFull(lhs: BigInt, rhs: BigInt, width: Int): Boolean = {
    val mask = (BigInt(1) << width) - 1
    val distance = (rhs - lhs) & mask
    lhs != rhs && distance < (BigInt(1) << (width - 1))
  }

  def lessEqualFull(lhs: BigInt, rhs: BigInt, width: Int): Boolean =
    lhs == rhs || lessFull(lhs, rhs, width)

  private def flushLsId(flush: Flush): BigInt = flush.fullLsId.getOrElse(BigInt(flush.lsId.value))
  private def rowLsId(row: Row): BigInt = row.fullLsId.getOrElse(BigInt(row.lsId.value))

  def matches(flush: Flush, row: Row): Boolean = {
    if (!flush.valid || !row.valid) {
      return false
    }
    if (flush.stid != row.stid) {
      return false
    }
    if (flush.baseOnPE && flush.peId != row.peId) {
      return false
    }
    if (flush.baseOnThread && flush.tid != row.tid) {
      return false
    }
    if (flush.baseOnBid) {
      return lessEqual(flush.bid, row.bid)
    }
    if (flush.baseOnGroup) {
      return lessEqual(flush.bid, row.bid) ||
        (flush.fullLsIdValid && (less(flush.bid, row.bid) ||
          (flush.bid == row.bid &&
            (less(flush.gid, row.gid) ||
              (flush.gid == row.gid && lessEqualFull(
                flushLsId(flush), rowLsId(row), flush.lsidWidth))))))
    }
    flush.fullLsIdValid &&
      (less(flush.bid, row.bid) ||
        (flush.bid == row.bid && lessEqualFull(flushLsId(flush), rowLsId(row), flush.lsidWidth)))
  }

  def prune(flush: Flush, rows: Seq[Row]): Result = {
    val matchesByRow = rows.map(matches(flush, _))
    val freeByRow = rows.zip(matchesByRow).map { case (row, matched) => matched && row.status == Wait }
    val blockedByRow = rows.zip(matchesByRow).map { case (row, matched) => matched && row.status != Wait }

    def mask(bits: Seq[Boolean]): Int =
      bits.zipWithIndex.foldLeft(0) { case (acc, (bit, index)) => if (bit) acc | (1 << index) else acc }

    Result(
      matchMask = mask(matchesByRow),
      freeMask = mask(freeByRow),
      statusBlockedMask = mask(blockedByRow),
      freeCount = freeByRow.count(identity)
    )
  }
}

class STQFlushPruneSpec extends AnyFunSuite {
  import STQFlushPruneReference._

  private def row(status: Status = Wait, bid: Int = 0, gid: Int = 0, lsId: Int = 0, stid: Int = 0, peId: Int = 0, tid: Int = 0): Row =
    Row(status = status, bid = Id(value = bid), gid = Id(value = gid), lsId = Id(value = lsId), stid = stid, peId = peId, tid = tid)

  test("base-on-BID frees only valid WAIT stores covered by the flush") {
    val rows = Seq(
      row(bid = 1),
      row(bid = 2),
      row(bid = 3),
      row(status = Commit, bid = 4),
      row(bid = 5).copy(valid = false),
      row(status = Resolved, bid = 5),
      row(bid = 0),
      row(bid = 1)
    )

    val result = prune(Flush(bid = Id(value = 3), baseOnBid = true), rows)

    assert(result.matchMask == 0x2c)
    assert(result.freeMask == 0x04)
    assert(result.statusBlockedMask == 0x28)
    assert(result.freeCount == 1)
  }

  test("non-BID flush compares BID plus LSID and keeps older same-BID stores") {
    val rows = Seq(
      row(bid = 2, lsId = 0),
      row(bid = 2, lsId = 1),
      row(bid = 2, lsId = 2),
      row(bid = 3, lsId = 0),
      row(bid = 1, lsId = 7),
      row(status = Miss, bid = 4, lsId = 0),
      row(bid = 0, lsId = 0),
      row(bid = 1, lsId = 0)
    )

    val result = prune(Flush(bid = Id(value = 2), lsId = Id(value = 1)), rows)

    assert(result.matchMask == 0x2e)
    assert(result.freeMask == 0x0e)
    assert(result.statusBlockedMask == 0x20)
    assert(result.freeCount == 3)
  }

  test("group-based flush follows the model BID fast path before group tuple comparison") {
    val rows = Seq(
      row(bid = 1, gid = 9, lsId = 9),
      row(bid = 2, gid = 0, lsId = 0),
      row(bid = 2, gid = 1, lsId = 0),
      row(bid = 3, gid = 0, lsId = 0),
      row(status = L2Wait, bid = 4, gid = 0, lsId = 0),
      row(bid = 0, gid = 0, lsId = 0),
      row(bid = 1, gid = 0, lsId = 0),
      row(bid = 1, gid = 1, lsId = 1)
    )

    val result = prune(
      Flush(bid = Id(value = 2), gid = Id(value = 1), lsId = Id(value = 1), baseOnGroup = true),
      rows
    )

    assert(result.matchMask == 0x1e)
    assert(result.freeMask == 0x0e)
    assert(result.statusBlockedMask == 0x10)
    assert(result.freeCount == 3)
  }

  test("STID, PE, and thread scoping must all match before a store is freed") {
    val rows = Seq(
      row(bid = 4, stid = 7, peId = 2, tid = 3),
      row(bid = 5, stid = 7, peId = 1, tid = 3),
      row(bid = 6, stid = 7, peId = 2, tid = 4),
      row(bid = 7, stid = 6, peId = 2, tid = 3),
      row(status = Idle, bid = 8, stid = 7, peId = 2, tid = 3),
      row(bid = 1, stid = 7, peId = 2, tid = 3),
      row(bid = 0, stid = 7, peId = 2, tid = 3),
      row(bid = 2, stid = 7, peId = 2, tid = 3)
    )

    val result = prune(
      Flush(stid = 7, peId = 2, tid = 3, bid = Id(value = 4), baseOnBid = true, baseOnPE = true, baseOnThread = true),
      rows
    )

    assert(result.matchMask == 0x11)
    assert(result.freeMask == 0x01)
    assert(result.statusBlockedMask == 0x10)
    assert(result.freeCount == 1)
  }

  test("invalid flush requests do not free or match STQ rows") {
    val rows = Seq.fill(8)(row(bid = 1))
    val result = prune(Flush(valid = false, bid = Id(value = 1), baseOnBid = true), rows)

    assert(result.matchMask == 0)
    assert(result.freeMask == 0)
    assert(result.statusBlockedMask == 0)
    assert(result.freeCount == 0)
  }

  test("full LSID recovery preserves high bits and modular wrap semantics") {
    val width = 40
    val wrapped = prune(
      Flush(
        bid = Id(value = 2),
        lsId = Id(value = 6),
        fullLsId = Some((BigInt(1) << width) - 2),
        lsidWidth = width),
      Seq(row(bid = 2, lsId = 1).copy(fullLsId = Some(1))))
    val highBitOlderRow = prune(
      Flush(
        bid = Id(value = 2),
        lsId = Id(value = 1),
        fullLsId = Some(BigInt("200000001", 16)),
        lsidWidth = width),
      Seq(row(bid = 2, lsId = 1).copy(fullLsId = Some(BigInt("100000001", 16)))))

    assert(wrapped.freeMask == 1)
    assert(highBitOlderRow.freeMask == 0)
  }

  test("non-BID store recovery blocks missing and half-range full LSID authority") {
    val width = 40
    val rowImage = Seq(row(bid = 2, lsId = 1).copy(fullLsId = Some(BigInt(1) << (width - 1))))
    val missing = prune(
      Flush(bid = Id(value = 2), lsId = Id(value = 0), fullLsIdValid = false, lsidWidth = width),
      rowImage)
    val ambiguous = prune(
      Flush(bid = Id(value = 2), lsId = Id(value = 0), fullLsId = Some(0), lsidWidth = width),
      rowImage)

    assert(missing.freeMask == 0)
    assert(ambiguous.freeMask == 0)
  }

  test("STQ recovery widths keep physical rows ROB identity and full LSID independent") {
    val io = new STQFlushPruneIO(entries = 16, robEntries = 8, lsidWidth = 40)

    assert(io.rows.length == 16)
    assert(io.rows.head.bid.value.getWidth == 3)
    assert(io.rows.head.lsIdFull.getWidth == 40)
    assert(io.flush.req.lsId.value.getWidth == 3)
    assert(io.flush.req.lsIdFull.getWidth == 40)
    assert(io.fullLsIdRequiredMask.getWidth == 16)
    assert(io.fullLsIdMissingMask.getWidth == 16)
    assert(io.fullLsIdAmbiguousMask.getWidth == 16)
  }

  test("Chisel STQFlushPrune elaborates with model-derived masks and counts") {
    val sv = ChiselStage.emitSystemVerilog(new STQFlushPrune(
      entries = 16, robEntries = 8, lsidWidth = 40))

    assert(sv.contains("module STQFlushPrune"))
    assert(sv.contains("io_matchMask"))
    assert(sv.contains("io_freeMask"))
    assert(sv.contains("io_statusBlockedMask"))
    assert(sv.contains("io_freeCount"))
    assert(sv.contains("io_flush_req_lsIdFull"))
    assert(sv.contains("io_fullLsIdMissingMask"))
    assert(sv.contains("io_fullLsIdAmbiguousMask"))
  }
}
