package linxcore.lsu

import chisel3._
import chisel3.util.log2Ceil

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
    val tidWidth: Int = 8)
    extends Bundle {
  private val countWidth = log2Ceil(depth + 1)

  val enable = Input(Bool())
  val flush = Input(Bool())
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
    tidWidth
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
    tidWidth
  ))
  val headConsumed = Output(Bool())
  val pending = Output(Bool())
  val full = Output(Bool())
  val empty = Output(Bool())
  val count = Output(UInt(countWidth.W))
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
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
    val tidWidth: Int = 8)
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

  private val ptrWidth = math.max(1, log2Ceil(depth))
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
    tidWidth
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
      tidWidth
    ))
    request := 0.U.asTypeOf(request)
    request
  }

  private def inc(ptr: UInt): UInt =
    if (depth == 1) 0.U(ptrWidth.W) else Mux(ptr === (depth - 1).U, 0.U, ptr + 1.U)

  val entries = RegInit(VecInit(Seq.fill(depth)(zeroRequest)))
  val headPtr = RegInit(0.U(ptrWidth.W))
  val tailPtr = RegInit(0.U(ptrWidth.W))
  val count = RegInit(0.U(countWidth.W))

  val active = io.enable && !io.flush
  val residentHeadValid = count =/= 0.U
  val popResident = active && residentHeadValid && io.dequeueReady
  val hasOpenSlot = count =/= depth.U
  val enqueueReady = active && (hasOpenSlot || popResident)
  val enqueueAccepted = io.enqueueValid && enqueueReady
  val bypassHead = active && !residentHeadValid && io.enqueueValid && hasOpenSlot
  val headValid = active && (residentHeadValid || bypassHead)
  val headRequest = Mux(residentHeadValid, entries(headPtr), io.enqueueRequest)
  val headConsumed = headValid && io.dequeueReady
  val storeEnqueue = enqueueAccepted && !(bypassHead && io.dequeueReady)

  when(io.flush) {
    for (idx <- 0 until depth) {
      entries(idx) := zeroRequest
    }
    headPtr := 0.U
    tailPtr := 0.U
    count := 0.U
  }.elsewhen(active) {
    when(popResident) {
      headPtr := inc(headPtr)
    }
    when(storeEnqueue) {
      entries(tailPtr) := io.enqueueRequest
      tailPtr := inc(tailPtr)
    }

    when(storeEnqueue && !popResident) {
      count := count + 1.U
    }.elsewhen(!storeEnqueue && popResident) {
      count := count - 1.U
    }
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
  io.blockedByDisabled := !io.enable && io.enqueueValid
  io.blockedByFlush := io.enable && io.flush && io.enqueueValid
  io.blockedByFull := io.enable && !io.flush && io.enqueueValid && !enqueueReady
}
