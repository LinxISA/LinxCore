package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPublishControlReference {
  final case class Result(
      candidateValid: Boolean,
      publishArmed: Boolean,
      publishFire: Boolean,
      blockedByDisabled: Boolean,
      blockedByNoPayload: Boolean,
      blockedByPublish: Boolean,
      blockedBySideEffects: Boolean,
      blockedByLiveDisabled: Boolean)

  def apply(
      enable: Boolean,
      liveEnable: Boolean,
      payloadValid: Boolean,
      publishReady: Boolean,
      sideEffectsReady: Boolean): Result = {
    val candidateValid = enable && payloadValid
    val publishArmed = candidateValid && publishReady && sideEffectsReady

    Result(
      candidateValid = candidateValid,
      publishArmed = publishArmed,
      publishFire = publishArmed && liveEnable,
      blockedByDisabled = !enable && payloadValid,
      blockedByNoPayload = enable && !payloadValid,
      blockedByPublish = candidateValid && !publishReady,
      blockedBySideEffects = candidateValid && publishReady && !sideEffectsReady,
      blockedByLiveDisabled = publishArmed && !liveEnable)
  }
}

class LoadReplayReturnPublishControlSpec extends AnyFunSuite {
  import LoadReplayReturnPublishControlReference._

  test("fires only when payload, publish, side effects, and live enable are all ready") {
    val result = LoadReplayReturnPublishControlReference(
      enable = true,
      liveEnable = true,
      payloadValid = true,
      publishReady = true,
      sideEffectsReady = true)

    assert(result.candidateValid)
    assert(result.publishArmed)
    assert(result.publishFire)
    assert(!result.blockedByLiveDisabled)
  }

  test("arms but does not fire while live publication is disabled") {
    val result = LoadReplayReturnPublishControlReference(
      enable = true,
      liveEnable = false,
      payloadValid = true,
      publishReady = true,
      sideEffectsReady = true)

    assert(result.publishArmed)
    assert(!result.publishFire)
    assert(result.blockedByLiveDisabled)
  }

  test("reports publish readiness blocking before side-effect blocking") {
    val publishBlocked = LoadReplayReturnPublishControlReference(
      enable = true,
      liveEnable = true,
      payloadValid = true,
      publishReady = false,
      sideEffectsReady = false)
    val sideEffectBlocked = LoadReplayReturnPublishControlReference(
      enable = true,
      liveEnable = true,
      payloadValid = true,
      publishReady = true,
      sideEffectsReady = false)

    assert(!publishBlocked.publishArmed)
    assert(publishBlocked.blockedByPublish)
    assert(!publishBlocked.blockedBySideEffects)
    assert(!sideEffectBlocked.publishArmed)
    assert(!sideEffectBlocked.blockedByPublish)
    assert(sideEffectBlocked.blockedBySideEffects)
  }

  test("reports disabled and empty payload diagnostics without firing") {
    val disabled = LoadReplayReturnPublishControlReference(
      enable = false,
      liveEnable = true,
      payloadValid = true,
      publishReady = true,
      sideEffectsReady = true)
    val empty = LoadReplayReturnPublishControlReference(
      enable = true,
      liveEnable = true,
      payloadValid = false,
      publishReady = true,
      sideEffectsReady = true)

    assert(!disabled.candidateValid)
    assert(!disabled.publishFire)
    assert(disabled.blockedByDisabled)
    assert(!empty.candidateValid)
    assert(!empty.publishFire)
    assert(empty.blockedByNoPayload)
  }

  test("does not report live-disabled blocking unless publish is armed") {
    val result = LoadReplayReturnPublishControlReference(
      enable = true,
      liveEnable = false,
      payloadValid = true,
      publishReady = false,
      sideEffectsReady = true)

    assert(!result.publishArmed)
    assert(!result.blockedByLiveDisabled)
    assert(result.blockedByPublish)
  }

  test("Chisel LoadReplayReturnPublishControl elaborates publish diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPublishControl)

    assert(sv.contains("module LoadReplayReturnPublishControl"))
    assert(sv.contains("io_liveEnable"))
    assert(sv.contains("io_payloadValid"))
    assert(sv.contains("io_publishReady"))
    assert(sv.contains("io_sideEffectsReady"))
    assert(sv.contains("io_publishArmed"))
    assert(sv.contains("io_publishFire"))
    assert(sv.contains("io_blockedByLiveDisabled"))
  }
}
