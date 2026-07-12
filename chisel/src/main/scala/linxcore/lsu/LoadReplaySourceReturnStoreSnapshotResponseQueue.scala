package linxcore.lsu

import chisel3._
import chisel3.util.{PopCount, log2Ceil}

import linxcore.recovery.FlushBus
import linxcore.rob.ROBID

class LoadReplaySourceReturnStoreSnapshotResponseQueueEntry(
    idEntries: Int,
    clusterIdWidth: Int,
    entryIdWidth: Int,
    pcWidth: Int,
    lineBytes: Int,
    peIdWidth: Int,
    stidWidth: Int,
    tidWidth: Int,
    storeEntries: Int = 0,
    lsidWidth: Int = 32)
    extends Bundle {
  private val physicalStoreEntries = if (storeEntries > 0) storeEntries else idEntries
  val clusterId = UInt(clusterIdWidth.W)
  val entryId = UInt(entryIdWidth.W)
  val requestBid = new ROBID(idEntries)
  val requestGid = new ROBID(idEntries)
  val requestRid = new ROBID(idEntries)
  val requestLoadLsId = new ROBID(idEntries)
  val requestLoadLsIdFullValid = Bool()
  val requestLoadLsIdFull = UInt(lsidWidth.W)
  val requestPeId = UInt(peIdWidth.W)
  val requestStid = UInt(stidWidth.W)
  val requestTid = UInt(tidWidth.W)
  val waitStore = Bool()
  val dataValid = Bool()
  val rawDataValid = Bool()
  val dataSuppressedByWait = Bool()
  val waitStoreIndex = UInt(log2Ceil(physicalStoreEntries).W)
  val waitStoreBid = new ROBID(idEntries)
  val waitStoreRid = new ROBID(idEntries)
  val waitStoreLsId = new ROBID(idEntries)
  val waitStoreLsIdFullValid = Bool()
  val waitStoreLsIdFull = UInt(lsidWidth.W)
  val waitStorePc = UInt(pcWidth.W)
  val dataMask = UInt(lineBytes.W)
  val data = UInt((lineBytes * 8).W)
}

class LoadReplaySourceReturnStoreSnapshotResponseQueueIO(
    idEntries: Int,
    clusterIdWidth: Int,
    entryIdWidth: Int,
    depth: Int,
    pcWidth: Int,
    lineBytes: Int,
    peIdWidth: Int,
    stidWidth: Int,
    tidWidth: Int,
    storeEntries: Int = 0,
    lsidWidth: Int = 32)
    extends Bundle {
  private val physicalStoreEntries = if (storeEntries > 0) storeEntries else idEntries
  private val countWidth = log2Ceil(depth + 1)

  val enable = Input(Bool())
  val flush = Input(Bool())
  val preciseFlush = Input(new FlushBus(idEntries, peIdWidth, stidWidth, tidWidth, lsidWidth))
  val enqueueValid = Input(Bool())
  val enqueue = Input(new LoadReplaySourceReturnStoreSnapshotResponsePayloadBundle(
    idEntries,
    clusterIdWidth,
    entryIdWidth,
    pcWidth,
    lineBytes,
    peIdWidth,
    stidWidth,
    tidWidth,
    physicalStoreEntries,
    lsidWidth
  ))
  val dequeueReady = Input(Bool())

  val enqueueReady = Output(Bool())
  val enqueueAccepted = Output(Bool())
  val enqueueDropped = Output(Bool())
  val headValid = Output(Bool())
  val head = Output(new LoadReplaySourceReturnStoreSnapshotResponsePayloadBundle(
    idEntries,
    clusterIdWidth,
    entryIdWidth,
    pcWidth,
    lineBytes,
    peIdWidth,
    stidWidth,
    tidWidth,
    physicalStoreEntries,
    lsidWidth
  ))
  val headClusterId = Output(UInt(clusterIdWidth.W))
  val headEntryId = Output(UInt(entryIdWidth.W))
  val headRequestBid = Output(new ROBID(idEntries))
  val headRequestGid = Output(new ROBID(idEntries))
  val headRequestRid = Output(new ROBID(idEntries))
  val headRequestLoadLsId = Output(new ROBID(idEntries))
  val headRequestLoadLsIdFullValid = Output(Bool())
  val headRequestLoadLsIdFull = Output(UInt(lsidWidth.W))
  val headRequestPeId = Output(UInt(peIdWidth.W))
  val headRequestStid = Output(UInt(stidWidth.W))
  val headRequestTid = Output(UInt(tidWidth.W))
  val headWaitStore = Output(Bool())
  val headDataValid = Output(Bool())
  val headRawDataValid = Output(Bool())
  val headDataSuppressedByWait = Output(Bool())
  val headWaitStoreIndex = Output(UInt(log2Ceil(physicalStoreEntries).W))
  val headWaitStoreBid = Output(new ROBID(idEntries))
  val headWaitStoreRid = Output(new ROBID(idEntries))
  val headWaitStoreLsId = Output(new ROBID(idEntries))
  val headWaitStoreLsIdFullValid = Output(Bool())
  val headWaitStoreLsIdFull = Output(UInt(lsidWidth.W))
  val headWaitStorePc = Output(UInt(pcWidth.W))
  val headDataMask = Output(UInt(lineBytes.W))
  val headData = Output(UInt((lineBytes * 8).W))
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

class LoadReplaySourceReturnStoreSnapshotResponseQueue(
    val idEntries: Int = 16,
    val clusterIdWidth: Int = 2,
    val entryIdWidth: Int = 4,
    val depth: Int = 2,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val storeEntries: Int = 0,
    val lsidWidth: Int = 32)
    extends Module {
  private val physicalStoreEntries = if (storeEntries > 0) storeEntries else idEntries
  require(idEntries > 1, "idEntries must be greater than one")
  require((idEntries & (idEntries - 1)) == 0, "idEntries must be a power of two")
  require(physicalStoreEntries > 1 && (physicalStoreEntries & (physicalStoreEntries - 1)) == 0,
    "store entries must be a power of two greater than one")
  require(clusterIdWidth > 0, "clusterIdWidth must be positive")
  require(entryIdWidth > 0, "entryIdWidth must be positive")
  require(depth > 0, "response queue depth must be nonzero")
  require(pcWidth > 0, "pcWidth must be positive")
  require(lineBytes == 64, "response queue currently carries 64-byte scalar line data")
  require(peIdWidth > 0, "peIdWidth must be positive")
  require(stidWidth > 0, "stidWidth must be positive")
  require(tidWidth > 0, "tidWidth must be positive")
  require(lsidWidth >= 2, "LSID width must support modular serial ordering")

  private val countWidth = log2Ceil(depth + 1)

  val io = IO(new LoadReplaySourceReturnStoreSnapshotResponseQueueIO(
    idEntries = idEntries,
    clusterIdWidth = clusterIdWidth,
    entryIdWidth = entryIdWidth,
    depth = depth,
    pcWidth = pcWidth,
    lineBytes = lineBytes,
    peIdWidth = peIdWidth,
    stidWidth = stidWidth,
    tidWidth = tidWidth,
    storeEntries = physicalStoreEntries,
    lsidWidth = lsidWidth
  ))

  private def zeroEntry: LoadReplaySourceReturnStoreSnapshotResponseQueueEntry = {
    val entry = Wire(new LoadReplaySourceReturnStoreSnapshotResponseQueueEntry(
      idEntries = idEntries,
      clusterIdWidth = clusterIdWidth,
      entryIdWidth = entryIdWidth,
      pcWidth = pcWidth,
      lineBytes = lineBytes,
      peIdWidth = peIdWidth,
      stidWidth = stidWidth,
      tidWidth = tidWidth,
      storeEntries = physicalStoreEntries,
      lsidWidth = lsidWidth
    ))
    entry := 0.U.asTypeOf(entry)
    entry
  }

  private def enqueueEntry: LoadReplaySourceReturnStoreSnapshotResponseQueueEntry = {
    val entry = Wire(new LoadReplaySourceReturnStoreSnapshotResponseQueueEntry(
      idEntries = idEntries,
      clusterIdWidth = clusterIdWidth,
      entryIdWidth = entryIdWidth,
      pcWidth = pcWidth,
      lineBytes = lineBytes,
      peIdWidth = peIdWidth,
      stidWidth = stidWidth,
      tidWidth = tidWidth,
      storeEntries = physicalStoreEntries,
      lsidWidth = lsidWidth
    ))
    entry.clusterId := io.enqueue.clusterId
    entry.entryId := io.enqueue.entryId
    entry.requestBid := io.enqueue.requestBid
    entry.requestGid := io.enqueue.requestGid
    entry.requestRid := io.enqueue.requestRid
    entry.requestLoadLsId := io.enqueue.requestLoadLsId
    entry.requestLoadLsIdFullValid := io.enqueue.requestLoadLsIdFullValid
    entry.requestLoadLsIdFull := io.enqueue.requestLoadLsIdFull
    entry.requestPeId := io.enqueue.requestPeId
    entry.requestStid := io.enqueue.requestStid
    entry.requestTid := io.enqueue.requestTid
    entry.waitStore := io.enqueue.waitStore
    entry.dataValid := io.enqueue.dataValid
    entry.rawDataValid := io.enqueue.rawDataValid
    entry.dataSuppressedByWait := io.enqueue.dataSuppressedByWait
    entry.waitStoreIndex := io.enqueue.waitStoreIndex
    entry.waitStoreBid := io.enqueue.waitStoreBid
    entry.waitStoreRid := io.enqueue.waitStoreRid
    entry.waitStoreLsId := io.enqueue.waitStoreLsId
    entry.waitStoreLsIdFullValid := io.enqueue.waitStoreLsIdFullValid
    entry.waitStoreLsIdFull := io.enqueue.waitStoreLsIdFull
    entry.waitStorePc := io.enqueue.waitStorePc
    entry.dataMask := io.enqueue.dataMask
    entry.data := io.enqueue.data
    entry
  }

  private def toPruneEntry(entry: LoadReplaySourceReturnStoreSnapshotResponseQueueEntry): STQFlushPruneEntry = {
    val row = Wire(new STQFlushPruneEntry(idEntries, peIdWidth, stidWidth, tidWidth, lsidWidth))
    row.valid := true.B
    row.status := STQEntryStatus.Wait
    row.peId := entry.requestPeId
    row.stid := entry.requestStid
    row.tid := entry.requestTid
    row.bid := entry.requestBid
    row.gid := entry.requestGid
    row.lsId := entry.requestLoadLsId
    row.lsIdFullValid := entry.requestLoadLsIdFullValid
    row.lsIdFull := entry.requestLoadLsIdFull
    row
  }

  val entries = RegInit(VecInit(Seq.fill(depth)(zeroEntry)))
  val count = RegInit(0.U(countWidth.W))

  val baseActive = io.enable && !io.flush
  val precisePruneActive = baseActive && io.preciseFlush.req.valid
  val active = baseActive && !precisePruneActive
  val residentHeadValid = count =/= 0.U
  val popResident = active && residentHeadValid && io.dequeueReady
  val hasOpenSlot = count =/= depth.U
  val enqueueReady = active && ((count =/= depth.U) || popResident)
  val enqueueAccepted = io.enqueueValid && enqueueReady
  val bypassHead = active && !residentHeadValid && io.enqueueValid && hasOpenSlot
  val headValid = active && (residentHeadValid || bypassHead)
  val headEntry = Mux(residentHeadValid, entries(0), enqueueEntry)
  val headPayload = WireDefault(0.U.asTypeOf(new LoadReplaySourceReturnStoreSnapshotResponsePayloadBundle(
    idEntries,
    clusterIdWidth,
    entryIdWidth,
    pcWidth,
    lineBytes,
    peIdWidth,
    stidWidth,
    tidWidth,
    physicalStoreEntries,
    lsidWidth
  )))
  when(headValid) {
    headPayload.valid := true.B
    headPayload.clusterId := headEntry.clusterId
    headPayload.entryId := headEntry.entryId
    headPayload.requestBid := headEntry.requestBid
    headPayload.requestGid := headEntry.requestGid
    headPayload.requestRid := headEntry.requestRid
    headPayload.requestLoadLsId := headEntry.requestLoadLsId
    headPayload.requestLoadLsIdFullValid := headEntry.requestLoadLsIdFullValid
    headPayload.requestLoadLsIdFull := headEntry.requestLoadLsIdFull
    headPayload.requestPeId := headEntry.requestPeId
    headPayload.requestStid := headEntry.requestStid
    headPayload.requestTid := headEntry.requestTid
    headPayload.waitStore := headEntry.waitStore
    headPayload.dataValid := headEntry.dataValid
    headPayload.rawDataValid := headEntry.rawDataValid
    headPayload.dataSuppressedByWait := headEntry.dataSuppressedByWait
    headPayload.waitStoreIndex := headEntry.waitStoreIndex
    headPayload.waitStoreBid := headEntry.waitStoreBid
    headPayload.waitStoreRid := headEntry.waitStoreRid
    headPayload.waitStoreLsId := headEntry.waitStoreLsId
    headPayload.waitStoreLsIdFullValid := headEntry.waitStoreLsIdFullValid
    headPayload.waitStoreLsIdFull := headEntry.waitStoreLsIdFull
    headPayload.waitStorePc := headEntry.waitStorePc
    headPayload.dataMask := headEntry.dataMask
    headPayload.data := headEntry.data
  }
  val headConsumed = headValid && io.dequeueReady
  val storeEnqueue = enqueueAccepted && !(bypassHead && io.dequeueReady)

  val precisePruneVec = Wire(Vec(depth, Bool()))
  val removeVec = Wire(Vec(depth, Bool()))
  val keptVec = Wire(Vec(depth, Bool()))
  val keptRank = Wire(Vec(depth, UInt(countWidth.W)))
  val compacted = Wire(Vec(depth, new LoadReplaySourceReturnStoreSnapshotResponseQueueEntry(
    idEntries = idEntries,
    clusterIdWidth = clusterIdWidth,
    entryIdWidth = entryIdWidth,
    pcWidth = pcWidth,
    lineBytes = lineBytes,
    peIdWidth = peIdWidth,
    stidWidth = stidWidth,
    tidWidth = tidWidth,
    storeEntries = physicalStoreEntries,
    lsidWidth = lsidWidth
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
    compacted(dst) := zeroEntry
    for (src <- 0 until depth) {
      when(keptVec(src) && (keptRank(src) === dst.U)) {
        compacted(dst) := entries(src)
      }
    }
  }

  val keptCount = PopCount(keptVec)
  val nextCount = keptCount + storeEnqueue.asUInt
  val nextEntries = Wire(Vec(depth, new LoadReplaySourceReturnStoreSnapshotResponseQueueEntry(
    idEntries = idEntries,
    clusterIdWidth = clusterIdWidth,
    entryIdWidth = entryIdWidth,
    pcWidth = pcWidth,
    lineBytes = lineBytes,
    peIdWidth = peIdWidth,
    stidWidth = stidWidth,
    tidWidth = tidWidth,
    storeEntries = physicalStoreEntries,
    lsidWidth = lsidWidth
  )))
  for (dst <- 0 until depth) {
    nextEntries(dst) := compacted(dst)
    when(storeEnqueue && (keptCount === dst.U)) {
      nextEntries(dst) := enqueueEntry
    }
  }

  when(io.flush) {
    for (idx <- 0 until depth) {
      entries(idx) := zeroEntry
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
  io.head := headPayload
  io.headClusterId := headPayload.clusterId
  io.headEntryId := headPayload.entryId
  io.headRequestBid := headPayload.requestBid
  io.headRequestGid := headPayload.requestGid
  io.headRequestRid := headPayload.requestRid
  io.headRequestLoadLsId := headPayload.requestLoadLsId
  io.headRequestLoadLsIdFullValid := headPayload.requestLoadLsIdFullValid
  io.headRequestLoadLsIdFull := headPayload.requestLoadLsIdFull
  io.headRequestPeId := headPayload.requestPeId
  io.headRequestStid := headPayload.requestStid
  io.headRequestTid := headPayload.requestTid
  io.headWaitStore := headPayload.waitStore
  io.headDataValid := headPayload.dataValid
  io.headRawDataValid := headPayload.rawDataValid
  io.headDataSuppressedByWait := headPayload.dataSuppressedByWait
  io.headWaitStoreIndex := headPayload.waitStoreIndex
  io.headWaitStoreBid := headPayload.waitStoreBid
  io.headWaitStoreRid := headPayload.waitStoreRid
  io.headWaitStoreLsId := headPayload.waitStoreLsId
  io.headWaitStoreLsIdFullValid := headPayload.waitStoreLsIdFullValid
  io.headWaitStoreLsIdFull := headPayload.waitStoreLsIdFull
  io.headWaitStorePc := headPayload.waitStorePc
  io.headDataMask := headPayload.dataMask
  io.headData := headPayload.data
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
