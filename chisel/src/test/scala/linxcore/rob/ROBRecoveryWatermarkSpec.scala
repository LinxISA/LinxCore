package linxcore.rob

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object ROBRecoveryWatermarkReference {
  final case class Row(
      valid: Boolean,
      status: String,
      stid: Int,
      ridValue: Int,
      ridWrap: Boolean,
      blockBid: BigInt)

  final case class Watermark(ridValue: Int, ridWrap: Boolean, blockBid: BigInt)

  def select(rows: Vector[Row], commitHead: Int, stidCount: Int): Vector[Option[Watermark]] = {
    require(rows.size > 1 && (rows.size & (rows.size - 1)) == 0)
    Vector.tabulate(stidCount) { stid =>
      (0 until rows.size).iterator
        .map(offset => rows((commitHead + offset) % rows.size))
        .find(row => row.valid && row.stid == stid && row.status != "Free" && row.status != "Retired")
        .map(row => Watermark(row.ridValue, row.ridWrap, row.blockBid))
    }
  }
}

class ROBRecoveryWatermarkSpec extends AnyFunSuite {
  import ROBRecoveryWatermarkReference._

  test("reference selects independently per STID in circular commit order") {
    val rows = Vector(
      Row(valid = true, "Allocated", stid = 1, ridValue = 0, ridWrap = true, blockBid = 8),
      Row(valid = true, "Allocated", stid = 0, ridValue = 1, ridWrap = true, blockBid = 9),
      Row(valid = true, "Completed", stid = 1, ridValue = 2, ridWrap = false, blockBid = 6),
      Row(valid = true, "Allocated", stid = 0, ridValue = 3, ridWrap = false, blockBid = 7)
    )

    assert(select(rows, commitHead = 2, stidCount = 2) == Vector(
      Some(Watermark(ridValue = 3, ridWrap = false, blockBid = 7)),
      Some(Watermark(ridValue = 2, ridWrap = false, blockBid = 6))
    ))
  }

  test("reference skips retired free and invalid rows while preserving RID wrap") {
    val rows = Vector(
      Row(valid = true, "Retired", stid = 0, ridValue = 0, ridWrap = false, blockBid = 0),
      Row(valid = false, "Allocated", stid = 0, ridValue = 1, ridWrap = false, blockBid = 1),
      Row(valid = true, "Free", stid = 0, ridValue = 2, ridWrap = false, blockBid = 2),
      Row(valid = true, "NeedFlush", stid = 0, ridValue = 3, ridWrap = true, blockBid = 11)
    )

    assert(select(rows, commitHead = 0, stidCount = 2) == Vector(
      Some(Watermark(ridValue = 3, ridWrap = true, blockBid = 11)),
      None
    ))
  }

  test("Chisel selector elaborates parameterized per-STID identity outputs") {
    val sv = ChiselStage.emitSystemVerilog(
      new ROBRecoveryWatermark(entries = 8, stidCount = 2, stidWidth = 3, blockBidWidth = 40)
    )

    assert(sv.contains("module ROBRecoveryWatermark"))
    assert(sv.contains("io_commitHead"))
    assert(sv.contains("io_rowRid_0_wrap"))
    assert(sv.contains("io_rowBlockBid_0"))
    assert(sv.contains("io_oldestValid_1"))
    assert(sv.contains("io_oldestRid_1_wrap"))
    assert(sv.contains("io_oldestBlockBid_1"))
  }
}
