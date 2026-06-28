package linxcore.lsu

import chisel3._
import chisel3.util._

import linxcore.common.InterfaceParams
import linxcore.recovery.FlushBus
import linxcore.rename.StoreSplitIssuePayload

class StoreDispatchSTQPathIO(
    val p: InterfaceParams = InterfaceParams(),
    val queueDepth: Int = 4,
    val entries: Int = 16,
    val addrWidth: Int = 64,
    val dataWidth: Int = 64,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val sizeWidth: Int = 4,
    val simtLaneWidth: Int = 8)
    extends Bundle {
  private val queueCountWidth = log2Ceil(queueDepth + 1)
  private val stqCountWidth = log2Ceil(entries + 1)
  private val ptrWidth = log2Ceil(entries)

  val flush = Input(new FlushBus(entries, peIdWidth, stidWidth, tidWidth))

  val staIn = Input(new StoreSplitIssuePayload(p))
  val stdIn = Input(new StoreSplitIssuePayload(p))
  val unsplitIn = Input(new StoreSplitIssuePayload(p))
  val staExec = Input(new StoreDispatchExecResult(addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth, sizeWidth, simtLaneWidth))
  val stdExec = Input(new StoreDispatchExecResult(addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth, sizeWidth, simtLaneWidth))

  val markCommitValid = Input(Bool())
  val markCommitIndex = Input(UInt(ptrWidth.W))
  val commitFreeValid = Input(Bool())
  val commitFreeIndex = Input(UInt(ptrWidth.W))
  val commitFreeMaskValid = Input(Bool())
  val commitFreeMask = Input(UInt(entries.W))

  val staReady = Output(Bool())
  val stdReady = Output(Bool())
  val inputProtocolError = Output(Bool())
  val splitInput = Output(Bool())
  val unsplitInput = Output(Bool())
  val staEnqueueFire = Output(Bool())
  val stdEnqueueFire = Output(Bool())
  val staDequeueFire = Output(Bool())
  val stdDequeueFire = Output(Bool())
  val staQueueValid = Output(Bool())
  val stdQueueValid = Output(Bool())
  val staQueue = Output(new StoreSplitIssuePayload(p))
  val stdQueue = Output(new StoreSplitIssuePayload(p))
  val staQueueCount = Output(UInt(queueCountWidth.W))
  val stdQueueCount = Output(UInt(queueCountWidth.W))
  val staQueueFull = Output(Bool())
  val stdQueueFull = Output(Bool())

  val staInsertReady = Output(Bool())
  val stdInsertReady = Output(Bool())
  val staInsertCanMerge = Output(Bool())
  val stdInsertCanMerge = Output(Bool())
  val staInsertCanAllocate = Output(Bool())
  val stdInsertCanAllocate = Output(Bool())
  val staInsertConflict = Output(Bool())
  val stdInsertConflict = Output(Bool())
  val staInsertIndex = Output(UInt(ptrWidth.W))
  val stdInsertIndex = Output(UInt(ptrWidth.W))

  val staRequest = Output(new STQStoreRequest(entries, addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth, sizeWidth, simtLaneWidth))
  val stdRequest = Output(new STQStoreRequest(entries, addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth, sizeWidth, simtLaneWidth))
  val staCandidate = Output(Bool())
  val stdCandidate = Output(Bool())
  val selectedSta = Output(Bool())
  val selectedStd = Output(Bool())
  val blockedByStaExec = Output(Bool())
  val blockedByStdExec = Output(Bool())
  val blockedByStaInsert = Output(Bool())
  val blockedByStdInsert = Output(Bool())
  val stdBypassStaBlocked = Output(Bool())

  val insertValid = Output(Bool())
  val insert = Output(new STQStoreRequest(entries, addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth, sizeWidth, simtLaneWidth))
  val insertAccepted = Output(Bool())
  val insertAllocated = Output(Bool())
  val insertMerged = Output(Bool())
  val insertConflict = Output(Bool())
  val insertIndex = Output(UInt(ptrWidth.W))

  val markCommitAccepted = Output(Bool())
  val markCommitIgnored = Output(Bool())
  val commitFreeAccepted = Output(Bool())
  val commitFreeIgnored = Output(Bool())
  val commitFreeAcceptedMask = Output(UInt(entries.W))
  val commitFreeIgnoredMask = Output(UInt(entries.W))
  val commitFreeCount = Output(UInt(stqCountWidth.W))

  val stqFlushApplied = Output(Bool())
  val stqFlushMatchMask = Output(UInt(entries.W))
  val stqFlushFreeMask = Output(UInt(entries.W))
  val stqFlushStatusBlockedMask = Output(UInt(entries.W))
  val stqFlushFreeCount = Output(UInt(stqCountWidth.W))
  val stqRows = Output(Vec(entries, new STQEntryBankRow(entries, addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth, sizeWidth, simtLaneWidth)))
  val stqOccupiedMask = Output(UInt(entries.W))
  val stqWaitMask = Output(UInt(entries.W))
  val stqCommitMask = Output(UInt(entries.W))
  val stqResidentCount = Output(UInt(stqCountWidth.W))
  val stqOutstandingWaitCount = Output(UInt(stqCountWidth.W))
  val stqEmpty = Output(Bool())
  val stqFull = Output(Bool())
  val stqStall = Output(Bool())
}

class StoreDispatchSTQPath(
    val p: InterfaceParams = InterfaceParams(),
    val queueDepth: Int = 4,
    val entries: Int = 16,
    val addrWidth: Int = 64,
    val dataWidth: Int = 64,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val sizeWidth: Int = 4,
    val simtLaneWidth: Int = 8)
    extends Module {
  require(queueDepth > 0, "store dispatch queue depth must be nonzero")
  require((queueDepth & (queueDepth - 1)) == 0, "store dispatch queue depth must be a power of two")
  require(entries > 1, "STQ entries must be greater than one")
  require((entries & (entries - 1)) == 0, "STQ entries must be a power of two")

  val io = IO(new StoreDispatchSTQPathIO(p, queueDepth, entries, addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth, sizeWidth, simtLaneWidth))

  val queues = Module(new StoreDispatchQueues(p, queueDepth))
  val bridge = Module(new StoreDispatchToSTQ(p, entries, addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth, sizeWidth, simtLaneWidth))
  val staProbe = Module(new STQInsertProbe(entries, addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth, sizeWidth, simtLaneWidth))
  val stdProbe = Module(new STQInsertProbe(entries, addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth, sizeWidth, simtLaneWidth))
  val stq = Module(new STQEntryBank(entries, addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth, sizeWidth, simtLaneWidth))

  queues.io.flushValid := io.flush.req.valid
  queues.io.staIn := io.staIn
  queues.io.stdIn := io.stdIn
  queues.io.unsplitIn := io.unsplitIn
  queues.io.staDequeueReady := bridge.io.staDequeueReady
  queues.io.stdDequeueReady := bridge.io.stdDequeueReady

  bridge.io.flushValid := io.flush.req.valid
  bridge.io.staValid := queues.io.staOutValid
  bridge.io.stdValid := queues.io.stdOutValid
  bridge.io.sta := queues.io.staOut
  bridge.io.std := queues.io.stdOut
  bridge.io.staExec := io.staExec
  bridge.io.stdExec := io.stdExec
  bridge.io.staInsertReady := staProbe.io.ready
  bridge.io.stdInsertReady := stdProbe.io.ready

  staProbe.io.requestValid := bridge.io.staCandidate
  staProbe.io.request := bridge.io.staRequest
  staProbe.io.rows := stq.io.rows
  staProbe.io.flushApplied := stq.io.flushApplied

  stdProbe.io.requestValid := bridge.io.stdCandidate
  stdProbe.io.request := bridge.io.stdRequest
  stdProbe.io.rows := stq.io.rows
  stdProbe.io.flushApplied := stq.io.flushApplied

  stq.io.flush := io.flush
  stq.io.insertValid := bridge.io.insertValid
  stq.io.insert := bridge.io.insert
  stq.io.markCommitValid := io.markCommitValid
  stq.io.markCommitIndex := io.markCommitIndex
  stq.io.commitFreeValid := io.commitFreeValid
  stq.io.commitFreeIndex := io.commitFreeIndex
  stq.io.commitFreeMaskValid := io.commitFreeMaskValid
  stq.io.commitFreeMask := io.commitFreeMask

  io.staReady := queues.io.staReady
  io.stdReady := queues.io.stdReady
  io.inputProtocolError := queues.io.inputProtocolError
  io.splitInput := queues.io.splitInput
  io.unsplitInput := queues.io.unsplitInput
  io.staEnqueueFire := queues.io.staEnqueueFire
  io.stdEnqueueFire := queues.io.stdEnqueueFire
  io.staDequeueFire := queues.io.staDequeueFire
  io.stdDequeueFire := queues.io.stdDequeueFire
  io.staQueueValid := queues.io.staOutValid
  io.stdQueueValid := queues.io.stdOutValid
  io.staQueue := queues.io.staOut
  io.stdQueue := queues.io.stdOut
  io.staQueueCount := queues.io.staCount
  io.stdQueueCount := queues.io.stdCount
  io.staQueueFull := queues.io.staFull
  io.stdQueueFull := queues.io.stdFull

  io.staInsertReady := staProbe.io.ready
  io.stdInsertReady := stdProbe.io.ready
  io.staInsertCanMerge := staProbe.io.canMerge
  io.stdInsertCanMerge := stdProbe.io.canMerge
  io.staInsertCanAllocate := staProbe.io.canAllocate
  io.stdInsertCanAllocate := stdProbe.io.canAllocate
  io.staInsertConflict := staProbe.io.conflict
  io.stdInsertConflict := stdProbe.io.conflict
  io.staInsertIndex := staProbe.io.insertIndex
  io.stdInsertIndex := stdProbe.io.insertIndex

  io.staRequest := bridge.io.staRequest
  io.stdRequest := bridge.io.stdRequest
  io.staCandidate := bridge.io.staCandidate
  io.stdCandidate := bridge.io.stdCandidate
  io.selectedSta := bridge.io.selectedSta
  io.selectedStd := bridge.io.selectedStd
  io.blockedByStaExec := bridge.io.blockedByStaExec
  io.blockedByStdExec := bridge.io.blockedByStdExec
  io.blockedByStaInsert := bridge.io.blockedByStaInsert
  io.blockedByStdInsert := bridge.io.blockedByStdInsert
  io.stdBypassStaBlocked := bridge.io.stdBypassStaBlocked

  io.insertValid := bridge.io.insertValid
  io.insert := bridge.io.insert
  io.insertAccepted := stq.io.insertAccepted
  io.insertAllocated := stq.io.insertAllocated
  io.insertMerged := stq.io.insertMerged
  io.insertConflict := stq.io.insertConflict
  io.insertIndex := stq.io.insertIndex

  io.markCommitAccepted := stq.io.markCommitAccepted
  io.markCommitIgnored := stq.io.markCommitIgnored
  io.commitFreeAccepted := stq.io.commitFreeAccepted
  io.commitFreeIgnored := stq.io.commitFreeIgnored
  io.commitFreeAcceptedMask := stq.io.commitFreeAcceptedMask
  io.commitFreeIgnoredMask := stq.io.commitFreeIgnoredMask
  io.commitFreeCount := stq.io.commitFreeCount

  io.stqFlushApplied := stq.io.flushApplied
  io.stqFlushMatchMask := stq.io.flushMatchMask
  io.stqFlushFreeMask := stq.io.flushFreeMask
  io.stqFlushStatusBlockedMask := stq.io.flushStatusBlockedMask
  io.stqFlushFreeCount := stq.io.flushFreeCount
  io.stqRows := stq.io.rows
  io.stqOccupiedMask := stq.io.occupiedMask
  io.stqWaitMask := stq.io.waitMask
  io.stqCommitMask := stq.io.commitMask
  io.stqResidentCount := stq.io.residentCount
  io.stqOutstandingWaitCount := stq.io.outstandingWaitCount
  io.stqEmpty := stq.io.empty
  io.stqFull := stq.io.full
  io.stqStall := stq.io.stall
}
