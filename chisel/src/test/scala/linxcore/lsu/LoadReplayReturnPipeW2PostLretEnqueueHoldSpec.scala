package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2PostLretEnqueueHoldReference {
  final case class Result(
      holdStart: Boolean,
      suppressCurrentClear: Boolean,
      releaseClear: Boolean,
      completionReady: Boolean,
      holdActive: Boolean)

  def idle(
      enable: Boolean,
      flush: Boolean,
      slotOccupied: Boolean,
      completionClearSlot: Boolean,
      liveClear: Boolean,
      lretEnqueueAccepted: Boolean,
      holdCycles: Int): Result = {
    require(holdCycles >= 0, "holdCycles must be nonnegative")
    val holdStart =
      holdCycles > 0 &&
        enable &&
        !flush &&
        slotOccupied &&
        completionClearSlot &&
        liveClear &&
        lretEnqueueAccepted

    Result(
      holdStart = holdStart,
      suppressCurrentClear = holdStart,
      releaseClear = false,
      completionReady = true,
      holdActive = false)
  }

  def active(
      enable: Boolean,
      flush: Boolean,
      remaining: Int): Result = {
    require(remaining >= 0, "remaining hold count must be nonnegative")
    val release = enable && !flush && remaining == 0

    Result(
      holdStart = false,
      suppressCurrentClear = false,
      releaseClear = release,
      completionReady = false,
      holdActive = enable && !flush && !release)
  }
}

class LoadReplayReturnPipeW2PostLretEnqueueHoldSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2PostLretEnqueueHoldReference._

  test("holdCycles zero is transparent") {
    val result = LoadReplayReturnPipeW2PostLretEnqueueHoldReference.idle(
      enable = true,
      flush = false,
      slotOccupied = true,
      completionClearSlot = true,
      liveClear = true,
      lretEnqueueAccepted = true,
      holdCycles = 0)

    assert(!result.holdStart)
    assert(!result.suppressCurrentClear)
    assert(!result.releaseClear)
    assert(result.completionReady)
    assert(!result.holdActive)
  }

  test("starts a hold only when accepted LRET enqueue overlaps live W2 clear") {
    val start = LoadReplayReturnPipeW2PostLretEnqueueHoldReference.idle(
      enable = true,
      flush = false,
      slotOccupied = true,
      completionClearSlot = true,
      liveClear = true,
      lretEnqueueAccepted = true,
      holdCycles = 1)
    val noEnqueue = LoadReplayReturnPipeW2PostLretEnqueueHoldReference.idle(
      enable = true,
      flush = false,
      slotOccupied = true,
      completionClearSlot = true,
      liveClear = true,
      lretEnqueueAccepted = false,
      holdCycles = 1)
    val noClear = LoadReplayReturnPipeW2PostLretEnqueueHoldReference.idle(
      enable = true,
      flush = false,
      slotOccupied = true,
      completionClearSlot = true,
      liveClear = false,
      lretEnqueueAccepted = true,
      holdCycles = 1)

    assert(start.holdStart)
    assert(start.suppressCurrentClear)
    assert(!noEnqueue.holdStart)
    assert(!noClear.holdStart)
  }

  test("active hold blocks W2 completion refire until direct release clear") {
    val held = LoadReplayReturnPipeW2PostLretEnqueueHoldReference.active(
      enable = true,
      flush = false,
      remaining = 1)
    val release = LoadReplayReturnPipeW2PostLretEnqueueHoldReference.active(
      enable = true,
      flush = false,
      remaining = 0)

    assert(held.holdActive)
    assert(!held.completionReady)
    assert(!held.releaseClear)
    assert(!release.holdActive)
    assert(!release.completionReady)
    assert(release.releaseClear)
  }

  test("flush and disable prevent new holds and release clears") {
    val flushedStart = LoadReplayReturnPipeW2PostLretEnqueueHoldReference.idle(
      enable = true,
      flush = true,
      slotOccupied = true,
      completionClearSlot = true,
      liveClear = true,
      lretEnqueueAccepted = true,
      holdCycles = 1)
    val disabledRelease = LoadReplayReturnPipeW2PostLretEnqueueHoldReference.active(
      enable = false,
      flush = false,
      remaining = 0)

    assert(!flushedStart.holdStart)
    assert(!flushedStart.suppressCurrentClear)
    assert(!disabledRelease.releaseClear)
    assert(!disabledRelease.holdActive)
  }

  test("Chisel LoadReplayReturnPipeW2PostLretEnqueueHold elaborates hold controls") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPipeW2PostLretEnqueueHold(holdCycles = 1))

    assert(sv.contains("module LoadReplayReturnPipeW2PostLretEnqueueHold"))
    assert(sv.contains("io_suppressCurrentClear"))
    assert(sv.contains("io_releaseClear"))
    assert(sv.contains("io_completionReady"))
    assert(sv.contains("io_holdStart"))
  }
}
