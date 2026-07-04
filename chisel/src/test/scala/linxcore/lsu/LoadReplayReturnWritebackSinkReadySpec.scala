package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnWritebackSinkReadyReference {
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

class LoadReplayReturnWritebackSinkReadySpec extends AnyFunSuite {
  import LoadReplayReturnWritebackSinkReadyReference._

  test("reports ready when writeback is required, sink is ready, and live enable is active") {
    val result = LoadReplayReturnWritebackSinkReadyReference(
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

  test("arms but does not report ready while live writeback mutation is disabled") {
    val result = LoadReplayReturnWritebackSinkReadyReference(
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

  test("reports scalar RF write-port backpressure before live-disabled blocking") {
    val result = LoadReplayReturnWritebackSinkReadyReference(
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
    val disabled = LoadReplayReturnWritebackSinkReadyReference(
      enable = false,
      flush = false,
      liveEnable = true,
      writebackRequired = true,
      sinkReady = true)
    val flushed = LoadReplayReturnWritebackSinkReadyReference(
      enable = true,
      flush = true,
      liveEnable = true,
      writebackRequired = true,
      sinkReady = true)
    val empty = LoadReplayReturnWritebackSinkReadyReference(
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

  test("Chisel LoadReplayReturnWritebackSinkReady elaborates writeback diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnWritebackSinkReady)

    assert(sv.contains("module LoadReplayReturnWritebackSinkReady"))
    assert(sv.contains("io_liveEnable"))
    assert(sv.contains("io_writebackRequired"))
    assert(sv.contains("io_sinkReady"))
    assert(sv.contains("io_writebackArmed"))
    assert(sv.contains("io_writebackSinkReady"))
    assert(sv.contains("io_blockedByLiveDisabled"))
  }
}
