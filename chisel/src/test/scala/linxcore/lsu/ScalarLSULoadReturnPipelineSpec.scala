package linxcore.lsu

import circt.stage.ChiselStage
import linxcore.common.ScalarLsuParams
import org.scalatest.funsuite.AnyFunSuite

object ScalarLSULoadReturnPipelineReference {
  final case class Entry(stid: Int, bid: Int, dstGpr: Boolean, wakeup: Boolean)
  final case class State(w1: Vector[Option[Entry]], w2: Vector[Option[Entry]], nextPipe: Int)

  def empty(pipes: Int): State = State(Vector.fill(pipes)(None), Vector.fill(pipes)(None), 0)

  def step(
      state: State,
      incoming: Option[Entry],
      resolveReady: Vector[Boolean],
      writebackReady: Vector[Boolean],
      wakeupReady: Vector[Boolean]): (Vector[Option[Entry]], State) = {
    val completed = state.w2.zipWithIndex.map { case (entry, pipe) =>
      entry.filter(e => resolveReady(pipe) && (!e.dstGpr || writebackReady(pipe)) &&
        (!e.wakeup || wakeupReady(pipe)))
    }
    val nextW2 = state.w2.indices.map { pipe =>
      if (state.w1(pipe).nonEmpty && (state.w2(pipe).isEmpty || completed(pipe).nonEmpty)) state.w1(pipe)
      else if (completed(pipe).nonEmpty) None
      else state.w2(pipe)
    }.toVector
    val advanced = state.w1.indices.map(pipe =>
      state.w1(pipe).nonEmpty && (state.w2(pipe).isEmpty || completed(pipe).nonEmpty)
    ).toVector
    val free = state.w1.indices.filter(pipe => state.w1(pipe).isEmpty || advanced(pipe))
    val selected = (0 until state.w1.size).map(offset => (state.nextPipe + offset) % state.w1.size)
      .find(free.contains)
    val nextW1 = state.w1.indices.map { pipe =>
      if (incoming.nonEmpty && selected.contains(pipe)) incoming
      else if (advanced(pipe)) None
      else state.w1(pipe)
    }.toVector
    val nextPointer = selected.map(pipe => (pipe + 1) % state.w1.size).getOrElse(state.nextPipe)
    (completed, State(nextW1, nextW2, nextPointer))
  }

  def preciseFlush(state: State, stid: Int, firstKilledBid: Int): State =
    state.copy(
      w1 = state.w1.map(_.filterNot(e => e.stid == stid && e.bid >= firstKilledBid)),
      w2 = state.w2.map(_.filterNot(e => e.stid == stid && e.bid >= firstKilledBid))
    )
}

class ScalarLSULoadReturnPipelineSpec extends AnyFunSuite {
  import ScalarLSULoadReturnPipelineReference._

  test("W2 holds every required side effect until the atomic rendezvous") {
    val load = Entry(stid = 0, bid = 1, dstGpr = true, wakeup = true)
    val (_, s1) = step(empty(2), Some(load), Vector(true, true), Vector(true, true), Vector(true, true))
    val (_, s2) = step(s1, None, Vector(true, true), Vector(true, true), Vector(true, true))
    val (blocked, s3) = step(s2, None, Vector(true, true), Vector(false, true), Vector(true, true))
    assert(blocked.flatten.isEmpty)
    assert(s3.w2.flatten == Vector(load))
    val (completed, s4) = step(s3, None, Vector(true, true), Vector(true, true), Vector(true, true))
    assert(completed.flatten == Vector(load))
    assert(s4.w2.flatten.isEmpty)
  }

  test("same-cycle W2 completion advances W1 and accepts a new return") {
    val a = Entry(0, 1, dstGpr = true, wakeup = true)
    val b = Entry(0, 2, dstGpr = true, wakeup = true)
    val c = Entry(1, 3, dstGpr = false, wakeup = false)
    val (_, s1) = step(empty(1), Some(a), Vector(true), Vector(true), Vector(true))
    val (_, s2) = step(s1, Some(b), Vector(true), Vector(true), Vector(true))
    val (done, s3) = step(s2, Some(c), Vector(true), Vector(true), Vector(true))
    assert(done.flatten == Vector(a))
    assert(s3.w2.flatten == Vector(b))
    assert(s3.w1.flatten == Vector(c))
  }

  test("typed recovery prunes matching W1 and W2 entries only") {
    val older = Entry(0, 1, dstGpr = true, wakeup = true)
    val killed = Entry(0, 4, dstGpr = true, wakeup = true)
    val independent = Entry(1, 5, dstGpr = true, wakeup = true)
    val state = State(Vector(Some(killed), Some(independent)), Vector(Some(older), Some(killed)), 0)
    val pruned = preciseFlush(state, stid = 0, firstKilledBid = 4)
    assert(pruned.w1 == Vector(None, Some(independent)))
    assert(pruned.w2 == Vector(Some(older), None))
  }

  test("canonical pipeline elaborates with scoped multi-pipe stages and atomic sinks") {
    val p = ScalarLsuParams(
      stqEntries = 8,
      commitQueueEntries = 4,
      scbEntries = 4,
      liqEntries = 8,
      resolveQueueEntries = 8,
      loadReturnQueueEntries = 2,
      loadReturnPipeCount = 3,
      stidCount = 2
    )
    val sv = ChiselStage.emitSystemVerilog(new ScalarLSULoadReturnPipeline(32, p))
    assert(sv.contains("module ScalarLSULoadReturnPipeline"))
    assert(sv.contains("io_in_peId"))
    assert(sv.contains("io_in_stid"))
    assert(sv.contains("io_in_tid"))
    assert(sv.contains("io_in_payload_loadLsId"))
    assert(sv.contains("io_resolveReady_2"))
    assert(sv.contains("io_writebackFire_2"))
    assert(sv.contains("io_wakeupFire_2"))
    assert(sv.contains("io_w1PrecisePruneMask"))
    assert(sv.contains("io_w2PrecisePruneMask"))
  }
}
