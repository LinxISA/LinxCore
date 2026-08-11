package linxcore.dtu

import chisel3._
import chisel3.util.Decoupled
import linxcore.params.CoreParams
import linxcore.top.interface.TracePacket

class TraceExportIO(val p: CoreParams) extends Bundle {
  val in = Flipped(Decoupled(new TracePacket(p)))
  val out = Decoupled(new TracePacket(p))
  val accepted = Output(UInt(64.W))
  val dropped = Output(UInt(64.W))
}

/** One-slot, loss-tolerant trace observer.
  *
  * Input is always accepted. A packet arriving while the retained packet is
  * externally stalled is counted and dropped, leaving the visible packet
  * stable until its consumer accepts it.
  */
class TraceExport(val p: CoreParams) extends Module {
  val io = IO(new TraceExportIO(p))

  private val occupied = RegInit(false.B)
  private val packet = Reg(new TracePacket(p))

  io.in.ready := true.B
  io.out.valid := occupied
  io.out.bits := packet
  io.accepted := Mux(io.in.fire && (!occupied || io.out.ready),
    io.in.bits.count, 0.U)
  io.dropped := Mux(io.in.fire && occupied && !io.out.ready,
    io.in.bits.count, 0.U)

  when(io.in.fire && (!occupied || io.out.ready)) {
    packet := io.in.bits
    occupied := true.B
  }.elsewhen(io.out.fire) {
    occupied := false.B
  }
}
