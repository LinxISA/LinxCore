package linxcore.lsu

import chisel3._

import linxcore.bctrl.BID
import linxcore.common.{CoreParams, ScalarLsuParams}
import linxcore.recovery.{
  FullBidFlushReq,
  RecoveryCleanupControl,
  RecoveryCleanupIntent,
  RecoveryEligibilityControl,
  RingFullBidRecoveryBridge
}
import linxcore.rob.{ROBFullBidLookupRequest, ROBFullBidLookupResult, ROBID}

class ScalarLSURecoveryControlIO(val coreParams: CoreParams, val p: ScalarLsuParams) extends Bundle {
  val fullReq = Input(new FullBidFlushReq(
    coreParams.robEntries,
    BID.DefaultWidth,
    p.peIdWidth,
    p.stidWidth,
    p.tidWidth
  ))
  val fullReqReady = Output(Bool())
  val oldestValid = Input(Bool())
  val oldestBid = Input(new ROBID(coreParams.robEntries))
  val oldestRid = Input(new ROBID(coreParams.robEntries))
  val fullBidLookupRequest = Output(new ROBFullBidLookupRequest(
    coreParams.robEntries,
    p.peIdWidth,
    p.stidWidth,
    p.tidWidth
  ))
  val fullBidLookup = Input(new ROBFullBidLookupResult(
    coreParams.robEntries,
    BID.DefaultWidth,
    p.peIdWidth,
    p.stidWidth,
    p.tidWidth
  ))
  val intentReady = Input(Bool())
  val intent = Output(new RecoveryCleanupIntent(
    coreParams.robEntries,
    BID.DefaultWidth,
    p.peIdWidth,
    p.stidWidth,
    p.tidWidth
  ))
  val sourcePending = Output(Bool())
  val sourceEligible = Output(Bool())
  val sourceAccepted = Output(Bool())
  val sourceBlockedByNoOldest = Output(Bool())
  val sourceBlockedByAge = Output(Bool())
  val sourceLookupMatched = Output(Bool())
  val sourceBlockedByLookupMiss = Output(Bool())
  val sourceBlockedByStaleLookup = Output(Bool())
  val sourceBlockedByRingProjection = Output(Bool())
  val cleanupPending = Output(Bool())
}

class ScalarLSUIO(val coreParams: CoreParams, val lsuParams: ScalarLsuParams) extends Bundle {
  val store = ScalarLSU.storePathIO(coreParams, lsuParams)
  val load = new ScalarLSULoadPathIO(coreParams, lsuParams)
  val recovery = new ScalarLSURecoveryControlIO(coreParams, lsuParams)
}

class ScalarLSU(val coreParams: CoreParams = CoreParams()) extends Module {
  private val lsuParams = coreParams.scalarLsu
  val io = IO(new ScalarLSUIO(coreParams, lsuParams))

  val storeCommitPath = Module(ScalarLSU.storeCommitPath(coreParams, lsuParams))
  val loadPath = Module(new ScalarLSULoadPath(coreParams))
  val recoveryEligibility = Module(new RecoveryEligibilityControl(
    coreParams.robEntries,
    lsuParams.peIdWidth,
    lsuParams.stidWidth,
    lsuParams.tidWidth
  ))
  val recoveryCleanup = Module(new RecoveryCleanupControl(
    coreParams.robEntries,
    BID.DefaultWidth,
    lsuParams.peIdWidth,
    lsuParams.stidWidth,
    lsuParams.tidWidth
  ))
  val ringFullBidBridge = Module(new RingFullBidRecoveryBridge(
    coreParams.robEntries,
    BID.DefaultWidth,
    lsuParams.peIdWidth,
    lsuParams.stidWidth,
    lsuParams.tidWidth
  ))
  storeCommitPath.io <> io.store
  loadPath.io <> io.load

  recoveryEligibility.io.request := loadPath.recovery.flush
  recoveryEligibility.io.oldestValid := io.recovery.oldestValid
  recoveryEligibility.io.oldestBid := io.recovery.oldestBid
  recoveryEligibility.io.oldestRid := io.recovery.oldestRid

  val pendingRingReq = Wire(chiselTypeOf(loadPath.recovery.flush))
  pendingRingReq := loadPath.recovery.flush
  pendingRingReq.req.valid := loadPath.recovery.valid
  ringFullBidBridge.io.ringReq := pendingRingReq
  ringFullBidBridge.io.lookupResult := io.recovery.fullBidLookup
  io.recovery.fullBidLookupRequest := ringFullBidBridge.io.lookupRequest

  val recoveredFullReq = Wire(chiselTypeOf(ringFullBidBridge.io.fullReq))
  recoveredFullReq := ringFullBidBridge.io.fullReq
  recoveredFullReq.valid :=
    ringFullBidBridge.io.fullReq.valid && recoveryEligibility.io.eligible
  recoveryCleanup.io.req := Mux(
    io.recovery.fullReq.valid,
    io.recovery.fullReq,
    recoveredFullReq
  )
  recoveryCleanup.io.ringReq := 0.U.asTypeOf(recoveryCleanup.io.ringReq)
  recoveryCleanup.io.intentReady := io.recovery.intentReady
  loadPath.recovery.ready :=
    recoveryEligibility.io.eligible &&
      ringFullBidBridge.io.matched &&
      !io.recovery.fullReq.valid &&
      recoveryCleanup.io.reqReady

  io.recovery.fullReqReady := recoveryCleanup.io.reqReady
  io.recovery.intent := recoveryCleanup.io.intent
  io.recovery.sourcePending := loadPath.recovery.pending
  io.recovery.sourceEligible := recoveryEligibility.io.eligible
  io.recovery.sourceAccepted := loadPath.recovery.accepted
  io.recovery.sourceBlockedByNoOldest := recoveryEligibility.io.blockedByNoOldest
  io.recovery.sourceBlockedByAge := recoveryEligibility.io.blockedByAge
  io.recovery.sourceLookupMatched := ringFullBidBridge.io.matched
  io.recovery.sourceBlockedByLookupMiss := ringFullBidBridge.io.blockedByLookupMiss
  io.recovery.sourceBlockedByStaleLookup := ringFullBidBridge.io.blockedByStaleResult
  io.recovery.sourceBlockedByRingProjection := ringFullBidBridge.io.blockedByRingProjection
  io.recovery.cleanupPending := recoveryCleanup.io.pending

  val storeCarriesAddress = io.store.insert.storeType =/= STQStoreType.Data
  val storeMdbPermit = !storeCarriesAddress || loadPath.mdbStore.probeReady
  storeCommitPath.io.insertValid := io.store.insertValid && storeMdbPermit
  io.store.insertReady := storeCommitPath.io.insertReady && storeMdbPermit

  val storeProbe = Wire(chiselTypeOf(loadPath.mdbStore.probe))
  storeProbe := 0.U.asTypeOf(storeProbe)
  storeProbe.valid := io.store.insertValid && storeCarriesAddress
  storeProbe.addrOnly := io.store.insert.storeType === STQStoreType.Addr
  storeProbe.isTile := !io.store.insert.scalarIex
  storeProbe.peId := io.store.insert.peId
  storeProbe.stid := io.store.insert.stid
  storeProbe.tid := io.store.insert.tid
  storeProbe.bid := io.store.insert.bid
  storeProbe.gid := io.store.insert.gid
  storeProbe.rid := io.store.insert.rid
  storeProbe.lsId := io.store.insert.lsId
  storeProbe.pc := io.store.insert.pc
  storeProbe.addr := io.store.insert.addr
  storeProbe.size := io.store.insert.size.pad(lsuParams.loadSizeWidth)
  loadPath.mdbStore.probe := storeProbe
  loadPath.mdbStore.probeCommit := storeCommitPath.io.insertAccepted && storeCarriesAddress

  for (idx <- 0 until lsuParams.stqEntries) {
    val row = storeCommitPath.io.stqRows(idx)
    val mdbRow = Wire(chiselTypeOf(loadPath.mdbStore.rows(idx)))
    mdbRow := 0.U.asTypeOf(mdbRow)
    mdbRow.valid := row.valid
    mdbRow.storeIndex := idx.U
    mdbRow.pc := row.pc
    mdbRow.bid := row.bid
    mdbRow.lsId := row.lsId
    mdbRow.stid := row.stid
    mdbRow.addr := row.addr
    mdbRow.size := row.size.pad(lsuParams.loadSizeWidth)
    mdbRow.addrReady := row.addrReady
    mdbRow.dataReady := row.dataReady
    mdbRow.isTile := !row.scalarIex
    loadPath.mdbStore.rows(idx) := mdbRow
  }
}

object ScalarLSU {
  def storePathIO(coreParams: CoreParams, lsuParams: ScalarLsuParams): STQSCBCommitPathIO =
    new STQSCBCommitPathIO(
      entries = lsuParams.stqEntries,
      queueEntries = lsuParams.commitQueueEntries,
      issueWidth = lsuParams.commitIssueWidth,
      scbEntries = lsuParams.scbEntries,
      scbResponseBufferDepth = lsuParams.scbResponseBufferDepth,
      addrWidth = lsuParams.addrWidth,
      dataWidth = lsuParams.dataWidth,
      peIdWidth = lsuParams.peIdWidth,
      stidWidth = lsuParams.stidWidth,
      tidWidth = lsuParams.tidWidth,
      sizeWidth = lsuParams.sizeWidth,
      simtLaneWidth = lsuParams.simtLaneWidth,
      lineBytes = lsuParams.lineBytes,
      mapQDepth = lsuParams.mapQDepth,
      robEntries = coreParams.robEntries
    )

  def storeCommitPath(coreParams: CoreParams, lsuParams: ScalarLsuParams): STQSCBCommitPath =
    new STQSCBCommitPath(
      entries = lsuParams.stqEntries,
      queueEntries = lsuParams.commitQueueEntries,
      issueWidth = lsuParams.commitIssueWidth,
      scbEntries = lsuParams.scbEntries,
      scbResponseBufferDepth = lsuParams.scbResponseBufferDepth,
      addrWidth = lsuParams.addrWidth,
      dataWidth = lsuParams.dataWidth,
      peIdWidth = lsuParams.peIdWidth,
      stidWidth = lsuParams.stidWidth,
      tidWidth = lsuParams.tidWidth,
      sizeWidth = lsuParams.sizeWidth,
      simtLaneWidth = lsuParams.simtLaneWidth,
      lineBytes = lsuParams.lineBytes,
      mapQDepth = lsuParams.mapQDepth,
      robEntries = coreParams.robEntries
    )
}
