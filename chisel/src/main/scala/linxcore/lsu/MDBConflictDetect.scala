package linxcore.lsu

import chisel3._
import chisel3.util.{log2Ceil, PopCount}

import linxcore.rob.ROBID

class MDBConflictStoreProbe(
    val entries: Int,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val sizeWidth: Int = 7)
    extends Bundle {
  val valid = Bool()
  val addrOnly = Bool()
  val isTile = Bool()
  val peId = UInt(peIdWidth.W)
  val stid = UInt(stidWidth.W)
  val tid = UInt(tidWidth.W)
  val bid = new ROBID(entries)
  val gid = new ROBID(entries)
  val rid = new ROBID(entries)
  val lsId = new ROBID(entries)
  val pc = UInt(pcWidth.W)
  val addr = UInt(addrWidth.W)
  val size = UInt(sizeWidth.W)
}

class MDBConflictLoadEntry(
    val entries: Int,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val sizeWidth: Int = 7)
    extends Bundle {
  val valid = Bool()
  val resolved = Bool()
  val isTile = Bool()
  val peId = UInt(peIdWidth.W)
  val stid = UInt(stidWidth.W)
  val tid = UInt(tidWidth.W)
  val bid = new ROBID(entries)
  val gid = new ROBID(entries)
  val rid = new ROBID(entries)
  val lsId = new ROBID(entries)
  val pc = UInt(pcWidth.W)
  val addr = UInt(addrWidth.W)
  val size = UInt(sizeWidth.W)
}

class MDBConflictRecord(
    val entries: Int,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val sizeWidth: Int = 7)
    extends Bundle {
  val load = new MDBConflictLoadEntry(entries, addrWidth, pcWidth, peIdWidth, stidWidth, tidWidth, sizeWidth)
  val store = new MDBConflictStoreProbe(entries, addrWidth, pcWidth, peIdWidth, stidWidth, tidWidth, sizeWidth)
}

class MDBConflictDetectIO(
    val entries: Int,
    val loadEntries: Int,
    val resolveEntries: Int,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val sizeWidth: Int = 7)
    extends Bundle {
  private val loadIndexWidth = log2Ceil(loadEntries.max(2))
  private val resolveIndexWidth = log2Ceil(resolveEntries.max(2))
  private val totalEntries = loadEntries + resolveEntries

  val store = Input(new MDBConflictStoreProbe(entries, addrWidth, pcWidth, peIdWidth, stidWidth, tidWidth, sizeWidth))
  val activeLoads = Input(Vec(loadEntries, new MDBConflictLoadEntry(entries, addrWidth, pcWidth, peIdWidth, stidWidth, tidWidth, sizeWidth)))
  val resolvedQueue = Input(Vec(resolveEntries, new MDBConflictLoadEntry(entries, addrWidth, pcWidth, peIdWidth, stidWidth, tidWidth, sizeWidth)))

  val activeCandidateMask = Output(UInt(loadEntries.W))
  val resolveCandidateMask = Output(UInt(resolveEntries.W))
  val tileSuppressedActiveMask = Output(UInt(loadEntries.W))
  val tileSuppressedResolveMask = Output(UInt(resolveEntries.W))
  val waitStoreMask = Output(UInt(loadEntries.W))
  val waitStoreCount = Output(UInt(log2Ceil(loadEntries + 1).W))

  val conflictValid = Output(Bool())
  val conflictFromResolveQueue = Output(Bool())
  val conflictActiveIndex = Output(UInt(loadIndexWidth.W))
  val conflictResolveIndex = Output(UInt(resolveIndexWidth.W))
  val conflictOrdinal = Output(UInt(log2Ceil(totalEntries.max(1)).W))
  val record = Output(new MDBConflictRecord(entries, addrWidth, pcWidth, peIdWidth, stidWidth, tidWidth, sizeWidth))

  val innerFlush = Output(Bool())
  val nukeFlush = Output(Bool())
}

class MDBConflictDetect(
    val entries: Int = 16,
    val loadEntries: Int = 16,
    val resolveEntries: Int = 8,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val sizeWidth: Int = 7)
    extends Module {
  require(entries > 1, "ROB entries must be greater than one")
  require((entries & (entries - 1)) == 0, "ROB entries must be a power of two")
  require(loadEntries > 0, "loadEntries must be nonzero")
  require(resolveEntries > 0, "resolveEntries must be nonzero")
  require(addrWidth > 0, "address width must be nonzero")
  require(sizeWidth > 0, "size width must be nonzero")

  private val totalEntries = loadEntries + resolveEntries
  private val loadIndexWidth = log2Ceil(loadEntries.max(2))
  private val resolveIndexWidth = log2Ceil(resolveEntries.max(2))

  val io = IO(new MDBConflictDetectIO(
    entries,
    loadEntries,
    resolveEntries,
    addrWidth,
    pcWidth,
    peIdWidth,
    stidWidth,
    tidWidth,
    sizeWidth
  ))

  private def zeroLoad: MDBConflictLoadEntry = {
    val out = Wire(new MDBConflictLoadEntry(entries, addrWidth, pcWidth, peIdWidth, stidWidth, tidWidth, sizeWidth))
    out := 0.U.asTypeOf(out)
    out
  }

  private def sameThread(store: MDBConflictStoreProbe, load: MDBConflictLoadEntry): Bool =
    store.stid === load.stid

  private def orderedBeforeOrSame(store: MDBConflictStoreProbe, load: MDBConflictLoadEntry): Bool =
    STQCommitQueue.lessEqualBidLs(store.bid, store.lsId, load.bid, load.lsId)

  private def addressOverlap(store: MDBConflictStoreProbe, load: MDBConflictLoadEntry): Bool = {
    val storeSize = store.size.asUInt
    val loadSize = load.size.asUInt
    val storeEnd = store.addr +& storeSize
    val loadEnd = load.addr +& loadSize
    (storeSize =/= 0.U) && (loadSize =/= 0.U) &&
      (store.addr < loadEnd) && (load.addr < storeEnd)
  }

  private def orderedOverlap(store: MDBConflictStoreProbe, load: MDBConflictLoadEntry): Bool =
    store.valid && load.valid && sameThread(store, load) && addressOverlap(store, load) && orderedBeforeOrSame(store, load)

  private def scalarConflict(store: MDBConflictStoreProbe, load: MDBConflictLoadEntry): Bool =
    orderedOverlap(store, load) && !store.isTile && !load.isTile

  private def tileSuppressed(store: MDBConflictStoreProbe, load: MDBConflictLoadEntry): Bool =
    orderedOverlap(store, load) && (store.isTile || load.isTile)

  val activeConflictVec = Wire(Vec(loadEntries, Bool()))
  val activeResolvedConflictVec = Wire(Vec(loadEntries, Bool()))
  val activeTileSuppressedVec = Wire(Vec(loadEntries, Bool()))
  val waitStoreVec = Wire(Vec(loadEntries, Bool()))

  for (idx <- 0 until loadEntries) {
    activeConflictVec(idx) := scalarConflict(io.store, io.activeLoads(idx))
    activeResolvedConflictVec(idx) := activeConflictVec(idx) && io.activeLoads(idx).resolved
    activeTileSuppressedVec(idx) := tileSuppressed(io.store, io.activeLoads(idx))
    waitStoreVec(idx) := activeConflictVec(idx) && !io.activeLoads(idx).resolved && io.store.addrOnly
  }

  val resolveConflictVec = Wire(Vec(resolveEntries, Bool()))
  val resolveTileSuppressedVec = Wire(Vec(resolveEntries, Bool()))
  for (idx <- 0 until resolveEntries) {
    resolveConflictVec(idx) := scalarConflict(io.store, io.resolvedQueue(idx))
    resolveTileSuppressedVec(idx) := tileSuppressed(io.store, io.resolvedQueue(idx))
  }

  val candidateLoads = Wire(Vec(totalEntries, new MDBConflictLoadEntry(entries, addrWidth, pcWidth, peIdWidth, stidWidth, tidWidth, sizeWidth)))
  val candidateValid = Wire(Vec(totalEntries, Bool()))
  for (idx <- 0 until loadEntries) {
    candidateLoads(idx) := io.activeLoads(idx)
    candidateValid(idx) := activeResolvedConflictVec(idx)
  }
  for (idx <- 0 until resolveEntries) {
    candidateLoads(loadEntries + idx) := io.resolvedQueue(idx)
    candidateValid(loadEntries + idx) := resolveConflictVec(idx)
  }

  var selectedValid: Bool = false.B
  var selectedOrdinal: UInt = 0.U(log2Ceil(totalEntries.max(2)).W)
  var selectedLoad: MDBConflictLoadEntry = zeroLoad
  for (idx <- 0 until totalEntries) {
    val candidateOlder =
      !selectedValid || STQCommitQueue.lessEqualBidLs(candidateLoads(idx).bid, candidateLoads(idx).lsId, selectedLoad.bid, selectedLoad.lsId)
    val takeCandidate = candidateValid(idx) && candidateOlder
    selectedLoad = Mux(takeCandidate, candidateLoads(idx), selectedLoad)
    selectedOrdinal = Mux(takeCandidate, idx.U, selectedOrdinal)
    selectedValid = selectedValid || takeCandidate
  }

  val fromResolveQueue = selectedValid && (selectedOrdinal >= loadEntries.U)
  val activeIndex = Wire(UInt(loadIndexWidth.W))
  activeIndex := 0.U
  when(selectedValid && !fromResolveQueue) {
    activeIndex := selectedOrdinal(loadIndexWidth - 1, 0)
  }

  val resolveIndex = Wire(UInt(resolveIndexWidth.W))
  resolveIndex := 0.U
  when(fromResolveQueue) {
    resolveIndex := (selectedOrdinal - loadEntries.U)(resolveIndexWidth - 1, 0)
  }

  io.activeCandidateMask := activeResolvedConflictVec.asUInt
  io.resolveCandidateMask := resolveConflictVec.asUInt
  io.tileSuppressedActiveMask := activeTileSuppressedVec.asUInt
  io.tileSuppressedResolveMask := resolveTileSuppressedVec.asUInt
  io.waitStoreMask := waitStoreVec.asUInt
  io.waitStoreCount := PopCount(waitStoreVec)

  io.conflictValid := selectedValid
  io.conflictFromResolveQueue := fromResolveQueue
  io.conflictActiveIndex := activeIndex
  io.conflictResolveIndex := resolveIndex
  io.conflictOrdinal := selectedOrdinal
  io.record.load := selectedLoad
  io.record.store := io.store

  io.innerFlush := selectedValid && ROBID.equal(selectedLoad.bid, io.store.bid)
  io.nukeFlush := selectedValid && !ROBID.equal(selectedLoad.bid, io.store.bid)
}
