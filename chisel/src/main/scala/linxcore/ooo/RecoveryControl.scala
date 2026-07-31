package linxcore.ooo

import chisel3._
import chisel3.util._
import linxcore.params.CoreParams
import linxcore.top.interface._

object RecoveryControlState extends ChiselEnum {
  val Idle, ResolveCandidates, RequestRob, WaitRob, WaitRobAbort,
    PrepareTargets, Apply = Value
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
  val robAbort = Valid(new RecoveryPlan(p))
  val targets = Vec(targetCount, new RecoveryTargetIO(p))
}

class RecoveryControl(val p: CoreParams, val targetCount: Int) extends Module {
  require(targetCount > 0)
  RecoveryAge.requireUnambiguousWindow(p)
  val io = IO(new RecoveryControlIO(p, targetCount))

  private def preciseTrap(event: RecoveryEvent): Bool =
    event.cause === RecoveryCause.Exception && event.trap.valid

  val state = RegInit(RecoveryControlState.Idle)
  val pendingValid = RegInit(VecInit(Seq.fill(2)(false.B)))
  val pending = Reg(Vec(2, new RecoveryEvent(p)))
  val resolvingValid = RegInit(VecInit(Seq.fill(2)(false.B)))
  val resolving = Reg(Vec(2, new RecoveryEvent(p)))
  val resolvedValid = RegInit(VecInit(Seq.fill(2)(false.B)))
  val resolvedStatus = Reg(Vec(2, new RecoveryCandidateStatus(p)))
  val resolvingInterruptValid = RegInit(false.B)
  val resolvingInterrupt = Reg(new RecoveryEvent(p))
  val activeEvent = RegInit(0.U.asTypeOf(new RecoveryEvent(p)))
  val robRequest = RegInit(0.U.asTypeOf(new RecoveryPlan(p)))
  val plan = RegInit(0.U.asTypeOf(new RecoveryPlan(p)))
  val sentMask = RegInit(0.U(targetCount.W))
  val ackMask = RegInit(0.U(targetCount.W))
  val applyPulse = RegInit(false.B)
  val abortPulse = RegInit(false.B)
  val robAbortPulse = RegInit(false.B)

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

  val lookupValid = Wire(Vec(2, Bool()))
  val lookupEvent = Wire(Vec(2, new RecoveryEvent(p)))
  for (idx <- 0 until 2) {
    lookupValid(idx) := Mux(state === RecoveryControlState.ResolveCandidates,
      resolvingValid(idx),
      candidateValid(idx))
    lookupEvent(idx) := Mux(state === RecoveryControlState.ResolveCandidates,
      resolving(idx),
      candidate(idx))
    io.robCandidate(idx).valid := lookupValid(idx)
    io.robCandidate(idx).bits.event := lookupEvent(idx)
  }

  val statusMatchesLookup = Wire(Vec(2, Bool()))
  for (idx <- 0 until 2) {
    statusMatchesLookup(idx) := io.robCandidateStatus(idx).valid &&
      io.robCandidateStatus(idx).bits.transactionId ===
        lookupEvent(idx).transactionId &&
      io.robCandidateStatus(idx).bits.trigger.asUInt ===
        lookupEvent(idx).trigger.asUInt
  }
  val effectiveResolvedValid = Wire(Vec(2, Bool()))
  val effectiveStatus = Wire(Vec(2, new RecoveryCandidateStatus(p)))
  val resolvedEligible = Wire(Vec(2, Bool()))
  for (idx <- 0 until 2) {
    val sameCycleStatus = state === RecoveryControlState.ResolveCandidates &&
      resolvingValid(idx) && statusMatchesLookup(idx)
    effectiveResolvedValid(idx) := resolvedValid(idx) || sameCycleStatus
    effectiveStatus(idx) := Mux(resolvedValid(idx),
      resolvedStatus(idx),
      io.robCandidateStatus(idx).bits)
    resolvedEligible(idx) := resolvingValid(idx) &&
      effectiveResolvedValid(idx) && effectiveStatus(idx).eligible
  }
  val source1Older = RecoveryAge.older(
    effectiveStatus(1).ageToken,
    effectiveStatus(0).ageToken)
  val idleStatusEligible = Wire(Vec(2, Bool()))
  for (idx <- 0 until 2) {
    idleStatusEligible(idx) := candidateValid(idx) && statusMatchesLookup(idx) &&
      io.robCandidateStatus(idx).bits.eligible
  }
  val idleSource1Older = RecoveryAge.older(
    io.robCandidateStatus(1).bits.ageToken,
    io.robCandidateStatus(0).bits.ageToken)
  val idleSelectedEvent = Wire(new RecoveryEvent(p))
  val idleSelectedSource = Wire(UInt(2.W))
  idleSelectedSource := 0.U
  idleSelectedEvent := candidate(0)
  when(idleStatusEligible(1) && (!idleStatusEligible(0) || idleSource1Older)) {
    idleSelectedSource := 1.U
    idleSelectedEvent := candidate(1)
  }
  when(candidateValid(2) &&
    (!idleStatusEligible(0) || !preciseTrap(candidate(0)) ||
      !io.robCandidateStatus(0).bits.headTrap) &&
    (!idleStatusEligible(1) || !preciseTrap(candidate(1)) ||
      !io.robCandidateStatus(1).bits.headTrap)) {
    idleSelectedSource := 2.U
    idleSelectedEvent := candidate(2)
  }
  when(idleStatusEligible(0) && preciseTrap(candidate(0)) &&
    io.robCandidateStatus(0).bits.headTrap) {
    idleSelectedSource := 0.U
    idleSelectedEvent := candidate(0)
  }.elsewhen(idleStatusEligible(1) && preciseTrap(candidate(1)) &&
    io.robCandidateStatus(1).bits.headTrap) {
    idleSelectedSource := 1.U
    idleSelectedEvent := candidate(1)
  }
  val idleAllResolved = (0 until 2).map { idx =>
    !candidateValid(idx) || statusMatchesLookup(idx)
  }.reduce(_ && _)
  val idleSelectedValid = idleStatusEligible.asUInt.orR || candidateValid(2)
  val selectedEvent = Wire(new RecoveryEvent(p))
  val selectedSource = Wire(UInt(2.W))
  selectedSource := 0.U
  selectedEvent := resolving(0)
  when(resolvedEligible(1) && (!resolvedEligible(0) || source1Older)) {
    selectedSource := 1.U
    selectedEvent := resolving(1)
  }
  when(resolvingInterruptValid &&
    (!resolvedEligible(0) || !preciseTrap(resolving(0)) ||
      !effectiveStatus(0).headTrap) &&
    (!resolvedEligible(1) || !preciseTrap(resolving(1)) ||
      !effectiveStatus(1).headTrap)) {
    selectedSource := 2.U
    selectedEvent := resolvingInterrupt
  }
  when(resolvedEligible(0) && preciseTrap(resolving(0)) &&
    effectiveStatus(0).headTrap) {
    selectedSource := 0.U
    selectedEvent := resolving(0)
  }.elsewhen(resolvedEligible(1) && preciseTrap(resolving(1)) &&
    effectiveStatus(1).headTrap) {
    selectedSource := 1.U
    selectedEvent := resolving(1)
  }
  val producerActive = resolvingValid.asUInt.orR
  val allResolved = (0 until 2).map { idx =>
    !resolvingValid(idx) || effectiveResolvedValid(idx)
  }.reduce(_ && _)
  val selectedValid = resolvedEligible.asUInt.orR || resolvingInterruptValid

  val seedPlan = Wire(new RecoveryPlan(p))
  seedPlan := 0.U.asTypeOf(seedPlan)
  seedPlan.transactionId := activeEvent.transactionId
  seedPlan.phase := RecoveryPhase.Prepare
  seedPlan.cause := activeEvent.cause
  seedPlan.trigger := activeEvent.trigger
  seedPlan.redirectPc := activeEvent.redirectPc
  seedPlan.newEpoch := activeEvent.instruction.epoch + 1.U

  io.robPrepare.valid := state === RecoveryControlState.RequestRob && !io.abort
  io.robPrepare.bits := seedPlan
  val requestMatchesSeed =
    RecoveryPlanContract.sameRobRequest(io.robPrepared.bits, seedPlan)
  val requestMatchesRetained =
    RecoveryPlanContract.sameRobRequest(io.robPrepared.bits, robRequest)
  val robPrepareFire = io.robPrepare.fire
  val robPreparedCanFire =
    (state === RecoveryControlState.RequestRob && !io.abort &&
      robPrepareFire && requestMatchesSeed) ||
      ((state === RecoveryControlState.WaitRob ||
        state === RecoveryControlState.WaitRobAbort) &&
        requestMatchesRetained)
  io.robPrepared.ready := robPreparedCanFire
  io.robAbort.valid := robAbortPulse
  io.robAbort.bits := plan
  io.robAbort.bits.phase := RecoveryPhase.Abort

  when(state === RecoveryControlState.Idle &&
    (candidateValid(0) || candidateValid(1) || candidateValid(2))) {
    when((!(candidateValid(0) || candidateValid(1)) ||
      (idleAllResolved && idleSelectedValid))) {
      activeEvent := Mux(candidateValid(0) || candidateValid(1),
        idleSelectedEvent,
        candidate(2))
      state := RecoveryControlState.RequestRob
      when(idleSelectedSource === 0.U && (candidateValid(0) || candidateValid(1))) {
        pendingValid(0) := false.B
      }.elsewhen(idleSelectedSource === 1.U &&
        (candidateValid(0) || candidateValid(1))) {
        pendingValid(1) := false.B
      }
      for (idx <- 0 until 2) {
        when(candidateValid(idx) && statusMatchesLookup(idx) &&
          io.robCandidateStatus(idx).bits.rejected) {
          pendingValid(idx) := false.B
        }
        resolvingValid(idx) := false.B
        resolvedValid(idx) := false.B
      }
      resolvingInterruptValid := false.B
    }.otherwise {
      for (idx <- 0 until 2) {
        resolvingValid(idx) := candidateValid(idx)
        resolving(idx) := candidate(idx)
        resolvedValid(idx) := candidateValid(idx) && statusMatchesLookup(idx)
        resolvedStatus(idx) := io.robCandidateStatus(idx).bits
      }
      resolvingInterruptValid := candidateValid(2)
      resolvingInterrupt := candidate(2)
      state := RecoveryControlState.ResolveCandidates
    }
  }

  when(state === RecoveryControlState.ResolveCandidates) {
    for (idx <- 0 until 2) {
      when(resolvingValid(idx) && !resolvedValid(idx) &&
        statusMatchesLookup(idx)) {
        resolvedValid(idx) := true.B
        resolvedStatus(idx) := io.robCandidateStatus(idx).bits
      }
    }
    when(allResolved) {
      when(selectedValid) {
        activeEvent := selectedEvent
        state := RecoveryControlState.RequestRob
        when(selectedSource === 0.U) {
          pendingValid(0) := false.B
        }.elsewhen(selectedSource === 1.U) {
          pendingValid(1) := false.B
        }
      }.otherwise {
        state := RecoveryControlState.Idle
      }
      for (idx <- 0 until 2) {
        when(resolvingValid(idx) && effectiveStatus(idx).rejected) {
          pendingValid(idx) := false.B
        }
        resolvingValid(idx) := false.B
        resolvedValid(idx) := false.B
      }
      resolvingInterruptValid := false.B
    }
  }

  when(robPrepareFire) {
    robRequest := seedPlan
  }

  when(state === RecoveryControlState.RequestRob && io.abort) {
    state := RecoveryControlState.Idle
  }.elsewhen(state === RecoveryControlState.RequestRob &&
    robPrepareFire && !io.robPrepared.fire) {
    state := RecoveryControlState.WaitRob
  }

  when(state === RecoveryControlState.WaitRob && io.abort &&
    !io.robPrepared.fire) {
    state := RecoveryControlState.WaitRobAbort
  }

  val robPreparedFire = (state === RecoveryControlState.RequestRob ||
    state === RecoveryControlState.WaitRob ||
    state === RecoveryControlState.WaitRobAbort) &&
    io.robPrepared.valid && robPreparedCanFire
  when(robPreparedFire) {
    plan := io.robPrepared.bits
    plan.phase := RecoveryPhase.Prepare
    sentMask := 0.U
    ackMask := 0.U
    when(state === RecoveryControlState.WaitRobAbort ||
      (state === RecoveryControlState.WaitRob && io.abort)) {
      robAbortPulse := true.B
      state := RecoveryControlState.Idle
    }.otherwise {
      state := RecoveryControlState.PrepareTargets
    }
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
  when(state === RecoveryControlState.PrepareTargets && io.abort) {
    abortPulse := true.B
    robAbortPulse := true.B
    state := RecoveryControlState.Idle
  }.elsewhen(state === RecoveryControlState.PrepareTargets &&
    nextSent.andR && nextAck.andR) {
    applyPulse := true.B
    state := RecoveryControlState.Apply
  }.elsewhen(state === RecoveryControlState.Apply) {
    applyPulse := false.B
    state := RecoveryControlState.Idle
  }.otherwise {
    applyPulse := false.B
  }

  when(state === RecoveryControlState.ResolveCandidates && io.abort) {
    for (idx <- 0 until 2) {
      resolvingValid(idx) := false.B
      resolvedValid(idx) := false.B
    }
    resolvingInterruptValid := false.B
    state := RecoveryControlState.Idle
  }

  when(abortPulse) {
    abortPulse := false.B
  }
  when(robAbortPulse) {
    robAbortPulse := false.B
  }
  assert(!(applyPulse && abortPulse))
}
