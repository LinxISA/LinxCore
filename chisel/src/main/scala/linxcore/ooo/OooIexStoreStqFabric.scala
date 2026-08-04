package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, Mux1H, OHToUInt, PopCount, RRArbiter, Valid,
  log2Ceil}

import linxcore.lsu.{MDBConflictStoreProbe, STQDataBank, STQEntryBank,
  STQEntryBankRow, STQEntryStatus, STQLoadForwardQuery,
  STQLoadForwardResponse, STQLoadForwardingPipeline, STQStoreRequest,
  STQStoreType}
import linxcore.params.CoreParams
import linxcore.top.interface.{RecoveryPhase, RecoveryPlan,
  RecoveryPlanContract, RecoveryTargetIO}

class OooIexStoreStqFabricIO(
    val core: CoreParams,
    val stqEntries: Int) extends Bundle {
  val p: OooParams = OooIexPhysicalProfile.fromCoreParams(core).params
  val reserve = Flipped(Decoupled(new OooIexIssueRow(p)))
  val storeAddress = Flipped(Vec(core.lsu.storePipes,
    Decoupled(new OooIexExecuteTransaction(p))))
  val storeData = Flipped(Vec(core.lsu.storePipes,
    Decoupled(new OooIexExecuteTransaction(p))))
  val recovery = Flipped(new RecoveryTargetIO(core))
  val recoveryApplied = Output(Bool())
  val loadCancel = Input(Vec(p.iexLoadCancelPorts,
    Valid(new OooIexLoadCancel(p))))
  val loadForwardQuery = Flipped(Vec(core.lsu.loadPipes, Decoupled(
    new STQLoadForwardQuery(
      p.robIdentityGroupsPerStid, stidWidth = p.stidWidth,
      lsidWidth = p.lsidWidth, tokenWidth = p.transactionIdWidth))))
  val loadForwardResponse = Vec(core.lsu.loadPipes, Decoupled(
    new STQLoadForwardResponse(
      p.robIdentityGroupsPerStid, stqEntries, stidWidth = p.stidWidth,
      lsidWidth = p.lsidWidth, tokenWidth = p.transactionIdWidth)))
  val loadForwardOccupied = Output(UInt(core.lsu.loadPipes.W))
  val lateStaProbe = Output(Valid(new MDBConflictStoreProbe(
    p.robIdentityGroupsPerStid, peIdWidth = p.peIdWidth,
    stidWidth = p.stidWidth, tidWidth = p.stidWidth,
    sizeWidth = 7, lsidWidth = p.lsidWidth)))
  val lateStaCandidate = Output(Valid(new MDBConflictStoreProbe(
    p.robIdentityGroupsPerStid, peIdWidth = p.peIdWidth,
    stidWidth = p.stidWidth, tidWidth = p.stidWidth,
    sizeWidth = 7, lsidWidth = p.lsidWidth)))
  val lateStaPermit = Input(Bool())

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
  val recoveryRejected = Output(Bool())

  val rows = Output(Vec(stqEntries, new STQEntryBankRow(
    p.robIdentityGroupsPerStid,
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
    val core: CoreParams,
    val stqEntries: Int = 16) extends Module {
  val p: OooParams = OooIexPhysicalProfile.fromCoreParams(core).params
  OooRecoveryMembership.requireCompatible(p, core)
  require(stqEntries > 1 && (stqEntries & (stqEntries - 1)) == 0,
    "STQ capacity must be a power of two greater than one")
  require(p.maxMemoryRequestsPerInstruction == 2,
    "static store fabric currently supports one or two store beats")

  require(core.lsu.storePipes == 2,
    "the current store fabric owns exactly two STA and two STD pipes")
  val io = IO(new OooIexStoreStqFabricIO(core, stqEntries))
  val reservation = Module(new OooStqReservationProjection(p, stqEntries))
  val recovery = Module(new OooStqRecoveryProjection(core, stqEntries))
  val stores = Seq.fill(core.lsu.storePipes)(
    Module(new OooIexStorePipeline(core, stqEntries)))
  val addressFillArbiter = Module(new RRArbiter(chiselTypeOf(
    stores.head.io.fill.bits), stores.length))
  val stq = Module(new STQEntryBank(
    entries = stqEntries,
    peIdWidth = p.peIdWidth,
    stidWidth = p.stidWidth,
    tidWidth = p.stidWidth,
    mapQDepth = p.tuMapQDepthPerStid,
    robEntries = p.robIdentityGroupsPerStid,
    lsidWidth = p.lsidWidth,
    nativeBidWidth = p.nativeBidWidth,
    ridGenerationWidth = p.ridGenerationWidth,
    brobGenerationWidth = p.brobGenerationWidth,
    memberIndexWidth = p.robMemberIndexWidth,
    residentGenerationWidth = p.residentGenerationWidth,
    leaseGenerationWidth = p.executeSlotGenerationWidth,
    maxReserveBeats = p.maxMemoryRequestsPerInstruction))
  val dataBank = Module(new STQDataBank(
    entries = stqEntries,
    peIdWidth = p.peIdWidth,
    stidWidth = p.stidWidth,
    tidWidth = p.stidWidth,
    mapQDepth = p.tuMapQDepthPerStid,
    robEntries = p.robIdentityGroupsPerStid,
    lsidWidth = p.lsidWidth,
    nativeBidWidth = p.nativeBidWidth,
    ridGenerationWidth = p.ridGenerationWidth,
    brobGenerationWidth = p.brobGenerationWidth,
    memberIndexWidth = p.robMemberIndexWidth,
    residentGenerationWidth = p.residentGenerationWidth,
    leaseGenerationWidth = p.executeSlotGenerationWidth,
    writePorts = stores.length))
  val loadForward = Module(new STQLoadForwardingPipeline(
    loadPipes = core.lsu.loadPipes,
    stqEntries = stqEntries,
    robEntries = p.robIdentityGroupsPerStid,
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
    leaseGenerationWidth = p.executeSlotGenerationWidth,
    tokenWidth = p.transactionIdWidth))

  val recoveryPending = RegInit(false.B)
  val preparedValid = RegInit(false.B)
  val retainedRecovery = Reg(new RecoveryPlan(core))
  val retainedFreeMask = Reg(UInt(stqEntries.W))
  val retainedBlockedMask = Reg(UInt(stqEntries.W))

  val prepareShapeExact =
    io.recovery.prepare.bits.phase === RecoveryPhase.Prepare &&
    io.recovery.prepare.bits.trigger.stid < p.stidCount.U &&
    RecoveryPlanContract.legalSuffixWindow(io.recovery.prepare.bits)
  recovery.io.prepareValid := io.recovery.prepare.valid && prepareShapeExact
  recovery.io.prepare := io.recovery.prepare.bits
  recovery.io.rows := stq.io.rows
  val prepareAcceptable = prepareShapeExact && !recovery.io.rejected &&
    !recovery.io.statusBlockedMask.orR
  io.recovery.prepare.ready := !recoveryPending && prepareAcceptable
  io.recovery.prepared.valid := preparedValid
  io.recovery.prepared.bits := retainedRecovery

  val terminalConflict = io.recovery.apply.valid && io.recovery.abort.valid
  val matchingApply = recoveryPending && !terminalConflict &&
    io.recovery.apply.valid &&
    io.recovery.apply.bits.phase === RecoveryPhase.Apply &&
    RecoveryPlanContract.sameTransactionIgnoringPhase(
      io.recovery.apply.bits, retainedRecovery)
  val matchingAbort = recoveryPending && !terminalConflict &&
    io.recovery.abort.valid &&
    io.recovery.abort.bits.phase === RecoveryPhase.Abort &&
    RecoveryPlanContract.sameTransactionIgnoringPhase(
      io.recovery.abort.bits, retainedRecovery)

  when(io.recovery.prepare.fire) {
    recoveryPending := true.B
    preparedValid := true.B
    retainedRecovery := io.recovery.prepare.bits
    retainedFreeMask := recovery.io.freeMask
    retainedBlockedMask := recovery.io.statusBlockedMask
  }.elsewhen(matchingApply || matchingAbort) {
    recoveryPending := false.B
    preparedValid := false.B
  }.elsewhen(io.recovery.prepared.fire) {
    preparedValid := false.B
  }

  private def stidFenced(stid: UInt): Bool = {
    val preparing = io.recovery.prepare.valid && prepareShapeExact &&
      io.recovery.prepare.bits.trigger.stid === stid
    val pending = recoveryPending && retainedRecovery.trigger.stid === stid
    preparing || pending
  }

  val reserveFence = stidFenced(io.reserve.bits.stid)

  // Shape-check the offered payload independently of valid so Decoupled
  // ready never feeds back through a producer that atomically joins valid to
  // this reservation dependency.
  reservation.io.inputValid := !reserveFence
  reservation.io.input := io.reserve.bits
  stq.io.reserveBatchValid := io.reserve.valid && reservation.io.reserveValid
  stq.io.reserveBatchMask := reservation.io.reserveMask
  stq.io.reserveBatch := reservation.io.reserve
  io.reserve.ready := !reserveFence && reservation.io.reserveValid &&
    stq.io.reserveBatchReady
  io.reserveAccepted := io.reserve.fire
  io.reserveRejected := io.reserve.valid && reservation.io.rejected

  io.recoveryApplied := matchingApply
  stq.io.exactRecoveryValid := matchingApply
  stq.io.exactRecoveryFreeMask := retainedFreeMask
  io.recoveryFreeMask := Mux(recoveryPending, retainedFreeMask,
    Mux(io.recovery.prepare.valid && prepareAcceptable,
      recovery.io.freeMask, 0.U))
  io.recoveryBlockedMask := Mux(recoveryPending, retainedBlockedMask,
    recovery.io.statusBlockedMask)
  io.recoveryRejected := io.recovery.prepare.valid &&
    !io.recovery.prepare.ready

  stq.io.flush := 0.U.asTypeOf(stq.io.flush)
  stq.io.insertValid := false.B
  stq.io.insert := 0.U.asTypeOf(stq.io.insert)
  stq.io.reserveValid := false.B
  stq.io.reserve := 0.U.asTypeOf(stq.io.reserve)
  val markCommitRow = stq.io.rows(io.markCommitIndex)
  val markCommitFence = markCommitRow.valid &&
    stidFenced(markCommitRow.stid)
  stq.io.markCommitValid := io.markCommitValid && !markCommitFence
  stq.io.markCommitIndex := io.markCommitIndex
  io.markCommitAccepted := stq.io.markCommitAccepted
  stq.io.commitFreeValid := false.B
  stq.io.commitFreeIndex := 0.U
  val recoveryTargetRows = VecInit(stq.io.rows.map { row =>
    row.valid && stidFenced(row.stid)
  }).asUInt
  val peerCommitFreeMask = io.commitFreeMask & ~recoveryTargetRows
  stq.io.commitFreeMaskValid := io.commitFreeMaskValid &&
    peerCommitFreeMask.orR
  stq.io.commitFreeMask := peerCommitFreeMask
  io.commitFreeAcceptedMask := stq.io.commitFreeAcceptedMask
  dataBank.io.residentRows := stq.io.rows
  dataBank.io.hold := false.B
  dataBank.io.clearMask := stq.io.exactRecoveryAcceptedMask |
    stq.io.commitFreeAcceptedMask
  stq.io.dataCompletions := dataBank.io.completions
  loadForward.io.hold := false.B
  loadForward.io.flush := false.B
  loadForward.io.metadataRows := stq.io.rows
  loadForward.io.dataRows := dataBank.io.rows
  for (pipe <- 0 until core.lsu.loadPipes) {
    val queryFence = stidFenced(io.loadForwardQuery(pipe).bits.stid)
    loadForward.io.queries(pipe).valid :=
      io.loadForwardQuery(pipe).valid && !queryFence
    loadForward.io.queries(pipe).bits := io.loadForwardQuery(pipe).bits
    io.loadForwardQuery(pipe).ready :=
      loadForward.io.queries(pipe).ready && !queryFence
    io.loadForwardResponse(pipe) <> loadForward.io.responses(pipe)
  }
  io.loadForwardOccupied := loadForward.io.occupied

  private def exactOwnerMatches(
      row: STQEntryBankRow,
      execute: OooIexExecuteTransaction): Bool = {
    val member = execute.i2.row.member
    row.exactOwner.valid && row.exactOwner.nativeBidValid &&
      row.exactOwner.peId === member.group.peId &&
      row.exactOwner.stid === member.group.stid &&
      row.exactOwner.nativeBid === member.bid.value &&
      row.exactOwner.brobGeneration === member.brobGeneration &&
      row.exactOwner.ridSlot === member.group.ridSlot &&
      row.exactOwner.ridGeneration === member.group.ridGeneration &&
      row.exactOwner.memberIndex === member.memberIndex &&
      row.exactOwner.residentGeneration === member.residentGeneration
  }

  private def leaseFor(
      execute: OooIexExecuteTransaction): (OooStqLeaseSet, Bool) = {
    val lease = Wire(new OooStqLeaseSet(p, stqEntries))
    val row = execute.i2.row
    val logical = Wire(new RobMemberKey(p))
    logical := row.member
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
    val childShape =
      (execute.ownerClass === OooUopClass.Agu &&
        row.reservation.uopClass === OooUopClass.Agu &&
        row.childIndex === 0.U) ||
      (execute.ownerClass === OooUopClass.Std &&
        row.reservation.uopClass === OooUopClass.Std &&
        row.childIndex === 1.U)
    val requestShape = row.valid && row.member.group.valid &&
      row.member.bid.valid && row.memory.valid && row.memory.isStore &&
      !row.memory.isLoad && row.memoryOrder.valid &&
      row.memoryOrder.memoryValid && row.memoryOrder.isStore &&
      !row.memoryOrder.isLoad &&
      ((row.memoryOrder.requestCount === 1.U) ||
        (row.memoryOrder.requestCount === 2.U)) &&
      childShape
    val exact = requestShape && beatExact.asUInt.andR
    lease.valid := exact
    (lease, exact)
  }

  for (index <- 0 until stores.length) {
    val (staLease, staLeaseExact) = leaseFor(io.storeAddress(index).bits)
    val (stdLease, stdLeaseExact) = leaseFor(io.storeData(index).bits)
    val staRecoveryFence = stidFenced(
      io.storeAddress(index).bits.i2.row.stid)
    val stdRecoveryFence = stidFenced(
      io.storeData(index).bits.i2.row.stid)
    stores(index).io.sta.valid :=
      io.storeAddress(index).valid && staLeaseExact && !staRecoveryFence
    stores(index).io.sta.bits.execute := io.storeAddress(index).bits
    stores(index).io.sta.bits.lease := staLease
    io.storeAddress(index).ready :=
      !staRecoveryFence && staLeaseExact && stores(index).io.sta.ready
    stores(index).io.std.valid := io.storeData(index).valid && stdLeaseExact &&
      !stdRecoveryFence
    stores(index).io.std.bits.execute := io.storeData(index).bits
    stores(index).io.std.bits.lease := stdLease
    io.storeData(index).ready :=
      !stdRecoveryFence && stdLeaseExact && stores(index).io.std.ready
    io.leaseLookupRejected(index) :=
      io.storeAddress(index).valid && !staLeaseExact
    io.leaseLookupRejected(2 + index) :=
      io.storeData(index).valid && !stdLeaseExact
    stores(index).io.recoveryApply.valid := matchingApply
    stores(index).io.recoveryApply.bits := io.recovery.apply.bits
    stores(index).io.loadCancel := io.loadCancel
    val dataFill = stores(index).io.fill.bits.storeType === STQStoreType.Data
    val fillRecoveryFence = stidFenced(stores(index).io.fill.bits.stid)
    dataBank.io.writes(index).valid := stores(index).io.fill.valid &&
      dataFill && !fillRecoveryFence
    dataBank.io.writes(index).bits := stores(index).io.fill.bits
    addressFillArbiter.io.in(index).valid := stores(index).io.fill.valid &&
      !dataFill && !fillRecoveryFence
    addressFillArbiter.io.in(index).bits := stores(index).io.fill.bits
    stores(index).io.fill.ready := !fillRecoveryFence && Mux(dataFill,
      dataBank.io.writes(index).ready,
      addressFillArbiter.io.in(index).ready)
  }

  val addressFillRecoveryFence = stidFenced(
    addressFillArbiter.io.out.bits.stid)
  stq.io.fillValid := addressFillArbiter.io.out.valid &&
    !addressFillRecoveryFence &&
    io.lateStaPermit
  stq.io.fill := addressFillArbiter.io.out.bits
  addressFillArbiter.io.out.ready := stq.io.fillReady &&
    !addressFillRecoveryFence &&
    io.lateStaPermit
  io.fillConflict := stq.io.fillConflict || dataBank.io.conflict.reduce(_ || _)

  private def projectLateStaProbe(
      target: MDBConflictStoreProbe,
      accepted: STQStoreRequest): Unit = {
    target.valid := true.B
    target.addrOnly := true.B
    target.isTile := !accepted.scalarIex
    target.peId := accepted.peId
    target.stid := accepted.stid
    target.tid := accepted.tid
    target.bid := accepted.bid
    target.gid := accepted.gid
    target.rid := accepted.rid
    target.lsId := accepted.lsId
    target.lsIdFullValid := true.B
    target.lsIdFull := accepted.lsIdFull
    target.pc := accepted.pc
    target.addr := accepted.addr
    target.size := accepted.size
  }

  io.lateStaCandidate := 0.U.asTypeOf(io.lateStaCandidate)
  io.lateStaCandidate.valid := addressFillArbiter.io.out.valid &&
    !addressFillRecoveryFence
  projectLateStaProbe(
    io.lateStaCandidate.bits, addressFillArbiter.io.out.bits)

  io.lateStaProbe := 0.U.asTypeOf(io.lateStaProbe)
  when(stq.io.fillAccepted) {
    val accepted = addressFillArbiter.io.out.bits
    io.lateStaProbe.valid := true.B
    projectLateStaProbe(io.lateStaProbe.bits, accepted)
  }

  val joinedRows = Wire(chiselTypeOf(io.rows))
  for (index <- 0 until stqEntries) {
    joinedRows(index) := stq.io.rows(index)
    when(dataBank.io.rows(index).valid) {
      joinedRows(index).data := dataBank.io.rows(index).data
    }
  }
  io.rows := joinedRows
  io.occupiedMask := stq.io.occupiedMask
  io.addrReadyMask := stq.io.addrReadyMask
  io.dataReadyMask := stq.io.dataReadyMask
  io.residentCount := stq.io.residentCount
  io.storePipelinesOccupied := VecInit(stores.map(_.io.occupied)).asUInt
  io.empty := stq.io.empty && dataBank.io.empty &&
    !loadForward.io.occupied.orR &&
    !io.storePipelinesOccupied.orR && !recoveryPending

  when(io.recovery.apply.valid) {
    assert(matchingApply,
      "store/STQ Apply must match one exact prepared recovery transaction")
  }
  when(io.recovery.abort.valid) {
    assert(matchingAbort,
      "store/STQ Abort must match one exact prepared recovery transaction")
  }
  when(io.reserve.fire) {
    assert(stq.io.reserveBatchAccepted,
      "store reservation and canonical STQ allocation must fire atomically")
  }
  assert(stq.io.dataReadyMask === dataBank.io.readyMask,
    "canonical STQ dataReady state must match the physical data-bank owner")
}
