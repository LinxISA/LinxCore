package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressBoundaryReference {
  final case class State(registeredValid: Boolean = false, registeredMask: Int = 0)
  final case class Result(
      capture: Boolean,
      registeredValid: Boolean,
      registeredMask: Int,
      registeredFullMask: Boolean,
      blockedByNoSelection: Boolean,
      blockedByPartialMask: Boolean,
      clearedByFlush: Boolean,
      next: State)

  def step(
      state: State,
      enable: Boolean,
      flush: Boolean,
      selected: Boolean,
      selectedMask: Int,
      allOrNoneInputMask: Boolean): Result = {
    val active = enable && !flush
    val capture = active && selected && allOrNoneInputMask && selectedMask == 0xf
    val next =
      if (!enable || flush) State()
      else State(registeredValid = capture, registeredMask = if (capture) selectedMask else 0)

    Result(
      capture = capture,
      registeredValid = state.registeredValid,
      registeredMask = state.registeredMask,
      registeredFullMask = state.registeredValid && state.registeredMask == 0xf,
      blockedByNoSelection = active && !selected,
      blockedByPartialMask = active && selected && !allOrNoneInputMask,
      clearedByFlush = flush && state.registeredValid,
      next = next)
  }
}

class LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressBoundarySpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressBoundaryReference._

  test("captures a full selected suppress mask into the next-cycle boundary") {
    val first = step(
      State(),
      enable = true,
      flush = false,
      selected = true,
      selectedMask = 0xf,
      allOrNoneInputMask = true)
    val second = step(
      first.next,
      enable = true,
      flush = false,
      selected = false,
      selectedMask = 0,
      allOrNoneInputMask = true)

    assert(first.capture)
    assert(!first.registeredValid)
    assert(second.registeredValid)
    assert(second.registeredMask == 0xf)
    assert(second.registeredFullMask)
  }

  test("does not register a missing selection") {
    val result = step(
      State(),
      enable = true,
      flush = false,
      selected = false,
      selectedMask = 0xf,
      allOrNoneInputMask = true)

    assert(!result.capture)
    assert(result.blockedByNoSelection)
    assert(!result.next.registeredValid)
    assert(result.next.registeredMask == 0)
  }

  test("rejects a partial selected mask") {
    val result = step(
      State(),
      enable = true,
      flush = false,
      selected = true,
      selectedMask = 0x7,
      allOrNoneInputMask = false)

    assert(!result.capture)
    assert(result.blockedByPartialMask)
    assert(!result.next.registeredValid)
  }

  test("flush clears an existing registered mask") {
    val result = step(
      State(registeredValid = true, registeredMask = 0xf),
      enable = true,
      flush = true,
      selected = true,
      selectedMask = 0xf,
      allOrNoneInputMask = true)

    assert(result.registeredValid)
    assert(result.clearedByFlush)
    assert(!result.next.registeredValid)
    assert(result.next.registeredMask == 0)
  }

  test("Chisel LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressBoundary elaborates") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressBoundary)

    assert(sv.contains("module LoadReplayReturnPipeW2RetireRecordPhysicalBundleSuppressBoundary"))
    assert(sv.contains("io_registeredValid"))
    assert(sv.contains("io_registeredMask"))
  }
}
