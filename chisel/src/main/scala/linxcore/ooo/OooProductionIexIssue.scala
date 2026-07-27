package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, OHToUInt, PopCount, PriorityEncoderOH, Valid,
  log2Ceil}
import linxcore.common.{DestinationKind, OperandClass}

object OooIexIssueSlotState extends ChiselEnum {
  val Free, BoundS2, ResidentS3 = Value
}

class OooProductionIexIssueIO(val p: OooParams = OooParams()) extends Bundle {
  val s1 = Flipped(Decoupled(new OooIexS1Transaction(p)))
  val wakeup = Input(Vec(p.iexWakeupPorts, Valid(new OooIexWakeup(p))))

  val release = Flipped(Decoupled(new OooIexIssueRelease(p)))
  val dispatchRelease = Decoupled(new OooDispatchRelease(p))

  val query = Input(new OooIexSlotQuery(p))
  val queryState = Output(OooIexIssueSlotState())
  val queryRow = Output(new OooIexIssueRow(p))
  val queryPickable = Output(Bool())

  val s1Occupied = Output(Vec(p.stidCount, Bool()))
  val s2Bind = Valid(new OooIexS2BindAck(p))
  val s3Enable = Valid(new OooIexS3Enable(p))
  val boundEntries = Output(Vec(p.iqClassCount,
    Vec(p.iqBankCount, UInt(p.iqBankEntryCountWidth.W))))
  val residentEntries = Output(Vec(p.iqClassCount,
    Vec(p.iqBankCount, UInt(p.iqBankEntryCountWidth.W))))

  val s1Rejected = Valid(new OooIexS1Reject(p))
  val releaseRejected = Valid(new OooIexReleaseReject(p))
}

/** Production OOO-S1 to IEX-S3 residency owner.
  *
  * One retained S1 slot exists per STID.  A fair shared S2 writer consumes at
  * most one transaction per cycle, writes every exact dispatch child or none,
  * and leaves the rows in `BoundS2` for a complete cycle.  Only the following
  * S3 transition makes a row eligible for a future picker.  Source wakeups are
  * registered into the row, so a wakeup observed in cycle N cannot affect an
  * S3 eligibility decision in that same cycle.
  *
  * This module deliberately stops at pick-enable.  P1/I1/I2 arbitration and
  * inflight/cancel handling remain a later IEX owner.  The release seam models
  * only a future exact, non-cancellable I2 terminal event and joins physical
  * row removal to the existing dispatch-reservation release handshake.
  */
class OooProductionIexIssue(val p: OooParams = OooParams()) extends Module {
  val io = IO(new OooProductionIexIssueIO(p))
  private val ttagIndexWidth = log2Ceil(p.tPhysRegs)
  private val utagIndexWidth = log2Ceil(p.uPhysRegs)

  val slotState = RegInit(VecInit(Seq.fill(p.iqClassCount)(
    VecInit(Seq.fill(p.iqBankCount)(
      VecInit(Seq.fill(p.iqEntriesPerBank)(OooIexIssueSlotState.Free)))))))
  // S1 owns a pending physical-write claim before S2 installs the row.  Keep
  // this separate from slotState so queryState continues to describe physical
  // IQ residency, while a second STID still cannot claim the same target.
  val s1Claimed = RegInit(VecInit(Seq.fill(p.iqClassCount)(
    VecInit(Seq.fill(p.iqBankCount)(
      VecInit(Seq.fill(p.iqEntriesPerBank)(false.B)))))))
  val rows = RegInit(VecInit(Seq.fill(p.iqClassCount)(
    VecInit(Seq.fill(p.iqBankCount)(
      VecInit(Seq.fill(p.iqEntriesPerBank)(
        0.U.asTypeOf(new OooIexIssueRow(p)))))))))

  val s1Valid = RegInit(VecInit(Seq.fill(p.stidCount)(false.B)))
  val s1Rows = Reg(Vec(p.stidCount, new OooIexS1Transaction(p)))
  val s2RoundRobin = RegInit(0.U(p.stidWidth.W))

  // A wakeup must remain visible to consumers dispatched after that producer
  // completed.  These generation-qualified scoreboards complement per-row
  // ready bits; without them, a consumer arriving after the one-cycle wakeup
  // pulse could remain blocked forever.
  val pReadyValid = RegInit(VecInit(Seq.fill(p.pPhysRegs)(false.B)))
  val pReadyGeneration = Reg(Vec(p.pPhysRegs,
    UInt(p.pTagGenerationWidth.W)))
  val pReadyStid = Reg(Vec(p.pPhysRegs, UInt(p.stidWidth.W)))
  val pReadyEpoch = Reg(Vec(p.pPhysRegs, UInt(p.epochWidth.W)))
  val tReadyValid = RegInit(VecInit(Seq.fill(p.stidCount)(
    VecInit(Seq.fill(p.tPhysRegs)(false.B)))))
  val tReadySequence = Reg(Vec(p.stidCount,
    Vec(p.tPhysRegs, new OooLocalSeq(p))))
  val tReadyEpoch = Reg(Vec(p.stidCount,
    Vec(p.tPhysRegs, UInt(p.epochWidth.W))))
  val uReadyValid = RegInit(VecInit(Seq.fill(p.stidCount)(
    VecInit(Seq.fill(p.uPhysRegs)(false.B)))))
  val uReadySequence = Reg(Vec(p.stidCount,
    Vec(p.uPhysRegs, new OooLocalSeq(p))))
  val uReadyEpoch = Reg(Vec(p.stidCount,
    Vec(p.uPhysRegs, UInt(p.epochWidth.W))))

  def sameMember(left: RobMemberKey, right: RobMemberKey): Bool =
    left.asUInt === right.asUInt

  val request = io.s1.bits
  val transaction = request.o3.request.reservation.transaction
  val plan = transaction.plan
  val decoded = transaction.decoded
  val requestStid = plan.stid
  val requestStidInRange = requestStid < p.stidCount.U
  val safeRequestStid = Mux(requestStidInRange, requestStid, 0.U)

  val identityExact = request.pRename.valid && request.tuRename.valid &&
    request.dispatch.valid && request.pRename.peId === plan.peId &&
    request.tuRename.peId === plan.peId && request.dispatch.peId === plan.peId &&
    request.pRename.stid === plan.stid && request.tuRename.stid === plan.stid &&
    request.dispatch.stid === plan.stid &&
    request.pRename.epoch === plan.epoch && request.tuRename.epoch === plan.epoch &&
    request.dispatch.epoch === plan.epoch &&
    request.pRename.transactionId === plan.transactionId &&
    request.tuRename.transactionId === plan.transactionId &&
    request.dispatch.transactionId === plan.transactionId &&
    decoded.peId === plan.peId && decoded.stid === plan.stid &&
    decoded.epoch === plan.epoch &&
    request.pRename.uopMask === decoded.uopMask &&
    request.tuRename.uopMask === decoded.uopMask

  val allocationShapeExact = (0 until p.dispatchWidth).map { lane =>
    val allocation = request.dispatch.allocations(lane)
    request.dispatch.allocationMask(lane) === allocation.valid &&
      (!allocation.valid || allocation.reservation.valid)
  }.reduce(_ && _)
  val allocationDense = (1 until p.dispatchWidth).map { lane =>
    !request.dispatch.allocationMask(lane) ||
      request.dispatch.allocationMask(lane - 1)
  }.foldLeft(true.B)(_ && _)

  val laneExact = Wire(Vec(p.dispatchWidth, Bool()))
  val laneTargetFree = Wire(Vec(p.dispatchWidth, Bool()))
  for (lane <- 0 until p.dispatchWidth) {
    val allocation = request.dispatch.allocations(lane)
    val reservation = allocation.reservation
    val uopIndexInRange = allocation.uopIndex < p.decodedUopWidth.U
    val safeUopIndex = Mux(uopIndexInRange, allocation.uopIndex, 0.U)
    val decodedUop = decoded.uops(safeUopIndex)
    val pUop = request.pRename.uops(safeUopIndex)
    val tuUop = request.tuRename.uops(safeUopIndex)
    val activeUop = decoded.uopMask(safeUopIndex) &&
      decodedUop.valid && pUop.valid && tuUop.valid
    val memberEnd = pUop.member.memberIndex +&
      allocation.childIndex
    val classIndex = reservation.uopClass.asUInt
    val classInRange = classIndex < p.iqClassCount.U
    val bankInRange = reservation.bank < p.iqBankCount.U
    val entryInRange = reservation.speculativeSlot < p.iqEntriesPerBank.U
    val portInRange = reservation.writePort < p.iqWritePortsPerBank.U
    val safeClass = Mux(classInRange, classIndex, 0.U)
    val safeBank = Mux(bankInRange, reservation.bank, 0.U)
    val safeEntry = Mux(entryInRange, reservation.speculativeSlot, 0.U)
    laneExact(lane) := !allocation.valid || (uopIndexInRange && activeUop &&
      classInRange && bankInRange && entryInRange && portInRange &&
      pUop.decoded.asUInt === decodedUop.asUInt &&
      sameMember(pUop.member, tuUop.member) &&
      allocation.childIndex < decodedUop.plannedChildCount &&
      memberEnd < p.maxOrdinaryUopsPerGroup.U)
    laneTargetFree(lane) := !allocation.valid ||
      (slotState(safeClass)(safeBank)(safeEntry) ===
        OooIexIssueSlotState.Free &&
        !s1Claimed(safeClass)(safeBank)(safeEntry))
  }

  val noDuplicateTargets = (0 until p.dispatchWidth).flatMap { left =>
    (left + 1 until p.dispatchWidth).map { right =>
      val a = request.dispatch.allocations(left)
      val b = request.dispatch.allocations(right)
      !a.valid || !b.valid || a.reservation.uopClass =/= b.reservation.uopClass ||
        a.reservation.bank =/= b.reservation.bank ||
        a.reservation.speculativeSlot =/= b.reservation.speculativeSlot
    }
  }.foldLeft(true.B)(_ && _)

  val s1ShapeExact = requestStidInRange && identityExact &&
    allocationShapeExact && allocationDense && laneExact.reduce(_ && _) &&
    noDuplicateTargets
  val s1TargetsExact = laneTargetFree.reduce(_ && _)
  io.s1.ready := !s1Valid(safeRequestStid) && s1ShapeExact && s1TargetsExact
  io.s1Rejected.valid := io.s1.valid && (!s1ShapeExact || !s1TargetsExact)
  io.s1Rejected.bits.peId := plan.peId
  io.s1Rejected.bits.stid := plan.stid
  io.s1Rejected.bits.epoch := plan.epoch
  io.s1Rejected.bits.transactionId := plan.transactionId
  io.s1Rejected.bits.shapeExact := s1ShapeExact
  io.s1Rejected.bits.targetsExact := s1TargetsExact

  when(io.s1.fire) {
    s1Valid(safeRequestStid) := true.B
    s1Rows(safeRequestStid) := request
    for (lane <- 0 until p.dispatchWidth) {
      val allocation = request.dispatch.allocations(lane)
      val reservation = allocation.reservation
      when(allocation.valid) {
        s1Claimed(reservation.uopClass.asUInt)(reservation.bank)(
          reservation.speculativeSlot) := true.B
      }
    }
  }

  val s2OffsetCandidates = Wire(Vec(p.stidCount, Bool()))
  for (offset <- 0 until p.stidCount) {
    val candidate = (s2RoundRobin + offset.U)(p.stidWidth - 1, 0)
    s2OffsetCandidates(offset) := s1Valid(candidate)
  }
  val s2OffsetSelect = PriorityEncoderOH(s2OffsetCandidates.asUInt)
  val s2Valid = s2OffsetCandidates.asUInt.orR
  val s2Stid = Mux(s2Valid,
    (s2RoundRobin + OHToUInt(s2OffsetSelect))(p.stidWidth - 1, 0), 0.U)
  val s2Request = s1Rows(s2Stid)
  val s2Dispatch = s2Request.dispatch
  val safeS2OwnerStid = Mux(s2Dispatch.stid < p.stidCount.U,
    s2Dispatch.stid, 0.U)

  val s2Ack = Wire(new OooIexS2BindAck(p))
  s2Ack.peId := s2Dispatch.peId
  s2Ack.stid := s2Dispatch.stid
  s2Ack.epoch := s2Dispatch.epoch
  s2Ack.transactionId := s2Dispatch.transactionId
  s2Ack.allocationMask := s2Dispatch.allocationMask
  io.s2Bind.valid := s2Valid
  io.s2Bind.bits := s2Ack

  val s3PendingValid = RegInit(false.B)
  val s3Pending = Reg(new OooIexS3Enable(p))
  io.s3Enable.valid := s3PendingValid
  io.s3Enable.bits := s3Pending

  for (port <- 0 until p.iexWakeupPorts) {
    val wakeup = io.wakeup(port)
    val wakeStidInRange = wakeup.bits.stid < p.stidCount.U
    val safeWakeStid = Mux(wakeStidInRange, wakeup.bits.stid, 0.U)
    val ptagInRange = wakeup.bits.ptag < p.pPhysRegs.U
    val safePtag = Mux(ptagInRange, wakeup.bits.ptag, 0.U)
    val ttagInRange = wakeup.bits.localTag < p.tPhysRegs.U
    val safeTtag = Mux(ttagInRange, wakeup.bits.localTag, 0.U)(
      ttagIndexWidth - 1, 0)
    val utagInRange = wakeup.bits.localTag < p.uPhysRegs.U
    val safeUtag = Mux(utagInRange, wakeup.bits.localTag, 0.U)(
      utagIndexWidth - 1, 0)
    when(wakeup.valid && wakeup.bits.operandClass === OperandClass.P &&
        ptagInRange) {
      pReadyValid(safePtag) := true.B
      pReadyGeneration(safePtag) := wakeup.bits.ptagGeneration
      pReadyStid(safePtag) := wakeup.bits.stid
      pReadyEpoch(safePtag) := wakeup.bits.epoch
    }
    when(wakeup.valid && wakeup.bits.operandClass === OperandClass.T &&
        wakeStidInRange && ttagInRange &&
        wakeup.bits.localSequence.valid) {
      tReadyValid(safeWakeStid)(safeTtag) := true.B
      tReadySequence(safeWakeStid)(safeTtag) :=
        wakeup.bits.localSequence
      tReadyEpoch(safeWakeStid)(safeTtag) := wakeup.bits.epoch
    }
    when(wakeup.valid && wakeup.bits.operandClass === OperandClass.U &&
        wakeStidInRange && utagInRange &&
        wakeup.bits.localSequence.valid) {
      uReadyValid(safeWakeStid)(safeUtag) := true.B
      uReadySequence(safeWakeStid)(safeUtag) :=
        wakeup.bits.localSequence
      uReadyEpoch(safeWakeStid)(safeUtag) := wakeup.bits.epoch
    }
  }

  // Existing S2 rows advance before a new S2 bind writes its targets.  The
  // later assignment below keeps newly bound rows in S2 for one full cycle.
  for (uopClass <- 0 until p.iqClassCount;
       bank <- 0 until p.iqBankCount;
       entry <- 0 until p.iqEntriesPerBank) {
    when(slotState(uopClass)(bank)(entry) === OooIexIssueSlotState.BoundS2) {
      slotState(uopClass)(bank)(entry) := OooIexIssueSlotState.ResidentS3
    }
  }

  when(s2Valid) {
    s1Valid(s2Stid) := false.B
    s2RoundRobin := s2Stid + 1.U
    s3PendingValid := true.B
    s3Pending.bind := s2Ack

    for (lane <- 0 until p.dispatchWidth) {
      val allocation = s2Dispatch.allocations(lane)
      val reservation = allocation.reservation
      val uopIndex = allocation.uopIndex
      val uopClass = reservation.uopClass.asUInt
      val bank = reservation.bank
      val entry = reservation.speculativeSlot
      when(allocation.valid) {
        assert(slotState(uopClass)(bank)(entry) === OooIexIssueSlotState.Free,
          "a retained dispatch lease must bind one free physical IQ row")
        assert(s1Claimed(uopClass)(bank)(entry),
          "S2 may bind only the exact physical target claimed by retained S1")
        val pUop = s2Request.pRename.uops(uopIndex)
        val tuUop = s2Request.tuRename.uops(uopIndex)
        val row = Wire(new OooIexIssueRow(p))
        row := 0.U.asTypeOf(row)
        row.valid := true.B
        row.peId := s2Dispatch.peId
        row.stid := s2Dispatch.stid
        row.epoch := s2Dispatch.epoch
        row.transactionId := s2Dispatch.transactionId
        row.uopIndex := allocation.uopIndex
        row.childIndex := allocation.childIndex
        row.member := pUop.member
        row.member.memberIndex := pUop.member.memberIndex + allocation.childIndex
        row.reservation := reservation
        row.uopKey := pUop.decoded.identity.key
        row.parentCount := pUop.decoded.identity.parentCount
        row.parentPcTokens := s2Request.o3.parentPcTokens(uopIndex)
        row.primaryPrediction := 0.U.asTypeOf(row.primaryPrediction)
        for (parentIndex <- 0 until p.maxArchitecturalParentRefs) {
          val parent = pUop.decoded.identity.parents(parentIndex)
          when(parent.key.valid && parent.key.asUInt ===
              pUop.decoded.identity.key.primaryParent.asUInt) {
            row.primaryPrediction := parent.prediction
          }
        }
        row.boundary := pUop.decoded.identity.boundary
        row.templateValid := pUop.decoded.identity.templateValid
        row.templateGroupId := pUop.decoded.identity.templateGroupId
        row.templateGeneration := pUop.decoded.identity.templateGeneration
        row.opcode := pUop.decoded.opcode
        row.recipe := pUop.decoded.recipe
        row.plannedChildCount := pUop.decoded.plannedChildCount
        row.immediateValid := pUop.decoded.immediateValid
        row.immediate := pUop.decoded.immediate
        row.boundaryTargetValid := pUop.decoded.boundaryTargetValid
        row.boundaryTarget := pUop.decoded.boundaryTarget
        row.preciseTrap := pUop.decoded.preciseTrap
        row.trapCause := pUop.decoded.trapCause
        row.blockLast := tuUop.blockLast
        row.closeBeforeValid := tuUop.closeBeforeValid
        row.closeBefore := tuUop.closeBefore
        for (sourceIndex <- 0 until p.maxSourceOperands) {
          val decodedSource = pUop.decoded.sources(sourceIndex)
          val pSource = pUop.sources(sourceIndex).pMapping
          val localSource = tuUop.sources(sourceIndex)
          val source = row.sources(sourceIndex)
          val isP = decodedSource.operandClass === OperandClass.P
          val isT = decodedSource.operandClass === OperandClass.T
          val isU = decodedSource.operandClass === OperandClass.U
          val ptagInRange = pSource.ptag < p.pPhysRegs.U
          val safePtag = Mux(ptagInRange, pSource.ptag, 0.U)
          val ttagInRange = localSource.physicalTag < p.tPhysRegs.U
          val safeTtag = Mux(ttagInRange, localSource.physicalTag, 0.U)(
            ttagIndexWidth - 1, 0)
          val utagInRange = localSource.physicalTag < p.uPhysRegs.U
          val safeUtag = Mux(utagInRange, localSource.physicalTag, 0.U)(
            utagIndexWidth - 1, 0)
          val pReadyRecorded = ptagInRange && pReadyValid(safePtag) &&
            pReadyGeneration(safePtag) === pSource.ptagGeneration &&
            pReadyStid(safePtag) === s2Dispatch.stid &&
            pReadyEpoch(safePtag) === s2Dispatch.epoch
          val tReadyRecorded = ttagInRange &&
            tReadyValid(safeS2OwnerStid)(safeTtag) &&
            tReadySequence(safeS2OwnerStid)(safeTtag).asUInt ===
              localSource.sequence.asUInt &&
            tReadyEpoch(safeS2OwnerStid)(safeTtag) === s2Dispatch.epoch
          val uReadyRecorded = utagInRange &&
            uReadyValid(safeS2OwnerStid)(safeUtag) &&
            uReadySequence(safeS2OwnerStid)(safeUtag).asUInt ===
              localSource.sequence.asUInt &&
            uReadyEpoch(safeS2OwnerStid)(safeUtag) === s2Dispatch.epoch
          // The registered scoreboards above are pre-edge state.  Include an
          // exact current-cycle wakeup when constructing the S2 row so a
          // consumer bound on the same edge cannot miss the one-cycle pulse.
          // The result is still written into registered row state; neither
          // query nor future pick logic consumes wakeup combinationally.
          val pReadyNow = io.wakeup.map { wakeup =>
            wakeup.valid && wakeup.bits.stid === s2Dispatch.stid &&
              wakeup.bits.epoch === s2Dispatch.epoch &&
              wakeup.bits.operandClass === OperandClass.P &&
              wakeup.bits.ptag === pSource.ptag &&
              wakeup.bits.ptagGeneration === pSource.ptagGeneration
          }.reduce(_ || _)
          val tReadyNow = io.wakeup.map { wakeup =>
            wakeup.valid && wakeup.bits.stid === s2Dispatch.stid &&
              wakeup.bits.epoch === s2Dispatch.epoch &&
              wakeup.bits.operandClass === OperandClass.T &&
              wakeup.bits.localTag === localSource.physicalTag &&
              wakeup.bits.localSequence.asUInt === localSource.sequence.asUInt
          }.reduce(_ || _)
          val uReadyNow = io.wakeup.map { wakeup =>
            wakeup.valid && wakeup.bits.stid === s2Dispatch.stid &&
              wakeup.bits.epoch === s2Dispatch.epoch &&
              wakeup.bits.operandClass === OperandClass.U &&
              wakeup.bits.localTag === localSource.physicalTag &&
              wakeup.bits.localSequence.asUInt === localSource.sequence.asUInt
          }.reduce(_ || _)
          source.valid := decodedSource.valid
          source.ready := !decodedSource.valid ||
            (isP && (pSource.ready || pReadyRecorded || pReadyNow)) ||
            (isT && (tReadyRecorded || tReadyNow)) ||
            (isU && (uReadyRecorded || uReadyNow))
          source.operandClass := decodedSource.operandClass
          source.ptag := pSource.ptag
          source.ptagGeneration := pSource.ptagGeneration
          source.localTag := localSource.physicalTag
          source.localSequence := localSource.sequence
          when(decodedSource.valid && !(isP || isT || isU)) {
            source.ready := false.B
          }
        }
        for (destinationIndex <- 0 until p.maxDestinationOperands) {
          val decodedDestination =
            pUop.decoded.destinations(destinationIndex)
          val pDestination =
            pUop.destinations(destinationIndex).currentPMapping
          val localDestination = tuUop.destinations(destinationIndex)
          val destination = row.destinations(destinationIndex)
          destination.valid := decodedDestination.valid
          destination.kind := decodedDestination.kind
          destination.atag := decodedDestination.atag
          destination.relativeIndex := decodedDestination.relativeIndex
          destination.ptag := pDestination.ptag
          destination.ptagGeneration := pDestination.ptagGeneration
          destination.localTag := localDestination.physicalTag
          destination.localSequence := localDestination.sequence
          when(decodedDestination.valid &&
              decodedDestination.kind === DestinationKind.Gpr &&
              pDestination.valid) {
            pReadyValid(pDestination.ptag) := false.B
          }
          when(decodedDestination.valid &&
              decodedDestination.kind === DestinationKind.T &&
              localDestination.valid &&
              localDestination.physicalTag < p.tPhysRegs.U) {
            val destinationTtag = localDestination.physicalTag(
              ttagIndexWidth - 1, 0)
            tReadyValid(safeS2OwnerStid)(destinationTtag) := false.B
          }
          when(decodedDestination.valid &&
              decodedDestination.kind === DestinationKind.U &&
              localDestination.valid &&
              localDestination.physicalTag < p.uPhysRegs.U) {
            val destinationUtag = localDestination.physicalTag(
              utagIndexWidth - 1, 0)
            uReadyValid(safeS2OwnerStid)(destinationUtag) := false.B
          }
        }
        rows(uopClass)(bank)(entry) := row
        s1Claimed(uopClass)(bank)(entry) := false.B
        slotState(uopClass)(bank)(entry) := OooIexIssueSlotState.BoundS2
      }
    }
  }.otherwise {
    s3PendingValid := false.B
  }

  // Wakeups mutate only registered source-ready state.  Query/pick-enable
  // below never consumes the combinational wakeup inputs directly.
  for (uopClass <- 0 until p.iqClassCount;
       bank <- 0 until p.iqBankCount;
       entry <- 0 until p.iqEntriesPerBank;
       sourceIndex <- 0 until p.maxSourceOperands) {
    val source = rows(uopClass)(bank)(entry).sources(sourceIndex)
    val wakeMatch = io.wakeup.map { wake =>
      val pMatch = source.operandClass === OperandClass.P &&
        wake.bits.operandClass === OperandClass.P &&
        source.ptag === wake.bits.ptag &&
        source.ptagGeneration === wake.bits.ptagGeneration
      val tMatch = source.operandClass === OperandClass.T &&
        wake.bits.operandClass === OperandClass.T &&
        source.localTag === wake.bits.localTag &&
        source.localSequence.asUInt === wake.bits.localSequence.asUInt
      val uMatch = source.operandClass === OperandClass.U &&
        wake.bits.operandClass === OperandClass.U &&
        source.localTag === wake.bits.localTag &&
        source.localSequence.asUInt === wake.bits.localSequence.asUInt
      wake.valid && wake.bits.stid === rows(uopClass)(bank)(entry).stid &&
        wake.bits.epoch === rows(uopClass)(bank)(entry).epoch &&
        (pMatch || tMatch || uMatch)
    }.reduce(_ || _)
    when(slotState(uopClass)(bank)(entry) =/= OooIexIssueSlotState.Free &&
        source.valid && !source.ready && wakeMatch) {
      source.ready := true.B
    }
  }

  val release = io.release.bits
  val releaseClass = release.dispatch.reservation.uopClass.asUInt
  val releaseClassInRange = releaseClass < p.iqClassCount.U
  val releaseBankInRange = release.dispatch.reservation.bank < p.iqBankCount.U
  val releaseEntryInRange =
    release.dispatch.reservation.speculativeSlot < p.iqEntriesPerBank.U
  val safeReleaseClass = Mux(releaseClassInRange, releaseClass, 0.U)
  val safeReleaseBank = Mux(releaseBankInRange,
    release.dispatch.reservation.bank, 0.U)
  val safeReleaseEntry = Mux(releaseEntryInRange,
    release.dispatch.reservation.speculativeSlot, 0.U)
  val releaseRow = rows(safeReleaseClass)(safeReleaseBank)(safeReleaseEntry)
  val releaseExact = releaseClassInRange && releaseBankInRange &&
    releaseEntryInRange && release.dispatch.reservation.valid &&
    slotState(safeReleaseClass)(safeReleaseBank)(safeReleaseEntry) ===
      OooIexIssueSlotState.ResidentS3 && releaseRow.valid &&
    sameMember(release.member, releaseRow.member) &&
    release.dispatch.peId === releaseRow.peId &&
    release.dispatch.stid === releaseRow.stid &&
    release.dispatch.epoch === releaseRow.epoch &&
    release.dispatch.transactionId === releaseRow.transactionId &&
    release.dispatch.reservation.asUInt === releaseRow.reservation.asUInt
  io.dispatchRelease.valid := io.release.valid && releaseExact
  io.dispatchRelease.bits := release.dispatch
  io.release.ready := releaseExact && io.dispatchRelease.ready
  io.releaseRejected.valid := io.release.valid && !releaseExact
  io.releaseRejected.bits.member := release.member
  io.releaseRejected.bits.peId := release.dispatch.peId
  io.releaseRejected.bits.stid := release.dispatch.stid
  io.releaseRejected.bits.epoch := release.dispatch.epoch
  io.releaseRejected.bits.transactionId := release.dispatch.transactionId
  io.releaseRejected.bits.reservation := release.dispatch.reservation

  when(io.release.fire) {
    slotState(safeReleaseClass)(safeReleaseBank)(safeReleaseEntry) :=
      OooIexIssueSlotState.Free
    rows(safeReleaseClass)(safeReleaseBank)(safeReleaseEntry) :=
      0.U.asTypeOf(new OooIexIssueRow(p))
  }

  val queryClass = io.query.uopClass.asUInt
  val queryClassInRange = queryClass < p.iqClassCount.U
  val queryBankInRange = io.query.bank < p.iqBankCount.U
  val queryEntryInRange = io.query.entry < p.iqEntriesPerBank.U
  val safeQueryClass = Mux(queryClassInRange, queryClass, 0.U)
  val safeQueryBank = Mux(queryBankInRange, io.query.bank, 0.U)
  val safeQueryEntry = Mux(queryEntryInRange, io.query.entry, 0.U)
  io.queryState := slotState(safeQueryClass)(safeQueryBank)(safeQueryEntry)
  io.queryRow := rows(safeQueryClass)(safeQueryBank)(safeQueryEntry)
  val querySourcesReady = io.queryRow.sources.map { source =>
    !source.valid || source.ready
  }.reduce(_ && _)
  io.queryPickable := queryClassInRange && queryBankInRange &&
    queryEntryInRange && io.queryState === OooIexIssueSlotState.ResidentS3 &&
    io.queryRow.valid && querySourcesReady

  io.s1Occupied := s1Valid
  for (uopClass <- 0 until p.iqClassCount; bank <- 0 until p.iqBankCount) {
    io.boundEntries(uopClass)(bank) := PopCount(VecInit(
      (0 until p.iqEntriesPerBank).map { entry =>
        slotState(uopClass)(bank)(entry) === OooIexIssueSlotState.BoundS2
      }).asUInt)
    io.residentEntries(uopClass)(bank) := PopCount(VecInit(
      (0 until p.iqEntriesPerBank).map { entry =>
        slotState(uopClass)(bank)(entry) === OooIexIssueSlotState.ResidentS3
      }).asUInt)
    for (entry <- 0 until p.iqEntriesPerBank) {
      assert((slotState(uopClass)(bank)(entry) === OooIexIssueSlotState.Free) ===
        !rows(uopClass)(bank)(entry).valid,
        "a physical IEX row must agree with its lifecycle state")
    }
  }
}
