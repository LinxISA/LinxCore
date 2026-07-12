package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object ReducedLoadReplayLiqAllocAdapterReference {
  import ReducedLoadWaitReplaySlotReference.{Dst, Id, Relaunch}

  final case class Alloc(
      bid: Id,
      gid: Id,
      rid: Id,
      loadLsId: Id,
      pc: BigInt,
      addr: BigInt,
      size: Int,
      returnSignExtend: Boolean,
      dst: Dst,
      youngestStoreId: Id,
      youngestStoreLsId: Id,
      isTile: Boolean,
      specWakeup: Boolean,
      stackValid: Boolean)

  final case class Step(
      allocValid: Boolean,
      alloc: Option[Alloc],
      consumeReady: Boolean,
      blockedByAlloc: Boolean,
      candidateUsable: Boolean)

  def id(value: Int, wrap: Boolean = false): Id =
    Id(valid = true, wrap = wrap, value = value)

  def candidate: Relaunch =
    Relaunch(
      pc = 0x4000,
      addr = 0x1008,
      size = 8,
      bid = id(6),
      lsId = id(3),
      gid = id(2),
      rid = id(7),
      dst = Dst(valid = true, kind = 1, archTag = 10, relTag = 10, physTag = 42, oldPhysTag = 10),
      youngestStoreId = id(4),
      youngestStoreLsId = id(1))

  def step(
      candidate: Option[Relaunch],
      allocReady: Boolean = true,
      flush: Boolean = false): Step = {
    val usable = !flush && candidate.isDefined
    val alloc = candidate.filter(_ => usable).map { relaunch =>
      Alloc(
        bid = relaunch.bid,
        gid = relaunch.gid,
        rid = relaunch.rid,
        loadLsId = relaunch.lsId,
        pc = relaunch.pc,
        addr = relaunch.addr,
        size = relaunch.size,
        returnSignExtend = relaunch.returnSignExtend,
        dst = relaunch.dst,
        youngestStoreId = relaunch.youngestStoreId,
        youngestStoreLsId = relaunch.youngestStoreLsId,
        isTile = false,
        specWakeup = false,
        stackValid = false)
    }

    Step(
      allocValid = usable,
      alloc = alloc,
      consumeReady = usable && allocReady,
      blockedByAlloc = usable && !allocReady,
      candidateUsable = usable)
  }
}

class ReducedLoadReplayLiqAllocAdapterSpec extends AnyFunSuite {
  import ReducedLoadReplayLiqAllocAdapterReference._

  test("maps replay candidate identity and forwarding snapshot into LIQ allocation") {
    val result = step(Some(candidate), allocReady = true)
    val alloc = result.alloc.get

    assert(result.allocValid)
    assert(result.consumeReady)
    assert(!result.blockedByAlloc)
    assert(alloc.bid == id(6))
    assert(alloc.gid == id(2))
    assert(alloc.rid == id(7))
    assert(alloc.loadLsId == id(3))
    assert(alloc.youngestStoreId == id(4))
    assert(alloc.youngestStoreLsId == id(1))
    assert(alloc.pc == 0x4000)
    assert(alloc.addr == 0x1008)
    assert(alloc.size == 8)
    assert(!alloc.returnSignExtend)
    assert(alloc.dst.valid)
    assert(alloc.dst.physTag == 42)
    assert(!alloc.isTile)
    assert(!alloc.specWakeup)
    assert(!alloc.stackValid)
  }

  test("backpressure keeps allocation valid but does not consume queue head") {
    val result = step(Some(candidate), allocReady = false)

    assert(result.allocValid)
    assert(!result.consumeReady)
    assert(result.blockedByAlloc)
    assert(result.alloc.nonEmpty)
  }

  test("absent candidate and flush suppress allocation and consume") {
    val absent = step(None, allocReady = true)
    assert(!absent.allocValid)
    assert(!absent.consumeReady)
    assert(!absent.blockedByAlloc)
    assert(absent.alloc.isEmpty)

    val flushed = step(Some(candidate), allocReady = true, flush = true)
    assert(!flushed.allocValid)
    assert(!flushed.consumeReady)
    assert(!flushed.blockedByAlloc)
    assert(flushed.alloc.isEmpty)
  }

  test("Chisel ReducedLoadReplayLiqAllocAdapter elaborates LIQ alloc handshake") {
    val io = new ReducedLoadReplayLiqAllocAdapterIO(liqEntries = 4, idEntries = 8, lsidWidth = 40)
    assert(io.candidate.loadLsIdFull.getWidth == 40)
    assert(io.candidate.youngestStoreLsIdFull.getWidth == 40)
    assert(io.alloc.loadLsIdFull.getWidth == 40)
    assert(io.alloc.youngestStoreLsIdFull.getWidth == 40)

    val sv = ChiselStage.emitSystemVerilog(new ReducedLoadReplayLiqAllocAdapter(
      liqEntries = 4, idEntries = 8, lsidWidth = 40))

    assert(sv.contains("module ReducedLoadReplayLiqAllocAdapter"))
    assert(sv.contains("io_allocValid"))
    assert(sv.contains("io_alloc_loadLsId_value"))
    assert(sv.contains("io_candidate_loadLsIdFullValid"))
    assert(sv.contains("io_candidate_loadLsIdFull"))
    assert(sv.contains("io_alloc_loadLsIdFullValid"))
    assert(sv.contains("io_alloc_loadLsIdFull"))
    assert(sv.contains("io_candidate_youngestStoreLsIdFull"))
    assert(sv.contains("io_alloc_youngestStoreLsIdFull"))
    assert(sv.contains("io_alloc_returnSignExtend"))
    assert(sv.contains("io_alloc_dst_physTag"))
    assert(sv.contains("io_alloc_youngestStoreId_value"))
    assert(sv.contains("io_alloc_youngestStoreLsId_value"))
    assert(sv.contains("io_consumeReady"))
    assert(sv.contains("io_blockedByAlloc"))
    assert(sv.contains("io_candidateUsable"))
  }
}
