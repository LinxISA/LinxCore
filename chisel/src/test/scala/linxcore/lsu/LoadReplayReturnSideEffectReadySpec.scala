package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnSideEffectReadyReference {
  final case class Result(
      candidateValid: Boolean,
      lretReady: Boolean,
      writebackReady: Boolean,
      wakeupReady: Boolean,
      sideEffectsReady: Boolean,
      blockedByDisabled: Boolean,
      blockedByNoPayload: Boolean,
      blockedByLret: Boolean,
      blockedByWriteback: Boolean,
      blockedByWakeup: Boolean)

  def apply(
      enable: Boolean,
      payloadValid: Boolean,
      lretSinkReady: Boolean,
      writebackRequired: Boolean,
      writebackSinkReady: Boolean,
      wakeupRequired: Boolean,
      wakeupSinkReady: Boolean): Result = {
    val candidateValid = enable && payloadValid
    val writebackReady = !writebackRequired || writebackSinkReady
    val wakeupReady = !wakeupRequired || wakeupSinkReady

    Result(
      candidateValid = candidateValid,
      lretReady = candidateValid && lretSinkReady,
      writebackReady = candidateValid && writebackReady,
      wakeupReady = candidateValid && wakeupReady,
      sideEffectsReady = candidateValid && lretSinkReady && writebackReady && wakeupReady,
      blockedByDisabled = !enable && payloadValid,
      blockedByNoPayload = enable && !payloadValid,
      blockedByLret = candidateValid && !lretSinkReady,
      blockedByWriteback = candidateValid && writebackRequired && !writebackSinkReady,
      blockedByWakeup = candidateValid && wakeupRequired && !wakeupSinkReady)
  }
}

class LoadReplayReturnSideEffectReadySpec extends AnyFunSuite {
  import LoadReplayReturnSideEffectReadyReference._

  test("requires LRET, writeback, and wakeup sinks when all side effects are required") {
    val result = LoadReplayReturnSideEffectReadyReference(
      enable = true,
      payloadValid = true,
      lretSinkReady = true,
      writebackRequired = true,
      writebackSinkReady = true,
      wakeupRequired = true,
      wakeupSinkReady = true)

    assert(result.candidateValid)
    assert(result.lretReady)
    assert(result.writebackReady)
    assert(result.wakeupReady)
    assert(result.sideEffectsReady)
  }

  test("requires only LRET when writeback and wakeup are not required") {
    val result = LoadReplayReturnSideEffectReadyReference(
      enable = true,
      payloadValid = true,
      lretSinkReady = true,
      writebackRequired = false,
      writebackSinkReady = false,
      wakeupRequired = false,
      wakeupSinkReady = false)

    assert(result.sideEffectsReady)
    assert(result.writebackReady)
    assert(result.wakeupReady)
    assert(!result.blockedByWriteback)
    assert(!result.blockedByWakeup)
  }

  test("reports each missing required sink independently") {
    val result = LoadReplayReturnSideEffectReadyReference(
      enable = true,
      payloadValid = true,
      lretSinkReady = false,
      writebackRequired = true,
      writebackSinkReady = false,
      wakeupRequired = true,
      wakeupSinkReady = false)

    assert(!result.sideEffectsReady)
    assert(result.blockedByLret)
    assert(result.blockedByWriteback)
    assert(result.blockedByWakeup)
  }

  test("does not report optional writeback or wakeup blockers") {
    val result = LoadReplayReturnSideEffectReadyReference(
      enable = true,
      payloadValid = true,
      lretSinkReady = false,
      writebackRequired = false,
      writebackSinkReady = false,
      wakeupRequired = false,
      wakeupSinkReady = false)

    assert(!result.sideEffectsReady)
    assert(result.blockedByLret)
    assert(!result.blockedByWriteback)
    assert(!result.blockedByWakeup)
  }

  test("reports disabled and empty payload diagnostics without claiming readiness") {
    val disabled = LoadReplayReturnSideEffectReadyReference(
      enable = false,
      payloadValid = true,
      lretSinkReady = true,
      writebackRequired = true,
      writebackSinkReady = true,
      wakeupRequired = true,
      wakeupSinkReady = true)
    val empty = LoadReplayReturnSideEffectReadyReference(
      enable = true,
      payloadValid = false,
      lretSinkReady = true,
      writebackRequired = false,
      writebackSinkReady = false,
      wakeupRequired = false,
      wakeupSinkReady = false)

    assert(!disabled.candidateValid)
    assert(!disabled.sideEffectsReady)
    assert(disabled.blockedByDisabled)
    assert(!empty.candidateValid)
    assert(!empty.sideEffectsReady)
    assert(empty.blockedByNoPayload)
  }

  test("Chisel LoadReplayReturnSideEffectReady elaborates side-effect diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnSideEffectReady)

    assert(sv.contains("module LoadReplayReturnSideEffectReady"))
    assert(sv.contains("io_payloadValid"))
    assert(sv.contains("io_lretSinkReady"))
    assert(sv.contains("io_writebackRequired"))
    assert(sv.contains("io_writebackSinkReady"))
    assert(sv.contains("io_wakeupRequired"))
    assert(sv.contains("io_wakeupSinkReady"))
    assert(sv.contains("io_sideEffectsReady"))
    assert(sv.contains("io_blockedByWriteback"))
    assert(sv.contains("io_blockedByWakeup"))
  }
}
