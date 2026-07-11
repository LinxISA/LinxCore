package linxcore.bctrl

import chisel3._
import chisel3.util.log2Ceil

class BIDRingOrderIO(val entries: Int, val stidWidth: Int) extends Bundle {
  private val bidWidth = log2Ceil(entries)
  val candidateStid = Input(UInt(stidWidth.W))
  val candidateBid = Input(UInt(bidWidth.W))
  val pivotStid = Input(UInt(stidWidth.W))
  val pivotBid = Input(UInt(bidWidth.W))
  val headBid = Input(UInt(bidWidth.W))
  val sameRing = Output(Bool())
  val candidateYounger = Output(Bool())
  val killOnFlush = Output(Bool())
}

class BIDRingOrder(val entries: Int = 256, val stidWidth: Int = 8) extends Module {
  require(entries > 1 && (entries & (entries - 1)) == 0, "BROB entries must be a power of two")
  require(stidWidth > 0, "STID width must be positive")

  private val bidWidth = log2Ceil(entries)
  val io = IO(new BIDRingOrderIO(entries, stidWidth))

  private def distanceFromHead(bid: UInt): UInt =
    (bid - io.headBid)(bidWidth - 1, 0)

  val candidateDistance = distanceFromHead(io.candidateBid)
  val pivotDistance = distanceFromHead(io.pivotBid)
  io.sameRing := io.candidateStid === io.pivotStid
  io.candidateYounger := candidateDistance > pivotDistance
  io.killOnFlush := io.sameRing && io.candidateYounger
}
