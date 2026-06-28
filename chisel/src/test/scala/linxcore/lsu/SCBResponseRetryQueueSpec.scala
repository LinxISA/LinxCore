package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object SCBResponseRetryQueueReference {
  final case class Step(
      pushReady: Boolean,
      pushAccepted: Boolean,
      headValid: Boolean,
      headEntryIndex: Option[Int],
      headConsumed: Boolean,
      full: Boolean,
      empty: Boolean,
      count: Int)

  final class Model(depth: Int) {
    private var queue = Vector.empty[Int]

    def step(push: Option[Int], popReady: Boolean): Step = {
      val oldHead = queue.headOption
      val headValid = oldHead.isDefined
      val headConsumed = headValid && popReady
      val pushReady = queue.size < depth || headConsumed
      val pushAccepted = push.isDefined && pushReady
      var next = if (headConsumed) queue.drop(1) else queue
      if (pushAccepted) {
        next = next :+ push.get
      }
      queue = next
      Step(
        pushReady = pushReady,
        pushAccepted = pushAccepted,
        headValid = headValid,
        headEntryIndex = oldHead,
        headConsumed = headConsumed,
        full = queue.size == depth,
        empty = queue.isEmpty,
        count = queue.size)
    }
  }
}

class SCBResponseRetryQueueSpec extends AnyFunSuite {
  import SCBResponseRetryQueueReference._

  test("stores response-returned row ids in FIFO order") {
    val model = new Model(depth = 2)

    val first = model.step(Some(3), popReady = false)
    assert(first.pushAccepted)
    assert(!first.headValid)
    assert(first.count == 1)

    val second = model.step(Some(1), popReady = false)
    assert(second.headEntryIndex.contains(3))
    assert(second.full)
    assert(second.count == 2)

    val popFirst = model.step(None, popReady = true)
    assert(popFirst.headConsumed)
    assert(popFirst.headEntryIndex.contains(3))
    assert(popFirst.count == 1)

    val popSecond = model.step(None, popReady = true)
    assert(popSecond.headConsumed)
    assert(popSecond.headEntryIndex.contains(1))
    assert(popSecond.empty)
  }

  test("simultaneous pop opens retry enqueue space") {
    val model = new Model(depth = 1)

    assert(model.step(Some(0), popReady = false).pushAccepted)
    val simultaneous = model.step(Some(2), popReady = true)

    assert(simultaneous.pushReady)
    assert(simultaneous.pushAccepted)
    assert(simultaneous.headConsumed)
    assert(simultaneous.headEntryIndex.contains(0))
    assert(simultaneous.full)

    val next = model.step(None, popReady = false)
    assert(next.headEntryIndex.contains(2))
  }

  test("backpressures legal response pushes when full and not popped") {
    val model = new Model(depth = 1)

    assert(model.step(Some(1), popReady = false).pushAccepted)
    val held = model.step(Some(3), popReady = false)

    assert(!held.pushReady)
    assert(!held.pushAccepted)
    assert(held.headEntryIndex.contains(1))
    assert(held.full)
  }

  test("Chisel SCBResponseRetryQueue elaborates with head and occupancy signals") {
    val sv = ChiselStage.emitSystemVerilog(new SCBResponseRetryQueue(scbEntries = 4, depth = 4))

    assert(sv.contains("module SCBResponseRetryQueue"))
    assert(sv.contains("io_pushReady"))
    assert(sv.contains("io_headEntryIndex"))
    assert(sv.contains("io_headConsumed"))
    assert(sv.contains("io_count"))
  }
}
