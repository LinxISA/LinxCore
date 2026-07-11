package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object MDBStoreProbeReplayReference {
  final case class Probe(valid: Boolean, pc: BigInt)
  final case class State(
      retained: Option[Probe] = None,
      replayed: Boolean = false,
      needsRetry: Boolean = false)
  final case class Step(out: Option[Probe], liveSelected: Boolean, replaySelected: Boolean, state: State)

  def step(state: State, live: Probe, replayEnable: Boolean, replayConsume: Boolean, flush: Boolean = false): Step = {
    if (flush) {
      return Step(None, liveSelected = false, replaySelected = false, State())
    }

    val replayEligible = state.retained.nonEmpty && !state.replayed && (state.needsRetry || replayEnable)
    val liveSelected = live.valid
    val replaySelected = !liveSelected && replayEligible
    val out =
      if (liveSelected) {
        Some(live)
      } else if (replaySelected) {
        state.retained
      } else {
        None
      }

    val next =
      if (live.valid) {
        State(
          retained = Some(live),
          replayed = replayConsume && replayEnable,
          needsRetry = !replayConsume)
      } else if (replaySelected && replayConsume) {
        state.copy(replayed = !state.needsRetry || replayEnable, needsRetry = false)
      } else {
        state
      }

    Step(out, liveSelected, replaySelected, next)
  }
}

class MDBStoreProbeReplaySpec extends AnyFunSuite {
  import MDBStoreProbeReplayReference._

  test("retains a live store probe and replays it once when ResolveQ becomes visible") {
    val s0 = State()
    val live = Probe(valid = true, pc = 0x2000)
    val captured = step(s0, live = live, replayEnable = false, replayConsume = true)
    assert(captured.liveSelected)
    assert(!captured.replaySelected)
    assert(captured.out.contains(live))
    assert(captured.state.retained.contains(live))
    assert(!captured.state.replayed)

    val replayed = step(captured.state, live = Probe(valid = false, pc = 0), replayEnable = true, replayConsume = true)
    assert(!replayed.liveSelected)
    assert(replayed.replaySelected)
    assert(replayed.out.contains(live))
    assert(replayed.state.replayed)

    val quiet = step(replayed.state, live = Probe(valid = false, pc = 0), replayEnable = true, replayConsume = true)
    assert(!quiet.liveSelected)
    assert(!quiet.replaySelected)
    assert(quiet.out.isEmpty)
  }

  test("an accepted live probe consumes delayed replay when ResolveQ is already visible") {
    val old = State(retained = Some(Probe(valid = true, pc = 0x2000)), replayed = false)
    val live = Probe(valid = true, pc = 0x3000)
    val result = step(old, live = live, replayEnable = true, replayConsume = true)

    assert(result.liveSelected)
    assert(!result.replaySelected)
    assert(result.out.contains(live))
    assert(result.state.retained.contains(live))
    assert(result.state.replayed)

    val quiet = step(
      result.state,
      live = Probe(valid = false, pc = 0),
      replayEnable = true,
      replayConsume = true
    )
    assert(!quiet.replaySelected)
  }

  test("a replay remains pending until its downstream transaction is accepted") {
    val old = State(retained = Some(Probe(valid = true, pc = 0x2000)), replayed = false)
    val blocked = step(old, live = Probe(valid = false, pc = 0), replayEnable = true, replayConsume = false)
    assert(blocked.replaySelected)
    assert(!blocked.state.replayed)

    val accepted = step(blocked.state, live = Probe(valid = false, pc = 0), replayEnable = true, replayConsume = true)
    assert(accepted.replaySelected)
    assert(accepted.state.replayed)
  }

  test("an unaccepted live probe retries without waiting for ResolveQ visibility") {
    val live = Probe(valid = true, pc = 0x4000)
    val blocked = step(State(), live, replayEnable = false, replayConsume = false)
    assert(blocked.state.needsRetry)

    val retried = step(
      blocked.state,
      live = Probe(valid = false, pc = 0),
      replayEnable = false,
      replayConsume = true
    )
    assert(retried.replaySelected)
    assert(!retried.state.needsRetry)
    assert(!retried.state.replayed)

    val delayed = step(
      retried.state,
      live = Probe(valid = false, pc = 0),
      replayEnable = true,
      replayConsume = true
    )
    assert(delayed.replaySelected)
    assert(delayed.state.replayed)
  }

  test("flush clears retained replay state") {
    val old = State(retained = Some(Probe(valid = true, pc = 0x2000)), replayed = false)
    val result = step(old, live = Probe(valid = false, pc = 0), replayEnable = true, replayConsume = true, flush = true)

    assert(result.out.isEmpty)
    assert(result.state.retained.isEmpty)
    assert(!result.state.replayed)
  }

  test("Chisel MDBStoreProbeReplay elaborates with live and replay diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new MDBStoreProbeReplay(entries = 8))

    assert(sv.contains("module MDBStoreProbeReplay"))
    assert(sv.contains("io_liveSelected"))
    assert(sv.contains("io_replaySelected"))
    assert(sv.contains("io_retainedValid"))
    assert(sv.contains("io_retainedNeedsRetry"))
    assert(sv.contains("io_replayAccepted"))
  }
}
