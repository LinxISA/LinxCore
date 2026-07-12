package linxcore.lsu

import chisel3._
import chisel3.util.{Cat, log2Ceil}

class LoadInflightRowMutationRequestBridgeIO(
    val liqEntries: Int,
    val idEntries: Int,
    val sourceStoreEntries: Int,
    val storeEntries: Int,
    val pcWidth: Int,
    val lineBytes: Int,
    val lsidWidth: Int = 32)
    extends Bundle {
  private val liqPtrWidth = log2Ceil(liqEntries)
  private val nativeStoreIndexWidth = log2Ceil(storeEntries)

  val enable = Input(Bool())
  val flush = Input(Bool())
  val requestValid = Input(Bool())
  val requestTargetMask = Input(UInt(liqEntries.W))
  val requestTargetIndex = Input(UInt(liqPtrWidth.W))
  val setWaitStatus = Input(Bool())
  val keepRepickStatus = Input(Bool())
  val clearReturnState = Input(Bool())
  val lineWrite = Input(Bool())
  val waitStoreWrite = Input(Bool())
  val nextWaitStore = Input(Bool())
  val nextWaitStoreInfo = Input(new LoadStoreForwardWait(
    idEntries, sourceStoreEntries, pcWidth, lsidWidth))
  val nextLineData = Input(UInt((lineBytes * 8).W))
  val nextValidMask = Input(UInt(lineBytes.W))
  val nextDataComplete = Input(Bool())
  val nextScbReturned = Input(Bool())
  val nextStqReturned = Input(Bool())
  val nextStoreSourceReturned = Input(Bool())

  val active = Output(Bool())
  val bridgeValid = Output(Bool())
  val requestTargetMaskOut = Output(UInt(liqEntries.W))
  val requestTargetIndexOut = Output(UInt(liqPtrWidth.W))
  val setWaitStatusOut = Output(Bool())
  val keepRepickStatusOut = Output(Bool())
  val clearReturnStateOut = Output(Bool())
  val lineWriteOut = Output(Bool())
  val waitStoreWriteOut = Output(Bool())
  val nextWaitStoreOut = Output(Bool())
  val nativeNextWaitStoreInfoOut = Output(new LoadStoreForwardWait(
    idEntries, storeEntries, pcWidth, lsidWidth))
  val nativeStoreIndexOut = Output(UInt(nativeStoreIndexWidth.W))
  val nextLineDataOut = Output(UInt((lineBytes * 8).W))
  val nextValidMaskOut = Output(UInt(lineBytes.W))
  val nextDataCompleteOut = Output(Bool())
  val nextScbReturnedOut = Output(Bool())
  val nextStqReturnedOut = Output(Bool())
  val nextStoreSourceReturnedOut = Output(Bool())
  val sourceStoreIndexFits = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoRequest = Output(Bool())
  val invalidStoreIndexOutOfRange = Output(Bool())
  val invalidConflictingStatusWrite = Output(Bool())
  val invalidWaitStoreWithoutWaitStatus = Output(Bool())
  val invalidReturnWithoutSplitSources = Output(Bool())
}

class LoadInflightRowMutationRequestBridge(
    val liqEntries: Int = 4,
    val idEntries: Int = 16,
    val sourceStoreEntries: Int = 16,
    val storeEntries: Int = 16,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64,
    val lsidWidth: Int = 32)
    extends Module {
  require(liqEntries > 1, "LIQ entries must be greater than one")
  require((liqEntries & (liqEntries - 1)) == 0, "LIQ entries must be a power of two")
  require(idEntries > 1, "ID entries must be greater than one")
  require((idEntries & (idEntries - 1)) == 0, "ID entries must be a power of two")
  require(sourceStoreEntries > 1, "sourceStoreEntries must be greater than one")
  require((sourceStoreEntries & (sourceStoreEntries - 1)) == 0, "sourceStoreEntries must be a power of two")
  require(storeEntries > 1, "storeEntries must be greater than one")
  require((storeEntries & (storeEntries - 1)) == 0, "storeEntries must be a power of two")
  require(pcWidth > 0, "pcWidth must be positive")
  require(lineBytes == 64, "LoadInflightRowMutationRequestBridge currently carries 64-byte scalar line data")

  private val nativeStoreIndexWidth = log2Ceil(storeEntries)

  val io = IO(new LoadInflightRowMutationRequestBridgeIO(
    liqEntries,
    idEntries,
    sourceStoreEntries,
    storeEntries,
    pcWidth,
    lineBytes,
    lsidWidth
  ))

  private def zeroNativeWait: LoadStoreForwardWait =
    0.U.asTypeOf(new LoadStoreForwardWait(idEntries, storeEntries, pcWidth, lsidWidth))

  private def resizeStoreIndex(index: UInt): UInt =
    if (index.getWidth == nativeStoreIndexWidth) {
      index
    } else if (index.getWidth < nativeStoreIndexWidth) {
      Cat(0.U((nativeStoreIndexWidth - index.getWidth).W), index)
    } else {
      index(nativeStoreIndexWidth - 1, 0)
    }

  val active = io.enable && !io.flush
  val requestActive = active && io.requestValid
  val sourceStoreIndexFits =
    if (sourceStoreEntries <= storeEntries) {
      true.B
    } else {
      io.nextWaitStoreInfo.storeIndex < storeEntries.U(io.nextWaitStoreInfo.storeIndex.getWidth.W)
    }

  val invalidConflictingStatusWrite = io.setWaitStatus && io.keepRepickStatus
  val invalidWaitStoreWithoutWaitStatus = io.nextWaitStore && !io.setWaitStatus
  val invalidReturnWithoutSplitSources =
    io.nextStoreSourceReturned && !(io.nextScbReturned && io.nextStqReturned)
  val invalidStoreIndexOutOfRange = io.nextWaitStore && !sourceStoreIndexFits
  val payloadShapeValid =
    !(invalidConflictingStatusWrite ||
      invalidWaitStoreWithoutWaitStatus ||
      invalidReturnWithoutSplitSources ||
      invalidStoreIndexOutOfRange)
  val bridgeValid = requestActive && payloadShapeValid

  val nativeWait = WireDefault(zeroNativeWait)
  nativeWait.valid := io.nextWaitStoreInfo.valid
  nativeWait.storeIndex := resizeStoreIndex(io.nextWaitStoreInfo.storeIndex)
  nativeWait.storeId := io.nextWaitStoreInfo.storeId
  nativeWait.storeLsId := io.nextWaitStoreInfo.storeLsId
  nativeWait.storeLsIdFullValid := io.nextWaitStoreInfo.storeLsIdFullValid
  nativeWait.storeLsIdFull := io.nextWaitStoreInfo.storeLsIdFull
  nativeWait.pc := io.nextWaitStoreInfo.pc

  io.active := active
  io.bridgeValid := bridgeValid
  io.requestTargetMaskOut := Mux(bridgeValid, io.requestTargetMask, 0.U(liqEntries.W))
  io.requestTargetIndexOut := Mux(bridgeValid, io.requestTargetIndex, 0.U(log2Ceil(liqEntries).W))
  io.setWaitStatusOut := bridgeValid && io.setWaitStatus
  io.keepRepickStatusOut := bridgeValid && io.keepRepickStatus
  io.clearReturnStateOut := bridgeValid && io.clearReturnState
  io.lineWriteOut := bridgeValid && io.lineWrite
  io.waitStoreWriteOut := bridgeValid && io.waitStoreWrite
  io.nextWaitStoreOut := bridgeValid && io.nextWaitStore
  io.nativeNextWaitStoreInfoOut := Mux(bridgeValid && io.nextWaitStore, nativeWait, zeroNativeWait)
  io.nativeStoreIndexOut := Mux(bridgeValid && io.nextWaitStore, resizeStoreIndex(io.nextWaitStoreInfo.storeIndex), 0.U)
  io.nextLineDataOut := Mux(bridgeValid, io.nextLineData, 0.U((lineBytes * 8).W))
  io.nextValidMaskOut := Mux(bridgeValid, io.nextValidMask, 0.U(lineBytes.W))
  io.nextDataCompleteOut := bridgeValid && io.nextDataComplete
  io.nextScbReturnedOut := bridgeValid && io.nextScbReturned
  io.nextStqReturnedOut := bridgeValid && io.nextStqReturned
  io.nextStoreSourceReturnedOut := bridgeValid && io.nextStoreSourceReturned
  io.sourceStoreIndexFits := sourceStoreIndexFits
  io.blockedByDisabled := !io.enable && io.requestValid
  io.blockedByFlush := io.enable && io.flush && io.requestValid
  io.blockedByNoRequest := active && !io.requestValid
  io.invalidStoreIndexOutOfRange := requestActive && invalidStoreIndexOutOfRange
  io.invalidConflictingStatusWrite := requestActive && invalidConflictingStatusWrite
  io.invalidWaitStoreWithoutWaitStatus := requestActive && invalidWaitStoreWithoutWaitStatus
  io.invalidReturnWithoutSplitSources := requestActive && invalidReturnWithoutSplitSources
}
