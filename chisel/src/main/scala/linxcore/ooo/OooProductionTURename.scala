package linxcore.ooo

import chisel3._
import chisel3.util.{Cat, PopCount, Valid}
import linxcore.common.{DestinationKind, OperandClass}

class OooProductionTURenameIO(val p: OooParams = OooParams()) extends Bundle {
  val reservePrepare = Flipped(Valid(new OooD2GroupedTransaction(p)))
  val reserveReady = Output(Bool())
  val reservation = Output(new OooTUReservation(p))
  val reserveFire = Input(Bool())
  val cancel = Input(Vec(p.stidCount, Bool()))

  val publicationPrepare = Flipped(Valid(new OooTUPublicationRequest(p)))
  val publicationReady = Output(Bool())
  val prepared = Output(new OooTURenamePreparedTransaction(p))
  val publishFire = Input(Bool())

  val provisional = Output(Vec(p.stidCount, new OooTUReservation(p)))
  val tMapQUsed = Output(Vec(p.stidCount, UInt(p.tuMapQCountWidth.W)))
  val uMapQUsed = Output(Vec(p.stidCount, UInt(p.tuMapQCountWidth.W)))
  val tPhysicalUsed = Output(Vec(p.stidCount,
    UInt(p.countWidth(p.tPhysRegs).W)))
  val uPhysicalUsed = Output(Vec(p.stidCount,
    UInt(p.countWidth(p.uPhysRegs).W)))
  val reserveRejected = Valid(new OooTURenamePrepareReject(p))
  val publicationRejected = Valid(new OooTURenamePublishReject(p))
}

/** Per-STID production T/U sequential rename owner.
  *
  * T and U deliberately share no P SMAP, CMAP, free list, or PTag staging
  * state. A relative source names `tail - (relativeIndex + 1)` in its own
  * namespace. Each destination claims the current local sequence and the next
  * circular physical tag. The complete bundle is previewed oldest-to-youngest
  * at D3 reserve, retained as one provisional lease, and becomes visible only
  * on the common S1 publication fire.
  *
  * Retirement/relation-CMAP and recovery are added by the following O4.4
  * packets. Until those owners are connected, published entries remain live;
  * this module never guesses that P commit or ROB slot reuse releases them.
  */
class OooProductionTURename(val p: OooParams = OooParams()) extends Module {
  val io = IO(new OooProductionTURenameIO(p))

  private val seqPackedWidth = p.localSeqGenerationWidth + p.tuMapQIndexWidth
  private val destinationIndexWidth = math.max(1,
    chisel3.util.log2Ceil(p.maxDestinationOperands))
  private val sourceSlotCount = p.decodedUopWidth * p.maxSourceOperands

  private def zeroRow: OooTUMapQEntry =
    0.U.asTypeOf(new OooTUMapQEntry(p))

  private def packSeq(seq: OooLocalSeq): UInt =
    Cat(seq.generation, seq.index)

  private def driveSeq(seq: OooLocalSeq, packed: UInt, valid: Bool): Unit = {
    seq.valid := valid
    seq.index := packed(p.tuMapQIndexWidth - 1, 0)
    seq.generation := packed(seqPackedWidth - 1, p.tuMapQIndexWidth)
  }

  private def kindIsT(kind: DestinationKind.Type): Bool =
    kind === DestinationKind.T

  private def kindIsU(kind: DestinationKind.Type): Bool =
    kind === DestinationKind.U

  private def incrementPhysical(ptr: UInt, increment: UInt, entries: Int): UInt = {
    val indexWidth = chisel3.util.log2Ceil(entries)
    val next = ptr + increment
    if (indexWidth == p.localTagWidth) {
      next(p.localTagWidth - 1, 0)
    } else {
      Cat(0.U((p.localTagWidth - indexWidth).W), next(indexWidth - 1, 0))
    }
  }

  val tMapQ = RegInit(VecInit(Seq.fill(p.stidCount)(
    VecInit(Seq.fill(p.tuMapQDepthPerStid)(zeroRow)))))
  val uMapQ = RegInit(VecInit(Seq.fill(p.stidCount)(
    VecInit(Seq.fill(p.tuMapQDepthPerStid)(zeroRow)))))
  val tTail = RegInit(VecInit(Seq.fill(p.stidCount)(
    0.U(seqPackedWidth.W))))
  val uTail = RegInit(VecInit(Seq.fill(p.stidCount)(
    0.U(seqPackedWidth.W))))
  val tNextPhysical = RegInit(VecInit(Seq.fill(p.stidCount)(
    0.U(p.localTagWidth.W))))
  val uNextPhysical = RegInit(VecInit(Seq.fill(p.stidCount)(
    0.U(p.localTagWidth.W))))
  val tCount = RegInit(VecInit(Seq.fill(p.stidCount)(
    0.U(p.tuMapQCountWidth.W))))
  val uCount = RegInit(VecInit(Seq.fill(p.stidCount)(
    0.U(p.tuMapQCountWidth.W))))
  val tPhysicalCount = RegInit(VecInit(Seq.fill(p.stidCount)(
    0.U(p.countWidth(p.tPhysRegs).W))))
  val uPhysicalCount = RegInit(VecInit(Seq.fill(p.stidCount)(
    0.U(p.countWidth(p.uPhysRegs).W))))
  val provisional = RegInit(VecInit(Seq.fill(p.stidCount)(
    0.U.asTypeOf(new OooTUReservation(p)))))

  val reservePlan = io.reservePrepare.bits.plan
  val reserveDecoded = io.reservePrepare.bits.decoded
  val reserveStid = reservePlan.stid
  val reserveStidInRange = reserveStid < p.stidCount.U
  val safeReserveStid = Mux(reserveStidInRange, reserveStid, 0.U)

  val destinationActive = Wire(Vec(p.tuAllocationWidth, Bool()))
  val destinationIsT = Wire(Vec(p.tuAllocationWidth, Bool()))
  val destinationIsU = Wire(Vec(p.tuAllocationWidth, Bool()))
  for (uopIndex <- 0 until p.decodedUopWidth;
       destinationIndex <- 0 until p.maxDestinationOperands) {
    val flatIndex = uopIndex * p.maxDestinationOperands + destinationIndex
    val uop = reserveDecoded.uops(uopIndex)
    val destination = uop.destinations(destinationIndex)
    val active = reserveDecoded.uopMask(uopIndex) && uop.valid &&
      destination.valid && (kindIsT(destination.kind) ||
        kindIsU(destination.kind))
    destinationActive(flatIndex) := active
    destinationIsT(flatIndex) := active && kindIsT(destination.kind)
    destinationIsU(flatIndex) := active && kindIsU(destination.kind)
  }
  val requestedT = PopCount(destinationIsT)
  val requestedU = PopCount(destinationIsU)
  val publicationStidPreview = io.publicationPrepare.bits.stid
  val publicationStidPreviewInRange =
    publicationStidPreview < p.stidCount.U
  val replacesPublishingLease = io.publishFire &&
    io.publicationPrepare.valid && publicationStidPreviewInRange &&
    publicationStidPreview === reserveStid
  val publishingLease = provisional(safeReserveStid)
  val publishingT = Mux(replacesPublishingLease,
    publishingLease.tAllocationCount, 0.U)
  val publishingU = Mux(replacesPublishingLease,
    publishingLease.uAllocationCount, 0.U)
  val freeTEntries = p.tuMapQDepthPerStid.U -
    (tCount(safeReserveStid) +& publishingT)
  val freeUEntries = p.tuMapQDepthPerStid.U -
    (uCount(safeReserveStid) +& publishingU)
  val freeTPhysical = p.tPhysRegs.U -
    (tPhysicalCount(safeReserveStid) +& publishingT)
  val freeUPhysical = p.uPhysRegs.U -
    (uPhysicalCount(safeReserveStid) +& publishingU)

  io.reservation := 0.U.asTypeOf(io.reservation)
  io.reservation.valid := io.reservePrepare.valid
  io.reservation.peId := reservePlan.peId
  io.reservation.stid := reserveStid
  io.reservation.epoch := reservePlan.epoch
  io.reservation.transactionId := reservePlan.transactionId
  io.reservation.uopMask := reserveDecoded.uopMask
  io.reservation.tAllocationCount := requestedT
  io.reservation.uAllocationCount := requestedU
  io.reservation.allocationMask := destinationActive.asUInt

  val sourceUnderflow = Wire(Vec(sourceSlotCount, Bool()))
  val allocationValid = Wire(Vec(p.tuAllocationWidth, Bool()))
  val allocationKind = Wire(Vec(p.tuAllocationWidth, DestinationKind()))
  val allocationSeqPacked = Wire(Vec(p.tuAllocationWidth,
    UInt(seqPackedWidth.W)))
  val allocationPhysical = Wire(Vec(p.tuAllocationWidth,
    UInt(p.localTagWidth.W)))
  allocationValid := VecInit(Seq.fill(p.tuAllocationWidth)(false.B))
  allocationKind := VecInit(Seq.fill(p.tuAllocationWidth)(DestinationKind.None))
  allocationSeqPacked := VecInit(Seq.fill(p.tuAllocationWidth)(0.U))
  allocationPhysical := VecInit(Seq.fill(p.tuAllocationWidth)(0.U))

  var virtualTSeq = (tTail(safeReserveStid) + publishingT)(
    seqPackedWidth - 1, 0)
  var virtualUSeq = (uTail(safeReserveStid) + publishingU)(
    seqPackedWidth - 1, 0)
  var virtualTPhysical = incrementPhysical(tNextPhysical(safeReserveStid),
    publishingT, p.tPhysRegs)
  var virtualUPhysical = incrementPhysical(uNextPhysical(safeReserveStid),
    publishingU, p.uPhysRegs)
  var olderTAllocations = 0.U(p.destinationDemandWidth.W)
  var olderUAllocations = 0.U(p.destinationDemandWidth.W)

  for (uopIndex <- 0 until p.decodedUopWidth) {
    val uop = reserveDecoded.uops(uopIndex)
    val activeUop = reserveDecoded.uopMask(uopIndex) && uop.valid
    val groupIndex = io.reservePrepare.bits.uopGroupIndex(uopIndex)
    val groupIndexInRange = groupIndex < p.instructionDecodeWidth.U &&
      groupIndex < reservePlan.groupCount
    val safeGroupIndex = Mux(groupIndexInRange, groupIndex, 0.U)
    val group = io.reservePrepare.bits.groups(safeGroupIndex)
    val member = io.reservation.members(uopIndex)
    member.valid := activeUop
    member.group := group.key
    member.memberIndex := io.reservePrepare.bits.uopMemberBase(uopIndex)
    driveSeq(io.reservation.tSeqBefore(uopIndex), virtualTSeq, activeUop)
    driveSeq(io.reservation.uSeqBefore(uopIndex), virtualUSeq, activeUop)

    for (sourceIndex <- 0 until p.maxSourceOperands) {
      val flatSource = uopIndex * p.maxSourceOperands + sourceIndex
      val source = uop.sources(sourceIndex)
      val isT = activeUop && source.valid &&
        source.operandClass === OperandClass.T
      val isU = activeUop && source.valid &&
        source.operandClass === OperandClass.U
      val isLocal = isT || isU
      val offset = source.relativeIndex +& 1.U
      val offsetInRange = offset <= p.tuMapQDepthPerStid.U
      val availableT = tCount(safeReserveStid) +& publishingT +&
        olderTAllocations
      val availableU = uCount(safeReserveStid) +& publishingU +&
        olderUAllocations
      val hasDepth = Mux(isT, offset <= availableT,
        Mux(isU, offset <= availableU, false.B))
      val basePacked = Mux(isT, virtualTSeq, virtualUSeq)
      val resolvedPacked = basePacked - offset
      val resolvedIndex = resolvedPacked(p.tuMapQIndexWidth - 1, 0)
      val baseRow = Mux(isT,
        tMapQ(safeReserveStid)(resolvedIndex),
        uMapQ(safeReserveStid)(resolvedIndex))
      var resolvedValid = baseRow.valid &&
        packSeq(baseRow.mapping.sequence) === resolvedPacked
      var resolvedPhysical = baseRow.mapping.physicalTag
      for (publishingFlat <- 0 until p.tuAllocationWidth) {
        val publishingAllocation = publishingLease.allocations(publishingFlat)
        val kindMatches = Mux(isT,
          kindIsT(publishingAllocation.kind),
          kindIsU(publishingAllocation.kind))
        val hit = replacesPublishingLease && publishingAllocation.valid &&
          kindMatches &&
          packSeq(publishingAllocation.mapping.sequence) === resolvedPacked
        resolvedValid = Mux(hit, true.B, resolvedValid)
        resolvedPhysical = Mux(hit,
          publishingAllocation.mapping.physicalTag, resolvedPhysical)
      }
      for (olderFlat <- 0 until uopIndex * p.maxDestinationOperands) {
        val kindMatches = Mux(isT,
          kindIsT(allocationKind(olderFlat)),
          kindIsU(allocationKind(olderFlat)))
        val hit = allocationValid(olderFlat) && kindMatches &&
          allocationSeqPacked(olderFlat) === resolvedPacked
        resolvedValid = Mux(hit, true.B, resolvedValid)
        resolvedPhysical = Mux(hit,
          allocationPhysical(olderFlat), resolvedPhysical)
      }

      val mapping = io.reservation.sourceMappings(uopIndex)(sourceIndex)
      mapping := 0.U.asTypeOf(mapping)
      mapping.valid := isLocal && offsetInRange && hasDepth && resolvedValid
      mapping.kind := Mux(isT, DestinationKind.T, DestinationKind.U)
      mapping.relativeIndex := source.relativeIndex
      driveSeq(mapping.sequence, resolvedPacked, isLocal)
      mapping.physicalTag := resolvedPhysical
      mapping.stid := reserveStid
      mapping.epoch := reservePlan.epoch
      sourceUnderflow(flatSource) := isLocal &&
        (!offsetInRange || !hasDepth || !resolvedValid)
    }

    for (destinationIndex <- 0 until p.maxDestinationOperands) {
      val flatIndex = uopIndex * p.maxDestinationOperands + destinationIndex
      val destination = uop.destinations(destinationIndex)
      val allocation = io.reservation.allocations(flatIndex)
      val isT = destinationIsT(flatIndex)
      val isU = destinationIsU(flatIndex)
      val selectedSeq = Mux(isT, virtualTSeq, virtualUSeq)
      val selectedPhysical = Mux(isT,
        virtualTPhysical, virtualUPhysical)

      allocation := 0.U.asTypeOf(allocation)
      allocation.valid := destinationActive(flatIndex)
      allocation.kind := destination.kind
      allocation.uopIndex := uopIndex.U
      allocation.destinationIndex := destinationIndex.U(destinationIndexWidth.W)
      allocation.relativeIndex := destination.relativeIndex
      allocation.mapping.valid := destinationActive(flatIndex)
      allocation.mapping.kind := destination.kind
      allocation.mapping.relativeIndex := destination.relativeIndex
      driveSeq(allocation.mapping.sequence, selectedSeq,
        destinationActive(flatIndex))
      allocation.mapping.physicalTag := selectedPhysical
      allocation.mapping.stid := reserveStid
      allocation.mapping.epoch := reservePlan.epoch

      allocationValid(flatIndex) := destinationActive(flatIndex)
      allocationKind(flatIndex) := destination.kind
      allocationSeqPacked(flatIndex) := selectedSeq
      allocationPhysical(flatIndex) := selectedPhysical

      val nextTSeq = virtualTSeq + isT.asUInt
      val nextUSeq = virtualUSeq + isU.asUInt
      virtualTSeq = nextTSeq(seqPackedWidth - 1, 0)
      virtualUSeq = nextUSeq(seqPackedWidth - 1, 0)
      virtualTPhysical = incrementPhysical(
        virtualTPhysical, isT.asUInt, p.tPhysRegs)
      virtualUPhysical = incrementPhysical(
        virtualUPhysical, isU.asUInt, p.uPhysRegs)
      olderTAllocations = olderTAllocations + isT.asUInt
      olderUAllocations = olderUAllocations + isU.asUInt
    }
  }

  val identityExact = reservePlan.peId === reserveDecoded.peId &&
    reservePlan.stid === reserveDecoded.stid &&
    reservePlan.epoch === reserveDecoded.epoch &&
    reservePlan.uopMask === reserveDecoded.uopMask
  val memberShapeExact = (0 until p.decodedUopWidth).map { uopIndex =>
    val uop = reserveDecoded.uops(uopIndex)
    val activeUop = reserveDecoded.uopMask(uopIndex) && uop.valid
    val groupIndex = io.reservePrepare.bits.uopGroupIndex(uopIndex)
    val groupIndexInRange = groupIndex < p.instructionDecodeWidth.U &&
      groupIndex < reservePlan.groupCount
    val safeGroupIndex = Mux(groupIndexInRange, groupIndex, 0.U)
    val group = io.reservePrepare.bits.groups(safeGroupIndex)
    val memberEnd = io.reservePrepare.bits.uopMemberBase(uopIndex) +&
      uop.plannedChildCount
    !activeUop || (groupIndexInRange && group.valid && group.key.valid &&
      group.key.peId === reservePlan.peId &&
      group.key.stid === reservePlan.stid &&
      group.logicalUopMask(uopIndex) && uop.plannedChildCount.orR &&
      memberEnd <= group.physicalMemberCount)
  }.reduce(_ && _)
  val demandExact = reservePlan.demand.tAllocations === requestedT &&
    reservePlan.demand.uAllocations === requestedU
  val capacityAvailable = requestedT <= freeTEntries &&
    requestedU <= freeUEntries && requestedT <= freeTPhysical &&
    requestedU <= freeUPhysical
  val provisionalAvailable = !provisional(safeReserveStid).valid ||
    io.cancel(safeReserveStid) || replacesPublishingLease
  val sourceExact = !sourceUnderflow.asUInt.orR
  val reserveExact = reserveStidInRange && identityExact && demandExact &&
    memberShapeExact

  io.reserveReady := reserveExact && capacityAvailable &&
    provisionalAvailable && sourceExact
  io.reservation.valid := io.reservePrepare.valid && io.reserveReady
  io.reserveRejected.valid := io.reservePrepare.valid && !io.reserveReady
  io.reserveRejected.bits.stid := reserveStid
  io.reserveRejected.bits.transactionId := reservePlan.transactionId
  io.reserveRejected.bits.requestedT := requestedT
  io.reserveRejected.bits.requestedU := requestedU
  io.reserveRejected.bits.freeTEntries := freeTEntries
  io.reserveRejected.bits.freeUEntries := freeUEntries
  io.reserveRejected.bits.freeTPhysical := freeTPhysical
  io.reserveRejected.bits.freeUPhysical := freeUPhysical
  io.reserveRejected.bits.sourceUnderflowMask := sourceUnderflow.asUInt

  val reserveFire = io.reserveFire && io.reservePrepare.valid && io.reserveReady
  when(io.reserveFire) {
    assert(io.reservePrepare.valid && io.reserveReady,
      "T/U reserveFire requires one exact sequential allocation preview")
  }
  for (stid <- 0 until p.stidCount) {
    when(io.cancel(stid)) {
      provisional(stid) := 0.U.asTypeOf(provisional(stid))
    }
    when(reserveFire && reserveStid === stid.U) {
      provisional(stid) := io.reservation
    }
  }

  val publication = io.publicationPrepare.bits
  val publicationStid = publication.stid
  val publicationStidInRange = publicationStid < p.stidCount.U
  val safePublicationStid = Mux(publicationStidInRange, publicationStid, 0.U)
  val live = provisional(safePublicationStid)
  val publicationDestinationActive = Wire(Vec(p.tuAllocationWidth, Bool()))
  val publicationAllocationExact = Wire(Vec(p.tuAllocationWidth, Bool()))
  for (uopIndex <- 0 until p.decodedUopWidth;
       destinationIndex <- 0 until p.maxDestinationOperands) {
    val flatIndex = uopIndex * p.maxDestinationOperands + destinationIndex
    val uop = publication.uops(uopIndex)
    val destination = uop.destinations(destinationIndex)
    publicationDestinationActive(flatIndex) :=
      publication.uopMask(uopIndex) && uop.valid &&
        destination.valid && (kindIsT(destination.kind) ||
          kindIsU(destination.kind))
    val allocation = live.allocations(flatIndex)
    val physicalInRange = Mux(kindIsT(destination.kind),
      allocation.mapping.physicalTag < p.tPhysRegs.U,
      allocation.mapping.physicalTag < p.uPhysRegs.U)
    publicationAllocationExact(flatIndex) :=
      Mux(publicationDestinationActive(flatIndex),
        allocation.valid && allocation.mapping.valid &&
          allocation.kind === destination.kind &&
          allocation.mapping.kind === destination.kind &&
          allocation.uopIndex === uopIndex.U &&
          allocation.destinationIndex === destinationIndex.U &&
          allocation.relativeIndex === destination.relativeIndex &&
          allocation.mapping.relativeIndex === destination.relativeIndex &&
          allocation.mapping.sequence.valid && physicalInRange &&
          allocation.mapping.stid === publicationStid &&
          allocation.mapping.epoch === publication.epoch,
        !allocation.valid && !allocation.mapping.valid)
  }
  val publicationSourceExact = (0 until p.decodedUopWidth).flatMap { uopIndex =>
    (0 until p.maxSourceOperands).map { sourceIndex =>
      val uop = publication.uops(uopIndex)
      val source = uop.sources(sourceIndex)
      val activeUop = publication.uopMask(uopIndex) && uop.valid
      val local = activeUop && source.valid &&
        (kindIsT(source.kind) || kindIsU(source.kind))
      val mapping = live.sourceMappings(uopIndex)(sourceIndex)
      Mux(local,
        mapping.valid && mapping.kind === source.kind &&
          mapping.relativeIndex === source.relativeIndex &&
          mapping.sequence.valid && mapping.stid === publicationStid &&
          mapping.epoch === publication.epoch,
        !mapping.valid)
    }
  }.reduce(_ && _)
  val publicationIdentityExact = publicationStidInRange && live.valid &&
    !io.cancel(safePublicationStid) && live.peId === publication.peId &&
    live.stid === publicationStid && live.epoch === publication.epoch &&
    live.transactionId === publication.transactionId &&
    live.uopMask === publication.uopMask &&
    live.allocationMask === publicationDestinationActive.asUInt &&
    publicationAllocationExact.asUInt.andR && publicationSourceExact

  io.prepared := 0.U.asTypeOf(io.prepared)
  io.prepared.valid := io.publicationPrepare.valid && publicationIdentityExact
  io.prepared.peId := publication.peId
  io.prepared.stid := publicationStid
  io.prepared.epoch := publication.epoch
  io.prepared.transactionId := publication.transactionId
  io.prepared.uopMask := publication.uopMask
  io.prepared.allocationMask := live.allocationMask

  val memberBindingsExact = Wire(Vec(p.decodedUopWidth, Bool()))
  for (uopIndex <- 0 until p.decodedUopWidth) {
    val publicationUop = publication.uops(uopIndex)
    val renamedUop = io.prepared.uops(uopIndex)
    val activeUop = publication.uopMask(uopIndex) && publicationUop.valid
    val member = publicationUop.member
    val reservedMember = live.members(uopIndex)

    memberBindingsExact(uopIndex) := !activeUop ||
      (reservedMember.valid && member.group.asUInt ===
        reservedMember.group.asUInt &&
        member.memberIndex === reservedMember.memberIndex &&
        member.group.valid && member.group.peId === publication.peId &&
        member.group.stid === publication.stid && member.bid.valid)

    renamedUop.valid := activeUop
    renamedUop.member := member
    renamedUop.tSeqBefore := live.tSeqBefore(uopIndex)
    renamedUop.uSeqBefore := live.uSeqBefore(uopIndex)
    renamedUop.sources := live.sourceMappings(uopIndex)

    for (destinationIndex <- 0 until p.maxDestinationOperands) {
      val flatIndex = uopIndex * p.maxDestinationOperands + destinationIndex
      renamedUop.destinations(destinationIndex) :=
        live.allocations(flatIndex).mapping
      val row = io.prepared.rows(flatIndex)
      row.valid := live.allocations(flatIndex).valid
      row.retired := false.B
      row.transactionId := publication.transactionId
      row.uopIndex := uopIndex.U
      row.destinationIndex := destinationIndex.U(destinationIndexWidth.W)
      row.member := renamedUop.member
      row.mapping := live.allocations(flatIndex).mapping
    }
  }

  val publicationExact = publicationIdentityExact &&
    memberBindingsExact.asUInt.andR
  io.publicationReady := publicationExact
  io.prepared.valid := io.publicationPrepare.valid && publicationExact
  io.publicationRejected.valid := io.publicationPrepare.valid &&
    !io.publicationReady
  io.publicationRejected.bits.requestedStid := publicationStid
  io.publicationRejected.bits.requestedTransactionId :=
    publication.transactionId
  io.publicationRejected.bits.live := live

  val publishExact = io.publishFire && io.publicationPrepare.valid &&
    io.publicationReady && io.prepared.valid
  when(io.publishFire) {
    assert(io.publicationPrepare.valid && io.publicationReady &&
      io.prepared.valid,
      "T/U publishFire requires the retained exact provisional lease")
  }
  when(publishExact) {
    for (flatIndex <- 0 until p.tuAllocationWidth) {
      val row = io.prepared.rows(flatIndex)
      when(row.valid && kindIsT(row.mapping.kind)) {
        tMapQ(safePublicationStid)(row.mapping.sequence.index) := row
      }
      when(row.valid && kindIsU(row.mapping.kind)) {
        uMapQ(safePublicationStid)(row.mapping.sequence.index) := row
      }
    }
    val nextTPacked = tTail(safePublicationStid) +
      live.tAllocationCount
    val nextUPacked = uTail(safePublicationStid) +
      live.uAllocationCount
    tTail(safePublicationStid) := nextTPacked(seqPackedWidth - 1, 0)
    uTail(safePublicationStid) := nextUPacked(seqPackedWidth - 1, 0)
    tNextPhysical(safePublicationStid) := incrementPhysical(
      tNextPhysical(safePublicationStid), live.tAllocationCount, p.tPhysRegs)
    uNextPhysical(safePublicationStid) := incrementPhysical(
      uNextPhysical(safePublicationStid), live.uAllocationCount, p.uPhysRegs)
    tCount(safePublicationStid) :=
      tCount(safePublicationStid) + live.tAllocationCount
    uCount(safePublicationStid) :=
      uCount(safePublicationStid) + live.uAllocationCount
    tPhysicalCount(safePublicationStid) :=
      tPhysicalCount(safePublicationStid) + live.tAllocationCount
    uPhysicalCount(safePublicationStid) :=
      uPhysicalCount(safePublicationStid) + live.uAllocationCount
    when(!(reserveFire && reserveStid === safePublicationStid)) {
      provisional(safePublicationStid) := 0.U.asTypeOf(live)
    }
  }

  io.provisional := provisional
  io.tMapQUsed := tCount
  io.uMapQUsed := uCount
  io.tPhysicalUsed := tPhysicalCount
  io.uPhysicalUsed := uPhysicalCount
}
