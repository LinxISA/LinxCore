package linxcore.rename

import chisel3._
import chisel3.util.log2Ceil

import linxcore.bctrl.BID
import linxcore.commit.{CommitTraceParams, CommitTraceRow}
import linxcore.common._
import linxcore.recovery.RecoveryCleanupIntent
import linxcore.rob.ROBID

class ScalarTURenameBridgeIO(
    val p: InterfaceParams = InterfaceParams(),
    val traceParams: CommitTraceParams = CommitTraceParams(),
    val scalarArchRegs: Int = 24,
    val physRegs: Int = 64,
    val localRegsT: Int = 32,
    val localRegsU: Int = 32,
    val mapQDepth: Int = 32,
    val bidWidth: Int = BID.DefaultWidth,
    val stidWidth: Int = 8,
    val peIdWidth: Int = 8,
    val tidWidth: Int = 8)
    extends Bundle {
  private val gprFreeWidth = log2Ceil(physRegs + 1)
  private val gprMapQFreeWidth = log2Ceil(mapQDepth + 1)
  private val tuCountWidth = log2Ceil(Seq(localRegsT, localRegsU, mapQDepth).max + 1)

  val in = Input(new DecodedUop(p))
  val outReady = Input(Bool())
  val robAllocReady = Input(Bool())

  val checkpointValid = Input(Bool())
  val checkpointBid = Input(new ROBID(p.robEntries))
  val commitValid = Input(Bool())
  val commitBid = Input(new ROBID(p.robEntries))
  val cleanup = Input(new RecoveryCleanupIntent(p.robEntries, bidWidth, peIdWidth, stidWidth, tidWidth))
  val robSource = Input(new TULinkFlushSequenceSource(p, mapQDepth, stidWidth))
  val lsuSource = Input(new TULinkFlushSequenceSource(p, mapQDepth, stidWidth))

  val tuRetireValid = Input(Bool())
  val tuRetireKind = Input(DestinationKind())
  val tuRetireSeq = Input(new ROBID(mapQDepth))
  val tuRetireDealloc = Input(Bool())
  val tuRetireAccepted = Output(Bool())
  val tuRetireMiss = Output(Bool())
  val tuRetireReleaseMismatch = Output(Bool())
  val tuRetireUnsupported = Output(Bool())
  val tuLocalBlockCommitValid = Input(Bool())
  val tuLocalBlockCommitBid = Input(new ROBID(p.robEntries))
  val tuLocalBlockCommitReady = Output(Bool())
  val tuLocalBlockCommitAccepted = Output(Bool())

  val inReady = Output(Bool())
  val accepted = Output(Bool())
  val outValid = Output(Bool())
  val out = Output(new RenamedUop(p))

  val robAllocAttemptValid = Output(Bool())
  val robAllocValid = Output(Bool())
  val robAllocRow = Output(new CommitTraceRow(traceParams))

  val needsGprRename = Output(Bool())
  val needsTAlloc = Output(Bool())
  val needsUAlloc = Output(Bool())
  val unsupportedSrcMask = Output(UInt(3.W))
  val unsupportedDst = Output(Bool())
  val unsupportedOperandClass = Output(Bool())
  val unsupported = Output(Bool())
  val blockedByMaintenance = Output(Bool())
  val blockedByRename = Output(Bool())
  val blockedByRob = Output(Bool())
  val blockedByOutput = Output(Bool())
  val blockedByTURename = Output(Bool())

  val srcPhysTags = Output(Vec(3, UInt(p.physRegWidth.W)))
  val dstPhysTag = Output(UInt(p.physRegWidth.W))
  val dstOldPhysTag = Output(UInt(p.physRegWidth.W))
  val renameAccepted = Output(Bool())
  val checkpointAccepted = Output(Bool())
  val commitAccepted = Output(Bool())
  val cleanupFlushApplied = Output(Bool())
  val cleanupReplayObserved = Output(Bool())
  val gprFreeCount = Output(UInt(gprFreeWidth.W))
  val gprMapQFreeCount = Output(UInt(gprMapQFreeWidth.W))

  val tuReady = Output(Bool())
  val tuAccepted = Output(Bool())
  val tuSrc = Output(Vec(3, new TULinkResolvedOperand(p, mapQDepth)))
  val tuDst = Output(new TULinkResolvedDestination(p, mapQDepth))
  val tuTSeq = Output(new ROBID(mapQDepth))
  val tuUSeq = Output(new ROBID(mapQDepth))
  val tuDstValid = Output(Bool())
  val tuDstKind = Output(DestinationKind())
  val tuBlockedByTAlloc = Output(Bool())
  val tuBlockedByUAlloc = Output(Bool())
  val tuSourceUnderflowMask = Output(UInt(3.W))
  val tuTUsedEntries = Output(UInt(tuCountWidth.W))
  val tuUUsedEntries = Output(UInt(tuCountWidth.W))

  val tuCleanupPublisherFlushValid = Output(Bool())
  val tuCleanupPublisherFlushBaseOnBid = Output(Bool())
  val tuCleanupPublisherFlushBid = Output(new ROBID(p.robEntries))
  val tuCleanupPublisherFlushRid = Output(new ROBID(p.robEntries))
  val tuCleanupPublisherFlushTSeq = Output(new ROBID(mapQDepth))
  val tuCleanupPublisherFlushUSeq = Output(new ROBID(mapQDepth))
  val tuCleanupActive = Output(Bool())
  val tuCleanupBlockedBySource = Output(Bool())
  val tuCleanupFlushSourceRequired = Output(Bool())
  val tuCleanupFlushSourceMatched = Output(Bool())
  val tuCleanupFlushMissingSource = Output(Bool())
  val tuCleanupFlushSourceMismatch = Output(Bool())
  val tuCleanupSelectedFlushSource = Output(new TULinkFlushSequenceSource(p, mapQDepth, stidWidth))
  val tuCleanupRobSourceMatched = Output(Bool())
  val tuCleanupLsuSourceMatched = Output(Bool())
  val tuCleanupRobSourceMismatched = Output(Bool())
  val tuCleanupLsuSourceMismatched = Output(Bool())
  val tuCleanupMultipleSourcesMatched = Output(Bool())
  val tuCleanupSourceConflict = Output(Bool())
  val tuCleanupSelectorSourceMissing = Output(Bool())
  val tuCleanupSelectedFromRob = Output(Bool())
  val tuCleanupSelectedFromLsu = Output(Bool())
  val tuCleanupFlushTPrevApplied = Output(Bool())
  val tuCleanupFlushUPrevApplied = Output(Bool())
}

class ScalarTURenameBridge(
    val p: InterfaceParams = InterfaceParams(),
    val traceParams: CommitTraceParams = CommitTraceParams(),
    val scalarArchRegs: Int = 24,
    val physRegs: Int = 64,
    val localRegsT: Int = 32,
    val localRegsU: Int = 32,
    val mapQDepth: Int = 32,
    val bidWidth: Int = BID.DefaultWidth,
    val stidWidth: Int = 8,
    val peIdWidth: Int = 8,
    val tidWidth: Int = 8)
    extends Module {
  require(scalarArchRegs == 24, "scalar/TU bridge follows LinxCoreModel GPR::GPR_COUNT")
  require(physRegs == (1 << p.physRegWidth), "physical GPR count must match InterfaceParams.physRegWidth")
  require(mapQDepth > 1 && (mapQDepth & (mapQDepth - 1)) == 0, "T/U mapQ depth must be a power of two")

  val io = IO(new ScalarTURenameBridgeIO(
    p,
    traceParams,
    scalarArchRegs,
    physRegs,
    localRegsT,
    localRegsU,
    mapQDepth,
    bidWidth,
    stidWidth,
    peIdWidth,
    tidWidth
  ))

  private def isTUClass(cls: OperandClass.Type): Bool =
    cls === OperandClass.T || cls === OperandClass.U

  private def isTUDestination(kind: DestinationKind.Type): Bool =
    kind === DestinationKind.T || kind === DestinationKind.U

  val localUnsupportedSrc = Wire(Vec(3, Bool()))
  val localSrcIsTU = Wire(Vec(3, Bool()))
  for (idx <- 0 until 3) {
    localSrcIsTU(idx) := io.in.src(idx).valid && isTUClass(io.in.src(idx).operandClass)
    localUnsupportedSrc(idx) :=
      io.in.src(idx).valid &&
        (io.in.src(idx).operandClass =/= OperandClass.P) &&
        !isTUClass(io.in.src(idx).operandClass)
  }

  val dstIsTU = io.in.dst(0).valid && isTUDestination(io.in.dst(0).kind)
  val localUnsupportedDst = io.in.dst(0).valid &&
    (io.in.dst(0).kind =/= DestinationKind.Gpr) &&
    !isTUDestination(io.in.dst(0).kind)
  val localUnsupported = localUnsupportedSrc.asUInt.orR || localUnsupportedDst

  val scalarInput = Wire(new DecodedUop(p))
  scalarInput := io.in
  for (idx <- 0 until 3) {
    when(io.in.src(idx).valid && (io.in.src(idx).operandClass =/= OperandClass.P)) {
      scalarInput.src(idx).valid := false.B
      scalarInput.src(idx).operandClass := OperandClass.Invalid
      scalarInput.src(idx).archTag := 0.U
      scalarInput.src(idx).relTag := 0.U
    }
  }
  when(io.in.dst(0).valid && (io.in.dst(0).kind =/= DestinationKind.Gpr)) {
    scalarInput.dst(0).valid := false.B
    scalarInput.dst(0).kind := DestinationKind.None
    scalarInput.dst(0).archTag := 0.U
    scalarInput.dst(0).relTag := 0.U
  }

  val scalar = Module(new ScalarDecodeRenameBridge(
    p = p,
    traceParams = traceParams,
    scalarArchRegs = scalarArchRegs,
    physRegs = physRegs,
    mapQDepth = mapQDepth,
    bidWidth = bidWidth,
    stidWidth = stidWidth,
    peIdWidth = peIdWidth,
    tidWidth = tidWidth
  ))
  val tu = Module(new TULinkRecoveryCleanupPath(
    p = p,
    localRegsT = localRegsT,
    localRegsU = localRegsU,
    mapQDepth = mapQDepth,
    bidWidth = bidWidth,
    peIdWidth = peIdWidth,
    stidWidth = stidWidth,
    tidWidth = tidWidth
  ))

  scalar.io.in := scalarInput
  scalar.io.outReady := io.outReady && tu.io.ready && !localUnsupported
  scalar.io.robAllocReady := io.robAllocReady
  scalar.io.checkpointValid := io.checkpointValid
  scalar.io.checkpointBid := io.checkpointBid
  scalar.io.commitValid := io.commitValid
  scalar.io.commitBid := io.commitBid
  scalar.io.cleanup := io.cleanup

  tu.io.in := io.in
  tu.io.renameValid := scalar.io.accepted
  tu.io.retireValid := io.tuRetireValid
  tu.io.retireKind := io.tuRetireKind
  tu.io.retireSeq := io.tuRetireSeq
  tu.io.retireDealloc := io.tuRetireDealloc
  tu.io.commitValid := io.commitValid
  tu.io.commitBid := io.commitBid
  tu.io.localBlockCommitValid := io.tuLocalBlockCommitValid
  tu.io.localBlockCommitBid := io.tuLocalBlockCommitBid
  tu.io.cleanup := io.cleanup
  tu.io.robSource := io.robSource
  tu.io.lsuSource := io.lsuSource

  val tuBlocksRename =
    io.in.valid && !localUnsupported &&
      (tu.io.needsTAlloc || tu.io.needsUAlloc || tu.io.sourceUnderflowMask.orR) &&
      !tu.io.ready
  val tuBlocksMaintenance = io.in.valid && !localUnsupported && tu.io.blockedByMaintenance

  io.inReady := scalar.io.inReady && !localUnsupported
  io.accepted := scalar.io.accepted
  io.outValid := scalar.io.outValid
  io.robAllocAttemptValid := scalar.io.robAllocAttemptValid
  io.robAllocValid := scalar.io.robAllocValid
  io.robAllocRow := scalar.io.robAllocRow

  io.needsGprRename := scalar.io.needsGprRename
  io.needsTAlloc := tu.io.needsTAlloc
  io.needsUAlloc := tu.io.needsUAlloc
  io.unsupportedSrcMask := scalar.io.unsupportedSrcMask | localUnsupportedSrc.asUInt
  io.unsupportedDst := scalar.io.unsupportedDst || localUnsupportedDst
  io.unsupportedOperandClass := scalar.io.unsupportedOperandClass || localUnsupported
  io.unsupported := scalar.io.unsupported || (io.in.valid && localUnsupported)
  io.blockedByMaintenance := scalar.io.blockedByMaintenance || tuBlocksMaintenance
  io.blockedByRename := scalar.io.blockedByRename || tuBlocksRename
  io.blockedByRob := scalar.io.blockedByRob && !localUnsupported
  io.blockedByOutput := scalar.io.blockedByOutput && !localUnsupported
  io.blockedByTURename := tuBlocksRename

  io.srcPhysTags := scalar.io.srcPhysTags
  io.dstPhysTag := Mux(dstIsTU, tu.io.dst.physTag, scalar.io.dstPhysTag)
  io.dstOldPhysTag := scalar.io.dstOldPhysTag
  io.renameAccepted := scalar.io.renameAccepted || tu.io.accepted
  io.checkpointAccepted := scalar.io.checkpointAccepted
  io.commitAccepted := scalar.io.commitAccepted || tu.io.commitAccepted
  io.cleanupFlushApplied := scalar.io.cleanupFlushApplied || tu.io.flushApplied
  io.cleanupReplayObserved := scalar.io.cleanupReplayObserved
  io.gprFreeCount := scalar.io.gprFreeCount
  io.gprMapQFreeCount := scalar.io.gprMapQFreeCount

  io.tuReady := tu.io.ready
  io.tuAccepted := tu.io.accepted
  io.tuRetireAccepted := tu.io.retireAccepted
  io.tuRetireMiss := tu.io.retireMiss
  io.tuRetireReleaseMismatch := tu.io.retireReleaseMismatch
  io.tuRetireUnsupported := tu.io.retireUnsupported
  io.tuLocalBlockCommitReady := tu.io.localBlockCommitReady
  io.tuLocalBlockCommitAccepted := tu.io.localBlockCommitAccepted
  io.tuSrc := tu.io.src
  io.tuDst := tu.io.dst
  io.tuTSeq := tu.io.tSeq
  io.tuUSeq := tu.io.uSeq
  io.tuDstValid := io.accepted && tu.io.dst.allocated
  io.tuDstKind := Mux(io.tuDstValid, io.in.dst(0).kind, DestinationKind.None)
  io.tuBlockedByTAlloc := tu.io.blockedByTAlloc
  io.tuBlockedByUAlloc := tu.io.blockedByUAlloc
  io.tuSourceUnderflowMask := tu.io.sourceUnderflowMask
  io.tuTUsedEntries := tu.io.tUsedEntries
  io.tuUUsedEntries := tu.io.uUsedEntries

  val renamed = Wire(new RenamedUop(p))
  renamed := scalar.io.out
  for (idx <- 0 until 3) {
    when(io.accepted && localSrcIsTU(idx)) {
      renamed.src(idx).valid := true.B
      renamed.src(idx).operandClass := io.in.src(idx).operandClass
      renamed.src(idx).archTag := io.in.src(idx).archTag
      renamed.src(idx).relTag := io.in.src(idx).relTag
      renamed.src(idx).physTag := tu.io.src(idx).physTag
      renamed.src(idx).ready := false.B
      renamed.src(idx).producer := 0.U
      renamed.src(idx).literalValid := false.B
      renamed.src(idx).literal := 0.U
    }
  }
  when(io.accepted && dstIsTU) {
    renamed.dst(0).valid := true.B
    renamed.dst(0).kind := io.in.dst(0).kind
    renamed.dst(0).archTag := io.in.dst(0).archTag
    renamed.dst(0).relTag := io.in.dst(0).relTag
    renamed.dst(0).physTag := tu.io.dst.physTag
    renamed.dst(0).oldPhysTag := 0.U
  }
  io.out := renamed

  io.tuCleanupPublisherFlushValid := tu.io.publisherFlushValid
  io.tuCleanupPublisherFlushBaseOnBid := tu.io.publisherFlushBaseOnBid
  io.tuCleanupPublisherFlushBid := tu.io.publisherFlushBid
  io.tuCleanupPublisherFlushRid := tu.io.publisherFlushRid
  io.tuCleanupPublisherFlushTSeq := tu.io.publisherFlushTSeq
  io.tuCleanupPublisherFlushUSeq := tu.io.publisherFlushUSeq
  io.tuCleanupActive := tu.io.cleanupActive
  io.tuCleanupBlockedBySource := tu.io.cleanupBlockedBySource
  io.tuCleanupFlushSourceRequired := tu.io.flushSourceRequired
  io.tuCleanupFlushSourceMatched := tu.io.flushSourceMatched
  io.tuCleanupFlushMissingSource := tu.io.flushMissingSource
  io.tuCleanupFlushSourceMismatch := tu.io.flushSourceMismatch
  io.tuCleanupSelectedFlushSource := tu.io.selectedFlushSource
  io.tuCleanupRobSourceMatched := tu.io.robSourceMatched
  io.tuCleanupLsuSourceMatched := tu.io.lsuSourceMatched
  io.tuCleanupRobSourceMismatched := tu.io.robSourceMismatched
  io.tuCleanupLsuSourceMismatched := tu.io.lsuSourceMismatched
  io.tuCleanupMultipleSourcesMatched := tu.io.multipleSourcesMatched
  io.tuCleanupSourceConflict := tu.io.sourceConflict
  io.tuCleanupSelectorSourceMissing := tu.io.selectorSourceMissing
  io.tuCleanupSelectedFromRob := tu.io.selectedFromRob
  io.tuCleanupSelectedFromLsu := tu.io.selectedFromLsu
  io.tuCleanupFlushTPrevApplied := tu.io.flushTPrevApplied
  io.tuCleanupFlushUPrevApplied := tu.io.flushUPrevApplied
}
