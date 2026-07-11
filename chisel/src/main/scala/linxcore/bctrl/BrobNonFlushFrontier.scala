package linxcore.bctrl

import chisel3._
import chisel3.util.{PopCount, log2Ceil}

class BrobNonFlushFrontierIO(
    val entries: Int,
    val bidWidth: Int,
    val stidCount: Int)
    extends Bundle {
  private val countWidth = log2Ceil(entries + 1)

  val orderHeadBid = Input(Vec(stidCount, UInt(bidWidth.W)))
  val orderLiveCount = Input(Vec(stidCount, UInt(countWidth.W)))
  val rowAllocated = Input(Vec(stidCount, Vec(entries, Bool())))
  val rowBid = Input(Vec(stidCount, Vec(entries, UInt(bidWidth.W))))
  val rowSafe = Input(Vec(stidCount, Vec(entries, Bool())))

  val valid = Output(Vec(stidCount, Bool()))
  val headBid = Output(Vec(stidCount, UInt(bidWidth.W)))
  val frontierBid = Output(Vec(stidCount, UInt(bidWidth.W)))
  val prefixCount = Output(Vec(stidCount, UInt(countWidth.W)))
  val blockedValid = Output(Vec(stidCount, Bool()))
  val blockedBid = Output(Vec(stidCount, UInt(bidWidth.W)))
}

/** Finds the exact consecutive strong-non-flush prefix in every STID ring. */
class BrobNonFlushFrontier(
    val entries: Int = 16,
    val bidWidth: Int = BID.DefaultWidth,
    val stidCount: Int = 1)
    extends Module {
  require(entries > 1 && (entries & (entries - 1)) == 0,
    "BROB non-flush entries must be a power of two")
  require(bidWidth > log2Ceil(entries), "BROB non-flush BID must include uniqueness bits")
  require(stidCount > 0, "BROB non-flush frontier must track at least one STID")

  private val countWidth = log2Ceil(entries + 1)
  val io = IO(new BrobNonFlushFrontierIO(entries, bidWidth, stidCount))

  for (stid <- 0 until stidCount) {
    val prefixOpen = Wire(Vec(entries + 1, Bool()))
    val accepted = Wire(Vec(entries, Bool()))
    prefixOpen(0) := true.B

    for (offset <- 0 until entries) {
      val candidateBid = io.orderHeadBid(stid) + offset.U
      val slot = BID.slot(candidateBid, entries)
      val resident =
        offset.U < io.orderLiveCount(stid) &&
          io.rowAllocated(stid)(slot) &&
          io.rowBid(stid)(slot) === candidateBid
      accepted(offset) := prefixOpen(offset) && resident && io.rowSafe(stid)(slot)
      prefixOpen(offset + 1) := accepted(offset)
    }

    val count = PopCount(accepted)
    val hasPrefix = count =/= 0.U
    io.valid(stid) := hasPrefix
    io.headBid(stid) := io.orderHeadBid(stid)
    io.prefixCount(stid) := count
    io.frontierBid(stid) := Mux(
      hasPrefix,
      io.orderHeadBid(stid) + count.pad(bidWidth) - 1.U,
      0.U)
    io.blockedValid(stid) := count < io.orderLiveCount(stid)
    io.blockedBid(stid) := Mux(
      io.blockedValid(stid),
      io.orderHeadBid(stid) + count.pad(bidWidth),
      0.U)
  }
}
