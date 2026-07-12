package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object ScalarLSULoadReturnQueueReference {
  final case class Entry(stid: Int, pipe: Int, bid: Int, lsid: Int, data: BigInt)
  final case class State(lanes: Vector[Vector[Entry]], nextLane: Int)

  def empty(stids: Int, pipes: Int): State = State(Vector.fill(stids * pipes)(Vector.empty), 0)

  def enqueue(state: State, entry: Entry, stids: Int, pipes: Int, depth: Int): (Boolean, State) = {
    val validTarget = entry.stid >= 0 && entry.stid < stids && entry.pipe >= 0 && entry.pipe < pipes
    if (!validTarget) return (false, state)
    val lane = entry.stid * pipes + entry.pipe
    if (state.lanes(lane).size >= depth) return (false, state)
    (true, state.copy(lanes = state.lanes.updated(lane, state.lanes(lane) :+ entry)))
  }

  def drain(state: State): (Option[Entry], State) = {
    val laneCount = state.lanes.size
    val selected = (0 until laneCount).map(offset => (state.nextLane + offset) % laneCount)
      .find(lane => state.lanes(lane).nonEmpty)
    selected match {
      case None => (None, state)
      case Some(lane) =>
        val entry = state.lanes(lane).head
        val lanes = state.lanes.updated(lane, state.lanes(lane).tail)
        (Some(entry), State(lanes, (lane + 1) % laneCount))
    }
  }

  def preciseFlush(state: State, stid: Int, firstKilledBid: Int): State =
    state.copy(lanes = state.lanes.map(_.filterNot(entry => entry.stid == stid && entry.bid >= firstKilledBid)))
}

class ScalarLSULoadReturnQueueSpec extends AnyFunSuite {
  import ScalarLSULoadReturnQueueReference._

  test("retains independent per-STID and per-pipe FIFO order") {
    val stids = 2
    val pipes = 2
    val depth = 2
    val a = Entry(0, 0, 1, 1, 0x11)
    val b = Entry(0, 0, 2, 2, 0x22)
    val c = Entry(1, 1, 3, 3, 0x33)
    val (_, s1) = enqueue(empty(stids, pipes), a, stids, pipes, depth)
    val (_, s2) = enqueue(s1, b, stids, pipes, depth)
    val (_, s3) = enqueue(s2, c, stids, pipes, depth)

    assert(s3.lanes(0) == Vector(a, b))
    assert(s3.lanes(3) == Vector(c))
  }

  test("backpressures only the selected full lane") {
    val stids = 2
    val pipes = 1
    val first = Entry(0, 0, 1, 1, 0x11)
    val blocked = Entry(0, 0, 2, 2, 0x22)
    val independent = Entry(1, 0, 3, 3, 0x33)
    val (_, full) = enqueue(empty(stids, pipes), first, stids, pipes, depth = 1)
    val (blockedAccepted, same) = enqueue(full, blocked, stids, pipes, depth = 1)
    val (independentAccepted, next) = enqueue(same, independent, stids, pipes, depth = 1)

    assert(!blockedAccepted)
    assert(independentAccepted)
    assert(next.lanes.map(_.size) == Vector(1, 1))
  }

  test("round-robin drain prevents one STID from monopolizing the shared IEX port") {
    val stids = 2
    val pipes = 1
    val a0 = Entry(0, 0, 1, 1, 0x10)
    val a1 = Entry(0, 0, 2, 2, 0x20)
    val b0 = Entry(1, 0, 3, 3, 0x30)
    val (_, s1) = enqueue(empty(stids, pipes), a0, stids, pipes, depth = 2)
    val (_, s2) = enqueue(s1, a1, stids, pipes, depth = 2)
    val (_, s3) = enqueue(s2, b0, stids, pipes, depth = 2)
    val (d0, s4) = drain(s3)
    val (d1, s5) = drain(s4)
    val (d2, _) = drain(s5)

    assert(d0.contains(a0))
    assert(d1.contains(b0))
    assert(d2.contains(a1))
  }

  test("precise flush compacts only the selected killed suffix") {
    val stids = 2
    val pipes = 1
    val keep0 = Entry(0, 0, 3, 3, 0x30)
    val kill0 = Entry(0, 0, 5, 5, 0x50)
    val keep1 = Entry(1, 0, 7, 7, 0x70)
    val (_, s1) = enqueue(empty(stids, pipes), keep0, stids, pipes, depth = 3)
    val (_, s2) = enqueue(s1, kill0, stids, pipes, depth = 3)
    val (_, s3) = enqueue(s2, keep1, stids, pipes, depth = 3)
    val flushed = preciseFlush(s3, stid = 0, firstKilledBid = 5)

    assert(flushed.lanes(0) == Vector(keep0))
    assert(flushed.lanes(1) == Vector(keep1))
  }

  test("rejects invalid STID and return-pipe targets") {
    val state = empty(stids = 2, pipes = 2)
    val (badStid, _) = enqueue(state, Entry(2, 0, 1, 1, 0), 2, 2, 2)
    val (badPipe, _) = enqueue(state, Entry(0, 2, 1, 1, 0), 2, 2, 2)
    assert(!badStid)
    assert(!badPipe)
  }

  test("Chisel queue bank elaborates scoped queues and fair arbitration") {
    val sv = ChiselStage.emitSystemVerilog(new ScalarLSULoadReturnQueueBank(
      idEntries = 8,
      stidCount = 2,
      returnPipeCount = 2,
      queueDepth = 2
    ))

    assert(sv.contains("module ScalarLSULoadReturnQueue"))
    assert(sv.contains("module ScalarLSULoadReturnQueueBank"))
    assert(sv.contains("io_preciseFlush"))
    assert(sv.contains("io_enqueueStid"))
    assert(sv.contains("io_enqueuePipeIndex"))
    assert(sv.contains("io_drainStid"))
    assert(sv.contains("io_laneCountState"))
    assert(sv.contains("io_precisePruneCount"))
  }
}
