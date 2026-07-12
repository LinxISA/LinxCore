package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object MDBStoreProbeReplayReference {
  final case class Probe(valid: Boolean, pc: BigInt)
  final case class State(retained: Vector[Probe] = Vector.empty)
  final case class Step(
      out: Option[Probe],
      liveReady: Boolean,
      liveSelected: Boolean,
      replaySelected: Boolean,
      replayAccepted: Boolean,
      state: State)

  def step(
      state: State,
      live: Probe,
      liveCommit: Boolean,
      retainForReplay: Boolean,
      replayEnable: Boolean,
      replayConsume: Boolean,
      depth: Int,
      flush: Boolean = false): Step = {
    if (flush) {
      return Step(None, liveReady = true, liveSelected = false, replaySelected = false,
        replayAccepted = false, State())
    }

    val liveReady = !retainForReplay || replayEnable || state.retained.size < depth
    val liveSelected = live.valid
    val replaySelected = !liveSelected && replayEnable && state.retained.nonEmpty
    val replayAccepted = replaySelected && replayConsume
    val out = if (liveSelected) Some(live) else if (replaySelected) state.retained.headOption else None
    val afterReplay = if (replayAccepted) state.retained.tail else state.retained
    val retainLive = live.valid && liveCommit && retainForReplay && !replayEnable && liveReady
    val next = if (retainLive) afterReplay :+ live else afterReplay

    Step(out, liveReady, liveSelected, replaySelected, replayAccepted, State(next))
  }
}

class MDBStoreProbeReplaySpec extends AnyFunSuite {
  import MDBStoreProbeReplayReference._

  test("retains consecutive accepted live probes without overwrite") {
    val first = Probe(valid = true, pc = 0x2000)
    val second = Probe(valid = true, pc = 0x2010)
    val s0 = step(State(), first, liveCommit = true, retainForReplay = true,
      replayEnable = false, replayConsume = true, depth = 2)
    val s1 = step(s0.state, second, liveCommit = true, retainForReplay = true,
      replayEnable = false, replayConsume = true, depth = 2)

    assert(s0.liveReady && s0.liveSelected)
    assert(s1.liveReady && s1.liveSelected)
    assert(s1.state.retained == Vector(first, second))
  }

  test("full replay queue backpressures a third live probe and drains in order") {
    val first = Probe(valid = true, pc = 0x2000)
    val second = Probe(valid = true, pc = 0x2010)
    val third = Probe(valid = true, pc = 0x2020)
    val full = State(Vector(first, second))
    val blocked = step(full, third, liveCommit = false, retainForReplay = true,
      replayEnable = false, replayConsume = false, depth = 2)
    val held = step(blocked.state, Probe(valid = false, pc = 0), liveCommit = false,
      retainForReplay = false, replayEnable = true, replayConsume = false, depth = 2)
    val firstAccepted = step(held.state, Probe(valid = false, pc = 0), liveCommit = false,
      retainForReplay = false, replayEnable = true, replayConsume = true, depth = 2)
    val secondAccepted = step(firstAccepted.state, Probe(valid = false, pc = 0), liveCommit = false,
      retainForReplay = false, replayEnable = true, replayConsume = true, depth = 2)

    assert(!blocked.liveReady)
    assert(blocked.state == full)
    assert(held.replaySelected && !held.replayAccepted && held.out.contains(first))
    assert(firstAccepted.replayAccepted && firstAccepted.out.contains(first))
    assert(secondAccepted.replayAccepted && secondAccepted.out.contains(second))
    assert(secondAccepted.state.retained.isEmpty)
  }

  test("visible ResolveQ consumes the live scan without retaining a duplicate") {
    val live = Probe(valid = true, pc = 0x3000)
    val result = step(State(), live, liveCommit = true, retainForReplay = true,
      replayEnable = true, replayConsume = true, depth = 2)

    assert(result.liveReady && result.liveSelected)
    assert(!result.replaySelected)
    assert(result.out.contains(live))
    assert(result.state.retained.isEmpty)
  }

  test("uncommitted live intent is not retained") {
    val live = Probe(valid = true, pc = 0x4000)
    val result = step(State(), live, liveCommit = false, retainForReplay = true,
      replayEnable = false, replayConsume = false, depth = 2)

    assert(result.liveReady && result.liveSelected)
    assert(result.state.retained.isEmpty)
  }

  test("flush clears every retained replay probe") {
    val old = State(Vector(Probe(valid = true, pc = 0x2000), Probe(valid = true, pc = 0x2010)))
    val result = step(old, Probe(valid = false, pc = 0), liveCommit = false,
      retainForReplay = false, replayEnable = true, replayConsume = true, depth = 2, flush = true)

    assert(result.out.isEmpty)
    assert(result.state.retained.isEmpty)
  }

  test("Chisel MDBStoreProbeReplay elaborates as a finite lossless replay queue") {
    val sv = ChiselStage.emitSystemVerilog(new MDBStoreProbeReplay(entries = 8))

    assert(sv.contains("module MDBStoreProbeReplay"))
    assert(sv.contains("io_liveCommit"))
    assert(sv.contains("io_retainForReplay"))
    assert(sv.contains("io_liveReady"))
    assert(sv.contains("io_liveSelected"))
    assert(sv.contains("io_replaySelected"))
    assert(sv.contains("io_retainedValid"))
    assert(sv.contains("io_retainedCount"))
    assert(sv.contains("io_replayAccepted"))
  }
}
