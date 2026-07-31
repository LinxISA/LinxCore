package linxcore.ooo

import chisel3._
import chisel3.util._
import linxcore.params.CoreParams
import linxcore.top.interface._

class CommitControlIO(val p: CoreParams) extends Bundle {
  val rob = Flipped(Valid(new OOORobCommitPreview(p)))
  val interrupts = Input(Vec(p.ooo.stidCount, new InterruptRequest(p)))
  val renameReleaseAck = Input(Bool())
  val brobReleaseAck = Input(Bool())
  val out = Decoupled(new CommitControlTxn(p))
}

class CommitControl(val p: CoreParams) extends Module {
  val io = IO(new CommitControlIO(p))

  val heldValid = RegInit(false.B)
  val held = Reg(new CommitControlTxn(p))

  val next = Wire(new CommitControlTxn(p))
  next := 0.U.asTypeOf(next)
  next.commit.count := io.rob.bits.count
  next.rename.count := io.rob.bits.count
  next.robRelease.count := io.rob.bits.count
  next.brobRelease.count := io.rob.bits.count
  for (lane <- 0 until p.widths.retireWidth) {
    next.commit.entries(lane) := io.rob.bits.entries(lane).commit
    next.rename.lanes(lane) := io.rob.bits.entries(lane).rename
    next.robRelease.lanes(lane).valid := io.rob.bits.entries(lane).valid
    next.robRelease.lanes(lane).rob :=
      io.rob.bits.entries(lane).commit.rob
    next.brobRelease.entries(lane) := io.rob.bits.entries(lane).commit.rob
  }
  val headTrap = io.rob.valid && io.rob.bits.count =/= 0.U &&
    io.rob.bits.entries(0).commit.trap.valid
  val anyInterrupt = io.interrupts.map(_.valid).reduce(_ || _)
  val bestInterrupt = io.interrupts.reduce { (a, b) =>
    Mux(a.valid && (!b.valid || a.priority >= b.priority), a, b)
  }
  next.trap := 0.U.asTypeOf(next.trap)
  when(headTrap) {
    next.trap := io.rob.bits.entries(0).commit.trap
  }.elsewhen(anyInterrupt) {
    next.trap.valid := true.B
    next.trap.kind := TrapKind.Interrupt
    next.trap.cause := bestInterrupt.cause
  }

  val robTxnValid = io.rob.valid && io.rob.bits.count =/= 0.U
  io.out.valid := heldValid || robTxnValid
  io.out.bits := Mux(heldValid, held, next)
  val txnAccepted = io.out.fire && io.renameReleaseAck && io.brobReleaseAck
  when(robTxnValid && !heldValid && !txnAccepted) {
    held := next
    heldValid := true.B
  }.elsewhen(txnAccepted) {
    heldValid := false.B
  }
}
