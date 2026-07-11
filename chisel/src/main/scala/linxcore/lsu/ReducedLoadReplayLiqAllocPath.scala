package linxcore.lsu

import chisel3._
import chisel3.util.log2Ceil

import linxcore.commit.{CommitOperandTrace, CommitTraceParams}
import linxcore.rob.ROBID
import linxcore.recovery.FlushBus

class ReducedLoadReplayLiqAllocPathIO(
    val liqEntries: Int,
    val idEntries: Int,
    val storeEntries: Int,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val sizeWidth: Int = 7,
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6)
    extends Bundle {
  private val liqPtrWidth = log2Ceil(liqEntries)
  private val countWidth = log2Ceil(liqEntries + 1)
  private val sourceTraceParams =
    CommitTraceParams(regWidth = math.max(8, archRegWidth), dataWidth = addrWidth)

  val flush = Input(Bool())
  val preciseFlush = Input(new FlushBus(idEntries))
  val flushPruneMask = Output(UInt(liqEntries.W))
  val flushPruneCount = Output(UInt(countWidth.W))
  val candidateValid = Input(Bool())
  val candidate = Input(new ReducedLoadReplayCandidate(idEntries, addrWidth, pcWidth, sizeWidth, archRegWidth, physRegWidth))

  val launchEnable = Input(Bool())
  val pickValid = Input(Bool())
  val pickIndex = Input(UInt(liqPtrWidth.W))
  val scbReturnValid = Input(Bool())
  val scbReturnIndex = Input(UInt(liqPtrWidth.W))
  val markResolvedValid = Input(Bool())
  val markResolvedIndex = Input(UInt(liqPtrWidth.W))
  val e2Stores = Input(Vec(storeEntries, new LoadStoreForwardStore(idEntries, storeEntries, addrWidth, pcWidth, lineBytes)))
  val e2BaseData = Input(UInt((lineBytes * 8).W))
  val e2BaseValidMask = Input(UInt(lineBytes.W))
  val e2LoadDataReturned = Input(Bool())
  val e2ScbReturned = Input(Bool())
  val e2StqReturned = Input(Bool())
  val e2ReturnReady = Input(Bool())
  val replayWakeValid = Input(Bool())
  val replayWake = Input(new LoadReplayWakeupRequest(idEntries, addrWidth, pcWidth, lineBytes))
  val replayWakeWaitStoreCandidateMask = Output(UInt(liqEntries.W))
  val replayWakeBidMatchMask = Output(UInt(liqEntries.W))
  val replayWakeLsIdMatchMask = Output(UInt(liqEntries.W))
  val replayWakePcMatchMask = Output(UInt(liqEntries.W))
  val replayWakeFullMatchMask = Output(UInt(liqEntries.W))
  val replayWakeStoreUnit = Output(Bool())
  val replayWakeStoreUnitFullMatchMask = Output(UInt(liqEntries.W))
  val replayWakeWaitStoreClearMask = Output(UInt(liqEntries.W))
  val replayWakeMergeMask = Output(UInt(liqEntries.W))
  val replayWakeCompletedMask = Output(UInt(liqEntries.W))

  val clearResolvedValid = Input(Bool())
  val clearResolvedIndex = Input(UInt(liqPtrWidth.W))

  val rowMutationRequestValid = Input(Bool())
  val rowMutationRequestTargetMask = Input(UInt(liqEntries.W))
  val rowMutationRequestTargetIndex = Input(UInt(liqPtrWidth.W))
  val rowMutationSetWaitStatus = Input(Bool())
  val rowMutationKeepRepickStatus = Input(Bool())
  val rowMutationClearReturnState = Input(Bool())
  val rowMutationLineWrite = Input(Bool())
  val rowMutationWaitStoreWrite = Input(Bool())
  val rowMutationNextWaitStore = Input(Bool())
  val rowMutationNextWaitStoreInfo = Input(new LoadStoreForwardWait(idEntries, idEntries, pcWidth))
  val rowMutationNextLineData = Input(UInt((lineBytes * 8).W))
  val rowMutationNextValidMask = Input(UInt(lineBytes.W))
  val rowMutationNextDataComplete = Input(Bool())
  val rowMutationNextScbReturned = Input(Bool())
  val rowMutationNextStqReturned = Input(Bool())
  val rowMutationNextStoreSourceReturned = Input(Bool())

  val refillValid = Input(Bool())
  val refill = Input(new LoadRefillWakeupRequest(addrWidth, lineBytes))
  val refillAccepted = Output(Bool())
  val refillWakeMask = Output(UInt(liqEntries.W))

  val rowMutationBridgeValid = Output(Bool())
  val rowMutationSourceStoreIndexFits = Output(Bool())
  val rowMutationInvalidStoreIndexOutOfRange = Output(Bool())
  val rowMutationInvalidConflictingStatusWrite = Output(Bool())
  val rowMutationInvalidWaitStoreWithoutWaitStatus = Output(Bool())
  val rowMutationInvalidReturnWithoutSplitSources = Output(Bool())
  val rowMutationWriteEnable = Output(Bool())
  val rowMutationApplyValid = Output(Bool())
  val rowMutationTargetEvidenceValid = Output(Bool())
  val rowMutationWriteConflict = Output(Bool())
  val rowMutationBlockedByBridge = Output(Bool())
  val rowMutationBlockedByControl = Output(Bool())
  val rowMutationControlBlockedByInvalidRow = Output(Bool())
  val rowMutationControlBlockedByNotRepick = Output(Bool())
  val rowMutationControlBlockedByScbNotReturned = Output(Bool())
  val rowMutationControlBlockedByE4UpdateConflict = Output(Bool())
  val rowMutationControlBlockedByClearResolvedConflict = Output(Bool())
  val rowMutationControlBlockedByReplayWakeConflict = Output(Bool())
  val rowMutationControlBlockedByRefillConflict = Output(Bool())
  val rowMutationControlBlockedByLaunchConflict = Output(Bool())
  val rowMutationControlBlockedByAllocationConflict = Output(Bool())

  val candidateConsumeReady = Output(Bool())
  val candidateUsable = Output(Bool())
  val candidateBlockedByAlloc = Output(Bool())

  val allocValid = Output(Bool())
  val allocReady = Output(Bool())
  val allocAccepted = Output(Bool())
  val allocIndex = Output(UInt(liqPtrWidth.W))
  val allocLoadId = Output(new ROBID(liqEntries))

  val rows = Output(Vec(
    liqEntries,
    new LoadInflightRow(
      liqEntries,
      idEntries,
      storeEntries,
      addrWidth,
      pcWidth,
      lineBytes,
      sizeWidth,
      archRegWidth,
      physRegWidth
    )
  ))
  val occupiedMask = Output(UInt(liqEntries.W))
  val waitMask = Output(UInt(liqEntries.W))
  val waitStoreMask = Output(UInt(liqEntries.W))
  val launchWaitMask = Output(UInt(liqEntries.W))
  val launchWaitStoreBlockedMask = Output(UInt(liqEntries.W))
  val launchTileBlockedMask = Output(UInt(liqEntries.W))
  val launchUnblockedWaitMask = Output(UInt(liqEntries.W))
  val launchRequestCompleteMask = Output(UInt(liqEntries.W))
  val launchDataHitMask = Output(UInt(liqEntries.W))
  val launchCandidateMask = Output(UInt(liqEntries.W))
  val launchMask = Output(UInt(liqEntries.W))
  val launchValid = Output(Bool())
  val launchIndex = Output(UInt(liqPtrWidth.W))
  val launchCandidateCount = Output(UInt(countWidth.W))
  val launchSelectedLoadId = Output(new ROBID(liqEntries))
  val launchSelectedBid = Output(new ROBID(idEntries))
  val launchSelectedGid = Output(new ROBID(idEntries))
  val launchSelectedRid = Output(new ROBID(idEntries))
  val launchSelectedLoadLsId = Output(new ROBID(idEntries))
  val launchSelectedPc = Output(UInt(pcWidth.W))
  val launchSelectedAddr = Output(UInt(addrWidth.W))
  val launchSelectedSize = Output(UInt(sizeWidth.W))
  val launchSelectedReturnSignExtend = Output(Bool())
  val launchSelectedDst = Output(new LoadReplayDestination(archRegWidth, physRegWidth))
  val launchSelectedSourceTraceValid = Output(Bool())
  val launchSelectedSource0 = Output(new CommitOperandTrace(sourceTraceParams))
  val launchSelectedSource1 = Output(new CommitOperandTrace(sourceTraceParams))
  val launchSelectedRequestByteMask = Output(UInt(lineBytes.W))
  val launchSelectedSpecWakeup = Output(Bool())
  val launchSelectedStackValid = Output(Bool())
  val launchDriveValid = Output(Bool())
  val launchReady = Output(Bool())
  val launchAccepted = Output(Bool())
  val pickReady = Output(Bool())
  val pickAccepted = Output(Bool())
  val scbReturnReady = Output(Bool())
  val scbReturnAccepted = Output(Bool())
  val markResolvedReady = Output(Bool())
  val markResolvedAccepted = Output(Bool())
  val returnCompleteRepickMask = Output(UInt(liqEntries.W))
  val returnCompleteSourceReturnedMask = Output(UInt(liqEntries.W))
  val returnCompleteDataCompleteMask = Output(UInt(liqEntries.W))
  val returnCompleteRequestCompleteMask = Output(UInt(liqEntries.W))
  val returnCompleteCandidateMask = Output(UInt(liqEntries.W))
  val returnCompleteMask = Output(UInt(liqEntries.W))
  val returnCompleteValid = Output(Bool())
  val returnCompleteIndex = Output(UInt(liqPtrWidth.W))
  val returnCompleteCandidateCount = Output(UInt(countWidth.W))
  val returnCompleteSelectedLoadId = Output(new ROBID(liqEntries))
  val returnCompleteSelectedBid = Output(new ROBID(idEntries))
  val returnCompleteSelectedGid = Output(new ROBID(idEntries))
  val returnCompleteSelectedRid = Output(new ROBID(idEntries))
  val returnCompleteSelectedLoadLsId = Output(new ROBID(idEntries))
  val returnCompleteSelectedPc = Output(UInt(pcWidth.W))
  val returnCompleteSelectedAddr = Output(UInt(addrWidth.W))
  val returnCompleteSelectedSize = Output(UInt(sizeWidth.W))
  val returnCompleteSelectedReturnSignExtend = Output(Bool())
  val returnCompleteSelectedDst = Output(new LoadReplayDestination(archRegWidth, physRegWidth))
  val returnCompleteSelectedSourceTraceValid = Output(Bool())
  val returnCompleteSelectedSource0 = Output(new CommitOperandTrace(sourceTraceParams))
  val returnCompleteSelectedSource1 = Output(new CommitOperandTrace(sourceTraceParams))
  val returnCompleteSelectedRequestByteMask = Output(UInt(lineBytes.W))
  val returnCompleteSelectedLineData = Output(UInt((lineBytes * 8).W))
  val returnCompleteSelectedValidMask = Output(UInt(lineBytes.W))
  val returnCompleteSelectedSpecWakeup = Output(Bool())
  val returnCompleteSelectedStackValid = Output(Bool())
  val repickMask = Output(UInt(liqEntries.W))
  val missMask = Output(UInt(liqEntries.W))
  val resolvedMask = Output(UInt(liqEntries.W))
  val e4UpdateValid = Output(Bool())
  val e4UpdateIndex = Output(UInt(liqPtrWidth.W))
  val e4MissKind = Output(LoadForwardMissKind())
  val e4WakeupValid = Output(Bool())
  val lhqRecordValid = Output(Bool())
  val lhqRecord = Output(new LoadHitRecord(liqEntries, idEntries, addrWidth, lineBytes, sizeWidth))
  val residentCount = Output(UInt(countWidth.W))
  val empty = Output(Bool())
  val full = Output(Bool())
  val missPending = Output(Bool())
  val clearResolvedAccepted = Output(Bool())
}

class ReducedLoadReplayLiqAllocPath(
    val liqEntries: Int = 4,
    val idEntries: Int = 16,
    val storeEntries: Int = 16,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val sizeWidth: Int = 7,
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6)
    extends Module {
  require(liqEntries > 1, "LIQ entries must be greater than one")
  require((liqEntries & (liqEntries - 1)) == 0, "LIQ entries must be a power of two")
  require(idEntries > 1, "ID entries must be greater than one")
  require((idEntries & (idEntries - 1)) == 0, "ID entries must be a power of two")
  require(storeEntries > 1, "storeEntries must be greater than one")
  require((storeEntries & (storeEntries - 1)) == 0, "storeEntries must be a power of two")
  require(lineBytes == 64, "ReducedLoadReplayLiqAllocPath currently models 64-byte scalar cachelines")
  require(sizeWidth >= 7, "sizeWidth must cover 64-byte scalar lines")

  private val liqPtrWidth = log2Ceil(liqEntries)

  val io = IO(new ReducedLoadReplayLiqAllocPathIO(
    liqEntries,
    idEntries,
    storeEntries,
    addrWidth,
    pcWidth,
    lineBytes,
    sizeWidth,
    archRegWidth,
    physRegWidth
  ))

  val adapter = Module(new ReducedLoadReplayLiqAllocAdapter(
    liqEntries,
    idEntries,
    addrWidth,
    pcWidth,
    sizeWidth,
    archRegWidth,
    physRegWidth
  ))
  val liq = Module(new LoadInflightQueue(
    liqEntries,
    idEntries,
    storeEntries,
    addrWidth,
    pcWidth,
    lineBytes,
    sizeWidth,
    archRegWidth,
    physRegWidth
  ))
  val launchSelect = Module(new LoadInflightLaunchSelect(
    liqEntries,
    idEntries,
    storeEntries,
    addrWidth,
    pcWidth,
    lineBytes,
    sizeWidth,
    archRegWidth,
    physRegWidth
  ))
  val completeRepickSelect = Module(new LoadReplayReturnCompleteRepickSelect(
    liqEntries,
    idEntries,
    storeEntries,
    addrWidth,
    pcWidth,
    lineBytes,
    sizeWidth,
    archRegWidth,
    physRegWidth
  ))
  val rowMutationBridge = Module(new LoadInflightRowMutationRequestBridge(
    liqEntries = liqEntries,
    idEntries = idEntries,
    sourceStoreEntries = idEntries,
    storeEntries = storeEntries,
    pcWidth = pcWidth,
    lineBytes = lineBytes
  ))

  adapter.io.flush := io.flush
  adapter.io.candidateValid := io.candidateValid
  adapter.io.candidate := io.candidate
  adapter.io.allocReady := liq.io.allocReady

  liq.io.flush := io.flush
  liq.io.preciseFlush := io.preciseFlush
  liq.io.allocValid := adapter.io.allocValid
  liq.io.alloc := adapter.io.alloc

  liq.io.launchValid := io.launchEnable && launchSelect.io.launchValid
  liq.io.launchIndex := launchSelect.io.launchIndex
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
  liq.io.clearResolvedValid := io.clearResolvedValid
  liq.io.clearResolvedIndex := io.clearResolvedIndex

  rowMutationBridge.io.enable := !io.flush
  rowMutationBridge.io.flush := io.flush
  rowMutationBridge.io.requestValid := io.rowMutationRequestValid
  rowMutationBridge.io.requestTargetMask := io.rowMutationRequestTargetMask
  rowMutationBridge.io.requestTargetIndex := io.rowMutationRequestTargetIndex
  rowMutationBridge.io.setWaitStatus := io.rowMutationSetWaitStatus
  rowMutationBridge.io.keepRepickStatus := io.rowMutationKeepRepickStatus
  rowMutationBridge.io.clearReturnState := io.rowMutationClearReturnState
  rowMutationBridge.io.lineWrite := io.rowMutationLineWrite
  rowMutationBridge.io.waitStoreWrite := io.rowMutationWaitStoreWrite
  rowMutationBridge.io.nextWaitStore := io.rowMutationNextWaitStore
  rowMutationBridge.io.nextWaitStoreInfo := io.rowMutationNextWaitStoreInfo
  rowMutationBridge.io.nextLineData := io.rowMutationNextLineData
  rowMutationBridge.io.nextValidMask := io.rowMutationNextValidMask
  rowMutationBridge.io.nextDataComplete := io.rowMutationNextDataComplete
  rowMutationBridge.io.nextScbReturned := io.rowMutationNextScbReturned
  rowMutationBridge.io.nextStqReturned := io.rowMutationNextStqReturned
  rowMutationBridge.io.nextStoreSourceReturned := io.rowMutationNextStoreSourceReturned

  liq.io.rowMutationValid := rowMutationBridge.io.bridgeValid
  liq.io.rowMutationTargetIndex := rowMutationBridge.io.requestTargetIndexOut
  liq.io.rowMutationSetWaitStatus := rowMutationBridge.io.setWaitStatusOut
  liq.io.rowMutationKeepRepickStatus := rowMutationBridge.io.keepRepickStatusOut
  liq.io.rowMutationClearReturnState := rowMutationBridge.io.clearReturnStateOut
  liq.io.rowMutationLineWrite := rowMutationBridge.io.lineWriteOut
  liq.io.rowMutationWaitStoreWrite := rowMutationBridge.io.waitStoreWriteOut
  liq.io.rowMutationNextWaitStore := rowMutationBridge.io.nextWaitStoreOut
  liq.io.rowMutationNextWaitStoreInfo := rowMutationBridge.io.nativeNextWaitStoreInfoOut
  liq.io.rowMutationNextLineData := rowMutationBridge.io.nextLineDataOut
  liq.io.rowMutationNextValidMask := rowMutationBridge.io.nextValidMaskOut
  liq.io.rowMutationNextDataComplete := rowMutationBridge.io.nextDataCompleteOut
  liq.io.rowMutationNextScbReturned := rowMutationBridge.io.nextScbReturnedOut
  liq.io.rowMutationNextStqReturned := rowMutationBridge.io.nextStqReturnedOut
  liq.io.rowMutationNextStoreSourceReturned := rowMutationBridge.io.nextStoreSourceReturnedOut
  liq.io.rowMutationAllowWaitTarget := false.B
  liq.io.rowMutationRequireScbReturned := true.B

  launchSelect.io.enable := !io.flush
  launchSelect.io.rows := liq.io.rows
  completeRepickSelect.io.enable := !io.flush
  completeRepickSelect.io.rows := liq.io.rows

  val replayWakeDiagnostics =
    Module(new LoadReplayWakeMatchDiagnostics(liqEntries, idEntries, storeEntries, addrWidth, pcWidth, lineBytes, sizeWidth, archRegWidth, physRegWidth))
  replayWakeDiagnostics.io.wakeValid := io.replayWakeValid
  replayWakeDiagnostics.io.wake := io.replayWake
  replayWakeDiagnostics.io.rows := liq.io.rows

  io.candidateConsumeReady := adapter.io.consumeReady
  io.candidateUsable := adapter.io.candidateUsable
  io.candidateBlockedByAlloc := adapter.io.blockedByAlloc
  io.allocValid := adapter.io.allocValid
  io.allocReady := liq.io.allocReady
  io.allocAccepted := liq.io.allocAccepted
  io.allocIndex := liq.io.allocIndex
  io.allocLoadId := liq.io.allocLoadId
  io.rows := liq.io.rows
  io.occupiedMask := liq.io.occupiedMask
  io.waitMask := liq.io.waitMask
  io.waitStoreMask := liq.io.waitStoreMask
  io.launchWaitMask := launchSelect.io.waitMask
  io.launchWaitStoreBlockedMask := launchSelect.io.waitStoreBlockedMask
  io.launchTileBlockedMask := launchSelect.io.tileBlockedMask
  io.launchUnblockedWaitMask := launchSelect.io.unblockedWaitMask
  io.launchRequestCompleteMask := launchSelect.io.requestCompleteMask
  io.launchDataHitMask := launchSelect.io.dataHitMask
  io.launchCandidateMask := launchSelect.io.launchCandidateMask
  io.launchMask := launchSelect.io.launchMask
  io.launchValid := launchSelect.io.launchValid
  io.launchIndex := launchSelect.io.launchIndex
  io.launchCandidateCount := launchSelect.io.candidateCount
  io.launchSelectedLoadId := launchSelect.io.selectedLoadId
  io.launchSelectedBid := launchSelect.io.selectedBid
  io.launchSelectedGid := launchSelect.io.selectedGid
  io.launchSelectedRid := launchSelect.io.selectedRid
  io.launchSelectedLoadLsId := launchSelect.io.selectedLoadLsId
  io.launchSelectedPc := launchSelect.io.selectedPc
  io.launchSelectedAddr := launchSelect.io.selectedAddr
  io.launchSelectedSize := launchSelect.io.selectedSize
  io.launchSelectedReturnSignExtend := launchSelect.io.selectedReturnSignExtend
  io.launchSelectedDst := launchSelect.io.selectedDst
  io.launchSelectedSourceTraceValid := launchSelect.io.selectedSourceTraceValid
  io.launchSelectedSource0 := launchSelect.io.selectedSource0
  io.launchSelectedSource1 := launchSelect.io.selectedSource1
  io.launchSelectedRequestByteMask := launchSelect.io.selectedRequestByteMask
  io.launchSelectedSpecWakeup := launchSelect.io.selectedSpecWakeup
  io.launchSelectedStackValid := launchSelect.io.selectedStackValid
  io.launchDriveValid := io.launchEnable && launchSelect.io.launchValid
  io.launchReady := launchSelect.io.launchValid && liq.io.launchReady
  io.launchAccepted := liq.io.launchAccepted
  io.pickReady := liq.io.pickReady
  io.pickAccepted := liq.io.pickAccepted
  io.scbReturnReady := liq.io.scbReturnReady
  io.scbReturnAccepted := liq.io.scbReturnAccepted
  io.markResolvedReady := liq.io.markResolvedReady
  io.markResolvedAccepted := liq.io.markResolvedAccepted
  io.returnCompleteRepickMask := completeRepickSelect.io.repickMask
  io.returnCompleteSourceReturnedMask := completeRepickSelect.io.sourceReturnedMask
  io.returnCompleteDataCompleteMask := completeRepickSelect.io.dataCompleteMask
  io.returnCompleteRequestCompleteMask := completeRepickSelect.io.requestCompleteMask
  io.returnCompleteCandidateMask := completeRepickSelect.io.returnCandidateMask
  io.returnCompleteMask := completeRepickSelect.io.returnMask
  io.returnCompleteValid := completeRepickSelect.io.returnValid
  io.returnCompleteIndex := completeRepickSelect.io.returnIndex
  io.returnCompleteCandidateCount := completeRepickSelect.io.candidateCount
  io.returnCompleteSelectedLoadId := completeRepickSelect.io.selectedLoadId
  io.returnCompleteSelectedBid := completeRepickSelect.io.selectedBid
  io.returnCompleteSelectedGid := completeRepickSelect.io.selectedGid
  io.returnCompleteSelectedRid := completeRepickSelect.io.selectedRid
  io.returnCompleteSelectedLoadLsId := completeRepickSelect.io.selectedLoadLsId
  io.returnCompleteSelectedPc := completeRepickSelect.io.selectedPc
  io.returnCompleteSelectedAddr := completeRepickSelect.io.selectedAddr
  io.returnCompleteSelectedSize := completeRepickSelect.io.selectedSize
  io.returnCompleteSelectedReturnSignExtend := completeRepickSelect.io.selectedReturnSignExtend
  io.returnCompleteSelectedDst := completeRepickSelect.io.selectedDst
  io.returnCompleteSelectedSourceTraceValid := completeRepickSelect.io.selectedSourceTraceValid
  io.returnCompleteSelectedSource0 := completeRepickSelect.io.selectedSource0
  io.returnCompleteSelectedSource1 := completeRepickSelect.io.selectedSource1
  io.returnCompleteSelectedRequestByteMask := completeRepickSelect.io.selectedRequestByteMask
  io.returnCompleteSelectedLineData := completeRepickSelect.io.selectedLineData
  io.returnCompleteSelectedValidMask := completeRepickSelect.io.selectedValidMask
  io.returnCompleteSelectedSpecWakeup := completeRepickSelect.io.selectedSpecWakeup
  io.returnCompleteSelectedStackValid := completeRepickSelect.io.selectedStackValid
  io.repickMask := liq.io.repickMask
  io.missMask := liq.io.missMask
  io.resolvedMask := liq.io.resolvedMask
  io.e4UpdateValid := liq.io.e4UpdateValid
  io.e4UpdateIndex := liq.io.e4UpdateIndex
  io.e4MissKind := liq.io.e4MissKind
  io.e4WakeupValid := liq.io.e4WakeupValid
  io.replayWakeWaitStoreCandidateMask := replayWakeDiagnostics.io.waitStoreCandidateMask
  io.replayWakeBidMatchMask := replayWakeDiagnostics.io.bidMatchMask
  io.replayWakeLsIdMatchMask := replayWakeDiagnostics.io.lsIdMatchMask
  io.replayWakePcMatchMask := replayWakeDiagnostics.io.pcMatchMask
  io.replayWakeFullMatchMask := replayWakeDiagnostics.io.fullMatchMask
  io.replayWakeStoreUnit := replayWakeDiagnostics.io.storeUnit
  io.replayWakeStoreUnitFullMatchMask := replayWakeDiagnostics.io.storeUnitFullMatchMask
  io.replayWakeWaitStoreClearMask := liq.io.replayWakeWaitStoreClearMask
  io.replayWakeMergeMask := liq.io.replayWakeMergeMask
  io.replayWakeCompletedMask := liq.io.replayWakeCompletedMask
  io.refillAccepted := liq.io.refillAccepted
  io.refillWakeMask := liq.io.refillWakeMask
  io.lhqRecordValid := liq.io.lhqRecordValid
  io.lhqRecord := liq.io.lhqRecord
  io.residentCount := liq.io.residentCount
  io.empty := liq.io.empty
  io.full := liq.io.full
  io.missPending := liq.io.missPending
  io.clearResolvedAccepted := liq.io.clearResolvedAccepted
  io.flushPruneMask := liq.io.flushPruneMask
  io.flushPruneCount := liq.io.flushPruneCount
  io.rowMutationBridgeValid := rowMutationBridge.io.bridgeValid
  io.rowMutationSourceStoreIndexFits := rowMutationBridge.io.sourceStoreIndexFits
  io.rowMutationInvalidStoreIndexOutOfRange := rowMutationBridge.io.invalidStoreIndexOutOfRange
  io.rowMutationInvalidConflictingStatusWrite := rowMutationBridge.io.invalidConflictingStatusWrite
  io.rowMutationInvalidWaitStoreWithoutWaitStatus := rowMutationBridge.io.invalidWaitStoreWithoutWaitStatus
  io.rowMutationInvalidReturnWithoutSplitSources := rowMutationBridge.io.invalidReturnWithoutSplitSources
  io.rowMutationWriteEnable := liq.io.rowMutationWriteEnable
  io.rowMutationApplyValid := liq.io.rowMutationApplyValid
  io.rowMutationTargetEvidenceValid := liq.io.rowMutationTargetEvidenceValid
  io.rowMutationWriteConflict := liq.io.rowMutationWriteConflict
  io.rowMutationBlockedByBridge := io.rowMutationRequestValid && !rowMutationBridge.io.bridgeValid
  io.rowMutationBlockedByControl := liq.io.rowMutationBlockedByControl
  io.rowMutationControlBlockedByInvalidRow := liq.io.rowMutationControlBlockedByInvalidRow
  io.rowMutationControlBlockedByNotRepick := liq.io.rowMutationControlBlockedByNotRepick
  io.rowMutationControlBlockedByScbNotReturned := liq.io.rowMutationControlBlockedByScbNotReturned
  io.rowMutationControlBlockedByE4UpdateConflict := liq.io.rowMutationControlBlockedByE4UpdateConflict
  io.rowMutationControlBlockedByClearResolvedConflict := liq.io.rowMutationControlBlockedByClearResolvedConflict
  io.rowMutationControlBlockedByReplayWakeConflict := liq.io.rowMutationControlBlockedByReplayWakeConflict
  io.rowMutationControlBlockedByRefillConflict := liq.io.rowMutationControlBlockedByRefillConflict
  io.rowMutationControlBlockedByLaunchConflict := liq.io.rowMutationControlBlockedByLaunchConflict
  io.rowMutationControlBlockedByAllocationConflict := liq.io.rowMutationControlBlockedByAllocationConflict
}
