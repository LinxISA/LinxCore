package linxcore.backend

import chisel3._
import chisel3.util.{log2Ceil, PriorityEncoder}

import linxcore.bctrl.BID
import linxcore.commit.{CommitTraceParams, CommitTracePort}
import linxcore.common._
import linxcore.frontend.{F4Slot, FrontendDecodeStage}
import linxcore.recovery.{FullBidRecoveryBridge, RecoveryCleanupIntent}
import linxcore.rename.ScalarDecodeRenameBridge
import linxcore.rob.{ROBEntryStatus, ROBID}

class DecodeRenameROBPathIO(
    val p: InterfaceParams = InterfaceParams(),
    val traceParams: CommitTraceParams = CommitTraceParams(),
    val bidWidth: Int = BID.DefaultWidth,
    val stidWidth: Int = 8,
    val peIdWidth: Int = 8,
    val tidWidth: Int = 8,
    val blockTypeWidth: Int = 4,
    val trapCauseWidth: Int = 32,
    val decRenQueueDepth: Int = 4,
    val loadStoreSerialWidth: Int = 64)
    extends Bundle {
  private val slotWidth = math.max(1, log2Ceil(p.decodeWidth))
  private val ptrWidth = log2Ceil(p.robEntries)
  private val sizeWidth = log2Ceil(p.robEntries + 1)
  private val decRenPtrWidth = math.max(1, log2Ceil(decRenQueueDepth))
  private val decRenCountWidth = log2Ceil(decRenQueueDepth + 1)

  val d1 = Input(new FrontendDecodePacket(p))
  val slots = Input(Vec(p.decodeWidth, new F4Slot(p)))
  val validMask = Input(UInt(p.decodeWidth.W))
  val flushValid = Input(Bool())

  val renamedOutReady = Input(Bool())
  val checkpointValid = Input(Bool())
  val checkpointBid = Input(new ROBID(p.robEntries))
  val commitValid = Input(Bool())
  val commitBid = Input(new ROBID(p.robEntries))
  val cleanup = Input(new RecoveryCleanupIntent(p.robEntries, bidWidth, peIdWidth, stidWidth, tidWidth))

  val completeValid = Input(Bool())
  val completeRobValue = Input(UInt(ptrWidth.W))
  val deallocReady = Input(Bool())

  val decodedValidMask = Output(UInt(p.decodeWidth.W))
  val invalidOpcodeMask = Output(UInt(p.decodeWidth.W))
  val blockBoundaryMask = Output(UInt(p.decodeWidth.W))
  val blockStopMask = Output(UInt(p.decodeWidth.W))
  val loadMask = Output(UInt(p.decodeWidth.W))
  val storeMask = Output(UInt(p.decodeWidth.W))
  val selectedValid = Output(Bool())
  val selectedSlot = Output(UInt(slotWidth.W))
  val selectedRobValue = Output(UInt(ptrWidth.W))
  val selectedBlockBid = Output(UInt(bidWidth.W))
  val decodeReady = Output(Bool())
  val decRenPushReady = Output(Bool())
  val decRenPushFire = Output(Bool())
  val decRenPopFire = Output(Bool())
  val decRenValid = Output(Bool())
  val decRenHead = Output(UInt(decRenPtrWidth.W))
  val decRenTail = Output(UInt(decRenPtrWidth.W))
  val decRenCount = Output(UInt(decRenCountWidth.W))
  val decRenEmpty = Output(Bool())
  val decRenFull = Output(Bool())
  val selectedIsLoad = Output(Bool())
  val selectedIsStore = Output(Bool())
  val selectedMemoryValid = Output(Bool())
  val lsidAssignFire = Output(Bool())
  val selectedLsId = Output(UInt(p.lsidWidth.W))
  val selectedLoadId = Output(UInt(loadStoreSerialWidth.W))
  val selectedStoreId = Output(UInt(loadStoreSerialWidth.W))
  val nextLsId = Output(UInt(p.lsidWidth.W))
  val nextLoadId = Output(UInt(loadStoreSerialWidth.W))
  val nextStoreId = Output(UInt(loadStoreSerialWidth.W))
  val storeSplitIntent = Output(Bool())

  val renamedOutValid = Output(Bool())
  val renamedOut = Output(new RenamedUop(p))
  val accepted = Output(Bool())
  val robAllocAttemptValid = Output(Bool())
  val robAllocReady = Output(Bool())
  val robAllocFire = Output(Bool())
  val robAllocBlockedByBrob = Output(Bool())
  val robAllocBlockedByRob = Output(Bool())
  val robAllocDuplicateIdentity = Output(Bool())
  val blockedByMaintenance = Output(Bool())
  val blockedByRename = Output(Bool())
  val blockedByRob = Output(Bool())
  val blockedByOutput = Output(Bool())
  val unsupported = Output(Bool())
  val unsupportedSrcMask = Output(UInt(3.W))
  val unsupportedDst = Output(Bool())
  val unsupportedOperandClass = Output(Bool())

  val allocBlockBid = Output(UInt(bidWidth.W))
  val allocRobValue = Output(UInt(ptrWidth.W))
  val completeAccepted = Output(Bool())
  val completeIgnored = Output(Bool())
  val commit = Output(new CommitTracePort(traceParams))
  val commitValidMask = Output(UInt(traceParams.commitWidth.W))
  val commitCount = Output(UInt(log2Ceil(traceParams.commitWidth + 1).W))
  val commitMonitorValidMask = Output(UInt(traceParams.commitWidth.W))
  val commitMonitorValidCount = Output(UInt(log2Ceil(traceParams.commitWidth + 1).W))
  val commitSkippedSlot = Output(Bool())
  val commitDuplicateIdentity = Output(Bool())
  val commitSlotMismatch = Output(Bool())
  val commitInvalidSideEffect = Output(Bool())
  val commitContractError = Output(Bool())

  val deallocValidMask = Output(UInt(traceParams.commitWidth.W))
  val deallocCount = Output(UInt(log2Ceil(traceParams.commitWidth + 1).W))

  val flushApplied = Output(Bool())
  val empty = Output(Bool())
  val full = Output(Bool())
  val size = Output(UInt(sizeWidth.W))
  val outstandingCount = Output(UInt(sizeWidth.W))
  val commitHeadValid = Output(Bool())
  val commitHeadStatus = Output(ROBEntryStatus())
  val commitHeadRobValue = Output(UInt(ptrWidth.W))
  val occupiedMask = Output(UInt(p.robEntries.W))
  val completedMask = Output(UInt(p.robEntries.W))
  val retiredMask = Output(UInt(p.robEntries.W))
  val blockAllocatedMask = Output(UInt(p.robEntries.W))
  val blockCompleteMask = Output(UInt(p.robEntries.W))
  val blockPendingMask = Output(UInt(p.robEntries.W))
}

class DecodeRenameROBPath(
    val p: InterfaceParams = InterfaceParams(),
    val traceParams: CommitTraceParams = CommitTraceParams(),
    val bidWidth: Int = BID.DefaultWidth,
    val stidWidth: Int = 8,
    val peIdWidth: Int = 8,
    val tidWidth: Int = 8,
    val blockTypeWidth: Int = 4,
    val trapCauseWidth: Int = 32,
    val scalarArchRegs: Int = 24,
    val physRegs: Int = 64,
    val mapQDepth: Int = 32,
    val decRenQueueDepth: Int = 4,
    val loadStoreSerialWidth: Int = 64)
    extends Module {
  require(traceParams.robValueWidth >= p.robIndexWidth, "trace ROB value must hold DecodeRenameROBPath ROB index")
  require(traceParams.commitWidth == p.commitWidth, "trace commit width must match InterfaceParams")
  require((p.robEntries & (p.robEntries - 1)) == 0, "ROB entries must be a power of two")
  require(decRenQueueDepth > 0 && (decRenQueueDepth & (decRenQueueDepth - 1)) == 0,
    "decode-to-rename queue depth must be a power of two")

  private val zeroRobId = 0.U.asTypeOf(new ROBID(p.robEntries))

  val io = IO(new DecodeRenameROBPathIO(
    p,
    traceParams,
    bidWidth,
    stidWidth,
    peIdWidth,
    tidWidth,
    blockTypeWidth,
    trapCauseWidth,
    decRenQueueDepth,
    loadStoreSerialWidth
  ))

  val decode = Module(new FrontendDecodeStage(p))
  decode.io.d1 := io.d1
  decode.io.slots := io.slots
  decode.io.validMask := io.validMask
  decode.io.flushValid := io.flushValid

  io.decodedValidMask := decode.io.outValidMask
  io.invalidOpcodeMask := decode.io.invalidOpcodeMask
  io.blockBoundaryMask := decode.io.blockBoundaryMask
  io.blockStopMask := decode.io.blockStopMask
  io.loadMask := decode.io.loadMask
  io.storeMask := decode.io.storeMask

  val selectedAny = decode.io.outValidMask.orR
  val selectedSlot = PriorityEncoder(decode.io.outValidMask)
  val selected = Wire(new DecodedUop(p))
  selected := 0.U.asTypeOf(selected)
  for (slot <- 0 until p.decodeWidth) {
    when(selectedAny && selectedSlot === slot.U) {
      selected := decode.io.out(slot)
    }
  }

  val selectedIsLoad = selectedAny && VecInit(decode.io.loadMask.asBools)(selectedSlot)
  val selectedIsStore = selectedAny && VecInit(decode.io.storeMask.asBools)(selectedSlot)

  val allocator = Module(new DispatchROBAllocator(
    entries = p.robEntries,
    traceParams = traceParams,
    bidWidth = bidWidth,
    peIdWidth = peIdWidth,
    tidWidth = tidWidth,
    blockTypeWidth = blockTypeWidth,
    trapCauseWidth = trapCauseWidth
  ))

  val decRenFlush = io.flushValid || (io.cleanup.valid && io.cleanup.backendFlushValid)
  val memIds = Module(new DecodeLoadStoreIdAssign(p, serialWidth = loadStoreSerialWidth))
  memIds.io.in := selected
  memIds.io.isLoad := selectedIsLoad
  memIds.io.isStore := selectedIsStore
  memIds.io.isDczva := false.B
  memIds.io.isLoadStorePair := false.B
  memIds.io.isStorePcr := false.B
  memIds.io.cacheMaintainNoSplit := false.B
  memIds.io.storeSplitRequest := selectedIsStore
  memIds.io.stackSetRequest := false.B
  memIds.io.flushValid := decRenFlush
  memIds.io.restoreValid := false.B
  memIds.io.restoreLsId := 0.U
  memIds.io.restoreLoadId := 0.U
  memIds.io.restoreStoreId := 0.U

  val selectedForQueue = Wire(new DecodedUop(p))
  selectedForQueue := memIds.io.out
  selectedForQueue.valid := selectedAny

  val decRenQ = Module(new DecodeRenameQueue(p, depth = decRenQueueDepth))
  decRenQ.io.push := selectedForQueue
  decRenQ.io.flushValid := decRenFlush
  memIds.io.accept := decRenQ.io.pushFire

  val queuedForRename = Wire(new DecodedUop(p))
  queuedForRename := decRenQ.io.out
  queuedForRename.bid :=
    FullBidRecoveryBridge.fullBidToRobId(allocator.io.allocBlockBid, decRenQ.io.out.valid, p.robEntries, bidWidth)
  queuedForRename.gid := zeroRobId
  queuedForRename.gid.valid := decRenQ.io.out.valid
  queuedForRename.rid.valid := decRenQ.io.out.valid
  queuedForRename.rid.wrap := false.B
  queuedForRename.rid.value := allocator.io.allocRobValue
  queuedForRename.blockBidValid := decRenQ.io.out.valid
  queuedForRename.blockBid := allocator.io.allocBlockBid

  val rename = Module(new ScalarDecodeRenameBridge(
    p = p,
    traceParams = traceParams,
    scalarArchRegs = scalarArchRegs,
    physRegs = physRegs,
    mapQDepth = mapQDepth,
    bidWidth = bidWidth,
    stidWidth = stidWidth,
    peIdWidth = peIdWidth,
    tidWidth = tidWidth
  ))
  rename.io.in := queuedForRename
  rename.io.outReady := io.renamedOutReady
  rename.io.robAllocReady := allocator.io.allocReady
  rename.io.checkpointValid := io.checkpointValid
  rename.io.checkpointBid := io.checkpointBid
  rename.io.commitValid := io.commitValid
  rename.io.commitBid := io.commitBid
  rename.io.cleanup := io.cleanup

  decRenQ.io.popReady := rename.io.inReady

  allocator.io.flush := io.cleanup.flush
  allocator.io.allocValid := rename.io.robAllocAttemptValid
  allocator.io.allocRow := rename.io.robAllocRow
  allocator.io.allocTid := queuedForRename.threadId
  allocator.io.allocPeId := 0.U
  allocator.io.allocBlockType := queuedForRename.boundaryKind.asUInt.pad(blockTypeWidth)(blockTypeWidth - 1, 0)
  allocator.io.allocNeedsEngine := false.B
  allocator.io.completeValid := io.completeValid
  allocator.io.completeRobValue := io.completeRobValue
  allocator.io.deallocReady := io.deallocReady
  allocator.io.blockScalarDoneValid := false.B
  allocator.io.blockScalarDoneBid := 0.U
  allocator.io.blockScalarTrapValid := false.B
  allocator.io.blockScalarTrapCause := 0.U
  allocator.io.blockEngineDoneValid := false.B
  allocator.io.blockEngineDoneBid := 0.U
  allocator.io.blockEngineTrapValid := false.B
  allocator.io.blockEngineTrapCause := 0.U
  allocator.io.blockRetireValid := false.B
  allocator.io.blockRetireBid := 0.U
  allocator.io.blockFlushValid := io.cleanup.blockFlushValid
  allocator.io.blockFlushBid := io.cleanup.blockFlushBid
  allocator.io.blockQueryBid := allocator.io.allocBlockBid

  io.selectedValid := selectedAny
  io.selectedSlot := selectedSlot
  io.selectedRobValue := allocator.io.allocRobValue
  io.selectedBlockBid := allocator.io.allocBlockBid
  io.decodeReady := decRenQ.io.pushReady
  io.decRenPushReady := decRenQ.io.pushReady
  io.decRenPushFire := decRenQ.io.pushFire
  io.decRenPopFire := decRenQ.io.popFire
  io.decRenValid := decRenQ.io.out.valid
  io.decRenHead := decRenQ.io.head
  io.decRenTail := decRenQ.io.tail
  io.decRenCount := decRenQ.io.count
  io.decRenEmpty := decRenQ.io.empty
  io.decRenFull := decRenQ.io.full
  io.selectedIsLoad := selectedIsLoad
  io.selectedIsStore := selectedIsStore
  io.selectedMemoryValid := memIds.io.memoryValid
  io.lsidAssignFire := memIds.io.assignFire
  io.selectedLsId := memIds.io.assignedLsId
  io.selectedLoadId := memIds.io.assignedLoadId
  io.selectedStoreId := memIds.io.assignedStoreId
  io.nextLsId := memIds.io.nextLsId
  io.nextLoadId := memIds.io.nextLoadId
  io.nextStoreId := memIds.io.nextStoreId
  io.storeSplitIntent := memIds.io.storeSplitIntent

  io.renamedOutValid := rename.io.outValid
  io.renamedOut := rename.io.out
  io.accepted := rename.io.accepted
  io.robAllocAttemptValid := rename.io.robAllocAttemptValid
  io.robAllocReady := allocator.io.allocReady
  io.robAllocFire := allocator.io.allocFire
  io.robAllocBlockedByBrob := allocator.io.allocBlockedByBrob
  io.robAllocBlockedByRob := allocator.io.allocBlockedByRob
  io.robAllocDuplicateIdentity := allocator.io.allocDuplicateIdentity
  io.blockedByMaintenance := rename.io.blockedByMaintenance
  io.blockedByRename := rename.io.blockedByRename
  io.blockedByRob := rename.io.blockedByRob
  io.blockedByOutput := rename.io.blockedByOutput
  io.unsupported := rename.io.unsupported
  io.unsupportedSrcMask := rename.io.unsupportedSrcMask
  io.unsupportedDst := rename.io.unsupportedDst
  io.unsupportedOperandClass := rename.io.unsupportedOperandClass

  io.allocBlockBid := allocator.io.allocBlockBid
  io.allocRobValue := allocator.io.allocRobValue
  io.completeAccepted := allocator.io.completeAccepted
  io.completeIgnored := allocator.io.completeIgnored
  io.commit := allocator.io.commit
  io.commitValidMask := allocator.io.commitValidMask
  io.commitCount := allocator.io.commitCount
  io.commitMonitorValidMask := allocator.io.commitMonitorValidMask
  io.commitMonitorValidCount := allocator.io.commitMonitorValidCount
  io.commitSkippedSlot := allocator.io.commitSkippedSlot
  io.commitDuplicateIdentity := allocator.io.commitDuplicateIdentity
  io.commitSlotMismatch := allocator.io.commitSlotMismatch
  io.commitInvalidSideEffect := allocator.io.commitInvalidSideEffect
  io.commitContractError := allocator.io.commitContractError
  io.deallocValidMask := allocator.io.deallocValidMask
  io.deallocCount := allocator.io.deallocCount
  io.flushApplied := allocator.io.flushApplied
  io.empty := allocator.io.empty
  io.full := allocator.io.full
  io.size := allocator.io.size
  io.outstandingCount := allocator.io.outstandingCount
  io.commitHeadValid := allocator.io.commitHeadValid
  io.commitHeadStatus := allocator.io.commitHeadStatus
  io.commitHeadRobValue := allocator.io.commitHeadRobValue
  io.occupiedMask := allocator.io.occupiedMask
  io.completedMask := allocator.io.completedMask
  io.retiredMask := allocator.io.retiredMask
  io.blockAllocatedMask := allocator.io.blockAllocatedMask
  io.blockCompleteMask := allocator.io.blockCompleteMask
  io.blockPendingMask := allocator.io.blockPendingMask
}
