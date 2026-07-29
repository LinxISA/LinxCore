package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, Mux1H, PopCount, PriorityEncoder, UIntToOH,
  Valid}

object OooDispatchSlotState extends ChiselEnum {
  val Free, Provisional, Published = Value
}

class OooDispatchIO(val p: OooParams = OooParams()) extends Bundle {
  val prepare = Flipped(Valid(new OooD2GroupedTransaction(p)))
  val prepareReady = Output(Bool())
  val prepared = Output(new OooDispatchReservationLease(p))
  val reserveFire = Input(Bool())
  val cancel = Input(Vec(p.stidCount, Bool()))

  val publish = Flipped(Valid(new OooDispatchPublish(p)))
  val releases = Flipped(Vec(p.iexReleaseWidth,
    Decoupled(new OooDispatchRelease(p))))
  def release = releases(0)

  val recoveryPrepare = Flipped(Valid(new OooResidencyRecoveryPlan(p)))
  val recoveryPrepareReady = Output(Bool())
  val recoveryPrepared = Output(new OooDispatchRecoveryPrepared(p))
  val recoveryFire = Input(Bool())

  val provisional = Output(Vec(p.stidCount,
    new OooDispatchReservationLease(p)))
  val freeEntries = Output(Vec(p.iqClassCount,
    Vec(p.iqBankCount, UInt(p.iqBankEntryCountWidth.W))))
  val provisionalEntries = Output(Vec(p.iqClassCount,
    Vec(p.iqBankCount, UInt(p.iqBankEntryCountWidth.W))))
  val publishedEntries = Output(Vec(p.iqClassCount,
    Vec(p.iqBankCount, UInt(p.iqBankEntryCountWidth.W))))
  val publishedByStid = Output(Vec(p.stidCount,
    UInt(p.countWidth(p.iqClassCount * p.iqBankCount *
      p.iqEntriesPerBank).W)))

  val prepareRejected = Valid(new OooDispatchPrepareReject(p))
  val publishRejected = Valid(new OooDispatchPublishReject(p))
  val releaseRejecteds = Vec(p.iexReleaseWidth,
    Valid(new OooDispatchReleaseReject(p)))
  def releaseRejected = releaseRejecteds(0)
  val recoveryRejected = Valid(new OooDispatchRecoveryReject(p))
}

/** D3 owner for exact speculative issue-queue reservations.
  *
  * Generated dispatch demand is compacted in logical-uop/class/child order.
  * Every child receives an exact class, sub-bank, write port, slot, and reuse
  * generation. The complete bundle reserves or publishes atomically; a later
  * IEX owner may release only the identical published token.
  */
class OooDispatch(val p: OooParams = OooParams()) extends Module {
  val io = IO(new OooDispatchIO(p))

  private val candidateCount = p.decodedUopWidth * p.iqClassCount *
    p.maxDispatchWritesPerInstruction
  private val childIndexWidth = math.max(1,
    chisel3.util.log2Ceil(p.maxDispatchWritesPerInstruction))

  private val classValues = VecInit(Seq(
    OooUopClass.Alu,
    OooUopClass.Bru,
    OooUopClass.Agu,
    OooUopClass.Std,
    OooUopClass.Fsu,
    OooUopClass.Sys,
    OooUopClass.Cmd,
    OooUopClass.Boundary))

  val slotState = RegInit(VecInit(Seq.fill(p.iqClassCount)(
    VecInit(Seq.fill(p.iqBankCount)(
      VecInit(Seq.fill(p.iqEntriesPerBank)(OooDispatchSlotState.Free)))))))
  val slotGeneration = RegInit(VecInit(Seq.fill(p.iqClassCount)(
    VecInit(Seq.fill(p.iqBankCount)(
      VecInit(Seq.fill(p.iqEntriesPerBank)(
        0.U(p.reservationEpochWidth.W))))))))
  val slotWritePort = RegInit(VecInit(Seq.fill(p.iqClassCount)(
    VecInit(Seq.fill(p.iqBankCount)(
      VecInit(Seq.fill(p.iqEntriesPerBank)(
        0.U(p.iqWritePortWidth.W))))))))
  val slotPeId = RegInit(VecInit(Seq.fill(p.iqClassCount)(
    VecInit(Seq.fill(p.iqBankCount)(
      VecInit(Seq.fill(p.iqEntriesPerBank)(0.U(p.peIdWidth.W))))))))
  val slotStid = RegInit(VecInit(Seq.fill(p.iqClassCount)(
    VecInit(Seq.fill(p.iqBankCount)(
      VecInit(Seq.fill(p.iqEntriesPerBank)(0.U(p.stidWidth.W))))))))
  val slotEpoch = RegInit(VecInit(Seq.fill(p.iqClassCount)(
    VecInit(Seq.fill(p.iqBankCount)(
      VecInit(Seq.fill(p.iqEntriesPerBank)(0.U(p.epochWidth.W))))))))
  val slotTransaction = RegInit(VecInit(Seq.fill(p.iqClassCount)(
    VecInit(Seq.fill(p.iqBankCount)(
      VecInit(Seq.fill(p.iqEntriesPerBank)(
        0.U(p.transactionIdWidth.W))))))))
  val slotMemberValid = RegInit(VecInit(Seq.fill(p.iqClassCount)(
    VecInit(Seq.fill(p.iqBankCount)(
      VecInit(Seq.fill(p.iqEntriesPerBank)(false.B)))))))
  val slotMember = Reg(Vec(p.iqClassCount,
    Vec(p.iqBankCount,
      Vec(p.iqEntriesPerBank, new RobMemberKey(p)))))
  val provisional = RegInit(VecInit(Seq.fill(p.stidCount)(
    0.U.asTypeOf(new OooDispatchReservationLease(p)))))

  val recoveryPlan = io.recoveryPrepare.bits
  val recoveryStid = recoveryPlan.oldHead.stid
  val recoveryStidInRange = recoveryStid < p.stidCount.U
  val safeRecoveryStid = Mux(recoveryStidInRange, recoveryStid, 0.U)
  val recoveryFreeze = io.recoveryPrepare.valid && recoveryPlan.valid &&
    recoveryStidInRange

  val freeMask = Wire(Vec(p.iqClassCount,
    Vec(p.iqBankCount, UInt(p.iqEntriesPerBank.W))))
  for (uopClass <- 0 until p.iqClassCount; bank <- 0 until p.iqBankCount) {
    freeMask(uopClass)(bank) := VecInit((0 until p.iqEntriesPerBank).map {
      entry => slotState(uopClass)(bank)(entry) === OooDispatchSlotState.Free
    }).asUInt
    io.freeEntries(uopClass)(bank) := PopCount(VecInit(
      (0 until p.iqEntriesPerBank).map { entry =>
        slotState(uopClass)(bank)(entry) === OooDispatchSlotState.Free
      }).asUInt)
    io.provisionalEntries(uopClass)(bank) := PopCount(VecInit(
      (0 until p.iqEntriesPerBank).map { entry =>
        slotState(uopClass)(bank)(entry) ===
          OooDispatchSlotState.Provisional
      }).asUInt)
    io.publishedEntries(uopClass)(bank) := PopCount(VecInit(
      (0 until p.iqEntriesPerBank).map { entry =>
        slotState(uopClass)(bank)(entry) === OooDispatchSlotState.Published
      }).asUInt)
  }
  io.provisional := provisional
  for (stid <- 0 until p.stidCount) {
    io.publishedByStid(stid) := PopCount(VecInit(
      (0 until p.iqClassCount).flatMap { uopClass =>
        (0 until p.iqBankCount).flatMap { bank =>
          (0 until p.iqEntriesPerBank).map { entry =>
            slotState(uopClass)(bank)(entry) ===
              OooDispatchSlotState.Published &&
              slotStid(uopClass)(bank)(entry) === stid.U
          }
        }
      }).asUInt)
  }

  val transaction = io.prepare.bits
  val plan = transaction.plan
  val decoded = transaction.decoded
  val prepareStid = plan.stid
  val prepareStidInRange = prepareStid < p.stidCount.U
  val safePrepareStid = Mux(prepareStidInRange, prepareStid, 0.U)
  val uopMaskExact = plan.uopMask === decoded.uopMask &&
    decoded.uopMask === transaction.plan.uopMask
  val identityExact = plan.peId === decoded.peId &&
    plan.stid === decoded.stid && plan.epoch === decoded.epoch

  val candidateActive = Wire(Vec(candidateCount, Bool()))
  val candidateUop = Wire(Vec(candidateCount,
    UInt(p.decodedUopIndexWidth.W)))
  val candidateClass = Wire(Vec(candidateCount,
    UInt(math.max(1, chisel3.util.log2Ceil(p.iqClassCount)).W)))
  val candidateChild = Wire(Vec(candidateCount, UInt(childIndexWidth.W)))
  var candidateIndex = 0
  for (uopIndex <- 0 until p.decodedUopWidth;
       uopClass <- 0 until p.iqClassCount;
       repeat <- 0 until p.maxDispatchWritesPerInstruction) {
    val uop = decoded.uops(uopIndex)
    candidateActive(candidateIndex) := decoded.uopMask(uopIndex) && uop.valid &&
      repeat.U < uop.recipe.dispatchDemand(uopClass)
    candidateUop(candidateIndex) := uopIndex.U
    candidateClass(candidateIndex) := uopClass.U
    val olderClassWrites = if (uopClass == 0) 0.U else
      uop.recipe.dispatchDemand.take(uopClass).reduce(_ +& _)
    candidateChild(candidateIndex) :=
      (olderClassWrites + repeat.U)(childIndexWidth - 1, 0)
    candidateIndex += 1
  }
  val requestedWrites = PopCount(candidateActive)

  val uopDispatchShapeExact = (0 until p.decodedUopWidth).map { uopIndex =>
    val uop = decoded.uops(uopIndex)
    val active = decoded.uopMask(uopIndex)
    val demandTotal = uop.recipe.dispatchDemand.reduce(_ +& _)
    !active || (uop.valid && uop.recipe.valid &&
      demandTotal === uop.recipe.dispatchWrites &&
      uop.recipe.dispatchWrites <= p.maxDispatchWritesPerInstruction.U)
  }.reduce(_ && _)
  val classDemandExact = (0 until p.iqClassCount).map { uopClass =>
    val decodedDemand = decoded.uops.zipWithIndex.map { case (uop, index) =>
      Mux(decoded.uopMask(index) && uop.valid,
        uop.recipe.dispatchDemand(uopClass), 0.U)
    }.reduce(_ +& _)
    decodedDemand === plan.demand.dispatchWritesByClass(uopClass)
  }.reduce(_ && _)
  val plannedWrites = plan.demand.dispatchWritesByClass.reduce(_ +& _)
  val requestShapeExact = uopMaskExact && identityExact &&
    uopDispatchShapeExact && classDemandExact &&
    requestedWrites === plannedWrites && requestedWrites <= p.dispatchWidth.U

  val laneActive = Wire(Vec(p.dispatchWidth, Bool()))
  val laneUop = Wire(Vec(p.dispatchWidth,
    UInt(p.decodedUopIndexWidth.W)))
  val laneClass = Wire(Vec(p.dispatchWidth,
    UInt(math.max(1, chisel3.util.log2Ceil(p.iqClassCount)).W)))
  val laneChild = Wire(Vec(p.dispatchWidth, UInt(childIndexWidth.W)))
  for (lane <- 0 until p.dispatchWidth) {
    laneActive(lane) := lane.U < requestedWrites
    val candidateSelect = Wire(Vec(candidateCount, Bool()))
    for (index <- 0 until candidateCount) {
      val olderCount = if (index == 0) 0.U else
        PopCount(candidateActive.take(index))
      candidateSelect(index) := candidateActive(index) &&
        olderCount === lane.U
    }
    laneUop(lane) := Mux1H(candidateSelect, candidateUop)
    laneClass(lane) := Mux1H(candidateSelect, candidateClass)
    laneChild(lane) := Mux1H(candidateSelect, candidateChild)
  }

  val selectedValid = Wire(Vec(p.dispatchWidth, Bool()))
  val selectedBank = Wire(Vec(p.dispatchWidth, UInt(p.iqBankWidth.W)))
  val selectedPort = Wire(Vec(p.dispatchWidth, UInt(p.iqWritePortWidth.W)))
  val selectedSlot = Wire(Vec(p.dispatchWidth, UInt(p.iqEntryWidth.W)))
  val selectedEpoch = Wire(Vec(p.dispatchWidth,
    UInt(p.reservationEpochWidth.W)))

  for (lane <- 0 until p.dispatchWidth) {
    val olderPrefixReady = if (lane == 0) true.B else
      (0 until lane).map { older =>
        !laneActive(older) || selectedValid(older)
      }.reduce(_ && _)
    val usedMask = Wire(Vec(p.iqBankCount, UInt(p.iqEntriesPerBank.W)))
    val olderWrites = Wire(Vec(p.iqBankCount,
      UInt(p.dispatchCountWidth.W)))
    for (bank <- 0 until p.iqBankCount) {
      val olderHere = if (lane == 0) Seq(false.B) else
        (0 until lane).map { older =>
          selectedValid(older) && laneClass(older) === laneClass(lane) &&
            selectedBank(older) === bank.U
        }
      olderWrites(bank) := PopCount(VecInit(olderHere).asUInt)
      val masks = if (lane == 0) Seq(0.U(p.iqEntriesPerBank.W)) else
        (0 until lane).map { older =>
          Mux(selectedValid(older) &&
            laneClass(older) === laneClass(lane) &&
            selectedBank(older) === bank.U,
            UIntToOH(selectedSlot(older), p.iqEntriesPerBank),
            0.U(p.iqEntriesPerBank.W))
        }
      usedMask(bank) := masks.reduce(_ | _)
    }

    val preferredBank = (plan.transactionId + laneUop(lane) +
      laneClass(lane))(p.iqBankWidth - 1, 0)
    val candidateBank = Wire(Vec(p.iqBankCount, UInt(p.iqBankWidth.W)))
    val candidateAvailable = Wire(Vec(p.iqBankCount,
      UInt(p.iqEntriesPerBank.W)))
    val candidateValid = Wire(Vec(p.iqBankCount, Bool()))
    val candidateSlot = Wire(Vec(p.iqBankCount, UInt(p.iqEntryWidth.W)))
    for (offset <- 0 until p.iqBankCount) {
      candidateBank(offset) := (preferredBank + offset.U)(
        p.iqBankWidth - 1, 0)
      candidateAvailable(offset) :=
        freeMask(laneClass(lane))(candidateBank(offset)) &
          ~usedMask(candidateBank(offset))
      val freeSelect = Module(new OooHierarchicalFreeSlotSelect(
        p.iqEntriesPerBank, p.iqFreeSelectLeafEntriesEffective))
      freeSelect.io.available := candidateAvailable(offset)
      candidateValid(offset) := freeSelect.io.selectedValid &&
        olderWrites(candidateBank(offset)) < p.iqWritePortsPerBank.U
      candidateSlot(offset) := freeSelect.io.selectedIndex
    }
    val chosenOffset = PriorityEncoder(candidateValid)
    val chosenBank = candidateBank(chosenOffset)
    val chosenSlot = candidateSlot(chosenOffset)
    val canSelect = candidateValid.asUInt.orR
    selectedValid(lane) := laneActive(lane) && olderPrefixReady && canSelect
    selectedBank(lane) := chosenBank
    selectedPort(lane) := olderWrites(chosenBank)(p.iqWritePortWidth - 1, 0)
    selectedSlot(lane) := chosenSlot
    selectedEpoch(lane) := slotGeneration(laneClass(lane))(chosenBank)(
      chosenSlot) + 1.U
  }

  val allSelected = (0 until p.dispatchWidth).map { lane =>
    !laneActive(lane) || selectedValid(lane)
  }.reduce(_ && _)
  val noLiveLease = !provisional(safePrepareStid).valid
  io.prepareReady := prepareStidInRange && requestShapeExact &&
    !io.cancel(safePrepareStid) &&
    !(recoveryFreeze && prepareStid === recoveryStid) &&
    noLiveLease && allSelected

  io.prepared := 0.U.asTypeOf(io.prepared)
  io.prepared.valid := io.prepare.valid && io.prepareReady
  io.prepared.peId := plan.peId
  io.prepared.stid := plan.stid
  io.prepared.epoch := plan.epoch
  io.prepared.transactionId := plan.transactionId
  io.prepared.allocationMask := VecInit((0 until p.dispatchWidth).map {
    lane => laneActive(lane) && selectedValid(lane)
  }).asUInt
  for (lane <- 0 until p.dispatchWidth) {
    val allocation = io.prepared.allocations(lane)
    allocation.valid := laneActive(lane) && selectedValid(lane)
    allocation.uopIndex := laneUop(lane)
    allocation.childIndex := laneChild(lane)
    allocation.reservation.valid := allocation.valid
    allocation.reservation.uopClass := classValues(laneClass(lane))
    allocation.reservation.bank := selectedBank(lane)
    allocation.reservation.writePort := selectedPort(lane)
    allocation.reservation.speculativeSlot := selectedSlot(lane)
    allocation.reservation.reservationEpoch := selectedEpoch(lane)
  }

  io.prepareRejected.valid := io.prepare.valid && !io.prepareReady
  io.prepareRejected.bits.stid := prepareStid
  io.prepareRejected.bits.transactionId := plan.transactionId
  io.prepareRejected.bits.requestedWrites := requestedWrites
  io.prepareRejected.bits.plannedWrites := plannedWrites
  io.prepareRejected.bits.liveLease := provisional(safePrepareStid)

  for (stid <- 0 until p.stidCount) {
    when(io.cancel(stid) && provisional(stid).valid) {
      for (lane <- 0 until p.dispatchWidth) {
        val allocation = provisional(stid).allocations(lane)
        when(allocation.valid) {
          val reservation = allocation.reservation
          slotState(reservation.uopClass.asUInt)(reservation.bank)(
            reservation.speculativeSlot) := OooDispatchSlotState.Free
          slotMemberValid(reservation.uopClass.asUInt)(reservation.bank)(
            reservation.speculativeSlot) := false.B
        }
      }
      provisional(stid) :=
        0.U.asTypeOf(new OooDispatchReservationLease(p))
    }
  }

  when(io.reserveFire) {
    assert(io.prepared.valid,
      "dispatch reserve must claim one complete prepared transaction")
    provisional(safePrepareStid) := io.prepared
    for (lane <- 0 until p.dispatchWidth) {
      val allocation = io.prepared.allocations(lane)
      when(allocation.valid) {
        val reservation = allocation.reservation
        val uopClass = reservation.uopClass.asUInt
        val bank = reservation.bank
        val slot = reservation.speculativeSlot
        assert(slotState(uopClass)(bank)(slot) === OooDispatchSlotState.Free,
          "dispatch reserve must own a free exact IQ slot")
        slotState(uopClass)(bank)(slot) := OooDispatchSlotState.Provisional
        slotGeneration(uopClass)(bank)(slot) :=
          reservation.reservationEpoch
        slotWritePort(uopClass)(bank)(slot) := reservation.writePort
        slotPeId(uopClass)(bank)(slot) := plan.peId
        slotStid(uopClass)(bank)(slot) := prepareStid
        slotEpoch(uopClass)(bank)(slot) := plan.epoch
        slotTransaction(uopClass)(bank)(slot) := plan.transactionId
        slotMemberValid(uopClass)(bank)(slot) := false.B
      }
    }
  }

  val publishStid = io.publish.bits.stid
  val publishStidInRange = publishStid < p.stidCount.U
  val safePublishStid = Mux(publishStidInRange, publishStid, 0.U)
  val publishLease = provisional(safePublishStid)
  val publishExact = publishStidInRange && !io.cancel(safePublishStid) &&
    !(recoveryFreeze && publishStid === recoveryStid) &&
    publishLease.valid &&
    publishLease.peId === io.publish.bits.peId &&
    publishLease.stid === publishStid &&
    publishLease.epoch === io.publish.bits.epoch &&
    publishLease.transactionId === io.publish.bits.transactionId &&
    io.publish.bits.memberMask === publishLease.allocationMask &&
    (0 until p.dispatchWidth).map { lane =>
      val allocation = publishLease.allocations(lane)
      val member = io.publish.bits.members(lane)
      !allocation.valid || (member.group.valid && member.bid.valid &&
        member.group.peId === publishLease.peId &&
        member.group.stid === publishLease.stid &&
        member.memberIndex < p.maxOrdinaryUopsPerGroup.U)
    }.reduce(_ && _)
  io.publishRejected.valid := io.publish.valid && !publishExact
  io.publishRejected.bits.requested := io.publish.bits
  io.publishRejected.bits.live := publishLease
  when(io.publish.valid && publishExact) {
    for (lane <- 0 until p.dispatchWidth) {
      val allocation = publishLease.allocations(lane)
      when(allocation.valid) {
        val reservation = allocation.reservation
        val uopClass = reservation.uopClass.asUInt
        val bank = reservation.bank
        val slot = reservation.speculativeSlot
        assert(slotState(uopClass)(bank)(slot) ===
          OooDispatchSlotState.Provisional &&
          slotGeneration(uopClass)(bank)(slot) ===
            reservation.reservationEpoch &&
          slotWritePort(uopClass)(bank)(slot) === reservation.writePort &&
          slotPeId(uopClass)(bank)(slot) === io.publish.bits.peId &&
          slotStid(uopClass)(bank)(slot) === publishStid &&
          slotEpoch(uopClass)(bank)(slot) === io.publish.bits.epoch &&
          slotTransaction(uopClass)(bank)(slot) ===
            io.publish.bits.transactionId,
          "dispatch publish must match every exact provisional IQ slot")
        slotState(uopClass)(bank)(slot) := OooDispatchSlotState.Published
        slotMemberValid(uopClass)(bank)(slot) := true.B
        slotMember(uopClass)(bank)(slot) := io.publish.bits.members(lane)
      }
    }
    provisional(safePublishStid) :=
      0.U.asTypeOf(new OooDispatchReservationLease(p))
  }

  val releaseClasses = Wire(Vec(p.iexReleaseWidth,
    UInt(math.max(1, chisel3.util.log2Ceil(p.iqClassCount)).W)))
  val releaseClassInRange = Wire(Vec(p.iexReleaseWidth, Bool()))
  val releaseBankInRange = Wire(Vec(p.iexReleaseWidth, Bool()))
  val releaseSlotInRange = Wire(Vec(p.iexReleaseWidth, Bool()))
  val safeReleaseClasses = Wire(Vec(p.iexReleaseWidth,
    UInt(math.max(1, chisel3.util.log2Ceil(p.iqClassCount)).W)))
  val safeReleaseBanks = Wire(Vec(p.iexReleaseWidth,
    UInt(p.iqBankWidth.W)))
  val safeReleaseSlots = Wire(Vec(p.iexReleaseWidth,
    UInt(p.iqEntryWidth.W)))
  val releaseAddressExact = Wire(Vec(p.iexReleaseWidth, Bool()))
  val releaseOwnerExact = Wire(Vec(p.iexReleaseWidth, Bool()))
  val releaseCollision = Wire(Vec(p.iexReleaseWidth, Bool()))

  for (lane <- 0 until p.iexReleaseWidth) {
    val release = io.releases(lane).bits
    releaseClasses(lane) := release.reservation.uopClass.asUInt
    releaseClassInRange(lane) := releaseClasses(lane) < p.iqClassCount.U
    releaseBankInRange(lane) := release.reservation.bank < p.iqBankCount.U
    releaseSlotInRange(lane) :=
      release.reservation.speculativeSlot < p.iqEntriesPerBank.U
    safeReleaseClasses(lane) := Mux(
      releaseClassInRange(lane), releaseClasses(lane), 0.U)
    safeReleaseBanks(lane) := Mux(
      releaseBankInRange(lane), release.reservation.bank, 0.U)
    safeReleaseSlots(lane) := Mux(
      releaseSlotInRange(lane), release.reservation.speculativeSlot, 0.U)
    releaseAddressExact(lane) := release.reservation.valid &&
      releaseClassInRange(lane) && releaseBankInRange(lane) &&
      releaseSlotInRange(lane)
    releaseOwnerExact(lane) := releaseAddressExact(lane) &&
      slotState(safeReleaseClasses(lane))(safeReleaseBanks(lane))(
        safeReleaseSlots(lane)) === OooDispatchSlotState.Published &&
      slotGeneration(safeReleaseClasses(lane))(safeReleaseBanks(lane))(
        safeReleaseSlots(lane)) === release.reservation.reservationEpoch &&
      slotWritePort(safeReleaseClasses(lane))(safeReleaseBanks(lane))(
        safeReleaseSlots(lane)) === release.reservation.writePort &&
      slotPeId(safeReleaseClasses(lane))(safeReleaseBanks(lane))(
        safeReleaseSlots(lane)) === release.peId &&
      slotStid(safeReleaseClasses(lane))(safeReleaseBanks(lane))(
        safeReleaseSlots(lane)) === release.stid &&
      slotEpoch(safeReleaseClasses(lane))(safeReleaseBanks(lane))(
        safeReleaseSlots(lane)) === release.epoch &&
      slotTransaction(safeReleaseClasses(lane))(safeReleaseBanks(lane))(
        safeReleaseSlots(lane)) === release.transactionId &&
      slotMemberValid(safeReleaseClasses(lane))(safeReleaseBanks(lane))(
        safeReleaseSlots(lane)) &&
      slotMember(safeReleaseClasses(lane))(safeReleaseBanks(lane))(
        safeReleaseSlots(lane)).asUInt === release.member.asUInt &&
      !(recoveryFreeze && release.stid === recoveryStid)
    releaseCollision(lane) := (0 until p.iexReleaseWidth).filter(_ != lane)
      .map { peer =>
        io.releases(peer).valid && releaseAddressExact(lane) &&
          releaseAddressExact(peer) &&
          safeReleaseClasses(lane) === safeReleaseClasses(peer) &&
          safeReleaseBanks(lane) === safeReleaseBanks(peer) &&
          safeReleaseSlots(lane) === safeReleaseSlots(peer)
      }.foldLeft(false.B)(_ || _)
    val releaseExact = releaseOwnerExact(lane) && !releaseCollision(lane)
    io.releases(lane).ready := releaseExact
    io.releaseRejecteds(lane).valid :=
      io.releases(lane).valid && !releaseExact
    io.releaseRejecteds(lane).bits.requested := release
    when(io.releases(lane).fire) {
      slotState(safeReleaseClasses(lane))(safeReleaseBanks(lane))(
        safeReleaseSlots(lane)) := OooDispatchSlotState.Free
      slotMemberValid(safeReleaseClasses(lane))(safeReleaseBanks(lane))(
        safeReleaseSlots(lane)) := false.B
    }
  }

  val recoveryPublishedExact = (0 until p.iqClassCount).flatMap { uopClass =>
    (0 until p.iqBankCount).flatMap { bank =>
      (0 until p.iqEntriesPerBank).map { entry =>
        val selected = slotState(uopClass)(bank)(entry) ===
          OooDispatchSlotState.Published &&
          slotStid(uopClass)(bank)(entry) === recoveryStid
        !selected || (slotMemberValid(uopClass)(bank)(entry) &&
          slotMember(uopClass)(bank)(entry).group.valid &&
          slotMember(uopClass)(bank)(entry).bid.valid &&
          slotMember(uopClass)(bank)(entry).group.peId ===
            slotPeId(uopClass)(bank)(entry) &&
          slotMember(uopClass)(bank)(entry).group.stid ===
            slotStid(uopClass)(bank)(entry) &&
          OooRecoveryMembership.memberInOldWindow(
            p, recoveryPlan, slotMember(uopClass)(bank)(entry)))
      }
    }
  }.reduce(_ && _)
  val recoveryProvisionalKilled = PopCount(VecInit(
    (0 until p.iqClassCount).flatMap { uopClass =>
      (0 until p.iqBankCount).flatMap { bank =>
        (0 until p.iqEntriesPerBank).map { entry =>
          slotState(uopClass)(bank)(entry) ===
            OooDispatchSlotState.Provisional &&
            slotStid(uopClass)(bank)(entry) === recoveryStid
        }
      }
    }).asUInt)
  val recoveryPublishedKill = Wire(Vec(p.iqClassCount,
    Vec(p.iqBankCount, Vec(p.iqEntriesPerBank, Bool()))))
  for (uopClass <- 0 until p.iqClassCount;
       bank <- 0 until p.iqBankCount;
       entry <- 0 until p.iqEntriesPerBank) {
    recoveryPublishedKill(uopClass)(bank)(entry) :=
      slotState(uopClass)(bank)(entry) === OooDispatchSlotState.Published &&
      slotStid(uopClass)(bank)(entry) === recoveryStid &&
      slotMemberValid(uopClass)(bank)(entry) &&
      OooRecoveryMembership.memberKilled(
        p, recoveryPlan, slotMember(uopClass)(bank)(entry))
  }
  val recoveryPublishedKilled = PopCount(VecInit(
    (0 until p.iqClassCount).flatMap { uopClass =>
      (0 until p.iqBankCount).flatMap { bank =>
        (0 until p.iqEntriesPerBank).map { entry =>
          recoveryPublishedKill(uopClass)(bank)(entry)
        }
      }
    }).asUInt)
  val recoveryPrepareExact = recoveryPlan.valid && recoveryStidInRange &&
    recoveryPublishedExact
  io.recoveryPrepareReady := io.recoveryPrepare.valid && recoveryPrepareExact
  io.recoveryPrepared := 0.U.asTypeOf(io.recoveryPrepared)
  io.recoveryPrepared.valid := io.recoveryPrepareReady
  io.recoveryPrepared.stid := recoveryStid
  io.recoveryPrepared.provisionalKilled := recoveryProvisionalKilled
  io.recoveryPrepared.publishedKilled := recoveryPublishedKilled
  io.recoveryRejected.valid := io.recoveryPrepare.valid &&
    !recoveryPrepareExact
  io.recoveryRejected.bits.requested := recoveryPlan
  io.recoveryRejected.bits.stidInRange := recoveryStidInRange
  io.recoveryRejected.bits.publishedMembersExact := recoveryPublishedExact

  when(io.recoveryFire) {
    assert(io.recoveryPrepareReady,
      "dispatch recovery may apply only one exact prepared ROB suffix")
    provisional(safeRecoveryStid) :=
      0.U.asTypeOf(new OooDispatchReservationLease(p))
    for (uopClass <- 0 until p.iqClassCount;
         bank <- 0 until p.iqBankCount;
         entry <- 0 until p.iqEntriesPerBank) {
      val provisionalTarget = slotState(uopClass)(bank)(entry) ===
        OooDispatchSlotState.Provisional &&
        slotStid(uopClass)(bank)(entry) === recoveryStid
      when(provisionalTarget || recoveryPublishedKill(uopClass)(bank)(entry)) {
        slotState(uopClass)(bank)(entry) := OooDispatchSlotState.Free
        slotMemberValid(uopClass)(bank)(entry) := false.B
      }
    }
  }

  for (uopClass <- 0 until p.iqClassCount; bank <- 0 until p.iqBankCount) {
    assert(io.freeEntries(uopClass)(bank) +
      io.provisionalEntries(uopClass)(bank) +
      io.publishedEntries(uopClass)(bank) === p.iqEntriesPerBank.U,
      "every dispatch IQ slot must have exactly one lifecycle owner")
    for (entry <- 0 until p.iqEntriesPerBank) {
      assert(slotMemberValid(uopClass)(bank)(entry) ===
        (slotState(uopClass)(bank)(entry) ===
          OooDispatchSlotState.Published),
        "only a published dispatch slot may retain exact ROB membership")
    }
  }
}
