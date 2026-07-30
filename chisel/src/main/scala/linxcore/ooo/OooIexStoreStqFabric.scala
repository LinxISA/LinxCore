package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, Mux1H, OHToUInt, PopCount, RRArbiter, Valid,
  log2Ceil}

import linxcore.lsu.{STQEntryBank, STQEntryBankRow, STQEntryStatus,
  STQStoreRequest}

class OooIexStoreStqFabricIO(
    val p: OooParams,
    val stqEntries: Int) extends Bundle {
  val reserve = Flipped(Decoupled(new OooIexIssueRow(p)))
  val storeAddress = Flipped(Vec(2,
    Decoupled(new OooIexExecuteTransaction(p))))
  val storeData = Flipped(Vec(2,
    Decoupled(new OooIexExecuteTransaction(p))))
  val recoveryPrepare = Flipped(Valid(new OooResidencyRecoveryPlan(p)))
  val recoveryPrepareReady = Output(Bool())
  val recoveryFire = Input(Bool())
  val recoveryApplied = Output(Bool())
  val loadCancel = Input(Vec(p.iexLoadCancelPorts,
    Valid(new OooIexLoadCancel(p))))

  val markCommitValid = Input(Bool())
  val markCommitIndex = Input(UInt(log2Ceil(stqEntries).W))
  val markCommitAccepted = Output(Bool())
  val commitFreeMaskValid = Input(Bool())
  val commitFreeMask = Input(UInt(stqEntries.W))
  val commitFreeAcceptedMask = Output(UInt(stqEntries.W))

  val reserveAccepted = Output(Bool())
  val reserveRejected = Output(Bool())
  val leaseLookupRejected = Output(Vec(4, Bool()))
  val fillConflict = Output(Bool())
  val recoveryFreeMask = Output(UInt(stqEntries.W))
  val recoveryBlockedMask = Output(UInt(stqEntries.W))
  val recoveryPartialStoreCut = Output(Bool())
  val recoveryRejected = Output(Bool())

  val rows = Output(Vec(stqEntries, new STQEntryBankRow(
    p.robGroupsPerStid,
    peIdWidth = p.peIdWidth,
    stidWidth = p.stidWidth,
    tidWidth = p.stidWidth,
    mapQDepth = p.tuMapQDepthPerStid,
    lsidWidth = p.lsidWidth,
    nativeBidWidth = p.nativeBidWidth,
    ridGenerationWidth = p.ridGenerationWidth,
    brobGenerationWidth = p.brobGenerationWidth,
    memberIndexWidth = p.robMemberIndexWidth,
    residentGenerationWidth = p.residentGenerationWidth,
    leaseGenerationWidth = p.executeSlotGenerationWidth)))
  val occupiedMask = Output(UInt(stqEntries.W))
  val addrReadyMask = Output(UInt(stqEntries.W))
  val dataReadyMask = Output(UInt(stqEntries.W))
  val residentCount = Output(UInt(log2Ceil(stqEntries + 1).W))
  val storePipelinesOccupied = Output(UInt(2.W))
  val empty = Output(Bool())
}

/** Canonical static store-execution/STQ composition.
  *
  * The IQ owner must reserve every logical store through `reserve` before S2
  * publication.  STA and STD then recover the exact one/two-row lease only
  * from canonical STQ residency and independently fill those rows.  There is
  * no pre-STQ address/data join state.
  */
class OooIexStoreStqFabric(
    val p: OooParams = OooParams(),
    val stqEntries: Int = 16) extends Module {
  require(stqEntries > 1 && (stqEntries & (stqEntries - 1)) == 0,
    "STQ capacity must be a power of two greater than one")
  require(p.maxMemoryRequestsPerInstruction == 2,
    "static store fabric currently supports one or two store beats")

  val io = IO(new OooIexStoreStqFabricIO(p, stqEntries))
  val reservation = Module(new OooStqReservationProjection(p, stqEntries))
  val recovery = Module(new OooStqRecoveryProjection(p, stqEntries))
  val stores = Seq.fill(2)(Module(new OooIexStorePipeline(p, stqEntries)))
  val fillArbiter = Module(new RRArbiter(chiselTypeOf(
    stores.head.io.fill.bits), stores.length))
  val stq = Module(new STQEntryBank(
    entries = stqEntries,
    peIdWidth = p.peIdWidth,
    stidWidth = p.stidWidth,
    tidWidth = p.stidWidth,
    mapQDepth = p.tuMapQDepthPerStid,
    robEntries = p.robGroupsPerStid,
    lsidWidth = p.lsidWidth,
    nativeBidWidth = p.nativeBidWidth,
    ridGenerationWidth = p.ridGenerationWidth,
    brobGenerationWidth = p.brobGenerationWidth,
    memberIndexWidth = p.robMemberIndexWidth,
    residentGenerationWidth = p.residentGenerationWidth,
    leaseGenerationWidth = p.executeSlotGenerationWidth,
    maxReserveBeats = p.maxMemoryRequestsPerInstruction))

  val recoveryFence = io.recoveryPrepare.valid

  reservation.io.inputValid := io.reserve.valid && !recoveryFence
  reservation.io.input := io.reserve.bits
  stq.io.reserveBatchValid := reservation.io.reserveValid
  stq.io.reserveBatchMask := reservation.io.reserveMask
  stq.io.reserveBatch := reservation.io.reserve
  io.reserve.ready := !recoveryFence && reservation.io.reserveValid &&
    stq.io.reserveBatchReady
  io.reserveAccepted := io.reserve.fire
  io.reserveRejected := io.reserve.valid && reservation.io.rejected

  recovery.io.recoveryValid := io.recoveryPrepare.valid
  recovery.io.recovery := io.recoveryPrepare.bits
  recovery.io.rows := stq.io.rows
  val partialPivot = io.recoveryPrepare.bits.survivingPivotValid &&
    io.recoveryPrepare.bits.survivingPivotPhysicalMemberCount =/= 0.U &&
    io.recoveryPrepare.bits.survivingPivotPhysicalMemberCount <
      io.recoveryPrepare.bits.pivotPhysicalMemberCount
  val pivotStoreRows = VecInit(stq.io.rows.map { row =>
    val owner = row.exactOwner
    val pivot = io.recoveryPrepare.bits.pivot
    io.recoveryPrepare.valid && row.valid && owner.valid &&
      owner.nativeBidValid && pivot.group.valid && pivot.bid.valid &&
      owner.peId === pivot.group.peId &&
      owner.stid === pivot.group.stid &&
      owner.nativeBid === pivot.bid.value &&
      owner.brobGeneration === pivot.brobGeneration &&
      owner.ridSlot === pivot.group.ridSlot &&
      owner.ridGeneration === pivot.group.ridGeneration &&
      owner.memberIndex === pivot.memberIndex &&
      owner.residentGeneration === pivot.residentGeneration
  })
  io.recoveryPartialStoreCut := partialPivot && pivotStoreRows.asUInt.orR
  io.recoveryPrepareReady := io.recoveryPrepare.valid &&
    !recovery.io.rejected && !recovery.io.statusBlockedMask.orR &&
    !io.recoveryPartialStoreCut
  val recoveryAccepted = io.recoveryFire && io.recoveryPrepareReady
  io.recoveryApplied := recoveryAccepted
  stq.io.exactRecoveryValid := recoveryAccepted
  stq.io.exactRecoveryFreeMask := recovery.io.freeMask
  io.recoveryFreeMask := Mux(io.recoveryPrepareReady,
    recovery.io.freeMask, 0.U)
  io.recoveryBlockedMask := recovery.io.statusBlockedMask
  io.recoveryRejected := io.recoveryPrepare.valid &&
    !io.recoveryPrepareReady

  stq.io.flush := 0.U.asTypeOf(stq.io.flush)
  stq.io.insertValid := false.B
  stq.io.insert := 0.U.asTypeOf(stq.io.insert)
  stq.io.reserveValid := false.B
  stq.io.reserve := 0.U.asTypeOf(stq.io.reserve)
  stq.io.markCommitValid := io.markCommitValid && !recoveryFence
  stq.io.markCommitIndex := io.markCommitIndex
  io.markCommitAccepted := stq.io.markCommitAccepted
  stq.io.commitFreeValid := false.B
  stq.io.commitFreeIndex := 0.U
  stq.io.commitFreeMaskValid := io.commitFreeMaskValid && !recoveryFence
  stq.io.commitFreeMask := io.commitFreeMask
  io.commitFreeAcceptedMask := stq.io.commitFreeAcceptedMask

  private def exactOwnerMatches(
      row: STQEntryBankRow,
      execute: OooIexExecuteTransaction): Bool = {
    val member = execute.i2.row.member
    val logicalIndex = member.memberIndex - execute.i2.row.childIndex
    row.exactOwner.valid && row.exactOwner.nativeBidValid &&
      row.exactOwner.peId === member.group.peId &&
      row.exactOwner.stid === member.group.stid &&
      row.exactOwner.nativeBid === member.bid.value &&
      row.exactOwner.brobGeneration === member.brobGeneration &&
      row.exactOwner.ridSlot === member.group.ridSlot &&
      row.exactOwner.ridGeneration === member.group.ridGeneration &&
      row.exactOwner.memberIndex === logicalIndex &&
      row.exactOwner.residentGeneration === member.residentGeneration
  }

  private def leaseFor(
      execute: OooIexExecuteTransaction): (OooStqLeaseSet, Bool) = {
    val lease = Wire(new OooStqLeaseSet(p, stqEntries))
    val row = execute.i2.row
    val logical = Wire(new RobMemberKey(p))
    logical := row.member
    logical.memberIndex := row.member.memberIndex - row.childIndex
    lease := 0.U.asTypeOf(lease)
    lease.logicalMember := logical
    lease.requestCount := row.memoryOrder.requestCount
    lease.firstLsid := row.memoryOrder.firstLsid
    lease.firstStoreId := row.memoryOrder.firstTypeId

    val beatExact = Wire(Vec(p.maxMemoryRequestsPerInstruction, Bool()))
    for (beat <- 0 until p.maxMemoryRequestsPerInstruction) {
      val matches = VecInit((0 until stqEntries).map { index =>
        val resident = stq.io.rows(index)
        resident.valid && resident.status === STQEntryStatus.Wait &&
          exactOwnerMatches(resident, execute) &&
          resident.logicalStoreValid &&
          resident.logicalFirstLsid === row.memoryOrder.firstLsid &&
          resident.logicalFirstStoreId === row.memoryOrder.firstTypeId &&
          resident.logicalRequestCount === row.memoryOrder.requestCount &&
          resident.logicalBeat === beat.U &&
          resident.lsIdFull === row.memoryOrder.firstLsid + beat.U &&
          resident.storeIdFullValid &&
          resident.storeIdFull === row.memoryOrder.firstTypeId + beat.U
      })
      val required = beat.U < row.memoryOrder.requestCount
      beatExact(beat) := Mux(required, PopCount(matches) === 1.U,
        !matches.asUInt.orR)
      lease.leases(beat).valid := required && PopCount(matches) === 1.U
      lease.leases(beat).index := OHToUInt(matches)
      lease.leases(beat).generation := Mux1H(matches,
        stq.io.rows.map(_.leaseGeneration))
    }
    val requestShape = row.valid && row.member.group.valid &&
      row.member.bid.valid && row.memory.valid && row.memory.isStore &&
      !row.memory.isLoad && row.memoryOrder.valid &&
      row.memoryOrder.memoryValid && row.memoryOrder.isStore &&
      !row.memoryOrder.isLoad &&
      ((row.memoryOrder.requestCount === 1.U) ||
        (row.memoryOrder.requestCount === 2.U)) &&
      row.childIndex <= 1.U && row.member.memberIndex >= row.childIndex
    val exact = requestShape && beatExact.asUInt.andR
    lease.valid := exact
    (lease, exact)
  }

  for (index <- 0 until stores.length) {
    val (staLease, staLeaseExact) = leaseFor(io.storeAddress(index).bits)
    val (stdLease, stdLeaseExact) = leaseFor(io.storeData(index).bits)
    stores(index).io.sta.valid :=
      io.storeAddress(index).valid && staLeaseExact && !recoveryFence
    stores(index).io.sta.bits.execute := io.storeAddress(index).bits
    stores(index).io.sta.bits.lease := staLease
    io.storeAddress(index).ready :=
      !recoveryFence && staLeaseExact && stores(index).io.sta.ready
    stores(index).io.std.valid := io.storeData(index).valid && stdLeaseExact &&
      !recoveryFence
    stores(index).io.std.bits.execute := io.storeData(index).bits
    stores(index).io.std.bits.lease := stdLease
    io.storeData(index).ready :=
      !recoveryFence && stdLeaseExact && stores(index).io.std.ready
    io.leaseLookupRejected(index) :=
      io.storeAddress(index).valid && !staLeaseExact
    io.leaseLookupRejected(2 + index) :=
      io.storeData(index).valid && !stdLeaseExact
    stores(index).io.recoveryApply.valid := recoveryAccepted
    stores(index).io.recoveryApply.bits := io.recoveryPrepare.bits
    stores(index).io.loadCancel := io.loadCancel
    fillArbiter.io.in(index) <> stores(index).io.fill
  }

  stq.io.fillValid := fillArbiter.io.out.valid && !recoveryFence
  stq.io.fill := fillArbiter.io.out.bits
  fillArbiter.io.out.ready := stq.io.fillReady && !recoveryFence
  io.fillConflict := stq.io.fillConflict

  io.rows := stq.io.rows
  io.occupiedMask := stq.io.occupiedMask
  io.addrReadyMask := stq.io.addrReadyMask
  io.dataReadyMask := stq.io.dataReadyMask
  io.residentCount := stq.io.residentCount
  io.storePipelinesOccupied := VecInit(stores.map(_.io.occupied)).asUInt
  io.empty := stq.io.empty && !io.storePipelinesOccupied.orR

  when(io.recoveryFire) {
    assert(io.recoveryPrepare.valid && io.recoveryPrepareReady,
      "store/STQ recovery needs one exact prepared WAIT-only projection")
  }
  when(io.reserve.fire) {
    assert(stq.io.reserveBatchAccepted,
      "store reservation and canonical STQ allocation must fire atomically")
  }
}
