package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object SCBResponseDecodeReference {
  sealed abstract class State
  case object Empty extends State
  case object Valid extends State
  case object Lookup extends State
  case object Miss extends State

  final case class Entry(valid: Boolean, state: State)
  final case class Result(
      memRespValid: Boolean,
      memRespEntryIndex: Int,
      decodedMask: BigInt,
      tagMatch: Boolean,
      responseTypeValid: Boolean,
      indexInRange: Boolean,
      targetMiss: Boolean,
      typeIllegal: Boolean,
      tagIllegal: Boolean,
      indexIllegal: Boolean,
      stateIllegalMask: BigInt,
      illegal: Boolean)

  def modelTxnId(entryIndex: Int): Int = (entryIndex << 2) | 2

  def decode(
      entries: Seq[Entry],
      rawValid: Boolean,
      rawTxnId: Int,
      rawWriteResp: Boolean,
      rawUpgradeResp: Boolean): Result = {
    val decodedIndex = rawTxnId >> 2
    val tagMatch = (rawTxnId & 3) == 2
    val responseTypeValid = rawWriteResp != rawUpgradeResp
    val indexInRange = decodedIndex >= 0 && decodedIndex < entries.length
    val candidate = rawValid && responseTypeValid && tagMatch && indexInRange
    val decodedMask = if (candidate) BigInt(1) << decodedIndex else BigInt(0)
    val targetMiss = candidate && entries(decodedIndex).valid && entries(decodedIndex).state == Miss
    val stateIllegalMask = if (candidate && !targetMiss) decodedMask else BigInt(0)
    val typeIllegal = rawValid && !responseTypeValid
    val tagIllegal = rawValid && responseTypeValid && !tagMatch
    val indexIllegal = rawValid && responseTypeValid && tagMatch && !indexInRange
    Result(
      memRespValid = candidate && targetMiss,
      memRespEntryIndex = decodedIndex,
      decodedMask = decodedMask,
      tagMatch = tagMatch,
      responseTypeValid = responseTypeValid,
      indexInRange = indexInRange,
      targetMiss = targetMiss,
      typeIllegal = typeIllegal,
      tagIllegal = tagIllegal,
      indexIllegal = indexIllegal,
      stateIllegalMask = stateIllegalMask,
      illegal = typeIllegal || tagIllegal || indexIllegal || stateIllegalMask != 0)
  }
}

class SCBResponseDecodeSpec extends AnyFunSuite {
  import SCBResponseDecodeReference._

  test("decodes model WriteResp transaction id for a miss row") {
    val entries = Seq.fill(6)(Entry(valid = false, state = Empty)).updated(3, Entry(valid = true, state = Miss))
    val result = decode(entries, rawValid = true, rawTxnId = modelTxnId(3), rawWriteResp = true, rawUpgradeResp = false)

    assert(result.memRespValid)
    assert(result.memRespEntryIndex == 3)
    assert(result.decodedMask == (BigInt(1) << 3))
    assert(result.tagMatch)
    assert(result.responseTypeValid)
    assert(result.indexInRange)
    assert(result.targetMiss)
    assert(!result.illegal)
  }

  test("decodes model UpgradeResp transaction id with the same row contract") {
    val entries = Seq.fill(4)(Entry(valid = false, state = Empty)).updated(1, Entry(valid = true, state = Miss))
    val result = decode(entries, rawValid = true, rawTxnId = modelTxnId(1), rawWriteResp = false, rawUpgradeResp = true)

    assert(result.memRespValid)
    assert(result.memRespEntryIndex == 1)
    assert(result.decodedMask == BigInt(2))
    assert(!result.illegal)
  }

  test("rejects transaction ids outside the model response tag namespace") {
    val entries = Seq(Entry(valid = true, state = Miss))
    val result = decode(entries, rawValid = true, rawTxnId = 1, rawWriteResp = true, rawUpgradeResp = false)

    assert(!result.memRespValid)
    assert(!result.tagMatch)
    assert(result.tagIllegal)
    assert(result.illegal)
  }

  test("rejects absent and ambiguous response types") {
    val entries = Seq(Entry(valid = true, state = Miss))
    val absent = decode(entries, rawValid = true, rawTxnId = modelTxnId(0), rawWriteResp = false, rawUpgradeResp = false)
    val ambiguous = decode(entries, rawValid = true, rawTxnId = modelTxnId(0), rawWriteResp = true, rawUpgradeResp = true)

    assert(!absent.memRespValid)
    assert(absent.typeIllegal)
    assert(!ambiguous.memRespValid)
    assert(ambiguous.typeIllegal)
  }

  test("rejects out-of-range response indices before row-state lookup") {
    val entries = Seq.fill(6)(Entry(valid = true, state = Miss))
    val result = decode(entries, rawValid = true, rawTxnId = modelTxnId(7), rawWriteResp = true, rawUpgradeResp = false)

    assert(!result.memRespValid)
    assert(!result.indexInRange)
    assert(result.indexIllegal)
    assert(result.decodedMask == BigInt(0))
    assert(result.stateIllegalMask == BigInt(0))
  }

  test("reports stale responses when the decoded row is not a miss") {
    val entries = Seq(Entry(valid = true, state = Valid), Entry(valid = true, state = Miss))
    val result = decode(entries, rawValid = true, rawTxnId = modelTxnId(0), rawWriteResp = false, rawUpgradeResp = true)

    assert(!result.memRespValid)
    assert(result.stateIllegalMask == BigInt(1))
    assert(result.illegal)
  }

  test("Chisel SCBResponseDecode elaborates with raw and decoded response signals") {
    val sv = ChiselStage.emitSystemVerilog(new SCBResponseDecode(scbEntries = 6))

    assert(sv.contains("module SCBResponseDecode"))
    assert(sv.contains("io_rawTxnId"))
    assert(sv.contains("io_memRespEntryIndex"))
    assert(sv.contains("io_stateIllegalMask"))
  }
}
