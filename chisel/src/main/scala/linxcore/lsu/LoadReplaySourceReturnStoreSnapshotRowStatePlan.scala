package linxcore.lsu

import chisel3._

import linxcore.rob.ROBID

class LoadReplaySourceReturnStoreSnapshotRowStatePlanIO(
    val idEntries: Int,
    val pcWidth: Int,
    val lineBytes: Int)
    extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val applyValid = Input(Bool())
  val applyStqReturned = Input(Bool())
  val waitStoreApply = Input(Bool())
  val waitStoreInfo = Input(new LoadStoreForwardWait(idEntries, idEntries, pcWidth))
  val waitStoreRid = Input(new ROBID(idEntries))
  val dataMergeApply = Input(Bool())
  val dataNoMerge = Input(Bool())
  val priorScbReturned = Input(Bool())
  val priorStqReturned = Input(Bool())
  val priorLineData = Input(UInt((lineBytes * 8).W))
  val priorValidMask = Input(UInt(lineBytes.W))
  val priorRequestComplete = Input(Bool())
  val mergedLineData = Input(UInt((lineBytes * 8).W))
  val mergedValidMask = Input(UInt(lineBytes.W))
  val mergedRequestComplete = Input(Bool())

  val active = Output(Bool())
  val planCandidate = Output(Bool())
  val planValid = Output(Bool())
  val rowWriteValid = Output(Bool())
  val rewaitApply = Output(Bool())
  val dataMergePlan = Output(Bool())
  val dataNoMergePlan = Output(Bool())
  val setWaitStatus = Output(Bool())
  val keepRepickStatus = Output(Bool())
  val clearReturnState = Output(Bool())
  val lineWrite = Output(Bool())
  val waitStoreWrite = Output(Bool())
  val nextWaitStore = Output(Bool())
  val nextWaitStoreInfo = Output(new LoadStoreForwardWait(idEntries, idEntries, pcWidth))
  val nextWaitStoreRid = Output(new ROBID(idEntries))
  val nextLineData = Output(UInt((lineBytes * 8).W))
  val nextValidMask = Output(UInt(lineBytes.W))
  val nextDataComplete = Output(Bool())
  val nextScbReturned = Output(Bool())
  val nextStqReturned = Output(Bool())
  val nextStoreSourceReturned = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoApply = Output(Bool())
  val invalidApplyWithoutStqReturned = Output(Bool())
  val invalidStqReturnedWithoutApply = Output(Bool())
  val invalidStqApplyWithoutScb = Output(Bool())
  val invalidWaitStoreWithMerge = Output(Bool())
  val invalidMergeAndNoData = Output(Bool())
}

class LoadReplaySourceReturnStoreSnapshotRowStatePlan(
    val idEntries: Int = 16,
    val pcWidth: Int = 64,
    val lineBytes: Int = 64)
    extends Module {
  require(idEntries > 1, "ID entries must be greater than one")
  require((idEntries & (idEntries - 1)) == 0, "ID entries must be a power of two")
  require(pcWidth > 0, "pcWidth must be positive")
  require(lineBytes == 64, "row-state plan currently carries 64-byte scalar line data")

  val io = IO(new LoadReplaySourceReturnStoreSnapshotRowStatePlanIO(idEntries, pcWidth, lineBytes))

  private def zeroWait: LoadStoreForwardWait =
    0.U.asTypeOf(new LoadStoreForwardWait(idEntries, idEntries, pcWidth))

  val active = io.enable && !io.flush
  val rawIntent =
    io.applyValid ||
      io.applyStqReturned ||
      io.waitStoreApply ||
      io.dataMergeApply ||
      io.dataNoMerge
  val planCandidate = active && io.applyValid
  val planValid = planCandidate && io.applyStqReturned
  val dataNoMergePlan = planValid && !io.waitStoreApply && io.dataNoMerge
  val dataNoMergeRewait = dataNoMergePlan && !io.priorRequestComplete
  val dataNoMergeComplete = dataNoMergePlan && io.priorRequestComplete
  val rewaitApply = planValid && (io.waitStoreApply || dataNoMergeRewait)
  val dataMergePlan = planValid && !io.waitStoreApply && io.dataMergeApply

  val nextScbReturned = Mux(rewaitApply, false.B, io.priorScbReturned)
  val nextStqReturned = Mux(rewaitApply, false.B, Mux(planValid, true.B, io.priorStqReturned))
  val nextStoreSourceReturned = planValid && !rewaitApply && nextScbReturned && nextStqReturned

  io.active := active
  io.planCandidate := planCandidate
  io.planValid := planValid
  io.rowWriteValid := planValid
  io.rewaitApply := rewaitApply
  io.dataMergePlan := dataMergePlan
  io.dataNoMergePlan := dataNoMergePlan
  io.setWaitStatus := rewaitApply
  io.keepRepickStatus := planValid && !rewaitApply
  io.clearReturnState := rewaitApply
  io.lineWrite := rewaitApply || dataMergePlan || dataNoMergeComplete
  io.waitStoreWrite := planValid
  io.nextWaitStore := planValid && io.waitStoreApply
  io.nextWaitStoreInfo := Mux(planValid && io.waitStoreApply, io.waitStoreInfo, zeroWait)
  io.nextWaitStoreRid := Mux(planValid && io.waitStoreApply, io.waitStoreRid, ROBID.disabled(idEntries))
  io.nextLineData := Mux(rewaitApply, 0.U, Mux(dataMergePlan, io.mergedLineData, io.priorLineData))
  io.nextValidMask := Mux(rewaitApply, 0.U, Mux(dataMergePlan, io.mergedValidMask, io.priorValidMask))
  io.nextDataComplete :=
    planValid && !rewaitApply && Mux(dataMergePlan, io.mergedRequestComplete, io.priorRequestComplete)
  io.nextScbReturned := nextScbReturned
  io.nextStqReturned := nextStqReturned
  io.nextStoreSourceReturned := nextStoreSourceReturned
  io.blockedByDisabled := !io.enable && rawIntent
  io.blockedByFlush := io.enable && io.flush && rawIntent
  io.blockedByNoApply := active && rawIntent && !io.applyValid
  io.invalidApplyWithoutStqReturned := planCandidate && !io.applyStqReturned
  io.invalidStqReturnedWithoutApply := active && io.applyStqReturned && !io.applyValid
  io.invalidStqApplyWithoutScb := planValid && !io.priorScbReturned
  io.invalidWaitStoreWithMerge := planValid && io.waitStoreApply && io.dataMergeApply
  io.invalidMergeAndNoData := planValid && io.dataMergeApply && io.dataNoMerge
}
