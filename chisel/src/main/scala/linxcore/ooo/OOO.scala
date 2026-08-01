package linxcore.ooo

import chisel3._
import chisel3.util.{Arbiter, PriorityEncoder}
import linxcore.params.CoreParams
import linxcore.top.interface._

/** Canonical D1/D2 stage retained inside the public OOO owner graph. */
private[ooo] class OOOD1D2Stage(val p: CoreParams) extends Module {
  val io = IO(new OOOD1D2IO(p))
  private val width = p.widths.decodeWidth
  private val stidWidth = math.max(1, chisel3.util.log2Ceil(p.ooo.stidCount))
  private val ridSlotWidth =
    math.max(1, chisel3.util.log2Ceil(p.ooo.robGroupsPerStid))
  private def select[T <: Data](values: Vec[T], index: UInt): T =
    if (p.ooo.stidCount == 1) values(0) else values(index)

  val dec = Module(new DEC(p))
  dec.io.in <> io.fromCtu

  val valid = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(false.B)))
  val rows = Reg(Vec(p.ooo.stidCount, new D2AdmissionGroup(p)))

  val recoveryPending = RegInit(false.B)
  val preparedValid = RegInit(false.B)
  val recoveryPlan = RegInit(0.U.asTypeOf(new RecoveryPlan(p)))
  val recoveryStid = recoveryPlan.trigger.stid
  val prepareStid = io.recovery.prepare.bits.trigger.stid

  private def terminalMatches(candidate: RecoveryPlan): Bool =
    candidate.transactionId === recoveryPlan.transactionId &&
      candidate.cause === recoveryPlan.cause &&
      candidate.trigger.asUInt === recoveryPlan.trigger.asUInt &&
      candidate.survivingTailValid === recoveryPlan.survivingTailValid &&
      candidate.survivingTail.asUInt === recoveryPlan.survivingTail.asUInt &&
      candidate.redirectPc === recoveryPlan.redirectPc &&
      candidate.newEpoch === recoveryPlan.newEpoch

  val preparedCompletes = !preparedValid || io.recovery.prepared.fire
  val applyHit = recoveryPending && io.recovery.apply.valid &&
    preparedCompletes && terminalMatches(io.recovery.apply.bits) &&
    io.recovery.apply.bits.phase === RecoveryPhase.Apply
  val abortHit = recoveryPending && io.recovery.abort.valid &&
    preparedCompletes && terminalMatches(io.recovery.abort.bits) &&
    io.recovery.abort.bits.phase === RecoveryPhase.Abort
  val prepareFire = io.recovery.prepare.fire

  io.recovery.prepare.ready := !recoveryPending &&
    io.recovery.prepare.bits.trigger.stid < p.ooo.stidCount.U
  io.recovery.prepared.valid := preparedValid
  io.recovery.prepared.bits := recoveryPlan
  when(prepareFire) {
    recoveryPending := true.B
    preparedValid := true.B
    recoveryPlan := io.recovery.prepare.bits
  }.elsewhen(applyHit || abortHit) {
    recoveryPending := false.B
    preparedValid := false.B
  }.elsewhen(io.recovery.prepared.fire) {
    preparedValid := false.B
  }

  val fence = Wire(Vec(p.ooo.stidCount, Bool()))
  val cancel = Wire(Vec(p.ooo.stidCount, Bool()))
  for (stid <- 0 until p.ooo.stidCount) {
    fence(stid) := (recoveryPending && recoveryStid === stid.U) ||
      (prepareFire && prepareStid === stid.U)
    cancel(stid) := applyHit && recoveryStid === stid.U
  }

  val inputStid = dec.io.out.bits.entries(0).uop.instruction.parent.identity.stid
  val inputInRange = inputStid < p.ooo.stidCount.U
  val inputOccupied = Mux(inputInRange, select(valid, inputStid), true.B)
  val inputFenced = Mux(inputInRange, select(fence, inputStid), true.B)

  val admission = Wire(new D2AdmissionGroup(p))
  admission := 0.U.asTypeOf(admission)
  admission.count := dec.io.out.bits.count
  val stateGroup = Wire(Vec(width + 1,
    UInt(PrefixPacketContract.countWidth(width).W)))
  val stateMembers = Wire(Vec(width + 1,
    UInt(PrefixPacketContract.countWidth(
      p.ooo.maxInstructionsPerRobGroup).W)))
  val stateClosed = Wire(Vec(width + 1, Bool()))
  val assignedGroup = Wire(Vec(width,
    UInt(PrefixPacketContract.countWidth(width).W)))
  val assignedMember = Wire(Vec(width,
    UInt(math.max(1, chisel3.util.log2Ceil(
      p.ooo.maxInstructionsPerRobGroup)).W)))
  stateGroup(0) := 0.U
  stateMembers(0) := 0.U
  stateClosed(0) := false.B
  for (lane <- 0 until width) {
    val laneActive = lane.U < dec.io.out.bits.count
    val needNewGroup = laneActive && stateMembers(lane).orR &&
      (stateClosed(lane) ||
        dec.io.out.bits.entries(lane).uop.blockBoundary ||
        stateMembers(lane) === p.ooo.maxInstructionsPerRobGroup.U)
    assignedGroup(lane) := stateGroup(lane) + needNewGroup.asUInt
    assignedMember(lane) := Mux(needNewGroup, 0.U, stateMembers(lane))
    stateGroup(lane + 1) := Mux(
      laneActive, assignedGroup(lane), stateGroup(lane))
    stateMembers(lane + 1) := Mux(
      laneActive,
      Mux(needNewGroup, 1.U, stateMembers(lane) + 1.U),
      stateMembers(lane))
    stateClosed(lane + 1) := Mux(
      laneActive,
      dec.io.out.bits.entries(lane).uop.blockBoundary,
      stateClosed(lane))
  }
  val groupCount = Mux(
    dec.io.out.bits.count.orR,
    stateGroup(width) + 1.U,
    0.U)
  admission.groupCount := groupCount
  for (group <- 0 until width) {
    val activeGroup = group.U < groupCount
    val slotSum = select(io.ridTailSlot, inputStid) +& group.U
    val wraps = slotSum >= p.ooo.robGroupsPerStid.U
    admission.groups(group).valid := activeGroup
    admission.groups(group).peId :=
      dec.io.out.bits.entries(0).uop.instruction.parent.identity.peId
    admission.groups(group).stid := inputStid
    admission.groups(group).ridSlot := slotSum(ridSlotWidth - 1, 0)
    admission.groups(group).ridGeneration :=
      select(io.ridTailGeneration, inputStid) + wraps.asUInt
  }
  for (lane <- 0 until width) {
    val slotSum = select(io.ridTailSlot, inputStid) +& assignedGroup(lane)
    val wraps = slotSum >= p.ooo.robGroupsPerStid.U
    admission.entries(lane).uop := dec.io.out.bits.entries(lane).uop
    admission.entries(lane).trap := dec.io.out.bits.entries(lane).trap
    admission.entries(lane).uop.rob.peId :=
      dec.io.out.bits.entries(lane).uop.instruction.parent.identity.peId
    admission.entries(lane).uop.rob.stid :=
      dec.io.out.bits.entries(lane).uop.instruction.parent.identity.stid
    admission.entries(lane).uop.rob.ridSlot := slotSum(ridSlotWidth - 1, 0)
    admission.entries(lane).uop.rob.ridGeneration :=
      select(io.ridTailGeneration, inputStid) + wraps.asUInt
    admission.entries(lane).uop.rob.memberIndex := assignedMember(lane)
    admission.entries(lane).uop.rob.residentGeneration := 0.U
    admission.entries(lane).uop.rob.bid := 0.U
    admission.entries(lane).uop.rob.brobGeneration := 0.U
    admission.entries(lane).residentBound := false.B
    admission.entries(lane).brobBound := false.B
  }

  dec.io.out.ready := inputInRange && !inputOccupied && !inputFenced
  when(dec.io.out.fire) {
    for (stid <- 0 until p.ooo.stidCount) {
      when(inputStid === stid.U) {
        rows(stid) := admission
        valid(stid) := true.B
      }
    }
  }

  val heldGrantValid = RegInit(false.B)
  val heldGrantStid = RegInit(0.U(stidWidth.W))
  val eligible = Wire(Vec(p.ooo.stidCount, Bool()))
  for (stid <- 0 until p.ooo.stidCount) {
    eligible(stid) := valid(stid) && !fence(stid) && !cancel(stid)
  }
  val selectedNew = if (p.ooo.stidCount == 1) 0.U(stidWidth.W)
    else PriorityEncoder(eligible.asUInt)
  val heldBlocked = heldGrantValid &&
    (select(fence, heldGrantStid) || select(cancel, heldGrantStid) ||
      !select(valid, heldGrantStid))
  val useHeld = heldGrantValid && !heldBlocked
  val selected = Mux(useHeld, heldGrantStid, selectedNew)
  io.d2.valid := Mux(useHeld, select(valid, selected), eligible.asUInt.orR)
  io.d2.bits := select(rows, selected)

  when(io.d2.valid && !io.d2.ready && !useHeld) {
    heldGrantValid := true.B
    heldGrantStid := selected
  }
  when(heldBlocked) {
    heldGrantValid := false.B
  }
  when(io.d2.fire) {
    for (stid <- 0 until p.ooo.stidCount) {
      when(selected === stid.U) {
        valid(stid) := false.B
      }
    }
    heldGrantValid := false.B
  }
  for (stid <- 0 until p.ooo.stidCount) {
    when(cancel(stid)) {
      valid(stid) := false.B
    }
  }

  when(dec.io.out.fire) {
    assert(dec.io.out.bits.count.orR)
    assert(dec.io.out.bits.count <= width.U)
  }
}

/** Public OOO box with one canonical D1-through-S1 owner graph. */
class OOO(val p: CoreParams) extends Module {
  val io = IO(new OOOIO(p))

  val d1d2 = Module(new OOOD1D2Stage(p))
  val renu = Module(new RENU(p))
  val rob = Module(new ROB(p))
  val brob = Module(new BROB(p))
  val dispatch = Module(new Dispatch(p))
  val commitControl = Module(new CommitControl(p))
  private val recoveryTargetCount = 8
  val recovery = Module(new RecoveryControl(p, recoveryTargetCount))

  d1d2.io.fromCtu <> io.fromCtu
  d1d2.io.ridTailSlot := rob.io.ridTailSlot
  d1d2.io.ridTailGeneration := rob.io.ridTailGeneration
  renu.io.fromD2 <> d1d2.io.d2

  rob.io.prepare.valid := renu.io.toD3.valid
  rob.io.prepare.bits := renu.io.toD3.bits
  brob.io.prepare.valid := renu.io.toD3.valid
  brob.io.prepare.bits := renu.io.toD3.bits
  dispatch.io.in.valid := renu.io.toD3.valid
  dispatch.io.in.bits := renu.io.toD3.bits
  rob.io.brobPrepared := brob.io.prepared
  brob.io.robPrepared := rob.io.prepared
  dispatch.io.robPrepared := rob.io.prepared
  dispatch.io.brobPrepared := brob.io.prepared
  val d3Ready = rob.io.prepare.ready && brob.io.prepare.ready &&
    dispatch.io.in.ready
  val d3Fire = renu.io.toD3.valid && d3Ready
  renu.io.toD3.ready := d3Ready
  rob.io.publishFire := d3Fire
  brob.io.publishFire := d3Fire
  dispatch.io.publishFire := d3Fire

  for (pipe <- 0 until p.iex.aluPipes) {
    io.iex.aluDispatch(pipe) <> dispatch.io.iex.aluDispatch(pipe)
  }
  for (pipe <- 0 until p.iex.bruPipes) {
    io.iex.bruDispatch(pipe) <> dispatch.io.iex.bruDispatch(pipe)
  }
  for (pipe <- 0 until p.iex.aguPipes) {
    io.iex.aguDispatch(pipe) <> dispatch.io.iex.aguDispatch(pipe)
  }
  for (pipe <- 0 until p.iex.stdPipes) {
    io.iex.stdDispatch(pipe) <> dispatch.io.iex.stdDispatch(pipe)
  }
  for (queue <- 0 until p.iex.systemMulticycleQueues) {
    io.iex.systemDispatch(queue) <> dispatch.io.iex.systemDispatch(queue)
  }
  for (queue <- 0 until p.iex.cmdIssueQueues) {
    io.iex.cmdDispatch(queue) <> dispatch.io.iex.cmdDispatch(queue)
  }

  val completionArb = Module(new Arbiter(new CompletionTxn(p), p.widths.issueWidth))
  for (lane <- 0 until p.widths.issueWidth) {
    completionArb.io.in(lane) <> io.iex.completion(lane)
  }
  rob.io.completion <> completionArb.io.out

  rob.io.commit.ready := true.B
  commitControl.io.rob.valid := rob.io.commit.valid
  commitControl.io.rob.bits := rob.io.commit.bits
  val interrupts = Wire(Vec(p.ooo.stidCount, new InterruptRequest(p)))
  for (stid <- 0 until p.ooo.stidCount) {
    interrupts(stid) := io.interrupt.bits
    interrupts(stid).valid := io.interrupt.valid &&
      io.interrupt.bits.valid && io.interrupt.bits.stid === stid.U
  }
  commitControl.io.interrupts := interrupts
  commitControl.io.interruptBoundaryValid := rob.io.commit.bits.headValid
  commitControl.io.interruptBoundary := rob.io.commit.bits.head

  val releaseProbe = rob.io.commit.valid && rob.io.commit.bits.count =/= 0.U
  rob.io.release.valid := releaseProbe
  rob.io.release.bits := commitControl.io.out.bits.robRelease
  renu.io.release.valid := releaseProbe
  renu.io.release.bits := commitControl.io.out.bits.rename
  brob.io.release.valid := releaseProbe
  brob.io.release.bits := commitControl.io.out.bits.brobRelease
  commitControl.io.robReleaseReady := rob.io.releaseReady
  commitControl.io.renameReleaseReady := renu.io.releaseReady
  commitControl.io.brobReleaseReady := brob.io.releaseReady

  io.commit.valid := commitControl.io.out.valid &&
    commitControl.io.out.bits.commit.count =/= 0.U
  io.commit.bits := commitControl.io.out.bits.commit
  io.trap.valid := commitControl.io.out.valid &&
    commitControl.io.out.bits.trap.valid
  io.trap.bits := commitControl.io.out.bits.trap
  commitControl.io.out.ready :=
    (!io.commit.valid || io.commit.ready) && (!io.trap.valid || io.trap.ready)
  val commitFire = commitControl.io.out.fire
  rob.io.commitApply := commitFire
  rob.io.releaseApply := commitFire
  renu.io.releaseApply := commitFire
  brob.io.releaseApply := commitFire

  recovery.io.events(0) <> io.iex.recoveryEvent
  recovery.io.events(1).valid := false.B
  recovery.io.events(1).bits := 0.U.asTypeOf(recovery.io.events(1).bits)
  recovery.io.interrupts := interrupts
  recovery.io.interruptBoundaryValid := rob.io.commit.bits.headValid
  recovery.io.interruptBoundary := rob.io.commit.bits.head
  recovery.io.abort := false.B
  rob.io.recoveryCandidate := recovery.io.robCandidate
  recovery.io.robCandidateStatus := rob.io.recoveryCandidateStatus
  rob.io.recoveryPrepare.valid := recovery.io.robPrepare.valid
  rob.io.recoveryPrepare.bits := recovery.io.robPrepare.bits
  recovery.io.robPrepare.ready := rob.io.recoveryPrepare.ready
  recovery.io.robPrepared.valid := rob.io.recoveryPrepared.valid
  recovery.io.robPrepared.bits := rob.io.recoveryPrepared.bits
  rob.io.recoveryAbort := recovery.io.robAbort
  rob.io.recoveryApply.valid := recovery.io.targets(0).apply.valid
  rob.io.recoveryApply.bits := recovery.io.targets(0).apply.bits

  private def connectTarget(
      controller: RecoveryTargetIO,
      target: RecoveryTargetIO): Unit = {
    target.prepare.valid := controller.prepare.valid
    target.prepare.bits := controller.prepare.bits
    controller.prepare.ready := target.prepare.ready
    controller.prepared.valid := target.prepared.valid
    controller.prepared.bits := target.prepared.bits
    target.prepared.ready := controller.prepared.ready
    target.apply.valid := controller.apply.valid
    target.apply.bits := controller.apply.bits
    target.abort.valid := controller.abort.valid
    target.abort.bits := controller.abort.bits
  }

  connectTarget(recovery.io.targets(0), d1d2.io.recovery)
  connectTarget(recovery.io.targets(1), renu.io.recovery)
  brob.io.recoveryPrepare.valid := recovery.io.targets(2).prepare.valid
  brob.io.recoveryPrepare.bits := recovery.io.targets(2).prepare.bits
  recovery.io.targets(2).prepare.ready := brob.io.recoveryPrepare.ready
  recovery.io.targets(2).prepared.valid := brob.io.recoveryPrepared.valid
  recovery.io.targets(2).prepared.bits := brob.io.recoveryPrepared.bits
  brob.io.recoveryApply := recovery.io.targets(2).apply
  brob.io.recoveryAbort := recovery.io.targets(2).abort
  connectTarget(recovery.io.targets(3), dispatch.io.recovery)
  connectTarget(recovery.io.targets(4), io.iex.recovery)
  connectTarget(recovery.io.targets(5), io.recoveryToIfu)
  connectTarget(recovery.io.targets(6), io.recoveryToCtu)
  connectTarget(recovery.io.targets(7), io.recoveryToLsu)

  io.trace.valid := false.B
  io.trace.bits := 0.U.asTypeOf(io.trace.bits)
}
