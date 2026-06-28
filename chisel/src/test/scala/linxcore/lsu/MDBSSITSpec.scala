package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object MDBSSITReference {
  import STQFlushPruneReference.Id

  final case class Entry(
      loadPc: BigInt,
      storePc: BigInt,
      bidOff: Int,
      lsIdOff: Int,
      conf: Int,
      weight: Int,
      nukeValid: Boolean,
      nukeBid: Id)

  final case class LookupResult(
      responseValid: Boolean,
      tableHit: Boolean,
      hit: Boolean,
      firstAfterNuke: Boolean,
      confBlocked: Boolean,
      weightBlocked: Boolean,
      storePc: BigInt,
      storeBid: Id,
      conf: Int,
      weight: Int)

  final case class DeleteResult(matched: Boolean, released: Boolean, droppedBelowStall: Boolean, weightAfter: Int)
  final case class RecordResult(
      accepted: Boolean,
      allocated: Boolean,
      replaced: Boolean,
      reinforced: Boolean,
      decremented: Boolean,
      overflow: Boolean,
      orderIllegal: Boolean)

  final class Model(depth: Int = 4, robEntries: Int = 16, maxWeight: Int = 3, releaseWeight: Int = 25, incStep: Int = 1) {
    private var table = Vector.empty[Entry]
    private val initWeight = (maxWeight + 1) * releaseWeight / 100
    private val stallThreshold = initWeight + 1

    def entries: Seq[Entry] = table

    private def less(lhs: Id, rhs: Id): Boolean =
      if (lhs.wrap == rhs.wrap) lhs.value < rhs.value else lhs.value > rhs.value

    private def lessEqual(lhs: Id, rhs: Id): Boolean =
      less(lhs, rhs) || lhs == rhs

    private def sub(id: Id, offset: Int): Id = {
      val raw = id.value - offset
      if (raw >= 0) {
        id.copy(value = raw)
      } else {
        id.copy(wrap = !id.wrap, value = robEntries + raw)
      }
    }

    private def gap(newer: Id, older: Id): Int =
      if (newer.wrap == older.wrap) newer.value - older.value else newer.value + robEntries - older.value

    private def stall(weight: Int): Boolean =
      weight >= stallThreshold

    def lookup(loadPc: BigInt, loadBid: Id): LookupResult = {
      val index = table.indexWhere(_.loadPc == loadPc)
      if (index < 0) {
        return LookupResult(
          responseValid = true,
          tableHit = false,
          hit = false,
          firstAfterNuke = false,
          confBlocked = false,
          weightBlocked = false,
          storePc = 0,
          storeBid = Id(valid = false),
          conf = 0,
          weight = 0
        )
      }

      val entry = table(index)
      val first = entry.nukeValid && entry.nukeBid == loadBid
      table = table.updated(index, entry.copy(nukeValid = false))
      val confBlocked = entry.conf <= 0
      val weightBlocked = !stall(entry.weight)
      LookupResult(
        responseValid = true,
        tableHit = true,
        hit = !first && !confBlocked && !weightBlocked,
        firstAfterNuke = first,
        confBlocked = confBlocked,
        weightBlocked = weightBlocked,
        storePc = entry.storePc,
        storeBid = sub(loadBid, entry.bidOff),
        conf = entry.conf,
        weight = entry.weight
      )
    }

    def delete(loadPc: BigInt, storePc: BigInt): DeleteResult = {
      val index = table.indexWhere(entry => entry.loadPc == loadPc && entry.storePc == storePc)
      if (index < 0) {
        return DeleteResult(matched = false, released = false, droppedBelowStall = false, weightAfter = 0)
      }

      val entry = table(index)
      if (entry.weight == 0) {
        table = table.patch(index, Nil, 1)
        return DeleteResult(matched = true, released = true, droppedBelowStall = false, weightAfter = 0)
      }

      val nextWeight = entry.weight - 1
      table = table.updated(index, entry.copy(weight = nextWeight))
      DeleteResult(matched = true, released = false, droppedBelowStall = !stall(nextWeight), weightAfter = nextWeight)
    }

    def record(
        loadPc: BigInt,
        loadBid: Id,
        loadLsId: Id,
        storePc: BigInt,
        storeBid: Id,
        storeLsId: Id,
        conf: Int = 1): RecordResult = {
      if (!lessEqual(storeBid, loadBid)) {
        return RecordResult(false, false, false, false, false, false, orderIllegal = true)
      }

      val bidOff = gap(loadBid, storeBid)
      val lsIdOff = gap(loadLsId, storeLsId)
      val index = table.indexWhere(_.loadPc == loadPc)
      if (index < 0) {
        if (table.size >= depth) {
          return RecordResult(false, false, false, false, false, overflow = true, orderIllegal = false)
        }
        table :+= Entry(loadPc, storePc, bidOff, lsIdOff, conf, initWeight, nukeValid = true, loadBid)
        return RecordResult(accepted = true, allocated = true, replaced = false, reinforced = false, decremented = false, overflow = false, orderIllegal = false)
      }

      val entry = table(index)
      if (entry.storePc != storePc) {
        val replace = entry.conf < 1 || bidOff < entry.bidOff || (bidOff == entry.bidOff && lsIdOff < entry.lsIdOff)
        if (replace) {
          table = table.updated(index, Entry(loadPc, storePc, bidOff, lsIdOff, conf, initWeight, nukeValid = true, loadBid))
          RecordResult(accepted = true, allocated = false, replaced = true, reinforced = false, decremented = false, overflow = false, orderIllegal = false)
        } else {
          table = table.updated(index, entry.copy(conf = math.max(entry.conf - 1, 0), nukeValid = true, nukeBid = loadBid))
          RecordResult(accepted = true, allocated = false, replaced = false, reinforced = false, decremented = true, overflow = false, orderIllegal = false)
        }
      } else {
        table = table.updated(index, entry.copy(
          bidOff = math.min(entry.bidOff, bidOff),
          conf = math.min(entry.conf + 1, 3),
          weight = math.min(entry.weight + incStep, maxWeight),
          nukeValid = true,
          nukeBid = loadBid
        ))
        RecordResult(accepted = true, allocated = false, replaced = false, reinforced = true, decremented = false, overflow = false, orderIllegal = false)
      }
    }
  }
}

class MDBSSITSpec extends AnyFunSuite {
  import MDBSSITReference._
  import STQFlushPruneReference.Id

  private def id(value: Int, wrap: Boolean = false): Id =
    Id(wrap = wrap, value = value)

  test("new record allocates below the stall threshold and suppresses the first lookup after nuke") {
    val model = new Model()
    val record = model.record(loadPc = 0x1000, loadBid = id(4), loadLsId = id(7), storePc = 0x2000, storeBid = id(2), storeLsId = id(5))
    val firstLookup = model.lookup(loadPc = 0x1000, loadBid = id(4))
    val secondLookup = model.lookup(loadPc = 0x1000, loadBid = id(4))

    assert(record.accepted)
    assert(record.allocated)
    assert(firstLookup.tableHit)
    assert(firstLookup.firstAfterNuke)
    assert(!firstLookup.hit)
    assert(firstLookup.storePc == 0x2000)
    assert(firstLookup.storeBid == id(2))
    assert(secondLookup.tableHit)
    assert(!secondLookup.firstAfterNuke)
    assert(secondLookup.weightBlocked)
    assert(!secondLookup.hit)
  }

  test("same-store record reinforces confidence and weight until lookup can stall") {
    val model = new Model()
    model.record(loadPc = 0x1000, loadBid = id(4), loadLsId = id(7), storePc = 0x2000, storeBid = id(2), storeLsId = id(5))
    model.lookup(loadPc = 0x1000, loadBid = id(4))
    val reinforce = model.record(loadPc = 0x1000, loadBid = id(5), loadLsId = id(8), storePc = 0x2000, storeBid = id(3), storeLsId = id(6))
    val suppressed = model.lookup(loadPc = 0x1000, loadBid = id(5))
    val hit = model.lookup(loadPc = 0x1000, loadBid = id(5))

    assert(reinforce.reinforced)
    assert(suppressed.firstAfterNuke)
    assert(!suppressed.hit)
    assert(hit.hit)
    assert(hit.conf == 2)
    assert(hit.weight == 2)
    assert(hit.storeBid == id(3))
  }

  test("different-store record replaces closer conflicts and decrements confidence for farther stores") {
    val model = new Model()
    model.record(loadPc = 0x1000, loadBid = id(8), loadLsId = id(8), storePc = 0x2000, storeBid = id(4), storeLsId = id(4))
    val closer = model.record(loadPc = 0x1000, loadBid = id(8), loadLsId = id(8), storePc = 0x3000, storeBid = id(7), storeLsId = id(7))
    model.lookup(loadPc = 0x1000, loadBid = id(8))
    model.record(loadPc = 0x1000, loadBid = id(9), loadLsId = id(9), storePc = 0x3000, storeBid = id(8), storeLsId = id(8))
    val farther = model.record(loadPc = 0x1000, loadBid = id(9), loadLsId = id(9), storePc = 0x4000, storeBid = id(3), storeLsId = id(3))
    val entry = model.entries.head

    assert(closer.replaced)
    assert(farther.decremented)
    assert(entry.storePc == 0x3000)
    assert(entry.conf == 1)
  }

  test("delete decays weight, reports not-stall, and releases only after a later zero-weight delete") {
    val model = new Model()
    model.record(loadPc = 0x1000, loadBid = id(4), loadLsId = id(7), storePc = 0x2000, storeBid = id(2), storeLsId = id(5))
    model.lookup(loadPc = 0x1000, loadBid = id(4))
    model.record(loadPc = 0x1000, loadBid = id(5), loadLsId = id(8), storePc = 0x2000, storeBid = id(3), storeLsId = id(6))
    model.lookup(loadPc = 0x1000, loadBid = id(5))

    val dec1 = model.delete(loadPc = 0x1000, storePc = 0x2000)
    val dec2 = model.delete(loadPc = 0x1000, storePc = 0x2000)
    val release = model.delete(loadPc = 0x1000, storePc = 0x2000)

    assert(dec1.matched)
    assert(dec1.droppedBelowStall)
    assert(dec1.weightAfter == 1)
    assert(dec2.matched)
    assert(dec2.droppedBelowStall)
    assert(dec2.weightAfter == 0)
    assert(release.released)
    assert(model.entries.isEmpty)
  }

  test("lookup with a different BID clears the first-after-nuke marker without suppressing by nuke") {
    val model = new Model()
    model.record(loadPc = 0x1000, loadBid = id(4), loadLsId = id(7), storePc = 0x2000, storeBid = id(2), storeLsId = id(5))
    model.lookup(loadPc = 0x1000, loadBid = id(3))
    val second = model.lookup(loadPc = 0x1000, loadBid = id(4))

    assert(!second.firstAfterNuke)
  }

  test("records reject younger stores and report overflow when the finite table is full") {
    val illegal = new Model(depth = 2).record(loadPc = 0x1000, loadBid = id(1), loadLsId = id(1), storePc = 0x2000, storeBid = id(3), storeLsId = id(0))
    val full = new Model(depth = 2)
    full.record(loadPc = 0x1000, loadBid = id(3), loadLsId = id(3), storePc = 0x2000, storeBid = id(1), storeLsId = id(1))
    full.record(loadPc = 0x1100, loadBid = id(4), loadLsId = id(4), storePc = 0x2100, storeBid = id(2), storeLsId = id(2))
    val overflow = full.record(loadPc = 0x1200, loadBid = id(5), loadLsId = id(5), storePc = 0x2200, storeBid = id(3), storeLsId = id(3))

    assert(illegal.orderIllegal)
    assert(!illegal.accepted)
    assert(overflow.overflow)
    assert(!overflow.accepted)
  }

  test("Chisel MDBSSIT elaborates with lookup, record, delete, and table observability") {
    val sv = ChiselStage.emitSystemVerilog(new MDBSSIT(robEntries = 8, ssitEntries = 4))

    assert(sv.contains("module MDBSSIT"))
    assert(sv.contains("io_lookupHit"))
    assert(sv.contains("io_recordReinforced"))
    assert(sv.contains("io_deleteDroppedBelowStall"))
    assert(sv.contains("io_table_0_storePc"))
  }
}
