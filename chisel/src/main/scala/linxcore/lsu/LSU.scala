package linxcore.lsu

import chisel3._
import chisel3.util.{Cat, Fill, Mux1H, MuxLookup, PopCount, PriorityEncoder,
  UIntToOH, log2Ceil}
import linxcore.common.{CoreParams => ScalarCoreParams, DestinationKind,
  ScalarLsuParams}
import linxcore.ooo._
import linxcore.params.CoreParams
import linxcore.top.interface.{LSUIO, OperandKind, RecoveryPhase,
  MemoryAccessKind, MemoryCommand, StoreMemoryClass}

/** Public state-free composition boundary for the canonical LSU graph.
  *
  * Physical STQ/data rows belong to `OooIexStoreStqFabric`; committed-store
  * attributes, CommitQ and SCB state belong to `STQSCBCommitBackend`; LIQ,
  * MDB, L1D and load-return state belong to `ScalarLSULoadPath`.
  */
class LSU(val p: CoreParams) extends Module {
  val io = IO(new LSUIO(p))
  private val profile = OooIexPhysicalProfile.fromCoreParams(p)
  private val op = profile.params
  private val scalarLsu = ScalarLsuParams.fromMainline(p)
  private val scalar = ScalarCoreParams(
    robEntries = op.robGroupsPerStid,
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
    robEntries = op.robGroupsPerStid,
    lsidWidth = op.lsidWidth,
    nativeBidWidth = op.nativeBidWidth,
    ridGenerationWidth = op.ridGenerationWidth,
    brobGenerationWidth = op.brobGenerationWidth,
    memberIndexWidth = op.robMemberIndexWidth,
    residentGenerationWidth = op.residentGenerationWidth,
    leaseGenerationWidth = op.executeSlotGenerationWidth))
  private val load = Module(new ScalarLSULoadPath(
    scalar, useExternalStqForwarding = true,
    stqForwardRobEntries = op.robGroupsPerStid,
    stqForwardTokenWidth = op.transactionIdWidth,
    useExternalLaunchPermit = true))

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

  for (lane <- 0 until p.lsu.storePipes) {
    val address = io.iex.storeAddress(lane)
    val zeroData = Wire(Vec(p.maxMemoryRequestsPerInstruction,
      UInt(p.dataWidth.W)))
    zeroData := 0.U.asTypeOf(zeroData)
    store.io.storeAddress(lane).valid := address.valid
    store.io.storeAddress(lane).bits := makeExecute(
      address.bits.identity, address.bits.memoryOrder,
      address.bits.requestCount, address.bits.pair,
      address.bits.address, address.bits.sizeBytes, zeroData, 0, lane)
    address.ready := store.io.storeAddress(lane).ready

    val data = io.iex.storeData(lane)
    store.io.storeData(lane).valid := data.valid
    store.io.storeData(lane).bits := makeExecute(
      data.bits.identity, data.bits.memoryOrder,
      data.bits.requestCount, data.bits.pair, 0.U,
      data.bits.sizeBytes, data.bits.data, 1, lane)
    data.ready := store.io.storeData(lane).ready
  }

  store.io.recovery <> io.recovery
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

  // Task 16 closes the external memory transport. Until then the backend is
  // live and fail-closed at those typed endpoints.
  backend.io.issueEnable := true.B
  backend.io.evictEnable := true.B
  backend.io.dcacheReady := load.scbCache.ready
  backend.io.dcacheTagHit := load.scbCache.tagHit
  backend.io.dcacheWriteHit := load.scbCache.writeHit
  backend.io.l2RequestReady := false.B
  backend.io.rawRespValid := false.B
  backend.io.rawRespTxnId := 0.U
  backend.io.rawRespWrite := false.B
  backend.io.rawRespUpgrade := false.B
  backend.io.serializedRequest.ready := false.B
  backend.io.serializedResponse.valid := false.B
  backend.io.serializedResponse.bits := 0.U.asTypeOf(
    backend.io.serializedResponse.bits)
  load.scbCache.lookupValid := false.B
  load.scbCache.lookupLineAddr := 0.U
  load.scbCache.update := 0.U.asTypeOf(load.scbCache.update)
  load.scbCache.grantWriteValid := false.B
  load.scbCache.grantWriteLineAddr := 0.U

  // Load owner default wiring is completed by the typed lifecycle adapter
  // below; all non-public Task-16 transports remain blocked.
  load.io := DontCare
  load.launchPermit.get := true.B
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

  val loadIssue = io.iex.loadAddress.head
  load.io.allocValid := loadIssue.valid
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
  load.io.alloc.attempt.generation :=
    loadIssue.bits.identity.attemptGeneration
  load.io.alloc.peId := loadIssue.bits.identity.rob.peId
  load.io.alloc.stid := loadIssue.bits.identity.rob.stid
  load.io.alloc.tid := loadIssue.bits.identity.rob.stid
  load.io.alloc.pc := loadIssue.bits.pc
  load.io.alloc.addr := loadIssue.bits.address
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
  loadIssue.ready := load.io.allocReady
  io.iex.loadAddress.tail.foreach(_.ready := false.B)

  io.iex.loadAllocation.foreach { out =>
    out.valid := false.B
    out.bits := 0.U.asTypeOf(out.bits)
  }
  val allocation = io.iex.loadAllocation.head
  // The free-row identity is a side-effect-free preview. Capacity is carried
  // only by loadAddress.ready; qualifying this identity with ready recreates
  // a producer-valid/consumer-ready combinational loop at the public seam.
  allocation.valid := true.B
  allocation.bits.identity := loadIssue.bits.identity
  allocation.bits.allocationId.value := load.io.allocLoadId.value
  allocation.bits.allocationId.generation :=
    load.io.allocLoadId.wrap.asUInt

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
  val launch = io.iex.loadLaunch.head
  launch.valid := load.io.launchAccepted
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
  launch.bits.identity.attemptGeneration := launchRow.attempt.generation
  launch.bits.identity.pipeId := launchRow.returnPipeIndex
  launch.bits.allocationId.value := launchRow.loadId.value
  launch.bits.allocationId.generation := launchRow.loadId.wrap.asUInt

  io.iex.loadResult.foreach { out =>
    out.valid := false.B
    out.bits := 0.U.asTypeOf(out.bits)
  }
  val completion = load.io.loadReturn.completion
  val completionLane = load.io.loadReturn.completionSelectedPipe
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
  val reissueRequest = io.loadReissueRequest
  val reissueNotice = io.iex.loadReissue.head
  reissueNotice.valid := reissueRequest.valid
  reissueNotice.bits := reissueRequest.bits
  reissueRequest.ready := reissueNotice.ready
  io.iex.loadReissue.tail.foreach { out =>
    out.valid := false.B
    out.bits := 0.U.asTypeOf(out.bits)
  }

  val rebindApply = io.iex.loadRebindApply.head
  load.io.attemptRebindValid := rebindApply.valid
  rebindApply.ready := load.io.attemptRebindReady
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
    target.generation := source.attemptGeneration
  }
  projectAttempt(load.io.attemptRebind.current,
    rebindApply.bits.currentIdentity)
  projectAttempt(load.io.attemptRebind.next,
    rebindApply.bits.nextIdentity)
  io.iex.loadRebindApply.tail.foreach(_.ready := false.B)
  load.io.structuralRetryValid := false.B
  load.io.structuralRetry := 0.U.asTypeOf(load.io.structuralRetry)
  load.io.pickValid := load.io.liqRepickMask.orR
  load.io.pickIndex := PriorityEncoder(load.io.liqRepickMask)
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
  val publicLoadRequest = io.memoryRequest.head
  publicLoadRequest.valid := load.io.missRequestValid
  publicLoadRequest.bits := 0.U.asTypeOf(publicLoadRequest.bits)
  publicLoadRequest.bits.identity.value := load.io.missRequest.missId.value
  publicLoadRequest.bits.identity.generation :=
    load.io.missRequest.missId.wrap.asUInt
  publicLoadRequest.bits.command := MemoryCommand.Read
  publicLoadRequest.bits.accessKind := MemoryAccessKind.Data
  publicLoadRequest.bits.address := load.io.missRequest.lineAddr
  publicLoadRequest.bits.sizeBytes := scalarLsu.lineBytes.U
  load.io.missRequestReady := publicLoadRequest.ready

  val publicLoadResponse = io.memoryResponse.head
  load.io.missResponseValid := publicLoadResponse.valid
  publicLoadResponse.ready := load.io.missResponseReady
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
  load.recovery.ready := true.B
  io.iex.loadRepick.foreach { out =>
    out.valid := false.B
    out.bits := 0.U.asTypeOf(out.bits)
  }
  io.iex.loadCancel.foreach { out =>
    out.valid := false.B
    out.bits := 0.U.asTypeOf(out.bits)
  }
  io.iex.recoveryEvent.valid := false.B
  io.iex.recoveryEvent.bits := 0.U.asTypeOf(io.iex.recoveryEvent.bits)

  io.memoryRequest.tail.foreach { out =>
    out.valid := false.B
    out.bits := 0.U.asTypeOf(out.bits)
  }
  io.memoryResponse.tail.foreach(_.ready := false.B)
  io.trace.valid := false.B
  io.trace.bits := 0.U.asTypeOf(io.trace.bits)
}
