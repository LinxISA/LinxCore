package linxcore.lsu

import chisel3._

class LoadReplaySourceReturnStoreSnapshotRowMutationLivePermitIO(val liqEntries: Int) extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val liveEnable = Input(Bool())
  val targetReady = Input(Bool())
  val targetMask = Input(UInt(liqEntries.W))
  val headTargetsRow = Input(Bool())
  val headRepick = Input(Bool())
  val headApplyEligible = Input(Bool())
  val headProofTargetMask = Input(UInt(liqEntries.W))
  val headBlockedByInvalidRow = Input(Bool())
  val headBlockedByScbNotReturned = Input(Bool())

  val active = Output(Bool())
  val headProofReady = Output(Bool())
  val headProofTargetMaskOut = Output(UInt(liqEntries.W))
  val livePermit = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByHeadProof = Output(Bool())
  val blockedByHeadInvalidRow = Output(Bool())
  val blockedByHeadScbNotReturned = Output(Bool())
  val blockedByHeadNotRepick = Output(Bool())
  val blockedByHeadTargetMismatch = Output(Bool())
}

class LoadReplaySourceReturnStoreSnapshotRowMutationLivePermit(val liqEntries: Int = 4) extends Module {
  require(liqEntries > 1, "LIQ entries must be greater than one")
  require((liqEntries & (liqEntries - 1)) == 0, "LIQ entries must be a power of two")

  val io = IO(new LoadReplaySourceReturnStoreSnapshotRowMutationLivePermitIO(liqEntries))

  val active = io.enable && !io.flush
  val targetCandidate = active && io.liveEnable && io.targetReady
  val headProofReady =
    active &&
      io.headApplyEligible &&
      io.targetMask.orR &&
      (io.targetMask === io.headProofTargetMask)

  io.active := active
  io.headProofReady := headProofReady
  io.headProofTargetMaskOut := io.headProofTargetMask
  io.livePermit := io.liveEnable && headProofReady
  io.blockedByDisabled := !io.enable && io.liveEnable && io.targetReady
  io.blockedByFlush := io.enable && io.flush && io.liveEnable && io.targetReady
  io.blockedByHeadProof := targetCandidate && !headProofReady
  io.blockedByHeadInvalidRow := targetCandidate && io.headBlockedByInvalidRow
  io.blockedByHeadScbNotReturned := targetCandidate && io.headBlockedByScbNotReturned
  io.blockedByHeadNotRepick := targetCandidate && io.headTargetsRow && !io.headRepick
  io.blockedByHeadTargetMismatch :=
    targetCandidate && io.headApplyEligible && (io.targetMask =/= io.headProofTargetMask)
}
