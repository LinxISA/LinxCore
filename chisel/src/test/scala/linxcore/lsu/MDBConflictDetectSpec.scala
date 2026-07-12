package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object MDBConflictDetectReference {
  import STQFlushPruneReference.Id

  final case class Store(
      valid: Boolean = true,
      addrOnly: Boolean = false,
      isTile: Boolean = false,
      stid: Int = 0,
      bid: Id = Id(),
      lsId: Id = Id(),
      lsIdFullValid: Boolean = true,
      lsIdFull: Option[BigInt] = None,
      lsidWidth: Int = 32,
      pc: BigInt = 0,
      addr: BigInt = 0,
      size: BigInt = 4)

  final case class Load(
      valid: Boolean = true,
      resolved: Boolean = false,
      isTile: Boolean = false,
      stid: Int = 0,
      bid: Id = Id(),
      lsId: Id = Id(),
      lsIdFullValid: Boolean = true,
      lsIdFull: Option[BigInt] = None,
      lsidWidth: Int = 32,
      pc: BigInt = 0,
      addr: BigInt = 0,
      size: BigInt = 4)

  final case class Result(
      activeCandidateMask: Int,
      resolveCandidateMask: Int,
      tileSuppressedActiveMask: Int,
      tileSuppressedResolveMask: Int,
      waitStoreMask: Int,
      conflictValid: Boolean,
      conflictFromResolveQueue: Boolean,
      conflictOrdinal: Int,
      innerFlush: Boolean,
      nukeFlush: Boolean,
      selectedLoad: Option[Load])

  private def less(lhs: Id, rhs: Id): Boolean =
    if (lhs.wrap == rhs.wrap) lhs.value < rhs.value else lhs.value > rhs.value

  private def lessEqual(lhs: Id, rhs: Id): Boolean =
    less(lhs, rhs) || lhs == rhs

  private def fullValue(lsId: Id, full: Option[BigInt]): BigInt = full.getOrElse(BigInt(lsId.value))

  private def lessFull(lhs: BigInt, rhs: BigInt, width: Int): Boolean = {
    val mask = (BigInt(1) << width) - 1
    val distance = (rhs - lhs) & mask
    lhs != rhs && distance < (BigInt(1) << (width - 1))
  }

  private def lessEqualBidLs(store: Store, load: Load): Boolean =
    less(store.bid, load.bid) ||
      (store.bid == load.bid && store.lsIdFullValid && load.lsIdFullValid &&
        (fullValue(store.lsId, store.lsIdFull) == fullValue(load.lsId, load.lsIdFull) ||
          lessFull(
            fullValue(store.lsId, store.lsIdFull),
            fullValue(load.lsId, load.lsIdFull),
            store.lsidWidth)))

  private def lessEqualLoads(lhs: Load, rhs: Load): Boolean =
    less(lhs.bid, rhs.bid) ||
      (lhs.bid == rhs.bid && lhs.lsIdFullValid && rhs.lsIdFullValid &&
        (fullValue(lhs.lsId, lhs.lsIdFull) == fullValue(rhs.lsId, rhs.lsIdFull) ||
          lessFull(fullValue(lhs.lsId, lhs.lsIdFull), fullValue(rhs.lsId, rhs.lsIdFull), lhs.lsidWidth)))

  private def overlap(store: Store, load: Load): Boolean = {
    store.size != 0 && load.size != 0 &&
      store.addr < load.addr + load.size &&
      load.addr < store.addr + store.size
  }

  private def orderedOverlap(store: Store, load: Load): Boolean =
    store.valid && load.valid && store.stid == load.stid && overlap(store, load) &&
      lessEqualBidLs(store, load)

  private def scalarConflict(store: Store, load: Load): Boolean =
    orderedOverlap(store, load) && !store.isTile && !load.isTile

  private def tileSuppressed(store: Store, load: Load): Boolean =
    orderedOverlap(store, load) && (store.isTile || load.isTile)

  private def mask(bits: Seq[Boolean]): Int =
    bits.zipWithIndex.foldLeft(0) { case (acc, (bit, index)) => if (bit) acc | (1 << index) else acc }

  def detect(store: Store, activeLoads: Seq[Load], resolvedQueue: Seq[Load]): Result = {
    val activeConflicts = activeLoads.map(load => scalarConflict(store, load))
    val activeResolved = activeLoads.zip(activeConflicts).map { case (load, hit) => hit && load.resolved }
    val activeTile = activeLoads.map(load => tileSuppressed(store, load))
    val waitMask = activeLoads.zip(activeConflicts).map { case (load, hit) => hit && !load.resolved && store.addrOnly }
    val resolveConflicts = resolvedQueue.map(load => scalarConflict(store, load))
    val resolveTile = resolvedQueue.map(load => tileSuppressed(store, load))

    val candidates =
      activeLoads.zip(activeResolved).map { case (load, valid) => (load, valid) } ++
        resolvedQueue.zip(resolveConflicts).map { case (load, valid) => (load, valid) }

    val selected = candidates.zipWithIndex.foldLeft(Option.empty[(Load, Int)]) {
      case (best, ((candidate, valid), ordinal)) if valid =>
        best match {
          case Some((cur, _)) if !lessEqualLoads(candidate, cur) => best
          case _ => Some(candidate -> ordinal)
        }
      case (best, _) => best
    }

    Result(
      activeCandidateMask = mask(activeResolved),
      resolveCandidateMask = mask(resolveConflicts),
      tileSuppressedActiveMask = mask(activeTile),
      tileSuppressedResolveMask = mask(resolveTile),
      waitStoreMask = mask(waitMask),
      conflictValid = selected.nonEmpty,
      conflictFromResolveQueue = selected.exists(_._2 >= activeLoads.length),
      conflictOrdinal = selected.map(_._2).getOrElse(0),
      innerFlush = selected.exists { case (load, _) => load.bid == store.bid },
      nukeFlush = selected.exists { case (load, _) => load.bid != store.bid },
      selectedLoad = selected.map(_._1)
    )
  }
}

class MDBConflictDetectSpec extends AnyFunSuite {
  import MDBConflictDetectReference._
  import STQFlushPruneReference.Id

  private def id(value: Int, wrap: Boolean = false): Id =
    Id(wrap = wrap, value = value)

  test("resolved active load conflict records same-BID inner flush") {
    val store = Store(bid = id(2), lsId = id(1), pc = 0x2000, addr = 0x1000, size = 8)
    val active = Seq(
      Load(valid = false, bid = id(0), lsId = id(0)),
      Load(resolved = true, bid = id(2), lsId = id(3), pc = 0x3000, addr = 0x1004, size = 4),
      Load(resolved = false, bid = id(3), lsId = id(0), addr = 0x2000, size = 4)
    )

    val result = detect(store, active, Seq.empty)

    assert(result.activeCandidateMask == 0x2)
    assert(result.conflictValid)
    assert(!result.conflictFromResolveQueue)
    assert(result.conflictOrdinal == 1)
    assert(result.innerFlush)
    assert(!result.nukeFlush)
    assert(result.selectedLoad.map(_.pc).contains(0x3000))
  }

  test("selects the oldest conflicting load across active rows and ResolveQ") {
    val store = Store(bid = id(1), lsId = id(0), addr = 0x1000, size = 16)
    val active = Seq(
      Load(resolved = true, bid = id(5), lsId = id(0), pc = 0x5000, addr = 0x1008, size = 4),
      Load(resolved = true, bid = id(3), lsId = id(2), pc = 0x3200, addr = 0x1002, size = 4)
    )
    val resolved = Seq(
      Load(resolved = true, bid = id(3), lsId = id(1), pc = 0x3100, addr = 0x1001, size = 4),
      Load(resolved = true, bid = id(4), lsId = id(0), pc = 0x4000, addr = 0x1004, size = 4)
    )

    val result = detect(store, active, resolved)

    assert(result.activeCandidateMask == 0x3)
    assert(result.resolveCandidateMask == 0x3)
    assert(result.conflictValid)
    assert(result.conflictFromResolveQueue)
    assert(result.conflictOrdinal == 2)
    assert(result.nukeFlush)
    assert(result.selectedLoad.map(_.pc).contains(0x3100))
  }

  test("store younger than load is not a conflict") {
    val store = Store(bid = id(5), lsId = id(0), addr = 0x1000, size = 8)
    val active = Seq(Load(resolved = true, bid = id(4), lsId = id(7), addr = 0x1000, size = 8))

    val result = detect(store, active, Seq.empty)

    assert(!result.conflictValid)
    assert(result.activeCandidateMask == 0)
    assert(result.waitStoreMask == 0)
  }

  test("same-BID conflict ordering uses full LSID high bits and rejects missing authority") {
    val width = 40
    val store = Store(
      bid = id(2), lsId = id(1), lsIdFull = Some(BigInt("100000001", 16)),
      lsidWidth = width, addr = 0x1000, size = 8)
    val younger = Load(
      resolved = true, bid = id(2), lsId = id(1),
      lsIdFull = Some(BigInt("200000001", 16)), lsidWidth = width,
      addr = 0x1000, size = 8)
    val missing = younger.copy(lsIdFullValid = false)

    assert(detect(store, Seq(younger), Seq.empty).conflictValid)
    assert(!detect(store, Seq(missing), Seq.empty).conflictValid)
  }

  test("ST_ADDR probe marks younger unresolved loads as waiting without flushing") {
    val store = Store(addrOnly = true, bid = id(2), lsId = id(4), pc = 0x2040, addr = 0x1800, size = 8)
    val active = Seq(
      Load(resolved = false, bid = id(2), lsId = id(5), addr = 0x1804, size = 4),
      Load(resolved = false, bid = id(2), lsId = id(6), addr = 0x1806, size = 2)
    )

    val result = detect(store, active, Seq.empty)

    assert(result.waitStoreMask == 0x3)
    assert(result.activeCandidateMask == 0)
    assert(!result.conflictValid)
    assert(!result.innerFlush)
    assert(!result.nukeFlush)
  }

  test("tile conflicts are suppressed until the tile nuke owner exists") {
    val store = Store(isTile = true, bid = id(1), lsId = id(0), addr = 0x1000, size = 64)
    val active = Seq(Load(resolved = true, bid = id(2), lsId = id(0), addr = 0x1010, size = 16))
    val resolved = Seq(Load(resolved = true, isTile = true, bid = id(3), lsId = id(0), addr = 0x1020, size = 16))

    val result = detect(store, active, resolved)

    assert(!result.conflictValid)
    assert(result.tileSuppressedActiveMask == 0x1)
    assert(result.tileSuppressedResolveMask == 0x1)
    assert(result.activeCandidateMask == 0)
    assert(result.resolveCandidateMask == 0)
  }

  test("zero-size accesses do not overlap") {
    val store = Store(bid = id(1), lsId = id(0), addr = 0x1000, size = 0)
    val active = Seq(Load(resolved = true, bid = id(2), lsId = id(0), addr = 0x1000, size = 8))

    val result = detect(store, active, Seq.empty)

    assert(!result.conflictValid)
    assert(result.activeCandidateMask == 0)
  }

  test("Chisel MDBConflictDetect elaborates with ROB-facing conflict outputs") {
    val sv = ChiselStage.emitSystemVerilog(new MDBConflictDetect(
      entries = 8, loadEntries = 4, resolveEntries = 2, lsidWidth = 40))

    assert(sv.contains("module MDBConflictDetect"))
    assert(sv.contains("io_waitStoreMask"))
    assert(sv.contains("io_innerFlush"))
    assert(sv.contains("io_nukeFlush"))
    assert(sv.contains("io_record_load_pc"))
    assert(sv.contains("io_record_load_lsIdFull"))
  }
}
