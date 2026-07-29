package linxcore.lsu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
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

class STQCommitDrainSpec extends AnyFunSuite with ChiselSim {
  import STQCommitDrainReference._
  import STQCommitQueueReference.Entry
  import STQFlushPruneReference.Id

  private def entry(index: Int, bid: Int, lsId: Int, stid: Int = 0): Entry =
    Entry(
      stqIndex = index,
      bid = Id(value = bid),
      lsId = Id(value = lsId),
      stid = stid)

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

  test("older split-stalled row blocks a younger same-STID row while a peer drains") {
    val drain = new Model(depth = 8, issueWidth = 2)
    val rows = Seq(
      Row(index = 0, addr = 0x203e, data = BigInt("ffeeddccbbaa9988", 16), size = 8),
      Row(index = 1, addr = 0x2080, data = BigInt("0102030405060708", 16), size = 8),
      Row(index = 2, addr = 0x2100, data = BigInt("8877665544332211", 16), size = 8)
    )

    drain.step(enqueue = Some(entry(0, bid = 1, lsId = 0)))
    drain.step(enqueue = Some(entry(1, bid = 1, lsId = 1)))
    drain.step(enqueue = Some(entry(2, bid = 1, lsId = 0, stid = 1)))
    val result = drain.step(
      rows = rows,
      primaryReady = Set(0, 1, 2),
      secondaryReady = Set.empty)

    assert(result.issued.map(_.stqIndex) == Seq(2))
    assert(result.freeMask == (BigInt(1) << 2))
    assert(result.queue.map(_.stqIndex) == Seq(0, 1))
    assert(result.requests.map(_.addr) == Seq(BigInt(0x2100)))
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

  test("architectural abort clears queued rows before issue or enqueue") {
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

  test("physical STQ depth is independent of ROB identity width") {
    val io = new STQCommitDrainIO(
      entries = 16, queueEntries = 16, issueWidth = 2, robEntries = 8, lsidWidth = 40)

    assert(io.rows.length == 16)
    assert(io.memReqs.head.stqIndex.getWidth == 4)
    assert(io.memReqs.head.stid.getWidth == 8)
    assert(io.memReqs.head.bid.value.getWidth == 3)
    assert(io.memReqs.head.gid.value.getWidth == 3)
    assert(io.memReqs.head.rid.value.getWidth == 3)
    assert(io.rows.head.lsIdFull.getWidth == 40)
    assert(io.memReqs.head.lsId.getWidth == 40)

    val sv = ChiselStage.emitSystemVerilog(
      new STQCommitDrain(
        entries = 16, queueEntries = 16, issueWidth = 2, robEntries = 8, lsidWidth = 40))
    assert(sv.contains("module STQCommitDrain"))
    assert(sv.contains("io_memReqs_0_bid_value"))
    assert(sv.contains("io_memReqs_0_gid_value"))
    assert(sv.contains("io_memReqs_0_rid_value"))
  }

  test("drain revalidates the exact STQ lease and gives ownership only to the last fragment") {
    simulate(new STQCommitDrain(
      entries = 4,
      queueEntries = 4,
      issueWidth = 2,
      robEntries = 8,
      stidWidth = 2,
      lsidWidth = 40)) { dut =>
      dut.io.rows.foreach(row => row.poke(0.U.asTypeOf(row)))
      dut.io.enqueueValid.poke(false.B)
      dut.io.enqueueIndex.poke(0.U)
      dut.io.enqueueBid.poke(0.U.asTypeOf(dut.io.enqueueBid))
      dut.io.enqueueLsId.poke(0.U)
      dut.io.flushValid.poke(false.B)
      dut.io.issueEnable.poke(false.B)
      dut.io.primaryReadyMask.poke(0.U)
      dut.io.secondaryReadyMask.poke(0.U)
      dut.clock.step()

      val row = dut.io.rows(1)
      row.valid.poke(true.B)
      row.status.poke(STQEntryStatus.Commit)
      row.storeType.poke(STQStoreType.All)
      row.stid.poke(0.U)
      row.bid.valid.poke(true.B)
      row.bid.value.poke(2.U)
      row.lsIdFull.poke(9.U)
      row.storeIdFullValid.poke(true.B)
      row.storeIdFull.poke(4.U)
      row.leaseGeneration.poke(7.U)
      row.addrReady.poke(true.B)
      row.dataReady.poke(true.B)
      row.addr.poke(0x103e.U)
      row.data.poke(BigInt("1122334455667788", 16).U)
      row.size.poke(8.U)
      row.exactOwner.valid.poke(true.B)
      row.exactOwner.peId.poke(1.U)
      row.exactOwner.stid.poke(0.U)
      row.exactOwner.nativeBidValid.poke(true.B)
      row.exactOwner.nativeBid.poke(6.U)
      row.exactOwner.brobGeneration.poke(2.U)
      row.exactOwner.ridSlot.poke(1.U)
      row.exactOwner.ridGeneration.poke(3.U)
      row.exactOwner.memberIndex.poke(0.U)
      row.exactOwner.residentGeneration.poke(4.U)

      dut.io.enqueueValid.poke(true.B)
      dut.io.enqueueIndex.poke(1.U)
      dut.io.enqueueBid.valid.poke(true.B)
      dut.io.enqueueBid.value.poke(2.U)
      dut.io.enqueueLsId.poke(9.U)
      dut.io.enqueueReady.expect(true.B)
      dut.io.enqueueAccepted.expect(true.B)
      dut.clock.step()
      dut.io.enqueueValid.poke(false.B)

      // A reused or corrupted physical slot cannot satisfy the retained
      // generation-qualified token, even when downstream is ready.
      row.leaseGeneration.poke(8.U)
      dut.io.issueEnable.poke(true.B)
      dut.io.primaryReadyMask.poke("b0010".U)
      dut.io.secondaryReadyMask.poke("b0010".U)
      dut.io.queuedIdentityError.expect(true.B)
      dut.io.issueValidMask.expect(0.U)
      dut.io.commitFreeMaskValid.expect(false.B)

      row.leaseGeneration.poke(7.U)
      dut.io.queuedIdentityError.expect(false.B)
      dut.io.primaryReadyMask.poke(0.U)
      dut.io.secondaryReadyMask.poke(0.U)
      dut.io.issue(0).valid.expect(true.B)
      dut.io.issue(0).leaseGeneration.expect(7.U)
      dut.clock.step()

      dut.io.retainedBatchValid.expect(true.B)
      dut.io.retainedBatchAccepted.expect(false.B)
      dut.io.memReqs(0).valid.expect(true.B)
      dut.io.memReqs(0).last.expect(false.B)
      dut.io.memReqs(0).ownsStqRow.expect(false.B)
      dut.io.memReqs(1).valid.expect(true.B)
      dut.io.memReqs(1).last.expect(true.B)
      dut.io.memReqs(1).ownsStqRow.expect(true.B)
      val firstAddr = dut.io.memReqs(0).addr.peek().litValue
      val firstData = dut.io.memReqs(0).data.peek().litValue
      dut.clock.step(2)
      dut.io.memReqs(0).valid.expect(true.B)
      dut.io.memReqs(0).addr.expect(firstAddr.U)
      dut.io.memReqs(0).data.expect(firstData.U)
      dut.io.commitFreeMaskValid.expect(false.B)

      dut.io.primaryReadyMask.poke("b0010".U)
      dut.io.secondaryReadyMask.poke("b0010".U)
      dut.io.issueEnable.poke(false.B)
      dut.io.memReqs(0).valid.expect(false.B)
      dut.io.retainedBatchAccepted.expect(false.B)
      dut.io.commitFreeMaskValid.expect(false.B)

      dut.io.issueEnable.poke(true.B)
      dut.io.memReqs(0).valid.expect(true.B)
      dut.io.retainedBatchAccepted.expect(true.B)
      dut.io.commitFreeMask.expect("b0010".U)
    }
  }
}
