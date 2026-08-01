package linxcore.ooo

import chisel3._
import chisel3.util.PriorityEncoder
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
  val d3s1 = Module(new OOOD3S1Graph(p))

  d1d2.io.fromCtu <> io.fromCtu
  d1d2.io.ridTailSlot := d3s1.io.ridTailSlot
  d1d2.io.ridTailGeneration := d3s1.io.ridTailGeneration
  d3s1.io.fromD2 <> d1d2.io.d2
  d1d2.io.recovery <> d3s1.io.recoveryToD1
  io.iex <> d3s1.io.iex
  io.commit <> d3s1.io.commit
  io.trap <> d3s1.io.trap
  d3s1.io.interrupt <> io.interrupt
  io.recoveryToIfu <> d3s1.io.recoveryToIfu
  io.recoveryToCtu <> d3s1.io.recoveryToCtu
  io.recoveryToLsu <> d3s1.io.recoveryToLsu
  io.trace <> d3s1.io.trace
}
