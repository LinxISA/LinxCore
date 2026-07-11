package linxcore.lsu

import chisel3._
import chisel3.util.log2Ceil

import linxcore.rob.ROBID

class ReducedStoreWaitReplayChiselPathProbeIO(
    val entries: Int,
    val liqEntries: Int,
    val addrWidth: Int,
    val dataWidth: Int,
    val pcWidth: Int,
    val lineBytes: Int,
    val sizeWidth: Int)
    extends Bundle {
  val flush = Input(Bool())

  val storeInsertValid = Input(Bool())
  val storeInsert = Input(new STQStoreRequest(entries, addrWidth, dataWidth, sizeWidth = 4, pcWidth = pcWidth))
  val storeInsertAccepted = Output(Bool())
  val stqOccupiedMask = Output(UInt(entries.W))
  val stqAddrReadyMask = Output(UInt(entries.W))
  val stqDataReadyMask = Output(UInt(entries.W))

  val loadValid = Input(Bool())
  val loadAddr = Input(UInt(addrWidth.W))
  val loadSize = Input(UInt(sizeWidth.W))
  val loadBid = Input(new ROBID(entries))
  val loadLsId = Input(new ROBID(entries))
  val baseLoadData = Input(UInt(dataWidth.W))
  val captureEnable = Input(Bool())

  val forwardWaitBlocked = Output(Bool())
  val forwardReady = Output(Bool())
  val forwardWaitStoreValid = Output(Bool())
  val forwardWaitStoreIndex = Output(UInt(log2Ceil(entries).W))

  val waitSlotCaptureAccepted = Output(Bool())
  val waitSlotActive = Output(Bool())
  val waitStoreClear = Output(Bool())
  val wakeValid = Output(Bool())
  val wakeIdentityMatch = Output(Bool())
  val wakeSelectedRowReady = Output(Bool())

  val relaunchQueuePending = Output(Bool())
  val relaunchQueueOutValid = Output(Bool())
  val relaunchQueueOutFire = Output(Bool())

  val liqCandidateConsumeReady = Output(Bool())
  val liqAllocAccepted = Output(Bool())
  val liqRefillValid = Input(Bool())
  val liqRefillLineAddr = Input(UInt(addrWidth.W))
  val liqRefillData = Input(UInt((lineBytes * 8).W))
  val liqRefillAccepted = Output(Bool())
  val liqRefillWakeMask = Output(UInt(liqEntries.W))
  val liqLaunchEnable = Input(Bool())
  val liqE2LoadDataReturned = Input(Bool())
  val liqE2ScbReturned = Input(Bool())
  val liqE2StqReturned = Input(Bool())
  val liqE2ReturnReady = Input(Bool())
  val liqReplayWakeValid = Input(Bool())
  val liqReplayWake = Input(new LoadReplayWakeupRequest(entries, addrWidth, pcWidth, lineBytes))
  val liqReplayWakeMergeMask = Output(UInt(liqEntries.W))
  val liqReplayWakeCompletedMask = Output(UInt(liqEntries.W))
  val liqLaunchValid = Output(Bool())
  val liqLaunchDriveValid = Output(Bool())
  val liqLaunchReady = Output(Bool())
  val liqLaunchAccepted = Output(Bool())
  val liqLaunchIndex = Output(UInt(log2Ceil(liqEntries).W))
  val liqLaunchSelectedLoadLsId = Output(new ROBID(entries))
  val liqE4UpdateValid = Output(Bool())
  val liqE4UpdateIndex = Output(UInt(log2Ceil(liqEntries).W))
  val liqE4WakeupValid = Output(Bool())
  val liqLhqRecordValid = Output(Bool())
  val liqLhqRecordLoadLsId = Output(new ROBID(entries))
  val liqLhqRecordData = Output(UInt((lineBytes * 8).W))
  val resolveQueuePushAccepted = Output(Bool())
  val resolveQueueRetireValid = Input(Bool())
  val resolveQueueRetireBid = Input(new ROBID(entries))
  val resolveQueueRetireLsId = Input(new ROBID(entries))
  val resolveQueueRetireMask = Output(UInt(liqEntries.W))
  val resolveQueueRetireCount = Output(UInt(log2Ceil(liqEntries + 1).W))
  val resolveQueueValidMask = Output(UInt(liqEntries.W))
  val resolveQueueCount = Output(UInt(log2Ceil(liqEntries + 1).W))
  val resolveQueueFirstLoadLsId = Output(new ROBID(entries))
  val mdbStore = Input(new MDBConflictStoreProbe(entries, addrWidth = addrWidth, pcWidth = pcWidth, sizeWidth = sizeWidth))
  val mdbLookupValid = Input(Bool())
  val mdbLookupUseLiveLoad = Input(Bool())
  val mdbDeleteValid = Input(Bool())
  val mdbDeleteWaitStorePc = Input(UInt(pcWidth.W))
  val mdbResolveCandidateMask = Output(UInt(liqEntries.W))
  val mdbConflictValid = Output(Bool())
  val mdbConflictFromResolveQueue = Output(Bool())
  val mdbConflictResolveIndex = Output(UInt(log2Ceil(liqEntries.max(2)).W))
  val mdbInnerFlush = Output(Bool())
  val mdbNukeFlush = Output(Bool())
  val mdbConflictLoadLsId = Output(new ROBID(entries))
  val mdbConflictLoadPc = Output(UInt(pcWidth.W))
  val mdbFanoutRecordReady = Output(Bool())
  val mdbFanoutRecordAccepted = Output(Bool())
  val mdbFanoutRecordProcessed = Output(Bool())
  val mdbFanoutBmdbReportValid = Output(Bool())
  val mdbFanoutBmdbLoadBid = Output(new ROBID(entries))
  val mdbFanoutBmdbStoreBid = Output(new ROBID(entries))
  val mdbFanoutBmdbStoreStid = Output(UInt(8.W))
  val mdbFanoutSsitValidMask = Output(UInt(liqEntries.W))
  val mdbFanoutLookupReady = Output(Bool())
  val mdbFanoutLookupAccepted = Output(Bool())
  val mdbFanoutLookupProcessed = Output(Bool())
  val mdbFanoutDeleteReady = Output(Bool())
  val mdbFanoutDeleteAccepted = Output(Bool())
  val mdbFanoutDeleteProcessed = Output(Bool())
  val mdbFanoutDeleteMatched = Output(Bool())
  val mdbFanoutDeleteReleased = Output(Bool())
  val mdbFanoutDeleteDroppedBelowStall = Output(Bool())
  val mdbFanoutLuOutDequeued = Output(Bool())
  val mdbFanoutLuOutHit = Output(Bool())
  val mdbFanoutSuOutDequeued = Output(Bool())
  val mdbFanoutSuOutHit = Output(Bool())
  val mdbFanoutSuMatchedStore = Output(Bool())
  val mdbFanoutSuStorePending = Output(Bool())
  val mdbFanoutSuWakeupValid = Output(Bool())
  val mdbFanoutSuWakeupStoreIndex = Output(UInt(log2Ceil(entries).W))
  val mdbFanoutSuWakeupBid = Output(new ROBID(entries))
  val mdbLookupWaitPlanLookupHit = Output(Bool())
  val mdbLookupWaitPlanCandidateMask = Output(UInt(liqEntries.W))
  val mdbLookupWaitPlanTargetIndex = Output(UInt(log2Ceil(liqEntries).W))
  val mdbLookupWaitPlanWaitIntentValid = Output(Bool())
  val mdbLookupWaitPlanRequestValid = Output(Bool())
  val mdbLookupWaitPlanBlockedByNoTarget = Output(Bool())
  val mdbLookupWaitPlanBlockedByMissingStoreIndex = Output(Bool())
  val mdbLookupWaitPlanBlockedByMissingStoreLsId = Output(Bool())
  val rowMutationBridgeValid = Output(Bool())
  val rowMutationTargetEvidenceValid = Output(Bool())
  val rowMutationWriteEnable = Output(Bool())
  val rowMutationApplyValid = Output(Bool())
  val rowMutationBlockedByControl = Output(Bool())
  val rowMutationControlBlockedByScbNotReturned = Output(Bool())
  val rowMutationControlBlockedByE4UpdateConflict = Output(Bool())
  val liqClearResolvedPending = Output(Bool())
  val liqClearResolvedAccepted = Output(Bool())
  val liqResidentCount = Output(UInt(log2Ceil(liqEntries + 1).W))
  val liqOccupiedMask = Output(UInt(liqEntries.W))
  val liqWaitMask = Output(UInt(liqEntries.W))
  val liqWaitStoreMask = Output(UInt(liqEntries.W))
  val liqRepickMask = Output(UInt(liqEntries.W))
  val liqResolvedMask = Output(UInt(liqEntries.W))
  val liqSourcesReturnedMask = Output(UInt(liqEntries.W))
  val liqScbReturnedMask = Output(UInt(liqEntries.W))
  val liqStqReturnedMask = Output(UInt(liqEntries.W))
  val liqFirstYoungestStoreLsId = Output(new ROBID(entries))
}

class ReducedStoreWaitReplayChiselPathProbe(
    val entries: Int = 8,
    val liqEntries: Int = 4,
    val addrWidth: Int = 64,
    val dataWidth: Int = 64,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val sizeWidth: Int = 7)
    extends Module {
  require(entries > 1 && (entries & (entries - 1)) == 0)
  require(liqEntries > 1 && (liqEntries & (liqEntries - 1)) == 0)

  val io = IO(new ReducedStoreWaitReplayChiselPathProbeIO(
    entries,
    liqEntries,
    addrWidth,
    dataWidth,
    pcWidth,
    lineBytes,
    sizeWidth
  ))

  val stq = Module(new STQEntryBank(entries, addrWidth, dataWidth, sizeWidth = 4))
  stq.io.flush := 0.U.asTypeOf(stq.io.flush)
  stq.io.flush.req.valid := io.flush
  stq.io.insertValid := io.storeInsertValid && !io.flush
  stq.io.insert := io.storeInsert
  stq.io.markCommitValid := false.B
  stq.io.markCommitIndex := 0.U
  stq.io.commitFreeValid := false.B
  stq.io.commitFreeIndex := 0.U
  stq.io.commitFreeMaskValid := false.B
  stq.io.commitFreeMask := 0.U

  val forward = Module(new ReducedStoreResidentForward(entries, addrWidth, dataWidth, sizeWidth = 4, pcWidth = pcWidth, lineBytes = lineBytes))
  forward.io.enable := !io.flush
  forward.io.loadValid := io.loadValid
  forward.io.loadAddr := io.loadAddr
  forward.io.loadSize := io.loadSize
  forward.io.loadBid := io.loadBid
  forward.io.loadLsId := io.loadLsId
  forward.io.baseLoadData := io.baseLoadData
  forward.io.rows := stq.io.rows

  val waitSlot = Module(new ReducedLoadWaitReplaySlot(entries, entries, addrWidth, pcWidth, lineBytes, sizeWidth))
  waitSlot.io.flush := io.flush
  waitSlot.io.captureValid := io.captureEnable && forward.io.waitBlocked
  waitSlot.io.capturePc := 0.U
  waitSlot.io.captureAddr := io.loadAddr
  waitSlot.io.captureSize := io.loadSize
  waitSlot.io.captureReturnSignExtend := false.B
  waitSlot.io.captureDst := LoadReplayDestination.none()
  waitSlot.io.captureSourceTraceValid := false.B
  waitSlot.io.captureSource0 := 0.U.asTypeOf(waitSlot.io.captureSource0)
  waitSlot.io.captureSource1 := 0.U.asTypeOf(waitSlot.io.captureSource1)
  waitSlot.io.captureBid := io.loadBid
  waitSlot.io.captureGid := ROBID.zero(entries)
  waitSlot.io.captureRid := ROBID.zero(entries)
  waitSlot.io.captureLsId := io.loadLsId
  waitSlot.io.captureYoungestStoreId := forward.io.waitStore.storeId
  waitSlot.io.captureYoungestStoreLsId := forward.io.waitStore.storeLsId
  waitSlot.io.captureWaitStore := forward.io.waitStore

  val wake = Module(new ResidentStoreReplayWakeup(entries, addrWidth, dataWidth, sizeWidth = 4, pcWidth = pcWidth, lineBytes = lineBytes))
  wake.io.enable := !io.flush
  wake.io.waitStore := waitSlot.io.storedWaitStore
  wake.io.rows := stq.io.rows
  waitSlot.io.replayWakeValid := wake.io.wakeValid
  waitSlot.io.replayWake := wake.io.wake

  val relaunchQueue = Module(new ReducedLoadReplayRelaunchQueue(entries, depth = 2, addrWidth, pcWidth, sizeWidth))
  relaunchQueue.io.flush := io.flush
  relaunchQueue.io.enqueueValid := waitSlot.io.relaunch.valid
  relaunchQueue.io.enqueue := waitSlot.io.relaunch

  val liq = Module(new ReducedLoadReplayLiqAllocPath(liqEntries, entries, entries, addrWidth, pcWidth, lineBytes, sizeWidth))
  liq.io.preciseFlush := 0.U.asTypeOf(liq.io.preciseFlush)
  val resolveQueue = Module(new LoadResolveQueue(queueEntries = liqEntries, liqEntries = liqEntries, idEntries = entries, addrWidth = addrWidth, pcWidth = pcWidth, lineBytes = lineBytes, sizeWidth = sizeWidth))
  val clearResolvedPending = RegInit(false.B)
  val clearResolvedIndex = RegInit(0.U(log2Ceil(liqEntries).W))

  liq.io.flush := io.flush
  liq.io.candidateValid := relaunchQueue.io.outValid
  liq.io.candidate := relaunchQueue.io.out
  liq.io.launchEnable := io.liqLaunchEnable
  liq.io.pickValid := false.B
  liq.io.pickIndex := 0.U
  liq.io.scbReturnValid := false.B
  liq.io.scbReturnIndex := 0.U
  liq.io.markResolvedValid := false.B
  liq.io.markResolvedIndex := 0.U
  liq.io.e2Stores := 0.U.asTypeOf(liq.io.e2Stores)
  liq.io.e2BaseData := 0.U
  liq.io.e2BaseValidMask := 0.U
  liq.io.e2LoadDataReturned := io.liqE2LoadDataReturned
  liq.io.e2ScbReturned := io.liqE2ScbReturned
  liq.io.e2StqReturned := io.liqE2StqReturned
  liq.io.e2ReturnReady := io.liqE2ReturnReady
  liq.io.replayWakeValid := io.liqReplayWakeValid
  liq.io.replayWake := io.liqReplayWake
  liq.io.refillValid := io.liqRefillValid
  liq.io.refill.isRead := true.B
  liq.io.refill.lineAddr := io.liqRefillLineAddr
  liq.io.refill.data := io.liqRefillData
  liq.io.refill.l2Miss := false.B
  liq.io.clearResolvedValid := clearResolvedPending
  liq.io.clearResolvedIndex := clearResolvedIndex
  relaunchQueue.io.outReady := liq.io.candidateConsumeReady

  resolveQueue.io.flush := io.flush
  resolveQueue.io.preciseFlush := 0.U.asTypeOf(resolveQueue.io.preciseFlush)
  resolveQueue.io.pushValid := liq.io.lhqRecordValid
  resolveQueue.io.pushPeId := 0.U
  resolveQueue.io.pushStid := 0.U
  resolveQueue.io.pushTid := 0.U
  resolveQueue.io.pushRecord := liq.io.lhqRecord
  resolveQueue.io.retireValid := io.resolveQueueRetireValid
  resolveQueue.io.retireBid := io.resolveQueueRetireBid
  resolveQueue.io.retireLsId := io.resolveQueueRetireLsId

  val mdbDetect = Module(new MDBConflictDetect(
    entries = entries,
    loadEntries = liqEntries,
    resolveEntries = liqEntries,
    addrWidth = addrWidth,
    pcWidth = pcWidth,
    sizeWidth = sizeWidth
  ))
  mdbDetect.io.store := io.mdbStore
  mdbDetect.io.activeLoads := 0.U.asTypeOf(mdbDetect.io.activeLoads)
  mdbDetect.io.resolvedQueue := resolveQueue.io.conflictRows

  val mdbFanout = Module(new MDBQueueFanout(
    entries = entries,
    ssitEntries = liqEntries,
    commandQueueEntries = liqEntries,
    outputQueueEntries = liqEntries,
    storeEntries = entries,
    addrWidth = addrWidth,
    pcWidth = pcWidth,
    sizeWidth = sizeWidth
  ))
  val mdbZeroBus = Wire(new MDBQueueBus(entries, addrWidth = addrWidth, pcWidth = pcWidth, sizeWidth = sizeWidth))
  mdbZeroBus := 0.U.asTypeOf(mdbZeroBus)
  mdbZeroBus.ldInfo.bid := ROBID.disabled(entries)
  mdbZeroBus.ldInfo.lsId := ROBID.disabled(entries)
  mdbZeroBus.stInfo.bid := ROBID.disabled(entries)
  mdbZeroBus.stInfo.lsId := ROBID.disabled(entries)

  val mdbRecordBus = Wire(chiselTypeOf(mdbZeroBus))
  mdbRecordBus := mdbZeroBus
  mdbRecordBus.valid := mdbDetect.io.conflictValid
  mdbRecordBus.ldInfo.valid := mdbDetect.io.conflictValid
  mdbRecordBus.ldInfo.pc := mdbDetect.io.record.load.pc
  mdbRecordBus.ldInfo.bid := mdbDetect.io.record.load.bid
  mdbRecordBus.ldInfo.lsId := mdbDetect.io.record.load.lsId
  mdbRecordBus.ldInfo.stid := mdbDetect.io.record.load.stid
  mdbRecordBus.ldInfo.addr := mdbDetect.io.record.load.addr
  mdbRecordBus.ldInfo.size := mdbDetect.io.record.load.size
  mdbRecordBus.ldInfo.isTile := mdbDetect.io.record.load.isTile
  mdbRecordBus.stInfo.valid := mdbDetect.io.conflictValid
  mdbRecordBus.stInfo.pc := mdbDetect.io.record.store.pc
  mdbRecordBus.stInfo.bid := mdbDetect.io.record.store.bid
  mdbRecordBus.stInfo.lsId := mdbDetect.io.record.store.lsId
  mdbRecordBus.stInfo.stid := mdbDetect.io.record.store.stid
  mdbRecordBus.stInfo.addr := mdbDetect.io.record.store.addr
  mdbRecordBus.stInfo.size := mdbDetect.io.record.store.size
  mdbRecordBus.stInfo.isTile := mdbDetect.io.record.store.isTile
  mdbRecordBus.conf := 1.U

  val mdbLookupBus = Wire(chiselTypeOf(mdbZeroBus))
  mdbLookupBus := mdbZeroBus
  mdbLookupBus.valid := io.mdbLookupValid
  mdbLookupBus.ldInfo.valid := io.mdbLookupValid
  mdbLookupBus.ldInfo.pc := Mux(io.mdbLookupUseLiveLoad, 0.U, resolveQueue.io.entries(0).record.pc)
  mdbLookupBus.ldInfo.bid := Mux(io.mdbLookupUseLiveLoad, io.loadBid, resolveQueue.io.entries(0).record.bid)
  mdbLookupBus.ldInfo.lsId := Mux(io.mdbLookupUseLiveLoad, io.loadLsId, resolveQueue.io.entries(0).record.loadLsId)
  mdbLookupBus.ldInfo.stid := resolveQueue.io.entries(0).stid
  mdbLookupBus.ldInfo.addr := Mux(io.mdbLookupUseLiveLoad, io.loadAddr, resolveQueue.io.entries(0).record.addr)
  mdbLookupBus.ldInfo.size := Mux(io.mdbLookupUseLiveLoad, io.loadSize, resolveQueue.io.entries(0).record.size)
  mdbLookupBus.ldInfo.isTile := false.B
  mdbLookupBus.conf := 1.U

  val mdbDeleteBus = Wire(chiselTypeOf(mdbZeroBus))
  mdbDeleteBus := mdbZeroBus
  mdbDeleteBus.valid := io.mdbDeleteValid
  mdbDeleteBus.ldInfo.valid := io.mdbDeleteValid
  mdbDeleteBus.ldInfo.pc := resolveQueue.io.entries(0).record.pc
  mdbDeleteBus.ldInfo.bid := resolveQueue.io.entries(0).record.bid
  mdbDeleteBus.ldInfo.lsId := resolveQueue.io.entries(0).record.loadLsId
  mdbDeleteBus.ldInfo.stid := resolveQueue.io.entries(0).stid
  mdbDeleteBus.ldInfo.waitStorePc := io.mdbDeleteWaitStorePc
  mdbDeleteBus.conf := 1.U

  val mdbFanoutStoreRows = Wire(Vec(
    entries,
    new MDBStoreWakeupEntry(entries, entries, addrWidth = addrWidth, pcWidth = pcWidth, sizeWidth = sizeWidth)
  ))
  for (idx <- 0 until entries) {
    val stqRow = stq.io.rows(idx)
    val wakeRow = Wire(chiselTypeOf(mdbFanoutStoreRows(idx)))
    wakeRow := 0.U.asTypeOf(wakeRow)
    wakeRow.bid := ROBID.disabled(entries)
    wakeRow.lsId := ROBID.disabled(entries)
    wakeRow.valid := stqRow.valid
    wakeRow.storeIndex := idx.U
    wakeRow.pc := stqRow.pc
    wakeRow.bid := stqRow.bid
    wakeRow.lsId := stqRow.lsId
    wakeRow.stid := stqRow.stid
    wakeRow.addr := stqRow.addr
    wakeRow.size := stqRow.size.pad(sizeWidth)
    wakeRow.addrReady := stqRow.addrReady
    wakeRow.dataReady := stqRow.dataReady
    wakeRow.isTile := !stqRow.scalarIex
    mdbFanoutStoreRows(idx) := wakeRow
  }

  mdbFanout.io.lookupIn := mdbLookupBus
  mdbFanout.io.lookupInValid := io.mdbLookupValid
  mdbFanout.io.deleteIn := mdbDeleteBus
  mdbFanout.io.deleteInValid := io.mdbDeleteValid
  mdbFanout.io.recordIn := mdbRecordBus
  mdbFanout.io.recordInValid := mdbDetect.io.conflictValid
  mdbFanout.io.luDequeueReady := true.B
  mdbFanout.io.suCheckReady := true.B
  mdbFanout.io.storeRows := mdbFanoutStoreRows

  val mdbLookupWaitPlan = Module(new LoadReplayMdbLookupWaitPlan(
    liqEntries = liqEntries,
    idEntries = entries,
    storeEntries = entries,
    addrWidth = addrWidth,
    pcWidth = pcWidth,
    lineBytes = lineBytes,
    sizeWidth = sizeWidth
  ))
  mdbLookupWaitPlan.io.enable := true.B
  mdbLookupWaitPlan.io.flush := io.flush
  mdbLookupWaitPlan.io.luOutValid := mdbFanout.io.luOutValid
  mdbLookupWaitPlan.io.luOut := mdbFanout.io.luOut
  mdbLookupWaitPlan.io.rows := liq.io.rows
  mdbLookupWaitPlan.io.storeIndexValid := mdbFanout.io.suMatchedStore
  mdbLookupWaitPlan.io.storeIndex := mdbFanout.io.suMatchedStoreIndex
  mdbLookupWaitPlan.io.storeLsIdValid := mdbFanout.io.suMatchedStore
  mdbLookupWaitPlan.io.storeLsId := mdbFanout.io.suMatchedStoreLsId

  liq.io.rowMutationRequestValid := mdbLookupWaitPlan.io.requestValid
  liq.io.rowMutationRequestTargetMask := mdbLookupWaitPlan.io.requestTargetMask
  liq.io.rowMutationRequestTargetIndex := mdbLookupWaitPlan.io.requestTargetIndex
  liq.io.rowMutationSetWaitStatus := mdbLookupWaitPlan.io.setWaitStatus
  liq.io.rowMutationKeepRepickStatus := mdbLookupWaitPlan.io.keepRepickStatus
  liq.io.rowMutationClearReturnState := mdbLookupWaitPlan.io.clearReturnState
  liq.io.rowMutationLineWrite := mdbLookupWaitPlan.io.lineWrite
  liq.io.rowMutationWaitStoreWrite := mdbLookupWaitPlan.io.waitStoreWrite
  liq.io.rowMutationNextWaitStore := mdbLookupWaitPlan.io.nextWaitStore
  liq.io.rowMutationNextWaitStoreInfo := mdbLookupWaitPlan.io.nextWaitStoreInfo
  liq.io.rowMutationNextLineData := mdbLookupWaitPlan.io.nextLineData
  liq.io.rowMutationNextValidMask := mdbLookupWaitPlan.io.nextValidMask
  liq.io.rowMutationNextDataComplete := mdbLookupWaitPlan.io.nextDataComplete
  liq.io.rowMutationNextScbReturned := mdbLookupWaitPlan.io.nextScbReturned
  liq.io.rowMutationNextStqReturned := mdbLookupWaitPlan.io.nextStqReturned
  liq.io.rowMutationNextStoreSourceReturned := mdbLookupWaitPlan.io.nextStoreSourceReturned

  when(io.flush) {
    clearResolvedPending := false.B
    clearResolvedIndex := 0.U
  }.otherwise {
    when(clearResolvedPending && liq.io.clearResolvedAccepted) {
      clearResolvedPending := false.B
    }
    when(resolveQueue.io.pushAccepted) {
      clearResolvedPending := true.B
      clearResolvedIndex := liq.io.lhqRecord.loadId.value
    }
  }

  io.storeInsertAccepted := stq.io.insertAccepted
  io.stqOccupiedMask := stq.io.occupiedMask
  io.stqAddrReadyMask := stq.io.addrReadyMask
  io.stqDataReadyMask := stq.io.dataReadyMask
  io.forwardWaitBlocked := forward.io.waitBlocked
  io.forwardReady := forward.io.readyForward
  io.forwardWaitStoreValid := forward.io.waitStore.valid
  io.forwardWaitStoreIndex := forward.io.waitStore.storeIndex
  io.waitSlotCaptureAccepted := waitSlot.io.captureAccepted
  io.waitSlotActive := waitSlot.io.active
  io.waitStoreClear := waitSlot.io.waitStoreClear
  io.wakeValid := wake.io.wakeValid
  io.wakeIdentityMatch := wake.io.identityMatch
  io.wakeSelectedRowReady := wake.io.selectedRowReady
  io.relaunchQueuePending := relaunchQueue.io.pending
  io.relaunchQueueOutValid := relaunchQueue.io.outValid
  io.relaunchQueueOutFire := relaunchQueue.io.outFire
  io.liqCandidateConsumeReady := liq.io.candidateConsumeReady
  io.liqAllocAccepted := liq.io.allocAccepted
  io.liqRefillAccepted := liq.io.refillAccepted
  io.liqRefillWakeMask := liq.io.refillWakeMask
  io.liqReplayWakeMergeMask := liq.io.replayWakeMergeMask
  io.liqReplayWakeCompletedMask := liq.io.replayWakeCompletedMask
  io.liqLaunchValid := liq.io.launchValid
  io.liqLaunchDriveValid := liq.io.launchDriveValid
  io.liqLaunchReady := liq.io.launchReady
  io.liqLaunchAccepted := liq.io.launchAccepted
  io.liqLaunchIndex := liq.io.launchIndex
  io.liqLaunchSelectedLoadLsId := liq.io.launchSelectedLoadLsId
  io.liqE4UpdateValid := liq.io.e4UpdateValid
  io.liqE4UpdateIndex := liq.io.e4UpdateIndex
  io.liqE4WakeupValid := liq.io.e4WakeupValid
  io.liqLhqRecordValid := liq.io.lhqRecordValid
  io.liqLhqRecordLoadLsId := liq.io.lhqRecord.loadLsId
  io.liqLhqRecordData := liq.io.lhqRecord.data
  io.resolveQueuePushAccepted := resolveQueue.io.pushAccepted
  io.resolveQueueRetireMask := resolveQueue.io.retireMask
  io.resolveQueueRetireCount := resolveQueue.io.retireCount
  io.resolveQueueValidMask := resolveQueue.io.validMask
  io.resolveQueueCount := resolveQueue.io.count
  io.resolveQueueFirstLoadLsId := resolveQueue.io.entries(0).record.loadLsId
  io.mdbResolveCandidateMask := mdbDetect.io.resolveCandidateMask
  io.mdbConflictValid := mdbDetect.io.conflictValid
  io.mdbConflictFromResolveQueue := mdbDetect.io.conflictFromResolveQueue
  io.mdbConflictResolveIndex := mdbDetect.io.conflictResolveIndex
  io.mdbInnerFlush := mdbDetect.io.innerFlush
  io.mdbNukeFlush := mdbDetect.io.nukeFlush
  io.mdbConflictLoadLsId := mdbDetect.io.record.load.lsId
  io.mdbConflictLoadPc := mdbDetect.io.record.load.pc
  io.mdbFanoutRecordReady := mdbFanout.io.recordInReady
  io.mdbFanoutRecordAccepted := mdbFanout.io.recordInAccepted
  io.mdbFanoutRecordProcessed := mdbFanout.io.recordProcessed
  io.mdbFanoutBmdbReportValid := mdbFanout.io.bmdbReportValid
  io.mdbFanoutBmdbLoadBid := mdbFanout.io.bmdbLoadBid
  io.mdbFanoutBmdbStoreBid := mdbFanout.io.bmdbStoreBid
  io.mdbFanoutBmdbStoreStid := mdbFanout.io.bmdbStoreStid
  io.mdbFanoutSsitValidMask := mdbFanout.io.ssitValidMask
  io.mdbFanoutLookupReady := mdbFanout.io.lookupInReady
  io.mdbFanoutLookupAccepted := mdbFanout.io.lookupInAccepted
  io.mdbFanoutLookupProcessed := mdbFanout.io.lookupProcessed
  io.mdbFanoutDeleteReady := mdbFanout.io.deleteInReady
  io.mdbFanoutDeleteAccepted := mdbFanout.io.deleteInAccepted
  io.mdbFanoutDeleteProcessed := mdbFanout.io.deleteProcessed
  io.mdbFanoutDeleteMatched := mdbFanout.io.deleteMatched
  io.mdbFanoutDeleteReleased := mdbFanout.io.deleteReleased
  io.mdbFanoutDeleteDroppedBelowStall := mdbFanout.io.deleteDroppedBelowStall
  io.mdbFanoutLuOutDequeued := mdbFanout.io.luOutDequeued
  io.mdbFanoutLuOutHit := mdbFanout.io.luOut.hit
  io.mdbFanoutSuOutDequeued := mdbFanout.io.suOutDequeued
  io.mdbFanoutSuOutHit := mdbFanout.io.suOut.hit
  io.mdbFanoutSuMatchedStore := mdbFanout.io.suMatchedStore
  io.mdbFanoutSuStorePending := mdbFanout.io.suStorePending
  io.mdbFanoutSuWakeupValid := mdbFanout.io.suWakeup.valid
  io.mdbFanoutSuWakeupStoreIndex := mdbFanout.io.suWakeup.storeIndex
  io.mdbFanoutSuWakeupBid := mdbFanout.io.suWakeup.bid
  io.mdbLookupWaitPlanLookupHit := mdbLookupWaitPlan.io.lookupHit
  io.mdbLookupWaitPlanCandidateMask := mdbLookupWaitPlan.io.candidateMask
  io.mdbLookupWaitPlanTargetIndex := mdbLookupWaitPlan.io.targetIndex
  io.mdbLookupWaitPlanWaitIntentValid := mdbLookupWaitPlan.io.waitIntentValid
  io.mdbLookupWaitPlanRequestValid := mdbLookupWaitPlan.io.requestValid
  io.mdbLookupWaitPlanBlockedByNoTarget := mdbLookupWaitPlan.io.blockedByNoTarget
  io.mdbLookupWaitPlanBlockedByMissingStoreIndex := mdbLookupWaitPlan.io.blockedByMissingStoreIndex
  io.mdbLookupWaitPlanBlockedByMissingStoreLsId := mdbLookupWaitPlan.io.blockedByMissingStoreLsId
  io.rowMutationBridgeValid := liq.io.rowMutationBridgeValid
  io.rowMutationTargetEvidenceValid := liq.io.rowMutationTargetEvidenceValid
  io.rowMutationWriteEnable := liq.io.rowMutationWriteEnable
  io.rowMutationApplyValid := liq.io.rowMutationApplyValid
  io.rowMutationBlockedByControl := liq.io.rowMutationBlockedByControl
  io.rowMutationControlBlockedByScbNotReturned := liq.io.rowMutationControlBlockedByScbNotReturned
  io.rowMutationControlBlockedByE4UpdateConflict := liq.io.rowMutationControlBlockedByE4UpdateConflict
  io.liqClearResolvedPending := clearResolvedPending
  io.liqClearResolvedAccepted := liq.io.clearResolvedAccepted
  io.liqResidentCount := liq.io.residentCount
  io.liqOccupiedMask := liq.io.occupiedMask
  io.liqWaitMask := liq.io.waitMask
  io.liqWaitStoreMask := liq.io.waitStoreMask
  io.liqRepickMask := liq.io.repickMask
  io.liqResolvedMask := liq.io.resolvedMask
  io.liqSourcesReturnedMask :=
    VecInit((0 until liqEntries).map(idx => liq.io.rows(idx).valid && liq.io.rows(idx).sourcesReturned)).asUInt
  io.liqScbReturnedMask :=
    VecInit((0 until liqEntries).map(idx => liq.io.rows(idx).valid && liq.io.rows(idx).scbReturned)).asUInt
  io.liqStqReturnedMask :=
    VecInit((0 until liqEntries).map(idx => liq.io.rows(idx).valid && liq.io.rows(idx).stqReturned)).asUInt
  io.liqFirstYoungestStoreLsId := liq.io.rows(0).youngestStoreLsId
}

object EmitReducedStoreWaitReplayChiselPathProbe extends App {
  circt.stage.ChiselStage.emitSystemVerilogFile(
    new ReducedStoreWaitReplayChiselPathProbe(entries = 8, liqEntries = 4),
    args = Array("--target-dir", "../generated/chisel-verilog/reduced-store-wait-replay-chisel-path"),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
}
