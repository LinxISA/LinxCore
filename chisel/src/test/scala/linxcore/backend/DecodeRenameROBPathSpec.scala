package linxcore.backend

import circt.stage.ChiselStage
import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.commit.CommitTraceParams
import linxcore.common.{DestinationKind, InterfaceParams}
import linxcore.frontend.{F4Slot, FrontendOpcodeDecodeTable}
import linxcore.rename.{ScalarTURenameBridgeIO, TULinkFlushSequencePublisherReference, TULinkFlushSourceSelectorReference}
import linxcore.rob.{ROBID, ROBIDValue}
import org.scalatest.funsuite.AnyFunSuite

class DecodeRenameROBPathIdentityProbeIO extends Bundle {
  val decodeValid = Input(Bool())
  val usePredecoded = Input(Bool())
  val decodeInsn = Input(UInt(64.W))
  val decodeLenBytes = Input(UInt(4.W))
  val decodePc = Input(UInt(64.W))
  val decodeLast = Input(Bool())
  val flushValid = Input(Bool())
  val renamedOutReady = Input(Bool())
  val decodeReady = Output(Bool())
  val selectedValid = Output(Bool())
  val selectedSlot = Output(UInt(2.W))
  val decodedValidMask = Output(UInt(4.W))
  val selectedRobValue = Output(UInt(4.W))
  val selectedBlockBid = Output(UInt(64.W))
  val renamedAccepted = Output(Bool())
  val renamedPc = Output(UInt(64.W))
  val renamedPredictionTag = Output(UInt(64.W))
  val storeDispatchFire = Output(Bool())
  val storeDispatchSplit = Output(Bool())
  val storeStaEnqueueFire = Output(Bool())
  val storeStdEnqueueFire = Output(Bool())
  val storeStaQueueValid = Output(Bool())
  val storeStdQueueValid = Output(Bool())
  val storeStaQueueCount = Output(UInt(3.W))
  val storeStdQueueCount = Output(UInt(3.W))
  val storeStaQueueRid = Output(UInt(4.W))
  val storeStdQueueRid = Output(UInt(4.W))
  val storeStaQueueLsId = Output(UInt(32.W))
  val storeStdQueueLsId = Output(UInt(32.W))

  val completeValid = Input(Bool())
  val completeRobValue = Input(UInt(4.W))
  val completeBlockBid = Input(UInt(64.W))
  val completePc = Input(UInt(64.W))

  val commandValid = Output(Bool())
  val commandKind = Output(DestinationKind())
  val commandSeqWrap = Output(Bool())
  val commandSeqValue = Output(UInt(3.W))
  val commandDealloc = Output(Bool())
  val commandPeId = Output(UInt(8.W))
  val commandStid = Output(UInt(8.W))
  val commandFire = Output(Bool())
  val tDeallocSeqWrap = Output(Bool())
  val tDeallocSeqValue = Output(UInt(3.W))
  val uDeallocSeqWrap = Output(Bool())
  val uDeallocSeqValue = Output(UInt(3.W))
  val tValidMask = Output(UInt(8.W))
  val uValidMask = Output(UInt(8.W))
  val tRetiredMask = Output(UInt(8.W))
  val uRetiredMask = Output(UInt(8.W))
  val tUsedEntries = Output(UInt(6.W))
  val uUsedEntries = Output(UInt(6.W))
  val retireAccepted = Output(Bool())
  val retireReleaseMismatch = Output(Bool())
}

class DecodeRenameROBPathIdentityProbe(reducedStoreDispatchBypass: Boolean = true) extends Module {
  private val p = InterfaceParams(robEntries = 16, commitWidth = 2)
  private val trace = CommitTraceParams(
    commitWidth = p.commitWidth,
    robValueWidth = p.robIndexWidth,
    blockBidWidth = p.blockBidWidth,
    pcWidth = p.pcWidth,
    insnWidth = p.insnWidth,
    lenWidth = p.lenWidth)
  private val mapQDepth = 8

  val io = IO(new DecodeRenameROBPathIdentityProbeIO)

  private def zeroRobId: ROBID = 0.U.asTypeOf(new ROBID(p.robEntries))

  val path = Module(new DecodeRenameROBPath(
    p = p,
    traceParams = trace,
    mapQDepth = mapQDepth,
    gprMapQDepth = 16,
    decRenQueueDepth = 4,
    tuRetireSourceQueueDepth = 8,
    tuRetireRelationCmapDepth = 16,
    markerRetireSourceQueueDepth = 4,
    blockRenameCommitQueueDepth = 4,
    scalarContinuationGprCutThreshold = 16,
    scalarContinuationTuCutThreshold = 8,
    scalarStidCount = 2,
    useMarkerDecodeContext = false,
    skipBlockMarkers = true,
    reducedStoreDispatchBypass = reducedStoreDispatchBypass))
  path.io.predecodedD1Valid := io.usePredecoded && io.decodeValid
  path.io.predecodedD1 := 0.U.asTypeOf(path.io.predecodedD1)
  path.io.predecodedD1.validMask := "b0100".U
  path.io.predecodedD1.entries(2).valid := true.B
  path.io.predecodedD1.entries(2).pc := io.decodePc
  path.io.predecodedD1.entries(2).opcode := FrontendOpcodeDecodeTable.OP_ADDI.U
  path.io.predecodedD1.entries(2).insnRaw := io.decodeInsn
  path.io.predecodedD1.entries(2).insnLen := io.decodeLenBytes
  path.io.predecodedD1.entries(2).isLastInBlock := io.decodeLast
  path.io.predecodedD1.entries(2).threadId := 0.U
  path.io.predecodedD1.entries(2).uid.uid := 0x55.U
  path.io.predecodedD1.entries(2).uid.fetchSlot := 2.U
  path.io.predecodedD1.entries(2).prediction.valid := true.B
  path.io.predecodedD1.entries(2).prediction.predictionTag := 0xabc.U
  path.io.predecodedD1.entries(2).prediction.fetchSeq := 7.U
  path.io.predecodedD1.entries(2).prediction.transactionId := 9.U
  path.io.predecodedD1.entries(2).prediction.epoch := 2.U
  path.io.predecodedD1.meta(2).valid := true.B
  path.io.predecodedD1.meta(2).opcode := FrontendOpcodeDecodeTable.OP_ADDI.U
  path.io.predecodedNextValid := false.B
  path.io.predecodedNext := 0.U.asTypeOf(path.io.predecodedNext)

  path.io.d1 := 0.U.asTypeOf(path.io.d1)
  path.io.d1.valid := io.decodeValid
  path.io.d1.peId := 0.U
  path.io.d1.threadId := 0.U
  path.io.d1.pc := io.decodePc
  for (slot <- 0 until p.decodeWidth) {
    path.io.slots(slot) := 0.U.asTypeOf(new F4Slot(p))
    path.io.slots(slot).pc := io.decodePc
    path.io.slots(slot).uopUid := slot.U
  }
  path.io.slots(0).valid := io.decodeValid
  path.io.slots(0).insnRaw := io.decodeInsn
  path.io.slots(0).lenBytes := io.decodeLenBytes
  path.io.slots(0).isLastInBlock := io.decodeLast
  path.io.validMask := Mux(io.decodeValid, 1.U, 0.U)
  path.io.samePacketNextSlotValid := false.B
  path.io.samePacketNextSlot := 0.U.asTypeOf(new F4Slot(p))

  path.io.flushValid := io.flushValid
  path.io.recoveryBlockQueryValid := false.B
  path.io.recoveryBlockQueryBid := 0.U
  path.io.recoveryBlockQueryStid := 0.U
  path.io.commitHold := false.B
  path.io.blockExplicitStoreCountValid := false.B
  path.io.blockExplicitStoreCountBid := 0.U
  path.io.blockExplicitStoreCountStid := 0.U
  path.io.blockExplicitStoreCountValue := 0.U
  path.io.renamedOutReady := io.renamedOutReady
  path.io.storeStaExec := 0.U.asTypeOf(path.io.storeStaExec)
  path.io.storeStdExec := 0.U.asTypeOf(path.io.storeStdExec)
  path.io.storeScResultValid := false.B
  path.io.storeScResultSuccess := false.B
  path.io.storeScResultIdentity := 0.U.asTypeOf(path.io.storeScResultIdentity)
  path.io.storeScStoreData := 0.U
  path.io.storeAddressInsertPermit := true.B
  path.io.storeMarkCommitValid := false.B
  path.io.storeMarkCommitIndex := 0.U
  path.io.storeCommitFreeValid := false.B
  path.io.storeCommitFreeIndex := 0.U
  path.io.storeCommitFreeMaskValid := false.B
  path.io.storeCommitFreeMask := 0.U
  path.io.checkpointValid := false.B
  path.io.checkpointBid := zeroRobId
  path.io.checkpointStid := 0.U
  path.io.commitValid := false.B
  path.io.commitBid := zeroRobId
  path.io.commitBlockBid := 0.U
  path.io.commitStid := 0.U
  path.io.recoveryNonLsuSources.foreach(_ := 0.U.asTypeOf(path.io.recoveryNonLsuSources.head))
  path.io.directBccRecoveryMiss := 0.U.asTypeOf(path.io.directBccRecoveryMiss)
  path.io.directIexSlowRecovery := 0.U.asTypeOf(path.io.directIexSlowRecovery)
  path.io.directIexIqStalled := false.B
  path.io.directIexIqProgress := false.B
  path.io.directIexIqStid := 0.U
  path.io.directIexIqPeId := 0.U
  path.io.directIexIqTid := 0.U
  path.io.directPeMismatchRecovery := 0.U.asTypeOf(path.io.directPeMismatchRecovery)
  path.io.lsuRecoverySource := 0.U.asTypeOf(path.io.lsuRecoverySource)
  path.io.lsuFullBidLookupRequest := 0.U.asTypeOf(path.io.lsuFullBidLookupRequest)
  path.io.recoveryIntentReady := true.B
  path.io.scalarCleanupOrderValid := false.B
  path.io.scalarCleanupOrder := 0.U
  path.io.completeValid := io.completeValid
  path.io.completeRobValue := io.completeRobValue
  path.io.completeRowValid := io.completeValid
  path.io.completeRow := 0.U.asTypeOf(path.io.completeRow)
  path.io.completeRow.valid := io.completeValid
  path.io.completeRow.blockBidValid := io.completeValid
  path.io.completeRow.blockBid := io.completeBlockBid
  path.io.completeRow.pc := io.completePc
  path.io.blockBranchTakenValid := false.B
  path.io.blockBranchTaken := false.B
  path.io.scalarRedirectValid := false.B
  path.io.scalarRedirectStid := 0.U
  path.io.deallocReady := true.B
  path.io.deallocHoldMask := 0.U
  path.io.robStatusLookupValid := false.B
  path.io.robStatusLookupRid := zeroRobId
  path.io.robCommitTraceLookupValid := false.B
  path.io.robCommitTraceLookupRid := zeroRobId
  path.io.robCommitTraceLookupSourceTraceEnable := false.B
  path.io.recoveryNonLsuSources.foreach(_.valid := false.B)
  path.io.directBccRecoveryMiss.valid := false.B
  path.io.directIexSlowRecovery.valid := false.B
  path.io.directPeMismatchRecovery.valid := false.B
  path.io.lsuRecoverySource.valid := false.B

  io.decodeReady := path.io.decodeReady
  io.selectedValid := path.io.selectedValid
  io.selectedSlot := path.io.selectedSlot
  io.decodedValidMask := path.io.decodedValidMask
  io.selectedRobValue := path.io.selectedRobValue
  io.selectedBlockBid := path.io.selectedBlockBid
  io.renamedAccepted := path.io.accepted
  io.renamedPc := path.io.renamedOut.pc
  io.renamedPredictionTag := path.io.renamedOut.prediction.predictionTag
  io.storeDispatchFire := path.io.storeDispatchFire
  io.storeDispatchSplit := path.io.storeDispatchSplit
  io.storeStaEnqueueFire := path.io.storeStaEnqueueFire
  io.storeStdEnqueueFire := path.io.storeStdEnqueueFire
  io.storeStaQueueValid := path.io.storeStaQueueValid
  io.storeStdQueueValid := path.io.storeStdQueueValid
  io.storeStaQueueCount := path.io.storeStaQueueCount
  io.storeStdQueueCount := path.io.storeStdQueueCount
  io.storeStaQueueRid := path.io.storeStaQueue.uop.rid.value
  io.storeStdQueueRid := path.io.storeStdQueue.uop.rid.value
  io.storeStaQueueLsId := path.io.storeStaQueue.uop.lsid
  io.storeStdQueueLsId := path.io.storeStdQueue.uop.lsid
  io.commandValid := path.io.tuRetireCommandValid
  io.commandKind := path.io.tuRetireCommandKind
  io.commandSeqWrap := path.io.tuRetireCommandSeq.wrap
  io.commandSeqValue := path.io.tuRetireCommandSeq.value
  io.commandDealloc := path.io.tuRetireCommandDealloc
  io.commandPeId := path.io.tuRetireCommandPeId
  io.commandStid := path.io.tuRetireCommandStid
  io.commandFire := path.io.tuRetireCommandFire
  io.tDeallocSeqWrap := path.io.tuRetireSelectedTDeallocSeq.wrap
  io.tDeallocSeqValue := path.io.tuRetireSelectedTDeallocSeq.value
  io.uDeallocSeqWrap := path.io.tuRetireSelectedUDeallocSeq.wrap
  io.uDeallocSeqValue := path.io.tuRetireSelectedUDeallocSeq.value
  io.tValidMask := path.io.tuRetireSelectedTValidMask
  io.uValidMask := path.io.tuRetireSelectedUValidMask
  io.tRetiredMask := path.io.tuRetireSelectedTRetiredMask
  io.uRetiredMask := path.io.tuRetireSelectedURetiredMask
  io.tUsedEntries := path.io.tuRenameTUsedEntries
  io.uUsedEntries := path.io.tuRenameUUsedEntries
  io.retireAccepted := path.io.tuRetireAccepted
  io.retireReleaseMismatch := path.io.tuRetireReleaseMismatch
}

object DecodeRenameROBPathReference {
  def firstValidSlot(mask: Int, width: Int): Option[Int] = {
    require(width > 0)
    (0 until width).find(slot => ((mask >> slot) & 1) != 0)
  }

  def allocAttemptValid(
      inputValid: Boolean,
      maintenanceBusy: Boolean,
      unsupported: Boolean,
      canRename: Boolean,
      outReady: Boolean): Boolean =
    inputValid && !maintenanceBusy && !unsupported && canRename && outReady

  def accepted(attemptValid: Boolean, robReady: Boolean): Boolean =
    attemptValid && robReady

  def robReservationAttemptValid(
      inputValid: Boolean,
      queueReady: Boolean,
      gprReservationReady: Boolean = true,
      redirectClose: Boolean = false): Boolean =
    inputValid && queueReady && gprReservationReady && !redirectClose

  def decodeReady(
      queueReady: Boolean,
      robReady: Boolean,
      gprReservationReady: Boolean = true,
      redirectClose: Boolean = false): Boolean =
    queueReady && robReady && gprReservationReady && !redirectClose

  def gprReservationReady(
      globalPending: Int,
      selectedLanePending: Int,
      selectedCount: Int,
      freePhys: Int,
      selectedLaneFreeMapQ: Int): Boolean = {
    require(0 <= selectedCount && selectedCount <= 2)
    globalPending + selectedCount <= freePhys &&
      selectedLanePending + selectedCount <= selectedLaneFreeMapQ
  }

  def closesActiveRedirectTarget(
      selectedValid: Boolean,
      selectedMarker: Boolean,
      selectedTemplateBoundary: Boolean,
      selectedPc: BigInt,
      activeValid: Boolean,
      activeTarget: BigInt,
      activeCond: Boolean,
      activeUnconditionalRedirect: Boolean,
      branchValid: Boolean,
      branchTaken: Boolean): Boolean =
    selectedValid && !selectedMarker && activeValid && activeTarget != 0 &&
      (selectedPc == activeTarget || selectedTemplateBoundary) &&
      (activeUnconditionalRedirect || (activeCond && branchValid && branchTaken))

  def activeRedirectNeedsTransfer(
      closesActiveRedirect: Boolean,
      selectedPc: BigInt,
      activeTarget: BigInt): Boolean =
    closesActiveRedirect && selectedPc != activeTarget

  def closingBlockBid(
      useMarkerDecodeContext: Boolean,
      markerDecodeActiveBid: BigInt,
      lifecycleActiveBid: BigInt): BigInt =
    if (useMarkerDecodeContext) markerDecodeActiveBid else lifecycleActiveBid

  def fretOwnsConditionalFallback(
      isFretStk: Boolean,
      activeValid: Boolean,
      activeCond: Boolean,
      activeTarget: BigInt): Boolean =
    isFretStk && activeValid && activeCond && activeTarget != 0

  def queuePushReady(count: Int, depth: Int, popFire: Boolean, flush: Boolean = false): Boolean = {
    require(depth > 0 && (depth & (depth - 1)) == 0)
    !flush && (count < depth || popFire)
  }

  def storeDispatchReady(
      valid: Boolean,
      isStore: Boolean,
      split: Boolean,
      staReady: Boolean,
      stdReady: Boolean,
      bypass: Boolean = false): Boolean = {
    val active = valid && isStore
    bypass || !active || (if (split) staReady && stdReady else staReady)
  }

  def markerRowConsumesRename(valid: Boolean, sob: Boolean, eob: Boolean, completionPending: Boolean): Boolean =
    valid && (sob || eob) && !completionPending

  def markerRowVisibleToScalarIssue(outValid: Boolean, sob: Boolean, eob: Boolean): Boolean =
    outValid && !(sob || eob)

  def markerCompletionUsesPort(pending: Boolean, externalCompleteValid: Boolean): Boolean =
    pending && !externalCompleteValid

  def activeTuBankStid(threadId: Int): Int =
    threadId

  def activeTuBankPe(peId: Int): Int =
    peId

  final case class MarkerStep(
      doneValid: Boolean,
      doneBid: Option[BigInt],
      nextActiveValid: Boolean,
      nextActiveBid: Option[BigInt])

  final case class MarkerBoundaryDecision(
      alloc: Boolean,
      redirect: Boolean,
      doneBid: Option[BigInt],
      nextActiveBid: Option[BigInt],
      preRetire: Boolean = false)

  final case class MarkerConflictDecision(
      conflict: Boolean,
      markerReady: Boolean,
      decodeReady: Boolean,
      stopRedirect: Boolean)

  final case class BlockRenameCommitQueueStep(
      nextQueue: Vector[BigInt],
      presentedBid: Option[BigInt],
      accepted: Boolean)

  final case class ScalarContinuationPressureState(gpr: Int = 0, t: Int = 0, u: Int = 0)

  final case class ScalarContinuationPressureStep(
      next: ScalarContinuationPressureState,
      gprCut: Boolean,
      tCut: Boolean,
      uCut: Boolean) {
    def ownershipCut: Boolean = gprCut || tCut || uCut
  }

  def scalarContinuationPressureStep(
      state: ScalarContinuationPressureState,
      usesExistingBlock: Boolean,
      boundary: Boolean,
      gprDst: Boolean,
      tDst: Boolean,
      uDst: Boolean,
      gprThreshold: Int,
      tuThreshold: Int): ScalarContinuationPressureStep = {
    require(gprThreshold > 1)
    require(tuThreshold > 1)
    val gprCut = usesExistingBlock && !boundary && gprDst && state.gpr == gprThreshold - 1
    val tCut = usesExistingBlock && !boundary && tDst && state.t == tuThreshold - 1
    val uCut = usesExistingBlock && !boundary && uDst && state.u == tuThreshold - 1
    val cut = gprCut || tCut || uCut
    val next =
      if (boundary || cut) {
        ScalarContinuationPressureState()
      } else {
        ScalarContinuationPressureState(
          gpr = if (gprDst) { if (usesExistingBlock) state.gpr + 1 else 1 } else if (usesExistingBlock) state.gpr else 0,
          t = if (tDst) { if (usesExistingBlock) state.t + 1 else 1 } else if (usesExistingBlock) state.t else 0,
          u = if (uDst) { if (usesExistingBlock) state.u + 1 else 1 } else if (usesExistingBlock) state.u else 0)
      }
    ScalarContinuationPressureStep(next, gprCut = gprCut, tCut = tCut, uCut = uCut)
  }

  def scalarStartLifecycleStep(
      activeValid: Boolean,
      activeBid: BigInt,
      scalarRedirectValid: Boolean,
      scalarAllocFire: Boolean,
      scalarAllocBid: BigInt,
      robBlockLastFire: Boolean,
      robBlockLastBid: BigInt): MarkerStep = {
    val clearsActive = robBlockLastFire && activeValid && robBlockLastBid == activeBid
    val nextActive =
      if (scalarRedirectValid && activeValid) None
      else if (scalarAllocFire && !activeValid) Some(scalarAllocBid)
      else if (clearsActive) None
      else if (activeValid) Some(activeBid)
      else None

    val redirectClosesActive = scalarRedirectValid && activeValid
    MarkerStep(
      doneValid = redirectClosesActive || robBlockLastFire,
      doneBid =
        if (redirectClosesActive) Some(activeBid)
        else if (robBlockLastFire) Some(robBlockLastBid)
        else None,
      nextActiveValid = nextActive.nonEmpty,
      nextActiveBid = nextActive
    )
  }

  def markerLifecycleStep(
      activeValid: Boolean,
      activeBid: BigInt,
      markerBoundary: Boolean,
      markerStop: Boolean,
      allocBid: BigInt): MarkerStep = {
    val done = activeValid && (markerBoundary || markerStop)
    val nextActive =
      if (markerBoundary) Some(allocBid)
      else if (markerStop) None
      else if (activeValid) Some(activeBid)
      else None

    MarkerStep(
      doneValid = done,
      doneBid = if (done) Some(activeBid) else None,
      nextActiveValid = nextActive.nonEmpty,
      nextActiveBid = nextActive
    )
  }

  def markerBoundaryDecision(
      activeValid: Boolean,
      activeBid: BigInt,
      activeCond: Boolean,
      activeUnconditionalRedirect: Boolean,
      activeTarget: BigInt,
      branchValid: Boolean,
      branchTaken: Boolean,
      allocBid: BigInt,
      allocReady: Boolean = true,
      retirePending: Boolean = false,
      entries: Int = 8,
      scalarWorkPending: Boolean = true): MarkerBoundaryDecision = {
    val unconditionalRedirect = activeValid && activeUnconditionalRedirect && activeTarget != 0
    val needsBranchDecision = activeValid && activeCond && activeTarget != 0 && (branchValid || scalarWorkPending)
    val redirect = unconditionalRedirect || (needsBranchDecision && branchValid && branchTaken)
    val fallthrough = !unconditionalRedirect && (!needsBranchDecision || (branchValid && !branchTaken))
    val alloc = fallthrough && allocReady
    val sameSlot = (activeBid % entries) == (allocBid % entries)
    val preRetire = activeValid && fallthrough && !allocReady && sameSlot && !retirePending
    val nextActive =
      if (alloc) Some(allocBid)
      else if (redirect) None
      else if (activeValid) Some(activeBid)
      else None

    MarkerBoundaryDecision(
      alloc = alloc,
      redirect = redirect,
      doneBid = if (activeValid && (alloc || redirect || preRetire)) Some(activeBid) else None,
      nextActiveBid = nextActive,
      preRetire = preRetire
    )
  }

  def markerDecodeConflictDecision(
      markerOnlyPacket: Boolean,
      markerStop: Boolean,
      markerBoundaryRedirect: Boolean,
      robBlockLastValid: Boolean,
      robBlockLastBid: BigInt,
      robBlockLastStid: Int,
      activeValid: Boolean,
      activeBid: BigInt,
      markerStid: Int,
      blanketConflict: Boolean = false): MarkerConflictDecision = {
    val ownsBlockLast =
      markerOnlyPacket && activeValid && robBlockLastValid &&
        robBlockLastBid == activeBid && robBlockLastStid == markerStid
    val conflict = robBlockLastValid && !(ownsBlockLast && !blanketConflict)
    val markerReady = !conflict && (markerStop || markerBoundaryRedirect)
    MarkerConflictDecision(
      conflict = conflict,
      markerReady = markerReady,
      decodeReady = markerOnlyPacket && markerReady,
      stopRedirect = markerBoundaryRedirect && markerReady)
  }

  def blockRenameCommitQueueStep(
      queue: Vector[BigInt],
      depth: Int,
      retireValid: Boolean,
      retireBid: BigInt,
      externalCommitValid: Boolean,
      cleanupActive: Boolean,
      flush: Boolean = false): BlockRenameCommitQueueStep = {
    require(depth > 1 && (depth & (depth - 1)) == 0)
    if (flush) {
      BlockRenameCommitQueueStep(Vector.empty, None, accepted = false)
    } else {
      val presented = queue.headOption
      val accepted = presented.nonEmpty && !externalCommitValid && !cleanupActive
      val afterDeq = if (accepted) queue.tail else queue
      val afterEnq =
        if (retireValid) {
          require(afterDeq.size < depth, "block rename commit queue overflow")
          afterDeq :+ retireBid
        } else {
          afterDeq
        }
      BlockRenameCommitQueueStep(afterEnq, presented, accepted)
    }
  }
}

class DecodeRenameROBPathSpec extends AnyFunSuite with ChiselSim {
  import DecodeRenameROBPathReference._

  test("reference selects the oldest decoded slot without compacting later slots") {
    assert(firstValidSlot(0x0, width = 4).isEmpty)
    assert(firstValidSlot(0x1, width = 4).contains(0))
    assert(firstValidSlot(0xa, width = 4).contains(1))
    assert(firstValidSlot(0xc, width = 4).contains(2))
  }

  test("FRET inherits fallback only from a live conditional marker") {
    assert(fretOwnsConditionalFallback(
      isFretStk = true, activeValid = true, activeCond = true, activeTarget = 0x1271e))
    assert(!fretOwnsConditionalFallback(
      isFretStk = true, activeValid = true, activeCond = false, activeTarget = 0x13e1e))
    assert(!fretOwnsConditionalFallback(
      isFretStk = false, activeValid = true, activeCond = true, activeTarget = 0x1271e))
    assert(!fretOwnsConditionalFallback(
      isFretStk = true, activeValid = true, activeCond = true, activeTarget = 0))
  }

  test("reference keeps ROB allocation attempt independent of allocator ready") {
    assert(allocAttemptValid(inputValid = true, maintenanceBusy = false, unsupported = false, canRename = true, outReady = true))
    assert(!accepted(attemptValid = true, robReady = false))
    assert(accepted(attemptValid = true, robReady = true))
    assert(!allocAttemptValid(inputValid = true, maintenanceBusy = false, unsupported = false, canRename = true, outReady = false))
    assert(!allocAttemptValid(inputValid = true, maintenanceBusy = true, unsupported = false, canRename = true, outReady = true))
  }

  test("reference reserves ROB/BROB before decode enters the dec-ren queue") {
    assert(robReservationAttemptValid(inputValid = true, queueReady = true))
    assert(!robReservationAttemptValid(inputValid = true, queueReady = false))
    assert(!robReservationAttemptValid(inputValid = false, queueReady = true))
    assert(decodeReady(queueReady = true, robReady = true))
    assert(!decodeReady(queueReady = true, robReady = false))
    assert(!decodeReady(queueReady = false, robReady = true))
  }

  test("reference gates ROB/BROB reservation on queued scalar GPR rename capacity") {
    assert(gprReservationReady(
      globalPending = 0, selectedLanePending = 0, selectedCount = 1,
      freePhys = 1, selectedLaneFreeMapQ = 1))
    assert(gprReservationReady(
      globalPending = 1, selectedLanePending = 1, selectedCount = 0,
      freePhys = 1, selectedLaneFreeMapQ = 1))
    assert(!gprReservationReady(
      globalPending = 1, selectedLanePending = 0, selectedCount = 1,
      freePhys = 1, selectedLaneFreeMapQ = 2))
    assert(!gprReservationReady(
      globalPending = 1, selectedLanePending = 1, selectedCount = 1,
      freePhys = 2, selectedLaneFreeMapQ = 1))
    assert(gprReservationReady(
      globalPending = 1, selectedLanePending = 0, selectedCount = 1,
      freePhys = 2, selectedLaneFreeMapQ = 1))
    assert(gprReservationReady(
      globalPending = 0, selectedLanePending = 0, selectedCount = 2,
      freePhys = 2, selectedLaneFreeMapQ = 2))
    assert(!gprReservationReady(
      globalPending = 0, selectedLanePending = 0, selectedCount = 2,
      freePhys = 1, selectedLaneFreeMapQ = 2))
    assert(!gprReservationReady(
      globalPending = 0, selectedLanePending = 0, selectedCount = 2,
      freePhys = 2, selectedLaneFreeMapQ = 1))
    assert(!robReservationAttemptValid(inputValid = true, queueReady = true, gprReservationReady = false))
    assert(!decodeReady(queueReady = true, robReady = true, gprReservationReady = false))
  }

  test("reference tracks scalar continuation GPR, T, and U pressure independently") {
    val gpr0 = scalarContinuationPressureStep(
      ScalarContinuationPressureState(), usesExistingBlock = true, boundary = false,
      gprDst = true, tDst = false, uDst = false, gprThreshold = 2, tuThreshold = 3)
    assert(gpr0.next == ScalarContinuationPressureState(gpr = 1))
    val t0 = scalarContinuationPressureStep(
      gpr0.next, usesExistingBlock = true, boundary = false,
      gprDst = false, tDst = true, uDst = false, gprThreshold = 2, tuThreshold = 3)
    assert(t0.next == ScalarContinuationPressureState(gpr = 1, t = 1))
    val u0 = scalarContinuationPressureStep(
      t0.next, usesExistingBlock = true, boundary = false,
      gprDst = false, tDst = false, uDst = true, gprThreshold = 2, tuThreshold = 3)
    assert(u0.next == ScalarContinuationPressureState(gpr = 1, t = 1, u = 1))

    val gprCut = scalarContinuationPressureStep(
      u0.next, usesExistingBlock = true, boundary = false,
      gprDst = true, tDst = false, uDst = false, gprThreshold = 2, tuThreshold = 3)
    assert(gprCut.gprCut)
    assert(!gprCut.tCut && !gprCut.uCut)
    assert(gprCut.next == ScalarContinuationPressureState())

    val t1 = scalarContinuationPressureStep(
      ScalarContinuationPressureState(t = 1, u = 1), usesExistingBlock = true, boundary = false,
      gprDst = false, tDst = true, uDst = false, gprThreshold = 2, tuThreshold = 3)
    assert(!t1.ownershipCut)
    val tCut = scalarContinuationPressureStep(
      t1.next, usesExistingBlock = true, boundary = false,
      gprDst = false, tDst = true, uDst = false, gprThreshold = 2, tuThreshold = 3)
    assert(tCut.tCut)
    assert(!tCut.gprCut && !tCut.uCut)
    assert(tCut.next == ScalarContinuationPressureState())
  }

  test("reference closes active redirect blocks before admitting the target scalar row") {
    val directClose = closesActiveRedirectTarget(
      selectedValid = true,
      selectedMarker = false,
      selectedTemplateBoundary = false,
      selectedPc = 0x40005f2cL,
      activeValid = true,
      activeTarget = 0x40005f2cL,
      activeCond = false,
      activeUnconditionalRedirect = true,
      branchValid = false,
      branchTaken = false)
    assert(directClose)
    assert(!robReservationAttemptValid(inputValid = true, queueReady = true, redirectClose = directClose))
    assert(!decodeReady(queueReady = true, robReady = true, redirectClose = directClose))

    val condTakenClose = closesActiveRedirectTarget(
      selectedValid = true,
      selectedMarker = false,
      selectedTemplateBoundary = false,
      selectedPc = 0x40005f2cL,
      activeValid = true,
      activeTarget = 0x40005f2cL,
      activeCond = true,
      activeUnconditionalRedirect = false,
      branchValid = true,
      branchTaken = true)
    assert(condTakenClose)

    assert(!closesActiveRedirectTarget(
      selectedValid = true,
      selectedMarker = true,
      selectedTemplateBoundary = false,
      selectedPc = 0x40005f2cL,
      activeValid = true,
      activeTarget = 0x40005f2cL,
      activeCond = false,
      activeUnconditionalRedirect = true,
      branchValid = false,
      branchTaken = false))
    assert(!closesActiveRedirectTarget(
      selectedValid = true,
      selectedMarker = false,
      selectedTemplateBoundary = false,
      selectedPc = 0x40005f2cL,
      activeValid = true,
      activeTarget = 0x40005f2cL,
      activeCond = true,
      activeUnconditionalRedirect = false,
      branchValid = true,
      branchTaken = false))
  }

  test("reference closes a pending CALL before admitting a following executable template boundary") {
    val portableMallocCallClose = closesActiveRedirectTarget(
      selectedValid = true,
      selectedMarker = false,
      selectedTemplateBoundary = true,
      selectedPc = 0x1272aL,
      activeValid = true,
      activeTarget = 0x13e1eL,
      activeCond = false,
      activeUnconditionalRedirect = true,
      branchValid = false,
      branchTaken = false)
    assert(portableMallocCallClose)
    assert(activeRedirectNeedsTransfer(
      closesActiveRedirect = portableMallocCallClose,
      selectedPc = 0x1272aL,
      activeTarget = 0x13e1eL))
    assert(!robReservationAttemptValid(
      inputValid = true,
      queueReady = true,
      redirectClose = portableMallocCallClose))
    assert(!decodeReady(
      queueReady = true,
      robReady = true,
      redirectClose = portableMallocCallClose))

    assert(!closesActiveRedirectTarget(
      selectedValid = true,
      selectedMarker = false,
      selectedTemplateBoundary = true,
      selectedPc = 0x1272aL,
      activeValid = true,
      activeTarget = 0x13e1eL,
      activeCond = true,
      activeUnconditionalRedirect = false,
      branchValid = true,
      branchTaken = false))

    assert(!activeRedirectNeedsTransfer(
      closesActiveRedirect = true,
      selectedPc = 0x13e1eL,
      activeTarget = 0x13e1eL))
    assert(closingBlockBid(
      useMarkerDecodeContext = false,
      markerDecodeActiveBid = 0,
      lifecycleActiveBid = 0x35L) == 0x35L)
    assert(closingBlockBid(
      useMarkerDecodeContext = true,
      markerDecodeActiveBid = 0x35L,
      lifecycleActiveBid = 0) == 0x35L)
  }

  test("reference retries internal block rename commits while cleanup blocks GPR commit") {
    val depth = 4
    val s0 = blockRenameCommitQueueStep(
      queue = Vector.empty,
      depth = depth,
      retireValid = true,
      retireBid = 0x178,
      externalCommitValid = false,
      cleanupActive = true)
    assert(s0.presentedBid.isEmpty)
    assert(!s0.accepted)
    assert(s0.nextQueue == Vector(BigInt(0x178)))

    val s1 = blockRenameCommitQueueStep(
      queue = s0.nextQueue,
      depth = depth,
      retireValid = false,
      retireBid = 0,
      externalCommitValid = false,
      cleanupActive = true)
    assert(s1.presentedBid.contains(BigInt(0x178)))
    assert(!s1.accepted)
    assert(s1.nextQueue == Vector(BigInt(0x178)))

    val s2 = blockRenameCommitQueueStep(
      queue = s1.nextQueue,
      depth = depth,
      retireValid = false,
      retireBid = 0,
      externalCommitValid = false,
      cleanupActive = false)
    assert(s2.presentedBid.contains(BigInt(0x178)))
    assert(s2.accepted)
    assert(s2.nextQueue.isEmpty)
  }

  test("reference admits decode only when the dec-ren queue can accept") {
    assert(queuePushReady(count = 0, depth = 4, popFire = false))
    assert(!queuePushReady(count = 4, depth = 4, popFire = false))
    assert(queuePushReady(count = 4, depth = 4, popFire = true))
    assert(!queuePushReady(count = 1, depth = 4, popFire = true, flush = true))
  }

  test("reference gates store dispatch like the model STA/STD split point") {
    assert(storeDispatchReady(valid = false, isStore = false, split = false, staReady = false, stdReady = false))
    assert(storeDispatchReady(valid = true, isStore = false, split = false, staReady = false, stdReady = false))
    assert(storeDispatchReady(valid = true, isStore = true, split = false, staReady = true, stdReady = false))
    assert(!storeDispatchReady(valid = true, isStore = true, split = false, staReady = false, stdReady = true))
    assert(storeDispatchReady(valid = true, isStore = true, split = true, staReady = true, stdReady = true))
    assert(!storeDispatchReady(valid = true, isStore = true, split = true, staReady = true, stdReady = false))
    assert(!storeDispatchReady(valid = true, isStore = true, split = true, staReady = false, stdReady = true))
    assert(storeDispatchReady(
      valid = true,
      isStore = true,
      split = true,
      staReady = false,
      stdReady = false,
      bypass = true))
  }

  test("reference internally consumes marker rows after rename instead of issuing them to scalar ALU") {
    assert(markerRowConsumesRename(valid = true, sob = true, eob = false, completionPending = false))
    assert(markerRowConsumesRename(valid = true, sob = false, eob = true, completionPending = false))
    assert(!markerRowConsumesRename(valid = true, sob = true, eob = false, completionPending = true))
    assert(!markerRowConsumesRename(valid = false, sob = true, eob = false, completionPending = false))

    assert(!markerRowVisibleToScalarIssue(outValid = true, sob = true, eob = false))
    assert(!markerRowVisibleToScalarIssue(outValid = true, sob = false, eob = true))
    assert(markerRowVisibleToScalarIssue(outValid = true, sob = false, eob = false))

    assert(markerCompletionUsesPort(pending = true, externalCompleteValid = false))
    assert(!markerCompletionUsesPort(pending = true, externalCompleteValid = true))
    assert(!markerCompletionUsesPort(pending = false, externalCompleteValid = false))
  }

  test("reference gives external execute completions priority over pending marker row completion") {
    val externalBusy = markerCompletionUsesPort(pending = true, externalCompleteValid = true)
    assert(!externalBusy)

    val nextCycle = markerCompletionUsesPort(pending = true, externalCompleteValid = false)
    assert(nextCycle)
  }

  test("reference forwards queued row thread ID as reduced T/U active STID") {
    assert(activeTuBankStid(threadId = 0) == 0)
    assert(activeTuBankStid(threadId = 3) == 3)
  }

  test("reference forwards queued row PE ID as reduced T/U active PE") {
    assert(activeTuBankPe(peId = 0) == 0)
    assert(activeTuBankPe(peId = 2) == 2)
  }

  test("reference marker lifecycle allocates new active BID and completes old/current BID") {
    val firstStart = markerLifecycleStep(
      activeValid = false,
      activeBid = 0,
      markerBoundary = true,
      markerStop = false,
      allocBid = 10)
    assert(!firstStart.doneValid)
    assert(firstStart.nextActiveBid.contains(10))

    val nextStart = markerLifecycleStep(
      activeValid = true,
      activeBid = 10,
      markerBoundary = true,
      markerStop = false,
      allocBid = 11)
    assert(nextStart.doneBid.contains(10))
    assert(nextStart.nextActiveBid.contains(11))

    val stop = markerLifecycleStep(
      activeValid = true,
      activeBid = 11,
      markerBoundary = false,
      markerStop = true,
      allocBid = 12)
    assert(stop.doneBid.contains(11))
    assert(!stop.nextActiveValid)
  }

  test("reference keeps scalar-created blocks active until a boundary or block-last closes them") {
    val scalarTarget = scalarStartLifecycleStep(
      activeValid = false,
      activeBid = 0,
      scalarRedirectValid = false,
      scalarAllocFire = true,
      scalarAllocBid = 12,
      robBlockLastFire = false,
      robBlockLastBid = 0)
    assert(scalarTarget.nextActiveBid.contains(12))

    val boundaryClose = markerLifecycleStep(
      activeValid = scalarTarget.nextActiveValid,
      activeBid = scalarTarget.nextActiveBid.get,
      markerBoundary = true,
      markerStop = false,
      allocBid = 13)
    assert(boundaryClose.doneBid.contains(12))
    assert(boundaryClose.nextActiveBid.contains(13))

    val scalarLast = scalarStartLifecycleStep(
      activeValid = true,
      activeBid = 13,
      scalarRedirectValid = false,
      scalarAllocFire = false,
      scalarAllocBid = 14,
      robBlockLastFire = true,
      robBlockLastBid = 13)
    assert(scalarLast.doneBid.contains(13))
    assert(!scalarLast.nextActiveValid)
  }

  test("reference scalar redirect clears marker target state before the return target block") {
    val redirected = scalarStartLifecycleStep(
      activeValid = true,
      activeBid = 15,
      scalarRedirectValid = true,
      scalarAllocFire = false,
      scalarAllocBid = 16,
      robBlockLastFire = false,
      robBlockLastBid = 0)
    assert(!redirected.nextActiveValid)
    assert(redirected.doneValid)
    assert(redirected.doneBid.contains(15))

    val targetBlock = scalarStartLifecycleStep(
      activeValid = redirected.nextActiveValid,
      activeBid = redirected.nextActiveBid.getOrElse(0),
      scalarRedirectValid = false,
      scalarAllocFire = true,
      scalarAllocBid = 16,
      robBlockLastFire = false,
      robBlockLastBid = 0)
    assert(targetBlock.nextActiveBid.contains(16))
  }

  test("reference redirects direct active blocks at the next marker boundary without allocation") {
    val directClose = markerBoundaryDecision(
      activeValid = true,
      activeBid = 20,
      activeCond = false,
      activeUnconditionalRedirect = true,
      activeTarget = 0x400055e2L,
      branchValid = false,
      branchTaken = false,
      allocBid = 21)
    assert(directClose.redirect)
    assert(!directClose.alloc)
    assert(directClose.doneBid.contains(20))
    assert(directClose.nextActiveBid.isEmpty)

    val condFallthrough = markerBoundaryDecision(
      activeValid = true,
      activeBid = 21,
      activeCond = true,
      activeUnconditionalRedirect = false,
      activeTarget = 0x400055f6L,
      branchValid = true,
      branchTaken = false,
      allocBid = 22)
    assert(condFallthrough.alloc)
    assert(!condFallthrough.redirect)
    assert(condFallthrough.doneBid.contains(21))
    assert(condFallthrough.nextActiveBid.contains(22))

    val condRedirect = markerBoundaryDecision(
      activeValid = true,
      activeBid = 22,
      activeCond = true,
      activeUnconditionalRedirect = false,
      activeTarget = 0x400055d4L,
      branchValid = true,
      branchTaken = true,
      allocBid = 23)
    assert(condRedirect.redirect)
    assert(!condRedirect.alloc)
    assert(condRedirect.doneBid.contains(22))
    assert(condRedirect.nextActiveBid.isEmpty)
  }

  test("reference treats zero-target conditional marker state as fallthrough") {
    val zeroTarget = markerBoundaryDecision(
      activeValid = true,
      activeBid = 30,
      activeCond = true,
      activeUnconditionalRedirect = false,
      activeTarget = 0,
      branchValid = false,
      branchTaken = false,
      allocBid = 31)

    assert(zeroTarget.alloc)
    assert(!zeroTarget.redirect)
    assert(zeroTarget.doneBid.contains(30))
    assert(zeroTarget.nextActiveBid.contains(31))
  }

  test("reference treats marker-only conditional state with no branch producer as fallthrough") {
    val markerOnly = markerBoundaryDecision(
      activeValid = true,
      activeBid = 32,
      activeCond = true,
      activeUnconditionalRedirect = false,
      activeTarget = 0x4000d1d8L,
      branchValid = false,
      branchTaken = false,
      allocBid = 33,
      scalarWorkPending = false)

    assert(markerOnly.alloc)
    assert(!markerOnly.redirect)
    assert(markerOnly.doneBid.contains(32))
    assert(markerOnly.nextActiveBid.contains(33))
  }

  test("reference admits marker-only redirect when ROB block-last owns the same active BID and STID") {
    val blanket = markerDecodeConflictDecision(
      markerOnlyPacket = true,
      markerStop = true,
      markerBoundaryRedirect = true,
      robBlockLastValid = true,
      robBlockLastBid = 0x44,
      robBlockLastStid = 0,
      activeValid = true,
      activeBid = 0x44,
      markerStid = 0,
      blanketConflict = true)
    assert(blanket.conflict)
    assert(!blanket.markerReady)
    assert(!blanket.decodeReady)
    assert(!blanket.stopRedirect)

    val owned = markerDecodeConflictDecision(
      markerOnlyPacket = true,
      markerStop = true,
      markerBoundaryRedirect = true,
      robBlockLastValid = true,
      robBlockLastBid = 0x44,
      robBlockLastStid = 0,
      activeValid = true,
      activeBid = 0x44,
      markerStid = 0)
    assert(!owned.conflict)
    assert(owned.markerReady)
    assert(owned.decodeReady)
    assert(owned.stopRedirect)

    val wrongBid = markerDecodeConflictDecision(
      markerOnlyPacket = true,
      markerStop = true,
      markerBoundaryRedirect = true,
      robBlockLastValid = true,
      robBlockLastBid = 0x45,
      robBlockLastStid = 0,
      activeValid = true,
      activeBid = 0x44,
      markerStid = 0)
    assert(wrongBid.conflict)

    val wrongStid = markerDecodeConflictDecision(
      markerOnlyPacket = true,
      markerStop = true,
      markerBoundaryRedirect = true,
      robBlockLastValid = true,
      robBlockLastBid = 0x44,
      robBlockLastStid = 1,
      activeValid = true,
      activeBid = 0x44,
      markerStid = 0)
    assert(wrongStid.conflict)
  }

  test("reference pre-retires an active marker block when allocation wraps onto its BROB slot") {
    val activeBid = BigInt(0xfc)
    val nextBidSameSlot = BigInt(0x104)
    val blocked = markerBoundaryDecision(
      activeValid = true,
      activeBid = activeBid,
      activeCond = false,
      activeUnconditionalRedirect = false,
      activeTarget = 0,
      branchValid = false,
      branchTaken = false,
      allocBid = nextBidSameSlot,
      allocReady = false,
      retirePending = false,
      entries = 8)

    assert(blocked.preRetire)
    assert(!blocked.alloc)
    assert(!blocked.redirect)
    assert(blocked.doneBid.contains(activeBid))
    assert(blocked.nextActiveBid.contains(activeBid))

    val waitingForRetire = markerBoundaryDecision(
      activeValid = true,
      activeBid = activeBid,
      activeCond = false,
      activeUnconditionalRedirect = false,
      activeTarget = 0,
      branchValid = false,
      branchTaken = false,
      allocBid = nextBidSameSlot,
      allocReady = false,
      retirePending = true,
      entries = 8)
    assert(!waitingForRetire.preRetire)
    assert(waitingForRetire.doneBid.isEmpty)
  }

  test("reference accepts agreeing ROB and LSU cleanup sources but blocks conflicting ones") {
    import TULinkFlushSequencePublisherReference._

    val bid = ROBIDValue(value = 2)
    val rid = ROBIDValue(value = 3)
    val source = Source(
      valid = true,
      bid = bid,
      rid = rid,
      stid = 1,
      tSeq = ROBIDValue(value = 5),
      uSeq = ROBIDValue(value = 6),
      dst = TDst)

    val agreed = TULinkFlushSourceSelectorReference.select(
      cleanupValid = true,
      backendFlushValid = true,
      baseOnBid = false,
      bid = bid,
      rid = rid,
      stid = 1,
      robSource = source,
      lsuSource = source)
    assert(agreed.multipleMatched)
    assert(!agreed.sourceConflict)
    assert(agreed.selectedFromRob)

    val conflict = TULinkFlushSourceSelectorReference.select(
      cleanupValid = true,
      backendFlushValid = true,
      baseOnBid = false,
      bid = bid,
      rid = rid,
      stid = 1,
      robSource = source,
      lsuSource = source.copy(uSeq = ROBIDValue(value = 7)))
    assert(conflict.multipleMatched)
    assert(conflict.sourceConflict)
    assert(!conflict.source.valid)
    assert(!conflict.selectedFromRob)
    assert(!conflict.selectedFromLsu)
  }

  test("IO exposes decode selection, rename, ROB allocation, and commit observability") {
    val p = InterfaceParams(robEntries = 8, commitWidth = 2)
    val trace = CommitTraceParams(commitWidth = 2, robValueWidth = p.robIndexWidth)
    val io = new DecodeRenameROBPathIO(p, trace)

    assert(io.decodedValidMask.getWidth == 4)
    assert(io.selectedSlot.getWidth == 2)
    assert(io.selectedRobValue.getWidth == 3)
    assert(io.selectedBlockBid.getWidth == 64)
    assert(io.commitBlockBid.getWidth == 64)
    assert(io.blockMarkerSkipValid.getWidth == 1)
    assert(io.blockMarkerMixedPacket.getWidth == 1)
    assert(io.blockMarkerBoundary.getWidth == 1)
    assert(io.blockMarkerStop.getWidth == 1)
    assert(io.blockMarkerPc.getWidth == 64)
    assert(io.blockMarkerInsn.getWidth == 64)
    assert(io.blockMarkerLen.getWidth == 4)
    assert(io.blockMarkerTarget.getWidth == 64)
    assert(io.blockMarkerAllocReady.getWidth == 1)
    assert(io.blockMarkerLifecycleConflict.getWidth == 1)
    assert(io.blockMarkerAllocFire.getWidth == 1)
    assert(io.blockMarkerAllocBid.getWidth == 64)
    assert(io.blockMarkerActiveValid.getWidth == 1)
    assert(io.blockMarkerActiveBid.getWidth == 64)
    assert(io.blockMarkerActiveTarget.getWidth == 64)
    assert(io.blockMarkerStopRedirectValid.getWidth == 1)
    assert(io.blockMarkerStopRedirectPc.getWidth == 64)
    assert(io.blockBranchTakenValid.getWidth == 1)
    assert(io.blockBranchTaken.getWidth == 1)
    assert(io.scalarRedirectValid.getWidth == 1)
    assert(io.scalarRedirectStid.getWidth == 8)
    assert(io.directRecoveryPendingMask.getWidth == 4)
    assert(io.directIexIqRecoveryBlockBid.getWidth == 64)
    assert(io.recoverySourceResolvedMask.getWidth == 6)
    assert(io.recoveryConsumedPayloadSourceMask.getWidth == 6)
    assert(io.decodeReady.getWidth == 1)
    assert(io.decRenPushFire.getWidth == 1)
    assert(io.decRenPopFire.getWidth == 1)
    assert(io.decRenHead.getWidth == 2)
    assert(io.decRenTail.getWidth == 2)
    assert(io.decRenCount.getWidth == 3)
    assert(io.decRenHeadPc.getWidth == 64)
    assert(io.decRenHeadUsesLocal.getWidth == 1)
    assert(io.decRenHeadRidValid.getWidth == 1)
    assert(io.decRenHeadRidValue.getWidth == 3)
    assert(io.selectedLsId.getWidth == 32)
    assert(io.selectedLoadId.getWidth == 64)
    assert(io.selectedStoreId.getWidth == 64)
    assert(io.nextLsId.getWidth == 32)
    assert(io.nextLoadId.getWidth == 64)
    assert(io.nextStoreId.getWidth == 64)
    assert(io.storeSplitIntent.getWidth == 1)
    assert(io.renamedOut.peId.getWidth == 8)
    assert(io.renamedOut.threadId.getWidth == 8)
    assert(io.renamedOut.isLastInBlock.getWidth == 1)
    assert(io.storeStaExec.valid.getWidth == 1)
    assert(io.storeStdExec.addr.getWidth == 64)
    assert(io.storeMarkCommitIndex.getWidth == 3)
    assert(io.storeMarkCommitAccepted.getWidth == 1)
    assert(io.storeCommitFreeAccepted.getWidth == 1)
    assert(io.storeCommitFreeMask.getWidth == 8)
    assert(io.storeCommitFreeAcceptedMask.getWidth == 8)
    assert(io.storeCommitFreeCount.getWidth == 4)
    assert(io.storeDispatchReady.getWidth == 1)
    assert(io.storeDispatchFire.getWidth == 1)
    assert(io.storeDispatchSplit.getWidth == 1)
    assert(io.storeDispatchBlockedBySta.getWidth == 1)
    assert(io.storeDispatchBlockedByStd.getWidth == 1)
    assert(io.storeSta.uop.lsid.getWidth == 32)
    assert(io.storeSta.uop.peId.getWidth == 8)
    assert(io.storeStd.dataSrcIndex.getWidth == 2)
    assert(io.storeUnsplit.dataSrcIndex.getWidth == 2)
    assert(io.storeStaQueueValid.getWidth == 1)
    assert(io.storeStdQueueValid.getWidth == 1)
    assert(io.storeStaQueue.uop.lsid.getWidth == 32)
    assert(io.storeStdQueue.uop.lsid.getWidth == 32)
    assert(io.storeStaEnqueueFire.getWidth == 1)
    assert(io.storeStdEnqueueFire.getWidth == 1)
    assert(io.storeStaDequeueFire.getWidth == 1)
    assert(io.storeStdDequeueFire.getWidth == 1)
    assert(io.storeDispatchInputProtocolError.getWidth == 1)
    assert(io.storeStaQueueCount.getWidth == 3)
    assert(io.storeStdQueueCount.getWidth == 3)
    assert(io.storeStaQueueFull.getWidth == 1)
    assert(io.storeStdQueueFull.getWidth == 1)
    assert(io.storeStaInsertReady.getWidth == 1)
    assert(io.storeStdInsertCanMerge.getWidth == 1)
    assert(io.storeSelectedSta.getWidth == 1)
    assert(io.storeBlockedByStaExec.getWidth == 1)
    assert(io.storeStdBypassStaBlocked.getWidth == 1)
    assert(io.storeScResultValid.getWidth == 1)
    assert(io.storeScCandidate.getWidth == 1)
    assert(io.storeScSelectedSuccess.getWidth == 1)
    assert(io.storeScSelectedMissDiscard.getWidth == 1)
    assert(io.storeScAcceptedIdentity.lsIdFull.getWidth == 32)
    assert(io.storeStqInsertValid.getWidth == 1)
    assert(io.storeStqInsert.addr.getWidth == 64)
    assert(io.storeStqInsert.data.getWidth == 64)
    assert(io.storeStqInsert.size.getWidth == 4)
    assert(io.storeStqInsert.peId.getWidth == 8)
    assert(io.storeStqInsert.stid.getWidth == 8)
    assert(io.storeStqInsert.bid.value.getWidth == 3)
    assert(io.storeStqInsert.lsId.value.getWidth == 3)
    assert(io.storeStqInsert.lsIdFull.getWidth == 32)
    assert(io.storeStqInsert.scalarIex.getWidth == 1)
    assert(io.storeStqInsertAccepted.getWidth == 1)
    assert(io.storeStqInsertIndex.getWidth == 3)
    assert(io.storeStqRows.length == 8)
    assert(io.storeStqRows.head.rid.value.getWidth == 3)
    assert(io.storeStqFlushFreeMask.getWidth == 8)
    assert(io.storeStqFlushFreeCount.getWidth == 4)
    assert(io.storeSta.tSeq.value.getWidth == 5)
    assert(io.storeStd.uSeq.value.getWidth == 5)
    assert(io.storeStaQueue.tuDstValid.getWidth == 1)
    assert(io.storeLsuTULinkSource.tSeq.value.getWidth == 5)
    assert(io.storeLsuTULinkSourceMatched.getWidth == 1)
    assert(io.storeStqOccupiedMask.getWidth == 8)
    assert(io.storeStqAddrReadyMask.getWidth == 8)
    assert(io.storeStqDataReadyMask.getWidth == 8)
    assert(io.storeStqResidentCount.getWidth == 4)
    assert(io.blockedByTURename.getWidth == 1)
    assert(io.tuRenameReady.getWidth == 1)
    assert(io.tuRenameAccepted.getWidth == 1)
    assert(io.tuRenameActivePeId.getWidth == 8)
    assert(io.tuRenameActiveStid.getWidth == 8)
    assert(io.tuRenameActivePeInRange.getWidth == 1)
    assert(io.tuRenameActiveStidInRange.getWidth == 1)
    assert(io.tuRenameActiveBankValid.getWidth == 1)
    assert(io.tuRenameTSeq.value.getWidth == 5)
    assert(io.tuRenameUSeq.value.getWidth == 5)
    assert(io.tuRenameDstValid.getWidth == 1)
    assert(io.tuRenameNeedsTAlloc.getWidth == 1)
    assert(io.tuRenameNeedsUAlloc.getWidth == 1)
    assert(io.tuRenameSourceUnderflowMask.getWidth == 3)
    assert(io.robAllocAttemptValid.getWidth == 1)
    assert(io.robAllocFire.getWidth == 1)
    assert(io.robRenameUpdateAttemptValid.getWidth == 1)
    assert(io.robRenameUpdateReady.getWidth == 1)
    assert(io.robRenameUpdateFire.getWidth == 1)
    assert(io.robRenameUpdateIgnored.getWidth == 1)
    assert(io.robMarkerRowCompletePending.getWidth == 1)
    assert(io.robMarkerRowCompleteFire.getWidth == 1)
    assert(io.robTULinkSource.tSeq.value.getWidth == 5)
    assert(io.robTULinkSource.uSeq.value.getWidth == 5)
    assert(io.robTULinkSourceMatched.getWidth == 1)
    assert(io.robTULinkSourceMultipleMatch.getWidth == 1)
    assert(io.robDeallocTURetireSource.length == 2)
    assert(io.robDeallocTURetireSource(0).tSeq.value.getWidth == 5)
    assert(io.robDeallocTURetireSource(0).peId.getWidth == 8)
    assert(io.robDeallocTURetireSource(0).isLast.getWidth == 1)
    assert(io.robDeallocBlockMarkerRetireSource.length == 2)
    assert(io.robDeallocBlockMarkerRetireSource(0).isBoundary.getWidth == 1)
    assert(io.robDeallocBlockMarkerRetireSource(0).isStop.getWidth == 1)
    assert(io.robDeallocBlockMarkerRetireSource(0).blockBid.getWidth == 64)
    assert(io.robDeallocBlockMarkerRetireSource(0).pc.getWidth == 64)
    assert(io.robDeallocBlockMarkerRetireSource(0).boundaryTarget.getWidth == 64)
    assert(io.robMarkerRetireSourceWindowReady.getWidth == 1)
    assert(io.robMarkerRetireSourceValidMask.getWidth == 2)
    assert(io.robMarkerRetireSourceEnqueueCount.getWidth == 2)
    assert(io.robMarkerRetireSourceQueueCount.getWidth == 4)
    assert(io.robMarkerRetireSourceQueueFull.getWidth == 1)
    assert(io.robMarkerRetireSourceQueueEmpty.getWidth == 1)
    assert(io.robMarkerRetireSourceDequeued.getWidth == 1)
    assert(io.robMarkerRetireSourcePruneCount.getWidth == 4)
    assert(io.robMarkerRetireSourceLifecycleReady.getWidth == 1)
    assert(io.robMarkerRetireSourceLifecycleFire.getWidth == 1)
    assert(io.robMarkerRetireSourceLifecycleBoundaryFire.getWidth == 1)
    assert(io.robMarkerRetireSourceLifecycleStopFire.getWidth == 1)
    assert(io.robMarkerRetireSource.isBoundary.getWidth == 1)
    assert(io.robMarkerRetireSource.isStop.getWidth == 1)
    assert(io.robMarkerRetireSource.blockBid.getWidth == 64)
    assert(io.robMarkerRetireSource.boundaryTarget.getWidth == 64)
    assert(io.robDeallocBlockLastValid.getWidth == 1)
    assert(io.robDeallocBlockLastBid.value.getWidth == 3)
    assert(io.robDeallocBlockLastGid.value.getWidth == 3)
    assert(io.robDeallocBlockLastBlockBid.getWidth == 64)
    assert(io.blockScalarDoneFire.getWidth == 1)
    assert(io.blockScalarDoneBid.getWidth == 64)
    assert(io.blockRetireFire.getWidth == 1)
    assert(io.blockRetireBid.getWidth == 64)
    assert(io.tuRetireSourceWindowReady.getWidth == 1)
    assert(io.tuRetireSourceValidMask.getWidth == 2)
    assert(io.tuRetireSourceEnqueueCount.getWidth == 2)
    assert(io.tuRetireSourceQueueCount.getWidth == 4)
    assert(io.tuRetireCleanupActive.getWidth == 1)
    assert(io.tuRetireSourcePruneCount.getWidth == 4)
    assert(io.tuRetireRelationPruneTCount.getWidth == 4)
    assert(io.tuRetireRelationPruneUCount.getWidth == 4)
    assert(io.tuRetireCommandKind.getWidth == DestinationKind.getWidth)
    assert(io.tuRetireCommandSeq.wrap.getWidth == 1)
    assert(io.tuRetireCommandSeq.value.getWidth == 5)
    assert(io.tuRetireCommandDealloc.getWidth == 1)
    assert(io.tuRetireCommandPeId.getWidth == 8)
    assert(io.tuRetireCommandStid.getWidth == 8)
    assert(io.tuRetireSelectedTDeallocSeq.wrap.getWidth == 1)
    assert(io.tuRetireSelectedTDeallocSeq.value.getWidth == 5)
    assert(io.tuRetireSelectedUDeallocSeq.wrap.getWidth == 1)
    assert(io.tuRetireSelectedUDeallocSeq.value.getWidth == 5)
    assert(io.tuRetireSelectedTValidMask.getWidth == 32)
    assert(io.tuRetireSelectedUValidMask.getWidth == 32)
    assert(io.tuRetireSelectedTRetiredMask.getWidth == 32)
    assert(io.tuRetireSelectedURetiredMask.getWidth == 32)
    assert(io.tuRetireAutoCleanBlockPending.getWidth == 1)
    assert(io.tuRetireAutoCleanBlockValid.getWidth == 1)
    assert(io.tuRetireAutoCleanBlockBid.value.getWidth == 3)
    assert(io.tuRetireLocalBlockCommitPending.getWidth == 1)
    assert(io.tuRetireLocalBlockCommitValid.getWidth == 1)
    assert(io.tuRetireLocalBlockCommitReady.getWidth == 1)
    assert(io.tuRetireLocalBlockCommitBid.value.getWidth == 3)
    assert(io.tuRetireLocalBlockCommitStid.getWidth == 8)
    assert(io.tuRetireLocalBlockCommitFire.getWidth == 1)
    assert(io.tuRetireLocalBlockCommitAccepted.getWidth == 1)
    assert(io.tuRetireLocalBlockCommitStidMatch.getWidth == 1)
    assert(io.tuRetireLocalBlockCommitBlockedByStid.getWidth == 1)
    assert(io.tuRetireLocalBlockCommitFanoutStidInRange.getWidth == 1)
    assert(io.tuRetireLocalBlockCommitFanoutBlockedByStidRange.getWidth == 1)
    assert(io.tuRetireLocalBlockCommitFanoutBlockedByBankReady.getWidth == 1)
    assert(io.tuRetireLocalBlockCommitFanoutTargetPeMask.getWidth == 1)
    assert(io.tuRetireLocalBlockCommitFanoutReadyPeMask.getWidth == 1)
    assert(io.tuRetireRelationPreReleaseT.getWidth == 1)
    assert(io.tuRetireRelationTCount.getWidth == 4)
    assert(io.tuRetireAccepted.getWidth == 1)
    assert(io.tuRetireReleaseMismatch.getWidth == 1)
    assert(io.tuRetirePeInRange.getWidth == 1)
    assert(io.tuRetireStidInRange.getWidth == 1)
    assert(io.tuRetireBankValid.getWidth == 1)
    assert(io.tuCleanupPublisherFlushValid.getWidth == 1)
    assert(io.tuCleanupPublisherFlushTSeq.value.getWidth == 5)
    assert(io.tuCleanupPublisherFlushUSeq.value.getWidth == 5)
    assert(io.tuCleanupSelectedFlushSource.uSeq.value.getWidth == 5)
    assert(io.tuCleanupRobSourceMatched.getWidth == 1)
    assert(io.tuCleanupLsuSourceMatched.getWidth == 1)
    assert(io.tuCleanupMultipleSourcesMatched.getWidth == 1)
    assert(io.tuCleanupSourceConflict.getWidth == 1)
    assert(io.tuCleanupSelectedFromRob.getWidth == 1)
    assert(io.tuCleanupSelectedFromLsu.getWidth == 1)
    assert(io.commit.rows.length == 2)
    assert(io.commitMemoryOrder.length == 2)
    assert(io.commitMemoryOrder(0).valid.getWidth == 1)
    assert(io.commitMemoryOrder(0).isLoadStore.getWidth == 1)
    assert(io.commitMemoryOrder(0).bid.value.getWidth == 3)
    assert(io.commitMemoryOrder(0).rid.value.getWidth == 3)
    assert(io.commitMemoryOrder(0).lsId.getWidth == 32)
    assert(io.occupiedMask.getWidth == 8)
    assert(io.blockAllocatedMask.getWidth == 8)
  }

  test("IO exposes model-sized scalar GPR mapQ pressure without widening T/U sequences") {
    val p = InterfaceParams(robEntries = 8, commitWidth = 2)
    val trace = CommitTraceParams(commitWidth = 2, robValueWidth = p.robIndexWidth)
    val io = new DecodeRenameROBPathIO(p, trace, mapQDepth = 8, gprMapQDepth = 256)

    assert(io.gprMapQFreeCount.getWidth == 9)
    assert(io.gprCommittedMapQCount.getWidth == 9)
    assert(io.gprReleasedPhysCount.getWidth == 7)
    assert(io.tuRenameTSeq.value.getWidth == 3)
    assert(io.tuRenameUSeq.value.getWidth == 3)
  }

  test("IO keeps scalar STQ capacity independent of ROB identity sizing") {
    val p = InterfaceParams(robEntries = 8, commitWidth = 2, lsidWidth = 40)
    val trace = CommitTraceParams(commitWidth = 2, robValueWidth = p.robIndexWidth)
    val io = new DecodeRenameROBPathIO(p, trace, storeStqEntries = 16)

    assert(io.storeMarkCommitIndex.getWidth == 4)
    assert(io.storeCommitFreeIndex.getWidth == 4)
    assert(io.storeCommitFreeMask.getWidth == 16)
    assert(io.storeStqInsertIndex.getWidth == 4)
    assert(io.storeStqRows.length == 16)
    assert(io.storeStqRows.head.bid.value.getWidth == 3)
    assert(io.storeStqRows.head.lsIdFull.getWidth == 40)
    assert(io.storeStqInsert.bid.value.getWidth == 3)
    assert(io.storeStqInsert.lsIdFull.getWidth == 40)
    assert(io.recoveryNonLsuSources.head.lsId.value.getWidth == 3)
    assert(io.recoveryNonLsuSources.head.lsIdFull.getWidth == 40)
    assert(io.recoveryIntent.flush.req.lsIdFull.getWidth == 40)
    assert(io.storeStqOccupiedMask.getWidth == 16)
    assert(io.storeStqResidentCount.getWidth == 5)
    assert(io.storeStqFlushFullLsIdRequiredMask.getWidth == 16)
    assert(io.storeStqFlushFullLsIdMissingMask.getWidth == 16)
    assert(io.storeStqFlushFullLsIdAmbiguousMask.getWidth == 16)
  }

  test("DecodeRenameROBPath elaborates unequal scalar STQ and ROB capacities") {
    val p = InterfaceParams(robEntries = 8, commitWidth = 2, lsidWidth = 40)
    val trace = CommitTraceParams(commitWidth = 2, robValueWidth = p.robIndexWidth)
    val sv = ChiselStage.emitSystemVerilog(
      new DecodeRenameROBPath(
        p = p,
        traceParams = trace,
        mapQDepth = 8,
        storeStqEntries = 16
      )
    )

    assert(sv.contains("io_storeStqRows_15_status"))
    assert(sv.contains("io_storeMarkCommitIndex"))
    assert(sv.contains("io_storeCommitFreeMask"))
    assert(sv.contains("io_storeStqRows_15_bid_value"))
    assert(sv.contains("io_storeStqRows_15_lsIdFull"))
    assert(sv.contains("io_recoveryIntent_flush_req_lsIdFull"))
    assert(sv.contains("io_storeStqFlushFullLsIdMissingMask"))
  }

  test("DecodeRenameROBPath elaborates frontend decode through rename and DispatchROBAllocator") {
    val p = InterfaceParams(robEntries = 8, commitWidth = 2)
    val trace = CommitTraceParams(commitWidth = 2, robValueWidth = p.robIndexWidth)
    val sv = ChiselStage.emitSystemVerilog(
      new DecodeRenameROBPath(p = p, traceParams = trace, mapQDepth = 8)
    )

    assert(sv.contains("module DecodeRenameROBPath"))
    assert(sv.contains("FrontendDecodeStage"))
    assert(sv.contains("DecodeLoadStoreIdAssign"))
    assert(sv.contains("DecodeRenameQueue"))
    assert(sv.contains("ScalarTURenameBridge"))
    assert(sv.contains("ScalarDecodeRenameBridge"))
    assert(sv.contains("StoreSplitPayload"))
    assert(sv.contains("StoreDispatchSTQPath"))
    assert(sv.contains("StoreDispatchQueues"))
    assert(sv.contains("STQEntryBank"))
    assert(sv.contains("DispatchROBAllocator"))
    assert(sv.contains("BlockMarkerLifecycle"))
    assert(sv.contains("BlockMarkerRetireSourceSerializer"))
    assert(sv.contains("BrobOrderState"))
    assert(!sv.contains("BlockScalarDoneSequencer"))
    assert(sv.contains("TULinkRecoveryCleanupPath"))
    assert(sv.contains("io_decodeReady"))
    assert(sv.contains("io_decRenPushFire"))
    assert(sv.contains("io_lsidAssignFire"))
    assert(sv.contains("io_storeDispatchReady"))
    assert(sv.contains("io_storeSta_valid"))
    assert(sv.contains("io_storeSta_tSeq_value"))
    assert(sv.contains("io_storeStaQueueValid"))
    assert(sv.contains("io_storeStdQueue_uSeq_value"))
    assert(sv.contains("io_storeStaEnqueueFire"))
    assert(sv.contains("io_storeStaQueueCount"))
    assert(sv.contains("io_storeLsuTULinkSource_tSeq_value"))
    assert(sv.contains("io_storeStqInsert_addr"))
    assert(sv.contains("io_storeStqInsert_lsId_value"))
    assert(sv.contains("io_storeStqInsert_scalarIex"))
    assert(sv.contains("io_storeStqInsertAccepted"))
    assert(sv.contains("io_storeMarkCommitAccepted"))
    assert(sv.contains("io_storeCommitFreeAcceptedMask"))
    assert(sv.contains("io_storeStqRows_0_valid"))
    assert(sv.contains("io_storeStqOccupiedMask"))
    assert(sv.contains("io_storeStqAddrReadyMask"))
    assert(sv.contains("io_storeStqDataReadyMask"))
    assert(sv.contains("io_selectedLsId"))
    assert(sv.contains("io_decRenCount"))
    assert(sv.contains("io_robAllocAttemptValid"))
    assert(sv.contains("io_robRenameUpdateAttemptValid"))
    assert(sv.contains("io_decRenHeadPc"))
    assert(sv.contains("io_decRenHeadUsesLocal"))
    assert(sv.contains("io_robRenameUpdateFire"))
    assert(sv.contains("io_robMarkerRowCompletePending"))
    assert(sv.contains("io_robMarkerRowCompleteFire"))
    assert(sv.contains("io_completeRowValid"))
    assert(sv.contains("io_completeRow_wb_data"))
    assert(sv.contains("io_commitMemoryOrder_0_valid"))
    assert(sv.contains("io_commitMemoryOrder_0_lsId"))
    assert(sv.contains("io_renamedOut_peId"))
    assert(sv.contains("io_tuRenameTSeq_value"))
    assert(sv.contains("io_tuRenameActivePeId"))
    assert(sv.contains("io_tuRenameActiveStid"))
    assert(sv.contains("io_tuRenameActiveBankValid"))
    assert(sv.contains("io_tuRenameDstValid"))
    assert(sv.contains("io_blockedByTURename"))
    assert(sv.contains("io_selectedRobValue"))
    assert(sv.contains("io_blockMarkerSkipValid"))
    assert(sv.contains("io_blockMarkerMixedPacket"))
    assert(sv.contains("io_blockMarkerPc"))
    assert(sv.contains("io_blockMarkerAllocFire"))
    assert(sv.contains("io_blockMarkerActiveBid"))
    assert(sv.contains("io_blockMarkerStopRedirectValid"))
    assert(sv.contains("markerDecodeQueryStid"))
    assert(sv.contains("robBlockLastOwnsMarkerDecode"))
    assert(sv.contains("io_scalarRedirectStid"))
    assert(sv.contains("io_robTULinkSource_tSeq_value"))
    assert(sv.contains("io_robTULinkSourceMatched"))
    assert(sv.contains("module RecoveryBackendControl"))
    assert(sv.contains("module RecoveryNonLsuProducerBank"))
    assert(sv.contains("io_directBccRecoveryMiss"))
    assert(sv.contains("io_directIexSlowRecovery"))
    assert(sv.contains("io_directIexIqTriggerCaptured"))
    assert(sv.contains("io_directIexIqIdentityValid"))
    assert(sv.contains("io_directIexIqRecoveryBlockBid"))
    assert(sv.contains("io_directPeMismatchRecovery"))
    assert(sv.contains("module IexIqStallRecoveryIdentity"))
    assert(sv.contains("module RecoveryFabric"))
    assert(sv.contains("io_recoveryNonLsuSources_0_blockBid"))
    assert(sv.contains("io_lsuRecoverySource_blockBid"))
    assert(sv.contains("io_lsuFullBidLookupRequest_rid_value"))
    assert(sv.contains("io_lsuFullBidLookup_blockBid"))
    assert(sv.contains("io_recoveryIntent_blockFlushBid"))
    assert(sv.contains("io_recoveryIntent_blockFlushPointer"))
    assert(sv.contains("io_recoveryIntentConsumed"))
    assert(sv.contains("io_recoveryOldestValid_0"))
    assert(sv.contains("io_recoveryOldestBlockBid_0"))
    assert(sv.contains("io_recoveryOldestBid_0_value"))
    assert(sv.contains("io_recoveryOldestRid_0_wrap"))
    assert(sv.contains("io_recoveryOldestBlockComplete_0"))
    assert(sv.contains("io_robDeallocTURetireSource_0_tSeq_value"))
    assert(sv.contains("io_robDeallocTURetireSource_0_peId"))
    assert(sv.contains("io_robDeallocTURetireSource_0_isLast"))
    assert(sv.contains("io_robDeallocBlockMarkerRetireSource_0_isBoundary"))
    assert(sv.contains("io_robDeallocBlockMarkerRetireSource_0_isStop"))
    assert(sv.contains("io_robDeallocBlockMarkerRetireSource_0_boundaryTarget"))
    assert(sv.contains("io_robMarkerRetireSourceWindowReady"))
    assert(sv.contains("io_robMarkerRetireSourceQueueCount"))
    assert(sv.contains("io_robMarkerRetireSourcePruneCount"))
    assert(sv.contains("io_robMarkerRetireSourceLifecycleFire"))
    assert(sv.contains("io_robMarkerRetireSource_boundaryTarget"))
    assert(sv.contains("io_robDeallocBlockLastValid"))
    assert(sv.contains("io_robDeallocBlockLastBid_value"))
    assert(sv.contains("io_robDeallocBlockLastBlockBid"))
    assert(sv.contains("io_blockScalarDoneFire"))
    assert(sv.contains("io_blockRetireFire"))
    assert(sv.contains("TULinkRetireCommandPath"))
    assert(sv.contains("io_tuRetireSourceWindowReady"))
    assert(sv.contains("io_tuRetireCleanupActive"))
    assert(sv.contains("io_tuRetireSourcePruneCount"))
    assert(sv.contains("io_tuRetireCommandSeq_value"))
    assert(sv.contains("io_tuRetireCommandSeq_wrap"))
    assert(sv.contains("io_tuRetireCommandPeId"))
    assert(sv.contains("io_tuRetireCommandStid"))
    assert(sv.contains("io_tuRetireSelectedTDeallocSeq_wrap"))
    assert(sv.contains("io_tuRetireSelectedTDeallocSeq_value"))
    assert(sv.contains("io_tuRetireSelectedUDeallocSeq_wrap"))
    assert(sv.contains("io_tuRetireSelectedUDeallocSeq_value"))
    assert(sv.contains("io_tuRetireSelectedTValidMask"))
    assert(sv.contains("io_tuRetireSelectedUValidMask"))
    assert(sv.contains("io_tuRetireSelectedTRetiredMask"))
    assert(sv.contains("io_tuRetireSelectedURetiredMask"))
    assert(sv.contains("io_tuRetireAutoCleanBlockPending"))
    assert(sv.contains("io_tuRetireAutoCleanBlockValid"))
    assert(sv.contains("io_tuRetireAutoCleanBlockBid_value"))
    assert(sv.contains("io_tuRetireLocalBlockCommitPending"))
    assert(sv.contains("io_tuRetireLocalBlockCommitValid"))
    assert(sv.contains("io_tuRetireLocalBlockCommitReady"))
    assert(sv.contains("io_tuRetireLocalBlockCommitBid_value"))
    assert(sv.contains("io_tuRetireLocalBlockCommitStid"))
    assert(sv.contains("io_tuRetireLocalBlockCommitFire"))
    assert(sv.contains("io_tuRetireLocalBlockCommitAccepted"))
    assert(sv.contains("io_tuRetireLocalBlockCommitStidMatch"))
    assert(sv.contains("io_tuRetireLocalBlockCommitBlockedByStid"))
    assert(sv.contains("TULinkLocalBankArray"))
    assert(sv.contains("TULinkLocalBlockCommitFanout"))
    assert(sv.contains("io_tuRetireLocalBlockCommitFanoutBlockedByBankReady"))
    assert(sv.contains("io_tuRetireBankValid"))
    assert(sv.contains("io_tuRetireRelationPreReleaseT"))
    assert(sv.contains("io_tuRetireAccepted"))
    assert(sv.contains("io_tuCleanupPublisherFlushTSeq_value"))
    assert(sv.contains("io_tuCleanupSourceConflict"))
    assert(sv.contains("io_tuCleanupSelectedFromLsu"))
    assert(sv.contains("io_commitContractError"))
  }

  test("T/U retire identity observability preserves full command and selected-bank widths") {
    val p = InterfaceParams(robEntries = 8, commitWidth = 2)
    val trace = CommitTraceParams(commitWidth = 2, robValueWidth = p.robIndexWidth)
    val bridgeIo = new ScalarTURenameBridgeIO(p, trace, mapQDepth = 8, scalarStidCount = 2)
    val pathIo = new DecodeRenameROBPathIO(p, trace, mapQDepth = 8, scalarStidCount = 2)

    Seq(bridgeIo.tuRetireCommandSeq, pathIo.tuRetireCommandSeq).foreach { seq =>
      assert(seq.wrap.getWidth == 1)
      assert(seq.value.getWidth == 3)
    }
    Seq(
      bridgeIo.tuRetireSelectedTDeallocSeq,
      bridgeIo.tuRetireSelectedUDeallocSeq,
      pathIo.tuRetireSelectedTDeallocSeq,
      pathIo.tuRetireSelectedUDeallocSeq).foreach { seq =>
      assert(seq.wrap.getWidth == 1)
      assert(seq.value.getWidth == 3)
    }
    Seq(
      bridgeIo.tuRetireSelectedTValidMask,
      bridgeIo.tuRetireSelectedUValidMask,
      bridgeIo.tuRetireSelectedTRetiredMask,
      bridgeIo.tuRetireSelectedURetiredMask,
      pathIo.tuRetireSelectedTValidMask,
      pathIo.tuRetireSelectedUValidMask,
      pathIo.tuRetireSelectedTRetiredMask,
      pathIo.tuRetireSelectedURetiredMask).foreach(mask => assert(mask.getWidth == 8))

    assert(bridgeIo.tuRetireCommandKind.getWidth == DestinationKind.getWidth)
    assert(pathIo.tuRetireCommandKind.getWidth == DestinationKind.getWidth)
    assert(bridgeIo.tuRetireCommandDealloc.getWidth == 1)
    assert(pathIo.tuRetireCommandDealloc.getWidth == 1)
    assert(bridgeIo.tuRetireCommandPeId.getWidth == 8)
    assert(pathIo.tuRetireCommandPeId.getWidth == 8)
    assert(bridgeIo.tuRetireCommandStid.getWidth == 8)
    assert(pathIo.tuRetireCommandStid.getWidth == 8)
  }

  test("sim forwards exact backend retire commands and mutation-sensitive T/U queue state") {
    simulate(new DecodeRenameROBPathIdentityProbe) { dut =>
      final case class Row(rob: Int, bid: BigInt, pc: BigInt)
      final case class Command(
          kind: DestinationKind.Type,
          seqValue: Int,
          dealloc: Boolean,
          tHead: Int,
          uHead: Int,
          tValid: Int,
          uValid: Int,
          tRetired: Int,
          uRetired: Int,
          accepted: Boolean = true,
          releaseMismatch: Boolean = false)
      final case class ObservedCommand(
          valid: Boolean,
          kind: BigInt,
          seqWrap: Boolean,
          seqValue: Int,
          dealloc: Boolean,
          peId: BigInt,
          stid: BigInt,
          tHeadWrap: Boolean,
          tHead: Int,
          uHeadWrap: Boolean,
          uHead: Int,
          tValid: BigInt,
          uValid: BigInt,
          tRetired: BigInt,
          uRetired: BigInt,
          accepted: Boolean,
          releaseMismatch: Boolean)

      def idle(): Unit = {
        dut.io.decodeValid.poke(false.B)
        dut.io.usePredecoded.poke(false.B)
        dut.io.decodeInsn.poke(0.U)
        dut.io.decodeLenBytes.poke(2.U)
        dut.io.decodePc.poke(0.U)
        dut.io.decodeLast.poke(false.B)
        dut.io.flushValid.poke(false.B)
        dut.io.renamedOutReady.poke(true.B)
        dut.io.completeValid.poke(false.B)
        dut.io.completeRobValue.poke(0.U)
        dut.io.completeBlockBid.poke(0.U)
        dut.io.completePc.poke(0.U)
      }

      def issue(raw: BigInt, pc: BigInt, last: Boolean): Row = {
        idle()
        dut.io.decodeValid.poke(true.B)
        dut.io.decodeInsn.poke(raw.U)
        dut.io.decodeLenBytes.poke(2.U)
        dut.io.decodePc.poke(pc.U)
        dut.io.decodeLast.poke(last.B)
        dut.io.decodeReady.expect(true.B)
        dut.io.selectedValid.expect(true.B)
        val row = Row(
          rob = dut.io.selectedRobValue.peek().litValue.toInt,
          bid = dut.io.selectedBlockBid.peek().litValue,
          pc = pc)
        dut.clock.step()
        idle()
        dut.clock.step()
        row
      }

      def complete(row: Row): Unit = {
        idle()
        dut.io.completeValid.poke(true.B)
        dut.io.completeRobValue.poke(row.rob.U)
        dut.io.completeBlockBid.poke(row.bid.U)
        dut.io.completePc.poke(row.pc.U)
        dut.clock.step()
        idle()
      }

      def marker(raw: BigInt, pc: BigInt): Unit = {
        idle()
        dut.io.decodeValid.poke(true.B)
        dut.io.decodeInsn.poke(raw.U)
        dut.io.decodeLenBytes.poke(2.U)
        dut.io.decodePc.poke(pc.U)
        dut.io.decodeLast.poke(false.B)
        dut.io.decodeReady.expect(true.B)
        dut.clock.step()
        idle()
        dut.clock.step()
      }

      val markerRaw = BigInt("0194", 16)
      def cMovr(dst: Int, src: Int): BigInt =
        BigInt(0x0006 | ((src & 0x1f) << 6) | ((dst & 0x1f) << 11))
      val tRaw = cMovr(dst = 31, src = 2)
      val uRaw = cMovr(dst = 30, src = 3)

      idle()
      dut.clock.step()
      marker(markerRaw, pc = 0x2000)
      val rows = Seq(
        issue(tRaw, pc = 0x2002, last = false),
        issue(tRaw, pc = 0x2004, last = false),
        issue(uRaw, pc = 0x2006, last = false),
        issue(uRaw, pc = 0x2008, last = true))

      idle()
      dut.clock.step(8)
      dut.io.tUsedEntries.expect(2.U)
      dut.io.uUsedEntries.expect(2.U)
      dut.io.tDeallocSeqWrap.expect(false.B)
      dut.io.tDeallocSeqValue.expect(0.U)
      dut.io.uDeallocSeqWrap.expect(false.B)
      dut.io.uDeallocSeqValue.expect(0.U)
      dut.io.tValidMask.expect("h03".U)
      dut.io.uValidMask.expect("h03".U)
      dut.io.tRetiredMask.expect(0.U)
      dut.io.uRetiredMask.expect(0.U)

      rows.reverse.foreach(complete)

      // commandFire and the queue observability signals are sampled before the
      // active edge.  Each row therefore pairs the current retire command with
      // the registered T/U state produced by the preceding command.
      val expected = Seq(
        Command(DestinationKind.T, 0, dealloc = false, 0, 0, 0x03, 0x03, 0x00, 0x00),
        Command(DestinationKind.T, 1, dealloc = false, 0, 0, 0x03, 0x03, 0x01, 0x00),
        Command(DestinationKind.U, 0, dealloc = false, 0, 0, 0x03, 0x03, 0x03, 0x00),
        Command(
          DestinationKind.T,
          0,
          dealloc = true,
          0,
          0,
          0x03,
          0x03,
          0x03,
          0x01,
          accepted = true),
        Command(DestinationKind.T, 1, dealloc = true, 1, 0, 0x02, 0x03, 0x02, 0x01),
        Command(DestinationKind.U, 0, dealloc = true, 2, 0, 0x00, 0x03, 0x00, 0x01),
        Command(DestinationKind.U, 1, dealloc = false, 2, 1, 0x00, 0x02, 0x00, 0x00),
        Command(DestinationKind.U, 1, dealloc = true, 2, 1, 0x00, 0x02, 0x00, 0x02))

      val actual = scala.collection.mutable.ArrayBuffer.empty[ObservedCommand]
      var observed = 0
      var cycles = 0
      while (observed < expected.length && cycles < 160) {
        if (dut.io.commandFire.peek().litToBoolean) {
          actual += ObservedCommand(
            valid = dut.io.commandValid.peek().litToBoolean,
            kind = dut.io.commandKind.peek().litValue,
            seqWrap = dut.io.commandSeqWrap.peek().litToBoolean,
            seqValue = dut.io.commandSeqValue.peek().litValue.toInt,
            dealloc = dut.io.commandDealloc.peek().litToBoolean,
            peId = dut.io.commandPeId.peek().litValue,
            stid = dut.io.commandStid.peek().litValue,
            tHeadWrap = dut.io.tDeallocSeqWrap.peek().litToBoolean,
            tHead = dut.io.tDeallocSeqValue.peek().litValue.toInt,
            uHeadWrap = dut.io.uDeallocSeqWrap.peek().litToBoolean,
            uHead = dut.io.uDeallocSeqValue.peek().litValue.toInt,
            tValid = dut.io.tValidMask.peek().litValue,
            uValid = dut.io.uValidMask.peek().litValue,
            tRetired = dut.io.tRetiredMask.peek().litValue,
            uRetired = dut.io.uRetiredMask.peek().litValue,
            accepted = dut.io.retireAccepted.peek().litToBoolean,
            releaseMismatch = dut.io.retireReleaseMismatch.peek().litToBoolean)
          observed += 1
        }
        dut.clock.step()
        cycles += 1
      }

      assert(observed == expected.length, s"observed $observed/${expected.length} retire commands in $cycles cycles")
      val exactExpected = expected.map { exp =>
        ObservedCommand(
          valid = true,
          kind = exp.kind.asUInt.litValue,
          seqWrap = false,
          seqValue = exp.seqValue,
          dealloc = exp.dealloc,
          peId = 0,
          stid = 0,
          tHeadWrap = false,
          tHead = exp.tHead,
          uHeadWrap = false,
          uHead = exp.uHead,
          tValid = exp.tValid,
          uValid = exp.uValid,
          tRetired = exp.tRetired,
          uRetired = exp.uRetired,
          accepted = exp.accepted,
          releaseMismatch = exp.releaseMismatch)
      }
      assert(actual.toSeq == exactExpected, s"exact retire forwarding mismatch:\nactual=$actual\nexpected=$exactExpected")
      idle()
      dut.clock.step(8)
      dut.io.commandValid.expect(false.B)
      dut.io.tDeallocSeqWrap.expect(false.B)
      dut.io.tDeallocSeqValue.expect(2.U)
      dut.io.uDeallocSeqWrap.expect(false.B)
      dut.io.uDeallocSeqValue.expect(2.U)
      dut.io.tValidMask.expect(0.U)
      dut.io.uValidMask.expect(0.U)
      dut.io.tRetiredMask.expect(0.U)
      dut.io.uRetiredMask.expect(0.U)
      dut.io.tUsedEntries.expect(0.U)
      dut.io.uUsedEntries.expect(0.U)
    }
  }

  test("sim holds store split enqueue until the renamed row is accepted") {
    simulate(new DecodeRenameROBPathIdentityProbe(reducedStoreDispatchBypass = false)) { dut =>
      def idle(): Unit = {
        dut.io.decodeValid.poke(false.B)
        dut.io.usePredecoded.poke(false.B)
        dut.io.decodeInsn.poke(0.U)
        dut.io.decodeLenBytes.poke(2.U)
        dut.io.decodePc.poke(0.U)
        dut.io.decodeLast.poke(false.B)
        dut.io.flushValid.poke(false.B)
        dut.io.renamedOutReady.poke(true.B)
        dut.io.completeValid.poke(false.B)
        dut.io.completeRobValue.poke(0.U)
        dut.io.completeBlockBid.poke(0.U)
        dut.io.completePc.poke(0.U)
      }

      def presentSc(ready: Boolean): Unit = {
        dut.io.decodeValid.poke(true.B)
        dut.io.decodeInsn.poke(BigInt("2000100b", 16).U)
        dut.io.decodeLenBytes.poke(4.U)
        dut.io.decodePc.poke(0x6242.U)
        dut.io.decodeLast.poke(false.B)
        dut.io.flushValid.poke(false.B)
        dut.io.renamedOutReady.poke(ready.B)
        dut.io.completeValid.poke(false.B)
      }

      idle()
      dut.clock.step()

      presentSc(ready = false)
      dut.io.decodeReady.expect(true.B)
      dut.io.renamedAccepted.expect(false.B)
      dut.io.storeDispatchFire.expect(false.B)
      dut.io.storeStaEnqueueFire.expect(false.B)
      dut.io.storeStdEnqueueFire.expect(false.B)
      dut.io.storeStaQueueCount.expect(0.U)
      dut.io.storeStdQueueCount.expect(0.U)
      dut.clock.step()

      for (_ <- 0 until 3) {
        dut.io.decodeValid.poke(false.B)
        dut.io.decodeInsn.poke(0.U)
        dut.io.decodeLenBytes.poke(2.U)
        dut.io.decodePc.poke(0.U)
        dut.io.decodeLast.poke(false.B)
        dut.io.flushValid.poke(false.B)
        dut.io.renamedOutReady.poke(false.B)
        dut.io.completeValid.poke(false.B)
        dut.io.renamedAccepted.expect(false.B)
        dut.io.storeDispatchFire.expect(false.B)
        dut.io.storeStaEnqueueFire.expect(false.B)
        dut.io.storeStdEnqueueFire.expect(false.B)
        dut.io.storeStaQueueCount.expect(0.U)
        dut.io.storeStdQueueCount.expect(0.U)
        dut.clock.step()
      }

      dut.io.decodeValid.poke(false.B)
      dut.io.decodeInsn.poke(0.U)
      dut.io.decodeLenBytes.poke(2.U)
      dut.io.decodePc.poke(0.U)
      dut.io.decodeLast.poke(false.B)
      dut.io.flushValid.poke(false.B)
      dut.io.renamedOutReady.poke(true.B)
      dut.io.completeValid.poke(false.B)
      dut.io.renamedAccepted.expect(true.B)
      dut.io.storeDispatchFire.expect(true.B)
      dut.io.storeDispatchSplit.expect(true.B)
      dut.io.storeStaEnqueueFire.expect(true.B)
      dut.io.storeStdEnqueueFire.expect(true.B)
      dut.clock.step()

      idle()
      dut.io.storeStaQueueValid.expect(true.B)
      dut.io.storeStdQueueValid.expect(true.B)
      dut.io.storeStaQueueCount.expect(1.U)
      dut.io.storeStdQueueCount.expect(1.U)
      dut.io.storeStaQueueRid.expect(0.U)
      dut.io.storeStdQueueRid.expect(0.U)
      dut.io.storeStaQueueLsId.expect(0.U)
      dut.io.storeStdQueueLsId.expect(0.U)
      dut.io.storeStaEnqueueFire.expect(false.B)
      dut.io.storeStdEnqueueFire.expect(false.B)
      dut.clock.step(2)
      dut.io.storeStaQueueCount.expect(1.U)
      dut.io.storeStdQueueCount.expect(1.U)

      idle()
      dut.io.flushValid.poke(true.B)
      dut.io.renamedAccepted.expect(false.B)
      dut.io.storeDispatchFire.expect(false.B)
      dut.io.storeStaEnqueueFire.expect(false.B)
      dut.io.storeStdEnqueueFire.expect(false.B)
      dut.clock.step()
      idle()
      dut.io.storeStaQueueValid.expect(false.B)
      dut.io.storeStdQueueValid.expect(false.B)
      dut.io.storeStaQueueCount.expect(0.U)
      dut.io.storeStdQueueCount.expect(0.U)
    }
  }

  test("DecodeRenameROBPath elaborates marker decode context in opt-in mode") {
    val p = InterfaceParams(robEntries = 8, commitWidth = 2)
    val trace = CommitTraceParams(commitWidth = 2, robValueWidth = p.robIndexWidth)
    val sv = ChiselStage.emitSystemVerilog(
      new DecodeRenameROBPath(
        p = p,
        traceParams = trace,
        mapQDepth = 8,
        useMarkerDecodeContext = true)
    )

    assert(sv.contains("BlockMarkerDecodeContext"))
    assert(sv.contains("io_decodeValid"))
    assert(sv.contains("io_decodeBlockBid"))
    assert(sv.contains("io_decodeUsesExistingBlock"))
    assert(sv.contains("io_samePacketNextSlotValid"))
    assert(sv.contains("io_serviceAdjacentStop_valid"))
  }

  test("DecodeRenameROBPath exposes full commit-head identity including GID") {
    val p = InterfaceParams(robEntries = 8, commitWidth = 2)
    val trace = CommitTraceParams(commitWidth = 2, robValueWidth = p.robIndexWidth)
    val sv = ChiselStage.emitSystemVerilog(
      new DecodeRenameROBPath(
        p = p,
        traceParams = trace,
        mapQDepth = 8)
    )

    assert(sv.contains("io_commitHeadBid_valid"))
    assert(sv.contains("io_commitHeadGid_valid"))
    assert(sv.contains("io_commitHeadRid_valid"))
  }

  test("DecodeRenameROBPath elaborates two STID BROB and GPR rename ownership lanes") {
    val p = InterfaceParams(robEntries = 8, commitWidth = 2)
    val trace = CommitTraceParams(commitWidth = 2, robValueWidth = p.robIndexWidth)
    val sv = ChiselStage.emitSystemVerilog(
      new DecodeRenameROBPath(
        p = p,
        traceParams = trace,
        mapQDepth = 8,
        gprMapQDepth = 8,
        scalarStidCount = 2)
    )

    assert(sv.contains("io_checkpointStid"))
    assert(sv.contains("io_commitStid"))
    assert(sv.contains("io_enq_bits_stid"))
    assert(sv.contains("GPRReservationTracker"))
    assert(sv.contains("io_recoveryOldestValid_1"))
    assert(sv.contains("io_recoveryOldestBlockBid_1"))
    assert(sv.contains("io_recoveryOldestBid_1_value"))
    assert(sv.contains("io_recoveryOldestRid_1_wrap"))
    assert(sv.contains("io_recoveryOldestBlockComplete_1"))
  }

  test("predecoded D1 ingress bypasses packet decode and preserves the final prediction sidecar") {
    simulate(new DecodeRenameROBPathIdentityProbe) { dut =>
      dut.io.decodeValid.poke(true.B)
      dut.io.usePredecoded.poke(true.B)
      dut.io.decodeInsn.poke("hffffffffffffffff".U)
      dut.io.decodeLenBytes.poke(4.U)
      dut.io.decodePc.poke(0x4800.U)
      dut.io.decodeLast.poke(false.B)
      dut.io.flushValid.poke(false.B)
      dut.io.renamedOutReady.poke(true.B)
      dut.io.completeValid.poke(false.B)
      dut.io.completeRobValue.poke(0.U)
      dut.io.completeBlockBid.poke(0.U)
      dut.io.completePc.poke(0.U)

      dut.io.decodeReady.expect(true.B)
      dut.io.decodedValidMask.expect("b0100".U)
      dut.io.selectedValid.expect(true.B)
      dut.io.selectedSlot.expect(2.U)
      dut.clock.step()
      dut.io.decodeValid.poke(false.B)

      var sawRename = false
      for (_ <- 0 until 8) {
        if (dut.io.renamedAccepted.peek().litToBoolean) {
          dut.io.renamedPc.expect(0x4800.U)
          dut.io.renamedPredictionTag.expect(0xabc.U)
          sawRename = true
        }
        dut.clock.step()
      }
      assert(sawRename, "predecoded row must reach rename without a packet/window reconstruction")
    }
  }

  test("service-adjacent stop classifier accepts only an exact ACRC plus same-packet C.BSTOP pair") {
    val p = InterfaceParams(robEntries = 8)
    simulate(new DecodeRenameServiceAdjacentStopClassifier(p, bidWidth = 16, stidWidth = 8)) { dut =>
      dut.io.selected.poke(0.U.asTypeOf(dut.io.selected))
      dut.io.nextSlot.poke(0.U.asTypeOf(dut.io.nextSlot))
      dut.io.nextSlotValid.poke(true.B)

      dut.io.selected.valid.poke(true.B)
      dut.io.selected.opcode.poke(FrontendOpcodeDecodeTable.OP_ACRC.U)
      dut.io.selected.pc.poke(0x1000.U)
      dut.io.selected.insnLen.poke(4.U)
      dut.io.selected.threadId.poke(3.U)
      dut.io.selected.bid.valid.poke(true.B)
      dut.io.selected.bid.value.poke(1.U)
      dut.io.selected.gid.valid.poke(true.B)
      dut.io.selected.gid.value.poke(2.U)
      dut.io.selected.rid.valid.poke(true.B)
      dut.io.selected.rid.value.poke(3.U)
      dut.io.selected.blockBidValid.poke(true.B)
      dut.io.selected.blockBid.poke(0x44.U)

      dut.io.nextSlot.valid.poke(true.B)
      dut.io.nextSlot.pc.poke(0x1004.U)
      dut.io.nextSlot.lenBytes.poke(2.U)
      dut.io.nextSlot.insnRaw.poke(0.U)

      dut.io.out.valid.expect(true.B)
      dut.io.out.pc.expect(0x1004.U)
      dut.io.out.len.expect(2.U)
      dut.io.out.stid.expect(3.U)
      dut.io.out.bid.value.expect(1.U)
      dut.io.out.gid.value.expect(2.U)
      dut.io.out.rid.value.expect(3.U)
      dut.io.out.blockBid.expect(0x44.U)

      dut.io.nextSlot.pc.poke(0x1006.U)
      dut.io.out.valid.expect(false.B)
      dut.io.nextSlot.pc.poke(0x1004.U)
      dut.io.nextSlotValid.poke(false.B)
      dut.io.out.valid.expect(false.B)
      dut.io.nextSlotValid.poke(true.B)
      dut.io.selected.opcode.poke(FrontendOpcodeDecodeTable.OP_ADDI.U)
      dut.io.out.valid.expect(false.B)
    }
  }
}
