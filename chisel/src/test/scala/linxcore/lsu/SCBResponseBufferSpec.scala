package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object SCBResponseBufferReference {
  final case class Raw(txnId: Int, write: Boolean, upgrade: Boolean)
  final case class Step(
      rawReady: Boolean,
      rawAccepted: Boolean,
      headValid: Boolean,
      head: Option[Raw],
      headConsumed: Boolean,
      full: Boolean,
      empty: Boolean,
      count: Int)

  final class Model(depth: Int) {
    private var queue = Vector.empty[Raw]

    def step(raw: Option[Raw], headReady: Boolean): Step = {
      val headValid = queue.nonEmpty
      val headConsumed = headValid && headReady
      val rawReady = queue.size < depth || headConsumed
      val rawAccepted = raw.isDefined && rawReady
      val oldHead = queue.headOption
      var next = if (headConsumed) queue.drop(1) else queue
      if (rawAccepted) {
        next = next :+ raw.get
      }
      queue = next
      Step(
        rawReady = rawReady,
        rawAccepted = rawAccepted,
        headValid = headValid,
        head = oldHead,
        headConsumed = headConsumed,
        full = queue.size == depth,
        empty = queue.isEmpty,
        count = queue.size)
    }
  }
}

class SCBResponseBufferSpec extends AnyFunSuite {
  import SCBResponseBufferReference._

  private def txn(entry: Int): Int = (entry << 2) | 2

  test("buffers raw SCB responses in FIFO order and exposes only the head") {
    val model = new Model(depth = 2)

    val first = model.step(Some(Raw(txn(1), write = true, upgrade = false)), headReady = false)
    assert(first.rawAccepted)
    assert(!first.headValid)
    assert(first.count == 1)

    val second = model.step(Some(Raw(txn(2), write = false, upgrade = true)), headReady = false)
    assert(second.head.contains(Raw(txn(1), write = true, upgrade = false)))
    assert(second.full)
    assert(second.count == 2)

    val popFirst = model.step(None, headReady = true)
    assert(popFirst.headConsumed)
    assert(popFirst.head.contains(Raw(txn(1), write = true, upgrade = false)))
    assert(popFirst.count == 1)

    val popSecond = model.step(None, headReady = true)
    assert(popSecond.headConsumed)
    assert(popSecond.head.contains(Raw(txn(2), write = false, upgrade = true)))
    assert(popSecond.empty)
  }

  test("uses legal-head consumption to create backpressure-compatible enqueue space") {
    val model = new Model(depth = 1)

    assert(model.step(Some(Raw(txn(0), write = true, upgrade = false)), headReady = false).rawAccepted)
    val simultaneous = model.step(Some(Raw(txn(3), write = false, upgrade = true)), headReady = true)

    assert(simultaneous.rawReady)
    assert(simultaneous.rawAccepted)
    assert(simultaneous.headConsumed)
    assert(simultaneous.head.contains(Raw(txn(0), write = true, upgrade = false)))
    assert(simultaneous.full)

    val next = model.step(None, headReady = false)
    assert(next.head.contains(Raw(txn(3), write = false, upgrade = true)))
  }

  test("retains an illegal or stale head until the decode owner consumes it") {
    val model = new Model(depth = 2)

    assert(model.step(Some(Raw(txn(0), write = true, upgrade = true)), headReady = false).rawAccepted)
    val held = model.step(Some(Raw(txn(1), write = true, upgrade = false)), headReady = false)
    assert(held.head.contains(Raw(txn(0), write = true, upgrade = true)))
    assert(!held.headConsumed)
    assert(held.full)

    val stillHeld = model.step(None, headReady = false)
    assert(stillHeld.head.contains(Raw(txn(0), write = true, upgrade = true)))
    assert(stillHeld.count == 2)
  }

  test("Chisel SCBResponseBuffer elaborates with FIFO response observability") {
    val sv = ChiselStage.emitSystemVerilog(new SCBResponseBuffer(scbEntries = 6, depth = 3))

    assert(sv.contains("module SCBResponseBuffer"))
    assert(sv.contains("io_rawReady"))
    assert(sv.contains("io_headTxnId"))
    assert(sv.contains("io_headConsumed"))
    assert(sv.contains("io_count"))
  }
}
