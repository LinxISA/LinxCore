package linxcore.ooo

import chisel3._
import chisel3.util.{PopCount, Valid}
import linxcore.common.{DestinationKind, OperandClass}

import scala.collection.mutable.ArrayBuffer

class OooProductionPRenameIO(val p: OooParams = OooParams()) extends Bundle {
  val prepare = Flipped(Valid(new OooO3PreparedPublication(p)))
  val ptagLease = Input(new OooPTagReservation(p))
  val prepareReady = Output(Bool())
  val prepared = Output(new OooPRenamePreparedTransaction(p))
  val publishFire = Input(Bool())

  val queryStid = Input(UInt(p.stidWidth.W))
  val queryAtag = Input(UInt(p.archRegWidth.W))
  val speculativeMapping = Output(new PMapPayload(p))
  val committedMapping = Output(new PMapPayload(p))
  val mapQUsed = Output(Vec(p.stidCount, UInt(p.pMapQCountWidth.W)))
  val prepareRejected = Valid(new OooPRenamePrepareReject(p))
}

/** Production P-register rename prepare and publication owner.
  *
  * The shared PTag pool owns physical allocation. This module consumes its
  * retained exact lease, resolves all P sources against the per-STID SMAP,
  * forwards older destinations across the complete transaction, and prepares
  * exact MapQ rows. SMAP and MapQ mutate only on the O3 common publish fire.
  * CMAP is deliberately unchanged here; exact commit/recovery is a separate
  * owner joined to the common retirement transaction.
  */
class OooProductionPRename(val p: OooParams = OooParams()) extends Module {
  val io = IO(new OooProductionPRenameIO(p))

  private val committedTagCount = p.stidCount * p.pArchRegs
  private val destinationIndexWidth = math.max(1,
    chisel3.util.log2Ceil(p.maxDestinationOperands))
  private val archIndexWidth = math.max(1, chisel3.util.log2Ceil(p.pArchRegs))

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
    VecInit(Seq.fill(p.pMapQDepthPerStid)(
      0.U.asTypeOf(new OooPMapQEntry(p)))))))
  val mapQTail = RegInit(VecInit(Seq.fill(p.stidCount)(
    0.U(p.pMapQIndexWidth.W))))
  val mapQCount = RegInit(VecInit(Seq.fill(p.stidCount)(
    0.U(p.pMapQCountWidth.W))))

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
    val tagInRange = allocation.token.ptag >= committedTagCount.U &&
      allocation.token.ptag < p.pPhysRegs.U
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

  val groupShapeExact = (0 until p.instructionDecodeWidth).map { groupIndex =>
    val group = transaction.groups(groupIndex)
    val membership = VecInit((0 until p.decodedUopWidth).map { uopIndex =>
      decoded.uopMask(uopIndex) && decoded.uops(uopIndex).valid &&
        transaction.uopGroupIndex(uopIndex) === groupIndex.U
    })
    val memberCount = (0 until p.decodedUopWidth).map { uopIndex =>
      Mux(membership(uopIndex), decoded.uops(uopIndex).plannedChildCount, 0.U)
    }.reduce(_ +& _)
    group.logicalUopMask === membership.asUInt &&
      group.physicalMemberCount === memberCount
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
    leaseRowsExact && leaseTagsUnique && groupShapeExact && memberBindingsExact

  io.prepareReady := prepareExact && mapQAvailable
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
      // O5 supplies the exact IQ binding at dispatch publication.
      current.producerBindingValid := false.B
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
        mapQ(safeStid)(row.mapQIndex) := row
        smap(safeStid)(row.atag(archIndexWidth - 1, 0)) := row.current
      }
    }
    mapQTail(safeStid) := mapQTail(safeStid) + destinationCount
    mapQCount(safeStid) := mapQCount(safeStid) + destinationCount
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
