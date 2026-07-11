package linxcore.bctrl

import chisel3._
import chisel3.util.Mux1H
import linxcore.common.{BlockMarkerRetireSource, BoundaryKind}

class BlockMarkerLifecycleIO(
    val entries: Int,
    val bidWidth: Int = BID.DefaultWidth,
    val pcWidth: Int = 64,
    val insnWidth: Int = 64,
    val lenWidth: Int = 4,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8)
    extends Bundle {
  val flushValid = Input(Bool())
  val flushStidValid = Input(Bool())
  val flushStid = Input(UInt(stidWidth.W))

  val retiredMarker = Input(new BlockMarkerRetireSource(
    entries = entries,
    blockBidWidth = bidWidth,
    pcWidth = pcWidth,
    insnWidth = insnWidth,
    lenWidth = lenWidth,
    peIdWidth = peIdWidth,
    stidWidth = stidWidth
  ))
  val markerBoundary = Input(Bool())
  val markerStop = Input(Bool())
  val markerPc = Input(UInt(pcWidth.W))
  val markerTarget = Input(UInt(pcWidth.W))
  val markerInsnLen = Input(UInt(4.W))
  val markerBoundaryKind = Input(BoundaryKind())
  val markerStid = Input(UInt(stidWidth.W))
  val markerAllocReady = Input(Bool())
  val markerAllocBid = Input(UInt(bidWidth.W))
  val branchTakenValid = Input(Bool())
  val branchTaken = Input(Bool())
  val scalarWorkPending = Input(Bool())
  val markerLifecycleConflict = Input(Bool())
  val retirePending = Input(Bool())

  val scalarRedirectValid = Input(Bool())
  val scalarRedirectStid = Input(UInt(stidWidth.W))
  val scalarBlockStartFire = Input(Bool())
  val scalarBlockStartStid = Input(UInt(stidWidth.W))
  val scalarBlockStartBid = Input(UInt(bidWidth.W))
  val robBlockLastValid = Input(Bool())
  val robBlockLastBid = Input(UInt(bidWidth.W))
  val robBlockLastStid = Input(UInt(stidWidth.W))
  val activeQueryStid = Input(UInt(stidWidth.W))

  val activeValid = Output(Bool())
  val activeBid = Output(UInt(bidWidth.W))
  val activeTarget = Output(UInt(pcWidth.W))
  val activeCond = Output(Bool())
  val activeUnconditionalRedirect = Output(Bool())

  val blockAllocOnlyValid = Output(Bool())
  val blockAllocOnlyStid = Output(UInt(stidWidth.W))
  val retiredMarkerReady = Output(Bool())
  val retiredMarkerFire = Output(Bool())
  val retiredMarkerBoundaryFire = Output(Bool())
  val retiredMarkerStopFire = Output(Bool())
  val markerReady = Output(Bool())
  val markerAllocFire = Output(Bool())
  val markerPreRetireFire = Output(Bool())
  val markerRedirectBoundaryFire = Output(Bool())
  val markerStopFire = Output(Bool())
  val scalarDoneValid = Output(Bool())
  val scalarDoneBid = Output(UInt(bidWidth.W))
  val scalarDoneStid = Output(UInt(stidWidth.W))
  val stopRedirectValid = Output(Bool())
  val stopRedirectPc = Output(UInt(pcWidth.W))
}

class BlockMarkerLifecycle(
    val entries: Int,
    val bidWidth: Int = BID.DefaultWidth,
    val pcWidth: Int = 64,
    val insnWidth: Int = 64,
    val lenWidth: Int = 4,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val stidCount: Int = 1)
    extends Module {
  require(entries > 1, "block marker lifecycle entries must be greater than one")
  require((entries & (entries - 1)) == 0, "block marker lifecycle entries must be a power of two")
  require(stidCount > 0, "block marker lifecycle must track at least one STID")
  require(BigInt(stidCount) <= (BigInt(1) << stidWidth), "STID count must fit stidWidth")

  val io = IO(new BlockMarkerLifecycleIO(entries, bidWidth, pcWidth, insnWidth, lenWidth, peIdWidth, stidWidth))

  val activeValid = RegInit(VecInit(Seq.fill(stidCount)(false.B)))
  val activeBid = RegInit(VecInit(Seq.fill(stidCount)(0.U(bidWidth.W))))
  val activeTarget = RegInit(VecInit(Seq.fill(stidCount)(0.U(pcWidth.W))))
  val activeCond = RegInit(VecInit(Seq.fill(stidCount)(false.B)))
  val activeUnconditionalRedirect = RegInit(VecInit(Seq.fill(stidCount)(false.B)))
  val activeClearsOnRobBlockLast = RegInit(VecInit(Seq.fill(stidCount)(false.B)))
  val markerOwnedDonePending = RegInit(false.B)
  val markerOwnedDoneBid = RegInit(0.U(bidWidth.W))
  val markerOwnedDoneStid = RegInit(0.U(stidWidth.W))

  private def matchesStid(stid: UInt): Vec[Bool] =
    VecInit((0 until stidCount).map(idx => stid === idx.U(stidWidth.W)))

  private def stidInRange(matches: Vec[Bool]): Bool =
    matches.asUInt.orR

  private def selectBool(values: Vec[Bool], matches: Vec[Bool]): Bool =
    Mux1H(matches, values)

  private def selectUInt(values: Vec[UInt], matches: Vec[Bool]): UInt =
    Mux1H(matches, values)

  private def clearLane(idx: Int): Unit = {
    activeValid(idx) := false.B
    activeBid(idx) := 0.U
    activeTarget(idx) := 0.U
    activeCond(idx) := false.B
    activeUnconditionalRedirect(idx) := false.B
    activeClearsOnRobBlockLast(idx) := false.B
  }

  private def installLane(idx: Int, bid: UInt, target: UInt, kind: BoundaryKind.Type): Unit = {
    activeValid(idx) := true.B
    activeBid(idx) := bid
    activeTarget(idx) := target
    activeCond(idx) := kind === BoundaryKind.Cond
    activeUnconditionalRedirect(idx) :=
      kind === BoundaryKind.Direct || kind === BoundaryKind.Call
    activeClearsOnRobBlockLast(idx) := false.B
  }

  private def installScalarLane(idx: Int, bid: UInt): Unit = {
    activeValid(idx) := true.B
    activeBid(idx) := bid
    activeTarget(idx) := 0.U
    activeCond(idx) := false.B
    activeUnconditionalRedirect(idx) := false.B
    activeClearsOnRobBlockLast(idx) := true.B
  }

  val markerStidMatch = matchesStid(io.markerStid)
  val markerStidInRange = stidInRange(markerStidMatch)
  val retiredStidMatch = matchesStid(io.retiredMarker.stid)
  val retiredStidInRange = stidInRange(retiredStidMatch)
  val queryStidMatch = matchesStid(io.activeQueryStid)
  val queryStidInRange = stidInRange(queryStidMatch)
  val scalarRedirectStidMatch = matchesStid(io.scalarRedirectStid)
  val scalarRedirectStidInRange = stidInRange(scalarRedirectStidMatch)
  val scalarBlockStartStidMatch = matchesStid(io.scalarBlockStartStid)
  val scalarBlockStartStidInRange = stidInRange(scalarBlockStartStidMatch)
  val flushStidMatch = matchesStid(io.flushStid)
  val scopedFlushMatchesPending = io.flushStidValid && io.flushStid === markerOwnedDoneStid
  val markerFlush = io.flushValid || (io.flushStidValid && io.flushStid === io.markerStid)
  val retiredFlush = io.flushValid || (io.flushStidValid && io.flushStid === io.retiredMarker.stid)

  val markerActiveValid = markerStidInRange && selectBool(activeValid, markerStidMatch)
  val markerActiveBid = selectUInt(activeBid, markerStidMatch)
  val markerActiveTarget = selectUInt(activeTarget, markerStidMatch)
  val markerActiveCond = markerStidInRange && selectBool(activeCond, markerStidMatch)
  val markerActiveUnconditionalRedirect =
    markerStidInRange && selectBool(activeUnconditionalRedirect, markerStidMatch)

  val retiredActiveValid = retiredStidInRange && selectBool(activeValid, retiredStidMatch)
  val retiredActiveBid = selectUInt(activeBid, retiredStidMatch)
  val retiredActiveTarget = selectUInt(activeTarget, retiredStidMatch)
  val retiredActiveCond = retiredStidInRange && selectBool(activeCond, retiredStidMatch)
  val retiredActiveUnconditionalRedirect =
    retiredStidInRange && selectBool(activeUnconditionalRedirect, retiredStidMatch)

  val queryActiveValid = queryStidInRange && selectBool(activeValid, queryStidMatch)
  val queryActiveBid = selectUInt(activeBid, queryStidMatch)
  val queryActiveTarget = selectUInt(activeTarget, queryStidMatch)
  val queryActiveCond = queryStidInRange && selectBool(activeCond, queryStidMatch)
  val queryActiveUnconditionalRedirect =
    queryStidInRange && selectBool(activeUnconditionalRedirect, queryStidMatch)

  val scalarRedirectActiveValid = scalarRedirectStidInRange && selectBool(activeValid, scalarRedirectStidMatch)
  val scalarRedirectActiveBid = selectUInt(activeBid, scalarRedirectStidMatch)

  val markerAllocBlockedByActiveSlot =
    markerActiveValid && (BID.slot(io.markerAllocBid, entries) === BID.slot(markerActiveBid, entries))
  val markerNeedsBranchDecision =
    io.markerBoundary && markerActiveValid && markerActiveCond && markerActiveTarget =/= 0.U &&
      (io.branchTakenValid || io.scalarWorkPending)
  val markerUnconditionalRedirect =
    io.markerBoundary && markerActiveValid && markerActiveUnconditionalRedirect && markerActiveTarget =/= 0.U
  val markerRedirectBoundary =
    markerUnconditionalRedirect ||
      (markerNeedsBranchDecision && io.branchTakenValid && io.branchTaken)
  val markerFallthroughBoundary =
    io.markerBoundary && !markerUnconditionalRedirect &&
      (!markerNeedsBranchDecision || (io.branchTakenValid && !io.branchTaken))

  io.blockAllocOnlyValid := markerStidInRange && markerFallthroughBoundary &&
    !io.markerLifecycleConflict && !markerFlush
  io.blockAllocOnlyStid := io.markerStid

  val markerPreRetireFire =
    markerStidInRange && markerFallthroughBoundary && !io.markerLifecycleConflict && !io.markerAllocReady &&
      markerAllocBlockedByActiveSlot && !io.retirePending
  val markerReady =
    markerStidInRange && !markerOwnedDonePending && !io.markerLifecycleConflict && !markerFlush &&
      (io.markerStop || markerRedirectBoundary || (markerFallthroughBoundary && io.markerAllocReady))
  val markerBoundaryFire = markerFallthroughBoundary && markerReady && io.markerAllocReady
  val markerBoundaryRedirectFire = markerRedirectBoundary && markerReady
  val markerStopFire = io.markerStop && markerReady

  val markerScalarDoneFire =
    markerActiveValid && (markerStopFire || markerBoundaryFire || markerBoundaryRedirectFire || markerPreRetireFire)
  val scalarRedirectScalarDoneFire = io.scalarRedirectValid && scalarRedirectActiveValid
  val robBlockLastClearsActive =
    VecInit((0 until stidCount).map(idx =>
      io.robBlockLastValid && activeValid(idx) && activeClearsOnRobBlockLast(idx) &&
        io.robBlockLastStid === idx.U(stidWidth.W) && io.robBlockLastBid === activeBid(idx)))

  val decodeMarkerActive = io.markerBoundary || io.markerStop
  val retiredBoundary = io.retiredMarker.valid && io.retiredMarker.isBoundary
  val retiredStop = io.retiredMarker.valid && io.retiredMarker.isStop
  val retiredNeedsBranchDecision =
    retiredBoundary && retiredActiveValid && retiredActiveCond && retiredActiveTarget =/= 0.U &&
      (io.branchTakenValid || io.scalarWorkPending)
  val retiredUnconditionalRedirect =
    retiredBoundary && retiredActiveValid && retiredActiveUnconditionalRedirect && retiredActiveTarget =/= 0.U
  val retiredRedirectBoundary =
    retiredUnconditionalRedirect ||
      (retiredNeedsBranchDecision && io.branchTakenValid && io.branchTaken)
  val retiredFallthroughBoundary =
    retiredBoundary && !retiredUnconditionalRedirect &&
      (!retiredNeedsBranchDecision || (io.branchTakenValid && !io.branchTaken))
  val retiredMarkerOwnsBlockLast =
    io.retiredMarker.valid && io.retiredMarker.isLast && io.retiredMarker.blockBidValid &&
      io.robBlockLastValid && io.retiredMarker.stid === io.robBlockLastStid &&
      io.retiredMarker.blockBid === io.robBlockLastBid
  val retiredLifecycleIdle =
    !markerOwnedDonePending && !decodeMarkerActive && !retiredFlush && !scalarRedirectScalarDoneFire &&
      (!io.robBlockLastValid || retiredMarkerOwnsBlockLast)
  val retiredMarkerConflict = io.markerLifecycleConflict && !retiredMarkerOwnsBlockLast
  val retiredReady =
    retiredLifecycleIdle && !retiredMarkerConflict &&
      (!io.retiredMarker.valid || (retiredStidInRange && (retiredStop || retiredRedirectBoundary ||
        (retiredFallthroughBoundary && io.retiredMarker.blockBidValid))
      ))
  val retiredBoundaryFire = retiredFallthroughBoundary && retiredReady && io.retiredMarker.blockBidValid
  val retiredBoundaryRedirectFire = retiredRedirectBoundary && retiredReady
  val retiredStopFire = retiredStop && retiredReady
  val retiredMarkerFire = retiredBoundaryFire || retiredBoundaryRedirectFire || retiredStopFire
  val retiredScalarDoneFire =
    retiredActiveValid && (retiredStopFire || retiredBoundaryFire || retiredBoundaryRedirectFire)
  val retiredRedirectOwnsMarkerOnlyBlock =
    retiredBoundaryRedirectFire && io.retiredMarker.blockBidValid &&
      (!retiredActiveValid || io.retiredMarker.blockBid =/= retiredActiveBid)
  val liveScalarDoneFire =
    markerScalarDoneFire || retiredScalarDoneFire || scalarRedirectScalarDoneFire || io.robBlockLastValid
  val markerOwnedDoneFire = markerOwnedDonePending && !liveScalarDoneFire

  val markerSequentialPc = io.markerPc + io.markerInsnLen.pad(pcWidth)(pcWidth - 1, 0)
  val retiredSequentialPc = io.retiredMarker.pc + io.retiredMarker.len.pad(pcWidth)(pcWidth - 1, 0)
  val markerRedirectValid =
    (markerStopFire || markerBoundaryRedirectFire) && markerActiveValid && markerActiveTarget =/= 0.U &&
      markerActiveTarget =/= markerSequentialPc
  val retiredRedirectValid =
    (retiredStopFire || retiredBoundaryRedirectFire) && retiredActiveValid && retiredActiveTarget =/= 0.U &&
      retiredActiveTarget =/= retiredSequentialPc

  io.activeValid := queryActiveValid
  io.activeBid := queryActiveBid
  io.activeTarget := queryActiveTarget
  io.activeCond := queryActiveCond
  io.activeUnconditionalRedirect := queryActiveUnconditionalRedirect
  io.markerAllocFire := markerBoundaryFire
  io.retiredMarkerReady := retiredReady
  io.retiredMarkerFire := retiredMarkerFire
  io.retiredMarkerBoundaryFire := retiredBoundaryFire
  io.retiredMarkerStopFire := retiredStopFire
  io.markerReady := markerReady
  io.markerPreRetireFire := markerPreRetireFire
  io.markerRedirectBoundaryFire := markerBoundaryRedirectFire
  io.markerStopFire := markerStopFire
  io.scalarDoneValid :=
    markerScalarDoneFire || retiredScalarDoneFire || scalarRedirectScalarDoneFire ||
      io.robBlockLastValid || markerOwnedDoneFire
  io.scalarDoneBid :=
    Mux(markerScalarDoneFire, markerActiveBid,
      Mux(retiredScalarDoneFire, retiredActiveBid,
        Mux(scalarRedirectScalarDoneFire, scalarRedirectActiveBid,
          Mux(io.robBlockLastValid, io.robBlockLastBid, markerOwnedDoneBid))))
  io.scalarDoneStid :=
    Mux(markerScalarDoneFire, io.markerStid,
      Mux(retiredScalarDoneFire, io.retiredMarker.stid,
        Mux(scalarRedirectScalarDoneFire, io.scalarRedirectStid,
          Mux(io.robBlockLastValid, io.robBlockLastStid, markerOwnedDoneStid))))
  io.stopRedirectValid := markerRedirectValid || retiredRedirectValid
  io.stopRedirectPc := Mux(retiredRedirectValid, retiredActiveTarget, markerActiveTarget)

  when(io.flushValid || scopedFlushMatchesPending) {
    markerOwnedDonePending := false.B
    markerOwnedDoneBid := 0.U
    markerOwnedDoneStid := 0.U
  }.elsewhen(markerOwnedDoneFire) {
    markerOwnedDonePending := false.B
    markerOwnedDoneBid := 0.U
    markerOwnedDoneStid := 0.U
  }.elsewhen(retiredRedirectOwnsMarkerOnlyBlock) {
    markerOwnedDonePending := true.B
    markerOwnedDoneBid := io.retiredMarker.blockBid
    markerOwnedDoneStid := io.retiredMarker.stid
  }

  when(io.flushValid) {
    for (idx <- 0 until stidCount) {
      clearLane(idx)
    }
  }.elsewhen(io.flushStidValid) {
    for (idx <- 0 until stidCount) {
      when(flushStidMatch(idx)) {
        clearLane(idx)
      }
    }
  }.elsewhen(scalarRedirectScalarDoneFire) {
    for (idx <- 0 until stidCount) {
      when(scalarRedirectStidMatch(idx)) {
        clearLane(idx)
      }
    }
  }.elsewhen(markerBoundaryFire) {
    for (idx <- 0 until stidCount) {
      when(markerStidMatch(idx)) {
        installLane(idx, io.markerAllocBid, io.markerTarget, io.markerBoundaryKind)
      }
    }
  }.elsewhen(retiredBoundaryFire) {
    for (idx <- 0 until stidCount) {
      when(retiredStidMatch(idx)) {
        installLane(idx, io.retiredMarker.blockBid, io.retiredMarker.boundaryTarget, io.retiredMarker.boundaryKind)
      }
    }
  }.elsewhen(io.scalarBlockStartFire && scalarBlockStartStidInRange) {
    for (idx <- 0 until stidCount) {
      when(scalarBlockStartStidMatch(idx)) {
        installScalarLane(idx, io.scalarBlockStartBid)
      }
    }
  }.elsewhen(
    markerStopFire || markerBoundaryRedirectFire || retiredStopFire || retiredBoundaryRedirectFire ||
      robBlockLastClearsActive.asUInt.orR) {
    for (idx <- 0 until stidCount) {
      when(
        (markerStidMatch(idx) && (markerStopFire || markerBoundaryRedirectFire)) ||
          (retiredStidMatch(idx) && (retiredStopFire || retiredBoundaryRedirectFire)) ||
          robBlockLastClearsActive(idx)) {
        clearLane(idx)
      }
    }
  }
}
