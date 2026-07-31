package linxcore.ooo

import chisel3._
import chisel3.util._
import linxcore.params.CoreParams
import linxcore.top.interface._

object RecoveryControlState extends ChiselEnum {
  val Idle, Prepare, Apply = Value
}

class RecoveryControlRobPrepare(val p: CoreParams) extends Bundle {
  val ready = Input(Bool())
  val bits = Input(new RecoveryPlan(p))
}

class RecoveryControlIO(val p: CoreParams, val targetCount: Int) extends Bundle {
  val events = Flipped(Vec(2, Valid(new RecoveryEvent(p))))
  val interrupts = Input(Vec(p.ooo.stidCount, new InterruptRequest(p)))
  val abort = Input(Bool())
  val robPrepare = new RecoveryControlRobPrepare(p)
  val targets = Vec(targetCount, new RecoveryTargetIO(p))
}

class RecoveryControl(val p: CoreParams, val targetCount: Int) extends Module {
  require(targetCount > 0)
  val io = IO(new RecoveryControlIO(p, targetCount))

  val state = RegInit(RecoveryControlState.Idle)
  val plan = RegInit(0.U.asTypeOf(new RecoveryPlan(p)))
  val preparedMask = RegInit(0.U(targetCount.W))
  val applyPulse = RegInit(false.B)
  val abortPulse = RegInit(false.B)

  val selectedEvent = io.events(0)
  val seedPlan = Wire(new RecoveryPlan(p))
  seedPlan := 0.U.asTypeOf(seedPlan)
  seedPlan.transactionId := selectedEvent.bits.transactionId
  seedPlan.phase := RecoveryPhase.Prepare
  seedPlan.cause := selectedEvent.bits.cause
  seedPlan.trigger := selectedEvent.bits.trigger
  seedPlan.redirectPc := selectedEvent.bits.redirectPc
  seedPlan.newEpoch := selectedEvent.bits.instruction.epoch + 1.U

  when(state === RecoveryControlState.Idle && selectedEvent.valid &&
    io.abort) {
    plan := seedPlan
    plan.phase := RecoveryPhase.Abort
    abortPulse := true.B
  }.elsewhen(state === RecoveryControlState.Idle && selectedEvent.valid &&
    io.robPrepare.ready) {
    plan := io.robPrepare.bits
    plan.phase := RecoveryPhase.Prepare
    state := RecoveryControlState.Prepare
    preparedMask := 0.U
  }

  val preparedHits = Wire(Vec(targetCount, Bool()))
  for (target <- 0 until targetCount) {
    io.targets(target).prepare.valid := state === RecoveryControlState.Prepare
    io.targets(target).prepare.bits := plan
    io.targets(target).prepared.ready := true.B
    io.targets(target).apply.valid := applyPulse
    io.targets(target).apply.bits := plan
    io.targets(target).apply.bits.phase := RecoveryPhase.Apply
    io.targets(target).abort.valid := abortPulse
    io.targets(target).abort.bits := plan
    io.targets(target).abort.bits.phase := RecoveryPhase.Abort
    preparedHits(target) := state === RecoveryControlState.Prepare &&
      io.targets(target).prepared.valid &&
      RecoveryPlanContract.sameTransactionIgnoringPhase(
        io.targets(target).prepared.bits, plan)
  }
  val preparedNext = preparedMask | preparedHits.asUInt
  val allPrepared = preparedNext.andR
  when(state === RecoveryControlState.Prepare) {
    preparedMask := preparedNext
  }
  when(state === RecoveryControlState.Prepare && allPrepared) {
    applyPulse := true.B
    state := RecoveryControlState.Apply
  }.elsewhen(state === RecoveryControlState.Apply) {
    applyPulse := false.B
    state := RecoveryControlState.Idle
  }.otherwise {
    applyPulse := false.B
  }
  when(abortPulse) {
    abortPulse := false.B
  }
}
