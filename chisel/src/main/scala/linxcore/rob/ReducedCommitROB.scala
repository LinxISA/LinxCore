package linxcore.rob

import chisel3._
import chisel3.util.{log2Ceil, PopCount}
import linxcore.commit.{CommitTraceParams, CommitTracePort, CommitTraceRow}

class ReducedCommitROBIO(val entries: Int, val traceParams: CommitTraceParams) extends Bundle {
  private val ptrWidth = log2Ceil(entries)
  private val sizeWidth = log2Ceil(entries + 1)

  val allocValid = Input(Bool())
  val allocReady = Output(Bool())
  val allocDuplicateIdentity = Output(Bool())
  val allocRow = Input(new CommitTraceRow(traceParams))

  val completeValid = Input(Bool())
  val completeRobValue = Input(UInt(ptrWidth.W))

  val commit = Output(new CommitTracePort(traceParams))
  val commitValidMask = Output(UInt(traceParams.commitWidth.W))
  val commitCount = Output(UInt(log2Ceil(traceParams.commitWidth + 1).W))

  val empty = Output(Bool())
  val full = Output(Bool())
  val size = Output(UInt(sizeWidth.W))
  val headValid = Output(Bool())
  val headComplete = Output(Bool())
  val headRobValue = Output(UInt(ptrWidth.W))
}

class ReducedCommitROB(
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

  val io = IO(new ReducedCommitROBIO(entries, traceParams))

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

  private def sameIdentity(lhs: CommitTraceRow, rhs: CommitTraceRow): Bool =
    (lhs.identity.bid === rhs.identity.bid) &&
      (lhs.identity.gid === rhs.identity.gid) &&
      (lhs.identity.rid === rhs.identity.rid)

  val table = Reg(Vec(entries, new CommitTraceRow(traceParams)))
  val valid = RegInit(VecInit(Seq.fill(entries)(false.B)))
  val complete = RegInit(VecInit(Seq.fill(entries)(false.B)))
  val headValue = RegInit(0.U(ptrWidth.W))
  val headWrap = RegInit(false.B)
  val tailValue = RegInit(0.U(ptrWidth.W))
  val tailWrap = RegInit(false.B)
  val size = RegInit(0.U(sizeWidth.W))

  val duplicateVec = Wire(Vec(entries, Bool()))
  for (idx <- 0 until entries) {
    duplicateVec(idx) := valid(idx) && sameIdentity(table(idx), io.allocRow)
  }
  io.allocDuplicateIdentity := io.allocValid && duplicateVec.asUInt.orR
  io.allocReady := (size =/= entries.U) && !io.allocDuplicateIdentity
  val allocFire = io.allocValid && io.allocReady

  val commitFireVec = Wire(Vec(traceParams.commitWidth, Bool()))
  for (slot <- 0 until traceParams.commitWidth) {
    val idx = wrapIndex(headValue, slot)
    val priorSlotsFire =
      if (slot == 0) true.B else commitFireVec.take(slot).reduce(_ && _)
    val fires = priorSlotsFire && valid(idx) && complete(idx)
    commitFireVec(slot) := fires

    val out = Wire(new CommitTraceRow(traceParams))
    out := table(idx)
    out.valid := fires
    out.slot := slot.U
    io.commit.rows(slot) := Mux(fires, out, zeroRow)
  }

  val commitCount = PopCount(commitFireVec)
  io.commitValidMask := commitFireVec.asUInt
  io.commitCount := commitCount

  io.empty := size === 0.U
  io.full := size === entries.U
  io.size := size
  io.headValid := valid(headValue)
  io.headComplete := valid(headValue) && complete(headValue)
  io.headRobValue := headValue

  when(io.completeValid && valid(io.completeRobValue)) {
    complete(io.completeRobValue) := true.B
  }

  for (slot <- 0 until traceParams.commitWidth) {
    val idx = wrapIndex(headValue, slot)
    when(commitFireVec(slot)) {
      valid(idx) := false.B
      complete(idx) := false.B
    }
  }

  when(allocFire) {
    val row = Wire(new CommitTraceRow(traceParams))
    row := io.allocRow
    row.valid := true.B
    row.rob.valid := true.B
    row.rob.wrap := tailWrap
    row.rob.value := tailValue
    table(tailValue) := row
    valid(tailValue) := true.B
    complete(tailValue) := false.B
  }

  when(commitCount =/= 0.U) {
    val (nextHeadValue, nextHeadWrap) = advance(headValue, headWrap, commitCount)
    headValue := nextHeadValue
    headWrap := nextHeadWrap
  }

  when(allocFire) {
    val (nextTailValue, nextTailWrap) = advance(tailValue, tailWrap, 1.U)
    tailValue := nextTailValue
    tailWrap := nextTailWrap
  }

  val nextSize = size + allocFire.asUInt - commitCount
  size := nextSize
}
