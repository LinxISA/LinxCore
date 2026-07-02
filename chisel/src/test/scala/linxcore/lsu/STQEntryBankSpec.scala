package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object STQEntryBankReference {
  import STQFlushPruneReference.Id

  sealed trait StoreType
  case object All extends StoreType
  case object Addr extends StoreType
  case object Data extends StoreType

  sealed trait TUDst
  case object NoTUDst extends TUDst
  case object TDst extends TUDst
  case object UDst extends TUDst

  sealed trait Status
  case object Wait extends Status
  case object Commit extends Status
  case object Idle extends Status

  final case class Request(
      storeType: StoreType = All,
      bid: Id = Id(),
      gid: Id = Id(),
      rid: Id = Id(),
      lsId: Id = Id(),
      tSeq: Id = Id(),
      uSeq: Id = Id(),
      tuDstValid: Boolean = false,
      tuDst: TUDst = NoTUDst,
      pc: BigInt = 0,
      stid: Int = 0,
      peId: Int = 0,
      tid: Int = 0,
      addr: BigInt = 0,
      data: BigInt = 0,
      size: Int = 8,
      stackValid: Boolean = false,
      scalarIex: Boolean = true,
      simtLane: Int = 0)

  final case class Entry(
      status: Status = Idle,
      req: Request = Request(),
      addrReady: Boolean = false,
      dataReady: Boolean = false)

  final case class InsertResult(accepted: Boolean, allocated: Boolean, merged: Boolean, conflict: Boolean, index: Option[Int])
  final case class CommitFreeMaskResult(acceptedMask: Int, ignoredMask: Int, count: Int)
  final case class TULinkSource(
      valid: Boolean = false,
      bid: Id = Id(valid = false),
      rid: Id = Id(valid = false),
      stid: Int = 0,
      tSeq: Id = Id(valid = false),
      uSeq: Id = Id(valid = false),
      dstValid: Boolean = false,
      dst: TUDst = NoTUDst,
      multipleMatch: Boolean = false)

  final class Model(entries: Int) {
    require(entries > 1 && (entries & (entries - 1)) == 0)

    private val table = Array.fill[Option[Entry]](entries)(None)
    private var resident = 0
    private var outstandingWait = 0

    def residentCount: Int = resident
    def outstandingWaitCount: Int = outstandingWait
    def full: Boolean = resident == entries
    def empty: Boolean = resident == 0
    def stall: Boolean = full && outstandingWait == resident
    def entry(index: Int): Option[Entry] = table(index)
    def occupiedMask: Int = mask(table.map(_.isDefined).toSeq)
    def waitMask: Int = mask(table.map(_.exists(_.status == Wait)).toSeq)
    def commitMask: Int = mask(table.map(_.exists(_.status == Commit)).toSeq)

    private def mask(bits: Seq[Boolean]): Int =
      bits.zipWithIndex.foldLeft(0) { case (acc, (bit, index)) => if (bit) acc | (1 << index) else acc }

    private def less(lhs: Id, rhs: Id): Boolean =
      if (lhs.wrap == rhs.wrap) lhs.value < rhs.value else lhs.value > rhs.value

    private def lessEqual(lhs: Id, rhs: Id): Boolean =
      less(lhs, rhs) || lhs == rhs

    private def lessEqualBidLs(srcBid: Id, srcLsId: Id, dstBid: Id, dstLsId: Id): Boolean =
      less(srcBid, dstBid) || (srcBid == dstBid && lessEqual(srcLsId, dstLsId))

    private def sameStoreId(entry: Entry, req: Request): Boolean =
      entry.req.bid == req.bid && entry.req.lsId == req.lsId &&
        (req.scalarIex || entry.req.simtLane == req.simtLane)

    private def sourceMatches(flush: STQFlushPruneReference.Flush, entry: Entry): Boolean =
      flush.valid && !flush.baseOnBid &&
        entry.req.bid == flush.bid &&
        entry.req.rid == flush.rid &&
        entry.req.stid == flush.stid

    private def compatible(entry: Entry, req: Request): Boolean =
      (req.storeType == Addr && entry.req.storeType == Data) ||
        (req.storeType == Data && entry.req.storeType == Addr)

    private def toEntry(req: Request): Entry =
      Entry(
        status = Wait,
        req = req,
        addrReady = req.storeType == All || req.storeType == Addr,
        dataReady = req.storeType == All || req.storeType == Data
      )

    private def merge(entry: Entry, req: Request): Entry = {
      val mergedReq = req.storeType match {
        case Addr =>
          entry.req.copy(storeType = All, addr = req.addr, size = req.size, stackValid = entry.req.stackValid || req.stackValid)
        case Data =>
          entry.req.copy(storeType = All, data = req.data, stackValid = entry.req.stackValid || req.stackValid)
        case All =>
          entry.req
      }
      Entry(status = Wait, req = mergedReq, addrReady = true, dataReady = true)
    }

    def insert(req: Request): InsertResult = {
      val partial = req.storeType != All
      val mergeIndex =
        if (partial) table.indices.find(i => table(i).exists(e => e.status == Wait && sameStoreId(e, req) && compatible(e, req)))
        else None
      val conflict =
        partial && mergeIndex.isEmpty && table.exists(_.exists(e => e.status == Wait && sameStoreId(e, req) && !compatible(e, req)))

      if (conflict) {
        return InsertResult(accepted = false, allocated = false, merged = false, conflict = true, index = None)
      }

      mergeIndex match {
        case Some(index) =>
          table(index) = table(index).map(merge(_, req))
          InsertResult(accepted = true, allocated = false, merged = true, conflict = false, index = Some(index))
        case None =>
          val freeIndex = table.indexWhere(_.isEmpty)
          if (freeIndex < 0) {
            InsertResult(accepted = false, allocated = false, merged = false, conflict = false, index = None)
          } else {
            table(freeIndex) = Some(toEntry(req))
            resident += 1
            outstandingWait += 1
            InsertResult(accepted = true, allocated = true, merged = false, conflict = false, index = Some(freeIndex))
          }
      }
    }

    def markCommit(index: Int): Boolean =
      table(index) match {
        case Some(entry) if entry.status == Wait && entry.addrReady && entry.dataReady && entry.req.storeType == All =>
          table(index) = Some(entry.copy(status = Commit))
          outstandingWait -= 1
          true
        case _ =>
          false
      }

    def commitFree(index: Int): Boolean =
      table(index) match {
        case Some(entry) if entry.status == Commit =>
          table(index) = None
          resident -= 1
          true
        case _ =>
          false
      }

    def commitFreeMask(reqMask: Int): CommitFreeMaskResult = {
      var accepted = 0
      var ignored = 0
      var freed = 0
      for (index <- table.indices if ((reqMask >> index) & 1) == 1) {
        table(index) match {
          case Some(entry) if entry.status == Commit =>
            table(index) = None
            resident -= 1
            accepted |= (1 << index)
            freed += 1
          case _ =>
            ignored |= (1 << index)
        }
      }
      CommitFreeMaskResult(acceptedMask = accepted, ignoredMask = ignored, count = freed)
    }

    def flush(flush: STQFlushPruneReference.Flush): STQFlushPruneReference.Result = {
      val rows = table.toSeq.map {
        case Some(entry) =>
          STQFlushPruneReference.Row(
            valid = true,
            status = entry.status match {
              case Wait => STQFlushPruneReference.Wait
              case Commit => STQFlushPruneReference.Commit
              case Idle => STQFlushPruneReference.Idle
            },
            stid = entry.req.stid,
            peId = entry.req.peId,
            tid = entry.req.tid,
            bid = entry.req.bid,
            gid = entry.req.gid,
            lsId = entry.req.lsId
          )
        case None =>
          STQFlushPruneReference.Row(valid = false, status = STQFlushPruneReference.Idle)
      }
      val result = STQFlushPruneReference.prune(flush, rows)
      for (idx <- table.indices if ((result.freeMask >> idx) & 1) == 1) {
        table(idx) = None
        resident -= 1
        outstandingWait -= 1
      }
      result
    }

    def tuSource(flush: STQFlushPruneReference.Flush): TULinkSource = {
      val matches = table.toSeq.flatten.filter(sourceMatches(flush, _))
      matches.lastOption match {
        case Some(entry) =>
          TULinkSource(
            valid = true,
            bid = entry.req.bid,
            rid = entry.req.rid,
            stid = entry.req.stid,
            tSeq = entry.req.tSeq,
            uSeq = entry.req.uSeq,
            dstValid = entry.req.tuDstValid,
            dst = entry.req.tuDst,
            multipleMatch = matches.size > 1
          )
        case None =>
          TULinkSource()
      }
    }
  }
}

class STQEntryBankSpec extends AnyFunSuite {
  import STQEntryBankReference._
  import STQFlushPruneReference.Id

  private def req(n: Int, storeType: StoreType = All, bid: Int = 0, lsId: Int = 0): Request =
    Request(
      storeType = storeType,
      bid = Id(value = bid),
      gid = Id(value = 0),
      rid = Id(value = n),
      lsId = Id(value = lsId),
      stid = 1,
      peId = 2,
      tid = 3,
      addr = 0x1000 + n * 8,
      data = 0x2000 + n,
      size = 8
    )

  test("insert allocates the first free row and updates resident and WAIT counts") {
    val stq = new Model(entries = 4)
    val r0 = stq.insert(req(0, bid = 1, lsId = 0))
    val r1 = stq.insert(req(1, bid = 1, lsId = 1))

    assert(r0 == InsertResult(accepted = true, allocated = true, merged = false, conflict = false, index = Some(0)))
    assert(r1.index.contains(1))
    assert(stq.residentCount == 2)
    assert(stq.outstandingWaitCount == 2)
    assert(stq.occupiedMask == 0x3)
    assert(stq.waitMask == 0x3)
    assert(!stq.empty)
    assert(!stq.full)
  }

  test("partial STA and STD rows merge in place without changing counts") {
    val stq = new Model(entries = 4)
    val addr = stq.insert(req(0, storeType = Addr, bid = 2, lsId = 5).copy(addr = 0x44))
    val data = stq.insert(req(1, storeType = Data, bid = 2, lsId = 5).copy(data = 0xdead, stackValid = true))

    assert(addr.allocated)
    assert(data == InsertResult(accepted = true, allocated = false, merged = true, conflict = false, index = Some(0)))
    assert(stq.residentCount == 1)
    assert(stq.outstandingWaitCount == 1)
    val merged = stq.entry(0).get
    assert(merged.req.storeType == All)
    assert(merged.addrReady)
    assert(merged.dataReady)
    assert(merged.req.addr == 0x44)
    assert(merged.req.data == 0xdead)
    assert(merged.req.stackValid)
  }

  test("full STQ still accepts a compatible partial merge but rejects a new allocation") {
    val stq = new Model(entries = 2)
    assert(stq.insert(req(0, storeType = Addr, bid = 3, lsId = 0)).allocated)
    assert(stq.insert(req(1, bid = 4, lsId = 0)).allocated)
    assert(stq.full)
    assert(stq.stall)

    val merge = stq.insert(req(2, storeType = Data, bid = 3, lsId = 0))
    val blocked = stq.insert(req(3, bid = 5, lsId = 0))

    assert(merge.merged)
    assert(!blocked.accepted)
    assert(stq.residentCount == 2)
    assert(stq.outstandingWaitCount == 2)
  }

  test("markCommit and commitFree split outstanding and resident accounting") {
    val stq = new Model(entries = 4)
    val partial = stq.insert(req(0, storeType = Addr, bid = 1, lsId = 0)).index.get
    val full = stq.insert(req(1, bid = 1, lsId = 1)).index.get

    assert(!stq.markCommit(partial))
    assert(stq.markCommit(full))
    assert(stq.residentCount == 2)
    assert(stq.outstandingWaitCount == 1)
    assert(stq.commitMask == 0x2)

    assert(stq.commitFree(full))
    assert(stq.residentCount == 1)
    assert(stq.outstandingWaitCount == 1)
    assert(stq.occupiedMask == 0x1)
  }

  test("commitFreeMask frees multiple committed rows and reports WAIT or invalid rows as ignored") {
    val stq = new Model(entries = 4)
    val committed0 = stq.insert(req(0, bid = 1, lsId = 0)).index.get
    val wait = stq.insert(req(1, bid = 1, lsId = 1)).index.get
    val committed2 = stq.insert(req(2, bid = 1, lsId = 2)).index.get
    assert(committed0 == 0 && wait == 1 && committed2 == 2)
    assert(stq.markCommit(committed0))
    assert(stq.markCommit(committed2))

    val result = stq.commitFreeMask(0xf)

    assert(result == CommitFreeMaskResult(acceptedMask = 0x5, ignoredMask = 0xa, count = 2))
    assert(stq.residentCount == 1)
    assert(stq.outstandingWaitCount == 1)
    assert(stq.occupiedMask == 0x2)
    assert(stq.waitMask == 0x2)
    assert(stq.commitMask == 0)
  }

  test("flush frees only matched WAIT rows and preserves committed rows") {
    val stq = new Model(entries = 4)
    val old = stq.insert(req(0, bid = 1, lsId = 0)).index.get
    val target = stq.insert(req(1, bid = 2, lsId = 0)).index.get
    val committed = stq.insert(req(2, bid = 3, lsId = 0)).index.get
    assert(old == 0 && target == 1 && committed == 2)
    assert(stq.markCommit(committed))

    val result = stq.flush(STQFlushPruneReference.Flush(stid = 1, bid = Id(value = 2), baseOnBid = true))

    assert(result.matchMask == 0x6)
    assert(result.freeMask == 0x2)
    assert(result.statusBlockedMask == 0x4)
    assert(stq.residentCount == 2)
    assert(stq.outstandingWaitCount == 1)
    assert(stq.occupiedMask == 0x5)
    assert(stq.commitMask == 0x4)
  }

  test("T/U source exposes exact non-BID store row sidecars") {
    val stq = new Model(entries = 4)
    val request = req(0, bid = 2, lsId = 7).copy(
      rid = Id(value = 5),
      stid = 3,
      tSeq = Id(value = 9),
      uSeq = Id(value = 10),
      tuDstValid = true,
      tuDst = TDst)

    assert(stq.insert(request).allocated)

    val source = stq.tuSource(STQFlushPruneReference.Flush(stid = 3, bid = Id(value = 2), rid = Id(value = 5)))

    assert(source == TULinkSource(
      valid = true,
      bid = Id(value = 2),
      rid = Id(value = 5),
      stid = 3,
      tSeq = Id(value = 9),
      uSeq = Id(value = 10),
      dstValid = true,
      dst = TDst))
  }

  test("T/U source ignores base-on-BID and non-identical store identities") {
    val stq = new Model(entries = 4)
    val request = req(0, bid = 2, lsId = 0).copy(
      rid = Id(value = 1),
      stid = 3,
      tSeq = Id(value = 4),
      uSeq = Id(value = 5),
      tuDstValid = true,
      tuDst = UDst)

    assert(stq.insert(request).allocated)

    assert(!stq.tuSource(STQFlushPruneReference.Flush(stid = 3, bid = Id(value = 2), rid = Id(value = 1), baseOnBid = true)).valid)
    assert(!stq.tuSource(STQFlushPruneReference.Flush(stid = 4, bid = Id(value = 2), rid = Id(value = 1))).valid)
    assert(!stq.tuSource(STQFlushPruneReference.Flush(stid = 3, bid = Id(value = 2), rid = Id(value = 2))).valid)
  }

  test("merged partial stores preserve the first sidecar owner") {
    val stq = new Model(entries = 4)
    val addr = req(0, storeType = Addr, bid = 2, lsId = 5).copy(
      tSeq = Id(value = 6),
      uSeq = Id(value = 7),
      tuDstValid = true,
      tuDst = TDst,
      pc = 0x1234)
    val data = req(1, storeType = Data, bid = 2, lsId = 5).copy(
      tSeq = Id(value = 11),
      uSeq = Id(value = 12),
      tuDstValid = true,
      tuDst = UDst,
      pc = 0x5678)

    assert(stq.insert(addr).allocated)
    assert(stq.insert(data).merged)

    val merged = stq.entry(0).get
    assert(merged.req.tSeq == Id(value = 6))
    assert(merged.req.uSeq == Id(value = 7))
    assert(merged.req.tuDst == TDst)
    assert(merged.req.pc == 0x1234)

    val source = stq.tuSource(STQFlushPruneReference.Flush(stid = 1, bid = Id(value = 2), rid = Id(value = 0)))
    assert(source.valid)
    assert(source.tSeq == Id(value = 6))
    assert(source.uSeq == Id(value = 7))
    assert(source.dst == TDst)
  }

  test("flush-cleared rows no longer publish an LSU T/U source") {
    val stq = new Model(entries = 4)
    val request = req(0, bid = 2, lsId = 0).copy(
      tSeq = Id(value = 8),
      uSeq = Id(value = 9),
      tuDstValid = true,
      tuDst = TDst)

    assert(stq.insert(request).allocated)
    val flush = STQFlushPruneReference.Flush(stid = 1, bid = Id(value = 2), rid = Id(value = 0), lsId = Id(value = 0))
    assert(stq.tuSource(flush).valid)

    val result = stq.flush(flush)

    assert(result.freeMask == 0x1)
    assert(!stq.tuSource(flush).valid)
  }

  test("Chisel STQEntryBank elaborates with state ownership and flush-prune child") {
    val sv = ChiselStage.emitSystemVerilog(new STQEntryBank(entries = 8))

    assert(sv.contains("module STQEntryBank"))
    assert(sv.contains("STQFlushPrune"))
    assert(sv.contains("io_flushFreeMask"))
    assert(sv.contains("io_insertMerged"))
    assert(sv.contains("io_insert_pc"))
    assert(sv.contains("io_rows_0_pc"))
    assert(sv.contains("io_markCommitAccepted"))
    assert(sv.contains("io_commitFreeAccepted"))
    assert(sv.contains("io_commitFreeAcceptedMask"))
    assert(sv.contains("io_commitFreeCount"))
    assert(sv.contains("io_lsuTULinkSource_valid"))
    assert(sv.contains("io_lsuTULinkSource_tSeq_value"))
    assert(sv.contains("io_lsuTULinkSourceMatched"))
  }
}
