package linxcore.lsu

import chisel3._
import chisel3.util.log2Ceil

import linxcore.common.{CoreParams, ScalarLsuParams}
import linxcore.recovery.FlushBus
import linxcore.rob.ROBID

class ScalarLSULoadPathIO(val coreParams: CoreParams, val lsuParams: ScalarLsuParams) extends Bundle {
  private val liqPtrWidth = log2Ceil(lsuParams.liqEntries)
  private val liqCountWidth = log2Ceil(lsuParams.liqEntries + 1)
  private val resolveCountWidth = log2Ceil(lsuParams.resolveQueueEntries + 1)

  val flush = Input(Bool())
  val preciseFlush = Input(new FlushBus(
    coreParams.robEntries,
    lsuParams.peIdWidth,
    lsuParams.stidWidth,
    lsuParams.tidWidth
  ))

  val allocValid = Input(Bool())
  val alloc = Input(new LoadInflightAlloc(
    lsuParams.liqEntries,
    coreParams.robEntries,
    lsuParams.addrWidth,
    lsuParams.pcWidth,
    lsuParams.loadSizeWidth,
    lsuParams.archRegWidth,
    lsuParams.physRegWidth,
    lsuParams.peIdWidth,
    lsuParams.stidWidth,
    lsuParams.tidWidth
  ))
  val allocReady = Output(Bool())
  val allocAccepted = Output(Bool())
  val allocIndex = Output(UInt(liqPtrWidth.W))
  val allocLoadId = Output(new ROBID(lsuParams.liqEntries))

  val launchValid = Input(Bool())
  val launchIndex = Input(UInt(liqPtrWidth.W))
  val launchReady = Output(Bool())
  val launchAccepted = Output(Bool())
  val launchBlockedByResolveCredit = Output(Bool())

  val pickValid = Input(Bool())
  val pickIndex = Input(UInt(liqPtrWidth.W))
  val pickReady = Output(Bool())
  val pickAccepted = Output(Bool())

  val scbReturnValid = Input(Bool())
  val scbReturnIndex = Input(UInt(liqPtrWidth.W))
  val scbReturnReady = Output(Bool())
  val scbReturnAccepted = Output(Bool())

  val markResolvedValid = Input(Bool())
  val markResolvedIndex = Input(UInt(liqPtrWidth.W))
  val markResolvedReady = Output(Bool())
  val markResolvedAccepted = Output(Bool())

  val e2Stores = Input(Vec(
    lsuParams.stqEntries,
    new LoadStoreForwardStore(
      coreParams.robEntries,
      lsuParams.stqEntries,
      lsuParams.addrWidth,
      lsuParams.pcWidth,
      lsuParams.lineBytes
    )
  ))
  val e2BaseData = Input(UInt((lsuParams.lineBytes * 8).W))
  val e2BaseValidMask = Input(UInt(lsuParams.lineBytes.W))
  val e2LoadDataReturned = Input(Bool())
  val e2ScbReturned = Input(Bool())
  val e2StqReturned = Input(Bool())
  val e2ReturnReady = Input(Bool())

  val replayWakeValid = Input(Bool())
  val replayWake = Input(new LoadReplayWakeupRequest(
    coreParams.robEntries,
    lsuParams.addrWidth,
    lsuParams.pcWidth,
    lsuParams.lineBytes
  ))
  val refillValid = Input(Bool())
  val refill = Input(new LoadRefillWakeupRequest(lsuParams.addrWidth, lsuParams.lineBytes))

  val resolveRetireValid = Input(Bool())
  val resolveRetireBid = Input(new ROBID(coreParams.robEntries))
  val resolveRetireLsId = Input(new ROBID(coreParams.robEntries))

  val liqRows = Output(Vec(
    lsuParams.liqEntries,
    new LoadInflightRow(
      lsuParams.liqEntries,
      coreParams.robEntries,
      lsuParams.stqEntries,
      lsuParams.addrWidth,
      lsuParams.pcWidth,
      lsuParams.lineBytes,
      lsuParams.loadSizeWidth,
      lsuParams.archRegWidth,
      lsuParams.physRegWidth,
      lsuParams.peIdWidth,
      lsuParams.stidWidth,
      lsuParams.tidWidth
    )
  ))
  val liqOccupiedMask = Output(UInt(lsuParams.liqEntries.W))
  val liqWaitMask = Output(UInt(lsuParams.liqEntries.W))
  val liqRepickMask = Output(UInt(lsuParams.liqEntries.W))
  val liqMissMask = Output(UInt(lsuParams.liqEntries.W))
  val liqResolvedMask = Output(UInt(lsuParams.liqEntries.W))
  val liqWaitStoreMask = Output(UInt(lsuParams.liqEntries.W))
  val liqFlushPruneMask = Output(UInt(lsuParams.liqEntries.W))
  val liqFlushPruneCount = Output(UInt(liqCountWidth.W))
  val liqResidentCount = Output(UInt(liqCountWidth.W))
  val liqEmpty = Output(Bool())
  val liqFull = Output(Bool())
  val liqMissPending = Output(Bool())
  val replayWakeWaitStoreClearMask = Output(UInt(lsuParams.liqEntries.W))
  val replayWakeMergeMask = Output(UInt(lsuParams.liqEntries.W))
  val replayWakeCompletedMask = Output(UInt(lsuParams.liqEntries.W))
  val refillAccepted = Output(Bool())
  val refillWakeMask = Output(UInt(lsuParams.liqEntries.W))

  val resolveEntries = Output(Vec(
    lsuParams.resolveQueueEntries,
    new LoadResolveQueueEntry(
      lsuParams.liqEntries,
      coreParams.robEntries,
      lsuParams.addrWidth,
      lsuParams.pcWidth,
      lsuParams.peIdWidth,
      lsuParams.stidWidth,
      lsuParams.tidWidth,
      lsuParams.lineBytes,
      lsuParams.loadSizeWidth
    )
  ))
  val resolveConflictRows = Output(Vec(
    lsuParams.resolveQueueEntries,
    new MDBConflictLoadEntry(
      coreParams.robEntries,
      lsuParams.addrWidth,
      lsuParams.pcWidth,
      lsuParams.peIdWidth,
      lsuParams.stidWidth,
      lsuParams.tidWidth,
      lsuParams.loadSizeWidth
    )
  ))
  val resolveValidMask = Output(UInt(lsuParams.resolveQueueEntries.W))
  val resolveCount = Output(UInt(resolveCountWidth.W))
  val resolveFlushPruneMask = Output(UInt(lsuParams.resolveQueueEntries.W))
  val resolveFlushPruneCount = Output(UInt(resolveCountWidth.W))
  val resolveRetireMask = Output(UInt(lsuParams.resolveQueueEntries.W))
  val resolveRetireCount = Output(UInt(resolveCountWidth.W))
  val resolveEmpty = Output(Bool())
  val resolveFull = Output(Bool())

  val transferPending = Output(Bool())
  val transferProtocolError = Output(Bool())
  val empty = Output(Bool())
}

class ScalarLSULoadPath(val coreParams: CoreParams = CoreParams()) extends Module {
  private val p = coreParams.scalarLsu
  require(p.resolveQueueEntries >= 4,
    "resolveQueueEntries must reserve two pipeline arrivals plus one resident row")
  require(p.liqEntries <= coreParams.robEntries,
    "liqEntries must fit the ROB identity domain used by replay diagnostics")
  require(p.lineBytes == 64, "canonical scalar load path currently requires 64-byte cache lines")

  val io = IO(new ScalarLSULoadPathIO(coreParams, p))

  val liq = Module(new LoadInflightQueue(
    liqEntries = p.liqEntries,
    idEntries = coreParams.robEntries,
    storeEntries = p.stqEntries,
    addrWidth = p.addrWidth,
    pcWidth = p.pcWidth,
    lineBytes = p.lineBytes,
    sizeWidth = p.loadSizeWidth,
    archRegWidth = p.archRegWidth,
    physRegWidth = p.physRegWidth,
    peIdWidth = p.peIdWidth,
    stidWidth = p.stidWidth,
    tidWidth = p.tidWidth
  ))
  val resolveQueue = Module(new LoadResolveQueue(
    queueEntries = p.resolveQueueEntries,
    liqEntries = p.liqEntries,
    idEntries = coreParams.robEntries,
    addrWidth = p.addrWidth,
    pcWidth = p.pcWidth,
    peIdWidth = p.peIdWidth,
    stidWidth = p.stidWidth,
    tidWidth = p.tidWidth,
    lineBytes = p.lineBytes,
    sizeWidth = p.loadSizeWidth
  ))

  val flushCycle = io.flush || io.preciseFlush.req.valid
  val transferPending = RegInit(false.B)
  val transferIndex = RegInit(0.U(log2Ceil(p.liqEntries).W))

  val resolveCreditSafe =
    resolveQueue.io.count <= (p.resolveQueueEntries - 3).U &&
      (!transferPending || liq.io.clearResolvedAccepted)

  liq.io.flush := io.flush
  liq.io.preciseFlush := io.preciseFlush
  liq.io.allocValid := io.allocValid
  liq.io.alloc := io.alloc
  liq.io.launchValid := io.launchValid && resolveCreditSafe
  liq.io.launchIndex := io.launchIndex
  liq.io.pickValid := io.pickValid
  liq.io.pickIndex := io.pickIndex
  liq.io.scbReturnValid := io.scbReturnValid
  liq.io.scbReturnIndex := io.scbReturnIndex
  liq.io.markResolvedValid := io.markResolvedValid
  liq.io.markResolvedIndex := io.markResolvedIndex
  liq.io.e2Stores := io.e2Stores
  liq.io.e2BaseData := io.e2BaseData
  liq.io.e2BaseValidMask := io.e2BaseValidMask
  liq.io.e2LoadDataReturned := io.e2LoadDataReturned
  liq.io.e2ScbReturned := io.e2ScbReturned
  liq.io.e2StqReturned := io.e2StqReturned
  liq.io.e2ReturnReady := io.e2ReturnReady
  liq.io.replayWakeValid := io.replayWakeValid
  liq.io.replayWake := io.replayWake
  liq.io.refillValid := io.refillValid
  liq.io.refill := io.refill
  liq.io.clearResolvedValid := transferPending
  liq.io.clearResolvedIndex := transferIndex

  liq.io.rowMutationValid := false.B
  liq.io.rowMutationTargetIndex := 0.U
  liq.io.rowMutationSetWaitStatus := false.B
  liq.io.rowMutationKeepRepickStatus := false.B
  liq.io.rowMutationClearReturnState := false.B
  liq.io.rowMutationLineWrite := false.B
  liq.io.rowMutationWaitStoreWrite := false.B
  liq.io.rowMutationNextWaitStore := false.B
  liq.io.rowMutationNextWaitStoreInfo := 0.U.asTypeOf(liq.io.rowMutationNextWaitStoreInfo)
  liq.io.rowMutationNextLineData := 0.U
  liq.io.rowMutationNextValidMask := 0.U
  liq.io.rowMutationNextDataComplete := false.B
  liq.io.rowMutationNextScbReturned := false.B
  liq.io.rowMutationNextStqReturned := false.B
  liq.io.rowMutationNextStoreSourceReturned := false.B

  val hitRow = liq.io.rows(liq.io.e4UpdateIndex)
  resolveQueue.io.flush := io.flush
  resolveQueue.io.preciseFlush := io.preciseFlush
  resolveQueue.io.pushValid := liq.io.lhqRecordValid
  resolveQueue.io.pushPeId := hitRow.peId
  resolveQueue.io.pushStid := hitRow.stid
  resolveQueue.io.pushTid := hitRow.tid
  resolveQueue.io.pushRecord := liq.io.lhqRecord
  resolveQueue.io.retireValid := io.resolveRetireValid
  resolveQueue.io.retireBid := io.resolveRetireBid
  resolveQueue.io.retireLsId := io.resolveRetireLsId

  val transferProtocolError =
    liq.io.lhqRecordValid && !resolveQueue.io.pushReady ||
      (resolveQueue.io.pushAccepted && transferPending && !liq.io.clearResolvedAccepted)

  when(flushCycle) {
    transferPending := false.B
  }.otherwise {
    when(liq.io.clearResolvedAccepted) {
      transferPending := false.B
    }
    when(resolveQueue.io.pushAccepted) {
      transferPending := true.B
      transferIndex := liq.io.e4UpdateIndex
    }
  }

  io.allocReady := liq.io.allocReady
  io.allocAccepted := liq.io.allocAccepted
  io.allocIndex := liq.io.allocIndex
  io.allocLoadId := liq.io.allocLoadId
  io.launchReady := liq.io.launchReady && resolveCreditSafe
  io.launchAccepted := liq.io.launchAccepted
  io.launchBlockedByResolveCredit := io.launchValid && !resolveCreditSafe
  io.pickReady := liq.io.pickReady
  io.pickAccepted := liq.io.pickAccepted
  io.scbReturnReady := liq.io.scbReturnReady
  io.scbReturnAccepted := liq.io.scbReturnAccepted
  io.markResolvedReady := liq.io.markResolvedReady
  io.markResolvedAccepted := liq.io.markResolvedAccepted
  io.liqRows := liq.io.rows
  io.liqOccupiedMask := liq.io.occupiedMask
  io.liqWaitMask := liq.io.waitMask
  io.liqRepickMask := liq.io.repickMask
  io.liqMissMask := liq.io.missMask
  io.liqResolvedMask := liq.io.resolvedMask
  io.liqWaitStoreMask := liq.io.waitStoreMask
  io.liqFlushPruneMask := liq.io.flushPruneMask
  io.liqFlushPruneCount := liq.io.flushPruneCount
  io.liqResidentCount := liq.io.residentCount
  io.liqEmpty := liq.io.empty
  io.liqFull := liq.io.full
  io.liqMissPending := liq.io.missPending
  io.replayWakeWaitStoreClearMask := liq.io.replayWakeWaitStoreClearMask
  io.replayWakeMergeMask := liq.io.replayWakeMergeMask
  io.replayWakeCompletedMask := liq.io.replayWakeCompletedMask
  io.refillAccepted := liq.io.refillAccepted
  io.refillWakeMask := liq.io.refillWakeMask
  io.resolveEntries := resolveQueue.io.entries
  io.resolveConflictRows := resolveQueue.io.conflictRows
  io.resolveValidMask := resolveQueue.io.validMask
  io.resolveCount := resolveQueue.io.count
  io.resolveFlushPruneMask := resolveQueue.io.flushPruneMask
  io.resolveFlushPruneCount := resolveQueue.io.flushPruneCount
  io.resolveRetireMask := resolveQueue.io.retireMask
  io.resolveRetireCount := resolveQueue.io.retireCount
  io.resolveEmpty := resolveQueue.io.empty
  io.resolveFull := resolveQueue.io.full
  io.transferPending := transferPending
  io.transferProtocolError := transferProtocolError
  io.empty := liq.io.empty && resolveQueue.io.empty && !transferPending
}
