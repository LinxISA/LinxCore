package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnSideEffectLiveControlReference {
  final case class Result(
      active: Boolean,
      requestActive: Boolean,
      candidateValid: Boolean,
      requiredMask: Int,
      liveEnableMask: Int,
      anyRequired: Boolean,
      allRequiredLiveEnabled: Boolean,
      publishLiveEnable: Boolean,
      writebackLiveEnable: Boolean,
      wakeupLiveEnable: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByNoPayload: Boolean,
      blockedByLiveDisabled: Boolean)

  def mask(payloadValid: Boolean, writeback: Boolean, wakeup: Boolean): Int =
    if (payloadValid) {
      0x1 |
        (if (writeback) 0x2 else 0) |
        (if (wakeup) 0x4 else 0)
    } else {
      0
    }

  def apply(
      enable: Boolean,
      flush: Boolean,
      liveRequested: Boolean,
      payloadValid: Boolean,
      writebackRequired: Boolean,
      wakeupRequired: Boolean): Result = {
    val active = enable && !flush
    val requestActive = active && liveRequested
    val candidateValid = active && payloadValid
    val requiredMask = mask(payloadValid, writebackRequired, wakeupRequired)
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
      publishLiveEnable = (liveEnableMask & 0x1) != 0,
      writebackLiveEnable = (liveEnableMask & 0x2) != 0,
      wakeupLiveEnable = (liveEnableMask & 0x4) != 0,
      blockedByDisabled = !enable && payloadValid,
      blockedByFlush = enable && flush && payloadValid,
      blockedByNoPayload = active && !payloadValid,
      blockedByLiveDisabled = candidateValid && anyRequired && !liveRequested)
  }
}

class LoadReplayReturnSideEffectLiveControlSpec extends AnyFunSuite {
  import LoadReplayReturnSideEffectLiveControlReference._

  test("keeps every pre-W2 side-effect sink disabled when live request is false") {
    val result = LoadReplayReturnSideEffectLiveControlReference(
      enable = true,
      flush = false,
      liveRequested = false,
      payloadValid = true,
      writebackRequired = true,
      wakeupRequired = true)

    assert(result.active)
    assert(!result.requestActive)
    assert(result.candidateValid)
    assert(result.requiredMask == 0x7)
    assert(result.liveEnableMask == 0x0)
    assert(result.anyRequired)
    assert(!result.allRequiredLiveEnabled)
    assert(!result.publishLiveEnable)
    assert(!result.writebackLiveEnable)
    assert(!result.wakeupLiveEnable)
    assert(result.blockedByLiveDisabled)
  }

  test("enables publish plus only the optional sinks required by the payload") {
    val result = LoadReplayReturnSideEffectLiveControlReference(
      enable = true,
      flush = false,
      liveRequested = true,
      payloadValid = true,
      writebackRequired = true,
      wakeupRequired = false)

    assert(result.requestActive)
    assert(result.requiredMask == 0x3)
    assert(result.liveEnableMask == 0x3)
    assert(result.allRequiredLiveEnabled)
    assert(result.publishLiveEnable)
    assert(result.writebackLiveEnable)
    assert(!result.wakeupLiveEnable)
    assert(!result.blockedByLiveDisabled)
  }

  test("supports LRET-only payload publication as the base required sink") {
    val result = LoadReplayReturnSideEffectLiveControlReference(
      enable = true,
      flush = false,
      liveRequested = true,
      payloadValid = true,
      writebackRequired = false,
      wakeupRequired = false)

    assert(result.requiredMask == 0x1)
    assert(result.liveEnableMask == 0x1)
    assert(result.publishLiveEnable)
    assert(!result.writebackLiveEnable)
    assert(!result.wakeupLiveEnable)
  }

  test("reports disabled flush and empty-payload blockers without live enables") {
    val disabled = LoadReplayReturnSideEffectLiveControlReference(
      enable = false,
      flush = false,
      liveRequested = true,
      payloadValid = true,
      writebackRequired = true,
      wakeupRequired = true)
    val flushed = LoadReplayReturnSideEffectLiveControlReference(
      enable = true,
      flush = true,
      liveRequested = true,
      payloadValid = true,
      writebackRequired = true,
      wakeupRequired = true)
    val empty = LoadReplayReturnSideEffectLiveControlReference(
      enable = true,
      flush = false,
      liveRequested = true,
      payloadValid = false,
      writebackRequired = true,
      wakeupRequired = true)

    assert(disabled.blockedByDisabled)
    assert(!disabled.candidateValid)
    assert(disabled.liveEnableMask == 0x0)
    assert(flushed.blockedByFlush)
    assert(!flushed.candidateValid)
    assert(flushed.liveEnableMask == 0x0)
    assert(empty.blockedByNoPayload)
    assert(!empty.anyRequired)
    assert(empty.liveEnableMask == 0x0)
  }

  test("Chisel LoadReplayReturnSideEffectLiveControl elaborates live masks") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnSideEffectLiveControl)

    assert(sv.contains("module LoadReplayReturnSideEffectLiveControl"))
    assert(sv.contains("io_liveRequested"))
    assert(sv.contains("io_requiredMask"))
    assert(sv.contains("io_liveEnableMask"))
    assert(sv.contains("io_publishLiveEnable"))
  }
}
