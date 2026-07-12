package linxcore.lsu

import chisel3._
import chisel3.util.Cat

import linxcore.rob.ROBID

class LoadReplaySourceReturnStoreSnapshotResponseApplyIO(
    val liqEntries: Int,
    val idEntries: Int,
    val clusterIdWidth: Int,
    val entryIdWidth: Int,
    val pcWidth: Int,
    val lineBytes: Int,
    val peIdWidth: Int,
    val stidWidth: Int,
    val tidWidth: Int,
    val storeEntries: Int = 0,
    val lsidWidth: Int = 32)
    extends Bundle {
  private val physicalStoreEntries = if (storeEntries > 0) storeEntries else idEntries
  val enable = Input(Bool())
  val flush = Input(Bool())
  val orderedConsumed = Input(Bool())
  val targetRepick = Input(Bool())
  val targetOneHot = Input(UInt(liqEntries.W))
  val response = Input(new LoadReplaySourceReturnStoreSnapshotResponsePayloadBundle(
    idEntries,
    clusterIdWidth,
    entryIdWidth,
    pcWidth,
    lineBytes,
    peIdWidth,
    stidWidth,
    tidWidth,
    physicalStoreEntries,
    lsidWidth
  ))
  val rowLineData = Input(UInt((lineBytes * 8).W))
  val rowValidMask = Input(UInt(lineBytes.W))
  val rowRequestMask = Input(UInt(lineBytes.W))

  val active = Output(Bool())
  val applyCandidate = Output(Bool())
  val applyValid = Output(Bool())
  val stqReturned = Output(Bool())
  val targetMask = Output(UInt(liqEntries.W))
  val waitStoreApply = Output(Bool())
  val waitStoreInfo = Output(new LoadStoreForwardWait(idEntries, physicalStoreEntries, pcWidth, lsidWidth))
  val waitStoreRid = Output(new ROBID(idEntries))
  val dataMergeApply = Output(Bool())
  val dataNoMerge = Output(Bool())
  val mergedValidMask = Output(UInt(lineBytes.W))
  val mergedLineData = Output(UInt((lineBytes * 8).W))
  val mergedRequestComplete = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoOrderedResponse = Output(Bool())
  val blockedByNotRepick = Output(Bool())
  val blockedByWaitStore = Output(Bool())
  val blockedByNoData = Output(Bool())
  val invalidOrderedWithoutPayload = Output(Bool())
  val invalidDataWithWaitStore = Output(Bool())
  val invalidDataValidWithoutRawData = Output(Bool())
  val invalidSuppressedDataWithoutWait = Output(Bool())
}

class LoadReplaySourceReturnStoreSnapshotResponseApply(
    val liqEntries: Int = 4,
    val idEntries: Int = 16,
    val clusterIdWidth: Int = 2,
    val entryIdWidth: Int = 4,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val storeEntries: Int = 0,
    val lsidWidth: Int = 32)
    extends Module {
  private val physicalStoreEntries = if (storeEntries > 0) storeEntries else idEntries
  require(liqEntries > 1, "LIQ entries must be greater than one")
  require((liqEntries & (liqEntries - 1)) == 0, "LIQ entries must be a power of two")
  require(idEntries > 1, "ID entries must be greater than one")
  require((idEntries & (idEntries - 1)) == 0, "ID entries must be a power of two")
  require(physicalStoreEntries > 1 && (physicalStoreEntries & (physicalStoreEntries - 1)) == 0,
    "store entries must be a power of two greater than one")
  require(clusterIdWidth > 0, "clusterIdWidth must be positive")
  require(entryIdWidth > 0, "entryIdWidth must be positive")
  require(pcWidth > 0, "pcWidth must be positive")
  require(lineBytes == 64, "response apply currently carries 64-byte scalar line data")
  require(peIdWidth > 0, "peIdWidth must be positive")
  require(stidWidth > 0, "stidWidth must be positive")
  require(tidWidth > 0, "tidWidth must be positive")
  require(lsidWidth >= 2, "LSID width must support modular serial ordering")

  val io = IO(new LoadReplaySourceReturnStoreSnapshotResponseApplyIO(
    liqEntries,
    idEntries,
    clusterIdWidth,
    entryIdWidth,
    pcWidth,
    lineBytes,
    peIdWidth,
    stidWidth,
    tidWidth,
    physicalStoreEntries,
    lsidWidth
  ))

  private def zeroWait: LoadStoreForwardWait =
    0.U.asTypeOf(new LoadStoreForwardWait(idEntries, physicalStoreEntries, pcWidth, lsidWidth))

  val active = io.enable && !io.flush
  val rawResponse =
    io.orderedConsumed ||
      io.response.valid ||
      io.response.waitStore ||
      io.response.dataValid ||
      io.response.rawDataValid ||
      io.response.dataSuppressedByWait
  val applyCandidate = active && io.orderedConsumed && io.response.valid
  val applyValid = applyCandidate && io.targetRepick
  val waitStoreApply = applyValid && io.response.waitStore
  val dataMergeApply = applyValid && !io.response.waitStore && io.response.dataValid
  val dataNoMerge = applyValid && !io.response.waitStore && !io.response.dataValid

  val waitInfo = WireDefault(zeroWait)
  when(waitStoreApply) {
    waitInfo.valid := true.B
    waitInfo.storeIndex := io.response.waitStoreIndex
    waitInfo.storeId := io.response.waitStoreBid
    waitInfo.storeLsId := io.response.waitStoreLsId
    waitInfo.storeLsIdFullValid := io.response.waitStoreLsIdFullValid
    waitInfo.storeLsIdFull := io.response.waitStoreLsIdFull
    waitInfo.pc := io.response.waitStorePc
  }

  val nextMask = io.rowValidMask | Mux(dataMergeApply, io.response.dataMask, 0.U)
  val mergedBytes = Wire(Vec(lineBytes, UInt(8.W)))
  for (byte <- 0 until lineBytes) {
    val rowByte = io.rowLineData((byte * 8) + 7, byte * 8)
    val responseByte = io.response.data((byte * 8) + 7, byte * 8)
    mergedBytes(byte) := Mux(dataMergeApply && io.response.dataMask(byte), responseByte, rowByte)
  }

  io.active := active
  io.applyCandidate := applyCandidate
  io.applyValid := applyValid
  io.stqReturned := applyValid
  io.targetMask := Mux(applyValid, io.targetOneHot, 0.U)
  io.waitStoreApply := waitStoreApply
  io.waitStoreInfo := waitInfo
  io.waitStoreRid := Mux(waitStoreApply, io.response.waitStoreRid, ROBID.disabled(idEntries))
  io.dataMergeApply := dataMergeApply
  io.dataNoMerge := dataNoMerge
  io.mergedValidMask := nextMask
  io.mergedLineData := Cat(mergedBytes.reverse)
  io.mergedRequestComplete :=
    dataMergeApply && (io.rowRequestMask =/= 0.U) && ((nextMask & io.rowRequestMask) === io.rowRequestMask)
  io.blockedByDisabled := !io.enable && rawResponse
  io.blockedByFlush := io.enable && io.flush && rawResponse
  io.blockedByNoOrderedResponse := active && !io.orderedConsumed
  io.blockedByNotRepick := applyCandidate && !io.targetRepick
  io.blockedByWaitStore := waitStoreApply
  io.blockedByNoData := dataNoMerge
  io.invalidOrderedWithoutPayload := active && io.orderedConsumed && !io.response.valid
  io.invalidDataWithWaitStore := applyValid && io.response.waitStore && io.response.dataValid
  io.invalidDataValidWithoutRawData := applyValid && io.response.dataValid && !io.response.rawDataValid
  io.invalidSuppressedDataWithoutWait := applyValid && io.response.dataSuppressedByWait && !io.response.waitStore
}
