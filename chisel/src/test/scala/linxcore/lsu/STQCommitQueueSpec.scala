package linxcore.lsu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object STQCommitQueueReference {
  import STQFlushPruneReference.Id

  final case class Entry(
      stqIndex: Int,
      bid: Id = Id(),
      lsId: Id = Id(),
      stid: Int = 0,
      storeId: Option[Id] = None,
      leaseGeneration: Int = 1) {
    def fullStoreId: Id = storeId.getOrElse(lsId)
  }
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

    def step(
        enqueue: Option[Entry] = None,
        readyRows: Set[Int] = Set.empty,
        issueEnable: Boolean = true,
        flush: Boolean = false): StepResult = {
      if (flush) {
        queue = Vector.empty
        return StepResult(
          issued = Vector.empty,
          enqueueAccepted = false,
          enqueueDuplicate = false,
          enqueueInsertPosition = None)
      }

      val frontier = queue.filter { candidate =>
        !queue.exists { other =>
          other.stid == candidate.stid &&
            less(other.fullStoreId, candidate.fullStoreId)
        }
      }
      val issued = if (issueEnable) {
        frontier.filter(entry => readyRows.contains(entry.stqIndex))
          .take(issueWidth)
      } else Vector.empty
      val issuedSet = issued.toSet
      queue = queue.filterNot(issuedSet.contains)

      val duplicate = enqueue.exists(entry =>
        queue.exists(current => current.stqIndex == entry.stqIndex ||
          (current.stid == entry.stid &&
            current.fullStoreId == entry.fullStoreId)))
      val accepted = enqueue.exists(_ => queue.size < depth) && !duplicate
      val insertPosition = if (accepted) {
        val position = queue.size
        queue :+= enqueue.get
        Some(position)
      } else None

      StepResult(issued, accepted, duplicate, insertPosition)
    }
  }
}

class STQCommitQueueSpec extends AnyFunSuite with ChiselSim {
  import STQCommitQueueReference._
  import STQFlushPruneReference.Id

  private def entry(
      index: Int,
      bid: Int,
      lsId: Int,
      stid: Int = 0,
      storeId: Int = -1,
      wrap: Boolean = false): Entry =
    Entry(
      stqIndex = index,
      bid = Id(wrap = wrap, value = bid),
      lsId = Id(wrap = wrap, value = lsId),
      stid = stid,
      storeId = Some(Id(wrap = wrap,
        value = if (storeId >= 0) storeId else lsId)))

  private def clearInputs(dut: STQCommitQueue): Unit = {
    dut.io.enqueueValid.poke(false.B)
    dut.io.enqueueIndex.poke(0.U)
    dut.io.enqueueLeaseGeneration.poke(0.U)
    dut.io.enqueueStid.poke(0.U)
    dut.io.enqueueBid.poke(0.U.asTypeOf(dut.io.enqueueBid))
    dut.io.enqueueLsId.poke(0.U)
    dut.io.enqueueStoreIdValid.poke(false.B)
    dut.io.enqueueStoreId.poke(0.U)
    dut.io.enqueueExactOwner.poke(0.U.asTypeOf(dut.io.enqueueExactOwner))
    dut.io.flushValid.poke(false.B)
    dut.io.issueEnable.poke(false.B)
    dut.io.readyMask.poke(0.U)
  }

  private def pokeEnqueue(
      dut: STQCommitQueue,
      index: Int,
      stid: Int,
      lsid: BigInt,
      storeId: BigInt,
      generation: Int = 1): Unit = {
    dut.io.enqueueValid.poke(true.B)
    dut.io.enqueueIndex.poke(index.U)
    dut.io.enqueueLeaseGeneration.poke(generation.U)
    dut.io.enqueueStid.poke(stid.U)
    dut.io.enqueueBid.valid.poke(true.B)
    dut.io.enqueueBid.wrap.poke(false.B)
    dut.io.enqueueBid.value.poke((index + 1).U)
    dut.io.enqueueLsId.poke(lsid.U)
    dut.io.enqueueStoreIdValid.poke(true.B)
    dut.io.enqueueStoreId.poke(storeId.U)
    val owner = dut.io.enqueueExactOwner
    owner.valid.poke(true.B)
    owner.peId.poke(1.U)
    owner.stid.poke(stid.U)
    owner.nativeBidValid.poke(true.B)
    owner.nativeBid.poke((index + 4).U)
    owner.brobGeneration.poke(2.U)
    owner.ridSlot.poke(index.U)
    owner.ridGeneration.poke(3.U)
    owner.memberIndex.poke(0.U)
    owner.residentGeneration.poke(4.U)
  }

  private def enqueue(
      dut: STQCommitQueue,
      index: Int,
      stid: Int,
      lsid: BigInt,
      storeId: BigInt): Unit = {
    pokeEnqueue(dut, index, stid, lsid, storeId)
    dut.io.enqueueReady.expect(true.B)
    dut.io.enqueueAccepted.expect(true.B)
    dut.clock.step()
    clearInputs(dut)
  }

  test("model blocks a ready younger same-STID store behind an older stall") {
    val queue = new Model(depth = 8, issueWidth = 2)
    queue.step(enqueue = Some(entry(0, bid = 1, lsId = 10, stid = 0)))
    queue.step(enqueue = Some(entry(1, bid = 1, lsId = 11, stid = 0)))
    queue.step(enqueue = Some(entry(2, bid = 1, lsId = 2, stid = 1)))

    val first = queue.step(readyRows = Set(1, 2))
    assert(first.issued.map(_.stqIndex) == Seq(2))
    assert(queue.entries.map(_.stqIndex) == Seq(0, 1))

    val second = queue.step(readyRows = Set(1))
    assert(second.issued.isEmpty)
    assert(queue.entries.map(_.stqIndex) == Seq(0, 1))
  }

  test("model uses wrap-safe full store IDs rather than queue positions") {
    val queue = new Model(depth = 8, issueWidth = 2)
    queue.step(enqueue = Some(entry(0, bid = 1, lsId = 1,
      storeId = 0, wrap = false)))
    queue.step(enqueue = Some(entry(1, bid = 1, lsId = 7,
      storeId = 7, wrap = true)))

    val issued = queue.step(readyRows = Set(0, 1))
    assert(issued.issued.map(_.stqIndex) == Seq(1))
  }

  test("issueEnable and architectural abort preserve explicit ownership") {
    val queue = new Model(depth = 4, issueWidth = 2)
    queue.step(enqueue = Some(entry(0, bid = 1, lsId = 0)))
    assert(queue.step(readyRows = Set(0), issueEnable = false).issued.isEmpty)
    val aborted = queue.step(
      enqueue = Some(entry(1, bid = 1, lsId = 1)),
      readyRows = Set(0),
      flush = true)
    assert(aborted.issued.isEmpty)
    assert(!aborted.enqueueAccepted)
    assert(queue.empty)
  }

  test("exact queue admits peer STIDs but never a younger same-STID token") {
    simulate(new STQCommitQueue(
      robEntries = 8,
      stqEntries = 8,
      queueEntries = 8,
      issueWidth = 2,
      lsidWidth = 40,
      stidWidth = 2)) { dut =>
      clearInputs(dut)
      dut.clock.step()
      enqueue(dut, index = 0, stid = 0, lsid = 10, storeId = 5)
      enqueue(dut, index = 1, stid = 0, lsid = 11, storeId = 6)
      enqueue(dut, index = 2, stid = 1, lsid = 2, storeId = 1)

      dut.io.issueEnable.poke(true.B)
      // Queue slots 1 and 2 are ready. Slot 1 is younger in STID0; slot 2 is
      // the independent STID1 frontier and may bypass.
      dut.io.readyMask.poke("b00000110".U)
      dut.io.issue(0).valid.expect(true.B)
      dut.io.issue(0).stqIndex.expect(2.U)
      dut.io.issue(1).valid.expect(false.B)
      dut.clock.step()

      // Peer removal compacts STID0 to slots 0/1. The younger token remains
      // blocked while only slot 1 is ready.
      dut.io.readyMask.poke("b00000010".U)
      dut.io.issueValidMask.expect(0.U)

      dut.io.readyMask.poke("b00000011".U)
      dut.io.issue(0).valid.expect(true.B)
      dut.io.issue(0).stqIndex.expect(0.U)
      dut.io.issue(1).valid.expect(false.B)
      dut.clock.step()

      dut.io.readyMask.poke(1.U)
      dut.io.issue(0).valid.expect(true.B)
      dut.io.issue(0).stqIndex.expect(1.U)
      dut.io.orderError.expect(false.B)
    }
  }

  test("malformed and duplicate exact tokens fail closed") {
    simulate(new STQCommitQueue(
      robEntries = 8,
      stqEntries = 8,
      queueEntries = 8,
      issueWidth = 2,
      lsidWidth = 40,
      stidWidth = 2)) { dut =>
      clearInputs(dut)
      dut.clock.step()

      pokeEnqueue(dut, index = 0, stid = 0, lsid = 8, storeId = 3)
      dut.io.enqueueExactOwner.valid.poke(false.B)
      dut.io.enqueueReady.expect(false.B)
      dut.io.enqueueMalformed.expect(true.B)
      dut.clock.step()
      clearInputs(dut)

      enqueue(dut, index = 0, stid = 0, lsid = 8, storeId = 3)
      pokeEnqueue(dut, index = 1, stid = 0, lsid = 9, storeId = 3)
      dut.io.enqueueReady.expect(false.B)
      dut.io.enqueueDuplicate.expect(true.B)
      dut.io.queueCount.expect(1.U)
    }
  }

  test("Chisel STQCommitQueue elaborates with exact token IO") {
    val sv = ChiselStage.emitSystemVerilog(new STQCommitQueue(
      robEntries = 8,
      stqEntries = 8,
      queueEntries = 8,
      issueWidth = 2))

    assert(sv.contains("module STQCommitQueue"))
    assert(sv.contains("io_enqueueLeaseGeneration"))
    assert(sv.contains("io_enqueueStoreId"))
    assert(sv.contains("io_enqueueExactOwner"))
    assert(sv.contains("io_enqueueMalformed"))
    assert(sv.contains("io_issueValidMask"))
    assert(sv.contains("io_orderError"))
  }

  test("commit queue keeps semantic serials independent of physical capacities") {
    val io = new STQCommitQueueIO(
      robEntries = 8,
      stqEntries = 16,
      queueEntries = 32,
      issueWidth = 4,
      lsidWidth = 40)

    assert(io.enqueueBid.value.getWidth == 3)
    assert(io.enqueueIndex.getWidth == 4)
    assert(io.enqueueLsId.getWidth == 40)
    assert(io.enqueueStoreId.getWidth == 40)
    assert(io.queued.head.lsId.getWidth == 40)
    assert(io.queued.head.storeId.getWidth == 40)
    assert(io.issue.head.leaseGeneration.getWidth == 8)
  }
}
