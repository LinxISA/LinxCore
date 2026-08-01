package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, RRArbiter, Valid, log2Ceil}

import linxcore.common.{CoreParams, DestinationKind}
import linxcore.lsu.{LoadAttemptIdentity, LoadInflightAlloc}
import linxcore.rob.ROBID

class OooIexLoadLiqAllocReject(val p: OooParams = OooParams())
    extends Bundle {
  val member = new RobMemberKey(p)
  val identityExact = Bool()
  val loadShapeExact = Bool()
  val memoryOrderExact = Bool()
  val destinationExact = Bool()
  val pcExact = Bool()
  val killed = Bool()
  val flushed = Bool()
}

/** Exact OOO identity returned beside one accepted canonical LIQ allocation.
  *
  * `loadId` remains LSU-owned and is joined by the caller from the canonical
  * allocation response.  This sideband only reports the OOO lane and attempt
  * which participated in that same ready/valid fire.
  */
class OooIexLoadLiqAllocAccepted(
    val p: OooParams = OooParams(),
    val laneCount: Int = 3) extends Bundle {
  val lane = UInt(math.max(1, log2Ceil(laneCount)).W)
  val load = new OooIexLoadGeneration(p)
  val request = new OooIexAguLoadRequest(p)
}

class OooIexLoadLiqAllocAdapterIO(
    val p: OooParams,
    val coreParams: CoreParams,
    val laneCount: Int) extends Bundle {
  private val lsu = coreParams.scalarLsu

  val agu = Flipped(Vec(laneCount,
    Decoupled(new OooIexAguLoadRequest(p))))
  val alloc = Decoupled(new LoadInflightAlloc(
    lsu.liqEntries,
    coreParams.robEntries,
    lsu.addrWidth,
    lsu.pcWidth,
    lsu.loadSizeWidth,
    lsu.archRegWidth,
    lsu.physRegWidth,
    lsu.peIdWidth,
    lsu.stidWidth,
    lsu.tidWidth,
    lsu.loadReturnPipeCount,
    coreParams.lsidWidth))

  val recoveryApply = Flipped(Valid(new OooResidencyRecoveryPlan(p)))
  val recoveryFence = Input(Bool())
  val flush = Input(Bool())
  val accepted = Valid(new OooIexLoadLiqAllocAccepted(p, laneCount))
  val rejected = Output(Vec(laneCount,
    Valid(new OooIexLoadLiqAllocReject(p))))
}

/** Multi-AGU to one-canonical-LIQ allocation bridge.
  *
  * The bridge is deliberately non-resident: AGU lanes retain their requests
  * under backpressure and the canonical LIQ becomes the sole load-lifecycle
  * owner on `alloc.fire`.  A global accepted-allocation serial creates
  * producer-qualified attempt generations; the counter is never reset by
  * recovery, so a stale return cannot alias a later request after a flush.
  */
class OooIexLoadLiqAllocAdapter(
    val p: OooParams = OooParams(),
    val coreParams: CoreParams = CoreParams(
      scalarLsu = linxcore.common.ScalarLsuParams(loadReturnPipeCount = 3)),
    val laneCount: Int = 3) extends Module {
  private val lsu = coreParams.scalarLsu

  require(laneCount > 0,
    "the Linx scalar execution profile needs at least one load-address lane")
  require(lsu.loadReturnPipeCount >= laneCount,
    "each physical load lane needs a canonical LSU return-pipe identity")
  require(p.robGroupsPerStid <= coreParams.robEntries,
    "OOO RID slots must fit the canonical LSU ROB projection")
  require(p.nativeBidWidth <= log2Ceil(coreParams.robEntries) + 1,
    "native BID must fit the canonical LSU value-plus-wrap projection")
  require(p.lsidWidth <= coreParams.lsidWidth,
    "the canonical LSU full LSID must not truncate the OOO memory stream")
  require(p.pcWidth <= lsu.pcWidth && p.peIdWidth <= lsu.peIdWidth &&
    p.stidWidth <= lsu.stidWidth && p.archRegWidth <= lsu.archRegWidth &&
    p.pTagWidth <= lsu.physRegWidth,
    "OOO load payload widths must fit the canonical LSU allocation payload")
  LoadAttemptIdentity.requireBridgeFits(
    p.peIdWidth,
    p.stidWidth,
    p.nativeBidWidth,
    p.ridSlotWidth,
    p.brobGenerationWidth,
    p.ridGenerationWidth,
    p.robMemberIndexWidth,
    p.residentGenerationWidth,
    p.loadGenerationWidth)

  val io = IO(new OooIexLoadLiqAllocAdapterIO(p, coreParams, laneCount))

  // Allocation is currently one-wide, so one global serial avoids making the
  // attempt generation depend on which physical AGU happened to win.  Replay
  // generations advance later through the exact LIQ rebind contract.
  private val generation = RegInit(0.U(p.loadGenerationWidth.W))

  private def killed(member: RobMemberKey): Bool =
    io.recoveryApply.valid && io.recoveryApply.bits.valid &&
      OooRecoveryMembership.memberKilled(p, io.recoveryApply.bits, member)

  private def identityExact(request: OooIexAguLoadRequest): Bool = {
    val row = request.execute.i2.row
    row.valid && row.member.group.valid && row.member.bid.valid &&
      row.stid < p.stidCount.U && row.member.group.peId === row.peId &&
      row.member.group.stid === row.stid
  }

  private def loadShapeExact(request: OooIexAguLoadRequest): Bool = {
    val execute = request.execute
    val row = execute.i2.row
    val bytesExact = request.accessBytes === 1.U ||
      request.accessBytes === 2.U || request.accessBytes === 4.U ||
      request.accessBytes === 8.U
    execute.ownerClass === OooUopClass.Agu && row.reservation.valid &&
      row.reservation.uopClass === OooUopClass.Agu && row.recipe.valid &&
      row.recipe.opcode === row.opcode &&
      row.recipe.disposition === OooOpcodeDisposition.Dispatch.U &&
      row.recipe.recipeKind === OooOpcodeRecipeKind.ScalarLoad.U &&
      row.recipe.dispatchClass === OooDispatchClass.Agu.U &&
      row.recipe.sideEffectOwner === OooSideEffectOwner.Lsu.U &&
      row.recipe.memoryRequestCount === 1.U && row.memory.valid &&
      row.memory.isLoad && !row.memory.isStore && bytesExact
  }

  private def memoryOrderExact(request: OooIexAguLoadRequest): Bool = {
    val order = request.execute.i2.row.memoryOrder
    order.valid && order.memoryValid && order.isLoad && !order.isStore &&
      order.requestCount === 1.U && order.firstLsid === order.before.lsid &&
      order.firstTypeId === order.before.loadId &&
      order.after.lsid === order.before.lsid + 1.U &&
      order.after.loadId === order.before.loadId + 1.U &&
      order.after.storeId === order.before.storeId &&
      order.after.youngestStoreLsidValid ===
        order.before.youngestStoreLsidValid &&
      order.after.youngestStoreLsid === order.before.youngestStoreLsid
  }

  private def destinationExact(request: OooIexAguLoadRequest): Bool = {
    val destination = request.destination
    val previous = request.execute.i2.row.payload.previousPDestinations(0)
    destination.valid && destination.kind === DestinationKind.Gpr &&
      destination.ptag < p.pPhysRegs.U &&
      previous.valid && previous.ptag < p.pPhysRegs.U &&
      previous.ptag =/= destination.ptag &&
      destination.asUInt ===
        request.execute.i2.row.destinations(0).asUInt
  }

  val exact = Wire(Vec(laneCount, Bool()))
  val killedLane = Wire(Vec(laneCount, Bool()))
  val arbiter = Module(new RRArbiter(
    new OooIexAguLoadRequest(p), laneCount))
  for (lane <- 0 until laneCount) {
    val request = io.agu(lane).bits
    val identity = identityExact(request)
    val shape = loadShapeExact(request)
    val order = memoryOrderExact(request)
    val destination = destinationExact(request)
    val pc = request.pcValid
    killedLane(lane) := killed(request.execute.i2.row.member)
    exact(lane) := identity && shape && order && destination && pc

    val drop = killedLane(lane) || io.flush
    val fenced = io.recoveryFence || io.recoveryApply.valid
    arbiter.io.in(lane).valid :=
      io.agu(lane).valid && exact(lane) && !drop && !fenced
    arbiter.io.in(lane).bits := request
    // Recovery is a destructive cancellation handshake at this non-resident
    // boundary.  Draining the visible producer request while allocation is
    // masked prevents the same pre-recovery request from allocating after the
    // recovery pulse disappears.  Malformed non-killed requests remain held
    // for fail-closed diagnostics.
    io.agu(lane).ready := drop ||
      (!fenced && arbiter.io.in(lane).ready && exact(lane))

    io.rejected(lane).valid := io.agu(lane).valid &&
      (!exact(lane) || killedLane(lane) || io.flush)
    io.rejected(lane).bits.member := request.execute.i2.row.member
    io.rejected(lane).bits.identityExact := identity
    io.rejected(lane).bits.loadShapeExact := shape
    io.rejected(lane).bits.memoryOrderExact := order
    io.rejected(lane).bits.destinationExact := destination
    io.rejected(lane).bits.pcExact := pc
    io.rejected(lane).bits.killed := killedLane(lane)
    io.rejected(lane).bits.flushed := io.flush
  }

  val selectedLane = arbiter.io.chosen
  val request = arbiter.io.out.bits
  val row = request.execute.i2.row
  val member = row.member
  val order = row.memoryOrder
  val attemptGeneration = generation + 1.U
  val hasOlderStore = order.before.youngestStoreLsidValid
  val youngestStoreIdFull = order.before.storeId - 1.U
  val youngestStoreLsidFull = order.before.youngestStoreLsid

  private def projectMember(target: ROBID): Unit = {
    target.valid := member.group.valid
    target.value := member.group.ridSlot
    target.wrap := member.group.ridGeneration(0)
  }

  private def projectBid(target: ROBID): Unit = {
    val valueWidth = log2Ceil(target.entries)
    target.valid := member.bid.valid
    target.value := member.bid.value(valueWidth - 1, 0)
    target.wrap := (if (p.nativeBidWidth > valueWidth)
      member.bid.value(valueWidth) else member.brobGeneration(0))
  }

  private def projectSerial(target: ROBID, full: UInt): Unit = {
    val valueWidth = log2Ceil(target.entries)
    target.valid := true.B
    target.value := full(valueWidth - 1, 0)
    target.wrap := full(valueWidth)
  }

  val alloc = io.alloc.bits
  alloc := 0.U.asTypeOf(alloc)
  projectBid(alloc.bid)
  projectMember(alloc.gid)
  projectMember(alloc.rid)
  projectSerial(alloc.loadLsId, order.firstLsid)
  alloc.loadLsIdFullValid := true.B
  alloc.loadLsIdFull := order.firstLsid
  alloc.attempt.valid := true.B
  alloc.attempt.producer.valid := member.group.valid
  alloc.attempt.producer.peId := member.group.peId
  alloc.attempt.producer.stid := member.group.stid
  alloc.attempt.producer.nativeBidValid := member.bid.valid
  alloc.attempt.producer.nativeBid := member.bid.value
  alloc.attempt.producer.brobGeneration := member.brobGeneration
  alloc.attempt.producer.ridSlot := member.group.ridSlot
  alloc.attempt.producer.ridGeneration := member.group.ridGeneration
  alloc.attempt.producer.memberIndex := member.memberIndex
  alloc.attempt.producer.residentGeneration := member.residentGeneration
  alloc.attempt.generation := attemptGeneration
  alloc.peId := row.peId
  alloc.stid := row.stid
  alloc.tid := row.stid
  alloc.pc := request.pc
  alloc.addr := request.address
  alloc.size := request.accessBytes
  alloc.returnSignExtend := request.signExtend
  alloc.dst.valid := request.destination.valid
  alloc.dst.kind := request.destination.kind
  alloc.dst.archTag := request.destination.atag
  alloc.dst.relTag := request.destination.relativeIndex
  alloc.dst.physTag := request.destination.ptag
  alloc.dst.oldPhysTag := row.payload.previousPDestinations(0).ptag
  alloc.sourceTraceValid := false.B
  alloc.source0 := 0.U.asTypeOf(alloc.source0)
  alloc.source1 := 0.U.asTypeOf(alloc.source1)
  projectSerial(alloc.youngestStoreId, youngestStoreIdFull)
  alloc.youngestStoreId.valid := hasOlderStore
  projectSerial(alloc.youngestStoreLsId, youngestStoreLsidFull)
  alloc.youngestStoreLsId.valid := hasOlderStore
  alloc.youngestStoreLsIdFullValid := hasOlderStore
  alloc.youngestStoreLsIdFull := youngestStoreLsidFull
  alloc.isTile := false.B
  // I0.15c-a establishes allocation identity only.  Speculative wakeup must
  // become true atomically with the exact wake/cancel composition in c-b.
  alloc.specWakeup := false.B
  alloc.stackValid := false.B
  alloc.returnPipeIndex := selectedLane

  io.alloc.valid := arbiter.io.out.valid
  arbiter.io.out.ready := io.alloc.ready
  io.accepted.valid := io.alloc.fire
  io.accepted.bits.lane := selectedLane
  // Candidate bits remain well formed before ready so a downstream atomic
  // composition can include its own readiness without a combinational
  // `fire -> valid -> ready` cycle.  `accepted.valid` still denotes the only
  // architecturally accepted allocation event.
  io.accepted.bits.load.valid := arbiter.io.out.valid
  io.accepted.bits.load.producer := member
  io.accepted.bits.load.generation := attemptGeneration
  io.accepted.bits.request := request

  when(io.alloc.fire) {
    generation := attemptGeneration
    assert(LoadAttemptIdentity.wellFormed(io.alloc.bits.attempt),
      "OOO-to-LIQ allocation must carry one well-formed exact attempt")
  }
}
