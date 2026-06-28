package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object SCBCommitBridgeReference {
  final case class Request(valid: Boolean, addr: BigInt, data: BigInt, size: Int, stqIndex: Int, last: Boolean)
  final case class Line(lineAddr: BigInt, mask: BigInt)
  final case class StepResult(
      modelBatchReady: Boolean,
      accepted: Seq[Boolean],
      stalled: Seq[Boolean],
      freeMask: BigInt,
      lines: Seq[Line])

  final class Model(entries: Int, requestCount: Int) {
    private var lines = Vector.empty[Line]

    def seed(lineAddrs: Seq[BigInt]): Unit = {
      require(lineAddrs.size <= entries)
      lines = lineAddrs.map(addr => Line(addr & ~BigInt(0x3f), BigInt(1))).toVector
    }

    def step(reqs: Seq[Request]): StepResult = {
      require(reqs.size == requestCount)
      val accepted = Array.fill(reqs.size)(false)
      val stalled = Array.fill(reqs.size)(false)
      var freeMask = BigInt(0)
      val modelBatchReady = (entries - lines.size) >= requestCount

      if (!modelBatchReady) {
        reqs.zipWithIndex.foreach { case (req, lane) =>
          stalled(lane) = req.valid
        }
        return StepResult(modelBatchReady, accepted.toSeq, stalled.toSeq, freeMask, lines)
      }

      reqs.zipWithIndex.foreach { case (req, lane) =>
        if (req.valid) {
          val lineAddr = req.addr & ~BigInt(0x3f)
          val hit = lines.indexWhere(_.lineAddr == lineAddr)
          val target =
            if (hit >= 0) hit
            else if (lines.size < entries) {
              lines = lines :+ Line(lineAddr, BigInt(0))
              lines.size - 1
            } else {
              -1
            }

          if (target < 0) {
            stalled(lane) = true
          } else {
            val merged = merge(lines(target), req)
            lines = lines.updated(target, merged)
            accepted(lane) = true
            if (req.last) {
              freeMask = freeMask | (BigInt(1) << req.stqIndex)
            }
          }
        }
      }

      StepResult(modelBatchReady, accepted.toSeq, stalled.toSeq, freeMask, lines)
    }

    private def merge(line: Line, req: Request): Line = {
      val off = (req.addr & 0x3f).toInt
      val bytes = (0 until req.size).foldLeft(BigInt(0)) { case (mask, idx) => mask | (BigInt(1) << (off + idx)) }
      line.copy(mask = line.mask | bytes)
    }
  }
}

class SCBCommitBridgeSpec extends AnyFunSuite {
  import SCBCommitBridgeReference._

  test("model batch gate admits a single-line store only when enough free entries remain for the width") {
    val bridge = new Model(entries = 4, requestCount = 2)
    bridge.seed(Seq(0x1000, 0x2000))
    val result = bridge.step(Seq(
      Request(valid = true, addr = 0x3004, data = 0x1122, size = 2, stqIndex = 1, last = true),
      Request(valid = false, addr = 0, data = 0, size = 0, stqIndex = 0, last = false)
    ))

    assert(result.modelBatchReady)
    assert(result.accepted == Seq(true, false))
    assert(result.stalled == Seq(false, false))
    assert(result.freeMask == (BigInt(1) << 1))
    assert(result.lines.exists(line => line.lineAddr == BigInt(0x3000) && line.mask == (BigInt(3) << 4)))
  }

  test("split store frees the STQ row only on the accepted last fragment") {
    val bridge = new Model(entries = 4, requestCount = 2)
    val result = bridge.step(Seq(
      Request(valid = true, addr = 0x403e, data = BigInt("7788", 16), size = 2, stqIndex = 3, last = false),
      Request(valid = true, addr = 0x4040, data = BigInt("112233445566", 16), size = 6, stqIndex = 3, last = true)
    ))

    assert(result.modelBatchReady)
    assert(result.accepted == Seq(true, true))
    assert(result.freeMask == (BigInt(1) << 3))
    assert(result.lines.map(_.lineAddr) == Seq(BigInt(0x4000), BigInt(0x4040)))
    assert(result.lines.head.mask == (BigInt(3) << 62))
    assert(result.lines(1).mask == BigInt("3f", 16))
  }

  test("model-full batch gate stalls even same-line hits when free entries are below commit width") {
    val bridge = new Model(entries = 2, requestCount = 2)
    bridge.seed(Seq(0x5000))
    val result = bridge.step(Seq(
      Request(valid = true, addr = 0x5008, data = 0xaa, size = 1, stqIndex = 0, last = true),
      Request(valid = false, addr = 0, data = 0, size = 0, stqIndex = 1, last = false)
    ))

    assert(!result.modelBatchReady)
    assert(result.accepted == Seq(false, false))
    assert(result.stalled == Seq(true, false))
    assert(result.freeMask == BigInt(0))
    assert(result.lines == Seq(Line(BigInt(0x5000), BigInt(1))))
  }

  test("batch gate admits all request lanes when free count equals commit width") {
    val bridge = new Model(entries = 2, requestCount = 2)
    val result = bridge.step(Seq(
      Request(valid = true, addr = 0x6000, data = 0x11, size = 1, stqIndex = 0, last = true),
      Request(valid = true, addr = 0x6040, data = 0x22, size = 1, stqIndex = 1, last = true)
    ))

    assert(result.modelBatchReady)
    assert(result.accepted == Seq(true, true))
    assert(result.stalled == Seq(false, false))
    assert(result.freeMask == BigInt(3))
    assert(result.lines.map(_.lineAddr) == Seq(BigInt(0x6000), BigInt(0x6040)))
  }

  test("Chisel SCBCommitBridge elaborates with model batch and STQ free outputs") {
    val sv = ChiselStage.emitSystemVerilog(new SCBCommitBridge(stqEntries = 8, scbEntries = 4, requestCount = 2))

    assert(sv.contains("module SCBCommitBridge"))
    assert(sv.contains("SCBCommitIngress"))
    assert(sv.contains("io_modelBatchReady"))
    assert(sv.contains("io_stalledMask"))
    assert(sv.contains("io_commitFreeMask"))
  }
}
