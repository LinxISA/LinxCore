package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, Valid}
import linxcore.common.{DestinationKind, OperandClass}

class OooO3RenameCoordinatorIO(val p: OooParams = OooParams()) extends Bundle {
  val reserve = Flipped(Decoupled(new OooD2GroupedTransaction(p)))
  val cancel = Input(Vec(p.stidCount, Bool()))

  val preparedValid = Output(Bool())
  val prepared = Output(new OooPRenamePreparedTransaction(p))
  val tuPrepared = Output(new OooTURenamePreparedTransaction(p))
  val publishPermit = Input(Bool())
  val publishFire = Output(Bool())

  val completion = Flipped(Decoupled(new OooRobMemberCompletion(p)))
  val commit = Decoupled(new OooRobCommitBatch(p))
  val ptagReturn = Flipped(Decoupled(new OooPTagReturnBatch(p)))

  val queryStid = Input(UInt(p.stidWidth.W))
  val queryAtag = Input(UInt(p.archRegWidth.W))
  val speculativeMapping = Output(new PMapPayload(p))
  val committedMapping = Output(new PMapPayload(p))
  val mapQUsed = Output(Vec(p.stidCount, UInt(p.pMapQCountWidth.W)))
  val tMapQUsed = Output(Vec(p.stidCount, UInt(p.tuMapQCountWidth.W)))
  val uMapQUsed = Output(Vec(p.stidCount, UInt(p.tuMapQCountWidth.W)))

  val pcReadTokens = Input(Vec(p.pcReadPorts, new PcBufferToken(p)))
  val pcReadValid = Output(Vec(p.pcReadPorts, Bool()))
  val pcRead = Output(Vec(p.pcReadPorts, UInt(p.pcWidth.W)))

  val ptagFreeCount = Output(UInt(p.countWidth(p.pPhysRegs).W))
  val ptagProvisionalCount = Output(UInt(p.countWidth(p.pPhysRegs).W))
  val ptagPublishedCount = Output(UInt(p.countWidth(p.pPhysRegs).W))
  val robOccupiedGroups = Output(Vec(p.stidCount,
    UInt(p.countWidth(p.robGroupsPerStid).W)))
  val pCommitBusy = Output(Bool())
  val pCommitRejected = Valid(new OooPRenameCommitReject(p))
  val tuReserveRejected = Valid(new OooTURenamePrepareReject(p))
  val tuPublicationRejected = Valid(new OooTURenamePublishReject(p))
}

/** Atomic D3/S1 seam extended through PTag allocation and P rename.
  *
  * D3 and the PTag staging pool claim on the same reserve handshake. Later,
  * ROB/BROB/PC publication, PTag publication, SMAP update, and MapQ insertion
  * occur on the same terminal fire. Dispatch remains the external permit owner
  * until O5 adds exact IQ reservations.
  */
class OooO3RenameCoordinator(val p: OooParams = OooParams()) extends Module {
  val io = IO(new OooO3RenameCoordinatorIO(p))

  val o3 = Module(new OooRobBrobPcCoordinator(p))
  val ptag = Module(new OooPTagStagingPool(p))
  val prename = Module(new OooProductionPRename(p))
  val turename = Module(new OooProductionTURename(p))

  ptag.io.prepare.valid := io.reserve.valid
  ptag.io.prepare.bits := io.reserve.bits
  turename.io.reservePrepare.valid := io.reserve.valid
  turename.io.reservePrepare.bits := io.reserve.bits
  val renameResourcesReady = ptag.io.prepareReady && turename.io.reserveReady
  // Stale virtual plans are terminally consumed by D3 even when their obsolete
  // P/T/U shape would fail current resource checks. The stale pulse suppresses
  // both physical lease fires below, so this bypass cannot mutate rename state.
  val reserveCanReachD3 = renameResourcesReady || o3.io.d3PlanStale
  o3.io.reserve.valid := io.reserve.valid && reserveCanReachD3
  o3.io.reserve.bits := io.reserve.bits
  io.reserve.ready := o3.io.reserve.ready && reserveCanReachD3
  // D3 intentionally consumes stale virtual plans with zero mutation. Do not
  // turn that input handshake into a PTag claim: only a non-stale D3 reserve
  // may acquire the matching lease.
  ptag.io.reserveFire := io.reserve.fire && !o3.io.d3StaleRejected.valid
  turename.io.reserveFire := io.reserve.fire &&
    !o3.io.d3StaleRejected.valid

  o3.io.cancel := io.cancel
  ptag.io.cancel := io.cancel
  turename.io.cancel := io.cancel
  for (stid <- 0 until p.stidCount) {
    o3.io.publishEligible(stid) := !prename.io.commitBusy ||
      prename.io.commitStid =/= stid.U
  }

  val preparedStid = o3.io.prepared.request.reservation.transaction.plan.stid
  val preparedStidInRange = preparedStid < p.stidCount.U
  val safePreparedStid = Mux(preparedStidInRange, preparedStid, 0.U)
  prename.io.prepare.valid := o3.io.preparedValid
  prename.io.prepare.bits := o3.io.prepared
  prename.io.ptagLease := ptag.io.provisional(safePreparedStid)

  val tuPublication = Wire(new OooTUPublicationRequest(p))
  tuPublication := 0.U.asTypeOf(tuPublication)
  val preparedTransaction = o3.io.prepared.request.reservation.transaction
  val preparedPlan = preparedTransaction.plan
  val preparedDecoded = preparedTransaction.decoded
  tuPublication.peId := preparedPlan.peId
  tuPublication.stid := preparedPlan.stid
  tuPublication.epoch := preparedPlan.epoch
  tuPublication.transactionId := preparedPlan.transactionId
  tuPublication.uopMask := preparedDecoded.uopMask
  for (uopIndex <- 0 until p.decodedUopWidth) {
    val decodedUop = preparedDecoded.uops(uopIndex)
    val publicationUop = tuPublication.uops(uopIndex)
    val activeUop = preparedDecoded.uopMask(uopIndex) && decodedUop.valid
    val groupIndex = preparedTransaction.uopGroupIndex(uopIndex)
    val groupIndexInRange = groupIndex < p.instructionDecodeWidth.U &&
      groupIndex < preparedPlan.groupCount
    val safeGroupIndex = Mux(groupIndexInRange, groupIndex, 0.U)
    val group = preparedTransaction.groups(safeGroupIndex)
    val binding = o3.io.prepared.request.bindings(safeGroupIndex)

    publicationUop.valid := activeUop
    publicationUop.member := 0.U.asTypeOf(publicationUop.member)
    publicationUop.member.group := group.key
    publicationUop.member.bid := binding.brob.bid
    publicationUop.member.brobGeneration := binding.brob.generation
    publicationUop.member.memberIndex :=
      preparedTransaction.uopMemberBase(uopIndex)
    publicationUop.member.residentGeneration := binding.residentGeneration
    for (sourceIndex <- 0 until p.maxSourceOperands) {
      val decodedSource = decodedUop.sources(sourceIndex)
      val source = publicationUop.sources(sourceIndex)
      val sourceIsT = decodedSource.operandClass === OperandClass.T
      val sourceIsU = decodedSource.operandClass === OperandClass.U
      source.valid := activeUop && decodedSource.valid &&
        (sourceIsT || sourceIsU)
      source.kind := Mux(sourceIsT, DestinationKind.T, DestinationKind.U)
      source.relativeIndex := decodedSource.relativeIndex
    }
    for (destinationIndex <- 0 until p.maxDestinationOperands) {
      val decodedDestination = decodedUop.destinations(destinationIndex)
      val destination = publicationUop.destinations(destinationIndex)
      val destinationIsLocal = decodedDestination.kind === DestinationKind.T ||
        decodedDestination.kind === DestinationKind.U
      destination.valid := activeUop && decodedDestination.valid &&
        destinationIsLocal
      destination.kind := decodedDestination.kind
      destination.relativeIndex := decodedDestination.relativeIndex
    }
  }
  turename.io.publicationPrepare.valid := o3.io.preparedValid
  turename.io.publicationPrepare.bits := tuPublication

  io.preparedValid := o3.io.preparedValid && prename.io.prepareReady &&
    turename.io.publicationReady
  io.prepared := prename.io.prepared
  io.tuPrepared := turename.io.prepared
  o3.io.publishPermit := io.publishPermit && prename.io.prepareReady &&
    turename.io.publicationReady
  prename.io.publishFire := o3.io.publishFire
  turename.io.publishFire := o3.io.publishFire
  io.publishFire := o3.io.publishFire

  val exposedPrepareValid = RegInit(false.B)
  val exposedPrepareStid = RegInit(0.U(p.stidWidth.W))
  val exposedPrepareCanceled = exposedPrepareValid &&
    io.cancel(exposedPrepareStid)
  when(io.publishFire || exposedPrepareCanceled) {
    exposedPrepareValid := false.B
  }.elsewhen(io.preparedValid) {
    exposedPrepareValid := true.B
    exposedPrepareStid := preparedStid
  }

  ptag.io.publish.valid := o3.io.publishFire
  ptag.io.publish.bits.stid := preparedStid
  ptag.io.publish.bits.transactionId :=
    o3.io.prepared.request.reservation.transaction.plan.transactionId
  when(o3.io.publishFire) {
    assert(io.preparedValid,
      "O3 P rename publication requires one exact prepared transaction")
    assert(!ptag.io.publishRejected.valid,
      "O3 P rename publication must publish the retained exact PTag lease")
  }

  o3.io.completion <> io.completion

  val commitStid = o3.io.commit.bits.release.firstGroup.stid
  val commitStidInRange = commitStid < p.stidCount.U
  // A fully prepared row that was exposed in a prior cycle cannot be withdrawn.
  // Let that exact row publish first. The O3 coordinator also prevents a raw
  // same-STID ROB commit from exposing a new D3 grant in the first place.
  // Merely provisional, unprepared rows do not block older commit.
  val commitConflictsExposedPrepare = exposedPrepareValid &&
    commitStidInRange && exposedPrepareStid === commitStid
  prename.io.commitPrepare.valid := o3.io.commit.valid &&
    !commitConflictsExposedPrepare
  prename.io.commitPrepare.bits := o3.io.commit.bits
  prename.io.ptagReturn <> ptag.io.release
  io.commit.valid := o3.io.commit.valid && prename.io.commitReady
  io.commit.bits := o3.io.commit.bits
  o3.io.commit.ready := io.commit.ready && prename.io.commitReady
  val sharedCommitFire = io.commit.valid && io.commit.ready
  prename.io.commitFire := sharedCommitFire

  // External recovery return remains sealed until O6 supplies exact killed-row
  // authority. Architectural commit return is wired privately above.
  io.ptagReturn.ready := false.B

  prename.io.queryStid := io.queryStid
  prename.io.queryAtag := io.queryAtag
  io.speculativeMapping := prename.io.speculativeMapping
  io.committedMapping := prename.io.committedMapping
  io.mapQUsed := prename.io.mapQUsed
  io.tMapQUsed := turename.io.tMapQUsed
  io.uMapQUsed := turename.io.uMapQUsed

  o3.io.pcReadTokens := io.pcReadTokens
  io.pcReadValid := o3.io.pcReadValid
  io.pcRead := o3.io.pcRead

  io.ptagFreeCount := ptag.io.freeCount
  io.ptagProvisionalCount := ptag.io.provisionalCount
  io.ptagPublishedCount := ptag.io.publishedCount
  io.robOccupiedGroups := o3.io.robOccupiedGroups
  io.pCommitBusy := prename.io.commitBusy
  io.pCommitRejected := prename.io.commitRejected
  io.tuReserveRejected := turename.io.reserveRejected
  io.tuPublicationRejected := turename.io.publicationRejected
}
