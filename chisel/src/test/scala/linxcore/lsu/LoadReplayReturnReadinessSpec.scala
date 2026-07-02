package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnReadinessReference {
  final case class Result(
      candidateValid: Boolean,
      returnReady: Boolean,
      selectedPipeIndex: Int,
      blockedByDisabled: Boolean,
      blockedByNoCandidate: Boolean,
      blockedBySources: Boolean,
      blockedByReturnPipe: Boolean)

  def apply(
      enable: Boolean,
      launchValid: Boolean,
      sourcesReturned: Boolean,
      returnPipeAvailable: Boolean,
      returnPipeIndex: Int): Result = {
    val candidateValid = enable && launchValid
    val returnReady = candidateValid && sourcesReturned && returnPipeAvailable

    Result(
      candidateValid = candidateValid,
      returnReady = returnReady,
      selectedPipeIndex = if (returnReady) returnPipeIndex else 0,
      blockedByDisabled = !enable && launchValid,
      blockedByNoCandidate = enable && !launchValid,
      blockedBySources = candidateValid && !sourcesReturned,
      blockedByReturnPipe = candidateValid && sourcesReturned && !returnPipeAvailable)
  }
}

class LoadReplayReturnReadinessSpec extends AnyFunSuite {
  import LoadReplayReturnReadinessReference._

  test("returns ready only after sources have returned and a pipe is available") {
    val result = LoadReplayReturnReadinessReference(
      enable = true,
      launchValid = true,
      sourcesReturned = true,
      returnPipeAvailable = true,
      returnPipeIndex = 1)

    assert(result.candidateValid)
    assert(result.returnReady)
    assert(result.selectedPipeIndex == 1)
    assert(!result.blockedBySources)
    assert(!result.blockedByReturnPipe)
  }

  test("source return blocks before return-pipe availability is reported") {
    val result = LoadReplayReturnReadinessReference(
      enable = true,
      launchValid = true,
      sourcesReturned = false,
      returnPipeAvailable = false,
      returnPipeIndex = 0)

    assert(result.candidateValid)
    assert(!result.returnReady)
    assert(result.blockedBySources)
    assert(!result.blockedByReturnPipe)
  }

  test("return pipe blocks after sources have returned") {
    val result = LoadReplayReturnReadinessReference(
      enable = true,
      launchValid = true,
      sourcesReturned = true,
      returnPipeAvailable = false,
      returnPipeIndex = 0)

    assert(result.candidateValid)
    assert(!result.returnReady)
    assert(!result.blockedBySources)
    assert(result.blockedByReturnPipe)
  }

  test("reports disabled and empty-candidate blockers") {
    val disabled = LoadReplayReturnReadinessReference(
      enable = false,
      launchValid = true,
      sourcesReturned = true,
      returnPipeAvailable = true,
      returnPipeIndex = 0)
    val empty = LoadReplayReturnReadinessReference(
      enable = true,
      launchValid = false,
      sourcesReturned = true,
      returnPipeAvailable = true,
      returnPipeIndex = 0)

    assert(disabled.blockedByDisabled)
    assert(empty.blockedByNoCandidate)
    assert(!disabled.returnReady)
    assert(!empty.returnReady)
  }

  test("Chisel LoadReplayReturnReadiness elaborates return-pipe diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnReadiness(returnPipeCount = 2))

    assert(sv.contains("module LoadReplayReturnReadiness"))
    assert(sv.contains("io_sourcesReturned"))
    assert(sv.contains("io_returnPipeAvailable"))
    assert(sv.contains("io_returnPipeIndex"))
    assert(sv.contains("io_selectedPipeIndex"))
    assert(sv.contains("io_blockedBySources"))
    assert(sv.contains("io_blockedByReturnPipe"))
  }
}
