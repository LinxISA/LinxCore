package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2ResolveSinkReadyReference {
  final case class Result(
      candidateValid: Boolean,
      resolveArmed: Boolean,
      resolveSinkReady: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByNoResolve: Boolean,
      blockedBySink: Boolean,
      blockedByLiveDisabled: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      liveEnable: Boolean,
      resolveRequired: Boolean,
      sinkReady: Boolean): Result = {
    val candidateValid = enable && !flush && resolveRequired
    val resolveArmed = candidateValid && sinkReady

    Result(
      candidateValid = candidateValid,
      resolveArmed = resolveArmed,
      resolveSinkReady = resolveArmed && liveEnable,
      blockedByDisabled = !enable && resolveRequired,
      blockedByFlush = enable && flush && resolveRequired,
      blockedByNoResolve = enable && !flush && !resolveRequired,
      blockedBySink = candidateValid && !sinkReady,
      blockedByLiveDisabled = resolveArmed && !liveEnable)
  }
}

class LoadReplayReturnPipeW2ResolveSinkReadySpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2ResolveSinkReadyReference._

  test("reports ready when W2 resolve is required, sink is ready, and live enable is active") {
    val result = LoadReplayReturnPipeW2ResolveSinkReadyReference(
      enable = true,
      flush = false,
      liveEnable = true,
      resolveRequired = true,
      sinkReady = true)

    assert(result.candidateValid)
    assert(result.resolveArmed)
    assert(result.resolveSinkReady)
    assert(!result.blockedByLiveDisabled)
    assert(!result.blockedBySink)
  }

  test("arms but does not report ready while live W2 resolve mutation is disabled") {
    val result = LoadReplayReturnPipeW2ResolveSinkReadyReference(
      enable = true,
      flush = false,
      liveEnable = false,
      resolveRequired = true,
      sinkReady = true)

    assert(result.candidateValid)
    assert(result.resolveArmed)
    assert(!result.resolveSinkReady)
    assert(result.blockedByLiveDisabled)
    assert(!result.blockedBySink)
  }

  test("reports sink backpressure before live-disabled blocking") {
    val result = LoadReplayReturnPipeW2ResolveSinkReadyReference(
      enable = true,
      flush = false,
      liveEnable = false,
      resolveRequired = true,
      sinkReady = false)

    assert(result.candidateValid)
    assert(!result.resolveArmed)
    assert(!result.resolveSinkReady)
    assert(result.blockedBySink)
    assert(!result.blockedByLiveDisabled)
  }

  test("reports disabled flush and no-resolve diagnostics without claiming readiness") {
    val disabled = LoadReplayReturnPipeW2ResolveSinkReadyReference(
      enable = false,
      flush = false,
      liveEnable = true,
      resolveRequired = true,
      sinkReady = true)
    val flushed = LoadReplayReturnPipeW2ResolveSinkReadyReference(
      enable = true,
      flush = true,
      liveEnable = true,
      resolveRequired = true,
      sinkReady = true)
    val empty = LoadReplayReturnPipeW2ResolveSinkReadyReference(
      enable = true,
      flush = false,
      liveEnable = true,
      resolveRequired = false,
      sinkReady = true)

    assert(disabled.blockedByDisabled)
    assert(!disabled.candidateValid)
    assert(!disabled.resolveSinkReady)
    assert(flushed.blockedByFlush)
    assert(!flushed.candidateValid)
    assert(!flushed.resolveSinkReady)
    assert(empty.blockedByNoResolve)
    assert(!empty.candidateValid)
    assert(!empty.resolveSinkReady)
  }

  test("Chisel LoadReplayReturnPipeW2ResolveSinkReady elaborates resolve diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPipeW2ResolveSinkReady)

    assert(sv.contains("module LoadReplayReturnPipeW2ResolveSinkReady"))
    assert(sv.contains("io_liveEnable"))
    assert(sv.contains("io_resolveRequired"))
    assert(sv.contains("io_sinkReady"))
    assert(sv.contains("io_resolveArmed"))
    assert(sv.contains("io_resolveSinkReady"))
    assert(sv.contains("io_blockedByLiveDisabled"))
  }
}
