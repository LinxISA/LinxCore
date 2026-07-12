package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object LoadRefillTransportReference {
  sealed trait Source
  case object Miss extends Source
  case object External extends Source
  final case class Packet(source: Source, lineAddr: BigInt)

  final class QueueModel(val depth: Int) {
    require(depth > 1)
    private var packets = Vector.empty[Packet]

    def count: Int = packets.size
    def head: Option[Packet] = packets.headOption
    def clear(): Unit = packets = Vector.empty

    def cycle(
        miss: Option[BigInt],
        external: Option[BigInt],
        outReady: Boolean,
        hold: Boolean = false): (Boolean, Boolean, Option[Packet]) = {
      if (hold) return (false, false, None)
      val out = if (outReady) packets.headOption else None
      if (out.nonEmpty) packets = packets.tail
      val capacity = depth - packets.size
      val missAccepted = miss.nonEmpty && capacity >= 1
      val externalAccepted = external.nonEmpty && capacity >= (if (missAccepted) 2 else 1)
      if (missAccepted) packets :+= Packet(Miss, miss.get)
      if (externalAccepted) packets :+= Packet(External, external.get)
      (missAccepted, externalAccepted, out)
    }
  }
}

class LoadRefillTransportSpec extends AnyFunSuite {
  import LoadRefillTransportReference._

  test("simultaneous ingress retains miss then external order") {
    val q = new QueueModel(4)
    val accepted = q.cycle(Some(0x1000), Some(0x2000), outReady = false)
    assert(accepted._1 && accepted._2 && q.count == 2)
    assert(q.cycle(None, None, outReady = true)._3.contains(Packet(Miss, 0x1000)))
    assert(q.cycle(None, None, outReady = true)._3.contains(Packet(External, 0x2000)))
  }

  test("same-cycle dequeue creates capacity for two ingress packets") {
    val q = new QueueModel(4)
    Seq(0x1000, 0x2000, 0x3000).foreach { line =>
      assert(q.cycle(None, Some(line), outReady = false)._2)
    }
    val result = q.cycle(Some(0x4000), Some(0x5000), outReady = true)
    assert(result._3.contains(Packet(External, 0x1000)))
    assert(result._1 && result._2 && q.count == 4)
  }

  test("hold preserves resident work and hard clear removes it") {
    val q = new QueueModel(4)
    q.cycle(Some(0x1000), None, outReady = false)
    val held = q.cycle(Some(0x2000), Some(0x3000), outReady = true, hold = true)
    assert(!held._1 && !held._2 && held._3.isEmpty)
    assert(q.count == 1 && q.head.contains(Packet(Miss, 0x1000)))
    q.clear()
    assert(q.count == 0)
  }

  test("refill transport depth is independent of LIQ and miss capacity") {
    val q = new QueueModel(2)
    assert(q.cycle(Some(0x1000), Some(0x2000), outReady = false)._2)
    assert(q.count == 2)
    assert(!q.cycle(Some(0x3000), None, outReady = false)._1)
  }

  test("Chisel LoadRefillTransport elaborates dual ingress and provenance") {
    val sv = ChiselStage.emitSystemVerilog(new LoadRefillTransport(entries = 4))
    assert(sv.contains("module LoadRefillTransport"))
    assert(sv.contains("io_missReady"))
    assert(sv.contains("io_externalReady"))
    assert(sv.contains("io_dualIngressAccepted"))
    assert(sv.contains("io_outFromMissQueue"))
    assert(sv.contains("io_validMask"))
  }
}
