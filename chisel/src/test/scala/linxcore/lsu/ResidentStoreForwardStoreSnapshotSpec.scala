package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object ResidentStoreForwardStoreSnapshotReference {
  import STQFlushPruneReference.Id

  final case class Row(
      valid: Boolean = true,
      waitState: Boolean = true,
      addrReady: Boolean = true,
      dataReady: Boolean = true,
      scalar: Boolean = true,
      bid: Id = Id(),
      lsId: Id = Id(),
      lsIdFull: BigInt = 0,
      pc: BigInt = 0,
      addr: BigInt = 0x1000,
      data: BigInt = 0,
      size: Int = 8)

  final case class Store(
      valid: Boolean,
      working: Boolean,
      addrReady: Boolean,
      dataReady: Boolean,
      isTile: Boolean,
      storeLsIdFullValid: Boolean,
      storeLsIdFull: BigInt,
      lineAddr: BigInt,
      byteMask: BigInt,
      data: BigInt)

  final case class Result(validMask: BigInt, waitMask: BigInt, crossLineMask: BigInt, stores: Seq[Store])

  private val LineBytes = 64

  private def lineAddr(addr: BigInt): BigInt =
    addr & ~BigInt(0x3f)

  private def crossesLine(addr: BigInt, size: Int): Boolean =
    ((addr & 0x3f) + size) > LineBytes

  private def byteMask(addr: BigInt, size: Int): BigInt = {
    val offset = (addr & 0x3f).toInt
    (0 until LineBytes).foldLeft(BigInt(0)) { case (acc, byte) =>
      if (size != 0 && byte >= offset && byte < offset + size) acc | (BigInt(1) << byte) else acc
    }
  }

  private def lineData(addr: BigInt, data: BigInt, mask: BigInt): BigInt = {
    val offset = (addr & 0x3f).toInt
    (0 until LineBytes).foldLeft(BigInt(0)) { case (acc, byte) =>
      if (((mask >> byte) & 1) == 1) {
        val sourceByte = (data >> ((byte - offset) * 8)) & 0xff
        acc | (sourceByte << (byte * 8))
      } else {
        acc
      }
    }
  }

  def apply(enable: Boolean, rows: Seq[Row]): Result = {
    val stores = rows.map { row =>
      val rowWait = row.valid && row.waitState
      val rowCrosses = crossesLine(row.addr, row.size)
      val mask = byteMask(row.addr, row.size)
      Store(
        valid = enable && rowWait && !rowCrosses,
        working = row.waitState,
        addrReady = row.addrReady,
        dataReady = row.dataReady,
        isTile = !row.scalar,
        storeLsIdFullValid = row.valid,
        storeLsIdFull = row.lsIdFull,
        lineAddr = lineAddr(row.addr),
        byteMask = mask,
        data = lineData(row.addr, row.data, mask)
      )
    }
    val validMask = stores.zipWithIndex.foldLeft(BigInt(0)) { case (acc, (store, idx)) =>
      if (store.valid) acc | (BigInt(1) << idx) else acc
    }
    val waitMask = rows.zipWithIndex.foldLeft(BigInt(0)) { case (acc, (row, idx)) =>
      if (row.valid && row.waitState) acc | (BigInt(1) << idx) else acc
    }
    val crossMask = rows.zipWithIndex.foldLeft(BigInt(0)) { case (acc, (row, idx)) =>
      if (row.valid && row.waitState && crossesLine(row.addr, row.size)) acc | (BigInt(1) << idx) else acc
    }
    Result(validMask, waitMask, crossMask, stores)
  }
}

class ResidentStoreForwardStoreSnapshotSpec extends AnyFunSuite {
  import ResidentStoreForwardStoreSnapshotReference._

  test("ready resident wait row becomes a forwarding store with positioned bytes") {
    val result = ResidentStoreForwardStoreSnapshotReference(
      enable = true,
      rows = Seq(Row(addr = 0x1002, data = BigInt("11223344", 16), size = 4)))

    assert(result.validMask == 1)
    assert(result.waitMask == 1)
    assert(result.crossLineMask == 0)
    assert(result.stores.head.byteMask == BigInt("3c", 16))
    assert((result.stores.head.data >> (2 * 8)) == BigInt("11223344", 16))
  }

  test("cross-line rows are reported but suppressed from forwarding validity") {
    val result = ResidentStoreForwardStoreSnapshotReference(
      enable = true,
      rows = Seq(Row(addr = 0x103e, data = BigInt("aabb", 16), size = 4)))

    assert(result.validMask == 0)
    assert(result.waitMask == 1)
    assert(result.crossLineMask == 1)
  }

  test("disabled snapshot preserves row diagnostics while deasserting store valid") {
    val result = ResidentStoreForwardStoreSnapshotReference(
      enable = false,
      rows = Seq(Row(addr = 0x2000, data = BigInt("55", 16), size = 1)))

    assert(result.validMask == 0)
    assert(result.waitMask == 1)
    assert(result.stores.head.byteMask == 1)
  }

  test("Chisel ResidentStoreForwardStoreSnapshot elaborates forward-store vector") {
    val sv = ChiselStage.emitSystemVerilog(new ResidentStoreForwardStoreSnapshot(
      entries = 8, lsidWidth = 40))

    assert(sv.contains("module ResidentStoreForwardStoreSnapshot"))
    assert(sv.contains("io_stores_0_valid"))
    assert(sv.contains("io_stores_0_storeId_value"))
    assert(sv.contains("io_stores_0_storeLsIdFull"))
    assert(sv.contains("io_stores_0_byteMask"))
    assert(sv.contains("io_validMask"))
    assert(sv.contains("io_crossLineMask"))
  }

  test("resident store snapshot separates physical rows from ROB identities") {
    val io = new ResidentStoreForwardStoreSnapshotIO(entries = 16, robEntries = 8, lsidWidth = 40)

    assert(io.rows.length == 16)
    assert(io.stores.length == 16)
    assert(io.validMask.getWidth == 16)
    assert(io.stores.head.storeIndex.getWidth == 4)
    assert(io.stores.head.storeId.value.getWidth == 3)
    assert(io.rows.head.lsIdFull.getWidth == 40)
    assert(io.stores.head.storeLsIdFull.getWidth == 40)
  }
}
