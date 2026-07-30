package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, Valid, log2Ceil}

import linxcore.common.CoreParams
import linxcore.lsu._
import linxcore.recovery.FlushBus
import linxcore.rob.ROBID

/** External memory/control boundary of the canonical scalar load/store path.
  *
  * OOO load allocation, attempt launch, terminal completion, and all three
  * STQ forwarding lanes are deliberately absent from this boundary because
  * [[OooIexScalarLoadStorePath]] closes them internally.
  */
class OooIexScalarLoadStorePathIO(
    val p: OooParams,
    val coreParams: CoreParams,
    val stqEntries: Int) extends Bundle {
  private val lsu = coreParams.scalarLsu
  private val liqIndexWidth = log2Ceil(lsu.liqEntries)

  val agu = Flipped(Vec(3, Decoupled(new OooIexAguLoadRequest(p))))
  val storeReserve = Flipped(Decoupled(new OooIexIssueRow(p)))
  val storeAddress = Flipped(Vec(2,
    Decoupled(new OooIexExecuteTransaction(p))))
  val storeData = Flipped(Vec(2,
    Decoupled(new OooIexExecuteTransaction(p))))

  val rebind = Flipped(Decoupled(
    new OooIexLoadTerminalMetadataRebind(p, coreParams)))
  val launch = Flipped(Decoupled(UInt(liqIndexWidth.W)))
  val pick = Flipped(Decoupled(UInt(liqIndexWidth.W)))
  val scbReturn = Flipped(Decoupled(UInt(liqIndexWidth.W)))

  val replayWake = Flipped(Valid(new LoadReplayWakeupRequest(
    coreParams.robEntries, lsu.addrWidth, lsu.pcWidth, lsu.lineBytes,
    coreParams.lsidWidth)))
  val refill = Flipped(Decoupled(new LoadRefillWakeupRequest(
    lsu.addrWidth, lsu.lineBytes)))
  val missRequest = Decoupled(new LoadMissLowerRequest(
    lsu.loadMissQueueEntries, lsu.addrWidth))
  val missResponse = Flipped(Decoupled(new LoadMissLowerResponse(
    lsu.loadMissQueueEntries, lsu.addrWidth, lsu.lineBytes)))
  val resolveRetireValid = Input(Bool())
  val resolveRetireBid = Input(new ROBID(coreParams.robEntries))
  val resolveRetireLsId = Input(new ROBID(coreParams.robEntries))
  val resolveRetireLsIdFullValid = Input(Bool())
  val resolveRetireLsIdFull = Input(UInt(coreParams.lsidWidth.W))

  val l1dEviction = Decoupled(new ScalarL1DEviction(
    lsu.addrWidth, lsu.lineBytes))
  val scbSource = Input(new LoadSourceLine(lsu.lineBytes))
  val scbCacheUpdate = Input(new SCBDCacheUpdate(
    lsu.scbEntries, lsu.addrWidth, lsu.lineBytes))
  val scbLookupValid = Input(Bool())
  val scbLookupLineAddr = Input(UInt(lsu.addrWidth.W))
  val scbGrantWriteValid = Input(Bool())
  val scbGrantWriteLineAddr = Input(UInt(lsu.addrWidth.W))

  val robLookupValid = Output(Bool())
  val robLookupPeId = Output(UInt(lsu.peIdWidth.W))
  val robLookupStid = Output(UInt(lsu.stidWidth.W))
  val robLookupTid = Output(UInt(lsu.tidWidth.W))
  val robLookupBid = Output(new ROBID(coreParams.robEntries))
  val robLookupGid = Output(new ROBID(coreParams.robEntries))
  val robLookupRid = Output(new ROBID(coreParams.robEntries))
  val robLookupLoadLsId = Output(new ROBID(coreParams.robEntries))
  val robLookupLoadLsIdFullValid = Output(Bool())
  val robLookupLoadLsIdFull = Output(UInt(coreParams.lsidWidth.W))
  val robLookupAttempt = Output(new LoadAttemptIdentity)
  val robRowValid = Input(Bool())
  val robRowNeedFlush = Input(Bool())
  val result = Decoupled(new OooIexLoadResult(p))
  val speculativeWakeup = Output(Vec(3, Valid(new OooIexWakeup(p))))
  val loadCancel = Output(Vec(p.iexLoadCancelPorts,
    Valid(new OooIexLoadCancel(p))))
  val loadBypass = Output(Vec(3, Valid(new OooIexBypassCandidate(p))))

  // One central transaction presents both owner-native projections.  Prepare
  // is side-effect free; only recoveryFire applies either projection.
  val recoveryPrepare = Flipped(Valid(new OooResidencyRecoveryPlan(p)))
  val lsuRecoveryProjection = Input(new FlushBus(
    coreParams.robEntries, lsu.peIdWidth, lsu.stidWidth, lsu.tidWidth,
    coreParams.lsidWidth))
  val recoveryPrepareReady = Output(Bool())
  val recoveryRejected = Output(Bool())
  val recoveryFire = Input(Bool())
  val hardFlush = Input(Bool())

  // Structural uncertainty is retained until a later retry/cancel policy
  // accepts it.  Keeping this typed port visible is fail-closed; it is not a
  // hidden cache miss or an unconditional drop.
  val hardBlock = Decoupled(new STQLoadForwardResponse(
    p.robGroupsPerStid, stqEntries, stidWidth = p.stidWidth,
    lsidWidth = p.lsidWidth, tokenWidth = p.transactionIdWidth))

  val markCommitValid = Input(Bool())
  val markCommitIndex = Input(UInt(log2Ceil(stqEntries).W))
  val markCommitAccepted = Output(Bool())
  val commitFreeMaskValid = Input(Bool())
  val commitFreeMask = Input(UInt(stqEntries.W))
  val commitFreeAcceptedMask = Output(UInt(stqEntries.W))

  val mdbRecoveryReady = Input(Bool())
  val mdbRecoveryValid = Output(Bool())
  val mdbRecovery = Output(new FlushBus(
    coreParams.robEntries, lsu.peIdWidth, lsu.stidWidth, lsu.tidWidth,
    coreParams.lsidWidth))

  val allocAccepted = Output(Bool())
  val launchAccepted = Output(Bool())
  val liqOccupiedMask = Output(UInt(lsu.liqEntries.W))
  val stqOccupiedMask = Output(UInt(stqEntries.W))
  val forwardingOccupied = Output(UInt(3.W))
  val empty = Output(Bool())
  val protocolError = Output(Bool())
}

/** Canonical scalar load/store data path shared by production OOO and LSU.
  *
  * The module owns exactly one OOO terminal-metadata sidecar, one canonical
  * LIQ/load-return path, and one canonical STQ.  An accepted AGU request is
  * atomically allocated in LIQ plus metadata; a selected LIQ launch is
  * atomically reported back to the metadata owner; and W2 completion is one
  * atomic event across LSU resolve/writeback/wakeup and OOO terminal result.
  * The three STQ query/response lanes never cross this module boundary.
  */
class OooIexScalarLoadStorePath(
    val p: OooParams = OooParams(),
    val coreParams: CoreParams = OooIexCanonicalLoadOwnership.defaultCoreParams(
      OooParams()),
    val stqEntries: Int = 16) extends Module {
  private val lsu = coreParams.scalarLsu
  require(lsu.stqEntries == stqEntries,
    "canonical scalar load and store owners must share one STQ capacity")
  require(lsu.loadReturnPipeCount == 3,
    "production scalar load/store path has exactly three load pipes")
  require(p.iexLoadCancelPorts >= 3,
    "production scalar load/store path needs one cancel port per load pipe")
  require(p.robGroupsPerStid <= coreParams.robEntries,
    "OOO grouped RID slots must fit the canonical LSU identity domain")
  require(p.lsidWidth <= coreParams.lsidWidth,
    "OOO full LSID must fit the canonical LSU ordering domain")

  val io = IO(new OooIexScalarLoadStorePathIO(
    p, coreParams, stqEntries))

  val ownership = Module(new OooIexCanonicalLoadOwnership(
    p, coreParams, laneCount = 3))
  val loadPath = Module(new ScalarLSULoadPath(
    coreParams,
    useExternalStqForwarding = true,
    stqForwardRobEntries = p.robGroupsPerStid,
    stqForwardTokenWidth = p.transactionIdWidth))
  val store = Module(new OooIexStoreStqFabric(p, stqEntries))
  val forwarding = loadPath.stqForward.get
  val recoveryPreparing = io.recoveryPrepare.valid

  ownership.io.agu <> io.agu
  store.io.reserve <> io.storeReserve
  store.io.storeAddress <> io.storeAddress
  store.io.storeData <> io.storeData

  loadPath.io.allocValid := ownership.io.liqAlloc.valid
  loadPath.io.alloc := ownership.io.liqAlloc.bits
  ownership.io.liqAlloc.ready := loadPath.io.allocReady
  ownership.io.liqAllocLoadId := loadPath.io.allocLoadId
  io.allocAccepted := ownership.io.allocAccepted &&
    loadPath.io.allocAccepted

  ownership.io.rebind <> io.rebind
  loadPath.io.attemptRebindValid := ownership.io.liqRebind.valid
  loadPath.io.attemptRebind := ownership.io.liqRebind.bits
  ownership.io.liqRebind.ready := loadPath.io.attemptRebindReady

  val launchRow = loadPath.io.liqRows(io.launch.bits)
  ownership.io.attemptLaunch.valid := io.launch.valid &&
    loadPath.io.launchReady
  ownership.io.attemptLaunch.bits.loadId :=
    LoadCanonicalRowIdentity.fromRobId(launchRow.loadId)
  ownership.io.attemptLaunch.bits.attempt := launchRow.attempt
  loadPath.io.launchValid := io.launch.valid &&
    ownership.io.attemptLaunchAccepted
  loadPath.io.launchIndex := io.launch.bits
  io.launch.ready := loadPath.io.launchReady &&
    ownership.io.attemptLaunchAccepted
  io.launchAccepted := loadPath.io.launchAccepted &&
    ownership.io.attemptLaunchAccepted

  loadPath.io.pickValid := io.pick.valid && !recoveryPreparing
  loadPath.io.pickIndex := io.pick.bits
  io.pick.ready := loadPath.io.pickReady && !recoveryPreparing
  loadPath.io.scbReturnValid := io.scbReturn.valid && !recoveryPreparing
  loadPath.io.scbReturnIndex := io.scbReturn.bits
  io.scbReturn.ready := loadPath.io.scbReturnReady && !recoveryPreparing

  ownership.io.completion.valid :=
    loadPath.io.loadReturn.completionCandidateValid
  ownership.io.completion.bits := loadPath.io.loadReturn.completion
  loadPath.io.loadReturn.resolveReady := ownership.io.completion.ready
  loadPath.io.loadReturn.writebackReady := ownership.io.completion.ready
  loadPath.io.loadReturn.wakeupReady := ownership.io.completion.ready
  ownership.io.result <> io.result
  io.speculativeWakeup := ownership.io.speculativeWakeup
  io.loadCancel.foreach(_ := 0.U.asTypeOf(io.loadCancel.head))
  for (lane <- 0 until 3) {
    io.loadCancel(lane) := ownership.io.loadCancel(lane)
  }
  io.loadBypass := ownership.io.loadBypass
  store.io.loadCancel := io.loadCancel

  for (pipe <- 0 until 3) {
    store.io.loadForwardQuery(pipe).valid := forwarding.queries(pipe).valid
    store.io.loadForwardQuery(pipe).bits := forwarding.queries(pipe).bits
    forwarding.queries(pipe).ready := store.io.loadForwardQuery(pipe).ready
    forwarding.responses(pipe).valid :=
      store.io.loadForwardResponse(pipe).valid
    forwarding.responses(pipe).bits :=
      store.io.loadForwardResponse(pipe).bits
    store.io.loadForwardResponse(pipe).ready :=
      forwarding.responses(pipe).ready
  }
  forwarding.scb := io.scbSource
  io.hardBlock <> forwarding.hardBlock

  private def widenRobId(target: ROBID, source: ROBID): Unit = {
    target.valid := source.valid
    target.wrap := source.wrap
    target.value := source.value
  }

  val lateProbe = store.io.lateStaProbe
  val lateCandidate = store.io.lateStaCandidate
  loadPath.mdbStore.probe := 0.U.asTypeOf(loadPath.mdbStore.probe)
  loadPath.mdbStore.probe.valid := lateCandidate.valid &&
    lateCandidate.bits.valid
  loadPath.mdbStore.probe.addrOnly := lateCandidate.bits.addrOnly
  loadPath.mdbStore.probe.isTile := lateCandidate.bits.isTile
  loadPath.mdbStore.probe.peId := lateCandidate.bits.peId
  loadPath.mdbStore.probe.stid := lateCandidate.bits.stid
  loadPath.mdbStore.probe.tid := lateCandidate.bits.tid
  widenRobId(loadPath.mdbStore.probe.bid, lateCandidate.bits.bid)
  widenRobId(loadPath.mdbStore.probe.gid, lateCandidate.bits.gid)
  widenRobId(loadPath.mdbStore.probe.rid, lateCandidate.bits.rid)
  widenRobId(loadPath.mdbStore.probe.lsId, lateCandidate.bits.lsId)
  loadPath.mdbStore.probe.lsIdFullValid :=
    lateCandidate.bits.lsIdFullValid
  loadPath.mdbStore.probe.lsIdFull := lateCandidate.bits.lsIdFull
  loadPath.mdbStore.probe.pc := lateCandidate.bits.pc
  loadPath.mdbStore.probe.addr := lateCandidate.bits.addr
  loadPath.mdbStore.probe.size := lateCandidate.bits.size
  loadPath.mdbStore.probeCommit := lateProbe.valid
  store.io.lateStaPermit := loadPath.mdbStore.probeReady

  for (index <- 0 until stqEntries) {
    val source = store.io.rows(index)
    val target = loadPath.mdbStore.rows(index)
    target := 0.U.asTypeOf(target)
    target.valid := source.valid
    target.storeIndex := index.U
    target.pc := source.pc
    widenRobId(target.bid, source.bid)
    widenRobId(target.lsId, source.lsId)
    target.lsIdFullValid := source.valid
    target.lsIdFull := source.lsIdFull
    target.stid := source.stid
    target.addr := source.addr
    target.size := source.size
    target.addrReady := source.addrReady
    target.dataReady := source.dataReady
    target.isTile := !source.scalarIex
  }

  loadPath.io.replayWakeValid := io.replayWake.valid && !recoveryPreparing
  loadPath.io.replayWake := io.replayWake.bits
  loadPath.io.refillValid := io.refill.valid && !recoveryPreparing
  loadPath.io.refill := io.refill.bits
  io.refill.ready := loadPath.io.refillReady && !recoveryPreparing
  io.missRequest.valid := loadPath.io.missRequestValid && !recoveryPreparing
  io.missRequest.bits := loadPath.io.missRequest
  loadPath.io.missRequestReady := io.missRequest.ready && !recoveryPreparing
  loadPath.io.missResponseValid := io.missResponse.valid && !recoveryPreparing
  loadPath.io.missResponse := io.missResponse.bits
  io.missResponse.ready := loadPath.io.missResponseReady && !recoveryPreparing
  loadPath.io.resolveRetireValid := io.resolveRetireValid && !recoveryPreparing
  loadPath.io.resolveRetireBid := io.resolveRetireBid
  loadPath.io.resolveRetireLsId := io.resolveRetireLsId
  loadPath.io.resolveRetireLsIdFullValid := io.resolveRetireLsIdFullValid
  loadPath.io.resolveRetireLsIdFull := io.resolveRetireLsIdFull

  io.l1dEviction.valid := loadPath.io.l1dEviction.valid
  io.l1dEviction.bits := loadPath.io.l1dEviction
  loadPath.io.l1dEvictionReady := io.l1dEviction.ready
  loadPath.scbCache.update := io.scbCacheUpdate
  loadPath.scbCache.lookupValid := io.scbLookupValid
  loadPath.scbCache.lookupLineAddr := io.scbLookupLineAddr
  loadPath.scbCache.grantWriteValid := io.scbGrantWriteValid
  loadPath.scbCache.grantWriteLineAddr := io.scbGrantWriteLineAddr
  io.robLookupValid := loadPath.io.loadReturn.robLookupValid
  io.robLookupPeId := loadPath.io.loadReturn.robLookupPeId
  io.robLookupStid := loadPath.io.loadReturn.robLookupStid
  io.robLookupTid := loadPath.io.loadReturn.robLookupTid
  io.robLookupBid := loadPath.io.loadReturn.robLookupBid
  io.robLookupGid := loadPath.io.loadReturn.robLookupGid
  io.robLookupRid := loadPath.io.loadReturn.robLookupRid
  io.robLookupLoadLsId := loadPath.io.loadReturn.robLookupLoadLsId
  io.robLookupLoadLsIdFullValid :=
    loadPath.io.loadReturn.robLookupLoadLsIdFullValid
  io.robLookupLoadLsIdFull := loadPath.io.loadReturn.robLookupLoadLsIdFull
  io.robLookupAttempt := loadPath.io.loadReturn.robLookupAttempt
  loadPath.io.loadReturn.robRowValid := io.robRowValid
  loadPath.io.loadReturn.robRowNeedFlush := io.robRowNeedFlush

  store.io.markCommitValid := io.markCommitValid
  store.io.markCommitIndex := io.markCommitIndex
  io.markCommitAccepted := store.io.markCommitAccepted
  store.io.commitFreeMaskValid := io.commitFreeMaskValid
  store.io.commitFreeMask := io.commitFreeMask
  io.commitFreeAcceptedMask := store.io.commitFreeAcceptedMask

  val oooLiqKill = Wire(Vec(lsu.liqEntries, Bool()))
  val lsuLiqKill = Wire(Vec(lsu.liqEntries, Bool()))
  for (index <- 0 until lsu.liqEntries) {
    val row = loadPath.io.liqRows(index)
    val producer = row.attempt.producer
    val member = Wire(new RobMemberKey(p))
    member := 0.U.asTypeOf(member)
    member.group.valid := producer.valid
    member.group.peId := producer.peId
    member.group.stid := producer.stid
    member.group.ridSlot := producer.ridSlot
    member.group.ridGeneration := producer.ridGeneration
    member.bid.valid := producer.nativeBidValid
    member.bid.value := producer.nativeBid
    member.brobGeneration := producer.brobGeneration
    member.memberIndex := producer.memberIndex
    member.residentGeneration := producer.residentGeneration
    oooLiqKill(index) := row.valid &&
      OooRecoveryMembership.memberKilled(
        p, io.recoveryPrepare.bits, member)

    val pruneRow = Wire(new STQFlushPruneEntry(
      coreParams.robEntries, lsu.peIdWidth, lsu.stidWidth,
      lsu.tidWidth, coreParams.lsidWidth))
    pruneRow := 0.U.asTypeOf(pruneRow)
    pruneRow.valid := row.valid
    pruneRow.status := STQEntryStatus.Wait
    pruneRow.peId := row.peId
    pruneRow.stid := row.stid
    pruneRow.tid := row.tid
    pruneRow.bid := row.bid
    pruneRow.gid := row.gid
    pruneRow.lsId := row.loadLsId
    pruneRow.lsIdFullValid := row.loadLsIdFullValid
    pruneRow.lsIdFull := row.loadLsIdFull
    lsuLiqKill(index) := STQFlushPrune.matchesFlush(
      io.lsuRecoveryProjection, pruneRow)
  }
  val liqProjectionExact = oooLiqKill.asUInt === lsuLiqKill.asUInt
  val nonLiqRecoveryStateEmptyNow = loadPath.io.resolveEmpty &&
    !loadPath.io.transferPending && loadPath.io.mdbTransientEmpty &&
    loadPath.io.loadReturn.empty &&
    (loadPath.io.loadReturn.reservedCount === 0.U) &&
    !loadPath.io.missQueueValidMask.orR &&
    (loadPath.io.missQueueReservations === 0.U) &&
    (loadPath.io.refillTransportCount === 0.U) &&
    forwarding.ownedStateEmpty && !forwarding.hardBlock.valid
  val nonLiqRecoveryStateEmptyD1 = RegNext(
    nonLiqRecoveryStateEmptyNow, false.B)
  val nonLiqRecoveryStateEmptyD2 = RegNext(
    nonLiqRecoveryStateEmptyD1, false.B)
  val recoveryPrepareD1 = RegNext(io.recoveryPrepare.valid, false.B)
  val recoveryPrepareD2 = RegNext(recoveryPrepareD1, false.B)
  val recoverySnapshotValid = recoveryPrepareD1 && recoveryPrepareD2
  val recoverySnapshotStateEmpty = nonLiqRecoveryStateEmptyD1 &&
    nonLiqRecoveryStateEmptyD2
  val lsuProjectionShapeExact = io.lsuRecoveryProjection.req.valid &&
    io.recoveryPrepare.bits.valid &&
    io.lsuRecoveryProjection.req.stid === io.recoveryPrepare.bits.oldHead.stid &&
    io.lsuRecoveryProjection.req.peId === io.recoveryPrepare.bits.oldHead.peId &&
    liqProjectionExact
  val allPrepared = ownership.io.recoveryPrepareReady &&
    store.io.recoveryPrepareReady && lsuProjectionShapeExact &&
    recoverySnapshotValid && recoverySnapshotStateEmpty
  val recoveryPrepared = RegInit(false.B)
  val preparedPlan = Reg(chiselTypeOf(io.recoveryPrepare.bits))
  val preparedLsuProjection = Reg(chiselTypeOf(io.lsuRecoveryProjection))
  io.recoveryPrepareReady := io.recoveryPrepare.valid &&
    (allPrepared || recoveryPrepared)
  val commonRecoveryFire = io.recoveryFire && io.recoveryPrepare.valid &&
    recoveryPrepared
  when(!io.recoveryPrepare.valid || commonRecoveryFire) {
    recoveryPrepared := false.B
  }.elsewhen(!recoveryPrepared && allPrepared) {
    recoveryPrepared := true.B
    preparedPlan := io.recoveryPrepare.bits
    preparedLsuProjection := io.lsuRecoveryProjection
  }
  val appliedOooRecovery = Wire(chiselTypeOf(io.recoveryPrepare))
  appliedOooRecovery := io.recoveryPrepare
  when(recoveryPrepared) {
    appliedOooRecovery.valid := true.B
    appliedOooRecovery.bits := preparedPlan
  }
  ownership.io.recoveryPrepare := appliedOooRecovery
  store.io.recoveryPrepare := appliedOooRecovery
  ownership.io.recoveryFire := commonRecoveryFire
  store.io.recoveryFire := commonRecoveryFire
  val appliedLsuRecovery = Wire(chiselTypeOf(io.lsuRecoveryProjection))
  appliedLsuRecovery := preparedLsuProjection
  appliedLsuRecovery.req.valid := commonRecoveryFire
  loadPath.io.preciseFlush := appliedLsuRecovery
  io.recoveryRejected := io.recoveryPrepare.valid &&
    (!ownership.io.recoveryPrepareReady || store.io.recoveryRejected ||
      !lsuProjectionShapeExact ||
      (recoverySnapshotValid && !recoverySnapshotStateEmpty))

  loadPath.io.flush := io.hardFlush
  ownership.io.flush := io.hardFlush
  loadPath.recovery.ready := io.mdbRecoveryReady && !recoveryPreparing
  io.mdbRecoveryValid := loadPath.recovery.valid
  io.mdbRecovery := loadPath.recovery.flush

  // Compatibility E2 sources are structurally absent in production mode.
  loadPath.io.e2Stores := 0.U.asTypeOf(loadPath.io.e2Stores)
  loadPath.io.e2ScbReturned := false.B
  loadPath.io.e2StqReturned := false.B

  io.liqOccupiedMask := loadPath.io.liqOccupiedMask
  io.stqOccupiedMask := store.io.occupiedMask
  io.forwardingOccupied := store.io.loadForwardOccupied
  io.empty := ownership.io.metadataEmpty && loadPath.io.empty && store.io.empty
  io.protocolError := forwarding.protocolError ||
    loadPath.io.allocAttemptMalformed ||
    loadPath.io.transferProtocolError ||
    loadPath.io.loadReturn.protocolError ||
    loadPath.io.mdbProtocolError ||
    loadPath.io.l1dProtocolError ||
    loadPath.io.missQueueProtocolError ||
    loadPath.io.refillTransportProtocolError ||
    ownership.io.metadataAllocRejected.valid ||
    ownership.io.metadataRebindRejected.valid ||
    ownership.io.attemptLaunchRejected.valid ||
    ownership.io.completionRejected.valid ||
    store.io.reserveRejected ||
    store.io.leaseLookupRejected.asUInt.orR ||
    store.io.fillConflict

  when(io.recoveryFire) {
    assert(io.recoveryPrepare.valid && recoveryPrepared,
      "scalar load/store recovery requires one commonly prepared owner set")
  }
  when(recoveryPrepared) {
    assert(io.recoveryPrepare.bits.asUInt === preparedPlan.asUInt &&
      io.lsuRecoveryProjection.asUInt === preparedLsuProjection.asUInt,
      "prepared scalar load/store recovery inputs must remain stable until fire")
  }
  when(loadPath.io.launchAccepted || ownership.io.attemptLaunchAccepted) {
    assert(loadPath.io.launchAccepted && ownership.io.attemptLaunchAccepted,
      "LIQ launch and OOO attempt publication must be atomic")
  }
  when(loadPath.io.loadReturn.resolveFire ||
      loadPath.io.loadReturn.writebackFire ||
      loadPath.io.loadReturn.wakeupFire) {
    assert(loadPath.io.loadReturn.resolveFire &&
      loadPath.io.loadReturn.writebackFire &&
      loadPath.io.loadReturn.wakeupFire,
      "scalar W2 resolve, writeback, and wakeup must fire atomically")
  }
}
