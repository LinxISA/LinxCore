package linxcore.backend

import circt.stage.ChiselStage
import linxcore.commit.CommitTraceParams
import org.scalatest.funsuite.AnyFunSuite

object DispatchROBAllocatorReference {
  final case class Row(bid: Int, gid: Int, rid: Int)
  final case class AllocResult(accepted: Boolean, blockBid: BigInt, robValue: Int)

  final class Model(entries: Int) {
    require(entries > 1 && (entries & (entries - 1)) == 0)

    private val blockLive = Array.fill(entries)(false)
    private val robRows = Array.fill[Option[Row]](entries)(None)
    private var blockSlot = 0
    private var blockUniq = BigInt(0)
    private var robAlloc = 0
    private var robSize = 0

    private def slotBits: Int =
      Integer.numberOfTrailingZeros(entries)

    private def makeBid: BigInt =
      (blockUniq << slotBits) | blockSlot

    private def duplicate(row: Row): Boolean =
      robRows.flatten.contains(row)

    private def advanceBlock(): Unit =
      if (blockSlot == entries - 1) {
        blockSlot = 0
        blockUniq += 1
      } else {
        blockSlot += 1
      }

    private def advanceRob(): Unit =
      robAlloc = (robAlloc + 1) & (entries - 1)

    def nextBlockBid: BigInt =
      makeBid

    def alloc(row: Row): AllocResult = {
      val bid = makeBid
      val slot = (bid & (entries - 1)).toInt
      val robValue = robAlloc
      val ready = !blockLive(slot) && robSize != entries && !duplicate(row)
      if (ready) {
        blockLive(slot) = true
        robRows(robAlloc) = Some(row)
        advanceBlock()
        advanceRob()
        robSize += 1
      }
      AllocResult(ready, bid, robValue)
    }

    def blockAllocatedMask: BigInt =
      blockLive.zipWithIndex.foldLeft(BigInt(0)) { case (mask, (live, idx)) =>
        if (live) mask | (BigInt(1) << idx) else mask
      }

    def robOccupiedMask: BigInt =
      robRows.zipWithIndex.foldLeft(BigInt(0)) { case (mask, (row, idx)) =>
        if (row.nonEmpty) mask | (BigInt(1) << idx) else mask
      }

    def freeBlockSlot(slot: Int): Unit =
      blockLive(slot) = false
  }
}

class DispatchROBAllocatorSpec extends AnyFunSuite {
  import DispatchROBAllocatorReference._

  test("reference allocates BROB BID and ROB RID atomically") {
    val model = new Model(entries = 4)
    val first = model.alloc(Row(bid = 1, gid = 0, rid = 10))
    val second = model.alloc(Row(bid = 1, gid = 0, rid = 11))

    assert(first == AllocResult(accepted = true, blockBid = 0, robValue = 0))
    assert(second == AllocResult(accepted = true, blockBid = 1, robValue = 1))
    assert(model.blockAllocatedMask == 0x3)
    assert(model.robOccupiedMask == 0x3)
  }

  test("reference BID cursor wraps through uniqueness bits") {
    val model = new Model(entries = 4)
    assert((0 until 4).map(i => model.alloc(Row(1, 0, i)).blockBid) == Seq(0, 1, 2, 3))
    assert(model.nextBlockBid == 4)
  }

  test("reference holds both allocators when BROB slot is occupied") {
    val model = new Model(entries = 2)
    assert(model.alloc(Row(1, 0, 0)).accepted)
    assert(model.alloc(Row(1, 0, 1)).accepted)

    val blocked = model.alloc(Row(1, 0, 2))
    assert(!blocked.accepted)
    assert(blocked.blockBid == 2)
    assert(blocked.robValue == 0)
    assert(model.nextBlockBid == 2)
  }

  test("reference holds both allocators on duplicate ROB identity") {
    val model = new Model(entries = 4)
    assert(model.alloc(Row(1, 0, 7)).accepted)
    model.freeBlockSlot(1)

    val duplicate = model.alloc(Row(1, 0, 7))
    assert(!duplicate.accepted)
    assert(duplicate.blockBid == 1)
    assert(duplicate.robValue == 1)
    assert(model.nextBlockBid == 1)
    assert(model.blockAllocatedMask == 0x1)
    assert(model.robOccupiedMask == 0x1)
  }

  test("Chisel DispatchROBAllocator elaborates BROB-to-ROB allocation wiring") {
    val sv = ChiselStage.emitSystemVerilog(
      new DispatchROBAllocator(
        entries = 8,
        traceParams = CommitTraceParams(commitWidth = 2, robValueWidth = 3)
      )
    )

    assert(sv.contains("module DispatchROBAllocator"))
    assert(sv.contains("BrobMetaTracker"))
    assert(sv.contains("ROBEntryBank"))
    assert(sv.contains("io_allocBlockBid"))
    assert(sv.contains("io_allocGid"))
    assert(sv.contains("io_allocIsLast"))
    assert(sv.contains("io_allocTSeq_value"))
    assert(sv.contains("io_robTULinkSource_tSeq_value"))
    assert(sv.contains("io_deallocTURetireSource_0_tSeq_value"))
    assert(sv.contains("io_deallocBlockLastValid"))
    assert(sv.contains("io_deallocBlockLastBid_value"))
    assert(sv.contains("io_robTULinkSourceMatched"))
    assert(sv.contains("io_blockAllocatedMask"))
    assert(sv.contains("io_commitContractError"))
  }
}
