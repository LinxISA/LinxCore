package linxcore.rename

import chisel3._
import chisel3.util.log2Ceil

import linxcore.bctrl.BID
import linxcore.common._
import linxcore.recovery.RecoveryCleanupIntent
import linxcore.rob.ROBID

class TULinkLocalBankArrayIO(
    val p: InterfaceParams = InterfaceParams(),
    val localRegsT: Int = 32,
    val localRegsU: Int = 32,
    val mapQDepth: Int = 32,
    val bidWidth: Int = BID.DefaultWidth,
    val peCount: Int = 1,
    val stidCount: Int = 1,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8)
    extends Bundle {
  private val countWidth = log2Ceil(Seq(localRegsT, localRegsU, mapQDepth).max + 1)

  val activePeId = Input(UInt(peIdWidth.W))
  val activeStid = Input(UInt(stidWidth.W))
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
  val localBlockCommitBlockedByBankReady = Output(Bool())
  val flushApplied = Output(Bool())

  val activePeInRange = Output(Bool())
  val activeStidInRange = Output(Bool())
  val activeBankValid = Output(Bool())
  val activePeOH = Output(UInt(peCount.W))
  val activeStidOH = Output(UInt(stidCount.W))
  val localBlockCommitFanoutStidInRange = Output(Bool())
  val localBlockCommitFanoutBlockedByStidRange = Output(Bool())
  val localBlockCommitFanoutBlockedByBankReady = Output(Bool())
  val localBlockCommitFanoutTargetPeMask = Output(UInt(peCount.W))
  val localBlockCommitFanoutReadyPeMask = Output(UInt(peCount.W))

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
  val bankTUsedEntries = Output(Vec(peCount, Vec(stidCount, UInt(countWidth.W))))
  val bankUUsedEntries = Output(Vec(peCount, Vec(stidCount, UInt(countWidth.W))))

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

class TULinkLocalBankArray(
    val p: InterfaceParams = InterfaceParams(),
    val localRegsT: Int = 32,
    val localRegsU: Int = 32,
    val mapQDepth: Int = 32,
    val bidWidth: Int = BID.DefaultWidth,
    val peCount: Int = 1,
    val stidCount: Int = 1,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8)
    extends Module {
  require(peCount > 0, "T/U local bank array must contain at least one scalar PE")
  require(stidCount > 0, "T/U local bank array must contain at least one scalar STID")
  require(mapQDepth > 1 && (mapQDepth & (mapQDepth - 1)) == 0, "T/U mapQ depth must be a power of two")
  require(peIdWidth > 0, "PE id width must be positive")
  require(stidWidth > 0, "STID width must be positive")
  require(BigInt(peCount) <= (BigInt(1) << peIdWidth), "PE count must fit peIdWidth")
  require(BigInt(stidCount) <= (BigInt(1) << stidWidth), "STID count must fit stidWidth")

  val io = IO(new TULinkLocalBankArrayIO(
    p,
    localRegsT,
    localRegsU,
    mapQDepth,
    bidWidth,
    peCount,
    stidCount,
    peIdWidth,
    stidWidth,
    tidWidth
  ))

  private def zeroRobId(entries: Int): ROBID =
    0.U.asTypeOf(new ROBID(entries))

  private def zeroResolvedOperand: TULinkResolvedOperand =
    0.U.asTypeOf(new TULinkResolvedOperand(p, mapQDepth))

  private def zeroResolvedDestination: TULinkResolvedDestination =
    0.U.asTypeOf(new TULinkResolvedDestination(p, mapQDepth))

  private def zeroSource: TULinkFlushSequenceSource =
    0.U.asTypeOf(new TULinkFlushSequenceSource(p, mapQDepth, stidWidth))

  val peMatches = VecInit((0 until peCount).map(pe => io.activePeId === pe.U(peIdWidth.W)))
  val stidMatches = VecInit((0 until stidCount).map(stid => io.activeStid === stid.U(stidWidth.W)))
  val activePeInRange = peMatches.asUInt.orR
  val activeStidInRange = stidMatches.asUInt.orR
  val activeBankValid = activePeInRange && activeStidInRange

  val banks = Seq.tabulate(peCount, stidCount) { (pe, stid) =>
    Module(new TULinkRecoveryCleanupPath(
      p = p,
      localRegsT = localRegsT,
      localRegsU = localRegsU,
      mapQDepth = mapQDepth,
      bidWidth = bidWidth,
      peIdWidth = peIdWidth,
      stidWidth = stidWidth,
      tidWidth = tidWidth,
      localStid = stid
    ))
  }

  val fanout = Module(new TULinkLocalBlockCommitFanout(
    p = p,
    peCount = peCount,
    stidCount = stidCount,
    stidWidth = stidWidth
  ))

  fanout.io.inValid := io.localBlockCommitValid
  fanout.io.inBid := io.localBlockCommitBid
  fanout.io.inStid := io.localBlockCommitStid

  for (pe <- 0 until peCount) {
    for (stid <- 0 until stidCount) {
      val selected = peMatches(pe) && stidMatches(stid)
      val bank = banks(pe)(stid)

      bank.io.in := io.in
      bank.io.renameValid := io.renameValid && selected
      bank.io.retireValid := io.retireValid && selected
      bank.io.retireKind := io.retireKind
      bank.io.retireSeq := io.retireSeq
      bank.io.retireDealloc := io.retireDealloc
      bank.io.commitValid := io.commitValid && selected
      bank.io.commitBid := io.commitBid
      bank.io.localBlockCommitValid := fanout.io.bankValid(pe)(stid)
      bank.io.localBlockCommitBid := fanout.io.bankBid(pe)(stid)
      bank.io.localBlockCommitStid := fanout.io.bankStid(pe)(stid)
      bank.io.cleanup := io.cleanup
      bank.io.robSource := io.robSource
      bank.io.lsuSource := io.lsuSource

      fanout.io.bankReady(pe)(stid) := bank.io.localBlockCommitReady
      io.bankTUsedEntries(pe)(stid) := bank.io.tUsedEntries
      io.bankUUsedEntries(pe)(stid) := bank.io.uUsedEntries
    }
  }

  val selectedReady = WireDefault(false.B)
  val selectedAccepted = WireDefault(false.B)
  val selectedSrc = Wire(Vec(3, new TULinkResolvedOperand(p, mapQDepth)))
  val selectedDst = Wire(new TULinkResolvedDestination(p, mapQDepth))
  val selectedTSeq = Wire(new ROBID(mapQDepth))
  val selectedUSeq = Wire(new ROBID(mapQDepth))
  val selectedTDeallocSeq = Wire(new ROBID(mapQDepth))
  val selectedUDeallocSeq = Wire(new ROBID(mapQDepth))
  val selectedNeedsTAlloc = WireDefault(false.B)
  val selectedNeedsUAlloc = WireDefault(false.B)
  val selectedBlockedByTAlloc = WireDefault(false.B)
  val selectedBlockedByUAlloc = WireDefault(false.B)
  val selectedSourceUnderflowMask = WireDefault(0.U(3.W))
  val selectedBlockedByMaintenance = WireDefault(false.B)
  val selectedRetireAccepted = WireDefault(false.B)
  val selectedRetireMiss = WireDefault(false.B)
  val selectedRetireReleaseMismatch = WireDefault(false.B)
  val selectedRetireUnsupported = WireDefault(false.B)
  val selectedCommitAccepted = WireDefault(false.B)
  val selectedFlushApplied = WireDefault(false.B)
  val selectedTAllocPhysTag = WireDefault(0.U(p.physRegWidth.W))
  val selectedUAllocPhysTag = WireDefault(0.U(p.physRegWidth.W))
  val selectedTMapQValidMask = WireDefault(0.U(mapQDepth.W))
  val selectedUMapQValidMask = WireDefault(0.U(mapQDepth.W))
  val selectedTRetiredMask = WireDefault(0.U(mapQDepth.W))
  val selectedURetiredMask = WireDefault(0.U(mapQDepth.W))
  val selectedTReleasedMask = WireDefault(0.U(mapQDepth.W))
  val selectedUReleasedMask = WireDefault(0.U(mapQDepth.W))
  val selectedTFlushedMask = WireDefault(0.U(mapQDepth.W))
  val selectedUFlushedMask = WireDefault(0.U(mapQDepth.W))
  val selectedTUsedEntries = WireDefault(0.U(log2Ceil(Seq(localRegsT, localRegsU, mapQDepth).max + 1).W))
  val selectedUUsedEntries = WireDefault(0.U(log2Ceil(Seq(localRegsT, localRegsU, mapQDepth).max + 1).W))
  val selectedTUsedPhys = WireDefault(0.U(log2Ceil(Seq(localRegsT, localRegsU, mapQDepth).max + 1).W))
  val selectedUUsedPhys = WireDefault(0.U(log2Ceil(Seq(localRegsT, localRegsU, mapQDepth).max + 1).W))
  val selectedPublisherFlushValid = WireDefault(false.B)
  val selectedPublisherFlushBaseOnBid = WireDefault(false.B)
  val selectedPublisherFlushBid = Wire(new ROBID(p.robEntries))
  val selectedPublisherFlushRid = Wire(new ROBID(p.robEntries))
  val selectedPublisherFlushTSeq = Wire(new ROBID(mapQDepth))
  val selectedPublisherFlushUSeq = Wire(new ROBID(mapQDepth))
  val selectedCleanupActive = WireDefault(false.B)
  val selectedCleanupBlockedBySource = WireDefault(false.B)
  val selectedFlushSourceRequired = WireDefault(false.B)
  val selectedFlushSourceMatched = WireDefault(false.B)
  val selectedFlushMissingSource = WireDefault(false.B)
  val selectedFlushSourceMismatch = WireDefault(false.B)
  val selectedFlushSource = Wire(new TULinkFlushSequenceSource(p, mapQDepth, stidWidth))
  val selectedRobSourceMatched = WireDefault(false.B)
  val selectedLsuSourceMatched = WireDefault(false.B)
  val selectedRobSourceMismatched = WireDefault(false.B)
  val selectedLsuSourceMismatched = WireDefault(false.B)
  val selectedMultipleSourcesMatched = WireDefault(false.B)
  val selectedSourceConflict = WireDefault(false.B)
  val selectedSelectorSourceMissing = WireDefault(false.B)
  val selectedFromRob = WireDefault(false.B)
  val selectedFromLsu = WireDefault(false.B)
  val selectedFlushTPrevApplied = WireDefault(false.B)
  val selectedFlushUPrevApplied = WireDefault(false.B)

  selectedSrc := VecInit(Seq.fill(3)(zeroResolvedOperand))
  selectedDst := zeroResolvedDestination
  selectedTSeq := ROBID.zero(mapQDepth)
  selectedUSeq := ROBID.zero(mapQDepth)
  selectedTDeallocSeq := ROBID.zero(mapQDepth)
  selectedUDeallocSeq := ROBID.zero(mapQDepth)
  selectedPublisherFlushBid := zeroRobId(p.robEntries)
  selectedPublisherFlushRid := zeroRobId(p.robEntries)
  selectedPublisherFlushTSeq := ROBID.zero(mapQDepth)
  selectedPublisherFlushUSeq := ROBID.zero(mapQDepth)
  selectedFlushSource := zeroSource

  for (pe <- 0 until peCount) {
    for (stid <- 0 until stidCount) {
      when(peMatches(pe) && stidMatches(stid)) {
        val bank = banks(pe)(stid)
        selectedReady := bank.io.ready
        selectedAccepted := bank.io.accepted
        selectedSrc := bank.io.src
        selectedDst := bank.io.dst
        selectedTSeq := bank.io.tSeq
        selectedUSeq := bank.io.uSeq
        selectedTDeallocSeq := bank.io.tDeallocSeq
        selectedUDeallocSeq := bank.io.uDeallocSeq
        selectedNeedsTAlloc := bank.io.needsTAlloc
        selectedNeedsUAlloc := bank.io.needsUAlloc
        selectedBlockedByTAlloc := bank.io.blockedByTAlloc
        selectedBlockedByUAlloc := bank.io.blockedByUAlloc
        selectedSourceUnderflowMask := bank.io.sourceUnderflowMask
        selectedBlockedByMaintenance := bank.io.blockedByMaintenance
        selectedRetireAccepted := bank.io.retireAccepted
        selectedRetireMiss := bank.io.retireMiss
        selectedRetireReleaseMismatch := bank.io.retireReleaseMismatch
        selectedRetireUnsupported := bank.io.retireUnsupported
        selectedCommitAccepted := bank.io.commitAccepted
        selectedFlushApplied := bank.io.flushApplied
        selectedTAllocPhysTag := bank.io.tAllocPhysTag
        selectedUAllocPhysTag := bank.io.uAllocPhysTag
        selectedTMapQValidMask := bank.io.tMapQValidMask
        selectedUMapQValidMask := bank.io.uMapQValidMask
        selectedTRetiredMask := bank.io.tRetiredMask
        selectedURetiredMask := bank.io.uRetiredMask
        selectedTReleasedMask := bank.io.tReleasedMask
        selectedUReleasedMask := bank.io.uReleasedMask
        selectedTFlushedMask := bank.io.tFlushedMask
        selectedUFlushedMask := bank.io.uFlushedMask
        selectedTUsedEntries := bank.io.tUsedEntries
        selectedUUsedEntries := bank.io.uUsedEntries
        selectedTUsedPhys := bank.io.tUsedPhys
        selectedUUsedPhys := bank.io.uUsedPhys
        selectedPublisherFlushValid := bank.io.publisherFlushValid
        selectedPublisherFlushBaseOnBid := bank.io.publisherFlushBaseOnBid
        selectedPublisherFlushBid := bank.io.publisherFlushBid
        selectedPublisherFlushRid := bank.io.publisherFlushRid
        selectedPublisherFlushTSeq := bank.io.publisherFlushTSeq
        selectedPublisherFlushUSeq := bank.io.publisherFlushUSeq
        selectedCleanupActive := bank.io.cleanupActive
        selectedCleanupBlockedBySource := bank.io.cleanupBlockedBySource
        selectedFlushSourceRequired := bank.io.flushSourceRequired
        selectedFlushSourceMatched := bank.io.flushSourceMatched
        selectedFlushMissingSource := bank.io.flushMissingSource
        selectedFlushSourceMismatch := bank.io.flushSourceMismatch
        selectedFlushSource := bank.io.selectedFlushSource
        selectedRobSourceMatched := bank.io.robSourceMatched
        selectedLsuSourceMatched := bank.io.lsuSourceMatched
        selectedRobSourceMismatched := bank.io.robSourceMismatched
        selectedLsuSourceMismatched := bank.io.lsuSourceMismatched
        selectedMultipleSourcesMatched := bank.io.multipleSourcesMatched
        selectedSourceConflict := bank.io.sourceConflict
        selectedSelectorSourceMissing := bank.io.selectorSourceMissing
        selectedFromRob := bank.io.selectedFromRob
        selectedFromLsu := bank.io.selectedFromLsu
        selectedFlushTPrevApplied := bank.io.flushTPrevApplied
        selectedFlushUPrevApplied := bank.io.flushUPrevApplied
      }
    }
  }

  io.ready := activeBankValid && selectedReady
  io.accepted := selectedAccepted
  io.src := selectedSrc
  io.dst := selectedDst
  io.tSeq := selectedTSeq
  io.uSeq := selectedUSeq
  io.tDeallocSeq := selectedTDeallocSeq
  io.uDeallocSeq := selectedUDeallocSeq
  io.needsTAlloc := selectedNeedsTAlloc
  io.needsUAlloc := selectedNeedsUAlloc
  io.blockedByTAlloc := selectedBlockedByTAlloc
  io.blockedByUAlloc := selectedBlockedByUAlloc
  io.sourceUnderflowMask := selectedSourceUnderflowMask
  io.blockedByMaintenance := selectedBlockedByMaintenance ||
    (io.in.valid && io.renameValid && !activeBankValid)
  io.retireAccepted := selectedRetireAccepted
  io.retireMiss := selectedRetireMiss
  io.retireReleaseMismatch := selectedRetireReleaseMismatch
  io.retireUnsupported := selectedRetireUnsupported
  io.commitAccepted := selectedCommitAccepted
  io.localBlockCommitReady := fanout.io.ready
  io.localBlockCommitAccepted := fanout.io.accepted
  io.localBlockCommitStidMatch := fanout.io.stidInRange
  io.localBlockCommitBlockedByStid := fanout.io.blockedByStidRange
  io.localBlockCommitBlockedByBankReady := fanout.io.blockedByBankReady
  io.flushApplied := selectedFlushApplied
  io.activePeInRange := activePeInRange
  io.activeStidInRange := activeStidInRange
  io.activeBankValid := activeBankValid
  io.activePeOH := peMatches.asUInt
  io.activeStidOH := stidMatches.asUInt
  io.localBlockCommitFanoutStidInRange := fanout.io.stidInRange
  io.localBlockCommitFanoutBlockedByStidRange := fanout.io.blockedByStidRange
  io.localBlockCommitFanoutBlockedByBankReady := fanout.io.blockedByBankReady
  io.localBlockCommitFanoutTargetPeMask := fanout.io.targetPeMask
  io.localBlockCommitFanoutReadyPeMask := fanout.io.selectedPeReadyMask
  io.tAllocPhysTag := selectedTAllocPhysTag
  io.uAllocPhysTag := selectedUAllocPhysTag
  io.tMapQValidMask := selectedTMapQValidMask
  io.uMapQValidMask := selectedUMapQValidMask
  io.tRetiredMask := selectedTRetiredMask
  io.uRetiredMask := selectedURetiredMask
  io.tReleasedMask := selectedTReleasedMask
  io.uReleasedMask := selectedUReleasedMask
  io.tFlushedMask := selectedTFlushedMask
  io.uFlushedMask := selectedUFlushedMask
  io.tUsedEntries := selectedTUsedEntries
  io.uUsedEntries := selectedUUsedEntries
  io.tUsedPhys := selectedTUsedPhys
  io.uUsedPhys := selectedUUsedPhys
  io.publisherFlushValid := selectedPublisherFlushValid
  io.publisherFlushBaseOnBid := selectedPublisherFlushBaseOnBid
  io.publisherFlushBid := selectedPublisherFlushBid
  io.publisherFlushRid := selectedPublisherFlushRid
  io.publisherFlushTSeq := selectedPublisherFlushTSeq
  io.publisherFlushUSeq := selectedPublisherFlushUSeq
  io.cleanupActive := selectedCleanupActive
  io.cleanupBlockedBySource := selectedCleanupBlockedBySource
  io.flushSourceRequired := selectedFlushSourceRequired
  io.flushSourceMatched := selectedFlushSourceMatched
  io.flushMissingSource := selectedFlushMissingSource
  io.flushSourceMismatch := selectedFlushSourceMismatch
  io.selectedFlushSource := selectedFlushSource
  io.robSourceMatched := selectedRobSourceMatched
  io.lsuSourceMatched := selectedLsuSourceMatched
  io.robSourceMismatched := selectedRobSourceMismatched
  io.lsuSourceMismatched := selectedLsuSourceMismatched
  io.multipleSourcesMatched := selectedMultipleSourcesMatched
  io.sourceConflict := selectedSourceConflict
  io.selectorSourceMissing := selectedSelectorSourceMissing
  io.selectedFromRob := selectedFromRob
  io.selectedFromLsu := selectedFromLsu
  io.flushTPrevApplied := selectedFlushTPrevApplied
  io.flushUPrevApplied := selectedFlushUPrevApplied
}
