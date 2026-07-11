package linxcore.lsu

import chisel3._
import linxcore.common.{CoreParams, ScalarLsuParams}
import linxcore.recovery.FlushType
import linxcore.rob.ROBID

class ScalarLSUMDBPathProbeIO extends Bundle {
  val flush = Input(Bool())
  val loadValid = Input(Bool())
  val loadResolved = Input(Bool())
  val loadBid = Input(UInt(5.W))
  val loadLsId = Input(UInt(5.W))
  val loadPc = Input(UInt(64.W))
  val loadAddr = Input(UInt(64.W))
  val loadSize = Input(UInt(7.W))
  val loadWaitStore = Input(Bool())
  val storeProbeValid = Input(Bool())
  val storeAddrOnly = Input(Bool())
  val storeBid = Input(UInt(5.W))
  val storeLsId = Input(UInt(5.W))
  val storePc = Input(UInt(64.W))
  val storeAddr = Input(UInt(64.W))
  val storeSize = Input(UInt(7.W))
  val lookupValid = Input(Bool())
  val mutationAccepted = Input(Bool())
  val recoveryReady = Input(Bool())
  val integratedAllocValid = Input(Bool())
  val integratedTrainValid = Input(Bool())
  val integratedSeedWaitValid = Input(Bool())

  val storeProbeReady = Output(Bool())
  val lookupReady = Output(Bool())
  val conflictValid = Output(Bool())
  val innerFlush = Output(Bool())
  val nukeFlush = Output(Bool())
  val recoveryValid = Output(Bool())
  val recoveryInnerFlush = Output(Bool())
  val recoveryNukeFlush = Output(Bool())
  val recoveryAccepted = Output(Bool())
  val recoveryPending = Output(Bool())
  val waitStoreMask = Output(UInt(4.W))
  val recordAccepted = Output(Bool())
  val recordProcessed = Output(Bool())
  val bmdbReportValid = Output(Bool())
  val ssitValidMask = Output(UInt(8.W))
  val lookupAccepted = Output(Bool())
  val lookupProcessed = Output(Bool())
  val lookupHit = Output(Bool())
  val mutationValid = Output(Bool())
  val mutationTargetIndex = Output(UInt(2.W))
  val lookupWaitMutation = Output(Bool())
  val failedWaitReleaseValid = Output(Bool())
  val failedWaitReleaseAccepted = Output(Bool())
  val deleteAccepted = Output(Bool())
  val deleteProcessed = Output(Bool())
  val deleteMatched = Output(Bool())
  val deleteDroppedBelowStall = Output(Bool())
  val deleteReleased = Output(Bool())
  val oneCycleTimeoutValid = Output(Bool())
  val integratedAllocAccepted = Output(Bool())
  val integratedTrainReady = Output(Bool())
  val integratedTrainAccepted = Output(Bool())
  val integratedRecordProcessed = Output(Bool())
  val integratedSeedWaitAccepted = Output(Bool())
  val integratedWaitStoreMask = Output(UInt(4.W))
  val integratedFailedWaitReleaseValid = Output(Bool())
  val integratedFailedWaitReleaseAccepted = Output(Bool())
  val integratedDeleteAccepted = Output(Bool())
  val integratedDeleteProcessed = Output(Bool())
  val integratedDeleteMatched = Output(Bool())
  val integratedProtocolError = Output(Bool())
  val protocolError = Output(Bool())
}

class ScalarLSUMDBPathProbe extends Module {
  private val lsuParams = ScalarLsuParams(
    stqEntries = 4,
    commitQueueEntries = 4,
    commitIssueWidth = 1,
    scbEntries = 4,
    liqEntries = 4,
    resolveQueueEntries = 4,
    mdbSsitEntries = 8,
    mdbCommandQueueEntries = 4,
    mdbOutputQueueEntries = 4,
    mdbWaitPlanQueueEntries = 4,
    mdbFailedWaitTimeoutCycles = 4,
    mapQDepth = 8
  )
  private val coreParams = CoreParams(robEntries = 32, commitWidth = 2, scalarLsu = lsuParams)

  val io = IO(new ScalarLSUMDBPathProbeIO)
  val mdb = Module(new ScalarLSUMDBPath(coreParams))

  private def id(value: UInt): ROBID = {
    val out = Wire(new ROBID(coreParams.robEntries))
    out.valid := true.B
    out.wrap := false.B
    out.value := value
    out
  }

  val loadRows = Wire(chiselTypeOf(mdb.io.loadRows))
  loadRows := 0.U.asTypeOf(loadRows)
  loadRows(0).valid := io.loadValid
  loadRows(0).status := Mux(io.loadResolved, LoadInflightStatus.Resolved, LoadInflightStatus.Wait)
  loadRows(0).loadId.valid := true.B
  loadRows(0).loadId.wrap := false.B
  loadRows(0).loadId.value := 0.U
  loadRows(0).bid := id(io.loadBid)
  loadRows(0).gid := id(0.U)
  loadRows(0).rid := id(1.U)
  loadRows(0).loadLsId := id(io.loadLsId)
  loadRows(0).peId := 0.U
  loadRows(0).stid := 0.U
  loadRows(0).tid := 0.U
  loadRows(0).pc := io.loadPc
  loadRows(0).addr := io.loadAddr
  loadRows(0).size := io.loadSize
  loadRows(0).isTile := false.B
  loadRows(0).waitStore := io.loadWaitStore
  loadRows(0).waitStoreInfo.valid := io.loadWaitStore
  loadRows(0).waitStoreInfo.storeIndex := 0.U
  loadRows(0).waitStoreInfo.storeId := id(io.storeBid)
  loadRows(0).waitStoreInfo.storeLsId := id(io.storeLsId)
  loadRows(0).waitStoreInfo.pc := io.storePc
  mdb.io.loadRows := loadRows
  mdb.io.resolvedRows := 0.U.asTypeOf(mdb.io.resolvedRows)

  val storeProbe = Wire(chiselTypeOf(mdb.io.storeProbe))
  storeProbe := 0.U.asTypeOf(storeProbe)
  storeProbe.valid := io.storeProbeValid
  storeProbe.addrOnly := io.storeAddrOnly
  storeProbe.isTile := false.B
  storeProbe.bid := id(io.storeBid)
  storeProbe.gid := id(0.U)
  storeProbe.rid := id(1.U)
  storeProbe.lsId := id(io.storeLsId)
  storeProbe.pc := io.storePc
  storeProbe.addr := io.storeAddr
  storeProbe.size := io.storeSize
  mdb.io.storeProbe := storeProbe
  mdb.io.storeProbeCommit := io.storeProbeValid && mdb.io.storeProbeReady

  val storeRows = Wire(chiselTypeOf(mdb.io.storeRows))
  storeRows := 0.U.asTypeOf(storeRows)
  storeRows(0).valid := true.B
  storeRows(0).storeIndex := 0.U
  storeRows(0).pc := io.storePc
  storeRows(0).bid := id(io.storeBid)
  storeRows(0).lsId := id(io.storeLsId)
  storeRows(0).stid := 0.U
  storeRows(0).addr := io.storeAddr
  storeRows(0).size := io.storeSize
  storeRows(0).addrReady := true.B
  storeRows(0).dataReady := true.B
  storeRows(0).isTile := false.B
  mdb.io.storeRows := storeRows

  val lookup = Wire(chiselTypeOf(mdb.io.loadLookup))
  lookup := 0.U.asTypeOf(lookup)
  lookup.bid := id(io.loadBid)
  lookup.gid := id(0.U)
  lookup.rid := id(1.U)
  lookup.loadLsId := id(io.loadLsId)
  lookup.pc := io.loadPc
  lookup.addr := io.loadAddr
  lookup.size := io.loadSize
  lookup.isTile := false.B
  mdb.io.loadLookupValid := io.lookupValid
  mdb.io.loadLookup := lookup
  mdb.io.flush := io.flush
  mdb.io.mutationAccepted := io.mutationAccepted
  mdb.io.recoveryReady := io.recoveryReady

  val oneCycleTimeout = Module(new LoadWaitStoreTimeout(
    liqEntries = lsuParams.liqEntries,
    idEntries = coreParams.robEntries,
    storeEntries = lsuParams.stqEntries,
    timeoutCycles = 1
  ))
  oneCycleTimeout.io.flush := io.flush
  oneCycleTimeout.io.rows := loadRows
  oneCycleTimeout.io.releaseAccepted := false.B

  val integratedLiq = Module(new LoadInflightQueue(
    liqEntries = lsuParams.liqEntries,
    idEntries = coreParams.robEntries,
    storeEntries = lsuParams.stqEntries,
    addrWidth = lsuParams.addrWidth,
    pcWidth = lsuParams.pcWidth,
    lineBytes = lsuParams.lineBytes,
    sizeWidth = lsuParams.loadSizeWidth,
    archRegWidth = lsuParams.archRegWidth,
    physRegWidth = lsuParams.physRegWidth,
    peIdWidth = lsuParams.peIdWidth,
    stidWidth = lsuParams.stidWidth,
    tidWidth = lsuParams.tidWidth
  ))
  val integratedMdb = Module(new ScalarLSUMDBPath(coreParams))

  integratedLiq.io.flush := io.flush
  integratedLiq.io.preciseFlush := 0.U.asTypeOf(integratedLiq.io.preciseFlush)
  val integratedAlloc = Wire(chiselTypeOf(integratedLiq.io.alloc))
  integratedAlloc := 0.U.asTypeOf(integratedAlloc)
  integratedAlloc.bid := id(io.loadBid)
  integratedAlloc.gid := id(0.U)
  integratedAlloc.rid := id(1.U)
  integratedAlloc.loadLsId := id(io.loadLsId)
  integratedAlloc.pc := io.loadPc
  integratedAlloc.addr := io.loadAddr
  integratedAlloc.size := io.loadSize
  integratedLiq.io.allocValid := io.integratedAllocValid
  integratedLiq.io.alloc := integratedAlloc
  integratedLiq.io.launchValid := false.B
  integratedLiq.io.launchIndex := 0.U
  integratedLiq.io.pickValid := false.B
  integratedLiq.io.pickIndex := 0.U
  integratedLiq.io.scbReturnValid := false.B
  integratedLiq.io.scbReturnIndex := 0.U
  integratedLiq.io.markResolvedValid := false.B
  integratedLiq.io.markResolvedIndex := 0.U
  integratedLiq.io.e2Stores := 0.U.asTypeOf(integratedLiq.io.e2Stores)
  integratedLiq.io.e2BaseData := 0.U
  integratedLiq.io.e2BaseValidMask := 0.U
  integratedLiq.io.e2LoadDataReturned := false.B
  integratedLiq.io.e2ScbReturned := false.B
  integratedLiq.io.e2StqReturned := false.B
  integratedLiq.io.e2ReturnReady := false.B
  integratedLiq.io.replayWakeValid := false.B
  integratedLiq.io.replayWake := 0.U.asTypeOf(integratedLiq.io.replayWake)
  integratedLiq.io.refillValid := false.B
  integratedLiq.io.refill := 0.U.asTypeOf(integratedLiq.io.refill)
  integratedLiq.io.clearResolvedValid := false.B
  integratedLiq.io.clearResolvedIndex := 0.U

  val integratedSeedWait = Wire(chiselTypeOf(integratedLiq.io.rowMutationNextWaitStoreInfo))
  integratedSeedWait := 0.U.asTypeOf(integratedSeedWait)
  integratedSeedWait.valid := io.integratedSeedWaitValid
  integratedSeedWait.storeIndex := 0.U
  integratedSeedWait.storeId := id(io.storeBid)
  integratedSeedWait.storeLsId := id(io.storeLsId)
  integratedSeedWait.pc := io.storePc
  val integratedSeedSelected = io.integratedSeedWaitValid
  integratedLiq.io.rowMutationValid :=
    integratedSeedSelected || integratedMdb.io.mutationValid
  integratedLiq.io.rowMutationTargetIndex :=
    Mux(integratedSeedSelected, 0.U, integratedMdb.io.mutationTargetIndex)
  integratedLiq.io.rowMutationSetWaitStatus :=
    Mux(integratedSeedSelected, true.B, integratedMdb.io.mutationSetWaitStatus)
  integratedLiq.io.rowMutationKeepRepickStatus := false.B
  integratedLiq.io.rowMutationClearReturnState :=
    Mux(integratedSeedSelected, true.B, integratedMdb.io.mutationClearReturnState)
  integratedLiq.io.rowMutationLineWrite :=
    Mux(integratedSeedSelected, true.B, integratedMdb.io.mutationLineWrite)
  integratedLiq.io.rowMutationWaitStoreWrite :=
    Mux(integratedSeedSelected, true.B, integratedMdb.io.mutationWaitStoreWrite)
  integratedLiq.io.rowMutationNextWaitStore :=
    Mux(integratedSeedSelected, true.B, integratedMdb.io.mutationNextWaitStore)
  integratedLiq.io.rowMutationNextWaitStoreInfo :=
    Mux(integratedSeedSelected, integratedSeedWait, integratedMdb.io.mutationNextWaitStoreInfo)
  integratedLiq.io.rowMutationNextLineData := 0.U
  integratedLiq.io.rowMutationNextValidMask := 0.U
  integratedLiq.io.rowMutationNextDataComplete := false.B
  integratedLiq.io.rowMutationNextScbReturned := false.B
  integratedLiq.io.rowMutationNextStqReturned := false.B
  integratedLiq.io.rowMutationNextStoreSourceReturned := false.B
  integratedLiq.io.rowMutationAllowWaitTarget := true.B
  integratedLiq.io.rowMutationRequireScbReturned := false.B

  val integratedStoreProbe = Wire(chiselTypeOf(integratedMdb.io.storeProbe))
  integratedStoreProbe := storeProbe
  integratedStoreProbe.valid := io.integratedTrainValid
  integratedStoreProbe.addrOnly := false.B
  integratedMdb.io.flush := io.flush
  integratedMdb.io.storeProbe := integratedStoreProbe
  integratedMdb.io.storeProbeCommit :=
    io.integratedTrainValid && integratedMdb.io.storeProbeReady
  integratedMdb.io.storeRows := storeRows
  integratedMdb.io.loadLookupValid := false.B
  integratedMdb.io.loadLookup := lookup
  integratedMdb.io.loadRows := integratedLiq.io.rows
  val integratedResolvedRows = Wire(chiselTypeOf(integratedMdb.io.resolvedRows))
  integratedResolvedRows := 0.U.asTypeOf(integratedResolvedRows)
  integratedResolvedRows(0).valid := true.B
  integratedResolvedRows(0).peId := 0.U
  integratedResolvedRows(0).stid := 0.U
  integratedResolvedRows(0).tid := 0.U
  integratedResolvedRows(0).bid := id(io.loadBid)
  integratedResolvedRows(0).gid := id(0.U)
  integratedResolvedRows(0).rid := id(1.U)
  integratedResolvedRows(0).lsId := id(io.loadLsId)
  integratedResolvedRows(0).pc := io.loadPc
  integratedResolvedRows(0).addr := io.loadAddr
  integratedResolvedRows(0).size := io.loadSize
  integratedMdb.io.resolvedRows := integratedResolvedRows
  integratedMdb.io.mutationAccepted :=
    !integratedSeedSelected && integratedLiq.io.rowMutationApplyValid
  integratedMdb.io.recoveryReady := true.B

  io.storeProbeReady := mdb.io.storeProbeReady
  io.lookupReady := mdb.io.loadLookupReady
  io.conflictValid := mdb.io.conflictValid
  io.innerFlush := mdb.io.conflictFlush.req.valid &&
    (mdb.io.conflictFlush.req.typ === FlushType.InnerFlush)
  io.nukeFlush := mdb.io.conflictFlush.req.valid &&
    (mdb.io.conflictFlush.req.typ === FlushType.NukeFlush)
  io.recoveryValid := mdb.io.recoveryValid
  io.recoveryInnerFlush := mdb.io.recoveryValid &&
    (mdb.io.recoveryFlush.req.typ === FlushType.InnerFlush)
  io.recoveryNukeFlush := mdb.io.recoveryValid &&
    (mdb.io.recoveryFlush.req.typ === FlushType.NukeFlush)
  io.recoveryAccepted := mdb.io.recoveryAccepted
  io.recoveryPending := mdb.io.recoveryPending
  io.waitStoreMask := mdb.io.conflictWaitStoreMask
  io.recordAccepted := mdb.io.recordAccepted
  io.recordProcessed := mdb.io.recordProcessed
  io.bmdbReportValid := mdb.io.bmdbReportValid
  io.ssitValidMask := mdb.io.ssitValidMask
  io.lookupAccepted := mdb.io.lookupAccepted
  io.lookupProcessed := mdb.io.lookupProcessed
  io.lookupHit := mdb.io.lookupHit
  io.mutationValid := mdb.io.mutationValid
  io.mutationTargetIndex := mdb.io.mutationTargetIndex
  io.lookupWaitMutation := mdb.io.lookupWaitMutation
  io.failedWaitReleaseValid := mdb.io.failedWaitReleaseValid
  io.failedWaitReleaseAccepted := mdb.io.failedWaitReleaseAccepted
  io.deleteAccepted := mdb.io.deleteAccepted
  io.deleteProcessed := mdb.io.deleteProcessed
  io.deleteMatched := mdb.io.deleteMatched
  io.deleteDroppedBelowStall := mdb.io.deleteDroppedBelowStall
  io.deleteReleased := mdb.io.deleteReleased
  io.oneCycleTimeoutValid := oneCycleTimeout.io.releaseValid
  io.integratedAllocAccepted := integratedLiq.io.allocAccepted
  io.integratedTrainReady := integratedMdb.io.storeProbeReady
  io.integratedTrainAccepted := integratedMdb.io.recordAccepted
  io.integratedRecordProcessed := integratedMdb.io.recordProcessed
  io.integratedSeedWaitAccepted :=
    integratedSeedSelected && integratedLiq.io.rowMutationApplyValid
  io.integratedWaitStoreMask := integratedLiq.io.waitStoreMask
  io.integratedFailedWaitReleaseValid := integratedMdb.io.failedWaitReleaseValid
  io.integratedFailedWaitReleaseAccepted := integratedMdb.io.failedWaitReleaseAccepted
  io.integratedDeleteAccepted := integratedMdb.io.deleteAccepted
  io.integratedDeleteProcessed := integratedMdb.io.deleteProcessed
  io.integratedDeleteMatched := integratedMdb.io.deleteMatched
  io.integratedProtocolError := integratedMdb.io.protocolError
  io.protocolError := mdb.io.protocolError
}

object EmitScalarLSUMDBPathProbe extends App {
  circt.stage.ChiselStage.emitSystemVerilogFile(
    new ScalarLSUMDBPathProbe,
    args = Array("--target-dir", "../generated/chisel-verilog/scalar-lsu-mdb-path-probe"),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
}
