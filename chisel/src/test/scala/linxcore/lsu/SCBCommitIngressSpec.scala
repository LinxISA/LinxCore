package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object SCBCommitIngressReference {
  final case class Request(valid: Boolean, addr: BigInt, data: BigInt, size: Int, index: Int = 0)
  final case class Line(lineAddr: BigInt, data: Vector[Int], mask: BigInt) {
    def full: Boolean = mask == ((BigInt(1) << 64) - 1)
  }
  final case class Wakeup(valid: Boolean, lineAddr: BigInt, byteMask: BigInt)
  final case class StepResult(accepted: Seq[Boolean], blocked: Seq[Boolean], wakeups: Seq[Wakeup], lines: Seq[Line])

  final class Model(entries: Int) {
    private var lines = Vector.empty[Line]

    def snapshot: Seq[Line] = lines
    def entryCount: Int = lines.size
    def full: Boolean = lines.size == entries

    def step(reqs: Seq[Request]): StepResult = {
      val accepted = Array.fill(reqs.size)(false)
      val blocked = Array.fill(reqs.size)(false)
      val wakeups = Array.fill(reqs.size)(Wakeup(valid = false, lineAddr = 0, byteMask = 0))

      reqs.zipWithIndex.foreach { case (req, lane) =>
        if (req.valid) {
          val lineAddr = req.addr & ~BigInt(0x3f)
          val hit = lines.indexWhere(_.lineAddr == lineAddr)
          val target =
            if (hit >= 0) hit
            else if (lines.size < entries) {
              lines = lines :+ Line(lineAddr, Vector.fill(64)(0), BigInt(0))
              lines.size - 1
            } else {
              -1
            }

          if (target < 0) {
            blocked(lane) = true
          } else {
            val merged = merge(lines(target), req)
            lines = lines.updated(target, merged)
            accepted(lane) = true
            wakeups(lane) = Wakeup(valid = true, lineAddr = lineAddr, byteMask = merged.mask)
          }
        }
      }

      StepResult(accepted = accepted.toSeq, blocked = blocked.toSeq, wakeups = wakeups.toSeq, lines = lines)
    }

    private def merge(line: Line, req: Request): Line = {
      val off = (req.addr & 0x3f).toInt
      var data = line.data
      var mask = line.mask
      for (idx <- 0 until req.size) {
        val byte = ((req.data >> (idx * 8)) & 0xff).toInt
        data = data.updated(off + idx, byte)
        mask = mask | (BigInt(1) << (off + idx))
      }
      line.copy(data = data, mask = mask)
    }
  }
}

class SCBCommitIngressSpec extends AnyFunSuite {
  import SCBCommitIngressReference._

  test("first store to a cacheline allocates one SCB entry and publishes byte wakeup mask") {
    val scb = new Model(entries = 2)
    val result = scb.step(Seq(Request(valid = true, addr = 0x1004, data = BigInt("1122334455667788", 16), size = 8)))

    assert(result.accepted == Seq(true))
    assert(result.blocked == Seq(false))
    assert(result.lines.size == 1)
    assert(result.lines.head.lineAddr == BigInt(0x1000))
    assert(result.lines.head.mask == (BigInt("ff", 16) << 4))
    assert(result.lines.head.data.slice(4, 12) == Seq(0x88, 0x77, 0x66, 0x55, 0x44, 0x33, 0x22, 0x11))
    assert(result.wakeups == Seq(Wakeup(valid = true, lineAddr = 0x1000, byteMask = BigInt("ff", 16) << 4)))
  }

  test("same-line stores merge without consuming another entry") {
    val scb = new Model(entries = 2)
    scb.step(Seq(Request(valid = true, addr = 0x2000, data = BigInt("aabbccdd", 16), size = 4)))
    val result = scb.step(Seq(Request(valid = true, addr = 0x2004, data = BigInt("11223344", 16), size = 4)))

    assert(result.accepted == Seq(true))
    assert(result.lines.size == 1)
    assert(result.lines.head.mask == BigInt("ff", 16))
    assert(result.lines.head.data.slice(0, 8) == Seq(0xdd, 0xcc, 0xbb, 0xaa, 0x44, 0x33, 0x22, 0x11))
  }

  test("full SCB blocks a new line but still accepts a hit to an existing line") {
    val scb = new Model(entries = 1)
    scb.step(Seq(Request(valid = true, addr = 0x3000, data = 0x11, size = 1)))
    val result = scb.step(Seq(
      Request(valid = true, addr = 0x3001, data = 0x22, size = 1),
      Request(valid = true, addr = 0x3040, data = 0x33, size = 1)
    ))

    assert(result.accepted == Seq(true, false))
    assert(result.blocked == Seq(false, true))
    assert(result.lines.size == 1)
    assert(result.lines.head.mask == BigInt(3))
    assert(result.lines.head.data.slice(0, 2) == Seq(0x11, 0x22))
  }

  test("split store fragments allocate and wake two adjacent cachelines in lane order") {
    val scb = new Model(entries = 2)
    val result = scb.step(Seq(
      Request(valid = true, addr = 0x403e, data = BigInt("7788", 16), size = 2),
      Request(valid = true, addr = 0x4040, data = BigInt("112233445566", 16), size = 6)
    ))

    assert(result.accepted == Seq(true, true))
    assert(result.lines.map(_.lineAddr) == Seq(BigInt(0x4000), BigInt(0x4040)))
    assert(result.lines.head.mask == (BigInt(3) << 62))
    assert(result.lines(1).mask == BigInt("3f", 16))
    assert(result.lines.head.data.slice(62, 64) == Seq(0x88, 0x77))
    assert(result.lines(1).data.slice(0, 6) == Seq(0x66, 0x55, 0x44, 0x33, 0x22, 0x11))
  }

  test("Chisel SCBCommitIngress elaborates with line entries and wakeup boundary IO") {
    val sv = ChiselStage.emitSystemVerilog(new SCBCommitIngress(stqEntries = 8, scbEntries = 4, requestCount = 4))

    assert(sv.contains("module SCBCommitIngress"))
    assert(sv.contains("io_acceptedMask"))
    assert(sv.contains("io_blockedMask"))
    assert(sv.contains("io_wakeups_0_byteMask"))
    assert(sv.contains("io_entries_0_byteMask"))
  }
}
