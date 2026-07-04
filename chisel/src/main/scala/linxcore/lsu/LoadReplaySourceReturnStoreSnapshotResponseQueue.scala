package linxcore.lsu

import chisel3._
import chisel3.util.log2Ceil

class LoadReplaySourceReturnStoreSnapshotResponseQueueEntry(
    clusterIdWidth: Int,
    entryIdWidth: Int)
    extends Bundle {
  val clusterId = UInt(clusterIdWidth.W)
  val entryId = UInt(entryIdWidth.W)
  val waitStore = Bool()
  val dataValid = Bool()
}

class LoadReplaySourceReturnStoreSnapshotResponseQueueIO(
    clusterIdWidth: Int,
    entryIdWidth: Int,
    depth: Int)
    extends Bundle {
  private val countWidth = log2Ceil(depth + 1)

  val enable = Input(Bool())
  val flush = Input(Bool())
  val enqueueValid = Input(Bool())
  val enqueueClusterId = Input(UInt(clusterIdWidth.W))
  val enqueueEntryId = Input(UInt(entryIdWidth.W))
  val enqueueWaitStore = Input(Bool())
  val enqueueDataValid = Input(Bool())
  val dequeueReady = Input(Bool())

  val enqueueReady = Output(Bool())
  val enqueueAccepted = Output(Bool())
  val enqueueDropped = Output(Bool())
  val headValid = Output(Bool())
  val headClusterId = Output(UInt(clusterIdWidth.W))
  val headEntryId = Output(UInt(entryIdWidth.W))
  val headWaitStore = Output(Bool())
  val headDataValid = Output(Bool())
  val headConsumed = Output(Bool())
  val pending = Output(Bool())
  val full = Output(Bool())
  val empty = Output(Bool())
  val count = Output(UInt(countWidth.W))
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByFull = Output(Bool())
}

class LoadReplaySourceReturnStoreSnapshotResponseQueue(
    val clusterIdWidth: Int = 2,
    val entryIdWidth: Int = 4,
    val depth: Int = 2)
    extends Module {
  require(clusterIdWidth > 0, "clusterIdWidth must be positive")
  require(entryIdWidth > 0, "entryIdWidth must be positive")
  require(depth > 0, "response queue depth must be nonzero")

  private val ptrWidth = math.max(1, log2Ceil(depth))
  private val countWidth = log2Ceil(depth + 1)

  val io = IO(new LoadReplaySourceReturnStoreSnapshotResponseQueueIO(
    clusterIdWidth = clusterIdWidth,
    entryIdWidth = entryIdWidth,
    depth = depth
  ))

  private def zeroEntry: LoadReplaySourceReturnStoreSnapshotResponseQueueEntry = {
    val entry = Wire(new LoadReplaySourceReturnStoreSnapshotResponseQueueEntry(
      clusterIdWidth = clusterIdWidth,
      entryIdWidth = entryIdWidth
    ))
    entry := 0.U.asTypeOf(entry)
    entry
  }

  private def enqueueEntry: LoadReplaySourceReturnStoreSnapshotResponseQueueEntry = {
    val entry = Wire(new LoadReplaySourceReturnStoreSnapshotResponseQueueEntry(
      clusterIdWidth = clusterIdWidth,
      entryIdWidth = entryIdWidth
    ))
    entry.clusterId := io.enqueueClusterId
    entry.entryId := io.enqueueEntryId
    entry.waitStore := io.enqueueWaitStore
    entry.dataValid := io.enqueueDataValid
    entry
  }

  private def inc(ptr: UInt): UInt =
    if (depth == 1) 0.U(ptrWidth.W) else Mux(ptr === (depth - 1).U, 0.U, ptr + 1.U)

  val entries = RegInit(VecInit(Seq.fill(depth)(zeroEntry)))
  val headPtr = RegInit(0.U(ptrWidth.W))
  val tailPtr = RegInit(0.U(ptrWidth.W))
  val count = RegInit(0.U(countWidth.W))

  val active = io.enable && !io.flush
  val residentHeadValid = count =/= 0.U
  val popResident = active && residentHeadValid && io.dequeueReady
  val hasOpenSlot = count =/= depth.U
  val enqueueReady = active && ((count =/= depth.U) || popResident)
  val enqueueAccepted = io.enqueueValid && enqueueReady
  val bypassHead = active && !residentHeadValid && io.enqueueValid && hasOpenSlot
  val headValid = active && (residentHeadValid || bypassHead)
  val headEntry = Mux(residentHeadValid, entries(headPtr), enqueueEntry)
  val headConsumed = headValid && io.dequeueReady
  val storeEnqueue = enqueueAccepted && !(bypassHead && io.dequeueReady)

  when(io.flush) {
    for (idx <- 0 until depth) {
      entries(idx) := zeroEntry
    }
    headPtr := 0.U
    tailPtr := 0.U
    count := 0.U
  }.elsewhen(active) {
    when(popResident) {
      headPtr := inc(headPtr)
    }
    when(storeEnqueue) {
      entries(tailPtr) := enqueueEntry
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
  io.headClusterId := Mux(headValid, headEntry.clusterId, 0.U)
  io.headEntryId := Mux(headValid, headEntry.entryId, 0.U)
  io.headWaitStore := headValid && headEntry.waitStore
  io.headDataValid := headValid && headEntry.dataValid
  io.headConsumed := headConsumed
  io.pending := count =/= 0.U
  io.full := count === depth.U
  io.empty := count === 0.U
  io.count := count
  io.blockedByDisabled := !io.enable && io.enqueueValid
  io.blockedByFlush := io.enable && io.flush && io.enqueueValid
  io.blockedByFull := io.enable && !io.flush && io.enqueueValid && !enqueueReady
}
