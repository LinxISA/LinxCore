package linxcore.rename

import circt.stage.ChiselStage
import linxcore.common.InterfaceParams
import linxcore.rob.ROBIDValue
import org.scalatest.funsuite.AnyFunSuite

object TULinkFlushSourceSelectorReference {
  import TULinkFlushSequencePublisherReference.Source

  final case class Result(
      source: Source,
      cleanupActive: Boolean,
      sourceRequired: Boolean,
      robMatched: Boolean,
      lsuMatched: Boolean,
      robMismatched: Boolean,
      lsuMismatched: Boolean,
      multipleMatched: Boolean,
      sourceConflict: Boolean,
      sourceMissing: Boolean,
      selectedFromRob: Boolean,
      selectedFromLsu: Boolean)

  private def matches(source: Source, bid: ROBIDValue, rid: ROBIDValue, stid: Int): Boolean =
    source.valid && source.bid == bid && source.rid == rid && source.stid == stid

  def select(
      cleanupValid: Boolean,
      backendFlushValid: Boolean,
      baseOnBid: Boolean,
      bid: ROBIDValue,
      rid: ROBIDValue,
      stid: Int,
      robSource: Source,
      lsuSource: Source): Result = {
    val active = cleanupValid && backendFlushValid
    val required = active && !baseOnBid
    val robMatched = required && matches(robSource, bid, rid, stid)
    val lsuMatched = required && matches(lsuSource, bid, rid, stid)
    val multiple = robMatched && lsuMatched
    val conflict = multiple && robSource != lsuSource
    val selectedFromRob = robMatched && !conflict
    val selectedFromLsu = !robMatched && lsuMatched && !conflict
    val selected =
      if (selectedFromRob) robSource
      else if (selectedFromLsu) lsuSource
      else robSource.copy(valid = false)

    Result(
      source = selected,
      cleanupActive = active,
      sourceRequired = required,
      robMatched = robMatched,
      lsuMatched = lsuMatched,
      robMismatched = required && robSource.valid && !robMatched,
      lsuMismatched = required && lsuSource.valid && !lsuMatched,
      multipleMatched = multiple,
      sourceConflict = conflict,
      sourceMissing = required && !robMatched && !lsuMatched,
      selectedFromRob = selectedFromRob,
      selectedFromLsu = selectedFromLsu)
  }
}

class TULinkFlushSourceSelectorSpec extends AnyFunSuite {
  import TULinkFlushSequencePublisherReference._
  import TULinkFlushSourceSelectorReference._

  private val bid1 = ROBIDValue(value = 1)
  private val bid2 = ROBIDValue(value = 2)
  private val rid3 = ROBIDValue(value = 3)
  private val rid4 = ROBIDValue(value = 4)
  private val empty = Source(
    valid = false,
    bid = ROBIDValue(),
    rid = ROBIDValue(),
    stid = 0,
    tSeq = ROBIDValue(),
    uSeq = ROBIDValue(),
    dst = NoDst
  )
  private val matchingRob = Source(
    valid = true,
    bid = bid2,
    rid = rid3,
    stid = 1,
    tSeq = ROBIDValue(value = 5),
    uSeq = ROBIDValue(value = 6),
    dst = TDst
  )
  private val matchingLsu = Source(
    valid = true,
    bid = bid2,
    rid = rid3,
    stid = 1,
    tSeq = ROBIDValue(value = 5),
    uSeq = ROBIDValue(value = 6),
    dst = TDst
  )

  test("non-base cleanup selects the matching ROB source when LSU has no candidate") {
    val result = select(
      cleanupValid = true,
      backendFlushValid = true,
      baseOnBid = false,
      bid = bid2,
      rid = rid3,
      stid = 1,
      robSource = matchingRob,
      lsuSource = empty)

    assert(result.source == matchingRob)
    assert(result.selectedFromRob)
    assert(!result.selectedFromLsu)
    assert(result.robMatched)
    assert(!result.lsuMatched)
    assert(!result.sourceMissing)
    assert(!result.sourceConflict)
  }

  test("non-base cleanup selects LSU when ROB is absent or mismatched") {
    val mismatchedRob = matchingRob.copy(rid = rid4)
    val result = select(
      cleanupValid = true,
      backendFlushValid = true,
      baseOnBid = false,
      bid = bid2,
      rid = rid3,
      stid = 1,
      robSource = mismatchedRob,
      lsuSource = matchingLsu.copy(dst = UDst))

    assert(result.source == matchingLsu.copy(dst = UDst))
    assert(!result.selectedFromRob)
    assert(result.selectedFromLsu)
    assert(result.robMismatched)
    assert(result.lsuMatched)
  }

  test("base-on-BID cleanup does not require or select a row source") {
    val result = select(
      cleanupValid = true,
      backendFlushValid = true,
      baseOnBid = true,
      bid = bid2,
      rid = rid3,
      stid = 1,
      robSource = matchingRob,
      lsuSource = matchingLsu)

    assert(!result.source.valid)
    assert(!result.sourceRequired)
    assert(!result.selectedFromRob)
    assert(!result.selectedFromLsu)
    assert(!result.sourceMissing)
    assert(!result.sourceConflict)
  }

  test("missing non-base source reports no matching candidate") {
    val result = select(
      cleanupValid = true,
      backendFlushValid = true,
      baseOnBid = false,
      bid = bid2,
      rid = rid3,
      stid = 1,
      robSource = matchingRob.copy(bid = bid1),
      lsuSource = empty)

    assert(!result.source.valid)
    assert(result.sourceMissing)
    assert(result.robMismatched)
    assert(!result.sourceConflict)
  }

  test("duplicate matching ROB and LSU sources are legal only when the payload agrees") {
    val agreed = select(
      cleanupValid = true,
      backendFlushValid = true,
      baseOnBid = false,
      bid = bid2,
      rid = rid3,
      stid = 1,
      robSource = matchingRob,
      lsuSource = matchingLsu)

    assert(agreed.multipleMatched)
    assert(!agreed.sourceConflict)
    assert(agreed.selectedFromRob)
    assert(agreed.source == matchingRob)

    val conflict = select(
      cleanupValid = true,
      backendFlushValid = true,
      baseOnBid = false,
      bid = bid2,
      rid = rid3,
      stid = 1,
      robSource = matchingRob,
      lsuSource = matchingLsu.copy(uSeq = ROBIDValue(value = 7)))

    assert(conflict.multipleMatched)
    assert(conflict.sourceConflict)
    assert(!conflict.source.valid)
    assert(!conflict.selectedFromRob)
    assert(!conflict.selectedFromLsu)
  }

  test("inactive cleanup ignores otherwise matching candidates") {
    val result = select(
      cleanupValid = true,
      backendFlushValid = false,
      baseOnBid = false,
      bid = bid2,
      rid = rid3,
      stid = 1,
      robSource = matchingRob,
      lsuSource = matchingLsu)

    assert(!result.cleanupActive)
    assert(!result.sourceRequired)
    assert(!result.source.valid)
    assert(!result.robMatched)
    assert(!result.lsuMatched)
  }

  test("IO exposes ROB/LSU candidates, selected source, and diagnostics") {
    val p = InterfaceParams(robEntries = 8)
    val io = new TULinkFlushSourceSelectorIO(p, mapQDepth = 8, bidWidth = 16)

    assert(io.robSource.tSeq.value.getWidth == 3)
    assert(io.lsuSource.uSeq.value.getWidth == 3)
    assert(io.source.tSeq.value.getWidth == 3)
    assert(io.source.bid.value.getWidth == 3)
    assert(io.cleanup.blockFlushBid.getWidth == 3)
    assert(io.cleanup.blockFlushPointer.getWidth == 16)
  }

  test("TULinkFlushSourceSelector elaborates as the ROB/LSU source boundary owner") {
    val sv = ChiselStage.emitSystemVerilog(
      new TULinkFlushSourceSelector(p = InterfaceParams(robEntries = 8), mapQDepth = 8, bidWidth = 16)
    )

    assert(sv.contains("module TULinkFlushSourceSelector"))
    assert(sv.contains("io_robMatched"))
    assert(sv.contains("io_lsuMatched"))
    assert(sv.contains("io_sourceConflict"))
    assert(sv.contains("io_selectedFromRob"))
    assert(!sv.contains("TULinkRename"))
  }
}
