package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplaySourceReturnStoreSnapshotQueryIssueReference {
  final case class Result(
      active: Boolean,
      requestActive: Boolean,
      queryCandidate: Boolean,
      queryValid: Boolean,
      queryIssued: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByRequestDisabled: Boolean,
      blockedByNoLaunch: Boolean,
      blockedBySink: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      requestEnable: Boolean,
      launchValid: Boolean,
      sinkReady: Boolean): Result = {
    val active = enable && !flush
    val rawRequest = requestEnable || launchValid
    val requestActive = active && requestEnable
    val queryCandidate = active && launchValid
    val queryValid = requestActive && launchValid
    val queryIssued = queryValid && sinkReady

    Result(
      active = active,
      requestActive = requestActive,
      queryCandidate = queryCandidate,
      queryValid = queryValid,
      queryIssued = queryIssued,
      blockedByDisabled = !enable && rawRequest,
      blockedByFlush = enable && flush && rawRequest,
      blockedByRequestDisabled = active && launchValid && !requestEnable,
      blockedByNoLaunch = requestActive && !launchValid,
      blockedBySink = queryValid && !sinkReady)
  }
}

class LoadReplaySourceReturnStoreSnapshotQueryIssueSpec extends AnyFunSuite {
  import LoadReplaySourceReturnStoreSnapshotQueryIssueReference._

  test("disabled or flushed query requests expose blockers and issue nothing") {
    val disabled = LoadReplaySourceReturnStoreSnapshotQueryIssueReference(
      enable = false,
      flush = false,
      requestEnable = true,
      launchValid = true,
      sinkReady = true)
    val flushed = LoadReplaySourceReturnStoreSnapshotQueryIssueReference(
      enable = true,
      flush = true,
      requestEnable = true,
      launchValid = true,
      sinkReady = true)

    assert(disabled.blockedByDisabled)
    assert(!disabled.queryIssued)
    assert(flushed.blockedByFlush)
    assert(!flushed.queryIssued)
  }

  test("selected replay row is only a candidate while live request is disabled") {
    val result = LoadReplaySourceReturnStoreSnapshotQueryIssueReference(
      enable = true,
      flush = false,
      requestEnable = false,
      launchValid = true,
      sinkReady = true)

    assert(result.active)
    assert(result.queryCandidate)
    assert(!result.requestActive)
    assert(!result.queryValid)
    assert(!result.queryIssued)
    assert(result.blockedByRequestDisabled)
  }

  test("enabled request waits for a selected replay row") {
    val result = LoadReplaySourceReturnStoreSnapshotQueryIssueReference(
      enable = true,
      flush = false,
      requestEnable = true,
      launchValid = false,
      sinkReady = true)

    assert(result.requestActive)
    assert(!result.queryCandidate)
    assert(!result.queryValid)
    assert(!result.queryIssued)
    assert(result.blockedByNoLaunch)
  }

  test("valid selected-row query waits for downstream STQ lookup capacity") {
    val result = LoadReplaySourceReturnStoreSnapshotQueryIssueReference(
      enable = true,
      flush = false,
      requestEnable = true,
      launchValid = true,
      sinkReady = false)

    assert(result.queryCandidate)
    assert(result.queryValid)
    assert(!result.queryIssued)
    assert(result.blockedBySink)
  }

  test("selected-row query issues when request and sink are both ready") {
    val result = LoadReplaySourceReturnStoreSnapshotQueryIssueReference(
      enable = true,
      flush = false,
      requestEnable = true,
      launchValid = true,
      sinkReady = true)

    assert(result.requestActive)
    assert(result.queryCandidate)
    assert(result.queryValid)
    assert(result.queryIssued)
    assert(!result.blockedBySink)
  }

  test("Chisel LoadReplaySourceReturnStoreSnapshotQueryIssue elaborates diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplaySourceReturnStoreSnapshotQueryIssue)

    assert(sv.contains("module LoadReplaySourceReturnStoreSnapshotQueryIssue"))
    assert(sv.contains("io_queryCandidate"))
    assert(sv.contains("io_queryIssued"))
    assert(sv.contains("io_blockedBySink"))
  }
}
