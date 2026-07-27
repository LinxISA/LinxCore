package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, PopCount, PriorityEncoder}

class OooD2GroupPlannerIO(val p: OooParams = OooParams()) extends Bundle {
  val in = Flipped(Decoupled(new OooD1DecodedPacket(p)))

  /** Snapshot-only allocator state. D2 never mutates these tails. */
  val tailSlot = Input(Vec(p.stidCount, UInt(p.ridSlotWidth.W)))
  val tailGeneration = Input(Vec(p.stidCount, UInt(p.ridGenerationWidth.W)))
  val tailEpoch = Input(Vec(p.stidCount, UInt(p.reservationEpochWidth.W)))
  val nextTransactionId = Input(Vec(p.stidCount, UInt(p.transactionIdWidth.W)))

  val out = Decoupled(new OooD2GroupedTransaction(p))
}

/** Combinational D2 virtual RID/group planner.
  *
  * Grouping is older-first. A group is closed before a new block start and
  * after a block stop, precise trap, or predicted-taken PC-release boundary.
  * Architectural-parent and physical-member caps are enforced independently.
  */
class OooD2GroupPlanner(val p: OooParams = OooParams()) extends Module {
  val io = IO(new OooD2GroupPlannerIO(p))

  val stid = io.in.bits.stid
  val inputCount = PopCount(io.in.bits.uopMask)
  val stateGroup = Wire(Vec(p.decodedUopWidth + 1, UInt(p.robGroupCountWidth.W)))
  val stateParents = Wire(Vec(p.decodedUopWidth + 1, UInt(p.robGroupParentDemandWidth.W)))
  val stateMembers = Wire(Vec(p.decodedUopWidth + 1, UInt(p.robMemberCountWidth.W)))
  val stateClosed = Wire(Vec(p.decodedUopWidth + 1, Bool()))

  stateGroup(0) := 0.U
  stateParents(0) := 0.U
  stateMembers(0) := 0.U
  stateClosed(0) := false.B

  val assignedGroup = Wire(Vec(p.decodedUopWidth, UInt(p.robGroupIndexWidth.W)))
  val assignedMemberBase = Wire(Vec(p.decodedUopWidth, UInt(p.robMemberIndexWidth.W)))
  val releasePcBase = Wire(Vec(p.decodedUopWidth, Bool()))

  for (uopIndex <- 0 until p.decodedUopWidth) {
    val active = io.in.bits.uopMask(uopIndex)
    val uop = io.in.bits.uops(uopIndex)
    val parentCount = PopCount(uop.identity.parents.zipWithIndex.map {
      case (parent, parentIndex) =>
        parentIndex.U < uop.identity.parentCount && parent.key.valid && parent.traceOwner
    })
    val memberCount = uop.plannedChildCount
    val hasCurrentGroup = stateMembers(uopIndex).orR
    val parentOverflow =
      stateParents(uopIndex) +& parentCount > p.maxInstPerRobGroup.U
    val memberOverflow =
      stateMembers(uopIndex) +& memberCount > p.maxOrdinaryUopsPerGroup.U
    val newGroup =
      active && hasCurrentGroup && (
        stateClosed(uopIndex) || uop.identity.boundary.start ||
          parentOverflow || memberOverflow)

    assignedGroup(uopIndex) :=
      (stateGroup(uopIndex) + newGroup.asUInt)(p.robGroupIndexWidth - 1, 0)
    assignedMemberBase(uopIndex) :=
      Mux(newGroup, 0.U, stateMembers(uopIndex))(p.robMemberIndexWidth - 1, 0)

    releasePcBase(uopIndex) := uop.identity.parents.zipWithIndex.map {
      case (parent, parentIndex) =>
        parentIndex.U < uop.identity.parentCount && parent.key.valid &&
          parent.traceOwner && parent.prediction.valid && parent.prediction.taken
    }.reduce(_ || _)
    val closesGroup =
      uop.identity.boundary.stop || uop.preciseTrap || releasePcBase(uopIndex)

    stateGroup(uopIndex + 1) := Mux(
      active,
      stateGroup(uopIndex) + newGroup.asUInt,
      stateGroup(uopIndex))
    stateParents(uopIndex + 1) := Mux(
      active,
      Mux(newGroup, parentCount, stateParents(uopIndex) + parentCount),
      stateParents(uopIndex))
    stateMembers(uopIndex + 1) := Mux(
      active,
      Mux(newGroup, memberCount, stateMembers(uopIndex) + memberCount),
      stateMembers(uopIndex))
    stateClosed(uopIndex + 1) := Mux(active, closesGroup, stateClosed(uopIndex))
  }

  val groupCount = Mux(inputCount.orR, stateGroup(p.decodedUopWidth) + 1.U, 0.U)
  val transaction = Wire(new OooD2GroupedTransaction(p))
  transaction := 0.U.asTypeOf(transaction)
  transaction.decoded := io.in.bits
  transaction.plan.transactionId := io.nextTransactionId(stid)
  transaction.plan.peId := io.in.bits.peId
  transaction.plan.stid := stid
  transaction.plan.epoch := io.in.bits.epoch
  transaction.plan.instructionMask := io.in.bits.acceptedInstructionMask
  transaction.plan.uopMask := io.in.bits.uopMask
  transaction.plan.groupCount := groupCount
  transaction.plan.virtualTailEpoch := io.tailEpoch(stid)
  transaction.plan.demand := io.in.bits.demand
  transaction.plan.demand.robGroups := groupCount
  transaction.plan.firstVirtualGroup.valid := groupCount.orR
  transaction.plan.firstVirtualGroup.peId := io.in.bits.peId
  transaction.plan.firstVirtualGroup.stid := stid
  transaction.plan.firstVirtualGroup.ridSlot := io.tailSlot(stid)
  transaction.plan.firstVirtualGroup.ridGeneration := io.tailGeneration(stid)
  transaction.groupMask :=
    ((1.U((p.instructionDecodeWidth + 1).W) << groupCount) - 1.U)(
      p.instructionDecodeWidth - 1, 0)
  transaction.uopGroupIndex := assignedGroup
  transaction.uopMemberBase := assignedMemberBase

  for (groupIndex <- 0 until p.instructionDecodeWidth) {
    val group = transaction.groups(groupIndex)
    val slotSum = io.tailSlot(stid) +& groupIndex.U
    val wraps = slotSum >= p.robGroupsPerStid.U
    group.valid := groupIndex.U < groupCount
    group.key.valid := group.valid
    group.key.peId := io.in.bits.peId
    group.key.stid := stid
    group.key.ridSlot := slotSum(p.ridSlotWidth - 1, 0)
    group.key.ridGeneration := io.tailGeneration(stid) + wraps.asUInt
    group.logicalUopMask := VecInit((0 until p.decodedUopWidth).map { uopIndex =>
      io.in.bits.uopMask(uopIndex) && assignedGroup(uopIndex) === groupIndex.U
    }).asUInt
    group.logicalUopCount := PopCount(group.logicalUopMask)
    group.firstLogicalUop := Mux(
      group.logicalUopMask.orR,
      PriorityEncoder(group.logicalUopMask),
      0.U)
    group.physicalMemberCount := (0 until p.decodedUopWidth).map { uopIndex =>
      Mux(group.logicalUopMask(uopIndex), io.in.bits.uops(uopIndex).plannedChildCount, 0.U)
    }.reduce(_ +& _)
    group.architecturalParentCount := (0 until p.decodedUopWidth).map { uopIndex =>
      val uop = io.in.bits.uops(uopIndex)
      val traceParents = PopCount(uop.identity.parents.zipWithIndex.map {
        case (parent, parentIndex) =>
          parentIndex.U < uop.identity.parentCount && parent.key.valid && parent.traceOwner
      })
      Mux(group.logicalUopMask(uopIndex), traceParents, 0.U)
    }.reduce(_ +& _)
    group.boundaryStart := (0 until p.decodedUopWidth).map { uopIndex =>
      group.logicalUopMask(uopIndex) && io.in.bits.uops(uopIndex).identity.boundary.start
    }.reduce(_ || _)
    group.boundaryStop := (0 until p.decodedUopWidth).map { uopIndex =>
      group.logicalUopMask(uopIndex) && io.in.bits.uops(uopIndex).identity.boundary.stop
    }.reduce(_ || _)
    group.releasePcBase := (0 until p.decodedUopWidth).map { uopIndex =>
      group.logicalUopMask(uopIndex) && releasePcBase(uopIndex)
    }.reduce(_ || _)
    group.preciseTrap := (0 until p.decodedUopWidth).map { uopIndex =>
      group.logicalUopMask(uopIndex) && io.in.bits.uops(uopIndex).preciseTrap
    }.reduce(_ || _)
  }

  io.out.valid := io.in.valid && inputCount.orR &&
    !io.in.bits.ctuParentMask.orR && !io.in.bits.complexParentMask.orR
  io.out.bits := transaction
  io.in.ready := Mux(
    inputCount.orR && !io.in.bits.ctuParentMask.orR && !io.in.bits.complexParentMask.orR,
    io.out.ready,
    false.B)

  when(io.in.valid) {
    assert(stid < p.stidCount.U)
    assert(!io.in.bits.ctuParentMask.orR && !io.in.bits.complexParentMask.orR,
      "D2 planner accepts only canonical rows after CTU/complex diversion")
    assert(groupCount <= p.instructionDecodeWidth.U)
    for (uopIndex <- 0 until p.decodedUopWidth) {
      when(io.in.bits.uopMask(uopIndex)) {
        assert(io.in.bits.uops(uopIndex).plannedChildCount.orR)
        assert(io.in.bits.uops(uopIndex).plannedChildCount <= p.maxOrdinaryUopsPerGroup.U,
          "one logical uop may not exceed one ordinary ROB group")
      }
    }
    for (groupIndex <- 0 until p.instructionDecodeWidth) {
      when(transaction.groups(groupIndex).valid) {
        assert(transaction.groups(groupIndex).architecturalParentCount <= p.maxInstPerRobGroup.U)
        assert(transaction.groups(groupIndex).physicalMemberCount <= p.maxOrdinaryUopsPerGroup.U)
      }
    }
  }
}
