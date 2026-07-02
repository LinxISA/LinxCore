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
      blockedByBudgetDisabled: Boolean,
      blockedByConsumer: Boolean)

  def apply(
      enable: Boolean,
      launchValid: Boolean,
      sourcesReturned: Boolean,
      pipeBudgetEnable: Boolean,
      consumerReady: Boolean): Result = {
    val candidateValid = enable && launchValid

    Result(
      candidateValid = candidateValid,
      pipeBudgetAvailable = enable && pipeBudgetEnable && consumerReady,
      blockedByDisabled = !enable && launchValid,
      blockedByNoCandidate = enable && !launchValid,
      blockedBySources = candidateValid && !sourcesReturned,
      blockedByBudgetDisabled = candidateValid && sourcesReturned && !pipeBudgetEnable,
      blockedByConsumer = candidateValid && sourcesReturned && pipeBudgetEnable && !consumerReady)
  }
}

class LoadReplayReturnPipeBudgetSpec extends AnyFunSuite {
  import LoadReplayReturnPipeBudgetReference._

  test("reports pipe budget availability when the wrapper and budget knob are enabled") {
    val result = LoadReplayReturnPipeBudgetReference(
      enable = true,
      launchValid = true,
      sourcesReturned = true,
      pipeBudgetEnable = true,
      consumerReady = true)

    assert(result.candidateValid)
    assert(result.pipeBudgetAvailable)
    assert(!result.blockedByDisabled)
    assert(!result.blockedByNoCandidate)
    assert(!result.blockedBySources)
    assert(!result.blockedByBudgetDisabled)
    assert(!result.blockedByConsumer)
  }

  test("does not arm the pipe budget while the replay-LIQ wrapper is disabled") {
    val result = LoadReplayReturnPipeBudgetReference(
      enable = false,
      launchValid = true,
      sourcesReturned = true,
      pipeBudgetEnable = true,
      consumerReady = true)

    assert(!result.candidateValid)
    assert(!result.pipeBudgetAvailable)
    assert(result.blockedByDisabled)
    assert(!result.blockedByBudgetDisabled)
    assert(!result.blockedByConsumer)
  }

  test("reports source-return blocking before budget-disable blocking") {
    val result = LoadReplayReturnPipeBudgetReference(
      enable = true,
      launchValid = true,
      sourcesReturned = false,
      pipeBudgetEnable = false,
      consumerReady = false)

    assert(result.candidateValid)
    assert(!result.pipeBudgetAvailable)
    assert(result.blockedBySources)
    assert(!result.blockedByBudgetDisabled)
    assert(!result.blockedByConsumer)
  }

  test("reports budget-disable blocking only after sources returned") {
    val result = LoadReplayReturnPipeBudgetReference(
      enable = true,
      launchValid = true,
      sourcesReturned = true,
      pipeBudgetEnable = false,
      consumerReady = true)

    assert(result.candidateValid)
    assert(!result.pipeBudgetAvailable)
    assert(!result.blockedBySources)
    assert(result.blockedByBudgetDisabled)
    assert(!result.blockedByConsumer)
  }

  test("reports consumer blocking only after budget is armed and sources returned") {
    val result = LoadReplayReturnPipeBudgetReference(
      enable = true,
      launchValid = true,
      sourcesReturned = true,
      pipeBudgetEnable = true,
      consumerReady = false)

    assert(result.candidateValid)
    assert(!result.pipeBudgetAvailable)
    assert(!result.blockedBySources)
    assert(!result.blockedByBudgetDisabled)
    assert(result.blockedByConsumer)
  }

  test("reports empty-candidate blocking without consuming the budget state") {
    val result = LoadReplayReturnPipeBudgetReference(
      enable = true,
      launchValid = false,
      sourcesReturned = true,
      pipeBudgetEnable = true,
      consumerReady = true)

    assert(!result.candidateValid)
    assert(result.pipeBudgetAvailable)
    assert(result.blockedByNoCandidate)
    assert(!result.blockedBySources)
    assert(!result.blockedByBudgetDisabled)
    assert(!result.blockedByConsumer)
  }

  test("Chisel LoadReplayReturnPipeBudget elaborates budget diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPipeBudget)

    assert(sv.contains("module LoadReplayReturnPipeBudget"))
    assert(sv.contains("io_pipeBudgetEnable"))
    assert(sv.contains("io_consumerReady"))
    assert(sv.contains("io_pipeBudgetAvailable"))
    assert(sv.contains("io_blockedBySources"))
    assert(sv.contains("io_blockedByBudgetDisabled"))
    assert(sv.contains("io_blockedByConsumer"))
  }
}
