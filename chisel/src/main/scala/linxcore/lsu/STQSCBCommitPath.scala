package linxcore.lsu

import chisel3._
import chisel3.util.{Fill, log2Ceil}

import linxcore.common.{InterfaceParams, TULinkFlushSequenceSource}
import linxcore.recovery.FlushBus

class STQSCBCommitPathIO(
    val entries: Int,
    val queueEntries: Int,
    val issueWidth: Int,
    val scbEntries: Int,
    val scbResponseBufferDepth: Int,
    val addrWidth: Int = 64,
    val dataWidth: Int = 64,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val sizeWidth: Int = 4,
    val simtLaneWidth: Int = 8,
    val lineBytes: Int = 64,
    val mapQDepth: Int = 32,
    val robEntries: Int = 0,
    val lsidWidth: Int = 32)
    extends Bundle {
  private val identityEntries = if (robEntries > 0) robEntries else entries
  private val ptrWidth = log2Ceil(entries)
  private val stqCountWidth = log2Ceil(entries + 1)
  private val queueCountWidth = log2Ceil(queueEntries + 1)
  private val issueCountWidth = log2Ceil(issueWidth + 1)
  private val scbCountWidth = log2Ceil(scbEntries + 1)
  private val requestCount = issueWidth * 2
  private val requestCountWidth = log2Ceil(requestCount + 1)
  private val scbIndexWidth = math.max(1, log2Ceil(scbEntries))
  private val scbResponseTxnIdWidth = scbIndexWidth + 2
  private val scbResponseBufferCountWidth = log2Ceil(scbResponseBufferDepth + 1)
  private val sourceParams = InterfaceParams(robEntries = identityEntries)

  val flush = Input(new FlushBus(identityEntries, peIdWidth, stidWidth, tidWidth, lsidWidth))

  val insertValid = Input(Bool())
  val insert = Input(new STQStoreRequest(identityEntries, addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth, sizeWidth, simtLaneWidth, mapQDepth, 64, lsidWidth))
  val insertReady = Output(Bool())
  val insertAccepted = Output(Bool())
  val insertAllocated = Output(Bool())
  val insertMerged = Output(Bool())
  val insertConflict = Output(Bool())
  val insertIndex = Output(UInt(ptrWidth.W))

  val markCommitValid = Input(Bool())
  val markCommitIndex = Input(UInt(ptrWidth.W))
  val markCommitAccepted = Output(Bool())
  val markCommitIgnored = Output(Bool())

  val issueEnable = Input(Bool())
  val evictEnable = Input(Bool())
  val dcacheReady = Input(Bool())
  val dcacheWriteHit = Input(Bool())
  val dcacheTagHit = Input(Bool())
  val l2RequestReady = Input(Bool())
  val rawRespValid = Input(Bool())
  val rawRespTxnId = Input(UInt(scbResponseTxnIdWidth.W))
  val rawRespWrite = Input(Bool())
  val rawRespUpgrade = Input(Bool())
  val rawRespReady = Output(Bool())

  val scbReadyForDrain = Output(Bool())
  val drainIssueEnable = Output(Bool())
  val downstreamReadyMask = Output(UInt(entries.W))

  val stqFlushApplied = Output(Bool())
  val stqFlushMatchMask = Output(UInt(entries.W))
  val stqFlushFreeMask = Output(UInt(entries.W))
  val stqFlushStatusBlockedMask = Output(UInt(entries.W))
  val stqFlushFreeCount = Output(UInt(stqCountWidth.W))
  val lsuTULinkSource = Output(new TULinkFlushSequenceSource(sourceParams, mapQDepth, stidWidth))
  val lsuTULinkSourceMatched = Output(Bool())
  val lsuTULinkSourceMultipleMatch = Output(Bool())
  val stqRows = Output(Vec(entries, new STQEntryBankRow(identityEntries, addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth, sizeWidth, simtLaneWidth, mapQDepth, 64, lsidWidth)))
  val stqOccupiedMask = Output(UInt(entries.W))
  val stqWaitMask = Output(UInt(entries.W))
  val stqCommitMask = Output(UInt(entries.W))
  val stqResidentCount = Output(UInt(stqCountWidth.W))
  val stqOutstandingWaitCount = Output(UInt(stqCountWidth.W))
  val stqEmpty = Output(Bool())
  val stqFull = Output(Bool())
  val stqStall = Output(Bool())

  val drainEnqueueReady = Output(Bool())
  val drainEnqueueAccepted = Output(Bool())
  val drainEnqueueDuplicate = Output(Bool())
  val drainEnqueueInsertPosition = Output(UInt(queueCountWidth.W))
  val drainCommitEligibleMask = Output(UInt(entries.W))
  val drainSplitMask = Output(UInt(entries.W))
  val drainReadyMask = Output(UInt(entries.W))
  val drainIssue = Output(Vec(issueWidth, new STQCommitIssue(identityEntries, entries, lsidWidth)))
  val drainIssueValidMask = Output(UInt(issueWidth.W))
  val drainIssueCount = Output(UInt(issueCountWidth.W))
  val drainMemReqs = Output(Vec(requestCount, new STQCommitDrainRequest(entries, addrWidth, dataWidth, sizeWidth, identityEntries, lsidWidth)))
  val drainEarlyFreeMaskValid = Output(Bool())
  val drainEarlyFreeMask = Output(UInt(entries.W))
  val drainEarlyFreeCount = Output(UInt(issueCountWidth.W))
  val drainQueued = Output(Vec(queueEntries, new STQCommitQueueEntry(identityEntries, entries, lsidWidth)))
  val drainQueuedValidMask = Output(UInt(queueEntries.W))
  val drainQueueCount = Output(UInt(queueCountWidth.W))
  val drainEmpty = Output(Bool())
  val drainFull = Output(Bool())
  val drainOrderError = Output(Bool())

  val scbModelBatchReady = Output(Bool())
  val scbModelFull = Output(Bool())
  val scbAcceptedMask = Output(UInt(requestCount.W))
  val scbStalledMask = Output(UInt(requestCount.W))
  val scbStructuralBlockedMask = Output(UInt(requestCount.W))
  val scbCommitFreeMaskValid = Output(Bool())
  val scbCommitFreeMask = Output(UInt(entries.W))
  val scbCommitFreeCount = Output(UInt(requestCountWidth.W))
  val scbWakeups = Output(Vec(requestCount, new SCBCommitWakeup(addrWidth, lineBytes)))
  val scbEntriesState = Output(Vec(scbEntries, new SCBLineEntry(addrWidth, lineBytes)))
  val scbNextEntries = Output(Vec(scbEntries, new SCBLineEntry(addrWidth, lineBytes)))
  val scbValidMask = Output(UInt(scbEntries.W))
  val scbFullLineMask = Output(UInt(scbEntries.W))
  val scbEntryCount = Output(UInt(scbCountWidth.W))
  val scbFreeCount = Output(UInt(scbCountWidth.W))
  val scbLookupRequest = Output(new SCBEgressLookupRequest(scbEntries, addrWidth, lineBytes))
  val scbLookupFire = Output(Bool())
  val scbLookupStall = Output(Bool())
  val scbDCacheUpdate = Output(new SCBDCacheUpdate(scbEntries, addrWidth, lineBytes))
  val scbL2Request = Output(new SCBL2OwnershipRequest(scbEntries, addrWidth, lineBytes))
  val scbStateIllegalMask = Output(UInt(scbEntries.W))
  val scbStateError = Output(Bool())
  val scbRespDecodedValid = Output(Bool())
  val scbRespDecodedUpgrade = Output(Bool())
  val scbRespDecodedEntryIndex = Output(UInt(scbIndexWidth.W))
  val scbRespDecodedMask = Output(UInt(scbEntries.W))
  val scbRespTypeIllegal = Output(Bool())
  val scbRespTagIllegal = Output(Bool())
  val scbRespIndexIllegal = Output(Bool())
  val scbRespStateIllegalMask = Output(UInt(scbEntries.W))
  val scbRespDecodeError = Output(Bool())
  val scbRespBufferAccepted = Output(Bool())
  val scbRespBufferHeadValid = Output(Bool())
  val scbRespBufferHeadConsumed = Output(Bool())
  val scbRespBufferHeadTxnId = Output(UInt(scbResponseTxnIdWidth.W))
  val scbRespBufferFull = Output(Bool())
  val scbRespBufferEmpty = Output(Bool())
  val scbRespBufferCount = Output(UInt(scbResponseBufferCountWidth.W))

  val stqCommitFreeAcceptedMask = Output(UInt(entries.W))
  val stqCommitFreeIgnoredMask = Output(UInt(entries.W))
  val stqCommitFreeCount = Output(UInt(stqCountWidth.W))
}

class STQSCBCommitPath(
    val entries: Int = 16,
    val queueEntries: Int = 16,
    val issueWidth: Int = 2,
    val scbEntries: Int = 16,
    val scbResponseBufferDepth: Int = 4,
    val addrWidth: Int = 64,
    val dataWidth: Int = 64,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val sizeWidth: Int = 4,
    val simtLaneWidth: Int = 8,
    val lineBytes: Int = 64,
    val mapQDepth: Int = 32,
    val robEntries: Int = 0,
    val lsidWidth: Int = 32)
    extends Module {
  private val identityEntries = if (robEntries > 0) robEntries else entries
  require(entries > 1, "STQ entries must be greater than one")
  require(queueEntries > 1, "STQ commit queue entries must be greater than one")
  require(issueWidth > 0, "STQ-to-SCB commit issue width must be nonzero")
  require(issueWidth <= queueEntries, "STQ-to-SCB issue width cannot exceed queue depth")
  require(scbEntries > 0, "SCB entries must be nonzero")
  require(scbResponseBufferDepth > 0, "SCB response buffer depth must be nonzero")
  require(issueWidth * 2 <= scbEntries, "SCB entries must cover the worst-case split-store request batch")
  require((entries & (entries - 1)) == 0, "STQ entries must be a power of two")
  require((queueEntries & (queueEntries - 1)) == 0, "STQ commit queue entries must be a power of two")
  require(identityEntries > 1 && (identityEntries & (identityEntries - 1)) == 0, "ROB entries must be a power of two")

  private val requestCount = issueWidth * 2

  val io = IO(new STQSCBCommitPathIO(
    entries,
    queueEntries,
    issueWidth,
    scbEntries,
    scbResponseBufferDepth,
    addrWidth,
    dataWidth,
    peIdWidth,
    stidWidth,
    tidWidth,
    sizeWidth,
    simtLaneWidth,
    lineBytes,
    mapQDepth,
    identityEntries,
    lsidWidth
  ))

  val stq = Module(new STQEntryBank(
    entries, addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth, sizeWidth,
    simtLaneWidth, mapQDepth, identityEntries, lsidWidth))
  val drain = Module(new STQCommitDrain(
    entries, queueEntries, issueWidth, addrWidth, dataWidth, peIdWidth, stidWidth,
    tidWidth, sizeWidth, simtLaneWidth, mapQDepth, identityEntries, lineBytes, lsidWidth))
  val scb = Module(new SCBRowBank(
    stqEntries = entries,
    scbEntries = scbEntries,
    requestCount = requestCount,
    responseBufferDepth = scbResponseBufferDepth,
    addrWidth = addrWidth,
    dataWidth = dataWidth,
    sizeWidth = sizeWidth,
    lineBytes = lineBytes,
    robEntries = identityEntries,
    lsidWidth = lsidWidth))

  stq.io.flush := io.flush
  stq.io.insertValid := io.insertValid
  stq.io.insert := io.insert
  stq.io.markCommitValid := io.markCommitValid
  stq.io.markCommitIndex := io.markCommitIndex
  stq.io.commitFreeValid := false.B
  stq.io.commitFreeIndex := 0.U
  stq.io.commitFreeMaskValid := scb.io.commitFreeMaskValid
  stq.io.commitFreeMask := scb.io.commitFreeMask

  drain.io.enqueueValid := stq.io.markCommitAccepted
  drain.io.enqueueIndex := io.markCommitIndex
  drain.io.enqueueBid := stq.io.rows(io.markCommitIndex).bid
  drain.io.enqueueLsId := stq.io.rows(io.markCommitIndex).lsIdFull
  drain.io.flushValid := stq.io.flushApplied
  drain.io.rows := stq.io.rows

  val scbReadyForDrain = scb.io.modelBatchReady && !stq.io.flushApplied
  drain.io.issueEnable := io.issueEnable && scbReadyForDrain
  drain.io.primaryReadyMask := Fill(entries, scbReadyForDrain)
  drain.io.secondaryReadyMask := Fill(entries, scbReadyForDrain)

  scb.io.reqs := drain.io.memReqs
  scb.io.evictEnable := io.evictEnable
  scb.io.dcacheReady := io.dcacheReady
  scb.io.dcacheWriteHit := io.dcacheWriteHit
  scb.io.dcacheTagHit := io.dcacheTagHit
  scb.io.l2RequestReady := io.l2RequestReady
  scb.io.rawRespValid := io.rawRespValid
  scb.io.rawRespTxnId := io.rawRespTxnId
  scb.io.rawRespWrite := io.rawRespWrite
  scb.io.rawRespUpgrade := io.rawRespUpgrade

  io.insertReady := stq.io.insertReady
  io.insertAccepted := stq.io.insertAccepted
  io.insertAllocated := stq.io.insertAllocated
  io.insertMerged := stq.io.insertMerged
  io.insertConflict := stq.io.insertConflict
  io.insertIndex := stq.io.insertIndex
  io.markCommitAccepted := stq.io.markCommitAccepted
  io.markCommitIgnored := stq.io.markCommitIgnored

  io.scbReadyForDrain := scbReadyForDrain
  io.drainIssueEnable := drain.io.issueEnable
  io.downstreamReadyMask := Fill(entries, scbReadyForDrain)

  io.stqFlushApplied := stq.io.flushApplied
  io.stqFlushMatchMask := stq.io.flushMatchMask
  io.stqFlushFreeMask := stq.io.flushFreeMask
  io.stqFlushStatusBlockedMask := stq.io.flushStatusBlockedMask
  io.stqFlushFreeCount := stq.io.flushFreeCount
  io.lsuTULinkSource := stq.io.lsuTULinkSource
  io.lsuTULinkSourceMatched := stq.io.lsuTULinkSourceMatched
  io.lsuTULinkSourceMultipleMatch := stq.io.lsuTULinkSourceMultipleMatch
  io.stqRows := stq.io.rows
  io.stqOccupiedMask := stq.io.occupiedMask
  io.stqWaitMask := stq.io.waitMask
  io.stqCommitMask := stq.io.commitMask
  io.stqResidentCount := stq.io.residentCount
  io.stqOutstandingWaitCount := stq.io.outstandingWaitCount
  io.stqEmpty := stq.io.empty
  io.stqFull := stq.io.full
  io.stqStall := stq.io.stall

  io.drainEnqueueReady := drain.io.enqueueReady
  io.drainEnqueueAccepted := drain.io.enqueueAccepted
  io.drainEnqueueDuplicate := drain.io.enqueueDuplicate
  io.drainEnqueueInsertPosition := drain.io.enqueueInsertPosition
  io.drainCommitEligibleMask := drain.io.commitEligibleMask
  io.drainSplitMask := drain.io.splitMask
  io.drainReadyMask := drain.io.readyMask
  io.drainIssue := drain.io.issue
  io.drainIssueValidMask := drain.io.issueValidMask
  io.drainIssueCount := drain.io.issueCount
  io.drainMemReqs := drain.io.memReqs
  io.drainEarlyFreeMaskValid := drain.io.commitFreeMaskValid
  io.drainEarlyFreeMask := drain.io.commitFreeMask
  io.drainEarlyFreeCount := drain.io.commitFreeCount
  io.drainQueued := drain.io.queued
  io.drainQueuedValidMask := drain.io.queuedValidMask
  io.drainQueueCount := drain.io.queueCount
  io.drainEmpty := drain.io.empty
  io.drainFull := drain.io.full
  io.drainOrderError := drain.io.orderError

  io.scbModelBatchReady := scb.io.modelBatchReady
  io.scbModelFull := scb.io.modelFull
  io.rawRespReady := scb.io.rawRespReady
  io.scbAcceptedMask := scb.io.acceptedMask
  io.scbStalledMask := scb.io.stalledMask
  io.scbStructuralBlockedMask := scb.io.structuralBlockedMask
  io.scbCommitFreeMaskValid := scb.io.commitFreeMaskValid
  io.scbCommitFreeMask := scb.io.commitFreeMask
  io.scbCommitFreeCount := scb.io.commitFreeCount
  io.scbWakeups := scb.io.wakeups
  io.scbEntriesState := scb.io.entries
  io.scbNextEntries := scb.io.nextEntries
  io.scbValidMask := scb.io.validMask
  io.scbFullLineMask := scb.io.fullLineMask
  io.scbEntryCount := scb.io.entryCount
  io.scbFreeCount := scb.io.freeCount
  io.scbLookupRequest := scb.io.lookupRequest
  io.scbLookupFire := scb.io.lookupFire
  io.scbLookupStall := scb.io.lookupStall
  io.scbDCacheUpdate := scb.io.dcacheUpdate
  io.scbL2Request := scb.io.l2Request
  io.scbStateIllegalMask := scb.io.stateIllegalMask
  io.scbStateError := scb.io.stateError
  io.scbRespDecodedValid := scb.io.respDecodedValid
  io.scbRespDecodedUpgrade := scb.io.respDecodedUpgrade
  io.scbRespDecodedEntryIndex := scb.io.respDecodedEntryIndex
  io.scbRespDecodedMask := scb.io.respDecodedMask
  io.scbRespTypeIllegal := scb.io.respTypeIllegal
  io.scbRespTagIllegal := scb.io.respTagIllegal
  io.scbRespIndexIllegal := scb.io.respIndexIllegal
  io.scbRespStateIllegalMask := scb.io.respStateIllegalMask
  io.scbRespDecodeError := scb.io.respDecodeError
  io.scbRespBufferAccepted := scb.io.respBufferAccepted
  io.scbRespBufferHeadValid := scb.io.respBufferHeadValid
  io.scbRespBufferHeadConsumed := scb.io.respBufferHeadConsumed
  io.scbRespBufferHeadTxnId := scb.io.respBufferHeadTxnId
  io.scbRespBufferFull := scb.io.respBufferFull
  io.scbRespBufferEmpty := scb.io.respBufferEmpty
  io.scbRespBufferCount := scb.io.respBufferCount

  io.stqCommitFreeAcceptedMask := stq.io.commitFreeAcceptedMask
  io.stqCommitFreeIgnoredMask := stq.io.commitFreeIgnoredMask
  io.stqCommitFreeCount := stq.io.commitFreeCount
}
