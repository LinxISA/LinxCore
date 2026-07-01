package linxcore.bctrl

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object BlockMarkerLifecycleReference {
  object Kind extends Enumeration {
    val Fall, Cond, Call, Direct = Value
  }

  final case class Input(
      markerBoundary: Boolean = false,
      markerStop: Boolean = false,
      markerPc: BigInt = 0,
      markerTarget: BigInt = 0,
      markerInsnLen: BigInt = 2,
      markerKind: Kind.Value = Kind.Fall,
      markerAllocReady: Boolean = true,
      markerAllocBid: BigInt = 0,
      branchTakenValid: Boolean = false,
      branchTaken: Boolean = false,
      scalarWorkPending: Boolean = true,
      markerLifecycleConflict: Boolean = false,
      retirePending: Boolean = false,
      retiredMarkerValid: Boolean = false,
      retiredMarkerBoundary: Boolean = false,
      retiredMarkerStop: Boolean = false,
      retiredMarkerPc: BigInt = 0,
      retiredMarkerTarget: BigInt = 0,
      retiredMarkerInsnLen: BigInt = 2,
      retiredMarkerKind: Kind.Value = Kind.Fall,
      retiredMarkerBlockBidValid: Boolean = false,
      retiredMarkerBlockBid: BigInt = 0,
      scalarRedirectValid: Boolean = false,
      scalarBlockStartFire: Boolean = false,
      scalarBlockStartBid: BigInt = 0,
      robBlockLastValid: Boolean = false,
      robBlockLastBid: BigInt = 0,
      flushValid: Boolean = false)

  final case class Output(
      activeBid: Option[BigInt],
      blockAllocOnlyValid: Boolean,
      retiredMarkerReady: Boolean,
      retiredMarkerFire: Boolean,
      retiredMarkerBoundaryFire: Boolean,
      retiredMarkerStopFire: Boolean,
      markerAllocFire: Boolean,
      markerPreRetireFire: Boolean,
      scalarDoneBid: Option[BigInt],
      stopRedirectPc: Option[BigInt])

  final class State(entries: Int) {
    private var activeValid = false
    private var activeBid = BigInt(0)
    private var activeTarget = BigInt(0)
    private var activeCond = false
    private var activeUnconditionalRedirect = false

    private def sameSlot(a: BigInt, b: BigInt): Boolean =
      (a % entries) == (b % entries)

    def step(in: Input): Output = {
      val markerAllocBlockedByActiveSlot = activeValid && sameSlot(in.markerAllocBid, activeBid)
      val markerNeedsBranchDecision =
        in.markerBoundary && activeValid && activeCond && activeTarget != 0 &&
          (in.branchTakenValid || in.scalarWorkPending)
      val markerUnconditionalRedirect =
        in.markerBoundary && activeValid && activeUnconditionalRedirect && activeTarget != 0
      val markerRedirectBoundary =
        markerUnconditionalRedirect || (markerNeedsBranchDecision && in.branchTakenValid && in.branchTaken)
      val markerFallthroughBoundary =
        in.markerBoundary && !markerUnconditionalRedirect &&
          (!markerNeedsBranchDecision || (in.branchTakenValid && !in.branchTaken))
      val blockAllocOnlyValid = markerFallthroughBoundary && !in.markerLifecycleConflict
      val markerPreRetireFire =
        markerFallthroughBoundary && !in.markerLifecycleConflict && !in.markerAllocReady &&
          markerAllocBlockedByActiveSlot && !in.retirePending
      val markerReady =
        !in.markerLifecycleConflict &&
          (in.markerStop || markerRedirectBoundary || (markerFallthroughBoundary && in.markerAllocReady))
      val markerAllocFire = markerFallthroughBoundary && markerReady && in.markerAllocReady
      val markerBoundaryRedirectFire = markerRedirectBoundary && markerReady
      val markerStopFire = in.markerStop && markerReady
      val markerScalarDoneFire =
        activeValid && (markerStopFire || markerAllocFire || markerBoundaryRedirectFire || markerPreRetireFire)
      val scalarRedirectScalarDoneFire = in.scalarRedirectValid && activeValid
      val robBlockLastClearsActive = in.robBlockLastValid && activeValid && in.robBlockLastBid == activeBid

      val decodeMarkerActive = in.markerBoundary || in.markerStop
      val retiredBoundary = in.retiredMarkerValid && in.retiredMarkerBoundary
      val retiredStop = in.retiredMarkerValid && in.retiredMarkerStop
      val retiredNeedsBranchDecision =
        retiredBoundary && activeValid && activeCond && activeTarget != 0 &&
          (in.branchTakenValid || in.scalarWorkPending)
      val retiredUnconditionalRedirect =
        retiredBoundary && activeValid && activeUnconditionalRedirect && activeTarget != 0
      val retiredRedirectBoundary =
        retiredUnconditionalRedirect || (retiredNeedsBranchDecision && in.branchTakenValid && in.branchTaken)
      val retiredFallthroughBoundary =
        retiredBoundary && !retiredUnconditionalRedirect &&
          (!retiredNeedsBranchDecision || (in.branchTakenValid && !in.branchTaken))
      val retiredLifecycleIdle =
        !decodeMarkerActive && !in.flushValid && !scalarRedirectScalarDoneFire && !in.robBlockLastValid
      val retiredMarkerReady =
        retiredLifecycleIdle && !in.markerLifecycleConflict &&
          (!in.retiredMarkerValid || retiredStop || retiredRedirectBoundary ||
            (retiredFallthroughBoundary && in.retiredMarkerBlockBidValid))
      val retiredMarkerBoundaryFire =
        retiredFallthroughBoundary && retiredMarkerReady && in.retiredMarkerBlockBidValid
      val retiredMarkerRedirectFire = retiredRedirectBoundary && retiredMarkerReady
      val retiredMarkerStopFire = retiredStop && retiredMarkerReady
      val retiredMarkerFire = retiredMarkerBoundaryFire || retiredMarkerRedirectFire || retiredMarkerStopFire
      val retiredScalarDoneFire =
        activeValid && (retiredMarkerStopFire || retiredMarkerBoundaryFire || retiredMarkerRedirectFire)

      val scalarDoneBid =
        if (markerScalarDoneFire || retiredScalarDoneFire || scalarRedirectScalarDoneFire) Some(activeBid)
        else if (in.robBlockLastValid) Some(in.robBlockLastBid)
        else None
      val stopRedirectPc =
        if ((markerStopFire || markerBoundaryRedirectFire) && activeValid &&
            activeTarget != 0 && activeTarget != (in.markerPc + in.markerInsnLen)) {
          Some(activeTarget)
        } else if ((retiredMarkerStopFire || retiredMarkerRedirectFire) && activeValid &&
            activeTarget != 0 && activeTarget != (in.retiredMarkerPc + in.retiredMarkerInsnLen)) {
          Some(activeTarget)
        } else {
          None
        }
      val out = Output(
        activeBid = if (activeValid) Some(activeBid) else None,
        blockAllocOnlyValid = blockAllocOnlyValid,
        retiredMarkerReady = retiredMarkerReady,
        retiredMarkerFire = retiredMarkerFire,
        retiredMarkerBoundaryFire = retiredMarkerBoundaryFire,
        retiredMarkerStopFire = retiredMarkerStopFire,
        markerAllocFire = markerAllocFire,
        markerPreRetireFire = markerPreRetireFire,
        scalarDoneBid = scalarDoneBid,
        stopRedirectPc = stopRedirectPc)

      if (in.flushValid || scalarRedirectScalarDoneFire) {
        activeValid = false
        activeBid = 0
        activeTarget = 0
        activeCond = false
        activeUnconditionalRedirect = false
      } else if (markerAllocFire) {
        activeValid = true
        activeBid = in.markerAllocBid
        activeTarget = in.markerTarget
        activeCond = in.markerKind == Kind.Cond
        activeUnconditionalRedirect = in.markerKind == Kind.Direct || in.markerKind == Kind.Call
      } else if (retiredMarkerBoundaryFire) {
        activeValid = true
        activeBid = in.retiredMarkerBlockBid
        activeTarget = in.retiredMarkerTarget
        activeCond = in.retiredMarkerKind == Kind.Cond
        activeUnconditionalRedirect = in.retiredMarkerKind == Kind.Direct || in.retiredMarkerKind == Kind.Call
      } else if (in.scalarBlockStartFire) {
        activeValid = true
        activeBid = in.scalarBlockStartBid
        activeTarget = 0
        activeCond = false
        activeUnconditionalRedirect = false
      } else if (markerStopFire || markerBoundaryRedirectFire || retiredMarkerStopFire ||
          retiredMarkerRedirectFire || robBlockLastClearsActive) {
        activeValid = false
        activeBid = 0
        activeTarget = 0
        activeCond = false
        activeUnconditionalRedirect = false
      }

      out
    }
  }
}

class BlockMarkerLifecycleSpec extends AnyFunSuite {
  import BlockMarkerLifecycleReference._

  test("reference allocates marker active BID and completes it at the next marker boundary") {
    val state = new State(entries = 8)

    val first = state.step(Input(markerBoundary = true, markerTarget = 0x100, markerAllocReady = true, markerAllocBid = 10))
    assert(first.activeBid.isEmpty)
    assert(first.blockAllocOnlyValid)
    assert(first.markerAllocFire)
    assert(!first.retiredMarkerFire)
    assert(first.scalarDoneBid.isEmpty)

    val second = state.step(Input(markerBoundary = true, markerTarget = 0x120, markerAllocReady = true, markerAllocBid = 11))
    assert(second.activeBid.contains(10))
    assert(second.markerAllocFire)
    assert(second.scalarDoneBid.contains(10))
  }

  test("reference consumes retired marker sources using row-owned block BID") {
    val state = new State(entries = 8)

    val retiredStart = state.step(Input(
      retiredMarkerValid = true,
      retiredMarkerBoundary = true,
      retiredMarkerTarget = 0x240,
      retiredMarkerKind = Kind.Fall,
      retiredMarkerBlockBidValid = true,
      retiredMarkerBlockBid = 0x44))
    assert(retiredStart.retiredMarkerReady)
    assert(retiredStart.retiredMarkerFire)
    assert(retiredStart.retiredMarkerBoundaryFire)
    assert(!retiredStart.blockAllocOnlyValid)
    assert(!retiredStart.markerAllocFire)
    assert(retiredStart.scalarDoneBid.isEmpty)

    val retiredStop = state.step(Input(
      retiredMarkerValid = true,
      retiredMarkerStop = true,
      retiredMarkerBlockBidValid = true,
      retiredMarkerBlockBid = 0x44))
    assert(retiredStop.activeBid.contains(0x44))
    assert(retiredStop.retiredMarkerReady)
    assert(retiredStop.retiredMarkerFire)
    assert(retiredStop.retiredMarkerStopFire)
    assert(retiredStop.scalarDoneBid.contains(0x44))

    val clear = state.step(Input())
    assert(clear.activeBid.isEmpty)
  }

  test("reference backpressures malformed retired marker boundaries") {
    val state = new State(entries = 8)

    val malformed = state.step(Input(
      retiredMarkerValid = true,
      retiredMarkerBoundary = true,
      retiredMarkerBlockBidValid = false))
    assert(!malformed.retiredMarkerReady)
    assert(!malformed.retiredMarkerFire)
    assert(malformed.scalarDoneBid.isEmpty)
  }

  test("reference redirects direct active blocks without allocating the following marker") {
    val state = new State(entries = 8)

    state.step(Input(
      markerBoundary = true,
      markerTarget = 0x400055e2L,
      markerKind = Kind.Direct,
      markerAllocReady = true,
      markerAllocBid = 20))

    val redirect = state.step(Input(
      markerBoundary = true,
      markerPc = 0x400055d4L,
      markerInsnLen = 2,
      markerTarget = 0x400055f6L,
      markerKind = Kind.Cond,
      markerAllocReady = true,
      markerAllocBid = 21))
    assert(redirect.activeBid.contains(20))
    assert(!redirect.blockAllocOnlyValid)
    assert(!redirect.markerAllocFire)
    assert(redirect.scalarDoneBid.contains(20))
    assert(redirect.stopRedirectPc.contains(0x400055e2L))

    val clear = state.step(Input())
    assert(clear.activeBid.isEmpty)
  }

  test("reference tracks scalar-created active blocks until block-last deallocation") {
    val state = new State(entries = 8)

    val scalarStart = state.step(Input(scalarBlockStartFire = true, scalarBlockStartBid = 0x30))
    assert(scalarStart.activeBid.isEmpty)
    assert(scalarStart.scalarDoneBid.isEmpty)

    val blockLast = state.step(Input(robBlockLastValid = true, robBlockLastBid = 0x30))
    assert(blockLast.activeBid.contains(0x30))
    assert(blockLast.scalarDoneBid.contains(0x30))

    val clear = state.step(Input())
    assert(clear.activeBid.isEmpty)
  }

  test("reference pre-retires active same-slot marker blocks without clearing active state") {
    val state = new State(entries = 8)

    state.step(Input(markerBoundary = true, markerAllocReady = true, markerAllocBid = 0xfc))
    val preRetire = state.step(Input(markerBoundary = true, markerAllocReady = false, markerAllocBid = 0x104))
    assert(preRetire.activeBid.contains(0xfc))
    assert(preRetire.markerPreRetireFire)
    assert(preRetire.scalarDoneBid.contains(0xfc))

    val waiting = state.step(Input(markerBoundary = true, markerAllocReady = false, markerAllocBid = 0x104, retirePending = true))
    assert(waiting.activeBid.contains(0xfc))
    assert(!waiting.markerPreRetireFire)
    assert(waiting.scalarDoneBid.isEmpty)
  }

  test("BlockMarkerLifecycle elaborates the active-state and lifecycle ports") {
    val sv = ChiselStage.emitSystemVerilog(new BlockMarkerLifecycle(entries = 8, bidWidth = 64, pcWidth = 64))

    assert(sv.contains("module BlockMarkerLifecycle"))
    assert(sv.contains("io_activeValid"))
    assert(sv.contains("io_activeBid"))
    assert(sv.contains("io_blockAllocOnlyValid"))
    assert(sv.contains("io_retiredMarkerReady"))
    assert(sv.contains("io_retiredMarker_blockBid"))
    assert(sv.contains("io_scalarDoneValid"))
    assert(sv.contains("io_stopRedirectValid"))
  }
}
