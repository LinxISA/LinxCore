package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayBaseDataAlignReference {
  private val LineBytes = 64
  private val LoadBytes = 8

  final case class Result(
      requestValid: Boolean,
      crossesLine: Boolean,
      requestByteMask: BigInt,
      lineData: BigInt,
      lineValidMask: BigInt,
      dataReturned: Boolean)

  private def byte(data: BigInt, idx: Int): BigInt =
    (data >> (idx * 8)) & 0xff

  def apply(enable: Boolean, loadValid: Boolean, addr: BigInt, size: Int, data: BigInt): Result = {
    val offset = (addr & 0x3f).toInt
    val crosses = enable && loadValid && size != 0 && (offset + size > LineBytes)
    val valid = enable && loadValid && size != 0 && !crosses
    val mask =
      if (valid) (0 until LineBytes).foldLeft(BigInt(0)) { case (acc, lane) =>
        if (lane >= offset && lane < offset + size) acc | (BigInt(1) << lane) else acc
      }
      else BigInt(0)
    val lineData =
      if (valid) (0 until LoadBytes).foldLeft(BigInt(0)) { case (acc, lane) =>
        acc | (byte(data, lane) << ((offset + lane) * 8))
      }
      else BigInt(0)

    Result(
      requestValid = valid,
      crossesLine = crosses,
      requestByteMask = mask,
      lineData = lineData,
      lineValidMask = mask,
      dataReturned = valid)
  }
}

class LoadReplayBaseDataAlignSpec extends AnyFunSuite {
  import LoadReplayBaseDataAlignReference._

  test("aligns a scalar sparse-memory response into a 64-byte line") {
    val result = LoadReplayBaseDataAlignReference(
      enable = true,
      loadValid = true,
      addr = 0x1014,
      size = 4,
      data = BigInt("8877665544332211", 16))

    assert(result.requestValid)
    assert(!result.crossesLine)
    assert(result.requestByteMask == (BigInt("f", 16) << 20))
    assert(result.lineValidMask == result.requestByteMask)
    assert(((result.lineData >> (20 * 8)) & BigInt("ffffffff", 16)) == BigInt("44332211", 16))
    assert(result.dataReturned)
  }

  test("suppresses zero-size and disabled requests") {
    val zeroSize = LoadReplayBaseDataAlignReference(
      enable = true,
      loadValid = true,
      addr = 0x2000,
      size = 0,
      data = 0x55)
    val disabled = LoadReplayBaseDataAlignReference(
      enable = false,
      loadValid = true,
      addr = 0x2000,
      size = 8,
      data = 0x55)

    assert(!zeroSize.requestValid)
    assert(!zeroSize.dataReturned)
    assert(zeroSize.requestByteMask == 0)
    assert(!disabled.requestValid)
    assert(disabled.lineData == 0)
  }

  test("reports cross-line requests without publishing base data") {
    val result = LoadReplayBaseDataAlignReference(
      enable = true,
      loadValid = true,
      addr = 0x303e,
      size = 8,
      data = BigInt("0102030405060708", 16))

    assert(!result.requestValid)
    assert(result.crossesLine)
    assert(result.requestByteMask == 0)
    assert(result.lineData == 0)
    assert(!result.dataReturned)
  }

  test("Chisel LoadReplayBaseDataAlign elaborates replay base-data diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayBaseDataAlign())

    assert(sv.contains("module LoadReplayBaseDataAlign"))
    assert(sv.contains("io_requestValid"))
    assert(sv.contains("io_requestCrossesLine"))
    assert(sv.contains("io_requestByteMask"))
    assert(sv.contains("io_lineData"))
    assert(sv.contains("io_lineValidMask"))
    assert(sv.contains("io_dataReturned"))
  }
}
