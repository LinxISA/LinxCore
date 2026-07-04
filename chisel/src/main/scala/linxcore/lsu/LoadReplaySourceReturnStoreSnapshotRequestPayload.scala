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
    val sizeWidth: Int = 7)
    extends Bundle {
  val valid = Bool()
  val clusterId = UInt(clusterIdWidth.W)
  val entryId = UInt(entryIdWidth.W)
  val loadId = new ROBID(liqEntries)
  val bid = new ROBID(idEntries)
  val gid = new ROBID(idEntries)
  val rid = new ROBID(idEntries)
  val loadLsId = new ROBID(idEntries)
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
    val sizeWidth: Int = 7)
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
    sizeWidth
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
    val sizeWidth: Int = 7)
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

  val io = IO(new LoadReplaySourceReturnStoreSnapshotRequestPayloadIO(
    liqEntries,
    idEntries,
    clusterIdWidth,
    entryIdWidth,
    addrWidth,
    pcWidth,
    lineBytes,
    sizeWidth
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
    sizeWidth
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
