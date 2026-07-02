package linxcore.rename

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

import linxcore.rob.ROBIDReference
import linxcore.rob.ROBIDValue

object GPRRenameCheckpointReference {
  final case class MapQEntry(
      valid: Boolean,
      bid: ROBIDValue,
      fullBid: BigInt,
      rid: ROBIDValue,
      order: BigInt = 0,
      archTag: Int,
      physTag: Int)

  final case class State(
      smap: Vector[Int],
      cmap: Vector[Int],
      cmapFullBid: Vector[BigInt],
      cmapRid: Vector[ROBIDValue],
      cmapOrder: Vector[BigInt],
      checkpoints: Vector[Vector[Int]],
      checkpointValid: Vector[Boolean],
      renamePtr: ROBIDValue,
      free: Vector[Boolean],
      mapQ: Vector[MapQEntry],
      nextOrder: BigInt) {
    def freeCount: Int = free.count(identity)
    def mapQFreeCount: Int = mapQ.count(!_.valid)
  }

  def initial(archRegs: Int = 24, physRegs: Int = 64, entries: Int = 8, mapQDepth: Int = 8): State = {
    val identity = (0 until archRegs).toVector
    State(
      smap = identity,
      cmap = identity,
      cmapFullBid = Vector.fill(archRegs)(BigInt(0)),
      cmapRid = Vector.fill(archRegs)(ROBIDValue()),
      cmapOrder = Vector.fill(archRegs)(BigInt(0)),
      checkpoints = Vector.fill(entries)(identity),
      checkpointValid = Vector.fill(entries)(false),
      renamePtr = ROBIDValue(),
      free = (0 until physRegs).map(_ >= archRegs).toVector,
      mapQ = Vector.fill(mapQDepth)(
        MapQEntry(
          valid = false,
          bid = ROBIDValue(),
          fullBid = 0,
          rid = ROBIDValue(),
          order = 0,
          archTag = 0,
          physTag = 0)),
      nextOrder = 0
    )
  }

  private def defaultFullBid(bid: ROBIDValue, entries: Int = 8): BigInt =
    BigInt(bid.value) + (if (bid.wrap) BigInt(entries) else BigInt(0))

  def rename(
      state: State,
      archTag: Int,
      bid: ROBIDValue,
      rid: ROBIDValue,
      fullBid: BigInt = -1,
      order: BigInt = -1): (State, Int) = {
    require(state.freeCount > 0)
    require(state.mapQFreeCount > 0)
    val storedFullBid = if (fullBid >= 0) fullBid else defaultFullBid(bid)
    val storedOrder = if (order >= 0) order else state.nextOrder
    val phys = state.free.indexWhere(identity)
    val mapIdx = state.mapQ.indexWhere(!_.valid)
    val next = state.copy(
      smap = state.smap.updated(archTag, phys),
      free = state.free.updated(phys, false),
      nextOrder = state.nextOrder.max(storedOrder + 1),
      mapQ = state.mapQ.updated(
        mapIdx,
        MapQEntry(
          valid = true,
          bid = bid,
          fullBid = storedFullBid,
          rid = rid,
          order = storedOrder,
          archTag = archTag,
          physTag = phys))
    )
    next -> phys
  }

  private def protectLive(state: State): State = {
    val live = state.smap.toSet ++ state.cmap.toSet ++ state.mapQ.collect {
      case entry if entry.valid => entry.physTag
    }
    state.copy(free = state.free.indices.map(phys => phys >= state.smap.length && !live.contains(phys)).toVector)
  }

  def checkpoint(state: State, bid: ROBIDValue): State =
    state.copy(
      checkpoints = state.checkpoints.updated(bid.value, state.smap),
      checkpointValid = state.checkpointValid.updated(bid.value, true),
      renamePtr = bid
    )

  def commit(state: State, bid: ROBIDValue, fullBid: BigInt = -1): (State, Set[Int]) = {
    val targetFullBid = if (fullBid >= 0) fullBid else defaultFullBid(bid)
    var cmap = state.cmap
    var cmapFullBid = state.cmapFullBid
    var cmapRid = state.cmapRid
    var cmapOrder = state.cmapOrder
    var free = state.free
    var mapQ = state.mapQ
    var released = Set.empty[Int]

    val hits = state.mapQ.zipWithIndex.collect {
      case (entry, idx) if entry.valid && entry.fullBid == targetFullBid => (entry, idx)
    }
    val newestByArch = hits
      .map(_._1)
      .groupBy(_.archTag)
      .view
      .mapValues { entries =>
        entries.reduce { (lhs, rhs) =>
          if (lhs.order < rhs.order) rhs else lhs
        }
      }
      .toMap

    for ((entry, idx) <- hits) {
      val newest = newestByArch(entry.archTag)
      if (entry == newest) {
        if (cmap(entry.archTag) >= state.smap.length) {
          released += cmap(entry.archTag)
          free = free.updated(cmap(entry.archTag), true)
        }
        cmap = cmap.updated(entry.archTag, entry.physTag)
        cmapFullBid = cmapFullBid.updated(entry.archTag, targetFullBid)
        cmapRid = cmapRid.updated(entry.archTag, entry.rid)
        cmapOrder = cmapOrder.updated(entry.archTag, entry.order)
      } else if (entry.physTag >= state.smap.length) {
        released += entry.physTag
        free = free.updated(entry.physTag, true)
      }
      mapQ = mapQ.updated(idx, entry.copy(valid = false))
    }
    protectLive(
      state.copy(cmap = cmap, cmapFullBid = cmapFullBid, cmapRid = cmapRid, cmapOrder = cmapOrder, free = free, mapQ = mapQ)) -> released
  }

  def flush(
      state: State,
      flushBid: ROBIDValue,
      flushRid: ROBIDValue,
      baseOnBid: Boolean,
      entries: Int,
      flushFullBid: BigInt = -1,
      flushOrder: BigInt = -1): (State, Set[Int]) = {
    val targetFullBid = if (flushFullBid >= 0) flushFullBid else defaultFullBid(flushBid, entries)
    val restoreBid = ROBIDReference.sub(flushBid, 1, entries)
    val needRestore = ROBIDReference.lessEqual(restoreBid, state.renamePtr, entries)
    val restoreFromCheckpoint = needRestore && state.checkpointValid(restoreBid.value)
    var smap =
      if (restoreFromCheckpoint) state.checkpoints(restoreBid.value)
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
          if (baseOnBid) targetFullBid <= entry.fullBid
          else
            targetFullBid < entry.fullBid ||
              (targetFullBid == entry.fullBid &&
                (if (flushOrder >= 0) flushOrder < entry.order
                 else ROBIDReference.lessEqual(flushRid, entry.rid, entries)))
        if (prune) {
          if (entry.physTag >= state.smap.length) {
            released += entry.physTag
            free = free.updated(entry.physTag, true)
          }
          mapQ = mapQ.updated(idx, entry.copy(valid = false))
        }
      }
    }

    if (restoreFromCheckpoint) {
      for (arch <- state.smap.indices) {
        val committedBeforeFlush =
          !baseOnBid &&
            (state.cmapFullBid(arch) < targetFullBid ||
              (state.cmapFullBid(arch) == targetFullBid &&
                (if (flushOrder >= 0) state.cmapOrder(arch) <= flushOrder
                 else ROBIDReference.less(state.cmapRid(arch), flushRid))))
        if (committedBeforeFlush) {
          smap = smap.updated(arch, state.cmap(arch))
        }
      }
      for ((entry, idx) <- mapQ.zipWithIndex) {
        if (entry.valid && !baseOnBid && entry.fullBid == targetFullBid) {
          smap = smap.updated(entry.archTag, entry.physTag)
        }
      }
    } else {
      for (arch <- state.smap.indices) {
        val newest = mapQ.filter(entry => entry.valid && entry.archTag == arch).reduceOption { (lhs, rhs) =>
          val lhsOlder =
            lhs.fullBid < rhs.fullBid ||
              (lhs.fullBid == rhs.fullBid && lhs.order < rhs.order)
          if (lhsOlder) rhs else lhs
        }
        newest.foreach(entry => smap = smap.updated(arch, entry.physTag))
      }
    }

    protectLive(state.copy(smap = smap, renamePtr = renamePtr, free = free, mapQ = mapQ)) -> released
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
    assert(released == Set(24))
    assert(s3.cmap(2) == 25)
    assert(s3.mapQ.forall(!_.valid))
    assert(!s3.free(2))
    assert(s3.free(24))
    assert(!s3.free(25))
  }

  test("committing first architectural writes never returns identity tags to the free pool") {
    val bid1 = ROBIDValue(value = 1)
    val bid2 = ROBIDValue(value = 2)
    val (s1, p24) = rename(initial(), archTag = 4, bid = bid1, rid = ROBIDValue(value = 0))
    val (s2, released) = commit(s1, bid1)
    val (s3, p25) = rename(s2, archTag = 8, bid = bid2, rid = ROBIDValue(value = 0))

    assert(p24 == 24)
    assert(released.isEmpty)
    assert(!s2.free.take(24).exists(identity))
    assert(p25 == 25)
    assert(s3.smap(4) == 24)
    assert(s3.smap(8) == 25)
  }

  test("commit keeps a physical tag allocated while any map still references it") {
    val bid1 = ROBIDValue(value = 1)
    val bid2 = ROBIDValue(value = 2)
    val base = initial()
    val staleFree = base.copy(
      smap = base.smap.updated(4, 28),
      cmap = base.cmap.updated(4, 28),
      free = base.free.zipWithIndex.map {
        case (_, phys) if phys >= 24 && phys < 28 => false
        case (_, 28) => true
        case (free, _) => free
      }
    )
    val (s1, p28) = rename(staleFree, archTag = 4, bid = bid1, rid = ROBIDValue(value = 0))
    val (s2, _) = commit(s1, bid1)
    val (s3, nextPhys) = rename(s2, archTag = 8, bid = bid2, rid = ROBIDValue(value = 0))

    assert(p28 == 28)
    assert(!s2.free(28))
    assert(nextPhys != 28)
    assert(s3.smap(4) == 28)
  }

  test("block commit selects newest same-arch RID independent of mapQ index order") {
    val bid1 = ROBIDValue(value = 1)
    val older = MapQEntry(
      valid = true,
      bid = bid1,
      fullBid = 1,
      rid = ROBIDValue(value = 1),
      order = 10,
      archTag = 4,
      physTag = 24)
    val newer = older.copy(rid = ROBIDValue(value = 3), order = 11, physTag = 25)
    val base = initial()
    val state = base.copy(
      cmap = base.cmap.updated(4, 28),
      mapQ = Vector(newer, older) ++ Vector.fill(6)(
        MapQEntry(
          valid = false,
          bid = ROBIDValue(),
          fullBid = 0,
          rid = ROBIDValue(),
          archTag = 0,
          physTag = 0)))
    val (committed, released) = commit(state, bid1)

    assert(committed.cmap(4) == 25)
    assert(committed.cmapRid(4) == ROBIDValue(value = 3))
    assert(committed.mapQ.forall(!_.valid))
    assert(released == Set(24, 28))
  }

  test("non-BID flush uses row order when same-block RID wraps") {
    val blockBid = BigInt(376)
    val flushBid = ROBIDValue(valid = true, wrap = false, value = 1)
    val base = checkpoint(initial(), ROBIDValue(value = 0))
    val olderSameBlockWrite = MapQEntry(
      valid = true,
      bid = flushBid,
      fullBid = blockBid,
      rid = ROBIDValue(valid = true, wrap = true, value = 4),
      order = 100,
      archTag = 4,
      physTag = 38)
    val youngerSameBlockWrite = MapQEntry(
      valid = true,
      bid = flushBid,
      fullBid = blockBid,
      rid = ROBIDValue(valid = true, wrap = false, value = 7),
      order = 102,
      archTag = 5,
      physTag = 39)
    val state = base.copy(
      free = base.free.updated(38, false).updated(39, false),
      mapQ = Vector(olderSameBlockWrite, youngerSameBlockWrite) ++ Vector.fill(6)(
        MapQEntry(
          valid = false,
          bid = ROBIDValue(),
          fullBid = 0,
          rid = ROBIDValue(),
          order = 0,
          archTag = 0,
          physTag = 0)))
    val (flushed, released) =
      flush(
        state,
        flushBid = flushBid,
        flushRid = ROBIDValue(valid = true, wrap = false, value = 6),
        baseOnBid = false,
        entries = 8,
        flushFullBid = blockBid,
        flushOrder = 101)

    assert(flushed.smap(4) == 38)
    assert(flushed.smap(5) == 5)
    assert(flushed.mapQ.exists(e => e.valid && e.archTag == 4 && e.physTag == 38))
    assert(!flushed.mapQ.exists(e => e.valid && e.archTag == 5 && e.physTag == 39))
    assert(released == Set(39))
  }

  test("commit recomputes free tags from live smap cmap and mapQ ownership") {
    val bid1 = ROBIDValue(value = 1)
    val base = initial(physRegs = 128, mapQDepth = 256)
    val leakedFree = base.copy(free = base.free.updated(127, false))
    val (renamed, allocated) = rename(leakedFree, archTag = 2, bid = bid1, rid = ROBIDValue(value = 0))
    val (committed, _) = commit(renamed, bid1)

    assert(allocated == 24)
    assert(!committed.free(24))
    assert(committed.free(127))
    assert(committed.freeCount == 103)
    assert(committed.mapQFreeCount == 256)
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

  test("non-BID flush keeps committed same-block older writes visible after mapQ commit") {
    val priorBid = ROBIDValue(value = 0)
    val bid1 = ROBIDValue(value = 1)
    val base = checkpoint(initial(), priorBid)
    val (s1, p24) = rename(base, archTag = 4, bid = bid1, rid = ROBIDValue(value = 4))
    val (s2, p25) = rename(s1, archTag = 5, bid = bid1, rid = ROBIDValue(value = 6))
    val (committed, _) = commit(s2, bid1)
    val (flushed, _) = flush(committed, flushBid = bid1, flushRid = ROBIDValue(value = 5), baseOnBid = false, entries = 8)

    assert(flushed.mapQ.forall(!_.valid))
    assert(flushed.smap(4) == p24)
    assert(flushed.smap(5) == 5)
    assert(p25 == 25)
  }

  test("non-BID flush overlays older-block committed cmap over stale checkpoint") {
    val baseBid = ROBIDValue(value = 0)
    val committedBid = ROBIDValue(value = 1)
    val flushBid = ROBIDValue(value = 2)
    val base = checkpoint(initial(), baseBid)
    val (renamed, p24) = rename(base, archTag = 4, bid = committedBid, rid = ROBIDValue(value = 3))
    val (committed, _) = commit(renamed, committedBid)
    val (later, _) = rename(committed, archTag = 5, bid = flushBid, rid = ROBIDValue(value = 1))
    val (flushed, released) = flush(later, flushBid = flushBid, flushRid = ROBIDValue(value = 1), baseOnBid = false, entries = 8)

    assert(p24 == 24)
    assert(released == Set(25))
    assert(flushed.smap(4) == p24)
    assert(flushed.smap(5) == 5)
    assert(flushed.mapQ.forall(!_.valid))
  }

  test("non-BID flush without checkpoints rebuilds smap from older surviving wrapped-BID mapQ entries") {
    val oldLoopBid = ROBIDValue(valid = true, wrap = true, value = 2)
    val markerBid = ROBIDValue(valid = true, wrap = true, value = 3)
    val (s1, p24) = rename(initial(), archTag = 8, bid = oldLoopBid, rid = ROBIDValue(value = 1))
    val (s2, released) = flush(s1, flushBid = markerBid, flushRid = ROBIDValue(value = 0), baseOnBid = false, entries = 8)

    assert(p24 == 24)
    assert(released.isEmpty)
    assert(s2.renamePtr == ROBIDReference.sub(markerBid, 1, entries = 8))
    assert(s2.smap(8) == 24)
    assert(s2.mapQ.exists(e => e.valid && e.archTag == 8 && e.physTag == 24))
  }

  test("survivor replay orders scalar GPR mapQ by full block BID across wrapped ROBID aliases") {
    val olderFullBid = BigInt(71)
    val newerFullBid = BigInt(146)
    val olderWrappedBid = ROBIDValue(valid = true, wrap = false, value = 7)
    val newerWrappedBid = ROBIDValue(valid = true, wrap = false, value = 2)
    val flushAfterBoth = ROBIDValue(valid = true, wrap = false, value = 3)
    val (s1, olderPhys) =
      rename(initial(), archTag = 6, bid = olderWrappedBid, rid = ROBIDValue(value = 2), fullBid = olderFullBid)
    val (s2, newerPhys) =
      rename(s1, archTag = 6, bid = newerWrappedBid, rid = ROBIDValue(value = 1), fullBid = newerFullBid)
    val (s3, released) =
      flush(
        s2,
        flushBid = flushAfterBoth,
        flushRid = ROBIDValue(value = 7),
        baseOnBid = false,
        entries = 8,
        flushFullBid = 200)

    assert(olderPhys == 24)
    assert(newerPhys == 25)
    assert(released.isEmpty)
    assert(s3.smap(6) == newerPhys)
    assert(s3.mapQ.exists(e => e.valid && e.archTag == 6 && e.fullBid == olderFullBid && e.physTag == olderPhys))
    assert(s3.mapQ.exists(e => e.valid && e.archTag == 6 && e.fullBid == newerFullBid && e.physTag == newerPhys))
  }

  test("Chisel GPRRenameCheckpoint elaborates cleanup, map, checkpoint, and release outputs") {
    val sv = ChiselStage.emitSystemVerilog(new GPRRenameCheckpoint(entries = 8, physRegs = 64, mapQDepth = 8))

    assert(sv.contains("module GPRRenameCheckpoint"))
    assert(sv.contains("io_renameBlockBid"))
    assert(sv.contains("io_commitBlockBid"))
    assert(sv.contains("io_cleanup_renameFlushValid"))
    assert(sv.contains("io_postRenameCheckpointValid"))
    assert(sv.contains("io_postRenameCheckpointBid_value"))
    assert(sv.contains("io_restoreFromCheckpoint"))
    assert(sv.contains("io_smap_0"))
    assert(sv.contains("io_cmap_0"))
    assert(sv.contains("io_releasedPhysMask"))
  }

  test("Chisel GPRRenameCheckpoint elaborates model GGPR physical capacity") {
    val sv = ChiselStage.emitSystemVerilog(new GPRRenameCheckpoint(entries = 8, physRegs = 128, mapQDepth = 8))

    assert(sv.contains("module GPRRenameCheckpoint"))
    assert(sv.contains("output [127:0] io_freeMask"))
    assert(sv.contains("output [127:0] io_releasedPhysMask"))
  }

  test("Chisel GPRRenameCheckpoint elaborates model-sized GGPR mapQ through helper modules") {
    val sv = ChiselStage.emitSystemVerilog(new GPRRenameCheckpoint(entries = 8, physRegs = 128, mapQDepth = 256))

    assert(sv.contains("module GPRRenameCheckpoint"))
    assert(sv.contains("module GPRRenameReplaySurvivorSelect"))
    assert(sv.contains("module GPRRenameCommitArchSelect"))
    assert(sv.contains("output [255:0] io_mapQValidMask"))
  }
}
