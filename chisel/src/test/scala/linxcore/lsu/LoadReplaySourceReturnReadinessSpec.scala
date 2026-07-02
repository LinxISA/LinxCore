package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplaySourceReturnReadinessReference {
  final case class Result(
      candidateValid: Boolean,
      storeSourceReturned: Boolean,
      scbSourceReturned: Boolean,
      sourceReturned: Boolean,
      blockedByDisabled: Boolean,
      blockedByNoCandidate: Boolean,
      blockedByBaseData: Boolean,
      blockedByStoreSnapshot: Boolean,
      blockedByScb: Boolean)

  def apply(
      enable: Boolean,
      launchValid: Boolean,
      baseDataReady: Boolean,
      storeSnapshotReady: Boolean,
      externalScbPending: Boolean,
      externalScbReturned: Boolean): Result = {
    val candidateValid = enable && launchValid
    val storeSourceReturned = candidateValid && baseDataReady && storeSnapshotReady
    val scbSourceReturned = candidateValid && (!externalScbPending || externalScbReturned)
    Result(
      candidateValid = candidateValid,
      storeSourceReturned = storeSourceReturned,
      scbSourceReturned = scbSourceReturned,
      sourceReturned = storeSourceReturned && scbSourceReturned,
      blockedByDisabled = !enable && launchValid,
      blockedByNoCandidate = enable && !launchValid,
      blockedByBaseData = candidateValid && !baseDataReady,
      blockedByStoreSnapshot = candidateValid && baseDataReady && !storeSnapshotReady,
      blockedByScb = candidateValid && baseDataReady && storeSnapshotReady && externalScbPending && !externalScbReturned)
  }
}

class LoadReplaySourceReturnReadinessSpec extends AnyFunSuite {
  import LoadReplaySourceReturnReadinessReference._

  test("reduced path returns sources when base data and local store snapshot are ready") {
    val result = LoadReplaySourceReturnReadinessReference(
      enable = true,
      launchValid = true,
      baseDataReady = true,
      storeSnapshotReady = true,
      externalScbPending = false,
      externalScbReturned = false)

    assert(result.candidateValid)
    assert(result.storeSourceReturned)
    assert(result.scbSourceReturned)
    assert(result.sourceReturned)
  }

  test("base data is the first source-return blocker") {
    val result = LoadReplaySourceReturnReadinessReference(
      enable = true,
      launchValid = true,
      baseDataReady = false,
      storeSnapshotReady = true,
      externalScbPending = false,
      externalScbReturned = false)

    assert(!result.sourceReturned)
    assert(result.blockedByBaseData)
    assert(!result.blockedByStoreSnapshot)
    assert(!result.blockedByScb)
  }

  test("local store snapshot readiness is reported separately from SCB") {
    val result = LoadReplaySourceReturnReadinessReference(
      enable = true,
      launchValid = true,
      baseDataReady = true,
      storeSnapshotReady = false,
      externalScbPending = false,
      externalScbReturned = false)

    assert(!result.storeSourceReturned)
    assert(!result.sourceReturned)
    assert(result.blockedByStoreSnapshot)
    assert(!result.blockedByScb)
  }

  test("future external SCB path must return before source readiness") {
    val waiting = LoadReplaySourceReturnReadinessReference(
      enable = true,
      launchValid = true,
      baseDataReady = true,
      storeSnapshotReady = true,
      externalScbPending = true,
      externalScbReturned = false)
    val returned = LoadReplaySourceReturnReadinessReference(
      enable = true,
      launchValid = true,
      baseDataReady = true,
      storeSnapshotReady = true,
      externalScbPending = true,
      externalScbReturned = true)

    assert(!waiting.scbSourceReturned)
    assert(!waiting.sourceReturned)
    assert(waiting.blockedByScb)
    assert(returned.scbSourceReturned)
    assert(returned.sourceReturned)
  }

  test("reports disabled and empty candidate blockers") {
    val disabled = LoadReplaySourceReturnReadinessReference(
      enable = false,
      launchValid = true,
      baseDataReady = true,
      storeSnapshotReady = true,
      externalScbPending = false,
      externalScbReturned = false)
    val empty = LoadReplaySourceReturnReadinessReference(
      enable = true,
      launchValid = false,
      baseDataReady = true,
      storeSnapshotReady = true,
      externalScbPending = false,
      externalScbReturned = false)

    assert(disabled.blockedByDisabled)
    assert(empty.blockedByNoCandidate)
    assert(!disabled.sourceReturned)
    assert(!empty.sourceReturned)
  }

  test("Chisel LoadReplaySourceReturnReadiness elaborates source-return diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplaySourceReturnReadiness)

    assert(sv.contains("module LoadReplaySourceReturnReadiness"))
    assert(sv.contains("io_storeSnapshotReady"))
    assert(sv.contains("io_externalScbPending"))
    assert(sv.contains("io_sourceReturned"))
    assert(sv.contains("io_blockedByStoreSnapshot"))
    assert(sv.contains("io_blockedByScb"))
  }
}
