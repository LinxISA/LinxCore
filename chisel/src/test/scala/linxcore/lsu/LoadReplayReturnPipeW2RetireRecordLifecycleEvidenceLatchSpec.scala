package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2RetireRecordLifecycleEvidenceLatchReference {
  final case class State(valid: Boolean, index: Int)
  final case class Result(
      state: State,
      active: Boolean,
      captureIntent: Boolean,
      captureFromLifecycle: Boolean,
      captureBlockedByNoLifecycle: Boolean,
      clearAccepted: Boolean,
      providerValid: Boolean,
      providerRowClearReady: Boolean,
      providerRowClearIndex: Int,
      providerValidWithoutRecord: Boolean,
      recordValidWithoutProvider: Boolean)

  def step(
      state: State,
      enable: Boolean,
      flush: Boolean,
      captureAccepted: Boolean,
      captureLifecycleRowClearReady: Boolean,
      captureRowClearIndex: Int,
      recordValid: Boolean,
      recordFire: Boolean): Result = {
    val active = enable && !flush
    val captureIntent = active && captureAccepted
    val captureFromLifecycle = captureIntent && captureLifecycleRowClearReady
    val clearAccepted = active && recordFire && state.valid
    val providerValid = active && state.valid && recordValid
    val nextState =
      if (flush || !enable) {
        State(valid = false, index = 0)
      } else if (captureFromLifecycle) {
        State(valid = true, index = captureRowClearIndex)
      } else if (clearAccepted) {
        State(valid = false, index = state.index)
      } else {
        state
      }

    Result(
      state = nextState,
      active = active,
      captureIntent = captureIntent,
      captureFromLifecycle = captureFromLifecycle,
      captureBlockedByNoLifecycle = captureIntent && !captureLifecycleRowClearReady,
      clearAccepted = clearAccepted,
      providerValid = providerValid,
      providerRowClearReady = providerValid,
      providerRowClearIndex = if (providerValid) state.index else 0,
      providerValidWithoutRecord = active && state.valid && !recordValid,
      recordValidWithoutProvider = active && recordValid && !state.valid)
  }
}

class LoadReplayReturnPipeW2RetireRecordLifecycleEvidenceLatchSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2RetireRecordLifecycleEvidenceLatchReference._

  test("captures lifecycle evidence and provides it for a retained record") {
    val captured = step(
      state = State(valid = false, index = 0),
      enable = true,
      flush = false,
      captureAccepted = true,
      captureLifecycleRowClearReady = true,
      captureRowClearIndex = 5,
      recordValid = false,
      recordFire = false)
    val provided = step(
      state = captured.state,
      enable = true,
      flush = false,
      captureAccepted = false,
      captureLifecycleRowClearReady = false,
      captureRowClearIndex = 0,
      recordValid = true,
      recordFire = false)

    assert(captured.captureIntent)
    assert(captured.captureFromLifecycle)
    assert(captured.state.valid)
    assert(provided.providerValid)
    assert(provided.providerRowClearReady)
    assert(provided.providerRowClearIndex == 5)
  }

  test("reports capture when lifecycle evidence is missing") {
    val result = step(
      state = State(valid = false, index = 0),
      enable = true,
      flush = false,
      captureAccepted = true,
      captureLifecycleRowClearReady = false,
      captureRowClearIndex = 0,
      recordValid = true,
      recordFire = false)

    assert(result.captureIntent)
    assert(!result.captureFromLifecycle)
    assert(result.captureBlockedByNoLifecycle)
    assert(result.recordValidWithoutProvider)
    assert(!result.state.valid)
  }

  test("clears retained lifecycle evidence on record fire") {
    val result = step(
      state = State(valid = true, index = 3),
      enable = true,
      flush = false,
      captureAccepted = false,
      captureLifecycleRowClearReady = false,
      captureRowClearIndex = 0,
      recordValid = true,
      recordFire = true)

    assert(result.providerValid)
    assert(result.clearAccepted)
    assert(!result.state.valid)
  }

  test("flush and disable clear retained lifecycle evidence") {
    val flushed = step(
      state = State(valid = true, index = 2),
      enable = true,
      flush = true,
      captureAccepted = true,
      captureLifecycleRowClearReady = true,
      captureRowClearIndex = 4,
      recordValid = true,
      recordFire = false)
    val disabled = step(
      state = State(valid = true, index = 2),
      enable = false,
      flush = false,
      captureAccepted = false,
      captureLifecycleRowClearReady = false,
      captureRowClearIndex = 0,
      recordValid = true,
      recordFire = false)

    assert(!flushed.active)
    assert(!flushed.state.valid)
    assert(!disabled.active)
    assert(!disabled.state.valid)
  }

  test("Chisel LoadReplayReturnPipeW2RetireRecordLifecycleEvidenceLatch elaborates") {
    val sv = ChiselStage.emitSystemVerilog(
      new LoadReplayReturnPipeW2RetireRecordLifecycleEvidenceLatch(liqEntries = 16))

    assert(sv.contains("module LoadReplayReturnPipeW2RetireRecordLifecycleEvidenceLatch"))
    assert(sv.contains("io_captureFromLifecycle"))
    assert(sv.contains("io_providerRowClearReady"))
  }
}
