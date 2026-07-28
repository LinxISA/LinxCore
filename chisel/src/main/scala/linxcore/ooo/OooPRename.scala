package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, Mux1H, PopCount, PriorityEncoder, Valid, is,
  switch}
import linxcore.common.{DestinationKind, OperandClass}

import scala.collection.mutable.ArrayBuffer

object OooPCommitState extends ChiselEnum {
  val Idle, ReadMapQ, ReturnTags, AwaitDeallocation = Value
}

object OooPRecoveryState extends ChiselEnum {
  val Idle, WaitSources, ReadRow, ReturnTag, CopyCmap, ReplaySurvivors,
    Complete = Value
}

class OooPRenameIO(val p: OooParams = OooParams()) extends Bundle {
  val prepare = Flipped(Valid(new OooO3PreparedPublication(p)))
  val ptagLease = Input(new OooPTagReservation(p))
  val dispatchLease = Input(new OooDispatchReservationLease(p))
  val prepareReady = Output(Bool())
  val prepared = Output(new OooPRenamePreparedTransaction(p))
  val publishFire = Input(Bool())

  val commitPrepare = Flipped(Valid(new OooRobCommitBatch(p)))
  val commitStartReady = Output(Bool())
  val commitReady = Output(Bool())
  val commitPrepared = Output(new OooPRenameCommitPrepared(p))
  val commitFire = Input(Bool())
  val commitBusy = Output(Bool())
  val commitStid = Output(UInt(p.stidWidth.W))
  val ptagReturn = Decoupled(new OooPTagReturnBatch(p))

  val queryStid = Input(UInt(p.stidWidth.W))
  val queryAtag = Input(UInt(p.archRegWidth.W))
  val speculativeMapping = Output(new PMapPayload(p))
  val committedMapping = Output(new PMapPayload(p))
  val mapQUsed = Output(Vec(p.stidCount, UInt(p.pMapQCountWidth.W)))
  val prepareRejected = Valid(new OooPRenamePrepareReject(p))
  val commitRejected = Valid(new OooPRenameCommitReject(p))

  val recoveryAuthorize = Flipped(Decoupled(
    new OooRenameRecoveryRequest(p)))
  val recoverySource = Flipped(Decoupled(new OooRenameRecoverySource(p)))
  val recoverySourcesDone = Input(Bool())
  val recoveryComplete = Output(Bool())
  val recoveryFinish = Input(Bool())
  val recoveryBusy = Output(Bool())
  val recoveryStid = Output(UInt(p.stidWidth.W))
  val recoveryRejected = Valid(new OooPRenameRecoveryReject(p))
}

/** P-register rename prepare and publication owner.
  *
  * The shared PTag pool owns physical allocation. This module consumes its
  * retained exact lease, resolves all P sources against the per-STID SMAP,
  * forwards older destinations across the complete transaction, and prepares
  * exact MapQ rows. SMAP and MapQ publish only on the O3 common publish fire.
  * A retained commit walk later advances CMAP and returns old PTags at the
  * independently parameterized return width before physical deallocation.
  */
class OooPRename(val p: OooParams = OooParams()) extends Module {
  val io = IO(new OooPRenameIO(p))

  private val destinationIndexWidth = math.max(1,
    chisel3.util.log2Ceil(p.maxDestinationOperands))
  private val archIndexWidth = math.max(1, chisel3.util.log2Ceil(p.pArchRegs))
  private val mapQReadWidth = math.min(
    p.pTagReturnWidth, p.pMapQSubbankCount)

  private def subMapQPtr(ptr: UInt, amount: UInt): UInt = {
    val difference = ptr - amount
    difference(p.pMapQIndexWidth - 1, 0)
  }

  private def identityMapping(stid: Int, atag: Int): PMapPayload = {
    val mapping = Wire(new PMapPayload(p))
    mapping := 0.U.asTypeOf(mapping)
    mapping.valid := true.B
    mapping.ptag := (stid * p.pArchRegs + atag).U
    mapping.ready := true.B
    mapping.stid := stid.U
    mapping
  }

  val smap = RegInit(VecInit((0 until p.stidCount).map { stid =>
    VecInit((0 until p.pArchRegs).map(atag => identityMapping(stid, atag)))
  }))
  val cmap = RegInit(VecInit((0 until p.stidCount).map { stid =>
    VecInit((0 until p.pArchRegs).map(atag => identityMapping(stid, atag)))
  }))
  val mapQ = RegInit(VecInit(Seq.fill(p.stidCount)(
    VecInit(Seq.fill(p.pMapQSubbankCount)(
      VecInit(Seq.fill(p.pMapQRowsPerSubbank)(
        0.U.asTypeOf(new OooPMapQEntry(p)))))))))
  private def mapQRow(stid: UInt, index: UInt): OooPMapQEntry = {
    if (p.pMapQSubbankCount == 1) {
      mapQ(stid)(0)(index)
    } else {
      val subbank = index(p.pMapQSubbankIndexBits - 1, 0)
      if (p.pMapQRowsPerSubbank == 1) {
        mapQ(stid)(subbank)(0)
      } else {
        val row = index(p.pMapQIndexWidth - 1, p.pMapQSubbankIndexBits)
        mapQ(stid)(subbank)(row)
      }
    }
  }
  val mapQTail = RegInit(VecInit(Seq.fill(p.stidCount)(
    0.U(p.pMapQIndexWidth.W))))
  val mapQHead = RegInit(VecInit(Seq.fill(p.stidCount)(
    0.U(p.pMapQIndexWidth.W))))
  val mapQCount = RegInit(VecInit(Seq.fill(p.stidCount)(
    0.U(p.pMapQCountWidth.W))))
  val commitState = RegInit(OooPCommitState.Idle)
  val commitBatch = RegInit(0.U.asTypeOf(new OooRobCommitBatch(p)))
  val commitRowsRemaining = RegInit(0.U(p.commitMapQRowCountWidth.W))
  val commitRowsTotal = RegInit(0.U(p.commitMapQRowCountWidth.W))
  val commitReadHead = RegInit(0.U(p.pMapQIndexWidth.W))
  val commitReadRowCount = RegInit(0.U(p.pTagReturnCountWidth.W))
  val commitReadRows = RegInit(VecInit(Seq.fill(p.pTagReturnWidth)(
    0.U.asTypeOf(new OooPMapQEntry(p)))))
  val commitActiveStid = commitBatch.release.firstGroup.stid
  io.commitBusy := commitState =/= OooPCommitState.Idle
  io.commitStid := Mux(io.commitBusy, commitActiveStid, 0.U)
  val recoveryState = RegInit(OooPRecoveryState.Idle)
  val recoveryRequest = RegInit(
    0.U.asTypeOf(new OooRenameRecoveryRequest(p)))
  val recoveryCurrentSource = RegInit(
    0.U.asTypeOf(new OooTURetireSource(p)))
  val recoveryRowsRemaining = RegInit(
    0.U(p.destinationCountWidth.W))
  val recoveryReadIndex = RegInit(0.U(p.pMapQIndexWidth.W))
  val recoveryReadRow = RegInit(0.U.asTypeOf(new OooPMapQEntry(p)))
  val recoveryReplayOffset = RegInit(0.U(p.pMapQCountWidth.W))
  val recoveryReplayRemaining = RegInit(0.U(p.pMapQCountWidth.W))
  val recoveryRejectValid = RegInit(false.B)
  val recoveryRejectRequest = RegInit(
    0.U.asTypeOf(new OooRenameRecoveryRequest(p)))
  val recoveryRejectTail = RegInit(0.U(p.pMapQIndexWidth.W))
  val recoveryRejectCount = RegInit(0.U(p.pMapQCountWidth.W))
  val recoveryActiveStid = recoveryRequest.key.member.group.stid
  io.recoveryBusy := recoveryState =/= OooPRecoveryState.Idle
  io.recoveryStid := Mux(io.recoveryBusy, recoveryActiveStid, 0.U)

  val reservation = io.prepare.bits.request.reservation
  val transaction = reservation.transaction
  val decoded = transaction.decoded
  val plan = transaction.plan
  val stidInRange = plan.stid < p.stidCount.U
  val safeStid = Mux(stidInRange, plan.stid, 0.U)

  val destinationActive = Wire(Vec(p.pTagAllocationWidth, Bool()))
  for (uopIndex <- 0 until p.decodedUopWidth;
       destinationIndex <- 0 until p.maxDestinationOperands) {
    val flatIndex = uopIndex * p.maxDestinationOperands + destinationIndex
    val uop = decoded.uops(uopIndex)
    val destination = uop.destinations(destinationIndex)
    destinationActive(flatIndex) := decoded.uopMask(uopIndex) && uop.valid &&
      destination.valid && destination.kind === DestinationKind.Gpr
  }
  val destinationCount = PopCount(destinationActive)

  val identityExact = plan.peId === decoded.peId &&
    plan.stid === decoded.stid && plan.epoch === decoded.epoch &&
    plan.uopMask === decoded.uopMask &&
    plan.demand.pDestinations === destinationCount &&
    plan.demand.mapQRows === destinationCount
  val leaseIdentityExact = io.ptagLease.valid &&
    io.ptagLease.peId === plan.peId && io.ptagLease.stid === plan.stid &&
    io.ptagLease.epoch === plan.epoch &&
    io.ptagLease.transactionId === plan.transactionId &&
    io.ptagLease.allocationMask === destinationActive.asUInt

  val leaseRowsExact = (0 until p.pTagAllocationWidth).map { flatIndex =>
    val uopIndex = flatIndex / p.maxDestinationOperands
    val destinationIndex = flatIndex % p.maxDestinationOperands
    val destination = decoded.uops(uopIndex).destinations(destinationIndex)
    val allocation = io.ptagLease.allocations(flatIndex)
    val tagInRange = allocation.token.ptag < p.pPhysRegs.U
    val bankExact = if (p.pTagBanks == 1) {
      allocation.token.bank === 0.U
    } else {
      allocation.token.bank ===
        allocation.token.ptag(p.pTagBankWidth - 1, 0)
    }
    Mux(destinationActive(flatIndex),
      allocation.valid && allocation.uopIndex === uopIndex.U &&
        allocation.destinationIndex === destinationIndex.U &&
        destination.atag < p.pArchRegs.U &&
        allocation.atag === destination.atag && allocation.token.valid &&
        tagInRange && bankExact,
      !allocation.valid)
  }.reduce(_ && _)
  val leaseTagsUnique = (0 until p.pTagAllocationWidth).map { index =>
    val allocation = io.ptagLease.allocations(index)
    (0 until index).map { older =>
      !allocation.valid || !io.ptagLease.allocations(older).valid ||
        allocation.token.ptag =/= io.ptagLease.allocations(older).token.ptag
    }.reduceOption(_ && _).getOrElse(true.B)
  }.reduce(_ && _)

  val dispatchDemandPresent = (0 until p.decodedUopWidth).map { uopIndex =>
    decoded.uopMask(uopIndex) && decoded.uops(uopIndex).valid &&
      decoded.uops(uopIndex).recipe.dispatchWrites.orR
  }.reduce(_ || _)
  val dispatchLeasePresent = !dispatchDemandPresent || io.dispatchLease.valid
  val dispatchLeaseIdentityExact = !io.dispatchLease.valid || (
    io.dispatchLease.peId === plan.peId &&
    io.dispatchLease.stid === plan.stid &&
    io.dispatchLease.epoch === plan.epoch &&
    io.dispatchLease.transactionId === plan.transactionId)
  val dispatchLeaseRowsExact = (0 until p.dispatchWidth).map { lane =>
    val allocation = io.dispatchLease.allocations(lane)
    val active = io.dispatchLease.allocationMask(lane)
    val uopIndexInRange = allocation.uopIndex < p.decodedUopWidth.U
    val safeUopIndex = Mux(uopIndexInRange, allocation.uopIndex, 0.U)
    !io.dispatchLease.valid || (allocation.valid === active && (!active || (
      uopIndexInRange && decoded.uopMask(safeUopIndex) &&
      decoded.uops(safeUopIndex).valid && allocation.reservation.valid &&
      allocation.reservation.uopClass.asUInt < p.iqClassCount.U &&
      allocation.reservation.bank < p.iqBankCount.U &&
      allocation.reservation.writePort < p.iqWritePortsPerBank.U &&
      allocation.reservation.speculativeSlot < p.iqEntriesPerBank.U)))
  }.reduce(_ && _)
  val dispatchedUopsBound = (0 until p.decodedUopWidth).map { uopIndex =>
    val uop = decoded.uops(uopIndex)
    val active = decoded.uopMask(uopIndex) && uop.valid &&
      uop.recipe.dispatchWrites.orR
    val allocationCount = PopCount(io.dispatchLease.allocations.map {
      allocation => allocation.valid && allocation.uopIndex === uopIndex.U
    })
    !active || (io.dispatchLease.valid &&
      allocationCount === uop.recipe.dispatchWrites)
  }.reduce(_ && _)

  val groupShapeExact = (0 until p.instructionDecodeWidth).map { groupIndex =>
    val group = transaction.groups(groupIndex)
    val membership = VecInit((0 until p.decodedUopWidth).map { uopIndex =>
      decoded.uopMask(uopIndex) && decoded.uops(uopIndex).valid &&
        transaction.uopGroupIndex(uopIndex) === groupIndex.U
    })
    val memberCount = (0 until p.decodedUopWidth).map { uopIndex =>
      Mux(membership(uopIndex), decoded.uops(uopIndex).plannedChildCount, 0.U)
    }.reduce(_ +& _)
    val pRows = (0 until p.decodedUopWidth).flatMap { uopIndex =>
      (0 until p.maxDestinationOperands).map { destinationIndex =>
        val destination = decoded.uops(uopIndex).destinations(destinationIndex)
        Mux(membership(uopIndex) && destination.valid &&
          destination.kind === DestinationKind.Gpr, 1.U, 0.U)
      }
    }.reduce(_ +& _)
    group.logicalUopMask === membership.asUInt &&
      group.physicalMemberCount === memberCount && group.pMapQRows === pRows
  }.reduce(_ && _)

  val memberBindingsExact = (0 until p.decodedUopWidth).map { uopIndex =>
    val active = decoded.uopMask(uopIndex) && decoded.uops(uopIndex).valid
    val groupIndex = transaction.uopGroupIndex(uopIndex)
    val groupIndexInRange = groupIndex < p.instructionDecodeWidth.U &&
      groupIndex < plan.groupCount
    val safeGroupIndex = Mux(groupIndexInRange, groupIndex, 0.U)
    val group = transaction.groups(safeGroupIndex)
    val binding = io.prepare.bits.request.bindings(safeGroupIndex)
    val childCount = decoded.uops(uopIndex).plannedChildCount
    val memberEnd = transaction.uopMemberBase(uopIndex) +& childCount
    val expectedMemberBase = (0 until uopIndex).map { olderIndex =>
      val olderActive = decoded.uopMask(olderIndex) &&
        decoded.uops(olderIndex).valid &&
        transaction.uopGroupIndex(olderIndex) === groupIndex
      Mux(olderActive, decoded.uops(olderIndex).plannedChildCount, 0.U)
    }.reduceOption(_ +& _).getOrElse(0.U)
    !active || (groupIndexInRange && group.valid && group.key.valid &&
      group.key.peId === plan.peId && group.key.stid === plan.stid &&
      group.logicalUopMask(uopIndex) && binding.valid &&
      childCount.orR && childCount <= p.maxOrdinaryUopsPerGroup.U &&
      transaction.uopMemberBase(uopIndex) === expectedMemberBase &&
      memberEnd <= group.physicalMemberCount &&
      group.physicalMemberCount <= p.maxOrdinaryUopsPerGroup.U &&
      binding.brob.valid && binding.brob.bid.valid && binding.pcBase.valid)
  }.reduce(_ && _)
  val freeRows = p.pMapQDepthPerStid.U - mapQCount(safeStid)
  val mapQAvailable = destinationCount <= freeRows
  val prepareExact = stidInRange && identityExact && leaseIdentityExact &&
    leaseRowsExact && leaseTagsUnique && dispatchLeasePresent &&
    dispatchLeaseIdentityExact && dispatchLeaseRowsExact &&
    dispatchedUopsBound && groupShapeExact && memberBindingsExact

  val commitBlocksPrepare = io.commitBusy && plan.stid === commitActiveStid
  val recoveryBlocksPrepare = io.recoveryBusy &&
    plan.stid === recoveryActiveStid
  io.prepareReady := prepareExact && mapQAvailable && !commitBlocksPrepare &&
    !recoveryBlocksPrepare
  io.prepared := 0.U.asTypeOf(io.prepared)
  io.prepared.valid := io.prepare.valid && io.prepareReady
  io.prepared.peId := plan.peId
  io.prepared.stid := plan.stid
  io.prepared.epoch := plan.epoch
  io.prepared.transactionId := plan.transactionId
  io.prepared.uopMask := decoded.uopMask
  io.prepared.mapQRowMask := destinationActive.asUInt

  val historyValid = ArrayBuffer.empty[Bool]
  val historyAtag = ArrayBuffer.empty[UInt]
  val historyMapping = ArrayBuffer.empty[PMapPayload]

  for (uopIndex <- 0 until p.decodedUopWidth) {
    val decodedUop = decoded.uops(uopIndex)
    val renamedUop = io.prepared.uops(uopIndex)
    val activeUop = decoded.uopMask(uopIndex) && decodedUop.valid
    val groupIndex = transaction.uopGroupIndex(uopIndex)
    val groupIndexInRange = groupIndex < p.instructionDecodeWidth.U &&
      groupIndex < plan.groupCount
    val safeGroupIndex = Mux(groupIndexInRange, groupIndex, 0.U)
    val group = transaction.groups(safeGroupIndex)
    val binding = io.prepare.bits.request.bindings(safeGroupIndex)

    renamedUop.valid := activeUop
    renamedUop.decoded := decodedUop
    renamedUop.member := 0.U.asTypeOf(renamedUop.member)
    renamedUop.member.group := group.key
    renamedUop.member.bid := binding.brob.bid
    renamedUop.member.brobGeneration := binding.brob.generation
    renamedUop.member.memberIndex := transaction.uopMemberBase(uopIndex)
    renamedUop.member.residentGeneration := binding.residentGeneration

    for (sourceIndex <- 0 until p.maxSourceOperands) {
      val source = decodedUop.sources(sourceIndex)
      val renamedSource = renamedUop.sources(sourceIndex)
      val atagInRange = source.atag < p.pArchRegs.U
      val safeAtag = Mux(atagInRange, source.atag, 0.U)(archIndexWidth - 1, 0)
      var resolved = smap(safeStid)(safeAtag)
      for (historyIndex <- historyValid.indices) {
        resolved = Mux(historyValid(historyIndex) &&
          historyAtag(historyIndex) === source.atag,
          historyMapping(historyIndex), resolved)
      }
      renamedSource.decoded := source
      renamedSource.pMapping := 0.U.asTypeOf(renamedSource.pMapping)
      when(activeUop && source.valid && source.operandClass === OperandClass.P &&
          atagInRange) {
        renamedSource.pMapping := resolved
      }
    }

    for (destinationIndex <- 0 until p.maxDestinationOperands) {
      val flatIndex = uopIndex * p.maxDestinationOperands + destinationIndex
      val destination = decodedUop.destinations(destinationIndex)
      val renamedDestination = renamedUop.destinations(destinationIndex)
      val allocation = io.ptagLease.allocations(flatIndex)
      val atagInRange = destination.atag < p.pArchRegs.U
      val safeAtag = Mux(atagInRange, destination.atag, 0.U)(archIndexWidth - 1, 0)
      var previous = smap(safeStid)(safeAtag)
      for (historyIndex <- historyValid.indices) {
        previous = Mux(historyValid(historyIndex) &&
          historyAtag(historyIndex) === destination.atag,
          historyMapping(historyIndex), previous)
      }

      val current = Wire(new PMapPayload(p))
      current := 0.U.asTypeOf(current)
      current.valid := destinationActive(flatIndex)
      current.ptag := allocation.token.ptag
      current.ptagGeneration := allocation.token.generation
      current.producerToken := plan.transactionId
      val producerMatches = VecInit(io.dispatchLease.allocations.map {
        dispatchAllocation => dispatchAllocation.valid &&
          dispatchAllocation.uopIndex === uopIndex.U &&
          dispatchAllocation.childIndex === 0.U
      })
      val producerReservation = Mux1H(producerMatches,
        io.dispatchLease.allocations.map(_.reservation))
      current.producerBindingValid := destinationActive(flatIndex) &&
        io.dispatchLease.valid && producerMatches.asUInt.orR
      current.producerIqClass := producerReservation.uopClass
      current.producerIqBank := producerReservation.bank
      current.producerIqEntry := producerReservation.speculativeSlot
      current.producerIqEpoch := producerReservation.reservationEpoch
      current.ready := false.B
      current.stid := plan.stid
      current.epoch := plan.epoch

      renamedDestination.decoded := destination
      renamedDestination.previousPMapping :=
        Mux(destinationActive(flatIndex), previous,
          0.U.asTypeOf(previous))
      renamedDestination.currentPMapping := current

      val row = io.prepared.mapQRows(flatIndex)
      val olderRows = if (flatIndex == 0) 0.U else
        PopCount(destinationActive.take(flatIndex))
      row.valid := destinationActive(flatIndex)
      row.mapQIndex := mapQTail(safeStid) + olderRows
      row.transactionId := plan.transactionId
      row.uopIndex := uopIndex.U
      row.destinationIndex := destinationIndex.U(destinationIndexWidth.W)
      row.member := renamedUop.member
      row.atag := destination.atag
      row.previous := renamedDestination.previousPMapping
      row.current := current

      historyValid += destinationActive(flatIndex)
      historyAtag += destination.atag
      historyMapping += current
    }
  }

  io.prepareRejected.valid := io.prepare.valid && !io.prepareReady
  io.prepareRejected.bits.stid := plan.stid
  io.prepareRejected.bits.transactionId := plan.transactionId
  io.prepareRejected.bits.requestedRows := destinationCount
  io.prepareRejected.bits.freeRows := freeRows
  io.prepareRejected.bits.lease := io.ptagLease

  val publishExact = io.publishFire && io.prepare.valid &&
    io.prepareReady && io.prepared.valid
  when(io.publishFire) {
    assert(io.prepare.valid && io.prepareReady && io.prepared.valid,
      "P rename publishFire requires one exact prepared transaction")
  }
  when(publishExact) {
    for (flatIndex <- 0 until p.pTagAllocationWidth) {
      val row = io.prepared.mapQRows(flatIndex)
      when(row.valid) {
        mapQRow(safeStid, row.mapQIndex) := row
        smap(safeStid)(row.atag(archIndexWidth - 1, 0)) := row.current
      }
    }
    mapQTail(safeStid) := mapQTail(safeStid) + destinationCount
    mapQCount(safeStid) := mapQCount(safeStid) + destinationCount
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
      ((1.U((p.maxOrdinaryUopsPerGroup + 1).W) << record.physicalMemberCount) - 1.U)(
        p.maxOrdinaryUopsPerGroup - 1, 0)
    record.valid === active && (!active || (record.key.valid &&
      record.key.peId === incomingBatch.release.firstGroup.peId &&
      record.key.stid === incomingStid &&
      record.key.ridSlot === slotSum(p.ridSlotWidth - 1, 0) &&
      record.key.ridGeneration ===
        incomingBatch.release.firstGroup.ridGeneration + wraps.asUInt &&
      record.brob.valid && record.brob.bid.valid && record.pcBase.valid &&
      record.physicalMemberCount.orR &&
      record.completedMembers === completedMask &&
      record.pMapQRows <= p.maxCommitMapQRows.U))
  }.reduce(_ && _)
  val incomingExpectedRows = (0 until p.retireGroupWidth).map { groupIndex =>
    Mux(groupIndex.U < incomingGroupCount,
      incomingBatch.groups(groupIndex).pMapQRows, 0.U)
  }.reduce(_ +& _)
  val incomingRowsInRange = incomingExpectedRows <= p.maxCommitMapQRows.U &&
    incomingExpectedRows <= mapQCount(safeIncomingStid)

  val incomingRows = Wire(Vec(p.maxCommitMapQRows, new OooPMapQEntry(p)))
  val incomingRowActive = Wire(Vec(p.maxCommitMapQRows, Bool()))
  val incomingRowMatches = Wire(Vec(p.maxCommitMapQRows,
    Vec(p.retireGroupWidth, Bool())))
  for (rowIndex <- 0 until p.maxCommitMapQRows) {
    val queueIndex = mapQHead(safeIncomingStid) + rowIndex.U
    incomingRows(rowIndex) :=
      mapQRow(safeIncomingStid, queueIndex(p.pMapQIndexWidth - 1, 0))
    incomingRowActive(rowIndex) := rowIndex.U < incomingExpectedRows
    for (groupIndex <- 0 until p.retireGroupWidth) {
      val group = incomingBatch.groups(groupIndex)
      val row = incomingRows(rowIndex)
      val uopIndexInRange = row.uopIndex < p.decodedUopWidth.U
      val safeUopIndex = Mux(uopIndexInRange, row.uopIndex, 0.U)
      incomingRowMatches(rowIndex)(groupIndex) :=
        groupIndex.U < incomingGroupCount && group.valid && row.valid &&
          row.member.group.asUInt === group.key.asUInt &&
          row.member.bid.asUInt === group.brob.bid.asUInt &&
          row.member.brobGeneration === group.brob.generation &&
          row.member.residentGeneration === group.residentGeneration &&
          row.transactionId === group.transactionId &&
          row.member.memberIndex < group.physicalMemberCount &&
          uopIndexInRange && group.logicalUopMask(safeUopIndex) &&
          row.destinationIndex < p.maxDestinationOperands.U
    }
  }
  val incomingGroupRowsExact = (0 until p.retireGroupWidth).map { groupIndex =>
    val active = groupIndex.U < incomingGroupCount
    val count = PopCount((0 until p.maxCommitMapQRows).map { rowIndex =>
      incomingRowActive(rowIndex) && incomingRowMatches(rowIndex)(groupIndex)
    })
    !active || count === incomingBatch.groups(groupIndex).pMapQRows
  }.reduce(_ && _)
  val incomingRowsExact = (0 until p.maxCommitMapQRows).map { rowIndex =>
    val row = incomingRows(rowIndex)
    val queueIndex = mapQHead(safeIncomingStid) + rowIndex.U
    val atagInRange = row.atag < p.pArchRegs.U
    val safeAtag = Mux(atagInRange, row.atag, 0.U)(archIndexWidth - 1, 0)
    var expectedPrevious = cmap(safeIncomingStid)(safeAtag)
    for (olderIndex <- 0 until rowIndex) {
      val older = incomingRows(olderIndex)
      expectedPrevious = Mux(incomingRowActive(olderIndex) &&
        older.atag === row.atag, older.current, expectedPrevious)
    }
    val matchesOne = PopCount(incomingRowMatches(rowIndex)) === 1.U
    !incomingRowActive(rowIndex) || (row.valid &&
      row.mapQIndex === queueIndex(p.pMapQIndexWidth - 1, 0) &&
      atagInRange && matchesOne &&
      row.previous.valid && row.current.valid &&
      row.previous.ptag === expectedPrevious.ptag &&
      row.previous.ptagGeneration === expectedPrevious.ptagGeneration &&
      row.previous.stid === expectedPrevious.stid &&
      row.previous.epoch === expectedPrevious.epoch &&
      row.current.stid === incomingStid &&
      row.current.producerToken === row.transactionId &&
      row.previous.ptag =/= row.current.ptag &&
      row.current.ptag < p.pPhysRegs.U)
  }.reduce(_ && _)
  val incomingReturnsUnique = (0 until p.maxCommitMapQRows).map { rowIndex =>
    val row = incomingRows(rowIndex)
    val returnsTag = incomingRowActive(rowIndex)
    (0 until rowIndex).map { olderIndex =>
      val older = incomingRows(olderIndex)
      val olderReturns = incomingRowActive(olderIndex)
      !returnsTag || !olderReturns || row.previous.ptag =/= older.previous.ptag
    }.reduceOption(_ && _).getOrElse(true.B)
  }.reduce(_ && _)
  val incomingGroupOrderExact = (1 until p.maxCommitMapQRows).map { rowIndex =>
    !incomingRowActive(rowIndex) || !incomingRowActive(rowIndex - 1) ||
      PriorityEncoder(incomingRowMatches(rowIndex - 1).asUInt) <=
        PriorityEncoder(incomingRowMatches(rowIndex).asUInt)
  }.reduceOption(_ && _).getOrElse(true.B)
  val lateQueueIndex = mapQHead(safeIncomingStid) + incomingExpectedRows
  val lateRow = mapQRow(safeIncomingStid,
    lateQueueIndex(p.pMapQIndexWidth - 1, 0))
  val lateRowMatches = (0 until p.retireGroupWidth).map { groupIndex =>
    val group = incomingBatch.groups(groupIndex)
    groupIndex.U < incomingGroupCount && group.valid && lateRow.valid &&
      lateRow.member.group.asUInt === group.key.asUInt &&
      lateRow.member.bid.asUInt === group.brob.bid.asUInt &&
      lateRow.member.brobGeneration === group.brob.generation &&
      lateRow.member.residentGeneration === group.residentGeneration &&
      lateRow.transactionId === group.transactionId
  }.reduce(_ || _)
  val noLateBatchRow = mapQCount(safeIncomingStid) === incomingExpectedRows ||
    !lateRowMatches
  val incomingCommitExact = incomingStidInRange &&
    incomingGroupCountInRange && incomingBatch.release.firstGroup.valid &&
    incomingGroupsExact && incomingRowsInRange && incomingGroupRowsExact &&
    incomingRowsExact && incomingReturnsUnique && incomingGroupOrderExact &&
    noLateBatchRow

  val commitBatchRetained = io.commitPrepare.valid &&
    io.commitPrepare.bits.asUInt === commitBatch.asUInt
  io.commitStartReady := commitState === OooPCommitState.Idle &&
    recoveryState === OooPRecoveryState.Idle &&
    incomingCommitExact
  val commitStart = commitState === OooPCommitState.Idle &&
    recoveryState === OooPRecoveryState.Idle &&
    io.commitPrepare.valid && incomingCommitExact
  when(commitStart) {
    commitBatch := incomingBatch
    commitRowsRemaining := incomingExpectedRows
    commitRowsTotal := incomingExpectedRows
    commitState := Mux(incomingExpectedRows.orR,
      OooPCommitState.ReadMapQ, OooPCommitState.AwaitDeallocation)
  }
  when(io.commitBusy) {
    assert(commitBatchRetained,
      "ROB must retain the exact P commit batch through MapQ drain")
  }

  // Read at most one logical row from each physical subbank, then retain the
  // complete slice before any pointer or CMAP update. This is the registered
  // boundary that breaks the MapQ entry-read-to-head-update loop.
  val chunkRowCount = Mux(commitRowsRemaining > mapQReadWidth.U,
    mapQReadWidth.U, commitRowsRemaining)
  val chunkRows = Wire(Vec(p.pTagReturnWidth, new OooPMapQEntry(p)))
  val chunkActive = Wire(Vec(p.pTagReturnWidth, Bool()))
  val chunkMatches = Wire(Vec(p.pTagReturnWidth,
    Vec(p.retireGroupWidth, Bool())))
  for (lane <- 0 until p.pTagReturnWidth) {
    if (lane < mapQReadWidth) {
      val queueIndex = mapQHead(commitActiveStid) + lane.U
      chunkRows(lane) :=
        mapQRow(commitActiveStid, queueIndex(p.pMapQIndexWidth - 1, 0))
      chunkActive(lane) := lane.U < chunkRowCount
      for (groupIndex <- 0 until p.retireGroupWidth) {
        val group = commitBatch.groups(groupIndex)
        val row = chunkRows(lane)
        val uopIndexInRange = row.uopIndex < p.decodedUopWidth.U
        val safeUopIndex = Mux(uopIndexInRange, row.uopIndex, 0.U)
        chunkMatches(lane)(groupIndex) :=
          groupIndex.U < commitBatch.release.groupCount && group.valid && row.valid &&
            row.member.group.asUInt === group.key.asUInt &&
            row.member.bid.asUInt === group.brob.bid.asUInt &&
            row.member.brobGeneration === group.brob.generation &&
            row.member.residentGeneration === group.residentGeneration &&
            row.transactionId === group.transactionId &&
            row.member.memberIndex < group.physicalMemberCount &&
            uopIndexInRange && group.logicalUopMask(safeUopIndex) &&
            row.destinationIndex < p.maxDestinationOperands.U
      }
    } else {
      chunkRows(lane) := 0.U.asTypeOf(chunkRows(lane))
      chunkActive(lane) := false.B
      chunkMatches(lane) := VecInit(Seq.fill(p.retireGroupWidth)(false.B))
    }
  }
  val chunkExact = (0 until p.pTagReturnWidth).map { lane =>
    val row = chunkRows(lane)
    val queueIndex = mapQHead(commitActiveStid) + lane.U
    val atagInRange = row.atag < p.pArchRegs.U
    val safeAtag = Mux(atagInRange, row.atag, 0.U)(archIndexWidth - 1, 0)
    var expectedPrevious = cmap(commitActiveStid)(safeAtag)
    for (older <- 0 until lane) {
      expectedPrevious = Mux(chunkActive(older) &&
        chunkRows(older).atag === row.atag,
        chunkRows(older).current, expectedPrevious)
    }
    !chunkActive(lane) || (row.valid &&
      row.mapQIndex === queueIndex(p.pMapQIndexWidth - 1, 0) &&
      atagInRange &&
      PopCount(chunkMatches(lane)) === 1.U && row.previous.valid &&
      row.current.valid && row.previous.ptag === expectedPrevious.ptag &&
      row.previous.ptagGeneration === expectedPrevious.ptagGeneration &&
      row.previous.stid === expectedPrevious.stid &&
      row.previous.epoch === expectedPrevious.epoch &&
      row.previous.ptag =/= row.current.ptag)
  }.reduce(_ && _)
  val chunkReturnCount = PopCount(chunkActive)
  val commitReadCapture = commitState === OooPCommitState.ReadMapQ &&
    commitBatchRetained && chunkExact && chunkReturnCount.orR
  when(commitState === OooPCommitState.ReadMapQ) {
    assert(commitRowsRemaining.orR && chunkExact && chunkReturnCount.orR,
      "P commit MapQ read stage must identify an exact non-empty slice")
  }
  when(commitReadCapture) {
    commitReadHead := mapQHead(commitActiveStid)
    commitReadRowCount := chunkReturnCount
    commitReadRows := chunkRows
    commitState := OooPCommitState.ReturnTags
  }

  val commitRegisteredRowsExact =
    commitReadRowCount.orR &&
    commitReadRowCount <= mapQReadWidth.U &&
    commitRowsRemaining >= commitReadRowCount &&
    (0 until p.pTagReturnWidth).map { lane =>
      val active = lane.U < commitReadRowCount
      val queueIndex = commitReadHead + lane.U
      val row = commitReadRows(lane)
      !active || (row.valid &&
        row.mapQIndex === queueIndex(p.pMapQIndexWidth - 1, 0))
    }.reduce(_ && _)
  val commitLiveRowsStable = mapQHead(commitActiveStid) === commitReadHead &&
    (0 until mapQReadWidth).map { lane =>
      val active = lane.U < commitReadRowCount
      val queueIndex = commitReadHead + lane.U
      !active || (mapQRow(commitActiveStid,
        queueIndex(p.pMapQIndexWidth - 1, 0)).asUInt ===
          commitReadRows(lane).asUInt)
    }.reduce(_ && _)
  when(commitState === OooPCommitState.ReturnTags) {
    assert(commitBatchRetained && commitRegisteredRowsExact &&
      commitLiveRowsStable,
      "P commit retained MapQ slice must remain exact until PTag return")
  }

  io.ptagReturn.valid := commitState === OooPCommitState.ReturnTags &&
    commitRegisteredRowsExact
  io.ptagReturn.bits := 0.U.asTypeOf(io.ptagReturn.bits)
  io.ptagReturn.bits.count := commitReadRowCount
  for (returnIndex <- 0 until p.pTagReturnWidth) {
    val selected = commitReadRows(returnIndex)
    val token = io.ptagReturn.bits.tokens(returnIndex)
    token.valid := returnIndex.U < commitReadRowCount
    token.ptag := selected.previous.ptag
    token.generation := selected.previous.ptagGeneration
    token.bank := (if (p.pTagBanks == 1) 0.U else
      selected.previous.ptag(p.pTagBankWidth - 1, 0))
  }
  val chunkAdvance = commitState === OooPCommitState.ReturnTags &&
    io.ptagReturn.fire
  when(chunkAdvance) {
    for (lane <- 0 until p.pTagReturnWidth) {
      when(lane.U < commitReadRowCount) {
        val row = commitReadRows(lane)
        val committedMapping = Wire(new PMapPayload(p))
        committedMapping := row.current
        committedMapping.ready := true.B
        committedMapping.producerBindingValid := false.B
        committedMapping.producerIqClass := OooUopClass.Alu
        committedMapping.producerIqBank := 0.U
        committedMapping.producerIqEntry := 0.U
        committedMapping.producerIqEpoch := 0.U
        cmap(commitActiveStid)(row.atag(archIndexWidth - 1, 0)) :=
          committedMapping
        val queueIndex = commitReadHead + lane.U
        mapQRow(commitActiveStid,
          queueIndex(p.pMapQIndexWidth - 1, 0)).valid := false.B
      }
    }
    mapQHead(commitActiveStid) := commitReadHead + commitReadRowCount
    mapQCount(commitActiveStid) :=
      mapQCount(commitActiveStid) - commitReadRowCount
    commitRowsRemaining := commitRowsRemaining - commitReadRowCount
    commitReadRowCount := 0.U
    when(commitRowsRemaining === commitReadRowCount) {
      commitState := OooPCommitState.AwaitDeallocation
    }.otherwise {
      commitState := OooPCommitState.ReadMapQ
    }
  }

  io.commitReady := commitState === OooPCommitState.AwaitDeallocation &&
    commitBatchRetained
  io.commitPrepared := 0.U.asTypeOf(io.commitPrepared)
  io.commitPrepared.valid := io.commitReady
  io.commitPrepared.stid := commitActiveStid
  io.commitPrepared.mapQRowCount := commitRowsTotal
  io.commitRejected.valid := commitState === OooPCommitState.Idle &&
    io.commitPrepare.valid && !incomingCommitExact
  io.commitRejected.bits.requested := io.commitPrepare.bits
  io.commitRejected.bits.mapQHead := mapQHead(safeIncomingStid)
  io.commitRejected.bits.mapQCount := mapQCount(safeIncomingStid)

  val commitExactFire = io.commitFire && io.commitReady
  when(io.commitFire) {
    assert(io.commitReady,
      "P rename deallocation fire requires completed retained MapQ commit")
  }
  when(commitExactFire) {
    commitState := OooPCommitState.Idle
    commitRowsRemaining := 0.U
    commitRowsTotal := 0.U
  }
  when(publishExact && io.commitBusy) {
    assert(safeStid =/= commitActiveStid,
      "same-STID P rename publication must wait for commit deallocation")
  }

  when(publishExact && io.recoveryBusy) {
    assert(safeStid =/= recoveryActiveStid,
      "same-STID P rename publication must wait for recovery rebuild")
  }

  recoveryRejectValid := false.B
  val incomingRecovery = io.recoveryAuthorize.bits
  val incomingRecoveryStid = incomingRecovery.key.member.group.stid
  val incomingRecoveryStidInRange = incomingRecoveryStid < p.stidCount.U
  val safeIncomingRecoveryStid = Mux(incomingRecoveryStidInRange,
    incomingRecoveryStid, 0.U)
  val incomingRecoveryShapeExact = incomingRecoveryStidInRange &&
    incomingRecovery.key.member.group.valid &&
    incomingRecovery.key.member.bid.valid
  val recoveryConflictsPrepare = io.prepare.valid && io.prepareReady &&
    plan.stid === incomingRecoveryStid
  io.recoveryAuthorize.ready := recoveryState === OooPRecoveryState.Idle &&
    commitState === OooPCommitState.Idle && !io.commitPrepare.valid &&
    !recoveryConflictsPrepare
  val recoveryAuthorizeFire = io.recoveryAuthorize.fire
  when(recoveryAuthorizeFire) {
    when(incomingRecoveryShapeExact) {
      recoveryRequest := incomingRecovery
      recoveryRowsRemaining := 0.U
      recoveryReplayOffset := 0.U
      recoveryReplayRemaining := 0.U
      recoveryState := OooPRecoveryState.WaitSources
    }.otherwise {
      recoveryRejectValid := true.B
      recoveryRejectRequest := incomingRecovery
      recoveryRejectTail := mapQTail(safeIncomingRecoveryStid)
      recoveryRejectCount := mapQCount(safeIncomingRecoveryStid)
    }
  }

  val killed = io.recoverySource.bits.source
  val killedCountInRange = killed.pDestinationCount <=
    p.maxDestinationOperands.U &&
    killed.pDestinationCount <= mapQCount(recoveryActiveStid)
  val killedRowsExact = (0 until p.maxDestinationOperands).map { offset =>
    val active = offset.U < killed.pDestinationCount
    val rowIndex = subMapQPtr(mapQTail(recoveryActiveStid), offset.U + 1.U)
    val row = mapQRow(recoveryActiveStid, rowIndex)
    val atagInRange = row.atag < p.pArchRegs.U
    !active || (row.valid && row.mapQIndex === rowIndex && atagInRange &&
      row.transactionId === killed.transactionId &&
      row.uopIndex === killed.uopIndex &&
      row.destinationIndex < p.maxDestinationOperands.U &&
      row.member.asUInt === killed.member.asUInt && row.current.valid &&
      row.current.ptag < p.pPhysRegs.U &&
      row.current.stid === recoveryActiveStid &&
      row.current.epoch === killed.epoch &&
      row.current.producerToken === killed.transactionId)
  }.reduce(_ && _)
  val nextOlderIndex = subMapQPtr(mapQTail(recoveryActiveStid),
    killed.pDestinationCount + 1.U)
  val nextOlderRow = mapQRow(recoveryActiveStid, nextOlderIndex)
  val nextOlderMatchesKilled = mapQCount(recoveryActiveStid) >
    killed.pDestinationCount && nextOlderRow.valid &&
    nextOlderRow.transactionId === killed.transactionId &&
    nextOlderRow.uopIndex === killed.uopIndex &&
    nextOlderRow.member.asUInt === killed.member.asUInt
  val killedSourceExact = io.recoverySource.bits.request.asUInt ===
    recoveryRequest.asUInt && killed.valid &&
    killed.member.group.stid === recoveryActiveStid &&
    killedCountInRange && killedRowsExact && !nextOlderMatchesKilled
  io.recoverySource.ready :=
    recoveryState === OooPRecoveryState.WaitSources && killedSourceExact
  val recoverySourceFire = io.recoverySource.fire
  when(recoveryState === OooPRecoveryState.WaitSources &&
      io.recoverySource.valid) {
    assert(killedSourceExact,
      "P recovery source must own the exact MapQ tail rows")
  }

  val recoveryDrainIndex = subMapQPtr(mapQTail(recoveryActiveStid), 1.U)
  val recoveryDrainRow = mapQRow(recoveryActiveStid, recoveryDrainIndex)
  val recoveryDrainExact = recoveryDrainRow.valid &&
    recoveryDrainRow.mapQIndex === recoveryDrainIndex &&
    recoveryDrainRow.transactionId === recoveryCurrentSource.transactionId &&
    recoveryDrainRow.uopIndex === recoveryCurrentSource.uopIndex &&
    recoveryDrainRow.member.asUInt === recoveryCurrentSource.member.asUInt &&
    recoveryDrainRow.current.valid &&
    recoveryDrainRow.current.ptag < p.pPhysRegs.U &&
    recoveryDrainRow.current.stid === recoveryActiveStid &&
    recoveryDrainRow.current.epoch === recoveryCurrentSource.epoch
  val recoveryReadCapture = recoveryState === OooPRecoveryState.ReadRow &&
    recoveryRowsRemaining.orR && recoveryDrainExact
  when(recoveryState === OooPRecoveryState.ReadRow) {
    assert(recoveryRowsRemaining.orR && recoveryDrainExact,
      "P recovery MapQ read stage must identify the exact killed tail row")
  }
  when(recoveryReadCapture) {
    recoveryReadIndex := recoveryDrainIndex
    recoveryReadRow := recoveryDrainRow
    recoveryState := OooPRecoveryState.ReturnTag
  }
  val recoveryRegisteredRowExact = recoveryRowsRemaining.orR &&
    recoveryReadRow.valid &&
    recoveryReadRow.mapQIndex === recoveryReadIndex &&
    recoveryReadRow.transactionId === recoveryCurrentSource.transactionId &&
    recoveryReadRow.uopIndex === recoveryCurrentSource.uopIndex &&
    recoveryReadRow.member.asUInt === recoveryCurrentSource.member.asUInt &&
    recoveryReadRow.current.valid &&
    recoveryReadRow.current.ptag < p.pPhysRegs.U &&
    recoveryReadRow.current.stid === recoveryActiveStid &&
    recoveryReadRow.current.epoch === recoveryCurrentSource.epoch
  val recoveryLiveRowStable =
    subMapQPtr(mapQTail(recoveryActiveStid), 1.U) === recoveryReadIndex &&
    mapQRow(recoveryActiveStid, recoveryReadIndex).asUInt ===
      recoveryReadRow.asUInt
  when(recoveryState === OooPRecoveryState.ReturnTag) {
    assert(recoveryRegisteredRowExact && recoveryLiveRowStable,
      "P recovery retained MapQ row must remain exact until PTag return")
    io.ptagReturn.valid := recoveryRegisteredRowExact
    io.ptagReturn.bits := 0.U.asTypeOf(io.ptagReturn.bits)
    io.ptagReturn.bits.count := 1.U
    io.ptagReturn.bits.tokens(0).valid := true.B
    io.ptagReturn.bits.tokens(0).ptag := recoveryReadRow.current.ptag
    io.ptagReturn.bits.tokens(0).generation :=
      recoveryReadRow.current.ptagGeneration
    io.ptagReturn.bits.tokens(0).bank := (if (p.pTagBanks == 1) 0.U else
      recoveryReadRow.current.ptag(p.pTagBankWidth - 1, 0))
  }
  val recoveryDrainFire = recoveryState === OooPRecoveryState.ReturnTag &&
    io.ptagReturn.fire

  val replayIndexSum = mapQHead(recoveryActiveStid) +& recoveryReplayOffset
  val replayIndex = replayIndexSum(p.pMapQIndexWidth - 1, 0)
  val replayRow = mapQRow(recoveryActiveStid, replayIndex)
  val replayRowExact = replayRow.valid && replayRow.mapQIndex === replayIndex &&
    replayRow.atag < p.pArchRegs.U && replayRow.current.valid &&
    replayRow.current.stid === recoveryActiveStid &&
    replayRow.current.ptag < p.pPhysRegs.U

  switch(recoveryState) {
    is(OooPRecoveryState.WaitSources) {
      when(recoverySourceFire) {
        recoveryCurrentSource := killed
        recoveryRowsRemaining := killed.pDestinationCount
        when(killed.pDestinationCount.orR) {
          recoveryState := OooPRecoveryState.ReadRow
        }
      }.elsewhen(io.recoverySourcesDone) {
        recoveryState := OooPRecoveryState.CopyCmap
      }
    }
    is(OooPRecoveryState.ReturnTag) {
      when(recoveryDrainFire) {
        mapQRow(recoveryActiveStid, recoveryReadIndex) :=
          0.U.asTypeOf(new OooPMapQEntry(p))
        mapQTail(recoveryActiveStid) := recoveryReadIndex
        mapQCount(recoveryActiveStid) :=
          mapQCount(recoveryActiveStid) - 1.U
        recoveryRowsRemaining := recoveryRowsRemaining - 1.U
        when(recoveryRowsRemaining === 1.U) {
          recoveryState := OooPRecoveryState.WaitSources
        }.otherwise {
          recoveryState := OooPRecoveryState.ReadRow
        }
      }
    }
    is(OooPRecoveryState.CopyCmap) {
      for (atag <- 0 until p.pArchRegs) {
        smap(recoveryActiveStid)(atag) := cmap(recoveryActiveStid)(atag)
      }
      recoveryReplayOffset := 0.U
      recoveryReplayRemaining := mapQCount(recoveryActiveStid)
      recoveryState := Mux(mapQCount(recoveryActiveStid).orR,
        OooPRecoveryState.ReplaySurvivors, OooPRecoveryState.Complete)
    }
    is(OooPRecoveryState.ReplaySurvivors) {
      assert(recoveryReplayRemaining.orR && replayRowExact,
        "P recovery replay must walk the exact surviving MapQ prefix")
      smap(recoveryActiveStid)(replayRow.atag(archIndexWidth - 1, 0)) :=
        replayRow.current
      recoveryReplayOffset := recoveryReplayOffset + 1.U
      recoveryReplayRemaining := recoveryReplayRemaining - 1.U
      when(recoveryReplayRemaining === 1.U) {
        recoveryState := OooPRecoveryState.Complete
      }
    }
    is(OooPRecoveryState.Complete) {
      when(io.recoveryFinish) {
        recoveryState := OooPRecoveryState.Idle
      }
    }
  }

  io.recoveryComplete := recoveryState === OooPRecoveryState.Complete
  io.recoveryRejected.valid := recoveryRejectValid
  io.recoveryRejected.bits.requested := recoveryRejectRequest
  io.recoveryRejected.bits.mapQTail := recoveryRejectTail
  io.recoveryRejected.bits.mapQCount := recoveryRejectCount
  when(io.recoveryFinish) {
    assert(io.recoveryComplete,
      "P recovery may finish only after CMAP copy and survivor replay")
  }

  val queryStidInRange = io.queryStid < p.stidCount.U
  val queryAtagInRange = io.queryAtag < p.pArchRegs.U
  val safeQueryStid = Mux(queryStidInRange, io.queryStid, 0.U)
  val safeQueryAtag = Mux(queryAtagInRange, io.queryAtag, 0.U)(archIndexWidth - 1, 0)
  io.speculativeMapping := Mux(queryStidInRange && queryAtagInRange,
    smap(safeQueryStid)(safeQueryAtag),
    0.U.asTypeOf(new PMapPayload(p)))
  io.committedMapping := Mux(queryStidInRange && queryAtagInRange,
    cmap(safeQueryStid)(safeQueryAtag),
    0.U.asTypeOf(new PMapPayload(p)))
  io.mapQUsed := mapQCount
}
