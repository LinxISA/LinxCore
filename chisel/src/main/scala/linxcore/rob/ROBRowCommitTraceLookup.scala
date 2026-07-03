package linxcore.rob

import chisel3._
import chisel3.util.log2Ceil

import linxcore.commit.{CommitOperandTrace, CommitTraceParams, CommitTraceRow}

class ROBRowCommitTraceLookupResult(
    val entries: Int,
    val traceParams: CommitTraceParams)
    extends Bundle {
  require(entries > 1, "entries must be greater than one")
  require((entries & (entries - 1)) == 0, "entries must be a power of two")

  val queryValid = Bool()
  val rowValid = Bool()
  val ridMatch = Bool()
  val status = ROBEntryStatus()
  val needFlush = Bool()
  val row = new CommitTraceRow(traceParams)
  val instructionProviderValid = Bool()
  val instructionRaw = UInt(traceParams.insnWidth.W)
  val instructionLen = UInt(traceParams.lenWidth.W)
  val sourceTraceProviderValid = Bool()
  val source0 = new CommitOperandTrace(traceParams)
  val source1 = new CommitOperandTrace(traceParams)
  val blockedByInvalidRid = Bool()
  val blockedByFree = Bool()
  val blockedByStaleRid = Bool()
  val blockedByNeedFlush = Bool()
  val blockedByMissingInstruction = Bool()
  val blockedBySourceTraceDisabled = Bool()
}

class ROBRowCommitTraceLookupIO(
    val entries: Int,
    val traceParams: CommitTraceParams)
    extends Bundle {
  require(entries > 1, "entries must be greater than one")
  require((entries & (entries - 1)) == 0, "entries must be a power of two")

  val queryValid = Input(Bool())
  val queryRid = Input(new ROBID(entries))
  val rowValidMask = Input(UInt(entries.W))
  val rowRid = Input(Vec(entries, new ROBID(entries)))
  val rowStatus = Input(Vec(entries, ROBEntryStatus()))
  val rows = Input(Vec(entries, new CommitTraceRow(traceParams)))
  val sourceTraceEnable = Input(Bool())

  val result = Output(new ROBRowCommitTraceLookupResult(entries, traceParams))
}

class ROBRowCommitTraceLookup(
    val entries: Int = 16,
    val traceParams: CommitTraceParams = CommitTraceParams())
    extends Module {
  require(entries > 1, "entries must be greater than one")
  require((entries & (entries - 1)) == 0, "entries must be a power of two")

  private val ptrWidth = log2Ceil(entries)

  val io = IO(new ROBRowCommitTraceLookupIO(entries, traceParams))

  val index = io.queryRid.value(ptrWidth - 1, 0)
  val slotOccupied = io.rowValidMask(index)
  val slotRidMatch = slotOccupied && ROBID.equal(io.rowRid(index), io.queryRid)
  val rowValid = io.queryValid && io.queryRid.valid && slotRidMatch
  val selectedStatus = Mux(rowValid, io.rowStatus(index), ROBEntryStatus.Free)
  val selectedRow = Wire(new CommitTraceRow(traceParams))
  selectedRow := Mux(rowValid, io.rows(index), 0.U.asTypeOf(new CommitTraceRow(traceParams)))
  val needFlush = rowValid && selectedStatus === ROBEntryStatus.NeedFlush
  val liveRow = rowValid && selectedRow.valid && !needFlush
  val instructionReady = liveRow && (selectedRow.len =/= 0.U)
  val sourceTraceReady = liveRow && io.sourceTraceEnable

  io.result.queryValid := io.queryValid
  io.result.rowValid := rowValid
  io.result.ridMatch := io.queryValid && io.queryRid.valid && slotRidMatch
  io.result.status := selectedStatus
  io.result.needFlush := needFlush
  io.result.row := selectedRow
  io.result.instructionProviderValid := instructionReady
  io.result.instructionRaw := Mux(instructionReady, selectedRow.insn, 0.U)
  io.result.instructionLen := Mux(instructionReady, selectedRow.len, 0.U)
  io.result.sourceTraceProviderValid := sourceTraceReady
  io.result.source0 := 0.U.asTypeOf(io.result.source0)
  io.result.source1 := 0.U.asTypeOf(io.result.source1)
  when(sourceTraceReady) {
    io.result.source0 := selectedRow.src0
    io.result.source1 := selectedRow.src1
  }
  io.result.blockedByInvalidRid := io.queryValid && !io.queryRid.valid
  io.result.blockedByFree := io.queryValid && io.queryRid.valid && !slotOccupied
  io.result.blockedByStaleRid := io.queryValid && io.queryRid.valid && slotOccupied && !slotRidMatch
  io.result.blockedByNeedFlush := needFlush
  io.result.blockedByMissingInstruction := rowValid && !needFlush && (!selectedRow.valid || selectedRow.len === 0.U)
  io.result.blockedBySourceTraceDisabled := liveRow && !io.sourceTraceEnable
}
