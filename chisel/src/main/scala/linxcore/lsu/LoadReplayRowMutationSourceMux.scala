package linxcore.lsu

import chisel3._
import chisel3.util.log2Ceil

class LoadReplayRowMutationRequest(
    val liqEntries: Int,
    val idEntries: Int,
    val sourceStoreEntries: Int,
    val pcWidth: Int,
    val lineBytes: Int)
    extends Bundle {
  private val liqPtrWidth = log2Ceil(liqEntries)

  val valid = Bool()
  val targetMask = UInt(liqEntries.W)
  val targetIndex = UInt(liqPtrWidth.W)
  val setWaitStatus = Bool()
  val keepRepickStatus = Bool()
  val clearReturnState = Bool()
  val lineWrite = Bool()
  val waitStoreWrite = Bool()
  val nextWaitStore = Bool()
  val nextWaitStoreInfo = new LoadStoreForwardWait(idEntries, sourceStoreEntries, pcWidth)
  val nextLineData = UInt((lineBytes * 8).W)
  val nextValidMask = UInt(lineBytes.W)
  val nextDataComplete = Bool()
  val nextScbReturned = Bool()
  val nextStqReturned = Bool()
  val nextStoreSourceReturned = Bool()
}

class LoadReplayRowMutationSourceMuxIO(
    val liqEntries: Int,
    val idEntries: Int,
    val sourceStoreEntries: Int,
    val pcWidth: Int,
    val lineBytes: Int)
    extends Bundle {
  val sourceReturn = Input(new LoadReplayRowMutationRequest(liqEntries, idEntries, sourceStoreEntries, pcWidth, lineBytes))
  val mdbWaitPlan = Input(new LoadReplayRowMutationRequest(liqEntries, idEntries, sourceStoreEntries, pcWidth, lineBytes))
  val out = Output(new LoadReplayRowMutationRequest(liqEntries, idEntries, sourceStoreEntries, pcWidth, lineBytes))

  val selectedSourceReturn = Output(Bool())
  val selectedMdbWaitPlan = Output(Bool())
  val conflict = Output(Bool())
}

class LoadReplayRowMutationSourceMux(
    val liqEntries: Int = 4,
    val idEntries: Int = 16,
    val sourceStoreEntries: Int = 16,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64)
    extends Module {
  require(liqEntries > 1, "LIQ entries must be greater than one")
  require((liqEntries & (liqEntries - 1)) == 0, "LIQ entries must be a power of two")
  require(idEntries > 1, "ID entries must be greater than one")
  require((idEntries & (idEntries - 1)) == 0, "ID entries must be a power of two")
  require(sourceStoreEntries > 1, "sourceStoreEntries must be greater than one")
  require((sourceStoreEntries & (sourceStoreEntries - 1)) == 0, "sourceStoreEntries must be a power of two")
  require(pcWidth > 0, "pcWidth must be positive")
  require(lineBytes == 64, "row mutation mux currently carries 64-byte scalar line data")

  val io = IO(new LoadReplayRowMutationSourceMuxIO(
    liqEntries,
    idEntries,
    sourceStoreEntries,
    pcWidth,
    lineBytes
  ))

  val useSourceReturn = io.sourceReturn.valid
  val useMdbWaitPlan = !useSourceReturn && io.mdbWaitPlan.valid
  val selected = WireDefault(0.U.asTypeOf(io.out))
  val selectedPayload = Mux(useSourceReturn, io.sourceReturn, Mux(useMdbWaitPlan, io.mdbWaitPlan, 0.U.asTypeOf(io.out)))

  selected := selectedPayload
  selected.valid := useSourceReturn || useMdbWaitPlan

  io.out := selected
  io.selectedSourceReturn := useSourceReturn
  io.selectedMdbWaitPlan := useMdbWaitPlan
  io.conflict := io.sourceReturn.valid && io.mdbWaitPlan.valid
}
