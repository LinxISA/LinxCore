package linxcore.lsu

import chisel3._

class LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressClearProofIO extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val liveMaskCandidate = Input(Bool())
  val suppressMask = Input(UInt(4.W))
  val fireComplete = Input(Bool())
  val clearIntent = Input(Bool())
  val liveClear = Input(Bool())
  val rowFillEnable = Input(Bool())
  val lifecycleClearEnable = Input(Bool())
  val lifecycleClearAccepted = Input(Bool())

  val candidate = Output(Bool())
  val fullMask = Output(Bool())
  val fireCompleteAligned = Output(Bool())
  val clearIntentAligned = Output(Bool())
  val liveClearAligned = Output(Bool())
  val rowFillAligned = Output(Bool())
  val lifecycleClearSuppressed = Output(Bool())
  val allClearAligned = Output(Bool())
  val blockedByPartialMask = Output(Bool())
  val blockedByNoFireComplete = Output(Bool())
  val blockedByNoClearIntent = Output(Bool())
  val blockedByNoLiveClear = Output(Bool())
  val blockedByNoRowFill = Output(Bool())
  val blockedByLifecycleClearStillEnabled = Output(Bool())
}

class LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressClearProof extends Module {
  val io = IO(new LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressClearProofIO)

  val active = io.enable && !io.flush
  val candidate = active && io.liveMaskCandidate
  val anyClearEvidence = active && (io.fireComplete || io.clearIntent || io.liveClear || io.rowFillEnable)
  val fireCompleteReg = RegInit(false.B)
  val clearIntentReg = RegInit(false.B)
  val liveClearReg = RegInit(false.B)
  val rowFillEnableReg = RegInit(false.B)

  when(!io.enable || io.flush) {
    fireCompleteReg := false.B
    clearIntentReg := false.B
    liveClearReg := false.B
    rowFillEnableReg := false.B
  }.elsewhen(candidate) {
    fireCompleteReg := false.B
    clearIntentReg := false.B
    liveClearReg := false.B
    rowFillEnableReg := false.B
  }.elsewhen(anyClearEvidence) {
    fireCompleteReg := io.fireComplete
    clearIntentReg := io.clearIntent
    liveClearReg := io.liveClear
    rowFillEnableReg := io.rowFillEnable
  }

  val fullMask = io.suppressMask === "b1111".U
  val fireCompleteAligned = io.fireComplete || fireCompleteReg
  val clearIntentAligned = io.clearIntent || clearIntentReg
  val liveClearAligned = io.liveClear || liveClearReg
  val rowFillAligned = io.rowFillEnable || rowFillEnableReg
  val lifecycleClearSuppressed = candidate && !io.lifecycleClearEnable && !io.lifecycleClearAccepted
  val allClearAligned =
    candidate &&
      fullMask &&
      fireCompleteAligned &&
      clearIntentAligned &&
      liveClearAligned &&
      rowFillAligned &&
      lifecycleClearSuppressed

  io.candidate := candidate
  io.fullMask := candidate && fullMask
  io.fireCompleteAligned := candidate && fireCompleteAligned
  io.clearIntentAligned := candidate && clearIntentAligned
  io.liveClearAligned := candidate && liveClearAligned
  io.rowFillAligned := candidate && rowFillAligned
  io.lifecycleClearSuppressed := lifecycleClearSuppressed
  io.allClearAligned := allClearAligned
  io.blockedByPartialMask := candidate && !fullMask
  io.blockedByNoFireComplete := candidate && fullMask && !fireCompleteAligned
  io.blockedByNoClearIntent := candidate && fullMask && fireCompleteAligned && !clearIntentAligned
  io.blockedByNoLiveClear := candidate && fullMask && fireCompleteAligned && clearIntentAligned && !liveClearAligned
  io.blockedByNoRowFill :=
    candidate && fullMask && fireCompleteAligned && clearIntentAligned && liveClearAligned && !rowFillAligned
  io.blockedByLifecycleClearStillEnabled :=
    candidate &&
      fullMask &&
      fireCompleteAligned &&
      clearIntentAligned &&
      liveClearAligned &&
      rowFillAligned &&
      (io.lifecycleClearEnable || io.lifecycleClearAccepted)
}
