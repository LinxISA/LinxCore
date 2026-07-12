package linxcore.lsu

import chisel3._

import linxcore.bctrl.BID
import linxcore.common.{CoreParams, ScalarLsuParams}
import linxcore.recovery.FullBidFlushReq
import linxcore.rob.{ROBFullBidLookupRequest, ROBFullBidLookupResult, ROBID}

class ScalarLSURecoverySourcePortIO(val coreParams: CoreParams, val p: ScalarLsuParams) extends Bundle {
  val source = Output(new FullBidFlushReq(
    coreParams.robEntries,
    BID.DefaultWidth,
    p.peIdWidth,
    p.stidWidth,
    p.tidWidth
  ))
  val sourceReady = Input(Bool())
  val oldestValid = Input(Vec(p.stidCount, Bool()))
  val oldestBid = Input(Vec(p.stidCount, new ROBID(coreParams.robEntries)))
  val oldestRid = Input(Vec(p.stidCount, new ROBID(coreParams.robEntries)))
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
  val sourcePending = Output(Bool())
  val sourceEligible = Output(Bool())
  val sourceAccepted = Output(Bool())
  val sourceBlockedByNoOldest = Output(Bool())
  val sourceBlockedByAge = Output(Bool())
  val sourceLookupMatched = Output(Bool())
  val sourceBlockedByLookupMiss = Output(Bool())
  val sourceBlockedByStaleLookup = Output(Bool())
  val sourceBlockedByRingProjection = Output(Bool())
  val sourceStidInRange = Output(Bool())
}

class ScalarLSUIO(val coreParams: CoreParams, val lsuParams: ScalarLsuParams) extends Bundle {
  val store = ScalarLSU.storePathIO(coreParams, lsuParams)
  val load = new ScalarLSULoadPathIO(coreParams, lsuParams)
  val recovery = new ScalarLSURecoverySourcePortIO(coreParams, lsuParams)
}

class ScalarLSU(val coreParams: CoreParams = CoreParams()) extends Module {
  private val lsuParams = coreParams.scalarLsu
  val io = IO(new ScalarLSUIO(coreParams, lsuParams))

  val storeCommitPath = Module(ScalarLSU.storeCommitPath(coreParams, lsuParams))
  val loadPath = Module(new ScalarLSULoadPath(coreParams))
  val recoveryBoundary = Module(new ScalarLSURecoveryBoundary(
    coreParams.robEntries,
    lsuParams.stidCount,
    BID.DefaultWidth,
    lsuParams.peIdWidth,
    lsuParams.stidWidth,
    lsuParams.tidWidth
  ))
  storeCommitPath.io <> io.store
  loadPath.io <> io.load

  val pendingRingReq = Wire(chiselTypeOf(loadPath.recovery.flush))
  pendingRingReq := loadPath.recovery.flush
  pendingRingReq.req.valid := loadPath.recovery.valid
  recoveryBoundary.io.ringReq := pendingRingReq
  recoveryBoundary.io.oldestValid := io.recovery.oldestValid
  recoveryBoundary.io.oldestBid := io.recovery.oldestBid
  recoveryBoundary.io.oldestRid := io.recovery.oldestRid
  recoveryBoundary.io.fullBidLookup := io.recovery.fullBidLookup
  recoveryBoundary.io.sourceReady := io.recovery.sourceReady
  loadPath.recovery.ready := recoveryBoundary.io.ringReqReady

  io.recovery.source := recoveryBoundary.io.source
  io.recovery.fullBidLookupRequest := recoveryBoundary.io.fullBidLookupRequest
  io.recovery.sourcePending := loadPath.recovery.pending
  io.recovery.sourceEligible := recoveryBoundary.io.eligible
  io.recovery.sourceAccepted := recoveryBoundary.io.sourceAccepted
  io.recovery.sourceBlockedByNoOldest := recoveryBoundary.io.blockedByNoOldest
  io.recovery.sourceBlockedByAge := recoveryBoundary.io.blockedByAge
  io.recovery.sourceLookupMatched := recoveryBoundary.io.lookupMatched
  io.recovery.sourceBlockedByLookupMiss := recoveryBoundary.io.blockedByLookupMiss
  io.recovery.sourceBlockedByStaleLookup := recoveryBoundary.io.blockedByStaleLookup
  io.recovery.sourceBlockedByRingProjection := recoveryBoundary.io.blockedByRingProjection
  io.recovery.sourceStidInRange := recoveryBoundary.io.stidInRange

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
      robEntries = coreParams.robEntries,
      lsidWidth = coreParams.lsidWidth
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
      robEntries = coreParams.robEntries,
      lsidWidth = coreParams.lsidWidth
    )
}
