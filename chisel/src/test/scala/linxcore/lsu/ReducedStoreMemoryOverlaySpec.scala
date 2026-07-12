package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object ReducedStoreMemoryOverlayReference {
  final case class Request(valid: Boolean, accepted: Boolean, addr: BigInt, data: BigInt, size: Int)
  final case class Line(lineAddr: BigInt, data: Vector[Int], mask: BigInt)
  final case class StepResult(lines: Seq[Line], lineCount: Int, dropped: Seq[Boolean])
  final case class LoadResult(data: BigInt, forwardMask: BigInt)

  final class Model(entries: Int) {
    private var lines = Vector.empty[Line]

    def snapshot: Seq[Line] = lines

    def step(reqs: Seq[Request], flush: Boolean = false): StepResult = {
      val dropped = Array.fill(reqs.size)(false)

      if (flush) {
        lines = Vector.empty
      } else {
        reqs.zipWithIndex.foreach { case (req, lane) =>
          if (req.valid && req.accepted) {
            val lineAddr = req.addr & ~BigInt(0x3f)
            val hit = lines.indexWhere(_.lineAddr == lineAddr)
            val target =
              if (hit >= 0) {
                hit
              } else if (lines.size < entries) {
                lines = lines :+ Line(lineAddr, Vector.fill(64)(0), BigInt(0))
                lines.size - 1
              } else {
                -1
              }

            if (target < 0) {
              dropped(lane) = true
            } else {
              lines = lines.updated(target, merge(lines(target), req))
            }
          }
        }
      }

      StepResult(lines = lines, lineCount = lines.size, dropped = dropped.toSeq)
    }

    def load(addr: BigInt, baseData: BigInt, valid: Boolean = true): LoadResult = {
      var data = BigInt(0)
      var forwardMask = BigInt(0)
      for (idx <- 0 until 8) {
        val byteAddr = addr + idx
        val lineAddr = byteAddr & ~BigInt(0x3f)
        val offset = (byteAddr & 0x3f).toInt
        val baseByte = ((baseData >> (idx * 8)) & 0xff).toInt
        val line = lines.find(line => line.lineAddr == lineAddr && ((line.mask >> offset) & 1) == 1)
        val selected = if (valid) line.map(_.data(offset)).getOrElse(baseByte) else baseByte
        if (valid && line.nonEmpty) {
          forwardMask |= BigInt(1) << idx
        }
        data |= BigInt(selected) << (idx * 8)
      }
      LoadResult(data, forwardMask)
    }

    private def merge(line: Line, req: Request): Line = {
      val off = (req.addr & 0x3f).toInt
      var data = line.data
      var mask = line.mask
      for (idx <- 0 until req.size) {
        val byte = ((req.data >> (idx * 8)) & 0xff).toInt
        data = data.updated(off + idx, byte)
        mask |= BigInt(1) << (off + idx)
      }
      line.copy(data = data, mask = mask)
    }
  }
}

class ReducedStoreMemoryOverlaySpec extends AnyFunSuite {
  import ReducedStoreMemoryOverlayReference._

  test("accepted same-line stores merge and overlay load bytes over base data") {
    val overlay = new Model(entries = 4)
    overlay.step(Seq(Request(valid = true, accepted = true, addr = 0x1002, data = BigInt("1122334455667788", 16), size = 4)))
    val result = overlay.step(Seq(Request(valid = true, accepted = true, addr = 0x1006, data = BigInt("aabb", 16), size = 2)))
    val load = overlay.load(addr = 0x1000, baseData = BigInt("ffeeddccbbaa9988", 16))

    assert(result.lineCount == 1)
    assert(result.lines.head.mask == BigInt("fc", 16))
    assert(result.lines.head.data.slice(2, 8) == Seq(0x88, 0x77, 0x66, 0x55, 0xbb, 0xaa))
    assert(load.forwardMask == BigInt("fc", 16))
    assert(load.data == BigInt("aabb556677889988", 16))
  }

  test("accepted mask gates store visibility") {
    val overlay = new Model(entries = 2)
    overlay.step(Seq(Request(valid = true, accepted = false, addr = 0x2000, data = BigInt("0102030405060708", 16), size = 8)))
    val load = overlay.load(addr = 0x2000, baseData = BigInt("8877665544332211", 16))

    assert(overlay.snapshot.isEmpty)
    assert(load.forwardMask == BigInt(0))
    assert(load.data == BigInt("8877665544332211", 16))
  }

  test("same-cycle overlapping requests rely on parent old-to-young lane order") {
    val overlay = new Model(entries = 2)
    overlay.step(Seq(
      Request(valid = true, accepted = true, addr = 0x2800, data = BigInt("1122", 16), size = 2),
      Request(valid = true, accepted = true, addr = 0x2800, data = BigInt("3344", 16), size = 2)
    ))
    val load = overlay.load(addr = 0x2800, baseData = BigInt(0))

    assert(load.forwardMask == BigInt("03", 16))
    assert((load.data & BigInt("ffff", 16)) == BigInt("3344", 16))
  }

  test("cross-line load merges bytes from adjacent accepted store fragments") {
    val overlay = new Model(entries = 4)
    overlay.step(Seq(
      Request(valid = true, accepted = true, addr = 0x203e, data = BigInt("7788", 16), size = 2),
      Request(valid = true, accepted = true, addr = 0x2040, data = BigInt("112233445566", 16), size = 6)
    ))
    val load = overlay.load(addr = 0x203c, baseData = BigInt("deadbeefcafebabe", 16))

    assert(overlay.snapshot.map(_.lineAddr) == Seq(BigInt(0x2000), BigInt(0x2040)))
    assert(load.forwardMask == BigInt("fc", 16))
    assert(load.data == BigInt("334455667788babe", 16))
  }

  test("capacity diagnostics report accepted stores that have no matching or free line") {
    val overlay = new Model(entries = 1)
    overlay.step(Seq(Request(valid = true, accepted = true, addr = 0x3000, data = 0x11, size = 1)))
    val dropped = overlay.step(Seq(Request(valid = true, accepted = true, addr = 0x3040, data = 0x22, size = 1)))
    val load = overlay.load(addr = 0x3040, baseData = BigInt("0102030405060708", 16))

    assert(dropped.dropped == Seq(true))
    assert(dropped.lineCount == 1)
    assert(load.forwardMask == BigInt(0))
    assert(load.data == BigInt("0102030405060708", 16))
  }

  test("flush clears recorded committed-store overlay lines") {
    val overlay = new Model(entries = 2)
    overlay.step(Seq(Request(valid = true, accepted = true, addr = 0x4000, data = 0x44, size = 1)))
    val result = overlay.step(Seq.empty, flush = true)

    assert(result.lines.isEmpty)
    assert(result.lineCount == 0)
    assert(overlay.load(addr = 0x4000, baseData = 0x55).data == BigInt(0x55))
  }

  test("Chisel ReducedStoreMemoryOverlay elaborates with store and load overlay IO") {
    val sv = ChiselStage.emitSystemVerilog(new ReducedStoreMemoryOverlay(stqEntries = 8, requestCount = 4, lineEntries = 4))

    assert(sv.contains("module ReducedStoreMemoryOverlay"))
    assert(sv.contains("io_storeReqs_0_valid"))
    assert(sv.contains("io_storeAcceptedMask"))
    assert(sv.contains("io_loadForwardMask"))
    assert(sv.contains("io_storeDroppedMask"))
    assert(sv.contains("io_lines_0_byteMask"))
  }

  test("physical STQ indices remain independent of ROB identity width") {
    val io = new ReducedStoreMemoryOverlayIO(
      stqEntries = 16,
      requestCount = 4,
      lineEntries = 4,
      robEntries = 8)

    assert(io.storeReqs.head.stqIndex.getWidth == 4)
    assert(io.storeReqs.head.bid.value.getWidth == 3)
    assert(io.storeReqs.head.lsId.value.getWidth == 3)

    val sv = ChiselStage.emitSystemVerilog(
      new ReducedStoreMemoryOverlay(stqEntries = 16, requestCount = 4, lineEntries = 4, robEntries = 8))
    assert(sv.contains("module ReducedStoreMemoryOverlay"))
    assert(sv.contains("io_storeReqs_0_bid_value"))
  }
}
