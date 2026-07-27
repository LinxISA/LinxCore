package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, PopCount, PriorityEncoder, Valid, is, switch}
import linxcore.common.DestinationKind

object OooTURetireState extends ChiselEnum {
  val Idle, LoadSource, PreReleaseT, PreReleaseU, MarkDestination,
    PostRelease, CleanRelations, BlockCommit, AdvanceSource,
    AwaitDeallocation = Value
}

object OooTURetireRecoveryState extends ChiselEnum {
  val Idle, Scan, Authorize, Emit, AwaitOwners = Value
}

class OooTURelationEntry(val p: OooParams) extends Bundle {
  val valid = Bool()
  val block = new BrobPointer(p)
  val group = new RobGroupKey(p)
  val member = new RobMemberKey(p)
  val sequence = new OooLocalSeq(p)
}

class OooProductionTURetireIO(val p: OooParams = OooParams()) extends Bundle {
  val publicationPrepare = Flipped(Valid(new OooTURetirePublication(p)))
  val publicationReady = Output(Bool())
  val publishFire = Input(Bool())

  val commitPrepare = Flipped(Valid(new OooRobCommitBatch(p)))
  val commitStartReady = Output(Bool())
  val commitReady = Output(Bool())
  val commitPrepared = Output(new OooTURetireCommitPrepared(p))
  val commitFire = Input(Bool())
  val commitBusy = Output(Bool())
  val commitStid = Output(UInt(p.stidWidth.W))

  val retireCommand = Decoupled(new OooTURetireCommand(p))
  val blockCommit = Decoupled(new OooTULocalBlockCommit(p))

  val sourceQueueUsed = Output(Vec(p.stidCount,
    UInt(p.tuRetireSourceCountWidth.W)))
  val tRelationUsed = Output(Vec(p.stidCount,
    UInt(p.tuRelationCountWidth.W)))
  val uRelationUsed = Output(Vec(p.stidCount,
    UInt(p.tuRelationCountWidth.W)))
  val commitRejected = Valid(new OooTURetireCommitReject(p))

  val recoveryRequest = Flipped(Decoupled(
    new OooRenameRecoveryRequest(p)))
  val recoveryAuthorize = Decoupled(new OooRenameRecoveryRequest(p))
  val recoverySource = Decoupled(new OooRenameRecoverySource(p))
  val recoveryFinish = Input(Bool())
  val recoveryBusy = Output(Bool())
  val recoveryStid = Output(UInt(p.stidWidth.W))
  val recoverySourcesDone = Output(Bool())
  val recoveryRejected = Valid(new OooRenameRecoveryReject(p))
}

/** Production owner for T/U relation retirement and local block release.
  *
  * Every published logical uop, including a row with no local destination, is
  * retained until its exact grouped-ROB commit reaches the source head.  The
  * owner serializes the model order:
  *
  *   pre-release T -> pre-release U -> destination mark -> optional pressure
  *   release -> exact-block relation cleanup -> local block commit.
  *
  * This module owns only retire-source and relation-CMAP state.  The T/U MapQ
  * and physical tags remain owned by [[OooProductionTURename]], which accepts
  * the generated exact mark/deallocation and post-clean block commands.
  */
class OooProductionTURetire(val p: OooParams = OooParams()) extends Module {
  val io = IO(new OooProductionTURetireIO(p))

  private val destinationCursorWidth = math.max(1,
    chisel3.util.log2Ceil(p.maxDestinationOperands + 1))

  private def zeroSource: OooTURetireSource =
    0.U.asTypeOf(new OooTURetireSource(p))

  private def zeroRelation: OooTURelationEntry =
    0.U.asTypeOf(new OooTURelationEntry(p))

  private def addSourcePtr(ptr: UInt, amount: UInt): UInt = {
    val sum = ptr +& amount
    sum(p.tuRetireSourceIndexWidth - 1, 0)
  }

  private def subSourcePtr(ptr: UInt, amount: UInt): UInt = {
    val difference = ptr - amount
    difference(p.tuRetireSourceIndexWidth - 1, 0)
  }

  private def addRelationPtr(ptr: UInt, amount: UInt): UInt = {
    val sum = ptr +& amount
    sum(p.tuRelationIndexWidth - 1, 0)
  }

  private def decRelationPtr(ptr: UInt): UInt =
    Mux(ptr === 0.U,
      (p.tuRelationDepthPerStid - 1).U(p.tuRelationIndexWidth.W),
      ptr - 1.U)

  private def sameBlock(lhs: BrobPointer, rhs: BrobPointer): Bool =
    lhs.valid && rhs.valid && lhs.bid.valid && rhs.bid.valid &&
      lhs.bid.value === rhs.bid.value && lhs.generation === rhs.generation

  private def sameGroup(lhs: RobGroupKey, rhs: RobGroupKey): Bool =
    lhs.asUInt === rhs.asUInt

  val sourceQueue = RegInit(VecInit(Seq.fill(p.stidCount)(
    VecInit(Seq.fill(p.tuRetireSourceDepthPerStid)(zeroSource)))))
  val sourceHead = RegInit(VecInit(Seq.fill(p.stidCount)(
    0.U(p.tuRetireSourceIndexWidth.W))))
  val sourceTail = RegInit(VecInit(Seq.fill(p.stidCount)(
    0.U(p.tuRetireSourceIndexWidth.W))))
  val sourceCount = RegInit(VecInit(Seq.fill(p.stidCount)(
    0.U(p.tuRetireSourceCountWidth.W))))

  val tRelations = RegInit(VecInit(Seq.fill(p.stidCount)(
    VecInit(Seq.fill(p.tuRelationDepthPerStid)(zeroRelation)))))
  val uRelations = RegInit(VecInit(Seq.fill(p.stidCount)(
    VecInit(Seq.fill(p.tuRelationDepthPerStid)(zeroRelation)))))
  val tRelationHead = RegInit(VecInit(Seq.fill(p.stidCount)(
    0.U(p.tuRelationIndexWidth.W))))
  val uRelationHead = RegInit(VecInit(Seq.fill(p.stidCount)(
    0.U(p.tuRelationIndexWidth.W))))
  val tRelationTail = RegInit(VecInit(Seq.fill(p.stidCount)(
    0.U(p.tuRelationIndexWidth.W))))
  val uRelationTail = RegInit(VecInit(Seq.fill(p.stidCount)(
    0.U(p.tuRelationIndexWidth.W))))
  val tRelationCount = RegInit(VecInit(Seq.fill(p.stidCount)(
    0.U(p.tuRelationCountWidth.W))))
  val uRelationCount = RegInit(VecInit(Seq.fill(p.stidCount)(
    0.U(p.tuRelationCountWidth.W))))

  val recoveryState = RegInit(OooTURetireRecoveryState.Idle)
  val recoveryRequest = RegInit(
    0.U.asTypeOf(new OooRenameRecoveryRequest(p)))
  val recoveryScanOffset = RegInit(
    0.U(p.tuRetireSourceCountWidth.W))
  val recoveryScanRemaining = RegInit(
    0.U(p.tuRetireSourceCountWidth.W))
  val recoveryMatchCount = RegInit(
    0.U(p.tuRetireSourceCountWidth.W))
  val recoveryMatchedOffset = RegInit(
    0.U(p.tuRetireSourceCountWidth.W))
  val recoveryEmitRemaining = RegInit(
    0.U(p.tuRetireSourceCountWidth.W))
  val recoveryRejectValid = RegInit(false.B)
  val recoveryRejectRequest = RegInit(
    0.U.asTypeOf(new OooRenameRecoveryRequest(p)))
  val recoveryRejectHead = RegInit(
    0.U(p.tuRetireSourceIndexWidth.W))
  val recoveryRejectTail = RegInit(
    0.U(p.tuRetireSourceIndexWidth.W))
  val recoveryRejectCount = RegInit(
    0.U(p.tuRetireSourceCountWidth.W))

  val publication = io.publicationPrepare.bits
  val publicationStid = publication.stid
  val publicationStidInRange = publicationStid < p.stidCount.U
  val safePublicationStid = Mux(publicationStidInRange, publicationStid, 0.U)
  val publicationMaskExact = (0 until p.decodedUopWidth).map { uopIndex =>
    publication.sources(uopIndex).valid === publication.uopMask(uopIndex)
  }.reduce(_ && _)
  val publicationRowsExact = (0 until p.decodedUopWidth).map { uopIndex =>
    val active = publication.uopMask(uopIndex)
    val source = publication.sources(uopIndex)
    val destinationShapeExact = (0 until p.maxDestinationOperands).map {
      destinationIndex =>
        val destination = source.destinations(destinationIndex)
        !destination.valid || (destination.sequence.valid &&
          (destination.kind === DestinationKind.T ||
            destination.kind === DestinationKind.U) &&
          destination.stid === publicationStid &&
          destination.epoch === publication.epoch)
    }.reduce(_ && _)
    !active || (source.valid && source.transactionId ===
      publication.transactionId && source.epoch === publication.epoch &&
      source.uopIndex === uopIndex.U &&
      source.pDestinationCount <= p.maxDestinationOperands.U &&
      source.member.group.valid &&
      source.member.group.peId === publication.peId &&
      source.member.group.stid === publicationStid &&
      source.member.bid.valid && source.tSeqBefore.valid &&
      source.uSeqBefore.valid && destinationShapeExact &&
      (!source.closeBeforeValid || (source.closeBefore.valid &&
        source.closeBefore.bid.valid &&
        !(source.closeBefore.bid.value === source.member.bid.value &&
          source.closeBefore.generation ===
            source.member.brobGeneration))))
  }.reduce(_ && _)
  val publicationSourceCount = PopCount(publication.uopMask)
  val publicationFree = p.tuRetireSourceDepthPerStid.U -
    sourceCount(safePublicationStid)
  val recoveryActiveStid = recoveryRequest.key.member.group.stid
  val publicationBlockedByRecovery =
    recoveryState =/= OooTURetireRecoveryState.Idle &&
      publicationStid === recoveryActiveStid
  io.publicationReady := publicationStidInRange && publicationMaskExact &&
    publicationRowsExact && publicationSourceCount <= publicationFree &&
    !publicationBlockedByRecovery
  val publicationFire = io.publishFire && io.publicationPrepare.valid &&
    io.publicationReady
  when(io.publishFire) {
    assert(io.publicationPrepare.valid && io.publicationReady,
      "T/U retire publication requires an exact source sidecar")
  }

  val state = RegInit(OooTURetireState.Idle)
  val commitBatch = RegInit(0.U.asTypeOf(new OooRobCommitBatch(p)))
  val commitSourcesRemaining = RegInit(
    0.U(p.commitTURetireSourceCountWidth.W))
  val commitSourcesTotal = RegInit(
    0.U(p.commitTURetireSourceCountWidth.W))
  val currentSource = RegInit(zeroSource)
  val processingCloseBefore = RegInit(false.B)
  val destinationCursor = RegInit(0.U(destinationCursorWidth.W))
  val postReleaseKind = RegInit(DestinationKind.None)

  recoveryRejectValid := false.B
  val incomingRecovery = io.recoveryRequest.bits
  val incomingRecoveryStid = incomingRecovery.key.member.group.stid
  val incomingRecoveryStidInRange = incomingRecoveryStid < p.stidCount.U
  val safeIncomingRecoveryStid = Mux(incomingRecoveryStidInRange,
    incomingRecoveryStid, 0.U)
  val incomingRecoveryShapeExact = incomingRecoveryStidInRange &&
    incomingRecovery.key.member.group.valid &&
    incomingRecovery.key.member.bid.valid &&
    sourceCount(safeIncomingRecoveryStid).orR
  val recoveryConflictsPublication = publicationFire &&
    publicationStid === incomingRecoveryStid
  io.recoveryRequest.ready :=
    recoveryState === OooTURetireRecoveryState.Idle &&
      state === OooTURetireState.Idle && !io.commitPrepare.valid &&
      !recoveryConflictsPublication
  val recoveryRequestFire = io.recoveryRequest.fire
  when(recoveryRequestFire) {
    when(incomingRecoveryShapeExact) {
      recoveryRequest := incomingRecovery
      recoveryScanOffset := 0.U
      recoveryScanRemaining := sourceCount(safeIncomingRecoveryStid)
      recoveryMatchCount := 0.U
      recoveryMatchedOffset := 0.U
      recoveryState := OooTURetireRecoveryState.Scan
    }.otherwise {
      recoveryRejectValid := true.B
      recoveryRejectRequest := incomingRecovery
      recoveryRejectHead := sourceHead(safeIncomingRecoveryStid)
      recoveryRejectTail := sourceTail(safeIncomingRecoveryStid)
      recoveryRejectCount := sourceCount(safeIncomingRecoveryStid)
    }
  }

  val recoveryStid = recoveryRequest.key.member.group.stid
  val recoveryScanIndex = subSourcePtr(sourceTail(recoveryStid),
    recoveryScanOffset + 1.U)
  val recoveryScanSource = sourceQueue(recoveryStid)(recoveryScanIndex)
  val recoveryScanMatch = recoveryScanSource.valid &&
    recoveryScanSource.member.asUInt === recoveryRequest.key.member.asUInt &&
    recoveryScanSource.transactionId === recoveryRequest.key.transactionId &&
    recoveryScanSource.epoch === recoveryRequest.key.epoch
  val recoveryFinalMatchCount = recoveryMatchCount +
    recoveryScanMatch.asUInt
  val recoveryFinalMatchedOffset = Mux(recoveryScanMatch &&
    !recoveryMatchCount.orR, recoveryScanOffset, recoveryMatchedOffset)
  val recoveryKillCount = recoveryFinalMatchedOffset +
    recoveryRequest.killTrigger.asUInt

  val recoveryEmitIndex = subSourcePtr(sourceTail(recoveryStid), 1.U)
  val recoveryEmitSource = sourceQueue(recoveryStid)(recoveryEmitIndex)
  io.recoveryAuthorize.valid :=
    recoveryState === OooTURetireRecoveryState.Authorize
  io.recoveryAuthorize.bits := recoveryRequest
  io.recoverySource.valid :=
    recoveryState === OooTURetireRecoveryState.Emit
  io.recoverySource.bits := 0.U.asTypeOf(io.recoverySource.bits)
  io.recoverySource.bits.request := recoveryRequest
  io.recoverySource.bits.source := recoveryEmitSource
  io.recoverySource.bits.last := recoveryEmitRemaining === 1.U
  val recoverySourceFire = io.recoverySource.fire
  val recoveryAuthorizeFire = io.recoveryAuthorize.fire

  switch(recoveryState) {
    is(OooTURetireRecoveryState.Scan) {
      when(recoveryScanMatch) {
        recoveryMatchCount := recoveryMatchCount + 1.U
        when(!recoveryMatchCount.orR) {
          recoveryMatchedOffset := recoveryScanOffset
        }
      }
      when(recoveryScanRemaining === 1.U) {
        when(recoveryFinalMatchCount === 1.U) {
          recoveryEmitRemaining := recoveryKillCount
          recoveryState := OooTURetireRecoveryState.Authorize
        }.otherwise {
          recoveryRejectValid := true.B
          recoveryRejectRequest := recoveryRequest
          recoveryRejectHead := sourceHead(recoveryStid)
          recoveryRejectTail := sourceTail(recoveryStid)
          recoveryRejectCount := sourceCount(recoveryStid)
          recoveryState := OooTURetireRecoveryState.Idle
        }
        recoveryScanRemaining := 0.U
      }.otherwise {
        recoveryScanOffset := recoveryScanOffset + 1.U
        recoveryScanRemaining := recoveryScanRemaining - 1.U
      }
    }
    is(OooTURetireRecoveryState.Authorize) {
      when(recoveryAuthorizeFire) {
        recoveryState := Mux(recoveryEmitRemaining.orR,
          OooTURetireRecoveryState.Emit,
          OooTURetireRecoveryState.AwaitOwners)
      }
    }
    is(OooTURetireRecoveryState.Emit) {
      when(recoverySourceFire) {
        assert(recoveryEmitSource.valid,
          "rename recovery suffix must contain only live source rows")
        assert(!(publicationFire && publicationStid === recoveryStid),
          "same-STID publication must wait for rename recovery suffix drain")
        sourceQueue(recoveryStid)(recoveryEmitIndex) := zeroSource
        sourceTail(recoveryStid) := subSourcePtr(
          sourceTail(recoveryStid), 1.U)
        sourceCount(recoveryStid) := sourceCount(recoveryStid) - 1.U
        recoveryEmitRemaining := recoveryEmitRemaining - 1.U
        when(recoveryEmitRemaining === 1.U) {
          recoveryState := OooTURetireRecoveryState.AwaitOwners
        }
      }
    }
    is(OooTURetireRecoveryState.AwaitOwners) {
      when(io.recoveryFinish) {
        recoveryState := OooTURetireRecoveryState.Idle
      }
    }
  }

  io.recoveryBusy :=
    recoveryState =/= OooTURetireRecoveryState.Idle
  io.recoveryStid := Mux(io.recoveryBusy, recoveryStid, 0.U)
  io.recoverySourcesDone :=
    recoveryState === OooTURetireRecoveryState.AwaitOwners
  io.recoveryRejected.valid := recoveryRejectValid
  io.recoveryRejected.bits.requested := recoveryRejectRequest
  io.recoveryRejected.bits.sourceHead := recoveryRejectHead
  io.recoveryRejected.bits.sourceTail := recoveryRejectTail
  io.recoveryRejected.bits.sourceCount := recoveryRejectCount

  when(io.recoveryFinish) {
    assert(io.recoverySourcesDone,
      "rename recovery may finish only after the exact source suffix drains")
  }

  val incomingBatch = io.commitPrepare.bits
  val incomingGroupCount = incomingBatch.release.groupCount
  val incomingStid = incomingBatch.release.firstGroup.stid
  val incomingStidInRange = incomingStid < p.stidCount.U
  val safeIncomingStid = Mux(incomingStidInRange, incomingStid, 0.U)
  val incomingGroupCountInRange = incomingGroupCount.orR &&
    incomingGroupCount <= p.retireGroupWidth.U
  val incomingGroupsExact = (0 until p.retireGroupWidth).map { groupIndex =>
    val active = groupIndex.U < incomingGroupCount
    val record = incomingBatch.groups(groupIndex)
    val slotSum = incomingBatch.release.firstGroup.ridSlot +& groupIndex.U
    val wraps = slotSum >= p.robGroupsPerStid.U
    val completedMask =
      ((1.U((p.maxOrdinaryUopsPerGroup + 1).W) <<
        record.physicalMemberCount) - 1.U)(
        p.maxOrdinaryUopsPerGroup - 1, 0)
    record.valid === active && (!active || (record.key.valid &&
      record.key.peId === incomingBatch.release.firstGroup.peId &&
      record.key.stid === incomingStid &&
      record.key.ridSlot === slotSum(p.ridSlotWidth - 1, 0) &&
      record.key.ridGeneration ===
        incomingBatch.release.firstGroup.ridGeneration + wraps.asUInt &&
      record.brob.valid && record.brob.bid.valid &&
      record.physicalMemberCount.orR &&
      record.completedMembers === completedMask))
  }.reduce(_ && _)
  val incomingExpectedSources = (0 until p.retireGroupWidth).map {
    groupIndex =>
      Mux(groupIndex.U < incomingGroupCount,
        PopCount(incomingBatch.groups(groupIndex).logicalUopMask), 0.U)
  }.reduce(_ +& _)
  val incomingSourcesInRange =
    incomingExpectedSources <= p.maxCommitTURetireSources.U &&
      incomingExpectedSources <= sourceCount(safeIncomingStid)

  val incomingSources = Wire(Vec(p.maxCommitTURetireSources,
    new OooTURetireSource(p)))
  val incomingSourceActive = Wire(Vec(p.maxCommitTURetireSources, Bool()))
  val incomingSourceMatches = Wire(Vec(p.maxCommitTURetireSources,
    Vec(p.retireGroupWidth, Bool())))
  for (sourceIndex <- 0 until p.maxCommitTURetireSources) {
    val queueIndex = addSourcePtr(sourceHead(safeIncomingStid), sourceIndex.U)
    val source = sourceQueue(safeIncomingStid)(queueIndex)
    incomingSources(sourceIndex) := source
    incomingSourceActive(sourceIndex) := sourceIndex.U < incomingExpectedSources
    for (groupIndex <- 0 until p.retireGroupWidth) {
      val group = incomingBatch.groups(groupIndex)
      val uopIndexInRange = source.uopIndex < p.decodedUopWidth.U
      val safeUopIndex = Mux(uopIndexInRange, source.uopIndex, 0.U)
      incomingSourceMatches(sourceIndex)(groupIndex) :=
        groupIndex.U < incomingGroupCount && group.valid && source.valid &&
          source.member.group.asUInt === group.key.asUInt &&
          source.member.bid.asUInt === group.brob.bid.asUInt &&
          source.member.brobGeneration === group.brob.generation &&
          source.member.residentGeneration === group.residentGeneration &&
          source.transactionId === group.transactionId &&
          source.member.memberIndex < group.physicalMemberCount &&
          uopIndexInRange && group.logicalUopMask(safeUopIndex)
    }
  }
  val incomingGroupSourcesExact = (0 until p.retireGroupWidth).flatMap {
    groupIndex =>
      (0 until p.decodedUopWidth).map { uopIndex =>
        val group = incomingBatch.groups(groupIndex)
        val active = groupIndex.U < incomingGroupCount
        val count = PopCount((0 until p.maxCommitTURetireSources).map {
          sourceIndex => incomingSourceActive(sourceIndex) &&
            incomingSourceMatches(sourceIndex)(groupIndex) &&
            incomingSources(sourceIndex).uopIndex === uopIndex.U
        })
        Mux(active && group.logicalUopMask(uopIndex),
          count === 1.U, count === 0.U)
      }
  }.reduce(_ && _)
  val incomingSourcesExact = (0 until p.maxCommitTURetireSources).map {
    sourceIndex =>
      !incomingSourceActive(sourceIndex) ||
        (incomingSources(sourceIndex).valid &&
          PopCount(incomingSourceMatches(sourceIndex)) === 1.U)
  }.reduce(_ && _)
  val incomingGroupOrderExact = (1 until p.maxCommitTURetireSources).map {
    sourceIndex =>
      !incomingSourceActive(sourceIndex) ||
        !incomingSourceActive(sourceIndex - 1) ||
        PriorityEncoder(incomingSourceMatches(sourceIndex - 1).asUInt) <=
          PriorityEncoder(incomingSourceMatches(sourceIndex).asUInt)
  }.reduceOption(_ && _).getOrElse(true.B)
  val lateIndex = addSourcePtr(sourceHead(safeIncomingStid),
    incomingExpectedSources)
  val lateSource = sourceQueue(safeIncomingStid)(lateIndex)
  val lateMatches = (0 until p.retireGroupWidth).map { groupIndex =>
    val group = incomingBatch.groups(groupIndex)
    groupIndex.U < incomingGroupCount && group.valid && lateSource.valid &&
      lateSource.member.group.asUInt === group.key.asUInt &&
      lateSource.member.bid.asUInt === group.brob.bid.asUInt &&
      lateSource.member.brobGeneration === group.brob.generation &&
      lateSource.member.residentGeneration === group.residentGeneration &&
      lateSource.transactionId === group.transactionId
  }.reduce(_ || _)
  val noLateSource = sourceCount(safeIncomingStid) ===
    incomingExpectedSources || !lateMatches
  val incomingCommitExact = incomingStidInRange &&
    incomingGroupCountInRange && incomingBatch.release.firstGroup.valid &&
    incomingGroupsExact && incomingSourcesInRange &&
    incomingGroupSourcesExact && incomingSourcesExact &&
    incomingGroupOrderExact && noLateSource

  val commitBatchRetained = io.commitPrepare.valid &&
    io.commitPrepare.bits.asUInt === commitBatch.asUInt
  io.commitStartReady := state === OooTURetireState.Idle &&
    recoveryState === OooTURetireRecoveryState.Idle &&
    incomingCommitExact
  val commitStart = state === OooTURetireState.Idle &&
    recoveryState === OooTURetireRecoveryState.Idle &&
    io.commitPrepare.valid && incomingCommitExact
  when(commitStart) {
    commitBatch := incomingBatch
    commitSourcesRemaining := incomingExpectedSources
    commitSourcesTotal := incomingExpectedSources
    state := Mux(incomingExpectedSources.orR,
      OooTURetireState.LoadSource,
      OooTURetireState.AwaitDeallocation)
  }

  val commitActiveStid = commitBatch.release.firstGroup.stid
  val eventBlock = Wire(new BrobPointer(p))
  eventBlock := 0.U.asTypeOf(eventBlock)
  // `RobMemberKey` stores the native BID and BROB generation separately.
  when(processingCloseBefore) {
    eventBlock := currentSource.closeBefore
  }.otherwise {
    eventBlock.valid := currentSource.member.bid.valid
    eventBlock.bid := currentSource.member.bid
    eventBlock.generation := currentSource.member.brobGeneration
  }
  val eventGroup = currentSource.member.group
  val eventIsLast = processingCloseBefore || currentSource.blockLast

  val tNewest = tRelations(commitActiveStid)(
    decRelationPtr(tRelationTail(commitActiveStid)))
  val uNewest = uRelations(commitActiveStid)(
    decRelationPtr(uRelationTail(commitActiveStid)))
  val preReleaseT = tRelationCount(commitActiveStid).orR &&
    (eventIsLast || !sameBlock(tNewest.block, eventBlock) ||
      !sameGroup(tNewest.group, eventGroup))
  val preReleaseU = uRelationCount(commitActiveStid).orR &&
    (eventIsLast || !sameBlock(uNewest.block, eventBlock) ||
      !sameGroup(uNewest.group, eventGroup))

  val destinationCandidates = Wire(Vec(p.maxDestinationOperands, Bool()))
  for (destinationIndex <- 0 until p.maxDestinationOperands) {
    destinationCandidates(destinationIndex) := !processingCloseBefore &&
      destinationIndex.U >= destinationCursor &&
      currentSource.destinations(destinationIndex).valid
  }
  val destinationAvailable = destinationCandidates.asUInt.orR
  val selectedDestinationIndex = PriorityEncoder(destinationCandidates.asUInt)
  val selectedDestination = currentSource.destinations(
    selectedDestinationIndex)

  io.retireCommand.valid := false.B
  io.retireCommand.bits := 0.U.asTypeOf(io.retireCommand.bits)
  io.blockCommit.valid := false.B
  io.blockCommit.bits := 0.U.asTypeOf(io.blockCommit.bits)

  when(state === OooTURetireState.PreReleaseT && preReleaseT) {
    val relation = tRelations(commitActiveStid)(
      tRelationHead(commitActiveStid))
    io.retireCommand.valid := true.B
    io.retireCommand.bits.valid := true.B
    io.retireCommand.bits.member := relation.member
    io.retireCommand.bits.kind := DestinationKind.T
    io.retireCommand.bits.sequence := relation.sequence
    io.retireCommand.bits.dealloc := true.B
  }.elsewhen(state === OooTURetireState.PreReleaseU && preReleaseU) {
    val relation = uRelations(commitActiveStid)(
      uRelationHead(commitActiveStid))
    io.retireCommand.valid := true.B
    io.retireCommand.bits.valid := true.B
    io.retireCommand.bits.member := relation.member
    io.retireCommand.bits.kind := DestinationKind.U
    io.retireCommand.bits.sequence := relation.sequence
    io.retireCommand.bits.dealloc := true.B
  }.elsewhen(state === OooTURetireState.MarkDestination &&
    destinationAvailable) {
    io.retireCommand.valid := true.B
    io.retireCommand.bits.valid := true.B
    io.retireCommand.bits.member := currentSource.member
    io.retireCommand.bits.kind := selectedDestination.kind
    io.retireCommand.bits.sequence := selectedDestination.sequence
    io.retireCommand.bits.dealloc := false.B
  }.elsewhen(state === OooTURetireState.PostRelease) {
    val releaseT = postReleaseKind === DestinationKind.T
    val relation = Mux(releaseT,
      tRelations(commitActiveStid)(tRelationHead(commitActiveStid)),
      uRelations(commitActiveStid)(uRelationHead(commitActiveStid)))
    io.retireCommand.valid := true.B
    io.retireCommand.bits.valid := true.B
    io.retireCommand.bits.member := relation.member
    io.retireCommand.bits.kind := postReleaseKind
    io.retireCommand.bits.sequence := relation.sequence
    io.retireCommand.bits.dealloc := true.B
  }

  when(state === OooTURetireState.BlockCommit) {
    io.blockCommit.valid := true.B
    io.blockCommit.bits.valid := true.B
    io.blockCommit.bits.peId := currentSource.member.group.peId
    io.blockCommit.bits.stid := commitActiveStid
    io.blockCommit.bits.block := eventBlock
  }

  val retireCommandFire = io.retireCommand.fire
  val blockCommitFire = io.blockCommit.fire

  val tKeep = Wire(Vec(p.tuRelationDepthPerStid, Bool()))
  val uKeep = Wire(Vec(p.tuRelationDepthPerStid, Bool()))
  val tCompacted = Wire(Vec(p.tuRelationDepthPerStid,
    new OooTURelationEntry(p)))
  val uCompacted = Wire(Vec(p.tuRelationDepthPerStid,
    new OooTURelationEntry(p)))
  tCompacted := VecInit(Seq.fill(p.tuRelationDepthPerStid)(zeroRelation))
  uCompacted := VecInit(Seq.fill(p.tuRelationDepthPerStid)(zeroRelation))
  for (offset <- 0 until p.tuRelationDepthPerStid) {
    val tEntry = tRelations(commitActiveStid)(
      addRelationPtr(tRelationHead(commitActiveStid), offset.U))
    val uEntry = uRelations(commitActiveStid)(
      addRelationPtr(uRelationHead(commitActiveStid), offset.U))
    tKeep(offset) := offset.U < tRelationCount(commitActiveStid) &&
      !sameBlock(tEntry.block, eventBlock)
    uKeep(offset) := offset.U < uRelationCount(commitActiveStid) &&
      !sameBlock(uEntry.block, eventBlock)
    val tOrdinal = Wire(UInt(p.tuRelationCountWidth.W))
    val uOrdinal = Wire(UInt(p.tuRelationCountWidth.W))
    tOrdinal := (if (offset == 0) 0.U else PopCount(tKeep.take(offset)))
    uOrdinal := (if (offset == 0) 0.U else PopCount(uKeep.take(offset)))
    when(tKeep(offset)) {
      tCompacted(tOrdinal(p.tuRelationIndexWidth - 1, 0)) := tEntry
    }
    when(uKeep(offset)) {
      uCompacted(uOrdinal(p.tuRelationIndexWidth - 1, 0)) := uEntry
    }
  }
  val tCompactedCount = PopCount(tKeep)
  val uCompactedCount = PopCount(uKeep)

  when(state =/= OooTURetireState.Idle) {
    assert(commitBatchRetained,
      "ROB must retain the exact T/U commit batch through relation retirement")
  }

  switch(state) {
    is(OooTURetireState.LoadSource) {
      currentSource := sourceQueue(commitActiveStid)(
        sourceHead(commitActiveStid))
      processingCloseBefore := sourceQueue(commitActiveStid)(
        sourceHead(commitActiveStid)).closeBeforeValid
      destinationCursor := 0.U
      state := OooTURetireState.PreReleaseT
    }
    is(OooTURetireState.PreReleaseT) {
      when(!preReleaseT) {
        state := OooTURetireState.PreReleaseU
      }.elsewhen(retireCommandFire) {
        tRelations(commitActiveStid)(tRelationHead(commitActiveStid)) :=
          zeroRelation
        tRelationHead(commitActiveStid) := addRelationPtr(
          tRelationHead(commitActiveStid), 1.U)
        tRelationCount(commitActiveStid) :=
          tRelationCount(commitActiveStid) - 1.U
      }
    }
    is(OooTURetireState.PreReleaseU) {
      when(!preReleaseU) {
        state := OooTURetireState.MarkDestination
      }.elsewhen(retireCommandFire) {
        uRelations(commitActiveStid)(uRelationHead(commitActiveStid)) :=
          zeroRelation
        uRelationHead(commitActiveStid) := addRelationPtr(
          uRelationHead(commitActiveStid), 1.U)
        uRelationCount(commitActiveStid) :=
          uRelationCount(commitActiveStid) - 1.U
      }
    }
    is(OooTURetireState.MarkDestination) {
      when(!destinationAvailable) {
        state := Mux(eventIsLast, OooTURetireState.CleanRelations,
          OooTURetireState.AdvanceSource)
      }.elsewhen(retireCommandFire) {
        val nextCursor = selectedDestinationIndex +& 1.U
        destinationCursor := nextCursor(destinationCursorWidth - 1, 0)
        when(selectedDestination.kind === DestinationKind.T) {
          assert(tRelationCount(commitActiveStid) <
            p.tuRelationDepthPerStid.U,
            "T relation CMAP must have capacity before destination mark")
          val relation = Wire(new OooTURelationEntry(p))
          relation.valid := true.B
          relation.block := eventBlock
          relation.group := eventGroup
          relation.member := currentSource.member
          relation.sequence := selectedDestination.sequence
          tRelations(commitActiveStid)(tRelationTail(commitActiveStid)) :=
            relation
          tRelationTail(commitActiveStid) := addRelationPtr(
            tRelationTail(commitActiveStid), 1.U)
          tRelationCount(commitActiveStid) :=
            tRelationCount(commitActiveStid) + 1.U
          when(eventIsLast || tRelationCount(commitActiveStid) >=
            p.tuRelationReleaseThreshold.U) {
            postReleaseKind := DestinationKind.T
            state := OooTURetireState.PostRelease
          }
        }.otherwise {
          assert(selectedDestination.kind === DestinationKind.U &&
            uRelationCount(commitActiveStid) <
              p.tuRelationDepthPerStid.U,
            "U relation CMAP must have capacity before destination mark")
          val relation = Wire(new OooTURelationEntry(p))
          relation.valid := true.B
          relation.block := eventBlock
          relation.group := eventGroup
          relation.member := currentSource.member
          relation.sequence := selectedDestination.sequence
          uRelations(commitActiveStid)(uRelationTail(commitActiveStid)) :=
            relation
          uRelationTail(commitActiveStid) := addRelationPtr(
            uRelationTail(commitActiveStid), 1.U)
          uRelationCount(commitActiveStid) :=
            uRelationCount(commitActiveStid) + 1.U
          when(eventIsLast || uRelationCount(commitActiveStid) >=
            p.tuRelationReleaseThreshold.U) {
            postReleaseKind := DestinationKind.U
            state := OooTURetireState.PostRelease
          }
        }
      }
    }
    is(OooTURetireState.PostRelease) {
      when(retireCommandFire) {
        when(postReleaseKind === DestinationKind.T) {
          tRelations(commitActiveStid)(tRelationHead(commitActiveStid)) :=
            zeroRelation
          tRelationHead(commitActiveStid) := addRelationPtr(
            tRelationHead(commitActiveStid), 1.U)
          // The mark and post-release happen in different cycles, so the
          // relation inserted by the mark is already reflected in this count.
          tRelationCount(commitActiveStid) :=
            tRelationCount(commitActiveStid) - 1.U
        }.otherwise {
          uRelations(commitActiveStid)(uRelationHead(commitActiveStid)) :=
            zeroRelation
          uRelationHead(commitActiveStid) := addRelationPtr(
            uRelationHead(commitActiveStid), 1.U)
          uRelationCount(commitActiveStid) :=
            uRelationCount(commitActiveStid) - 1.U
        }
        state := OooTURetireState.MarkDestination
      }
    }
    is(OooTURetireState.CleanRelations) {
      tRelations(commitActiveStid) := tCompacted
      uRelations(commitActiveStid) := uCompacted
      tRelationHead(commitActiveStid) := 0.U
      uRelationHead(commitActiveStid) := 0.U
      tRelationTail(commitActiveStid) :=
        tCompactedCount(p.tuRelationIndexWidth - 1, 0)
      uRelationTail(commitActiveStid) :=
        uCompactedCount(p.tuRelationIndexWidth - 1, 0)
      tRelationCount(commitActiveStid) := tCompactedCount
      uRelationCount(commitActiveStid) := uCompactedCount
      state := OooTURetireState.BlockCommit
    }
    is(OooTURetireState.BlockCommit) {
      when(blockCommitFire) {
        when(processingCloseBefore) {
          processingCloseBefore := false.B
          destinationCursor := 0.U
          state := OooTURetireState.PreReleaseT
        }.otherwise {
          state := OooTURetireState.AdvanceSource
        }
      }
    }
    is(OooTURetireState.AdvanceSource) {
      sourceQueue(commitActiveStid)(sourceHead(commitActiveStid)) := zeroSource
      sourceHead(commitActiveStid) := addSourcePtr(
        sourceHead(commitActiveStid), 1.U)
      sourceCount(commitActiveStid) := sourceCount(commitActiveStid) - 1.U
      commitSourcesRemaining := commitSourcesRemaining - 1.U
      state := Mux(commitSourcesRemaining === 1.U,
        OooTURetireState.AwaitDeallocation,
        OooTURetireState.LoadSource)
    }
    is(OooTURetireState.AwaitDeallocation) {
      when(io.commitFire) {
        state := OooTURetireState.Idle
        commitSourcesRemaining := 0.U
        commitSourcesTotal := 0.U
      }
    }
  }

  for (stid <- 0 until p.stidCount) {
    val publishHere = publicationFire && publicationStid === stid.U
    val advanceHere = state === OooTURetireState.AdvanceSource &&
      commitActiveStid === stid.U
    when(publishHere) {
      for (uopIndex <- 0 until p.decodedUopWidth) {
        val priorCount = if (uopIndex == 0) 0.U else
          PopCount(publication.uopMask(uopIndex - 1, 0))
        val writeIndex = addSourcePtr(sourceTail(stid), priorCount)
        when(publication.uopMask(uopIndex)) {
          sourceQueue(stid)(writeIndex) := publication.sources(uopIndex)
        }
      }
      sourceTail(stid) := addSourcePtr(sourceTail(stid),
        publicationSourceCount)
      sourceCount(stid) := sourceCount(stid) + publicationSourceCount
    }
    when(publishHere && advanceHere) {
      assert(false.B,
        "same-STID T/U publication must wait for retirement commit")
    }
  }

  io.commitBusy := state =/= OooTURetireState.Idle
  io.commitStid := Mux(io.commitBusy, commitActiveStid, 0.U)
  io.commitReady := state === OooTURetireState.AwaitDeallocation &&
    commitBatchRetained
  io.commitPrepared := 0.U.asTypeOf(io.commitPrepared)
  io.commitPrepared.valid := io.commitReady
  io.commitPrepared.stid := commitActiveStid
  io.commitPrepared.sourceCount := commitSourcesTotal
  io.commitRejected.valid := state === OooTURetireState.Idle &&
    io.commitPrepare.valid && !incomingCommitExact
  io.commitRejected.bits.requested := io.commitPrepare.bits
  io.commitRejected.bits.sourceHead := sourceHead(safeIncomingStid)
  io.commitRejected.bits.sourceCount := sourceCount(safeIncomingStid)

  when(io.commitFire) {
    assert(io.commitReady,
      "T/U common deallocation requires completed retained retirement work")
  }

  io.sourceQueueUsed := sourceCount
  io.tRelationUsed := tRelationCount
  io.uRelationUsed := uRelationCount
}
