package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnTimingStatsCandidateReference {
  final case class Result(
      candidateValid: Boolean,
      timingSidebandValid: Boolean,
      iqNameSidebandValid: Boolean,
      ldRntCycleValid: Boolean,
      statsUpdateValid: Boolean,
      lsuRecvCycle: BigInt,
      ldqPickCycle: BigInt,
      ldqIssueCycle: BigInt,
      l1MissCycle: BigInt,
      l2MissCycle: BigInt,
      memRntCycle: BigInt,
      l2RntCycle: BigInt,
      l1RntCycle: BigInt,
      ldRntCycle: BigInt,
      statsLatencyIncrement: BigInt,
      latencyUnderflow: Boolean,
      readyForPipeInsert: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByNoFinalMetadata: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      finalMetadataValid: Boolean,
      currentCycle: BigInt,
      memCycles: Seq[BigInt],
      cycleWidth: Int = 64): Result = {
    require(memCycles.length == 8)
    val candidateValid = enable && !flush && finalMetadataValid
    val modulus = BigInt(1) << cycleWidth
    val latency = (currentCycle - memCycles.head + modulus) % modulus
    Result(
      candidateValid = candidateValid,
      timingSidebandValid = candidateValid,
      iqNameSidebandValid = candidateValid,
      ldRntCycleValid = candidateValid,
      statsUpdateValid = candidateValid,
      lsuRecvCycle = if (candidateValid) memCycles(0) else 0,
      ldqPickCycle = if (candidateValid) memCycles(1) else 0,
      ldqIssueCycle = if (candidateValid) memCycles(2) else 0,
      l1MissCycle = if (candidateValid) memCycles(3) else 0,
      l2MissCycle = if (candidateValid) memCycles(4) else 0,
      memRntCycle = if (candidateValid) memCycles(5) else 0,
      l2RntCycle = if (candidateValid) memCycles(6) else 0,
      l1RntCycle = if (candidateValid) memCycles(7) else 0,
      ldRntCycle = if (candidateValid) currentCycle else 0,
      statsLatencyIncrement = if (candidateValid) latency else 0,
      latencyUnderflow = candidateValid && currentCycle < memCycles.head,
      readyForPipeInsert = candidateValid,
      blockedByDisabled = !enable && finalMetadataValid,
      blockedByFlush = enable && flush && finalMetadataValid,
      blockedByNoFinalMetadata = enable && !flush && !finalMetadataValid)
  }
}

class LoadReplayReturnTimingStatsCandidateSpec extends AnyFunSuite {
  import LoadReplayReturnTimingStatsCandidateReference._

  test("copies returned-load timing sidebands and computes stats latency intent") {
    val result = LoadReplayReturnTimingStatsCandidateReference(
      enable = true,
      flush = false,
      finalMetadataValid = true,
      currentCycle = 80,
      memCycles = Seq(50, 51, 52, 60, 61, 70, 72, 74))

    assert(result.candidateValid)
    assert(result.timingSidebandValid)
    assert(result.iqNameSidebandValid)
    assert(result.ldRntCycleValid)
    assert(result.statsUpdateValid)
    assert(result.lsuRecvCycle == 50)
    assert(result.ldqPickCycle == 51)
    assert(result.ldqIssueCycle == 52)
    assert(result.l1MissCycle == 60)
    assert(result.l2MissCycle == 61)
    assert(result.memRntCycle == 70)
    assert(result.l2RntCycle == 72)
    assert(result.l1RntCycle == 74)
    assert(result.ldRntCycle == 80)
    assert(result.statsLatencyIncrement == 30)
    assert(!result.latencyUnderflow)
    assert(result.readyForPipeInsert)
  }

  test("reports latency underflow without suppressing candidate diagnostics") {
    val result = LoadReplayReturnTimingStatsCandidateReference(
      enable = true,
      flush = false,
      finalMetadataValid = true,
      currentCycle = 10,
      memCycles = Seq(12, 13, 14, 15, 16, 17, 18, 19),
      cycleWidth = 8)

    assert(result.candidateValid)
    assert(result.latencyUnderflow)
    assert(result.statsLatencyIncrement == 254)
  }

  test("reports disabled flush and missing final-metadata blockers") {
    val disabled = LoadReplayReturnTimingStatsCandidateReference(
      enable = false,
      flush = false,
      finalMetadataValid = true,
      currentCycle = 0,
      memCycles = Seq.fill(8)(0))
    assert(disabled.blockedByDisabled)
    assert(!disabled.candidateValid)
    assert(disabled.statsLatencyIncrement == 0)

    val flushed = LoadReplayReturnTimingStatsCandidateReference(
      enable = true,
      flush = true,
      finalMetadataValid = true,
      currentCycle = 0,
      memCycles = Seq.fill(8)(0))
    assert(flushed.blockedByFlush)
    assert(!flushed.candidateValid)

    val missingFinalMetadata = LoadReplayReturnTimingStatsCandidateReference(
      enable = true,
      flush = false,
      finalMetadataValid = false,
      currentCycle = 0,
      memCycles = Seq.fill(8)(0))
    assert(missingFinalMetadata.blockedByNoFinalMetadata)
    assert(!missingFinalMetadata.candidateValid)
  }

  test("Chisel LoadReplayReturnTimingStatsCandidate elaborates timing/stat diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnTimingStatsCandidate(cycleWidth = 8))

    assert(sv.contains("module LoadReplayReturnTimingStatsCandidate"))
    assert(sv.contains("io_statsUpdateValid"))
    assert(sv.contains("io_statsLatencyIncrement"))
    assert(sv.contains("io_latencyUnderflow"))
  }
}
