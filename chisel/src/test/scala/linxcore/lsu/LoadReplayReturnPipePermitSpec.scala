package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipePermitReference {
  final case class Result(
      candidateValid: Boolean,
      pipeAvailableMask: Int,
      permitValid: Boolean,
      blockedByDisabled: Boolean,
      blockedByNoCandidate: Boolean,
      blockedBySources: Boolean,
      blockedByPipeBudget: Boolean)

  def apply(
      enable: Boolean,
      launchValid: Boolean,
      sourcesReturned: Boolean,
      pipeBudgetAvailable: Boolean,
      returnPipeCount: Int): Result = {
    require(returnPipeCount > 0)

    val candidateValid = enable && launchValid
    val permitValid = candidateValid && sourcesReturned && pipeBudgetAvailable
    val pipeAvailableMask = if (permitValid) 1 else 0

    Result(
      candidateValid = candidateValid,
      pipeAvailableMask = pipeAvailableMask,
      permitValid = permitValid,
      blockedByDisabled = !enable && launchValid,
      blockedByNoCandidate = enable && !launchValid,
      blockedBySources = candidateValid && !sourcesReturned,
      blockedByPipeBudget = candidateValid && sourcesReturned && !pipeBudgetAvailable)
  }
}

class LoadReplayReturnPipePermitSpec extends AnyFunSuite {
  import LoadReplayReturnPipePermitReference._

  test("permits pipe zero only after sources and pipe budget are available") {
    val result = LoadReplayReturnPipePermitReference(
      enable = true,
      launchValid = true,
      sourcesReturned = true,
      pipeBudgetAvailable = true,
      returnPipeCount = 2)

    assert(result.candidateValid)
    assert(result.permitValid)
    assert(result.pipeAvailableMask == 1)
    assert(!result.blockedBySources)
    assert(!result.blockedByPipeBudget)
  }

  test("source-return blocking precedes pipe-budget blocking") {
    val result = LoadReplayReturnPipePermitReference(
      enable = true,
      launchValid = true,
      sourcesReturned = false,
      pipeBudgetAvailable = false,
      returnPipeCount = 1)

    assert(result.candidateValid)
    assert(!result.permitValid)
    assert(result.pipeAvailableMask == 0)
    assert(result.blockedBySources)
    assert(!result.blockedByPipeBudget)
  }

  test("pipe budget blocks only after sources have returned") {
    val result = LoadReplayReturnPipePermitReference(
      enable = true,
      launchValid = true,
      sourcesReturned = true,
      pipeBudgetAvailable = false,
      returnPipeCount = 1)

    assert(result.candidateValid)
    assert(!result.permitValid)
    assert(result.pipeAvailableMask == 0)
    assert(!result.blockedBySources)
    assert(result.blockedByPipeBudget)
  }

  test("reports disabled and empty-candidate blockers") {
    val disabled = LoadReplayReturnPipePermitReference(
      enable = false,
      launchValid = true,
      sourcesReturned = true,
      pipeBudgetAvailable = true,
      returnPipeCount = 1)
    val empty = LoadReplayReturnPipePermitReference(
      enable = true,
      launchValid = false,
      sourcesReturned = true,
      pipeBudgetAvailable = true,
      returnPipeCount = 1)

    assert(disabled.blockedByDisabled)
    assert(empty.blockedByNoCandidate)
    assert(!disabled.permitValid)
    assert(!empty.permitValid)
  }

  test("Chisel LoadReplayReturnPipePermit elaborates permit diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPipePermit(returnPipeCount = 2))

    assert(sv.contains("module LoadReplayReturnPipePermit"))
    assert(sv.contains("io_sourcesReturned"))
    assert(sv.contains("io_pipeBudgetAvailable"))
    assert(sv.contains("io_pipeAvailableMask"))
    assert(sv.contains("io_permitValid"))
    assert(sv.contains("io_blockedBySources"))
    assert(sv.contains("io_blockedByPipeBudget"))
  }
}
