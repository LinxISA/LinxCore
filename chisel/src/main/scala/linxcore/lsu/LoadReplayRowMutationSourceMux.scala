package linxcore.lsu

import chisel3._
import chisel3.util.log2Ceil

class LoadReplayRowMutationRequest(
    val liqEntries: Int,
    val idEntries: Int,
    val sourceStoreEntries: Int,
    val pcWidth: Int,
    val lineBytes: Int,
    val lsidWidth: Int = 32)
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
  val nextWaitStoreInfo = new LoadStoreForwardWait(idEntries, sourceStoreEntries, pcWidth, lsidWidth)
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
    val lineBytes: Int,
    val lsidWidth: Int = 32)
    extends Bundle {
  val sourceReturn = Input(new LoadReplayRowMutationRequest(
    liqEntries, idEntries, sourceStoreEntries, pcWidth, lineBytes, lsidWidth))
  val mdbWaitPlan = Input(new LoadReplayRowMutationRequest(
    liqEntries, idEntries, sourceStoreEntries, pcWidth, lineBytes, lsidWidth))
  val out = Output(new LoadReplayRowMutationRequest(
    liqEntries, idEntries, sourceStoreEntries, pcWidth, lineBytes, lsidWidth))

  val selectedSourceReturn = Output(Bool())
  val selectedMdbWaitPlan = Output(Bool())
  val conflict = Output(Bool())
}

class LoadReplayRowMutationSourceMux(
    val liqEntries: Int = 4,
    val idEntries: Int = 16,
    val sourceStoreEntries: Int = 16,
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
  require(pcWidth > 0, "pcWidth must be positive")
  require(lineBytes == 64, "row mutation mux currently carries 64-byte scalar line data")

  val io = IO(new LoadReplayRowMutationSourceMuxIO(
    liqEntries,
    idEntries,
    sourceStoreEntries,
    pcWidth,
    lineBytes,
    lsidWidth
  ))

  val sourceValid = io.sourceReturn.valid
  val mdbValid = io.mdbWaitPlan.valid
  val sameTarget =
    sourceValid &&
      mdbValid &&
      (io.sourceReturn.targetMask === io.mdbWaitPlan.targetMask) &&
      (io.sourceReturn.targetIndex === io.mdbWaitPlan.targetIndex)
  val useSourceReturn = sourceValid
  val useMdbWaitPlan = mdbValid && (!sourceValid || sameTarget)
  val selected = WireDefault(0.U.asTypeOf(io.out))
  val selectedPayload = Mux(useSourceReturn, io.sourceReturn, Mux(useMdbWaitPlan, io.mdbWaitPlan, 0.U.asTypeOf(io.out)))

  selected := selectedPayload
  selected.valid := useSourceReturn || useMdbWaitPlan
  when(sameTarget) {
    selected.valid := true.B
    selected.targetMask := io.sourceReturn.targetMask
    selected.targetIndex := io.sourceReturn.targetIndex
    selected.setWaitStatus := io.sourceReturn.setWaitStatus || io.mdbWaitPlan.setWaitStatus
    selected.keepRepickStatus := io.sourceReturn.keepRepickStatus && !io.mdbWaitPlan.setWaitStatus
    selected.clearReturnState := io.sourceReturn.clearReturnState || io.mdbWaitPlan.clearReturnState
    selected.lineWrite := io.sourceReturn.lineWrite || io.mdbWaitPlan.lineWrite
    selected.waitStoreWrite := io.sourceReturn.waitStoreWrite || io.mdbWaitPlan.waitStoreWrite
    selected.nextWaitStore := Mux(io.mdbWaitPlan.waitStoreWrite, io.mdbWaitPlan.nextWaitStore, io.sourceReturn.nextWaitStore)
    selected.nextWaitStoreInfo :=
      Mux(io.mdbWaitPlan.waitStoreWrite, io.mdbWaitPlan.nextWaitStoreInfo, io.sourceReturn.nextWaitStoreInfo)
    selected.nextLineData := Mux(io.mdbWaitPlan.lineWrite, io.mdbWaitPlan.nextLineData, io.sourceReturn.nextLineData)
    selected.nextValidMask := Mux(io.mdbWaitPlan.lineWrite, io.mdbWaitPlan.nextValidMask, io.sourceReturn.nextValidMask)
    selected.nextDataComplete :=
      Mux(io.mdbWaitPlan.clearReturnState, io.mdbWaitPlan.nextDataComplete, io.sourceReturn.nextDataComplete)
    selected.nextScbReturned :=
      Mux(io.mdbWaitPlan.clearReturnState, io.mdbWaitPlan.nextScbReturned, io.sourceReturn.nextScbReturned)
    selected.nextStqReturned :=
      Mux(io.mdbWaitPlan.clearReturnState, io.mdbWaitPlan.nextStqReturned, io.sourceReturn.nextStqReturned)
    selected.nextStoreSourceReturned :=
      Mux(io.mdbWaitPlan.clearReturnState, io.mdbWaitPlan.nextStoreSourceReturned, io.sourceReturn.nextStoreSourceReturned)
  }

  io.out := selected
  io.selectedSourceReturn := useSourceReturn
  io.selectedMdbWaitPlan := useMdbWaitPlan
  io.conflict := io.sourceReturn.valid && io.mdbWaitPlan.valid
}
