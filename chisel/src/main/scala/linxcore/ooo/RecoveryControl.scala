package linxcore.ooo

import chisel3._
import chisel3.util._
import linxcore.params.CoreParams
import linxcore.top.interface._

object RecoveryControlState extends ChiselEnum {
  val Idle, RequestRob, WaitRob, PrepareTargets, Apply = Value
}

class RecoveryControlIO(val p: CoreParams, val targetCount: Int) extends Bundle {
  val events = Flipped(Vec(2, Decoupled(new RecoveryEvent(p))))
  val interrupts = Input(Vec(p.ooo.stidCount, new InterruptRequest(p)))
  val interruptBoundaryValid = Input(Bool())
  val interruptBoundary = Input(new RobIdentity(p))
  val abort = Input(Bool())
  val robCandidate = Output(Vec(2, Valid(new RecoveryCandidateLookup(p))))
  val robCandidateStatus = Input(Vec(2, Valid(new RecoveryCandidateStatus(p))))
  val robPrepare = Decoupled(new RecoveryPlan(p))
  val robPrepared = Flipped(Decoupled(new RecoveryPlan(p)))
  val targets = Vec(targetCount, new RecoveryTargetIO(p))
}

class RecoveryControl(val p: CoreParams, val targetCount: Int) extends Module {
  require(targetCount > 0)
  val io = IO(new RecoveryControlIO(p, targetCount))

  private def preciseTrap(event: RecoveryEvent): Bool =
    event.cause === RecoveryCause.Exception && event.trap.valid

  val state = RegInit(RecoveryControlState.Idle)
  val pendingValid = RegInit(VecInit(Seq.fill(2)(false.B)))
  val pending = Reg(Vec(2, new RecoveryEvent(p)))
  val activeEvent = RegInit(0.U.asTypeOf(new RecoveryEvent(p)))
  val plan = RegInit(0.U.asTypeOf(new RecoveryPlan(p)))
  val sentMask = RegInit(0.U(targetCount.W))
  val ackMask = RegInit(0.U(targetCount.W))
  val applyPulse = RegInit(false.B)
  val abortPulse = RegInit(false.B)

  val interruptEvent = Wire(new RecoveryEvent(p))
  interruptEvent := 0.U.asTypeOf(interruptEvent)
  val anyInterrupt = io.interrupts.map(_.valid).reduce(_ || _)
  val bestInterrupt = io.interrupts.reduce { (a, b) =>
    Mux(a.valid && (!b.valid || a.priority >= b.priority), a, b)
  }
  interruptEvent.transactionId := bestInterrupt.cause
  interruptEvent.cause := RecoveryCause.Interrupt
  interruptEvent.trigger := io.interruptBoundary
  interruptEvent.trap.valid := anyInterrupt && io.interruptBoundaryValid
  interruptEvent.trap.kind := TrapKind.Interrupt
  interruptEvent.trap.cause := bestInterrupt.cause
  interruptEvent.trap.rob := io.interruptBoundary

  for (idx <- 0 until 2) {
    io.events(idx).ready := !pendingValid(idx) &&
      state === RecoveryControlState.Idle
    when(io.events(idx).fire) {
      pendingValid(idx) := true.B
      pending(idx) := io.events(idx).bits
    }
  }

  val candidateValid = Wire(Vec(3, Bool()))
  val candidate = Wire(Vec(3, new RecoveryEvent(p)))
  candidateValid(0) := pendingValid(0) || io.events(0).valid
  candidate(0) := Mux(pendingValid(0), pending(0), io.events(0).bits)
  candidateValid(1) := pendingValid(1) || io.events(1).valid
  candidate(1) := Mux(pendingValid(1), pending(1), io.events(1).bits)
  candidateValid(2) := anyInterrupt && io.interruptBoundaryValid
  candidate(2) := interruptEvent

  for (idx <- 0 until 2) {
    io.robCandidate(idx).valid := candidateValid(idx)
    io.robCandidate(idx).bits.event := candidate(idx)
  }

  val statusMatches = Wire(Vec(2, Bool()))
  val statusEligible = Wire(Vec(2, Bool()))
  for (idx <- 0 until 2) {
    statusMatches(idx) := io.robCandidateStatus(idx).valid &&
      io.robCandidateStatus(idx).bits.transactionId ===
        candidate(idx).transactionId &&
      io.robCandidateStatus(idx).bits.trigger.asUInt ===
        candidate(idx).trigger.asUInt
    statusEligible(idx) := candidateValid(idx) && statusMatches(idx) &&
      io.robCandidateStatus(idx).bits.eligible
  }
  val source1Older = io.robCandidateStatus(1).bits.ageToken <
    io.robCandidateStatus(0).bits.ageToken
  val selectedEvent = Wire(new RecoveryEvent(p))
  val selectedSource = Wire(UInt(2.W))
  selectedSource := 0.U
  selectedEvent := candidate(0)
  when(statusEligible(1) && (!statusEligible(0) || source1Older)) {
    selectedSource := 1.U
    selectedEvent := candidate(1)
  }
  when(candidateValid(2) &&
    (!statusEligible(0) || !preciseTrap(candidate(0)) ||
      !io.robCandidateStatus(0).bits.headTrap) &&
    (!statusEligible(1) || !preciseTrap(candidate(1)) ||
      !io.robCandidateStatus(1).bits.headTrap)) {
    selectedSource := 2.U
    selectedEvent := candidate(2)
  }
  when(statusEligible(0) && preciseTrap(candidate(0)) &&
    io.robCandidateStatus(0).bits.headTrap) {
    selectedSource := 0.U
    selectedEvent := candidate(0)
  }.elsewhen(statusEligible(1) && preciseTrap(candidate(1)) &&
    io.robCandidateStatus(1).bits.headTrap) {
    selectedSource := 1.U
    selectedEvent := candidate(1)
  }
  val anyCandidate = statusEligible.asUInt.orR || candidateValid(2)

  val seedPlan = Wire(new RecoveryPlan(p))
  seedPlan := 0.U.asTypeOf(seedPlan)
  seedPlan.transactionId := activeEvent.transactionId
  seedPlan.phase := RecoveryPhase.Prepare
  seedPlan.cause := activeEvent.cause
  seedPlan.trigger := activeEvent.trigger
  seedPlan.redirectPc := activeEvent.redirectPc
  seedPlan.newEpoch := activeEvent.instruction.epoch + 1.U

  io.robPrepare.valid := state === RecoveryControlState.RequestRob
  io.robPrepare.bits := seedPlan
  io.robPrepared.ready := state === RecoveryControlState.RequestRob ||
    state === RecoveryControlState.WaitRob

  when(state === RecoveryControlState.Idle && anyCandidate) {
    activeEvent := selectedEvent
    state := RecoveryControlState.RequestRob
    when(selectedSource === 0.U) {
      pendingValid(0) := false.B
    }.elsewhen(selectedSource === 1.U) {
      pendingValid(1) := false.B
    }
  }

  when(state === RecoveryControlState.Idle) {
    for (idx <- 0 until 2) {
      when(candidateValid(idx) && statusMatches(idx) &&
        io.robCandidateStatus(idx).bits.rejected) {
        pendingValid(idx) := false.B
      }
    }
  }

  when(state === RecoveryControlState.RequestRob && io.robPrepare.fire &&
    !io.robPrepared.fire) {
    state := RecoveryControlState.WaitRob
  }

  when((state === RecoveryControlState.RequestRob ||
    state === RecoveryControlState.WaitRob) && io.robPrepared.fire) {
    plan := io.robPrepared.bits
    plan.phase := RecoveryPhase.Prepare
    sentMask := 0.U
    ackMask := 0.U
    state := RecoveryControlState.PrepareTargets
  }

  val sentHits = Wire(Vec(targetCount, Bool()))
  val ackHits = Wire(Vec(targetCount, Bool()))
  for (target <- 0 until targetCount) {
    val sent = sentMask(target)
    io.targets(target).prepare.valid :=
      state === RecoveryControlState.PrepareTargets && !sent
    io.targets(target).prepare.bits := plan
    sentHits(target) := io.targets(target).prepare.fire
    io.targets(target).prepared.ready :=
      state === RecoveryControlState.PrepareTargets
    ackHits(target) := state === RecoveryControlState.PrepareTargets &&
      io.targets(target).prepared.valid &&
      RecoveryPlanContract.sameTransactionIgnoringPhase(
        io.targets(target).prepared.bits, plan)
    io.targets(target).apply.valid := applyPulse
    io.targets(target).apply.bits := plan
    io.targets(target).apply.bits.phase := RecoveryPhase.Apply
    io.targets(target).abort.valid := abortPulse
    io.targets(target).abort.bits := plan
    io.targets(target).abort.bits.phase := RecoveryPhase.Abort
  }

  val nextSent = sentMask | sentHits.asUInt
  val nextAck = ackMask | ackHits.asUInt
  when(state === RecoveryControlState.PrepareTargets) {
    sentMask := nextSent
    ackMask := nextAck
  }
  when(state === RecoveryControlState.PrepareTargets && nextSent.andR &&
    nextAck.andR) {
    applyPulse := true.B
    state := RecoveryControlState.Apply
  }.elsewhen(state === RecoveryControlState.Apply) {
    applyPulse := false.B
    state := RecoveryControlState.Idle
  }.otherwise {
    applyPulse := false.B
  }

  when(io.abort && state =/= RecoveryControlState.Idle) {
    abortPulse := true.B
    state := RecoveryControlState.Idle
  }.elsewhen(abortPulse) {
    abortPulse := false.B
  }
}
