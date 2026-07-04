package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplaySourceReturnStoreSnapshotReadyControlReference {
  final case class Result(
      active: Boolean,
      requestActive: Boolean,
      snapshotEvidenceValid: Boolean,
      legacyReady: Boolean,
      liveReady: Boolean,
      storeSnapshotReady: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByRequestDisabled: Boolean,
      blockedByLegacySnapshot: Boolean,
      blockedBySnapshot: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      requestEnable: Boolean,
      legacySnapshotReady: Boolean,
      snapshotRequired: Boolean,
      snapshotValid: Boolean): Result = {
    val active = enable && !flush
    val requestActive = active && requestEnable
    val rawEvidence = snapshotRequired || snapshotValid
    val liveReady = requestActive && (!snapshotRequired || snapshotValid)

    Result(
      active = active,
      requestActive = requestActive,
      snapshotEvidenceValid = active && rawEvidence,
      legacyReady = legacySnapshotReady,
      liveReady = liveReady,
      storeSnapshotReady = if (requestEnable) liveReady else legacySnapshotReady,
      blockedByDisabled = !enable && (requestEnable || rawEvidence),
      blockedByFlush = enable && flush && (requestEnable || rawEvidence),
      blockedByRequestDisabled = active && !requestEnable && rawEvidence,
      blockedByLegacySnapshot = active && !requestEnable && !legacySnapshotReady,
      blockedBySnapshot = requestActive && snapshotRequired && !snapshotValid)
  }
}

class LoadReplaySourceReturnStoreSnapshotReadyControlSpec extends AnyFunSuite {
  import LoadReplaySourceReturnStoreSnapshotReadyControlReference._

  test("current reduced top shape preserves legacy snapshot readiness") {
    val ready = LoadReplaySourceReturnStoreSnapshotReadyControlReference(
      enable = true,
      flush = false,
      requestEnable = false,
      legacySnapshotReady = true,
      snapshotRequired = false,
      snapshotValid = false)
    val blocked = LoadReplaySourceReturnStoreSnapshotReadyControlReference(
      enable = true,
      flush = false,
      requestEnable = false,
      legacySnapshotReady = false,
      snapshotRequired = false,
      snapshotValid = false)

    assert(ready.active)
    assert(!ready.requestActive)
    assert(ready.legacyReady)
    assert(ready.storeSnapshotReady)
    assert(!ready.blockedByRequestDisabled)
    assert(!blocked.storeSnapshotReady)
    assert(blocked.blockedByLegacySnapshot)
  }

  test("live optional snapshot request releases when active") {
    val result = LoadReplaySourceReturnStoreSnapshotReadyControlReference(
      enable = true,
      flush = false,
      requestEnable = true,
      legacySnapshotReady = false,
      snapshotRequired = false,
      snapshotValid = false)

    assert(result.requestActive)
    assert(result.liveReady)
    assert(result.storeSnapshotReady)
    assert(!result.blockedBySnapshot)
  }

  test("live required snapshot waits for selected-row evidence") {
    val waiting = LoadReplaySourceReturnStoreSnapshotReadyControlReference(
      enable = true,
      flush = false,
      requestEnable = true,
      legacySnapshotReady = true,
      snapshotRequired = true,
      snapshotValid = false)
    val returned = LoadReplaySourceReturnStoreSnapshotReadyControlReference(
      enable = true,
      flush = false,
      requestEnable = true,
      legacySnapshotReady = false,
      snapshotRequired = true,
      snapshotValid = true)

    assert(waiting.snapshotEvidenceValid)
    assert(!waiting.liveReady)
    assert(!waiting.storeSnapshotReady)
    assert(waiting.blockedBySnapshot)
    assert(returned.liveReady)
    assert(returned.storeSnapshotReady)
    assert(!returned.blockedBySnapshot)
  }

  test("disabled, flushed, and request-disabled states suppress live readiness") {
    val disabled = LoadReplaySourceReturnStoreSnapshotReadyControlReference(
      enable = false,
      flush = false,
      requestEnable = true,
      legacySnapshotReady = true,
      snapshotRequired = true,
      snapshotValid = true)
    val flushed = LoadReplaySourceReturnStoreSnapshotReadyControlReference(
      enable = true,
      flush = true,
      requestEnable = true,
      legacySnapshotReady = true,
      snapshotRequired = true,
      snapshotValid = true)
    val requestDisabled = LoadReplaySourceReturnStoreSnapshotReadyControlReference(
      enable = true,
      flush = false,
      requestEnable = false,
      legacySnapshotReady = true,
      snapshotRequired = true,
      snapshotValid = true)

    assert(disabled.blockedByDisabled)
    assert(!disabled.active)
    assert(!disabled.liveReady)
    assert(!disabled.storeSnapshotReady)
    assert(flushed.blockedByFlush)
    assert(!flushed.active)
    assert(!flushed.liveReady)
    assert(!flushed.storeSnapshotReady)
    assert(requestDisabled.blockedByRequestDisabled)
    assert(!requestDisabled.requestActive)
    assert(requestDisabled.storeSnapshotReady)
  }

  test("Chisel LoadReplaySourceReturnStoreSnapshotReadyControl elaborates diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplaySourceReturnStoreSnapshotReadyControl)

    assert(sv.contains("module LoadReplaySourceReturnStoreSnapshotReadyControl"))
    assert(sv.contains("io_legacySnapshotReady"))
    assert(sv.contains("io_storeSnapshotReady"))
    assert(sv.contains("io_blockedBySnapshot"))
  }
}
