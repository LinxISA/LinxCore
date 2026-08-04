package linxcore.lsu

import chisel3._
import chisel3.util.{Cat, Fill, Mux1H, MuxLookup, PopCount, PriorityEncoder,
  RRArbiter, UIntToOH, log2Ceil}
import linxcore.common.{CoreParams => ScalarCoreParams, DestinationKind,
  ScalarLsuParams}
import linxcore.ooo._
import linxcore.params.CoreParams
import linxcore.recovery.{ExecEngineType, FlushType}
import linxcore.top.interface.{LSUIO, OperandKind, RecoveryPhase,
  MemoryAccessKind, MemoryCommand, RecoveryCause, RecoveryPlan,
  RecoveryPlanContract, StoreMemoryClass}

/** Public state-free composition boundary for the canonical LSU graph.
  *
  * Physical STQ/data rows belong to `OooIexStoreStqFabric`; committed-store
  * attributes, CommitQ and SCB state belong to `STQSCBCommitBackend`; LIQ,
  * MDB, L1D and load-return state belong to `ScalarLSULoadPath`.
  */
private final class LSUCanonicalOwner(val p: CoreParams) extends Module {
  val io = IO(new LSUIO(p))
  private val profile = OooIexPhysicalProfile.fromCoreParams(p)
  private val op = profile.params
  private val scalarLsu = ScalarLsuParams.fromMainline(p)
  private val scalarRobIdentityEntries = math.max(
    op.robIdentityGroupsPerStid, 1 << (op.nativeBidWidth - 1))
  private val scalar = ScalarCoreParams(
    robEntries = scalarRobIdentityEntries,
    commitWidth = p.widths.retireWidth,
    scalarLsu = scalarLsu,
    lsidWidth = p.lsidWidth)
  private val stqEntries = scalarLsu.stqEntries

  private val store = Module(new OooIexStoreStqFabric(p, stqEntries))
  private val backend = Module(new STQSCBCommitBackend(
    entries = stqEntries,
    queueEntries = scalarLsu.commitQueueEntries,
    issueWidth = scalarLsu.commitIssueWidth,
    scbEntries = scalarLsu.scbEntries,
    scbResponseBufferDepth = scalarLsu.scbResponseBufferDepth,
    addrWidth = scalarLsu.addrWidth,
    dataWidth = scalarLsu.dataWidth,
    peIdWidth = op.peIdWidth,
    stidWidth = op.stidWidth,
    tidWidth = op.stidWidth,
    sizeWidth = scalarLsu.sizeWidth,
    simtLaneWidth = scalarLsu.simtLaneWidth,
    lineBytes = scalarLsu.lineBytes,
    mapQDepth = op.tuMapQDepthPerStid,
    robEntries = op.robIdentityGroupsPerStid,
    lsidWidth = op.lsidWidth,
    nativeBidWidth = op.nativeBidWidth,
    ridGenerationWidth = op.ridGenerationWidth,
    brobGenerationWidth = op.brobGenerationWidth,
    memberIndexWidth = op.robMemberIndexWidth,
    residentGenerationWidth = op.residentGenerationWidth,
    leaseGenerationWidth = op.executeSlotGenerationWidth))
  private val load = Module(new ScalarLSULoadPath(
    scalar, useExternalStqForwarding = true,
    stqForwardRobEntries = op.robIdentityGroupsPerStid,
    stqForwardTokenWidth = op.transactionIdWidth,
    useExternalLaunchPermit = true))
  private val translation = Module(new DSideTranslation(
    p = p,
    entries = math.max(4, scalarLsu.l1dWays),
    pageBytes = 4096))
  private val lowerRecovery = Module(new LSULowerTransactionRecovery(
    p = p,
    lanes = p.lsu.loadPipes + p.lsu.storePipes,
    entries = 4 * (p.lsu.loadPipes + p.lsu.storePipes)))
  lowerRecovery.io.requestFire := 0.U.asTypeOf(lowerRecovery.io.requestFire)
  lowerRecovery.io.requestIdentity :=
    0.U.asTypeOf(lowerRecovery.io.requestIdentity)
  lowerRecovery.io.responseFire :=
    0.U.asTypeOf(lowerRecovery.io.responseFire)
  lowerRecovery.io.responseIdentity :=
    0.U.asTypeOf(lowerRecovery.io.responseIdentity)
  lowerRecovery.io.prepareFire := false.B
  lowerRecovery.io.applyFire := false.B
  lowerRecovery.io.abortFire := false.B
  translation.io.invalidate := false.B

  private def projectMember(target: RobMemberKey,
      source: linxcore.top.interface.RobIdentity): Unit = {
    target := 0.U.asTypeOf(target)
    target.group.valid := true.B
    target.group.peId := source.peId
    target.group.stid := source.stid
    target.group.ridSlot := source.ridSlot
    target.group.ridGeneration := source.ridGeneration
    target.bid.valid := true.B
    target.bid.value := source.bid
    target.brobGeneration := source.brobGeneration
    target.memberIndex := source.memberIndex
    target.residentGeneration := source.residentGeneration
  }

  private def projectCommonRow(target: OooIexIssueRow,
      source: linxcore.top.interface.MemoryIdentity,
      order: linxcore.top.interface.MemoryOrderMeta,
      requestCount: UInt, pair: Bool, child: Int): Unit = {
    target := 0.U.asTypeOf(target)
    target.schedule.valid := true.B
    target.schedule.peId := source.rob.peId
    target.schedule.stid := source.rob.stid
    target.schedule.transactionId := source.transaction.value
    target.schedule.memoryTransactionValid := true.B
    target.schedule.memoryTransaction.value := source.transaction.value
    target.schedule.memoryTransaction.generation := source.transaction.generation
    target.schedule.childIndex := child.U
    projectMember(target.schedule.member, source.rob)
    target.schedule.reservation.uopClass :=
      Mux(child.U === 0.U, OooUopClass.Agu, OooUopClass.Std)
    target.payload.recipe.valid := true.B
    target.payload.recipe.disposition := OooOpcodeDisposition.Dispatch.U
    target.payload.recipe.sideEffectOwner := OooSideEffectOwner.Lsu.U
    target.payload.recipe.recipeKind := Mux(pair,
      OooOpcodeRecipeKind.PairStore.U,
      OooOpcodeRecipeKind.ScalarStore.U)
    target.payload.recipe.lateSplitKind := Mux(pair,
      OooLateSplitKind.PairStoreAddressData.U,
      OooLateSplitKind.StoreAddressData.U)
    target.payload.memory.valid := true.B
    target.payload.memory.isStore := true.B
    target.payload.memory.isLoad := false.B
    target.payload.memory.accessBytes := 0.U
    target.payload.memoryOrder.valid := true.B
    target.payload.memoryOrder.memoryValid := true.B
    target.payload.memoryOrder.isStore := true.B
    target.payload.memoryOrder.isLoad := false.B
    target.payload.memoryOrder.requestCount := requestCount
    target.payload.memoryOrder.firstLsid := order.firstLsid
    target.payload.memoryOrder.firstTypeId := order.firstSid
    target.payload.memoryOrder.before.youngestStoreLsidValid := order.yostValid
    target.payload.memoryOrder.before.youngestStoreLsid := order.yostLsid
    target.payload.memoryOrder.before.storeId := order.yostSid + 1.U
  }

  private def makeExecute(source: linxcore.top.interface.MemoryIdentity,
      order: linxcore.top.interface.MemoryOrderMeta, requestCount: UInt,
      pair: Bool, address: UInt, sizeBytes: UInt, data: Vec[UInt],
      child: Int, lane: Int): OooIexExecuteTransaction = {
    val out = Wire(new OooIexExecuteTransaction(op))
    out := 0.U.asTypeOf(out)
    projectCommonRow(out.i2.row, source, order, requestCount, pair, child)
    out.ownerClass := Mux(child.U === 0.U, OooUopClass.Agu, OooUopClass.Std)
    out.ownerLane := lane.U
    out.slotGeneration := 0.U
    out.i2.row.payload.memory.accessBytes := sizeBytes
    out.i2.row.payload.memory.addressMode := OooMemoryAddressMode.BaseOffset
    out.i2.row.payload.memory.indexMode := OooMemoryIndexMode.Identity
    out.i2.row.payload.memory.addressSourceMask := 1.U
    out.i2.row.payload.memory.dataSourceMask :=
      Mux(requestCount === 2.U, 3.U, 1.U)
    out.i2.sourceMask := Mux(child.U === 0.U,
      out.i2.row.payload.memory.addressSourceMask,
      out.i2.row.payload.memory.dataSourceMask)
    out.i2.sourceData := 0.U.asTypeOf(out.i2.sourceData)
    out.i2.sourceData(0) := Mux(child.U === 0.U, address, data(0))
    if (op.maxSourceOperands > 1) {
      out.i2.sourceData(1) := data(1)
    }
    out.i2.pcValid := false.B
    out.i2.pc := 0.U
    out
  }

  val reservation = io.iex.storeReservation.head
  val reservationIdentity = Wire(new linxcore.top.interface.MemoryIdentity(p))
  reservationIdentity := 0.U.asTypeOf(reservationIdentity)
  reservationIdentity.rob := reservation.bits.rob
  reservationIdentity.transaction.value := reservation.bits.transactionId
  reservationIdentity.lsid := reservation.bits.memoryOrder.firstLsid
  val reserveRow = Wire(new OooIexIssueRow(op))
  projectCommonRow(reserveRow, reservationIdentity,
    reservation.bits.memoryOrder, reservation.bits.requestCount,
    reservation.bits.pair, 0)
  reserveRow.schedule.transactionId := reservation.bits.transactionId
  reserveRow.payload.memory.accessBytes := reservation.bits.sizeBytes
  store.io.reserve.valid := reservation.valid
  store.io.reserve.bits := reserveRow
  reservation.ready := store.io.reserve.ready
  io.iex.storeReservation.tail.foreach(_.ready := false.B)

  val storeTranslationSelected = Wire(Vec(p.lsu.storePipes, Bool()))
  val storeTranslationReady = Wire(Bool())
  val storeTranslatedAddress = Wire(UInt(p.physicalAddressWidth.W))
  for (lane <- 0 until p.lsu.storePipes) {
    val address = io.iex.storeAddress(lane)
    val zeroData = Wire(Vec(p.maxMemoryRequestsPerInstruction,
      UInt(p.dataWidth.W)))
    zeroData := 0.U.asTypeOf(zeroData)
    store.io.storeAddress(lane).valid := address.valid &&
      !lowerRecovery.io.fenced &&
      storeTranslationSelected(lane) && storeTranslationReady
    store.io.storeAddress(lane).bits := makeExecute(
      address.bits.identity, address.bits.memoryOrder,
      address.bits.requestCount, address.bits.pair,
      storeTranslatedAddress, address.bits.sizeBytes, zeroData, 0, lane)
    address.ready := !lowerRecovery.io.fenced &&
      storeTranslationSelected(lane) &&
      storeTranslationReady && store.io.storeAddress(lane).ready

    val data = io.iex.storeData(lane)
    store.io.storeData(lane).valid := data.valid
    store.io.storeData(lane).bits := makeExecute(
      data.bits.identity, data.bits.memoryOrder,
      data.bits.requestCount, data.bits.pair, 0.U,
      data.bits.sizeBytes, data.bits.data, 1, lane)
    data.ready := store.io.storeData(lane).ready
  }

  private val loadRecoveryBoundaryMatch = VecInit(load.io.liqRows.map { row =>
    val firstKilled = io.recovery.prepare.bits.firstKilled
    row.valid && row.loadLsIdFullValid && row.attempt.valid &&
      row.attempt.producer.valid && row.attempt.producer.nativeBidValid &&
      row.attempt.producer.peId === firstKilled.peId &&
      row.attempt.producer.stid === firstKilled.stid &&
      row.attempt.producer.nativeBid === firstKilled.bid &&
      row.attempt.producer.brobGeneration === firstKilled.brobGeneration &&
      row.attempt.producer.ridSlot === firstKilled.ridSlot &&
      row.attempt.producer.ridGeneration === firstKilled.ridGeneration &&
      row.attempt.producer.memberIndex === firstKilled.memberIndex &&
      row.attempt.producer.residentGeneration ===
        firstKilled.residentGeneration
  })
  private val loadRecoveryBoundaryExact =
    !io.recovery.prepare.bits.firstKilledValid ||
      PopCount(loadRecoveryBoundaryMatch) === 1.U
  private val selectedLoadRecoveryLsId = Mux1H(
    loadRecoveryBoundaryMatch, load.io.liqRows.map(_.loadLsIdFull))

  // Recovery preparation is one common store/load transaction. A killed
  // boundary that cannot be resolved to exactly one authoritative LIQ full
  // LSID fails closed: neither sub-owner mutates nor publishes prepared.
  store.io.recovery.prepare.valid :=
    io.recovery.prepare.valid && loadRecoveryBoundaryExact
  store.io.recovery.prepare.bits := io.recovery.prepare.bits
  io.recovery.prepare.ready :=
    store.io.recovery.prepare.ready && loadRecoveryBoundaryExact
  private val lowerDrainQuiescent = lowerRecovery.io.quiescent &&
    translation.io.quiescent && load.io.transientEmpty &&
    !backend.io.serializedRequest.valid && !backend.io.l2Request.valid
  io.recovery.prepared.valid := store.io.recovery.prepared.valid &&
    lowerDrainQuiescent
  io.recovery.prepared.bits := store.io.recovery.prepared.bits
  store.io.recovery.prepared.ready := io.recovery.prepared.ready &&
    lowerDrainQuiescent
  store.io.recovery.apply := io.recovery.apply
  store.io.recovery.abort := io.recovery.abort
  private val loadRecoveryPending = RegInit(false.B)
  private val loadRecoveryPlan = Reg(new RecoveryPlan(p))
  private val loadRecoveryLsIdFull = Reg(UInt(p.lsidWidth.W))
  when(io.recovery.prepare.fire) {
    loadRecoveryPending := true.B
    loadRecoveryPlan := io.recovery.prepare.bits
    loadRecoveryLsIdFull := selectedLoadRecoveryLsId
  }
  private val loadRecoveryApply = loadRecoveryPending &&
    io.recovery.apply.valid && io.recovery.apply.bits.phase === RecoveryPhase.Apply &&
    RecoveryPlanContract.sameTransactionIgnoringPhase(
      io.recovery.apply.bits, loadRecoveryPlan)
  private val loadRecoveryAbort = loadRecoveryPending &&
    io.recovery.abort.valid && io.recovery.abort.bits.phase === RecoveryPhase.Abort &&
    RecoveryPlanContract.sameTransactionIgnoringPhase(
      io.recovery.abort.bits, loadRecoveryPlan)
  when(loadRecoveryApply || loadRecoveryAbort) {
    loadRecoveryPending := false.B
  }
  lowerRecovery.io.prepareFire := io.recovery.prepare.fire
  lowerRecovery.io.applyFire := loadRecoveryApply
  lowerRecovery.io.abortFire := loadRecoveryAbort
  store.io.loadCancel := 0.U.asTypeOf(store.io.loadCancel)
  store.io.lateStaPermit := load.mdbStore.probeReady
  load.mdbStore.probe := store.io.lateStaCandidate.bits
  load.mdbStore.probe.valid := store.io.lateStaCandidate.valid
  load.mdbStore.probeCommit := store.io.lateStaProbe.valid

  backend.io.rows := store.io.rows
  backend.io.recoveryActive := !store.io.recovery.prepare.ready
  store.io.markCommitValid := backend.io.markCommitValid
  store.io.markCommitIndex := backend.io.markCommitIndex
  backend.io.markCommitAccepted := store.io.markCommitAccepted
  store.io.commitFreeMaskValid := backend.io.commitFreeMaskValid
  store.io.commitFreeMask := backend.io.commitFreeMask
  backend.io.commitFreeAcceptedMask := store.io.commitFreeAcceptedMask

  private def projectExactOwner(target: STQExactOwner,
      source: linxcore.top.interface.RobIdentity): Unit = {
    target.valid := true.B
    target.peId := source.peId
    target.stid := source.stid
    target.nativeBidValid := true.B
    target.nativeBid := source.bid
    target.brobGeneration := source.brobGeneration
    target.ridSlot := source.ridSlot
    target.ridGeneration := source.ridGeneration
    target.memberIndex := source.memberIndex
    target.residentGeneration := source.residentGeneration
  }

  backend.io.robStoreCommit.valid := io.storeCommit.valid
  backend.io.robStoreCommit.bits := 0.U.asTypeOf(backend.io.robStoreCommit.bits)
  backend.io.robStoreCommit.bits.logicalFirstLsid :=
    io.storeCommit.bits.logicalFirstLsid
  backend.io.robStoreCommit.bits.logicalFirstStoreId :=
    io.storeCommit.bits.logicalFirstStoreId
  backend.io.robStoreCommit.bits.logicalRequestCount :=
    io.storeCommit.bits.requestCount
  backend.io.robStoreCommit.bits.logicalBeat := io.storeCommit.bits.beat
  projectExactOwner(backend.io.robStoreCommit.bits.exactOwner,
    io.storeCommit.bits.rob)
  io.storeCommit.ready := backend.io.robStoreCommit.ready

  val classifyMatches = Wire(Vec(stqEntries, Bool()))
  for (index <- 0 until stqEntries) {
    val row = store.io.rows(index)
    val source = io.storeClassify.bits
    classifyMatches(index) := row.valid && row.exactOwner.valid &&
      row.exactOwner.peId === source.rob.peId &&
      row.exactOwner.stid === source.rob.stid &&
      row.exactOwner.nativeBid === source.rob.bid &&
      row.exactOwner.brobGeneration === source.rob.brobGeneration &&
      row.exactOwner.ridSlot === source.rob.ridSlot &&
      row.exactOwner.ridGeneration === source.rob.ridGeneration &&
      row.exactOwner.memberIndex === source.rob.memberIndex &&
      row.exactOwner.residentGeneration === source.rob.residentGeneration &&
      row.logicalFirstLsid === source.logicalFirstLsid &&
      row.logicalFirstStoreId === source.logicalFirstStoreId &&
      row.logicalRequestCount === source.requestCount &&
      row.logicalBeat === source.beat
  }
  val classifyUnique = PopCount(classifyMatches) === 1.U
  val classifyIndex = PriorityEncoder(classifyMatches)
  backend.io.memoryClassify.valid := io.storeClassify.valid && classifyUnique
  backend.io.memoryClassify.bits := 0.U.asTypeOf(backend.io.memoryClassify.bits)
  backend.io.memoryClassify.bits.lease.valid := classifyUnique
  backend.io.memoryClassify.bits.lease.index := classifyIndex
  backend.io.memoryClassify.bits.lease.generation :=
    store.io.rows(classifyIndex).leaseGeneration
  projectExactOwner(backend.io.memoryClassify.bits.exactOwner,
    io.storeClassify.bits.rob)
  backend.io.memoryClassify.bits.logicalBeat := io.storeClassify.bits.beat
  backend.io.memoryClassify.bits.memoryClass := MuxLookup(
    io.storeClassify.bits.memoryClass.asUInt, STQMemoryClass.Fault)(Seq(
      StoreMemoryClass.NormalCacheable.asUInt -> STQMemoryClass.NormalCacheable,
      StoreMemoryClass.NormalNonCacheable.asUInt -> STQMemoryClass.NormalNonCacheable,
      StoreMemoryClass.Device.asUInt -> STQMemoryClass.DeviceMmio,
      StoreMemoryClass.Fault.asUInt -> STQMemoryClass.Fault))
  io.storeClassify.ready := classifyUnique && backend.io.memoryClassify.ready

  // Canonical committed-store transport occupies the store lanes immediately
  // after the load lanes: serialized writes first, cache ownership second.
  backend.io.issueEnable := true.B
  backend.io.evictEnable := true.B
  backend.io.dcacheReady := load.scbCache.ready
  backend.io.dcacheTagHit := load.scbCache.tagHit
  backend.io.dcacheWriteHit := load.scbCache.writeHit
  val serializedMemory = io.memoryRequest(p.lsu.loadPipes)
  serializedMemory.valid := backend.io.serializedRequest.valid
  serializedMemory.bits := 0.U.asTypeOf(serializedMemory.bits)
  serializedMemory.bits.identity.value :=
    backend.io.serializedRequest.bits.transactionId
  serializedMemory.bits.command := MemoryCommand.Write
  serializedMemory.bits.accessKind := Mux(
    backend.io.serializedRequest.bits.memoryClass === STQMemoryClass.DeviceMmio,
    MemoryAccessKind.Device, MemoryAccessKind.Data)
  serializedMemory.bits.address := backend.io.serializedRequest.bits.fragment.addr
  serializedMemory.bits.data := backend.io.serializedRequest.bits.fragment.data
  serializedMemory.bits.sizeBytes := backend.io.serializedRequest.bits.fragment.size
  private val publicDataBytes = p.dataWidth / 8
  private val serializedByteOffsetWidth = log2Ceil(publicDataBytes)
  private val serializedByteCount =
    (1.U((publicDataBytes + 1).W) <<
      backend.io.serializedRequest.bits.fragment.size) - 1.U
  serializedMemory.bits.byteMask :=
    (serializedByteCount <<
      backend.io.serializedRequest.bits.fragment.addr(
        serializedByteOffsetWidth - 1, 0))(publicDataBytes - 1, 0)
  backend.io.serializedRequest.ready := serializedMemory.ready

  val ownershipMemory = io.memoryRequest(p.lsu.loadPipes + 1)
  ownershipMemory.valid := backend.io.l2Request.valid
  ownershipMemory.bits := 0.U.asTypeOf(ownershipMemory.bits)
  ownershipMemory.bits.identity.value := backend.io.l2Request.txnTid
  ownershipMemory.bits.identity.generation := Cat(
    backend.io.l2Request.write, backend.io.l2Request.upgrade)
  ownershipMemory.bits.command := MemoryCommand.AcquireWrite
  ownershipMemory.bits.accessKind := MemoryAccessKind.Data
  ownershipMemory.bits.address := backend.io.l2Request.lineAddr
  ownershipMemory.bits.sizeBytes := backend.io.l2Request.size
  backend.io.l2RequestReady := ownershipMemory.ready

  val serializedResponse = io.memoryResponse(p.lsu.loadPipes)
  backend.io.serializedResponse.valid := serializedResponse.valid
  backend.io.serializedResponse.bits.transactionId :=
    serializedResponse.bits.identity.value
  backend.io.serializedResponse.bits.error := serializedResponse.bits.denied ||
    serializedResponse.bits.corrupt
  serializedResponse.ready := backend.io.serializedResponse.ready

  val ownershipResponse = io.memoryResponse(p.lsu.loadPipes + 1)
  backend.io.rawRespValid := ownershipResponse.valid
  backend.io.rawRespTxnId := ownershipResponse.bits.identity.value
  backend.io.rawRespWrite := ownershipResponse.bits.identity.generation(1)
  backend.io.rawRespUpgrade := ownershipResponse.bits.identity.generation(0)
  ownershipResponse.ready := backend.io.rawRespReady
  load.scbCache.lookupValid := false.B
  load.scbCache.lookupLineAddr := 0.U
  load.scbCache.update := 0.U.asTypeOf(load.scbCache.update)
  load.scbCache.grantWriteValid := false.B
  load.scbCache.grantWriteLineAddr := 0.U

  // Every load-owner input is assigned explicitly below. No unknown value may
  // enter the canonical LIQ/MDB/cache graph through the public LSU boundary.
  load.launchPermit.get := !lowerRecovery.io.fenced
  load.stqForward.get.queries <> store.io.loadForwardQuery
  load.stqForward.get.responses <> store.io.loadForwardResponse
  load.stqForward.get.hardBlock.ready := true.B
  for (index <- 0 until stqEntries) {
    load.mdbStore.rows(index) := 0.U.asTypeOf(load.mdbStore.rows(index))
    load.mdbStore.rows(index).valid := store.io.rows(index).valid
    load.mdbStore.rows(index).storeIndex := index.U
    load.mdbStore.rows(index).pc := store.io.rows(index).pc
    load.mdbStore.rows(index).bid := store.io.rows(index).bid
    load.mdbStore.rows(index).lsId := store.io.rows(index).lsId
    load.mdbStore.rows(index).stid := store.io.rows(index).stid
    load.mdbStore.rows(index).addr := store.io.rows(index).addr
    load.mdbStore.rows(index).size := store.io.rows(index).size
    load.mdbStore.rows(index).addrReady := store.io.rows(index).addrReady
    load.mdbStore.rows(index).dataReady := store.io.rows(index).dataReady
    load.mdbStore.rows(index).isTile := !store.io.rows(index).scalarIex
  }

  private def projectRobId(target: linxcore.rob.ROBID, value: UInt,
      valid: Bool = true.B): Unit = {
    val width = log2Ceil(target.entries)
    target.valid := valid
    target.value := value.pad(width)(width - 1, 0)
    target.wrap := value.pad(width + 1)(width)
  }

  val loadIssueArbiter = Module(new RRArbiter(
    chiselTypeOf(io.iex.loadAddress.head.bits), p.lsu.loadPipes))
  for (lane <- 0 until p.lsu.loadPipes) {
    loadIssueArbiter.io.in(lane) <> io.iex.loadAddress(lane)
  }
  val loadIssue = loadIssueArbiter.io.out
  val storeTranslationCandidates = VecInit(
    io.iex.storeAddress.map(_.valid))
  val storeTranslationIndex = PriorityEncoder(storeTranslationCandidates)
  val storeTranslationRequest = storeTranslationCandidates.asUInt.orR
  val loadTranslationSelected = loadIssue.valid
  for (lane <- 0 until p.lsu.storePipes) {
    storeTranslationSelected(lane) := !loadTranslationSelected &&
      storeTranslationRequest && storeTranslationIndex === lane.U
  }
  val selectedStoreAddress = io.iex.storeAddress(storeTranslationIndex)
  translation.io.lookupValid := !lowerRecovery.io.fenced &&
    (loadIssue.valid || storeTranslationRequest)
  translation.io.virtualAddress := Mux(loadTranslationSelected,
    loadIssue.bits.address, selectedStoreAddress.bits.address)
  translation.io.sizeBytes := Mux(loadTranslationSelected,
    loadIssue.bits.sizeBytes, selectedStoreAddress.bits.sizeBytes)
  translation.io.write := !loadTranslationSelected
  storeTranslationReady := translation.io.lookupReady
  storeTranslatedAddress := translation.io.physicalAddress
  load.io.allocValid := loadIssue.valid && !lowerRecovery.io.fenced &&
    translation.io.lookupReady
  load.io.alloc := 0.U.asTypeOf(load.io.alloc)
  projectRobId(load.io.alloc.bid, loadIssue.bits.identity.rob.bid)
  projectRobId(load.io.alloc.gid, loadIssue.bits.identity.rob.ridSlot)
  projectRobId(load.io.alloc.rid, loadIssue.bits.identity.rob.ridSlot)
  projectRobId(load.io.alloc.loadLsId, loadIssue.bits.identity.lsid)
  load.io.alloc.loadLsIdFullValid := true.B
  load.io.alloc.loadLsIdFull := loadIssue.bits.identity.lsid
  load.io.alloc.attempt.valid := true.B
  load.io.alloc.attempt.producer.valid := true.B
  load.io.alloc.attempt.producer.peId := loadIssue.bits.identity.rob.peId
  load.io.alloc.attempt.producer.stid := loadIssue.bits.identity.rob.stid
  load.io.alloc.attempt.producer.nativeBidValid := true.B
  load.io.alloc.attempt.producer.nativeBid := loadIssue.bits.identity.rob.bid
  load.io.alloc.attempt.producer.brobGeneration :=
    loadIssue.bits.identity.rob.brobGeneration
  load.io.alloc.attempt.producer.ridSlot := loadIssue.bits.identity.rob.ridSlot
  load.io.alloc.attempt.producer.ridGeneration :=
    loadIssue.bits.identity.rob.ridGeneration
  load.io.alloc.attempt.producer.memberIndex :=
    loadIssue.bits.identity.rob.memberIndex
  load.io.alloc.attempt.producer.residentGeneration :=
    loadIssue.bits.identity.rob.residentGeneration
  load.io.alloc.attempt.transactionValue :=
    loadIssue.bits.identity.transaction.value
  load.io.alloc.attempt.transactionGeneration :=
    loadIssue.bits.identity.transaction.generation
  load.io.alloc.attempt.generation :=
    loadIssue.bits.identity.attemptGeneration
  load.io.alloc.peId := loadIssue.bits.identity.rob.peId
  load.io.alloc.stid := loadIssue.bits.identity.rob.stid
  load.io.alloc.tid := loadIssue.bits.identity.rob.stid
  load.io.alloc.pc := loadIssue.bits.pc
  load.io.alloc.addr := translation.io.physicalAddress
  load.io.alloc.size := loadIssue.bits.sizeBytes
  load.io.alloc.returnSignExtend := loadIssue.bits.signed
  load.io.alloc.dst.valid := loadIssue.bits.destination.valid
  load.io.alloc.dst.kind := DestinationKind.Gpr
  load.io.alloc.dst.archTag := loadIssue.bits.destination.atag
  load.io.alloc.dst.relTag := loadIssue.bits.destinationRelativeIndex
  load.io.alloc.dst.physTag := loadIssue.bits.destination.ptag
  load.io.alloc.dst.oldPhysTag := loadIssue.bits.destination.previousPtag
  projectRobId(load.io.alloc.youngestStoreId,
    loadIssue.bits.youngestStoreId, loadIssue.bits.youngestStoreValid)
  projectRobId(load.io.alloc.youngestStoreLsId,
    loadIssue.bits.youngestStoreLsid, loadIssue.bits.youngestStoreValid)
  load.io.alloc.youngestStoreLsIdFullValid :=
    loadIssue.bits.youngestStoreValid
  load.io.alloc.youngestStoreLsIdFull := loadIssue.bits.youngestStoreLsid
  load.io.alloc.returnPipeIndex := loadIssue.bits.identity.pipeId
  loadIssue.ready := !lowerRecovery.io.fenced &&
    translation.io.lookupReady && load.io.allocReady

  io.iex.loadAllocation.foreach { out =>
    out.valid := false.B
    out.bits := 0.U.asTypeOf(out.bits)
  }
  for (lane <- 0 until p.lsu.loadPipes) {
    val allocation = io.iex.loadAllocation(lane)
    allocation.valid := loadIssue.valid && !lowerRecovery.io.fenced &&
      translation.io.lookupReady &&
      loadIssueArbiter.io.chosen === lane.U
    allocation.bits.identity := loadIssue.bits.identity
    allocation.bits.allocationId.value := load.io.allocLoadId.value
    allocation.bits.allocationId.generation :=
      load.io.allocLoadId.wrap.asUInt
  }

  val launchIndex = PriorityEncoder(load.io.liqWaitMask)
  val launchRow = load.io.liqRows(launchIndex)
  private val lineOffsetWidth = log2Ceil(scalarLsu.lineBytes)
  val launchLineAddress = Cat(
    launchRow.addr(p.physicalAddressWidth - 1, lineOffsetWidth),
    0.U(lineOffsetWidth.W))
  val scbSnapshot = Module(new SCBLoadSnapshotLookup(
    scalarLsu.scbEntries, p.physicalAddressWidth, scalarLsu.lineBytes))
  scbSnapshot.io.rows := backend.io.scbRows
  scbSnapshot.io.lineAddress := launchLineAddress
  load.stqForward.get.scb.returned := scbSnapshot.io.returned
  load.stqForward.get.scb.validMask := scbSnapshot.io.validMask
  load.stqForward.get.scb.data := scbSnapshot.io.data
  assert(!scbSnapshot.io.ambiguous,
    "one load line may match at most one canonical SCB row")
  load.io.launchValid := load.io.liqWaitMask.orR
  load.io.launchIndex := launchIndex
  io.iex.loadLaunch.foreach { out =>
    out.valid := false.B
    out.bits := 0.U.asTypeOf(out.bits)
  }
  for (lane <- 0 until p.lsu.loadPipes) {
    val launch = io.iex.loadLaunch(lane)
    launch.valid := load.io.launchAccepted && launchRow.returnPipeIndex === lane.U
    launch.bits.identity.rob.peId := launchRow.peId
    launch.bits.identity.rob.stid := launchRow.stid
    launch.bits.identity.rob.ridSlot := launchRow.attempt.producer.ridSlot
    launch.bits.identity.rob.ridGeneration :=
      launchRow.attempt.producer.ridGeneration
    launch.bits.identity.rob.memberIndex :=
      launchRow.attempt.producer.memberIndex
    launch.bits.identity.rob.residentGeneration :=
      launchRow.attempt.producer.residentGeneration
    launch.bits.identity.rob.bid := launchRow.attempt.producer.nativeBid
    launch.bits.identity.rob.brobGeneration :=
      launchRow.attempt.producer.brobGeneration
    launch.bits.identity.lsid := launchRow.loadLsIdFull
    launch.bits.identity.transaction.value :=
      launchRow.attempt.transactionValue
    launch.bits.identity.transaction.generation :=
      launchRow.attempt.transactionGeneration
    launch.bits.identity.attemptGeneration := launchRow.attempt.generation
    launch.bits.identity.pipeId := launchRow.returnPipeIndex
    launch.bits.allocationId.value := launchRow.loadId.value
    launch.bits.allocationId.generation := launchRow.loadId.wrap.asUInt
  }

  io.iex.loadResult.foreach { out =>
    out.valid := false.B
    out.bits := 0.U.asTypeOf(out.bits)
  }
  val completion = load.io.loadReturn.completion
  val completionLane = completion.payload.pipeIndex
  for (lane <- 0 until p.lsu.loadPipes) {
    val result = io.iex.loadResult(lane)
    result.valid := load.io.loadReturn.completionCandidateValid &&
      completionLane === lane.U
    result.bits.identity.rob.peId := completion.peId
    result.bits.identity.rob.stid := completion.stid
    result.bits.identity.rob.ridSlot :=
      completion.payload.attempt.producer.ridSlot
    result.bits.identity.rob.ridGeneration :=
      completion.payload.attempt.producer.ridGeneration
    result.bits.identity.rob.memberIndex :=
      completion.payload.attempt.producer.memberIndex
    result.bits.identity.rob.residentGeneration :=
      completion.payload.attempt.producer.residentGeneration
    result.bits.identity.rob.bid := completion.payload.attempt.producer.nativeBid
    result.bits.identity.rob.brobGeneration :=
      completion.payload.attempt.producer.brobGeneration
    result.bits.identity.lsid := completion.payload.loadLsIdFull
    result.bits.identity.transaction.value :=
      completion.payload.attempt.transactionValue
    result.bits.identity.transaction.generation :=
      completion.payload.attempt.transactionGeneration
    result.bits.identity.attemptGeneration := completion.payload.attempt.generation
    result.bits.identity.pipeId := completion.payload.pipeIndex
    result.bits.allocationId.value := completion.payload.loadId.slot
    result.bits.allocationId.generation := completion.payload.loadId.generation
    result.bits.data := completion.payload.data
    result.bits.destination.valid := completion.payload.dst.valid
    result.bits.destination.kind := OperandKind.Gpr
    result.bits.destination.atag := completion.payload.dst.archTag
    result.bits.destination.ptag := completion.payload.dst.physTag
    result.bits.destination.previousPtag := completion.payload.dst.oldPhysTag
    result.bits.destination.previousPtagValid := completion.payload.dst.valid
    result.bits.destination.ptagValid := completion.payload.dst.valid
    result.bits.destinationRelativeIndex := completion.payload.dst.relTag
    result.bits.trap.valid := completion.payload.faultValid
    result.bits.trap.cause := completion.payload.faultCause
  }
  val completionReady = Mux1H(
    UIntToOH(completionLane, p.lsu.loadPipes),
    io.iex.loadResult.map(_.ready))
  load.io.loadReturn.robRowValid := true.B
  load.io.loadReturn.robRowNeedFlush := false.B
  load.io.loadReturn.resolveReady := completionReady
  load.io.loadReturn.writebackReady := completionReady
  load.io.loadReturn.wakeupReady := completionReady

  load.io.flush := false.B
  load.io.preciseFlush := 0.U.asTypeOf(load.io.preciseFlush)
  when(loadRecoveryApply && loadRecoveryPlan.firstKilledValid) {
    load.io.preciseFlush.req.valid := true.B
    load.io.preciseFlush.req.typ := FlushType.NukeFlush
    load.io.preciseFlush.req.peId := loadRecoveryPlan.firstKilled.peId
    load.io.preciseFlush.req.stid := loadRecoveryPlan.firstKilled.stid
    load.io.preciseFlush.req.tid := loadRecoveryPlan.firstKilled.stid
    projectRobId(load.io.preciseFlush.req.bid,
      loadRecoveryPlan.firstKilled.bid)
    projectRobId(load.io.preciseFlush.req.gid,
      loadRecoveryPlan.firstKilled.ridSlot)
    projectRobId(load.io.preciseFlush.req.rid,
      loadRecoveryPlan.firstKilled.ridSlot)
    projectRobId(load.io.preciseFlush.req.lsId, loadRecoveryLsIdFull)
    load.io.preciseFlush.req.lsIdFullValid := true.B
    load.io.preciseFlush.req.lsIdFull := loadRecoveryLsIdFull
    load.io.preciseFlush.req.execEngine := ExecEngineType.Mem
    load.io.preciseFlush.req.fetchTpcValid := false.B
    load.io.preciseFlush.req.fetchTpc := loadRecoveryPlan.redirectPc
    load.io.preciseFlush.req.immediateFlush := false.B
    load.io.preciseFlush.baseOnBid := false.B
    load.io.preciseFlush.baseOnGroup := true.B
    load.io.preciseFlush.baseOnPE := true.B
    load.io.preciseFlush.baseOnThread := true.B
  }
  val reissueRequest = io.loadReissueRequest
  val reissueMatches = Wire(Vec(scalarLsu.liqEntries, Bool()))
  for (index <- 0 until scalarLsu.liqEntries) {
    val row = load.io.liqRows(index)
    val current = reissueRequest.bits.currentIdentity
    reissueMatches(index) := row.valid && row.loadId.valid &&
      row.loadId.value === reissueRequest.bits.allocationId.value &&
      row.loadId.wrap.asUInt === reissueRequest.bits.allocationId.generation &&
      row.attempt.valid && row.attempt.producer.valid &&
      row.attempt.producer.peId === current.rob.peId &&
      row.attempt.producer.stid === current.rob.stid &&
      row.attempt.producer.nativeBid === current.rob.bid &&
      row.attempt.producer.brobGeneration === current.rob.brobGeneration &&
      row.attempt.producer.ridSlot === current.rob.ridSlot &&
      row.attempt.producer.ridGeneration === current.rob.ridGeneration &&
      row.attempt.producer.memberIndex === current.rob.memberIndex &&
      row.attempt.producer.residentGeneration === current.rob.residentGeneration &&
      row.attempt.transactionValue === current.transaction.value &&
      row.attempt.transactionGeneration === current.transaction.generation &&
      row.attempt.generation === current.attemptGeneration &&
      row.loadLsIdFull === current.lsid &&
      row.returnPipeIndex === current.pipeId
  }
  val reissueUnique = PopCount(reissueMatches) === 1.U
  val reissueRow = load.io.liqRows(PriorityEncoder(reissueMatches))
  val nextIdentity = Wire(chiselTypeOf(reissueRequest.bits.currentIdentity))
  nextIdentity := reissueRequest.bits.currentIdentity
  nextIdentity.attemptGeneration :=
    reissueRequest.bits.currentIdentity.attemptGeneration + 1.U
  val reissueLaneReady = Wire(Vec(p.lsu.loadPipes, Bool()))
  for (lane <- 0 until p.lsu.loadPipes) {
    val notice = io.iex.loadReissue(lane)
    notice.valid := reissueRequest.valid && reissueUnique &&
      reissueRow.returnPipeIndex === lane.U
    notice.bits := 0.U.asTypeOf(notice.bits)
    notice.bits.allocationId := reissueRequest.bits.allocationId
    notice.bits.currentIdentity := reissueRequest.bits.currentIdentity
    notice.bits.nextIdentity := nextIdentity
    notice.bits.address := reissueRequest.bits.address
    reissueLaneReady(lane) := notice.ready &&
      reissueRow.returnPipeIndex === lane.U
  }
  // A malformed, stale, or future replay request is a permanent protocol
  // rejection, not backpressure. Consume it without publishing a transition.
  reissueRequest.ready := Mux(
    reissueUnique, reissueLaneReady.asUInt.orR, reissueRequest.valid)

  val rebindArbiter = Module(new RRArbiter(
    chiselTypeOf(io.iex.loadRebindApply.head.bits), p.lsu.loadPipes))
  for (lane <- 0 until p.lsu.loadPipes) {
    rebindArbiter.io.in(lane) <> io.iex.loadRebindApply(lane)
  }
  val rebindApply = rebindArbiter.io.out
  val selectedCancelReady = Mux1H(
    UIntToOH(rebindArbiter.io.chosen, p.lsu.loadPipes),
    io.iex.loadCancel.map(_.ready))
  load.io.attemptRebindValid := rebindApply.valid && selectedCancelReady
  rebindApply.ready := selectedCancelReady && load.io.attemptRebindReady
  load.io.attemptRebind := 0.U.asTypeOf(load.io.attemptRebind)
  projectRobId(load.io.attemptRebind.loadId,
    rebindApply.bits.allocationId.value)
  load.io.attemptRebind.loadId.wrap :=
    rebindApply.bits.allocationId.generation(0)
  private def projectAttempt(target: linxcore.lsu.LoadAttemptIdentity,
      source: linxcore.top.interface.MemoryIdentity): Unit = {
    target := 0.U.asTypeOf(target)
    target.valid := true.B
    target.producer.valid := true.B
    target.producer.peId := source.rob.peId
    target.producer.stid := source.rob.stid
    target.producer.nativeBidValid := true.B
    target.producer.nativeBid := source.rob.bid
    target.producer.brobGeneration := source.rob.brobGeneration
    target.producer.ridSlot := source.rob.ridSlot
    target.producer.ridGeneration := source.rob.ridGeneration
    target.producer.memberIndex := source.rob.memberIndex
    target.producer.residentGeneration := source.rob.residentGeneration
    target.transactionValue := source.transaction.value
    target.transactionGeneration := source.transaction.generation
    target.generation := source.attemptGeneration
  }
  projectAttempt(load.io.attemptRebind.current,
    rebindApply.bits.currentIdentity)
  projectAttempt(load.io.attemptRebind.next,
    rebindApply.bits.nextIdentity)
  load.io.structuralRetryValid := false.B
  load.io.structuralRetry := 0.U.asTypeOf(load.io.structuralRetry)
  val repickIndex = PriorityEncoder(load.io.liqRepickMask)
  val repickRow = load.io.liqRows(repickIndex)
  load.io.pickValid := false.B
  load.io.pickIndex := repickIndex
  load.io.scbReturnValid := false.B
  load.io.scbReturnIndex := 0.U
  load.io.e2Stores := 0.U.asTypeOf(load.io.e2Stores)
  load.io.e2ScbReturned := false.B
  load.io.e2StqReturned := false.B
  load.io.l1dEvictionReady := false.B
  load.io.replayWakeValid := false.B
  load.io.replayWake := 0.U.asTypeOf(load.io.replayWake)
  load.io.refillValid := false.B
  load.io.refill := 0.U.asTypeOf(load.io.refill)
  val missLane = if (p.lsu.loadPipes == 1) 0.U else
    load.io.missRequest.missId.value(log2Ceil(p.lsu.loadPipes) - 1, 0)
  val missLaneReady = Wire(Vec(p.lsu.loadPipes, Bool()))
  translation.io.memoryRequest.ready := false.B
  for (lane <- 0 until p.lsu.loadPipes) {
    val request = io.memoryRequest(lane)
    val translationSelected = lane == 0
    val dataRequest = Wire(chiselTypeOf(request.bits))
    dataRequest := 0.U.asTypeOf(dataRequest)
    dataRequest.identity.value := load.io.missRequest.missId.value
    dataRequest.identity.generation := load.io.missRequest.missId.wrap.asUInt
    dataRequest.command := MemoryCommand.Read
    dataRequest.accessKind := Mux(
      load.io.missRequest.lineAddr(p.physicalAddressWidth - 1,
        p.physicalAddressWidth - 8).andR,
      MemoryAccessKind.Device, MemoryAccessKind.Data)
    dataRequest.address := load.io.missRequest.lineAddr
    dataRequest.sizeBytes := scalarLsu.lineBytes.U
    val translationValid = if (translationSelected)
      translation.io.memoryRequest.valid else false.B
    val dataValid = load.io.missRequestValid && missLane === lane.U
    request.valid := translationValid || dataValid
    request.bits := Mux(translationValid,
      translation.io.memoryRequest.bits, dataRequest)
    if (translationSelected) {
      translation.io.memoryRequest.ready := request.ready
    }
    missLaneReady(lane) := request.ready && !translationValid &&
      missLane === lane.U
  }
  load.io.missRequestReady := missLaneReady.asUInt.orR

  val loadResponseArbiter = Module(new RRArbiter(
    chiselTypeOf(io.memoryResponse.head.bits), p.lsu.loadPipes))
  for (lane <- 0 until p.lsu.loadPipes) {
    loadResponseArbiter.io.in(lane) <> io.memoryResponse(lane)
  }
  val publicLoadResponse = loadResponseArbiter.io.out
  translation.io.memoryResponse.bits := publicLoadResponse.bits
  val translationResponse = translation.io.responseOwned
  translation.io.memoryResponse.valid := publicLoadResponse.valid &&
    translationResponse
  load.io.missResponseValid := publicLoadResponse.valid &&
    !translationResponse
  publicLoadResponse.ready := Mux(translationResponse,
    translation.io.memoryResponse.ready, load.io.missResponseReady)
  load.io.missResponse := 0.U.asTypeOf(load.io.missResponse)
  load.io.missResponse.missId.valid := publicLoadResponse.valid
  load.io.missResponse.missId.value := publicLoadResponse.bits.identity.value
  load.io.missResponse.missId.wrap :=
    publicLoadResponse.bits.identity.generation(0)
  load.io.missResponse.lineAddr := publicLoadResponse.bits.address
  load.io.missResponse.isRead := true.B
  load.io.missResponse.data := Fill(
    scalarLsu.lineBytes / (p.dataWidth / 8), publicLoadResponse.bits.data)
  load.io.missResponse.l2Miss := publicLoadResponse.bits.denied ||
    publicLoadResponse.bits.corrupt
  load.io.resolveRetireValid := false.B
  load.io.resolveRetireBid := 0.U.asTypeOf(load.io.resolveRetireBid)
  load.io.resolveRetireLsId := 0.U.asTypeOf(load.io.resolveRetireLsId)
  load.io.resolveRetireLsIdFullValid := false.B
  load.io.resolveRetireLsIdFull := 0.U
  load.recovery.ready := io.iex.recoveryEvent.ready
  for (lane <- 0 until p.lsu.loadPipes) {
    val repick = io.iex.loadRepick(lane)
    repick.valid := load.io.liqRepickMask.orR &&
      repickRow.returnPipeIndex === lane.U
    repick.bits := 0.U.asTypeOf(repick.bits)
    repick.bits.allocationId.value := repickRow.loadId.value
    repick.bits.allocationId.generation := repickRow.loadId.wrap.asUInt
    repick.bits.currentIdentity.rob.peId := repickRow.peId
    repick.bits.currentIdentity.rob.stid := repickRow.stid
    repick.bits.currentIdentity.rob.ridSlot :=
      repickRow.attempt.producer.ridSlot
    repick.bits.currentIdentity.rob.ridGeneration :=
      repickRow.attempt.producer.ridGeneration
    repick.bits.currentIdentity.rob.memberIndex :=
      repickRow.attempt.producer.memberIndex
    repick.bits.currentIdentity.rob.residentGeneration :=
      repickRow.attempt.producer.residentGeneration
    repick.bits.currentIdentity.rob.bid :=
      repickRow.attempt.producer.nativeBid
    repick.bits.currentIdentity.rob.brobGeneration :=
      repickRow.attempt.producer.brobGeneration
    repick.bits.currentIdentity.lsid := repickRow.loadLsIdFull
    repick.bits.currentIdentity.transaction.value :=
      repickRow.attempt.transactionValue
    repick.bits.currentIdentity.transaction.generation :=
      repickRow.attempt.transactionGeneration
    repick.bits.currentIdentity.attemptGeneration :=
      repickRow.attempt.generation
    repick.bits.currentIdentity.pipeId := repickRow.returnPipeIndex
    repick.bits.nextIdentity := repick.bits.currentIdentity
    repick.bits.nextIdentity.attemptGeneration :=
      repickRow.attempt.generation + 1.U
  }
  for (lane <- 0 until p.lsu.loadPipes) {
    val cancel = io.iex.loadCancel(lane)
    cancel.valid := rebindApply.valid && load.io.attemptRebindReady &&
      rebindArbiter.io.chosen === lane.U
    cancel.bits := 0.U.asTypeOf(cancel.bits)
    cancel.bits.currentIdentity := rebindApply.bits.currentIdentity
  }
  val cancelFire = VecInit(io.iex.loadCancel.map(_.fire)).asUInt.orR
  when(rebindApply.fire || load.io.attemptRebindAccepted || cancelFire) {
    assert(rebindApply.fire && load.io.attemptRebindAccepted && cancelFire,
      "LSU rebind, LIQ attempt mutation, and old-attempt cancel must share one fire")
  }
  io.iex.recoveryEvent.valid := load.recovery.valid
  io.iex.recoveryEvent.bits := 0.U.asTypeOf(io.iex.recoveryEvent.bits)
  io.iex.recoveryEvent.bits.transactionId := load.recovery.flush.req.lsIdFull
  io.iex.recoveryEvent.bits.cause := RecoveryCause.MemoryOrder
  io.iex.recoveryEvent.bits.trigger.peId := load.recovery.flush.req.peId
  io.iex.recoveryEvent.bits.trigger.stid := load.recovery.flush.req.stid
  io.iex.recoveryEvent.bits.trigger.bid := load.recovery.flush.req.bid.value
  io.iex.recoveryEvent.bits.trigger.ridSlot := load.recovery.flush.req.gid.value
  io.iex.recoveryEvent.bits.instruction.peId := load.recovery.flush.req.peId
  io.iex.recoveryEvent.bits.instruction.stid := load.recovery.flush.req.stid
  io.iex.recoveryEvent.bits.redirectPc := load.recovery.flush.req.fetchTpc

  io.memoryRequest.drop(p.lsu.loadPipes + 2).foreach { out =>
    out.valid := false.B
    out.bits := 0.U.asTypeOf(out.bits)
  }
  io.memoryResponse.drop(p.lsu.loadPipes + 2).foreach(_.ready := false.B)
  for (lane <- io.memoryRequest.indices) {
    lowerRecovery.io.requestFire(lane) := io.memoryRequest(lane).fire
    lowerRecovery.io.requestIdentity(lane) :=
      io.memoryRequest(lane).bits.identity
    lowerRecovery.io.responseFire(lane) := io.memoryResponse(lane).fire
    lowerRecovery.io.responseIdentity(lane) :=
      io.memoryResponse(lane).bits.identity
  }
  io.quiescent := store.io.empty && backend.io.empty && load.io.empty &&
    translation.io.quiescent && lowerRecovery.io.quiescent &&
    !loadRecoveryPending
  io.trace.valid := false.B
  io.trace.bits := 0.U.asTypeOf(io.trace.bits)
}

/** Stable public LSU box. All retained state and arbitration live below this
  * wiring-only shell in the private canonical owner graph.
  */
class LSU(val p: CoreParams) extends Module {
  val io = IO(new LSUIO(p))
  private val owner = Module(new LSUCanonicalOwner(p))
  io <> owner.io
}
