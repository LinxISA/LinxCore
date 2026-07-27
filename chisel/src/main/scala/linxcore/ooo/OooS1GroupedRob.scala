package linxcore.ooo

import chisel3._
import chisel3.util.{Cat, Decoupled, PriorityEncoder, UIntToOH, Valid}

class OooS1GroupedRobIO(val p: OooParams = OooParams()) extends Bundle {
  val publish = Flipped(Decoupled(new OooS1GroupedPublicationRequest(p)))
  val completion = Flipped(Decoupled(new OooRobMemberCompletion(p)))
  val nonFlushEvidence = Flipped(Decoupled(new OooRobNonFlushEvidence(p)))
  val interruptPending = Input(Vec(p.stidCount, Bool()))
  val commit = Decoupled(new OooRobCommitBatch(p))

  val publicationRejected = Valid(new OooS1PublicationReject(p))
  val completionRejected = Valid(new OooRobMemberCompletionReject(p))
  val nonFlushEvidenceRejected = Valid(new OooRobNonFlushEvidenceReject(p))
  val nonFlushWindows = Output(Vec(p.stidCount, new NonFlushWindow(p)))
  val occupiedGroups = Output(Vec(p.stidCount, UInt(p.countWidth(p.robGroupsPerStid).W)))
  val headSlot = Output(Vec(p.stidCount, UInt(p.ridSlotWidth.W)))
  val headGeneration = Output(Vec(p.stidCount, UInt(p.ridGenerationWidth.W)))
  val headEpoch = Output(Vec(p.stidCount, UInt(p.reservationEpochWidth.W)))
}

/** S1 physical grouped ROB owner.
  *
  * Publication is atomic across every group in a D3 reservation. Completion
  * consumes an exact member key and rejects stale/duplicate reports with zero
  * mutation. Commit is a retained, older-first batch; its release token is the
  * sole authority for advancing the matching D3 allocator head.
  */
class OooS1GroupedRob(val p: OooParams = OooParams()) extends Module {
  val io = IO(new OooS1GroupedRobIO(p))
  private val occupiedWidth = p.countWidth(p.robGroupsPerStid)

  val rows = RegInit(VecInit(Seq.fill(p.stidCount)(
    VecInit(Seq.fill(p.robGroupsPerStid)(
      0.U.asTypeOf(new OooRobPhysicalGroupRecord(p)))))))
  val occupied = RegInit(VecInit(Seq.fill(p.stidCount)(0.U(occupiedWidth.W))))
  val headSlot = RegInit(VecInit(Seq.fill(p.stidCount)(0.U(p.ridSlotWidth.W))))
  val headGeneration = RegInit(VecInit(Seq.fill(p.stidCount)(0.U(p.ridGenerationWidth.W))))
  val headEpoch = RegInit(VecInit(Seq.fill(p.stidCount)(0.U(p.reservationEpochWidth.W))))
  val headPeId = RegInit(VecInit(Seq.fill(p.stidCount)(0.U(p.peIdWidth.W))))
  val nonFlushAuthorized = RegInit(VecInit(Seq.fill(p.stidCount)(
    0.U(p.nonFlushPrefixCountWidth.W))))
  val nonFlushEpoch = RegInit(VecInit(Seq.fill(p.stidCount)(0.U(p.epochWidth.W))))

  val publishStid = io.publish.bits.reservation.transaction.plan.stid
  val publishStidInRange = publishStid < p.stidCount.U
  val safePublishStid = Mux(publishStidInRange, publishStid, 0.U)
  val publishGroupCount = io.publish.bits.reservation.transaction.plan.groupCount
  val publishGroupCountInRange = publishGroupCount.orR &&
    publishGroupCount <= p.instructionDecodeWidth.U
  val liveTailSum = headSlot(safePublishStid).pad(occupiedWidth + 1) +&
    occupied(safePublishStid).pad(occupiedWidth + 1)
  val liveTailWrap = liveTailSum >= p.robGroupsPerStid.U
  val liveTailSlot = liveTailSum(p.ridSlotWidth - 1, 0)
  val liveTailGeneration = headGeneration(safePublishStid) + liveTailWrap.asUInt
  val tailAfterSum = liveTailSlot +& publishGroupCount
  val tailAfterWrap = tailAfterSum >= p.robGroupsPerStid.U
  val hasPublicationCapacity =
    occupied(safePublishStid).pad(occupiedWidth + 1) +
      publishGroupCount.pad(occupiedWidth + 1) <= p.robGroupsPerStid.U
  val expectedGroupMask =
    ((1.U((p.instructionDecodeWidth + 1).W) << publishGroupCount) - 1.U)(
      p.instructionDecodeWidth - 1, 0)
  val publicationIdentityExact =
    io.publish.bits.reservation.transaction.plan.peId ===
      io.publish.bits.reservation.transaction.decoded.peId &&
      publishStid === io.publish.bits.reservation.transaction.decoded.stid &&
      io.publish.bits.reservation.transaction.plan.epoch ===
        io.publish.bits.reservation.transaction.decoded.epoch

  val groupRequiredProofs = Wire(Vec(p.instructionDecodeWidth,
    UInt(OooNonFlushProof.Count.W)))
  val groupNonFlushNever = Wire(Vec(p.instructionDecodeWidth, Bool()))
  for (groupIndex <- 0 until p.instructionDecodeWidth) {
    val group = io.publish.bits.reservation.transaction.groups(groupIndex)
    val decoded = io.publish.bits.reservation.transaction.decoded
    val exceptionRequired = (0 until p.decodedUopWidth).map { uopIndex =>
      val member = group.logicalUopMask(uopIndex)
      val uop = decoded.uops(uopIndex)
      member && uop.valid && (uop.recipe.mayTrap || uop.recipe.mayTrapLate)
    }.reduce(_ || _)
    val memoryRequired = (0 until p.decodedUopWidth).map { uopIndex =>
      val member = group.logicalUopMask(uopIndex)
      val uop = decoded.uops(uopIndex)
      member && uop.valid && (uop.recipe.memoryRequestCount.orR ||
        uop.recipe.mayTrapLate || uop.recipe.sideEffectOwner === OooSideEffectOwner.Lsu.U)
    }.reduce(_ || _)
    val controlRequired = group.boundaryStart || group.boundaryStop ||
      (0 until p.decodedUopWidth).map { uopIndex =>
        val member = group.logicalUopMask(uopIndex)
        val uop = decoded.uops(uopIndex)
        member && uop.valid && (uop.recipe.mayRedirect ||
          uop.recipe.requiresTargetValidation ||
          uop.recipe.sideEffectOwner === OooSideEffectOwner.Bctrl.U)
      }.reduce(_ || _)
    val serializationRequired = (0 until p.decodedUopWidth).map { uopIndex =>
      val member = group.logicalUopMask(uopIndex)
      val uop = decoded.uops(uopIndex)
      member && uop.valid && (uop.recipe.nonspeculative ||
        uop.recipe.dispatchClass === OooDispatchClass.Sys.U ||
        uop.recipe.dispatchClass === OooDispatchClass.Cmd.U ||
        uop.recipe.sideEffectOwner === OooSideEffectOwner.Commit.U ||
        uop.recipe.sideEffectOwner === OooSideEffectOwner.Ctu.U)
    }.reduce(_ || _)
    groupRequiredProofs(groupIndex) := Cat(
      serializationRequired, controlRequired, memoryRequired, exceptionRequired)
    val malformedMember = (0 until p.decodedUopWidth).map { uopIndex =>
      val member = group.logicalUopMask(uopIndex)
      val uop = decoded.uops(uopIndex)
      member && (!decoded.uopMask(uopIndex) || !uop.valid || !uop.recipe.valid ||
        uop.recipe.disposition === OooOpcodeDisposition.Illegal.U ||
        uop.recipe.sideEffectOwner === OooSideEffectOwner.Illegal.U)
    }.reduce(_ || _)
    groupNonFlushNever(groupIndex) := group.preciseTrap ||
      !group.logicalUopMask.orR || malformedMember
  }

  val targetExact = Wire(Vec(p.instructionDecodeWidth, Bool()))
  val targetFree = Wire(Vec(p.instructionDecodeWidth, Bool()))
  for (groupIndex <- 0 until p.instructionDecodeWidth) {
    val group = io.publish.bits.reservation.transaction.groups(groupIndex)
    val binding = io.publish.bits.bindings(groupIndex)
    val active = groupIndex.U < publishGroupCount
    val targetRow = rows(safePublishStid)(group.key.ridSlot)
    val expectedSlotSum =
      io.publish.bits.reservation.transaction.plan.firstVirtualGroup.ridSlot +& groupIndex.U
    val expectedWrap = expectedSlotSum >= p.robGroupsPerStid.U
    val memberMask =
      ((1.U((p.maxOrdinaryUopsPerGroup + 1).W) << group.physicalMemberCount) - 1.U)(
        p.maxOrdinaryUopsPerGroup - 1, 0)
    targetExact(groupIndex) :=
      group.valid === active && binding.valid === active && (!active || (
        group.key.valid &&
          group.key.peId === io.publish.bits.reservation.transaction.plan.peId &&
          group.key.stid === publishStid &&
          group.key.ridSlot === expectedSlotSum(p.ridSlotWidth - 1, 0) &&
          group.key.ridGeneration ===
            io.publish.bits.reservation.transaction.plan.firstVirtualGroup.ridGeneration +
              expectedWrap.asUInt &&
          binding.brob.valid && binding.brob.bid.valid &&
          group.physicalMemberCount.orR &&
          group.physicalMemberCount <= p.maxOrdinaryUopsPerGroup.U &&
          group.architecturalParentCount.orR &&
          group.architecturalParentCount <= p.maxInstPerRobGroup.U &&
          (binding.initiallyCompletedMembers & ~memberMask) === 0.U))
    targetFree(groupIndex) := !active || !targetRow.valid
  }
  val publicationExact = publishStidInRange && publishGroupCountInRange &&
    hasPublicationCapacity &&
    io.publish.bits.reservation.transaction.plan.firstVirtualGroup.valid &&
    io.publish.bits.reservation.transaction.plan.firstVirtualGroup.peId ===
      io.publish.bits.reservation.transaction.plan.peId &&
    io.publish.bits.reservation.transaction.plan.firstVirtualGroup.stid === publishStid &&
    io.publish.bits.reservation.transaction.plan.firstVirtualGroup.ridSlot === liveTailSlot &&
    io.publish.bits.reservation.transaction.plan.firstVirtualGroup.ridGeneration ===
      liveTailGeneration &&
    (!occupied(safePublishStid).orR ||
      io.publish.bits.reservation.transaction.plan.peId === headPeId(safePublishStid)) &&
    io.publish.bits.reservation.tailAfter.valid &&
    io.publish.bits.reservation.tailAfter.peId ===
      io.publish.bits.reservation.transaction.plan.peId &&
    io.publish.bits.reservation.tailAfter.stid === publishStid &&
    io.publish.bits.reservation.tailAfter.ridSlot ===
      tailAfterSum(p.ridSlotWidth - 1, 0) &&
    io.publish.bits.reservation.tailAfter.ridGeneration ===
      liveTailGeneration + tailAfterWrap.asUInt &&
    io.publish.bits.reservation.transaction.groupMask === expectedGroupMask &&
    publicationIdentityExact && targetExact.reduce(_ && _)
  val allTargetsFree = targetFree.reduce(_ && _)
  io.publish.ready := publicationExact && allTargetsFree
  val publishFire = io.publish.valid && io.publish.ready

  io.publicationRejected.valid := io.publish.valid && !io.publish.ready
  io.publicationRejected.bits.stid := publishStid
  io.publicationRejected.bits.transactionId :=
    io.publish.bits.reservation.transaction.plan.transactionId
  io.publicationRejected.bits.groupMask :=
    io.publish.bits.reservation.transaction.groupMask

  for (groupIndex <- 0 until p.instructionDecodeWidth) {
    val group = io.publish.bits.reservation.transaction.groups(groupIndex)
    val binding = io.publish.bits.bindings(groupIndex)
    when(publishFire && group.valid) {
      val row = rows(publishStid)(group.key.ridSlot)
      row.valid := true.B
      row.key := group.key
      row.transactionId := io.publish.bits.reservation.transaction.plan.transactionId
      row.claimEpoch := io.publish.bits.reservation.claimEpoch
      row.brob := binding.brob
      row.pcBase := binding.pcBase
      row.residentGeneration := binding.residentGeneration
      row.logicalUopMask := group.logicalUopMask
      row.physicalMemberCount := group.physicalMemberCount
      row.pMapQRows := group.pMapQRows
      row.completedMembers := binding.initiallyCompletedMembers
      row.architecturalParentCount := group.architecturalParentCount
      row.boundaryStart := group.boundaryStart
      row.boundaryStop := group.boundaryStop
      row.releasePcBase := group.releasePcBase
      row.preciseTrap := group.preciseTrap
      row.nonFlushRequiredProofs := groupRequiredProofs(groupIndex)
      row.nonFlushObservedProofs := 0.U
      row.nonFlushNever := groupNonFlushNever(groupIndex)
    }
  }

  val completionStid = io.completion.bits.key.group.stid
  val completionStidInRange = completionStid < p.stidCount.U
  val safeCompletionStid = Mux(completionStidInRange, completionStid, 0.U)
  val completionSlot = io.completion.bits.key.group.ridSlot
  val liveCompletionRow = rows(safeCompletionStid)(completionSlot)
  val completionMemberInRange =
    io.completion.bits.key.memberIndex < liveCompletionRow.physicalMemberCount &&
      io.completion.bits.key.memberIndex < p.maxOrdinaryUopsPerGroup.U
  val completionBit = UIntToOH(
    io.completion.bits.key.memberIndex,
    p.maxOrdinaryUopsPerGroup)
  val completionExact = completionStidInRange && liveCompletionRow.valid &&
    liveCompletionRow.brob.valid && liveCompletionRow.brob.bid.valid &&
    io.completion.bits.key.group.asUInt === liveCompletionRow.key.asUInt &&
    io.completion.bits.key.bid.asUInt === liveCompletionRow.brob.bid.asUInt &&
    io.completion.bits.key.brobGeneration === liveCompletionRow.brob.generation &&
    io.completion.bits.key.residentGeneration === liveCompletionRow.residentGeneration &&
    completionMemberInRange &&
    !(liveCompletionRow.completedMembers & completionBit).orR

  io.completion.ready := true.B
  val completionFire = io.completion.valid && io.completion.ready
  when(completionFire && completionExact) {
    rows(completionStid)(completionSlot).completedMembers :=
      liveCompletionRow.completedMembers | completionBit
  }
  io.completionRejected.valid := completionFire && !completionExact
  io.completionRejected.bits.requested := io.completion.bits.key
  io.completionRejected.bits.occupied := completionStidInRange && liveCompletionRow.valid
  io.completionRejected.bits.live := liveCompletionRow

  val evidenceStid = io.nonFlushEvidence.bits.key.group.stid
  val evidenceStidInRange = evidenceStid < p.stidCount.U
  val safeEvidenceStid = Mux(evidenceStidInRange, evidenceStid, 0.U)
  val evidenceSlot = io.nonFlushEvidence.bits.key.group.ridSlot
  val liveEvidenceRow = rows(safeEvidenceStid)(evidenceSlot)
  val evidenceMemberInRange =
    io.nonFlushEvidence.bits.key.memberIndex < liveEvidenceRow.physicalMemberCount &&
      io.nonFlushEvidence.bits.key.memberIndex < p.maxOrdinaryUopsPerGroup.U
  val newEvidenceProofs = io.nonFlushEvidence.bits.proofs &
    liveEvidenceRow.nonFlushRequiredProofs &
    ~liveEvidenceRow.nonFlushObservedProofs
  val evidenceExact = evidenceStidInRange && liveEvidenceRow.valid &&
    !liveEvidenceRow.nonFlushNever && liveEvidenceRow.brob.valid &&
    liveEvidenceRow.brob.bid.valid &&
    io.nonFlushEvidence.bits.key.group.asUInt === liveEvidenceRow.key.asUInt &&
    io.nonFlushEvidence.bits.key.bid.asUInt === liveEvidenceRow.brob.bid.asUInt &&
    io.nonFlushEvidence.bits.key.brobGeneration === liveEvidenceRow.brob.generation &&
    io.nonFlushEvidence.bits.key.residentGeneration ===
      liveEvidenceRow.residentGeneration && evidenceMemberInRange &&
    newEvidenceProofs.orR

  io.nonFlushEvidence.ready := true.B
  val evidenceFire = io.nonFlushEvidence.valid && io.nonFlushEvidence.ready
  when(evidenceFire && evidenceExact) {
    rows(evidenceStid)(evidenceSlot).nonFlushObservedProofs :=
      liveEvidenceRow.nonFlushObservedProofs | newEvidenceProofs
  }
  io.nonFlushEvidenceRejected.valid := evidenceFire && !evidenceExact
  io.nonFlushEvidenceRejected.bits.requested := io.nonFlushEvidence.bits
  io.nonFlushEvidenceRejected.bits.occupied :=
    evidenceStidInRange && liveEvidenceRow.valid
  io.nonFlushEvidenceRejected.bits.live := liveEvidenceRow

  def groupComplete(row: OooRobPhysicalGroupRecord): Bool = {
    val expected =
      ((1.U((p.maxOrdinaryUopsPerGroup + 1).W) << row.physicalMemberCount) - 1.U)(
        p.maxOrdinaryUopsPerGroup - 1, 0)
    row.valid && row.physicalMemberCount.orR &&
      (row.completedMembers & expected) === expected
  }

  def groupNonFlushSafe(row: OooRobPhysicalGroupRecord): Bool =
    row.valid && !row.nonFlushNever &&
      (row.nonFlushObservedProofs & row.nonFlushRequiredProofs) ===
        row.nonFlushRequiredProofs

  val safePrefixByStid = Wire(Vec(p.stidCount,
    UInt(p.nonFlushPrefixCountWidth.W)))
  for (stid <- 0 until p.stidCount) {
    val prefix = Wire(Vec(p.robGroupsPerStid + 1,
      UInt(p.nonFlushPrefixCountWidth.W)))
    prefix(0) := 0.U
    for (offset <- 0 until p.robGroupsPerStid) {
      val slotSum = headSlot(stid) +& offset.U
      val wraps = slotSum >= p.robGroupsPerStid.U
      val row = rows(stid)(slotSum(p.ridSlotWidth - 1, 0))
      val expectedGeneration = headGeneration(stid) + wraps.asUInt
      val exactHead = row.key.valid && row.key.peId === headPeId(stid) &&
        row.key.stid === stid.U &&
        row.key.ridSlot === slotSum(p.ridSlotWidth - 1, 0) &&
        row.key.ridGeneration === expectedGeneration
      val canExtend = prefix(offset) === offset.U &&
        offset.U < occupied(stid) && exactHead && groupNonFlushSafe(row)
      prefix(offset + 1) := Mux(canExtend, (offset + 1).U, prefix(offset))
    }
    safePrefixByStid(stid) := prefix(p.robGroupsPerStid)
  }

  val commitCountByStid = Wire(Vec(p.stidCount, UInt(p.robReleaseCountWidth.W)))
  for (stid <- 0 until p.stidCount) {
    val prefix = Wire(Vec(p.retireGroupWidth + 1, UInt(p.robReleaseCountWidth.W)))
    prefix(0) := 0.U
    for (offset <- 0 until p.retireGroupWidth) {
      val slotSum = headSlot(stid) +& offset.U
      val wraps = slotSum >= p.robGroupsPerStid.U
      val row = rows(stid)(slotSum(p.ridSlotWidth - 1, 0))
      val expectedGeneration = headGeneration(stid) + wraps.asUInt
      val exactHead = row.key.valid && row.key.peId === headPeId(stid) &&
        row.key.stid === stid.U &&
        row.key.ridSlot === slotSum(p.ridSlotWidth - 1, 0) &&
        row.key.ridGeneration === expectedGeneration
      val canExtend = prefix(offset) === offset.U &&
        offset.U < occupied(stid) && exactHead && groupComplete(row)
      prefix(offset + 1) := Mux(canExtend, (offset + 1).U, prefix(offset))
    }
    commitCountByStid(stid) := prefix(p.retireGroupWidth)
  }

  val commitPending = RegInit(false.B)
  val commitRow = Reg(new OooRobCommitBatch(p))
  val rrStart = RegInit(0.U(p.stidWidth.W))
  val rotatedEligible = Wire(Vec(p.stidCount, Bool()))
  for (offset <- 0 until p.stidCount) {
    val index = if (p.stidCount == 1) 0.U else (rrStart + offset.U)(p.stidWidth - 1, 0)
    rotatedEligible(offset) := commitCountByStid(index).orR
  }
  val anyEligible = rotatedEligible.asUInt.orR
  val selectedOffset = if (p.stidCount == 1) 0.U else PriorityEncoder(rotatedEligible.asUInt)
  val selectedStid =
    if (p.stidCount == 1) 0.U(p.stidWidth.W)
    else (rrStart + selectedOffset)(p.stidWidth - 1, 0)

  when(!commitPending && anyEligible) {
    val selectedCount = commitCountByStid(selectedStid)
    commitPending := true.B
    commitRow := 0.U.asTypeOf(commitRow)
    commitRow.release.firstGroup.valid := true.B
    commitRow.release.firstGroup.peId := headPeId(selectedStid)
    commitRow.release.firstGroup.stid := selectedStid
    commitRow.release.firstGroup.ridSlot := headSlot(selectedStid)
    commitRow.release.firstGroup.ridGeneration := headGeneration(selectedStid)
    commitRow.release.headEpoch := headEpoch(selectedStid)
    commitRow.release.groupCount := selectedCount
    for (offset <- 0 until p.retireGroupWidth) {
      val slotSum = headSlot(selectedStid) +& offset.U
      when(offset.U < selectedCount) {
        commitRow.groups(offset) :=
          rows(selectedStid)(slotSum(p.ridSlotWidth - 1, 0))
      }
    }
  }

  io.commit.valid := commitPending
  io.commit.bits := commitRow
  val commitFire = io.commit.valid && io.commit.ready
  when(commitFire) {
    val stid = commitRow.release.firstGroup.stid
    val count = commitRow.release.groupCount
    for (offset <- 0 until p.retireGroupWidth) {
      val slotSum = commitRow.release.firstGroup.ridSlot +& offset.U
      when(offset.U < count) {
        rows(stid)(slotSum(p.ridSlotWidth - 1, 0)) :=
          0.U.asTypeOf(new OooRobPhysicalGroupRecord(p))
      }
    }
    val headSum = headSlot(stid) +& count
    val wraps = headSum >= p.robGroupsPerStid.U
    occupied(stid) := occupied(stid) - count
    headSlot(stid) := headSum(p.ridSlotWidth - 1, 0)
    headGeneration(stid) := headGeneration(stid) + wraps.asUInt
    headEpoch(stid) := headEpoch(stid) + 1.U
    commitPending := false.B
    rrStart :=
      (if (p.stidCount == 1) 0.U else (stid + 1.U)(p.stidWidth - 1, 0))
  }

  for (stid <- 0 until p.stidCount) {
    val commitHere = commitFire &&
      commitRow.release.firstGroup.stid === stid.U
    val committedCount = commitRow.release.groupCount
    val authorizedAfterCommit = Mux(
      committedCount >= nonFlushAuthorized(stid),
      0.U,
      nonFlushAuthorized(stid) - committedCount)
    when(commitHere) {
      nonFlushAuthorized(stid) := authorizedAfterCommit
      nonFlushEpoch(stid) := nonFlushEpoch(stid) + 1.U
    }.elsewhen(safePrefixByStid(stid) < nonFlushAuthorized(stid)) {
      // Safety is monotonic for a live row; this branch is a fail-closed guard
      // for owner reset/recovery integration added in O7.
      nonFlushAuthorized(stid) := safePrefixByStid(stid)
      nonFlushEpoch(stid) := nonFlushEpoch(stid) + 1.U
    }.elsewhen(!io.interruptPending(stid) &&
      safePrefixByStid(stid) > nonFlushAuthorized(stid)) {
      nonFlushAuthorized(stid) := safePrefixByStid(stid)
      nonFlushEpoch(stid) := nonFlushEpoch(stid) + 1.U
    }
  }

  for (stid <- 0 until p.stidCount) {
    val publishedHere = publishFire && publishStid === stid.U
    when(publishedHere) {
      when(occupied(stid) === 0.U) {
        headSlot(stid) := io.publish.bits.reservation.transaction.plan.firstVirtualGroup.ridSlot
        headGeneration(stid) :=
          io.publish.bits.reservation.transaction.plan.firstVirtualGroup.ridGeneration
        headPeId(stid) := io.publish.bits.reservation.transaction.plan.peId
      }
      occupied(stid) := occupied(stid) + publishGroupCount
    }
    when(publishedHere && commitFire && commitRow.release.firstGroup.stid === stid.U) {
      occupied(stid) := occupied(stid) + publishGroupCount - commitRow.release.groupCount
      when(occupied(stid) === commitRow.release.groupCount) {
        headPeId(stid) := io.publish.bits.reservation.transaction.plan.peId
      }
    }
  }

  io.occupiedGroups := occupied
  io.headSlot := headSlot
  io.headGeneration := headGeneration
  io.headEpoch := headEpoch
  for (stid <- 0 until p.stidCount) {
    val window = io.nonFlushWindows(stid)
    window.valid := occupied(stid).orR
    window.stid := stid.U
    window.head.valid := occupied(stid).orR
    window.head.peId := headPeId(stid)
    window.head.stid := stid.U
    window.head.ridSlot := headSlot(stid)
    window.head.ridGeneration := headGeneration(stid)
    window.prefixCount := nonFlushAuthorized(stid)
    window.epoch := nonFlushEpoch(stid)
    assert(nonFlushAuthorized(stid) <= occupied(stid),
      "non-flush prefix cannot extend beyond the live ROB window")
  }

  when(publishFire) {
    assert(publishGroupCount <= p.robGroupsPerStid.U)
  }
  when(commitPending && !io.commit.ready) {
    assert(io.commit.bits.asUInt === commitRow.asUInt,
      "retained grouped-ROB commit must remain stable under backpressure")
  }
}
