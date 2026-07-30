package linxcore.lsu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
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
      storeLsIdFullValid: Boolean = true,
      storeLsIdFull: BigInt = 0,
      pc: BigInt = 0,
      lineAddr: BigInt = 0x1000,
      validMask: BigInt = 0,
      data: BigInt = 0)

  final case class Result(
      waitStoreClear: Boolean,
      merge: Boolean,
      completed: Boolean,
      orderAuthorityMissing: Boolean,
      orderAmbiguous: Boolean,
      requestByteMask: BigInt,
      mergedValidMask: BigInt,
      mergedLineData: BigInt)

  private def less(lhs: Id, rhs: Id): Boolean =
    if (lhs.wrap == rhs.wrap) lhs.value < rhs.value else lhs.value > rhs.value

  private def lessEqual(lhs: Id, rhs: Id): Boolean =
    less(lhs, rhs) || lhs == rhs

  private def fullAmbiguous(lhs: BigInt, rhs: BigInt, width: Int): Boolean = {
    val mask = (BigInt(1) << width) - 1
    ((rhs - lhs) & mask) == (BigInt(1) << (width - 1))
  }

  private def fullLessEqual(lhs: BigInt, rhs: BigInt, width: Int): Boolean = {
    val mask = (BigInt(1) << width) - 1
    val distance = (rhs - lhs) & mask
    lhs == rhs || (distance != 0 && (distance & (BigInt(1) << (width - 1))) == 0)
  }

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
    val offset = (row.alloc.addr & BigInt(0x3f)).toInt
    val firstSize = 64 - offset
    val second = row.crossLine && row.secondSegmentActive
    val requestMask = byteMask(if (second) 0 else offset,
      if (second) row.alloc.size - firstSize else if (row.crossLine) firstSize else row.alloc.size)
    val activeLine = (row.alloc.addr & ~BigInt(0x3f)) + (if (second) 64 else 0)
    val sameLine = activeLine == wake.lineAddr
    val waitStoreClear = wake.source == StoreUnit &&
      row.waitStore.exists(store =>
        store.storeId == wake.storeId &&
          (!store.storeLsId.valid || store.storeLsId == wake.storeLsId) &&
          store.storeLsIdFullValid &&
          wake.storeLsIdFullValid &&
          store.storeLsIdFull == wake.storeLsIdFull &&
          store.pc == wake.pc)
    val storeMissCandidate = wake.source == StoreUnit && sameLine &&
      (row.status == L1DcMiss || row.status == L2Wait)
    val sameBid = wake.storeId == row.alloc.youngestStoreId
    val bidAuthorityValid = wake.storeId.valid && row.alloc.youngestStoreId.valid
    val sameBidFullAuthority = wake.storeLsIdFullValid &&
      row.alloc.youngestStoreLsIdFullValid
    val sameBidAmbiguous = sameBid && sameBidFullAuthority &&
      fullAmbiguous(wake.storeLsIdFull,
        row.alloc.youngestStoreLsIdFull, width = 40)
    val ordered = bidAuthorityValid &&
      (less(wake.storeId, row.alloc.youngestStoreId) ||
        (sameBid && sameBidFullAuthority && !sameBidAmbiguous &&
          fullLessEqual(wake.storeLsIdFull,
            row.alloc.youngestStoreLsIdFull, width = 40)))
    val storeMissEligible = storeMissCandidate && ordered
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
      orderAuthorityMissing = storeMissCandidate &&
        (!bidAuthorityValid || (sameBid && !sameBidFullAuthority)),
      orderAmbiguous = storeMissCandidate && bidAuthorityValid &&
        sameBidAmbiguous,
      requestByteMask = requestMask,
      mergedValidMask = mergedValidMask,
      mergedLineData = mergedLineData)
  }

  def storeWait(
      pc: BigInt,
      storeId: Id,
      storeLsId: Id = Id(),
      storeLsIdFullValid: Boolean = true,
      storeLsIdFull: BigInt = 0): Store =
    Store(
      index = 0,
      dataReady = false,
      pc = pc,
      storeId = storeId,
      storeLsId = storeLsId,
      storeLsIdFullValid = storeLsIdFullValid,
      storeLsIdFull = storeLsIdFull)

  def data(bytes: (Int, Int)*): BigInt =
    lineData(bytes.toMap)
}

class LoadReplayWakeupSpec extends AnyFunSuite with ChiselSim {
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
      youngestStoreLsIdFullValid: Boolean = false,
      youngestStoreLsIdFull: BigInt = 0,
      validMask: BigInt = 0,
      lineData: BigInt = 0,
      waitStore: Option[LoadStoreForwardingReference.Store] = None,
      crossLine: Boolean = false,
      secondSegmentActive: Boolean = false): Row =
    Row(
      status = status,
      alloc = Alloc(
        addr = addr,
        size = size,
        youngestStoreId = youngestStore,
        youngestStoreLsId = youngestStoreLsId,
        youngestStoreLsIdFullValid = youngestStoreLsIdFullValid,
        youngestStoreLsIdFull = youngestStoreLsIdFull),
      lineData = lineData,
      validMask = validMask,
      waitStore = waitStore,
      crossLine = crossLine,
      secondSegmentActive = secondSegmentActive)

  test("store-unit wakeup clears matching wait-store diagnostics") {
    val storeId = id(4)
    val result = LoadReplayWakeupReference(
      row(Wait, waitStore = Some(storeWait(pc = 0x3450, storeId = storeId))),
      Wakeup(source = StoreUnit, storeId = storeId, pc = 0x3450, lineAddr = 0x1000))

    assert(result.waitStoreClear)
    assert(!result.merge)
    assert(!result.completed)
  }

  test("store-unit wakeup refuses MDB wait-store rows without full LSID authority") {
    val storeId = id(4)
    val result = LoadReplayWakeupReference(
      row(Wait, waitStore = Some(storeWait(
        pc = 0x3450,
        storeId = storeId,
        storeLsId = Id(valid = false),
        storeLsIdFullValid = false))),
      Wakeup(source = StoreUnit, storeId = storeId, storeLsId = id(9), pc = 0x3450, lineAddr = 0x1000))

    assert(!result.waitStoreClear)
    assert(!result.merge)
    assert(!result.completed)
  }

  test("store-unit wakeup requires an exact full LSID match") {
    val storeId = id(4)
    val waiting = row(Wait, waitStore = Some(storeWait(
      pc = 0x3450,
      storeId = storeId,
      storeLsId = id(1),
      storeLsIdFull = BigInt("8000000001", 16))))

    val exact = LoadReplayWakeupReference(
      waiting,
      Wakeup(
        source = StoreUnit,
        storeId = storeId,
        storeLsId = id(1),
        storeLsIdFull = BigInt("8000000001", 16),
        pc = 0x3450))
    val projectedAlias = LoadReplayWakeupReference(
      waiting,
      Wakeup(
        source = StoreUnit,
        storeId = storeId,
        storeLsId = id(1),
        storeLsIdFull = BigInt(1),
        pc = 0x3450))

    assert(exact.waitStoreClear)
    assert(!projectedAlias.waitStoreClear)
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
      row(L1DcMiss, addr = 0x1000, size = 2, youngestStore = id(3),
        youngestStoreLsId = id(5), youngestStoreLsIdFullValid = true,
        youngestStoreLsIdFull = BigInt("100000005", 16)),
      Wakeup(
        source = StoreUnit,
        storeId = id(3),
        storeLsId = id(5),
        storeLsIdFull = BigInt("100000005", 16),
        lineAddr = 0x1000,
        validMask = byteMask(0, 2),
        data = data(0 -> 0x21, 1 -> 0x22)))
    val tooYoung = LoadReplayWakeupReference(
      row(L1DcMiss, addr = 0x1000, size = 2, youngestStore = id(3),
        youngestStoreLsId = id(5), youngestStoreLsIdFullValid = true,
        youngestStoreLsIdFull = BigInt("100000005", 16)),
      Wakeup(
        source = StoreUnit,
        storeId = id(3),
        storeLsId = id(6),
        storeLsIdFull = BigInt("100000006", 16),
        lineAddr = 0x1000,
        validMask = byteMask(0, 2),
        data = data(0 -> 0x31, 1 -> 0x32)))

    assert(oldEnough.merge)
    assert(oldEnough.completed)
    assert(!tooYoung.merge)
    assert(!tooYoung.completed)
  }

  test("same-BID miss wake fails closed on projected aliases and missing or ambiguous full LSID") {
    val snapshot = row(
      L1DcMiss,
      addr = 0x1000,
      size = 2,
      youngestStore = id(3),
      youngestStoreLsId = id(5),
      youngestStoreLsIdFullValid = true,
      youngestStoreLsIdFull = BigInt("100000005", 16))
    val projectedAlias = LoadReplayWakeupReference(
      snapshot,
      Wakeup(
        source = StoreUnit,
        storeId = id(3),
        storeLsId = id(5),
        storeLsIdFull = BigInt("200000005", 16),
        lineAddr = 0x1000,
        validMask = byteMask(0, 2)))
    val missing = LoadReplayWakeupReference(
      snapshot,
      Wakeup(
        source = StoreUnit,
        storeId = id(3),
        storeLsId = id(5),
        storeLsIdFullValid = false,
        lineAddr = 0x1000,
        validMask = byteMask(0, 2)))
    val ambiguous = LoadReplayWakeupReference(
      snapshot,
      Wakeup(
        source = StoreUnit,
        storeId = id(3),
        storeLsId = id(5),
        storeLsIdFull = BigInt("8100000005", 16),
        lineAddr = 0x1000,
        validMask = byteMask(0, 2)))

    assert(!projectedAlias.merge)
    assert(!projectedAlias.orderAuthorityMissing)
    assert(!projectedAlias.orderAmbiguous)
    assert(!missing.merge)
    assert(missing.orderAuthorityMissing)
    assert(!ambiguous.merge)
    assert(ambiguous.orderAmbiguous)
  }

  test("Chisel same-BID miss wake uses 40-bit LSID authority and reports fail-closed causes") {
    simulate(new LoadReplayWakeup(
      liqEntries = 4,
      idEntries = 8,
      storeEntries = 4,
      lsidWidth = 40)) { dut =>
      dut.io.wakeValid.poke(true.B)
      dut.io.wake.poke(0.U.asTypeOf(dut.io.wake))
      dut.io.rows.foreach(_.poke(0.U.asTypeOf(dut.io.rows.head)))

      val row0 = dut.io.rows(0)
      row0.valid.poke(true.B)
      row0.status.poke(LoadInflightStatus.L1DcMiss)
      row0.addr.poke(0x1000.U)
      row0.size.poke(2.U)
      row0.youngestStoreId.valid.poke(true.B)
      row0.youngestStoreId.value.poke(3.U)
      row0.youngestStoreLsId.valid.poke(true.B)
      row0.youngestStoreLsId.value.poke(5.U)
      row0.youngestStoreLsIdFullValid.poke(true.B)
      row0.youngestStoreLsIdFull.poke(BigInt("100000005", 16).U)

      dut.io.wake.source.poke(LoadReplayWakeSource.StoreUnit)
      dut.io.wake.storeId.valid.poke(true.B)
      dut.io.wake.storeId.value.poke(3.U)
      dut.io.wake.storeLsId.valid.poke(true.B)
      dut.io.wake.storeLsId.value.poke(5.U)
      dut.io.wake.lineAddr.poke(0x1000.U)
      dut.io.wake.validMask.poke(byteMask(0, 2).U)

      dut.io.wake.storeLsIdFullValid.poke(true.B)
      dut.io.wake.storeLsIdFull.poke(BigInt("200000005", 16).U)
      dut.io.mergeMask.expect(0.U)
      dut.io.orderAuthorityMissingMask.expect(0.U)
      dut.io.orderAmbiguousMask.expect(0.U)

      dut.io.wake.storeLsIdFullValid.poke(false.B)
      dut.io.mergeMask.expect(0.U)
      dut.io.orderAuthorityMissingMask.expect(1.U)

      dut.io.wake.storeLsIdFullValid.poke(true.B)
      dut.io.wake.storeLsIdFull.poke(BigInt("8100000005", 16).U)
      dut.io.mergeMask.expect(0.U)
      dut.io.orderAuthorityMissingMask.expect(0.U)
      dut.io.orderAmbiguousMask.expect(1.U)

      dut.io.wake.storeLsIdFull.poke(BigInt("100000005", 16).U)
      dut.io.mergeMask.expect(1.U)
      dut.io.completedMask.expect(1.U)
      dut.io.orderAmbiguousMask.expect(0.U)
    }
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

  test("SCB wakeup targets only the active second segment of a cross-line load") {
    val active = row(
      Wait,
      addr = 0x103e,
      size = 4,
      crossLine = true,
      secondSegmentActive = true)
    val wrongLine = LoadReplayWakeupReference(
      active,
      Wakeup(source = StoreCoalescingBuffer, lineAddr = 0x1000, validMask = byteMask(62, 2)))
    val secondLine = LoadReplayWakeupReference(
      active,
      Wakeup(
        source = StoreCoalescingBuffer,
        lineAddr = 0x1040,
        validMask = byteMask(0, 2),
        data = data(0 -> 0x80, 1 -> 0x81)))

    assert(!wrongLine.merge)
    assert(secondLine.merge)
    assert(secondLine.completed)
    assert(secondLine.requestByteMask == byteMask(0, 2))
  }

  test("Chisel LoadReplayWakeup elaborates with replay masks") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayWakeup(liqEntries = 4, idEntries = 8, storeEntries = 4))

    assert(sv.contains("module LoadReplayWakeup"))
    assert(sv.contains("io_waitStoreClearMask"))
    assert(sv.contains("io_mergeMask"))
    assert(sv.contains("io_completedMask"))
    assert(sv.contains("io_orderAuthorityMissingMask"))
    assert(sv.contains("io_orderAmbiguousMask"))
    assert(sv.contains("io_mergedLineData_0"))
  }
}
