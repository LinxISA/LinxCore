package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, Valid}
import linxcore.params.CoreParams
import linxcore.top.interface._

class OooD3ReservationAllocatorIO(val p: CoreParams) extends Bundle {
  val in = Flipped(Decoupled(new D3RenameGroup(p)))
  val robPrepared = Input(new OOORobPrepared(p))
  val brobPrepared = Input(new BROBPrepared(p))
  val advance = Input(UInt(PrefixPacketContract.countWidth(p.ooo.d3PrefixWidth).W))
  val pendingValid = Output(Bool())
  val pending = Output(new D3RenameGroup(p))
  val cursor = Output(UInt(PrefixPacketContract.countWidth(p.ooo.d3PrefixWidth).W))
  val transactionBase = Output(UInt(p.transactionIdWidth.W))
  val publicationTransactionBase = Output(UInt(p.transactionIdWidth.W))
  val recovery = Flipped(new RecoveryTargetIO(p))
}

/** Canonical D3/S1 dispatch-reservation owner.
  *
  * ROB and BROB remain the only residency owners. This module retains only the
  * already-published canonical payload while classed dispatch drains an exact
  * continuous suffix. The common caller-controlled publication pulse is the
  * sole point at which a new row becomes visible here.
  */
class OooD3ReservationAllocator(val p: CoreParams) extends Module {
  val io = IO(new OooD3ReservationAllocatorIO(p))

  private val countWidth = PrefixPacketContract.countWidth(p.ooo.d3PrefixWidth)
  val heldValid = RegInit(false.B)
  val held = Reg(new D3RenameGroup(p))
  val cursor = RegInit(0.U(countWidth.W))
  val nextTransaction = RegInit(0.U(p.transactionIdWidth.W))
  val heldTransactionBase = RegInit(0.U(p.transactionIdWidth.W))

  val recoveryPending = RegInit(false.B)
  val preparedValid = RegInit(false.B)
  val recoveryPlan = RegInit(0.U.asTypeOf(new RecoveryPlan(p)))
  val sameApply = recoveryPending && io.recovery.apply.valid &&
    io.recovery.apply.bits.phase === RecoveryPhase.Apply &&
    RecoveryPlanContract.sameTransactionIgnoringPhase(
      io.recovery.apply.bits, recoveryPlan)
  val sameAbort = recoveryPending && io.recovery.abort.valid &&
    io.recovery.abort.bits.phase === RecoveryPhase.Abort &&
    RecoveryPlanContract.sameTransactionIgnoringPhase(
      io.recovery.abort.bits, recoveryPlan)

  io.recovery.prepare.ready := !recoveryPending
  io.recovery.prepared.valid := preparedValid
  io.recovery.prepared.bits := recoveryPlan
  when(io.recovery.prepare.fire) {
    recoveryPending := true.B
    preparedValid := true.B
    recoveryPlan := io.recovery.prepare.bits
  }.elsewhen(sameApply || sameAbort) {
    recoveryPending := false.B
    preparedValid := false.B
  }.elsewhen(io.recovery.prepared.fire) {
    preparedValid := false.B
  }

  val inputCountExact = io.in.bits.count =/= 0.U &&
    io.in.bits.count <= p.ooo.d3PrefixWidth.U &&
    io.robPrepared.count === io.in.bits.count &&
    io.brobPrepared.count === io.in.bits.count
  val preparedExact = (0 until p.ooo.d3PrefixWidth).map { lane =>
    val active = lane.U < io.in.bits.count
    val raw = io.in.bits.entries(lane).uop.decoded.rob
    val bound = io.robPrepared.entries(lane).rob
    val brob = io.brobPrepared.entries(lane)
    !active || (io.robPrepared.entries(lane).valid && brob.valid &&
      raw.peId === bound.peId && raw.stid === bound.stid &&
      raw.ridSlot === bound.ridSlot &&
      raw.ridGeneration === bound.ridGeneration &&
      raw.memberIndex === bound.memberIndex &&
      brob.stid === bound.stid &&
      bound.bid === brob.bid &&
      bound.brobGeneration === brob.brobGeneration)
  }.reduce(_ && _)
  val inputStid = io.in.bits.entries(0).uop.decoded.rob.stid
  val recoveryBlocksInput = recoveryPending &&
    recoveryPlan.trigger.stid === inputStid
  io.in.ready := !heldValid && !recoveryBlocksInput &&
    inputCountExact && preparedExact

  val published = io.in.fire
  when(published) {
    held := io.in.bits
    for (lane <- 0 until p.ooo.d3PrefixWidth) {
      when(lane.U < io.in.bits.count) {
        held.entries(lane).uop.decoded.rob := io.robPrepared.entries(lane).rob
      }
    }
    heldValid := true.B
    cursor := 0.U
    heldTransactionBase := nextTransaction
    nextTransaction := nextTransaction + io.in.bits.count
  }

  val remaining = held.count - cursor
  when(heldValid && io.advance =/= 0.U) {
    assert(io.advance <= remaining)
    when(io.advance === remaining) {
      heldValid := false.B
      cursor := 0.U
    }.otherwise {
      cursor := cursor + io.advance
    }
  }
  when(sameApply && heldValid &&
    held.entries(0).uop.decoded.rob.stid === recoveryPlan.trigger.stid) {
    heldValid := false.B
    cursor := 0.U
  }

  io.pendingValid := heldValid
  io.pending := held
  io.cursor := cursor
  io.transactionBase := heldTransactionBase
  io.publicationTransactionBase := nextTransaction
}
