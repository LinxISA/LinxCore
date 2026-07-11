package linxcore.bctrl

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object BrobStoreRangeReference {
  final case class Row(bid: Int, count: Option[Int])
  final case class Assignment(bid: Int, start: Int, count: Option[Int])
  final case class Result(assignments: Seq[Assignment], cursor: Int, nextStoreId: Int)

  def scan(cursor: Int, nextStoreId: Int, rows: Seq[Row], bidMask: Int): Result = {
    var open = true
    var next = nextStoreId
    var advanced = 0
    val assignments = rows.zipWithIndex.flatMap { case (row, offset) =>
      val expected = (cursor + offset) & bidMask
      if (!open || row.bid != expected) {
        open = false
        None
      } else {
        val assignment = Assignment(row.bid, next, row.count)
        row.count match {
          case Some(count) =>
            next += count
            advanced += 1
          case None => open = false
        }
        Some(assignment)
      }
    }
    Result(assignments, (cursor + advanced) & bidMask, next)
  }
}

class BrobStoreRangeStateSpec extends AnyFunSuite {
  import BrobStoreRangeReference._

  test("reference assigns an uncertain cursor start and blocks younger ranges") {
    val result = scan(
      cursor = 4,
      nextStoreId = 10,
      rows = Seq(Row(4, None), Row(5, Some(3))),
      bidMask = 15)

    assert(result.assignments == Seq(Assignment(4, 10, None)))
    assert(result.cursor == 4)
    assert(result.nextStoreId == 10)
  }

  test("reference assigns consecutive known ranges including zero-count blocks") {
    val result = scan(
      cursor = 6,
      nextStoreId = 20,
      rows = Seq(Row(6, Some(2)), Row(7, Some(0)), Row(8, Some(4))),
      bidMask = 15)

    assert(result.assignments.map(_.start) == Seq(20, 22, 22))
    assert(result.cursor == 9)
    assert(result.nextStoreId == 26)
  }

  test("reference range assignment crosses BID rollover without unsigned age") {
    val result = scan(
      cursor = 14,
      nextStoreId = 0,
      rows = Seq(Row(14, Some(1)), Row(15, Some(2)), Row(0, Some(3))),
      bidMask = 15)

    assert(result.assignments.map(row => (row.bid, row.start)) ==
      Seq((14, 0), (15, 1), (0, 3)))
    assert(result.cursor == 1)
    assert(result.nextStoreId == 6)
  }

  test("BrobStoreRangeState elaborates parameterized per-STID range ownership") {
    val sv = ChiselStage.emitSystemVerilog(new BrobStoreRangeState(
      entries = 8,
      bidWidth = 16,
      stidWidth = 2,
      stidCount = 2,
      storeIdWidth = 32,
      storeCountWidth = 16))

    assert(sv.contains("module BrobStoreRangeState"))
    assert(sv.contains("io_rangeCursorBid_1"))
    assert(sv.contains("io_nextStoreId_1"))
    assert(sv.contains("io_countCertainUseValue"))
    assert(sv.contains("io_recoveryRewound"))
    assert(sv.contains("io_recoveryMissingStart"))
  }
}
