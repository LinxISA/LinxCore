package linxcore.lsu

import chisel3._
import chisel3.util.Mux1H

import linxcore.bctrl.BID
import linxcore.recovery.{FlushBus, FullBidFlushReq}
import linxcore.rob.{ROBFullBidLookupRequest, ROBFullBidLookupResult, ROBID}

class ScalarLSURecoveryBoundaryIO(
    val entries: Int,
    val stidCount: Int,
    val bidWidth: Int = BID.DefaultWidth,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val lsidWidth: Int = 32)
    extends Bundle {
  val ringReq = Input(new FlushBus(entries, peIdWidth, stidWidth, tidWidth, lsidWidth))
  val ringReqReady = Output(Bool())
  val oldestValid = Input(Vec(stidCount, Bool()))
  val oldestBid = Input(Vec(stidCount, new ROBID(entries)))
  val oldestRid = Input(Vec(stidCount, new ROBID(entries)))
  val fullBidLookupRequest = Output(new ROBFullBidLookupRequest(entries, peIdWidth, stidWidth, tidWidth))
  val fullBidLookup = Input(new ROBFullBidLookupResult(
    entries, bidWidth, peIdWidth, stidWidth, tidWidth))
  val source = Output(new FullBidFlushReq(
    entries, bidWidth, peIdWidth, stidWidth, tidWidth, lsidWidth))
  val sourceReady = Input(Bool())
  val sourceAccepted = Output(Bool())
  val stidInRange = Output(Bool())
  val eligible = Output(Bool())
  val blockedByNoOldest = Output(Bool())
  val blockedByAge = Output(Bool())
  val lookupMatched = Output(Bool())
  val blockedByLookupMiss = Output(Bool())
  val blockedByStaleLookup = Output(Bool())
  val blockedByRingProjection = Output(Bool())
}

/** Canonical scalar-LSU boundary from a retained ring report to central recovery.
  *
  * The report STID selects its own retirement watermark. Exact allocator-owned
  * full-BID promotion remains in [[ScalarLSURecoverySource]], and the upstream
  * queue retains the report until `ringReqReady`.
  */
class ScalarLSURecoveryBoundary(
    val entries: Int = 16,
    val stidCount: Int = 1,
    val bidWidth: Int = BID.DefaultWidth,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val lsidWidth: Int = 32)
    extends Module {
  require(stidCount > 0, "scalar LSU recovery must expose at least one STID")
  require(BigInt(stidCount) <= (BigInt(1) << stidWidth), "scalar LSU recovery STID count must fit stidWidth")

  val io = IO(new ScalarLSURecoveryBoundaryIO(
    entries, stidCount, bidWidth, peIdWidth, stidWidth, tidWidth, lsidWidth))

  val stidMatches = VecInit((0 until stidCount).map(stid => io.ringReq.req.stid === stid.U))
  val stidInRange = io.ringReq.req.valid && stidMatches.asUInt.orR
  val source = Module(new ScalarLSURecoverySource(
    entries, bidWidth, peIdWidth, stidWidth, tidWidth, lsidWidth))

  val selectedRingReq = Wire(chiselTypeOf(io.ringReq))
  selectedRingReq := io.ringReq
  selectedRingReq.req.valid := stidInRange
  source.io.ringReq := selectedRingReq
  source.io.oldestValid := stidInRange && Mux1H(stidMatches, io.oldestValid)
  source.io.oldestBid := Mux1H(stidMatches, io.oldestBid)
  source.io.oldestRid := Mux1H(stidMatches, io.oldestRid)
  source.io.fullBidLookup := io.fullBidLookup
  source.io.sourceReady := io.sourceReady

  io.ringReqReady := source.io.ringReqReady
  io.fullBidLookupRequest := source.io.fullBidLookupRequest
  io.source := source.io.source
  io.sourceAccepted := source.io.sourceAccepted
  io.stidInRange := stidInRange
  io.eligible := source.io.eligible
  io.blockedByNoOldest := source.io.blockedByNoOldest
  io.blockedByAge := source.io.blockedByAge
  io.lookupMatched := source.io.lookupMatched
  io.blockedByLookupMiss := source.io.blockedByLookupMiss
  io.blockedByStaleLookup := source.io.blockedByStaleLookup
  io.blockedByRingProjection := source.io.blockedByRingProjection
}
