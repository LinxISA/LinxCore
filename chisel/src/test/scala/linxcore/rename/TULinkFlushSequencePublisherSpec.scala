package linxcore.rename

import circt.stage.ChiselStage
import linxcore.common.{DestinationKind, InterfaceParams}
import linxcore.rob.{ROBIDReference, ROBIDValue}
import org.scalatest.funsuite.AnyFunSuite

object TULinkFlushSequencePublisherReference {
  sealed trait Dst
  case object NoDst extends Dst
  case object TDst extends Dst
  case object UDst extends Dst

  final case class Source(
      valid: Boolean,
      bid: ROBIDValue,
      rid: ROBIDValue,
      stid: Int,
      tSeq: ROBIDValue,
      uSeq: ROBIDValue,
      dst: Dst)

  final case class Result(
      flushValid: Boolean,
      tSeq: ROBIDValue,
      uSeq: ROBIDValue,
      sourceRequired: Boolean,
      sourceMatched: Boolean,
      missingSource: Boolean,
      sourceMismatch: Boolean,
      tPrevApplied: Boolean,
      uPrevApplied: Boolean)

  def publish(
      cleanupValid: Boolean,
      backendFlushValid: Boolean,
      baseOnBid: Boolean,
      bid: ROBIDValue,
      rid: ROBIDValue,
      stid: Int,
      source: Source,
      mapQDepth: Int): Result = {
    val active = cleanupValid && backendFlushValid
    val required = active && !baseOnBid
    val matched =
      source.valid &&
        source.bid == bid &&
        source.rid == rid &&
        source.stid == stid
    val usable = !required || matched
    val tPrev = active && usable && source.valid && source.dst == TDst
    val uPrev = active && usable && source.valid && source.dst == UDst
    val zero = ROBIDValue()
    val sourceT = if (source.valid) source.tSeq else zero
    val sourceU = if (source.valid) source.uSeq else zero

    Result(
      flushValid = active && usable,
      tSeq = if (tPrev) ROBIDReference.sub(sourceT, 1, mapQDepth) else sourceT,
      uSeq = if (uPrev) ROBIDReference.sub(sourceU, 1, mapQDepth) else sourceU,
      sourceRequired = required,
      sourceMatched = matched,
      missingSource = required && !source.valid,
      sourceMismatch = required && source.valid && !matched,
      tPrevApplied = tPrev,
      uPrevApplied = uPrev
    )
  }
}

class TULinkFlushSequencePublisherSpec extends AnyFunSuite {
  import TULinkFlushSequencePublisherReference._

  private val bid2 = ROBIDValue(value = 2)
  private val rid3 = ROBIDValue(value = 3)
  private val source = Source(
    valid = true,
    bid = bid2,
    rid = rid3,
    stid = 0,
    tSeq = ROBIDValue(value = 5),
    uSeq = ROBIDValue(value = 6),
    dst = NoDst
  )

  test("non-base cleanup publishes the selected row's T/U sequences when the row does not own a T/U destination") {
    val result = publish(
      cleanupValid = true,
      backendFlushValid = true,
      baseOnBid = false,
      bid = bid2,
      rid = rid3,
      stid = 0,
      source = source,
      mapQDepth = 8)

    assert(result.flushValid)
    assert(result.sourceRequired)
    assert(result.sourceMatched)
    assert(!result.missingSource)
    assert(!result.sourceMismatch)
    assert(result.tSeq == ROBIDValue(value = 5))
    assert(result.uSeq == ROBIDValue(value = 6))
    assert(!result.tPrevApplied)
    assert(!result.uPrevApplied)
  }

  test("the flushed T destination uses the previous local T sequence and leaves U unchanged") {
    val result = publish(
      cleanupValid = true,
      backendFlushValid = true,
      baseOnBid = false,
      bid = bid2,
      rid = rid3,
      stid = 0,
      source = source.copy(dst = TDst, tSeq = ROBIDValue(value = 0)),
      mapQDepth = 8)

    assert(result.flushValid)
    assert(result.tSeq == ROBIDValue(wrap = true, value = 7))
    assert(result.uSeq == ROBIDValue(value = 6))
    assert(result.tPrevApplied)
    assert(!result.uPrevApplied)
  }

  test("the flushed U destination uses the previous local U sequence and leaves T unchanged") {
    val result = publish(
      cleanupValid = true,
      backendFlushValid = true,
      baseOnBid = false,
      bid = bid2,
      rid = rid3,
      stid = 0,
      source = source.copy(dst = UDst),
      mapQDepth = 8)

    assert(result.flushValid)
    assert(result.tSeq == ROBIDValue(value = 5))
    assert(result.uSeq == ROBIDValue(value = 5))
    assert(!result.tPrevApplied)
    assert(result.uPrevApplied)
  }

  test("base-on-BID cleanup does not require a selected row sequence source") {
    val result = publish(
      cleanupValid = true,
      backendFlushValid = true,
      baseOnBid = true,
      bid = bid2,
      rid = rid3,
      stid = 0,
      source = source.copy(valid = false),
      mapQDepth = 8)

    assert(result.flushValid)
    assert(!result.sourceRequired)
    assert(!result.sourceMatched)
    assert(!result.missingSource)
    assert(!result.sourceMismatch)
    assert(result.tSeq == ROBIDValue())
    assert(result.uSeq == ROBIDValue())
  }

  test("non-base cleanup reports missing or mismatched sequence sources and suppresses the command") {
    val missing = publish(
      cleanupValid = true,
      backendFlushValid = true,
      baseOnBid = false,
      bid = bid2,
      rid = rid3,
      stid = 0,
      source = source.copy(valid = false),
      mapQDepth = 8)
    assert(!missing.flushValid)
    assert(missing.missingSource)

    val mismatch = publish(
      cleanupValid = true,
      backendFlushValid = true,
      baseOnBid = false,
      bid = bid2,
      rid = rid3,
      stid = 0,
      source = source.copy(rid = ROBIDValue(value = 4)),
      mapQDepth = 8)
    assert(!mismatch.flushValid)
    assert(mismatch.sourceMismatch)
  }

  test("IO exposes cleanup, selected-row source, T/U sequence outputs, and diagnostics") {
    val p = InterfaceParams(robEntries = 8)
    val io = new TULinkFlushSequencePublisherIO(p, mapQDepth = 8, bidWidth = 16)

    assert(io.source.tSeq.value.getWidth == 3)
    assert(io.source.uSeq.value.getWidth == 3)
    assert(io.flushTSeq.value.getWidth == 3)
    assert(io.flushUSeq.value.getWidth == 3)
    assert(io.flushBid.value.getWidth == 3)
    assert(io.flushRid.value.getWidth == 3)
  }

  test("TULinkFlushSequencePublisher elaborates as a standalone sideband owner") {
    val sv = ChiselStage.emitSystemVerilog(
      new TULinkFlushSequencePublisher(p = InterfaceParams(robEntries = 8), mapQDepth = 8, bidWidth = 16)
    )

    assert(sv.contains("module TULinkFlushSequencePublisher"))
    assert(sv.contains("io_flushTSeq_value"))
    assert(sv.contains("io_flushUSeq_value"))
    assert(sv.contains("io_missingSource"))
    assert(sv.contains("io_sourceMismatch"))
    assert(sv.contains("io_tPrevApplied"))
    assert(!sv.contains("GPRRenameCheckpoint"))
  }

  test("Chisel destination enum values used by the publisher stay stable") {
    assert(DestinationKind.T.asUInt.litValue == 2)
    assert(DestinationKind.U.asUInt.litValue == 3)
  }
}
