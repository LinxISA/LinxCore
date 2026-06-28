package linxcore.rename

import chisel3._
import chisel3.util.log2Ceil

import linxcore.bctrl.BID
import linxcore.common._
import linxcore.recovery.RecoveryCleanupIntent
import linxcore.rob.ROBID

class TULinkRecoveryCleanupPathIO(
    val p: InterfaceParams = InterfaceParams(),
    val localRegsT: Int = 32,
    val localRegsU: Int = 32,
    val mapQDepth: Int = 32,
    val bidWidth: Int = BID.DefaultWidth,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8)
    extends Bundle {
  private val countWidth = log2Ceil(Seq(localRegsT, localRegsU, mapQDepth).max + 1)

  val in = Input(new DecodedUop(p))
  val renameValid = Input(Bool())

  val retireValid = Input(Bool())
  val retireKind = Input(DestinationKind())
  val retireSeq = Input(new ROBID(mapQDepth))
  val retireDealloc = Input(Bool())
  val commitValid = Input(Bool())
  val commitBid = Input(new ROBID(p.robEntries))
  val localBlockCommitValid = Input(Bool())
  val localBlockCommitBid = Input(new ROBID(p.robEntries))
  val localBlockCommitStid = Input(UInt(stidWidth.W))
  val cleanup = Input(new RecoveryCleanupIntent(p.robEntries, bidWidth, peIdWidth, stidWidth, tidWidth))
  val robSource = Input(new TULinkFlushSequenceSource(p, mapQDepth, stidWidth))
  val lsuSource = Input(new TULinkFlushSequenceSource(p, mapQDepth, stidWidth))

  val ready = Output(Bool())
  val accepted = Output(Bool())

  val src = Output(Vec(3, new TULinkResolvedOperand(p, mapQDepth)))
  val dst = Output(new TULinkResolvedDestination(p, mapQDepth))

  val tSeq = Output(new ROBID(mapQDepth))
  val uSeq = Output(new ROBID(mapQDepth))
  val tDeallocSeq = Output(new ROBID(mapQDepth))
  val uDeallocSeq = Output(new ROBID(mapQDepth))
  val needsTAlloc = Output(Bool())
  val needsUAlloc = Output(Bool())
  val blockedByTAlloc = Output(Bool())
  val blockedByUAlloc = Output(Bool())
  val sourceUnderflowMask = Output(UInt(3.W))
  val blockedByMaintenance = Output(Bool())
  val retireAccepted = Output(Bool())
  val retireMiss = Output(Bool())
  val retireReleaseMismatch = Output(Bool())
  val retireUnsupported = Output(Bool())
  val commitAccepted = Output(Bool())
  val localBlockCommitReady = Output(Bool())
  val localBlockCommitAccepted = Output(Bool())
  val localBlockCommitStidMatch = Output(Bool())
  val localBlockCommitBlockedByStid = Output(Bool())
  val flushApplied = Output(Bool())

  val tAllocPhysTag = Output(UInt(p.physRegWidth.W))
  val uAllocPhysTag = Output(UInt(p.physRegWidth.W))
  val tMapQValidMask = Output(UInt(mapQDepth.W))
  val uMapQValidMask = Output(UInt(mapQDepth.W))
  val tRetiredMask = Output(UInt(mapQDepth.W))
  val uRetiredMask = Output(UInt(mapQDepth.W))
  val tReleasedMask = Output(UInt(mapQDepth.W))
  val uReleasedMask = Output(UInt(mapQDepth.W))
  val tFlushedMask = Output(UInt(mapQDepth.W))
  val uFlushedMask = Output(UInt(mapQDepth.W))
  val tUsedEntries = Output(UInt(countWidth.W))
  val uUsedEntries = Output(UInt(countWidth.W))
  val tUsedPhys = Output(UInt(countWidth.W))
  val uUsedPhys = Output(UInt(countWidth.W))

  val publisherFlushValid = Output(Bool())
  val publisherFlushBaseOnBid = Output(Bool())
  val publisherFlushBid = Output(new ROBID(p.robEntries))
  val publisherFlushRid = Output(new ROBID(p.robEntries))
  val publisherFlushTSeq = Output(new ROBID(mapQDepth))
  val publisherFlushUSeq = Output(new ROBID(mapQDepth))
  val cleanupActive = Output(Bool())
  val cleanupBlockedBySource = Output(Bool())
  val flushSourceRequired = Output(Bool())
  val flushSourceMatched = Output(Bool())
  val flushMissingSource = Output(Bool())
  val flushSourceMismatch = Output(Bool())
  val selectedFlushSource = Output(new TULinkFlushSequenceSource(p, mapQDepth, stidWidth))
  val robSourceMatched = Output(Bool())
  val lsuSourceMatched = Output(Bool())
  val robSourceMismatched = Output(Bool())
  val lsuSourceMismatched = Output(Bool())
  val multipleSourcesMatched = Output(Bool())
  val sourceConflict = Output(Bool())
  val selectorSourceMissing = Output(Bool())
  val selectedFromRob = Output(Bool())
  val selectedFromLsu = Output(Bool())
  val flushTPrevApplied = Output(Bool())
  val flushUPrevApplied = Output(Bool())
}

class TULinkRecoveryCleanupPath(
    val p: InterfaceParams = InterfaceParams(),
    val localRegsT: Int = 32,
    val localRegsU: Int = 32,
    val mapQDepth: Int = 32,
    val bidWidth: Int = BID.DefaultWidth,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val localStid: Int = 0)
    extends Module {
  require(mapQDepth > 1 && (mapQDepth & (mapQDepth - 1)) == 0, "T/U mapQ depth must be a power of two")
  require(stidWidth > 0, "STID width must be positive")
  require(localStid >= 0 && BigInt(localStid) < (BigInt(1) << stidWidth), "local STID must fit stidWidth")

  val io = IO(new TULinkRecoveryCleanupPathIO(
    p,
    localRegsT,
    localRegsU,
    mapQDepth,
    bidWidth,
    peIdWidth,
    stidWidth,
    tidWidth
  ))

  val selector = Module(new TULinkFlushSourceSelector(
    p,
    mapQDepth,
    bidWidth,
    peIdWidth,
    stidWidth,
    tidWidth
  ))
  val publisher = Module(new TULinkFlushSequencePublisher(
    p,
    mapQDepth,
    bidWidth,
    peIdWidth,
    stidWidth,
    tidWidth
  ))
  val rename = Module(new TULinkRename(p, localRegsT, localRegsU, mapQDepth, bidWidth))

  selector.io.cleanup := io.cleanup
  selector.io.robSource := io.robSource
  selector.io.lsuSource := io.lsuSource

  publisher.io.cleanup := io.cleanup
  publisher.io.source := selector.io.source

  val cleanupActive = io.cleanup.valid && io.cleanup.backendFlushValid
  val cleanupBlockedBySource = cleanupActive && !publisher.io.flushValid
  val localBlockCommitStidMatch = io.localBlockCommitStid === localStid.U(stidWidth.W)
  val localBlockCommitReady =
    localBlockCommitStidMatch && !io.commitValid && !cleanupBlockedBySource && !publisher.io.flushValid
  val localBlockCommitFire = io.localBlockCommitValid && localBlockCommitReady

  rename.io.in := io.in
  rename.io.renameValid := io.renameValid && !cleanupBlockedBySource
  rename.io.retireValid := io.retireValid && !cleanupBlockedBySource
  rename.io.retireKind := io.retireKind
  rename.io.retireSeq := io.retireSeq
  rename.io.retireDealloc := io.retireDealloc
  rename.io.commitValid := (io.commitValid || localBlockCommitFire) && !cleanupBlockedBySource
  rename.io.commitBid := Mux(io.commitValid, io.commitBid, io.localBlockCommitBid)
  rename.io.flushValid := publisher.io.flushValid
  rename.io.flushBaseOnBid := publisher.io.flushBaseOnBid
  rename.io.flushBid := publisher.io.flushBid
  rename.io.flushRid := publisher.io.flushRid
  rename.io.flushTSeq := publisher.io.flushTSeq
  rename.io.flushUSeq := publisher.io.flushUSeq

  io.ready := rename.io.ready && !cleanupBlockedBySource
  io.accepted := rename.io.accepted
  io.src := rename.io.src
  io.dst := rename.io.dst
  io.tSeq := rename.io.tSeq
  io.uSeq := rename.io.uSeq
  io.tDeallocSeq := rename.io.tDeallocSeq
  io.uDeallocSeq := rename.io.uDeallocSeq
  io.needsTAlloc := rename.io.needsTAlloc
  io.needsUAlloc := rename.io.needsUAlloc
  io.blockedByTAlloc := rename.io.blockedByTAlloc
  io.blockedByUAlloc := rename.io.blockedByUAlloc
  io.sourceUnderflowMask := rename.io.sourceUnderflowMask
  io.blockedByMaintenance := rename.io.blockedByMaintenance || (io.in.valid && cleanupBlockedBySource)
  io.retireAccepted := rename.io.retireAccepted
  io.retireMiss := rename.io.retireMiss
  io.retireReleaseMismatch := rename.io.retireReleaseMismatch
  io.retireUnsupported := rename.io.retireUnsupported
  io.commitAccepted := rename.io.commitAccepted
  io.localBlockCommitReady := localBlockCommitReady
  io.localBlockCommitAccepted := localBlockCommitFire
  io.localBlockCommitStidMatch := localBlockCommitStidMatch
  io.localBlockCommitBlockedByStid := io.localBlockCommitValid && !localBlockCommitStidMatch
  io.flushApplied := rename.io.flushApplied
  io.tAllocPhysTag := rename.io.tAllocPhysTag
  io.uAllocPhysTag := rename.io.uAllocPhysTag
  io.tMapQValidMask := rename.io.tMapQValidMask
  io.uMapQValidMask := rename.io.uMapQValidMask
  io.tRetiredMask := rename.io.tRetiredMask
  io.uRetiredMask := rename.io.uRetiredMask
  io.tReleasedMask := rename.io.tReleasedMask
  io.uReleasedMask := rename.io.uReleasedMask
  io.tFlushedMask := rename.io.tFlushedMask
  io.uFlushedMask := rename.io.uFlushedMask
  io.tUsedEntries := rename.io.tUsedEntries
  io.uUsedEntries := rename.io.uUsedEntries
  io.tUsedPhys := rename.io.tUsedPhys
  io.uUsedPhys := rename.io.uUsedPhys

  io.publisherFlushValid := publisher.io.flushValid
  io.publisherFlushBaseOnBid := publisher.io.flushBaseOnBid
  io.publisherFlushBid := publisher.io.flushBid
  io.publisherFlushRid := publisher.io.flushRid
  io.publisherFlushTSeq := publisher.io.flushTSeq
  io.publisherFlushUSeq := publisher.io.flushUSeq
  io.cleanupActive := cleanupActive
  io.cleanupBlockedBySource := cleanupBlockedBySource
  io.flushSourceRequired := publisher.io.sourceRequired
  io.flushSourceMatched := publisher.io.sourceMatched
  io.flushMissingSource := publisher.io.missingSource
  io.flushSourceMismatch := publisher.io.sourceMismatch
  io.selectedFlushSource := selector.io.source
  io.robSourceMatched := selector.io.robMatched
  io.lsuSourceMatched := selector.io.lsuMatched
  io.robSourceMismatched := selector.io.robMismatched
  io.lsuSourceMismatched := selector.io.lsuMismatched
  io.multipleSourcesMatched := selector.io.multipleMatched
  io.sourceConflict := selector.io.sourceConflict
  io.selectorSourceMissing := selector.io.sourceMissing
  io.selectedFromRob := selector.io.selectedFromRob
  io.selectedFromLsu := selector.io.selectedFromLsu
  io.flushTPrevApplied := publisher.io.tPrevApplied
  io.flushUPrevApplied := publisher.io.uPrevApplied
}
