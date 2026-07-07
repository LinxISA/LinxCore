package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressLiveMaskReference {
  final case class Result(
      maskCandidate: Boolean,
      liveMaskCandidate: Boolean,
      suppressMask: Int,
      allOrNoneMask: Boolean,
      blockedByLiveMaskDisabled: Boolean,
      blockedByNoEligibleOwnership: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      liveMaskEnable: Boolean,
      eligibleRegisteredMask: Boolean): Result = {
    val active = enable && !flush
    val maskCandidate = active && eligibleRegisteredMask
    val liveMaskCandidate = maskCandidate && liveMaskEnable
    val suppressMask = if (liveMaskCandidate) 0xf else 0

    Result(
      maskCandidate = maskCandidate,
      liveMaskCandidate = liveMaskCandidate,
      suppressMask = suppressMask,
      allOrNoneMask = suppressMask == 0 || suppressMask == 0xf,
      blockedByLiveMaskDisabled = maskCandidate && !liveMaskEnable,
      blockedByNoEligibleOwnership = active && !eligibleRegisteredMask)
  }
}

class LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressLiveMaskSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressLiveMaskReference._

  test("selects the full physical bundle only when ownership and live mask are enabled") {
    val result = apply(
      enable = true,
      flush = false,
      liveMaskEnable = true,
      eligibleRegisteredMask = true)

    assert(result.maskCandidate)
    assert(result.liveMaskCandidate)
    assert(result.suppressMask == 0xf)
    assert(result.allOrNoneMask)
    assert(!result.blockedByLiveMaskDisabled)
    assert(!result.blockedByNoEligibleOwnership)
  }

  test("keeps an eligible ownership candidate blocked when the live mask knob is disabled") {
    val result = apply(
      enable = true,
      flush = false,
      liveMaskEnable = false,
      eligibleRegisteredMask = true)

    assert(result.maskCandidate)
    assert(!result.liveMaskCandidate)
    assert(result.suppressMask == 0)
    assert(result.allOrNoneMask)
    assert(result.blockedByLiveMaskDisabled)
  }

  test("does not emit a partial mask for idle, disabled, or flushed cycles") {
    val idle = apply(enable = true, flush = false, liveMaskEnable = true, eligibleRegisteredMask = false)
    val disabled = apply(enable = false, flush = false, liveMaskEnable = true, eligibleRegisteredMask = true)
    val flushed = apply(enable = true, flush = true, liveMaskEnable = true, eligibleRegisteredMask = true)

    for (result <- Seq(idle, disabled, flushed)) {
      assert(result.suppressMask == 0)
      assert(result.allOrNoneMask)
      assert(!result.liveMaskCandidate)
    }
    assert(idle.blockedByNoEligibleOwnership)
    assert(!disabled.blockedByNoEligibleOwnership)
    assert(!flushed.blockedByNoEligibleOwnership)
  }

  test("elaboration exposes live-mask IOs") {
    val sv = ChiselStage.emitSystemVerilog(
      new LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressLiveMask,
      firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
    )

    assert(sv.contains("LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressLiveMask"))
    assert(sv.contains("io_liveMaskCandidate"))
    assert(sv.contains("io_suppressMask"))
  }
}
