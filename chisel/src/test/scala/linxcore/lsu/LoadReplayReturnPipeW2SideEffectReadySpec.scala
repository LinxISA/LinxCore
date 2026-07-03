package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2SideEffectReadyReference {
  final case class Result(
      readyCandidateValid: Boolean,
      resolveReady: Boolean,
      writebackReady: Boolean,
      wakeupReady: Boolean,
      sideEffectsReady: Boolean,
      blockedByDisabled: Boolean,
      blockedByNoCandidate: Boolean,
      blockedByResolve: Boolean,
      blockedByWriteback: Boolean,
      blockedByWakeup: Boolean)

  def apply(
      enable: Boolean,
      candidateValid: Boolean,
      resolveRequired: Boolean,
      resolveSinkReady: Boolean,
      writebackRequired: Boolean,
      writebackSinkReady: Boolean,
      wakeupRequired: Boolean,
      wakeupSinkReady: Boolean): Result = {
    val readyCandidateValid = enable && candidateValid
    val resolveReady = !resolveRequired || resolveSinkReady
    val writebackReady = !writebackRequired || writebackSinkReady
    val wakeupReady = !wakeupRequired || wakeupSinkReady

    Result(
      readyCandidateValid = readyCandidateValid,
      resolveReady = readyCandidateValid && resolveReady,
      writebackReady = readyCandidateValid && writebackReady,
      wakeupReady = readyCandidateValid && wakeupReady,
      sideEffectsReady = readyCandidateValid && resolveReady && writebackReady && wakeupReady,
      blockedByDisabled = !enable && candidateValid,
      blockedByNoCandidate = enable && !candidateValid,
      blockedByResolve = readyCandidateValid && resolveRequired && !resolveSinkReady,
      blockedByWriteback = readyCandidateValid && writebackRequired && !writebackSinkReady,
      blockedByWakeup = readyCandidateValid && wakeupRequired && !wakeupSinkReady)
  }
}

class LoadReplayReturnPipeW2SideEffectReadySpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2SideEffectReadyReference._

  test("requires resolve writeback and wakeup sinks when all W2 side effects are required") {
    val result = LoadReplayReturnPipeW2SideEffectReadyReference(
      enable = true,
      candidateValid = true,
      resolveRequired = true,
      resolveSinkReady = true,
      writebackRequired = true,
      writebackSinkReady = true,
      wakeupRequired = true,
      wakeupSinkReady = true)

    assert(result.readyCandidateValid)
    assert(result.resolveReady)
    assert(result.writebackReady)
    assert(result.wakeupReady)
    assert(result.sideEffectsReady)
  }

  test("allows optional writeback and wakeup when only resolve is required") {
    val result = LoadReplayReturnPipeW2SideEffectReadyReference(
      enable = true,
      candidateValid = true,
      resolveRequired = true,
      resolveSinkReady = true,
      writebackRequired = false,
      writebackSinkReady = false,
      wakeupRequired = false,
      wakeupSinkReady = false)

    assert(result.sideEffectsReady)
    assert(result.resolveReady)
    assert(result.writebackReady)
    assert(result.wakeupReady)
    assert(!result.blockedByWriteback)
    assert(!result.blockedByWakeup)
  }

  test("reports each missing required W2 side-effect sink independently") {
    val result = LoadReplayReturnPipeW2SideEffectReadyReference(
      enable = true,
      candidateValid = true,
      resolveRequired = true,
      resolveSinkReady = false,
      writebackRequired = true,
      writebackSinkReady = false,
      wakeupRequired = true,
      wakeupSinkReady = false)

    assert(!result.sideEffectsReady)
    assert(result.blockedByResolve)
    assert(result.blockedByWriteback)
    assert(result.blockedByWakeup)
  }

  test("does not report optional writeback or wakeup blockers") {
    val result = LoadReplayReturnPipeW2SideEffectReadyReference(
      enable = true,
      candidateValid = true,
      resolveRequired = true,
      resolveSinkReady = false,
      writebackRequired = false,
      writebackSinkReady = false,
      wakeupRequired = false,
      wakeupSinkReady = false)

    assert(!result.sideEffectsReady)
    assert(result.blockedByResolve)
    assert(!result.blockedByWriteback)
    assert(!result.blockedByWakeup)
  }

  test("reports disabled and missing-candidate diagnostics without claiming readiness") {
    val disabled = LoadReplayReturnPipeW2SideEffectReadyReference(
      enable = false,
      candidateValid = true,
      resolveRequired = true,
      resolveSinkReady = true,
      writebackRequired = true,
      writebackSinkReady = true,
      wakeupRequired = true,
      wakeupSinkReady = true)
    val empty = LoadReplayReturnPipeW2SideEffectReadyReference(
      enable = true,
      candidateValid = false,
      resolveRequired = true,
      resolveSinkReady = true,
      writebackRequired = false,
      writebackSinkReady = false,
      wakeupRequired = false,
      wakeupSinkReady = false)

    assert(!disabled.readyCandidateValid)
    assert(!disabled.sideEffectsReady)
    assert(disabled.blockedByDisabled)
    assert(!empty.readyCandidateValid)
    assert(!empty.sideEffectsReady)
    assert(empty.blockedByNoCandidate)
  }

  test("Chisel LoadReplayReturnPipeW2SideEffectReady elaborates W2 side-effect diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPipeW2SideEffectReady)

    assert(sv.contains("module LoadReplayReturnPipeW2SideEffectReady"))
    assert(sv.contains("io_candidateValid"))
    assert(sv.contains("io_resolveRequired"))
    assert(sv.contains("io_resolveSinkReady"))
    assert(sv.contains("io_writebackRequired"))
    assert(sv.contains("io_writebackSinkReady"))
    assert(sv.contains("io_wakeupRequired"))
    assert(sv.contains("io_wakeupSinkReady"))
    assert(sv.contains("io_sideEffectsReady"))
    assert(sv.contains("io_blockedByResolve"))
  }
}
