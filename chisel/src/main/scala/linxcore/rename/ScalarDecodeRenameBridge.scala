package linxcore.rename

import chisel3._
import chisel3.util.log2Ceil

import linxcore.bctrl.BID
import linxcore.commit.{CommitTraceParams, CommitTraceRow}
import linxcore.common._
import linxcore.recovery.RecoveryCleanupIntent
import linxcore.rob.ROBID

class ScalarDecodeRenameBridgeIO(
    val p: InterfaceParams = InterfaceParams(),
    val traceParams: CommitTraceParams = CommitTraceParams(),
    val scalarArchRegs: Int = 24,
    val physRegs: Int = 64,
    val mapQDepth: Int = 32,
    val bidWidth: Int = BID.DefaultWidth,
    val stidWidth: Int = 8,
    val peIdWidth: Int = 8,
    val tidWidth: Int = 8)
    extends Bundle {
  val in = Input(new DecodedUop(p))
  val outReady = Input(Bool())
  val robAllocReady = Input(Bool())

  val checkpointValid = Input(Bool())
  val checkpointBid = Input(new ROBID(p.robEntries))
  val commitValid = Input(Bool())
  val commitBid = Input(new ROBID(p.robEntries))
  val commitBlockBid = Input(UInt(bidWidth.W))
  val cleanup = Input(new RecoveryCleanupIntent(p.robEntries, bidWidth, peIdWidth, stidWidth, tidWidth))

  val inReady = Output(Bool())
  val accepted = Output(Bool())
  val outValid = Output(Bool())
  val out = Output(new RenamedUop(p))

  val robAllocAttemptValid = Output(Bool())
  val robAllocValid = Output(Bool())
  val robAllocRow = Output(new CommitTraceRow(traceParams))

  val needsGprRename = Output(Bool())
  val unsupportedSrcMask = Output(UInt(3.W))
  val unsupportedDst = Output(Bool())
  val unsupportedOperandClass = Output(Bool())
  val unsupported = Output(Bool())
  val blockedByMaintenance = Output(Bool())
  val blockedByRename = Output(Bool())
  val blockedByRob = Output(Bool())
  val blockedByOutput = Output(Bool())

  val srcPhysTags = Output(Vec(3, UInt(p.physRegWidth.W)))
  val dstPhysTag = Output(UInt(p.physRegWidth.W))
  val dstOldPhysTag = Output(UInt(p.physRegWidth.W))
  val renameAccepted = Output(Bool())
  val checkpointAccepted = Output(Bool())
  val commitAccepted = Output(Bool())
  val cleanupFlushApplied = Output(Bool())
  val cleanupReplayObserved = Output(Bool())
  val gprFreeCount = Output(UInt(log2Ceil(physRegs + 1).W))
  val gprMapQValidCount = Output(UInt(log2Ceil(mapQDepth + 1).W))
  val gprMapQFreeCount = Output(UInt(log2Ceil(mapQDepth + 1).W))
  val gprSmapLiveCount = Output(UInt(log2Ceil(physRegs + 1).W))
  val gprCmapLiveCount = Output(UInt(log2Ceil(physRegs + 1).W))
  val gprMapQLiveCount = Output(UInt(log2Ceil(physRegs + 1).W))
  val gprLivePhysCount = Output(UInt(log2Ceil(physRegs + 1).W))
  val gprFreeFromLiveCount = Output(UInt(log2Ceil(physRegs + 1).W))
  val gprFreeListMismatchCount = Output(UInt(log2Ceil(physRegs + 1).W))
  val gprNextMapQValidCount = Output(UInt(log2Ceil(mapQDepth + 1).W))
  val gprNextMapQLiveCount = Output(UInt(log2Ceil(physRegs + 1).W))
  val gprNextLivePhysCount = Output(UInt(log2Ceil(physRegs + 1).W))
  val gprNextFreeFromLiveCount = Output(UInt(log2Ceil(physRegs + 1).W))
}

class ScalarDecodeRenameBridge(
    val p: InterfaceParams = InterfaceParams(),
    val traceParams: CommitTraceParams = CommitTraceParams(),
    val scalarArchRegs: Int = 24,
    val physRegs: Int = 64,
    val mapQDepth: Int = 32,
    val bidWidth: Int = BID.DefaultWidth,
    val stidWidth: Int = 8,
    val peIdWidth: Int = 8,
    val tidWidth: Int = 8)
    extends Module {
  require(scalarArchRegs == 24, "first scalar bridge follows LinxCoreModel GPR::GPR_COUNT")
  require(physRegs == (1 << p.physRegWidth), "physical GPR count must match InterfaceParams.physRegWidth")
  require(traceParams.robValueWidth >= p.robIndexWidth, "commit trace ROB value must hold the Chisel ROB index")

  private val archIdxWidth = math.max(1, log2Ceil(scalarArchRegs))
  private val scalarArchRegLimit = scalarArchRegs.U(p.archRegWidth.W)

  val io = IO(new ScalarDecodeRenameBridgeIO(
    p,
    traceParams,
    scalarArchRegs,
    physRegs,
    mapQDepth,
    bidWidth,
    stidWidth,
    peIdWidth,
    tidWidth
  ))

  private def zeroRobId: ROBID =
    0.U.asTypeOf(new ROBID(p.robEntries))

  private def fitReg(tag: UInt): UInt =
    tag.pad(traceParams.regWidth)(traceParams.regWidth - 1, 0)

  private def archIndex(tag: UInt): UInt =
    tag(archIdxWidth - 1, 0)

  private def robIdValue(id: ROBID): UInt =
    id.value.pad(32)(31, 0)

  val srcIsGpr = Wire(Vec(3, Bool()))
  val srcInRange = Wire(Vec(3, Bool()))
  val srcUnsupported = Wire(Vec(3, Bool()))
  for (idx <- 0 until 3) {
    srcIsGpr(idx) := io.in.src(idx).valid && (io.in.src(idx).operandClass === OperandClass.P)
    srcInRange(idx) := io.in.src(idx).archTag < scalarArchRegLimit
    srcUnsupported(idx) := srcIsGpr(idx) && !srcInRange(idx)
  }

  val dstIsGpr = io.in.dst(0).valid && (io.in.dst(0).kind === DestinationKind.Gpr)
  val dstInRange = io.in.dst(0).archTag < scalarArchRegLimit
  val dstUnsupported = dstIsGpr && !dstInRange

  val unsupportedOperandClass =
    io.in.src.map(src => src.valid && (src.operandClass =/= OperandClass.P)).reduce(_ || _) ||
      (io.in.dst(0).valid && (io.in.dst(0).kind =/= DestinationKind.Gpr))
  val unsupported = srcUnsupported.asUInt.orR || dstUnsupported || unsupportedOperandClass
  val cleanupActive = io.cleanup.valid && (io.cleanup.renameFlushValid || io.cleanup.renameReplayValid)
  val maintenanceBusy = cleanupActive || io.commitValid || io.checkpointValid

  val gpr = Module(new GPRRenameCheckpoint(
    entries = p.robEntries,
    archRegs = scalarArchRegs,
    physRegs = physRegs,
    mapQDepth = mapQDepth,
    bidWidth = bidWidth,
    stidWidth = stidWidth,
    peIdWidth = peIdWidth,
    tidWidth = tidWidth
  ))

  for (idx <- 0 until 3) {
    gpr.io.srcArchTags(idx) := Mux(srcIsGpr(idx) && srcInRange(idx), archIndex(io.in.src(idx).archTag), 0.U)
  }

  val needsRename = dstIsGpr && dstInRange
  val canRename = !needsRename || gpr.io.renameReady
  val allocAttemptValid = io.in.valid && !maintenanceBusy && !unsupported && canRename && io.outReady
  val canAccept = !maintenanceBusy && !unsupported && canRename && io.robAllocReady && io.outReady
  val accepted = io.in.valid && canAccept

  gpr.io.renameValid := accepted && needsRename
  gpr.io.renameArchTag := Mux(needsRename, archIndex(io.in.dst(0).archTag), 0.U)
  gpr.io.renameBid := io.in.bid
  gpr.io.renameBlockBid := Mux(io.in.blockBidValid, io.in.blockBid, 0.U)
  gpr.io.renameRid := io.in.rid
  gpr.io.renameGid := io.in.gid
  gpr.io.checkpointValid := io.checkpointValid
  gpr.io.checkpointBid := io.checkpointBid
  gpr.io.postRenameCheckpointValid := accepted
  gpr.io.postRenameCheckpointBid := io.in.bid
  gpr.io.commitValid := io.commitValid
  gpr.io.commitBid := io.commitBid
  gpr.io.commitBlockBid := io.commitBlockBid
  gpr.io.cleanup := io.cleanup

  io.inReady := canAccept
  io.accepted := accepted
  io.outValid := accepted
  io.robAllocAttemptValid := allocAttemptValid
  io.robAllocValid := accepted
  io.needsGprRename := io.in.valid && needsRename
  io.unsupportedSrcMask := srcUnsupported.asUInt
  io.unsupportedDst := dstUnsupported
  io.unsupportedOperandClass := unsupportedOperandClass
  io.unsupported := io.in.valid && unsupported
  io.blockedByMaintenance := io.in.valid && maintenanceBusy
  io.blockedByRename := io.in.valid && !maintenanceBusy && !unsupported && needsRename && !gpr.io.renameReady
  io.blockedByRob := io.in.valid && !maintenanceBusy && !unsupported && canRename && !io.robAllocReady
  io.blockedByOutput := io.in.valid && !maintenanceBusy && !unsupported && canRename && io.robAllocReady && !io.outReady
  io.srcPhysTags := gpr.io.srcPhysTags
  io.dstPhysTag := gpr.io.renamePhysTag
  io.dstOldPhysTag := Mux(needsRename, gpr.io.smap(archIndex(io.in.dst(0).archTag)), 0.U)
  io.renameAccepted := gpr.io.renameAccepted
  io.checkpointAccepted := gpr.io.checkpointAccepted
  io.commitAccepted := gpr.io.commitAccepted
  io.cleanupFlushApplied := gpr.io.cleanupFlushApplied
  io.cleanupReplayObserved := gpr.io.cleanupReplayObserved
  io.gprFreeCount := gpr.io.freeCount
  io.gprMapQValidCount := gpr.io.mapQValidCount
  io.gprMapQFreeCount := gpr.io.mapQFreeCount
  io.gprSmapLiveCount := gpr.io.smapLiveCount
  io.gprCmapLiveCount := gpr.io.cmapLiveCount
  io.gprMapQLiveCount := gpr.io.mapQLiveCount
  io.gprLivePhysCount := gpr.io.livePhysCount
  io.gprFreeFromLiveCount := gpr.io.freeFromLiveCount
  io.gprFreeListMismatchCount := gpr.io.freeListMismatchCount
  io.gprNextMapQValidCount := gpr.io.nextMapQValidCount
  io.gprNextMapQLiveCount := gpr.io.nextMapQLiveCount
  io.gprNextLivePhysCount := gpr.io.nextLivePhysCount
  io.gprNextFreeFromLiveCount := gpr.io.nextFreeFromLiveCount

  val renamed = Wire(new RenamedUop(p))
  renamed := 0.U.asTypeOf(renamed)
  renamed.valid := accepted
  renamed.peId := io.in.peId
  renamed.threadId := io.in.threadId
  renamed.pc := io.in.pc
  renamed.opcode := io.in.opcode
  renamed.dispatchTarget := DispatchTarget.safe(io.in.uopType)._1
  renamed.imm := io.in.imm
  renamed.immType := io.in.immType
  renamed.immValid := io.in.immValid
  renamed.rid := io.in.rid
  renamed.bid := io.in.bid
  renamed.gid := io.in.gid
  renamed.lsid := io.in.lsid
  renamed.isLoad := io.in.isLoad
  renamed.isStore := io.in.isStore
  renamed.storeSplitIntent := io.in.storeSplitIntent
  renamed.isLoadStorePair := io.in.isLoadStorePair
  renamed.isStorePcr := io.in.isStorePcr
  renamed.cacheMaintainNoSplit := io.in.cacheMaintainNoSplit
  renamed.sob := io.in.sob
  renamed.eob := io.in.eob
  renamed.boundaryKind := io.in.boundaryKind
  renamed.boundaryTarget := io.in.boundaryTarget
  renamed.predTaken := io.in.predTaken
  renamed.resolvedD2 := accepted
  renamed.insnLen := io.in.insnLen
  renamed.insnRaw := io.in.insnRaw
  renamed.checkpointId := io.in.checkpointId
  renamed.blockUid := io.in.blockUid
  renamed.blockBidValid := io.in.blockBidValid
  renamed.blockBid := io.in.blockBid
  renamed.uid := io.in.uid

  for (idx <- 0 until 3) {
    renamed.src(idx).valid := accepted && io.in.src(idx).valid
    renamed.src(idx).operandClass := io.in.src(idx).operandClass
    renamed.src(idx).archTag := io.in.src(idx).archTag
    renamed.src(idx).relTag := io.in.src(idx).relTag
    renamed.src(idx).physTag := Mux(srcIsGpr(idx) && srcInRange(idx), gpr.io.srcPhysTags(idx), 0.U)
    renamed.src(idx).ready := false.B
    renamed.src(idx).producer := 0.U
    renamed.src(idx).literalValid := false.B
    renamed.src(idx).literal := 0.U
  }

  renamed.dst(0).valid := accepted && io.in.dst(0).valid
  renamed.dst(0).kind := io.in.dst(0).kind
  renamed.dst(0).archTag := io.in.dst(0).archTag
  renamed.dst(0).relTag := io.in.dst(0).relTag
  renamed.dst(0).physTag := Mux(needsRename, gpr.io.renamePhysTag, 0.U)
  renamed.dst(0).oldPhysTag := Mux(needsRename, gpr.io.smap(archIndex(io.in.dst(0).archTag)), 0.U)
  io.out := renamed

  val row = Wire(new CommitTraceRow(traceParams))
  row := 0.U.asTypeOf(row)
  row.valid := accepted
  row.identity.bid := robIdValue(io.in.bid)
  row.identity.gid := robIdValue(io.in.gid)
  row.identity.rid := robIdValue(io.in.rid)
  row.blockBidValid := io.in.blockBidValid
  row.blockBid := io.in.blockBid
  row.pc := io.in.pc
  row.insn := io.in.insnRaw
  row.len := io.in.insnLen
  row.nextPc := io.in.pc + io.in.insnLen
  row.src0.valid := accepted && io.in.src(0).valid
  row.src0.reg := fitReg(io.in.src(0).archTag)
  row.src1.valid := accepted && io.in.src(1).valid
  row.src1.reg := fitReg(io.in.src(1).archTag)
  row.dst.valid := accepted && io.in.dst(0).valid
  row.dst.reg := fitReg(io.in.dst(0).archTag)
  io.robAllocRow := row
}
