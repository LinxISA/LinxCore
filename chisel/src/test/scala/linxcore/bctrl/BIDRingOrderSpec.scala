package linxcore.bctrl

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object BIDRingOrderReference {
  def distance(bid: Int, head: Int, entries: Int): Int =
    (bid - head) & (entries - 1)

  def kill(candidateStid: Int, candidateBid: Int, pivotStid: Int, pivotBid: Int, headBid: Int, entries: Int): Boolean =
    candidateStid == pivotStid && distance(candidateBid, headBid, entries) > distance(pivotBid, headBid, entries)
}

class BIDRingOrderSpec extends AnyFunSuite {
  import BIDRingOrderReference._

  test("default 256-entry ring uses an 8-bit BID") {
    val io = new BIDRingOrderIO(entries = 256, stidWidth = 8)
    assert(io.candidateBid.getWidth == 8)
    assert(io.pivotBid.getWidth == 8)
  }

  test("ring distance orders wrapped BIDs relative to the current head") {
    assert(kill(1, 2, 1, 255, headBid = 250, entries = 256))
    assert(!kill(1, 252, 1, 255, headBid = 250, entries = 256))
  }

  test("same BID on another STID is isolated") {
    assert(!kill(2, 2, 1, 255, headBid = 250, entries = 256))
  }

  test("Chisel BIDRingOrder elaborates explicit ring context") {
    val sv = ChiselStage.emitSystemVerilog(new BIDRingOrder())
    assert(sv.contains("module BIDRingOrder"))
    assert(sv.contains("candidateStid"))
    assert(sv.contains("headBid"))
    assert(sv.contains("killOnFlush"))
  }
}
