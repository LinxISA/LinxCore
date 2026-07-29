package linxcore.ooo

import chisel3._
import chisel3.util.{PopCount, Valid}

class OooMemoryOrderAllocatorIO(val p: OooParams = OooParams())
    extends Bundle {
  val prepare = Flipped(Valid(new OooD2GroupedTransaction(p)))
  val prepareReady = Output(Bool())
  val prepared = Output(new OooMemoryOrderReservationLease(p))
  val reserveFire = Input(Bool())

  /** Exact D3 row selected for the common S1 publication. */
  val publishPrepare = Flipped(Valid(new OooD3GroupedReservation(p)))
  val publishReady = Output(Bool())
  val publishFire = Input(Bool())

  val cancel = Input(Vec(p.stidCount, Bool()))

  val recoveryPrepare = Flipped(Valid(new OooRobRecoveryPlan(p)))
  val recoveryPrepareReady = Output(Bool())
  val recoveryPrepared = Output(new OooMemoryOrderRecoveryPrepared(p))
  val recoveryFire = Input(Bool())

  val provisional = Output(Vec(p.stidCount,
    new OooMemoryOrderReservationLease(p)))
  val next = Output(Vec(p.stidCount, new OooMemoryIdState(p)))
  val prepareRejected = Valid(new OooMemoryOrderPrepareReject(p))
  val recoveryRejected = Valid(new OooMemoryOrderRecoveryReject(p))
}

/** Canonical full-LSID, load-ID, and store-ID owner.
  *
  * D2 supplies only demand. One exact D3 reserve fire claims all serial ranges
  * for the transaction and retains a per-STID provisional lease. Common S1
  * publication consumes that lease. Cancellation rolls back only the private
  * provisional suffix. Global recovery restores the serial tails from the
  * ROB-owned group snapshots on the same common apply used by ROB, rename,
  * dispatch, and IEX.
  *
  * The serials are never physical LHQ/STQ indexes. Future queue owners bind a
  * separate generation-qualified lease while preserving these full identities.
  */
class OooMemoryOrderAllocator(val p: OooParams = OooParams()) extends Module {
  val io = IO(new OooMemoryOrderAllocatorIO(p))

  private def sameState(left: OooMemoryIdState, right: OooMemoryIdState): Bool =
    left.asUInt === right.asUInt

  val nextState = RegInit(VecInit(Seq.fill(p.stidCount)(
    0.U.asTypeOf(new OooMemoryIdState(p)))))
  val provisionalValid = RegInit(VecInit(Seq.fill(p.stidCount)(false.B)))
  val provisionalRows = Reg(Vec(p.stidCount,
    new OooMemoryOrderReservationLease(p)))

  val prepareTransaction = io.prepare.bits
  val preparePlan = prepareTransaction.plan
  val prepareDecoded = prepareTransaction.decoded
  val prepareStid = preparePlan.stid
  val prepareStidInRange = prepareStid < p.stidCount.U
  val safePrepareStid = Mux(prepareStidInRange, prepareStid, 0.U)

  val allocationState = Wire(Vec(p.decodedUopWidth + 1,
    new OooMemoryIdState(p)))
  allocationState(0) := nextState(safePrepareStid)
  val allocations = Wire(Vec(p.decodedUopWidth,
    new OooMemoryOrderUopAllocation(p)))
  val calculatedLoadIds = Wire(Vec(p.decodedUopWidth,
    UInt(p.memoryDemandWidth.W)))
  val calculatedStoreIds = Wire(Vec(p.decodedUopWidth,
    UInt(p.memoryDemandWidth.W)))
  val uopShapeExact = Wire(Vec(p.decodedUopWidth, Bool()))

  for (uopIndex <- 0 until p.decodedUopWidth) {
    val uop = prepareDecoded.uops(uopIndex)
    val active = prepareDecoded.uopMask(uopIndex)
    val requestCount = uop.recipe.memoryRequestCount
    val typedMemory = uop.memory.valid && (uop.memory.isLoad ^ uop.memory.isStore)
    val memoryActive = active && uop.valid && uop.recipe.valid &&
      typedMemory && requestCount.orR
    val loadActive = memoryActive && uop.memory.isLoad
    val storeActive = memoryActive && uop.memory.isStore
    val allocation = allocations(uopIndex)

    allocation := 0.U.asTypeOf(allocation)
    allocation.valid := active
    allocation.memoryValid := memoryActive
    allocation.isLoad := loadActive
    allocation.isStore := storeActive
    allocation.requestCount := Mux(memoryActive, requestCount, 0.U)
    allocation.firstLsid := allocationState(uopIndex).lsid
    allocation.firstTypeId := Mux(loadActive,
      allocationState(uopIndex).loadId,
      allocationState(uopIndex).storeId)
    allocation.before := allocationState(uopIndex)
    allocation.after := allocationState(uopIndex)
    allocation.after.lsid := allocationState(uopIndex).lsid +
      Mux(memoryActive, requestCount, 0.U)
    allocation.after.loadId := allocationState(uopIndex).loadId +
      Mux(loadActive, requestCount, 0.U)
    allocation.after.storeId := allocationState(uopIndex).storeId +
      Mux(storeActive, requestCount, 0.U)
    allocationState(uopIndex + 1) := allocation.after

    calculatedLoadIds(uopIndex) := Mux(loadActive, requestCount, 0.U)
    calculatedStoreIds(uopIndex) := Mux(storeActive, requestCount, 0.U)
    uopShapeExact(uopIndex) := !active || (
      uop.valid && uop.recipe.valid &&
        (requestCount.orR === typedMemory) &&
        requestCount <= p.maxMemoryRequestsPerInstruction.U)
  }

  val calculatedLoadTotal = calculatedLoadIds.reduce(_ +& _)
  val calculatedStoreTotal = calculatedStoreIds.reduce(_ +& _)
  val identityExact = preparePlan.peId === prepareDecoded.peId &&
    preparePlan.stid === prepareDecoded.stid &&
    preparePlan.epoch === prepareDecoded.epoch &&
    preparePlan.uopMask === prepareDecoded.uopMask
  val demandExact = calculatedLoadTotal === preparePlan.demand.loadIds &&
    calculatedStoreTotal === preparePlan.demand.storeIds &&
    preparePlan.demand.loadIds === prepareDecoded.demand.loadIds &&
    preparePlan.demand.storeIds === prepareDecoded.demand.storeIds
  val shapeExact = prepareStidInRange && identityExact && demandExact &&
    uopShapeExact.asUInt.andR
  val replacesPublishedLease = io.publishFire &&
    io.publishPrepare.valid &&
    io.publishPrepare.bits.transaction.plan.stid === prepareStid
  val prepareSlotAvailable = !provisionalValid(safePrepareStid) ||
    replacesPublishedLease

  val prepared = Wire(new OooMemoryOrderReservationLease(p))
  prepared := 0.U.asTypeOf(prepared)
  prepared.valid := io.prepare.valid && shapeExact &&
    prepareSlotAvailable
  prepared.peId := preparePlan.peId
  prepared.stid := prepareStid
  prepared.epoch := preparePlan.epoch
  prepared.transactionId := preparePlan.transactionId
  prepared.uopMask := prepareDecoded.uopMask
  prepared.before := allocationState(0)
  prepared.after := allocationState(p.decodedUopWidth)
  prepared.uops := allocations
  io.prepared := prepared
  io.prepareReady := shapeExact && prepareSlotAvailable
  io.prepareRejected.valid := io.prepare.valid && !io.prepareReady
  io.prepareRejected.bits.stid := prepareStid
  io.prepareRejected.bits.transactionId := preparePlan.transactionId
  io.prepareRejected.bits.requestedLoadIds := preparePlan.demand.loadIds
  io.prepareRejected.bits.requestedStoreIds := preparePlan.demand.storeIds
  io.prepareRejected.bits.calculatedLoadIds := calculatedLoadTotal
  io.prepareRejected.bits.calculatedStoreIds := calculatedStoreTotal
  io.prepareRejected.bits.occupied := provisionalValid(safePrepareStid)

  val publishReservation = io.publishPrepare.bits
  val publishPlan = publishReservation.transaction.plan
  val publishStid = publishPlan.stid
  val publishStidInRange = publishStid < p.stidCount.U
  val safePublishStid = Mux(publishStidInRange, publishStid, 0.U)
  val livePublishLease = provisionalRows(safePublishStid)
  val publishExact = publishStidInRange &&
    provisionalValid(safePublishStid) && livePublishLease.valid &&
    livePublishLease.peId === publishPlan.peId &&
    livePublishLease.stid === publishPlan.stid &&
    livePublishLease.epoch === publishPlan.epoch &&
    livePublishLease.transactionId === publishPlan.transactionId &&
    livePublishLease.uopMask === publishPlan.uopMask &&
    sameState(livePublishLease.after, nextState(safePublishStid))
  io.publishReady := io.publishPrepare.valid && publishExact

  val recoveryPlan = io.recoveryPrepare.bits
  val recoveryStid = recoveryPlan.request.rename.key.member.group.stid
  val recoveryStidInRange = recoveryStid < p.stidCount.U
  val safeRecoveryStid = Mux(recoveryStidInRange, recoveryStid, 0.U)
  val recoveryKilledCount = recoveryPlan.killedGroupCount
  val safeLastKilledIndex = Mux(recoveryKilledCount.orR,
    recoveryKilledCount - 1.U, 0.U)(p.ridSlotWidth - 1, 0)
  val lastKilled = recoveryPlan.killedGroups(safeLastKilledIndex)
  val oldPublishedTailRow = Mux(recoveryKilledCount.orR,
    lastKilled, recoveryPlan.pivot)
  val oldPublishedTail = oldPublishedTailRow.memoryAfter
  val newTail = Mux(recoveryPlan.newOccupied.orR,
    recoveryPlan.survivingTail.memoryAfter,
    recoveryPlan.pivot.memoryBefore)

  val killedRowsExact = Wire(Vec(p.robGroupsPerStid, Bool()))
  val killedChainExact = Wire(Vec(p.robGroupsPerStid, Bool()))
  for (index <- 0 until p.robGroupsPerStid) {
    val active = index.U < recoveryKilledCount
    val row = recoveryPlan.killedGroups(index)
    val previousTail = if (index == 0) {
      Mux(recoveryPlan.survivingPivotValid,
        recoveryPlan.pivot.memoryAfter,
        Mux(recoveryPlan.newOccupied.orR,
          recoveryPlan.survivingTail.memoryAfter,
          recoveryPlan.pivot.memoryBefore))
    } else {
      recoveryPlan.killedGroups(index - 1).memoryAfter
    }
    killedRowsExact(index) := !active ||
      (row.valid && row.memoryOrderValid)
    killedChainExact(index) := !active ||
      sameState(row.memoryBefore, previousTail)
  }
  val publishedChainExact = recoveryPlan.valid &&
    recoveryPlan.oldOccupied.orR && recoveryPlan.pivot.memoryOrderValid &&
    (!recoveryPlan.newOccupied.orR ||
      (recoveryPlan.survivingTailValid &&
        recoveryPlan.survivingTail.memoryOrderValid)) &&
    oldPublishedTailRow.valid && oldPublishedTailRow.memoryOrderValid &&
    killedRowsExact.asUInt.andR && killedChainExact.asUInt.andR

  val recoveryHasProvisional = provisionalValid(safeRecoveryStid)
  val recoveryProvisional = provisionalRows(safeRecoveryStid)
  val provisionalExact = !recoveryHasProvisional || (
    recoveryProvisional.valid &&
      recoveryProvisional.stid === recoveryStid &&
      sameState(recoveryProvisional.before, oldPublishedTail) &&
      sameState(recoveryProvisional.after, nextState(safeRecoveryStid)))
  val liveTailExact = Mux(recoveryHasProvisional,
    sameState(nextState(safeRecoveryStid), recoveryProvisional.after),
    sameState(nextState(safeRecoveryStid), oldPublishedTail))
  val recoveryExact = io.recoveryPrepare.valid && recoveryPlan.valid &&
    recoveryStidInRange && publishedChainExact && provisionalExact &&
    liveTailExact

  io.recoveryPrepareReady := recoveryExact
  io.recoveryPrepared.valid := recoveryExact
  io.recoveryPrepared.stid := recoveryStid
  io.recoveryPrepared.oldPublishedTail := oldPublishedTail
  io.recoveryPrepared.newTail := newTail
  io.recoveryPrepared.provisionalKilled := recoveryHasProvisional
  io.recoveryRejected.valid := io.recoveryPrepare.valid && !recoveryExact
  io.recoveryRejected.bits.requested := recoveryPlan
  io.recoveryRejected.bits.stidInRange := recoveryStidInRange
  io.recoveryRejected.bits.publishedChainExact := publishedChainExact
  io.recoveryRejected.bits.liveTailExact := liveTailExact
  io.recoveryRejected.bits.provisionalExact := provisionalExact

  when(io.reserveFire) {
    assert(io.prepare.valid && io.prepareReady && prepared.valid,
      "memory-order reserve requires one exact prepared D2 transaction")
    provisionalValid(safePrepareStid) := true.B
    provisionalRows(safePrepareStid) := prepared
    nextState(safePrepareStid) := prepared.after
  }

  when(io.publishFire) {
    assert(io.publishPrepare.valid && io.publishReady,
      "memory-order publication requires the retained exact D3 lease")
    when(!(io.reserveFire && safePrepareStid === safePublishStid)) {
      provisionalValid(safePublishStid) := false.B
      provisionalRows(safePublishStid) :=
        0.U.asTypeOf(new OooMemoryOrderReservationLease(p))
    }
  }

  for (stid <- 0 until p.stidCount) {
    val cancelProvisional = io.cancel(stid) && provisionalValid(stid)
    when(cancelProvisional) {
      assert(!(io.publishFire && safePublishStid === stid.U),
        "an exposed common S1 publication cannot be canceled privately")
      nextState(stid) := provisionalRows(stid).before
      provisionalValid(stid) := false.B
      provisionalRows(stid) :=
        0.U.asTypeOf(new OooMemoryOrderReservationLease(p))
    }
  }

  when(io.recoveryFire) {
    assert(io.recoveryPrepare.valid && io.recoveryPrepareReady,
      "memory-order recovery requires the same exact ROB suffix plan")
    nextState(safeRecoveryStid) := newTail
    provisionalValid(safeRecoveryStid) := false.B
    provisionalRows(safeRecoveryStid) :=
      0.U.asTypeOf(new OooMemoryOrderReservationLease(p))
  }

  when(io.reserveFire) {
    assert(!io.cancel(safePrepareStid),
      "memory-order reserve and cancel cannot target the same STID")
  }
  when(io.recoveryFire) {
    assert(!(io.reserveFire && safePrepareStid === safeRecoveryStid),
      "memory-order reserve and recovery cannot target the same STID")
    assert(!(io.publishFire && safePublishStid === safeRecoveryStid),
      "memory-order publication and recovery cannot target the same STID")
  }

  for (stid <- 0 until p.stidCount) {
    io.provisional(stid) := Mux(provisionalValid(stid),
      provisionalRows(stid),
      0.U.asTypeOf(new OooMemoryOrderReservationLease(p)))
  }
  io.next := nextState
}
