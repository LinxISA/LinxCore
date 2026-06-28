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
  final case class RetryHead(valid: Boolean, index: Int)
  final case class Result(
      selectedValid: Boolean,
      selectedIndex: Int,
      retrySelected: Boolean,
      normalSelected: Boolean,
      retryHeadBlocked: Boolean,
      retryCandidateMask: BigInt,
      retryLookupMask: BigInt,
      normalSelectedMask: BigInt,
      lookupMask: BigInt,
      lookupFull: Boolean,
      lookupNotFull: Boolean,
      noCandidate: Boolean)

  def select(entries: Seq[Entry], retryHead: RetryHead, normal: NormalRequest, normalLookupMask: BigInt): Result = {
    val retryCandidateMask = entries.zipWithIndex.foldLeft(BigInt(0)) {
      case (mask, (entry, idx)) if entry.valid && entry.state == Lookup => mask | (BigInt(1) << idx)
      case (mask, _) => mask
    }
    val retryReady = retryHead.valid && entries(retryHead.index).valid && entries(retryHead.index).state == Lookup
    val retryHeadBlocked = retryHead.valid && !retryReady
    val normalSelected = !retryHead.valid && normal.valid
    val retrySelected = retryReady
    val selectedValid = retrySelected || normalSelected
    val selectedIndex = if (retrySelected) retryHead.index else normal.index
    val selectedFull = if (retrySelected) entries(retryHead.index).full else normal.full

    Result(
      selectedValid = selectedValid,
      selectedIndex = selectedIndex,
      retrySelected = retrySelected,
      normalSelected = normalSelected,
      retryHeadBlocked = retryHeadBlocked,
      retryCandidateMask = retryCandidateMask,
      retryLookupMask = if (retrySelected) BigInt(1) << retryHead.index else BigInt(0),
      normalSelectedMask = if (normalSelected) normalLookupMask else BigInt(0),
      lookupMask = if (retryHead.valid) {
        if (retrySelected) BigInt(1) << retryHead.index else BigInt(0)
      } else normalLookupMask,
      lookupFull = selectedValid && selectedFull,
      lookupNotFull = selectedValid && !selectedFull,
      noCandidate = !retryHead.valid && !normal.valid)
  }
}

class SCBResponseRetrySelectSpec extends AnyFunSuite {
  import SCBResponseRetrySelectReference._

  test("response-returned lookup rows win over ordinary valid-row eviction") {
    val result = select(
      Seq(
        Entry(valid = true, state = Valid, full = true),
        Entry(valid = true, state = Lookup, full = false),
        Entry(valid = true, state = Valid, full = false)),
      retryHead = RetryHead(valid = true, index = 1),
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

  test("queued response head selects retry order instead of lowest lookup index") {
    val result = select(
      Seq(
        Entry(valid = true, state = Lookup, full = false),
        Entry(valid = true, state = Lookup, full = true),
        Entry(valid = true, state = Miss, full = false)),
      retryHead = RetryHead(valid = true, index = 1),
      normal = NormalRequest(valid = false, index = 0, full = false),
      normalLookupMask = BigInt(0))

    assert(result.retrySelected)
    assert(result.selectedIndex == 1)
    assert(result.retryCandidateMask == BigInt(3))
    assert(result.retryLookupMask == BigInt(2))
    assert(result.lookupMask == BigInt(2))
    assert(result.lookupFull)
  }

  test("normal egress descriptor is forwarded when no retry row exists") {
    val result = select(
      Seq(
        Entry(valid = true, state = Valid, full = true),
        Entry(valid = true, state = Miss, full = false)),
      retryHead = RetryHead(valid = false, index = 0),
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

  test("stale retry head blocks normal egress and reports the blocked head") {
    val result = select(
      Seq(
        Entry(valid = true, state = Valid, full = true),
        Entry(valid = true, state = Miss, full = false)),
      retryHead = RetryHead(valid = true, index = 1),
      normal = NormalRequest(valid = true, index = 0, full = true),
      normalLookupMask = BigInt(1))

    assert(!result.selectedValid)
    assert(!result.retrySelected)
    assert(!result.normalSelected)
    assert(result.retryHeadBlocked)
    assert(result.retryCandidateMask == BigInt(0))
    assert(result.lookupMask == BigInt(0))
  }

  test("reports no candidate when neither retry nor normal egress can issue") {
    val result = select(
      Seq(
        Entry(valid = false, state = Empty, full = false),
        Entry(valid = true, state = Miss, full = false)),
      retryHead = RetryHead(valid = false, index = 0),
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
    assert(sv.contains("io_retryHeadBlocked"))
    assert(sv.contains("io_lookupRequest_entryIndex"))
  }
}
