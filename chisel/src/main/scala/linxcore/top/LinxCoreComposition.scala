package linxcore.top

import chisel3._
import chisel3.util.{Decoupled, Queue, Valid, log2Ceil}
import linxcore.common.InterfaceParams
import linxcore.frontend._
import linxcore.ooo.OooFrontendRecoveryContract

class LinxCoreCompositionIO(
    val p: InterfaceParams = InterfaceParams(),
    val threadCount: Int = 1,
    val lineBytes: Int = 64,
    val pageBytes: Int = 4096,
    val missEntries: Int = 8,
    val joinEntries: Int = 8,
    val lineBridgeEntries: Int = 8,
    val instructionBufferDepth: Int = 16)
    extends Bundle {
  private val missCountWidth = log2Ceil(lineBridgeEntries + 1)
  private val joinCountWidth = log2Ceil(joinEntries + 1)

  val start = Flipped(Valid(new ISideStartRequest(p)))
  val ptwRequest = Decoupled(new ISidePtwRequest(p, lineBytes, pageBytes))
  val ptwRefill = Flipped(Valid(new ISideItlbRefill(p, pageBytes)))
  val memoryRequest = Decoupled(new IfuLineMemoryReadRequest(p, lineBytes))
  val memoryResponse = Flipped(Decoupled(new IfuLineMemoryReadResponse(p, lineBytes)))
  val fetchFault = Decoupled(new ISideFetchFault(p, lineBytes))

  val invalidateItlb = Input(Bool())
  val invalidateL1I = Input(Bool())
  val d1ThreadId = Input(UInt(p.threadIdWidth.W))
  val decoded = Decoupled(new D1DecodedInstructionGroup(p))

  /** Exact Dispatch/BRU validation event supplied by the backend.
    * Full rename/dispatch/issue event generation remains outside this IFU
    * composition boundary.
    */
  val backendValidation = Flipped(Decoupled(new BackendBranchValidation(p)))

  /** Retained redirect emitted only after the OOO recovery applies. */
  val recoveryRedirect = Flipped(Decoupled(new IfuInnerFlush(p)))

  val canonicalFlush = Valid(new IfuInnerFlush(p))
  val active = Output(Vec(threadCount, Bool()))
  val currentPc = Output(Vec(threadCount, UInt(p.pcWidth.W)))
  val epochs = Output(Vec(threadCount, UInt(p.blockEpochWidth.W)))

  val missValidMask = Output(UInt(missEntries.W))
  val missOrphanMask = Output(UInt(missEntries.W))
  val lineOutstandingMask = Output(UInt(lineBridgeEntries.W))
  val lineIssuedMask = Output(UInt(lineBridgeEntries.W))
  val lineResponsePendingMask = Output(UInt(lineBridgeEntries.W))
  val lineOutstandingCount = Output(UInt(missCountWidth.W))
  val lineRequestQueueCount = Output(UInt(missCountWidth.W))
  val staleMemoryResponse = Output(Bool())

  val joinCount = Output(UInt(joinCountWidth.W))
  val lineContextCount = Output(UInt(joinCountWidth.W))
  val lineContextCompletedMask = Output(UInt(joinEntries.W))
  val bSideStageValid = Output(UInt(5.W))
  val ptwPending = Output(Bool())
  val crossLinePending = Output(Bool())
  val f3WaitingForNextLine = Output(Bool())
  val staleF2Result = Output(Bool())
  val feedbackPending = Output(Bool())
  val feedbackPendingMispredict = Output(Bool())
}

/** Canonical IFU boundary composed from the four selected wrappers.
  *
  * The canonical `LinxCoreIfu` owns I-SIDE, B-SIDE, canonical redirects, the
  * Instruction Buffer, and fixed-width D1 grouping. `IfuLineMemoryBridge`
  * owns tagged lower-memory transport, `D1InstructionDecodeStage` owns full
  * four-wide decode, and `IfuBackendFeedbackBridge` owns atomic predictor
  * training plus BRU recovery. No request identity is reconstructed at a
  * composition seam.
  *
  * This module deliberately exposes the exact backend validation event rather
  * than claiming that four-lane rename/dispatch/issue and full-BID cleanup are
  * already composed here.
  */
class LinxCoreComposition(
    val p: InterfaceParams = InterfaceParams(),
    val threadCount: Int = 1,
    val lineBytes: Int = 64,
    val pageBytes: Int = 4096,
    val itlbEntries: Int = 16,
    val l1iSets: Int = 64,
    val missEntries: Int = 8,
    val joinEntries: Int = 8,
    val maxGroupsPerTransaction: Int = 8,
    val instructionBufferDepth: Int = 16,
    val lineBridgeEntries: Int = 8,
    val feedbackEntries: Int = 2)
    extends Module {
  require(lineBytes == 64, "IFU composition uses 64-byte cache lines")
  require(
    missEntries >= joinEntries,
    "IFU requires one miss credit per live prediction-join transaction")
  require(
    lineBridgeEntries >= missEntries,
    "line bridge must preserve the IFU miss-table concurrency")
  require(feedbackEntries > 0)

  val io = IO(
    new LinxCoreCompositionIO(
      p,
      threadCount,
      lineBytes,
      pageBytes,
      missEntries,
      joinEntries,
      lineBridgeEntries,
      instructionBufferDepth))

  val ifu = Module(
    new LinxCoreIfu(
      p,
      threadCount,
      lineBytes,
      pageBytes,
      itlbEntries,
      l1iSets,
      missEntries,
      joinEntries,
      maxGroupsPerTransaction,
      instructionBufferDepth))
  val lineBridge = Module(new IfuLineMemoryBridge(p, lineBridgeEntries, lineBytes))
  val d1Decode = Module(new D1InstructionDecodeStage(p))
  val feedback = Module(new IfuBackendFeedbackBridge(p, feedbackEntries))
  val backendRecoveryQueue = Module(new Queue(new IfuInnerFlush(p), 1))

  ifu.io.start := io.start
  io.ptwRequest <> ifu.io.ptwRequest
  ifu.io.ptwRefill := io.ptwRefill
  io.fetchFault <> ifu.io.fetchFault
  ifu.io.invalidateItlb := io.invalidateItlb
  ifu.io.invalidateL1I := io.invalidateL1I
  ifu.io.d1ThreadId := io.d1ThreadId

  lineBridge.io.ifuRequest <> ifu.io.lineRead
  ifu.io.lineRefill <> lineBridge.io.ifuRefill
  io.memoryRequest <> lineBridge.io.memoryRequest
  lineBridge.io.memoryResponse <> io.memoryResponse

  d1Decode.io.in <> ifu.io.d1
  d1Decode.io.flush := ifu.io.canonicalFlush.bits
  io.decoded <> d1Decode.io.out

  feedback.io.validation.valid := io.backendValidation.valid
  feedback.io.validation.bits := io.backendValidation.bits
  io.backendValidation.ready := feedback.io.validation.ready
  ifu.io.branchResolve <> feedback.io.resolve
  backendRecoveryQueue.io.enq <> feedback.io.backendRecovery

  // Applied OOO recovery has priority over the compatibility BRU-feedback
  // redirect.  If both describe the same branch event, consume the queued
  // compatibility copy on the same fire so IFU canonicalizes the event once.
  val duplicateRecovery = io.recoveryRedirect.valid &&
    backendRecoveryQueue.io.deq.valid &&
    OooFrontendRecoveryContract.sameRedirectProposal(
      io.recoveryRedirect.bits,
      backendRecoveryQueue.io.deq.bits)
  ifu.io.backendRedirect.valid :=
    io.recoveryRedirect.valid || backendRecoveryQueue.io.deq.valid
  ifu.io.backendRedirect.bits := Mux(
    io.recoveryRedirect.valid,
    io.recoveryRedirect.bits,
    backendRecoveryQueue.io.deq.bits)
  io.recoveryRedirect.ready := ifu.io.backendRedirect.ready
  backendRecoveryQueue.io.deq.ready := ifu.io.backendRedirect.ready &&
    (!io.recoveryRedirect.valid || duplicateRecovery)

  io.canonicalFlush := ifu.io.canonicalFlush
  io.active := ifu.io.active
  io.currentPc := ifu.io.currentPc
  io.epochs := ifu.io.epochs
  io.missValidMask := ifu.io.missValidMask
  io.missOrphanMask := ifu.io.missOrphanMask
  io.lineOutstandingMask := lineBridge.io.outstandingMask
  io.lineIssuedMask := lineBridge.io.issuedMask
  io.lineResponsePendingMask := lineBridge.io.responsePendingMask
  io.lineOutstandingCount := lineBridge.io.outstandingCount
  io.lineRequestQueueCount := lineBridge.io.requestQueueCount
  io.staleMemoryResponse := lineBridge.io.staleResponse
  io.joinCount := ifu.io.joinCount
  io.lineContextCount := ifu.io.lineContextCount
  io.lineContextCompletedMask := ifu.io.lineContextCompletedMask
  io.bSideStageValid := ifu.io.bSideStageValid
  io.ptwPending := ifu.io.ptwPending
  io.crossLinePending := ifu.io.crossLinePending
  io.f3WaitingForNextLine := ifu.io.f3WaitingForNextLine
  io.staleF2Result := ifu.io.staleF2Result
  io.feedbackPending := feedback.io.pending
  io.feedbackPendingMispredict := feedback.io.pendingMispredict
}
