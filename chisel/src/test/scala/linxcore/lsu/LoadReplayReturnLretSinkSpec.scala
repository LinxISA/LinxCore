package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnLretSinkReference {
  final case class Entry(id: Int, data: BigInt, pipeIndex: Int = 0, valid: Boolean = true)

  final case class StepResult(
      enqueueReady: Boolean,
      enqueueAccepted: Boolean,
      enqueueDropped: Boolean,
      drainValid: Boolean,
      drain: Option[Entry],
      drainFire: Boolean,
      pending: Boolean,
      full: Boolean,
      empty: Boolean,
      count: Int,
      blockedByFlush: Boolean,
      blockedByNoPayload: Boolean,
      blockedByFull: Boolean,
      blockedByDrain: Boolean)

  final class Model(depth: Int) {
    require(depth > 0)
    private var queue = Vector.empty[Entry]

    def step(
        enqueueValid: Boolean = false,
        enqueue: Option[Entry] = None,
        drainReady: Boolean = false,
        flush: Boolean = false): StepResult = {
      val inputValid = enqueueValid && enqueue.exists(_.valid)
      val drainValid = queue.nonEmpty && !flush
      val drain = if (drainValid) Some(queue.head) else None
      val drainFire = drainValid && drainReady
      val enqueueReady = !flush && (queue.size < depth || drainFire)
      val enqueueAccepted = inputValid && enqueueReady
      val enqueueDropped = inputValid && !flush && !enqueueReady

      val result = StepResult(
        enqueueReady = enqueueReady,
        enqueueAccepted = enqueueAccepted,
        enqueueDropped = enqueueDropped,
        drainValid = drainValid,
        drain = drain,
        drainFire = drainFire,
        pending = queue.nonEmpty,
        full = queue.size == depth,
        empty = queue.isEmpty,
        count = queue.size,
        blockedByFlush = flush && enqueueValid,
        blockedByNoPayload = enqueueValid && enqueue.forall(!_.valid),
        blockedByFull = inputValid && !flush && !enqueueReady,
        blockedByDrain = drainValid && !drainReady)

      if (flush) {
        queue = Vector.empty
      } else {
        if (drainFire) {
          queue = queue.tail
        }
        if (enqueueAccepted) {
          queue = queue :+ enqueue.get.copy(valid = true)
        }
      }

      result
    }

    def snapshot: Vector[Entry] = queue
  }
}

class LoadReplayReturnLretSinkSpec extends AnyFunSuite {
  import LoadReplayReturnLretSinkReference._

  test("accepts valid LRET entries and drains them in FIFO order") {
    val model = new Model(depth = 2)

    val first = model.step(enqueueValid = true, enqueue = Some(Entry(id = 1, data = 0x1001)))
    assert(first.enqueueReady)
    assert(first.enqueueAccepted)
    assert(!first.drainValid)
    assert(model.snapshot.map(_.id) == Vector(1))

    val second = model.step(enqueueValid = true, enqueue = Some(Entry(id = 2, data = 0x2002)))
    assert(second.enqueueAccepted)
    assert(second.drainValid)
    assert(second.drain.contains(Entry(id = 1, data = 0x1001)))
    assert(model.snapshot.map(_.id) == Vector(1, 2))

    val drainFirst = model.step(drainReady = true)
    assert(drainFirst.drainFire)
    assert(drainFirst.drain.exists(_.id == 1))
    assert(model.snapshot.map(_.id) == Vector(2))

    val drainSecond = model.step(drainReady = true)
    assert(drainSecond.drainFire)
    assert(drainSecond.drain.exists(_.id == 2))
    assert(model.snapshot.isEmpty)
  }

  test("reports full stalls but accepts enqueue when same-cycle drain opens space") {
    val model = new Model(depth = 1)

    assert(model.step(enqueueValid = true, enqueue = Some(Entry(id = 1, data = 0x11))).enqueueAccepted)

    val full = model.step(enqueueValid = true, enqueue = Some(Entry(id = 2, data = 0x22)))
    assert(full.full)
    assert(full.enqueueDropped)
    assert(full.blockedByFull)
    assert(!full.enqueueAccepted)
    assert(model.snapshot.map(_.id) == Vector(1))

    val replace = model.step(
      enqueueValid = true,
      enqueue = Some(Entry(id = 3, data = 0x33)),
      drainReady = true)
    assert(replace.drainFire)
    assert(replace.drain.exists(_.id == 1))
    assert(replace.enqueueAccepted)
    assert(!replace.enqueueDropped)
    assert(model.snapshot.map(_.id) == Vector(3))
  }

  test("flush clears resident entries and suppresses same-cycle enqueue") {
    val model = new Model(depth = 2)
    assert(model.step(enqueueValid = true, enqueue = Some(Entry(id = 1, data = 0x10))).enqueueAccepted)

    val flushed = model.step(
      enqueueValid = true,
      enqueue = Some(Entry(id = 2, data = 0x20)),
      drainReady = true,
      flush = true)

    assert(!flushed.enqueueReady)
    assert(!flushed.enqueueAccepted)
    assert(!flushed.drainValid)
    assert(flushed.blockedByFlush)
    assert(model.snapshot.isEmpty)
  }

  test("valid request without payload is diagnosed but not enqueued") {
    val model = new Model(depth = 2)

    val result = model.step(enqueueValid = true, enqueue = Some(Entry(id = 1, data = 0x55, valid = false)))

    assert(result.enqueueReady)
    assert(result.blockedByNoPayload)
    assert(!result.enqueueAccepted)
    assert(!result.enqueueDropped)
    assert(model.snapshot.isEmpty)
  }

  test("non-drained head reports drain backpressure") {
    val model = new Model(depth = 2)
    assert(model.step(enqueueValid = true, enqueue = Some(Entry(id = 1, data = 0xaa))).enqueueAccepted)

    val blocked = model.step()

    assert(blocked.drainValid)
    assert(!blocked.drainFire)
    assert(blocked.blockedByDrain)
    assert(model.snapshot.map(_.id) == Vector(1))
  }

  test("Chisel LoadReplayReturnLretSink elaborates FIFO diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnLretSink(
      idEntries = 8,
      depth = 2,
      returnPipeCount = 2
    ))

    assert(sv.contains("module LoadReplayReturnLretSink"))
    assert(sv.contains("io_enqueueValid"))
    assert(sv.contains("io_enqueueReady"))
    assert(sv.contains("io_enqueueAccepted"))
    assert(sv.contains("io_drainValid"))
    assert(sv.contains("io_drainFire"))
    assert(sv.contains("io_pending"))
    assert(sv.contains("io_blockedByFull"))
    assert(sv.contains("io_blockedByDrain"))
  }
}
