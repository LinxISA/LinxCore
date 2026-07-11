package linxcore.recovery

import chisel3._
import linxcore.commit.CommitTraceParams
import linxcore.common.{BoundaryKind, DestinationKind}
import linxcore.lsu.ScalarLSURecoverySource
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
  val fullPe = Input(UInt(8.W))
  val peerFullValid = Input(Bool())
  val peerFullBlockBid = Input(UInt(16.W))
  val peerFullStid = Input(UInt(8.W))
  val peerFullPe = Input(UInt(8.W))
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
  val recoveryPending = Output(Bool())
  val arbiterPendingMask = Output(UInt(3.W))
  val classGlobalFlushPendingMask = Output(UInt(2.W))
  val classGlobalReplayPendingMask = Output(UInt(2.W))
  val classPePendingMask = Output(UInt(4.W))
  val arbiterSelectedValid = Output(Bool())
  val arbiterSelectedSource = Output(UInt(2.W))
  val arbiterSelectedBlockBid = Output(UInt(16.W))
  val cleanupIntentValid = Output(Bool())
  val cleanupBlockFlushValid = Output(Bool())
  val cleanupBlockFlushBid = Output(UInt(16.W))
  val sourceResolvedMask = Output(UInt(3.W))
  val consumedPayloadSourceMask = Output(UInt(3.W))
  val robFlushApplied = Output(Bool())
  val robFlushPruneMask = Output(UInt(8.W))
  val robSize = Output(UInt(4.W))
}

class RecoveryCleanupROBProbe extends Module {
  private val entries = 8
  private val mapQDepth = 8
  private val traceParams = CommitTraceParams(commitWidth = 1)

  val io = IO(new RecoveryCleanupROBProbeIO)
  val backend = Module(new RecoveryBackendControl(
    nonLsuSourceCount = 2,
    stidCount = 2,
    peCount = 2,
    entries = entries,
    bidWidth = 16
  ))
  val lsuSource = Module(new ScalarLSURecoverySource(entries = entries, bidWidth = 16))
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

  val fullReq = Wire(chiselTypeOf(backend.io.nonLsuSources(0)))
  fullReq := 0.U.asTypeOf(fullReq)
  fullReq.valid := io.fullValid
  fullReq.typ := FlushType.NukeFlush
  fullReq.stid := io.fullStid
  fullReq.peId := io.fullPe
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
  peerFullReq.peId := io.peerFullPe
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
  lsuSource.io.ringReq := annotatedRingReq
  lsuSource.io.oldestValid := io.oldestValid
  lsuSource.io.oldestBid := id(io.oldestBid)
  lsuSource.io.oldestRid := id(io.oldestRid)
  lsuSource.io.fullBidLookup := backend.io.lsuFullBidLookup
  lsuSource.io.sourceReady := backend.io.lsuSourceReady
  backend.io.lsuFullBidLookupRequest := lsuSource.io.fullBidLookupRequest
  backend.io.robFullBidLookup := rob.io.fullBidLookup
  rob.io.fullBidLookupRequest := backend.io.robFullBidLookupRequest
  backend.io.nonLsuSources(0) := fullReq
  backend.io.nonLsuSources(1) := peerFullReq
  backend.io.lsuSource := lsuSource.io.source
  backend.io.oldestBid(0) := id(io.oldestBid)
  backend.io.oldestBid(1) := id(3.U)
  backend.io.oldestBlockComplete := VecInit(false.B, false.B)
  backend.io.intentReady := io.intentReady
  rob.io.flush := backend.io.robFlush

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
  io.ringReady := lsuSource.io.ringReqReady
  io.ringAccepted := lsuSource.io.sourceAccepted
  io.fullReady := backend.io.nonLsuSourceReady(0)
  io.fullAccepted := backend.io.nonLsuSourceAccepted(0)
  io.peerFullReady := backend.io.nonLsuSourceReady(1)
  io.peerFullAccepted := backend.io.nonLsuSourceAccepted(1)
  io.ringBlockedByAge := lsuSource.io.blockedByAge
  io.ringLookupMatched := lsuSource.io.lookupMatched
  io.ringLookupBlocked :=
    lsuSource.io.blockedByLookupMiss ||
      lsuSource.io.blockedByStaleLookup ||
      lsuSource.io.blockedByRingProjection
  io.cleanupPending := backend.io.intent.valid
  io.recoveryPending := backend.io.pending
  io.arbiterPendingMask := backend.io.sourcePendingMask
  io.classGlobalFlushPendingMask := backend.io.classGlobalFlushPendingMask
  io.classGlobalReplayPendingMask := backend.io.classGlobalReplayPendingMask
  io.classPePendingMask := backend.io.classPePendingMask
  io.arbiterSelectedValid := backend.io.sourceSelectedValid
  io.arbiterSelectedSource := backend.io.sourceSelected
  io.arbiterSelectedBlockBid := backend.io.sourceSelectedBlockBid
  io.cleanupIntentValid := backend.io.intent.valid
  io.cleanupBlockFlushValid := backend.io.intent.blockFlushValid
  io.cleanupBlockFlushBid := backend.io.intent.blockFlushBid
  io.sourceResolvedMask := backend.io.sourceResolvedMask
  io.consumedPayloadSourceMask := backend.io.consumedPayloadSourceMask
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
