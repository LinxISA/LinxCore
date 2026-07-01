package linxcore.bctrl

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object BlockScalarDoneSequencerReference {
  final case class Output(
      scalarDoneValid: Boolean,
      scalarDoneBid: BigInt,
      retireValid: Boolean,
      retireBid: BigInt,
      retirePending: Boolean)

  final class State {
    private var pending = false
    private var pendingBid = BigInt(0)

    def step(inValid: Boolean, inBid: BigInt, flush: Boolean = false): Output = {
      val out = Output(
        scalarDoneValid = inValid,
        scalarDoneBid = inBid,
        retireValid = pending,
        retireBid = pendingBid,
        retirePending = pending)

      if (flush) {
        pending = false
        pendingBid = 0
      } else if (inValid) {
        pending = true
        pendingBid = inBid
      } else if (pending) {
        pending = false
        pendingBid = 0
      }

      out
    }
  }
}

class BlockScalarDoneSequencerSpec extends AnyFunSuite {
  import BlockScalarDoneSequencerReference._

  test("reference forwards scalar-done immediately and retires the same BID one cycle later") {
    val state = new State

    val idle = state.step(inValid = false, inBid = 0)
    assert(!idle.scalarDoneValid)
    assert(!idle.retireValid)

    val scalarDone = state.step(inValid = true, inBid = 0x123)
    assert(scalarDone.scalarDoneValid)
    assert(scalarDone.scalarDoneBid == 0x123)
    assert(!scalarDone.retireValid)

    val retire = state.step(inValid = false, inBid = 0)
    assert(!retire.scalarDoneValid)
    assert(retire.retireValid)
    assert(retire.retireBid == 0x123)
    assert(retire.retirePending)

    val clear = state.step(inValid = false, inBid = 0)
    assert(!clear.scalarDoneValid)
    assert(!clear.retireValid)
  }

  test("reference preserves the old retire pulse while accepting a new scalar-done BID") {
    val state = new State

    state.step(inValid = true, inBid = 0x10)
    val overlap = state.step(inValid = true, inBid = 0x20)
    assert(overlap.scalarDoneValid)
    assert(overlap.scalarDoneBid == 0x20)
    assert(overlap.retireValid)
    assert(overlap.retireBid == 0x10)

    val nextRetire = state.step(inValid = false, inBid = 0)
    assert(nextRetire.retireValid)
    assert(nextRetire.retireBid == 0x20)
  }

  test("reference flush clears pending state after the current retire observation") {
    val state = new State

    state.step(inValid = true, inBid = 0x33)
    val flushedRetire = state.step(inValid = false, inBid = 0, flush = true)
    assert(flushedRetire.retireValid)
    assert(flushedRetire.retireBid == 0x33)

    val clear = state.step(inValid = false, inBid = 0)
    assert(!clear.retireValid)
  }

  test("BlockScalarDoneSequencer elaborates the scalar-done and retire ports") {
    val sv = ChiselStage.emitSystemVerilog(new BlockScalarDoneSequencer(bidWidth = 64))

    assert(sv.contains("module BlockScalarDoneSequencer"))
    assert(sv.contains("io_scalarDoneValid"))
    assert(sv.contains("io_scalarDoneBid"))
    assert(sv.contains("io_retireValid"))
    assert(sv.contains("io_retireBid"))
    assert(sv.contains("io_retirePending"))
  }
}
