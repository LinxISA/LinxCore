package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnPipeW2CommitRowCandidateReference {
  final case class Id(valid: Boolean = true, wrap: Boolean = false, value: Int = 0)
  final case class Destination(valid: Boolean = true, kind: String = "gpr", archTag: Int = 0)
  final case class Source(valid: Boolean = false, reg: Int = 0, data: BigInt = 0)

  final case class Row(
      valid: Boolean,
      bid: Int,
      gid: Int,
      rid: Int,
      pc: BigInt,
      insn: BigInt,
      len: Int,
      nextPc: BigInt,
      wbValid: Boolean,
      wbReg: Int,
      wbData: BigInt,
      memValid: Boolean,
      memAddr: BigInt,
      memRdata: BigInt,
      memSize: Int,
      src0Valid: Boolean,
      src1Valid: Boolean)

  final case class Result(
      active: Boolean,
      candidateValid: Boolean,
      targetValid: Boolean,
      identityValid: Boolean,
      metadataReady: Boolean,
      sourceTraceReady: Boolean,
      sizeSupported: Boolean,
      destinationGpr: Boolean,
      rowFillCandidateValid: Boolean,
      completeRowValid: Boolean,
      row: Row,
      blockedByDisabled: Boolean,
      blockedByFlush: Boolean,
      blockedByNoSlot: Boolean,
      blockedByInvalidTarget: Boolean,
      blockedByInvalidIdentity: Boolean,
      blockedByNoInstructionMetadata: Boolean,
      blockedByNoSourceTrace: Boolean,
      blockedByInvalidSize: Boolean,
      blockedByNoDestination: Boolean,
      blockedByNonGprDestination: Boolean,
      blockedByRowFillDisabled: Boolean)

  def apply(
      enable: Boolean,
      flush: Boolean,
      rowFillEnable: Boolean,
      slotOccupied: Boolean,
      slotTargetIsAgu: Boolean,
      slotTargetIsLda: Boolean,
      slotBid: Id,
      slotGid: Id,
      slotRid: Id,
      slotPc: BigInt,
      slotAddr: BigInt,
      slotSize: Int,
      slotDst: Destination,
      slotData: BigInt,
      instructionValid: Boolean,
      instructionRaw: BigInt,
      instructionLen: Int,
      sourceTraceValid: Boolean,
      source0: Source = Source(),
      source1: Source = Source()): Result = {
    val active = enable && !flush
    val candidateValid = active && slotOccupied
    val targetValid = slotTargetIsAgu ^ slotTargetIsLda
    val identityValid = slotBid.valid && slotGid.valid && slotRid.valid
    val metadataReady = instructionValid && instructionLen != 0
    val sourceTraceReady = sourceTraceValid
    val sizeSupported = Set(1, 2, 4, 8).contains(slotSize)
    val hasDestination = slotDst.valid && slotDst.kind != "none"
    val destinationGpr = hasDestination && slotDst.kind == "gpr"
    val rowFillCandidateValid =
      candidateValid &&
        targetValid &&
        identityValid &&
        metadataReady &&
        sourceTraceReady &&
        sizeSupported &&
        destinationGpr
    val completeRowValid = rowFillCandidateValid && rowFillEnable
    val row = Row(
      valid = completeRowValid,
      bid = slotBid.value,
      gid = slotGid.value,
      rid = slotRid.value,
      pc = slotPc,
      insn = instructionRaw,
      len = instructionLen,
      nextPc = slotPc + instructionLen,
      wbValid = completeRowValid,
      wbReg = slotDst.archTag,
      wbData = slotData,
      memValid = completeRowValid,
      memAddr = slotAddr,
      memRdata = slotData,
      memSize = slotSize & 0xf,
      src0Valid = completeRowValid && source0.valid,
      src1Valid = completeRowValid && source1.valid)

    Result(
      active = active,
      candidateValid = candidateValid,
      targetValid = candidateValid && targetValid,
      identityValid = candidateValid && targetValid && identityValid,
      metadataReady = candidateValid && targetValid && identityValid && metadataReady,
      sourceTraceReady = candidateValid && targetValid && identityValid && metadataReady && sourceTraceReady,
      sizeSupported = candidateValid && targetValid && identityValid && metadataReady && sourceTraceReady && sizeSupported,
      destinationGpr =
        candidateValid && targetValid && identityValid && metadataReady && sourceTraceReady && sizeSupported && destinationGpr,
      rowFillCandidateValid = rowFillCandidateValid,
      completeRowValid = completeRowValid,
      row = row,
      blockedByDisabled = !enable && slotOccupied,
      blockedByFlush = enable && flush && slotOccupied,
      blockedByNoSlot = active && !slotOccupied,
      blockedByInvalidTarget = candidateValid && !targetValid,
      blockedByInvalidIdentity = candidateValid && targetValid && !identityValid,
      blockedByNoInstructionMetadata = candidateValid && targetValid && identityValid && !metadataReady,
      blockedByNoSourceTrace = candidateValid && targetValid && identityValid && metadataReady && !sourceTraceReady,
      blockedByInvalidSize = candidateValid && targetValid && identityValid && metadataReady && sourceTraceReady && !sizeSupported,
      blockedByNoDestination =
        candidateValid && targetValid && identityValid && metadataReady && sourceTraceReady && sizeSupported && !hasDestination,
      blockedByNonGprDestination =
        candidateValid && targetValid && identityValid && metadataReady && sourceTraceReady && sizeSupported &&
          slotDst.valid && !destinationGpr,
      blockedByRowFillDisabled = rowFillCandidateValid && !rowFillEnable)
  }
}

class LoadReplayReturnPipeW2CommitRowCandidateSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2CommitRowCandidateReference._

  test("builds a load commit row when slot identity metadata source trace and fill enable agree") {
    val result = LoadReplayReturnPipeW2CommitRowCandidateReference(
      enable = true,
      flush = false,
      rowFillEnable = true,
      slotOccupied = true,
      slotTargetIsAgu = true,
      slotTargetIsLda = false,
      slotBid = Id(value = 1),
      slotGid = Id(value = 2),
      slotRid = Id(wrap = true, value = 3),
      slotPc = BigInt("40001000", 16),
      slotAddr = BigInt("40002008", 16),
      slotSize = 8,
      slotDst = Destination(archTag = 10),
      slotData = BigInt("1122334455667788", 16),
      instructionValid = true,
      instructionRaw = BigInt("123456789abc", 16),
      instructionLen = 6,
      sourceTraceValid = true,
      source0 = Source(valid = true, reg = 1, data = 42))

    assert(result.rowFillCandidateValid)
    assert(result.completeRowValid)
    assert(result.row.valid)
    assert(result.row.rid == 3)
    assert(result.row.nextPc == BigInt("40001006", 16))
    assert(result.row.wbValid)
    assert(result.row.wbReg == 10)
    assert(result.row.wbData == BigInt("1122334455667788", 16))
    assert(result.row.memValid)
    assert(result.row.memAddr == BigInt("40002008", 16))
    assert(result.row.memRdata == BigInt("1122334455667788", 16))
    assert(result.row.memSize == 8)
    assert(result.row.src0Valid)
  }

  test("keeps a complete row dormant when the row-fill enable remains false") {
    val result = LoadReplayReturnPipeW2CommitRowCandidateReference(
      enable = true,
      flush = false,
      rowFillEnable = false,
      slotOccupied = true,
      slotTargetIsAgu = false,
      slotTargetIsLda = true,
      slotBid = Id(value = 1),
      slotGid = Id(value = 2),
      slotRid = Id(value = 3),
      slotPc = 16,
      slotAddr = 32,
      slotSize = 4,
      slotDst = Destination(archTag = 5),
      slotData = 99,
      instructionValid = true,
      instructionRaw = 0xabc,
      instructionLen = 4,
      sourceTraceValid = true)

    assert(result.rowFillCandidateValid)
    assert(!result.completeRowValid)
    assert(!result.row.valid)
    assert(result.blockedByRowFillDisabled)
  }

  test("blocks row fill until instruction metadata and source trace are available") {
    val noMetadata = LoadReplayReturnPipeW2CommitRowCandidateReference(
      enable = true,
      flush = false,
      rowFillEnable = true,
      slotOccupied = true,
      slotTargetIsAgu = true,
      slotTargetIsLda = false,
      slotBid = Id(value = 1),
      slotGid = Id(value = 2),
      slotRid = Id(value = 3),
      slotPc = 16,
      slotAddr = 32,
      slotSize = 8,
      slotDst = Destination(archTag = 5),
      slotData = 99,
      instructionValid = false,
      instructionRaw = 0,
      instructionLen = 0,
      sourceTraceValid = true)
    val noSource = LoadReplayReturnPipeW2CommitRowCandidateReference(
      enable = true,
      flush = false,
      rowFillEnable = true,
      slotOccupied = true,
      slotTargetIsAgu = true,
      slotTargetIsLda = false,
      slotBid = Id(value = 1),
      slotGid = Id(value = 2),
      slotRid = Id(value = 3),
      slotPc = 16,
      slotAddr = 32,
      slotSize = 8,
      slotDst = Destination(archTag = 5),
      slotData = 99,
      instructionValid = true,
      instructionRaw = 0xabc,
      instructionLen = 4,
      sourceTraceValid = false)

    assert(!noMetadata.rowFillCandidateValid)
    assert(noMetadata.blockedByNoInstructionMetadata)
    assert(!noSource.rowFillCandidateValid)
    assert(noSource.blockedByNoSourceTrace)
  }

  test("reports invalid target identity size and destination blockers") {
    val invalidTarget = LoadReplayReturnPipeW2CommitRowCandidateReference(
      enable = true,
      flush = false,
      rowFillEnable = true,
      slotOccupied = true,
      slotTargetIsAgu = true,
      slotTargetIsLda = true,
      slotBid = Id(value = 1),
      slotGid = Id(value = 2),
      slotRid = Id(value = 3),
      slotPc = 16,
      slotAddr = 32,
      slotSize = 8,
      slotDst = Destination(archTag = 5),
      slotData = 99,
      instructionValid = true,
      instructionRaw = 0xabc,
      instructionLen = 4,
      sourceTraceValid = true)
    val invalidIdentity = LoadReplayReturnPipeW2CommitRowCandidateReference(
      enable = true,
      flush = false,
      rowFillEnable = true,
      slotOccupied = true,
      slotTargetIsAgu = true,
      slotTargetIsLda = false,
      slotBid = Id(valid = false, value = 1),
      slotGid = Id(value = 2),
      slotRid = Id(value = 3),
      slotPc = 16,
      slotAddr = 32,
      slotSize = 8,
      slotDst = Destination(archTag = 5),
      slotData = 99,
      instructionValid = true,
      instructionRaw = 0xabc,
      instructionLen = 4,
      sourceTraceValid = true)
    val invalidSize = LoadReplayReturnPipeW2CommitRowCandidateReference(
      enable = true,
      flush = false,
      rowFillEnable = true,
      slotOccupied = true,
      slotTargetIsAgu = true,
      slotTargetIsLda = false,
      slotBid = Id(value = 1),
      slotGid = Id(value = 2),
      slotRid = Id(value = 3),
      slotPc = 16,
      slotAddr = 32,
      slotSize = 3,
      slotDst = Destination(archTag = 5),
      slotData = 99,
      instructionValid = true,
      instructionRaw = 0xabc,
      instructionLen = 4,
      sourceTraceValid = true)
    val nonGpr = LoadReplayReturnPipeW2CommitRowCandidateReference(
      enable = true,
      flush = false,
      rowFillEnable = true,
      slotOccupied = true,
      slotTargetIsAgu = true,
      slotTargetIsLda = false,
      slotBid = Id(value = 1),
      slotGid = Id(value = 2),
      slotRid = Id(value = 3),
      slotPc = 16,
      slotAddr = 32,
      slotSize = 8,
      slotDst = Destination(valid = true, kind = "t", archTag = 5),
      slotData = 99,
      instructionValid = true,
      instructionRaw = 0xabc,
      instructionLen = 4,
      sourceTraceValid = true)

    assert(invalidTarget.blockedByInvalidTarget)
    assert(invalidIdentity.blockedByInvalidIdentity)
    assert(invalidSize.blockedByInvalidSize)
    assert(nonGpr.blockedByNonGprDestination)
  }

  test("suppresses disabled flushed and empty slots") {
    val disabled = LoadReplayReturnPipeW2CommitRowCandidateReference(
      enable = false,
      flush = false,
      rowFillEnable = true,
      slotOccupied = true,
      slotTargetIsAgu = true,
      slotTargetIsLda = false,
      slotBid = Id(),
      slotGid = Id(),
      slotRid = Id(),
      slotPc = 0,
      slotAddr = 0,
      slotSize = 8,
      slotDst = Destination(),
      slotData = 0,
      instructionValid = true,
      instructionRaw = 0,
      instructionLen = 4,
      sourceTraceValid = true)
    val empty = LoadReplayReturnPipeW2CommitRowCandidateReference(
      enable = true,
      flush = false,
      rowFillEnable = true,
      slotOccupied = false,
      slotTargetIsAgu = true,
      slotTargetIsLda = false,
      slotBid = Id(),
      slotGid = Id(),
      slotRid = Id(),
      slotPc = 0,
      slotAddr = 0,
      slotSize = 8,
      slotDst = Destination(),
      slotData = 0,
      instructionValid = true,
      instructionRaw = 0,
      instructionLen = 4,
      sourceTraceValid = true)
    val flushedActual = LoadReplayReturnPipeW2CommitRowCandidateReference(
      enable = true,
      flush = true,
      rowFillEnable = true,
      slotOccupied = true,
      slotTargetIsAgu = true,
      slotTargetIsLda = false,
      slotBid = Id(),
      slotGid = Id(),
      slotRid = Id(),
      slotPc = 0,
      slotAddr = 0,
      slotSize = 8,
      slotDst = Destination(),
      slotData = 0,
      instructionValid = true,
      instructionRaw = 0,
      instructionLen = 4,
      sourceTraceValid = true)

    assert(disabled.blockedByDisabled)
    assert(!disabled.completeRowValid)
    assert(flushedActual.blockedByFlush)
    assert(!flushedActual.completeRowValid)
    assert(empty.blockedByNoSlot)
    assert(!empty.completeRowValid)
  }

  test("Chisel LoadReplayReturnPipeW2CommitRowCandidate elaborates row-fill diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(
      new LoadReplayReturnPipeW2CommitRowCandidate(idEntries = 8, sizeWidth = 4))

    assert(sv.contains("module LoadReplayReturnPipeW2CommitRowCandidate"))
    assert(sv.contains("io_rowFillCandidateValid"))
    assert(sv.contains("io_completeRowValid"))
    assert(sv.contains("io_completeRow_wb_valid"))
    assert(sv.contains("io_completeRow_mem_rdata"))
    assert(sv.contains("io_blockedByNoInstructionMetadata"))
    assert(sv.contains("io_blockedByRowFillDisabled"))
  }
}
