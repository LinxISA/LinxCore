package linxcore.lsu

import chisel3._

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
  val lookupDataValid = Input(Bool())

  val active = Output(Bool())
  val requestCandidate = Output(Bool())
  val requestReady = Output(Bool())
  val requestAccepted = Output(Bool())
  val responseValid = Output(Bool())
  val responseClusterId = Output(UInt(clusterIdWidth.W))
  val responseEntryId = Output(UInt(entryIdWidth.W))
  val responseWaitStore = Output(Bool())
  val responseDataValid = Output(Bool())
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
  val rawRequest = io.requestValid || io.request.valid || io.lookupWaitStore || io.lookupDataValid
  val requestCandidate = active && io.requestValid && io.request.valid
  val requestReady = active && io.rawSinkReady && io.responseReady
  val requestAccepted = requestCandidate && requestReady

  io.active := active
  io.requestCandidate := requestCandidate
  io.requestReady := requestReady
  io.requestAccepted := requestAccepted
  io.responseValid := requestAccepted
  io.responseClusterId := Mux(requestAccepted, io.request.clusterId, 0.U)
  io.responseEntryId := Mux(requestAccepted, io.request.entryId, 0.U)
  io.responseWaitStore := requestAccepted && io.lookupWaitStore
  io.responseDataValid := requestAccepted && io.lookupDataValid
  io.blockedByDisabled := !io.enable && rawRequest
  io.blockedByFlush := io.enable && io.flush && rawRequest
  io.blockedByNoRequest := active && !io.requestValid && (io.rawSinkReady || io.responseReady)
  io.blockedByRawSink := requestCandidate && !io.rawSinkReady
  io.blockedByResponse := requestCandidate && io.rawSinkReady && !io.responseReady
  io.invalidDataWithWaitStore := requestAccepted && io.lookupWaitStore && io.lookupDataValid
}
