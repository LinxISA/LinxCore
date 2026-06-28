package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object SCBResponseRetrySelectReference {
  sealed abstract class State
  case object Empty extends State
  case object Valid extends State
  case object Lookup extends State
  case object Miss extends State

  final case class Entry(valid: Boolean, state: State, full: Boolean, lineAddr: BigInt = 0, byteMask: BigInt = 0)
  final case class NormalRequest(valid: Boolean, index: Int, full: Boolean)
  final case class Result(
      selectedValid: Boolean,
      selectedIndex: Int,
      retrySelected: Boolean,
      normalSelected: Boolean,
      retryCandidateMask: BigInt,
      retryLookupMask: BigInt,
      normalSelectedMask: BigInt,
      lookupMask: BigInt,
      lookupFull: Boolean,
      lookupNotFull: Boolean,
      noCandidate: Boolean)

  def select(entries: Seq[Entry], normal: NormalRequest, normalLookupMask: BigInt): Result = {
    val retryCandidateMask = entries.zipWithIndex.foldLeft(BigInt(0)) {
      case (mask, (entry, idx)) if entry.valid && entry.state == Lookup => mask | (BigInt(1) << idx)
      case (mask, _) => mask
    }
    val retrySelected = retryCandidateMask != 0
    val retryIndex = if (retrySelected) firstSet(retryCandidateMask) else 0
    val normalSelected = !retrySelected && normal.valid
    val selectedValid = retrySelected || normalSelected
    val selectedIndex = if (retrySelected) retryIndex else normal.index
    val selectedFull = if (retrySelected) entries(retryIndex).full else normal.full

    Result(
      selectedValid = selectedValid,
      selectedIndex = selectedIndex,
      retrySelected = retrySelected,
      normalSelected = normalSelected,
      retryCandidateMask = retryCandidateMask,
      retryLookupMask = if (retrySelected) BigInt(1) << retryIndex else BigInt(0),
      normalSelectedMask = if (normalSelected) normalLookupMask else BigInt(0),
      lookupMask = if (retrySelected) BigInt(1) << retryIndex else normalLookupMask,
      lookupFull = selectedValid && selectedFull,
      lookupNotFull = selectedValid && !selectedFull,
      noCandidate = !retrySelected && !normal.valid)
  }

  private def firstSet(mask: BigInt): Int =
    LazyList.from(0).find(idx => ((mask >> idx) & 1) == 1).getOrElse(0)
}

class SCBResponseRetrySelectSpec extends AnyFunSuite {
  import SCBResponseRetrySelectReference._

  test("response-returned lookup rows win over ordinary valid-row eviction") {
    val result = select(
      Seq(
        Entry(valid = true, state = Valid, full = true),
        Entry(valid = true, state = Lookup, full = false),
        Entry(valid = true, state = Valid, full = false)),
      normal = NormalRequest(valid = true, index = 0, full = true),
      normalLookupMask = BigInt(1))

    assert(result.selectedValid)
    assert(result.retrySelected)
    assert(!result.normalSelected)
    assert(result.selectedIndex == 1)
    assert(result.retryCandidateMask == BigInt(2))
    assert(result.retryLookupMask == BigInt(2))
    assert(result.normalSelectedMask == BigInt(0))
    assert(result.lookupMask == BigInt(2))
    assert(result.lookupNotFull)
  }

  test("first lookup row is selected when multiple retry candidates exist") {
    val result = select(
      Seq(
        Entry(valid = true, state = Lookup, full = false),
        Entry(valid = true, state = Lookup, full = true),
        Entry(valid = true, state = Miss, full = false)),
      normal = NormalRequest(valid = false, index = 0, full = false),
      normalLookupMask = BigInt(0))

    assert(result.retrySelected)
    assert(result.selectedIndex == 0)
    assert(result.retryCandidateMask == BigInt(3))
    assert(result.retryLookupMask == BigInt(1))
    assert(result.lookupMask == BigInt(1))
  }

  test("normal egress descriptor is forwarded when no retry row exists") {
    val result = select(
      Seq(
        Entry(valid = true, state = Valid, full = true),
        Entry(valid = true, state = Miss, full = false)),
      normal = NormalRequest(valid = true, index = 0, full = true),
      normalLookupMask = BigInt(1))

    assert(result.selectedValid)
    assert(!result.retrySelected)
    assert(result.normalSelected)
    assert(result.selectedIndex == 0)
    assert(result.normalSelectedMask == BigInt(1))
    assert(result.lookupMask == BigInt(1))
    assert(result.lookupFull)
  }

  test("reports no candidate when neither retry nor normal egress can issue") {
    val result = select(
      Seq(
        Entry(valid = false, state = Empty, full = false),
        Entry(valid = true, state = Miss, full = false)),
      normal = NormalRequest(valid = false, index = 0, full = false),
      normalLookupMask = BigInt(0))

    assert(!result.selectedValid)
    assert(!result.retrySelected)
    assert(!result.normalSelected)
    assert(result.noCandidate)
    assert(result.lookupMask == BigInt(0))
  }

  test("Chisel SCBResponseRetrySelect elaborates with retry and final lookup masks") {
    val sv = ChiselStage.emitSystemVerilog(new SCBResponseRetrySelect(scbEntries = 4))

    assert(sv.contains("module SCBResponseRetrySelect"))
    assert(sv.contains("io_retryCandidateMask"))
    assert(sv.contains("io_retryLookupMask"))
    assert(sv.contains("io_normalSelectedMask"))
    assert(sv.contains("io_lookupRequest_entryIndex"))
  }
}
