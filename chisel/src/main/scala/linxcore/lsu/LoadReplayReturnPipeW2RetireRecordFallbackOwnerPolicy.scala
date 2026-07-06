package linxcore.lsu

import chisel3._

class LoadReplayReturnPipeW2RetireRecordFallbackOwnerPolicyIO extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val globalFallbackEnable = Input(Bool())
  val recordValid = Input(Bool())
  val robCandidate = Input(Bool())
  val rfCandidate = Input(Bool())
  val wakeupCandidate = Input(Bool())
  val lifecycleClearCandidate = Input(Bool())
  val robDuplicatePhysicalComplete = Input(Bool())
  val rfDuplicatePhysicalWriteback = Input(Bool())
  val wakeupDuplicatePhysicalWakeup = Input(Bool())
  val lifecycleClearDuplicatePhysicalClear = Input(Bool())

  val active = Output(Bool())
  val recordCandidate = Output(Bool())
  val allFallbackCandidatesReady = Output(Bool())
  val anyPhysicalDuplicate = Output(Bool())
  val retainedSoleOwnerEligible = Output(Bool())
  val sideEffectOwnerEnable = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoRecord = Output(Bool())
  val blockedByMissingFallbackCandidate = Output(Bool())
  val blockedByPhysicalDuplicate = Output(Bool())
  val blockedByGlobalFallbackDisabled = Output(Bool())
}

class LoadReplayReturnPipeW2RetireRecordFallbackOwnerPolicy extends Module {
  val io = IO(new LoadReplayReturnPipeW2RetireRecordFallbackOwnerPolicyIO)

  val active = io.enable && !io.flush
  val recordCandidate = active && io.recordValid
  val allFallbackCandidatesReady =
    recordCandidate &&
      io.robCandidate &&
      io.rfCandidate &&
      io.wakeupCandidate &&
      io.lifecycleClearCandidate
  val anyPhysicalDuplicate =
    allFallbackCandidatesReady &&
      (io.robDuplicatePhysicalComplete ||
        io.rfDuplicatePhysicalWriteback ||
        io.wakeupDuplicatePhysicalWakeup ||
        io.lifecycleClearDuplicatePhysicalClear)
  val retainedSoleOwnerEligible = allFallbackCandidatesReady && !anyPhysicalDuplicate
  val sideEffectOwnerEnable = io.globalFallbackEnable && retainedSoleOwnerEligible

  io.active := active
  io.recordCandidate := recordCandidate
  io.allFallbackCandidatesReady := allFallbackCandidatesReady
  io.anyPhysicalDuplicate := anyPhysicalDuplicate
  io.retainedSoleOwnerEligible := retainedSoleOwnerEligible
  io.sideEffectOwnerEnable := sideEffectOwnerEnable
  io.blockedByDisabled := !io.enable && io.recordValid
  io.blockedByFlush := io.enable && io.flush && io.recordValid
  io.blockedByNoRecord := active && !io.recordValid
  io.blockedByMissingFallbackCandidate :=
    recordCandidate &&
      !(io.robCandidate &&
        io.rfCandidate &&
        io.wakeupCandidate &&
        io.lifecycleClearCandidate)
  io.blockedByPhysicalDuplicate := anyPhysicalDuplicate
  io.blockedByGlobalFallbackDisabled := retainedSoleOwnerEligible && !io.globalFallbackEnable
}
