package linxcore.lsu

import chisel3._
import chisel3.util.{Cat, Fill, log2Ceil, PopCount}

import linxcore.rob.ROBID

class STQCommitDrainRequest(
    val entries: Int,
    val addrWidth: Int = 64,
    val dataWidth: Int = 64,
    val sizeWidth: Int = 4,
    val robEntries: Int = 0,
    val lsidWidth: Int = 32,
    val stidWidth: Int = 8)
    extends Bundle {
  private val identityEntries = if (robEntries > 0) robEntries else entries
  val valid = Bool()
  val ownsStqRow = Bool()
  val stqIndex = UInt(log2Ceil(entries).W)
  val split = Bool()
  val segment = UInt(1.W)
  val last = Bool()
  val addr = UInt(addrWidth.W)
  val data = UInt(dataWidth.W)
  val size = UInt(sizeWidth.W)
  val stid = UInt(stidWidth.W)
  val bid = new ROBID(identityEntries)
  val gid = new ROBID(identityEntries)
  val rid = new ROBID(identityEntries)
  val lsId = UInt(lsidWidth.W)
}

/** One accepted logical store drain group, independent of its one/two STQ
  * beats and one/two cache-line fragments per beat. This is not a lower-level
  * WriteResp or architectural memory-completion acknowledgement.
  */
class STQCommitLogicalCompletion(
    val entries: Int,
    val lsidWidth: Int = 32,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val nativeBidWidth: Int = 8,
    val ridGenerationWidth: Int = 8,
    val brobGenerationWidth: Int = 8,
    val memberIndexWidth: Int = 8,
    val residentGenerationWidth: Int = 8)
    extends Bundle {
  val valid = Bool()
  val stid = UInt(stidWidth.W)
  val logicalFirstLsid = UInt(lsidWidth.W)
  val logicalFirstStoreId = UInt(lsidWidth.W)
  val logicalRequestCount = UInt(2.W)
  val exactOwner = new STQExactOwner(
    peIdWidth, stidWidth, nativeBidWidth, log2Ceil(entries),
    ridGenerationWidth, brobGenerationWidth, memberIndexWidth,
    residentGenerationWidth)
}

class STQCommitDrainIO(
    val entries: Int,
    val queueEntries: Int,
    val issueWidth: Int,
    val addrWidth: Int = 64,
    val dataWidth: Int = 64,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val sizeWidth: Int = 4,
    val simtLaneWidth: Int = 8,
    val mapQDepth: Int = 32,
    val robEntries: Int = 0,
    val lsidWidth: Int = 32)
    extends Bundle {
  private val identityEntries = if (robEntries > 0) robEntries else entries
  private val ptrWidth = log2Ceil(entries)
  private val queueCountWidth = log2Ceil(queueEntries + 1)
  private val freeCountWidth = log2Ceil(issueWidth + 1)

  val enqueueValid = Input(Bool())
  val enqueueIndex = Input(UInt(ptrWidth.W))
  val enqueueBid = Input(new ROBID(identityEntries))
  val enqueueLsId = Input(UInt(lsidWidth.W))
  val flushValid = Input(Bool())
  val enqueueReady = Output(Bool())
  val enqueueAccepted = Output(Bool())
  val enqueueDuplicate = Output(Bool())
  val enqueueMalformed = Output(Bool())
  val enqueueInsertPosition = Output(UInt(queueCountWidth.W))

  val issueEnable = Input(Bool())
  val primaryReadyMask = Input(UInt(entries.W))
  val secondaryReadyMask = Input(UInt(entries.W))
  val rows = Input(Vec(entries, new STQEntryBankRow(identityEntries, addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth, sizeWidth, simtLaneWidth, mapQDepth, 64, lsidWidth)))

  val commitEligibleMask = Output(UInt(entries.W))
  val splitMask = Output(UInt(entries.W))
  val readyMask = Output(UInt(entries.W))

  val issue = Output(Vec(issueWidth, new STQCommitIssue(identityEntries, entries, lsidWidth)))
  val issueValidMask = Output(UInt(issueWidth.W))
  val issueCount = Output(UInt(freeCountWidth.W))
  val retainedBatchValid = Output(Bool())
  val retainedBatchAccepted = Output(Bool())
  val retainedIdentityError = Output(Bool())
  val memReqs = Output(Vec(issueWidth * 2, new STQCommitDrainRequest(entries, addrWidth, dataWidth, sizeWidth, identityEntries, lsidWidth)))
  val logicalCompletions = Output(Vec(issueWidth,
    new STQCommitLogicalCompletion(identityEntries, lsidWidth, peIdWidth, stidWidth)))
  val logicalCompletionCount = Output(UInt(freeCountWidth.W))

  val commitFreeMaskValid = Output(Bool())
  val commitFreeMask = Output(UInt(entries.W))
  val commitFreeCount = Output(UInt(freeCountWidth.W))

  val queued = Output(Vec(queueEntries, new STQCommitQueueEntry(identityEntries, entries, lsidWidth)))
  val queuedValidMask = Output(UInt(queueEntries.W))
  val queueCount = Output(UInt(queueCountWidth.W))
  val empty = Output(Bool())
  val full = Output(Bool())
  val orderError = Output(Bool())
  val queuedIdentityError = Output(Bool())
}

object STQCommitDrain {
  def crossesScalarCacheline(addr: UInt, size: UInt, lineBytes: Int = 64): Bool = {
    require(lineBytes > 1 && (lineBytes & (lineBytes - 1)) == 0, "lineBytes must be a power of two greater than one")
    val offsetBits = log2Ceil(lineBytes)
    val compareWidth = math.max(offsetBits + 1, size.getWidth)
    val offset = Wire(UInt(compareWidth.W))
    val sizeWide = Wire(UInt(compareWidth.W))
    offset := addr(offsetBits - 1, 0)
    sizeWide := size
    (offset +& sizeWide) > lineBytes.U
  }
}

class STQCommitDrain(
    val entries: Int = 16,
    val queueEntries: Int = 16,
    val issueWidth: Int = 2,
    val addrWidth: Int = 64,
    val dataWidth: Int = 64,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val sizeWidth: Int = 4,
    val simtLaneWidth: Int = 8,
    val mapQDepth: Int = 32,
    val robEntries: Int = 0,
    val lineBytes: Int = 64,
    val lsidWidth: Int = 32)
    extends Module {
  private val identityEntries = if (robEntries > 0) robEntries else entries
  require(entries > 1, "STQ entries must be greater than one")
  require(queueEntries > 1, "STQ commit queue entries must be greater than one")
  require(issueWidth > 0, "STQ commit drain issue width must be nonzero")
  require(issueWidth <= queueEntries, "STQ commit drain issue width cannot exceed queue depth")
  require((entries & (entries - 1)) == 0, "STQ entries must be a power of two")
  require((queueEntries & (queueEntries - 1)) == 0, "STQ commit queue entries must be a power of two")
  require(identityEntries > 1 && (identityEntries & (identityEntries - 1)) == 0, "ROB entries must be a power of two")
  require(addrWidth >= 7, "STQ commit drain needs at least 7 address bits for scalar cacheline split detection")
  require(sizeWidth >= 4, "STQ commit drain scalar store sizes require at least 4 size bits")
  require(lineBytes > 1 && (lineBytes & (lineBytes - 1)) == 0, "lineBytes must be a power of two greater than one")
  require(addrWidth >= log2Ceil(lineBytes), "address width must cover the cache-line offset")
  require(lsidWidth >= 2, "LSID width must support modular serial ordering")

  private val freeCountWidth = log2Ceil(issueWidth + 1)

  val io = IO(new STQCommitDrainIO(entries, queueEntries, issueWidth, addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth, sizeWidth, simtLaneWidth, mapQDepth, identityEntries, lsidWidth))

  private def zeroReq: STQCommitDrainRequest = {
    val req = Wire(new STQCommitDrainRequest(entries, addrWidth, dataWidth, sizeWidth, identityEntries, lsidWidth))
    req := 0.U.asTypeOf(req)
    req
  }

  private def zeroLogicalCompletion: STQCommitLogicalCompletion = {
    val completion = Wire(new STQCommitLogicalCompletion(
      identityEntries, lsidWidth, peIdWidth, stidWidth))
    completion := 0.U.asTypeOf(completion)
    completion
  }

  private def sameLogical(left: STQCommitIssue, right: STQCommitIssue): Bool =
    left.valid && right.valid && left.stid === right.stid &&
      left.exactOwner.asUInt === right.exactOwner.asUInt &&
      left.logicalStoreValid && right.logicalStoreValid &&
      left.logicalFirstLsid === right.logicalFirstLsid &&
      left.logicalFirstStoreId === right.logicalFirstStoreId &&
      left.logicalRequestCount === right.logicalRequestCount

  val queue = Module(new STQCommitQueue(
    robEntries = identityEntries,
    stqEntries = entries,
    queueEntries = queueEntries,
    issueWidth = issueWidth,
    lsidWidth = lsidWidth,
    peIdWidth = peIdWidth,
    stidWidth = stidWidth))
  val enqueueRow = io.rows(io.enqueueIndex)
  val enqueueRowExact = enqueueRow.valid &&
    (enqueueRow.status === STQEntryStatus.Wait ||
      enqueueRow.status === STQEntryStatus.Commit) &&
    enqueueRow.storeType === STQStoreType.All &&
    enqueueRow.addrReady && enqueueRow.dataReady &&
    enqueueRow.bid.valid && io.enqueueBid.valid &&
    ROBID.equal(enqueueRow.bid, io.enqueueBid) &&
    enqueueRow.lsIdFull === io.enqueueLsId &&
    enqueueRow.storeIdFullValid && enqueueRow.exactOwner.valid &&
    enqueueRow.exactOwner.nativeBidValid &&
    enqueueRow.exactOwner.stid === enqueueRow.stid &&
    enqueueRow.logicalStoreValid &&
    (enqueueRow.logicalRequestCount === 1.U ||
      enqueueRow.logicalRequestCount === 2.U) &&
    enqueueRow.logicalBeat < enqueueRow.logicalRequestCount &&
    enqueueRow.lsIdFull ===
      enqueueRow.logicalFirstLsid + enqueueRow.logicalBeat &&
    enqueueRow.storeIdFull ===
      enqueueRow.logicalFirstStoreId + enqueueRow.logicalBeat
  queue.io.enqueueValid := io.enqueueValid && enqueueRowExact
  queue.io.enqueueIndex := io.enqueueIndex
  queue.io.enqueueLeaseGeneration := enqueueRow.leaseGeneration
  queue.io.enqueueStid := enqueueRow.stid
  queue.io.enqueueBid := io.enqueueBid
  queue.io.enqueueLsId := io.enqueueLsId
  queue.io.enqueueStoreIdValid := enqueueRow.storeIdFullValid
  queue.io.enqueueStoreId := enqueueRow.storeIdFull
  queue.io.enqueueLogicalStoreValid := enqueueRow.logicalStoreValid
  queue.io.enqueueLogicalFirstLsid := enqueueRow.logicalFirstLsid
  queue.io.enqueueLogicalFirstStoreId := enqueueRow.logicalFirstStoreId
  queue.io.enqueueLogicalRequestCount := enqueueRow.logicalRequestCount
  queue.io.enqueueLogicalBeat := enqueueRow.logicalBeat
  queue.io.enqueueExactOwner := enqueueRow.exactOwner
  queue.io.flushValid := io.flushValid

  val retainedBatchValid = RegInit(false.B)
  val retainedIssues = Reg(Vec(issueWidth, new STQCommitIssue(
    identityEntries, entries, lsidWidth, peIdWidth, stidWidth)))
  val retainedReqs = Reg(Vec(issueWidth * 2, new STQCommitDrainRequest(
    entries, addrWidth, dataWidth, sizeWidth, identityEntries, lsidWidth)))
  queue.io.issueEnable := io.issueEnable && !retainedBatchValid

  val commitEligibleVec = Wire(Vec(entries, Bool()))
  val splitVec = Wire(Vec(entries, Bool()))
  val readyVec = Wire(Vec(entries, Bool()))
  for (idx <- 0 until entries) {
    val row = io.rows(idx)
    splitVec(idx) := STQCommitDrain.crossesScalarCacheline(row.addr, row.size, lineBytes)
    commitEligibleVec(idx) :=
      row.valid &&
        (row.status === STQEntryStatus.Commit) &&
        (row.storeType === STQStoreType.All) &&
        row.addrReady &&
        row.dataReady
    readyVec(idx) :=
      commitEligibleVec(idx) &&
        Mux(splitVec(idx), io.primaryReadyMask(idx) && io.secondaryReadyMask(idx), io.primaryReadyMask(idx))
  }

  val queuedTokenExact = Wire(Vec(queueEntries, Bool()))
  val queuedTokenReady = Wire(Vec(queueEntries, Bool()))
  for (slot <- 0 until queueEntries) {
    val token = queue.io.queued(slot)
    val row = io.rows(token.stqIndex)
    queuedTokenExact(slot) := !token.valid || (
      row.valid && row.status === STQEntryStatus.Commit &&
        row.storeType === STQStoreType.All && row.addrReady && row.dataReady &&
        row.leaseGeneration === token.leaseGeneration &&
        row.stid === token.stid && ROBID.equal(row.bid, token.bid) &&
        row.lsIdFull === token.lsId && row.storeIdFullValid &&
        token.storeIdValid && row.storeIdFull === token.storeId &&
        row.logicalStoreValid && token.logicalStoreValid &&
        row.logicalFirstLsid === token.logicalFirstLsid &&
        row.logicalFirstStoreId === token.logicalFirstStoreId &&
        row.logicalRequestCount === token.logicalRequestCount &&
        row.logicalBeat === token.logicalBeat &&
        row.exactOwner.asUInt === token.exactOwner.asUInt)
    queuedTokenReady(slot) := token.valid && queuedTokenExact(slot) &&
      commitEligibleVec(token.stqIndex)
  }
  queue.io.readyMask := queuedTokenReady.asUInt

  io.enqueueReady := enqueueRowExact && queue.io.enqueueReady
  io.enqueueAccepted := queue.io.enqueueAccepted
  io.enqueueDuplicate := queue.io.enqueueDuplicate
  io.enqueueMalformed := io.enqueueValid && !io.flushValid &&
    (!enqueueRowExact || queue.io.enqueueMalformed)
  io.enqueueInsertPosition := queue.io.enqueueInsertPosition
  io.commitEligibleMask := commitEligibleVec.asUInt
  io.splitMask := splitVec.asUInt
  io.readyMask := readyVec.asUInt
  io.issue := queue.io.issue
  io.issueValidMask := queue.io.issueValidMask
  io.issueCount := queue.io.issueCount
  io.queued := queue.io.queued
  io.queuedValidMask := queue.io.queuedValidMask
  io.queueCount := queue.io.queueCount
  io.empty := queue.io.empty && !retainedBatchValid
  io.full := queue.io.full
  io.orderError := queue.io.orderError
  io.queuedIdentityError := VecInit((0 until queueEntries).map { slot =>
    queue.io.queued(slot).valid && !queuedTokenExact(slot)
  }).asUInt.orR

  val launchReqs = Wire(Vec(issueWidth * 2, new STQCommitDrainRequest(
    entries, addrWidth, dataWidth, sizeWidth, identityEntries, lsidWidth)))
  for (reqIdx <- 0 until issueWidth * 2) {
    launchReqs(reqIdx) := zeroReq
  }

  for (lane <- 0 until issueWidth) {
    val issue = queue.io.issue(lane)
    val row = io.rows(issue.stqIndex)
    val offset = Wire(UInt(7.W))
    val sizeWide = Wire(UInt(7.W))
    offset := row.addr(5, 0)
    sizeWide := row.size
    val crosses = STQCommitDrain.crossesScalarCacheline(row.addr, row.size, lineBytes)
    val firstSizeWide = Mux(crosses, 64.U(7.W) - offset, sizeWide)
    val secondSizeWide = sizeWide - firstSizeWide
    val secondAddr = (Cat(row.addr(addrWidth - 1, 6), 0.U(6.W)) + 64.U)(addrWidth - 1, 0)
    val allDataBits = Fill(dataWidth, 1.B).asUInt
    val secondShiftBits = secondSizeWide << 3
    val firstShiftBits = firstSizeWide << 3
    val firstData = row.data & (allDataBits >> secondShiftBits)
    val secondData = row.data >> firstShiftBits

    val firstReq = Wire(new STQCommitDrainRequest(entries, addrWidth, dataWidth, sizeWidth, identityEntries, lsidWidth))
    firstReq := zeroReq
    firstReq.valid := issue.valid
    firstReq.ownsStqRow := issue.valid && !crosses
    firstReq.stqIndex := issue.stqIndex
    firstReq.split := crosses
    firstReq.segment := 0.U
    firstReq.last := !crosses
    firstReq.addr := row.addr
    firstReq.data := firstData
    firstReq.size := firstSizeWide(sizeWidth - 1, 0)
    firstReq.stid := row.stid
    firstReq.bid := issue.bid
    firstReq.gid := row.gid
    firstReq.rid := row.rid
    firstReq.lsId := issue.lsId

    val secondReq = Wire(new STQCommitDrainRequest(entries, addrWidth, dataWidth, sizeWidth, identityEntries, lsidWidth))
    secondReq := zeroReq
    secondReq.valid := issue.valid && crosses
    secondReq.ownsStqRow := issue.valid && crosses
    secondReq.stqIndex := issue.stqIndex
    secondReq.split := crosses
    secondReq.segment := 1.U
    secondReq.last := true.B
    secondReq.addr := secondAddr
    secondReq.data := secondData
    secondReq.size := secondSizeWide(sizeWidth - 1, 0)
    secondReq.stid := row.stid
    secondReq.bid := issue.bid
    secondReq.gid := row.gid
    secondReq.rid := row.rid
    secondReq.lsId := issue.lsId

    launchReqs(lane * 2) := firstReq
    launchReqs(lane * 2 + 1) := secondReq

    when(issue.valid) {
      assert(row.leaseGeneration === issue.leaseGeneration &&
        row.exactOwner.asUInt === issue.exactOwner.asUInt &&
        row.storeIdFullValid && issue.storeIdValid &&
        row.storeIdFull === issue.storeId &&
        row.logicalStoreValid && issue.logicalStoreValid &&
        row.logicalFirstLsid === issue.logicalFirstLsid &&
        row.logicalFirstStoreId === issue.logicalFirstStoreId &&
        row.logicalRequestCount === issue.logicalRequestCount &&
        row.logicalBeat === issue.logicalBeat,
        "a committed store may drain only through its exact live STQ lease")
    }
  }

  val retainedExact = Wire(Vec(issueWidth, Bool()))
  val retainedReady = Wire(Vec(issueWidth, Bool()))
  for (lane <- 0 until issueWidth) {
    val issue = retainedIssues(lane)
    val row = io.rows(issue.stqIndex)
    retainedExact(lane) := !issue.valid || (
      row.valid && row.status === STQEntryStatus.Commit &&
        row.storeType === STQStoreType.All && row.addrReady && row.dataReady &&
        row.leaseGeneration === issue.leaseGeneration &&
        row.stid === issue.stid && ROBID.equal(row.bid, issue.bid) &&
        row.lsIdFull === issue.lsId && row.storeIdFullValid &&
        issue.storeIdValid && row.storeIdFull === issue.storeId &&
        row.logicalStoreValid && issue.logicalStoreValid &&
        row.logicalFirstLsid === issue.logicalFirstLsid &&
        row.logicalFirstStoreId === issue.logicalFirstStoreId &&
        row.logicalRequestCount === issue.logicalRequestCount &&
        row.logicalBeat === issue.logicalBeat &&
        row.exactOwner.asUInt === issue.exactOwner.asUInt)
    val crosses = retainedReqs(lane * 2 + 1).valid
    retainedReady(lane) := !issue.valid || (
      io.primaryReadyMask(issue.stqIndex) &&
        (!crosses || io.secondaryReadyMask(issue.stqIndex)))
  }
  val retainedIdentityError = retainedBatchValid &&
    VecInit((0 until issueWidth).map { lane =>
      retainedIssues(lane).valid && !retainedExact(lane)
    }).asUInt.orR
  val retainedBatchAccepted = retainedBatchValid && io.issueEnable &&
    !io.flushValid && !retainedIdentityError && retainedReady.asUInt.andR
  val launchAccepted = queue.io.issueValidMask.orR

  when(io.flushValid) {
    retainedBatchValid := false.B
  }.elsewhen(retainedBatchAccepted) {
    retainedBatchValid := false.B
  }.elsewhen(launchAccepted) {
    retainedBatchValid := true.B
    retainedIssues := queue.io.issue
    retainedReqs := launchReqs
  }

  io.retainedBatchValid := retainedBatchValid
  io.retainedBatchAccepted := retainedBatchAccepted
  io.retainedIdentityError := retainedIdentityError
  for (reqIdx <- 0 until issueWidth * 2) {
    io.memReqs(reqIdx) := retainedReqs(reqIdx)
    io.memReqs(reqIdx).valid := retainedBatchValid &&
      io.issueEnable && !io.flushValid && !retainedIdentityError &&
      retainedReqs(reqIdx).valid
  }

  val logicalCompletionValid = Wire(Vec(issueWidth, Bool()))
  for (lane <- 0 until issueWidth) {
    val earlierSame = (0 until lane).map { earlier =>
      sameLogical(retainedIssues(lane), retainedIssues(earlier))
    }
    val isLeader = retainedIssues(lane).valid &&
      !(if (earlierSame.isEmpty) false.B else earlierSame.reduce(_ || _))
    logicalCompletionValid(lane) := retainedBatchAccepted && isLeader
    io.logicalCompletions(lane) := zeroLogicalCompletion
    io.logicalCompletions(lane).valid := logicalCompletionValid(lane)
    io.logicalCompletions(lane).stid := retainedIssues(lane).stid
    io.logicalCompletions(lane).logicalFirstLsid :=
      retainedIssues(lane).logicalFirstLsid
    io.logicalCompletions(lane).logicalFirstStoreId :=
      retainedIssues(lane).logicalFirstStoreId
    io.logicalCompletions(lane).logicalRequestCount :=
      retainedIssues(lane).logicalRequestCount
    io.logicalCompletions(lane).exactOwner := retainedIssues(lane).exactOwner

    when(retainedBatchValid && retainedIssues(lane).valid) {
      val matchingCount = PopCount((0 until issueWidth).map { other =>
        sameLogical(retainedIssues(lane), retainedIssues(other))
      })
      assert(matchingCount === retainedIssues(lane).logicalRequestCount,
        "a retained logical store must contain every exact STQ beat")
    }
  }
  io.logicalCompletionCount := PopCount(logicalCompletionValid)

  val commitFreeVec = Wire(Vec(entries, Bool()))
  for (idx <- 0 until entries) {
    commitFreeVec(idx) := retainedBatchAccepted &&
      retainedIssues.map(issue =>
        issue.valid && issue.stqIndex === idx.U).reduce(_ || _)
  }
  io.commitFreeMask := commitFreeVec.asUInt
  io.commitFreeMaskValid := commitFreeVec.asUInt.orR
  io.commitFreeCount := PopCount(commitFreeVec)(freeCountWidth - 1, 0)

}
