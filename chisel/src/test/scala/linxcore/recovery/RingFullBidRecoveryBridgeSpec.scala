package linxcore.recovery

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object RingFullBidRecoveryBridgeReference {
  final case class Id(valid: Boolean = true, wrap: Boolean = false, value: Int = 0)
  final case class Key(
      valid: Boolean = true,
      bid: Id,
      gid: Id,
      rid: Id,
      peId: Int,
      stid: Int,
      tid: Int)

  def project(fullBid: BigInt, entries: Int): Id = {
    val slotBits = Integer.numberOfTrailingZeros(entries)
    Id(wrap = (((fullBid >> slotBits) & 1) == 1), value = (fullBid & (entries - 1)).toInt)
  }

  def promotes(
      request: Key,
      echo: Key,
      resultMatched: Boolean,
      blockBidValid: Boolean,
      blockBid: BigInt,
      entries: Int): Boolean =
    request.valid && resultMatched && blockBidValid && request == echo &&
      project(blockBid, entries) == request.bid
}

class RingFullBidRecoveryBridgeSpec extends AnyFunSuite {
  import RingFullBidRecoveryBridgeReference._

  test("exact echoed lookup promotes ring recovery with full uniqueness") {
    val entries = 8
    val fullBid = BigInt(0x15)
    val key = Key(
      bid = project(fullBid, entries),
      gid = Id(value = 2),
      rid = Id(wrap = true, value = 3),
      peId = 4,
      stid = 5,
      tid = 6
    )
    assert(promotes(key, key, resultMatched = true, blockBidValid = true, fullBid, entries))
  }

  test("stale echo and ring projection mismatch block promotion") {
    val entries = 8
    val fullBid = BigInt(0x15)
    val key = Key(
      bid = project(fullBid, entries),
      gid = Id(value = 2),
      rid = Id(wrap = true, value = 3),
      peId = 4,
      stid = 5,
      tid = 6
    )
    assert(!promotes(key, key.copy(stid = 7), resultMatched = true, blockBidValid = true, fullBid, entries))
    assert(!promotes(
      key,
      key.copy(rid = key.rid.copy(valid = false)),
      resultMatched = true,
      blockBidValid = true,
      fullBid,
      entries))
    assert(!promotes(key, key, resultMatched = true, blockBidValid = true, fullBid + 1, entries))
    assert(!promotes(key, key, resultMatched = false, blockBidValid = false, fullBid, entries))
  }

  test("Chisel bridge elaborates lookup and full-BID recovery surfaces") {
    val sv = ChiselStage.emitSystemVerilog(new RingFullBidRecoveryBridge(entries = 8))
    assert(sv.contains("module RingFullBidRecoveryBridge"))
    assert(sv.contains("io_lookupRequest_bid_value"))
    assert(sv.contains("io_lookupResult_blockBid"))
    assert(sv.contains("io_fullReq_blockBid"))
    assert(sv.contains("io_blockedByStaleResult"))
    assert(sv.contains("io_blockedByRingProjection"))
  }
}
