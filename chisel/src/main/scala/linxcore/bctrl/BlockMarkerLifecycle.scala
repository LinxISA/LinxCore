package linxcore.bctrl

import chisel3._
import linxcore.common.BoundaryKind

class BlockMarkerLifecycleIO(val entries: Int, val bidWidth: Int = BID.DefaultWidth, val pcWidth: Int = 64)
    extends Bundle {
  val flushValid = Input(Bool())

  val markerBoundary = Input(Bool())
  val markerStop = Input(Bool())
  val markerPc = Input(UInt(pcWidth.W))
  val markerTarget = Input(UInt(pcWidth.W))
  val markerInsnLen = Input(UInt(4.W))
  val markerBoundaryKind = Input(BoundaryKind())
  val markerAllocReady = Input(Bool())
  val markerAllocBid = Input(UInt(bidWidth.W))
  val branchTakenValid = Input(Bool())
  val branchTaken = Input(Bool())
  val scalarWorkPending = Input(Bool())
  val markerLifecycleConflict = Input(Bool())
  val retirePending = Input(Bool())

  val scalarRedirectValid = Input(Bool())
  val scalarBlockStartFire = Input(Bool())
  val scalarBlockStartBid = Input(UInt(bidWidth.W))
  val robBlockLastValid = Input(Bool())
  val robBlockLastBid = Input(UInt(bidWidth.W))

  val activeValid = Output(Bool())
  val activeBid = Output(UInt(bidWidth.W))
  val activeTarget = Output(UInt(pcWidth.W))
  val activeCond = Output(Bool())
  val activeUnconditionalRedirect = Output(Bool())

  val blockAllocOnlyValid = Output(Bool())
  val markerReady = Output(Bool())
  val markerAllocFire = Output(Bool())
  val markerPreRetireFire = Output(Bool())
  val markerRedirectBoundaryFire = Output(Bool())
  val markerStopFire = Output(Bool())
  val scalarDoneValid = Output(Bool())
  val scalarDoneBid = Output(UInt(bidWidth.W))
  val stopRedirectValid = Output(Bool())
  val stopRedirectPc = Output(UInt(pcWidth.W))
}

class BlockMarkerLifecycle(val entries: Int, val bidWidth: Int = BID.DefaultWidth, val pcWidth: Int = 64)
    extends Module {
  require(entries > 1, "block marker lifecycle entries must be greater than one")
  require((entries & (entries - 1)) == 0, "block marker lifecycle entries must be a power of two")

  val io = IO(new BlockMarkerLifecycleIO(entries, bidWidth, pcWidth))

  val activeValid = RegInit(false.B)
  val activeBid = RegInit(0.U(bidWidth.W))
  val activeTarget = RegInit(0.U(pcWidth.W))
  val activeCond = RegInit(false.B)
  val activeUnconditionalRedirect = RegInit(false.B)

  val markerAllocBlockedByActiveSlot =
    activeValid && (BID.slot(io.markerAllocBid, entries) === BID.slot(activeBid, entries))
  val markerNeedsBranchDecision =
    io.markerBoundary && activeValid && activeCond && activeTarget =/= 0.U &&
      (io.branchTakenValid || io.scalarWorkPending)
  val markerUnconditionalRedirect =
    io.markerBoundary && activeValid && activeUnconditionalRedirect && activeTarget =/= 0.U
  val markerRedirectBoundary =
    markerUnconditionalRedirect ||
      (markerNeedsBranchDecision && io.branchTakenValid && io.branchTaken)
  val markerFallthroughBoundary =
    io.markerBoundary && !markerUnconditionalRedirect &&
      (!markerNeedsBranchDecision || (io.branchTakenValid && !io.branchTaken))

  io.blockAllocOnlyValid := markerFallthroughBoundary && !io.markerLifecycleConflict

  val markerPreRetireFire =
    markerFallthroughBoundary && !io.markerLifecycleConflict && !io.markerAllocReady &&
      markerAllocBlockedByActiveSlot && !io.retirePending
  val markerReady =
    !io.markerLifecycleConflict &&
      (io.markerStop || markerRedirectBoundary || (markerFallthroughBoundary && io.markerAllocReady))
  val markerBoundaryFire = markerFallthroughBoundary && markerReady && io.markerAllocReady
  val markerBoundaryRedirectFire = markerRedirectBoundary && markerReady
  val markerStopFire = io.markerStop && markerReady

  val markerScalarDoneFire =
    activeValid && (markerStopFire || markerBoundaryFire || markerBoundaryRedirectFire || markerPreRetireFire)
  val scalarRedirectScalarDoneFire = io.scalarRedirectValid && activeValid
  val robBlockLastClearsActive =
    io.robBlockLastValid && activeValid && io.robBlockLastBid === activeBid

  io.activeValid := activeValid
  io.activeBid := activeBid
  io.activeTarget := activeTarget
  io.activeCond := activeCond
  io.activeUnconditionalRedirect := activeUnconditionalRedirect
  io.markerAllocFire := markerBoundaryFire
  io.markerReady := markerReady
  io.markerPreRetireFire := markerPreRetireFire
  io.markerRedirectBoundaryFire := markerBoundaryRedirectFire
  io.markerStopFire := markerStopFire
  io.scalarDoneValid := markerScalarDoneFire || scalarRedirectScalarDoneFire || io.robBlockLastValid
  io.scalarDoneBid :=
    Mux(markerScalarDoneFire, activeBid, Mux(scalarRedirectScalarDoneFire, activeBid, io.robBlockLastBid))
  io.stopRedirectValid :=
    (markerStopFire || markerBoundaryRedirectFire) && activeValid && activeTarget =/= 0.U &&
      activeTarget =/= (io.markerPc + io.markerInsnLen)
  io.stopRedirectPc := activeTarget

  when(io.flushValid) {
    activeValid := false.B
    activeBid := 0.U
    activeTarget := 0.U
    activeCond := false.B
    activeUnconditionalRedirect := false.B
  }.elsewhen(scalarRedirectScalarDoneFire) {
    activeValid := false.B
    activeBid := 0.U
    activeTarget := 0.U
    activeCond := false.B
    activeUnconditionalRedirect := false.B
  }.elsewhen(markerBoundaryFire) {
    activeValid := true.B
    activeBid := io.markerAllocBid
    activeTarget := io.markerTarget
    activeCond := io.markerBoundaryKind === BoundaryKind.Cond
    activeUnconditionalRedirect :=
      io.markerBoundaryKind === BoundaryKind.Direct || io.markerBoundaryKind === BoundaryKind.Call
  }.elsewhen(io.scalarBlockStartFire) {
    activeValid := true.B
    activeBid := io.scalarBlockStartBid
    activeTarget := 0.U
    activeCond := false.B
    activeUnconditionalRedirect := false.B
  }.elsewhen(markerStopFire || markerBoundaryRedirectFire || robBlockLastClearsActive) {
    activeValid := false.B
    activeBid := 0.U
    activeTarget := 0.U
    activeCond := false.B
    activeUnconditionalRedirect := false.B
  }
}
