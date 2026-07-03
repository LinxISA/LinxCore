package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2SideEffectLiveControlReference {
  final case class Result(
      active: Boolean,
      requestActive: Boolean,
      candidateValid: Boolean,
      requiredMask: Int,
      liveEnableMask: Int,
      anyRequired: Boolean,
      allRequiredLiveEnabled: Boolean,
      resolveLiveEnable: Boolean,
      writebackLiveEnable: Boolean,
      wakeupLiveEnable: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByNoCandidate: Boolean,
      blockedByNoRequiredSink: Boolean,
      blockedByLiveDisabled: Boolean)

  def mask(resolve: Boolean, writeback: Boolean, wakeup: Boolean): Int =
    (if (wakeup) 4 else 0) |
      (if (writeback) 2 else 0) |
      (if (resolve) 1 else 0)

  def apply(
      enable: Boolean,
      flush: Boolean,
      liveRequested: Boolean,
      sideEffectCandidateValid: Boolean,
      resolveRequired: Boolean,
      writebackRequired: Boolean,
      wakeupRequired: Boolean): Result = {
    val active = enable && !flush
    val requestActive = active && liveRequested
    val candidateValid = active && sideEffectCandidateValid
    val requiredMask = mask(resolveRequired, writebackRequired, wakeupRequired)
    val anyRequired = requiredMask != 0
    val liveAllowed = candidateValid && liveRequested && anyRequired
    val liveEnableMask = if (liveAllowed) requiredMask else 0

    Result(
      active = active,
      requestActive = requestActive,
      candidateValid = candidateValid,
      requiredMask = requiredMask,
      liveEnableMask = liveEnableMask,
      anyRequired = anyRequired,
      allRequiredLiveEnabled = liveAllowed,
      resolveLiveEnable = (liveEnableMask & 0x1) != 0,
      writebackLiveEnable = (liveEnableMask & 0x2) != 0,
      wakeupLiveEnable = (liveEnableMask & 0x4) != 0,
      blockedByDisabled = !enable && sideEffectCandidateValid,
      blockedByFlush = enable && flush && sideEffectCandidateValid,
      blockedByNoCandidate = active && !sideEffectCandidateValid,
      blockedByNoRequiredSink = candidateValid && !anyRequired,
      blockedByLiveDisabled = candidateValid && anyRequired && !liveRequested)
  }
}

class LoadReplayReturnPipeW2SideEffectLiveControlSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2SideEffectLiveControlReference._

  test("keeps every sink disabled when the top-level live request is false") {
    val result = LoadReplayReturnPipeW2SideEffectLiveControlReference(
      enable = true,
      flush = false,
      liveRequested = false,
      sideEffectCandidateValid = true,
      resolveRequired = true,
      writebackRequired = true,
      wakeupRequired = true)

    assert(result.active)
    assert(!result.requestActive)
    assert(result.candidateValid)
    assert(result.requiredMask == 0x7)
    assert(result.liveEnableMask == 0x0)
    assert(!result.allRequiredLiveEnabled)
    assert(!result.resolveLiveEnable)
    assert(!result.writebackLiveEnable)
    assert(!result.wakeupLiveEnable)
    assert(result.blockedByLiveDisabled)
  }

  test("enables only the side-effect sinks required by the resident W2 entry") {
    val result = LoadReplayReturnPipeW2SideEffectLiveControlReference(
      enable = true,
      flush = false,
      liveRequested = true,
      sideEffectCandidateValid = true,
      resolveRequired = true,
      writebackRequired = false,
      wakeupRequired = true)

    assert(result.requestActive)
    assert(result.requiredMask == 0x5)
    assert(result.liveEnableMask == 0x5)
    assert(result.allRequiredLiveEnabled)
    assert(result.resolveLiveEnable)
    assert(!result.writebackLiveEnable)
    assert(result.wakeupLiveEnable)
    assert(!result.blockedByLiveDisabled)
  }

  test("reports disabled and flushed candidates without arming sinks") {
    val disabled = LoadReplayReturnPipeW2SideEffectLiveControlReference(
      enable = false,
      flush = false,
      liveRequested = true,
      sideEffectCandidateValid = true,
      resolveRequired = true,
      writebackRequired = false,
      wakeupRequired = false)
    val flushed = LoadReplayReturnPipeW2SideEffectLiveControlReference(
      enable = true,
      flush = true,
      liveRequested = true,
      sideEffectCandidateValid = true,
      resolveRequired = true,
      writebackRequired = false,
      wakeupRequired = false)

    assert(disabled.blockedByDisabled)
    assert(!disabled.candidateValid)
    assert(disabled.liveEnableMask == 0x0)
    assert(flushed.blockedByFlush)
    assert(!flushed.candidateValid)
    assert(flushed.liveEnableMask == 0x0)
  }

  test("separates no-candidate and no-required-sink blockers") {
    val noCandidate = LoadReplayReturnPipeW2SideEffectLiveControlReference(
      enable = true,
      flush = false,
      liveRequested = true,
      sideEffectCandidateValid = false,
      resolveRequired = true,
      writebackRequired = true,
      wakeupRequired = true)
    val noRequired = LoadReplayReturnPipeW2SideEffectLiveControlReference(
      enable = true,
      flush = false,
      liveRequested = true,
      sideEffectCandidateValid = true,
      resolveRequired = false,
      writebackRequired = false,
      wakeupRequired = false)

    assert(noCandidate.blockedByNoCandidate)
    assert(!noCandidate.candidateValid)
    assert(noCandidate.liveEnableMask == 0x0)
    assert(noRequired.candidateValid)
    assert(noRequired.blockedByNoRequiredSink)
    assert(noRequired.liveEnableMask == 0x0)
    assert(!noRequired.allRequiredLiveEnabled)
  }

  test("Chisel LoadReplayReturnPipeW2SideEffectLiveControl elaborates live masks") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPipeW2SideEffectLiveControl)

    assert(sv.contains("module LoadReplayReturnPipeW2SideEffectLiveControl"))
    assert(sv.contains("io_liveRequested"))
    assert(sv.contains("io_requiredMask"))
    assert(sv.contains("io_liveEnableMask"))
    assert(sv.contains("io_resolveLiveEnable"))
  }
}
