package linxcore.lsu

import chisel3._
import chisel3.util.log2Ceil

import linxcore.rob.ROBID

class LoadReplaySourceReturnStoreSnapshotRawResponseSourceIO(
    idEntries: Int,
    clusterIdWidth: Int,
    entryIdWidth: Int,
    pcWidth: Int,
    lineBytes: Int,
    peIdWidth: Int,
    stidWidth: Int,
    tidWidth: Int)
    extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val liveEnable = Input(Bool())
  val rawValid = Input(Bool())
  val clusterId = Input(UInt(clusterIdWidth.W))
  val entryId = Input(UInt(entryIdWidth.W))
  val requestBid = Input(new ROBID(idEntries))
  val requestGid = Input(new ROBID(idEntries))
  val requestRid = Input(new ROBID(idEntries))
  val requestLoadLsId = Input(new ROBID(idEntries))
  val requestPeId = Input(UInt(peIdWidth.W))
  val requestStid = Input(UInt(stidWidth.W))
  val requestTid = Input(UInt(tidWidth.W))
  val waitStore = Input(Bool())
  val dataValid = Input(Bool())
  val rawDataValid = Input(Bool())
  val dataSuppressedByWait = Input(Bool())
  val waitStoreIndex = Input(UInt(log2Ceil(idEntries).W))
  val waitStoreBid = Input(new ROBID(idEntries))
  val waitStoreRid = Input(new ROBID(idEntries))
  val waitStoreLsId = Input(new ROBID(idEntries))
  val waitStorePc = Input(UInt(pcWidth.W))
  val dataMask = Input(UInt(lineBytes.W))
  val data = Input(UInt((lineBytes * 8).W))

  val active = Output(Bool())
  val candidate = Output(Bool())
  val responseValid = Output(Bool())
  val response = Output(new LoadReplaySourceReturnStoreSnapshotResponsePayloadBundle(
    idEntries,
    clusterIdWidth,
    entryIdWidth,
    pcWidth,
    lineBytes,
    peIdWidth,
    stidWidth,
    tidWidth
  ))
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByLiveDisabled = Output(Bool())
  val invalidDataWithWaitStore = Output(Bool())
  val invalidDataValidWithoutRawData = Output(Bool())
  val invalidSuppressedDataWithoutWait = Output(Bool())
  val invalidSuppressedDataWithoutRawData = Output(Bool())
}

class LoadReplaySourceReturnStoreSnapshotRawResponseSource(
    val idEntries: Int = 16,
    val clusterIdWidth: Int = 2,
    val entryIdWidth: Int = 4,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8)
    extends Module {
  require(idEntries > 1, "idEntries must be greater than one")
  require((idEntries & (idEntries - 1)) == 0, "idEntries must be a power of two")
  require(clusterIdWidth > 0, "clusterIdWidth must be positive")
  require(entryIdWidth > 0, "entryIdWidth must be positive")
  require(pcWidth > 0, "pcWidth must be positive")
  require(lineBytes == 64, "raw response source currently carries 64-byte scalar line data")
  require(peIdWidth > 0, "peIdWidth must be positive")
  require(stidWidth > 0, "stidWidth must be positive")
  require(tidWidth > 0, "tidWidth must be positive")

  val io = IO(new LoadReplaySourceReturnStoreSnapshotRawResponseSourceIO(
    idEntries = idEntries,
    clusterIdWidth = clusterIdWidth,
    entryIdWidth = entryIdWidth,
    pcWidth = pcWidth,
    lineBytes = lineBytes,
    peIdWidth = peIdWidth,
    stidWidth = stidWidth,
    tidWidth = tidWidth
  ))

  val active = io.enable && !io.flush
  val candidate = active && io.rawValid
  val responseValid = candidate && io.liveEnable

  val response = WireDefault(0.U.asTypeOf(new LoadReplaySourceReturnStoreSnapshotResponsePayloadBundle(
    idEntries,
    clusterIdWidth,
    entryIdWidth,
    pcWidth,
    lineBytes,
    peIdWidth,
    stidWidth,
    tidWidth
  )))
  when(responseValid) {
    response.valid := true.B
    response.clusterId := io.clusterId
    response.entryId := io.entryId
    response.requestBid := io.requestBid
    response.requestGid := io.requestGid
    response.requestRid := io.requestRid
    response.requestLoadLsId := io.requestLoadLsId
    response.requestPeId := io.requestPeId
    response.requestStid := io.requestStid
    response.requestTid := io.requestTid
    response.waitStore := io.waitStore
    response.dataValid := io.dataValid
    response.rawDataValid := io.rawDataValid
    response.dataSuppressedByWait := io.dataSuppressedByWait
    response.waitStoreIndex := io.waitStoreIndex
    response.waitStoreBid := io.waitStoreBid
    response.waitStoreRid := io.waitStoreRid
    response.waitStoreLsId := io.waitStoreLsId
    response.waitStorePc := io.waitStorePc
    response.dataMask := io.dataMask
    response.data := io.data
  }

  io.active := active
  io.candidate := candidate
  io.responseValid := responseValid
  io.response := response
  io.blockedByDisabled := !io.enable && io.rawValid
  io.blockedByFlush := io.enable && io.flush && io.rawValid
  io.blockedByLiveDisabled := candidate && !io.liveEnable
  io.invalidDataWithWaitStore := candidate && io.waitStore && io.dataValid
  io.invalidDataValidWithoutRawData := candidate && io.dataValid && !io.rawDataValid
  io.invalidSuppressedDataWithoutWait := candidate && io.dataSuppressedByWait && !io.waitStore
  io.invalidSuppressedDataWithoutRawData := candidate && io.dataSuppressedByWait && !io.rawDataValid
}
