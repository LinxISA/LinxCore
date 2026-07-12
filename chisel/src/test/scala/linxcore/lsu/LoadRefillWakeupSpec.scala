package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadRefillWakeupReference {
  import LoadInflightQueueReference._
  import LoadStoreForwardingReference.{byteMask, lineData}

  final case class Refill(
      isRead: Boolean = true,
      lineAddr: BigInt = 0x1000,
      data: BigInt = 0,
      l2Miss: Boolean = false)

  final case class Result(
      refillAccepted: Boolean,
      wake: Boolean,
      requestByteMask: BigInt,
      lineValidMask: BigInt)

  private val fullLineMask: BigInt = (BigInt(1) << 64) - 1

  private def working(row: Row): Boolean =
    row.status != Idle && row.status != Resolved

  def apply(row: Row, refill: Refill): Result = {
    val accepted = refill.isRead
    val offset = (row.alloc.addr & BigInt(0x3f)).toInt
    val firstSize = 64 - offset
    val second = row.crossLine && row.secondSegmentActive
    val activeLine = (row.alloc.addr & ~BigInt(0x3f)) + (if (second) 64 else 0)
    val sameLine = activeLine == refill.lineAddr
    val wake = accepted && working(row) && sameLine && !row.l1Hit && !row.alloc.isTile
    val requestMask = byteMask(if (second) 0 else offset,
      if (second) row.alloc.size - firstSize else if (row.crossLine) firstSize else row.alloc.size)

    Result(
      refillAccepted = accepted,
      wake = wake,
      requestByteMask = requestMask,
      lineValidMask = if (accepted) fullLineMask else BigInt(0))
  }

  def data(bytes: (Int, Int)*): BigInt =
    lineData(bytes.toMap)
}

class LoadRefillWakeupSpec extends AnyFunSuite {
  import LoadInflightQueueReference._
  import LoadRefillWakeupReference._
  import LoadStoreForwardingReference.byteMask

  private def row(
      status: Status,
      addr: BigInt = 0x1008,
      size: Int = 4,
      isTile: Boolean = false,
      l1Hit: Boolean = false,
      crossLine: Boolean = false,
      secondSegmentActive: Boolean = false): Row =
    Row(
      status = status,
      alloc = Alloc(addr = addr, size = size, isTile = isTile),
      l1Hit = l1Hit,
      crossLine = crossLine,
      secondSegmentActive = secondSegmentActive)

  test("read refill wakes unresolved same-line scalar rows") {
    val result = LoadRefillWakeupReference(
      row(L1DcMiss, addr = 0x1008, size = 2),
      Refill(isRead = true, lineAddr = 0x1000, data = data(8 -> 0xaa, 9 -> 0xbb)))

    assert(result.refillAccepted)
    assert(result.wake)
    assert(result.requestByteMask == byteMask(8, 2))
    assert(result.lineValidMask == ((BigInt(1) << 64) - 1))
  }

  test("refill ignores non-read, tile, already-hit, resolved, idle, and different-line rows") {
    val read = Refill(isRead = true, lineAddr = 0x1000)
    val write = Refill(isRead = false, lineAddr = 0x1000)

    assert(!LoadRefillWakeupReference(row(L1DcMiss), write).wake)
    assert(!LoadRefillWakeupReference(row(L1DcMiss, isTile = true), read).wake)
    assert(!LoadRefillWakeupReference(row(L1DcMiss, l1Hit = true), read).wake)
    assert(!LoadRefillWakeupReference(row(Resolved), read).wake)
    assert(!LoadRefillWakeupReference(row(Idle), read).wake)
    assert(!LoadRefillWakeupReference(row(L1DcMiss, addr = 0x1040), read).wake)
  }

  test("refill may wake wait and repick rows, matching the model working-entry scan") {
    val refill = Refill(isRead = true, lineAddr = 0x1000)

    assert(LoadRefillWakeupReference(row(Wait, addr = 0x1000), refill).wake)
    assert(LoadRefillWakeupReference(row(Repick, addr = 0x1000), refill).wake)
    assert(LoadRefillWakeupReference(row(L2Wait, addr = 0x1000), refill).wake)
  }

  test("refill targets the active second line and its phase-local byte mask") {
    val active = row(
      L1DcMiss,
      addr = 0x103e,
      size = 4,
      crossLine = true,
      secondSegmentActive = true)
    val firstLine = LoadRefillWakeupReference(active, Refill(lineAddr = 0x1000))
    val secondLine = LoadRefillWakeupReference(active, Refill(lineAddr = 0x1040))

    assert(!firstLine.wake)
    assert(secondLine.wake)
    assert(secondLine.requestByteMask == byteMask(0, 2))
  }

  test("Chisel LoadRefillWakeup elaborates with refill masks") {
    val sv = ChiselStage.emitSystemVerilog(new LoadRefillWakeup(liqEntries = 4, idEntries = 8, storeEntries = 4))

    assert(sv.contains("module LoadRefillWakeup"))
    assert(sv.contains("io_refillAccepted"))
    assert(sv.contains("io_wakeMask"))
    assert(sv.contains("io_lineValidMask"))
  }
}
