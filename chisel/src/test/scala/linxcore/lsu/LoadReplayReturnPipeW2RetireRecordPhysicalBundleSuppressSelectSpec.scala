package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressSelectReference {
  final case class Result(
      planPromotedCandidate: Boolean,
      selected: Boolean,
      selectedMask: Int,
      allOrNoneInputMask: Boolean,
      selectedFromProbe: Boolean,
      selectedFromPromotion: Boolean,
      blockedByPromoteDisabled: Boolean,
      blockedByNoPlanCandidate: Boolean,
      blockedByPartialPlanMask: Boolean,
      invalidProbePromotionMaskMismatch: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      promoteEnable: Boolean,
      planAtomicSuppressCandidate: Boolean,
      planSuppressMask: Int,
      planAllOrNoneSuppress: Boolean,
      probeSelected: Boolean,
      probeSelectedMask: Int,
      probeAllOrNoneInputMask: Boolean): Result = {
    val active = enable && !flush
    val planCandidate = active && planAtomicSuppressCandidate
    val planFullMask = planAllOrNoneSuppress && (planSuppressMask & 0xf) == 0xf
    val planPromotedCandidate = planCandidate && promoteEnable && planFullMask
    val probeFullCandidate =
      active && probeSelected && probeAllOrNoneInputMask && (probeSelectedMask & 0xf) == 0xf
    val selected = probeFullCandidate || planPromotedCandidate
    val selectedMask = if (selected) 0xf else 0

    Result(
      planPromotedCandidate = planPromotedCandidate,
      selected = selected,
      selectedMask = selectedMask,
      allOrNoneInputMask = selectedMask == 0 || selectedMask == 0xf,
      selectedFromProbe = probeFullCandidate,
      selectedFromPromotion = planPromotedCandidate,
      blockedByPromoteDisabled = planCandidate && !promoteEnable,
      blockedByNoPlanCandidate = active && promoteEnable && !planAtomicSuppressCandidate,
      blockedByPartialPlanMask = planCandidate && promoteEnable && !planFullMask,
      invalidProbePromotionMaskMismatch =
        active && probeSelected && planPromotedCandidate && ((probeSelectedMask & 0xf) != (planSuppressMask & 0xf)))
  }
}

class LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressSelectSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressSelectReference._

  test("selects a promoted full physical bundle when the promotion knob is enabled") {
    val result = apply(
      enable = true,
      flush = false,
      promoteEnable = true,
      planAtomicSuppressCandidate = true,
      planSuppressMask = 0xf,
      planAllOrNoneSuppress = true,
      probeSelected = false,
      probeSelectedMask = 0,
      probeAllOrNoneInputMask = true)

    assert(result.planPromotedCandidate)
    assert(result.selected)
    assert(result.selectedFromPromotion)
    assert(!result.selectedFromProbe)
    assert(result.selectedMask == 0xf)
    assert(result.allOrNoneInputMask)
  }

  test("preserves the existing probe path when promotion is disabled") {
    val result = apply(
      enable = true,
      flush = false,
      promoteEnable = false,
      planAtomicSuppressCandidate = true,
      planSuppressMask = 0xf,
      planAllOrNoneSuppress = true,
      probeSelected = true,
      probeSelectedMask = 0xf,
      probeAllOrNoneInputMask = true)

    assert(result.selected)
    assert(result.selectedFromProbe)
    assert(!result.selectedFromPromotion)
    assert(result.blockedByPromoteDisabled)
  }

  test("rejects partial or missing promoted plan candidates") {
    val partial = apply(true, false, true, true, 0x7, false, false, 0, true)
    val missing = apply(true, false, true, false, 0, true, false, 0, true)

    assert(!partial.selected)
    assert(partial.blockedByPartialPlanMask)
    assert(!missing.selected)
    assert(missing.blockedByNoPlanCandidate)
  }

  test("reports a probe and promotion mask mismatch") {
    val result = apply(
      enable = true,
      flush = false,
      promoteEnable = true,
      planAtomicSuppressCandidate = true,
      planSuppressMask = 0xf,
      planAllOrNoneSuppress = true,
      probeSelected = true,
      probeSelectedMask = 0x7,
      probeAllOrNoneInputMask = true)

    assert(result.selectedFromPromotion)
    assert(result.invalidProbePromotionMaskMismatch)
  }

  test("flush and disabled cycles do not select a boundary") {
    val disabled = apply(false, false, true, true, 0xf, true, false, 0, true)
    val flushed = apply(true, true, true, true, 0xf, true, false, 0, true)

    assert(!disabled.selected)
    assert(!flushed.selected)
  }

  test("Chisel LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressSelect elaborates") {
    val sv = ChiselStage.emitSystemVerilog(
      new LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressSelect,
      firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
    )

    assert(sv.contains("LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressSelect"))
    assert(sv.contains("io_selectedFromPromotion"))
    assert(sv.contains("io_invalidProbePromotionMaskMismatch"))
  }
}
