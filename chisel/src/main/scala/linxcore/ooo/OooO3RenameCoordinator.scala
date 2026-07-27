package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, PopCount, Valid}
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

  val recoveryRequest = Flipped(Decoupled(
    new OooRenameRecoveryRequest(p)))
  val recoveryBusy = Output(Bool())
  val recoveryStid = Output(UInt(p.stidWidth.W))
  val recoveryComplete = Output(Bool())
  val recoveryRejected = Valid(new OooRenameRecoveryReject(p))
  val pRecoveryRejected = Valid(new OooPRenameRecoveryReject(p))
  val tuRecoveryRejected = Valid(new OooTURenameRecoveryReject(p))

  val queryStid = Input(UInt(p.stidWidth.W))
  val queryAtag = Input(UInt(p.archRegWidth.W))
  val speculativeMapping = Output(new PMapPayload(p))
  val committedMapping = Output(new PMapPayload(p))
  val mapQUsed = Output(Vec(p.stidCount, UInt(p.pMapQCountWidth.W)))
  val tMapQUsed = Output(Vec(p.stidCount, UInt(p.tuMapQCountWidth.W)))
  val uMapQUsed = Output(Vec(p.stidCount, UInt(p.tuMapQCountWidth.W)))
  val tuRetireSourceUsed = Output(Vec(p.stidCount,
    UInt(p.tuRetireSourceCountWidth.W)))
  val tRelationUsed = Output(Vec(p.stidCount,
    UInt(p.tuRelationCountWidth.W)))
  val uRelationUsed = Output(Vec(p.stidCount,
    UInt(p.tuRelationCountWidth.W)))

  val pcReadTokens = Input(Vec(p.pcReadPorts, new PcBufferToken(p)))
  val pcReadValid = Output(Vec(p.pcReadPorts, Bool()))
  val pcRead = Output(Vec(p.pcReadPorts, UInt(p.pcWidth.W)))

  val ptagFreeCount = Output(UInt(p.countWidth(p.pPhysRegs).W))
  val ptagProvisionalCount = Output(UInt(p.countWidth(p.pPhysRegs).W))
  val ptagPublishedCount = Output(UInt(p.countWidth(p.pPhysRegs).W))
  val robOccupiedGroups = Output(Vec(p.stidCount,
    UInt(p.countWidth(p.robGroupsPerStid).W)))
  val pCommitBusy = Output(Bool())
  val tuCommitBusy = Output(Bool())
  val pCommitRejected = Valid(new OooPRenameCommitReject(p))
  val tuCommitRejected = Valid(new OooTURetireCommitReject(p))
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
  val turetire = Module(new OooProductionTURetire(p))

  val preparedStid = o3.io.prepared.request.reservation.transaction.plan.stid
  val recoveryRequestStid = io.recoveryRequest.bits.key.member.group.stid
  val recoveryRequestStidInRange = recoveryRequestStid < p.stidCount.U
  val recoveryRequestConflictsPrepared = o3.io.preparedValid &&
    recoveryRequestStidInRange && preparedStid === recoveryRequestStid

  turetire.io.recoveryRequest.valid := io.recoveryRequest.valid &&
    !recoveryRequestConflictsPrepared
  turetire.io.recoveryRequest.bits := io.recoveryRequest.bits
  io.recoveryRequest.ready := turetire.io.recoveryRequest.ready &&
    !recoveryRequestConflictsPrepared

  val reserveStid = io.reserve.bits.plan.stid
  val reserveBlockedByRecovery = io.reserve.valid && (
    (turetire.io.recoveryBusy &&
      reserveStid === turetire.io.recoveryStid) ||
    (io.recoveryRequest.valid && recoveryRequestStidInRange &&
      reserveStid === recoveryRequestStid))
  val reserveOffer = io.reserve.valid && !reserveBlockedByRecovery

  ptag.io.prepare.valid := reserveOffer
  ptag.io.prepare.bits := io.reserve.bits
  turename.io.reservePrepare.valid := reserveOffer
  turename.io.reservePrepare.bits := io.reserve.bits
  val renameResourcesReady = ptag.io.prepareReady && turename.io.reserveReady
  // Stale virtual plans are terminally consumed by D3 even when their obsolete
  // P/T/U shape would fail current resource checks. The stale pulse suppresses
  // both physical lease fires below, so this bypass cannot mutate rename state.
  val reserveCanReachD3 = renameResourcesReady || o3.io.d3PlanStale
  o3.io.reserve.valid := reserveOffer && reserveCanReachD3
  o3.io.reserve.bits := io.reserve.bits
  io.reserve.ready := o3.io.reserve.ready && reserveCanReachD3 &&
    !reserveBlockedByRecovery
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
    when(turetire.io.commitBusy && turetire.io.commitStid === stid.U) {
      o3.io.publishEligible(stid) := false.B
    }
    when(turetire.io.recoveryBusy && turetire.io.recoveryStid === stid.U) {
      o3.io.publishEligible(stid) := false.B
    }
  }

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
    val memberEnd = preparedTransaction.uopMemberBase(uopIndex) +&
      decodedUop.plannedChildCount
    publicationUop.blockLast := activeUop && group.boundaryStop &&
      memberEnd === group.physicalMemberCount
    publicationUop.closeBeforeValid := activeUop &&
      uopIndex.U === group.firstLogicalUop &&
      o3.io.prepared.brobImplicitCloseMask(safeGroupIndex)
    publicationUop.closeBefore :=
      o3.io.prepared.brobImplicitClosePointers(safeGroupIndex)
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

  val tuRetirePublication = Wire(new OooTURetirePublication(p))
  tuRetirePublication := 0.U.asTypeOf(tuRetirePublication)
  tuRetirePublication.peId := preparedPlan.peId
  tuRetirePublication.stid := preparedPlan.stid
  tuRetirePublication.epoch := preparedPlan.epoch
  tuRetirePublication.transactionId := preparedPlan.transactionId
  tuRetirePublication.uopMask := preparedDecoded.uopMask
  for (uopIndex <- 0 until p.decodedUopWidth) {
    val renamed = turename.io.prepared.uops(uopIndex)
    val source = tuRetirePublication.sources(uopIndex)
    source.valid := renamed.valid
    source.transactionId := preparedPlan.transactionId
    source.epoch := preparedPlan.epoch
    source.uopIndex := uopIndex.U
    source.member := renamed.member
    source.blockLast := renamed.blockLast
    source.closeBeforeValid := renamed.closeBeforeValid
    source.closeBefore := renamed.closeBefore
    source.tSeqBefore := renamed.tSeqBefore
    source.uSeqBefore := renamed.uSeqBefore
    source.pDestinationCount := PopCount((0 until p.maxDestinationOperands)
      .map { destinationIndex =>
        prename.io.prepared.mapQRows(
          uopIndex * p.maxDestinationOperands + destinationIndex).valid
      })
    source.destinations := renamed.destinations
  }
  turetire.io.publicationPrepare.valid := o3.io.preparedValid &&
    turename.io.publicationReady
  turetire.io.publicationPrepare.bits := tuRetirePublication
  turename.io.retireCommand <> turetire.io.retireCommand
  turename.io.blockCommit <> turetire.io.blockCommit

  // The retire-source owner is the sole suffix authority. Authorization and
  // every youngest-to-oldest source item fire only when both state owners can
  // accept in the same cycle; neither owner may observe a partial recovery.
  val recoveryAuthorizeBothReady = prename.io.recoveryAuthorize.ready &&
    turename.io.recoveryAuthorize.ready
  turetire.io.recoveryAuthorize.ready := recoveryAuthorizeBothReady
  prename.io.recoveryAuthorize.valid :=
    turetire.io.recoveryAuthorize.valid && turename.io.recoveryAuthorize.ready
  prename.io.recoveryAuthorize.bits := turetire.io.recoveryAuthorize.bits
  turename.io.recoveryAuthorize.valid :=
    turetire.io.recoveryAuthorize.valid && prename.io.recoveryAuthorize.ready
  turename.io.recoveryAuthorize.bits := turetire.io.recoveryAuthorize.bits

  val recoverySourceBothReady = prename.io.recoverySource.ready &&
    turename.io.recoverySource.ready
  turetire.io.recoverySource.ready := recoverySourceBothReady
  prename.io.recoverySource.valid :=
    turetire.io.recoverySource.valid && turename.io.recoverySource.ready
  prename.io.recoverySource.bits := turetire.io.recoverySource.bits
  turename.io.recoverySource.valid :=
    turetire.io.recoverySource.valid && prename.io.recoverySource.ready
  turename.io.recoverySource.bits := turetire.io.recoverySource.bits

  prename.io.recoverySourcesDone := turetire.io.recoverySourcesDone
  turename.io.recoverySourcesDone := turetire.io.recoverySourcesDone
  val commonRecoveryComplete = turetire.io.recoverySourcesDone &&
    prename.io.recoveryComplete && turename.io.recoveryComplete
  turetire.io.recoveryFinish := commonRecoveryComplete
  prename.io.recoveryFinish := commonRecoveryComplete
  turename.io.recoveryFinish := commonRecoveryComplete

  when(turetire.io.recoveryAuthorize.fire) {
    assert(prename.io.recoveryAuthorize.fire &&
      turename.io.recoveryAuthorize.fire,
      "rename recovery authorization must start P and T/U atomically")
  }
  when(turetire.io.recoverySource.fire) {
    assert(prename.io.recoverySource.fire && turename.io.recoverySource.fire,
      "rename recovery source must mutate P and T/U atomically")
  }

  io.preparedValid := o3.io.preparedValid && prename.io.prepareReady &&
    turename.io.publicationReady && turetire.io.publicationReady
  io.prepared := prename.io.prepared
  io.tuPrepared := turename.io.prepared
  o3.io.publishPermit := io.publishPermit && prename.io.prepareReady &&
    turename.io.publicationReady && turetire.io.publicationReady
  prename.io.publishFire := o3.io.publishFire
  turename.io.publishFire := o3.io.publishFire
  turetire.io.publishFire := o3.io.publishFire
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
  val commitProbeValid = o3.io.commit.valid &&
    !commitConflictsExposedPrepare
  prename.io.commitPrepare.bits := o3.io.commit.bits
  turetire.io.commitPrepare.bits := o3.io.commit.bits
  val commitOwnersStarted = RegInit(false.B)
  val commitOwnersStart = commitProbeValid && !commitOwnersStarted &&
    prename.io.commitStartReady && turetire.io.commitStartReady
  val ownerCommitPrepareValid = commitOwnersStarted || commitOwnersStart
  prename.io.commitPrepare.valid := ownerCommitPrepareValid
  turetire.io.commitPrepare.valid := ownerCommitPrepareValid
  when(commitOwnersStart) {
    commitOwnersStarted := true.B
  }
  prename.io.ptagReturn <> ptag.io.release
  io.commit.valid := o3.io.commit.valid && commitOwnersStarted &&
    prename.io.commitReady && turetire.io.commitReady
  io.commit.bits := o3.io.commit.bits
  o3.io.commit.ready := io.commit.ready && commitOwnersStarted &&
    prename.io.commitReady && turetire.io.commitReady
  val sharedCommitFire = io.commit.valid && io.commit.ready
  prename.io.commitFire := sharedCommitFire
  turetire.io.commitFire := sharedCommitFire
  when(sharedCommitFire) {
    commitOwnersStarted := false.B
  }

  // P recovery and architectural commit share the exact internal return owner.
  // The legacy external return seam remains closed until its O6 removal.
  io.ptagReturn.ready := false.B

  prename.io.queryStid := io.queryStid
  prename.io.queryAtag := io.queryAtag
  io.speculativeMapping := prename.io.speculativeMapping
  io.committedMapping := prename.io.committedMapping
  io.mapQUsed := prename.io.mapQUsed
  io.tMapQUsed := turename.io.tMapQUsed
  io.uMapQUsed := turename.io.uMapQUsed
  io.tuRetireSourceUsed := turetire.io.sourceQueueUsed
  io.tRelationUsed := turetire.io.tRelationUsed
  io.uRelationUsed := turetire.io.uRelationUsed

  o3.io.pcReadTokens := io.pcReadTokens
  io.pcReadValid := o3.io.pcReadValid
  io.pcRead := o3.io.pcRead

  io.ptagFreeCount := ptag.io.freeCount
  io.ptagProvisionalCount := ptag.io.provisionalCount
  io.ptagPublishedCount := ptag.io.publishedCount
  io.robOccupiedGroups := o3.io.robOccupiedGroups
  io.pCommitBusy := prename.io.commitBusy
  io.tuCommitBusy := turetire.io.commitBusy
  io.pCommitRejected := prename.io.commitRejected
  io.tuCommitRejected := turetire.io.commitRejected
  io.tuReserveRejected := turename.io.reserveRejected
  io.tuPublicationRejected := turename.io.publicationRejected
  io.recoveryBusy := turetire.io.recoveryBusy || prename.io.recoveryBusy ||
    turename.io.recoveryBusy
  io.recoveryStid := Mux(turetire.io.recoveryBusy,
    turetire.io.recoveryStid,
    Mux(prename.io.recoveryBusy, prename.io.recoveryStid,
      turename.io.recoveryStid))
  io.recoveryComplete := commonRecoveryComplete
  io.recoveryRejected := turetire.io.recoveryRejected
  io.pRecoveryRejected := prename.io.recoveryRejected
  io.tuRecoveryRejected := turename.io.recoveryRejected
}
