package linxcore.lsu

import chisel3._
import chisel3.util.{log2Ceil, PopCount, PriorityEncoder, PriorityEncoderOH, UIntToOH}

import linxcore.commit.{CommitTraceParams, CommitTracePort, CommitTraceRow}
import linxcore.common.LSIDOrder
import linxcore.rob.{ROBID, ROBMemoryOrderCommit}

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
    val mapQDepth: Int = 32,
    val lsidWidth: Int = 32,
    val robEntries: Int = 0)
    extends Bundle {
  private val identityEntries = if (robEntries > 0) robEntries else entries
  private val ptrWidth = log2Ceil(entries)
  private val countWidth = log2Ceil(entries + 1)

  val enable = Input(Bool())
  val directFreeEnable = Input(Bool())
  val flushValid = Input(Bool())
  val activeStid = Input(UInt(stidWidth.W))
  val nonFlushValid = Input(Bool())
  val nonFlushHeadBid = Input(UInt(traceParams.blockBidWidth.W))
  val nonFlushPrefixCount = Input(UInt(countWidth.W))
  val oldestBlockValid = Input(Bool())
  val oldestBlockBid = Input(UInt(traceParams.blockBidWidth.W))
  val oldestRobValid = Input(Bool())
  val oldestRobBid = Input(new ROBID(identityEntries))
  val oldestRobLsId = Input(UInt(lsidWidth.W))
  val oldestRobStid = Input(UInt(stidWidth.W))

  val commit = Input(new CommitTracePort(traceParams))
  val commitValidMask = Input(UInt(traceParams.commitWidth.W))
  val commitMemoryOrder = Input(Vec(traceParams.commitWidth, new ROBMemoryOrderCommit(identityEntries, lsidWidth)))
  val stqRows = Input(Vec(entries, new STQEntryBankRow(identityEntries, addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth, sizeWidth, simtLaneWidth, mapQDepth, 64, lsidWidth)))

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
  val commitStoreReleasedByEarlySafe = Output(Bool())
  val matchMask = Output(UInt(entries.W))
  val earlySafeMatchMask = Output(UInt(entries.W))
  val pendingCommitIdentityMask = Output(UInt(entries.W))
  val pendingCommitEarlySafeMask = Output(UInt(entries.W))
  val residentEarlySafeMask = Output(UInt(entries.W))
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
    val mapQDepth: Int = 32,
    val lsidWidth: Int = 32,
    val robEntries: Int = 0)
    extends Module {
  private val identityEntries = if (robEntries > 0) robEntries else entries
  require(entries > 1, "reduced store commit/free owner needs at least two STQ entries")
  require((entries & (entries - 1)) == 0, "reduced store commit/free owner entries must be a power of two")
  require(identityEntries > 1, "reduced store commit/free owner needs at least two ROB entries")
  require((identityEntries & (identityEntries - 1)) == 0,
    "reduced store commit/free owner ROB entries must be a power of two")
  require(traceParams.robValueWidth >= log2Ceil(identityEntries), "commit ROB value width must cover ROB identities")

  private val ptrWidth = log2Ceil(entries)
  private val identityWidth = log2Ceil(identityEntries)

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
    mapQDepth,
    lsidWidth,
    identityEntries
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
      resize(commitRow.identity.bid, identityWidth) === stqRow.bid.value &&
      resize(commitRow.identity.gid, identityWidth) === stqRow.gid.value &&
      resize(commitRow.identity.rid, identityWidth) === stqRow.rid.value

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

  private def scalarEarlySafe(
      blockBidValid: Bool,
      blockBid: UInt,
      storeLsIdValid: Bool,
      storeLsId: UInt): Bool =
    io.oldestBlockValid && blockBidValid && storeLsIdValid &&
      blockBid === io.oldestBlockBid &&
      VecInit((0 until traceParams.commitWidth).map { slot =>
        val frontier = io.commitMemoryOrder(slot)
        frontier.valid && io.commitValidMask(slot) &&
          io.commit.rows(slot).valid && io.commit.rows(slot).blockBidValid &&
          io.commit.rows(slot).blockBid === blockBid &&
          LSIDOrder.less(storeLsId, frontier.lsId)
      }).asUInt.orR

  val pendingMark = RegInit(0.U(entries.W))
  val pendingFree = RegInit(0.U(entries.W))
  val pendingCommitValid = RegInit(VecInit(Seq.fill(entries)(false.B)))
  val pendingCommitBid = Reg(Vec(entries, UInt(identityWidth.W)))
  val pendingCommitGid = Reg(Vec(entries, UInt(identityWidth.W)))
  val pendingCommitRid = Reg(Vec(entries, UInt(identityWidth.W)))
  val pendingCommitStid = Reg(Vec(entries, UInt(stidWidth.W)))
  val pendingCommitBlockBidValid = RegInit(VecInit(Seq.fill(entries)(false.B)))
  val pendingCommitBlockBid = Reg(Vec(entries, UInt(traceParams.blockBidWidth.W)))
  val pendingCommitLsId = Reg(Vec(entries, UInt(lsidWidth.W)))
  val pendingCommitEarlySafe = RegInit(VecInit(Seq.fill(entries)(false.B)))

  private def pendingBlockInNonFlushPrefix(idx: Int): Bool = {
    val distance = pendingCommitBlockBid(idx) - io.nonFlushHeadBid
    io.nonFlushValid && pendingCommitBlockBidValid(idx) &&
      pendingCommitStid(idx) === io.activeStid &&
      distance < io.nonFlushPrefixCount.pad(traceParams.blockBidWidth)
  }

  private def pendingAuthorized(idx: Int, row: STQEntryBankRow): Bool =
    pendingBlockInNonFlushPrefix(idx) ||
      (row.scalarIex && (pendingCommitEarlySafe(idx) || scalarEarlySafe(
        pendingCommitBlockBidValid(idx), pendingCommitBlockBid(idx), pendingCommitValid(idx), pendingCommitLsId(idx))))

  private def pendingEarlySafe(idx: Int, row: STQEntryBankRow): Bool =
    !pendingBlockInNonFlushPrefix(idx) && row.scalarIex &&
      (pendingCommitEarlySafe(idx) || scalarEarlySafe(
        pendingCommitBlockBidValid(idx), pendingCommitBlockBid(idx), pendingCommitValid(idx), pendingCommitLsId(idx)))

  private def samePendingIdentity(idx: Int, row: STQEntryBankRow): Bool =
    pendingCommitValid(idx) &&
      pendingCommitStid(idx) === row.stid &&
      pendingAuthorized(idx, row) &&
      stqRowIdentityValid(row) &&
      pendingCommitBid(idx) === row.bid.value &&
      pendingCommitGid(idx) === row.gid.value &&
      pendingCommitRid(idx) === row.rid.value

  private def samePendingCommit(idx: Int, commitRow: CommitTraceRow): Bool =
    pendingCommitValid(idx) &&
      pendingCommitStid(idx) === io.activeStid &&
      pendingCommitBlockBidValid(idx) && commitRow.blockBidValid &&
      pendingCommitBlockBid(idx) === commitRow.blockBid &&
      pendingCommitBid(idx) === resize(commitRow.identity.bid, identityWidth) &&
      pendingCommitGid(idx) === resize(commitRow.identity.gid, identityWidth) &&
      pendingCommitRid(idx) === resize(commitRow.identity.rid, identityWidth)

  val commitStoreVec = VecInit((0 until traceParams.commitWidth).map(commitStore))
  val currentStoreLsId = Wire(Vec(traceParams.commitWidth, UInt(lsidWidth.W)))
  val currentStoreEarlySafe = Wire(Vec(traceParams.commitWidth, Bool()))
  for (slot <- 0 until traceParams.commitWidth) {
    currentStoreLsId(slot) := io.commitMemoryOrder(slot).lsId
    currentStoreEarlySafe(slot) := scalarEarlySafe(
      io.commit.rows(slot).blockBidValid,
      io.commit.rows(slot).blockBid,
      io.commitMemoryOrder(slot).valid && io.commitMemoryOrder(slot).isStore,
      currentStoreLsId(slot))
  }
  val slotMatchVec = Wire(Vec(traceParams.commitWidth, Bool()))
  for (slot <- 0 until traceParams.commitWidth) {
    slotMatchVec(slot) :=
      commitStoreVec(slot) &&
        VecInit((0 until entries).map { idx =>
          val row = io.stqRows(idx)
          markable(row) && sameCommitIdentity(io.commit.rows(slot), row) &&
            (blockInNonFlushPrefix(io.commit.rows(slot)) || (row.scalarIex && currentStoreEarlySafe(slot)))
        }).asUInt.orR
  }

  val matchVec = Wire(Vec(entries, Bool()))
  val residentEarlySafeVec = Wire(Vec(entries, Bool()))
  for (idx <- 0 until entries) {
    residentEarlySafeVec(idx) :=
      markable(io.stqRows(idx)) && io.stqRows(idx).scalarIex &&
        io.oldestRobValid && io.oldestRobBid.valid &&
        io.oldestRobStid === io.activeStid &&
        ROBID.equal(io.stqRows(idx).bid, io.oldestRobBid) &&
        LSIDOrder.less(io.stqRows(idx).lsIdFull, io.oldestRobLsId)
    matchVec(idx) :=
      markable(io.stqRows(idx)) &&
        (residentEarlySafeVec(idx) || VecInit((0 until traceParams.commitWidth).map { slot =>
          commitStoreVec(slot) &&
            sameCommitIdentity(io.commit.rows(slot), io.stqRows(idx)) &&
            (blockInNonFlushPrefix(io.commit.rows(slot)) ||
              (io.stqRows(idx).scalarIex && currentStoreEarlySafe(slot)))
        }).asUInt.orR ||
          VecInit((0 until entries).map(pendingIdx => samePendingIdentity(pendingIdx, io.stqRows(idx)))).asUInt.orR)
  }
  val matchMask = matchVec.asUInt
  val earlySafeMatchVec = Wire(Vec(entries, Bool()))
  for (idx <- 0 until entries) {
    earlySafeMatchVec(idx) :=
      markable(io.stqRows(idx)) &&
        (VecInit((0 until traceParams.commitWidth).map { slot =>
          commitStoreVec(slot) && sameCommitIdentity(io.commit.rows(slot), io.stqRows(idx)) &&
            !blockInNonFlushPrefix(io.commit.rows(slot)) &&
            io.stqRows(idx).scalarIex && currentStoreEarlySafe(slot)
        }).asUInt.orR ||
          VecInit((0 until entries).map(pendingIdx =>
            pendingCommitValid(pendingIdx) &&
              pendingCommitStid(pendingIdx) === io.stqRows(idx).stid &&
              stqRowIdentityValid(io.stqRows(idx)) &&
              pendingCommitBid(pendingIdx) === io.stqRows(idx).bid.value &&
              pendingCommitGid(pendingIdx) === io.stqRows(idx).gid.value &&
              pendingCommitRid(pendingIdx) === io.stqRows(idx).rid.value &&
              pendingEarlySafe(pendingIdx, io.stqRows(idx)))).asUInt.orR)
  }

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
    val nextPendingBid = Wire(Vec(entries, UInt(identityWidth.W)))
    val nextPendingGid = Wire(Vec(entries, UInt(identityWidth.W)))
    val nextPendingRid = Wire(Vec(entries, UInt(identityWidth.W)))
    val nextPendingStid = Wire(Vec(entries, UInt(stidWidth.W)))
    val nextPendingBlockBidValid = Wire(Vec(entries, Bool()))
    val nextPendingBlockBid = Wire(Vec(entries, UInt(traceParams.blockBidWidth.W)))
    val nextPendingLsId = Wire(Vec(entries, UInt(lsidWidth.W)))
    val nextPendingEarlySafe = Wire(Vec(entries, Bool()))
    for (idx <- 0 until entries) {
      basePendingValid(idx) := pendingCommitValid(idx) && !pendingMatchedVec(idx)
      nextPendingValid(idx) := basePendingValid(idx)
      nextPendingBid(idx) := pendingCommitBid(idx)
      nextPendingGid(idx) := pendingCommitGid(idx)
      nextPendingRid(idx) := pendingCommitRid(idx)
      nextPendingStid(idx) := pendingCommitStid(idx)
      nextPendingBlockBidValid(idx) := pendingCommitBlockBidValid(idx)
      nextPendingBlockBid(idx) := pendingCommitBlockBid(idx)
      nextPendingLsId(idx) := pendingCommitLsId(idx)
      nextPendingEarlySafe(idx) := pendingCommitEarlySafe(idx) || scalarEarlySafe(
        pendingCommitBlockBidValid(idx), pendingCommitBlockBid(idx),
        pendingCommitValid(idx), pendingCommitLsId(idx))
    }
    val pendingIdentityFreeMask = VecInit((0 until entries).map(idx => !basePendingValid(idx))).asUInt
    val pendingIdentityFreeOH = PriorityEncoderOH(pendingIdentityFreeMask)
    val bufferRow = io.commit.rows(bufferStoreSlot)
    for (idx <- 0 until entries) {
      when(bufferStoreValid && pendingIdentityFreeOH(idx)) {
        nextPendingValid(idx) := true.B
        nextPendingBid(idx) := resize(bufferRow.identity.bid, identityWidth)
        nextPendingGid(idx) := resize(bufferRow.identity.gid, identityWidth)
        nextPendingRid(idx) := resize(bufferRow.identity.rid, identityWidth)
        nextPendingStid(idx) := io.activeStid
        nextPendingBlockBidValid(idx) := bufferRow.blockBidValid
        nextPendingBlockBid(idx) := bufferRow.blockBid
        nextPendingLsId(idx) := currentStoreLsId(bufferStoreSlot)
        nextPendingEarlySafe(idx) := currentStoreEarlySafe(bufferStoreSlot)
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
      pendingCommitLsId(idx) := nextPendingLsId(idx)
      pendingCommitEarlySafe(idx) := nextPendingEarlySafe(idx)
    }
  }

  val unmatchedSlotVec = VecInit((0 until traceParams.commitWidth).map(slot => commitStoreVec(slot) && !slotMatchVec(slot)))
  io.commitStoreSeen := commitStoreVec.asUInt.orR
  io.commitStoreMatched := slotMatchVec.asUInt.orR
  io.commitStoreUnmatched := unmatchedSlotVec.asUInt.orR
  io.commitStoreBlockedByNonFlush := VecInit((0 until traceParams.commitWidth).map { slot =>
    commitStoreVec(slot) && !blockInNonFlushPrefix(io.commit.rows(slot)) && !currentStoreEarlySafe(slot)
  }).asUInt.orR
  io.commitStoreReleasedByEarlySafe := VecInit((0 until traceParams.commitWidth).map { slot =>
    commitStoreVec(slot) && !blockInNonFlushPrefix(io.commit.rows(slot)) && currentStoreEarlySafe(slot)
  }).asUInt.orR
  io.matchMask := matchMask
  io.earlySafeMatchMask := earlySafeMatchVec.asUInt
  io.pendingCommitIdentityMask := pendingCommitValid.asUInt
  io.pendingCommitEarlySafeMask := VecInit((0 until entries).map(idx =>
    pendingCommitValid(idx) && pendingCommitEarlySafe(idx))).asUInt
  io.residentEarlySafeMask := residentEarlySafeVec.asUInt
  io.pendingMarkMask := pendingMark
  io.pendingFreeMask := pendingFree
  io.pendingMarkCount := PopCount(pendingMark)
  io.pendingFreeCount := PopCount(pendingFree)
  io.markBlocked := markValid && !io.markCommitAccepted
  io.freeBlocked := freeValid && !io.commitFreeAccepted
  io.idle := !pendingMark.orR && (!io.directFreeEnable || !pendingFree.orR)
}
