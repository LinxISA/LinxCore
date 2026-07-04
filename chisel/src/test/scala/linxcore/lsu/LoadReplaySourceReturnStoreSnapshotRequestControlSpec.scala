package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplaySourceReturnStoreSnapshotRequestControlReference {
  final case class Result(
      active: Boolean,
      requestActive: Boolean,
      queryCandidate: Boolean,
      queryRequestEnable: Boolean,
      querySinkReady: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByRequestDisabled: Boolean,
      blockedByNoLaunch: Boolean,
      blockedBySink: Boolean,
      blockedByToken: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      requestEnable: Boolean,
      launchValid: Boolean,
      rawSinkReady: Boolean,
      tokenCanAccept: Boolean): Result = {
    val active = enable && !flush
    val rawRequest = requestEnable || launchValid || rawSinkReady
    val requestActive = active && requestEnable
    val queryCandidate = active && launchValid
    val queryReadyCandidate = requestActive && launchValid
    val sinkReady = active && requestEnable && rawSinkReady && tokenCanAccept

    Result(
      active = active,
      requestActive = requestActive,
      queryCandidate = queryCandidate,
      queryRequestEnable = requestEnable,
      querySinkReady = sinkReady,
      blockedByDisabled = !enable && rawRequest,
      blockedByFlush = enable && flush && rawRequest,
      blockedByRequestDisabled = active && launchValid && !requestEnable,
      blockedByNoLaunch = requestActive && !launchValid,
      blockedBySink = queryReadyCandidate && !rawSinkReady,
      blockedByToken = queryReadyCandidate && rawSinkReady && !tokenCanAccept)
  }
}

class LoadReplaySourceReturnStoreSnapshotRequestControlSpec extends AnyFunSuite {
  import LoadReplaySourceReturnStoreSnapshotRequestControlReference._

  test("current reduced top shape leaves request and sink disabled") {
    val result = LoadReplaySourceReturnStoreSnapshotRequestControlReference(
      enable = true,
      flush = false,
      requestEnable = false,
      launchValid = true,
      rawSinkReady = false,
      tokenCanAccept = true)

    assert(result.active)
    assert(result.queryCandidate)
    assert(!result.requestActive)
    assert(!result.querySinkReady)
    assert(result.blockedByRequestDisabled)
  }

  test("ready live request exposes sink readiness to query issue") {
    val result = LoadReplaySourceReturnStoreSnapshotRequestControlReference(
      enable = true,
      flush = false,
      requestEnable = true,
      launchValid = true,
      rawSinkReady = true,
      tokenCanAccept = true)

    assert(result.requestActive)
    assert(result.queryCandidate)
    assert(result.queryRequestEnable)
    assert(result.querySinkReady)
    assert(!result.blockedBySink)
    assert(!result.blockedByToken)
  }

  test("raw sink stall blocks an otherwise valid live request") {
    val result = LoadReplaySourceReturnStoreSnapshotRequestControlReference(
      enable = true,
      flush = false,
      requestEnable = true,
      launchValid = true,
      rawSinkReady = false,
      tokenCanAccept = true)

    assert(result.requestActive)
    assert(!result.querySinkReady)
    assert(result.blockedBySink)
    assert(!result.blockedByToken)
  }

  test("accepted-token capacity blocks sink readiness separately from raw sink") {
    val result = LoadReplaySourceReturnStoreSnapshotRequestControlReference(
      enable = true,
      flush = false,
      requestEnable = true,
      launchValid = true,
      rawSinkReady = true,
      tokenCanAccept = false)

    assert(result.requestActive)
    assert(!result.querySinkReady)
    assert(!result.blockedBySink)
    assert(result.blockedByToken)
  }

  test("disabled, flushed, and no-launch states suppress live sink readiness") {
    val disabled = LoadReplaySourceReturnStoreSnapshotRequestControlReference(
      enable = false,
      flush = false,
      requestEnable = true,
      launchValid = true,
      rawSinkReady = true,
      tokenCanAccept = true)
    val flushed = LoadReplaySourceReturnStoreSnapshotRequestControlReference(
      enable = true,
      flush = true,
      requestEnable = true,
      launchValid = true,
      rawSinkReady = true,
      tokenCanAccept = true)
    val noLaunch = LoadReplaySourceReturnStoreSnapshotRequestControlReference(
      enable = true,
      flush = false,
      requestEnable = true,
      launchValid = false,
      rawSinkReady = true,
      tokenCanAccept = true)

    assert(disabled.blockedByDisabled)
    assert(flushed.blockedByFlush)
    assert(noLaunch.blockedByNoLaunch)
    assert(!disabled.querySinkReady)
    assert(!flushed.querySinkReady)
    assert(noLaunch.querySinkReady)
  }

  test("Chisel LoadReplaySourceReturnStoreSnapshotRequestControl elaborates request diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplaySourceReturnStoreSnapshotRequestControl)

    assert(sv.contains("module LoadReplaySourceReturnStoreSnapshotRequestControl"))
    assert(sv.contains("io_rawSinkReady"))
    assert(sv.contains("io_querySinkReady"))
    assert(sv.contains("io_blockedByToken"))
  }
}
