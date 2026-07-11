package linxcore.lsu

import chisel3._
import chisel3.util.{log2Ceil, PopCount, PriorityEncoder, PriorityEncoderOH, UIntToOH}

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
  val nonFlushValid = Input(Bool())
  val nonFlushHeadBid = Input(UInt(traceParams.blockBidWidth.W))
  val nonFlushPrefixCount = Input(UInt(countWidth.W))

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
  val commitStoreBlockedByNonFlush = Output(Bool())
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
      row.mem.valid &&
      row.mem.isStore
  }

  private def stqRowIdentityValid(stqRow: STQEntryBankRow): Bool =
    stqRow.bid.valid &&
      stqRow.gid.valid &&
      stqRow.rid.valid

  private def sameCommitIdentity(commitRow: CommitTraceRow, stqRow: STQEntryBankRow): Bool =
    stqRowIdentityValid(stqRow) &&
      resize(commitRow.identity.bid, ptrWidth) === stqRow.bid.value &&
      resize(commitRow.identity.gid, ptrWidth) === stqRow.gid.value &&
      resize(commitRow.identity.rid, ptrWidth) === stqRow.rid.value

  private def markable(row: STQEntryBankRow): Bool =
    row.valid &&
      row.rid.valid &&
      row.status === STQEntryStatus.Wait &&
      row.storeType === STQStoreType.All &&
      row.addrReady &&
      row.dataReady &&
      row.stid === resize(io.activeStid, stidWidth)

  private def blockInNonFlushPrefix(row: CommitTraceRow): Bool = {
    val distance = row.blockBid - io.nonFlushHeadBid
    io.nonFlushValid && row.blockBidValid &&
      distance < io.nonFlushPrefixCount.pad(traceParams.blockBidWidth)
  }

  val pendingMark = RegInit(0.U(entries.W))
  val pendingFree = RegInit(0.U(entries.W))
  val pendingCommitValid = RegInit(VecInit(Seq.fill(entries)(false.B)))
  val pendingCommitBid = Reg(Vec(entries, UInt(ptrWidth.W)))
  val pendingCommitGid = Reg(Vec(entries, UInt(ptrWidth.W)))
  val pendingCommitRid = Reg(Vec(entries, UInt(ptrWidth.W)))
  val pendingCommitStid = Reg(Vec(entries, UInt(stidWidth.W)))
  val pendingCommitBlockBidValid = RegInit(VecInit(Seq.fill(entries)(false.B)))
  val pendingCommitBlockBid = Reg(Vec(entries, UInt(traceParams.blockBidWidth.W)))

  private def pendingBlockInNonFlushPrefix(idx: Int): Bool = {
    val distance = pendingCommitBlockBid(idx) - io.nonFlushHeadBid
    io.nonFlushValid && pendingCommitBlockBidValid(idx) &&
      pendingCommitStid(idx) === io.activeStid &&
      distance < io.nonFlushPrefixCount.pad(traceParams.blockBidWidth)
  }

  private def samePendingIdentity(idx: Int, row: STQEntryBankRow): Bool =
    pendingCommitValid(idx) &&
      pendingCommitStid(idx) === row.stid &&
      pendingBlockInNonFlushPrefix(idx) &&
      stqRowIdentityValid(row) &&
      pendingCommitBid(idx) === row.bid.value &&
      pendingCommitGid(idx) === row.gid.value &&
      pendingCommitRid(idx) === row.rid.value

  private def samePendingCommit(idx: Int, commitRow: CommitTraceRow): Bool =
    pendingCommitValid(idx) &&
      pendingCommitStid(idx) === io.activeStid &&
      pendingCommitBlockBidValid(idx) && commitRow.blockBidValid &&
      pendingCommitBlockBid(idx) === commitRow.blockBid &&
      pendingCommitBid(idx) === resize(commitRow.identity.bid, ptrWidth) &&
      pendingCommitGid(idx) === resize(commitRow.identity.gid, ptrWidth) &&
      pendingCommitRid(idx) === resize(commitRow.identity.rid, ptrWidth)

  val commitStoreVec = VecInit((0 until traceParams.commitWidth).map(commitStore))
  val slotMatchVec = Wire(Vec(traceParams.commitWidth, Bool()))
  for (slot <- 0 until traceParams.commitWidth) {
    slotMatchVec(slot) :=
      commitStoreVec(slot) &&
        blockInNonFlushPrefix(io.commit.rows(slot)) &&
        VecInit((0 until entries).map(idx => markable(io.stqRows(idx)) && sameCommitIdentity(io.commit.rows(slot), io.stqRows(idx)))).asUInt.orR
  }

  val matchVec = Wire(Vec(entries, Bool()))
  for (idx <- 0 until entries) {
    matchVec(idx) :=
      markable(io.stqRows(idx)) &&
        (VecInit((0 until traceParams.commitWidth).map { slot =>
          commitStoreVec(slot) &&
            blockInNonFlushPrefix(io.commit.rows(slot)) &&
            sameCommitIdentity(io.commit.rows(slot), io.stqRows(idx))
        }).asUInt.orR ||
          VecInit((0 until entries).map(pendingIdx => samePendingIdentity(pendingIdx, io.stqRows(idx)))).asUInt.orR)
  }
  val matchMask = matchVec.asUInt

  val pendingMatchedVec = Wire(Vec(entries, Bool()))
  for (pendingIdx <- 0 until entries) {
    pendingMatchedVec(pendingIdx) :=
      pendingCommitValid(pendingIdx) &&
        VecInit((0 until entries).map(rowIdx => markable(io.stqRows(rowIdx)) && samePendingIdentity(pendingIdx, io.stqRows(rowIdx)))).asUInt.orR
  }
  val bufferStoreVec = Wire(Vec(traceParams.commitWidth, Bool()))
  for (slot <- 0 until traceParams.commitWidth) {
    val alreadyPending =
      VecInit((0 until entries).map(idx => samePendingCommit(idx, io.commit.rows(slot)))).asUInt.orR
    bufferStoreVec(slot) := commitStoreVec(slot) && !slotMatchVec(slot) && !alreadyPending
  }
  val bufferStoreMask = bufferStoreVec.asUInt
  val bufferStoreValid = bufferStoreMask.orR
  val bufferStoreSlot = PriorityEncoder(bufferStoreMask)

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
    for (idx <- 0 until entries) {
      pendingCommitValid(idx) := false.B
    }
  }.otherwise {
    val nextMark = (pendingMark | matchMask) & ~Mux(markAccepted, markOH, 0.U(entries.W))
    val nextFree = Mux(
      io.directFreeEnable,
      (pendingFree | Mux(markAccepted, markOH, 0.U(entries.W))) & ~Mux(freeAccepted, freeOH, 0.U(entries.W)),
      0.U(entries.W))
    pendingMark := nextMark
    pendingFree := nextFree

    val basePendingValid = Wire(Vec(entries, Bool()))
    val nextPendingValid = Wire(Vec(entries, Bool()))
    val nextPendingBid = Wire(Vec(entries, UInt(ptrWidth.W)))
    val nextPendingGid = Wire(Vec(entries, UInt(ptrWidth.W)))
    val nextPendingRid = Wire(Vec(entries, UInt(ptrWidth.W)))
    val nextPendingStid = Wire(Vec(entries, UInt(stidWidth.W)))
    val nextPendingBlockBidValid = Wire(Vec(entries, Bool()))
    val nextPendingBlockBid = Wire(Vec(entries, UInt(traceParams.blockBidWidth.W)))
    for (idx <- 0 until entries) {
      basePendingValid(idx) := pendingCommitValid(idx) && !pendingMatchedVec(idx)
      nextPendingValid(idx) := basePendingValid(idx)
      nextPendingBid(idx) := pendingCommitBid(idx)
      nextPendingGid(idx) := pendingCommitGid(idx)
      nextPendingRid(idx) := pendingCommitRid(idx)
      nextPendingStid(idx) := pendingCommitStid(idx)
      nextPendingBlockBidValid(idx) := pendingCommitBlockBidValid(idx)
      nextPendingBlockBid(idx) := pendingCommitBlockBid(idx)
    }
    val pendingIdentityFreeMask = VecInit((0 until entries).map(idx => !basePendingValid(idx))).asUInt
    val pendingIdentityFreeOH = PriorityEncoderOH(pendingIdentityFreeMask)
    val bufferRow = io.commit.rows(bufferStoreSlot)
    for (idx <- 0 until entries) {
      when(bufferStoreValid && pendingIdentityFreeOH(idx)) {
        nextPendingValid(idx) := true.B
        nextPendingBid(idx) := resize(bufferRow.identity.bid, ptrWidth)
        nextPendingGid(idx) := resize(bufferRow.identity.gid, ptrWidth)
        nextPendingRid(idx) := resize(bufferRow.identity.rid, ptrWidth)
        nextPendingStid(idx) := io.activeStid
        nextPendingBlockBidValid(idx) := bufferRow.blockBidValid
        nextPendingBlockBid(idx) := bufferRow.blockBid
      }
    }
    for (idx <- 0 until entries) {
      pendingCommitValid(idx) := nextPendingValid(idx)
      pendingCommitBid(idx) := nextPendingBid(idx)
      pendingCommitGid(idx) := nextPendingGid(idx)
      pendingCommitRid(idx) := nextPendingRid(idx)
      pendingCommitStid(idx) := nextPendingStid(idx)
      pendingCommitBlockBidValid(idx) := nextPendingBlockBidValid(idx)
      pendingCommitBlockBid(idx) := nextPendingBlockBid(idx)
    }
  }

  val unmatchedSlotVec = VecInit((0 until traceParams.commitWidth).map(slot => commitStoreVec(slot) && !slotMatchVec(slot)))
  io.commitStoreSeen := commitStoreVec.asUInt.orR
  io.commitStoreMatched := slotMatchVec.asUInt.orR
  io.commitStoreUnmatched := unmatchedSlotVec.asUInt.orR
  io.commitStoreBlockedByNonFlush := VecInit((0 until traceParams.commitWidth).map { slot =>
    commitStoreVec(slot) && !blockInNonFlushPrefix(io.commit.rows(slot))
  }).asUInt.orR
  io.matchMask := matchMask
  io.pendingMarkMask := pendingMark
  io.pendingFreeMask := pendingFree
  io.pendingMarkCount := PopCount(pendingMark)
  io.pendingFreeCount := PopCount(pendingFree)
  io.markBlocked := markValid && !io.markCommitAccepted
  io.freeBlocked := freeValid && !io.commitFreeAccepted
  io.idle := !pendingMark.orR && (!io.directFreeEnable || !pendingFree.orR)
}
