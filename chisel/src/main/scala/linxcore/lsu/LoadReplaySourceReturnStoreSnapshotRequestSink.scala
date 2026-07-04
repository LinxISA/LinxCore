package linxcore.lsu

import chisel3._
import chisel3.util.log2Ceil

import linxcore.rob.ROBID

class LoadReplaySourceReturnStoreSnapshotRequestSinkIO(
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
  val requestValid = Input(Bool())
  val request = Input(new LoadReplaySourceReturnStoreSnapshotRequestPayloadBundle(
    liqEntries,
    idEntries,
    clusterIdWidth,
    entryIdWidth,
    addrWidth,
    pcWidth,
    lineBytes,
    sizeWidth
  ))
  val rawSinkReady = Input(Bool())
  val responseReady = Input(Bool())
  val lookupWaitStore = Input(Bool())
  val lookupWaitStoreInfo = Input(new LoadStoreForwardWait(idEntries, idEntries, pcWidth))
  val lookupWaitStoreRid = Input(new ROBID(idEntries))
  val lookupRawDataValid = Input(Bool())
  val lookupDataValid = Input(Bool())
  val lookupDataSuppressedByWait = Input(Bool())
  val lookupDataMask = Input(UInt(lineBytes.W))
  val lookupData = Input(UInt((lineBytes * 8).W))

  val active = Output(Bool())
  val requestCandidate = Output(Bool())
  val requestReady = Output(Bool())
  val requestAccepted = Output(Bool())
  val response = Output(new LoadReplaySourceReturnStoreSnapshotResponsePayloadBundle(
    idEntries,
    clusterIdWidth,
    entryIdWidth,
    pcWidth,
    lineBytes
  ))
  val responseValid = Output(Bool())
  val responseClusterId = Output(UInt(clusterIdWidth.W))
  val responseEntryId = Output(UInt(entryIdWidth.W))
  val responseRequestBid = Output(new ROBID(idEntries))
  val responseRequestGid = Output(new ROBID(idEntries))
  val responseRequestRid = Output(new ROBID(idEntries))
  val responseRequestLoadLsId = Output(new ROBID(idEntries))
  val responseWaitStore = Output(Bool())
  val responseDataValid = Output(Bool())
  val responseRawDataValid = Output(Bool())
  val responseDataSuppressedByWait = Output(Bool())
  val responseWaitStoreIndex = Output(UInt(log2Ceil(idEntries).W))
  val responseWaitStoreBid = Output(new ROBID(idEntries))
  val responseWaitStoreRid = Output(new ROBID(idEntries))
  val responseWaitStoreLsId = Output(new ROBID(idEntries))
  val responseWaitStorePc = Output(UInt(pcWidth.W))
  val responseDataMask = Output(UInt(lineBytes.W))
  val responseData = Output(UInt((lineBytes * 8).W))
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoRequest = Output(Bool())
  val blockedByRawSink = Output(Bool())
  val blockedByResponse = Output(Bool())
  val invalidDataWithWaitStore = Output(Bool())
}

class LoadReplaySourceReturnStoreSnapshotRequestSink(
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
  require(addrWidth >= 7, "request sink needs 64-byte line addresses")
  require(lineBytes == 64, "request sink currently models 64-byte scalar cachelines")
  require(sizeWidth >= 7, "sizeWidth must cover 64-byte scalar lines")

  val io = IO(new LoadReplaySourceReturnStoreSnapshotRequestSinkIO(
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
  val rawRequest =
    io.requestValid || io.request.valid || io.lookupWaitStore || io.lookupRawDataValid || io.lookupDataValid
  val requestCandidate = active && io.requestValid && io.request.valid
  val requestReady = active && io.rawSinkReady && io.responseReady
  val requestAccepted = requestCandidate && requestReady
  val response = WireDefault(0.U.asTypeOf(new LoadReplaySourceReturnStoreSnapshotResponsePayloadBundle(
    idEntries,
    clusterIdWidth,
    entryIdWidth,
    pcWidth,
    lineBytes
  )))

  when(requestAccepted) {
    response.valid := true.B
    response.clusterId := io.request.clusterId
    response.entryId := io.request.entryId
    response.requestBid := io.request.bid
    response.requestGid := io.request.gid
    response.requestRid := io.request.rid
    response.requestLoadLsId := io.request.loadLsId
    response.waitStore := io.lookupWaitStore
    response.dataValid := io.lookupDataValid
    response.rawDataValid := io.lookupRawDataValid
    response.dataSuppressedByWait := io.lookupDataSuppressedByWait
    response.waitStoreIndex := io.lookupWaitStoreInfo.storeIndex
    response.waitStoreBid := io.lookupWaitStoreInfo.storeId
    response.waitStoreRid := io.lookupWaitStoreRid
    response.waitStoreLsId := io.lookupWaitStoreInfo.storeLsId
    response.waitStorePc := io.lookupWaitStoreInfo.pc
    response.dataMask := Mux(io.lookupRawDataValid, io.lookupDataMask, 0.U)
    response.data := Mux(io.lookupRawDataValid, io.lookupData, 0.U)
  }

  io.active := active
  io.requestCandidate := requestCandidate
  io.requestReady := requestReady
  io.requestAccepted := requestAccepted
  io.response := response
  io.responseValid := response.valid
  io.responseClusterId := response.clusterId
  io.responseEntryId := response.entryId
  io.responseRequestBid := response.requestBid
  io.responseRequestGid := response.requestGid
  io.responseRequestRid := response.requestRid
  io.responseRequestLoadLsId := response.requestLoadLsId
  io.responseWaitStore := response.waitStore
  io.responseDataValid := response.dataValid
  io.responseRawDataValid := response.rawDataValid
  io.responseDataSuppressedByWait := response.dataSuppressedByWait
  io.responseWaitStoreIndex := response.waitStoreIndex
  io.responseWaitStoreBid := response.waitStoreBid
  io.responseWaitStoreRid := response.waitStoreRid
  io.responseWaitStoreLsId := response.waitStoreLsId
  io.responseWaitStorePc := response.waitStorePc
  io.responseDataMask := response.dataMask
  io.responseData := response.data
  io.blockedByDisabled := !io.enable && rawRequest
  io.blockedByFlush := io.enable && io.flush && rawRequest
  io.blockedByNoRequest := active && !io.requestValid && (io.rawSinkReady || io.responseReady)
  io.blockedByRawSink := requestCandidate && !io.rawSinkReady
  io.blockedByResponse := requestCandidate && io.rawSinkReady && !io.responseReady
  io.invalidDataWithWaitStore := requestAccepted && io.lookupWaitStore && io.lookupDataValid
}
