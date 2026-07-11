package linxcore.lsu

import chisel3._
import chisel3.util.{log2Ceil, PriorityEncoder, Queue, UIntToOH}

import linxcore.common.{CoreParams, ScalarLsuParams}
import linxcore.recovery.{ExecEngineType, FlushBus, FlushControl, FlushReq, FlushType}
import linxcore.rob.ROBID

class ScalarLSUMDBWaitPlan(
    val robEntries: Int,
    val liqEntries: Int,
    val addrWidth: Int,
    val pcWidth: Int,
    val peIdWidth: Int,
    val stidWidth: Int,
    val tidWidth: Int,
    val sizeWidth: Int)
    extends Bundle {
  val targetMask = UInt(liqEntries.W)
  val store = new MDBConflictStoreProbe(
    robEntries,
    addrWidth,
    pcWidth,
    peIdWidth,
    stidWidth,
    tidWidth,
    sizeWidth
  )
}

class ScalarLSUMDBPathIO(val coreParams: CoreParams, val p: ScalarLsuParams) extends Bundle {
  private val liqPtrWidth = log2Ceil(p.liqEntries)
  private val weightWidth = log2Ceil(p.mdbMaxWeight + 1).max(1)

  val flush = Input(Bool())
  val storeProbe = Input(new MDBConflictStoreProbe(
    coreParams.robEntries,
    p.addrWidth,
    p.pcWidth,
    p.peIdWidth,
    p.stidWidth,
    p.tidWidth,
    p.loadSizeWidth
  ))
  val storeProbeReady = Output(Bool())
  val storeRows = Input(Vec(
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
  val loadLookupValid = Input(Bool())
  val loadLookup = Input(new LoadInflightAlloc(
    p.liqEntries,
    coreParams.robEntries,
    p.addrWidth,
    p.pcWidth,
    p.loadSizeWidth,
    p.archRegWidth,
    p.physRegWidth,
    p.peIdWidth,
    p.stidWidth,
    p.tidWidth
  ))
  val loadLookupReady = Output(Bool())
  val loadRows = Input(Vec(
    p.liqEntries,
    new LoadInflightRow(
      p.liqEntries,
      coreParams.robEntries,
      p.stqEntries,
      p.addrWidth,
      p.pcWidth,
      p.lineBytes,
      p.loadSizeWidth,
      p.archRegWidth,
      p.physRegWidth,
      p.peIdWidth,
      p.stidWidth,
      p.tidWidth
    )
  ))
  val resolvedRows = Input(Vec(
    p.resolveQueueEntries,
    new MDBConflictLoadEntry(
      coreParams.robEntries,
      p.addrWidth,
      p.pcWidth,
      p.peIdWidth,
      p.stidWidth,
      p.tidWidth,
      p.loadSizeWidth
    )
  ))

  val mutationAccepted = Input(Bool())
  val mutationValid = Output(Bool())
  val mutationTargetIndex = Output(UInt(liqPtrWidth.W))
  val mutationSetWaitStatus = Output(Bool())
  val mutationClearReturnState = Output(Bool())
  val mutationLineWrite = Output(Bool())
  val mutationWaitStoreWrite = Output(Bool())
  val mutationNextWaitStore = Output(Bool())
  val mutationNextWaitStoreInfo = Output(new LoadStoreForwardWait(
    coreParams.robEntries,
    p.stqEntries,
    p.pcWidth
  ))

  val conflictValid = Output(Bool())
  val conflictFromResolveQueue = Output(Bool())
  val conflictActiveMask = Output(UInt(p.liqEntries.W))
  val conflictResolveMask = Output(UInt(p.resolveQueueEntries.W))
  val conflictWaitStoreMask = Output(UInt(p.liqEntries.W))
  val conflictFlush = Output(new FlushBus(
    coreParams.robEntries,
    p.peIdWidth,
    p.stidWidth,
    p.tidWidth
  ))
  val recordAccepted = Output(Bool())
  val recordProcessed = Output(Bool())
  val bmdbReportValid = Output(Bool())
  val bmdbLoadBid = Output(new ROBID(coreParams.robEntries))
  val bmdbStoreBid = Output(new ROBID(coreParams.robEntries))
  val bmdbStoreStid = Output(UInt(p.stidWidth.W))
  val ssitValidMask = Output(UInt(p.mdbSsitEntries.W))
  val lookupAccepted = Output(Bool())
  val lookupProcessed = Output(Bool())
  val lookupHit = Output(Bool())
  val lookupWaitMutation = Output(Bool())
  val failedWaitActiveMask = Output(UInt(p.liqEntries.W))
  val failedWaitExpiredMask = Output(UInt(p.liqEntries.W))
  val failedWaitReleaseValid = Output(Bool())
  val failedWaitReleaseIndex = Output(UInt(liqPtrWidth.W))
  val failedWaitReleaseAge = Output(UInt(log2Ceil(p.mdbFailedWaitTimeoutCycles + 1).max(1).W))
  val failedWaitReleaseAccepted = Output(Bool())
  val deleteAccepted = Output(Bool())
  val deleteProcessed = Output(Bool())
  val deleteMatched = Output(Bool())
  val deleteDroppedBelowStall = Output(Bool())
  val deleteReleased = Output(Bool())
  val storeWakeup = Output(new MDBStoreWakeup(
    coreParams.robEntries,
    p.stqEntries,
    p.addrWidth,
    p.pcWidth,
    p.stidWidth,
    p.loadSizeWidth
  ))
  val waitPlanPending = Output(Bool())
  val waitPlanTargetMask = Output(UInt(p.liqEntries.W))
  val transientEmpty = Output(Bool())
  val protocolError = Output(Bool())
  val ssitTable = Output(Vec(
    p.mdbSsitEntries,
    new MDBSSITEntry(coreParams.robEntries, p.pcWidth, weightWidth, p.mdbConfWidth)
  ))
}

class ScalarLSUMDBPath(val coreParams: CoreParams = CoreParams()) extends Module {
  private val p = coreParams.scalarLsu
  private val liqPtrWidth = log2Ceil(p.liqEntries)
  private val weightWidth = log2Ceil(p.mdbMaxWeight + 1).max(1)

  val io = IO(new ScalarLSUMDBPathIO(coreParams, p))

  private def zeroBus: MDBQueueBus =
    0.U.asTypeOf(new MDBQueueBus(
      coreParams.robEntries,
      p.addrWidth,
      p.pcWidth,
      p.stidWidth,
      p.loadSizeWidth,
      p.mdbConfWidth,
      weightWidth
    ))

  private def zeroWait: LoadStoreForwardWait =
    0.U.asTypeOf(new LoadStoreForwardWait(coreParams.robEntries, p.stqEntries, p.pcWidth))

  val conflict = Module(new MDBConflictDetect(
    entries = coreParams.robEntries,
    loadEntries = p.liqEntries,
    resolveEntries = p.resolveQueueEntries,
    addrWidth = p.addrWidth,
    pcWidth = p.pcWidth,
    peIdWidth = p.peIdWidth,
    stidWidth = p.stidWidth,
    tidWidth = p.tidWidth,
    sizeWidth = p.loadSizeWidth
  ))
  conflict.io.store := io.storeProbe
  for (idx <- 0 until p.liqEntries) {
    val row = io.loadRows(idx)
    val out = Wire(chiselTypeOf(conflict.io.activeLoads(idx)))
    out := 0.U.asTypeOf(out)
    out.valid := row.valid
    out.resolved := row.status === LoadInflightStatus.Resolved
    out.isTile := row.isTile
    out.peId := row.peId
    out.stid := row.stid
    out.tid := row.tid
    out.bid := row.bid
    out.gid := row.gid
    out.rid := row.rid
    out.lsId := row.loadLsId
    out.pc := row.pc
    out.addr := row.addr
    out.size := row.size
    conflict.io.activeLoads(idx) := out
  }
  conflict.io.resolvedQueue := io.resolvedRows

  val fanout = Module(new MDBQueueFanout(
    entries = coreParams.robEntries,
    ssitEntries = p.mdbSsitEntries,
    commandQueueEntries = p.mdbCommandQueueEntries,
    outputQueueEntries = p.mdbOutputQueueEntries,
    storeEntries = p.stqEntries,
    addrWidth = p.addrWidth,
    pcWidth = p.pcWidth,
    stidWidth = p.stidWidth,
    sizeWidth = p.loadSizeWidth,
    mdbReleaseWeight = p.mdbReleaseWeight,
    mdbMaxWeight = p.mdbMaxWeight,
    mdbIncStep = p.mdbIncStep,
    confWidth = p.mdbConfWidth
  ))
  fanout.io.flush := io.flush
  fanout.io.storeRows := io.storeRows

  val recordBus = Wire(chiselTypeOf(fanout.io.recordIn))
  recordBus := zeroBus
  recordBus.valid := conflict.io.conflictValid
  recordBus.ldInfo.valid := conflict.io.conflictValid
  recordBus.ldInfo.pc := conflict.io.record.load.pc
  recordBus.ldInfo.bid := conflict.io.record.load.bid
  recordBus.ldInfo.lsId := conflict.io.record.load.lsId
  recordBus.ldInfo.stid := conflict.io.record.load.stid
  recordBus.ldInfo.addr := conflict.io.record.load.addr
  recordBus.ldInfo.size := conflict.io.record.load.size
  recordBus.ldInfo.isTile := conflict.io.record.load.isTile
  recordBus.stInfo.valid := conflict.io.conflictValid
  recordBus.stInfo.pc := conflict.io.record.store.pc
  recordBus.stInfo.bid := conflict.io.record.store.bid
  recordBus.stInfo.lsId := conflict.io.record.store.lsId
  recordBus.stInfo.stid := conflict.io.record.store.stid
  recordBus.stInfo.addr := conflict.io.record.store.addr
  recordBus.stInfo.size := conflict.io.record.store.size
  recordBus.stInfo.isTile := conflict.io.record.store.isTile
  recordBus.conf := 1.U
  fanout.io.recordIn := recordBus
  fanout.io.recordInValid := io.storeProbe.valid && conflict.io.conflictValid

  val lookupBus = Wire(chiselTypeOf(fanout.io.lookupIn))
  lookupBus := zeroBus
  lookupBus.valid := io.loadLookupValid
  lookupBus.ldInfo.valid := io.loadLookupValid
  lookupBus.ldInfo.pc := io.loadLookup.pc
  lookupBus.ldInfo.bid := io.loadLookup.bid
  lookupBus.ldInfo.lsId := io.loadLookup.loadLsId
  lookupBus.ldInfo.stid := io.loadLookup.stid
  lookupBus.ldInfo.addr := io.loadLookup.addr
  lookupBus.ldInfo.size := io.loadLookup.size
  lookupBus.ldInfo.isTile := io.loadLookup.isTile
  lookupBus.conf := 1.U
  fanout.io.lookupIn := lookupBus
  fanout.io.lookupInValid := io.loadLookupValid

  val failedWait = Module(new LoadWaitStoreTimeout(
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
    timeoutCycles = p.mdbFailedWaitTimeoutCycles
  ))
  failedWait.io.flush := io.flush
  failedWait.io.rows := io.loadRows

  val failedWaitRow = io.loadRows(failedWait.io.releaseIndex)
  val deleteBus = Wire(chiselTypeOf(fanout.io.deleteIn))
  deleteBus := zeroBus
  deleteBus.valid := failedWait.io.releaseValid
  deleteBus.ldInfo.valid := failedWait.io.releaseValid
  deleteBus.ldInfo.pc := failedWaitRow.pc
  deleteBus.ldInfo.bid := failedWaitRow.bid
  deleteBus.ldInfo.lsId := failedWaitRow.loadLsId
  deleteBus.ldInfo.stid := failedWaitRow.stid
  deleteBus.ldInfo.addr := failedWaitRow.addr
  deleteBus.ldInfo.size := failedWaitRow.size
  deleteBus.ldInfo.waitStorePc := failedWaitRow.waitStoreInfo.pc
  deleteBus.ldInfo.isTile := failedWaitRow.isTile
  deleteBus.stInfo.valid := failedWait.io.releaseValid
  deleteBus.stInfo.pc := failedWaitRow.waitStoreInfo.pc
  deleteBus.stInfo.bid := failedWaitRow.waitStoreInfo.storeId
  deleteBus.stInfo.lsId := failedWaitRow.waitStoreInfo.storeLsId
  deleteBus.stInfo.stid := failedWaitRow.stid
  deleteBus.conf := 1.U
  fanout.io.deleteIn := deleteBus

  val waitPlanQ = withReset(reset.asBool || io.flush) {
    Module(new Queue(
      new ScalarLSUMDBWaitPlan(
        coreParams.robEntries,
        p.liqEntries,
        p.addrWidth,
        p.pcWidth,
        p.peIdWidth,
        p.stidWidth,
        p.tidWidth,
        p.loadSizeWidth
      ),
      p.mdbWaitPlanQueueEntries
    ))
  }
  waitPlanQ.io.enq.valid := io.storeProbe.valid && conflict.io.waitStoreMask.orR && !io.flush
  waitPlanQ.io.enq.bits.targetMask := conflict.io.waitStoreMask
  waitPlanQ.io.enq.bits.store := io.storeProbe

  val currentWaitValid = RegInit(false.B)
  val currentWaitMask = RegInit(0.U(p.liqEntries.W))
  val currentWaitStore = RegInit(0.U.asTypeOf(chiselTypeOf(io.storeProbe)))
  waitPlanQ.io.deq.ready := !currentWaitValid && !io.flush

  val currentWaitIndex = PriorityEncoder(currentWaitMask)
  val currentWaitOH = UIntToOH(currentWaitIndex, p.liqEntries)
  val currentWaitRow = io.loadRows(currentWaitIndex)
  val currentWaitActionable =
    currentWaitValid &&
      currentWaitRow.valid &&
      ((currentWaitRow.status === LoadInflightStatus.Wait) ||
        (currentWaitRow.status === LoadInflightStatus.Repick))

  val currentStoreMatchVec = Wire(Vec(p.stqEntries, Bool()))
  for (idx <- 0 until p.stqEntries) {
    val row = io.storeRows(idx)
    currentStoreMatchVec(idx) :=
      row.valid &&
        ROBID.equal(row.bid, currentWaitStore.bid) &&
        ROBID.equal(row.lsId, currentWaitStore.lsId) &&
        (row.pc === currentWaitStore.pc)
  }
  val currentStoreMatchMask = currentStoreMatchVec.asUInt
  val currentStoreMatch = currentStoreMatchMask.orR
  val currentStoreIndex = PriorityEncoder(currentStoreMatchMask)

  val lookupStoreMatchVec = Wire(Vec(p.stqEntries, Bool()))
  for (idx <- 0 until p.stqEntries) {
    val row = io.storeRows(idx)
    lookupStoreMatchVec(idx) :=
      fanout.io.luOutValid &&
        fanout.io.luOut.hit &&
        row.valid &&
        ROBID.equal(row.bid, fanout.io.luOut.stInfo.bid) &&
        (row.pc === fanout.io.luOut.stInfo.pc)
  }
  val lookupStoreMatchMask = lookupStoreMatchVec.asUInt
  val lookupStoreMatch = lookupStoreMatchMask.orR
  val lookupStoreIndex = PriorityEncoder(lookupStoreMatchMask)
  val lookupStoreRow = io.storeRows(lookupStoreIndex)

  val lookupWaitPlan = Module(new LoadReplayMdbLookupWaitPlan(
    liqEntries = p.liqEntries,
    idEntries = coreParams.robEntries,
    storeEntries = p.stqEntries,
    addrWidth = p.addrWidth,
    pcWidth = p.pcWidth,
    lineBytes = p.lineBytes,
    sizeWidth = p.loadSizeWidth,
    archRegWidth = p.archRegWidth,
    physRegWidth = p.physRegWidth,
    stidWidth = p.stidWidth,
    confWidth = p.mdbConfWidth,
    weightWidth = weightWidth
  ))
  lookupWaitPlan.io.enable := !currentWaitValid && !failedWait.io.releaseValid
  lookupWaitPlan.io.flush := io.flush
  lookupWaitPlan.io.luOutValid := fanout.io.luOutValid
  lookupWaitPlan.io.luOut := fanout.io.luOut
  lookupWaitPlan.io.rows := io.loadRows
  lookupWaitPlan.io.storeIndexValid := lookupStoreMatch
  lookupWaitPlan.io.storeIndex := lookupStoreIndex
  lookupWaitPlan.io.storeLsIdValid := lookupStoreMatch
  lookupWaitPlan.io.storeLsId := lookupStoreRow.lsId

  val conflictWaitInfo = Wire(chiselTypeOf(io.mutationNextWaitStoreInfo))
  conflictWaitInfo := zeroWait
  conflictWaitInfo.valid := currentWaitActionable
  conflictWaitInfo.storeIndex := Mux(currentStoreMatch, currentStoreIndex, 0.U)
  conflictWaitInfo.storeId := currentWaitStore.bid
  conflictWaitInfo.storeLsId := Mux(currentStoreMatch, currentWaitStore.lsId, ROBID.disabled(coreParams.robEntries))
  conflictWaitInfo.pc := currentWaitStore.pc

  val selectFailedWait = failedWait.io.releaseValid && fanout.io.deleteInReady
  val selectConflictWait = !failedWait.io.releaseValid && currentWaitActionable
  val selectLookupWait =
    !failedWait.io.releaseValid && !currentWaitValid && lookupWaitPlan.io.requestValid
  io.mutationValid := selectFailedWait || selectConflictWait || selectLookupWait
  io.mutationTargetIndex := Mux(
    selectFailedWait,
    failedWait.io.releaseIndex,
    Mux(selectConflictWait, currentWaitIndex, lookupWaitPlan.io.requestTargetIndex)
  )
  io.mutationSetWaitStatus := io.mutationValid
  io.mutationClearReturnState := io.mutationValid
  io.mutationLineWrite := io.mutationValid
  io.mutationWaitStoreWrite := io.mutationValid
  io.mutationNextWaitStore := io.mutationValid && !selectFailedWait
  io.mutationNextWaitStoreInfo := Mux(
    selectFailedWait,
    zeroWait,
    Mux(selectConflictWait, conflictWaitInfo, lookupWaitPlan.io.nextWaitStoreInfo)
  )

  val failedWaitReleaseAccepted = selectFailedWait && io.mutationAccepted
  fanout.io.deleteInValid := failedWaitReleaseAccepted
  failedWait.io.releaseAccepted := failedWaitReleaseAccepted && fanout.io.deleteInAccepted

  val lookupCanDequeue =
    !failedWait.io.releaseValid &&
      !currentWaitValid &&
      (!lookupWaitPlan.io.requestValid || io.mutationAccepted)
  fanout.io.luDequeueReady := lookupCanDequeue
  fanout.io.suCheckReady := lookupCanDequeue

  when(io.flush) {
    currentWaitValid := false.B
    currentWaitMask := 0.U
    currentWaitStore := 0.U.asTypeOf(currentWaitStore)
  }.otherwise {
    when(!currentWaitValid && waitPlanQ.io.deq.valid) {
      currentWaitValid := true.B
      currentWaitMask := waitPlanQ.io.deq.bits.targetMask
      currentWaitStore := waitPlanQ.io.deq.bits.store
    }.elsewhen(currentWaitValid && (!currentWaitActionable || io.mutationAccepted)) {
      val remaining = currentWaitMask & ~currentWaitOH
      currentWaitMask := remaining
      when(!remaining.orR) {
        currentWaitValid := false.B
      }
    }
  }

  val flushReq = Wire(new FlushReq(
    coreParams.robEntries,
    p.peIdWidth,
    p.stidWidth,
    p.tidWidth
  ))
  flushReq := 0.U.asTypeOf(flushReq)
  flushReq.valid := io.storeProbe.valid && conflict.io.conflictValid
  flushReq.typ := Mux(conflict.io.nukeFlush, FlushType.NukeFlush, FlushType.InnerFlush)
  flushReq.peId := conflict.io.record.load.peId
  flushReq.tid := conflict.io.record.load.tid
  flushReq.stid := conflict.io.record.load.stid
  flushReq.bid := conflict.io.record.load.bid
  flushReq.gid := conflict.io.record.load.gid
  flushReq.rid := conflict.io.record.load.rid
  flushReq.lsId := conflict.io.record.load.lsId
  flushReq.execEngine := ExecEngineType.Scalar
  flushReq.fetchTpcValid := true.B
  flushReq.immediateFlush := true.B

  val storeWakeupReg = RegInit(0.U.asTypeOf(chiselTypeOf(io.storeWakeup)))
  when(io.flush) {
    storeWakeupReg := 0.U.asTypeOf(storeWakeupReg)
  }.otherwise {
    storeWakeupReg := fanout.io.suWakeup
  }

  io.storeProbeReady := !io.flush && fanout.io.recordInReady && waitPlanQ.io.enq.ready
  io.loadLookupReady := !io.flush && fanout.io.lookupInReady
  io.conflictValid := io.storeProbe.valid && conflict.io.conflictValid
  io.conflictFromResolveQueue := conflict.io.conflictFromResolveQueue
  io.conflictActiveMask := conflict.io.activeCandidateMask
  io.conflictResolveMask := conflict.io.resolveCandidateMask
  io.conflictWaitStoreMask := conflict.io.waitStoreMask
  io.conflictFlush := FlushControl.annotate(flushReq)
  io.recordAccepted := fanout.io.recordInAccepted
  io.recordProcessed := fanout.io.recordProcessed
  io.bmdbReportValid := fanout.io.bmdbReportValid
  io.bmdbLoadBid := fanout.io.bmdbLoadBid
  io.bmdbStoreBid := fanout.io.bmdbStoreBid
  io.bmdbStoreStid := fanout.io.bmdbStoreStid
  io.ssitValidMask := fanout.io.ssitValidMask
  io.lookupAccepted := fanout.io.lookupInAccepted
  io.lookupProcessed := fanout.io.lookupProcessed
  io.lookupHit := fanout.io.luOutValid && fanout.io.luOut.hit
  io.lookupWaitMutation := selectLookupWait && io.mutationAccepted
  io.failedWaitActiveMask := failedWait.io.activeMask
  io.failedWaitExpiredMask := failedWait.io.expiredMask
  io.failedWaitReleaseValid := failedWait.io.releaseValid
  io.failedWaitReleaseIndex := failedWait.io.releaseIndex
  io.failedWaitReleaseAge := failedWait.io.releaseAge
  io.failedWaitReleaseAccepted := failedWait.io.releaseAccepted
  io.deleteAccepted := fanout.io.deleteInAccepted
  io.deleteProcessed := fanout.io.deleteProcessed
  io.deleteMatched := fanout.io.deleteMatched
  io.deleteDroppedBelowStall := fanout.io.deleteDroppedBelowStall
  io.deleteReleased := fanout.io.deleteReleased
  io.storeWakeup := storeWakeupReg
  io.waitPlanPending := currentWaitValid || waitPlanQ.io.deq.valid
  io.waitPlanTargetMask := Mux(currentWaitValid, currentWaitMask, 0.U)
  io.transientEmpty :=
    fanout.io.transientEmpty &&
      !currentWaitValid &&
      !waitPlanQ.io.deq.valid &&
      !failedWait.io.releaseValid &&
      !storeWakeupReg.valid
  io.protocolError :=
    (io.storeProbe.valid && !io.storeProbeReady) ||
      (io.loadLookupValid && !io.loadLookupReady) ||
      (failedWaitReleaseAccepted && !fanout.io.deleteInAccepted) ||
      fanout.io.recordOverflow ||
      fanout.io.recordOrderIllegal
  io.ssitTable := fanout.io.ssitTable
}
