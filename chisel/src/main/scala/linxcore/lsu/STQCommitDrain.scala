package linxcore.lsu

import chisel3._
import chisel3.util.{Cat, Fill, log2Ceil, PopCount}

import linxcore.rob.ROBID

class STQCommitDrainRequest(
    val entries: Int,
    val addrWidth: Int = 64,
    val dataWidth: Int = 64,
    val sizeWidth: Int = 4,
    val robEntries: Int = 0)
    extends Bundle {
  private val identityEntries = if (robEntries > 0) robEntries else entries
  val valid = Bool()
  val stqIndex = UInt(log2Ceil(entries).W)
  val split = Bool()
  val segment = UInt(1.W)
  val last = Bool()
  val addr = UInt(addrWidth.W)
  val data = UInt(dataWidth.W)
  val size = UInt(sizeWidth.W)
  val bid = new ROBID(identityEntries)
  val lsId = new ROBID(identityEntries)
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
    val robEntries: Int = 0)
    extends Bundle {
  private val identityEntries = if (robEntries > 0) robEntries else entries
  private val ptrWidth = log2Ceil(entries)
  private val queueCountWidth = log2Ceil(queueEntries + 1)
  private val freeCountWidth = log2Ceil(issueWidth + 1)

  val enqueueValid = Input(Bool())
  val enqueueIndex = Input(UInt(ptrWidth.W))
  val enqueueBid = Input(new ROBID(identityEntries))
  val enqueueLsId = Input(new ROBID(identityEntries))
  val flushValid = Input(Bool())
  val enqueueReady = Output(Bool())
  val enqueueAccepted = Output(Bool())
  val enqueueDuplicate = Output(Bool())
  val enqueueInsertPosition = Output(UInt(queueCountWidth.W))

  val issueEnable = Input(Bool())
  val primaryReadyMask = Input(UInt(entries.W))
  val secondaryReadyMask = Input(UInt(entries.W))
  val rows = Input(Vec(entries, new STQEntryBankRow(identityEntries, addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth, sizeWidth, simtLaneWidth, mapQDepth)))

  val commitEligibleMask = Output(UInt(entries.W))
  val splitMask = Output(UInt(entries.W))
  val readyMask = Output(UInt(entries.W))

  val issue = Output(Vec(issueWidth, new STQCommitIssue(identityEntries, entries)))
  val issueValidMask = Output(UInt(issueWidth.W))
  val issueCount = Output(UInt(freeCountWidth.W))
  val memReqs = Output(Vec(issueWidth * 2, new STQCommitDrainRequest(entries, addrWidth, dataWidth, sizeWidth, identityEntries)))

  val commitFreeMaskValid = Output(Bool())
  val commitFreeMask = Output(UInt(entries.W))
  val commitFreeCount = Output(UInt(freeCountWidth.W))

  val queued = Output(Vec(queueEntries, new STQCommitQueueEntry(identityEntries, entries)))
  val queuedValidMask = Output(UInt(queueEntries.W))
  val queueCount = Output(UInt(queueCountWidth.W))
  val empty = Output(Bool())
  val full = Output(Bool())
  val orderError = Output(Bool())
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
    val lineBytes: Int = 64)
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

  private val freeCountWidth = log2Ceil(issueWidth + 1)

  val io = IO(new STQCommitDrainIO(entries, queueEntries, issueWidth, addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth, sizeWidth, simtLaneWidth, mapQDepth, identityEntries))

  private def zeroReq: STQCommitDrainRequest = {
    val req = Wire(new STQCommitDrainRequest(entries, addrWidth, dataWidth, sizeWidth, identityEntries))
    req := 0.U.asTypeOf(req)
    req
  }

  val queue = Module(new STQCommitQueue(robEntries = identityEntries, stqEntries = entries, queueEntries = queueEntries, issueWidth = issueWidth))
  queue.io.enqueueValid := io.enqueueValid
  queue.io.enqueueIndex := io.enqueueIndex
  queue.io.enqueueBid := io.enqueueBid
  queue.io.enqueueLsId := io.enqueueLsId
  queue.io.flushValid := io.flushValid
  queue.io.issueEnable := io.issueEnable

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

  queue.io.readyMask := readyVec.asUInt

  io.enqueueReady := queue.io.enqueueReady
  io.enqueueAccepted := queue.io.enqueueAccepted
  io.enqueueDuplicate := queue.io.enqueueDuplicate
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
  io.empty := queue.io.empty
  io.full := queue.io.full
  io.orderError := queue.io.orderError

  val commitFreeVec = Wire(Vec(entries, Bool()))
  for (idx <- 0 until entries) {
    commitFreeVec(idx) := queue.io.issue.map(issue => issue.valid && (issue.stqIndex === idx.U)).reduce(_ || _)
  }
  io.commitFreeMask := commitFreeVec.asUInt
  io.commitFreeMaskValid := commitFreeVec.asUInt.orR
  io.commitFreeCount := PopCount(commitFreeVec)(freeCountWidth - 1, 0)

  for (reqIdx <- 0 until issueWidth * 2) {
    io.memReqs(reqIdx) := zeroReq
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

    val firstReq = Wire(new STQCommitDrainRequest(entries, addrWidth, dataWidth, sizeWidth))
    firstReq := zeroReq
    firstReq.valid := issue.valid
    firstReq.stqIndex := issue.stqIndex
    firstReq.split := crosses
    firstReq.segment := 0.U
    firstReq.last := !crosses
    firstReq.addr := row.addr
    firstReq.data := firstData
    firstReq.size := firstSizeWide(sizeWidth - 1, 0)
    firstReq.bid := issue.bid
    firstReq.lsId := issue.lsId

    val secondReq = Wire(new STQCommitDrainRequest(entries, addrWidth, dataWidth, sizeWidth))
    secondReq := zeroReq
    secondReq.valid := issue.valid && crosses
    secondReq.stqIndex := issue.stqIndex
    secondReq.split := crosses
    secondReq.segment := 1.U
    secondReq.last := true.B
    secondReq.addr := secondAddr
    secondReq.data := secondData
    secondReq.size := secondSizeWide(sizeWidth - 1, 0)
    secondReq.bid := issue.bid
    secondReq.lsId := issue.lsId

    io.memReqs(lane * 2) := firstReq
    io.memReqs(lane * 2 + 1) := secondReq
  }
}
