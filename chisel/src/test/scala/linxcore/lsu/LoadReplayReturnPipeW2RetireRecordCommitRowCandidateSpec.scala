package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

class LoadReplayReturnPipeW2RetireRecordCommitRowCandidateSpec extends AnyFunSuite {
  import LoadReplayReturnPipeW2CommitRowCandidateReference._

  test("retained record uses load-return payload as a dormant commit-row candidate") {
    val result = LoadReplayReturnPipeW2CommitRowCandidateReference(
      enable = true,
      flush = false,
      rowFillEnable = false,
      slotOccupied = true,
      slotTargetIsAgu = false,
      slotTargetIsLda = true,
      slotBid = Id(value = 2),
      slotGid = Id(value = 3),
      slotRid = Id(value = 4),
      slotPc = BigInt("40001000", 16),
      slotAddr = BigInt("40002000", 16),
      slotSize = 8,
      slotDst = Destination(archTag = 11),
      slotData = BigInt("1122334455667788", 16),
      instructionValid = true,
      instructionRaw = BigInt("123456789abc", 16),
      instructionLen = 6,
      sourceTraceValid = true,
      source0 = Source(valid = true, reg = 1, data = 42),
      source1 = Source(valid = true, reg = 2, data = 84))

    assert(result.rowFillCandidateValid)
    assert(!result.completeRowValid)
    assert(result.blockedByRowFillDisabled)
    assert(result.row.rid == 4)
    assert(result.row.nextPc == BigInt("40001006", 16))
    assert(result.row.memRdata == BigInt("1122334455667788", 16))
  }

  test("retained record still reports missing instruction metadata and source trace") {
    val noMetadata = LoadReplayReturnPipeW2CommitRowCandidateReference(
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
      rowFillEnable = false,
      slotOccupied = true,
      slotTargetIsAgu = false,
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
      sourceTraceValid = false)

    assert(!noMetadata.rowFillCandidateValid)
    assert(noMetadata.blockedByNoInstructionMetadata)
    assert(!noSource.rowFillCandidateValid)
    assert(noSource.blockedByNoSourceTrace)
  }

  test("Chisel LoadReplayReturnPipeW2RetireRecordCommitRowCandidate elaborates diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(
      new LoadReplayReturnPipeW2RetireRecordCommitRowCandidate(idEntries = 8, sizeWidth = 4))

    assert(sv.contains("module LoadReplayReturnPipeW2RetireRecordCommitRowCandidate"))
    assert(sv.contains("io_recordValid"))
    assert(sv.contains("io_rowFillCandidateValid"))
    assert(sv.contains("io_blockedByNoRecord"))
    assert(sv.contains("io_blockedByRowFillDisabled"))
  }
}
