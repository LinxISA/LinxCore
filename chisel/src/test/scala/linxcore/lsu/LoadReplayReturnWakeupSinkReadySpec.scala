package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnWakeupSinkReadyReference {
  final case class Result(
      candidateValid: Boolean,
      wakeupArmed: Boolean,
      wakeupSinkReady: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByNoWakeup: Boolean,
      blockedBySink: Boolean,
      blockedByLiveDisabled: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      liveEnable: Boolean,
      wakeupRequired: Boolean,
      sinkReady: Boolean): Result = {
    val candidateValid = enable && !flush && wakeupRequired
    val wakeupArmed = candidateValid && sinkReady

    Result(
      candidateValid = candidateValid,
      wakeupArmed = wakeupArmed,
      wakeupSinkReady = wakeupArmed && liveEnable,
      blockedByDisabled = !enable && wakeupRequired,
      blockedByFlush = enable && flush && wakeupRequired,
      blockedByNoWakeup = enable && !flush && !wakeupRequired,
      blockedBySink = candidateValid && !sinkReady,
      blockedByLiveDisabled = wakeupArmed && !liveEnable)
  }
}

class LoadReplayReturnWakeupSinkReadySpec extends AnyFunSuite {
  import LoadReplayReturnWakeupSinkReadyReference._

  test("reports ready when wakeup is required, sink is ready, and live enable is active") {
    val result = LoadReplayReturnWakeupSinkReadyReference(
      enable = true,
      flush = false,
      liveEnable = true,
      wakeupRequired = true,
      sinkReady = true)

    assert(result.candidateValid)
    assert(result.wakeupArmed)
    assert(result.wakeupSinkReady)
    assert(!result.blockedByLiveDisabled)
    assert(!result.blockedBySink)
  }

  test("arms but does not report ready while live wakeup mutation is disabled") {
    val result = LoadReplayReturnWakeupSinkReadyReference(
      enable = true,
      flush = false,
      liveEnable = false,
      wakeupRequired = true,
      sinkReady = true)

    assert(result.candidateValid)
    assert(result.wakeupArmed)
    assert(!result.wakeupSinkReady)
    assert(result.blockedByLiveDisabled)
    assert(!result.blockedBySink)
  }

  test("reports ready-table and issue-wakeup backpressure before live-disabled blocking") {
    val result = LoadReplayReturnWakeupSinkReadyReference(
      enable = true,
      flush = false,
      liveEnable = false,
      wakeupRequired = true,
      sinkReady = false)

    assert(result.candidateValid)
    assert(!result.wakeupArmed)
    assert(!result.wakeupSinkReady)
    assert(result.blockedBySink)
    assert(!result.blockedByLiveDisabled)
  }

  test("reports disabled flush and no-wakeup diagnostics without claiming readiness") {
    val disabled = LoadReplayReturnWakeupSinkReadyReference(
      enable = false,
      flush = false,
      liveEnable = true,
      wakeupRequired = true,
      sinkReady = true)
    val flushed = LoadReplayReturnWakeupSinkReadyReference(
      enable = true,
      flush = true,
      liveEnable = true,
      wakeupRequired = true,
      sinkReady = true)
    val empty = LoadReplayReturnWakeupSinkReadyReference(
      enable = true,
      flush = false,
      liveEnable = true,
      wakeupRequired = false,
      sinkReady = true)

    assert(disabled.blockedByDisabled)
    assert(!disabled.candidateValid)
    assert(!disabled.wakeupSinkReady)
    assert(flushed.blockedByFlush)
    assert(!flushed.candidateValid)
    assert(!flushed.wakeupSinkReady)
    assert(empty.blockedByNoWakeup)
    assert(!empty.candidateValid)
    assert(!empty.wakeupSinkReady)
  }

  test("Chisel LoadReplayReturnWakeupSinkReady elaborates wakeup diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnWakeupSinkReady)

    assert(sv.contains("module LoadReplayReturnWakeupSinkReady"))
    assert(sv.contains("io_liveEnable"))
    assert(sv.contains("io_wakeupRequired"))
    assert(sv.contains("io_sinkReady"))
    assert(sv.contains("io_wakeupArmed"))
    assert(sv.contains("io_wakeupSinkReady"))
    assert(sv.contains("io_blockedByLiveDisabled"))
  }
}
