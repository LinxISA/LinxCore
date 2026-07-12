package linxcore.lsu

import chisel3._
import chisel3.util.{PopCount, log2Ceil}

import linxcore.recovery.FlushBus

class LoadReplaySourceReturnStoreSnapshotRequestQueueIO(
    val liqEntries: Int,
    val idEntries: Int,
    val clusterIdWidth: Int,
    val entryIdWidth: Int,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val sizeWidth: Int = 7,
    val depth: Int,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val lsidWidth: Int = 32)
    extends Bundle {
  private val countWidth = log2Ceil(depth + 1)

  val enable = Input(Bool())
  val flush = Input(Bool())
  val preciseFlush = Input(new FlushBus(idEntries, peIdWidth, stidWidth, tidWidth, lsidWidth))
  val enqueueValid = Input(Bool())
  val enqueueRequest = Input(new LoadReplaySourceReturnStoreSnapshotRequestPayloadBundle(
    liqEntries,
    idEntries,
    clusterIdWidth,
    entryIdWidth,
    addrWidth,
    pcWidth,
    lineBytes,
    sizeWidth,
    peIdWidth,
    stidWidth,
    tidWidth,
    lsidWidth
  ))
  val dequeueReady = Input(Bool())

  val enqueueReady = Output(Bool())
  val enqueueAccepted = Output(Bool())
  val enqueueDropped = Output(Bool())
  val headValid = Output(Bool())
  val head = Output(new LoadReplaySourceReturnStoreSnapshotRequestPayloadBundle(
    liqEntries,
    idEntries,
    clusterIdWidth,
    entryIdWidth,
    addrWidth,
    pcWidth,
    lineBytes,
    sizeWidth,
    peIdWidth,
    stidWidth,
    tidWidth,
    lsidWidth
  ))
  val headConsumed = Output(Bool())
  val pending = Output(Bool())
  val full = Output(Bool())
  val empty = Output(Bool())
  val count = Output(UInt(countWidth.W))
  val precisePruneMask = Output(UInt(depth.W))
  val precisePruneCount = Output(UInt(countWidth.W))
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByPreciseFlush = Output(Bool())
  val blockedByFull = Output(Bool())
}

class LoadReplaySourceReturnStoreSnapshotRequestQueue(
    val liqEntries: Int = 4,
    val idEntries: Int = 16,
    val clusterIdWidth: Int = 2,
    val entryIdWidth: Int = 4,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val sizeWidth: Int = 7,
    val depth: Int = 2,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val lsidWidth: Int = 32)
    extends Module {
  require(liqEntries > 1, "LIQ entries must be greater than one")
  require((liqEntries & (liqEntries - 1)) == 0, "LIQ entries must be a power of two")
  require(idEntries > 1, "ID entries must be greater than one")
  require((idEntries & (idEntries - 1)) == 0, "ID entries must be a power of two")
  require(clusterIdWidth > 0, "clusterIdWidth must be positive")
  require(entryIdWidth > 0, "entryIdWidth must be positive")
  require(addrWidth >= 7, "request queue needs 64-byte line addresses")
  require(lineBytes == 64, "request queue currently models 64-byte scalar cachelines")
  require(sizeWidth >= 7, "sizeWidth must cover 64-byte scalar lines")
  require(depth > 0, "request queue depth must be nonzero")
  require(peIdWidth > 0, "peIdWidth must be positive")
  require(stidWidth > 0, "stidWidth must be positive")
  require(tidWidth > 0, "tidWidth must be positive")
  require(lsidWidth >= 2, "LSID width must support modular serial ordering")

  private val countWidth = log2Ceil(depth + 1)

  val io = IO(new LoadReplaySourceReturnStoreSnapshotRequestQueueIO(
    liqEntries,
    idEntries,
    clusterIdWidth,
    entryIdWidth,
    addrWidth,
    pcWidth,
    lineBytes,
    sizeWidth,
    depth,
    peIdWidth,
    stidWidth,
    tidWidth,
    lsidWidth
  ))

  private def zeroRequest: LoadReplaySourceReturnStoreSnapshotRequestPayloadBundle = {
    val request = Wire(new LoadReplaySourceReturnStoreSnapshotRequestPayloadBundle(
      liqEntries,
      idEntries,
      clusterIdWidth,
      entryIdWidth,
      addrWidth,
      pcWidth,
      lineBytes,
      sizeWidth,
      peIdWidth,
      stidWidth,
      tidWidth,
      lsidWidth
    ))
    request := 0.U.asTypeOf(request)
    request
  }

  private def toPruneEntry(request: LoadReplaySourceReturnStoreSnapshotRequestPayloadBundle): STQFlushPruneEntry = {
    val entry = Wire(new STQFlushPruneEntry(idEntries, peIdWidth, stidWidth, tidWidth, lsidWidth))
    entry.valid := request.valid
    entry.status := STQEntryStatus.Wait
    entry.peId := request.peId
    entry.stid := request.stid
    entry.tid := request.tid
    entry.bid := request.bid
    entry.gid := request.gid
    entry.lsId := request.loadLsId
    entry.lsIdFullValid := request.loadLsIdFullValid
    entry.lsIdFull := request.loadLsIdFull
    entry
  }

  val entries = RegInit(VecInit(Seq.fill(depth)(zeroRequest)))
  val count = RegInit(0.U(countWidth.W))

  val baseActive = io.enable && !io.flush
  val precisePruneActive = baseActive && io.preciseFlush.req.valid
  val active = baseActive && !precisePruneActive
  val residentHeadValid = count =/= 0.U
  val popResident = active && residentHeadValid && io.dequeueReady
  val hasOpenSlot = count =/= depth.U
  val enqueueReady = active && (hasOpenSlot || popResident)
  val enqueueAccepted = io.enqueueValid && enqueueReady
  val bypassHead = active && !residentHeadValid && io.enqueueValid && hasOpenSlot
  val headValid = active && (residentHeadValid || bypassHead)
  val headRequest = Mux(residentHeadValid, entries(0), io.enqueueRequest)
  val headConsumed = headValid && io.dequeueReady
  val storeEnqueue = enqueueAccepted && !(bypassHead && io.dequeueReady)

  val precisePruneVec = Wire(Vec(depth, Bool()))
  val removeVec = Wire(Vec(depth, Bool()))
  val keptVec = Wire(Vec(depth, Bool()))
  val keptRank = Wire(Vec(depth, UInt(countWidth.W)))
  val compacted = Wire(Vec(depth, new LoadReplaySourceReturnStoreSnapshotRequestPayloadBundle(
    liqEntries,
    idEntries,
    clusterIdWidth,
    entryIdWidth,
    addrWidth,
    pcWidth,
    lineBytes,
    sizeWidth,
    peIdWidth,
    stidWidth,
    tidWidth,
    lsidWidth
  )))

  for (slot <- 0 until depth) {
    val resident = slot.U < count
    precisePruneVec(slot) := resident && precisePruneActive &&
      STQFlushPrune.matchesFlush(io.preciseFlush, toPruneEntry(entries(slot)))
    removeVec(slot) := precisePruneVec(slot) || (popResident && slot.U === 0.U)
    keptVec(slot) := resident && !removeVec(slot)
    if (slot == 0) {
      keptRank(slot) := 0.U
    } else {
      keptRank(slot) := PopCount((0 until slot).map(keptVec(_)))
    }
  }

  for (dst <- 0 until depth) {
    compacted(dst) := zeroRequest
    for (src <- 0 until depth) {
      when(keptVec(src) && (keptRank(src) === dst.U)) {
        compacted(dst) := entries(src)
      }
    }
  }

  val keptCount = PopCount(keptVec)
  val nextCount = keptCount + storeEnqueue.asUInt
  val nextEntries = Wire(Vec(depth, new LoadReplaySourceReturnStoreSnapshotRequestPayloadBundle(
    liqEntries,
    idEntries,
    clusterIdWidth,
    entryIdWidth,
    addrWidth,
    pcWidth,
    lineBytes,
    sizeWidth,
    peIdWidth,
    stidWidth,
    tidWidth,
    lsidWidth
  )))
  for (dst <- 0 until depth) {
    nextEntries(dst) := compacted(dst)
    when(storeEnqueue && (keptCount === dst.U)) {
      nextEntries(dst) := io.enqueueRequest
    }
  }

  when(io.flush) {
    for (idx <- 0 until depth) {
      entries(idx) := zeroRequest
    }
    count := 0.U
  }.elsewhen(baseActive) {
    entries := nextEntries
    count := nextCount
  }

  io.enqueueReady := enqueueReady
  io.enqueueAccepted := enqueueAccepted
  io.enqueueDropped := io.enqueueValid && !io.flush && !enqueueReady
  io.headValid := headValid
  io.head := Mux(headValid, headRequest, zeroRequest)
  io.headConsumed := headConsumed
  io.pending := count =/= 0.U
  io.full := count === depth.U
  io.empty := count === 0.U
  io.count := count
  io.precisePruneMask := precisePruneVec.asUInt
  io.precisePruneCount := PopCount(precisePruneVec)
  io.blockedByDisabled := !io.enable && io.enqueueValid
  io.blockedByFlush := io.enable && io.flush && io.enqueueValid
  io.blockedByPreciseFlush := precisePruneActive && io.enqueueValid
  io.blockedByFull := io.enable && !io.flush && !precisePruneActive && io.enqueueValid && !enqueueReady
}
