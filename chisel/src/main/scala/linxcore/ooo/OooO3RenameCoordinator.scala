package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, PopCount, RRArbiter, Valid}
import linxcore.common.{DestinationKind, OperandClass}
import linxcore.lsu.STQRobCommitToken

object OooGlobalRecoveryState extends ChiselEnum {
  val Idle, CaptureOwners, PrepareOwners, Rebuild, AbortOwners = Value
}

class OooO3RenameCoordinatorIO(val p: OooParams = OooParams()) extends Bundle {
  val reserve = Flipped(Decoupled(new OooD2GroupedTransaction(p)))
  val cancel = Input(Vec(p.stidCount, Bool()))

  val preparedValid = Output(Bool())
  val prepared = Output(new OooPRenamePreparedTransaction(p))
  val tuPrepared = Output(new OooTURenamePreparedTransaction(p))
  val iexS1 = Decoupled(new OooIexS1Transaction(p))
  val publishFire = Output(Bool())

  val fastBoundary = Decoupled(new OooFastResolveBoundaryRequest(p))
  val fastWriteback = Decoupled(new OooFastResolveWriteback(p))
  val fastWakeup = Decoupled(new OooIexWakeup(p))
  val fastTrace = Decoupled(new OooFastResolveTrace(p))
  val fastPendingByStid = Output(Vec(p.stidCount,
    UInt(p.decodedUopCountWidth.W)))
  val fastTerminalFire = Output(Bool())
  val fastS1Rejected = Valid(new OooFastResolveS1Reject(p))

  val completion = Flipped(Decoupled(new OooRobMemberCompletion(p)))
  val nonFlushEvidence = Flipped(Decoupled(new OooRobNonFlushEvidence(p)))
  val interruptPending = Input(Vec(p.stidCount, Bool()))
  val nonFlushWindows = Output(Vec(p.stidCount, new NonFlushWindow(p)))
  val commit = Decoupled(new OooRobCommitBatch(p))
  val storeCommit = Decoupled(new STQRobCommitToken(
    p.robGroupsPerStid, p.lsidWidth, p.peIdWidth, p.stidWidth,
    p.nativeBidWidth, p.ridGenerationWidth, p.brobGenerationWidth,
    p.robMemberIndexWidth, p.residentGenerationWidth))
  val storeCommitUsed = Output(UInt(p.storeCommitBufferCountWidth.W))
  val storeCommitFree = Output(UInt(p.storeCommitBufferCountWidth.W))
  val storeCommitRejected = Valid(new OooRobCommitBatch(p))
  val dispatchReleases = Flipped(Vec(p.iexReleaseWidth,
    Decoupled(new OooDispatchRelease(p))))
  def dispatchRelease = dispatchReleases(0)
  val ptagRecycle = Decoupled(new OooPTagReturnBatch(p))

  val recoveryRequest = Flipped(Decoupled(
    new OooGlobalRecoveryRequest(p)))
  val recoveryBusy = Output(Bool())
  val recoveryStid = Output(UInt(p.stidWidth.W))
  val recoveryComplete = Output(Bool())
  val recoveryCompleted = Valid(new OooGlobalRecoveryRequest(p))
  val recoveryApplied = Valid(new OooGlobalRecoveryRequest(p))
  val recoveryAborted = Valid(new OooGlobalRecoveryRequest(p))
  val iexRecoveryPrepare = Valid(new OooResidencyRecoveryPlan(p))
  val iexRecoveryPrepareReady = Input(Bool())
  val iexRecoveryPrepared = Input(new OooIexRecoveryPrepared(p))
  val iexRecoveryRejected = Flipped(Valid(new OooIexRecoveryReject(p)))
  val iexRecoveryFire = Output(Bool())
  val recoveryRejected = Valid(new OooRenameRecoveryReject(p))
  val pRecoveryRejected = Valid(new OooPRenameRecoveryReject(p))
  val tuRecoveryRejected = Valid(new OooTURenameRecoveryReject(p))
  val robRecoveryRejected = Valid(new OooRobRecoveryReject(p))
  val d3RecoveryRejected = Valid(new OooD3RecoveryReject(p))
  val brobRecoveryRejected = Valid(new OooBrobRecoveryReject(p))
  val pcRecoveryRejected = Valid(new OooPcRecoveryReject(p))
  val dispatchRecoveryRejected = Valid(new OooDispatchRecoveryReject(p))
  val fastRecoveryRejected = Valid(new OooFastResolveRecoveryReject(p))
  val memoryOrderPrepareRejected = Valid(new OooMemoryOrderPrepareReject(p))
  val memoryOrderRecoveryRejected =
    Valid(new OooMemoryOrderRecoveryReject(p))
  val memoryOrderNext = Output(Vec(p.stidCount,
    new OooMemoryIdState(p)))
  val dispatchPrepared = Output(new OooDispatchReservationLease(p))
  val dispatchFreeEntries = Output(Vec(p.iqClassCount,
    Vec(p.iqBankCount, UInt(p.iqBankEntryCountWidth.W))))
  val dispatchProvisionalEntries = Output(Vec(p.iqClassCount,
    Vec(p.iqBankCount, UInt(p.iqBankEntryCountWidth.W))))
  val dispatchPublishedEntries = Output(Vec(p.iqClassCount,
    Vec(p.iqBankCount, UInt(p.iqBankEntryCountWidth.W))))
  val dispatchPrepareRejected = Valid(new OooDispatchPrepareReject(p))
  val dispatchPublishRejected = Valid(new OooDispatchPublishReject(p))
  val dispatchReleaseRejecteds = Vec(p.iexReleaseWidth,
    Valid(new OooDispatchReleaseReject(p)))
  def dispatchReleaseRejected = dispatchReleaseRejecteds(0)

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
  val d3UsedGroups = Output(Vec(p.stidCount,
    UInt(p.countWidth(p.robGroupsPerStid).W)))
  val d3PublishedGroups = Output(Vec(p.stidCount,
    UInt(p.countWidth(p.robGroupsPerStid).W)))
  val d3TailSlot = Output(Vec(p.stidCount, UInt(p.ridSlotWidth.W)))
  val d3TailGeneration = Output(Vec(p.stidCount,
    UInt(p.ridGenerationWidth.W)))
  val d3TailEpoch = Output(Vec(p.stidCount,
    UInt(p.reservationEpochWidth.W)))
  val d3NextTransactionId = Output(Vec(p.stidCount,
    UInt(p.transactionIdWidth.W)))
  val pCommitBusy = Output(Bool())
  val tuCommitBusy = Output(Bool())
  val pCommitRejected = Valid(new OooPRenameCommitReject(p))
  val tuCommitRejected = Valid(new OooTURetireCommitReject(p))
  val tuReserveRejected = Valid(new OooTURenamePrepareReject(p))
  val tuPublicationRejected = Valid(new OooTURenamePublishReject(p))
  val nonFlushEvidenceRejected = Valid(new OooRobNonFlushEvidenceReject(p))
}

/** Atomic D3/S1 seam through ROB/BROB/PC, P/T/U rename, and dispatch.
  *
  * D3 and the PTag staging pool claim on the same reserve handshake. Later,
  * ROB/BROB/PC publication, PTag publication, SMAP update, and MapQ insertion
  * and exact IQ reservations occur on the same terminal fire.  The terminal
  * sink is the real retained IEX S1 transaction; no external Boolean permit
  * can publish the OOO owners without also transferring that exact payload.
  */
class OooO3RenameCoordinator(
    val p: OooParams = OooParams(),
    val capabilityTopology: OooIexCapabilityTopology =
      OooIexCapabilityTopology(Seq.empty)) extends Module {
  private val effectiveCapabilityTopology =
    if (capabilityTopology.classBankCapabilities.isEmpty)
      OooIexCapabilityTopology.permissive(p)
    else capabilityTopology
  val io = IO(new OooO3RenameCoordinatorIO(p))

  val o3 = Module(new OooRobBrobPcCoordinator(p))
  val ptag = Module(new OooPTagStagingPool(p))
  val prename = Module(new OooPRename(p))
  val turename = Module(new OooTURename(p))
  val turetire = Module(new OooTURetire(p))
  val dispatch = Module(new OooDispatch(p, effectiveCapabilityTopology))
  val fast = Module(new OooFastResolve(p))
  val memoryOrder = Module(new OooMemoryOrderAllocator(p))
  val storeCommit = Module(new OooRobStoreCommitOwner(p))

  val preparedStid = o3.io.prepared.request.reservation.transaction.plan.stid
  val globalRecoveryState = RegInit(OooGlobalRecoveryState.Idle)
  val globalRecoveryRequest = RegInit(
    0.U.asTypeOf(new OooGlobalRecoveryRequest(p)))
  val lowerRecoveryAccepted = RegInit(false.B)
  val renameRecoveryAccepted = RegInit(false.B)
  val globalRecoveryActive =
    globalRecoveryState =/= OooGlobalRecoveryState.Idle
  val activeRecoveryStid = globalRecoveryRequest.rename.key.member.group.stid

  val recoveryRequestStid =
    io.recoveryRequest.bits.rename.key.member.group.stid
  val recoveryRequestStidInRange = recoveryRequestStid < p.stidCount.U
  val recoveryRequestConflictsPrepared = o3.io.preparedValid &&
    recoveryRequestStidInRange && preparedStid === recoveryRequestStid
  io.recoveryRequest.ready := !globalRecoveryActive &&
    !recoveryRequestConflictsPrepared
  when(io.recoveryRequest.fire) {
    globalRecoveryRequest := io.recoveryRequest.bits
    lowerRecoveryAccepted := false.B
    renameRecoveryAccepted := false.B
    globalRecoveryState := OooGlobalRecoveryState.CaptureOwners
  }

  val reserveStid = io.reserve.bits.plan.stid
  val reserveBlockedByRecovery = io.reserve.valid && (
    (globalRecoveryActive && reserveStid === activeRecoveryStid) ||
    (io.recoveryRequest.valid && recoveryRequestStidInRange &&
      reserveStid === recoveryRequestStid))
  val reserveOffer = io.reserve.valid && !reserveBlockedByRecovery

  ptag.io.prepare.valid := reserveOffer
  ptag.io.prepare.bits := io.reserve.bits
  turename.io.reservePrepare.valid := reserveOffer
  turename.io.reservePrepare.bits := io.reserve.bits
  dispatch.io.prepare.valid := reserveOffer
  dispatch.io.prepare.bits := io.reserve.bits
  memoryOrder.io.prepare.valid := reserveOffer
  memoryOrder.io.prepare.bits := io.reserve.bits
  val renameResourcesReady = ptag.io.prepareReady && turename.io.reserveReady &&
    dispatch.io.prepareReady && memoryOrder.io.prepareReady
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
  dispatch.io.reserveFire := io.reserve.fire &&
    !o3.io.d3StaleRejected.valid
  memoryOrder.io.reserveFire := io.reserve.fire &&
    !o3.io.d3StaleRejected.valid

  o3.io.cancel := io.cancel
  o3.io.nonFlushEvidence <> io.nonFlushEvidence
  o3.io.interruptPending := io.interruptPending
  io.nonFlushWindows := o3.io.nonFlushWindows
  val captureRecoveryOwners =
    globalRecoveryState === OooGlobalRecoveryState.CaptureOwners
  o3.io.recoveryRequest.valid := captureRecoveryOwners &&
    !lowerRecoveryAccepted
  o3.io.recoveryRequest.bits := globalRecoveryRequest
  turetire.io.recoveryRequest.valid := captureRecoveryOwners &&
    !renameRecoveryAccepted
  turetire.io.recoveryRequest.bits := globalRecoveryRequest.rename
  val lowerRecoveryFire = o3.io.recoveryRequest.fire
  val renameRecoveryFire = turetire.io.recoveryRequest.fire
  when(lowerRecoveryFire) {
    lowerRecoveryAccepted := true.B
  }
  when(renameRecoveryFire) {
    renameRecoveryAccepted := true.B
  }
  val recoveryOwnersCaptured =
    (lowerRecoveryAccepted || lowerRecoveryFire) &&
      (renameRecoveryAccepted || renameRecoveryFire)
  when(captureRecoveryOwners && recoveryOwnersCaptured) {
    globalRecoveryState := OooGlobalRecoveryState.PrepareOwners
  }

  val residencyRecoveryPlan = Wire(new OooResidencyRecoveryPlan(p))
  residencyRecoveryPlan := 0.U.asTypeOf(residencyRecoveryPlan)
  residencyRecoveryPlan.valid := o3.io.recoveryPrepared.valid
  residencyRecoveryPlan.oldHead := o3.io.recoveryPrepared.oldHead
  residencyRecoveryPlan.oldOccupied := o3.io.recoveryPrepared.oldOccupied
  residencyRecoveryPlan.newOccupied := o3.io.recoveryPrepared.newOccupied
  residencyRecoveryPlan.pivotOffset := o3.io.recoveryPrepared.pivotOffset
  residencyRecoveryPlan.pivot :=
    o3.io.recoveryPrepared.request.rename.key.member
  residencyRecoveryPlan.pivotPhysicalMemberCount :=
    o3.io.recoveryPrepared.pivot.physicalMemberCount
  residencyRecoveryPlan.survivingPivotValid :=
    o3.io.recoveryPrepared.survivingPivotValid
  residencyRecoveryPlan.survivingPivotPhysicalMemberCount := Mux(
    o3.io.recoveryPrepared.survivingPivotValid,
    o3.io.recoveryPrepared.survivingPivot.physicalMemberCount,
    0.U)

  val prepareGlobalOwners =
    globalRecoveryState === OooGlobalRecoveryState.PrepareOwners &&
      o3.io.recoveryPreparedValid && turetire.io.recoveryAuthorize.valid
  dispatch.io.recoveryPrepare.valid := prepareGlobalOwners
  dispatch.io.recoveryPrepare.bits := residencyRecoveryPlan
  fast.io.recoveryPrepare.valid := prepareGlobalOwners
  fast.io.recoveryPrepare.bits := residencyRecoveryPlan
  memoryOrder.io.recoveryPrepare.valid := prepareGlobalOwners
  memoryOrder.io.recoveryPrepare.bits := o3.io.recoveryPrepared
  io.iexRecoveryPrepare.valid := prepareGlobalOwners
  io.iexRecoveryPrepare.bits := residencyRecoveryPlan

  val iexRecoveryPreparedExact = io.iexRecoveryPrepareReady &&
    io.iexRecoveryPrepared.valid &&
    io.iexRecoveryPrepared.stid === activeRecoveryStid
  val renameRecoveryAuthority = turetire.io.recoveryAuthorize.bits
  val renameRecoveryAuthorityExact =
    renameRecoveryAuthority.key.member.group.valid &&
      renameRecoveryAuthority.key.member.bid.valid &&
      renameRecoveryAuthority.key.member.group.stid < p.stidCount.U &&
      renameRecoveryAuthority.key.member.group.stid === activeRecoveryStid
  val renameRecoveryPrepared = prename.io.recoveryAuthorize.ready &&
    turename.io.recoveryAuthorize.ready
  val allGlobalOwnersPrepared = prepareGlobalOwners &&
    dispatch.io.recoveryPrepareReady && fast.io.recoveryPrepareReady &&
    memoryOrder.io.recoveryPrepareReady &&
    iexRecoveryPreparedExact && renameRecoveryAuthorityExact &&
    renameRecoveryPrepared
  val globalRecoveryApply = allGlobalOwnersPrepared
  o3.io.recoveryApply := globalRecoveryApply
  dispatch.io.recoveryFire := globalRecoveryApply
  fast.io.recoveryFire := globalRecoveryApply
  memoryOrder.io.recoveryFire := globalRecoveryApply
  io.iexRecoveryFire := globalRecoveryApply
  io.recoveryApplied.valid := globalRecoveryApply
  io.recoveryApplied.bits := globalRecoveryRequest
  when(globalRecoveryApply) {
    assert(o3.io.recoveryApplied.valid && dispatch.io.recoveryPrepared.valid &&
      fast.io.recoveryPrepared.valid &&
      memoryOrder.io.recoveryPrepared.valid && io.iexRecoveryPrepared.valid,
      "all lower and upper physical owners must share one recovery apply")
    assert(turetire.io.recoveryAuthorize.fire &&
      prename.io.recoveryAuthorize.fire && turename.io.recoveryAuthorize.fire,
      "global recovery apply must authorize P/T/U rebuild atomically")
  }
  when(turetire.io.recoveryAuthorize.valid) {
    assert(renameRecoveryAuthorityExact,
      "rename suffix authority must retain the exact active recovery key")
  }

  val lowerRecoveryRejected = o3.io.robRecoveryRejected.valid ||
    o3.io.d3RecoveryRejected.valid || o3.io.brobRecoveryRejected.valid ||
    o3.io.pcRecoveryRejected.valid
  val upperRecoveryRejected = turetire.io.recoveryRejected.valid ||
    dispatch.io.recoveryRejected.valid || fast.io.recoveryRejected.valid ||
    memoryOrder.io.recoveryRejected.valid ||
    io.iexRecoveryRejected.valid
  val anyGlobalRecoveryRejected = globalRecoveryActive &&
    (lowerRecoveryRejected || upperRecoveryRejected)
  val abortGlobalOwners =
    globalRecoveryState === OooGlobalRecoveryState.AbortOwners
  o3.io.recoveryAbort := abortGlobalOwners && o3.io.recoveryBusy
  turetire.io.recoveryAbort := abortGlobalOwners && turetire.io.recoveryBusy

  ptag.io.cancel := io.cancel
  turename.io.cancel := io.cancel
  dispatch.io.cancel := io.cancel
  memoryOrder.io.cancel := io.cancel
  for (stid <- 0 until p.stidCount) {
    o3.io.publishEligible(stid) := !prename.io.commitBusy ||
      prename.io.commitStid =/= stid.U
    when(turetire.io.commitBusy && turetire.io.commitStid === stid.U) {
      o3.io.publishEligible(stid) := false.B
    }
    when(globalRecoveryActive && activeRecoveryStid === stid.U) {
      o3.io.publishEligible(stid) := false.B
    }
  }

  val preparedStidInRange = preparedStid < p.stidCount.U
  val safePreparedStid = Mux(preparedStidInRange, preparedStid, 0.U)
  prename.io.prepare.valid := o3.io.preparedValid
  prename.io.prepare.bits := o3.io.prepared
  prename.io.ptagLease := ptag.io.provisional(safePreparedStid)
  prename.io.dispatchLease := dispatch.io.provisional(safePreparedStid)
  io.dispatchPrepared := dispatch.io.provisional(safePreparedStid)
  o3.io.memoryOrder := memoryOrder.io.provisional(safePreparedStid)
  memoryOrder.io.publishPrepare.valid := o3.io.preparedValid
  memoryOrder.io.publishPrepare.bits :=
    o3.io.prepared.request.reservation

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
  turetire.io.recoveryAuthorize.ready := recoveryAuthorizeBothReady &&
    globalRecoveryApply
  prename.io.recoveryAuthorize.valid :=
    turetire.io.recoveryAuthorize.valid && turename.io.recoveryAuthorize.ready &&
      globalRecoveryApply
  prename.io.recoveryAuthorize.bits := turetire.io.recoveryAuthorize.bits
  turename.io.recoveryAuthorize.valid :=
    turetire.io.recoveryAuthorize.valid && prename.io.recoveryAuthorize.ready &&
      globalRecoveryApply
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
  val globalRecoveryAbortComplete = abortGlobalOwners &&
    !o3.io.recoveryBusy && !turetire.io.recoveryBusy
  turetire.io.recoveryFinish := commonRecoveryComplete
  prename.io.recoveryFinish := commonRecoveryComplete
  turename.io.recoveryFinish := commonRecoveryComplete

  when(globalRecoveryApply) {
    globalRecoveryState := OooGlobalRecoveryState.Rebuild
  }.elsewhen(anyGlobalRecoveryRejected) {
    globalRecoveryState := OooGlobalRecoveryState.AbortOwners
  }.elsewhen(globalRecoveryAbortComplete) {
    globalRecoveryState := OooGlobalRecoveryState.Idle
    lowerRecoveryAccepted := false.B
    renameRecoveryAccepted := false.B
  }.elsewhen(globalRecoveryState === OooGlobalRecoveryState.Rebuild &&
      commonRecoveryComplete) {
    globalRecoveryState := OooGlobalRecoveryState.Idle
    lowerRecoveryAccepted := false.B
    renameRecoveryAccepted := false.B
  }

  when(turetire.io.recoveryAuthorize.fire) {
    assert(prename.io.recoveryAuthorize.fire &&
      turename.io.recoveryAuthorize.fire,
      "rename recovery authorization must start P and T/U atomically")
  }
  when(globalRecoveryState === OooGlobalRecoveryState.Rebuild) {
    assert(!prename.io.recoveryRejected.valid &&
      !turename.io.recoveryRejected.valid,
      "prevalidated P/T/U recovery authority cannot reject after apply")
  }
  when(turetire.io.recoverySource.fire) {
    assert(prename.io.recoverySource.fire && turename.io.recoverySource.fire,
      "rename recovery source must mutate P and T/U atomically")
  }

  io.preparedValid := o3.io.preparedValid && prename.io.prepareReady &&
    turename.io.publicationReady && turetire.io.publicationReady &&
    memoryOrder.io.publishReady
  io.prepared := prename.io.prepared
  io.tuPrepared := turename.io.prepared
  // The real IEX residency owner and typed fast-resolve owner observe the
  // identical retained common-S1 transaction.  Neither can consume alone.
  io.iexS1.valid := io.preparedValid && fast.io.s1.ready
  io.iexS1.bits.o3 := o3.io.prepared
  io.iexS1.bits.pRename := prename.io.prepared
  io.iexS1.bits.tuRename := turename.io.prepared
  io.iexS1.bits.dispatch := dispatch.io.provisional(safePreparedStid)
  io.iexS1.bits.memoryOrder :=
    memoryOrder.io.provisional(safePreparedStid)
  fast.io.s1.valid := io.preparedValid && io.iexS1.ready
  fast.io.s1.bits := io.iexS1.bits
  o3.io.publishPermit := io.iexS1.ready && fast.io.s1.ready &&
    prename.io.prepareReady &&
    turename.io.publicationReady && turetire.io.publicationReady &&
    memoryOrder.io.publishReady
  prename.io.publishFire := o3.io.publishFire
  turename.io.publishFire := o3.io.publishFire
  turetire.io.publishFire := o3.io.publishFire
  io.publishFire := o3.io.publishFire
  memoryOrder.io.publishFire := o3.io.publishFire
  when(io.iexS1.valid || fast.io.s1.valid || o3.io.publishFire) {
    assert(io.iexS1.fire === o3.io.publishFire,
      "OOO publication and the exact IEX S1 transfer must share one fire")
    assert(fast.io.s1.fire === o3.io.publishFire,
      "OOO publication and typed fast-resolve observation must share one fire")
  }

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
  dispatch.io.publish.valid := o3.io.publishFire
  dispatch.io.publish.bits.peId :=
    o3.io.prepared.request.reservation.transaction.plan.peId
  dispatch.io.publish.bits.stid := preparedStid
  dispatch.io.publish.bits.epoch :=
    o3.io.prepared.request.reservation.transaction.plan.epoch
  dispatch.io.publish.bits.transactionId :=
    o3.io.prepared.request.reservation.transaction.plan.transactionId
  dispatch.io.publish.bits.memberMask :=
    dispatch.io.provisional(safePreparedStid).allocationMask
  for (lane <- 0 until p.dispatchWidth) {
    val allocation = dispatch.io.provisional(safePreparedStid).allocations(lane)
    val pUop = prename.io.prepared.uops(allocation.uopIndex)
    dispatch.io.publish.bits.members(lane) := pUop.member
    dispatch.io.publish.bits.members(lane).memberIndex :=
      pUop.member.memberIndex + allocation.childIndex
  }
  when(o3.io.publishFire) {
    assert(!dispatch.io.publishRejected.valid,
      "O3 publication must publish the retained exact dispatch lease")
  }

  val completionArbiter = Module(new RRArbiter(
    new OooRobMemberCompletion(p), 2))
  completionArbiter.io.in(0) <> io.completion
  completionArbiter.io.in(1) <> fast.io.completion
  o3.io.completion <> completionArbiter.io.out

  io.fastBoundary <> fast.io.boundary
  io.fastWriteback <> fast.io.writeback
  io.fastWakeup <> fast.io.wakeup
  io.fastTrace <> fast.io.trace
  io.fastPendingByStid := fast.io.pendingByStid
  io.fastTerminalFire := fast.io.terminalFire
  io.fastS1Rejected := fast.io.s1Rejected

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
  storeCommit.io.commitPrepare.bits := o3.io.commit.bits
  val commitOwnersStarted = RegInit(false.B)
  val commitOwnersStart = commitProbeValid && !commitOwnersStarted &&
    prename.io.commitStartReady && turetire.io.commitStartReady &&
    storeCommit.io.commitStartReady
  val ownerCommitPrepareValid = commitOwnersStarted || commitOwnersStart
  prename.io.commitPrepare.valid := ownerCommitPrepareValid
  turetire.io.commitPrepare.valid := ownerCommitPrepareValid
  storeCommit.io.commitPrepare.valid := ownerCommitPrepareValid
  when(commitOwnersStart) {
    commitOwnersStarted := true.B
  }
  // A returned token is made free only on the same handshake which tells the
  // external IEX scoreboard to invalidate that exact generation.
  ptag.io.release.valid := prename.io.ptagReturn.valid &&
    io.ptagRecycle.ready
  ptag.io.release.bits := prename.io.ptagReturn.bits
  io.ptagRecycle.valid := prename.io.ptagReturn.valid &&
    ptag.io.release.ready
  io.ptagRecycle.bits := prename.io.ptagReturn.bits
  prename.io.ptagReturn.ready := ptag.io.release.ready &&
    io.ptagRecycle.ready
  io.commit.valid := o3.io.commit.valid && commitOwnersStarted &&
    prename.io.commitReady && turetire.io.commitReady &&
    storeCommit.io.commitStartReady
  io.commit.bits := o3.io.commit.bits
  o3.io.commit.ready := io.commit.ready && commitOwnersStarted &&
    prename.io.commitReady && turetire.io.commitReady &&
    storeCommit.io.commitStartReady
  val sharedCommitFire = io.commit.valid && io.commit.ready
  prename.io.commitFire := sharedCommitFire
  turetire.io.commitFire := sharedCommitFire
  storeCommit.io.commitFire := sharedCommitFire
  when(sharedCommitFire) {
    commitOwnersStarted := false.B
  }

  dispatch.io.releases <> io.dispatchReleases

  io.storeCommit <> storeCommit.io.storeCommit
  io.storeCommitUsed := storeCommit.io.used
  io.storeCommitFree := storeCommit.io.free
  io.storeCommitRejected := storeCommit.io.commitRejected

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
  io.d3UsedGroups := o3.io.d3UsedGroups
  io.d3PublishedGroups := o3.io.d3PublishedGroups
  io.d3TailSlot := o3.io.d3TailSlot
  io.d3TailGeneration := o3.io.d3TailGeneration
  io.d3TailEpoch := o3.io.d3TailEpoch
  io.d3NextTransactionId := o3.io.d3NextTransactionId
  io.pCommitBusy := prename.io.commitBusy
  io.tuCommitBusy := turetire.io.commitBusy
  io.pCommitRejected := prename.io.commitRejected
  io.tuCommitRejected := turetire.io.commitRejected
  io.tuReserveRejected := turename.io.reserveRejected
  io.tuPublicationRejected := turename.io.publicationRejected
  io.nonFlushEvidenceRejected := o3.io.nonFlushEvidenceRejected
  io.dispatchFreeEntries := dispatch.io.freeEntries
  io.dispatchProvisionalEntries := dispatch.io.provisionalEntries
  io.dispatchPublishedEntries := dispatch.io.publishedEntries
  io.dispatchPrepareRejected := dispatch.io.prepareRejected
  io.dispatchPublishRejected := dispatch.io.publishRejected
  io.dispatchReleaseRejecteds := dispatch.io.releaseRejecteds
  io.recoveryBusy := globalRecoveryActive || turetire.io.recoveryBusy ||
    prename.io.recoveryBusy || turename.io.recoveryBusy || o3.io.recoveryBusy
  io.recoveryStid := Mux(globalRecoveryActive, activeRecoveryStid,
    Mux(turetire.io.recoveryBusy, turetire.io.recoveryStid,
      Mux(prename.io.recoveryBusy, prename.io.recoveryStid,
        turename.io.recoveryStid)))
  io.recoveryComplete := commonRecoveryComplete
  io.recoveryCompleted.valid :=
    globalRecoveryState === OooGlobalRecoveryState.Rebuild && commonRecoveryComplete
  io.recoveryCompleted.bits := globalRecoveryRequest
  io.recoveryAborted.valid := globalRecoveryAbortComplete
  io.recoveryAborted.bits := globalRecoveryRequest
  io.recoveryRejected := turetire.io.recoveryRejected
  io.pRecoveryRejected := prename.io.recoveryRejected
  io.tuRecoveryRejected := turename.io.recoveryRejected
  io.robRecoveryRejected := o3.io.robRecoveryRejected
  io.d3RecoveryRejected := o3.io.d3RecoveryRejected
  io.brobRecoveryRejected := o3.io.brobRecoveryRejected
  io.pcRecoveryRejected := o3.io.pcRecoveryRejected
  io.dispatchRecoveryRejected := dispatch.io.recoveryRejected
  io.fastRecoveryRejected := fast.io.recoveryRejected
  io.memoryOrderPrepareRejected := memoryOrder.io.prepareRejected
  io.memoryOrderRecoveryRejected := memoryOrder.io.recoveryRejected
  io.memoryOrderNext := memoryOrder.io.next
}
