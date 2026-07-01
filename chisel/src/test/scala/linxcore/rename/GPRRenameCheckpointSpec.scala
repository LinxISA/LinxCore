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
      archTag: Int,
      physTag: Int)

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
      mapQ = Vector.fill(mapQDepth)(
        MapQEntry(
          valid = false,
          bid = ROBIDValue(),
          fullBid = 0,
          rid = ROBIDValue(),
          archTag = 0,
          physTag = 0))
    )
  }

  private def defaultFullBid(bid: ROBIDValue, entries: Int = 8): BigInt =
    BigInt(bid.value) + (if (bid.wrap) BigInt(entries) else BigInt(0))

  def rename(
      state: State,
      archTag: Int,
      bid: ROBIDValue,
      rid: ROBIDValue,
      fullBid: BigInt = -1): (State, Int) = {
    require(state.freeCount > 0)
    require(state.mapQFreeCount > 0)
    val storedFullBid = if (fullBid >= 0) fullBid else defaultFullBid(bid)
    val phys = state.free.indexWhere(identity)
    val mapIdx = state.mapQ.indexWhere(!_.valid)
    val next = state.copy(
      smap = state.smap.updated(archTag, phys),
      free = state.free.updated(phys, false),
      mapQ = state.mapQ.updated(
        mapIdx,
        MapQEntry(valid = true, bid = bid, fullBid = storedFullBid, rid = rid, archTag = archTag, physTag = phys))
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
    var free = state.free
    var mapQ = state.mapQ
    var released = Set.empty[Int]

    for ((entry, idx) <- state.mapQ.zipWithIndex) {
      if (entry.valid && entry.fullBid == targetFullBid) {
        if (cmap(entry.archTag) >= state.smap.length) {
          released += cmap(entry.archTag)
          free = free.updated(cmap(entry.archTag), true)
        }
        cmap = cmap.updated(entry.archTag, entry.physTag)
        mapQ = mapQ.updated(idx, entry.copy(valid = false))
      }
    }
    protectLive(state.copy(cmap = cmap, free = free, mapQ = mapQ)) -> released
  }

  def flush(
      state: State,
      flushBid: ROBIDValue,
      flushRid: ROBIDValue,
      baseOnBid: Boolean,
      entries: Int,
      flushFullBid: BigInt = -1): (State, Set[Int]) = {
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
          else targetFullBid < entry.fullBid || (targetFullBid == entry.fullBid && ROBIDReference.lessEqual(flushRid, entry.rid, entries))
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
              (lhs.fullBid == rhs.fullBid && ROBIDReference.less(lhs.rid, rhs.rid))
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
