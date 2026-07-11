package linxcore.recovery

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

class RecoveryCleanupROBProbeSpec extends AnyFunSuite {
  test("ring recovery remains blocked until the cleanup consumer accepts it") {
    final case class State(pending: Boolean = false, size: Int = 3)

    def step(state: State, sourceValid: Boolean, consumerReady: Boolean): State = {
      val consume = state.pending && consumerReady
      val accept = sourceValid && (!state.pending || consumerReady)
      State(
        pending = accept || (state.pending && !consume),
        size = if (consume) 1 else state.size
      )
    }

    val accepted = step(State(), sourceValid = true, consumerReady = false)
    assert(accepted.pending)
    assert(accepted.size == 3)
    val held = step(accepted, sourceValid = false, consumerReady = false)
    assert(held == accepted)
    val consumed = step(held, sourceValid = false, consumerReady = true)
    assert(!consumed.pending)
    assert(consumed.size == 1)
  }

  test("generated recovery probe elaborates ring retention and real ROB prune ownership") {
    val sv = ChiselStage.emitSystemVerilog(new RecoveryCleanupROBProbe)

    assert(sv.contains("module RecoveryCleanupROBProbe"))
    assert(sv.contains("RecoveryCleanupControl"))
    assert(sv.contains("RecoveryEligibilityControl"))
    assert(sv.contains("ROBEntryBank"))
    assert(sv.contains("io_robFlushPruneMask"))
    assert(sv.contains("io_cleanupBlockFlushValid"))
  }
}
