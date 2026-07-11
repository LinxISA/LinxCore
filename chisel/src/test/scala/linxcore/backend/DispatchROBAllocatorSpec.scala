package linxcore.backend

import circt.stage.ChiselStage
import linxcore.commit.CommitTraceParams
import org.scalatest.funsuite.AnyFunSuite

object DispatchROBAllocatorReference {
  final case class Row(bid: Int, gid: Int, rid: Int)
  final case class AllocResult(accepted: Boolean, blockBid: BigInt, robValue: Int, robWrap: Boolean = false)

  final class Model(entries: Int) {
    require(entries > 1 && (entries & (entries - 1)) == 0)

    private val blockLive = Array.fill(entries)(false)
    private val robRows = Array.fill[Option[Row]](entries)(None)
    private var blockSlot = 0
    private var blockUniq = BigInt(0)
    private var robAlloc = 0
    private var robWrap = false
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
      if (robAlloc == entries - 1) {
        robAlloc = 0
        robWrap = !robWrap
      } else {
        robAlloc += 1
      }

    def nextBlockBid: BigInt =
      makeBid

    def alloc(row: Row): AllocResult = {
      val bid = makeBid
      val slot = (bid & (entries - 1)).toInt
      val robValue = robAlloc
      val allocWrap = robWrap
      val ready = !blockLive(slot) && robSize != entries && !duplicate(row)
      if (ready) {
        blockLive(slot) = true
        robRows(robAlloc) = Some(row)
        advanceBlock()
        advanceRob()
        robSize += 1
      }
      AllocResult(ready, bid, robValue, allocWrap)
    }

    def allocExistingBlock(row: Row, blockBid: BigInt): AllocResult = {
      val robValue = robAlloc
      val allocWrap = robWrap
      val ready = robSize != entries && !duplicate(row)
      if (ready) {
        robRows(robAlloc) = Some(row.copy(bid = blockBid.toInt))
        advanceRob()
        robSize += 1
      }
      AllocResult(ready, blockBid, robValue, allocWrap)
    }

    def allocBlockOnly(): AllocResult = {
      val bid = makeBid
      val slot = (bid & (entries - 1)).toInt
      val ready = !blockLive(slot)
      if (ready) {
        blockLive(slot) = true
        advanceBlock()
      }
      AllocResult(ready, bid, robAlloc, robWrap)
    }

    def renameUpdate(robValue: Int, row: Row): Boolean =
      robRows(robValue) match {
        case Some(_) =>
          robRows(robValue) = Some(row)
          true
        case None =>
          false
      }

    def rowAt(robValue: Int): Option[Row] =
      robRows(robValue)

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

  test("reference exposes ROB RID wrap when the allocation cursor returns to slot zero") {
    val model = new Model(entries = 4)
    val firstRound = (0 until 4).map(i => model.alloc(Row(1, 0, i)))

    assert(firstRound.map(r => (r.robValue, r.robWrap)) == Seq((0, false), (1, false), (2, false), (3, false)))
    assert(model.allocExistingBlock(Row(1, 0, 4), blockBid = 0).robWrap)
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

  test("reference separates decode-time allocation from rename-time row update") {
    val model = new Model(entries = 4)
    val reserved = model.alloc(Row(bid = 0, gid = 0, rid = 0))

    assert(reserved == AllocResult(accepted = true, blockBid = 0, robValue = 0))
    assert(model.renameUpdate(reserved.robValue, Row(bid = 0, gid = 0, rid = 0)))
    assert(model.rowAt(reserved.robValue).contains(Row(bid = 0, gid = 0, rid = 0)))
    assert(!model.renameUpdate(1, Row(bid = 0, gid = 0, rid = 1)))
  }

  test("reference supports marker-only BROB allocation and scalar active-BID reuse") {
    val model = new Model(entries = 4)

    val marker = model.allocBlockOnly()
    assert(marker == AllocResult(accepted = true, blockBid = 0, robValue = 0))
    assert(model.blockAllocatedMask == 0x1)
    assert(model.robOccupiedMask == 0x0)
    assert(model.nextBlockBid == 1)

    val scalar = model.allocExistingBlock(Row(bid = 99, gid = 0, rid = 0), marker.blockBid)
    assert(scalar == AllocResult(accepted = true, blockBid = 0, robValue = 0))
    assert(model.blockAllocatedMask == 0x1)
    assert(model.robOccupiedMask == 0x1)
    assert(model.nextBlockBid == 1)
    assert(model.rowAt(0).contains(Row(bid = 0, gid = 0, rid = 0)))
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
    assert(sv.contains("io_allocUsesExistingBlock"))
    assert(sv.contains("io_allocExistingBlockBid"))
    assert(sv.contains("io_allocBlockBid"))
    assert(sv.contains("io_allocRobWrap"))
    assert(sv.contains("io_blockAllocOnlyValid"))
    assert(sv.contains("io_blockAllocOnlyFire"))
    assert(sv.contains("io_blockAllocOnlyBid"))
    assert(sv.contains("io_allocGid"))
    assert(sv.contains("io_allocPeId"))
    assert(sv.contains("io_allocLsId"))
    assert(sv.contains("io_allocIsLoad"))
    assert(sv.contains("io_allocIsStore"))
    assert(sv.contains("io_allocIsLast"))
    assert(sv.contains("io_allocMarkerBoundary"))
    assert(sv.contains("io_allocMarkerStop"))
    assert(sv.contains("io_allocMarkerBoundaryKind"))
    assert(sv.contains("io_allocMarkerBoundaryTarget"))
    assert(sv.contains("io_allocTSeq_value"))
    assert(sv.contains("io_renameUpdateReady"))
    assert(sv.contains("io_renameUpdateRid_value"))
    assert(sv.contains("io_renameUpdateTSeq_value"))
    assert(sv.contains("io_renameUpdateTUDstKind"))
    assert(sv.contains("io_completeRowValid"))
    assert(sv.contains("io_completeRow_wb_data"))
    assert(sv.contains("io_commitMemoryOrder_0_valid"))
    assert(sv.contains("io_commitMemoryOrder_0_lsId"))
    assert(sv.contains("io_robTULinkSource_tSeq_value"))
    assert(sv.contains("io_deallocTURetireSource_0_tSeq_value"))
    assert(sv.contains("io_deallocTURetireSource_0_peId"))
    assert(sv.contains("io_deallocBlockMarkerRetireSource_0_isBoundary"))
    assert(sv.contains("io_deallocBlockMarkerRetireSource_0_boundaryTarget"))
    assert(sv.contains("io_deallocBlockLastValid"))
    assert(sv.contains("io_deallocBlockLastBid_value"))
    assert(sv.contains("io_deallocBlockLastBlockBid"))
    assert(sv.contains("io_robTULinkSourceMatched"))
    assert(sv.contains("io_fullBidLookupRequest_rid_value"))
    assert(sv.contains("io_fullBidLookup_blockBid"))
    assert(sv.contains("io_fullBidLookup_blockedByScope"))
    assert(sv.contains("io_blockAllocatedMask"))
    assert(sv.contains("io_commitContractError"))
  }
}
