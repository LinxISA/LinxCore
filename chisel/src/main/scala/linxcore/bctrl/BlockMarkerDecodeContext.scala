package linxcore.bctrl

import chisel3._
import chisel3.util.Mux1H
import linxcore.common.BoundaryKind

class BlockMarkerDecodeContextIO(
    val bidWidth: Int = BID.DefaultWidth,
    val pcWidth: Int = 64,
    val stidWidth: Int = 8)
    extends Bundle {
  val flushValid = Input(Bool())

  val decodeValid = Input(Bool())
  val decodeFire = Input(Bool())
  val decodeBoundary = Input(Bool())
  val decodeStop = Input(Bool())
  val decodeStid = Input(UInt(stidWidth.W))
  val decodeAllocBid = Input(UInt(bidWidth.W))
  val decodeTarget = Input(UInt(pcWidth.W))
  val decodeBoundaryKind = Input(BoundaryKind())

  val scalarRedirectValid = Input(Bool())
  val scalarRedirectStid = Input(UInt(stidWidth.W))
  val robBlockLastValid = Input(Bool())
  val robBlockLastBid = Input(UInt(bidWidth.W))
  val queryStid = Input(UInt(stidWidth.W))

  val decodeStidInRange = Output(Bool())
  val decodeActiveValid = Output(Bool())
  val decodeActiveBid = Output(UInt(bidWidth.W))
  val decodeBlockBid = Output(UInt(bidWidth.W))
  val decodeUsesExistingBlock = Output(Bool())
  val decodeStartsNewBlock = Output(Bool())
  val decodeClosesActive = Output(Bool())
  val decodeStopWithoutActive = Output(Bool())

  val activeValid = Output(Bool())
  val activeBid = Output(UInt(bidWidth.W))
  val activeTarget = Output(UInt(pcWidth.W))
  val activeCond = Output(Bool())
  val activeUnconditionalRedirect = Output(Bool())
}

class BlockMarkerDecodeContext(
    val bidWidth: Int = BID.DefaultWidth,
    val pcWidth: Int = 64,
    val stidWidth: Int = 8,
    val stidCount: Int = 1)
    extends Module {
  require(stidCount > 0, "decode context must track at least one STID")
  require(BigInt(stidCount) <= (BigInt(1) << stidWidth), "STID count must fit stidWidth")

  val io = IO(new BlockMarkerDecodeContextIO(bidWidth, pcWidth, stidWidth))

  val activeValid = RegInit(VecInit(Seq.fill(stidCount)(false.B)))
  val activeBid = RegInit(VecInit(Seq.fill(stidCount)(0.U(bidWidth.W))))
  val activeTarget = RegInit(VecInit(Seq.fill(stidCount)(0.U(pcWidth.W))))
  val activeCond = RegInit(VecInit(Seq.fill(stidCount)(false.B)))
  val activeUnconditionalRedirect = RegInit(VecInit(Seq.fill(stidCount)(false.B)))

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
  }

  private def installBoundary(idx: Int, bid: UInt, target: UInt, kind: BoundaryKind.Type): Unit = {
    activeValid(idx) := true.B
    activeBid(idx) := bid
    activeTarget(idx) := target
    activeCond(idx) := kind === BoundaryKind.Cond
    activeUnconditionalRedirect(idx) :=
      kind === BoundaryKind.Direct || kind === BoundaryKind.Call
  }

  private def installScalar(idx: Int, bid: UInt): Unit = {
    activeValid(idx) := true.B
    activeBid(idx) := bid
    activeTarget(idx) := 0.U
    activeCond(idx) := false.B
    activeUnconditionalRedirect(idx) := false.B
  }

  val decodeStidMatch = matchesStid(io.decodeStid)
  val decodeStidInRange = stidInRange(decodeStidMatch)
  val queryStidMatch = matchesStid(io.queryStid)
  val queryStidInRange = stidInRange(queryStidMatch)
  val scalarRedirectStidMatch = matchesStid(io.scalarRedirectStid)
  val scalarRedirectStidInRange = stidInRange(scalarRedirectStidMatch)

  val decodeActiveValid = decodeStidInRange && selectBool(activeValid, decodeStidMatch)
  val decodeActiveBid = selectUInt(activeBid, decodeStidMatch)
  val queryActiveValid = queryStidInRange && selectBool(activeValid, queryStidMatch)
  val queryActiveBid = selectUInt(activeBid, queryStidMatch)
  val queryActiveTarget = selectUInt(activeTarget, queryStidMatch)
  val queryActiveCond = queryStidInRange && selectBool(activeCond, queryStidMatch)
  val queryActiveUnconditionalRedirect =
    queryStidInRange && selectBool(activeUnconditionalRedirect, queryStidMatch)

  val decodeMarker = io.decodeBoundary || io.decodeStop
  val decodeScalarFire = io.decodeFire && !decodeMarker
  val decodeUsesExistingBlock = io.decodeValid && decodeActiveValid && !io.decodeBoundary
  val decodeStartsNewBlock =
    io.decodeFire && decodeStidInRange && (io.decodeBoundary || (decodeScalarFire && !decodeActiveValid))
  val decodeClosesActive =
    io.decodeFire && decodeStidInRange && decodeActiveValid && (io.decodeBoundary || io.decodeStop)

  val decodeBoundaryFire = io.decodeFire && decodeStidInRange && io.decodeBoundary
  val decodeStopFire = io.decodeFire && decodeStidInRange && io.decodeStop && !io.decodeBoundary
  val decodeScalarStartFire = decodeScalarFire && decodeStidInRange && !decodeActiveValid
  val robBlockLastClearsActive =
    VecInit((0 until stidCount).map(idx =>
      io.robBlockLastValid && activeValid(idx) && io.robBlockLastBid === activeBid(idx)))

  io.decodeStidInRange := decodeStidInRange
  io.decodeActiveValid := decodeActiveValid
  io.decodeActiveBid := decodeActiveBid
  io.decodeBlockBid := Mux(decodeUsesExistingBlock, decodeActiveBid, io.decodeAllocBid)
  io.decodeUsesExistingBlock := decodeUsesExistingBlock
  io.decodeStartsNewBlock := decodeStartsNewBlock
  io.decodeClosesActive := decodeClosesActive
  io.decodeStopWithoutActive := io.decodeFire && io.decodeStop && !io.decodeBoundary && !decodeActiveValid

  io.activeValid := queryActiveValid
  io.activeBid := queryActiveBid
  io.activeTarget := queryActiveTarget
  io.activeCond := queryActiveCond
  io.activeUnconditionalRedirect := queryActiveUnconditionalRedirect

  when(io.flushValid) {
    for (idx <- 0 until stidCount) {
      clearLane(idx)
    }
  }.elsewhen(io.scalarRedirectValid && scalarRedirectStidInRange) {
    for (idx <- 0 until stidCount) {
      when(scalarRedirectStidMatch(idx)) {
        clearLane(idx)
      }
    }
  }.elsewhen(decodeBoundaryFire) {
    for (idx <- 0 until stidCount) {
      when(decodeStidMatch(idx)) {
        installBoundary(idx, io.decodeAllocBid, io.decodeTarget, io.decodeBoundaryKind)
      }
    }
  }.elsewhen(decodeStopFire) {
    for (idx <- 0 until stidCount) {
      when(decodeStidMatch(idx)) {
        clearLane(idx)
      }
    }
  }.elsewhen(decodeScalarStartFire) {
    for (idx <- 0 until stidCount) {
      when(decodeStidMatch(idx)) {
        installScalar(idx, io.decodeAllocBid)
      }
    }
  }.elsewhen(robBlockLastClearsActive.asUInt.orR) {
    for (idx <- 0 until stidCount) {
      when(robBlockLastClearsActive(idx)) {
        clearLane(idx)
      }
    }
  }
}
