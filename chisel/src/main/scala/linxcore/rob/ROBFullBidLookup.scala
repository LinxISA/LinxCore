package linxcore.rob

import chisel3._
import chisel3.util.log2Ceil

import linxcore.bctrl.BID
import linxcore.recovery.FullBidRecoveryBridge

class ROBFullBidLookupRequest(
    val entries: Int,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8)
    extends Bundle {
  val valid = Bool()
  val bid = new ROBID(entries)
  val gid = new ROBID(entries)
  val rid = new ROBID(entries)
  val peId = UInt(peIdWidth.W)
  val stid = UInt(stidWidth.W)
  val tid = UInt(tidWidth.W)
}

class ROBFullBidLookupResult(
    val entries: Int,
    val fullBidWidth: Int = BID.DefaultWidth,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8)
    extends Bundle {
  val request = new ROBFullBidLookupRequest(entries, peIdWidth, stidWidth, tidWidth)
  val matched = Bool()
  val blockBidValid = Bool()
  val blockBid = UInt(fullBidWidth.W)
  val blockedByInvalidIdentity = Bool()
  val blockedByFree = Bool()
  val blockedByStaleRid = Bool()
  val blockedByBid = Bool()
  val blockedByGid = Bool()
  val blockedByScope = Bool()
  val blockedByMissingBlockBid = Bool()
  val blockedByRingProjection = Bool()
}

class ROBFullBidLookupIO(
    val entries: Int,
    val fullBidWidth: Int = BID.DefaultWidth,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8)
    extends Bundle {
  val request = Input(new ROBFullBidLookupRequest(entries, peIdWidth, stidWidth, tidWidth))
  val occupiedMask = Input(UInt(entries.W))
  val rowBid = Input(Vec(entries, new ROBID(entries)))
  val rowGid = Input(Vec(entries, new ROBID(entries)))
  val rowRid = Input(Vec(entries, new ROBID(entries)))
  val rowPeId = Input(Vec(entries, UInt(peIdWidth.W)))
  val rowStid = Input(Vec(entries, UInt(stidWidth.W)))
  val rowTid = Input(Vec(entries, UInt(tidWidth.W)))
  val rowBlockBidValid = Input(Vec(entries, Bool()))
  val rowBlockBid = Input(Vec(entries, UInt(fullBidWidth.W)))
  val result = Output(new ROBFullBidLookupResult(
    entries,
    fullBidWidth,
    peIdWidth,
    stidWidth,
    tidWidth
  ))
}

class ROBFullBidLookup(
    val entries: Int = 16,
    val fullBidWidth: Int = BID.DefaultWidth,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8)
    extends Module {
  require(entries > 1, "entries must be greater than one")
  require((entries & (entries - 1)) == 0, "entries must be a power of two")
  require(fullBidWidth > log2Ceil(entries), "full BID must include uniqueness bits")

  val io = IO(new ROBFullBidLookupIO(
    entries,
    fullBidWidth,
    peIdWidth,
    stidWidth,
    tidWidth
  ))

  val index = io.request.rid.value
  val identityValid =
    io.request.bid.valid && io.request.gid.valid && io.request.rid.valid
  val occupied = io.occupiedMask(index)
  val ridMatched =
    occupied && io.rowRid(index).valid && ROBID.equal(io.rowRid(index), io.request.rid)
  val bidMatched =
    ridMatched && io.rowBid(index).valid && ROBID.equal(io.rowBid(index), io.request.bid)
  val gidMatched =
    bidMatched && io.rowGid(index).valid && ROBID.equal(io.rowGid(index), io.request.gid)
  val scopeMatched =
    gidMatched &&
      (io.rowPeId(index) === io.request.peId) &&
      (io.rowStid(index) === io.request.stid) &&
      (io.rowTid(index) === io.request.tid)
  val blockBidValid = scopeMatched && io.rowBlockBidValid(index)
  val projectedBid = FullBidRecoveryBridge.fullBidToRobId(
    io.rowBlockBid(index),
    blockBidValid,
    entries,
    fullBidWidth
  )
  val ringProjectionMatched =
    blockBidValid && projectedBid.valid && io.request.bid.valid &&
      ROBID.equal(projectedBid, io.request.bid)
  val matched = io.request.valid && identityValid && ringProjectionMatched

  io.result.request := io.request
  io.result.matched := matched
  io.result.blockBidValid := matched
  io.result.blockBid := Mux(matched, io.rowBlockBid(index), 0.U)
  io.result.blockedByInvalidIdentity := io.request.valid && !identityValid
  io.result.blockedByFree := io.request.valid && identityValid && !occupied
  io.result.blockedByStaleRid :=
    io.request.valid && identityValid && occupied && !ridMatched
  io.result.blockedByBid := io.request.valid && identityValid && ridMatched && !bidMatched
  io.result.blockedByGid := io.request.valid && identityValid && bidMatched && !gidMatched
  io.result.blockedByScope := io.request.valid && identityValid && gidMatched && !scopeMatched
  io.result.blockedByMissingBlockBid :=
    io.request.valid && identityValid && scopeMatched && !io.rowBlockBidValid(index)
  io.result.blockedByRingProjection :=
    io.request.valid && identityValid && blockBidValid && !ringProjectionMatched
}
