package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressClearProofReference {
  final case class Result(
      candidate: Boolean,
      fullMask: Boolean,
      fireCompleteAligned: Boolean,
      clearIntentAligned: Boolean,
      liveClearAligned: Boolean,
      rowFillAligned: Boolean,
      lifecycleClearSuppressed: Boolean,
      allClearAligned: Boolean,
      blockedByPartialMask: Boolean,
      blockedByNoFireComplete: Boolean,
      blockedByNoClearIntent: Boolean,
      blockedByNoLiveClear: Boolean,
      blockedByNoRowFill: Boolean,
      blockedByLifecycleClearStillEnabled: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      liveMaskCandidate: Boolean,
      suppressMask: Int,
      fireComplete: Boolean,
      clearIntent: Boolean,
      liveClear: Boolean,
      rowFillEnable: Boolean,
      lifecycleClearEnable: Boolean,
      lifecycleClearAccepted: Boolean,
      previousFireComplete: Boolean = false,
      previousClearIntent: Boolean = false,
      previousLiveClear: Boolean = false,
      previousRowFillEnable: Boolean = false): Result = {
    val active = enable && !flush
    val candidate = active && liveMaskCandidate
    val fullMaskRaw = suppressMask == 0xf
    val fireEvidence = fireComplete || previousFireComplete
    val clearIntentEvidence = clearIntent || previousClearIntent
    val liveClearEvidence = liveClear || previousLiveClear
    val rowFillEvidence = rowFillEnable || previousRowFillEnable
    val lifecycleClearSuppressed = candidate && !lifecycleClearEnable && !lifecycleClearAccepted
    val allClearAligned =
      candidate && fullMaskRaw && fireEvidence && clearIntentEvidence && liveClearEvidence && rowFillEvidence &&
        lifecycleClearSuppressed

    Result(
      candidate = candidate,
      fullMask = candidate && fullMaskRaw,
      fireCompleteAligned = candidate && fireEvidence,
      clearIntentAligned = candidate && clearIntentEvidence,
      liveClearAligned = candidate && liveClearEvidence,
      rowFillAligned = candidate && rowFillEvidence,
      lifecycleClearSuppressed = lifecycleClearSuppressed,
      allClearAligned = allClearAligned,
      blockedByPartialMask = candidate && !fullMaskRaw,
      blockedByNoFireComplete = candidate && fullMaskRaw && !fireEvidence,
      blockedByNoClearIntent = candidate && fullMaskRaw && fireEvidence && !clearIntentEvidence,
      blockedByNoLiveClear = candidate && fullMaskRaw && fireEvidence && clearIntentEvidence && !liveClearEvidence,
      blockedByNoRowFill = candidate && fullMaskRaw && fireEvidence && clearIntentEvidence && liveClearEvidence &&
        !rowFillEvidence,
      blockedByLifecycleClearStillEnabled =
        candidate && fullMaskRaw && fireEvidence && clearIntentEvidence && liveClearEvidence && rowFillEvidence &&
          (lifecycleClearEnable || lifecycleClearAccepted))
  }
}

class LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressClearProofSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressClearProofReference._

  test("accepts a full live mask with fire-complete and clear timing aligned") {
    val result = apply(
      enable = true,
      flush = false,
      liveMaskCandidate = true,
      suppressMask = 0xf,
      fireComplete = true,
      clearIntent = true,
      liveClear = true,
      rowFillEnable = true,
      lifecycleClearEnable = false,
      lifecycleClearAccepted = false)

    assert(result.candidate)
    assert(result.fullMask)
    assert(result.fireCompleteAligned)
    assert(result.clearIntentAligned)
    assert(result.liveClearAligned)
    assert(result.rowFillAligned)
    assert(result.lifecycleClearSuppressed)
    assert(result.allClearAligned)
  }

  test("accepts a registered live-mask candidate with prior-cycle clear evidence") {
    val result = apply(
      enable = true,
      flush = false,
      liveMaskCandidate = true,
      suppressMask = 0xf,
      fireComplete = false,
      clearIntent = false,
      liveClear = false,
      rowFillEnable = false,
      lifecycleClearEnable = false,
      lifecycleClearAccepted = false,
      previousFireComplete = true,
      previousClearIntent = true,
      previousLiveClear = true,
      previousRowFillEnable = true)

    assert(result.fireCompleteAligned)
    assert(result.clearIntentAligned)
    assert(result.liveClearAligned)
    assert(result.rowFillAligned)
    assert(result.lifecycleClearSuppressed)
    assert(result.allClearAligned)
  }

  test("reports blockers in prerequisite order") {
    val partial = apply(true, false, true, 0x7, true, true, true, true, false, false)
    val noFire = apply(true, false, true, 0xf, false, true, true, true, false, false)
    val noClear = apply(true, false, true, 0xf, true, false, true, true, false, false)
    val noLiveClear = apply(true, false, true, 0xf, true, true, false, true, false, false)
    val noRowFill = apply(true, false, true, 0xf, true, true, true, false, false, false)
    val lifecycleStillEnabled = apply(true, false, true, 0xf, true, true, true, true, true, false)

    assert(partial.blockedByPartialMask)
    assert(noFire.blockedByNoFireComplete)
    assert(noClear.blockedByNoClearIntent)
    assert(noLiveClear.blockedByNoLiveClear)
    assert(noRowFill.blockedByNoRowFill)
    assert(lifecycleStillEnabled.blockedByLifecycleClearStillEnabled)
  }

  test("flush and disabled cycles do not create candidates") {
    val disabled = apply(false, false, true, 0xf, true, true, true, true, false, false)
    val flushed = apply(true, true, true, 0xf, true, true, true, true, false, false)

    assert(!disabled.candidate)
    assert(!flushed.candidate)
    assert(!disabled.allClearAligned)
    assert(!flushed.allClearAligned)
  }

  test("elaboration exposes clear-proof IOs") {
    val sv = ChiselStage.emitSystemVerilog(
      new LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressClearProof,
      firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
    )

    assert(sv.contains("LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressClearProof"))
    assert(sv.contains("io_allClearAligned"))
    assert(sv.contains("io_blockedByLifecycleClearStillEnabled"))
  }
}
