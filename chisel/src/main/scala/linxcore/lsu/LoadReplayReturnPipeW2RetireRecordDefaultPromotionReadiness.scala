package linxcore.lsu

import chisel3._

class LoadReplayReturnPipeW2RetireRecordDefaultPromotionReadinessIO extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val recordValid = Input(Bool())
  val fallbackCandidatesReady = Input(Bool())
  val lifecycleEvidenceProviderValid = Input(Bool())
  val robDuplicatePhysicalComplete = Input(Bool())
  val rfDuplicatePhysicalWriteback = Input(Bool())
  val wakeupDuplicatePhysicalWakeup = Input(Bool())
  val lifecycleClearDuplicatePhysicalClear = Input(Bool())
  val probeActive = Input(Bool())

  val active = Output(Bool())
  val recordCandidate = Output(Bool())
  val preArmModelOrderReady = Output(Bool())
  val anyPhysicalDuplicate = Output(Bool())
  val defaultPromotionReady = Output(Bool())
  val blockedByNoRecord = Output(Bool())
  val blockedByMissingFallbackCandidate = Output(Bool())
  val blockedByMissingLifecycleEvidence = Output(Bool())
  val blockedByPhysicalDuplicate = Output(Bool())
  val blockedByProbeActive = Output(Bool())
}

class LoadReplayReturnPipeW2RetireRecordDefaultPromotionReadiness extends Module {
  val io = IO(new LoadReplayReturnPipeW2RetireRecordDefaultPromotionReadinessIO)

  val active = io.enable && !io.flush
  val recordCandidate = active && io.recordValid
  val preArmModelOrderReady =
    io.fallbackCandidatesReady && io.lifecycleEvidenceProviderValid
  val anyPhysicalDuplicate =
    preArmModelOrderReady &&
      (io.robDuplicatePhysicalComplete ||
        io.rfDuplicatePhysicalWriteback ||
        io.wakeupDuplicatePhysicalWakeup ||
        io.lifecycleClearDuplicatePhysicalClear)
  val promotionWithoutDuplicate = preArmModelOrderReady && !anyPhysicalDuplicate
  val defaultPromotionReady = promotionWithoutDuplicate && !io.probeActive

  io.active := active
  io.recordCandidate := recordCandidate
  io.preArmModelOrderReady := preArmModelOrderReady
  io.anyPhysicalDuplicate := anyPhysicalDuplicate
  io.defaultPromotionReady := defaultPromotionReady
  io.blockedByNoRecord := active && !io.recordValid
  io.blockedByMissingFallbackCandidate := recordCandidate && !io.fallbackCandidatesReady
  io.blockedByMissingLifecycleEvidence :=
    io.fallbackCandidatesReady && !io.lifecycleEvidenceProviderValid
  io.blockedByPhysicalDuplicate := anyPhysicalDuplicate
  io.blockedByProbeActive := promotionWithoutDuplicate && io.probeActive
}
