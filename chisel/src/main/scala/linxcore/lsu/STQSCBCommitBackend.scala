package linxcore.lsu

import chisel3._
import chisel3.util.{Decoupled, MuxLookup, log2Ceil}

/** Production committed-store backend for an externally owned canonical STQ.
  *
  * This module deliberately does not allocate or retain STQ rows.  The
  * execution-side STQ remains the sole physical owner; this backend resolves
  * generation-qualified ROB tokens against those rows, atomically requests
  * WAIT-to-COMMIT plus CommitQ insertion, and returns rows only after the SCB
  * or serialized transport has accepted the final fragment.
  */
class STQSCBCommitBackendIO(
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
    val robEntries: Int = 64,
    val lsidWidth: Int = 32,
    val nativeBidWidth: Int = 8,
    val ridGenerationWidth: Int = 8,
    val brobGenerationWidth: Int = 8,
    val memberIndexWidth: Int = 8,
    val residentGenerationWidth: Int = 8,
    val leaseGenerationWidth: Int = 8,
    val memoryTransactionIdWidth: Int = 8,
    val memoryTransactionGenerationWidth: Int = 8)
    extends Bundle {
  private val ptrWidth = log2Ceil(entries)
  private val queueCountWidth = log2Ceil(queueEntries + 1)
  private val requestCount = issueWidth * 2
  private val issueCountWidth = log2Ceil(issueWidth + 1)
  private val scbIndexWidth = math.max(1, log2Ceil(scbEntries))
  private val scbResponseTxnIdWidth = scbIndexWidth + 2

  val rows = Input(Vec(entries, new STQEntryBankRow(
    robEntries, addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth,
    sizeWidth, simtLaneWidth, mapQDepth, 64, lsidWidth, nativeBidWidth,
    ridGenerationWidth, brobGenerationWidth, memberIndexWidth,
    residentGenerationWidth, leaseGenerationWidth)))
  val recoveryActive = Input(Bool())

  val robStoreCommit = Flipped(Decoupled(new STQRobCommitToken(
    robEntries, lsidWidth, peIdWidth, stidWidth, nativeBidWidth,
    ridGenerationWidth, brobGenerationWidth, memberIndexWidth,
    residentGenerationWidth)))
  val memoryClassify = Flipped(Decoupled(new STQMemoryClassifyToken(
    entries, robEntries, peIdWidth, stidWidth, nativeBidWidth,
    ridGenerationWidth, brobGenerationWidth, memberIndexWidth,
    residentGenerationWidth, leaseGenerationWidth)))

  val markCommitValid = Output(Bool())
  val markCommitIndex = Output(UInt(ptrWidth.W))
  val markCommitAccepted = Input(Bool())
  val commitFreeMaskValid = Output(Bool())
  val commitFreeMask = Output(UInt(entries.W))
  val commitFreeAcceptedMask = Input(UInt(entries.W))

  val issueEnable = Input(Bool())
  val evictEnable = Input(Bool())
  val dcacheReady = Input(Bool())
  val dcacheWriteHit = Input(Bool())
  val dcacheTagHit = Input(Bool())
  val l2RequestReady = Input(Bool())
  val l2Request = Output(new SCBL2OwnershipRequest(
    scbEntries, addrWidth, lineBytes, memoryTransactionIdWidth,
    memoryTransactionGenerationWidth))
  val rawRespValid = Input(Bool())
  val rawRespTxnId = Input(UInt(scbResponseTxnIdWidth.W))
  val rawRespTransactionValue = Input(UInt(memoryTransactionIdWidth.W))
  val rawRespTransactionGeneration =
    Input(UInt(memoryTransactionGenerationWidth.W))
  val rawRespWrite = Input(Bool())
  val rawRespUpgrade = Input(Bool())
  val rawRespReady = Output(Bool())

  val serializedRequest = Decoupled(new STQSerializedStoreRequest(
    entries, robEntries, addrWidth, dataWidth, sizeWidth, lsidWidth,
    peIdWidth, stidWidth, nativeBidWidth, ridGenerationWidth,
    brobGenerationWidth, memberIndexWidth, residentGenerationWidth,
    leaseGenerationWidth))
  val serializedResponse = Flipped(Decoupled(
    new STQSerializedStoreResponse(
      memoryTransactionIdWidth, memoryTransactionGenerationWidth)))

  val robStoreCommitAccepted = Output(Bool())
  val robStoreCommitMissing = Output(Bool())
  val robStoreCommitMultiple = Output(Bool())
  val robStoreCommitNotReady = Output(Bool())
  val robStoreCommitClassificationMissing = Output(Bool())
  val robStoreCommitClassificationFault = Output(Bool())
  val memoryClassifyAccepted = Output(Bool())
  val memoryClassifyMissing = Output(Bool())
  val memoryClassifyMultiple = Output(Bool())
  val memoryClassifyDuplicate = Output(Bool())
  val memoryClassifyConflict = Output(Bool())
  val memoryClassifyMalformed = Output(Bool())
  val memoryAttributes = Output(Vec(entries, new STQMemoryAttribute))

  val drainQueueCount = Output(UInt(queueCountWidth.W))
  val drainEnqueueAccepted = Output(Bool())
  val drainIssueCount = Output(UInt(issueCountWidth.W))
  val scbAcceptedMask = Output(UInt(requestCount.W))
  val scbValidMask = Output(UInt(scbEntries.W))
  val scbRows = Output(Vec(scbEntries,
    new SCBLineEntry(addrWidth, lineBytes)))
  val logicalCompletions = Output(Vec(issueWidth,
    new STQCommitLogicalCompletion(
      robEntries, lsidWidth, peIdWidth, stidWidth, nativeBidWidth,
      ridGenerationWidth, brobGenerationWidth, memberIndexWidth,
      residentGenerationWidth)))
  val logicalCompletionCount = Output(UInt(issueCountWidth.W))
  val serializedBusy = Output(Bool())
  val protocolError = Output(Bool())
  val empty = Output(Bool())
}

class STQSCBCommitBackend(
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
    val robEntries: Int = 64,
    val lsidWidth: Int = 32,
    val nativeBidWidth: Int = 8,
    val ridGenerationWidth: Int = 8,
    val brobGenerationWidth: Int = 8,
    val memberIndexWidth: Int = 8,
    val residentGenerationWidth: Int = 8,
    val leaseGenerationWidth: Int = 8,
    val memoryTransactionIdWidth: Int = 8,
    val memoryTransactionGenerationWidth: Int = 8)
    extends Module {
  require(entries > 1 && (entries & (entries - 1)) == 0,
    "commit backend requires a power-of-two STQ")
  require(queueEntries > 1 && (queueEntries & (queueEntries - 1)) == 0,
    "commit backend requires a power-of-two CommitQ")
  require(issueWidth > 0 && issueWidth <= queueEntries,
    "commit backend issue width must fit CommitQ")
  require(issueWidth * 2 <= scbEntries,
    "SCB must accept the worst-case split-store batch")

  private val requestCount = issueWidth * 2
  val io = IO(new STQSCBCommitBackendIO(
    entries, queueEntries, issueWidth, scbEntries, scbResponseBufferDepth,
    addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth, sizeWidth,
    simtLaneWidth, lineBytes, mapQDepth, robEntries, lsidWidth,
    nativeBidWidth, ridGenerationWidth, brobGenerationWidth,
    memberIndexWidth, residentGenerationWidth, leaseGenerationWidth,
    memoryTransactionIdWidth, memoryTransactionGenerationWidth))

  val memoryAttributes = Module(new STQMemoryAttributeOwner(
    entries, robEntries, addrWidth, dataWidth, peIdWidth, stidWidth,
    tidWidth, sizeWidth, simtLaneWidth, mapQDepth, lsidWidth,
    nativeBidWidth, ridGenerationWidth, brobGenerationWidth,
    memberIndexWidth, residentGenerationWidth, leaseGenerationWidth))
  memoryAttributes.io.classify <> io.memoryClassify
  memoryAttributes.io.rows := io.rows
  memoryAttributes.io.recoveryActive := io.recoveryActive

  val drain = Module(new STQCommitDrain(
    entries, queueEntries, issueWidth, addrWidth, dataWidth, peIdWidth,
    stidWidth, tidWidth, sizeWidth, simtLaneWidth, mapQDepth, robEntries,
    lineBytes, lsidWidth, nativeBidWidth, ridGenerationWidth,
    brobGenerationWidth, memberIndexWidth, residentGenerationWidth,
    leaseGenerationWidth))
  val ingress = Module(new STQRobCommitIngress(
    entries, robEntries, addrWidth, dataWidth, peIdWidth, stidWidth,
    tidWidth, sizeWidth, simtLaneWidth, mapQDepth, lsidWidth,
    nativeBidWidth, ridGenerationWidth, brobGenerationWidth,
    memberIndexWidth, residentGenerationWidth, leaseGenerationWidth))
  ingress.io.commit <> io.robStoreCommit
  ingress.io.rows := io.rows
  ingress.io.memoryAttributes := memoryAttributes.io.attributes
  ingress.io.recoveryActive := io.recoveryActive
  ingress.io.drainEnqueueReady := drain.io.enqueueReady

  io.markCommitValid := ingress.io.markValid
  io.markCommitIndex := ingress.io.markIndex
  drain.io.enqueueValid := io.markCommitAccepted
  drain.io.enqueueIndex := ingress.io.markIndex
  drain.io.enqueueBid := io.rows(ingress.io.markIndex).bid
  drain.io.enqueueLsId := io.rows(ingress.io.markIndex).lsIdFull
  drain.io.flushValid := false.B
  drain.io.rows := io.rows
  drain.io.memoryAttributes := memoryAttributes.io.attributes

  val serializer = Module(new STQCommittedStoreSerializer(
    entries, robEntries, issueWidth, addrWidth, dataWidth, sizeWidth,
    lsidWidth, peIdWidth, stidWidth, nativeBidWidth, ridGenerationWidth,
    brobGenerationWidth, memberIndexWidth, residentGenerationWidth,
    leaseGenerationWidth, memoryTransactionIdWidth,
    memoryTransactionGenerationWidth))
  val scb = Module(new SCBRowBank(
    entries, scbEntries, requestCount, scbResponseBufferDepth, addrWidth,
    dataWidth, sizeWidth, lineBytes, robEntries, lsidWidth,
    memoryTransactionIdWidth, memoryTransactionGenerationWidth))

  val terminalFreeValid = scb.io.commitFreeMaskValid ||
    serializer.io.freeMask.valid
  val terminalFreeMask = Mux(scb.io.commitFreeMaskValid,
    scb.io.commitFreeMask, 0.U) | Mux(serializer.io.freeMask.valid,
    serializer.io.freeMask.bits, 0.U)
  val pendingFreeValid = RegInit(false.B)
  val pendingFreeMask = Reg(UInt(entries.W))

  val scbReadyForDrain = scb.io.modelBatchReady && !io.recoveryActive &&
    !pendingFreeValid
  drain.io.issueEnable := io.issueEnable && !io.recoveryActive &&
    !pendingFreeValid
  serializer.io.batch.valid := drain.io.retainedBatchValid &&
    drain.io.retainedBatchSerialized && io.issueEnable &&
    !io.recoveryActive && !pendingFreeValid
  serializer.io.batch.bits.memoryClass := drain.io.retainedBatchMemoryClass
  serializer.io.batch.bits.issues := drain.io.retainedIssue
  serializer.io.batch.bits.requests := drain.io.memReqs
  serializer.io.recoveryActive := io.recoveryActive

  val serializedReady = !pendingFreeValid && Mux(
    drain.io.retainedBatchValid && drain.io.retainedBatchSerialized,
    serializer.io.batch.ready, serializer.io.canAcceptBatch)
  val downstreamReady = Wire(Vec(entries, Bool()))
  for (index <- 0 until entries) {
    val attribute = memoryAttributes.io.attributes(index)
    downstreamReady(index) := attribute.valid && MuxLookup(
      attribute.memoryClass.asUInt, false.B)(Seq(
        STQMemoryClass.NormalCacheable.asUInt -> scbReadyForDrain,
        STQMemoryClass.NormalNonCacheable.asUInt -> serializedReady,
        STQMemoryClass.DeviceMmio.asUInt -> serializedReady))
  }
  drain.io.primaryReadyMask := downstreamReady.asUInt
  drain.io.secondaryReadyMask := downstreamReady.asUInt

  for (request <- 0 until requestCount) {
    scb.io.reqs(request) := drain.io.memReqs(request)
    scb.io.reqs(request).valid := drain.io.memReqs(request).valid &&
      !drain.io.retainedBatchSerialized
  }
  scb.io.evictEnable := io.evictEnable
  scb.io.dcacheReady := io.dcacheReady
  scb.io.dcacheWriteHit := io.dcacheWriteHit
  scb.io.dcacheTagHit := io.dcacheTagHit
  scb.io.l2RequestReady := io.l2RequestReady
  io.l2Request := scb.io.l2Request
  scb.io.rawRespValid := io.rawRespValid
  scb.io.rawRespTxnId := io.rawRespTxnId
  scb.io.rawRespTransactionValue := io.rawRespTransactionValue
  scb.io.rawRespTransactionGeneration := io.rawRespTransactionGeneration
  scb.io.rawRespWrite := io.rawRespWrite
  scb.io.rawRespUpgrade := io.rawRespUpgrade
  io.rawRespReady := scb.io.rawRespReady

  io.serializedRequest <> serializer.io.request
  serializer.io.response <> io.serializedResponse

  io.commitFreeMaskValid := pendingFreeValid || terminalFreeValid
  io.commitFreeMask := Mux(pendingFreeValid, pendingFreeMask,
    terminalFreeMask)
  val offeredFreeAccepted = io.commitFreeMaskValid &&
    (io.commitFreeAcceptedMask & io.commitFreeMask) === io.commitFreeMask

  when(pendingFreeValid) {
    when(offeredFreeAccepted) {
      pendingFreeValid := false.B
    }
  }.elsewhen(terminalFreeValid && !offeredFreeAccepted) {
    pendingFreeValid := true.B
    pendingFreeMask := terminalFreeMask
  }

  io.robStoreCommitAccepted := ingress.io.accepted
  io.robStoreCommitMissing := ingress.io.missing
  io.robStoreCommitMultiple := ingress.io.multiple
  io.robStoreCommitNotReady := ingress.io.notReady
  io.robStoreCommitClassificationMissing := ingress.io.classificationMissing
  io.robStoreCommitClassificationFault := ingress.io.classificationFault
  io.memoryClassifyAccepted := memoryAttributes.io.accepted
  io.memoryClassifyMissing := memoryAttributes.io.missing
  io.memoryClassifyMultiple := memoryAttributes.io.multiple
  io.memoryClassifyDuplicate := memoryAttributes.io.duplicate
  io.memoryClassifyConflict := memoryAttributes.io.conflict
  io.memoryClassifyMalformed := memoryAttributes.io.malformed
  io.memoryAttributes := memoryAttributes.io.attributes

  io.drainQueueCount := drain.io.queueCount
  io.drainEnqueueAccepted := drain.io.enqueueAccepted
  io.drainIssueCount := drain.io.issueCount
  io.scbAcceptedMask := scb.io.acceptedMask
  io.scbValidMask := scb.io.validMask
  io.scbRows := scb.io.entries
  io.logicalCompletions := drain.io.logicalCompletions
  when(serializer.io.logicalCompletion.valid) {
    io.logicalCompletions(0) := serializer.io.logicalCompletion.bits
  }
  io.logicalCompletionCount := Mux(serializer.io.logicalCompletion.valid,
    1.U, drain.io.logicalCompletionCount)
  io.serializedBusy := serializer.io.busy
  io.protocolError := drain.io.orderError || drain.io.queuedIdentityError ||
    drain.io.retainedIdentityError || serializer.io.batchMalformed ||
    serializer.io.staleResponse || serializer.io.terminalError ||
    scb.io.stateError || scb.io.respDecodeError || scb.io.protocolError
  io.empty := drain.io.empty && !serializer.io.busy && !pendingFreeValid &&
    scb.io.quiescent

  when(io.robStoreCommit.fire) {
    assert(io.markCommitAccepted && drain.io.enqueueAccepted,
      "ROB store commit must atomically mark the canonical STQ and enqueue CommitQ")
  }
  when(io.markCommitAccepted) {
    assert(drain.io.enqueueAccepted,
      "canonical STQ WAIT-to-COMMIT must have the matching CommitQ token")
  }
  when(drain.io.retainedBatchAccepted && drain.io.retainedBatchSerialized) {
    assert(serializer.io.batch.fire,
      "serialized committed-store batch transfer must be atomic")
  }
  assert(!(scb.io.commitFreeMaskValid && serializer.io.freeMask.valid &&
    (scb.io.commitFreeMask & serializer.io.freeMask.bits).orR),
    "cacheable and serialized transports cannot free the same STQ row")
  when(io.commitFreeAcceptedMask.orR) {
    assert(io.commitFreeMaskValid &&
      (io.commitFreeAcceptedMask & io.commitFreeMask) ===
        io.commitFreeMask,
      "the canonical STQ cannot partially accept a terminal free mask")
  }
}
