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
      markerStid: Int = 0,
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
      retiredMarkerIsLast: Boolean = false,
      retiredMarkerPc: BigInt = 0,
      retiredMarkerTarget: BigInt = 0,
      retiredMarkerInsnLen: BigInt = 2,
      retiredMarkerKind: Kind.Value = Kind.Fall,
      retiredMarkerStid: Int = 0,
      retiredMarkerBlockBidValid: Boolean = false,
      retiredMarkerBlockBid: BigInt = 0,
      scalarRedirectValid: Boolean = false,
      scalarRedirectStid: Int = 0,
      scalarBlockStartFire: Boolean = false,
      scalarBlockStartStid: Int = 0,
      scalarBlockStartBid: BigInt = 0,
      robBlockLastValid: Boolean = false,
      robBlockLastBid: BigInt = 0,
      robBlockLastStid: Int = 0,
      activeQueryStid: Int = 0,
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

  final class State(entries: Int, stidCount: Int = 1) {
    require(stidCount > 0)
    private val activeValid = Array.fill(stidCount)(false)
    private val activeBid = Array.fill(stidCount)(BigInt(0))
    private val activeTarget = Array.fill(stidCount)(BigInt(0))
    private val activeCond = Array.fill(stidCount)(false)
    private val activeUnconditionalRedirect = Array.fill(stidCount)(false)
    private val activeClearsOnRobBlockLast = Array.fill(stidCount)(false)
    private var markerOwnedDonePending = false
    private var markerOwnedDoneBid = BigInt(0)

    private def sameSlot(a: BigInt, b: BigInt): Boolean =
      (a % entries) == (b % entries)

    private def lane(stid: Int): Option[Int] =
      if (stid >= 0 && stid < stidCount) Some(stid) else None

    private def isActive(lane: Option[Int]): Boolean =
      lane.exists(activeValid)

    private def laneBid(lane: Option[Int]): BigInt =
      lane.map(activeBid).getOrElse(BigInt(0))

    private def laneTarget(lane: Option[Int]): BigInt =
      lane.map(activeTarget).getOrElse(BigInt(0))

    private def laneCond(lane: Option[Int]): Boolean =
      lane.exists(activeCond)

    private def laneUnconditionalRedirect(lane: Option[Int]): Boolean =
      lane.exists(activeUnconditionalRedirect)

    private def clear(lane: Int): Unit = {
      activeValid(lane) = false
      activeBid(lane) = 0
      activeTarget(lane) = 0
      activeCond(lane) = false
      activeUnconditionalRedirect(lane) = false
      activeClearsOnRobBlockLast(lane) = false
    }

    private def installBoundary(lane: Int, bid: BigInt, target: BigInt, kind: Kind.Value): Unit = {
      activeValid(lane) = true
      activeBid(lane) = bid
      activeTarget(lane) = target
      activeCond(lane) = kind == Kind.Cond
      activeUnconditionalRedirect(lane) = kind == Kind.Direct || kind == Kind.Call
      activeClearsOnRobBlockLast(lane) = false
    }

    private def installScalar(lane: Int, bid: BigInt): Unit = {
      activeValid(lane) = true
      activeBid(lane) = bid
      activeTarget(lane) = 0
      activeCond(lane) = false
      activeUnconditionalRedirect(lane) = false
      activeClearsOnRobBlockLast(lane) = true
    }

    def step(in: Input): Output = {
      val markerLane = lane(in.markerStid)
      val retiredLane = lane(in.retiredMarkerStid)
      val queryLane = lane(in.activeQueryStid)
      val scalarRedirectLane = lane(in.scalarRedirectStid)
      val scalarBlockStartLane = lane(in.scalarBlockStartStid)
      val markerActiveValid = isActive(markerLane)
      val markerActiveBid = laneBid(markerLane)
      val markerActiveTarget = laneTarget(markerLane)
      val retiredActiveValid = isActive(retiredLane)
      val retiredActiveBid = laneBid(retiredLane)
      val retiredActiveTarget = laneTarget(retiredLane)
      val scalarRedirectActiveValid = isActive(scalarRedirectLane)
      val scalarRedirectActiveBid = laneBid(scalarRedirectLane)

      val markerAllocBlockedByActiveSlot = markerActiveValid && sameSlot(in.markerAllocBid, markerActiveBid)
      val markerNeedsBranchDecision =
        in.markerBoundary && markerActiveValid && laneCond(markerLane) && markerActiveTarget != 0 &&
          (in.branchTakenValid || in.scalarWorkPending)
      val markerUnconditionalRedirect =
        in.markerBoundary && markerActiveValid && laneUnconditionalRedirect(markerLane) && markerActiveTarget != 0
      val markerRedirectBoundary =
        markerUnconditionalRedirect || (markerNeedsBranchDecision && in.branchTakenValid && in.branchTaken)
      val markerFallthroughBoundary =
        in.markerBoundary && !markerUnconditionalRedirect &&
          (!markerNeedsBranchDecision || (in.branchTakenValid && !in.branchTaken))
      val markerStidInRange = markerLane.nonEmpty
      val blockAllocOnlyValid = markerStidInRange && markerFallthroughBoundary && !in.markerLifecycleConflict
      val markerPreRetireFire =
        markerStidInRange && markerFallthroughBoundary && !in.markerLifecycleConflict && !in.markerAllocReady &&
          markerAllocBlockedByActiveSlot && !in.retirePending
      val markerReady =
        markerStidInRange && !markerOwnedDonePending && !in.markerLifecycleConflict &&
          (in.markerStop || markerRedirectBoundary || (markerFallthroughBoundary && in.markerAllocReady))
      val markerAllocFire = markerFallthroughBoundary && markerReady && in.markerAllocReady
      val markerBoundaryRedirectFire = markerRedirectBoundary && markerReady
      val markerStopFire = in.markerStop && markerReady
      val markerScalarDoneFire =
        markerActiveValid && (markerStopFire || markerAllocFire || markerBoundaryRedirectFire || markerPreRetireFire)
      val scalarRedirectScalarDoneFire = in.scalarRedirectValid && scalarRedirectActiveValid
      val robBlockLastClearsActive =
        activeValid.indices.exists(idx =>
          in.robBlockLastValid && activeValid(idx) && activeClearsOnRobBlockLast(idx) &&
            in.robBlockLastStid == idx && in.robBlockLastBid == activeBid(idx))

      val decodeMarkerActive = in.markerBoundary || in.markerStop
      val retiredBoundary = in.retiredMarkerValid && in.retiredMarkerBoundary
      val retiredStop = in.retiredMarkerValid && in.retiredMarkerStop
      val retiredNeedsBranchDecision =
        retiredBoundary && retiredActiveValid && laneCond(retiredLane) && retiredActiveTarget != 0 &&
          (in.branchTakenValid || in.scalarWorkPending)
      val retiredUnconditionalRedirect =
        retiredBoundary && retiredActiveValid && laneUnconditionalRedirect(retiredLane) && retiredActiveTarget != 0
      val retiredRedirectBoundary =
        retiredUnconditionalRedirect || (retiredNeedsBranchDecision && in.branchTakenValid && in.branchTaken)
      val retiredFallthroughBoundary =
        retiredBoundary && !retiredUnconditionalRedirect &&
          (!retiredNeedsBranchDecision || (in.branchTakenValid && !in.branchTaken))
      val retiredMarkerOwnsBlockLast =
        in.retiredMarkerValid && in.retiredMarkerIsLast && in.retiredMarkerBlockBidValid &&
          in.robBlockLastValid && in.retiredMarkerStid == in.robBlockLastStid &&
          in.retiredMarkerBlockBid == in.robBlockLastBid
      val retiredLifecycleIdle =
        !markerOwnedDonePending && !decodeMarkerActive && !in.flushValid && !scalarRedirectScalarDoneFire &&
          (!in.robBlockLastValid || retiredMarkerOwnsBlockLast)
      val retiredMarkerConflict = in.markerLifecycleConflict && !retiredMarkerOwnsBlockLast
      val retiredStidInRange = retiredLane.nonEmpty
      val retiredMarkerReady =
        retiredLifecycleIdle && !retiredMarkerConflict &&
          (!in.retiredMarkerValid || (retiredStidInRange && (retiredStop || retiredRedirectBoundary ||
            (retiredFallthroughBoundary && in.retiredMarkerBlockBidValid))))
      val retiredMarkerBoundaryFire =
        retiredFallthroughBoundary && retiredMarkerReady && in.retiredMarkerBlockBidValid
      val retiredMarkerRedirectFire = retiredRedirectBoundary && retiredMarkerReady
      val retiredMarkerStopFire = retiredStop && retiredMarkerReady
      val retiredMarkerFire = retiredMarkerBoundaryFire || retiredMarkerRedirectFire || retiredMarkerStopFire
      val retiredScalarDoneFire =
        retiredActiveValid && (retiredMarkerStopFire || retiredMarkerBoundaryFire || retiredMarkerRedirectFire)
      val retiredRedirectOwnsMarkerOnlyBlock =
        retiredMarkerRedirectFire && in.retiredMarkerBlockBidValid &&
          (!retiredActiveValid || in.retiredMarkerBlockBid != retiredActiveBid)
      val liveScalarDoneFire =
        markerScalarDoneFire || retiredScalarDoneFire || scalarRedirectScalarDoneFire || in.robBlockLastValid
      val markerOwnedDoneFire = markerOwnedDonePending && !liveScalarDoneFire

      val scalarDoneBid =
        if (markerScalarDoneFire) Some(markerActiveBid)
        else if (retiredScalarDoneFire) Some(retiredActiveBid)
        else if (scalarRedirectScalarDoneFire) Some(scalarRedirectActiveBid)
        else if (in.robBlockLastValid) Some(in.robBlockLastBid)
        else if (markerOwnedDoneFire) Some(markerOwnedDoneBid)
        else None
      val stopRedirectPc =
        if ((markerStopFire || markerBoundaryRedirectFire) && markerActiveValid &&
            markerActiveTarget != 0 && markerActiveTarget != (in.markerPc + in.markerInsnLen)) {
          Some(markerActiveTarget)
        } else if ((retiredMarkerStopFire || retiredMarkerRedirectFire) && retiredActiveValid &&
            retiredActiveTarget != 0 && retiredActiveTarget != (in.retiredMarkerPc + in.retiredMarkerInsnLen)) {
          Some(retiredActiveTarget)
        } else {
          None
        }
      val out = Output(
        activeBid = if (isActive(queryLane)) Some(laneBid(queryLane)) else None,
        blockAllocOnlyValid = blockAllocOnlyValid,
        retiredMarkerReady = retiredMarkerReady,
        retiredMarkerFire = retiredMarkerFire,
        retiredMarkerBoundaryFire = retiredMarkerBoundaryFire,
        retiredMarkerStopFire = retiredMarkerStopFire,
        markerAllocFire = markerAllocFire,
        markerPreRetireFire = markerPreRetireFire,
        scalarDoneBid = scalarDoneBid,
        stopRedirectPc = stopRedirectPc)

      if (in.flushValid) {
        markerOwnedDonePending = false
        markerOwnedDoneBid = 0
      } else if (markerOwnedDoneFire) {
        markerOwnedDonePending = false
        markerOwnedDoneBid = 0
      } else if (retiredRedirectOwnsMarkerOnlyBlock) {
        markerOwnedDonePending = true
        markerOwnedDoneBid = in.retiredMarkerBlockBid
      }

      if (in.flushValid) {
        activeValid.indices.foreach(clear)
      } else if (scalarRedirectScalarDoneFire) {
        scalarRedirectLane.foreach(clear)
      } else if (markerAllocFire) {
        markerLane.foreach(installBoundary(_, in.markerAllocBid, in.markerTarget, in.markerKind))
      } else if (retiredMarkerBoundaryFire) {
        retiredLane.foreach(installBoundary(_, in.retiredMarkerBlockBid, in.retiredMarkerTarget, in.retiredMarkerKind))
      } else if (in.scalarBlockStartFire) {
        scalarBlockStartLane.foreach(installScalar(_, in.scalarBlockStartBid))
      } else if (markerStopFire || markerBoundaryRedirectFire || retiredMarkerStopFire ||
          retiredMarkerRedirectFire || robBlockLastClearsActive) {
        if (markerStopFire || markerBoundaryRedirectFire) {
          markerLane.foreach(clear)
        }
        if (retiredMarkerStopFire || retiredMarkerRedirectFire) {
          retiredLane.foreach(clear)
        }
        if (in.robBlockLastValid) {
          activeValid.indices.foreach { idx =>
            if (in.robBlockLastStid == idx && activeValid(idx) && activeClearsOnRobBlockLast(idx) &&
                in.robBlockLastBid == activeBid(idx)) {
              clear(idx)
            }
          }
        }
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

  test("reference lets retired marker stop own matching ROB block-last event") {
    val state = new State(entries = 8)

    state.step(Input(
      retiredMarkerValid = true,
      retiredMarkerBoundary = true,
      retiredMarkerTarget = 0x4000550eL,
      retiredMarkerKind = Kind.Direct,
      retiredMarkerBlockBidValid = true,
      retiredMarkerBlockBid = 0x44))

    val retiredStop = state.step(Input(
      retiredMarkerValid = true,
      retiredMarkerStop = true,
      retiredMarkerIsLast = true,
      retiredMarkerPc = 0x40005508L,
      retiredMarkerInsnLen = 2,
      retiredMarkerBlockBidValid = true,
      retiredMarkerBlockBid = 0x44,
      markerLifecycleConflict = true,
      robBlockLastValid = true,
      robBlockLastBid = 0x44))
    assert(retiredStop.activeBid.contains(0x44))
    assert(retiredStop.retiredMarkerReady)
    assert(retiredStop.retiredMarkerFire)
    assert(retiredStop.retiredMarkerStopFire)
    assert(retiredStop.scalarDoneBid.contains(0x44))
    assert(retiredStop.stopRedirectPc.contains(0x4000550eL))

    val clear = state.step(Input())
    assert(clear.activeBid.isEmpty)
  }

  test("reference does not let equal BID from another STID own ROB block-last") {
    val state = new State(entries = 8, stidCount = 2)

    state.step(Input(
      retiredMarkerValid = true,
      retiredMarkerBoundary = true,
      retiredMarkerStid = 1,
      retiredMarkerTarget = 0x4000550eL,
      retiredMarkerKind = Kind.Direct,
      retiredMarkerBlockBidValid = true,
      retiredMarkerBlockBid = 0x44,
      activeQueryStid = 1))

    val wrongLane = state.step(Input(
      retiredMarkerValid = true,
      retiredMarkerStop = true,
      retiredMarkerIsLast = true,
      retiredMarkerStid = 1,
      retiredMarkerBlockBidValid = true,
      retiredMarkerBlockBid = 0x44,
      markerLifecycleConflict = true,
      robBlockLastValid = true,
      robBlockLastBid = 0x44,
      robBlockLastStid = 0,
      activeQueryStid = 1))

    assert(!wrongLane.retiredMarkerReady)
    assert(!wrongLane.retiredMarkerFire)
    assert(wrongLane.activeBid.contains(0x44))
  }

  test("reference keeps retired marker boundary target across marker-row block-last cleanup") {
    val state = new State(entries = 8)

    val retiredStart = state.step(Input(
      retiredMarkerValid = true,
      retiredMarkerBoundary = true,
      retiredMarkerIsLast = true,
      retiredMarkerTarget = 0x4000550eL,
      retiredMarkerKind = Kind.Direct,
      retiredMarkerBlockBidValid = true,
      retiredMarkerBlockBid = 0x44,
      robBlockLastValid = true,
      robBlockLastBid = 0x44))
    assert(retiredStart.retiredMarkerReady)
    assert(retiredStart.retiredMarkerBoundaryFire)

    val markerBlockLastCleanup = state.step(Input(
      robBlockLastValid = true,
      robBlockLastBid = 0x44))
    assert(markerBlockLastCleanup.activeBid.contains(0x44))
    assert(markerBlockLastCleanup.scalarDoneBid.contains(0x44))

    val retiredStop = state.step(Input(
      retiredMarkerValid = true,
      retiredMarkerStop = true,
      retiredMarkerIsLast = true,
      retiredMarkerPc = 0x40005508L,
      retiredMarkerInsnLen = 2,
      retiredMarkerBlockBidValid = true,
      retiredMarkerBlockBid = 0x44,
      markerLifecycleConflict = true,
      robBlockLastValid = true,
      robBlockLastBid = 0x44))
    assert(retiredStop.activeBid.contains(0x44))
    assert(retiredStop.retiredMarkerReady)
    assert(retiredStop.retiredMarkerStopFire)
    assert(retiredStop.scalarDoneBid.contains(0x44))
    assert(retiredStop.stopRedirectPc.contains(0x4000550eL))

    val clear = state.step(Input())
    assert(clear.activeBid.isEmpty)
  }

  test("reference completes marker-owned redirect boundary block after active block") {
    val state = new State(entries = 8)

    state.step(Input(
      retiredMarkerValid = true,
      retiredMarkerBoundary = true,
      retiredMarkerTarget = 0x40005566L,
      retiredMarkerKind = Kind.Direct,
      retiredMarkerBlockBidValid = true,
      retiredMarkerBlockBid = 0x44))

    val redirect = state.step(Input(
      retiredMarkerValid = true,
      retiredMarkerBoundary = true,
      retiredMarkerPc = 0x40005574L,
      retiredMarkerTarget = 0x40005566L,
      retiredMarkerInsnLen = 2,
      retiredMarkerKind = Kind.Direct,
      retiredMarkerBlockBidValid = true,
      retiredMarkerBlockBid = 0x45))
    assert(redirect.activeBid.contains(0x44))
    assert(redirect.retiredMarkerReady)
    assert(redirect.retiredMarkerFire)
    assert(redirect.scalarDoneBid.contains(0x44))
    assert(redirect.stopRedirectPc.contains(0x40005566L))

    val markerOnlyDone = state.step(Input())
    assert(markerOnlyDone.activeBid.isEmpty)
    assert(!markerOnlyDone.retiredMarkerReady)
    assert(markerOnlyDone.scalarDoneBid.contains(0x45))

    val idle = state.step(Input())
    assert(idle.activeBid.isEmpty)
    assert(idle.scalarDoneBid.isEmpty)
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

  test("reference keeps active marker state independent across STID lanes") {
    val state = new State(entries = 8, stidCount = 2)

    state.step(Input(
      markerBoundary = true,
      markerStid = 0,
      markerAllocReady = true,
      markerAllocBid = 0x10))

    val stid1Start = state.step(Input(
      markerBoundary = true,
      markerStid = 1,
      markerAllocReady = true,
      markerAllocBid = 0x20,
      activeQueryStid = 0))
    assert(stid1Start.activeBid.contains(0x10))
    assert(stid1Start.markerAllocFire)
    assert(stid1Start.scalarDoneBid.isEmpty)

    val stid1Stop = state.step(Input(
      markerStop = true,
      markerStid = 1,
      activeQueryStid = 0))
    assert(stid1Stop.activeBid.contains(0x10))
    assert(stid1Stop.scalarDoneBid.contains(0x20))

    val stid0StillActive = state.step(Input(activeQueryStid = 0))
    assert(stid0StillActive.activeBid.contains(0x10))
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
    val sv = ChiselStage.emitSystemVerilog(new BlockMarkerLifecycle(
      entries = 8,
      bidWidth = 64,
      pcWidth = 64,
      stidCount = 2))

    assert(sv.contains("module BlockMarkerLifecycle"))
    assert(sv.contains("io_markerStid"))
    assert(sv.contains("io_activeQueryStid"))
    assert(sv.contains("io_scalarRedirectStid"))
    assert(sv.contains("io_activeValid"))
    assert(sv.contains("io_activeBid"))
    assert(sv.contains("io_blockAllocOnlyValid"))
    assert(sv.contains("io_retiredMarkerReady"))
    assert(sv.contains("io_retiredMarker_blockBid"))
    assert(sv.contains("io_scalarDoneValid"))
    assert(sv.contains("io_stopRedirectValid"))
  }
}
