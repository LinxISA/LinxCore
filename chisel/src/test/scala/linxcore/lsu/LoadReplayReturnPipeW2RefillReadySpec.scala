package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2RefillReadyReference {
  final case class Result(
      active: Boolean,
      emptyReady: Boolean,
      sameCycleRefillEligible: Boolean,
      sameCycleRefillReady: Boolean,
      futureAdvanceReady: Boolean,
      currentMatchesFuture: Boolean,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByOccupied: Boolean,
      blockedByLiveClearDisabled: Boolean,
      invalidLiveClearWithoutIntent: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      slotOccupied: Boolean,
      currentAdvanceReady: Boolean,
      clearIntent: Boolean,
      liveClear: Boolean): Result = {
    val active = enable && !flush
    val emptyReady = active && !slotOccupied
    val sameCycleRefillEligible = active && slotOccupied && clearIntent
    val sameCycleRefillReady = active && slotOccupied && liveClear
    val futureAdvanceReady = emptyReady || sameCycleRefillReady

    Result(
      active = active,
      emptyReady = emptyReady,
      sameCycleRefillEligible = sameCycleRefillEligible,
      sameCycleRefillReady = sameCycleRefillReady,
      futureAdvanceReady = futureAdvanceReady,
      currentMatchesFuture = currentAdvanceReady == futureAdvanceReady,
      blockedByDisabled = !enable && slotOccupied,
      blockedByFlush = enable && flush && slotOccupied,
      blockedByOccupied = active && slotOccupied && !liveClear,
      blockedByLiveClearDisabled = sameCycleRefillEligible && !liveClear,
      invalidLiveClearWithoutIntent = active && liveClear && !clearIntent)
  }
}

class LoadReplayReturnPipeW2RefillReadySpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2RefillReadyReference._

  test("reports ready when W2 is empty and matches the current advance gate") {
    val result = LoadReplayReturnPipeW2RefillReadyReference(
      enable = true,
      flush = false,
      slotOccupied = false,
      currentAdvanceReady = true,
      clearIntent = false,
      liveClear = false)

    assert(result.active)
    assert(result.emptyReady)
    assert(result.futureAdvanceReady)
    assert(result.currentMatchesFuture)
    assert(!result.sameCycleRefillEligible)
    assert(!result.blockedByOccupied)
  }

  test("keeps future advance blocked by an occupied W2 slot without clear") {
    val result = LoadReplayReturnPipeW2RefillReadyReference(
      enable = true,
      flush = false,
      slotOccupied = true,
      currentAdvanceReady = false,
      clearIntent = false,
      liveClear = false)

    assert(!result.emptyReady)
    assert(!result.futureAdvanceReady)
    assert(result.currentMatchesFuture)
    assert(result.blockedByOccupied)
    assert(!result.blockedByLiveClearDisabled)
  }

  test("exposes same-cycle refill eligibility while live clear remains disabled") {
    val result = LoadReplayReturnPipeW2RefillReadyReference(
      enable = true,
      flush = false,
      slotOccupied = true,
      currentAdvanceReady = false,
      clearIntent = true,
      liveClear = false)

    assert(result.sameCycleRefillEligible)
    assert(!result.sameCycleRefillReady)
    assert(!result.futureAdvanceReady)
    assert(result.currentMatchesFuture)
    assert(result.blockedByLiveClearDisabled)
  }

  test("allows future same-cycle refill when live clear is enabled") {
    val result = LoadReplayReturnPipeW2RefillReadyReference(
      enable = true,
      flush = false,
      slotOccupied = true,
      currentAdvanceReady = false,
      clearIntent = true,
      liveClear = true)

    assert(result.sameCycleRefillEligible)
    assert(result.sameCycleRefillReady)
    assert(result.futureAdvanceReady)
    assert(!result.currentMatchesFuture)
    assert(!result.blockedByLiveClearDisabled)
  }

  test("flags live clear evidence that lacks clear intent") {
    val result = LoadReplayReturnPipeW2RefillReadyReference(
      enable = true,
      flush = false,
      slotOccupied = true,
      currentAdvanceReady = false,
      clearIntent = false,
      liveClear = true)

    assert(result.sameCycleRefillReady)
    assert(result.futureAdvanceReady)
    assert(result.invalidLiveClearWithoutIntent)
  }

  test("blocks disabled and flushed occupied W2 slots") {
    val disabled = LoadReplayReturnPipeW2RefillReadyReference(
      enable = false,
      flush = false,
      slotOccupied = true,
      currentAdvanceReady = false,
      clearIntent = true,
      liveClear = true)
    val flushed = LoadReplayReturnPipeW2RefillReadyReference(
      enable = true,
      flush = true,
      slotOccupied = true,
      currentAdvanceReady = false,
      clearIntent = true,
      liveClear = true)

    assert(!disabled.futureAdvanceReady)
    assert(disabled.blockedByDisabled)
    assert(!flushed.futureAdvanceReady)
    assert(flushed.blockedByFlush)
  }

  test("Chisel LoadReplayReturnPipeW2RefillReady elaborates future-ready diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPipeW2RefillReady)

    assert(sv.contains("module LoadReplayReturnPipeW2RefillReady"))
    assert(sv.contains("io_currentAdvanceReady"))
    assert(sv.contains("io_sameCycleRefillEligible"))
    assert(sv.contains("io_futureAdvanceReady"))
    assert(sv.contains("io_invalidLiveClearWithoutIntent"))
  }
}
