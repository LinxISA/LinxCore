package linxcore.lsu

import chisel3._

class LoadReplayReturnPipeW2RetireRecordBundleTransferPlanIO extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val recordValid = Input(Bool())
  val preArmModelOrderReady = Input(Bool())
  val defaultPromotionReady = Input(Bool())
  val duplicateVectorValid = Input(Bool())
  val modelOrderDuplicateBundle = Input(Bool())
  val partialDuplicateVector = Input(Bool())
  val probeActive = Input(Bool())

  val active = Output(Bool())
  val recordCandidate = Output(Bool())
  val preArmReady = Output(Bool())
  val modelOrderBundle = Output(Bool())
  val defaultTransferCandidate = Output(Bool())
  val requiresPhysicalBundleSuppression = Output(Bool())
  val defaultPromotionAlreadyReady = Output(Bool())
  val blockedByNoRecord = Output(Bool())
  val blockedByPreArmNotReady = Output(Bool())
  val blockedByNoDuplicateVector = Output(Bool())
  val blockedByPartialDuplicate = Output(Bool())
  val blockedByProbeActive = Output(Bool())
}

class LoadReplayReturnPipeW2RetireRecordBundleTransferPlan extends Module {
  val io = IO(new LoadReplayReturnPipeW2RetireRecordBundleTransferPlanIO)

  val active = io.enable && !io.flush
  val recordCandidate = active && io.recordValid
  val preArmReady = recordCandidate && io.preArmModelOrderReady
  val modelOrderBundle =
    preArmReady &&
      io.duplicateVectorValid &&
      io.modelOrderDuplicateBundle
  val defaultPromotionAlreadyReady = preArmReady && io.defaultPromotionReady
  val defaultTransferCandidate =
    modelOrderBundle &&
      !defaultPromotionAlreadyReady &&
      !io.probeActive

  io.active := active
  io.recordCandidate := recordCandidate
  io.preArmReady := preArmReady
  io.modelOrderBundle := modelOrderBundle
  io.defaultTransferCandidate := defaultTransferCandidate
  io.requiresPhysicalBundleSuppression := defaultTransferCandidate
  io.defaultPromotionAlreadyReady := defaultPromotionAlreadyReady
  io.blockedByNoRecord := active && !io.recordValid
  io.blockedByPreArmNotReady := recordCandidate && !io.preArmModelOrderReady
  io.blockedByNoDuplicateVector :=
    preArmReady &&
      !io.defaultPromotionReady &&
      !io.duplicateVectorValid
  io.blockedByPartialDuplicate :=
    preArmReady &&
      io.duplicateVectorValid &&
      io.partialDuplicateVector
  io.blockedByProbeActive :=
    modelOrderBundle &&
      !defaultPromotionAlreadyReady &&
      io.probeActive
}
