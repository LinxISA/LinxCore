package linxcore.recovery

import chisel3._

import linxcore.bctrl.BID
import linxcore.rob.{ROBFullBidLookupRequest, ROBFullBidLookupResult, ROBID}

class RingFullBidRecoveryBridgeIO(
    val entries: Int,
    val bidWidth: Int = BID.DefaultWidth,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val lsidWidth: Int = 32)
    extends Bundle {
  val ringReq = Input(new FlushBus(entries, peIdWidth, stidWidth, tidWidth, lsidWidth))
  val lookupRequest = Output(new ROBFullBidLookupRequest(entries, peIdWidth, stidWidth, tidWidth))
  val lookupResult = Input(new ROBFullBidLookupResult(
    entries,
    bidWidth,
    peIdWidth,
    stidWidth,
    tidWidth
  ))
  val fullReq = Output(new FullBidFlushReq(
    entries, bidWidth, peIdWidth, stidWidth, tidWidth, lsidWidth))
  val matched = Output(Bool())
  val blockedByLookupMiss = Output(Bool())
  val blockedByStaleResult = Output(Bool())
  val blockedByRingProjection = Output(Bool())
}

class RingFullBidRecoveryBridge(
    val entries: Int = 16,
    val bidWidth: Int = BID.DefaultWidth,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val lsidWidth: Int = 32)
    extends Module {
  val io = IO(new RingFullBidRecoveryBridgeIO(
    entries,
    bidWidth,
    peIdWidth,
    stidWidth,
    tidWidth,
    lsidWidth
  ))

  io.lookupRequest.valid := io.ringReq.req.valid
  io.lookupRequest.bid := io.ringReq.req.bid
  io.lookupRequest.gid := io.ringReq.req.gid
  io.lookupRequest.rid := io.ringReq.req.rid
  io.lookupRequest.peId := io.ringReq.req.peId
  io.lookupRequest.stid := io.ringReq.req.stid
  io.lookupRequest.tid := io.ringReq.req.tid

  private def sameId(lhs: ROBID, rhs: ROBID): Bool =
    (lhs.valid === rhs.valid) && ROBID.equal(lhs, rhs)

  val echoed = io.lookupResult.request
  val echoMatched =
    (echoed.valid === io.lookupRequest.valid) &&
      sameId(echoed.bid, io.lookupRequest.bid) &&
      sameId(echoed.gid, io.lookupRequest.gid) &&
      sameId(echoed.rid, io.lookupRequest.rid) &&
      (echoed.peId === io.lookupRequest.peId) &&
      (echoed.stid === io.lookupRequest.stid) &&
      (echoed.tid === io.lookupRequest.tid)
  val projectedBid = FullBidRecoveryBridge.fullBidToRobId(
    io.lookupResult.blockBid,
    io.lookupResult.matched,
    entries,
    bidWidth
  )
  val projectionMatched = sameId(projectedBid, io.ringReq.req.bid)
  val matched =
    io.ringReq.req.valid &&
      io.lookupResult.matched &&
      io.lookupResult.blockBidValid &&
      echoMatched &&
      projectionMatched

  io.fullReq := 0.U.asTypeOf(io.fullReq)
  io.fullReq.valid := matched
  io.fullReq.typ := io.ringReq.req.typ
  io.fullReq.peId := io.ringReq.req.peId
  io.fullReq.tid := io.ringReq.req.tid
  io.fullReq.stid := io.ringReq.req.stid
  io.fullReq.blockBid := io.lookupResult.blockBid
  io.fullReq.gid := io.ringReq.req.gid
  io.fullReq.rid := io.ringReq.req.rid
  io.fullReq.lsId := io.ringReq.req.lsId
  io.fullReq.lsIdFullValid := io.ringReq.req.lsIdFullValid
  io.fullReq.lsIdFull := io.ringReq.req.lsIdFull
  io.fullReq.execEngine := io.ringReq.req.execEngine
  io.fullReq.fetchTpcValid := io.ringReq.req.fetchTpcValid
  io.fullReq.fetchTpc := io.ringReq.req.fetchTpc
  io.fullReq.immediateFlush := io.ringReq.req.immediateFlush

  io.matched := matched
  io.blockedByLookupMiss :=
    io.ringReq.req.valid && echoMatched && !io.lookupResult.matched
  io.blockedByStaleResult := io.ringReq.req.valid && !echoMatched
  io.blockedByRingProjection :=
    io.ringReq.req.valid && io.lookupResult.matched && echoMatched && !projectionMatched
}
