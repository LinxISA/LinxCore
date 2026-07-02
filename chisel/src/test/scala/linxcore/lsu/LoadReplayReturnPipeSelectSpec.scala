package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeSelectReference {
  final case class Result(
      candidateValid: Boolean,
      pipeAvailable: Boolean,
      selectedPipeIndex: Int,
      blockedByDisabled: Boolean,
      blockedByNoCandidate: Boolean,
      blockedBySources: Boolean,
      blockedByNoPipe: Boolean)

  def apply(
      enable: Boolean,
      launchValid: Boolean,
      sourcesReturned: Boolean,
      pipeAvailableMask: Int,
      returnPipeCount: Int): Result = {
    require(returnPipeCount > 0)

    val candidateValid = enable && launchValid
    val maskedPipeAvailability = pipeAvailableMask & ((1 << returnPipeCount) - 1)
    val anyPipeAvailable = maskedPipeAvailability != 0
    val pipeAvailable = candidateValid && sourcesReturned && anyPipeAvailable
    val selectedPipeIndex =
      if (pipeAvailable)
        (0 until returnPipeCount).find(index => (maskedPipeAvailability & (1 << index)) != 0).getOrElse(0)
      else 0

    Result(
      candidateValid = candidateValid,
      pipeAvailable = pipeAvailable,
      selectedPipeIndex = selectedPipeIndex,
      blockedByDisabled = !enable && launchValid,
      blockedByNoCandidate = enable && !launchValid,
      blockedBySources = candidateValid && !sourcesReturned,
      blockedByNoPipe = candidateValid && sourcesReturned && !anyPipeAvailable)
  }
}

class LoadReplayReturnPipeSelectSpec extends AnyFunSuite {
  import LoadReplayReturnPipeSelectReference._

  test("selects the lowest available return pipe after sources have returned") {
    val result = LoadReplayReturnPipeSelectReference(
      enable = true,
      launchValid = true,
      sourcesReturned = true,
      pipeAvailableMask = 0x0a,
      returnPipeCount = 4)

    assert(result.candidateValid)
    assert(result.pipeAvailable)
    assert(result.selectedPipeIndex == 1)
    assert(!result.blockedBySources)
    assert(!result.blockedByNoPipe)
  }

  test("source-return blocking takes priority before pipe blocking") {
    val result = LoadReplayReturnPipeSelectReference(
      enable = true,
      launchValid = true,
      sourcesReturned = false,
      pipeAvailableMask = 0x0,
      returnPipeCount = 2)

    assert(result.candidateValid)
    assert(!result.pipeAvailable)
    assert(result.blockedBySources)
    assert(!result.blockedByNoPipe)
  }

  test("reports no-pipe blocking after sources return") {
    val result = LoadReplayReturnPipeSelectReference(
      enable = true,
      launchValid = true,
      sourcesReturned = true,
      pipeAvailableMask = 0x0,
      returnPipeCount = 2)

    assert(result.candidateValid)
    assert(!result.pipeAvailable)
    assert(!result.blockedBySources)
    assert(result.blockedByNoPipe)
  }

  test("reports disabled and empty-candidate blockers") {
    val disabled = LoadReplayReturnPipeSelectReference(
      enable = false,
      launchValid = true,
      sourcesReturned = true,
      pipeAvailableMask = 0x1,
      returnPipeCount = 1)
    val empty = LoadReplayReturnPipeSelectReference(
      enable = true,
      launchValid = false,
      sourcesReturned = true,
      pipeAvailableMask = 0x1,
      returnPipeCount = 1)

    assert(disabled.blockedByDisabled)
    assert(empty.blockedByNoCandidate)
    assert(!disabled.pipeAvailable)
    assert(!empty.pipeAvailable)
  }

  test("Chisel LoadReplayReturnPipeSelect elaborates pipe-selection diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPipeSelect(returnPipeCount = 4))

    assert(sv.contains("module LoadReplayReturnPipeSelect"))
    assert(sv.contains("io_sourcesReturned"))
    assert(sv.contains("io_pipeAvailableMask"))
    assert(sv.contains("io_pipeAvailable"))
    assert(sv.contains("io_selectedPipeIndex"))
    assert(sv.contains("io_blockedBySources"))
    assert(sv.contains("io_blockedByNoPipe"))
  }
}
