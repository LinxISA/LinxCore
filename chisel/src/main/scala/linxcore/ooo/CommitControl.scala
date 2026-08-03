package linxcore.ooo

import chisel3._
import chisel3.util._
import linxcore.params.CoreParams
import linxcore.top.interface._

class CommitControlIO(val p: CoreParams) extends Bundle {
  val rob = Flipped(Valid(new OOORobCommitPreview(p)))
  val residentHeads = Input(Vec(p.ooo.stidCount,
    new OOORobResidentHeadPreview(p)))
  val recoveryFence = Input(Vec(p.ooo.stidCount, Bool()))
  val interrupts = Input(Vec(p.ooo.stidCount, new InterruptRequest(p)))
  val interruptBoundaryValid = Input(Bool())
  val interruptBoundary = Input(new RobIdentity(p))
  val robReleaseReady = Input(Bool())
  val renameReleaseReady = Input(Bool())
  val brobReleaseReady = Input(Bool())
  val pcBufferCommitReady = Input(Bool())
  val out = Decoupled(new CommitControlTxn(p))
  val robNoflushReady = Flipped(Decoupled(new RobNoflushReadyTxn(p)))
  val robNoflush = Decoupled(new RobNoflushTxn(p))
}

class CommitControl(val p: CoreParams) extends Module {
  val io = IO(new CommitControlIO(p))
  private def recoveryFenced(stid: UInt): Bool =
    if (p.ooo.stidCount == 1) io.recoveryFence(0) else io.recoveryFence(stid)

  val heldValid = RegInit(false.B)
  val held = Reg(new CommitControlTxn(p))
  val acceptedValid = RegInit(false.B)
  val acceptedTxn = Reg(new CommitControlTxn(p))

  val next = Wire(new CommitControlTxn(p))
  next := 0.U.asTypeOf(next)
  next.commit.count := io.rob.bits.count
  next.rename.count := io.rob.bits.count
  next.robRelease.count := io.rob.bits.count
  next.brobRelease.count := io.rob.bits.count
  for (lane <- 0 until p.widths.retireWidth) {
    when(lane.U < io.rob.bits.count) {
      next.commit.entries(lane) := io.rob.bits.entries(lane).commit
      next.rename.lanes(lane) := io.rob.bits.entries(lane).rename
      next.robRelease.lanes(lane).valid := io.rob.bits.entries(lane).valid
      next.robRelease.lanes(lane).rob :=
        io.rob.bits.entries(lane).commit.rob
      next.brobRelease.entries(lane) := io.rob.bits.entries(lane).commit.rob
    }
  }
  val firstEntryTrap = io.rob.valid && io.rob.bits.count =/= 0.U &&
    io.rob.bits.entries(0).commit.trap.valid
  val headTrap = io.rob.valid && (io.rob.bits.headTrap.valid || firstEntryTrap)
  val anyInterrupt = io.interrupts.map(_.valid).reduce(_ || _)
  val bestInterrupt = io.interrupts.reduce { (a, b) =>
    Mux(a.valid && (!b.valid || a.priority >= b.priority), a, b)
  }
  next.trap := 0.U.asTypeOf(next.trap)
  when(headTrap) {
    next.trap := Mux(io.rob.bits.headTrap.valid,
      io.rob.bits.headTrap,
      io.rob.bits.entries(0).commit.trap)
  }.elsewhen(anyInterrupt && io.interruptBoundaryValid) {
    next.trap.valid := true.B
    next.trap.kind := TrapKind.Interrupt
    next.trap.cause := bestInterrupt.cause
    next.trap.rob := io.interruptBoundary
  }

  val candidate = Wire(new CommitControlTxn(p))
  candidate := Mux(heldValid, held, next)
  val candidateHasTxn = candidate.commit.count =/= 0.U || candidate.trap.valid
  val sameAccepted = acceptedValid && candidateHasTxn &&
    candidate.asUInt === acceptedTxn.asUInt
  val robTxnValid = io.rob.valid && !sameAccepted &&
    (io.rob.bits.count =/= 0.U || io.rob.bits.headTrap.valid ||
      (anyInterrupt && io.interruptBoundaryValid))
  val candidateValid = heldValid || robTxnValid
  val hasReleaseLanes = candidate.commit.count =/= 0.U
  val releaseReady = io.robReleaseReady && io.renameReleaseReady &&
    io.brobReleaseReady && io.pcBufferCommitReady
  io.out.valid := candidateValid && (!hasReleaseLanes || releaseReady)
  io.out.bits := candidate
  val txnAccepted = io.out.fire
  when(robTxnValid && !heldValid && !txnAccepted) {
    held := next
    heldValid := true.B
  }.elsewhen(txnAccepted) {
    heldValid := false.B
    acceptedTxn := candidate
    acceptedValid := true.B
  }
  when(!txnAccepted &&
    (!io.rob.valid || (acceptedValid && candidateHasTxn &&
      candidate.asUInt =/= acceptedTxn.asUInt))) {
    acceptedValid := false.B
  }

  val noflushAcceptedValid = RegInit(VecInit(
    Seq.fill(p.ooo.stidCount)(false.B)))
  val noflushAcceptedRob = Reg(Vec(p.ooo.stidCount, new RobIdentity(p)))
  for (stid <- 0 until p.ooo.stidCount) {
    when(!io.residentHeads(stid).valid ||
      io.residentHeads(stid).rob.asUInt =/= noflushAcceptedRob(stid).asUInt) {
      noflushAcceptedValid(stid) := false.B
    }
  }

  val readyStidRaw = io.robNoflushReady.bits.rob.stid
  val readyStidInRange = readyStidRaw < p.ooo.stidCount.U
  val readyStid = Mux(readyStidInRange, readyStidRaw, 0.U)
  val readyHead = if (p.ooo.stidCount == 1) io.residentHeads(0)
    else io.residentHeads(readyStid)
  val readyIdentityExact = readyStidInRange && readyHead.valid &&
    readyHead.noflushEligible &&
    readyHead.transactionId === io.robNoflushReady.bits.transactionId &&
    readyHead.instruction.asUInt === io.robNoflushReady.bits.instruction.asUInt &&
    readyHead.rob.asUInt === io.robNoflushReady.bits.rob.asUInt
  val readyAlreadyAccepted = readyStidInRange &&
    (if (p.ooo.stidCount == 1) noflushAcceptedValid(0)
      else noflushAcceptedValid(readyStid)) &&
    (if (p.ooo.stidCount == 1)
      noflushAcceptedRob(0).asUInt === readyHead.rob.asUInt
    else noflushAcceptedRob(readyStid).asUInt === readyHead.rob.asUInt)
  val readyFenced = readyStidInRange && recoveryFenced(readyStid)
  val noflushCandidate = io.robNoflushReady.valid && readyIdentityExact &&
    !readyAlreadyAccepted && !readyFenced

  io.robNoflush.valid := noflushCandidate
  io.robNoflush.bits := 0.U.asTypeOf(io.robNoflush.bits)
  io.robNoflush.bits.transactionId := io.robNoflushReady.bits.transactionId
  io.robNoflush.bits.instruction := io.robNoflushReady.bits.instruction
  io.robNoflush.bits.rob := io.robNoflushReady.bits.rob
  io.robNoflushReady.ready := !readyIdentityExact || readyAlreadyAccepted ||
    (!readyFenced && io.robNoflush.ready)
  when(io.robNoflush.fire) {
    if (p.ooo.stidCount == 1) {
      noflushAcceptedValid(0) := true.B
      noflushAcceptedRob(0) := io.robNoflush.bits.rob
    } else {
      noflushAcceptedValid(readyStid) := true.B
      noflushAcceptedRob(readyStid) := io.robNoflush.bits.rob
    }
  }
}
