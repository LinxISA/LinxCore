package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, Enum, Valid, is, switch}

class OooRobBrobPcCoordinatorIO(val p: OooParams = OooParams()) extends Bundle {
  val reserve = Flipped(Decoupled(new OooD2GroupedTransaction(p)))
  val cancel = Input(Vec(p.stidCount, Bool()))
  val publishEligible = Input(Vec(p.stidCount, Bool()))

  // Later RENU/dispatch owners inspect this immutable D3 view and assert the
  // permit only when their own reservations can join the same publication.
  val preparedValid = Output(Bool())
  val prepared = Output(new OooO3PreparedPublication(p))
  val publishPermit = Input(Bool())
  val publishFire = Output(Bool())

  val completion = Flipped(Decoupled(new OooRobMemberCompletion(p)))
  val nonFlushEvidence = Flipped(Decoupled(new OooRobNonFlushEvidence(p)))
  val interruptPending = Input(Vec(p.stidCount, Bool()))
  val nonFlushWindows = Output(Vec(p.stidCount, new NonFlushWindow(p)))
  val commit = Decoupled(new OooRobCommitBatch(p))

  // Retained physical-owner recovery subtransaction.  A future global R0-R4
  // coordinator joins this prepared view with rename, dispatch, IEX, fast,
  // frontend-stage, and CTU owners before asserting `recoveryApply`.
  val recoveryRequest = Flipped(Decoupled(new OooGlobalRecoveryRequest(p)))
  val recoveryBusy = Output(Bool())
  val recoveryPreparedValid = Output(Bool())
  val recoveryPrepared = Output(new OooRobRecoveryPlan(p))
  val recoveryApply = Input(Bool())
  val recoveryAbort = Input(Bool())
  val recoveryApplied = Valid(new OooGlobalRecoveryRequest(p))

  val pcReadTokens = Input(Vec(p.pcReadPorts, new PcBufferToken(p)))
  val pcReadValid = Output(Vec(p.pcReadPorts, Bool()))
  val pcRead = Output(Vec(p.pcReadPorts, UInt(p.pcWidth.W)))

  val d3StaleRejected = Valid(new OooD3StalePlanReject(p))
  val d3PlanStale = Output(Bool())
  val d3ReleaseRejected = Valid(new OooD3ReleaseReject(p))
  val robPublicationRejected = Valid(new OooS1PublicationReject(p))
  val completionRejected = Valid(new OooRobMemberCompletionReject(p))
  val nonFlushEvidenceRejected = Valid(new OooRobNonFlushEvidenceReject(p))
  val brobPrepareRejected = Valid(new OooBrobPrepareReject(p))
  val brobCommitRejected = Valid(new OooBrobCommitReject(p))
  val pcPrepareRejected = Valid(new OooPcPrepareReject(p))
  val pcCommitRejected = Valid(new OooPcCommitReject(p))
  val robRecoveryRejected = Valid(new OooRobRecoveryReject(p))
  val d3RecoveryRejected = Valid(new OooD3RecoveryReject(p))
  val brobRecoveryRejected = Valid(new OooBrobRecoveryReject(p))
  val pcRecoveryRejected = Valid(new OooPcRecoveryReject(p))

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
  val robOccupiedGroups = Output(Vec(p.stidCount,
    UInt(p.countWidth(p.robGroupsPerStid).W)))
  val brobUsedBlocks = Output(Vec(p.stidCount, UInt(p.brobCountWidth.W)))
  val pcUsedBases = Output(Vec(p.stidCount, UInt(p.pcPartitionCountWidth.W)))
}

/** Atomic O3 coordinator for D3 allocation, grouped ROB, native BROB, and PC.
  *
  * BROB and PC perform side-effect-free preparation against the retained D3
  * reservation. The grouped ROB validates the fully bound publication. None
  * of the four owners mutate until `publishPermit` joins all ready views into
  * one `publishFire`. Commit follows the same rule: the retained ROB batch is
  * externally visible only when D3 release, BROB retirement, and PC retirement
  * all accept the exact batch, so every owner fires in the same cycle.
  */
class OooRobBrobPcCoordinator(val p: OooParams = OooParams()) extends Module {
  val io = IO(new OooRobBrobPcCoordinatorIO(p))

  val d3 = Module(new OooD3ReservationAllocator(p))
  val rob = Module(new OooS1GroupedRob(p))
  val brob = Module(new OooProductionBrob(p))
  val pc = Module(new OooProductionPcBuffer(p))

  val recoveryIdle :: recoveryPreparing :: recoveryPrepared :: Nil = Enum(3)
  val recoveryState = RegInit(recoveryIdle)
  val recoveryRequestReg = RegInit(
    0.U.asTypeOf(new OooGlobalRecoveryRequest(p)))
  val recoveryPlanReg = RegInit(0.U.asTypeOf(new OooRobRecoveryPlan(p)))

  d3.io.in <> io.reserve
  d3.io.cancel := io.cancel
  val rawCommitStid = rob.io.commit.bits.release.firstGroup.stid
  val rawCommitStidInRange = rawCommitStid < p.stidCount.U
  for (stid <- 0 until p.stidCount) {
    // An older raw ROB head gets same-STID priority before D3 exposes a new
    // grant. Different STIDs remain independently eligible.
    d3.io.publishEligible(stid) := io.publishEligible(stid) &&
      !(rob.io.commit.valid && rawCommitStidInRange && rawCommitStid === stid.U)
  }

  brob.io.prepare.valid := d3.io.out.valid
  brob.io.prepare.bits := d3.io.out.bits
  pc.io.prepare.valid := d3.io.out.valid
  pc.io.prepare.bits := d3.io.out.bits

  val residentGeneration = RegInit(VecInit(Seq.fill(p.stidCount)(
    VecInit(Seq.fill(p.robGroupsPerStid)(0.U(p.residentGenerationWidth.W))))))
  val publishRequest = Wire(new OooS1GroupedPublicationRequest(p))
  publishRequest := 0.U.asTypeOf(publishRequest)
  publishRequest.reservation := d3.io.out.bits
  for (groupIndex <- 0 until p.instructionDecodeWidth) {
    val group = d3.io.out.bits.transaction.groups(groupIndex)
    val binding = publishRequest.bindings(groupIndex)
    val stid = d3.io.out.bits.transaction.plan.stid
    binding.valid := group.valid
    binding.brob := brob.io.prepared.pointers(groupIndex)
    binding.brobAllocated := brob.io.prepared.newBlockMask(groupIndex)
    binding.brobImplicitCloseValid :=
      brob.io.prepared.implicitCloseMask(groupIndex)
    binding.brobImplicitClose :=
      brob.io.prepared.implicitClosePointers(groupIndex)
    binding.pcBase := pc.io.prepared.groupTokens(groupIndex)
    binding.pcBaseAllocated := pc.io.prepared.newBaseMask(groupIndex)
    binding.pcImplicitCloseValid :=
      pc.io.prepared.implicitCloseMask(groupIndex)
    binding.pcImplicitClose :=
      pc.io.prepared.implicitCloseTokens(groupIndex)
    binding.residentGeneration :=
      residentGeneration(stid)(group.key.ridSlot) + 1.U
    binding.initiallyCompletedMembers := 0.U
  }

  rob.io.publish.bits := publishRequest
  val ownerPrepareReady = brob.io.prepareReady && pc.io.prepareReady &&
    rob.io.publish.ready
  io.preparedValid := d3.io.out.valid && ownerPrepareReady
  io.prepared.request := publishRequest
  io.prepared.parentPcTokens := pc.io.prepared.parentTokens
  io.prepared.brobImplicitCloseMask := brob.io.prepared.implicitCloseMask
  io.prepared.brobImplicitClosePointers :=
    brob.io.prepared.implicitClosePointers

  rob.io.publish.valid := d3.io.out.valid && brob.io.prepareReady &&
    pc.io.prepareReady && io.publishPermit
  d3.io.out.ready := ownerPrepareReady && io.publishPermit
  val sharedPublishFire = d3.io.out.valid && d3.io.out.ready
  brob.io.publishFire := sharedPublishFire
  pc.io.publishFire := sharedPublishFire
  io.publishFire := sharedPublishFire

  when(sharedPublishFire) {
    assert(rob.io.publish.fire,
      "grouped ROB must publish on the shared ROB/BROB/PC fire")
    for (groupIndex <- 0 until p.instructionDecodeWidth) {
      val group = d3.io.out.bits.transaction.groups(groupIndex)
      when(group.valid) {
        residentGeneration(group.key.stid)(group.key.ridSlot) :=
          publishRequest.bindings(groupIndex).residentGeneration
      }
    }
  }

  rob.io.completion <> io.completion
  rob.io.nonFlushEvidence <> io.nonFlushEvidence
  rob.io.interruptPending := io.interruptPending
  io.nonFlushWindows := rob.io.nonFlushWindows
  val incomingRecoveryStid =
    io.recoveryRequest.bits.rename.key.member.group.stid
  val incomingRecoveryStidInRange = incomingRecoveryStid < p.stidCount.U
  val incomingRecoveryHitsExposedD3 = incomingRecoveryStidInRange &&
    d3.io.out.valid &&
    d3.io.out.bits.transaction.plan.stid === incomingRecoveryStid
  val incomingRecoveryHitsRetainedCommit = incomingRecoveryStidInRange &&
    rob.io.commit.valid && rawCommitStidInRange &&
    rawCommitStid === incomingRecoveryStid

  // A Decoupled producer cannot retract an exposed D3 publication, and an
  // already-retained architectural commit cannot be silently canceled.  Both
  // obligations drain before R0 capture. Hidden provisional reservations are
  // legal and are canceled by the later common recovery fire.
  io.recoveryRequest.ready := recoveryState === recoveryIdle &&
    !incomingRecoveryHitsExposedD3 &&
    !incomingRecoveryHitsRetainedCommit

  val recoveryActive = recoveryState =/= recoveryIdle
  rob.io.recoveryPrepare.valid := recoveryActive
  rob.io.recoveryPrepare.bits := recoveryRequestReg

  val liveRecoveryPlan = rob.io.recoveryPrepared
  val selectedRecoveryPlan = Wire(new OooRobRecoveryPlan(p))
  selectedRecoveryPlan := Mux(
    recoveryState === recoveryPrepared,
    recoveryPlanReg,
    liveRecoveryPlan)
  val downstreamRecoveryOffer = recoveryActive && Mux(
    recoveryState === recoveryPrepared,
    true.B,
    rob.io.recoveryPrepareReady)

  d3.io.recoveryPrepare.valid := downstreamRecoveryOffer
  d3.io.recoveryPrepare.bits := selectedRecoveryPlan
  brob.io.recoveryPrepare.valid := downstreamRecoveryOffer
  brob.io.recoveryPrepare.bits := selectedRecoveryPlan
  pc.io.recoveryPrepare.valid := downstreamRecoveryOffer
  pc.io.recoveryPrepare.bits := selectedRecoveryPlan

  val allRecoveryOwnersReady = rob.io.recoveryPrepareReady &&
    d3.io.recoveryPrepareReady && brob.io.recoveryPrepareReady &&
    pc.io.recoveryPrepareReady
  val recoveryPlanStable = liveRecoveryPlan.asUInt === recoveryPlanReg.asUInt
  io.recoveryPreparedValid := recoveryState === recoveryPrepared &&
    allRecoveryOwnersReady && recoveryPlanStable
  io.recoveryPrepared := recoveryPlanReg
  io.recoveryBusy := recoveryActive

  val recoveryApplyFire = io.recoveryApply && io.recoveryPreparedValid
  rob.io.recoveryFire := recoveryApplyFire
  d3.io.recoveryFire := recoveryApplyFire
  brob.io.recoveryFire := recoveryApplyFire
  pc.io.recoveryFire := recoveryApplyFire
  io.recoveryApplied.valid := recoveryApplyFire
  io.recoveryApplied.bits := recoveryRequestReg

  val anyRecoveryOwnerRejected = rob.io.recoveryRejected.valid ||
    d3.io.recoveryRejected.valid || brob.io.recoveryRejected.valid ||
    pc.io.recoveryRejected.valid

  switch(recoveryState) {
    is(recoveryIdle) {
      when(io.recoveryRequest.fire) {
        recoveryRequestReg := io.recoveryRequest.bits
        recoveryState := recoveryPreparing
      }
    }
    is(recoveryPreparing) {
      when(io.recoveryAbort || anyRecoveryOwnerRejected) {
        recoveryState := recoveryIdle
      }.elsewhen(allRecoveryOwnersReady) {
        recoveryPlanReg := liveRecoveryPlan
        recoveryState := recoveryPrepared
      }
    }
    is(recoveryPrepared) {
      when(io.recoveryAbort || anyRecoveryOwnerRejected) {
        recoveryState := recoveryIdle
      }.elsewhen(recoveryApplyFire) {
        recoveryState := recoveryIdle
      }
    }
  }

  when(io.recoveryApply) {
    assert(io.recoveryPreparedValid,
      "physical recovery apply requires one retained all-owner plan")
  }
  when(io.recoveryAbort) {
    assert(recoveryActive && !recoveryApplyFire,
      "physical recovery abort requires one unapplied retained transaction")
  }
  when(recoveryState === recoveryPrepared) {
    assert(recoveryPlanStable,
      "ROB recovery plan must remain stable through external apply backpressure")
  }
  when(recoveryApplyFire) {
    assert(rob.io.recoveryPrepareReady && d3.io.recoveryPrepareReady &&
      brob.io.recoveryPrepareReady && pc.io.recoveryPrepareReady,
      "ROB, D3, BROB, and PC must apply recovery atomically")
  }

  // The ROB is the retained commit source. All other owners see the same valid
  // batch while computing readiness; external visibility is gated until every
  // exact owner can fire, preventing partial architectural retirement.
  // Owner readiness is a side-effect-free exactness check. Their valids are
  // asserted only for the terminal external handshake, so no owner can retire
  // early while the consumer is applying backpressure.
  d3.io.release.bits := rob.io.commit.bits.release
  brob.io.commit.bits := rob.io.commit.bits
  pc.io.commit.bits := rob.io.commit.bits
  val allCommitReady = d3.io.release.ready && brob.io.commit.ready && pc.io.commit.ready
  io.commit.valid := rob.io.commit.valid && allCommitReady
  io.commit.bits := rob.io.commit.bits
  rob.io.commit.ready := io.commit.ready && allCommitReady
  val sharedCommitFire = io.commit.valid && io.commit.ready
  d3.io.release.valid := sharedCommitFire
  brob.io.commit.valid := sharedCommitFire
  pc.io.commit.valid := sharedCommitFire

  when(rob.io.commit.fire) {
    assert(d3.io.release.fire && brob.io.commit.fire && pc.io.commit.fire,
      "ROB, D3 release, BROB, and PC must commit atomically")
  }

  pc.io.readTokens := io.pcReadTokens
  io.pcReadValid := pc.io.readValid
  io.pcRead := pc.io.readPc

  io.d3StaleRejected := d3.io.staleRejected
  io.d3PlanStale := d3.io.planStale
  io.d3ReleaseRejected := d3.io.releaseRejected
  io.robPublicationRejected := rob.io.publicationRejected
  io.completionRejected := rob.io.completionRejected
  io.nonFlushEvidenceRejected := rob.io.nonFlushEvidenceRejected
  io.brobPrepareRejected := brob.io.prepareRejected
  io.brobCommitRejected := brob.io.commitRejected
  io.pcPrepareRejected := pc.io.prepareRejected
  io.pcCommitRejected := pc.io.commitRejected
  io.robRecoveryRejected := rob.io.recoveryRejected
  io.d3RecoveryRejected := d3.io.recoveryRejected
  io.brobRecoveryRejected := brob.io.recoveryRejected
  io.pcRecoveryRejected := pc.io.recoveryRejected

  io.d3UsedGroups := d3.io.usedGroups
  io.d3PublishedGroups := d3.io.publishedGroups
  io.d3TailSlot := d3.io.tailSlot
  io.d3TailGeneration := d3.io.tailGeneration
  io.d3TailEpoch := d3.io.tailEpoch
  io.d3NextTransactionId := d3.io.nextTransactionId
  io.robOccupiedGroups := rob.io.occupiedGroups
  io.brobUsedBlocks := brob.io.usedBlocks
  io.pcUsedBases := pc.io.usedBases
}
