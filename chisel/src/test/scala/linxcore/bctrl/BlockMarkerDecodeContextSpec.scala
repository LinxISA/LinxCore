package linxcore.bctrl

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object BlockMarkerDecodeContextReference {
  object Kind extends Enumeration {
    val Fall, Cond, Call, Direct = Value
  }

  final case class Input(
      decodeValid: Boolean = false,
      decodeFire: Boolean = false,
      boundary: Boolean = false,
      stop: Boolean = false,
      last: Boolean = false,
      stid: Int = 0,
      allocBid: BigInt = 0,
      target: BigInt = 0,
      kind: Kind.Value = Kind.Fall,
      scalarRedirectValid: Boolean = false,
      scalarRedirectStid: Int = 0,
      robBlockLastValid: Boolean = false,
      robBlockLastBid: BigInt = 0,
      queryStid: Int = 0,
      flushValid: Boolean = false)

  final case class Output(
      activeBid: Option[BigInt],
      activeTarget: BigInt,
      decodeBlockBid: BigInt,
      usesExistingBlock: Boolean,
      startsNewBlock: Boolean,
      closesActive: Boolean,
      stopWithoutActive: Boolean)

  final class State(stidCount: Int = 1) {
    require(stidCount > 0)
    private val activeValid = Array.fill(stidCount)(false)
    private val activeBid = Array.fill(stidCount)(BigInt(0))
    private val activeTarget = Array.fill(stidCount)(BigInt(0))
    private val activeCond = Array.fill(stidCount)(false)
    private val activeUnconditionalRedirect = Array.fill(stidCount)(false)

    private def lane(stid: Int): Option[Int] =
      if (stid >= 0 && stid < stidCount) Some(stid) else None

    private def clear(idx: Int): Unit = {
      activeValid(idx) = false
      activeBid(idx) = 0
      activeTarget(idx) = 0
      activeCond(idx) = false
      activeUnconditionalRedirect(idx) = false
    }

    private def installBoundary(idx: Int, bid: BigInt, target: BigInt, kind: Kind.Value): Unit = {
      activeValid(idx) = true
      activeBid(idx) = bid
      activeTarget(idx) = target
      activeCond(idx) = kind == Kind.Cond
      activeUnconditionalRedirect(idx) = kind == Kind.Direct || kind == Kind.Call
    }

    private def installScalar(idx: Int, bid: BigInt): Unit = {
      activeValid(idx) = true
      activeBid(idx) = bid
      activeTarget(idx) = 0
      activeCond(idx) = false
      activeUnconditionalRedirect(idx) = false
    }

    def step(in: Input = Input()): Output = {
      val decodeLane = lane(in.stid)
      val queryLane = lane(in.queryStid)
      val decodeActive = decodeLane.exists(activeValid)
      val decodeActiveBid = decodeLane.map(activeBid).getOrElse(BigInt(0))
      val decodeMarker = in.boundary || in.stop
      val candidateValid = in.decodeValid || in.decodeFire
      val decodeScalar = in.decodeFire && !decodeMarker
      val usesExisting = candidateValid && decodeActive && !in.boundary
      val startsNew =
        in.decodeFire && decodeLane.nonEmpty && (in.boundary || (decodeScalar && !decodeActive))
      val closesActive = in.decodeFire && decodeLane.nonEmpty && decodeActive && (in.boundary || in.stop || in.last)
      val out = Output(
        activeBid = queryLane.filter(activeValid).map(activeBid),
        activeTarget = queryLane.map(activeTarget).getOrElse(BigInt(0)),
        decodeBlockBid = if (usesExisting) decodeActiveBid else in.allocBid,
        usesExistingBlock = usesExisting,
        startsNewBlock = startsNew,
        closesActive = closesActive,
        stopWithoutActive = in.decodeFire && in.stop && !in.boundary && !decodeActive)

      if (in.flushValid) {
        activeValid.indices.foreach(clear)
      } else if (in.scalarRedirectValid) {
        lane(in.scalarRedirectStid).foreach(clear)
      } else if (in.decodeFire && in.boundary) {
        decodeLane.foreach(installBoundary(_, in.allocBid, in.target, in.kind))
      } else if (in.decodeFire && in.stop) {
        decodeLane.foreach(clear)
      } else if (decodeScalar && in.last) {
        decodeLane.foreach(clear)
      } else if (decodeScalar && !decodeActive) {
        decodeLane.foreach(installScalar(_, in.allocBid))
      } else if (in.robBlockLastValid) {
        activeValid.indices.foreach { idx =>
          if (activeValid(idx) && activeBid(idx) == in.robBlockLastBid) {
            clear(idx)
          }
        }
      }

      out
    }
  }
}

class BlockMarkerDecodeContextSpec extends AnyFunSuite {
  import BlockMarkerDecodeContextReference._

  test("reference assigns a new BID to decoded boundaries even when an active block exists") {
    val state = new State

    val scalarStart = state.step(Input(decodeFire = true, allocBid = 0x10))
    assert(scalarStart.activeBid.isEmpty)
    assert(!scalarStart.usesExistingBlock)
    assert(scalarStart.startsNewBlock)
    assert(scalarStart.decodeBlockBid == 0x10)

    val boundary = state.step(Input(
      decodeFire = true,
      boundary = true,
      allocBid = 0x20,
      target = 0x400,
      kind = Kind.Cond))
    assert(boundary.activeBid.contains(0x10))
    assert(!boundary.usesExistingBlock)
    assert(boundary.startsNewBlock)
    assert(boundary.closesActive)
    assert(boundary.decodeBlockBid == 0x20)

    val scalarInBoundary = state.step(Input(decodeFire = true, allocBid = 0x30))
    assert(scalarInBoundary.activeBid.contains(0x20))
    assert(scalarInBoundary.activeTarget == 0x400)
    assert(scalarInBoundary.usesExistingBlock)
    assert(!scalarInBoundary.startsNewBlock)
    assert(scalarInBoundary.decodeBlockBid == 0x20)
  }

  test("reference assigns a stop marker to the active BID and clears decode context") {
    val state = new State

    state.step(Input(decodeFire = true, boundary = true, allocBid = 0x40))

    val stop = state.step(Input(decodeFire = true, stop = true, allocBid = 0x50))
    assert(stop.activeBid.contains(0x40))
    assert(stop.usesExistingBlock)
    assert(!stop.startsNewBlock)
    assert(stop.closesActive)
    assert(!stop.stopWithoutActive)
    assert(stop.decodeBlockBid == 0x40)

    val nextScalar = state.step(Input(decodeFire = true, allocBid = 0x60))
    assert(nextScalar.activeBid.isEmpty)
    assert(!nextScalar.usesExistingBlock)
    assert(nextScalar.startsNewBlock)
    assert(nextScalar.decodeBlockBid == 0x60)
  }

  test("reference keeps decode context independent across STID lanes") {
    val state = new State(stidCount = 2)

    state.step(Input(decodeFire = true, boundary = true, stid = 0, allocBid = 0x70, queryStid = 0))
    val stid1Start = state.step(Input(decodeFire = true, boundary = true, stid = 1, allocBid = 0x80, queryStid = 0))
    assert(stid1Start.activeBid.contains(0x70))
    assert(!stid1Start.usesExistingBlock)

    val stid1Stop = state.step(Input(decodeFire = true, stop = true, stid = 1, allocBid = 0x90, queryStid = 0))
    assert(stid1Stop.activeBid.contains(0x70))
    assert(stid1Stop.usesExistingBlock)
    assert(stid1Stop.decodeBlockBid == 0x80)

    val stid0StillActive = state.step(Input(queryStid = 0))
    assert(stid0StillActive.activeBid.contains(0x70))
  }

  test("reference clears decode context on flush, scalar redirect, and ROB block-last") {
    val flushState = new State
    flushState.step(Input(decodeFire = true, boundary = true, allocBid = 0xa0))
    assert(flushState.step(Input(flushValid = true)).activeBid.contains(0xa0))
    assert(flushState.step().activeBid.isEmpty)

    val redirectState = new State(stidCount = 2)
    redirectState.step(Input(decodeFire = true, boundary = true, stid = 0, allocBid = 0xb0, queryStid = 0))
    redirectState.step(Input(decodeFire = true, boundary = true, stid = 1, allocBid = 0xc0, queryStid = 1))
    assert(redirectState.step(Input(scalarRedirectValid = true, scalarRedirectStid = 1, queryStid = 1)).activeBid.contains(0xc0))
    assert(redirectState.step(Input(queryStid = 1)).activeBid.isEmpty)
    assert(redirectState.step(Input(queryStid = 0)).activeBid.contains(0xb0))

    val blockLastState = new State
    blockLastState.step(Input(decodeFire = true, allocBid = 0xd0))
    assert(blockLastState.step(Input(robBlockLastValid = true, robBlockLastBid = 0xd0)).activeBid.contains(0xd0))
    assert(blockLastState.step().activeBid.isEmpty)
  }

  test("reference clears decode context after a scalar last row") {
    val state = new State

    state.step(Input(decodeFire = true, boundary = true, allocBid = 0x120))
    val last = state.step(Input(decodeFire = true, last = true, allocBid = 0x130))
    assert(last.activeBid.contains(0x120))
    assert(last.usesExistingBlock)
    assert(last.closesActive)
    assert(last.decodeBlockBid == 0x120)

    val continuation = state.step(Input(decodeFire = true, allocBid = 0x140))
    assert(continuation.activeBid.isEmpty)
    assert(!continuation.usesExistingBlock)
    assert(continuation.startsNewBlock)
    assert(continuation.decodeBlockBid == 0x140)
  }

  test("reference reports stop markers that have no active decode context") {
    val state = new State

    val stop = state.step(Input(decodeFire = true, stop = true, allocBid = 0xe0))
    assert(stop.activeBid.isEmpty)
    assert(!stop.usesExistingBlock)
    assert(!stop.startsNewBlock)
    assert(!stop.closesActive)
    assert(stop.stopWithoutActive)
    assert(stop.decodeBlockBid == 0xe0)
  }

  test("reference publishes candidate active BID without mutating state before decode fires") {
    val state = new State

    state.step(Input(decodeFire = true, boundary = true, allocBid = 0xf0))

    val stalledScalar = state.step(Input(decodeValid = true, decodeFire = false, allocBid = 0x100))
    assert(stalledScalar.activeBid.contains(0xf0))
    assert(stalledScalar.usesExistingBlock)
    assert(!stalledScalar.startsNewBlock)
    assert(!stalledScalar.closesActive)
    assert(stalledScalar.decodeBlockBid == 0xf0)

    val stillActive = state.step(Input())
    assert(stillActive.activeBid.contains(0xf0))
  }

  test("BlockMarkerDecodeContext elaborates the decode-side active BID ports") {
    val sv = ChiselStage.emitSystemVerilog(new BlockMarkerDecodeContext(
      bidWidth = 64,
      pcWidth = 64,
      stidCount = 2))

    assert(sv.contains("module BlockMarkerDecodeContext"))
    assert(sv.contains("io_decodeValid"))
    assert(sv.contains("io_decodeFire"))
    assert(sv.contains("io_decodeLast"))
    assert(sv.contains("io_decodeBlockBid"))
    assert(sv.contains("io_decodeUsesExistingBlock"))
    assert(sv.contains("io_decodeStartsNewBlock"))
    assert(sv.contains("io_decodeClosesActive"))
    assert(sv.contains("io_decodeStopWithoutActive"))
    assert(sv.contains("io_activeBid"))
  }
}
