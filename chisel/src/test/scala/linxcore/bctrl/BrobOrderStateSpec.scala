package linxcore.bctrl

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object BrobOrderStateReference {
  final case class Retire(stid: Int, bid: BigInt)

  final class State(val entries: Int, val bidWidth: Int, val stidCount: Int) {
    private val mask = (BigInt(1) << bidWidth) - 1
    private val tail = Array.fill(stidCount)(BigInt(0))
    private val head = Array.fill(stidCount)(BigInt(0))
    private val count = Array.fill(stidCount)(0)
    private val complete = Array.fill(stidCount, entries)(Option.empty[BigInt])
    private var rrStart = 0
    private var pendingRetire = Option.empty[Retire]

    private def slot(bid: BigInt): Int = (bid & (entries - 1)).toInt
    private def add(bid: BigInt, amount: BigInt): BigInt = (bid + amount) & mask
    private def distance(from: BigInt, to: BigInt): BigInt = (to - from) & mask

    def allocCursor(stid: Int): BigInt = tail(stid)
    def commitCursor(stid: Int): BigInt = head(stid)
    def liveCount(stid: Int): Int = count(stid)

    def allocate(stid: Int, bid: BigInt): Boolean = {
      if (bid != tail(stid) || count(stid) == entries) return false
      complete(stid)(slot(bid)) = None
      tail(stid) = add(tail(stid), 1)
      count(stid) += 1
      true
    }

    def markComplete(stid: Int, bid: BigInt): Unit =
      complete(stid)(slot(bid)) = Some(bid)

    def retire(ready: Boolean = true, simultaneousAlloc: Option[Int] = None): Option[Retire] = {
      val result = pendingRetire.orElse {
        (0 until stidCount).map(off => (rrStart + off) % stidCount).find { stid =>
          count(stid) > 0 && complete(stid)(slot(head(stid))).contains(head(stid))
        }.map(stid => Retire(stid, head(stid)))
      }
      if (ready) {
        result.foreach { r =>
          complete(r.stid)(slot(r.bid)) = None
          head(r.stid) = add(head(r.stid), 1)
          count(r.stid) -= 1
          rrStart = (r.stid + 1) % stidCount
        }
        pendingRetire = None
        simultaneousAlloc.foreach(stid => assert(allocate(stid, tail(stid))))
      } else {
        pendingRetire = result
      }
      result
    }

    def recover(stid: Int, transportedPivot: BigInt, inclusive: Boolean): Boolean = {
      val canonicalSlot = slot(transportedPivot)
      val matches = (0 until count(stid)).map(offset => add(head(stid), offset)).filter(slot(_) == canonicalSlot)
      if (matches.size != 1) return false
      val resolvedPivot = matches.head
      val firstKilled = if (inclusive) resolvedPivot else add(resolvedPivot, 1)
      val retained = distance(head(stid), firstKilled)
      tail(stid) = firstKilled
      count(stid) = retained.toInt
      for (idx <- 0 until entries) {
        complete(stid)(idx) match {
          case Some(bid) if distance(head(stid), bid) >= retained => complete(stid)(idx) = None
          case _ =>
        }
      }
      true
    }
  }
}

class BrobOrderStateSpec extends AnyFunSuite {
  import BrobOrderStateReference._

  test("per-STID allocation tails, commit heads, and live counts advance independently") {
    val state = new State(entries = 8, bidWidth = 8, stidCount = 2)
    assert(state.allocate(0, 0))
    assert(state.allocate(0, 1))
    assert(state.allocate(1, 0))
    assert(state.allocCursor(0) == 2 && state.commitCursor(0) == 0 && state.liveCount(0) == 2)
    assert(state.allocCursor(1) == 1 && state.commitCursor(1) == 0 && state.liveCount(1) == 1)
  }

  test("younger completion cannot bypass an incomplete commit head") {
    val state = new State(entries = 8, bidWidth = 8, stidCount = 1)
    assert(state.allocate(0, 0) && state.allocate(0, 1))
    state.markComplete(0, 1)
    assert(state.retire().isEmpty)
    assert(state.commitCursor(0) == 0 && state.liveCount(0) == 2)
  }

  test("completed head remains stable under retire backpressure") {
    val state = new State(entries = 8, bidWidth = 8, stidCount = 2)
    assert(state.allocate(0, 0) && state.allocate(1, 0))
    state.markComplete(0, 0)
    assert(state.retire(ready = false).contains(Retire(0, 0)))
    state.markComplete(1, 0)
    assert(state.retire(ready = false).contains(Retire(0, 0)))
    assert(state.commitCursor(0) == 0 && state.liveCount(0) == 1)
    assert(state.retire(ready = true).contains(Retire(0, 0)))
    assert(state.commitCursor(0) == 1 && state.liveCount(0) == 0)
  }

  test("shared retire port is fair across ready STIDs") {
    val state = new State(entries = 8, bidWidth = 8, stidCount = 2)
    assert(state.allocate(0, 0) && state.allocate(1, 0))
    state.markComplete(0, 0)
    state.markComplete(1, 0)
    assert(state.retire().contains(Retire(0, 0)))
    assert(state.allocate(0, 1))
    state.markComplete(0, 1)
    assert(state.retire().contains(Retire(1, 0)))
  }

  test("inclusive miss-predict recovery truncates from the first killed BID") {
    val state = new State(entries = 8, bidWidth = 8, stidCount = 1)
    (0 until 4).foreach(bid => assert(state.allocate(0, bid)))
    assert(state.recover(0, transportedPivot = 2, inclusive = true))
    assert(state.allocCursor(0) == 2 && state.commitCursor(0) == 0 && state.liveCount(0) == 2)
  }

  test("retained-pivot recovery truncates from the pivot successor") {
    val state = new State(entries = 8, bidWidth = 8, stidCount = 1)
    (0 until 4).foreach(bid => assert(state.allocate(0, bid)))
    assert(state.recover(0, transportedPivot = 1, inclusive = false))
    assert(state.allocCursor(0) == 2 && state.liveCount(0) == 2)
  }

  test("recovery outside the live window is rejected without mutation") {
    val state = new State(entries = 8, bidWidth = 8, stidCount = 1)
    (0 until 3).foreach(bid => assert(state.allocate(0, bid)))
    assert(!state.recover(0, transportedPivot = 7, inclusive = true))
    assert(state.allocCursor(0) == 3 && state.commitCursor(0) == 0 && state.liveCount(0) == 3)
  }

  test("legacy upper BID bits do not override canonical live-window resolution") {
    val state = new State(entries = 8, bidWidth = 8, stidCount = 1)
    (0 until 4).foreach(bid => assert(state.allocate(0, bid)))
    assert(state.recover(0, transportedPivot = 0x82, inclusive = true))
    assert(state.allocCursor(0) == 2 && state.commitCursor(0) == 0 && state.liveCount(0) == 2)
  }

  test("canonical recovery resolves only inside the selected STID window") {
    val state = new State(entries = 8, bidWidth = 8, stidCount = 2)
    (0 until 3).foreach(bid => assert(state.allocate(0, bid)))
    (0 until 2).foreach(bid => assert(state.allocate(1, bid)))
    assert(state.recover(1, transportedPivot = 0x80, inclusive = true))
    assert(state.allocCursor(0) == 3 && state.liveCount(0) == 3)
    assert(state.allocCursor(1) == 0 && state.liveCount(1) == 0)
  }

  test("simultaneous allocation and retirement preserve count while moving both cursors") {
    val state = new State(entries = 8, bidWidth = 8, stidCount = 1)
    assert(state.allocate(0, 0))
    state.markComplete(0, 0)
    assert(state.retire(simultaneousAlloc = Some(0)).contains(Retire(0, 0)))
    assert(state.allocCursor(0) == 2 && state.commitCursor(0) == 1 && state.liveCount(0) == 1)
  }

  test("full BID cursors wrap while the bounded live window remains unambiguous") {
    val state = new State(entries = 8, bidWidth = 4, stidCount = 1)
    (0 until 8).foreach(bid => assert(state.allocate(0, bid)))
    (0 until 8).foreach { bid =>
      state.markComplete(0, bid)
      assert(state.retire().contains(Retire(0, bid)))
    }
    (8 until 16).foreach(bid => assert(state.allocate(0, bid)))
    assert(state.allocCursor(0) == 0 && state.liveCount(0) == 8)
  }

  test("Chisel BrobOrderState elaborates parameterized order and recovery state") {
    val io = new BrobOrderStateIO(entries = 8, bidWidth = 16, stidWidth = 2, stidCount = 2)
    assert(io.recoveryPivotBid.getWidth == 3)
    assert(io.recoveryTransportPointer.getWidth == 16)

    val sv = ChiselStage.emitSystemVerilog(
      new BrobOrderState(entries = 8, bidWidth = 16, stidWidth = 2, stidCount = 2)
    )
    assert(sv.contains("module BrobOrderState"))
    assert(sv.contains("io_allocCursor_1"))
    assert(sv.contains("io_commitCursor_1"))
    assert(sv.contains("io_liveCount_1"))
    assert(sv.contains("io_recoveryWindowValid"))
    assert(sv.contains("io_recoveryResolvedPivotBid"))
    assert(sv.contains("io_recoveryTransportPointerValid"))
    assert(sv.contains("io_recoveryTransportPointer"))
    assert(sv.contains("io_recoveryLegacyPointerMismatch"))
    assert(sv.contains("io_retireFire"))
  }
}
