package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadResolveQueueReference {
  import STQFlushPruneReference.Id

  final case class HitRecord(
      loadId: Id = Id(valid = true),
      bid: Id = Id(),
      gid: Id = Id(),
      rid: Id = Id(),
      loadLsId: Id = Id(),
      pc: BigInt = 0,
      addr: BigInt = 0,
      size: Int = 4,
      byteMask: BigInt = 0,
      data: BigInt = 0,
      forwardedMask: BigInt = 0)

  final case class Entry(
      peId: Int = 0,
      stid: Int = 0,
      tid: Int = 0,
      record: HitRecord = HitRecord())

  final case class ConflictRow(
      valid: Boolean,
      resolved: Boolean,
      peId: Int,
      stid: Int,
      tid: Int,
      bid: Id,
      gid: Id,
      rid: Id,
      lsId: Id,
      pc: BigInt,
      addr: BigInt,
      size: Int)

  final case class PushResult(accepted: Boolean, insertIndex: Int)
  final case class RetireResult(mask: Int, count: Int)
  final case class FlushPruneResult(mask: Int, count: Int)

  private def less(lhs: Id, rhs: Id): Boolean =
    if (lhs.wrap == rhs.wrap) lhs.value < rhs.value else lhs.value > rhs.value

  private def lessEqual(lhs: Id, rhs: Id): Boolean =
    lhs == rhs || less(lhs, rhs)

  private def lessEqualBidLs(srcBid: Id, srcLsId: Id, dstBid: Id, dstLsId: Id): Boolean =
    less(srcBid, dstBid) || (srcBid == dstBid && lessEqual(srcLsId, dstLsId))

  final class Model(capacity: Int) {
    private var entries = Vector.empty[Entry]

    def count: Int = entries.length
    def full: Boolean = entries.length >= capacity
    def validMask: Int = (1 << entries.length) - 1

    def push(entry: Entry): PushResult = {
      val insertIndex = entries.length
      if (full) {
        PushResult(accepted = false, insertIndex = insertIndex)
      } else {
        entries = entries :+ entry
        PushResult(accepted = true, insertIndex = insertIndex)
      }
    }

    def retire(commitBid: Id, commitLsId: Id): RetireResult = {
      val retireBits = entries.map(entry => lessEqualBidLs(entry.record.bid, entry.record.loadLsId, commitBid, commitLsId))
      val mask = retireBits.zipWithIndex.foldLeft(0) { case (acc, (bit, idx)) =>
        if (bit) acc | (1 << idx) else acc
      }
      entries = entries.zip(retireBits).collect { case (entry, false) => entry }
      RetireResult(mask, retireBits.count(identity))
    }

    def preciseFlush(flush: STQFlushPruneReference.Flush): FlushPruneResult = {
      val pruneBits = entries.map { entry =>
        STQFlushPruneReference.matches(
          flush,
          STQFlushPruneReference.Row(
            valid = true,
            stid = entry.stid,
            peId = entry.peId,
            tid = entry.tid,
            bid = entry.record.bid,
            gid = entry.record.gid,
            lsId = entry.record.loadLsId,
            fullLsId = Some(entry.record.loadLsId.value)
          )
        )
      }
      val mask = pruneBits.zipWithIndex.foldLeft(0) { case (acc, (bit, idx)) =>
        if (bit) acc | (1 << idx) else acc
      }
      entries = entries.zip(pruneBits).collect { case (entry, false) => entry }
      FlushPruneResult(mask, pruneBits.count(identity))
    }

    def flush(): Unit =
      entries = Vector.empty

    def conflictRows: Seq[ConflictRow] =
      entries.map { entry =>
        ConflictRow(
          valid = true,
          resolved = true,
          peId = entry.peId,
          stid = entry.stid,
          tid = entry.tid,
          bid = entry.record.bid,
          gid = entry.record.gid,
          rid = entry.record.rid,
          lsId = entry.record.loadLsId,
          pc = entry.record.pc,
          addr = entry.record.addr,
          size = entry.record.size
        )
      }
  }
}

class LoadResolveQueueSpec extends AnyFunSuite {
  import LoadResolveQueueReference._
  import STQFlushPruneReference.Id

  private def id(value: Int, wrap: Boolean = false): Id =
    Id(wrap = wrap, value = value)

  private def record(n: Int): HitRecord =
    HitRecord(
      loadId = id(n),
      bid = id(n / 4),
      gid = id(0),
      rid = id(n),
      loadLsId = id(n),
      pc = 0x2000 + n,
      addr = 0x1000 + n * 8,
      size = 4,
      byteMask = 0xf,
      data = BigInt(n),
      forwardedMask = 0)

  test("push appends resolved hit records until capacity") {
    val queue = new Model(capacity = 2)

    assert(queue.push(Entry(peId = 1, stid = 2, tid = 3, record = record(0))) == PushResult(true, 0))
    assert(queue.push(Entry(peId = 1, stid = 2, tid = 3, record = record(1))) == PushResult(true, 1))
    assert(queue.full)
    assert(queue.push(Entry(record = record(2))) == PushResult(false, 2))
    assert(queue.count == 2)
    assert(queue.validMask == 0x3)
  }

  test("retire removes loads at or older than the commit identity") {
    val queue = new Model(capacity = 4)
    queue.push(Entry(record = record(0).copy(bid = id(1), loadLsId = id(1))))
    queue.push(Entry(record = record(1).copy(bid = id(1), loadLsId = id(3))))
    queue.push(Entry(record = record(2).copy(bid = id(2), loadLsId = id(0))))

    val retired = queue.retire(commitBid = id(1), commitLsId = id(3))

    assert(retired == RetireResult(mask = 0x3, count = 2))
    assert(queue.count == 1)
    assert(queue.conflictRows.head.lsId == id(0))
  }

  test("conflict rows preserve thread sidecars and load PC") {
    val queue = new Model(capacity = 2)
    queue.push(Entry(peId = 7, stid = 5, tid = 3, record = record(6)))

    val row = queue.conflictRows.head
    assert(row.valid && row.resolved)
    assert(row.peId == 7)
    assert(row.stid == 5)
    assert(row.tid == 3)
    assert(row.pc == 0x2006)
    assert(row.addr == 0x1030)
    assert(row.lsId == id(6))
  }

  test("flush clears all resolved entries") {
    val queue = new Model(capacity = 4)
    queue.push(Entry(record = record(0)))
    queue.push(Entry(record = record(1)))

    queue.flush()

    assert(queue.count == 0)
    assert(queue.validMask == 0)
    assert(queue.conflictRows.isEmpty)
  }

  test("precise flush prunes matching entries and compacts survivors") {
    val queue = new Model(capacity = 4)
    queue.push(Entry(stid = 2, peId = 1, tid = 3, record = record(0).copy(bid = id(1), loadLsId = id(1))))
    queue.push(Entry(stid = 2, peId = 1, tid = 3, record = record(1).copy(bid = id(1), loadLsId = id(2))))
    queue.push(Entry(stid = 2, peId = 1, tid = 3, record = record(2).copy(bid = id(2), loadLsId = id(0))))
    queue.push(Entry(stid = 1, peId = 1, tid = 3, record = record(3).copy(bid = id(3), loadLsId = id(0))))

    val pruned = queue.preciseFlush(
      STQFlushPruneReference.Flush(
        stid = 2,
        peId = 1,
        tid = 3,
        bid = id(1),
        lsId = id(2),
        fullLsId = Some(2),
        baseOnPE = true,
        baseOnThread = true
      )
    )

    assert(pruned == FlushPruneResult(mask = 0x6, count = 2))
    assert(queue.count == 2)
    assert(queue.conflictRows.map(_.lsId) == Seq(id(1), id(0)))
    assert(queue.conflictRows.map(_.stid) == Seq(2, 1))
  }

  test("Chisel LoadResolveQueue elaborates push, retire, and conflict-row ports") {
    val sv = ChiselStage.emitSystemVerilog(new LoadResolveQueue(queueEntries = 4, liqEntries = 4, idEntries = 8))

    assert(sv.contains("module LoadResolveQueue"))
    assert(sv.contains("io_pushRecord_pc"))
    assert(sv.contains("io_pushAccepted"))
    assert(sv.contains("io_retireMask"))
    assert(sv.contains("io_preciseFlush_req_valid"))
    assert(sv.contains("io_flushPruneMask"))
    assert(sv.contains("io_conflictRows_0_pc"))
    assert(sv.contains("io_conflictRows_0_lsId_value"))
    assert(sv.contains("io_entries_0_record_loadLsId_value"))
  }
}
