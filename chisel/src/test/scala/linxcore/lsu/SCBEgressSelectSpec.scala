package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object SCBEgressSelectReference {
  sealed abstract class State
  case object Empty extends State
  case object Valid extends State
  case object Lookup extends State
  case object Miss extends State

  final case class Entry(valid: Boolean, state: State, full: Boolean, lineAddr: BigInt = 0, byteMask: BigInt = 0)
  final case class Result(
      selectedValid: Boolean,
      selectedIndex: Int,
      lookupFull: Boolean,
      lookupNotFull: Boolean,
      noCandidate: Boolean,
      validStateMask: BigInt,
      fullCandidateMask: BigInt,
      notFullCandidateMask: BigInt,
      lookupMask: BigInt)

  def select(entries: Seq[Entry], evictEnable: Boolean): Result = {
    val validStateMask = mask(entries.zipWithIndex.collect { case (entry, idx) if entry.valid && entry.state == Valid => idx })
    val fullMask = mask(entries.zipWithIndex.collect {
      case (entry, idx) if entry.valid && entry.state == Valid && entry.full => idx
    })
    val notFullMask = mask(entries.zipWithIndex.collect {
      case (entry, idx) if entry.valid && entry.state == Valid && !entry.full => idx
    })
    val selectedIndex =
      if (fullMask != 0) firstSet(fullMask)
      else if (notFullMask != 0) firstSet(notFullMask)
      else 0
    val selectedValid = evictEnable && (fullMask != 0 || notFullMask != 0)
    val selectedFromFull = selectedValid && fullMask != 0
    val selectedFromNotFull = selectedValid && fullMask == 0 && notFullMask != 0

    Result(
      selectedValid = selectedValid,
      selectedIndex = selectedIndex,
      lookupFull = selectedFromFull,
      lookupNotFull = selectedFromNotFull,
      noCandidate = evictEnable && fullMask == 0 && notFullMask == 0,
      validStateMask = validStateMask,
      fullCandidateMask = fullMask,
      notFullCandidateMask = notFullMask,
      lookupMask = if (selectedValid) BigInt(1) << selectedIndex else BigInt(0))
  }

  private def mask(indices: Seq[Int]): BigInt =
    indices.foldLeft(BigInt(0)) { case (acc, idx) => acc | (BigInt(1) << idx) }

  private def firstSet(mask: BigInt): Int =
    LazyList.from(0).find(idx => ((mask >> idx) & 1) == 1).getOrElse(0)
}

class SCBEgressSelectSpec extends AnyFunSuite {
  import SCBEgressSelectReference._

  test("full valid entries have priority over not-full entries") {
    val result = select(
      Seq(
        Entry(valid = true, state = Valid, full = false),
        Entry(valid = true, state = Valid, full = true),
        Entry(valid = true, state = Valid, full = true)),
      evictEnable = true)

    assert(result.selectedValid)
    assert(result.selectedIndex == 1)
    assert(result.lookupFull)
    assert(!result.lookupNotFull)
    assert(result.validStateMask == BigInt(7))
    assert(result.fullCandidateMask == BigInt(6))
    assert(result.notFullCandidateMask == BigInt(1))
    assert(result.lookupMask == BigInt(2))
  }

  test("not-full fallback selects the first valid line when no full line exists") {
    val result = select(
      Seq(
        Entry(valid = false, state = Empty, full = false),
        Entry(valid = true, state = Valid, full = false),
        Entry(valid = true, state = Valid, full = false)),
      evictEnable = true)

    assert(result.selectedValid)
    assert(result.selectedIndex == 1)
    assert(!result.lookupFull)
    assert(result.lookupNotFull)
    assert(!result.noCandidate)
    assert(result.lookupMask == BigInt(2))
  }

  test("selector exposes candidates but issues no lookup when eviction is disabled") {
    val result = select(
      Seq(
        Entry(valid = true, state = Valid, full = true),
        Entry(valid = true, state = Valid, full = false)),
      evictEnable = false)

    assert(!result.selectedValid)
    assert(!result.lookupFull)
    assert(!result.lookupNotFull)
    assert(!result.noCandidate)
    assert(result.fullCandidateMask == BigInt(1))
    assert(result.notFullCandidateMask == BigInt(2))
    assert(result.lookupMask == BigInt(0))
  }

  test("lookup and miss entries are ignored until a response-retry owner handles them") {
    val result = select(
      Seq(
        Entry(valid = true, state = Lookup, full = true),
        Entry(valid = true, state = Miss, full = false),
        Entry(valid = true, state = Valid, full = false)),
      evictEnable = true)

    assert(result.selectedValid)
    assert(result.selectedIndex == 2)
    assert(result.validStateMask == BigInt(4))
    assert(result.fullCandidateMask == BigInt(0))
    assert(result.notFullCandidateMask == BigInt(4))
    assert(result.lookupNotFull)
  }

  test("selector reports no candidate when every entry is empty or already in flight") {
    val result = select(
      Seq(
        Entry(valid = false, state = Empty, full = false),
        Entry(valid = true, state = Lookup, full = true),
        Entry(valid = true, state = Miss, full = false)),
      evictEnable = true)

    assert(!result.selectedValid)
    assert(result.noCandidate)
    assert(result.lookupMask == BigInt(0))
  }

  test("Chisel SCBEgressSelect elaborates with lookup descriptor and candidate masks") {
    val sv = ChiselStage.emitSystemVerilog(new SCBEgressSelect(scbEntries = 4))

    assert(sv.contains("module SCBEgressSelect"))
    assert(sv.contains("io_lookupRequest_lineAddr"))
    assert(sv.contains("io_lookupRequest_entryIndex"))
    assert(sv.contains("io_lookupMask"))
    assert(sv.contains("io_fullCandidateMask"))
  }
}
