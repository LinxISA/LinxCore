package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnWakeupCandidateReference {
  final case class Dst(valid: Boolean, kind: Int, physTag: Int)

  final case class Result(
      candidateValid: Boolean,
      wakeupRequired: Boolean,
      wakeupValid: Boolean,
      wakeupKind: Int,
      wakeupTag: Int,
      reducedGprWakeupValid: Boolean,
      nonGprWakeup: Boolean,
      suppressedWakeupNotRequired: Boolean,
      ignoredNoDestination: Boolean,
      blockedByDisabled: Boolean)

  private val NoneKind = 0
  private val GprKind = 1

  def apply(
      enable: Boolean,
      payloadValid: Boolean,
      payloadWakeupRequired: Boolean,
      dst: Dst): Result = {
    val candidateValid = enable && payloadValid
    val wakeupRequired = candidateValid && payloadWakeupRequired
    val hasDestination = dst.valid && dst.kind != NoneKind
    val isGprDestination = hasDestination && dst.kind == GprKind
    val wakeupValid = wakeupRequired && hasDestination

    Result(
      candidateValid = candidateValid,
      wakeupRequired = wakeupRequired,
      wakeupValid = wakeupValid,
      wakeupKind = if (wakeupValid) dst.kind else NoneKind,
      wakeupTag = if (wakeupValid) dst.physTag else 0,
      reducedGprWakeupValid = wakeupValid && isGprDestination,
      nonGprWakeup = wakeupValid && !isGprDestination,
      suppressedWakeupNotRequired = candidateValid && !payloadWakeupRequired,
      ignoredNoDestination = wakeupRequired && !hasDestination,
      blockedByDisabled = !enable && payloadValid)
  }
}

class LoadReplayReturnWakeupCandidateSpec extends AnyFunSuite {
  import LoadReplayReturnWakeupCandidateReference._

  test("emits a reduced GPR wakeup candidate from a required payload") {
    val result = LoadReplayReturnWakeupCandidateReference(
      enable = true,
      payloadValid = true,
      payloadWakeupRequired = true,
      dst = Dst(valid = true, kind = 1, physTag = 42))

    assert(result.candidateValid)
    assert(result.wakeupRequired)
    assert(result.wakeupValid)
    assert(result.wakeupKind == 1)
    assert(result.wakeupTag == 42)
    assert(result.reducedGprWakeupValid)
    assert(!result.nonGprWakeup)
    assert(!result.suppressedWakeupNotRequired)
    assert(!result.ignoredNoDestination)
  }

  test("suppresses candidates when the payload does not require a regular wakeup") {
    val result = LoadReplayReturnWakeupCandidateReference(
      enable = true,
      payloadValid = true,
      payloadWakeupRequired = false,
      dst = Dst(valid = true, kind = 1, physTag = 42))

    assert(result.candidateValid)
    assert(!result.wakeupRequired)
    assert(!result.wakeupValid)
    assert(result.wakeupKind == 0)
    assert(result.wakeupTag == 0)
    assert(!result.reducedGprWakeupValid)
    assert(result.suppressedWakeupNotRequired)
  }

  test("reports missing and non-GPR wakeup destinations") {
    val missing = LoadReplayReturnWakeupCandidateReference(
      enable = true,
      payloadValid = true,
      payloadWakeupRequired = true,
      dst = Dst(valid = false, kind = 0, physTag = 42))
    val local = LoadReplayReturnWakeupCandidateReference(
      enable = true,
      payloadValid = true,
      payloadWakeupRequired = true,
      dst = Dst(valid = true, kind = 2, physTag = 7))

    assert(missing.wakeupRequired)
    assert(!missing.wakeupValid)
    assert(missing.ignoredNoDestination)
    assert(local.wakeupValid)
    assert(!local.reducedGprWakeupValid)
    assert(local.nonGprWakeup)
    assert(local.wakeupKind == 2)
    assert(local.wakeupTag == 7)
  }

  test("reports disabled payloads and suppresses stale wakeup fields") {
    val disabled = LoadReplayReturnWakeupCandidateReference(
      enable = false,
      payloadValid = true,
      payloadWakeupRequired = true,
      dst = Dst(valid = true, kind = 1, physTag = 42))
    val empty = LoadReplayReturnWakeupCandidateReference(
      enable = true,
      payloadValid = false,
      payloadWakeupRequired = true,
      dst = Dst(valid = true, kind = 1, physTag = 42))

    assert(!disabled.candidateValid)
    assert(!disabled.wakeupValid)
    assert(disabled.blockedByDisabled)
    assert(disabled.wakeupKind == 0)
    assert(disabled.wakeupTag == 0)
    assert(!empty.candidateValid)
    assert(!empty.wakeupValid)
    assert(!empty.blockedByDisabled)
  }

  test("Chisel LoadReplayReturnWakeupCandidate elaborates wakeup diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnWakeupCandidate)

    assert(sv.contains("module LoadReplayReturnWakeupCandidate"))
    assert(sv.contains("io_payloadWakeupRequired"))
    assert(sv.contains("io_wakeupValid"))
    assert(sv.contains("io_wakeupTag"))
    assert(sv.contains("io_reducedGprWakeupValid"))
    assert(sv.contains("io_suppressedWakeupNotRequired"))
  }
}
