package linxcore.rob

import chisel3._
import chisel3.util.{log2Ceil, OHToUInt, PopCount, PriorityEncoderOH}
import linxcore.commit.{CommitTraceMonitor, CommitTraceParams, CommitTracePort, CommitTraceRow}
import linxcore.common.{
  BlockMarkerRetireSource,
  BoundaryKind,
  DestinationKind,
  InterfaceParams,
  TULinkFlushSequenceSource,
  TULinkRetireSource
}
import linxcore.recovery.FlushBus

class ROBEntryBankIO(
    val entries: Int,
    val traceParams: CommitTraceParams,
    val mapQDepth: Int = 32,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8)
    extends Bundle {
  private val ptrWidth = log2Ceil(entries)
  private val sizeWidth = log2Ceil(entries + 1)
  private val sourceParams = InterfaceParams(robEntries = entries)

  val flush = Input(new FlushBus(entries))

  val allocValid = Input(Bool())
  val allocReady = Output(Bool())
  val allocDuplicateIdentity = Output(Bool())
  val allocRow = Input(new CommitTraceRow(traceParams))
  val allocBid = Input(new ROBID(entries))
  val allocGid = Input(new ROBID(entries))
  val allocPeId = Input(UInt(peIdWidth.W))
  val allocStid = Input(UInt(stidWidth.W))
  val allocTSeq = Input(new ROBID(mapQDepth))
  val allocUSeq = Input(new ROBID(mapQDepth))
  val allocTUDstValid = Input(Bool())
  val allocTUDstKind = Input(DestinationKind())
  val allocIsLast = Input(Bool())
  val allocMarkerBoundary = Input(Bool())
  val allocMarkerStop = Input(Bool())
  val allocMarkerBoundaryKind = Input(BoundaryKind())
  val allocMarkerBoundaryTarget = Input(UInt(traceParams.pcWidth.W))
  val allocRobValue = Output(UInt(ptrWidth.W))
  val allocRobWrap = Output(Bool())

  val renameUpdateValid = Input(Bool())
  val renameUpdateReady = Output(Bool())
  val renameUpdateAccepted = Output(Bool())
  val renameUpdateIgnored = Output(Bool())
  val renameUpdateRid = Input(new ROBID(entries))
  val renameUpdateRow = Input(new CommitTraceRow(traceParams))
  val renameUpdateTSeq = Input(new ROBID(mapQDepth))
  val renameUpdateUSeq = Input(new ROBID(mapQDepth))
  val renameUpdateTUDstValid = Input(Bool())
  val renameUpdateTUDstKind = Input(DestinationKind())

  val completeValid = Input(Bool())
  val completeRobValue = Input(UInt(ptrWidth.W))
  val completeRowValid = Input(Bool())
  val completeRow = Input(new CommitTraceRow(traceParams))
  val completeAccepted = Output(Bool())
  val completeIgnored = Output(Bool())

  val deallocReady = Input(Bool())

  val commit = Output(new CommitTracePort(traceParams))
  val commitValidMask = Output(UInt(traceParams.commitWidth.W))
  val commitCount = Output(UInt(log2Ceil(traceParams.commitWidth + 1).W))
  val commitMonitorValidMask = Output(UInt(traceParams.commitWidth.W))
  val commitMonitorValidCount = Output(UInt(log2Ceil(traceParams.commitWidth + 1).W))
  val commitSkippedSlot = Output(Bool())
  val commitDuplicateIdentity = Output(Bool())
  val commitSlotMismatch = Output(Bool())
  val commitInvalidSideEffect = Output(Bool())
  val commitContractError = Output(Bool())

  val deallocValidMask = Output(UInt(traceParams.commitWidth.W))
  val deallocCount = Output(UInt(log2Ceil(traceParams.commitWidth + 1).W))
  val deallocTURetireSource =
    Output(Vec(traceParams.commitWidth, new TULinkRetireSource(sourceParams, mapQDepth, stidWidth, peIdWidth)))
  val deallocBlockMarkerRetireSource =
    Output(Vec(traceParams.commitWidth, new BlockMarkerRetireSource(
      entries = entries,
      blockBidWidth = traceParams.blockBidWidth,
      pcWidth = traceParams.pcWidth,
      insnWidth = traceParams.insnWidth,
      lenWidth = traceParams.lenWidth,
      peIdWidth = peIdWidth,
      stidWidth = stidWidth
    )))
  val deallocBlockLastValid = Output(Bool())
  val deallocBlockLastBid = Output(new ROBID(entries))
  val deallocBlockLastGid = Output(new ROBID(entries))
  val deallocBlockLastBlockBid = Output(UInt(traceParams.blockBidWidth.W))

  val flushApplied = Output(Bool())
  val flushDirectMatchMask = Output(UInt(entries.W))
  val flushPruneMask = Output(UInt(entries.W))
  val flushPruneBeforeCommitMask = Output(UInt(entries.W))
  val flushOutstandingPruneMask = Output(UInt(entries.W))
  val flushResidentDecrement = Output(UInt(sizeWidth.W))
  val flushOutstandingDecrement = Output(UInt(sizeWidth.W))
  val flushAllocRebased = Output(Bool())
  val flushAllocRebaseValue = Output(UInt(ptrWidth.W))
  val flushCommitRebased = Output(Bool())
  val flushCommitRebaseValue = Output(UInt(ptrWidth.W))
  val flushClearedAll = Output(Bool())
  val robTULinkSource = Output(new TULinkFlushSequenceSource(sourceParams, mapQDepth, stidWidth))
  val robTULinkSourceMatched = Output(Bool())
  val robTULinkSourceMultipleMatch = Output(Bool())

  val empty = Output(Bool())
  val full = Output(Bool())
  val size = Output(UInt(sizeWidth.W))
  val outstandingCount = Output(UInt(sizeWidth.W))

  val commitHeadValid = Output(Bool())
  val commitHeadStatus = Output(ROBEntryStatus())
  val commitHeadRobValue = Output(UInt(ptrWidth.W))
  val deallocHeadValid = Output(Bool())
  val deallocHeadStatus = Output(ROBEntryStatus())
  val deallocHeadRobValue = Output(UInt(ptrWidth.W))

  val occupiedMask = Output(UInt(entries.W))
  val completedMask = Output(UInt(entries.W))
  val retiredMask = Output(UInt(entries.W))
}

class ROBEntryBank(
    val entries: Int = 16,
    val traceParams: CommitTraceParams = CommitTraceParams(),
    val mapQDepth: Int = 32,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8)
    extends Module {
  require(entries > 1, "ROB entries must be greater than one")
  require((entries & (entries - 1)) == 0, "ROB entries must be a power of two")
  require(mapQDepth > 1 && (mapQDepth & (mapQDepth - 1)) == 0, "T/U mapQ depth must be a power of two")
  require(traceParams.commitWidth > 0, "commitWidth must be positive")
  require(traceParams.commitWidth <= entries, "commitWidth cannot exceed entries")
  require(traceParams.robValueWidth >= log2Ceil(entries), "ROB trace value must hold entry index")

  private val ptrWidth = log2Ceil(entries)
  private val sizeWidth = log2Ceil(entries + 1)
  private val sourceParams = InterfaceParams(robEntries = entries)

  val io = IO(new ROBEntryBankIO(entries, traceParams, mapQDepth, peIdWidth, stidWidth))

  private def zeroRow: CommitTraceRow = {
    val row = Wire(new CommitTraceRow(traceParams))
    row := 0.U.asTypeOf(row)
    row
  }

  private def wrapIndex(value: UInt, offset: Int): UInt = {
    val sum = value + offset.U
    Mux(sum >= entries.U, sum - entries.U, sum)(ptrWidth - 1, 0)
  }

  private def advance(value: UInt, wrap: Bool, amount: UInt): (UInt, Bool) = {
    val sum = value +& amount
    val entryCount = entries.U(sum.getWidth.W)
    val wraps = sum >= entryCount
    val nextValue = Mux(wraps, sum - entryCount, sum)(ptrWidth - 1, 0)
    (nextValue, wrap ^ wraps)
  }

  private def scanWrapFor(value: UInt, headValue: UInt, headWrap: Bool): Bool =
    headWrap ^ (value < headValue)

  private def zeroRobId: ROBID =
    0.U.asTypeOf(new ROBID(entries))

  private def zeroLocalSeq: ROBID =
    0.U.asTypeOf(new ROBID(mapQDepth))

  private def zeroTULinkSource: TULinkFlushSequenceSource = {
    val source = Wire(new TULinkFlushSequenceSource(sourceParams, mapQDepth, stidWidth))
    source := 0.U.asTypeOf(source)
    source
  }

  private def zeroTURetireSource: TULinkRetireSource = {
    val source = Wire(new TULinkRetireSource(sourceParams, mapQDepth, stidWidth, peIdWidth))
    source := 0.U.asTypeOf(source)
    source
  }

  private def zeroBlockMarkerRetireSource: BlockMarkerRetireSource = {
    val source = Wire(new BlockMarkerRetireSource(
      entries = entries,
      blockBidWidth = traceParams.blockBidWidth,
      pcWidth = traceParams.pcWidth,
      insnWidth = traceParams.insnWidth,
      lenWidth = traceParams.lenWidth,
      peIdWidth = peIdWidth,
      stidWidth = stidWidth
    ))
    source := 0.U.asTypeOf(source)
    source
  }

  private def allocatedRid: ROBID = {
    val id = Wire(new ROBID(entries))
    id.valid := true.B
    id.wrap := allocWrap
    id.value := allocValue
    id
  }

  private def storedAllocBid: ROBID = {
    val id = Wire(new ROBID(entries))
    id := io.allocBid
    id.valid := true.B
    id
  }

  private def sameIdentity(lhs: CommitTraceRow, rhs: CommitTraceRow): Bool =
    (lhs.identity.bid === rhs.identity.bid) &&
      (lhs.identity.gid === rhs.identity.gid) &&
      (lhs.identity.rid === rhs.identity.rid)

  private def mayComplete(status: ROBEntryStatus.Type): Bool =
    status === ROBEntryStatus.Allocated ||
      status === ROBEntryStatus.Renamed ||
      status === ROBEntryStatus.Issued ||
      status === ROBEntryStatus.Completed

  val rows = Reg(Vec(entries, new CommitTraceRow(traceParams)))
  val rowBid = RegInit(VecInit(Seq.fill(entries)(0.U.asTypeOf(new ROBID(entries)))))
  val rowGid = RegInit(VecInit(Seq.fill(entries)(0.U.asTypeOf(new ROBID(entries)))))
  val rowRid = RegInit(VecInit(Seq.fill(entries)(0.U.asTypeOf(new ROBID(entries)))))
  val rowPeId = RegInit(VecInit(Seq.fill(entries)(0.U(peIdWidth.W))))
  val rowStid = RegInit(VecInit(Seq.fill(entries)(0.U(stidWidth.W))))
  val rowTSeq = RegInit(VecInit(Seq.fill(entries)(0.U.asTypeOf(new ROBID(mapQDepth)))))
  val rowUSeq = RegInit(VecInit(Seq.fill(entries)(0.U.asTypeOf(new ROBID(mapQDepth)))))
  val rowTUDstValid = RegInit(VecInit(Seq.fill(entries)(false.B)))
  val rowTUDstKind = RegInit(VecInit(Seq.fill(entries)(DestinationKind.None)))
  val rowIsLast = RegInit(VecInit(Seq.fill(entries)(false.B)))
  val rowMarkerBoundary = RegInit(VecInit(Seq.fill(entries)(false.B)))
  val rowMarkerStop = RegInit(VecInit(Seq.fill(entries)(false.B)))
  val rowMarkerBoundaryKind = RegInit(VecInit(Seq.fill(entries)(BoundaryKind.Fall)))
  val rowMarkerBoundaryTarget = RegInit(VecInit(Seq.fill(entries)(0.U(traceParams.pcWidth.W))))
  val status = RegInit(VecInit(Seq.fill(entries)(ROBEntryStatus.Free)))
  val allocValue = RegInit(0.U(ptrWidth.W))
  val allocWrap = RegInit(false.B)
  val commitValue = RegInit(0.U(ptrWidth.W))
  val commitWrap = RegInit(false.B)
  val deallocValue = RegInit(0.U(ptrWidth.W))
  val deallocWrap = RegInit(false.B)
  val size = RegInit(0.U(sizeWidth.W))
  val outstandingCount = RegInit(0.U(sizeWidth.W))
  val commitPort = Wire(new CommitTracePort(traceParams))

  val duplicateVec = Wire(Vec(entries, Bool()))
  val occupiedVec = Wire(Vec(entries, Bool()))
  val completedVec = Wire(Vec(entries, Bool()))
  val retiredVec = Wire(Vec(entries, Bool()))
  for (idx <- 0 until entries) {
    occupiedVec(idx) := ROBEntryStatus.occupiesRob(status(idx))
    completedVec(idx) := status(idx) === ROBEntryStatus.Completed
    retiredVec(idx) := status(idx) === ROBEntryStatus.Retired
    duplicateVec(idx) := occupiedVec(idx) && sameIdentity(rows(idx), io.allocRow)
  }

  io.occupiedMask := occupiedVec.asUInt
  io.completedMask := completedVec.asUInt
  io.retiredMask := retiredVec.asUInt

  val flushPrune = Module(new ROBFlushPrune(entries))
  flushPrune.io.flush := io.flush
  flushPrune.io.deallocHead := deallocValue
  flushPrune.io.commitHead := commitValue
  for (idx <- 0 until entries) {
    val rowValid = rows(idx).valid && ROBEntryStatus.occupiesRob(status(idx))
    flushPrune.io.rows(idx).valid := rowValid
    flushPrune.io.rows(idx).status := status(idx)
    flushPrune.io.rows(idx).bid := rowBid(idx)
    flushPrune.io.rows(idx).rid := rowRid(idx)
  }

  val flushApplied = flushPrune.io.firstPruneValid
  io.flushApplied := flushApplied
  io.flushDirectMatchMask := flushPrune.io.directMatchMask
  io.flushPruneMask := flushPrune.io.pruneMask
  io.flushPruneBeforeCommitMask := flushPrune.io.pruneBeforeCommitMask
  io.flushOutstandingPruneMask := flushPrune.io.outstandingPruneMask
  io.flushResidentDecrement := flushPrune.io.residentDecrement
  io.flushOutstandingDecrement := flushPrune.io.outstandingDecrement
  io.flushAllocRebased := flushApplied
  io.flushAllocRebaseValue := flushPrune.io.firstPruneValue
  io.flushCommitRebased := flushPrune.io.commitRebaseNeeded
  io.flushCommitRebaseValue := flushPrune.io.commitRebaseValue

  val robTULinkSourceMatchVec = Wire(Vec(entries, Bool()))
  for (idx <- 0 until entries) {
    val rowValid = rows(idx).valid && ROBEntryStatus.occupiesRob(status(idx))
    robTULinkSourceMatchVec(idx) :=
      io.flush.req.valid &&
        !io.flush.baseOnBid &&
        rowValid &&
        ROBID.equal(rowBid(idx), io.flush.req.bid) &&
        ROBID.equal(rowRid(idx), io.flush.req.rid) &&
        (rowStid(idx) === io.flush.req.stid)
  }
  val robTULinkSourceMatched = robTULinkSourceMatchVec.asUInt.orR
  val robTULinkSourceMatchOH = PriorityEncoderOH(robTULinkSourceMatchVec)
  val robTULinkSourceMatchIndex = OHToUInt(robTULinkSourceMatchOH)
  val robTULinkSource = Wire(new TULinkFlushSequenceSource(sourceParams, mapQDepth, stidWidth))
  robTULinkSource := zeroTULinkSource
  when(robTULinkSourceMatched) {
    robTULinkSource.valid := true.B
    robTULinkSource.bid := rowBid(robTULinkSourceMatchIndex)
    robTULinkSource.rid := rowRid(robTULinkSourceMatchIndex)
    robTULinkSource.stid := rowStid(robTULinkSourceMatchIndex)
    robTULinkSource.tSeq := rowTSeq(robTULinkSourceMatchIndex)
    robTULinkSource.uSeq := rowUSeq(robTULinkSourceMatchIndex)
    robTULinkSource.dstValid := rowTUDstValid(robTULinkSourceMatchIndex)
    robTULinkSource.dstKind := rowTUDstKind(robTULinkSourceMatchIndex)
  }
  io.robTULinkSource := robTULinkSource
  io.robTULinkSourceMatched := robTULinkSourceMatched
  io.robTULinkSourceMultipleMatch := PopCount(robTULinkSourceMatchVec) > 1.U

  val sizeAfterFlush = size - flushPrune.io.residentDecrement
  val outstandingAfterFlush = outstandingCount - flushPrune.io.outstandingDecrement
  val flushClearedAll = flushApplied && (sizeAfterFlush === 0.U)
  io.flushClearedAll := flushClearedAll

  io.allocDuplicateIdentity := io.allocValid && duplicateVec.asUInt.orR
  io.allocReady := !flushApplied && (size =/= entries.U) && !io.allocDuplicateIdentity
  io.allocRobValue := allocValue
  io.allocRobWrap := allocWrap
  val allocFire = io.allocValid && io.allocReady

  val renameUpdateStatus = status(io.renameUpdateRid.value)
  val renameUpdateMayApply =
    io.renameUpdateRid.valid &&
      rows(io.renameUpdateRid.value).valid &&
      ROBID.equal(rowRid(io.renameUpdateRid.value), io.renameUpdateRid) &&
      (renameUpdateStatus === ROBEntryStatus.Allocated || renameUpdateStatus === ROBEntryStatus.Renamed)
  io.renameUpdateReady := !flushApplied && renameUpdateMayApply
  val renameUpdateFire = io.renameUpdateValid && io.renameUpdateReady
  io.renameUpdateAccepted := renameUpdateFire
  io.renameUpdateIgnored := io.renameUpdateValid && !io.renameUpdateReady

  val completeStatus = status(io.completeRobValue)
  val completeMayUpdate = mayComplete(completeStatus)
  val completeAccepted = !flushApplied && io.completeValid && completeMayUpdate
  io.completeAccepted := completeAccepted
  io.completeIgnored := io.completeValid && (!completeMayUpdate || flushApplied)

  val deallocMarkerWindowStop = Wire(Bool())
  val commitFireVec = Wire(Vec(traceParams.commitWidth, Bool()))
  val commitContinueVec = Wire(Vec(traceParams.commitWidth, Bool()))
  for (slot <- 0 until traceParams.commitWidth) {
    val idx = wrapIndex(commitValue, slot)
    val priorSlotsContinue =
      if (slot == 0) true.B else commitContinueVec(slot - 1)
    val markerWindowStop = rowMarkerBoundary(idx) || rowMarkerStop(idx)
    val fires =
      !flushApplied && !deallocMarkerWindowStop && priorSlotsContinue &&
        rows(idx).valid && ROBEntryStatus.canCommit(status(idx))
    commitFireVec(slot) := fires
    commitContinueVec(slot) := fires && !markerWindowStop

    val out = Wire(new CommitTraceRow(traceParams))
    out := rows(idx)
    out.valid := fires
    out.slot := slot.U
    commitPort.rows(slot) := Mux(fires, out, zeroRow)
  }
  io.commit := commitPort

  val commitCount = PopCount(commitFireVec)
  io.commitValidMask := commitFireVec.asUInt
  io.commitCount := commitCount

  val commitMonitor = Module(new CommitTraceMonitor(traceParams))
  commitMonitor.io.in := commitPort
  io.commitMonitorValidMask := commitMonitor.io.validMask
  io.commitMonitorValidCount := commitMonitor.io.validCount
  io.commitSkippedSlot := commitMonitor.io.skippedSlot
  io.commitDuplicateIdentity := commitMonitor.io.duplicateIdentity
  io.commitSlotMismatch := commitMonitor.io.slotMismatch
  io.commitInvalidSideEffect := commitMonitor.io.invalidSideEffect
  io.commitContractError := commitMonitor.io.contractError

  val deallocFireVec = Wire(Vec(traceParams.commitWidth, Bool()))
  val deallocContinueVec = Wire(Vec(traceParams.commitWidth, Bool()))
  val deallocMarkerWindowStopVec = Wire(Vec(traceParams.commitWidth, Bool()))
  for (slot <- 0 until traceParams.commitWidth) {
    val idx = wrapIndex(deallocValue, slot)
    val priorSlotsContinue =
      if (slot == 0) true.B else deallocContinueVec(slot - 1)
    deallocFireVec(slot) :=
      !flushApplied && io.deallocReady && priorSlotsContinue && rows(idx).valid && ROBEntryStatus.canDealloc(status(idx))
    deallocMarkerWindowStopVec(slot) := deallocFireVec(slot) && (rowMarkerBoundary(idx) || rowMarkerStop(idx))
    deallocContinueVec(slot) := deallocFireVec(slot) &&
      !rowIsLast(idx) && !rowMarkerBoundary(idx) && !rowMarkerStop(idx)
  }
  deallocMarkerWindowStop := deallocMarkerWindowStopVec.asUInt.orR
  val deallocCount = PopCount(deallocFireVec)
  io.deallocValidMask := deallocFireVec.asUInt
  io.deallocCount := deallocCount
  val deallocBlockLastVec = Wire(Vec(traceParams.commitWidth, Bool()))
  for (slot <- 0 until traceParams.commitWidth) {
    val idx = wrapIndex(deallocValue, slot)
    val source = Wire(new TULinkRetireSource(sourceParams, mapQDepth, stidWidth, peIdWidth))
    source := zeroTURetireSource
    source.valid := deallocFireVec(slot)
    source.isLast := rowIsLast(idx)
    source.bid := rowBid(idx)
    source.gid := rowGid(idx)
    source.rid := rowRid(idx)
    source.peId := rowPeId(idx)
    source.stid := rowStid(idx)
    source.tSeq := rowTSeq(idx)
    source.uSeq := rowUSeq(idx)
    source.dstValid := rowTUDstValid(idx)
    source.dstKind := rowTUDstKind(idx)
    io.deallocTURetireSource(slot) := source

    val markerSource = Wire(new BlockMarkerRetireSource(
      entries = entries,
      blockBidWidth = traceParams.blockBidWidth,
      pcWidth = traceParams.pcWidth,
      insnWidth = traceParams.insnWidth,
      lenWidth = traceParams.lenWidth,
      peIdWidth = peIdWidth,
      stidWidth = stidWidth
    ))
    markerSource := zeroBlockMarkerRetireSource
    markerSource.valid := deallocFireVec(slot) && (rowMarkerBoundary(idx) || rowMarkerStop(idx))
    markerSource.isBoundary := rowMarkerBoundary(idx)
    markerSource.isStop := rowMarkerStop(idx)
    markerSource.isLast := rowIsLast(idx)
    markerSource.bid := rowBid(idx)
    markerSource.gid := rowGid(idx)
    markerSource.rid := rowRid(idx)
    markerSource.peId := rowPeId(idx)
    markerSource.stid := rowStid(idx)
    markerSource.blockBidValid := rows(idx).blockBidValid
    markerSource.blockBid := rows(idx).blockBid
    markerSource.pc := rows(idx).pc
    markerSource.insn := rows(idx).insn
    markerSource.len := rows(idx).len
    markerSource.boundaryKind := rowMarkerBoundaryKind(idx)
    markerSource.boundaryTarget := rowMarkerBoundaryTarget(idx)
    io.deallocBlockMarkerRetireSource(slot) := markerSource

    deallocBlockLastVec(slot) := deallocFireVec(slot) && rowIsLast(idx)
  }
  val deallocBlockLastValid = deallocBlockLastVec.asUInt.orR
  val deallocBlockLastOH = PriorityEncoderOH(deallocBlockLastVec)
  val deallocBlockLastSlot = Wire(UInt(ptrWidth.W))
  deallocBlockLastSlot := 0.U
  if (traceParams.commitWidth > 1) {
    deallocBlockLastSlot := OHToUInt(deallocBlockLastOH)
  }
  val deallocBlockLastSum = deallocValue +& deallocBlockLastSlot
  val deallocBlockLastIndex =
    Mux(deallocBlockLastSum >= entries.U, deallocBlockLastSum - entries.U, deallocBlockLastSum)(ptrWidth - 1, 0)
  io.deallocBlockLastValid := deallocBlockLastValid
  io.deallocBlockLastBid := Mux(deallocBlockLastValid, rowBid(deallocBlockLastIndex), zeroRobId)
  io.deallocBlockLastGid := Mux(deallocBlockLastValid, rowGid(deallocBlockLastIndex), zeroRobId)
  io.deallocBlockLastBlockBid := Mux(deallocBlockLastValid, rows(deallocBlockLastIndex).blockBid, 0.U)

  io.empty := size === 0.U
  io.full := size === entries.U
  io.size := size
  io.outstandingCount := outstandingCount
  io.commitHeadValid := rows(commitValue).valid && ROBEntryStatus.occupiesRob(status(commitValue))
  io.commitHeadStatus := status(commitValue)
  io.commitHeadRobValue := commitValue
  io.deallocHeadValid := rows(deallocValue).valid && ROBEntryStatus.occupiesRob(status(deallocValue))
  io.deallocHeadStatus := status(deallocValue)
  io.deallocHeadRobValue := deallocValue

  for (idx <- 0 until entries) {
    when(flushPrune.io.pruneMask(idx)) {
      rows(idx) := zeroRow
      rowBid(idx) := zeroRobId
      rowGid(idx) := zeroRobId
      rowRid(idx) := zeroRobId
      rowPeId(idx) := 0.U
      rowStid(idx) := 0.U
      rowTSeq(idx) := zeroLocalSeq
      rowUSeq(idx) := zeroLocalSeq
      rowTUDstValid(idx) := false.B
      rowTUDstKind(idx) := DestinationKind.None
      rowIsLast(idx) := false.B
      rowMarkerBoundary(idx) := false.B
      rowMarkerStop(idx) := false.B
      rowMarkerBoundaryKind(idx) := BoundaryKind.Fall
      rowMarkerBoundaryTarget(idx) := 0.U
      status(idx) := ROBEntryStatus.Free
    }
  }

  when(renameUpdateFire) {
    val row = Wire(new CommitTraceRow(traceParams))
    row := io.renameUpdateRow
    row.valid := true.B
    row.rob.valid := true.B
    row.rob.wrap := rowRid(io.renameUpdateRid.value).wrap
    row.rob.value := rowRid(io.renameUpdateRid.value).value
    rows(io.renameUpdateRid.value) := row
    rowTSeq(io.renameUpdateRid.value) := io.renameUpdateTSeq
    rowUSeq(io.renameUpdateRid.value) := io.renameUpdateUSeq
    rowTUDstValid(io.renameUpdateRid.value) := io.renameUpdateTUDstValid
    rowTUDstKind(io.renameUpdateRid.value) := io.renameUpdateTUDstKind
    when(status(io.renameUpdateRid.value) === ROBEntryStatus.Allocated) {
      status(io.renameUpdateRid.value) := ROBEntryStatus.Renamed
    }
  }

  when(completeAccepted) {
    when(io.completeRowValid) {
      val row = Wire(new CommitTraceRow(traceParams))
      row := io.completeRow
      row.valid := true.B
      row.rob.valid := true.B
      row.rob.wrap := rowRid(io.completeRobValue).wrap
      row.rob.value := rowRid(io.completeRobValue).value
      rows(io.completeRobValue) := row
    }
    status(io.completeRobValue) := ROBEntryStatus.Completed
  }

  for (slot <- 0 until traceParams.commitWidth) {
    val idx = wrapIndex(commitValue, slot)
    when(commitFireVec(slot)) {
      status(idx) := ROBEntryStatus.Retired
    }
  }

  for (slot <- 0 until traceParams.commitWidth) {
    val idx = wrapIndex(deallocValue, slot)
    when(deallocFireVec(slot)) {
      rows(idx) := zeroRow
      rowBid(idx) := zeroRobId
      rowGid(idx) := zeroRobId
      rowRid(idx) := zeroRobId
      rowPeId(idx) := 0.U
      rowStid(idx) := 0.U
      rowTSeq(idx) := zeroLocalSeq
      rowUSeq(idx) := zeroLocalSeq
      rowTUDstValid(idx) := false.B
      rowTUDstKind(idx) := DestinationKind.None
      rowIsLast(idx) := false.B
      rowMarkerBoundary(idx) := false.B
      rowMarkerStop(idx) := false.B
      rowMarkerBoundaryKind(idx) := BoundaryKind.Fall
      rowMarkerBoundaryTarget(idx) := 0.U
      status(idx) := ROBEntryStatus.Free
    }
  }

  when(allocFire) {
    val row = Wire(new CommitTraceRow(traceParams))
    row := io.allocRow
    row.valid := true.B
    row.rob.valid := true.B
    row.rob.wrap := allocWrap
    row.rob.value := allocValue
    rows(allocValue) := row
    rowBid(allocValue) := storedAllocBid
    rowGid(allocValue) := io.allocGid
    rowRid(allocValue) := allocatedRid
    rowPeId(allocValue) := io.allocPeId
    rowStid(allocValue) := io.allocStid
    rowTSeq(allocValue) := io.allocTSeq
    rowUSeq(allocValue) := io.allocUSeq
    rowTUDstValid(allocValue) := io.allocTUDstValid
    rowTUDstKind(allocValue) := io.allocTUDstKind
    rowIsLast(allocValue) := io.allocIsLast
    rowMarkerBoundary(allocValue) := io.allocMarkerBoundary
    rowMarkerStop(allocValue) := io.allocMarkerStop
    rowMarkerBoundaryKind(allocValue) := io.allocMarkerBoundaryKind
    rowMarkerBoundaryTarget(allocValue) := io.allocMarkerBoundaryTarget
    status(allocValue) := ROBEntryStatus.Allocated
  }

  when(commitCount =/= 0.U) {
    val (nextCommitValue, nextCommitWrap) = advance(commitValue, commitWrap, commitCount)
    commitValue := nextCommitValue
    commitWrap := nextCommitWrap
  }

  when(deallocCount =/= 0.U) {
    val (nextDeallocValue, nextDeallocWrap) = advance(deallocValue, deallocWrap, deallocCount)
    deallocValue := nextDeallocValue
    deallocWrap := nextDeallocWrap
  }

  when(allocFire) {
    val (nextAllocValue, nextAllocWrap) = advance(allocValue, allocWrap, 1.U)
    allocValue := nextAllocValue
    allocWrap := nextAllocWrap
  }

  when(flushApplied) {
    val nextAllocValue = flushPrune.io.firstPruneValue
    val nextAllocWrap = scanWrapFor(nextAllocValue, deallocValue, deallocWrap)
    allocValue := nextAllocValue
    allocWrap := nextAllocWrap

    when(flushClearedAll) {
      commitValue := nextAllocValue
      commitWrap := nextAllocWrap
      deallocValue := nextAllocValue
      deallocWrap := nextAllocWrap
    }.elsewhen(flushPrune.io.commitRebaseNeeded) {
      commitValue := flushPrune.io.commitRebaseValue
      commitWrap := scanWrapFor(flushPrune.io.commitRebaseValue, deallocValue, deallocWrap)
    }.elsewhen(outstandingAfterFlush === 0.U) {
      commitValue := nextAllocValue
      commitWrap := nextAllocWrap
    }
  }

  val allocDelta = Wire(UInt(sizeWidth.W))
  val commitDelta = Wire(UInt(sizeWidth.W))
  val deallocDelta = Wire(UInt(sizeWidth.W))
  val flushResidentDelta = Wire(UInt(sizeWidth.W))
  val flushOutstandingDelta = Wire(UInt(sizeWidth.W))
  allocDelta := allocFire.asUInt
  commitDelta := commitCount
  deallocDelta := deallocCount
  flushResidentDelta := Mux(flushApplied, flushPrune.io.residentDecrement, 0.U)
  flushOutstandingDelta := Mux(flushApplied, flushPrune.io.outstandingDecrement, 0.U)

  size := size + allocDelta - deallocDelta - flushResidentDelta
  outstandingCount := outstandingCount + allocDelta - commitDelta - flushOutstandingDelta
}
