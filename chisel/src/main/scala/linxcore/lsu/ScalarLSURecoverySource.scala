package linxcore.lsu

import chisel3._

import linxcore.bctrl.BID
import linxcore.recovery.{
  FlushBus,
  FullBidFlushReq,
  RecoveryEligibilityControl,
  RingFullBidRecoveryBridge
}
import linxcore.rob.{ROBFullBidLookupRequest, ROBFullBidLookupResult, ROBID}

class ScalarLSURecoverySourceIO(
    val entries: Int,
    val bidWidth: Int = BID.DefaultWidth,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val lsidWidth: Int = 32)
    extends Bundle {
  val ringReq = Input(new FlushBus(entries, peIdWidth, stidWidth, tidWidth, lsidWidth))
  val ringReqReady = Output(Bool())
  val oldestValid = Input(Bool())
  val oldestBid = Input(new ROBID(entries))
  val oldestRid = Input(new ROBID(entries))
  val fullBidLookupRequest = Output(new ROBFullBidLookupRequest(entries, peIdWidth, stidWidth, tidWidth))
  val fullBidLookup = Input(new ROBFullBidLookupResult(
    entries,
    bidWidth,
    peIdWidth,
    stidWidth,
    tidWidth
  ))
  val source = Output(new FullBidFlushReq(
    entries, bidWidth, peIdWidth, stidWidth, tidWidth, lsidWidth))
  val sourceReady = Input(Bool())
  val sourceAccepted = Output(Bool())
  val eligible = Output(Bool())
  val blockedByNoOldest = Output(Bool())
  val blockedByAge = Output(Bool())
  val lookupMatched = Output(Bool())
  val blockedByLookupMiss = Output(Bool())
  val blockedByStaleLookup = Output(Bool())
  val blockedByRingProjection = Output(Bool())
}

/** Converts one retained scalar-LSU ring report into an exact full-BID source.
  *
  * The upstream MDB queue owns retention until `ringReqReady`. This module owns
  * only LSU-specific age eligibility and allocator-stamped identity promotion;
  * competing-source arbitration and cleanup side effects are central owners.
  */
class ScalarLSURecoverySource(
    val entries: Int = 16,
    val bidWidth: Int = BID.DefaultWidth,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val lsidWidth: Int = 32)
    extends Module {
  val io = IO(new ScalarLSURecoverySourceIO(
    entries,
    bidWidth,
    peIdWidth,
    stidWidth,
    tidWidth,
    lsidWidth
  ))

  val eligibility = Module(new RecoveryEligibilityControl(
    entries, peIdWidth, stidWidth, tidWidth, lsidWidth))
  val promotion = Module(new RingFullBidRecoveryBridge(
    entries, bidWidth, peIdWidth, stidWidth, tidWidth, lsidWidth))

  eligibility.io.request := io.ringReq
  eligibility.io.oldestValid := io.oldestValid
  eligibility.io.oldestBid := io.oldestBid
  eligibility.io.oldestRid := io.oldestRid

  promotion.io.ringReq := io.ringReq
  promotion.io.lookupResult := io.fullBidLookup
  io.fullBidLookupRequest := promotion.io.lookupRequest

  io.source := promotion.io.fullReq
  io.source.valid := promotion.io.fullReq.valid && eligibility.io.eligible
  io.sourceAccepted := io.source.valid && io.sourceReady
  io.ringReqReady := io.sourceAccepted

  io.eligible := eligibility.io.eligible
  io.blockedByNoOldest := eligibility.io.blockedByNoOldest
  io.blockedByAge := eligibility.io.blockedByAge
  io.lookupMatched := promotion.io.matched
  io.blockedByLookupMiss := promotion.io.blockedByLookupMiss
  io.blockedByStaleLookup := promotion.io.blockedByStaleResult
  io.blockedByRingProjection := promotion.io.blockedByRingProjection
}
