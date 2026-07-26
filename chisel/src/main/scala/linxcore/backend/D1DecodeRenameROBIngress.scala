package linxcore.backend

import chisel3._
import chisel3.util.{Decoupled, log2Ceil}
import linxcore.commit.{CommitTraceParams, CommitTracePort, CommitTraceRow}
import linxcore.common.{InterfaceParams, RenamedUop}
import linxcore.frontend.{D1DecodedInstructionGroup, D1DecodedLaneQueue, F4Slot, IfuInnerFlush}
import linxcore.lsu.StoreDispatchExecResult
import linxcore.rob.ROBID

class D1DecodeRenameROBIngressIO(
    val p: InterfaceParams = InterfaceParams(),
    val traceParams: CommitTraceParams = CommitTraceParams(),
    val laneQueueDepth: Int = 8)
    extends Bundle {
  private val queueCountWidth = log2Ceil(laneQueueDepth + 1)
  private val robCountWidth = log2Ceil(p.robEntries + 1)

  val in = Flipped(Decoupled(new D1DecodedInstructionGroup(p)))
  val ifuFlush = Input(new IfuInnerFlush(p))
  val renamedOutReady = Input(Bool())
  val completeValid = Input(Bool())
  val completeRobValue = Input(UInt(p.robIndexWidth.W))
  val deallocReady = Input(Bool())
  val blockBranchTakenValid = Input(Bool())
  val blockBranchTaken = Input(Bool())

  val laneDequeued = Output(Bool())
  val laneQueueCount = Output(UInt(queueCountWidth.W))
  val laneQueueRejectedMalformed = Output(Bool())
  val laneQueueRebasedTrigger = Output(Bool())
  val selectedValid = Output(Bool())
  val selectedSlot = Output(UInt(math.max(1, log2Ceil(p.decodeWidth)).W))
  val decRenPushFire = Output(Bool())
  val renamedOutValid = Output(Bool())
  val renamedOut = Output(new RenamedUop(p))
  val renamedAccepted = Output(Bool())
  val serviceAdjacentStop = Output(new DecodeRenameServiceAdjacentStop(p))

  val robAllocFire = Output(Bool())
  val completeAccepted = Output(Bool())
  val commit = Output(new CommitTracePort(traceParams))
  val commitValidMask = Output(UInt(traceParams.commitWidth.W))
  val commitCount = Output(UInt(log2Ceil(traceParams.commitWidth + 1).W))
  val robSize = Output(UInt(robCountWidth.W))
  val robOutstandingCount = Output(UInt(robCountWidth.W))
  val robEmpty = Output(Bool())
}

/** Fixed-width production D1 ingress into the existing rename/ROB owner.
  *
  * The queue accepts a complete four-lane decoded group atomically and presents
  * one already-decoded lane at a time to `DecodeRenameROBPath`. It never
  * recreates a fetch packet, byte window, or F4 slot. Precise IFU recovery only
  * prunes rows that have not crossed this ingress; rows already allocated in
  * the backend remain subject to the backend's full-BID recovery fabric.
  */
class D1DecodeRenameROBIngress(
    val p: InterfaceParams = InterfaceParams(),
    val traceParams: CommitTraceParams = CommitTraceParams(),
    val laneQueueDepth: Int = 8,
    val decRenQueueDepth: Int = 4,
    val mapQDepth: Int = 32,
    val gprMapQDepth: Int = 32)
    extends Module {
  require(p.decodeWidth == 4, "production D1 ingress is four-wide")
  require(traceParams.commitWidth == p.commitWidth)
  require(traceParams.robValueWidth >= p.robIndexWidth)

  val io = IO(new D1DecodeRenameROBIngressIO(p, traceParams, laneQueueDepth))

  val laneQueue = Module(new D1DecodedLaneQueue(p, laneQueueDepth))
  val path = Module(new DecodeRenameROBPath(
    p = p,
    traceParams = traceParams,
    decRenQueueDepth = decRenQueueDepth,
    mapQDepth = mapQDepth,
    gprMapQDepth = gprMapQDepth,
    skipBlockMarkers = true,
    reducedStoreDispatchBypass = true,
    enablePacketDecodeAdapter = false))

  laneQueue.io.in <> io.in
  laneQueue.io.flush := io.ifuFlush
  laneQueue.io.out.ready := path.io.decodeReady

  path.io.predecodedD1Valid := laneQueue.io.out.valid
  path.io.predecodedD1 := laneQueue.io.out.bits
  path.io.predecodedNextValid := laneQueue.io.nextSameGroupValid
  path.io.predecodedNext := laneQueue.io.nextSameGroupUop

  path.io.d1 := 0.U.asTypeOf(path.io.d1)
  path.io.slots := 0.U.asTypeOf(path.io.slots)
  path.io.validMask := 0.U
  path.io.samePacketNextSlotValid := false.B
  path.io.samePacketNextSlot := 0.U.asTypeOf(new F4Slot(p))
  // IFU recovery is precise at the ingress queue. Backend-resident rows are
  // recovered by full-BID sources, not by broad frontend invalidation.
  path.io.flushValid := false.B

  DecodeRenameROBPath.tieOffExplicitStoreCount(path)
  DecodeRenameROBPath.tieOffStoreScResult(path)
  DecodeRenameROBPath.tieOffRecovery(path)
  path.io.renamedOutReady := io.renamedOutReady
  path.io.storeStaExec := 0.U.asTypeOf(
    new StoreDispatchExecResult(64, 64, p.peIdWidth, p.threadIdWidth, p.threadIdWidth))
  path.io.storeStdExec := 0.U.asTypeOf(
    new StoreDispatchExecResult(64, 64, p.peIdWidth, p.threadIdWidth, p.threadIdWidth))
  path.io.storeAddressInsertPermit := true.B
  path.io.storeMarkCommitValid := false.B
  path.io.storeMarkCommitIndex := 0.U
  path.io.storeCommitFreeValid := false.B
  path.io.storeCommitFreeIndex := 0.U
  path.io.storeCommitFreeMaskValid := false.B
  path.io.storeCommitFreeMask := 0.U
  path.io.checkpointValid := false.B
  path.io.checkpointBid := ROBID.disabled(p.robEntries)
  path.io.checkpointStid := 0.U
  path.io.commitValid := false.B
  path.io.commitBid := ROBID.disabled(p.robEntries)
  path.io.commitBlockBid := 0.U
  path.io.commitStid := 0.U
  path.io.scalarCleanupOrderValid := false.B
  path.io.scalarCleanupOrder := 0.U
  path.io.completeValid := io.completeValid
  path.io.completeRobValue := io.completeRobValue
  path.io.completeRowValid := false.B
  path.io.completeRow := 0.U.asTypeOf(new CommitTraceRow(traceParams))
  path.io.blockBranchTakenValid := io.blockBranchTakenValid
  path.io.blockBranchTaken := io.blockBranchTaken
  path.io.scalarRedirectValid := false.B
  path.io.scalarRedirectStid := 0.U
  path.io.deallocReady := io.deallocReady
  path.io.deallocHoldMask := 0.U
  path.io.robStatusLookupValid := false.B
  path.io.robStatusLookupRid := ROBID.disabled(p.robEntries)
  path.io.robCommitTraceLookupValid := false.B
  path.io.robCommitTraceLookupRid := ROBID.disabled(p.robEntries)
  path.io.robCommitTraceLookupSourceTraceEnable := false.B

  io.laneDequeued := laneQueue.io.out.fire
  io.laneQueueCount := laneQueue.io.count
  io.laneQueueRejectedMalformed := laneQueue.io.rejectedMalformed
  io.laneQueueRebasedTrigger := laneQueue.io.rebasedTrigger
  io.selectedValid := path.io.selectedValid
  io.selectedSlot := path.io.selectedSlot
  io.decRenPushFire := path.io.decRenPushFire
  io.renamedOutValid := path.io.renamedOutValid
  io.renamedOut := path.io.renamedOut
  io.renamedAccepted := path.io.accepted
  io.serviceAdjacentStop := path.io.serviceAdjacentStop
  io.robAllocFire := path.io.robAllocFire
  io.completeAccepted := path.io.completeAccepted
  io.commit := path.io.commit
  io.commitValidMask := path.io.commitValidMask
  io.commitCount := path.io.commitCount
  io.robSize := path.io.size
  io.robOutstandingCount := path.io.outstandingCount
  io.robEmpty := path.io.empty
}

object EmitD1DecodeRenameROBIngress extends App {
  val p = InterfaceParams(robEntries = 8, commitWidth = 2)
  val trace = CommitTraceParams(
    commitWidth = p.commitWidth,
    robValueWidth = p.robIndexWidth,
    blockBidWidth = p.blockBidWidth,
    pcWidth = p.pcWidth,
    insnWidth = p.insnWidth,
    lenWidth = p.lenWidth)
  circt.stage.ChiselStage.emitSystemVerilogFile(
    new D1DecodeRenameROBIngress(p, trace, mapQDepth = 8, gprMapQDepth = 16),
    args,
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info"))
}
