package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, PopCount, PriorityEncoder, Valid}
import linxcore.common.DestinationKind

object OooCtuLeaseState extends ChiselEnum {
  val Idle, WaitPlan, EmitChildren = Value
}

object OooCtuPlanRejectReason extends ChiselEnum {
  val StaleLease, MalformedCount = Value
}

object OooCtuChildRejectReason extends ChiselEnum {
  val StaleLease, WrongOrdinal, MalformedChild = Value
}

object OooCtuRecoveryRejectReason extends ChiselEnum {
  val MalformedRequest = Value
}

class OooCtuLeaseKey(val p: OooParams = OooParams()) extends Bundle {
  val valid = Bool()
  val peId = UInt(p.peIdWidth.W)
  val stid = UInt(p.stidWidth.W)
  val parent = new CanonicalParentKey(p)
  val templateGroupId = UInt(p.templateGroupIdWidth.W)
  val generation = UInt(p.residentGenerationWidth.W)
}

class OooCtuParentClaim(val p: OooParams = OooParams()) extends Bundle {
  val lease = new OooCtuLeaseKey(p)
  val parent = new OooRawInstruction(p)
}

class OooCtuExpansionPlan(val p: OooParams = OooParams()) extends Bundle {
  val lease = new OooCtuLeaseKey(p)
  val childCount = UInt(p.recipeUopCountWidth.W)
}

class OooCtuCanonicalChild(val p: OooParams = OooParams()) extends Bundle {
  val lease = new OooCtuLeaseKey(p)
  val ordinal = UInt(p.recipeUopCountWidth.W)
  val childCount = UInt(p.recipeUopCountWidth.W)
  val finalChild = Bool()
  val uop = new OooDecodedUop(p)
}

class OooCtuPlanReject(val p: OooParams = OooParams()) extends Bundle {
  val plan = new OooCtuExpansionPlan(p)
  val reason = OooCtuPlanRejectReason()
}

class OooCtuChildReject(val p: OooParams = OooParams()) extends Bundle {
  val child = new OooCtuCanonicalChild(p)
  val reason = OooCtuChildRejectReason()
}

class OooCtuRecoveryPrepared(val p: OooParams = OooParams()) extends Bundle {
  val request = new OooGlobalRecoveryRequest(p)
  val packetPresent = Bool()
  val claimPending = Bool()
  val expansionActive = Bool()
  val lease = new OooCtuLeaseKey(p)
}

class OooCtuRecoveryReject(val p: OooParams = OooParams()) extends Bundle {
  val request = new OooGlobalRecoveryRequest(p)
  val reason = OooCtuRecoveryRejectReason()
}

class OooCtuIngressBridgeIO(val p: OooParams = OooParams()) extends Bundle {
  val in = Flipped(Decoupled(new OooD1DecodedPacket(p)))
  val out = Decoupled(new OooD1DecodedPacket(p))

  val parentClaim = Decoupled(new OooCtuParentClaim(p))
  val expansionPlan = Flipped(Decoupled(new OooCtuExpansionPlan(p)))
  val child = Flipped(Decoupled(new OooCtuCanonicalChild(p)))
  val planRejected = Valid(new OooCtuPlanReject(p))
  val childRejected = Valid(new OooCtuChildReject(p))

  /** Fence is non-mutating; cancel is the destructive stage event. */
  val fence = Input(Vec(p.stidCount, Bool()))
  val cancel = Input(Vec(p.stidCount, Bool()))

  val recoveryPrepare = Flipped(Decoupled(new OooGlobalRecoveryRequest(p)))
  val recoveryPrepared = Valid(new OooCtuRecoveryPrepared(p))
  val recoveryRejected = Valid(new OooCtuRecoveryReject(p))
  val recoveryApply = Input(Bool())
  val recoveryAbort = Input(Bool())

  val occupied = Output(Vec(p.stidCount, Bool()))
  val active = Output(Vec(p.stidCount, Bool()))
  val blockedByComplex = Output(Vec(p.stidCount, Bool()))
}

/** Production OOO-side CTU ingress and lease ledger.
  *
  * D1 packets are retained per STID. Ordinary uops before the next diverted
  * parent leave as a dense packet; a CTU parent is claimed exactly once and
  * blocks only its STID until the external CTU supplies an exact plan and
  * ordered canonical children. Each accepted child re-enters the ordinary D2
  * path as one logical uop, so long expansions naturally span multiple RID
  * groups. Only the final child owns the architectural parent trace.
  */
class OooCtuIngressBridge(val p: OooParams = OooParams()) extends Module {
  val io = IO(new OooCtuIngressBridgeIO(p))

  private def instructionOlder(left: UInt, right: UInt): Bool = {
    val distance = right - left
    distance.orR && !distance(p.instructionIdWidth - 1)
  }

  val packetValid = RegInit(VecInit(Seq.fill(p.stidCount)(false.B)))
  val packets = RegInit(VecInit(Seq.fill(p.stidCount)(
    0.U.asTypeOf(new OooD1DecodedPacket(p)))))
  val remainingUops = RegInit(VecInit(Seq.fill(p.stidCount)(0.U(p.decodedUopWidth.W))))
  val remainingCtu = RegInit(VecInit(Seq.fill(p.stidCount)(0.U(p.instructionDecodeWidth.W))))
  val remainingComplex = RegInit(VecInit(Seq.fill(p.stidCount)(0.U(p.instructionDecodeWidth.W))))

  val leaseState = RegInit(VecInit(Seq.fill(p.stidCount)(OooCtuLeaseState.Idle)))
  val lease = RegInit(VecInit(Seq.fill(p.stidCount)(
    0.U.asTypeOf(new OooCtuLeaseKey(p)))))
  val leaseParent = RegInit(VecInit(Seq.fill(p.stidCount)(
    0.U.asTypeOf(new OooRawInstruction(p)))))
  val leaseChildCount = RegInit(VecInit(Seq.fill(p.stidCount)(
    0.U(p.recipeUopCountWidth.W))))
  val expectedOrdinal = RegInit(VecInit(Seq.fill(p.stidCount)(
    0.U(p.recipeUopCountWidth.W))))
  val nextTemplateGroupId = RegInit(VecInit(Seq.fill(p.stidCount)(0.U(p.templateGroupIdWidth.W))))
  val nextGeneration = RegInit(VecInit(Seq.fill(p.stidCount)(0.U(p.residentGenerationWidth.W))))

  val recoveryPending = RegInit(false.B)
  val recoveryRequest = RegInit(0.U.asTypeOf(new OooGlobalRecoveryRequest(p)))
  val recoverySnapshot = RegInit(0.U.asTypeOf(new OooCtuRecoveryPrepared(p)))
  val recoveryStid = recoveryRequest.rename.key.member.group.stid
  val offeredRecoveryStid = io.recoveryPrepare.bits.rename.key.member.group.stid
  val offeredRecoveryWellFormed =
    io.recoveryPrepare.bits.rename.key.member.group.valid &&
      io.recoveryPrepare.bits.rename.key.member.bid.valid &&
      offeredRecoveryStid < p.stidCount.U

  io.recoveryPrepare.ready := !recoveryPending
  io.recoveryPrepared.valid := recoveryPending
  io.recoveryPrepared.bits := recoverySnapshot
  io.recoveryRejected.valid := io.recoveryPrepare.fire && !offeredRecoveryWellFormed
  io.recoveryRejected.bits.request := io.recoveryPrepare.bits
  io.recoveryRejected.bits.reason := OooCtuRecoveryRejectReason.MalformedRequest

  when(io.recoveryPrepare.fire && offeredRecoveryWellFormed) {
    val stid = offeredRecoveryStid
    recoveryPending := true.B
    recoveryRequest := io.recoveryPrepare.bits
    recoverySnapshot.request := io.recoveryPrepare.bits
    recoverySnapshot.packetPresent := packetValid(stid)
    recoverySnapshot.claimPending := leaseState(stid) === OooCtuLeaseState.WaitPlan
    recoverySnapshot.expansionActive := leaseState(stid) === OooCtuLeaseState.EmitChildren
    recoverySnapshot.lease := lease(stid)
  }

  val effectiveFence = Wire(Vec(p.stidCount, Bool()))
  for (stid <- 0 until p.stidCount) {
    effectiveFence(stid) := io.fence(stid) ||
      (recoveryPending && recoveryStid === stid.U)
  }

  val inputStid = io.in.bits.stid
  val inputStidInRange = inputStid < p.stidCount.U
  val inputBusy = Mux(inputStidInRange, packetValid(inputStid), true.B)
  val inputLeaseBusy = Mux(
    inputStidInRange,
    leaseState(inputStid) =/= OooCtuLeaseState.Idle,
    true.B)
  val inputFenced = Mux(inputStidInRange, effectiveFence(inputStid), true.B)
  val inputCancelled = Mux(inputStidInRange, io.cancel(inputStid), true.B)
  io.in.ready := inputStidInRange && !inputBusy && !inputLeaseBusy &&
    !inputFenced && !inputCancelled

  when(io.in.fire) {
    packetValid(inputStid) := true.B
    packets(inputStid) := io.in.bits
    remainingUops(inputStid) := io.in.bits.uopMask
    remainingCtu(inputStid) := io.in.bits.ctuParentMask
    remainingComplex(inputStid) := io.in.bits.complexParentMask
  }

  val normalMaskByStid = Wire(Vec(p.stidCount, UInt(p.decodedUopWidth.W)))
  val nextOwnerIsCtu = Wire(Vec(p.stidCount, Bool()))
  val actionCandidate = Wire(Vec(p.stidCount, Bool()))
  val complexBlocked = Wire(Vec(p.stidCount, Bool()))
  for (stid <- 0 until p.stidCount) {
    val packet = packets(stid)
    val ctuMask = remainingCtu(stid)
    val complexMask = remainingComplex(stid)
    val hasCtu = ctuMask.orR
    val hasComplex = complexMask.orR
    val ctuLane = PriorityEncoder(ctuMask)
    val complexLane = PriorityEncoder(complexMask)
    val ctuId = packet.ctuParents(ctuLane).parent.key.instructionId
    val complexId = packet.complexParents(complexLane).parent.key.instructionId
    val boundaryValid = hasCtu || hasComplex
    val ctuFirst = hasCtu && (!hasComplex || instructionOlder(ctuId, complexId))
    val boundaryId = Mux(ctuFirst, ctuId, complexId)
    val normalBits = Wire(Vec(p.decodedUopWidth, Bool()))
    for (uopIndex <- 0 until p.decodedUopWidth) {
      normalBits(uopIndex) := remainingUops(stid)(uopIndex) &&
        (!boundaryValid ||
          instructionOlder(
            packet.uops(uopIndex).identity.key.primaryParent.instructionId,
            boundaryId))
    }
    normalMaskByStid(stid) := normalBits.asUInt
    nextOwnerIsCtu(stid) := ctuFirst && !normalBits.asUInt.orR
    complexBlocked(stid) := packetValid(stid) && hasComplex && !ctuFirst &&
      !normalBits.asUInt.orR
    actionCandidate(stid) := packetValid(stid) &&
      leaseState(stid) === OooCtuLeaseState.Idle && !effectiveFence(stid) &&
      !io.cancel(stid) && (normalBits.asUInt.orR || nextOwnerIsCtu(stid))
  }

  val rrStart = RegInit(0.U(p.stidWidth.W))
  val rotated = Wire(Vec(p.stidCount, Bool()))
  for (offset <- 0 until p.stidCount) {
    val index = if (p.stidCount == 1) 0.U else (rrStart + offset.U)(p.stidWidth - 1, 0)
    rotated(offset) := actionCandidate(index)
  }
  val actionValid = rotated.asUInt.orR
  val actionOffset = if (p.stidCount == 1) 0.U else PriorityEncoder(rotated.asUInt)
  val actionStid =
    if (p.stidCount == 1) 0.U(p.stidWidth.W)
    else (rrStart + actionOffset)(p.stidWidth - 1, 0)
  val actionNormalMask = normalMaskByStid(actionStid)
  val actionNormal = actionValid && actionNormalMask.orR
  val actionClaim = actionValid && !actionNormal && nextOwnerIsCtu(actionStid)

  val claimLane = PriorityEncoder(remainingCtu(actionStid))
  val offeredLease = Wire(new OooCtuLeaseKey(p))
  offeredLease := 0.U.asTypeOf(offeredLease)
  offeredLease.valid := true.B
  offeredLease.peId := packets(actionStid).peId
  offeredLease.stid := actionStid
  offeredLease.parent := packets(actionStid).ctuParents(claimLane).parent.key
  offeredLease.templateGroupId := nextTemplateGroupId(actionStid)
  offeredLease.generation := nextGeneration(actionStid)

  io.parentClaim.valid := actionClaim
  io.parentClaim.bits.lease := offeredLease
  io.parentClaim.bits.parent := packets(actionStid).ctuParents(claimLane)

  val planStidInRange = io.expansionPlan.bits.lease.stid < p.stidCount.U
  val planStid = Mux(planStidInRange, io.expansionPlan.bits.lease.stid, 0.U)
  val planLeaseExact = planStidInRange &&
    leaseState(planStid) === OooCtuLeaseState.WaitPlan &&
    io.expansionPlan.bits.lease.asUInt === lease(planStid).asUInt
  val planCountWellFormed = io.expansionPlan.bits.childCount.orR &&
    io.expansionPlan.bits.childCount <= p.maxRecipeUops.U
  val planBlocked = planLeaseExact && effectiveFence(planStid)
  io.expansionPlan.ready := !planBlocked
  io.planRejected.valid := io.expansionPlan.fire &&
    (!planLeaseExact || !planCountWellFormed)
  io.planRejected.bits.plan := io.expansionPlan.bits
  io.planRejected.bits.reason := Mux(
    !planLeaseExact,
    OooCtuPlanRejectReason.StaleLease,
    OooCtuPlanRejectReason.MalformedCount)

  val childStidInRange = io.child.bits.lease.stid < p.stidCount.U
  val childStid = Mux(childStidInRange, io.child.bits.lease.stid, 0.U)
  val childLeaseExact = childStidInRange &&
    leaseState(childStid) === OooCtuLeaseState.EmitChildren &&
    io.child.bits.lease.asUInt === lease(childStid).asUInt
  val childOrdinalExact = childLeaseExact &&
    io.child.bits.ordinal === expectedOrdinal(childStid) &&
    io.child.bits.childCount === leaseChildCount(childStid)
  val childFinalExpected = childOrdinalExact &&
    io.child.bits.finalChild ===
      (expectedOrdinal(childStid) === leaseChildCount(childStid) - 1.U)
  val childShapeExact = childFinalExpected && io.child.bits.uop.valid &&
    io.child.bits.uop.recipe.valid &&
    io.child.bits.uop.recipe.disposition =/= OooOpcodeDisposition.Ctu.U &&
    io.child.bits.uop.recipe.disposition =/= OooOpcodeDisposition.Illegal.U &&
    io.child.bits.uop.plannedChildCount.orR &&
    io.child.bits.uop.plannedChildCount <= p.maxOrdinaryUopsPerGroup.U
  val childBlocked = childShapeExact && effectiveFence(childStid)
  val childExact = childShapeExact && !childBlocked

  io.childRejected.valid := io.child.valid && !childShapeExact
  io.childRejected.bits.child := io.child.bits
  io.childRejected.bits.reason := Mux(
    !childLeaseExact,
    OooCtuChildRejectReason.StaleLease,
    Mux(
      !childOrdinalExact,
      OooCtuChildRejectReason.WrongOrdinal,
      OooCtuChildRejectReason.MalformedChild))

  private def populateDemand(packet: OooD1DecodedPacket): Unit = {
    packet.demand.instructionRows := PopCount(packet.uops.flatMap(_.identity.parents).map { parent =>
      parent.key.valid && parent.traceOwner
    })
    packet.acceptedInstructionMask :=
      ((1.U((p.instructionDecodeWidth + 1).W) << packet.demand.instructionRows) - 1.U)(
        p.instructionDecodeWidth - 1, 0)
    packet.demand.decodedUops := PopCount(packet.uopMask)
    packet.demand.robGroups := 0.U
    packet.demand.brobSlots := PopCount(packet.uops.map { uop =>
      uop.valid && uop.identity.boundary.start
    })
    packet.demand.pcBaseWrites := PopCount(packet.uops.flatMap(_.identity.parents).map { parent =>
      parent.key.valid && parent.traceOwner && parent.prediction.valid && parent.prediction.taken
    })
    packet.demand.pDestinations := PopCount(packet.uops.flatMap(_.destinations).map { destination =>
      destination.valid && destination.kind === DestinationKind.Gpr
    })
    packet.demand.tAllocations := PopCount(packet.uops.flatMap(_.destinations).map { destination =>
      destination.valid && destination.kind === DestinationKind.T
    })
    packet.demand.uAllocations := PopCount(packet.uops.flatMap(_.destinations).map { destination =>
      destination.valid && destination.kind === DestinationKind.U
    })
    packet.demand.mapQRows := packet.demand.pDestinations
    for (dispatchClass <- 0 until p.iqClassCount) {
      packet.demand.dispatchWritesByClass(dispatchClass) := packet.uops.map { uop =>
        Mux(uop.valid, uop.recipe.dispatchDemand(dispatchClass), 0.U)
      }.reduce(_ +& _)
    }
    packet.demand.dispatchWritesByBank.foreach(_ := 0.U)
    packet.demand.loadIds := packet.uops.map { uop =>
      val load = uop.recipe.recipeKind === OooOpcodeRecipeKind.ScalarLoad.U ||
        uop.recipe.recipeKind === OooOpcodeRecipeKind.PairLoad.U
      Mux(uop.valid && load, uop.recipe.memoryRequestCount, 0.U)
    }.reduce(_ +& _)
    packet.demand.storeIds := packet.uops.map { uop =>
      val store = uop.recipe.recipeKind === OooOpcodeRecipeKind.ScalarStore.U ||
        uop.recipe.recipeKind === OooOpcodeRecipeKind.PairStore.U
      Mux(uop.valid && store, uop.recipe.memoryRequestCount, 0.U)
    }.reduce(_ +& _)
  }

  val normalPacket = Wire(new OooD1DecodedPacket(p))
  normalPacket := 0.U.asTypeOf(normalPacket)
  normalPacket.peId := packets(actionStid).peId
  normalPacket.stid := actionStid
  normalPacket.epoch := packets(actionStid).epoch
  val normalCount = PopCount(actionNormalMask)
  normalPacket.uopMask :=
    ((1.U((p.decodedUopWidth + 1).W) << normalCount) - 1.U)(p.decodedUopWidth - 1, 0)
  for (sourceIndex <- 0 until p.decodedUopWidth) {
    val outputIndex = if (sourceIndex == 0) 0.U else PopCount(actionNormalMask(sourceIndex - 1, 0))
    for (outputSlot <- 0 until p.decodedUopWidth) {
      when(actionNormalMask(sourceIndex) && outputIndex === outputSlot.U) {
        normalPacket.uops(outputSlot) := packets(actionStid).uops(sourceIndex)
      }
    }
  }
  val normalLeavesUops = (remainingUops(actionStid) & ~actionNormalMask).orR
  val normalLeavesSideband = remainingCtu(actionStid).orR ||
    remainingComplex(actionStid).orR
  normalPacket.endOfStream := packets(actionStid).endOfStream &&
    !normalLeavesUops && !normalLeavesSideband
  populateDemand(normalPacket)

  val childPacket = Wire(new OooD1DecodedPacket(p))
  childPacket := 0.U.asTypeOf(childPacket)
  childPacket.peId := lease(childStid).peId
  childPacket.stid := childStid
  childPacket.epoch := lease(childStid).parent.epoch
  childPacket.uopMask := 1.U
  childPacket.uops(0) := io.child.bits.uop
  childPacket.uops(0).identity.key.primaryParent := lease(childStid).parent
  childPacket.uops(0).identity.key.uopOrdinal := io.child.bits.ordinal
  childPacket.uops(0).identity.key.uopCount := io.child.bits.childCount
  childPacket.uops(0).identity.parentCount := 1.U
  childPacket.uops(0).identity.parents(0) := leaseParent(childStid).parent
  childPacket.uops(0).identity.parents(0).traceOwner := io.child.bits.finalChild
  childPacket.uops(0).identity.templateValid := true.B
  childPacket.uops(0).identity.templateGroupId := lease(childStid).templateGroupId
  childPacket.uops(0).identity.templateGeneration := lease(childStid).generation
  childPacket.endOfStream := false.B
  populateDemand(childPacket)

  val childUsesOutput = io.child.valid && childExact
  io.out.valid := childUsesOutput || (!childUsesOutput && actionNormal)
  io.out.bits := Mux(childUsesOutput, childPacket, normalPacket)
  io.child.ready := Mux(childBlocked, false.B, Mux(childExact, io.out.ready, true.B))

  val normalFire = io.out.fire && !childUsesOutput
  val childFire = io.child.fire && childExact
  when(normalFire) {
    remainingUops(actionStid) := remainingUops(actionStid) & ~actionNormalMask
    when(!normalLeavesUops && !normalLeavesSideband) {
      packetValid(actionStid) := false.B
    }
    rrStart := actionStid + 1.U
  }

  when(io.parentClaim.fire) {
    leaseState(actionStid) := OooCtuLeaseState.WaitPlan
    lease(actionStid) := offeredLease
    leaseParent(actionStid) := io.parentClaim.bits.parent
    remainingCtu(actionStid) := remainingCtu(actionStid) & ~(1.U << claimLane)
    nextTemplateGroupId(actionStid) := nextTemplateGroupId(actionStid) + 1.U
    nextGeneration(actionStid) := nextGeneration(actionStid) + 1.U
    rrStart := actionStid + 1.U
  }

  when(io.expansionPlan.fire && planLeaseExact && planCountWellFormed) {
    leaseState(planStid) := OooCtuLeaseState.EmitChildren
    leaseChildCount(planStid) := io.expansionPlan.bits.childCount
    expectedOrdinal(planStid) := 0.U
  }

  when(childFire) {
    when(io.child.bits.finalChild) {
      leaseState(childStid) := OooCtuLeaseState.Idle
      lease(childStid).valid := false.B
      when(!remainingUops(childStid).orR && !remainingCtu(childStid).orR &&
          !remainingComplex(childStid).orR) {
        packetValid(childStid) := false.B
      }
    }.otherwise {
      expectedOrdinal(childStid) := expectedOrdinal(childStid) + 1.U
    }
  }

  for (stid <- 0 until p.stidCount) {
    val recoveryOwnsCancellation = recoveryPending && recoveryStid === stid.U
    when(io.cancel(stid) && !recoveryOwnsCancellation) {
      packetValid(stid) := false.B
      remainingUops(stid) := 0.U
      remainingCtu(stid) := 0.U
      remainingComplex(stid) := 0.U
      leaseState(stid) := OooCtuLeaseState.Idle
      lease(stid).valid := false.B
    }
  }

  when(io.recoveryApply) {
    assert(recoveryPending, "CTU recovery apply requires a retained prepare")
    val stid = recoveryStid
    packetValid(stid) := false.B
    remainingUops(stid) := 0.U
    remainingCtu(stid) := 0.U
    remainingComplex(stid) := 0.U
    leaseState(stid) := OooCtuLeaseState.Idle
    lease(stid).valid := false.B
    recoveryPending := false.B
  }.elsewhen(io.recoveryAbort) {
    assert(recoveryPending, "CTU recovery abort requires a retained prepare")
    recoveryPending := false.B
  }

  io.occupied := packetValid
  for (stid <- 0 until p.stidCount) {
    io.active(stid) := leaseState(stid) =/= OooCtuLeaseState.Idle
    io.blockedByComplex(stid) := complexBlocked(stid)
  }

  when(io.in.valid) {
    assert(inputStidInRange, "CTU ingress packet STID must be in range")
  }
  when(io.parentClaim.fire) {
    assert(io.parentClaim.bits.parent.parent.key === io.parentClaim.bits.lease.parent)
  }
  when(childFire) {
    assert(io.out.bits.ctuParentMask === 0.U)
    assert(io.out.bits.complexParentMask === 0.U)
  }
}
