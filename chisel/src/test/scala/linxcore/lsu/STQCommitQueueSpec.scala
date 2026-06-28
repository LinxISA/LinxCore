package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object STQCommitQueueReference {
  import STQFlushPruneReference.Id

  final case class Entry(stqIndex: Int, bid: Id = Id(), lsId: Id = Id())
  final case class StepResult(
      issued: Seq[Entry],
      enqueueAccepted: Boolean,
      enqueueDuplicate: Boolean,
      enqueueInsertPosition: Option[Int])

  final class Model(depth: Int, issueWidth: Int) {
    require(depth > 1 && (depth & (depth - 1)) == 0)
    require(issueWidth > 0 && issueWidth <= depth)

    private var queue = Vector.empty[Entry]

    def entries: Seq[Entry] = queue
    def count: Int = queue.size
    def full: Boolean = queue.size == depth
    def empty: Boolean = queue.isEmpty

    private def less(lhs: Id, rhs: Id): Boolean =
      if (lhs.wrap == rhs.wrap) lhs.value < rhs.value else lhs.value > rhs.value

    private def lessEqual(lhs: Id, rhs: Id): Boolean =
      less(lhs, rhs) || lhs == rhs

    private def lessEqualBidLs(srcBid: Id, srcLsId: Id, dstBid: Id, dstLsId: Id): Boolean =
      less(srcBid, dstBid) || (srcBid == dstBid && lessEqual(srcLsId, dstLsId))

    def step(enqueue: Option[Entry] = None, readyRows: Set[Int] = Set.empty, issueEnable: Boolean = true): StepResult = {
      val issued =
        if (issueEnable) queue.filter(entry => readyRows.contains(entry.stqIndex)).take(issueWidth)
        else Vector.empty
      val issuedSet = issued.toSet
      queue = queue.filterNot(issuedSet.contains)

      val duplicate = enqueue.exists(entry => queue.exists(_.stqIndex == entry.stqIndex))
      val accepted = enqueue.exists(_ => queue.size < depth) && !duplicate
      val insertPosition = if (accepted) {
        val entry = enqueue.get
        val firstYounger = queue.indexWhere(cur => lessEqualBidLs(entry.bid, entry.lsId, cur.bid, cur.lsId))
        val position = if (firstYounger >= 0) firstYounger else queue.size
        queue = queue.patch(position, Seq(entry), 0)
        Some(position)
      } else {
        None
      }

      StepResult(issued = issued, enqueueAccepted = accepted, enqueueDuplicate = duplicate, enqueueInsertPosition = insertPosition)
    }
  }
}

class STQCommitQueueSpec extends AnyFunSuite {
  import STQCommitQueueReference._
  import STQFlushPruneReference.Id

  private def entry(index: Int, bid: Int, lsId: Int, wrap: Boolean = false): Entry =
    Entry(stqIndex = index, bid = Id(wrap = wrap, value = bid), lsId = Id(value = lsId))

  test("enqueue inserts committed stores in model bid and LSID age order") {
    val queue = new Model(depth = 8, issueWidth = 2)

    assert(queue.step(enqueue = Some(entry(2, bid = 2, lsId = 3))).enqueueInsertPosition.contains(0))
    assert(queue.step(enqueue = Some(entry(0, bid = 1, lsId = 5))).enqueueInsertPosition.contains(0))
    assert(queue.step(enqueue = Some(entry(1, bid = 2, lsId = 1))).enqueueInsertPosition.contains(1))

    assert(queue.entries.map(_.stqIndex) == Seq(0, 1, 2))
  }

  test("enqueue uses ROBID wrap ordering through the same helper convention as the model") {
    val queue = new Model(depth = 8, issueWidth = 2)

    queue.step(enqueue = Some(entry(0, bid = 1, lsId = 0, wrap = false)))
    queue.step(enqueue = Some(entry(1, bid = 7, lsId = 0, wrap = true)))
    queue.step(enqueue = Some(entry(2, bid = 2, lsId = 0, wrap = false)))

    assert(queue.entries.map(_.stqIndex) == Seq(1, 0, 2))
  }

  test("issue scans in queue order, skips stalled downstream rows, and compacts survivors") {
    val queue = new Model(depth = 8, issueWidth = 2)
    for (idx <- 0 until 4) {
      queue.step(enqueue = Some(entry(idx, bid = 1, lsId = idx)))
    }

    val result = queue.step(readyRows = Set(1, 3))

    assert(result.issued.map(_.stqIndex) == Seq(1, 3))
    assert(queue.entries.map(_.stqIndex) == Seq(0, 2))
  }

  test("simultaneous issue frees queue capacity before accepting a younger enqueue") {
    val queue = new Model(depth = 2, issueWidth = 1)
    queue.step(enqueue = Some(entry(0, bid = 1, lsId = 0)))
    queue.step(enqueue = Some(entry(1, bid = 1, lsId = 1)))
    assert(queue.full)

    val result = queue.step(enqueue = Some(entry(2, bid = 1, lsId = 2)), readyRows = Set(0))

    assert(result.issued.map(_.stqIndex) == Seq(0))
    assert(result.enqueueAccepted)
    assert(result.enqueueInsertPosition.contains(1))
    assert(queue.entries.map(_.stqIndex) == Seq(1, 2))
  }

  test("enqueue rejects duplicate live STQ row indices and full queues") {
    val queue = new Model(depth = 2, issueWidth = 1)
    queue.step(enqueue = Some(entry(0, bid = 1, lsId = 0)))
    val duplicate = queue.step(enqueue = Some(entry(0, bid = 1, lsId = 1)))
    queue.step(enqueue = Some(entry(1, bid = 1, lsId = 1)))
    val full = queue.step(enqueue = Some(entry(2, bid = 1, lsId = 2)))

    assert(duplicate.enqueueDuplicate)
    assert(!duplicate.enqueueAccepted)
    assert(!full.enqueueAccepted)
    assert(queue.entries.map(_.stqIndex) == Seq(0, 1))
  }

  test("issueEnable gates drain selection") {
    val queue = new Model(depth = 4, issueWidth = 2)
    queue.step(enqueue = Some(entry(0, bid = 1, lsId = 0)))
    queue.step(enqueue = Some(entry(1, bid = 1, lsId = 1)))

    val disabled = queue.step(readyRows = Set(0, 1), issueEnable = false)

    assert(disabled.issued.isEmpty)
    assert(queue.entries.map(_.stqIndex) == Seq(0, 1))
  }

  test("Chisel STQCommitQueue elaborates with commit-order and issue-selection IO") {
    val sv = ChiselStage.emitSystemVerilog(new STQCommitQueue(robEntries = 8, stqEntries = 8, queueEntries = 8, issueWidth = 2))

    assert(sv.contains("module STQCommitQueue"))
    assert(sv.contains("io_enqueueReady"))
    assert(sv.contains("io_issueValidMask"))
    assert(sv.contains("io_queueCount"))
    assert(sv.contains("io_orderError"))
  }
}
