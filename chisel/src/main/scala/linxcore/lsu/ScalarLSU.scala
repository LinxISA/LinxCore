package linxcore.lsu

import chisel3._

import linxcore.common.{CoreParams, ScalarLsuParams}

class ScalarLSUIO(val coreParams: CoreParams, val lsuParams: ScalarLsuParams) extends Bundle {
  val store = ScalarLSU.storePathIO(coreParams, lsuParams)
  val load = new ScalarLSULoadPathIO(coreParams, lsuParams)
}

class ScalarLSU(val coreParams: CoreParams = CoreParams()) extends Module {
  private val lsuParams = coreParams.scalarLsu
  val io = IO(new ScalarLSUIO(coreParams, lsuParams))

  val storeCommitPath = Module(ScalarLSU.storeCommitPath(coreParams, lsuParams))
  val loadPath = Module(new ScalarLSULoadPath(coreParams))
  storeCommitPath.io <> io.store
  loadPath.io <> io.load

  val storeCarriesAddress = io.store.insert.storeType =/= STQStoreType.Data
  val storeMdbPermit = !storeCarriesAddress || loadPath.mdbStore.probeReady
  storeCommitPath.io.insertValid := io.store.insertValid && storeMdbPermit
  io.store.insertReady := storeCommitPath.io.insertReady && storeMdbPermit

  val storeProbe = Wire(chiselTypeOf(loadPath.mdbStore.probe))
  storeProbe := 0.U.asTypeOf(storeProbe)
  storeProbe.valid := storeCommitPath.io.insertAccepted && storeCarriesAddress
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
