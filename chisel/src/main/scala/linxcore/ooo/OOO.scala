package linxcore.ooo

import chisel3._
import chisel3.util.{DecoupledIO, PriorityEncoder, Queue}
import linxcore.params.CoreParams
import linxcore.top.interface._

/** Canonical D1/D2 stage retained inside the public OOO owner graph. */
private[ooo] class OOOD1D2Stage(val p: CoreParams) extends Module {
  val io = IO(new OOOD1D2IO(p))
  private val width = p.widths.decodeWidth
  private val stidWidth = p.ooo.stidWidth
  private val ridSlotWidth = p.ooo.ridSlotWidth
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
    UInt(p.ooo.robMemberIndexWidth.W)))
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
    admission.groups(group).ridSlot := Mux(wraps,
      (slotSum - p.ooo.robGroupsPerStid.U)(ridSlotWidth - 1, 0),
      slotSum(ridSlotWidth - 1, 0))
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
    admission.entries(lane).uop.rob.ridSlot := Mux(wraps,
      (slotSum - p.ooo.robGroupsPerStid.U)(ridSlotWidth - 1, 0),
      slotSum(ridSlotWidth - 1, 0))
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
  val d3s1 = Module(new OOOD3S1Graph(p))

  d1d2.io.fromCtu <> io.fromCtu
  d1d2.io.ridTailSlot := d3s1.io.ridAdmissionTailSlot
  d1d2.io.ridTailGeneration := d3s1.io.ridAdmissionTailGeneration
  d3s1.io.fromD2 <> d1d2.io.d2
  d1d2.io.recovery <> d3s1.io.recoveryToD1
  for (lane <- io.iex.aluDispatch.indices) {
    io.iex.aluDispatch(lane) <> d3s1.io.iex.aluDispatch(lane)
  }
  for (lane <- io.iex.bruDispatch.indices) {
    io.iex.bruDispatch(lane) <> d3s1.io.iex.bruDispatch(lane)
  }
  for (lane <- io.iex.aguDispatch.indices) {
    io.iex.aguDispatch(lane) <> d3s1.io.iex.aguDispatch(lane)
  }
  for (lane <- io.iex.storeDispatch.indices) {
    io.iex.storeDispatch(lane) <> d3s1.io.iex.storeDispatch(lane)
  }
  for (lane <- io.iex.systemDispatch.indices) {
    io.iex.systemDispatch(lane) <> d3s1.io.iex.systemDispatch(lane)
  }
  for (lane <- io.iex.cmdDispatch.indices) {
    io.iex.cmdDispatch(lane) <> d3s1.io.iex.cmdDispatch(lane)
  }
  io.iex.allocationClear := d3s1.io.iex.allocationClear
  io.iex.fastResult <> d3s1.io.iex.fastResult
  d3s1.io.iex.pcBufferReadAddress := io.iex.pcBufferReadAddress
  io.iex.pcBufferReadPcBase := d3s1.io.iex.pcBufferReadPcBase
  d3s1.io.iex.robNoflushReady <> io.iex.robNoflushReady
  io.iex.robNoflush <> d3s1.io.iex.robNoflush
  for (lane <- io.iex.robResolve.indices) {
    d3s1.io.iex.robResolve(lane) <> io.iex.robResolve(lane)
  }
  d3s1.io.storeResolve <> io.storeResolve
  for (lane <- io.iex.storeBinding.indices) {
    d3s1.io.iex.storeBinding(lane).valid := io.iex.storeBinding(lane).valid
    d3s1.io.iex.storeBinding(lane).bits := io.iex.storeBinding(lane).bits
    io.iex.storeBinding(lane).ready := d3s1.io.iex.storeBinding(lane).ready
  }
  for (lane <- io.iex.systemIssue.indices) {
    d3s1.io.iex.systemIssue(lane) <> io.iex.systemIssue(lane)
  }
  d3s1.io.iex.recoveryEvent <> io.iex.recoveryEvent
  d3s1.io.iex.recovery <> io.iex.recovery
  io.commit <> d3s1.io.commit
  io.storeCommit <> d3s1.io.storeCommit
  io.trap <> d3s1.io.trap
  d3s1.io.interrupt <> io.interrupt
  d3s1.io.debugRequest <> io.debugRequest
  io.debugResponse <> d3s1.io.debugResponse
  io.recoveryToIfu <> d3s1.io.recoveryToIfu
  io.recoveryToCtu <> d3s1.io.recoveryToCtu
  io.recoveryToLsu <> d3s1.io.recoveryToLsu
  for (lane <- io.systemIssue.indices) {
    io.systemIssue(lane) <> d3s1.io.systemIssue(lane)
  }
  io.trace <> d3s1.io.trace
}
