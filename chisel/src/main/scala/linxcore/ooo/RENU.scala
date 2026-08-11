package linxcore.ooo

import chisel3._
import chisel3.util.{PopCount, PriorityEncoder, log2Ceil}
import linxcore.params.CoreParams
import linxcore.top.interface._

/** Rename coordinator for D2-to-D3.
  *
  * The coordinator owns only the retained provisional D3 row and recovery
  * handshake. P and T/U state live exclusively in their sibling owner modules.
  * Both owners prepare from the same D2 prefix, then both publish from the
  * exact retained D3 row on the common toD3 fire.
  */
class RENU(val p: CoreParams) extends Module {
  require(p.ooo.d3PrefixWidth == p.widths.decodeWidth)
  require(p.ooo.renameWidth >= p.widths.decodeWidth)

  val io = IO(new RENUD2D3IO(p))

  private val width = p.widths.decodeWidth
  private val stidIndexWidth = p.ooo.stidWidth

  private def safeStid(value: UInt): UInt =
    if (p.ooo.stidCount == 1) 0.U else value(p.ooo.stidWidth - 1, 0)

  val pRename = Module(new PRename(p))
  val tuRename = Module(new TURename(p))

  pRename.io.prepare.valid := io.fromD2.valid
  pRename.io.prepare.bits := io.fromD2.bits
  tuRename.io.prepare.valid := io.fromD2.valid
  tuRename.io.prepare.bits := io.fromD2.bits
  pRename.io.release.valid := io.release.valid
  pRename.io.release.bits := io.release.bits
  tuRename.io.release.valid := io.release.valid
  tuRename.io.release.bits := io.release.bits

  io.debugPMap := pRename.io.debugPMap
  io.debugTCount := tuRename.io.debugTCount
  io.debugUCount := tuRename.io.debugUCount

  val pendingValid = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(false.B)))
  val pending = Reg(Vec(p.ooo.stidCount, new D3RenameGroup(p)))
  for (stid <- 0 until p.ooo.stidCount) {
    io.reservedGroupCount(stid) := Mux(
      pendingValid(stid), pending(stid).groupCount, 0.U)
  }
  val heldGrantValid = RegInit(false.B)
  val heldGrantStid = RegInit(0.U(stidIndexWidth.W))

  val recoveryPending = RegInit(false.B)
  val preparedValid = RegInit(false.B)
  val recoveryPlan = RegInit(0.U.asTypeOf(new RecoveryPlan(p)))

  private def samePlan(candidate: RecoveryPlan): Bool =
    RecoveryPlanContract.sameTransactionIgnoringPhase(candidate, recoveryPlan)

  val applyHit = recoveryPending && io.recovery.apply.valid &&
    samePlan(io.recovery.apply.bits) &&
    io.recovery.apply.bits.phase === RecoveryPhase.Apply
  val abortHit = recoveryPending && io.recovery.abort.valid &&
    samePlan(io.recovery.abort.bits) &&
    io.recovery.abort.bits.phase === RecoveryPhase.Abort
  val rawAnyPending = pendingValid.asUInt.orR
  val rawNextPendingStid = PriorityEncoder(pendingValid.asUInt)
  val rawPresentedStid = if (p.ooo.stidCount == 1) {
    0.U(stidIndexWidth.W)
  } else {
    Mux(heldGrantValid, heldGrantStid, rawNextPendingStid)
  }
  val prepareTargetStid = safeStid(io.recovery.prepare.bits.trigger.stid)
  val prepareConflictsWithIrrevocable = rawAnyPending &&
    rawPresentedStid === prepareTargetStid &&
    heldGrantValid

  io.recovery.prepare.ready := !recoveryPending &&
    io.recovery.prepare.bits.phase === RecoveryPhase.Prepare &&
    io.recovery.prepare.bits.trigger.stid < p.ooo.stidCount.U &&
    (!io.recovery.prepare.bits.survivingTailValid ||
      io.recovery.prepare.bits.survivingTail.stid ===
        io.recovery.prepare.bits.trigger.stid) &&
    !prepareConflictsWithIrrevocable
  val recoveryMutationFence = recoveryPending || io.recovery.prepare.fire ||
    applyHit || abortHit
  val releasePrefixShape = (0 until p.widths.retireWidth).map { lane =>
    io.release.bits.lanes(lane).valid === (lane.U < io.release.bits.count)
  }
  val releaseShapeExact = io.release.bits.count <= p.widths.retireWidth.U &&
    releasePrefixShape.reduce(_ && _)
  val releaseReady = io.release.valid && releaseShapeExact &&
    !recoveryMutationFence && pRename.io.releaseExact &&
    tuRename.io.releaseExact
  io.releaseReady := releaseReady
  val releaseApply = releaseReady && io.releaseApply
  pRename.io.releaseApply := releaseApply
  tuRename.io.releaseApply := releaseApply
  io.recovery.prepared.valid := preparedValid
  io.recovery.prepared.bits := recoveryPlan
  when(io.recovery.prepare.fire) {
    recoveryPending := true.B
    preparedValid := true.B
    recoveryPlan := io.recovery.prepare.bits
  }.elsewhen(applyHit || abortHit) {
    recoveryPending := false.B
    preparedValid := false.B
  }.elsewhen(io.recovery.prepared.fire) {
    preparedValid := false.B
  }

  pRename.io.recoveryApply.valid := applyHit
  pRename.io.recoveryApply.bits := io.recovery.apply.bits
  tuRename.io.recoveryApply.valid := applyHit
  tuRename.io.recoveryApply.bits := io.recovery.apply.bits

  val prepared = Wire(new D3RenameGroup(p))
  prepared := 0.U.asTypeOf(prepared)
  prepared.count := io.fromD2.bits.count
  prepared.groupCount := io.fromD2.bits.groupCount
  for (group <- 0 until width) {
    prepared.groups(group) := io.fromD2.bits.groups(group)
  }
  for (lane <- 0 until width) {
    prepared.entries(lane).uop.decoded := io.fromD2.bits.entries(lane).uop
    prepared.entries(lane).trap := io.fromD2.bits.entries(lane).trap
    prepared.entries(lane).residentBound :=
      io.fromD2.bits.entries(lane).residentBound
    prepared.entries(lane).brobBound := io.fromD2.bits.entries(lane).brobBound
    prepared.entries(lane).blockStart :=
      io.fromD2.bits.entries(lane).uop.blockStart
    prepared.entries(lane).blockStop :=
      io.fromD2.bits.entries(lane).uop.blockStop
    val fastResultProducer =
      io.fromD2.bits.entries(lane).uop.classification.disposition ===
        OooOpcodeDisposition.FastResolve.U &&
      (io.fromD2.bits.entries(lane).uop.classification.fastResolveClass ===
        OooFastResolveClass.ImmediateProducer.U ||
        io.fromD2.bits.entries(lane).uop.classification.fastResolveClass ===
          OooFastResolveClass.ControlValueProducer.U)
    prepared.entries(lane).earlyRobComplete :=
      io.fromD2.bits.entries(lane).uop.earlyComplete &&
        !fastResultProducer
    prepared.entries(lane).tSeqBefore :=
      tuRename.io.prepared.entries(lane).tSeqBefore
    prepared.entries(lane).uSeqBefore :=
      tuRename.io.prepared.entries(lane).uSeqBefore
    for (source <- 0 until p.maxSourceOperands) {
      val base = prepared.entries(lane).uop.sources(source)
      val pSrc = pRename.io.prepared.entries(lane).uop.sources(source)
      val tuSrc = tuRename.io.prepared.entries(lane).uop.sources(source)
      base.valid := io.fromD2.bits.entries(lane).uop.sources(source).valid
      base.kind := io.fromD2.bits.entries(lane).uop.sources(source).kind
      base.atag := io.fromD2.bits.entries(lane).uop.sources(source).atag
      when(pSrc.ptagValid) {
        base.ptag := pSrc.ptag
        base.pGeneration := pSrc.pGeneration
        base.ptagValid := true.B
        base.ready := pSrc.ready
      }.elsewhen(tuSrc.ttagValid) {
        base.ttag := tuSrc.ttag
        base.tGeneration := tuSrc.tGeneration
        base.tSeqIndex := tuSrc.tSeqIndex
        base.tSeqGeneration := tuSrc.tSeqGeneration
        base.localEpoch := tuSrc.localEpoch
        base.ttagValid := true.B
        base.ready := tuSrc.ready
      }.elsewhen(tuSrc.utagValid) {
        base.utag := tuSrc.utag
        base.uGeneration := tuSrc.uGeneration
        base.uSeqIndex := tuSrc.uSeqIndex
        base.uSeqGeneration := tuSrc.uSeqGeneration
        base.localEpoch := tuSrc.localEpoch
        base.utagValid := true.B
        base.ready := tuSrc.ready
      }.otherwise {
        base.ready := true.B
      }
    }
    for (dest <- 0 until p.maxDestinationOperands) {
      val base = prepared.entries(lane).uop.destinations(dest)
      val pDest = pRename.io.prepared.entries(lane).uop.destinations(dest)
      val tuDest = tuRename.io.prepared.entries(lane).uop.destinations(dest)
      val pHist = pRename.io.prepared.entries(lane).history(dest)
      val tuHist = tuRename.io.prepared.entries(lane).history(dest)
      base.valid := io.fromD2.bits.entries(lane).uop.destinations(dest).valid
      base.kind := io.fromD2.bits.entries(lane).uop.destinations(dest).kind
      base.atag := io.fromD2.bits.entries(lane).uop.destinations(dest).atag
      when(pDest.ptagValid) {
        base.ptag := pDest.ptag
        base.previousPtag := pDest.previousPtag
        base.pGeneration := pDest.pGeneration
        base.previousPGeneration := pDest.previousPGeneration
        base.previousPtagValid := pDest.previousPtagValid
        base.ptagValid := true.B
        prepared.entries(lane).history(dest) := pHist
      }.elsewhen(tuDest.ttagValid) {
        base.ttag := tuDest.ttag
        base.tGeneration := tuDest.tGeneration
        base.tSeqIndex := tuDest.tSeqIndex
        base.tSeqGeneration := tuDest.tSeqGeneration
        base.ttagValid := true.B
        prepared.entries(lane).history(dest) := tuHist
      }.elsewhen(tuDest.utagValid) {
        base.utag := tuDest.utag
        base.uGeneration := tuDest.uGeneration
        base.uSeqIndex := tuDest.uSeqIndex
        base.uSeqGeneration := tuDest.uSeqGeneration
        base.utagValid := true.B
        prepared.entries(lane).history(dest) := tuHist
      }
    }
  }

  val prepareFenceValid = io.recovery.prepare.fire
  val targetFenceValid = recoveryPending || prepareFenceValid
  val targetFenceStid = Mux(recoveryPending,
    safeStid(recoveryPlan.trigger.stid),
    safeStid(io.recovery.prepare.bits.trigger.stid))
  val eligiblePending = Wire(Vec(p.ooo.stidCount, Bool()))
  for (stid <- 0 until p.ooo.stidCount) {
    eligiblePending(stid) := pendingValid(stid) &&
      !(targetFenceValid && targetFenceStid === stid.U)
  }
  val anyEligiblePending = eligiblePending.asUInt.orR
  val nextPendingStid = PriorityEncoder(eligiblePending.asUInt)
  val heldGrantEligible = if (p.ooo.stidCount == 1) {
    heldGrantValid && eligiblePending(0)
  } else {
    heldGrantValid && eligiblePending(heldGrantStid)
  }
  val selectedPendingStid = if (p.ooo.stidCount == 1) {
    0.U(stidIndexWidth.W)
  } else {
    Mux(heldGrantEligible, heldGrantStid, nextPendingStid)
  }
  val selectedPending = if (p.ooo.stidCount == 1) {
    pending(0)
  } else {
    pending(selectedPendingStid)
  }

  val incomingStid = safeStid(
    io.fromD2.bits.entries(0).uop.instruction.parent.identity.stid)
  val incomingPending = if (p.ooo.stidCount == 1) {
    pendingValid(0)
  } else {
    pendingValid(incomingStid)
  }
  val incomingFenced = targetFenceValid && incomingStid === targetFenceStid
  val recoveryTargetStid = safeStid(recoveryPlan.trigger.stid)
  val d2LaneShape = (0 until width).map { lane =>
    val active = lane.U < io.fromD2.bits.count
    val laneUop = io.fromD2.bits.entries(lane).uop
    laneUop.valid === active &&
      (!active || (laneUop.instruction.parent.identity.stid ===
        io.fromD2.bits.entries(0).uop.instruction.parent.identity.stid &&
        laneUop.rob.stid ===
          io.fromD2.bits.entries(0).uop.instruction.parent.identity.stid))
  }
  val d2ShapeExact = io.fromD2.bits.count.orR &&
    io.fromD2.bits.count <= width.U &&
    io.fromD2.bits.groupCount.orR &&
    io.fromD2.bits.groupCount <= io.fromD2.bits.count &&
    d2LaneShape.reduce(_ && _)
  io.candidate.valid := anyEligiblePending
  io.candidate.bits := selectedPending

  val acceptedCount = io.prefixLimit.bits.count
  val acceptedGroupCount = io.prefixLimit.bits.groupCount
  val acceptedLaneGroupMatch = Wire(Vec(width, Vec(width, Bool())))
  for (lane <- 0 until width; group <- 0 until width) {
    val row = selectedPending.entries(lane).uop.decoded.rob
    val intent = selectedPending.groups(group)
    acceptedLaneGroupMatch(lane)(group) :=
      lane.U < selectedPending.count && group.U < acceptedGroupCount &&
      intent.valid && intent.peId === row.peId && intent.stid === row.stid &&
      intent.ridSlot === row.ridSlot &&
      intent.ridGeneration === row.ridGeneration
  }
  val acceptedPrefixShape = (0 until width).map { lane =>
    val accepted = lane.U < acceptedCount
    val matches = PopCount(acceptedLaneGroupMatch(lane))
    Mux(accepted, matches === 1.U, matches === 0.U)
  }.reduce(_ && _) && (0 until width).map { group =>
    val accepted = group.U < acceptedGroupCount
    val seen = (0 until width).map(lane =>
      acceptedLaneGroupMatch(lane)(group)).reduce(_ || _)
    Mux(accepted, seen, !seen)
  }.reduce(_ && _)
  val prefixLimitExact = io.prefixLimit.valid && acceptedCount.orR &&
    acceptedCount <= selectedPending.count && acceptedGroupCount.orR &&
    acceptedGroupCount <= selectedPending.groupCount &&
    acceptedGroupCount <= acceptedCount && acceptedPrefixShape

  val selectedPrefix = Wire(new D3RenameGroup(p))
  selectedPrefix := 0.U.asTypeOf(selectedPrefix)
  selectedPrefix.count := acceptedCount
  selectedPrefix.groupCount := acceptedGroupCount
  for (group <- 0 until width) {
    when(group.U < acceptedGroupCount) {
      selectedPrefix.groups(group) := selectedPending.groups(group)
    }
  }
  for (lane <- 0 until width) {
    when(lane.U < acceptedCount) {
      selectedPrefix.entries(lane) := selectedPending.entries(lane)
    }
  }
  io.toD3.valid := anyEligiblePending && prefixLimitExact
  io.toD3.bits := selectedPrefix
  val drainsSelectedRow = io.toD3.fire &&
    acceptedCount === selectedPending.count &&
    acceptedGroupCount === selectedPending.groupCount
  val replacesSelectedStid = incomingPending && drainsSelectedRow &&
    selectedPendingStid === incomingStid
  io.fromD2.ready := !incomingFenced &&
    (!incomingPending || replacesSelectedStid) && d2ShapeExact &&
    pRename.io.prepareReady && tuRename.io.prepareReady

  when(heldGrantValid && !heldGrantEligible) {
    heldGrantValid := false.B
  }.elsewhen(io.toD3.fire) {
    heldGrantValid := false.B
  }.elsewhen(!heldGrantValid && io.toD3.valid && !io.toD3.ready) {
    heldGrantValid := true.B
    heldGrantStid := nextPendingStid
  }

  val compactedSuffix = Wire(new D3RenameGroup(p))
  compactedSuffix := 0.U.asTypeOf(compactedSuffix)
  compactedSuffix.count := selectedPending.count - acceptedCount
  compactedSuffix.groupCount :=
    selectedPending.groupCount - acceptedGroupCount
  for (group <- 0 until width) {
    for (source <- 0 until width) {
      when(source.U === group.U + acceptedGroupCount &&
          source.U < selectedPending.groupCount) {
        compactedSuffix.groups(group) := selectedPending.groups(source)
      }
    }
  }
  for (lane <- 0 until width) {
    for (source <- 0 until width) {
      when(source.U === lane.U + acceptedCount &&
          source.U < selectedPending.count) {
        compactedSuffix.entries(lane) := selectedPending.entries(source)
      }
    }
  }
  when(io.toD3.fire) {
    if (p.ooo.stidCount == 1) {
      pending(0) := compactedSuffix
      pendingValid(0) := compactedSuffix.count.orR
    } else {
      pending(selectedPendingStid) := compactedSuffix
      pendingValid(selectedPendingStid) := compactedSuffix.count.orR
    }
  }
  when(io.fromD2.fire) {
    if (p.ooo.stidCount == 1) {
      pending(0) := prepared
      pendingValid(0) := true.B
    } else {
      pending(incomingStid) := prepared
      pendingValid(incomingStid) := true.B
    }
  }
  when(applyHit) {
    if (p.ooo.stidCount == 1) {
      pendingValid(0) := false.B
    } else {
      pendingValid(recoveryTargetStid) := false.B
    }
  }

  val publication = Wire(new D3RenameGroup(p))
  publication := selectedPrefix
  when(io.toD3.valid && io.publicationIdentity.valid) {
    assert(io.publicationIdentity.bits.count === acceptedCount,
      "RENU publication identities must cover the exact retained D3 prefix")
    for (lane <- 0 until width) {
      when(lane.U < acceptedCount) {
        assert(io.publicationIdentity.bits.entries(lane).valid)
        publication.entries(lane).uop.decoded.rob :=
          io.publicationIdentity.bits.entries(lane).rob
      }
    }
  }
  pRename.io.publish.valid := io.toD3.fire
  pRename.io.publish.bits := publication
  pRename.io.reserve.valid := io.fromD2.fire
  pRename.io.reserve.bits := prepared
  pRename.io.cancel.valid := applyHit
  pRename.io.cancel.bits := recoveryTargetStid
  tuRename.io.publish.valid := io.toD3.fire
  tuRename.io.publish.bits := publication

  when(io.fromD2.fire) {
    when(incomingPending) {
      assert(replacesSelectedStid,
        "same-STID D2 replacement requires the final common D3 publication")
    }
    assert(io.fromD2.bits.count.orR)
    assert(io.fromD2.bits.count <= width.U)
  }
}
