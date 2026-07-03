package linxcore.rob

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object ROBRowCommitTraceLookupReference {
  final case class Id(valid: Boolean = true, wrap: Boolean = false, value: Int = 0)
  final case class Row(
      occupied: Boolean = false,
      rid: Id = Id(valid = false),
      status: String = "Free",
      rowValid: Boolean = false,
      insn: BigInt = 0,
      len: Int = 0,
      src0Valid: Boolean = false,
      src0Reg: Int = 0,
      src1Valid: Boolean = false,
      src1Reg: Int = 0)
  final case class Result(
      queryValid: Boolean,
      rowValid: Boolean,
      ridMatch: Boolean,
      status: String,
      needFlush: Boolean,
      instructionProviderValid: Boolean,
      instructionRaw: BigInt,
      instructionLen: Int,
      sourceTraceProviderValid: Boolean,
      source0Valid: Boolean,
      source0Reg: Int,
      source1Valid: Boolean,
      source1Reg: Int,
      blockedByInvalidRid: Boolean,
      blockedByFree: Boolean,
      blockedByStaleRid: Boolean,
      blockedByNeedFlush: Boolean,
      blockedByMissingInstruction: Boolean,
      blockedBySourceTraceDisabled: Boolean,
      blockedBySourceTraceBeforeCompletion: Boolean)

  private def equal(lhs: Id, rhs: Id): Boolean =
    lhs.wrap == rhs.wrap && lhs.value == rhs.value

  def apply(queryValid: Boolean, queryRid: Id, rows: Seq[Row], sourceTraceEnable: Boolean): Result = {
    val selected = rows(queryRid.value)
    val slotRidMatch = selected.occupied && equal(selected.rid, queryRid)
    val rowValid = queryValid && queryRid.valid && slotRidMatch
    val status = if (rowValid) selected.status else "Free"
    val needFlush = rowValid && status == "NeedFlush"
    val liveRow = rowValid && selected.rowValid && !needFlush
    val rowCompleted = rowValid && status == "Completed"
    val instructionReady = liveRow && selected.len != 0
    val sourceTraceReady = liveRow && sourceTraceEnable && rowCompleted

    Result(
      queryValid = queryValid,
      rowValid = rowValid,
      ridMatch = queryValid && queryRid.valid && slotRidMatch,
      status = status,
      needFlush = needFlush,
      instructionProviderValid = instructionReady,
      instructionRaw = if (instructionReady) selected.insn else 0,
      instructionLen = if (instructionReady) selected.len else 0,
      sourceTraceProviderValid = sourceTraceReady,
      source0Valid = sourceTraceReady && selected.src0Valid,
      source0Reg = if (sourceTraceReady) selected.src0Reg else 0,
      source1Valid = sourceTraceReady && selected.src1Valid,
      source1Reg = if (sourceTraceReady) selected.src1Reg else 0,
      blockedByInvalidRid = queryValid && !queryRid.valid,
      blockedByFree = queryValid && queryRid.valid && !selected.occupied,
      blockedByStaleRid = queryValid && queryRid.valid && selected.occupied && !slotRidMatch,
      blockedByNeedFlush = needFlush,
      blockedByMissingInstruction = rowValid && !needFlush && (!selected.rowValid || selected.len == 0),
      blockedBySourceTraceDisabled = liveRow && !sourceTraceEnable,
      blockedBySourceTraceBeforeCompletion = liveRow && sourceTraceEnable && !rowCompleted)
  }
}

class ROBRowCommitTraceLookupSpec extends AnyFunSuite {
  import ROBRowCommitTraceLookupReference._

  private def emptyRows: Seq[Row] = Seq.fill(8)(Row())

  test("returns instruction metadata and source traces for a live matching ROB row") {
    val rows = emptyRows.updated(3, Row(
      occupied = true,
      rid = Id(value = 3),
      status = "Completed",
      rowValid = true,
      insn = BigInt("123456789abc", 16),
      len = 6,
      src0Valid = true,
      src0Reg = 10,
      src1Valid = true,
      src1Reg = 11))

    val result = ROBRowCommitTraceLookupReference(
      queryValid = true,
      queryRid = Id(value = 3),
      rows = rows,
      sourceTraceEnable = true)

    assert(result.rowValid)
    assert(result.ridMatch)
    assert(!result.needFlush)
    assert(result.instructionProviderValid)
    assert(result.instructionRaw == BigInt("123456789abc", 16))
    assert(result.instructionLen == 6)
    assert(result.sourceTraceProviderValid)
    assert(result.source0Valid)
    assert(result.source0Reg == 10)
    assert(result.source1Valid)
    assert(result.source1Reg == 11)
  }

  test("suppresses source traces when sourceTraceEnable is false but keeps instruction metadata") {
    val rows = emptyRows.updated(2, Row(
      occupied = true,
      rid = Id(value = 2),
      status = "Completed",
      rowValid = true,
      insn = 0xabcdef,
      len = 4,
      src0Valid = true,
      src0Reg = 1))

    val result = ROBRowCommitTraceLookupReference(
      queryValid = true,
      queryRid = Id(value = 2),
      rows = rows,
      sourceTraceEnable = false)

    assert(result.instructionProviderValid)
    assert(result.instructionRaw == 0xabcdef)
    assert(!result.sourceTraceProviderValid)
    assert(!result.source0Valid)
    assert(result.source0Reg == 0)
    assert(result.blockedBySourceTraceDisabled)
  }

  test("blocks ROB row source traces before completion while preserving instruction metadata") {
    val rows = emptyRows.updated(1, Row(
      occupied = true,
      rid = Id(value = 1),
      status = "Renamed",
      rowValid = true,
      insn = 0x1234,
      len = 4,
      src0Valid = true,
      src0Reg = 3,
      src1Valid = true,
      src1Reg = 4))

    val result = ROBRowCommitTraceLookupReference(
      queryValid = true,
      queryRid = Id(value = 1),
      rows = rows,
      sourceTraceEnable = true)

    assert(result.rowValid)
    assert(result.instructionProviderValid)
    assert(result.instructionRaw == 0x1234)
    assert(!result.sourceTraceProviderValid)
    assert(!result.source0Valid)
    assert(!result.source1Valid)
    assert(result.blockedBySourceTraceBeforeCompletion)
    assert(!result.blockedBySourceTraceDisabled)
  }

  test("blocks invalid free and stale RID queries") {
    val rows = emptyRows.updated(5, Row(
      occupied = true,
      rid = Id(wrap = true, value = 5),
      status = "Renamed",
      rowValid = true,
      insn = 0x44,
      len = 2))

    val invalid = ROBRowCommitTraceLookupReference(
      queryValid = true,
      queryRid = Id(valid = false, value = 1),
      rows = rows,
      sourceTraceEnable = true)
    val free = ROBRowCommitTraceLookupReference(
      queryValid = true,
      queryRid = Id(value = 1),
      rows = rows,
      sourceTraceEnable = true)
    val stale = ROBRowCommitTraceLookupReference(
      queryValid = true,
      queryRid = Id(wrap = false, value = 5),
      rows = rows,
      sourceTraceEnable = true)

    assert(invalid.blockedByInvalidRid)
    assert(!invalid.rowValid)
    assert(free.blockedByFree)
    assert(!free.instructionProviderValid)
    assert(stale.blockedByStaleRid)
    assert(!stale.ridMatch)
  }

  test("blocks NeedFlush rows before exposing provider payloads") {
    val rows = emptyRows.updated(4, Row(
      occupied = true,
      rid = Id(value = 4),
      status = "NeedFlush",
      rowValid = true,
      insn = 0x777,
      len = 4,
      src0Valid = true,
      src0Reg = 3))

    val result = ROBRowCommitTraceLookupReference(
      queryValid = true,
      queryRid = Id(value = 4),
      rows = rows,
      sourceTraceEnable = true)

    assert(result.rowValid)
    assert(result.needFlush)
    assert(result.blockedByNeedFlush)
    assert(!result.instructionProviderValid)
    assert(!result.sourceTraceProviderValid)
  }

  test("blocks zero-length or invalid rows as missing instruction metadata") {
    val zeroLengthRows = emptyRows.updated(6, Row(
      occupied = true,
      rid = Id(value = 6),
      status = "Issued",
      rowValid = true,
      insn = 0x55,
      len = 0))
    val invalidRowPayload = emptyRows.updated(7, Row(
      occupied = true,
      rid = Id(value = 7),
      status = "Issued",
      rowValid = false,
      insn = 0x66,
      len = 4))

    val zeroLength = ROBRowCommitTraceLookupReference(
      queryValid = true,
      queryRid = Id(value = 6),
      rows = zeroLengthRows,
      sourceTraceEnable = true)
    val invalidPayload = ROBRowCommitTraceLookupReference(
      queryValid = true,
      queryRid = Id(value = 7),
      rows = invalidRowPayload,
      sourceTraceEnable = true)

    assert(zeroLength.blockedByMissingInstruction)
    assert(!zeroLength.instructionProviderValid)
    assert(invalidPayload.blockedByMissingInstruction)
    assert(!invalidPayload.instructionProviderValid)
  }

  test("ROBRowCommitTraceLookup elaborates read-only commit trace provider diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new ROBRowCommitTraceLookup(entries = 8))

    assert(sv.contains("module ROBRowCommitTraceLookup"))
    assert(sv.contains("io_result_instructionProviderValid"))
    assert(sv.contains("io_result_instructionRaw"))
    assert(sv.contains("io_result_sourceTraceProviderValid"))
    assert(sv.contains("io_result_blockedByNeedFlush"))
    assert(sv.contains("io_result_blockedByMissingInstruction"))
    assert(sv.contains("io_result_blockedBySourceTraceBeforeCompletion"))
  }
}
