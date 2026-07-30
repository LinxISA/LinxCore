package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, Valid, log2Ceil}

import linxcore.common.CoreParams
import linxcore.lsu._
import linxcore.recovery.FlushBus
import linxcore.rob.ROBID

/** External memory/control boundary of the installed scalar load path. */
class OooIexScalarLoadExternalIO(
    val p: OooParams,
    val coreParams: CoreParams,
    val stqEntries: Int) extends Bundle {
  private val lsu = coreParams.scalarLsu
  private val liqIndexWidth = log2Ceil(lsu.liqEntries)

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
  val lsuRecoveryProjection = Input(new FlushBus(
    coreParams.robEntries, lsu.peIdWidth, lsu.stidWidth, lsu.tidWidth,
    coreParams.lsidWidth))
  val hardFlush = Input(Bool())

  // Structural uncertainty is retained until a later retry/cancel policy
  // accepts it.  Keeping this typed port visible is fail-closed; it is not a
  // hidden cache miss or an unconditional drop.
  val hardBlock = Decoupled(new STQLoadForwardResponse(
    p.robGroupsPerStid, stqEntries, stidWidth = p.stidWidth,
    lsidWidth = p.lsidWidth, tokenWidth = p.transactionIdWidth))

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

/** Private attachment to the one canonical STQ already owned by the
  * execution/store wrapper.  Forwarding and MDB capacity never escape that
  * production boundary.
  */
class OooIexScalarStoreAttachIO(
    val p: OooParams,
    val stqEntries: Int) extends Bundle {
  val forwardQuery = Vec(3, Decoupled(new STQLoadForwardQuery(
    p.robGroupsPerStid, stidWidth = p.stidWidth,
    lsidWidth = p.lsidWidth, tokenWidth = p.transactionIdWidth)))
  val forwardResponse = Flipped(Vec(3, Decoupled(
    new STQLoadForwardResponse(
      p.robGroupsPerStid, stqEntries, stidWidth = p.stidWidth,
      lsidWidth = p.lsidWidth, tokenWidth = p.transactionIdWidth))))
  val rows = Input(Vec(stqEntries, new STQEntryBankRow(
    p.robGroupsPerStid,
    peIdWidth = p.peIdWidth,
    stidWidth = p.stidWidth,
    tidWidth = p.stidWidth,
    mapQDepth = p.tuMapQDepthPerStid,
    lsidWidth = p.lsidWidth,
    nativeBidWidth = p.nativeBidWidth,
    ridGenerationWidth = p.ridGenerationWidth,
    brobGenerationWidth = p.brobGenerationWidth,
    memberIndexWidth = p.robMemberIndexWidth,
    residentGenerationWidth = p.residentGenerationWidth,
    leaseGenerationWidth = p.executeSlotGenerationWidth)))
  val lateStaProbe = Input(Valid(new MDBConflictStoreProbe(
    p.robGroupsPerStid, peIdWidth = p.peIdWidth,
    stidWidth = p.stidWidth, tidWidth = p.stidWidth,
    sizeWidth = 7, lsidWidth = p.lsidWidth)))
  val lateStaCandidate = Input(Valid(new MDBConflictStoreProbe(
    p.robGroupsPerStid, peIdWidth = p.peIdWidth,
    stidWidth = p.stidWidth, tidWidth = p.stidWidth,
    sizeWidth = 7, lsidWidth = p.lsidWidth)))
  val lateStaPermit = Output(Bool())
  val occupiedMask = Input(UInt(stqEntries.W))
  val forwardingOccupied = Input(UInt(3.W))
}

class OooIexScalarLoadStorePathIO(
    val p: OooParams,
    val coreParams: CoreParams,
    val stqEntries: Int) extends Bundle {
  val owner = Flipped(new OooIexCanonicalLoadPortIO(p, coreParams))
  val store = new OooIexScalarStoreAttachIO(p, stqEntries)
  val external = new OooIexScalarLoadExternalIO(p, coreParams, stqEntries)

  // One central transaction presents both owner-native projections. Prepare
  // is side-effect free; only recoveryFire applies the LSU projection.
  val recoveryPrepare = Flipped(Valid(new OooResidencyRecoveryPlan(p)))
  val recoveryPrepareReady = Output(Bool())
  val recoveryRejected = Output(Bool())
  val recoveryFire = Input(Bool())
}

/** Installed scalar load path shared by the existing OOO metadata owner and
  * canonical STQ.  This module owns LIQ/L1D/MDB/LRET exactly once and closes
  * the forwarding and prospective-MDB seams without allocating another OOO
  * metadata sidecar or STQ.
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

  val loadPath = Module(new ScalarLSULoadPath(
    coreParams,
    useExternalStqForwarding = true,
    stqForwardRobEntries = p.robGroupsPerStid,
    stqForwardTokenWidth = p.transactionIdWidth,
    useExternalLaunchPermit = true))
  val forwarding = loadPath.stqForward.get
  val recoveryPreparing = io.recoveryPrepare.valid

  loadPath.io.allocValid := io.owner.liqAlloc.valid
  loadPath.io.alloc := io.owner.liqAlloc.bits
  io.owner.liqAlloc.ready := loadPath.io.allocReady
  io.owner.liqAllocLoadId := loadPath.io.allocLoadId
  io.external.allocAccepted := io.owner.liqAlloc.fire &&
    loadPath.io.allocAccepted

  io.owner.rebind <> io.external.rebind
  loadPath.io.attemptRebindValid := io.owner.liqRebind.valid
  loadPath.io.attemptRebind := io.owner.liqRebind.bits
  io.owner.liqRebind.ready := loadPath.io.attemptRebindReady

  val launchRow = loadPath.io.liqRows(io.external.launch.bits)
  io.owner.attemptLaunch.valid := io.external.launch.valid &&
    loadPath.io.launchReady
  io.owner.attemptLaunch.bits.loadId :=
    LoadCanonicalRowIdentity.fromRobId(launchRow.loadId)
  io.owner.attemptLaunch.bits.attempt := launchRow.attempt
  loadPath.io.launchValid := io.external.launch.valid
  loadPath.io.launchIndex := io.external.launch.bits
  loadPath.launchPermit.get := io.owner.attemptLaunchAccepted
  io.external.launch.ready := loadPath.io.launchReady &&
    io.owner.attemptLaunchAccepted
  io.external.launchAccepted := loadPath.io.launchAccepted &&
    io.owner.attemptLaunchAccepted

  loadPath.io.pickValid := io.external.pick.valid && !recoveryPreparing
  loadPath.io.pickIndex := io.external.pick.bits
  io.external.pick.ready := loadPath.io.pickReady && !recoveryPreparing
  loadPath.io.scbReturnValid := io.external.scbReturn.valid &&
    !recoveryPreparing
  loadPath.io.scbReturnIndex := io.external.scbReturn.bits
  io.external.scbReturn.ready := loadPath.io.scbReturnReady &&
    !recoveryPreparing

  io.owner.completion.valid :=
    loadPath.io.loadReturn.completionCandidateValid
  io.owner.completion.bits := loadPath.io.loadReturn.completion
  loadPath.io.loadReturn.resolveReady := io.owner.completion.ready
  loadPath.io.loadReturn.writebackReady := io.owner.completion.ready
  loadPath.io.loadReturn.wakeupReady := io.owner.completion.ready

  for (pipe <- 0 until 3) {
    io.store.forwardQuery(pipe).valid := forwarding.queries(pipe).valid
    io.store.forwardQuery(pipe).bits := forwarding.queries(pipe).bits
    forwarding.queries(pipe).ready := io.store.forwardQuery(pipe).ready
    forwarding.responses(pipe).valid :=
      io.store.forwardResponse(pipe).valid
    forwarding.responses(pipe).bits :=
      io.store.forwardResponse(pipe).bits
    io.store.forwardResponse(pipe).ready :=
      forwarding.responses(pipe).ready
  }
  forwarding.scb := io.external.scbSource
  io.external.hardBlock <> forwarding.hardBlock

  private def widenRobId(target: ROBID, source: ROBID): Unit = {
    target.valid := source.valid
    target.wrap := source.wrap
    target.value := source.value
  }

  val lateProbe = io.store.lateStaProbe
  val lateCandidate = io.store.lateStaCandidate
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
  io.store.lateStaPermit := loadPath.mdbStore.probeReady

  for (index <- 0 until stqEntries) {
    val source = io.store.rows(index)
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

  loadPath.io.replayWakeValid := io.external.replayWake.valid &&
    !recoveryPreparing
  loadPath.io.replayWake := io.external.replayWake.bits
  loadPath.io.refillValid := io.external.refill.valid && !recoveryPreparing
  loadPath.io.refill := io.external.refill.bits
  io.external.refill.ready := loadPath.io.refillReady && !recoveryPreparing
  io.external.missRequest.valid := loadPath.io.missRequestValid &&
    !recoveryPreparing
  io.external.missRequest.bits := loadPath.io.missRequest
  loadPath.io.missRequestReady := io.external.missRequest.ready &&
    !recoveryPreparing
  loadPath.io.missResponseValid := io.external.missResponse.valid &&
    !recoveryPreparing
  loadPath.io.missResponse := io.external.missResponse.bits
  io.external.missResponse.ready := loadPath.io.missResponseReady &&
    !recoveryPreparing
  loadPath.io.resolveRetireValid := io.external.resolveRetireValid &&
    !recoveryPreparing
  loadPath.io.resolveRetireBid := io.external.resolveRetireBid
  loadPath.io.resolveRetireLsId := io.external.resolveRetireLsId
  loadPath.io.resolveRetireLsIdFullValid :=
    io.external.resolveRetireLsIdFullValid
  loadPath.io.resolveRetireLsIdFull := io.external.resolveRetireLsIdFull

  io.external.l1dEviction.valid := loadPath.io.l1dEviction.valid
  io.external.l1dEviction.bits := loadPath.io.l1dEviction
  loadPath.io.l1dEvictionReady := io.external.l1dEviction.ready
  loadPath.scbCache.update := io.external.scbCacheUpdate
  loadPath.scbCache.lookupValid := io.external.scbLookupValid
  loadPath.scbCache.lookupLineAddr := io.external.scbLookupLineAddr
  loadPath.scbCache.grantWriteValid := io.external.scbGrantWriteValid
  loadPath.scbCache.grantWriteLineAddr :=
    io.external.scbGrantWriteLineAddr
  io.external.robLookupValid := loadPath.io.loadReturn.robLookupValid
  io.external.robLookupPeId := loadPath.io.loadReturn.robLookupPeId
  io.external.robLookupStid := loadPath.io.loadReturn.robLookupStid
  io.external.robLookupTid := loadPath.io.loadReturn.robLookupTid
  io.external.robLookupBid := loadPath.io.loadReturn.robLookupBid
  io.external.robLookupGid := loadPath.io.loadReturn.robLookupGid
  io.external.robLookupRid := loadPath.io.loadReturn.robLookupRid
  io.external.robLookupLoadLsId := loadPath.io.loadReturn.robLookupLoadLsId
  io.external.robLookupLoadLsIdFullValid :=
    loadPath.io.loadReturn.robLookupLoadLsIdFullValid
  io.external.robLookupLoadLsIdFull :=
    loadPath.io.loadReturn.robLookupLoadLsIdFull
  io.external.robLookupAttempt := loadPath.io.loadReturn.robLookupAttempt
  loadPath.io.loadReturn.robRowValid := io.external.robRowValid
  loadPath.io.loadReturn.robRowNeedFlush := io.external.robRowNeedFlush

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
      io.external.lsuRecoveryProjection, pruneRow)
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
  val lsuProjectionShapeExact = io.external.lsuRecoveryProjection.req.valid &&
    io.recoveryPrepare.bits.valid &&
    io.external.lsuRecoveryProjection.req.stid ===
      io.recoveryPrepare.bits.oldHead.stid &&
    io.external.lsuRecoveryProjection.req.peId ===
      io.recoveryPrepare.bits.oldHead.peId &&
    liqProjectionExact
  val allPrepared = lsuProjectionShapeExact &&
    recoverySnapshotValid && recoverySnapshotStateEmpty
  val recoveryPrepared = RegInit(false.B)
  val preparedPlan = Reg(chiselTypeOf(io.recoveryPrepare.bits))
  val preparedLsuProjection = Reg(chiselTypeOf(
    io.external.lsuRecoveryProjection))
  io.recoveryPrepareReady := io.recoveryPrepare.valid &&
    (allPrepared || recoveryPrepared)
  val commonRecoveryFire = io.recoveryFire && io.recoveryPrepare.valid &&
    recoveryPrepared
  when(!io.recoveryPrepare.valid || commonRecoveryFire) {
    recoveryPrepared := false.B
  }.elsewhen(!recoveryPrepared && allPrepared) {
    recoveryPrepared := true.B
    preparedPlan := io.recoveryPrepare.bits
    preparedLsuProjection := io.external.lsuRecoveryProjection
  }
  val appliedLsuRecovery = Wire(chiselTypeOf(io.external.lsuRecoveryProjection))
  appliedLsuRecovery := preparedLsuProjection
  appliedLsuRecovery.req.valid := commonRecoveryFire
  loadPath.io.preciseFlush := appliedLsuRecovery
  io.recoveryRejected := io.recoveryPrepare.valid &&
    (!lsuProjectionShapeExact ||
      (recoverySnapshotValid && !recoverySnapshotStateEmpty))

  loadPath.io.flush := io.external.hardFlush
  loadPath.recovery.ready := io.external.mdbRecoveryReady &&
    !recoveryPreparing
  io.external.mdbRecoveryValid := loadPath.recovery.valid
  io.external.mdbRecovery := loadPath.recovery.flush

  // Compatibility E2 sources are structurally absent in production mode.
  loadPath.io.e2Stores := 0.U.asTypeOf(loadPath.io.e2Stores)
  loadPath.io.e2ScbReturned := false.B
  loadPath.io.e2StqReturned := false.B

  io.external.liqOccupiedMask := loadPath.io.liqOccupiedMask
  io.external.stqOccupiedMask := io.store.occupiedMask
  io.external.forwardingOccupied := io.store.forwardingOccupied
  io.external.empty := loadPath.io.empty
  io.external.protocolError := forwarding.protocolError ||
    loadPath.io.allocAttemptMalformed ||
    loadPath.io.transferProtocolError ||
    loadPath.io.loadReturn.protocolError ||
    loadPath.io.mdbProtocolError ||
    loadPath.io.l1dProtocolError ||
    loadPath.io.missQueueProtocolError ||
    loadPath.io.refillTransportProtocolError

  when(io.recoveryFire) {
    assert(io.recoveryPrepare.valid && recoveryPrepared,
      "scalar load/store recovery requires one commonly prepared owner set")
  }
  when(recoveryPrepared) {
    assert(io.recoveryPrepare.bits.asUInt === preparedPlan.asUInt &&
      io.external.lsuRecoveryProjection.asUInt ===
        preparedLsuProjection.asUInt,
      "prepared scalar load/store recovery inputs must remain stable until fire")
  }
  when(loadPath.io.launchAccepted || io.owner.attemptLaunchAccepted) {
    assert(loadPath.io.launchAccepted && io.owner.attemptLaunchAccepted,
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
