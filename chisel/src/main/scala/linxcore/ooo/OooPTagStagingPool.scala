package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, PopCount, PriorityEncoder, UIntToOH, Valid}
import linxcore.common.DestinationKind

class OooPTagStagingPoolIO(val p: OooParams = OooParams()) extends Bundle {
  val prepare = Flipped(Valid(new OooD2GroupedTransaction(p)))
  val prepareReady = Output(Bool())
  val prepared = Output(new OooPTagReservation(p))
  val reserveFire = Input(Bool())

  val cancel = Input(Vec(p.stidCount, Bool()))
  val publish = Flipped(Valid(new OooPTagPublish(p)))
  val release = Flipped(Decoupled(new OooPTagReturnBatch(p)))

  val provisional = Output(Vec(p.stidCount, new OooPTagReservation(p)))
  val freeCount = Output(UInt(p.countWidth(p.pPhysRegs).W))
  val stagedCount = Output(Vec(p.pTagBanks,
    UInt(p.countWidth(p.pTagStagingDepthPerBank).W)))
  val provisionalCount = Output(UInt(p.countWidth(p.pPhysRegs).W))
  val publishedCount = Output(UInt(p.countWidth(p.pPhysRegs).W))
  val conservationValid = Output(Bool())

  val prepareRejected = Valid(new OooPTagPrepareReject(p))
  val publishRejected = Valid(new OooPTagPublishReject(p))
  val returnRejected = Valid(new OooPTagReturnReject(p))
}

/** Shared banked PTag free list and D2-refilled staging owner.
  *
  * D3 can claim tags only from the compact staging rows exposed by
  * `prepared`; it never selects directly from the free list. Claims remain
  * private per STID until the common S1 publication fire. Cancellation returns
  * only provisional tags, while exact deallocation returns published tags.
  */
class OooPTagStagingPool(val p: OooParams = OooParams()) extends Module {
  val io = IO(new OooPTagStagingPoolIO(p))

  private val committedTagCount = p.stidCount * p.pArchRegs
  private val allTagMaskValue = (BigInt(1) << p.pPhysRegs) - 1
  private val allTagMask = allTagMaskValue.U(p.pPhysRegs.W)
  private val initialCommittedMaskValue =
    (BigInt(1) << committedTagCount) - 1
  private val initialFreeMaskValue =
    allTagMaskValue ^ initialCommittedMaskValue
  private val bankMasks = (0 until p.pTagBanks).map { bank =>
    (0 until p.pPhysRegs)
      .filter(tag => tag % p.pTagBanks == bank)
      .foldLeft(BigInt(0))((mask, tag) => mask | (BigInt(1) << tag))
      .U(p.pPhysRegs.W)
  }
  private val destinationIndexWidth = math.max(1,
    chisel3.util.log2Ceil(p.maxDestinationOperands))
  private val stagingIndexWidth = math.max(1,
    chisel3.util.log2Ceil(p.pTagStagingDepthPerBank))

  val freeList = RegInit(initialFreeMaskValue.U(p.pPhysRegs.W))
  // Reset identity mappings are architectural-live, not permanently reserved.
  // Once CMAP replaces one, the exact commit return moves that tag into the
  // ordinary free/staged/provisional/published lifecycle.
  val initialCommittedMask = RegInit(
    initialCommittedMaskValue.U(p.pPhysRegs.W))
  val stagedTags = RegInit(VecInit(Seq.fill(p.pTagBanks)(
    VecInit(Seq.fill(p.pTagStagingDepthPerBank)(0.U(p.pTagWidth.W))))))
  val stagedCounts = RegInit(VecInit(Seq.fill(p.pTagBanks)(
    0.U(p.countWidth(p.pTagStagingDepthPerBank).W))))
  val provisional = RegInit(VecInit(Seq.fill(p.stidCount)(
    0.U.asTypeOf(new OooPTagReservation(p)))))
  val publishedMask = RegInit(0.U(p.pPhysRegs.W))
  val tagGeneration = RegInit(VecInit(Seq.fill(p.pPhysRegs)(
    0.U(p.pTagGenerationWidth.W))))

  def reservationTagMask(row: OooPTagReservation): UInt =
    row.allocations.map { allocation =>
      Mux(row.valid && allocation.valid,
        UIntToOH(allocation.token.ptag, p.pPhysRegs), 0.U(p.pPhysRegs.W))
    }.reduce(_ | _)

  val prepareStid = io.prepare.bits.plan.stid
  val prepareStidInRange = prepareStid < p.stidCount.U
  val safePrepareStid = Mux(prepareStidInRange, prepareStid, 0.U)

  val publishStid = io.publish.bits.stid
  val publishStidInRange = publishStid < p.stidCount.U
  val safePublishStid = Mux(publishStidInRange, publishStid, 0.U)
  val publishLive = provisional(safePublishStid)
  val publishExact = io.publish.valid && publishStidInRange &&
    !io.cancel(safePublishStid) && publishLive.valid &&
    publishLive.stid === publishStid &&
    publishLive.transactionId === io.publish.bits.transactionId
  val publishTagMask = Mux(publishExact,
    reservationTagMask(publishLive), 0.U(p.pPhysRegs.W))

  val destinationActive = Wire(Vec(p.pTagAllocationWidth, Bool()))
  val destinationBank = Wire(Vec(p.pTagAllocationWidth,
    UInt(p.pTagBankWidth.W)))
  val destinationBankOrdinal = Wire(Vec(p.pTagAllocationWidth,
    UInt(p.countWidth(p.pTagStagingDepthPerBank).W)))
  val destinationAtag = Wire(Vec(p.pTagAllocationWidth,
    UInt(p.archRegWidth.W)))
  val destinationUop = Wire(Vec(p.pTagAllocationWidth,
    UInt(p.decodedUopIndexWidth.W)))
  val destinationIndex = Wire(Vec(p.pTagAllocationWidth,
    UInt(destinationIndexWidth.W)))

  for (uopIndex <- 0 until p.decodedUopWidth;
       dstIndex <- 0 until p.maxDestinationOperands) {
    val flatIndex = uopIndex * p.maxDestinationOperands + dstIndex
    val uop = io.prepare.bits.decoded.uops(uopIndex)
    val destination = uop.destinations(dstIndex)
    destinationActive(flatIndex) := io.prepare.bits.decoded.uopMask(uopIndex) &&
      uop.valid && destination.valid && destination.kind === DestinationKind.Gpr
    destinationAtag(flatIndex) := destination.atag
    destinationUop(flatIndex) := uopIndex.U
    destinationIndex(flatIndex) := dstIndex.U
  }

  val startBank = if (p.pTagBanks == 1) {
    0.U(p.pTagBankWidth.W)
  } else {
    io.prepare.bits.plan.transactionId(p.pTagBankWidth - 1, 0)
  }
  val destinationAssignmentAvailable = Wire(Vec(p.pTagAllocationWidth, Bool()))
  var assignedByBank = Seq.fill(p.pTagBanks)(
    0.U(p.countWidth(p.pTagStagingDepthPerBank).W))
  for (flatIndex <- 0 until p.pTagAllocationWidth) {
    val olderCount = if (flatIndex == 0) 0.U else
      PopCount(destinationActive.take(flatIndex))
    val preferredBank =
      (startBank + olderCount)(p.pTagBankWidth - 1, 0)
    val candidateAvailable = Wire(Vec(p.pTagBanks, Bool()))
    for (offset <- 0 until p.pTagBanks) {
      val candidateBank = if (p.pTagBanks == 1) {
        0.U(p.pTagBankWidth.W)
      } else {
        (preferredBank + offset.U)(p.pTagBankWidth - 1, 0)
      }
      candidateAvailable(offset) := destinationActive(flatIndex) &&
        VecInit(assignedByBank)(candidateBank) < stagedCounts(candidateBank)
    }
    val selectedOffset = if (p.pTagBanks == 1) {
      0.U(p.pTagBankWidth.W)
    } else {
      PriorityEncoder(candidateAvailable.asUInt)
    }
    val selectedBank = if (p.pTagBanks == 1) {
      0.U(p.pTagBankWidth.W)
    } else {
      (preferredBank + selectedOffset)(p.pTagBankWidth - 1, 0)
    }
    val assignmentAvailable = candidateAvailable.asUInt.orR
    destinationAssignmentAvailable(flatIndex) :=
      !destinationActive(flatIndex) || assignmentAvailable
    destinationBank(flatIndex) := selectedBank
    destinationBankOrdinal(flatIndex) := VecInit(assignedByBank)(selectedBank)
    assignedByBank = assignedByBank.zipWithIndex.map { case (assigned, bank) =>
      assigned + (destinationActive(flatIndex) && assignmentAvailable &&
        selectedBank === bank.U).asUInt
    }
  }

  val bankDemand = Wire(Vec(p.pTagBanks,
    UInt(p.countWidth(p.pTagStagingDepthPerBank).W)))
  for (bank <- 0 until p.pTagBanks) {
    bankDemand(bank) := assignedByBank(bank)
  }

  val destinationCount = PopCount(destinationActive)
  val destinationShapeExact = (0 until p.pTagAllocationWidth).map { index =>
    !destinationActive(index) || destinationAtag(index) < p.pArchRegs.U
  }.reduce(_ && _)
  val prepareIdentityExact =
    io.prepare.bits.plan.peId === io.prepare.bits.decoded.peId &&
      io.prepare.bits.plan.stid === io.prepare.bits.decoded.stid &&
      io.prepare.bits.plan.epoch === io.prepare.bits.decoded.epoch
  val prepareDemandExact =
    destinationCount === io.prepare.bits.plan.demand.pDestinations &&
      destinationCount === io.prepare.bits.plan.demand.mapQRows
  val prepareExact = prepareStidInRange && prepareIdentityExact &&
    prepareDemandExact && destinationShapeExact
  val stagingAvailable = (0 until p.pTagBanks).map { bank =>
    bankDemand(bank) <= stagedCounts(bank)
  }.reduce(_ && _)
  val allAssignmentsAvailable = destinationAssignmentAvailable.reduce(_ && _)
  val provisionalRowAvailable = !provisional(safePrepareStid).valid ||
    io.cancel(safePrepareStid) ||
    (publishExact && publishStid === prepareStid)

  io.prepareReady := prepareExact && allAssignmentsAvailable &&
    stagingAvailable && provisionalRowAvailable
  io.prepared := 0.U.asTypeOf(io.prepared)
  io.prepared.valid := io.prepare.valid && io.prepareReady
  io.prepared.peId := io.prepare.bits.plan.peId
  io.prepared.stid := prepareStid
  io.prepared.epoch := io.prepare.bits.plan.epoch
  io.prepared.transactionId := io.prepare.bits.plan.transactionId
  io.prepared.allocationMask := destinationActive.asUInt
  for (index <- 0 until p.pTagAllocationWidth) {
    val allocation = io.prepared.allocations(index)
    val bank = destinationBank(index)
    val ordinal = destinationBankOrdinal(index)
    val ordinalInRange = ordinal < p.pTagStagingDepthPerBank.U
    val safeOrdinal = Mux(ordinalInRange, ordinal, 0.U)(stagingIndexWidth - 1, 0)
    allocation.valid := destinationActive(index)
    allocation.uopIndex := destinationUop(index)
    allocation.destinationIndex := destinationIndex(index)
    allocation.atag := destinationAtag(index)
    allocation.token.valid := destinationActive(index)
    allocation.token.bank := bank
    allocation.token.ptag := stagedTags(bank)(safeOrdinal)
    allocation.token.generation :=
      tagGeneration(stagedTags(bank)(safeOrdinal)) + 1.U
  }

  io.prepareRejected.valid := io.prepare.valid && !prepareExact
  io.prepareRejected.bits.stid := prepareStid
  io.prepareRejected.bits.transactionId := io.prepare.bits.plan.transactionId
  io.prepareRejected.bits.requestedDestinations :=
    io.prepare.bits.plan.demand.pDestinations
  io.publishRejected.valid := io.publish.valid && !publishExact
  io.publishRejected.bits.requested := io.publish.bits
  io.publishRejected.bits.live := publishLive

  val returnCount = io.release.bits.count
  val returnCountInRange = returnCount.orR && returnCount <= p.pTagReturnWidth.U
  val returnTagMask = (0 until p.pTagReturnWidth).map { index =>
    val active = index.U < returnCount
    val token = io.release.bits.tokens(index)
    val tag = token.ptag
    val tagInRange = tag < p.pPhysRegs.U
    val safeTag = Mux(tagInRange, tag, 0.U)
    Mux(active && tagInRange,
      UIntToOH(safeTag, p.pPhysRegs), 0.U(p.pPhysRegs.W))
  }.reduce(_ | _)
  val returnTagsExact = (0 until p.pTagReturnWidth).map { index =>
    val active = index.U < returnCount
    val token = io.release.bits.tokens(index)
    val tag = token.ptag
    val tagInRange = tag < p.pPhysRegs.U
    val safeTag = Mux(tagInRange, tag, 0.U)
    val unique = (0 until index).map { older =>
      older.U >= returnCount || io.release.bits.tokens(older).ptag =/= tag
    }.reduceOption(_ && _).getOrElse(true.B)
    val bankExact = if (p.pTagBanks == 1) {
      token.bank === 0.U
    } else {
      token.bank === tag(p.pTagBankWidth - 1, 0)
    }
    Mux(active,
      token.valid && tagInRange && unique && bankExact &&
        token.generation === tagGeneration(safeTag) &&
          (publishedMask(safeTag) || initialCommittedMask(safeTag)),
      true.B)
  }.reduce(_ && _)
  val returnExact = returnCountInRange && returnTagsExact &&
    PopCount(returnTagMask) === returnCount
  io.release.ready := returnExact
  val returnFire = io.release.valid && io.release.ready
  io.returnRejected.valid := io.release.valid && !io.release.ready
  io.returnRejected.bits.requested := io.release.bits
  io.returnRejected.bits.publishedMask := publishedMask

  val cancelTagMask = (0 until p.stidCount).map { stid =>
    Mux(io.cancel(stid) && provisional(stid).valid,
      reservationTagMask(provisional(stid)), 0.U(p.pPhysRegs.W))
  }.reduce(_ | _)
  val returnedToFree = cancelTagMask |
    Mux(returnFire, returnTagMask, 0.U(p.pPhysRegs.W))

  val reserveFire = io.reserveFire && io.prepare.valid && io.prepareReady
  when(io.reserveFire) {
    assert(io.prepare.valid && io.prepareReady,
      "PTag reserveFire requires one exact staging-backed preparation")
  }

  when(reserveFire) {
    for (index <- 0 until p.pTagAllocationWidth) {
      val allocation = io.prepared.allocations(index)
      when(allocation.valid) {
        tagGeneration(allocation.token.ptag) := allocation.token.generation
      }
    }
  }

  val consumeByBank = Wire(Vec(p.pTagBanks,
    UInt(p.countWidth(p.pTagStagingDepthPerBank).W)))
  val survivorCount = Wire(Vec(p.pTagBanks,
    UInt(p.countWidth(p.pTagStagingDepthPerBank).W)))
  for (bank <- 0 until p.pTagBanks) {
    consumeByBank(bank) := Mux(reserveFire, bankDemand(bank), 0.U)
    survivorCount(bank) := stagedCounts(bank) - consumeByBank(bank)
  }

  val refillTags = Wire(Vec(p.pTagBanks,
    Vec(p.pTagStagingDepthPerBank, UInt(p.pTagWidth.W))))
  val refillValid = Wire(Vec(p.pTagBanks,
    Vec(p.pTagStagingDepthPerBank, Bool())))
  var remainingFree = freeList
  for (bank <- 0 until p.pTagBanks; refillIndex <- 0 until p.pTagStagingDepthPerBank) {
    val candidates = remainingFree & bankMasks(bank)
    val vacancy = p.pTagStagingDepthPerBank.U - survivorCount(bank)
    val take = refillIndex.U < vacancy && candidates.orR
    val tag = PriorityEncoder(candidates)
    refillValid(bank)(refillIndex) := take
    refillTags(bank)(refillIndex) := tag
    remainingFree = remainingFree &
      ~Mux(take, UIntToOH(tag, p.pPhysRegs), 0.U(p.pPhysRegs.W))
  }
  val refillMask = freeList & ~remainingFree

  for (bank <- 0 until p.pTagBanks) {
    val refillCount = PopCount(refillValid(bank))
    val nextCount = survivorCount(bank) + refillCount
    stagedCounts(bank) := nextCount
    for (slot <- 0 until p.pTagStagingDepthPerBank) {
      val sourceIndex = slot.U + consumeByBank(bank)
      val sourceInRange = sourceIndex < p.pTagStagingDepthPerBank.U
      val safeSource = Mux(sourceInRange, sourceIndex, 0.U)(stagingIndexWidth - 1, 0)
      val nextTag = WireDefault(Mux(slot.U < survivorCount(bank),
        stagedTags(bank)(safeSource), 0.U))
      for (refillIndex <- 0 until p.pTagStagingDepthPerBank) {
        when(refillValid(bank)(refillIndex) &&
            slot.U === survivorCount(bank) + refillIndex.U) {
          nextTag := refillTags(bank)(refillIndex)
        }
      }
      stagedTags(bank)(slot) := nextTag
    }
  }
  freeList := (freeList & ~refillMask) | returnedToFree

  for (stid <- 0 until p.stidCount) {
    when(io.cancel(stid) || (publishExact && publishStid === stid.U)) {
      provisional(stid) := 0.U.asTypeOf(provisional(stid))
    }
    when(reserveFire && prepareStid === stid.U) {
      provisional(stid) := io.prepared
    }
  }
  publishedMask := (publishedMask | publishTagMask) &
    ~Mux(returnFire, returnTagMask, 0.U(p.pPhysRegs.W))
  initialCommittedMask := initialCommittedMask &
    ~Mux(returnFire, returnTagMask, 0.U(p.pPhysRegs.W))

  val stagedLocationMask = (0 until p.pTagBanks).flatMap { bank =>
    (0 until p.pTagStagingDepthPerBank).map { slot =>
      Mux(slot.U < stagedCounts(bank),
        UIntToOH(stagedTags(bank)(slot), p.pPhysRegs), 0.U(p.pPhysRegs.W))
    }
  }.reduce(_ | _)
  val provisionalLocationMask = provisional.map(reservationTagMask).reduce(_ | _)
  val freeStagedDisjoint = (freeList & stagedLocationMask) === 0.U
  val freeProvisionalDisjoint = (freeList & provisionalLocationMask) === 0.U
  val freePublishedDisjoint = (freeList & publishedMask) === 0.U
  val freeInitialCommittedDisjoint =
    (freeList & initialCommittedMask) === 0.U
  val stagedProvisionalDisjoint =
    (stagedLocationMask & provisionalLocationMask) === 0.U
  val stagedPublishedDisjoint = (stagedLocationMask & publishedMask) === 0.U
  val provisionalPublishedDisjoint =
    (provisionalLocationMask & publishedMask) === 0.U
  val stagedInitialCommittedDisjoint =
    (stagedLocationMask & initialCommittedMask) === 0.U
  val provisionalInitialCommittedDisjoint =
    (provisionalLocationMask & initialCommittedMask) === 0.U
  val publishedInitialCommittedDisjoint =
    (publishedMask & initialCommittedMask) === 0.U
  val locationUnion = freeList | stagedLocationMask |
    provisionalLocationMask | publishedMask | initialCommittedMask
  io.conservationValid := freeStagedDisjoint && freeProvisionalDisjoint &&
    freePublishedDisjoint && freeInitialCommittedDisjoint &&
    stagedProvisionalDisjoint && stagedPublishedDisjoint &&
    stagedInitialCommittedDisjoint && provisionalPublishedDisjoint &&
    provisionalInitialCommittedDisjoint && publishedInitialCommittedDisjoint &&
    locationUnion === allTagMask
  assert(io.conservationValid,
    "every PTag must occupy exactly one lifecycle location")

  io.provisional := provisional
  io.freeCount := PopCount(freeList)
  io.stagedCount := stagedCounts
  io.provisionalCount := PopCount(provisionalLocationMask)
  io.publishedCount := PopCount(publishedMask)
}
