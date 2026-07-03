package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2WritebackSinkReadyReference {
  final case class Result(
      candidateValid: Boolean,
      writebackArmed: Boolean,
      writebackSinkReady: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByNoWriteback: Boolean,
      blockedBySink: Boolean,
      blockedByLiveDisabled: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      liveEnable: Boolean,
      writebackRequired: Boolean,
      sinkReady: Boolean): Result = {
    val candidateValid = enable && !flush && writebackRequired
    val writebackArmed = candidateValid && sinkReady

    Result(
      candidateValid = candidateValid,
      writebackArmed = writebackArmed,
      writebackSinkReady = writebackArmed && liveEnable,
      blockedByDisabled = !enable && writebackRequired,
      blockedByFlush = enable && flush && writebackRequired,
      blockedByNoWriteback = enable && !flush && !writebackRequired,
      blockedBySink = candidateValid && !sinkReady,
      blockedByLiveDisabled = writebackArmed && !liveEnable)
  }
}

class LoadReplayReturnPipeW2WritebackSinkReadySpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2WritebackSinkReadyReference._

  test("reports ready when W2 writeback is required, sink is ready, and live enable is active") {
    val result = LoadReplayReturnPipeW2WritebackSinkReadyReference(
      enable = true,
      flush = false,
      liveEnable = true,
      writebackRequired = true,
      sinkReady = true)

    assert(result.candidateValid)
    assert(result.writebackArmed)
    assert(result.writebackSinkReady)
    assert(!result.blockedByLiveDisabled)
    assert(!result.blockedBySink)
  }

  test("arms but does not report ready while live W2 writeback mutation is disabled") {
    val result = LoadReplayReturnPipeW2WritebackSinkReadyReference(
      enable = true,
      flush = false,
      liveEnable = false,
      writebackRequired = true,
      sinkReady = true)

    assert(result.candidateValid)
    assert(result.writebackArmed)
    assert(!result.writebackSinkReady)
    assert(result.blockedByLiveDisabled)
    assert(!result.blockedBySink)
  }

  test("reports scalar RF port backpressure before live-disabled blocking") {
    val result = LoadReplayReturnPipeW2WritebackSinkReadyReference(
      enable = true,
      flush = false,
      liveEnable = false,
      writebackRequired = true,
      sinkReady = false)

    assert(result.candidateValid)
    assert(!result.writebackArmed)
    assert(!result.writebackSinkReady)
    assert(result.blockedBySink)
    assert(!result.blockedByLiveDisabled)
  }

  test("reports disabled flush and no-writeback diagnostics without claiming readiness") {
    val disabled = LoadReplayReturnPipeW2WritebackSinkReadyReference(
      enable = false,
      flush = false,
      liveEnable = true,
      writebackRequired = true,
      sinkReady = true)
    val flushed = LoadReplayReturnPipeW2WritebackSinkReadyReference(
      enable = true,
      flush = true,
      liveEnable = true,
      writebackRequired = true,
      sinkReady = true)
    val empty = LoadReplayReturnPipeW2WritebackSinkReadyReference(
      enable = true,
      flush = false,
      liveEnable = true,
      writebackRequired = false,
      sinkReady = true)

    assert(disabled.blockedByDisabled)
    assert(!disabled.candidateValid)
    assert(!disabled.writebackSinkReady)
    assert(flushed.blockedByFlush)
    assert(!flushed.candidateValid)
    assert(!flushed.writebackSinkReady)
    assert(empty.blockedByNoWriteback)
    assert(!empty.candidateValid)
    assert(!empty.writebackSinkReady)
  }

  test("Chisel LoadReplayReturnPipeW2WritebackSinkReady elaborates writeback diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPipeW2WritebackSinkReady)

    assert(sv.contains("module LoadReplayReturnPipeW2WritebackSinkReady"))
    assert(sv.contains("io_liveEnable"))
    assert(sv.contains("io_writebackRequired"))
    assert(sv.contains("io_sinkReady"))
    assert(sv.contains("io_writebackArmed"))
    assert(sv.contains("io_writebackSinkReady"))
    assert(sv.contains("io_blockedByLiveDisabled"))
  }
}
