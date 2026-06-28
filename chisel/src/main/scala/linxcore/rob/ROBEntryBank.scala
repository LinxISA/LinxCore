package linxcore.rob

import chisel3._
import chisel3.util.{log2Ceil, PopCount}
import linxcore.commit.{CommitTraceMonitor, CommitTraceParams, CommitTracePort, CommitTraceRow}
import linxcore.recovery.FlushBus

class ROBEntryBankIO(val entries: Int, val traceParams: CommitTraceParams) extends Bundle {
  private val ptrWidth = log2Ceil(entries)
  private val sizeWidth = log2Ceil(entries + 1)

  val flush = Input(new FlushBus(entries))

  val allocValid = Input(Bool())
  val allocReady = Output(Bool())
  val allocDuplicateIdentity = Output(Bool())
  val allocRow = Input(new CommitTraceRow(traceParams))
  val allocRobValue = Output(UInt(ptrWidth.W))

  val completeValid = Input(Bool())
  val completeRobValue = Input(UInt(ptrWidth.W))
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
    val traceParams: CommitTraceParams = CommitTraceParams())
    extends Module {
  require(entries > 1, "ROB entries must be greater than one")
  require((entries & (entries - 1)) == 0, "ROB entries must be a power of two")
  require(traceParams.commitWidth > 0, "commitWidth must be positive")
  require(traceParams.commitWidth <= entries, "commitWidth cannot exceed entries")
  require(traceParams.robValueWidth >= log2Ceil(entries), "ROB trace value must hold entry index")

  private val ptrWidth = log2Ceil(entries)
  private val sizeWidth = log2Ceil(entries + 1)

  val io = IO(new ROBEntryBankIO(entries, traceParams))

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

  private def identityToRobId(raw: UInt, valid: Bool): ROBID = {
    val id = Wire(new ROBID(entries))
    id.valid := valid
    id.value := raw(ptrWidth - 1, 0)
    id.wrap := (if (ptrWidth < raw.getWidth) raw(ptrWidth) else false.B)
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
    flushPrune.io.rows(idx).bid := identityToRobId(rows(idx).identity.bid, rowValid)
    flushPrune.io.rows(idx).rid := identityToRobId(rows(idx).identity.rid, rowValid)
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

  val sizeAfterFlush = size - flushPrune.io.residentDecrement
  val outstandingAfterFlush = outstandingCount - flushPrune.io.outstandingDecrement
  val flushClearedAll = flushApplied && (sizeAfterFlush === 0.U)
  io.flushClearedAll := flushClearedAll

  io.allocDuplicateIdentity := io.allocValid && duplicateVec.asUInt.orR
  io.allocReady := !flushApplied && (size =/= entries.U) && !io.allocDuplicateIdentity
  io.allocRobValue := allocValue
  val allocFire = io.allocValid && io.allocReady

  val completeStatus = status(io.completeRobValue)
  val completeMayUpdate = mayComplete(completeStatus)
  val completeAccepted = !flushApplied && io.completeValid && completeMayUpdate
  io.completeAccepted := completeAccepted
  io.completeIgnored := io.completeValid && (!completeMayUpdate || flushApplied)

  val commitFireVec = Wire(Vec(traceParams.commitWidth, Bool()))
  for (slot <- 0 until traceParams.commitWidth) {
    val idx = wrapIndex(commitValue, slot)
    val priorSlotsFire =
      if (slot == 0) true.B else commitFireVec.take(slot).reduce(_ && _)
    val fires = !flushApplied && priorSlotsFire && rows(idx).valid && ROBEntryStatus.canCommit(status(idx))
    commitFireVec(slot) := fires

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
  for (slot <- 0 until traceParams.commitWidth) {
    val idx = wrapIndex(deallocValue, slot)
    val priorSlotsFire =
      if (slot == 0) true.B else deallocFireVec.take(slot).reduce(_ && _)
    deallocFireVec(slot) :=
      !flushApplied && io.deallocReady && priorSlotsFire && rows(idx).valid && ROBEntryStatus.canDealloc(status(idx))
  }
  val deallocCount = PopCount(deallocFireVec)
  io.deallocValidMask := deallocFireVec.asUInt
  io.deallocCount := deallocCount

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
      status(idx) := ROBEntryStatus.Free
    }
  }

  when(completeAccepted) {
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
