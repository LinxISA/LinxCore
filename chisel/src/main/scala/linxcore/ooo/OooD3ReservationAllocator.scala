package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, PopCount, PriorityEncoder, Valid}

class OooD3ReservationAllocatorIO(val p: OooParams = OooParams()) extends Bundle {
  val in = Flipped(Decoupled(new OooD2GroupedTransaction(p)))
  val release = Flipped(Decoupled(new OooRobGroupRelease(p)))
  val cancel = Input(Vec(p.stidCount, Bool()))
  val publishEligible = Input(Vec(p.stidCount, Bool()))
  val out = Decoupled(new OooD3GroupedReservation(p))

  val tailSlot = Output(Vec(p.stidCount, UInt(p.ridSlotWidth.W)))
  val tailGeneration = Output(Vec(p.stidCount, UInt(p.ridGenerationWidth.W)))
  val tailEpoch = Output(Vec(p.stidCount, UInt(p.reservationEpochWidth.W)))
  val nextTransactionId = Output(Vec(p.stidCount, UInt(p.transactionIdWidth.W)))
  val headSlot = Output(Vec(p.stidCount, UInt(p.ridSlotWidth.W)))
  val headGeneration = Output(Vec(p.stidCount, UInt(p.ridGenerationWidth.W)))
  val headEpoch = Output(Vec(p.stidCount, UInt(p.reservationEpochWidth.W)))
  val usedGroups = Output(Vec(p.stidCount, UInt(p.countWidth(p.robGroupsPerStid).W)))
  val publishedGroups = Output(Vec(p.stidCount, UInt(p.countWidth(p.robGroupsPerStid).W)))
  val provisionalMask = Output(UInt(p.stidCount.W))
  val staleRejected = Valid(new OooD3StalePlanReject(p))
  val releaseRejected = Valid(new OooD3ReleaseReject(p))
  val capacityBlocked = Output(Bool())
}

/** D3 provisional grouped-ROB allocator.
  *
  * Accepted claims advance private reserved tails but do not create public ROB
  * rows. `out.fire` is the later S1 publication boundary. A canceled claim can
  * therefore roll back exactly because only one provisional row exists per
  * STID. Stale plans are consumed and reported with zero state mutation.
  */
class OooD3ReservationAllocator(val p: OooParams = OooParams()) extends Module {
  val io = IO(new OooD3ReservationAllocatorIO(p))
  private val usedWidth = p.countWidth(p.robGroupsPerStid)

  val tailSlot = RegInit(VecInit(Seq.fill(p.stidCount)(0.U(p.ridSlotWidth.W))))
  val tailGeneration = RegInit(VecInit(Seq.fill(p.stidCount)(0.U(p.ridGenerationWidth.W))))
  val tailEpoch = RegInit(VecInit(Seq.fill(p.stidCount)(0.U(p.reservationEpochWidth.W))))
  val nextTransactionId = RegInit(VecInit(Seq.fill(p.stidCount)(0.U(p.transactionIdWidth.W))))
  val headSlot = RegInit(VecInit(Seq.fill(p.stidCount)(0.U(p.ridSlotWidth.W))))
  val headGeneration = RegInit(VecInit(Seq.fill(p.stidCount)(0.U(p.ridGenerationWidth.W))))
  val headEpoch = RegInit(VecInit(Seq.fill(p.stidCount)(0.U(p.reservationEpochWidth.W))))
  val headPeId = RegInit(VecInit(Seq.fill(p.stidCount)(0.U(p.peIdWidth.W))))
  val usedGroups = RegInit(VecInit(Seq.fill(p.stidCount)(0.U(usedWidth.W))))
  val publishedGroups = RegInit(VecInit(Seq.fill(p.stidCount)(0.U(usedWidth.W))))
  val valid = RegInit(VecInit(Seq.fill(p.stidCount)(false.B)))
  val rows = Reg(Vec(p.stidCount, new OooD3GroupedReservation(p)))

  val rrStart = RegInit(0.U(p.stidWidth.W))
  val heldGrantValid = RegInit(false.B)
  val heldGrantStid = RegInit(0.U(p.stidWidth.W))
  val rotated = Wire(Vec(p.stidCount, Bool()))
  for (offset <- 0 until p.stidCount) {
    val index = if (p.stidCount == 1) 0.U else (rrStart + offset.U)(p.stidWidth - 1, 0)
    rotated(offset) := valid(index) && !io.cancel(index) &&
      io.publishEligible(index)
  }
  val rrValid = rotated.asUInt.orR
  val rrOffset = if (p.stidCount == 1) 0.U else PriorityEncoder(rotated.asUInt)
  val rrSelected =
    if (p.stidCount == 1) 0.U(p.stidWidth.W)
    else (rrStart + rrOffset)(p.stidWidth - 1, 0)
  val selected = Mux(heldGrantValid, heldGrantStid, rrSelected)
  // Eligibility filters only a new grant. Once valid has been exposed and the
  // grant is held, ready/valid requires it to remain asserted until fire or an
  // explicit matching cancel, even if downstream eligibility later changes.
  val selectedLive = if (p.stidCount == 1) {
    valid(0) && !io.cancel(0) && (heldGrantValid || io.publishEligible(0))
  } else {
    valid(selected) && !io.cancel(selected) &&
      (heldGrantValid || io.publishEligible(selected))
  }
  io.out.valid := Mux(heldGrantValid, selectedLive, rrValid)
  io.out.bits := (if (p.stidCount == 1) rows(0) else rows(selected))

  val inStid = io.in.bits.plan.stid
  val inRange = inStid < p.stidCount.U
  val liveSlot = tailSlot(inStid)
  val liveGeneration = tailGeneration(inStid)
  val liveEpoch = tailEpoch(inStid)
  val groupCount = io.in.bits.plan.groupCount
  val firstKey = io.in.bits.plan.firstVirtualGroup
  val groupCountInRange = groupCount.orR && groupCount <= p.instructionDecodeWidth.U
  val expectedGroupMask =
    ((1.U((p.instructionDecodeWidth + 1).W) << groupCount) - 1.U)(
      p.instructionDecodeWidth - 1, 0)
  val planIdentityExact =
    io.in.bits.plan.peId === io.in.bits.decoded.peId &&
      io.in.bits.plan.stid === io.in.bits.decoded.stid &&
      io.in.bits.plan.epoch === io.in.bits.decoded.epoch
  val allGroupKeysExact = (0 until p.instructionDecodeWidth).map { groupIndex =>
    val expectedSum = liveSlot +& groupIndex.U
    val expectedWrap = expectedSum >= p.robGroupsPerStid.U
    val group = io.in.bits.groups(groupIndex)
    val shouldBeValid = groupIndex.U < groupCount
    group.valid === shouldBeValid && (!shouldBeValid || (
      group.key.valid &&
        group.key.peId === io.in.bits.plan.peId &&
        group.key.stid === inStid &&
        group.key.ridSlot === expectedSum(p.ridSlotWidth - 1, 0) &&
        group.key.ridGeneration === liveGeneration + expectedWrap.asUInt))
  }.reduce(_ && _)
  val stale = inRange && (
    io.in.bits.plan.transactionId =/= nextTransactionId(inStid) ||
      io.in.bits.plan.virtualTailEpoch =/= liveEpoch ||
      !firstKey.valid || firstKey.ridSlot =/= liveSlot ||
      firstKey.ridGeneration =/= liveGeneration ||
      firstKey.peId =/= io.in.bits.plan.peId || firstKey.stid =/= inStid ||
      !groupCountInRange || io.in.bits.groupMask =/= expectedGroupMask ||
      !planIdentityExact || !allGroupKeysExact)
  val releaseStid = io.release.bits.firstGroup.stid
  val releaseInRange = releaseStid < p.stidCount.U
  val safeReleaseStid = Mux(releaseInRange, releaseStid, 0.U)
  val releaseExact = releaseInRange &&
    io.release.bits.firstGroup.valid &&
    io.release.bits.firstGroup.peId === headPeId(safeReleaseStid) &&
    io.release.bits.firstGroup.ridSlot === headSlot(safeReleaseStid) &&
    io.release.bits.firstGroup.ridGeneration === headGeneration(safeReleaseStid) &&
    io.release.bits.headEpoch === headEpoch(safeReleaseStid) &&
    io.release.bits.groupCount.orR &&
    io.release.bits.groupCount <= p.retireGroupWidth.U &&
    publishedGroups(safeReleaseStid) >= io.release.bits.groupCount
  io.release.ready := releaseExact
  val releaseFire = io.release.valid && io.release.ready
  val releaseHitsInput = releaseFire && releaseStid === inStid
  val releasedForInput = Mux(releaseHitsInput, io.release.bits.groupCount, 0.U)
  val freeAfterRelease =
    p.robGroupsPerStid.U((usedWidth + 1).W) - usedGroups(inStid).pad(usedWidth + 1) +
      releasedForInput.pad(usedWidth + 1)
  val hasCapacity = freeAfterRelease >= groupCount.pad(usedWidth + 1)
  val replacingSelected = io.out.fire && selected === inStid
  val occupied = if (p.stidCount == 1) valid(0) else valid(inStid)
  val cancelled = if (p.stidCount == 1) io.cancel(0) else io.cancel(inStid)

  io.in.ready := inRange && Mux(
    stale,
    true.B,
    !cancelled && groupCount.orR && hasCapacity && (!occupied || replacingSelected))
  val inFire = io.in.valid && io.in.ready
  val staleFire = inFire && stale
  val reserveFire = inFire && !stale

  io.staleRejected.valid := staleFire
  io.staleRejected.bits.stid := inStid
  io.staleRejected.bits.transactionId := io.in.bits.plan.transactionId
  io.staleRejected.bits.plannedTailEpoch := io.in.bits.plan.virtualTailEpoch
  io.staleRejected.bits.liveTailEpoch := liveEpoch
  io.releaseRejected.valid := io.release.valid && !io.release.ready
  io.releaseRejected.bits.requested := io.release.bits
  io.releaseRejected.bits.liveHead.valid := releaseInRange && publishedGroups(safeReleaseStid).orR
  io.releaseRejected.bits.liveHead.peId := Mux(releaseInRange, headPeId(safeReleaseStid), 0.U)
  io.releaseRejected.bits.liveHead.stid := releaseStid
  io.releaseRejected.bits.liveHead.ridSlot := Mux(releaseInRange, headSlot(safeReleaseStid), 0.U)
  io.releaseRejected.bits.liveHead.ridGeneration :=
    Mux(releaseInRange, headGeneration(safeReleaseStid), 0.U)
  io.releaseRejected.bits.liveHeadEpoch := Mux(releaseInRange, headEpoch(safeReleaseStid), 0.U)
  io.capacityBlocked := io.in.valid && inRange && !stale && !hasCapacity

  val tailSum = liveSlot +& groupCount
  val tailWrap = tailSum >= p.robGroupsPerStid.U
  val reservation = Wire(new OooD3GroupedReservation(p))
  reservation := 0.U.asTypeOf(reservation)
  reservation.transaction := io.in.bits
  reservation.claimEpoch := liveEpoch
  reservation.tailAfter.valid := true.B
  reservation.tailAfter.peId := io.in.bits.plan.peId
  reservation.tailAfter.stid := inStid
  reservation.tailAfter.ridSlot := tailSum(p.ridSlotWidth - 1, 0)
  reservation.tailAfter.ridGeneration := liveGeneration + tailWrap.asUInt

  for (stid <- 0 until p.stidCount) {
    val cancelClaim = io.cancel(stid) && valid(stid)
    val releaseHit = releaseFire && releaseStid === stid.U
    val reserveHit = reserveFire && inStid === stid.U
    val publishHit = io.out.fire && selected === stid.U
    val releaseCount = Mux(releaseHit, io.release.bits.groupCount, 0.U)
    val cancelCount = Mux(cancelClaim, rows(stid).transaction.plan.groupCount, 0.U)
    val reserveCount = Mux(reserveHit, groupCount, 0.U)

    when(releaseHit || cancelClaim || reserveHit) {
      usedGroups(stid) := usedGroups(stid) + reserveCount.pad(usedWidth) -
        releaseCount.pad(usedWidth) - cancelCount.pad(usedWidth)
      tailEpoch(stid) := tailEpoch(stid) +
        releaseHit.asUInt + cancelClaim.asUInt + reserveHit.asUInt
    }
    when(releaseHit || publishHit) {
      publishedGroups(stid) := publishedGroups(stid) +
        Mux(publishHit, rows(stid).transaction.plan.groupCount, 0.U).pad(usedWidth) -
        Mux(releaseHit, io.release.bits.groupCount, 0.U).pad(usedWidth)
    }
    when(releaseHit) {
      val headSum = headSlot(stid) +& io.release.bits.groupCount
      val headWrap = headSum >= p.robGroupsPerStid.U
      headSlot(stid) := headSum(p.ridSlotWidth - 1, 0)
      headGeneration(stid) := headGeneration(stid) + headWrap.asUInt
      headEpoch(stid) := headEpoch(stid) + 1.U
    }
    when(cancelClaim) {
      tailSlot(stid) := rows(stid).transaction.plan.firstVirtualGroup.ridSlot
      tailGeneration(stid) := rows(stid).transaction.plan.firstVirtualGroup.ridGeneration
    }
    when(reserveHit) {
      when(usedGroups(stid) === Mux(releaseHit, io.release.bits.groupCount, 0.U)) {
        headPeId(stid) := io.in.bits.plan.peId
      }
      tailSlot(stid) := reservation.tailAfter.ridSlot
      tailGeneration(stid) := reservation.tailAfter.ridGeneration
      nextTransactionId(stid) := nextTransactionId(stid) + 1.U
      rows(stid) := reservation
    }

    when(io.cancel(stid)) {
      valid(stid) := false.B
    }.elsewhen(io.out.fire && selected === stid.U) {
      valid(stid) := false.B
    }
    when(reserveHit) {
      valid(stid) := true.B
    }
  }

  when(io.out.valid && !io.out.ready && !heldGrantValid) {
    heldGrantValid := true.B
    heldGrantStid := selected
  }
  when(heldGrantValid && (if (p.stidCount == 1) io.cancel(0) else io.cancel(heldGrantStid))) {
    heldGrantValid := false.B
  }
  when(io.out.fire) {
    heldGrantValid := false.B
    rrStart :=
      (if (p.stidCount == 1) 0.U else (selected + 1.U)(p.stidWidth - 1, 0))
  }

  io.tailSlot := tailSlot
  io.tailGeneration := tailGeneration
  io.tailEpoch := tailEpoch
  io.nextTransactionId := nextTransactionId
  io.headSlot := headSlot
  io.headGeneration := headGeneration
  io.headEpoch := headEpoch
  io.usedGroups := usedGroups
  io.publishedGroups := publishedGroups
  io.provisionalMask := valid.asUInt

  when(reserveFire) {
    assert(io.in.bits.plan.transactionId === nextTransactionId(inStid))
    assert(groupCount <= p.robGroupsPerStid.U)
  }
  when(heldGrantValid && io.out.valid && !io.out.ready) {
    assert(io.out.bits.transaction.plan.stid === heldGrantStid)
  }
}
