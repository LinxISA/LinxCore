package linxcore.lsu

import chisel3._

import linxcore.rob.ROBID

class LoadReplaySourceReturnStoreSnapshotRequestPayloadBundle(
    val liqEntries: Int,
    val idEntries: Int,
    val clusterIdWidth: Int,
    val entryIdWidth: Int,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val sizeWidth: Int = 7,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val lsidWidth: Int = 32)
    extends Bundle {
  val valid = Bool()
  val clusterId = UInt(clusterIdWidth.W)
  val entryId = UInt(entryIdWidth.W)
  val loadId = new ROBID(liqEntries)
  val bid = new ROBID(idEntries)
  val gid = new ROBID(idEntries)
  val rid = new ROBID(idEntries)
  val loadLsId = new ROBID(idEntries)
  val loadLsIdFullValid = Bool()
  val loadLsIdFull = UInt(lsidWidth.W)
  val peId = UInt(peIdWidth.W)
  val stid = UInt(stidWidth.W)
  val tid = UInt(tidWidth.W)
  val pc = UInt(pcWidth.W)
  val addr = UInt(addrWidth.W)
  val size = UInt(sizeWidth.W)
  val requestByteMask = UInt(lineBytes.W)
}

class LoadReplaySourceReturnStoreSnapshotRequestPayloadIO(
    val liqEntries: Int,
    val idEntries: Int,
    val clusterIdWidth: Int,
    val entryIdWidth: Int,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val sizeWidth: Int = 7,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val lsidWidth: Int = 32)
    extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val queryIssued = Input(Bool())
  val selectedValid = Input(Bool())
  val selectedRepick = Input(Bool())
  val selectedClusterId = Input(UInt(clusterIdWidth.W))
  val selectedEntryId = Input(UInt(entryIdWidth.W))
  val selectedLoadId = Input(new ROBID(liqEntries))
  val selectedBid = Input(new ROBID(idEntries))
  val selectedGid = Input(new ROBID(idEntries))
  val selectedRid = Input(new ROBID(idEntries))
  val selectedLoadLsId = Input(new ROBID(idEntries))
  val selectedLoadLsIdFullValid = Input(Bool())
  val selectedLoadLsIdFull = Input(UInt(lsidWidth.W))
  val selectedPeId = Input(UInt(peIdWidth.W))
  val selectedStid = Input(UInt(stidWidth.W))
  val selectedTid = Input(UInt(tidWidth.W))
  val selectedPc = Input(UInt(pcWidth.W))
  val selectedAddr = Input(UInt(addrWidth.W))
  val selectedSize = Input(UInt(sizeWidth.W))
  val selectedRequestByteMask = Input(UInt(lineBytes.W))

  val active = Output(Bool())
  val captureCandidate = Output(Bool())
  val requestValid = Output(Bool())
  val request = Output(new LoadReplaySourceReturnStoreSnapshotRequestPayloadBundle(
    liqEntries,
    idEntries,
    clusterIdWidth,
    entryIdWidth,
    addrWidth,
    pcWidth,
    lineBytes,
    sizeWidth,
    peIdWidth,
    stidWidth,
    tidWidth,
    lsidWidth
  ))
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoIssue = Output(Bool())
  val blockedByNoSelected = Output(Bool())
  val blockedByStaleRow = Output(Bool())
}

class LoadReplaySourceReturnStoreSnapshotRequestPayload(
    val liqEntries: Int = 4,
    val idEntries: Int = 16,
    val clusterIdWidth: Int = 2,
    val entryIdWidth: Int = 4,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val sizeWidth: Int = 7,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val lsidWidth: Int = 32)
    extends Module {
  require(liqEntries > 1, "LIQ entries must be greater than one")
  require((liqEntries & (liqEntries - 1)) == 0, "LIQ entries must be a power of two")
  require(idEntries > 1, "ID entries must be greater than one")
  require((idEntries & (idEntries - 1)) == 0, "ID entries must be a power of two")
  require(clusterIdWidth > 0, "clusterIdWidth must be positive")
  require(entryIdWidth > 0, "entryIdWidth must be positive")
  require(addrWidth >= 7, "request payload needs 64-byte line addresses")
  require(lineBytes == 64, "request payload currently models 64-byte scalar cachelines")
  require(sizeWidth >= 7, "sizeWidth must cover 64-byte scalar lines")
  require(peIdWidth > 0, "peIdWidth must be positive")
  require(stidWidth > 0, "stidWidth must be positive")
  require(tidWidth > 0, "tidWidth must be positive")
  require(lsidWidth >= 2, "LSID width must support modular serial ordering")

  val io = IO(new LoadReplaySourceReturnStoreSnapshotRequestPayloadIO(
    liqEntries,
    idEntries,
    clusterIdWidth,
    entryIdWidth,
    addrWidth,
    pcWidth,
    lineBytes,
    sizeWidth,
    peIdWidth,
    stidWidth,
    tidWidth,
    lsidWidth
  ))

  val active = io.enable && !io.flush
  val captureCandidate = active && io.queryIssued
  val requestValid = captureCandidate && io.selectedValid && io.selectedRepick

  val request = WireDefault(0.U.asTypeOf(new LoadReplaySourceReturnStoreSnapshotRequestPayloadBundle(
    liqEntries,
    idEntries,
    clusterIdWidth,
    entryIdWidth,
    addrWidth,
    pcWidth,
    lineBytes,
    sizeWidth,
    peIdWidth,
    stidWidth,
    tidWidth,
    lsidWidth
  )))

  when(requestValid) {
    request.valid := true.B
    request.clusterId := io.selectedClusterId
    request.entryId := io.selectedEntryId
    request.loadId := io.selectedLoadId
    request.bid := io.selectedBid
    request.gid := io.selectedGid
    request.rid := io.selectedRid
    request.loadLsId := io.selectedLoadLsId
    request.loadLsIdFullValid := io.selectedLoadLsIdFullValid
    request.loadLsIdFull := io.selectedLoadLsIdFull
    request.peId := io.selectedPeId
    request.stid := io.selectedStid
    request.tid := io.selectedTid
    request.pc := io.selectedPc
    request.addr := io.selectedAddr
    request.size := io.selectedSize
    request.requestByteMask := io.selectedRequestByteMask
  }

  io.active := active
  io.captureCandidate := captureCandidate
  io.requestValid := requestValid
  io.request := request
  io.blockedByDisabled := !io.enable && io.queryIssued
  io.blockedByFlush := io.enable && io.flush && io.queryIssued
  io.blockedByNoIssue := active && io.selectedValid && io.selectedRepick && !io.queryIssued
  io.blockedByNoSelected := captureCandidate && !io.selectedValid
  io.blockedByStaleRow := captureCandidate && io.selectedValid && !io.selectedRepick
}
