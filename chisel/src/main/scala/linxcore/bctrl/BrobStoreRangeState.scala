package linxcore.bctrl

import chisel3._
import chisel3.util.{Mux1H, PopCount, log2Ceil}

class BrobStoreRangeEntry(
    val bidWidth: Int,
    val storeIdWidth: Int,
    val storeCountWidth: Int)
    extends Bundle {
  val allocated = Bool()
  val bid = UInt(bidWidth.W)
  val countKnown = Bool()
  val storeCount = UInt(storeCountWidth.W)
  val startValid = Bool()
  val startStoreId = UInt(storeIdWidth.W)
}

class BrobStoreRangeStateIO(
    val entries: Int,
    val bidWidth: Int,
    val stidWidth: Int,
    val stidCount: Int,
    val storeIdWidth: Int,
    val storeCountWidth: Int)
    extends Bundle {
  private val countWidth = log2Ceil(entries + 1)

  val allocValid = Input(Bool())
  val allocBid = Input(UInt(bidWidth.W))
  val allocStid = Input(UInt(stidWidth.W))
  val allocReady = Output(Bool())
  val allocAccepted = Output(Bool())

  val storeObservedValid = Input(Bool())
  val storeObservedBid = Input(UInt(bidWidth.W))
  val storeObservedStid = Input(UInt(stidWidth.W))
  val storeObservedAccepted = Output(Bool())
  val storeObservedIgnored = Output(Bool())

  val countCertainValid = Input(Bool())
  val countCertainBid = Input(UInt(bidWidth.W))
  val countCertainStid = Input(UInt(stidWidth.W))
  val countCertainUseValue = Input(Bool())
  val countCertainValue = Input(UInt(storeCountWidth.W))
  val countCertainAccepted = Output(Bool())
  val countCertainDuplicateMatch = Output(Bool())
  val countCertainConflict = Output(Bool())
  val countCertainIgnored = Output(Bool())

  val retireValid = Input(Bool())
  val retireBid = Input(UInt(bidWidth.W))
  val retireStid = Input(UInt(stidWidth.W))
  val retireAccepted = Output(Bool())
  val retireIgnored = Output(Bool())

  val recoveryValid = Input(Bool())
  val recoveryStid = Input(UInt(stidWidth.W))
  val recoveryFirstKilledBid = Input(UInt(bidWidth.W))
  val orderHeadBid = Input(Vec(stidCount, UInt(bidWidth.W)))
  val orderLiveCount = Input(Vec(stidCount, UInt(countWidth.W)))
  val recoveryRewound = Output(Bool())
  val recoveryMissingStart = Output(Bool())

  val queryBid = Input(UInt(bidWidth.W))
  val queryStid = Input(UInt(stidWidth.W))
  val query = Output(new BrobStoreRangeEntry(bidWidth, storeIdWidth, storeCountWidth))
  val queryHit = Output(Bool())

  val rangeCursorBid = Output(Vec(stidCount, UInt(bidWidth.W)))
  val nextStoreId = Output(Vec(stidCount, UInt(storeIdWidth.W)))
  val advanceCount = Output(Vec(stidCount, UInt(countWidth.W)))
  val blockedValid = Output(Vec(stidCount, Bool()))
  val blockedBid = Output(Vec(stidCount, UInt(bidWidth.W)))
  val headResident = Output(Vec(stidCount, Bool()))
  val headCountKnown = Output(Vec(stidCount, Bool()))
}

/** Per-STID contiguous block store-range assignment and recovery owner. */
class BrobStoreRangeState(
    val entries: Int = 16,
    val bidWidth: Int = BID.DefaultWidth,
    val stidWidth: Int = 8,
    val stidCount: Int = 1,
    val storeIdWidth: Int = 64,
    val storeCountWidth: Int = 64)
    extends Module {
  require(entries > 1 && (entries & (entries - 1)) == 0,
    "BROB store-range entries must be a power of two")
  require(bidWidth > log2Ceil(entries), "BROB store-range BID must include uniqueness bits")
  require(stidCount > 0, "BROB store-range owner must track at least one STID")
  require(BigInt(stidCount) <= (BigInt(1) << stidWidth), "BROB store-range STID count must fit")
  require(storeIdWidth > 0 && storeCountWidth > 0, "store ID and count widths must be positive")

  private val countWidth = log2Ceil(entries + 1)
  val io = IO(new BrobStoreRangeStateIO(
    entries,
    bidWidth,
    stidWidth,
    stidCount,
    storeIdWidth,
    storeCountWidth))

  private def emptyEntry: BrobStoreRangeEntry =
    0.U.asTypeOf(new BrobStoreRangeEntry(bidWidth, storeIdWidth, storeCountWidth))

  private def resize(value: UInt, width: Int): UInt =
    if (value.getWidth > width) value(width - 1, 0) else value.pad(width)

  val table = RegInit(VecInit(Seq.fill(stidCount)(VecInit(Seq.fill(entries)(emptyEntry)))))
  val rangeCursorBid = RegInit(VecInit(Seq.fill(stidCount)(0.U(bidWidth.W))))
  val nextStoreId = RegInit(VecInit(Seq.fill(stidCount)(0.U(storeIdWidth.W))))

  private def laneMatch(stid: UInt): Vec[Bool] =
    VecInit((0 until stidCount).map(lane => stid === lane.U(stidWidth.W)))

  private def selectEntry(matches: Vec[Bool], bid: UInt): BrobStoreRangeEntry =
    Mux1H(matches, table.map(_(BID.slot(bid, entries))))

  val allocMatch = laneMatch(io.allocStid)
  val storeMatch = laneMatch(io.storeObservedStid)
  val certainMatch = laneMatch(io.countCertainStid)
  val retireMatch = laneMatch(io.retireStid)
  val recoveryMatch = laneMatch(io.recoveryStid)
  val queryMatch = laneMatch(io.queryStid)
  val allocEntry = selectEntry(allocMatch, io.allocBid)
  val storeEntry = selectEntry(storeMatch, io.storeObservedBid)
  val certainEntry = selectEntry(certainMatch, io.countCertainBid)
  val queryEntry = selectEntry(queryMatch, io.queryBid)
  val retireEntry = selectEntry(retireMatch, io.retireBid)

  val allocInRange = allocMatch.asUInt.orR
  val allocReady = allocInRange && !allocEntry.allocated
  val allocAccepted = io.allocValid && allocReady
  val storeHitsAllocated = storeMatch.asUInt.orR && storeEntry.allocated &&
    storeEntry.bid === io.storeObservedBid
  val storeHitsNewAlloc = allocAccepted && io.allocStid === io.storeObservedStid &&
    io.allocBid === io.storeObservedBid
  val storeAccepted = io.storeObservedValid && (storeHitsAllocated || storeHitsNewAlloc)
  val certainExactAllocated = certainMatch.asUInt.orR && certainEntry.allocated &&
    certainEntry.bid === io.countCertainBid
  val certainHitsAllocated = certainExactAllocated && !certainEntry.countKnown
  val certainHitsNewAlloc = allocAccepted && io.allocStid === io.countCertainStid &&
    io.allocBid === io.countCertainBid
  val certainAccepted = io.countCertainValid && (certainHitsAllocated || certainHitsNewAlloc)
  val certainDuplicate = io.countCertainValid && certainExactAllocated && certainEntry.countKnown
  val certainDuplicateMatch = certainDuplicate &&
    (!io.countCertainUseValue || certainEntry.storeCount === io.countCertainValue)
  val certainConflict = certainDuplicate && io.countCertainUseValue &&
    certainEntry.storeCount =/= io.countCertainValue

  io.allocReady := allocReady
  io.allocAccepted := allocAccepted
  io.storeObservedAccepted := storeAccepted
  io.storeObservedIgnored := io.storeObservedValid && !storeAccepted
  io.countCertainAccepted := certainAccepted
  io.countCertainDuplicateMatch := certainDuplicateMatch
  io.countCertainConflict := certainConflict
  io.countCertainIgnored := io.countCertainValid && !certainAccepted && !certainDuplicateMatch
  val retireHit = retireMatch.asUInt.orR && retireEntry.allocated && retireEntry.bid === io.retireBid
  io.retireAccepted := io.retireValid && retireHit
  io.retireIgnored := io.retireValid && !retireHit

  val assignValid = Wire(Vec(stidCount, Vec(entries, Bool())))
  val assignStart = Wire(Vec(stidCount, Vec(entries, UInt(storeIdWidth.W))))
  val laneAdvanceCount = Wire(Vec(stidCount, UInt(countWidth.W)))
  val laneNextStoreId = Wire(Vec(stidCount, UInt(storeIdWidth.W)))

  for (stid <- 0 until stidCount) {
    val headEntry = table(stid)(BID.slot(io.orderHeadBid(stid), entries))
    io.headResident(stid) := io.orderLiveCount(stid) =/= 0.U &&
      headEntry.allocated && headEntry.bid === io.orderHeadBid(stid)
    io.headCountKnown(stid) := io.headResident(stid) && headEntry.countKnown
    val scanOpen = Wire(Vec(entries + 1, Bool()))
    val runningStoreId = Wire(Vec(entries + 1, UInt(storeIdWidth.W)))
    val touch = Wire(Vec(entries, Bool()))
    val advance = Wire(Vec(entries, Bool()))
    val candidateBid = Wire(Vec(entries, UInt(bidWidth.W)))
    scanOpen(0) := true.B
    runningStoreId(0) := nextStoreId(stid)
    val cursorDistance = rangeCursorBid(stid) - io.orderHeadBid(stid)
    val cursorInWindow = cursorDistance <= io.orderLiveCount(stid).pad(bidWidth)
    val remainingLive = Mux(
      cursorInWindow,
      io.orderLiveCount(stid) - cursorDistance(countWidth - 1, 0),
      0.U)

    for (offset <- 0 until entries) {
      candidateBid(offset) := rangeCursorBid(stid) + offset.U
      val slot = BID.slot(candidateBid(offset), entries)
      val entry = table(stid)(slot)
      val resident = offset.U < remainingLive && entry.allocated && entry.bid === candidateBid(offset)
      touch(offset) := scanOpen(offset) && resident
      advance(offset) := touch(offset) && entry.countKnown
      scanOpen(offset + 1) := advance(offset)
      runningStoreId(offset + 1) := Mux(
        advance(offset),
        runningStoreId(offset) + resize(entry.storeCount, storeIdWidth),
        runningStoreId(offset))
    }

    for (idx <- 0 until entries) {
      val hitVec = VecInit((0 until entries).map { offset =>
        touch(offset) && BID.slot(candidateBid(offset), entries) === idx.U
      })
      assignValid(stid)(idx) := hitVec.asUInt.orR
      assignStart(stid)(idx) := Mux(
        hitVec.asUInt.orR,
        Mux1H(hitVec, runningStoreId.take(entries)),
        0.U)
    }

    laneAdvanceCount(stid) := PopCount(advance)
    laneNextStoreId(stid) := runningStoreId(entries)
    io.advanceCount(stid) := laneAdvanceCount(stid)
    io.blockedValid(stid) := laneAdvanceCount(stid) < remainingLive
    io.blockedBid(stid) := Mux(
      io.blockedValid(stid),
      rangeCursorBid(stid) + laneAdvanceCount(stid).pad(bidWidth),
      0.U)
  }

  val selectedRecoveryHead = Mux(recoveryMatch.asUInt.orR, Mux1H(recoveryMatch, io.orderHeadBid), 0.U)
  val selectedRecoveryLiveCount = Mux(recoveryMatch.asUInt.orR, Mux1H(recoveryMatch, io.orderLiveCount), 0.U)
  val selectedRecoveryCursor = Mux(recoveryMatch.asUInt.orR, Mux1H(recoveryMatch, rangeCursorBid), 0.U)
  val selectedRecoveryNextStoreId = Mux(recoveryMatch.asUInt.orR, Mux1H(recoveryMatch, nextStoreId), 0.U)
  val recoveryEntry = selectEntry(recoveryMatch, io.recoveryFirstKilledBid)
  val firstKilledDistance = io.recoveryFirstKilledBid - selectedRecoveryHead
  val cursorDistance = selectedRecoveryCursor - selectedRecoveryHead
  val recoveryBeforeOrAtCursor = firstKilledDistance <= cursorDistance &&
    cursorDistance <= selectedRecoveryLiveCount.pad(bidWidth)
  val recoveryExactCursor = io.recoveryFirstKilledBid === selectedRecoveryCursor
  val recoveryStartHit = recoveryEntry.allocated &&
    recoveryEntry.bid === io.recoveryFirstKilledBid && recoveryEntry.startValid
  val recoveryCanRewind = recoveryBeforeOrAtCursor && (recoveryExactCursor || recoveryStartHit)
  val recoveryRewound = io.recoveryValid && recoveryMatch.asUInt.orR && recoveryCanRewind
  io.recoveryRewound := recoveryRewound
  io.recoveryMissingStart := io.recoveryValid && recoveryMatch.asUInt.orR &&
    recoveryBeforeOrAtCursor && !recoveryCanRewind

  for (stid <- 0 until stidCount) {
    val laneAlloc = allocAccepted && allocMatch(stid)
    val laneStore = storeAccepted && storeMatch(stid)
    val laneCertain = certainAccepted && certainMatch(stid)
    val laneRetire = io.retireValid && retireHit && retireMatch(stid)
    val laneRecovery = io.recoveryValid && recoveryMatch(stid)
    val allocSlot = BID.slot(io.allocBid, entries)
    val storeSlot = BID.slot(io.storeObservedBid, entries)
    val certainSlot = BID.slot(io.countCertainBid, entries)
    val retireSlot = BID.slot(io.retireBid, entries)

    when(laneAdvanceCount(stid) =/= 0.U && !laneRecovery) {
      rangeCursorBid(stid) := rangeCursorBid(stid) + laneAdvanceCount(stid).pad(bidWidth)
      nextStoreId(stid) := laneNextStoreId(stid)
    }

    for (idx <- 0 until entries) {
      when(laneAlloc && allocSlot === idx.U) {
        table(stid)(idx) := emptyEntry
        table(stid)(idx).allocated := true.B
        table(stid)(idx).bid := io.allocBid
        table(stid)(idx).storeCount := Mux(
          laneCertain && io.countCertainBid === io.allocBid && io.countCertainUseValue,
          io.countCertainValue,
          Mux(laneStore && io.storeObservedBid === io.allocBid, 1.U, 0.U))
        table(stid)(idx).countKnown := laneCertain && io.countCertainBid === io.allocBid
      }

      val oldStoreHit = laneStore && storeSlot === idx.U &&
        table(stid)(idx).allocated && table(stid)(idx).bid === io.storeObservedBid &&
        !(laneAlloc && allocSlot === idx.U)
      when(oldStoreHit) {
        table(stid)(idx).storeCount := table(stid)(idx).storeCount + 1.U
      }

      val oldCertainHit = laneCertain && certainSlot === idx.U &&
        table(stid)(idx).allocated && table(stid)(idx).bid === io.countCertainBid &&
        !(laneAlloc && allocSlot === idx.U)
      when(oldCertainHit) {
        table(stid)(idx).countKnown := true.B
        table(stid)(idx).storeCount := Mux(
          io.countCertainUseValue,
          io.countCertainValue,
          table(stid)(idx).storeCount + oldStoreHit.asUInt)
      }

      when(assignValid(stid)(idx) && !laneRecovery) {
        table(stid)(idx).startValid := true.B
        table(stid)(idx).startStoreId := assignStart(stid)(idx)
      }

      when(laneRetire && retireSlot === idx.U && table(stid)(idx).allocated &&
        table(stid)(idx).bid === io.retireBid) {
        table(stid)(idx) := emptyEntry
      }

      val candidateDistance = table(stid)(idx).bid - io.orderHeadBid(stid)
      val firstDistance = io.recoveryFirstKilledBid - io.orderHeadBid(stid)
      val candidateInWindow = candidateDistance < io.orderLiveCount(stid).pad(bidWidth)
      when(laneRecovery && table(stid)(idx).allocated && candidateInWindow &&
        candidateDistance >= firstDistance) {
        table(stid)(idx) := emptyEntry
      }
    }

    when(laneRecovery && recoveryCanRewind) {
      rangeCursorBid(stid) := io.recoveryFirstKilledBid
      nextStoreId(stid) := Mux(
        recoveryExactCursor,
        selectedRecoveryNextStoreId,
        recoveryEntry.startStoreId)
    }
  }

  io.query := queryEntry
  io.queryHit := queryMatch.asUInt.orR && queryEntry.allocated && queryEntry.bid === io.queryBid
  io.rangeCursorBid := rangeCursorBid
  io.nextStoreId := nextStoreId
}
