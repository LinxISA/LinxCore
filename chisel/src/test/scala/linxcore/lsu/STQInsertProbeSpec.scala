package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object STQInsertProbeReference {
  import STQEntryBankReference._

  final case class Result(
      ready: Boolean,
      requestReady: Boolean,
      canMerge: Boolean,
      canAllocate: Boolean,
      conflict: Boolean,
      mergeMask: Int,
      conflictMask: Int,
      freeMask: Int,
      insertIndex: Option[Int])

  private def sameStoreId(entry: Entry, req: Request): Boolean =
    entry.req.stid == req.stid && entry.req.bid == req.bid && entry.req.lsId == req.lsId &&
      (req.scalarIex || entry.req.simtLane == req.simtLane)

  private def compatible(entry: Entry, req: Request): Boolean =
    (req.storeType == Addr && entry.req.storeType == Data) ||
      (req.storeType == Data && entry.req.storeType == Addr)

  private def mask(bits: Seq[Boolean]): Int =
    bits.zipWithIndex.foldLeft(0) { case (acc, (bit, index)) => if (bit) acc | (1 << index) else acc }

  def probe(rows: Seq[Option[Entry]], req: Request, valid: Boolean = true, flushApplied: Boolean = false): Result = {
    val partial = req.storeType != All
    val mergeBits = rows.map(_.exists(entry => valid && partial && entry.status == Wait && sameStoreId(entry, req) && compatible(entry, req)))
    val conflictBits = rows.map(_.exists(entry => valid && partial && entry.status == Wait && sameStoreId(entry, req) && !compatible(entry, req)))
    val freeBits = rows.map(_.isEmpty)
    val mergeIndex = mergeBits.indexWhere(identity)
    val freeIndex = freeBits.indexWhere(identity)
    val canMerge = partial && mergeIndex >= 0
    val canAllocate = (req.storeType == All || mergeIndex < 0) && freeIndex >= 0
    val conflict = valid && conflictBits.contains(true) && !canMerge
    val ready = !flushApplied && !conflict && (canMerge || canAllocate)
    val insertIndex =
      if (canMerge) Some(mergeIndex)
      else if (canAllocate) Some(freeIndex)
      else None

    Result(
      ready = ready,
      requestReady = valid && ready,
      canMerge = valid && canMerge,
      canAllocate = valid && canAllocate,
      conflict = conflict,
      mergeMask = mask(mergeBits),
      conflictMask = mask(conflictBits),
      freeMask = mask(freeBits),
      insertIndex = insertIndex
    )
  }
}

class STQInsertProbeSpec extends AnyFunSuite {
  import STQEntryBankReference._
  import STQFlushPruneReference.Id
  import STQInsertProbeReference._

  private def req(n: Int, storeType: StoreType = All, bid: Int = 0, lsId: Int = 0, stid: Int = 1): Request =
    Request(
      storeType = storeType,
      bid = Id(value = bid),
      gid = Id(value = 0),
      rid = Id(value = n),
      lsId = Id(value = lsId),
      stid = stid,
      peId = 2,
      tid = 3,
      addr = 0x1000 + n * 8,
      data = 0x2000 + n,
      size = 8
    )

  private def waitEntry(request: Request): Entry =
    Entry(
      status = Wait,
      req = request,
      addrReady = request.storeType == All || request.storeType == Addr,
      dataReady = request.storeType == All || request.storeType == Data)

  test("reference accepts a complementary split merge even when no free row exists") {
    val rows = Seq(
      Some(waitEntry(req(0, storeType = Addr, bid = 3, lsId = 1))),
      Some(waitEntry(req(1, storeType = All, bid = 4, lsId = 0))))

    val merge = probe(rows, req(2, storeType = Data, bid = 3, lsId = 1))
    val allocate = probe(rows, req(3, storeType = All, bid = 5, lsId = 0))

    assert(merge.ready)
    assert(merge.requestReady)
    assert(merge.canMerge)
    assert(!merge.canAllocate)
    assert(!merge.conflict)
    assert(merge.mergeMask == 0x1)
    assert(merge.insertIndex.contains(0))
    assert(!allocate.ready)
    assert(!allocate.canAllocate)
  }

  test("reference reports incompatible same-ID split halves as conflicts before allocation") {
    val rows = Seq(
      Some(waitEntry(req(0, storeType = Addr, bid = 2, lsId = 7))),
      None)

    val result = probe(rows, req(1, storeType = Addr, bid = 2, lsId = 7))

    assert(!result.ready)
    assert(!result.requestReady)
    assert(!result.canMerge)
    assert(result.canAllocate)
    assert(result.conflict)
    assert(result.conflictMask == 0x1)
    assert(result.freeMask == 0x2)
  }

  test("reference never merges equal BID and LSID identities across STIDs") {
    val rows = Seq(
      Some(waitEntry(req(0, storeType = Addr, bid = 2, lsId = 7, stid = 1))),
      None)

    val result = probe(rows, req(1, storeType = Data, bid = 2, lsId = 7, stid = 2))

    assert(result.ready)
    assert(!result.canMerge)
    assert(result.canAllocate)
    assert(!result.conflict)
    assert(result.mergeMask == 0)
    assert(result.insertIndex.contains(1))
  }

  test("reference blocks otherwise-ready insertion during a flush-applied bank cycle") {
    val rows = Seq(None, None)
    val result = probe(rows, req(0), flushApplied = true)

    assert(!result.ready)
    assert(!result.requestReady)
    assert(result.canAllocate)
    assert(!result.conflict)
    assert(result.freeMask == 0x3)
    assert(result.insertIndex.contains(0))
  }

  test("STQInsertProbe IO exposes request, row, and decision widths") {
    val io = new STQInsertProbeIO(entries = 8)

    assert(io.request.bid.value.getWidth == 3)
    assert(io.request.tSeq.value.getWidth == 5)
    assert(io.rows.head.uSeq.value.getWidth == 5)
    assert(io.rows.length == 8)
    assert(io.ready.getWidth == 1)
    assert(io.requestReady.getWidth == 1)
    assert(io.mergeMask.getWidth == 8)
    assert(io.conflictMask.getWidth == 8)
    assert(io.freeMask.getWidth == 8)
    assert(io.insertIndex.getWidth == 3)
  }

  test("STQInsertProbe elaborates as a standalone read-only readiness owner") {
    val sv = ChiselStage.emitSystemVerilog(new STQInsertProbe(entries = 8))

    assert(sv.contains("module STQInsertProbe"))
    assert(sv.contains("io_ready"))
    assert(sv.contains("io_request_tSeq_value"))
    assert(sv.contains("io_requestReady"))
    assert(sv.contains("io_conflict"))
    assert(sv.contains("io_freeMask"))
  }
}
