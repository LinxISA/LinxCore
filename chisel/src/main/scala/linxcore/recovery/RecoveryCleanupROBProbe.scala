package linxcore.recovery

import chisel3._
import linxcore.commit.CommitTraceParams
import linxcore.common.{BoundaryKind, DestinationKind}
import linxcore.rob.{ROBEntryBank, ROBID}

class RecoveryCleanupROBProbeIO extends Bundle {
  val allocValid = Input(Bool())
  val allocBid = Input(UInt(3.W))
  val allocBlockBid = Input(UInt(16.W))
  val allocStid = Input(UInt(8.W))
  val allocReady = Output(Bool())
  val fullValid = Input(Bool())
  val fullBlockBid = Input(UInt(16.W))
  val fullStid = Input(UInt(8.W))
  val peerFullValid = Input(Bool())
  val peerFullBlockBid = Input(UInt(16.W))
  val peerFullStid = Input(UInt(8.W))
  val ringValid = Input(Bool())
  val ringBid = Input(UInt(3.W))
  val ringRid = Input(UInt(3.W))
  val ringNuke = Input(Bool())
  val oldestValid = Input(Bool())
  val oldestBid = Input(UInt(3.W))
  val oldestRid = Input(UInt(3.W))
  val intentReady = Input(Bool())

  val ringReady = Output(Bool())
  val ringAccepted = Output(Bool())
  val fullReady = Output(Bool())
  val fullAccepted = Output(Bool())
  val peerFullReady = Output(Bool())
  val peerFullAccepted = Output(Bool())
  val ringBlockedByAge = Output(Bool())
  val ringLookupMatched = Output(Bool())
  val ringLookupBlocked = Output(Bool())
  val cleanupPending = Output(Bool())
  val arbiterPendingMask = Output(UInt(3.W))
  val arbiterSelectedValid = Output(Bool())
  val arbiterSelectedSource = Output(UInt(2.W))
  val arbiterSelectedBlockBid = Output(UInt(16.W))
  val cleanupIntentValid = Output(Bool())
  val cleanupBlockFlushValid = Output(Bool())
  val cleanupBlockFlushBid = Output(UInt(16.W))
  val robFlushApplied = Output(Bool())
  val robFlushPruneMask = Output(UInt(8.W))
  val robSize = Output(UInt(4.W))
}

class RecoveryCleanupROBProbe extends Module {
  private val entries = 8
  private val mapQDepth = 8
  private val traceParams = CommitTraceParams(commitWidth = 1)

  val io = IO(new RecoveryCleanupROBProbeIO)
  val cleanup = Module(new RecoveryCleanupControl(entries = entries, bidWidth = 16))
  val arbiter = Module(new RecoverySourceArbiter(
    sourceCount = 3,
    stidCount = 2,
    entries = entries,
    bidWidth = 16
  ))
  val eligibility = Module(new RecoveryEligibilityControl(entries = entries))
  val ringFullBid = Module(new RingFullBidRecoveryBridge(entries = entries, bidWidth = 16))
  val rob = Module(new ROBEntryBank(
    entries = entries,
    traceParams = traceParams,
    mapQDepth = mapQDepth
  ))

  private def id(value: UInt): ROBID = {
    val out = Wire(new ROBID(entries))
    out.valid := true.B
    out.wrap := false.B
    out.value := value
    out
  }

  val fullReq = Wire(chiselTypeOf(cleanup.io.req))
  fullReq := 0.U.asTypeOf(fullReq)
  fullReq.valid := io.fullValid
  fullReq.typ := FlushType.NukeFlush
  fullReq.stid := io.fullStid
  fullReq.blockBid := io.fullBlockBid
  fullReq.gid := id(0.U)
  fullReq.rid := id(0.U)
  fullReq.lsId := id(0.U)
  fullReq.execEngine := ExecEngineType.Scalar
  fullReq.fetchTpcValid := true.B
  val peerFullReq = Wire(chiselTypeOf(fullReq))
  peerFullReq := fullReq
  peerFullReq.valid := io.peerFullValid
  peerFullReq.stid := io.peerFullStid
  peerFullReq.blockBid := io.peerFullBlockBid
  val ringReq = Wire(new FlushReq(entries))
  ringReq := 0.U.asTypeOf(ringReq)
  ringReq.valid := io.ringValid
  ringReq.typ := Mux(io.ringNuke, FlushType.NukeFlush, FlushType.InnerFlush)
  ringReq.bid := id(io.ringBid)
  ringReq.rid := id(io.ringRid)
  ringReq.gid := id(0.U)
  ringReq.lsId := id(io.ringRid)
  ringReq.execEngine := ExecEngineType.Scalar
  ringReq.fetchTpcValid := true.B
  ringReq.immediateFlush := false.B
  val annotatedRingReq = FlushControl.annotate(ringReq)
  eligibility.io.request := annotatedRingReq
  eligibility.io.oldestValid := io.oldestValid
  eligibility.io.oldestBid := id(io.oldestBid)
  eligibility.io.oldestRid := id(io.oldestRid)
  val eligibleRingReq = Wire(chiselTypeOf(annotatedRingReq))
  eligibleRingReq := annotatedRingReq
  eligibleRingReq.req.valid := annotatedRingReq.req.valid && eligibility.io.eligible
  ringFullBid.io.ringReq := eligibleRingReq
  ringFullBid.io.lookupResult := rob.io.fullBidLookup
  rob.io.fullBidLookupRequest := ringFullBid.io.lookupRequest
  arbiter.io.sources(0) := fullReq
  arbiter.io.sources(1) := peerFullReq
  arbiter.io.sources(2) := ringFullBid.io.fullReq
  arbiter.io.oldestBid(0) := id(io.oldestBid)
  arbiter.io.oldestBid(1) := id(3.U)
  arbiter.io.outReady := cleanup.io.reqReady
  cleanup.io.req := arbiter.io.out
  cleanup.io.ringReq := 0.U.asTypeOf(cleanup.io.ringReq)
  cleanup.io.intentReady := io.intentReady

  val appliedFlush = Wire(chiselTypeOf(cleanup.io.intent.flush))
  appliedFlush := cleanup.io.intent.flush
  appliedFlush.req.valid :=
    cleanup.io.intent.valid && cleanup.io.intent.robPruneValid && io.intentReady
  rob.io.flush := appliedFlush

  val allocRow = Wire(chiselTypeOf(rob.io.allocRow))
  allocRow := 0.U.asTypeOf(allocRow)
  allocRow.valid := io.allocValid
  allocRow.identity.bid := io.allocBid
  allocRow.identity.gid := 0.U
  allocRow.identity.rid := io.allocBid
  allocRow.blockBidValid := io.allocValid
  allocRow.blockBid := io.allocBlockBid
  allocRow.pc := io.allocBid
  allocRow.len := 4.U
  rob.io.allocValid := io.allocValid
  rob.io.allocRow := allocRow
  rob.io.allocBid := id(io.allocBid)
  rob.io.allocGid := id(0.U)
  rob.io.allocPeId := 0.U
  rob.io.allocStid := io.allocStid
  rob.io.allocTid := 0.U
  rob.io.allocLsId := io.allocBid
  rob.io.allocIsLoad := true.B
  rob.io.allocIsStore := false.B
  rob.io.allocTSeq := 0.U.asTypeOf(rob.io.allocTSeq)
  rob.io.allocUSeq := 0.U.asTypeOf(rob.io.allocUSeq)
  rob.io.allocTUDstValid := false.B
  rob.io.allocTUDstKind := DestinationKind.None
  rob.io.allocIsLast := false.B
  rob.io.allocMarkerBoundary := false.B
  rob.io.allocMarkerStop := false.B
  rob.io.allocMarkerBoundaryKind := BoundaryKind.Fall
  rob.io.allocMarkerBoundaryTarget := 0.U

  rob.io.renameUpdateValid := false.B
  rob.io.renameUpdateRid := 0.U.asTypeOf(rob.io.renameUpdateRid)
  rob.io.renameUpdateRow := 0.U.asTypeOf(rob.io.renameUpdateRow)
  rob.io.renameUpdateTSeq := 0.U.asTypeOf(rob.io.renameUpdateTSeq)
  rob.io.renameUpdateUSeq := 0.U.asTypeOf(rob.io.renameUpdateUSeq)
  rob.io.renameUpdateTUDstValid := false.B
  rob.io.renameUpdateTUDstKind := DestinationKind.None
  rob.io.completeValid := false.B
  rob.io.completeRobValue := 0.U
  rob.io.completeRowValid := false.B
  rob.io.completeRow := 0.U.asTypeOf(rob.io.completeRow)
  rob.io.deallocReady := false.B
  rob.io.deallocHoldMask := 0.U
  rob.io.statusLookupValid := false.B
  rob.io.statusLookupRid := 0.U.asTypeOf(rob.io.statusLookupRid)
  rob.io.commitTraceLookupValid := false.B
  rob.io.commitTraceLookupRid := 0.U.asTypeOf(rob.io.commitTraceLookupRid)
  rob.io.commitTraceLookupSourceTraceEnable := false.B

  io.allocReady := rob.io.allocReady
  io.ringReady := arbiter.io.sourceReady(2) && eligibility.io.eligible && ringFullBid.io.matched
  io.ringAccepted := arbiter.io.sourceAccepted(2)
  io.fullReady := arbiter.io.sourceReady(0)
  io.fullAccepted := arbiter.io.sourceAccepted(0)
  io.peerFullReady := arbiter.io.sourceReady(1)
  io.peerFullAccepted := arbiter.io.sourceAccepted(1)
  io.ringBlockedByAge := eligibility.io.blockedByAge
  io.ringLookupMatched := ringFullBid.io.matched
  io.ringLookupBlocked :=
    ringFullBid.io.blockedByLookupMiss ||
      ringFullBid.io.blockedByStaleResult ||
      ringFullBid.io.blockedByRingProjection
  io.cleanupPending := cleanup.io.pending
  io.arbiterPendingMask := arbiter.io.pendingMask
  io.arbiterSelectedValid := arbiter.io.selectedSourceValid
  io.arbiterSelectedSource := arbiter.io.selectedSource
  io.arbiterSelectedBlockBid := arbiter.io.out.blockBid
  io.cleanupIntentValid := cleanup.io.intent.valid
  io.cleanupBlockFlushValid := cleanup.io.intent.blockFlushValid
  io.cleanupBlockFlushBid := cleanup.io.intent.blockFlushBid
  io.robFlushApplied := rob.io.flushApplied
  io.robFlushPruneMask := rob.io.flushPruneMask
  io.robSize := rob.io.size
}

object EmitRecoveryCleanupROBProbe extends App {
  circt.stage.ChiselStage.emitSystemVerilogFile(
    new RecoveryCleanupROBProbe,
    args = Array("--target-dir", "../generated/chisel-verilog/recovery-cleanup-rob-probe"),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
}
