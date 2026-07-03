package linxcore.rob

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object ROBRowStatusLookupReference {
  sealed trait Status
  case object Free extends Status
  case object Allocated extends Status
  case object Renamed extends Status
  case object Issued extends Status
  case object Completed extends Status
  case object Retired extends Status
  case object Fault extends Status
  case object NeedFlush extends Status

  final case class Id(valid: Boolean = true, wrap: Boolean = false, value: Int = 0)
  final case class Row(valid: Boolean, rid: Id, status: Status)
  final case class Result(
      rowValid: Boolean,
      status: Status,
      needFlush: Boolean,
      blockedByInvalidRid: Boolean,
      blockedByFree: Boolean,
      blockedByStaleRid: Boolean)

  def apply(queryValid: Boolean, queryRid: Id, rows: Seq[Row]): Result = {
    val entry = rows(queryRid.value)
    val occupied = entry.valid && entry.status != Free
    val ridMatch = occupied && entry.rid == queryRid
    val rowValid = queryValid && queryRid.valid && ridMatch
    val status = if (rowValid) entry.status else Free
    Result(
      rowValid = rowValid,
      status = status,
      needFlush = rowValid && status == NeedFlush,
      blockedByInvalidRid = queryValid && !queryRid.valid,
      blockedByFree = queryValid && queryRid.valid && !occupied,
      blockedByStaleRid = queryValid && queryRid.valid && occupied && !ridMatch)
  }
}

class ROBRowStatusLookupSpec extends AnyFunSuite {
  import ROBRowStatusLookupReference._

  private def emptyRows(entries: Int): Seq[Row] =
    Seq.tabulate(entries)(idx => Row(valid = false, rid = Id(value = idx), status = Free))

  test("reference reports a matching non-free ROB row") {
    val rows = emptyRows(8).updated(3, Row(valid = true, rid = Id(value = 3), status = Completed))
    val result = ROBRowStatusLookupReference(queryValid = true, queryRid = Id(value = 3), rows)

    assert(result.rowValid)
    assert(result.status == Completed)
    assert(!result.needFlush)
    assert(!result.blockedByFree)
    assert(!result.blockedByStaleRid)
  }

  test("reference marks model NeedFlush rows without treating them as missing") {
    val rows = emptyRows(8).updated(5, Row(valid = true, rid = Id(wrap = true, value = 5), status = NeedFlush))
    val result = ROBRowStatusLookupReference(queryValid = true, queryRid = Id(wrap = true, value = 5), rows)

    assert(result.rowValid)
    assert(result.needFlush)
    assert(!result.blockedByFree)
  }

  test("reference separates invalid, free, and stale RID blockers") {
    val occupied = emptyRows(8).updated(2, Row(valid = true, rid = Id(wrap = true, value = 2), status = Renamed))

    val invalid = ROBRowStatusLookupReference(queryValid = true, queryRid = Id(valid = false, value = 2), occupied)
    assert(invalid.blockedByInvalidRid)
    assert(!invalid.rowValid)

    val free = ROBRowStatusLookupReference(queryValid = true, queryRid = Id(value = 1), occupied)
    assert(free.blockedByFree)
    assert(!free.rowValid)

    val stale = ROBRowStatusLookupReference(queryValid = true, queryRid = Id(wrap = false, value = 2), occupied)
    assert(stale.blockedByStaleRid)
    assert(!stale.rowValid)
  }

  test("ROBRowStatusLookup elaborates read-only row status diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new ROBRowStatusLookup(entries = 8))

    assert(sv.contains("module ROBRowStatusLookup"))
    assert(sv.contains("io_queryValid"))
    assert(sv.contains("io_result_rowValid"))
    assert(sv.contains("io_result_needFlush"))
    assert(sv.contains("io_result_blockedByStaleRid"))
  }
}
