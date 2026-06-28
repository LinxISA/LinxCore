package linxcore.recovery

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object FullBidRecoveryBridgeReference {
  final case class RobId(valid: Boolean, wrap: Boolean, value: Int)
  final case class BridgeResult(
      blockFlushValid: Boolean,
      blockFlushBid: BigInt,
      robBid: RobId,
      rid: RobId,
      baseOnBid: Boolean)

  sealed trait RefFlushType
  case object MissPredFlush extends RefFlushType
  case object PeReplay extends RefFlushType
  case object NukeFlush extends RefFlushType
  case object InnerFlush extends RefFlushType
  case object FastReplay extends RefFlushType
  case object FastFlush extends RefFlushType
  case object SimtInnerFlush extends RefFlushType

  private def slotBits(entries: Int): Int = {
    require(entries > 1 && (entries & (entries - 1)) == 0)
    Integer.numberOfTrailingZeros(entries)
  }

  def fullBidToRobId(blockBid: BigInt, valid: Boolean, entries: Int): RobId = {
    val slotMask = BigInt(entries - 1)
    val uniq = blockBid >> slotBits(entries)
    RobId(
      valid = valid,
      wrap = (uniq & 1) == 1,
      value = (blockBid & slotMask).toInt
    )
  }

  def isBaseOnBid(typ: RefFlushType, fetchTpcValid: Boolean = false): Boolean =
    typ == MissPredFlush ||
      typ == NukeFlush ||
      typ == FastReplay ||
      typ == FastFlush ||
      (typ == PeReplay && !fetchTpcValid)

  def bridge(
      typ: RefFlushType,
      blockBid: BigInt,
      valid: Boolean,
      entries: Int,
      rid: RobId = RobId(valid = true, wrap = false, value = 0),
      fetchTpcValid: Boolean = false): BridgeResult =
    BridgeResult(
      blockFlushValid = valid,
      blockFlushBid = blockBid,
      robBid = fullBidToRobId(blockBid, valid, entries),
      rid = rid,
      baseOnBid = isBaseOnBid(typ, fetchTpcValid)
    )
}

class FullBidRecoveryBridgeSpec extends AnyFunSuite {
  import FullBidRecoveryBridgeReference._

  test("full hardware BID maps slot bits and low uniqueness bit into ROBID") {
    val mapped = Seq(0, 7, 8, 15, 16, 23, 24).map(bid => bid -> fullBidToRobId(bid, valid = true, entries = 8)).toMap

    assert(mapped(0) == RobId(valid = true, wrap = false, value = 0))
    assert(mapped(7) == RobId(valid = true, wrap = false, value = 7))
    assert(mapped(8) == RobId(valid = true, wrap = true, value = 0))
    assert(mapped(15) == RobId(valid = true, wrap = true, value = 7))
    assert(mapped(16) == RobId(valid = true, wrap = false, value = 0))
    assert(mapped(23) == RobId(valid = true, wrap = false, value = 7))
    assert(mapped(24) == RobId(valid = true, wrap = true, value = 0))
  }

  test("base-on-BID request preserves full block BID while producing ring ROB BID") {
    val result = bridge(MissPredFlush, blockBid = BigInt(0x25), valid = true, entries = 16)

    assert(result.blockFlushValid)
    assert(result.blockFlushBid == BigInt(0x25))
    assert(result.robBid == RobId(valid = true, wrap = false, value = 5))
    assert(result.baseOnBid)
  }

  test("non-base inner flush keeps RID as a separate ring sub-ID") {
    val rid = RobId(valid = true, wrap = false, value = 3)
    val result = bridge(
      typ = InnerFlush,
      blockBid = BigInt(14),
      valid = true,
      entries = 8,
      rid = rid
    )

    assert(!result.baseOnBid)
    assert(result.robBid == RobId(valid = true, wrap = true, value = 6))
    assert(result.rid == rid)
    assert(result.robBid != result.rid)
  }

  test("Chisel FullBidRecoveryBridge elaborates full-BID and ROB flush surfaces") {
    val sv = ChiselStage.emitSystemVerilog(new FullBidRecoveryBridge(entries = 8, bidWidth = 16))

    assert(sv.contains("module FullBidRecoveryBridge"))
    assert(sv.contains("io_req_blockBid"))
    assert(sv.contains("io_blockFlushBid"))
    assert(sv.contains("io_robBid"))
    assert(sv.contains("io_robFlush_baseOnBid") || sv.contains("baseOnBid"))
  }
}
