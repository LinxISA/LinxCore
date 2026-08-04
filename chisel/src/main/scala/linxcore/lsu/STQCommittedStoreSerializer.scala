package linxcore.lsu

import chisel3._
import chisel3.util.{Decoupled, PopCount, PriorityEncoder, Valid, log2Ceil}

/** One complete committed non-cacheable/MMIO batch.
  *
  * The drain owner guarantees that the batch contains exactly one logical
  * store (one or two STQ beats, each optionally split into two fragments).
  */
class STQSerializedStoreBatch(
    val stqEntries: Int,
    val robEntries: Int,
    val issueWidth: Int,
    val addrWidth: Int = 64,
    val dataWidth: Int = 64,
    val sizeWidth: Int = 4,
    val lsidWidth: Int = 32,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val nativeBidWidth: Int = 8,
    val ridGenerationWidth: Int = 8,
    val brobGenerationWidth: Int = 8,
    val memberIndexWidth: Int = 8,
    val residentGenerationWidth: Int = 8,
    val leaseGenerationWidth: Int = 8)
    extends Bundle {
  val memoryClass = STQMemoryClass()
  val issues = Vec(issueWidth, new STQCommitIssue(
    robEntries, stqEntries, lsidWidth, peIdWidth, stidWidth, nativeBidWidth,
    ridGenerationWidth, brobGenerationWidth, memberIndexWidth,
    residentGenerationWidth, leaseGenerationWidth))
  val requests = Vec(issueWidth * 2, new STQCommitDrainRequest(
    stqEntries, addrWidth, dataWidth, sizeWidth, robEntries, lsidWidth,
    stidWidth))
}

class STQSerializedStoreRequest(
    val stqEntries: Int,
    val robEntries: Int,
    val addrWidth: Int = 64,
    val dataWidth: Int = 64,
    val sizeWidth: Int = 4,
    val lsidWidth: Int = 32,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val nativeBidWidth: Int = 8,
    val ridGenerationWidth: Int = 8,
    val brobGenerationWidth: Int = 8,
    val memberIndexWidth: Int = 8,
    val residentGenerationWidth: Int = 8,
    val leaseGenerationWidth: Int = 8,
    val transactionIdWidth: Int = 8,
    val transactionGenerationWidth: Int = 8)
    extends Bundle {
  val transactionId = UInt(transactionIdWidth.W)
  val transactionGeneration = UInt(transactionGenerationWidth.W)
  val memoryClass = STQMemoryClass()
  val issue = new STQCommitIssue(
    robEntries, stqEntries, lsidWidth, peIdWidth, stidWidth, nativeBidWidth,
    ridGenerationWidth, brobGenerationWidth, memberIndexWidth,
    residentGenerationWidth, leaseGenerationWidth)
  val fragment = new STQCommitDrainRequest(
    stqEntries, addrWidth, dataWidth, sizeWidth, robEntries, lsidWidth,
    stidWidth)
}

class STQSerializedStoreResponse(
    val transactionIdWidth: Int = 8,
    val transactionGenerationWidth: Int = 8)
    extends Bundle {
  val transactionId = UInt(transactionIdWidth.W)
  val transactionGeneration = UInt(transactionGenerationWidth.W)
  val error = Bool()
}

class STQCommittedStoreSerializerIO(
    val stqEntries: Int,
    val robEntries: Int,
    val issueWidth: Int,
    val addrWidth: Int = 64,
    val dataWidth: Int = 64,
    val sizeWidth: Int = 4,
    val lsidWidth: Int = 32,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val nativeBidWidth: Int = 8,
    val ridGenerationWidth: Int = 8,
    val brobGenerationWidth: Int = 8,
    val memberIndexWidth: Int = 8,
    val residentGenerationWidth: Int = 8,
    val leaseGenerationWidth: Int = 8,
    val transactionIdWidth: Int = 8,
    val transactionGenerationWidth: Int = 8)
    extends Bundle {
  val batch = Flipped(Decoupled(new STQSerializedStoreBatch(
    stqEntries, robEntries, issueWidth, addrWidth, dataWidth, sizeWidth,
    lsidWidth, peIdWidth, stidWidth, nativeBidWidth, ridGenerationWidth,
    brobGenerationWidth, memberIndexWidth, residentGenerationWidth,
    leaseGenerationWidth)))
  val request = Decoupled(new STQSerializedStoreRequest(
    stqEntries, robEntries, addrWidth, dataWidth, sizeWidth, lsidWidth,
    peIdWidth, stidWidth, nativeBidWidth, ridGenerationWidth,
    brobGenerationWidth, memberIndexWidth, residentGenerationWidth,
    leaseGenerationWidth, transactionIdWidth, transactionGenerationWidth))
  val response = Flipped(Decoupled(
    new STQSerializedStoreResponse(
      transactionIdWidth, transactionGenerationWidth)))
  /** Ordinary recovery fences only new committed work.  An accepted batch is
    * already architectural and must run to its exact response.
    */
  val recoveryActive = Input(Bool())

  val freeMask = Output(Valid(UInt(stqEntries.W)))
  val logicalCompletion = Output(Valid(new STQCommitLogicalCompletion(
    robEntries, lsidWidth, peIdWidth, stidWidth, nativeBidWidth,
    ridGenerationWidth, brobGenerationWidth, memberIndexWidth,
    residentGenerationWidth)))
  val terminalError = Output(Bool())
  val canAcceptBatch = Output(Bool())
  val busy = Output(Bool())
  val waitingResponse = Output(Bool())
  val batchMalformed = Output(Bool())
  val staleResponse = Output(Bool())
  val acceptedRequestCount = Output(UInt(log2Ceil(issueWidth * 2 + 1).W))
}

/** Exactly-once committed store transport for NormalNonCacheable and MMIO.
  *
  * At most one external request is outstanding.  Request payload and
  * transaction generation remain stable under backpressure.  STQ rows and
  * logical completion are released only after every fragment receives its
  * exact response; ordinary recovery never reissues or cancels accepted work.
  */
class STQCommittedStoreSerializer(
    val stqEntries: Int = 16,
    val robEntries: Int = 64,
    val issueWidth: Int = 2,
    val addrWidth: Int = 64,
    val dataWidth: Int = 64,
    val sizeWidth: Int = 4,
    val lsidWidth: Int = 32,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val nativeBidWidth: Int = 8,
    val ridGenerationWidth: Int = 8,
    val brobGenerationWidth: Int = 8,
    val memberIndexWidth: Int = 8,
    val residentGenerationWidth: Int = 8,
    val leaseGenerationWidth: Int = 8,
    val transactionIdWidth: Int = 8,
    val transactionGenerationWidth: Int = 8)
    extends Module {
  require(stqEntries > 1 && (stqEntries & (stqEntries - 1)) == 0,
    "serialized stores require a power-of-two physical STQ")
  require(robEntries > 1 && (robEntries & (robEntries - 1)) == 0,
    "serialized stores require a power-of-two ROB identity space")
  require(issueWidth > 0, "serialized store issue width must be nonzero")
  require(transactionIdWidth > 1,
    "serialized response identity needs a generation-capable transaction ID")
  require(transactionGenerationWidth > 0,
    "serialized response identity needs a transaction generation")

  private val requestCount = issueWidth * 2
  private val requestCountWidth = log2Ceil(requestCount + 1)

  val io = IO(new STQCommittedStoreSerializerIO(
    stqEntries, robEntries, issueWidth, addrWidth, dataWidth, sizeWidth,
    lsidWidth, peIdWidth, stidWidth, nativeBidWidth, ridGenerationWidth,
    brobGenerationWidth, memberIndexWidth, residentGenerationWidth,
    leaseGenerationWidth, transactionIdWidth, transactionGenerationWidth))

  val busy = RegInit(false.B)
  val waitingResponse = RegInit(false.B)
  val pendingMask = RegInit(0.U(requestCount.W))
  val retainedClass = RegInit(STQMemoryClass.Unknown)
  val retainedIssues = Reg(Vec(issueWidth, new STQCommitIssue(
    robEntries, stqEntries, lsidWidth, peIdWidth, stidWidth, nativeBidWidth,
    ridGenerationWidth, brobGenerationWidth, memberIndexWidth,
    residentGenerationWidth, leaseGenerationWidth)))
  val retainedRequests = Reg(Vec(requestCount, new STQCommitDrainRequest(
    stqEntries, addrWidth, dataWidth, sizeWidth, robEntries, lsidWidth,
    stidWidth)))
  val nextTransactionId = RegInit(0.U(transactionIdWidth.W))
  val nextTransactionGeneration =
    RegInit(0.U(transactionGenerationWidth.W))
  val outstandingTransactionId = Reg(UInt(transactionIdWidth.W))
  val outstandingTransactionGeneration =
    Reg(UInt(transactionGenerationWidth.W))
  val accumulatedError = RegInit(false.B)
  val acceptedRequestCount = RegInit(0.U(requestCountWidth.W))

  val inputRequestMask = VecInit(io.batch.bits.requests.map(_.valid)).asUInt
  val inputIssueMask = VecInit(io.batch.bits.issues.map(_.valid)).asUInt
  val serializedClass =
    io.batch.bits.memoryClass === STQMemoryClass.NormalNonCacheable ||
      io.batch.bits.memoryClass === STQMemoryClass.DeviceMmio
  val requestShapeExact = (0 until requestCount).map { index =>
    val request = io.batch.bits.requests(index)
    val issue = io.batch.bits.issues(index / 2)
    !request.valid || (issue.valid && request.memoryClass ===
      io.batch.bits.memoryClass && request.stqIndex === issue.stqIndex &&
      request.stid === issue.stid && request.lsId === issue.lsId)
  }.reduce(_ && _)
  val fragmentShapeExact = (0 until issueWidth).map { lane =>
    val issue = io.batch.bits.issues(lane)
    val first = io.batch.bits.requests(lane * 2)
    val second = io.batch.bits.requests(lane * 2 + 1)
    val oneFragment = issue.valid && first.valid && !second.valid &&
      !first.split && first.segment === 0.U && first.last &&
      first.ownsStqRow
    val twoFragments = issue.valid && first.valid && second.valid &&
      first.split && second.split && first.segment === 0.U &&
      second.segment === 1.U && !first.last && second.last &&
      !first.ownsStqRow && second.ownsStqRow
    (!issue.valid && !first.valid && !second.valid) ||
      oneFragment || twoFragments
  }.reduce(_ && _)
  val singleLogical = (0 until issueWidth).map { lane =>
    !io.batch.bits.issues(lane).valid ||
      io.batch.bits.issues(lane).exactOwner.asUInt ===
        io.batch.bits.issues(PriorityEncoder(inputIssueMask)).exactOwner.asUInt
  }.reduce(_ && _)
  val inputShapeExact = serializedClass && inputRequestMask.orR &&
    inputIssueMask.orR && requestShapeExact && fragmentShapeExact &&
    singleLogical

  io.batch.ready := !busy && !io.recoveryActive && inputShapeExact
  io.batchMalformed := io.batch.valid && !inputShapeExact

  val currentIndex = PriorityEncoder(pendingMask)
  val currentRequest = retainedRequests(currentIndex)
  val currentIssue = retainedIssues(currentIndex >> 1)
  io.request.valid := busy && !waitingResponse && pendingMask.orR
  io.request.bits.transactionId := nextTransactionId
  io.request.bits.transactionGeneration := nextTransactionGeneration
  io.request.bits.memoryClass := retainedClass
  io.request.bits.issue := currentIssue
  io.request.bits.fragment := currentRequest

  val responseExact = waitingResponse &&
    io.response.bits.transactionId === outstandingTransactionId &&
    io.response.bits.transactionGeneration === outstandingTransactionGeneration
  io.response.ready := responseExact
  io.staleResponse := io.response.valid && !responseExact

  val requestFire = io.request.fire
  val responseFire = io.response.fire
  val remainingAfterResponse = pendingMask & ~(1.U << currentIndex)
  val terminalResponse = responseFire && !remainingAfterResponse.orR

  io.freeMask.valid := terminalResponse
  io.freeMask.bits := VecInit((0 until stqEntries).map { index =>
    retainedIssues.map(issue =>
      issue.valid && issue.stqIndex === index.U).reduce(_ || _)
  }).asUInt

  val leaderIndex = PriorityEncoder(VecInit(retainedIssues.map(_.valid)).asUInt)
  val leader = retainedIssues(leaderIndex)
  io.logicalCompletion.valid := terminalResponse
  io.logicalCompletion.bits := 0.U.asTypeOf(io.logicalCompletion.bits)
  io.logicalCompletion.bits.valid := terminalResponse
  io.logicalCompletion.bits.stid := leader.stid
  io.logicalCompletion.bits.logicalFirstLsid := leader.logicalFirstLsid
  io.logicalCompletion.bits.logicalFirstStoreId := leader.logicalFirstStoreId
  io.logicalCompletion.bits.logicalRequestCount := leader.logicalRequestCount
  io.logicalCompletion.bits.exactOwner := leader.exactOwner
  io.terminalError := terminalResponse &&
    (accumulatedError || io.response.bits.error)
  io.canAcceptBatch := !busy && !io.recoveryActive
  io.busy := busy
  io.waitingResponse := waitingResponse
  io.acceptedRequestCount := acceptedRequestCount

  when(io.batch.fire) {
    busy := true.B
    waitingResponse := false.B
    pendingMask := inputRequestMask
    retainedClass := io.batch.bits.memoryClass
    retainedIssues := io.batch.bits.issues
    retainedRequests := io.batch.bits.requests
    accumulatedError := false.B
    acceptedRequestCount := 0.U
  }

  when(requestFire) {
    waitingResponse := true.B
    outstandingTransactionId := nextTransactionId
    outstandingTransactionGeneration := nextTransactionGeneration
    when(nextTransactionId.andR) {
      nextTransactionGeneration := nextTransactionGeneration + 1.U
    }
    nextTransactionId := nextTransactionId + 1.U
    acceptedRequestCount := acceptedRequestCount + 1.U
  }

  when(responseFire) {
    pendingMask := remainingAfterResponse
    accumulatedError := accumulatedError || io.response.bits.error
    when(remainingAfterResponse.orR) {
      waitingResponse := false.B
    }.otherwise {
      busy := false.B
      waitingResponse := false.B
    }
  }

  when(io.request.valid && !io.request.ready) {
    assert(io.request.bits.transactionId === nextTransactionId,
      "serialized store transaction identity must remain stable")
    assert(io.request.bits.transactionGeneration === nextTransactionGeneration,
      "serialized store transaction generation must remain stable")
  }
  when(responseFire) {
    assert(io.response.bits.transactionId === outstandingTransactionId,
      "only the exact serialized-store response may advance state")
    assert(io.response.bits.transactionGeneration ===
      outstandingTransactionGeneration,
      "serialized-store response generation must match exactly")
  }
}
