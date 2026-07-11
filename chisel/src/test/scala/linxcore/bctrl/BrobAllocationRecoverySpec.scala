package linxcore.bctrl

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object BrobAllocationRecoveryReference {
  final class State(val bidWidth: Int, val stidCount: Int) {
    private val mask = (BigInt(1) << bidWidth) - 1
    private val cursor = Array.fill(stidCount)(BigInt(0))

    def next(stid: Int): BigInt = cursor(stid)

    def step(
        advance: Option[Int] = None,
        recovery: Option[(Int, BigInt, Boolean)] = None): Unit = {
      for (stid <- 0 until stidCount) {
        recovery match {
          case Some((lane, pivot, inclusive)) if lane == stid =>
            cursor(stid) = (if (inclusive) pivot else pivot + 1) & mask
          case _ if advance.contains(stid) =>
            cursor(stid) = (cursor(stid) + 1) & mask
          case _ =>
        }
      }
    }
  }
}

class BrobAllocationRecoverySpec extends AnyFunSuite {
  import BrobAllocationRecoveryReference._

  test("per-STID allocation cursors advance independently") {
    val state = new State(bidWidth = 8, stidCount = 2)
    state.step(advance = Some(0))
    state.step(advance = Some(0))
    state.step(advance = Some(1))
    assert(state.next(0) == 2)
    assert(state.next(1) == 1)
  }

  test("miss-predict recovery restores the first killed BID inclusively") {
    val state = new State(bidWidth = 8, stidCount = 2)
    (0 until 4).foreach(_ => state.step(advance = Some(0)))
    state.step(recovery = Some((0, BigInt(2), true)))
    assert(state.next(0) == 2)
  }

  test("accepted global flush preserves its pivot and restores to the successor") {
    val state = new State(bidWidth = 8, stidCount = 2)
    (0 until 4).foreach(_ => state.step(advance = Some(0)))
    state.step(recovery = Some((0, BigInt(1), false)))
    assert(state.next(0) == 2)
  }

  test("same-lane recovery dominates allocation while another STID may advance") {
    val state = new State(bidWidth = 8, stidCount = 2)
    state.step(advance = Some(0))
    state.step(advance = Some(1), recovery = Some((0, BigInt(7), true)))
    assert(state.next(0) == 7)
    assert(state.next(1) == 1)
  }

  test("cursor wraps at the configured full-BID width") {
    val state = new State(bidWidth = 4, stidCount = 1)
    state.step(recovery = Some((0, BigInt(15), false)))
    assert(state.next(0) == 0)
  }

  test("Chisel BrobAllocationRecovery elaborates parameterized lane state") {
    val sv = ChiselStage.emitSystemVerilog(
      new BrobAllocationRecovery(bidWidth = 16, stidWidth = 2, stidCount = 2)
    )
    assert(sv.contains("module BrobAllocationRecovery"))
    assert(sv.contains("io_recoveryInclusive"))
    assert(sv.contains("io_recoveryFirstKilledBid"))
    assert(sv.contains("io_recoveryOldAllocBid"))
    assert(sv.contains("io_cursor_1"))
  }
}
