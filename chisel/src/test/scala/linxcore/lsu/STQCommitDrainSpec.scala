package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object STQCommitDrainReference {
  import STQCommitQueueReference.Entry

  final case class Row(index: Int, addr: BigInt, data: BigInt, size: Int, committed: Boolean = true)
  final case class Request(index: Int, segment: Int, split: Boolean, last: Boolean, addr: BigInt, data: BigInt, size: Int)
  final case class StepResult(
      issued: Seq[Entry],
      requests: Seq[Request],
      freeMask: BigInt,
      enqueueAccepted: Boolean,
      enqueueDuplicate: Boolean,
      queue: Seq[Entry])

  final class Model(depth: Int, issueWidth: Int) {
    private val queue = new STQCommitQueueReference.Model(depth, issueWidth)

    def entries: Seq[Entry] = queue.entries

    def step(
        enqueue: Option[Entry] = None,
        rows: Seq[Row] = Seq.empty,
        primaryReady: Set[Int] = Set.empty,
        secondaryReady: Set[Int] = Set.empty,
        issueEnable: Boolean = true,
        flush: Boolean = false): StepResult = {
      val rowByIndex = rows.map(row => row.index -> row).toMap
      val readyRows = rowByIndex.collect {
        case (idx, row) if row.committed && primaryReady.contains(idx) && (!crosses(row.addr, row.size) || secondaryReady.contains(idx)) => idx
      }.toSet

      val queueResult = queue.step(enqueue = enqueue, readyRows = readyRows, issueEnable = issueEnable, flush = flush)
      val requests = queueResult.issued.flatMap(entry => requestsFor(rowByIndex(entry.stqIndex)))
      val freeMask = queueResult.issued.foldLeft(BigInt(0)) { case (mask, entry) => mask | (BigInt(1) << entry.stqIndex) }

      StepResult(
        issued = queueResult.issued,
        requests = requests,
        freeMask = freeMask,
        enqueueAccepted = queueResult.enqueueAccepted,
        enqueueDuplicate = queueResult.enqueueDuplicate,
        queue = queue.entries
      )
    }
  }

  def crosses(addr: BigInt, size: Int): Boolean =
    ((addr & 0x3f) + size) > 0x40

  def requestsFor(row: Row): Seq[Request] = {
    if (!crosses(row.addr, row.size)) {
      Seq(Request(row.index, segment = 0, split = false, last = true, addr = row.addr, data = row.data, size = row.size))
    } else {
      val firstSize = 0x40 - (row.addr & 0x3f).toInt
      val secondSize = row.size - firstSize
      val allDataBits = (BigInt(1) << 64) - 1
      Seq(
        Request(
          row.index,
          segment = 0,
          split = true,
          last = false,
          addr = row.addr,
          data = row.data & (allDataBits >> (secondSize * 8)),
          size = firstSize
        ),
        Request(
          row.index,
          segment = 1,
          split = true,
          last = true,
          addr = (row.addr & ~BigInt(0x3f)) + 0x40,
          data = row.data >> (firstSize * 8),
          size = secondSize
        )
      )
    }
  }
}

class STQCommitDrainSpec extends AnyFunSuite {
  import STQCommitDrainReference._
  import STQCommitQueueReference.Entry
  import STQFlushPruneReference.Id

  private def entry(index: Int, bid: Int, lsId: Int): Entry =
    Entry(stqIndex = index, bid = Id(value = bid), lsId = Id(value = lsId))

  test("single-line committed store drains to one request and one bank free bit") {
    val drain = new Model(depth = 8, issueWidth = 2)
    val rows = Seq(Row(index = 0, addr = 0x1000, data = BigInt("1122334455667788", 16), size = 8))

    assert(drain.step(enqueue = Some(entry(0, bid = 1, lsId = 0))).enqueueAccepted)
    val result = drain.step(rows = rows, primaryReady = Set(0))

    assert(result.issued.map(_.stqIndex) == Seq(0))
    assert(result.freeMask == BigInt(1))
    assert(result.requests == Seq(Request(0, segment = 0, split = false, last = true, addr = 0x1000, data = BigInt("1122334455667788", 16), size = 8)))
  }

  test("cacheline split store requires both segment targets before free") {
    val drain = new Model(depth = 8, issueWidth = 2)
    val row = Row(index = 3, addr = 0x103e, data = BigInt("1122334455667788", 16), size = 8)

    drain.step(enqueue = Some(entry(3, bid = 1, lsId = 0)))
    val stalled = drain.step(rows = Seq(row), primaryReady = Set(3), secondaryReady = Set.empty)

    assert(stalled.issued.isEmpty)
    assert(stalled.freeMask == BigInt(0))
    assert(stalled.queue.map(_.stqIndex) == Seq(3))

    val drained = drain.step(rows = Seq(row), primaryReady = Set(3), secondaryReady = Set(3))

    assert(drained.issued.map(_.stqIndex) == Seq(3))
    assert(drained.freeMask == (BigInt(1) << 3))
    assert(drained.requests == Seq(
      Request(3, segment = 0, split = true, last = false, addr = 0x103e, data = BigInt("7788", 16), size = 2),
      Request(3, segment = 1, split = true, last = true, addr = 0x1040, data = BigInt("112233445566", 16), size = 6)
    ))
  }

  test("older split-stalled row stays queued while younger ready row drains") {
    val drain = new Model(depth = 8, issueWidth = 2)
    val rows = Seq(
      Row(index = 0, addr = 0x203e, data = BigInt("ffeeddccbbaa9988", 16), size = 8),
      Row(index = 1, addr = 0x2080, data = BigInt("0102030405060708", 16), size = 8)
    )

    drain.step(enqueue = Some(entry(0, bid = 1, lsId = 0)))
    drain.step(enqueue = Some(entry(1, bid = 1, lsId = 1)))
    val result = drain.step(rows = rows, primaryReady = Set(0, 1), secondaryReady = Set.empty)

    assert(result.issued.map(_.stqIndex) == Seq(1))
    assert(result.freeMask == (BigInt(1) << 1))
    assert(result.queue.map(_.stqIndex) == Seq(0))
    assert(result.requests.map(_.addr) == Seq(BigInt(0x2080)))
  }

  test("issueEnable suppresses ready committed rows without dropping queue entries") {
    val drain = new Model(depth = 4, issueWidth = 2)
    val rows = Seq(Row(index = 0, addr = 0x3000, data = 0x55, size = 1))

    drain.step(enqueue = Some(entry(0, bid = 1, lsId = 0)))
    val result = drain.step(rows = rows, primaryReady = Set(0), issueEnable = false)

    assert(result.issued.isEmpty)
    assert(result.freeMask == BigInt(0))
    assert(result.queue.map(_.stqIndex) == Seq(0))
  }

  test("flush clears queued rows before issue or enqueue") {
    val drain = new Model(depth = 4, issueWidth = 2)
    val rows = Seq(Row(index = 0, addr = 0x3000, data = 0x55, size = 1))

    drain.step(enqueue = Some(entry(0, bid = 1, lsId = 0)))
    val result = drain.step(
      enqueue = Some(entry(1, bid = 1, lsId = 1)),
      rows = rows,
      primaryReady = Set(0),
      flush = true)

    assert(result.issued.isEmpty)
    assert(!result.enqueueAccepted)
    assert(result.queue.isEmpty)
  }

  test("Chisel STQCommitDrain elaborates with memory request and bank free boundary IO") {
    val sv = ChiselStage.emitSystemVerilog(new STQCommitDrain(entries = 8, queueEntries = 8, issueWidth = 2))

    assert(sv.contains("module STQCommitDrain"))
    assert(sv.contains("STQCommitQueue"))
    assert(sv.contains("io_flushValid"))
    assert(sv.contains("io_memReqs_0_valid"))
    assert(sv.contains("io_commitFreeMask"))
    assert(sv.contains("io_readyMask"))
  }
}
