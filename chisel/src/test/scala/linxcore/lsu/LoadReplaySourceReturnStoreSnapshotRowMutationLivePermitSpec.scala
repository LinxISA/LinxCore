package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplaySourceReturnStoreSnapshotRowMutationLivePermitReference {
  final case class Result(
      active: Boolean,
      headProofReady: Boolean,
      headProofTargetMask: Int,
      livePermit: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByHeadProof: Boolean,
      blockedByHeadInvalidRow: Boolean,
      blockedByHeadScbNotReturned: Boolean,
      blockedByHeadNotRepick: Boolean,
      blockedByHeadTargetMismatch: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      liveEnable: Boolean,
      targetReady: Boolean,
      targetMask: Int,
      headTargetsRow: Boolean,
      headRepick: Boolean,
      headApplyEligible: Boolean,
      headProofTargetMask: Int,
      headBlockedByInvalidRow: Boolean = false,
      headBlockedByScbNotReturned: Boolean = false): Result = {
    val active = enable && !flush
    val targetCandidate = active && liveEnable && targetReady
    val headProofReady =
      active &&
        headApplyEligible &&
        targetMask != 0 &&
        targetMask == headProofTargetMask

    Result(
      active = active,
      headProofReady = headProofReady,
      headProofTargetMask = headProofTargetMask,
      livePermit = liveEnable && headProofReady,
      blockedByDisabled = !enable && liveEnable && targetReady,
      blockedByFlush = enable && flush && liveEnable && targetReady,
      blockedByHeadProof = targetCandidate && !headProofReady,
      blockedByHeadInvalidRow = targetCandidate && headBlockedByInvalidRow,
      blockedByHeadScbNotReturned = targetCandidate && headBlockedByScbNotReturned,
      blockedByHeadNotRepick = targetCandidate && headTargetsRow && !headRepick,
      blockedByHeadTargetMismatch = targetCandidate && headApplyEligible && targetMask != headProofTargetMask)
  }
}

class LoadReplaySourceReturnStoreSnapshotRowMutationLivePermitSpec extends AnyFunSuite {
  import LoadReplaySourceReturnStoreSnapshotRowMutationLivePermitReference._

  test("permits live mutation only when target and reduced head proof match") {
    val result = LoadReplaySourceReturnStoreSnapshotRowMutationLivePermitReference(
      enable = true,
      flush = false,
      liveEnable = true,
      targetReady = true,
      targetMask = 0x4,
      headTargetsRow = true,
      headRepick = true,
      headApplyEligible = true,
      headProofTargetMask = 0x4)

    assert(result.active)
    assert(result.headProofReady)
    assert(result.livePermit)
    assert(!result.blockedByHeadProof)
    assert(!result.blockedByHeadTargetMismatch)
  }

  test("keeps candidate blocked when live disabled or head proof is absent") {
    val disabled = LoadReplaySourceReturnStoreSnapshotRowMutationLivePermitReference(
      enable = true,
      flush = false,
      liveEnable = false,
      targetReady = true,
      targetMask = 0x4,
      headTargetsRow = true,
      headRepick = true,
      headApplyEligible = true,
      headProofTargetMask = 0x4)
    val noProof = LoadReplaySourceReturnStoreSnapshotRowMutationLivePermitReference(
      enable = true,
      flush = false,
      liveEnable = true,
      targetReady = true,
      targetMask = 0x4,
      headTargetsRow = true,
      headRepick = true,
      headApplyEligible = false,
      headProofTargetMask = 0x4)

    assert(disabled.headProofReady)
    assert(!disabled.livePermit)
    assert(!disabled.blockedByHeadProof)
    assert(!noProof.headProofReady)
    assert(!noProof.livePermit)
    assert(noProof.blockedByHeadProof)
  }

  test("reports row proof blocker reasons independently") {
    val invalid = LoadReplaySourceReturnStoreSnapshotRowMutationLivePermitReference(
      enable = true,
      flush = false,
      liveEnable = true,
      targetReady = true,
      targetMask = 0x4,
      headTargetsRow = true,
      headRepick = true,
      headApplyEligible = false,
      headProofTargetMask = 0x4,
      headBlockedByInvalidRow = true)
    val noScb = LoadReplaySourceReturnStoreSnapshotRowMutationLivePermitReference(
      enable = true,
      flush = false,
      liveEnable = true,
      targetReady = true,
      targetMask = 0x4,
      headTargetsRow = true,
      headRepick = true,
      headApplyEligible = false,
      headProofTargetMask = 0x4,
      headBlockedByScbNotReturned = true)
    val notRepick = LoadReplaySourceReturnStoreSnapshotRowMutationLivePermitReference(
      enable = true,
      flush = false,
      liveEnable = true,
      targetReady = true,
      targetMask = 0x4,
      headTargetsRow = true,
      headRepick = false,
      headApplyEligible = false,
      headProofTargetMask = 0x4)

    assert(invalid.blockedByHeadInvalidRow)
    assert(noScb.blockedByHeadScbNotReturned)
    assert(notRepick.blockedByHeadNotRepick)
    assert(Seq(invalid, noScb, notRepick).forall(result => result.blockedByHeadProof && !result.livePermit))
  }

  test("reports disabled flush and target mismatch blockers") {
    val disabled = LoadReplaySourceReturnStoreSnapshotRowMutationLivePermitReference(
      enable = false,
      flush = false,
      liveEnable = true,
      targetReady = true,
      targetMask = 0x4,
      headTargetsRow = true,
      headRepick = true,
      headApplyEligible = true,
      headProofTargetMask = 0x4)
    val flushed = LoadReplaySourceReturnStoreSnapshotRowMutationLivePermitReference(
      enable = true,
      flush = true,
      liveEnable = true,
      targetReady = true,
      targetMask = 0x4,
      headTargetsRow = true,
      headRepick = true,
      headApplyEligible = true,
      headProofTargetMask = 0x4)
    val mismatch = LoadReplaySourceReturnStoreSnapshotRowMutationLivePermitReference(
      enable = true,
      flush = false,
      liveEnable = true,
      targetReady = true,
      targetMask = 0x4,
      headTargetsRow = true,
      headRepick = true,
      headApplyEligible = true,
      headProofTargetMask = 0x2)

    assert(disabled.blockedByDisabled)
    assert(flushed.blockedByFlush)
    assert(mismatch.blockedByHeadProof)
    assert(mismatch.blockedByHeadTargetMismatch)
    assert(!disabled.livePermit)
    assert(!flushed.livePermit)
    assert(!mismatch.livePermit)
  }

  test("Chisel LoadReplaySourceReturnStoreSnapshotRowMutationLivePermit elaborates guard diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplaySourceReturnStoreSnapshotRowMutationLivePermit)

    assert(sv.contains("module LoadReplaySourceReturnStoreSnapshotRowMutationLivePermit"))
    assert(sv.contains("io_headProofReady"))
    assert(sv.contains("io_livePermit"))
    assert(sv.contains("io_blockedByHeadProof"))
    assert(sv.contains("io_blockedByHeadInvalidRow"))
    assert(sv.contains("io_blockedByHeadScbNotReturned"))
    assert(sv.contains("io_blockedByHeadTargetMismatch"))
  }
}
