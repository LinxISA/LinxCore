package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadReplayReturnDataExtractReference {
  private val LineBytes = 64
  private val DataBytes = 8
  private val Mask64 = (BigInt(1) << 64) - 1

  final case class Result(
      candidateValid: Boolean,
      requestByteMask: BigInt,
      bytesComplete: Boolean,
      crossLine: Boolean,
      sizeSupported: Boolean,
      rawData: BigInt,
      data: BigInt,
      dataValid: Boolean,
      blockedByDisabled: Boolean,
      blockedByNoCandidate: Boolean,
      blockedByZeroSize: Boolean,
      blockedByUnsupportedSize: Boolean,
      blockedByCrossLine: Boolean,
      blockedByIncompleteBytes: Boolean)

  def lineData(bytes: Map[Int, Int]): BigInt =
    bytes.foldLeft(BigInt(0)) { case (acc, (idx, value)) =>
      acc | ((BigInt(value) & 0xff) << (idx * 8))
    }

  def byte(data: BigInt, idx: Int): BigInt =
    (data >> (idx * 8)) & 0xff

  private def byteMask(offset: Int, size: Int): BigInt =
    (0 until LineBytes).foldLeft(BigInt(0)) { case (acc, idx) =>
      if (idx >= offset && idx < offset + size) acc | (BigInt(1) << idx) else acc
    }

  private def extend(raw: BigInt, size: Int, signExtend: Boolean): BigInt = {
    val bits = size * 8
    val mask = (BigInt(1) << bits) - 1
    val masked = raw & mask
    if (!signExtend || size == DataBytes) masked & Mask64
    else {
      val sign = (masked >> (bits - 1)) & 1
      if (sign == 1) (masked | (Mask64 ^ mask)) & Mask64 else masked
    }
  }

  def apply(
      enable: Boolean,
      returnValid: Boolean,
      line: BigInt,
      validMask: BigInt,
      addr: BigInt,
      size: Int,
      signExtend: Boolean): Result = {
    val candidate = enable && returnValid
    val offset = (addr & 0x3f).toInt
    val nonZeroSize = size != 0
    val supported = Set(1, 2, 4, 8).contains(size)
    val cross = candidate && nonZeroSize && offset + size > LineBytes
    val extractCandidate = candidate && nonZeroSize && supported && !cross
    val requestMask = if (extractCandidate) byteMask(offset, size) else BigInt(0)
    val complete = extractCandidate && requestMask != 0 && (validMask & requestMask) == requestMask
    val raw =
      if (extractCandidate) {
        (0 until math.min(size, DataBytes)).foldLeft(BigInt(0)) { case (acc, idx) =>
          acc | (byte(line, offset + idx) << (idx * 8))
        }
      } else {
        BigInt(0)
      }
    val valid = extractCandidate && complete
    val data = if (valid) extend(raw, size, signExtend) else BigInt(0)

    Result(
      candidateValid = candidate,
      requestByteMask = requestMask,
      bytesComplete = complete,
      crossLine = cross,
      sizeSupported = candidate && nonZeroSize && supported,
      rawData = raw,
      data = data,
      dataValid = valid,
      blockedByDisabled = !enable && returnValid,
      blockedByNoCandidate = enable && !returnValid,
      blockedByZeroSize = candidate && !nonZeroSize,
      blockedByUnsupportedSize = candidate && nonZeroSize && !supported,
      blockedByCrossLine = candidate && nonZeroSize && supported && cross,
      blockedByIncompleteBytes = extractCandidate && !complete)
  }
}

class LoadReplayReturnDataExtractSpec extends AnyFunSuite {
  import LoadReplayReturnDataExtractReference._

  test("extracts little-endian scalar data from a 64-byte line") {
    val line = lineData((0 until 8).map(idx => 16 + idx -> ((idx + 1) * 0x11)).toMap)
    val result = LoadReplayReturnDataExtractReference(
      enable = true,
      returnValid = true,
      line = line,
      validMask = BigInt("ff", 16) << 16,
      addr = 0x1010,
      size = 8,
      signExtend = false)

    assert(result.candidateValid)
    assert(result.requestByteMask == (BigInt("ff", 16) << 16))
    assert(result.bytesComplete)
    assert(result.rawData == BigInt("8877665544332211", 16))
    assert(result.data == BigInt("8877665544332211", 16))
    assert(result.dataValid)
  }

  test("sign-extends byte, halfword, and word results when requested") {
    val byteLine = lineData(Map(5 -> 0x80))
    val halfLine = lineData(Map(6 -> 0x34, 7 -> 0x80))
    val wordLine = lineData(Map(8 -> 0, 9 -> 0, 10 -> 0, 11 -> 0x80))

    assert(LoadReplayReturnDataExtractReference(true, true, byteLine, BigInt(1) << 5, 0x1005, 1, true).data ==
      BigInt("ffffffffffffff80", 16))
    assert(LoadReplayReturnDataExtractReference(true, true, halfLine, BigInt(3) << 6, 0x1006, 2, true).data ==
      BigInt("ffffffffffff8034", 16))
    assert(LoadReplayReturnDataExtractReference(true, true, wordLine, BigInt("f", 16) << 8, 0x1008, 4, true).data ==
      BigInt("ffffffff80000000", 16))
  }

  test("zero-extends unsigned byte, halfword, and word results") {
    val line = lineData(Map(0 -> 0xff, 2 -> 0xff, 3 -> 0x80, 8 -> 0xff, 9 -> 0xff, 10 -> 0xff, 11 -> 0x80))

    assert(LoadReplayReturnDataExtractReference(true, true, line, BigInt(1), 0x1000, 1, false).data == 0xff)
    assert(LoadReplayReturnDataExtractReference(true, true, line, BigInt(3) << 2, 0x1002, 2, false).data == 0x80ff)
    assert(LoadReplayReturnDataExtractReference(true, true, line, BigInt("f", 16) << 8, 0x1008, 4, false).data ==
      BigInt("80ffffff", 16))
  }

  test("blocks incomplete lines without publishing return data") {
    val line = lineData(Map(20 -> 0xaa, 21 -> 0xbb, 22 -> 0xcc, 23 -> 0xdd))
    val result = LoadReplayReturnDataExtractReference(
      enable = true,
      returnValid = true,
      line = line,
      validMask = BigInt(3) << 20,
      addr = 0x1014,
      size = 4,
      signExtend = false)

    assert(result.requestByteMask == (BigInt("f", 16) << 20))
    assert(!result.bytesComplete)
    assert(!result.dataValid)
    assert(result.blockedByIncompleteBytes)
    assert(result.data == 0)
  }

  test("reports cross-line and unsupported-size blockers") {
    val cross = LoadReplayReturnDataExtractReference(true, true, 0, 0, 0x103e, 4, false)
    val unsupported = LoadReplayReturnDataExtractReference(true, true, 0, 0, 0x1000, 3, false)

    assert(cross.crossLine)
    assert(cross.blockedByCrossLine)
    assert(!cross.dataValid)
    assert(unsupported.blockedByUnsupportedSize)
    assert(!unsupported.dataValid)
  }

  test("reports disabled, empty, and zero-size candidates") {
    val disabled = LoadReplayReturnDataExtractReference(false, true, 0, 0, 0x1000, 8, false)
    val empty = LoadReplayReturnDataExtractReference(true, false, 0, 0, 0x1000, 8, false)
    val zero = LoadReplayReturnDataExtractReference(true, true, 0, 0, 0x1000, 0, false)

    assert(disabled.blockedByDisabled)
    assert(empty.blockedByNoCandidate)
    assert(zero.blockedByZeroSize)
    assert(!disabled.dataValid && !empty.dataValid && !zero.dataValid)
  }

  test("Chisel LoadReplayReturnDataExtract elaborates return-data diagnostics") {
    val sv = ChiselStage.emitSystemVerilog(new LoadReplayReturnDataExtract())

    assert(sv.contains("module LoadReplayReturnDataExtract"))
    assert(sv.contains("io_requestByteMask"))
    assert(sv.contains("io_bytesComplete"))
    assert(sv.contains("io_rawData"))
    assert(sv.contains("io_dataValid"))
    assert(sv.contains("io_blockedByIncompleteBytes"))
  }
}
