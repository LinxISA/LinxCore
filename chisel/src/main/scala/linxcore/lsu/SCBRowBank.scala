package linxcore.lsu

import chisel3._
import chisel3.util.{Cat, log2Ceil, OHToUInt, PopCount, PriorityEncoderOH}

class SCBRowBankIO(
    val stqEntries: Int,
    val scbEntries: Int,
    val requestCount: Int,
    val responseBufferDepth: Int,
    val addrWidth: Int = 64,
    val dataWidth: Int = 64,
    val sizeWidth: Int = 4,
    val lineBytes: Int = 64,
    val robEntries: Int = 0)
    extends Bundle {
  private val identityEntries = if (robEntries > 0) robEntries else stqEntries
  private val scbCountWidth = log2Ceil(scbEntries + 1)
  private val freeCountWidth = log2Ceil(requestCount + 1)
  private val entryIndexWidth = math.max(1, log2Ceil(scbEntries))
  private val responseTxnIdWidth = entryIndexWidth + 2
  private val responseBufferCountWidth = log2Ceil(responseBufferDepth + 1)
  private val responseRetryCountWidth = log2Ceil(scbEntries + 1)

  val reqs = Input(Vec(requestCount,
    new STQCommitDrainRequest(stqEntries, addrWidth, dataWidth, sizeWidth, identityEntries)))
  val evictEnable = Input(Bool())
  val dcacheReady = Input(Bool())
  val dcacheWriteHit = Input(Bool())
  val dcacheTagHit = Input(Bool())
  val l2RequestReady = Input(Bool())
  val rawRespValid = Input(Bool())
  val rawRespTxnId = Input(UInt(responseTxnIdWidth.W))
  val rawRespWrite = Input(Bool())
  val rawRespUpgrade = Input(Bool())
  val rawRespReady = Output(Bool())

  val modelBatchReady = Output(Bool())
  val modelFull = Output(Bool())
  val acceptedMask = Output(UInt(requestCount.W))
  val stalledMask = Output(UInt(requestCount.W))
  val structuralBlockedMask = Output(UInt(requestCount.W))

  val commitFreeMaskValid = Output(Bool())
  val commitFreeMask = Output(UInt(stqEntries.W))
  val commitFreeCount = Output(UInt(freeCountWidth.W))

  val wakeups = Output(Vec(requestCount, new SCBCommitWakeup(addrWidth, lineBytes)))
  val entries = Output(Vec(scbEntries, new SCBLineEntry(addrWidth, lineBytes)))
  val nextEntries = Output(Vec(scbEntries, new SCBLineEntry(addrWidth, lineBytes)))
  val validMask = Output(UInt(scbEntries.W))
  val fullLineMask = Output(UInt(scbEntries.W))
  val entryCount = Output(UInt(scbCountWidth.W))
  val freeCount = Output(UInt(scbCountWidth.W))
  val ingressFull = Output(Bool())

  val validStateMask = Output(UInt(scbEntries.W))
  val fullCandidateMask = Output(UInt(scbEntries.W))
  val notFullCandidateMask = Output(UInt(scbEntries.W))
  val lookupMask = Output(UInt(scbEntries.W))
  val normalLookupMask = Output(UInt(scbEntries.W))
  val responseRetryCandidateMask = Output(UInt(scbEntries.W))
  val responseRetryMask = Output(UInt(scbEntries.W))
  val responseRetryHeadValid = Output(Bool())
  val responseRetryHeadEntryIndex = Output(UInt(entryIndexWidth.W))
  val responseRetryHeadBlocked = Output(Bool())
  val responseRetryHeadConsumed = Output(Bool())
  val responseRetryPushAccepted = Output(Bool())
  val responseRetryFull = Output(Bool())
  val responseRetryEmpty = Output(Bool())
  val responseRetryCount = Output(UInt(responseRetryCountWidth.W))
  val lookupRetry = Output(Bool())
  val lookupNormal = Output(Bool())
  val lookupFull = Output(Bool())
  val lookupNotFull = Output(Bool())
  val noLookupCandidate = Output(Bool())
  val lookupRequest = Output(new SCBEgressLookupRequest(scbEntries, addrWidth, lineBytes))

  val lookupReady = Output(Bool())
  val lookupFire = Output(Bool())
  val lookupStall = Output(Bool())
  val dcacheUpdate = Output(new SCBDCacheUpdate(scbEntries, addrWidth, lineBytes))
  val l2Request = Output(new SCBL2OwnershipRequest(scbEntries, addrWidth, lineBytes))

  val stateAcceptedToLookupMask = Output(UInt(scbEntries.W))
  val stateMissMask = Output(UInt(scbEntries.W))
  val stateRespToLookupMask = Output(UInt(scbEntries.W))
  val stateClearedMask = Output(UInt(scbEntries.W))
  val stateIllegalMask = Output(UInt(scbEntries.W))
  val stateError = Output(Bool())

  val respDecodedValid = Output(Bool())
  val respDecodedEntryIndex = Output(UInt(entryIndexWidth.W))
  val respDecodedMask = Output(UInt(scbEntries.W))
  val respTypeIllegal = Output(Bool())
  val respTagIllegal = Output(Bool())
  val respIndexIllegal = Output(Bool())
  val respStateIllegalMask = Output(UInt(scbEntries.W))
  val respDecodeError = Output(Bool())
  val respBufferAccepted = Output(Bool())
  val respBufferHeadValid = Output(Bool())
  val respBufferHeadConsumed = Output(Bool())
  val respBufferHeadTxnId = Output(UInt(responseTxnIdWidth.W))
  val respBufferFull = Output(Bool())
  val respBufferEmpty = Output(Bool())
  val respBufferCount = Output(UInt(responseBufferCountWidth.W))
}

class SCBRowBank(
    val stqEntries: Int = 16,
    val scbEntries: Int = 16,
    val requestCount: Int = 4,
    val responseBufferDepth: Int = 4,
    val addrWidth: Int = 64,
    val dataWidth: Int = 64,
    val sizeWidth: Int = 4,
    val lineBytes: Int = 64,
    val robEntries: Int = 0)
    extends Module {
  private val identityEntries = if (robEntries > 0) robEntries else stqEntries
  require(stqEntries > 1, "STQ entries must be greater than one")
  require(identityEntries > 1 && (identityEntries & (identityEntries - 1)) == 0,
    "ROB entries must be a power of two greater than one")
  require(scbEntries > 0, "SCB row bank entries must be nonzero")
  require(requestCount > 0, "SCB row bank request count must be nonzero")
  require(responseBufferDepth > 0, "SCB response buffer depth must be nonzero")
  require(requestCount <= scbEntries, "SCB row bank model batch width cannot exceed SCB depth")
  require(addrWidth >= 7, "SCB row bank needs at least 7 address bits for 64-byte lines")
  require(dataWidth == 64, "SCB row bank currently models scalar 64-bit store fragments")
  require(sizeWidth >= 4, "SCB row bank scalar store sizes require at least 4 size bits")
  require(lineBytes == 64, "SCB row bank currently models 64-byte scalar cachelines")

  private val scbCountWidth = log2Ceil(scbEntries + 1)
  private val freeCountWidth = log2Ceil(requestCount + 1)

  val io = IO(new SCBRowBankIO(
    stqEntries,
    scbEntries,
    requestCount,
    responseBufferDepth,
    addrWidth,
    dataWidth,
    sizeWidth,
    lineBytes,
    identityEntries))

  private def zeroEntry: SCBLineEntry = {
    val entry = Wire(new SCBLineEntry(addrWidth, lineBytes))
    entry := 0.U.asTypeOf(entry)
    entry
  }

  private def zeroWakeup: SCBCommitWakeup = {
    val wakeup = Wire(new SCBCommitWakeup(addrWidth, lineBytes))
    wakeup := 0.U.asTypeOf(wakeup)
    wakeup
  }

  private def requestByteMask(addr: UInt, size: UInt): UInt = {
    val mask = Wire(Vec(lineBytes, Bool()))
    val offset = Wire(UInt(7.W))
    val sizeWide = Wire(UInt(7.W))
    offset := addr(5, 0)
    sizeWide := size
    val end = offset +& sizeWide
    for (byte <- 0 until lineBytes) {
      val byteIdx = byte.U(7.W)
      mask(byte) := (byteIdx >= offset) && (byteIdx < end)
    }
    mask.asUInt
  }

  private def mergeData(oldData: UInt, req: STQCommitDrainRequest, byteMask: UInt): UInt = {
    val mergedBytes = Wire(Vec(lineBytes, UInt(8.W)))
    val offset = Wire(UInt(7.W))
    offset := req.addr(5, 0)
    for (byte <- 0 until lineBytes) {
      val byteIdx = byte.U(7.W)
      val reqByteOffset = byteIdx - offset
      val reqByte = (req.data >> (reqByteOffset << 3))(7, 0)
      val oldByte = oldData((byte * 8) + 7, byte * 8)
      mergedBytes(byte) := Mux(byteMask(byte), reqByte, oldByte)
    }
    Cat(mergedBytes.reverse)
  }

  val entries = RegInit(VecInit(Seq.fill(scbEntries)(zeroEntry)))

  val currentValidVec = VecInit(entries.map(_.valid))
  val currentFullVec = VecInit(entries.map(entry => entry.valid && entry.full))
  val currentEntryCount = PopCount(currentValidVec)
  val currentFreeCount = scbEntries.U(scbCountWidth.W) - currentEntryCount
  val modelBatchReady = currentFreeCount >= requestCount.U(scbCountWidth.W)

  val ingressStages = Seq.fill(requestCount + 1)(Wire(Vec(scbEntries, new SCBLineEntry(addrWidth, lineBytes))))
  ingressStages.head := entries

  val acceptedVec = Wire(Vec(requestCount, Bool()))
  val blockedVec = Wire(Vec(requestCount, Bool()))
  val wakeups = Wire(Vec(requestCount, new SCBCommitWakeup(addrWidth, lineBytes)))

  for (lane <- 0 until requestCount) {
    val req = io.reqs(lane)
    val line = SCBCommitIngress.lineAddr(req.addr, addrWidth)
    val hitVec = VecInit((0 until scbEntries).map { idx =>
      ingressStages(lane)(idx).valid &&
      (ingressStages(lane)(idx).state === SCBEntryState.Valid) &&
      (ingressStages(lane)(idx).lineAddr === line)
    })
    val freeVec = VecInit((0 until scbEntries).map(idx => !ingressStages(lane)(idx).valid))
    val hit = hitVec.asUInt.orR
    val free = freeVec.asUInt.orR
    val accept = req.valid && modelBatchReady && (hit || free)
    val hitIndex = OHToUInt(PriorityEncoderOH(hitVec.asUInt))
    val freeIndex = OHToUInt(PriorityEncoderOH(freeVec.asUInt))
    val targetIndex = Mux(hit, hitIndex, freeIndex)
    val byteMask = requestByteMask(req.addr, req.size)
    val oldEntry = ingressStages(lane)(targetIndex)
    val mergedMask = oldEntry.byteMask | byteMask
    val mergedData = mergeData(oldEntry.data, req, byteMask)
    val nextEntry = Wire(new SCBLineEntry(addrWidth, lineBytes))

    ingressStages(lane + 1) := ingressStages(lane)
    nextEntry := oldEntry
    nextEntry.valid := true.B
    nextEntry.lineAddr := line
    nextEntry.byteMask := mergedMask
    nextEntry.data := mergedData
    nextEntry.full := mergedMask.andR
    nextEntry.state := SCBEntryState.Valid

    acceptedVec(lane) := accept
    blockedVec(lane) := req.valid && modelBatchReady && !accept
    wakeups(lane) := zeroWakeup
    wakeups(lane).valid := accept
    wakeups(lane).lineAddr := line
    wakeups(lane).byteMask := mergedMask

    when(accept) {
      ingressStages(lane + 1)(targetIndex) := nextEntry
    }
  }

  val ingressEntries = Wire(Vec(scbEntries, new SCBLineEntry(addrWidth, lineBytes)))
  ingressEntries := ingressStages.last

  val egress = Module(new SCBEgressSelect(scbEntries, addrWidth, lineBytes))
  egress.io.evictEnable := io.evictEnable
  egress.io.entries := ingressEntries

  val responseRetryQueue = Module(new SCBResponseRetryQueue(scbEntries, scbEntries))

  val retrySelect = Module(new SCBResponseRetrySelect(scbEntries, addrWidth, lineBytes))
  retrySelect.io.entries := ingressEntries
  retrySelect.io.normalLookupRequest := egress.io.lookupRequest
  retrySelect.io.normalLookupMask := egress.io.lookupMask
  retrySelect.io.retryHeadValid := responseRetryQueue.io.headValid
  retrySelect.io.retryHeadEntryIndex := responseRetryQueue.io.headEntryIndex

  val lookup = Module(new SCBLookupControl(scbEntries, addrWidth, lineBytes))
  lookup.io.lookupRequest := retrySelect.io.lookupRequest
  lookup.io.dcacheReady := io.dcacheReady
  lookup.io.dcacheWriteHit := io.dcacheWriteHit
  lookup.io.dcacheTagHit := io.dcacheTagHit
  lookup.io.l2RequestReady := io.l2RequestReady

  val responseBuffer = Module(new SCBResponseBuffer(scbEntries, responseBufferDepth))
  responseBuffer.io.rawValid := io.rawRespValid
  responseBuffer.io.rawTxnId := io.rawRespTxnId
  responseBuffer.io.rawWriteResp := io.rawRespWrite
  responseBuffer.io.rawUpgradeResp := io.rawRespUpgrade

  val responseDecode = Module(new SCBResponseDecode(scbEntries, addrWidth, lineBytes))
  responseDecode.io.entries := ingressEntries
  responseDecode.io.rawValid := responseBuffer.io.headValid
  responseDecode.io.rawTxnId := responseBuffer.io.headTxnId
  responseDecode.io.rawWriteResp := responseBuffer.io.headWriteResp
  responseDecode.io.rawUpgradeResp := responseBuffer.io.headUpgradeResp
  responseBuffer.io.headReady := responseDecode.io.memRespValid && responseRetryQueue.io.pushReady

  responseRetryQueue.io.pushValid := responseDecode.io.memRespValid
  responseRetryQueue.io.pushEntryIndex := responseDecode.io.memRespEntryIndex
  responseRetryQueue.io.popReady := lookup.io.lookupFire && retrySelect.io.lookupRetry

  val state = Module(new SCBStateUpdate(scbEntries, addrWidth, lineBytes))
  state.io.entries := ingressEntries
  state.io.acceptedMask := lookup.io.acceptedMask
  state.io.missMask := lookup.io.missMask
  state.io.freeMask := lookup.io.freeMask
  state.io.memRespValid := responseRetryQueue.io.pushAccepted
  state.io.memRespEntryIndex := responseDecode.io.memRespEntryIndex

  entries := state.io.nextEntries

  val commitFreeVec = Wire(Vec(stqEntries, Bool()))
  for (idx <- 0 until stqEntries) {
    commitFreeVec(idx) :=
      (0 until requestCount)
        .map(lane => acceptedVec(lane) && io.reqs(lane).last && (io.reqs(lane).stqIndex === idx.U))
        .reduce(_ || _)
  }
  val validReqMask = VecInit(io.reqs.map(_.valid)).asUInt

  io.modelBatchReady := modelBatchReady
  io.modelFull := !modelBatchReady
  io.rawRespReady := responseBuffer.io.rawReady
  io.acceptedMask := acceptedVec.asUInt
  io.structuralBlockedMask := blockedVec.asUInt
  io.stalledMask := validReqMask & ~acceptedVec.asUInt
  io.commitFreeMask := commitFreeVec.asUInt
  io.commitFreeMaskValid := commitFreeVec.asUInt.orR
  io.commitFreeCount := PopCount(commitFreeVec)(freeCountWidth - 1, 0)
  io.wakeups := wakeups
  io.entries := entries
  io.nextEntries := state.io.nextEntries
  io.validMask := currentValidVec.asUInt
  io.fullLineMask := currentFullVec.asUInt
  io.entryCount := currentEntryCount
  io.freeCount := currentFreeCount
  io.ingressFull := currentEntryCount === scbEntries.U

  io.validStateMask := egress.io.validStateMask
  io.fullCandidateMask := egress.io.fullCandidateMask
  io.notFullCandidateMask := egress.io.notFullCandidateMask
  io.lookupMask := retrySelect.io.lookupMask
  io.normalLookupMask := egress.io.lookupMask
  io.responseRetryCandidateMask := retrySelect.io.retryCandidateMask
  io.responseRetryMask := retrySelect.io.retryLookupMask
  io.responseRetryHeadValid := responseRetryQueue.io.headValid
  io.responseRetryHeadEntryIndex := responseRetryQueue.io.headEntryIndex
  io.responseRetryHeadBlocked := retrySelect.io.retryHeadBlocked
  io.responseRetryHeadConsumed := responseRetryQueue.io.headConsumed
  io.responseRetryPushAccepted := responseRetryQueue.io.pushAccepted
  io.responseRetryFull := responseRetryQueue.io.full
  io.responseRetryEmpty := responseRetryQueue.io.empty
  io.responseRetryCount := responseRetryQueue.io.count
  io.lookupRetry := retrySelect.io.lookupRetry
  io.lookupNormal := retrySelect.io.lookupNormal
  io.lookupFull := retrySelect.io.lookupFull
  io.lookupNotFull := retrySelect.io.lookupNotFull
  io.noLookupCandidate := retrySelect.io.noCandidate
  io.lookupRequest := retrySelect.io.lookupRequest

  io.lookupReady := lookup.io.lookupReady
  io.lookupFire := lookup.io.lookupFire
  io.lookupStall := lookup.io.lookupStall
  io.dcacheUpdate := lookup.io.dcacheUpdate
  io.l2Request := lookup.io.l2Request

  io.stateAcceptedToLookupMask := state.io.acceptedToLookupMask
  io.stateMissMask := state.io.missStateMask
  io.stateRespToLookupMask := state.io.respToLookupMask
  io.stateClearedMask := state.io.clearedMask
  io.stateIllegalMask := state.io.illegalMask | responseDecode.io.stateIllegalMask | Mux(
    retrySelect.io.retryHeadBlocked,
    responseRetryQueue.io.headMask,
    0.U(scbEntries.W))
  io.stateError := state.io.stateError || responseDecode.io.illegal || retrySelect.io.retryHeadBlocked

  io.respDecodedValid := responseRetryQueue.io.pushAccepted
  io.respDecodedEntryIndex := responseDecode.io.memRespEntryIndex
  io.respDecodedMask := responseDecode.io.decodedMask
  io.respTypeIllegal := responseDecode.io.typeIllegal
  io.respTagIllegal := responseDecode.io.tagIllegal
  io.respIndexIllegal := responseDecode.io.indexIllegal
  io.respStateIllegalMask := responseDecode.io.stateIllegalMask
  io.respDecodeError := responseDecode.io.illegal
  io.respBufferAccepted := responseBuffer.io.rawAccepted
  io.respBufferHeadValid := responseBuffer.io.headValid
  io.respBufferHeadConsumed := responseBuffer.io.headConsumed
  io.respBufferHeadTxnId := responseBuffer.io.headTxnId
  io.respBufferFull := responseBuffer.io.full
  io.respBufferEmpty := responseBuffer.io.empty
  io.respBufferCount := responseBuffer.io.count
}
