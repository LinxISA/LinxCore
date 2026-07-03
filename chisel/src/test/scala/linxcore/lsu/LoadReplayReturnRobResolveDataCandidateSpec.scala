package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnRobResolveDataCandidateReference {
  final case class Dst(valid: Boolean = true, physTag: Int = 42)

  final case class Result(
      candidateValid: Boolean,
      resolveValid: Boolean,
      readyForPipeInsert: Boolean,
      copiedRid: Int,
      copiedData: BigInt,
      markAllDestinationsDataValid: Boolean,
      markDestinationDataValid: Boolean,
      retLaneIncrement: Boolean,
      vectorLaneDataWrite: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByNoSetMemData: Boolean,
      blockedByUnsupportedMultiLane: Boolean,
      blockedByInvalidRid: Boolean,
      blockedByNoDestination: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      setMemDataValid: Boolean,
      reducedSingleLane: Boolean,
      ridValid: Boolean,
      rid: Int,
      dst: Dst,
      data: BigInt): Result = {
    val candidateValid = enable && !flush && setMemDataValid
    val resolveValid = candidateValid && reducedSingleLane && ridValid

    Result(
      candidateValid = candidateValid,
      resolveValid = resolveValid,
      readyForPipeInsert = resolveValid,
      copiedRid = if (resolveValid) rid else 0,
      copiedData = if (resolveValid) data else 0,
      markAllDestinationsDataValid = resolveValid,
      markDestinationDataValid = resolveValid && dst.valid,
      retLaneIncrement = resolveValid,
      vectorLaneDataWrite = false,
      blockedByDisabled = !enable && setMemDataValid,
      blockedByFlush = enable && flush && setMemDataValid,
      blockedByNoSetMemData = enable && !flush && !setMemDataValid,
      blockedByUnsupportedMultiLane = candidateValid && !reducedSingleLane,
      blockedByInvalidRid = candidateValid && reducedSingleLane && !ridValid,
      blockedByNoDestination = resolveValid && !dst.valid)
  }
}

class LoadReplayReturnRobResolveDataCandidateSpec extends AnyFunSuite {
  import LoadReplayReturnRobResolveDataCandidateReference._

  test("forms a reduced scalar ROB resolve-data request after setMemData admission") {
    val result = LoadReplayReturnRobResolveDataCandidateReference(
      enable = true,
      flush = false,
      setMemDataValid = true,
      reducedSingleLane = true,
      ridValid = true,
      rid = 7,
      dst = Dst(valid = true, physTag = 19),
      data = BigInt("8877665544332211", 16))

    assert(result.candidateValid)
    assert(result.resolveValid)
    assert(result.readyForPipeInsert)
    assert(result.copiedRid == 7)
    assert(result.copiedData == BigInt("8877665544332211", 16))
    assert(result.markAllDestinationsDataValid)
    assert(result.markDestinationDataValid)
    assert(result.retLaneIncrement)
    assert(!result.vectorLaneDataWrite)
  }

  test("keeps the resolve request valid when the reduced destination sideband is absent") {
    val result = LoadReplayReturnRobResolveDataCandidateReference(
      enable = true,
      flush = false,
      setMemDataValid = true,
      reducedSingleLane = true,
      ridValid = true,
      rid = 3,
      dst = Dst(valid = false),
      data = 0x1234)

    assert(result.resolveValid)
    assert(result.readyForPipeInsert)
    assert(result.markAllDestinationsDataValid)
    assert(!result.markDestinationDataValid)
    assert(result.blockedByNoDestination)
  }

  test("blocks unsupported multi-lane resolve until vector lane ownership exists") {
    val result = LoadReplayReturnRobResolveDataCandidateReference(
      enable = true,
      flush = false,
      setMemDataValid = true,
      reducedSingleLane = false,
      ridValid = true,
      rid = 3,
      dst = Dst(),
      data = 0x1234)

    assert(result.candidateValid)
    assert(!result.resolveValid)
    assert(!result.readyForPipeInsert)
    assert(result.blockedByUnsupportedMultiLane)
    assert(!result.retLaneIncrement)
  }

  test("reports disabled flush no-setMemData and invalid-RID blockers") {
    val disabled = LoadReplayReturnRobResolveDataCandidateReference(
      enable = false,
      flush = false,
      setMemDataValid = true,
      reducedSingleLane = true,
      ridValid = true,
      rid = 1,
      dst = Dst(),
      data = 0x11)
    assert(disabled.blockedByDisabled)
    assert(!disabled.candidateValid)

    val flushed = LoadReplayReturnRobResolveDataCandidateReference(
      enable = true,
      flush = true,
      setMemDataValid = true,
      reducedSingleLane = true,
      ridValid = true,
      rid = 1,
      dst = Dst(),
      data = 0x11)
    assert(flushed.blockedByFlush)
    assert(!flushed.candidateValid)

    val noSetMemData = LoadReplayReturnRobResolveDataCandidateReference(
      enable = true,
      flush = false,
      setMemDataValid = false,
      reducedSingleLane = true,
      ridValid = true,
      rid = 1,
      dst = Dst(),
      data = 0x11)
    assert(noSetMemData.blockedByNoSetMemData)
    assert(!noSetMemData.candidateValid)

    val invalidRid = LoadReplayReturnRobResolveDataCandidateReference(
      enable = true,
      flush = false,
      setMemDataValid = true,
      reducedSingleLane = true,
      ridValid = false,
      rid = 1,
      dst = Dst(),
      data = 0x11)
    assert(invalidRid.candidateValid)
    assert(!invalidRid.resolveValid)
    assert(invalidRid.blockedByInvalidRid)
  }

  test("Chisel LoadReplayReturnRobResolveDataCandidate elaborates resolve diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnRobResolveDataCandidate)

    assert(sv.contains("module LoadReplayReturnRobResolveDataCandidate"))
    assert(sv.contains("io_setMemDataValid"))
    assert(sv.contains("io_readyForPipeInsert"))
    assert(sv.contains("io_markAllDestinationsDataValid"))
    assert(sv.contains("io_blockedByUnsupportedMultiLane"))
  }
}
