package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object ResidentStoreReplayWakeupReference {
  import STQFlushPruneReference.Id

  final case class WaitStore(valid: Boolean = true, index: Int = 0, storeId: Id = Id(), storeLsId: Id = Id(), pc: BigInt = 0)
  final case class Row(
      valid: Boolean = true,
      waitState: Boolean = true,
      addrReady: Boolean = true,
      dataReady: Boolean = true,
      scalar: Boolean = true,
      bid: Id = Id(),
      lsId: Id = Id(),
      pc: BigInt = 0,
      addr: BigInt = 0x1000,
      data: BigInt = 0,
      size: Int = 8)
  final case class Wakeup(storeId: Id, storeLsId: Id, pc: BigInt, lineAddr: BigInt, validMask: BigInt, data: BigInt)
  final case class Result(
      wakeValid: Boolean,
      selected: Boolean,
      identityMatch: Boolean,
      ready: Boolean,
      crossesLine: Boolean,
      wake: Option[Wakeup])

  private val LineBytes = 64

  private def crosses(addr: BigInt, size: Int): Boolean =
    ((addr & 0x3f) + size) > LineBytes

  private def lineAddr(addr: BigInt): BigInt =
    addr & ~BigInt(0x3f)

  private def byteMask(addr: BigInt, size: Int): BigInt = {
    val offset = (addr & 0x3f).toInt
    (0 until size).foldLeft(BigInt(0)) { case (mask, lane) => mask | (BigInt(1) << (offset + lane)) }
  }

  private def getByte(data: BigInt, byte: Int): BigInt =
    (data >> (byte * 8)) & BigInt(0xff)

  private def lineData(row: Row, mask: BigInt): BigInt = {
    val offset = (row.addr & 0x3f).toInt
    (0 until LineBytes).foldLeft(BigInt(0)) { case (line, byte) =>
      if (((mask >> byte) & BigInt(1)) == BigInt(1)) {
        line | (getByte(row.data, byte - offset) << (byte * 8))
      } else {
        line
      }
    }
  }

  def apply(enable: Boolean, waitStore: WaitStore, rows: Seq[Row]): Result = {
    val row = rows(waitStore.index)
    val selected = enable && waitStore.valid && row.valid && row.waitState
    val identity =
      selected &&
        row.bid == waitStore.storeId &&
        row.lsId == waitStore.storeLsId &&
        row.pc == waitStore.pc
    val cross = crosses(row.addr, row.size)
    val ready = identity && row.addrReady && row.dataReady && row.scalar && !cross
    val mask = byteMask(row.addr, row.size)
    val valid = ready && mask != 0
    Result(
      wakeValid = valid,
      selected = selected,
      identityMatch = identity,
      ready = ready,
      crossesLine = cross,
      wake = if (valid) Some(Wakeup(waitStore.storeId, waitStore.storeLsId, waitStore.pc, lineAddr(row.addr), mask, lineData(row, mask))) else None)
  }
}

class ResidentStoreReplayWakeupSpec extends AnyFunSuite {
  import ResidentStoreReplayWakeupReference._
  import STQFlushPruneReference.Id

  private def id(value: Int, wrap: Boolean = false): Id =
    Id(valid = true, wrap = wrap, value = value)

  test("matching ready resident wait-store emits a store-unit replay wakeup") {
    val storeId = id(3)
    val lsId = id(5)
    val wait = WaitStore(index = 1, storeId = storeId, storeLsId = lsId, pc = 0x4400)
    val rows = Seq(
      Row(valid = false),
      Row(bid = storeId, lsId = lsId, pc = 0x4400, addr = 0x1002, data = BigInt("11223344", 16), size = 4))

    val result = ResidentStoreReplayWakeupReference(enable = true, waitStore = wait, rows = rows)

    assert(result.wakeValid)
    assert(result.selected)
    assert(result.identityMatch)
    assert(result.ready)
    val wake = result.wake.get
    assert(wake.storeId == storeId)
    assert(wake.storeLsId == lsId)
    assert(wake.pc == 0x4400)
    assert(wake.lineAddr == 0x1000)
    assert(wake.validMask == BigInt("3c", 16))
    assert(((wake.data >> (2 * 8)) & 0xff) == 0x44)
    assert(((wake.data >> (5 * 8)) & 0xff) == 0x11)
  }

  test("matching not-ready row is selected but does not emit a wakeup") {
    val storeId = id(4)
    val wait = WaitStore(index = 0, storeId = storeId, storeLsId = id(1), pc = 0x5000)
    val result = ResidentStoreReplayWakeupReference(
      enable = true,
      waitStore = wait,
      rows = Seq(Row(dataReady = false, bid = storeId, lsId = id(1), pc = 0x5000)))

    assert(result.selected)
    assert(result.identityMatch)
    assert(!result.ready)
    assert(!result.wakeValid)
  }

  test("stale wait-store identity suppresses a ready row") {
    val result = ResidentStoreReplayWakeupReference(
      enable = true,
      waitStore = WaitStore(index = 0, storeId = id(2), storeLsId = id(0), pc = 0x1000),
      rows = Seq(Row(bid = id(2), lsId = id(0), pc = 0x2000)))

    assert(result.selected)
    assert(!result.identityMatch)
    assert(!result.ready)
    assert(!result.wakeValid)
  }

  test("cross-line resident store does not emit a scalar replay wakeup") {
    val storeId = id(6)
    val result = ResidentStoreReplayWakeupReference(
      enable = true,
      waitStore = WaitStore(index = 0, storeId = storeId, storeLsId = id(0), pc = 0x6000),
      rows = Seq(Row(bid = storeId, lsId = id(0), pc = 0x6000, addr = 0x103e, size = 4)))

    assert(result.identityMatch)
    assert(result.crossesLine)
    assert(!result.ready)
    assert(!result.wakeValid)
  }

  test("Chisel ResidentStoreReplayWakeup elaborates store-unit wakeup request diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new ResidentStoreReplayWakeup(entries = 8))

    assert(sv.contains("module ResidentStoreReplayWakeup"))
    assert(sv.contains("io_wakeValid"))
    assert(sv.contains("io_wake_storeId_value"))
    assert(sv.contains("io_wake_validMask"))
    assert(sv.contains("io_identityMatch"))
  }
}
