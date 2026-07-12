package linxcore.lsu

import chisel3._
import chisel3.util.{Cat, Mux1H, RRArbiter, UIntToOH, log2Ceil}

import linxcore.common.{CoreParams, ScalarLsuParams}
import linxcore.recovery.FlushBus
import linxcore.rob.ROBID

class ScalarLSULoadReturnPortIO(val coreParams: CoreParams, val p: ScalarLsuParams) extends Bundle {
  private val pipeWidth = math.max(1, log2Ceil(p.loadReturnPipeCount))
  private val laneCount = p.stidCount * p.loadReturnPipeCount
  private val laneWidth = math.max(1, log2Ceil(laneCount))
  private val laneCountWidth = log2Ceil(p.loadReturnQueueEntries + 1)
  private val totalCountWidth = log2Ceil(laneCount * p.loadReturnQueueEntries + 1)
  private val pipeCountWidth = log2Ceil(p.loadReturnPipeCount + 1)
  private def scopedEntry = new ScalarLSULoadReturnEntry(
    coreParams.robEntries,
    p.addrWidth,
    p.pcWidth,
    p.dataWidth,
    p.loadSizeWidth,
    p.loadReturnPipeCount,
    p.archRegWidth,
    p.physRegWidth,
    p.peIdWidth,
    p.stidWidth,
    p.tidWidth
  )

  val drainValid = Output(Bool())
  val drain = Output(new LoadReplayReturnLretEntry(
    coreParams.robEntries,
    p.addrWidth,
    p.pcWidth,
    p.dataWidth,
    p.loadSizeWidth,
    p.loadReturnPipeCount,
    p.archRegWidth,
    p.physRegWidth
  ))
  val drainPeId = Output(UInt(p.peIdWidth.W))
  val drainStid = Output(UInt(p.stidWidth.W))
  val drainTid = Output(UInt(p.tidWidth.W))
  val drainPipeIndex = Output(UInt(pipeWidth.W))
  val drainLane = Output(UInt(laneWidth.W))
  val drainFire = Output(Bool())
  val robLookupValid = Output(Bool())
  val robLookupPeId = Output(UInt(p.peIdWidth.W))
  val robLookupStid = Output(UInt(p.stidWidth.W))
  val robLookupTid = Output(UInt(p.tidWidth.W))
  val robLookupBid = Output(new ROBID(coreParams.robEntries))
  val robLookupGid = Output(new ROBID(coreParams.robEntries))
  val robLookupRid = Output(new ROBID(coreParams.robEntries))
  val robLookupLoadLsId = Output(new ROBID(coreParams.robEntries))
  val robRowValid = Input(Bool())
  val robRowNeedFlush = Input(Bool())
  val resolveReady = Input(Bool())
  val writebackReady = Input(Bool())
  val wakeupReady = Input(Bool())
  val completionCandidateValid = Output(Bool())
  val completionSelectedPipe = Output(UInt(pipeWidth.W))
  val resolveFire = Output(Bool())
  val writebackFire = Output(Bool())
  val wakeupFire = Output(Bool())
  val completion = Output(scopedEntry)
  val w1ValidMask = Output(UInt(p.loadReturnPipeCount.W))
  val w2ValidMask = Output(UInt(p.loadReturnPipeCount.W))
  val w1PrecisePruneMask = Output(UInt(p.loadReturnPipeCount.W))
  val w2PrecisePruneMask = Output(UInt(p.loadReturnPipeCount.W))
  val completionMask = Output(UInt(p.loadReturnPipeCount.W))
  val w1Count = Output(UInt(pipeCountWidth.W))
  val w2Count = Output(UInt(pipeCountWidth.W))
  val pipelineEmpty = Output(Bool())
  val pending = Output(Bool())
  val full = Output(Bool())
  val empty = Output(Bool())
  val laneCounts = Output(Vec(laneCount, UInt(laneCountWidth.W)))
  val totalCount = Output(UInt(totalCountWidth.W))
  val reservedCount = Output(UInt(totalCountWidth.W))
  val precisePruneCount = Output(UInt(totalCountWidth.W))
  val publicationValid = Output(Bool())
  val publicationAccepted = Output(Bool())
  val protocolError = Output(Bool())
}

class ScalarLSULoadPathIO(val coreParams: CoreParams, val lsuParams: ScalarLsuParams) extends Bundle {
  private val liqPtrWidth = log2Ceil(lsuParams.liqEntries)
  private val liqCountWidth = log2Ceil(lsuParams.liqEntries + 1)
  private val resolveCountWidth = log2Ceil(lsuParams.resolveQueueEntries + 1)

  val flush = Input(Bool())
  val preciseFlush = Input(new FlushBus(
    coreParams.robEntries,
    lsuParams.peIdWidth,
    lsuParams.stidWidth,
    lsuParams.tidWidth,
    coreParams.lsidWidth
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
    lsuParams.tidWidth,
    lsuParams.loadReturnPipeCount
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
  val launchBlockedByReturnCredit = Output(Bool())

  val pickValid = Input(Bool())
  val pickIndex = Input(UInt(liqPtrWidth.W))
  val pickReady = Output(Bool())
  val pickAccepted = Output(Bool())

  val scbReturnValid = Input(Bool())
  val scbReturnIndex = Input(UInt(liqPtrWidth.W))
  val scbReturnReady = Output(Bool())
  val scbReturnAccepted = Output(Bool())

  val loadReturn = new ScalarLSULoadReturnPortIO(coreParams, lsuParams)

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
      lsuParams.tidWidth,
      lsuParams.loadReturnPipeCount
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
  val mdbConflictValid = Output(Bool())
  val mdbConflictFromResolveQueue = Output(Bool())
  val mdbConflictActiveMask = Output(UInt(lsuParams.liqEntries.W))
  val mdbConflictResolveMask = Output(UInt(lsuParams.resolveQueueEntries.W))
  val mdbConflictWaitStoreMask = Output(UInt(lsuParams.liqEntries.W))
  val mdbConflictFlush = Output(new FlushBus(
    coreParams.robEntries,
    lsuParams.peIdWidth,
    lsuParams.stidWidth,
    lsuParams.tidWidth,
    coreParams.lsidWidth
  ))
  val mdbRecordAccepted = Output(Bool())
  val mdbRecordProcessed = Output(Bool())
  val mdbBmdbReportValid = Output(Bool())
  val mdbLookupAccepted = Output(Bool())
  val mdbLookupProcessed = Output(Bool())
  val mdbLookupHit = Output(Bool())
  val mdbLookupWaitMutation = Output(Bool())
  val mdbFailedWaitActiveMask = Output(UInt(lsuParams.liqEntries.W))
  val mdbFailedWaitExpiredMask = Output(UInt(lsuParams.liqEntries.W))
  val mdbFailedWaitReleaseValid = Output(Bool())
  val mdbFailedWaitReleaseIndex = Output(UInt(log2Ceil(lsuParams.liqEntries).W))
  val mdbFailedWaitReleaseAccepted = Output(Bool())
  val mdbDeleteAccepted = Output(Bool())
  val mdbDeleteProcessed = Output(Bool())
  val mdbDeleteMatched = Output(Bool())
  val mdbDeleteDroppedBelowStall = Output(Bool())
  val mdbDeleteReleased = Output(Bool())
  val mdbWaitPlanPending = Output(Bool())
  val mdbWaitPlanTargetMask = Output(UInt(lsuParams.liqEntries.W))
  val mdbSsitValidMask = Output(UInt(lsuParams.mdbSsitEntries.W))
  val mdbStoreWakeupValid = Output(Bool())
  val mdbReplayWakeCollision = Output(Bool())
  val mdbTransientEmpty = Output(Bool())
  val mdbProtocolError = Output(Bool())
  val empty = Output(Bool())
}

class ScalarLSULoadPathRecoveryIO(val coreParams: CoreParams, val p: ScalarLsuParams) extends Bundle {
  val ready = Input(Bool())
  val valid = Output(Bool())
  val flush = Output(new FlushBus(
    coreParams.robEntries,
    p.peIdWidth,
    p.stidWidth,
    p.tidWidth,
    coreParams.lsidWidth))
  val accepted = Output(Bool())
  val pending = Output(Bool())
}

class ScalarLSULoadPathStoreIO(val coreParams: CoreParams, val p: ScalarLsuParams) extends Bundle {
  val probe = Input(new MDBConflictStoreProbe(
    coreParams.robEntries,
    p.addrWidth,
    p.pcWidth,
    p.peIdWidth,
    p.stidWidth,
    p.tidWidth,
    p.loadSizeWidth
  ))
  val probeCommit = Input(Bool())
  val probeReady = Output(Bool())
  val rows = Input(Vec(
    p.stqEntries,
    new MDBStoreWakeupEntry(
      coreParams.robEntries,
      p.stqEntries,
      p.addrWidth,
      p.pcWidth,
      p.stidWidth,
      p.loadSizeWidth
    )
  ))
}

class ScalarLSULoadPath(val coreParams: CoreParams = CoreParams()) extends Module {
  private val p = coreParams.scalarLsu
  require(p.resolveQueueEntries >= 4,
    "resolveQueueEntries must reserve two pipeline arrivals plus one resident row")
  require(p.liqEntries <= coreParams.robEntries,
    "liqEntries must fit the ROB identity domain used by replay diagnostics")
  require(p.lineBytes == 64, "canonical scalar load path currently requires 64-byte cache lines")

  val io = IO(new ScalarLSULoadPathIO(coreParams, p))
  val mdbStore = IO(new ScalarLSULoadPathStoreIO(coreParams, p))
  val recovery = IO(new ScalarLSULoadPathRecoveryIO(coreParams, p))

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
    tidWidth = p.tidWidth,
    returnPipeCount = p.loadReturnPipeCount,
    lsidWidth = coreParams.lsidWidth
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
    sizeWidth = p.loadSizeWidth,
    lsidWidth = coreParams.lsidWidth
  ))
  val mdbPath = Module(new ScalarLSUMDBPath(coreParams))
  val returnDataExtract = Module(new LoadReplayReturnDataExtract(
    p.addrWidth,
    p.dataWidth,
    p.loadSizeWidth,
    p.lineBytes
  ))
  val returnPayload = Module(new LoadReplayReturnLretPayload(
    coreParams.robEntries,
    p.addrWidth,
    p.pcWidth,
    p.dataWidth,
    p.loadSizeWidth,
    p.loadReturnPipeCount,
    p.archRegWidth,
    p.physRegWidth,
    p.peIdWidth,
    p.stidWidth,
    p.tidWidth
  ))
  val returnQueue = Module(new ScalarLSULoadReturnQueueBank(
    coreParams.robEntries,
    p.stidCount,
    p.loadReturnPipeCount,
    p.loadReturnQueueEntries,
    p.addrWidth,
    p.pcWidth,
    p.dataWidth,
    p.loadSizeWidth,
    p.archRegWidth,
    p.physRegWidth,
    p.peIdWidth,
    p.stidWidth,
    p.tidWidth,
    coreParams.lsidWidth
  ))
  val returnPipeline = Module(new ScalarLSULoadReturnPipeline(
    coreParams.robEntries, p, coreParams.lsidWidth))

  val flushCycle = io.flush || io.preciseFlush.req.valid
  private val returnLaneCount = p.stidCount * p.loadReturnPipeCount
  private val returnLaneWidth = math.max(1, log2Ceil(returnLaneCount))
  private val reservationWidth = log2Ceil(p.loadReturnQueueEntries + 1)
  val returnReservations = RegInit(VecInit(Seq.fill(returnLaneCount)(0.U(reservationWidth.W))))
  val transferPending = RegInit(false.B)
  val transferIndex = RegInit(0.U(log2Ceil(p.liqEntries).W))

  val resolveCreditSafe =
    resolveQueue.io.count <= (p.resolveQueueEntries - 3).U &&
      (!transferPending || liq.io.clearResolvedAccepted)

  liq.io.flush := io.flush
  liq.io.preciseFlush := io.preciseFlush
  val allocNeedsMdbLookup = io.allocValid && !io.alloc.isTile
  val allocMdbReady = !allocNeedsMdbLookup || mdbPath.io.loadLookupReady
  liq.io.allocValid := io.allocValid && allocMdbReady
  liq.io.alloc := io.alloc
  val launchRow = liq.io.rows(io.launchIndex)
  val launchStidInRange = launchRow.stid < p.stidCount.U
  val launchPipeInRange = launchRow.returnPipeIndex < p.loadReturnPipeCount.U
  val launchTargetValid = launchStidInRange && launchPipeInRange
  val launchLane = Wire(UInt(returnLaneWidth.W))
  launchLane := launchRow.stid * p.loadReturnPipeCount.U + launchRow.returnPipeIndex
  val launchResidentCount =
    if (returnLaneCount == 1) returnQueue.io.laneCountState(0)
    else Mux(launchTargetValid, returnQueue.io.laneCountState(launchLane), 0.U)
  val launchReservedCount =
    if (returnLaneCount == 1) returnReservations(0)
    else Mux(launchTargetValid, returnReservations(launchLane), 0.U)
  val launchDrainCredit =
    returnQueue.io.drainFire && launchTargetValid && (returnQueue.io.drainLane === launchLane)
  val launchReturnCreditSafe =
    launchTargetValid &&
      ((launchResidentCount +& launchReservedCount) < p.loadReturnQueueEntries.U || launchDrainCredit)
  liq.io.launchValid := io.launchValid && resolveCreditSafe && launchReturnCreditSafe
  liq.io.launchIndex := io.launchIndex
  liq.io.pickValid := io.pickValid
  liq.io.pickIndex := io.pickIndex
  liq.io.scbReturnValid := io.scbReturnValid
  liq.io.scbReturnIndex := io.scbReturnIndex
  liq.io.markResolvedValid := false.B
  liq.io.markResolvedIndex := 0.U
  liq.io.e2Stores := io.e2Stores
  liq.io.e2BaseData := io.e2BaseData
  liq.io.e2BaseValidMask := io.e2BaseValidMask
  liq.io.e2LoadDataReturned := io.e2LoadDataReturned
  liq.io.e2ScbReturned := io.e2ScbReturned
  liq.io.e2StqReturned := io.e2StqReturned
  liq.io.e2ReturnReady := true.B
  val mdbReplayWake = Wire(chiselTypeOf(io.replayWake))
  mdbReplayWake := 0.U.asTypeOf(mdbReplayWake)
  mdbReplayWake.source := LoadReplayWakeSource.StoreUnit
  mdbReplayWake.storeId := mdbPath.io.storeWakeup.bid
  mdbReplayWake.storeLsId := mdbPath.io.storeWakeup.lsId
  mdbReplayWake.pc := mdbPath.io.storeWakeup.pc
  mdbReplayWake.lineAddr := Cat(
    mdbPath.io.storeWakeup.addr(p.addrWidth - 1, log2Ceil(p.lineBytes)),
    0.U(log2Ceil(p.lineBytes).W)
  )
  mdbReplayWake.validMask := 0.U
  mdbReplayWake.data := 0.U
  liq.io.replayWakeValid := mdbPath.io.storeWakeup.valid || io.replayWakeValid
  liq.io.replayWake := Mux(mdbPath.io.storeWakeup.valid, mdbReplayWake, io.replayWake)
  liq.io.refillValid := io.refillValid
  liq.io.refill := io.refill
  liq.io.clearResolvedValid := transferPending
  liq.io.clearResolvedIndex := transferIndex

  liq.io.rowMutationValid := mdbPath.io.mutationValid
  liq.io.rowMutationTargetIndex := mdbPath.io.mutationTargetIndex
  liq.io.rowMutationSetWaitStatus := mdbPath.io.mutationSetWaitStatus
  liq.io.rowMutationKeepRepickStatus := false.B
  liq.io.rowMutationClearReturnState := mdbPath.io.mutationClearReturnState
  liq.io.rowMutationLineWrite := mdbPath.io.mutationLineWrite
  liq.io.rowMutationWaitStoreWrite := mdbPath.io.mutationWaitStoreWrite
  liq.io.rowMutationNextWaitStore := mdbPath.io.mutationNextWaitStore
  liq.io.rowMutationNextWaitStoreInfo := mdbPath.io.mutationNextWaitStoreInfo
  liq.io.rowMutationNextLineData := 0.U
  liq.io.rowMutationNextValidMask := 0.U
  liq.io.rowMutationNextDataComplete := false.B
  liq.io.rowMutationNextScbReturned := false.B
  liq.io.rowMutationNextStqReturned := false.B
  liq.io.rowMutationNextStoreSourceReturned := false.B
  liq.io.rowMutationAllowWaitTarget := true.B
  liq.io.rowMutationRequireScbReturned := false.B

  mdbPath.io.flush := flushCycle
  mdbPath.io.storeProbe := mdbStore.probe
  mdbPath.io.storeProbeCommit := mdbStore.probeCommit
  mdbPath.io.storeRows := mdbStore.rows
  mdbStore.probeReady := mdbPath.io.storeProbeReady
  mdbPath.io.loadLookupValid := liq.io.allocAccepted && !io.alloc.isTile
  mdbPath.io.loadLookup := io.alloc
  mdbPath.io.loadRows := liq.io.rows
  mdbPath.io.resolvedRows := resolveQueue.io.conflictRows
  mdbPath.io.mutationAccepted := liq.io.rowMutationApplyValid
  mdbPath.io.recoveryReady := recovery.ready

  val hitRow = liq.io.rows(liq.io.e4UpdateIndex)
  returnDataExtract.io.enable := true.B
  returnDataExtract.io.returnValid := liq.io.lhqRecordValid
  returnDataExtract.io.lineData := liq.io.lhqRecord.data
  returnDataExtract.io.lineValidMask := liq.io.lhqRecord.byteMask
  returnDataExtract.io.addr := hitRow.addr
  returnDataExtract.io.size := hitRow.size
  returnDataExtract.io.signExtend := hitRow.returnSignExtend

  returnPayload.io.enable := true.B
  returnPayload.io.launchValid := liq.io.lhqRecordValid
  returnPayload.io.dataValid := returnDataExtract.io.dataValid
  returnPayload.io.selectedBid := hitRow.bid
  returnPayload.io.selectedGid := hitRow.gid
  returnPayload.io.selectedRid := hitRow.rid
  returnPayload.io.selectedLoadLsId := hitRow.loadLsId
  returnPayload.io.selectedPeId := hitRow.peId
  returnPayload.io.selectedStid := hitRow.stid
  returnPayload.io.selectedTid := hitRow.tid
  returnPayload.io.selectedPc := hitRow.pc
  returnPayload.io.selectedAddr := hitRow.addr
  returnPayload.io.selectedSize := hitRow.size
  returnPayload.io.selectedDst := hitRow.dst
  returnPayload.io.selectedSourceTraceValid := hitRow.sourceTraceValid
  returnPayload.io.selectedSource0 := hitRow.source0
  returnPayload.io.selectedSource1 := hitRow.source1
  returnPayload.io.returnData := returnDataExtract.io.data
  returnPayload.io.returnPipeIndex := hitRow.returnPipeIndex
  returnPayload.io.specWakeup := hitRow.specWakeup
  returnPayload.io.stackValid := hitRow.stackValid

  val returnEntry = Wire(chiselTypeOf(returnQueue.io.enqueue))
  returnEntry := 0.U.asTypeOf(returnEntry)
  returnEntry.valid := returnPayload.io.payloadValid
  returnEntry.bid := returnPayload.io.payloadBid
  returnEntry.gid := returnPayload.io.payloadGid
  returnEntry.rid := returnPayload.io.payloadRid
  returnEntry.loadLsId := returnPayload.io.payloadLoadLsId
  returnEntry.pc := returnPayload.io.payloadPc
  returnEntry.addr := returnPayload.io.payloadAddr
  returnEntry.size := returnPayload.io.payloadSize
  returnEntry.dst := returnPayload.io.payloadDst
  returnEntry.sourceTraceValid := returnPayload.io.payloadSourceTraceValid
  returnEntry.source0 := returnPayload.io.payloadSource0
  returnEntry.source1 := returnPayload.io.payloadSource1
  returnEntry.data := returnPayload.io.payloadData
  returnEntry.pipeIndex := returnPayload.io.payloadPipeIndex
  returnEntry.specWakeup := returnPayload.io.payloadSpecWakeup
  returnEntry.stackValid := returnPayload.io.payloadStackValid

  resolveQueue.io.flush := io.flush
  resolveQueue.io.preciseFlush := io.preciseFlush
  val publicationValid = liq.io.lhqRecordValid && !flushCycle
  val publicationPayloadValid = returnPayload.io.payloadValid
  val publicationReady = returnQueue.io.enqueueReady && resolveQueue.io.pushReady
  returnQueue.io.enable := true.B
  returnQueue.io.flush := io.flush
  returnQueue.io.preciseFlush := io.preciseFlush
  returnQueue.io.enqueueValid := publicationValid && publicationPayloadValid && resolveQueue.io.pushReady
  returnQueue.io.enqueuePeId := returnPayload.io.payloadPeId
  returnQueue.io.enqueueStid := returnPayload.io.payloadStid
  returnQueue.io.enqueueTid := returnPayload.io.payloadTid
  returnQueue.io.enqueuePipeIndex := returnPayload.io.payloadPipeIndex
  returnQueue.io.enqueue := returnEntry
  returnPipeline.io.enable := true.B
  returnPipeline.io.flush := io.flush
  returnPipeline.io.preciseFlush := io.preciseFlush
  returnPipeline.io.inValid := returnQueue.io.drainValid
  returnPipeline.io.in.peId := returnQueue.io.drainPeId
  returnPipeline.io.in.stid := returnQueue.io.drainStid
  returnPipeline.io.in.tid := returnQueue.io.drainTid
  returnPipeline.io.in.payload := returnQueue.io.drain
  returnPipeline.io.robRowValid := io.loadReturn.robRowValid
  returnPipeline.io.robRowNeedFlush := io.loadReturn.robRowNeedFlush
  val completionArb = Module(new RRArbiter(UInt(math.max(1, log2Ceil(p.loadReturnPipeCount)).W),
    p.loadReturnPipeCount))
  for (pipe <- 0 until p.loadReturnPipeCount) {
    completionArb.io.in(pipe).valid := returnPipeline.io.w2ValidMask(pipe)
    completionArb.io.in(pipe).bits := pipe.U
    val selected = completionArb.io.out.valid && completionArb.io.out.bits === pipe.U
    returnPipeline.io.resolveReady(pipe) := selected && io.loadReturn.resolveReady
    returnPipeline.io.writebackReady(pipe) := selected && io.loadReturn.writebackReady
    returnPipeline.io.wakeupReady(pipe) := selected && io.loadReturn.wakeupReady
  }
  val selectedResolveFire = returnPipeline.io.resolveFire.asUInt.orR
  completionArb.io.out.ready := selectedResolveFire
  returnQueue.io.drainReady := returnPipeline.io.inReady
  resolveQueue.io.pushValid := publicationValid && publicationPayloadValid && returnQueue.io.enqueueReady
  resolveQueue.io.pushPeId := hitRow.peId
  resolveQueue.io.pushStid := hitRow.stid
  resolveQueue.io.pushTid := hitRow.tid
  resolveQueue.io.pushRecord := liq.io.lhqRecord
  resolveQueue.io.retireValid := io.resolveRetireValid
  resolveQueue.io.retireBid := io.resolveRetireBid
  resolveQueue.io.retireLsId := io.resolveRetireLsId

  val publicationAccepted = returnQueue.io.enqueueAccepted && resolveQueue.io.pushAccepted
  val publicationAcceptanceMismatch =
    returnQueue.io.enqueueAccepted =/= resolveQueue.io.pushAccepted
  val publicationBlocked = publicationValid && (!publicationPayloadValid || !publicationReady)
  val transferProtocolError =
    publicationBlocked || publicationAcceptanceMismatch ||
      (resolveQueue.io.pushAccepted && transferPending && !liq.io.clearResolvedAccepted)

  val releaseStidInRange = hitRow.stid < p.stidCount.U
  val releasePipeInRange = hitRow.returnPipeIndex < p.loadReturnPipeCount.U
  val releaseTargetValid = releaseStidInRange && releasePipeInRange
  val releaseLane = Wire(UInt(returnLaneWidth.W))
  releaseLane := hitRow.stid * p.loadReturnPipeCount.U + hitRow.returnPipeIndex
  val releaseReservation = liq.io.e4UpdateValid && releaseTargetValid && !flushCycle
  val selectedReleaseReservation =
    if (returnLaneCount == 1) returnReservations(0)
    else returnReservations(releaseLane)
  val reservationUnderflow = releaseReservation && (selectedReleaseReservation === 0.U)

  for (lane <- 0 until returnLaneCount) {
    val reserve = liq.io.launchAccepted && launchTargetValid && (launchLane === lane.U)
    val release = releaseReservation && (releaseLane === lane.U)
    when(flushCycle) {
      returnReservations(lane) := 0.U
    }.elsewhen(reserve && !release) {
      returnReservations(lane) := returnReservations(lane) + 1.U
    }.elsewhen(release && !reserve) {
      returnReservations(lane) := returnReservations(lane) - 1.U
    }
  }
  assert(!reservationUnderflow, "scalar load-return E4 completion must release a launch reservation")
  assert(!publicationValid || publicationAccepted,
    "scalar E4 hit must enter ResolveQ and its selected LRET lane atomically")

  when(flushCycle) {
    transferPending := false.B
  }.otherwise {
    when(liq.io.clearResolvedAccepted) {
      transferPending := false.B
    }
    when(publicationAccepted) {
      transferPending := true.B
      transferIndex := liq.io.e4UpdateIndex
    }
  }

  io.allocReady := liq.io.allocReady && allocMdbReady
  io.allocAccepted := liq.io.allocAccepted
  io.allocIndex := liq.io.allocIndex
  io.allocLoadId := liq.io.allocLoadId
  io.launchReady := liq.io.launchReady && resolveCreditSafe && launchReturnCreditSafe
  io.launchAccepted := liq.io.launchAccepted
  io.launchBlockedByResolveCredit := io.launchValid && !resolveCreditSafe
  io.launchBlockedByReturnCredit := io.launchValid && !launchReturnCreditSafe
  io.pickReady := liq.io.pickReady
  io.pickAccepted := liq.io.pickAccepted
  io.scbReturnReady := liq.io.scbReturnReady
  io.scbReturnAccepted := liq.io.scbReturnAccepted
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
  val reservedCount = returnReservations.map(_.asUInt).reduce(_ +& _)
  io.loadReturn.drainValid := returnQueue.io.drainValid
  io.loadReturn.drain := returnQueue.io.drain
  io.loadReturn.drainPeId := returnQueue.io.drainPeId
  io.loadReturn.drainStid := returnQueue.io.drainStid
  io.loadReturn.drainTid := returnQueue.io.drainTid
  io.loadReturn.drainPipeIndex := returnQueue.io.drainPipeIndex
  io.loadReturn.drainLane := returnQueue.io.drainLane
  io.loadReturn.drainFire := returnQueue.io.drainFire
  io.loadReturn.robLookupValid := returnPipeline.io.robLookupValid
  io.loadReturn.robLookupPeId := returnPipeline.io.robLookupPeId
  io.loadReturn.robLookupStid := returnPipeline.io.robLookupStid
  io.loadReturn.robLookupTid := returnPipeline.io.robLookupTid
  io.loadReturn.robLookupBid := returnPipeline.io.robLookupBid
  io.loadReturn.robLookupGid := returnPipeline.io.robLookupGid
  io.loadReturn.robLookupRid := returnPipeline.io.robLookupRid
  io.loadReturn.robLookupLoadLsId := returnPipeline.io.robLookupLoadLsId
  io.loadReturn.completionCandidateValid := completionArb.io.out.valid
  io.loadReturn.completionSelectedPipe := completionArb.io.out.bits
  io.loadReturn.resolveFire := selectedResolveFire
  io.loadReturn.writebackFire := returnPipeline.io.writebackFire.asUInt.orR
  io.loadReturn.wakeupFire := returnPipeline.io.wakeupFire.asUInt.orR
  io.loadReturn.completion := Mux1H(
    UIntToOH(completionArb.io.out.bits, p.loadReturnPipeCount),
    returnPipeline.io.completion
  )
  io.loadReturn.w1ValidMask := returnPipeline.io.w1ValidMask
  io.loadReturn.w2ValidMask := returnPipeline.io.w2ValidMask
  io.loadReturn.w1PrecisePruneMask := returnPipeline.io.w1PrecisePruneMask
  io.loadReturn.w2PrecisePruneMask := returnPipeline.io.w2PrecisePruneMask
  io.loadReturn.completionMask := returnPipeline.io.completionMask
  io.loadReturn.w1Count := returnPipeline.io.w1Count
  io.loadReturn.w2Count := returnPipeline.io.w2Count
  io.loadReturn.pipelineEmpty := returnPipeline.io.empty
  io.loadReturn.pending := returnQueue.io.pending || !returnPipeline.io.empty
  io.loadReturn.full := returnQueue.io.full
  io.loadReturn.empty := returnQueue.io.empty && returnPipeline.io.empty
  io.loadReturn.laneCounts := returnQueue.io.laneCountState
  io.loadReturn.totalCount := returnQueue.io.totalCount
  io.loadReturn.reservedCount := reservedCount
  io.loadReturn.precisePruneCount := returnQueue.io.precisePruneCount
  io.loadReturn.publicationValid := publicationValid
  io.loadReturn.publicationAccepted := publicationAccepted
  io.loadReturn.protocolError :=
    transferProtocolError || reservationUnderflow || returnPipeline.io.protocolError
  io.mdbConflictValid := mdbPath.io.conflictValid
  io.mdbConflictFromResolveQueue := mdbPath.io.conflictFromResolveQueue
  io.mdbConflictActiveMask := mdbPath.io.conflictActiveMask
  io.mdbConflictResolveMask := mdbPath.io.conflictResolveMask
  io.mdbConflictWaitStoreMask := mdbPath.io.conflictWaitStoreMask
  io.mdbConflictFlush := mdbPath.io.conflictFlush
  recovery.valid := mdbPath.io.recoveryValid
  recovery.flush := mdbPath.io.recoveryFlush
  recovery.accepted := mdbPath.io.recoveryAccepted
  recovery.pending := mdbPath.io.recoveryPending
  io.mdbRecordAccepted := mdbPath.io.recordAccepted
  io.mdbRecordProcessed := mdbPath.io.recordProcessed
  io.mdbBmdbReportValid := mdbPath.io.bmdbReportValid
  io.mdbLookupAccepted := mdbPath.io.lookupAccepted
  io.mdbLookupProcessed := mdbPath.io.lookupProcessed
  io.mdbLookupHit := mdbPath.io.lookupHit
  io.mdbLookupWaitMutation := mdbPath.io.lookupWaitMutation
  io.mdbFailedWaitActiveMask := mdbPath.io.failedWaitActiveMask
  io.mdbFailedWaitExpiredMask := mdbPath.io.failedWaitExpiredMask
  io.mdbFailedWaitReleaseValid := mdbPath.io.failedWaitReleaseValid
  io.mdbFailedWaitReleaseIndex := mdbPath.io.failedWaitReleaseIndex
  io.mdbFailedWaitReleaseAccepted := mdbPath.io.failedWaitReleaseAccepted
  io.mdbDeleteAccepted := mdbPath.io.deleteAccepted
  io.mdbDeleteProcessed := mdbPath.io.deleteProcessed
  io.mdbDeleteMatched := mdbPath.io.deleteMatched
  io.mdbDeleteDroppedBelowStall := mdbPath.io.deleteDroppedBelowStall
  io.mdbDeleteReleased := mdbPath.io.deleteReleased
  io.mdbWaitPlanPending := mdbPath.io.waitPlanPending
  io.mdbWaitPlanTargetMask := mdbPath.io.waitPlanTargetMask
  io.mdbSsitValidMask := mdbPath.io.ssitValidMask
  io.mdbStoreWakeupValid := mdbPath.io.storeWakeup.valid
  io.mdbReplayWakeCollision := mdbPath.io.storeWakeup.valid && io.replayWakeValid
  io.mdbTransientEmpty := mdbPath.io.transientEmpty
  io.mdbProtocolError := mdbPath.io.protocolError || io.mdbReplayWakeCollision
  io.empty :=
    liq.io.empty && resolveQueue.io.empty && !transferPending && mdbPath.io.transientEmpty &&
      returnQueue.io.empty && returnPipeline.io.empty && (reservedCount === 0.U)
}
