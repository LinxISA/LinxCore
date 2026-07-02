package linxcore.lsu

import chisel3._
import chisel3.util.{log2Ceil, PopCount, PriorityEncoder, UIntToOH}

import linxcore.commit.{CommitTraceParams, CommitTracePort, CommitTraceRow}
import linxcore.rob.ROBID

class ReducedStoreCommitFreeOwnerIO(
    val entries: Int,
    val traceParams: CommitTraceParams = CommitTraceParams(),
    val addrWidth: Int = 64,
    val dataWidth: Int = 64,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val sizeWidth: Int = 4,
    val simtLaneWidth: Int = 8,
    val mapQDepth: Int = 32)
    extends Bundle {
  private val ptrWidth = log2Ceil(entries)
  private val countWidth = log2Ceil(entries + 1)

  val enable = Input(Bool())
  val directFreeEnable = Input(Bool())
  val flushValid = Input(Bool())
  val activeStid = Input(UInt(stidWidth.W))

  val commit = Input(new CommitTracePort(traceParams))
  val commitValidMask = Input(UInt(traceParams.commitWidth.W))
  val stqRows = Input(Vec(entries, new STQEntryBankRow(entries, addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth, sizeWidth, simtLaneWidth, mapQDepth)))

  val markCommitValid = Output(Bool())
  val markCommitIndex = Output(UInt(ptrWidth.W))
  val markCommitAccepted = Input(Bool())
  val markCommitIgnored = Input(Bool())

  val commitFreeValid = Output(Bool())
  val commitFreeIndex = Output(UInt(ptrWidth.W))
  val commitFreeAccepted = Input(Bool())
  val commitFreeIgnored = Input(Bool())
  val commitFreeAcceptedMask = Input(UInt(entries.W))
  val commitFreeIgnoredMask = Input(UInt(entries.W))

  val commitStoreSeen = Output(Bool())
  val commitStoreMatched = Output(Bool())
  val commitStoreUnmatched = Output(Bool())
  val matchMask = Output(UInt(entries.W))
  val pendingMarkMask = Output(UInt(entries.W))
  val pendingFreeMask = Output(UInt(entries.W))
  val pendingMarkCount = Output(UInt(countWidth.W))
  val pendingFreeCount = Output(UInt(countWidth.W))
  val markBlocked = Output(Bool())
  val freeBlocked = Output(Bool())
  val idle = Output(Bool())
}

class ReducedStoreCommitFreeOwner(
    val entries: Int = 16,
    val traceParams: CommitTraceParams = CommitTraceParams(),
    val addrWidth: Int = 64,
    val dataWidth: Int = 64,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val sizeWidth: Int = 4,
    val simtLaneWidth: Int = 8,
    val mapQDepth: Int = 32)
    extends Module {
  require(entries > 1, "reduced store commit/free owner needs at least two STQ entries")
  require((entries & (entries - 1)) == 0, "reduced store commit/free owner entries must be a power of two")
  require(traceParams.robValueWidth >= log2Ceil(entries), "commit ROB value width must cover STQ/ROB indices")

  private val ptrWidth = log2Ceil(entries)

  val io = IO(new ReducedStoreCommitFreeOwnerIO(
    entries,
    traceParams,
    addrWidth,
    dataWidth,
    peIdWidth,
    stidWidth,
    tidWidth,
    sizeWidth,
    simtLaneWidth,
    mapQDepth
  ))

  private def resize(value: UInt, width: Int): UInt =
    if (width <= value.getWidth) value(width - 1, 0) else value.pad(width)

  private def commitStore(slot: Int): Bool = {
    val row = io.commit.rows(slot)
    io.enable &&
      io.commitValidMask(slot) &&
      row.valid &&
      row.rob.valid &&
      row.mem.valid &&
      row.mem.isStore
  }

  private def sameRobRow(commitRow: CommitTraceRow, stqRow: STQEntryBankRow): Bool =
    stqRow.rid.valid &&
      commitRow.rob.wrap === stqRow.rid.wrap &&
      resize(commitRow.rob.value, ptrWidth) === stqRow.rid.value

  private def markable(row: STQEntryBankRow): Bool =
    row.valid &&
      row.rid.valid &&
      row.status === STQEntryStatus.Wait &&
      row.storeType === STQStoreType.All &&
      row.addrReady &&
      row.dataReady &&
      row.stid === resize(io.activeStid, stidWidth)

  val pendingMark = RegInit(0.U(entries.W))
  val pendingFree = RegInit(0.U(entries.W))

  val commitStoreVec = VecInit((0 until traceParams.commitWidth).map(commitStore))
  val slotMatchVec = Wire(Vec(traceParams.commitWidth, Bool()))
  for (slot <- 0 until traceParams.commitWidth) {
    slotMatchVec(slot) :=
      commitStoreVec(slot) &&
        VecInit((0 until entries).map(idx => markable(io.stqRows(idx)) && sameRobRow(io.commit.rows(slot), io.stqRows(idx)))).asUInt.orR
  }

  val matchVec = Wire(Vec(entries, Bool()))
  for (idx <- 0 until entries) {
    matchVec(idx) :=
      markable(io.stqRows(idx)) &&
        VecInit((0 until traceParams.commitWidth).map(slot => commitStoreVec(slot) && sameRobRow(io.commit.rows(slot), io.stqRows(idx)))).asUInt.orR
  }
  val matchMask = matchVec.asUInt

  val markIndex = PriorityEncoder(pendingMark)
  val markValid = io.enable && !io.flushValid && pendingMark.orR
  val markOH = Mux(markValid, UIntToOH(markIndex, entries), 0.U(entries.W))

  val freeIndex = PriorityEncoder(pendingFree)
  val freeValid = io.enable && io.directFreeEnable && !io.flushValid && pendingFree.orR
  val freeOH = Mux(freeValid, UIntToOH(freeIndex, entries), 0.U(entries.W))

  io.markCommitValid := markValid
  io.markCommitIndex := markIndex
  io.commitFreeValid := freeValid
  io.commitFreeIndex := freeIndex

  val markAccepted = markValid && io.markCommitAccepted
  val freeAccepted = freeValid && io.commitFreeAccepted

  when(!io.enable || io.flushValid) {
    pendingMark := 0.U
    pendingFree := 0.U
  }.otherwise {
    val nextMark = (pendingMark | matchMask) & ~Mux(markAccepted, markOH, 0.U(entries.W))
    val nextFree = Mux(
      io.directFreeEnable,
      (pendingFree | Mux(markAccepted, markOH, 0.U(entries.W))) & ~Mux(freeAccepted, freeOH, 0.U(entries.W)),
      0.U(entries.W))
    pendingMark := nextMark
    pendingFree := nextFree
  }

  val unmatchedSlotVec = VecInit((0 until traceParams.commitWidth).map(slot => commitStoreVec(slot) && !slotMatchVec(slot)))
  io.commitStoreSeen := commitStoreVec.asUInt.orR
  io.commitStoreMatched := slotMatchVec.asUInt.orR
  io.commitStoreUnmatched := unmatchedSlotVec.asUInt.orR
  io.matchMask := matchMask
  io.pendingMarkMask := pendingMark
  io.pendingFreeMask := pendingFree
  io.pendingMarkCount := PopCount(pendingMark)
  io.pendingFreeCount := PopCount(pendingFree)
  io.markBlocked := markValid && !io.markCommitAccepted
  io.freeBlocked := freeValid && !io.commitFreeAccepted
  io.idle := !pendingMark.orR && (!io.directFreeEnable || !pendingFree.orR)
}
