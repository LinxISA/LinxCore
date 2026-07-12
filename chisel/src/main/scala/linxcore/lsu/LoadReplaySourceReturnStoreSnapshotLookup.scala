package linxcore.lsu

import chisel3._
import chisel3.util.{Cat, log2Ceil}

class LoadReplaySourceReturnStoreSnapshotLookupIO(
    val liqEntries: Int,
    val idEntries: Int,
    val clusterIdWidth: Int,
    val entryIdWidth: Int,
    val addrWidth: Int = 64,
    val dataWidth: Int = 64,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val requestSizeWidth: Int = 7,
    val stqSizeWidth: Int = 4,
    val simtLaneWidth: Int = 8,
    val mapQDepth: Int = 32,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val stqEntries: Int = 0,
    val lsidWidth: Int = 32)
    extends Bundle {
  private val physicalStqEntries = if (stqEntries > 0) stqEntries else idEntries
  val enable = Input(Bool())
  val flush = Input(Bool())
  val requestValid = Input(Bool())
  val request = Input(new LoadReplaySourceReturnStoreSnapshotRequestPayloadBundle(
    liqEntries,
    idEntries,
    clusterIdWidth,
    entryIdWidth,
    addrWidth,
    pcWidth,
    lineBytes,
    requestSizeWidth,
    peIdWidth,
    stidWidth,
    tidWidth,
    lsidWidth
  ))
  val rows = Input(Vec(physicalStqEntries, new STQEntryBankRow(
    idEntries,
    addrWidth,
    dataWidth,
    peIdWidth,
    stidWidth,
    tidWidth,
    stqSizeWidth,
    simtLaneWidth,
    mapQDepth,
    pcWidth,
    lsidWidth
  )))
  val cacheData = Input(UInt((lineBytes * 8).W))

  val active = Output(Bool())
  val requestActive = Output(Bool())
  val queryValid = Output(Bool())
  val loadCrossesLine = Output(Bool())
  val requestMaskMismatch = Output(Bool())
  val storeSnapshotValidMask = Output(UInt(physicalStqEntries.W))
  val storeSnapshotWaitMask = Output(UInt(physicalStqEntries.W))
  val storeSnapshotCrossLineMask = Output(UInt(physicalStqEntries.W))
  val loadByteMask = Output(UInt(lineBytes.W))
  val eligibleStoreMask = Output(UInt(physicalStqEntries.W))
  val forwardMask = Output(UInt(lineBytes.W))
  val waitMask = Output(UInt(lineBytes.W))
  val uncoveredLoadMask = Output(UInt(lineBytes.W))
  val waitStore = Output(new LoadStoreForwardWait(idEntries, physicalStqEntries, pcWidth, lsidWidth))
  val waitStoreValid = Output(Bool())
  val rawDataValid = Output(Bool())
  val responseDataValid = Output(Bool())
  val dataSuppressedByWait = Output(Bool())
  val storeBypassComplete = Output(Bool())
  val forwardData = Output(UInt((lineBytes * 8).W))
  val mergedData = Output(UInt((lineBytes * 8).W))
}

class LoadReplaySourceReturnStoreSnapshotLookup(
    val liqEntries: Int = 4,
    val idEntries: Int = 16,
    val clusterIdWidth: Int = 2,
    val entryIdWidth: Int = 4,
    val addrWidth: Int = 64,
    val dataWidth: Int = 64,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val requestSizeWidth: Int = 7,
    val stqSizeWidth: Int = 4,
    val simtLaneWidth: Int = 8,
    val mapQDepth: Int = 32,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val stqEntries: Int = 0,
    val lsidWidth: Int = 32)
    extends Module {
  private val physicalStqEntries = if (stqEntries > 0) stqEntries else idEntries
  require(liqEntries > 1, "LIQ entries must be greater than one")
  require((liqEntries & (liqEntries - 1)) == 0, "LIQ entries must be a power of two")
  require(idEntries > 1, "ID entries must be greater than one")
  require((idEntries & (idEntries - 1)) == 0, "ID entries must be a power of two")
  require(physicalStqEntries > 1 && (physicalStqEntries & (physicalStqEntries - 1)) == 0,
    "STQ entries must be a power of two greater than one")
  require(clusterIdWidth > 0, "clusterIdWidth must be positive")
  require(entryIdWidth > 0, "entryIdWidth must be positive")
  require(addrWidth >= 7, "lookup needs 64-byte line addresses")
  require(dataWidth == 64, "lookup currently consumes 64-bit scalar STQ rows")
  require(requestSizeWidth >= 7, "requestSizeWidth must cover 64-byte scalar lines")
  require(stqSizeWidth >= 4, "stqSizeWidth must cover scalar store sizes")
  require(lineBytes == 64, "lookup currently models 64-byte scalar cachelines")
  require(lsidWidth >= 2, "LSID width must support modular serial ordering")

  private val offsetWidth = log2Ceil(lineBytes)

  val io = IO(new LoadReplaySourceReturnStoreSnapshotLookupIO(
    liqEntries,
    idEntries,
    clusterIdWidth,
    entryIdWidth,
    addrWidth,
    dataWidth,
    peIdWidth,
    stidWidth,
    tidWidth,
    requestSizeWidth,
    stqSizeWidth,
    simtLaneWidth,
    mapQDepth,
    pcWidth,
    lineBytes,
    physicalStqEntries,
    lsidWidth
  ))

  private def lineAddr(addr: UInt): UInt =
    Cat(addr(addrWidth - 1, offsetWidth), 0.U(offsetWidth.W))

  private def crossesLine(addr: UInt, size: UInt): Bool = {
    val offset = Wire(UInt(requestSizeWidth.W))
    offset := addr(offsetWidth - 1, 0)
    (offset +& size) > lineBytes.U
  }

  val active = io.enable && !io.flush
  val requestActive = active && io.requestValid && io.request.valid
  val loadCrossesLine = requestActive && crossesLine(io.request.addr, io.request.size)
  val queryValid = requestActive && (io.request.size =/= 0.U) && !loadCrossesLine

  val storeSnapshot = Module(new ResidentStoreForwardStoreSnapshot(
    entries = physicalStqEntries,
    robEntries = idEntries,
    addrWidth = addrWidth,
    dataWidth = dataWidth,
    peIdWidth = peIdWidth,
    stidWidth = stidWidth,
    tidWidth = tidWidth,
    sizeWidth = stqSizeWidth,
    simtLaneWidth = simtLaneWidth,
    mapQDepth = mapQDepth,
    pcWidth = pcWidth,
    lineBytes = lineBytes,
    lsidWidth = lsidWidth
  ))
  val forward = Module(new LoadStoreForwarding(
    robEntries = idEntries,
    storeEntries = physicalStqEntries,
    addrWidth = addrWidth,
    pcWidth = pcWidth,
    lineBytes = lineBytes,
    sizeWidth = requestSizeWidth,
    lsidWidth = lsidWidth
  ))

  storeSnapshot.io.enable := queryValid
  storeSnapshot.io.rows := io.rows

  forward.io.query.valid := queryValid
  forward.io.query.lineAddr := lineAddr(io.request.addr)
  forward.io.query.byteOffset := io.request.addr(offsetWidth - 1, 0)
  forward.io.query.size := io.request.size
  forward.io.query.youngestStoreId := io.request.bid
  forward.io.query.youngestStoreLsId := io.request.loadLsId
  forward.io.query.isTile := false.B
  forward.io.cacheData := io.cacheData
  for (idx <- 0 until physicalStqEntries) {
    forward.io.stores(idx) := storeSnapshot.io.stores(idx)
  }

  val waitStoreValid = queryValid && forward.io.waitStore.valid
  val rawDataValid = queryValid && forward.io.forwardValid
  val responseDataValid = rawDataValid && !waitStoreValid

  io.active := active
  io.requestActive := requestActive
  io.queryValid := queryValid
  io.loadCrossesLine := loadCrossesLine
  io.requestMaskMismatch := queryValid && (io.request.requestByteMask =/= forward.io.loadByteMask)
  io.storeSnapshotValidMask := storeSnapshot.io.validMask
  io.storeSnapshotWaitMask := storeSnapshot.io.waitMask
  io.storeSnapshotCrossLineMask := storeSnapshot.io.crossLineMask
  io.loadByteMask := forward.io.loadByteMask
  io.eligibleStoreMask := forward.io.eligibleStoreMask
  io.forwardMask := forward.io.forwardMask
  io.waitMask := forward.io.waitMask
  io.uncoveredLoadMask := forward.io.uncoveredLoadMask
  io.waitStore := forward.io.waitStore
  io.waitStoreValid := waitStoreValid
  io.rawDataValid := rawDataValid
  io.responseDataValid := responseDataValid
  io.dataSuppressedByWait := rawDataValid && waitStoreValid
  io.storeBypassComplete := queryValid && forward.io.storeBypassComplete
  io.forwardData := forward.io.forwardData
  io.mergedData := forward.io.mergedData
}
