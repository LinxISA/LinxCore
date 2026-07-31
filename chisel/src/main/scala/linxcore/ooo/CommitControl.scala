package linxcore.ooo

import chisel3._
import chisel3.util._
import linxcore.params.CoreParams
import linxcore.top.interface._

class CommitControlIO(val p: CoreParams) extends Bundle {
  val rob = Flipped(Valid(new OOORobCommitPreview(p)))
  val interrupts = Input(Vec(p.ooo.stidCount, new InterruptRequest(p)))
  val interruptBoundaryValid = Input(Bool())
  val interruptBoundary = Input(new RobIdentity(p))
  val robReleaseReady = Input(Bool())
  val renameReleaseReady = Input(Bool())
  val brobReleaseReady = Input(Bool())
  val out = Decoupled(new CommitControlTxn(p))
}

class CommitControl(val p: CoreParams) extends Module {
  val io = IO(new CommitControlIO(p))

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
  val legacyEntryTrap = io.rob.valid && io.rob.bits.count =/= 0.U &&
    io.rob.bits.entries(0).commit.trap.valid
  val headTrap = io.rob.valid && (io.rob.bits.headTrap.valid || legacyEntryTrap)
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
    io.brobReleaseReady
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
}
