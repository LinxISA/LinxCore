package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, Valid}

class OooIexP1I2LaneIO(val p: OooParams = OooParams()) extends Bundle {
  val p1 = Flipped(Decoupled(new OooIexP1Request(p)))

  /** One whole-uop request. The shared arbiter grants every requested P/T/U
    * source and the optional PC read together or denies the attempt together.
    */
  val readAttempt = Valid(new OooIexI1ReadAttempt(p))
  val readDecisionValid = Input(Bool())
  val readGrant = Input(Bool())
  val sourceDataValid = Input(UInt(p.maxSourceOperands.W))
  val sourceData = Input(Vec(p.maxSourceOperands, UInt(p.pcWidth.W)))
  val pcDataValid = Input(Bool())
  val pcData = Input(UInt(p.pcWidth.W))

  val i2 = Decoupled(new OooIexI2Transaction(p))
  val recoveryApply = Flipped(Valid(new OooResidencyRecoveryPlan(p)))

  val repick = Valid(new OooIexReadRepick(p))
  val p1Rejected = Valid(new OooIexP1Reject(p))
  val readRejected = Valid(new OooIexReadReject(p))
  // Index 0 reports I1 cancellation and index 1 reports retained I2
  // cancellation. Both can be valid when one recovery kills both stages.
  val recoveryCanceled = Output(Vec(2, Valid(new OooIexReadRepick(p))))

  val i1Occupied = Output(Bool())
  val i2Occupied = Output(Bool())
  val empty = Output(Bool())
}

/** One reusable canonical P1/I1/I2 issue lane.
  *
  * P1 accepts one exact resident IQ row. I1 exposes a single atomic read
  * attempt covering every valid P/T/U source plus the optional PC token. A
  * shared read arbiter returns one explicit decision: denial releases the
  * attempt for exact repick, while a grant may advance only when every
  * requested readyless read is valid in that cycle. I2 retains the complete
  * row, operand data, and reconstructed PC under downstream backpressure.
  *
  * This lane owns only pipeline residency. The physical IQ, RFs, PC buffer,
  * picker, and execution pipe remain separate single owners.
  */
class OooIexP1I2Lane(val p: OooParams = OooParams()) extends Module {
  val io = IO(new OooIexP1I2LaneIO(p))

  val i1Valid = RegInit(false.B)
  val i1Request = Reg(new OooIexP1Request(p))
  val i2Valid = RegInit(false.B)
  val i2Transaction = Reg(new OooIexI2Transaction(p))

  private def killedByRecovery(row: OooIexIssueRow): Bool =
    io.recoveryApply.valid && io.recoveryApply.bits.valid &&
      OooRecoveryMembership.memberKilled(p, io.recoveryApply.bits, row.member)

  private def repickFrom(row: OooIexIssueRow): OooIexReadRepick = {
    val result = Wire(new OooIexReadRepick(p))
    result.member := row.member
    result.reservation := row.reservation
    result
  }

  val p1Row = io.p1.bits.row
  val p1Member = p1Row.member
  val p1Reservation = p1Row.reservation
  val p1ClassInRange = p1Reservation.uopClass.asUInt < p.iqClassCount.U
  val p1BankInRange = p1Reservation.bank < p.iqBankCount.U
  val p1EntryInRange =
    p1Reservation.speculativeSlot < p.iqEntriesPerBank.U
  val p1IdentityExact = p1Row.valid && p1Member.group.valid &&
    p1Member.bid.valid && p1Reservation.valid && p1ClassInRange &&
    p1BankInRange && p1EntryInRange &&
    p1Row.peId === p1Member.group.peId &&
    p1Row.stid === p1Member.group.stid &&
    p1Row.uopKey.primaryParent.valid &&
    p1Row.uopKey.primaryParent.peId === p1Row.peId &&
    p1Row.uopKey.primaryParent.stid === p1Row.stid
  val p1SourcesReady = p1Row.sources.map(source =>
    !source.valid || source.ready || source.specReady).reduce(_ && _)
  val p1ParentIndexInRange =
    io.p1.bits.pcParentIndex < p.maxArchitecturalParentRefs.U
  val safeP1ParentIndex = Mux(
    p1ParentIndexInRange, io.p1.bits.pcParentIndex, 0.U)
  val selectedP1PcToken = p1Row.parentPcTokens(safeP1ParentIndex)
  val p1PcTokenExact = !io.p1.bits.pcReadRequired ||
    (p1ParentIndexInRange &&
      io.p1.bits.pcParentIndex < p1Row.parentCount &&
      selectedP1PcToken.valid)
  val p1ShapeExact = p1IdentityExact && p1SourcesReady && p1PcTokenExact
  val p1Killed = killedByRecovery(p1Row)

  val i1Killed = i1Valid && killedByRecovery(i1Request.row)
  val i2Killed = i2Valid && killedByRecovery(i2Transaction.row)
  val i2WillDrain = i2Valid && !i2Killed && io.i2.ready
  val i2CanAccept = !i2Valid || i2WillDrain || i2Killed

  val i1SourceMask = VecInit(
    i1Request.row.sources.map(_.valid)).asUInt
  val i1ParentIndexInRange =
    i1Request.pcParentIndex < p.maxArchitecturalParentRefs.U
  val safeI1ParentIndex = Mux(
    i1ParentIndexInRange, i1Request.pcParentIndex, 0.U)
  val i1PcToken = i1Request.row.parentPcTokens(safeI1ParentIndex)

  io.readAttempt.valid := i1Valid && !i1Killed && i2CanAccept
  io.readAttempt.bits.member := i1Request.row.member
  io.readAttempt.bits.reservation := i1Request.row.reservation
  io.readAttempt.bits.stid := i1Request.row.stid
  io.readAttempt.bits.epoch := i1Request.row.epoch
  io.readAttempt.bits.transactionId := i1Request.row.transactionId
  io.readAttempt.bits.sourceMask := i1SourceMask
  io.readAttempt.bits.sources := i1Request.row.sources
  io.readAttempt.bits.pcRequired := i1Request.pcReadRequired
  io.readAttempt.bits.pcToken := i1PcToken

  val readDecision = io.readAttempt.valid && io.readDecisionValid
  val readDenied = readDecision && !io.readGrant
  val readResponseExact = io.sourceDataValid === i1SourceMask &&
    (!i1Request.pcReadRequired || io.pcDataValid)
  val readAccepted = readDecision && io.readGrant && readResponseExact
  val readInvalid = readDecision && io.readGrant && !readResponseExact
  val i1WillClear = i1Killed || readDenied || readAccepted || readInvalid
  // Do not replace a denied/invalid attempt on the same edge: the single
  // exact retry channel must never need to return two different members.
  val i1CanAccept = !i1Valid || readAccepted || i1Killed

  // Invalid P1 payloads are consumed as typed rejects when the lane has room;
  // a malformed producer cannot wedge the ready/valid boundary forever.
  io.p1.ready := i1CanAccept && !p1Killed
  io.p1Rejected.valid := io.p1.fire && !p1ShapeExact
  io.p1Rejected.bits.member := p1Member
  io.p1Rejected.bits.reservation := p1Reservation
  io.p1Rejected.bits.identityExact := p1IdentityExact
  io.p1Rejected.bits.sourcesReady := p1SourcesReady
  io.p1Rejected.bits.pcTokenExact := p1PcTokenExact

  val p1Failed = io.p1.fire && !p1ShapeExact
  io.repick.valid := readDenied || readInvalid || p1Failed
  io.repick.bits := Mux(p1Failed,
    repickFrom(p1Row), repickFrom(i1Request.row))
  io.readRejected.valid := readInvalid
  io.readRejected.bits.member := i1Request.row.member
  io.readRejected.bits.reservation := i1Request.row.reservation
  io.readRejected.bits.sourceMask := i1SourceMask
  io.readRejected.bits.sourceDataValid := io.sourceDataValid
  io.readRejected.bits.pcRequired := i1Request.pcReadRequired
  io.readRejected.bits.pcDataValid := io.pcDataValid

  io.recoveryCanceled(0).valid := i1Killed
  io.recoveryCanceled(0).bits := repickFrom(i1Request.row)
  io.recoveryCanceled(1).valid := i2Killed
  io.recoveryCanceled(1).bits := repickFrom(i2Transaction.row)

  io.i2.valid := i2Valid && !i2Killed
  io.i2.bits := i2Transaction
  io.i1Occupied := i1Valid && !i1Killed
  io.i2Occupied := i2Valid && !i2Killed
  io.empty := !io.i1Occupied && !io.i2Occupied

  when(i2WillDrain || i2Killed) {
    i2Valid := false.B
  }
  when(readAccepted) {
    i2Valid := true.B
    i2Transaction.row := i1Request.row
    i2Transaction.sourceMask := i1SourceMask
    i2Transaction.sourceData := io.sourceData
    i2Transaction.pcValid := i1Request.pcReadRequired
    i2Transaction.pc := Mux(i1Request.pcReadRequired, io.pcData, 0.U)
  }

  when(i1WillClear) {
    i1Valid := false.B
  }
  when(io.p1.fire && p1ShapeExact) {
    i1Valid := true.B
    i1Request := io.p1.bits
  }

}
