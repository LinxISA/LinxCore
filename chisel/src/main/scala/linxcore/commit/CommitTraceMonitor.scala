package linxcore.commit

import chisel3._
import chisel3.util.{log2Ceil, PopCount}

class CommitTraceMonitorIO(val p: CommitTraceParams = CommitTraceParams()) extends Bundle {
  val in = Input(new CommitTracePort(p))

  val validMask = Output(UInt(p.commitWidth.W))
  val validCount = Output(UInt(log2Ceil(p.commitWidth + 1).W))
  val skippedSlot = Output(Bool())
  val duplicateIdentity = Output(Bool())
  val slotMismatch = Output(Bool())
  val invalidSideEffect = Output(Bool())
  val contractError = Output(Bool())
}

class CommitTraceMonitor(val p: CommitTraceParams = CommitTraceParams()) extends Module {
  val io = IO(new CommitTraceMonitorIO(p))

  private def sameIdentity(lhs: CommitTraceRow, rhs: CommitTraceRow): Bool =
    (lhs.identity.bid === rhs.identity.bid) &&
      (lhs.identity.gid === rhs.identity.gid) &&
      (lhs.identity.rid === rhs.identity.rid)

  private def hasSideEffect(row: CommitTraceRow): Bool =
    row.wb.valid || row.src0.valid || row.src1.valid || row.dst.valid ||
      row.mem.valid || row.trap.valid

  val validVec = VecInit(io.in.rows.map(_.valid))
  io.validMask := validVec.asUInt
  io.validCount := PopCount(validVec)

  val skippedVec = (0 until p.commitWidth).map { slot =>
    if (slot == 0) {
      false.B
    } else {
      io.in.rows(slot).valid && !validVec.take(slot).reduce(_ && _)
    }
  }
  io.skippedSlot := VecInit(skippedVec).asUInt.orR

  val duplicateVec = for {
    lhs <- 0 until p.commitWidth
    rhs <- (lhs + 1) until p.commitWidth
  } yield {
    io.in.rows(lhs).valid && io.in.rows(rhs).valid &&
      sameIdentity(io.in.rows(lhs), io.in.rows(rhs))
  }
  io.duplicateIdentity := {
    if (duplicateVec.isEmpty) false.B else VecInit(duplicateVec).asUInt.orR
  }

  io.slotMismatch := VecInit((0 until p.commitWidth).map { slot =>
    io.in.rows(slot).valid && (io.in.rows(slot).slot =/= slot.U)
  }).asUInt.orR

  io.invalidSideEffect := VecInit(io.in.rows.map { row =>
    !row.valid && hasSideEffect(row)
  }).asUInt.orR

  io.contractError := io.skippedSlot || io.duplicateIdentity ||
    io.slotMismatch || io.invalidSideEffect
}
