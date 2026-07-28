package linxcore.ooo

import chisel3._
import chisel3.util.{Cat, Decoupled, PopCount, PriorityEncoder, UIntToOH, Valid}

object OooRobRecoveryScanState extends ChiselEnum {
  val Idle, FindPivot, BuildPlan, Prepared, Rejected = Value
}

class OooS1GroupedRobIO(val p: OooParams = OooParams()) extends Bundle {
  val publish = Flipped(Decoupled(new OooS1GroupedPublicationRequest(p)))
  val completion = Flipped(Decoupled(new OooRobMemberCompletion(p)))
  val nonFlushEvidence = Flipped(Decoupled(new OooRobNonFlushEvidence(p)))
  val interruptPending = Input(Vec(p.stidCount, Bool()))
  val recoveryPrepare = Flipped(Valid(new OooGlobalRecoveryRequest(p)))
  val recoveryPrepareReady = Output(Bool())
  val recoveryPrepared = Output(new OooRobRecoveryPlan(p))
  val recoveryFire = Input(Bool())
  val commit = Decoupled(new OooRobCommitBatch(p))

  val publicationRejected = Valid(new OooS1PublicationReject(p))
  val completionRejected = Valid(new OooRobMemberCompletionReject(p))
  val nonFlushEvidenceRejected = Valid(new OooRobNonFlushEvidenceReject(p))
  val recoveryRejected = Valid(new OooRobRecoveryReject(p))
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
    VecInit(Seq.fill(p.robBankCountEffective)(
      VecInit(Seq.fill(p.robSubbankCountEffective)(
        VecInit(Seq.fill(p.robRowsPerSubbank)(
          0.U.asTypeOf(new OooRobPhysicalGroupRecord(p)))))))))))
  private def robBankIndex(slot: UInt): UInt =
    if (p.robBankCountEffective == 1) 0.U(p.robBankIndexWidth.W)
    else slot(p.robBankSelectionBits - 1, 0)
  private def robSubbankIndex(slot: UInt): UInt =
    if (p.robSubbankCountEffective == 1) 0.U(p.robSubbankIndexWidth.W)
    else (slot >> p.robBankSelectionBits)(p.robSubbankSelectionBits - 1, 0)
  private def robSubbankRowIndex(slot: UInt): UInt =
    if (p.robRowsPerSubbank == 1) 0.U(p.robSubbankRowIndexWidth.W)
    else (slot >> (p.robBankSelectionBits + p.robSubbankSelectionBits))(
      p.robSubbankRowIndexWidth - 1, 0)
  private def rowAt(stid: UInt, slot: UInt): OooRobPhysicalGroupRecord =
    rows(stid)(robBankIndex(slot))(robSubbankIndex(slot))(
      robSubbankRowIndex(slot))
  val occupied = RegInit(VecInit(Seq.fill(p.stidCount)(0.U(occupiedWidth.W))))
  val headSlot = RegInit(VecInit(Seq.fill(p.stidCount)(0.U(p.ridSlotWidth.W))))
  val headGeneration = RegInit(VecInit(Seq.fill(p.stidCount)(0.U(p.ridGenerationWidth.W))))
  val headEpoch = RegInit(VecInit(Seq.fill(p.stidCount)(0.U(p.reservationEpochWidth.W))))
  val headPeId = RegInit(VecInit(Seq.fill(p.stidCount)(0.U(p.peIdWidth.W))))
  val nonFlushAuthorized = RegInit(VecInit(Seq.fill(p.stidCount)(
    0.U(p.nonFlushPrefixCountWidth.W))))
  val nonFlushEpoch = RegInit(VecInit(Seq.fill(p.stidCount)(0.U(p.epochWidth.W))))

  val recoveryScanState = RegInit(OooRobRecoveryScanState.Idle)
  val retainedRecoveryRequest = RegInit(
    0.U.asTypeOf(new OooGlobalRecoveryRequest(p)))
  val retainedRecoveryPlan = RegInit(
    0.U.asTypeOf(new OooRobRecoveryPlan(p)))
  val recoveryScanCursor = RegInit(0.U(p.robRecoveryScanCursorWidth.W))
  val recoveryExactMatchCountReg = RegInit(
    0.U(p.countWidth(p.robGroupsPerStid).W))
  val recoveryWindowExactReg = RegInit(true.B)
  val recoveryPivotReg = RegInit(
    0.U.asTypeOf(new OooRobPhysicalGroupRecord(p)))
  val recoveryPivotOffsetReg = RegInit(
    0.U(p.nonFlushPrefixCountWidth.W))
  val recoverySnapshotOccupied = RegInit(
    0.U(p.nonFlushPrefixCountWidth.W))
  val recoverySnapshotHeadSlot = RegInit(0.U(p.ridSlotWidth.W))
  val recoverySnapshotHeadGeneration = RegInit(
    0.U(p.ridGenerationWidth.W))
  val recoverySnapshotHeadPeId = RegInit(0.U(p.peIdWidth.W))

  val recoveryRequest = Mux(
    recoveryScanState === OooRobRecoveryScanState.Idle,
    io.recoveryPrepare.bits,
    retainedRecoveryRequest)
  val recoveryMember = recoveryRequest.rename.key.member
  val recoveryStid = recoveryMember.group.stid
  val recoveryStidInRange = recoveryStid < p.stidCount.U
  val safeRecoveryStid = Mux(recoveryStidInRange, recoveryStid, 0.U)
  val recoveryOfferExact = io.recoveryPrepare.valid &&
    io.recoveryPrepare.bits.asUInt === recoveryRequest.asUInt
  val recoveryTargetsStid =
    (io.recoveryPrepare.valid ||
      recoveryScanState =/= OooRobRecoveryScanState.Idle) &&
    recoveryStidInRange && recoveryMember.group.valid && recoveryMember.bid.valid
  val recoveryCommitConflict = WireDefault(false.B)

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
  val groupLogicalRequiredProofs = Wire(Vec(p.instructionDecodeWidth,
    Vec(p.decodedUopWidth, UInt(OooNonFlushProof.Count.W))))
  val groupLogicalNonFlushNever = Wire(Vec(p.instructionDecodeWidth,
    UInt(p.decodedUopWidth.W)))
  for (groupIndex <- 0 until p.instructionDecodeWidth) {
    val group = io.publish.bits.reservation.transaction.groups(groupIndex)
    val decoded = io.publish.bits.reservation.transaction.decoded
    val logicalNever = Wire(Vec(p.decodedUopWidth, Bool()))
    for (uopIndex <- 0 until p.decodedUopWidth) {
      val member = group.logicalUopMask(uopIndex)
      val uop = decoded.uops(uopIndex)
      val exceptionRequired = member && uop.valid &&
        (uop.recipe.mayTrap || uop.recipe.mayTrapLate)
      val memoryRequired = member && uop.valid &&
        (uop.recipe.memoryRequestCount.orR ||
        uop.recipe.mayTrapLate || uop.recipe.sideEffectOwner === OooSideEffectOwner.Lsu.U)
      val controlRequired = member && uop.valid &&
        (uop.identity.boundary.start || uop.identity.boundary.stop ||
          uop.recipe.mayRedirect ||
          uop.recipe.requiresTargetValidation ||
          uop.recipe.sideEffectOwner === OooSideEffectOwner.Bctrl.U)
      val serializationRequired = member && uop.valid &&
        (uop.recipe.nonspeculative ||
        uop.recipe.dispatchClass === OooDispatchClass.Sys.U ||
        uop.recipe.dispatchClass === OooDispatchClass.Cmd.U ||
        uop.recipe.sideEffectOwner === OooSideEffectOwner.Commit.U ||
        uop.recipe.sideEffectOwner === OooSideEffectOwner.Ctu.U)
      groupLogicalRequiredProofs(groupIndex)(uopIndex) := Cat(
        serializationRequired, controlRequired, memoryRequired,
        exceptionRequired)
      logicalNever(uopIndex) := member &&
        (!decoded.uopMask(uopIndex) || !uop.valid || !uop.recipe.valid ||
        uop.recipe.disposition === OooOpcodeDisposition.Illegal.U ||
        uop.recipe.sideEffectOwner === OooSideEffectOwner.Illegal.U)
    }
    groupRequiredProofs(groupIndex) :=
      groupLogicalRequiredProofs(groupIndex).reduce(_ | _)
    groupLogicalNonFlushNever(groupIndex) := logicalNever.asUInt
    groupNonFlushNever(groupIndex) := group.preciseTrap ||
      !group.logicalUopMask.orR || logicalNever.asUInt.orR
  }

  val targetExact = Wire(Vec(p.instructionDecodeWidth, Bool()))
  val targetFree = Wire(Vec(p.instructionDecodeWidth, Bool()))
  for (groupIndex <- 0 until p.instructionDecodeWidth) {
    val group = io.publish.bits.reservation.transaction.groups(groupIndex)
    val binding = io.publish.bits.bindings(groupIndex)
    val decoded = io.publish.bits.reservation.transaction.decoded
    val active = groupIndex.U < publishGroupCount
    val targetRow = rowAt(safePublishStid, group.key.ridSlot)
    val expectedSlotSum =
      io.publish.bits.reservation.transaction.plan.firstVirtualGroup.ridSlot +& groupIndex.U
    val expectedWrap = expectedSlotSum >= p.robGroupsPerStid.U
    val memberMask =
      ((1.U((p.maxOrdinaryUopsPerGroup + 1).W) << group.physicalMemberCount) - 1.U)(
        p.maxOrdinaryUopsPerGroup - 1, 0)
    val templateContinuationMask = VecInit((0 until p.decodedUopWidth).map { uopIndex =>
      val uop = decoded.uops(uopIndex)
      group.logicalUopMask(uopIndex) && uop.valid &&
        uop.identity.templateValid && uop.identity.key.uopCount > 1.U &&
        (uop.identity.key.uopOrdinal +& 1.U) < uop.identity.key.uopCount
    }).asUInt
    val internalTemplateContinuation = group.logicalUopMask.orR &&
      (group.logicalUopMask & ~templateContinuationMask) === 0.U
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
          (group.architecturalParentCount.orR || internalTemplateContinuation) &&
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
  val recoveryBlocksPublish = recoveryTargetsStid &&
    publishStid === recoveryStid
  io.publish.ready := publicationExact && allTargetsFree &&
    !recoveryBlocksPublish
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
      val row = rowAt(publishStid, group.key.ridSlot)
      row.valid := true.B
      row.key := group.key
      row.transactionId := io.publish.bits.reservation.transaction.plan.transactionId
      row.publicationEpoch :=
        io.publish.bits.reservation.transaction.plan.epoch
      row.claimEpoch := io.publish.bits.reservation.claimEpoch
      row.brob := binding.brob
      row.brobAllocated := binding.brobAllocated
      row.brobImplicitCloseValid := binding.brobImplicitCloseValid
      row.brobImplicitClose := binding.brobImplicitClose
      row.pcBase := binding.pcBase
      row.pcBaseAllocated := binding.pcBaseAllocated
      row.pcImplicitCloseValid := binding.pcImplicitCloseValid
      row.pcImplicitClose := binding.pcImplicitClose
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
      row.logicalBoundaryStart := VecInit((0 until p.decodedUopWidth).map {
        uopIndex => group.logicalUopMask(uopIndex) &&
          io.publish.bits.reservation.transaction.decoded.uops(uopIndex)
            .identity.boundary.start
      }).asUInt
      row.logicalBoundaryStop := VecInit((0 until p.decodedUopWidth).map {
        uopIndex => group.logicalUopMask(uopIndex) &&
          io.publish.bits.reservation.transaction.decoded.uops(uopIndex)
            .identity.boundary.stop
      }).asUInt
      row.logicalReleasePcBase := VecInit((0 until p.decodedUopWidth).map {
        uopIndex =>
          val uop = io.publish.bits.reservation.transaction.decoded.uops(uopIndex)
          group.logicalUopMask(uopIndex) &&
            uop.identity.parents.zipWithIndex.map { case (parent, parentIndex) =>
              parentIndex.U < uop.identity.parentCount && parent.key.valid &&
                parent.traceOwner && parent.prediction.valid &&
                parent.prediction.taken
            }.reduce(_ || _)
      }).asUInt
      row.logicalPreciseTrap := VecInit((0 until p.decodedUopWidth).map {
        uopIndex => group.logicalUopMask(uopIndex) &&
          io.publish.bits.reservation.transaction.decoded.uops(uopIndex)
            .preciseTrap
      }).asUInt
      row.logicalNonFlushNever := groupLogicalNonFlushNever(groupIndex)
      for (uopIndex <- 0 until p.decodedUopWidth) {
        val decoded = io.publish.bits.reservation.transaction.decoded
        val uop = decoded.uops(uopIndex)
        val logicalMember = group.logicalUopMask(uopIndex)
        val parentCount = PopCount(uop.identity.parents.zipWithIndex.map {
          case (parent, parentIndex) =>
            parentIndex.U < uop.identity.parentCount && parent.key.valid &&
              parent.traceOwner
        })
        val pMapQRows = PopCount(uop.destinations.map { destination =>
          destination.valid &&
            destination.kind === linxcore.common.DestinationKind.Gpr
        })
        row.logicalMemberBase(uopIndex) := Mux(logicalMember,
          io.publish.bits.reservation.transaction.uopMemberBase(uopIndex), 0.U)
        row.logicalMemberCount(uopIndex) := Mux(logicalMember,
          uop.plannedChildCount, 0.U)
        row.logicalPMapQRows(uopIndex) := Mux(logicalMember, pMapQRows, 0.U)
        row.logicalArchitecturalParentCount(uopIndex) :=
          Mux(logicalMember, parentCount, 0.U)
        row.logicalNonFlushRequiredProofs(uopIndex) :=
          groupLogicalRequiredProofs(groupIndex)(uopIndex)
      }
    }
  }

  // Recovery is a retained two-pass walk over the physical bank slices.  The
  // first pass validates the complete old window and finds one exact pivot;
  // the second pass writes the ordered killed-row records into private plan
  // state.  No ROB row or pointer mutates before the common recovery fire.
  val recoveryScanOffsets = Wire(Vec(
    p.robRecoveryScanGroupsPerCycleEffective,
    UInt(p.nonFlushPrefixCountWidth.W)))
  val recoveryScanRows = Wire(Vec(
    p.robRecoveryScanGroupsPerCycleEffective,
    new OooRobPhysicalGroupRecord(p)))
  val recoveryScanWindowExact = Wire(Vec(
    p.robRecoveryScanGroupsPerCycleEffective, Bool()))
  val recoveryScanMatches = Wire(Vec(
    p.robRecoveryScanGroupsPerCycleEffective, Bool()))
  for (scanLane <- 0 until p.robRecoveryScanGroupsPerCycleEffective) {
    val offsetWide = recoveryScanCursor *
      p.robRecoveryScanGroupsPerCycleEffective.U + scanLane.U
    val offset = Wire(UInt(p.nonFlushPrefixCountWidth.W))
    offset := offsetWide
    val slotSum = recoverySnapshotHeadSlot +& offset
    val wraps = slotSum >= p.robGroupsPerStid.U
    val row = rowAt(safeRecoveryStid,
      slotSum(p.ridSlotWidth - 1, 0))
    val active = offset < recoverySnapshotOccupied
    val windowExact = !active || (
      row.valid && row.key.valid &&
        row.key.peId === recoverySnapshotHeadPeId &&
        row.key.stid === recoveryStid &&
        row.key.ridSlot === slotSum(p.ridSlotWidth - 1, 0) &&
        row.key.ridGeneration ===
          recoverySnapshotHeadGeneration + wraps.asUInt)
    recoveryScanOffsets(scanLane) := offset
    recoveryScanRows(scanLane) := row
    recoveryScanWindowExact(scanLane) := windowExact
    recoveryScanMatches(scanLane) := active && windowExact &&
      row.key.asUInt === recoveryMember.group.asUInt && row.brob.valid &&
      row.brob.bid.asUInt === recoveryMember.bid.asUInt &&
      row.brob.generation === recoveryMember.brobGeneration &&
      row.residentGeneration === recoveryMember.residentGeneration &&
      row.transactionId === recoveryRequest.rename.key.transactionId &&
      row.publicationEpoch === recoveryRequest.rename.key.epoch
  }
  val recoverySliceWindowExact = recoveryScanWindowExact.asUInt.andR
  val recoverySliceMatchCount = PopCount(recoveryScanMatches)
  val recoveryNextExactMatchCount = recoveryExactMatchCountReg +
    recoverySliceMatchCount
  val recoveryNextWindowExact = recoveryWindowExactReg &&
    recoverySliceWindowExact
  val recoverySliceHasMatch = recoveryScanMatches.asUInt.orR
  val recoverySlicePivotIndex =
    if (p.robRecoveryScanGroupsPerCycleEffective == 1) 0.U
    else PriorityEncoder(recoveryScanMatches.asUInt)
  val recoveryCandidatePivot = Mux(
    recoverySliceHasMatch,
    recoveryScanRows(recoverySlicePivotIndex),
    recoveryPivotReg)
  val recoveryCandidatePivotOffset = Mux(
    recoverySliceHasMatch,
    recoveryScanOffsets(recoverySlicePivotIndex),
    recoveryPivotOffsetReg)
  val recoveryScanLast = recoveryScanCursor ===
    (p.robRecoveryScanCycles - 1).U

  val recoveryPivot = recoveryCandidatePivot
  val recoveryPivotOffset = recoveryCandidatePivotOffset
  val recoveryTriggerEnd = recoveryMember.memberIndex +&
    recoveryRequest.triggerMemberCount
  val recoveryTriggerShape = Wire(Vec(p.decodedUopWidth, Bool()))
  for (uopIndex <- 0 until p.decodedUopWidth) {
    recoveryTriggerShape(uopIndex) := recoveryPivot.logicalUopMask(uopIndex) &&
      recoveryPivot.logicalMemberCount(uopIndex).orR &&
      recoveryPivot.logicalMemberBase(uopIndex) === recoveryMember.memberIndex &&
      recoveryPivot.logicalMemberCount(uopIndex) ===
        recoveryRequest.triggerMemberCount
  }
  val recoveryTriggerShapeCount = PopCount(recoveryTriggerShape)
  val recoveryTriggerShapeExact = recoveryTriggerShapeCount === 1.U &&
    recoveryRequest.triggerMemberCount.orR &&
    recoveryTriggerEnd <= recoveryPivot.physicalMemberCount
  val recoverySurvivingMemberCountWide = recoveryMember.memberIndex +&
    Mux(recoveryRequest.rename.killTrigger, 0.U,
      recoveryRequest.triggerMemberCount)
  val recoverySurvivingMemberCount =
    recoverySurvivingMemberCountWide(p.robMemberCountWidth - 1, 0)
  val recoverySurvivingPivotValid = recoverySurvivingMemberCount.orR
  val recoverySurvivingLogical = Wire(Vec(p.decodedUopWidth, Bool()))
  for (uopIndex <- 0 until p.decodedUopWidth) {
    val logicalEnd = recoveryPivot.logicalMemberBase(uopIndex) +&
      recoveryPivot.logicalMemberCount(uopIndex)
    recoverySurvivingLogical(uopIndex) :=
      recoveryPivot.logicalUopMask(uopIndex) &&
        recoveryPivot.logicalMemberCount(uopIndex).orR &&
        logicalEnd <= recoverySurvivingMemberCountWide
  }
  val recoverySurvivingPivot = Wire(new OooRobPhysicalGroupRecord(p))
  recoverySurvivingPivot := recoveryPivot
  recoverySurvivingPivot.valid := recoverySurvivingPivotValid
  recoverySurvivingPivot.logicalUopMask := recoverySurvivingLogical.asUInt
  recoverySurvivingPivot.physicalMemberCount := recoverySurvivingMemberCount
  val recoverySurvivingMemberMask =
    ((1.U((p.maxOrdinaryUopsPerGroup + 1).W) <<
      recoverySurvivingMemberCount) - 1.U)(
      p.maxOrdinaryUopsPerGroup - 1, 0)
  recoverySurvivingPivot.completedMembers :=
    recoveryPivot.completedMembers & recoverySurvivingMemberMask
  recoverySurvivingPivot.pMapQRows := (0 until p.decodedUopWidth).map {
    uopIndex => Mux(recoverySurvivingLogical(uopIndex),
      recoveryPivot.logicalPMapQRows(uopIndex), 0.U)
  }.reduce(_ +& _)
  recoverySurvivingPivot.architecturalParentCount :=
    (0 until p.decodedUopWidth).map { uopIndex =>
      Mux(recoverySurvivingLogical(uopIndex),
        recoveryPivot.logicalArchitecturalParentCount(uopIndex), 0.U)
    }.reduce(_ +& _)
  recoverySurvivingPivot.boundaryStart :=
    (recoveryPivot.logicalBoundaryStart &
      recoverySurvivingLogical.asUInt).orR
  recoverySurvivingPivot.boundaryStop :=
    (recoveryPivot.logicalBoundaryStop &
      recoverySurvivingLogical.asUInt).orR
  recoverySurvivingPivot.releasePcBase :=
    (recoveryPivot.logicalReleasePcBase &
      recoverySurvivingLogical.asUInt).orR
  recoverySurvivingPivot.preciseTrap :=
    (recoveryPivot.logicalPreciseTrap &
      recoverySurvivingLogical.asUInt).orR
  recoverySurvivingPivot.nonFlushRequiredProofs :=
    (0 until p.decodedUopWidth).map { uopIndex =>
      Mux(recoverySurvivingLogical(uopIndex),
        recoveryPivot.logicalNonFlushRequiredProofs(uopIndex), 0.U)
    }.reduce(_ | _)
  recoverySurvivingPivot.nonFlushObservedProofs := 0.U
  recoverySurvivingPivot.nonFlushNever :=
    (recoveryPivot.logicalNonFlushNever &
      recoverySurvivingLogical.asUInt).orR
  recoverySurvivingPivot.logicalBoundaryStart :=
    recoveryPivot.logicalBoundaryStart & recoverySurvivingLogical.asUInt
  recoverySurvivingPivot.logicalBoundaryStop :=
    recoveryPivot.logicalBoundaryStop & recoverySurvivingLogical.asUInt
  recoverySurvivingPivot.logicalReleasePcBase :=
    recoveryPivot.logicalReleasePcBase & recoverySurvivingLogical.asUInt
  recoverySurvivingPivot.logicalPreciseTrap :=
    recoveryPivot.logicalPreciseTrap & recoverySurvivingLogical.asUInt
  recoverySurvivingPivot.logicalNonFlushNever :=
    recoveryPivot.logicalNonFlushNever & recoverySurvivingLogical.asUInt
  for (uopIndex <- 0 until p.decodedUopWidth) {
    when(!recoverySurvivingLogical(uopIndex)) {
      recoverySurvivingPivot.logicalMemberBase(uopIndex) := 0.U
      recoverySurvivingPivot.logicalMemberCount(uopIndex) := 0.U
      recoverySurvivingPivot.logicalPMapQRows(uopIndex) := 0.U
      recoverySurvivingPivot.logicalArchitecturalParentCount(uopIndex) := 0.U
      recoverySurvivingPivot.logicalNonFlushRequiredProofs(uopIndex) := 0.U
    }
  }
  val recoveryNewOccupied = recoveryPivotOffset +
    recoverySurvivingPivotValid.asUInt
  val recoveryKilledGroupCount = recoverySnapshotOccupied -
    recoveryNewOccupied
  val recoveryFirstKilledSlotSum = recoverySnapshotHeadSlot +&
    recoveryNewOccupied
  val recoveryFirstKilledWrap =
    recoveryFirstKilledSlotSum >= p.robGroupsPerStid.U
  val recoveryOldTailSlotSum = recoverySnapshotHeadSlot +&
    recoverySnapshotOccupied
  val recoveryOldTailWrap = recoveryOldTailSlotSum >= p.robGroupsPerStid.U

  val recoveryPlanBase = Wire(new OooRobRecoveryPlan(p))
  recoveryPlanBase := 0.U.asTypeOf(recoveryPlanBase)
  recoveryPlanBase.valid := false.B
  recoveryPlanBase.request := recoveryRequest
  recoveryPlanBase.oldHead.valid := recoverySnapshotOccupied.orR
  recoveryPlanBase.oldHead.peId := recoverySnapshotHeadPeId
  recoveryPlanBase.oldHead.stid := recoveryStid
  recoveryPlanBase.oldHead.ridSlot := recoverySnapshotHeadSlot
  recoveryPlanBase.oldHead.ridGeneration := recoverySnapshotHeadGeneration
  recoveryPlanBase.oldOccupied := recoverySnapshotOccupied
  recoveryPlanBase.pivot := recoveryPivot
  recoveryPlanBase.pivotOffset := recoveryPivotOffset
  recoveryPlanBase.survivingPivotValid := recoverySurvivingPivotValid
  recoveryPlanBase.survivingPivot := recoverySurvivingPivot
  recoveryPlanBase.newOccupied := recoveryNewOccupied
  recoveryPlanBase.survivingTailValid := recoveryNewOccupied.orR
  recoveryPlanBase.survivingTail := Mux(
    recoverySurvivingPivotValid,
    recoverySurvivingPivot,
    0.U.asTypeOf(new OooRobPhysicalGroupRecord(p)))
  recoveryPlanBase.firstKilledGroup.valid := recoveryKilledGroupCount.orR
  recoveryPlanBase.firstKilledGroup.peId := recoverySnapshotHeadPeId
  recoveryPlanBase.firstKilledGroup.stid := recoveryStid
  recoveryPlanBase.firstKilledGroup.ridSlot :=
    recoveryFirstKilledSlotSum(p.ridSlotWidth - 1, 0)
  recoveryPlanBase.firstKilledGroup.ridGeneration :=
    recoverySnapshotHeadGeneration + recoveryFirstKilledWrap.asUInt
  recoveryPlanBase.killedGroupCount := recoveryKilledGroupCount
  recoveryPlanBase.killedGroupMask :=
    ((1.U((p.robGroupsPerStid + 1).W) << recoveryKilledGroupCount) - 1.U)(
      p.robGroupsPerStid - 1, 0)
  recoveryPlanBase.oldTail.valid := true.B
  recoveryPlanBase.oldTail.peId := recoverySnapshotHeadPeId
  recoveryPlanBase.oldTail.stid := recoveryStid
  recoveryPlanBase.oldTail.ridSlot :=
    recoveryOldTailSlotSum(p.ridSlotWidth - 1, 0)
  recoveryPlanBase.oldTail.ridGeneration :=
    recoverySnapshotHeadGeneration + recoveryOldTailWrap.asUInt
  recoveryPlanBase.newTail.valid := true.B
  recoveryPlanBase.newTail.peId := recoverySnapshotHeadPeId
  recoveryPlanBase.newTail.stid := recoveryStid
  recoveryPlanBase.newTail.ridSlot :=
    recoveryFirstKilledSlotSum(p.ridSlotWidth - 1, 0)
  recoveryPlanBase.newTail.ridGeneration :=
    recoverySnapshotHeadGeneration + recoveryFirstKilledWrap.asUInt

  val recoveryCaptureWellFormed = io.recoveryPrepare.valid &&
    recoveryStidInRange && recoveryMember.group.valid &&
    recoveryMember.bid.valid
  val recoveryCaptureMalformed = io.recoveryPrepare.valid &&
    !recoveryCaptureWellFormed
  val recoveryOfferChanged =
    recoveryScanState =/= OooRobRecoveryScanState.Idle &&
      recoveryScanState =/= OooRobRecoveryScanState.Rejected &&
      io.recoveryPrepare.valid && !recoveryOfferExact
  val recoveryFindRejected =
    recoveryScanState === OooRobRecoveryScanState.FindPivot &&
      recoveryScanLast && (!recoveryNextWindowExact ||
        recoveryNextExactMatchCount =/= 1.U ||
        !recoveryTriggerShapeExact)
  val recoveryBuildRejected =
    recoveryScanState === OooRobRecoveryScanState.BuildPlan &&
      recoveryScanLast && !recoveryNextWindowExact

  io.recoveryPrepared := retainedRecoveryPlan
  io.recoveryPrepareReady :=
    recoveryScanState === OooRobRecoveryScanState.Prepared &&
      recoveryOfferExact && retainedRecoveryPlan.valid &&
      !recoveryCommitConflict
  io.recoveryPrepared.valid := io.recoveryPrepareReady
  io.recoveryRejected.valid := recoveryCaptureMalformed ||
    recoveryOfferChanged || recoveryFindRejected || recoveryBuildRejected ||
    (recoveryScanState === OooRobRecoveryScanState.Rejected &&
      io.recoveryPrepare.valid)
  io.recoveryRejected.bits.requested := recoveryRequest
  io.recoveryRejected.bits.occupied := recoverySnapshotOccupied
  io.recoveryRejected.bits.exactMatchCount := Mux(
    recoveryScanState === OooRobRecoveryScanState.FindPivot,
    recoveryNextExactMatchCount,
    recoveryExactMatchCountReg)
  io.recoveryRejected.bits.triggerShapeMatch := recoveryTriggerShapeExact

  when(recoveryScanState === OooRobRecoveryScanState.Idle &&
      recoveryCaptureWellFormed && !recoveryCommitConflict) {
    retainedRecoveryRequest := io.recoveryPrepare.bits
    retainedRecoveryPlan := 0.U.asTypeOf(retainedRecoveryPlan)
    recoverySnapshotOccupied := occupied(safeRecoveryStid)
    recoverySnapshotHeadSlot := headSlot(safeRecoveryStid)
    recoverySnapshotHeadGeneration := headGeneration(safeRecoveryStid)
    recoverySnapshotHeadPeId := headPeId(safeRecoveryStid)
    recoveryExactMatchCountReg := 0.U
    recoveryWindowExactReg := true.B
    recoveryPivotReg := 0.U.asTypeOf(recoveryPivotReg)
    recoveryPivotOffsetReg := 0.U
    recoveryScanCursor := 0.U
    recoveryScanState := OooRobRecoveryScanState.FindPivot
  }.elsewhen(recoveryScanState =/= OooRobRecoveryScanState.Idle &&
      !io.recoveryPrepare.valid) {
    // Prepare-valid withdrawal is the owner-abort signal.  Both passes have
    // touched private metadata only, so cancellation needs no physical undo.
    recoveryScanState := OooRobRecoveryScanState.Idle
  }.elsewhen(recoveryOfferChanged) {
    recoveryScanState := OooRobRecoveryScanState.Rejected
  }.elsewhen(recoveryScanState === OooRobRecoveryScanState.FindPivot) {
    recoveryExactMatchCountReg := recoveryNextExactMatchCount
    recoveryWindowExactReg := recoveryNextWindowExact
    when(recoverySliceHasMatch) {
      recoveryPivotReg := recoveryCandidatePivot
      recoveryPivotOffsetReg := recoveryCandidatePivotOffset
    }
    when(recoveryScanLast) {
      when(recoveryNextWindowExact &&
          recoveryNextExactMatchCount === 1.U &&
          recoveryTriggerShapeExact) {
        retainedRecoveryPlan := recoveryPlanBase
        recoveryWindowExactReg := true.B
        recoveryScanCursor := 0.U
        recoveryScanState := OooRobRecoveryScanState.BuildPlan
      }.otherwise {
        recoveryScanState := OooRobRecoveryScanState.Rejected
      }
    }.otherwise {
      recoveryScanCursor := recoveryScanCursor + 1.U
    }
  }.elsewhen(recoveryScanState === OooRobRecoveryScanState.BuildPlan) {
    recoveryWindowExactReg := recoveryNextWindowExact
    for (scanLane <- 0 until p.robRecoveryScanGroupsPerCycleEffective) {
      val offset = recoveryScanOffsets(scanLane)
      val killed = offset >= retainedRecoveryPlan.newOccupied &&
        offset < recoverySnapshotOccupied
      val killedIndex = offset - retainedRecoveryPlan.newOccupied
      when(killed && recoveryScanWindowExact(scanLane)) {
        retainedRecoveryPlan.killedGroups(
          killedIndex(p.ridSlotWidth - 1, 0)) :=
          recoveryScanRows(scanLane)
      }
      val survivingTail = retainedRecoveryPlan.survivingTailValid &&
        !retainedRecoveryPlan.survivingPivotValid &&
        offset === retainedRecoveryPlan.newOccupied - 1.U
      when(survivingTail && recoveryScanWindowExact(scanLane)) {
        retainedRecoveryPlan.survivingTail := recoveryScanRows(scanLane)
      }
    }
    when(recoveryScanLast) {
      when(recoveryNextWindowExact) {
        retainedRecoveryPlan.valid := true.B
        recoveryScanState := OooRobRecoveryScanState.Prepared
      }.otherwise {
        recoveryScanState := OooRobRecoveryScanState.Rejected
      }
    }.otherwise {
      recoveryScanCursor := recoveryScanCursor + 1.U
    }
  }.elsewhen(recoveryScanState === OooRobRecoveryScanState.Prepared &&
      io.recoveryFire) {
    recoveryScanState := OooRobRecoveryScanState.Idle
  }

  val completionStid = io.completion.bits.key.group.stid
  val completionStidInRange = completionStid < p.stidCount.U
  val safeCompletionStid = Mux(completionStidInRange, completionStid, 0.U)
  val completionSlot = io.completion.bits.key.group.ridSlot
  val liveCompletionRow = rowAt(safeCompletionStid, completionSlot)
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

  val recoveryBlocksCompletion = recoveryTargetsStid &&
    completionStid === recoveryStid
  io.completion.ready := !recoveryBlocksCompletion
  val completionFire = io.completion.valid && io.completion.ready
  when(completionFire && completionExact) {
    rowAt(completionStid, completionSlot).completedMembers :=
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
  val liveEvidenceRow = rowAt(safeEvidenceStid, evidenceSlot)
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

  val recoveryBlocksEvidence = recoveryTargetsStid &&
    evidenceStid === recoveryStid
  io.nonFlushEvidence.ready := !recoveryBlocksEvidence
  val evidenceFire = io.nonFlushEvidence.valid && io.nonFlushEvidence.ready
  when(evidenceFire && evidenceExact) {
    rowAt(evidenceStid, evidenceSlot).nonFlushObservedProofs :=
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
      val row = rowAt(stid.U, slotSum(p.ridSlotWidth - 1, 0))
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
      val row = rowAt(stid.U, slotSum(p.ridSlotWidth - 1, 0))
      val expectedGeneration = headGeneration(stid) + wraps.asUInt
      val exactHead = row.key.valid && row.key.peId === headPeId(stid) &&
        row.key.stid === stid.U &&
        row.key.ridSlot === slotSum(p.ridSlotWidth - 1, 0) &&
        row.key.ridGeneration === expectedGeneration
      val canExtend = prefix(offset) === offset.U &&
        offset.U < occupied(stid) && exactHead && groupComplete(row)
      prefix(offset + 1) := Mux(canExtend, (offset + 1).U, prefix(offset))
    }
    commitCountByStid(stid) := Mux(
      recoveryTargetsStid && recoveryStid === stid.U,
      0.U,
      prefix(p.retireGroupWidth))
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
          rowAt(selectedStid, slotSum(p.ridSlotWidth - 1, 0))
      }
    }
  }

  io.commit.valid := commitPending
  io.commit.bits := commitRow
  val commitFire = io.commit.valid && io.commit.ready
  recoveryCommitConflict := commitPending &&
    commitRow.release.firstGroup.stid === recoveryStid
  when(commitFire) {
    val stid = commitRow.release.firstGroup.stid
    val count = commitRow.release.groupCount
    for (offset <- 0 until p.retireGroupWidth) {
      val slotSum = commitRow.release.firstGroup.ridSlot +& offset.U
      when(offset.U < count) {
        rowAt(stid, slotSum(p.ridSlotWidth - 1, 0)) :=
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

  val recoveryApply = io.recoveryFire && io.recoveryPrepareReady
  when(io.recoveryFire) {
    assert(io.recoveryPrepare.valid && io.recoveryPrepareReady &&
      io.recoveryPrepared.valid,
      "ROB recovery fire requires the same exact prepared suffix plan")
  }
  when(recoveryApply) {
    for (offset <- 0 until p.robGroupsPerStid) {
      val slotSum = retainedRecoveryPlan.oldHead.ridSlot +& offset.U
      when(offset.U >= retainedRecoveryPlan.newOccupied &&
        offset.U < retainedRecoveryPlan.oldOccupied) {
        rowAt(safeRecoveryStid, slotSum(p.ridSlotWidth - 1, 0)) :=
          0.U.asTypeOf(new OooRobPhysicalGroupRecord(p))
      }
    }
    when(retainedRecoveryPlan.survivingPivotValid &&
      retainedRecoveryPlan.survivingPivot.physicalMemberCount <
        retainedRecoveryPlan.pivot.physicalMemberCount) {
      rowAt(safeRecoveryStid, retainedRecoveryPlan.pivot.key.ridSlot) :=
        retainedRecoveryPlan.survivingPivot
    }
    occupied(safeRecoveryStid) := retainedRecoveryPlan.newOccupied
    nonFlushAuthorized(safeRecoveryStid) := 0.U
    nonFlushEpoch(safeRecoveryStid) :=
      nonFlushEpoch(safeRecoveryStid) + 1.U
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
