package linxcore.lsu

import chisel3._
import chisel3.util.log2Ceil

import linxcore.commit.{CommitOperandTrace, CommitTraceParams}
import linxcore.rob.ROBID

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
  val candidateValid = Input(Bool())
  val candidate = Input(new ReducedLoadReplayCandidate(idEntries, addrWidth, pcWidth, sizeWidth, archRegWidth, physRegWidth))

  val launchEnable = Input(Bool())
  val e2Stores = Input(Vec(storeEntries, new LoadStoreForwardStore(idEntries, storeEntries, addrWidth, pcWidth, lineBytes)))
  val e2BaseData = Input(UInt((lineBytes * 8).W))
  val e2BaseValidMask = Input(UInt(lineBytes.W))
  val e2LoadDataReturned = Input(Bool())
  val e2ScbReturned = Input(Bool())
  val e2ReturnReady = Input(Bool())

  val clearResolvedValid = Input(Bool())
  val clearResolvedIndex = Input(UInt(liqPtrWidth.W))

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

  adapter.io.flush := io.flush
  adapter.io.candidateValid := io.candidateValid
  adapter.io.candidate := io.candidate
  adapter.io.allocReady := liq.io.allocReady

  liq.io.flush := io.flush
  liq.io.allocValid := adapter.io.allocValid
  liq.io.alloc := adapter.io.alloc

  liq.io.launchValid := io.launchEnable && launchSelect.io.launchValid
  liq.io.launchIndex := launchSelect.io.launchIndex
  liq.io.e2Stores := io.e2Stores
  liq.io.e2BaseData := io.e2BaseData
  liq.io.e2BaseValidMask := io.e2BaseValidMask
  liq.io.e2LoadDataReturned := io.e2LoadDataReturned
  liq.io.e2ScbReturned := io.e2ScbReturned
  liq.io.e2ReturnReady := io.e2ReturnReady
  liq.io.replayWakeValid := false.B
  liq.io.replayWake := 0.U.asTypeOf(liq.io.replayWake)
  liq.io.refillValid := false.B
  liq.io.refill := 0.U.asTypeOf(liq.io.refill)
  liq.io.clearResolvedValid := io.clearResolvedValid
  liq.io.clearResolvedIndex := io.clearResolvedIndex
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

  launchSelect.io.enable := !io.flush
  launchSelect.io.rows := liq.io.rows

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
  io.repickMask := liq.io.repickMask
  io.missMask := liq.io.missMask
  io.resolvedMask := liq.io.resolvedMask
  io.e4UpdateValid := liq.io.e4UpdateValid
  io.e4UpdateIndex := liq.io.e4UpdateIndex
  io.e4MissKind := liq.io.e4MissKind
  io.e4WakeupValid := liq.io.e4WakeupValid
  io.lhqRecordValid := liq.io.lhqRecordValid
  io.lhqRecord := liq.io.lhqRecord
  io.residentCount := liq.io.residentCount
  io.empty := liq.io.empty
  io.full := liq.io.full
  io.missPending := liq.io.missPending
  io.clearResolvedAccepted := liq.io.clearResolvedAccepted
}
