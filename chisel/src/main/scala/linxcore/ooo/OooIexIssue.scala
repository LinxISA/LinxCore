package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, OHToUInt, PopCount, PriorityEncoderOH, UIntToOH,
  Valid, log2Ceil}
import linxcore.common.{DestinationKind, OperandClass}

object OooIexIssueSlotState extends ChiselEnum {
  val Free, BoundS2, ResidentS3 = Value
}

object OooIexRecoveryScanState extends ChiselEnum {
  val Idle, Scan, Prepared, Rejected = Value
}

class OooIexIssueIO(val p: OooParams = OooParams()) extends Bundle {
  val s1 = Flipped(Decoupled(new OooIexS1Transaction(p)))
  val wakeup = Input(Vec(p.iexWakeupPorts, Valid(new OooIexWakeup(p))))

  val release = Flipped(Decoupled(new OooIexIssueRelease(p)))
  val dispatchRelease = Decoupled(new OooDispatchRelease(p))

  val recoveryPrepare = Flipped(Valid(new OooResidencyRecoveryPlan(p)))
  val recoveryPrepareReady = Output(Bool())
  val recoveryPrepared = Output(new OooIexRecoveryPrepared(p))
  val recoveryFire = Input(Bool())
  val recoveryApplied = Valid(new OooResidencyRecoveryPlan(p))
  // Every PTag return must invalidate the generation-qualified ready record
  // before the freelist can recycle that token.
  val ptagRecycle = Flipped(Decoupled(new OooPTagReturnBatch(p)))

  val query = Input(new OooIexSlotQuery(p))
  val queryState = Output(OooIexIssueSlotState())
  val queryRow = Output(new OooIexIssueRow(p))
  val queryPickable = Output(Bool())

  // One topology-neutral issue domain. Later width packets instantiate the
  // same picker for disjoint class/bank domains rather than copying IQ state.
  val pickClass = Input(OooUopClass())
  val pickBankEnable = Input(UInt(p.iqBankCount.W))
  val pick = Decoupled(new OooIexPickToken(p))
  val pickRetry = Flipped(Valid(new OooIexReadRepick(p)))
  val pickMalformed = Valid(new OooIexPickReject(p))
  val pickRejected = Valid(new OooIexPickClaimReject(p))
  val pickRetryRejected = Valid(new OooIexPickRetryReject(p))
  val pickRecoveryCanceled = Valid(new OooIexPickToken(p))
  val pickRecoveryBlocked = Valid(new OooIexPickToken(p))

  val s1Occupied = Output(Vec(p.stidCount, Bool()))
  val s2Bind = Valid(new OooIexS2BindAck(p))
  val s3Enable = Valid(new OooIexS3Enable(p))
  val boundEntries = Output(Vec(p.iqClassCount,
    Vec(p.iqBankCount, UInt(p.iqBankEntryCountWidth.W))))
  val residentEntries = Output(Vec(p.iqClassCount,
    Vec(p.iqBankCount, UInt(p.iqBankEntryCountWidth.W))))
  val inFlightEntries = Output(Vec(p.iqClassCount,
    Vec(p.iqBankCount, UInt(p.iqBankEntryCountWidth.W))))

  val s1Rejected = Valid(new OooIexS1Reject(p))
  val releaseRejected = Valid(new OooIexReleaseReject(p))
  val recoveryRejected = Valid(new OooIexRecoveryReject(p))
}

/** OOO-S1 to IEX-S3 residency owner.
  *
  * One retained S1 slot exists per STID.  A fair shared S2 writer consumes at
  * most one transaction per cycle, writes every exact dispatch child or none,
  * and leaves the rows in `BoundS2` for a complete cycle.  Only the following
  * S3 transition makes a row eligible for a future picker.  Source wakeups are
  * registered into the row, so a wakeup observed in cycle N cannot affect an
  * S3 eligibility decision in that same cycle.
  *
  * One topology-neutral oldest-ready domain now claims canonical in-flight
  * state before P1. Read denial/rejection returns that exact claim for retry.
  * The release seam models only a later exact, non-cancellable I2 terminal
  * event and joins physical row removal to the existing dispatch-reservation
  * release handshake.
  */
class OooIexIssue(val p: OooParams = OooParams()) extends Module {
  val io = IO(new OooIexIssueIO(p))
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
  val scheduleRows = RegInit(VecInit(Seq.fill(p.iqClassCount)(
    VecInit(Seq.fill(p.iqBankCount)(
      VecInit(Seq.fill(p.iqEntriesPerBank)(
        0.U.asTypeOf(new OooIexScheduleRow(p)))))))))
  val payloadRows = Seq.tabulate(p.iqClassCount, p.iqBankCount) { (_, _) =>
    Mem(p.iqEntriesPerBank, new OooIexPayloadSidecar(p))
  }

  val s1Valid = RegInit(VecInit(Seq.fill(p.stidCount)(false.B)))
  val s1Rows = Reg(Vec(p.stidCount, new OooIexS1Transaction(p)))
  val s2RoundRobin = RegInit(0.U(p.stidWidth.W))

  val recoveryScanState = RegInit(OooIexRecoveryScanState.Idle)
  val retainedRecoveryPlan = RegInit(
    0.U.asTypeOf(new OooResidencyRecoveryPlan(p)))
  val recoveryScanCursor = RegInit(0.U(p.iexRecoveryScanCursorWidth.W))
  val recoveryRowKillMask = RegInit(VecInit(Seq.fill(p.iqClassCount)(
    VecInit(Seq.fill(p.iqBankCount)(
      VecInit(Seq.fill(p.iqEntriesPerBank)(false.B)))))))
  val retainedRecoveryS1KillMask = RegInit(0.U(p.dispatchWidth.W))
  val retainedRecoveryS1RowsExact = RegInit(false.B)
  val retainedRecoveryBoundKilled = RegInit(0.U(
    p.countWidth(p.iqClassCount * p.iqBankCount * p.iqEntriesPerBank).W))
  val retainedRecoveryResidentKilled = RegInit(0.U(
    p.countWidth(p.iqClassCount * p.iqBankCount * p.iqEntriesPerBank).W))
  val retainedRecoveryS3SeenMask = RegInit(0.U(p.dispatchWidth.W))
  val retainedRecoveryS3KillMask = RegInit(0.U(p.dispatchWidth.W))
  val retainedRecoveryPReadyKillMask = RegInit(0.U(p.pPhysRegs.W))
  val retainedRecoveryTReadyKillMask = RegInit(0.U(p.tPhysRegs.W))
  val retainedRecoveryUReadyKillMask = RegInit(0.U(p.uPhysRegs.W))

  val recoveryPlan = Mux(
    recoveryScanState === OooIexRecoveryScanState.Idle,
    io.recoveryPrepare.bits,
    retainedRecoveryPlan)
  val recoveryStid = recoveryPlan.oldHead.stid
  val recoveryStidInRange = recoveryStid < p.stidCount.U
  val safeRecoveryStid = Mux(recoveryStidInRange, recoveryStid, 0.U)
  val recoveryOfferExact = io.recoveryPrepare.valid &&
    io.recoveryPrepare.bits.asUInt === recoveryPlan.asUInt
  val recoveryFreeze = recoveryPlan.valid && recoveryStidInRange &&
    (io.recoveryPrepare.valid ||
      recoveryScanState =/= OooIexRecoveryScanState.Idle)
  io.recoveryApplied.valid := io.recoveryFire && io.recoveryPrepareReady
  io.recoveryApplied.bits := recoveryPlan

  // A wakeup must remain visible to consumers dispatched after that producer
  // completed.  These generation-qualified scoreboards complement per-row
  // ready bits; without them, a consumer arriving after the one-cycle wakeup
  // pulse could remain blocked forever.
  val pReadyValid = RegInit(VecInit(Seq.fill(p.pPhysRegs)(false.B)))
  val pReadyGeneration = Reg(Vec(p.pPhysRegs,
    UInt(p.pTagGenerationWidth.W)))
  val pReadyStid = Reg(Vec(p.pPhysRegs, UInt(p.stidWidth.W)))
  val pReadyEpoch = Reg(Vec(p.pPhysRegs, UInt(p.epochWidth.W)))
  // The global P-ready scoreboard may be recycled and rewritten by a peer
  // while a target-STID recovery scan is retained.  Snapshot the complete
  // owner identity at capture so the scan never follows mutable scoreboard
  // state, then revalidate the same identity again at common apply.
  val retainedRecoveryPReadyValid = RegInit(
    VecInit(Seq.fill(p.pPhysRegs)(false.B)))
  val retainedRecoveryPReadyGeneration = RegInit(VecInit(
    Seq.fill(p.pPhysRegs)(0.U(p.pTagGenerationWidth.W))))
  val retainedRecoveryPReadyStid = RegInit(VecInit(
    Seq.fill(p.pPhysRegs)(0.U(p.stidWidth.W))))
  val retainedRecoveryPReadyEpoch = RegInit(VecInit(
    Seq.fill(p.pPhysRegs)(0.U(p.epochWidth.W))))
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

  val recycleCountInRange = io.ptagRecycle.bits.count <= p.pTagReturnWidth.U
  val recycleTokensExact = (0 until p.pTagReturnWidth).map { index =>
    val active = index.U < io.ptagRecycle.bits.count
    val token = io.ptagRecycle.bits.tokens(index)
    token.valid === active && (!active || token.ptag < p.pPhysRegs.U)
  }.reduce(_ && _)
  io.ptagRecycle.ready := recycleCountInRange && recycleTokensExact

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
  io.s1.ready := !s1Valid(safeRequestStid) && s1ShapeExact && s1TargetsExact &&
    !(recoveryFreeze && requestStid === recoveryStid)
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
  val s3PendingValid = RegInit(false.B)
  val s3Pending = Reg(new OooIexS3Enable(p))
  val s3PendingFrozen = recoveryFreeze && s3PendingValid &&
    s3Pending.bind.stid === recoveryStid
  for (offset <- 0 until p.stidCount) {
    val candidate = (s2RoundRobin + offset.U)(p.stidWidth - 1, 0)
    s2OffsetCandidates(offset) := s1Valid(candidate) && !s3PendingFrozen &&
      !(recoveryFreeze && candidate === recoveryStid)
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

  io.s3Enable.valid := s3PendingValid && !s3PendingFrozen
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
    val wakeupFrozen = recoveryFreeze && wakeup.bits.stid === recoveryStid
    when(wakeup.valid && wakeupFrozen) {
      assert(false.B,
        "global recovery must quiesce target-STID wakeups before IEX prepare")
    }
    when(wakeup.valid && !wakeupFrozen &&
        wakeup.bits.operandClass === OperandClass.P &&
        ptagInRange) {
      pReadyValid(safePtag) := true.B
      pReadyGeneration(safePtag) := wakeup.bits.ptagGeneration
      pReadyStid(safePtag) := wakeup.bits.stid
      pReadyEpoch(safePtag) := wakeup.bits.epoch
    }
    when(wakeup.valid && !wakeupFrozen &&
        wakeup.bits.operandClass === OperandClass.T &&
        wakeStidInRange && ttagInRange &&
        wakeup.bits.localSequence.valid) {
      tReadyValid(safeWakeStid)(safeTtag) := true.B
      tReadySequence(safeWakeStid)(safeTtag) :=
        wakeup.bits.localSequence
      tReadyEpoch(safeWakeStid)(safeTtag) := wakeup.bits.epoch
    }
    when(wakeup.valid && !wakeupFrozen &&
        wakeup.bits.operandClass === OperandClass.U &&
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
    when(slotState(uopClass)(bank)(entry) === OooIexIssueSlotState.BoundS2 &&
        !(recoveryFreeze &&
          scheduleRows(uopClass)(bank)(entry).stid === recoveryStid)) {
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
        row.dispatchLane := lane.U
        row.uopIndex := allocation.uopIndex
        row.childIndex := allocation.childIndex
        row.member := pUop.member
        row.member.memberIndex := pUop.member.memberIndex + allocation.childIndex
        row.reservation := reservation
        row.uopKey := pUop.decoded.identity.key
        row.parentCount := pUop.decoded.identity.parentCount
        row.parentPcTokens := s2Request.o3.parentPcTokens(uopIndex)
        row.pcParentIndexValid := false.B
        row.pcParentIndex := 0.U
        row.primaryPrediction := 0.U.asTypeOf(row.primaryPrediction)
        for (parentIndex <- 0 until p.maxArchitecturalParentRefs) {
          val parent = pUop.decoded.identity.parents(parentIndex)
          when(parent.key.valid && parent.key.asUInt ===
              pUop.decoded.identity.key.primaryParent.asUInt) {
            row.pcParentIndexValid := true.B
            row.pcParentIndex := parentIndex.U
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
        scheduleRows(uopClass)(bank)(entry) := row.schedule
        for (targetClass <- 0 until p.iqClassCount;
             targetBank <- 0 until p.iqBankCount) {
          when(uopClass === targetClass.U && bank === targetBank.U) {
            payloadRows(targetClass)(targetBank).write(entry, row.payload)
          }
        }
        s1Claimed(uopClass)(bank)(entry) := false.B
        slotState(uopClass)(bank)(entry) := OooIexIssueSlotState.BoundS2
      }
    }
  }.elsewhen(!s3PendingFrozen) {
    s3PendingValid := false.B
  }

  // Wakeups mutate only registered source-ready state.  Query/pick-enable
  // below never consumes the combinational wakeup inputs directly.
  for (uopClass <- 0 until p.iqClassCount;
       bank <- 0 until p.iqBankCount;
       entry <- 0 until p.iqEntriesPerBank;
       sourceIndex <- 0 until p.maxSourceOperands) {
    val source = scheduleRows(uopClass)(bank)(entry).sources(sourceIndex)
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
      wake.valid && wake.bits.stid === scheduleRows(uopClass)(bank)(entry).stid &&
        wake.bits.epoch === scheduleRows(uopClass)(bank)(entry).epoch &&
        (pMatch || tMatch || uMatch)
    }.reduce(_ || _)
    when(slotState(uopClass)(bank)(entry) =/= OooIexIssueSlotState.Free &&
        !(recoveryFreeze &&
          scheduleRows(uopClass)(bank)(entry).stid === recoveryStid) &&
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
  val releaseRow = scheduleRows(safeReleaseClass)(safeReleaseBank)(safeReleaseEntry)
  val releaseExact = releaseClassInRange && releaseBankInRange &&
    releaseEntryInRange && release.dispatch.reservation.valid &&
    slotState(safeReleaseClass)(safeReleaseBank)(safeReleaseEntry) ===
      OooIexIssueSlotState.ResidentS3 && releaseRow.valid &&
    releaseRow.inFlight &&
    sameMember(release.member, releaseRow.member) &&
    release.dispatch.peId === releaseRow.peId &&
    release.dispatch.stid === releaseRow.stid &&
    release.dispatch.epoch === releaseRow.epoch &&
    release.dispatch.transactionId === releaseRow.transactionId &&
    release.dispatch.reservation.asUInt === releaseRow.reservation.asUInt &&
    sameMember(release.dispatch.member, release.member) &&
    !(recoveryFreeze && release.dispatch.stid === recoveryStid)
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
    scheduleRows(safeReleaseClass)(safeReleaseBank)(safeReleaseEntry) :=
      0.U.asTypeOf(new OooIexScheduleRow(p))
  }

  val recoveryS1 = s1Rows(safeRecoveryStid)
  val recoveryS1Live = recoveryStidInRange && s1Valid(safeRecoveryStid)
  val recoveryS1Kill = Wire(Vec(p.dispatchWidth, Bool()))
  val recoveryS1LaneExact = Wire(Vec(p.dispatchWidth, Bool()))
  for (lane <- 0 until p.dispatchWidth) {
    val allocation = recoveryS1.dispatch.allocations(lane)
    val reservation = allocation.reservation
    val uopIndexInRange = allocation.uopIndex < p.decodedUopWidth.U
    val safeUopIndex = Mux(uopIndexInRange, allocation.uopIndex, 0.U)
    val pUop = recoveryS1.pRename.uops(safeUopIndex)
    val decodedUop = recoveryS1.o3.request.reservation.transaction.decoded
      .uops(safeUopIndex)
    val classIndex = reservation.uopClass.asUInt
    val classInRange = classIndex < p.iqClassCount.U
    val bankInRange = reservation.bank < p.iqBankCount.U
    val entryInRange = reservation.speculativeSlot < p.iqEntriesPerBank.U
    val safeClass = Mux(classInRange, classIndex, 0.U)
    val safeBank = Mux(bankInRange, reservation.bank, 0.U)
    val safeEntry = Mux(entryInRange, reservation.speculativeSlot, 0.U)
    val member = Wire(new RobMemberKey(p))
    member := pUop.member
    member.memberIndex := pUop.member.memberIndex + allocation.childIndex
    recoveryS1LaneExact(lane) := !allocation.valid || (
      recoveryS1.dispatch.allocationMask(lane) && reservation.valid &&
        uopIndexInRange && pUop.valid && decodedUop.valid &&
        allocation.childIndex < decodedUop.plannedChildCount &&
        classInRange && bankInRange && entryInRange &&
        slotState(safeClass)(safeBank)(safeEntry) ===
          OooIexIssueSlotState.Free &&
        s1Claimed(safeClass)(safeBank)(safeEntry) &&
        member.group.peId === recoveryS1.dispatch.peId &&
        member.group.stid === recoveryS1.dispatch.stid &&
        OooRecoveryMembership.memberInOldWindow(p, recoveryPlan, member))
    recoveryS1Kill(lane) := recoveryS1Live && allocation.valid &&
      OooRecoveryMembership.memberKilled(p, recoveryPlan, member)
  }
  val recoveryS1IdentityExact = !recoveryS1Live || (
    recoveryS1.dispatch.valid && recoveryS1.pRename.valid &&
      recoveryS1.dispatch.stid === recoveryStid &&
      recoveryS1.pRename.stid === recoveryStid &&
      recoveryS1.dispatch.peId === recoveryPlan.oldHead.peId &&
      recoveryS1.pRename.peId === recoveryPlan.oldHead.peId &&
      recoveryS1.dispatch.transactionId ===
        recoveryS1.pRename.transactionId &&
      recoveryS1.dispatch.allocationMask === VecInit(
        recoveryS1.dispatch.allocations.map(_.valid)).asUInt)
  val recoveryS1RowsExact = !recoveryS1Live ||
    (recoveryS1IdentityExact && recoveryS1LaneExact.reduce(_ && _))

  // Recovery scans one shallow slice from every physical class/bank per
  // cycle.  This replaces the former one-cycle CAM over every resident row.
  // The scan is side-effect free: it retains exact masks and counters, then
  // the common O3 apply consumes those masks in one architectural event.
  val recoveryScanRowExact = Wire(Vec(p.iqClassCount,
    Vec(p.iqBankCount,
      Vec(p.iexRecoveryScanEntriesPerBankPerCycle, Bool()))))
  val recoveryScanRowKill = Wire(Vec(p.iqClassCount,
    Vec(p.iqBankCount,
      Vec(p.iexRecoveryScanEntriesPerBankPerCycle, Bool()))))
  val recoveryScanBoundKill = Wire(Vec(p.iqClassCount,
    Vec(p.iqBankCount,
      Vec(p.iexRecoveryScanEntriesPerBankPerCycle, Bool()))))
  val recoveryScanResidentKill = Wire(Vec(p.iqClassCount,
    Vec(p.iqBankCount,
      Vec(p.iexRecoveryScanEntriesPerBankPerCycle, Bool()))))
  val recoveryScanS3Seen = Wire(Vec(p.iqClassCount,
    Vec(p.iqBankCount,
      Vec(p.iexRecoveryScanEntriesPerBankPerCycle,
        UInt(p.dispatchWidth.W)))))
  val recoveryScanS3Kill = Wire(Vec(p.iqClassCount,
    Vec(p.iqBankCount,
      Vec(p.iexRecoveryScanEntriesPerBankPerCycle,
        UInt(p.dispatchWidth.W)))))
  val recoveryScanPReadyKill = Wire(Vec(p.iqClassCount,
    Vec(p.iqBankCount,
      Vec(p.iexRecoveryScanEntriesPerBankPerCycle,
        UInt(p.pPhysRegs.W)))))
  val recoveryScanTReadyKill = Wire(Vec(p.iqClassCount,
    Vec(p.iqBankCount,
      Vec(p.iexRecoveryScanEntriesPerBankPerCycle,
        UInt(p.tPhysRegs.W)))))
  val recoveryScanUReadyKill = Wire(Vec(p.iqClassCount,
    Vec(p.iqBankCount,
      Vec(p.iexRecoveryScanEntriesPerBankPerCycle,
        UInt(p.uPhysRegs.W)))))

  for (uopClass <- 0 until p.iqClassCount;
       bank <- 0 until p.iqBankCount;
       scanLane <- 0 until p.iexRecoveryScanEntriesPerBankPerCycle) {
    val entryWide = recoveryScanCursor *
      p.iexRecoveryScanEntriesPerBankPerCycle.U + scanLane.U
    val entry = entryWide(p.iqEntryWidth - 1, 0)
    val state = slotState(uopClass)(bank)(entry)
    val row = scheduleRows(uopClass)(bank)(entry)
    val occupied = state =/= OooIexIssueSlotState.Free
    val selected = occupied && row.stid === recoveryStid
    val rowExact = !selected || (
      row.valid && row.peId === recoveryPlan.oldHead.peId &&
        row.member.group.peId === row.peId &&
        row.member.group.stid === row.stid &&
        row.reservation.valid &&
        row.reservation.uopClass.asUInt === uopClass.U &&
        row.reservation.bank === bank.U &&
        row.reservation.speculativeSlot === entry &&
        row.dispatchLane < p.dispatchWidth.U &&
        OooRecoveryMembership.memberInOldWindow(
          p, recoveryPlan, row.member))
    val rowKill = selected && rowExact &&
      OooRecoveryMembership.memberKilled(p, recoveryPlan, row.member)
    recoveryScanRowExact(uopClass)(bank)(scanLane) := rowExact
    recoveryScanRowKill(uopClass)(bank)(scanLane) := rowKill
    recoveryScanBoundKill(uopClass)(bank)(scanLane) := rowKill &&
      state === OooIexIssueSlotState.BoundS2
    recoveryScanResidentKill(uopClass)(bank)(scanLane) := rowKill &&
      state === OooIexIssueSlotState.ResidentS3

    val s3RowExact = s3PendingValid &&
      s3Pending.bind.stid === recoveryStid && occupied && row.valid &&
      row.dispatchLane < p.dispatchWidth.U &&
      row.peId === s3Pending.bind.peId &&
      row.stid === s3Pending.bind.stid &&
      row.epoch === s3Pending.bind.epoch &&
      row.transactionId === s3Pending.bind.transactionId
    recoveryScanS3Seen(uopClass)(bank)(scanLane) := Mux(s3RowExact,
      UIntToOH(row.dispatchLane, p.dispatchWidth), 0.U(p.dispatchWidth.W))
    recoveryScanS3Kill(uopClass)(bank)(scanLane) := Mux(
      s3RowExact && rowKill,
      UIntToOH(row.dispatchLane, p.dispatchWidth), 0.U(p.dispatchWidth.W))

    val pReadyKill = (0 until p.maxDestinationOperands).map {
      destinationIndex =>
        val destination = row.destinations(destinationIndex)
        val inRange = destination.ptag < p.pPhysRegs.U
        val safeTag = Mux(inRange, destination.ptag, 0.U)
        val exact = rowKill && destination.valid &&
          destination.kind === DestinationKind.Gpr && inRange &&
          retainedRecoveryPReadyValid(safeTag) &&
          retainedRecoveryPReadyGeneration(safeTag) ===
            destination.ptagGeneration &&
          retainedRecoveryPReadyStid(safeTag) === row.stid &&
          retainedRecoveryPReadyEpoch(safeTag) === row.epoch
        Mux(exact, UIntToOH(destination.ptag, p.pPhysRegs),
          0.U(p.pPhysRegs.W))
    }.reduce(_ | _)
    val tReadyKill = (0 until p.maxDestinationOperands).map {
      destinationIndex =>
        val destination = row.destinations(destinationIndex)
        val inRange = destination.localTag < p.tPhysRegs.U
        val safeTag = Mux(inRange, destination.localTag, 0.U)(
          ttagIndexWidth - 1, 0)
        val exact = rowKill && destination.valid &&
          destination.kind === DestinationKind.T && inRange &&
          tReadyValid(safeRecoveryStid)(safeTag) &&
          tReadySequence(safeRecoveryStid)(safeTag).asUInt ===
            destination.localSequence.asUInt &&
          tReadyEpoch(safeRecoveryStid)(safeTag) === row.epoch
        Mux(exact, UIntToOH(destination.localTag, p.tPhysRegs),
          0.U(p.tPhysRegs.W))
    }.reduce(_ | _)
    val uReadyKill = (0 until p.maxDestinationOperands).map {
      destinationIndex =>
        val destination = row.destinations(destinationIndex)
        val inRange = destination.localTag < p.uPhysRegs.U
        val safeTag = Mux(inRange, destination.localTag, 0.U)(
          utagIndexWidth - 1, 0)
        val exact = rowKill && destination.valid &&
          destination.kind === DestinationKind.U && inRange &&
          uReadyValid(safeRecoveryStid)(safeTag) &&
          uReadySequence(safeRecoveryStid)(safeTag).asUInt ===
            destination.localSequence.asUInt &&
          uReadyEpoch(safeRecoveryStid)(safeTag) === row.epoch
        Mux(exact, UIntToOH(destination.localTag, p.uPhysRegs),
          0.U(p.uPhysRegs.W))
    }.reduce(_ | _)
    recoveryScanPReadyKill(uopClass)(bank)(scanLane) := pReadyKill
    recoveryScanTReadyKill(uopClass)(bank)(scanLane) := tReadyKill
    recoveryScanUReadyKill(uopClass)(bank)(scanLane) := uReadyKill
  }

  val recoveryScanRowsExact = recoveryScanRowExact.asUInt.andR
  val recoveryScanBoundKilled = PopCount(recoveryScanBoundKill.asUInt)
  val recoveryScanResidentKilled = PopCount(recoveryScanResidentKill.asUInt)
  val recoveryScanS3SeenMask = (0 until p.iqClassCount).flatMap {
    uopClass => (0 until p.iqBankCount).flatMap { bank =>
      (0 until p.iexRecoveryScanEntriesPerBankPerCycle).map { scanLane =>
        recoveryScanS3Seen(uopClass)(bank)(scanLane)
      }
    }
  }.reduce(_ | _)
  val recoveryScanS3KillMask = (0 until p.iqClassCount).flatMap {
    uopClass => (0 until p.iqBankCount).flatMap { bank =>
      (0 until p.iexRecoveryScanEntriesPerBankPerCycle).map { scanLane =>
        recoveryScanS3Kill(uopClass)(bank)(scanLane)
      }
    }
  }.reduce(_ | _)
  val recoveryScanPReadyKillMask = (0 until p.iqClassCount).flatMap {
    uopClass => (0 until p.iqBankCount).flatMap { bank =>
      (0 until p.iexRecoveryScanEntriesPerBankPerCycle).map { scanLane =>
        recoveryScanPReadyKill(uopClass)(bank)(scanLane)
      }
    }
  }.reduce(_ | _)
  val recoveryScanTReadyKillMask = (0 until p.iqClassCount).flatMap {
    uopClass => (0 until p.iqBankCount).flatMap { bank =>
      (0 until p.iexRecoveryScanEntriesPerBankPerCycle).map { scanLane =>
        recoveryScanTReadyKill(uopClass)(bank)(scanLane)
      }
    }
  }.reduce(_ | _)
  val recoveryScanUReadyKillMask = (0 until p.iqClassCount).flatMap {
    uopClass => (0 until p.iqBankCount).flatMap { bank =>
      (0 until p.iexRecoveryScanEntriesPerBankPerCycle).map { scanLane =>
        recoveryScanUReadyKill(uopClass)(bank)(scanLane)
      }
    }
  }.reduce(_ | _)
  val recoveryScanLast = recoveryScanCursor ===
    (p.iexRecoveryScanCycles - 1).U
  val recoveryNextS3SeenMask = retainedRecoveryS3SeenMask |
    recoveryScanS3SeenMask
  val recoveryS3ExactAfterScan = !s3PendingValid ||
    s3Pending.bind.stid =/= recoveryStid ||
    (s3Pending.bind.allocationMask & ~recoveryNextS3SeenMask) === 0.U
  val recoveryCaptureExact = recoveryPlan.valid && recoveryStidInRange &&
    recoveryS1RowsExact

  val recoveryCaptureRejected =
    recoveryScanState === OooIexRecoveryScanState.Idle &&
      io.recoveryPrepare.valid && !recoveryCaptureExact
  val recoveryOfferChanged =
    recoveryScanState =/= OooIexRecoveryScanState.Idle &&
      recoveryScanState =/= OooIexRecoveryScanState.Rejected &&
      io.recoveryPrepare.valid && !recoveryOfferExact
  val recoveryScanRejected =
    recoveryScanState === OooIexRecoveryScanState.Scan &&
      io.recoveryPrepare.valid && recoveryOfferExact &&
      (!recoveryScanRowsExact ||
        (recoveryScanLast && !recoveryS3ExactAfterScan))

  io.recoveryPrepareReady :=
    recoveryScanState === OooIexRecoveryScanState.Prepared &&
      recoveryOfferExact
  io.recoveryPrepared := 0.U.asTypeOf(io.recoveryPrepared)
  io.recoveryPrepared.valid := io.recoveryPrepareReady
  io.recoveryPrepared.stid := recoveryStid
  io.recoveryPrepared.s1Killed := PopCount(retainedRecoveryS1KillMask)
  io.recoveryPrepared.boundKilled := retainedRecoveryBoundKilled
  io.recoveryPrepared.residentKilled := retainedRecoveryResidentKilled
  io.recoveryRejected.valid := recoveryCaptureRejected ||
    recoveryOfferChanged || recoveryScanRejected ||
    (recoveryScanState === OooIexRecoveryScanState.Rejected &&
      io.recoveryPrepare.valid)
  io.recoveryRejected.bits.requested := recoveryPlan
  io.recoveryRejected.bits.stidInRange := recoveryStidInRange
  io.recoveryRejected.bits.residentRowsExact := false.B
  io.recoveryRejected.bits.s1RowsExact := Mux(
    recoveryScanState === OooIexRecoveryScanState.Idle,
    recoveryS1RowsExact, retainedRecoveryS1RowsExact)

  when(recoveryScanState === OooIexRecoveryScanState.Idle &&
      io.recoveryPrepare.valid && recoveryCaptureExact) {
    retainedRecoveryPlan := io.recoveryPrepare.bits
    retainedRecoveryS1KillMask := recoveryS1Kill.asUInt
    retainedRecoveryS1RowsExact := recoveryS1RowsExact
    retainedRecoveryBoundKilled := 0.U
    retainedRecoveryResidentKilled := 0.U
    retainedRecoveryS3SeenMask := 0.U
    retainedRecoveryS3KillMask := 0.U
    retainedRecoveryPReadyKillMask := 0.U
    retainedRecoveryTReadyKillMask := 0.U
    retainedRecoveryUReadyKillMask := 0.U
    retainedRecoveryPReadyValid := pReadyValid
    retainedRecoveryPReadyGeneration := pReadyGeneration
    retainedRecoveryPReadyStid := pReadyStid
    retainedRecoveryPReadyEpoch := pReadyEpoch
    recoveryScanCursor := 0.U
    recoveryScanState := OooIexRecoveryScanState.Scan
  }.elsewhen(recoveryScanState =/= OooIexRecoveryScanState.Idle &&
      !io.recoveryPrepare.valid) {
    // Valid deassertion is the existing owner-abort indication.  Scan state is
    // disposable metadata and has never mutated physical residency.
    recoveryScanState := OooIexRecoveryScanState.Idle
  }.elsewhen(recoveryOfferChanged) {
    recoveryScanState := OooIexRecoveryScanState.Rejected
  }.elsewhen(recoveryScanState === OooIexRecoveryScanState.Scan) {
    when(!recoveryScanRowsExact) {
      recoveryScanState := OooIexRecoveryScanState.Rejected
    }.otherwise {
      for (uopClass <- 0 until p.iqClassCount;
           bank <- 0 until p.iqBankCount;
           scanLane <- 0 until p.iexRecoveryScanEntriesPerBankPerCycle) {
        val entryWide = recoveryScanCursor *
          p.iexRecoveryScanEntriesPerBankPerCycle.U + scanLane.U
        val entry = entryWide(p.iqEntryWidth - 1, 0)
        recoveryRowKillMask(uopClass)(bank)(entry) :=
          recoveryScanRowKill(uopClass)(bank)(scanLane)
      }
      retainedRecoveryBoundKilled := retainedRecoveryBoundKilled +
        recoveryScanBoundKilled
      retainedRecoveryResidentKilled := retainedRecoveryResidentKilled +
        recoveryScanResidentKilled
      retainedRecoveryS3SeenMask := recoveryNextS3SeenMask
      retainedRecoveryS3KillMask := retainedRecoveryS3KillMask |
        recoveryScanS3KillMask
      retainedRecoveryPReadyKillMask := retainedRecoveryPReadyKillMask |
        recoveryScanPReadyKillMask
      retainedRecoveryTReadyKillMask := retainedRecoveryTReadyKillMask |
        recoveryScanTReadyKillMask
      retainedRecoveryUReadyKillMask := retainedRecoveryUReadyKillMask |
        recoveryScanUReadyKillMask
      when(recoveryScanLast) {
        recoveryScanState := Mux(recoveryS3ExactAfterScan,
          OooIexRecoveryScanState.Prepared,
          OooIexRecoveryScanState.Rejected)
      }.otherwise {
        recoveryScanCursor := recoveryScanCursor + 1.U
      }
    }
  }.elsewhen(io.recoveryFire) {
    recoveryScanState := OooIexRecoveryScanState.Idle
  }

  when(io.recoveryFire) {
    assert(io.recoveryPrepareReady,
      "IEX recovery may apply only one exact prepared ROB suffix")

    val survivingS1Mask = recoveryS1.dispatch.allocationMask &
      ~retainedRecoveryS1KillMask
    when(recoveryS1Live) {
      s1Rows(safeRecoveryStid).dispatch.allocationMask := survivingS1Mask
      for (lane <- 0 until p.dispatchWidth) {
        val allocation = recoveryS1.dispatch.allocations(lane)
        when(retainedRecoveryS1KillMask(lane)) {
          s1Claimed(allocation.reservation.uopClass.asUInt)(
            allocation.reservation.bank)(
            allocation.reservation.speculativeSlot) := false.B
          s1Rows(safeRecoveryStid).dispatch.allocations(lane).valid := false.B
          s1Rows(safeRecoveryStid).dispatch.allocations(lane)
            .reservation.valid := false.B
        }
      }
      when(!survivingS1Mask.orR) {
        s1Valid(safeRecoveryStid) := false.B
      }
    }

    for (uopClass <- 0 until p.iqClassCount;
         bank <- 0 until p.iqBankCount;
         entry <- 0 until p.iqEntriesPerBank) {
      when(recoveryRowKillMask(uopClass)(bank)(entry)) {
        slotState(uopClass)(bank)(entry) := OooIexIssueSlotState.Free
        scheduleRows(uopClass)(bank)(entry) :=
          0.U.asTypeOf(new OooIexScheduleRow(p))
      }
    }

    when(s3PendingValid && s3Pending.bind.stid === recoveryStid) {
      val survivingS3Mask = s3Pending.bind.allocationMask &
        ~retainedRecoveryS3KillMask
      s3Pending.bind.allocationMask := survivingS3Mask
      when(!survivingS3Mask.orR) {
        s3PendingValid := false.B
      }
    }

    for (ptag <- 0 until p.pPhysRegs) {
      val retainedPReadyIdentityStillExact = pReadyValid(ptag) &&
        retainedRecoveryPReadyValid(ptag) &&
        pReadyGeneration(ptag) ===
          retainedRecoveryPReadyGeneration(ptag) &&
        pReadyStid(ptag) === retainedRecoveryPReadyStid(ptag) &&
        pReadyEpoch(ptag) === retainedRecoveryPReadyEpoch(ptag)
      when(retainedRecoveryPReadyKillMask(ptag) &&
          retainedPReadyIdentityStillExact) {
        pReadyValid(ptag) := false.B
      }
    }
    for (ttag <- 0 until p.tPhysRegs) {
      when(retainedRecoveryTReadyKillMask(ttag)) {
        tReadyValid(safeRecoveryStid)(ttag) := false.B
      }
    }
    for (utag <- 0 until p.uPhysRegs) {
      when(retainedRecoveryUReadyKillMask(utag)) {
        uReadyValid(safeRecoveryStid)(utag) := false.B
      }
    }
  }

  // This assignment intentionally follows wakeup and recovery processing:
  // token recycle wins a same-cycle race so a stale completion can never make
  // the next generation of the physical register appear ready.
  when(io.ptagRecycle.fire) {
    for (index <- 0 until p.pTagReturnWidth) {
      val token = io.ptagRecycle.bits.tokens(index)
      when(index.U < io.ptagRecycle.bits.count && token.valid &&
          pReadyValid(token.ptag) &&
          pReadyGeneration(token.ptag) === token.generation) {
        pReadyValid(token.ptag) := false.B
      }
    }
  }

  val picker = Module(new OooIexOldestReadyPicker(p))
  picker.io.uopClass := io.pickClass
  picker.io.bankEnable := io.pickBankEnable
  picker.io.stidBlock := Mux(recoveryFreeze,
    UIntToOH(safeRecoveryStid, p.stidCount), 0.U)
  picker.io.recoveryApply.valid := io.recoveryFire && recoveryPlan.valid
  picker.io.recoveryApply.bits := recoveryPlan

  val pickerClassIndex = io.pickClass.asUInt
  for (bank <- 0 until p.iqBankCount;
       entry <- 0 until p.iqEntriesPerBank) {
    val row = scheduleRows(pickerClassIndex)(bank)(entry)
    val sourcesReady = row.sources.map(source =>
      !source.valid || source.ready).reduce(_ && _)
    val candidate = picker.io.candidates(bank)(entry)
    candidate.eligible :=
      slotState(pickerClassIndex)(bank)(entry) ===
        OooIexIssueSlotState.ResidentS3 && row.valid && !row.inFlight &&
      sourcesReady &&
      !(recoveryFreeze && row.stid === recoveryStid)
    candidate.peId := row.peId
    candidate.stid := row.stid
    candidate.epoch := row.epoch
    candidate.transactionId := row.transactionId
    candidate.member := row.member
    candidate.reservation := row.reservation
  }

  val pickToken = picker.io.pick.bits
  val pickClassIndex = pickToken.query.uopClass.asUInt
  val pickClassInRange = pickClassIndex < p.iqClassCount.U
  val pickBankInRange = pickToken.query.bank < p.iqBankCount.U
  val pickEntryInRange = pickToken.query.entry < p.iqEntriesPerBank.U
  val safePickClass = Mux(pickClassInRange, pickClassIndex, 0.U)
  val safePickBank = Mux(pickBankInRange, pickToken.query.bank, 0.U)
  val safePickEntry = Mux(pickEntryInRange, pickToken.query.entry, 0.U)
  val pickRow = scheduleRows(safePickClass)(safePickBank)(safePickEntry)
  val pickResidentExact = pickClassInRange && pickBankInRange &&
    pickEntryInRange && slotState(safePickClass)(safePickBank)(safePickEntry) ===
      OooIexIssueSlotState.ResidentS3 && pickRow.valid
  val pickIdentityExact = pickToken.candidate.peId === pickRow.peId &&
    pickToken.candidate.stid === pickRow.stid &&
    pickToken.candidate.epoch === pickRow.epoch &&
    pickToken.candidate.transactionId === pickRow.transactionId &&
    sameMember(pickToken.candidate.member, pickRow.member) &&
    pickToken.candidate.reservation.asUInt === pickRow.reservation.asUInt &&
    pickToken.query.uopClass === pickRow.reservation.uopClass &&
    pickToken.query.bank === pickRow.reservation.bank &&
    pickToken.query.entry === pickRow.reservation.speculativeSlot
  val pickNotInFlight = !pickRow.inFlight
  val pickClaimExact = pickResidentExact && pickIdentityExact &&
    pickNotInFlight &&
    !(recoveryFreeze && pickToken.candidate.stid === recoveryStid)

  io.pick.valid := picker.io.pick.valid && pickClaimExact
  io.pick.bits := pickToken
  // A stale retained token is consumed as a typed rejection so corruption or
  // an unexpected lifecycle race cannot wedge the domain indefinitely.
  picker.io.pick.ready := Mux(pickClaimExact, io.pick.ready, true.B)
  io.pickRejected.valid := picker.io.pick.valid && !pickClaimExact
  io.pickRejected.bits.token := pickToken
  io.pickRejected.bits.residentExact := pickResidentExact
  io.pickRejected.bits.identityExact := pickIdentityExact
  io.pickRejected.bits.notInFlight := pickNotInFlight
  io.pickMalformed := picker.io.malformed
  io.pickRecoveryCanceled := picker.io.recoveryCanceled
  io.pickRecoveryBlocked := picker.io.blockedCanceled

  when(io.pick.fire) {
    scheduleRows(safePickClass)(safePickBank)(safePickEntry).inFlight := true.B
  }

  val retry = io.pickRetry.bits
  val retryClass = retry.reservation.uopClass.asUInt
  val retryClassInRange = retryClass < p.iqClassCount.U
  val retryBankInRange = retry.reservation.bank < p.iqBankCount.U
  val retryEntryInRange =
    retry.reservation.speculativeSlot < p.iqEntriesPerBank.U
  val safeRetryClass = Mux(retryClassInRange, retryClass, 0.U)
  val safeRetryBank = Mux(retryBankInRange, retry.reservation.bank, 0.U)
  val safeRetryEntry = Mux(
    retryEntryInRange, retry.reservation.speculativeSlot, 0.U)
  val retryRow = scheduleRows(safeRetryClass)(safeRetryBank)(safeRetryEntry)
  val retryResidentExact = retryClassInRange && retryBankInRange &&
    retryEntryInRange &&
    slotState(safeRetryClass)(safeRetryBank)(safeRetryEntry) ===
      OooIexIssueSlotState.ResidentS3 && retryRow.valid
  val retryIdentityExact = sameMember(retry.member, retryRow.member) &&
    retry.reservation.asUInt === retryRow.reservation.asUInt
  val retryClaimsCurrentPick = io.pick.fire &&
    safeRetryClass === safePickClass && safeRetryBank === safePickBank &&
    safeRetryEntry === safePickEntry &&
    sameMember(retry.member, pickToken.candidate.member) &&
    retry.reservation.asUInt === pickToken.candidate.reservation.asUInt
  // A fail-closed P1/join rejection can return the exact claim on the same
  // edge that the picker fires. Treat that edge as in-flight so the later
  // retry assignment wins and the canonical row remains immediately pickable.
  val retryWasInFlight = retryRow.inFlight || retryClaimsCurrentPick
  val retryExact = retryResidentExact && retryIdentityExact &&
    retryWasInFlight &&
    !(recoveryFreeze && retry.member.group.stid === recoveryStid)
  io.pickRetryRejected.valid := io.pickRetry.valid && !retryExact
  io.pickRetryRejected.bits.retry := retry
  io.pickRetryRejected.bits.residentExact := retryResidentExact
  io.pickRetryRejected.bits.identityExact := retryIdentityExact
  io.pickRetryRejected.bits.wasInFlight := retryWasInFlight
  when(io.pickRetry.valid && retryExact) {
    scheduleRows(safeRetryClass)(safeRetryBank)(safeRetryEntry).inFlight :=
      false.B
  }

  val queryClass = io.query.uopClass.asUInt
  val queryClassInRange = queryClass < p.iqClassCount.U
  val queryBankInRange = io.query.bank < p.iqBankCount.U
  val queryEntryInRange = io.query.entry < p.iqEntriesPerBank.U
  val safeQueryClass = Mux(queryClassInRange, queryClass, 0.U)
  val safeQueryBank = Mux(queryBankInRange, io.query.bank, 0.U)
  val safeQueryEntry = Mux(queryEntryInRange, io.query.entry, 0.U)
  io.queryState := slotState(safeQueryClass)(safeQueryBank)(safeQueryEntry)
  val queryPayloads = Wire(Vec(p.iqClassCount,
    Vec(p.iqBankCount, new OooIexPayloadSidecar(p))))
  for (uopClass <- 0 until p.iqClassCount; bank <- 0 until p.iqBankCount) {
    queryPayloads(uopClass)(bank) :=
      payloadRows(uopClass)(bank).read(safeQueryEntry)
  }
  io.queryRow.schedule :=
    scheduleRows(safeQueryClass)(safeQueryBank)(safeQueryEntry)
  io.queryRow.payload := queryPayloads(safeQueryClass)(safeQueryBank)
  val querySourcesReady = io.queryRow.sources.map { source =>
    !source.valid || source.ready
  }.reduce(_ && _)
  io.queryPickable := queryClassInRange && queryBankInRange &&
    queryEntryInRange && io.queryState === OooIexIssueSlotState.ResidentS3 &&
    io.queryRow.valid && !io.queryRow.schedule.inFlight && querySourcesReady &&
    !(recoveryFreeze && io.queryRow.stid === recoveryStid)

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
    io.inFlightEntries(uopClass)(bank) := PopCount(VecInit(
      (0 until p.iqEntriesPerBank).map { entry =>
        scheduleRows(uopClass)(bank)(entry).valid &&
          scheduleRows(uopClass)(bank)(entry).inFlight
      }).asUInt)
    for (entry <- 0 until p.iqEntriesPerBank) {
      assert((slotState(uopClass)(bank)(entry) === OooIexIssueSlotState.Free) ===
        !scheduleRows(uopClass)(bank)(entry).valid,
        "a physical IEX row must agree with its lifecycle state")
      assert(!s1Claimed(uopClass)(bank)(entry) ||
        slotState(uopClass)(bank)(entry) === OooIexIssueSlotState.Free,
        "an S1 claim may reserve only an otherwise free physical IEX row")
      assert(!scheduleRows(uopClass)(bank)(entry).inFlight ||
        (scheduleRows(uopClass)(bank)(entry).valid &&
          slotState(uopClass)(bank)(entry) ===
            OooIexIssueSlotState.ResidentS3),
        "only one resident S3 row may own speculative issue in-flight state")
    }
  }
}
