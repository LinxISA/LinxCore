package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object ReducedLoadReplayCompletionDrainReference {
  import ReducedLoadWaitReplaySlotReference.{Id, Relaunch}

  final case class Completion(
      valid: Boolean = true,
      memLoad: Boolean = true,
      pc: BigInt,
      addr: BigInt,
      size: Int,
      bid: Id,
      lsId: Id)
  final case class Result(
      consumeReady: Boolean,
      matchValid: Boolean,
      mismatch: Boolean,
      pcMismatch: Boolean,
      addrMismatch: Boolean,
      sizeMismatch: Boolean,
      bidMismatch: Boolean,
      lsIdMismatch: Boolean)

  def step(candidate: Option[Relaunch], completion: Completion): Result = {
    val comparable = candidate.isDefined && completion.valid && completion.memLoad
    val pcMismatch = comparable && candidate.get.pc != completion.pc
    val addrMismatch = comparable && candidate.get.addr != completion.addr
    val sizeMismatch = comparable && candidate.get.size != completion.size
    val bidMismatch = comparable && candidate.get.bid != completion.bid
    val lsIdMismatch = comparable && candidate.get.lsId != completion.lsId
    val mismatch = pcMismatch || addrMismatch || sizeMismatch || bidMismatch || lsIdMismatch
    val matchValid = comparable && !mismatch
    Result(
      consumeReady = matchValid,
      matchValid = matchValid,
      mismatch = comparable && mismatch,
      pcMismatch = pcMismatch,
      addrMismatch = addrMismatch,
      sizeMismatch = sizeMismatch,
      bidMismatch = bidMismatch,
      lsIdMismatch = lsIdMismatch)
  }

  def id(value: Int, wrap: Boolean = false): Id =
    Id(valid = true, wrap = wrap, value = value)

  def candidate: Relaunch =
    Relaunch(pc = 0x4000, addr = 0x1008, size = 8, bid = id(6), lsId = id(3))

  def completion: Completion =
    Completion(pc = 0x4000, addr = 0x1008, size = 8, bid = id(6), lsId = id(3))
}

class ReducedLoadReplayCompletionDrainSpec extends AnyFunSuite {
  import ReducedLoadReplayCompletionDrainReference._

  test("matching load completion consumes the queued replay candidate") {
    val result = step(Some(candidate), completion)

    assert(result.matchValid)
    assert(result.consumeReady)
    assert(!result.mismatch)
  }

  test("non-load completion leaves the candidate pending without mismatch") {
    val result = step(Some(candidate), completion.copy(memLoad = false))

    assert(!result.matchValid)
    assert(!result.consumeReady)
    assert(!result.mismatch)
  }

  test("mismatched load completion reports identity differences without consuming") {
    val result = step(Some(candidate), completion.copy(addr = 0x1010, lsId = id(4)))

    assert(!result.matchValid)
    assert(!result.consumeReady)
    assert(result.mismatch)
    assert(result.addrMismatch)
    assert(result.lsIdMismatch)
    assert(!result.pcMismatch)
  }

  test("absent candidate ignores load completions") {
    val result = step(None, completion)

    assert(!result.matchValid)
    assert(!result.consumeReady)
    assert(!result.mismatch)
  }

  test("Chisel ReducedLoadReplayCompletionDrain elaborates match diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new ReducedLoadReplayCompletionDrain(idEntries = 8))

    assert(sv.contains("module ReducedLoadReplayCompletionDrain"))
    assert(sv.contains("io_consumeReady"))
    assert(sv.contains("io_matchValid"))
    assert(sv.contains("io_lsIdMismatch"))
  }
}
