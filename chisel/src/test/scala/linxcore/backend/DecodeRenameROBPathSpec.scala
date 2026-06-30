package linxcore.backend

import circt.stage.ChiselStage
import linxcore.commit.CommitTraceParams
import linxcore.common.InterfaceParams
import linxcore.rename.{TULinkFlushSequencePublisherReference, TULinkFlushSourceSelectorReference}
import linxcore.rob.ROBIDValue
import org.scalatest.funsuite.AnyFunSuite

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

  def robReservationAttemptValid(inputValid: Boolean, queueReady: Boolean): Boolean =
    inputValid && queueReady

  def decodeReady(queueReady: Boolean, robReady: Boolean): Boolean =
    queueReady && robReady

  def queuePushReady(count: Int, depth: Int, popFire: Boolean, flush: Boolean = false): Boolean = {
    require(depth > 0 && (depth & (depth - 1)) == 0)
    !flush && (count < depth || popFire)
  }

  def storeDispatchReady(
      valid: Boolean,
      isStore: Boolean,
      split: Boolean,
      staReady: Boolean,
      stdReady: Boolean): Boolean = {
    val active = valid && isStore
    !active || (if (split) staReady && stdReady else staReady)
  }

  def activeTuBankStid(threadId: Int): Int =
    threadId

  def activeTuBankPe(peId: Int): Int =
    peId

  final case class MarkerStep(
      doneValid: Boolean,
      doneBid: Option[BigInt],
      nextActiveValid: Boolean,
      nextActiveBid: Option[BigInt])

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
}

class DecodeRenameROBPathSpec extends AnyFunSuite {
  import DecodeRenameROBPathReference._

  test("reference selects the oldest decoded slot without compacting later slots") {
    assert(firstValidSlot(0x0, width = 4).isEmpty)
    assert(firstValidSlot(0x1, width = 4).contains(0))
    assert(firstValidSlot(0xa, width = 4).contains(1))
    assert(firstValidSlot(0xc, width = 4).contains(2))
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
    assert(io.blockMarkerSkipValid.getWidth == 1)
    assert(io.blockMarkerMixedPacket.getWidth == 1)
    assert(io.blockMarkerBoundary.getWidth == 1)
    assert(io.blockMarkerStop.getWidth == 1)
    assert(io.blockMarkerPc.getWidth == 64)
    assert(io.blockMarkerInsn.getWidth == 64)
    assert(io.blockMarkerLen.getWidth == 4)
    assert(io.blockMarkerAllocFire.getWidth == 1)
    assert(io.blockMarkerAllocBid.getWidth == 64)
    assert(io.blockMarkerActiveValid.getWidth == 1)
    assert(io.blockMarkerActiveBid.getWidth == 64)
    assert(io.decodeReady.getWidth == 1)
    assert(io.decRenPushFire.getWidth == 1)
    assert(io.decRenPopFire.getWidth == 1)
    assert(io.decRenHead.getWidth == 2)
    assert(io.decRenTail.getWidth == 2)
    assert(io.decRenCount.getWidth == 3)
    assert(io.selectedLsId.getWidth == 32)
    assert(io.selectedLoadId.getWidth == 64)
    assert(io.selectedStoreId.getWidth == 64)
    assert(io.nextLsId.getWidth == 32)
    assert(io.nextLoadId.getWidth == 64)
    assert(io.nextStoreId.getWidth == 64)
    assert(io.storeSplitIntent.getWidth == 1)
    assert(io.renamedOut.peId.getWidth == 8)
    assert(io.renamedOut.threadId.getWidth == 8)
    assert(io.storeStaExec.valid.getWidth == 1)
    assert(io.storeStdExec.addr.getWidth == 64)
    assert(io.storeMarkCommitIndex.getWidth == 3)
    assert(io.storeCommitFreeMask.getWidth == 8)
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
    assert(io.storeStqInsertAccepted.getWidth == 1)
    assert(io.storeStqInsertIndex.getWidth == 3)
    assert(io.storeStqFlushFreeMask.getWidth == 8)
    assert(io.storeStqFlushFreeCount.getWidth == 4)
    assert(io.storeSta.tSeq.value.getWidth == 5)
    assert(io.storeStd.uSeq.value.getWidth == 5)
    assert(io.storeStaQueue.tuDstValid.getWidth == 1)
    assert(io.storeLsuTULinkSource.tSeq.value.getWidth == 5)
    assert(io.storeLsuTULinkSourceMatched.getWidth == 1)
    assert(io.storeStqOccupiedMask.getWidth == 8)
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
    assert(io.robTULinkSource.tSeq.value.getWidth == 5)
    assert(io.robTULinkSource.uSeq.value.getWidth == 5)
    assert(io.robTULinkSourceMatched.getWidth == 1)
    assert(io.robTULinkSourceMultipleMatch.getWidth == 1)
    assert(io.robDeallocTURetireSource.length == 2)
    assert(io.robDeallocTURetireSource(0).tSeq.value.getWidth == 5)
    assert(io.robDeallocTURetireSource(0).peId.getWidth == 8)
    assert(io.robDeallocTURetireSource(0).isLast.getWidth == 1)
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
    assert(io.tuRetireCommandSeq.value.getWidth == 5)
    assert(io.tuRetireCommandDealloc.getWidth == 1)
    assert(io.tuRetireCommandPeId.getWidth == 8)
    assert(io.tuRetireCommandStid.getWidth == 8)
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
    assert(io.occupiedMask.getWidth == 8)
    assert(io.blockAllocatedMask.getWidth == 8)
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
    assert(sv.contains("io_storeStqInsertAccepted"))
    assert(sv.contains("io_storeStqOccupiedMask"))
    assert(sv.contains("io_selectedLsId"))
    assert(sv.contains("io_decRenCount"))
    assert(sv.contains("io_robAllocAttemptValid"))
    assert(sv.contains("io_robRenameUpdateAttemptValid"))
    assert(sv.contains("io_robRenameUpdateFire"))
    assert(sv.contains("io_completeRowValid"))
    assert(sv.contains("io_completeRow_wb_data"))
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
    assert(sv.contains("io_robTULinkSource_tSeq_value"))
    assert(sv.contains("io_robTULinkSourceMatched"))
    assert(sv.contains("io_robDeallocTURetireSource_0_tSeq_value"))
    assert(sv.contains("io_robDeallocTURetireSource_0_peId"))
    assert(sv.contains("io_robDeallocTURetireSource_0_isLast"))
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
    assert(sv.contains("io_tuRetireCommandPeId"))
    assert(sv.contains("io_tuRetireCommandStid"))
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
}
