package linxcore.recovery

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

class RecoveryCleanupROBProbeSpec extends AnyFunSuite {
  test("retained recovery arbitration selects model-oldest reports without cross-STID BID ordering") {
    final case class Req(source: Int, stid: Int, bid: Int)

    def sameStidWinner(requests: Seq[Req], oldest: Int, oldestValid: Boolean = true): Req =
      requests.reduceLeft { (winner, next) =>
        val winnerOlder = winner.stid == next.stid &&
          (winner.bid <= next.bid || (oldestValid && winner.bid == oldest))
        if (winnerOlder) winner else next
      }

    val sameLane = Seq(Req(0, stid = 0, bid = 6), Req(1, stid = 0, bid = 3), Req(2, stid = 0, bid = 5))
    assert(sameStidWinner(sameLane, oldest = 3).source == 1)

    val laneWinners = Seq(
      sameStidWinner(sameLane, oldest = 3),
      sameStidWinner(Seq(Req(3, stid = 1, bid = 7)), oldest = 7)
    )
    assert(laneWinners.map(_.stid) == Seq(0, 1))
    assert(laneWinners.head.bid == 3)
    assert(laneWinners(1).bid == 7)
    assert(sameStidWinner(Seq(Req(0, 0, 7), Req(1, 0, 3)), oldest = 7, oldestValid = false).bid == 3)
  }

  test("ring recovery remains blocked until the cleanup consumer accepts it") {
    final case class State(pending: Boolean = false, size: Int = 3)

    def step(state: State, sourceValid: Boolean, consumerReady: Boolean): State = {
      val consume = state.pending && consumerReady
      val accept = sourceValid && (!state.pending || consumerReady)
      State(
        pending = accept || (state.pending && !consume),
        size = if (consume) 1 else state.size
      )
    }

    val accepted = step(State(), sourceValid = true, consumerReady = false)
    assert(accepted.pending)
    assert(accepted.size == 3)
    val held = step(accepted, sourceValid = false, consumerReady = false)
    assert(held == accepted)
    val consumed = step(held, sourceValid = false, consumerReady = true)
    assert(!consumed.pending)
    assert(consumed.size == 1)
  }

  test("generated recovery probe elaborates full-BID lookup and real ROB prune ownership") {
    val sv = ChiselStage.emitSystemVerilog(new RecoveryCleanupROBProbe)

    assert(sv.contains("module RecoveryCleanupROBProbe"))
    assert(sv.contains("RecoveryCleanupControl"))
    assert(sv.contains("RecoveryFabric"))
    assert(sv.contains("RecoverySourceArbiter"))
    assert(sv.contains("RecoveryClassMerge"))
    assert(sv.contains("ScalarLSURecoverySource"))
    assert(sv.contains("RecoveryEligibilityControl"))
    assert(sv.contains("RingFullBidRecoveryBridge"))
    assert(sv.contains("ROBFullBidLookup"))
    assert(sv.contains("ROBEntryBank"))
    assert(sv.contains("RecoveryWatermarkJoin"))
    assert(sv.contains("io_joinedOldestValidMask"))
    assert(sv.contains("io_robFlushPruneMask"))
    assert(sv.contains("io_cleanupBlockFlushValid"))
    assert(sv.contains("io_cleanupBlockFlushBid"))
    assert(sv.contains("io_ringLookupMatched"))
    assert(sv.contains("io_arbiterPendingMask"))
    assert(sv.contains("io_recoveryPending"))
    assert(sv.contains("io_classGlobalFlushPendingMask"))
  }
}
