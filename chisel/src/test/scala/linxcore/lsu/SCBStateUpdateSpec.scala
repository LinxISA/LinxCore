package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object SCBStateUpdateReference {
  sealed abstract class State
  case object Empty extends State
  case object Valid extends State
  case object Lookup extends State
  case object Miss extends State

  final case class Entry(
      valid: Boolean,
      state: State,
      lineAddr: BigInt = 0,
      byteMask: BigInt = 0,
      data: BigInt = 0,
      full: Boolean = false)

  final case class Result(
      nextEntries: Seq[Entry],
      memRespMask: BigInt,
      acceptedToLookupMask: BigInt,
      missStateMask: BigInt,
      respToLookupMask: BigInt,
      clearedMask: BigInt,
      acceptedIllegalMask: BigInt,
      missIllegalMask: BigInt,
      freeIllegalMask: BigInt,
      memRespIllegalMask: BigInt,
      illegalMask: BigInt,
      stateError: Boolean)

  def step(
      entries: Seq[Entry],
      acceptedMask: BigInt = 0,
      missMask: BigInt = 0,
      freeMask: BigInt = 0,
      memRespValid: Boolean = false,
      memRespEntryIndex: Int = 0): Result = {
    require(entries.nonEmpty)
    require(memRespEntryIndex >= 0 && memRespEntryIndex < entries.length)

    val memRespMask = if (memRespValid) BigInt(1) << memRespEntryIndex else BigInt(0)
    var acceptedToLookupMask = BigInt(0)
    var missStateMask = BigInt(0)
    var respToLookupMask = BigInt(0)
    var clearedMask = BigInt(0)
    var acceptedIllegalMask = BigInt(0)
    var missIllegalMask = BigInt(0)
    var freeIllegalMask = BigInt(0)
    var memRespIllegalMask = BigInt(0)

    val nextEntries = entries.zipWithIndex.map { case (entry, idx) =>
      val bit = BigInt(1) << idx
      val accepted = (acceptedMask & bit) != 0
      val miss = (missMask & bit) != 0
      val free = (freeMask & bit) != 0
      val resp = (memRespMask & bit) != 0
      val canStartLookup = entry.valid && entry.state == Valid
      val canFinishLookup = entry.valid && (entry.state == Valid || entry.state == Lookup)
      val canAcceptResp = entry.valid && entry.state == Miss

      val acceptedOnly = accepted && !free && !miss && !resp
      val acceptedIllegal = acceptedOnly && !canStartLookup
      val missIllegal = miss && !canFinishLookup
      val freeIllegal = free && !canFinishLookup
      val memRespIllegal = resp && !canAcceptResp
      if (acceptedIllegal) acceptedIllegalMask |= bit
      if (missIllegal) missIllegalMask |= bit
      if (freeIllegal) freeIllegalMask |= bit
      if (memRespIllegal) memRespIllegalMask |= bit

      if (free && !freeIllegal) {
        clearedMask |= bit
        Entry(valid = false, state = Empty)
      } else if (miss && !missIllegal) {
        missStateMask |= bit
        entry.copy(state = Miss)
      } else if (resp && !memRespIllegal) {
        respToLookupMask |= bit
        entry.copy(state = Lookup)
      } else if (accepted && !acceptedIllegal && !free && !miss && !resp) {
        acceptedToLookupMask |= bit
        entry.copy(state = Lookup)
      } else {
        entry
      }
    }

    val illegalMask = acceptedIllegalMask | missIllegalMask | freeIllegalMask | memRespIllegalMask
    Result(
      nextEntries = nextEntries,
      memRespMask = memRespMask,
      acceptedToLookupMask = acceptedToLookupMask,
      missStateMask = missStateMask,
      respToLookupMask = respToLookupMask,
      clearedMask = clearedMask,
      acceptedIllegalMask = acceptedIllegalMask,
      missIllegalMask = missIllegalMask,
      freeIllegalMask = freeIllegalMask,
      memRespIllegalMask = memRespIllegalMask,
      illegalMask = illegalMask,
      stateError = illegalMask != 0)
  }
}

class SCBStateUpdateSpec extends AnyFunSuite {
  import SCBStateUpdateReference._

  test("accepted valid rows move to lookup state") {
    val result = step(
      Seq(Entry(valid = true, state = Valid, byteMask = 0xff), Entry(valid = false, state = Empty)),
      acceptedMask = BigInt(1))

    assert(result.nextEntries.head.state == Lookup)
    assert(result.nextEntries.head.valid)
    assert(result.nextEntries.head.byteMask == BigInt(0xff))
    assert(result.acceptedToLookupMask == BigInt(1))
    assert(!result.stateError)
  }

  test("same-cycle miss drives the selected row to miss state") {
    val result = step(
      Seq(Entry(valid = true, state = Valid, lineAddr = 0x1000), Entry(valid = true, state = Valid)),
      acceptedMask = BigInt(1),
      missMask = BigInt(1))

    assert(result.nextEntries.head.state == Miss)
    assert(result.missStateMask == BigInt(1))
    assert(result.acceptedToLookupMask == BigInt(0))
    assert(!result.stateError)
  }

  test("writable hit free clears the row and dominates lookup start") {
    val result = step(
      Seq(Entry(valid = true, state = Valid, lineAddr = 0x2000, byteMask = 0xf, data = 0xaabb, full = true)),
      acceptedMask = BigInt(1),
      freeMask = BigInt(1))

    assert(!result.nextEntries.head.valid)
    assert(result.nextEntries.head.state == Empty)
    assert(result.nextEntries.head.byteMask == BigInt(0))
    assert(result.nextEntries.head.data == BigInt(0))
    assert(result.clearedMask == BigInt(1))
    assert(result.acceptedToLookupMask == BigInt(0))
    assert(!result.stateError)
  }

  test("lookup rows may move to miss after a registered DCache miss") {
    val result = step(
      Seq(Entry(valid = true, state = Lookup), Entry(valid = true, state = Valid)),
      missMask = BigInt(1))

    assert(result.nextEntries.head.state == Miss)
    assert(result.missStateMask == BigInt(1))
    assert(!result.stateError)
  }

  test("retrying lookup rows may finish on a writable hit") {
    val result = step(
      Seq(Entry(valid = true, state = Lookup, lineAddr = 0x3000, byteMask = 0xff, data = 0xaabb)),
      acceptedMask = BigInt(1),
      freeMask = BigInt(1))

    assert(!result.nextEntries.head.valid)
    assert(result.clearedMask == BigInt(1))
    assert(result.acceptedIllegalMask == BigInt(0))
    assert(!result.stateError)
  }

  test("accepted-only lookup rows remain illegal") {
    val result = step(
      Seq(Entry(valid = true, state = Lookup, lineAddr = 0x3800)),
      acceptedMask = BigInt(1))

    assert(result.nextEntries.head.state == Lookup)
    assert(result.acceptedIllegalMask == BigInt(1))
    assert(result.stateError)
  }

  test("memory response returns miss rows to lookup state") {
    val result = step(
      Seq(Entry(valid = true, state = Valid), Entry(valid = true, state = Miss, lineAddr = 0x4000)),
      memRespValid = true,
      memRespEntryIndex = 1)

    assert(result.memRespMask == BigInt(2))
    assert(result.nextEntries(1).state == Lookup)
    assert(result.nextEntries(1).lineAddr == BigInt(0x4000))
    assert(result.respToLookupMask == BigInt(2))
    assert(!result.stateError)
  }

  test("memory response to a non-miss row is reported illegal and leaves state unchanged") {
    val result = step(
      Seq(Entry(valid = true, state = Valid), Entry(valid = true, state = Miss)),
      memRespValid = true,
      memRespEntryIndex = 0)

    assert(result.nextEntries.head.state == Valid)
    assert(result.memRespIllegalMask == BigInt(1))
    assert(result.illegalMask == BigInt(1))
    assert(result.stateError)
  }

  test("free masks have highest legal transition priority") {
    val result = step(
      Seq(Entry(valid = true, state = Valid, byteMask = 0xff)),
      acceptedMask = BigInt(1),
      missMask = BigInt(1),
      freeMask = BigInt(1))

    assert(result.nextEntries.head.state == Empty)
    assert(result.clearedMask == BigInt(1))
    assert(result.missStateMask == BigInt(0))
    assert(result.acceptedToLookupMask == BigInt(0))
    assert(!result.stateError)
  }

  test("illegal miss and free requests are visible to the future composition owner") {
    val result = step(
      Seq(Entry(valid = false, state = Empty), Entry(valid = true, state = Miss)),
      missMask = BigInt(1),
      freeMask = BigInt(2))

    assert(result.missIllegalMask == BigInt(1))
    assert(result.freeIllegalMask == BigInt(2))
    assert(result.illegalMask == BigInt(3))
    assert(result.stateError)
  }

  test("Chisel SCBStateUpdate elaborates with response and error masks") {
    val sv = ChiselStage.emitSystemVerilog(new SCBStateUpdate(scbEntries = 4))

    assert(sv.contains("module SCBStateUpdate"))
    assert(sv.contains("io_nextEntries_0_state"))
    assert(sv.contains("io_memRespMask"))
    assert(sv.contains("io_respToLookupMask"))
    assert(sv.contains("io_stateError"))
  }
}
