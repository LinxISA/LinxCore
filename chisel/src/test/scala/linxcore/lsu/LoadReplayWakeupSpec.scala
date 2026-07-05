package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayWakeupReference {
  import LoadInflightQueueReference._
  import LoadStoreForwardingReference.{Store, byteMask, lineData}
  import STQFlushPruneReference.Id

  sealed trait Source
  case object StoreUnit extends Source
  case object StoreCoalescingBuffer extends Source

  final case class Wakeup(
      source: Source,
      storeId: Id = Id(),
      storeLsId: Id = Id(),
      pc: BigInt = 0,
      lineAddr: BigInt = 0x1000,
      validMask: BigInt = 0,
      data: BigInt = 0)

  final case class Result(
      waitStoreClear: Boolean,
      merge: Boolean,
      completed: Boolean,
      requestByteMask: BigInt,
      mergedValidMask: BigInt,
      mergedLineData: BigInt)

  private def less(lhs: Id, rhs: Id): Boolean =
    if (lhs.wrap == rhs.wrap) lhs.value < rhs.value else lhs.value > rhs.value

  private def lessEqual(lhs: Id, rhs: Id): Boolean =
    less(lhs, rhs) || lhs == rhs

  private def lessEqualBidLs(srcBid: Id, srcLsId: Id, dstBid: Id, dstLsId: Id): Boolean =
    less(srcBid, dstBid) || (srcBid == dstBid && lessEqual(srcLsId, dstLsId))

  private def bit(mask: BigInt, lane: Int): Boolean =
    ((mask >> lane) & BigInt(1)) == BigInt(1)

  private def getByte(data: BigInt, lane: Int): Int =
    ((data >> (lane * 8)) & BigInt(0xff)).toInt

  private def setByte(data: BigInt, lane: Int, value: Int): BigInt = {
    val clearMask = ~(BigInt(0xff) << (lane * 8))
    (data & clearMask) | (BigInt(value & 0xff) << (lane * 8))
  }

  private def working(row: Row): Boolean =
    row.status != Idle && row.status != Resolved

  def apply(row: Row, wake: Wakeup): Result = {
    val requestMask = byteMask((row.alloc.addr & BigInt(0x3f)).toInt, row.alloc.size)
    val sameLine = (row.alloc.addr & ~BigInt(0x3f)) == wake.lineAddr
    val waitStoreClear = wake.source == StoreUnit &&
      row.waitStore.exists(store =>
        store.storeId == wake.storeId &&
          (!store.storeLsId.valid || store.storeLsId == wake.storeLsId) &&
          store.pc == wake.pc)
    val storeMissEligible = wake.source == StoreUnit &&
      sameLine &&
      (row.status == L1DcMiss || row.status == L2Wait) &&
      lessEqualBidLs(wake.storeId, wake.storeLsId, row.alloc.youngestStoreId, row.alloc.youngestStoreLsId)
    val scbEligible = wake.source == StoreCoalescingBuffer &&
      working(row) &&
      sameLine &&
      row.status != Repick
    val merge = wake.validMask != 0 && (storeMissEligible || scbEligible)
    val mergedValidMask = row.validMask | wake.validMask
    val mergedLineData = (0 until 64).foldLeft(row.lineData) { case (data, lane) =>
      if (bit(wake.validMask, lane)) setByte(data, lane, getByte(wake.data, lane)) else data
    }
    val completed = merge && requestMask != 0 && (mergedValidMask & requestMask) == requestMask

    Result(
      waitStoreClear = waitStoreClear,
      merge = merge,
      completed = completed,
      requestByteMask = requestMask,
      mergedValidMask = mergedValidMask,
      mergedLineData = mergedLineData)
  }

  def storeWait(pc: BigInt, storeId: Id, storeLsId: Id = Id()): Store =
    Store(index = 0, dataReady = false, pc = pc, storeId = storeId, storeLsId = storeLsId)

  def data(bytes: (Int, Int)*): BigInt =
    lineData(bytes.toMap)
}

class LoadReplayWakeupSpec extends AnyFunSuite {
  import LoadInflightQueueReference._
  import LoadReplayWakeupReference._
  import LoadStoreForwardingReference.byteMask
  import STQFlushPruneReference.Id

  private def id(value: Int, wrap: Boolean = false): Id =
    Id(valid = true, wrap = wrap, value = value)

  private def row(
      status: Status,
      addr: BigInt = 0x1008,
      size: Int = 4,
      youngestStore: Id = id(7),
      youngestStoreLsId: Id = Id(),
      validMask: BigInt = 0,
      lineData: BigInt = 0,
      waitStore: Option[LoadStoreForwardingReference.Store] = None): Row =
    Row(
      status = status,
      alloc = Alloc(addr = addr, size = size, youngestStoreId = youngestStore, youngestStoreLsId = youngestStoreLsId),
      lineData = lineData,
      validMask = validMask,
      waitStore = waitStore)

  test("store-unit wakeup clears matching wait-store diagnostics") {
    val storeId = id(4)
    val result = LoadReplayWakeupReference(
      row(Wait, waitStore = Some(storeWait(pc = 0x3450, storeId = storeId))),
      Wakeup(source = StoreUnit, storeId = storeId, pc = 0x3450, lineAddr = 0x1000))

    assert(result.waitStoreClear)
    assert(!result.merge)
    assert(!result.completed)
  }

  test("store-unit wakeup clears MDB wait-store rows with wildcard LSID") {
    val storeId = id(4)
    val result = LoadReplayWakeupReference(
      row(Wait, waitStore = Some(storeWait(pc = 0x3450, storeId = storeId, storeLsId = Id(valid = false)))),
      Wakeup(source = StoreUnit, storeId = storeId, storeLsId = id(9), pc = 0x3450, lineAddr = 0x1000))

    assert(result.waitStoreClear)
    assert(!result.merge)
    assert(!result.completed)
  }

  test("store-unit wakeup merges older miss bytes and completes requested data") {
    val result = LoadReplayWakeupReference(
      row(L1DcMiss, addr = 0x1008, size = 2, youngestStore = id(6)),
      Wakeup(
        source = StoreUnit,
        storeId = id(4),
        lineAddr = 0x1000,
        validMask = byteMask(8, 2),
        data = data(8 -> 0xaa, 9 -> 0xbb)))

    assert(result.merge)
    assert(result.completed)
    assert(result.requestByteMask == byteMask(8, 2))
    assert(result.mergedValidMask == byteMask(8, 2))
    assert(result.mergedLineData == data(8 -> 0xaa, 9 -> 0xbb))
  }

  test("store-unit wakeup ignores stores younger than the allocation snapshot") {
    val result = LoadReplayWakeupReference(
      row(L2Wait, addr = 0x1000, size = 4, youngestStore = id(3)),
      Wakeup(
        source = StoreUnit,
        storeId = id(4),
        lineAddr = 0x1000,
        validMask = byteMask(0, 4),
        data = data(0 -> 0x11, 1 -> 0x22, 2 -> 0x33, 3 -> 0x44)))

    assert(!result.merge)
    assert(!result.completed)
  }

  test("store-unit wakeup uses LSID for same-BID allocation snapshots") {
    val oldEnough = LoadReplayWakeupReference(
      row(L1DcMiss, addr = 0x1000, size = 2, youngestStore = id(3), youngestStoreLsId = id(5)),
      Wakeup(
        source = StoreUnit,
        storeId = id(3),
        storeLsId = id(5),
        lineAddr = 0x1000,
        validMask = byteMask(0, 2),
        data = data(0 -> 0x21, 1 -> 0x22)))
    val tooYoung = LoadReplayWakeupReference(
      row(L1DcMiss, addr = 0x1000, size = 2, youngestStore = id(3), youngestStoreLsId = id(5)),
      Wakeup(
        source = StoreUnit,
        storeId = id(3),
        storeLsId = id(6),
        lineAddr = 0x1000,
        validMask = byteMask(0, 2),
        data = data(0 -> 0x31, 1 -> 0x32)))

    assert(oldEnough.merge)
    assert(oldEnough.completed)
    assert(!tooYoung.merge)
    assert(!tooYoung.completed)
  }

  test("SCB wakeup merges working non-repick rows and leaves partial rows incomplete") {
    val partial = LoadReplayWakeupReference(
      row(Wait, addr = 0x1010, size = 4, validMask = byteMask(16, 1), lineData = data(16 -> 0x10)),
      Wakeup(
        source = StoreCoalescingBuffer,
        lineAddr = 0x1000,
        validMask = byteMask(17, 2),
        data = data(17 -> 0x20, 18 -> 0x30)))

    assert(partial.merge)
    assert(!partial.completed)
    assert(partial.mergedValidMask == byteMask(16, 3))

    val complete = LoadReplayWakeupReference(
      row(Wait, addr = 0x1010, size = 4, validMask = byteMask(16, 3), lineData = partial.mergedLineData),
      Wakeup(
        source = StoreCoalescingBuffer,
        lineAddr = 0x1000,
        validMask = byteMask(19, 1),
        data = data(19 -> 0x40)))

    assert(complete.merge)
    assert(complete.completed)
    assert(complete.mergedValidMask == byteMask(16, 4))
  }

  test("SCB wakeup ignores REPick and resolved rows") {
    val wake = Wakeup(source = StoreCoalescingBuffer, lineAddr = 0x1000, validMask = byteMask(0, 4))

    assert(!LoadReplayWakeupReference(row(Repick, addr = 0x1000, size = 4), wake).merge)
    assert(!LoadReplayWakeupReference(row(Resolved, addr = 0x1000, size = 4), wake).merge)
  }

  test("Chisel LoadReplayWakeup elaborates with replay masks") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayWakeup(liqEntries = 4, idEntries = 8, storeEntries = 4))

    assert(sv.contains("module LoadReplayWakeup"))
    assert(sv.contains("io_waitStoreClearMask"))
    assert(sv.contains("io_mergeMask"))
    assert(sv.contains("io_completedMask"))
    assert(sv.contains("io_mergedLineData_0"))
  }
}
