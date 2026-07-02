package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeBudgetReference {
  final case class Result(
      candidateValid: Boolean,
      pipeBudgetAvailable: Boolean,
      blockedByDisabled: Boolean,
      blockedByNoCandidate: Boolean,
      blockedBySources: Boolean,
      blockedByBudgetDisabled: Boolean)

  def apply(
      enable: Boolean,
      launchValid: Boolean,
      sourcesReturned: Boolean,
      pipeBudgetEnable: Boolean): Result = {
    val candidateValid = enable && launchValid

    Result(
      candidateValid = candidateValid,
      pipeBudgetAvailable = enable && pipeBudgetEnable,
      blockedByDisabled = !enable && launchValid,
      blockedByNoCandidate = enable && !launchValid,
      blockedBySources = candidateValid && !sourcesReturned,
      blockedByBudgetDisabled = candidateValid && sourcesReturned && !pipeBudgetEnable)
  }
}

class LoadReplayReturnPipeBudgetSpec extends AnyFunSuite {
  import LoadReplayReturnPipeBudgetReference._

  test("reports pipe budget availability when the wrapper and budget knob are enabled") {
    val result = LoadReplayReturnPipeBudgetReference(
      enable = true,
      launchValid = true,
      sourcesReturned = true,
      pipeBudgetEnable = true)

    assert(result.candidateValid)
    assert(result.pipeBudgetAvailable)
    assert(!result.blockedByDisabled)
    assert(!result.blockedByNoCandidate)
    assert(!result.blockedBySources)
    assert(!result.blockedByBudgetDisabled)
  }

  test("does not arm the pipe budget while the replay-LIQ wrapper is disabled") {
    val result = LoadReplayReturnPipeBudgetReference(
      enable = false,
      launchValid = true,
      sourcesReturned = true,
      pipeBudgetEnable = true)

    assert(!result.candidateValid)
    assert(!result.pipeBudgetAvailable)
    assert(result.blockedByDisabled)
    assert(!result.blockedByBudgetDisabled)
  }

  test("reports source-return blocking before budget-disable blocking") {
    val result = LoadReplayReturnPipeBudgetReference(
      enable = true,
      launchValid = true,
      sourcesReturned = false,
      pipeBudgetEnable = false)

    assert(result.candidateValid)
    assert(!result.pipeBudgetAvailable)
    assert(result.blockedBySources)
    assert(!result.blockedByBudgetDisabled)
  }

  test("reports budget-disable blocking only after sources returned") {
    val result = LoadReplayReturnPipeBudgetReference(
      enable = true,
      launchValid = true,
      sourcesReturned = true,
      pipeBudgetEnable = false)

    assert(result.candidateValid)
    assert(!result.pipeBudgetAvailable)
    assert(!result.blockedBySources)
    assert(result.blockedByBudgetDisabled)
  }

  test("reports empty-candidate blocking without consuming the budget state") {
    val result = LoadReplayReturnPipeBudgetReference(
      enable = true,
      launchValid = false,
      sourcesReturned = true,
      pipeBudgetEnable = true)

    assert(!result.candidateValid)
    assert(result.pipeBudgetAvailable)
    assert(result.blockedByNoCandidate)
    assert(!result.blockedBySources)
    assert(!result.blockedByBudgetDisabled)
  }

  test("Chisel LoadReplayReturnPipeBudget elaborates budget diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPipeBudget)

    assert(sv.contains("module LoadReplayReturnPipeBudget"))
    assert(sv.contains("io_pipeBudgetEnable"))
    assert(sv.contains("io_pipeBudgetAvailable"))
    assert(sv.contains("io_blockedBySources"))
    assert(sv.contains("io_blockedByBudgetDisabled"))
  }
}
