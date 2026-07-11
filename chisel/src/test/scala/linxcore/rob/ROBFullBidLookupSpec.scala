package linxcore.rob

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object ROBFullBidLookupReference {
  final case class Id(valid: Boolean = true, wrap: Boolean = false, value: Int = 0)
  final case class Key(
      valid: Boolean = true,
      bid: Id = Id(),
      gid: Id = Id(),
      rid: Id = Id(),
      peId: Int = 0,
      stid: Int = 0,
      tid: Int = 0)
  final case class Row(
      occupied: Boolean = false,
      bid: Id = Id(),
      gid: Id = Id(),
      rid: Id = Id(),
      peId: Int = 0,
      stid: Int = 0,
      tid: Int = 0,
      blockBidValid: Boolean = false,
      blockBid: BigInt = 0)
  final case class Result(
      matched: Boolean,
      blockedByFree: Boolean = false,
      blockedByStaleRid: Boolean = false,
      blockedByBid: Boolean = false,
      blockedByGid: Boolean = false,
      blockedByScope: Boolean = false,
      blockedByMissingBlockBid: Boolean = false,
      blockedByRingProjection: Boolean = false,
      blockBid: BigInt = 0)

  def project(fullBid: BigInt, entries: Int): Id = {
    val slotBits = Integer.numberOfTrailingZeros(entries)
    Id(wrap = (((fullBid >> slotBits) & 1) == 1), value = (fullBid & (entries - 1)).toInt)
  }

  def lookup(rows: Seq[Row], key: Key): Result = {
    val row = rows(key.rid.value)
    if (!key.valid || !key.bid.valid || !key.gid.valid || !key.rid.valid) Result(matched = false)
    else if (!row.occupied) Result(matched = false, blockedByFree = true)
    else if (row.rid != key.rid) Result(matched = false, blockedByStaleRid = true)
    else if (row.bid != key.bid) Result(matched = false, blockedByBid = true)
    else if (row.gid != key.gid) Result(matched = false, blockedByGid = true)
    else if ((row.peId, row.stid, row.tid) != (key.peId, key.stid, key.tid))
      Result(matched = false, blockedByScope = true)
    else if (!row.blockBidValid) Result(matched = false, blockedByMissingBlockBid = true)
    else if (project(row.blockBid, rows.size) != key.bid)
      Result(matched = false, blockedByRingProjection = true)
    else Result(matched = true, blockBid = row.blockBid)
  }
}

class ROBFullBidLookupSpec extends AnyFunSuite {
  import ROBFullBidLookupReference._

  private val entries = 8
  private val fullBid = BigInt(0x15)
  private val bid = project(fullBid, entries)
  private val gid = Id(value = 2)
  private val rid = Id(wrap = true, value = 3)
  private val key = Key(bid = bid, gid = gid, rid = rid, peId = 4, stid = 5, tid = 6)
  private val row = Row(
    occupied = true,
    bid = bid,
    gid = gid,
    rid = rid,
    peId = 4,
    stid = 5,
    tid = 6,
    blockBidValid = true,
    blockBid = fullBid
  )

  private def rows(target: Row = row): Seq[Row] =
    Seq.tabulate(entries)(idx => if (idx == rid.value) target else Row())

  test("exact native row identity recovers allocator-owned full BID") {
    val result = lookup(rows(), key)
    assert(result.matched)
    assert(result.blockBid == fullBid)
    assert(result.blockBid != key.bid.value)
  }

  test("stale RID and cross-scope requests cannot recover full BID") {
    assert(lookup(rows(row.copy(rid = rid.copy(wrap = false))), key).blockedByStaleRid)
    assert(lookup(rows(), key.copy(stid = 7)).blockedByScope)
    assert(lookup(rows(), key.copy(peId = 7)).blockedByScope)
    assert(lookup(rows(), key.copy(tid = 7)).blockedByScope)
  }

  test("missing sideband and inconsistent ring projection are explicit blockers") {
    assert(lookup(rows(row.copy(blockBidValid = false)), key).blockedByMissingBlockBid)
    assert(lookup(rows(row.copy(blockBid = fullBid + 1)), key).blockedByRingProjection)
  }

  test("Chisel ROBFullBidLookup elaborates exact identity blockers") {
    val sv = ChiselStage.emitSystemVerilog(new ROBFullBidLookup(entries = entries))
    assert(sv.contains("module ROBFullBidLookup"))
    assert(sv.contains("io_result_matched"))
    assert(sv.contains("io_result_blockBid"))
    assert(sv.contains("io_result_blockedByStaleRid"))
    assert(sv.contains("io_result_blockedByScope"))
    assert(sv.contains("io_result_blockedByRingProjection"))
  }
}
