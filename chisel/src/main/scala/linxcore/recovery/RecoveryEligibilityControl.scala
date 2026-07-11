package linxcore.recovery

import chisel3._
import linxcore.rob.ROBID

class RecoveryEligibilityControlIO(
    val entries: Int,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8)
    extends Bundle {
  val request = Input(new FlushBus(entries, peIdWidth, stidWidth, tidWidth))
  val oldestValid = Input(Bool())
  val oldestBid = Input(new ROBID(entries))
  val oldestRid = Input(new ROBID(entries))
  val eligible = Output(Bool())
  val blockedByNoOldest = Output(Bool())
  val blockedByAge = Output(Bool())
}

class RecoveryEligibilityControl(
    val entries: Int = 16,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8)
    extends Module {
  val io = IO(new RecoveryEligibilityControlIO(entries, peIdWidth, stidWidth, tidWidth))

  val ageEligible = Mux(
    io.request.baseOnBid,
    ROBID.lessEqual(io.request.req.bid, io.oldestBid),
    FlushControl.lessEqualBidRid(
      io.request.req.bid,
      io.request.req.rid,
      io.oldestBid,
      io.oldestRid
    )
  )
  val immediate = io.request.req.immediateFlush
  val orderedEligible = io.oldestValid && ageEligible

  io.eligible := io.request.req.valid && (immediate || orderedEligible)
  io.blockedByNoOldest := io.request.req.valid && !immediate && !io.oldestValid
  io.blockedByAge := io.request.req.valid && !immediate && io.oldestValid && !ageEligible
}
