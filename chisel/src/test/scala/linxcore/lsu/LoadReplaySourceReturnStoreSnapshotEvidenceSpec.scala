package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplaySourceReturnStoreSnapshotEvidenceReference {
  final case class Result(
      active: Boolean,
      requestValid: Boolean,
      queryActive: Boolean,
      responseAccepted: Boolean,
      snapshotRequired: Boolean,
      snapshotValid: Boolean,
      waitStoreReplay: Boolean,
      mergeDataPresent: Boolean,
      noDataReturn: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByNoLaunch: Boolean,
      blockedByNoQuery: Boolean,
      blockedByNoResponse: Boolean,
      blockedByWaitStore: Boolean,
      invalidResponseWithoutQuery: Boolean,
      invalidWaitStoreWithoutResponse: Boolean,
      invalidDataWithWaitStore: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      launchValid: Boolean,
      queryIssued: Boolean,
      responseValid: Boolean,
      waitStore: Boolean,
      dataValid: Boolean): Result = {
    val active = enable && !flush
    val rawEvidence = launchValid || queryIssued || responseValid || waitStore || dataValid
    val requestValid = active && launchValid
    val queryActive = requestValid && queryIssued
    val responseAccepted = queryActive && responseValid
    val waitStoreReplay = responseAccepted && waitStore
    val snapshotValid = responseAccepted && !waitStore

    Result(
      active = active,
      requestValid = requestValid,
      queryActive = queryActive,
      responseAccepted = responseAccepted,
      snapshotRequired = requestValid,
      snapshotValid = snapshotValid,
      waitStoreReplay = waitStoreReplay,
      mergeDataPresent = snapshotValid && dataValid,
      noDataReturn = snapshotValid && !dataValid,
      blockedByDisabled = !enable && rawEvidence,
      blockedByFlush = enable && flush && rawEvidence,
      blockedByNoLaunch = active && !launchValid && rawEvidence,
      blockedByNoQuery = requestValid && !queryIssued,
      blockedByNoResponse = queryActive && !responseValid,
      blockedByWaitStore = waitStoreReplay,
      invalidResponseWithoutQuery = active && responseValid && !queryIssued,
      invalidWaitStoreWithoutResponse = active && waitStore && !responseValid,
      invalidDataWithWaitStore = responseAccepted && waitStore && dataValid)
  }
}

class LoadReplaySourceReturnStoreSnapshotEvidenceSpec extends AnyFunSuite {
  import LoadReplaySourceReturnStoreSnapshotEvidenceReference._

  test("inactive or no selected replay row does not require a snapshot") {
    val idle = LoadReplaySourceReturnStoreSnapshotEvidenceReference(
      enable = true,
      flush = false,
      launchValid = false,
      queryIssued = false,
      responseValid = false,
      waitStore = false,
      dataValid = false)

    assert(idle.active)
    assert(!idle.requestValid)
    assert(!idle.snapshotRequired)
    assert(!idle.snapshotValid)
    assert(!idle.blockedByNoLaunch)
  }

  test("selected replay row requires an issued STQ snapshot query") {
    val result = LoadReplaySourceReturnStoreSnapshotEvidenceReference(
      enable = true,
      flush = false,
      launchValid = true,
      queryIssued = false,
      responseValid = false,
      waitStore = false,
      dataValid = false)

    assert(result.requestValid)
    assert(result.snapshotRequired)
    assert(!result.queryActive)
    assert(!result.snapshotValid)
    assert(result.blockedByNoQuery)
  }

  test("issued query waits until the selected-row STQ response arrives") {
    val result = LoadReplaySourceReturnStoreSnapshotEvidenceReference(
      enable = true,
      flush = false,
      launchValid = true,
      queryIssued = true,
      responseValid = false,
      waitStore = false,
      dataValid = false)

    assert(result.queryActive)
    assert(!result.responseAccepted)
    assert(!result.snapshotValid)
    assert(result.blockedByNoResponse)
  }

  test("STQ response without data still completes snapshot evidence") {
    val result = LoadReplaySourceReturnStoreSnapshotEvidenceReference(
      enable = true,
      flush = false,
      launchValid = true,
      queryIssued = true,
      responseValid = true,
      waitStore = false,
      dataValid = false)

    assert(result.responseAccepted)
    assert(result.snapshotValid)
    assert(result.noDataReturn)
    assert(!result.mergeDataPresent)
    assert(!result.blockedByWaitStore)
  }

  test("STQ response with data reports merge evidence") {
    val result = LoadReplaySourceReturnStoreSnapshotEvidenceReference(
      enable = true,
      flush = false,
      launchValid = true,
      queryIssued = true,
      responseValid = true,
      waitStore = false,
      dataValid = true)

    assert(result.snapshotValid)
    assert(result.mergeDataPresent)
    assert(!result.noDataReturn)
  }

  test("wait-store response blocks snapshot completion and requests replay") {
    val result = LoadReplaySourceReturnStoreSnapshotEvidenceReference(
      enable = true,
      flush = false,
      launchValid = true,
      queryIssued = true,
      responseValid = true,
      waitStore = true,
      dataValid = false)

    assert(result.responseAccepted)
    assert(result.waitStoreReplay)
    assert(result.blockedByWaitStore)
    assert(!result.snapshotValid)
    assert(!result.mergeDataPresent)
  }

  test("disabled, flushed, and malformed response shapes expose diagnostics") {
    val disabled = LoadReplaySourceReturnStoreSnapshotEvidenceReference(
      enable = false,
      flush = false,
      launchValid = true,
      queryIssued = true,
      responseValid = true,
      waitStore = false,
      dataValid = true)
    val flushed = LoadReplaySourceReturnStoreSnapshotEvidenceReference(
      enable = true,
      flush = true,
      launchValid = true,
      queryIssued = true,
      responseValid = true,
      waitStore = false,
      dataValid = true)
    val responseWithoutQuery = LoadReplaySourceReturnStoreSnapshotEvidenceReference(
      enable = true,
      flush = false,
      launchValid = true,
      queryIssued = false,
      responseValid = true,
      waitStore = false,
      dataValid = false)
    val waitWithoutResponse = LoadReplaySourceReturnStoreSnapshotEvidenceReference(
      enable = true,
      flush = false,
      launchValid = true,
      queryIssued = true,
      responseValid = false,
      waitStore = true,
      dataValid = false)
    val dataWithWait = LoadReplaySourceReturnStoreSnapshotEvidenceReference(
      enable = true,
      flush = false,
      launchValid = true,
      queryIssued = true,
      responseValid = true,
      waitStore = true,
      dataValid = true)

    assert(disabled.blockedByDisabled)
    assert(!disabled.snapshotValid)
    assert(flushed.blockedByFlush)
    assert(!flushed.snapshotValid)
    assert(responseWithoutQuery.invalidResponseWithoutQuery)
    assert(waitWithoutResponse.invalidWaitStoreWithoutResponse)
    assert(dataWithWait.invalidDataWithWaitStore)
  }

  test("Chisel LoadReplaySourceReturnStoreSnapshotEvidence elaborates diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplaySourceReturnStoreSnapshotEvidence)

    assert(sv.contains("module LoadReplaySourceReturnStoreSnapshotEvidence"))
    assert(sv.contains("io_snapshotRequired"))
    assert(sv.contains("io_snapshotValid"))
    assert(sv.contains("io_blockedByWaitStore"))
  }
}
