package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayLaunchReadinessReference {
  final case class Result(
      candidateValid: Boolean,
      baseDataReady: Boolean,
      sourcesReturned: Boolean,
      launchReady: Boolean,
      launchEnable: Boolean,
      blockedByDisabled: Boolean,
      blockedByNoCandidate: Boolean,
      blockedByBaseLookup: Boolean,
      blockedByBaseData: Boolean,
      blockedByScb: Boolean,
      blockedByReturn: Boolean)

  def apply(
      enable: Boolean,
      launchValid: Boolean,
      baseLookupGranted: Boolean,
      baseDataReturned: Boolean,
      scbReturned: Boolean,
      returnReady: Boolean): Result = {
    val candidateValid = enable && launchValid
    val baseDataReady = baseLookupGranted && baseDataReturned
    val sourcesReturned = baseDataReady && scbReturned
    val launchReady = candidateValid && sourcesReturned && returnReady

    Result(
      candidateValid = candidateValid,
      baseDataReady = baseDataReady,
      sourcesReturned = sourcesReturned,
      launchReady = launchReady,
      launchEnable = launchReady,
      blockedByDisabled = !enable && launchValid,
      blockedByNoCandidate = enable && !launchValid,
      blockedByBaseLookup = candidateValid && !baseLookupGranted,
      blockedByBaseData = candidateValid && baseLookupGranted && !baseDataReturned,
      blockedByScb = candidateValid && baseDataReady && !scbReturned,
      blockedByReturn = candidateValid && sourcesReturned && !returnReady)
  }
}

class LoadReplayLaunchReadinessSpec extends AnyFunSuite {
  import LoadReplayLaunchReadinessReference._

  test("requires candidate, base data, SCB/source return, and return slot") {
    val result = LoadReplayLaunchReadinessReference(
      enable = true,
      launchValid = true,
      baseLookupGranted = true,
      baseDataReturned = true,
      scbReturned = true,
      returnReady = true)

    assert(result.candidateValid)
    assert(result.baseDataReady)
    assert(result.sourcesReturned)
    assert(result.launchReady)
    assert(result.launchEnable)
    assert(!result.blockedByDisabled)
    assert(!result.blockedByNoCandidate)
    assert(!result.blockedByBaseLookup)
    assert(!result.blockedByBaseData)
    assert(!result.blockedByScb)
    assert(!result.blockedByReturn)
  }

  test("reports the current top-level SCB blocker before live launch") {
    val result = LoadReplayLaunchReadinessReference(
      enable = true,
      launchValid = true,
      baseLookupGranted = true,
      baseDataReturned = true,
      scbReturned = false,
      returnReady = false)

    assert(result.candidateValid)
    assert(result.baseDataReady)
    assert(!result.sourcesReturned)
    assert(!result.launchReady)
    assert(!result.launchEnable)
    assert(result.blockedByScb)
    assert(!result.blockedByReturn)
  }

  test("does not accept execute-owned base data without a replay lookup grant") {
    val result = LoadReplayLaunchReadinessReference(
      enable = true,
      launchValid = true,
      baseLookupGranted = false,
      baseDataReturned = true,
      scbReturned = true,
      returnReady = true)

    assert(result.candidateValid)
    assert(!result.baseDataReady)
    assert(!result.sourcesReturned)
    assert(!result.launchReady)
    assert(result.blockedByBaseLookup)
    assert(!result.blockedByBaseData)
  }

  test("reports return-port blocking after all sources have returned") {
    val result = LoadReplayLaunchReadinessReference(
      enable = true,
      launchValid = true,
      baseLookupGranted = true,
      baseDataReturned = true,
      scbReturned = true,
      returnReady = false)

    assert(result.sourcesReturned)
    assert(!result.launchReady)
    assert(!result.launchEnable)
    assert(result.blockedByReturn)
  }

  test("Chisel LoadReplayLaunchReadiness elaborates replay launch diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayLaunchReadiness)

    assert(sv.contains("module LoadReplayLaunchReadiness"))
    assert(sv.contains("io_enable"))
    assert(sv.contains("io_launchValid"))
    assert(sv.contains("io_baseLookupGranted"))
    assert(sv.contains("io_baseDataReturned"))
    assert(sv.contains("io_scbReturned"))
    assert(sv.contains("io_returnReady"))
    assert(sv.contains("io_launchEnable"))
    assert(sv.contains("io_blockedByBaseLookup"))
    assert(sv.contains("io_blockedByScb"))
    assert(sv.contains("io_blockedByReturn"))
  }
}
