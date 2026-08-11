package linxcore.top

import chisel3._
import chisel3.util.{Decoupled, Mux1H, PopCount}
import linxcore.params.CoreParams
import linxcore.top.interface.TracePacket

class TracePrefixPackerIO(val p: CoreParams, val sourceCount: Int)
    extends Bundle {
  val in = Flipped(Vec(sourceCount, Decoupled(new TracePacket(p))))
  val out = Decoupled(new TracePacket(p))
  val dropped = Output(UInt(64.W))
}

/** Nonbackpressured observation packer with deterministic source-prefix order. */
class TracePrefixPacker(val p: CoreParams, val sourceCount: Int) extends Module {
  require(sourceCount > 0)
  val io = IO(new TracePrefixPackerIO(p, sourceCount))
  io.in.foreach(_.ready := true.B)
  private val events = for {
    source <- io.in
    lane <- 0 until p.dtu.traceWidth
  } yield (source.valid && lane.U < source.bits.count, source.bits.entries(lane))
  private val valid = VecInit(events.map(_._1))
  private val ordinal = events.indices.map { index =>
    if (index == 0) 0.U else PopCount(valid.take(index))
  }
  private val count = PopCount(valid)
  io.out.valid := count.orR
  io.out.bits := 0.U.asTypeOf(io.out.bits)
  io.out.bits.count := Mux(count > p.dtu.traceWidth.U,
    p.dtu.traceWidth.U, count)
  for (lane <- 0 until p.dtu.traceWidth) {
    val matches = events.indices.map { index =>
      valid(index) && ordinal(index) === lane.U
    }
    io.out.bits.entries(lane) := Mux1H(matches, events.map(_._2))
  }
  io.dropped := Mux(count > p.dtu.traceWidth.U,
    count - p.dtu.traceWidth.U, 0.U)
}
