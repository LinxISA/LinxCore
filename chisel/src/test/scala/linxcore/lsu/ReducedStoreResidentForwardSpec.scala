package linxcore.lsu

import circt.stage.ChiselStage
import linxcore.rob.ROBID
import org.scalatest.funsuite.AnyFunSuite

object ReducedStoreResidentForwardReference {
  final case class Id(value: Int = 0, wrap: Boolean = false)
  final case class Row(
      valid: Boolean = true,
      waitState: Boolean = true,
      addrReady: Boolean = true,
      dataReady: Boolean = true,
      scalar: Boolean = true,
      bid: Id = Id(),
      lsId: Id = Id(),
      lsIdFullValid: Boolean = true,
      lsIdFull: Option[BigInt] = None,
      pc: BigInt = 0,
      addr: BigInt = 0x1000,
      data: BigInt = 0,
      size: Int = 8)
  final case class WaitStore(index: Int, bid: Id, lsId: Id, pc: BigInt)
  final case class Result(
      data: BigInt,
      forwardMask: BigInt,
      waitMask: BigInt,
      eligibleMask: BigInt,
      waitBlocked: Boolean,
      waitStore: Option[WaitStore],
      crosses: Boolean)

  private val LoadBytes = 8
  private val LineBytes = 64
  private val Mask64 = (BigInt(1) << 64) - 1

  private def less(lhs: Id, rhs: Id): Boolean =
    if (lhs.wrap == rhs.wrap) lhs.value < rhs.value else lhs.value > rhs.value

  private def lessEqual(lhs: Id, rhs: Id): Boolean =
    less(lhs, rhs) || lhs == rhs

  private def lessFull(lhs: BigInt, rhs: BigInt, width: Int): Boolean = {
    val mask = (BigInt(1) << width) - 1
    val distance = (rhs - lhs) & mask
    lhs != rhs && distance < (BigInt(1) << (width - 1))
  }

  private def rowFull(row: Row): BigInt = row.lsIdFull.getOrElse(BigInt(row.lsId.value))

  private def beforeOrSame(row: Row, loadBid: Id, loadFullValid: Boolean, loadFull: BigInt, width: Int): Boolean =
    less(row.bid, loadBid) ||
      (row.bid == loadBid && row.lsIdFullValid && loadFullValid &&
        (rowFull(row) == loadFull || lessFull(rowFull(row), loadFull, width)))

  private def greaterRow(lhs: Row, rhs: Row, width: Int): Boolean =
    less(rhs.bid, lhs.bid) ||
      (lhs.bid == rhs.bid && lhs.lsIdFullValid && rhs.lsIdFullValid &&
        lessFull(rowFull(rhs), rowFull(lhs), width))

  private def crosses(addr: BigInt, size: Int): Boolean =
    ((addr & 0x3f) + size) > LineBytes

  private def overlap(aAddr: BigInt, aSize: Int, bAddr: BigInt, bSize: Int): Boolean =
    aAddr < (bAddr + bSize) && bAddr < (aAddr + aSize)

  private def lineAddr(addr: BigInt): BigInt = addr & ~BigInt(0x3f)

  private def byte(data: BigInt, idx: Int): Int =
    ((data >> (idx * 8)) & 0xff).toInt

  def apply(
      enable: Boolean,
      loadValid: Boolean,
      loadAddr: BigInt,
      loadSize: Int,
      loadBid: Id,
      loadLsId: Id,
      baseData: BigInt,
      rows: Seq[Row],
      loadLsIdFullValid: Boolean = true,
      loadLsIdFull: Option[BigInt] = None,
      lsidWidth: Int = 32): Result = {
    val loadFull = loadLsIdFull.getOrElse(BigInt(loadLsId.value))
    val queryValid = enable && loadValid && loadSize != 0 && !crosses(loadAddr, loadSize)
    var out = baseData & Mask64
    var fmask = BigInt(0)
    var wmask = BigInt(0)
    var emask = BigInt(0)
    var waitStore: Option[WaitStore] = None

    if (queryValid) {
      for (idx <- rows.indices) {
        val row = rows(idx)
        val eligible =
          row.valid &&
            row.waitState &&
            row.addrReady &&
            row.scalar &&
            !crosses(row.addr, row.size) &&
            lineAddr(row.addr) == lineAddr(loadAddr) &&
            overlap(row.addr, row.size, loadAddr, loadSize) &&
            beforeOrSame(row, loadBid, loadLsIdFullValid, loadFull, lsidWidth)
        if (eligible) {
          emask |= BigInt(1) << idx
        }
      }

      for (loadByte <- 0 until LoadBytes) {
        val addr = loadAddr + loadByte
        if (loadByte < loadSize) {
          val candidates = rows.zipWithIndex.filter { case (row, idx) =>
            ((emask >> idx) & 1) == 1 && addr >= row.addr && addr < (row.addr + row.size)
          }
          if (candidates.nonEmpty) {
            val nearest = candidates.reduce { (best, candidate) =>
              if (greaterRow(candidate._1, best._1, lsidWidth)) candidate else best
            }
            val (nearestRow, nearestIndex) = nearest
            val storeByte = byte(nearestRow.data, (addr - nearestRow.addr).toInt)
            if (nearestRow.dataReady) {
              out = (out & ~(BigInt(0xff) << (loadByte * 8))) | (BigInt(storeByte) << (loadByte * 8))
              fmask |= BigInt(1) << loadByte
            } else {
              wmask |= BigInt(1) << loadByte
              val current = waitStore
              val currentRow = current.flatMap(wait => rows.lift(wait.index))
              if (currentRow.isEmpty || greaterRow(nearestRow, currentRow.get, lsidWidth)) {
                waitStore = Some(WaitStore(nearestIndex, nearestRow.bid, nearestRow.lsId, nearestRow.pc))
              }
            }
          }
        }
      }
    }

    val wait = queryValid && wmask != 0
    Result(
      data = if (wait) baseData & Mask64 else out,
      forwardMask = if (wait) 0 else fmask,
      waitMask = wmask,
      eligibleMask = emask,
      waitBlocked = wait,
      waitStore = if (wait) waitStore else None,
      crosses = enable && loadValid && crosses(loadAddr, loadSize))
  }
}

class ReducedStoreResidentForwardSpec extends AnyFunSuite {
  import ReducedStoreResidentForwardReference._

  private def id(value: Int, wrap: Boolean = false): Id = Id(value, wrap)

  test("ready resident store forwards bytes over committed overlay data") {
    val result = ReducedStoreResidentForwardReference(
      enable = true,
      loadValid = true,
      loadAddr = 0x1000,
      loadSize = 8,
      loadBid = id(3),
      loadLsId = id(3),
      baseData = BigInt("ffeeddccbbaa9988", 16),
      rows = Seq(Row(bid = id(2), lsId = id(7), addr = 0x1002, data = BigInt("11223344", 16), size = 4)))

    assert(result.data == BigInt("ffee112233449988", 16))
    assert(result.forwardMask == BigInt("3c", 16))
    assert(result.waitMask == 0)
    assert(result.eligibleMask == 1)
  }

  test("same-BID resident stores use LSID to pick the nearest older source") {
    val result = ReducedStoreResidentForwardReference(
      enable = true,
      loadValid = true,
      loadAddr = 0x2000,
      loadSize = 2,
      loadBid = id(4),
      loadLsId = id(5),
      baseData = 0,
      rows = Seq(
        Row(bid = id(4), lsId = id(3), addr = 0x2000, data = BigInt("1111", 16), size = 2),
        Row(bid = id(4), lsId = id(5), addr = 0x2000, data = BigInt("5555", 16), size = 2),
        Row(bid = id(4), lsId = id(6), addr = 0x2000, data = BigInt("6666", 16), size = 2)
      ))

    assert((result.data & BigInt("ffff", 16)) == BigInt("5555", 16))
    assert(result.forwardMask == BigInt("03", 16))
    assert(result.eligibleMask == BigInt("03", 16))
  }

  test("same-BID resident forwarding selects by full LSID and refuses missing authority") {
    val result = ReducedStoreResidentForwardReference(
      enable = true,
      loadValid = true,
      loadAddr = 0x2100,
      loadSize = 2,
      loadBid = id(4),
      loadLsId = id(5),
      loadLsIdFull = Some(BigInt("0100000005", 16)),
      lsidWidth = 40,
      baseData = 0,
      rows = Seq(
        Row(bid = id(4), lsId = id(3), lsIdFull = Some(BigInt("0000000003", 16)), addr = 0x2100, data = BigInt("1111", 16), size = 2),
        Row(bid = id(4), lsId = id(3), lsIdFull = Some(BigInt("0100000004", 16)), addr = 0x2100, data = BigInt("4444", 16), size = 2),
        Row(bid = id(4), lsId = id(3), lsIdFullValid = false, addr = 0x2100, data = BigInt("7777", 16), size = 2)))

    assert((result.data & BigInt("ffff", 16)) == BigInt("4444", 16))
    assert(result.eligibleMask == BigInt("03", 16))
  }

  test("not-ready nearest store reports wait and preserves base data") {
    val result = ReducedStoreResidentForwardReference(
      enable = true,
      loadValid = true,
      loadAddr = 0x3000,
      loadSize = 2,
      loadBid = id(8),
      loadLsId = id(0),
      baseData = BigInt("8877665544332211", 16),
      rows = Seq(Row(dataReady = false, bid = id(7), lsId = id(1), pc = 0x44, addr = 0x3000, data = BigInt("aaaa", 16), size = 2)))

    assert(result.waitBlocked)
    assert(result.waitStore.contains(WaitStore(index = 0, bid = id(7), lsId = id(1), pc = 0x44)))
    assert(result.waitMask == BigInt("03", 16))
    assert(result.forwardMask == 0)
    assert(result.data == BigInt("8877665544332211", 16))
  }

  test("cross-line loads stay on the base overlay path for the ready-only packet") {
    val result = ReducedStoreResidentForwardReference(
      enable = true,
      loadValid = true,
      loadAddr = 0x403e,
      loadSize = 8,
      loadBid = id(2),
      loadLsId = id(0),
      baseData = BigInt("0102030405060708", 16),
      rows = Seq(Row(bid = id(1), lsId = id(0), addr = 0x403e, data = BigInt("aaaa", 16), size = 2)))

    assert(result.crosses)
    assert(result.forwardMask == 0)
    assert(result.data == BigInt("0102030405060708", 16))
  }

  test("Chisel ReducedStoreResidentForward elaborates resident STQ forwarding diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new ReducedStoreResidentForward(entries = 8))

    assert(sv.contains("module ReducedStoreResidentForward"))
    assert(sv.contains("ResidentStoreForwardStoreSnapshot"))
    assert(sv.contains("io_loadForwardMask"))
    assert(sv.contains("io_waitMask"))
    assert(sv.contains("io_eligibleStoreMask"))
    assert(sv.contains("io_waitStore_pc"))
    assert(sv.contains("io_waitStore_storeId_value"))
    assert(sv.contains("io_loadCrossesLine"))
  }

  test("resident forwarding separates physical STQ rows from ROB identity width") {
    val io = new ReducedStoreResidentForwardIO(entries = 16, robEntries = 8)

    assert(io.rows.length == 16)
    assert(io.eligibleStoreMask.getWidth == 16)
    assert(io.waitStore.storeIndex.getWidth == 4)
    assert(io.loadBid.value.getWidth == 3)
    assert(io.waitStore.storeId.value.getWidth == 3)
  }

  test("resident forwarding preserves parameterized full LSID authority after selection") {
    val io = new ReducedStoreResidentForwardIO(entries = 16, robEntries = 8, lsidWidth = 40)

    assert(io.rows.head.lsIdFull.getWidth == 40)
    assert(io.loadLsIdFull.getWidth == 40)
    assert(io.waitStore.storeLsIdFull.getWidth == 40)
  }
}
