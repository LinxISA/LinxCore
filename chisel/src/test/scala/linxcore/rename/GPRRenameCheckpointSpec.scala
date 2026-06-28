package linxcore.rename

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

import linxcore.rob.ROBIDReference
import linxcore.rob.ROBIDValue

object GPRRenameCheckpointReference {
  final case class MapQEntry(valid: Boolean, bid: ROBIDValue, rid: ROBIDValue, archTag: Int, physTag: Int)

  final case class State(
      smap: Vector[Int],
      cmap: Vector[Int],
      checkpoints: Vector[Vector[Int]],
      checkpointValid: Vector[Boolean],
      renamePtr: ROBIDValue,
      free: Vector[Boolean],
      mapQ: Vector[MapQEntry]) {
    def freeCount: Int = free.count(identity)
    def mapQFreeCount: Int = mapQ.count(!_.valid)
  }

  def initial(archRegs: Int = 24, physRegs: Int = 64, entries: Int = 8, mapQDepth: Int = 8): State = {
    val identity = (0 until archRegs).toVector
    State(
      smap = identity,
      cmap = identity,
      checkpoints = Vector.fill(entries)(identity),
      checkpointValid = Vector.fill(entries)(false),
      renamePtr = ROBIDValue(),
      free = (0 until physRegs).map(_ >= archRegs).toVector,
      mapQ = Vector.fill(mapQDepth)(MapQEntry(valid = false, ROBIDValue(), ROBIDValue(), archTag = 0, physTag = 0))
    )
  }

  def rename(state: State, archTag: Int, bid: ROBIDValue, rid: ROBIDValue): (State, Int) = {
    require(state.freeCount > 0)
    require(state.mapQFreeCount > 0)
    val phys = state.free.indexWhere(identity)
    val mapIdx = state.mapQ.indexWhere(!_.valid)
    val next = state.copy(
      smap = state.smap.updated(archTag, phys),
      free = state.free.updated(phys, false),
      mapQ = state.mapQ.updated(mapIdx, MapQEntry(valid = true, bid = bid, rid = rid, archTag = archTag, physTag = phys))
    )
    next -> phys
  }

  def checkpoint(state: State, bid: ROBIDValue): State =
    state.copy(
      checkpoints = state.checkpoints.updated(bid.value, state.smap),
      checkpointValid = state.checkpointValid.updated(bid.value, true),
      renamePtr = bid
    )

  def commit(state: State, bid: ROBIDValue): (State, Set[Int]) = {
    var cmap = state.cmap
    var free = state.free
    var mapQ = state.mapQ
    var released = Set.empty[Int]

    for ((entry, idx) <- state.mapQ.zipWithIndex) {
      if (entry.valid && entry.bid == bid) {
        released += cmap(entry.archTag)
        free = free.updated(cmap(entry.archTag), true)
        cmap = cmap.updated(entry.archTag, entry.physTag)
        mapQ = mapQ.updated(idx, entry.copy(valid = false))
      }
    }
    state.copy(cmap = cmap, free = free, mapQ = mapQ) -> released
  }

  def flush(state: State, flushBid: ROBIDValue, flushRid: ROBIDValue, baseOnBid: Boolean, entries: Int): (State, Set[Int]) = {
    val restoreBid = ROBIDReference.sub(flushBid, 1, entries)
    val needRestore = ROBIDReference.lessEqual(restoreBid, state.renamePtr, entries)
    var smap =
      if (!needRestore) state.smap
      else if (state.checkpointValid(restoreBid.value)) state.checkpoints(restoreBid.value)
      else state.cmap
    var renamePtr = state.renamePtr
    if (needRestore) {
      renamePtr = restoreBid
    }

    var mapQ = state.mapQ
    var free = state.free
    var released = Set.empty[Int]
    for ((entry, idx) <- state.mapQ.zipWithIndex) {
      if (entry.valid) {
        val prune =
          if (baseOnBid) ROBIDReference.lessEqual(flushBid, entry.bid, entries)
          else ROBIDReference.lessEqualBidRid(flushBid, flushRid, entry.bid, entry.rid, entries)
        if (prune) {
          released += entry.physTag
          free = free.updated(entry.physTag, true)
          mapQ = mapQ.updated(idx, entry.copy(valid = false))
        }
      }
      val afterPrune = mapQ(idx)
      if (afterPrune.valid && !baseOnBid && afterPrune.bid == flushBid) {
        smap = smap.updated(afterPrune.archTag, afterPrune.physTag)
      }
    }

    state.copy(smap = smap, renamePtr = renamePtr, free = free, mapQ = mapQ) -> released
  }

  implicit final class ROBIDReferenceOps(private val ref: ROBIDReference.type) extends AnyVal {
    def lessEqual(lhs: ROBIDValue, rhs: ROBIDValue, entries: Int): Boolean =
      ref.less(lhs, rhs) || lhs == rhs

    def lessEqualBidRid(srcBid: ROBIDValue, srcRid: ROBIDValue, dstBid: ROBIDValue, dstRid: ROBIDValue, entries: Int): Boolean =
      ref.less(srcBid, dstBid) || (srcBid == dstBid && lessEqual(srcRid, dstRid, entries))
  }
}

class GPRRenameCheckpointSpec extends AnyFunSuite {
  import GPRRenameCheckpointReference._

  test("initial state mirrors LinxCoreModel GPRRename Build identity maps and free tags") {
    val s = initial()

    assert(s.smap == (0 until 24).toVector)
    assert(s.cmap == (0 until 24).toVector)
    assert(s.free.take(24).forall(!_))
    assert(s.free.drop(24).forall(identity))
    assert(s.freeCount == 40)
    assert(s.mapQFreeCount == 8)
  }

  test("rename source/destination behavior allocates first free tag and inserts mapQ in order") {
    val (s1, p24) = rename(initial(), archTag = 2, bid = ROBIDValue(value = 1), rid = ROBIDValue(value = 0))
    val (s2, p25) = rename(s1, archTag = 3, bid = ROBIDValue(value = 1), rid = ROBIDValue(value = 1))

    assert(p24 == 24)
    assert(p25 == 25)
    assert(s2.smap(2) == 24)
    assert(s2.smap(3) == 25)
    assert(s2.mapQ.take(2).map(_.physTag) == Vector(24, 25))
    assert(s2.freeCount == 38)
  }

  test("block commit walks mapQ in allocation order and frees overwritten committed tags") {
    val (s1, p24) = rename(initial(), archTag = 2, bid = ROBIDValue(value = 1), rid = ROBIDValue(value = 0))
    val (s2, p25) = rename(s1, archTag = 2, bid = ROBIDValue(value = 1), rid = ROBIDValue(value = 1))
    val (s3, released) = commit(s2, ROBIDValue(value = 1))

    assert(p24 == 24)
    assert(p25 == 25)
    assert(released == Set(2, 24))
    assert(s3.cmap(2) == 25)
    assert(s3.mapQ.forall(!_.valid))
    assert(s3.free(2))
    assert(s3.free(24))
    assert(!s3.free(25))
  }

  test("base-on-BID flush restores from prior checkpoint and prunes younger mapQ rows") {
    val bid1 = ROBIDValue(value = 1)
    val bid2 = ROBIDValue(value = 2)
    val (s1, _) = rename(initial(), archTag = 2, bid = bid1, rid = ROBIDValue(value = 0))
    val s2 = checkpoint(s1, bid1)
    val (s3, p25) = rename(s2, archTag = 3, bid = bid2, rid = ROBIDValue(value = 0))
    val (s4, released) = flush(s3, flushBid = bid2, flushRid = ROBIDValue(value = 0), baseOnBid = true, entries = 8)

    assert(p25 == 25)
    assert(s4.renamePtr == bid1)
    assert(s4.smap == s2.smap)
    assert(released == Set(25))
    assert(s4.mapQ.count(_.valid) == 1)
    assert(s4.mapQ.exists(e => e.valid && e.physTag == 24))
  }

  test("non-BID flush keeps same-BID older mapQ entries visible in smap after restore") {
    val bid1 = ROBIDValue(value = 1)
    val (s1, _) = rename(initial(), archTag = 2, bid = bid1, rid = ROBIDValue(value = 0))
    val s2 = checkpoint(s1, bid1)
    val (s3, _) = rename(s2, archTag = 3, bid = bid1, rid = ROBIDValue(value = 1))
    val (s4, _) = rename(s3, archTag = 4, bid = bid1, rid = ROBIDValue(value = 2))
    val (s5, released) = flush(s4, flushBid = bid1, flushRid = ROBIDValue(value = 1), baseOnBid = false, entries = 8)

    assert(released == Set(25, 26))
    assert(s5.smap(2) == 24)
    assert(s5.smap(3) == 3)
    assert(s5.smap(4) == 4)
    assert(s5.mapQ.count(_.valid) == 1)
  }

  test("Chisel GPRRenameCheckpoint elaborates cleanup, map, checkpoint, and release outputs") {
    val sv = ChiselStage.emitSystemVerilog(new GPRRenameCheckpoint(entries = 8, physRegs = 64, mapQDepth = 8))

    assert(sv.contains("module GPRRenameCheckpoint"))
    assert(sv.contains("io_cleanup_renameFlushValid"))
    assert(sv.contains("io_restoreFromCheckpoint"))
    assert(sv.contains("io_smap_0"))
    assert(sv.contains("io_cmap_0"))
    assert(sv.contains("io_releasedPhysMask"))
  }
}
